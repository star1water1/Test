package com.novelcharacter.app.excel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.novelcharacter.app.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExcelExporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val exportScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun exportAll() {
        exportScope.launch {
            try {
                val workbook = XSSFWorkbook()
                val headerStyle = createHeaderStyle(workbook)
                val usedSheetNames = mutableSetOf<String>()

                exportUniverses(workbook, headerStyle, usedSheetNames)
                exportNovels(workbook, headerStyle, usedSheetNames)
                exportFieldDefinitions(workbook, headerStyle, usedSheetNames)
                exportCharacters(workbook, headerStyle, usedSheetNames)
                exportTimeline(workbook, headerStyle, usedSheetNames)
                exportStateChanges(workbook, headerStyle, usedSheetNames)
                exportRelationships(workbook, headerStyle, usedSheetNames)
                exportNameBank(workbook, headerStyle, usedSheetNames)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "NovelCharacter_$timestamp.xlsx"

                val file = saveWorkbook(workbook, fileName)
                workbook.close()

                withContext(Dispatchers.Main) {
                    shareFile(file)
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Export failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "내보내기 실패: 잠시 후 다시 시도하세요", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveWorkbook(workbook: XSSFWorkbook, fileName: String): File {
        val exportsDir = File(context.cacheDir, "exports")
        exportsDir.mkdirs()
        // Clean old exports, keeping the 3 most recent
        exportsDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }

        val file = File(exportsDir, fileName)
        FileOutputStream(file).use { workbook.write(it) }
        return file
    }

    private fun shareFile(file: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(shareIntent, "내보내기 파일 공유")

        val activity = context as? Activity
        if (activity != null) {
            activity.startActivity(chooserIntent)
        } else {
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
        }
    }

    private fun sanitizeSheetName(name: String, usedNames: MutableSet<String>): String {
        // Excel 시트명: 최대 31자, 특수문자 제거
        var sanitized = name
            .replace(Regex("[\\[\\]*/\\\\?:]"), "")
            .take(31)
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

    private suspend fun exportUniverses(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle,
        usedSheetNames: MutableSet<String>
    ) {
        val universes = db.universeDao().getAllUniversesList()
        if (universes.isEmpty()) return

        val sheetName = sanitizeSheetName("세계관", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "설명")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        universes.forEachIndexed { index, universe ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(universe.name)
            row.createCell(1).setCellValue(universe.description)
        }

        headers.indices.forEach { sheet.setColumnWidth(it, 8000) }
    }

    private suspend fun exportNovels(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle,
        usedSheetNames: MutableSet<String>
    ) {
        val novels = db.novelDao().getAllNovelsList()
        val universes = db.universeDao().getAllUniversesList()
        if (novels.isEmpty()) return

        val sheetName = sanitizeSheetName("작품", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("제목", "설명", "세계관")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        novels.forEachIndexed { index, novel ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(novel.title)
            row.createCell(1).setCellValue(novel.description)
            val universeName = universes.find { it.id == novel.universeId }?.name ?: ""
            row.createCell(2).setCellValue(universeName)
        }

        headers.indices.forEach { sheet.setColumnWidth(it, 8000) }
    }

    private suspend fun exportFieldDefinitions(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle,
        usedSheetNames: MutableSet<String>
    ) {
        val universes = db.universeDao().getAllUniversesList()
        val allFields = mutableListOf<Pair<String, com.novelcharacter.app.data.model.FieldDefinition>>()
        for (universe in universes) {
            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            fields.forEach { allFields.add(universe.name to it) }
        }
        if (allFields.isEmpty()) return

        val sheetName = sanitizeSheetName("필드 정의", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("세계관", "필드키", "필드명", "타입", "설정(JSON)", "그룹", "순서", "필수여부")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        allFields.forEachIndexed { index, (universeName, field) ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(universeName)
            row.createCell(1).setCellValue(field.key)
            row.createCell(2).setCellValue(field.name)
            row.createCell(3).setCellValue(field.type)
            row.createCell(4).setCellValue(field.config)
            row.createCell(5).setCellValue(field.groupName)
            row.createCell(6).setCellValue(field.displayOrder.toDouble())
            row.createCell(7).setCellValue(if (field.isRequired) "Y" else "N")
        }

        headers.indices.forEach { sheet.setColumnWidth(it, 5000) }
    }

    private suspend fun exportCharacters(workbook: XSSFWorkbook, headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>) {
        val novels = db.novelDao().getAllNovelsList()
        val allCharacters = db.characterDao().getAllCharactersList()
        val universes = db.universeDao().getAllUniversesList()

        // 세계관별로 시트 생성
        for (universe in universes) {
            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            val universeNovels = novels.filter { it.universeId == universe.id }
            val universeCharIds = universeNovels.flatMap { novel ->
                db.characterDao().getCharactersByNovelList(novel.id).map { it.id }
            }.toSet()
            val universeChars = allCharacters.filter { it.id in universeCharIds }

            if (universeChars.isEmpty()) continue

            val sheetName = sanitizeSheetName(universe.name, usedSheetNames)
            val sheet = workbook.createSheet(sheetName)

            // 헤더: 이름 + 동적 필드 + 이미지경로 + 작품 + 메모 + 태그
            val headers = mutableListOf("이름")
            headers.addAll(fields.map { it.name })
            headers.add("이미지경로")
            headers.add("작품")
            headers.add("메모")
            headers.add("태그")

            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { index, header ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = headerStyle
            }

            // 데이터
            universeChars.forEachIndexed { index, character ->
                val row = sheet.createRow(index + 1)
                val novelTitle = novels.find { it.id == character.novelId }?.title ?: ""
                val fieldValues = db.characterFieldValueDao().getValuesByCharacterList(character.id)

                row.createCell(0).setCellValue(character.name)

                fields.forEachIndexed { fi, field ->
                    val value = fieldValues.find { it.fieldDefinitionId == field.id }?.value ?: ""
                    row.createCell(fi + 1).setCellValue(value)
                }

                row.createCell(fields.size + 1).setCellValue(character.imagePaths)
                row.createCell(fields.size + 2).setCellValue(novelTitle)
                row.createCell(fields.size + 3).setCellValue(character.memo)
                val tags = db.characterTagDao().getTagsByCharacterList(character.id)
                row.createCell(fields.size + 4).setCellValue(tags.joinToString(", ") { it.tag })
            }

            headers.indices.forEach { sheet.setColumnWidth(it, 5000) }
        }

        // 세계관 없는 캐릭터들도 별도 시트
        val unassignedChars = allCharacters.filter { char ->
            val novel = novels.find { it.id == char.novelId }
            novel?.universeId == null
        }
        if (unassignedChars.isNotEmpty()) {
            val sheetName = sanitizeSheetName("미분류 캐릭터", usedSheetNames)
            val sheet = workbook.createSheet(sheetName)
            val headers = listOf("이름", "이미지경로", "작품", "메모", "태그")
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { index, header ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = headerStyle
            }
            unassignedChars.forEachIndexed { index, character ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue(character.name)
                row.createCell(1).setCellValue(character.imagePaths)
                row.createCell(2).setCellValue(novels.find { it.id == character.novelId }?.title ?: "")
                row.createCell(3).setCellValue(character.memo)
                val tags = db.characterTagDao().getTagsByCharacterList(character.id)
                row.createCell(4).setCellValue(tags.joinToString(", ") { it.tag })
            }
            headers.indices.forEach { sheet.setColumnWidth(it, 5000) }
        }
    }

    private suspend fun exportTimeline(workbook: XSSFWorkbook, headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>) {
        val events = db.timelineDao().getAllEventsList()
        val novels = db.novelDao().getAllNovelsList()

        val sheetName = sanitizeSheetName("사건 연표", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("연도", "월", "일", "역법", "사건 설명", "관련 작품", "관련 캐릭터")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        events.forEachIndexed { index, event ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(event.year.toDouble())
            event.month?.let { row.createCell(1).setCellValue(it.toDouble()) }
            event.day?.let { row.createCell(2).setCellValue(it.toDouble()) }
            row.createCell(3).setCellValue(event.calendarType)
            row.createCell(4).setCellValue(event.description)

            val novelTitle = novels.find { it.id == event.novelId }?.title ?: ""
            row.createCell(5).setCellValue(novelTitle)

            val characters = db.timelineDao().getCharactersForEvent(event.id)
            row.createCell(6).setCellValue(characters.joinToString(", ") { it.name })
        }

        sheet.setColumnWidth(0, 3000)
        sheet.setColumnWidth(1, 2000)
        sheet.setColumnWidth(2, 2000)
        sheet.setColumnWidth(3, 3000)
        sheet.setColumnWidth(4, 15000)
        sheet.setColumnWidth(5, 5000)
        sheet.setColumnWidth(6, 10000)
    }

    private suspend fun exportStateChanges(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle,
        usedSheetNames: MutableSet<String>
    ) {
        val allCharacters = db.characterDao().getAllCharactersList()
        val novels = db.novelDao().getAllNovelsList()

        val allChanges = mutableListOf<Triple<String, String, com.novelcharacter.app.data.model.CharacterStateChange>>()
        for (character in allCharacters) {
            val changes = db.characterStateChangeDao().getChangesByCharacterList(character.id)
            val novelTitle = novels.find { it.id == character.novelId }?.title ?: ""
            changes.forEach { allChanges.add(Triple(character.name, novelTitle, it)) }
        }
        if (allChanges.isEmpty()) return

        val sheetName = sanitizeSheetName("캐릭터 상태변화", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("캐릭터", "작품", "연도", "월", "일", "필드키", "새 값", "설명")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        allChanges.forEachIndexed { index, (charName, novelTitle, change) ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(charName)
            row.createCell(1).setCellValue(novelTitle)
            row.createCell(2).setCellValue(change.year.toDouble())
            change.month?.let { row.createCell(3).setCellValue(it.toDouble()) }
            change.day?.let { row.createCell(4).setCellValue(it.toDouble()) }
            row.createCell(5).setCellValue(change.fieldKey)
            row.createCell(6).setCellValue(change.newValue)
            row.createCell(7).setCellValue(change.description)
        }

        headers.indices.forEach { sheet.setColumnWidth(it, 5000) }
    }

    private suspend fun exportRelationships(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle,
        usedSheetNames: MutableSet<String>
    ) {
        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        if (allRelationships.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("캐릭터 관계", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("캐릭터1", "캐릭터2", "관계 유형", "설명")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }

        allRelationships.forEachIndexed { i, rel ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(charMap[rel.characterId1]?.name ?: "")
            row.createCell(1).setCellValue(charMap[rel.characterId2]?.name ?: "")
            row.createCell(2).setCellValue(rel.relationshipType)
            row.createCell(3).setCellValue(rel.description)
        }

        headers.indices.forEach { sheet.setColumnWidth(it, 5000) }
    }

    private suspend fun exportNameBank(
        workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle,
        usedSheetNames: MutableSet<String>
    ) {
        val allNames = db.nameBankDao().getAllNamesList()
        if (allNames.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("이름 은행", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "성별", "출처", "메모", "사용여부", "사용 캐릭터")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }

        allNames.forEachIndexed { i, entry ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(entry.name)
            row.createCell(1).setCellValue(entry.gender)
            row.createCell(2).setCellValue(entry.origin)
            row.createCell(3).setCellValue(entry.notes)
            row.createCell(4).setCellValue(if (entry.isUsed) "Y" else "N")
            row.createCell(5).setCellValue(entry.usedByCharacterId?.let { charMap[it]?.name } ?: "")
        }

        headers.indices.forEach { sheet.setColumnWidth(it, 5000) }
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
