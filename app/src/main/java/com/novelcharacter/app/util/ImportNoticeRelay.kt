package com.novelcharacter.app.util

import android.content.Context

/**
 * **가져오기 결과가 화면보다 오래 살게 한다** (B-56).
 *
 * ## 무엇이 문제였는가
 *
 * `ExcelImporter`는 `WeakReference<Activity>`로 누수를 막지만 **작업은 화면이 사라져도 계속
 * 돈다.** 그러면 결과 창을 띄울 자리가 없어 토스트로 물러섰는데, 토스트는
 * ⓐ **앱이 앞에 없으면 안드로이드가 아예 막고**(API 30+)
 * ⓑ 떠도 사라지며 **오류·경고 상세가 통째로 없어진다.**
 * 데이터가 조용히 망가지지는 않지만 **고지가 사라진다** — 변수 제어(검증 → 알림 → 교정)에서
 * '알림'이 빠지는 자리다.
 *
 * ## 두 길로 보내는 것이 일부러다
 *
 * **알림**은 앱 밖에 있는 사용자에게 지금 닿고, **이 보관함**은 알림을 못 봤거나 껐거나
 * 권한을 안 준 사용자에게 **다음에 앱을 열 때** 닿는다. 알림 권한은 사용자가 거절할 수 있고
 * (API 33+) 거절 자체가 정당한 선택이므로, 그것 하나에 고지를 걸면 **거절한 사용자에게는
 * 종전과 똑같이 아무 말도 없다.**
 *
 * ## 한 번만 낸다
 *
 * [consume]은 읽으면서 지운다. 남겨 두면 앱을 열 때마다 지난 결과가 다시 떠 사용자가 그것을
 * **새 결과로 오인**한다. 반대로 지우기만 하고 못 보여 주는 경우가 없도록, 지우는 시점은
 * *보여 줄 화면이 확실히 있을 때*다(부르는 쪽이 `isAdded`를 확인한 뒤 부른다).
 */
object ImportNoticeRelay {

    private const val PREFS = "import_notice"
    private const val KEY_TITLE = "title"
    private const val KEY_BODY = "body"

    /** 화면이 없어 못 전한 고지 하나. */
    data class Notice(val title: String, val body: String)

    /**
     * 다음 진입에서 낼 고지를 보관한다.
     *
     * **덮어쓰기가 옳다** — 화면 없이 가져오기를 두 번 했다면 사용자가 알아야 할 것은
     * 마지막 결과다. 쌓아 두면 앱을 열 때 지난 것들이 줄줄이 뜨고, 그중 무엇이 방금 것인지
     * 가릴 수 없다.
     */
    fun store(context: Context, title: String, body: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TITLE, title)
            .putString(KEY_BODY, body)
            .apply()
    }

    /** 보관된 고지를 꺼내면서 지운다. 없으면 null이다. */
    fun consume(context: Context): Notice? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString(KEY_TITLE, null) ?: return null
        val body = prefs.getString(KEY_BODY, null) ?: return null
        prefs.edit().remove(KEY_TITLE).remove(KEY_BODY).apply()
        return Notice(title, body)
    }
}
