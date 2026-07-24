package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "필드 데이터" 시트 매핑 — 왕복 무결성(무편집 왕복 불변) + 형식 이탈 관대 수용. */
class FieldValueSheetMapperTest {

    private fun row(
        value: String,
        label: String? = null,
        aliases: String? = null,
        category: String? = null,
        description: String? = null,
        hidden: String? = null,
        code: String? = null,
        entity: String? = null
    ) = FieldValueSheetMapper.ImportedRow(
        universeName = "세계관", fieldKey = "k", entityLabel = entity, value = value,
        displayLabel = label, aliasesCsv = aliases, category = category,
        description = description, hiddenFlag = hidden, code = code
    )

    @Test
    fun roundTrip_unchanged() {
        val entry = FieldValueEntry(
            id = 5, fieldDefinitionId = 1, value = "서울", displayLabel = "서울특별시",
            aliasesJson = FieldValueEntry.aliasesToJson(listOf("서울시", "Seoul")),
            category = "수도권", description = "설명", isHidden = true, code = "abc123"
        )
        // 내보내기 형태 그대로 다시 들여오면 내용이 불변이어야 한다
        val reimported = FieldValueSheetMapper.applyRow(
            existing = entry,
            fieldDefId = 1,
            row = row(
                value = entry.value, label = entry.displayLabel,
                aliases = FieldValueSheetMapper.aliasesToCsv(entry),
                category = entry.category, description = entry.description,
                hidden = "Y", code = entry.code
            )
        )!!
        assertEquals(entry.value, reimported.value)
        assertEquals(entry.displayLabel, reimported.displayLabel)
        assertEquals(entry.aliases(), reimported.aliases())
        assertEquals(entry.category, reimported.category)
        assertEquals(entry.description, reimported.description)
        assertEquals(entry.isHidden, reimported.isHidden)
        assertEquals(entry.code, reimported.code)
        assertEquals(entry.id, reimported.id)
    }

    @Test
    fun newEntry_sourceImport_keepsSheetCode() {
        val created = FieldValueSheetMapper.applyRow(null, 7, row("한양", code = "code99"))!!
        assertEquals(FieldValueEntry.SOURCE_IMPORT, created.source)
        assertEquals("code99", created.code)
        assertEquals(7, created.fieldDefinitionId)
    }

    @Test
    fun fullWidthCommaAliases_accepted() {
        assertEquals(
            listOf("서울시", "Seoul", "한양"),
            FieldValueSheetMapper.csvToAliases("서울시， Seoul、 한양")
        )
    }

    @Test
    fun hiddenFlag_tolerantParsing() {
        assertTrue(FieldValueSheetMapper.parseHidden("Y"))
        assertTrue(FieldValueSheetMapper.parseHidden("예"))
        assertTrue(FieldValueSheetMapper.parseHidden("true"))
        assertFalse(FieldValueSheetMapper.parseHidden("N"))
        assertFalse(FieldValueSheetMapper.parseHidden(null))
        assertFalse(FieldValueSheetMapper.parseHidden(""))
    }

    @Test
    fun entityLabel_tolerant() {
        assertEquals(FieldDefinition.ENTITY_EVENT, FieldValueSheetMapper.entityTypeOf("사건"))
        assertEquals(FieldDefinition.ENTITY_EVENT, FieldValueSheetMapper.entityTypeOf("EVENT"))
        assertEquals(FieldDefinition.ENTITY_CHARACTER, FieldValueSheetMapper.entityTypeOf("캐릭터"))
        assertEquals(FieldDefinition.ENTITY_CHARACTER, FieldValueSheetMapper.entityTypeOf(null))
        assertEquals(FieldDefinition.ENTITY_CHARACTER, FieldValueSheetMapper.entityTypeOf(""))
    }

    @Test
    fun blankValue_returnsNull() {
        assertNull(FieldValueSheetMapper.applyRow(null, 1, row("   ")))
    }

    @Test
    fun aliasEqualToValue_removed() {
        val created = FieldValueSheetMapper.applyRow(null, 1, row("서울", aliases = "서울, 서울시"))!!
        assertEquals(listOf("서울시"), created.aliases())
    }

    @Test
    fun conflictingAliases_detected() {
        val a = FieldValueEntry(id = 1, fieldDefinitionId = 1, value = "서울",
            aliasesJson = FieldValueEntry.aliasesToJson(listOf("서울시")))
        val candidate = FieldValueEntry(id = 2, fieldDefinitionId = 1, value = "한양",
            aliasesJson = FieldValueEntry.aliasesToJson(listOf("서울시", "옛서울")))
        assertEquals(listOf("서울시"), FieldValueSheetMapper.conflictingAliases(candidate, listOf(a)))
    }
}
