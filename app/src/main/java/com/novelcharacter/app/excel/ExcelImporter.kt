package com.novelcharacter.app.excel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory

class ExcelImporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val importScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private var importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null

    /**
     * registerForActivityResult는 Fragment가 STARTED 상태 이전에 호출되어야 합니다.
     * onViewCreated 등에서 미리 호출하세요.
     */
    fun registerLauncher(fragment: Fragment) {
        importLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let { importFromExcel(it) }
        }
    }

    fun cleanup() {
        importScope.cancel()
        importLauncher = null
    }

    fun showImportDialog(fragment: Fragment) {
        val launcher = importLauncher
        if (launcher != null) {
            launcher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream"
            ))
        } else {
            Toast.makeText(context, com.novelcharacter.app.R.string.importer_restart, Toast.LENGTH_SHORT).show()
        }
    }

    private data class ImportResult(
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

    fun importFromExcel(uri: Uri) {
        importScope.launch {
            var workbook: org.apache.poi.ss.usermodel.Workbook? = null
            try {
                // ZIP bomb / oversized file protection
                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                if (fileSize > MAX_IMPORT_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "파일 크기가 너무 큽니다 (최대 50MB)", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("파일을 열 수 없습니다")

                workbook = inputStream.use { WorkbookFactory.create(it) }
                val result = ImportResult()

                novelIdCache.clear()
                db.withTransaction {
                    // 의존성 순서대로 가져오기
                    importUniverses(workbook, result)
                    importNovels(workbook, result)
                    importFieldDefinitions(workbook, result)
                    importCharacterSheets(workbook, result)
                    importUnclassifiedCharacters(workbook, result)
                    importTimeline(workbook, result)
                    importStateChanges(workbook, result)
                    importRelationships(workbook, result)
                    importNameBank(workbook, result)
                }

                val message = buildResultMessage(result)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelImporter", "Import failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, com.novelcharacter.app.R.string.import_failed_retry, Toast.LENGTH_LONG).show()
                }
            } finally {
                try { workbook?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun buildResultMessage(result: ImportResult): String {
        val parts = mutableListOf<String>()
        val charTotal = result.newCharacters + result.updatedCharacters
        val eventTotal = result.newEvents + result.updatedEvents

        if (result.newUniverses > 0 || result.updatedUniverses > 0)
            parts.add("세계관 ${result.newUniverses + result.updatedUniverses}개")
        if (result.newNovels > 0 || result.updatedNovels > 0)
            parts.add("작품 ${result.newNovels + result.updatedNovels}개")
        if (result.newFields > 0 || result.updatedFields > 0)
            parts.add("필드 ${result.newFields + result.updatedFields}개")
        if (charTotal > 0) {
            val detail = if (result.updatedCharacters > 0) " (업데이트 ${result.updatedCharacters}명)" else ""
            parts.add("캐릭터 ${charTotal}명$detail")
        }
        if (eventTotal > 0) {
            val detail = if (result.updatedEvents > 0) " (업데이트 ${result.updatedEvents}개)" else ""
            parts.add("사건 ${eventTotal}개$detail")
        }
        val scTotal = result.newStateChanges + result.updatedStateChanges
        if (scTotal > 0) {
            val detail = if (result.updatedStateChanges > 0) " (업데이트 ${result.updatedStateChanges}개)" else ""
            parts.add("상태변화 ${scTotal}개$detail")
        }
        val relTotal = result.newRelationships + result.updatedRelationships
        if (relTotal > 0) parts.add("관계 ${relTotal}개")
        val nbTotal = result.newNameBank + result.updatedNameBank
        if (nbTotal > 0) parts.add("이름 ${nbTotal}개")
        if (result.skippedRows > 0)
            parts.add("오류 ${result.skippedRows}건 건너뜀")

        return if (parts.isEmpty()) "가져오기 완료: 데이터 없음"
        else "가져오기 완료: ${parts.joinToString(", ")}"
    }

    // ── 세계관 가져오기 ──

    private suspend fun importUniverses(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("세계관") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "이름") return

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, 0)
                if (name.isBlank()) continue

                val description = getCellString(row, 1)
                val existing = db.universeDao().getUniverseByName(name)
                if (existing != null) {
                    db.universeDao().update(existing.copy(description = description))
                    result.updatedUniverses++
                } else {
                    db.universeDao().insert(Universe(name = name, description = description))
                    result.newUniverses++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세계관 행 $i: ${e.message}")
            }
        }
    }

    // ── 작품 가져오기 ──

    private suspend fun importNovels(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("작품") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "제목") return

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val title = getCellString(row, 0)
                if (title.isBlank()) continue

                val description = getCellString(row, 1)
                val universeName = getCellString(row, 2)
                val universeId = if (universeName.isNotBlank()) {
                    db.universeDao().getUniverseByName(universeName)?.id
                } else null

                val existing = if (universeId != null) {
                    db.novelDao().getNovelByTitleAndUniverse(title, universeId)
                } else {
                    db.novelDao().getNovelByTitleNoUniverse(title)
                }

                if (existing != null) {
                    db.novelDao().update(existing.copy(description = description, universeId = universeId))
                    result.updatedNovels++
                } else {
                    db.novelDao().insert(Novel(title = title, description = description, universeId = universeId))
                    result.newNovels++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("작품 행 $i: ${e.message}")
            }
        }
    }

    // ── 필드 정의 가져오기 ──

    private suspend fun importFieldDefinitions(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("필드 정의") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "세계관") return

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val universeName = getCellString(row, 0)
                if (universeName.isBlank()) continue

                val universe = db.universeDao().getUniverseByName(universeName) ?: continue
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
                        name = name,
                        type = type,
                        config = config,
                        groupName = groupName,
                        displayOrder = displayOrder,
                        isRequired = isRequired
                    ))
                    result.updatedFields++
                } else {
                    db.fieldDefinitionDao().insert(FieldDefinition(
                        universeId = universe.id,
                        key = key,
                        name = name,
                        type = type,
                        config = config,
                        groupName = groupName,
                        displayOrder = displayOrder,
                        isRequired = isRequired
                    ))
                    result.newFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 정의 행 $i: ${e.message}")
            }
        }
    }

    // ── 세계관별 캐릭터 시트 가져오기 ──

    private suspend fun importCharacterSheets(workbook: Workbook, result: ImportResult) {
        val universes = db.universeDao().getAllUniversesList()
        val reservedNames = setOf("사용 안내", "세계관", "작품", "필드 정의", "미분류 캐릭터", "사건 연표", "캐릭터 상태변화", "캐릭터 관계", "이름 은행")

        for (universe in universes) {
            // 시트 찾기: 세계관 이름과 일치하는 시트
            val sheet = findSheetForUniverse(workbook, universe.name, reservedNames) ?: continue
            val headerRow = sheet.getRow(0) ?: continue
            if (getCellString(headerRow, 0) != "이름") continue

            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)

            // 헤더 기반 필드 매핑: 컬럼명 → 필드 정의 매칭
            val columnFieldMap = buildColumnFieldMap(headerRow, fields)

            // 특수 컬럼 위치 찾기
            val imageColIndex = findColumnIndex(headerRow, "이미지경로")
            val novelColIndex = findColumnIndex(headerRow, "작품")
            val memoColIndex = findColumnIndex(headerRow, "메모")
            val tagsColIndex = findColumnIndex(headerRow, "태그")

            for (i in 1..sheet.lastRowNum) {
                try {
                    val row = sheet.getRow(i) ?: continue
                    val name = getCellString(row, 0)
                    if (name.isBlank()) continue

                    val novelTitle = if (novelColIndex >= 0) getCellString(row, novelColIndex) else ""
                    val novelId = resolveNovelId(novelTitle, universe.id)
                    val imagePaths = if (imageColIndex >= 0) getCellString(row, imageColIndex) else "[]"
                    val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""

                    // 기존 캐릭터 찾기 (덮어쓰기)
                    val existingChar = if (novelId != null) {
                        db.characterDao().getCharacterByNameAndNovel(name, novelId)
                    } else null

                    val charId: Long
                    if (existingChar != null) {
                        charId = existingChar.id
                        db.characterDao().update(existingChar.copy(
                            imagePaths = imagePaths,
                            memo = memo,
                            updatedAt = System.currentTimeMillis()
                        ))
                        result.updatedCharacters++
                    } else {
                        charId = db.characterDao().insert(Character(
                            name = name,
                            novelId = novelId,
                            imagePaths = imagePaths,
                            memo = memo
                        ))
                        result.newCharacters++
                    }

                    // 태그 가져오기
                    if (tagsColIndex >= 0) {
                        val tagsStr = getCellString(row, tagsColIndex)
                        if (tagsStr.isNotBlank()) {
                            db.characterTagDao().deleteAllByCharacter(charId)
                            val tags = tagsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
                            tags.forEach { tag ->
                                db.characterTagDao().insert(CharacterTag(characterId = charId, tag = tag))
                            }
                        }
                    }

                    // 동적 필드 값 가져오기 (헤더 기반 매핑)
                    for ((colIndex, field) in columnFieldMap) {
                        val value = getCellString(row, colIndex)
                        if (value.isNotBlank()) {
                            val existingValue = db.characterFieldValueDao().getValue(charId, field.id)
                            if (existingValue != null) {
                                db.characterFieldValueDao().update(existingValue.copy(value = value))
                            } else {
                                db.characterFieldValueDao().insert(CharacterFieldValue(
                                    characterId = charId,
                                    fieldDefinitionId = field.id,
                                    value = value
                                ))
                            }
                        }
                    }
                } catch (e: Exception) {
                    result.skippedRows++
                    result.errors.add("${universe.name} 행 $i: ${e.message}")
                }
            }
        }
    }

    // ── 미분류 캐릭터 가져오기 ──

    private suspend fun importUnclassifiedCharacters(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("미분류 캐릭터") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "이름") return

        val imageColIndex = findColumnIndex(headerRow, "이미지경로")
        val novelColIndex = findColumnIndex(headerRow, "작품")
        val memoColIndex = findColumnIndex(headerRow, "메모")
        val tagsColIndex = findColumnIndex(headerRow, "태그")

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, 0)
                if (name.isBlank()) continue

                val novelTitle = if (novelColIndex >= 0) getCellString(row, novelColIndex) else ""
                val novelId = resolveNovelIdNoUniverse(novelTitle)
                val imagePaths = if (imageColIndex >= 0) getCellString(row, imageColIndex) else "[]"
                val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""

                val existingChar = if (novelId != null) {
                    db.characterDao().getCharacterByNameAndNovel(name, novelId)
                } else null

                val charId: Long
                if (existingChar != null) {
                    charId = existingChar.id
                    db.characterDao().update(existingChar.copy(
                        imagePaths = imagePaths,
                        memo = memo,
                        updatedAt = System.currentTimeMillis()
                    ))
                    result.updatedCharacters++
                } else {
                    charId = db.characterDao().insert(Character(
                        name = name,
                        novelId = novelId,
                        imagePaths = imagePaths,
                        memo = memo
                    ))
                    result.newCharacters++
                }

                // 태그 가져오기
                if (tagsColIndex >= 0) {
                    val tagsStr = getCellString(row, tagsColIndex)
                    if (tagsStr.isNotBlank()) {
                        db.characterTagDao().deleteAllByCharacter(charId)
                        val tags = tagsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        tags.forEach { tag ->
                            db.characterTagDao().insert(CharacterTag(characterId = charId, tag = tag))
                        }
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("미분류 캐릭터 행 $i: ${e.message}")
            }
        }
    }

    // ── 연표 가져오기 ──

    private suspend fun importTimeline(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("사건 연표") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "연도") return

        val allNovels = db.novelDao().getAllNovelsList()

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
                val novelId = if (novelTitle.isNotBlank()) {
                    allNovels.find { it.title == novelTitle }?.id
                } else null

                // 기존 이벤트 찾기 (덮어쓰기)
                val existingEvent = if (novelId != null) {
                    db.timelineDao().getEventByNaturalKey(year, description, novelId)
                } else {
                    db.timelineDao().getEventByNaturalKeyNoNovel(year, description)
                }

                val eventId: Long
                if (existingEvent != null) {
                    eventId = existingEvent.id
                    db.timelineDao().update(existingEvent.copy(
                        month = month,
                        day = day,
                        calendarType = calendarType
                    ))
                    result.updatedEvents++
                } else {
                    eventId = db.timelineDao().insert(TimelineEvent(
                        year = year,
                        month = month,
                        day = day,
                        calendarType = calendarType,
                        description = description,
                        novelId = novelId
                    ))
                    result.newEvents++
                }

                // 관련 캐릭터 연결 복원
                val characterNames = getCellString(row, 6)
                if (characterNames.isNotBlank()) {
                    // 기존 크로스레프 제거 후 재설정
                    db.timelineDao().deleteCrossRefsByEvent(eventId)
                    val names = characterNames.split(", ").map { it.trim() }.filter { it.isNotBlank() }
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
    }

    // ── 상태변화 가져오기 ──

    private suspend fun importStateChanges(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("캐릭터 상태변화") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "캐릭터") return

        val allNovels = db.novelDao().getAllNovelsList()

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

                // 캐릭터 찾기
                val novelId = if (novelTitle.isNotBlank()) {
                    allNovels.find { it.title == novelTitle }?.id
                } else null
                val character = findCharacterByName(charName, novelId) ?: continue

                // 중복 체크 및 덮어쓰기
                val existing = db.characterStateChangeDao()
                    .getChangeByNaturalKey(character.id, year, fieldKey, newValue)

                if (existing != null) {
                    db.characterStateChangeDao().update(existing.copy(
                        month = month,
                        day = day,
                        description = description
                    ))
                    result.updatedStateChanges++
                } else {
                    db.characterStateChangeDao().insert(CharacterStateChange(
                        characterId = character.id,
                        year = year,
                        month = month,
                        day = day,
                        fieldKey = fieldKey,
                        newValue = newValue,
                        description = description
                    ))
                    result.newStateChanges++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("상태변화 행 $i: ${e.message}")
            }
        }
    }

    // ── 캐릭터 관계 가져오기 ──

    private suspend fun importRelationships(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("캐릭터 관계") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "캐릭터1") return

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val char1Name = getCellString(row, 0)
                val char2Name = getCellString(row, 1)
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val relationshipType = getCellString(row, 2)
                if (relationshipType.isBlank()) continue
                val description = getCellString(row, 3)

                val char1 = findCharacterByName(char1Name, null) ?: continue
                val char2 = findCharacterByName(char2Name, null) ?: continue

                // 자기 참조 관계 방지
                if (char1.id == char2.id) {
                    result.skippedRows++
                    result.errors.add("관계 행 $i: 자기 자신과의 관계는 허용되지 않습니다")
                    continue
                }

                // 중복 체크: 같은 두 캐릭터 + 같은 관계 유형
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
                        characterId1 = char1.id,
                        characterId2 = char2.id,
                        relationshipType = relationshipType,
                        description = description
                    ))
                    result.newRelationships++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("관계 행 $i: ${e.message}")
            }
        }
    }

    // ── 이름 은행 가져오기 ──

    private suspend fun importNameBank(workbook: Workbook, result: ImportResult) {
        val sheet = workbook.getSheet("이름 은행") ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (getCellString(headerRow, 0) != "이름") return

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

                val usedByCharacterId = if (usedByCharName.isNotBlank()) {
                    findCharacterByName(usedByCharName, null)?.id
                } else null

                // 중복 체크: 같은 이름
                val existing = existingNames.find { it.name == name && it.gender == gender }

                if (existing != null) {
                    db.nameBankDao().update(existing.copy(
                        origin = origin,
                        notes = notes,
                        isUsed = isUsed,
                        usedByCharacterId = usedByCharacterId
                    ))
                    result.updatedNameBank++
                } else {
                    val newEntry = NameBankEntry(
                        name = name,
                        gender = gender,
                        origin = origin,
                        notes = notes,
                        isUsed = isUsed,
                        usedByCharacterId = usedByCharacterId
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
    }

    // ── 유틸리티 메서드 ──

    private fun findSheetForUniverse(workbook: Workbook, universeName: String, reservedNames: Set<String>): Sheet? {
        // 정확한 이름 매칭 시도
        workbook.getSheet(universeName)?.let { return it }
        // 31자 잘림 대응: 세계관 이름이 31자 초과일 때 앞부분 매칭
        if (universeName.length > 31) {
            val truncated = universeName.take(31)
            workbook.getSheet(truncated)?.let { return it }
        }
        // 시트 이름 순회하며 매칭 (suffix 제거 대응)
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
        for (col in 1 until lastCol) {
            val headerName = getCellString(headerRow, col)
            if (headerName in setOf("작품", "이미지경로", "메모", "태그")) continue
            val field = fields.find { it.name == headerName }
            if (field != null) {
                map[col] = field
            }
        }
        return map
    }

    private fun findColumnIndex(headerRow: Row, columnName: String): Int {
        val lastCol = headerRow.lastCellNum.toInt()
        for (col in 0 until lastCol) {
            if (getCellString(headerRow, col) == columnName) return col
        }
        return -1
    }

    private val novelIdCache = mutableMapOf<Pair<String, Long?>, Long?>()

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
        // 같은 작품의 캐릭터 우선
        if (preferredNovelId != null) {
            val match = db.characterDao().getCharacterByNameAndNovel(name, preferredNovelId)
            if (match != null) return match
        }
        // 이름으로 직접 DB 검색 (전체 로드 방지)
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
        private const val MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024 // 50MB
    }
}
