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

    // ===== B-177 — 쉼표를 쪼개는 열에 안내를 붙인다. 쓰기는 하나, 읽기는 둘 =====

    private fun multiField(id: Long, name: String, universeId: Long?) = FieldDefinition(
        id = id, universeId = universeId, key = "k$id", name = name, type = "MULTI_TEXT",
        entityType = FieldDefinition.ENTITY_EVENT
    )

    @Test
    fun multiTokenField_getsCommaSuffix() {
        val headers = EntityFieldHeaders.headersFor(listOf(multiField(1, "참가자", 1)), universes)
        assertEquals(listOf("필드:참가자 (쉼표 구분)"), headers.map { it.second })
        // 쪼개지 않는 필드는 그대로 — 안내가 거짓이 되면 안 된다
        assertEquals(
            listOf("필드:규모"),
            EntityFieldHeaders.headersFor(listOf(field(2, "규모", 1)), universes).map { it.second }
        )
    }

    @Test
    fun multiTokenField_suffixComesAfterQualifier() {
        val fields = listOf(multiField(1, "참가자", 1), multiField(2, "참가자", 2))
        assertEquals(
            listOf("필드:참가자(아스트라) (쉼표 구분)", "필드:참가자(다른세계) (쉼표 구분)"),
            EntityFieldHeaders.headersFor(fields, universes).map { it.second }
        )
    }

    @Test
    fun legacyHeaderWithoutSuffix_stillResolvesExactly() {
        // **이것이 없으면 이미 내보낸 파일의 그 열이 통째로 '알 수 없는 열'이 되어 값이 버려진다.**
        // 되돌리면 빨간불 — 쓰기는 새 머리 하나, 읽기는 둘(R-2의 취지).
        val fields = listOf(multiField(1, "참가자", 1))
        val expected = EntityFieldHeaders.expectedHeaders(fields, universes)
        assertEquals(1L, expected["필드:참가자 (쉼표 구분)"]?.id)
        assertEquals(1L, expected["필드:참가자"]?.id)
    }

    @Test
    fun fieldNamedLikeTheSuffix_keepsItsOwnHeader() {
        // 이름이 실제로 '참가자 (쉼표 구분)'인 필드가 있으면 **그 필드가 그 머리를 갖는다** —
        // 옛 머리 폴백이 남의 자리를 빼앗으면 값이 엉뚱한 필드에 붙는다(R-1).
        val literal = field(9, "참가자 (쉼표 구분)", 1)
        val multi = multiField(1, "참가자", 1)
        val expected = EntityFieldHeaders.expectedHeaders(listOf(literal, multi), universes)
        assertEquals(9L, expected["필드:참가자 (쉼표 구분)"]?.id)

        val parsed = EntityFieldHeaders.parseFallback(
            "필드:참가자 (쉼표 구분)", setOf("참가자 (쉼표 구분)"), setOf("아스트라")
        )!!
        assertEquals("참가자 (쉼표 구분)", parsed.fieldName)
        assertNull(parsed.universeName)
    }

    @Test
    fun fallback_stripsSuffixThenResolves() {
        val plain = EntityFieldHeaders.parseFallback("필드:참가자 (쉼표 구분)", setOf("참가자"), setOf("아스트라"))!!
        assertEquals("참가자", plain.fieldName)
        assertNull(plain.universeName)

        val qualified = EntityFieldHeaders
            .parseFallback("필드:참가자(아스트라) (쉼표 구분)", emptySet(), setOf("아스트라"))!!
        assertEquals("참가자", qualified.fieldName)
        assertEquals("아스트라", qualified.universeName)
    }

    // ===== 전역 구역의 한정자 — 종전에는 사람이 읽는 열 머리에 'null'이 나갔다 =====

    @Test
    fun globalField_usesReadableQualifier() {
        val fields = listOf(field(1, "공통메모", 1), FieldDefinition(
            id = 2, universeId = null, key = "k2", name = "공통메모", type = "TEXT",
            entityType = FieldDefinition.ENTITY_EVENT
        ))
        val headers = EntityFieldHeaders.headersFor(fields, universes).map { it.second }
        assertEquals(listOf("필드:공통메모(아스트라)", "필드:공통메모(전역)"), headers)

        val parsed = EntityFieldHeaders.parseFallback("필드:공통메모(전역)", emptySet(), setOf("아스트라"))!!
        assertEquals("공통메모", parsed.fieldName)
        assertEquals(EntityFieldHeaders.GLOBAL_QUALIFIER, parsed.universeName)
    }

    // ===== B-65 — 같은 머리를 두 필드가 만들면 열은 하나, 나머지는 오버플로 =====

    @Test
    fun plan_keepsOneColumnPerHeader_andReportsTheRest() {
        // 유니크 색인은 (구역, 대상, 키)라 **같은 구역에 같은 이름의 필드가 둘** 있을 수 있다.
        // 종전에는 같은 이름의 열이 둘 그려졌고 가져오기가 뒤쪽을 버려, 그 값은 결코 돌아오지 못했다.
        val first = field(1, "규모", 1)
        val second = FieldDefinition(
            id = 2, universeId = 1, key = "k2", name = "규모", type = "TEXT",
            entityType = FieldDefinition.ENTITY_EVENT
        )
        val plan = EntityFieldHeaders.plan(listOf(first, second), universes)
        assertEquals(listOf("필드:규모(아스트라)"), plan.columns.map { it.second })
        assertEquals(setOf(1L), plan.coveredFieldIds)
    }

    @Test
    fun plan_coversEveryFieldWhenHeadersAreUnique() {
        val fields = listOf(field(1, "규모", 1), field(2, "지역", 2))
        val plan = EntityFieldHeaders.plan(fields, universes)
        assertEquals(2, plan.columns.size)
        assertEquals(setOf(1L, 2L), plan.coveredFieldIds)
    }
}
