package com.novelcharacter.app.data.repository

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.util.DuelCounterRelations
import com.novelcharacter.app.util.DuelPairing
import com.novelcharacter.app.util.DuelRating
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.DuelScoreIndex
import com.novelcharacter.app.util.DuelStandings

/**
 * 대결(B-104)의 **쓰기 경로와 읽기 조립** — 저장된 행과 순수 계층 사이의 유일한 통로.
 *
 * 순수 계층 셋은 참가자를 `Long` id로 보고 저장은 코드로 한다(R-1). 그 사이의 해석은
 * [DuelRecords]가 pure로 갖고 있고, 이 저장소는 **Room 호출과 트랜잭션만** 맡는다 —
 * 판단이 여기 들어오면 이 앱의 로컬 검증이 그것을 볼 수 없기 때문이다.
 *
 * ## 삭제는 반드시 이 저장소를 거친다
 * 축을 지우면 그 아래 판·처분이 FK CASCADE로 함께 죽는다. 수만 번의 누름이 되돌릴 길 없이
 * 사라지는 것이라, [deleteAxis]가 **지우기 전에** 휴지통 스냅샷을 남긴다(R-4).
 * DAO를 직접 불러 지우면 그 보장이 통째로 빠진다.
 */
class DuelRepository(private val db: AppDatabase) {

    /**
     * 한 축의 현재 상태 — 점수·대기열·상성이 **같은 해석 하나**에서 나온다.
     *
     * @property missingParticipants 판에는 있으나 지금 살아 있지 않은 참가자 수.
     *   `fit.orphanMatches`와 함께 *"지워진 캐릭터의 판은 점수에서 빠져 있다"*를 말하는 재료다.
     */
    data class AxisState(
        val axis: DuelAxis,
        val fit: DuelRating.Fit,
        val report: DuelCounterRelations.Report,
        val plan: DuelPairing.Plan,
        val records: DuelRecords.Resolved,
        val missingParticipants: Int
    )

    suspend fun axes(universeId: Long): List<DuelAxis> =
        db.duelAxisDao().getByUniverseList(universeId)

    suspend fun axesForTarget(universeId: Long, targetType: String): List<DuelAxis> =
        db.duelAxisDao().getByUniverseAndTarget(universeId, targetType)

    suspend fun axis(id: Long): DuelAxis? = db.duelAxisDao().getById(id)

    suspend fun saveAxis(axis: DuelAxis): DuelAxis = db.withTransaction {
        if (axis.id == 0L) axis.copy(id = db.duelAxisDao().insert(axis))
        else { db.duelAxisDao().update(axis); axis }
    }

    /**
     * 축 삭제 — 판·처분이 FK CASCADE로 함께 사라지므로 **먼저 휴지통에 담는다**.
     *
     * @param trash 이 삭제 작업의 휴지통 인스턴스(인스턴스 하나 = 작업 하나 — R-3).
     * @return 함께 사라진 판 수. 호출부는 삭제 전에 이 규모를 사용자에게 알릴 것(R-4).
     */
    suspend fun deleteAxis(axis: DuelAxis, trash: TrashRepository): Int = db.withTransaction {
        val matches = db.duelMatchDao().countByAxis(axis.id)
        trash.snapshotDuelAxis(axis)
        db.duelAxisDao().delete(axis)
        matches
    }

    /** 이 참가자가 이 축에서 치른 판 수 — 삭제·정리가 결과를 먼저 알릴 때 쓴다. */
    suspend fun matchCountFor(axisId: Long, participantCode: String): Int =
        db.duelMatchDao().countForParticipant(axisId, participantCode)

    // ──────────────────────────────────────────────────────────────────────
    // 한 판 기록하기 / 되돌리기
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 한 판을 기록한다.
     *
     * @param winnerCode 이긴 쪽. **null이면 무승부**다. 두 참가자 중 어느 쪽도 아니면 저장하지
     *   않고 null을 돌려준다 — 잘못된 입력을 조용히 받아 두면 적합이 나중에 '깨진 판'으로
     *   세는 수밖에 없다(검증은 들어오는 자리에서 하는 것이 싸다).
     * @param groupId k지선다 한 화면이 낸 판들을 묶는 값. 되돌리기가 그 단위로 돈다.
     */
    suspend fun record(
        axisId: Long,
        aCode: String,
        bCode: String,
        winnerCode: String?,
        groupId: String? = null
    ): DuelMatch? {
        if (aCode.isBlank() || bCode.isBlank() || aCode == bCode) return null
        if (winnerCode != null && winnerCode != aCode && winnerCode != bCode) return null
        val row = DuelMatch(
            axisId = axisId,
            aCode = aCode,
            bCode = bCode,
            winnerCode = winnerCode,
            groupId = groupId
        )
        return row.copy(id = db.duelMatchDao().insert(row))
    }

