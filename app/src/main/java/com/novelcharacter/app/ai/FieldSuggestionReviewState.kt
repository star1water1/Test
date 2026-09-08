package com.novelcharacter.app.ai

import com.novelcharacter.app.util.AiCheckState

/** One review session, owned by a ViewModel. UI recreation never reseeds user decisions. */
class FieldSuggestionReviewState {
    private val checks = AiCheckState<String>()
    private val edited = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    private val originals = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    val editDrafts = mutableMapOf<String, String>()
    val instructions = mutableMapOf<String, String>()

    fun seedDefaults(defaultOn: Collection<String>) = checks.seedDefaults(defaultOn)
    fun setChecked(fieldKey: String, on: Boolean) = checks.setChecked(fieldKey, on)
    fun isChecked(fieldKey: String) = checks.isChecked(fieldKey)
    fun remember(suggestion: CharacterFieldAiSuggester.Suggestion) {
        edited[suggestion.fieldKey] = suggestion
    }
    fun current(original: CharacterFieldAiSuggester.Suggestion): CharacterFieldAiSuggester.Suggestion {
        originals.putIfAbsent(original.fieldKey, original)
        return edited[original.fieldKey] ?: original
    }
    fun reset(original: CharacterFieldAiSuggester.Suggestion): CharacterFieldAiSuggester.Suggestion {
        editDrafts.remove(original.fieldKey)
        val first = originals[original.fieldKey] ?: original
        edited[original.fieldKey] = first
        return first
    }
    /** Only successful replacements change an edit; failures and other fields stay untouched. */
    fun replaced(suggestions: List<CharacterFieldAiSuggester.Suggestion>) {
        suggestions.forEach {
            edited.remove(it.fieldKey)
            editDrafts.remove(it.fieldKey)
        }
    }
    fun clear() {
        checks.clear()
        edited.clear()
        originals.clear()
        editDrafts.clear()
        instructions.clear()
    }

    companion object {
        fun merge(
            previous: CharacterFieldAiSuggester.SuggestOutcome,
            retry: CharacterFieldAiSuggester.SuggestOutcome
        ): CharacterFieldAiSuggester.SuggestOutcome {
            val replacements = retry.suggestions.associateBy { it.fieldKey }
            val existingKeys = previous.suggestions.map { it.fieldKey }.toSet()
            val suggestions = previous.suggestions.map { replacements[it.fieldKey] ?: it } +
                retry.suggestions.filter { it.fieldKey !in existingKeys }
            val resolved = suggestions.map { it.fieldKey }.toSet()
            val missing = (previous.missing + retry.missing).associateBy { it.fieldKey }
                .values.filter { it.fieldKey !in resolved }
            return previous.copy(
                suggestions = suggestions,
                missing = missing,
                droppedCount = previous.droppedCount + retry.droppedCount,
                failures = previous.failures + retry.failures + retry.missing
                    .filter { it.fieldKey in existingKeys }
                    .map { "보완 결과를 받지 못해 이전 제안을 유지했습니다: ${it.describe()}" },
                truncationNotes = (previous.truncationNotes + retry.truncationNotes).distinct(),
                inputTokens = previous.inputTokens + retry.inputTokens,
                outputTokens = previous.outputTokens + retry.outputTokens,
                unknownKeys = (previous.unknownKeys + retry.unknownKeys).distinct()
            )
        }
    }
}
