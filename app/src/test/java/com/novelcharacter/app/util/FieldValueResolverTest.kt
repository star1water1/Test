package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.repository.FieldValueLibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldValueResolverTest {

    private fun entry(
        value: String,
        label: String = "",
        aliases: List<String> = emptyList(),
        category: String = "",
        id: Long = value.hashCode().toLong()
    ) = FieldValueEntry(
        id = id, fieldDefinitionId = 1L, value = value, displayLabel = label,
        aliasesJson = FieldValueEntry.aliasesToJson(aliases), category = category
    )

    // ===== canonical / display =====

    @Test
    fun canonical_foldsAliasAndTrims() {
        val r = FieldValueResolver(listOf(entry("서울", aliases = listOf("서울시", "Seoul"))))
        assertEquals("서울", r.canonical("서울"))
        assertEquals("서울", r.canonical("서울시"))
        assertEquals("서울", r.canonical(" Seoul "))
        assertEquals("부산", r.canonical(" 부산 "))  // 미등록 → trim만
    }

    @Test
    fun display_labelFallsBackToValue() {
        val r = FieldValueResolver(listOf(entry("m", label = "남성"), entry("f")))
        assertEquals("남성", r.display("m"))
        assertEquals("f", r.display("f"))
        assertEquals("x", r.display("x"))
    }

    // ===== 카테고리 라벨 체인 (구 valueCategories 라벨 키공간 동작 보존) =====

    @Test
    fun category_directOnEntry() {
        val r = FieldValueResolver(listOf(entry("청염", category = "불")))
        assertEquals("불", r.category("청염"))
    }

    @Test
    fun category_chainsThroughDisplayLabel() {
        // 구 데이터: valueLabels {"seoul"→"서울"}, valueCategories {"서울"→"수도권"}
        // 시드 후: entry(seoul, label=서울) + entry(서울, category=수도권)일 수 있다 — 체인 유지
        val r = FieldValueResolver(
            listOf(
                entry("seoul", label = "서울"),
                entry("서울", category = "수도권")
            )
        )
        assertEquals("수도권", r.category("seoul"))
        assertEquals("수도권", r.category("서울"))
        assertNull(r.category("부산"))
    }

    // ===== statsKeys =====

    @Test
    fun statsKeys_valueMode_returnsLabel() {
        val r = FieldValueResolver(listOf(entry("청염", label = "푸른 불꽃", category = "불")))
        assertEquals(listOf("푸른 불꽃"), r.statsKeys("청염", "value"))
    }

    @Test
    fun statsKeys_categoryMode_fallsBackToLabelWhenNoCategory() {
        val r = FieldValueResolver(listOf(entry("청염", category = "불"), entry("빙결")))
        assertEquals(listOf("불"), r.statsKeys("청염", "category"))
        assertEquals(listOf("빙결"), r.statsKeys("빙결", "category"))
    }

    @Test
    fun statsKeys_bothMode() {
        val r = FieldValueResolver(listOf(entry("청염", category = "불"), entry("빙결")))
        assertEquals(listOf("청염", "불"), r.statsKeys("청염", "both"))
        assertEquals(listOf("빙결"), r.statsKeys("빙결", "both"))
    }

    @Test
    fun statsKeys_aliasResolvesBeforeGrouping() {
        val r = FieldValueResolver(listOf(entry("서울", label = "서울특별시", aliases = listOf("서울시"))))
        assertEquals(listOf("서울특별시"), r.statsKeys("서울시", "value"))
    }

    // ===== expandForFilter =====

    @Test
    fun expandForFilter_includesAliases() {
        val r = FieldValueResolver(listOf(entry("서울", aliases = listOf("서울시", "Seoul"))))
        assertEquals(listOf("서울", "서울시", "Seoul"), r.expandForFilter("서울"))
        assertEquals(listOf("부산"), r.expandForFilter("부산"))
    }

    @Test
    fun emptyResolver_identity() {
        val r = FieldValueResolver(emptyList())
        assertTrue(r.isEmpty)
        assertEquals("서울", r.canonical(" 서울 "))
        assertEquals(listOf("서울"), r.statsKeys("서울", "both"))
    }

    // ===== validateRestricted =====

    private fun fd(type: String, config: String = "{}") = FieldDefinition(
        id = 1L, universeId = 1L, key = "k", name = "필드", type = type, config = config
    )

    @Test
    fun validateRestricted_allowsCanonicalAndAlias() {
        val entries = listOf(entry("서울", aliases = listOf("서울시")), entry("부산"))
        assertEquals(
            emptyList<String>(),
            FieldValueLibraryRepository.validateRestricted(fd("TEXT"), " 서울시 ", entries)
        )
    }

    @Test
    fun validateRestricted_reportsUnknownTokensOnly() {
        val entries = listOf(entry("서울"))
        val fdMulti = fd("MULTI_TEXT")
        assertEquals(
            listOf("한양"),
            FieldValueLibraryRepository.validateRestricted(fdMulti, "서울, 한양", entries)
        )
        // 빈 값은 위반 아님 (필수 여부는 isRequired의 몫)
        assertEquals(
            emptyList<String>(),
            FieldValueLibraryRepository.validateRestricted(fdMulti, "  ", entries)
        )
    }

    // ===== conflictOf =====

    @Test
    fun conflictOf_detectsValueAndAliasCollisions() {
        val a = entry("서울", aliases = listOf("서울시"), id = 1)
        val b = entry("부산", id = 2)
        assertEquals(a to false, FieldValueLibraryRepository.conflictOf(listOf(a, b), "서울", null))
        assertEquals(a to true, FieldValueLibraryRepository.conflictOf(listOf(a, b), "서울시", null))
        assertNull(FieldValueLibraryRepository.conflictOf(listOf(a, b), "대구", null))
        assertNull(FieldValueLibraryRepository.conflictOf(listOf(a, b), "서울", 1L))
    }
}
