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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 최근 캐릭터 위젯 (4x2).
 * RecentActivity에서 최근 본 캐릭터 4명을 표시한다.
 */
class RecentCharactersWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val data = withTimeoutOrNull(5000L) {
                    val db = AppDatabase.getDatabase(context)
                    val recentActivities = db.recentActivityDao().getRecentActivitiesList()
                    recentActivities.filter { it.entityType == "character" }.take(4)
                }
                val charActivities = data ?: emptyList()

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_recent_characters)

                    val launchIntent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(
                        context, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)

                    val textIds = listOf(
                        R.id.charName1, R.id.charName2, R.id.charName3, R.id.charName4
                    )

                    for (i in textIds.indices) {
                        val name = charActivities.getOrNull(i)?.title ?: ""
                        views.setTextViewText(textIds[i], name)
                    }

                    views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_recent_characters))
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (e: Exception) {
                android.util.Log.w("RecentCharsWidget", "Widget update failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
