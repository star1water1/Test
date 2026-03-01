package com.novelcharacter.app.excel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
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

                var importedCharacters = 0
                var importedEvents = 0

                inputStream.use { stream ->
                    val workbook = XSSFWorkbook(stream)
                    try {
                        // 세계관별 시트에서 캐릭터 가져오기
                        val universes = db.universeDao().getAllUniversesList()

                        for (universe in universes) {
                            val sheet = workbook.getSheet(universe.name) ?: continue
                            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
                            val headerRow = sheet.getRow(0) ?: continue

                            // 첫 번째 열이 "이름"인지 확인
                            if (getCellString(headerRow, 0) != "이름") continue

                            for (i in 1..sheet.lastRowNum) {
                                val row = sheet.getRow(i) ?: continue
                                val name = getCellString(row, 0)
                                if (name.isBlank()) continue

                                // 마지막 열은 "작품"
                                val novelTitle = getCellString(row, fields.size + 1)
                                val novelId = if (novelTitle.isNotBlank()) {
                                    val existingNovels = db.novelDao().getAllNovelsList()
                                    val existing = existingNovels.find { it.title == novelTitle }
                                    existing?.id ?: db.novelDao().insert(
                                        Novel(title = novelTitle, universeId = universe.id)
                                    )
                                } else null

                                val character = Character(name = name, novelId = novelId)
                                val charId = db.characterDao().insert(character)

                                // 동적 필드 값 가져오기
                                val fieldValues = mutableListOf<CharacterFieldValue>()
                                fields.forEachIndexed { fi, field ->
                                    val value = getCellString(row, fi + 1)
                                    if (value.isNotBlank()) {
                                        fieldValues.add(
                                            CharacterFieldValue(
                                                characterId = charId,
                                                fieldDefinitionId = field.id,
                                                value = value
                                            )
                                        )
                                    }
                                }
                                if (fieldValues.isNotEmpty()) {
                                    db.characterFieldValueDao().insertAll(fieldValues)
                                }
                                importedCharacters++
                            }
                        }

                        // 연표 시트 가져오기
                        val timelineSheet = workbook.getSheet("사건 연표")
                        if (timelineSheet != null) {
                            for (i in 1..timelineSheet.lastRowNum) {
                                val row = timelineSheet.getRow(i) ?: continue
                                val yearStr = getCellString(row, 0)
                                val year = yearStr.toDoubleOrNull()?.toInt() ?: continue
                                val description = getCellString(row, 4)
                                if (description.isBlank()) continue

                                val month = getCellString(row, 1).toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                                val day = getCellString(row, 2).toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                                val calendarType = getCellString(row, 3)
                                val novelTitle = getCellString(row, 5)
                                val novelId = if (novelTitle.isNotBlank()) {
                                    val existingNovels = db.novelDao().getAllNovelsList()
                                    existingNovels.find { it.title == novelTitle }?.id
                                } else null

                                val event = TimelineEvent(
                                    year = year,
                                    month = month,
                                    day = day,
                                    calendarType = calendarType.ifBlank { "천개력" },
                                    description = description,
                                    novelId = novelId
                                )
                                db.timelineDao().insert(event)
                                importedEvents++
                            }
                        }
                    } finally {
                        workbook.close()
                    }
                }

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
