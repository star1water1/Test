package com.novelcharacter.app.excel

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.generateEntityCode
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook

data class ImportProgress(
    val currentPhase: String,
    val processedRows: Int,
    val totalRows: Int
)

data class ImportResult(
    var newUniverses: Int = 0,
    var updatedUniverses: Int = 0,
    var newNovels: Int = 0,
    var updatedNovels: Int = 0,
    var newFields: Int = 0,
    var updatedFields: Int = 0,
    var newCharacters: Int = 0,
    var updatedCharacters: Int = 0,
    var newEvents: Int = 0,
    var updatedEvents: Int = 0,
    var newStateChanges: Int = 0,
    var updatedStateChanges: Int = 0,
    var newRelationships: Int = 0,
    var updatedRelationships: Int = 0,
    var newRelationshipChanges: Int = 0,
    var updatedRelationshipChanges: Int = 0,
    var newNameBank: Int = 0,
    var updatedNameBank: Int = 0,
    var skippedRows: Int = 0,
    val errors: MutableList<String> = mutableListOf(),
    var nameBasedMappings: Int = 0,
    var autoRepairedValues: Int = 0,
    var newCodesGenerated: Int = 0,
    val warnings: MutableList<String> = mutableListOf(),
    val pendingConflicts: MutableList<String> = mutableListOf()
)

class ExcelImportService(private val db: AppDatabase) {

    private val novelIdCache = mutableMapOf<Pair<String, Long?>, Long?>()
    private var truncatedFieldCount = 0

    // ── Header alias map for tolerant import (Sprint C) ──

    private val headerAliases: Map<String, String> = buildMap {
        fun alias(canonical: String, vararg aliases: String) {
            put(canonical.normalizeHeader(), canonical)
            for (a in aliases) {
                val key = a.normalizeHeader()
                // Only add if not already claimed by another canonical
                if (key !in this) put(key, canonical)
            }
        }
        // Core identifiers (order matters: first registration wins)
        alias("이름", "name", "캐릭터명")
        alias("설명", "description", "desc")
        alias("코드", "code")
        alias("정렬순서", "sort_order", "display_order", "displayorder")
        alias("제목", "title", "작품제목")
        alias("세계관", "universe")
        alias("세계관코드", "universe_code")
        alias("이명", "another_name", "별칭", "alias")
        alias("성", "last_name", "lastName", "family_name")
        alias("이름(First)", "first_name", "firstName", "given_name")
        alias("이미지경로", "image_path", "이미지 경로", "image_file")
        alias("이미지모드", "image_mode", "이미지 모드")
        alias("이미지캐릭터ID", "image_character_id", "이미지 캐릭터 ID")
        alias("작품", "novel")
        alias("메모", "memo", "비고", "note", "notes")
        alias("태그", "tags", "tag")
        alias("작품코드", "novel_code")
        // Field definition (unique aliases only)
        alias("필드키", "field_key")
        alias("필드명", "field_name")
        alias("타입", "type", "field_type")
        alias("설정(JSON)", "config", "configuration")
        alias("그룹", "group", "그룹명", "group_name")
        alias("순서", "order")
        alias("필수여부", "required", "is_required")
        // Timeline (unique aliases only)
        alias("연도", "year")
        alias("월", "month")
        alias("일", "day")
        alias("역법", "calendar", "calendar_type")
        alias("사건 설명", "event_description", "사건설명")
        alias("관련 작품", "related_novel", "관련작품")
        alias("관련 캐릭터", "related_characters", "관련캐릭터")
        alias("관련작품코드", "related_novel_code")
        // State change
        alias("캐릭터", "character", "character_name")
        alias("새 값", "new_value", "새값")
        alias("캐릭터코드", "character_code")
        // Relationship
        alias("캐릭터1", "character1")
        alias("캐릭터2", "character2")
        alias("관계 유형", "relationship_type", "관계유형")
        alias("캐릭터1코드", "character1_code")
        alias("캐릭터2코드", "character2_code")
        // Name bank
        alias("성별", "gender", "sex")
        alias("출처", "origin", "문화권")
        alias("사용여부", "is_used")
        alias("사용 캐릭터", "used_by", "사용캐릭터")
        alias("사용캐릭터코드", "used_character_code")
        // Border color (Sprint D)
        alias("테두리색", "border_color", "bordercolor")
        alias("테두리두께", "border_width", "borderwidth")
    }

