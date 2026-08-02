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
        assertEquals(FieldDefinition.ENTITY_NOVEL, FieldValueSheetMapper.entityTypeOf("작품"))
        assertEquals(FieldDefinition.ENTITY_NOVEL, FieldValueSheetMapper.entityTypeOf("NOVEL"))
        assertEquals(FieldDefinition.ENTITY_NOVEL, FieldValueSheetMapper.entityTypeOf(" novel "))
        assertEquals(FieldDefinition.ENTITY_CHARACTER, FieldValueSheetMapper.entityTypeOf("캐릭터"))
        assertEquals(FieldDefinition.ENTITY_CHARACTER, FieldValueSheetMapper.entityTypeOf(null))
        assertEquals(FieldDefinition.ENTITY_CHARACTER, FieldValueSheetMapper.entityTypeOf(""))
    }

    /**
     * '대상' 열의 왕복 — 라벨과 해석이 **서로의 역함수**여야 한다.
     * 한쪽만 종류를 늘리면 그 종류의 정의가 왕복에서 캐릭터로 접히고, 그 순간
     * 값이 붙을 자리를 잃는다(R-29). 드롭다운 목록도 같은 소스를 쓴다.
     */
    @Test
    fun entityLabel_roundTripsEveryKind() {
        for (type in listOf(
            FieldDefinition.ENTITY_CHARACTER, FieldDefinition.ENTITY_EVENT, FieldDefinition.ENTITY_NOVEL
        )) {
            assertEquals(type, FieldValueSheetMapper.entityTypeOf(FieldValueSheetMapper.entityLabel(type)))
        }
        // 드롭다운이 제시하는 값은 전부 해석 가능해야 한다 — 고를 수 있는데 해석되지 않으면
        // 사용자는 고른 대로 저장됐다고 믿는다.
        assertEquals(
            FieldValueSheetMapper.ENTITY_LABELS,
            FieldValueSheetMapper.ENTITY_LABELS.map {
                FieldValueSheetMapper.entityLabel(FieldValueSheetMapper.entityTypeOf(it))
            }
        )
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

    // ── match / effectOf — 가져오기와 복원 미리보기의 단일 소스 (B-87) ──
    //
    // 종전 미리보기는 '동일'을 셀 자리가 없어(unchanged에 상수 0) 매칭된 행을 전부 '변경'이라
    // 말했다. 아무것도 고치지 않은 파일을 그대로 다시 넣어도 "변경 N"이 뜨는 자리다(A7).

    private fun entry(
        id: Long,
        value: String,
        code: String = "FVE$id",
        label: String = "",
        aliases: List<String> = emptyList(),
        category: String = "",
        description: String = "",
        hidden: Boolean = false,
        source: String = FieldValueEntry.SOURCE_AUTO,
        updatedAt: Long = 1_000L
    ) = FieldValueEntry(
        id = id, fieldDefinitionId = 1, value = value, displayLabel = label,
        aliasesJson = FieldValueEntry.aliasesToJson(aliases), category = category,
        description = description, isHidden = hidden, source = source,
        code = code, createdAt = 500L, updatedAt = updatedAt
    )

    /** 내보낸 엔트리를 그대로 되읽었을 때의 시트 행 (엑셀 왕복의 정상 경로) */
    private fun exportedRow(e: FieldValueEntry) = row(
        e.value, label = e.displayLabel, aliases = FieldValueSheetMapper.aliasesToCsv(e),
        category = e.category, description = e.description,
        hidden = if (e.isHidden) "Y" else "", code = e.code, source = e.source
    )

    @Test
    fun `고치지 않은 왕복은 전부 동일로 판정된다`() {
        val a = entry(1, "서울", label = "서울특별시", aliases = listOf("서울시"), category = "수도권", description = "설명")
        val b = entry(2, "부산", hidden = true, source = FieldValueEntry.SOURCE_MANUAL)
        val siblings = listOf(a, b)
        val effects = siblings.map {
            val existing = FieldValueSheetMapper.match(siblings, exportedRow(it))
            FieldValueSheetMapper.effectOf(existing, 1, exportedRow(it), siblings)
        }
        assertEquals(
            "갱신 시각(updatedAt)까지 비교하면 '동일'이 영원히 0이 된다",
            listOf(FieldValueSheetMapper.RowEffect.UNCHANGED, FieldValueSheetMapper.RowEffect.UNCHANGED),
            effects
        )
    }

    @Test
    fun `값 하나만 달라도 변경이다`() {
        val existing = entry(1, "서울", category = "수도권")
        val siblings = listOf(existing)
        val changed = exportedRow(existing).copy(category = "경기권")
        assertEquals(
            FieldValueSheetMapper.RowEffect.UPDATED,
            FieldValueSheetMapper.effectOf(existing, 1, changed, siblings)
        )
    }

    @Test
    fun `코드가 맞으면 값이 바뀌어도 같은 엔트리다 - 이름 변경`() {
        val existing = entry(1, "서울")
        val siblings = listOf(existing)
        val renamed = exportedRow(existing).copy(value = "한양")
        assertEquals(existing, FieldValueSheetMapper.match(siblings, renamed))

        val outcome = FieldValueSheetMapper.mergeRow(existing, 1, renamed, siblings)
        assertEquals("구 값을 별칭으로 보존해야 재수확이 갈라지지 않는다", "서울", outcome.renamedFrom)
        assertTrue(outcome.entry!!.aliases().contains("서울"))
        assertEquals(
            FieldValueSheetMapper.RowEffect.UPDATED,
            FieldValueSheetMapper.effectOf(existing, 1, renamed, siblings)
        )
    }

    @Test
    fun `코드는 같은 필드 안에서만 찾는다`() {
        // 전역 코드 색인으로 찾으면 다른 필드의 엔트리를 집어 와 '변경'으로 세어진다
        val otherField = FieldValueEntry(id = 9, fieldDefinitionId = 2, value = "부산", code = "FVE9")
        assertNull(FieldValueSheetMapper.match(emptyList(), row("서울", code = otherField.code)))
    }

    @Test
    fun `코드가 시트에만 있고 기존에 없으면 값으로 되돌아간다`() {
        val existing = entry(1, "서울", code = "FVE1")
        assertEquals(existing, FieldValueSheetMapper.match(listOf(existing), row("서울", code = "없는코드")))
    }

    @Test
    fun `매칭되는 것이 없으면 신규다`() {
        assertEquals(
            FieldValueSheetMapper.RowEffect.NEW,
            FieldValueSheetMapper.effectOf(null, 1, row("대구"), emptyList())
        )
    }

    @Test
    fun `바뀐 값을 다른 엔트리가 이미 쓰면 가져오기가 건너뛴다`() {
        val target = entry(1, "서울")
        val taken = entry(2, "한양")
        val siblings = listOf(target, taken)
        val renamed = exportedRow(target).copy(value = "한양")
        val outcome = FieldValueSheetMapper.mergeRow(target, 1, renamed, siblings)
        assertTrue(outcome.valueTaken)
        assertEquals(
            "건너뛰는 행은 바뀌는 것이 없으니 '변경'도 '동일'도 아니다",
            FieldValueSheetMapper.RowEffect.SKIPPED,
            FieldValueSheetMapper.effectOf(target, 1, renamed, siblings)
        )
    }

    @Test
    fun `충돌 별칭만 빠져도 나머지가 같으면 동일이다`() {
        val other = entry(2, "부산", aliases = listOf("부산시"))
        val existing = entry(1, "서울", aliases = listOf("서울시"))
        val siblings = listOf(existing, other)
        // 외부에서 '부산시'를 서울의 별칭으로 덧붙였다 — 가져오기는 그 별칭만 제외한다
        val withConflict = exportedRow(existing).copy(aliasesCsv = "서울시, 부산시")
        val outcome = FieldValueSheetMapper.mergeRow(existing, 1, withConflict, siblings)
        assertEquals(listOf("부산시"), outcome.droppedAliases)
        assertEquals(
            "제외하고 나면 기존과 같으므로 저장돼도 바뀌는 것이 없다",
            FieldValueSheetMapper.RowEffect.UNCHANGED,
            FieldValueSheetMapper.effectOf(existing, 1, withConflict, siblings)
        )
    }

    @Test
    fun `축약 시트는 말하지 않은 열을 바꾸지 않는다`() {
        // 세계관·필드키·값만 남긴 시트 — F1-A로 나머지가 유지되므로 '동일'이다
        val existing = entry(1, "서울", label = "서울특별시", aliases = listOf("서울시"), category = "수도권")
        assertEquals(
            FieldValueSheetMapper.RowEffect.UNCHANGED,
            FieldValueSheetMapper.effectOf(existing, 1, row("서울"), listOf(existing))
        )
    }
}
