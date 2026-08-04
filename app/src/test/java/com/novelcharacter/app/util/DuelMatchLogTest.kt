package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.util.DuelFieldLinks.Agreement
import com.novelcharacter.app.util.DuelFieldLinks.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelMatchLog] 하네스 — 기록 화면이 *무엇을 짚어 주는가*의 계약.
 *
 * **화면에 판단을 두지 않으려고 이 계층을 팠다**(「세션 착수 규칙」 4번). 여기서 지키는 것 셋:
 * ① 깨진 판을 무승부로 접지 않는다 ② 사라진 참가자의 판을 버리지 않는다
 * ③ 어긋남으로 짚는 것은 *필드가 반대를 말한 판*뿐이다.
 */
class DuelMatchLogTest {

    private fun match(
        a: String, b: String, winner: String?, code: String = "MT-$a$b", at: Long = 1_000
    ) = DuelMatch(axisId = 1, aCode = a, bCode = b, winnerCode = winner, decidedAt = at, code = code)

    private val names = mapOf("A" to "리안", "B" to "카이", "C" to "세라")
    private val influences = listOf(Link("mana"), Link("age", higherWins = false))
    private val labels = mapOf("mana" to "마력량", "age" to "나이")
    private val values = mapOf(
        "A" to mapOf("mana" to "90", "age" to "30"),
        "B" to mapOf("mana" to "40", "age" to "20"),
        "C" to mapOf("mana" to "40", "age" to "50")
    )

    private fun rows(vararg matches: DuelMatch) =
        DuelMatchLog.rows(matches.toList(), names, influences, labels, values)

    // ── 결과 읽기 ──

    @Test
    fun `winner code becomes a readable outcome`() {
        assertEquals(DuelMatchLog.Outcome.A_WON, DuelMatchLog.outcomeOf(match("A", "B", "A")))
        assertEquals(DuelMatchLog.Outcome.B_WON, DuelMatchLog.outcomeOf(match("A", "B", "B")))
        assertEquals(DuelMatchLog.Outcome.DRAW, DuelMatchLog.outcomeOf(match("A", "B", null)))
    }

    /**
     * 승자가 두 참가자 중 어느 쪽도 아니면 **깨진 판**이다 — 무승부로 접으면 외부에서 잘못
     * 편집된 행이 *"사용자가 비겼다고 판정한 판"*으로 둔갑해 고칠 자리가 사라진다.
     */
    @Test
    fun `a winner that is neither participant is broken not a draw`() {
        val row = rows(match("A", "B", "Z")).single()
        assertEquals(DuelMatchLog.Outcome.BROKEN, row.outcome)
        assertFalse("깨진 판은 어긋남으로 세지 않는다 — 고칠 것이 먼저다", row.disagrees)
    }

    // ── 사라진 참가자 ──

    @Test
    fun `matches of deleted participants stay in the list`() {
        val row = rows(match("A", "Z", "A")).single()
        assertEquals("리안", row.aName)
        assertTrue("지워진 참가자는 이름이 없을 뿐 판은 남는다", row.bMissing)
        assertEquals("Z", row.bCode)
    }

    // ── 층 C 대조 ──

    /** 1순위(마력량)가 A를 가리키는데 B가 이겼다 → 짚어 준다. */
    @Test
    fun `a reversal against the top ranked field is flagged`() {
        val row = rows(match("A", "B", "B")).single()
        assertEquals(Agreement.DISAGREE, row.agreement)
        assertTrue(row.disagrees)
        assertEquals("mana", row.prediction.decidedBy)
        assertEquals(1, row.prediction.rank)
    }

    @Test
    fun `agreeing matches are not flagged`() {
        val row = rows(match("A", "B", "A")).single()
        assertEquals(Agreement.AGREE, row.agreement)
        assertFalse(row.disagrees)
    }

    /** 1순위가 같으면 2순위가 말한다 — '나이'는 낮을수록 유리라 B가 예상 승자다. */
    @Test
    fun `the second rank speaks when the first ties`() {
        val row = rows(match("B", "C", "C")).single()
        assertEquals("age", row.prediction.decidedBy)
        assertEquals(2, row.prediction.rank)
        assertEquals(Agreement.DISAGREE, row.agreement)
    }

    /** 무승부는 어긋남이 아니다 — 세면 값을 안 적은 판이 소음이 된다. */
    @Test
    fun `draws are not disagreements`() {
        val row = rows(match("A", "B", null)).single()
        assertEquals(Agreement.UNDECIDED, row.agreement)
        assertFalse(row.disagrees)
    }

    @Test
    fun `cells carry both sides in rank order`() {
        val row = rows(match("A", "B", "A")).single()
        assertEquals(listOf("마력량", "나이"), row.cells.map { it.label })
        assertEquals(listOf(1, 2), row.cells.map { it.rank })
        assertEquals("90", row.cells[0].aValue)
        assertEquals("40", row.cells[0].bValue)
    }

    /** 연결이 없으면 대조도 없다 — 그 축은 그냥 목록이다. */
    @Test
    fun `an axis without links produces no cells and no flags`() {
        val row = DuelMatchLog.rows(
            listOf(match("A", "B", "B")), names, emptyList(), labels, values
        ).single()
        assertTrue(row.cells.isEmpty())
        assertEquals(Agreement.UNKNOWN, row.agreement)
        assertFalse(row.disagrees)
    }

    // ── 요약과 상한 ──

    @Test
    fun `summary reports the cap so nothing is silently cut`() {
        val shown = rows(match("A", "B", "B"), match("A", "B", null, code = "MT-2"))
        val summary = DuelMatchLog.summarize(shown, total = 12_000)
        assertEquals(2, summary.shown)
        assertEquals(12_000, summary.total)
        assertTrue("전량보다 적게 실었으면 잘렸다고 말해야 한다", summary.truncated)
        assertEquals(1, summary.disagreements)
        assertEquals(1, summary.draws)
    }

    @Test
    fun `filtering keeps only the reversals`() {
        val shown = rows(match("A", "B", "B"), match("A", "B", "A", code = "MT-2"))
        assertEquals(listOf("MT-AB"), DuelMatchLog.onlyDisagreements(shown).map { it.code })
    }
}
