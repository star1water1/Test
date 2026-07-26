package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CardDisplayConfig
import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 연표 카드 필드값 요약 회귀 테스트 (B-5).
 *
 * 요점은 두 가지다: (1) 값이 있는데 목록에서 안 보이는 상태를 만들지 않는다,
 * (2) 상한 때문에 잘라낼 때는 잘랐다는 사실을 반드시 개수로 남긴다.
 */
class CardFieldSummaryTest {

    private fun field(id: Long, name: String, order: Int = 0, config: String = "{}") =
        FieldDefinition(
            id = id, universeId = 1, key = "k$id", name = name, type = "TEXT",
            config = config, displayOrder = order, entityType = FieldDefinition.ENTITY_EVENT
        )

    private val hidden = """{"cardDisplay":{"show":false}}"""

    @Test
    fun `값이 있는 필드는 카드 줄이 된다`() {
        val defs = listOf(field(1, "회차", order = 0), field(2, "챕터", order = 1))
        val rows = mapOf(10L to listOf(1L to "3화", 2L to "2장"))
        val s = CardFieldSummary.build(defs, rows, listOf(10L)).getValue(10L)
        assertEquals(listOf(CardFieldSummary.Line("회차", "3화"), CardFieldSummary.Line("챕터", "2장")), s.lines)
        assertEquals(0, s.hiddenCount)
    }

    @Test
    fun `줄 순서는 필드 표시 순서를 따른다`() {
        // 값 행이 뒤섞여 들어와도 카드마다 순서가 흔들리면 안 된다.
        val defs = listOf(field(1, "회차", order = 5), field(2, "챕터", order = 1))
        val rows = mapOf(10L to listOf(1L to "3화", 2L to "2장"))
        val s = CardFieldSummary.build(defs, rows, listOf(10L)).getValue(10L)
        assertEquals(listOf("챕터", "회차"), s.lines.map { it.label })
    }

    @Test
    fun `표시를 끈 필드는 줄이 되지 않는다`() {
        val defs = listOf(field(1, "회차"), field(2, "내부 메모", config = hidden))
        val rows = mapOf(10L to listOf(1L to "3화", 2L to "비공개"))
        val s = CardFieldSummary.build(defs, rows, listOf(10L)).getValue(10L)
        assertEquals(listOf("회차"), s.lines.map { it.label })
        // 사용자가 직접 끈 것은 '잘린 값'이 아니다 — 초과 개수로 세지 않는다.
        assertEquals(0, s.hiddenCount)
    }

    @Test
    fun `빈 값과 공백만 있는 값은 줄이 되지 않는다`() {
        val defs = listOf(field(1, "회차"), field(2, "챕터", order = 1))
        val rows = mapOf(10L to listOf(1L to "", 2L to "   "))
        assertNull(CardFieldSummary.build(defs, rows, listOf(10L))[10L])
    }

    @Test
    fun `상한을 넘으면 잘린 개수를 남긴다`() {
        // 존재조차 모르는 데이터를 만들지 않는다 — 잘랐으면 몇 개인지 말한다.
        val defs = (1..7L).map { field(it, "필드$it", order = it.toInt()) }
        val rows = mapOf(10L to (1..7L).map { it to "값$it" })
        val s = CardFieldSummary.build(defs, rows, listOf(10L)).getValue(10L)
        assertEquals(CardDisplayConfig.MAX_ON_CARD, s.lines.size)
        assertEquals(7 - CardDisplayConfig.MAX_ON_CARD, s.hiddenCount)
        assertEquals("필드1", s.lines.first().label)
    }

    @Test
    fun `상한은 호출부가 조정할 수 있다`() {
        val defs = (1..3L).map { field(it, "필드$it", order = it.toInt()) }
        val rows = mapOf(10L to (1..3L).map { it to "값$it" })
        val s = CardFieldSummary.build(defs, rows, listOf(10L), limit = 1).getValue(10L)
        assertEquals(1, s.lines.size)
        assertEquals(2, s.hiddenCount)
    }

    @Test
    fun `요청하지 않은 엔티티는 결과에 없다`() {
        val defs = listOf(field(1, "회차"))
        val rows = mapOf(10L to listOf(1L to "3화"), 11L to listOf(1L to "4화"))
        val out = CardFieldSummary.build(defs, rows, listOf(10L))
        assertEquals(setOf(10L), out.keys)
    }

    @Test
    fun `정의에 없는 필드 id의 값은 무시한다`() {
        // 다른 세계관 필드이거나 방금 지워진 정의를 가리키는 값 — 카드가 깨지지 않아야 한다.
        val defs = listOf(field(1, "회차"))
        val rows = mapOf(10L to listOf(1L to "3화", 999L to "유령"))
        val s = CardFieldSummary.build(defs, rows, listOf(10L)).getValue(10L)
        assertEquals(1, s.lines.size)
        assertEquals(0, s.hiddenCount)
    }

    @Test
    fun `필드가 없거나 대상이 없으면 빈 결과다`() {
        assertTrue(CardFieldSummary.build(emptyList(), emptyMap(), listOf(1L)).isEmpty())
        assertTrue(CardFieldSummary.build(listOf(field(1, "회차")), emptyMap(), emptyList()).isEmpty())
        assertTrue(CardFieldSummary.empty().isEmpty)
        assertFalse(
            CardFieldSummary.Summary(listOf(CardFieldSummary.Line("a", "b")), 0).isEmpty
        )
    }

    // ===== 설정 왕복 =====

    @Test
    fun `기본값은 표시이고 설정을 못 읽어도 숨기지 않는다`() {
        assertTrue(CardDisplayConfig.fromConfig("{}").show)
        assertTrue(CardDisplayConfig.fromConfig("깨진 JSON").show)
        assertTrue(CardDisplayConfig.fromConfig("""{"stats":{"enabled":true}}""").show)
    }

    @Test
    fun `설정은 다른 설정을 지우지 않고 왕복한다`() {
        val base = """{"stats":{"enabled":true},"structuredInput":{"enabled":false}}"""
        val off = CardDisplayConfig.applyToConfig(base, CardDisplayConfig(show = false))
        assertFalse(CardDisplayConfig.fromConfig(off).show)
        assertTrue(org.json.JSONObject(off).has("stats"))
        assertTrue(org.json.JSONObject(off).has("structuredInput"))

        // 기본값으로 되돌리면 키를 남기지 않는다 (config가 기본값으로 부풀지 않게).
        val backOn = CardDisplayConfig.applyToConfig(off, CardDisplayConfig(show = true))
        assertTrue(CardDisplayConfig.fromConfig(backOn).show)
        assertFalse(org.json.JSONObject(backOn).has("cardDisplay"))
        assertTrue(org.json.JSONObject(backOn).has("stats"))
    }
}
