package com.novelcharacter.app.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.*
import org.junit.Test

class FieldReviewRevisionTest {
    private val first = Suggestion("a", "처음", "이유")
    private val next = first.copy(value = "보완")
    private fun outcome(vararg values: Suggestion) = SuggestOutcome(values.toList(), 0,
        emptyList(), emptyList(), 10, 5)
    private fun state() = FieldSuggestionReviewState().apply { current(first); seedDefaults(listOf("a")) }
    private fun FieldSuggestionReviewState.request() = beginRequest(listOf("a"), listOf(first))
    private fun roundTrip(state: FieldSuggestionReviewState) = FieldSuggestionReviewState().apply {
        restore(Gson().fromJson(Gson().toJson(state.snapshot()), FieldSuggestionReviewState.Snapshot::class.java))
    }

    @Test fun unfinishedEditAndCheckboxSurviveLateResponse() {
        val state = state(); val request = state.request()
        state.setDraft("a", "아직 입력 중"); state.setChecked("a", false)
        assertTrue(state.receive(request, outcome(next)))
        assertEquals("아직 입력 중", state.editDrafts["a"])
        assertEquals(first, state.current(next)); assertFalse(state.isChecked("a"))
        assertEquals(next, state.candidates("a").single().suggestion)
    }
    @Test fun typingThenRevertingStillCountsAsEditing() {
        val state = state(); state.setDraft("a", first.value); val request = state.request()
        state.setDraft("a", "잠시 수정"); state.setDraft("a", first.value)
        state.receive(request, outcome(next))
        assertEquals(first.value, state.editDrafts["a"])
        assertEquals(1, state.candidates("a").size)
    }
    @Test fun committedEditSurvivesLateResponseAndCanAdoptCandidate() {
        val state = state(); val request = state.request()
        val edit = first.copy(value = "내 값", editedByUser = true)
        state.remember(edit); state.receive(request, outcome(next))
        assertEquals(edit, state.current(next))
        assertTrue(state.adopt(state.candidates("a").single().id))
        assertEquals(next, state.current(first))
    }
    @Test fun resetDuringRequestIsAnExplicitDecision() {
        val state = state(); val request = state.request()
        state.reset(first); state.receive(request, outcome(next))
        assertEquals(first, state.current(next))
        assertEquals(next, state.reset(first))
    }
    @Test fun newRequestDoesNotClobberAnotherFieldsDraftOrChoice() {
        val state = state(); val b = Suggestion("b", "둘", "")
        state.current(b); state.setDraft("b", "둘 수정"); state.setChecked("b", false)
        state.receive(state.request(), outcome(next))
        assertEquals("둘 수정", state.editDrafts["b"]); assertFalse(state.isChecked("b"))
        assertEquals(next, state.reset(first))
    }
    @Test fun reverseResponsesKeepNewestResetAndOlderPaidCandidate() {
        val state = state(); val a = state.request(); val b = state.request()
        val newest = first.copy(value = "최신 B")
        state.receive(b, outcome(newest))
        assertFalse(state.receive(a, outcome(next)))
        assertEquals(newest, state.current(first)); assertEquals(newest, state.reset(first))
        assertTrue(state.candidates("a").single().stale)
        state.adopt(state.candidates("a").single().id)
        assertEquals(next, state.current(first)); assertEquals(newest, state.reset(first))
    }
    @Test fun repeatedCheckpointAndFinalCannotUndoEditsAfterFirstDelivery() {
        val state = state(); val request = state.request()
        state.receive(request, outcome(next)); state.setDraft("a", "응답 본 뒤 수정")
        state.receive(request, outcome(next)); state.receive(request, outcome(next))
        assertEquals("응답 본 뒤 수정", state.editDrafts["a"])
        assertTrue(state.candidates("a").isEmpty())
    }
    @Test fun cumulativeCheckpointAddsOnlyTheNewField() {
        val state = state(); val b = Suggestion("b", "둘", "")
        val request = state.beginRequest(listOf("a", "b"), listOf(first))
        state.receive(request, outcome(next)); state.remember(next.copy(value = "보완을 수정"))
        state.receive(request, outcome(next, b))
        assertEquals("보완을 수정", state.current(next).value); assertEquals(b, state.current(b))
    }
    @Test fun failedRetryPreservesLastSuccessfulAiAndDraft() {
        val state = state(); state.receive(state.request(), outcome(next))
        state.setDraft("a", "초안"); state.receive(state.request(), outcome())
        assertEquals("초안", state.editDrafts["a"]); assertEquals(next, state.reset(first))
    }
    @Test fun freshRestoreRetainsRevisionCandidateReceiptAndLatestReset() {
        val receipt = AiInputReceipt("receipt", "fake", AiProtocol.OPENAI_COMPAT, "sha", listOf("전체 입력"),
            AiInputSource("긴 Brief", mapOf("a" to "지시"), emptyList()), 0, AiInputBudget())
        val paid = next.copy(inputReceiptId = receipt.id)
        val state = state(); val request = state.request()
        state.setDraft("a", "미완성"); state.receive(request, outcome(paid).copy(inputReceipts = listOf(receipt)))
        val restored = roundTrip(state)
        assertEquals("미완성", restored.editDrafts["a"])
        assertEquals(receipt, restored.candidates("a").single().receipt)
        assertFalse(restored.isCurrent(request))
        assertFalse(restored.receive(request, outcome(paid)))
        assertEquals(1, restored.candidates("a").size)
        assertEquals(paid, restored.reset(first))
    }
    @Test fun legacySnapshotUsesRecoveredRunAsResetBaseline() {
        val json = JsonParser.parseString(Gson().toJson(state().snapshot())).asJsonObject
        listOf("latestAi", "revisions", "generation", "received", "candidates").forEach { json.remove(it) }
        val restored = FieldSuggestionReviewState()
        restored.restore(Gson().fromJson(json, FieldSuggestionReviewState.Snapshot::class.java))
        restored.current(next)
        assertEquals(next, restored.reset(first))
        restored.receive(restored.request(), outcome(next.copy(value = "또 보완")))
        assertEquals("또 보완", restored.current(first).value)
    }
    @Test fun differentSessionCannotAttachAResponseToAnotherReview() {
        val state = state(); val request = state.request(); state.clear()
        assertFalse(state.receive(request, outcome(next)))
        assertTrue(state.candidates("a").isEmpty()); assertFalse(state.isCurrent(request))
    }

