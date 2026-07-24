package com.novelcharacter.app.ui.fieldlibrary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.FieldValueLibraryConfig
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.repository.FieldValueLibraryRepository
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class FieldValueLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db = app.database
    val repo: FieldValueLibraryRepository = app.fieldValueLibraryRepository

    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    // ===== 홈 =====

    /** 홈 목록 행 — 값 자체는 로드하지 않는다 (countByField 1쿼리 요약) */
    data class FieldRow(
        val field: FieldDefinition,
        val universeName: String,
        val supported: Boolean,
        val unsupportedReasonRes: Int,
        val entryCount: Int,
        val uncategorizedCount: Int,
        val unusedCount: Int
    )

    suspend fun loadUniverses(): List<Universe> = db.universeDao().getAllUniversesList()

    suspend fun loadFieldRows(universeId: Long, entityType: String): List<FieldRow> {
        val universes = db.universeDao().getAllUniversesList().associateBy { it.id }
        val fields = if (universeId == -1L) {
            db.fieldDefinitionDao().getAllFieldsList(entityType)
        } else {
            db.fieldDefinitionDao().getFieldsByUniverseList(universeId, entityType)
        }
        val counts = repo.entryCounts()
        return fields.map { fd ->
            val c = counts[fd.id]
            FieldRow(
                field = fd,
                universeName = universes[fd.universeId]?.name ?: "",
                supported = FieldValueTokenizer.supportsLibrary(fd),
                unsupportedReasonRes = unsupportedReason(fd),
                entryCount = c?.entryCount ?: 0,
                uncategorizedCount = c?.uncategorizedCount ?: 0,
                unusedCount = c?.unusedCount ?: 0
            )
        }
    }

    private fun unsupportedReason(fd: FieldDefinition): Int = when {
        FieldValueTokenizer.supportsLibrary(fd) -> 0
        fd.type == "CALCULATED" -> R.string.field_library_unsupported_calculated
        fd.type == "NUMBER" -> R.string.field_library_unsupported_number
        else -> R.string.field_library_unsupported_structured
    }

    // ===== 값 목록 =====

    fun entriesLive(fieldDefId: Long): LiveData<List<FieldValueEntry>> = repo.entriesForFieldLive(fieldDefId)

    suspend fun getField(fieldDefId: Long): FieldDefinition? = db.fieldDefinitionDao().getFieldById(fieldDefId)

    /** 화면 진입 시 usageCount 최신화 (diff-only — 무변화면 무효화도 없음) */
    fun refreshUsage(fieldDefId: Long) = viewModelScopeLaunch { repo.recountUsage(fieldDefId) }

    fun addValue(
        fd: FieldDefinition,
        value: String,
        onAdded: () -> Unit = {},
        onDuplicate: (String) -> Unit
    ) = viewModelScopeLaunch {
        when (val r = repo.addEntry(fd.id, value)) {
            is FieldValueLibraryRepository.AddResult.Added -> {
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY,
                    app.getString(R.string.field_library_added, r.entry.value)))
                onAdded()
            }
            is FieldValueLibraryRepository.AddResult.Duplicate -> onDuplicate(
                if (r.asAlias) app.getString(R.string.field_library_duplicate_alias, r.conflictWith.value)
                else app.getString(R.string.field_library_duplicate_value, r.conflictWith.value)
            )
            FieldValueLibraryRepository.AddResult.BlankValue -> Unit
        }
    }

    fun saveEntry(
        entry: FieldValueEntry,
        onSaved: () -> Unit = {},
        onAliasConflict: (String) -> Unit
    ) = viewModelScopeLaunch {
        when (val r = repo.updateEntry(entry)) {
            FieldValueLibraryRepository.UpdateResult.Updated -> {
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY,
                    app.getString(R.string.field_library_saved)))
                onSaved()
            }
            is FieldValueLibraryRepository.UpdateResult.AliasConflict -> onAliasConflict(
                app.getString(R.string.field_library_alias_conflict, r.alias, r.conflictWith.value)
            )
        }
    }

    fun renameValue(
        fd: FieldDefinition,
        entry: FieldValueEntry,
        newValue: String,
        keepOldAsAlias: Boolean,
        onConflict: (FieldValueEntry) -> Unit
    ) = viewModelScopeLaunch {
        when (val r = repo.renameValue(fd, entry, newValue, keepOldAsAlias)) {
            is FieldValueLibraryRepository.RenameResult.Renamed -> {
                val summary = app.getString(
                    R.string.field_library_renamed, entry.value, r.entry.value, r.report.total)
                val detail = if (r.report.configUpdated)
                    app.getString(R.string.field_library_config_synced) else null
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY, summary, detail))
                repo.recountUsage(fd.id)
            }
            is FieldValueLibraryRepository.RenameResult.Conflict -> onConflict(r.conflictWith)
            FieldValueLibraryRepository.RenameResult.BlankValue -> Unit
        }
    }

    fun mergeValues(fd: FieldDefinition, targetId: Long, sourceIds: List<Long>) = viewModelScopeLaunch {
        val outcome = repo.mergeValues(fd, targetId, sourceIds) ?: return@viewModelScopeLaunch
        val summary = app.getString(
            R.string.field_library_merged, sourceIds.size, outcome.target.value, outcome.report.total)
        val detail = if (outcome.report.configUpdated)
            app.getString(R.string.field_library_config_synced) else null
        reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY, summary, detail))
        repo.recountUsage(fd.id)
    }

    fun deleteEntry(fd: FieldDefinition, entry: FieldValueEntry, alsoClearData: Boolean) = viewModelScopeLaunch {
        val report = repo.deleteEntry(fd, entry, alsoClearData)
        val summary = if (alsoClearData) {
            app.getString(R.string.field_library_deleted) +
                " · " + app.getString(R.string.field_library_propagation_message,
                    report.characterValues, report.eventValues, report.stateChanges)
        } else {
            app.getString(R.string.field_library_deleted)
        }
        reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY, summary))
    }

    fun pruneUnused(fd: FieldDefinition) = viewModelScopeLaunch {
        val pruned = db.fieldValueEntryDao().pruneUncuratedUnused(fd.id)
        reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY,
            app.getString(R.string.field_library_prune_done, pruned)))
    }

    suspend fun countPrunable(fd: FieldDefinition): Int = db.fieldValueEntryDao().countUncuratedUnused(fd.id)

    suspend fun previewPropagation(fd: FieldDefinition, tokens: Set<String>) =
        repo.previewPropagation(fd, tokens)

    suspend fun usageDrilldown(fd: FieldDefinition, entry: FieldValueEntry) =
        repo.usageDrilldown(fd, entry)

    fun setInputMode(fd: FieldDefinition, mode: String, onSaved: (FieldDefinition) -> Unit) = viewModelScopeLaunch {
        val newConfig = FieldValueLibraryConfig.applyToConfig(fd.config, FieldValueLibraryConfig(mode))
        val updated = fd.copy(config = newConfig)
        db.fieldDefinitionDao().update(updated)
        reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY,
            app.getString(R.string.field_library_input_mode_saved)))
        onSaved(updated)
    }

    /** AI 정리 적용 완료 통보 (AiOrganizeSheet에서 호출) — 연쇄 제안 스킵 건수도 표면화 */
    fun notifyAiApplied(mergeCount: Int, categoryCount: Int, skippedCount: Int = 0) {
        val summary = app.getString(R.string.field_library_ai_applied, mergeCount, categoryCount)
        val detail = if (skippedCount > 0)
            app.getString(R.string.field_library_ai_apply_skipped, skippedCount) else null
        reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY, summary, detail))
    }

    /** AI 정리 적용 중 실패 통보 — 부분 적용 상황을 알린다 */
    fun notifyAiApplyFailed(mergeCount: Int, categoryCount: Int, message: String?) {
        reportResult(_result, OpResult.failure(OpResult.CAT_FIELD_LIBRARY,
            app.getString(R.string.field_library_ai_apply_failed, mergeCount, categoryCount),
            message))
    }

    private fun viewModelScopeLaunch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.e("FieldValueLibraryVM", "operation failed", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD_LIBRARY,
                e.message ?: app.getString(R.string.ai_error_unknown)))
        }
    }
}
