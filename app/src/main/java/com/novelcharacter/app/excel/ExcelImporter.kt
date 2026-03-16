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
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class ExcelImporter(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)

    private var importLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    /**
     * registerForActivityResult는 Fragment가 STARTED 상태 이전에 호출되어야 합니다.
     * onViewCreated 등에서 미리 호출하세요.
     */
    fun registerLauncher(fragment: Fragment) {
        importLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { importFromExcel(it) }
        }
    }

    fun showImportDialog(fragment: Fragment) {
        val launcher = importLauncher
        if (launcher != null) {
            launcher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        } else {
            Toast.makeText(appContext, "가져오기를 사용하려면 앱을 다시 시작하세요", Toast.LENGTH_SHORT).show()
        }
    }

    fun importFromExcel(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw Exception("파일을 열 수 없습니다")

                var importedCharacters = 0
                var importedEvents = 0

                inputStream.use { stream ->
                    val workbook = XSSFWorkbook(stream)
                    workbook.use { wb ->
                        db.withTransaction {
                            // Cache novels to avoid N+1 queries
                            val novelCache = db.novelDao().getAllNovelsList().associateBy { it.title }.toMutableMap()

                            // 세계관별 시트에서 캐릭터 가져오기
                            val universes = db.universeDao().getAllUniversesList()

                            for (universe in universes) {
                                val sheetName = universe.name.take(31)
                                val sheet = wb.getSheet(sheetName) ?: continue
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
                                        val existing = novelCache[novelTitle]
                                        if (existing != null) {
                                            existing.id
                                        } else {
                                            val newNovel = Novel(title = novelTitle, universeId = universe.id)
                                            val id = db.novelDao().insert(newNovel)
                                            novelCache[novelTitle] = newNovel.copy(id = id)
                                            id
                                        }
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
                            val timelineSheet = wb.getSheet("사건 연표")
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
                                        novelCache[novelTitle]?.id
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
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        "가져오기 완료: 캐릭터 ${importedCharacters}명, 사건 ${importedEvents}개",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "가져오기 실패: ${e.message}", Toast.LENGTH_LONG).show()
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
