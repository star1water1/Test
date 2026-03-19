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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 오늘의 캐릭터 / 생일 위젯 (4x1).
 * 오늘 생일인 캐릭터를 보여주거나, 없으면 랜덤 캐릭터 하나를 보여준다.
 */
class TodayCharacterWidget : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_today_character)

        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 1, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val calendar = Calendar.getInstance()
                val month = calendar.get(Calendar.MONTH) + 1
                val day = calendar.get(Calendar.DAY_OF_MONTH)

                // 오늘 생일인 캐릭터 검색
                val birthChanges = db.characterStateChangeDao()
                    .getChangesByFieldAndDate(CharacterStateChange.KEY_BIRTH, month, day)

                if (birthChanges.isNotEmpty()) {
                    val charIds = birthChanges.map { it.characterId }.distinct()
                    val names = charIds.mapNotNull { id ->
                        db.characterDao().getCharacterById(id)?.name
                    }
                    val text = context.getString(R.string.widget_birthday_today, names.joinToString(", "))
                    views.setTextViewText(R.id.widgetText, text)
                } else {
                    // 랜덤 캐릭터
                    val allChars = db.characterDao().getAllCharactersList()
                    if (allChars.isNotEmpty()) {
                        val random = allChars.random()
                        views.setTextViewText(
                            R.id.widgetText,
                            context.getString(R.string.widget_random_character, random.name)
                        )
                    } else {
                        views.setTextViewText(R.id.widgetText, context.getString(R.string.widget_no_birthday))
                    }
                }

                manager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {}
        }
    }
}
