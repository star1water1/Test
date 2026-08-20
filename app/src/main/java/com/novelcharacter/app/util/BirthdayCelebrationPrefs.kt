package com.novelcharacter.app.util

import android.content.Context

/**
 * 생일 축하 창의 두 값 (사용자 요청 2026.08.20).
 *
 * 성질이 갈리는 둘이 한 저장소에 산다 — **가르는 축은 "새 기기로 옮겨야 하는가"**다.
 *
 * | | 무엇 | 옮기는가 |
 * |---|---|---|
 * | [isEnabled] | 축하 창을 띄우는가 — **취향** | **옮긴다** (`birthday_celebration_enabled`로 엑셀에 실린다) |
 * | [lastShownDay] | 오늘 이미 띄웠는가 — **그 기기에서 일어난 일** | 안 옮긴다 |
 *
 * 뒤엣것을 실으면 새 기기가 **하지도 않은 축하를 했다고** 말해 그날 창이 안 뜬다
 * (`backup_status`를 뺀 것과 같은 근거).
 */
object BirthdayCelebrationPrefs {

    private const val PREFS_NAME = "birthday_celebration"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SHOWN_DAY = "last_shown_day"

    /** 아직 한 번도 안 띄운 상태. `0`이 아니라 이름을 두는 것은 셈에 쓰이기 때문이다. */
    const val NEVER_SHOWN = 0L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** **기본은 켬** — 생일을 적어 둔 사용자는 축하받기를 기대한다. 끄는 길은 설정에 있다. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 마지막으로 축하 창을 띄운 날(`yyyyMMdd` — [QuotePicker.todayStamp]와 같은 셈). */
    fun lastShownDay(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SHOWN_DAY, NEVER_SHOWN)

    fun markShown(context: Context, dayStamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_SHOWN_DAY, dayStamp).apply()
    }

    /**
     * 오늘 띄울 차례인가 — **설정이 켜져 있고, 오늘 아직 안 띄웠을 때**.
     *
     * 생일인 캐릭터가 있는가는 **묻지 않는다.** 그것은 DB를 읽어야 알 수 있고, 이 판정은
     * 순수해야 부르는 쪽이 *"읽기 전에 걸러낼 수 있는가"*를 물을 수 있다 — 축하 창을 꺼 둔
     * 사용자에게는 질의 자체가 돌지 않는다.
     */
    fun shouldShow(context: Context, todayStamp: Long): Boolean =
        isEnabled(context) && lastShownDay(context) != todayStamp
}
