package com.novelcharacter.app.excel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.novelcharacter.app.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExcelExporter(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)

    fun exportAll() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val workbook = XSSFWorkbook()
                val headerStyle = createHeaderStyle(workbook)

                exportCharacters(workbook, headerStyle)
                exportTimeline(workbook, headerStyle)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "NovelCharacter_$timestamp.xlsx"

                saveWorkbook(workbook, fileName)
                workbook.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "내보내기 완료: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveWorkbook(workbook: XSSFWorkbook, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ : MediaStore 사용 (Scoped Storage)
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = appContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
            ) ?: throw Exception("파일을 생성할 수 없습니다")

            appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                workbook.write(outputStream)
            } ?: throw Exception("파일에 쓸 수 없습니다")
        } else {
            // API 28 이하: 기존 방식
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { outputStream ->
                workbook.write(outputStream)
            }
        }
    }

    /**
     * Sanitize and deduplicate sheet names for Excel compatibility.
     * Sheet names cannot contain \ / ? * [ ] and must be <= 31 chars.
     */
    private fun sanitizeSheetName(name: String, existingNames: Set<String>): String {
        val sanitized = name.replace(Regex("[\\\\/?*\\[\\]]"), "_").take(31)
        if (sanitized !in existingNames) return sanitized
        // Deduplicate by appending a number
        for (i in 2..999) {
            val suffix = " ($i)"
            val candidate = sanitized.take(31 - suffix.length) + suffix
            if (candidate !in existingNames) return candidate
        }
        return sanitized
    }

    private suspend fun exportCharacters(workbook: XSSFWorkbook, headerStyle: CellStyle) {
        val novels = db.novelDao().getAllNovelsList()
        val allCharacters = db.characterDao().getAllCharactersList()
        val universes = db.universeDao().getAllUniversesList()
        val usedSheetNames = mutableSetOf<String>()

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
            usedSheetNames.add(sheetName)
            val sheet = workbook.createSheet(sheetName)

            // 헤더: 이름 + 동적 필드 + 작품
            val headers = mutableListOf("이름")
            headers.addAll(fields.map { it.name })
            headers.add("작품")

            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { index, header ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = headerStyle
            }

            // 데이터 - use map for O(1) novel lookup, batch load field values
            val novelMap = novels.associateBy { it.id }
            universeChars.forEachIndexed { index, character ->
                val row = sheet.createRow(index + 1)
                val novelTitle = character.novelId?.let { novelMap[it]?.title } ?: ""
                val fieldValues = db.characterFieldValueDao().getValuesByCharacterList(character.id)

                row.createCell(0).setCellValue(character.name)

                fields.forEachIndexed { fi, field ->
                    val value = fieldValues.find { it.fieldDefinitionId == field.id }?.value ?: ""
                    row.createCell(fi + 1).setCellValue(value)
                }

                row.createCell(fields.size + 1).setCellValue(novelTitle)
            }

            headers.indices.forEach { sheet.setColumnWidth(it, 5000) }
        }

        // 세계관 없는 캐릭터들도 별도 시트
        val novelMapForFilter = novels.associateBy { it.id }
        val unassignedChars = allCharacters.filter { char ->
            val novel = char.novelId?.let { novelMapForFilter[it] }
            novel?.universeId == null
        }
        if (unassignedChars.isNotEmpty()) {
            val sheetName = sanitizeSheetName("미분류 캐릭터", usedSheetNames)
            usedSheetNames.add(sheetName)
            val sheet = workbook.createSheet(sheetName)
            val headerRow = sheet.createRow(0)
            listOf("이름", "작품").forEachIndexed { index, header ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = headerStyle
            }
            unassignedChars.forEachIndexed { index, character ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue(character.name)
                row.createCell(1).setCellValue(character.novelId?.let { novelMapForFilter[it]?.title } ?: "")
            }
        }
    }

    private suspend fun exportTimeline(workbook: XSSFWorkbook, headerStyle: CellStyle) {
        val events = db.timelineDao().getAllEventsList()
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        val sheet = workbook.createSheet("사건 연표")
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
            if (event.month != null) {
                row.createCell(1).setCellValue(event.month.toDouble())
            } else {
                row.createCell(1).setCellValue("")
            }
            if (event.day != null) {
                row.createCell(2).setCellValue(event.day.toDouble())
            } else {
                row.createCell(2).setCellValue("")
            }
            row.createCell(3).setCellValue(event.calendarType)
            row.createCell(4).setCellValue(event.description)

            val novelTitle = event.novelId?.let { novelMap[it]?.title } ?: ""
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

    private fun createHeaderStyle(workbook: XSSFWorkbook): CellStyle {
        val style = workbook.createCellStyle()
        val font = workbook.createFont()
        font.bold = true
        font.color = IndexedColors.WHITE.index
        style.setFont(font)
        style.fillForegroundColor = IndexedColors.DARK_BLUE.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        return style
    }
}
