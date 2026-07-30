package com.novelcharacter.app.data.repository

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.GradeSystem
import com.novelcharacter.app.data.model.GradeSystemRef

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

    data class SaveResult(val system: GradeSystem, val propagatedFields: Int)

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
            SaveResult(saved, propagate(saved, renames))
        }

    /**
     * 참조 필드들의 실효 표를 현재 체계 기준으로 다시 쓴다. 호출 시점에 이미 트랜잭션 안이면
     * Room 트랜잭션이 중첩을 허용하므로 그대로 합류한다.
     *
     * @return config가 실제로 바뀐 필드 수.
     */
    suspend fun propagate(system: GradeSystem, renames: Map<String, String> = emptyMap()): Int {
        val systemGrades = GradeSystemRef.gradesFromJson(system.gradesJson)
        var changed = 0
        for (field in referencingFields(system)) {
            val overrides = GradeSystemRef.renameOverrides(
                GradeSystemRef.overridesFromConfig(field.config), renames
            )
            val next = GradeSystemRef.materialize(field.config, system.code, systemGrades, overrides)
            if (next != field.config) {
                db.fieldDefinitionDao().update(field.copy(config = next))
                changed++
            }
        }
        return changed
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
                db.fieldDefinitionDao().update(field.copy(config = GradeSystemRef.demote(field.config)))
            }
            db.gradeSystemDao().delete(system)
            refs.size
        }
}
