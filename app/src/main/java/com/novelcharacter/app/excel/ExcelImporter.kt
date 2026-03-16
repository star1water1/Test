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

class ExcelImporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private var supervisorJob = kotlinx.coroutines.SupervisorJob()
    private var importScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private val importService = ExcelImportService(db)

    private var importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null

    fun registerLauncher(fragment: Fragment) {
        importLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let { importFromExcel(it) }
        }
    }

    fun cleanup() {
        supervisorJob.cancel()
        importLauncher = null
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
            Toast.makeText(context, com.novelcharacter.app.R.string.importer_restart, Toast.LENGTH_SHORT).show()
        }
    }

    fun importFromExcel(uri: Uri) {
        // Recreate scope if previous one was cancelled
        if (supervisorJob.isCompleted || supervisorJob.isCancelled) {
            supervisorJob = kotlinx.coroutines.SupervisorJob()
            importScope = CoroutineScope(Dispatchers.IO + supervisorJob)
        }
        importScope.launch {
            var workbook: org.apache.poi.ss.usermodel.Workbook? = null
            var progressDialog: AlertDialog? = null
            var progressText: TextView? = null

            try {
                // ZIP bomb / oversized file protection
                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                if (fileSize > MAX_IMPORT_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, com.novelcharacter.app.R.string.import_file_too_large, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("파일을 열 수 없습니다")

                workbook = inputStream.use { WorkbookFactory.create(it) }

                // Show progress dialog (only if context is an Activity)
                val activity = context as? Activity
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    withContext(Dispatchers.Main) {
                        val layout = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            val dp16 = (16 * activity.resources.displayMetrics.density).toInt()
                            setPadding(dp16 * 2, dp16, dp16 * 2, dp16)
                        }
                        val progressBar = ProgressBar(activity).apply {
                            isIndeterminate = true
                        }
                        layout.addView(progressBar)
                        val textView = TextView(activity).apply {
                            text = "가져오기 준비 중..."
                            gravity = Gravity.CENTER
                            val dp8 = (8 * activity.resources.displayMetrics.density).toInt()
                            setPadding(0, dp8, 0, 0)
                        }
                        layout.addView(textView)
                        progressText = textView

                        progressDialog = AlertDialog.Builder(activity)
                            .setTitle("가져오기 진행 중")
                            .setView(layout)
                            .setCancelable(false)
                            .create()
                        progressDialog?.show()
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
                    progressDialog?.dismiss()
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelImporter", "Import failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    Toast.makeText(context, com.novelcharacter.app.R.string.import_failed_retry, Toast.LENGTH_LONG).show()
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
        val nbTotal = result.newNameBank + result.updatedNameBank
        if (nbTotal > 0) parts.add("이름 ${nbTotal}개")
        if (result.skippedRows > 0)
            parts.add("오류 ${result.skippedRows}건 건너뜀")

        return if (parts.isEmpty()) "가져오기 완료: 데이터 없음"
        else "가져오기 완료: ${parts.joinToString(", ")}"
    }

    companion object {
        private const val MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024 // 50MB
    }
}
