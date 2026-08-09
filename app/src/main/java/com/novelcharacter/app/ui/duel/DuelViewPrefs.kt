package com.novelcharacter.app.ui.duel

import android.content.Context
import com.novelcharacter.app.util.DuelImageFit
import com.novelcharacter.app.util.DuelRound

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
    private const val KEY_SHOW_PROFILE = "show_profile"
    private const val KEY_SHOW_INFLUENCE = "show_influence"
    private const val KEY_GROUP_SIZE = "group_size"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun layout(context: Context): DuelImageFit.Layout =
        DuelImageFit.Layout.of(prefs(context).getString(KEY_LAYOUT, null))

    fun fit(context: Context): DuelImageFit.Fit =
        DuelImageFit.Fit.of(prefs(context).getString(KEY_FIT, null))

    /**
     * 프로필 필드를 카드에 띄우는가 (B-122). **기본은 켬** — 축에 프로필을 걸지 않았으면
     * 어차피 뜰 것이 없으므로 켠 기본값이 아무 말도 하지 않는다. 반대로 기본을 끔으로 두면
     * 사용자가 필드를 걸어 놓고도 *"왜 안 뜨지"*로 되돌아온다.
     */
    fun showProfile(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_PROFILE, true)

    /** 영향 필드를 카드에 띄우는가. 같은 근거로 기본은 켬이다(종전에는 끌 길이 없었다). */
    fun showInfluence(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_INFLUENCE, true)

    /**
     * 한 화면에 몇을 올리는가 — **1:1(2) · 3지선다 · 4지선다** (B-115).
     *
     * **기본이 2인 것은 되돌릴 수 있는 쪽이기 때문이다.** 셋 이상은 한 번에 `k−1`판을 남겨
     * 판이 빨리 쌓이지만 카드가 4분의 1로 줄어 정보 패널이 접힌다
     * ([com.novelcharacter.app.util.DuelCardGrid.Plan.compact]) —
     * 그림이 판단 재료의 전부인 사용자에게는 이득이고, 필드값을 견주는 사용자에게는 손해다.
     * **어느 쪽인지는 사용자가 가린다**(개발 의도 3번) — 그래서 설정으로 두고 기본은 종전 동작이다.
     */
    fun groupSize(context: Context): Int =
        prefs(context).getInt(KEY_GROUP_SIZE, DuelRound.MIN_SIZE)
            .coerceIn(DuelRound.MIN_SIZE, DuelRound.MAX_SIZE)

    fun save(
        context: Context,
        layout: DuelImageFit.Layout,
        fit: DuelImageFit.Fit,
        showProfile: Boolean,
        showInfluence: Boolean,
        groupSize: Int
    ) {
        prefs(context).edit()
            .putString(KEY_LAYOUT, layout.name)
            .putString(KEY_FIT, fit.name)
            .putBoolean(KEY_SHOW_PROFILE, showProfile)
            .putBoolean(KEY_SHOW_INFLUENCE, showInfluence)
            .putInt(KEY_GROUP_SIZE, groupSize.coerceIn(DuelRound.MIN_SIZE, DuelRound.MAX_SIZE))
            .apply()
    }
}
