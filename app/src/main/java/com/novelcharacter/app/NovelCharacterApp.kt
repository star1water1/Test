package com.novelcharacter.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.repository.AppRepository
import com.novelcharacter.app.notification.BirthdayWorker
import java.util.concurrent.TimeUnit

class NovelCharacterApp : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy {
        AppRepository(
            database,
            database.novelDao(),
            database.characterDao(),
            database.timelineDao(),
            database.universeDao(),
            database.fieldDefinitionDao(),
            database.characterFieldValueDao(),
            database.characterStateChangeDao()
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleBirthdayCheck()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            BIRTHDAY_CHANNEL_ID,
            "캐릭터 생일 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "소설 캐릭터의 생일을 알려줍니다"
        }
        val manager = getSystemService(NotificationManager::class.java)
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

    companion object {
        const val BIRTHDAY_CHANNEL_ID = "birthday_channel"
    }
}
