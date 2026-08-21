package com.novelcharacter.app.excel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.PromptTemplates
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterQuote
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.DuelCandidateFilter
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.OpResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.DataValidation
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddressList
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

    /**
     * 지금 돌고 있는 구간 (B-228). 도는 것이 없으면 null이다.
     *
     * 내보내기는 구간이 하나다([TransferPhase.EXPORT_BUILD]) — 산출물을 다 만들어도
     * 건네려면(공유 시트·SAF) 화면이 있어야 하므로, 화면이 없으면 끝까지 만들 이유가 없다.
     *
     * ⚠️ **이 값은 '지금 전송이 도는가'가 아니다** — [cancelForScreenGone]이 고지 부기로
     * 내리는데 `EXPORT_SAVE`의 옮겨 쓰기는 그 뒤에도 끝까지 간다. 그 물음의 답은
     * [ActiveTransfers]가 회차의 진짜 경계에서 따로 든다.
     */
    @Volatile private var phase: TransferPhase? = null

    /**
     * 이 회차의 진행·취소 창구 (B-228).
     *
     * **사용자가 취소를 눌렀는지**를 여기서 읽는다 — 취소 버튼이 세우는 그 플래그를
     * 싱크가 이미 들고 있으므로 따로 배선하지 않는다. 눌렀으면 토스트가 이미 말했고,
     * 뒤이어 화면이 사라져도 *"중단되었습니다"*를 또 띄우지 않는다.
     */
    @Volatile private var activeProgress: ExportProgressSink? = null

    @Synchronized
    private fun ensureActiveScope(): CoroutineScope {
        if (supervisorJob.isCompleted || supervisorJob.isCancelled) {
            supervisorJob = kotlinx.coroutines.SupervisorJob()
            exportScope = CoroutineScope(Dispatchers.IO + supervisorJob)
        }
        return exportScope
    }

    private lateinit var styles: ExcelStyles

    // 한도를 넘은 드롭다운 목록의 보관처 (B-221) — populateWorkbook 1회 수명.
    // 워크북마다 새로 세운다: 시트·행 번호를 들고 있어 다음 내보내기로 넘어가면 어긋난다.
    private var dropdownLists: DropdownListSheet? = null

    // XLSX 셀 규격(32,767자) 초과로 잘린 셀 수 — 내보내기 1회 단위 집계
    private var truncatedCellCount = 0

    /** 셀 한도 초과 텍스트를 잘라 기록한다 — 값 하나 때문에 전체 내보내기가 실패(POI 예외)하지 않도록. */
    private fun org.apache.poi.ss.usermodel.Cell.setTextSafe(value: String) {
        if (value.length > XLSX_CELL_LIMIT) {
            // 경계 처리는 truncateForCell(단일 소스)이 든다 — 서러게이트 쌍을 반쪽 내지 않는다.
            setCellValue(truncateForCell(value, XLSX_CELL_LIMIT))
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
        workbook: Workbook,
        options: ExportOptions = ExportOptions(),
        progress: ExportProgressSink? = null
    ): Int {
        truncatedCellCount = 0
        styles = ExcelStyles(workbook)
        val usedSheetNames = mutableSetOf<String>()
        // 이름은 여기서 못박는다(시트는 실제로 필요할 때 생긴다) — 예약명이라 세계관 시트가
        // 가져갈 수 없고, 미리 잡아 두면 만들어지는 시점과 무관하게 이름이 같다.
        dropdownLists = DropdownListSheet(
            assignSheetName(DROPDOWN_LIST_SHEET_NAME, usedSheetNames, ownerOf = DROPDOWN_LIST_SHEET_NAME)
        )

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
                ExportSheetStep.DEFAULT_FIELDS -> exportDefaultFieldTemplates(workbook, usedSheetNames)
                ExportSheetStep.FIELD_DEFINITIONS -> exportFieldDefinitions(workbook, usedSheetNames)
                ExportSheetStep.FIELD_VALUE_LIBRARY -> exportFieldValueLibrary(workbook, usedSheetNames)
                ExportSheetStep.IMAGE_META -> exportImageMeta(workbook, usedSheetNames)
                ExportSheetStep.CHARACTERS -> exportCharacters(workbook, usedSheetNames)
                ExportSheetStep.TIMELINE -> exportTimeline(workbook, usedSheetNames)
                ExportSheetStep.STATE_CHANGES -> exportStateChanges(workbook, usedSheetNames)
                ExportSheetStep.QUOTES -> exportQuotes(workbook, usedSheetNames)
                ExportSheetStep.RELATIONSHIPS -> exportRelationships(workbook, usedSheetNames)
                ExportSheetStep.RELATIONSHIP_CHANGES -> exportRelationshipChanges(workbook, usedSheetNames)
                ExportSheetStep.NAME_BANK -> exportNameBank(workbook, usedSheetNames)
                ExportSheetStep.FACTIONS -> exportFactions(workbook, usedSheetNames)
                ExportSheetStep.FACTION_MEMBERSHIPS -> exportFactionMemberships(workbook, usedSheetNames)
                ExportSheetStep.FACTION_RELATIONSHIPS -> exportFactionRelationships(workbook, usedSheetNames)
                ExportSheetStep.PRESET_TEMPLATES -> exportUserPresetTemplates(workbook, usedSheetNames)
                ExportSheetStep.SEARCH_PRESETS -> exportSearchPresets(workbook, usedSheetNames)
                ExportSheetStep.CHARACTER_LIST_PRESETS -> exportCharacterListPresets(workbook, usedSheetNames)
                ExportSheetStep.APP_SETTINGS -> exportAppSettings(workbook, usedSheetNames, options)
                ExportSheetStep.DUEL_AXES -> exportDuelAxes(workbook, usedSheetNames)
                ExportSheetStep.DUEL_MATCHES -> exportDuelMatches(workbook, usedSheetNames)
                ExportSheetStep.DUEL_VERDICTS -> exportDuelVerdicts(workbook, usedSheetNames)
            }
            progress?.onSheets?.invoke(index + 1, plan.size)
        }
        applyTabColors(workbook)
        return truncatedCellCount
    }

    /**
     * 탭 색(P-8) — 5그룹(안내/구조/캐릭터/기록/도구·파생) 판정은 [SheetTabColors]가 든다.
     * 시트 수준 속성이라 값·왕복과 무관하고 가져오기는 탭 색을 읽지 않는다.
     *
     * `SXSSFSheet`에는 setTabColor가 없어 **XSSF 레이어로 내려가 건다** — 열 너비·고정 창처럼
     * 흘려보낸 행과 무관한 시트 속성이라 스트리밍에 안전하며(R-49의 "시트 수준 서식" 부류),
     * 두 구현이 같은 파일을 낸다는 것은 `ExportWorkbookParityTest`의 탭 색 비교가 잠근다.
     */
    private fun applyTabColors(workbook: Workbook) {
        val xssf = when (workbook) {
            is org.apache.poi.xssf.streaming.SXSSFWorkbook -> workbook.xssfWorkbook
            is org.apache.poi.xssf.usermodel.XSSFWorkbook -> workbook
            else -> return
        }
        for (i in 0 until xssf.numberOfSheets) {
            val sheet = xssf.getSheetAt(i)
            sheet.setTabColor(
                org.apache.poi.xssf.usermodel.XSSFColor(SheetTabColors.forSheet(sheet.sheetName), null)
            )
        }
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
        phase = TransferPhase.EXPORT_BUILD
        // 등재는 바깥 `finally`에서 내린다 — 고지 부기가 아니라 **일**을 따라간다.
        ActiveTransfers.enter(this)
        activeProgress = progress
        ensureActiveScope().launch {
            if (onFinished == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_preparing), Toast.LENGTH_SHORT).show()
                }
            }
            var workbook: Workbook? = null
            // 취소·실패 시 지울 산출물. 사용자에게 건넨 뒤에는 null로 되돌려 놓는다 —
            // 그때부터는 공유 시트·SAF가 쓰는 파일이라 우리가 지울 것이 아니다.
            var orphanFile: File? = null
            try {
                // 스트리밍 워크북 — 메모리가 데이터 양에 비례하지 않는다(B-72 · S7).
                // 임시 파일 자리를 먼저 못박는다(그러지 않으면 앱이 모르는 자리에 백업 크기의
                // 임시 파일이 생긴다). 두 구현이 같은 파일을 낸다는 것은 시험이 잠근다.
                ExportWorkbooks.useTempDirectory(appContext.cacheDir)
                workbook = ExportWorkbooks.create(streaming = ExportWorkbooks.isStreamingSupported())
                populateWorkbook(workbook, options, progress)

                // 내보내기 요약(시트/행 건수) — 사용 안내 시트와 드롭다운 목록 보관 시트(B-221)는
                // 데이터가 아니므로 제외한다. 세면 "시트 N개"가 사용자가 볼 시트 수와 어긋난다.
                var exportedSheets = 0
                var exportedRows = 0
                for (i in 0 until workbook.numberOfSheets) {
                    val s = workbook.getSheetAt(i)
                    if (s.sheetName == GUIDE_SHEET_NAME) continue
                    // 숨긴 시트는 사용자가 볼 것이 아니다 — 지금은 드롭다운 목록 보관처뿐이고,
                    // 이름이 아니라 '숨김'으로 거르므로 나중에 같은 부류가 늘어도 함께 빠진다.
                    if (workbook.isSheetHidden(i)) continue
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
                // 이력 한 줄만 봐도 백업이 불완전함을 알 수 있게 요약에 누락 건수를 붙인다.
                // **못 읽은 참조는 누락 건수에 합치지 않는다(B-225)** — 몇 장인지 모르는 것을
                // 아는 척 세면 "0장 누락"이라는 참말로 거짓 결론을 만든다.
                val exportSummary = appContext.getString(R.string.result_excel_exported, exportedSheets, exportedRows) +
                    (if (imageReport.hasLoss) appContext.getString(R.string.export_images_summary_suffix, imageReport.excludedCount) else "") +
                    (if (imageReport.referencesIncomplete) appContext.getString(R.string.export_images_summary_unreadable_suffix, imageReport.unreadableRefCount) else "")
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
                // **이력이 토스트보다 먼저다** (B-228). 종전에는 순서가 반대라, 스코프가 취소된
                // 상태에서는 `withContext`가 그 자리에 던져 **아래 이력 기록에 닿지 못했다** —
                // 화면이 사라진 회차만 이력에서 통째로 빠졌고, 그러면 나중에 되짚을 자리가 없다.
                logExportResult(OpResult.success(OpResult.CAT_EXCEL,
                    appContext.getString(R.string.result_excel_export_cancelled)))
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.export_cancelled), Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 화면이 사라져 끊겼다 — **실패가 아니다.** 고지·이력은 [cancelForScreenGone]이
                // 이미 남겼다(B-228). 여기서 아래 갈래로 흘려보내면 *"내보내기 실패"*가 이력에 남는다.
                orphanFile?.delete()
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Export failed", e)
                orphanFile?.delete()
                // 공간 부족은 별도 갈래로 말한다(설계 D7 · R-17) — "다시 시도하세요"는
                // 공간이 없는 사용자에게 아무것도 알려 주지 않는 안내다.
                val outOfSpace = ExportSpace.isOutOfSpace(e)
                val message = if (outOfSpace) {
                    // 잰 몫이 없으면 숫자를 말하지 않는다 (B-189) — 이미지를 끈 내보내기는
                    // 견적이 아무것도 재지 못하는데, 종전에는 그때도 "약 1MB"라고 말했다.
                    val needMb = ExportSpace.requiredMegabytesOrNull(estimateExportBytes(options))
                    if (needMb != null) appContext.getString(R.string.export_failed_no_space, needMb)
                    else appContext.getString(R.string.export_failed_no_space_unknown)
                } else {
                    appContext.getString(R.string.export_failed_retry)
                }
                // 이력이 먼저다 — 위 취소 갈래와 같은 이유다(B-228).
                logExportResult(OpResult.failure(OpResult.CAT_EXCEL,
                    appContext.getString(R.string.result_excel_export_failed),
                    listOfNotNull(if (outOfSpace) message else null, e.message).joinToString("\n").ifBlank { null }))
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                // 임시 파일까지 함께 놓는다 — 남으면 그 크기가 백업 한 판 분량이다([ExportWorkbooks.release]).
                try { ExportWorkbooks.release(workbook) } catch (e: Exception) { android.util.Log.w("ExcelExporter", "Failed to close workbook", e) }
                phase = null
                ActiveTransfers.exit(this@ExcelExporter)
                activeProgress = null
                isExporting.set(false)
                if (onFinished != null) {
                    // 스코프 취소(화면 이탈) 중에도 다이얼로그 해제는 보장한다
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { onFinished() }
                }
            }
        }
    }

    /**
     * 사용자가 고른 자리(SAF)에 산출물을 옮겨 쓴다.
     *
     * **이 쓰기는 화면이 사라져도 끊기지 않는다** (B-228 · [TransferPhase.EXPORT_SAVE]).
     * 목적지는 **사용자가 고른 실제 파일**이라, 중간에 끊기면 그 자리에 반쪽 파일이
     * 멀쩡한 이름으로 남는다 — 그래서 끊지 않고 끝까지 가고, 그래도 실패하면
     * [reportSaveFailure]가 그 반쪽을 지우러 간다(R-26 후단: 조용한 반쪽 상태 금지).
     *
     * 화면이 사라져도 도는 구간이므로 **끝나는 시점에 화면이 없을 수 있다** — 종결 고지는
     * 성공·실패 모두 [deliverTerminal]로 낸다(토스트는 앱이 앞에 없으면 API 30+가 막는다).
     *
     * 쓰기에 들어가기 전에 원본 경로를 [ExportRetryStore]에 적어 두는 것은 **프로세스가
     * 도중에 죽는 회차**를 위해서다 — 그때는 어떤 콜백도 살아남지 못하지만, 다음 실행의
     * 보관함 창이 이 기록을 읽어 다시 저장을 세운다. 성공이 지운다.
     *
     * @param onSaveFailed 실패를 컨트롤러에 돌리는 창구 — 살아 있으면 그 자리에서 재시도
     *   창을 세운다. **회전-안전은 이 콜백이 아니라 [ExportRetryStore]가 든다**(R-65):
     *   실패 처분이 콜백보다 먼저 경로를 영속 보관하므로, 콜백이 죽은 화면을 잡고 있어도
     *   다음 진입이 줍는다.
     */
    fun writeToUri(uri: Uri, sourceFile: File, onSaveFailed: (() -> Unit)? = null) {
        phase = TransferPhase.EXPORT_SAVE
        // 이 구간은 화면이 사라져도 끝까지 간다 — 그래서 등재도 그 끝까지 남아야 한다.
        ActiveTransfers.enter(this)
        ensureActiveScope().launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    ExportRetryStore.store(appContext, sourceFile)
                    val outputStream = appContext.contentResolver.openOutputStream(uri)
                    if (outputStream == null) {
                        // 열지 못했어도 CreateDocument가 만든 빈 파일이 목적지에 있다 — 같은 실패 처분으로 간다.
                        reportSaveFailure(uri, sourceFile, null, onSaveFailed)
                        return@withContext
                    }
                    outputStream.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    sourceFile.delete()
                    ExportRetryStore.clear(appContext)
                    // 이력이 토스트보다 먼저다 (B-228) — 화면이 사라진 회차만 이력에서 빠지면 안 된다.
                    logExportResult(OpResult.success(OpResult.CAT_EXCEL,
                        appContext.getString(R.string.result_excel_saved)))
                    withContext(Dispatchers.Main) {
                        deliverTerminal(
                            appContext.getString(R.string.transfer_export_saved_title),
                            appContext.getString(R.string.export_save_success)
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelExporter", "Save to URI failed", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    reportSaveFailure(uri, sourceFile, e, onSaveFailed)
                }
            } finally {
                phase = null
                ActiveTransfers.exit(this@ExcelExporter)
            }
        }
    }

    /**
     * 저장(SAF) 실패의 처분 — 셋을 순서대로 한다.
     *
     * ① **목적지의 반쪽 파일을 지운다.** CreateDocument 직후라 프로세스가 살아 있는 동안은
     *    지울 권한이 있다. 지우기는 실패할 수 있으므로 확인 없는 단정을 하지 않는다(B-225) —
     *    지웠으면 지웠다고, 못 지웠으면 남았을 수 있다고 문구를 가른다.
     * ② **원본을 영속 보관한다** — 실패 시 sourceFile은 지우지 않으므로(성공만 지운다)
     *    [ExportRetryStore]의 경로가 유효하고, 다시 저장은 처음부터 만들지 않는다(원칙 04).
     * ③ **고지하고 재시도를 돌린다** — 이력 먼저(B-228), 그다음 [deliverTerminal](화면
     *    있으면 토스트, 없으면 알림+보관함), 마지막으로 살아 있는 컨트롤러의 재시도 창.
     */
    private suspend fun reportSaveFailure(
        uri: Uri,
        sourceFile: File,
        cause: Exception?,
        onSaveFailed: (() -> Unit)?
    ) {
        val removed = runCatching {
            android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        }.getOrDefault(false)
        ExportRetryStore.store(appContext, sourceFile)
        val body = appContext.getString(
            if (removed) R.string.export_save_failed_destination_removed
            else R.string.export_save_failed_destination_unknown
        )
        logExportResult(OpResult.failure(OpResult.CAT_EXCEL,
            appContext.getString(R.string.result_excel_save_failed),
            listOfNotNull(body, cause?.message).joinToString("\n").ifBlank { null }))
        withContext(Dispatchers.Main) {
            deliverTerminal(appContext.getString(R.string.transfer_export_save_failed_title), body)
            onSaveFailed?.invoke()
        }
    }

    /** 내보내기 결과를 작업 이력에 기록한다(즉시 알림은 Toast/공유시트가 담당). */
    private fun logExportResult(result: OpResult) {
        (appContext as? NovelCharacterApp)?.operationLogRepository?.logAsync(result)
    }

    /**
     * 종결 고지를 띄울 화면 — 형제 [ExcelImporter]와 같은 WeakReference 꼴이다.
     * 컨트롤러가 exporter를 세울 때 [attachScreen]으로 넣고, 화면이 사라지면
     * [cancelForScreenGone]이 놓는다. 쓰는 쪽은 IO 코루틴이고 넣고 놓는 쪽은
     * 메인이라 `@Volatile`이 필요하다.
     */
    @Volatile private var currentActivityRef: java.lang.ref.WeakReference<android.app.Activity>? = null

    /** 종결 고지를 띄울 화면을 잇는다 — [ExcelImporter.registerLauncher]가 하는 것과 같은 일. */
    fun attachScreen(activity: android.app.Activity?) {
        currentActivityRef = activity?.let { java.lang.ref.WeakReference(it) }
    }

    /** 지금 무언가를 띄울 화면이 있는가 — 판정은 [TransferScreenPresence] 한 자리가 든다. */
    private fun hasScreen(): Boolean = TransferScreenPresence.canShow(currentActivityRef?.get())

    /**
     * 종결 고지 — 화면이 있으면 토스트, 없으면 알림 + 다음 진입 보관함 (B-56 · B-228).
     * 가져오기의 `deliverTerminal`과 같은 꼴이다. [TransferPhase.EXPORT_SAVE]는 화면이
     * 사라져도 끝까지 돌므로, 끝나는 시점의 화면 유무를 여기서 가른다 — 토스트 하나에
     * 걸면 앱이 앞에 없는 회차는 무고지가 된다(API 30+가 백그라운드 토스트를 막는다).
     */
    private fun deliverTerminal(title: String, body: String) {
        if (hasScreen()) {
            Toast.makeText(appContext, body, Toast.LENGTH_LONG).show()
            return
        }
        deliverOffscreen(title, body)
    }

    /**
     * 화면 없는 고지 — 알림은 앱 밖의 사용자에게 지금 닿고, 보관함은 알림을 못 봤거나
     * 권한을 거절한 사용자에게 다음 진입에서 닿는다(둘은 서로의 사각을 메우는 짝이다).
     *
     * @param notify 시스템 알림까지 띄우는가. 같은 화면이 곧 다시 서는 경우(회전)에는
     *   false다 — 보관함이 다음 진입에서 창으로 띄우므로 알림은 같은 말을 한 번 더 하는
     *   소음이 된다.
     */
    private fun deliverOffscreen(title: String, body: String, notify: Boolean = true) {
        com.novelcharacter.app.util.TransferNoticeRelay.store(appContext, title, body)
        if (!notify) return
        runCatching {
            com.novelcharacter.app.notification.NotificationHelper.showTransferResultNotification(
                appContext, title, body
            )
        }.onFailure {
            android.util.Log.w("ExcelExporter", "Failed to post transfer result notification", it)
        }
    }

    /**
     * 조용히 끊는다 — 부르는 쪽이 **곧바로 같은 일을 다시 시작**하는 경우에만 쓴다
     * (같은 화면에서 내보내기를 다시 누른 자리). 화면이 사라져 끊는 것은
     * [cancelForScreenGone]이다 — 그쪽은 말을 남긴다.
     */
    @Synchronized
    fun cancel() {
        supervisorJob.cancel()
    }

    /**
     * **화면이 사라졌다** — 진행 중인 내보내기를 끊고, 끊었다는 사실을 말한다 (B-228).
     *
     * 종전에는 이 자리가 [cancel] 하나였고 아무 말도 남지 않았다. 취소된 스코프 안에서는
     * `withContext(Dispatchers.Main)`이 그 자리에서 던지므로 **catch가 고지에도 이력에도
     * 닿지 못했다** — 몇 분짜리 백업이 회전 한 번에 사라지고 작업 이력에도 한 줄이 없었다.
     * 그래서 고지는 *끊기는 쪽*이 아니라 **끊는 쪽**이 한다.
     *
     * 말할 것이 있는지는 [TransferInterruption]이 정한다. 특히 **사용자가 방금 취소를
     * 눌렀으면 말하지 않는다** — 그 고지는 토스트가 이미 했고, 여기서 또 하면 취소 한 번에
     * 고지가 두 번 뜬다. 눌렸는지는 진행 창구가 들고 있는 그 플래그를 그대로 읽는다.
     *
     * @param screenReturns 같은 화면이 곧 다시 선다(회전 등 구성 변경) — 알림은 띄우지 않고
     *   보관함에만 남긴다.
     */
    @Synchronized
    fun cancelForScreenGone(screenReturns: Boolean) {
        val userCancelled = activeProgress?.isCancelled?.invoke() == true
        val kind = TransferInterruption.abortedKind(phase, userCancelled)
        phase = null
        if (kind == TransferKind.EXPORT) {
            deliverOffscreen(
                appContext.getString(R.string.transfer_export_aborted_title),
                appContext.getString(R.string.transfer_export_aborted_body),
                notify = !screenReturns
            )
            logExportResult(OpResult.success(OpResult.CAT_EXCEL,
                appContext.getString(R.string.result_excel_export_aborted)))
        }
        supervisorJob.cancel()
        // 화면 참조도 함께 놓는다 — ExcelImporter.onScreenGone이 하는 것과 같은 처분이고,
        // 이 뒤로도 도는 EXPORT_SAVE의 종결 고지가 죽은 화면에 토스트를 걸지 않게 한다.
        currentActivityRef = null
    }

    // ── 스타일 관리 (시각 개편 2026.08.14 — 사용자 확정 Q-1~Q-3, 정본: excel_visual_design_review_2026-08.md 5장) ──

    private class ExcelStyles(private val workbook: Workbook) {
        init {
            // 글꼴(P-10) — 기본 폰트를 못박는다. 아래 [font]만으로는 **평범한 데이터 셀이 남는다**
            // (`dataStyle`의 비-읽기전용 갈래와 `guideBody`는 `setFont`를 부르지 않는다).
            // 두 자리가 함께 걸려야 워크북이 덮인다 — 근거·실측은 [applyExportBaseFont]에 있다.
            applyExportBaseFont(workbook)
        }

        // 팔레트(P-1) — 앱 테두리 프리셋 1번(#5C6BC0)의 인디고 계열로 정렬. IndexedColors(엑셀 97
        // 고정 팔레트)를 커스텀 RGB로 바꿨다. 스타일은 워크북 수준 객체라 스트리밍(R-49)과 무관하다.
        private fun rgb(r: Int, g: Int, b: Int) =
            org.apache.poi.xssf.usermodel.XSSFColor(byteArrayOf(r.toByte(), g.toByte(), b.toByte()), null)

        private val headerFill = rgb(0x39, 0x49, 0xAB)    // 인디고 600 — 일반 헤더 (흰 글자 대비 7.7:1)
        private val requiredFill = rgb(0xB2, 0x3B, 0x36)  // 벽돌 빨강 — 필수(Q-2 ⓐ: "빨간 헤더" 학습·안내 문구 유지, 채도만 정제)
        private val roHeaderFill = rgb(0x75, 0x7F, 0x8C)  // 슬레이트 — 읽기전용 헤더
        private val roFill = rgb(0xEF, 0xF1, 0xF4)        // 읽기전용 셀 바탕
        private val roFillBand = rgb(0xE9, 0xEC, 0xF1)    // 읽기전용 셀 바탕(밴딩 행) — 밴딩색에 묻히지 않게 반 단계 진하다
        private val bandFill = rgb(0xF6, 0xF8, 0xFB)      // 짝수 행 밴딩(Q-3 ⓑ)
        // 읽기전용 글자 — 종전 회색-위-회색 1.6:1을 6.6:1로(V-3). "손대지 말라"는 신호는 바탕·헤더·
        // 안내가 이미 주므로 글자까지 흐릴 이유가 없다 — 코드는 읽고 붙여넣으라고 있는 값이다.
        private val roText = rgb(0x4B, 0x55, 0x63)
        private val guideInk = rgb(0x28, 0x35, 0x93)      // 안내 시트 제목·섹션 글자(인디고 800)
        private val guideBandFill = rgb(0xE8, 0xEA, 0xF6) // 안내 시트 섹션 밴드

        private fun solidFill(style: CellStyle, fill: org.apache.poi.xssf.usermodel.XSSFColor) {
            (style as org.apache.poi.xssf.usermodel.XSSFCellStyle).setFillForegroundColor(fill)
            style.fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        private fun font(bold: Boolean, points: Int, color: org.apache.poi.xssf.usermodel.XSSFColor? = null) =
            // `workbook.createFont()`가 아니다 — 그것은 매번 Calibri로 세워 돌려주므로 위 init의
            // 기본 폰트 수정이 이 자리에 미치지 않는다(실측). 글꼴은 [createExportFont]가 든다.
            createExportFont(workbook).apply {
                this.bold = bold
                fontHeightInPoints = points.toShort()
                if (color != null) (this as org.apache.poi.xssf.usermodel.XSSFFont).setColor(color)
            }

        private fun headerStyle(fill: org.apache.poi.xssf.usermodel.XSSFColor): CellStyle =
            workbook.createCellStyle().apply {
                val f = font(bold = true, points = 11)
                f.color = IndexedColors.WHITE.index
                setFont(f)
                solidFill(this, fill)
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
                setBorderBottom(BorderStyle.THIN)
            }

        val header: CellStyle = headerStyle(headerFill)
        val requiredHeader: CellStyle = headerStyle(requiredFill)
        val readOnlyHeader: CellStyle = headerStyle(roHeaderFill)

        // 숫자 서식은 위 팔레트와 독립인 표시 규칙이다:
        //  "0"    — 13자리 epoch millis의 과학표기 차단(B-222 ① / P-4). 값 불변.
        //  "0.00" — CALCULATED 소수를 앱 표시([FormulaDisplay.format]의 %.2f)와 글자까지 같게.
        //           가져오기가 읽지 않는 열(F4)이라 왕복에 관여하지 않는다.
        private val fmtPlainInt = workbook.createDataFormat().getFormat("0")
        private val fmtTwoDecimals = workbook.createDataFormat().getFormat("0.00")

        // 데이터 셀 매트릭스 — 세로 상단 정렬(wrap 행에서 위부터 읽힌다). banded는 짝수 행.
        private fun dataStyle(
            banded: Boolean,
            wrap: Boolean = false,
            readOnly: Boolean = false,
            format: Short? = null
        ): CellStyle = workbook.createCellStyle().apply {
            verticalAlignment = VerticalAlignment.TOP
            if (wrap) wrapText = true
            if (readOnly) {
                setFont(font(bold = false, points = 11, color = roText))
                solidFill(this, if (banded) roFillBand else roFill)
            } else if (banded) {
                solidFill(this, bandFill)
            }
            if (format != null) dataFormat = format
        }

        private val data = dataStyle(banded = false)
        private val dataBand = dataStyle(banded = true)
        private val dataWrap = dataStyle(banded = false, wrap = true)
        private val dataWrapBand = dataStyle(banded = true, wrap = true)
        private val dataMillis = dataStyle(banded = false, format = fmtPlainInt)
        private val dataMillisBand = dataStyle(banded = true, format = fmtPlainInt)
        private val calcDecimal = dataStyle(banded = false, format = fmtTwoDecimals)
        private val calcDecimalBand = dataStyle(banded = true, format = fmtTwoDecimals)
        private val readOnly = dataStyle(banded = false, readOnly = true)
        private val readOnlyBand = dataStyle(banded = true, readOnly = true)
        private val readOnlyWrap = dataStyle(banded = false, readOnly = true, wrap = true)
        private val readOnlyWrapBand = dataStyle(banded = true, readOnly = true, wrap = true)
        private val readOnlyMillis = dataStyle(banded = false, readOnly = true, format = fmtPlainInt)
        private val readOnlyMillisBand = dataStyle(banded = true, readOnly = true, format = fmtPlainInt)
        private val readOnlyCalcDecimal = dataStyle(banded = false, readOnly = true, format = fmtTwoDecimals)
        private val readOnlyCalcDecimalBand = dataStyle(banded = true, readOnly = true, format = fmtTwoDecimals)

        /**
         * 열 명세와 행 홀짝으로 셀 스타일 하나를 고른다 — [finishDataRow]만 부른다.
         * 종류는 [cellStyleKindFor](순수 판정 — 읽기전용이 서식을 가리지 않는 우선순위를 시험이
         * 잠근다)가 정하고, 여기는 종류×홀짝을 워크북의 실제 스타일 객체로 바꾸기만 한다.
         * [fractionalCalc]는 CALCULATED 숫자 셀의 소수 여부(정수는 서식 없이도 같은 글자라 General).
         */
        fun cellStyleFor(col: ColumnSpec, banded: Boolean, fractionalCalc: Boolean): CellStyle =
            when (cellStyleKindFor(col, fractionalCalc)) {
                CellStyleKind.READ_ONLY_MILLIS -> if (banded) readOnlyMillisBand else readOnlyMillis
                CellStyleKind.READ_ONLY_CALC_DECIMAL ->
                    if (banded) readOnlyCalcDecimalBand else readOnlyCalcDecimal
                CellStyleKind.READ_ONLY_WRAP -> if (banded) readOnlyWrapBand else readOnlyWrap
                CellStyleKind.READ_ONLY -> if (banded) readOnlyBand else readOnly
                CellStyleKind.CALC_DECIMAL -> if (banded) calcDecimalBand else calcDecimal
                CellStyleKind.MILLIS -> if (banded) dataMillisBand else dataMillis
                CellStyleKind.WRAP -> if (banded) dataWrapBand else dataWrap
                CellStyleKind.PLAIN -> if (banded) dataBand else data
            }

        val guideTitle: CellStyle = workbook.createCellStyle().apply {
            setFont(font(bold = true, points = 16, color = guideInk))
        }

        val guideSection: CellStyle = workbook.createCellStyle().apply {
            setFont(font(bold = true, points = 12, color = guideInk))
            solidFill(this, guideBandFill)
            verticalAlignment = VerticalAlignment.CENTER
        }

        val guideBody: CellStyle = workbook.createCellStyle().apply {
            wrapText = true
            verticalAlignment = VerticalAlignment.TOP
        }
    }

    // ── SheetSpec 기반 유틸리티 ──

    private fun writeHeaderRow(sheet: Sheet, spec: SheetSpec) {
        val headerRow = sheet.createRow(0)
        // 자동 필터 화살표가 가운데 정렬 헤더 글자를 덮지 않게 한 뼘 높인다(P-9).
        headerRow.heightInPoints = 24f
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

    /**
     * 한 데이터 행을 마무리한다 — **읽기 전용 칸에 스타일을 입히는 유일한 자리**(B-72).
     *
     * ⚠️ **이 호출은 그 행을 쓴 직후에, 같은 반복 안에서** 일어나야 한다. 종전에는 시트를 다
     * 쓴 뒤 [applySpecFormatting]이 되돌아가 입혔는데, 스트리밍 워크북은 창을 넘긴 행을
     * 디스크로 흘려보내고 메모리에서 버리므로 그 되돌아가기가 **조용히 아무 일도 하지
     * 않는다**(실측: 500행 중 100행만 입혀졌다 — [ExportWorkbooks] 주석). 오류가 아니라
     * 회색이 안 보이는 것뿐이라, 되돌아가는 방식으로는 빠뜨렸다는 사실 자체를 알 수 없다.
     *
     * 행마다 도는 비용이 붙지만 순회 자체는 종전과 같다 — 전에도 열마다 행 전체를 다시 돌았다
     * (오히려 읽기 전용 열이 여럿이면 그만큼 반복했다).
     */
    private fun finishDataRow(row: Row, spec: SheetSpec, banded: Boolean) {
        // 짝수 행 밴딩(Q-3 ⓑ) — [banded]는 시트 단위 결정(행 수 < BANDING_ROW_LIMIT)이고,
        // 홀짝은 rowNum이라 엑셀에서 재정렬하면 무늬가 섞인다(값 무해 — 리뷰 문서 Q-3 캐비앳).
        val bandRow = banded && row.rowNum % 2 == 0
        var lines = 1
        spec.columns.forEachIndexed { colIndex, col ->
            val cell = row.getCell(colIndex) ?: row.createCell(colIndex)
            val fractionalCalc = col.calc &&
                cell.cellType == org.apache.poi.ss.usermodel.CellType.NUMERIC &&
                cell.numericCellValue != kotlin.math.floor(cell.numericCellValue)
            cell.cellStyle = styles.cellStyleFor(col, bandRow, fractionalCalc)
            if (col.wrap && cell.cellType == org.apache.poi.ss.usermodel.CellType.STRING) {
                lines = maxOf(lines, estimateWrapLines(cell.stringCellValue, col.width))
            }
        }
        // 엑셀은 파일을 열 때 행 높이를 재계산하지 않는다 — wrap이 보이려면 여기서(행을 쓰는
        // 자리, R-49) 줄수만큼 높이를 함께 기록해야 한다. [estimateWrapLines]가 상한(4줄)을 든다.
        if (lines > 1) row.heightInPoints = lines * row.sheet.defaultRowHeightInPoints
    }

    /**
     * 커스텀 필드 표시값 셀 쓰기 — 숫자 성격 값은 숫자 셀로(Q-1 ⓐ), 나머지는 문자열로.
     *
     * - CALCULATED: 파싱되면 항상 숫자다. 가져오기가 읽지 않는 열(F4)이라 왕복 멱등성이 걸리지
     *   않고, 소수의 표시는 [ExcelStyles]의 `0.00` 서식이 앱 표시와 글자까지 맞춘다. "오류" 표식은
     *   파싱이 안 돼 자연히 문자열로 남는다(U-9의 진단 가치 유지).
     * - NUMBER: [numericExportValueOrNull]의 왕복 멱등 판정을 통과할 때만 숫자다("24.50"·"007"은
     *   문자열 유지). [SemanticRole.BIRTH_DATE] 필드는 가져오기가 `dateHint=true`로 읽어 정수
     *   숫자 셀을 날짜로 해석하므로 제외한다 — 판정 소스는 가져오기와 같은 [SemanticRole.fromConfig].
     * - 그 외 타입: 종전대로 문자열([setTextSafe]).
     */
    private fun org.apache.poi.ss.usermodel.Cell.setFieldValue(field: FieldDefinition, value: String) {
        if (value.isNotEmpty()) {
            if (field.fieldType == FieldType.CALCULATED) {
                val d = value.toDoubleOrNull()
                if (d != null && d.isFinite()) { setCellValue(d); return }
            } else if (field.fieldType == FieldType.NUMBER && !isBirthDateField(field)) {
                val d = numericExportValueOrNull(value)
                if (d != null) { setCellValue(d); return }
            }
        }
        setTextSafe(value)
    }

    /**
     * BIRTH_DATE 판정 캐시 — 판정([SemanticRole.fromConfig])은 config의 순수 함수인데 셀마다
     * JSON 파싱을 새로 내면 행×필드만큼 든다(통계가 S6 5차에 걷어낸 그 행별 config 파싱 모양).
     * 키가 config 원문이라 어떤 시점의 어떤 필드가 와도 값이 낡을 수 없다.
     */
    private val birthDateByConfig = HashMap<String, Boolean>()

    private fun isBirthDateField(field: FieldDefinition): Boolean =
        birthDateByConfig.getOrPut(field.config) {
            com.novelcharacter.app.data.model.SemanticRole.fromConfig(field.config) ==
                com.novelcharacter.app.data.model.SemanticRole.BIRTH_DATE
        }

    /**
     * 시트 수준 서식 — 목록·너비·고정·필터. **셀을 만지지 않는다**(위 [finishDataRow] 참조).
     * 넷 다 스트리밍 워크북에서 그대로 동작하는 것을 실측으로 확인했다.
     */
    private fun applySpecFormatting(sheet: Sheet, spec: SheetSpec, dataRowCount: Int) {
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
        sheet.freezeAndFilter(spec.columns.size, dataRowCount, spec.freezeCols)
    }

    // ── 기존 유틸리티 ──

    private fun Sheet.freezeAndFilter(lastCol: Int, dataRowCount: Int, freezeCols: Int) {
        // 헤더 행 + 정체 열(spec.freezeCols — V-6). 넓은 시트를 오른쪽으로 넘겨도 행의 주인이 보인다.
        createFreezePane(freezeCols, 1)
        if (dataRowCount > 0) {
            setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, lastCol - 1))
        }
    }

    private fun addDropdownValidation(
        sheet: Sheet,
        colIndex: Int,
        dataRowCount: Int,
        options: List<String>
    ) {
        if (options.isEmpty()) return
        val maxRow = minOf(maxOf(dataRowCount + DROPDOWN_EXTRA_ROWS, 1), MAX_DROPDOWN_ROWS)
        val addressList = CellRangeAddressList(1, maxRow, colIndex, colIndex)
        val dvHelper = sheet.dataValidationHelper
        // 명시 목록이 엑셀 한도(255자)를 넘거나 값에 쉼표·따옴표가 있으면 수식이 깨져
        // **유효성 검사가 통째로 벗겨진다** — 그때는 숨김 시트 + 범위 참조로 간다 (B-221).
        val lists = dropdownLists
        val dvConstraint = if (lists != null && !DropdownListLimits.fitsExplicitList(options)) {
            dvHelper.createFormulaListConstraint(lists.referenceFor(sheet.workbook, options))
        } else {
            dvHelper.createExplicitListConstraint(options.toTypedArray())
        }
        val validation = dvHelper.createValidation(dvConstraint, addressList)
        validation.showErrorBox = true
        validation.errorStyle = DataValidation.ErrorStyle.WARNING
        // 두 상자에도 각자의 한도가 있다 — 목록을 그대로 넣으면 목록이 길어질수록 **안내가
        // 먼저 깨진다.** 몇 개를 뺐는지 말하며 줄인다(말없이 자르지 않는다).
        val errorTemplate = appContext.getString(R.string.export_validation_error_message, "")
        validation.createErrorBox(
            appContext.getString(R.string.export_validation_error_title),
            appContext.getString(
                R.string.export_validation_error_message,
                DropdownListLimits.summarize(
                    options,
                    (DropdownListLimits.MAX_ERROR_CHARS - errorTemplate.length).coerceAtLeast(16)
                )
            )
        )
        validation.showPromptBox = true
        validation.createPromptBox(
            appContext.getString(R.string.export_validation_prompt_title),
            DropdownListLimits.summarize(options, DropdownListLimits.MAX_PROMPT_CHARS)
        )
        sheet.addValidationData(validation)
    }

    private fun saveWorkbook(workbook: Workbook, fileName: String): File {
        val exportsDir = File(appContext.cacheDir, "exports")
        exportsDir.mkdirs()
        // 저장(SAF) 실패로 보관 중인 파일은 회전 정리에서 뺀다 — 지우면 재시도 창의
        // "보관되어 있습니다"가 거짓이 된다([ExportRetryStore]가 실존 확인으로 닫는 약속).
        val retained = ExportRetryStore.rawPath(appContext)
        exportsDir.listFiles()?.filter { it.absolutePath != retained }
            ?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }

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

    private data class GuideLine(val section: String, val style: CellStyle, val text: String)

    private fun exportInstructions(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val sheetName = assignSheetName(GUIDE_SHEET_NAME, usedSheetNames, ownerOf = GUIDE_SHEET_NAME)
        val sheet = workbook.createSheet(sheetName)

        val lines = listOf(
            GuideLine("", styles.guideTitle, "NovelCharacter 엑셀 파일 편집 안내"),
            GuideLine("", styles.guideBody, ""),
            // 색상 범례 — 견본 칸(A열)에 실제 헤더 스타일을 입힌다(V-7: 색을 설명하는 자리에
            // 색이 없었다). 문구는 감사(WD-1)가 다듬은 그대로다 — 검정 ■만 실색 견본으로 바뀐다.
            GuideLine("색상 안내", styles.guideSection, ""),
            GuideLine("일반 컬럼", styles.header, "파란 헤더 = 편집 가능한 일반 컬럼"),
            GuideLine("필수 컬럼", styles.requiredHeader, "빨간 헤더 = 필수 입력 컬럼 (비워두면 대개 해당 행을 읽지 않습니다)"),
            GuideLine("", styles.guideBody, "  예외: '필드 정의'·'필드 데이터'·'캐릭터 필드값' 시트의 세계관 칸은 비우면 전역(모든 세계관 공통) 필드를 뜻합니다."),
            GuideLine("앱이 채움", styles.readOnlyHeader, "회색 헤더/셀 = 앱이 채우는 열 (그대로 두세요 — 예외는 아래 '코드 컬럼 안내')"),
            GuideLine("", styles.guideBody, ""),
            // 주의사항을 앞으로 — 시트명·헤더 행처럼 어기면 되돌리기 어려운 경고가 종전에는
            // 맨 끝(약 200행 아래)에 있어 읽히기 전에 스크롤이 끝났다(P-7).
            GuideLine("주의사항", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 시트 이름을 변경하지 마세요 (가져오기 시 시트명으로 데이터를 찾습니다)"),
            GuideLine("", styles.guideBody, "• 헤더 행(1행)을 삭제하지 마세요 (컬럼 순서 변경은 가능합니다)"),
            GuideLine("", styles.guideBody, "• 행을 추가하여 새 데이터를 입력할 수 있습니다"),
            // 종전 문구는 "이미지경로 컬럼은 … 수정하지 마세요"라는 **일괄 금지**였는데,
            // 캐릭터·세계관·작품 시트의 그 열은 파란 헤더(편집 가능)이고 가져오기가 실제로
            // 읽어 반영한다 — 안내가 실동작과 어긋나 있었다 (B-222 ③).
            GuideLine("", styles.guideBody, "• 이미지경로는 앱 내부 경로입니다. 캐릭터·세계관·작품 시트에서는 편집이 반영되지만,"),
            GuideLine("", styles.guideBody, "  적을 값은 이 파일에 이미 있는 경로여야 합니다(새 경로를 지어내면 그림이 없는 자리가 됩니다)."),
            GuideLine("", styles.guideBody, "  '이미지' 시트의 이미지경로는 회색 — 그 행의 정체이므로 고치지 마세요."),
            GuideLine("", styles.guideBody, "• 태그는 쉼표(,)로 구분하여 입력하세요"),
            GuideLine("", styles.guideBody, "• 이 '사용 안내' 시트는 가져오기 시 무시됩니다"),
            // '완전한 백업입니다' 고지가 이 셋의 미수록을 말하지 않아, 기기 이전 뒤 휴지통
            // 복구가 안 된다는 사실을 복원 시점에야 알게 됐다(검증 CONFIRMED — 말하는 수리).
            GuideLine("", styles.guideBody, "• 휴지통·작업 이력·최근 활동은 기기 안의 기록이라 이 파일에 실리지 않습니다."),
            GuideLine("", styles.guideBody, "  기기를 옮기기 전에 휴지통에서 되살릴 것을 먼저 되살려 주세요."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("길이 제한", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 셀당 최대 32,767자(엑셀 규격) — 초과분은 내보내기 시 잘려 기록됩니다."),
            GuideLine("", styles.guideBody, "• 가져오기도 동일하게 32,767자까지 저장됩니다 — 내보낸 파일을 그대로 들여오면 잘리지 않습니다."),
            GuideLine("", styles.guideBody, "• '앱 설정' 시트의 AI 메시지 양식 행은 앱 상한(${AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS}자)이 먼저 걸립니다 — 넘으면 자르지 않고 그 행만 건너뜁니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("빈 시트 안내", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "아직 데이터가 없는 종류도 머리글만 있는 빈 시트로 함께 나갑니다. 여기에 행을 적어 새 데이터를 만듭니다."),
            GuideLine("", styles.guideBody, "• 캐릭터가 없는 세계관도 시트가 만들어집니다. 그 세계관의 필드가 열로 준비되어 있습니다."),
            GuideLine("", styles.guideBody, "• 빈 시트는 '엑셀에 없는 항목 삭제'의 대상이 아닙니다 — 행이 하나도 없는 시트로는 기존 데이터를 지우지 않습니다."),
            GuideLine("", styles.guideBody, "• 그래서 실수로 행을 모두 지운 파일을 들여와도 그 종류가 통째로 사라지지 않습니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("코드 컬럼 안내 (중요)", styles.guideSection, ""),
            // 13자리 밀리초 값은 외부 편집자에게 아무 뜻이 없다 — "지우지 마세요"만 있고
            // **무엇인지**가 없어서, 날짜로 보이는 칸을 날짜로 고쳐 매칭을 깨뜨릴 수 있었다 (B-222 ①).
            GuideLine("", styles.guideBody, "• '생성일'·'수정일'·'판정일'·'뗀날짜'의 13자리 숫자는 1970-01-01 UTC부터의 밀리초입니다."),
            GuideLine("", styles.guideBody, "  사람이 읽을 날짜가 아니라 행을 알아보는 값이라, 날짜 서식으로 바꾸거나 비우면 매칭이 갈립니다."),
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
            GuideLine("", styles.guideBody, "• 이름 은행: 코드로 매칭합니다(이름·성별을 고쳐도 같은 항목으로 인식). 코드가 없으면 이름+성별로 매칭. 사용여부는 Y/N"),
            GuideLine("", styles.guideBody, "• 이미지: '태그'와 '링크그룹' 열을 직접 편집할 수 있습니다 (파일명은 앱이 채우는 열입니다)"),
            GuideLine("", styles.guideBody, "  링크그룹은 같은 문자열을 적은 행끼리 한 묶음이 됩니다 — 아무 이름이나 써도 되고, 두 장 이상일 때만 묶입니다"),
            GuideLine("", styles.guideBody, "  칸을 비우면 그 이미지의 링크가 풀립니다. 'char:'로 시작하는 값은 캐릭터 자동 링크라"),
            GuideLine("", styles.guideBody, "  가져온 뒤 현재 배정 기준으로 다시 계산됩니다 (직접 적을 필요가 없습니다)"),
            GuideLine("", styles.guideBody, "  '뗀날짜' 칸을 비우면 뗀 이미지 서랍에서 꺼냅니다(뗀 적 없음이 됩니다). '뗀곳'은 앱이 채우는 열입니다"),
            GuideLine("", styles.guideBody, "• 대결 기록: 승자 칸은 참가자 이름(또는 코드)입니다. 비우거나 '${DuelSheetLabels.WINNER_DRAW}'이라 적으면 무승부입니다"),
            GuideLine("", styles.guideBody, "  두 참가자의 이름이 같으면 승자 칸에 코드를 적어 주세요. 행의 '코드' 칸은 그 판의 정체이니 지우지 마세요"),
            GuideLine("", styles.guideBody, "• 대결 상성: '참가자들'의 적힌 차례에 뜻이 있습니다(천적은 센 쪽이 앞, 순환은 이기는 차례)."),
            GuideLine("", styles.guideBody, "  종류는 '${DuelSheetLabels.KIND_COUNTER}'/'${DuelSheetLabels.KIND_UNDECIDED}' 중 하나입니다"),
            GuideLine("", styles.guideBody, ""),
            // '앱 설정' 시트 안내 (사용자 요청 2026.08.20) — 시트별 안내가 열일곱 시트를 다루면서
            // **이 시트만 한 줄도 없었다.** 값을 고칠 수는 있는데 무엇을 뜻하고 어떤 값을 받는지
            // 알 길이 없던 자리다. 행별 뜻은 시트 안의 '설명'·'입력 가능한 값' 칸이 든다.
            GuideLine("'앱 설정' 시트", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "한 행이 설정 하나입니다. 고치는 칸은 '설정값' 하나뿐입니다."),
            GuideLine("", styles.guideBody, "• '설명'과 '입력 가능한 값'은 앱이 채우는 회색 칸입니다. 여기에 적은 것은 가져오기에 반영되지 않습니다."),
            GuideLine("", styles.guideBody, "• 그 행이 어떤 값을 받는지는 같은 행의 '입력 가능한 값' 칸에 적혀 있습니다 — 범위·목록·빈 칸의 뜻까지."),
            GuideLine("", styles.guideBody, "• '설정키'는 앱이 그 설정을 알아보는 이름입니다. 고치면 그 행을 못 알아보고 건너뜁니다."),
            GuideLine("", styles.guideBody, "• 이 버전이 모르는 설정키는 건너뛰고 개수를 알려 드립니다(옛 파일·새 파일 모두 그대로 들여올 수 있습니다)."),
            GuideLine("", styles.guideBody, "• 뜻을 알 수 없는 값은 그 행만 건너뛰고 사유를 알려 드립니다. 그 설정은 종전 값 그대로입니다."),
            GuideLine("", styles.guideBody, "  다만 쉼표로 여럿 적는 설정(패턴 유형·어시스턴트 항목)은 **아는 이름만 적용하고** 모르는 이름을 알려 드립니다."),
            GuideLine("", styles.guideBody, "  `키=값` 목록(패턴 민감도)도 같습니다 — 읽은 키만 반영하고 나머지는 종전 값으로 둡니다."),
            GuideLine("", styles.guideBody, "• 범위를 벗어난 숫자는 좁혀서 받는 설정도 있습니다 — 그런 설정은 '입력 가능한 값'이 범위를 적어 둡니다."),
            GuideLine("", styles.guideBody, "  좁혀서 받으면 조용히 넘어가지 않습니다 — 무엇으로 저장했는지 가져오기 결과에서 그 행 단위로 알려 드립니다."),
            GuideLine("", styles.guideBody, "• '설정값' 열 자체를 지우면 이 시트를 통째로 건너뜁니다(설정이 지워지지는 않습니다)."),
            GuideLine("", styles.guideBody, "• 행을 지워도 그 설정이 지워지지는 않습니다. 값을 바꾸려면 행을 남기고 '설정값'을 고치세요."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("AI 메시지 양식 (ai_tpl_ 로 시작하는 행)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "AI에 실제로 보내는 글입니다. 여기서 고치면 다음 요청부터 그 글이 나갑니다."),
            GuideLine("", styles.guideBody, "• 여러 줄 글이라 셀 안에서 Alt+Enter로 줄을 바꿔 편집하세요. 칸을 비우면 기본 양식으로 돌아갑니다."),
            GuideLine("", styles.guideBody, "• 한 행은 '지시문'(AI의 역할과 규칙), 다른 행은 '재료'(이번 요청에 실을 캐릭터·사건 정보)입니다."),
            GuideLine("", styles.guideBody, "• 글 안의 ${PromptTemplates.OPEN}이름${PromptTemplates.CLOSE} 은 앱이 값으로 바꿔 넣는 자리입니다."),
            GuideLine("", styles.guideBody, "  쓸 수 있는 이름은 같은 행의 '입력 가능한 값' 칸에 뜻과 함께 적혀 있습니다."),
            GuideLine("", styles.guideBody, "  이름 앞뒤에 공백을 넣지 마세요 — ${PromptTemplates.OPEN} 이름 ${PromptTemplates.CLOSE} 은 다른 글자로 읽습니다."),
            GuideLine("", styles.guideBody, "• '반드시 들어가야 하는 자리'가 빠지거나, '(한 번만)'이 붙은 자리가 두 번 나오면 그 행은 적용하지 않고 사유를 알려 드립니다."),
            GuideLine("", styles.guideBody, "  앱이 답을 읽는 형식 지시가 거기 들어가기 때문입니다 — 빠지면 답이 와도 읽지 못합니다."),
            GuideLine("", styles.guideBody, "• 자리표가 든 줄은 그 자리가 빌 때 줄째로 빠집니다. `태그: ` 처럼 이름표만 남지 않게 하기 위해서입니다."),
            GuideLine("", styles.guideBody, "  `[…]`로 시작하는 절 이름도 **그 절의 줄이 하나도 안 남으면** 함께 빠집니다."),
            GuideLine("", styles.guideBody, "  보기 좋으라고 절 이름 아래에 빈 줄을 넣어도 됩니다 — 그것 때문에 절이 사라지지는 않습니다."),
            GuideLine("", styles.guideBody, "• 값 안에 ${PromptTemplates.OPEN}…${PromptTemplates.CLOSE} 이 들어 있어도 자리표로 읽지 않습니다(한 번만 바꿔 넣습니다)."),
            GuideLine("", styles.guideBody, "• 이 칸에는 지금 나가는 글이 그대로 실립니다 — 고치지 않았으면 기본 양식이 실립니다."),
            GuideLine("", styles.guideBody, "  그래서 앱 판이 다른 파일을 들이면 **그때의 기본 양식이 고친 양식으로 굳습니다.**"),
            GuideLine("", styles.guideBody, "  되돌리려면 그 칸을 비워 다시 가져오거나, 앱의 양식 편집 창에서 '기본값으로'를 누르세요."),
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
            GuideLine("'작품 필드값'·'사건 필드값' 시트", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 작품·연표 시트는 모든 세계관의 필드를 열로 싣습니다. 열 이름이 맞으면 다른 세계관의"),
            GuideLine("", styles.guideBody, "  필드여도 값이 그대로 되돌아옵니다 — 세계관을 옮긴 뒤 남은 값도 유실되지 않습니다."),
            GuideLine("", styles.guideBody, "• 같은 이름의 필드가 한 구역에 둘 있으면 열이 하나만 서므로, 나머지 값이 이 시트로 옵니다."),
            GuideLine("", styles.guideBody, "  담을 값이 없으면 시트 자체가 만들어지지 않습니다(정상입니다)."),
            GuideLine("", styles.guideBody, "• 정체성은 작품코드/사건코드 + 세계관 + 필드키입니다 — 이 열들을 수정하면 값이 다른 곳에 붙습니다."),
            GuideLine("", styles.guideBody, "• 같은 항목이 작품·연표 시트에도 있으면 그쪽이 우선하며 이 시트의 행은 무시됩니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("'캐릭터 명대사' 시트", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 한 캐릭터가 대사를 여럿 가질 수 있습니다. '표시순서'가 앱 목록의 차례입니다."),
            GuideLine("", styles.guideBody, "• '상황' 칸이 비어 있으면 일반 명대사이고, __birthday 이면 생일 전용입니다."),
            GuideLine("", styles.guideBody, "  이 글자는 그대로 두세요 — '생일'처럼 고쳐 적으면 앱은 그것을 직접 만든 상황 이름으로"),
            GuideLine("", styles.guideBody, "  받아들여, 그 대사가 생일 축하 창에 더 이상 뜨지 않습니다(값은 지워지지 않습니다)."),
            GuideLine("", styles.guideBody, "• 그 둘 말고 원하는 상황 이름을 직접 적어도 됩니다(예: 첫 등장). 앱에도 그대로 보입니다."),
            GuideLine("", styles.guideBody, "• 대사 글자를 고쳐도 '코드' 열이 같으면 같은 대사를 고친 것으로 인식합니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("필드 열의 '(쉼표 구분)' 표시", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 열 머리에 '(쉼표 구분)'이 붙은 칸은 쉼표로 여러 값을 적는 칸입니다(캐릭터·작품·연표 공통)."),
            GuideLine("", styles.guideBody, "• 값 자체에 쉼표를 넣으려면 그 값을 따옴표로 감싸세요: \"홍길동, 어릴 적 이름\", 아무개"),
            GuideLine("", styles.guideBody, "  값 안의 따옴표는 두 번 겹쳐 씁니다(엑셀·CSV와 같은 방식)."),
            GuideLine("", styles.guideBody, "• 표시가 없는 옛 파일도 그대로 읽습니다 — 열 이름을 고치지 않아도 됩니다."),
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
            GuideLine("'기본 필드' 시트 (모든 세계관이 갖는 필드)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "모든 세계관에 기본으로 심기는 필드의 원본입니다. 세계관 열이 없는 것은 이 필드가 특정 세계관에 속하지 않기 때문입니다."),
            GuideLine("", styles.guideBody, "• 여기 있는 것은 원본이고, 실제로 쓰이는 것은 각 세계관에 심긴 '필드 정의' 시트의 필드입니다."),
            GuideLine("", styles.guideBody, "• 템플릿은 '코드'로 묶입니다(코드가 없으면 대상+필드키). '필드 정의' 시트의 '기본필드코드' 열에 그 코드를 적으면 그 필드가 기본 필드와 연결됩니다."),
            GuideLine("", styles.guideBody, "• 기본 필드는 칸이 '있다'는 것만 보장합니다. 세계관마다 이름·설정을 다르게 고쳐도 되고, 고친 것은 앱에서 전파를 고를 때까지 유지됩니다."),
            GuideLine("", styles.guideBody, "• '기본필드코드' 칸을 비우면 그 필드는 연결이 풀려 보통 필드가 됩니다(필드와 값은 그대로입니다). 열을 통째로 지우면 기존 연결이 유지됩니다."),
            GuideLine("", styles.guideBody, "• 가리키는 기본 필드가 파일에도 앱에도 없으면 거부하지 않고 보통 필드로 들여온 뒤 결과에 알립니다."),
            GuideLine("", styles.guideBody, "• 이 시트에서 기본 필드를 지워도 이미 심긴 필드와 값은 지워지지 않습니다 — 연결만 풀립니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("'필드 데이터' 시트 (값 정리)", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "필드마다 실제로 쓰인 값이 모이는 시트입니다. 여기서 정리한 표기가 앱의 자동완성·통계·검색에 함께 반영됩니다."),
            GuideLine("", styles.guideBody, "• '표시라벨'·'${FieldValueSheetMapper.ALIAS_HEADER}'·'카테고리'·'설명'·'숨김' 열을 채워 다시 가져오면 그대로 반영됩니다."),
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
            GuideLine("'대결 축' 시트의 필드 연결", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• '영향필드'·'산출필드'·'프로필필드'는 필드키를 쉼표로 이어 적는 칸입니다."),
            GuideLine("", styles.guideBody, "  필드키 자체에 쉼표가 있으면 그 키를 따옴표로 감싸세요: \"내, 키\", 힘"),
            GuideLine("", styles.guideBody, "• '영향필드'는 적은 차례가 곧 영향력 순위입니다(맨 앞이 1순위)."),
            GuideLine("", styles.guideBody, "• 키 앞에 ▼를 붙이면 '값이 작을수록 유리'라는 뜻입니다. 예) ▼나이"),
            GuideLine("", styles.guideBody, "  앞에 -를 붙인 옛 파일도 그대로 읽습니다. 앱은 이제 ▼로 내보냅니다."),
            GuideLine("", styles.guideBody, "  ('프로필필드'는 견주지 않는 칸이라 ▼가 뜻을 갖지 않습니다.)"),
            GuideLine("", styles.guideBody, "• 캐릭터 표의 열도 sys:이름으로 적을 수 있습니다. 예) sys:another_name(이명)"),
            GuideLine("", styles.guideBody, "  앱이 모르는 sys: 이름은 값이 영영 비므로 가져오기 결과에서 알려 드립니다."),
            GuideLine("", styles.guideBody, ""),
            GuideLine("테두리 색상", styles.guideSection, ""),
            GuideLine("", styles.guideBody, "• 세계관/작품 시트에서 테두리색(HEX), 테두리두께를 설정할 수 있습니다."),
            GuideLine("", styles.guideBody, "• 작품의 테두리를 비워두면 세계관 색상을 상속합니다.")
        )

        // 구조(P-7): A열은 좁은 견본·섹션 열, B열 하나가 본문이다 — 종전에는 본문이 A열에 들어가고
        // B열(25000)이 항상 비어, 읽는 폭 따로 노는 폭 따로였다. 섹션 행은 두 칸을 같은 밴드로
        // 채워 200행 텍스트 벽을 끊는다. 이 시트는 가져오기가 읽지 않아(GUIDE_SHEET_NAME 무시)
        // 어떤 배치 변경도 왕복에 무해하다.
        lines.forEachIndexed { rowIndex, line ->
            val row = sheet.createRow(rowIndex)
            when {
                line.section.isNotBlank() && line.style === styles.guideSection -> {
                    row.heightInPoints = 20f
                    row.createCell(0).apply { setCellValue(line.section); cellStyle = line.style }
                    row.createCell(1).apply { setCellValue(line.text); cellStyle = styles.guideSection }
                }
                line.section.isNotBlank() -> {
                    // 색상 견본 행 — A칸이 실제 헤더 스타일 그대로의 견본, B칸이 설명(V-7)
                    row.heightInPoints = 18f
                    row.createCell(0).apply { setCellValue(line.section); cellStyle = line.style }
                    row.createCell(1).apply { setCellValue(line.text); cellStyle = styles.guideBody }
                }
                line.style === styles.guideTitle -> {
                    row.heightInPoints = 26f
                    row.createCell(1).apply { setCellValue(line.text); cellStyle = line.style }
                }
                else -> {
                    row.createCell(1).apply { setCellValue(line.text); cellStyle = line.style }
                    // 본문 wrap 높이 — 엑셀은 열 때 행 높이를 재계산하지 않는다(finishDataRow와 같은 근거)
                    val wrapLines = estimateWrapLines(line.text, GUIDE_BODY_WIDTH)
                    if (wrapLines > 1) row.heightInPoints = wrapLines * sheet.defaultRowHeightInPoints
                }
            }
        }

        sheet.setColumnWidth(0, 4600)
        sheet.setColumnWidth(1, GUIDE_BODY_WIDTH)
    }

    // ── 세계관 ──

    private suspend fun exportUniverses(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = universes.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, universes.size)
    }

    // ── 작품 ──

    private suspend fun exportNovels(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val novels = db.novelDao().getAllNovelsList()
        val universes = db.universeDao().getAllUniversesList()

        val universeMap = universes.associateBy { it.id }
        val charCodeMap = db.characterDao().getAllCharactersList().associate { it.id to it.code }

        // 작품 커스텀 필드 (확-3) — 헤더 규칙은 EntityFieldHeaders 단일 소스이고
        // 가져오기가 같은 규칙의 역함수로 되짚는다(연표 시트의 사건 필드 열과 같은 방식).
        val novelFields = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_NOVEL)
        // 열은 헤더가 유일한 것만 선다 — 나머지는 '작품 필드값' 시트가 담는다(B-65).
        val novelFieldPlan = EntityFieldHeaders.plan(
            novelFields,
            universeMap.mapValues { (_, u) -> u.name }
        )
        val novelFieldColumns = novelFieldPlan.columns
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
                fieldValues[fieldDef.id]?.let { row.createCell(15 + fi).setFieldValue(fieldDef, it.value) }
            }
            finishDataRow(row, spec, banded = novels.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, novels.size)

        exportNovelFieldValueOverflow(
            workbook, usedSheetNames, novels, novelFieldValuesByNovel,
            novelFieldPlan.coveredFieldIds, novelFields, universeMap
        )
    }

    /**
     * '작품 필드값' 오버플로 (B-65) — 작품 시트가 열로 담지 못한 값만 담는다.
     *
     * 선별은 [NovelFieldValueOverflow]가 단일 소스이고, `covered`는 **열을 실제로 그린 그 계획**이
     * 낸 집합이다(`EntityFieldHeaders.plan`). 채우는 쪽과 고르는 쪽이 갈리면 같은 값이 두 시트에
     * 겹쳐 나가거나 어느 시트에도 안 나간다.
     */
    private suspend fun exportNovelFieldValueOverflow(
        workbook: Workbook,
        usedSheetNames: MutableSet<String>,
        novels: List<Novel>,
        valuesByNovel: Map<Long, List<com.novelcharacter.app.data.model.NovelFieldValue>>,
        coveredFieldIds: Set<Long>,
        novelFields: List<FieldDefinition>,
        universeMap: Map<Long, Universe>
    ) {
        val fieldsById = novelFields.associateBy { it.id }
        // 정렬을 (작품 displayOrder, 필드 displayOrder, 필드키)로 고정 — 무편집 왕복 멱등성의 근거
        val rows = novels.sortedWith(compareBy({ it.displayOrder }, { it.id }))
            .flatMap { novel ->
                NovelFieldValueOverflow
                    .select(valuesByNovel[novel.id].orEmpty(), coveredFieldIds, fieldsById)
                    .sortedWith(compareBy({ it.second.displayOrder }, { it.second.key }))
                    .map { (value, fd) -> Triple(novel, fd, value.value) }
            }
        if (rows.isEmpty()) return  // 다른 시트와 동일 — 빈 시트는 만들지 않는다

        val spec = novelFieldValueSpec(universeMap.values.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        rows.forEachIndexed { index, (novel, fd, value) ->
            val universe = fd.universeId?.let { universeMap[it] }
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(novel.code)
            row.createCell(1).setTextSafe(novel.title)
            row.createCell(2).setTextSafe(universe?.name ?: "")
            row.createCell(3).setTextSafe(universe?.code ?: "")
            row.createCell(4).setTextSafe(fd.key)
            row.createCell(5).setTextSafe(fd.name)
            row.createCell(6).setFieldValue(fd, value)
            finishDataRow(row, spec, banded = rows.size < BANDING_ROW_LIMIT)
        }
        applySpecFormatting(sheet, spec, rows.size)
    }

    // ── 필드 정의 ──

    private suspend fun exportFieldDefinitions(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val allFields = mutableListOf<Pair<Long?, FieldDefinition>>()
        for (universe in universes) {
            // 구역 id를 nullable로 못박아 담는다 — 전역 행(null)과 **한 목록**에 들어가므로
            // 여기서 타입을 맞춰 두면 아래 행 쓰기가 두 경우를 한 갈래로 다룬다.
            val scopeId: Long? = universe.id
            // **모든 종류**를 왕복한다(캐릭터·사건·작품) — 정의가 파일에 없으면 신규 기기
            // 복원 시 그 종류의 필드값이 통째로 유실된다(대상 열로 구분). 종류를 늘릴 때
            // 여기를 잊으면 새 종류만 조용히 빠진다(R-29) — 그래서 전 종류 조회를 쓴다.
            val fields = db.fieldDefinitionDao().getFieldsByUniverseAllTypes(universe.id)
            fields.forEach { allFields.add(scopeId to it) }
        }
        // 전역 구역(universeId IS NULL — B-119 확장)도 **같은 시트에** 싣는다. 세계관·세계관코드
        // 두 칸이 빈 행이 곧 전역이라는 것이 이 시트의 약속이고(설계 1-9), 가져오기·미리보기는
        // 이미 그렇게 읽는다 — 셋 중 이 자리만 빠져 있었다(B-130).
        //
        // 빠져 있는 동안 **앱이 만든 어떤 백업에도 전역 필드 행이 없었고**, 그 백업을 덮어쓰기로
        // 되돌리면 pruneUnmatchedFieldDefinitions가 '백업에 없는 정의'로 보고 지웠다 —
        // 값(CharacterFieldValue)은 FK CASCADE로 함께 사라지고 휴지통도 지나지 않는다.
        // 세계관 순회와 **한 그릇에 담는** 이유가 이것이다: 그릇을 나누면 아래 행 쓰기·prune·
        // 미리보기 중 어느 하나가 또 빠져도 드러나지 않는다.
        db.fieldDefinitionDao().getGlobalFieldsAllTypes().forEach { allFields.add(null to it) }

        // 등급 체계 참조(U-1)는 전용 열로만 나간다 — code → 체계로 풀어 이름·코드를 싣는다.
        val systemsByCode = db.gradeSystemDao().getAllList().associateBy { it.code }
        // 전역 기본 필드 연결(B-119)도 같은 규약 — 살아 있는 템플릿만 코드로 나간다.
        val templatesByCode = db.defaultFieldTemplateDao().getAllList().associateBy { it.code }
        val spec = fieldDefinitionSpec(
            universes.map { it.name },
            systemsByCode.values.map { it.name }.distinct()
        )
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allFields.forEachIndexed { index, (universeId, field) ->
            // 전역 구역은 universeId가 null이라 세계관을 찾지 않는다 — 아래 이름·코드 두 칸이
            // 빈 칸으로 나가고, 그 빈 칸이 가져오기·미리보기에게 '전역'을 뜻한다(설계 1-9).
            val universe = if (universeId == null) null else universeMap[universeId]
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
            // 전역 기본 필드 연결(B-119) — 템플릿이 실제로 있을 때만 싣는다. 이미 지워진
            // 템플릿을 가리키는 잔재는 빈칸으로 나간다(위 등급 체계 참조와 같은 근거:
            // 그대로 다시 들이면 강등과 같은 결과라, 파일이 앱보다 넓은 약속을 하지 않는다).
            val linkedTemplate = com.novelcharacter.app.data.model.DefaultFieldRef
                .codeFromConfig(field.config)?.let { templatesByCode[it] }
            row.createCell(14).setTextSafe(linkedTemplate?.code ?: "")
            finishDataRow(row, spec, banded = allFields.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allFields.size)
    }

    // ── 전역 기본 필드 템플릿 (B-119) ──

    /**
     * '기본 필드' 시트 — 전역이라 세계관 열이 없다(설계 1-5).
     *
     * **시트 순서에서 '필드 정의'보다 앞에 둔다** — 가져오기 순서가 그렇고(그쪽이 이 시트의
     * 템플릿을 찾아야 한다), 사람이 파일을 열었을 때도 *정의가 참조보다 앞*이라는 이 파일의
     * 규약과 같은 모양이 된다.
     */
    private suspend fun exportDefaultFieldTemplates(
        workbook: Workbook,
        usedSheetNames: MutableSet<String>
    ) {
        val templates = db.defaultFieldTemplateDao().getAllList()

        val spec = defaultFieldSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        templates.forEachIndexed { index, template ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(template.key)
            row.createCell(1).setTextSafe(template.name)
            row.createCell(2).setTextSafe(template.type)
            // '필드 정의' 시트와 **같은 규약**이다 — AI추천·필드설명은 전용 열로만 나간다.
            row.createCell(3).setTextSafe(FieldConfigColumns.stripPortableKeys(template.config))
            row.createCell(4).setTextSafe(template.groupName)
            row.createCell(5).setCellValue(template.displayOrder.toDouble())
            row.createCell(6).setTextSafe(if (template.isRequired) "Y" else "N")
            row.createCell(7).setTextSafe(FieldConfigColumns.aiCellOf(template.config))
            row.createCell(8).setTextSafe(
                com.novelcharacter.app.data.model.FieldDescription.fromConfig(template.config)
            )
            row.createCell(9).setTextSafe(FieldValueSheetMapper.entityLabel(template.entityType))
            row.createCell(10).setTextSafe(template.code)
            row.createCell(11).setCellValue(template.createdAt.toDouble())
            finishDataRow(row, spec, banded = templates.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, templates.size)
    }

    // ── 등급 체계 (U-1) ──

    private suspend fun exportGradeSystems(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val systems = db.gradeSystemDao().getAllList()

        val spec = gradeSystemSpec(universes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        // 밴딩은 시트 단위 결정(Q-3 ⓑ — 행 1만 미만 시트만)이라 총 행 수를 먼저 재야 한다.
        val gradeRows = systems.map { system ->
            // 행 순서는 숫자 오름차순 — 앱의 등급 순서 파생 규칙과 같은 모양으로 내보낸다.
            system to com.novelcharacter.app.data.model.GradeSystemRef.gradesFromJson(system.gradesJson)
                .entries.sortedBy { it.value }
        }
        val banded = gradeRows.sumOf { it.second.size } < BANDING_ROW_LIMIT
        var rowIndex = 1
        for ((system, grades) in gradeRows) {
            val universe = universeMap[system.universeId]
            for ((label, value) in grades) {
                val row = sheet.createRow(rowIndex++)
                row.createCell(0).setTextSafe(universe?.name ?: "")
                row.createCell(1).setTextSafe(system.name)
                row.createCell(2).setTextSafe(label)
                row.createCell(3).setCellValue(value)
                row.createCell(4).setTextSafe(universe?.code ?: "")
                row.createCell(5).setTextSafe(system.code)
                finishDataRow(row, spec, banded)
            }
        }

        applySpecFormatting(sheet, spec, rowIndex - 1)
    }

    // ── 필드 데이터 라이브러리 (값 카탈로그 — 별칭·라벨·카테고리 왕복) ──

    private suspend fun exportFieldValueLibrary(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val universes = db.universeDao().getAllUniversesList()
        val universeMap = universes.associateBy { it.id }
        val fieldsById = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
        val entries = db.fieldValueEntryDao().getAllList()

        val spec = fieldValueLibrarySpec(universes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        // 밴딩은 시트 단위 결정(Q-3 ⓑ) — 실제로 실릴 행(정의가 살아 있는 엔트리)만 센다.
        val banded = entries.count { fieldsById.containsKey(it.fieldDefinitionId) } < BANDING_ROW_LIMIT
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
            finishDataRow(row, spec, banded)
        }

        applySpecFormatting(sheet, spec, rowIndex - 1)
    }

    // ── 캐릭터 (세계관별 + 미분류 통합) ──

    private suspend fun exportCharacters(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
        val allSpec = allCharactersSpec(sharedFields.map { it.header }, sharedFields.map { it.type })
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
                    allSheet, allSpec, allRowCount, sharedFields, universe.name,
                    universeChars, fields, novelMap, resolved, tags,
                    banded = allCharacters.size < BANDING_ROW_LIMIT
                )
            }
        }

        // 미분류 캐릭터 — 세계관은 없지만 **전역 구역의 필드는 가진다**(B-119 확장, B-149).
        //
        // 종전에는 여기에 `emptyList()`를 넘겼다("세계관이 없어 필드 열을 만들 수 없다"는 그 시절
        // 전제 그대로). B-119 확장이 무소속에 전역 필드를 준 순간 그 전제가 낡았고, 값은
        // 오버플로 시트로 나가 **유실되지는 않았지만** 엑셀에서 고치려면 캐릭터 시트가 아니라
        // 오버플로 시트를 찾아가야 했다 — '무소속이 일급 개념'이라는 설계 1-9의 취지와 어긋난다.
        //
        // **읽기 전용으로 조회한다** — `DefaultFieldTemplateRepository.globalFields()`는 그림자가
        // 없으면 심는데, 내보내기가 DB를 바꾸는 것은 이 함수가 할 일이 아니다. 심기지 않았다면
        // 그 필드를 가리키는 값도 없으므로 열이 없어도 왕복은 그대로 성립한다.
        val globalFields = db.fieldDefinitionDao().getGlobalFieldsList()
        val unassignedChars = allCharacters.filter { char ->
            val novel = novelMap[char.novelId]
            novel?.universeId == null
        }
        // **0명이어도 시트를 만든다** (B-220) — 종전에는 `isNotEmpty()` 안에서만 만들어
        // 이 시트가 B-88("아직 데이터가 없는 종류도 빈 시트로 나갑니다")의 **미문서화 예외**였다.
        // 그래서 미분류가 0명인 사용자는 *엑셀에서 무소속 캐릭터를 새로 적어 넣을 경로가 없었고*,
        // 안내 시트의 그 문장이 이 시트에 대해서만 거짓이었다. 우회로(세계관 시트에서 작품 칸
        // 비우기)는 문서에 없었을뿐더러 **전역 필드 열이 없어** 값을 함께 적을 수도 없다.
        //
        // 삭제 판정은 그대로다 — `canRestore`는 시트 유무가 아니라 **내용 있는 행**을 보므로
        // (OverwriteGuard) 빈 시트가 '전부 지워라'가 되지 않는다. B-88이 세운 그 짝이다.
        run {
            val tags = unassignedChars.associate { char ->
                char.id to (allTagsMap[char.id] ?: emptyList())
            }
            // 열로 담은 것과 오버플로 판정을 **함께** 옮긴다 — 한쪽만 고치면 같은 값이 두 시트에
            // 겹쳐 나가고(열 + 오버플로), 가져오기에서 어느 쪽이 권위인지가 값마다 갈린다.
            val globalFieldIds = globalFields.mapTo(HashSet()) { it.id }
            unassignedChars.forEach { coveredFieldIds[it.id] = globalFieldIds }
            val unassignedResolved = unassignedChars.associate { char ->
                char.id to resolveFieldDisplayValues(
                    globalFields,
                    (allFieldValuesMap[char.id] ?: emptyList()).associateBy { it.fieldDefinitionId }
                )
            }
            exportCharacterSheet(
                workbook, usedSheetNames, UNCLASSIFIED_SHEET_NAME,
                unassignedChars, globalFields, novelMap, unassignedResolved, tags,
                sheetOwnerOf = UNCLASSIFIED_SHEET_NAME
            )
            // 미분류 캐릭터도 '전체'에 들어간다 — 빠지면 이 시트의 합계가 앱의 인원수와 어긋나고,
            // 그것을 알아채려면 일일이 세어 봐야 한다(원칙 04).
            //
            // **여기에는 전역 필드를 넘기지 않는다 — 의도한 제외다**(B-149, 2026.08.10 사용자 확정).
            // 이 시트는 *두 세계관 이상이 공유하는 필드*를 모으는 집계 시트이고([sharedFields]가
            // `universeIdsWithCharacters`로 거른다), 전역 필드를 넣으면 시트의 정의가 *공유 필드*에서
            // *모든 필드*로 달라진다 — 고치는 것이 아니라 **다른 시트를 만드는 것**이다.
            if (allSheet != null && unassignedChars.isNotEmpty()) {
                allRowCount += appendAllCharacterRows(
                    allSheet, allSpec, allRowCount, sharedFields, "",
                    unassignedChars, emptyList(), novelMap, emptyMap(), tags,
                    banded = allCharacters.size < BANDING_ROW_LIMIT
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
        val calculatedFields = fields.filter { it.fieldType == FieldType.CALCULATED }
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
                //
                // **다만 읽는 쪽은 이 값을 알아본다**([CalculatedCellEcho]). 저장은 안 해도
                // *읽어서 세고 말하기*는 했기 때문에, 종전에는 무편집 왕복이 '건너뜀'과
                // "값을 직접 넣으려면 타입을 바꾸세요" 경고를 냈다.
                f.id to com.novelcharacter.app.util.FormulaDisplay
                    .evaluateForDisplay(formula, evaluator::evaluate)
            }.toMap()
        } else emptyMap()

        return fields.associate { field ->
            field.id to if (field.fieldType == FieldType.CALCULATED) {
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
        sheet: Sheet,
        spec: SheetSpec,
        startRow: Int,
        sharedFields: List<AllCharactersSheet.SharedField>,
        universeName: String,
        characters: List<Character>,
        fields: List<FieldDefinition>,
        novelMap: Map<Long, Novel>,
        resolvedValues: Map<Long, Map<Long, String>>,
        allTags: Map<Long, List<CharacterTag>>,
        /** 시트 단위 밴딩 결정 — 시트 서식은 호출부([exportCharacters])가 걸므로 결정도 그쪽 몫이다. */
        banded: Boolean
    ): Int {
        // (필드키, 타입) → 이 세계관의 필드 id. 같은 조합이 한 세계관에 둘 있을 수 없다
        // (필드키는 세계관·entityType 안에서 유일하다).
        val fieldIdByKeyType = fields.associate { (it.key to it.type) to it.id }
        val fieldsById = fields.associateBy { it.id }
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
                joinCsv(allTags[character.id] ?: emptyList()) { it.tag }
            )
            row.createCell(col++).setTextSafe(if (character.isPinned) "Y" else "N")
            row.createCell(col++).setCellValue(character.displayOrder.toDouble())
            row.createCell(col++).setCellValue(character.createdAt.toDouble())
            row.createCell(col++).setTextSafe(character.code)
            row.createCell(col++).setTextSafe(novel?.code ?: "")
            for (shared in sharedFields) {
                val fieldId = fieldIdByKeyType[shared.key to shared.type]
                val fieldDef = fieldId?.let { fieldsById[it] }
                val cellValue = fieldId?.let { values[it] } ?: ""
                val cell = row.createCell(col++)
                // 숫자 성격 값은 캐릭터 시트와 같은 규칙으로 숫자 셀이다(Q-1) — 이 시트의 존재
                // 이유가 전체 정렬·피벗(U-12a)인데 텍스트 셀은 "10"을 "9" 앞에 세운다(N-1).
                if (fieldDef != null) cell.setFieldValue(fieldDef, cellValue) else cell.setTextSafe(cellValue)
            }
            finishDataRow(row, spec, banded)
        }
        return characters.size
    }

    /**
     * 캐릭터 시트가 열로 담지 못한 필드값 전부 — 미분류 캐릭터 + 타 세계관 잔여값.
     * 이 시트가 없으면 해당 값은 내보내기에서 무음 폐기되고, 덮어쓰기 복원 시 CASCADE로 영구 소멸한다.
     */
    private suspend fun exportCharacterFieldValueOverflow(
        workbook: Workbook,
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
            row.createCell(7).setFieldValue(fd, value)
            finishDataRow(row, spec, banded = rows.size < BANDING_ROW_LIMIT)
        }
        applySpecFormatting(sheet, spec, rows.size)
    }

    private fun exportCharacterSheet(
        workbook: Workbook,
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
            // 숫자 성격 값은 숫자 셀로 나간다(Q-1 ⓐ — setFieldValue가 왕복 멱등 단서를 든다).
            for (field in fields) {
                row.createCell(col++).setFieldValue(field, values[field.id] ?: "")
            }

            // 이미지경로 — 편집이 반영된다(세계관·작품 시트와 같은 규약, B-222 WD-6)
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
            row.createCell(col++).setTextSafe(joinCsv(tags) { it.tag })

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
            finishDataRow(row, spec, banded = characters.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, characters.size)
    }

    // ── 사건 연표 ──

    private suspend fun exportTimeline(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val events = db.timelineDao().getAllEventsList()
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        // 사건 커스텀 필드 (B-10) — 필드명 중복 시 세계관명으로 구분한 헤더 "필드:{이름}"
        val eventFields = db.fieldDefinitionDao().getAllFieldsList(
            com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT
        )
        val universesById = db.universeDao().getAllUniversesList().associateBy { it.id }
        // 헤더 규칙은 EntityFieldHeaders 단일 소스 — 가져오기가 같은 규칙의 역함수로 정확히 되짚는다.
        // 열은 헤더가 유일한 것만 선다 — 나머지는 '사건 필드값' 시트가 담는다(B-65).
        val eventFieldPlan = EntityFieldHeaders.plan(
            eventFields,
            universesById.mapValues { (_, u) -> u.name }
        )
        val eventFieldColumns = eventFieldPlan.columns

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
            row.createCell(6).setTextSafe(joinCsv(novels) { it.title })

            val eventCharIds = eventCharIdMap[event.id] ?: emptyList()
            val characterNames = eventCharIds.mapNotNull { charMap[it]?.name }
            row.createCell(7).setTextSafe(joinCsv(characterNames))

            // 관련작품코드 (readOnly)
            row.createCell(8).setTextSafe(joinCsv(novels.mapNotNull { it.code }))
            // 관련캐릭터코드 (readOnly) — 동명이인 오결합 방지(P1-I). 가져오기 시 코드 우선 매칭.
            row.createCell(9).setTextSafe(joinCsv(eventCharIds.mapNotNull { charMap[it]?.code }))
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
                fieldValues[fieldDef.id]?.let { row.createCell(16 + fi).setFieldValue(fieldDef, it.value) }
            }
            finishDataRow(row, spec, banded = events.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, events.size)

        exportEventFieldValueOverflow(
            workbook, usedSheetNames, events, eventFieldValuesByEvent,
            eventFieldPlan.coveredFieldIds, eventFields, universesById
        )
    }

    /**
     * '사건 필드값' 오버플로 (B-65) — 근거·구조는 [exportNovelFieldValueOverflow]와 같다.
     *
     * **정체는 사건 코드 하나다**(연도·설명으로 되짚으면 남의 사건에 붙는다 — R-1). 코드가 비어
     * 있는 사건(구버전 행)의 값도 **행은 그대로 싣는다** — 빼면 파일에서 그 값이 사라져 사용자가
     * 존재조차 모르게 되고, 실으면 가져오기가 *"코드가 비어 확정할 수 없다"*고 말해 코드 칸을
     * 채워 바로잡을 길이 남는다(검증 → 알림 → 교정 경로. 개발 의도 2번).
     */
    private suspend fun exportEventFieldValueOverflow(
        workbook: Workbook,
        usedSheetNames: MutableSet<String>,
        events: List<TimelineEvent>,
        valuesByEvent: Map<Long, List<com.novelcharacter.app.data.model.EventFieldValue>>,
        coveredFieldIds: Set<Long>,
        eventFields: List<FieldDefinition>,
        universesById: Map<Long, Universe>
    ) {
        val fieldsById = eventFields.associateBy { it.id }
        val rows = events.sortedWith(compareBy({ it.year }, { it.displayOrder }, { it.id }))
            .flatMap { event ->
                EventFieldValueOverflow
                    .select(valuesByEvent[event.id].orEmpty(), coveredFieldIds, fieldsById)
                    .sortedWith(compareBy({ it.second.displayOrder }, { it.second.key }))
                    .map { (value, fd) -> Triple(event, fd, value.value) }
            }
        if (rows.isEmpty()) return  // 다른 시트와 동일 — 빈 시트는 만들지 않는다

        val spec = eventFieldValueSpec(universesById.values.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        rows.forEachIndexed { index, (event, fd, value) ->
            val universe = fd.universeId?.let { universesById[it] }
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(event.code ?: "")
            row.createCell(1).setTextSafe(event.description)
            row.createCell(2).setTextSafe(universe?.name ?: "")
            row.createCell(3).setTextSafe(universe?.code ?: "")
            row.createCell(4).setTextSafe(fd.key)
            row.createCell(5).setTextSafe(fd.name)
            row.createCell(6).setFieldValue(fd, value)
            finishDataRow(row, spec, banded = rows.size < BANDING_ROW_LIMIT)
        }
        applySpecFormatting(sheet, spec, rows.size)
    }

    // ── 캐릭터 상태변화 ──

    private suspend fun exportStateChanges(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = allChanges.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allChanges.size)
    }

    // ── 캐릭터 명대사 (사용자 요청 2026.08.20) ──

    /**
     * 명대사 시트 — 상태 변화와 **같은 모양**이다(캐릭터의 자식 표라 같은 부류다).
     *
     * `상황` 칸은 예약 글자(`__birthday`)를 **그대로** 싣는다. 사람이 읽기 좋게 '생일'로
     * 바꿔 적으면 가져오기가 그 글자를 상황 이름으로 받아, 왕복 한 번에 생일 대사가
     * *생일*이라는 이름의 보통 상황이 되어 축하 창에서 사라진다(개발 의도 4번).
     */
    private suspend fun exportQuotes(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val allQuotesRaw = db.characterQuoteDao().getAllQuotesList()

        val quotesByCharId = allQuotesRaw.groupBy { it.characterId }
        val charIds = quotesByCharId.keys
        val allCharacters = db.characterDao().getAllCharactersList()
        val charMap = allCharacters.filter { it.id in charIds }.associateBy { it.id }
        val novels = db.novelDao().getAllNovelsList()
        val novelMap = novels.associateBy { it.id }

        data class QuoteRow(val character: Character, val novelTitle: String, val quote: CharacterQuote)
        val allRows = mutableListOf<QuoteRow>()
        for ((charId, quotes) in quotesByCharId) {
            val character = charMap[charId] ?: continue
            val novelTitle = character.novelId?.let { novelMap[it]?.title } ?: ""
            quotes.forEach { allRows.add(QuoteRow(character, novelTitle, it)) }
        }

        val spec = quoteSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        allRows.forEachIndexed { index, (character, novelTitle, quote) ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setTextSafe(character.name)
            row.createCell(1).setTextSafe(novelTitle)
            row.createCell(2).setTextSafe(quote.text)
            row.createCell(3).setTextSafe(quote.occasionKey)
            row.createCell(4).setTextSafe(quote.note)
            row.createCell(5).setCellValue(quote.sortOrder.toDouble())
            // 캐릭터코드 (readOnly)
            row.createCell(6).setTextSafe(character.code)
            // 코드 (readOnly) — 왕복 안정 식별자: 대사 글자를 외부에서 고쳐도 같은 행으로 인식
            row.createCell(7).setTextSafe(quote.code ?: "")
            row.createCell(8).setCellValue(quote.createdAt.toDouble())
            finishDataRow(row, spec, banded = allRows.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allRows.size)
    }

    // ── 캐릭터 관계 ──

    private suspend fun exportRelationships(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = allRelationships.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allRelationships.size)
    }

    // ── 관계 변화 ──

    private suspend fun exportRelationshipChanges(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = allChanges.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allChanges.size)
    }

    // ── 이미지 라이브러리 메타 (G3) ──

    /**
     * 라이브러리 관리 이미지(meta 행)의 태그·링크 그룹을 시트로 기록한다.
     * 파일명은 basename만 — 절대경로는 기기 간 이식성이 없다(가져오기에서 zip 리맵/로컬 존재로 해석).
     */
    private suspend fun exportImageMeta(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val metas = db.imageMetaDao().getAllList()
        val tagsByImage = db.imageTagDao().getAllList().groupBy({ it.imageId }, { it.tag })

        val spec = imageMetaSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        metas.forEachIndexed { i, meta ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setTextSafe(java.io.File(meta.path).name)
            row.createCell(1).setTextSafe(tagsByImage[meta.id]?.let { joinCsv(it) } ?: "")
            row.createCell(2).setTextSafe(meta.linkGroupId ?: "")
            // 뗀 적 없으면 **칸을 만들지 않는다** — 빈칸이 곧 "뗀 적 없음"이라(D1) 0이나
            // 빈 문자열을 넣으면 상태가 값과 갈린다. 시각은 다른 시트의 `createdAt`과 같은
            // 규약으로 밀리초 숫자다(사람이 읽을 일이 없고, 지울 때는 칸을 비우면 된다).
            meta.detachedAt?.let { row.createCell(3).setCellValue(it.toDouble()) }
            row.createCell(4).setTextSafe(meta.detachedFromCode ?: "")
            finishDataRow(row, spec, banded = metas.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, metas.size)
    }

    // ── 이름 은행 ──

    private suspend fun exportNameBank(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = allNames.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allNames.size)
    }

    // ── 세력 ──

    private suspend fun exportFactions(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = allFactions.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allFactions.size)
    }

    // ── 세력 소속 ──

    private suspend fun exportFactionMemberships(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = allMemberships.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, allMemberships.size)
    }

    // ── 세력 관계 (B-3) ──

    private suspend fun exportFactionRelationships(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = allRelationships.size < BANDING_ROW_LIMIT)
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
    private suspend fun exportDuelAxes(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            row.createCell(6).setTextSafe(DuelFieldLinks.toText(links.profiles))
            // 후보 필터(B-168) — 필드를 키로 가리키는 JSON이라 그대로 실어도 이식된다.
            // 필터 없음은 빈 칸이다("{}"를 적으면 사람이 그 칸을 지워야 하는지 헷갈린다).
            row.createCell(7).setTextSafe(
                axis.candidateFiltersJson?.takeIf { DuelCandidateFilter.parse(it).isNotEmpty() }.orEmpty()
            )
            // 기준 축(B-104 ⓑ·ⓒ) — 대표 추첨의 가중치와 걸러낼 후보가 이 축을 따른다.
            // 다른 불리언 열과 같은 표기(Y/N)라 사람이 고치는 법이 시트 전체에서 하나다.
            row.createCell(8).setTextSafe(if (axis.isBasisAxis) "Y" else "N")
            row.createCell(9).setCellValue(axis.displayOrder.toDouble())
            row.createCell(10).setTextSafe(axis.code)
            row.createCell(11).setCellValue(axis.createdAt.toDouble())
            finishDataRow(row, spec, banded = axes.size < BANDING_ROW_LIMIT)
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
    private suspend fun exportDuelMatches(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val axes = db.duelAxisDao().getAllList()
        val nameByCode = db.characterDao().getAllCharactersList().associate { it.code to it.displayName }

        val spec = duelMatchSpec(axes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        // 밴딩은 시트 단위 결정(Q-3 ⓑ)이라 총 행 수가 먼저 필요하다 — 축별 목록을 미리 모은다
        // (질의도 축별 그대로라 행 순서 무변경. '대결 기록'은 1만 행을 실제로 넘는 시트다).
        val matchesByAxis = axes.map { it to db.duelMatchDao().getByAxis(it.id) }
        val banded = matchesByAxis.sumOf { it.second.size } < BANDING_ROW_LIMIT
        var rowIndex = 0
        for ((axis, matches) in matchesByAxis) {
            for (match in matches) {
                val row = sheet.createRow(++rowIndex)
                row.createCell(0).setTextSafe(axis.name)
                row.createCell(1).setTextSafe(axis.code)
                row.createCell(2).setTextSafe(nameByCode[match.aCode] ?: "")
                row.createCell(3).setTextSafe(match.aCode)
                row.createCell(4).setTextSafe(nameByCode[match.bCode] ?: "")
                row.createCell(5).setTextSafe(match.bCode)
                // 승자 이름을 못 찾으면 **코드를 그대로 적는다** — 비우면 무승부로 되읽혀
                // 사용자가 고른 승패가 왕복 한 번에 사라진다(개발 의도 4번).
                // 두 참가자의 표시 이름이 같은 판(동명이인 대결)은 승자를 **코드로** 적는다 —
                // 이름을 적으면 가져오기가 어느 쪽인지 정할 수 없고(모호 거부), first-match로
                // 고르면 무편집 왕복만으로 승패가 뒤집힌다(가져오기는 코드를 먼저 받는다).
                val sameName = nameByCode[match.aCode] != null &&
                    nameByCode[match.aCode] == nameByCode[match.bCode]
                row.createCell(6).setTextSafe(
                    match.winnerCode?.let { w -> if (sameName) w else nameByCode[w] ?: w }
                        ?: DuelSheetLabels.WINNER_DRAW
                )
                row.createCell(7).setTextSafe(match.groupId ?: "")
                row.createCell(8).setCellValue(match.decidedAt.toDouble())
                row.createCell(9).setTextSafe(match.code)
                finishDataRow(row, spec, banded)
            }
        }

        applySpecFormatting(sheet, spec, rowIndex)
    }

    /** 대결 **상성** — 층 B의 사용자 판정. 파생이 아니라 판정이라 싣는다. */
    private suspend fun exportDuelVerdicts(workbook: Workbook, usedSheetNames: MutableSet<String>) {
        val axes = db.duelAxisDao().getAllList()
        val nameByCode = db.characterDao().getAllCharactersList().associate { it.code to it.displayName }

        val spec = duelVerdictSpec(axes.map { it.name })
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        // 밴딩은 시트 단위 결정(Q-3 ⓑ) — 축별 목록을 미리 모아 총 행 수를 잰다(행 순서 무변경).
        val verdictsByAxis = axes.map { it to db.duelCounterVerdictDao().getByAxis(it.id) }
        val banded = verdictsByAxis.sumOf { it.second.size } < BANDING_ROW_LIMIT
        var rowIndex = 0
        for ((axis, verdicts) in verdictsByAxis) {
            for (verdict in verdicts) {
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
                row.createCell(4).setTextSafe(joinCsv(members) { nameByCode[it] ?: it })
                row.createCell(5).setTextSafe(joinCsv(members))
                row.createCell(6).setCellValue(verdict.decidedAt.toDouble())
                row.createCell(7).setTextSafe(verdict.code)
                finishDataRow(row, spec, banded)
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
     * 이 내보내기가 만들 파일의 대략적 크기(바이트) — **공간 부족 안내(D7) 전용이다.**
     *
     * (종전 이 자리는 *"사전 견적(D6)이 같은 식을 쓴다"*고 적었는데 **사실이 아니다** —
     * D6은 `ExcelTransferController`가 `ImageZipHelper.estimateImageBytes`를 **직접** 부른다.
     * 부르는 자리가 하나뿐인 것을 실측하고 고쳤다.)
     *
     * 이미지는 실측 합산(무압축으로 담으므로 실제 zip 크기와 거의 같다 — 설계 D8의 부수 이득),
     * 워크북 몫은 이미 만들어 둔 임시 파일에서 재지 않고 생략한다 — 실패 시점에 그 파일이
     * 남아 있다는 보장이 없고, 이미지가 압도적이라(실측 744MB 대 수 MB) 안내의 자릿수가
     * 바뀌지 않는다. **모자라게 말하지 않는 것이 중요하므로** 이미지 몫만으로도 안내는 성립한다.
     *
     * **다만 그 논거는 이미지를 담을 때만 선다** — 그래서 담지 않으면 `null`이다(B-189).
     * 종전에는 `0L`을 돌려줬고 [ExportSpace.requiredMegabytes]가 그 0을 1로 올려
     * *"약 1MB가 필요합니다"*가 나갔다. **잰 것이 아무것도 없는데 숫자를 말한 것**이고,
     * B-72가 시트를 임시 파일로 흘려보내는 디스크 축을 새로 붙인 뒤로 그 거짓은 더 커졌다.
     * `null`은 *"모른다"*이고, 호출부가 숫자 없는 문구로 갈라 말한다.
     */
    private suspend fun estimateExportBytes(options: ExportOptions): Long? =
        if (options.images) ImageZipHelper.estimateImageBytes(db, appContext) else null

    /**
     * 이미지 포함 결과 고지. 사실만 말한다 — 제외가 0건이면 손실 문구를 쓰지 않는다.
     * (사실과 다른 경고는 무음보다 나쁘다)
     *
     * **갈래 판정은 [ImageZipReport.noticeKinds]가 한다(B-225).** 여기 남은 일은 자원 매핑뿐이다 —
     * 우선순위가 이 사적 함수 안에 있던 동안 *참조를 못 읽은 상태에서도 '완전한 백업입니다'*가
     * 나갔고, 어느 시험도 그 자리에 닿지 못했다.
     */
    private fun buildImageNotice(r: ImageZipReport, isCompleteBackup: Boolean): String? =
        r.noticeKinds(isCompleteBackup)
            .map { imageNoticeText(r, it) }
            .joinToString("\n")
            .ifBlank { null }

    private fun imageNoticeText(r: ImageZipReport, kind: ImageNoticeKind): String = when (kind) {
        ImageNoticeKind.NONE_INCLUDED ->
            appContext.getString(R.string.export_images_none_included, r.referencedCount)
        ImageNoticeKind.INCOMPLETE ->
            appContext.getString(R.string.export_images_incomplete, r.referencedCount, r.includedCount, r.excludedCount)
        ImageNoticeKind.REFS_UNREADABLE ->
            appContext.getString(R.string.export_images_refs_unreadable, r.unreadableRefCount)
        // 요청했으나 앱에 이미지 자체가 없는 경우 — 손실이 아니라 확장자(.xlsx)에 대한 설명
        ImageNoticeKind.NO_IMAGES -> appContext.getString(R.string.export_images_none)
        // 전부 담겼다. 종전에는 이 갈래가 무고지였다(설계 1장) — 손실은 알려 주면서 완전함은
        // 말하지 않으면, 백업의 생명인 완전성을 사용자가 매번 열어서 확인해야 한다(원칙 04).
        ImageNoticeKind.COMPLETE_BACKUP -> appContext.getString(R.string.export_backup_complete, r.includedCount)
    }

    /**
     * 작업 이력 '상세'에 실을 제외 내역 + 교정 경로 안내. 알릴 것이 없으면 null.
     *
     * **참조를 못 읽은 항목도 여기 싣는다(B-225)** — 제외 건수가 0이어도 그 항목의 이미지는
     * 담기지 않았고, 교정 경로 안내('이미지 경로 점검')가 필요한 것도 같다.
     */
    private fun buildImageDetail(r: ImageZipReport): String? {
        if (!r.hasLoss && !r.referencesIncomplete) return null
        val lines = mutableListOf<String>()
        for ((reason, count) in r.lossReasons()) {
            lines.add(appContext.getString(ImageNoticeRes.lossReason(reason), count))
        }
        if (r.unreadableRefCount > 0) lines.add(appContext.getString(R.string.export_images_detail_unreadable, r.unreadableRefCount))
        if (r.sampleNames.isNotEmpty()) lines.add(appContext.getString(R.string.export_images_detail_samples, r.sampleNames.joinToString(", ")))
        lines.add(appContext.getString(R.string.export_images_detail_guide))
        return lines.joinToString("\n")
    }

    // ── 필드 템플릿 ──

    private suspend fun exportUserPresetTemplates(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = templates.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, templates.size)
    }

    // ── 검색 프리셋 ──

    private suspend fun exportSearchPresets(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            finishDataRow(row, spec, banded = presets.size < BANDING_ROW_LIMIT)
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
    private suspend fun exportCharacterListPresets(workbook: Workbook, usedSheetNames: MutableSet<String>) {
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
            row.createCell(8).setTextSafe(joinCsv(novelCodes))
            row.createCell(9).setTextSafe(if (preset.isDefault) "Y" else "N")
            row.createCell(10).setCellValue(preset.createdAt.toDouble())
            row.createCell(11).setCellValue(preset.updatedAt.toDouble())
            finishDataRow(row, spec, banded = presets.size < BANDING_ROW_LIMIT)
        }

        applySpecFormatting(sheet, spec, presets.size)
    }

    private suspend fun exportAppSettings(
        workbook: Workbook,
        usedSheetNames: MutableSet<String>,
        options: ExportOptions
    ) {
        val spec = appSettingsSpec()
        val sheetName = assignSheetName(spec.sheetName, usedSheetNames, ownerOf = spec.sheetName)
        val sheet = workbook.createSheet(sheetName)
        writeHeaderRow(sheet, spec)

        // 사용자 설정 왕복 — 새 기기 복원 시 설정을 다시 맞추지 않아도 되게 한다.
        //
        // **무엇을 싣는가는 여기서 정하지 않는다**(B-105). 종전에는 이 자리가 손으로 적은
        // 나열이고 가져오기가 손수 짠 `when`이라 **설정 하나를 늘리려면 두 곳을 고쳐야 했고,
        // 그래서 늘지 않았다.** 이제 목록도 읽기도 [AppSettingsBindings] 하나가 든다.
        //
        // 비밀(API 키)은 **별도 동의가 있을 때만** 나간다 — 사용자 확정 3번 ㄴ1.
        // 밴딩은 시트 단위 결정(Q-3 ⓑ) — 등재 수가 상한이다(읽기 실패로 빠지는 행은 더 줄일 뿐이다).
        val bindings = AppSettingsBindings.exported(options.aiKeys)
        val banded = bindings.size < BANDING_ROW_LIMIT
        var rowIndex = 1
        for (binding in bindings) {
            // **한 설정이 실패해도 백업 전체를 잃지 않는다.** 종전에는 저장소 셋에서 열한 번
            // 읽었고 지금은 열 곳에서 서른일곱 번 읽는다 — 그중 하나가 던지면(손상된 prefs,
            // 복호화 실패한 키) 잡지 않는 한 **내보내기가 통째로 죽는다.** 늘어난 것이
            // 편의인데 그 대가가 백업 유실이어서는 안 된다.
            //
            // 값이 없으면 행 자체를 만들지 않는다(빈 칸과 '값 없음'은 다른 사실이다).
            val value = try {
                binding.read(appContext)
            } catch (e: Exception) {
                Log.w("ExcelExporter", "앱 설정 '${binding.spec.key}'을(를) 읽지 못해 건너뜁니다", e)
                null
            } ?: continue
            val row = sheet.createRow(rowIndex++)
            row.createCell(0).setTextSafe(binding.spec.key)
            if (binding.spec.kind == AppSettingsKeys.Kind.NUMBER) {
                val number = value.toDoubleOrNull()
                if (number != null) row.createCell(1).setCellValue(number)
                else row.createCell(1).setTextSafe(value)
            } else {
                row.createCell(1).setTextSafe(value)
            }
            // 안내 두 칸 (사용자 요청 2026.08.20) — **문구는 선언에서 나온다.**
            // 여기서 손으로 적으면 상한을 옮기는 날 시트만 낡는다(R-14).
            row.createCell(2).setTextSafe(binding.spec.note)
            row.createCell(3).setTextSafe(binding.spec.accepts())
            finishDataRow(row, spec, banded)
        }

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

        /**
         * 짝수 행 밴딩(Q-3 ⓑ — 사용자 확정 *"행 1만 미만 시트만"*)을 켜는 시트 크기 상한.
         * **시트 단위 결정이다** — 이 수 이상이면 그 시트는 무늬 없이 나간다(행 단위로 자르면
         * 1만 행에서 무늬가 끊겨 "여기부터 뭔가 잘못됐다"로 읽힌다). 파일 크기는 이 상한의 몫이
         * 아니다: 세로 상단 정렬(P-3)로 모든 데이터 셀이 이미 스타일 참조를 가지므로 밴딩은
         * 어느 스타일을 가리키는가만 바꾼다 — 리뷰 문서 5-2의 비용 산정은 무스타일 셀 전제였고,
         * 그 전제가 P-3 시행으로 낡았다(같은 문서 8-1 ③).
         */
        private const val BANDING_ROW_LIMIT = 10_000

        /** 사용 안내 시트 본문(B열) 너비 — 행 높이 추정([estimateWrapLines])과 같은 값을 봐야 한다. */
        private const val GUIDE_BODY_WIDTH = 26000
        private const val XLSX_CELL_LIMIT = EXCEL_CELL_TEXT_LIMIT // 단일 소스: SheetSpec.EXCEL_CELL_TEXT_LIMIT
    }
}
