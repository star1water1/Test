package com.novelcharacter.app.excel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.OpResult
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

    // XLSX 셀 규격(32,767자) 초과로 잘린 셀 수 — 내보내기 1회 단위 집계
    private var truncatedCellCount = 0

    /** 셀 한도 초과 텍스트를 잘라 기록한다 — 값 하나 때문에 전체 내보내기가 실패(POI 예외)하지 않도록. */
    private fun org.apache.poi.ss.usermodel.Cell.setTextSafe(value: String) {
        if (value.length > XLSX_CELL_LIMIT) {
            setCellValue(value.take(XLSX_CELL_LIMIT))
            truncatedCellCount++
        } else {
            setCellValue(value)
        }
    }

    /**
     * 워크북에 선택된 데이터 시트를 모두 채운다.
     *
     * 공유·저장 내보내기(exportAll)와 자동 백업(AutoBackupWorker)이 공유하는 **단일 내보내기 소스**.
     * 두 경로가 별도 export 로직을 두면 포맷이 드리프트(자동 백업이 세력관계·사건코드·커스텀필드 등을
     * 누락)하여 복원 시 데이터가 유실되므로, 반드시 이 메서드 하나만을 통해 시트를 생성한다.
     *
     * 호출 전 truncatedCellCount를 초기화하고, styles를 워크북에 바인딩한다.
     *
     * @param progress 진행 보고·취소 창구(R-26). 취소가 걸리면 [ExportCancelledException]을 던지며,
     *   반쯤 채운 워크북은 호출부가 버린다(반쪽 파일을 건네지 않는다).
     *   null이면 보고도 취소 확인도 없이 끝까지 돈다 — **자동 백업은 더 이상 그 경로가 아니다**
     *   (B-96: 워커가 stop돼도 루프가 끝까지 돌아 재시도 인스턴스와 겹쳤다. 지금은
     *   `isCancelled = { isStopped }`만 실은 싱크를 넘긴다).
     * @return 32,767자(XLSX 셀 규격) 초과로 잘린 셀 수
     */
    suspend fun populateWorkbook(
        workbook: XSSFWorkbook,
        options: ExportOptions = ExportOptions(),
        progress: ExportProgressSink? = null
    ): Int {
        truncatedCellCount = 0
        styles = ExcelStyles(workbook)
        val usedSheetNames = mutableSetOf<String>()

        // 시트 목록·순서는 [ExportSheetStep.of]가 단일 소스다 — 진행도의 총량도 같은 것을
        // 쓴다(R-26: 총량 확정 후에 띄운다). 종전 if 연쇄와 조건·순서는 그대로다.
        //
        // **빈 범주도 시트를 만든다(B-88).** 종전에는 각 export 함수가 `if (xxx.isEmpty()) return`
        // 으로 빠져나가, '전체 체크'로 내보내도 **비어 있던 종류는 시트 자체가 없었다** —
        // 그러면 엑셀에서 그 종류를 새로 적어 넣을 수단이 사라진다(개발 의도 4번 '엑셀 왕복
        // 무결성'. 사용자 지적: "시트를 안 만들면 엑셀 편집이 안 되니까"). 지금은 헤더만 있는
        // 빈 시트가 나가고, 사용자는 거기에 행을 적어 되돌려 넣는다.
        //
        // **이것은 '삭제'의 의미를 바꾸지 않는다**(선택지 ①, 사용자 판정 2026.08.02).
        // 가져오기의 덮어쓰기 가드는 "시트가 있는가"가 아니라 **"데이터 행이 1개 이상인가"**를
        // 본다(`ExcelImportService.canRestore`) — 그래서 빈 시트를 만들어도 덮어쓰기가
        // 지우던 범위는 종전 그대로이고, 엑셀에서 행을 실수로 다 지운 파일이 그 종류를
        // 통째로 없애는 일도 없다. **두 자리는 함께 움직여야 한다** — 한쪽만 바꾸면
        // 빈 시트가 곧 '전부 삭제' 지시가 된다.
        //
        // 파생·읽기 전용 시트는 대상이 아니다 — '전체 캐릭터'(U-12a)와 캐릭터 필드값
        // 오버플로는 가져오기가 읽지 않으므로 빈 채로 내보낼 이유가 없다.
        val plan = ExportSheetStep.of(options)
        progress?.onSheets?.invoke(0, plan.size)
        for ((index, step) in plan.withIndex()) {
            if (progress?.isCancelled?.invoke() == true) throw ExportCancelledException()
            when (step) {
                ExportSheetStep.INSTRUCTIONS -> exportInstructions(workbook, usedSheetNames)
                ExportSheetStep.UNIVERSES -> exportUniverses(workbook, usedSheetNames)
                ExportSheetStep.NOVELS -> exportNovels(workbook, usedSheetNames)
                ExportSheetStep.GRADE_SYSTEMS -> exportGradeSystems(workbook, usedSheetNames)
                ExportSheetStep.FIELD_DEFINITIONS -> exportFieldDefinitions(workbook, usedSheetNames)
                ExportSheetStep.FIELD_VALUE_LIBRARY -> exportFieldValueLibrary(workbook, usedSheetNames)
                ExportSheetStep.IMAGE_META -> exportImageMeta(workbook, usedSheetNames)
                ExportSheetStep.CHARACTERS -> exportCharacters(workbook, usedSheetNames)
                ExportSheetStep.TIMELINE -> exportTimeline(workbook, usedSheetNames)
                ExportSheetStep.STATE_CHANGES -> exportStateChanges(workbook, usedSheetNames)
                ExportSheetStep.RELATIONSHIPS -> exportRelationships(workbook, usedSheetNames)
                ExportSheetStep.RELATIONSHIP_CHANGES -> exportRelationshipChanges(workbook, usedSheetNames)
                ExportSheetStep.NAME_BANK -> exportNameBank(workbook, usedSheetNames)
                ExportSheetStep.FACTIONS -> exportFactions(workbook, usedSheetNames)
                ExportSheetStep.FACTION_MEMBERSHIPS -> exportFactionMemberships(workbook, usedSheetNames)
                ExportSheetStep.FACTION_RELATIONSHIPS -> exportFactionRelationships(workbook, usedSheetNames)
                ExportSheetStep.PRESET_TEMPLATES -> exportUserPresetTemplates(workbook, usedSheetNames)
                ExportSheetStep.SEARCH_PRESETS -> exportSearchPresets(workbook, usedSheetNames)
                ExportSheetStep.CHARACTER_LIST_PRESETS -> exportCharacterListPresets(workbook, usedSheetNames)
                ExportSheetStep.APP_SETTINGS -> exportAppSettings(workbook, usedSheetNames)
                ExportSheetStep.DUEL_AXES -> exportDuelAxes(workbook, usedSheetNames)
                ExportSheetStep.DUEL_MATCHES -> exportDuelMatches(workbook, usedSheetNames)
                ExportSheetStep.DUEL_VERDICTS -> exportDuelVerdicts(workbook, usedSheetNames)
            }
            progress?.onSheets?.invoke(index + 1, plan.size)
        }
        return truncatedCellCount
    }

    /**
     * @param options 내보내기에 포함할 항목 선택
     * @param onFinished if non-null, 성공/실패와 무관하게 작업 종료 시 Main에서 호출 —
     *                   호출측 진행 다이얼로그 해제용. 지정 시 시작 Toast는 생략된다(중복 안내 방지).
     * @param progress if non-null, 시트·이미지 순회의 진행을 보고하고 취소를 받는다(R-26).
     *                 취소하면 산출물을 만들지 않고 임시 파일을 지운 뒤 '취소했습니다'만 알린다.
     * @param onFileReady if non-null, called with the temp file instead of opening a share sheet.
     *                    The caller is responsible for launching SAF to let the user pick a save location.
     */
    fun exportAll(
        options: ExportOptions = ExportOptions(),
        onFinished: (() -> Unit)? = null,
        progress: ExportProgressSink? = null,
        onFileReady: ((File, String) -> Unit)? = null
    ) {
        if (!isExporting.compareAndSet(false, true)) return
        ensureActiveScope().launch {
            if (onFinished == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_preparing), Toast.LENGTH_SHORT).show()
                }
            }
            var workbook: XSSFWorkbook? = null
            // 취소·실패 시 지울 산출물. 사용자에게 건넨 뒤에는 null로 되돌려 놓는다 —
            // 그때부터는 공유 시트·SAF가 쓰는 파일이라 우리가 지울 것이 아니다.
            var orphanFile: File? = null
            try {
                workbook = XSSFWorkbook()
                populateWorkbook(workbook, options, progress)

                // 내보내기 요약(시트/행 건수) — 사용 안내 시트는 데이터가 아니므로 제외
                var exportedSheets = 0
                var exportedRows = 0
                for (i in 0 until workbook.numberOfSheets) {
                    val s = workbook.getSheetAt(i)
                    if (s.sheetName == "사용 안내") continue
                    exportedSheets++
                    exportedRows += maxOf(0, s.physicalNumberOfRows - 1)
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val xlsxFileName = "NovelCharacter_$timestamp.xlsx"
                val xlsxFile = saveWorkbook(workbook, xlsxFileName)
                orphanFile = xlsxFile

                val file: File
                val fileName: String
                var imageReport = ImageZipReport.NOT_REQUESTED
                if (options.images) {
                    val zipFileName = "NovelCharacter_$timestamp.zip"
                    val wrapped = wrapWithImages(xlsxFile, zipFileName, progress)
                    imageReport = wrapped.second
                    val zipFile: File? = wrapped.first
                    if (zipFile != null) {
                        file = zipFile
                        fileName = zipFileName
                        xlsxFile.delete()
                    } else {
                        // 담을 이미지가 없으면 XLSX 그대로 사용 — 제외 사유는 아래에서 반드시 통보한다
                        file = xlsxFile
                        fileName = xlsxFileName
                    }
                } else {
                    file = xlsxFile
                    fileName = xlsxFileName
                }
                orphanFile = file

                val imageNotice = buildImageNotice(imageReport, options.isCompleteBackup)
                val imageDetail = buildImageDetail(imageReport)
                // 이력 한 줄만 봐도 백업이 불완전함을 알 수 있게 요약에 누락 건수를 붙인다
                val exportSummary = appContext.getString(R.string.result_excel_exported, exportedSheets, exportedRows) +
                    if (imageReport.hasLoss) appContext.getString(R.string.export_images_summary_suffix, imageReport.excludedCount) else ""
                withContext(Dispatchers.Main) {
                    if (truncatedCellCount > 0) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.export_cells_truncated, truncatedCellCount),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // 여기서부터 파일은 공유 시트·SAF의 것이다 — 아래 catch가 지우면 안 된다
                    orphanFile = null
                    if (onFileReady != null) {
                        // 저장(SAF) 모드: 실제 완료는 writeToUri에서 통보 — 여기선 이력만 기록
                        onFileReady(file, fileName)
                    } else {
                        // 공유 모드: 공유 시트가 열리기 전 요약 통보
                        Toast.makeText(appContext, exportSummary, Toast.LENGTH_SHORT).show()
                        // 확장자는 실제 산출물에서 파생 — 이미지 0장이면 .xlsx인데 zip MIME로 공유되던 오류 제거
                        shareFile(file, isZip = fileName.endsWith(".zip", ignoreCase = true))
                    }
                    // 이미지 고지는 마지막에 — 공유 시트/SAF가 뜬 뒤에도 화면 위에 남아 읽히게 한다
                    if (imageNotice != null) {
                        Toast.makeText(appContext, imageNotice, Toast.LENGTH_LONG).show()
                    }
                }
                logExportResult(OpResult.success(OpResult.CAT_EXCEL, exportSummary,
                    listOfNotNull(
                        if (truncatedCellCount > 0) appContext.getString(R.string.export_cells_truncated, truncatedCellCount) else null,
                        imageDetail
                    ).joinToString("\n").ifBlank { null }))
            } catch (e: ExportCancelledException) {
                // 취소는 실패가 아니다 — 반쪽 파일만 지우고 사실대로 한 줄 알린다.
                // (반쪽을 건네면 그 파일로 복원할 때 조용히 유실된다 — R-26 후단)
                orphanFile?.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_cancelled), Toast.LENGTH_SHORT).show()
                }
                logExportResult(OpResult.success(OpResult.CAT_EXCEL,
                    appContext.getString(R.string.result_excel_export_cancelled)))
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Export failed", e)
                orphanFile?.delete()
                // 공간 부족은 별도 갈래로 말한다(설계 D7 · R-17) — "다시 시도하세요"는
                // 공간이 없는 사용자에게 아무것도 알려 주지 않는 안내다.
                val outOfSpace = ExportSpace.isOutOfSpace(e)
                val message = if (outOfSpace) {
                    val needMb = ExportSpace.requiredMegabytes(estimateExportBytes(options))
                    appContext.getString(R.string.export_failed_no_space, needMb)
                } else {
                    appContext.getString(R.string.export_failed_retry)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
                logExportResult(OpResult.failure(OpResult.CAT_EXCEL,
                    appContext.getString(R.string.result_excel_export_failed),
                    listOfNotNull(if (outOfSpace) message else null, e.message).joinToString("\n").ifBlank { null }))
            } finally {
                try { workbook?.close() } catch (e: Exception) { android.util.Log.w("ExcelExporter", "Failed to close workbook", e) }
                isExporting.set(false)
                if (onFinished != null) {
                    // 스코프 취소(화면 이탈) 중에도 다이얼로그 해제는 보장한다
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { onFinished() }
                }
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
                    logExportResult(OpResult.failure(OpResult.CAT_EXCEL,
                        appContext.getString(R.string.result_excel_save_failed)))
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
                logExportResult(OpResult.success(OpResult.CAT_EXCEL,
                    appContext.getString(R.string.result_excel_saved)))
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Save to URI failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_save_failed), Toast.LENGTH_LONG).show()
                }
                logExportResult(OpResult.failure(OpResult.CAT_EXCEL,
                    appContext.getString(R.string.result_excel_save_failed), e.message))
            }
        }
    }

    /** 내보내기 결과를 작업 이력에 기록한다(즉시 알림은 Toast/공유시트가 담당). */
    private fun logExportResult(result: OpResult) {
        (appContext as? NovelCharacterApp)?.operationLogRepository?.logAsync(result)
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
        val joinedOptions = options.joinToString(", ")
        validation.showErrorBox = true
        validation.errorStyle = DataValidation.ErrorStyle.WARNING
        validation.createErrorBox(
            appContext.getString(R.string.export_validation_error_title),
            appContext.getString(R.string.export_validation_error_message, joinedOptions)
        )
        validation.showPromptBox = true
        validation.createPromptBox(appContext.getString(R.string.export_validation_prompt_title), joinedOptions)
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

    // 시트명 배정은 SheetSpec.assignSheetName이 단일 소스다 — 예약명은 소유자만 가질 수 있고,
    // 세계관 캐릭터 시트는 ownerOf 없이 부르므로 어떤 예약명도 차지하지 못한다(4-5 규약).

    // ── 사용 안내 시트 ──

    private data class GuideLine(val section: String, val style: XSSFCellStyle, val text: String)

    private fun exportInstructions(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val sheetName = assignSheetName(GUIDE_SHEET_NAME, usedSheetNames, ownerOf = GUIDE_SHEET_NAME)
        val sheet = workbook.createSheet(sheetName)

        val lines = listOf(
            GuideLine("", styles.guideTitle, "NovelCharacter 엑셀 파일 편집 안내"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("색상 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "■ 파란 헤더 = 편집 가능한 일반 컬럼"),
            GuideLine("", styles.guideBody, "■ 빨간 헤더 = 필수 입력 컬럼 (비워두면 해당 행 무시됨)"),
            GuideLine("", styles.guideBody, "■ 회색 헤더/셀 = 앱이 채우는 열 (그대로 두세요 — 예외는 아래 '코드 컬럼 안내')"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("길이 제한", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 셀당 최대 32,767자(엑셀 규격) — 초과분은 내보내기 시 잘려 기록됩니다."),
            GuideLine("", styles.guideBody, "• 가져오기도 동일하게 32,767자까지 저장됩니다 — 내보낸 파일을 그대로 들여오면 잘리지 않습니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("빈 시트 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "아직 데이터가 없는 종류도 머리글만 있는 빈 시트로 함께 나갑니다. 여기에 행을 적어 새 데이터를 만듭니다."),
            GuideLine("", styles.guideBody, "• 캐릭터가 없는 세계관도 시트가 만들어집니다. 그 세계관의 필드가 열로 준비되어 있습니다."),
            GuideLine("", styles.guideBody, "• 빈 시트는 '엑셀에 없는 항목 삭제'의 대상이 아닙니다 — 행이 하나도 없는 시트로는 기존 데이터를 지우지 않습니다."),
            GuideLine("", styles.guideBody, "• 그래서 실수로 행을 모두 지운 파일을 들여와도 그 종류가 통째로 사라지지 않습니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("코드 컬럼 안내 (중요)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 회색 코드 컬럼은 자동 생성된 고유 식별자입니다. 수정하지 마세요."),
            GuideLine("", styles.guideBody, "• 코드가 데이터 매칭의 1순위입니다. 이름/제목은 자유롭게 변경 가능합니다."),
            GuideLine("", styles.guideBody, "• 새 행을 추가할 때는 코드를 비워 두세요. 자동으로 생성됩니다."),
            GuideLine("", styles.guideBody, "• 코드가 없으면 이름 기반으로 매칭되지만, 경고가 표시됩니다."),
            GuideLine("", styles.guideBody, "• 참조 코드(작품코드, 세계관코드 등)도 동일한 규칙을 따릅니다."),
            GuideLine("", styles.guideBody, "• 단, 참조 코드 열은 이름이 겹칠 때 직접 채워 대상을 확정할 수 있습니다 (코드가 이름보다 우선)."),
            GuideLine("", styles.guideBody, "  예) 세계관이 다른 동명 세력이 둘 이상이면 '세력 소속'·'세력 관계' 시트의 세력코드 열에"),
            GuideLine("", styles.guideBody, "  '세력' 시트의 코드 값을 붙여넣으세요. (그 행 자신의 '코드' 열은 여전히 수정하지 마세요 — 행의 정체성입니다)"),
            GuideLine("", styles.guideBody, "• 사건 연표/상태변화/관계 변화 시트에도 코드 열이 있습니다. 지우지 마세요 —"),
            GuideLine("", styles.guideBody, "  설명·연도·값을 편집해도 같은 항목으로 인식하는 기준입니다. (구버전 파일도 계속 가져올 수 있습니다)"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("시트별 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 세계관: 코드로 기존 데이터 매칭. 코드 없을 시 이름으로 매칭"),
            GuideLine("", styles.guideBody, "  '커스텀관계유형'은 JSON 배열([\"연인\",\"라이벌\"]), '커스텀관계색상'은 JSON 객체({\"연인\":\"#E91E63\"})입니다."),
            GuideLine("", styles.guideBody, "  쉼표 구분(연인, 라이벌 / 연인=#E91E63)으로 적어도 해석하지만 경고가 표시됩니다."),
            GuideLine("", styles.guideBody, "  비우면 기본 관계 유형·색상으로 돌아갑니다. 해석할 수 없으면 적용하지 않고 기존 설정을 유지합니다."),
            GuideLine("", styles.guideBody, "• 작품: 코드로 매칭. 코드 없을 시 제목+세계관으로 매칭"),
            GuideLine("", styles.guideBody, "• 필드 정의: 세계관+필드키+대상으로 매칭. 타입은 드롭다운에서 선택. 대상이 사건·작품이면 그 종류의 필드"),
            GuideLine("", styles.guideBody, "  'AI추천'(Y/개별만/N)·'필드설명' 열을 채워 다시 가져오면 AI 추천 동작에 반영됩니다"),
            GuideLine("", styles.guideBody, "  (Y=일괄 추천과 ✨ 모두, 개별만=✨ 버튼으로 요청할 때만, N=AI가 건드리지 않음)"),
            GuideLine("", styles.guideBody, "  (AI추천 빈칸=Y, 필드설명 빈칸=설명 없음. 두 열을 지운 파일은 기존 설정을 유지합니다)"),
            GuideLine("", styles.guideBody, "  '등급체계' 열은 GRADE 필드가 참조하는 '등급 체계' 시트의 체계명입니다 — 아래 그 시트 안내 참조"),
            GuideLine("", styles.guideBody, "• 등급 체계: 한 행이 등급 하나입니다. 세계관+체계명(코드 우선)으로 묶어 같은 체계로 인식합니다"),
            GuideLine("", styles.guideBody, "• 캐릭터 시트 (세계관 이름): 코드로 매칭. 코드 없을 시 이름+작품으로 매칭"),
            GuideLine("", styles.guideBody, "• 사건 연표: 코드로 매칭 (코드 없을 시 연도+설명). 관련 캐릭터는 쉼표로 구분. 세계관 열이 소속 기준"),
            GuideLine("", styles.guideBody, "• 필드 템플릿: '생성일'로 매칭합니다 — 이름이 같은 템플릿이 여럿 있을 수 있어 지우지 마세요"),
            GuideLine("", styles.guideBody, "  (생성일이 남아 있으면 이름만 바꿔도 같은 템플릿으로 인식합니다)"),
            GuideLine("", styles.guideBody, "• 검색 프리셋: 이름으로 매칭. 정렬모드는 ${SearchPreset.SORT_MODES.joinToString("/")} 만 인식하며,"),
            GuideLine("", styles.guideBody, "  그 외 값은 경고 후 ${SearchPreset.SORT_RELEVANCE}로 처리됩니다. '기본값' Y는 앱 기본 제공 프리셋(수정·삭제 불가)을 뜻합니다"),
            GuideLine("", styles.guideBody, "• 목록 프리셋: 이름으로 매칭. 작품코드목록은 작품 시트의 코드 값을 쉼표로 나열"),
            GuideLine("", styles.guideBody, "• 캐릭터 관계: 관계 유형은 드롭다운에서 선택. '세력' 열은 편집 가능합니다 — 비우면 자동 관계가 수동 관계로 풀리고,"),
            GuideLine("", styles.guideBody, "  채우면 그 세력의 자동 관계가 되어 세력 삭제·멤버 탈퇴 시 함께 삭제될 수 있습니다 (대상은 '세력코드'가 우선)"),
            GuideLine("", styles.guideBody, "  관계의 '코드' 열을 지우지 마세요 — 코드가 있으면 관계 유형을 고쳐도 같은 관계로 인식합니다"),
            GuideLine("", styles.guideBody, "  (코드를 비우고 유형만 바꾸면 새 관계가 생기고 기존 관계가 그대로 남습니다)"),
            GuideLine("", styles.guideBody, "• 관계 변화: '관계코드'가 이 이력이 붙은 관계를 가리킵니다 ('부모관계유형'은 코드 없는 구버전 파일용 폴백,"),
            GuideLine("", styles.guideBody, "  같은 행의 '관계 유형'은 그 시점의 유형이라 서로 다른 값입니다)"),
            GuideLine("", styles.guideBody, "• 세력 소속: 같은 세력·캐릭터의 이력이 여러 건일 수 있어 '생성일'로 구분합니다 — 지우지 마세요"),
            GuideLine("", styles.guideBody, "• 세력 이름은 세계관마다 겹칠 수 있습니다. 코드 우선, 코드가 없으면 캐릭터(세력 관계는 상대 세력)의"),
            GuideLine("", styles.guideBody, "  세계관으로 좁혀 찾고, 그래도 동명이 남으면 그 행은 건너뛰고 세력코드 열을 채우라고 안내합니다"),
            GuideLine("", styles.guideBody, "• 이름 은행: 이름+성별로 매칭. 사용여부는 Y/N"),
            GuideLine("", styles.guideBody, "• 이미지: '태그'와 '링크그룹' 열을 직접 편집할 수 있습니다 (파일명은 앱이 채우는 열입니다)"),
            GuideLine("", styles.guideBody, "  링크그룹은 같은 문자열을 적은 행끼리 한 묶음이 됩니다 — 아무 이름이나 써도 되고, 두 장 이상일 때만 묶입니다"),
            GuideLine("", styles.guideBody, "  칸을 비우면 그 이미지의 링크가 풀립니다. 'char:'로 시작하는 값은 캐릭터 자동 링크라"),
            GuideLine("", styles.guideBody, "  가져온 뒤 현재 배정 기준으로 다시 계산됩니다 (직접 적을 필요가 없습니다)"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("필드 정의 — 타입별 설정 가이드", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "설정(JSON) 컬럼에 아래 형식으로 입력하세요. 비워두면 기본값이 적용됩니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[TEXT] 텍스트 — 자유 입력 필드"),
            GuideLine("", styles.guideBody, "  설정 예: {\"semanticRole\":\"height\"}  또는  {\"semanticRole\":\"age\"}"),
            GuideLine("", styles.guideBody, "  semanticRole 옵션: height(키), age(나이), birth_year(출생연도), birth_date(생일, placeholder로 형식 지정)"),
            GuideLine("", styles.guideBody, "  생일 예: {\"semanticRole\":\"birth_date\",\"placeholder\":\"MM-DD\"}"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[NUMBER] 숫자 — 숫자만 입력 가능"),
            GuideLine("", styles.guideBody, "  별도 설정 없이 사용 가능"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[SELECT] 단일 선택 — 드롭다운에서 하나 선택"),
            GuideLine("", styles.guideBody, "  설정 예: {\"options\":[\"남\",\"여\",\"?\"]}"),
            GuideLine("", styles.guideBody, "  설정 예: {\"options\":[\"생존\",\"사망\",\"불명\"]}"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[MULTI_TEXT] 복수 텍스트 — 쉼표(,)로 구분하여 여러 값 입력"),
            GuideLine("", styles.guideBody, "  별도 설정 없이 사용 가능"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[GRADE] 등급 — 등급 라벨과 수치를 연결"),
            GuideLine("", styles.guideBody, "  설정 예: {\"grades\":{\"C\":1,\"B\":2,\"A\":3,\"S\":4},\"allowNegative\":false}"),
            GuideLine("", styles.guideBody, "  grades: 등급명과 수치의 대응 (필수). allowNegative: 음수 허용 여부 (기본 false)"),
            GuideLine("", styles.guideBody, "  여러 필드가 같은 등급 구성을 쓰면 '등급체계' 열로 체계를 참조하세요 (아래 '등급 체계' 시트 안내)"),
            GuideLine("", styles.guideBody, "  gradeOverrides: 체계를 참조하는 필드가 일부 등급의 수치만 다르게 쓸 때의 대응 (선택)"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[CALCULATED] 자동 계산 — 다른 필드값을 참조하여 자동 산출"),
            GuideLine("", styles.guideBody, "  설정 예: {\"formula\":\"field('strength')+field('agility')\"}"),
            GuideLine("", styles.guideBody, "  field('필드키')로 다른 필드 참조. +, -, *, / 및 max(), min() 사용 가능"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[BODY_SIZE] 신체 사이즈 — 구분자로 연결된 수치 입력 (예: 90-60-90)"),
            GuideLine("", styles.guideBody, "  설정 예: {\"separator\":\"-\"}"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("", styles.guideBody, "[공통 옵션] 모든 타입에 추가 가능:"),
            GuideLine("", styles.guideBody, "  • linkageRule: \"birth_anchor\" 또는 \"age_anchor\" — 나이/출생연도 자동 연동"),
            GuideLine("", styles.guideBody, "  • percentile: {\"enabled\":true,\"scopes\":[\"세계관명\"]} — 백분위 통계 활성화"),
            GuideLine("", styles.guideBody, ""),
            GuideLine("관대한 가져오기", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 헤더 순서를 변경해도 자동으로 인식합니다."),
            GuideLine("", styles.guideBody, "• 숫자/문자 혼합, Y/N/TRUE/FALSE/1/0 모두 인식합니다."),
            GuideLine("", styles.guideBody, "• 일부 행 오류가 있어도 나머지는 정상 처리됩니다."),
            GuideLine("", styles.guideBody, "• 헤더 이름을 바꾸면 그 열은 인식되지 않으며, 가져오기 결과에 '인식하지 못한 열'로 보고됩니다."),
            GuideLine("", styles.guideBody, "• 열을 통째로 지우면 해당 항목은 기존 값이 유지되고, 열은 두되 칸을 비우면 값이 지워집니다."),
            GuideLine("", styles.guideBody, "• 가져오기 결과에서 경고/오류 내역을 확인할 수 있습니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("'캐릭터 필드값' 시트", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 캐릭터 시트는 그 시트 세계관의 필드만 열로 만듭니다. 미분류 캐릭터의 필드값이나"),
            GuideLine("", styles.guideBody, "  다른 세계관 필드를 가리키는 잔여 값은 이 시트가 **유일한 보관처**입니다."),
            GuideLine("", styles.guideBody, "• 정체성은 캐릭터코드 + 세계관 + 필드키입니다 — 이 열들을 수정하면 값이 다른 곳에 붙습니다."),
            GuideLine("", styles.guideBody, "• '값' 칸을 비우면 그 값이 삭제됩니다. 행을 지워도 값은 지워지지 않습니다(업서트 전용)."),
            GuideLine("", styles.guideBody, "• 같은 항목이 캐릭터 시트에도 있으면 캐릭터 시트가 우선하며 이 시트의 행은 무시됩니다."),
            GuideLine("", styles.guideBody, "• 캐릭터를 다시 작품에 배정하면 값이 캐릭터 시트로 옮겨가 이 시트에서 사라집니다(정상)."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("'전체 캐릭터' 시트 (읽기 전용)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "모든 세계관의 캐릭터를 한 장에 모은 시트입니다. 전체 인원에 정렬·필터·피벗을 걸 때 쓰세요."),
            GuideLine("", styles.guideBody, "• 가져오기는 이 시트를 읽지 않습니다. 값을 고치려면 세계관 이름의 캐릭터 시트에서 고치세요."),
            GuideLine("", styles.guideBody, "• 그래서 이 시트는 지우거나 이름을 바꿔도 데이터에 영향이 없습니다."),
            GuideLine("", styles.guideBody, "• 필드 열은 여러 세계관이 함께 쓰는 필드만 실립니다(열 이름에 필드키를 함께 적습니다)."),
            GuideLine("", styles.guideBody, "  한 세계관에만 있는 필드는 그 세계관의 캐릭터 시트에 있습니다."),
            GuideLine("", styles.guideBody, "• 세계관 칸이 빈 행은 미분류 캐릭터입니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("'등급 체계' 시트 (등급 구성 공유)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "등급(GRADE) 필드 여러 개가 같은 등급 구성을 쓰도록 세계관 단위로 묶는 시트입니다. 한 행이 등급 하나입니다."),
            GuideLine("", styles.guideBody, "• 체계는 세계관+체계명으로 묶입니다('코드'가 있으면 코드 우선). 행을 추가해 등급을, 체계명을 바꿔 새 체계를 만듭니다."),
            GuideLine("", styles.guideBody, "• '필드 정의' 시트의 '등급체계' 열에 체계명을 적으면 그 필드가 체계를 참조합니다 — 체계를 고치면 참조하는 필드 전체에 반영됩니다."),
            GuideLine("", styles.guideBody, "• 참조하는 필드의 설정(JSON) grades는 실제 적용값입니다. 체계 기본과 다른 수치는 필드별 재정의(gradeOverrides)로 남습니다."),
            GuideLine("", styles.guideBody, "• '등급체계' 칸을 비우면 그 필드는 체계와 무관한 독자 등급 표가 됩니다(표 내용은 유지 — 코드 칸이 남아 있어도 이름 칸이 기준입니다). 열을 통째로 지우면 기존 참조가 유지됩니다."),
            GuideLine("", styles.guideBody, "• 가리키는 체계가 파일에도 앱에도 없으면 거부하지 않고 독자 표로 들여온 뒤 결과에 알립니다."),
            GuideLine("", styles.guideBody, "• 이 시트의 '기본숫자'만 고쳐 전체 파일을 다시 가져오면, 참조 필드의 설정(JSON) grades가 내보낼 때의 실제 적용값 그대로라 그 값이 재정의로 남습니다."),
            GuideLine("", styles.guideBody, "  기본숫자 변경을 필드까지 반영하려면 '필드 정의' 시트를 뺀 파일로 가져오거나, 해당 필드의 설정(JSON) grades 값도 함께 고치세요."),
            GuideLine("", styles.guideBody, "• 체계에서 등급 행을 지우면 참조 필드의 그 등급도 빠집니다. 캐릭터에 저장된 값은 지워지지 않고 해석만 빠집니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("'필드 데이터' 시트 (값 정리)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "필드마다 실제로 쓰인 값이 모이는 시트입니다. 여기서 정리한 표기가 앱의 자동완성·통계·검색에 함께 반영됩니다."),
            GuideLine("", styles.guideBody, "• '표시라벨'·'별칭(콤마구분)'·'카테고리'·'설명'·'숨김' 열을 채워 다시 가져오면 그대로 반영됩니다."),
            GuideLine("", styles.guideBody, "• 값이 많으면 앱에서 하나씩 여는 것보다 이 시트에서 한 번에 채우는 편이 빠릅니다."),
            GuideLine("", styles.guideBody, "• 별칭은 '데이터에 있는 다른 표기 → 이 값'입니다."),
            GuideLine("", styles.guideBody, "  예) 값 '서울'의 별칭에 '서울시, 서울특별시'를 적으면 통계와 검색이 셋을 하나로 묶습니다."),
            GuideLine("", styles.guideBody, "• '표시라벨'은 통계·카드에 보이는 이름이고, '값'은 캐릭터에 저장된 원래 표기입니다."),
            GuideLine("", styles.guideBody, "• '숨김' Y는 입력 제안에서만 빼는 표시입니다. 통계에는 그대로 들어갑니다."),
            GuideLine("", styles.guideBody, "• 기존 값은 코드로 찾고, 코드가 없으면 세계관+필드키+대상+값으로 찾습니다."),
            GuideLine("", styles.guideBody, "• '필드명'·'사용횟수'·'코드'는 앱이 채우는 열입니다. 고쳐도 반영되지 않습니다."),
            GuideLine("", styles.guideBody, "• '출처'는 고칠 수 있습니다. MANUAL로 두면 '미사용 자동수집 정리'에서 빠집니다."),
            GuideLine("", styles.guideBody, "• '값' 칸을 고치면 이름 변경으로 보고 기존 값을 별칭으로 남깁니다."),
            GuideLine("", styles.guideBody, "  캐릭터에 저장된 값까지 함께 바꾸려면 앱의 값 이름 변경을 쓰세요."),
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

        // imageCharacterId/imageNovelId → code 해석용 맵
        val charCodeMap = db.characterDao().getAllCharactersList().associate { it.id to it.code }
        val novelCodeMap = db.novelDao().getAllNovelsList().associate { it.id to it.code }

        val spec = universeSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        universes.forEachIndexed { index, universe ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(universe.name)
            row.createCell(1).setTextSafe(universe.description)
            row.createCell(2).setTextSafe(universe.code)
            row.createCell(3).setCellValue(universe.displayOrder.toDouble())
            row.createCell(4).setTextSafe(universe.borderColor)
            row.createCell(5).setCellValue(universe.borderWidthDp.toDouble())
            row.createCell(6).setTextSafe(universe.imagePaths)
            row.createCell(7).setTextSafe(universe.imageMode)
            row.createCell(8).setTextSafe(universe.customRelationshipTypes)
            row.createCell(9).setTextSafe(universe.customRelationshipColors)
            universe.imageCharacterId?.let { id -> charCodeMap[id]?.let { row.createCell(10).setTextSafe(it) } }
            universe.imageNovelId?.let { id -> novelCodeMap[id]?.let { row.createCell(11).setTextSafe(it) } }
            row.createCell(12).setCellValue(universe.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, universes.size)
    }

    // ── 작품 ──

    private suspend fun exportNovels(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val novels = db.novelDao().getAllNovelsList()
        val universes = db.universeDao().getAllUniversesList()

        val universeMap = universes.associateBy { it.id }
        val charCodeMap = db.characterDao().getAllCharactersList().associate { it.id to it.code }

        // 작품 커스텀 필드 (확-3) — 헤더 규칙은 EntityFieldHeaders 단일 소스이고
        // 가져오기가 같은 규칙의 역함수로 되짚는다(연표 시트의 사건 필드 열과 같은 방식).
        val novelFields = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_NOVEL)
        val novelFieldColumns = EntityFieldHeaders.headersFor(
            novelFields,
            universeMap.mapValues { (_, u) -> u.name }
        )
        val novelFieldValuesByNovel = db.novelFieldValueDao().getAllValuesList().groupBy { it.novelId }

        val spec = novelSpec(universes.map { it.name }, novelFieldColumns.map { it.second })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        novels.forEachIndexed { index, novel ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(novel.title)
            row.createCell(1).setTextSafe(novel.description)
            val universe = novel.universeId?.let { universeMap[it] }
            row.createCell(2).setTextSafe(universe?.name ?: "")
            row.createCell(3).setTextSafe(novel.code)
            row.createCell(4).setTextSafe(universe?.code ?: "")
            row.createCell(5).setCellValue(novel.displayOrder.toDouble())
            row.createCell(6).setTextSafe(novel.borderColor)
            row.createCell(7).setCellValue(novel.borderWidthDp.toDouble())
            row.createCell(8).setTextSafe(novel.imagePaths)
            row.createCell(9).setTextSafe(novel.imageMode)
            novel.imageCharacterId?.let { id -> charCodeMap[id]?.let { row.createCell(10).setTextSafe(it) } }
            row.createCell(11).setTextSafe(if (novel.inheritUniverseBorder) "Y" else "N")
            row.createCell(12).setTextSafe(if (novel.isPinned) "Y" else "N")
            novel.standardYear?.let { row.createCell(13).setCellValue(it.toDouble()) }
            row.createCell(14).setCellValue(novel.createdAt.toDouble())

            // 작품 커스텀 필드 값 (확-3) — 열이 없으면 내보내기에서 값이 유실된다(개발 의도 4)
            val fieldValues = novelFieldValuesByNovel[novel.id]?.associateBy { it.fieldDefinitionId } ?: emptyMap()
            novelFieldColumns.forEachIndexed { fi, (fieldDef, _) ->
                fieldValues[fieldDef.id]?.let { row.createCell(15 + fi).setTextSafe(it.value) }
            }
        }

        applySpecFormatting(sheet, spec, novels.size)
    }

    // ── 필드 정의 ──

    private suspend fun exportFieldDefinitions(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val allFields = mutableListOf<Pair<Long, FieldDefinition>>()
        for (universe in universes) {
            // **모든 종류**를 왕복한다(캐릭터·사건·작품) — 정의가 파일에 없으면 신규 기기
            // 복원 시 그 종류의 필드값이 통째로 유실된다(대상 열로 구분). 종류를 늘릴 때
            // 여기를 잊으면 새 종류만 조용히 빠진다(R-29) — 그래서 전 종류 조회를 쓴다.
            val fields = db.fieldDefinitionDao().getFieldsByUniverseAllTypes(universe.id)
            fields.forEach { allFields.add(universe.id to it) }
        }

        // 등급 체계 참조(U-1)는 전용 열로만 나간다 — code → 체계로 풀어 이름·코드를 싣는다.
        val systemsByCode = db.gradeSystemDao().getAllList().associateBy { it.code }
        val spec = fieldDefinitionSpec(
            universes.map { it.name },
            systemsByCode.values.map { it.name }.distinct()
        )
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allFields.forEachIndexed { index, (universeId, field) ->
            val universe = universeMap[universeId]
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(universe?.name ?: "")
            row.createCell(1).setTextSafe(field.key)
            row.createCell(2).setTextSafe(field.name)
            row.createCell(3).setTextSafe(field.type)
            // AI추천·필드설명·등급체계는 전용 열로만 나간다 — 같은 사실을 두 벌 두지 않는다.
            // stripPortableKeys는 문자열 사본 변환이라 DB config(월드패키지의 원천)는 그대로다.
            row.createCell(4).setTextSafe(FieldConfigColumns.stripPortableKeys(field.config))
            row.createCell(5).setTextSafe(field.groupName)
            row.createCell(6).setCellValue(field.displayOrder.toDouble())
            row.createCell(7).setTextSafe(if (field.isRequired) "Y" else "N")
            // 3단(B-80) — Y/개별만/N. 값의 단일 소스는 FieldConfigColumns다(드롭다운도 같은 목록을 쓴다).
            row.createCell(8).setTextSafe(FieldConfigColumns.aiCellOf(field.config))
            row.createCell(9).setTextSafe(
                com.novelcharacter.app.data.model.FieldDescription.fromConfig(field.config)
            )
            row.createCell(10).setTextSafe(universe?.code ?: "")
            row.createCell(11).setTextSafe(FieldValueSheetMapper.entityLabel(field.entityType))
            // 참조가 해석되지 않으면(체계가 이미 삭제된 잔재) 빈칸으로 내보낸다 — 그대로 다시
            // 들이면 독자 표 강등과 같은 결과라, 파일이 앱보다 더 넓은 약속을 하지 않는다.
            val refSystem = com.novelcharacter.app.data.model.GradeSystemRef.codeFromConfig(field.config)
                ?.let { systemsByCode[it] }
            row.createCell(12).setTextSafe(refSystem?.name ?: "")
            row.createCell(13).setTextSafe(refSystem?.code ?: "")
        }

        applySpecFormatting(sheet, spec, allFields.size)
    }

    // ── 등급 체계 (U-1) ──

    private suspend fun exportGradeSystems(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val systems = db.gradeSystemDao().getAllList()

        val spec = gradeSystemSpec(universes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        var rowIndex = 1
        for (system in systems) {
            val universe = universeMap[system.universeId]
            // 행 순서는 숫자 오름차순 — 앱의 등급 순서 파생 규칙과 같은 모양으로 내보낸다.
            val grades = com.novelcharacter.app.data.model.GradeSystemRef.gradesFromJson(system.gradesJson)
                .entries.sortedBy { it.value }
            for ((label, value) in grades) {
                val row = sheet.createRow(rowIndex++)
                row.createCell(0).setTextSafe(universe?.name ?: "")
                row.createCell(1).setTextSafe(system.name)
                row.createCell(2).setTextSafe(label)
                row.createCell(3).setCellValue(value)
                row.createCell(4).setTextSafe(universe?.code ?: "")
                row.createCell(5).setTextSafe(system.code)
            }
        }

        applySpecFormatting(sheet, spec, rowIndex - 1)
    }

    // ── 필드 데이터 라이브러리 (값 카탈로그 — 별칭·라벨·카테고리 왕복) ──

    private suspend fun exportFieldValueLibrary(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val fieldsById = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
        val entries = db.fieldValueEntryDao().getAllList()

        val spec = fieldValueLibrarySpec(universes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        var rowIndex = 1
        for (entry in entries) {
            val fd = fieldsById[entry.fieldDefinitionId] ?: continue
            val universe = universeMap[fd.universeId]
            val row = sheet.createRow(rowIndex++)
            row.createCell(0).setTextSafe(universe?.name ?: "")
            row.createCell(1).setTextSafe(fd.key)
            row.createCell(2).setTextSafe(fd.name)
            row.createCell(3).setTextSafe(FieldValueSheetMapper.entityLabel(fd.entityType))
            row.createCell(4).setTextSafe(entry.value)
            row.createCell(5).setTextSafe(entry.displayLabel)
            row.createCell(6).setTextSafe(FieldValueSheetMapper.aliasesToCsv(entry))
            row.createCell(7).setTextSafe(entry.category)
            row.createCell(8).setTextSafe(entry.description)
            row.createCell(9).setTextSafe(if (entry.isHidden) "Y" else "N")
            row.createCell(10).setTextSafe(entry.source)
            row.createCell(11).setCellValue(entry.usageCount.toDouble())
            row.createCell(12).setTextSafe(entry.code)
        }

        applySpecFormatting(sheet, spec, rowIndex - 1)
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

        // 캐릭터별로 '그 캐릭터의 시트가 열로 담은 필드 id' — 오버플로 판정의 단일 소스.
        // 내보내기 로직이 직접 채우므로 두 곳이 드리프트할 수 없다.
        val coveredFieldIds = HashMap<Long, Set<Long>>()

        // 오버플로 시트명은 실제 생성보다 먼저 확보한다(행이 없으면 시트를 만들지 않으므로).
        // 예약명 보호 자체는 assignSheetName의 ownerOf 규칙이 하므로 순서에 의존하지 않는다.
        val overflowSpecName = characterFieldValueSpec().sheetName
        val overflowSheetName = assignSheetName(overflowSpecName, usedSheetNames, ownerOf = overflowSpecName)

        // ── '전체 캐릭터' 시트(U-12a) — 세계관 시트를 돌면서 같은 행을 한 벌 더 쌓는다 ──
        // 열은 캐릭터 데이터를 보기 전에 정해진다(필드 정의만으로 판정) — 그래서 행을 따로
        // 모아 두었다가 나중에 쓰지 않고, 시트를 먼저 열어 **한 번만** 순회한다.
        val novelUniverseIds = novels.associate { it.id to it.universeId }
        val universeIdsWithChars = allCharacters
            .mapNotNullTo(HashSet<Long>()) { ch -> ch.novelId?.let { novelUniverseIds[it] } }
        val sharedFields = AllCharactersSheet.sharedFields(
            db.fieldDefinitionDao().getAllFieldsList(), universeIdsWithChars
        )
        val allSpec = allCharactersSpec(sharedFields.map { it.header })
        val allSheet = if (allCharacters.isNotEmpty()) {
            val name = assignSheetName(
                ALL_CHARACTERS_SHEET_NAME, usedSheetNames, ownerOf = ALL_CHARACTERS_SHEET_NAME
            )
            workbook.createSheet(name).also { writeHeaderRow(it, allSpec) }
        } else null
        var allRowCount = 0

        for (universe in universes) {
            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            val universeNovels = novels.filter { it.universeId == universe.id }
            val universeNovelIds = universeNovels.map { it.id }.toSet()
            val universeChars = allCharacters.filter { it.novelId in universeNovelIds }

            // 캐릭터가 0명인 세계관도 **시트를 만든다**(B-88) — 종전에는 `continue`로 건너뛰어
            // 새로 만든 세계관에 엑셀로 캐릭터를 적어 넣을 길이 아예 없었다. 열 구성은
            // 그 세계관의 필드 정의가 정하므로 캐릭터가 없어도 정확히 만들어진다.
            val tags = universeChars.associate { char ->
                char.id to (allTagsMap[char.id] ?: emptyList())
            }
            // 표시값은 캐릭터당 한 번만 낸다 — 두 시트가 **같은 값을 보여야 하고**,
            // CALCULATED 평가를 시트마다 되풀이하면 캐릭터 수 × 수식 수만큼 두 배가 된다.
            val resolved = universeChars.associate { char ->
                char.id to resolveFieldDisplayValues(
                    fields, (allFieldValuesMap[char.id] ?: emptyList()).associateBy { it.fieldDefinitionId }
                )
            }

            val covered = fields.mapTo(HashSet()) { it.id }
            universeChars.forEach { coveredFieldIds[it.id] = covered }

            exportCharacterSheet(
                workbook, usedSheetNames, universe.name,
                universeChars, fields, novelMap, resolved, tags
            )

            if (allSheet != null) {
                allRowCount += appendAllCharacterRows(
                    allSheet, allRowCount, sharedFields, universe.name,
                    universeChars, fields, novelMap, resolved, tags
                )
            }
        }

        // 미분류 캐릭터 — 세계관이 없어 필드 열을 만들 수 없다.
        // 그 필드값은 아래 '캐릭터 필드값' 시트가 (세계관, 필드키)로 담는다(무음 유실 차단).
        val unassignedChars = allCharacters.filter { char ->
            val novel = novelMap[char.novelId]
            novel?.universeId == null
        }
        if (unassignedChars.isNotEmpty()) {
            val tags = unassignedChars.associate { char ->
                char.id to (allTagsMap[char.id] ?: emptyList())
            }
            unassignedChars.forEach { coveredFieldIds[it.id] = emptySet() }
            exportCharacterSheet(
                workbook, usedSheetNames, UNCLASSIFIED_SHEET_NAME,
                unassignedChars, emptyList(), novelMap, emptyMap(), tags,
                sheetOwnerOf = UNCLASSIFIED_SHEET_NAME
            )
            // 미분류 캐릭터도 '전체'에 들어간다 — 빠지면 이 시트의 합계가 앱의 인원수와 어긋나고,
            // 그것을 알아채려면 일일이 세어 봐야 한다(원칙 04).
            if (allSheet != null) {
                allRowCount += appendAllCharacterRows(
                    allSheet, allRowCount, sharedFields, "",
                    unassignedChars, emptyList(), novelMap, emptyMap(), tags
                )
            }
        }

        if (allSheet != null) applySpecFormatting(allSheet, allSpec, allRowCount)

        exportCharacterFieldValueOverflow(
            workbook, overflowSheetName, allCharacters, allFieldValuesMap, coveredFieldIds
        )
    }

    /**
     * 한 캐릭터의 필드 표시값(필드 id → 문자열) — CALCULATED는 실시간 평가한다.
     *
     * 캐릭터 시트와 '전체 캐릭터' 시트가 **같은 값을 보여야 하므로** 이 함수가 단일 소스다.
     * 두 벌로 두면 한쪽만 고쳐진 채로 오래 간다(엑셀에서 대조하기 전에는 드러나지 않는다).
     */
    private fun resolveFieldDisplayValues(
        fields: List<FieldDefinition>,
        fieldValueMap: Map<Long, CharacterFieldValue>
    ): Map<Long, String> {
        if (fields.isEmpty()) return emptyMap()
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
                // 깨진 수식은 **셀에 오류 표식을 쓴다**(U-9). 엑셀에서 훑는 사람에게 그 자리가
                // 곧 진단이고, 빈칸으로 두면 값이 없는 것과 구분되지 않는다.
                // 왕복 오염은 없다 — 가져오기는 CALCULATED 열을 저장하지 않는다(F4).
                f.id to com.novelcharacter.app.util.FormulaDisplay
                    .evaluateForDisplay(formula, evaluator::evaluate)
            }.toMap()
        } else emptyMap()

        return fields.associate { field ->
            field.id to if (field.type == "CALCULATED") {
                calculatedResults[field.id] ?: ""
            } else {
                fieldValueMap[field.id]?.value ?: ""
            }
        }
    }

    /**
     * '전체 캐릭터' 시트에 한 세계관 몫의 행을 잇는다. 반환값은 쓴 행 수다.
     * 시트를 한 번만 순회하려고 세계관 루프 안에서 부른다(행을 모아 두지 않는다).
     */
    private fun appendAllCharacterRows(
        sheet: XSSFSheet,
        startRow: Int,
        sharedFields: List<AllCharactersSheet.SharedField>,
        universeName: String,
        characters: List<Character>,
        fields: List<FieldDefinition>,
        novelMap: Map<Long, Novel>,
        resolvedValues: Map<Long, Map<Long, String>>,
        allTags: Map<Long, List<CharacterTag>>
    ): Int {
        // (필드키, 타입) → 이 세계관의 필드 id. 같은 조합이 한 세계관에 둘 있을 수 없다
        // (필드키는 세계관·entityType 안에서 유일하다).
        val fieldIdByKeyType = fields.associate { (it.key to it.type) to it.id }
        characters.forEachIndexed { index, character ->
            val row = sheet.createRow(startRow + index + 1)
            val novel = character.novelId?.let { novelMap[it] }
            val values = resolvedValues[character.id].orEmpty()
            var col = 0
            row.createCell(col++).setTextSafe(universeName)
            row.createCell(col++).setTextSafe(novel?.title ?: "")
            row.createCell(col++).setTextSafe(character.name)
            row.createCell(col++).setTextSafe(character.lastName)
            row.createCell(col++).setTextSafe(character.firstName)
            row.createCell(col++).setTextSafe(character.anotherName)
            row.createCell(col++).setTextSafe(
                (allTags[character.id] ?: emptyList()).joinToString(", ") { it.tag }
            )
            row.createCell(col++).setTextSafe(if (character.isPinned) "Y" else "N")
            row.createCell(col++).setCellValue(character.displayOrder.toDouble())
            row.createCell(col++).setCellValue(character.createdAt.toDouble())
            row.createCell(col++).setTextSafe(character.code)
            row.createCell(col++).setTextSafe(novel?.code ?: "")
            for (shared in sharedFields) {
                val fieldId = fieldIdByKeyType[shared.key to shared.type]
                row.createCell(col++).setTextSafe(fieldId?.let { values[it] } ?: "")
            }
        }
        return characters.size
    }

    /**
     * 캐릭터 시트가 열로 담지 못한 필드값 전부 — 미분류 캐릭터 + 타 세계관 잔여값.
     * 이 시트가 없으면 해당 값은 내보내기에서 무음 폐기되고, 덮어쓰기 복원 시 CASCADE로 영구 소멸한다.
     */
    private suspend fun exportCharacterFieldValueOverflow(
        workbook: XSSFWorkbook,
        sheetName: String,
        characters: List<Character>,
        allFieldValuesMap: Map<Long, List<CharacterFieldValue>>,
        coveredFieldIds: Map<Long, Set<Long>>
    ) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val fieldsById = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }

        // 정렬을 (캐릭터 displayOrder, 필드 displayOrder, 필드키)로 고정 — 무편집 왕복 멱등성의 근거
        val rows = characters.sortedWith(compareBy({ it.displayOrder }, { it.id }))
            .flatMap { ch ->
                CharacterFieldValueOverflow
                    .select(allFieldValuesMap[ch.id].orEmpty(), coveredFieldIds[ch.id] ?: emptySet(), fieldsById)
                    .sortedWith(compareBy({ it.second.displayOrder }, { it.second.key }))
                    .map { (value, fd) -> Triple(ch, fd, value.value) }
            }
        if (rows.isEmpty()) return  // 다른 시트와 동일 — 빈 시트는 만들지 않는다

        val spec = characterFieldValueSpec(universes.map { it.name })
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        rows.forEachIndexed { index, (ch, fd, value) ->
            val universe = universeMap[fd.universeId]
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(ch.code)
            row.createCell(1).setTextSafe(ch.name)
            row.createCell(2).setTextSafe(universe?.name ?: "")
            row.createCell(3).setTextSafe(universe?.code ?: "")
            row.createCell(4).setTextSafe(fd.key)
            row.createCell(5).setTextSafe(fd.name)
            row.createCell(6).setTextSafe(FieldValueSheetMapper.entityLabel(fd.entityType))
            row.createCell(7).setTextSafe(value)
        }
        applySpecFormatting(sheet, spec, rows.size)
    }

    private fun exportCharacterSheet(
        workbook: XSSFWorkbook,
        usedSheetNames: MutableSet<String>,
        sheetLabel: String,
        characters: List<Character>,
        fields: List<FieldDefinition>,
        novelMap: Map<Long, Novel>,
        /** 캐릭터 id → (필드 id → 표시값). [resolveFieldDisplayValues]가 만든다 — 두 시트의 단일 소스. */
        resolvedValues: Map<Long, Map<Long, String>>,
        allTags: Map<Long, List<CharacterTag>>,
        /**
         * 이 시트가 소유권을 주장하는 예약명. '미분류 캐릭터' 시트만 값을 갖고,
         * 세계관 캐릭터 시트는 null이라 어떤 예약명도 차지할 수 없다(4-5 규약).
         */
        sheetOwnerOf: String? = null
    ) {
        val novelTitles = novelMap.values.map { it.title }.distinct()
        val spec = characterSpec(fields, novelTitles)
        val sheetName = assignSheetName(sheetLabel, usedSheetNames, ownerOf = sheetOwnerOf)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        characters.forEachIndexed { index, character ->
            val row = sheet.createRow(index + 1)
            val novel = character.novelId?.let { novelMap[it] }
            val values = resolvedValues[character.id].orEmpty()
            var col = 0

            // 이름
            row.createCell(col++).setTextSafe(character.name)

            // 성
            row.createCell(col++).setTextSafe(character.lastName)

            // 이름(First)
            row.createCell(col++).setTextSafe(character.firstName)

            // 이명
            row.createCell(col++).setTextSafe(character.anotherName)

            // 동적 필드 — CALCULATED 실시간 평가를 포함한 표시값은 resolveFieldDisplayValues가 냈다.
            for (field in fields) {
                row.createCell(col++).setTextSafe(values[field.id] ?: "")
            }

            // 이미지경로 (readOnly)
            row.createCell(col++).setTextSafe(character.imagePaths)

            // 대표이미지 (B-103 D8) — 사람이 읽고 고칠 수 있도록 파일명으로 싣는다.
            // 한 행 안에서 파일명이 겹치면 규약이 알아서 전체 경로로 떨어진다.
            row.createCell(col++).setTextSafe(
                com.novelcharacter.app.util.RepresentativeImageCell.toCell(
                    character.representativeImagePath,
                    com.novelcharacter.app.util.CharacterRepresentativeImage.paths(character.imagePaths)
                )
            )

            // 작품
            row.createCell(col++).setTextSafe(novel?.title ?: "")

            // 메모
            row.createCell(col++).setTextSafe(character.memo)

            // 태그
            val tags = allTags[character.id] ?: emptyList()
            row.createCell(col++).setTextSafe(tags.joinToString(", ") { it.tag })

            // 코드 (readOnly)
            row.createCell(col++).setTextSafe(character.code)

            // 작품코드 (readOnly)
            row.createCell(col++).setTextSafe(novel?.code ?: "")

            // 정렬순서
            row.createCell(col++).setCellValue(character.displayOrder.toDouble())

            // 고정
            row.createCell(col++).setTextSafe(if (character.isPinned) "Y" else "N")

            // 생성일
            row.createCell(col).setCellValue(character.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, characters.size)
    }

    // ── 사건 연표 ──

    private suspend fun exportTimeline(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val events = db.timelineDao().getAllEventsList()
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        // 사건 커스텀 필드 (B-10) — 필드명 중복 시 세계관명으로 구분한 헤더 "필드:{이름}"
        val eventFields = db.fieldDefinitionDao().getAllFieldsList(
            com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT
        )
        val universesById = db.universeDao().getAllUniversesList().associateBy { it.id }
        // 헤더 규칙은 EntityFieldHeaders 단일 소스 — 가져오기가 같은 규칙의 역함수로 정확히 되짚는다
        val eventFieldColumns = EntityFieldHeaders.headersFor(
            eventFields,
            universesById.mapValues { (_, u) -> u.name }
        )

        val spec = timelineSpec(
            novels.map { it.title },
            eventFieldColumns.map { it.second },
            universesById.values.map { it.name }
        )
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        val eventFieldValuesByEvent = db.eventFieldValueDao().getAllValuesList().groupBy { it.eventId }

        // Batch load all cross-refs and characters to avoid N+1 queries
        val allCrossRefs = db.timelineDao().getAllCrossRefs()
        val eventCharIdMap = allCrossRefs.groupBy({ it.eventId }, { it.characterId })
        val allChars = db.characterDao().getAllCharactersList()
        val charMap = allChars.associateBy { it.id }
        // Novel cross-ref: 사건별 연결 작품 (다대다)
        val allEventNovelCrossRefs = db.timelineDao().getAllEventNovelCrossRefs()
        val eventNovelIdMap = allEventNovelCrossRefs.groupBy({ it.eventId }, { it.novelId })

        events.forEachIndexed { index, event ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(event.year.toDouble())
            event.month?.let { row.createCell(1).setCellValue(it.toDouble()) }
            event.day?.let { row.createCell(2).setCellValue(it.toDouble()) }
            row.createCell(3).setTextSafe(event.calendarType)
            row.createCell(4).setTextSafe(eventTypeToLabel(event.eventType))
            row.createCell(5).setTextSafe(event.description)

            val novelIds = eventNovelIdMap[event.id] ?: emptyList()
            val novels = novelIds.mapNotNull { novelMap[it] }
            row.createCell(6).setTextSafe(novels.joinToString(", ") { it.title })

            val eventCharIds = eventCharIdMap[event.id] ?: emptyList()
            val characterNames = eventCharIds.mapNotNull { charMap[it]?.name }
            row.createCell(7).setTextSafe(characterNames.joinToString(", "))

            // 관련작품코드 (readOnly)
            row.createCell(8).setTextSafe(novels.mapNotNull { it.code }.joinToString(", "))
            // 관련캐릭터코드 (readOnly) — 동명이인 오결합 방지(P1-I). 가져오기 시 코드 우선 매칭.
            row.createCell(9).setTextSafe(eventCharIds.mapNotNull { charMap[it]?.code }.joinToString(", "))
            row.createCell(10).setCellValue(event.displayOrder.toDouble())
            row.createCell(11).setTextSafe(if (event.isTemporary) "Y" else "N")
            // 코드 (readOnly) — 왕복 안정 식별자: 설명·연도를 외부에서 편집해도 같은 사건으로 인식
            row.createCell(12).setTextSafe(event.code ?: "")
            row.createCell(13).setCellValue(event.createdAt.toDouble())

            // 세계관 소속 — 작품 미연결 사건도 신규 기기 복원 시 세계관을 잃지 않게 명시 기록
            val eventUniverse = event.universeId?.let { universesById[it] }
            row.createCell(14).setTextSafe(eventUniverse?.name ?: "")
            row.createCell(15).setTextSafe(eventUniverse?.code ?: "")

            // 사건 커스텀 필드 값 (B-10)
            val fieldValues = eventFieldValuesByEvent[event.id]?.associateBy { it.fieldDefinitionId } ?: emptyMap()
            eventFieldColumns.forEachIndexed { fi, (fieldDef, _) ->
                fieldValues[fieldDef.id]?.let { row.createCell(16 + fi).setTextSafe(it.value) }
            }
        }

        applySpecFormatting(sheet, spec, events.size)
    }

    // ── 캐릭터 상태변화 ──

    private suspend fun exportStateChanges(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allChangesRaw = db.characterStateChangeDao().getAllChangesList()

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

        val spec = stateChangeSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allChanges.forEachIndexed { index, (character, novelTitle, change) ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(character.name)
            row.createCell(1).setTextSafe(novelTitle)
            row.createCell(2).setCellValue(change.year.toDouble())
            change.month?.let { row.createCell(3).setCellValue(it.toDouble()) }
            change.day?.let { row.createCell(4).setCellValue(it.toDouble()) }
            row.createCell(5).setTextSafe(change.fieldKey)
            row.createCell(6).setTextSafe(change.newValue)
            row.createCell(7).setTextSafe(change.description)
            // 캐릭터코드 (readOnly)
            row.createCell(8).setTextSafe(character.code)
            // 코드 (readOnly) — 왕복 안정 식별자: 값·연도를 외부에서 편집해도 같은 이력으로 인식
            row.createCell(9).setTextSafe(change.code ?: "")
            row.createCell(10).setCellValue(change.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allChanges.size)
    }

    // ── 캐릭터 관계 ──

    private suspend fun exportRelationships(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allRelationships = db.characterRelationshipDao().getAllRelationships()

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val allFactions = db.factionDao().getAllFactionsList()
        val factionMap = allFactions.associateBy { it.id }

        val allUniverses = db.universeDao().getAllUniversesList()
        val allCustomTypes = allUniverses.flatMap { it.getRelationshipTypes() }
        // 동명 세력은 드롭다운에서 구분되지 않으므로 접는다 — 대상 확정은 '세력코드' 열이 한다
        val spec = relationshipSpec(allCustomTypes, allFactions.map { it.name }.distinct())
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allRelationships.forEachIndexed { i, rel ->
            val row = sheet.createRow(i + 1)
            val char1 = charMap[rel.characterId1]
            val char2 = charMap[rel.characterId2]
            row.createCell(0).setTextSafe(char1?.name ?: "")
            row.createCell(1).setTextSafe(char2?.name ?: "")
            row.createCell(2).setTextSafe(rel.relationshipType)
            row.createCell(3).setTextSafe(rel.description)
            row.createCell(4).setCellValue(rel.intensity.toDouble())
            row.createCell(5).setTextSafe(if (rel.isBidirectional) "Y" else "N")
            row.createCell(6).setCellValue(rel.displayOrder.toDouble())
            // 코드 (readOnly)
            row.createCell(7).setTextSafe(char1?.code ?: "")
            row.createCell(8).setTextSafe(char2?.code ?: "")
            row.createCell(9).setTextSafe(rel.factionId?.let { factionMap[it]?.name } ?: "")
            row.createCell(10).setTextSafe(rel.factionId?.let { factionMap[it]?.code } ?: "")
            row.createCell(11).setCellValue(rel.createdAt.toDouble())
            row.createCell(12).setTextSafe(rel.code ?: "")
        }

        applySpecFormatting(sheet, spec, allRelationships.size)
    }

    // ── 관계 변화 ──

    private suspend fun exportRelationshipChanges(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allChanges = db.characterRelationshipChangeDao().getAllChanges()

        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        val relMap = allRelationships.associateBy { it.id }
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }
        // 연결 사건 참조는 id가 아닌 code로 기록 — id는 복원/기기 이전 시 변한다
        val eventCodeById = db.timelineDao().getAllEventsList().associate { it.id to it.code }

        val spec = relationshipChangeSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allChanges.forEachIndexed { i, rc ->
            val rel = relMap[rc.relationshipId] ?: return@forEachIndexed
            val char1 = charMap[rel.characterId1]
            val char2 = charMap[rel.characterId2]
            val row = sheet.createRow(i + 1)
            row.createCell(0).setTextSafe(char1?.name ?: "")
            row.createCell(1).setTextSafe(char2?.name ?: "")
            row.createCell(2).setCellValue(rc.year.toDouble())
            rc.month?.let { row.createCell(3).setCellValue(it.toDouble()) }
            rc.day?.let { row.createCell(4).setCellValue(it.toDouble()) }
            row.createCell(5).setTextSafe(rc.relationshipType)
            row.createCell(6).setTextSafe(rc.description)
            row.createCell(7).setCellValue(rc.intensity.toDouble())
            row.createCell(8).setTextSafe(if (rc.isBidirectional) "Y" else "N")
            rc.eventId?.let { eid -> eventCodeById[eid]?.let { row.createCell(9).setTextSafe(it) } }
            // 코드 (readOnly) — 왕복 안정 식별자
            row.createCell(10).setTextSafe(rc.code ?: "")
            row.createCell(11).setTextSafe(char1?.code ?: "")
            row.createCell(12).setTextSafe(char2?.code ?: "")
            row.createCell(13).setCellValue(rc.createdAt.toDouble())
            // 부모 관계 식별 — 코드가 있으면 유형을 고쳐도 정확히 따라간다(유형은 코드 없는 구파일용 폴백)
            row.createCell(14).setTextSafe(rel.relationshipType)
            row.createCell(15).setTextSafe(rel.code ?: "")
        }

        applySpecFormatting(sheet, spec, allChanges.size)
    }

    // ── 이미지 라이브러리 메타 (G3) ──

    /**
     * 라이브러리 관리 이미지(meta 행)의 태그·링크 그룹을 시트로 기록한다.
     * 파일명은 basename만 — 절대경로는 기기 간 이식성이 없다(가져오기에서 zip 리맵/로컬 존재로 해석).
     */
    private suspend fun exportImageMeta(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val metas = db.imageMetaDao().getAllList()
        val tagsByImage = db.imageTagDao().getAllList().groupBy({ it.imageId }, { it.tag })

        val spec = imageMetaSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        metas.forEachIndexed { i, meta ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setTextSafe(java.io.File(meta.path).name)
            row.createCell(1).setTextSafe(tagsByImage[meta.id]?.joinToString(", ") ?: "")
            row.createCell(2).setTextSafe(meta.linkGroupId ?: "")
            // 뗀 적 없으면 **칸을 만들지 않는다** — 빈칸이 곧 "뗀 적 없음"이라(D1) 0이나
            // 빈 문자열을 넣으면 상태가 값과 갈린다. 시각은 다른 시트의 `createdAt`과 같은
            // 규약으로 밀리초 숫자다(사람이 읽을 일이 없고, 지울 때는 칸을 비우면 된다).
            meta.detachedAt?.let { row.createCell(3).setCellValue(it.toDouble()) }
            row.createCell(4).setTextSafe(meta.detachedFromCode ?: "")
        }

        applySpecFormatting(sheet, spec, metas.size)
    }

    // ── 이름 은행 ──

    private suspend fun exportNameBank(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allNames = db.nameBankDao().getAllNamesList()

        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val spec = nameBankSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allNames.forEachIndexed { i, entry ->
            val row = sheet.createRow(i + 1)
            val usedByChar = entry.usedByCharacterId?.let { charMap[it] }
            row.createCell(0).setTextSafe(entry.name)
            row.createCell(1).setTextSafe(entry.gender)
            row.createCell(2).setTextSafe(entry.origin)
            row.createCell(3).setTextSafe(entry.notes)
            row.createCell(4).setTextSafe(if (entry.isUsed) "Y" else "N")
            row.createCell(5).setTextSafe(usedByChar?.name ?: "")
            // 사용캐릭터코드 (readOnly)
            row.createCell(6).setTextSafe(usedByChar?.code ?: "")
            row.createCell(7).setCellValue(entry.createdAt.toDouble())
            // 코드 (readOnly) — 이름 은행 항목 자체의 왕복 안정 식별자 (F3-D)
            row.createCell(8).setTextSafe(entry.code)
        }

        applySpecFormatting(sheet, spec, allNames.size)
    }

    // ── 세력 ──

    private suspend fun exportFactions(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allFactions = db.factionDao().getAllFactionsList()

        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }

        val spec = factionSpec(universes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allFactions.forEachIndexed { i, faction ->
            val row = sheet.createRow(i + 1)
            val universe = universeMap[faction.universeId]
            row.createCell(0).setTextSafe(faction.name)
            row.createCell(1).setTextSafe(universe?.name ?: "")
            row.createCell(2).setTextSafe(universe?.code ?: "")
            row.createCell(3).setTextSafe(faction.description)
            row.createCell(4).setTextSafe(faction.color)
            row.createCell(5).setTextSafe(faction.autoRelationType)
            row.createCell(6).setCellValue(faction.autoRelationIntensity.toDouble())
            row.createCell(7).setTextSafe(faction.code)
            row.createCell(8).setCellValue(faction.displayOrder.toDouble())
            row.createCell(9).setCellValue(faction.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allFactions.size)
    }

    // ── 세력 소속 ──

    private suspend fun exportFactionMemberships(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allMemberships = db.factionMembershipDao().getAllMembershipsList()

        val allFactions = db.factionDao().getAllFactionsList()
        val factionMap = allFactions.associateBy { it.id }
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.associateBy { it.id }

        val spec = factionMembershipSpec(allFactions.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allMemberships.forEachIndexed { i, membership ->
            val row = sheet.createRow(i + 1)
            val faction = factionMap[membership.factionId]
            val character = charMap[membership.characterId]
            row.createCell(0).setTextSafe(faction?.name ?: "")
            row.createCell(1).setTextSafe(character?.name ?: "")
            membership.joinYear?.let { row.createCell(2).setCellValue(it.toDouble()) }
            membership.leaveYear?.let { row.createCell(3).setCellValue(it.toDouble()) }
            val leaveTypeLabel = when (membership.leaveType) {
                "removed" -> "순수제거"
                "departed" -> "설정상탈퇴"
                else -> ""
            }
            row.createCell(4).setTextSafe(leaveTypeLabel)
            row.createCell(5).setTextSafe(membership.departedRelationType ?: "")
            membership.departedIntensity?.let { row.createCell(6).setCellValue(it.toDouble()) }
            // readOnly codes
            row.createCell(7).setTextSafe(faction?.code ?: "")
            row.createCell(8).setTextSafe(character?.code ?: "")
            row.createCell(9).setCellValue(membership.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allMemberships.size)
    }

    // ── 세력 관계 (B-3) ──

    private suspend fun exportFactionRelationships(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val allRelationships = db.factionRelationshipDao().getAllRelationshipsList()

        val allFactions = db.factionDao().getAllFactionsList()
        val factionMap = allFactions.associateBy { it.id }
        val customTypes = db.universeDao().getAllUniversesList()
            .flatMap { it.getRelationshipTypes() }.distinct()

        val spec = factionRelationshipSpec(allFactions.map { it.name }, customTypes)
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allRelationships.forEachIndexed { i, rel ->
            val row = sheet.createRow(i + 1)
            val faction1 = factionMap[rel.factionId1]
            val faction2 = factionMap[rel.factionId2]
            row.createCell(0).setTextSafe(faction1?.name ?: "")
            row.createCell(1).setTextSafe(faction2?.name ?: "")
            row.createCell(2).setTextSafe(rel.relationType)
            row.createCell(3).setTextSafe(rel.description)
            row.createCell(4).setCellValue(rel.intensity.toDouble())
            row.createCell(5).setTextSafe(if (rel.isBidirectional) "Y" else "N")
            row.createCell(6).setCellValue(rel.displayOrder.toDouble())
            row.createCell(7).setTextSafe(faction1?.code ?: "")
            row.createCell(8).setTextSafe(faction2?.code ?: "")
            row.createCell(9).setCellValue(rel.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, allRelationships.size)
    }

    // ── ZIP + 이미지 래핑 ──

    /** @return (사용할 ZIP 파일 또는 null, 이미지 포함 결과 집계) */
    // ── 대결 (B-104 ㄹ1) ──

    /**
     * 대결 **축** — 세계관·대상·필드 연결.
     *
     * 필드 연결은 키를 쉼표로 이은 글이다(`util/DuelFieldLinks.toText`) — 사람이 손으로 고칠
     * 자리라 JSON을 싣지 않는다. **순서가 곧 영향력 순위**이므로 이어 붙이는 차례를 지킨다.
     */
    private suspend fun exportDuelAxes(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val axes = db.duelAxisDao().getAllList()
        val universeMap = db.universeDao().getAllUniversesList().associateBy { it.id }

        val spec = duelAxisSpec(universeMap.values.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        axes.forEachIndexed { i, axis ->
            val row = sheet.createRow(i + 1)
            val universe = universeMap[axis.universeId]
            val links = axis.fieldLinks
            row.createCell(0).setTextSafe(axis.name)
            row.createCell(1).setTextSafe(universe?.name ?: "")
            row.createCell(2).setTextSafe(universe?.code ?: "")
            row.createCell(3).setTextSafe(
                if (axis.isImageAxis) DuelSheetLabels.TARGET_IMAGE else DuelSheetLabels.TARGET_CHARACTER
            )
            row.createCell(4).setTextSafe(DuelFieldLinks.toText(links.influences))
            row.createCell(5).setTextSafe(DuelFieldLinks.toText(links.outcomes))
            row.createCell(6).setCellValue(axis.displayOrder.toDouble())
            row.createCell(7).setTextSafe(axis.code)
            row.createCell(8).setCellValue(axis.createdAt.toDouble())
        }

        applySpecFormatting(sheet, spec, axes.size)
    }

    /**
     * 대결 **기록** — 한 행이 한 판이다.
     *
     * ⚠️ 이 앱에서 **가장 큰 시트**가 될 수 있다(수만 행). 참가자 이름을 붙이려고 캐릭터 표를
     * 한 번만 읽고 코드로 색인한다 — 행마다 조회하면 그 비용이 행 수만큼 곱해진다.
     *
     * **승자를 이름으로 적는 것이 이 시트의 요점이다.** 코드를 적으라고 하면 사람이 고칠 수
     * 없고, 그러면 이 시트를 엑셀에 싣는 뜻이 없어진다(사용자 요청: *"엑셀에서도 편집"*).
     */
    private suspend fun exportDuelMatches(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val axes = db.duelAxisDao().getAllList()
        val nameByCode = db.characterDao().getAllCharactersList().associate { it.code to it.displayName }

        val spec = duelMatchSpec(axes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        var rowIndex = 0
        for (axis in axes) {
            for (match in db.duelMatchDao().getByAxis(axis.id)) {
                val row = sheet.createRow(++rowIndex)
                row.createCell(0).setTextSafe(axis.name)
                row.createCell(1).setTextSafe(axis.code)
                row.createCell(2).setTextSafe(nameByCode[match.aCode] ?: "")
                row.createCell(3).setTextSafe(match.aCode)
                row.createCell(4).setTextSafe(nameByCode[match.bCode] ?: "")
                row.createCell(5).setTextSafe(match.bCode)
                // 승자 이름을 못 찾으면 **코드를 그대로 적는다** — 비우면 무승부로 되읽혀
                // 사용자가 고른 승패가 왕복 한 번에 사라진다(개발 의도 4번).
                row.createCell(6).setTextSafe(
                    match.winnerCode?.let { nameByCode[it] ?: it } ?: DuelSheetLabels.WINNER_DRAW
                )
                row.createCell(7).setTextSafe(match.groupId ?: "")
                row.createCell(8).setCellValue(match.decidedAt.toDouble())
                row.createCell(9).setTextSafe(match.code)
            }
        }

        applySpecFormatting(sheet, spec, rowIndex)
    }

    /** 대결 **상성** — 층 B의 사용자 판정. 파생이 아니라 판정이라 싣는다. */
    private suspend fun exportDuelVerdicts(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val axes = db.duelAxisDao().getAllList()
        val nameByCode = db.characterDao().getAllCharactersList().associate { it.code to it.displayName }

        val spec = duelVerdictSpec(axes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        var rowIndex = 0
        for (axis in axes) {
            for (verdict in db.duelCounterVerdictDao().getByAxis(axis.id)) {
                val members = DuelRecords.decodeMembers(verdict.memberCodes)
                val row = sheet.createRow(++rowIndex)
                row.createCell(0).setTextSafe(axis.name)
                row.createCell(1).setTextSafe(axis.code)
                row.createCell(2).setTextSafe(
                    if (verdict.kind == DuelCounterVerdict.KIND_COUNTER) {
                        DuelSheetLabels.KIND_COUNTER
                    } else {
                        DuelSheetLabels.KIND_UNDECIDED
                    }
                )
                row.createCell(3).setTextSafe(
                    if (verdict.shape == DuelCounterVerdict.SHAPE_CYCLE) {
                        DuelSheetLabels.SHAPE_CYCLE
                    } else {
                        DuelSheetLabels.SHAPE_DIRECT
                    }
                )
                // 뜻이 있는 순서다(천적은 [센 쪽, 잡는 쪽], 순환은 이기는 차례) — 정렬하지 않는다.
                row.createCell(4).setTextSafe(members.joinToString(", ") { nameByCode[it] ?: it })
                row.createCell(5).setTextSafe(members.joinToString(", "))
                row.createCell(6).setCellValue(verdict.decidedAt.toDouble())
                row.createCell(7).setTextSafe(verdict.code)
            }
        }

        applySpecFormatting(sheet, spec, rowIndex)
    }

    private suspend fun wrapWithImages(
        xlsxFile: File,
        zipFileName: String,
        progress: ExportProgressSink? = null
    ): Pair<File?, ImageZipReport> {
        val exportsDir = File(appContext.cacheDir, "exports")
        exportsDir.mkdirs()
        val zipFile = File(exportsDir, zipFileName)
        try {
            val report = ImageZipHelper.wrapWithImages(xlsxFile, zipFile, db, appContext, progress)
            return (if (report.created) zipFile else null) to report
        } catch (e: Throwable) {
            // 취소든 실패든 반쯤 쓴 ZIP은 남기지 않는다 — 캐시에 쌓이고, 무엇보다
            // 다음 '백업 내보내기'가 그것을 집을 수 있다
            zipFile.delete()
            throw e
        }
    }

    /**
     * 이 내보내기가 만들 파일의 대략적 크기(바이트) — 공간 부족 안내(D7)와
     * 사전 견적(D6)이 같은 식을 쓴다.
     *
     * 이미지는 실측 합산(무압축으로 담으므로 실제 zip 크기와 거의 같다 — 설계 D8의 부수 이득),
     * 워크북 몫은 이미 만들어 둔 임시 파일에서 재지 않고 생략한다 — 실패 시점에 그 파일이
     * 남아 있다는 보장이 없고, 이미지가 압도적이라(실측 744MB 대 수 MB) 안내의 자릿수가
     * 바뀌지 않는다. **모자라게 말하지 않는 것이 중요하므로** 이미지 몫만으로도 안내는 성립한다.
     */
    private suspend fun estimateExportBytes(options: ExportOptions): Long =
        if (options.images) ImageZipHelper.estimateImageBytes(db, appContext) else 0L

    /**
     * 이미지 포함 결과 고지 한 줄. 사실만 말한다 — 제외가 0건이면 손실 문구를 쓰지 않는다.
     * (사실과 다른 경고는 무음보다 나쁘다)
     */
    private fun buildImageNotice(r: ImageZipReport, isCompleteBackup: Boolean): String? = when {
        !r.requested -> null
        r.hasLoss && r.includedCount == 0 ->
            appContext.getString(R.string.export_images_none_included, r.referencedCount)
        r.hasLoss ->
            appContext.getString(R.string.export_images_incomplete, r.referencedCount, r.includedCount, r.excludedCount)
        // 요청했으나 앱에 이미지 자체가 없는 경우 — 손실이 아니라 확장자(.xlsx)에 대한 설명
        r.referencedCount == 0 -> appContext.getString(R.string.export_images_none)
        // 전부 담겼다. 종전에는 이 갈래가 무고지였다(설계 1장) — 손실은 알려 주면서 완전함은
        // 말하지 않으면, 백업의 생명인 완전성을 사용자가 매번 열어서 확인해야 한다(원칙 04).
        isCompleteBackup -> appContext.getString(R.string.export_backup_complete, r.includedCount)
        else -> null
    }

    /** 작업 이력 '상세'에 실을 제외 내역 + 교정 경로 안내. 손실이 없으면 null. */
    private fun buildImageDetail(r: ImageZipReport): String? {
        if (!r.hasLoss) return null
        val lines = mutableListOf<String>()
        if (r.missingCount > 0) lines.add(appContext.getString(R.string.export_images_detail_missing, r.missingCount))
        if (r.outsideAppDirCount > 0) lines.add(appContext.getString(R.string.export_images_detail_outside, r.outsideAppDirCount))
        if (r.failedCount > 0) lines.add(appContext.getString(R.string.export_images_detail_failed, r.failedCount))
        if (r.sampleNames.isNotEmpty()) lines.add(appContext.getString(R.string.export_images_detail_samples, r.sampleNames.joinToString(", ")))
        lines.add(appContext.getString(R.string.export_images_detail_guide))
        return lines.joinToString("\n")
    }

    // ── 필드 템플릿 ──

    private suspend fun exportUserPresetTemplates(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val templates = db.userPresetTemplateDao().getAllTemplatesList()

        val spec = userPresetTemplateSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        templates.forEachIndexed { i, t ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setTextSafe(t.name)
            row.createCell(1).setTextSafe(t.description)
            row.createCell(2).setTextSafe(t.fieldsJson)
            row.createCell(3).setTextSafe(if (t.isBuiltIn) "Y" else "N")
            row.createCell(4).setCellValue(t.createdAt.toDouble())
            row.createCell(5).setCellValue(t.updatedAt.toDouble())
        }

        applySpecFormatting(sheet, spec, templates.size)
    }

    // ── 검색 프리셋 ──

    private suspend fun exportSearchPresets(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val presets = db.searchPresetDao().getAllPresetsList()

        val spec = searchPresetSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        val filterStableKeys = fieldFilterStableKeys()
        presets.forEachIndexed { i, p ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setTextSafe(p.name)
            row.createCell(1).setTextSafe(p.query)
            row.createCell(2).setTextSafe(PortableFieldFilters.augment(p.filtersJson, filterStableKeys))
            row.createCell(3).setTextSafe(p.sortMode)
            row.createCell(4).setTextSafe(if (p.isDefault) "Y" else "N")
            row.createCell(5).setCellValue(p.createdAt.toDouble())
            row.createCell(6).setCellValue(p.updatedAt.toDouble())
        }

        applySpecFormatting(sheet, spec, presets.size)
    }

    /**
     * 프리셋 필드 필터의 fieldId → 이 기기의 필드 정보(세계관코드·필드키·현재 필드명) 맵.
     * fieldId는 기기 이전·덮어쓰기 복원에서 재발급되므로 이 맵으로 왕복 이식성을 확보한다.
     * 필드명도 함께 갱신해 인앱 이름 변경 후에도 파일의 표시명이 자연키 폴백의 진실을 담게 한다
     * (필터 대상은 캐릭터 필드 — 검색·목록 프리셋 공통).
     */
    private suspend fun fieldFilterStableKeys(): Map<Long, PortableFieldFilters.DeviceField> {
        val universeById = db.universeDao().getAllUniversesList().associateBy { it.id }
        return db.fieldDefinitionDao().getAllFieldsList().associate { f ->
            val u = universeById[f.universeId]
            f.id to PortableFieldFilters.DeviceField(
                id = f.id,
                universeCode = u?.code ?: "",
                universeName = u?.name ?: "",
                key = f.key,
                name = f.name
            )
        }
    }

    // ── 앱 설정 ──

    /**
     * 캐릭터 목록 프리셋 왕복 — 이름이 유니크 키.
     * novelIdsJson(DB id 배열)은 기기 간 이식성이 없으므로 작품코드 콤마 목록으로 변환해 기록한다.
     */
    private suspend fun exportCharacterListPresets(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val presets = db.characterListPresetDao().getAllPresetsList()

        val novelCodeById = db.novelDao().getAllNovelsList().associate { it.id to it.code }
        val spec = characterListPresetSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        val filterStableKeys = fieldFilterStableKeys()
        presets.forEachIndexed { index, preset ->
            val novelCodes = try {
                val arr = org.json.JSONArray(preset.novelIdsJson)
                (0 until arr.length()).mapNotNull { novelCodeById[arr.getLong(it)] }
            } catch (_: Exception) {
                emptyList()
            }
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(preset.name)
            row.createCell(1).setTextSafe(preset.tagsJson)
            row.createCell(2).setTextSafe(PortableFieldFilters.augment(preset.fieldFiltersJson, filterStableKeys))
            row.createCell(3).setTextSafe(preset.sortKind)
            row.createCell(4).setTextSafe(preset.sortFieldKey ?: "")
            // 대결 정렬 축(B-117). 열 차례는 `characterListPresetSpec()`이 정하고 여기 인덱스가
            // 그것을 따라간다 — 머리글은 spec 순서인데 데이터 셀은 손으로 번호를 매기므로
            // **spec에 열을 끼워 넣으면 이 아래를 전부 밀어야 한다**(B-103이 같은 자리에서 겪었다).
            row.createCell(5).setTextSafe(preset.sortDuelAxisCode ?: "")
            row.createCell(6).setTextSafe(if (preset.sortAscending) "Y" else "N")
            preset.bodySizePartIndex?.let { row.createCell(7).setCellValue(it.toDouble()) }
            row.createCell(8).setTextSafe(novelCodes.joinToString(", "))
            row.createCell(9).setTextSafe(if (preset.isDefault) "Y" else "N")
            row.createCell(10).setCellValue(preset.createdAt.toDouble())
            row.createCell(11).setCellValue(preset.updatedAt.toDouble())
        }

        applySpecFormatting(sheet, spec, presets.size)
    }

    private suspend fun exportAppSettings(workbook: XSSFWorkbook, usedSheetNames: MutableSet<String>) {
        val spec = appSettingsSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        // 사용자 설정 왕복 — 새 기기 복원 시 설정을 다시 맞추지 않아도 되게 한다.
        // key/value 구조라 항목 추가는 가져오기(when 분기)와 짝으로 확장한다.
        val backupSettings = com.novelcharacter.app.backup.BackupSettingsStore(appContext).getSettings()
        val imageSettings = com.novelcharacter.app.util.ImageSettingsStore(appContext).getSettings()
        val editorRemovePolicy = com.novelcharacter.app.util.ImageSettingsStore(appContext).getEditorRemovePolicy()
        val autoLinkByCharacter = com.novelcharacter.app.util.ImageSettingsStore(appContext).getAutoLinkByCharacter()

        var rowIndex = 1
        fun writeTextRow(key: String, value: String) {
            val row = sheet.createRow(rowIndex++)
            row.createCell(0).setTextSafe(key)
            row.createCell(1).setTextSafe(value)
        }
        fun writeNumberRow(key: String, value: Double) {
            val row = sheet.createRow(rowIndex++)
            row.createCell(0).setTextSafe(key)
            row.createCell(1).setCellValue(value)
        }

        writeNumberRow("theme_mode", ThemeHelper.getSavedTheme(appContext).toDouble())
        writeTextRow("backup_include_images", if (backupSettings.includeImages) "Y" else "N")
        writeNumberRow("backup_max_backups", backupSettings.maxBackups.toDouble())
        writeTextRow("image_compress_enabled", if (imageSettings.enabled) "Y" else "N")
        writeNumberRow("image_quality_percent", imageSettings.qualityPercent.toDouble())
        writeTextRow("image_cap_dimension", if (imageSettings.capDimension) "Y" else "N")
        writeNumberRow("image_max_long_edge_px", imageSettings.maxLongEdgePx.toDouble())
        writeTextRow("image_skip_below_enabled", if (imageSettings.skipBelowEnabled) "Y" else "N")
        writeNumberRow("image_skip_below_bytes", imageSettings.skipBelowBytes.toDouble())
        writeTextRow("image_editor_remove_policy", editorRemovePolicy.name)
        writeTextRow("image_auto_link_by_character", if (autoLinkByCharacter) "Y" else "N")

        applySpecFormatting(sheet, spec, rowIndex - 1)
    }

    private fun eventTypeToLabel(eventType: String): String = when (eventType) {
        TimelineEvent.TYPE_BIRTH -> "탄생"
        TimelineEvent.TYPE_DEATH -> "사망"
        else -> "일반"
    }

    companion object {
        private const val DROPDOWN_EXTRA_ROWS = 100
        private const val MAX_DROPDOWN_ROWS = 10000
        private const val XLSX_CELL_LIMIT = EXCEL_CELL_TEXT_LIMIT // 단일 소스: SheetSpec.EXCEL_CELL_TEXT_LIMIT
    }
}
