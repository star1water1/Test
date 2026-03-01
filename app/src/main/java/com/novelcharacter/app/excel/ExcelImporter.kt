package com.novelcharacter.app.excel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class ExcelImporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)

    fun showImportDialog(fragment: Fragment) {
        val launcher = fragment.registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { importFromExcel(it) }
        }
        launcher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    }

    fun importFromExcel(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("파일을 열 수 없습니다")

                val workbook = XSSFWorkbook(inputStream)
                var importedCharacters = 0
                var importedEvents = 0

                // 캐릭터 시트 찾기 ("전체 캐릭터" 또는 첫 번째 시트)
                val characterSheet = workbook.getSheet("전체 캐릭터")
                    ?: workbook.getSheetAt(0)

                if (characterSheet != null) {
                    val headerRow = characterSheet.getRow(0)
                    if (headerRow != null && getCellString(headerRow, 0) == "이름") {
                        // 캐릭터 데이터 가져오기
                        for (i in 1..characterSheet.lastRowNum) {
                            val row = characterSheet.getRow(i) ?: continue
                            val name = getCellString(row, 0)
                            if (name.isBlank()) continue

                            // 작품 찾기 또는 생성
                            val novelTitle = getCellString(row, 22)
                            val novelId = if (novelTitle.isNotBlank()) {
                                val existingNovels = db.novelDao().getAllNovelsList()
                                val existing = existingNovels.find { it.title == novelTitle }
                                existing?.id ?: db.novelDao().insert(Novel(title = novelTitle))
                            } else null

                            val character = Character(
                                name = name,
                                novelId = novelId,
                                age = getCellString(row, 1),
                                isAlive = when (getCellString(row, 2).lowercase()) {
                                    "o" -> true
                                    "x" -> false
                                    else -> null
                                },
                                gender = getCellString(row, 3),
                                height = getCellString(row, 4),
                                bodyType = getCellString(row, 5),
                                race = getCellString(row, 6),
                                jobTitle = getCellString(row, 7),
                                isTranscendent = when (getCellString(row, 8).lowercase()) {
                                    "o" -> true
                                    "x" -> false
                                    else -> null
                                },
                                transcendentNumber = getCellString(row, 9).toIntOrNull(),
                                transcendentGeneration = getCellString(row, 10),
                                magicPower = getCellString(row, 11),
                                authority = getCellString(row, 12),
                                residence = getCellString(row, 13),
                                affiliation = getCellString(row, 14),
                                likes = getCellString(row, 15),
                                dislikes = getCellString(row, 16),
                                personality = getCellString(row, 17),
                                specialNotes = getCellString(row, 18),
                                appearance = getCellString(row, 19),
                                combatRank = getCellString(row, 20),
                                birthday = getCellString(row, 21)
                            )
                            db.characterDao().insert(character)
                            importedCharacters++
                        }
                    }
                }

                // 연표 시트 찾기
                val timelineSheet = workbook.getSheet("사건 연표")
                if (timelineSheet != null) {
                    for (i in 1..timelineSheet.lastRowNum) {
                        val row = timelineSheet.getRow(i) ?: continue
                        val yearStr = getCellString(row, 0)
                        val year = yearStr.toDoubleOrNull()?.toInt() ?: continue
                        val description = getCellString(row, 2)
                        if (description.isBlank()) continue

                        val calendarType = getCellString(row, 1)
                        val novelTitle = getCellString(row, 3)
                        val novelId = if (novelTitle.isNotBlank()) {
                            val existingNovels = db.novelDao().getAllNovelsList()
                            existingNovels.find { it.title == novelTitle }?.id
                        } else null

                        val event = TimelineEvent(
                            year = year,
                            calendarType = calendarType.ifBlank { "천개력" },
                            description = description,
                            novelId = novelId
                        )
                        db.timelineDao().insert(event)
                        importedEvents++
                    }
                }

                workbook.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "가져오기 완료: 캐릭터 ${importedCharacters}명, 사건 ${importedEvents}개",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "가져오기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getCellString(row: Row, cellIndex: Int): String {
        val cell = row.getCell(cellIndex) ?: return ""
        return when (cell.cellType) {
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
    }
}
