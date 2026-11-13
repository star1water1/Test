package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.repository.ArchivedFieldValues.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivedFieldValuesTest {
    private fun field(id: Long, universeId: Long?, name: String, order: Int = 0) = FieldDefinition(
        id = id, universeId = universeId, key = "k$id", name = name, type = "TEXT",
        entityType = FieldDefinition.ENTITY_EVENT, displayOrder = order)

    private val definitions = listOf(
        field(1, 10, "장소"), field(2, 10, "날씨", order = 1), field(3, 20, "피해"),
        field(4, null, "비고"), field(5, 99, "사라진 세계관의 필드")
    ).associateBy { it.id }
    private val names = mapOf(10L to "가람", 20L to "나래")

    private fun select(stored: List<Pair<Long, String>>, covered: Set<Long>) =
        ArchivedFieldValues.select(stored, covered, definitions, names)

    @Test fun coveredValuesAreNotArchived() {
        assertTrue(select(listOf(1L to "항구", 2L to "비"), setOf(1L, 2L)).isEmpty())
    }

    @Test fun valuesOutsideTheFormAreListedWithTheirZone() {
        // 사건이 '나래'로 옮겨 가 창은 3번만 덮는다 — '가람'의 두 값과 전역 값이 남아 있다.
        val lines = select(listOf(3L to "셋", 2L to "비", 1L to "항구", 4L to "메모"), setOf(3L))
        assertEquals(listOf(Line(null, "비고", "메모"), Line("가람", "장소", "항구"), Line("가람", "날씨", "비")), lines)
    }

    @Test fun blankValuesAndLostDefinitionsAreNotShown() {
        assertTrue(select(listOf(1L to "  ", 7L to "정의 없음", 5L to "세계관 없음"), emptySet()).isEmpty())
    }
}
