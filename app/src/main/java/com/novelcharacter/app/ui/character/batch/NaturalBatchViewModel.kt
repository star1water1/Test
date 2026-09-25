package com.novelcharacter.app.ui.character.batch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.NaturalBatchAnalyzer
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
class NaturalBatchViewModel(app: Application) : AndroidViewModel(app) {
    enum class BusyKind { LOADING, ANALYSIS, PREFLIGHT, APPLY, UNDO }
    data class ScopeOption(val scope: NaturalBatchInput.Scope, val label: String)
    data class ExecutionRecord(val id: String, val createdAt: Long,
        val applied: Int, val undone: Int = 0)
    private data class SavedReview(
        val review: NaturalBatchReviewState.Snapshot,
        val context: NaturalBatchContextSnapshot?, val executions: List<ExecutionRecord>,
        val results: List<NaturalBatchFieldExecutor.Decision>?, val scrollPosition: Int = 0,
        val expanded: Set<String> = emptySet()
    )

    private val db = AppDatabase.getDatabase(app)
    private val slot = ReviewSlot(app, SavedReview::class.java)
    private val analyzer = NaturalBatchAnalyzer(db, AiService(app))
    private val executor = NaturalBatchFieldExecutor(db)
    private var review: NaturalBatchReviewState? = null
    private var context: NaturalBatchContext? = null
    private var prepared: NaturalBatchFieldExecutor.Prepared? = null
    private var analyzing = false
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
        context = null; prepared = null
        scrollPosition = 0; expanded.clear()
        message = "범위가 바뀌어 이전 분석과 선택을 비웠습니다. 원문을 확인한 뒤 다시 분석해 주세요."
        checkpoint()
    }

    fun clearAnalysis() {
        val state = review ?: return
        if (busy || storageFailed || state.plan == null) return
        state.clearAnalysis()
        context = null; prepared = null
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
        context = null; prepared = null
        scrollPosition = 0; expanded.clear()
        message = "원문이 바뀌어 이전 분석과 선택을 비웠습니다. 다시 분석해 주세요."
        checkpoint()
    }

    fun analyze() {
        val state = review ?: return
        if (busy || storageFailed || state.input.text.isBlank() || state.plan != null) return
        if (!slot.beginRequest()) { storageFailed = true; signal(); return }
        busy = true; busyKind = BusyKind.ANALYSIS; analyzing = true; message = null; signal()
        val request = state.beginAnalysis()
        val input = state.input
        viewModelScope.launch {
            try {
                when (val outcome = withContext(Dispatchers.IO) { analyzer.analyze(input, request.generation) }) {
                    is NaturalBatchAnalyzer.Outcome.Ready -> {
                        val merge = NaturalBatchPlans.merge(input, listOf(outcome.plan))
                        if (state.accept(request, merge)) {
                            context = outcome.context
                            checkpoint()
                            message = if (merge.operations.isEmpty() && merge.unresolved.isEmpty() &&
                                merge.constraints.isEmpty() && merge.notes.isEmpty())
                                "변경 후보를 찾지 못했습니다. 원문과 범위를 확인해 주세요. 분석 요청은 비용이 발생했을 수 있습니다."
                            else "분석 결과를 검토해 주세요. 분석 요청은 비용이 발생했을 수 있습니다."
                        } else message = "분석 중 원문이 바뀌어 이전 응답을 검토에 넣지 않았습니다. 다시 분석해 주세요."
                    }
                    is NaturalBatchAnalyzer.Outcome.Failed -> {
                        message = outcome.reason + if (outcome.billedResponse != null)
                            "\n응답을 받았으므로 비용이 발생했을 수 있습니다." else ""
                    }
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
            operation.kind == NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE || value.isBlank()) return
        state.editProposal(id, value)
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

    fun selectExtracted() {
        val state = review ?: return
        val plan = state.plan ?: return
        if (busy || storageFailed) return
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
            executions.toList(), results, scrollPosition, expanded.toSet()))
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
