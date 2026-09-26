package com.novelcharacter.app.ui.character.batch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.NaturalBatchAnalyzer
import com.novelcharacter.app.ai.NaturalBatchChunks
import com.novelcharacter.app.ai.NaturalBatchChunkState
import com.novelcharacter.app.ai.NaturalBatchContext
import com.novelcharacter.app.ai.NaturalBatchContextSnapshot
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor
import com.novelcharacter.app.ai.NaturalBatchInput
import com.novelcharacter.app.ai.NaturalBatchPlan
import com.novelcharacter.app.ai.NaturalBatchPlans
import com.novelcharacter.app.ai.NaturalBatchReviewState
import com.novelcharacter.app.ai.NaturalBatchReviewSelection
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.ui.common.ReviewSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Owns a paid review outside the Fragment view and checkpoints every explicit decision. */
class NaturalBatchViewModel @JvmOverloads constructor(app: Application,
    private val analyzer: NaturalBatchAnalyzer = NaturalBatchAnalyzer(AppDatabase.getDatabase(app), AiService(app))
) : AndroidViewModel(app) {
    enum class BusyKind { LOADING, ANALYSIS, PREFLIGHT, APPLY, UNDO }
    data class ScopeOption(val scope: NaturalBatchInput.Scope, val label: String)
    data class ExecutionRecord(val id: String, val createdAt: Long,
        val applied: Int, val undone: Int = 0)
    private data class SavedReview(
        val review: NaturalBatchReviewState.Snapshot,
        val context: NaturalBatchContextSnapshot?, val executions: List<ExecutionRecord>,
        val results: List<NaturalBatchFieldExecutor.Decision>?, val scrollPosition: Int = 0,
        val expanded: Set<String> = emptySet(),
        val chunks: NaturalBatchChunkState? = null
    )

    private val db = AppDatabase.getDatabase(app)
    private val slot = ReviewSlot(app, SavedReview::class.java)
    private val executor = NaturalBatchFieldExecutor(db)
    private var review: NaturalBatchReviewState? = null
    private var context: NaturalBatchContext? = null
    private var prepared: NaturalBatchFieldExecutor.Prepared? = null
    private var analyzing = false
    private var chunks: NaturalBatchChunkState? = null
    private var stopAnalysis = false
    var analysisDone = 0; private set
    var analysisTotal = 0; private set
    private var pendingInput: String? = null
    private val executions = mutableListOf<ExecutionRecord>()
    var results: List<NaturalBatchFieldExecutor.Decision>? = null; private set
    var options: List<ScopeOption> = emptyList(); private set
    var busy = false; private set
    var busyKind: BusyKind? = null; private set
    var message: String? = null; private set
    var storageFailed = false; private set
    var scrollPosition = 0; private set
    private val expanded = mutableSetOf<String>()
    val updates = MutableLiveData(0)

    val snapshot get() = review?.snapshot()
    val analysisContext get() = context
    val preview get() = prepared?.items?.map { it.decision }
    val canUndo get() = executions.any { it.undone < it.applied }
    val undoChoices get() = executions.filter { it.undone < it.applied }
    val analysisFailures get() = chunks?.failures.orEmpty()
    val retrySegments get() = review?.let { state ->
        val seeds = state.plan?.incompleteSegments.orEmpty()
        if (seeds.isEmpty()) emptySet() else chunks?.let {
            NaturalBatchPlans.retryScope(state.input, it.plans, seeds)
        }
    }.orEmpty()

    fun requestCount(retry: Boolean = false): Int {
        val state = review ?: return 0
        return NaturalBatchChunks.partition(state.input,
            if (retry) retrySegments else state.input.segments().map { it.id }.toSet(),
            plans = if (retry) chunks?.plans.orEmpty() else emptyList()).size
    }

    fun stopAfterRequest() { stopAnalysis = true; signal() }

    fun open(preferredWorkId: Long) {
        if (review != null || busy) return
        busy = true; busyKind = BusyKind.LOADING; signal()
        viewModelScope.launch {
            try {
                val choices = withContext(Dispatchers.IO) {
                    val universes = db.universeDao().getAllUniversesList()
                    val works = db.novelDao().getAllNovelsList()
                    universes.map { ScopeOption(NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.UNIVERSE, it.id),
                        "세계관 · ${it.name}") } + works.map {
                        ScopeOption(NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.WORK, it.id), "작품 · ${it.title}")
                    }
                }
                options = choices
                val saved = slot.read(OWNER)
                storageFailed = slot.failed
                if (!storageFailed) {
                    if (saved != null) {
                        val restored = NaturalBatchReviewState(saved.review.input)
                        restored.restore(saved.review)
                        review = restored
                        context = saved.context?.restore()
                        chunks = saved.chunks
                        executions.clear(); executions.addAll(saved.executions)
                        results = saved.results
                        scrollPosition = saved.scrollPosition.coerceAtLeast(0)
                        expanded.clear(); expanded.addAll(saved.expanded)
                        if (restored.plan != null && context == null) {
                            storageFailed = true
                            message = "검토 대상 자료를 복구하지 못했습니다. 보관 파일은 유지했습니다."
                        }
                        val retained = withContext(Dispatchers.IO) {
                            executions.mapNotNull { record ->
                                val operations = db.naturalBatchJournalDao().operations(record.id)
                                if (operations.isEmpty()) null else record.copy(
                                    applied = operations.size, undone = operations.count { it.undone })
                            }
                        }
                        if (retained != executions) {
                            val missing = retained.size != executions.size
                            executions.clear(); executions.addAll(retained)
                            if (missing && !storageFailed) {
                                message = "일부 실행 기록을 찾지 못했습니다. Excel 덮어쓰기나 초기화 후에는 되돌릴 수 없습니다."
                            }
                            if (!storageFailed) checkpoint()
                        }
                    } else {
                        val choice = choices.firstOrNull { it.scope.kind == NaturalBatchInput.ScopeKind.WORK &&
                            it.scope.id == preferredWorkId } ?: choices.firstOrNull()
                        if (choice != null) {
                            review = NaturalBatchReviewState(NaturalBatchInput.create(choice.scope))
                            checkpoint()
                        }
                    }
                }
                if (choices.isEmpty()) message = "먼저 작품이나 세계관을 만들어 주세요."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                storageFailed = true
                message = "검토 내용을 열지 못했습니다: ${e.message ?: "저장 상태를 확인해 주세요"}"
            } finally { busy = false; busyKind = null; flushPendingInput(); signal() }
        }
    }

    fun chooseScope(scope: NaturalBatchInput.Scope) {
        if (busy || storageFailed || options.none { it.scope == scope }) return
        val state = review ?: NaturalBatchReviewState(NaturalBatchInput.create(scope)).also { review = it }
        state.changeScope(scope)
        context = null; prepared = null; chunks = null
        scrollPosition = 0; expanded.clear()
        message = "범위가 바뀌어 이전 분석과 선택을 비웠습니다. 원문을 확인한 뒤 다시 분석해 주세요."
        checkpoint()
    }

    fun clearAnalysis() {
        val state = review ?: return
        if (busy || storageFailed || state.plan == null) return
        state.clearAnalysis()
        context = null; prepared = null; chunks = null
        scrollPosition = 0; expanded.clear()
        message = "이전 분석과 선택을 비웠습니다. 원문을 확인한 뒤 다시 분석해 주세요."
        checkpoint()
    }

    fun editInput(text: String) {
        if (storageFailed) return
        if (busy && !analyzing) { pendingInput = text; return }
        val state = review ?: return
        if (state.input.text == text) return
        state.editInput(text)
        context = null; prepared = null; chunks = null
        scrollPosition = 0; expanded.clear()
        message = "원문이 바뀌어 이전 분석과 선택을 비웠습니다. 다시 분석해 주세요."
        checkpoint()
    }

    fun analyze(retry: Boolean = false) {
        val state = review ?: return
        if (busy || storageFailed || state.input.text.isBlank() ||
            (!retry && state.plan != null) || (retry && retrySegments.isEmpty())) return
        val input = state.input
        val requests = NaturalBatchChunks.partition(input,
            if (retry) retrySegments else input.segments().map { it.id }.toSet(),
            plans = if (retry) chunks?.plans.orEmpty() else emptyList())
        if (!slot.beginRequest()) { storageFailed = true; signal(); return }
        busy = true; busyKind = BusyKind.ANALYSIS; analyzing = true; message = null
        stopAnalysis = false; analysisDone = 0; analysisTotal = requests.size; prepared = null
        val request = if (retry) state.beginRetry() else state.beginAnalysis()
        signal()
        viewModelScope.launch {
            try {
                val ctx = if (retry) checkNotNull(context) else withContext(Dispatchers.IO) { analyzer.loadContext(input) }
                if (state.input != input) return@launch
                context = ctx
                var ledger = if (retry) checkNotNull(chunks) else NaturalBatchChunkState.create(input)
                chunks = ledger
                if (!state.acceptProgress(request, ledger.merge(input)) || !checkpoint()) return@launch
                for (ids in requests) {
                    if (stopAnalysis || state.input != input || storageFailed) break
                    val reservation = ledger.reserve(input, ids)
                    ledger = reservation.first; chunks = ledger
                    if (!state.acceptProgress(request, ledger.merge(input)) || !checkpoint()) break
                    val outcome = try {
                        withContext(Dispatchers.IO) { analyzer.analyzeChunk(input, ctx, ids, reservation.second) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { stopAnalysis = true; NaturalBatchAnalyzer.Outcome.Failed(
                        "분석을 마치지 못했습니다: ${e.message ?: "다시 시도해 주세요"}") }
                    if (state.input != input) break
                    ledger = when (outcome) {
                        is NaturalBatchAnalyzer.Outcome.Ready -> ledger.finish(reservation.second, outcome.plan)
                        is NaturalBatchAnalyzer.Outcome.Failed -> ledger.finish(reservation.second, null,
                            outcome.reason + if (outcome.billedResponse != null) " 응답 비용이 발생했을 수 있습니다." else "")
                    }
                    chunks = ledger
                    analysisDone++
                    if (!state.acceptProgress(request, ledger.merge(input)) || !checkpoint()) break
                    // Authentication, quota and network failures must not fan out into more paid attempts.
                    if (outcome is NaturalBatchAnalyzer.Outcome.Failed && outcome.providerFailure != null) {
                        stopAnalysis = true
                    }
                }
                if (!storageFailed && state.input == input) {
                    val merge = state.plan!!
                    message = if (!merge.complete)
                        "분석이 불완전합니다. 요청 $analysisDone/${requests.size}회 완료 · 미처리 ${merge.incompleteSegments.size}개 문단. 받은 제안은 보관했습니다. 미처리 재분석으로 이어갈 수 있습니다."
                    else if (merge.operations.isEmpty() && merge.unresolved.isEmpty() && merge.constraints.isEmpty() && merge.notes.isEmpty())
                        "변경 후보를 찾지 못했습니다. 원문과 범위를 확인해 주세요. 분석 비용이 발생했을 수 있습니다."
                    else "분석 결과를 검토해 주세요. 완료 요청 $analysisDone/${requests.size}회. 분석 비용이 발생했을 수 있습니다."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "분석을 마치지 못했습니다: ${e.message ?: "다시 시도해 주세요"}" }
            finally { slot.endRequest(); analyzing = false; busy = false; busyKind = null; flushPendingInput(); signal() }
        }
    }

    fun select(id: String, selected: Boolean) {
        val state = review ?: return
        val operation = state.plan?.operations?.firstOrNull { it.id == id } ?: return
        if (busy || storageFailed || !NaturalBatchReviewSelection.canSelect(operation,
                state.plan!!.conflicts)) return
        if (selected) state.confirm(id)
        state.setSelected(id, selected)
        prepared = null
        checkpoint()
    }

    fun editProposal(id: String, value: String) {
        val state = review ?: return
        val operation = state.plan?.operations?.firstOrNull { it.id == id } ?: return
        if (busy || storageFailed || !NaturalBatchReviewSelection.canSelect(operation,
                state.plan!!.conflicts) ||
            operation.kind !in NaturalBatchReviewSelection.fieldKinds ||
            operation.kind == NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE || value.isBlank()) return
        state.editProposal(id, value)
        prepared = null
        checkpoint()
    }

    fun editRelationship(id: String, draft: com.novelcharacter.app.ai.NaturalBatchRelationshipEdits.Draft) {
        val state = review ?: return
        val operation = state.plan?.operations?.firstOrNull { it.id == id } ?: return
        if (busy || storageFailed || !NaturalBatchReviewSelection.canSelect(operation, state.plan!!.conflicts) ||
            operation.kind !in NaturalBatchReviewSelection.relationshipKinds ||
            operation.kind == NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP ||
            draft.type !in context?.relationshipTypes.orEmpty() || draft.intensity !in 1..10) return
        state.editProposal(id, draft.encode())
        prepared = null
        checkpoint()
    }

    fun preflight() {
        val state = review ?: return
        val ctx = context ?: return
        if (busy || storageFailed || state.snapshot().selected.isEmpty()) return
        busy = true; busyKind = BusyKind.PREFLIGHT; prepared = null; message = null; signal()
        viewModelScope.launch {
            try {
                prepared = withContext(Dispatchers.IO) { executor.preflight(state.snapshot(), ctx) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "적용 전 검증에 실패했습니다: ${e.message ?: "다시 확인해 주세요"}" }
            finally { busy = false; busyKind = null; flushPendingInput(); signal() }
        }
    }

    fun editFaction(id: String, draft: com.novelcharacter.app.ai.NaturalBatchFactionEdits.Draft) {
        val state = review ?: return
        val operation = state.plan?.operations?.firstOrNull { it.id == id } ?: return
        if (busy || storageFailed || !NaturalBatchReviewSelection.canSelect(operation, state.plan!!.conflicts) ||
            operation.kind !in NaturalBatchReviewSelection.factionKinds) return
        if (operation.leaveMode == NaturalBatchPlan.LeaveMode.DEPART &&
            (draft.leaveYear == null || draft.type !in context?.relationshipTypes.orEmpty() ||
                draft.intensity == null || draft.intensity !in 1..10)) return
        state.editProposal(id, draft.encode())
        prepared = null
        checkpoint()
    }

    fun selectExtracted() {
        val state = review ?: return
        val plan = state.plan ?: return
        if (busy || storageFailed || !plan.complete) return
        plan.operations.filter { NaturalBatchReviewSelection.canBulkSelect(it, plan.conflicts) }
            .forEach { state.confirm(it.id); state.setSelected(it.id, true) }
        prepared = null
        checkpoint()
    }

    fun clearSelection() {
        val state = review ?: return
        if (busy || storageFailed) return
        state.snapshot().selected.forEach { state.setSelected(it, false) }
        prepared = null
        checkpoint()
    }

    fun applyPrepared() {
        val batch = prepared ?: return
        if (busy || storageFailed || batch.items.none { it.decision.status == NaturalBatchFieldExecutor.Status.READY }) return
        val id = UUID.randomUUID().toString()
        val previousResults = results
        results = null
        executions += ExecutionRecord(id, System.currentTimeMillis(), 0)
        if (!checkpoint()) { executions.removeAll { it.id == id }; results = previousResults; return }
        busy = true; busyKind = BusyKind.APPLY; prepared = null; signal()
        viewModelScope.launch {
            try {
                results = withContext(Dispatchers.IO) { executor.apply(batch, id) }
                val applied = results?.count { it.status == NaturalBatchFieldExecutor.Status.APPLIED } ?: 0
                if (applied == 0) executions.removeAll { it.id == id }
                else executions.replaceAll { if (it.id == id) it.copy(applied = applied) else it }
                message = "적용 결과를 확인해 주세요. 충돌한 항목은 원래 값을 유지했습니다."
                checkpoint()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                try {
                    val applied = reconcileExecution(id)
                    if (!storageFailed) message = if (applied > 0)
                        "적용 중 오류가 났지만 ${applied}건의 실행 기록을 찾았습니다. 되돌리기에서 확인할 수 있습니다: ${e.message}"
                    else "적용 중 오류가 났습니다. 실행 기록에 적용된 변경이 없습니다: ${e.message}"
                } catch (_: Exception) {
                    message = "적용 결과와 실행 기록을 확인하지 못했습니다. 앱을 다시 열어 복구 상태를 확인해 주세요: ${e.message}"
                }
            }
            finally { busy = false; busyKind = null; flushPendingInput(); signal() }
        }
    }

    fun undo(id: String) {
        if (undoChoices.none { it.id == id }) return
        if (busy || storageFailed) return
        results = null
        busy = true; busyKind = BusyKind.UNDO; message = null; signal()
        viewModelScope.launch {
            try {
                results = withContext(Dispatchers.IO) { executor.undo(id) }
                val undone = withContext(Dispatchers.IO) {
                    db.naturalBatchJournalDao().operations(id).count { it.undone }
                }
                executions.replaceAll { if (it.id == id) it.copy(undone = undone) else it }
                checkpoint()
                message = "되돌리기 결과를 확인해 주세요. 이후 바뀐 행은 덮어쓰지 않았습니다."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                try {
                    reconcileExecution(id)
                    if (!storageFailed) message = "되돌리기 중 오류가 났습니다. 실행 기록을 다시 읽었습니다: ${e.message ?: "다시 시도해 주세요"}"
                } catch (_: Exception) {
                    message = "되돌리기 결과를 확인하지 못했습니다. 앱을 다시 열어 복구 상태를 확인해 주세요: ${e.message}"
                }
            }
            finally { busy = false; busyKind = null; flushPendingInput(); signal() }
        }
    }

    fun recordScroll(position: Int) {
        val safe = position.coerceAtLeast(0)
        if (safe != scrollPosition && !storageFailed) {
            scrollPosition = safe
            checkpoint()
        }
    }

    fun isExpanded(id: String) = id in expanded

    fun toggleExpanded(id: String) {
        if (id in expanded) expanded.remove(id) else expanded.add(id)
        checkpoint()
    }

    private fun flushPendingInput() {
        val next = pendingInput ?: return
        pendingInput = null
        editInput(next)
    }

    private suspend fun reconcileExecution(id: String): Int {
        val operations = withContext(Dispatchers.IO) { db.naturalBatchJournalDao().operations(id) }
        if (operations.isEmpty()) executions.removeAll { it.id == id }
        else executions.replaceAll { record ->
            if (record.id == id) record.copy(applied = operations.size,
                undone = operations.count { it.undone }) else record
        }
        checkpoint()
        return operations.size
    }

    private fun checkpoint(): Boolean {
        val state = review ?: return false
        val okay = slot.save(SavedReview(state.snapshot(), context?.let(NaturalBatchContextSnapshot::from),
            executions.toList(), results, scrollPosition, expanded.toSet(), chunks))
        storageFailed = !okay
        if (!okay) message = "검토 내용을 보관하지 못했습니다. 저장 공간과 다른 편집 창을 확인해 주세요."
        signal()
        return okay
    }

    private fun signal() { updates.value = (updates.value ?: 0) + 1 }

    override fun onCleared() { slot.endRequest(); super.onCleared() }

    companion object {
        private const val OWNER = "natural-batch:active"
    }
}
