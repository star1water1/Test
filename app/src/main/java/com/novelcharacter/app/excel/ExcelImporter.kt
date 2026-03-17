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
        // Cancel old scope and recreate if previous one completed or was cancelled
        if (supervisorJob.isCompleted || supervisorJob.isCancelled) {
            supervisorJob.cancel()
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
                if (fileSize < 0 || fileSize > MAX_IMPORT_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        val msgRes = if (fileSize < 0) com.novelcharacter.app.R.string.import_file_unreadable
                            else com.novelcharacter.app.R.string.import_file_too_large
                        Toast.makeText(context, msgRes, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception(context.getString(com.novelcharacter.app.R.string.import_file_unreadable))

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
                            text = activity.getString(com.novelcharacter.app.R.string.import_preparing)
                            gravity = Gravity.CENTER
                            val dp8 = (8 * activity.resources.displayMetrics.density).toInt()
                            setPadding(0, dp8, 0, 0)
                        }
                        layout.addView(textView)
                        progressText = textView

                        progressDialog = AlertDialog.Builder(activity)
                            .setTitle(activity.getString(com.novelcharacter.app.R.string.import_in_progress))
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
                    val text = context.getString(com.novelcharacter.app.R.string.import_progress_format, progress.currentPhase, pct)
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
            parts.add(context.getString(com.novelcharacter.app.R.string.import_result_universes, result.newUniverses + result.updatedUniverses))
        if (result.newNovels > 0 || result.updatedNovels > 0)
            parts.add(context.getString(com.novelcharacter.app.R.string.import_result_novels, result.newNovels + result.updatedNovels))
        if (result.newFields > 0 || result.updatedFields > 0)
            parts.add(context.getString(com.novelcharacter.app.R.string.import_result_fields, result.newFields + result.updatedFields))
        if (charTotal > 0) {
            val detail = if (result.updatedCharacters > 0) context.getString(com.novelcharacter.app.R.string.import_result_updated_count, result.updatedCharacters) else ""
            parts.add(context.getString(com.novelcharacter.app.R.string.import_result_characters, charTotal) + detail)
        }
        if (eventTotal > 0) {
            val detail = if (result.updatedEvents > 0) context.getString(com.novelcharacter.app.R.string.import_result_updated_items, result.updatedEvents) else ""
            parts.add(context.getString(com.novelcharacter.app.R.string.import_result_events, eventTotal) + detail)
        }
        val scTotal = result.newStateChanges + result.updatedStateChanges
        if (scTotal > 0) {
            val detail = if (result.updatedStateChanges > 0) context.getString(com.novelcharacter.app.R.string.import_result_updated_items, result.updatedStateChanges) else ""
            parts.add(context.getString(com.novelcharacter.app.R.string.import_result_state_changes, scTotal) + detail)
        }
        val relTotal = result.newRelationships + result.updatedRelationships
        if (relTotal > 0) parts.add(context.getString(com.novelcharacter.app.R.string.import_result_relationships, relTotal))
        val nbTotal = result.newNameBank + result.updatedNameBank
        if (nbTotal > 0) parts.add(context.getString(com.novelcharacter.app.R.string.import_result_names, nbTotal))
        if (result.skippedRows > 0)
            parts.add(context.getString(com.novelcharacter.app.R.string.import_result_skipped, result.skippedRows))

        return if (parts.isEmpty()) context.getString(com.novelcharacter.app.R.string.import_complete_empty)
        else context.getString(com.novelcharacter.app.R.string.import_complete, parts.joinToString(", "))
    }

    companion object {
        private const val MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024 // 50MB
    }
}
