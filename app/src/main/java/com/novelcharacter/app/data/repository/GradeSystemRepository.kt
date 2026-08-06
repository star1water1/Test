package com.novelcharacter.app.data.repository

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.GradeSystem
import com.novelcharacter.app.data.model.GradeSystemRef
import com.novelcharacter.app.util.DuelGradeAssign

/**
 * 등급 체계(U-1)의 쓰기 경로 — 저장·삭제와 **참조 필드 전파**가 한 몸이다.
 *
 * 필드 config에는 실효 표가 물질화되어 있으므로([GradeSystemRef]), 체계를 고치고 참조
 * 필드를 다시 쓰지 않으면 "한 곳을 고치면 전부 반영"이라는 참조의 약속이 조용히 깨진다.
 * 그래서 저장·삭제는 반드시 이 저장소를 거치고, 전파는 같은 트랜잭션 안에서 끝난다.
 *
 * 참조 필드 조회는 **전 entityType**이다(R-29) — 사건·작품 필드도 GRADE 타입이 될 수 있고,
 * 캐릭터 필드만 전파하면 나머지 종류의 실효 표가 낡은 채 남는다.
 */
class GradeSystemRepository(private val db: AppDatabase) {

    /**
     * @property duelCutsMerged 전파가 **대결 등급 산정의 컷을 잃게 만든 라벨들**(B-113).
     *   체계에서 등급을 지우면 그 등급의 구간은 다음 등급에 합쳐지는데, 그 사실을 여기서
     *   돌려주지 않으면 사용자는 **등급 하나를 지웠을 뿐인데 다른 필드의 배정 폭이 바뀐 것을**
     *   알 길이 없다(변수 제어 — 조용히 좁히지 않는다).
     */
    data class SaveResult(
        val system: GradeSystem,
        val propagatedFields: Int,
        val duelCutsMerged: List<String> = emptyList()
    )

    /** [propagate]의 산출 — 다시 쓴 필드 수와, 대결 컷에서 밀려난 라벨들. */
    data class PropagateResult(val changedFields: Int, val duelCutsMerged: List<String>)

    suspend fun getByUniverse(universeId: Long): List<GradeSystem> =
        db.gradeSystemDao().getByUniverseList(universeId)

    /** 이 체계를 참조하는 GRADE 필드 전부 (전 entityType — R-29). */
    suspend fun referencingFields(system: GradeSystem): List<FieldDefinition> =
        db.fieldDefinitionDao().getFieldsByUniverseAllTypes(system.universeId)
            .filter { it.type == FieldType.GRADE.name && GradeSystemRef.codeFromConfig(it.config) == system.code }

    /**
     * 체계 저장(신규/수정) + 참조 필드 전파.
     *
     * @param renames 이번 편집에서 라벨이 바뀐 것(옛 라벨 → 새 라벨). 재정의가 개명을
     *   따라가게 한다 — 넘기지 않으면 개명된 라벨의 재정의가 기본 숫자로 조용히 되돌아간다.
     * @return 저장된 체계(신규면 id 채워짐)와 실효 표를 다시 쓴 필드 수.
     */
    suspend fun saveSystem(system: GradeSystem, renames: Map<String, String> = emptyMap()): SaveResult =
        db.withTransaction {
            val saved = if (system.id == 0L) {
                system.copy(id = db.gradeSystemDao().insert(system))
            } else {
                db.gradeSystemDao().update(system)
                system
            }
            val propagated = propagate(saved, renames)
            SaveResult(saved, propagated.changedFields, propagated.duelCutsMerged)
        }

    /**
     * 참조 필드들의 실효 표를 현재 체계 기준으로 다시 쓴다. 호출 시점에 이미 트랜잭션 안이면
     * Room 트랜잭션이 중첩을 허용하므로 그대로 합류한다.
     *
     * @return config가 실제로 바뀐 필드 수.
     */
    suspend fun propagate(system: GradeSystem, renames: Map<String, String> = emptyMap()): PropagateResult {
        val systemGrades = GradeSystemRef.gradesFromJson(system.gradesJson)
        var changed = 0
        val merged = LinkedHashSet<String>()
        for (field in referencingFields(system)) {
            val overrides = GradeSystemRef.renameOverrides(
                GradeSystemRef.overridesFromConfig(field.config), renames
            )
            val materialized = GradeSystemRef.materialize(field.config, system.code, systemGrades, overrides)
            val next = followDuelGradeCuts(field.config, materialized, systemGrades, renames, merged)
            if (next != field.config) {
                db.fieldDefinitionDao().update(field.copy(config = next))
                changed++
            }
        }
        return PropagateResult(changed, merged.toList())
    }

