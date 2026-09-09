package com.novelcharacter.app.ai

import com.google.gson.Gson
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.speech.SpeechSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Value(val text: String)
    private fun journal() = ReviewJournal(temporary.root)
    private inline fun rejected(action: () -> Unit) {
        try { action(); fail("Must reject without destroying the previous record") }
        catch (_: IllegalStateException) { }
    }
    @Test fun liveRequestInAnotherEditorCannotBeRecoveredAsInterrupted() {
        val process=ReviewRequestLease()
        val first=Any(); val second=Any()
        assertTrue(process.acquire("same character",first))
        assertTrue(process.heldByOther("same character",second))
        assertFalse(process.acquire("same character",second))
        process.release("same character",second)
        assertTrue(process.heldByOther("same character",second))
        process.release("same character",first)
        assertFalse(process.heldByOther("same character",second))
        assertTrue(process.acquire("same character",second))
    }
    @Test fun differentEntitiesAndFreshProcessHaveIndependentLeases() {
        val process=ReviewRequestLease(); val first=Any(); val second=Any()
        assertTrue(process.acquire("character:1",first))
        assertFalse(process.acquire("character:1",first)) // Double tap by the same owner.
        assertTrue(process.acquire("character:2",second))
        assertFalse(ReviewRequestLease().heldByOther("character:1",second))
    }
    @Test fun newStoreRestoresExactLongUnicodeDraft() {
        val text="귀족은 아니다. 키는 165~170 정도?\n미완성 입력…".repeat(2000)
        journal().write("character:-1:fields",Value(text),null)
        assertEquals(text,journal().read("character:-1:fields",Value::class.java)!!.value.text)
    }
    @Test fun secondEditorCannotOverwriteOrDeleteNewerReview() {
        val first=journal().write("one",Value("첫 응답"),null)
        val latest=journal().write("one",Value("사용자가 수정"),first)
        rejected { journal().write("one",Value("오래된 창"),first) }
        rejected { journal().clear("one",first) }
        rejected { journal().write("one",Value("새 창"),null) }
        assertEquals("사용자가 수정",journal().read("one",Value::class.java)!!.value.text)
        journal().clear("one",latest)
        assertNull(journal().read("one",Value::class.java))
    }
    @Test fun ownersAndFeaturesAreIsolatedIncludingUnsavedEntities() {
        listOf("character:-1:fields","event:-1:fields","character:1:narrative","character:1:narrative-bulk")
            .forEach { journal().write(it,Value(it),null) }
        assertEquals("event:-1:fields",journal().read("event:-1:fields",Value::class.java)!!.value.text)
        assertNull(journal().read("character:2:narrative",Value::class.java))
    }
    @Test fun truncatedTemporaryFileNeverReplacesLastCompleteResponse() {
        journal().write("one",Value("받은 응답"),null)
        File(temporary.root,"review-interrupted.tmp").writeText("{\"partial\"")
        assertEquals("받은 응답",journal().read("one",Value::class.java)!!.value.text)
    }
    @Test fun corruptedContentIsReportedAndNeverSilentlyReplaced() {
        val revision=journal().write("one",Value("original"),null)
        val file=temporary.root.listFiles()!!.single()
        file.writeText(file.readText().replace("original","tampered"))
        val damaged=file.readText()
        rejected { journal().read("one",Value::class.java) }
        rejected { journal().write("one",Value("replacement"),revision) }
        rejected { journal().clear("one",revision) }
        assertEquals(damaged,file.readText())
    }
    @Test fun unsupportedVersionIsNotTreatedAsNoSavedReview() {
        journal().write("one",Value("preserve"),null)
        val file=temporary.root.listFiles()!!.single()
        file.writeText(file.readText().replace("\"version\":1","\"version\":999"))
        rejected { journal().read("one",Value::class.java) }
        assertTrue(file.exists())
    }
    @Test fun failedSerializationPreservesPreviousAtomicRecord() {
        val revision=journal().write("one",Value("preserve"),null)
        try { journal().write("one",mapOf("invalid" to Double.NaN),revision); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(revision,journal().read("one",Value::class.java)!!.revision)
    }
    @Test fun endingReviewRemovesOnlyItsAssociatedInputsAndTranscripts() {
        val revision=journal().write("review",Value("response"),null)
        journal().write("creative-input:field-refine:session:a",Value("instruction"),null)
        journal().write("voice:field-refine:session:a",Value("transcript"),null)
        journal().write("voice:field-refine:another:a",Value("other review"),null)
        journal().clear("review",revision,listOf("field-refine:session:a"))
        assertNull(journal().read("review",Value::class.java))
        assertNull(journal().read("creative-input:field-refine:session:a",Value::class.java))
        assertNull(journal().read("voice:field-refine:session:a",Value::class.java))
        assertEquals("other review",journal().read("voice:field-refine:another:a",Value::class.java)!!.value.text)
    }
    @Test fun fieldReviewRoundTripRetainsUncheckedRawDraftAndFirstAiOriginal() {
        val state=FieldSuggestionReviewState()
        val first=Suggestion("a","최초 제안","이유",sourceEvidence="원문",suggestionNote="메모")
        state.seedDefaults(listOf("a","b")); state.setChecked("b",false)
        state.current(first); state.remember(first.copy(value="손수 고친 값"))
        state.editDrafts["a"]="고치다 만 글"; state.instructions["a"]="범위는 유지"
        journal().write("state",state.snapshot(),null)
        val recovered=FieldSuggestionReviewState()
        recovered.restore(journal().read("state",FieldSuggestionReviewState.Snapshot::class.java)!!.value)
        recovered.seedDefaults(listOf("a","b"))
        assertEquals(state.sessionId,recovered.sessionId)
        assertFalse(recovered.isChecked("b")); assertTrue(recovered.isChecked("a"))
        assertEquals("고치다 만 글",recovered.editDrafts["a"])
        assertEquals("손수 고친 값",recovered.current(first.copy(value="후속 제안")).value)
        assertEquals(first,recovered.reset(first.copy(value="후속 제안")))
        assertEquals("범위는 유지",recovered.instructions["a"])
    }
    @Test fun narrativeReviewRoundTripRetainsCandidateAndPendingChoices() {
        val state=NarrativeReviewState()
        state.seedDefaults(listOf(1L,2L)); state.setChecked(2L,false); state.choose(1L,1)
        state.edits["1:1"]="긴 글\n".repeat(5000); state.editing.add("1:1"); state.expanded.add(1L)
        state.instructions[1L]="불확실한 표현 유지"; state.resumeSelection.add(5L)
        journal().write("narrative",state.snapshot(),null)
        val recovered=NarrativeReviewState()
        recovered.restore(journal().read("narrative",NarrativeReviewState.Snapshot::class.java)!!.value)
        assertEquals(state.snapshot(),recovered.snapshot())
        recovered.seedDefaults(listOf(1L,2L,5L))
        assertFalse(recovered.isChecked(2L)); assertFalse(recovered.isChecked(5L))
        assertEquals(1,recovered.chosen(1L))
    }
    @Test fun bulkResumeExcludesCompletedUncertainUnselectedAndDuplicateIds() {
        assertEquals(listOf(4L,2L),NarrativeResumePlan.select(listOf(1L,4L,4L,2L,3L,5L),
            listOf(1L),listOf(3L),setOf(1L,2L,3L,4L,99L)))
    }
    @Test fun interruptedSpeechRestoreNeverRestartsPaidRequest() {
        val session=SpeechSession()
        session.startRecording(true); session.audioReady(); session.beginTranscription()
        journal().write("speech",session.snapshot(),null)
        val restored=SpeechSession()
        restored.restore(journal().read("speech",SpeechSession.Snapshot::class.java)!!.value)
        assertEquals(SpeechSession.Phase.ERROR,restored.phase)
        assertEquals("",restored.original)
    }
    @Test fun speechRoundTripRetainsRawAndEditedTextSeparately() {
        val session=SpeechSession(); session.deviceResult("귀족이 아니야"); session.draft="귀족은 아니다"
        journal().write("speech",session.snapshot(),null)
        val restored=SpeechSession()
        restored.restore(journal().read("speech",SpeechSession.Snapshot::class.java)!!.value)
        assertEquals("귀족이 아니야",restored.original)
        assertEquals("귀족은 아니다",restored.draft)
        assertEquals(SpeechSession.Phase.REVIEW,restored.phase)
    }
    @Test fun completedChunkIsDurableBeforeNextRequestCanBeInterrupted() = runBlocking {
        var calls=0
        var revision: String?=null
        val engine=CharacterFieldAiSuggester(complete={
            if (++calls==2) {
                val saved=journal().read("chunks",SuggestOutcome::class.java)!!.value
                assertEquals("첫 응답",saved.suggestions.single().value)
                assertTrue(saved.missing.any { it.fieldKey=="f16" })
                throw CancellationException("process boundary simulation")
            }
            AiResult.Success("""{"suggestions":[{"key":"f1","value":"첫 응답"}]}""","test")
        },effectiveMaxTokens={4096},temperatureFor={null},isTemperatureUnsupported={false},isImagesUnsupported={false})
        val context=CharacterAiContext("이름",emptyList(),emptyList(),"",emptyList(),emptyList(),emptyList(),emptyList())
        val targets=(1..16).map { FieldSpec("f$it","같은 이름",FieldType.TEXT,emptyList(),false,null,"") }
        try {
            engine.suggest(context,targets,onCheckpoint={ revision=journal().write("chunks",it,revision) }) { "실패" }
            fail("Expected interruption")
        } catch (_: CancellationException) { }
        assertEquals(2,calls)
        assertEquals("첫 응답",journal().read("chunks",SuggestOutcome::class.java)!!.value.suggestions.single().value)
    }
    @Test fun contextAndFieldSpecsKeepTheirTypesAfterRecovery() {
        data class Request(val context: CharacterAiContext, val targets: List<FieldSpec>)
        val request=Request(CharacterAiContext("인물",listOf("별칭"),emptyList(),"메모",
            listOf("성격" to "과묵"),emptyList(),emptyList(),emptyList(),briefing="아마 귀족은 아니다"),
            listOf(FieldSpec("f","등급",FieldType.SELECT,listOf("S","A"),false,null,"A")))
        val json=Gson().toJson(request)
        assertEquals(request,Gson().fromJson(json,Request::class.java))
    }
}
