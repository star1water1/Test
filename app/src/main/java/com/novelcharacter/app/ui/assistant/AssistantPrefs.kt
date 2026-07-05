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

    /** 숨김 상태인지 — 숨긴 뒤 상황이 악화(resurfaceValue 증가)하지 않았다면 계속 숨긴다. */
    fun isDismissed(insight: AssistantInsight): Boolean {
        if (!insight.dismissible) return false
        val key = dismissKey(insight.id)
        if (!sp.contains(key)) return false
        val dismissedAt = sp.getInt(key, Int.MAX_VALUE)
        return insight.resurfaceValue <= dismissedAt
    }

    fun dismiss(insight: AssistantInsight) {
        // 재노출 판정값과 함께, 복원 UI가 목록에 이름을 보여줄 수 있도록 제목도 저장한다.
        sp.edit()
            .putInt(dismissKey(insight.id), insight.resurfaceValue)
            .putString(titleKey(insight.id), insight.title)
            .apply()
    }

    /** 특정 카드의 숨김을 해제(복원). */
    fun undismiss(id: String) {
        sp.edit().remove(dismissKey(id)).remove(titleKey(id)).apply()
    }

    /** 현재 숨김 상태인 카드들의 id→제목. 복원 다이얼로그 목록용. */
    fun dismissedTitles(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((key, value) in sp.all) {
            if (key.startsWith(TITLE_PREFIX) && value is String) {
                result[key.removePrefix(TITLE_PREFIX)] = value
            }
        }
        return result
    }

    private fun catKey(category: InsightCategory) = "cat_${category.name}"
    private fun dismissKey(id: String) = "$DISMISS_PREFIX$id"
    private fun titleKey(id: String) = "$TITLE_PREFIX$id"

    companion object {
        private const val PREFS_NAME = "assistant_prefs"
        private const val DISMISS_PREFIX = "dismiss_"
        private const val TITLE_PREFIX = "dtitle_"
    }
}
