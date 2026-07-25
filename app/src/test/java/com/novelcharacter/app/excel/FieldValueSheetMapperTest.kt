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
        entity: String? = null,
        source: String? = null
    ) = FieldValueSheetMapper.ImportedRow(
        universeName = "세계관", fieldKey = "k", entityLabel = entity, value = value,
        displayLabel = label, aliasesCsv = aliases, category = category,
        description = description, hiddenFlag = hidden, code = code, sourceFlag = source
    )

    // ── '출처' 열 왕복 — 이 값이 재현되지 않으면 '미사용 자동수집 정리'가 복원 후 영구히 0건이 된다 ──

    @Test
    fun sourceRoundTrip_preservesEachKind() {
        // 빈 DB 복원(existing=null)에서도 시트의 출처가 재현돼야 한다
        assertEquals(FieldValueEntry.SOURCE_MANUAL,
            FieldValueSheetMapper.applyRow(null, 1, row("서울", source = "MANUAL"))!!.source)
        assertEquals(FieldValueEntry.SOURCE_AUTO,
            FieldValueSheetMapper.applyRow(null, 1, row("서울", source = "AUTO"))!!.source)
        assertEquals(FieldValueEntry.SOURCE_AI,
            FieldValueSheetMapper.applyRow(null, 1, row("서울", source = "AI"))!!.source)
    }

    @Test
    fun sourceColumn_absent_keepsExisting() {
        // F1-A: 열 없음(축약 시트·구버전 파일) → 기존 값 유지
        val e = FieldValueEntry(id = 5, fieldDefinitionId = 1, value = "서울", source = FieldValueEntry.SOURCE_MANUAL)
        assertEquals(FieldValueEntry.SOURCE_MANUAL, FieldValueSheetMapper.applyRow(e, 1, row("서울"))!!.source)
    }

    @Test
    fun sourceColumn_blank_resetsToEntityDefault() {
        // F1-A: 열 있음 + 빈칸 = 비움 의도 → 엔티티 기본값(AUTO)
        val e = FieldValueEntry(id = 5, fieldDefinitionId = 1, value = "서울", source = FieldValueEntry.SOURCE_MANUAL)
        assertEquals(FieldValueEntry.SOURCE_AUTO, FieldValueSheetMapper.applyRow(e, 1, row("서울", source = ""))!!.source)
    }

    @Test
    fun sourceColumn_absent_newEntryStaysImport() {
        // 구버전 파일 호환 — 정보가 없으면 기존 계약(IMPORT) 유지
        assertEquals(FieldValueEntry.SOURCE_IMPORT, FieldValueSheetMapper.applyRow(null, 1, row("서울"))!!.source)
    }

    @Test
    fun sourceCell_tolerantParsing() {
        assertEquals(FieldValueSheetMapper.SourceCell.Known(FieldValueEntry.SOURCE_AUTO),
            FieldValueSheetMapper.parseSourceCell("auto"))
        assertEquals(FieldValueSheetMapper.SourceCell.Known(FieldValueEntry.SOURCE_MANUAL),
            FieldValueSheetMapper.parseSourceCell(" 직접 "))
        assertEquals(FieldValueSheetMapper.SourceCell.Absent, FieldValueSheetMapper.parseSourceCell(null))
        assertEquals(FieldValueSheetMapper.SourceCell.Blank, FieldValueSheetMapper.parseSourceCell("   "))
    }

    @Test
    fun sourceCell_unknown_doesNotDestroyExistingValue() {
        // 오타는 조용히 수용하지도, 기존 값을 파괴하지도 않는다 (호출측이 경고한다)
        val cell = FieldValueSheetMapper.parseSourceCell("AUT0")
        assertTrue(cell is FieldValueSheetMapper.SourceCell.Unknown)
        val e = FieldValueEntry(id = 5, fieldDefinitionId = 1, value = "서울", source = FieldValueEntry.SOURCE_AI)
        assertEquals(FieldValueEntry.SOURCE_AI, FieldValueSheetMapper.applyRow(e, 1, row("서울", source = "AUT0"))!!.source)
    }

    @Test
    fun fieldValueLibrarySpec_columnOrderMatchesExporterCellIndices() {
        // ExcelExporter가 셀 인덱스 0..12로 기록하므로 spec 열 순서가 계약이다.
        // '출처'를 끼워 넣으며 사용횟수·코드를 재번호했는데, 이 계약이 깨지면
        // 사용횟수 자리에 코드 문자열이 들어가는 조용한 오염이 난다(차분 컴파일로는 안 잡힌다).
        assertEquals(
            listOf("세계관", "필드키", "필드명", "대상", "값", "표시라벨", "별칭(콤마구분)",
                   "카테고리", "설명", "숨김", "출처", "사용횟수", "코드"),
            fieldValueLibrarySpec().columns.map { it.header }
        )
    }

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
    fun absentColumns_keepExistingAttributes() {
        // F1-A: 세계관·필드키·값만 남긴 축약 시트(선택 열 없음 = null) 왕복에서
        // 별칭·라벨·카테고리·설명·숨김이 지워지지 않아야 한다
        val entry = FieldValueEntry(
            id = 5, fieldDefinitionId = 1, value = "서울", displayLabel = "서울특별시",
            aliasesJson = FieldValueEntry.aliasesToJson(listOf("서울시")),
            category = "수도권", description = "설명", isHidden = true, code = "abc123"
        )
        val reimported = FieldValueSheetMapper.applyRow(entry, 1, row(entry.value))!!
        assertEquals(entry.displayLabel, reimported.displayLabel)
        assertEquals(entry.aliases(), reimported.aliases())
        assertEquals(entry.category, reimported.category)
        assertEquals(entry.description, reimported.description)
        assertEquals(entry.isHidden, reimported.isHidden)
    }

    @Test
    fun blankCells_clearExistingAttributes() {
        // F1-A 규칙 가: 열이 있고 빈칸이면 비움 의도 존중 (숨김 빈칸은 해제로 해석)
        val entry = FieldValueEntry(
            id = 5, fieldDefinitionId = 1, value = "서울", displayLabel = "서울특별시",
            aliasesJson = FieldValueEntry.aliasesToJson(listOf("서울시")),
            category = "수도권", description = "설명", isHidden = true
        )
        val reimported = FieldValueSheetMapper.applyRow(
            entry, 1,
            row(entry.value, label = "", aliases = "", category = "", description = "", hidden = "")
        )!!
        assertEquals("", reimported.displayLabel)
        assertTrue(reimported.aliases().isEmpty())
        assertEquals("", reimported.category)
        assertEquals("", reimported.description)
        assertFalse(reimported.isHidden)
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