    private fun spec(key: String = "a") = FieldSpec(key, key, FieldType.TEXT, emptyList(), false, null, "현재")
    @Test fun newOwnersAreIndependentAndLegacyIsNeverAutomaticallyChosen() {
        val a = FieldReviewOwner("character"); val b = FieldReviewOwner("character")
        val first = a.key(-1L)
        assertNotEquals("character:-1:fields", first)
        assertNotEquals(first, b.key(-1L)); assertEquals(first, a.key(-1L))
        assertEquals("character:42:fields", a.key(42L))
        b.choose(first); assertEquals(first, b.key(-1L))
        b.choose("character:-1:fields"); assertEquals("character:-1:fields", b.key(-1L))
        assertFalse(FieldReviewOwner("event").isNewKey(first))
        a.clear(); assertNotEquals(first, a.key(-1L))
    }
    @Test fun removedFieldFailsWholeBatchBeforeAnyWrites() {
        val result = FieldReviewApply.prepare(listOf(first, Suggestion("deleted", "값", "")), listOf(spec()))
        assertTrue(result.values.isEmpty()); assertEquals(1, result.errors.size)
    }
    @Test fun changedOptionsInvalidatePaidValueWithoutCreatingAnOption() {
        val live = spec().copy(type = FieldType.SELECT, options = listOf("지금 옵션"))
        val result = FieldReviewApply.prepare(listOf(first), listOf(live))
        assertTrue(result.values.isEmpty()); assertEquals(1, result.errors.size)
    }
    @Test fun changedCurrentValueOrDefinitionRequiresNewReview() {
        val before = mapOf("a" to spec())
        assertEquals(listOf("a"), FieldReviewApply.changedKeys(listOf("a"), before,
            mapOf("a" to spec().copy(currentValue = "지금 수정"))))
        assertEquals(listOf("a"), FieldReviewApply.changedKeys(listOf("a"), before, emptyMap()))
        assertTrue(FieldReviewApply.changedKeys(listOf("a"), before, before).isEmpty())
    }
}
