package com.novelcharacter.app.ai

import com.novelcharacter.app.speech.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingAudioStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private fun store()=PendingAudioStore(File(temp.root,"private"),File(temp.root,"legacy"))
    private val bytes=ByteArray(8192) {(it%251).toByte()}
    private fun ready():PendingAudioStore.Item {
        val item=store().create(UUID.randomUUID().toString(),"briefing:draft:${UUID.randomUUID()}")
        store().file(item.record.id).writeBytes(bytes)
        return store().ready(item,480_000)
    }
    private fun rejects(action:()->Unit) {
        try {action();fail("Expected refusal")} catch(e:AssertionError) {throw e} catch(_:Exception) {}
    }
    @Test fun readyBytesOwnerAndDurationSurviveFreshStore() {
        val item=ready()
        val restored=store().read(item.record.id)!!
        assertEquals(item,restored)
        assertArrayEquals(bytes,store().verifiedFile(restored).readBytes())
        assertEquals(480_000,restored.record.durationMs)
    }
    @Test fun inFlightRecoveryRetainsAttemptWithoutRetryOrDeletion() {
        val item=store().beginAttempt(ready(),"provider-a","model-a")
        val recovered=store().catalog().items.single()
        assertEquals(item,recovered)
        assertEquals(PendingAudioStore.Phase.TRANSCRIBING,recovered.record.phase)
        assertArrayEquals(bytes,store().verifiedFile(recovered).readBytes())
    }
    @Test fun allFailuresAndCancellationRetainSameFileAndCanChangeProvider() {
        var item=ready()
        listOf(SpeechError.NETWORK,SpeechError.SERVER,SpeechError.RATE_LIMIT,SpeechError.AUTH,
            SpeechError.CONFIG,SpeechError.NO_PROVIDER,SpeechError.NO_KEY,SpeechError.EMPTY).forEach {
            item=store().beginAttempt(item,"first",it.name)
            item=store().finishAttempt(item,null)
            assertArrayEquals(bytes,store().verifiedFile(item).readBytes())
        }
        val previous=item.record.attemptId
        item=store().beginAttempt(item,"second","another-model")
        assertNotEquals(previous,item.record.attemptId)
        assertEquals("second",item.record.providerId)
    }
    @Test fun successAndNonblankPartialTextKeepAudioUntilUserAccepts() {
        var item=store().beginAttempt(ready(),"p","m")
        val text=SpeechSession.Snapshot("앞부분만 받은 결과","사용자 수정",false,item.record.id)
        item=store().finishAttempt(item,text)
        val restored=store().read(item.record.id)!!
        assertEquals(text,restored.record.transcript)
        assertArrayEquals(bytes,store().verifiedFile(restored).readBytes())
        val released=store().release(restored)
        assertTrue(store().file(item.record.id).exists())
        store().cleanup(released)
        store().cleanup(released)
        assertNull(store().read(item.record.id))
        assertFalse(store().file(item.record.id).exists())
    }
    @Test fun failedRetryKeepsPreviousTranscriptAndItsEdit() {
        var item=store().beginAttempt(ready(),"p","m")
        val text=SpeechSession.Snapshot("원문","수정본",false,item.record.id)
        item=store().finishAttempt(item,text)
        item=store().beginAttempt(item,"p2","m2")
        item=store().finishAttempt(item,null)
        assertEquals(text,item.record.transcript)
        assertArrayEquals(bytes,store().verifiedFile(item).readBytes())
    }
    @Test fun transcriptCheckpointFailureDoesNotReleaseTheAudio() {
        val item=store().beginAttempt(ready(),"p","m")
        val newest=store().finishAttempt(item,null)
        rejects {store().finishAttempt(item,SpeechSession.Snapshot("받은 글","수정",false,item.record.id))}
        assertEquals(newest,store().read(item.record.id))
        assertTrue(store().file(item.record.id).exists())
    }
    @Test fun cleanupFailureKeepsDurableReleaseDecisionAndIsRetryable() {
        val item=store().release(ready())
        val file=store().file(item.record.id)
        assertTrue(file.delete());assertTrue(file.mkdir())
        val blocker=File(file,"block").apply {writeText("x")}
        rejects {store().cleanup(item)}
        assertEquals(PendingAudioStore.Phase.RELEASED,store().read(item.record.id)!!.record.phase)
        assertTrue(blocker.delete());store().cleanup(item)
        assertNull(store().read(item.record.id))
    }
    @Test fun staleWindowCannotDeleteNewerAttempt() {
        val item=ready()
        val newer=store().beginAttempt(item,"p","m")
        rejects {store().release(item)}
        assertEquals(newer,store().read(item.record.id))
        assertTrue(store().file(item.record.id).exists())
    }
    @Test fun pathEscapeWrongOwnerAndChangedBytesAreRejected() {
        rejects {store().file("../outside")}
        rejects {store().orphanFile(PendingAudioStore.Orphan("../outside",false))}
        val item=ready()
        store().file(item.record.id).appendText("changed")
        rejects {store().verifiedFile(item)}
        rejects {store().beginAttempt(item,"p","m")}
        assertTrue(store().file(item.record.id).exists())
    }
    @Test fun activeSessionPreventsSecondHolderAndDeletion() {
        val item=ready();val first=Any();val second=Any()
        try {
            assertTrue(PendingAudioStore.claim(item.record.id,first))
            assertFalse(PendingAudioStore.claim(item.record.id,second))
            rejects {store().release(item)}
            PendingAudioStore.end(item.record.id,second)
            assertTrue(PendingAudioStore.isActive(item.record.id))
        } finally {PendingAudioStore.end(item.record.id,first)}
        store().cleanup(store().release(item))
    }
    @Test fun incompleteAudioAndDamagedMetadataRemainVisibleRegardlessOfAge() {
        val item=store().create(UUID.randomUUID().toString(),"briefing:draft:original")
        store().file(item.record.id).writeBytes(byteArrayOf(1,2,3))
        val damaged=File(store().root,"records/damaged.json").apply {writeText("broken")}
        val orphan=File(store().root,"audio/leftover.tmp").apply {writeText("partial")}
        orphan.setLastModified(1)
        val catalog=store().catalog()
        assertEquals(1,catalog.unreadable)
        assertEquals(PendingAudioStore.Phase.RECORDING,catalog.items.single().record.phase)
        assertEquals(listOf(PendingAudioStore.Orphan("leftover.tmp",false)),catalog.orphans)
        assertTrue(damaged.exists());assertTrue(orphan.exists())
    }
    @Test fun legacyCacheMovesOnlyAfterVerifiedCopyAndNoOwnerIsGuessed() {
        val legacy=File(temp.root,"legacy").apply {mkdirs()}
        val original=File(legacy,"voice-old.m4a").apply {writeBytes(bytes)}
        store().preserveLegacy()
        assertFalse(original.exists())
        val orphan=store().catalog().orphans.single()
        assertFalse(orphan.legacy)
        assertArrayEquals(bytes,store().orphanFile(orphan).readBytes())
        assertTrue(store().catalog().items.isEmpty())
        val recovered=store().importOrphan(orphan,480_000)
        assertTrue(recovered.record.inputKey.startsWith("briefing:draft:"))
        assertArrayEquals(bytes,store().verifiedFile(recovered).readBytes())
    }
    @Test fun conflictingLegacyCopyDoesNotDeleteEitherVersion() {
        val legacy=File(temp.root,"legacy").apply {mkdirs()}
        val original=File(legacy,"voice-old.m4a").apply {writeBytes(bytes)}
        File(store().root,"audio").mkdirs()
        val other=File(store().root,"audio/voice-old.m4a").apply {writeText("different")}
        rejects {store().preserveLegacy()}
        assertArrayEquals(bytes,original.readBytes());assertEquals("different",other.readText())
    }
}
