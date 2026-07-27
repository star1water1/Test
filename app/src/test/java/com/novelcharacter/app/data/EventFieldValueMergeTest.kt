package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.repository.EventFieldValueMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-6이 고정하는 계약: 사건 편집 폼의 권한은 폼이 실제로 렌더한 필드 정의 집합(커버)까지다.
 * - 커버된 필드는 폼이 진실(폼에 없으면 삭제, 있으면 대체)
 * - 커버 밖 필드는 기존 값 보존, 단 폼이 제출했다면 그 값이 대체(방어 계약 — 캐릭터판과 동일)
 * - 커버 공집합(세계관 미해결·로딩 미완) = 전량 보존
 */
class EventFieldValueMergeTest {

    private fun v(fieldId: Long, value: String, id: Long = 0L, eventId: Long = 7L) =
        EventFieldValue(id = id, eventId = eventId, fieldDefinitionId = fieldId, value = value)

    @Test
    fun `커버 공집합이면 폼이 비어도 기존 값을 전량 보존한다`() {
        val existing = listOf(v(1, "회차 3", id = 10), v(2, "본편", id = 11))
        val result = EventFieldValueMerge.resultingValues(emptyList(), emptySet(), existing)
        assertEquals(existing, result)
        assertEquals(2, EventFieldValueMerge.preservedCount(emptyList(), emptySet(), existing))
    }

    @Test
    fun `커버된 필드가 폼에 없으면 비움 의도로 삭제된다`() {
        val existing = listOf(v(1, "회차 3", id = 10), v(2, "본편", id = 11))
        val result = EventFieldValueMerge.resultingValues(emptyList(), setOf(1L), existing)
        assertEquals(listOf(v(2, "본편", id = 11)), result)
        assertEquals(1, EventFieldValueMerge.preservedCount(emptyList(), setOf(1L), existing))
    }

    @Test
    fun `커버된 필드의 폼 값이 기존 값을 대체한다`() {
        val existing = listOf(v(1, "회차 3", id = 10))
        val form = listOf(v(1, "회차 4"))
        val result = EventFieldValueMerge.resultingValues(form, setOf(1L), existing)
        assertEquals(listOf(v(1, "회차 4")), result)
        assertEquals(0, EventFieldValueMerge.preservedCount(form, setOf(1L), existing))
    }

    @Test
    fun `커버 밖 필드값을 폼이 제출하면 보존이 아니라 대체다`() {
        // 폼이 실제로 알고 낸 값을 버리지 않는다 — CharacterFieldValueMerge와 같은 방어 계약
        val existing = listOf(v(1, "옛값", id = 10))
        val form = listOf(v(1, "새값"))
        val result = EventFieldValueMerge.resultingValues(form, emptySet(), existing)
        assertEquals(listOf(v(1, "새값")), result)
        // 대체된 값은 '보존'으로 세지 않는다 — 세면 거짓 고지가 된다
        assertEquals(0, EventFieldValueMerge.preservedCount(form, emptySet(), existing))
    }

    @Test
    fun `커버 밖 기존 값은 폼 제출과 무관하게 남는다`() {
        val existing = listOf(v(1, "커버밖", id = 10), v(2, "커버안", id = 11))
        val form = listOf(v(2, "수정"))
        val result = EventFieldValueMerge.resultingValues(form, setOf(2L), existing)
        assertEquals(setOf(v(1, "커버밖", id = 10), v(2, "수정")), result.toSet())
        assertEquals(1, EventFieldValueMerge.preservedCount(form, setOf(2L), existing))
    }

    @Test
    fun `결과에 같은 필드 정의가 두 번 실리지 않는다`() {
        // (eventId, fieldDefinitionId) 유니크 — 겹치면 REPLACE 삽입이 조용히 덮으므로
        // 순수 모델 단계에서 이미 유일해야 한다
        val existing = listOf(v(1, "a", id = 10), v(2, "b", id = 11), v(3, "c", id = 12))
        val form = listOf(v(2, "B"), v(3, "C"))
        val result = EventFieldValueMerge.resultingValues(form, setOf(2L), existing)
        val ids = result.map { it.fieldDefinitionId }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf(v(1, "a", id = 10), v(2, "B"), v(3, "C")), result.toSet())
    }

    @Test
    fun `보존 개수는 커버 밖이면서 폼도 제출하지 않은 값만 센다`() {
        val existing = listOf(v(1, "보존", id = 10), v(2, "대체", id = 11), v(3, "삭제", id = 12))
        val form = listOf(v(2, "대체값"))
        val count = EventFieldValueMerge.preservedCount(form, setOf(3L), existing)
        assertEquals(1, count)  // 1번만 보존 — 2번은 대체, 3번은 커버 안(삭제)
    }

    @Test
    fun `기존 값이 없으면 보존 개수는 0이다`() {
        assertEquals(0, EventFieldValueMerge.preservedCount(listOf(v(1, "x")), emptySet(), emptyList()))
        assertTrue(EventFieldValueMerge.resultingValues(listOf(v(1, "x")), emptySet(), emptyList())
            .single().value == "x")
    }
}
