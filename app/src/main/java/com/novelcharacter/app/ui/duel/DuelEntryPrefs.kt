package com.novelcharacter.app.ui.duel

import android.content.Context

/**
 * 마지막에 연 대결 축을 기억한다 — 홈 진입점의 **이어하기**가 이 값을 쓴다.
 *
 * **전용 파일을 쓰는 것은 R-28 때문이다.** 같은 prefs 파일의 같은 키를 두 화면이 다른 타입으로
 * 다루면 읽는 순간 죽는다. 대결이 기억하는 것은 이 하나뿐이라 파일도 하나면 충분하고,
 * 남의 파일에 얹으면 그 위험만 들여온다.
 *
 * **여기 담기는 것은 힌트이지 사실이 아니다.** 축은 지워질 수 있고(휴지통·세계관 삭제) 그때
 * 이 값을 지워 주는 경로는 없다 — 그러므로 **읽는 쪽이 언제나 살아 있는지 확인해야 한다**
 * (`DuelEntry.targetOf`가 그 확인 결과를 받는다).
 */
object DuelEntryPrefs {

    private const val PREFS_NAME = "duel_entry"
    private const val KEY_LAST_AXIS_ID = "last_axis_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 대결 화면이 열릴 때 부른다. */
    fun rememberAxis(context: Context, axisId: Long) {
        if (axisId <= 0L) return
        prefs(context).edit().putLong(KEY_LAST_AXIS_ID, axisId).apply()
    }

    /** 기억이 없으면 0. */
    fun lastAxisId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_AXIS_ID, 0L)
}
