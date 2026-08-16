package com.novelcharacter.app.data.repository

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.util.DuelAxisTransfer
import com.novelcharacter.app.util.DuelCounterRelations
import com.novelcharacter.app.util.DuelImageParticipants
import com.novelcharacter.app.util.DuelImageRoster
import com.novelcharacter.app.util.DuelPairing
import com.novelcharacter.app.util.DuelRating
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.DuelScoreIndex
import com.novelcharacter.app.util.DuelStandings
import com.novelcharacter.app.util.SqlInChunks

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
        val missingParticipants: Int,
        /**
         * **이미지 축에서만** 0이 아니다 (B-175) — 이 캐릭터의 그림과 남의 그림이 함께 적힌
         * 판 수다. 어느 캐릭터의 대결도 아니라 점수에 들지 않으며, 화면이 그 사실을 말한다.
         */
        val crossCharacterMatches: Int = 0
    )

    suspend fun axes(universeId: Long): List<DuelAxis> =
        db.duelAxisDao().getByUniverseList(universeId)

    suspend fun axesForTarget(universeId: Long, targetType: String): List<DuelAxis> =
        db.duelAxisDao().getByUniverseAndTarget(universeId, targetType)

    suspend fun axis(id: Long): DuelAxis? = db.duelAxisDao().getById(id)

    /**
     * 축을 저장한다. **기준 축([DuelAxis.isBasisAxis])의 유일성을 여기서 지킨다.**
     *
     * 같은 트랜잭션에서 형제 축의 표식을 내리는 것이 요점이다 — 갈라 두면 *"둘 다 켜짐"*이나
     * *"둘 다 꺼짐"*인 순간이 생기고, 그 사이에 대표 추첨이 돌면 사용자가 지정한 적 없는
     * 축을 따르거나 아무 축도 따르지 않는다.
     *
     * **판정이 [DuelAxis.isImageBasis]인 것이 요점이다** — `isBasisAxis`만 보면 캐릭터 축에
     * 켜져 들어온 *아무 일도 하지 않아야 할* 표식이 형제를 내리게 된다. 그러면 **축 목록을
     * 드래그해 순서만 바꿔도**(이 함수가 축마다 불린다) 살아 있는 이미지 축의 기준이 풀린다 —
     * 사용자가 한 일과 결과 사이에 아무 연결도 없어 원인을 찾을 수 없는 부류다.
     */
    suspend fun saveAxis(axis: DuelAxis): DuelAxis = db.withTransaction {
        val saved = if (axis.id == 0L) axis.copy(id = db.duelAxisDao().insert(axis))
        else { db.duelAxisDao().update(axis); axis }
        if (saved.isImageBasis) {
            db.duelAxisDao().clearBasisExcept(saved.universeId, saved.targetType, saved.id)
        }
        saved
    }

    /**
     * **다른 세계관의 축을 이 세계관으로 옮긴다** (B-116) — 규칙은 [DuelAxisTransfer]가 단일 소스다.
     *
     * 이 저장소가 맡는 것은 셋뿐이다: **한 트랜잭션**(반만 들어간 상태를 남기지 않는다) ·
     * `displayOrder`를 받는 목록 끝에 이어 붙이기 · **새 code 발급**. 무엇이 새로 나야
     * 하는지의 판단은 순수 계층에 있고, 그래서 그 판단이 이 저장소의 시험 밖에서도 재어진다.
     *
     * **이름이 겹치는 축은 넣지 않는다** — 계획을 짤 때 이미 걸러지지만
     * ([DuelAxisTransfer.Plan.importable]) 여기서 한 번 더 본다. 사이에 다른 화면이
     * 같은 이름의 축을 만들었을 수 있고, 그때 삽입은 유니크 위반으로 **죽는다**
     * (사용자에게는 *"가져오기가 실패했다"*로만 보이고 무엇이 겹쳤는지 알 길이 없다).
     *
     * @return 실제로 들어간 축들. 겹쳐서 빠진 것은 여기 없다 — 부르는 쪽이 고른 수와
     *   견주어 *"N개 중 M개"*를 말한다(조용히 줄이지 않는다).
     */
    suspend fun importAxes(targetUniverseId: Long, sources: List<DuelAxis>): List<DuelAxis> {
        if (sources.isEmpty()) return emptyList()
        return db.withTransaction {
            val existing = db.duelAxisDao().getByUniverseList(targetUniverseId)
            val taken = existing.map { it.targetType to it.name }.toMutableSet()
            var order = (existing.maxOfOrNull { it.displayOrder } ?: -1) + 1
            val inserted = ArrayList<DuelAxis>(sources.size)
            val now = System.currentTimeMillis()
            for (source in sources) {
                if (!taken.add(source.targetType to source.name)) continue
                val axis = DuelAxisTransfer.transfer(
                    source = source,
                    targetUniverseId = targetUniverseId,
                    displayOrder = order++,
                    code = generateEntityCode(),
                    createdAt = now
                )
                inserted.add(axis.copy(id = db.duelAxisDao().insert(axis)))
            }
            inserted
        }
    }

    /**
     * 이 세계관의 **기준 이미지 축** — 대표 추첨(ⓑ)과 걸러낼 후보(ⓒ)가 따르는 축.
     *
     * 지정이 없으면 null이고, **그것이 가장 흔한 상태다**(예외가 아니다). 소비처는 null을
     * 받으면 종전 그대로 동작한다 — 균등 추첨이고, 후보 제안은 *"기준 축이 없다"*를 말한다.
     */
    suspend fun basisImageAxis(universeId: Long): DuelAxis? =
        db.duelAxisDao().getBasisAxis(universeId, DuelAxis.TARGET_IMAGE)

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
    // 경로 재작성 추종 — 이미지 축의 빗장 (설계 13장)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **파일이 개명되면 이미지 축의 참가자 코드도 함께 옮긴다** (B-104 ⓑ·ⓒ의 빗장, R-42).
     *
     * 이미지 축의 참가자 코드는 이미지 경로다([DuelMatch.aCode]). 경로를 바꾸는 자리가 이것을
     * 부르지 않으면 **쌓아 둔 판이 통째로 고아가 되거나 남의 이미지에 붙는다** — 설계 4장 ①의
     * ⚠️가 이미지 축을 닫아 두었던 이유가 그것이고, 이 함수가 그 빗장을 연다.
     *
     * **부르는 자리는 `FolderRoundtripPrefs.recordRenames`를 부르는 자리와 같다** — 그쪽 KDoc이
     * 이미 *"경로를 바꾸는 모든 지점이 불러야 한다"*고 선언해 둔, 이 앱에서 개명의 유일한 관문이다.
     * 짝이 맞는지는 `tools/check_duel_image_codes.sh`가 기계로 본다(규약으로만 적으면 빠진다 —
     * R-31이 닷새를 빠져 있던 그 실패 형태).
     *
     * **캐릭터 축은 훑지 않는다.** `Character.code`는 개명되지 않으므로 그쪽에 이 문제가 없고,
     * 훑으면 이 앱에서 가장 큰 표를 이유 없이 한 번 더 읽는다.
     *
     * @param renames 옛 경로 → 새 경로. 빈 표면 아무것도 하지 않는다(질의조차 열지 않는다).
     * @return 무엇을 옮겼고 무엇을 못 옮겼는가. 호출부가 [FollowResult.hasCaveat]일 때
     *   사용자에게 알린다 — 조용히 넘어가면 개발 의도 2번을 어긴다.
     */
    suspend fun followImageRenames(renames: Map<String, String>): FollowResult {
        if (renames.isEmpty()) return FollowResult.NONE
        val imageAxes = db.duelAxisDao().getAllList().filter { it.isImageAxis }
        if (imageAxes.isEmpty()) return FollowResult.NONE

        var movedMatches = 0
        var movedVerdicts = 0
        var collapsed = 0
        var conflicts = 0
        for (axis in imageAxes) {
            val plan = DuelImageParticipants.plan(
                matches = db.duelMatchDao().getByAxis(axis.id),
                verdicts = db.duelCounterVerdictDao().getByAxis(axis.id),
                renames = renames
            )
            collapsed += plan.collapsedMatches
            conflicts += plan.verdictConflicts
            if (!plan.any) continue
            // 축 하나가 한 트랜잭션이다 — 전량을 한 트랜잭션에 묶으면 축이 여럿일 때
            // 한 축의 실패가 다른 축의 성공까지 되돌린다(옮길 수 있었던 것은 옮기는 편이 낫다).
            db.withTransaction {
                for (match in plan.matches) db.duelMatchDao().update(match)
                for (verdict in plan.verdicts) db.duelCounterVerdictDao().update(verdict)
            }
            movedMatches += plan.matches.size
            movedVerdicts += plan.verdicts.size
        }
        return FollowResult(movedMatches, movedVerdicts, collapsed, conflicts)
    }

    /**
     * [followImageRenames]의 결과.
     *
     * @property collapsedMatches 개명이 두 참가자를 하나로 모아 뜻을 잃은 판 수. **지우지 않았다** —
     *   적합이 '깨진 판'으로 세어 화면이 이미 알리는 자리로 흘러간다.
     * @property verdictConflicts 옮기면 정규 키가 부딪히거나 참가자가 접혀 **손대지 않은** 처분 수.
     */
    data class FollowResult(
        val movedMatches: Int,
        val movedVerdicts: Int,
        val collapsedMatches: Int,
        val verdictConflicts: Int
    ) {
        val moved: Int get() = movedMatches + movedVerdicts
        val hasCaveat: Boolean get() = collapsedMatches > 0 || verdictConflicts > 0

        companion object {
            /** 옮길 이미지 축이 없거나 개명이 없다 — 예외가 아니라 가장 흔한 결과다. */
            val NONE = FollowResult(0, 0, 0, 0)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 한 판 기록하기 / 되돌리기
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 대결 화면이 낸 판들을 기록한다 — **판 하나짜리(1:1)도 이 길이다** (B-115).
     *
     * ## 쓰는 길이 하나인 것이 요점이다
     * 종전에는 판 하나를 적는 `record`가 따로 있었다. k지선다가 들어오면서 그 옆에 묶음용을
     * 세우면 **승자 검증이 두 곳에 생기고**, 한쪽만 고쳐지는 순간 *어느 길로 들어왔는가*에
     * 따라 깨진 판이 통과한다 — 그 어긋남은 화면에 아무 표시도 내지 않고 적합의
     * `malformedMatches`에서만 뒤늦게 드러난다. 그래서 `record`를 없애고 여기로 합쳤다
     * (`k=2`가 특수한 경우가 아니라는 [DuelRound]의 뼈대와 같은 근거다).
     *
     * ## 하나라도 어긋나면 아무것도 적지 않는다
     * [record]는 판 하나를 거절하면 그만이지만, 여기서는 거절이 **반쪽 묶음**을 남긴다 —
     * `a>b`는 적히고 `a>c`는 빠지면 사용자가 한 번 누른 일이 절반만 기록된 것이고,
     * 되돌리기가 지울 대상도 절반이다. 그 상태는 화면 어디에서도 보이지 않으므로
     * **적기 전에 전부 검사하고, 하나라도 걸리면 빈 목록을 낸다**(화면이 그 사실을 말한다).
     *
     * ## 묶음 값은 판이 둘 이상일 때만 붙인다
     * [DuelMatch.groupId]의 계약이 *"null이면 단독 판"*이다. 1:1과 '비슷함'은 판이 하나라
     * 종전과 **한 글자도 다르지 않게** 저장된다 — k지선다를 켠 적 없는 사용자의 데이터가
     * 이 슬라이스로 달라지지 않는다는 뜻이다.
     *
     * @param outcomes `(왼쪽 코드, 오른쪽 코드, 이긴 코드 — null이면 무승부)`.
     * @return 적힌 판들. 하나라도 어긋났으면 **빈 목록**이고 DB는 손대지 않았다.
     */
    suspend fun recordGroup(
        axisId: Long,
        outcomes: List<Triple<String, String, String?>>
    ): List<DuelMatch> {
        if (outcomes.isEmpty()) return emptyList()
        val wellFormed = outcomes.all { (aCode, bCode, winnerCode) ->
            aCode.isNotBlank() && bCode.isNotBlank() && aCode != bCode &&
                (winnerCode == null || winnerCode == aCode || winnerCode == bCode)
        }
        if (!wellFormed) return emptyList()

        // 판이 하나면 묶지 않는다 — 위 계약 그대로.
        val groupId = if (outcomes.size > 1) generateEntityCode() else null
        val rows = outcomes.map { (aCode, bCode, winnerCode) ->
            DuelMatch(
                axisId = axisId,
                aCode = aCode,
                bCode = bCode,
                winnerCode = winnerCode,
                groupId = groupId
            )
        }
        // 집계를 트랜잭션 **반환값**으로 뺀다(B-143) — 바깥 변수에 모으면 롤백이 DB만
        // 되돌리고 그 변수는 되돌리지 못해, 실패한 묶음이 성공한 것처럼 화면에 실린다.
        return db.withTransaction {
            rows.map { row -> row.copy(id = db.duelMatchDao().insert(row)) }
        }
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
     * 이 축의 참가자 코드를 **무엇으로 견주는가** (B-175).
     *
     * 축의 종류가 곧 답이라 **판정을 여기 한 줄로 둔다** — 부르는 자리마다 적으면 한 자리가
     * 빠지고, 빠진 자리는 오류가 나지 않는다(같은 파일의 판이 조용히 고아가 될 뿐이다).
     */
    private fun matchingOf(axis: DuelAxis): DuelRecords.CodeMatch =
        if (axis.isImageAxis) DuelRecords.CodeMatch.IMAGE_PATH else DuelRecords.CodeMatch.EXACT

    /**
     * 축의 현재 상태를 낸다. **2단 적합이 권장 진입점**이라 그것을 쓴다 — 한 번만 적합하면
     * 천적 관계 자체가 점수를 오염시켜 잔차를 지운다(설계 3장).
     *
     * **이미지 축은 [imageStateOf]를 쓴다** — 이 함수는 축의 판을 **전부** 넘기는데, 이미지
     * 대결은 캐릭터마다 따로 돌아(설계 13-2) 남의 캐릭터 판이 전부 고아로 읽힌다.
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
        ratingOptions: DuelRating.Options = DuelRating.Options(),
        /**
         * 짝이 될 수 있는 참가자의 **코드** (B-168 후보 필터). null이면 전원이다.
         * [participantCodes]는 전적 있는 비후보까지 담아야 하고(점수가 흔들리지 않게 —
         * `DuelCandidateFilter.participants`가 그 합집합을 만든다) 대기열만 이것으로 좁힌다.
         */
        candidateCodes: Collection<String>? = null
    ): AxisState {
        val records = DuelRecords.resolve(
            participantCodes,
            db.duelMatchDao().getByAxis(axis.id),
            db.duelCounterVerdictDao().getByAxis(axis.id),
            matching = matchingOf(axis)
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
            options = pairingOptions,
            eligibleIds = candidateCodes?.mapNotNull { records.idOf(it) }?.toSet()
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
     * **이미지 축 한 캐릭터 몫의 상태** (B-175 — 로드맵 20판).
     *
     * @property state 그 캐릭터의 그림들만으로 낸 상태.
     * @property verdicts 그 캐릭터 몫의 처분만. **축 전체를 넘기지 않는 것이 요점이다** —
     *   상성 상세가 축 전체를 받으면 *"이 캐릭터의 관계"*를 묻는 화면에 **남의 그림 사이의
     *   판정**이 줄로 뜬다.
     */
    data class ImageAxisState(
        val state: AxisState,
        val verdicts: List<DuelCounterVerdict>
    )

    /**
     * **이미지 축은 한 캐릭터 몫만 본다** (B-175 — 설계 13-2).
     *
     * [stateOf]가 축의 판을 전부 넘기는 것이 이미지 축에서 왜 틀렸는가가 이 함수의 전부다.
     * 참가자는 그 캐릭터의 그림뿐인데 판은 세계관 전원의 것이라, **남의 캐릭터 판이 모두
     * 고아로 읽혔다** — 화면에는 *"지워진 참가자 N명의 판 M개가 점수에서 빠져 있습니다.
     * 휴지통에서 되살리면 그 판도 함께 돌아옵니다"*로 나타난다. **아무것도 지워지지 않았고
     * 되살릴 것도 없다.** 점수는 그 고아들을 빼고 냈으므로 맞았지만, 사용자가 읽는 문장이
     * 거짓이었고 그것이 개발 의도 2번이 금지한 자리다.
     *
     * 몫을 가르는 **잣대**는 [DuelImageRoster.claimOf]이고 점수표([imageScoresByCharacter])와
     * **같은 것**이다 — 두 벌로 적으면 순위표와 대표 추첨이 다른 판 수를 말한다(계약 1).
     * 버킷을 짜는 벌만 갈려 있고(이쪽은 [DuelImageRoster.splitOf], 저쪽은
     * [DuelImageRoster.split]) **둘이 같은 답을 낸다는 사실은 시험이 잰다** — 갈린 이유는
     * 성능이고 그 근거는 `splitOf`의 KDoc이 든다.
     *
     * **받는 것이 캐릭터 표가 아니라 *내 경로 + 소유 표*인 것이 요점이다.** 이 함수가 도는 자리가
     * **한 판 누를 때마다**여서, 캐릭터 표를 받으면 화면이 그것을 들고 있어야 하고 몫 가르기가
     * 인원마다 버킷을 지어 하나만 쓴다(`DuelImageRoster.splitOf`의 KDoc).
     *
     * @param paths 이 캐릭터의 참가자 경로 — 목록에 적힌 **원본 표기 그대로**(R-42).
     * @param owners 세계관에서 어느 그림이 누구 것인가. **화면이 들고 있다가 넘긴다** —
     *   여기서 다시 만들면 그림 수만큼의 파일 시스템 호출이 판마다 붙는다.
     * @return 참가자가 둘 미만이면 null — 붙일 짝이 없다는 뜻이고, 화면이 그 사실을 말한다.
     */
    suspend fun imageStateOf(
        axis: DuelAxis,
        characterId: Long,
        paths: List<String>,
        owners: DuelImageRoster.Owners,
        pairingOptions: DuelPairing.Options = DuelPairing.Options(),
        counterOptions: DuelCounterRelations.Options = DuelCounterRelations.Options(),
        ratingOptions: DuelRating.Options = DuelRating.Options()
    ): ImageAxisState? {
        if (paths.size < 2) return null
        val split = DuelImageRoster.splitOf(
            characterId,
            paths,
            db.duelMatchDao().getByAxis(axis.id),
            db.duelCounterVerdictDao().getByAxis(axis.id),
            owners
        )

        val records = DuelRecords.resolve(
            split.paths,
            split.matches,
            split.verdicts,
            matching = DuelRecords.CodeMatch.IMAGE_PATH
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
        return ImageAxisState(
            state = AxisState(
                axis = axis,
                fit = twoPass.fit,
                report = twoPass.report,
                plan = plan,
                records = records,
                missingParticipants = records.missingParticipants,
                crossCharacterMatches = split.crossCharacterMatches
            ),
            verdicts = split.verdicts
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
            db.duelCounterVerdictDao().getByAxis(axis.id),
            matching = matchingOf(axis)
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

    /**
     * **이미지 축의 점수를 캐릭터 몫으로 나눠 낸다** (B-104 소비처 ⓑ·ⓒ — 설계 13-5).
     *
     * 이미지 축은 **캐릭터마다 독립된 대결**이라(13-2) 축 전체를 한 번에 적합하면 캐릭터
     * 사이의 점수 차가 **아무 판도 근거하지 않은 수**가 된다. 그래서 [DuelImageRoster.split]이
     * 나눈 몫마다 따로 적합한다 — 그 수가 아예 생기지 않고, 제곱 비용도 전원이 아니라
     * 한 캐릭터의 이미지 수에만 붙는다.
     *
     * **[scoresOf]와 같은 진입점을 탄다**([DuelCounterRelations.analyzeTwoPass]) — 순위표가
     * 보이는 그 수여야 대표 추첨과 순위표가 같은 말을 한다([DuelScoreIndex]의 계약 1).
     *
     * **판을 한 번만 읽는다.** 캐릭터마다 질의하면 인원만큼 왕복이 는다.
     *
     * @return 캐릭터 id → 그 캐릭터 몫의 점수표. **한 판도 없는 캐릭터는 아예 담기지 않는다** —
     *   빈 표를 담으면 소비처가 *"계산했는데 값이 없다"*와 *"계산할 것이 없었다"*를 구별하려
     *   또 세어야 하고, 어느 쪽이든 처분이 같다(균등 추첨 · 후보 없음).
     */
    suspend fun imageScoresByCharacter(
        axis: DuelAxis,
        characters: List<Character>,
        counterOptions: DuelCounterRelations.Options = DuelCounterRelations.Options(),
        ratingOptions: DuelRating.Options = DuelRating.Options()
    ): Map<Long, DuelScoreIndex.AxisScores> {
        if (!axis.isImageAxis || characters.isEmpty()) return emptyMap()
        val matches = db.duelMatchDao().getByAxis(axis.id)
        if (matches.isEmpty()) return emptyMap()
        val verdicts = db.duelCounterVerdictDao().getByAxis(axis.id)

        val out = LinkedHashMap<Long, DuelScoreIndex.AxisScores>()
        for (split in DuelImageRoster.split(characters, matches, verdicts)) {
            if (split.matches.isEmpty()) continue
            val records = DuelRecords.resolve(
                split.paths,
                split.matches,
                split.verdicts,
                matching = DuelRecords.CodeMatch.IMAGE_PATH
            )
            val twoPass = DuelCounterRelations.analyzeTwoPass(
                participants = records.participants,
                matches = records.matches,
                confirmedCounters = records.excludedPairs,
                ratingOptions = ratingOptions,
                options = counterOptions
            )
            out[split.characterId] = DuelScoreIndex.of(
                axisId = axis.id,
                axisCode = axis.code,
                axisName = axis.name,
                universeId = axis.universeId,
                rows = DuelStandings.rows(twoPass.fit, twoPass.report, records),
                fit = twoPass.fit,
                missingParticipants = records.missingParticipants,
                scanned = split.matches.size
            )
        }
        return out
    }

    /**
     * 한 캐릭터의 이미지 대결 결과 — 소비처 둘이 함께 쓰는 재료.
     *
     * @property paths 그 캐릭터의 이미지 경로(목록 순서 그대로). 점수가 없는 그림도 들어 있다 —
     *   대표 추첨은 **안 겨룬 그림도 뽑아야** 하므로 점수표만으로는 부족하다.
     */
    data class CharacterImageScores(
        val characterId: Long,
        val paths: List<String>,
        val scores: DuelScoreIndex.AxisScores
    ) {
        /** 경로 → 표시 점수. [com.novelcharacter.app.util.RepresentativeWeighting]이 받는 모양이다. */
        val scoreByPath: Map<String, Int> get() = scores.byCode.mapValues { it.value.score }
    }

    /**
     * @property byCharacter 점수가 난 캐릭터만.
     * @property basisUniverses 기준 이미지 축을 가진 세계관 수. **0이면 지정 자체가 없다** —
     *   화면은 *"후보 0건"*이 아니라 *"기준 축을 먼저 지정하세요"*를 말해야 한다(원칙 02).
     */
    data class BasisScan(
        val byCharacter: Map<Long, CharacterImageScores>,
        val basisUniverses: Int
    ) {
        val hasBasis: Boolean get() = basisUniverses > 0

        companion object {
            /** 기준 축이 하나도 없다 — **가장 흔한 상태이지 예외가 아니다.** */
            val NONE = BasisScan(emptyMap(), 0)
        }
    }

    /**
     * **기준 이미지 축이 지정된 모든 세계관을 훑는다** (B-104 소비처 ⓑ·ⓒ의 공통 진입점).
     *
     * 소비처가 둘이고 둘 다 *"어느 축을 따르고 누구의 그림을 세는가"*를 알아야 한다 —
     * 대표 추첨(캐릭터 목록·상세)과 걸러낼 후보(이미지 탭). **두 화면이 각자 훑으면 한쪽만
     * 고쳐지는 날이 오고**, 그때 대표가 보는 순위와 정리가 보는 순위가 갈린다(R-7의 정신이자
     * 인수인계 ③이 남긴 못이다).
     *
     * 세계관을 가리지 않는 것은 이미지 탭이 앱 안의 모든 이미지를 한 그리드에 놓기 때문이고,
     * 캐릭터 목록도 그 상위집합을 받아 쓰면 그만이다(자기 세계관 것만 집어 쓴다).
     *
     * **지정이 없으면 축 표를 한 번 읽고 끝난다** — 이 기능을 쓰지 않는 사용자의 비용이 0이다.
     */
    suspend fun basisImageScores(): BasisScan {
        val basisAxes = db.duelAxisDao().getAllList().filter { it.isImageBasis }
        if (basisAxes.isEmpty()) return BasisScan.NONE

        val out = LinkedHashMap<Long, CharacterImageScores>()
        // 한 세계관에 기준이 둘일 수는 없지만(쓰기가 지킨다) 여기서 그것을 전제하지 않는다 —
        // 전제가 깨졌을 때 조용히 한쪽을 버리는 대신 먼저 만난 축이 그 캐릭터를 차지한다.
        for (axis in basisAxes) {
            val characters = db.characterDao().getCharactersByUniverseList(axis.universeId)
            if (characters.isEmpty()) continue
            val byId = characters.associateBy { it.id }
            for ((characterId, scores) in imageScoresByCharacter(axis, characters)) {
                if (out.containsKey(characterId)) continue
                val character = byId[characterId] ?: continue
                out[characterId] = CharacterImageScores(
                    characterId = characterId,
                    paths = com.novelcharacter.app.util.CharacterRepresentativeImage.paths(character.imagePaths),
                    scores = scores
                )
            }
        }
        return BasisScan(out, basisAxes.size)
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
            // 전역 구역 필드(universeId null)는 여기 올 수 없다 — duelGrade는 축이 세계관
            // 단위라 전역 템플릿이 갖지 못하고(설계 1-2), 그림자 config에서도 강등된다.
            // 그래도 방어한다: null이면 반영 대상이 없는 것이지 전 세계관이 아니다.
            val fieldUniverseId = field.universeId ?: return@withTransaction
            val characters = db.characterDao().getCharactersByUniverseList(fieldUniverseId)
            val byCode = characters.associateBy { it.code }
            // 값은 한 번에 읽는다 — 캐릭터마다 물으면 인원만큼 왕복이 늘고, 그것이 목표 규모에서
            // 이 트랜잭션을 가장 길게 잡는 자리가 된다('받쳐주는 확장성').
            // **다만 한 번에 다 넘기지는 못한다** — `IN (:ids)`는 SQLite 변수 상한(999)에 걸리므로
            // 저장소 공통 통로([SqlInChunks] · R-54)를 지난다. 쪼개지 않으면 캐릭터가 많은 세계관에서
            // 반영이 통째로 예외가 되고, 그것은 목표 규모(실사용 ×30)에서 먼저 무너지는 자리다.
            val currentByCharacter = HashMap<Long, String>()
            SqlInChunks.each(characters.map { it.id }) { chunk ->
                for (value in db.characterFieldValueDao().getValuesForCharacters(chunk)) {
                    if (value.fieldDefinitionId == fieldDefinitionId) {
                        currentByCharacter[value.characterId] = value.value
                    }
                }
            }

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
