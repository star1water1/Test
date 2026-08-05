package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 점수가 **대결 밖에서** 쓰일 때의 계약(B-117).
 *
 * 이 하네스가 지키는 것은 셋이고, 셋 다 *"코드를 읽어서는 알 수 없는 것"*이다:
 *
 * 1. **밖에서 보는 수는 순위표가 보이는 그 수다** — 다시 계산하지 않는다.
 * 2. **한 판도 안 치른 참가자는 값이 없다** — 앵커 1500을 값으로 섞으면 아무것도 안 한
 *    캐릭터가 목록 한가운데에 박힌다. 이것은 *의도적인 침묵*이라 시험으로 못 박는다
 *    (B-112가 남긴 관행 — 코드가 하지 **않는** 일은 읽어서 알 수 없다).
 * 3. **값 없음은 방향과 무관하게 최후순이다** — 커스텀 필드 정렬과 같은 규칙.
 */
class DuelScoreIndexTest {

    private fun match(a: String, b: String, winner: String?): DuelMatch =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner)

    private fun verdict(members: List<String>, kind: String): DuelCounterVerdict =
        DuelCounterVerdict(
            id = 1L,
            axisId = 1L,
            kind = kind,
            shape = DuelRecords.shapeOf(members)!!,
            memberCodes = DuelRecords.encodeMembers(members),
            memberKey = DuelRecords.memberKey(members)
        )

    /** 순위표가 서는 것과 **같은 경로**로 축 하나를 세운다 — 그것이 계약 1의 시험 방법이다. */
    private fun scoresOf(
        codes: List<String>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict> = emptyList()
    ): DuelScoreIndex.AxisScores {
        val records = DuelRecords.resolve(codes, matches, verdicts)
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs
        )
        return DuelScoreIndex.of(
            axisId = 1L,
            axisCode = "AX",
            axisName = "강함",
            universeId = 7L,
            rows = DuelStandings.rows(twoPass.fit, twoPass.report, records),
            fit = twoPass.fit,
            missingParticipants = records.missingParticipants,
            scanned = matches.size
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 계약 1 — 순위표와 같은 수
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `점수와 등수가 순위표 줄과 글자 그대로 같다`() {
        val codes = listOf("A", "B", "C")
        val matches = listOf(
            match("A", "B", "A"), match("A", "B", "A"), match("A", "B", "A"),
            match("B", "C", "B"), match("B", "C", "B"), match("A", "C", "A")
        )
        val records = DuelRecords.resolve(codes, matches)
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants, matches = records.matches
        )
        val rows = DuelStandings.rows(twoPass.fit, twoPass.report, records)

        val scores = scoresOf(codes, matches)

        // 순위표가 낸 줄 전부가 같은 점수·등수로 표에 들어 있다.
        for (row in rows) {
            val entry = scores.entryOf(row.code)
            assertEquals("점수를 다시 계산하면 화면마다 다른 수가 된다", row.score, entry?.score)
            assertEquals(row.rank, entry?.rank)
            assertEquals(row.scoreError, entry?.scoreError)
            assertEquals(row.played, entry?.played)
        }
        assertEquals(rows.size, scores.scored)
    }

    @Test
    fun `이긴 쪽이 진 쪽보다 점수가 높다`() {
        val scores = scoresOf(
            listOf("A", "B"),
            List(5) { match("A", "B", "A") }
        )

        assertTrue(scores.scoreOf("A")!! > scores.scoreOf("B")!!)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 계약 2 — 한 판도 안 치른 참가자는 값이 없다 (의도적인 침묵)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `한 판도 안 치른 참가자는 앵커 점수를 갖지만 표에는 없다`() {
        val scores = scoresOf(
            listOf("A", "B", "쉬는사람"),
            List(3) { match("A", "B", "A") }
        )

        assertNull("앵커 1500은 계산된 값이 아니다 — 값으로 섞으면 목록 한가운데에 박힌다",
            scores.scoreOf("쉬는사람"))
        assertEquals(2, scores.scored)
        assertEquals("빠진 인원은 세어서 알린다", 1, scores.unplayed)
        assertTrue(scores.hasCaveat)
    }

    @Test
    fun `판이 하나도 없으면 표가 통째로 비고 전원이 무기록으로 잡힌다`() {
        val scores = scoresOf(listOf("A", "B", "C"), emptyList())

        assertEquals(0, scores.scored)
        assertEquals(3, scores.unplayed)
        assertEquals(0, scores.scanned)
    }

    @Test
    fun `빈 축은 예외가 아니라 빈 표다`() {
        val empty = DuelScoreIndex.AxisScores.empty(9L, "AX", "아름다움", 3L)

        assertEquals(0, empty.scored)
        assertFalse("말할 것이 없으면 고지 영역을 감춘다", empty.hasCaveat)
        assertNull(empty.scoreOf("A"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // 계약 3 — 값 없음은 방향과 무관하게 최후순
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `값 없음은 오름차순에서도 내림차순에서도 맨 뒤다`() {
        val scores = scoresOf(
            listOf("A", "B", "무기록"),
            List(4) { match("A", "B", "A") }
        )
        val items = listOf("A", "B", "무기록")

        val desc = DuelScoreIndex.sorted(items, false, scores, { it }, { it })
        val asc = DuelScoreIndex.sorted(items, true, scores, { it }, { it })

        assertEquals("내림차순: 센 쪽 먼저, 값 없음은 뒤", listOf("A", "B", "무기록"), desc)
        assertEquals(
            "오름차순은 값에 대한 물음이지 값의 유무에 대한 물음이 아니다",
            listOf("B", "A", "무기록"), asc
        )
    }

    @Test
    fun `동점자는 이름으로 갈려 목록이 스스로 움직이지 않는다`() {
        // 서로 한 번씩 이겨 완전히 대칭 — 두 점수가 같다.
        val scores = scoresOf(
            listOf("나", "가"),
            listOf(match("가", "나", "가"), match("가", "나", "나"))
        )
        assertEquals(scores.scoreOf("가"), scores.scoreOf("나"))

        val first = DuelScoreIndex.sorted(listOf("나", "가"), false, scores, { it }, { it })
        val again = DuelScoreIndex.sorted(listOf("가", "나"), false, scores, { it }, { it })

        assertEquals("입력 차례가 달라도 결과가 같아야 목록이 갱신될 때 튀지 않는다", first, again)
        assertEquals(listOf("가", "나"), first)
    }

    @Test
    fun `참가자가 아닌 코드는 값 없음으로 최후순에 간다`() {
        val scores = scoresOf(listOf("A", "B"), List(3) { match("A", "B", "A") })

        val sorted = DuelScoreIndex.sorted(
            listOf("A", "다른세계관캐릭터", "B"), false, scores, { it }, { it }
        )

        assertEquals(listOf("A", "B", "다른세계관캐릭터"), sorted)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 고지 — 조용히 버리지 않는다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `지워진 캐릭터의 판은 고아로 세어서 알린다`() {
        val scores = scoresOf(
            listOf("A", "B"),
            listOf(match("A", "B", "A"), match("A", "지워진캐릭터", "A"))
        )

        assertTrue("판을 지우지 않으므로 복원하면 되살아난다", scores.orphanMatches > 0)
        assertEquals(1, scores.missingParticipants)
        assertTrue(scores.hasCaveat)
        assertEquals("scanned는 상한이 아니라 규모의 표시다", 2, scores.scanned)
    }

    @Test
    fun `고지 숫자가 순위표의 고지와 같다`() {
        // Caveats를 받지 않고 Fit에서 직접 드는 것이 **같은 수인가**를 못 박는다.
        // 갈리면 대결 화면과 목록·통계가 서로 다른 개수를 말하게 된다.
        val codes = listOf("A", "B")
        val matches = listOf(
            match("A", "B", "A"), match("A", "지워진캐릭터", "A"), match("A", "B", "엉뚱한값")
        )
        val records = DuelRecords.resolve(codes, matches)
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants, matches = records.matches
        )
        val plan = DuelPairing.plan(fit = twoPass.fit, recheckTargets = twoPass.report.involvedIds)
        val caveats = DuelStandings.caveats(
            twoPass.fit, twoPass.report, plan, records.missingParticipants
        )

        val scores = scoresOf(codes, matches)

        assertEquals(caveats.orphanMatches, scores.orphanMatches)
        assertEquals(caveats.missingParticipants, scores.missingParticipants)
        assertEquals(caveats.excludedMatches, scores.excludedMatches)
        assertEquals(caveats.malformedMatches, scores.malformedMatches)
        assertEquals(caveats.notConverged, scores.notConverged)
    }

    @Test
    fun `상성으로 확정해 뺀 판도 세어서 알린다`() {
        val matches = List(4) { match("A", "B", "B") } + List(4) { match("A", "C", "A") }
        val scores = scoresOf(
            listOf("A", "B", "C"),
            matches,
            listOf(verdict(listOf("A", "B"), DuelCounterVerdict.KIND_COUNTER))
        )

        assertTrue("점수에서 뺐다는 사실은 화면이 말해야 한다", scores.excludedMatches > 0)
    }

    @Test
    fun `깨진 판은 세어서 알리고 점수를 오염시키지 않는다`() {
        // 승자가 두 참가자 중 어느 쪽도 아니다 — 외부에서 편집된 파일이 만드는 모양.
        val scores = scoresOf(
            listOf("A", "B"),
            listOf(match("A", "B", "A"), match("A", "B", "엉뚱한값"))
        )

        assertEquals(1, scores.malformedMatches)
        assertTrue(scores.hasCaveat)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 분포 — 구간은 NumericBinning이 만든다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `분포의 인원 합이 점수를 가진 인원과 같다`() {
        val codes = listOf("A", "B", "C", "D")
        val matches = listOf(
            match("A", "B", "A"), match("A", "C", "A"), match("A", "D", "A"),
            match("B", "C", "B"), match("B", "D", "B"), match("C", "D", "C")
        )
        val scores = scoresOf(codes, matches)

        val dist = DuelScoreIndex.distribution(scores)

        assertTrue(dist.isNotEmpty())
        assertEquals(
            "구간이 최댓값을 놓치면 합이 모집단보다 작아진다",
            scores.scored, dist.sumOf { it.second }
        )
    }

    @Test
    fun `전원이 같은 점수면 나눌 폭이 없어 분포가 비고 그 사실이 드러난다`() {
        // 서로 한 번씩 이긴 완전 대칭 — 두 점수가 같다.
        val scores = scoresOf(
            listOf("A", "B"),
            listOf(match("A", "B", "A"), match("A", "B", "B"))
        )

        assertEquals(2, scores.scored)
        assertTrue("빈 목록은 실패가 아니라 '나눌 수 없다'는 답이다",
            DuelScoreIndex.distribution(scores).isEmpty())
    }

    @Test
    fun `무기록 참가자는 분포에도 들지 않는다`() {
        val scores = scoresOf(
            listOf("A", "B", "C", "쉬는사람"),
            listOf(match("A", "B", "A"), match("B", "C", "B"), match("A", "C", "A"))
        )

        val dist = DuelScoreIndex.distribution(scores)

        assertEquals(3, dist.sumOf { it.second })
    }
}
