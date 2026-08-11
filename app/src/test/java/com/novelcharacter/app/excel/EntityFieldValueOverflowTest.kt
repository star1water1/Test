package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NovelFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 작품·사건 오버플로 선별 (B-65 · 사용자 확정 15번 ㄴ1).
 *
 * **캐릭터판과 같은 규칙을 쓰는지가 이 시험의 요점이다** — 규칙을 시트마다 따로 적으면 갈리고,
 * 갈림은 *어느 시트에도 안 나가는 값*이나 *두 시트에 겹쳐 나가는 값*으로만 드러난다.
 */
class EntityFieldValueOverflowTest {

    private fun field(id: Long, type: String = "TEXT", entity: String = FieldDefinition.ENTITY_NOVEL) =
        FieldDefinition(
            id = id, universeId = 1L, key = "k$id", name = "필드$id", type = type, entityType = entity
        )

    private fun novelValue(fieldId: Long, value: String = "값") =
        NovelFieldValue(id = fieldId, novelId = 7L, fieldDefinitionId = fieldId, value = value)

    private fun eventValue(fieldId: Long, value: String = "값") =
        EventFieldValue(id = fieldId, eventId = 7L, fieldDefinitionId = fieldId, value = value)

    @Test
    fun coveredValues_stayInTheMainSheet() {
        val fields = mapOf(1L to field(1), 2L to field(2))
        val picked = NovelFieldValueOverflow.select(
            listOf(novelValue(1), novelValue(2)), setOf(1L), fields
        )
        assertEquals(listOf(2L), picked.map { it.second.id })
    }

    @Test
    fun orphanValue_withoutDefinition_isDropped() {
        // 정의가 사라진 값은 복원할 정체성이 없다 — 담아도 되읽을 수 없다
        val picked = NovelFieldValueOverflow.select(
            listOf(novelValue(9)), emptySet(), mapOf(1L to field(1))
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun calculatedAndBlank_areNotCarried() {
        val fields = mapOf(1L to field(1, type = "CALCULATED"), 2L to field(2))
        val picked = NovelFieldValueOverflow.select(
            listOf(novelValue(1), novelValue(2, value = "  ")), emptySet(), fields
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun eventSide_followsTheSameRule() {
        val fields = mapOf(
            1L to field(1, entity = FieldDefinition.ENTITY_EVENT),
            2L to field(2, entity = FieldDefinition.ENTITY_EVENT)
        )
        val picked = EventFieldValueOverflow.select(
            listOf(eventValue(1), eventValue(2)), setOf(2L), fields
        )
        assertEquals(listOf(1L), picked.map { it.second.id })
    }

    @Test
    fun sheetIdentity_isReservedAndNotMistakenForTheMainSheet() {
        // 첫 열이 '제목'/'연도'가 아니어야 작품·연표 시트로 오인되지 않고, 예약명이라야
        // 세계관 이름이 같아도 이름을 빼앗기지 않는다('캐릭터 필드값'과 같은 방어).
        val novel = novelFieldValueSpec()
        val event = eventFieldValueSpec()
        assertEquals("작품코드", novel.firstColumnHeader)
        assertEquals("사건코드", event.firstColumnHeader)
        assertTrue(novel.sheetName in RESERVED_SHEET_NAMES)
        assertTrue(event.sheetName in RESERVED_SHEET_NAMES)
        // 내보내기 셀 인덱스(0..6)와 헤더 순서가 단일 소스로 일치해야 한다
        assertEquals(
            listOf("작품코드", "작품제목", "세계관", "세계관코드", "필드키", "필드명", "값"),
            novel.columns.map { it.header }
        )
        assertEquals(
            listOf("사건코드", "사건설명", "세계관", "세계관코드", "필드키", "필드명", "값"),
            event.columns.map { it.header }
        )
    }

    @Test
    fun everyEntityKindSharesOneRule() {
        // 셋이 같은 판정을 지나는지 — 한쪽만 고쳐지는 것을 막는 유일한 자리다
        val fields = mapOf(1L to field(1))
        val novel = NovelFieldValueOverflow.select(listOf(novelValue(1)), emptySet(), fields)
        val event = EventFieldValueOverflow.select(listOf(eventValue(1)), emptySet(), fields)
        val generic = FieldValueOverflow.select(
            listOf(novelValue(1)), emptySet(), fields, { it.fieldDefinitionId }, { it.value }
        )
        assertEquals(1, novel.size)
        assertEquals(1, event.size)
        assertEquals(1, generic.size)
    }
}
