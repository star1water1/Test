package com.novelcharacter.app.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import com.novelcharacter.app.util.ThemeHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoBackupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val BACKUP_DIR_NAME = "backups"
        private const val BACKUP_PREFIX = "NovelCharacter_AutoBackup_"
        private const val BACKUP_EXTENSION = ".enc"
        private const val MAX_BACKUPS = 3
    }

    override suspend fun doWork(): Result {
        val statusStore = BackupStatusStore(appContext)
        return try {
            Log.i(TAG, "Starting auto backup...")
            val db = AppDatabase.getDatabase(appContext)
            val workbook = XSSFWorkbook()
            try {
                val headerStyle = createHeaderStyle(workbook)
                val usedSheetNames = mutableSetOf<String>()

                // Export all data
                exportUniverses(db, workbook, headerStyle, usedSheetNames)
                exportNovels(db, workbook, headerStyle, usedSheetNames)
                exportCharacters(db, workbook, headerStyle, usedSheetNames)
                exportTimeline(db, workbook, headerStyle, usedSheetNames)
                exportFieldDefinitions(db, workbook, headerStyle, usedSheetNames)
                exportStateChanges(db, workbook, headerStyle, usedSheetNames)
                exportRelationships(db, workbook, headerStyle, usedSheetNames)
                exportRelationshipChanges(db, workbook, headerStyle, usedSheetNames)
                exportNameBank(db, workbook, headerStyle, usedSheetNames)
                exportUserPresetTemplates(db, workbook, headerStyle, usedSheetNames)
                exportSearchPresets(db, workbook, headerStyle, usedSheetNames)
                exportAppSettings(workbook, headerStyle, usedSheetNames)

                // Write workbook to bytes, encrypt, and save to internal storage
                saveEncryptedBackup(workbook)
            } finally {
                try { workbook.close() } catch (e: Exception) { Log.w(TAG, "Failed to close workbook", e) }
            }

            // Rotate old backups
            rotateBackups()

            statusStore.recordSuccess()
            Log.i(TAG, "Auto backup completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed", e)
            statusStore.recordFailure(e.message ?: "Unknown error")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun saveEncryptedBackup(workbook: XSSFWorkbook) {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "$BACKUP_PREFIX$timestamp$BACKUP_EXTENSION"
        val backupFile = File(backupDir, fileName)

        // Write to temp file then stream-encrypt to avoid double memory copy
        val tempFile = File.createTempFile("backup_", ".xlsx", backupDir)
        try {
            tempFile.outputStream().use { fos ->
                workbook.write(fos)
            }
            BackupEncryptor.encryptFile(tempFile, backupFile)
        } catch (e: Exception) {
            backupFile.delete() // clean up partial encrypted file on failure
            throw e
        } finally {
            tempFile.delete()
        }

        Log.i(TAG, "Encrypted backup saved: ${backupFile.absolutePath}")
    }

    private fun rotateBackups() {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) return

        // Clean up orphaned temp files from previous interrupted backups
        backupDir.listFiles { file ->
            file.name.startsWith("backup_") && file.name.endsWith(".xlsx")
        }?.forEach { it.delete() }

        val backupFiles = backupDir.listFiles { file ->
            file.name.startsWith(BACKUP_PREFIX) && file.name.endsWith(BACKUP_EXTENSION)
        }?.sortedByDescending { it.lastModified() } ?: return

        if (backupFiles.size > MAX_BACKUPS) {
            backupFiles.drop(MAX_BACKUPS).forEach { file ->
                if (file.delete()) {
                    Log.i(TAG, "Deleted old backup: ${file.name}")
                }
            }
        }
    }

    private suspend fun exportUniverses(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val universes = db.universeDao().getAllUniversesList()
        if (universes.isEmpty()) return

        val sheetName = sanitizeSheetName("세계관", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "설명", "코드", "정렬순서", "테두리색", "테두리두께", "이미지경로", "이미지모드", "커스텀관계유형", "커스텀관계색상", "이미지캐릭터ID", "이미지작품ID")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        universes.forEachIndexed { i, u ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(u.name)
            row.createCell(1).setCellValue(u.description)
            row.createCell(2).setCellValue(u.code)
            row.createCell(3).setCellValue(u.displayOrder.toDouble())
            row.createCell(4).setCellValue(u.borderColor)
            row.createCell(5).setCellValue(u.borderWidthDp.toDouble())
            row.createCell(6).setCellValue(u.imagePaths)
            row.createCell(7).setCellValue(u.imageMode)
            row.createCell(8).setCellValue(u.customRelationshipTypes)
            row.createCell(9).setCellValue(u.customRelationshipColors)
            u.imageCharacterId?.let { row.createCell(10).setCellValue(it.toDouble()) }
            u.imageNovelId?.let { row.createCell(11).setCellValue(it.toDouble()) }
        }
    }

    private suspend fun exportNovels(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val novels = db.novelDao().getAllNovelsList()
        val universes = db.universeDao().getAllUniversesList()
        if (novels.isEmpty()) return

        val sheetName = sanitizeSheetName("작품", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("제목", "설명", "세계관", "코드", "세계관코드", "정렬순서", "테두리색", "테두리두께", "이미지경로", "이미지모드", "이미지캐릭터ID", "테두리상속", "고정", "표준연도")
        val universeMap = universes.associateBy { it.id }
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        novels.forEachIndexed { i, n ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(n.title)
            row.createCell(1).setCellValue(n.description)
            val universe = n.universeId?.let { universeMap[it] }
            row.createCell(2).setCellValue(universe?.name ?: "")
            row.createCell(3).setCellValue(n.code)
            row.createCell(4).setCellValue(universe?.code ?: "")
            row.createCell(5).setCellValue(n.displayOrder.toDouble())
            row.createCell(6).setCellValue(n.borderColor)
            row.createCell(7).setCellValue(n.borderWidthDp.toDouble())
            row.createCell(8).setCellValue(n.imagePaths)
            row.createCell(9).setCellValue(n.imageMode)
            n.imageCharacterId?.let { row.createCell(10).setCellValue(it.toDouble()) }
            row.createCell(11).setCellValue(if (n.inheritUniverseBorder) "Y" else "N")
            row.createCell(12).setCellValue(if (n.isPinned) "Y" else "N")
            n.standardYear?.let { row.createCell(13).setCellValue(it.toDouble()) }
        }
    }

    private suspend fun exportCharacters(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val allCharacters = db.characterDao().getAllCharactersList()
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }
        val universes = db.universeDao().getAllUniversesList()
        if (allCharacters.isEmpty()) return

        // Batch load all field values and tags to avoid N+1 queries
        val allFieldValues = db.characterFieldValueDao().getAllValuesList().groupBy { it.characterId }
        val allTags = db.characterTagDao().getAllTagsList().groupBy { it.characterId }

        // Export per-universe sheets (matching ExcelExporter/ExcelImportService format)
        for (universe in universes) {
            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            val universeNovels = novels.filter { it.universeId == universe.id }
            val universeNovelIds = universeNovels.map { it.id }.toSet()
            val universeChars = allCharacters.filter { it.novelId in universeNovelIds }
            if (universeChars.isEmpty()) continue

            exportCharacterSheet(
                workbook, headerStyle, usedSheetNames,
                universe.name, universeChars, fields, novelMap, allFieldValues, allTags
            )
        }

        // Unassigned characters (no universe)
        val unassignedChars = allCharacters.filter { char ->
            val novel = novelMap[char.novelId]
            novel?.universeId == null
        }
        if (unassignedChars.isNotEmpty()) {
            exportCharacterSheet(
                workbook, headerStyle, usedSheetNames,
                "미분류 캐릭터", unassignedChars, emptyList(), novelMap, allFieldValues, allTags
            )
        }
    }

    private fun exportCharacterSheet(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>,
        sheetLabel: String,
        characters: List<com.novelcharacter.app.data.model.Character>,
        fields: List<com.novelcharacter.app.data.model.FieldDefinition>,
        novelMap: Map<Long, com.novelcharacter.app.data.model.Novel>,
        allFieldValues: Map<Long, List<com.novelcharacter.app.data.model.CharacterFieldValue>>,
        allTags: Map<Long, List<com.novelcharacter.app.data.model.CharacterTag>>
    ) {
        val sheetName = sanitizeSheetName(sheetLabel, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)

        // Build headers: 이름, 성, 이름(First), 이명, [dynamic fields...], 이미지경로, 작품, 메모, 태그, 코드, 작품코드, 정렬순서
        val headers = mutableListOf("이름", "성", "이름(First)", "이명")
        fields.forEach { headers.add(it.name) }
        headers.addAll(listOf("이미지경로", "작품", "메모", "태그", "코드", "작품코드", "정렬순서", "고정"))

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }

        characters.forEachIndexed { i, c ->
            val row = sheet.createRow(i + 1)
            val novel = c.novelId?.let { novelMap[it] }
            val fieldValueMap = (allFieldValues[c.id] ?: emptyList()).associateBy { it.fieldDefinitionId }
            val tags = allTags[c.id] ?: emptyList()
            var col = 0

            row.createCell(col++).setCellValue(c.name)
            row.createCell(col++).setCellValue(c.lastName)
            row.createCell(col++).setCellValue(c.firstName)
            row.createCell(col++).setCellValue(c.anotherName)
            for (field in fields) {
                row.createCell(col++).setCellValue(fieldValueMap[field.id]?.value ?: "")
            }
            row.createCell(col++).setCellValue(c.imagePaths)
            row.createCell(col++).setCellValue(novel?.title ?: "")
            row.createCell(col++).setCellValue(c.memo)
            row.createCell(col++).setCellValue(tags.joinToString(", ") { it.tag })
            row.createCell(col++).setCellValue(c.code)
            row.createCell(col++).setCellValue(novel?.code ?: "")
            row.createCell(col++).setCellValue(c.displayOrder.toDouble())
            row.createCell(col).setCellValue(if (c.isPinned) "Y" else "N")
        }
    }

    private suspend fun exportTimeline(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val events = db.timelineDao().getAllEventsList()
        if (events.isEmpty()) return
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        // Batch load all cross-refs and characters to avoid N+1 queries
        val allCrossRefs = db.timelineDao().getAllCrossRefs()
        val eventCharMap = allCrossRefs.groupBy({ it.eventId }, { it.characterId })
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("사건 연표", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("연도", "월", "일", "역법", "사건 설명", "관련 작품", "관련 캐릭터", "관련작품코드", "정렬순서", "임시배치")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        events.forEachIndexed { i, e ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(e.year.toDouble())
            e.month?.let { row.createCell(1).setCellValue(it.toDouble()) }
            e.day?.let { row.createCell(2).setCellValue(it.toDouble()) }
            row.createCell(3).setCellValue(e.calendarType)
            row.createCell(4).setCellValue(e.description)
            val novel = e.novelId?.let { novelMap[it] }
            row.createCell(5).setCellValue(novel?.title ?: "")
            val characterNames = (eventCharMap[e.id] ?: emptyList()).mapNotNull { charMap[it]?.name }
            row.createCell(6).setCellValue(characterNames.joinToString(", "))
            row.createCell(7).setCellValue(novel?.code ?: "")
            row.createCell(8).setCellValue(e.displayOrder.toDouble())
            row.createCell(9).setCellValue(if (e.isTemporary) "Y" else "N")
        }
    }

    private suspend fun exportFieldDefinitions(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val allFields = universes.flatMap { u ->
            db.fieldDefinitionDao().getFieldsByUniverseList(u.id).map { u to it }
        }
        if (allFields.isEmpty()) return

        val sheetName = sanitizeSheetName("필드 정의", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("세계관", "필드키", "필드명", "타입", "설정(JSON)", "그룹", "순서", "필수여부", "세계관코드")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        allFields.forEachIndexed { i, (universe, f) ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(universe.name)
            row.createCell(1).setCellValue(f.key)
            row.createCell(2).setCellValue(f.name)
            row.createCell(3).setCellValue(f.type)
            row.createCell(4).setCellValue(f.config)
            row.createCell(5).setCellValue(f.groupName)
            row.createCell(6).setCellValue(f.displayOrder.toDouble())
            row.createCell(7).setCellValue(if (f.isRequired) "Y" else "N")
            row.createCell(8).setCellValue(universe.code)
        }
    }

    private suspend fun exportStateChanges(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        data class ChangeRow(val character: com.novelcharacter.app.data.model.Character, val novelTitle: String, val change: com.novelcharacter.app.data.model.CharacterStateChange)
        val allStateChanges = db.characterStateChangeDao().getAllChangesList()
        val allChanges = allStateChanges.mapNotNull { change ->
            val c = charMap[change.characterId] ?: return@mapNotNull null
            val novelTitle = c.novelId?.let { novelMap[it]?.title } ?: ""
            ChangeRow(c, novelTitle, change)
        }
        if (allChanges.isEmpty()) return

        val sheetName = sanitizeSheetName("캐릭터 상태변화", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("캐릭터", "작품", "연도", "월", "일", "필드키", "새 값", "설명", "캐릭터코드")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        allChanges.forEachIndexed { i, (character, novelTitle, sc) ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(character.name)
            row.createCell(1).setCellValue(novelTitle)
            row.createCell(2).setCellValue(sc.year.toDouble())
            sc.month?.let { row.createCell(3).setCellValue(it.toDouble()) }
            sc.day?.let { row.createCell(4).setCellValue(it.toDouble()) }
            row.createCell(5).setCellValue(sc.fieldKey)
            row.createCell(6).setCellValue(sc.newValue)
            row.createCell(7).setCellValue(sc.description)
            row.createCell(8).setCellValue(character.code)
        }
    }

    private suspend fun exportRelationships(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val relationships = db.characterRelationshipDao().getAllRelationships()
        if (relationships.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("캐릭터 관계", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("캐릭터1", "캐릭터2", "관계 유형", "설명", "강도", "양방향", "표시순서", "캐릭터1코드", "캐릭터2코드")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        relationships.forEachIndexed { i, r ->
            val row = sheet.createRow(i + 1)
            val char1 = charMap[r.characterId1]
            val char2 = charMap[r.characterId2]
            row.createCell(0).setCellValue(char1?.name ?: "")
            row.createCell(1).setCellValue(char2?.name ?: "")
            row.createCell(2).setCellValue(r.relationshipType)
            row.createCell(3).setCellValue(r.description)
            row.createCell(4).setCellValue(r.intensity.toDouble())
            row.createCell(5).setCellValue(if (r.isBidirectional) "Y" else "N")
            row.createCell(6).setCellValue(r.displayOrder.toDouble())
            row.createCell(7).setCellValue(char1?.code ?: "")
            row.createCell(8).setCellValue(char2?.code ?: "")
        }
    }

    private suspend fun exportRelationshipChanges(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val allChanges = db.characterRelationshipChangeDao().getAllChanges()
        if (allChanges.isEmpty()) return

        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        val relMap = allRelationships.associateBy { it.id }
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("관계 변화", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("캐릭터1", "캐릭터2", "연도", "월", "일", "관계 유형", "설명", "강도", "양방향", "캐릭터1코드", "캐릭터2코드", "연결사건ID")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        var rowIndex = 0
        for (rc in allChanges) {
            val rel = relMap[rc.relationshipId] ?: continue
            val char1 = charMap[rel.characterId1]
            val char2 = charMap[rel.characterId2]
            val row = sheet.createRow(++rowIndex)
            row.createCell(0).setCellValue(char1?.name ?: "")
            row.createCell(1).setCellValue(char2?.name ?: "")
            row.createCell(2).setCellValue(rc.year.toDouble())
            rc.month?.let { row.createCell(3).setCellValue(it.toDouble()) }
            rc.day?.let { row.createCell(4).setCellValue(it.toDouble()) }
            row.createCell(5).setCellValue(rc.relationshipType)
            row.createCell(6).setCellValue(rc.description)
            row.createCell(7).setCellValue(rc.intensity.toDouble())
            row.createCell(8).setCellValue(if (rc.isBidirectional) "Y" else "N")
            row.createCell(9).setCellValue(char1?.code ?: "")
            row.createCell(10).setCellValue(char2?.code ?: "")
            rc.eventId?.let { row.createCell(11).setCellValue(it.toDouble()) }
        }
    }

    private suspend fun exportNameBank(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val names = db.nameBankDao().getAllNamesList()
        if (names.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("이름 은행", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "성별", "출처", "메모", "사용여부", "사용 캐릭터", "사용캐릭터코드")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        names.forEachIndexed { i, n ->
            val row = sheet.createRow(i + 1)
            val usedByChar = n.usedByCharacterId?.let { charMap[it] }
            row.createCell(0).setCellValue(n.name)
            row.createCell(1).setCellValue(n.gender)
            row.createCell(2).setCellValue(n.origin)
            row.createCell(3).setCellValue(n.notes)
            row.createCell(4).setCellValue(if (n.isUsed) "Y" else "N")
            row.createCell(5).setCellValue(usedByChar?.name ?: "")
            row.createCell(6).setCellValue(usedByChar?.code ?: "")
        }
    }

    private suspend fun exportUserPresetTemplates(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val templates = db.userPresetTemplateDao().getAllTemplatesList()
        if (templates.isEmpty()) return

        val sheetName = sanitizeSheetName("필드 템플릿", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "설명", "설정(JSON)", "기본제공", "생성일", "수정일")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        templates.forEachIndexed { i, t ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(t.name)
            row.createCell(1).setCellValue(t.description)
            row.createCell(2).setCellValue(t.fieldsJson)
            row.createCell(3).setCellValue(if (t.isBuiltIn) "Y" else "N")
            row.createCell(4).setCellValue(t.createdAt.toDouble())
            row.createCell(5).setCellValue(t.updatedAt.toDouble())
        }
    }

    private suspend fun exportSearchPresets(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val presets = db.searchPresetDao().getAllPresetsList()
        if (presets.isEmpty()) return

        val sheetName = sanitizeSheetName("검색 프리셋", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "검색어", "필터(JSON)", "정렬모드", "기본값", "생성일", "수정일")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        presets.forEachIndexed { i, p ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(p.name)
            row.createCell(1).setCellValue(p.query)
            row.createCell(2).setCellValue(p.filtersJson)
            row.createCell(3).setCellValue(p.sortMode)
            row.createCell(4).setCellValue(if (p.isDefault) "Y" else "N")
            row.createCell(5).setCellValue(p.createdAt.toDouble())
            row.createCell(6).setCellValue(p.updatedAt.toDouble())
        }
    }

    private fun exportAppSettings(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val sheetName = sanitizeSheetName("앱 설정", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("설정키", "설정값")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }

        val themeMode = ThemeHelper.getSavedTheme(appContext)
        val row = sheet.createRow(1)
        row.createCell(0).setCellValue("theme_mode")
        row.createCell(1).setCellValue(themeMode.toDouble())
    }

    private fun sanitizeSheetName(name: String, usedNames: MutableSet<String>): String {
        var sanitized = name.replace(Regex("[\\[\\]*/\\\\?:]"), "").take(31)
        if (sanitized.isBlank()) sanitized = "Sheet"
        var result = sanitized
        var counter = 2
        while (result in usedNames) {
            val suffix = "($counter)"
            result = sanitized.take(31 - suffix.length) + suffix
            counter++
        }
        usedNames.add(result)
        return result
    }

    private fun createHeaderStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        val font = workbook.createFont()
        font.bold = true
        font.color = IndexedColors.WHITE.index
        style.setFont(font)
        style.fillForegroundColor = IndexedColors.DARK_BLUE.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        return style
    }
}
