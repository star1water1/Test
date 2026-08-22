package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NarrativeDensityCurve] — 서사 밀도 곡선의 항목 수가 **연도 폭에 붙지 않는가**.
 *
 * 이 앱은 판타지 역법을 일급으로 다루므로 폭 12만 년짜리 연표가 정당한 데이터다. 그것을
 * 거부하지 않고 접는 것이 처분이고, 접었을 때도 **합은 한 건도 잃지 않는다**.
 */
class NarrativeDensityCurveTest {

    private val CAP = DisplayCap.DENSITY_CURVE_BUCKETS

    // ── ① 상한 아래에서는 종전과 한 칸도 다르지 않다 ──────────────────────────

    @Test
    fun `빈 연도는 0으로 채워진다`() {
        val out = NarrativeDensityCurve.build(mapOf(1000 to 2, 1003 to 1), CAP)
        assertEquals(4, out.size)
        assertEquals(listOf(2, 0, 0, 1), out.map { it.count })
        assertEquals(listOf(1000, 1001, 1002, 1003), out.map { it.fromYear })
        assertTrue("접히지 않은 칸은 한 해다", out.none { it.folded })
    }

    @Test
    fun `한 해뿐이면 한 칸이다`() {
        val out = NarrativeDensityCurve.build(mapOf(7 to 5), CAP)
        assertEquals(1, out.size)
        assertEquals(7, out.single().fromYear)
        assertEquals(7, out.single().toYear)
        assertEquals(5, out.single().count)
    }

    @Test
    fun `비어 있으면 빈 곡선이다`() {
        assertTrue(NarrativeDensityCurve.build(emptyMap(), CAP).isEmpty())
    }

    /** 폭이 정확히 상한이면 아직 접지 않는다 — 경계에서 화면이 바뀌지 않게. */
    @Test
    fun `폭이 상한과 같으면 접지 않는다`() {
        val out = NarrativeDensityCurve.build(mapOf(0 to 1, CAP - 1 to 1), CAP)
        assertEquals(CAP, out.size)
        assertTrue(out.none { it.folded })
    }

    // ── ② 넘으면 접는다 — 칸 수는 상한, 합은 보존 ────────────────────────────

    @Test
    fun `폭이 십이만이어도 칸 수는 상한 이내이고 합이 보존된다`() {
        val density = mapOf(1 to 3, 60000 to 7, 120001 to 5)
        val out = NarrativeDensityCurve.build(density, CAP)
        assertTrue("칸 수: ${out.size}", out.size <= CAP)
        assertEquals(15, out.sumOf { it.count })
        assertTrue("접혔으면 접혔다고 말할 수 있어야 한다", out.any { it.folded })
    }

    @Test
    fun `접힌 구간은 경계가 이어지고 실측 범위를 덮는다`() {
        val out = NarrativeDensityCurve.build(mapOf(-500 to 1, 900000 to 2), CAP)
        assertEquals(-500, out.first().fromYear)
        assertEquals(900000, out.last().toYear)
        for (i in 1 until out.size) {
            assertEquals("구간에 틈이 있다", out[i - 1].toYear + 1, out[i].fromYear)
        }
    }

    /** 사건이 둘뿐이면 폭이 얼마든 만드는 것은 상한 규모다 — 이것이 결함의 핵심이었다. */
    @Test
    fun `사건이 둘뿐이면 어떤 폭에서도 상한 규모만 만든다`() {
        for (span in listOf(1_000, 1_000_000, 100_000_000)) {
            val out = NarrativeDensityCurve.build(mapOf(0 to 1, span to 1), CAP)
            assertTrue("span=$span 칸=${out.size}", out.size <= CAP)
            assertEquals("span=$span", 2, out.sumOf { it.count })
        }
    }

    /**
     * **Int 뺄셈이 넘치는 입력** — 기원전 표기와 큰 연도가 섞이면 실제로 닿는다.
     * 넘치면 폭이 음수가 되어 상한 판정이 거꾸로 서고, 접지 않은 채 그대로 터진다.
     */
    @Test
    fun `연도 폭이 Int를 넘어도 접힌다`() {
        val out = NarrativeDensityCurve.build(
            mapOf(Int.MIN_VALUE to 4, 0 to 1, Int.MAX_VALUE to 2), CAP
        )
        assertTrue("칸=${out.size}", out.size <= CAP)
        assertEquals(7, out.sumOf { it.count })
        assertEquals(Int.MIN_VALUE, out.first().fromYear)
        assertEquals(Int.MAX_VALUE, out.last().toYear)
        assertTrue(out.all { it.fromYear <= it.toYear })
    }

    @Test
    fun `같은 입력은 언제나 같은 곡선을 낸다`() {
        val density = (0..300).associateWith { it % 3 }
        assertEquals(
            NarrativeDensityCurve.build(density, CAP),
            NarrativeDensityCurve.build(density, CAP)
        )
    }

    @Test
    fun `상한이 0이면 그리지 않는다`() {
        assertTrue(NarrativeDensityCurve.build(mapOf(1 to 1), 0).isEmpty())
    }

    @Test
    fun `접히지 않은 칸은 folded가 거짓이다`() {
        val out = NarrativeDensityCurve.build(mapOf(3 to 1, 4 to 1), CAP)
        assertFalse(out.first().folded)
    }
}
