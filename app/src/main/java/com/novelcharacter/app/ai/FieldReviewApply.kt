package com.novelcharacter.app.ai

/** Preflight the whole batch against the live form before any widget is changed. */
object FieldReviewApply {
    data class Prepared(val values: List<CharacterFieldAiSuggester.Suggestion>, val errors: List<String>)
    fun prepare(selected: List<CharacterFieldAiSuggester.Suggestion>,
        live: List<CharacterFieldAiSuggester.FieldSpec>): Prepared {
        val specs = live.associateBy { it.key }
        val errors = mutableListOf<String>()
        val values = selected.mapNotNull { suggestion ->
            val spec = specs[suggestion.fieldKey]
            if (spec == null) {
                errors.add("${suggestion.fieldKey}: 현재 폼에 적용할 수 없는 항목입니다.")
                null
            } else when (val valid = CharacterFieldAiSuggester.normalizeChecked(suggestion.value, spec)) {
                is CharacterFieldAiSuggester.Normalized.Rejected -> {
                    errors.add("${spec.name}: ${valid.cause.label}"); null
                }
                is CharacterFieldAiSuggester.Normalized.Ok -> suggestion.copy(value = valid.value,
                    outsideLibrary = valid.outsideLibrary)
            }
        }
        return Prepared(if (errors.isEmpty()) values else emptyList(), errors)
    }
    fun changedKeys(keys: Collection<String>, reviewed: Map<String, CharacterFieldAiSuggester.FieldSpec>,
        live: Map<String, CharacterFieldAiSuggester.FieldSpec>): List<String> =
        keys.filter { reviewed[it] != live[it] }
}
