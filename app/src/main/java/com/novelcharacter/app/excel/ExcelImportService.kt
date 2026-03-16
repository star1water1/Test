package com.novelcharacter.app.excel

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
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
    var newNameBank: Int = 0,
    var updatedNameBank: Int = 0,
    var skippedRows: Int = 0,
    val errors: MutableList<String> = mutableListOf()
)

class ExcelImportService(private val db: AppDatabase) {

    private val novelIdCache = mutableMapOf<Pair<String, Long?>, Long?>()

    suspend fun importAll(
        workbook: Workbook,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult {
        val result = ImportResult()
        novelIdCache.clear()
        processedRowsSoFar = 0

        // Count total rows for progress
        val totalRows = countTotalRows(workbook)

        db.withTransaction {
            importUniverses(workbook, result, onProgress, totalRows)
            importNovels(workbook, result, onProgress, totalRows)
            importFieldDefinitions(workbook, result, onProgress, totalRows)
            importCharacterSheets(workbook, result, onProgress, totalRows)
            importUnclassifiedCharacters(workbook, result, onProgress, totalRows)
            importTimeline(workbook, result, onProgress, totalRows)
            importStateChanges(workbook, result, onProgress, totalRows)
            importRelationships(workbook, result, onProgress, totalRows)
            importNameBank(workbook, result, onProgress, totalRows)
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

    // ── 세계관 가져오기 ──

    private suspend fun importUniverses(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = universeSpec()
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != spec.firstColumnHeader) return

        val codeColIndex = spec.findColumn(headerRow, "코드")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, 0)
                if (name.isBlank()) continue

                val description = getCellString(row, 1)
                val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""

                // Code-first matching, then name fallback
                val existing = if (code.isNotBlank()) {
                    db.universeDao().getUniverseByCode(code)
                } else null
                    ?: db.universeDao().getUniverseByName(name)

                if (existing != null) {
                    db.universeDao().update(existing.copy(name = name, description = description))
                    result.updatedUniverses++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    db.universeDao().insert(Universe(name = name, description = description, code = newCode))
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
        if (getCellString(headerRow, 0) != spec.firstColumnHeader) return

        val codeColIndex = spec.findColumn(headerRow, "코드")
        val universeCodeColIndex = spec.findColumn(headerRow, "세계관코드")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val title = getCellString(row, 0)
                if (title.isBlank()) continue

                val description = getCellString(row, 1)
                val universeName = getCellString(row, 2)
                val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
                val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""

                // Resolve universe: code-first, then name
                val universeId = if (universeCode.isNotBlank()) {
                    db.universeDao().getUniverseByCode(universeCode)?.id
                } else null
                    ?: if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null

                // Resolve novel: code-first, then title+universe
                val existing = if (code.isNotBlank()) {
                    db.novelDao().getNovelByCode(code)
                } else null
                    ?: if (universeId != null) {
                        db.novelDao().getNovelByTitleAndUniverse(title, universeId)
                    } else {
                        db.novelDao().getNovelByTitleNoUniverse(title)
                    }

                if (existing != null) {
                    db.novelDao().update(existing.copy(title = title, description = description, universeId = universeId))
                    result.updatedNovels++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    db.novelDao().insert(Novel(title = title, description = description, universeId = universeId, code = newCode))
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
        if (getCellString(headerRow, 0) != spec.firstColumnHeader) return

        val universeCodeColIndex = spec.findColumn(headerRow, "세계관코드")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val universeName = getCellString(row, 0)
                if (universeName.isBlank()) continue

                val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
                val universe = if (universeCode.isNotBlank()) {
                    db.universeDao().getUniverseByCode(universeCode)
                } else null
                    ?: db.universeDao().getUniverseByName(universeName)
                    ?: continue

                val key = getCellString(row, 1)
                if (key.isBlank()) continue

                val name = getCellString(row, 2)
                val type = getCellString(row, 3)
                val config = getCellString(row, 4).ifBlank { "{}" }
                val groupName = getCellString(row, 5).ifBlank { "기본 정보" }
                val displayOrder = getCellString(row, 6).toDoubleOrNull()?.toInt() ?: 0
                val isRequired = getCellString(row, 7) == "Y"

                val existing = db.fieldDefinitionDao().getFieldByKey(universe.id, key)
                if (existing != null) {
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
            if (getCellString(headerRow, 0) != "이름") continue

            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            importCharacterRows(sheet, headerRow, universe, fields, result, onProgress, totalRows)
        }
    }

    // ── 미분류 캐릭터 가져오기 ──

    private suspend fun importUnclassifiedCharacters(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val sheet = workbook.getSheet(UNCLASSIFIED_SHEET_NAME) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "이름") return

        importCharacterRows(sheet, headerRow, null, emptyList(), result, onProgress, totalRows)
    }

    /**
     * Shared character import logic for both universe and unclassified sheets.
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
        val spec = characterSpec(fields, emptyList())
        val columnFieldMap = buildColumnFieldMap(headerRow, fields)
        val imageColIndex = spec.findColumn(headerRow, "이미지경로")
        val novelColIndex = spec.findColumn(headerRow, "작품")
        val memoColIndex = spec.findColumn(headerRow, "메모")
        val tagsColIndex = spec.findColumn(headerRow, "태그")
        val codeColIndex = spec.findColumn(headerRow, "코드")
        val novelCodeColIndex = spec.findColumn(headerRow, "작품코드")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, 0)
                if (name.isBlank()) continue

                val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
                val novelCode = if (novelCodeColIndex >= 0) getCellString(row, novelCodeColIndex) else ""
                val novelTitle = if (novelColIndex >= 0) getCellString(row, novelColIndex) else ""

                // Resolve novel: code-first, then title
                val novelId = if (novelCode.isNotBlank()) {
                    db.novelDao().getNovelByCode(novelCode)?.id
                } else null
                    ?: if (universe != null) {
                        resolveNovelId(novelTitle, universe.id)
                    } else {
                        resolveNovelIdNoUniverse(novelTitle)
                    }

                val imagePaths = if (imageColIndex >= 0) getCellString(row, imageColIndex) else "[]"
                val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""

                // Code-first matching, then name+novel fallback
                val existingChar = if (code.isNotBlank()) {
                    db.characterDao().getCharacterByCode(code)
                } else null
                    ?: if (novelId != null) {
                        db.characterDao().getCharacterByNameAndNovel(name, novelId)
                    } else null

                val charId: Long
                if (existingChar != null) {
                    charId = existingChar.id
                    db.characterDao().update(existingChar.copy(
                        name = name,
                        novelId = novelId,
                        imagePaths = imagePaths,
                        memo = memo,
                        updatedAt = System.currentTimeMillis()
                    ))
                    result.updatedCharacters++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    charId = db.characterDao().insert(Character(
                        name = name, novelId = novelId,
                        imagePaths = imagePaths, memo = memo, code = newCode
                    ))
                    result.newCharacters++
                }

                // 태그 가져오기
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

                // 동적 필드 값 가져오기
                for ((colIndex, field) in columnFieldMap) {
                    val value = getCellString(row, colIndex)
                    if (value.isNotBlank()) {
                        val existingValue = db.characterFieldValueDao().getValue(charId, field.id)
                        if (existingValue != null) {
                            db.characterFieldValueDao().update(existingValue.copy(value = value))
                        } else {
                            db.characterFieldValueDao().insert(CharacterFieldValue(
                                characterId = charId, fieldDefinitionId = field.id, value = value
                            ))
                        }
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
        if (getCellString(headerRow, 0) != spec.firstColumnHeader) return

        val allNovels = db.novelDao().getAllNovelsList()
        val novelCodeColIndex = spec.findColumn(headerRow, "관련작품코드")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val yearStr = getCellString(row, 0)
                val year = yearStr.toDoubleOrNull()?.toInt() ?: continue
                val description = getCellString(row, 4)
                if (description.isBlank()) continue

                val month = getCellString(row, 1).toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                val day = getCellString(row, 2).toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                val calendarType = getCellString(row, 3).ifBlank { "천개력" }
                val novelTitle = getCellString(row, 5)
                val novelCode = if (novelCodeColIndex >= 0) getCellString(row, novelCodeColIndex) else ""

                val novelId = if (novelCode.isNotBlank()) {
                    db.novelDao().getNovelByCode(novelCode)?.id
                } else null
                    ?: if (novelTitle.isNotBlank()) allNovels.find { it.title == novelTitle }?.id else null

                val existingEvent = if (novelId != null) {
                    db.timelineDao().getEventByNaturalKey(year, description, novelId)
                } else {
                    db.timelineDao().getEventByNaturalKeyNoNovel(year, description)
                }

                val eventId: Long
                if (existingEvent != null) {
                    eventId = existingEvent.id
                    db.timelineDao().update(existingEvent.copy(
                        month = month, day = day, calendarType = calendarType
                    ))
                    result.updatedEvents++
                } else {
                    eventId = db.timelineDao().insert(TimelineEvent(
                        year = year, month = month, day = day,
                        calendarType = calendarType, description = description, novelId = novelId
                    ))
                    result.newEvents++
                }

                val characterNames = getCellString(row, 6)
                if (characterNames.isNotBlank()) {
                    db.timelineDao().deleteCrossRefsByEvent(eventId)
                    val names = splitCsv(characterNames)
                    for (charName in names) {
                        val character = findCharacterByName(charName, novelId)
                        if (character != null) {
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
        if (getCellString(headerRow, 0) != spec.firstColumnHeader) return

        val allNovels = db.novelDao().getAllNovelsList()
        val charCodeColIndex = spec.findColumn(headerRow, "캐릭터코드")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val charName = getCellString(row, 0)
                if (charName.isBlank()) continue

                val novelTitle = getCellString(row, 1)
                val yearStr = getCellString(row, 2)
                val year = yearStr.toDoubleOrNull()?.toInt() ?: continue

                val month = getCellString(row, 3).toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                val day = getCellString(row, 4).toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                val fieldKey = getCellString(row, 5)
                if (fieldKey.isBlank()) continue
                val newValue = getCellString(row, 6)
                val description = getCellString(row, 7)
                val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""

                // Resolve character: code-first, then name
                val character = if (charCode.isNotBlank()) {
                    db.characterDao().getCharacterByCode(charCode)
                } else null
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
        if (getCellString(headerRow, 0) != spec.firstColumnHeader) return

        val char1CodeColIndex = spec.findColumn(headerRow, "캐릭터1코드")
        val char2CodeColIndex = spec.findColumn(headerRow, "캐릭터2코드")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val char1Name = getCellString(row, 0)
                val char2Name = getCellString(row, 1)
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val relationshipType = getCellString(row, 2)
                if (relationshipType.isBlank()) continue
                val description = getCellString(row, 3)
                val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
                val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""

                val char1 = if (char1Code.isNotBlank()) {
                    db.characterDao().getCharacterByCode(char1Code)
                } else null
                    ?: findCharacterByName(char1Name, null) ?: continue

                val char2 = if (char2Code.isNotBlank()) {
                    db.characterDao().getCharacterByCode(char2Code)
                } else null
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
                    db.characterRelationshipDao().update(existing.copy(description = description))
                    result.updatedRelationships++
                } else {
                    db.characterRelationshipDao().insert(CharacterRelationship(
                        characterId1 = char1.id, characterId2 = char2.id,
                        relationshipType = relationshipType, description = description
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

    // ── 이름 은행 가져오기 ──

    private suspend fun importNameBank(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = nameBankSpec()
        val sheet = workbook.getSheet(spec.sheetName) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != spec.firstColumnHeader) return

        val charCodeColIndex = spec.findColumn(headerRow, "사용캐릭터코드")
        val existingNames = db.nameBankDao().getAllNamesList().toMutableList()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, 0)
                if (name.isBlank()) continue

                val gender = getCellString(row, 1)
                val origin = getCellString(row, 2)
                val notes = getCellString(row, 3)
                val isUsed = getCellString(row, 4) == "Y"
                val usedByCharName = getCellString(row, 5)
                val usedByCharCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""

                val usedByCharacterId = if (usedByCharCode.isNotBlank()) {
                    db.characterDao().getCharacterByCode(usedByCharCode)?.id
                } else null
                    ?: if (usedByCharName.isNotBlank()) findCharacterByName(usedByCharName, null)?.id else null

                val existing = existingNames.find { it.name == name && it.gender == gender }

                if (existing != null) {
                    db.nameBankDao().update(existing.copy(
                        origin = origin, notes = notes,
                        isUsed = isUsed, usedByCharacterId = usedByCharacterId
                    ))
                    result.updatedNameBank++
                } else {
                    val newEntry = NameBankEntry(
                        name = name, gender = gender, origin = origin, notes = notes,
                        isUsed = isUsed, usedByCharacterId = usedByCharacterId
                    )
                    val newId = db.nameBankDao().insert(newEntry)
                    existingNames.add(newEntry.copy(id = newId))
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
        if (universeName.length > 31) {
            val truncated = universeName.take(31)
            workbook.getSheet(truncated)?.let { return it }
        }
        for (idx in 0 until workbook.numberOfSheets) {
            val sheetName = workbook.getSheetName(idx)
            if (sheetName in reservedNames) continue
            if (universeName.startsWith(sheetName.replace(Regex("\\(\\d+\\)$"), ""))) {
                return workbook.getSheetAt(idx)
            }
        }
        return null
    }

    private fun buildColumnFieldMap(headerRow: Row, fields: List<FieldDefinition>): Map<Int, FieldDefinition> {
        val map = mutableMapOf<Int, FieldDefinition>()
        val lastCol = headerRow.lastCellNum.toInt()
        val skipHeaders = setOf("작품", "이미지경로", "메모", "태그", "코드", "작품코드")
        for (col in 1 until lastCol) {
            val headerName = getCellString(headerRow, col)
            if (headerName in skipHeaders) continue
            val field = fields.find { it.name == headerName }
            if (field != null) {
                map[col] = field
            }
        }
        return map
    }

    private suspend fun resolveNovelId(novelTitle: String, universeId: Long): Long? {
        if (novelTitle.isBlank()) return null
        val cacheKey = novelTitle to universeId as Long?
        novelIdCache[cacheKey]?.let { return it }
        val existing = db.novelDao().getNovelByTitleAndUniverse(novelTitle, universeId)
        if (existing != null) {
            novelIdCache[cacheKey] = existing.id
            return existing.id
        }
        val newId = db.novelDao().insert(Novel(title = novelTitle, universeId = universeId))
        novelIdCache[cacheKey] = newId
        return newId
    }

    private suspend fun resolveNovelIdNoUniverse(novelTitle: String): Long? {
        if (novelTitle.isBlank()) return null
        val cacheKey = novelTitle to null
        novelIdCache[cacheKey]?.let { return it }
        val existing = db.novelDao().getNovelByTitleNoUniverse(novelTitle)
        if (existing != null) {
            novelIdCache[cacheKey] = existing.id
            return existing.id
        }
        val newId = db.novelDao().insert(Novel(title = novelTitle))
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

    private fun getCellString(row: Row, cellIndex: Int, maxLength: Int = MAX_FIELD_LENGTH): String {
        val cell = row.getCell(cellIndex) ?: return ""
        val raw = when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue?.trim() ?: ""
            CellType.NUMERIC -> {
                val value = cell.numericCellValue
                if (value == value.toLong().toDouble()) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue?.trim() ?: ""
                } catch (e: Exception) {
                    cell.numericCellValue.toString()
                }
            }
            else -> ""
        }
        return if (raw.length > maxLength) raw.substring(0, maxLength) else raw
    }

    companion object {
        private const val MAX_FIELD_LENGTH = 10000
        private const val GUIDE_SHEET_NAME = "사용 안내"
        private const val UNCLASSIFIED_SHEET_NAME = "미분류 캐릭터"
    }
}
