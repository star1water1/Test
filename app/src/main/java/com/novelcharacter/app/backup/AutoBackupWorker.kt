package com.novelcharacter.app.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.excel.ExcelExporter
import com.novelcharacter.app.excel.ExportOptions
import com.novelcharacter.app.excel.ExportWorkbooks
import org.apache.poi.ss.usermodel.Workbook
import com.novelcharacter.app.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoBackupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val BACKUP_DIR_NAME = "backups"
        private const val BACKUP_PREFIX = "NovelCharacter_AutoBackup_"
        private const val BACKUP_EXTENSION = ".enc"

        /**
         * 주기 예약의 유니크 이름. **수동 격발은 이 이름을 쓰지 않는다** —
         * 같은 이름공간에 넣으면 요청이 조용히 무시되거나 주기 예약이 취소될 수 있다.
         */
        const val PERIODIC_WORK_NAME = "auto_backup"

        /** '지금 백업'(설계 D2)의 유니크 이름 — 주기와 별도 이름공간이다. */
        const val MANUAL_WORK_NAME = "manual_backup"

        /**
         * 완료되지 않은 임시 파일로 볼 나이(밀리초).
         *
         * 종전에는 `backup_*` 임시 파일을 **전부** 지웠다. 주기와 수동이 겹칠 수 있게 된 지금
         * 그것은 **실행 중인 다른 인스턴스의 작업 파일을 태우는** 일이다(설계 D2 무해화 ①).
         * 백업 하나가 이 시간을 넘겨 걸리는 일은 없다고 보고, 넘겼다면 그것은 중단된 잔해다.
         */
        const val ORPHAN_TEMP_AGE_MS = 6L * 60L * 60L * 1000L // 6시간

        /** 수동 격발 요청 — 제약 없음(즉시성이 이 버튼의 존재 이유다). 이미 돌고 있으면 KEEP이 무시한다. */
        fun enqueueManual(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<AutoBackupWorker>().build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    /**
     * 취소 창구(B-96) — 보고는 하지 않고 **stop만 잇는다.**
     *
     * 백그라운드라 띄울 막대가 없으므로 `onSheets`·`onImages`는 비어 있다. 이 싱크의 존재
     * 이유는 `isCancelled` 하나다: WorkManager가 실행 시간 초과로 워커를 stop해도
     * 종전에는 **이미지 루프가 그것을 보지 못해** 끝까지 돌았고(`copyTo`는 블로킹 I/O라
     * 코루틴 취소로도 멈추지 않는다), 그동안 시작된 재시도 인스턴스와 **둘이 함께 돌았다**
     * (워크북 2개 = 메모리 2배). 2026.08.02 사용자 기기의 `ENOENT` 실패가 그 결과였다.
     */
    private fun cancellationSink() = com.novelcharacter.app.excel.ExportProgressSink(
        onSheets = { _, _ -> },
        onImages = { _, _ -> },
        isCancelled = { isStopped }
    )

    override suspend fun doWork(): Result {
        val statusStore = BackupStatusStore(appContext)
        val settings = BackupSettingsStore(appContext).getSettings()
        val progress = cancellationSink()
        return try {
            Log.i(TAG, "Starting auto backup...")
            // 스트리밍 워크북 — 이 경로가 B-72가 말한 **마지막 방어선**이다: 배경에서 도는
            // 백업이 워크북 전량을 메모리에 세우면 저사양 기기에서 죽고, 그 실패는 사용자가
            // 보지 못한 자리에서 일어난다. 임시 파일 자리를 먼저 못박는다.
            ExportWorkbooks.useTempDirectory(appContext.cacheDir)
            val workbook = ExportWorkbooks.create(streaming = true)
            val imageReport: com.novelcharacter.app.excel.ImageZipReport
            val truncatedCells: Int
            try {
                // 공유·저장 내보내기와 동일한 단일 소스(ExcelExporter)를 재사용한다.
                // 별도 export 로직을 두면 포맷이 드리프트(세력관계 시트·사건 코드·커스텀 필드·
                // 관련캐릭터코드 누락, 32,767자 미가드)하여 복원 시 데이터가 유실되므로,
                // 자동 백업도 반드시 이 경로로 워크북을 생성한다. (엑셀 왕복 무결성)
                // 반환값(잘린 셀 수)을 버리지 않는다 — 수동 내보내기는 토스트·이력으로 알리는데
                // 마지막 방어선인 자동 백업만 무기록이면, 그 백업만 믿고 복원했을 때 잘린
                // 데이터가 무음으로 확정된다.
                truncatedCells = ExcelExporter(appContext).populateWorkbook(workbook, ExportOptions(), progress)

                // Write workbook to bytes, encrypt, and save to internal storage
                imageReport = saveEncryptedBackup(workbook, settings.includeImages, progress)
            } finally {
                // 임시 파일까지 함께 놓는다 — 배경 경로라 남은 것을 아무도 알아채지 못한다.
                try { ExportWorkbooks.release(workbook) } catch (e: Exception) { Log.w(TAG, "Failed to close workbook", e) }
            }

            // Rotate old backups (non-critical: rotation failure must not fail a completed backup)
            try {
                rotateBackups(settings.maxBackups)
            } catch (e: Exception) {
                Log.w(TAG, "Backup rotation failed, will retry next time", e)
            }

            // 이미지가 빠진 백업을 완전한 백업으로 오인하지 않도록 상태·이력 양쪽에 남긴다.
            // (백그라운드라 Toast가 불가능하고 매 회차 시스템 알림은 소음이 되므로,
            //  설정 화면의 백업 상태 + 작업 이력으로 노출한다)
            val imageWarning = if (imageReport.hasLoss) {
                appContext.getString(
                    com.novelcharacter.app.R.string.backup_images_incomplete,
                    imageReport.referencedCount, imageReport.includedCount, imageReport.excludedCount
                )
            } else null
            // 잘림도 같은 경로로 남긴다 — 수동 내보내기의 export_cells_truncated와 같은 문구.
            val truncationWarning = if (truncatedCells > 0) {
                appContext.getString(com.novelcharacter.app.R.string.export_cells_truncated, truncatedCells)
            } else null
            val backupWarning = listOfNotNull(imageWarning, truncationWarning)
                .joinToString("\n").ifBlank { null }
            statusStore.recordSuccess(backupWarning)
            Log.i(TAG, "Auto backup completed successfully")
            logResult(com.novelcharacter.app.util.OpResult.success(
                com.novelcharacter.app.util.OpResult.CAT_BACKUP,
                appContext.getString(com.novelcharacter.app.R.string.backup_result_auto_success)
                    + (imageWarning?.let { " — $it" } ?: ""),
                backupWarning
            ))
            Result.success()
        } catch (e: Exception) {
            // 취소(stop)는 실패가 아니다 — 산출물이 없으므로 기록할 실패도 없다(B-96).
            // `isStopped`도 함께 보는 이유: 취소 시점에 따라 접힌 예외가
            // ExportCancelledException이 아니라 그 뒤에 따라온 I/O 예외일 수 있다.
            val cancelled = e is com.novelcharacter.app.excel.ExportCancelledException || isStopped
            val outcome = com.novelcharacter.app.util.BackupWorkerPolicy.outcome(
                cancelled = cancelled,
                failed = true,
                runAttemptCount = runAttemptCount
            )
            if (cancelled) {
                Log.i(TAG, "Auto backup cancelled (stopped by WorkManager) — will run again next time")
            } else {
                Log.e(TAG, "Auto backup failed", e)
                AppLogger.error(TAG, "자동 백업 실패: ${e.message}", e)
            }
            if (outcome.recordsFailure) {
                statusStore.recordFailure(e.message ?: "Unknown error")
            }
            if (outcome.notifiesUser) {
                // 재시도 소진 — 최종 실패. 시스템 알림으로 능동 통지 + 이력 기록.
                com.novelcharacter.app.notification.NotificationHelper
                    .showBackupFailedNotification(appContext, e.message ?: "Unknown error")
                logResult(com.novelcharacter.app.util.OpResult.failure(
                    com.novelcharacter.app.util.OpResult.CAT_BACKUP,
                    appContext.getString(com.novelcharacter.app.R.string.backup_result_auto_failed),
                    detail = e.message
                ))
            }
            if (outcome.retries) Result.retry() else Result.failure()
        }
    }

    private fun logResult(result: com.novelcharacter.app.util.OpResult) {
        try {
            (appContext.applicationContext as? com.novelcharacter.app.NovelCharacterApp)
                ?.operationLogRepository?.logAsync(result)
        } catch (_: Exception) { /* 이력 기록 실패는 무시 */ }
    }

    private suspend fun saveEncryptedBackup(
        workbook: Workbook,
        includeImages: Boolean,
        progress: com.novelcharacter.app.excel.ExportProgressSink?
    ): com.novelcharacter.app.excel.ImageZipReport {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "$BACKUP_PREFIX$timestamp$BACKUP_EXTENSION"
        val backupFile = File(backupDir, fileName)

        val tempXlsx = File.createTempFile("backup_", ".xlsx", backupDir)
        val tempZip = File.createTempFile("backup_", ".zip", backupDir)
        // 암호화는 임시 이름에 쓰고 끝나야 최종 이름으로 옮긴다(설계 D2 무해화 ②).
        // 최종 이름에 직접 쓰면 **암호화 중인 부분 파일**을 '백업 내보내기'(최신 파일 선택)와
        // 복원 목록이 집을 수 있다. 종전에도 있던 창인데, 수동 버튼이 그것을 사용자 눈앞으로
        // 가져오므로 이번에 함께 닫는다.
        val tempEnc = File.createTempFile("backup_", ".enc.part", backupDir)
        try {
            // 1. XLSX 쓰기
            tempXlsx.outputStream().use { fos ->
                workbook.write(fos)
            }

            // 2. 이미지 포함 ZIP 래핑 (설정으로 제외 가능 — 용량 절약)
            val report = if (includeImages) {
                val db = AppDatabase.getDatabase(appContext)
                com.novelcharacter.app.excel.ImageZipHelper.wrapWithImages(
                    tempXlsx, tempZip, db, appContext, progress
                )
            } else {
                com.novelcharacter.app.excel.ImageZipReport.NOT_REQUESTED
            }

            // 3. 암호화 (이미지가 담겼으면 ZIP, 아니면 XLSX)
            val sourceFile = if (report.created) tempZip else tempXlsx
            BackupEncryptor.encryptFile(sourceFile, tempEnc)

            // 4. 완성된 것만 최종 이름으로 — 같은 파일 시스템 안이라 rename은 원자적이다
            if (!tempEnc.renameTo(backupFile)) {
                throw java.io.IOException("Failed to finalize backup file: ${backupFile.name}")
            }
            Log.i(TAG, "Encrypted backup saved (includeImages=$includeImages, " +
                "images=${report.includedCount}/${report.referencedCount}): ${backupFile.absolutePath}")
            return report
        } catch (e: Exception) {
            backupFile.delete()
            throw e
        } finally {
            tempXlsx.delete()
            tempZip.delete()
            tempEnc.delete()
        }
    }

    private fun rotateBackups(maxBackups: Int) {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) return

        // 중단된 이전 백업의 고아 임시 파일 청소 — **오래된 것만** 지운다(설계 D2 무해화 ①).
        // 조건 없이 지우면 지금 함께 돌고 있는 다른 인스턴스(주기 ↔ 수동)의 작업 파일을 태운다.
        val orphanCutoff = System.currentTimeMillis() - ORPHAN_TEMP_AGE_MS
        backupDir.listFiles { file ->
            file.name.startsWith("backup_") &&
                (file.name.endsWith(".xlsx") || file.name.endsWith(".zip") || file.name.endsWith(".enc.part")) &&
                file.lastModified() < orphanCutoff
        }?.forEach { it.delete() }

        val backupFiles = backupDir.listFiles { file ->
            file.name.startsWith(BACKUP_PREFIX) && file.name.endsWith(BACKUP_EXTENSION)
        }?.sortedByDescending { it.lastModified() } ?: return

        if (backupFiles.size > maxBackups) {
            backupFiles.drop(maxBackups).forEach { file ->
                if (file.delete()) {
                    Log.i(TAG, "Deleted old backup: ${file.name}")
                }
            }
        }
    }
}
