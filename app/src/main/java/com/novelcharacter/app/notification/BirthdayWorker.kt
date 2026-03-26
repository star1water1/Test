package com.novelcharacter.app.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.util.BirthdayHelper

class BirthdayWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getDatabase(applicationContext)

            // month/day가 있는 모든 birth 상태변경 조회
            val allBirthChanges = db.characterStateChangeDao()
                .getChangesWithDate(CharacterStateChange.KEY_BIRTH)

            // BirthdayHelper로 오늘 생일 캐릭터 필터링 (윤년 처리 포함)
            val birthdayCharIds = BirthdayHelper.getTodayBirthdayCharacterIds(allBirthChanges)

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
