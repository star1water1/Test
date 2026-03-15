package com.novelcharacter.app.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterStateChange
import java.util.Calendar

class BirthdayWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val calendar = Calendar.getInstance()
            val todayMonth = calendar.get(Calendar.MONTH) + 1
            val todayDay = calendar.get(Calendar.DAY_OF_MONTH)

            // 오늘 월/일과 일치하는 탄생 이벤트를 일괄 조회
            val birthChanges = db.characterStateChangeDao()
                .getChangesByFieldAndDate(CharacterStateChange.KEY_BIRTH, todayMonth, todayDay)

            val birthdayCharIds = birthChanges.map { it.characterId }.distinct()
            val birthdayNames = mutableListOf<String>()
            for (charId in birthdayCharIds) {
                val character = db.characterDao().getCharacterById(charId)
                if (character != null) {
                    birthdayNames.add(character.name)
                }
            }

            if (birthdayNames.isNotEmpty()) {
                NotificationHelper.showBirthdayNotification(applicationContext, birthdayNames)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("BirthdayWorker", "Birthday check failed", e)
            Result.retry()
        }
    }
}
