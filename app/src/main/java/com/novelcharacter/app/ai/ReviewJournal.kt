package com.novelcharacter.app.ai

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Private review drafts only. Never writes domain data or stores credentials/audio. */
class ReviewJournal(private val root: File) {
    data class Entry<T>(val revision: String, val value: T)
    private data class Envelope(val version: Int, val owner: String, val revision: String,
        val payload: String, val checksum: String)
    private val gson = Gson()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun file(owner: String) = File(root, hash(owner) + ".json")
    private fun envelope(owner: String): Envelope? {
        val file = file(owner)
        if (!file.exists()) return null
        val record = gson.fromJson(file.readText(Charsets.UTF_8), Envelope::class.java)
        check(record.version == 1 && record.owner == owner && record.revision.isNotBlank())
        check(record.checksum == hash(record.payload)) { "Review checksum mismatch" }
        return record
    }
    fun <T> read(owner: String, type: Class<T>): Entry<T>? = synchronized(lock) {
        envelope(owner)?.let { Entry(it.revision, requireNotNull(gson.fromJson(it.payload, type))) }
    }
    fun revision(owner: String): String? = synchronized(lock) { envelope(owner)?.revision }
    /** Compare-and-swap prevents a second editor from silently overwriting newer decisions. */
    fun write(owner: String, value: Any, expectedRevision: String?): String = synchronized(lock) {
        check(envelope(owner)?.revision == expectedRevision) { "Review changed in another editor" }
        check(root.isDirectory || root.mkdirs())
        val revision = UUID.randomUUID().toString()
        val payload = gson.toJson(value)
        val bytes = gson.toJson(Envelope(1, owner, revision, payload, hash(payload))).toByteArray(Charsets.UTF_8)
        val temporary = File.createTempFile("review-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file(owner).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        revision
    }
    fun clear(owner: String, expectedRevision: String?, relatedInputs: List<String> = emptyList()) = synchronized(lock) {
        check(envelope(owner)?.revision == expectedRevision) { "Review changed in another editor" }
        val owners = relatedInputs.flatMap { listOf("creative-input:$it", "voice:$it") } + owner
        owners.forEach { envelope(it) } // Validate before removing anything; never erase unreadable data.
        owners.forEach {
            val file = file(it)
            check(!file.exists() || file.delete())
        }
    }
    companion object { private val lock = Any() }
}
