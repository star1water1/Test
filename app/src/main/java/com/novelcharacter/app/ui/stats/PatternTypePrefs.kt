package com.novelcharacter.app.ui.stats

import android.content.Context

/**
 * '인사이트 유형' 설정의 **단일 소스** (B-31 인접).
 *
 * 같은 SharedPreferences 키를 세 곳이 각자 읽고 썼다 — 설정 다이얼로그, 통계 ViewModel,
 * 그리고 어시스턴트는 **아예 읽지 않았다**. 그래서 사용자가 '편중'을 꺼도 어시스턴트의
 * 편향 카드는 그대로 떴다: 하나의 설정이 화면마다 다르게 해석되는 상태였다(R-15의 취지).
 *
 * 저장 형식(문자열 집합)과 "저장된 값이 없으면 전체 활성"이라는 기본값 규칙도 여기 하나뿐이다.
 * 알 수 없는 이름(구버전에서 사라진 유형)은 조용히 버린다 — 그 유형은 이제 존재하지 않는다.
 */
object PatternTypePrefs {

    private const val PREFS_NAME = "stats_prefs"
    private const val KEY = "pattern_insights_enabled_types"

    /** 사용자가 활성화한 패턴 유형. 저장된 값이 없으면 전체. */
    fun enabled(context: Context): Set<PatternType> {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY, null) ?: return PatternType.values().toSet()
        return stored.mapNotNull { name ->
            try { PatternType.valueOf(name) } catch (_: Exception) { null }
        }.toSet()
    }

    fun save(context: Context, types: Set<PatternType>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, types.map { it.name }.toSet())
            .apply()
    }
}
