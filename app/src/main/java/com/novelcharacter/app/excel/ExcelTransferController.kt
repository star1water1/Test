package com.novelcharacter.app.excel

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.databinding.DialogExportFullBackupBinding
import com.novelcharacter.app.ui.common.TaskProgressDialog
import com.novelcharacter.app.util.ImportNoticeRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 엑셀 내보내기/가져오기 진입 흐름의 공용 컨트롤러.
 *
 * SettingsFragment에 묶여 있던 옵션 다이얼로그·SAF 런처·pendingExportFile 복원을 추출해
 * 홈 대시보드 등 어느 프래그먼트에서든 같은 흐름을 중복 없이 쓸 수 있게 한다.
 * **세계관·작품 목록의 복제 흐름도 2026.08.02에 여기로 흡수했다**(설계 D3 후단) — 그 전에는
 * 두 진입이 자기 exporter·런처·모드 창을 따로 들고 있어, 이 컨트롤러만 고치면 그쪽은
 * 옛 흐름에 남았다.
 *
 * **내보내기는 두 직업으로 갈라져 있다**(설계 D1·D3):
 * - [startFullBackup] — "전부 안전하게 보관". 옵션은 [ExportOptions.ALL_WITH_IMAGES] 고정이고
 *   체크박스가 없다. 백업의 생명은 완전성인데, 종전에는 그것이 18항목 중 **마지막 체크박스**·
 *   기본 꺼짐으로 앉아 있어 모르면 반쪽 백업이 됐다.
 * - [showExportDialog] — "표로 뽑아 작업". 현행 18항목 그대로이고 이미지는 기본 꺼짐이다 —
 *   그 직업에는 그 기본값이 옳다.
 *
 * 반드시 프래그먼트의 onCreate에서 생성해야 한다 — registerForActivityResult는
 * STARTED 이전에 등록되어야 하고, 등록 순서가 재생성 시에도 동일해야 결과가 올바로 배달된다.
 */
class ExcelTransferController(private val fragment: Fragment) {

    private val importer = ExcelImporter(fragment.requireContext().applicationContext)
    private var exporter: ExcelExporter? = null
    private var pendingExportFile: File? = null

