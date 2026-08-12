package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 순위표·상성 상세가 무엇을 보이는가(B-104 화면 계층)를 지키는 하네스.
 *
 * 지키는 것 셋: **동점을 표시 점수로 가른다**(내부 γ로 가르면 화면에 같은 숫자가 다른 등수로
 * 뜬다) · **한 관계가 두 줄로 보이지 않는다**(후보와 처분을 같은 정규 키로 맞춘다) ·
 * **버려진 것은 세어서 알린다**(개발 의도 2번).
 */
class DuelStandingsTest {

    private fun match(a: String, b: String, winner: String?): DuelMatch =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner)

    private fun verdict(members: List<String>, kind: String, id: Long = 1L): DuelCounterVerdict =
        DuelCounterVerdict(
            id = id,
            axisId = 1L,
            kind = kind,
            shape = DuelRecords.shapeOf(members)!!,
            memberCodes = DuelRecords.encodeMembers(members),
            memberKey = DuelRecords.memberKey(members)
        )

    private fun stateOf(
        codes: List<String>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict> = emptyList()
    ): Triple<DuelRecords.Resolved, DuelRating.Fit, DuelCounterRelations.Report> {
        val records = DuelRecords.resolve(codes, matches, verdicts)
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs
        )
        return Triple(records, twoPass.fit, twoPass.report)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 순위표
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `한 판도 없으면 전원 동점 1위이고 잠정으로 가린다`() {
        val (records, fit, report) = stateOf(listOf("A", "B", "C"), emptyList())

        val rows = DuelStandings.rows(fit, report, records)

        assertEquals(3, rows.size)
        assertTrue("한 판도 안 했으면 전원 앵커 점수다", rows.all { it.score == DuelRating.ANCHOR_SCORE })
        assertTrue("동점이면 같은 등수다", rows.all { it.rank == 1 })
        assertTrue(rows.all { it.provisional })
        assertTrue(rows.all { it.played == 0 })
    }

    @Test
    fun `동점 다음 등수는 건너뛴다`() {
        // A와 B는 서로 한 번씩 이겨 같은 점수, C는 둘에게 진다.
        val matches = listOf(
            match("A", "B", "A"), match("A", "B", "B"),
            match("A", "C", "A"), match("B", "C", "B")
        )
        val (records, fit, report) = stateOf(listOf("A", "B", "C"), matches)

        val rows = DuelStandings.rows(fit, report, records)

        assertEquals(listOf(1, 1, 3), rows.map { it.rank })
        assertEquals(rows[0].score, rows[1].score)
        assertEquals("C", rows[2].code)
    }

    @Test
    fun `이긴 쪽이 위에 서고 판 수와 오차를 함께 낸다`() {
        val matches = (1..5).map { match("A", "B", "A") }
        val (records, fit, report) = stateOf(listOf("A", "B"), matches)

        val rows = DuelStandings.rows(fit, report, records)

        assertEquals("A", rows[0].code)
        assertEquals(1, rows[0].rank)
        assertEquals(2, rows[1].rank)
        assertEquals(5, rows[0].played)
        assertEquals(5.0, rows[0].wins, 1e-9)
        assertFalse(rows[0].provisional)
        assertTrue("판이 있으면 오차가 유한하다", rows[0].scoreError in 1..2000)
    }

    @Test
    fun `한 판도 안 한 참가자가 섞여도 잠정만 가려진다`() {
        val matches = (1..3).map { match("A", "B", "A") }
        val (records, fit, report) = stateOf(listOf("A", "B", "C"), matches)

        val rows = DuelStandings.rows(fit, report, records).associateBy { it.code }

        assertFalse(rows.getValue("A").provisional)
        assertFalse(rows.getValue("B").provisional)
        assertTrue(rows.getValue("C").provisional)
        assertEquals(DuelRating.ANCHOR_SCORE, rows.getValue("C").score)
    }

    @Test
    fun `상성에 얽힌 행에 건수가 붙는다 — P-10 배지를 행으로 나눈 것`() {
        // 사슬 A>B>C>D>E에 E>A를 얹는다 — 순환은 안 생기고 천적 하나가 잡히는 배치.
        val chain = listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E")
        val matches = ArrayList<DuelMatch>()
        chain.forEach { (w, l) -> repeat(6) { matches.add(match(w, l, w)) } }
        repeat(4) { matches.add(match("E", "A", "E")) }
        val (records, fit, report) = stateOf(listOf("A", "B", "C", "D", "E"), matches)

        val rows = DuelStandings.rows(fit, report, records).associateBy { it.code }

        assertTrue("천적이 잡혀야 이 검사가 뜻이 있다", report.count > 0)
        assertTrue(rows.getValue("A").counters > 0)
        assertTrue(rows.getValue("E").counters > 0)
        assertEquals(0, rows.getValue("C").counters)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 고지
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `말할 것이 없으면 고지가 비어 있다`() {
        val matches = (1..3).map { match("A", "B", "A") }
        val (records, fit, report) = stateOf(listOf("A", "B"), matches)
        val plan = DuelPairing.plan(fit)

        val caveats = DuelStandings.caveats(fit, report, plan, records.missingParticipants)

        assertFalse(caveats.any)
    }

    @Test
    fun `지워진 참가자의 판은 세어서 알린다 — 지우지 않았으므로 복원하면 되살아난다`() {
        val matches = listOf(match("A", "B", "A"), match("A", "GONE", "A"))
        val (records, fit, report) = stateOf(listOf("A", "B"), matches)
        val plan = DuelPairing.plan(fit)

        val caveats = DuelStandings.caveats(fit, report, plan, records.missingParticipants)

        assertTrue(caveats.any)
        assertEquals(1, caveats.orphanMatches)
        assertEquals(1, caveats.missingParticipants)
    }

    @Test
    fun `깨진 판과 상성으로 뺀 판이 따로 세어진다`() {
        val matches = listOf(
            match("A", "B", "C"),           // 승자가 두 참가자 중 어느 쪽도 아니다
            match("A", "C", "A")
        )
        val verdicts = listOf(verdict(listOf("A", "C"), DuelCounterVerdict.KIND_COUNTER))
        val (records, fit, report) = stateOf(listOf("A", "B", "C"), matches, verdicts)
        val plan = DuelPairing.plan(fit)

        val caveats = DuelStandings.caveats(fit, report, plan, records.missingParticipants)

        assertEquals(1, caveats.malformedMatches)
        assertEquals(1, caveats.excludedMatches)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 상성 상세
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `아직 판정하지 않은 어긋남은 후보로 나온다`() {
        val chain = listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E")
        val matches = ArrayList<DuelMatch>()
        chain.forEach { (w, l) -> repeat(6) { matches.add(match(w, l, w)) } }
        repeat(4) { matches.add(match("E", "A", "E")) }
        val (records, _, report) = stateOf(listOf("A", "B", "C", "D", "E"), matches)

        val review = DuelStandings.counterReview(report, records, emptyList())

        assertTrue(review.candidates.isNotEmpty())
        assertTrue(review.decided.isEmpty())
        assertEquals(0, review.unresolvedMembers)
        val first = review.candidates.first()
        assertEquals(DuelCounterRelations.Kind.DIRECT, first.kind)
        assertEquals("천적은 [센 쪽, 잡는 쪽] 둘이다", 2, first.memberCodes.size)
        assertNull(first.verdictKind)
    }

    @Test
    fun `처분한 관계는 후보에서 빠지고 처분 목록에만 선다 — 한 관계는 한 줄이다`() {
        val chain = listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E")
        val matches = ArrayList<DuelMatch>()
        chain.forEach { (w, l) -> repeat(6) { matches.add(match(w, l, w)) } }
        repeat(4) { matches.add(match("E", "A", "E")) }
        val undecided = listOf(verdict(listOf("A", "E"), DuelCounterVerdict.KIND_UNDECIDED))
        val (records, _, report) = stateOf(listOf("A", "B", "C", "D", "E"), matches, undecided)

        val review = DuelStandings.counterReview(report, records, undecided)

        assertTrue(
            "미정으로 미룬 짝이 후보에 남으면 같은 것을 두 번 판정하게 된다",
            review.candidates.none { it.memberCodes.toSet() == setOf("A", "E") }
        )
        assertEquals(1, review.decided.size)
        assertEquals(DuelCounterVerdict.KIND_UNDECIDED, review.decided[0].verdictKind)
    }

    @Test
    fun `미정은 여전히 잡힌다 — 그것이 나중에 다시 묻는 신호다`() {
        val chain = listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E")
        val matches = ArrayList<DuelMatch>()
        chain.forEach { (w, l) -> repeat(6) { matches.add(match(w, l, w)) } }
        repeat(4) { matches.add(match("E", "A", "E")) }
        val undecided = listOf(verdict(listOf("A", "E"), DuelCounterVerdict.KIND_UNDECIDED))
        val (records, _, report) = stateOf(listOf("A", "B", "C", "D", "E"), matches, undecided)

        val review = DuelStandings.counterReview(report, records, undecided)

        assertTrue(
            "②는 점수에서 빼지 않으므로 어긋남이 그대로 남는다(확정 문서 2-1)",
            review.decided[0].stillDetected
        )
        assertTrue("숫자는 지금 기록에서 다시 낸 것이다", review.decided[0].samples > 0)
    }

    @Test
    fun `상성으로 확정하면 어긋남이 사라진다 — 그 짝이 적합에서 빠지기 때문`() {
        val chain = listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E")
        val matches = ArrayList<DuelMatch>()
        chain.forEach { (w, l) -> repeat(6) { matches.add(match(w, l, w)) } }
        repeat(4) { matches.add(match("E", "A", "E")) }
        val counter = listOf(verdict(listOf("A", "E"), DuelCounterVerdict.KIND_COUNTER))
        val (records, _, report) = stateOf(listOf("A", "B", "C", "D", "E"), matches, counter)

        val review = DuelStandings.counterReview(report, records, counter)

        assertEquals(1, review.decided.size)
        assertFalse(review.decided[0].stillDetected)
        assertEquals(DuelCounterVerdict.KIND_COUNTER, review.decided[0].verdictKind)
        assertEquals(1L, review.decided[0].verdictId)
    }

    @Test
    fun `회전해 적은 순환도 같은 처분으로 맞춰진다`() {
        val matches = listOf(
            match("A", "B", "A"), match("B", "C", "B"), match("C", "A", "C")
        )
        // 사용자가 A>B>C로 처분했는데 검출은 회전한 순서로 나올 수 있다.
        val cycle = listOf(verdict(listOf("B", "C", "A"), DuelCounterVerdict.KIND_UNDECIDED))
        val (records, _, report) = stateOf(listOf("A", "B", "C"), matches, cycle)

        assertTrue("이 배치는 순환이 잡혀야 한다", report.cycles.isNotEmpty())
        val review = DuelStandings.counterReview(report, records, cycle)

        assertTrue(
            "회전·반전으로 같은 관계가 두 줄이 되면 안 된다",
            review.candidates.isEmpty()
        )
        assertEquals(1, review.decided.size)
        assertTrue(review.decided[0].isCycle)
        assertTrue(review.decided[0].stillDetected)
    }

    @Test
    fun `이미지 축은 표기가 갈린 처분도 한 줄이다`() {
        // B-175. 후보 쪽 열쇠는 해석을 거친 **대표 표기**로 만들어지는데 저장된 `memberKey`는
        // 원본 표기다(R-42). 저장된 값을 그대로 열쇠로 쓰면 **이미 판정한 처분이 후보로 한 번 더
        // 뜬다** — 사용자는 같은 관계를 두 번 판정하게 되고, 두 판정은 서로를 덮는다.
        val dir = "/data/user/0/com.novelcharacter.app/files"
        val a = "$dir/a1.jpg"
        val b = "$dir/a2.jpg"
        val c = "$dir/a3.jpg"
        val matches = listOf(match(a, b, a), match(b, c, b), match("$dir/./a3.jpg", a, c))
        // 사용자가 판정할 때 적힌 표기가 목록의 것과 갈려 있다(엑셀·백업으로 들어온 모양).
        val cycle = listOf(
            verdict(listOf("$dir/./a1.jpg", b, "$dir/sub/../a3.jpg"), DuelCounterVerdict.KIND_UNDECIDED)
        )
        val records = DuelRecords.resolve(
            listOf(a, b, c), matches, cycle, DuelRecords.CodeMatch.IMAGE_PATH
        )
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs
        )

        assertTrue("이 배치는 순환이 잡혀야 한다", twoPass.report.cycles.isNotEmpty())
        val review = DuelStandings.counterReview(twoPass.report, records, cycle)

        assertTrue("표기가 갈렸다고 같은 관계가 두 줄이 되면 안 된다", review.candidates.isEmpty())
        assertEquals(1, review.decided.size)
        assertTrue(review.decided[0].stillDetected)
        // **표시는 저장된 표기 그대로다** — 화면이 파일 이름을 그 값에서 내므로, 여기서 대표
        // 표기로 갈아 끼우면 사용자가 파일 관리자에서 보는 이름과 갈릴 수 있다.
        assertEquals("$dir/./a1.jpg", review.decided[0].memberCodes.first())
    }

    @Test
    fun `캐릭터 축에서는 정규 키가 저장된 열쇠와 글자 그대로 같다`() {
        // 위 시험이 바꾼 것이 캐릭터 축에 새지 않는다는 것 — EXACT에서는 대표 표기가 코드
        // 그대로라 `memberKey`와 정규 키가 같은 문자열이어야 한다.
        val decided = listOf(verdict(listOf("A", "B"), DuelCounterVerdict.KIND_COUNTER))
        val (records, _, _) = stateOf(listOf("A", "B"), listOf(match("A", "B", "A")), decided)
        assertEquals(
            decided[0].memberKey,
            DuelRecords.memberKey(listOf("A", "B").map { records.canonicalCode(it) })
        )
    }
}
