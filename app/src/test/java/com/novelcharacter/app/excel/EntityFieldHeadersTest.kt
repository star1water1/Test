package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 연표 사건 필드 헤더 — 내보내기 규칙과 가져오기 역함수가 정확히 맞물리는지(무편집 왕복 무결). */
class EntityFieldHeadersTest {

    private fun field(id: Long, name: String, universeId: Long) = FieldDefinition(
        id = id, universeId = universeId, key = "k$id", name = name, type = "TEXT",
        entityType = FieldDefinition.ENTITY_EVENT
    )

    private val universes = mapOf(1L to "아스트라", 2L to "다른세계")

    @Test
    fun uniqueName_noUniverseSuffix() {
        val fields = listOf(field(1, "규모", 1))
        assertEquals(listOf("필드:규모"), EntityFieldHeaders.headersFor(fields, universes).map { it.second })
    }

    @Test
    fun duplicateName_getsUniverseSuffix() {
        val fields = listOf(field(1, "규모", 1), field(2, "규모", 2))
        assertEquals(
            listOf("필드:규모(아스트라)", "필드:규모(다른세계)"),
            EntityFieldHeaders.headersFor(fields, universes).map { it.second }
        )
    }

    @Test
    fun parenthesizedFieldName_roundTripsExactly() {
        // 회귀: '규모(명)'는 정규식 추측 파싱에서 세계관 한정으로 오인돼 열 전체가 버려졌다
        val fields = listOf(field(1, "규모(명)", 1))
        val headers = EntityFieldHeaders.headersFor(fields, universes)
        assertEquals("필드:규모(명)", headers[0].second)

        val expected = EntityFieldHeaders.expectedHeaders(fields, universes)
        assertEquals(1L, expected["필드:규모(명)"]?.id)
    }

    @Test
    fun parenthesizedNameWithDuplicate_stillResolvesExactly() {
        val fields = listOf(field(1, "규모(명)", 1), field(2, "규모(명)", 2))
        val expected = EntityFieldHeaders.expectedHeaders(fields, universes)
        assertEquals(1L, expected["필드:규모(명)(아스트라)"]?.id)
        assertEquals(2L, expected["필드:규모(명)(다른세계)"]?.id)
    }

    @Test
    fun universeNameWithParens_roundTripsExactly() {
        val u = mapOf(1L to "아스트라(리부트)", 2L to "다른세계")
        val fields = listOf(field(1, "규모", 1), field(2, "규모", 2))
        val expected = EntityFieldHeaders.expectedHeaders(fields, u)
        assertEquals(1L, expected["필드:규모(아스트라(리부트))"]?.id)
    }

    @Test
    fun fallback_exactFieldNameWins_overQualifierGuess() {
        val names = setOf("규모(명)")
        val parsed = EntityFieldHeaders.parseFallback("필드:규모(명)", names, setOf("아스트라"))!!
        assertEquals("규모(명)", parsed.fieldName)
        assertNull(parsed.universeName)
    }

    @Test
    fun fallback_qualifierOnlyWhenUniverseExists() {
        val hit = EntityFieldHeaders.parseFallback("필드:규모(아스트라)", emptySet(), setOf("아스트라"))!!
        assertEquals("규모", hit.fieldName)
        assertEquals("아스트라", hit.universeName)

        // 실존 세계관이 아니면 한정자로 보지 않는다 — 이름의 일부로 취급
        val miss = EntityFieldHeaders.parseFallback("필드:규모(명)", emptySet(), setOf("아스트라"))!!
        assertEquals("규모(명)", miss.fieldName)
        assertNull(miss.universeName)
    }

    @Test
    fun nonFieldHeader_isNotAColumn() {
        assertNull(EntityFieldHeaders.parseFallback("연도", emptySet(), emptySet()))
        assertNull(EntityFieldHeaders.parseFallback("세계관코드", emptySet(), emptySet()))
    }
}