    private val saveFileLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (!fragment.isAdded) return@registerForActivityResult
        val file = pendingExportFile
        if (uri != null && file != null) {
            if (exporter == null) {
                exporter = ExcelExporter(fragment.requireContext().applicationContext)
            }
            exporter?.writeToUri(uri, file)
        }
        pendingExportFile = null
    }

    init {
        importer.registerLauncher(fragment)
        fragment.lifecycle.addObserver(object : DefaultLifecycleObserver {
            /**
             * **화면 없이 끝난 가져오기의 결과를 여기서 갚는다** (B-56).
             *
             * 가져오기는 화면이 사라져도 계속 도는데, 끝났을 때 결과 창을 띄울 자리가 없으면
             * 종전에는 토스트로 물러섰다 — 앱이 앞에 없으면 안드로이드가 그 토스트를 막으므로
             * (API 30+) **정작 필요한 순간에 아무 일도 안 하는 고지**였다.
             *
             * 알림과 짝이다: 알림은 앱 밖에서 지금 닿고 이쪽은 **알림을 못 봤거나 권한을
             * 거절한 사용자**에게 닿는다. 알림 권한 거절은 정당한 선택이라 그것 하나에 걸면
             * 거절한 사용자에게는 종전과 똑같이 침묵이다.
             *
             * **가져오기 진입이 있는 화면에 붙이는 것**이 자리 판정이다 — 파일을 들인 사람이
             * 다시 오는 자리이고, 이 컨트롤러가 곧 그 진입이다.
             */
            override fun onStart(owner: LifecycleOwner) {
                showPendingImportNotice()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                exporter?.cancel()
                exporter = null
                importer.cleanup()
            }
        })
    }

    /**
     * 보관된 고지를 한 번만 낸다 — 읽으면서 지운다([ImportNoticeRelay.consume]).
     * 남겨 두면 앱을 열 때마다 지난 결과가 다시 떠 **새 결과로 오인**된다.
     *
     * `isAdded`를 먼저 보는 것은 지우기만 하고 못 보여 주는 경우를 막기 위해서다.
     */
    private fun showPendingImportNotice() {
        if (!fragment.isAdded) return
        val notice = ImportNoticeRelay.consume(fragment.requireContext().applicationContext) ?: return
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.import_notice_pending_title))
            .setMessage("${notice.title}\n\n${notice.body}")
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    /**
     * 내보내기 진입 2단(설계 D3) — 목록 화면의 메뉴처럼 **진입이 하나뿐인 자리**에서 쓴다.
     * 설정·홈처럼 두 항목을 각각 놓을 수 있는 화면은 [startFullBackup]·[showExportDialog]를
     * 직접 부른다.
     */
    fun showExportEntry() {
        if (!fragment.isAdded) return
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.export_pick_title)
            .setItems(
                arrayOf(
                    fragment.getString(R.string.export_full_backup),
                    fragment.getString(R.string.export_choose_items)
                )
            ) { _, which ->
                when (which) {
                    0 -> startFullBackup()
                    1 -> showExportDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 전체 백업(설계 D1) — 항목 선택 없이 **모드 선택 창이 곧 확인 단계**다.
     * 그 창이 목적문과 크기 견적(D6)을 함께 보인다.
     */
    fun startFullBackup() {
        if (!fragment.isAdded) return
        val binding = DialogExportFullBackupBinding.inflate(fragment.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.export_full_backup)
            .setView(binding.root)
            // 버튼 순서(취소 | 공유하기 | 파일로 저장)는 머티리얼 규격이 정한다
            .setNeutralButton(R.string.export_mode_share) { _, _ ->
                runExport(ExportOptions.ALL_WITH_IMAGES, saveToFile = false)
            }
            .setPositiveButton(R.string.export_mode_save) { _, _ ->
                runExport(ExportOptions.ALL_WITH_IMAGES, saveToFile = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        // 견적은 편의이지 게이트가 아니다 — 늦거나 실패하면 그 줄만 뜨지 않고 흐름은 그대로다.
        val appContext = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ImageZipHelper.estimateImageBytes(AppDatabase.getDatabase(appContext), appContext)
            }
            if (bytes <= 0L || !dialog.isShowing || !fragment.isAdded) return@launch
            binding.estimateText.text =
                fragment.getString(R.string.export_size_estimate, formatSize(bytes))
            binding.estimateText.visibility = android.view.View.VISIBLE
        }
    }

    /** 골라서 내보내기(고급): 항목 선택 → 공유/파일 저장 선택 → 실행 */
    fun showExportDialog() {
        // 항목 선택 창은 [ExportOptionsDialog]가 단일 소스다(종전에는 이 코드가 3곳에 복제돼 있었다).
        ExportOptionsDialog.show(fragment) { showExportModeDialog(it) }
    }

    private fun showExportModeDialog(options: ExportOptions) {
        if (!fragment.isAdded) return
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.export_mode_title)
            .setItems(
                arrayOf(
                    fragment.getString(R.string.export_mode_share),
                    fragment.getString(R.string.export_mode_save)
                )
            ) { _, which ->
                runExport(options, saveToFile = which == 1)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 두 진입이 공유하는 실행부 — **진행도는 작업형 규격**(R-26 · 설계 D5)이다.
     *
     * 종전에는 조회형 스피너였다. 이미지 포함 백업은 수백 MB·수 분이 걸리는 순회 작업이라
     * "끝을 모르는 표시"로는 사용자가 멈춘 것과 도는 것을 구분할 수 없었다(B-51의 엑셀 몫).
     *
     * 취소는 **산출물을 만들지 않고 멈추는 것**이다 — 반쯤 쓴 파일을 건네면 그것으로 복원할 때
     * 조용히 유실된다. 취소 뒤 임시 파일 삭제·고지는 [ExcelExporter]가 맡는다.
     */
    private fun runExport(options: ExportOptions, saveToFile: Boolean) {
        if (!fragment.isAdded) return
        exporter?.cancel()
        exporter = ExcelExporter(fragment.requireContext().applicationContext)

        // **플래그는 스레드를 건넌다** (B-219 ③): 취소 버튼은 메인에서 쓰고 내보내기 루프는
        // IO에서 읽는다. 캡처된 지역 `var`에는 가시성 보장이 없어 눌린 취소가 안 보인 채
        // 대형 백업이 끝까지 만들어질 수 있다.
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        // 총량은 띄우기 전에 안다(R-26: 총량 확정 후에 show한다) — 실행부와 같은 목록을 쓴다.
        val progressDialog = TaskProgressDialog.show(
            fragment.requireContext(),
            titleRes = R.string.export_progress_title,
            total = ExportSheetStep.of(options).size,
            stageRes = R.string.export_progress_stage_sheets,
            onCancel = { cancelled.set(true) }
        )
        val sheetsStage = fragment.getString(R.string.export_progress_stage_sheets)
        val imagesStage = fragment.getString(R.string.export_progress_stage_images)
        val writingStage = fragment.getString(R.string.export_progress_stage_writing)

        // 작업은 IO, 갱신은 메인(R-26). 화면이 사라진 뒤의 갱신은 조용히 버린다 —
        // 그래도 작업 자체는 계속 돈다(취소가 아니다).
        fun post(block: () -> Unit) {
            fragment.activity?.runOnUiThread {
                if (fragment.isAdded) block()
            }
        }

        val sink = ExportProgressSink(
            onSheets = { done, total ->
                post {
                    // 시트를 다 만들면 다음은 쓰기다 — 마지막 시트에서 막대가 100%로 멈춘 채
                    // 오래 있는 것처럼 보이지 않도록 단계 문구를 바꿔 준다
                    progressDialog.update(done, total, if (done == total && total > 0) writingStage else sheetsStage)
                }
            },
            onImages = { done, total -> post { progressDialog.update(done, total, imagesStage) } },
            isCancelled = { cancelled.get() }
        )

        val onFinished: () -> Unit = { progressDialog.dismiss() }
        if (saveToFile) {
            exporter?.exportAll(options, onFinished = onFinished, progress = sink) { file, fileName ->
                if (fragment.isAdded) {
                    pendingExportFile = file
                    saveFileLauncher.launch(fileName)
                }
            }
        } else {
            exporter?.exportAll(options, onFinished = onFinished, progress = sink)
        }
    }

    /** 견적 표기 — GB/MB 두 자리까지. 자릿수만 맞으면 되는 안내라 소수 1자리로 끊는다. */
    private fun formatSize(bytes: Long): String {
        val mb = bytes.toDouble() / 1_048_576.0
        return if (mb >= 1024.0) {
            String.format(java.util.Locale.US, "%.1fGB", mb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.0fMB", mb)
        }
    }

    /** 가져오기: 파일 선택(SAF) → 분석/미리보기 → 임포트 (ExcelImporter가 전체 흐름 처리) */
    fun showImportDialog() {
        if (!fragment.isAdded) return
        importer.showImportDialog(fragment)
    }

    /** 복호화된 백업 등 이미 로컬에 있는 파일을 직접 가져오기 (백업 복원 경로용 패스스루) */
    fun importFromLocalFile(file: File) {
        importer.importFromLocalFile(file)
    }

    /** SAF 저장 위치 선택 중 프로세스 재생성에 대비해 내보내기 산출물 경로를 보존한다. */
    fun saveState(outState: Bundle) {
        pendingExportFile?.absolutePath?.let {
            outState.putString(KEY_PENDING_EXPORT_FILE, it)
        }
    }

    fun restoreState(savedInstanceState: Bundle?) {
        savedInstanceState?.getString(KEY_PENDING_EXPORT_FILE)?.let {
            pendingExportFile = File(it)
        }
    }

    companion object {
        // SettingsFragment 시절의 키를 그대로 유지 — 업데이트 직전 저장된 상태와도 호환
        private const val KEY_PENDING_EXPORT_FILE = "pendingExportFilePath"
    }
}
