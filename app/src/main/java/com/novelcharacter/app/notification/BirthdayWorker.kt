package com.novelcharacter.app.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BirthdayWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val today = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date())

            val birthdayCharacters = db.characterDao().getCharactersByBirthday(today)

            if (birthdayCharacters.isNotEmpty()) {
                val names = birthdayCharacters.map { it.name }
                NotificationHelper.showBirthdayNotification(applicationContext, names)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
