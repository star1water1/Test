package com.novelcharacter.app.ui.duel

import android.content.Context
import com.novelcharacter.app.util.DuelImageFit

/**
 * 대결 화면의 **보기 설정** — 카드 배치와 그림 맞춤(B-104).
 *
 * **전용 파일을 쓰는 것은 R-28 때문이다**([DuelEntryPrefs]와 같은 근거) — 같은 prefs 파일의
 * 같은 키를 두 화면이 다른 타입으로 다루면 읽는 순간 죽는다. 이어하기 힌트와 보기 설정은
 * 성격이 달라(하나는 자취, 하나는 취향) 같은 파일에 섞을 이유도 없다.
 *
 * **앱 전체 하나다.** 축마다 다르게 두면 축을 만들 때마다 다시 고르게 되는데, 이 설정이
 * 따르는 것은 축의 성질이 아니라 **사용자가 쓰는 그림의 규격**이라 축이 달라도 같다.
 */
object DuelViewPrefs {

    private const val PREFS_NAME = "duel_view"
    private const val KEY_LAYOUT = "card_layout"
    private const val KEY_FIT = "image_fit"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun layout(context: Context): DuelImageFit.Layout =
        DuelImageFit.Layout.of(prefs(context).getString(KEY_LAYOUT, null))

    fun fit(context: Context): DuelImageFit.Fit =
        DuelImageFit.Fit.of(prefs(context).getString(KEY_FIT, null))

    fun save(context: Context, layout: DuelImageFit.Layout, fit: DuelImageFit.Fit) {
        prefs(context).edit()
            .putString(KEY_LAYOUT, layout.name)
            .putString(KEY_FIT, fit.name)
            .apply()
    }
}
