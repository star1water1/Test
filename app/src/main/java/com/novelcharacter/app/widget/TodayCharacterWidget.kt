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
import com.novelcharacter.app.util.SqlInChunks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 오늘의 캐릭터 / 생일 위젯 (4x1).
 * 오늘 생일인 캐릭터를 보여주거나, 없으면 랜덤 캐릭터 하나를 보여준다.
 */
class TodayCharacterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // **시간 상한이 있어야 한다** — `goAsync()`가 준 시간을 넘기면 시스템이
                // 프로세스를 거둬 가고, 그러면 아래 `pendingResult.finish()`에 닿지도 못한다.
                // 이 갈래는 생일 상태변화 전량과(경우에 따라) **캐릭터 표 전량**을 올리므로
                // 저장소가 커질수록 그 위험이 커진다 — 이름 하나를 고르려고 그런다.
                // 형제 위젯(`RecentCharactersWidget`)이 같은 자리를 이미 이렇게 묶어 두었는데
                // 이 위젯만 빠져 있었다.
                val widgetText: String = withTimeoutOrNull(5000L) {
                    val db = AppDatabase.getDatabase(context)

                    // BirthdayHelper로 오늘 생일 캐릭터 조회 (윤년 처리 포함)
                    val allBirthChanges = db.characterStateChangeDao()
                        .getChangesWithDate(CharacterStateChange.KEY_BIRTH)
                    val birthdayCharIds = BirthdayHelper.getTodayBirthdayCharacterIds(allBirthChanges)

                    if (birthdayCharIds.isNotEmpty()) {
                        val names = SqlInChunks
                            .flat(birthdayCharIds) { db.characterDao().getCharactersByIds(it) }
                            .map { it.name }
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
                // 시간이 모자랐으면 **직전 내용을 그대로 둔다** — 빈 칸으로 덮으면 사용자는
                // 데이터가 없어진 줄로 읽는다. 다음 주기(1시간)에 다시 시도한다.
                } ?: return@launch

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
