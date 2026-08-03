package com.novelcharacter.app.ui.character

import android.content.Context
import com.novelcharacter.app.util.DetailListSort

/**
 * 캐릭터 상세 인라인 목록 세 개의 **보기 정렬 저장소**(B-85).
 *
 * 세 목록의 정렬이 한 화면에 있으므로 키도 한 파일에서 관리한다 — 흩어 두면 같은 prefs 파일의
 * 같은 키를 다른 곳이 다른 타입으로 읽는 사고가 난다(`tools/check_prefs_keys.sh`가 지키는 규약 R-28).
 * 값은 전부 enum 이름 문자열이며, 모르는 값은 기본으로 떨어진다(구버전·손상에 죽지 않는다).
 *
 * 캐릭터별이 아니라 화면별로 기억한다: "나는 관계를 강도순으로 본다"는 사용자의 보기 습관이지
 * 캐릭터 하나의 속성이 아니다. 캐릭터마다 따로 두면 새 캐릭터를 열 때마다 다시 골라야 한다(원칙 04).
 */
object CharacterDetailSortPrefs {

    private const val PREFS_NAME = "character_detail_ui_state"
    private const val KEY_RELATIONSHIP = "relationship_sort_mode"
    private const val KEY_STATE_CHANGE = "state_change_sort_mode"
    private const val KEY_EVENT = "event_sort_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun relationshipMode(context: Context): DetailListSort.RelationshipMode =
        DetailListSort.RelationshipMode.values()
            .firstOrNull { it.name == prefs(context).getString(KEY_RELATIONSHIP, null) }
            ?: DetailListSort.RelationshipMode.MANUAL

    fun setRelationshipMode(context: Context, mode: DetailListSort.RelationshipMode) {
        prefs(context).edit().putString(KEY_RELATIONSHIP, mode.name).apply()
    }

    fun stateChangeMode(context: Context): DetailListSort.StateChangeMode =
        DetailListSort.StateChangeMode.values()
            .firstOrNull { it.name == prefs(context).getString(KEY_STATE_CHANGE, null) }
            ?: DetailListSort.StateChangeMode.CHRONO

    fun setStateChangeMode(context: Context, mode: DetailListSort.StateChangeMode) {
        prefs(context).edit().putString(KEY_STATE_CHANGE, mode.name).apply()
    }

    fun eventMode(context: Context): DetailListSort.EventMode =
        DetailListSort.EventMode.values()
            .firstOrNull { it.name == prefs(context).getString(KEY_EVENT, null) }
            ?: DetailListSort.EventMode.CHRONO

    fun setEventMode(context: Context, mode: DetailListSort.EventMode) {
        prefs(context).edit().putString(KEY_EVENT, mode.name).apply()
    }
}