    /**
     * 층 B ①(*"잘못 눌렀다"*)의 실행 — 그 판을 지운다.
     *
     * 점수는 **다시 적합하면 그것이 정확한 답**이다(BT는 결과 집합의 함수다). Elo였다면
     * 뒤의 모든 판을 다시 계산해야 했고, 그 성질이 이 모델을 고른 근거 셋 중 하나였다.
     */
    suspend fun undo(match: DuelMatch) = db.duelMatchDao().delete(match)

    /** 한 화면이 낸 판들을 통째로 되돌린다(k지선다). 반쪽 되돌리기를 만들지 않는다. */
    suspend fun undoGroup(groupId: String) = db.duelMatchDao().deleteByGroup(groupId)

    /**
     * 기록 화면의 손편집 — **승자만 고친다.**
     *
     * @param winnerCode 이긴 쪽. null이면 무승부다. 두 참가자 중 어느 쪽도 아니면 고치지 않고
     *   null을 돌려준다 — [record]와 같은 규칙이다(검증은 들어오는 자리에서 하는 것이 싸다).
     *   **깨진 판을 고치는 경우는 예외로 받는다**: 이미 저장된 값이 깨져 있어 사용자가 그것을
     *   바로잡으러 온 것이라, 새 값이 옳으면 통과시켜야 한다.
     */
    suspend fun updateWinner(match: DuelMatch, winnerCode: String?): DuelMatch? {
        if (winnerCode != null && winnerCode != match.aCode && winnerCode != match.bCode) return null
        if (winnerCode == match.winnerCode) return match
        val updated = match.copy(winnerCode = winnerCode)
        db.duelMatchDao().update(updated)
        return updated
    }

    /** 기록 화면이 보는 최근 판 — 상한을 받는다(이 표는 수만 행이 될 수 있다). */
    suspend fun recentMatches(axisId: Long, limit: Int): List<DuelMatch> =
        db.duelMatchDao().getRecent(axisId, limit)

    suspend fun matchCount(axisId: Long): Int = db.duelMatchDao().countByAxis(axisId)

    /** 가장 최근 판 — 화면의 '방금 그거 취소'가 집는 대상. */
    suspend fun lastMatch(axisId: Long): DuelMatch? =
        db.duelMatchDao().getRecent(axisId, 1).firstOrNull()

    // ──────────────────────────────────────────────────────────────────────
    // 층 B의 처분
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 층 B의 ②·③ 처분을 남긴다. 같은 관계에 다시 판정하면 **덮어쓴다**(②를 ③으로 굳히는
     * 흐름이 확정이라, 행이 둘로 늘면 어느 쪽이 현행인지 알 수 없다).
     *
     * @param members 뜻이 있는 순서 — 천적은 `[센 쪽, 잡는 쪽]`, 순환은 이기는 차례.
     * @return 저장한 처분. 참가자가 둘 미만이면 판정할 관계가 없으므로 null이다.
     */
    suspend fun recordVerdict(
        axisId: Long,
        members: List<String>,
        kind: String
    ): DuelCounterVerdict? {
        val cleaned = members.filter { it.isNotBlank() }.distinct()
        val shape = DuelRecords.shapeOf(cleaned) ?: return null
        val row = DuelCounterVerdict(
            axisId = axisId,
            kind = kind,
            shape = shape,
            memberCodes = DuelRecords.encodeMembers(cleaned),
            memberKey = DuelRecords.memberKey(cleaned)
        )
        return row.copy(id = db.duelCounterVerdictDao().upsert(row))
    }

    /** 처분을 물린다 — 잘못 눌렀거나 생각이 바뀐 경우. 되돌리면 그 짝이 점수 적합으로 돌아온다. */
    suspend fun clearVerdict(verdict: DuelCounterVerdict) =
        db.duelCounterVerdictDao().delete(verdict)

    suspend fun verdicts(axisId: Long): List<DuelCounterVerdict> =
        db.duelCounterVerdictDao().getByAxis(axisId)

