package com.novelcharacter.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.novelcharacter.app.MainActivity
import com.novelcharacter.app.R
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.util.BirthdayHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 오늘의 캐릭터 / 생일 위젯 (4x1).
 * 오늘 생일인 캐릭터를 보여주거나, 없으면 랜덤 캐릭터 하나를 보여준다.
 */
class TodayCharacterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)

                // BirthdayHelper로 오늘 생일 캐릭터 조회 (윤년 처리 포함)
                val allBirthChanges = db.characterStateChangeDao()
                    .getChangesWithDate(CharacterStateChange.KEY_BIRTH)
                val birthdayCharIds = BirthdayHelper.getTodayBirthdayCharacterIds(allBirthChanges)

                val widgetText: String = if (birthdayCharIds.isNotEmpty()) {
                    val names = db.characterDao().getCharactersByIds(birthdayCharIds).map { it.name }
                    context.getString(R.string.widget_birthday_today, names.joinToString(", "))
                } else {
                    val allChars = db.characterDao().getAllCharactersList()
                    if (allChars.isNotEmpty()) {
                        val random = allChars.random()
                        context.getString(R.string.widget_random_character, random.name)
                    } else {
                        context.getString(R.string.widget_no_birthday)
                    }
                }

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_today_character)

                    val launchIntent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(
                        context, 1, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
                    views.setTextViewText(R.id.widgetText, widgetText)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (e: Exception) {
                android.util.Log.w("TodayCharWidget", "Widget update failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
