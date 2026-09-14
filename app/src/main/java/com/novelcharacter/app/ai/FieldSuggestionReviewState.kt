package com.novelcharacter.app.ai

import com.novelcharacter.app.util.AiCheckState

/** One review session, owned by a ViewModel. UI recreation never reseeds user decisions. */
class FieldSuggestionReviewState {
    var onChanged: () -> Unit = {}
    fun changed() = onChanged()
    var sessionId: String = java.util.UUID.randomUUID().toString(); private set
    data class Snapshot(val sessionId: String, val checks: AiCheckState.Snapshot<String>,
        val edited: Map<String, CharacterFieldAiSuggester.Suggestion>,
        val originals: Map<String, CharacterFieldAiSuggester.Suggestion>,
        val editDrafts: Map<String, String>, val instructions: Map<String, String>)
    fun snapshot() = Snapshot(sessionId, checks.snapshot(), edited.toMap(), originals.toMap(),
        editDrafts.toMap(), instructions.toMap())
    fun restore(value: Snapshot) {
        sessionId=value.sessionId; checks.restore(value.checks)
        edited.clear(); edited.putAll(value.edited); originals.clear(); originals.putAll(value.originals)
        editDrafts.clear(); editDrafts.putAll(value.editDrafts)
        instructions.clear(); instructions.putAll(value.instructions)
    }
    private val checks = AiCheckState<String>()
    private val edited = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    private val originals = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    val editDrafts = mutableMapOf<String, String>()
    val instructions = mutableMapOf<String, String>()

    fun seedDefaults(defaultOn: Collection<String>) = checks.seedDefaults(defaultOn).also { changed() }
    fun setChecked(fieldKey: String, on: Boolean) = checks.setChecked(fieldKey, on).also { changed() }
    fun isChecked(fieldKey: String) = checks.isChecked(fieldKey)
    fun remember(suggestion: CharacterFieldAiSuggester.Suggestion) {
        edited[suggestion.fieldKey] = suggestion
        changed()
    }
    fun current(original: CharacterFieldAiSuggester.Suggestion): CharacterFieldAiSuggester.Suggestion {
        if (originals.putIfAbsent(original.fieldKey, original) == null) changed()
        return edited[original.fieldKey] ?: original
    }
    fun reset(original: CharacterFieldAiSuggester.Suggestion): CharacterFieldAiSuggester.Suggestion {
        editDrafts.remove(original.fieldKey)
        val first = originals[original.fieldKey] ?: original
        edited[original.fieldKey] = first
        changed()
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
        sessionId=java.util.UUID.randomUUID().toString()
    }

    companion object {
        /** Unchanged confidence/rejected values cannot be fixed by paying for the same retry. */
        fun retryableKeys(outcome: CharacterFieldAiSuggester.SuggestOutcome): List<String> =
            outcome.missing.filter { it.cause in RETRYABLE_CAUSES }.map { it.fieldKey }.distinct()
        private val RETRYABLE_CAUSES = setOf(
            CharacterFieldAiSuggester.MissingCause.NOT_RETURNED,
            CharacterFieldAiSuggester.MissingCause.TRUNCATED,
            CharacterFieldAiSuggester.MissingCause.UNREADABLE,
            CharacterFieldAiSuggester.MissingCause.REQUEST_FAILED,
            CharacterFieldAiSuggester.MissingCause.NOT_REQUESTED,
            CharacterFieldAiSuggester.MissingCause.CANCELLED,
            CharacterFieldAiSuggester.MissingCause.INVALID,
            CharacterFieldAiSuggester.MissingCause.DUPLICATE
        )
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
                unknownKeys = (previous.unknownKeys + retry.unknownKeys).distinct(),
                inputReceipts = (previous.inputReceipts.orEmpty() + retry.inputReceipts.orEmpty()).distinctBy { it.id }
            )
        }
    }
}
