package com.novelcharacter.app.util

import android.content.Context

/**
 * 온보딩/1회성 기능 힌트 플래그 (B-8).
 * 롱프레스처럼 화면만 봐서는 존재를 알 수 없는 기능을 첫 방문 시 한 번만 안내한다.
 */
object OnboardingPrefs {

    private const val PREFS_NAME = "onboarding_prefs"

    const val KEY_WELCOME_SHOWN = "welcome_shown"
    const val KEY_TIMELINE_HINT_SHOWN = "timeline_longpress_hint_shown"
    const val KEY_CHARACTER_MULTISELECT_HINT_SHOWN = "character_multiselect_hint_shown"
    const val KEY_BACKUP_IMAGE_NOTICE_SHOWN = "backup_image_default_notice_shown"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isShown(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, false)

    fun markShown(context: Context, key: String) {
        prefs(context).edit().putBoolean(key, true).apply()
    }
}
