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
                .toMutableList()

            // 비윤년 2월 28일: 2월 29일 생일도 함께 알림
            val isLeapYear = calendar.getActualMaximum(Calendar.DAY_OF_MONTH).let {
                calendar.get(Calendar.MONTH) == Calendar.FEBRUARY && it == 29
            }
            if (todayMonth == 2 && todayDay == 28 && !isLeapYear) {
                val leapBirthdays = db.characterStateChangeDao()
                    .getChangesByFieldAndDate(CharacterStateChange.KEY_BIRTH, 2, 29)
                birthChanges.addAll(leapBirthdays)
            }

            val birthdayCharIds = birthChanges.map { it.characterId }.distinct()
            val birthdayNames = if (birthdayCharIds.isNotEmpty()) {
                db.characterDao().getCharactersByIds(birthdayCharIds).map { it.name }
            } else {
                emptyList()
            }

            if (birthdayNames.isNotEmpty()) {
                NotificationHelper.showBirthdayNotification(applicationContext, birthdayNames)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("BirthdayWorker", "Birthday check failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
