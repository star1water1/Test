package com.novelcharacter.app.excel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import com.novelcharacter.app.util.ThemeHelper
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
    private val isExporting = java.util.concurrent.atomic.AtomicBoolean(false)

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
     * @param options 내보내기에 포함할 항목 선택
     * @param onFileReady if non-null, called with the temp file instead of opening a share sheet.
     *                    The caller is responsible for launching SAF to let the user pick a save location.
     */
    fun exportAll(options: ExportOptions = ExportOptions(), onFileReady: ((File, String) -> Unit)? = null) {
        if (!isExporting.compareAndSet(false, true)) return
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
                if (options.universes) exportUniverses(workbook, usedSheetNames)
                if (options.novels) exportNovels(workbook, usedSheetNames)
                if (options.fieldDefinitions) exportFieldDefinitions(workbook, usedSheetNames)
                if (options.characters) exportCharacters(workbook, usedSheetNames)
                if (options.timeline) exportTimeline(workbook, usedSheetNames)
                if (options.stateChanges) exportStateChanges(workbook, usedSheetNames)
                if (options.relationships) exportRelationships(workbook, usedSheetNames)
                if (options.relationshipChanges) exportRelationshipChanges(workbook, usedSheetNames)
                if (options.nameBank) exportNameBank(workbook, usedSheetNames)
                if (options.factions) exportFactions(workbook, usedSheetNames)
                if (options.factionMemberships) exportFactionMemberships(workbook, usedSheetNames)
                if (options.presetTemplates) exportUserPresetTemplates(workbook, usedSheetNames)
                if (options.searchPresets) exportSearchPresets(workbook, usedSheetNames)
                if (options.appSettings) exportAppSettings(workbook, usedSheetNames)

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val xlsxFileName = "NovelCharacter_$timestamp.xlsx"
                val xlsxFile = saveWorkbook(workbook, xlsxFileName)

                val file: File
                val fileName: String
                if (options.images) {
                    val zipFileName = "NovelCharacter_$timestamp.zip"
                    val zipFile = wrapWithImages(xlsxFile, zipFileName)
                    if (zipFile != null) {
                        file = zipFile
                        fileName = zipFileName
                        xlsxFile.delete()
                    } else {
                        // 이미지가 없으면 XLSX 그대로 사용
                        file = xlsxFile
                        fileName = xlsxFileName
                    }
                } else {
                    file = xlsxFile
                    fileName = xlsxFileName
                }

                withContext(Dispatchers.Main) {
                    if (onFileReady != null) {
                        onFileReady(file, fileName)
                    } else {
                        shareFile(file, isZip = options.images)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Export failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_failed_retry), Toast.LENGTH_LONG).show()
                }
            } finally {
                try { workbook?.close() } catch (e: Exception) { android.util.Log.w("ExcelExporter", "Failed to close workbook", e) }
                isExporting.set(false)
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
                    Toast.makeText(appContext, appContext.getString(R.string.export_save_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Synchronized
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

    private fun shareFile(file: File, isZip: Boolean = false) {
        val authority = "${appContext.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(appContext, authority, file)

        val mimeType = if (isZip) "application/zip"
            else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
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
            GuideLine("코드 컬럼 안내 (중요)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 회색 코드 컬럼은 자동 생성된 고유 식별자입니다. 수정하지 마세요."),
            GuideLine("", styles.guideBody, "• 코드가 데이터 매칭의 1순위입니다. 이름/제목은 자유롭게 변경 가능합니다."),
            GuideLine("", styles.guideBody, "• 새 행을 추가할 때는 코드를 비워두세요. 자동으로 생성됩니다."),
            GuideLine("", styles.guideBody, "• 코드가 없으면 이름 기반으로 매칭되지만, 경고가 표시됩니다."),
            GuideLine("", styles.guideBody, "• 참조 코드(작품코드, 세계관코드 등)도 동일한 규칙을 따릅니다."),
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
            GuideLine("관대한 가져오기", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 헤더 순서를 변경해도 자동으로 인식합니다."),
            GuideLine("", styles.guideBody, "• 숫자/문자 혼합, Y/N/TRUE/FALSE/1/0 모두 인식합니다."),
            GuideLine("", styles.guideBody, "• 일부 행 오류가 있어도 나머지는 정상 처리됩니다."),
            GuideLine("", styles.guideBody, "• 가져오기 결과에서 경고/오류 내역을 확인할 수 있습니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("테두리 색상", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 세계관/작품 시트에서 테두리색(HEX), 테두리두께를 설정할 수 있습니다."),
            GuideLine("", styles.guideBody, "• 작품의 테두리를 비워두면 세계관 색상을 상속합니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("주의사항", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 시트 이름을 변경하지 마세요 (가져오기 시 시트명으로 데이터를 찾습니다)"),
            GuideLine("", styles.guideBody, "• 헤더 행(1행)을 삭제하지 마세요 (컬럼 순서 변경은 가능합니다)"),
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
            row.createCell(3).setCellValue(universe.displayOrder.toDouble())
            row.createCell(4).setCellValue(universe.borderColor)
            row.createCell(5).setCellValue(universe.borderWidthDp.toDouble())
            row.createCell(6).setCellValue(universe.imagePaths)
            row.createCell(7).setCellValue(universe.imageMode)
            row.createCell(8).setCellValue(universe.customRelationshipTypes)
            row.createCell(9).setCellValue(universe.customRelationshipColors)
            universe.imageCharacterId?.let { row.createCell(10).setCellValue(it.toDouble()) }
            universe.imageNovelId?.let { row.createCell(11).setCellValue(it.toDouble()) }
            row.createCell(12).setCellValue(universe.createdAt.toDouble())
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
            row.createCell(5).setCellValue(novel.displayOrder.toDouble())
            row.createCell(6).setCellValue(novel.borderColor)
            row.createCell(7).setCellValue(novel.borderWidthDp.toDouble())
            row.createCell(8).setCellValue(novel.imagePaths)
            row.createCell(9).setCellValue(novel.imageMode)
            novel.imageCharacterId?.let { row.createCell(10).setCellValue(it.toDouble()) }
            row.createCell(11).setCellValue(if (novel.inheritUniverseBorder) "Y" else "N")
            row.createCell(12).setCellValue(if (novel.isPinned) "Y" else "N")
            novel.standardYear?.let { row.createCell(13).setCellValue(it.toDouble()) }
            row.createCell(14).setCellValue(novel.createdAt.toDouble())
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

            // 성
            row.createCell(col++).setCellValue(character.lastName)

            // 이름(First)
            row.createCell(col++).setCellValue(character.firstName)

            // 이명
            row.createCell(col++).setCellValue(character.anotherName)

            // 동적 필드 (CALCULATED 필드는 FormulaEvaluator로 실시간 계산)
            val calculatedFields = fields.filter { it.type == "CALCULATED" }
            val calculatedResults: Map<Long, String> = if (calculatedFields.isNotEmpty()) {
                val fieldKeyValues = mutableMapOf<String, String>()
                for (f in fields) {
                    val v = fieldValueMap[f.id]?.value ?: ""
                    if (v.isNotBlank()) fieldKeyValues[f.key] = v
                }
                val evaluator = com.novelcharacter.app.util.FormulaEvaluator(fieldKeyValues, fields)
                calculatedFields.mapNotNull { f ->
                    val formula = try {
                        org.json.JSONObject(f.config).optString("formula", "")
                    } catch (_: Exception) { "" }
                    if (formula.isBlank()) return@mapNotNull null
                    try {
                        val result = evaluator.evaluate(formula)
                        if (!result.isNaN() && !result.isInfinite()) {
                            f.id to if (result == result.toLong().toDouble()) result.toLong().toString()
                            else "%.2f".format(result)
                        } else null
                    } catch (_: Exception) { null }
                }.toMap()
            } else emptyMap()

            for (field in fields) {
                val value = if (field.type == "CALCULATED") {
                    calculatedResults[field.id] ?: ""
                } else {
                    fieldValueMap[field.id]?.value ?: ""
                }
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
            row.createCell(col++).setCellValue(novel?.code ?: "")

            // 정렬순서
            row.createCell(col++).setCellValue(character.displayOrder.toDouble())

            // 고정
            row.createCell(col++).setCellValue(if (character.isPinned) "Y" else "N")

            // 생성일
            row.createCell(col).setCellValue(character.createdAt.toDouble())
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
            row.createCell(8).setCellValue(event.displayOrder.toDouble())
            row.createCell(9).setCellValue(if (event.isTemporary) "Y" else "N")
            row.createCell(10).setCellValue(event.createdAt.toDouble())
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
            row.createCell(9).setCellValue(change.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allChanges.size)
    }

    // ── 캐릭터 관계 ──

    private suspend fun exportRelationships(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        if (allRelationships.isEmpty()) return

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val allFactions = db.factionDao().getAllFactions()
        val factionMap = allFactions.associateBy { it.id }

        val allUniverses = db.universeDao().getAllUniversesList()
        val allCustomTypes = allUniverses.flatMap { it.getRelationshipTypes() }
        val spec = relationshipSpec(allCustomTypes)
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
            row.createCell(4).setCellValue(rel.intensity.toDouble())
            row.createCell(5).setCellValue(if (rel.isBidirectional) "Y" else "N")
            row.createCell(6).setCellValue(rel.displayOrder.toDouble())
            // 코드 (readOnly)
            row.createCell(7).setCellValue(char1?.code ?: "")
            row.createCell(8).setCellValue(char2?.code ?: "")
            row.createCell(9).setCellValue(rel.factionId?.let { factionMap[it]?.name } ?: "")
            row.createCell(10).setCellValue(rel.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allRelationships.size)
    }

    // ── 관계 변화 ──

    private suspend fun exportRelationshipChanges(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allChanges = db.characterRelationshipChangeDao().getAllChanges()
        if (allChanges.isEmpty()) return

        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        val relMap = allRelationships.associateBy { it.id }
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val spec = relationshipChangeSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allChanges.forEachIndexed { i, rc ->
            val rel = relMap[rc.relationshipId] ?: return@forEachIndexed
            val char1 = charMap[rel.characterId1]
            val char2 = charMap[rel.characterId2]
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(char1?.name ?: "")
            row.createCell(1).setCellValue(char2?.name ?: "")
            row.createCell(2).setCellValue(rc.year.toDouble())
            rc.month?.let { row.createCell(3).setCellValue(it.toDouble()) }
            rc.day?.let { row.createCell(4).setCellValue(it.toDouble()) }
            row.createCell(5).setCellValue(rc.relationshipType)
            row.createCell(6).setCellValue(rc.description)
            row.createCell(7).setCellValue(rc.intensity.toDouble())
            row.createCell(8).setCellValue(if (rc.isBidirectional) "Y" else "N")
            rc.eventId?.let { row.createCell(9).setCellValue(it.toDouble()) }
            row.createCell(10).setCellValue(char1?.code ?: "")
            row.createCell(11).setCellValue(char2?.code ?: "")
            row.createCell(12).setCellValue(rc.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allChanges.size)
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
            row.createCell(7).setCellValue(entry.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allNames.size)
    }

    // ── 세력 ──

    private suspend fun exportFactions(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allFactions = db.factionDao().getAllFactionsList()
        if (allFactions.isEmpty()) return

        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }

        val spec = factionSpec(universes.map { it.name })
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allFactions.forEachIndexed { i, faction ->
            val row = sheet.createRow(i + 1)
            val universe = universeMap[faction.universeId]
            row.createCell(0).setCellValue(faction.name)
            row.createCell(1).setCellValue(universe?.name ?: "")
            row.createCell(2).setCellValue(universe?.code ?: "")
            row.createCell(3).setCellValue(faction.description)
            row.createCell(4).setCellValue(faction.color)
            row.createCell(5).setCellValue(faction.autoRelationType)
            row.createCell(6).setCellValue(faction.autoRelationIntensity.toDouble())
            row.createCell(7).setCellValue(faction.code)
            row.createCell(8).setCellValue(faction.displayOrder.toDouble())
            row.createCell(9).setCellValue(faction.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allFactions.size)
    }

    // ── 세력 소속 ──

    private suspend fun exportFactionMemberships(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allMemberships = db.factionMembershipDao().getAllMembershipsList()
        if (allMemberships.isEmpty()) return

        val allFactions = db.factionDao().getAllFactionsList()
        val factionMap = allFactions.associateBy { it.id }
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val spec = factionMembershipSpec(allFactions.map { it.name })
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allMemberships.forEachIndexed { i, membership ->
            val row = sheet.createRow(i + 1)
            val faction = factionMap[membership.factionId]
            val character = charMap[membership.characterId]
            row.createCell(0).setCellValue(faction?.name ?: "")
            row.createCell(1).setCellValue(character?.name ?: "")
            membership.joinYear?.let { row.createCell(2).setCellValue(it.toDouble()) }
            membership.leaveYear?.let { row.createCell(3).setCellValue(it.toDouble()) }
            val leaveTypeLabel = when (membership.leaveType) {
                "removed" -> "순수제거"
                "departed" -> "설정상탈퇴"
                else -> ""
            }
            row.createCell(4).setCellValue(leaveTypeLabel)
            row.createCell(5).setCellValue(membership.departedRelationType ?: "")
            membership.departedIntensity?.let { row.createCell(6).setCellValue(it.toDouble()) }
            // readOnly codes
            row.createCell(7).setCellValue(faction?.code ?: "")
            row.createCell(8).setCellValue(character?.code ?: "")
            row.createCell(9).setCellValue(membership.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allMemberships.size)
    }

    // ── ZIP + 이미지 래핑 ──

    private suspend fun wrapWithImages(xlsxFile: File, zipFileName: String): File? {
        val exportsDir = File(appContext.cacheDir, "exports")
        exportsDir.mkdirs()
        val zipFile = File(exportsDir, zipFileName)
        val hasImages = ImageZipHelper.wrapWithImages(xlsxFile, zipFile, db, appContext)
        return if (hasImages) zipFile else null
    }

    // ── 필드 템플릿 ──

    private suspend fun exportUserPresetTemplates(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val templates = db.userPresetTemplateDao().getAllTemplatesList()
        if (templates.isEmpty()) return

        val spec = userPresetTemplateSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        templates.forEachIndexed { i, t ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(t.name)
            row.createCell(1).setCellValue(t.description)
            row.createCell(2).setCellValue(t.fieldsJson)
            row.createCell(3).setCellValue(if (t.isBuiltIn) "Y" else "N")
            row.createCell(4).setCellValue(t.createdAt.toDouble())
            row.createCell(5).setCellValue(t.updatedAt.toDouble())
        }

        applySpecFormatting(sheet, spec, templates.size)
    }

    // ── 검색 프리셋 ──

    private suspend fun exportSearchPresets(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val presets = db.searchPresetDao().getAllPresetsList()
        if (presets.isEmpty()) return

        val spec = searchPresetSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

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

        applySpecFormatting(sheet, spec, presets.size)
    }

    // ── 앱 설정 ──

    private suspend fun exportAppSettings(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val spec = appSettingsSpec()
        val sheetName = sanitizeSheetName(spec.sheetName, usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        val themeMode = ThemeHelper.getSavedTheme(appContext)
        val row = sheet.createRow(1)
        row.createCell(0).setCellValue("theme_mode")
        row.createCell(1).setCellValue(themeMode.toDouble())

        applySpecFormatting(sheet, spec, 1)
    }

    companion object {
        private const val DROPDOWN_EXTRA_ROWS = 100
        private const val MAX_DROPDOWN_ROWS = 10000
    }
}
