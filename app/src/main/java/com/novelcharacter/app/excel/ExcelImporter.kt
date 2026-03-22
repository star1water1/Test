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
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

class ExcelImporter(context: Context) {

    private val appContext: Context = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    @Volatile private var supervisorJob = kotlinx.coroutines.SupervisorJob()
    @Volatile private var importScope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private val importService = ExcelImportService(db, appContext)
    private var pendingImageFailures = 0

    private var importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var currentActivityRef: java.lang.ref.WeakReference<Activity>? = null

    fun registerLauncher(fragment: Fragment) {
        currentActivityRef = java.lang.ref.WeakReference(fragment.activity)
        try {
            importLauncher = fragment.registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { importFromFile(it) }
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
                "application/zip",
                "application/octet-stream"
            ))
        } else {
            Toast.makeText(appContext, com.novelcharacter.app.R.string.importer_restart, Toast.LENGTH_SHORT).show()
        }
    }

    /** 외부에서 URI로 직접 가져오기 (하위호환) */
    fun importFromExcel(uri: Uri) {
        importFromFile(uri)
    }

    private fun importFromFile(uri: Uri) {
        ensureActiveScope().launch {
            try {
                // 파일 크기 체크
                val fileSize = appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                if (fileSize > MAX_IMPORT_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, com.novelcharacter.app.R.string.import_file_too_large, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 파일을 임시로 복사하여 형식 감지
                val tempFile = File(appContext.cacheDir, "import_temp_${System.currentTimeMillis()}")
                try {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    } ?: throw Exception("Cannot open file input stream")

                    if (isZipFile(tempFile)) {
                        importFromZip(tempFile)
                    } else {
                        importFromXlsx(tempFile)
                    }
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelImporter", "Import failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_failed_retry, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── ZIP 감지 ──

    private fun isZipFile(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                read >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
                    && magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
            }
        } catch (_: Exception) { false }
    }

    // ── ZIP에서 가져오기 ──

    private suspend fun importFromZip(zipTempFile: File) {
        val extractDir = File(appContext.cacheDir, "import_extract_${System.currentTimeMillis()}")
        try {
            extractDir.mkdirs()

            // ZIP 해제
            var xlsxFile: File? = null
            var imageMapJson: String? = null
            val hasImages: Boolean

            ZipFile(zipTempFile).use { zip ->
                var imageCount = 0
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    when {
                        entry.name == "data.xlsx" -> {
                            xlsxFile = File(extractDir, "data.xlsx")
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(xlsxFile!!).use { output -> input.copyTo(output) }
                            }
                        }
                        entry.name == "image_map.json" -> {
                            imageMapJson = zip.getInputStream(entry).use { it.bufferedReader().readText() }
                        }
                        entry.name.startsWith("images/") -> {
                            val imageFile = File(extractDir, entry.name)
                            if (!imageFile.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                                Log.w("ExcelImporter", "Skipping suspicious zip entry: ${entry.name}")
                                continue
                            }
                            imageFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(imageFile).use { output -> input.copyTo(output) }
                            }
                            imageCount++
                        }
                    }
                }
                hasImages = imageCount > 0
            }

            if (xlsxFile == null || !xlsxFile!!.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_zip_no_data, Toast.LENGTH_LONG).show()
                }
                return
            }

            // 이미지 경로 재매핑 생성
            val imagePathRemap = buildImageRemap(imageMapJson, extractDir)

            // 가져오기 옵션 다이얼로그 표시
            val options = showImportOptionsDialog(hasImages) ?: return

            // 이미지 복원: extractDir에서 filesDir로 복사
            pendingImageFailures = 0
            if (options.images && hasImages) {
                pendingImageFailures = restoreImages(imageMapJson, extractDir, imagePathRemap)
            }

            // xlsx 가져오기
            importService.imagePathRemap = if (options.images) imagePathRemap else emptyMap()
            importFromXlsx(xlsxFile!!, options)
            importService.imagePathRemap = emptyMap()

        } finally {
            extractDir.deleteRecursively()
        }
    }

    private fun buildImageRemap(imageMapJson: String?, extractDir: File): Map<String, String> {
        if (imageMapJson == null) return emptyMap()
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        val originalMap: Map<String, String> = try {
            gson.fromJson(imageMapJson, type)
        } catch (_: Exception) { return emptyMap() }

        val remap = mutableMapOf<String, String>()
        val appDir = appContext.filesDir
        for ((originalPath, zipRelPath) in originalMap) {
            val extractedFile = File(extractDir, zipRelPath)
            if (!extractedFile.exists()) continue
            val newFileName = "char_${UUID.randomUUID()}.jpg"
            val newPath = File(appDir, newFileName).absolutePath
            remap[originalPath] = newPath
        }
        return remap
    }

    /**
     * @return 복원 실패한 이미지 수
     */
    private fun restoreImages(
        imageMapJson: String?, extractDir: File, imagePathRemap: Map<String, String>
    ): Int {
        if (imageMapJson == null) return 0
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        val originalMap: Map<String, String> = try {
            gson.fromJson(imageMapJson, type)
        } catch (_: Exception) { return 0 }

        var failedCount = 0
        for ((originalPath, zipRelPath) in originalMap) {
            val newPath = imagePathRemap[originalPath] ?: continue
            val extractedFile = File(extractDir, zipRelPath)
            if (!extractedFile.exists()) { failedCount++; continue }
            val destFile = File(newPath)
            if (destFile.exists()) continue
            try {
                extractedFile.copyTo(destFile)
            } catch (_: Exception) { failedCount++ }
        }
        return failedCount
    }

    // ── XLSX에서 가져오기 ──

    private suspend fun importFromXlsx(xlsxFile: File, options: ExportOptions = ExportOptions()) {
        var workbook: org.apache.poi.ss.usermodel.Workbook? = null
        var progressDialog: AlertDialog? = null
        var progressText: TextView? = null

        try {
            org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.01)
            org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024)

            workbook = xlsxFile.inputStream().use { WorkbookFactory.create(it) }

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
                        val progressBar = ProgressBar(act).apply { isIndeterminate = true }
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

            val result = importService.importAll(workbook, options) { progress ->
                val pct = if (progress.totalRows > 0) {
                    (progress.processedRows * 100 / progress.totalRows).coerceAtMost(100)
                } else 0
                val text = appContext.getString(com.novelcharacter.app.R.string.import_progress_format, progress.currentPhase, pct)
                importScope.launch(Dispatchers.Main) {
                    progressText?.text = text
                }
            }

            if (pendingImageFailures > 0) {
                result.warnings.add("${pendingImageFailures}개 이미지 복원 실패")
                pendingImageFailures = 0
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
            try { workbook?.close() } catch (e: Exception) { android.util.Log.w("ExcelImporter", "Failed to close workbook", e) }
        }
    }

    // ── 가져오기 옵션 다이얼로그 ──

    private suspend fun showImportOptionsDialog(hasImages: Boolean): ExportOptions? {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return ExportOptions()
        }

        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val labels = ExportOptions.LABELS.toMutableList()
                val checked = ExportOptions.ALL.toBooleanArray().toMutableList()

                // 이미지 옵션: ZIP에 이미지가 있을 때만 기본 선택
                checked[checked.size - 1] = hasImages

                val checkedArray = checked.toBooleanArray()

                AlertDialog.Builder(activity)
                    .setTitle(com.novelcharacter.app.R.string.import_options_title)
                    .setMultiChoiceItems(labels.toTypedArray(), checkedArray) { _, which, isChecked ->
                        checkedArray[which] = isChecked
                    }
                    .setPositiveButton(com.novelcharacter.app.R.string.confirm) { _, _ ->
                        cont.resume(ExportOptions.fromBooleanArray(checkedArray), null)
                    }
                    .setNegativeButton(com.novelcharacter.app.R.string.cancel) { _, _ ->
                        cont.resume(null, null)
                    }
                    .setOnCancelListener {
                        cont.resume(null, null)
                    }
                    .show()
            }
        }
    }

    // ── 결과 메시지 ──

    private fun buildResultMessage(result: ImportResult): String {
        val r = appContext.resources
        val parts = mutableListOf<String>()
        val charTotal = result.newCharacters + result.updatedCharacters
        val eventTotal = result.newEvents + result.updatedEvents

        if (result.newUniverses > 0 || result.updatedUniverses > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_universes, result.newUniverses + result.updatedUniverses))
        if (result.newNovels > 0 || result.updatedNovels > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_novels, result.newNovels + result.updatedNovels))
        if (result.newFields > 0 || result.updatedFields > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_fields, result.newFields + result.updatedFields))
        if (charTotal > 0) {
            parts.add(if (result.updatedCharacters > 0) {
                r.getString(com.novelcharacter.app.R.string.import_result_characters_updated, charTotal, result.updatedCharacters)
            } else {
                r.getString(com.novelcharacter.app.R.string.import_result_characters, charTotal)
            })
        }
        if (eventTotal > 0) {
            parts.add(if (result.updatedEvents > 0) {
                r.getString(com.novelcharacter.app.R.string.import_result_events_updated, eventTotal, result.updatedEvents)
            } else {
                r.getString(com.novelcharacter.app.R.string.import_result_events, eventTotal)
            })
        }
        val scTotal = result.newStateChanges + result.updatedStateChanges
        if (scTotal > 0) {
            parts.add(if (result.updatedStateChanges > 0) {
                r.getString(com.novelcharacter.app.R.string.import_result_state_changes_updated, scTotal, result.updatedStateChanges)
            } else {
                r.getString(com.novelcharacter.app.R.string.import_result_state_changes, scTotal)
            })
        }
        val relTotal = result.newRelationships + result.updatedRelationships
        if (relTotal > 0) parts.add(r.getString(com.novelcharacter.app.R.string.import_result_relationships, relTotal))
        if (result.newRelationshipChanges > 0) parts.add(r.getString(com.novelcharacter.app.R.string.import_result_relationship_changes, result.newRelationshipChanges))
        val nbTotal = result.newNameBank + result.updatedNameBank
        if (nbTotal > 0) parts.add(r.getString(com.novelcharacter.app.R.string.import_result_names, nbTotal))
        val ptTotal = result.newPresetTemplates + result.updatedPresetTemplates
        if (ptTotal > 0) parts.add(r.getString(com.novelcharacter.app.R.string.import_result_preset_templates, ptTotal))
        val spTotal = result.newSearchPresets + result.updatedSearchPresets
        if (spTotal > 0) parts.add(r.getString(com.novelcharacter.app.R.string.import_result_search_presets, spTotal))
        if (result.restoredSettings > 0) parts.add(r.getString(com.novelcharacter.app.R.string.import_result_settings, result.restoredSettings))
        if (result.skippedRows > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_skipped, result.skippedRows))
        if (result.nameBasedMappings > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_name_mappings, result.nameBasedMappings))
        if (result.newCodesGenerated > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_new_codes, result.newCodesGenerated))

        if (result.warnings.isNotEmpty()) {
            parts.add("⚠ ${result.warnings.size}건 경고")
        }

        return if (parts.isEmpty()) r.getString(com.novelcharacter.app.R.string.import_result_empty)
        else r.getString(com.novelcharacter.app.R.string.import_result_complete, parts.joinToString(", "))
    }

    private fun dismissDialogSafely(dialog: AlertDialog?) {
        try {
            val act = currentActivityRef?.get()
            if (dialog != null && dialog.isShowing && act != null && !act.isFinishing && !act.isDestroyed) {
                dialog.dismiss()
            }
        } catch (e: Exception) {
            android.util.Log.w("ExcelImporter", "Failed to dismiss progress dialog", e)
        }
    }

    companion object {
        private const val MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024 // 50MB (ZIP with images can be larger)
    }
}
