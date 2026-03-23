package com.novelcharacter.app.ui.supplement

import android.content.Context
import android.content.SharedPreferences

data class SupplementCriteria(
    val checkImages: Boolean = true,
    val checkMemo: Boolean = false,
    val checkAliases: Boolean = false,
    val checkNovel: Boolean = true,
    val checkTags: Boolean = false,
    val checkCustomFields: Boolean = true,
    val fieldCompletionThreshold: Int = 50,
    val checkRelationships: Boolean = false,
    val checkEvents: Boolean = false,
    val checkFactions: Boolean = false
) {
    companion object {
        private const val PREFS_NAME = "supplement_criteria"
        private const val KEY_CHECK_IMAGES = "check_images"
        private const val KEY_CHECK_MEMO = "check_memo"
        private const val KEY_CHECK_ALIASES = "check_aliases"
        private const val KEY_CHECK_NOVEL = "check_novel"
        private const val KEY_CHECK_TAGS = "check_tags"
        private const val KEY_CHECK_CUSTOM_FIELDS = "check_custom_fields"
        private const val KEY_FIELD_THRESHOLD = "field_threshold"
        private const val KEY_CHECK_RELATIONSHIPS = "check_relationships"
        private const val KEY_CHECK_EVENTS = "check_events"
        private const val KEY_CHECK_FACTIONS = "check_factions"

        fun load(context: Context): SupplementCriteria {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val defaults = SupplementCriteria()
            return SupplementCriteria(
                checkImages = prefs.getBoolean(KEY_CHECK_IMAGES, defaults.checkImages),
                checkMemo = prefs.getBoolean(KEY_CHECK_MEMO, defaults.checkMemo),
                checkAliases = prefs.getBoolean(KEY_CHECK_ALIASES, defaults.checkAliases),
                checkNovel = prefs.getBoolean(KEY_CHECK_NOVEL, defaults.checkNovel),
                checkTags = prefs.getBoolean(KEY_CHECK_TAGS, defaults.checkTags),
                checkCustomFields = prefs.getBoolean(KEY_CHECK_CUSTOM_FIELDS, defaults.checkCustomFields),
                fieldCompletionThreshold = prefs.getInt(KEY_FIELD_THRESHOLD, defaults.fieldCompletionThreshold),
                checkRelationships = prefs.getBoolean(KEY_CHECK_RELATIONSHIPS, defaults.checkRelationships),
                checkEvents = prefs.getBoolean(KEY_CHECK_EVENTS, defaults.checkEvents),
                checkFactions = prefs.getBoolean(KEY_CHECK_FACTIONS, defaults.checkFactions)
            )
        }

        fun save(context: Context, criteria: SupplementCriteria) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putBoolean(KEY_CHECK_IMAGES, criteria.checkImages)
                putBoolean(KEY_CHECK_MEMO, criteria.checkMemo)
                putBoolean(KEY_CHECK_ALIASES, criteria.checkAliases)
                putBoolean(KEY_CHECK_NOVEL, criteria.checkNovel)
                putBoolean(KEY_CHECK_TAGS, criteria.checkTags)
                putBoolean(KEY_CHECK_CUSTOM_FIELDS, criteria.checkCustomFields)
                putInt(KEY_FIELD_THRESHOLD, criteria.fieldCompletionThreshold)
                putBoolean(KEY_CHECK_RELATIONSHIPS, criteria.checkRelationships)
                putBoolean(KEY_CHECK_EVENTS, criteria.checkEvents)
                putBoolean(KEY_CHECK_FACTIONS, criteria.checkFactions)
                apply()
            }
        }

        fun resetToDefaults(context: Context) {
            save(context, SupplementCriteria())
        }
    }
}

enum class SupplementIssue(val label: String) {
    NO_IMAGE("이미지"),
    NO_MEMO("메모"),
    NO_ALIASES("별명"),
    NO_NOVEL("작품"),
    NO_TAGS("태그"),
    INCOMPLETE_FIELDS("필드"),
    NO_RELATIONSHIPS("관계"),
    NO_EVENTS("사건"),
    NO_FACTIONS("세력")
}

data class SupplementTarget(
    val character: com.novelcharacter.app.data.model.Character,
    val issues: List<SupplementIssue>,
    val fieldCompletion: Float
)
