package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.repository.CharacterFieldValueMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 편집 폼 저장의 병합 규칙 (N2).
 *
 * 고정하는 계약: **폼의 권한은 폼이 실제로 렌더한 필드 정의 집합까지다.**
 * 커버된 필드는 폼이 진실(없으면 삭제), 커버 밖은 폼이 판단할 근거가 없으므로 보존.
 */
class CharacterFieldValueMergeTest {

    private fun v(fieldId: Long, value: String, id: Long = 0L, charId: Long = 7L) =
        CharacterFieldValue(id = id, characterId = charId, fieldDefinitionId = fieldId, value = value)

    @Test
    fun `작품을 없음으로 바꾸면 폼이 비어도 기존 값을 전량 보존한다`() {
        // 폼이 필드를 0개 렌더한 상태 = 커버 집합이 공집합. 종전에는 여기서 전량 삭제됐다.
        val existing = listOf(v(1, "높음"), v(2, "은발"), v(3, "왕도"))
        val merged = CharacterFieldValueMerge.merge(
            formValues = emptyList(),
            coveredFieldDefinitionIds = emptySet(),
            existingValues = existing
        )
        assertEquals(3, merged.size)
        assertEquals(setOf(1L, 2L, 3L), merged.map { it.fieldDefinitionId }.toSet())
        assertEquals(listOf("높음", "은발", "왕도"), merged.map { it.value })
    }

    @Test
    fun `커버된 필드를 사용자가 비우면 삭제된다`() {
        // 폼이 필드 1·2를 렌더했고 사용자가 2를 비웠다 → collectFieldValues는 1만 돌려준다.
        val merged = CharacterFieldValueMerge.merge(
            formValues = listOf(v(1, "높음")),
            coveredFieldDefinitionIds = setOf(1L, 2L),
            existingValues = listOf(v(1, "낮음"), v(2, "은발"))
        )
        assertEquals(1, merged.size)
        assertEquals(1L, merged[0].fieldDefinitionId)
        assertEquals("높음", merged[0].value)
    }

    @Test
    fun `커버된 필드는 폼 값이 기존 값을 이긴다`() {
        val merged = CharacterFieldValueMerge.merge(
            formValues = listOf(v(1, "새 값")),
            coveredFieldDefinitionIds = setOf(1L),
            existingValues = listOf(v(1, "옛 값", id = 55L))
        )
        assertEquals(1, merged.size)
        assertEquals("새 값", merged[0].value)
    }

    @Test
    fun `커버 밖 값은 같은 세계관 안의 평범한 저장에서도 살아남는다`() {
        // 사건(entityType=event) 필드 정의를 가리키는 값처럼, 캐릭터 폼이 절대 렌더하지 않는 값.
        // 종전에는 세계관을 옮기지 않는 저장에서도 매번 지워져 왕복 멱등성이 깨졌다.
        val merged = CharacterFieldValueMerge.merge(
            formValues = listOf(v(1, "높음")),
            coveredFieldDefinitionIds = setOf(1L, 2L),
            existingValues = listOf(v(1, "낮음"), v(2, "은발"), v(99, "3화"))
        )
        assertEquals(setOf(1L, 99L), merged.map { it.fieldDefinitionId }.toSet())
        assertEquals("3화", merged.first { it.fieldDefinitionId == 99L }.value)
    }

    @Test
    fun `보존되는 값은 id를 새로 발급받는다 — 호출부가 전량 삭제 후 재삽입한다`() {
        val merged = CharacterFieldValueMerge.merge(
            formValues = emptyList(),
            coveredFieldDefinitionIds = emptySet(),
            existingValues = listOf(v(1, "높음", id = 41L))
        )
        assertEquals(0L, merged[0].id)
        assertEquals(7L, merged[0].characterId)
    }

    @Test
    fun `같은 필드 정의가 중복돼도 유니크 제약을 깨지 않는다`() {
        // (characterId, fieldDefinitionId) 유니크 — insertAll이 ABORT라 중복이 들어가면
        // 저장 전체가 실패한다. 폼 우선으로 하나만 남긴다.
        val merged = CharacterFieldValueMerge.merge(
            formValues = listOf(v(1, "A"), v(1, "B")),
            coveredFieldDefinitionIds = setOf(1L),
            existingValues = listOf(v(1, "C"))
        )
        assertEquals(1, merged.size)
        assertEquals("A", merged[0].value)
    }

    @Test
    fun `폼이 커버 밖 필드값을 제출하면 그 값이 보존값을 대체한다`() {
        // 방어적 계약 — 커버 집합과 제출 값이 어긋나도 유니크 제약이 깨지지 않아야 한다.
        val merged = CharacterFieldValueMerge.merge(
            formValues = listOf(v(9, "폼")),
            coveredFieldDefinitionIds = emptySet(),
            existingValues = listOf(v(9, "DB"))
        )
        assertEquals(1, merged.size)
        assertEquals("폼", merged[0].value)
    }

    @Test
    fun `보존 개수는 실제로 남는 커버 밖 값만 센다`() {
        val n = CharacterFieldValueMerge.preservedCount(
            formValues = listOf(v(1, "높음"), v(9, "폼")),
            coveredFieldDefinitionIds = setOf(1L, 2L),
            existingValues = listOf(v(1, "낮음"), v(2, "은발"), v(9, "DB"), v(99, "3화"))
        )
        // 1·2는 커버됨, 9는 폼이 제출해 대체됨 → 실제 보존은 99 하나뿐이다.
        assertEquals(1, n)
    }

    @Test
    fun `보존이 없으면 개수는 0이고 결과는 폼과 같다`() {
        val form = listOf(v(1, "높음"), v(2, "은발"))
        val covered = setOf(1L, 2L)
        val existing = listOf(v(1, "낮음"))
        assertEquals(0, CharacterFieldValueMerge.preservedCount(form, covered, existing))
        val merged = CharacterFieldValueMerge.merge(form, covered, existing)
        assertEquals(form.map { it.fieldDefinitionId }, merged.map { it.fieldDefinitionId })
        assertTrue(merged.all { it.id == 0L })
    }
}
