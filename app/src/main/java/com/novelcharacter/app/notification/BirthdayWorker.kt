package com.novelcharacter.app.notification

import android.content.Context
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

            // v2: 생일은 CharacterStateChange에서 __birth 필드로 관리
            // 모든 캐릭터의 탄생 이벤트를 확인하여 오늘이 생일인 캐릭터 찾기
            val allCharacters = db.characterDao().getAllCharactersList()
            val birthdayNames = mutableListOf<String>()

            for (character in allCharacters) {
                val birthChanges = db.characterStateChangeDao()
                    .getChangesByField(character.id, CharacterStateChange.KEY_BIRTH)
                val birthChange = birthChanges.firstOrNull() ?: continue

                if (birthChange.month == todayMonth && birthChange.day == todayDay) {
                    birthdayNames.add(character.name)
                }
            }

            if (birthdayNames.isNotEmpty()) {
                NotificationHelper.showBirthdayNotification(applicationContext, birthdayNames)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
