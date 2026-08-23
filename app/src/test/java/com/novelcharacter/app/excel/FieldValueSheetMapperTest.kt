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
            listOf("세계관", "필드키", "필드명", "대상", "값", "표시라벨", "별칭 (쉼표 구분)",
                   "카테고리", "설명", "숨김", "출처", "사용횟수", "코드"),
            fieldValueLibrarySpec().columns.map { it.header }
        )
    }

    /**
     * 별칭 열 머리의 개명(B-222 ②) — **표준 접미사와 같은 글자인가**를 잠근다.
     *
     * 글자를 여기 다시 적지 않고 [EntityFieldHeaders.MULTI_SUFFIX]로 조립해 견주는 것이 요점이다.
     * 값을 베껴 적으면 접미사가 바뀌는 날 이 시험이 *옛 글자를 지키는* 시험으로 뒤집힌다 —
     * 이 항목이 고치려던 것이 정확히 그 두 벌짜리 어휘다.
     */
    @Test
    fun aliasHeader_표준_접미사를_쓴다() {
        assertEquals("별칭" + EntityFieldHeaders.MULTI_SUFFIX, FieldValueSheetMapper.ALIAS_HEADER)
        assertEquals(FieldValueSheetMapper.ALIAS_HEADER, fieldValueLibrarySpec().columns[6].header)
    }

    /**
     * 옛 파일의 별칭 열이 계속 읽히는가 — 개명의 폴백(R-2 취지).
     *
     * **가져오기의 `cols[ALIAS_HEADER]` 조회가 이 표 하나에 걸려 있다.** 이 등재가 빠지면
     * 옛 파일의 별칭이 통째로 '알 수 없는 열'이 되어 조용히 버려지고, 덤으로 '인식하지 못해
     * 무시했습니다' 경고가 **맞는 말이 되어** 사용자가 알아챌 길도 사라진다.
     */
    @Test
    fun 옛_별칭_머리가_새_머리로_접힌다() {
        assertEquals(
            FieldValueSheetMapper.ALIAS_HEADER,
            ExcelHeaderAliases.canonical(FieldValueSheetMapper.LEGACY_ALIAS_HEADER)
        )
        // 새 머리 자신도 같은 자리로 접힌다(별칭 표는 canonical도 함께 싣는다).
        assertEquals(
            FieldValueSheetMapper.ALIAS_HEADER,
            ExcelHeaderAliases.canonical(FieldValueSheetMapper.ALIAS_HEADER)
        )
        // 맨 '별칭'은 여전히 캐릭터 시트의 '이명'이다 — 두 시트가 같은 말을 다른 뜻으로 쓴다.
        // 이 시험이 그 사실을 **의도로** 잠근다: 나중에 '별칭'을 이 열로 돌리면 캐릭터 시트의
        // 이명 열이 조용히 갈린다(전역 표라 시트별로 가릴 수 없다).
        assertEquals("이명", ExcelHeaderAliases.canonical("별칭"))
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
    fun aliasWithComma_survivesRoundtrip() {
        // 별칭은 사람이 적는 글이라 쉼표가 든다("서울, 한양"). 감싸지 않으면 왕복 한 번에
        // 별칭 하나가 둘로 갈리고, 갈린 쪽은 원래 별칭으로 되돌릴 방법이 없다 (B-27 ② · R-47).
        val aliases = listOf("서울, 한양", "Seoul")
        assertEquals(aliases, FieldValueSheetMapper.csvToAliases(joinCsv(aliases)))
    }

    @Test
    fun aliasCsv_fullWidthAliasSurvivesRoundtrip() {
        // **별칭은 글자 그대로 살아남는다** — 조회 없이 그대로 저장되고 `byAlias[t]`가
        // 정확 일치로 쓰는 값이라, 전각을 반각으로 낮춰 읽으면 그것이 곧 조용한 개명이다.
        // 종전에는 `ＮＰＣ`가 왕복 한 번에 `NPC`로 접혔고, 그러면 canonical과 같아진
        // 그 별칭을 `applyRow`가 버려 **별칭이 통째로 사라졌다**(태그가 B-261에서 간 그 축).
        for (alias in listOf("ＮＰＣ", "ＡＢＣ１２３", "Ｓ급")) {
            assertEquals(listOf(alias), FieldValueSheetMapper.csvToAliases(joinCsv(listOf(alias))))
        }
    }

    @Test
    fun aliasCsv_fullWidthCommaAliasSurvivesRoundtrip() {
        // **콜드 검토가 잡은 자리**: `needsQuoting`은 `toHalfWidth` 뒤에 보므로 전각 쉼표를
        // 품은 별칭도 내보내기가 **감싼다**. 그 토큰에는 ASCII 쉼표가 없어 «쉼표를 품었는가»로
        // 가르면 감싼 칸이 다시 갈린다 — 셀의 `"` 유무로 가르는 것이 그래서다.
        val aliases = listOf("ＮＰＣ，주식회사", "Seoul")
        assertEquals(aliases, FieldValueSheetMapper.csvToAliases(joinCsv(aliases)))
    }

    @Test
    fun aliasCsv_fullWidthSeparatorDoesNotBreakQuotedCell() {
        // 감싸인 칸 안의 `、`는 값의 일부다 — 종전에는 셀 전체를 치환해 감싸기를 무력화했다.
        val aliases = listOf("北斗、南斗, 天", "Seoul")
        assertEquals(aliases, FieldValueSheetMapper.csvToAliases(joinCsv(aliases)))
    }

    @Test
    fun aliasCsv_legacyFileStillParses() {
        // 따옴표가 없는 옛 파일은 종전 그대로 읽힌다 — 형식 변경의 필수 조건이다.
        assertEquals(listOf("서울시", "한양"), FieldValueSheetMapper.csvToAliases("서울시, 한양"))
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
