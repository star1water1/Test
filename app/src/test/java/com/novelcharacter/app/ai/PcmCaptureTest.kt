package com.novelcharacter.app.ai

import com.novelcharacter.app.speech.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmCaptureTest {
    @get:Rule val temp=TemporaryFolder()
    private fun store()=PendingAudioStore(File(temp.root,"private"))
    private fun create()=store().create(UUID.randomUUID().toString(),"briefing:test",pcm=true)
    private fun rejects(action:()->Unit) {try {action();fail("Expected refusal")} catch(e:AssertionError) {throw e} catch(_:Exception) {}}
    @Test fun unfinalizedSourcePreservesEveryFrameAndDistinguishesLastCheckpoint() {
        val item=create();val first=ByteArray(32000) {(it%251).toByte()};val tail=byteArrayOf(2,3,4,5)
        PcmCapture(store(),item).use {it.append(first,first.size);it.checkpoint();it.append(tail,tail.size)}
        val restored=store().read(item.record.id)!!
        assertEquals(PendingAudioStore.Phase.RECORDING,restored.record.phase)
        assertEquals(32000L,restored.record.checkpointBytes)
        assertEquals(1000L,restored.record.durationMs)
        assertArrayEquals(first+tail,store().pcmFile(item.record.id).readBytes())
        assertTrue(store().catalog().orphans.isEmpty())
    }
    @Test fun interruptedStopKeepsReasonAndDurablePrefix() {
        val item=create()
        PcmCapture(store(),item).use {it.append(ByteArray(64000),64000);it.checkpoint(true,"microphone silenced")}
        val restored=store().read(item.record.id)!!
        assertEquals(PendingAudioStore.Phase.INTERRUPTED,restored.record.phase)
        assertEquals("microphone silenced",restored.record.interruption)
        assertEquals(2000L,restored.record.durationMs)
        assertEquals(64000L,store().pcmFile(item.record.id).length())
    }
    @Test fun normalStopIsCapturedButNotYetVerifiedForUpload() {
        val item=create()
        PcmCapture(store(),item).use {it.append(ByteArray(32000),32000);it.checkpoint(true)}
        val restored=store().read(item.record.id)!!
        assertEquals(PendingAudioStore.Phase.CAPTURED,restored.record.phase)
        rejects {store().verifiedFile(restored)}
        rejects {PcmCapture(store(),restored)}
        assertEquals(32000L,store().pcmFile(item.record.id).length())
    }
    @Test fun metadataConflictDoesNotDeleteAlreadyWrittenAudio() {
        val item=create()
        PcmCapture(store(),item).use {
            it.append(ByteArray(32000),32000)
            store().interrupted(item,"concurrent state")
            rejects {it.checkpoint()}
        }
        assertEquals(32000L,store().pcmFile(item.record.id).length())
        assertEquals("concurrent state",store().read(item.record.id)!!.record.interruption)
    }
    @Test fun cleanupRemovesAllRenditionsOnlyAfterRelease() {
        val item=create()
        PcmCapture(store(),item).use {it.append(ByteArray(100),100);it.checkpoint(true)}
        store().file(item.record.id).writeBytes(byteArrayOf(1))
        store().encodingFile(item.record.id).writeBytes(byteArrayOf(2))
        rejects {store().cleanup(store().read(item.record.id)!!)}
        val released=store().release(store().read(item.record.id)!!)
        assertTrue(store().pcmFile(item.record.id).exists())
        store().cleanup(released);store().cleanup(released)
        assertFalse(store().pcmFile(item.record.id).exists());assertTrue(store().catalog().orphans.isEmpty())
    }
    @Test fun activePcmOrphanCannotBeDiscardedEvenWhenMetadataIsUnavailable() {
        val id=UUID.randomUUID().toString();val owner=Any()
        store().pcmFile(id).apply {parentFile!!.mkdirs();writeBytes(byteArrayOf(0,1))}
        assertTrue(PendingAudioStore.claim(id,owner))
        try {rejects {store().discardOrphan(store().catalog().orphans.single())}}
        finally {PendingAudioStore.end(id,owner)}
        assertTrue(store().pcmFile(id).exists())
    }
    @Test fun durationComesFromCompleteFramesAndLimitComesFromUploadBudget() {
        assertEquals(32000L,PcmCapture.prefixBytes(32001))
        assertEquals(1000L,PcmCapture.durationMs(32001))
        assertTrue(SpeechProtocol.MAX_RECORDING_MS>15*60*1000)
        assertTrue(SpeechProtocol.MAX_RECORDING_MS/1000L*(SpeechProtocol.AAC_BIT_RATE/8)<SpeechProtocol.MAX_AUDIO_BYTES)
    }
}