    suspend fun importAll(
        workbook: Workbook,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult {
        val result = ImportResult()
        novelIdCache.clear()
        processedRowsSoFar = 0
        truncatedFieldCount = 0

        val totalRows = countTotalRows(workbook)

        // Phase 1: Schema definitions (universes, novels, field definitions)
        db.withTransaction {
            importUniverses(workbook, result, onProgress, totalRows)
            importNovels(workbook, result, onProgress, totalRows)
            importFieldDefinitions(workbook, result, onProgress, totalRows)
        }
        // Phase 2: Entity data (characters)
        db.withTransaction {
            importCharacterSheets(workbook, result, onProgress, totalRows)
            importUnclassifiedCharacters(workbook, result, onProgress, totalRows)
        }
        // Phase 3: Relationships and references
        db.withTransaction {
            importTimeline(workbook, result, onProgress, totalRows)
            importStateChanges(workbook, result, onProgress, totalRows)
            importRelationships(workbook, result, onProgress, totalRows)
            importRelationshipChanges(workbook, result, onProgress, totalRows)
            importNameBank(workbook, result, onProgress, totalRows)
        }

        if (truncatedFieldCount > 0) {
            result.warnings.add("${truncatedFieldCount}개 필드값이 ${MAX_FIELD_LENGTH}자 제한으로 잘렸습니다.")
        }

        return result
    }

    private fun countTotalRows(workbook: Workbook): Int {
        var total = 0
        for (i in 0 until workbook.numberOfSheets) {
            val sheetName = workbook.getSheetName(i)
            if (sheetName == GUIDE_SHEET_NAME) continue
            total += maxOf(0, workbook.getSheetAt(i).lastRowNum)
        }
        return maxOf(total, 1)
    }

    private var processedRowsSoFar = 0

    private fun reportProgress(onProgress: (ImportProgress) -> Unit, phase: String, rowsInPhase: Int, totalRows: Int) {
        processedRowsSoFar += rowsInPhase
        onProgress(ImportProgress(phase, processedRowsSoFar, totalRows))
    }

    // ── Tolerant header matching (Sprint C) ──

    /**
     * Build a column index map by resolving header names through aliases.
     * Returns Map<canonical_header_name, column_index>.
     */
    private fun resolveHeaderColumns(headerRow: Row): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val lastCol = headerRow.lastCellNum.toInt()
        for (col in 0 until lastCol) {
            val cell = headerRow.getCell(col) ?: continue
            val rawHeader = try { cell.stringCellValue?.trim() ?: "" } catch (_: Exception) { "" }
            if (rawHeader.isBlank()) continue
            val normalized = rawHeader.normalizeHeader()
            val canonical = headerAliases[normalized] ?: rawHeader
            if (canonical !in result) {
                result[canonical] = col
            }
        }
        return result
    }

    /**
     * Check if header row is valid for a sheet by looking for expected first column header
     * using alias-aware matching.
     */
    private fun isValidHeader(headerRow: Row, expectedFirstHeader: String): Boolean {
        val firstCell = getCellString(headerRow, 0)
        if (firstCell.isBlank()) return false
        val normalized = firstCell.normalizeHeader()
        val canonical = headerAliases[normalized] ?: firstCell
        return canonical == expectedFirstHeader || firstCell == expectedFirstHeader
    }

    // ── 세계관 가져오기 ──

