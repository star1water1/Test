package com.novelcharacter.app.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.excel.ExcelExporter
import com.novelcharacter.app.excel.ExportOptions
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
    }

    override suspend fun doWork(): Result {
        val statusStore = BackupStatusStore(appContext)
        val settings = BackupSettingsStore(appContext).getSettings()
        return try {
            Log.i(TAG, "Starting auto backup...")
            val workbook = XSSFWorkbook()
            try {
                // 공유·저장 내보내기와 동일한 단일 소스(ExcelExporter)를 재사용한다.
                // 별도 export 로직을 두면 포맷이 드리프트(세력관계 시트·사건 코드·커스텀 필드·
                // 관련캐릭터코드 누락, 32,767자 미가드)하여 복원 시 데이터가 유실되므로,
                // 자동 백업도 반드시 이 경로로 워크북을 생성한다. (엑셀 왕복 무결성)
                ExcelExporter(appContext).populateWorkbook(workbook, ExportOptions())

                // Write workbook to bytes, encrypt, and save to internal storage
                saveEncryptedBackup(workbook, settings.includeImages)
            } finally {
                try { workbook.close() } catch (e: Exception) { Log.w(TAG, "Failed to close workbook", e) }
            }

            // Rotate old backups (non-critical: rotation failure must not fail a completed backup)
            try {
                rotateBackups(settings.maxBackups)
            } catch (e: Exception) {
                Log.w(TAG, "Backup rotation failed, will retry next time", e)
            }

            statusStore.recordSuccess()
            Log.i(TAG, "Auto backup completed successfully")
            logResult(com.novelcharacter.app.util.OpResult.success(
                com.novelcharacter.app.util.OpResult.CAT_BACKUP,
                appContext.getString(com.novelcharacter.app.R.string.backup_result_auto_success)
            ))
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed", e)
            AppLogger.error(TAG, "자동 백업 실패: ${e.message}", e)
            statusStore.recordFailure(e.message ?: "Unknown error")
            val willRetry = runAttemptCount < 3
            if (!willRetry) {
                // 재시도 소진 — 최종 실패. 시스템 알림으로 능동 통지 + 이력 기록.
                com.novelcharacter.app.notification.NotificationHelper
                    .showBackupFailedNotification(appContext, e.message ?: "Unknown error")
                logResult(com.novelcharacter.app.util.OpResult.failure(
                    com.novelcharacter.app.util.OpResult.CAT_BACKUP,
                    appContext.getString(com.novelcharacter.app.R.string.backup_result_auto_failed),
                    detail = e.message
                ))
            }
            if (willRetry) Result.retry() else Result.failure()
        }
    }

    private fun logResult(result: com.novelcharacter.app.util.OpResult) {
        try {
            (appContext.applicationContext as? com.novelcharacter.app.NovelCharacterApp)
                ?.operationLogRepository?.logAsync(result)
        } catch (_: Exception) { /* 이력 기록 실패는 무시 */ }
    }

    private suspend fun saveEncryptedBackup(workbook: XSSFWorkbook, includeImages: Boolean) {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "$BACKUP_PREFIX$timestamp$BACKUP_EXTENSION"
        val backupFile = File(backupDir, fileName)

        val tempXlsx = File.createTempFile("backup_", ".xlsx", backupDir)
        val tempZip = File.createTempFile("backup_", ".zip", backupDir)
        try {
            // 1. XLSX 쓰기
            tempXlsx.outputStream().use { fos ->
                workbook.write(fos)
            }

            // 2. 이미지 포함 ZIP 래핑 (설정으로 제외 가능 — 용량 절약)
            val hasImages = if (includeImages) {
                val db = AppDatabase.getDatabase(appContext)
                com.novelcharacter.app.excel.ImageZipHelper.wrapWithImages(
                    tempXlsx, tempZip, db, appContext
                )
            } else {
                false
            }

            // 3. 암호화 (이미지가 있으면 ZIP, 없으면 XLSX)
            val sourceFile = if (hasImages) tempZip else tempXlsx
            BackupEncryptor.encryptFile(sourceFile, backupFile)
        } catch (e: Exception) {
            backupFile.delete()
            throw e
        } finally {
            tempXlsx.delete()
            tempZip.delete()
        }

        Log.i(TAG, "Encrypted backup saved (includeImages=$includeImages): ${backupFile.absolutePath}")
    }

    private fun rotateBackups(maxBackups: Int) {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) return

        // Clean up orphaned temp files from previous interrupted backups
        backupDir.listFiles { file ->
            file.name.startsWith("backup_") &&
                (file.name.endsWith(".xlsx") || file.name.endsWith(".zip"))
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
