package com.novelcharacter.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.repository.AppRepository
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.TimelineRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import com.novelcharacter.app.data.repository.NameBankRepository
import com.novelcharacter.app.data.repository.SearchPresetRepository
import com.novelcharacter.app.backup.AutoBackupWorker
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

    // Keep backward compatibility
    val repository by lazy {
        AppRepository(novelRepository, characterRepository, timelineRepository, universeRepository, nameBankRepository)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // Apply saved theme from SharedPreferences cache (non-blocking)
        ThemeHelper.applyTheme(ThemeHelper.getSavedTheme(this))
        // Migrate DataStore → SharedPreferences cache on first launch
        appScope.launch(Dispatchers.IO) {
            ThemeHelper.migrateCacheIfNeeded(this@NovelCharacterApp)
        }
        createNotificationChannel()
        scheduleBirthdayCheck()
        scheduleAutoBackup()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            BIRTHDAY_CHANNEL_ID,
            "캐릭터 생일 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "소설 캐릭터의 생일을 알려줍니다"
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

    companion object {
        const val BIRTHDAY_CHANNEL_ID = "birthday_channel"
    }
}
