package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.FieldValueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 정리 응답 파싱·검증 (AiService 미호출 — 응답 텍스트 주입).
 * 핵심 계약: 실존하지 않는 값을 참조하는 제안은 드롭하고 드롭 수를 보고한다 (변수 제어).
 */
class FieldLibraryAiOrganizerTest {

    private fun entry(
        value: String,
        aliases: List<String> = emptyList(),
        id: Long = value.hashCode().toLong(),
        description: String = ""
    ) =
        FieldValueEntry(
            id = id, fieldDefinitionId = 1L, value = value,
            aliasesJson = FieldValueEntry.aliasesToJson(aliases),
            description = description
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
    fun prompt_includesValueMeaning() {
        // B-46 — 시스템 프롬프트가 이미 *"이 설명을 병합·분류 판단의 근거로 삼아라"*라고
        // 말하는데 값의 뜻은 안 보내고 있었다. 뜻이 갈린 두 값을 합치라고 제안하면
        // 사용자가 그것을 받는 순간 구별이 사라진다.
        val text = FieldLibraryAiOrganizer.buildUserPrompt(
            listOf(
                entry("북부", description = "왕도 이북의 한랭지"),
                entry("북부지방", description = "남대륙 북쪽")
            )
        )
        assertTrue(text.contains("· 뜻: 왕도 이북의 한랭지"))
        assertTrue(text.contains("· 뜻: 남대륙 북쪽"))
        // 설명이 없는 값에는 빈 절을 붙이지 않는다
        assertFalse(FieldLibraryAiOrganizer.buildUserPrompt(listOf(entry("왕도"))).contains("뜻:"))
    }

    @Test
    fun prompt_valueMeaningIsFoldedToOneLine() {
        // 엔트리를 줄 단위로 잇는 형식이라 원문 개행이 행을 쪼개면 뒤쪽이 새 값처럼 읽힌다
        val text = FieldLibraryAiOrganizer.buildUserPrompt(
            listOf(entry("북부", description = "왕도 이북\n한랭지"))
        )
        assertEquals(1, text.lines().size)
        assertTrue(text.contains("· 뜻: 왕도 이북 한랭지"))
    }

    @Test
    fun chunking_countsDescriptionChars() {
        // 프롬프트에 실리는 것을 청크 예산이 안 세면 CHUNK_MAX_CHARS가 거짓이 되고,
        // 그 함수로 요청 수를 내는 **비용 고지가 함께 거짓이 된다**
        val long = "가".repeat(200)
        val bare = (1..60).map { entry("값$it", id = it.toLong()) }
        val described = (1..60).map { entry("값$it", id = it.toLong(), description = long) }
        assertEquals("짧은 쪽은 한 청크에 들어간다", 1, FieldLibraryAiOrganizer.chunkEntries(bare).size)
        assertTrue(
            "설명이 붙으면 청크가 쪼개진다(요청 수가 늘고 고지가 그것을 말한다)",
            FieldLibraryAiOrganizer.chunkEntries(described).size > 1
        )
        assertEquals(60, FieldLibraryAiOrganizer.chunkEntries(described).sumOf { it.size })
    }

    @Test
    fun systemPrompt_forbidsMergingDifferentMeanings() {
        val fd = FieldDefinition(
            id = 1L, universeId = 1L, key = "region", name = "거주지", type = FieldType.TEXT.name
        )
        val sys = FieldLibraryAiOrganizer.buildSystemPrompt(fd)
        assertTrue(sys.contains("뜻이 다른 두 값을 합치지 마라"))
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
