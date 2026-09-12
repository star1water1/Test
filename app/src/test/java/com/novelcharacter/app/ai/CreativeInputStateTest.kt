package com.novelcharacter.app.ai

import com.google.gson.Gson
import com.novelcharacter.app.speech.SpeechSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreativeInputStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "briefing:7"
    private fun delivery(id: String, text: String, owner: String = key) =
        CreativeInputState.Delivery(owner, id, text)
    private fun journal() = ReviewJournal(temporary.root)

    @Test fun duplicateDeliveryDoesNotAppendTwice() {
        val state = CreativeInputState(key, null, "직접 입력")
        assertTrue(state.accept(delivery("a", "전사")) { true })
        assertTrue(state.accept(delivery("a", "전사")) { true })
        assertEquals("직접 입력\n전사", state.snapshot.text)
    }

    @Test fun identicalTextFromDifferentRecordingsIsNotDeduplicated() {
        val state = CreativeInputState(key, null)
        state.accept(delivery("a", "같은 말")) { true }
        state.accept(delivery("b", "같은 말")) { true }
        assertEquals("같은 말\n같은 말", state.snapshot.text)
    }

    @Test fun insertionDoesNotTrimExistingWhitespaceOrTranscript() {
        val old = "  기존 글\n \n"
        val transcript = "  귀족은 아니다.  \n"
        val state = CreativeInputState(key, null, old)
        state.accept(delivery("a", transcript)) { true }
        assertEquals(old + "\n" + transcript, state.snapshot.text)
    }

    @Test fun deletionOfAcceptedTextDoesNotAllowTheSessionToComeBack() {
        val state = CreativeInputState(key, null)
        state.accept(delivery("a", "전사")) { true }
        state.edit("사용자가 다시 쓴 글") { true }
        state.accept(delivery("a", "전사 수정본")) { true }
        assertEquals("사용자가 다시 쓴 글", state.snapshot.text)
    }

    @Test fun writeFailureLeavesTextAndReceiptUnchanged() {
        val state = CreativeInputState(key, null, "보존")
        assertFalse(state.accept(delivery("a", "전사")) { false })
        assertEquals("보존", state.snapshot.text)
        assertTrue(state.snapshot.acceptedSpeechSessions!!.isEmpty())
        assertTrue(state.accept(delivery("a", "전사")) { true })
        assertEquals("보존\n전사", state.snapshot.text)
    }

    @Test fun failedEditDoesNotReplaceTheDurableCheckpoint() {
        val state = CreativeInputState(key, null, "저장된 글")
        assertFalse(state.edit("미저장 글") { false })
        assertEquals("저장된 글", state.snapshot.text)
    }

    @Test fun wrongOwnerAndInvalidDeliveryAreRejectedWithoutWrites() {
        val state = CreativeInputState(key, null)
        var writes = 0
        listOf(delivery("a", "남의 전사", "briefing:8"), delivery("", "전사"), delivery("a", " "))
            .forEach { assertFalse(state.accept(it) { writes++; true }) }
        assertEquals(0, writes)
        assertEquals("", state.snapshot.text)
    }

    @Test fun legacyInputPayloadLoadsWithoutInventingReceipts() {
        val saved = Gson().fromJson("""{"text":"구버전 원문","pending":true}""",
            CreativeInputState.Snapshot::class.java)
        val state = CreativeInputState(key, saved)
        assertEquals("구버전 원문", state.snapshot.text)
        assertTrue(state.snapshot.pending)
        assertEquals(key, state.snapshot.inputKey)
        assertTrue(state.snapshot.acceptedSpeechSessions!!.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun payloadCannotMoveBetweenOwners() {
        CreativeInputState("briefing:8", CreativeInputState(key, null).snapshot)
    }

    @Test fun crashAfterInputWriteBeforeVoiceCleanupIsIdempotent() {
        val voice = SpeechSession().apply { deviceResult("전사 원문"); draft = "확인한 최종 글" }
        journal().write("voice:$key", voice.snapshot(), null)
        val state = CreativeInputState(key, null, "기존 Brief")
        state.accept(delivery(voice.sessionId, voice.draft)) {
            journal().write("creative-input:$key", it, null); true
        }
        // Fresh objects represent restart before the voice record was cleared.
        val entry = journal().read("creative-input:$key", CreativeInputState.Snapshot::class.java)!!
        val restored = CreativeInputState(key, entry.value)
        val restoredVoice = SpeechSession().apply {
            restore(journal().read("voice:$key", SpeechSession.Snapshot::class.java)!!.value)
        }
        restored.accept(delivery(restoredVoice.sessionId, restoredVoice.draft)) {
            journal().write("creative-input:$key", it, entry.revision); true
        }
        assertEquals("기존 Brief\n확인한 최종 글", restored.snapshot.text)
        assertEquals("전사 원문", restoredVoice.original)
    }

    @Test fun longUnicodeAndReceiptsSurviveEditAndFreshStore() {
        val text = "부정·범위 165~170 🐈\n마지막 자기정정. ".repeat(2000)
        val state = CreativeInputState(key, null)
        state.accept(delivery("a", text)) { true }
        state.edit(text + "직접 수정") { true }
        journal().write(key, state.snapshot, null)
        val restored = CreativeInputState(key, journal().read(key, CreativeInputState.Snapshot::class.java)!!.value)
        restored.accept(delivery("a", text)) { true }
        assertEquals(text + "직접 수정", restored.snapshot.text)
    }

    @Test fun originalAndAcceptedDraftRemainAfterSourceCleanupAndLaterInputEdit() {
        val state = CreativeInputState(key, null)
        state.accept(CreativeInputState.Delivery(key, "a", "선택한 교정과 직접 수정", "STT 원문")) { true }
        state.edit("나중에 다시 쓴 Brief") { true }
        journal().write(key, state.snapshot, null)
        val restored = CreativeInputState(key, journal().read(key, CreativeInputState.Snapshot::class.java)!!.value)
        val receipt = restored.snapshot.acceptedSpeechSessions!!.getValue("a")
        assertEquals("STT 원문", receipt.original)
        assertEquals("선택한 교정과 직접 수정", receipt.finalText)
        assertEquals("나중에 다시 쓴 Brief", restored.snapshot.text)
    }

    @Test fun staleEditorCannotAcknowledgeAnUnwrittenDelivery() {
        val first = CreativeInputState(key, null)
        val revision = journal().write(key, first.snapshot, null)
        val second = CreativeInputState(key, journal().read(key, CreativeInputState.Snapshot::class.java)!!.value)
        first.edit("첫 창 최신 수정") { journal().write(key, it, revision); true }
        assertFalse(second.accept(delivery("a", "전사")) {
            try { journal().write(key, it, revision); true } catch (_: IllegalStateException) { false }
        })
        assertEquals("첫 창 최신 수정", journal().read(key, CreativeInputState.Snapshot::class.java)!!.value.text)
        assertTrue(second.snapshot.acceptedSpeechSessions!!.isEmpty())
    }

    @Test fun duplicateStillRequiresSuccessfulDurabilityCheck() {
        val state = CreativeInputState(key, null)
        state.accept(delivery("a", "전사")) { true }
        assertFalse(state.accept(delivery("a", "전사")) { false })
    }

    @Test fun newDraftsAndFeaturesHaveIndependentOwnersAndReceipts() {
        val keys = listOf("briefing:draft:a", "briefing:draft:b", "briefing:1", "field-refine:review:a")
        keys.forEach { owner ->
            val state = CreativeInputState(owner, null)
            state.accept(delivery("same-id", owner, owner)) { journal().write(owner, it, null); true }
        }
        keys.forEach { owner ->
            assertEquals(owner, journal().read(owner, CreativeInputState.Snapshot::class.java)!!.value.text)
        }
    }

    @Test fun catalogFindsBlankVoiceOwnersAndLegacyBriefWithoutDeletingAnything() {
        listOf("creative-input:briefing:-1", "creative-input:briefing:draft:a", "voice:briefing:draft:a")
            .forEach { journal().write(it, CreativeInputState.Snapshot("", false), null) }
        val before = temporary.root.listFiles()!!.associate { it.name to it.readText() }
        assertEquals(listOf("creative-input:briefing:-1", "creative-input:briefing:draft:a"),
            journal().owners("creative-input:briefing:").owners)
        assertEquals(before, temporary.root.listFiles()!!.associate { it.name to it.readText() })
    }

    @Test fun damagedRecordDoesNotHideOtherRecoverableBriefs() {
        journal().write("creative-input:briefing:draft:a", CreativeInputState.Snapshot("보존", false), null)
        val damaged = java.io.File(temporary.root, "damaged.json").apply { writeText("{broken") }
        val catalog = journal().owners("creative-input:briefing:")
        assertEquals(listOf("creative-input:briefing:draft:a"), catalog.owners)
        assertEquals(1, catalog.unreadableCount)
        assertEquals("{broken", damaged.readText())
    }

    @Test fun legacySpeechIdentityIsCheckpointedBeforeReceiptAndSurvivesRestart() {
        val old = Gson().fromJson("""{"original":"원문","draft":"수정본","awaitingResponse":false}""",
            SpeechSession.Snapshot::class.java)
        val voice = SpeechSession().apply { restore(old) }
        journal().write("voice", voice.snapshot(), null)
        val restored = SpeechSession().apply { restore(journal().read("voice", SpeechSession.Snapshot::class.java)!!.value) }
        assertEquals(voice.sessionId, restored.sessionId)
        assertEquals("원문", restored.original)
        assertEquals("수정본", restored.draft)
    }

    @Test fun deniedStartKeepsIdentityAndEveryNewRecordingChangesIt() {
        val voice = SpeechSession()
        val first = voice.sessionId
        assertFalse(voice.startRecording(false))
        assertEquals(first, voice.sessionId)
        assertTrue(voice.startRecording(true))
        val recording = voice.sessionId
        assertNotEquals(first, recording)
        voice.audioReady(); voice.beginTranscription()
        assertEquals(recording, voice.sessionId)
        voice.reset()
        assertNotEquals(recording, voice.sessionId)
    }
}
