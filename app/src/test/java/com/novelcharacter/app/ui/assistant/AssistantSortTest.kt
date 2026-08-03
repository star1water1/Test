package com.novelcharacter.app.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 어시스턴트 보기 정렬(B-85) — 기준 3종과 **비파괴 계약**을 든다.
 *
 * 가장 중요한 것은 `sort_neverChangesMembership`이다: 정렬이 목록의 내용을 바꾸면
 * 편향 카드 상한과 겹쳐 "정렬을 바꿨더니 카드가 사라진다"가 된다(AssistantSort의 계약).
 */
class AssistantSortTest {

    private fun card(
        id: String,
        category: InsightCategory,
        severity: Int,
        count: Int = 0
    ) = AssistantInsight(
        id = id, category = category, severity = severity,
        title = id, detail = "", count = count
    )

    private fun ids(list: List<AssistantInsight>, mode: AssistantSort.Mode): List<String> =
        AssistantSort.sort(list, mode).map { it.id }

    // ── 기본 기준 = 종전 엔진 정렬 ──

    @Test
    fun severity_ordersByseverityThenId() {
        val list = listOf(
            card("b", InsightCategory.NUDGE, 20),
            card("a", InsightCategory.CONSISTENCY, 100),
            card("c", InsightCategory.BIAS, 20)
        )
        assertEquals(listOf("a", "b", "c"), ids(list, AssistantSort.Mode.SEVERITY))
    }

    @Test
    fun severity_isTheDefaultMode() {
        assertEquals(AssistantSort.Mode.SEVERITY, AssistantSort.DEFAULT)
    }

    // ── 분류순 ──

    @Test
    fun category_groupsByDeclarationOrder_notBySeverity() {
        // 넛지가 심각도는 더 높지만 분류순에서는 정합성 → 건강 → 편향 → 넛지 순서를 따른다.
        val list = listOf(
            card("nudge", InsightCategory.NUDGE, 90),
            card("bias", InsightCategory.BIAS, 80),
            card("consistency", InsightCategory.CONSISTENCY, 10),
            card("health", InsightCategory.HEALTH, 70)
        )
        assertEquals(
            listOf("consistency", "health", "bias", "nudge"),
            ids(list, AssistantSort.Mode.CATEGORY)
        )
    }

    @Test
    fun category_withinSameCategory_fallsBackToSeverity() {
        val list = listOf(
            card("low", InsightCategory.HEALTH, 10),
            card("high", InsightCategory.HEALTH, 90)
        )
        assertEquals(listOf("high", "low"), ids(list, AssistantSort.Mode.CATEGORY))
    }

    // ── 항목 많은 순 ──

    @Test
    fun count_ordersByCountDescending() {
        val list = listOf(
            card("few", InsightCategory.HEALTH, 50, count = 2),
            card("many", InsightCategory.NUDGE, 10, count = 30),
            card("mid", InsightCategory.BIAS, 90, count = 9)
        )
        assertEquals(listOf("many", "mid", "few"), ids(list, AssistantSort.Mode.COUNT))
    }

    @Test
    fun count_cardsWithoutCount_sinkToBottom() {
        val list = listOf(
            card("nocount", InsightCategory.CONSISTENCY, 100, count = 0),
            card("counted", InsightCategory.NUDGE, 10, count = 1)
        )
        assertEquals(listOf("counted", "nocount"), ids(list, AssistantSort.Mode.COUNT))
    }

    @Test
    fun count_ties_fallBackToSeverity() {
        val list = listOf(
            card("weak", InsightCategory.NUDGE, 10, count = 5),
            card("strong", InsightCategory.CONSISTENCY, 100, count = 5)
        )
        assertEquals(listOf("strong", "weak"), ids(list, AssistantSort.Mode.COUNT))
    }

    // ── 비파괴 계약 ──

    @Test
    fun sort_neverChangesMembership() {
        val list = listOf(
            card("a", InsightCategory.CONSISTENCY, 100, count = 0),
            card("b", InsightCategory.BIAS, 45, count = 12),
            card("c", InsightCategory.NUDGE, 18, count = 3),
            card("d", InsightCategory.HEALTH, 55, count = 3)
        )
        val expected = list.map { it.id }.toSet()
        for (mode in AssistantSort.Mode.values()) {
            val sorted = AssistantSort.sort(list, mode)
            assertEquals("$mode 에서 카드 수가 변했다", list.size, sorted.size)
            assertEquals("$mode 에서 카드 구성이 변했다", expected, sorted.map { it.id }.toSet())
        }
    }

    @Test
    fun sort_isTotalOrder_sameInputGivesSameOutput() {
        // id까지 타이브레이크가 닿으므로 입력 순서가 달라도 결과가 같다(회전·새로고침 안정성).
        val list = listOf(
            card("a", InsightCategory.HEALTH, 50, count = 3),
            card("b", InsightCategory.HEALTH, 50, count = 3),
            card("c", InsightCategory.HEALTH, 50, count = 3)
        )
        for (mode in AssistantSort.Mode.values()) {
            assertEquals(ids(list, mode), ids(list.reversed(), mode))
        }
    }

    // ── 저장값 해석 ──

    @Test
    fun parse_roundTripsEveryMode() {
        for (mode in AssistantSort.Mode.values()) {
            assertEquals(mode, AssistantSort.parse(mode.name))
        }
    }

    @Test
    fun parse_unknownOrNull_fallsBackToDefault() {
        assertEquals(AssistantSort.DEFAULT, AssistantSort.parse(null))
        assertEquals(AssistantSort.DEFAULT, AssistantSort.parse(""))
        assertEquals(AssistantSort.DEFAULT, AssistantSort.parse("SEVERITY_DESC_LEGACY"))
    }
}
