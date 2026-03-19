package com.novelcharacter.app.excel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.novelcharacter.app.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory

class ExcelImporter(context: Context) {

    private val appContext: Context = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    @Volatile private var supervisorJob = kotlinx.coroutines.SupervisorJob()
    @Volatile private var importScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private val importService = ExcelImportService(db)

    private var importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var currentActivityRef: java.lang.ref.WeakReference<Activity>? = null

    fun registerLauncher(fragment: Fragment) {
        currentActivityRef = java.lang.ref.WeakReference(fragment.activity)
        try {
            importLauncher = fragment.registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { importFromExcel(it) }
            }
        } catch (e: IllegalStateException) {
            android.util.Log.w("ExcelImporter", "registerForActivityResult called too late in lifecycle", e)
        }
    }

    @Synchronized
    private fun ensureActiveScope(): CoroutineScope {
        if (supervisorJob.isCompleted || supervisorJob.isCancelled) {
            supervisorJob = kotlinx.coroutines.SupervisorJob()
            importScope = CoroutineScope(Dispatchers.IO + supervisorJob)
        }
        return importScope
    }

    @Synchronized
    fun cleanup() {
        supervisorJob.cancel()
        importLauncher = null
        currentActivityRef = null
    }

    fun showImportDialog(fragment: Fragment) {
        val launcher = importLauncher
        if (launcher != null) {
            launcher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream"
            ))
        } else {
            Toast.makeText(appContext, com.novelcharacter.app.R.string.importer_restart, Toast.LENGTH_SHORT).show()
        }
    }

    fun importFromExcel(uri: Uri) {
        ensureActiveScope().launch {
            var workbook: org.apache.poi.ss.usermodel.Workbook? = null
            var progressDialog: AlertDialog? = null
            var progressText: TextView? = null

            try {
                // ZIP bomb / oversized file protection
                val fileSize = appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                if (fileSize > MAX_IMPORT_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, com.novelcharacter.app.R.string.import_file_too_large, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Limit POI ZIP expansion ratio to prevent memory DoS
                org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.01)
                org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024) // 50MB max per entry

                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw Exception("파일을 열 수 없습니다")

                workbook = inputStream.use { WorkbookFactory.create(it) }

                // Show progress dialog (requires an Activity reference)
                val activityRef = currentActivityRef
                val activity = activityRef?.get()
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    withContext(Dispatchers.Main) {
                        val act = activityRef?.get()
                        if (act != null && !act.isFinishing && !act.isDestroyed) {
                            val layout = LinearLayout(act).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                val dp16 = (16 * act.resources.displayMetrics.density).toInt()
                                setPadding(dp16 * 2, dp16, dp16 * 2, dp16)
                            }
                            val progressBar = ProgressBar(act).apply {
                                isIndeterminate = true
                            }
                            layout.addView(progressBar)
                            val textView = TextView(act).apply {
                                text = appContext.getString(com.novelcharacter.app.R.string.import_preparing)
                                gravity = Gravity.CENTER
                                val dp8 = (8 * act.resources.displayMetrics.density).toInt()
                                setPadding(0, dp8, 0, 0)
                            }
                            layout.addView(textView)
                            progressText = textView

                            progressDialog = AlertDialog.Builder(act)
                                .setTitle(appContext.getString(com.novelcharacter.app.R.string.import_progress_title))
                                .setView(layout)
                                .setCancelable(false)
                                .create()
                            progressDialog?.show()
                        }
                    }
                }

                val result = importService.importAll(workbook) { progress ->
                    val pct = if (progress.totalRows > 0) {
                        (progress.processedRows * 100 / progress.totalRows).coerceAtMost(100)
                    } else 0
                    val text = "${progress.currentPhase} 처리 중... ($pct%)"
                    importScope.launch(Dispatchers.Main) {
                        progressText?.text = text
                    }
                }

                val message = buildResultMessage(result)
                withContext(Dispatchers.Main) {
                    dismissDialogSafely(progressDialog)
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelImporter", "Import failed", e)
                withContext(Dispatchers.Main) {
                    dismissDialogSafely(progressDialog)
                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_failed_retry, Toast.LENGTH_LONG).show()
                }
            } finally {
                try { workbook?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun buildResultMessage(result: ImportResult): String {
        val parts = mutableListOf<String>()
        val charTotal = result.newCharacters + result.updatedCharacters
        val eventTotal = result.newEvents + result.updatedEvents

        if (result.newUniverses > 0 || result.updatedUniverses > 0)
            parts.add("세계관 ${result.newUniverses + result.updatedUniverses}개")
        if (result.newNovels > 0 || result.updatedNovels > 0)
            parts.add("작품 ${result.newNovels + result.updatedNovels}개")
        if (result.newFields > 0 || result.updatedFields > 0)
            parts.add("필드 ${result.newFields + result.updatedFields}개")
        if (charTotal > 0) {
            val detail = if (result.updatedCharacters > 0) " (업데이트 ${result.updatedCharacters}명)" else ""
            parts.add("캐릭터 ${charTotal}명$detail")
        }
        if (eventTotal > 0) {
            val detail = if (result.updatedEvents > 0) " (업데이트 ${result.updatedEvents}개)" else ""
            parts.add("사건 ${eventTotal}개$detail")
        }
        val scTotal = result.newStateChanges + result.updatedStateChanges
        if (scTotal > 0) {
            val detail = if (result.updatedStateChanges > 0) " (업데이트 ${result.updatedStateChanges}개)" else ""
            parts.add("상태변화 ${scTotal}개$detail")
        }
        val relTotal = result.newRelationships + result.updatedRelationships
        if (relTotal > 0) parts.add("관계 ${relTotal}개")
        if (result.newRelationshipChanges > 0) parts.add("관계 변화 ${result.newRelationshipChanges}개")
        val nbTotal = result.newNameBank + result.updatedNameBank
        if (nbTotal > 0) parts.add("이름 ${nbTotal}개")
        if (result.skippedRows > 0)
            parts.add("오류 ${result.skippedRows}건 건너뜀")
        if (result.nameBasedMappings > 0)
            parts.add("이름 매칭 ${result.nameBasedMappings}건")
        if (result.newCodesGenerated > 0)
            parts.add("새 코드 ${result.newCodesGenerated}건 발급")

        return if (parts.isEmpty()) "가져오기 완료: 데이터 없음"
        else "가져오기 완료: ${parts.joinToString(", ")}"
    }

    private fun dismissDialogSafely(dialog: AlertDialog?) {
        try {
            val act = currentActivityRef?.get()
            if (dialog != null && dialog.isShowing && act != null && !act.isFinishing && !act.isDestroyed) {
                dialog.dismiss()
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val MAX_IMPORT_FILE_SIZE = 10L * 1024 * 1024 // 10MB (Apache POI expands ~5-10x in memory)
    }
}
