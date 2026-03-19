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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 최근 캐릭터 위젯 (4x2).
 * RecentActivity에서 최근 본 캐릭터 4명을 표시한다.
 */
class RecentCharactersWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_recent_characters)

        // 앱 실행 인텐트
        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val recentActivities = db.recentActivityDao().getRecentActivitiesList()
                val charActivities = recentActivities
                    .filter { it.entityType == "character" }
                    .take(4)

                val textIds = listOf(
                    R.id.charName1, R.id.charName2, R.id.charName3, R.id.charName4
                )

                for (i in textIds.indices) {
                    val name = charActivities.getOrNull(i)?.title ?: ""
                    views.setTextViewText(textIds[i], name)
                }

                views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_recent_characters))
                manager.updateAppWidget(widgetId, views)
            } catch (_: Exception) {
                // Widget update failure is not critical
            } finally {
                pendingResult.finish()
            }
        }
    }
}
