package com.novelcharacter.app.excel

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.UserPresetTemplate
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

data class CategoryAnalysis(
    val key: String,
    val label: String,
    val inBackup: Int,
    val newCount: Int,
    val updateCount: Int,
    val unchangedCount: Int,
    val existingTotal: Int
) {
    /** 백업에 없고 DB에만 있는 항목 수 (덮어쓰기 시 삭제 대상) */
    val onlyInDb: Int get() = existingTotal - (updateCount + unchangedCount)
}

data class RestoreAnalysis(
    val categories: List<CategoryAnalysis>,
    val characterConflicts: List<CharacterConflict> = emptyList()
)

enum class ConflictResolution {
    SKIP,
    CREATE_NEW,
    UPDATE_EXISTING
}

data class CharacterConflict(
    val excelRowIndex: Int,
    val sheetName: String,
    val excelName: String,
    val excelNovelTitle: String?,
    val existingCharacters: List<Character>,
    var resolution: ConflictResolution = ConflictResolution.CREATE_NEW,
    var selectedExistingId: Long? = null
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
    var newPresetTemplates: Int = 0,
    var updatedPresetTemplates: Int = 0,
    var newSearchPresets: Int = 0,
    var updatedSearchPresets: Int = 0,
    var newFactions: Int = 0,
    var updatedFactions: Int = 0,
    var newFactionMemberships: Int = 0,
    var updatedFactionMemberships: Int = 0,
    var restoredSettings: Int = 0,
    var skippedRows: Int = 0,
    val errors: MutableList<String> = mutableListOf(),
    var nameBasedMappings: Int = 0,
    var autoRepairedValues: Int = 0,
    var newCodesGenerated: Int = 0,
    val warnings: MutableList<String> = mutableListOf(),
    val pendingConflicts: MutableList<String> = mutableListOf()
)

class ExcelImportService(private val db: AppDatabase, private val appContext: android.content.Context? = null) {

