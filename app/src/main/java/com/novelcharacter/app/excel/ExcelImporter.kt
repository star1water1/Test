package com.novelcharacter.app.excel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
import android.graphics.Typeface
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
                uri?.let { importFromUri(it) }
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
        importFromUri(uri)
    }

    /**
     * 로컬 파일을 직접 가져오기 (복사 없이).
     * 복호화된 백업 파일 등 이미 로컬에 있는 파일에 사용.
     * 호출자가 파일 수명을 관리해야 함 (가져오기 완료 후 삭제 가능).
     */
    fun importFromLocalFile(file: File) {
        ensureActiveScope().launch {
            try {
                if (file.length() > MAX_IMPORT_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, com.novelcharacter.app.R.string.import_file_too_large, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                if (isZipFile(file)) {
                    importFromZip(file)
                } else {
                    importFromXlsx(file)
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelImporter", "Import failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_failed_retry, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importFromUri(uri: Uri) {
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

            // Phase 1: 분석 프로그레스 표시
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
                            text = appContext.getString(com.novelcharacter.app.R.string.restore_analyzing)
                            gravity = Gravity.CENTER
                            val dp8 = (8 * act.resources.displayMetrics.density).toInt()
                            setPadding(0, dp8, 0, 0)
                        }
                        layout.addView(textView)
                        progressText = textView

                        progressDialog = AlertDialog.Builder(act)
                            .setTitle(appContext.getString(com.novelcharacter.app.R.string.restore_preview_title))
                            .setView(layout)
                            .setCancelable(false)
                            .create()
                        progressDialog?.show()
                    }
                }
            }

            // Phase 2: 백업 내용 분석
            val analysis = importService.analyzeAll(workbook, options) { progress ->
                val pct = if (progress.totalRows > 0) {
                    (progress.processedRows * 100 / progress.totalRows).coerceAtMost(100)
                } else 0
                val text = appContext.getString(com.novelcharacter.app.R.string.import_progress_format, progress.currentPhase, pct)
                importScope.launch(Dispatchers.Main) {
                    progressText?.text = text
                }
            }

            withContext(Dispatchers.Main) { dismissDialogSafely(progressDialog) }

            // Phase 3: 미리보기 + 전략 선택 다이얼로그
            val strategy = showRestorePreviewDialog(analysis) ?: return

            // Phase 3.5: 동명이인 충돌 해결 다이얼로그
            // OVERWRITE 전략은 기존 데이터 전부 삭제 후 재삽입이므로 충돌 해결 불필요
            val resolvedConflicts: Map<String, CharacterConflict> = if (
                strategy != ImportStrategy.OVERWRITE && analysis.characterConflicts.isNotEmpty()
            ) {
                showConflictResolutionDialog(analysis.characterConflicts) ?: return
            } else {
                emptyMap()
            }

            // Phase 4: 실제 가져오기 진행
            progressDialog = null
            progressText = null
            val act2 = currentActivityRef?.get()
            if (act2 != null && !act2.isFinishing && !act2.isDestroyed) {
                withContext(Dispatchers.Main) {
                    val act = currentActivityRef?.get()
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

            val result = importService.importAll(workbook, options, strategy, resolvedConflicts) { progress ->
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
                showResultDialog(result, message)
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

    // ── 복원 미리보기 + 전략 선택 다이얼로그 ──

    private suspend fun showRestorePreviewDialog(analysis: RestoreAnalysis): ImportStrategy? {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return ImportStrategy.MERGE
        }

        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val act = currentActivityRef?.get()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    cont.resume(ImportStrategy.MERGE, null)
                    return@suspendCancellableCoroutine
                }

                val dp = act.resources.displayMetrics.density
                val dp8 = (8 * dp).toInt()
                val dp16 = (16 * dp).toInt()

                val scrollView = ScrollView(act)
                val container = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp16, dp16, dp16, dp8)
                }
                scrollView.addView(container)

                // 카테고리별 분석 결과
                val hasAnyChange = analysis.categories.any { it.newCount > 0 || it.updateCount > 0 }
                val totalOnlyInDb = analysis.categories.sumOf { it.onlyInDb.coerceAtLeast(0) }

                for (cat in analysis.categories) {
                    val catLayout = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp8 / 2, 0, dp8 / 2)
                    }

                    val labelText = TextView(act).apply {
                        text = cat.label
                        setTypeface(null, Typeface.BOLD)
                        textSize = 14f
                    }
                    catLayout.addView(labelText)

                    if (cat.newCount == 0 && cat.updateCount == 0 && cat.inBackup > 0) {
                        val detailText = TextView(act).apply {
                            text = appContext.getString(com.novelcharacter.app.R.string.restore_preview_no_change) +
                                    "  (${appContext.getString(com.novelcharacter.app.R.string.restore_preview_db_info, cat.existingTotal, cat.onlyInDb.coerceAtLeast(0))})"
                            textSize = 12f
                            setPadding(dp8, 0, 0, 0)
                        }
                        catLayout.addView(detailText)
                    } else if (cat.inBackup == 0) {
                        val detailText = TextView(act).apply {
                            text = appContext.getString(com.novelcharacter.app.R.string.restore_preview_no_data, cat.existingTotal)
                            textSize = 12f
                            setPadding(dp8, 0, 0, 0)
                        }
                        catLayout.addView(detailText)
                    } else {
                        val summaryText = TextView(act).apply {
                            text = appContext.getString(com.novelcharacter.app.R.string.restore_preview_summary,
                                cat.newCount, cat.updateCount, cat.unchangedCount)
                            textSize = 12f
                            setPadding(dp8, 0, 0, 0)
                        }
                        catLayout.addView(summaryText)
                        val dbInfoText = TextView(act).apply {
                            text = appContext.getString(com.novelcharacter.app.R.string.restore_preview_db_info,
                                cat.existingTotal, cat.onlyInDb.coerceAtLeast(0))
                            textSize = 11f
                            setPadding(dp8, 0, 0, 0)
                            alpha = 0.7f
                        }
                        catLayout.addView(dbInfoText)
                    }

                    container.addView(catLayout)
                }

                // 구분선
                val divider = android.view.View(act).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { setMargins(0, dp8, 0, dp8) }
                    setBackgroundColor(0x33888888)
                }
                container.addView(divider)

                // 전략 선택
                val strategyLabel = TextView(act).apply {
                    text = appContext.getString(com.novelcharacter.app.R.string.restore_strategy_label)
                    setTypeface(null, Typeface.BOLD)
                    textSize = 14f
                    setPadding(0, 0, 0, dp8)
                }
                container.addView(strategyLabel)

                val radioGroup = RadioGroup(act)
                val mergeRadio = RadioButton(act).apply {
                    id = android.view.View.generateViewId()
                    text = appContext.getString(com.novelcharacter.app.R.string.restore_strategy_merge)
                    isChecked = true
                }
                radioGroup.addView(mergeRadio)
                val mergeDesc = TextView(act).apply {
                    text = appContext.getString(com.novelcharacter.app.R.string.restore_strategy_merge_desc)
                    textSize = 12f
                    setPadding(dp16 * 2, 0, 0, dp8)
                    alpha = 0.7f
                }
                radioGroup.addView(mergeDesc)

                val overwriteRadio = RadioButton(act).apply {
                    id = android.view.View.generateViewId()
                    text = appContext.getString(com.novelcharacter.app.R.string.restore_strategy_overwrite)
                }
                radioGroup.addView(overwriteRadio)
                val overwriteDesc = TextView(act).apply {
                    text = appContext.getString(com.novelcharacter.app.R.string.restore_strategy_overwrite_desc)
                    textSize = 12f
                    setPadding(dp16 * 2, 0, 0, 0)
                    alpha = 0.7f
                }
                radioGroup.addView(overwriteDesc)

                // 덮어쓰기 경고
                val overwriteWarning = TextView(act).apply {
                    visibility = android.view.View.GONE
                    textSize = 12f
                    setPadding(dp16 * 2, dp8 / 2, 0, 0)
                    setTextColor(0xFFFF5722.toInt())
                }

                if (totalOnlyInDb > 0) {
                    val deleteParts = analysis.categories
                        .filter { it.onlyInDb > 0 }
                        .map { appContext.getString(com.novelcharacter.app.R.string.restore_overwrite_delete_item, it.label, it.onlyInDb) }
                    overwriteWarning.text = appContext.getString(
                        com.novelcharacter.app.R.string.restore_overwrite_warning,
                        deleteParts.joinToString(", ")
                    )
                }
                radioGroup.addView(overwriteWarning)

                radioGroup.setOnCheckedChangeListener { _, checkedId ->
                    overwriteWarning.visibility = if (checkedId == overwriteRadio.id && totalOnlyInDb > 0) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                }

                container.addView(radioGroup)

                AlertDialog.Builder(act)
                    .setTitle(com.novelcharacter.app.R.string.restore_preview_title)
                    .setView(scrollView)
                    .setPositiveButton(com.novelcharacter.app.R.string.restore_action) { _, _ ->
                        val strategy = if (overwriteRadio.isChecked) ImportStrategy.OVERWRITE else ImportStrategy.MERGE
                        cont.resume(strategy, null)
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

    // ── 동명이인 충돌 해결 다이얼로그 ──

    private suspend fun showConflictResolutionDialog(
        conflicts: List<CharacterConflict>
    ): Map<String, CharacterConflict>? {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return conflicts.associateBy { "${it.sheetName}:${it.excelRowIndex}" }
        }

        // 사전에 소설 제목 및 세계관 이름 로드 (IO 스레드)
        val novelTitleCache = mutableMapOf<Long, String>()
        val novelUniverseNameCache = mutableMapOf<Long, String?>()
        for (conflict in conflicts) {
            for (char in conflict.existingCharacters) {
                val nid = char.novelId ?: continue
                if (nid !in novelTitleCache) {
                    val novel = db.novelDao().getNovelById(nid)
                    novelTitleCache[nid] = novel?.title
                        ?: appContext.getString(com.novelcharacter.app.R.string.duplicate_novel_none)
                    val univName = novel?.universeId?.let { db.universeDao().getUniverseById(it)?.name }
                    novelUniverseNameCache[nid] = univName
                }
            }
        }

        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val act = currentActivityRef?.get()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    cont.resume(conflicts.associateBy { "${it.sheetName}:${it.excelRowIndex}" }, null)
                    return@suspendCancellableCoroutine
                }

                val dp = act.resources.displayMetrics.density
                val dp8 = (8 * dp).toInt()
                val dp16 = (16 * dp).toInt()

                val scrollView = ScrollView(act).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val container = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp16, dp8, dp16, dp8)
                }
                scrollView.addView(container)

                // 안내 메시지
                val messageView = TextView(act).apply {
                    text = appContext.getString(com.novelcharacter.app.R.string.import_conflict_message, conflicts.size)
                    textSize = 13f
                    setPadding(0, 0, 0, dp8)
                }
                container.addView(messageView)

                // 충돌별 RadioGroup 매핑
                data class ConflictUI(
                    val conflict: CharacterConflict,
                    val radioGroup: RadioGroup,
                    val skipRadioId: Int,
                    val updateRadios: Map<Int, Long>,
                    val cleanupCheckboxes: Map<Int, CheckBox> = emptyMap()  // updateRadioId → CheckBox
                )
                val conflictUIs = mutableListOf<ConflictUI>()

                for (conflict in conflicts) {
                    // 구분선
                    val divider = android.view.View(act).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { setMargins(0, dp8, 0, dp8) }
                        setBackgroundColor(0x33888888)
                    }
                    container.addView(divider)

                    // 행 정보
                    val rowLabel = TextView(act).apply {
                        text = appContext.getString(com.novelcharacter.app.R.string.import_conflict_row_format,
                            conflict.excelRowIndex, conflict.excelName)
                        setTypeface(null, Typeface.BOLD)
                        textSize = 13f
                    }
                    container.addView(rowLabel)

                    if (!conflict.excelNovelTitle.isNullOrBlank()) {
                        val novelLabel = TextView(act).apply {
                            text = "  작품: ${conflict.excelNovelTitle}"
                            textSize = 11f
                            alpha = 0.7f
                        }
                        container.addView(novelLabel)
                    }

                    // 선택지 RadioGroup
                    val radioGroup = RadioGroup(act).apply {
                        setPadding(dp8, dp8 / 2, 0, 0)
                    }

                    // 옵션 1: 건너뛰기
                    val skipRadio = RadioButton(act).apply {
                        id = android.view.View.generateViewId()
                        text = appContext.getString(com.novelcharacter.app.R.string.import_conflict_skip)
                        textSize = 12f
                    }
                    radioGroup.addView(skipRadio)

                    // 옵션 2: 동명이인으로 새로 생성 (기본값)
                    val createNewRadio = RadioButton(act).apply {
                        id = android.view.View.generateViewId()
                        text = appContext.getString(com.novelcharacter.app.R.string.import_conflict_create_new)
                        textSize = 12f
                        isChecked = true
                    }
                    radioGroup.addView(createNewRadio)

                    // 옵션 3+: 기존 캐릭터 업데이트 (매칭된 캐릭터당 1개)
                    val updateRadios = mutableMapOf<Int, Long>()
                    val cleanupCheckboxes = mutableMapOf<Int, CheckBox>()
                    val importingSheetName = conflict.sheetName
                    for (existing in conflict.existingCharacters) {
                        val novelTitle = existing.novelId?.let { nid ->
                            novelTitleCache[nid]
                        } ?: appContext.getString(com.novelcharacter.app.R.string.duplicate_novel_none)
                        val updateRadio = RadioButton(act).apply {
                            id = android.view.View.generateViewId()
                            text = appContext.getString(com.novelcharacter.app.R.string.import_conflict_update_format,
                                existing.name, novelTitle)
                            textSize = 12f
                        }
                        updateRadios[updateRadio.id] = existing.id
                        radioGroup.addView(updateRadio)

                        // 세계관이 다른 캐릭터면 필드 정리 체크박스 추가
                        val existingUniverseName = existing.novelId?.let { novelUniverseNameCache[it] }
                        if (existingUniverseName != null && existingUniverseName != importingSheetName) {
                            val cleanupCb = CheckBox(act).apply {
                                text = appContext.getString(com.novelcharacter.app.R.string.import_conflict_cleanup_fields,
                                    existingUniverseName)
                                textSize = 11f
                                alpha = 0.85f
                                setPadding((32 * dp).toInt(), 0, 0, 0)
                                visibility = android.view.View.GONE
                                isChecked = true
                            }
                            cleanupCheckboxes[updateRadio.id] = cleanupCb
                            radioGroup.addView(cleanupCb)
                        }
                    }

                    // 라디오 선택 변경 시 해당 체크박스만 표시
                    radioGroup.setOnCheckedChangeListener { _, checkedId ->
                        cleanupCheckboxes.values.forEach { it.visibility = android.view.View.GONE }
                        cleanupCheckboxes[checkedId]?.visibility = android.view.View.VISIBLE
                    }

                    container.addView(radioGroup)
                    conflictUIs.add(ConflictUI(conflict, radioGroup, skipRadio.id, updateRadios, cleanupCheckboxes))
                }

                AlertDialog.Builder(act)
                    .setTitle(com.novelcharacter.app.R.string.import_conflict_title)
                    .setView(scrollView)
                    .setPositiveButton(com.novelcharacter.app.R.string.import_conflict_confirm) { _, _ ->
                        // 각 충돌의 선택 결과 수집
                        val resultMap = mutableMapOf<String, CharacterConflict>()
                        for (ui in conflictUIs) {
                            val checkedId = ui.radioGroup.checkedRadioButtonId
                            val key = "${ui.conflict.sheetName}:${ui.conflict.excelRowIndex}"
                            val selectedExistingId = ui.updateRadios[checkedId]
                            val resolution = when {
                                selectedExistingId != null -> ConflictResolution.UPDATE_EXISTING
                                checkedId == ui.skipRadioId -> ConflictResolution.SKIP
                                else -> ConflictResolution.CREATE_NEW
                            }
                            val cleanupOldFields = if (selectedExistingId != null) {
                                ui.cleanupCheckboxes[checkedId]?.isChecked == true
                            } else false
                            resultMap[key] = ui.conflict.copy(
                                resolution = resolution,
                                selectedExistingId = selectedExistingId,
                                cleanupOldFields = cleanupOldFields
                            )
                        }
                        cont.resume(resultMap, null)
                    }
                    .setNegativeButton(com.novelcharacter.app.R.string.duplicate_action_cancel) { _, _ ->
                        cont.resume(null, null)
                    }
                    .setOnCancelListener {
                        cont.resume(null, null)
                    }
                    .show()
            }
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

    private fun showResultDialog(result: ImportResult, summaryMessage: String) {
        val act = currentActivityRef?.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            Toast.makeText(appContext, summaryMessage, Toast.LENGTH_LONG).show()
            return
        }

        val hasDetails = result.errors.isNotEmpty() || result.warnings.isNotEmpty()
        val builder = AlertDialog.Builder(act)
            .setTitle(appContext.getString(com.novelcharacter.app.R.string.import_result_title))
            .setMessage(summaryMessage)

        if (hasDetails) {
            builder.setNeutralButton(appContext.getString(com.novelcharacter.app.R.string.import_result_details)) { _, _ ->
                showErrorDetailDialog(act, result)
            }
        }
        builder.setPositiveButton(appContext.getString(com.novelcharacter.app.R.string.confirm), null)
        builder.show()
    }

    private fun showErrorDetailDialog(act: android.app.Activity, result: ImportResult) {
        if (act.isFinishing || act.isDestroyed) return

        val sb = StringBuilder()
        if (result.errors.isNotEmpty()) {
            sb.appendLine("── 오류 (${result.errors.size}건) ──")
            result.errors.forEachIndexed { i, err ->
                sb.appendLine("${i + 1}. $err")
            }
        }
        if (result.warnings.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("── 경고 (${result.warnings.size}건) ──")
            result.warnings.forEach { sb.appendLine("• $it") }
        }

        val scrollView = android.widget.ScrollView(act)
        val textView = TextView(act).apply {
            text = sb.toString()
            val dp16 = (16 * act.resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, dp16)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(act)
            .setTitle(appContext.getString(com.novelcharacter.app.R.string.import_result_details))
            .setView(scrollView)
            .setPositiveButton(appContext.getString(com.novelcharacter.app.R.string.confirm), null)
            .show()
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
