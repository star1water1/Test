package com.novelcharacter.app.excel

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import java.io.File

/**
 * 엑셀 내보내기/가져오기 진입 흐름의 공용 컨트롤러.
 *
 * SettingsFragment에 묶여 있던 옵션 다이얼로그·SAF 런처·pendingExportFile 복원을 추출해
 * 홈 대시보드 등 어느 프래그먼트에서든 같은 흐름을 중복 없이 쓸 수 있게 한다.
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
            override fun onDestroy(owner: LifecycleOwner) {
                exporter?.cancel()
                exporter = null
                importer.cleanup()
            }
        })
    }

    /** 내보내기: 항목 선택 → 공유/파일 저장 선택 → 실행 */
    fun showExportDialog() {
        if (!fragment.isAdded) return
        val labels = ExportOptions.LABELS
        val checked = ExportOptions.ALL.toBooleanArray()

        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.export_options_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.confirm) { _, _ ->
                showExportModeDialog(ExportOptions.fromBooleanArray(checked))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                exporter?.cancel()
                exporter = ExcelExporter(fragment.requireContext().applicationContext)
                when (which) {
                    0 -> exporter?.exportAll(options)
                    1 -> exporter?.exportAll(options) { file, fileName ->
                        if (fragment.isAdded) {
                            pendingExportFile = file
                            saveFileLauncher.launch(fileName)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
