package com.novelcharacter.app.ui.assistant

import android.content.Context

/**
 * 어시스턴트의 사용자 자율성 설정 저장소 — 카테고리 on/off와 카드 숨김.
 *
 * 자율성 우선(개발 의도): 무엇을 볼지는 사용자가 정한다. 다만 숨김은 영구가 아니라
 * "상황이 악화되면 다시 노출"한다 — 숨긴 시점의 [AssistantInsight.resurfaceValue]를
 * 저장해두고, 이후 값이 더 커지면 무시한다(재노출).
 */
class AssistantPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enabledCategories(): Set<InsightCategory> =
        InsightCategory.values().filter { sp.getBoolean(catKey(it), true) }.toSet()

    fun isCategoryEnabled(category: InsightCategory): Boolean =
        sp.getBoolean(catKey(category), true)

    fun setCategoryEnabled(category: InsightCategory, enabled: Boolean) {
        sp.edit().putBoolean(catKey(category), enabled).apply()
    }

    /**
     * 숨김 상태인지. 기본은 "숨긴 뒤 상황이 악화(resurfaceValue 증가)하지 않았다면 계속 숨긴다".
     * [AssistantInsight.resurfaceExact]면 시그니처가 **정확히 같을 때만** 숨긴다 —
     * 정합성 오류의 대상 집합이 바뀌면(새 오류 포함) 반드시 재노출되도록(P1-C).
     */
    fun isDismissed(insight: AssistantInsight): Boolean {
        if (!insight.dismissible) return false
        val key = dismissKey(insight.id)
        if (!sp.contains(key)) return false
        val dismissedAt = sp.getInt(key, Int.MAX_VALUE)
        return if (insight.resurfaceExact) insight.resurfaceValue == dismissedAt
        else insight.resurfaceValue <= dismissedAt
    }

    fun dismiss(insight: AssistantInsight) {
        // 재노출 판정값만 저장. 복원 목록의 제목은 ViewModel이 라이브 산출한다(Finding 2).
        sp.edit().putInt(dismissKey(insight.id), insight.resurfaceValue).apply()
    }

    /** 특정 카드의 숨김을 해제(복원). 구버전이 남긴 제목 키도 함께 정리. */
    fun undismiss(id: String) {
        sp.edit().remove(dismissKey(id)).remove(legacyTitleKey(id)).apply()
    }

    // ── 편향 카드 규모 제어(사용자 설정) ──
    // 데이터가 많아질수록 편향 카드가 쏟아지던 문제를, 사용자가 직접 임계값으로 가린다(자율성 우선).

    /** 편향/이상치 카드를 낼 최소 모집단(필드 값 총수). 미만이면 소표본 노이즈로 보고 숨긴다. */
    fun biasMinPopulation(): Int = sp.getInt(KEY_BIAS_MIN_POP, DEFAULT_BIAS_MIN_POP)
    fun setBiasMinPopulation(value: Int) {
        sp.edit().putInt(KEY_BIAS_MIN_POP, value.coerceIn(1, 1000)).apply()
    }

    /** 한 번에 보여줄 편향 카드 최대 수(심각도 상위). */
    fun biasMaxCards(): Int = sp.getInt(KEY_BIAS_MAX_CARDS, DEFAULT_BIAS_MAX_CARDS)
    fun setBiasMaxCards(value: Int) {
        sp.edit().putInt(KEY_BIAS_MAX_CARDS, value.coerceIn(1, 50)).apply()
    }

    private fun catKey(category: InsightCategory) = "cat_${category.name}"
    private fun dismissKey(id: String) = "$DISMISS_PREFIX$id"
    private fun legacyTitleKey(id: String) = "dtitle_$id"

    companion object {
        private const val PREFS_NAME = "assistant_prefs"
        private const val DISMISS_PREFIX = "dismiss_"
        private const val KEY_BIAS_MIN_POP = "bias_min_population"
        private const val KEY_BIAS_MAX_CARDS = "bias_max_cards"
        const val DEFAULT_BIAS_MIN_POP = 8
        const val DEFAULT_BIAS_MAX_CARDS = 5
    }
}
