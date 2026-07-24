package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldValueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 정리 응답 파싱·검증 (AiService 미호출 — 응답 텍스트 주입).
 * 핵심 계약: 실존하지 않는 값을 참조하는 제안은 드롭하고 드롭 수를 보고한다 (변수 제어).
 */
class FieldLibraryAiOrganizerTest {

    private fun entry(value: String, aliases: List<String> = emptyList(), id: Long = value.hashCode().toLong()) =
        FieldValueEntry(
            id = id, fieldDefinitionId = 1L, value = value,
            aliasesJson = FieldValueEntry.aliasesToJson(aliases)
        )

    private val entries = listOf(entry("서울"), entry("서울시"), entry("부산", aliases = listOf("부산시")))

    @Test
    fun validMergeAndCategory_parsed() {
        val text = """{"merges":[{"canonical":"서울","variants":["서울시"],"reason":"동일 지역"}],
                       "categories":[{"value":"부산","category":"영남"}]}"""
        val parsed = FieldLibraryAiOrganizer.parseResponse(text, entries)!!
        assertEquals(1, parsed.merges.size)
        assertEquals("서울", parsed.merges[0].canonical)
        assertEquals(listOf("서울시"), parsed.merges[0].variants)
        assertEquals(1, parsed.categories.size)
        assertEquals(0, parsed.droppedCount)
    }

    @Test
    fun hallucinatedValues_droppedAndCounted() {
        val text = """{"merges":[
            {"canonical":"한양","variants":["서울시"],"reason":"환각 canonical"},
            {"canonical":"서울","variants":["서울특별시"],"reason":"환각 variant"}
        ],"categories":[{"value":"대구","category":"영남"}]}"""
        val parsed = FieldLibraryAiOrganizer.parseResponse(text, entries)!!
        assertTrue(parsed.merges.isEmpty())
        assertTrue(parsed.categories.isEmpty())
        assertEquals(3, parsed.droppedCount)  // 환각 canonical 1 + 환각 variant 1 + 환각 category value 1
    }

    @Test
    fun alreadyAliasedVariant_dropped() {
        // '부산시'는 이미 '부산'의 별칭 — 재병합 제안은 드롭
        val text = """{"merges":[{"canonical":"부산","variants":["부산시"],"reason":"기병합"}],"categories":[]}"""
        val parsed = FieldLibraryAiOrganizer.parseResponse(text, entries)!!
        assertTrue(parsed.merges.isEmpty())
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun selfMerge_dropped() {
        val text = """{"merges":[{"canonical":"서울","variants":["서울"],"reason":"자기 자신"}],"categories":[]}"""
        val parsed = FieldLibraryAiOrganizer.parseResponse(text, entries)!!
        assertTrue(parsed.merges.isEmpty())
    }

    @Test
    fun unparseableResponse_returnsNull() {
        assertNull(FieldLibraryAiOrganizer.parseResponse("정리할 것이 없습니다.", entries))
    }

    @Test
    fun chunking_respectsMaxValues() {
        val many = (1..250).map { entry("값$it", id = it.toLong()) }
        val chunks = FieldLibraryAiOrganizer.chunkEntries(many)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.size <= FieldLibraryAiOrganizer.CHUNK_MAX_VALUES })
        assertEquals(250, chunks.sumOf { it.size })
    }

    @Test
    fun prompt_includesUsageAndAliases() {
        val text = FieldLibraryAiOrganizer.buildUserPrompt(
            listOf(entry("서울").copy(usageCount = 3), entry("부산", aliases = listOf("부산시")))
        )
        assertTrue(text.contains("서울 (사용 3회)"))
        assertTrue(text.contains("별칭: 부산시"))
    }
}
