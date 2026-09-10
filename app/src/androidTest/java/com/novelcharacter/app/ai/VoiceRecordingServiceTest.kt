package com.novelcharacter.app.ai

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.MainActivity
import com.novelcharacter.app.speech.*
import com.novelcharacter.app.ui.common.VoiceInputViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real AudioRecord/service on an emulator. This proves lifecycle, not physical microphone quality. */
@RunWith(AndroidJUnit4::class)
class VoiceRecordingServiceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private val store get()=PendingAudio.store(app)
    private var activity: ActivityScenario<MainActivity>?=null
    private lateinit var config: SpeechConfig
    private val owners=mutableListOf<String>()
    private val holders=mutableListOf<ViewModelStore>()
    @Before fun visiblePermissionGrantedHost() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
            .use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        config=SpeechSettings(app).read();SpeechSettings(app).save(SpeechConfig(providerId="fake-unused",model="fake-unused"))
        activity=ActivityScenario.launch(MainActivity::class.java)
    }
    @After fun cleanupOnlyTestData() {
        runBlocking(Dispatchers.Main) {
        VoiceRecordingService.state?.takeIf {it.running && it.inputKey in owners}?.let {VoiceRecordingService.stop(app,it.id)}
        withTimeout(15_000) {while(VoiceRecordingService.state?.let {it.running && it.inputKey in owners}==true) delay(25)}
        holders.forEach {it.clear()}
        store.catalog().items.filter {it.record.inputKey in owners}.forEach {store.cleanup(store.release(it))}
        val journal=ReviewJournal(File(app.noBackupFilesDir,"creative-reviews"))
        owners.forEach {key->journal.revision("voice:$key")?.let {journal.clear("voice:$key",it)}}
        if(::config.isInitialized) SpeechSettings(app).save(config)
        }
        // close() waits for DESTROYED. finish() alone let the next API 35 launch race the old task.
        activity?.close()
    }
    private fun model(key: String="briefing:service-test:${UUID.randomUUID()}"): VoiceInputViewModel {
        owners.add(key)
        return VoiceInputViewModel(app).also {vm->
            holders.add(ViewModelStore().apply {put("voice",vm)})
            vm.bind(key)
        }
    }
    @Test fun destroyedEditorReattachesToSameCaptureAndExplicitStopNeverRequestsTranscription()=runBlocking(Dispatchers.Main) {
        val first=model();first.start(true)
        val id=first.session.sessionId
        withTimeout(15_000) {while((VoiceRecordingService.state?.durationMs ?: 0)<2000) delay(25)}
        assertEquals(id,VoiceRecordingService.state?.id)
        first.leaveVisibleScreen();holders.first().clear()
        val key=store.read(id)!!.record.inputKey
        val restored=model(key)
        assertEquals(id,restored.session.sessionId);assertTrue(restored.isServiceRecording())
        val before=VoiceRecordingService.state!!.durationMs
        withTimeout(15_000) {while(VoiceRecordingService.state!!.durationMs<=before) delay(25)}
        var requests=0;restored.transcribeFile={_,_,_,_,_->requests++;SpeechResult.Success("unexpected","fake")}
        VoiceRecordingService.stop(app,id) // Same command as notification stop; never queues a paid request.
        withTimeout(15_000) {while(VoiceRecordingService.state!!.running || restored.session.phase==SpeechSession.Phase.RECORDING) delay(25)}
        assertEquals(0,requests)
        val saved=store.read(id)!!
        assertEquals(PendingAudioStore.Phase.CAPTURED,saved.record.phase)
        assertTrue(saved.record.durationMs>=3000)
        assertEquals(saved.record.checkpointBytes,store.pcmFile(id).length())
        assertFalse(store.file(id).exists());assertTrue(restored.hasAudio())
    }
    @Test fun serviceShutdownPreservesPrefixAndMarksInterruption()=runBlocking(Dispatchers.Main) {
        val vm=model();vm.start(true);val id=vm.session.sessionId
        withTimeout(15_000) {while((VoiceRecordingService.state?.durationMs ?: 0)<1000) delay(25)}
        app.stopService(Intent(app,VoiceRecordingService::class.java))
        withTimeout(15_000) {while(VoiceRecordingService.state!!.running) delay(25)}
        val item=store.read(id)!!
        assertEquals(PendingAudioStore.Phase.INTERRUPTED,item.record.phase)
        assertTrue(item.record.interruption!!.contains("시스템"))
        assertTrue(store.pcmFile(id).length()>=32000)
        // Delivery of a restart without a live launch reservation cannot start the microphone.
        app.startService(Intent(app,VoiceRecordingService::class.java))
        delay(500)
        assertFalse(VoiceRecordingService.state!!.running)
        assertEquals(item,store.read(id))
    }
    @Test fun nextInputRecordingDoesNotLeavePreviousEditorShowingCapture()=runBlocking(Dispatchers.Main) {
        val first=model();first.start(true)
        withTimeout(15_000) {while((VoiceRecordingService.state?.durationMs ?: 0)<1000) delay(25)}
        first.stop(false)
        withTimeout(15_000) {while(VoiceRecordingService.state!!.running) delay(1)}
        val second=model();second.start(true)
        withTimeout(15_000) {while(first.session.phase==SpeechSession.Phase.RECORDING || (VoiceRecordingService.state?.durationMs ?: 0)<1000) delay(25)}
        assertEquals(SpeechSession.Phase.READY,first.session.phase)
        assertEquals(second.session.sessionId,VoiceRecordingService.state!!.id)
        assertNotEquals(first.session.sessionId,second.session.sessionId)
        assertTrue(first.hasAudio());assertTrue(second.isServiceRecording())
    }
    @Test fun deniedPermissionCreatesNoRecordingAndStartsNoService()=runBlocking(Dispatchers.Main) {
        val vm=model();vm.start(false)
        assertEquals(SpeechError.PERMISSION,vm.session.error)
        assertNull(store.read(vm.session.sessionId))
        assertFalse(vm.isServiceRecording())
    }
}
