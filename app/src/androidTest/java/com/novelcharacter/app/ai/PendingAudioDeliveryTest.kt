package com.novelcharacter.app.ai

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.speech.*
import com.novelcharacter.app.ui.common.NaturalLanguageInputModel
import com.novelcharacter.app.ui.common.VoiceInputViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real storage/ViewModels with a fake paid boundary; no microphone, server or domain writes. */
@RunWith(AndroidJUnit4::class)
class PendingAudioDeliveryTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private val store get()=PendingAudio.store(app)
    private val journal get()=ReviewJournal(File(app.noBackupFilesDir,"creative-reviews"))
    private val records=mutableListOf<Pair<String,String>>()
    private lateinit var oldConfig:SpeechConfig
    private suspend fun finished(vm: VoiceInputViewModel) = withTimeout(20_000) {
        while(vm.session.phase==SpeechSession.Phase.TRANSCRIBING || PendingAudioStore.isActive(vm.session.sessionId)) delay(20)
    }
    @Before fun configure() {
        oldConfig=SpeechSettings(app).read()
        SpeechSettings(app).save(SpeechConfig(providerId="fake-first",model="fake-model"))
    }
    @After fun cleanOnlyTestRecords() {
        records.forEach {(id,key)->
            store.read(id)?.let {store.cleanup(store.release(it))}
            listOf("voice:$key","creative-input:$key").forEach {owner->journal.revision(owner)?.let {journal.clear(owner,it)}}
        }
        SpeechSettings(app).save(oldConfig)
    }
    private fun ready():PendingAudioStore.Item {
        val id=UUID.randomUUID().toString();val key="briefing:draft:${UUID.randomUUID()}"
        records.add(id to key)
        val record=store.create(id,key)
        store.file(id).writeBytes(ByteArray(5000) {(it%250).toByte()})
        return store.ready(record,480_000)
    }
    private fun model(item:PendingAudioStore.Item)=VoiceInputViewModel(app).apply {bind(item.record.inputKey,item.record.id)}

    @Test fun successRestoresAudioAndEditedTextUntilExplicitAppend()=runBlocking(Dispatchers.Main) {
        val item=ready();var calls=0
        val first=model(item).apply {transcribeFile={_,_,_,_,_->calls++;SpeechResult.Success("STT 원문","fake")}}
        first.transcribe();finished(first)
        assertEquals(1,calls);assertTrue(store.file(item.record.id).exists())
        first.editDraft("확인한 수정본")
        val restored=model(store.read(item.record.id)!!)
        assertEquals("STT 원문",restored.session.original)
        assertEquals("확인한 수정본",restored.session.draft)
        assertTrue(restored.hasAudio());assertEquals(1,calls)
        val input=NaturalLanguageInputModel(app,SavedStateHandle())
        assertTrue(restored.acceptInto(input))
        assertEquals("확인한 수정본",input.values[item.record.inputKey])
        assertNull(store.read(item.record.id));assertFalse(store.file(item.record.id).exists())
        assertFalse(restored.acceptInto(input))
    }
    @Test fun failuresKeepBytesAndExplicitRetryUsesCurrentProvider()=runBlocking(Dispatchers.Main) {
        val item=ready();val expected=store.file(item.record.id).readBytes();val vm=model(item)
        listOf(SpeechError.NETWORK,SpeechError.SERVER,SpeechError.RATE_LIMIT,SpeechError.AUTH,
            SpeechError.CONFIG,SpeechError.NO_PROVIDER,SpeechError.NO_KEY,SpeechError.UNSUPPORTED).forEach {error->
            vm.transcribeFile={_,_,_,_,_->SpeechResult.Failure(error)}
            vm.transcribe();finished(vm)
            assertArrayEquals(expected,store.file(item.record.id).readBytes())
            assertTrue(vm.hasAudio())
        }
        SpeechSettings(app).save(SpeechConfig(providerId="fake-second",model="another-model"))
        vm.transcribeFile={config,_,_,_,_->
            assertEquals("fake-second",config.providerId)
            assertEquals("another-model",config.model)
            SpeechResult.Success("새 전사","fake")
        }
        vm.transcribe();finished(vm)
        assertEquals("새 전사",vm.session.original)
        assertArrayEquals(expected,store.file(item.record.id).readBytes())
    }
    @Test fun cancellationAndViewModelClearPreserveAudioAndAllowExplicitRetry()=runBlocking(Dispatchers.Main) {
        val item=ready();val vm=model(item);var calls=0
        vm.transcribeFile={_,_,_,_,_->calls++;awaitCancellation()}
        vm.transcribe();vm.transcribe()
        withTimeout(20_000) {while(calls<1) delay(20)}
        assertEquals(1,calls)
        vm.cancelTranscription();finished(vm)
        assertTrue(vm.hasAudio())
        assertEquals(PendingAudioStore.Phase.READY,store.read(item.record.id)!!.record.phase)
        vm.transcribe()
        withTimeout(20_000) {while(calls<2) delay(20)}
        val holder=ViewModelStore();holder.put("voice",vm);holder.clear()
        finished(vm)
        assertFalse(PendingAudioStore.isActive(item.record.id))
        assertTrue(store.file(item.record.id).exists())
        val restored=model(store.read(item.record.id)!!)
        assertEquals(2,calls);assertTrue(restored.hasAudio())
    }
    @Test fun processDeathInFlightIsVisibleWithoutAutomaticRequest()=runBlocking(Dispatchers.Main) {
        val item=store.beginAttempt(ready(),"former-provider","former-model")
        val vm=model(item)
        assertTrue(vm.hasAudio())
        assertTrue(vm.notice.contains("과금"))
        assertEquals(item.record.attemptId,store.read(item.record.id)!!.record.attemptId)
        assertEquals(PendingAudioStore.Phase.TRANSCRIBING,store.read(item.record.id)!!.record.phase)
    }
    @Test fun transcriptWriteFailureKeepsAudioAndRefusesConsumption()=runBlocking(Dispatchers.Main) {
        val item=ready();val vm=model(item)
        vm.transcribeFile={_,_,_,_,_->
            val owner="voice:${item.record.inputKey}"
            val current=journal.read(owner,SpeechSession.Snapshot::class.java)!!
            journal.write(owner,current.value.copy(draft="다른 창의 최신 수정"),current.revision)
            SpeechResult.Success("새 결과","fake")
        }
        vm.transcribe();finished(vm)
        assertTrue(store.file(item.record.id).exists())
        assertFalse(vm.acceptInto(NaturalLanguageInputModel(app,SavedStateHandle())))
        assertEquals(PendingAudioStore.Phase.TRANSCRIBING,store.read(item.record.id)!!.record.phase)
    }
    @Test fun failedRetranscriptionKeepsPreviousRawAndEdit()=runBlocking(Dispatchers.Main) {
        val item=ready();val vm=model(item)
        vm.transcribeFile={_,_,_,_,_->SpeechResult.Success("첫 원문","fake")};vm.transcribe();finished(vm)
        vm.editDraft("첫 원문 직접 수정")
        vm.transcribeFile={_,_,_,_,_->SpeechResult.Failure(SpeechError.SERVER)};vm.transcribe();finished(vm)
        assertEquals("첫 원문",vm.session.original)
        assertEquals("첫 원문 직접 수정",vm.session.draft)
        assertTrue(vm.hasAudio())
        assertTrue(vm.acceptInto(NaturalLanguageInputModel(app,SavedStateHandle())))
    }
}
