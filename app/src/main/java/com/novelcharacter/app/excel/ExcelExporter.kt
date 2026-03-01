package com.novelcharacter.app.excel

import android.content.Context
import android.os.Environment
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

class ExcelExporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)

    fun exportAll() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val workbook = XSSFWorkbook()
                val headerStyle = createHeaderStyle(workbook)

                exportCharacters(workbook, headerStyle)
                exportTimeline(workbook, headerStyle)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "NovelCharacter_$timestamp.xlsx"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)

                FileOutputStream(file).use { outputStream ->
                    workbook.write(outputStream)
                }
                workbook.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "내보내기 완료: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun exportCharacters(workbook: XSSFWorkbook, headerStyle: CellStyle) {
        val novels = db.novelDao().getAllNovelsList()
        val allCharacters = db.characterDao().getAllCharactersList()

        // 전체 캐릭터 시트
        val sheet = workbook.createSheet("전체 캐릭터")
        val headers = listOf(
            "이름", "나이", "생존", "성별", "키", "체형", "종족", "직업/직책",
            "초월 유무", "초월자 번호", "초월자 세대", "마력", "권능",
            "거주지", "소속", "좋아하는 것", "싫어하는 것", "성격",
            "특이사항", "외모특징", "전투력 등급", "생일", "등장 시리즈"
        )

        // 헤더 행
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        // 데이터 행
        allCharacters.forEachIndexed { index, character ->
            val row = sheet.createRow(index + 1)
            val novelTitle = novels.find { it.id == character.novelId }?.title ?: ""

            row.createCell(0).setCellValue(character.name)
            row.createCell(1).setCellValue(character.age)
            row.createCell(2).setCellValue(when (character.isAlive) {
                true -> "o"
                false -> "x"
                null -> "?"
            })
            row.createCell(3).setCellValue(character.gender)
            row.createCell(4).setCellValue(character.height)
            row.createCell(5).setCellValue(character.bodyType)
            row.createCell(6).setCellValue(character.race)
            row.createCell(7).setCellValue(character.jobTitle)
            row.createCell(8).setCellValue(when (character.isTranscendent) {
                true -> "o"
                false -> "x"
                null -> ""
            })
            row.createCell(9).setCellValue(character.transcendentNumber?.toString() ?: "")
            row.createCell(10).setCellValue(character.transcendentGeneration)
            row.createCell(11).setCellValue(character.magicPower)
            row.createCell(12).setCellValue(character.authority)
            row.createCell(13).setCellValue(character.residence)
            row.createCell(14).setCellValue(character.affiliation)
            row.createCell(15).setCellValue(character.likes)
            row.createCell(16).setCellValue(character.dislikes)
            row.createCell(17).setCellValue(character.personality)
            row.createCell(18).setCellValue(character.specialNotes)
            row.createCell(19).setCellValue(character.appearance)
            row.createCell(20).setCellValue(character.combatRank)
            row.createCell(21).setCellValue(character.birthday)
            row.createCell(22).setCellValue(novelTitle)
        }

        // 열 너비 자동 조절
        headers.indices.forEach { sheet.setColumnWidth(it, 5000) }

        // 소설별 시트도 추가
        novels.forEach { novel ->
            val novelCharacters = allCharacters.filter { it.novelId == novel.id }
            if (novelCharacters.isNotEmpty()) {
                val novelSheet = workbook.createSheet(novel.title.take(31)) // 시트 이름 최대 31자
                val novelHeaderRow = novelSheet.createRow(0)
                headers.forEachIndexed { index, header ->
                    val cell = novelHeaderRow.createCell(index)
                    cell.setCellValue(header)
                    cell.cellStyle = headerStyle
                }
                novelCharacters.forEachIndexed { index, character ->
                    val row = novelSheet.createRow(index + 1)
                    row.createCell(0).setCellValue(character.name)
                    row.createCell(1).setCellValue(character.age)
                    row.createCell(2).setCellValue(when (character.isAlive) { true -> "o"; false -> "x"; null -> "?" })
                    row.createCell(3).setCellValue(character.gender)
                    row.createCell(4).setCellValue(character.height)
                    row.createCell(5).setCellValue(character.bodyType)
                    row.createCell(6).setCellValue(character.race)
                    row.createCell(7).setCellValue(character.jobTitle)
                    row.createCell(8).setCellValue(when (character.isTranscendent) { true -> "o"; false -> "x"; null -> "" })
                    row.createCell(9).setCellValue(character.transcendentNumber?.toString() ?: "")
                    row.createCell(10).setCellValue(character.transcendentGeneration)
                    row.createCell(11).setCellValue(character.magicPower)
                    row.createCell(12).setCellValue(character.authority)
                    row.createCell(13).setCellValue(character.residence)
                    row.createCell(14).setCellValue(character.affiliation)
                    row.createCell(15).setCellValue(character.likes)
                    row.createCell(16).setCellValue(character.dislikes)
                    row.createCell(17).setCellValue(character.personality)
                    row.createCell(18).setCellValue(character.specialNotes)
                    row.createCell(19).setCellValue(character.appearance)
                    row.createCell(20).setCellValue(character.combatRank)
                    row.createCell(21).setCellValue(character.birthday)
                    row.createCell(22).setCellValue(novel.title)
                }
                headers.indices.forEach { novelSheet.setColumnWidth(it, 5000) }
            }
        }
    }

    private suspend fun exportTimeline(workbook: XSSFWorkbook, headerStyle: CellStyle) {
        val events = db.timelineDao().getAllEventsList()
        val novels = db.novelDao().getAllNovelsList()

        val sheet = workbook.createSheet("사건 연표")
        val headers = listOf("연도", "역법", "사건 설명", "관련 작품", "관련 캐릭터")

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        events.forEachIndexed { index, event ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(event.year.toDouble())
            row.createCell(1).setCellValue(event.calendarType)
            row.createCell(2).setCellValue(event.description)

            val novelTitle = novels.find { it.id == event.novelId }?.title ?: ""
            row.createCell(3).setCellValue(novelTitle)

            val characters = db.timelineDao().getCharactersForEvent(event.id)
            row.createCell(4).setCellValue(characters.joinToString(", ") { it.name })
        }

        sheet.setColumnWidth(0, 3000)
        sheet.setColumnWidth(1, 3000)
        sheet.setColumnWidth(2, 15000)
        sheet.setColumnWidth(3, 5000)
        sheet.setColumnWidth(4, 10000)
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
