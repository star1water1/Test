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
        val editDrafts: Map<String, String>, val instructions: Map<String, String>,
        val latestAi: Map<String, CharacterFieldAiSuggester.Suggestion>? = null,
        val revisions: Map<String, Long>? = null, val generation: Long? = null,
        val received: Set<String>? = null, val candidates: List<Candidate>? = null,
        val selectionValues: Map<String, CharacterFieldAiSuggester.Suggestion>? = null,
        val explicitOff: Set<String>? = null, val selectionRecheck: Set<String>? = null,
        val confirmedDirect: Map<String, CharacterFieldAiSuggester.Suggestion>? = null,
        val viewport: ReviewPresentation.Viewport? = null)
    data class Request(val sessionId: String, val generation: Long, val revisions: Map<String, Long>)
    data class Candidate(val id: String, val suggestion: CharacterFieldAiSuggester.Suggestion,
        val receipt: AiInputReceipt?, val stale: Boolean)
    fun snapshot() = Snapshot(sessionId, checks.snapshot(), edited.toMap(), originals.toMap(),
        editDrafts.toMap(), instructions.toMap(), latestAi.toMap(), revisions.toMap(), generation,
        received.toSet(), held.toList(), selectionValues.toMap(), explicitOff.toSet(), selectionRecheck.toSet(), confirmedDirect.toMap(), viewport)
    fun restore(value: Snapshot) {
        viewport=value.viewport
        sessionId=value.sessionId; checks.restore(value.checks)
        edited.clear(); edited.putAll(value.edited); originals.clear(); originals.putAll(value.originals)
        drafts.clear(); drafts.putAll(value.editDrafts)
        instructions.clear(); instructions.putAll(value.instructions)
        latestAi.clear(); latestAi.putAll(value.latestAi.orEmpty())
        revisions.clear(); revisions.putAll(value.revisions.orEmpty())
        generation = (value.generation ?: 0L) + 1 // Old callbacks cannot become current after recovery.
        received.clear(); received.addAll(value.received.orEmpty())
        held.clear(); held.addAll(value.candidates.orEmpty())
        selectionValues.clear(); selectionValues.putAll(value.selectionValues.orEmpty())
        if (value.selectionValues == null) {
            // Explicitly edited legacy values already belong to the user, not a new model proposal.
            value.edited.values.filter { it.editedByUser }.forEach { selectionValues[it.fieldKey] = it }
        }
        explicitOff.clear(); explicitOff.addAll(value.explicitOff.orEmpty())
        if (value.selectionValues == null && value.checks.seeded) {
            // Legacy snapshots cannot distinguish an unchecked default from a user's refusal.
            explicitOff.addAll(value.originals.keys - value.checks.checked)
        }
        selectionRecheck.clear(); selectionRecheck.addAll(value.selectionRecheck.orEmpty())
        confirmedDirect.clear(); confirmedDirect.putAll(value.confirmedDirect.orEmpty())
    }
    var viewport: ReviewPresentation.Viewport? = null
    fun latest(original: CharacterFieldAiSuggester.Suggestion) = latestAi[original.fieldKey] ?: original
    private val checks = AiCheckState<String>()
    private val edited = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    private val originals = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    private val drafts = mutableMapOf<String, String>()
    val editDrafts: Map<String, String> get() = drafts
    private val latestAi = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    private val revisions = mutableMapOf<String, Long>()
    private var generation = 0L
    private val received = mutableSetOf<String>()
    private val held = mutableListOf<Candidate>()
    private val selectionValues = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    private val explicitOff = mutableSetOf<String>()
    private val selectionRecheck = mutableSetOf<String>()
    private val confirmedDirect = mutableMapOf<String, CharacterFieldAiSuggester.Suggestion>()
    val instructions = mutableMapOf<String, String>()

    fun seedDefaults(defaultOn: Collection<String>) = checks.seedDefaults(defaultOn).also { changed() }
    fun setChecked(fieldKey: String, on: Boolean) {
        if (on) explicitOff.remove(fieldKey) else explicitOff.add(fieldKey)
        selectionRecheck.remove(fieldKey)
        if (checks.isChecked(fieldKey) != on) touch(fieldKey)
        checks.setChecked(fieldKey, on); changed()
    }
    private fun touch(key: String) { revisions[key] = (revisions[key] ?: 0L) + 1 }
    fun setDraft(key: String, value: String) {
        if (drafts[key] != value) touch(key)
        drafts[key] = value; changed()
    }
    fun removeDraft(key: String) { if (drafts.remove(key) != null) touch(key); changed() }
    fun isChecked(fieldKey: String) = checks.isChecked(fieldKey)
    fun remember(suggestion: CharacterFieldAiSuggester.Suggestion) {
        confirmedDirect.remove(suggestion.fieldKey)
        edited[suggestion.fieldKey] = suggestion
        selectionValues[suggestion.fieldKey] = suggestion // Editing is not a new AI proposal.
        touch(suggestion.fieldKey)
        changed()
    }
    fun current(original: CharacterFieldAiSuggester.Suggestion): CharacterFieldAiSuggester.Suggestion {
        originals.putIfAbsent(original.fieldKey, original)
        latestAi.putIfAbsent(original.fieldKey, original)
        return edited[original.fieldKey] ?: latestAi.getValue(original.fieldKey)
    }
    fun reset(original: CharacterFieldAiSuggester.Suggestion): CharacterFieldAiSuggester.Suggestion {
        val latest = latestAi[original.fieldKey] ?: original
        drafts.remove(original.fieldKey)
        edited[original.fieldKey] = latest
        touch(original.fieldKey)
        changed()
        return latest
    }
    fun candidates(key: String): List<Candidate> = held.filter { it.suggestion.fieldKey == key }
    fun receipt(value: CharacterFieldAiSuggester.Suggestion, receipts: List<AiInputReceipt>): AiInputReceipt? =
        receipts.firstOrNull { it.id == value.inputReceiptId } ?:
            held.firstOrNull { it.receipt?.id == value.inputReceiptId }?.receipt
    fun needsSelectionReview(key: String) = key in selectionRecheck
    fun isDirectConfirmed(value: CharacterFieldAiSuggester.Suggestion) = confirmedDirect[value.fieldKey] == value
    fun confirmDirect(value: CharacterFieldAiSuggester.Suggestion) {
        confirmedDirect[value.fieldKey] = value
        touch(value.fieldKey)
        changed()
    }
    /** Rerenders preserve decisions; a different AI proposal needs its own selection decision. */
    fun syncSelection(value: CharacterFieldAiSuggester.Suggestion, defaultOn: Boolean) {
        val key = value.fieldKey
        if (selectionValues[key] == value) return
        selectionRecheck.remove(key)
        if (!defaultOn && checks.isChecked(key)) selectionRecheck.add(key)
        checks.setChecked(key, defaultOn && key !in explicitOff)
        selectionValues[key] = value
        changed()
    }
    fun adopt(id: String): Boolean {
        val candidate = held.firstOrNull { it.id == id } ?: return false
        val key = candidate.suggestion.fieldKey
        edited[key] = candidate.suggestion
        drafts.remove(key); touch(key); changed()
        return true
    }
    /** Caller persists this state together with the run, using the existing journal/lease. */
    fun beginRequest(keys: Collection<String>, previous: List<CharacterFieldAiSuggester.Suggestion>): Request {
        previous.forEach { current(it) }
        generation++
        return Request(sessionId, generation, keys.associateWith { revisions[it] ?: 0L })
    }
    fun isCurrent(request: Request) = request.sessionId == sessionId && request.generation == generation
    /** Cumulative checkpoints and the final outcome pass through the same idempotent reducer. */
    fun receive(request: Request, outcome: CharacterFieldAiSuggester.SuggestOutcome): Boolean {
        if (request.sessionId != sessionId) return false
        val currentRequest = isCurrent(request)
        for (suggestion in outcome.suggestions) {
            val key = suggestion.fieldKey
            if (key !in request.revisions) continue
            val id = "${request.generation}:$key"
            if (!received.add(id)) continue
            val modified = (revisions[key] ?: 0L) != request.revisions[key]
            if (!currentRequest || modified) {
                // Pin the displayed value before changing the reset baseline. Drafts and checks survive.
                latestAi[key]?.let { edited.putIfAbsent(key, it) }
                held.add(Candidate(id, suggestion,
                    outcome.inputReceipts.orEmpty().firstOrNull { it.id == suggestion.inputReceiptId },
                    stale = !currentRequest))
            } else {
                edited.remove(key); drafts.remove(key)
            }
            if (currentRequest) latestAi[key] = suggestion
        }
        return currentRequest
    }
    fun clear() {
        viewport = null
        checks.clear()
        edited.clear()
        originals.clear()
        drafts.clear()
        instructions.clear()
        latestAi.clear(); revisions.clear(); received.clear(); held.clear(); generation = 0
        selectionValues.clear(); explicitOff.clear(); selectionRecheck.clear()
        confirmedDirect.clear()
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
