package com.novelcharacter.app.excel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.novelcharacter.app.R
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
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

class ExcelExporter(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    @Volatile private var supervisorJob = kotlinx.coroutines.SupervisorJob()
    @Volatile private var exportScope = CoroutineScope(Dispatchers.IO + supervisorJob)

    @Synchronized
    private fun ensureActiveScope(): CoroutineScope {
        if (supervisorJob.isCompleted || supervisorJob.isCancelled) {
            supervisorJob = kotlinx.coroutines.SupervisorJob()
            exportScope = CoroutineScope(Dispatchers.IO + supervisorJob)
        }
        return exportScope
    }

    private lateinit var styles: ExcelStyles

    /**
     * @param onFileReady if non-null, called with the temp file instead of opening a share sheet.
     *                    The caller is responsible for launching SAF to let the user pick a save location.
     */
    fun exportAll(onFileReady: ((File, String) -> Unit)? = null) {
        ensureActiveScope().launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, appContext.getString(R.string.export_preparing), Toast.LENGTH_SHORT).show()
            }
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
                    if (onFileReady != null) {
                        onFileReady(file, fileName)
                    } else {
                        shareFile(file)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Export failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_failed_retry) + "\n" + e.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                try { workbook?.close() } catch (_: Exception) {}
            }
        }
    }

    fun writeToUri(uri: Uri, sourceFile: File) {
        ensureActiveScope().launch {
            try {
                val outputStream = appContext.contentResolver.openOutputStream(uri)
                if (outputStream == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, appContext.getString(R.string.export_save_failed), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                outputStream.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                sourceFile.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_save_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Save to URI failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_save_failed) + "\n" + e.message, Toast.LENGTH_LONG).show()
                }
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

    // ── SheetSpec 기반 유틸리티 ──

    private fun writeHeaderRow(sheet: XSSFSheet, spec: SheetSpec) {
        val headerRow = sheet.createRow(0)
        spec.columns.forEachIndexed { index, col ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(col.header)
            cell.cellStyle = when {
                col.readOnly -> styles.readOnlyHeader
                col.required -> styles.requiredHeader
                else -> styles.header
            }
        }
    }

    private fun applySpecFormatting(sheet: XSSFSheet, spec: SheetSpec, dataRowCount: Int) {
        // Dropdowns
        spec.columns.forEachIndexed { colIndex, col ->
            col.dropdownOptions?.let { options ->
                addDropdownValidation(sheet, colIndex, dataRowCount, options)
            }
        }
        // Column widths
        spec.columns.forEachIndexed { index, col ->
            sheet.setColumnWidth(index, col.width)
        }
        // Read-only cell styles
        spec.columns.forEachIndexed { colIndex, col ->
            if (col.readOnly) {
                applyReadOnlyColumn(sheet, colIndex, dataRowCount)
            }
        }
        sheet.freezeAndFilter(spec.columns.size, dataRowCount)
    }

    // ── 기존 유틸리티 ──

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
        if (options.isEmpty()) return
        val maxRow = minOf(maxOf(dataRowCount + DROPDOWN_EXTRA_ROWS, 1), MAX_DROPDOWN_ROWS)
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

        val chooserIntent = Intent.createChooser(shareIntent, appContext.getString(R.string.export_share_title))

        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(chooserIntent)
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
            GuideLine("코드 컬럼 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 회색 코드 컬럼은 자동 생성된 고유 식별자입니다. 수정하지 마세요."),
            GuideLine("", styles.guideBody, "• 이름/제목은 자유롭게 변경할 수 있습니다 — 코드가 있으면 코드로 매칭합니다."),
            GuideLine("", styles.guideBody, "• 새 행을 추가할 때는 코드를 비워두세요. 자동으로 생성됩니다."),
            GuideLine("", styles.guideBody, "• 코드를 삭제하면 이름 기반으로 매칭됩니다 (구버전 호환)."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("시트별 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 세계관: 코드로 기존 데이터 매칭. 코드 없을 시 이름으로 매칭"),
            GuideLine("", styles.guideBody, "• 작품: 코드로 매칭. 코드 없을 시 제목+세계관으로 매칭"),
            GuideLine("", styles.guideBody, "• 필드 정의: 세계관+필드키로 매칭. 타입은 드롭다운에서 선택"),
            GuideLine("", styles.guideBody, "• 캐릭터 시트 (세계관 이름): 코드로 매칭. 코드 없을 시 이름+작품으로 매칭"),
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

        val spec = universeSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        universes.forEachIndexed { index, universe ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(universe.name)
            row.createCell(1).setCellValue(universe.description)
            row.createCell(2).setCellValue(universe.code)
        }

        applySpecFormatting(sheet, spec, universes.size)
    }

    // ── 작품 ──

    private suspend fun exportNovels(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val novels = db.novelDao().getAllNovelsList()
        val universes = db.universeDao().getAllUniversesList()
        if (novels.isEmpty()) return

        val universeMap = universes.associateBy { it.id }
        val spec = novelSpec(universes.map { it.name })
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        novels.forEachIndexed { index, novel ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(novel.title)
            row.createCell(1).setCellValue(novel.description)
            val universe = novel.universeId?.let { universeMap[it] }
            row.createCell(2).setCellValue(universe?.name ?: "")
            row.createCell(3).setCellValue(novel.code)
            row.createCell(4).setCellValue(universe?.code ?: "")
        }

        applySpecFormatting(sheet, spec, novels.size)
    }

    // ── 필드 정의 ──

    private suspend fun exportFieldDefinitions(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val allFields = mutableListOf<Pair<Long, FieldDefinition>>()
        for (universe in universes) {
            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            fields.forEach { allFields.add(universe.id to it) }
        }
        if (allFields.isEmpty()) return

        val spec = fieldDefinitionSpec(universes.map { it.name })
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allFields.forEachIndexed { index, (universeId, field) ->
            val universe = universeMap[universeId]
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(universe?.name ?: "")
            row.createCell(1).setCellValue(field.key)
            row.createCell(2).setCellValue(field.name)
            row.createCell(3).setCellValue(field.type)
            row.createCell(4).setCellValue(field.config)
            row.createCell(5).setCellValue(field.groupName)
            row.createCell(6).setCellValue(field.displayOrder.toDouble())
            row.createCell(7).setCellValue(if (field.isRequired) "Y" else "N")
            row.createCell(8).setCellValue(universe?.code ?: "")
        }

        applySpecFormatting(sheet, spec, allFields.size)
    }

    // ── 캐릭터 (세계관별 + 미분류 통합) ──

    private suspend fun exportCharacters(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }
        val allCharacters = db.characterDao().getAllCharactersList()
        val universes = db.universeDao().getAllUniversesList()

        // Batch load all field values and tags to avoid N+1 queries
        val allFieldValuesMap = db.characterFieldValueDao().getAllValuesList().groupBy { it.characterId }
        val allTagsMap = db.characterTagDao().getAllTagsList().groupBy { it.characterId }

        for (universe in universes) {
            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            val universeNovels = novels.filter { it.universeId == universe.id }
            val universeNovelIds = universeNovels.map { it.id }.toSet()
            val universeChars = allCharacters.filter { it.novelId in universeNovelIds }

            if (universeChars.isEmpty()) continue

            val fieldValues = universeChars.associate { char ->
                char.id to (allFieldValuesMap[char.id] ?: emptyList())
            }
            val tags = universeChars.associate { char ->
                char.id to (allTagsMap[char.id] ?: emptyList())
            }

            exportCharacterSheet(
                workbook, usedSheetNames, universe.name,
                universeChars, fields, novelMap, fieldValues, tags
            )
        }

        // 미분류 캐릭터
        val unassignedChars = allCharacters.filter { char ->
            val novel = novelMap[char.novelId]
            novel?.universeId == null
        }
        if (unassignedChars.isNotEmpty()) {
            val tags = unassignedChars.associate { char ->
                char.id to (allTagsMap[char.id] ?: emptyList())
            }
            exportCharacterSheet(
                workbook, usedSheetNames, "미분류 캐릭터",
                unassignedChars, emptyList(), novelMap, emptyMap(), tags
            )
        }
    }

    private fun exportCharacterSheet(
        workbook: XSSFWorkbook,
        usedSheetNames: MutableSet<String>,
        sheetLabel: String,
        characters: List<Character>,
        fields: List<FieldDefinition>,
        novelMap: Map<Long, Novel>,
        allFieldValues: Map<Long, List<CharacterFieldValue>>,
        allTags: Map<Long, List<CharacterTag>>
    ) {
        val novelTitles = novelMap.values.map { it.title }.distinct()
        val spec = characterSpec(fields, novelTitles)
        val sheetName = sanitizeSheetName(sheetLabel, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        characters.forEachIndexed { index, character ->
            val row = sheet.createRow(index + 1)
            val novel = character.novelId?.let { novelMap[it] }
            val fieldValues = allFieldValues[character.id] ?: emptyList()
            val fieldValueMap = fieldValues.associateBy { it.fieldDefinitionId }
            var col = 0

            // 이름
            row.createCell(col++).setCellValue(character.name)

            // 동적 필드
            for (field in fields) {
                val value = fieldValueMap[field.id]?.value ?: ""
                row.createCell(col++).setCellValue(value)
            }

            // 이미지경로 (readOnly)
            row.createCell(col++).setCellValue(character.imagePaths)

            // 작품
            row.createCell(col++).setCellValue(novel?.title ?: "")

            // 메모
            row.createCell(col++).setCellValue(character.memo)

            // 태그
            val tags = allTags[character.id] ?: emptyList()
            row.createCell(col++).setCellValue(tags.joinToString(", ") { it.tag })

            // 코드 (readOnly)
            row.createCell(col++).setCellValue(character.code)

            // 작품코드 (readOnly)
            row.createCell(col).setCellValue(novel?.code ?: "")
        }

        applySpecFormatting(sheet, spec, characters.size)
    }

    // ── 사건 연표 ──

    private suspend fun exportTimeline(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val events = db.timelineDao().getAllEventsList()
        if (events.isEmpty()) return
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        val spec = timelineSpec(novels.map { it.title })
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        // Batch load all cross-refs and characters to avoid N+1 queries
        val allCrossRefs = db.timelineDao().getAllCrossRefs()
        val eventCharIdMap = allCrossRefs.groupBy({ it.eventId }, { it.characterId })
        val allChars = db.characterDao().getAllCharactersList()
        val charMap = allChars.associateBy { it.id }

        events.forEachIndexed { index, event ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(event.year.toDouble())
            event.month?.let { row.createCell(1).setCellValue(it.toDouble()) }
            event.day?.let { row.createCell(2).setCellValue(it.toDouble()) }
            row.createCell(3).setCellValue(event.calendarType)
            row.createCell(4).setCellValue(event.description)

            val novel = event.novelId?.let { novelMap[it] }
            row.createCell(5).setCellValue(novel?.title ?: "")

            val characterNames = (eventCharIdMap[event.id] ?: emptyList()).mapNotNull { charMap[it]?.name }
            row.createCell(6).setCellValue(characterNames.joinToString(", "))

            // 관련작품코드 (readOnly)
            row.createCell(7).setCellValue(novel?.code ?: "")
        }

        applySpecFormatting(sheet, spec, events.size)
    }

    // ── 캐릭터 상태변화 ──

    private suspend fun exportStateChanges(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allChangesRaw = db.characterStateChangeDao().getAllChangesList()
        if (allChangesRaw.isEmpty()) return

        val changesByCharId = allChangesRaw.groupBy { it.characterId }
        val charIds = changesByCharId.keys
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.filter { it.id in charIds }.associateBy { it.id }
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        data class ChangeRow(val character: Character, val novelTitle: String, val change: CharacterStateChange)
        val allChanges = mutableListOf<ChangeRow>()
        for ((charId, changes) in changesByCharId) {
            val character = charMap[charId] ?: continue
            val novelTitle = character.novelId?.let { novelMap[it]?.title } ?: ""
            changes.forEach { allChanges.add(ChangeRow(character, novelTitle, it)) }
        }
        if (allChanges.isEmpty()) return

        val spec = stateChangeSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allChanges.forEachIndexed { index, (character, novelTitle, change) ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(character.name)
            row.createCell(1).setCellValue(novelTitle)
            row.createCell(2).setCellValue(change.year.toDouble())
            change.month?.let { row.createCell(3).setCellValue(it.toDouble()) }
            change.day?.let { row.createCell(4).setCellValue(it.toDouble()) }
            row.createCell(5).setCellValue(change.fieldKey)
            row.createCell(6).setCellValue(change.newValue)
            row.createCell(7).setCellValue(change.description)
            // 캐릭터코드 (readOnly)
            row.createCell(8).setCellValue(character.code)
        }

        applySpecFormatting(sheet, spec, allChanges.size)
    }

    // ── 캐릭터 관계 ──

    private suspend fun exportRelationships(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        if (allRelationships.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val spec = relationshipSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allRelationships.forEachIndexed { i, rel ->
            val row = sheet.createRow(i + 1)
            val char1 = charMap[rel.characterId1]
            val char2 = charMap[rel.characterId2]
            row.createCell(0).setCellValue(char1?.name ?: "")
            row.createCell(1).setCellValue(char2?.name ?: "")
            row.createCell(2).setCellValue(rel.relationshipType)
            row.createCell(3).setCellValue(rel.description)
            // 코드 (readOnly)
            row.createCell(4).setCellValue(char1?.code ?: "")
            row.createCell(5).setCellValue(char2?.code ?: "")
        }

        applySpecFormatting(sheet, spec, allRelationships.size)
    }

    // ── 이름 은행 ──

    private suspend fun exportNameBank(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allNames = db.nameBankDao().getAllNamesList()
        if (allNames.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val spec = nameBankSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allNames.forEachIndexed { i, entry ->
            val row = sheet.createRow(i + 1)
            val usedByChar = entry.usedByCharacterId?.let { charMap[it] }
            row.createCell(0).setCellValue(entry.name)
            row.createCell(1).setCellValue(entry.gender)
            row.createCell(2).setCellValue(entry.origin)
            row.createCell(3).setCellValue(entry.notes)
            row.createCell(4).setCellValue(if (entry.isUsed) "Y" else "N")
            row.createCell(5).setCellValue(usedByChar?.name ?: "")
            // 사용캐릭터코드 (readOnly)
            row.createCell(6).setCellValue(usedByChar?.code ?: "")
        }

        applySpecFormatting(sheet, spec, allNames.size)
    }

    companion object {
        private const val DROPDOWN_EXTRA_ROWS = 100
        private const val MAX_DROPDOWN_ROWS = 10000
    }
}