    /**
     * 실효 표를 다시 쓴 뒤 **대결 등급 산정의 컷도 함께 따라가게 한다** (B-113, 설계 4-2).
     *
     * 재정의가 개명을 따라가는 것([GradeSystemRef.renameOverrides])과 **같은 자리·같은
     * 이유**다: 사용자가 한 것은 등급 이름 바꾸기인데, 따라가지 않으면 컷이 실재하지 않는
     * 라벨을 가리켜 반영이 통째로 막힌다. 라벨 증감은 [DuelGradeAssign.reconcile]이 **기존
     * 배정을 보존하는 방향으로** 맞춘다.
     *
     * **[DuelGradeRef.LastApplied]는 개명하지 않는다.** 그 배정표는 *"직전 반영이 캐릭터에
     * 써 넣은 문자열"*의 기록인데, 저장된 캐릭터 값은 개명을 따라가지 않기 때문이다(체계에서
     * 라벨을 바꿔도 캐릭터 값은 그대로 — SELECT 옵션과 같은 규칙). 배정표만 새 이름으로
     * 바꾸면 **둘이 어긋나 직전 반영값이 전부 '손값'으로 뒤집힌다**(그러면 기본 체크가 풀려
     * 2차 반영에서 전 행을 손으로 켜야 한다).
     */
    private fun followDuelGradeCuts(
        originalConfig: String,
        materialized: String,
        systemGrades: Map<String, Double>,
        renames: Map<String, String>,
        mergedOut: MutableSet<String>
    ): String {
        val spec = DuelGradeRef.fromConfig(materialized) ?: return materialized
        val oldLabels = DuelGradeAssign.orderedLabels(GradeSystemRef.gradesFromConfig(originalConfig))
        val newLabels = DuelGradeAssign.orderedLabels(systemGrades)
        val renamed = DuelGradeRef.renameCuts(spec.cuts, renames)
        val reconciled = DuelGradeAssign.reconcile(
            renamed,
            // 옛 라벨도 개명을 거쳐야 "누가 마지막이었는가"가 새 이름으로 견줘진다.
            oldLabels.map { renames[it] ?: it },
            newLabels
        )
        mergedOut.addAll(reconciled.merged)
        if (reconciled.cuts == spec.cuts) return materialized
        return DuelGradeRef.write(materialized, spec.copy(cuts = reconciled.cuts))
    }

    /**
     * 체계 삭제 — 참조 필드는 **독자 표로 내려앉는다**(실효 표가 남아 필드는 그대로 동작한다).
     * 스냅샷은 강등 **전에** 남긴다: 복원이 "삭제 시점에 누가 참조했는가"를 알아야 다시 이을 수
     * 있다. 호출부는 삭제 전에 참조 수를 고지할 것(R-4 — 파괴적 동작은 결과를 먼저 알린다).
     *
     * @param trash 이 삭제 작업의 휴지통 인스턴스(인스턴스 하나 = 작업 하나).
     * @return 독자 표로 전환된 필드 수.
     */
    suspend fun deleteSystem(system: GradeSystem, trash: TrashRepository): Int =
        db.withTransaction {
            val refs = referencingFields(system)
            trash.snapshotGradeSystem(system, refs)
            for (field in refs) {
                // **대결 등급 산정([DuelGradeRef])은 걷어내지 않는다** — 여기는 세계관 경계가
                // 아니다. 체계가 사라져도 실효 표는 config에 남고 대결 축도 그대로라, 컷은
                // 어제와 똑같이 성립한다. 경계를 넘는 복사에서만 걷어낸다
                // ([com.novelcharacter.app.data.model.FieldConfigTransfer]).
                db.fieldDefinitionDao().update(field.copy(config = GradeSystemRef.demote(field.config)))
            }
            db.gradeSystemDao().delete(system)
            refs.size
        }
}