    private suspend fun importUniverses(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = universeSpec()
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val codeColIndex = cols["코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val borderColorColIndex = cols["테두리색"] ?: -1
        val borderWidthColIndex = cols["테두리두께"] ?: -1
        val imagePathColIndex = cols["이미지경로"] ?: -1
        val imageModeColIndex = cols["이미지모드"] ?: -1

        // Build code index for duplicate detection within file
        val codesSeen = mutableMapOf<String, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val description = getCellString(row, descColIndex)
                val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
                val displayOrder = if (orderColIndex >= 0) parseNumber(getCellString(row, orderColIndex))?.toLong() ?: 0L else 0L
                val borderColor = if (borderColorColIndex >= 0) getCellString(row, borderColorColIndex) else ""
                val borderWidthDp = if (borderWidthColIndex >= 0) parseNumber(getCellString(row, borderWidthColIndex))?.toFloat() ?: 1.5f else 1.5f
                val imagePath = if (imagePathColIndex >= 0) getCellString(row, imagePathColIndex) else ""
                val imageMode = if (imageModeColIndex >= 0) getCellString(row, imageModeColIndex).ifBlank { "none" } else "none"

                // Duplicate code detection within file (last-write-wins)
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("세계관: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Code-first matching (Sprint A strict rule)
                val existing: Universe?
                val matchedByName: Boolean
                if (code.isNotBlank()) {
                    val byCode = db.universeDao().getUniverseByCode(code)
                    if (byCode != null) {
                        existing = byCode
                        matchedByName = false
                    } else {
                        // Code present but not found in DB => new entity, don't fallback to name
                        existing = null
                        matchedByName = false
                    }
                } else {
                    // No code => try name for backward compatibility + warn
                    existing = db.universeDao().getUniverseByName(name)
                    matchedByName = existing != null
                    if (matchedByName) {
                        result.nameBasedMappings++
                        result.warnings.add("세계관 행 $i: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    db.universeDao().update(existing.copy(
                        name = name, description = description, displayOrder = displayOrder,
                        borderColor = borderColor, borderWidthDp = borderWidthDp,
                        imagePath = imagePath, imageMode = imageMode
                    ))
                    result.updatedUniverses++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    db.universeDao().insert(Universe(
                        name = name, description = description, code = newCode,
                        displayOrder = displayOrder, borderColor = borderColor, borderWidthDp = borderWidthDp,
                        imagePath = imagePath, imageMode = imageMode
                    ))
                    result.newUniverses++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세계관 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "세계관", sheet.lastRowNum, totalRows)
    }

    // ── 작품 가져오기 ──

    private suspend fun importNovels(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = novelSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val titleColIndex = cols[spec.firstColumnHeader] ?: cols["제목"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val universeNameColIndex = cols["세계관"] ?: 2
        val codeColIndex = cols["코드"] ?: -1
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val borderColorColIndex = cols["테두리색"] ?: -1
        val borderWidthColIndex = cols["테두리두께"] ?: -1
        val novelImagePathColIndex = cols["이미지경로"] ?: -1
        val novelImageModeColIndex = cols["이미지모드"] ?: -1
        val imageCharIdColIndex = cols["이미지캐릭터ID"] ?: -1
        val inheritBorderColIndex = cols["테두리상속"] ?: -1
        val novelPinnedColIndex = cols["고정"] ?: -1

        val codesSeen = mutableMapOf<String, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val title = getCellString(row, titleColIndex)
                if (title.isBlank()) continue

                val description = getCellString(row, descColIndex)
                val universeName = getCellString(row, universeNameColIndex)
                val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
                val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
                val displayOrder = if (orderColIndex >= 0) parseNumber(getCellString(row, orderColIndex))?.toLong() ?: 0L else 0L
                val borderColor = if (borderColorColIndex >= 0) getCellString(row, borderColorColIndex) else ""
                val borderWidthDp = if (borderWidthColIndex >= 0) parseNumber(getCellString(row, borderWidthColIndex))?.toFloat() ?: 1.5f else 1.5f
                val novelImagePath = if (novelImagePathColIndex >= 0) getCellString(row, novelImagePathColIndex) else ""
                val novelImageMode = if (novelImageModeColIndex >= 0) getCellString(row, novelImageModeColIndex).ifBlank { "none" } else "none"
                val novelImageCharId = if (imageCharIdColIndex >= 0) parseNumber(getCellString(row, imageCharIdColIndex))?.toLong() else null
                val novelIsPinned = if (novelPinnedColIndex >= 0) parseBoolean(getCellString(row, novelPinnedColIndex)) else false

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("작품: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Resolve universe: code-first, then name
                val universeId = if (universeCode.isNotBlank()) {
                    db.universeDao().getUniverseByCode(universeCode)?.id
                } else null
                    ?: if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null

                // Code-first matching (Sprint A)
                val existing: Novel?
                if (code.isNotBlank()) {
                    existing = db.novelDao().getNovelByCode(code)
                    // Code present but not found => new entity
                } else {
                    // No code => fallback to title+universe
                    existing = if (universeId != null) {
                        db.novelDao().getNovelByTitleAndUniverse(title, universeId)
                    } else {
                        db.novelDao().getNovelByTitleNoUniverse(title)
                    }
                    if (existing != null) {
                        result.nameBasedMappings++
                        result.warnings.add("작품 행 $i: 이름 기반 매칭 ('$title') — 코드 사용 권장")
                    }
                }

                val effectiveInherit = if (inheritBorderColIndex >= 0) parseBoolean(getCellString(row, inheritBorderColIndex)) else borderColor.isBlank()

                if (existing != null) {
                    db.novelDao().update(existing.copy(
                        title = title, description = description, universeId = universeId,
                        displayOrder = displayOrder, borderColor = borderColor, borderWidthDp = borderWidthDp,
                        inheritUniverseBorder = effectiveInherit, isPinned = novelIsPinned,
                        imagePath = novelImagePath, imageMode = novelImageMode,
                        imageCharacterId = novelImageCharId
                    ))
                    result.updatedNovels++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    db.novelDao().insert(Novel(
                        title = title, description = description, universeId = universeId,
                        code = newCode, displayOrder = displayOrder,
                        borderColor = borderColor, borderWidthDp = borderWidthDp,
                        inheritUniverseBorder = effectiveInherit, isPinned = novelIsPinned,
                        imagePath = novelImagePath, imageMode = novelImageMode,
                        imageCharacterId = novelImageCharId
                    ))
                    result.newNovels++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("작품 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "작품", sheet.lastRowNum, totalRows)
    }

    // ── 필드 정의 가져오기 ──

    private suspend fun importFieldDefinitions(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = fieldDefinitionSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val universeNameColIndex = cols[spec.firstColumnHeader] ?: cols["세계관"] ?: 0
        val keyColIndex = cols["필드키"] ?: 1
        val nameColIndex = cols["필드명"] ?: 2
        val typeColIndex = cols["타입"] ?: 3
        val configColIndex = cols["설정(JSON)"] ?: 4
        val groupColIndex = cols["그룹"] ?: 5
        val orderColIndex = cols["순서"] ?: 6
        val requiredColIndex = cols["필수여부"] ?: 7
        val universeCodeColIndex = cols["세계관코드"] ?: -1

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val universeName = getCellString(row, universeNameColIndex)
                if (universeName.isBlank()) continue

                val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
                val universe = (if (universeCode.isNotBlank()) {
                    db.universeDao().getUniverseByCode(universeCode)
                } else null)
                    ?: db.universeDao().getUniverseByName(universeName)
                    ?: continue

                val key = getCellString(row, keyColIndex)
                if (key.isBlank()) continue

                val name = getCellString(row, nameColIndex)
                val type = getCellString(row, typeColIndex)
                // Validate field type against known types
                if (type.isBlank()) {
                    result.skippedRows++
                    result.errors.add("필드 정의 행 $i: 필드 타입이 비어 있음 (허용: ${FieldType.entries.joinToString { it.name }})")
                    continue
                }
                if (FieldType.fromName(type) == null) {
                    result.skippedRows++
                    result.errors.add("필드 정의 행 $i: 알 수 없는 필드 타입 '$type' (허용: ${FieldType.entries.joinToString { it.name }})")
                    continue
                }
                val config = getCellString(row, configColIndex).ifBlank { "{}" }
                val groupName = getCellString(row, groupColIndex).ifBlank { "기본 정보" }
                val displayOrder = parseNumber(getCellString(row, orderColIndex))?.toInt() ?: 0
                val isRequired = parseBoolean(getCellString(row, requiredColIndex))

                val existing = db.fieldDefinitionDao().getFieldByKey(universe.id, key)
                if (existing != null) {
                    if (existing.type != type && type.isNotBlank()) {
                        result.warnings.add("필드 정의 행 $i: 필드 '$name'의 타입이 '${existing.type}'에서 '$type'(으)로 변경됨 — 기존 값 호환성을 확인하세요")
                    }
                    db.fieldDefinitionDao().update(existing.copy(
                        name = name, type = type, config = config,
                        groupName = groupName, displayOrder = displayOrder, isRequired = isRequired
                    ))
                    result.updatedFields++
                } else {
                    db.fieldDefinitionDao().insert(FieldDefinition(
                        universeId = universe.id, key = key, name = name, type = type,
                        config = config, groupName = groupName, displayOrder = displayOrder,
                        isRequired = isRequired
                    ))
                    result.newFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 정의 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "필드 정의", sheet.lastRowNum, totalRows)
    }

    // ── 세계관별 캐릭터 시트 가져오기 ──

    private suspend fun importCharacterSheets(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val universes = db.universeDao().getAllUniversesList()
        val reservedNames = RESERVED_SHEET_NAMES

        for (universe in universes) {
            val sheet = findSheetForUniverse(workbook, universe.name, reservedNames) ?: continue
            val headerRow = sheet.getRow(0) ?: continue
            if (!isValidHeader(headerRow, "이름")) continue

            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            importCharacterRows(sheet, headerRow, universe, fields, result, onProgress, totalRows)
        }
    }

    // ── 미분류 캐릭터 가져오기 ──

    private suspend fun importUnclassifiedCharacters(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val sheet = workbook.getSheet(UNCLASSIFIED_SHEET_NAME) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, "이름")) return

        importCharacterRows(sheet, headerRow, null, emptyList(), result, onProgress, totalRows)
    }

    /**
     * Shared character import logic for both universe and unclassified sheets.
     * Sprint A: Strict code-first matching with conflict detection
     * Sprint B: Scope-based displayOrder
     * Sprint C: Tolerant header resolution
     */
    private suspend fun importCharacterRows(
        sheet: Sheet,
        headerRow: Row,
        universe: Universe?,
        fields: List<FieldDefinition>,
        result: ImportResult,
        onProgress: (ImportProgress) -> Unit,
        totalRows: Int
    ) {
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val anotherNameColIndex = cols["이명"] ?: -1
        val lastNameColIndex = cols["성"] ?: -1
        val firstNameColIndex = cols["이름(First)"] ?: -1
        val imageColIndex = cols["이미지경로"] ?: -1
        val novelColIndex = cols["작품"] ?: -1
        val memoColIndex = cols["메모"] ?: -1
        val tagsColIndex = cols["태그"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val novelCodeColIndex = cols["작품코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val pinnedColIndex = cols["고정"] ?: -1
        val fixedColIndices = setOf(nameColIndex, anotherNameColIndex, lastNameColIndex, firstNameColIndex, imageColIndex, novelColIndex, memoColIndex, tagsColIndex, codeColIndex, novelCodeColIndex, orderColIndex, pinnedColIndex).filter { it >= 0 }.toSet()
        val columnFieldMap = buildColumnFieldMap(headerRow, fields, fixedColIndices)

        val codesSeen = mutableMapOf<String, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
                val novelCode = if (novelCodeColIndex >= 0) getCellString(row, novelCodeColIndex) else ""
                val novelTitle = if (novelColIndex >= 0) getCellString(row, novelColIndex) else ""

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("캐릭터: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Resolve novel: code-first, then title (Sprint A)
                val novelId = if (novelCode.isNotBlank()) {
                    db.novelDao().getNovelByCode(novelCode)?.id
                } else null
                    ?: if (universe != null) {
                        resolveNovelId(novelTitle, universe.id)
                    } else {
                        resolveNovelId(novelTitle)
                    }

                val anotherName = if (anotherNameColIndex >= 0) getCellString(row, anotherNameColIndex) else ""
                val lastName = if (lastNameColIndex >= 0) getCellString(row, lastNameColIndex) else ""
                val firstName = if (firstNameColIndex >= 0) getCellString(row, firstNameColIndex) else ""
                // imageColIndex < 0 means column is missing: use null sentinel to preserve existing images
                val imagePathsFromExcel: String? = if (imageColIndex >= 0) getCellString(row, imageColIndex).ifBlank { "[]" } else null
                val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""
                val displayOrder = if (orderColIndex >= 0) parseNumber(getCellString(row, orderColIndex))?.toLong() ?: 0L else 0L
                val charIsPinned = if (pinnedColIndex >= 0) parseBoolean(getCellString(row, pinnedColIndex)) else false

                // Code-first matching (Sprint A strict rule)
                val existingChar: Character?
                if (code.isNotBlank()) {
                    existingChar = db.characterDao().getCharacterByCode(code)
                    // Code present but not found => new entity
                } else {
                    // No code => fallback with warning
                    existingChar = if (novelId != null) {
                        db.characterDao().getCharacterByNameAndNovel(name, novelId)
                    } else {
                        db.characterDao().getCharacterByName(name)
                    }
                    if (existingChar != null) {
                        result.nameBasedMappings++
                        result.warnings.add("캐릭터 행 $i: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                val charId: Long
                if (existingChar != null) {
                    charId = existingChar.id
                    db.characterDao().update(existingChar.copy(
                        name = name,
                        firstName = firstName,
                        lastName = lastName,
                        anotherName = anotherName,
                        novelId = novelId,
                        imagePaths = imagePathsFromExcel ?: existingChar.imagePaths,
                        memo = memo,
                        updatedAt = System.currentTimeMillis(),
                        displayOrder = displayOrder,
                        isPinned = charIsPinned
                    ))
                    result.updatedCharacters++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    charId = db.characterDao().insert(Character(
                        name = name, firstName = firstName, lastName = lastName,
                        anotherName = anotherName, novelId = novelId,
                        imagePaths = imagePathsFromExcel ?: "[]", memo = memo, code = newCode, displayOrder = displayOrder,
                        isPinned = charIsPinned
                    ))
                    result.newCharacters++
                }

                // 태그 가져오기 (빈 셀 = 기존 태그 유지, 명시적 값이 있을 때만 교체)
                if (tagsColIndex >= 0) {
                    val tagsStr = getCellString(row, tagsColIndex)
                    if (tagsStr.isNotBlank()) {
                        db.characterTagDao().deleteAllByCharacter(charId)
                        val tags = splitCsv(tagsStr)
                        tags.forEach { tag ->
                            db.characterTagDao().insert(CharacterTag(characterId = charId, tag = tag))
                        }
                    }
                }

                // 동적 필드 값 가져오기 (빈 셀 = 기존 값 삭제)
                for ((colIndex, field) in columnFieldMap) {
                    val value = getCellString(row, colIndex)
                    val existingValue = db.characterFieldValueDao().getValue(charId, field.id)
                    if (value.isNotBlank()) {
                        if (existingValue != null) {
                            db.characterFieldValueDao().update(existingValue.copy(value = value))
                        } else {
                            db.characterFieldValueDao().insert(CharacterFieldValue(
                                characterId = charId, fieldDefinitionId = field.id, value = value
                            ))
                        }
                    } else if (existingValue != null) {
                        db.characterFieldValueDao().deleteValue(charId, field.id)
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                val sheetLabel = universe?.name ?: "미분류 캐릭터"
                result.errors.add("$sheetLabel 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, universe?.name ?: "미분류 캐릭터", sheet.lastRowNum, totalRows)
    }

    // ── 연표 가져오기 ──

    private suspend fun importTimeline(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = timelineSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val yearColIndex = cols["연도"] ?: 0
        val monthColIndex = cols["월"] ?: 1
        val dayColIndex = cols["일"] ?: 2
        val calendarColIndex = cols["역법"] ?: 3
        val descColIndex = cols["사건 설명"] ?: 4
        val novelColIndex = cols["관련 작품"] ?: 5
        val charColIndex = cols["관련 캐릭터"] ?: 6
        val novelCodeColIndex = cols["관련작품코드"] ?: -1

        val allNovels = db.novelDao().getAllNovelsList()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt() ?: continue
                val description = getCellString(row, descColIndex)
                if (description.isBlank()) continue

                val month = parseNumber(getCellString(row, monthColIndex))?.toInt()?.takeIf { it in 1..12 }
                val day = parseNumber(getCellString(row, dayColIndex))?.toInt()?.takeIf { it in 1..31 }
                val calendarType = getCellString(row, calendarColIndex).ifBlank { "천개력" }
                val novelTitle = getCellString(row, novelColIndex)
                val novelCode = if (novelCodeColIndex >= 0) getCellString(row, novelCodeColIndex) else ""

                val resolvedNovel = if (novelCode.isNotBlank()) {
                    db.novelDao().getNovelByCode(novelCode)
                } else null
                    ?: if (novelTitle.isNotBlank()) allNovels.find { it.title == novelTitle } else null
                val novelId = resolvedNovel?.id
                val universeId = resolvedNovel?.universeId

                val existingEvent = if (novelId != null) {
                    db.timelineDao().getEventByNaturalKey(year, description, novelId)
                } else {
                    db.timelineDao().getEventByNaturalKeyNoNovel(year, description)
                }

                val eventId: Long
                if (existingEvent != null) {
                    eventId = existingEvent.id
                    db.timelineDao().update(existingEvent.copy(
                        month = month, day = day, calendarType = calendarType,
                        novelId = novelId, universeId = universeId
                    ))
                    result.updatedEvents++
                } else {
                    eventId = db.timelineDao().insert(TimelineEvent(
                        year = year, month = month, day = day,
                        calendarType = calendarType, description = description,
                        novelId = novelId, universeId = universeId
                    ))
                    result.newEvents++
                }

                val characterNames = getCellString(row, charColIndex)
                if (characterNames.isNotBlank()) {
                    val names = splitCsv(characterNames)
                    val resolvedCharacters = names.mapNotNull { charName ->
                        findCharacterByName(charName, novelId)
                    }
                    if (resolvedCharacters.isNotEmpty()) {
                        db.timelineDao().deleteCrossRefsByEvent(eventId)
                        for (character in resolvedCharacters) {
                            db.timelineDao().insertCrossRef(
                                TimelineCharacterCrossRef(eventId = eventId, characterId = character.id)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("연표 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "사건 연표", sheet.lastRowNum, totalRows)
    }

    // ── 상태변화 가져오기 ──

    private suspend fun importStateChanges(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = stateChangeSpec()
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val charNameColIndex = cols["캐릭터"] ?: 0
        val novelColIndex = cols["작품"] ?: 1
        val yearColIndex = cols["연도"] ?: 2
        val monthColIndex = cols["월"] ?: 3
        val dayColIndex = cols["일"] ?: 4
        val fieldKeyColIndex = cols["필드키"] ?: 5
        val newValueColIndex = cols["새 값"] ?: 6
        val descColIndex = cols["설명"] ?: 7
        val charCodeColIndex = cols["캐릭터코드"] ?: -1

        val allNovels = db.novelDao().getAllNovelsList()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val charName = getCellString(row, charNameColIndex)
                if (charName.isBlank()) continue

                val novelTitle = getCellString(row, novelColIndex)
                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt() ?: continue

                val month = parseNumber(getCellString(row, monthColIndex))?.toInt()?.takeIf { it in 1..12 }
                val day = parseNumber(getCellString(row, dayColIndex))?.toInt()?.takeIf { it in 1..31 }
                val fieldKey = getCellString(row, fieldKeyColIndex)
                if (fieldKey.isBlank()) continue
                val newValue = getCellString(row, newValueColIndex)
                if (newValue.isBlank()) {
                    result.skippedRows++
                    result.warnings.add("상태변화 행 $i: 빈 값은 허용되지 않습니다")
                    continue
                }
                val description = getCellString(row, descColIndex)
                val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""

                // Resolve character: code-first, then name (Sprint A)
                val character = (if (charCode.isNotBlank()) {
                    db.characterDao().getCharacterByCode(charCode)
                } else null)
                    ?: run {
                        val novelId = if (novelTitle.isNotBlank()) allNovels.find { it.title == novelTitle }?.id else null
                        findCharacterByName(charName, novelId)
                    }
                    ?: continue

                val existing = db.characterStateChangeDao()
                    .getChangeByNaturalKey(character.id, year, fieldKey, newValue)

                if (existing != null) {
                    db.characterStateChangeDao().update(existing.copy(
                        month = month, day = day, description = description
                    ))
                    result.updatedStateChanges++
                } else {
                    db.characterStateChangeDao().insert(CharacterStateChange(
                        characterId = character.id, year = year, month = month, day = day,
                        fieldKey = fieldKey, newValue = newValue, description = description
                    ))
                    result.newStateChanges++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("상태변화 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "상태변화", sheet.lastRowNum, totalRows)
    }

    // ── 캐릭터 관계 가져오기 ──

    private suspend fun importRelationships(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = relationshipSpec()
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        val char2NameColIndex = cols["캐릭터2"] ?: 1
        val typeColIndex = cols["관계 유형"] ?: 2
        val descColIndex = cols["설명"] ?: 3
        val intensityColIndex = cols["강도"] ?: -1
        val bidirectionalColIndex = cols["양방향"] ?: -1
        val displayOrderColIndex = cols["표시순서"] ?: -1
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val char1Name = getCellString(row, char1NameColIndex)
                val char2Name = getCellString(row, char2NameColIndex)
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val relationshipType = getCellString(row, typeColIndex)
                if (relationshipType.isBlank()) continue
                val description = getCellString(row, descColIndex)
                val intensity = if (intensityColIndex >= 0) parseNumber(getCellString(row, intensityColIndex))?.toInt()?.coerceIn(1, 10) ?: 5 else 5
                val isBidirectional = if (bidirectionalColIndex >= 0) getCellString(row, bidirectionalColIndex).uppercase() != "N" else true
                val displayOrder = if (displayOrderColIndex >= 0) parseNumber(getCellString(row, displayOrderColIndex))?.toInt() ?: 0 else 0
                val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
                val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""

                val char1 = (if (char1Code.isNotBlank()) {
                    db.characterDao().getCharacterByCode(char1Code)
                } else null)
                    ?: findCharacterByName(char1Name, null) ?: continue

                val char2 = (if (char2Code.isNotBlank()) {
                    db.characterDao().getCharacterByCode(char2Code)
                } else null)
                    ?: findCharacterByName(char2Name, null) ?: continue

                if (char1.id == char2.id) {
                    result.skippedRows++
                    result.errors.add("관계 행 $i: 자기 자신과의 관계는 허용되지 않습니다")
                    continue
                }

                val existingRels = db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id)
                val existing = existingRels.find { rel ->
                    ((rel.characterId1 == char1.id && rel.characterId2 == char2.id) ||
                     (rel.characterId1 == char2.id && rel.characterId2 == char1.id)) &&
                    rel.relationshipType == relationshipType
                }

                if (existing != null) {
                    db.characterRelationshipDao().update(existing.copy(
                        description = description, intensity = intensity,
                        isBidirectional = isBidirectional, displayOrder = displayOrder
                    ))
                    result.updatedRelationships++
                } else {
                    db.characterRelationshipDao().insert(CharacterRelationship(
                        characterId1 = char1.id, characterId2 = char2.id,
                        relationshipType = relationshipType, description = description,
                        intensity = intensity, isBidirectional = isBidirectional,
                        displayOrder = displayOrder
                    ))
                    result.newRelationships++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("관계 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "관계", sheet.lastRowNum, totalRows)
    }

    // ── 관계 변화 가져오기 ──

    private suspend fun importRelationshipChanges(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val sheet = workbook.getSheet("관계 변화") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, "캐릭터1")) return

        val cols = resolveHeaderColumns(headerRow)
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        val char2NameColIndex = cols["캐릭터2"] ?: 1
        val yearColIndex = cols["연도"] ?: 2
        val monthColIndex = cols["월"] ?: 3
        val dayColIndex = cols["일"] ?: 4
        val relTypeColIndex = cols["관계 유형"] ?: 5
        val descColIndex = cols["설명"] ?: 6
        val intensityColIndex = cols["강도"] ?: 7
        val bidirectionalColIndex = cols["양방향"] ?: 8
        val eventIdColIndex = cols["연결사건ID"] ?: -1
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val char1Name = getCellString(row, char1NameColIndex)
                val char2Name = getCellString(row, char2NameColIndex)
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt() ?: continue
                val month = parseNumber(getCellString(row, monthColIndex))?.toInt()?.takeIf { it in 1..12 }
                val day = parseNumber(getCellString(row, dayColIndex))?.toInt()?.takeIf { it in 1..31 }
                val relationshipType = getCellString(row, relTypeColIndex)
                val description = getCellString(row, descColIndex)
                val intensity = parseNumber(getCellString(row, intensityColIndex))?.toInt()?.coerceIn(1, 10) ?: 5
                val isBidirectional = parseBoolean(getCellString(row, bidirectionalColIndex))
                val eventId = if (eventIdColIndex >= 0) parseNumber(getCellString(row, eventIdColIndex))?.toLong() else null

                val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
                val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""

                val char1 = (if (char1Code.isNotBlank()) db.characterDao().getCharacterByCode(char1Code) else null)
                    ?: findCharacterByName(char1Name, null) ?: continue
                val char2 = (if (char2Code.isNotBlank()) db.characterDao().getCharacterByCode(char2Code) else null)
                    ?: findCharacterByName(char2Name, null) ?: continue

                // Find the relationship between these characters
                val relationships = db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id)
                val relationship = relationships.find { rel ->
                    (rel.characterId1 == char1.id && rel.characterId2 == char2.id) ||
                    (rel.characterId1 == char2.id && rel.characterId2 == char1.id)
                } ?: continue

                val existing = db.characterRelationshipChangeDao().getChangeByNaturalKey(
                    relationship.id, year, month, day
                )
                if (existing != null) {
                    db.characterRelationshipChangeDao().update(existing.copy(
                        relationshipType = relationshipType, description = description,
                        intensity = intensity, isBidirectional = isBidirectional,
                        eventId = eventId
                    ))
                    result.updatedRelationshipChanges++
                } else {
                    db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(
                        relationshipId = relationship.id,
                        year = year, month = month, day = day,
                        relationshipType = relationshipType, description = description,
                        intensity = intensity, isBidirectional = isBidirectional,
                        eventId = eventId
                    ))
                    result.newRelationshipChanges++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("관계 변화 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "관계 변화", sheet.lastRowNum, totalRows)
    }

    // ── 이름 은행 가져오기 ──

    private suspend fun importNameBank(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = nameBankSpec()
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val genderColIndex = cols["성별"] ?: 1
        val originColIndex = cols["출처"] ?: 2
        val notesColIndex = cols["메모"] ?: 3
        val usedColIndex = cols["사용여부"] ?: 4
        val usedByColIndex = cols["사용 캐릭터"] ?: 5
        val charCodeColIndex = cols["사용캐릭터코드"] ?: -1

        val existingNamesMap = db.nameBankDao().getAllNamesList()
            .associateBy { "${it.name}\u0000${it.gender}" }
            .toMutableMap()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val gender = getCellString(row, genderColIndex)
                val origin = getCellString(row, originColIndex)
                val notes = getCellString(row, notesColIndex)
                val isUsed = parseBoolean(getCellString(row, usedColIndex))
                val usedByCharName = getCellString(row, usedByColIndex)
                val usedByCharCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""

                val usedByCharacterId = if (usedByCharCode.isNotBlank()) {
                    db.characterDao().getCharacterByCode(usedByCharCode)?.id
                } else null
                    ?: if (usedByCharName.isNotBlank()) findCharacterByName(usedByCharName, null)?.id else null

                val effectiveIsUsed = isUsed && usedByCharacterId != null

                val mapKey = "${name}\u0000${gender}"
                val existing = existingNamesMap[mapKey]

                if (existing != null) {
                    db.nameBankDao().update(existing.copy(
                        origin = origin, notes = notes,
                        isUsed = effectiveIsUsed, usedByCharacterId = usedByCharacterId
                    ))
                    result.updatedNameBank++
                } else {
                    val newEntry = NameBankEntry(
                        name = name, gender = gender, origin = origin, notes = notes,
                        isUsed = effectiveIsUsed, usedByCharacterId = usedByCharacterId
                    )
                    val newId = db.nameBankDao().insert(newEntry)
                    existingNamesMap[mapKey] = newEntry.copy(id = newId)
                    result.newNameBank++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("이름 은행 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "이름 은행", sheet.lastRowNum, totalRows)
    }

    // ── 유틸리티 메서드 ──

    private fun findSheetForUniverse(workbook: Workbook, universeName: String, reservedNames: Set<String>): Sheet? {
        workbook.getSheet(universeName)?.let { return it }
        val sanitized = universeName
            .replace(Regex("[\\[\\]*/\\\\?:]"), "")
            .take(31)
        if (sanitized != universeName) {
            workbook.getSheet(sanitized)?.let { return it }
        }
        for (idx in 0 until workbook.numberOfSheets) {
            val sheetName = workbook.getSheetName(idx)
            if (sheetName in reservedNames) continue
            val baseName = sheetName.replace(Regex("\\(\\d+\\)$"), "")
            if (baseName == sanitized || (sanitized.startsWith(baseName) && sheetName.length >= 31)) {
                return workbook.getSheetAt(idx)
            }
        }
        return null
    }

    private fun buildColumnFieldMap(headerRow: Row, fields: List<FieldDefinition>, fixedColIndices: Set<Int>): Map<Int, FieldDefinition> {
        val map = mutableMapOf<Int, FieldDefinition>()
        val lastCol = headerRow.lastCellNum.toInt()
        for (col in 0 until lastCol) {
            if (col in fixedColIndices) continue
            val headerName = getCellString(headerRow, col)
            if (headerName.isBlank()) continue
            val trimmedHeader = headerName.trim()
            val field = fields.find { it.name == trimmedHeader }
                ?: fields.find { it.name.equals(trimmedHeader, ignoreCase = true) }
            if (field != null) {
                map[col] = field
            }
        }
        return map
    }

    private suspend fun resolveNovelId(novelTitle: String, universeId: Long? = null): Long? {
        if (novelTitle.isBlank()) return null
        val cacheKey = novelTitle to universeId
        novelIdCache[cacheKey]?.let { return it }
        val existing = if (universeId != null) {
            db.novelDao().getNovelByTitleAndUniverse(novelTitle, universeId)
        } else {
            db.novelDao().getNovelByTitleNoUniverse(novelTitle)
        }
        if (existing != null) {
            novelIdCache[cacheKey] = existing.id
            return existing.id
        }
        val newId = db.novelDao().insert(Novel(title = novelTitle, universeId = universeId, code = generateEntityCode()))
        novelIdCache[cacheKey] = newId
        return newId
    }

    private suspend fun findCharacterByName(name: String, preferredNovelId: Long?): Character? {
        if (preferredNovelId != null) {
            val match = db.characterDao().getCharacterByNameAndNovel(name, preferredNovelId)
            if (match != null) return match
        }
        return db.characterDao().getCharacterByName(name)
    }

    /**
     * Tolerant number parsing (Sprint C): handles "12", "12.0", " 12 ", etc.
     */
    private fun parseNumber(value: String): Double? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return trimmed.toDoubleOrNull()
    }

    /**
     * Tolerant boolean parsing (Sprint C): Y/N, TRUE/FALSE, 1/0, yes/no
     */
    private fun parseBoolean(value: String): Boolean {
        return when (value.trim().uppercase()) {
            "Y", "YES", "TRUE", "1", "O", "예" -> true
            else -> false
        }
    }

    private fun getCellString(row: Row, cellIndex: Int, maxLength: Int = MAX_FIELD_LENGTH): String {
        if (cellIndex < 0) return ""
        val cell = row.getCell(cellIndex) ?: return ""
        val raw = when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue?.trim() ?: ""
            CellType.NUMERIC -> {
                val value = cell.numericCellValue
                when {
                    value.isNaN() || value.isInfinite() -> ""
                    value == value.toLong().toDouble() -> value.toLong().toString()
                    else -> value.toString()
                }
            }
            CellType.BOOLEAN -> if (cell.booleanCellValue) "Y" else "N"
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue?.trim() ?: ""
                } catch (e: Exception) {
                    try {
                        val value = cell.numericCellValue
                        if (value.isNaN() || value.isInfinite()) "" else {
                            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
                        }
                    } catch (_: Exception) { "" }
                }
            }
            else -> ""
        }
        if (raw.length > maxLength) {
            truncatedFieldCount++
            return raw.substring(0, maxLength)
        }
        return raw
    }

    companion object {
        private const val MAX_FIELD_LENGTH = 10000
        private const val GUIDE_SHEET_NAME = "사용 안내"
        private const val UNCLASSIFIED_SHEET_NAME = "미분류 캐릭터"
    }
}

/**
 * Normalize header string for alias matching:
 * lowercase, remove spaces/underscores/special chars
 */
private fun String.normalizeHeader(): String =
    this.trim().lowercase().replace(Regex("[\\s_\\-()（）]"), "")
