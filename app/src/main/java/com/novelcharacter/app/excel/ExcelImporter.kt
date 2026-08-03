package com.novelcharacter.app.excel

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
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
import com.novelcharacter.app.ui.common.TaskProgressDialog
import com.novelcharacter.app.util.CopyCancelledException
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.ProgressScale
import com.novelcharacter.app.util.copyWithLimit
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

    /**
     * **이번 가져오기가 filesDir에 새로 만든** 이미지 파일들 (B-77).
     *
     * `importAll`은 전체가 한 트랜잭션이라 실패하면 DB는 통째로 롤백되는데, 이미지 복사는
     * 그 **트랜잭션 밖에서 먼저** 일어난다 — 그래서 실패하면 가리킬 행이 없는 파일만 남았다.
     * 되돌리려면 남의 이미지를 건드리지 않아야 하므로 **새로 만든 것만** 여기 모은다
     * ([restoreImages]가 이미 존재하는 목적지를 건너뛰므로 이 목록은 정확히 신규분이다).
     */
    private var restoredImagePaths: List<String> = emptyList()

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

    // ── 작업형 진행도 (규약 R-26 · B-51 완결) ─────────────────────────────────────
    //
    // 가져오기는 구간이 **다섯**이고(복사 · 압축 풀기 · 이미지 되살리기 · 분석 · 반영)
    // 단위가 둘이다(MB와 건). 종전에는 가장 오래 걸리는 앞 두 구간에 **표시가 아예 없었고**
    // 표시가 있는 분석 단계가 맨 뒤였다 — 750MB 백업에서 몇 분 동안 화면이 멈춘 것과
    // 구분되지 않았다(사용자 회신이 이 항목의 착수 근거다).
    //
    // 착수 대조가 조사표를 넓혔다: 표는 "3구간"이라 적었으나 **이미지 되살리기 구간이 빠져
    // 있었다.** 그 구간도 총량(복원 대상 장 수)을 알고 파일 복사라 실제로 오래 걸린다.
    //
    // 창은 **한 번의 가져오기가 하나**를 공유한다 — 구간마다 새로 띄우면 다섯 번 깜빡인다.
    // 다만 사용자에게 묻는 창(옵션 · 미리보기 · 충돌 해결) 앞에서는 닫는다. 진행 창 위에
    // 선택 창을 겹치면 어느 쪽이 살아 있는지 알 수 없다.

    /**
     * 진행 창을 띄운다.
     *
     * 화면이 없으면(액티비티가 이미 사라진 뒤의 복원 등) null이고, **작업은 그대로 진행된다** —
     * 표시가 없는 것과 작업이 없는 것은 다른 일이다.
     */
    private suspend fun showTaskProgress(
        total: Int,
        @androidx.annotation.StringRes stageRes: Int,
        format: TaskProgressDialog.CountFormat = TaskProgressDialog.CountFormat.ITEMS,
        onCancel: (() -> Unit)? = null
    ): TaskProgressDialog.Handle? = withContext(Dispatchers.Main) {
        val act = currentActivityRef?.get()
        if (act == null || act.isFinishing || act.isDestroyed) return@withContext null
        runCatching {
            TaskProgressDialog.show(
                act,
                titleRes = com.novelcharacter.app.R.string.import_progress_title,
                total = total,
                stageRes = stageRes,
                format = format,
                onCancel = onCancel
            )
        }.getOrNull()
    }

    /**
     * 진행도 갱신 — 작업은 IO, 갱신은 메인(R-26).
     *
     * 화면이 사라진 뒤의 갱신은 조용히 버린다. 그래도 작업 자체는 계속 돈다(취소가 아니다).
     */
    private fun postProgress(
        handle: TaskProgressDialog.Handle?,
        current: Int,
        total: Int,
        stage: String? = null,
        format: TaskProgressDialog.CountFormat = TaskProgressDialog.CountFormat.ITEMS
    ) {
        if (handle == null) return
        importScope.launch(Dispatchers.Main) { handle.update(current, total, stage, format) }
    }

    /** 진행 창 닫기 — 완료·실패·취소와 무관하게 부른다. */
    private suspend fun dismissTaskProgress(handle: TaskProgressDialog.Handle?) {
        if (handle == null) return
        withContext(Dispatchers.Main) { handle.dismiss() }
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
                routeImport(file)
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
            // 복사 구간의 취소는 **폐기**다 — 반쯤 받아온 파일은 어차피 열 수 없고, 아직 DB에
            // 아무것도 쓰지 않았으므로 되돌릴 것도 없다(R-26: 반쪽 항목을 남기지 않는다).
            var cancelled = false
            var progress: TaskProgressDialog.Handle? = null
            try {
                // 파일 크기 체크 (외부 파일 전체 상한 — 이미지 포함 ZIP 왕복을 보장하는 수준으로 넉넉히)
                val fileSize = appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                if (fileSize > MAX_EXTERNAL_FILE_SIZE) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, com.novelcharacter.app.R.string.import_file_too_large, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 파일을 임시로 복사하여 형식 감지
                // statSize를 보고하지 않는 콘텐츠 프로바이더(-1L)가 있으므로 복사 단계에서도 크기 상한을 강제한다
                val tempFile = File(appContext.cacheDir, "import_temp_${System.currentTimeMillis()}")
                try {
                    // 크기를 보고하지 않는 프로바이더면 총량 눈금이 0이 되고, 창은 퍼센트 대신
                    // 불확정 막대와 "지금까지 N MB"를 그린다(퍼센트를 지어내지 않는다).
                    val scale = ProgressScale.forBytes(fileSize)
                    progress = showTaskProgress(
                        total = scale.totalSteps,
                        stageRes = com.novelcharacter.app.R.string.import_progress_stage_copy,
                        format = TaskProgressDialog.CountFormat.MEGABYTES
                    ) { cancelled = true }

                    val copied = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            copyWithLimit(
                                input, output, MAX_EXTERNAL_FILE_SIZE,
                                onProgress = { done ->
                                    postProgress(
                                        progress, scale.stepsFor(done), scale.totalSteps,
                                        format = TaskProgressDialog.CountFormat.MEGABYTES
                                    )
                                },
                                isCancelled = { cancelled }
                            )
                        }
                    } ?: throw Exception("Cannot open file input stream")
                    if (copied > MAX_EXTERNAL_FILE_SIZE) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, com.novelcharacter.app.R.string.import_file_too_large, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }

                    routeImport(tempFile, progress)
                } finally {
                    tempFile.delete()
                }
            } catch (e: CopyCancelledException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ExcelImporter", "Import failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_failed_retry, Toast.LENGTH_LONG).show()
                }
            } finally {
                dismissTaskProgress(progress)
            }
        }
    }

    // ── 형식 판별 3분기 (S-5) ──

    /**
     * 판별([ImportFileFormat])에 따라 경로를 나눈다:
     * - 앱 백업 ZIP(data.xlsx) → 기존 엑셀 ZIP 경로
     * - 월드패키지(manifest.json) → [importWorldPackage] (종전에는 xlsx로 오판되어 일반 오류만 떴다)
     * - 그 외 ZIP → 원인별 안내 (일반 "가져오기 실패" 금지)
     * - ZIP 아님/평범한 xlsx → 기존 xlsx 경로 (구형 .xls는 POI가 판별)
     */
    private suspend fun routeImport(file: File, progress: TaskProgressDialog.Handle? = null) {
        when (ImportFileFormat.detect(file)) {
            ImportFileKind.EXCEL_BACKUP_ZIP -> {
                // 이미지 포함 백업(ZIP)은 전체 크기 대신 해제 단계에서 엔트리별 상한을 검사한다
                // (전체 상한을 걸면 앱이 만든 대용량 백업이 스스로 복원 불가가 됨)
                importFromZip(file, progress)
            }
            ImportFileKind.WORLD_PACKAGE -> importWorldPackage(file)
            ImportFileKind.OTHER_ZIP -> withContext(Dispatchers.Main) {
                Toast.makeText(appContext, com.novelcharacter.app.R.string.import_unsupported_zip, Toast.LENGTH_LONG).show()
            }
            // B-8: 크기로 거부하지 않는다. 큰 파일은 스트리밍 경로가 받는다([openImportSource]) —
            // 종전에는 앱이 만든 백업이 128MB를 넘으면 앱 자신이 복원을 거부했다(자기모순).
            ImportFileKind.PLAIN_XLSX, ImportFileKind.NOT_ZIP -> importFromXlsx(file, progress = progress)
        }
    }

    // ── 월드패키지 가져오기 (S-5) ──

    private val worldPackageImporter by lazy {
        com.novelcharacter.app.share.WorldPackageImporter(appContext)
    }

    private suspend fun importWorldPackage(pkgFile: File) {
        val extractDir = File(appContext.cacheDir, "world_import_${System.currentTimeMillis()}")
        var progressDialog: AlertDialog? = null
        try {
            extractDir.mkdirs()
            val read = worldPackageImporter.read(pkgFile, extractDir)
            val contents = when (read) {
                is com.novelcharacter.app.share.WorldPackageImporter.ReadResult.Ok -> read.contents
                is com.novelcharacter.app.share.WorldPackageImporter.ReadResult.Failed -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, parseFailureMessage(read.parse), Toast.LENGTH_LONG).show()
                    }
                    return
                }
                com.novelcharacter.app.share.WorldPackageImporter.ReadResult.TooLarge -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, com.novelcharacter.app.R.string.world_package_too_large, Toast.LENGTH_LONG).show()
                    }
                    return
                }
                com.novelcharacter.app.share.WorldPackageImporter.ReadResult.ImagesTooLarge -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, com.novelcharacter.app.R.string.world_package_too_many_images, Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }

            // 충돌 3분기: 덮어쓰기 / 새로 생성 / 건너뛰기 (검증 → 알림 → 바로잡을 경로)
            val conflict = worldPackageImporter.findConflict(contents.universe)
            var overwriteTarget: com.novelcharacter.app.data.model.Universe? = null
            val mode: com.novelcharacter.app.share.WorldPackageImporter.Mode
            if (conflict == null) {
                mode = com.novelcharacter.app.share.WorldPackageImporter.Mode.CLEAN
            } else {
                mode = showWorldConflictDialog(contents.universe.name, conflict) ?: return
                if (mode == com.novelcharacter.app.share.WorldPackageImporter.Mode.OVERWRITE) {
                    val target = conflict.target ?: return
                    // 파괴적 동작은 실행 전에 결과를 알리고 취소 경로를 남긴다 (R-4)
                    if (!confirmWorldOverwrite(target)) return
                    overwriteTarget = target
                }
            }

            withContext(Dispatchers.Main) {
                val act = currentActivityRef?.get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    progressDialog = createWorldProgressDialog(act)
                    progressDialog?.show()
                }
            }

            val outcome = try {
                worldPackageImporter.import(contents, mode, overwriteTarget, extractDir)
            } catch (e: Exception) {
                android.util.Log.e("ExcelImporter", "World package import failed", e)
                withContext(Dispatchers.Main) {
                    dismissDialogSafely(progressDialog)
                    // 덮어쓰기 경로의 실패는 기존 세계관이 이미 휴지통으로 간 뒤다 —
                    // 그 행방을 알리지 않으면 "실패했는데 세계관이 사라진" 것으로 보인다(R-4)
                    val messageRes = if (mode == com.novelcharacter.app.share.WorldPackageImporter.Mode.OVERWRITE) {
                        com.novelcharacter.app.R.string.world_package_import_failed_after_overwrite
                    } else {
                        com.novelcharacter.app.R.string.world_package_import_failed
                    }
                    Toast.makeText(appContext, messageRes, Toast.LENGTH_LONG).show()
                }
                return
            }

            withContext(Dispatchers.Main) {
                dismissDialogSafely(progressDialog)
                showWorldResultDialog(outcome)
            }
        } catch (e: Exception) {
            android.util.Log.e("ExcelImporter", "World package import failed", e)
            withContext(Dispatchers.Main) {
                dismissDialogSafely(progressDialog)
                Toast.makeText(appContext, com.novelcharacter.app.R.string.world_package_import_failed, Toast.LENGTH_LONG).show()
            }
        } finally {
            extractDir.deleteRecursively()
        }
    }

    /** 형식 인식 실패의 원인별 메시지 — 일반 "가져오기 실패" 토스트 금지 (S-5). */
    private fun parseFailureMessage(parse: com.novelcharacter.app.share.WorldPackageParseResult): String {
        return when (parse) {
            is com.novelcharacter.app.share.WorldPackageParseResult.UnsupportedVersion ->
                appContext.getString(com.novelcharacter.app.R.string.world_package_unsupported_version, parse.found)
            is com.novelcharacter.app.share.WorldPackageParseResult.MissingEntry ->
                appContext.getString(com.novelcharacter.app.R.string.world_package_corrupted, parse.entryName)
            is com.novelcharacter.app.share.WorldPackageParseResult.Malformed ->
                appContext.getString(com.novelcharacter.app.R.string.world_package_corrupted, parse.entryName)
            else -> appContext.getString(com.novelcharacter.app.R.string.world_package_not_a_package)
        }
    }

    /** @return 선택된 모드, null이면 건너뛰기/취소 */
    private suspend fun showWorldConflictDialog(
        universeName: String,
        conflict: com.novelcharacter.app.share.WorldPackageImporter.Conflict
    ): com.novelcharacter.app.share.WorldPackageImporter.Mode? {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) return null
        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val act = currentActivityRef?.get()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    cont.resume(null, null)
                    return@suspendCancellableCoroutine
                }
                var resumed = false
                fun finish(mode: com.novelcharacter.app.share.WorldPackageImporter.Mode?) {
                    if (!resumed) {
                        resumed = true
                        cont.resume(mode, null)
                    }
                }
                val message = when {
                    conflict.byCode -> appContext.getString(
                        com.novelcharacter.app.R.string.world_package_conflict_code, universeName
                    )
                    conflict.target != null -> appContext.getString(
                        com.novelcharacter.app.R.string.world_package_conflict_name, universeName
                    )
                    else -> appContext.getString(
                        com.novelcharacter.app.R.string.world_package_conflict_name_multi,
                        universeName, conflict.nameMatches
                    )
                }
                val builder = MaterialAlertDialogBuilder(act)
                    .setTitle(com.novelcharacter.app.R.string.share_world_conflict_title)
                    .setMessage(message)
                    .setNegativeButton(com.novelcharacter.app.R.string.share_world_conflict_skip) { _, _ -> finish(null) }
                    .setOnCancelListener { finish(null) }
                if (conflict.target != null) {
                    builder.setPositiveButton(com.novelcharacter.app.R.string.share_world_conflict_overwrite) { _, _ ->
                        finish(com.novelcharacter.app.share.WorldPackageImporter.Mode.OVERWRITE)
                    }
                    builder.setNeutralButton(com.novelcharacter.app.R.string.share_world_conflict_new) { _, _ ->
                        finish(com.novelcharacter.app.share.WorldPackageImporter.Mode.AS_NEW)
                    }
                } else {
                    // 동명 세계관이 여럿 — 덮어쓰기 대상이 모호하므로 그 선택지는 내리지 않는다
                    builder.setPositiveButton(com.novelcharacter.app.R.string.share_world_conflict_new) { _, _ ->
                        finish(com.novelcharacter.app.share.WorldPackageImporter.Mode.AS_NEW)
                    }
                }
                builder.show()
            }
        }
    }

    private suspend fun confirmWorldOverwrite(target: com.novelcharacter.app.data.model.Universe): Boolean {
        val characterCount = db.characterDao().getCharactersByUniverseList(target.id).size
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) return false
        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val act = currentActivityRef?.get()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    cont.resume(false, null)
                    return@suspendCancellableCoroutine
                }
                var resumed = false
                fun finish(confirmed: Boolean) {
                    if (!resumed) {
                        resumed = true
                        cont.resume(confirmed, null)
                    }
                }
                MaterialAlertDialogBuilder(act)
                    .setTitle(com.novelcharacter.app.R.string.share_world_conflict_overwrite)
                    .setMessage(
                        appContext.getString(
                            com.novelcharacter.app.R.string.world_package_overwrite_confirm,
                            target.name, characterCount
                        )
                    )
                    .setPositiveButton(com.novelcharacter.app.R.string.confirm) { _, _ -> finish(true) }
                    .setNegativeButton(com.novelcharacter.app.R.string.cancel) { _, _ -> finish(false) }
                    .setOnCancelListener { finish(false) }
                    .show()
            }
        }
    }

    private fun createWorldProgressDialog(act: Activity): AlertDialog {
        val dp16 = (16 * act.resources.displayMetrics.density).toInt()
        val dp8 = dp16 / 2
        val layout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp16 * 2, dp16, dp16 * 2, dp16)
        }
        layout.addView(ProgressBar(act).apply { isIndeterminate = true })
        layout.addView(TextView(act).apply {
            text = appContext.getString(com.novelcharacter.app.R.string.world_package_importing)
            gravity = Gravity.CENTER
            setPadding(0, dp8, 0, 0)
        })
        return MaterialAlertDialogBuilder(act)
            .setTitle(appContext.getString(com.novelcharacter.app.R.string.world_package_import_title))
            .setView(layout)
            .setCancelable(false)
            .create()
    }

    private fun showWorldResultDialog(outcome: com.novelcharacter.app.share.WorldPackageImporter.Outcome) {
        val parts = mutableListOf<String>()
        if (outcome.novels > 0) parts.add("작품 ${outcome.novels}")
        if (outcome.characters > 0) parts.add("캐릭터 ${outcome.characters}")
        if (outcome.fieldDefinitions > 0) parts.add("필드 ${outcome.fieldDefinitions}")
        if (outcome.gradeSystems > 0) parts.add("등급 체계 ${outcome.gradeSystems}")
        if (outcome.fieldValues > 0) parts.add("필드값 ${outcome.fieldValues}")
        if (outcome.events > 0) parts.add("사건 ${outcome.events}")
        if (outcome.eventFieldValues > 0) parts.add("사건 필드값 ${outcome.eventFieldValues}")
        if (outcome.novelFieldValues > 0) parts.add("작품 필드값 ${outcome.novelFieldValues}")
        if (outcome.tags > 0) parts.add("태그 ${outcome.tags}")
        if (outcome.stateChanges > 0) parts.add("상태변화 ${outcome.stateChanges}")
        if (outcome.relationships > 0) parts.add("관계 ${outcome.relationships}")
        if (outcome.relationshipChanges > 0) parts.add("관계변화 ${outcome.relationshipChanges}")
        if (outcome.factions > 0) parts.add("세력 ${outcome.factions}")
        if (outcome.factionMemberships > 0) parts.add("세력 소속 ${outcome.factionMemberships}")
        if (outcome.factionRelationships > 0) parts.add("세력 관계 ${outcome.factionRelationships}")
        if (outcome.libraryEntries > 0) parts.add("값 라이브러리 ${outcome.libraryEntries}")
        val nameBankTotal = outcome.nameBankNew + outcome.nameBankLinked
        if (nameBankTotal > 0) parts.add("이름 은행 $nameBankTotal")
        if (outcome.restoredImages > 0) parts.add("이미지 ${outcome.restoredImages}")

        val summary = appContext.getString(
            com.novelcharacter.app.R.string.world_package_import_done,
            outcome.universeName,
            if (parts.isEmpty()) appContext.getString(com.novelcharacter.app.R.string.import_result_empty)
            else parts.joinToString(", ")
        )
        val act = currentActivityRef?.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            Toast.makeText(appContext, summary, Toast.LENGTH_LONG).show()
            return
        }
        val message = if (outcome.warnings.isEmpty()) summary else "$summary\n\n⚠ ${outcome.warnings.size}건 안내"
        val builder = MaterialAlertDialogBuilder(act)
            .setTitle(appContext.getString(com.novelcharacter.app.R.string.world_package_import_title))
            .setMessage(message)
        if (outcome.warnings.isNotEmpty()) {
            builder.setNeutralButton(appContext.getString(com.novelcharacter.app.R.string.import_result_details)) { _, _ ->
                showWorldWarningsDialog(act, outcome.warnings)
            }
        }
        builder.setPositiveButton(appContext.getString(com.novelcharacter.app.R.string.confirm), null)
        builder.show()
    }

    private fun showWorldWarningsDialog(act: android.app.Activity, warnings: List<String>) {
        if (act.isFinishing || act.isDestroyed) return
        // 안내 줄은 경고 수만큼 늘어난다 — 상한 없이는 긴 목록이 잘린다(B-91).
        val scrollView = cappedScrollView(act)
        val textView = TextView(act).apply {
            text = warnings.joinToString("\n") { "• $it" }
            val dp16 = (16 * act.resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, dp16)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)
        MaterialAlertDialogBuilder(act)
            .setTitle(appContext.getString(com.novelcharacter.app.R.string.import_result_details))
            .setView(scrollView)
            .setPositiveButton(appContext.getString(com.novelcharacter.app.R.string.confirm), null)
            .show()
    }

    // ── ZIP에서 가져오기 ──

    private suspend fun importFromZip(zipTempFile: File, progress: TaskProgressDialog.Handle? = null) {
        val extractDir = File(appContext.cacheDir, "import_extract_${System.currentTimeMillis()}")
        // 해제 구간의 취소는 안전하다 — 산출물이 전부 [extractDir] 안에 있고 아래 finally가
        // 통째로 지운다. 아직 DB에도 filesDir에도 쓴 것이 없다.
        var extractCancelled = false
        try {
            extractDir.mkdirs()

            // ZIP 해제
            var xlsxFile: File? = null
            var imageMapJson: String? = null
            val hasImages: Boolean

            // 해제 구간의 진행 창 — 복사 구간에서 이어받은 것이 있으면 그대로 쓴다(창을 다시
            // 띄우면 한 번의 가져오기에 창이 두 번 깜빡인다). 없으면(백업 복원처럼 복사 없이
            // 들어온 경로) 여기서 연다.
            val extractStage = appContext.getString(com.novelcharacter.app.R.string.import_progress_stage_extract)
            val entryTotal = ZipFile(zipTempFile).use { it.size() }
            val extractProgress = progress ?: showTaskProgress(
                total = entryTotal,
                stageRes = com.novelcharacter.app.R.string.import_progress_stage_extract
            ) { extractCancelled = true }
            postProgress(extractProgress, 0, entryTotal, extractStage)

            // ZIP bomb 방어는 전체 파일 크기가 아니라 해제 지점에서 엔트리별로 수행한다
            // (엔트리 헤더의 크기 선언은 신뢰할 수 없으므로 실제 해제 바이트를 계수)
            ZipFile(zipTempFile).use { zip ->
                var imageCount = 0
                var imageTotalBytes = 0L
                var processed = 0
                for (entry in zip.entries()) {
                    // 이어받은 창은 취소 버튼이 복사 구간에서 붙은 것이라, 눌린 사실은 창이 들고
                    // 있다([Handle.isCancelled]). 새로 연 창은 위 람다가 플래그를 세운다.
                    if (extractCancelled || extractProgress?.isCancelled == true) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, com.novelcharacter.app.R.string.import_cancelled, Toast.LENGTH_SHORT).show()
                        }
                        return
                    }
                    processed++
                    postProgress(extractProgress, processed, entryTotal, extractStage)
                    if (entry.isDirectory) continue
                    when {
                        entry.name == "data.xlsx" -> {
                            val target = File(extractDir, "data.xlsx")
                            xlsxFile = target
                            // 이 상한은 **파서 한계가 아니라 zip-bomb·디스크 방어**다 (B-8).
                            // 파싱 쪽 한계는 스트리밍 경로가 없앴으므로, 앱이 만든 큰 백업이
                            // 여기서 걸려 되돌아오지 못하는 일이 없도록 넉넉히 잡는다.
                            val copied = zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output -> copyWithLimit(input, output, MAX_EXTRACTED_XLSX_SIZE) }
                            }
                            if (copied > MAX_EXTRACTED_XLSX_SIZE) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_xlsx_too_large, Toast.LENGTH_LONG).show()
                                }
                                return
                            }
                        }
                        entry.name == "image_map.json" -> {
                            val bos = java.io.ByteArrayOutputStream()
                            val copied = zip.getInputStream(entry).use { input -> copyWithLimit(input, bos, MAX_IMAGE_MAP_SIZE) }
                            if (copied > MAX_IMAGE_MAP_SIZE) {
                                // 비정상적으로 큰 매핑 파일은 무시 (이미지 재매핑 없이 데이터만 가져오기)
                                Log.w("ExcelImporter", "image_map.json exceeds limit, ignoring")
                                imageMapJson = null
                            } else {
                                imageMapJson = bos.toString(Charsets.UTF_8.name())
                            }
                        }
                        entry.name.startsWith("images/") -> {
                            if (imageCount >= MAX_IMAGE_ENTRY_COUNT) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_too_many_images, Toast.LENGTH_LONG).show()
                                }
                                return
                            }
                            val imageFile = File(extractDir, entry.name)
                            if (!imageFile.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                                Log.w("ExcelImporter", "Skipping suspicious zip entry: ${entry.name}")
                                continue
                            }
                            imageFile.parentFile?.mkdirs()
                            val copied = zip.getInputStream(entry).use { input ->
                                FileOutputStream(imageFile).use { output ->
                                    copyWithLimit(input, output, MAX_IMAGE_TOTAL_SIZE - imageTotalBytes)
                                }
                            }
                            imageTotalBytes += copied
                            if (imageTotalBytes > MAX_IMAGE_TOTAL_SIZE) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_images_too_large, Toast.LENGTH_LONG).show()
                                }
                                return
                            }
                            imageCount++
                        }
                    }
                }
                hasImages = imageCount > 0
            }

            if (xlsxFile?.exists() != true) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, com.novelcharacter.app.R.string.import_zip_no_data, Toast.LENGTH_LONG).show()
                }
                return
            }

            // 이미지 경로 재매핑 생성
            val imagePathRemap = buildImageRemap(imageMapJson, extractDir)

            // 묻는 창을 띄우기 전에 진행 창을 닫는다 — 겹치면 어느 쪽이 살아 있는지 알 수 없다.
            dismissTaskProgress(extractProgress)

            // 가져오기 옵션 다이얼로그 표시
            val options = showImportOptionsDialog(hasImages) ?: return

            // 선택 뒤의 두 구간(이미지 되살리기 · 분석)이 공유하는 창.
            //
            // **이 구간부터는 취소를 제공하지 않는다.** 되살린 이미지는 filesDir에 남는데 그것을
            // 가리킬 DB 행은 아직 안 들어왔으므로, 중간에 끊으면 참조 없는 파일이 남는다 —
            // R-26이 금지한 "조용한 반쪽 상태"다. 되돌리려면 이번에 만든 파일만 골라 지워야
            // 하는데, 재복원 멱등화(같은 경로로 매핑되는 기존 파일)와 섞여 있어 남의 이미지를
            // 지울 위험이 있다. 표시는 하되 끊지 않는 쪽이 안전하다.
            var stageProgress: TaskProgressDialog.Handle? = null
            try {
                // 이미지 복원: extractDir에서 filesDir로 복사
                pendingImageFailures = 0
                restoredImagePaths = emptyList()
                if (options.images && hasImages) {
                    // 총량은 재매핑 크기다 — restoreImages가 도는 대상이 정확히 그 집합이고
                    // (재매핑에 없는 원본은 건너뛴다), 실행과 같은 값을 쓰므로 갈리지 않는다.
                    val imagesStage = appContext.getString(com.novelcharacter.app.R.string.import_progress_stage_images)
                    stageProgress = showTaskProgress(
                        total = imagePathRemap.size,
                        stageRes = com.novelcharacter.app.R.string.import_progress_stage_images
                    )
                    val restored = restoreImages(imageMapJson, extractDir, imagePathRemap) { done, total ->
                        postProgress(stageProgress, done, total, imagesStage)
                    }
                    pendingImageFailures = restored.failedCount
                    // 가져오기가 실패하면 이 목록만 지운다(B-77) — DB는 한 트랜잭션이라 스스로 롤백된다.
                    restoredImagePaths = restored.createdPaths
                    // 복원은 원 파일이 없을 때 새 UUID로 자리를 잡는다 = 정리 폴더 토큰이 끊긴다.
                    // 별칭을 남겨 다른 기기에서 내보낸 사본도 같은 이미지로 이어지게 한다(C-1).
                    runCatching {
                        com.novelcharacter.app.util.FolderRoundtripPrefs.recordRenames(
                            appContext,
                            imagePathRemap.filter { (old, new) -> old != new }
                        )
                    }
                }

                // xlsx 가져오기
                val verifiedXlsx = xlsxFile ?: return  // already checked above
                importService.imagePathRemap = if (options.images) imagePathRemap else emptyMap()
                importFromXlsx(verifiedXlsx, options, stageProgress)
                importService.imagePathRemap = emptyMap()
            } finally {
                dismissTaskProgress(stageProgress)
            }

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
            // 같은 기기 재복원 멱등화: 원경로 파일이 이미 있으면 자기 자신으로 매핑한다
            // (파일명이 UUID라 경로 일치 = 같은 파일). 새 UUID 발급 시 복원마다 파일·meta가
            // 복제되고 링크 그룹이 기존 멤버 합산으로 팽창하던 문제 차단. restoreImages는
            // destFile.exists()로 복사를 건너뛴다.
            if (runCatching { File(originalPath).exists() }.getOrDefault(false)) {
                remap[originalPath] = originalPath
                continue
            }
            // 원 파일명의 프리픽스·확장자 보존 — 복원 후에도 앱관리 이미지 분류(img_=라이브러리 임포트 등)와
            // 파일 형식 표기가 유지된다 ("char_" 고정 하드코딩이던 것을 교정, G3)
            val origName = File(originalPath).name
            val prefix = com.novelcharacter.app.util.StorageAnalyzer.IMAGE_PREFIXES
                .firstOrNull { origName.startsWith(it) } ?: "char_"
            val ext = origName.substringAfterLast('.', "").ifBlank { "jpg" }
            val newFileName = "$prefix${UUID.randomUUID()}.$ext"
            val newPath = File(appDir, newFileName).absolutePath
            remap[originalPath] = newPath
        }
        return remap
    }

    /**
     * @return 복원 실패한 이미지 수
     */
    /**
     * 해제된 이미지를 filesDir로 되살린다.
     *
     * [onProgress]는 (처리한 장, 전체 장)을 받는다 — 전체는 [imagePathRemap]의 크기다.
     * 이 루프가 도는 대상이 정확히 그 집합이라(재매핑에 없는 원본은 건너뛴다) 총량과 실행이
     * 갈리지 않는다(R-26: 따로 세면 막대가 100%를 넘거나 못 미친 채 끝난다).
     */
    private fun restoreImages(
        imageMapJson: String?, extractDir: File, imagePathRemap: Map<String, String>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): RestoreImagesOutcome {
        if (imageMapJson == null) return RestoreImagesOutcome()
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        val originalMap: Map<String, String> = try {
            gson.fromJson(imageMapJson, type)
        } catch (_: Exception) { return RestoreImagesOutcome() }

        val total = imagePathRemap.size
        var processed = 0
        var failedCount = 0
        val created = mutableListOf<String>()
        for ((originalPath, zipRelPath) in originalMap) {
            val newPath = imagePathRemap[originalPath] ?: continue
            processed++
            onProgress?.invoke(processed, total)
            val extractedFile = File(extractDir, zipRelPath)
            if (!extractedFile.exists()) { failedCount++; continue }
            val destFile = File(newPath)
            // 이미 있는 목적지는 건드리지 않는다 — 그래서 아래 copyTo에 닿는 것은
            // **전부 이번에 새로 만드는 파일**이고, 되돌릴 때 남의 이미지를 지울 일이 없다(B-77).
            if (destFile.exists()) continue
            try {
                extractedFile.copyTo(destFile)
                created.add(newPath)
            } catch (_: Exception) { failedCount++ }
        }
        return RestoreImagesOutcome(failedCount, created)
    }

    /**
     * [restoreImages]의 결과.
     *
     * @param createdPaths **이번에 새로 만든** 파일만. 이미 있던 목적지는 들어오지 않으므로
     *   실패 되돌리기가 기존 이미지를 건드리지 않는다(B-77 처방 ②).
     */
    private data class RestoreImagesOutcome(
        val failedCount: Int = 0,
        val createdPaths: List<String> = emptyList()
    )

    /**
     * 가져오기가 실패했을 때 **이번에 만든 이미지 파일만** 지운다 (B-77 처방 ②).
     *
     * `importAll`은 전체가 한 트랜잭션이라 DB는 스스로 롤백된다 — 트랜잭션 밖에서 먼저 복사된
     * 이 파일들만 남으므로 여기서 짝을 맞춘다. 파일 삭제가 실패해도 조용히 넘긴다:
     * 이미 실패를 알리는 중이고, 남은 파일은 다음 가져오기가 같은 경로로 덮어쓴다.
     *
     * 이름 별칭(`FolderRoundtripPrefs.recordRenames`)은 되돌리지 않는다 — 다시 시도하면
     * 같은 상태에서 같은 재매핑이 나와 그 별칭이 그대로 유효하다.
     */
    private fun rollbackRestoredImages() {
        val paths = restoredImagePaths
        restoredImagePaths = emptyList()
        if (paths.isEmpty()) return
        var deleted = 0
        for (p in paths) {
            if (runCatching { File(p).delete() }.getOrDefault(false)) deleted++
        }
        Log.i("ExcelImporter", "import failed — rolled back $deleted/${paths.size} restored images")
    }

    // ── XLSX에서 가져오기 ──

    /**
     * 가져오기 원본을 연다 — 파일 크기에 따라 DOM/스트리밍을 고른다 (B-8 / 색출 로드맵 6).
     *
     * **경로가 둘이어도 해석은 하나다.** 두 구현 모두 [ImportWorkbook]이고, 값 정규화는
     * [ExcelCellValue] 하나를 탄다(`ImportSource.kt` 참조). 그래서 어느 경로로 읽든 같은
     * 파일은 같은 값이 된다 — 왕복 무결성이 구조적으로 보존된다.
     *
     * **DOM을 남겨 둔 이유:** 검증된 기준을 버리지 않기 위해서다. 작은 파일은 종전 그대로
     * 돌고, 스트리밍은 종전이라면 **거부되거나 OOM으로 죽던** 크기에서만 켜진다.
     */
    private fun openImportSource(xlsxFile: File): OpenedImportSource {
        val budget = domParseBudgetBytes()
        if (xlsxFile.length() > budget) {
            Log.i("ExcelImporter", "streaming import: ${xlsxFile.length()} bytes > budget $budget")
            val wb = StreamingImportWorkbook(xlsxFile)
            return OpenedImportSource(wb, closer = wb)
        }
        return try {
            val poi = xlsxFile.inputStream().use { WorkbookFactory.create(it) }
            OpenedImportSource(DomImportWorkbook(poi), closer = poi)
        } catch (oom: OutOfMemoryError) {
            // 예산 안이라 DOM으로 갔는데도 이 기기 힙엔 벅찼다 — 조용히 죽는 대신 스트리밍으로 물러선다.
            // **여는 단계에서만** 폴백한다: 이 시점엔 DB에 쓴 것이 없어 다시 시작해도 안전하다
            // (importAll 도중의 OOM을 여기서 되잡으면 부분 반영 위에 두 번 쓰게 된다).
            Log.w("ExcelImporter", "DOM open OOM — falling back to streaming", oom)
            val wb = StreamingImportWorkbook(xlsxFile)
            OpenedImportSource(wb, closer = wb)
        }
    }

    /**
     * DOM(XSSFWorkbook)으로 열어도 좋은 파일 크기 상한.
     *
     * XSSF DOM은 XMLBeans 객체 그래프를 모든 시트에 대해 동시에 들고 있어 실사용에서 파일
     * 크기의 **10~20배** 힙을 쓴다. 그래서 고정 상수가 아니라 이 기기의 힙에서 역산한다 —
     * 상수로 두면 힙이 작은 기기에서는 여전히 죽고, 큰 기기에서는 공연히 스트리밍으로 샌다.
     * 힙의 1/4을 DOM 파싱에 허용한다고 보고 12배를 나눈 값이며, 양끝은 상식선에서 자른다.
     */
    private fun domParseBudgetBytes(): Long {
        val maxHeap = Runtime.getRuntime().maxMemory()
        return (maxHeap / 4 / 12).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024)
    }

    /**
     * 연 원본과 그 자원. [ImportWorkbook]은 닫는 방법을 모르므로 닫개를 따로 들고 다닌다.
     *
     * 어느 경로로 열렸는지는 **사용자에게 알리지 않는다** — 두 경로의 결과가 같다는 것이
     * 이 설계의 전제이므로(설계 문서 "사용자에겐 투명, 임계값 자동"), 알릴 것이 있다면
     * 그것은 결함이지 정보가 아니다. 진단은 [openImportSource]의 로그가 남긴다.
     */
    private class OpenedImportSource(
        val source: ImportWorkbook,
        private val closer: java.io.Closeable
    ) : java.io.Closeable {
        override fun close() = closer.close()
    }

    private suspend fun importFromXlsx(
        xlsxFile: File,
        options: ExportOptions = ExportOptions(),
        progress: TaskProgressDialog.Handle? = null
    ) {
        var opened: OpenedImportSource? = null
        // 분석 구간의 창 — 앞 구간에서 이어받은 것이 있으면 그것을 그대로 쓴다.
        var stageProgress: TaskProgressDialog.Handle? = progress
        // 실패 고지가 "어디서 멈췄는가"를 말하려면 마지막 진행 지점이 catch에서도 보여야 한다(B-77 처방 ③).
        // **진행 보고는 각 단계가 '끝났을 때' 오므로 이것은 마지막으로 '끝낸' 단계다** —
        // 실패한 단계는 그다음이다. 문구도 그렇게 말한다(모르는 것을 아는 척하지 않는다).
        var lastDonePhase = ""
        var lastDoneRows = 0

        try {
            org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.01)
            // 압축비(setMinInflateRatio)로 zip-bomb를 막고, 엔트리 크기 상한은 POI 허용 최댓값까지 열어
            // 대형 백업의 압축 해제된 시트 XML이 상한에 걸려 거부되지 않게 한다.
            org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(POI_MAX_ENTRY_SIZE)

            opened = openImportSource(xlsxFile)
            val workbook = opened.source

            // Phase 1: 분석 구간의 진행 창(작업형 · R-26).
            //
            // 총량(행 수)은 [ExcelImportService.analyzeAll]이 세므로 **첫 보고가 와야 안다**.
            // 그때까지는 총량 0 = 불확정으로 두고, 첫 보고에서 막대가 확정으로 바뀐다 —
            // 모르는 동안 퍼센트를 지어내지 않는다(R-26 후단).
            val analyzeStage = appContext.getString(com.novelcharacter.app.R.string.import_progress_stage_analyze)
            if (stageProgress == null) {
                stageProgress = showTaskProgress(
                    total = 0,
                    stageRes = com.novelcharacter.app.R.string.import_progress_stage_analyze
                )
            } else {
                postProgress(stageProgress, 0, 0, analyzeStage)
            }
            val analyzeWindow = stageProgress

            // Phase 2: 백업 내용 분석
            val analysis = importService.analyzeAll(workbook, options) { p ->
                postProgress(
                    analyzeWindow, p.processedRows, p.totalRows,
                    appContext.getString(
                        com.novelcharacter.app.R.string.import_progress_stage_detail,
                        analyzeStage, p.currentPhase
                    )
                )
            }

            // 묻는 창(미리보기·충돌 해결) 앞에서는 진행 창을 닫는다.
            dismissTaskProgress(stageProgress)
            stageProgress = null

            // Phase 3: 미리보기 + 전략 선택 다이얼로그
            val (strategy, deleteOpts) = showRestorePreviewDialog(analysis) ?: return
            val effectiveOptions = if (deleteOpts.hasAny) options.copy(deleteOptions = deleteOpts) else options

            // Phase 3.5: 동명이인 충돌 해결 다이얼로그
            // OVERWRITE 전략은 기존 데이터 전부 삭제 후 재삽입이므로 충돌 해결 불필요
            val resolvedConflicts: Map<String, CharacterConflict> = if (
                strategy != ImportStrategy.OVERWRITE && analysis.characterConflicts.isNotEmpty()
            ) {
                showConflictResolutionDialog(analysis.characterConflicts) ?: return
            } else {
                emptyMap()
            }

            // Phase 4: 실제 가져오기 진행.
            //
            // **이 구간은 취소를 제공하지 않는다.** 전략(덮어쓰기·병합)이 여러 표에 걸쳐 반영되므로
            // 중간에 끊으면 반쯤 덮인 DB가 남는다 — 항목 단위로 완결되지 않는 작업이라
            // R-26이 취소를 요구하지 않는 자리다(반쪽 상태 금지가 우선한다).
            val applyStage = appContext.getString(com.novelcharacter.app.R.string.import_progress_stage_apply)
            stageProgress = showTaskProgress(
                total = 0,
                stageRes = com.novelcharacter.app.R.string.import_progress_stage_apply
            )
            val applyWindow = stageProgress

            // 진행 콜백이 이미 시트 이름과 행 수를 나르므로 따로 세지 않는다 — 따로 세면 갈린다.
            val result = importService.importAll(workbook, effectiveOptions, strategy, resolvedConflicts) { p ->
                lastDonePhase = p.currentPhase
                lastDoneRows = p.processedRows
                postProgress(
                    applyWindow, p.processedRows, p.totalRows,
                    appContext.getString(
                        com.novelcharacter.app.R.string.import_progress_stage_detail,
                        applyStage, p.currentPhase
                    )
                )
            }

            if (pendingImageFailures > 0) {
                result.warnings.add("${pendingImageFailures}개 이미지 복원 실패")
                pendingImageFailures = 0
            }
            // 여기까지 왔으면 트랜잭션이 커밋됐다 — 되살린 이미지는 이제 가리킬 행이 있다.
            restoredImagePaths = emptyList()

            val message = buildResultMessage(result)
            withContext(Dispatchers.Main) {
                stageProgress?.dismiss()
                showResultDialog(result, message)
            }
        } catch (e: Exception) {
            android.util.Log.e("ExcelImporter", "Import failed", e)
            rollbackRestoredImages()
            withContext(Dispatchers.Main) {
                stageProgress?.dismiss()
                showImportFailedDialog(lastDonePhase, lastDoneRows)
            }
        } catch (oom: OutOfMemoryError) {
            // 여는 단계는 openImportSource가 스트리밍으로 물러서므로, 여기 오는 것은 그 뒤(분석·반영)의
            // OOM이다. 그 지점은 이미 DB에 쓰고 있을 수 있어 자동 재시도가 위험하다 —
            // Error는 위 Exception catch로 안 잡히므로 여기서 받아 조용한 크래시 대신 원인을 알린다(변수 제어).
            try { opened?.close() } catch (_: Exception) {}
            opened = null
            android.util.Log.e("ExcelImporter", "Import out of memory", oom)
            // OOM도 트랜잭션은 롤백된다 — 트랜잭션 밖에서 만든 이미지 파일만 남으므로 함께 지운다(B-77).
            rollbackRestoredImages()
            withContext(Dispatchers.Main) {
                stageProgress?.dismiss()
                Toast.makeText(appContext, com.novelcharacter.app.R.string.import_oom, Toast.LENGTH_LONG).show()
            }
        } finally {
            // 어느 갈래로 빠져나가든 진행 창은 닫힌다 — 위 catch들이 이미 닫았어도 무해하다.
            withContext(kotlinx.coroutines.NonCancellable) { dismissTaskProgress(stageProgress) }
            try { opened?.close() } catch (e: Exception) { android.util.Log.w("ExcelImporter", "Failed to close workbook", e) }
        }
    }

    // ── 복원 미리보기 + 전략 선택 다이얼로그 ──

    /** @return Pair(strategy, deleteOptions) or null if cancelled */
    private suspend fun showRestorePreviewDialog(analysis: RestoreAnalysis): Pair<ImportStrategy, DeleteOptions>? {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return Pair(ImportStrategy.MERGE, DeleteOptions())
        }

        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val act = currentActivityRef?.get()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    cont.resume(Pair(ImportStrategy.MERGE, DeleteOptions()), null)
                    return@suspendCancellableCoroutine
                }

                val dp = act.resources.displayMetrics.density
                val dp8 = (8 * dp).toInt()
                val dp16 = (16 * dp).toInt()

                // 범주 블록이 파일에 담긴 범주 수만큼 쌓이고 삭제 옵션까지 붙는다 —
                // 상한 없이는 아래쪽 옵션과 버튼이 화면 밖으로 밀린다(B-91).
                val scrollView = cappedScrollView(act)
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

                    // B-102 ⓑ: 실행되지 않을 행은 '신규'가 아니라 '건너뜀'이다.
                    // "신규 N건"이라 예고해 놓고 아무것도 넣지 않으면 미리보기가 거짓말이 된다.
                    fun addSkippedLine() {
                        if (cat.skippedCount <= 0) return
                        catLayout.addView(TextView(act).apply {
                            text = appContext.getString(
                                com.novelcharacter.app.R.string.restore_preview_skipped, cat.skippedCount)
                            textSize = 11f
                            setPadding(dp8, 0, 0, 0)
                        })
                    }

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
                    addSkippedLine()

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

                container.addView(radioGroup)

                // MERGE 모드 항목별 삭제 옵션 (삭제 지원 카테고리만 표시)
                val deletableKeys = setOf("characters", "timeline", "stateChanges", "relationships",
                    "relationshipChanges", "nameBank", "factions", "factionMemberships", "factionRelationships")
                val deletableCats = analysis.categories.filter { it.onlyInDb > 0 && it.key in deletableKeys }
                val deleteSectionLabel = TextView(act).apply {
                    text = appContext.getString(com.novelcharacter.app.R.string.restore_merge_delete_option)
                    setTypeface(null, Typeface.BOLD)
                    textSize = 13f
                    setPadding(0, dp16, 0, dp8 / 2)
                    visibility = if (deletableCats.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
                container.addView(deleteSectionLabel)

                // 카테고리별 체크박스 생성
                data class DeleteCatCheckbox(val key: String, val checkbox: CheckBox)
                val deleteCatCheckboxes = mutableListOf<DeleteCatCheckbox>()
                val deleteContainer = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp16, 0, 0, 0)
                    visibility = if (deletableCats.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
                for (cat in deletableCats) {
                    val cb = CheckBox(act).apply {
                        text = "${cat.label} (${cat.onlyInDb}개)"
                        textSize = 13f
                    }
                    deleteContainer.addView(cb)
                    deleteCatCheckboxes.add(DeleteCatCheckbox(cat.key, cb))
                }
                container.addView(deleteContainer)

                // OVERWRITE 선택 시 삭제 옵션 숨김
                radioGroup.setOnCheckedChangeListener { _, checkedId ->
                    overwriteWarning.visibility = if (checkedId == overwriteRadio.id && totalOnlyInDb > 0) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                    val showDelete = checkedId == mergeRadio.id && deletableCats.isNotEmpty()
                    deleteSectionLabel.visibility = if (showDelete) android.view.View.VISIBLE else android.view.View.GONE
                    deleteContainer.visibility = if (showDelete) android.view.View.VISIBLE else android.view.View.GONE
                }

                MaterialAlertDialogBuilder(act)
                    .setTitle(com.novelcharacter.app.R.string.restore_preview_title)
                    .setView(scrollView)
                    .setPositiveButton(com.novelcharacter.app.R.string.restore_action) { _, _ ->
                        val strategy = if (overwriteRadio.isChecked) ImportStrategy.OVERWRITE else ImportStrategy.MERGE
                        // 카테고리별 삭제 옵션 수집
                        val deleteOpts = if (mergeRadio.isChecked) {
                            val checked = deleteCatCheckboxes.filter { it.checkbox.isChecked }.map { it.key }.toSet()
                            DeleteOptions(
                                characters = "characters" in checked,
                                timeline = "timeline" in checked,
                                stateChanges = "stateChanges" in checked,
                                relationships = "relationships" in checked,
                                relationshipChanges = "relationshipChanges" in checked,
                                nameBank = "nameBank" in checked,
                                factions = "factions" in checked,
                                factionMemberships = "factionMemberships" in checked,
                                factionRelationships = "factionRelationships" in checked
                            )
                        } else DeleteOptions()
                        cont.resume(Pair(strategy, deleteOpts), null)
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

                // 충돌 블록이 이름 충돌 건수만큼 쌓인다 — 상한 없이는 뒤쪽 선택지가 잘린다(B-91).
                val scrollView = cappedScrollView(act).apply {
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
                        if (existingUniverseName != null
                            && existingUniverseName != importingSheetName
                            && importingSheetName != UNCLASSIFIED_SHEET_NAME) {
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

                MaterialAlertDialogBuilder(act)
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
                val checked = ExportOptions.ALL.toBooleanArray()
                // 이미지 옵션: ZIP에 이미지가 있을 때만 기본 선택
                checked[checked.size - 1] = hasImages

                // 항목 선택 창은 [ExportOptionsDialog]가 단일 소스 — 내보내기와 **같은 창**이라
                // '전부 선택/해제'가 양쪽에 함께 붙는다(종전엔 내보내기에만 있었다).
                // isActive 가드: 버튼과 취소 리스너는 서로 배타적이지만, 두 번 resume 되면
                // 크래시다. 값싼 가드로 그 가능성 자체를 없앤다.
                ExportOptionsDialog.showForImport(
                    context = activity,
                    initial = checked,
                    onConfirm = { if (cont.isActive) cont.resume(it, null) },
                    onCancel = { if (cont.isActive) cont.resume(null, null) }
                )
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
        val gsTotal = result.newGradeSystems + result.updatedGradeSystems
        if (gsTotal > 0) parts.add("등급 체계 ${gsTotal}건")
        if (result.deletedGradeSystems > 0) parts.add("등급 체계 삭제 ${result.deletedGradeSystems}건")
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
        val lpTotal = result.newListPresets + result.updatedListPresets
        if (lpTotal > 0) parts.add("목록 프리셋 ${lpTotal}건")
        val frTotal = result.newFactionRelationships + result.updatedFactionRelationships
        if (frTotal > 0) parts.add("세력 관계 ${frTotal}건")
        val imTotal = result.newImageMeta + result.updatedImageMeta
        if (imTotal > 0) parts.add("이미지 태그·링크 ${imTotal}건")
        val fvTotal = result.newFieldValueEntries + result.updatedFieldValueEntries
        if (fvTotal > 0) parts.add("필드 데이터 ${fvTotal}건")
        if (result.restoredSettings > 0) parts.add(r.getString(com.novelcharacter.app.R.string.import_result_settings, result.restoredSettings))
        if (result.skippedRows > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_skipped, result.skippedRows))
        if (result.nameBasedMappings > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_name_mappings, result.nameBasedMappings))
        if (result.newCodesGenerated > 0)
            parts.add(r.getString(com.novelcharacter.app.R.string.import_result_new_codes, result.newCodesGenerated))

        // 삭제 건수 요약
        val totalDeleted = result.deletedCharacters + result.deletedRelationships + result.deletedEvents +
            result.deletedStateChanges + result.deletedRelationshipChanges + result.deletedNameBank +
            result.deletedFields + result.deletedFactions + result.deletedFactionMemberships +
            result.deletedFactionRelationships
        if (totalDeleted > 0) {
            val delParts = mutableListOf<String>()
            if (result.deletedCharacters > 0) delParts.add("캐릭터 ${result.deletedCharacters}")
            if (result.deletedEvents > 0) delParts.add("사건 ${result.deletedEvents}")
            if (result.deletedRelationships > 0) delParts.add("관계 ${result.deletedRelationships}")
            if (result.deletedStateChanges > 0) delParts.add("상태변화 ${result.deletedStateChanges}")
            if (result.deletedRelationshipChanges > 0) delParts.add("관계변화 ${result.deletedRelationshipChanges}")
            if (result.deletedNameBank > 0) delParts.add("이름 ${result.deletedNameBank}")
            if (result.deletedFactions > 0) delParts.add("세력 ${result.deletedFactions}")
            if (result.deletedFactionMemberships > 0) delParts.add("세력소속 ${result.deletedFactionMemberships}")
            if (result.deletedFactionRelationships > 0) delParts.add("세력관계 ${result.deletedFactionRelationships}")
            parts.add("🗑 삭제: ${delParts.joinToString(", ")}")
        }

        // 빈 셀로 비워진 값 요약 (F1-A 규칙 가: 열은 있으나 셀이 비어 삭제된 필드/태그)
        if (result.clearedFields > 0) {
            parts.add("🧹 빈 셀로 지워진 값 ${result.clearedFields}건")
        }

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
        val builder = MaterialAlertDialogBuilder(act)
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

    /**
     * 가져오기가 도중에 죽었을 때의 고지 (B-77 처방 ③).
     *
     * **토스트가 아니라 창인 이유:** 여기서 말할 것이 둘인데(전부 아니면 전무라는 사실 + 고칠 자리)
     * 사라지는 문구로는 "어느 시트 몇 행에서 멈췄는가"가 남지 않는다.
     *
     * 반영분이 없다고 단언할 수 있는 근거는 [ExcelImportService]가 가져오기 **전체를 한
     * 트랜잭션으로** 감싸기 때문이다 — 도중에 죽으면 DB는 통째로 롤백된다.
     * 트랜잭션 밖에서 만든 이미지 파일은 [rollbackRestoredImages]가 이미 지웠다.
     */
    private fun showImportFailedDialog(lastDonePhase: String, lastDoneRows: Int) {
        // [lastDonePhase]는 마지막으로 **끝낸** 단계다(진행 보고가 단계 종료 시점에 온다).
        // 누적 행 수도 파일 전체 기준이라 "그 시트의 몇 번째 행"이 아니다 — 문구가 그대로 말한다.
        val where = if (lastDonePhase.isBlank()) {
            appContext.getString(com.novelcharacter.app.R.string.import_failed_where_unknown)
        } else {
            appContext.getString(com.novelcharacter.app.R.string.import_failed_where, lastDonePhase, lastDoneRows)
        }
        val body = appContext.getString(com.novelcharacter.app.R.string.import_failed_nothing_applied, where)
        val act = currentActivityRef?.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            Toast.makeText(appContext, body, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(appContext.getString(com.novelcharacter.app.R.string.import_failed_title))
            .setMessage(body)
            .setPositiveButton(appContext.getString(com.novelcharacter.app.R.string.confirm), null)
            .show()
    }

    private fun showErrorDetailDialog(act: android.app.Activity, result: ImportResult) {
        if (act.isFinishing || act.isDestroyed) return

        val sb = StringBuilder()
        val maxDetailItems = 30
        val maxShownItems = 20
        if (result.errors.isNotEmpty()) {
            sb.appendLine("── 오류 (${result.errors.size}건) ──")
            if (result.errors.size <= maxDetailItems) {
                result.errors.forEachIndexed { i, err ->
                    sb.appendLine("${i + 1}. $err")
                }
            } else {
                result.errors.take(maxShownItems).forEachIndexed { i, err ->
                    sb.appendLine("${i + 1}. $err")
                }
                sb.appendLine("... 외 ${result.errors.size - maxShownItems}건")
            }
        }
        if (result.warnings.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("── 경고 (${result.warnings.size}건) ──")
            if (result.warnings.size <= maxDetailItems) {
                result.warnings.forEach { sb.appendLine("• $it") }
            } else {
                result.warnings.take(maxShownItems).forEach { sb.appendLine("• $it") }
                sb.appendLine("... 외 ${result.warnings.size - maxShownItems}건")
            }
        }

        // 오류·경고를 각각 최대 30건까지 적으므로 60줄 넘는 본문이 나온다(B-91).
        val scrollView = cappedScrollView(act)
        val textView = TextView(act).apply {
            text = sb.toString()
            val dp16 = (16 * act.resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, dp16)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(act)
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
        // 왕복 무결성: 앱이 내보낸 백업은 크기와 무관하게 그 앱으로 다시 복원되어야 한다.
        //
        // **B-8 이후 상한의 뜻이 바뀌었다.** 종전의 128MB는 '메모리 바운드'였다 — POI가 data.xlsx를
        // 통째로 메모리에 올리므로 그 이상은 읽을 수 없었고, 그래서 앱이 만든 큰 백업을 앱이
        // 거부했다. 스트리밍 경로가 그 메모리 바운드를 없앴으므로(파일 크기가 아니라 가장 큰 시트
        // 하나에 비례), 파싱에는 더 이상 크기 상한이 없다. 남은 상한은 전부 '디스크/개수 바운드'이며
        // zip-bomb 방어의 몫이다.
        private const val MAX_EXTRACTED_XLSX_SIZE = 1024L * 1024 * 1024 // ZIP에서 꺼내는 data.xlsx 상한(디스크·zip-bomb 방어)
        private const val MAX_EXTERNAL_FILE_SIZE = 4L * 1024 * 1024 * 1024 // 외부/전체 파일 상한(스트리밍 복사 — 디스크 바운드, 4GB)
        private const val MAX_IMAGE_ENTRY_COUNT = 50000 // ZIP 이미지 엔트리 개수 상한
        private const val MAX_IMAGE_TOTAL_SIZE = 8L * 1024 * 1024 * 1024 // ZIP 이미지 총 해제 용량 상한 (8GB)
        private const val MAX_IMAGE_MAP_SIZE = 64L * 1024 * 1024 // image_map.json 상한
        // POI ZipSecureFile.setMaxEntrySize 허용 최댓값(0xFFFFFFFF ≈ 4GB−1). 이를 초과하면 IllegalArgumentException.
        private const val POI_MAX_ENTRY_SIZE = 4L * 1024 * 1024 * 1024 - 1
    }
}
