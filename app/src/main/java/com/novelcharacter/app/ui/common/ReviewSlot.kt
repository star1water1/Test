package com.novelcharacter.app.ui.common

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.ai.ReviewJournal
import java.io.File

/** A device-local, backup-excluded slot, bound to the entity before any paid request. */
class ReviewSlot<T>(private val app: Application, private val type: Class<T>) {
    private val journal = ReviewJournal(File(app.noBackupFilesDir, "creative-reviews"))
    private var owner: String? = null
    private var revision: String? = null
    private var lastSaved: T? = null
    var failed = false; private set
    fun read(key: String): T? = attempt {
        check(!leases.heldByOther(key,this)) { "LIVE_REQUEST" }
        val entry = journal.read(key, type)
        owner = key; revision = entry?.revision
        lastSaved = entry?.value
        entry?.value
    }
    fun beginRequest(): Boolean = attempt {
        val key=checkNotNull(owner)
        check(journal.revision(key)==revision) { "Review changed in another editor" }
        check(leases.acquire(key,this)) { "LIVE_REQUEST" }
        true
    } ?: false
    fun endRequest() { owner?.let { leases.release(it,this) } }
    fun save(value: T): Boolean = attempt {
        val key = checkNotNull(owner)
        check(!leases.heldByOther(key,this)) { "LIVE_REQUEST" }
        // Rendering an unchanged review must not repeatedly serialize/fsync the full draft.
        if (!failed && value == lastSaved) return@attempt true
        revision = journal.write(key, value as Any, revision)
        lastSaved = value
        true
    } ?: false
    fun clear(relatedInputs: List<String> = emptyList()): Boolean = attempt {
        owner?.let { journal.clear(it, revision, relatedInputs) }
        revision = null
        lastSaved = null
        true
    } ?: false
    private fun <R> attempt(action: () -> R): R? = try {
        action().also { failed = false }
    } catch (e: Exception) {
        if (!failed) Toast.makeText(app,
            if(e.message=="LIVE_REQUEST") "다른 편집 창에서 이 AI 요청을 진행 중입니다. 요청이 끝난 뒤 다시 열어 주세요."
            else "AI 검토 내용을 보관하거나 복구하지 못했습니다. 저장 공간을 확인하고 다른 편집 창을 닫은 뒤 다시 열어 주세요. 기존 보관 파일은 지우지 않았습니다.",
            Toast.LENGTH_LONG).show()
        failed = true
        null
    }
    companion object { private val leases = com.novelcharacter.app.ai.ReviewRequestLease() }
}

/** Checkpoint before observers can offer apply/discard. Edits checkpoint through state.changed(). */
class ReviewLiveData<T>(private val checkpoint: (T?) -> Unit): MutableLiveData<T?>() {
    override fun setValue(value: T?) { checkpoint(value); super.setValue(value) }
}