    private val novelIdCache = mutableMapOf<Pair<String, Long?>, Long?>()
    private var truncatedFieldCount = 0
    private val truncatedDetails = mutableListOf<String>()

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
        alias("이미지경로", "image_path", "이미지 경로", "image_file", "imagepath", "imagepaths")
        alias("이미지모드", "image_mode", "이미지 모드")
        alias("이미지캐릭터ID", "image_character_id", "이미지 캐릭터 ID")
        alias("이미지작품ID", "image_novel_id", "이미지 작품 ID")
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
        // Relationship extra
        alias("강도", "intensity")
        alias("양방향", "bidirectional", "is_bidirectional")
        alias("표시순서", "display_order_rel")
        // Universe custom relationship
        alias("커스텀관계유형", "custom_relationship_types", "커스텀 관계 유형")
        alias("커스텀관계색상", "custom_relationship_colors", "커스텀 관계 색상")
        // Novel extra
        alias("표준연도", "standard_year", "표준 연도")
        // Timeline extra
        alias("임시배치", "is_temporary", "임시 배치", "temporary")
        // Name bank
        alias("성별", "gender", "sex")
        alias("출처", "origin", "문화권")
        alias("사용여부", "is_used")
        alias("사용 캐릭터", "used_by", "사용캐릭터")
        alias("사용캐릭터코드", "used_character_code")
        // Border color (Sprint D)
        alias("테두리색", "border_color", "bordercolor")
        alias("테두리두께", "border_width", "borderwidth")
        // User preset template
        alias("기본제공", "is_built_in", "builtin")
        alias("생성일", "created_at", "createdat")
        alias("수정일", "updated_at", "updatedat")
        // Search preset
        alias("검색어", "query", "search_query")
        alias("필터(JSON)", "filters", "filter_json")
        alias("정렬모드", "sort_mode", "sortmode")
        alias("기본값", "is_default", "default")
        // App settings
        alias("설정키", "setting_key", "key")
        alias("설정값", "setting_value", "value")
        // Faction
        alias("색상", "color", "faction_color")
        alias("자동관계유형", "auto_relation_type", "autorelationtype")
        alias("자동관계강도", "auto_relation_intensity", "autorelationintensity")
        // Faction membership
        alias("세력", "faction", "faction_name")
        alias("가입연도", "join_year", "joinyear")
        alias("탈퇴연도", "leave_year", "leaveyear")
        alias("탈퇴유형", "leave_type", "leavetype")
        alias("탈퇴후관계유형", "departed_relation_type", "departedrelationtype")
        alias("탈퇴후강도", "departed_intensity", "departedintensity")
        alias("세력코드", "faction_code", "factioncode")
    }

    /** 가져올 이미지의 경로 재매핑: {원본경로 → 새경로} */
    var imagePathRemap: Map<String, String> = emptyMap()

    /** 단일 이미지 경로를 재매핑. 매핑 없으면 원본 반환. */
    private fun remapImagePath(path: String): String {
        if (imagePathRemap.isEmpty() || path.isBlank()) return path
        return imagePathRemap[path] ?: path
    }

    /** imagePaths JSON 배열 내 모든 경로를 재매핑. 레거시 단일 경로도 JSON 배열로 변환. */
    private fun remapImagePaths(imagePathsJson: String): String {
        if (imagePathsJson.isBlank() || imagePathsJson == "[]") return "[]"
        return try {
            val gson = com.google.gson.Gson()
            val paths = gson.fromJson(imagePathsJson, Array<String>::class.java)
            if (imagePathRemap.isEmpty()) gson.toJson(paths) else {
                val remapped = paths?.map { remapImagePath(it) } ?: return imagePathsJson
                gson.toJson(remapped)
            }
        } catch (_: Exception) {
            // 레거시: 단일 경로 문자열 → JSON 배열로 변환
            if (imagePathsJson.isNotBlank()) {
                val remapped = remapImagePath(imagePathsJson)
                org.json.JSONArray(listOf(remapped)).toString()
            } else "[]"
        }
    }

    suspend fun importAll(
        workbook: Workbook,
        options: ExportOptions = ExportOptions(),
        strategy: ImportStrategy = ImportStrategy.MERGE,
        resolvedConflicts: Map<String, CharacterConflict> = emptyMap(),
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult {
        val result = ImportResult()
        novelIdCache.clear()
        processedRowsSoFar = 0
        truncatedFieldCount = 0
        truncatedDetails.clear()

        val totalRows = countTotalRows(workbook)

        // OVERWRITE 전략 시 CASCADE 종속 카테고리를 자동 포함하여 데이터 정합성 보장.
        // 예: 세계관 덮어쓰기 → 필드 정의·세력이 CASCADE 삭제되므로 재가져오기 필수.
        val effectiveOptions = if (strategy == ImportStrategy.OVERWRITE) {
            options.copy(
                // 세계관 삭제 → 필드 정의, 세력 CASCADE 삭제 → 재가져오기 필수
                fieldDefinitions = options.fieldDefinitions || options.universes,
                factions = options.factions || options.universes,
                // 세력 삭제 → 세력 소속 CASCADE 삭제 → 재가져오기 필수
                factionMemberships = options.factionMemberships || options.factions || options.universes,
                // 캐릭터 삭제 → 관계, 상태변화 CASCADE 삭제 → 재가져오기 필수
                relationships = options.relationships || options.characters,
                relationshipChanges = options.relationshipChanges || options.characters || options.relationships,
                stateChanges = options.stateChanges || options.characters
            )
        } else {
            options
        }

        // 전체를 단일 트랜잭션으로 감싸서 부분 커밋 방지
        // Room은 중첩 withTransaction을 savepoint로 처리하므로 phase별 격리 유지
        db.withTransaction {
            // 덮어쓰기 전략: 선택된 카테고리의 기존 데이터를 먼저 삭제
            // CASCADE 안전 순서: 종속 데이터 → 상위 엔티티
            if (strategy == ImportStrategy.OVERWRITE) {
                if (effectiveOptions.relationshipChanges) db.characterRelationshipChangeDao().deleteAll()
                if (effectiveOptions.relationships) db.characterRelationshipDao().deleteAll()
                if (effectiveOptions.factionMemberships) db.factionMembershipDao().deleteAll()
                if (effectiveOptions.factions) db.factionDao().deleteAll()
                if (effectiveOptions.stateChanges) db.characterStateChangeDao().deleteAll()
                if (effectiveOptions.timeline) {
                    db.timelineDao().deleteAllCrossRefs()
                    db.timelineDao().deleteAllEvents()
                }
                if (effectiveOptions.characters) db.characterDao().deleteAll()
                if (effectiveOptions.fieldDefinitions) db.fieldDefinitionDao().deleteAll()
                if (effectiveOptions.novels) db.novelDao().deleteAll()
                if (effectiveOptions.universes) db.universeDao().deleteAll()
                if (effectiveOptions.nameBank) db.nameBankDao().deleteAll()
                if (effectiveOptions.presetTemplates) db.userPresetTemplateDao().deleteAll()
                if (effectiveOptions.searchPresets) db.searchPresetDao().deleteAll()
            }

            // Phase 1: Schema definitions (universes, novels, field definitions)
            if (effectiveOptions.universes) importUniverses(workbook, result, onProgress, totalRows)
            if (effectiveOptions.novels) importNovels(workbook, result, onProgress, totalRows)
            if (effectiveOptions.fieldDefinitions) importFieldDefinitions(workbook, result, onProgress, totalRows)

            // Phase 2: Entity data (characters)
            if (effectiveOptions.characters) {
                importCharacterSheets(workbook, result, resolvedConflicts, onProgress, totalRows)
                importUnclassifiedCharacters(workbook, result, resolvedConflicts, onProgress, totalRows)
            }

            // Phase 3: Relationships and references
            if (effectiveOptions.timeline) importTimeline(workbook, result, onProgress, totalRows)
            if (effectiveOptions.stateChanges) importStateChanges(workbook, result, onProgress, totalRows)
            if (effectiveOptions.relationships) importRelationships(workbook, result, onProgress, totalRows)
            if (effectiveOptions.relationshipChanges) importRelationshipChanges(workbook, result, onProgress, totalRows)
            if (effectiveOptions.nameBank) importNameBank(workbook, result, onProgress, totalRows)
            if (effectiveOptions.factions) importFactions(workbook, result, onProgress, totalRows)
            if (effectiveOptions.factionMemberships) importFactionMemberships(workbook, result, onProgress, totalRows)

            // Phase 4: User settings and presets
            if (effectiveOptions.presetTemplates) importUserPresetTemplates(workbook, result, onProgress, totalRows)
            if (effectiveOptions.searchPresets) importSearchPresets(workbook, result, onProgress, totalRows)
            if (effectiveOptions.appSettings) importAppSettings(workbook, result)
        }

        if (truncatedFieldCount > 0) {
            val detail = if (truncatedDetails.size <= 5) {
                truncatedDetails.joinToString(", ")
            } else {
                truncatedDetails.take(5).joinToString(", ") + " 외 ${truncatedDetails.size - 5}건"
            }
            result.warnings.add("${truncatedFieldCount}개 필드값이 ${MAX_FIELD_LENGTH}자 제한으로 잘렸습니다. ($detail)")
        }

        return result
    }

    // ── 복원 미리보기 분석 (읽기 전용) ──

    suspend fun analyzeAll(
        workbook: Workbook,
        options: ExportOptions = ExportOptions(),
        onProgress: (ImportProgress) -> Unit = {}
    ): RestoreAnalysis {
        val categories = mutableListOf<CategoryAnalysis>()
        var characterConflicts = emptyList<CharacterConflict>()
        processedRowsSoFar = 0
        val totalRows = countTotalRows(workbook)

        if (options.universes) categories.add(analyzeUniverses(workbook, onProgress, totalRows))
        if (options.novels) categories.add(analyzeNovels(workbook, onProgress, totalRows))
        if (options.fieldDefinitions) categories.add(analyzeFieldDefinitions(workbook, onProgress, totalRows))
        if (options.characters) {
            val charResult = analyzeCharacters(workbook, onProgress, totalRows)
            categories.add(charResult.category)
            characterConflicts = charResult.conflicts
        }
        if (options.timeline) categories.add(analyzeTimeline(workbook, onProgress, totalRows))
        if (options.stateChanges) categories.add(analyzeStateChanges(workbook, onProgress, totalRows))
        if (options.relationships) categories.add(analyzeRelationships(workbook, onProgress, totalRows))
        if (options.relationshipChanges) categories.add(analyzeRelationshipChanges(workbook, onProgress, totalRows))
        if (options.nameBank) categories.add(analyzeNameBank(workbook, onProgress, totalRows))
        if (options.factions) categories.add(analyzeFactions(workbook, onProgress, totalRows))
        if (options.factionMemberships) categories.add(analyzeFactionMemberships(workbook, onProgress, totalRows))
        if (options.presetTemplates) categories.add(analyzePresetTemplates(workbook, onProgress, totalRows))
        if (options.searchPresets) categories.add(analyzeSearchPresets(workbook, onProgress, totalRows))

        return RestoreAnalysis(categories, characterConflicts)
    }

    private suspend fun analyzeUniverses(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = universeSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.universeDao().getAllUniversesList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val codeColIndex = cols["코드"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++
            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val description = getCellString(row, descColIndex)

            val existing = if (code.isNotBlank()) db.universeDao().getUniverseByCode(code) else db.universeDao().getUniverseByName(name)
            if (existing == null) { newCount++; continue }
            if (existing.name != name || existing.description != description) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세계관 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("universes", "세계관", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeNovels(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = novelSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.novelDao().getAllNovelsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val titleColIndex = cols[spec.firstColumnHeader] ?: cols["제목"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val codeColIndex = cols["코드"] ?: -1
        val universeNameColIndex = cols["세계관"] ?: 2

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val title = getCellString(row, titleColIndex)
            if (title.isBlank()) continue
            inBackup++
            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val description = getCellString(row, descColIndex)
            val universeName = getCellString(row, universeNameColIndex)

            val existing = if (code.isNotBlank()) {
                db.novelDao().getNovelByCode(code)
            } else {
                val universeId = if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null
                if (universeId != null) db.novelDao().getNovelByTitleAndUniverse(title, universeId) else db.novelDao().getNovelByTitleNoUniverse(title)
            }
            if (existing == null) { newCount++; continue }
            if (existing.title != title || existing.description != description) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "작품 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("novels", "작품", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFieldDefinitions(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = fieldDefinitionSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.fieldDefinitionDao().getAllFieldsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val universeNameColIndex = cols[spec.firstColumnHeader] ?: cols["세계관"] ?: 0
        val keyColIndex = cols["필드키"] ?: 1
        val nameColIndex = cols["필드명"] ?: 2
        val typeColIndex = cols["타입"] ?: 3
        val universeCodeColIndex = cols["세계관코드"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val universeName = getCellString(row, universeNameColIndex)
            if (universeName.isBlank()) continue
            val key = getCellString(row, keyColIndex)
            if (key.isBlank()) continue
            inBackup++

            val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
            val universe = (if (universeCode.isNotBlank()) db.universeDao().getUniverseByCode(universeCode) else null)
                ?: db.universeDao().getUniverseByName(universeName) ?: run { newCount++; continue }

            val name = getCellString(row, nameColIndex)
            val type = getCellString(row, typeColIndex)
            val existing = db.fieldDefinitionDao().getFieldByKey(universe.id, key)
            if (existing == null) { newCount++; continue }
            if (existing.name != name || existing.type != type) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "필드 정의 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("fieldDefinitions", "필드 정의", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    data class CharacterAnalysisResult(
        val category: CategoryAnalysis,
        val conflicts: List<CharacterConflict>
    )

    private suspend fun analyzeCharacters(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CharacterAnalysisResult {
        val existingTotal = db.characterDao().getAllCharactersList().size
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        val allConflicts = mutableListOf<CharacterConflict>()

        // 세계관별 캐릭터 시트 분석
        val universes = db.universeDao().getAllUniversesList()
        val reservedNames = RESERVED_SHEET_NAMES
        for (universe in universes) {
            val sheet = findSheetForUniverse(workbook, universe.name, reservedNames) ?: continue
            val headerRow = sheet.getRow(0) ?: continue
            if (!isValidHeader(headerRow, "이름")) continue
            val result = analyzeCharacterSheet(sheet, headerRow, universe.name)
            inBackup += result.first; newCount += result.second; updateCount += result.third
            unchangedCount += (result.first - result.second - result.third)
            allConflicts.addAll(result.fourth)
        }

        // 미분류 캐릭터 분석
        val unclSheet = workbook.getSheet(UNCLASSIFIED_SHEET_NAME)
        if (unclSheet != null) {
            val headerRow = unclSheet.getRow(0)
            if (headerRow != null && isValidHeader(headerRow, "이름")) {
                val result = analyzeCharacterSheet(unclSheet, headerRow, UNCLASSIFIED_SHEET_NAME)
                inBackup += result.first; newCount += result.second; updateCount += result.third
                unchangedCount += (result.first - result.second - result.third)
                allConflicts.addAll(result.fourth)
            }
        }

        reportProgress(onProgress, "캐릭터 분석", 0, totalRows)
        val category = CategoryAnalysis("characters", "캐릭터", inBackup, newCount, updateCount, unchangedCount, existingTotal)
        return CharacterAnalysisResult(category, allConflicts)
    }

    /** @return Quad(inBackup, newCount, updateCount, conflicts) */
    private data class SheetAnalysis(val first: Int, val second: Int, val third: Int, val fourth: List<CharacterConflict>)

    private suspend fun analyzeCharacterSheet(sheet: Sheet, headerRow: Row, sheetLabel: String): SheetAnalysis {
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val codeColIndex = cols["코드"] ?: -1
        val memoColIndex = cols["메모"] ?: -1
        val anotherNameColIndex = cols["이명"] ?: -1
        val novelColIndex = cols["작품"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0
        val conflicts = mutableListOf<CharacterConflict>()

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            if (code.isNotBlank()) {
                // 코드 기반 매칭: 충돌 없음 (코드가 권위적)
                val existing = db.characterDao().getCharacterByCode(code)
                if (existing == null) { newCount++; continue }
                val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""
                val anotherName = if (anotherNameColIndex >= 0) getCellString(row, anotherNameColIndex) else ""
                if (existing.name != name || existing.memo != memo || existing.anotherName != anotherName) updateCount++
            } else {
                // 코드 없음: 이름 기반 매칭 — 동명이인 충돌 가능
                val allMatches = db.characterDao().getAllCharactersByName(name)
                if (allMatches.isEmpty()) {
                    newCount++
                } else if (allMatches.size == 1) {
                    // 단일 매칭: 기존 동작과 동일
                    val existing = allMatches[0]
                    val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""
                    val anotherName = if (anotherNameColIndex >= 0) getCellString(row, anotherNameColIndex) else ""
                    if (existing.name != name || existing.memo != memo || existing.anotherName != anotherName) updateCount++
                } else {
                    // 다중 매칭: 충돌 발생
                    val novelTitle = if (novelColIndex >= 0) getCellString(row, novelColIndex) else null
                    conflicts.add(CharacterConflict(
                        excelRowIndex = i,
                        sheetName = sheetLabel,
                        excelName = name,
                        excelNovelTitle = novelTitle?.ifBlank { null },
                        existingCharacters = allMatches
                    ))
                    // 충돌은 사용자 결정 전까지 분류 미정이므로 newCount에 임시 포함
                    newCount++
                }
            }
        }
        return SheetAnalysis(inBackup, newCount, updateCount, conflicts)
    }

    private suspend fun analyzeTimeline(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = timelineSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.timelineDao().getAllEventsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val yearColIndex = cols["연도"] ?: 0
        val descColIndex = cols["사건 설명"] ?: 4
        val novelColIndex = cols["관련 작품"] ?: 5
        val novelCodeColIndex = cols["관련작품코드"] ?: -1

        val allNovels = db.novelDao().getAllNovelsList()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val year = parseNumber(getCellString(row, yearColIndex))?.toInt() ?: continue
            val description = getCellString(row, descColIndex)
            if (description.isBlank()) continue
            inBackup++

            val novelCode = if (novelCodeColIndex >= 0) getCellString(row, novelCodeColIndex) else ""
            val novelTitle = getCellString(row, novelColIndex)
            val novelId = (if (novelCode.isNotBlank()) db.novelDao().getNovelByCode(novelCode)?.id else null)
                ?: if (novelTitle.isNotBlank()) allNovels.find { it.title == novelTitle }?.id else null

            val existing = if (novelId != null) db.timelineDao().getEventByNaturalKey(year, description, novelId)
            else db.timelineDao().getEventByNaturalKeyNoNovel(year, description)
            if (existing == null) newCount++ else unchangedCount++ // 사건은 natural key 매칭이므로 키가 같으면 동일
        }
        reportProgress(onProgress, "사건 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("timeline", "사건 연표", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeStateChanges(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = stateChangeSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.characterStateChangeDao().getAllChangesList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val charNameColIndex = cols["캐릭터"] ?: 0
        val yearColIndex = cols["연도"] ?: 2
        val fieldKeyColIndex = cols["필드키"] ?: 5
        val newValueColIndex = cols["새 값"] ?: 6
        val charCodeColIndex = cols["캐릭터코드"] ?: -1
        val novelColIndex = cols["작품"] ?: 1

        val allNovels = db.novelDao().getAllNovelsList()
        var inBackup = 0; var newCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val charName = getCellString(row, charNameColIndex)
            if (charName.isBlank()) continue
            val year = parseNumber(getCellString(row, yearColIndex))?.toInt() ?: continue
            val fieldKey = getCellString(row, fieldKeyColIndex)
            if (fieldKey.isBlank()) continue
            val newValue = getCellString(row, newValueColIndex)
            if (newValue.isBlank()) continue
            inBackup++

            val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""
            val novelTitle = getCellString(row, novelColIndex)
            val character = (if (charCode.isNotBlank()) db.characterDao().getCharacterByCode(charCode) else null)
                ?: run {
                    val novelId = if (novelTitle.isNotBlank()) allNovels.find { it.title == novelTitle }?.id else null
                    findCharacterByName(charName, novelId)
                } ?: run { newCount++; continue }

            val existing = db.characterStateChangeDao().getChangeByNaturalKey(character.id, year, fieldKey, newValue)
            if (existing == null) newCount++ else unchangedCount++
        }
        reportProgress(onProgress, "상태 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("stateChanges", "상태 변화", inBackup, newCount, 0, unchangedCount, existingTotal)
    }

    private suspend fun analyzeRelationships(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = relationshipSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.characterRelationshipDao().getAllRelationships().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        val char2NameColIndex = cols["캐릭터2"] ?: 1
        val typeColIndex = cols["관계 유형"] ?: 2
        val descColIndex = cols["설명"] ?: 3
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val char1Name = getCellString(row, char1NameColIndex)
            val char2Name = getCellString(row, char2NameColIndex)
            if (char1Name.isBlank() || char2Name.isBlank()) continue
            val relationshipType = getCellString(row, typeColIndex)
            if (relationshipType.isBlank()) continue
            inBackup++

            val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
            val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""
            val char1 = (if (char1Code.isNotBlank()) db.characterDao().getCharacterByCode(char1Code) else null) ?: findCharacterByName(char1Name, null) ?: run { newCount++; continue }
            val char2 = (if (char2Code.isNotBlank()) db.characterDao().getCharacterByCode(char2Code) else null) ?: findCharacterByName(char2Name, null) ?: run { newCount++; continue }
            if (char1.id == char2.id) continue

            val existingRels = db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id)
            val existing = existingRels.find { rel ->
                ((rel.characterId1 == char1.id && rel.characterId2 == char2.id) || (rel.characterId1 == char2.id && rel.characterId2 == char1.id)) && rel.relationshipType == relationshipType
            }
            if (existing == null) { newCount++; continue }
            val description = getCellString(row, descColIndex)
            if (existing.description != description) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationships", "관계", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeRelationshipChanges(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val sheet = workbook.getSheet("관계 변화")
        val existingTotal = db.characterRelationshipChangeDao().getAllChanges().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        val char2NameColIndex = cols["캐릭터2"] ?: 1
        val yearColIndex = cols["연도"] ?: 2
        val monthColIndex = cols["월"] ?: 3
        val dayColIndex = cols["일"] ?: 4
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1

        var inBackup = 0; var newCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val char1Name = getCellString(row, char1NameColIndex)
            val char2Name = getCellString(row, char2NameColIndex)
            if (char1Name.isBlank() || char2Name.isBlank()) continue
            val year = parseNumber(getCellString(row, yearColIndex))?.toInt() ?: continue
            inBackup++

            val month = parseNumber(getCellString(row, monthColIndex))?.toInt()
            val day = parseNumber(getCellString(row, dayColIndex))?.toInt()
            val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
            val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""
            val char1 = (if (char1Code.isNotBlank()) db.characterDao().getCharacterByCode(char1Code) else null) ?: findCharacterByName(char1Name, null) ?: run { newCount++; continue }
            val char2 = (if (char2Code.isNotBlank()) db.characterDao().getCharacterByCode(char2Code) else null) ?: findCharacterByName(char2Name, null) ?: run { newCount++; continue }

            val relationships = db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id)
            val relationship = relationships.find { rel ->
                (rel.characterId1 == char1.id && rel.characterId2 == char2.id) || (rel.characterId1 == char2.id && rel.characterId2 == char1.id)
            } ?: run { newCount++; continue }

            val existing = db.characterRelationshipChangeDao().getChangeByNaturalKey(relationship.id, year, month, day)
            if (existing == null) newCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationshipChanges", "관계 변화", inBackup, newCount, 0, unchangedCount, existingTotal)
    }

    private suspend fun analyzeNameBank(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = nameBankSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingNames = db.nameBankDao().getAllNamesList()
        val existingTotal = existingNames.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val genderColIndex = cols["성별"] ?: 1
        val originColIndex = cols["출처"] ?: 2
        val notesColIndex = cols["메모"] ?: 3

        val existingMap = existingNames.associateBy { "${it.name}\u0000${it.gender}" }
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val gender = getCellString(row, genderColIndex)
            val mapKey = "${name}\u0000${gender}"
            val existing = existingMap[mapKey]
            if (existing == null) { newCount++; continue }
            val origin = getCellString(row, originColIndex)
            val notes = getCellString(row, notesColIndex)
            if (existing.origin != origin || existing.notes != notes) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "이름 은행 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("nameBank", "이름 은행", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFactions(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.factionDao().getAllFactionsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val universeNameColIndex = cols["세계관"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val universeName = if (universeNameColIndex >= 0) getCellString(row, universeNameColIndex) else ""
            val universeId = if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null

            val existing = if (code.isNotBlank()) db.factionDao().getByCode(code)
            else if (universeId != null) db.factionDao().getByNameAndUniverse(name, universeId) else null

            if (existing == null) { newCount++; continue }
            val description = if (descColIndex >= 0) getCellString(row, descColIndex) else ""
            if (existing.name != name || existing.description != description) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세력 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factions", "세력", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFactionMemberships(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionMembershipSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.factionMembershipDao().getAllMembershipsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val factionNameColIndex = cols[spec.firstColumnHeader] ?: cols["세력"] ?: 0
        val charNameColIndex = cols["캐릭터"] ?: -1
        val factionCodeColIndex = cols["세력코드"] ?: -1
        val charCodeColIndex = cols["캐릭터코드"] ?: -1
        val leaveTypeColIndex = cols["탈퇴유형"] ?: -1

        if (charNameColIndex < 0) return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)

        var inBackup = 0; var newCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val factionName = getCellString(row, factionNameColIndex)
            if (factionName.isBlank()) continue
            val charName = getCellString(row, charNameColIndex)
            if (charName.isBlank()) continue
            inBackup++

            val factionCode = if (factionCodeColIndex >= 0) getCellString(row, factionCodeColIndex) else ""
            val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""
            val leaveTypeRaw = if (leaveTypeColIndex >= 0) getCellString(row, leaveTypeColIndex) else ""
            val leaveType = when (leaveTypeRaw.trim()) { "순수제거", "removed" -> "removed"; "설정상탈퇴", "departed" -> "departed"; else -> null }

            val faction = (if (factionCode.isNotBlank()) db.factionDao().getByCode(factionCode) else null)
                ?: db.factionDao().getAllFactionsList().find { it.name == factionName }
                ?: run { newCount++; continue }
            val character = (if (charCode.isNotBlank()) db.characterDao().getCharacterByCode(charCode) else null)
                ?: db.characterDao().getCharacterByName(charName)
                ?: run { newCount++; continue }

            val existingMembership = db.factionMembershipDao().getActiveMembership(faction.id, character.id)
            if (existingMembership != null && leaveType == null) unchangedCount++ else newCount++
        }
        reportProgress(onProgress, "세력 소속 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factionMemberships", "세력 소속", inBackup, newCount, 0, unchangedCount, existingTotal)
    }

    private suspend fun analyzePresetTemplates(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = userPresetTemplateSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTemplates = db.userPresetTemplateDao().getAllTemplatesList()
        val existingTotal = existingTemplates.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("presetTemplates", "필드 템플릿", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("presetTemplates", "필드 템플릿", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val fieldsJsonColIndex = cols["설정(JSON)"] ?: 2

        val existingByName = existingTemplates.associateBy { it.name }
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val existing = existingByName[name]
            if (existing == null) { newCount++; continue }
            val description = getCellString(row, descColIndex)
            val fieldsJson = getCellString(row, fieldsJsonColIndex).ifBlank { "[]" }
            if (existing.description != description || existing.fieldsJson != fieldsJson) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "필드 템플릿 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("presetTemplates", "필드 템플릿", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeSearchPresets(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = searchPresetSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingPresets = db.searchPresetDao().getAllPresetsList()
        val existingTotal = existingPresets.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("searchPresets", "검색 프리셋", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("searchPresets", "검색 프리셋", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val queryColIndex = cols["검색어"] ?: 1
        val filtersColIndex = cols["필터(JSON)"] ?: 2

        val existingByName = existingPresets.associateBy { it.name }
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val existing = existingByName[name]
            if (existing == null) { newCount++; continue }
            val query = getCellString(row, queryColIndex)
            val filtersJson = getCellString(row, filtersColIndex).ifBlank { "{}" }
            if (existing.query != query || existing.filtersJson != filtersJson) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "검색 프리셋 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("searchPresets", "검색 프리셋", inBackup, newCount, updateCount, unchangedCount, existingTotal)
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

    /**
     * 시트를 찾고, 못 찾으면 경고를 남기고 null 반환.
     */
    private fun findSheet(workbook: Workbook, sheetName: String, result: ImportResult): Sheet? {
        val sheet = workbook.getSheet(sheetName)
        if (sheet == null) {
            result.warnings.add("'$sheetName' 시트를 찾을 수 없어 해당 데이터를 건너뛰었습니다.")
        }
        return sheet
    }

    // ── 세계관 가져오기 ──

    private suspend fun importUniverses(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = universeSpec()
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
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
        val customRelTypesColIndex = cols["커스텀관계유형"] ?: -1
        val customRelColorsColIndex = cols["커스텀관계색상"] ?: -1
        val imageCharIdColIndex = cols["이미지캐릭터ID"] ?: -1
        val imageNovelIdColIndex = cols["이미지작품ID"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

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
                val rawImagePaths = if (imagePathColIndex >= 0) getCellString(row, imagePathColIndex) else "[]"
                val imagePaths = remapImagePaths(rawImagePaths)
                val imageMode = if (imageModeColIndex >= 0) getCellString(row, imageModeColIndex).ifBlank { "none" } else "none"
                val customRelTypes = if (customRelTypesColIndex >= 0) getCellString(row, customRelTypesColIndex) else ""
                val customRelColors = if (customRelColorsColIndex >= 0) getCellString(row, customRelColorsColIndex) else ""
                val imageCharId = if (imageCharIdColIndex >= 0) parseNumber(getCellString(row, imageCharIdColIndex))?.toLong() else null
                val imageNovelId = if (imageNovelIdColIndex >= 0) parseNumber(getCellString(row, imageNovelIdColIndex))?.toLong() else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                        existing = null
                        matchedByName = false
                    }
                } else {
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
                        imagePaths = imagePaths, imageMode = imageMode,
                        // 구버전 파일(컬럼 없음)에서는 기존값 유지
                        customRelationshipTypes = if (customRelTypesColIndex >= 0) customRelTypes else existing.customRelationshipTypes,
                        customRelationshipColors = if (customRelColorsColIndex >= 0) customRelColors else existing.customRelationshipColors,
                        imageCharacterId = if (imageCharIdColIndex >= 0) imageCharId else existing.imageCharacterId,
                        imageNovelId = if (imageNovelIdColIndex >= 0) imageNovelId else existing.imageNovelId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    result.updatedUniverses++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    db.universeDao().insert(Universe(
                        name = name, description = description, code = newCode,
                        displayOrder = displayOrder, borderColor = borderColor, borderWidthDp = borderWidthDp,
                        imagePaths = imagePaths, imageMode = imageMode,
                        customRelationshipTypes = customRelTypes,
                        customRelationshipColors = customRelColors,
                        imageCharacterId = imageCharId,
                        imageNovelId = imageNovelId,
                        createdAt = createdAt
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
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
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
        val standardYearColIndex = cols["표준연도"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

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
                val rawNovelImagePaths = if (novelImagePathColIndex >= 0) getCellString(row, novelImagePathColIndex) else "[]"
                val novelImagePaths = remapImagePaths(rawNovelImagePaths)
                val novelImageMode = if (novelImageModeColIndex >= 0) getCellString(row, novelImageModeColIndex).ifBlank { "none" } else "none"
                val novelImageCharId = if (imageCharIdColIndex >= 0) parseNumber(getCellString(row, imageCharIdColIndex))?.toLong() else null
                val novelIsPinned = if (novelPinnedColIndex >= 0) parseBoolean(getCellString(row, novelPinnedColIndex)) else false
                val standardYear = if (standardYearColIndex >= 0) parseNumber(getCellString(row, standardYearColIndex))?.toInt() else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                        inheritUniverseBorder = if (inheritBorderColIndex >= 0) effectiveInherit else existing.inheritUniverseBorder,
                        isPinned = if (novelPinnedColIndex >= 0) novelIsPinned else existing.isPinned,
                        imagePaths = novelImagePaths, imageMode = novelImageMode,
                        imageCharacterId = if (imageCharIdColIndex >= 0) novelImageCharId else existing.imageCharacterId,
                        standardYear = if (standardYearColIndex >= 0) standardYear else existing.standardYear,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
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
                        imagePaths = novelImagePaths, imageMode = novelImageMode,
                        imageCharacterId = novelImageCharId, standardYear = standardYear,
                        createdAt = createdAt
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
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
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

    private suspend fun importCharacterSheets(workbook: Workbook, result: ImportResult, resolvedConflicts: Map<String, CharacterConflict>, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val universes = db.universeDao().getAllUniversesList()
        val reservedNames = RESERVED_SHEET_NAMES

        for (universe in universes) {
            val sheet = findSheetForUniverse(workbook, universe.name, reservedNames) ?: continue
            val headerRow = sheet.getRow(0) ?: continue
            if (!isValidHeader(headerRow, "이름")) continue

            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            importCharacterRows(sheet, headerRow, universe, fields, result, resolvedConflicts, universe.name, onProgress, totalRows)
        }
    }

    // ── 미분류 캐릭터 가져오기 ──

    private suspend fun importUnclassifiedCharacters(workbook: Workbook, result: ImportResult, resolvedConflicts: Map<String, CharacterConflict>, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val sheet = workbook.getSheet(UNCLASSIFIED_SHEET_NAME) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, "이름")) return

        importCharacterRows(sheet, headerRow, null, emptyList(), result, resolvedConflicts, UNCLASSIFIED_SHEET_NAME, onProgress, totalRows)
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
        resolvedConflicts: Map<String, CharacterConflict>,
        sheetLabel: String,
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
        val createdAtColIndex = cols["생성일"] ?: -1
        val fixedColIndices = setOf(nameColIndex, anotherNameColIndex, lastNameColIndex, firstNameColIndex, imageColIndex, novelColIndex, memoColIndex, tagsColIndex, codeColIndex, novelCodeColIndex, orderColIndex, pinnedColIndex, createdAtColIndex).filter { it >= 0 }.toSet()
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
                val rawImagePaths: String? = if (imageColIndex >= 0) getCellString(row, imageColIndex).ifBlank { "[]" } else null
                val imagePathsFromExcel: String? = rawImagePaths?.let { remapImagePaths(it) }
                val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""
                val displayOrder = if (orderColIndex >= 0) parseNumber(getCellString(row, orderColIndex))?.toLong() ?: 0L else 0L
                val charIsPinned = if (pinnedColIndex >= 0) parseBoolean(getCellString(row, pinnedColIndex)) else false
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                // 충돌 해결 확인
                val conflictKey = "$sheetLabel:$i"
                val conflict = resolvedConflicts[conflictKey]
                if (conflict != null && conflict.resolution == ConflictResolution.SKIP) {
                    result.skippedRows++
                    continue
                }

                // Code-first matching (Sprint A strict rule)
                val existingChar: Character?
                if (conflict != null) {
                    // 충돌이 해결된 행: 사용자 결정에 따라 매칭
                    existingChar = when (conflict.resolution) {
                        ConflictResolution.CREATE_NEW -> null // 강제 신규 생성
                        ConflictResolution.UPDATE_EXISTING -> {
                            conflict.selectedExistingId?.let { db.characterDao().getCharacterById(it) }
                        }
                        ConflictResolution.SKIP -> null // 이미 위에서 처리됨
                    }
                } else if (code.isNotBlank()) {
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
                        isPinned = charIsPinned,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existingChar.createdAt
                    ))
                    result.updatedCharacters++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    charId = db.characterDao().insert(Character(
                        name = name, firstName = firstName, lastName = lastName,
                        anotherName = anotherName, novelId = novelId,
                        imagePaths = imagePathsFromExcel ?: "[]", memo = memo, code = newCode, displayOrder = displayOrder,
                        isPinned = charIsPinned, createdAt = createdAt
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
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
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
        val displayOrderColIndex = cols["정렬순서"] ?: -1
        val isTemporaryColIndex = cols["임시배치"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        val allNovels = db.novelDao().getAllNovelsList()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt() ?: continue
                val description = getCellString(row, descColIndex)
                if (description.isBlank()) continue

                val month = parseNumber(getCellString(row, monthColIndex))?.toInt()?.takeIf { it in 1..12 }
                val day = parseNumber(getCellString(row, dayColIndex))?.toInt()?.takeIf { d -> d in 1..31 && isValidDay(month, d) }
                val calendarType = getCellString(row, calendarColIndex).ifBlank { "천개력" }
                val novelTitle = getCellString(row, novelColIndex)
                val novelCode = if (novelCodeColIndex >= 0) getCellString(row, novelCodeColIndex) else ""
                val displayOrder = if (displayOrderColIndex >= 0) parseNumber(getCellString(row, displayOrderColIndex))?.toInt() ?: 0 else 0
                val isTemporary = if (isTemporaryColIndex >= 0) parseBoolean(getCellString(row, isTemporaryColIndex)) else false
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                        novelId = novelId, universeId = universeId,
                        displayOrder = if (displayOrderColIndex >= 0) displayOrder else existingEvent.displayOrder,
                        isTemporary = if (isTemporaryColIndex >= 0) isTemporary else existingEvent.isTemporary,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existingEvent.createdAt
                    ))
                    result.updatedEvents++
                } else {
                    eventId = db.timelineDao().insert(TimelineEvent(
                        year = year, month = month, day = day,
                        calendarType = calendarType, description = description,
                        novelId = novelId, universeId = universeId,
                        displayOrder = displayOrder, isTemporary = isTemporary,
                        createdAt = createdAt
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
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
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
        val createdAtColIndex = cols["생성일"] ?: -1

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
                val day = parseNumber(getCellString(row, dayColIndex))?.toInt()?.takeIf { d -> d in 1..31 && isValidDay(month, d) }
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
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                        month = month, day = day, description = description,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    result.updatedStateChanges++
                } else {
                    db.characterStateChangeDao().insert(CharacterStateChange(
                        characterId = character.id, year = year, month = month, day = day,
                        fieldKey = fieldKey, newValue = newValue, description = description,
                        createdAt = createdAt
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
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
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
        val factionColIndex = cols["세력"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        val allFactions = if (factionColIndex >= 0) db.factionDao().getAllFactions() else emptyList()

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
                val isBidirectional = if (bidirectionalColIndex >= 0) parseBoolean(getCellString(row, bidirectionalColIndex)) else true
                val displayOrder = if (displayOrderColIndex >= 0) parseNumber(getCellString(row, displayOrderColIndex))?.toInt() ?: 0 else 0
                val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
                val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()
                val factionName = if (factionColIndex >= 0) getCellString(row, factionColIndex) else ""
                val factionId = if (factionName.isNotBlank()) allFactions.find { it.name == factionName }?.id else null

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
                        description = description,
                        intensity = if (intensityColIndex >= 0) intensity else existing.intensity,
                        isBidirectional = if (bidirectionalColIndex >= 0) isBidirectional else existing.isBidirectional,
                        displayOrder = if (displayOrderColIndex >= 0) displayOrder else existing.displayOrder,
                        factionId = if (factionColIndex >= 0) factionId else existing.factionId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    result.updatedRelationships++
                } else {
                    db.characterRelationshipDao().insert(CharacterRelationship(
                        characterId1 = char1.id, characterId2 = char2.id,
                        relationshipType = relationshipType, description = description,
                        intensity = intensity, isBidirectional = isBidirectional,
                        displayOrder = displayOrder, factionId = factionId,
                        createdAt = createdAt
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
        val sheet = findSheet(workbook, "관계 변화", result) ?: return
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
        val createdAtColIndex = cols["생성일"] ?: -1

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val char1Name = getCellString(row, char1NameColIndex)
                val char2Name = getCellString(row, char2NameColIndex)
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt() ?: continue
                val month = parseNumber(getCellString(row, monthColIndex))?.toInt()?.takeIf { it in 1..12 }
                val day = parseNumber(getCellString(row, dayColIndex))?.toInt()?.takeIf { d -> d in 1..31 && isValidDay(month, d) }
                val relationshipType = getCellString(row, relTypeColIndex)
                val description = getCellString(row, descColIndex)
                val intensity = parseNumber(getCellString(row, intensityColIndex))?.toInt()?.coerceIn(1, 10) ?: 5
                val isBidirectional = parseBoolean(getCellString(row, bidirectionalColIndex))
                val eventId = if (eventIdColIndex >= 0) parseNumber(getCellString(row, eventIdColIndex))?.toLong() else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                        eventId = if (eventIdColIndex >= 0) eventId else existing.eventId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    result.updatedRelationshipChanges++
                } else {
                    db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(
                        relationshipId = relationship.id,
                        year = year, month = month, day = day,
                        relationshipType = relationshipType, description = description,
                        intensity = intensity, isBidirectional = isBidirectional,
                        eventId = eventId, createdAt = createdAt
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
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
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
        val createdAtColIndex = cols["생성일"] ?: -1

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
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                        isUsed = effectiveIsUsed, usedByCharacterId = usedByCharacterId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    result.updatedNameBank++
                } else {
                    val newEntry = NameBankEntry(
                        name = name, gender = gender, origin = origin, notes = notes,
                        isUsed = effectiveIsUsed, usedByCharacterId = usedByCharacterId,
                        createdAt = createdAt
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

    // ── 필드 템플릿 가져오기 ──

    private suspend fun importUserPresetTemplates(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = userPresetTemplateSpec()
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val fieldsJsonColIndex = cols["설정(JSON)"] ?: 2
        val builtInColIndex = cols["기본제공"] ?: 3
        val createdAtColIndex = cols["생성일"] ?: -1
        val updatedAtColIndex = cols["수정일"] ?: -1

        val existingTemplates = db.userPresetTemplateDao().getAllTemplatesList()
        val existingByName = existingTemplates.associateBy { it.name }.toMutableMap()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val description = getCellString(row, descColIndex)
                val fieldsJson = getCellString(row, fieldsJsonColIndex).ifBlank { "[]" }
                val isBuiltIn = parseBoolean(getCellString(row, builtInColIndex))
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()
                val updatedAt = if (updatedAtColIndex >= 0) parseNumber(getCellString(row, updatedAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                val existing = existingByName[name]
                if (existing != null) {
                    db.userPresetTemplateDao().update(existing.copy(
                        description = description, fieldsJson = fieldsJson,
                        isBuiltIn = isBuiltIn, updatedAt = updatedAt
                    ))
                    result.updatedPresetTemplates++
                } else {
                    val newTemplate = UserPresetTemplate(
                        name = name, description = description,
                        fieldsJson = fieldsJson, isBuiltIn = isBuiltIn,
                        createdAt = createdAt, updatedAt = updatedAt
                    )
                    val newId = db.userPresetTemplateDao().insert(newTemplate)
                    existingByName[name] = newTemplate.copy(id = newId)
                    result.newPresetTemplates++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 템플릿 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "필드 템플릿", sheet.lastRowNum, totalRows)
    }

    // ── 검색 프리셋 가져오기 ──

    private suspend fun importSearchPresets(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = searchPresetSpec()
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val queryColIndex = cols["검색어"] ?: 1
        val filtersColIndex = cols["필터(JSON)"] ?: 2
        val sortModeColIndex = cols["정렬모드"] ?: 3
        val isDefaultColIndex = cols["기본값"] ?: 4
        val createdAtColIndex = cols["생성일"] ?: -1
        val updatedAtColIndex = cols["수정일"] ?: -1

        val existingPresets = db.searchPresetDao().getAllPresetsList()
        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val query = getCellString(row, queryColIndex)
                val filtersJson = getCellString(row, filtersColIndex).ifBlank { "{}" }
                val sortMode = getCellString(row, sortModeColIndex).ifBlank { SearchPreset.SORT_RELEVANCE }
                val isDefault = parseBoolean(getCellString(row, isDefaultColIndex))
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()
                val updatedAt = if (updatedAtColIndex >= 0) parseNumber(getCellString(row, updatedAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                val existing = existingByName[name]
                if (existing != null) {
                    db.searchPresetDao().update(existing.copy(
                        query = query, filtersJson = filtersJson,
                        sortMode = sortMode, isDefault = isDefault,
                        updatedAt = updatedAt
                    ))
                    result.updatedSearchPresets++
                } else {
                    val newPreset = SearchPreset(
                        name = name, query = query, filtersJson = filtersJson,
                        sortMode = sortMode, isDefault = isDefault,
                        createdAt = createdAt, updatedAt = updatedAt
                    )
                    val newId = db.searchPresetDao().insert(newPreset)
                    existingByName[name] = newPreset.copy(id = newId)
                    result.newSearchPresets++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("검색 프리셋 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "검색 프리셋", sheet.lastRowNum, totalRows)
    }

    // ── 앱 설정 가져오기 ──

    private suspend fun importAppSettings(workbook: Workbook, result: ImportResult) {
        val spec = appSettingsSpec()
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val keyColIndex = cols["설정키"] ?: 0
        val valueColIndex = cols["설정값"] ?: 1

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val key = getCellString(row, keyColIndex)
                if (key.isBlank()) continue
                val value = getCellString(row, valueColIndex)

                when (key) {
                    "theme_mode" -> {
                        val ctx = appContext ?: continue
                        val mode = parseNumber(value)?.toInt() ?: 0
                        val validMode = mode.coerceIn(0, 2)
                        com.novelcharacter.app.util.ThemeHelper.saveTheme(ctx, validMode)
                        result.restoredSettings++
                    }
                }
            } catch (e: Exception) {
                result.errors.add("앱 설정 행 $i: ${e.message}")
            }
        }
    }

    // ── 세력 가져오기 ──

    private suspend fun importFactions(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = factionSpec()
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val universeNameColIndex = cols["세계관"] ?: -1
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        val descColIndex = cols["설명"] ?: -1
        val colorColIndex = cols["색상"] ?: -1
        val autoRelTypeColIndex = cols["자동관계유형"] ?: -1
        val autoRelIntensityColIndex = cols["자동관계강도"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        val codesSeen = mutableMapOf<String, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val universeName = if (universeNameColIndex >= 0) getCellString(row, universeNameColIndex) else ""
                val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
                val description = if (descColIndex >= 0) getCellString(row, descColIndex) else ""
                val color = if (colorColIndex >= 0) getCellString(row, colorColIndex).ifBlank { "#2196F3" } else "#2196F3"
                val autoRelationType = if (autoRelTypeColIndex >= 0) getCellString(row, autoRelTypeColIndex) else ""
                if (autoRelationType.isBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 행 $i: 자동관계유형이 비어 있음")
                    continue
                }
                val autoRelationIntensity = if (autoRelIntensityColIndex >= 0) parseNumber(getCellString(row, autoRelIntensityColIndex))?.toInt()?.coerceIn(1, 10) ?: 5 else 5
                val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
                val displayOrder = if (orderColIndex >= 0) parseNumber(getCellString(row, orderColIndex))?.toInt() ?: 0 else 0
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                // Resolve universe (코드 우선 매칭 — 작품 가져오기와 동일 패턴)
                val universeId: Long
                val resolvedUniverse = (if (universeCode.isNotBlank()) db.universeDao().getUniverseByCode(universeCode) else null)
                    ?: (if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName) else null)
                if (resolvedUniverse != null) {
                    universeId = resolvedUniverse.id
                } else if (universeName.isNotBlank() || universeCode.isNotBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 행 $i: 세계관 '$universeName'을(를) 찾을 수 없음")
                    continue
                } else {
                    result.skippedRows++
                    result.errors.add("세력 행 $i: 세계관이 지정되지 않음")
                    continue
                }

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("세력: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Code-first matching
                val existing: Faction?
                val matchedByName: Boolean
                if (code.isNotBlank()) {
                    val byCode = db.factionDao().getByCode(code)
                    existing = byCode
                    matchedByName = false
                } else {
                    existing = db.factionDao().getByNameAndUniverse(name, universeId)
                    matchedByName = existing != null
                    if (matchedByName) {
                        result.nameBasedMappings++
                        result.warnings.add("세력 행 $i: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    db.factionDao().update(existing.copy(
                        name = name,
                        universeId = universeId,
                        description = description,
                        color = color,
                        autoRelationType = autoRelationType,
                        autoRelationIntensity = autoRelationIntensity,
                        displayOrder = displayOrder,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    result.updatedFactions++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    db.factionDao().insert(Faction(
                        name = name,
                        universeId = universeId,
                        description = description,
                        color = color,
                        autoRelationType = autoRelationType,
                        autoRelationIntensity = autoRelationIntensity,
                        code = newCode,
                        displayOrder = displayOrder,
                        createdAt = createdAt
                    ))
                    result.newFactions++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세력 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "세력", sheet.lastRowNum, totalRows)
    }

    // ── 세력 소속 가져오기 ──

    private suspend fun importFactionMemberships(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = factionMembershipSpec()
        val sheet = findSheet(workbook, spec.sheetName, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return

        val cols = resolveHeaderColumns(headerRow)
        val factionNameColIndex = cols[spec.firstColumnHeader] ?: cols["세력"] ?: 0
        val charNameColIndex = cols["캐릭터"] ?: -1
        val joinYearColIndex = cols["가입연도"] ?: -1
        val leaveYearColIndex = cols["탈퇴연도"] ?: -1
        val leaveTypeColIndex = cols["탈퇴유형"] ?: -1
        val departedRelTypeColIndex = cols["탈퇴후관계유형"] ?: -1
        val departedIntensityColIndex = cols["탈퇴후강도"] ?: -1
        val factionCodeColIndex = cols["세력코드"] ?: -1
        val charCodeColIndex = cols["캐릭터코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        if (charNameColIndex < 0) {
            result.errors.add("세력 소속 시트: '캐릭터' 컬럼을 찾을 수 없음")
            return
        }

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val factionName = getCellString(row, factionNameColIndex)
                if (factionName.isBlank()) continue
                val charName = getCellString(row, charNameColIndex)
                if (charName.isBlank()) continue

                val factionCode = if (factionCodeColIndex >= 0) getCellString(row, factionCodeColIndex) else ""
                val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""

                // Resolve faction
                val faction: Faction? = if (factionCode.isNotBlank()) {
                    db.factionDao().getByCode(factionCode)
                } else {
                    // Try all universes to find faction by name
                    val allFactions = db.factionDao().getAllFactionsList()
                    allFactions.find { it.name == factionName }
                }
                if (faction == null) {
                    result.skippedRows++
                    result.errors.add("세력 소속 행 $i: 세력 '$factionName'을(를) 찾을 수 없음")
                    continue
                }

                // Resolve character
                val character: com.novelcharacter.app.data.model.Character? = if (charCode.isNotBlank()) {
                    db.characterDao().getCharacterByCode(charCode)
                } else {
                    db.characterDao().getCharacterByName(charName)
                }
                if (character == null) {
                    result.skippedRows++
                    result.errors.add("세력 소속 행 $i: 캐릭터 '$charName'을(를) 찾을 수 없음")
                    continue
                }

                val joinYear = if (joinYearColIndex >= 0) parseNumber(getCellString(row, joinYearColIndex))?.toInt() else null
                val leaveYear = if (leaveYearColIndex >= 0) parseNumber(getCellString(row, leaveYearColIndex))?.toInt() else null
                val leaveTypeRaw = if (leaveTypeColIndex >= 0) getCellString(row, leaveTypeColIndex) else ""
                val leaveType = when (leaveTypeRaw.trim()) {
                    "순수제거", "removed" -> FactionMembership.LEAVE_REMOVED
                    "설정상탈퇴", "departed" -> FactionMembership.LEAVE_DEPARTED
                    else -> null
                }
                val departedRelationType = if (departedRelTypeColIndex >= 0) getCellString(row, departedRelTypeColIndex).ifBlank { null } else null
                val departedIntensity = if (departedIntensityColIndex >= 0) parseNumber(getCellString(row, departedIntensityColIndex))?.toInt()?.coerceIn(1, 10) else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                // Check if existing active membership exists
                val existingMembership = db.factionMembershipDao().getActiveMembership(faction.id, character.id)

                if (existingMembership != null && leaveType == null) {
                    // Update existing active membership
                    db.factionMembershipDao().update(existingMembership.copy(
                        joinYear = joinYear ?: existingMembership.joinYear,
                        leaveYear = leaveYear,
                        leaveType = leaveType,
                        departedRelationType = departedRelationType,
                        departedIntensity = departedIntensity,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existingMembership.createdAt
                    ))
                    result.updatedFactionMemberships++
                } else {
                    // Insert new membership
                    val membershipId = db.factionMembershipDao().insert(FactionMembership(
                        factionId = faction.id,
                        characterId = character.id,
                        joinYear = joinYear,
                        leaveYear = leaveYear,
                        leaveType = leaveType,
                        departedRelationType = departedRelationType,
                        departedIntensity = departedIntensity,
                        createdAt = createdAt
                    ))

                    // If this is an active membership (no leaveType), trigger auto-relationships
                    if (leaveType == null && membershipId > 0) {
                        val activeMembers = db.factionMembershipDao().getActiveMembershipsByFaction(faction.id)
                        val otherCharIds = activeMembers
                            .filter { it.characterId != character.id }
                            .map { it.characterId }

                        if (otherCharIds.isNotEmpty()) {
                            val existingRels = db.characterRelationshipDao().getAllRelationships()
                                .filter { it.factionId == faction.id }
                            val existingPairs = existingRels.map { setOf(it.characterId1, it.characterId2) }.toSet()

                            val newRelationships = otherCharIds.mapNotNull { otherCharId ->
                                val pair = setOf(character.id, otherCharId)
                                if (pair in existingPairs) return@mapNotNull null
                                val (c1, c2) = if (character.id < otherCharId)
                                    character.id to otherCharId else otherCharId to character.id
                                CharacterRelationship(
                                    characterId1 = c1,
                                    characterId2 = c2,
                                    relationshipType = faction.autoRelationType,
                                    intensity = faction.autoRelationIntensity,
                                    isBidirectional = true,
                                    factionId = faction.id
                                )
                            }
                            if (newRelationships.isNotEmpty()) {
                                db.characterRelationshipDao().insertAll(newRelationships)
                            }
                        }
                    }

                    result.newFactionMemberships++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세력 소속 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "세력 소속", sheet.lastRowNum, totalRows)
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

    /** 월에 맞는 유효한 일수인지 검증 (월이 null이면 1..31 범위만 체크) */
    private fun isValidDay(month: Int?, day: Int): Boolean {
        if (month == null) return day in 1..31
        val maxDay = when (month) {
            2 -> 29  // 윤년 가능성 허용
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..maxDay
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
            val sheetName = row.sheet?.sheetName ?: "?"
            truncatedDetails.add("${sheetName} 행${row.rowNum + 1} 열${cellIndex + 1}")
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
