package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.repository.NovelFieldValueMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 확-3이 고정하는 계약 — [EventFieldValueMergeTest]의 작품판이며 **규칙이 같다**(R-5).
 *
 * 같은 계약을 세 표(캐릭터·사건·작품)가 공유하는 것이 통합 테이블을 쓰지 않은 근거였다
 * (모양을 똑같이 두면 규약 하나가 세 표에 그대로 적용된다). 그 근거를 주장으로 두지 않고
 * 실행 검증으로 만든다 — 작품판만 규칙이 갈리면 그 순간 근거가 사라진다.
 */
class NovelFieldValueMergeTest {

    private fun v(fieldId: Long, value: String, id: Long = 0L, novelId: Long = 4L) =
        NovelFieldValue(id = id, novelId = novelId, fieldDefinitionId = fieldId, value = value)

    @Test
    fun `커버 공집합이면 폼이 비어도 기존 값을 전량 보존한다`() {
        val existing = listOf(v(1, "장편", id = 10), v(2, "연재 중", id = 11))
        val result = NovelFieldValueMerge.resultingValues(emptyList(), emptySet(), existing)
        assertEquals(existing, result)
        assertEquals(2, NovelFieldValueMerge.preservedCount(emptyList(), emptySet(), existing))
    }

    @Test
    fun `커버된 필드가 폼에 없으면 비움 의도로 삭제된다`() {
        val existing = listOf(v(1, "장편", id = 10), v(2, "연재 중", id = 11))
        val result = NovelFieldValueMerge.resultingValues(emptyList(), setOf(1L), existing)
        assertEquals(listOf(v(2, "연재 중", id = 11)), result)
        assertEquals(1, NovelFieldValueMerge.preservedCount(emptyList(), setOf(1L), existing))
    }

    @Test
    fun `커버된 필드의 폼 값이 기존 값을 대체한다`() {
        val existing = listOf(v(1, "장편", id = 10))
        val form = listOf(v(1, "중편"))
        val result = NovelFieldValueMerge.resultingValues(form, setOf(1L), existing)
        assertEquals(listOf(v(1, "중편")), result)
        assertEquals(0, NovelFieldValueMerge.preservedCount(form, setOf(1L), existing))
    }

    @Test
    fun `커버 밖 필드값을 폼이 제출하면 보존이 아니라 대체다`() {
        val existing = listOf(v(1, "옛값", id = 10))
        val form = listOf(v(1, "새값"))
        val result = NovelFieldValueMerge.resultingValues(form, emptySet(), existing)
        assertEquals(listOf(v(1, "새값")), result)
        assertEquals(0, NovelFieldValueMerge.preservedCount(form, emptySet(), existing))
    }

    @Test
    fun `커버 밖 기존 값은 폼 제출과 무관하게 남는다`() {
        val existing = listOf(v(1, "커버밖", id = 10), v(2, "커버안", id = 11))
        val form = listOf(v(2, "수정"))
        val result = NovelFieldValueMerge.resultingValues(form, setOf(2L), existing)
        assertEquals(setOf(v(1, "커버밖", id = 10), v(2, "수정")), result.toSet())
        assertEquals(1, NovelFieldValueMerge.preservedCount(form, setOf(2L), existing))
    }

    @Test
    fun `결과에 같은 필드 정의가 두 번 실리지 않는다`() {
        // (novelId, fieldDefinitionId) 유니크 — 겹치면 REPLACE 삽입이 조용히 덮으므로
        // 순수 모델 단계에서 이미 유일해야 한다
        val existing = listOf(v(1, "a", id = 10), v(2, "b", id = 11), v(3, "c", id = 12))
        val form = listOf(v(2, "B"), v(3, "C"))
        val result = NovelFieldValueMerge.resultingValues(form, setOf(2L), existing)
        val ids = result.map { it.fieldDefinitionId }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf(v(1, "a", id = 10), v(2, "B"), v(3, "C")), result.toSet())
    }

    @Test
    fun `보존 개수는 커버 밖이면서 폼도 제출하지 않은 값만 센다`() {
        val existing = listOf(v(1, "보존", id = 10), v(2, "대체", id = 11), v(3, "삭제", id = 12))
        val form = listOf(v(2, "대체값"))
        assertEquals(1, NovelFieldValueMerge.preservedCount(form, setOf(3L), existing))
    }

    @Test
    fun `기존 값이 없으면 보존 개수는 0이다`() {
        assertEquals(0, NovelFieldValueMerge.preservedCount(listOf(v(1, "x")), emptySet(), emptyList()))
        assertTrue(
            NovelFieldValueMerge.resultingValues(listOf(v(1, "x")), emptySet(), emptyList())
                .single().value == "x"
        )
    }
}
