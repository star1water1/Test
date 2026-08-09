package com.novelcharacter.app.util

import android.content.Context

/**
 * **기준 이미지 축의 결과를 앱이 얼마나 세게 쓰는가** (B-104 소비처 ⓑ·ⓒ — 설계 13-5).
 *
 * 어느 축을 따르는가는 **DB**가 든다([com.novelcharacter.app.data.model.DuelAxis.isBasisAxis]) —
 * 대표 그림을 좌우하므로 백업·엑셀을 따라 넘어가야 두 기기가 갈리지 않는다.
 * 반면 여기 셋은 **취향이라 기기 설정이다**: 같은 데이터를 두고도 한 기기에서는 세게,
 * 다른 기기에서는 꺼둘 수 있어야 하고, 그렇게 갈려도 **데이터가 갈리지는 않는다.**
 * [DuelViewPrefs]가 카드 배치·그림 맞춤을 기기에 두는 것과 같은 갈래다.
 *
 * **전용 파일을 쓰는 것은 R-28 때문이다** — 같은 prefs 파일의 같은 키를 두 화면이 다른
 * 타입으로 다루면 읽는 순간 죽는다.
 *
 * ## 기본값이 곧 약속이다
 * 세기의 기본은 **약하게**(사용자 확정 — *"확률이면 모를까"*). 꺼둠이 기본이면 기능이
 * 있어도 아무도 만나지 못하고, 세게가 기본이면 사용자가 물리친 *"사실상 고정"*에 가까워진다.
 * 걸러낼 후보의 기본은 [DuelImagePrune]의 상수를 그대로 쓴다 — 여기 숫자를 다시 적으면
 * 판정과 설정이 두 벌이 되어 한쪽만 고쳐지는 날이 온다(R-7).
 */
object DuelImageBasisPrefs {

    private const val PREFS_NAME = "duel_image_basis"
    private const val KEY_STRENGTH = "representative_strength"
    private const val KEY_BOTTOM_PERCENT = "prune_bottom_percent"
    private const val KEY_MIN_PLAYED = "prune_min_played"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 대표 추첨을 얼마나 기울이는가. 기본 **약하게**. */
    fun strength(context: Context): RepresentativeWeighting.Strength =
        RepresentativeWeighting.Strength.of(prefs(context).getString(KEY_STRENGTH, null))

    /** 걸러낼 후보의 두 기준. 저장된 값이 이상해도 [DuelImagePrune.Options]가 접는다. */
    fun pruneOptions(context: Context): DuelImagePrune.Options {
        val p = prefs(context)
        return DuelImagePrune.Options(
            bottomPercent = p.getInt(KEY_BOTTOM_PERCENT, DuelImagePrune.DEFAULT_BOTTOM_PERCENT),
            minPlayed = p.getInt(KEY_MIN_PLAYED, DuelImagePrune.DEFAULT_MIN_PLAYED)
        )
    }

    fun save(
        context: Context,
        strength: RepresentativeWeighting.Strength,
        pruneOptions: DuelImagePrune.Options
    ) {
        prefs(context).edit()
            .putString(KEY_STRENGTH, strength.name)
            // 접은 값을 저장한다 — 창이 준 값이 범위를 벗어나도 다음에 읽을 때 또 접지 않게 한다.
            .putInt(KEY_BOTTOM_PERCENT, pruneOptions.percent)
            .putInt(KEY_MIN_PLAYED, pruneOptions.played)
            .apply()
    }
}
