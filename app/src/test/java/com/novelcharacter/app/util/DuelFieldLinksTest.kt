package com.novelcharacter.app.util

import com.novelcharacter.app.util.DuelFieldLinks.Agreement
import com.novelcharacter.app.util.DuelFieldLinks.Link
import com.novelcharacter.app.util.DuelFieldLinks.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelFieldLinks] 하네스 — 축↔필드 연결의 계약(B-104 층 C).
 *
 * **여기서 지키는 것 셋:** ① 저장 형식이 왕복해도 순위가 뒤집히지 않는다 ② 견줄 수 없는 값을
 * 견주지 않는다 ③ 어긋남으로 세는 것은 *실제로 반대인 것* 하나뿐이다.
 */
class DuelFieldLinksTest {

    // ── 저장 형식 ──

    @Test
    fun `json round trip keeps order and direction`() {
        val links = listOf(Link("mana"), Link("age", higherWins = false), Link("attr"))
        val decoded = DuelFieldLinks.decode(DuelFieldLinks.encode(links))
        assertEquals(links, decoded)
    }

    @Test
    fun `excel text round trip keeps order and direction`() {
        val links = listOf(Link("mana"), Link("age", higherWins = false))
        assertEquals("mana, -age", DuelFieldLinks.toText(links))
        assertEquals(links, DuelFieldLinks.parseText("mana, -age"))
        // 사람이 적은 것이라 공백·줄바꿈이 섞여도 같은 결과여야 한다.
        assertEquals(links, DuelFieldLinks.parseText("  mana ,\n  - age  "))
    }

    @Test
    fun `broken storage yields nothing instead of crashing`() {
        assertEquals(emptyList<Link>(), DuelFieldLinks.decode(null))
        assertEquals(emptyList<Link>(), DuelFieldLinks.decode(""))
        assertEquals(emptyList<Link>(), DuelFieldLinks.decode("{이건 JSON이 아니다"))
        assertEquals(emptyList<Link>(), DuelFieldLinks.parseText("  ,  , "))
        assertEquals(emptyList<Link>(), DuelFieldLinks.parseText("-"))
    }

    /** 같은 필드가 두 번 오면 앞의 것이 남는다 — 순위가 둘일 수는 없다. */
    @Test
    fun `duplicate keys keep the first rank`() {
        val decoded = DuelFieldLinks.parseText("mana, attr, -mana")
        assertEquals(listOf(Link("mana"), Link("attr")), decoded)
    }

    // ── 값 읽기 ──

    @Test
    fun `numbers are read only from the front`() {
        assertEquals(80.0, DuelFieldLinks.numberOf("80")!!, 0.0001)
        assertEquals(3.5, DuelFieldLinks.numberOf("3.5cm")!!, 0.0001)
        assertEquals(1200.0, DuelFieldLinks.numberOf("1,200")!!, 0.0001)
        assertEquals(-5.0, DuelFieldLinks.numberOf("-5")!!, 0.0001)
        // 등급 이름에서 수를 주워 오면 서열이 뒤집힌다 — S-1이 −1이 되어 꼴찌가 된다.
        assertNull(DuelFieldLinks.numberOf("S-1"))
        assertNull(DuelFieldLinks.numberOf("불속성"))
        assertNull(DuelFieldLinks.numberOf(""))
        assertNull(DuelFieldLinks.numberOf(null))
    }

    @Test
    fun `direction flips which side wins`() {
        assertEquals(Side.A, DuelFieldLinks.compareOne(Link("mana"), "90", "40"))
        assertEquals(Side.B, DuelFieldLinks.compareOne(Link("age", higherWins = false), "90", "40"))
        assertEquals(Side.TIE, DuelFieldLinks.compareOne(Link("mana"), "40", "40"))
    }

    /** 차례가 없는 값(속성·소속)은 견주지 않는다 — 억지로 정렬하면 앱이 서열을 지어낸다. */
    @Test
    fun `unorderable values are not compared`() {
        assertEquals(Side.UNKNOWN, DuelFieldLinks.compareOne(Link("attr"), "불", "물"))
        assertEquals(Side.UNKNOWN, DuelFieldLinks.compareOne(Link("mana"), "90", ""))
        assertEquals(Side.UNKNOWN, DuelFieldLinks.compareOne(Link("mana"), null, "40"))
    }

    // ── 사전식 예측 ──

    /** 1순위에서 갈리면 거기서 끝난다 — 뒤 순위가 반대를 말해도 뒤집지 못한다. */
    @Test
    fun `first rank decides`() {
        val links = listOf(Link("mana"), Link("speed"))
        val p = DuelFieldLinks.predict(links, mapOf("mana" to "90", "speed" to "10"), mapOf("mana" to "40", "speed" to "99"))
        assertEquals(Side.A, p.side)
        assertEquals("mana", p.decidedBy)
        assertEquals(1, p.rank)
    }

    /** 1순위가 비기면 2순위로 넘어간다 — 사용자가 말한 "1순위·2순위"의 뜻 그대로. */
    @Test
    fun `tie at the first rank falls through to the next`() {
        val links = listOf(Link("mana"), Link("speed"))
        val p = DuelFieldLinks.predict(links, mapOf("mana" to "50", "speed" to "10"), mapOf("mana" to "50", "speed" to "99"))
        assertEquals(Side.B, p.side)
        assertEquals("speed", p.decidedBy)
        assertEquals(2, p.rank)
    }

