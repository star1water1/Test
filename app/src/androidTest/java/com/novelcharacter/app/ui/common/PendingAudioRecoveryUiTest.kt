package com.novelcharacter.app.ui.common

import android.app.Application
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.MainActivity
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.ReviewJournal
import com.novelcharacter.app.speech.*
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual recovery list and MainActivity banner; no microphone or paid transcription. */
@RunWith(AndroidJUnit4::class)
class PendingAudioRecoveryUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val store get() = PendingAudio.store(app)
    private val key = "briefing:cleanup-test:${UUID.randomUUID()}"
    private val id = UUID.randomUUID().toString()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val journal get() = ReviewJournal(File(app.noBackupFilesDir,"creative-reviews"))
    private var recordFile: File? = null
    @Before fun seedOnlyTestRecording() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val item = store.create(id,key)
        store.file(id).writeBytes(byteArrayOf(1,2,3,4))
        store.ready(item,3000)
        recordFile = File(store.root,"records/${ReviewJournal(File(store.root,"records")).fileName("audio:$id")}")
        journal.write("voice:$key",SpeechSession.Snapshot("원문","내가 고친 전사",false,id),null)
        journal.write("creative-input:$key",mapOf("text" to "이미 입력한 글"),null)
    }
    @After fun removeOnlyTestData() {
        scenario?.close()
        recordFile?.delete()
        listOf(store.file(id),store.pcmFile(id),store.encodingFile(id)).forEach { it.delete() }
        listOf("voice:$key","creative-input:$key").forEach { journal.clear(it,journal.revision(it)) }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitUi { it.findViewById<View>(R.id.pending_audio).isShown }
        scenario!!.onActivity { it.findViewById<View>(R.id.pending_audio).performClick() }
    }
    private fun sheet(activity: MainActivity) = activity.supportFragmentManager.findFragmentByTag(PendingAudioRecoverySheet.TAG) as PendingAudioRecoverySheet
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun texts(activity: MainActivity) = all(sheet(activity).requireDialog().window!!.decorView).filterIsInstance<TextView>()
    private fun click(activity: MainActivity, text: String) = texts(activity).single { it.text.toString() == text }.performClick()
    private fun confirmDelete() {
        // Dialog.show() posts its onShow callback. Wait for validated button wiring before clicking.
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { sheet(it).confirmation!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
    }
    private fun awaitUi(predicate: (MainActivity) -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            scenario!!.onActivity { ready = predicate(it) }
            if (ready) return
            Thread.sleep(25)
        } while(android.os.SystemClock.elapsedRealtime() < deadline)
        var observed = ""
        scenario!!.onActivity { a ->
            observed = "banner=${a.findViewById<View>(R.id.pending_audio).visibility}; list=${texts(a).map { it.text }}; confirmation=${sheet(a).confirmation?.isShowing}"
        }
        fail("UI did not reach the expected recovery state: $observed")
    }
    @Test fun cancelThenDeleteRefreshesListAndBannerAndSurvivesRecreationWithoutTouchingText() {
        val voice = journal.read("voice:$key",SpeechSession.Snapshot::class.java)
        val input = journal.read("creative-input:$key",Map::class.java)
        launch()
        awaitUi { texts(it).any { view -> view.text.toString() == "녹음 파일 삭제" } }
        scenario!!.onActivity { a ->
            click(a,"녹음 파일 삭제")
            sheet(a).confirmation!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        assertNotNull(store.read(id));assertTrue(store.file(id).exists())
        scenario!!.onActivity { a ->
            click(a,"녹음 파일 삭제")
        }
        confirmDelete()
        awaitUi { a -> !a.findViewById<View>(R.id.pending_audio).isShown && texts(a).any { it.text.toString() == "보관한 녹음이 없습니다." } }
        assertNull(store.read(id));assertFalse(store.file(id).exists())
        assertEquals(voice,journal.read("voice:$key",SpeechSession.Snapshot::class.java))
        assertEquals(input,journal.read("creative-input:$key",Map::class.java))
        scenario!!.recreate()
        awaitUi { a -> !a.findViewById<View>(R.id.pending_audio).isShown && texts(a).any { it.text.toString() == "보관한 녹음이 없습니다." } }
    }
    @Test fun damagedRecordAndOrphanCanBothBeCleanedFromList() {
        recordFile!!.writeText("broken record")
        launch()
        awaitUi { texts(it).any { view -> view.text.toString() == "읽지 못하는 기록 정리" } }
        scenario!!.onActivity { a ->
            click(a,"읽지 못하는 기록 정리")
        }
        confirmDelete()
        awaitUi { a -> texts(a).none { it.text.toString() == "읽지 못하는 기록 정리" } && texts(a).any { it.text.toString() == "녹음 파일 삭제" } }
        assertTrue(store.file(id).exists())
        scenario!!.onActivity { a ->
            assertTrue(a.findViewById<View>(R.id.pending_audio).isShown)
            click(a,"녹음 파일 삭제")
        }
        awaitUi { sheet(it).confirmation?.isShowing == true }
        confirmDelete()
        awaitUi { a -> !a.findViewById<View>(R.id.pending_audio).isShown && texts(a).any { it.text.toString() == "보관한 녹음이 없습니다." } }
        assertFalse(recordFile!!.exists());assertFalse(store.file(id).exists())
        assertNotNull(journal.read("voice:$key",SpeechSession.Snapshot::class.java))
    }
    @Test fun activeRecordingRefusesDeletionAndKeepsConfirmationOpen() {
        launch()
        awaitUi { texts(it).any { view -> view.text.toString() == "녹음 파일 삭제" } }
        val holder = Any()
        try {
            assertTrue(PendingAudioStore.claim(id,holder))
            scenario!!.onActivity { a ->
                click(a,"녹음 파일 삭제")
            }
            confirmDelete()
            awaitUi { a -> sheet(a).confirmation?.let { prompt ->
                prompt.isShowing && all(prompt.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("정리하지 못했습니다.") }
            } == true }
            assertTrue(store.file(id).exists());assertNotNull(store.read(id))
            scenario!!.onActivity { assertTrue(it.findViewById<View>(R.id.pending_audio).isShown) }
        } finally { PendingAudioStore.end(id,holder) }
    }
}
