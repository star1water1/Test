package com.novelcharacter.app

import android.app.Application
import com.novelcharacter.app.R
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.TimelineRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import com.novelcharacter.app.data.repository.NameBankRepository
import com.novelcharacter.app.data.repository.SearchPresetRepository
import com.novelcharacter.app.backup.AutoBackupWorker
import com.novelcharacter.app.backup.BackupEncryptor
import com.novelcharacter.app.backup.BackupStatusStore
import com.novelcharacter.app.notification.BirthdayWorker
import com.novelcharacter.app.util.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NovelCharacterApp : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val novelRepository by lazy { NovelRepository(database, database.novelDao()) }
    val characterRepository by lazy {
        CharacterRepository(
            database,
            database.characterDao(),
            database.characterFieldValueDao(),
            database.characterStateChangeDao(),
            database.characterTagDao(),
            database.characterRelationshipDao(),
            database.nameBankDao()
        )
    }
    val timelineRepository by lazy { TimelineRepository(database.timelineDao()) }
    val universeRepository by lazy {
        UniverseRepository(
            database,
            database.universeDao(),
            database.fieldDefinitionDao(),
            database.novelDao()
        )
    }
    val nameBankRepository by lazy { NameBankRepository(database.nameBankDao()) }
    val searchPresetRepository by lazy { SearchPresetRepository(database.searchPresetDao()) }
    val recentActivityDao by lazy { database.recentActivityDao() }
    val backupStatusStore by lazy { BackupStatusStore(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // Apply saved theme from SharedPreferences cache (non-blocking)
        ThemeHelper.applyTheme(ThemeHelper.getSavedTheme(this))
        // Migrate DataStore → SharedPreferences cache on first launch
        appScope.launch(Dispatchers.IO) {
            ThemeHelper.migrateCacheIfNeeded(this@NovelCharacterApp)
        }
        createNotificationChannel()
        checkBackupKeyAvailability()
        try {
            scheduleBirthdayCheck()
            scheduleAutoBackup()
        } catch (e: Exception) {
            android.util.Log.e("NovelCharacterApp", "Failed to schedule background workers", e)
        }
    }

    /**
     * 릴리스 빌드 크래시 디버깅용.
     * 크래시 발생 시 스택 트레이스를 /data/data/<pkg>/files/crash_log.txt 에 저장.
     * 설정 > 크래시 로그 확인 또는 파일 탐색기로 확인 가능.
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(filesDir, "crash_log.txt")
                logFile.writeText(buildString {
                    appendLine("=== Crash at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine(throwable.stackTraceToString())
                })
            } catch (_: Exception) {
                // 로그 저장 실패 시 무시
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            BIRTHDAY_CHANNEL_ID,
            getString(R.string.notification_channel_birthday_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_birthday_desc)
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(channel)
    }

    private fun scheduleBirthdayCheck() {
        val workRequest = PeriodicWorkRequestBuilder<BirthdayWorker>(
            1, TimeUnit.DAYS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "birthday_check",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun scheduleAutoBackup() {
        val workRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            1, TimeUnit.DAYS
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_backup",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun checkBackupKeyAvailability() {
        val backupDir = java.io.File(filesDir, "backups")
        val hasBackups = backupDir.exists() && (backupDir.listFiles()?.isNotEmpty() == true)
        if (hasBackups && !BackupEncryptor.isKeyAvailable()) {
            android.util.Log.w("NovelCharacterApp",
                "Backup encryption key is missing! Previous backups cannot be decrypted. " +
                "This may happen after a factory reset or KeyStore wipe.")
        }
    }

    companion object {
        const val BIRTHDAY_CHANNEL_ID = "birthday_channel"
    }
}
