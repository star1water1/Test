package com.novelcharacter.app.ai

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.speech.SpeechSession
import com.novelcharacter.app.ui.common.NaturalLanguageInputModel
import com.novelcharacter.app.ui.common.VoiceInputViewModel
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android ViewModels/slots, without microphone, network calls or domain writes. */
@RunWith(AndroidJUnit4::class)
class CreativeInputDeliveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val owners = mutableSetOf<String>()
    private fun journal() = ReviewJournal(File(app.noBackupFilesDir, "creative-reviews"))
    private fun key(): String = "briefing:draft:${UUID.randomUUID()}".also {
        owners.add("creative-input:$it"); owners.add("voice:$it")
    }
    private fun input(state: SavedStateHandle = SavedStateHandle()) = NaturalLanguageInputModel(app, state)
    private fun voice(key: String) = VoiceInputViewModel(app).apply { bind(key) }
    private fun onMain(test: () -> Unit) = instrumentation.runOnMainSync(test)

    @After fun cleanOnlyTestRecords() {
        owners.forEach { owner -> journal().revision(owner)?.let { journal().clear(owner, it) } }
    }

    @Test fun crashBetweenAppendAndCleanupCannotAppendAgain() = onMain {
        val key = key()
        val firstInput = input()
        assertTrue(firstInput.prepare(key, "Brief"))
        val firstVoice = voice(key)
        firstVoice.session.deviceResult("전사 원문")
        firstVoice.editDraft("사용자가 확인한 수정본")
        assertTrue(firstInput.accept(CreativeInputState.Delivery(key,
            firstVoice.session.sessionId, firstVoice.session.draft, firstVoice.session.original)))
        // Recreate before the source is cleared, as after process death at this boundary.
        val restoredInput = input()
        val restoredVoice = voice(key)
        assertEquals("전사 원문", restoredVoice.session.original)
        assertEquals("사용자가 확인한 수정본", restoredVoice.session.draft)
        assertTrue(restoredVoice.acceptInto(restoredInput))
        assertEquals("Brief\n사용자가 확인한 수정본", restoredInput.values[key])
        assertFalse(restoredVoice.acceptInto(restoredInput))
        val receipt = journal().read("creative-input:$key", CreativeInputState.Snapshot::class.java)!!
            .value.acceptedSpeechSessions!!.values.single()
        assertEquals("전사 원문", receipt.original)
        assertEquals("사용자가 확인한 수정본", receipt.finalText)
    }

    @Test fun staleVoiceCannotAppendOverAnotherEditorsTranscript() = onMain {
        val key = key()
        val stale = voice(key).apply { session.deviceResult("전사"); editDraft("이전 수정본") }
        val latest = voice(key).apply { editDraft("다른 창의 최신 수정본") }
        val target = input()
        assertFalse(stale.acceptInto(target))
        assertTrue(latest.acceptInto(target))
        assertEquals("다른 창의 최신 수정본", target.values[key])
    }

    @Test fun staleInputCannotConsumeVoiceAndLatestEditorCanRetry() = onMain {
        val key = key()
        val stale = input().apply { assertTrue(prepare(key, "이전 Brief")) }
        val latest = input().apply { assertTrue(prepare(key, "")); edit(key, "최신 사용자 수정") }
        val source = voice(key).apply { session.deviceResult("전사"); editDraft("전사 수정본") }
        assertFalse(source.acceptInto(stale))
        assertEquals("전사", source.session.original)
        assertEquals("전사 수정본", journal().read("voice:$key", SpeechSession.Snapshot::class.java)!!.value.draft)
        assertTrue(source.acceptInto(latest))
        assertEquals("최신 사용자 수정\n전사 수정본", latest.values[key])
    }

    @Test fun legacyVoiceGetsDurableIdentityBeforeDelivery() = onMain {
        val key = key()
        journal().write("voice:$key", mapOf("original" to "옛 원문", "draft" to "옛 수정본",
            "awaitingResponse" to false), null)
        val source = voice(key)
        val checkpoint = journal().read("voice:$key", SpeechSession.Snapshot::class.java)!!.value
        assertEquals(source.session.sessionId, checkpoint.sessionId)
        val target = input()
        assertTrue(source.acceptInto(target))
        assertEquals("옛 수정본", target.values[key])
    }

    @Test fun restoredSelectionIsStableButFreshEditorDoesNotInheritAnotherBrief() = onMain {
        val key = key()
        val handle = SavedStateHandle()
        val first = input(handle)
        assertTrue(first.chooseNewCharacterBrief(key))
        first.edit(key, "캐릭터 A")
        val restored = input(SavedStateHandle(mapOf("newCharacterBrief" to handle.get<String>("newCharacterBrief"))))
        assertEquals(key, restored.resolvedKey("briefing:-1"))
        assertNull(input().resolvedKey("briefing:-1"))
        val otherKey = key()
        val other = input().apply { assertTrue(chooseNewCharacterBrief(otherKey)); edit(otherKey, "캐릭터 B") }
        assertEquals(otherKey, other.resolvedKey("briefing:-1"))
        assertTrue(restored.prepare(key, ""))
        assertEquals("캐릭터 A", restored.values[key])
    }

    @Test fun twoSessionsWithIdenticalTextBothAppendThroughTheRealSlots() = onMain {
        val key = key()
        val target = input()
        repeat(2) {
            val source = voice(key).apply { session.deviceResult("같은 말"); editDraft("같은 말") }
            assertTrue(source.acceptInto(target))
        }
        assertEquals("같은 말\n같은 말", target.values[key])
    }
}