    /** 견줄 수 없는 1순위는 **빼는 것이 아니라 넘어간다** — 뒤 순위가 여전히 말할 기회를 갖는다. */
    @Test
    fun `unorderable first rank still lets later ranks speak`() {
        val links = listOf(Link("attr"), Link("mana"))
        val p = DuelFieldLinks.predict(links, mapOf("attr" to "불", "mana" to "90"), mapOf("attr" to "물", "mana" to "40"))
        assertEquals(Side.A, p.side)
        assertEquals("mana", p.decidedBy)
        assertEquals(2, p.rank)
    }

    @Test
    fun `nothing comparable yields unknown`() {
        val links = listOf(Link("attr"))
        val p = DuelFieldLinks.predict(links, mapOf("attr" to "불"), mapOf("attr" to "물"))
        assertEquals(Side.UNKNOWN, p.side)
        assertEquals(0, p.comparable)
        assertNull(p.decidedBy)

        val none = DuelFieldLinks.predict(emptyList(), emptyMap(), emptyMap())
        assertEquals(Side.UNKNOWN, none.side)
    }

    /** 전부 비기면 '갈리지 않았다'이지 '모른다'가 아니다 — 값은 있었다. */
    @Test
    fun `all ties report a tie not unknown`() {
        val links = listOf(Link("mana"), Link("speed"))
        val p = DuelFieldLinks.predict(links, mapOf("mana" to "50", "speed" to "7"), mapOf("mana" to "50", "speed" to "7"))
        assertEquals(Side.TIE, p.side)
        assertEquals(2, p.comparable)
    }

    // ── 대조 ──

    @Test
    fun `agreement counts only real reversals`() {
        val decided = DuelFieldLinks.Prediction(Side.A, "mana", 1, 1)
        assertEquals(Agreement.AGREE, DuelFieldLinks.agreementOf(decided, Side.A))
        assertEquals(Agreement.DISAGREE, DuelFieldLinks.agreementOf(decided, Side.B))
        // 무승부와 판정 불가는 어긋남이 아니다 — 세면 값을 안 적은 캐릭터가 소음이 된다.
        assertEquals(Agreement.UNDECIDED, DuelFieldLinks.agreementOf(decided, null))
        assertEquals(Agreement.UNKNOWN, DuelFieldLinks.agreementOf(DuelFieldLinks.Prediction(Side.UNKNOWN), Side.A))
        assertEquals(Agreement.UNDECIDED, DuelFieldLinks.agreementOf(DuelFieldLinks.Prediction(Side.TIE, null, 0, 2), Side.A))
    }

    // ── 산출 필드: 순위와의 대조 ──

    /** 순위가 위인 쪽을 필드가 아래로 보면 어긋남이다 — *"필드를 고칠 때가 됐다"*의 신호. */
    @Test
    fun `outcome field reports pairs the ranking disagrees with`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("power"),
            rankedCodes = listOf("A", "B", "C"),          // 대결 순위: A > B > C
            values = mapOf("A" to "10", "B" to "90", "C" to "50")
        )
        // 필드는 B > C > A라고 말한다 → 어긋난 짝은 (A,B)·(A,C) 둘.
        assertEquals(2, report.total)
        assertEquals(3, report.comparable)
        assertEquals(listOf("A" to "B", "A" to "C"), report.mismatches.map { it.higherCode to it.lowerCode })
    }

    @Test
    fun `outcome field agrees when the field follows the ranking`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("power"),
            rankedCodes = listOf("A", "B", "C"),
            values = mapOf("A" to "90", "B" to "50", "C" to "10")
        )
        assertEquals(0, report.total)
        assertTrue(report.mismatches.isEmpty())
    }

    /**
     * **견줄 수 있는 값이 없는 것과 어긋남이 없는 것은 다르다** — 둘 다 `total = 0`이지만
     * 사용자가 할 일이 다르므로 `comparable`이 그것을 가른다.
     */
    @Test
    fun `outcome field separates 'nothing to compare' from 'nothing wrong'`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("attr"),
            rankedCodes = listOf("A", "B"),
            values = mapOf("A" to "불", "B" to "물")
        )
        assertEquals(0, report.total)
        assertEquals(0, report.comparable)
    }

    /** 상한에 걸려도 **전량은 센다** — 조용히 자르지 않는다. */
    @Test
    fun `outcome field counts everything even when the list is capped`() {
        // 순위는 A>B>C>D인데 필드값은 정확히 반대라 모든 짝(6)이 어긋난다.
        val report = DuelFieldLinks.outcomeReport(
            Link("power"),
            rankedCodes = listOf("A", "B", "C", "D"),
            values = mapOf("A" to "1", "B" to "2", "C" to "3", "D" to "4"),
            limit = 2
        )
        assertEquals(6, report.total)
        assertEquals(2, report.mismatches.size)
    }

    /** 방향을 뒤집으면 어긋남도 뒤집힌다 — 낮을수록 유리한 산출 필드. */
    @Test
    fun `outcome direction flips what counts as a mismatch`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("rank", higherWins = false),
            rankedCodes = listOf("A", "B"),
            values = mapOf("A" to "1", "B" to "9")   // 낮을수록 유리 → 필드도 A가 위다
        )
        assertEquals(0, report.total)
    }

    // ── 축 단위 ──

    /** 같은 필드가 영향과 산출에 함께 걸리면 알린다 — 재료이면서 결과일 수는 없다. */
    @Test
    fun `axis reports fields linked as both material and outcome`() {
        val axis = DuelFieldLinks.Axis(
            influences = listOf(Link("mana"), Link("power")),
            outcomes = listOf(Link("power"))
        )
        assertEquals(listOf("power"), axis.conflicts)
        assertTrue(axis.hasAny)
        assertTrue(DuelFieldLinks.Axis().conflicts.isEmpty())
    }
}
