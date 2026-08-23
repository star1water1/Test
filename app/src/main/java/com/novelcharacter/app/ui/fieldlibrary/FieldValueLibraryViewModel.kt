package com.novelcharacter.app.ui.fieldlibrary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiErrorMessages
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.FieldLibraryAiOrganizer
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
import com.novelcharacter.app.data.model.FieldType

class FieldValueLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db = app.database
    val repo: FieldValueLibraryRepository = app.fieldValueLibraryRepository

    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    /**
     * 선택 모드와 고른 값 — **회전을 넘긴다**(뷰모델이 화면보다 오래 산다).
     *
     * 종전에는 화면이 들고 있어 회전 한 번에 **병합하려고 고른 값들이 아무 고지 없이
     * 풀렸다.** 이름은행이 이미 쓰는 처방이고(`NameBankViewModel`), 이미지 관리 화면도
     * 같은 판에서 이쪽으로 옮겼다 — 새 관용구를 만들지 않는다.
     */
    var selectionMode = false
    val selectedIds = linkedSetOf<Long>()

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
                unsupportedReasonRes = unsupportedReasonRes(fd),
                entryCount = c?.entryCount ?: 0,
                uncategorizedCount = c?.uncategorizedCount ?: 0,
                unusedCount = c?.unusedCount ?: 0
            )
        }
    }

    companion object {
        /**
         * 이 필드가 값 라이브러리를 못 쓰는 **사유 문자열** — 0이면 쓸 수 있다는 뜻이다.
         *
         * **두 화면이 같은 표를 본다** (B-55, 2026.08.13): 라이브러리 목록의 회색 사유와
         * 필드 관리에서 눌렀을 때 뜨는 토스트. 종전에는 같은 판정이 생문자열로 두 벌 적혀
         * 있었고, 갈리면 **한 화면은 "계산 필드라 안 됩니다"라 하고 다른 화면은 "구조화
         * 입력이라 안 됩니다"라고 하는** 모양이 된다 — 어느 쪽이 참인지 사용자가 알 길이 없다.
         *
         * 판정 순서가 표의 일부다: 쓸 수 있는가를 [FieldValueTokenizer.supportsLibrary]에게
         * 먼저 묻고, 못 쓸 때만 사유를 가른다. 그래서 구조화 입력을 켠 글자 필드는
         * '구조화' 사유로 떨어진다(타입이 아니라 설정 때문에 막힌 것이라 그쪽이 맞다).
         */
        fun unsupportedReasonRes(fd: FieldDefinition): Int = when {
            FieldValueTokenizer.supportsLibrary(fd) -> 0
            fd.fieldType == FieldType.CALCULATED -> R.string.field_library_unsupported_calculated
            fd.fieldType == FieldType.NUMBER -> R.string.field_library_unsupported_number
            else -> R.string.field_library_unsupported_structured
        }
    }

    // ===== 값 목록 =====

    fun entriesLive(fieldDefId: Long): LiveData<List<FieldValueEntry>> = repo.entriesForFieldLive(fieldDefId)

    suspend fun getField(fieldDefId: Long): FieldDefinition? = db.fieldDefinitionDao().getFieldById(fieldDefId)

    /** 화면 진입 시 usageCount 최신화 (diff-only — 무변화면 무효화도 없음) */
    fun refreshUsage(fieldDefId: Long) = viewModelScopeLaunch {
        // 저장 훅이 예약해 둔 재집계를 먼저 소진한다 — 그러지 않으면 이 화면의 갱신이
        // 뒤늦게 도착한 예약에 덮여 다시 옛 수치가 된다.
        repo.awaitPendingRecounts()
        repo.recountUsage(fieldDefId)
    }

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
                reportResult(_result, OpResult.success(
                    OpResult.CAT_FIELD_LIBRARY, summary, detailOf(r.report)))
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
        reportResult(_result, OpResult.success(
            OpResult.CAT_FIELD_LIBRARY, summary, detailOf(outcome.report)))
        repo.recountUsage(fd.id)
    }

    fun deleteEntry(fd: FieldDefinition, entry: FieldValueEntry, alsoClearData: Boolean) = viewModelScopeLaunch {
        val report = repo.deleteEntry(fd, entry, alsoClearData)
        val summary = if (alsoClearData) {
            app.getString(R.string.field_library_deleted) +
                " · " + app.getString(R.string.field_library_propagation_message,
                    report.characterValues, report.eventValues, report.stateChanges,
                    report.novelValues)
        } else {
            app.getString(R.string.field_library_deleted)
        }
        reportResult(_result, OpResult.success(
            OpResult.CAT_FIELD_LIBRARY, summary, detailOf(report)))
    }

    /**
     * 결과 알림의 **부가 줄** — config 동기화 고지와 「가리지 못해 남긴 이력」 고지를 합친다.
     *
     * 칸이 하나뿐이라 한쪽만 실으면 나중에 붙은 쪽이 앞의 것을 조용히 지운다. 둘 다 없으면
     * `null`이라 알림에 빈 줄이 생기지 않는다.
     */
    private fun detailOf(report: FieldValueLibraryRepository.PropagationReport): String? =
        listOfNotNull(
            if (report.configUpdated) app.getString(R.string.field_library_config_synced) else null,
            app.unattributedHistoryNotice(report).takeIf { it.isNotEmpty() }
        ).joinToString(" · ").takeIf { it.isNotEmpty() }

    /**
     * 미사용·미큐레이션 엔트리 정리 — **파괴적**이라 예약된 재집계를 반드시 먼저 소진한다.
     * 재집계 전의 `usageCount`는 0으로 남아 있을 수 있고, 그 상태로 지우면 **실제로 쓰이는 값의
     * 엔트리**가 사라진다(무음 유실). 기다리는 비용보다 잘못 지우는 비용이 크다.
     */
    fun pruneUnused(fd: FieldDefinition) = viewModelScopeLaunch {
        repo.awaitPendingRecounts()
        repo.recountUsage(fd.id)
        val pruned = db.fieldValueEntryDao().pruneUncuratedUnused(fd.id)
        reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY,
            app.getString(R.string.field_library_prune_done, pruned)))
    }

    /** 정리 대상 건수 — 사용자에게 숫자를 보이기 전에 그 숫자가 맞아야 한다(경합 제거). */
    suspend fun countPrunable(fd: FieldDefinition): Int {
        repo.awaitPendingRecounts()
        repo.recountUsage(fd.id)
        return db.fieldValueEntryDao().countUncuratedUnused(fd.id)
    }

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

    // ===== AI 정리 — 유료 응답의 실행·보관 (R-38 · B-155) =====

    /**
     * AI 정리 한 번의 산출 — **제안과 그것을 낸 재료를 함께 든다.**
     *
     * `entries`를 같이 들고 다니는 것은 적용 단계가 그것을 쓰기 때문이다(연쇄 병합에서
     * canonical이 이미 소모됐는지 판정한다). 종전에는 코루틴 지역 변수에 있어서
     * **회전 한 번에 제안과 함께 사라졌다.**
     */
    data class AiOrganizeOutcome(
        val field: FieldDefinition,
        val entries: List<FieldValueEntry>,
        val result: FieldLibraryAiOrganizer.OrganizeOutcome
    )

    val aiOrganizeRunning = MutableLiveData(false)

    /**
     * AI 정리의 결과 — **이미 결제한 응답이라 회전을 넘긴다** (R-38 부류의 셋째 · B-155).
     *
     * 이미지 검토 시트 둘([ImageManagerViewModel]의 `aiTagResult`·`folderTagResult`)이
     * 2026.08.08에 같은 이유로 여기 올라왔다. 이 기능은 그보다 **먼저 있던 첫 실데이터 AI
     * 기능**이라(`docs/ai_integration.md`) 청킹·환각 검증·부분 실패 동반 반환의 본보기인데,
     * 정작 보존은 셋 중 가장 나빴다 — 실행이 `viewLifecycleOwner.lifecycleScope`에 있어
     * **회전이 결제 중인 요청을 끊었고**, 체크리스트는 맨 `AlertDialog`라 되살릴 자리조차
     * 없었다.
     */
    val aiOrganizeResult: MutableLiveData<AiOrganizeOutcome?> = MutableLiveData()

    fun clearAiOrganizeResult() { aiOrganizeResult.value = null }

    /**
     * AI 정리 실행 — [viewModelScope]에서 돌아 **회전을 넘긴다**.
     *
     * **바깥에 열지 않는다** — 입구는 이 함수 하나다. 뷰가 `organize`를 직접 부를 수 있으면
     * 결과를 보관하지 않는 경로가 다시 생기고, 그 경로는 조용히 돈을 버린다(R-38 ④).
     *
     * @return 이미 실행 중이면 false — 호출측이 알린다(무통보 무시 금지).
     */
    fun runAiOrganize(fd: FieldDefinition, entries: List<FieldValueEntry>): Boolean {
        if (aiOrganizeRunning.value == true) return false
        aiOrganizeRunning.value = true
        viewModelScope.launch {
            try {
                val organizer = FieldLibraryAiOrganizer(AiService(app))
                val outcome = organizer.organize(
                    fd, entries,
                    // 사용자가 고친 메시지 양식 (2026.08.20). 손댄 적이 없으면 기본 양식이다.
                    com.novelcharacter.app.ai.AiPromptSettings(app).asTemplateSource()
                ) { failure ->
                    AiErrorMessages.of(app, failure)
                }
                aiOrganizeResult.value = AiOrganizeOutcome(fd, entries, outcome)
            } catch (e: Exception) {
                android.util.Log.e("FieldValueLibraryVM", "ai organize failed", e)
                reportResult(_result, OpResult.failure(OpResult.CAT_FIELD_LIBRARY,
                    e.message ?: app.getString(R.string.ai_error_unknown)))
            } finally {
                aiOrganizeRunning.value = false
            }
        }
        return true
    }

    /**
     * 선택 항목을 적용한다 — **적용이 깨지면 유료 응답을 되살린다** (R-38 ⑤ · B-163과 같은 갈래).
     *
     * 시트는 [적용]에 곧바로 닫히므로, 여기서 결과를 비워 버리면 트랜잭션이 중간에 깨졌을 때
     * **되돌아가 다시 적용할 자리가 없다** — 다시 얻으려면 재결제다. 그래서 성공했을 때만 비운다.
     */
    fun applyAiOrganize(
        outcome: AiOrganizeOutcome,
        merges: List<FieldLibraryAiOrganizer.MergeSuggestion>,
        categories: List<FieldLibraryAiOrganizer.CategorySuggestion>
    ) = viewModelScope.launch {
        val fd = outcome.field
        var appliedMerges = 0
        var appliedCategories = 0
        var skipped = 0
        try {
            val byValue = outcome.entries.associateBy { it.value }.toMutableMap()
            for (m in merges) {
                // 선행 병합이 canonical을 소모한 연쇄 제안은 건너뛰되 건수로 표면화 (조용히 버리지 않음)
                val target = byValue[m.canonical]
                val sourceIds = m.variants.mapNotNull { byValue[it]?.id }
                if (target == null || sourceIds.isEmpty()) {
                    skipped++
                    continue
                }
                repo.mergeValues(fd, target.id, sourceIds)
                m.variants.forEach { byValue.remove(it) }
                appliedMerges++
            }
            // 카테고리는 병합 반영 후 최신 엔트리 기준으로 적용 (source=AI 마킹)
            val fresh = repo.entriesForField(fd.id).associateBy { it.value }
            for (c in categories) {
                val entry = fresh[c.value]
                if (entry == null) {
                    skipped++
                    continue
                }
                if (entry.category == c.category) continue
                repo.updateEntry(entry.copy(
                    category = c.category,
                    source = FieldValueEntry.SOURCE_AI
                ))
                appliedCategories++
            }
            repo.recountUsage(fd.id)
            notifyAiApplied(appliedMerges, appliedCategories, skipped)
            // 성공했을 때만 비운다 — 실패 경로는 아래가 응답을 그대로 남긴다.
            clearAiOrganizeResult()
        } catch (e: Exception) {
            // 부분 적용 중 실패 — 이미 적용된 건수와 함께 실패를 알린다 (앱 크래시 금지).
            // **보관 중인 제안은 비우지 않는다**: 그것이 되살리기의 재료다.
            android.util.Log.e("FieldValueLibraryVM", "ai organize apply failed", e)
            notifyAiApplyFailed(appliedMerges, appliedCategories, e.message)
        }
    }

    /** AI 정리 적용 완료 통보 — 연쇄 제안 스킵 건수도 표면화 */
    private fun notifyAiApplied(mergeCount: Int, categoryCount: Int, skippedCount: Int = 0) {
        val summary = app.getString(R.string.field_library_ai_applied, mergeCount, categoryCount)
        val detail = if (skippedCount > 0)
            app.getString(R.string.field_library_ai_apply_skipped, skippedCount) else null
        reportResult(_result, OpResult.success(OpResult.CAT_FIELD_LIBRARY, summary, detail))
    }

    /** AI 정리 적용 중 실패 통보 — 부분 적용 상황을 알린다 */
    private fun notifyAiApplyFailed(mergeCount: Int, categoryCount: Int, message: String?) {
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
