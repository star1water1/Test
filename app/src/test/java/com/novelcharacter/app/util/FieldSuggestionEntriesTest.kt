package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldValueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 폼 자동완성 제안의 잣대 (B-129).
 *
 * 이 시험이 지키는 것은 둘이고 **서로 반대 방향**이다:
 * ① 잣대가 종전 SQL(`getForUniverse`)과 글자 그대로 같은가 — 갈리면 폼이 권하는 값과
 *    라이브러리가 든 값이 달라지고, 화면에서는 어느 쪽이 옳은지 알 길이 없다.
 * ② 그런데 **세계관을 묻지 않는가** — 묻는 순간 전역 구역(무소속)에서 제안이 통째로 죽는다.
 *    종전 질의가 정확히 그 모양이었다.
 */
class FieldSuggestionEntriesTest {

    private fun entry(
        fieldId: Long,
        value: String,
        usageCount: Int = 0,
        hidden: Boolean = false
    ) = FieldValueEntry(
        id = 0, fieldDefinitionId = fieldId, value = value,
        isHidden = hidden, usageCount = usageCount, code = "c-$fieldId-$value"
    )

    @Test
    fun `숨긴 값은 제안에 서지 않는다`() {
        // 저장소는 숨김까지 준다(허용 목록 판정은 숨긴 값도 목록의 일부로 보기 때문이다).
        // 제안은 그 계약에서 숨김을 빼는 쪽이고, 그 뺄셈이 여기다.
        val entries = listOf(
            entry(1, "여성"),
            entry(1, "폐기값", hidden = true)
        )
        assertEquals(listOf("여성"), FieldSuggestionEntries.suggestable(entries).map { it.value })
    }

    @Test
    fun `사용 횟수가 많은 값이 먼저 온다`() {
        val entries = listOf(entry(1, "남성", usageCount = 3), entry(1, "여성", usageCount = 11))
        assertEquals(listOf("여성", "남성"), FieldSuggestionEntries.suggestable(entries).map { it.value })
    }

    @Test
    fun `사용 횟수가 같으면 값 순서로 갈린다`() {
        // 동률에서 순서가 흔들리면 같은 폼을 두 번 열 때 목록이 뒤바뀌어 보인다.
        val entries = listOf(entry(1, "다"), entry(1, "가"), entry(1, "나"))
        assertEquals(listOf("가", "나", "다"), FieldSuggestionEntries.suggestable(entries).map { it.value })
    }

    @Test
    fun `제안이 세계관을 묻지 않는다 — 전역 구역에서도 선다`() {
        // 종전 질의는 `fd.universeId = :universeId`라 전역 구역(universeId IS NULL)에서
        // **원리적으로** 빈 목록이었다. 엔트리는 필드 id로만 매이므로 구역이 개입할 자리가 없다.
        val byField = mapOf(7L to listOf(entry(7, "장편"), entry(7, "단편", usageCount = 2)))
        val out = FieldSuggestionEntries.from(byField)
        assertEquals(listOf("단편", "장편"), out.getValue(7L).map { it.value })
    }

    @Test
    fun `전부 숨긴 필드는 키 자체를 남기지 않는다`() {
        // 빈 목록을 남기면 호출부가 `isNotEmpty()` 검사를 잊었을 때 빈 어댑터가 붙는다.
        val byField = mapOf(
            1L to listOf(entry(1, "쓰는값")),
            2L to listOf(entry(2, "숨김1", hidden = true), entry(2, "숨김2", hidden = true))
        )
        val out = FieldSuggestionEntries.from(byField)
        assertTrue(out.containsKey(1L))
        assertFalse(out.containsKey(2L))
    }

    @Test
    fun `필드마다 따로 고른다`() {
        // 한 폼이 여러 필드를 그리므로, 섞이면 A 필드에 B 필드의 값이 권해진다.
        val byField = mapOf(
            1L to listOf(entry(1, "여성"), entry(1, "남성")),
            2L to listOf(entry(2, "서울"))
        )
        val out = FieldSuggestionEntries.from(byField)
        assertEquals(listOf("남성", "여성"), out.getValue(1L).map { it.value })
        assertEquals(listOf("서울"), out.getValue(2L).map { it.value })
    }

    @Test
    fun `엔트리가 없으면 빈 맵이다`() {
        assertTrue(FieldSuggestionEntries.from(emptyMap()).isEmpty())
        assertTrue(FieldSuggestionEntries.suggestable(emptyList()).isEmpty())
    }
}
