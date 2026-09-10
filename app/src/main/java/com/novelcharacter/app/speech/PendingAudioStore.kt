package com.novelcharacter.app.speech

import com.novelcharacter.app.ai.ReviewJournal
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Audio stays outside JSON. A durable release decision always precedes deletion. */
class PendingAudioStore(val root: File, private val legacyRoot: File? = null) {
    enum class Phase { RECORDING, CAPTURED, READY, TRANSCRIBING, REVIEW, INTERRUPTED, RELEASED }
    data class Record(val version: Int = 1, val id: String, val inputKey: String,
        val phase: Phase, val createdAt: Long, val durationMs: Long = 0,
        val bytes: Long = 0, val checksum: String? = null, val attemptId: String? = null,
        val providerId: String? = null, val model: String? = null,
        val transcript: SpeechSession.Snapshot? = null,
        // Nullable: old records contain only their finalized m4a, never infer a PCM source.
        val captureFormat: String? = null, val checkpointBytes: Long? = null,
        val interruption: String? = null)
    data class Item(val record: Record, val revision: String)
    data class Orphan(val name: String, val legacy: Boolean)
    data class Catalog(val items: List<Item>, val orphans: List<Orphan>, val unreadable: Int)
    private val journal = ReviewJournal(File(root, "records"))
    private fun validId(id: String): String {
        require(UUID.fromString(id).toString() == id) { "Invalid recording ID" }
        return id
    }
    private fun owner(id: String) = "audio:${validId(id)}"
    private fun contained(directory: File, name: String): File {
        require(name == File(name).name && name != "." && name != "..")
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath())) { "Linked audio directory" }
        val file = File(directory, name)
        check(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Linked audio file" }
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "Audio path escaped" }
        return file
    }
    fun file(id: String): File = contained(File(root, "audio"), "${validId(id)}.m4a")
    fun pcmFile(id: String): File = contained(File(root, "audio"), "${validId(id)}.pcm")
    fun encodingFile(id: String): File = contained(File(root, "audio"), "${validId(id)}.encoding")
    fun read(id: String): Item? = journal.read(owner(id), Record::class.java)?.let {
        val r = it.value
        check(r.version == 1 && r.id == id && r.inputKey.isNotBlank() && r.phase in Phase.entries)
        check(r.transcript==null || r.transcript.sessionId==r.id)
        check(r.captureFormat==null || r.captureFormat==PcmCapture.FORMAT)
        check(r.checkpointBytes==null || r.checkpointBytes>=0 && r.checkpointBytes%2==0L)
        Item(r, it.revision)
    }
    fun create(id: String, inputKey: String, pcm: Boolean = false): Item = synchronized(lock) {
        require(inputKey.isNotBlank())
        val f = file(id)
        check(!f.exists() && !pcmFile(id).exists() && !encodingFile(id).exists() && read(id) == null)
        check(f.parentFile!!.isDirectory || f.parentFile!!.mkdirs())
        // Identity exists before recorder creation, including before the first audio byte.
        val r = Record(id = id, inputKey = inputKey, phase = Phase.RECORDING, createdAt = System.currentTimeMillis(),
            captureFormat = if(pcm) PcmCapture.FORMAT else null, checkpointBytes = if(pcm) 0L else null)
        Item(r, journal.write(owner(id), r, null))
    }
    private fun save(item: Item, value: Record): Item {
        check(value.id == item.record.id && value.inputKey == item.record.inputKey)
        return Item(value, journal.write(owner(value.id), value, item.revision))
    }
    /** Caller fsyncs PCM first. A failed journal write leaves the audio prefix available. */
    fun checkpoint(item: Item, bytes: Long, finished: Boolean = false, reason: String? = null): Item = synchronized(lock) {
        check(item.record.captureFormat==PcmCapture.FORMAT)
        check(item.record.phase in setOf(Phase.RECORDING,Phase.INTERRUPTED,Phase.CAPTURED))
        check(bytes>= (item.record.checkpointBytes ?: 0) && bytes%2==0L && pcmFile(item.record.id).length()>=bytes)
        save(item,item.record.copy(checkpointBytes=bytes,durationMs=PcmCapture.durationMs(bytes),
            phase=if(finished) {if(reason==null) Phase.CAPTURED else Phase.INTERRUPTED} else Phase.RECORDING,
            interruption=reason))
    }
    fun ready(item: Item, durationMs: Long): Item = synchronized(lock) {
        require(durationMs > 0)
        check(item.record.phase != Phase.RELEASED)
        val f = file(item.record.id)
        check(f.isFile && f.length() > 0)
        FileOutputStream(f, true).use { it.fd.sync() }
        save(item, item.record.copy(phase = Phase.READY, durationMs = durationMs,
            bytes = f.length(), checksum = checksum(f)))
    }
    fun verifiedFile(item: Item): File {
        val r = item.record
        check(r.phase != Phase.RELEASED && r.checksum != null && r.durationMs > 0)
        val f = file(r.id)
        check(f.isFile && f.length() == r.bytes && checksum(f) == r.checksum) { "Audio changed or missing" }
        return f
    }
    fun beginAttempt(item: Item, providerId: String, model: String): Item = synchronized(lock) {
        check(item.record.phase in setOf(Phase.READY, Phase.REVIEW, Phase.TRANSCRIBING))
        verifiedFile(item)
        save(item, item.record.copy(phase = Phase.TRANSCRIBING,
            attemptId = UUID.randomUUID().toString(), providerId = providerId, model = model))
    }
    fun finishAttempt(item: Item, transcript: SpeechSession.Snapshot?): Item = synchronized(lock) {
        check(item.record.phase == Phase.TRANSCRIBING)
        check(transcript==null || transcript.sessionId==item.record.id)
        val kept = transcript ?: item.record.transcript
        save(item, item.record.copy(phase = if (kept != null) Phase.REVIEW else Phase.READY, transcript = kept))
    }
    fun interrupted(item: Item, reason: String? = null): Item = synchronized(lock) {
        save(item, item.record.copy(phase = Phase.INTERRUPTED, interruption=reason ?: item.record.interruption))
    }
    fun release(item: Item): Item = synchronized(lock) {
        check(!isActive(item.record.id)) { "Recording is in use" }
        if (item.record.phase == Phase.RELEASED) item else save(item, item.record.copy(phase = Phase.RELEASED))
    }
    fun cleanup(item: Item) = synchronized(lock) {
        check(item.record.phase == Phase.RELEASED && !isActive(item.record.id))
        val files = listOf(file(item.record.id),pcmFile(item.record.id),encodingFile(item.record.id))
        val current=read(item.record.id)
        if(current==null) {check(files.none {it.exists()});return@synchronized}
        check(current.revision == item.revision)
        files.forEach { f -> check(!f.exists() || f.delete()) { "Audio deletion failed" } }
        journal.clear(owner(item.record.id), item.revision)
    }
    fun catalog(): Catalog = synchronized(lock) {
        val catalog = journal.owners("audio:")
        var unreadable = catalog.unreadableCount
        val items = catalog.owners.mapNotNull {
            try { read(it.removePrefix("audio:")) } catch (_: Exception) { unreadable++; null }
        }
        val known = items.flatMap { listOf("${it.record.id}.m4a","${it.record.id}.pcm","${it.record.id}.encoding") }.toSet()
        val orphan = File(root, "audio").listFiles().orEmpty()
            .filter { it.isFile && it.name !in known }.map { Orphan(it.name, false) } +
            legacyRoot?.listFiles().orEmpty().filter {
                it.isFile && it.name.startsWith("voice-") && it.extension == "m4a"
            }.map { Orphan(it.name, true) }
        Catalog(items.sortedByDescending { it.record.createdAt }, orphan, unreadable)
    }
    fun orphanFile(orphan: Orphan): File = contained(
        if (orphan.legacy) checkNotNull(legacyRoot) else File(root, "audio"), orphan.name)
    /** Explicit recovery creates a new independent Brief; it never guesses a character. */
    fun importOrphan(orphan: Orphan, durationMs: Long): Item = synchronized(lock) {
        check(orphan in catalog().orphans)
        check(!isActive(orphan.name.substringBeforeLast('.')))
        val source = orphanFile(orphan)
        val id = UUID.randomUUID().toString()
        val item = create(id, "briefing:draft:${UUID.randomUUID()}")
        source.inputStream().use { input -> FileOutputStream(file(id)).use { out -> input.copyTo(out); out.fd.sync() } }
        val ready = ready(item, durationMs)
        // A failed removal leaves a visible duplicate source, never loses the only audio.
        check(source.delete()) { "Recovered audio; original cleanup failed" }
        ready
    }
    /** Preserve pre-upgrade cache recordings without assigning them to any character. */
    fun preserveLegacy() = synchronized(lock) {
        legacyRoot?.listFiles().orEmpty().filter {
            it.isFile && it.name.startsWith("voice-") && it.extension=="m4a"
        }.forEach { source ->
            val from=orphanFile(Orphan(source.name,true))
            val target=contained(File(root,"audio"),source.name)
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            if(!target.exists()) {
                val temp=File.createTempFile("legacy-",".tmp",target.parentFile)
                // A crash leaves the source and any partial copy discoverable, not deleted.
                from.inputStream().use { input -> FileOutputStream(temp).use { out -> input.copyTo(out);out.fd.sync() } }
                java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            check(checksum(from)==checksum(target))
            check(from.delete())
        }
    }
    fun discardOrphan(orphan: Orphan) = synchronized(lock) {
        check(orphan in catalog().orphans)
        // A damaged record can conceal ownership: refuse deletion of a live session by name.
        check(!isActive(orphan.name.substringBeforeLast('.')))
        check(orphanFile(orphan).delete())
    }
    companion object {
        private val lock = Any()
        private val active = mutableMapOf<String, Any>()
        fun claim(id: String, holder: Any): Boolean = synchronized(lock) {
            if (active[id] != null && active[id] !== holder) false else { active[id] = holder; true }
        }
        fun end(id: String, holder: Any) = synchronized(lock) { if (active[id] === holder) active.remove(id); Unit }
        fun isActive(id: String): Boolean = synchronized(lock) { id in active }
        private fun checksum(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
