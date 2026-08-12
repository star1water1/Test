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

    /**
     * 유형별 **민감도**(B-70). 유형 on/off와 같은 저장소·같은 창에 두는 것은 둘이 한 질문의
     * 두 부분이기 때문이다 — *이 유형을 볼 것인가*와 *무엇부터를 그 유형이라 부를 것인가*.
     *
     * 값은 [PatternThresholds.encode] 한 벌이라 엑셀 왕복과 형식이 갈리지 않는다.
     */
    private const val KEY_THRESHOLDS = "pattern_insights_thresholds"

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

    /**
     * 저장된 민감도. 저장된 값이 없으면 기본값 — **처음 쓰는 사용자에게 종전과 똑같이 보인다**는
     * 뜻이고, 확정 11번의 *"기본값은 현행 유지"*가 이 한 줄이다.
     */
    fun thresholds(context: Context): PatternThresholds {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THRESHOLDS, null) ?: return PatternThresholds.DEFAULT
        return PatternThresholds.decode(raw).thresholds
    }

    /** 접히는 값은 여기서 접는다 — 저장된 뒤에 접으면 화면이 보여 준 수와 저장된 수가 갈린다. */
    fun saveThresholds(context: Context, thresholds: PatternThresholds) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THRESHOLDS, PatternThresholds.clamp(thresholds).encode())
            .apply()
    }
}
