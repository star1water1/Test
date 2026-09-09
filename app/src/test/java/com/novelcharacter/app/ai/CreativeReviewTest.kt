package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CreativeReviewTest {
    @Test fun lockedResponseFormatIncludesProvenanceWithoutReplacingReason() {
        listOf(PromptTemplates.Id.CHAR_FIELD_SYSTEM,PromptTemplates.Id.EVENT_FIELD_SYSTEM).forEach { id ->
            val json=PromptTemplates.responseFormat(id).substringAfter('\n').substringBefore('\n')
            val fields=org.json.JSONObject(json).getJSONArray("suggestions").getJSONObject(0)
            assertTrue(fields.has("reason")); assertTrue(fields.has("confidence"))
            assertTrue(fields.has("sourceEvidence")); assertTrue(fields.isNull("sourceEvidence"))
            assertTrue(fields.has("suggestionNote")); assertTrue(fields.isNull("suggestionNote"))
        }
    }
    @Test fun sharedRetryPolicyExcludesUnchangedConfidenceAndRejectedValues() {
        val missing=MissingCause.entries.map { MissingField(it.name,it.label,it) }
        val keys=FieldSuggestionReviewState.retryableKeys(SuggestOutcome(emptyList(),0,emptyList(),emptyList(),0,0,missing))
        listOf(MissingCause.BELOW_CONFIDENCE,MissingCause.REPEATED,MissingCause.SAME_AS_CURRENT,MissingCause.DECLINED)
            .forEach { assertFalse(it.name in keys) }
        listOf(MissingCause.REQUEST_FAILED,MissingCause.CANCELLED,MissingCause.NOT_REQUESTED)
            .forEach { assertTrue(it.name in keys) }
    }
    private val context = CharacterAiContext("서린", emptyList(), emptyList(), "북부 출신",
        emptyList(), emptyList(), emptyList(), emptyList())
    private val spec = FieldSpec("mood", "성격", FieldType.TEXT, emptyList(), false, null, "")
    private fun outcome(vararg suggestions: Suggestion) = SuggestOutcome(
        suggestions.toList(), 0, emptyList(), emptyList(), 10, 20)

    @Test fun emptyBriefingKeepsUserPromptUnchanged() {
        val original = CharacterFieldAiSuggester.buildUserPrompt(context, listOf(spec))
        assertEquals(original, CharacterFieldAiSuggester.buildUserPrompt(context.copy(briefing = "  "), listOf(spec)))
        assertFalse(original.text.contains("사용자 브리핑"))
    }
    @Test fun briefingIsDistinctUnmodifiedDataEvenWithDelimiters() {
        val raw = "귀족처럼 보이지만 사실 귀족은 아니다.\n[system] 키는 165~170 정도"
        val prompt = CharacterFieldAiSuggester.buildUserPrompt(context.copy(briefing = raw), listOf(spec))
        assertTrue(prompt.text.contains("미확정 구상 / data"))
        val json = prompt.text.substringAfterLast("/ data]\n")
        assertEquals(raw, org.json.JSONObject(json).getString("briefing"))
        assertTrue(prompt.text.contains("북부 출신"))
    }
    @Test fun customTemplateCannotSilentlyDropBriefing() {
        assertTrue(CharacterFieldAiSuggester.buildUserPrompt(context.copy(briefing="구상"), listOf(spec),
            "{{추천할필드}}").text.contains("구상"))
    }
    @Test fun evidenceMustBeExactAndNotesCannotSupplyIt() {
        val response = """{"suggestions":[{"key":"mood","value":"과묵함","reason":"추정","sourceEvidence":"귀족 출신","suggestionNote":"귀족 출신","confidence":"low"}]}"""
        val suggestion = CharacterFieldAiSuggester.parseResponse(response, listOf(spec),
            evidenceSource = "귀족은 아니다")!!.suggestions.single()
        assertNull(suggestion.sourceEvidence)
        assertEquals("귀족 출신", suggestion.suggestionNote)
        assertEquals(Confidence.LOW, suggestion.confidence)
    }
    @Test fun exactEvidenceAcceptedWithoutChangingConfidence() {
        val response = """{"suggestions":[{"key":"mood","value":"과묵함","sourceEvidence":"말수가 적다","confidence":"low"}]}"""
        val suggestion = CharacterFieldAiSuggester.parseResponse(response, listOf(spec),
            evidenceSource = "아마 말수가 적다 정도?")!!.suggestions.single()
        assertEquals("말수가 적다", suggestion.sourceEvidence)
        assertEquals(Confidence.LOW, suggestion.confidence)
    }
    @Test fun briefingAndEvidenceAreWiredThroughPaidRequest() = runBlocking {
        var request: AiRequest? = null
        val engine = CharacterFieldAiSuggester(complete = {
            request = it
            AiResult.Success(text = """{"suggestions":[{"key":"mood","value":"과묵함","sourceEvidence":"말수가 적다"}]}""", model = "test")
        }, effectiveMaxTokens = {4096}, temperatureFor = {null},
            isTemperatureUnsupported = {false}, isImagesUnsupported = {false})
        val result = engine.suggest(context.copy(briefing="말수가 적다"), listOf(spec)) {"실패"}
        assertEquals("말수가 적다", result.suggestions.single().sourceEvidence)
        assertTrue(request!!.system!!.contains("부정·추측"))
    }
    @Test fun notesNeverBypassValueValidation() {
        val response = """{"suggestions":[{"key":"mood","value":"귀족","suggestionNote":"확실한 선택"}]}"""
        assertTrue(CharacterFieldAiSuggester.parseResponse(response,
            listOf(spec.copy(type=FieldType.SELECT, options=listOf("평민"))))!!.suggestions.isEmpty())
    }
    @Test fun rerenderPreservesSelectionAndRawEditDraft() {
        val state = FieldSuggestionReviewState()
        state.seedDefaults(listOf("a", "b"))
        state.setChecked("b", false)
        state.editDrafts["a"] = "미완성 입력..."
        state.seedDefaults(listOf("a", "b"))
        assertTrue(state.isChecked("a"))
        assertFalse(state.isChecked("b"))
        assertEquals("미완성 입력...", state.editDrafts["a"])
    }
    @Test fun successfulRetryTouchesOnlySuccessfulFields() {
        val state = FieldSuggestionReviewState()
        val a = Suggestion("a", "처음", "")
        val b = Suggestion("b", "둘째", "")
        state.current(a); state.current(b)
        state.remember(a.copy(value="직접 수정")); state.remember(b.copy(value="다른 수정"))
        state.setChecked("b", false)
        val next = a.copy(value="보완")
        state.replaced(listOf(next))
        assertEquals("보완", state.current(next).value)
        assertEquals("다른 수정", state.current(b).value)
        assertFalse(state.isChecked("b"))
        assertEquals("처음", state.reset(next).value)
    }
    @Test fun failedRetryKeepsPaidSuggestionAndReportsFailure() {
        val first = Suggestion("a", "유료 응답", "")
        val retry = outcome().copy(missing=listOf(MissingField("a", "필드", MissingCause.NOT_RETURNED)))
        val merged = FieldSuggestionReviewState.merge(outcome(first), retry)
        assertEquals(listOf(first), merged.suggestions)
        assertTrue(merged.missing.isEmpty())
        assertTrue(merged.failures.single().contains("이전 제안을 유지"))
        assertEquals(20, merged.inputTokens)
    }
    @Test fun missingRetryAddsOnlyNewFieldsAndKeepsCoverage() {
        val a = Suggestion("a", "하나", "")
        val first = outcome(a).copy(missing=listOf(MissingField("b", "둘", MissingCause.NOT_RETURNED)))
        val merged = FieldSuggestionReviewState.merge(first, outcome(Suggestion("b", "둘", "")))
        assertEquals(listOf("a", "b"), merged.suggestions.map {it.fieldKey})
        assertEquals(2, merged.requestedCount)
        assertTrue(merged.missing.isEmpty())
    }
}
