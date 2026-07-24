package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 토큰화 단일 소스 회귀 가드.
 * splitForStats는 StatsDataProvider의 기존 Step 1 동작을 그대로 옮긴 것 — 케이스가 깨지면
 * 통계 결과가 바뀐다는 뜻이므로 구현이 아니라 케이스 기준으로 판단할 것.
 */
class FieldValueTokenizerTest {

    private fun fd(type: String, config: String = "{}") = FieldDefinition(
        id = 1L, universeId = 1L, key = "k", name = "필드", type = type, config = config
    )

    private val commaListConfig = """{"displayFormat":"comma_list"}"""
    private val bulletListConfig = """{"displayFormat":"bullet_list"}"""
    private val structuredConfig =
        """{"structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"키"},{"label":"체중"}]}}"""

    // ===== supportsLibrary =====

    @Test
    fun supportsLibrary_textSelectMultiTextGrade() {
        assertTrue(FieldValueTokenizer.supportsLibrary(fd("TEXT")))
        assertTrue(FieldValueTokenizer.supportsLibrary(fd("SELECT")))
        assertTrue(FieldValueTokenizer.supportsLibrary(fd("MULTI_TEXT")))
        assertTrue(FieldValueTokenizer.supportsLibrary(fd("GRADE")))
    }

    @Test
    fun supportsLibrary_excludesDerivedContinuousAndStructured() {
        assertFalse(FieldValueTokenizer.supportsLibrary(fd("CALCULATED")))
        assertFalse(FieldValueTokenizer.supportsLibrary(fd("NUMBER")))
        assertFalse(FieldValueTokenizer.supportsLibrary(fd("BODY_SIZE")))
        assertFalse(FieldValueTokenizer.supportsLibrary(fd("TEXT", structuredConfig)))
    }

    // ===== isMultiToken =====

    @Test
    fun isMultiToken_multiTextAndListFormats() {
        assertTrue(FieldValueTokenizer.isMultiToken(fd("MULTI_TEXT")))
        assertTrue(FieldValueTokenizer.isMultiToken(fd("TEXT", commaListConfig)))
        assertTrue(FieldValueTokenizer.isMultiToken(fd("TEXT", bulletListConfig)))
        assertFalse(FieldValueTokenizer.isMultiToken(fd("TEXT")))
        assertFalse(FieldValueTokenizer.isMultiToken(fd("SELECT")))
    }

    // ===== tokenize =====

    @Test
    fun tokenize_singleValue_trims() {
        assertEquals(listOf("서울"), FieldValueTokenizer.tokenize(fd("TEXT"), " 서울 "))
    }

    @Test
    fun tokenize_blank_returnsEmpty() {
        assertEquals(emptyList<String>(), FieldValueTokenizer.tokenize(fd("TEXT"), "  "))
        assertEquals(emptyList<String>(), FieldValueTokenizer.tokenize(fd("MULTI_TEXT"), ""))
    }

    @Test
    fun tokenize_multiToken_splitsTrimsDropsEmpty() {
        assertEquals(
            listOf("불", "얼음"),
            FieldValueTokenizer.tokenize(fd("MULTI_TEXT"), " 불 , 얼음 ,, ")
        )
        assertEquals(
            listOf("검", "방패"),
            FieldValueTokenizer.tokenize(fd("TEXT", commaListConfig), "검,방패")
        )
    }

    @Test
    fun tokenize_singleValueKeepsInternalComma_whenNotMultiToken() {
        // PLAIN 텍스트 필드의 콤마는 값의 일부 — 분리하지 않는다
        assertEquals(listOf("김철수, 2세"), FieldValueTokenizer.tokenize(fd("TEXT"), "김철수, 2세"))
    }

    // ===== splitForStats — 기존 통계 동작 고정 =====

    @Test
    fun splitForStats_plain_trimsButKeepsBlankAsSingleToken() {
        // 기존 동작: else 분기는 필터 없이 listOf(trim()) — 빈 문자열도 1토큰
        assertEquals(listOf("서울"), FieldValueTokenizer.splitForStats(fd("TEXT"), " 서울 "))
        assertEquals(listOf(""), FieldValueTokenizer.splitForStats(fd("TEXT"), "  "))
    }

    @Test
    fun splitForStats_commaFormats_splitTrimDropEmpty() {
        assertEquals(
            listOf("불", "얼음"),
            FieldValueTokenizer.splitForStats(fd("TEXT", commaListConfig), "불, 얼음,")
        )
        assertEquals(
            listOf("불", "얼음"),
            FieldValueTokenizer.splitForStats(fd("MULTI_TEXT"), "불, 얼음")
        )
        assertEquals(
            listOf("불"),
            FieldValueTokenizer.splitForStats(fd("TEXT", bulletListConfig), "불")
        )
    }

    @Test
    fun splitForStats_structuredParts_labeled() {
        assertEquals(
            listOf("키:180", "체중:75"),
            FieldValueTokenizer.splitForStats(fd("TEXT", structuredConfig), "180-75")
        )
    }

    @Test
    fun splitForStats_bodySizeWithoutParts_separatorSplit() {
        assertEquals(
            listOf("90", "60", "88"),
            FieldValueTokenizer.splitForStats(fd("BODY_SIZE"), "90-60-88")
        )
        // 파트 2개 미만이면 전체 값 1토큰
        assertEquals(
            listOf("측정불가"),
            FieldValueTokenizer.splitForStats(fd("BODY_SIZE"), "측정불가")
        )
    }

    // ===== join =====

    @Test
    fun join_commaSpace() {
        assertEquals("불, 얼음", FieldValueTokenizer.join(listOf("불", "얼음")))
        assertEquals("서울", FieldValueTokenizer.join(listOf("서울")))
    }
}
