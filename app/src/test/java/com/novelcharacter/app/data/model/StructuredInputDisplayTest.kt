package com.novelcharacter.app.data.model

import com.novelcharacter.app.util.CardFieldSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 구조화 필드의 읽기 표시와 "폼이 실제로 그리는 구조"의 단일 소스(S8 관찰 `79--103.2-`).
 *
 * 지키는 것: ① 빈 파트도 자리를 지킨다(비었다는 사실이 사라지지 않는다) ② 구조화 근거가 없는
 * 자유 글은 파트로 몰아 그리지 않는다 ③ 폼·읽기·카드가 같은 기본 파트를 본다.
 */
class StructuredInputDisplayTest {

    private fun field(type: FieldType, config: String = "{}") = FieldDefinition(
        id = 1, universeId = 1, key = "k", name = "체형", type = type.name, config = config
    )

    private val fourParts = StructuredInputConfig(
        enabled = true,
        separator = "-",
        parts = listOf(
            StructuredInputConfig.Part("B", "cm", "number"),
            StructuredInputConfig.Part("W", "cm", "number"),
            StructuredInputConfig.Part("H", "cm", "number"),
            StructuredInputConfig.Part("어깨", "cm", "number")
        )
    )

    @Test
    fun `빈 파트는 표시로 자리를 지킨다`() {
        val e = StructuredInputConfig.EMPTY_PART
        assertEquals("B 79cm · W $e · H 103.2cm · 어깨 $e", fourParts.displayValue("79--103.2-"))
    }

    @Test
    fun `단위를 이미 적은 값에는 단위를 겹치지 않는다`() {
        assertEquals(
            "B 79cm · W 60cm · H 88cm · 어깨 40cm",
            fourParts.displayValue("79cm-60-88-40")
        )
    }

    @Test
    fun `구분자가 없는 자유 글은 구조화로 그리지 않는다`() {
        // 구조화를 켜기 전에 적은 글 — 첫 파트로 몰면 뜻을 바꾼다.
        assertNull(fourParts.displayValue("글래머"))
        assertNull(fourParts.displayValue("   "))
        assertNull(StructuredInputConfig().displayValue("79-60-88"))
    }

    @Test
    fun `신체 사이즈는 설정이 없어도 폼과 같은 B-W-H로 읽힌다`() {
        val eff = StructuredInputConfig.effectiveFor(field(FieldType.BODY_SIZE))
        assertSame(StructuredInputConfig.DEFAULT_BODY_SIZE, eff)
        assertEquals("B 82cm · W ${StructuredInputConfig.EMPTY_PART} · H 90cm", eff.displayValue("82--90"))
    }

    @Test
    fun `생일 역할 텍스트 필드는 월-일이다`() {
        val birth = field(FieldType.TEXT, """{"semanticRole":"birth_date"}""")
        assertSame(StructuredInputConfig.DEFAULT_BIRTH_DATE, StructuredInputConfig.effectiveFor(birth))
        // 읽기 화면은 저장 표기 MM-DD가 곧 표시 표기라 풀지 않는다.
        assertNull(StructuredInputConfig.readDisplay(birth, "03-15"))
    }

    @Test
    fun `설정을 켜 둔 필드는 기본 파트로 덮지 않는다`() {
        val cfg = """{"structuredInput":{"enabled":true,"separator":"/","parts":[{"label":"키","suffix":"cm"},{"label":"몸무게","suffix":"kg"}]}}"""
        val f = field(FieldType.BODY_SIZE, cfg)
        assertEquals("키 170cm · 몸무게 60kg", StructuredInputConfig.readDisplay(f, "170/60"))
    }

    @Test
    fun `파트 위젯이 없는 타입은 남은 설정이 있어도 구조화하지 않는다`() {
        val cfg = """{"structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"a"},{"label":"b"}]}}"""
        assertNull(StructuredInputConfig.effectiveFor(field(FieldType.NUMBER, cfg)).displayValue("1-2"))
        assertEquals("a 1 · b 2", StructuredInputConfig.effectiveFor(field(FieldType.TEXT, cfg)).displayValue("1-2"))
    }

    @Test
    fun `목록 카드 요약도 같은 모양으로 그린다`() {
        val defs = listOf(field(FieldType.BODY_SIZE))
        val s = CardFieldSummary.build(defs, mapOf(10L to listOf(1L to "82--90")), listOf(10L)).getValue(10L)
        assertEquals("B 82cm · W ${StructuredInputConfig.EMPTY_PART} · H 90cm", s.lines.single().value)
    }
}
