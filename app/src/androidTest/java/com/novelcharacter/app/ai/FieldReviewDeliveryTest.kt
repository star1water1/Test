package com.novelcharacter.app.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.ui.character.CharacterViewModel
import com.novelcharacter.app.ui.timeline.EventFieldAiViewModel
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Both real ViewModels, engine callbacks and durable slots; only the paid transport is replaced. */
@RunWith(AndroidJUnit4::class)
class FieldReviewDeliveryTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val owners = mutableListOf<String>()
    private val spec = FieldSpec("mood", "성격", FieldType.TEXT, emptyList(), false, null, "")
    private fun id(prefix: String): Long = (-System.nanoTime()).also { owners.add("$prefix:$it:fields") }
    private fun reply(value: String) = AiResult.Success(
        """{"suggestions":[{"key":"mood","value":"$value","reason":"이유","confidence":"HIGH"}]}""", "fake")
    private class Transport(val first: AiResult) {
        var calls = 0
        val pending = CompletableDeferred<AiResult>()
        fun engine() = CharacterFieldAiSuggester(complete = {
            calls++; if (calls == 1) first else pending.await()
        }, effectiveMaxTokens = { 4096 }, temperatureFor = { null },
            isTemperatureUnsupported = { false }, isImagesUnsupported = { false })
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }
    @After fun cleanOnlyTestRecords() {
        val journal = ReviewJournal(File(app.noBackupFilesDir, "creative-reviews"))
        owners.forEach { owner -> journal.revision(owner)?.let { journal.clear(owner, it) } }
    }

    @Test fun characterLateRefinementPreservesDraftThroughFreshViewModel() = runBlocking(Dispatchers.Main) {
        val id = id("character"); val transport = Transport(reply("처음"))
        val vm = CharacterViewModel(app).apply { fieldSuggesterFactory = { transport.engine() } }
        val context = CharacterAiContext("검증", emptyList(), emptyList(), "", emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(vm.runAiSuggest(context, listOf(spec), true, false, id))
        until { vm.aiSuggestRunning.value == false }
        val first = requireNotNull(vm.aiSuggestResult.value)
        assertEquals("처음", first.outcome.suggestions.single().value)
        assertTrue(vm.runAiSuggest(context, listOf(spec), true, false, id, carryOver = first))
        until { transport.calls == 2 }
        try {
            vm.aiReviewState.setDraft("mood", "미완성 수정")
            vm.aiReviewState.setChecked("mood", false)
            assertFalse(vm.runAiSuggest(context, listOf(spec), true, false, id, carryOver = first))
            vm.clearAiSuggestResult(); assertNotNull(vm.aiSuggestResult.value)
        } finally { transport.pending.complete(reply("보완")) }
        until { vm.aiSuggestRunning.value == false }
        assertEquals("미완성 수정", vm.aiReviewState.editDrafts["mood"])
        assertEquals(1, vm.aiReviewState.candidates("mood").size)
        val restored = CharacterViewModel(app).apply { fieldSuggesterFactory = { error("Recovery must not bill") } }
        assertTrue(restored.recoverAiSuggest(id))
        assertFalse(restored.aiReviewState.isChecked("mood"))
        assertEquals("미완성 수정", restored.aiReviewState.editDrafts["mood"])
        assertEquals("처음", restored.aiReviewState.current(first.outcome.suggestions.single()).value)
        assertEquals("보완", restored.aiReviewState.reset(first.outcome.suggestions.single()).value)
        assertEquals(2, transport.calls)
        assertFalse(restored.aiSuggestResult.value!!.outcome.failures.any { it.contains("이전 실행이 중단") })
        restored.clearAiSuggestResult()
    }

    @Test fun eventLateRefinementAndFailedRetryKeepPaidCandidateAndSelection() = runBlocking(Dispatchers.Main) {
        val id = id("event"); val transport = Transport(reply("처음"))
        val vm = EventFieldAiViewModel(app).apply { suggesterFactory = { EventFieldAiSuggester(transport.engine()) } }
        val context = EventFieldAiSuggester.EventAiContext("검증", "오늘")
        assertTrue(vm.run(context, listOf(spec), id)); until { vm.running.value == false }
        val first = requireNotNull(vm.result.value)
        assertEquals("처음", first.outcome.suggestions.single().value)
        assertTrue(vm.run(context, listOf(spec), id, first)); until { transport.calls == 2 }
        try {
            vm.reviewState.setDraft("mood", "사건 수정")
            vm.reviewState.setChecked("mood", false)
            vm.clearResult(); assertNotNull(vm.result.value)
            assertFalse(vm.run(context, listOf(spec), id, first))
        } finally { transport.pending.complete(reply("보완")) }
        until { vm.running.value == false }
        val restored = EventFieldAiViewModel(app).apply { suggesterFactory = { error("Recovery must not bill") } }
        assertTrue(restored.recover(id))
        assertEquals("사건 수정", restored.reviewState.editDrafts["mood"])
        assertFalse(restored.reviewState.isChecked("mood"))
        assertEquals("보완", restored.reviewState.candidates("mood").single().suggestion.value)
        // A failed explicit retry must still go through the real VM finalization and journal.
        restored.suggesterFactory = { error("Simulated transport setup failure") }
        assertTrue(restored.run(context, listOf(spec), id, restored.result.value))
        until { restored.running.value == false }
        assertEquals("사건 수정", restored.reviewState.editDrafts["mood"])
        assertEquals("보완", restored.reviewState.reset(first.outcome.suggestions.single()).value)
        assertEquals(1, restored.reviewState.candidates("mood").size)
        assertFalse(restored.result.value!!.outcome.failures.isEmpty())
        restored.clearResult()
    }

    @Test fun unsavedEventsKeepSeparateSlotsAndRecoveryIsExplicit() = runBlocking(Dispatchers.Main) {
        val name = "review-test-${java.util.UUID.randomUUID()}"
        val a = EventFieldAiViewModel(app).apply { suggesterFactory = { EventFieldAiSuggester(Transport(reply("A 값")).engine()) } }
        val b = EventFieldAiViewModel(app).apply { suggesterFactory = { EventFieldAiSuggester(Transport(reply("B 값")).engine()) } }
        assertTrue(a.run(EventFieldAiSuggester.EventAiContext(name + " A", ""), listOf(spec), -1L))
        until { a.running.value == false }
        owners.addAll(a.savedNewReviews().filter { it.second.startsWith(name) }.map { it.first })
        assertTrue(b.run(EventFieldAiSuggester.EventAiContext(name + " B", ""), listOf(spec), -1L))
        until { b.running.value == false }
        owners.addAll(b.savedNewReviews().filter { it.second.startsWith(name) }.map { it.first })
        assertEquals("A 값", a.result.value!!.outcome.suggestions.single().value)
        assertEquals("B 값", b.result.value!!.outcome.suggestions.single().value)
        val fresh = EventFieldAiViewModel(app).apply { suggesterFactory = { error("Recovery must not bill") } }
        val choices = fresh.savedNewReviews().filter { it.second.startsWith(name) }
        assertEquals(2, choices.size)
        fresh.chooseNewReview(choices.single { it.second == name + " A" }.first)
        assertTrue(fresh.recover(-1L))
        assertEquals("A 값", fresh.result.value!!.outcome.suggestions.single().value)
        fresh.clearResult(); b.clearResult()
    }
}
