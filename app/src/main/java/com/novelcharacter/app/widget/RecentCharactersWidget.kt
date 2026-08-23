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
                // **시간이 모자랐으면 사실대로 적는다** — 형제 위젯(`TodayCharacterWidget`)이
                // 같은 자리에서 세운 처분이고, 그쪽은 레이아웃 기본 문구를 그대로 두는 것조차
                // *"실제로 있는데 없다고 말하게 된다"*는 이유로 금지해 두었다. 이쪽만 그 처분이
                // 없어, 상한에 걸리면 이름 칸 넷을 전부 ""로 덮어 **'최근 본 캐릭터가 없다'**고
                // 말했다(느린 기기·콜드 스타트에서 걸린다). 틀린 말이지 유실은 아니다.
                val deferred = data == null
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

                    // `RemoteViews`는 매번 새로 짓는 뷰 트리라 **앞선 갱신의 내용이 남지 않는다** —
                    // 안 채우면 레이아웃 기본값(여기서는 빈 칸)이 그려진다. 그래서 이름 칸은
                    // 어느 갈래든 명시로 채우고, *왜 비었는가*는 제목 줄이 말한다.
                    for (i in textIds.indices) {
                        val name = if (deferred) "" else charActivities.getOrNull(i)?.title ?: ""
                        views.setTextViewText(textIds[i], name)
                    }

                    views.setTextViewText(
                        R.id.widgetTitle,
                        context.getString(
                            if (deferred) R.string.widget_update_deferred
                            else R.string.widget_recent_characters
                        )
                    )
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