    // ──────────────────────────────────────────────────────────────────────
    // 읽기 — 점수·상성·대기열을 한 번에
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 축의 현재 상태를 낸다. **2단 적합이 권장 진입점**이라 그것을 쓴다 — 한 번만 적합하면
     * 천적 관계 자체가 점수를 오염시켜 잔차를 지운다(설계 3장).
     *
     * @param participantCodes 지금 살아 있는 참가자의 코드. 캐릭터 축이면 세계관의 캐릭터
     *   코드이고, 이미지 축이면 그 캐릭터의 이미지 경로다 — **무엇이 참가자인가는 호출부가
     *   정한다**(축의 종류를 아는 것이 그쪽이다).
     *
     * **따뜻한 시작(직전 강함에서 출발)은 여기 넣지 않았다.** 순위표가 쓰는 점수는 2차 적합의
     * 것인데([DuelCounterRelations.analyzeTwoPass]가 그렇게 돌려준다), 씨앗을 받으려면 적합을
     * 한 번 더 돌려야 하고 그러면 **점수와 상성 보고가 서로 다른 적합에서 나온다.** 두 자리가
     * 갈라지는 것이 이 설계가 파생값을 저장하지 않는 이유 그 자체다. 화면 슬라이스가 이
     * 최적화를 원하면 `analyzeTwoPass`가 씨앗을 받도록 순수 계층을 먼저 열 것.
     */
    suspend fun stateOf(
        axis: DuelAxis,
        participantCodes: Collection<String>,
        pairingOptions: DuelPairing.Options = DuelPairing.Options(),
        counterOptions: DuelCounterRelations.Options = DuelCounterRelations.Options(),
        ratingOptions: DuelRating.Options = DuelRating.Options()
    ): AxisState {
        val records = DuelRecords.resolve(
            participantCodes,
            db.duelMatchDao().getByAxis(axis.id),
            db.duelCounterVerdictDao().getByAxis(axis.id)
        )
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs,
            ratingOptions = ratingOptions,
            options = counterOptions
        )
        val plan = DuelPairing.plan(
            fit = twoPass.fit,
            recheckTargets = twoPass.report.involvedIds,
            options = pairingOptions
        )
        return AxisState(
            axis = axis,
            fit = twoPass.fit,
            report = twoPass.report,
            plan = plan,
            records = records,
            missingParticipants = records.missingParticipants
        )
    }

    /**
     * **대결 밖이 쓰는 점수표** (B-117) — 캐릭터 목록 정렬과 통계 순위가 이것을 든다.
     *
     * [stateOf]와 **같은 진입점**([DuelCounterRelations.analyzeTwoPass])을 타는 것이 요점이다.
     * 다른 경로로 점수를 내면 같은 캐릭터가 순위표와 목록에서 다른 수를 갖는다 —
     * 설계 4장이 파생값을 저장하지 않는 이유가 그것이고, 저장하지 않는 대신 **계산의 출구를
     * 하나로 묶는 것**이 [DuelScoreIndex]의 몫이다.
     *
     * 다른 점은 하나뿐이다: **짝 고르기 계획을 세우지 않는다.** 대기열은 대결 화면의 것이고
     * 여기서는 아무도 그것을 읽지 않는데, 계획은 짝 전수를 훑느라 목표 규모 위쪽에서 147ms를
     * 먹는다(설계 6장의 실측표).
     *
     * @param participantCodes 지금 살아 있는 참가자의 코드 — **축 전체의 참가자를 넘길 것.**
     *   목록이 필터·검색으로 좁혀져 있어도 좁힌 집합으로 적합하면 안 된다. BT는 결과 집합의
     *   함수라 참가자를 빼면 점수 자체가 달라지고, 그러면 **필터를 걸 때마다 순위가 흔들린다.**
     */
    suspend fun scoresOf(
        axis: DuelAxis,
        participantCodes: Collection<String>,
        counterOptions: DuelCounterRelations.Options = DuelCounterRelations.Options(),
        ratingOptions: DuelRating.Options = DuelRating.Options()
    ): DuelScoreIndex.AxisScores {
        val matches = db.duelMatchDao().getByAxis(axis.id)
        val records = DuelRecords.resolve(
            participantCodes,
            matches,
            db.duelCounterVerdictDao().getByAxis(axis.id)
        )
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs,
            ratingOptions = ratingOptions,
            options = counterOptions
        )
        return DuelScoreIndex.of(
            axisId = axis.id,
            axisCode = axis.code,
            axisName = axis.name,
            universeId = axis.universeId,
            rows = DuelStandings.rows(twoPass.fit, twoPass.report, records),
            fit = twoPass.fit,
            missingParticipants = records.missingParticipants,
            scanned = matches.size
        )
    }

    /** 코드로 축을 집는다 — 프리셋·엑셀이 축을 가리키는 방법이 코드다(R-1). */
    suspend fun axisByCode(code: String): DuelAxis? = db.duelAxisDao().getByCode(code)

    /**
     * 등급 반영 — 대결 순위에서 나온 등급을 **필드 값으로 써 넣는다** (B-113, 설계 4-3).
     *
     * 파생 표시가 아니라 명시적 반영을 고른 이유가 이 함수의 모양이다: 값이 보통 필드 값으로
     * 실리므로 통계·목록·엑셀이 **공짜로** 그것을 읽는다(원칙 05 — 새 코드 0줄).
     *
     * ## 한 트랜잭션에 셋이 들어간다
     *
     * 1. **덮이는 값의 스냅샷** — 편집 직전 백업(일괄 편집 B-83과 같은 자리)이라 휴지통에서
     *    통째로 되돌린다. **값이 실제로 덮이는 캐릭터만** 담는다(빈 칸을 채우는 것은 잃는
     *    것이 없어 백업할 것도 없다 — 담으면 백업이 인원에 비례해 커진다, R-10).
     * 2. **값 쓰기.**
     * 3. **흔적 쓰기**([DuelGradeRef.LastApplied]) — 원본 쓰기와 파생 기록이 한 몸이다(R-30의
     *    정신). 갈라 두면 값은 들어갔는데 흔적이 없는 상태가 생기고, 그때 다음 반영은
     *    **직전에 자기가 쓴 값을 '손값'으로 읽어** 기본 체크를 풀어 버린다.
     *
     * @param assignments 캐릭터 code → 배정된 라벨. **점수 보유자 전원**이 온다(체크된 것만이
     *   아니다 — 아래 [selected] 참조).
     * @param selected 사용자가 미리보기에서 체크한 캐릭터 code. **이들에게만 값을 쓴다.**
     * @return 실제로 값을 쓴 캐릭터 수.
     */
    suspend fun applyGrades(
        fieldDefinitionId: Long,
        assignments: Map<String, String>,
        selected: Set<String>,
        appliedAt: Long
    ): Int {
        if (assignments.isEmpty()) return 0
        // 인스턴스 하나 = 작업 하나(R-3) — 이 반영이 만든 백업이 정리에서 보호된다.
        val trash = TrashRepository(db, TrashSnapshot.KIND_EDIT_BACKUP)
        var written = 0
        db.withTransaction {
            val field = db.fieldDefinitionDao().getFieldById(fieldDefinitionId) ?: return@withTransaction
            val characters = db.characterDao().getCharactersByUniverseList(field.universeId)
            val byCode = characters.associateBy { it.code }
            // 값은 한 번에 읽는다 — 캐릭터마다 물으면 인원만큼 왕복이 늘고, 그것이 목표 규모에서
            // 이 트랜잭션을 가장 길게 잡는 자리가 된다('받쳐주는 확장성').
            val currentByCharacter = db.characterFieldValueDao()
                .getValuesForCharacters(characters.map { it.id })
                .filter { it.fieldDefinitionId == fieldDefinitionId }
                .associate { it.characterId to it.value }

            for (code in selected) {
                val label = assignments[code] ?: continue
                val character = byCode[code] ?: continue
                val current = currentByCharacter[character.id].orEmpty()
                if (current == label) continue
                if (current.isNotBlank()) {
                    // 덮이는 값만 백업한다 — 빈 칸을 채우는 것은 잃는 것이 없고, 전원을 담으면
                    // 백업 한 행이 인원에 비례해 커진다(R-10). 되돌리기 범위를 필드값으로 좁혀
                    // 두면 복원이 캐릭터 행까지 교체하지 않는다(일괄 편집이 세운 그 규칙).
                    trash.snapshotCharacter(
                        character,
                        imagePaths = emptyList(),
                        kind = TrashSnapshot.KIND_EDIT_BACKUP,
                        revertScope = listOf(RestoreModes.SCOPE_FIELD_VALUES)
                    )
                }
                db.characterFieldValueDao().upsert(
                    CharacterFieldValue(
                        characterId = character.id,
                        fieldDefinitionId = fieldDefinitionId,
                        value = label
                    )
                )
                written++
            }

            if (written > 0) {
                val spec = DuelGradeRef.fromConfig(field.config)
                if (spec != null) {
                    // **흔적은 배정 전체를 담는다 — 체크된 것만이 아니다.**
                    //
                    // 값이 이미 배정과 같아 쓸 일이 없던 캐릭터는 미리보기에 줄조차 서지 않는데,
                    // 그들을 빼면 다음 반영이 **직전에 자기가 쓴 값을 '손값'으로 읽어** 기본
                    // 체크를 풀어 버린다(Q1이 없애려던 그 마찰이 되살아난다).
                    // 체크를 끈 캐릭터를 담아도 거짓이 되지 않는다 — 분류는 흔적을 **현재 값과
                    // 대조**하므로, 값이 그대로인 손값은 여전히 손값으로 갈린다.
                    val trace = DuelGradeRef.LastApplied(appliedAt, assignments.filterKeys { it in byCode })
                    db.fieldDefinitionDao().update(
                        field.copy(config = DuelGradeRef.write(field.config, spec.copy(lastApplied = trace)))
                    )
                }
            }
        }
        trash.pruneIfNeeded()
        return written
    }
}
