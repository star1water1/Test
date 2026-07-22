package com.novelcharacter.app.ui.supplement

import android.content.Context

/**
 * 랜덤 보충 탭 설정 — SupplementCriteria와 같은 SharedPreferences 패턴.
 * 기존 "supplement_ui_state" prefs 파일에 새 키만 추가한다(하위 호환).
 * 뽑기 모드는 SupplementViewModel이 같은 파일의 "random_mode" 키로 관리한다.
 */
data class RandomSupplementSettings(
    /** 편집 모드 OFF 시 확인 없이 자동 저장 (옵트인 — 기본 꺼짐) */
    val autoSaveOnExit: Boolean = false
) {
    companion object {
        private const val PREFS_NAME = "supplement_ui_state"
        private const val KEY_AUTO_SAVE = "random_auto_save"

        fun load(context: Context): RandomSupplementSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return RandomSupplementSettings(
                autoSaveOnExit = prefs.getBoolean(KEY_AUTO_SAVE, false)
            )
        }

        fun save(context: Context, settings: RandomSupplementSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_SAVE, settings.autoSaveOnExit)
                .apply()
        }
    }
}
