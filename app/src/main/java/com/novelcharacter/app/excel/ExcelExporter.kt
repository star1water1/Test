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
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.DataValidation
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExcelExporter(private val context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    private val supervisorJob = kotlinx.coroutines.SupervisorJob()
    private val exportScope = CoroutineScope(Dispatchers.IO + supervisorJob)

    private lateinit var styles: ExcelStyles

    fun exportAll() {
        exportScope.launch {
            var workbook: XSSFWorkbook? = null
            try {
                workbook = XSSFWorkbook()
                styles = ExcelStyles(workbook)
                val usedSheetNames = mutableSetOf<String>()

                exportInstructions(workbook, usedSheetNames)
                exportUniverses(workbook, usedSheetNames)
                exportNovels(workbook, usedSheetNames)
                exportFieldDefinitions(workbook, usedSheetNames)
                exportCharacters(workbook, usedSheetNames)
                exportTimeline(workbook, usedSheetNames)
                exportStateChanges(workbook, usedSheetNames)
                exportRelationships(workbook, usedSheetNames)
                exportNameBank(workbook, usedSheetNames)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "NovelCharacter_$timestamp.xlsx"

                val file = saveWorkbook(workbook, fileName)

                withContext(Dispatchers.Main) {
                    shareFile(file)
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Export failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(com.novelcharacter.app.R.string.export_failed_retry), Toast.LENGTH_LONG).show()
                }
            } finally {
                try { workbook?.close() } catch (_: Exception) {}
                supervisorJob.complete()
            }
        }
    }

    fun cancel() {
        supervisorJob.cancel()
    }

    // ── 스타일 관리 ──

    private class ExcelStyles(workbook: XSSFWorkbook) {
        val header: XSSFCellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.color = IndexedColors.WHITE.index
            font.fontHeightInPoints = 11
            setFont(font)
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            setBorderBottom(BorderStyle.THIN)
        }

        val requiredHeader: XSSFCellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.color = IndexedColors.WHITE.index
            font.fontHeightInPoints = 11
            setFont(font)
            fillForegroundColor = IndexedColors.RED.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            setBorderBottom(BorderStyle.THIN)
        }

        val readOnly: XSSFCellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.color = IndexedColors.GREY_50_PERCENT.index
            setFont(font)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val readOnlyHeader: XSSFCellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.color = IndexedColors.WHITE.index
            font.fontHeightInPoints = 11
            setFont(font)
            fillForegroundColor = IndexedColors.GREY_50_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            setBorderBottom(BorderStyle.THIN)
        }

        val guideTitle: XSSFCellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 14
            setFont(font)
        }

        val guideSection: XSSFCellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 11
            font.color = IndexedColors.DARK_BLUE.index
            setFont(font)
        }

        val guideBody: XSSFCellStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            wrapText = true
            verticalAlignment = VerticalAlignment.TOP
        }
    }

    // ── 유틸리티 ──

    private fun XSSFSheet.freezeAndFilter(lastCol: Int, dataRowCount: Int) {
        createFreezePane(0, 1)
        if (dataRowCount > 0) {
            setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, lastCol - 1))
        }
    }

    private fun addDropdownValidation(
        sheet: XSSFSheet,
        colIndex: Int,
        dataRowCount: Int,
        options: List<String>
    ) {
        if (dataRowCount <= 0 || options.isEmpty()) return
        val maxRow = minOf(dataRowCount, 10000)
        val addressList = CellRangeAddressList(1, maxRow, colIndex, colIndex)
        val dvHelper = sheet.dataValidationHelper
        val dvConstraint = dvHelper.createExplicitListConstraint(options.toTypedArray())
        val validation = dvHelper.createValidation(dvConstraint, addressList)
        validation.showErrorBox = true
        validation.errorStyle = DataValidation.ErrorStyle.WARNING
        validation.createErrorBox("입력 오류", "목록에서 선택하세요: ${options.joinToString(", ")}")
        validation.showPromptBox = true
        validation.createPromptBox("선택", options.joinToString(", "))
        sheet.addValidationData(validation)
    }

    private fun applyReadOnlyColumn(sheet: XSSFSheet, colIndex: Int, dataRowCount: Int) {
        for (i in 1..dataRowCount) {
            val row = sheet.getRow(i) ?: continue
            val cell = row.getCell(colIndex) ?: row.createCell(colIndex)
            cell.cellStyle = styles.readOnly
        }
    }

    private fun saveWorkbook(workbook: XSSFWorkbook, fileName: String): File {
        val exportsDir = File(appContext.cacheDir, "exports")
        exportsDir.mkdirs()
        exportsDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }

        val file = File(exportsDir, fileName)
        FileOutputStream(file).use { workbook.write(it) }
        return file
    }

    private fun shareFile(file: File) {
        val authority = "${appContext.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(appContext, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(shareIntent, appContext.getString(com.novelcharacter.app.R.string.export_share_title))

        val activity = context as? Activity
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            activity.startActivity(chooserIntent)
        } else {
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooserIntent)
        }
    }

    private fun sanitizeSheetName(name: String, usedNames: MutableSet<String>): String {
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

    // ── 사용 안내 시트 ──

    private data class GuideLine(val section: String, val style: XSSFCellStyle, val text: String)

    private fun exportInstructions(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val sheetName = sanitizeSheetName("사용 안내", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)

        val lines = listOf(
            GuideLine("", styles.guideTitle, "NovelCharacter 엑셀 파일 편집 안내"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("색상 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "■ 파란 헤더 = 편집 가능한 일반 컬럼"),
            GuideLine("", styles.guideBody, "■ 빨간 헤더 = 필수 입력 컬럼 (비워두면 해당 행 무시됨)"),
            GuideLine("", styles.guideBody, "■ 회색 헤더/셀 = 수정 불가 (앱 내부 데이터, 수정해도 무시됨)"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("시트별 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 세계관: 이름으로 기존 데이터 매칭. 이름 변경 시 새 세계관으로 추가됨"),
            GuideLine("", styles.guideBody, "• 작품: 제목+세계관으로 매칭. 세계관 이름은 '세계관' 시트의 이름과 정확히 일치해야 함"),
            GuideLine("", styles.guideBody, "• 필드 정의: 세계관+필드키로 매칭. 타입은 드롭다운에서 선택"),
            GuideLine("", styles.guideBody, "• 캐릭터 시트 (세계관 이름): 이름+작품으로 매칭. 드롭다운이 있는 필드는 목록에서 선택"),
            GuideLine("", styles.guideBody, "• 사건 연표: 연도+설명으로 매칭. 관련 캐릭터는 쉼표로 구분"),
            GuideLine("", styles.guideBody, "• 캐릭터 관계: 관계 유형은 드롭다운에서 선택"),
            GuideLine("", styles.guideBody, "• 이름 은행: 이름+성별로 매칭. 사용여부는 Y/N"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("주의사항", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 시트 이름을 변경하지 마세요 (가져오기 시 시트명으로 데이터를 찾습니다)"),
            GuideLine("", styles.guideBody, "• 헤더 행(1행)을 삭제하거나 순서를 바꾸지 마세요"),
            GuideLine("", styles.guideBody, "• 행을 추가하여 새 데이터를 입력할 수 있습니다"),
            GuideLine("", styles.guideBody, "• 이미지경로 컬럼은 앱 내부 경로이므로 수정하지 마세요"),
            GuideLine("", styles.guideBody, "• 태그는 쉼표(,)로 구분하여 입력하세요"),
            GuideLine("", styles.guideBody, "• 이 '사용 안내' 시트는 가져오기 시 무시됩니다")
        )

        lines.forEachIndexed { rowIndex, line ->
            val row = sheet.createRow(rowIndex)
            if (line.section.isNotBlank()) {
                row.createCell(0).apply {
                    setCellValue(line.section)
                    cellStyle = line.style
                }
                if (line.text.isNotBlank()) {
                    row.createCell(1).apply {
                        setCellValue(line.text)
                        cellStyle = styles.guideBody
                    }
                }
            } else {
                row.createCell(0).apply {
                    setCellValue(line.text)
                    cellStyle = line.style
                }
            }
        }

        sheet.setColumnWidth(0, 15000)
        sheet.setColumnWidth(1, 25000)
    }

    // ── 세계관 ──

    private suspend fun exportUniverses(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        if (universes.isEmpty()) return

        val sheetName = sanitizeSheetName("세계관", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름" to true, "설명" to false)

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, (header, required) ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = if (required) styles.requiredHeader else styles.header
        }

        universes.forEachIndexed { index, universe ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(universe.name)
            row.createCell(1).setCellValue(universe.description)
        }

        sheet.setColumnWidth(0, 8000)
        sheet.setColumnWidth(1, 15000)
        sheet.freezeAndFilter(headers.size, universes.size)
    }

    // ── 작품 ──

    private suspend fun exportNovels(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val novels = db.novelDao().getAllNovelsList()
        val universes = db.universeDao().getAllUniversesList()
        if (novels.isEmpty()) return

        val sheetName = sanitizeSheetName("작품", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("제목" to true, "설명" to false, "세계관" to false)

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, (header, required) ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = if (required) styles.requiredHeader else styles.header
        }

        novels.forEachIndexed { index, novel ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(novel.title)
            row.createCell(1).setCellValue(novel.description)
            val universeName = universes.find { it.id == novel.universeId }?.name ?: ""
            row.createCell(2).setCellValue(universeName)
        }

        // 세계관 드롭다운
        if (universes.isNotEmpty()) {
            addDropdownValidation(sheet, 2, novels.size + 100, universes.map { it.name })
        }

        sheet.setColumnWidth(0, 8000)
        sheet.setColumnWidth(1, 15000)
        sheet.setColumnWidth(2, 8000)
        sheet.freezeAndFilter(headers.size, novels.size)
    }

    // ── 필드 정의 ──

    private suspend fun exportFieldDefinitions(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val allFields = mutableListOf<Pair<String, com.novelcharacter.app.data.model.FieldDefinition>>()
        for (universe in universes) {
            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            fields.forEach { allFields.add(universe.name to it) }
        }
        if (allFields.isEmpty()) return

        val sheetName = sanitizeSheetName("필드 정의", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        // (헤더, 필수여부, 읽기전용)
        val headers = listOf(
            Triple("세계관", true, false),
            Triple("필드키", true, false),
            Triple("필드명", true, false),
            Triple("타입", true, false),
            Triple("설정(JSON)", false, false),
            Triple("그룹", false, false),
            Triple("순서", false, false),
            Triple("필수여부", false, false)
        )

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, (header, required, _) ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = if (required) styles.requiredHeader else styles.header
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

        // 타입 드롭다운
        val fieldTypes = listOf("TEXT", "NUMBER", "SELECT", "MULTI_TEXT", "GRADE", "CALCULATED", "BODY_SIZE")
        addDropdownValidation(sheet, 3, allFields.size + 100, fieldTypes)

        // 필수여부 드롭다운
        addDropdownValidation(sheet, 7, allFields.size + 100, listOf("Y", "N"))

        // 세계관 드롭다운
        if (universes.isNotEmpty()) {
            addDropdownValidation(sheet, 0, allFields.size + 100, universes.map { it.name })
        }

        sheet.setColumnWidth(0, 5000)
        sheet.setColumnWidth(1, 5000)
        sheet.setColumnWidth(2, 5000)
        sheet.setColumnWidth(3, 4000)
        sheet.setColumnWidth(4, 10000)
        sheet.setColumnWidth(5, 5000)
        sheet.setColumnWidth(6, 3000)
        sheet.setColumnWidth(7, 4000)
        sheet.freezeAndFilter(headers.size, allFields.size)
    }

    // ── 캐릭터 (세계관별) ──

    private suspend fun exportCharacters(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val novels = db.novelDao().getAllNovelsList()
        val allCharacters = db.characterDao().getAllCharactersList()
        val universes = db.universeDao().getAllUniversesList()

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

            // 헤더 구성: (이름, 필수, 읽기전용, SELECT옵션)
            data class ColDef(val name: String, val required: Boolean, val readOnly: Boolean, val selectOptions: List<String>? = null)

            val columns = mutableListOf<ColDef>()
            columns.add(ColDef("이름", required = true, readOnly = false))

            // 동적 필드 - SELECT 타입은 드롭다운 옵션 파싱
            for (field in fields) {
                val options = if (field.type == "SELECT") {
                    try {
                        val json = org.json.JSONObject(field.config)
                        val arr = json.optJSONArray("options")
                        if (arr != null) (0 until arr.length()).map { arr.getString(it) } else null
                    } catch (_: Exception) { null }
                } else null
                columns.add(ColDef(field.name, required = field.isRequired, readOnly = false, selectOptions = options))
            }

            columns.add(ColDef("이미지경로", required = false, readOnly = true))
            columns.add(ColDef("작품", required = false, readOnly = false))
            columns.add(ColDef("메모", required = false, readOnly = false))
            columns.add(ColDef("태그", required = false, readOnly = false))

            // 헤더 행
            val headerRow = sheet.createRow(0)
            columns.forEachIndexed { index, col ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(col.name)
                cell.cellStyle = when {
                    col.readOnly -> styles.readOnlyHeader
                    col.required -> styles.requiredHeader
                    else -> styles.header
                }
            }

            // 데이터 행
            universeChars.forEachIndexed { index, character ->
                val row = sheet.createRow(index + 1)
                val novelTitle = novels.find { it.id == character.novelId }?.title ?: ""
                val fieldValues = db.characterFieldValueDao().getValuesByCharacterList(character.id)

                row.createCell(0).setCellValue(character.name)

                fields.forEachIndexed { fi, field ->
                    val value = fieldValues.find { it.fieldDefinitionId == field.id }?.value ?: ""
                    row.createCell(fi + 1).setCellValue(value)
                }

                val imageCell = row.createCell(fields.size + 1)
                imageCell.setCellValue(character.imagePaths)
                imageCell.cellStyle = styles.readOnly

                row.createCell(fields.size + 2).setCellValue(novelTitle)
                row.createCell(fields.size + 3).setCellValue(character.memo)
                val tags = db.characterTagDao().getTagsByCharacterList(character.id)
                row.createCell(fields.size + 4).setCellValue(tags.joinToString(", ") { it.tag })
            }

            // 드롭다운 적용
            columns.forEachIndexed { colIndex, col ->
                if (col.selectOptions != null) {
                    addDropdownValidation(sheet, colIndex, universeChars.size + 100, col.selectOptions)
                }
            }

            // 작품 드롭다운
            if (universeNovels.isNotEmpty()) {
                val novelColIndex = fields.size + 2
                addDropdownValidation(sheet, novelColIndex, universeChars.size + 100, universeNovels.map { it.title })
            }

            // 컬럼 너비
            columns.forEachIndexed { index, col ->
                val width = when {
                    col.name == "이름" -> 6000
                    col.name == "메모" -> 10000
                    col.name == "태그" -> 8000
                    col.name == "이미지경로" -> 4000
                    col.name == "작품" -> 6000
                    else -> 5000
                }
                sheet.setColumnWidth(index, width)
            }

            sheet.freezeAndFilter(columns.size, universeChars.size)
        }

        // 미분류 캐릭터
        val unassignedChars = allCharacters.filter { char ->
            val novel = novels.find { it.id == char.novelId }
            novel?.universeId == null
        }
        if (unassignedChars.isNotEmpty()) {
            val sheetName = sanitizeSheetName("미분류 캐릭터", usedSheetNames)
            val sheet = workbook.createSheet(sheetName)

            val headerDefs = listOf(
                "이름" to true,
                "이미지경로" to false,
                "작품" to false,
                "메모" to false,
                "태그" to false
            )
            val readOnlyCols = setOf(1) // 이미지경로

            val headerRow = sheet.createRow(0)
            headerDefs.forEachIndexed { index, (header, required) ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = when {
                    index in readOnlyCols -> styles.readOnlyHeader
                    required -> styles.requiredHeader
                    else -> styles.header
                }
            }

            unassignedChars.forEachIndexed { index, character ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue(character.name)
                val imageCell = row.createCell(1)
                imageCell.setCellValue(character.imagePaths)
                imageCell.cellStyle = styles.readOnly
                row.createCell(2).setCellValue(novels.find { it.id == character.novelId }?.title ?: "")
                row.createCell(3).setCellValue(character.memo)
                val tags = db.characterTagDao().getTagsByCharacterList(character.id)
                row.createCell(4).setCellValue(tags.joinToString(", ") { it.tag })
            }

            sheet.setColumnWidth(0, 6000)
            sheet.setColumnWidth(1, 4000)
            sheet.setColumnWidth(2, 6000)
            sheet.setColumnWidth(3, 10000)
            sheet.setColumnWidth(4, 8000)
            sheet.freezeAndFilter(headerDefs.size, unassignedChars.size)
        }
    }

    // ── 사건 연표 ──

    private suspend fun exportTimeline(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val events = db.timelineDao().getAllEventsList()
        if (events.isEmpty()) return
        val novels = db.novelDao().getAllNovelsList()

        val sheetName = sanitizeSheetName("사건 연표", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf(
            "연도" to true,
            "월" to false,
            "일" to false,
            "역법" to false,
            "사건 설명" to true,
            "관련 작품" to false,
            "관련 캐릭터" to false
        )

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, (header, required) ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = if (required) styles.requiredHeader else styles.header
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

        // 작품 드롭다운
        if (novels.isNotEmpty()) {
            addDropdownValidation(sheet, 5, events.size + 100, novels.map { it.title })
        }

        sheet.setColumnWidth(0, 3000)
        sheet.setColumnWidth(1, 2000)
        sheet.setColumnWidth(2, 2000)
        sheet.setColumnWidth(3, 3000)
        sheet.setColumnWidth(4, 15000)
        sheet.setColumnWidth(5, 6000)
        sheet.setColumnWidth(6, 10000)
        sheet.freezeAndFilter(headers.size, events.size)
    }

    // ── 캐릭터 상태변화 ──

    private suspend fun exportStateChanges(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
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
        val headers = listOf(
            "캐릭터" to true,
            "작품" to false,
            "연도" to true,
            "월" to false,
            "일" to false,
            "필드키" to true,
            "새 값" to false,
            "설명" to false
        )

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, (header, required) ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = if (required) styles.requiredHeader else styles.header
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

        sheet.setColumnWidth(0, 5000)
        sheet.setColumnWidth(1, 5000)
        sheet.setColumnWidth(2, 3000)
        sheet.setColumnWidth(3, 2000)
        sheet.setColumnWidth(4, 2000)
        sheet.setColumnWidth(5, 5000)
        sheet.setColumnWidth(6, 5000)
        sheet.setColumnWidth(7, 10000)
        sheet.freezeAndFilter(headers.size, allChanges.size)
    }

    // ── 캐릭터 관계 ──

    private suspend fun exportRelationships(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        if (allRelationships.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("캐릭터 관계", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf(
            "캐릭터1" to true,
            "캐릭터2" to true,
            "관계 유형" to true,
            "설명" to false
        )

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, (h, required) ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = if (required) styles.requiredHeader else styles.header
            }
        }

        allRelationships.forEachIndexed { i, rel ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(charMap[rel.characterId1]?.name ?: "")
            row.createCell(1).setCellValue(charMap[rel.characterId2]?.name ?: "")
            row.createCell(2).setCellValue(rel.relationshipType)
            row.createCell(3).setCellValue(rel.description)
        }

        // 관계 유형 드롭다운
        val relationTypes = listOf("부모-자식", "연인", "라이벌", "멘토-제자", "동료", "적", "형제자매", "친구", "기타")
        addDropdownValidation(sheet, 2, allRelationships.size + 100, relationTypes)

        sheet.setColumnWidth(0, 6000)
        sheet.setColumnWidth(1, 6000)
        sheet.setColumnWidth(2, 5000)
        sheet.setColumnWidth(3, 10000)
        sheet.freezeAndFilter(headers.size, allRelationships.size)
    }

    // ── 이름 은행 ──

    private suspend fun exportNameBank(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allNames = db.nameBankDao().getAllNamesList()
        if (allNames.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val sheetName = sanitizeSheetName("이름 은행", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf(
            "이름" to true,
            "성별" to false,
            "출처" to false,
            "메모" to false,
            "사용여부" to false,
            "사용 캐릭터" to false
        )

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, (h, required) ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = if (required) styles.requiredHeader else styles.header
            }
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

        // 사용여부 드롭다운
        addDropdownValidation(sheet, 4, allNames.size + 100, listOf("Y", "N"))

        sheet.setColumnWidth(0, 5000)
        sheet.setColumnWidth(1, 3000)
        sheet.setColumnWidth(2, 5000)
        sheet.setColumnWidth(3, 8000)
        sheet.setColumnWidth(4, 4000)
        sheet.setColumnWidth(5, 5000)
        sheet.freezeAndFilter(headers.size, allNames.size)
    }
}
