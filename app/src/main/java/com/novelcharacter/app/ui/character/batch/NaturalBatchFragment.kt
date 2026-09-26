package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.text.InputType
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.NaturalBatchContext
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor
import com.novelcharacter.app.ai.NaturalBatchPlan
import com.novelcharacter.app.ai.NaturalBatchReviewState
import com.novelcharacter.app.ai.NaturalBatchReviewSelection
import com.novelcharacter.app.ai.NaturalBatchRelationshipEdits
import com.novelcharacter.app.ai.NaturalBatchFactionEdits
import com.novelcharacter.app.util.FactionStanding
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import com.novelcharacter.app.ui.common.NaturalLanguageInput

/** Full-screen review. A model response is always inert until each selected row is confirmed. */
class NaturalBatchFragment : Fragment() {
    private sealed interface Row {
        data class Proposal(val operation: NaturalBatchPlan.Operation) : Row
        data class Info(val id: String, val title: String, val body: String) : Row
    }
    private val model: NaturalBatchViewModel by viewModels()
    private lateinit var scopeButton: MaterialButton
    private lateinit var editorPanel: LinearLayout
    private lateinit var editSourceButton: MaterialButton
    private lateinit var analyzeButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var summary: TextView
    private lateinit var notice: TextView
    private lateinit var resultButton: MaterialButton
    private lateinit var selectRow: LinearLayout
    private lateinit var selectExtractedButton: MaterialButton
    private lateinit var clearSelectionButton: MaterialButton
    private lateinit var list: RecyclerView
    private lateinit var applyButton: MaterialButton
    private lateinit var undoButton: MaterialButton
    private lateinit var filterButton: MaterialButton
    private val rows = ReviewAdapter()
    private var editorSession: String? = null
    private var editingSource = false
    private var filter = 0
    private var shownPreview: List<NaturalBatchFieldExecutor.Decision>? = null
    private var restoredScroll = false
    private var previousPlan: Any? = null
    private val busyBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { requestExit() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingSource = savedInstanceState?.getBoolean("editingSource") ?: false
        filter = savedInstanceState?.getInt("filter") ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(ctx).apply {
            title = "AI 자연어 일괄편집"
            setNavigationIcon(com.novelcharacter.app.R.drawable.ic_arrow_back)
            setNavigationOnClickListener { requestExit() }
        })
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        scopeButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "범위 선택"
            setOnClickListener { chooseScope() }
        }
        top.addView(scopeButton)
        editSourceButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "원문 보기·수정"
            setOnClickListener { revealSource() }
        }
        top.addView(editSourceButton)
        editorPanel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        top.addView(editorPanel)
        analyzeButton = MaterialButton(ctx).apply {
            text = "AI 분석 시작"
            setOnClickListener { confirmAnalysis() }
        }
        top.addView(analyzeButton)
        resetButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "새 분석 준비"
            setOnClickListener { confirmReset() }
        }
        top.addView(resetButton)
        summary = TextView(ctx).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(4)) }
        top.addView(summary)
        selectRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        selectExtractedButton = MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "추출 필드 선택"
            setOnClickListener { model.selectExtracted() }
        }
        selectRow.addView(selectExtractedButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clearSelectionButton = MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "선택 해제"
            setOnClickListener { model.clearSelection() }
        }
        selectRow.addView(clearSelectionButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(selectRow)
        notice = TextView(ctx).apply { textSize = 13f }
        top.addView(notice)
        resultButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "적용·되돌리기 결과 보기"
            setOnClickListener { showResults() }
        }
        top.addView(resultButton)
        root.addView(top)

        val listHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        listHeader.addView(TextView(ctx).apply {
            text = "검토 항목"
            textSize = 17f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "전체"
            setOnClickListener { anchor ->
                PopupMenu(ctx, anchor).apply {
                    menu.add(0, 0, 0, "전체")
                    menu.add(0, 1, 1, "필드 제안")
                    menu.add(0, 2, 2, "확인 필요·참고")
                    menu.add(0, 3, 3, "관계 제안")
                    menu.add(0, 4, 4, "세력 제안")
                    setOnMenuItemClickListener { item -> filter = item.itemId; render(); true }
                    show()
                }
            }
        }
        listHeader.addView(filterButton)
        root.addView(listHeader)
        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = rows
            clipToPadding = false
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        applyButton = MaterialButton(ctx).apply {
            text = "적용 전 확인"
            setOnClickListener { model.preflight() }
        }
        actions.addView(applyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "되돌리기"
            setOnClickListener { confirmUndo() }
        }
        actions.addView(undoButton)
        root.addView(actions)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.updates.observe(viewLifecycleOwner) { render() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, busyBack)
        model.open(arguments?.getLong("novelId", -1L) ?: -1L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editingSource", editingSource)
        outState.putInt("filter", filter)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.let(model::recordScroll)
        super.onStop()
    }

    private fun render() {
        val snapshot = model.snapshot
        scopeButton.text = model.options.firstOrNull { it.scope == snapshot?.input?.scope }?.label ?: "범위 선택"
        scopeButton.isEnabled = !model.busy && !model.storageFailed && model.options.isNotEmpty()
        if (snapshot != null && editorSession != snapshot.input.sessionId) {
            editorSession = snapshot.input.sessionId
            editorPanel.removeAllViews()
            editorPanel.addView(NaturalLanguageInput.create(this,
                "natural-batch:${snapshot.input.sessionId}",
                "여러 인물의 설정을 자유롭게 적어 주세요. 빈 줄로 문단을 나눌 수 있습니다.",
                snapshot.input.text, model::editInput))
            (editorPanel.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.let { input ->
                (input as? EditText)?.apply { maxLines = 6; isVerticalScrollBarEnabled = true }
            }
        }
        val plan = snapshot?.plan
        if (previousPlan != null && previousPlan !== plan) {
            list.scrollToPosition(0)
            restoredScroll = false
        }
        previousPlan = plan
        if (plan == null && !model.busy) editingSource = true
        editorPanel.isVisible = editingSource
        editSourceButton.isVisible = plan != null
        editSourceButton.text = if (editingSource) "원문 접기" else "원문 보기·수정"
        analyzeButton.isVisible = plan == null
        analyzeButton.isEnabled = snapshot?.input?.text?.isNotBlank() == true &&
            !model.busy && !model.storageFailed
        resetButton.isVisible = plan != null
        resetButton.isEnabled = !model.busy && !model.storageFailed
        val selected = snapshot?.selected?.size ?: 0
        summary.text = if (plan == null) {
            "범위와 원문을 확인한 뒤 분석하세요. 전사만으로 AI 요청이나 저장이 시작되지 않습니다."
        } else {
            "제안 ${plan.operations.size} · 관계 ${plan.operations.count { it.kind in NaturalBatchReviewSelection.relationshipKinds }} · " +
                "세력 ${plan.operations.count { it.kind in NaturalBatchReviewSelection.factionKinds }} · " +
                "선택 $selected · 창작 ${plan.operations.count { it.origin == NaturalBatchPlan.Origin.CREATIVE }} · " +
                "충돌 ${plan.conflicts.size} · 해석 불가 ${plan.unresolved.size} · 미처리 문단 ${plan.incompleteSegments.size}"
        }
        notice.text = when {
            model.storageFailed -> model.message ?: "보관한 검토를 열지 못했습니다. 저장 공간을 확인해 주세요."
            model.busy -> when (model.busyKind) {
                NaturalBatchViewModel.BusyKind.LOADING -> "보관한 검토를 여는 중입니다."
                NaturalBatchViewModel.BusyKind.ANALYSIS -> "AI 분석 중입니다. 완료될 때까지 이 화면에 머물러 주세요. 요청은 비용이 발생할 수 있습니다."
                NaturalBatchViewModel.BusyKind.PREFLIGHT -> "현재 값을 다시 확인하는 중입니다."
                NaturalBatchViewModel.BusyKind.APPLY -> "선택한 변경을 적용하는 중입니다. 실행 기록과 함께 저장합니다."
                NaturalBatchViewModel.BusyKind.UNDO -> "변경을 되돌리는 중입니다. 이후 바뀐 행은 덮어쓰지 않습니다."
                null -> "처리 중입니다."
            }
            model.message != null -> model.message
            plan != null && !plan.complete -> "분석이 불완전합니다. 미처리 원문과 다른 제안을 함께 확인해 주세요."
            else -> ""
        }
        notice.isVisible = notice.text.isNotBlank()
        selectRow.isVisible = plan != null
        selectExtractedButton.isEnabled = !model.busy && !model.storageFailed &&
            plan?.operations.orEmpty().any { NaturalBatchReviewSelection.canBulkSelect(it,
                plan?.conflicts.orEmpty()) && it.id !in snapshot?.selected.orEmpty() }
        clearSelectionButton.isEnabled = selected > 0 && !model.busy && !model.storageFailed
        resultButton.isVisible = model.results != null
        applyButton.text = "선택 ${selected}건 적용 전 확인"
        applyButton.isEnabled = selected > 0 && !model.busy && !model.storageFailed
        undoButton.isVisible = model.canUndo
        undoButton.isEnabled = !model.busy && !model.storageFailed
        busyBack.isEnabled = model.busyKind in setOf(NaturalBatchViewModel.BusyKind.ANALYSIS,
            NaturalBatchViewModel.BusyKind.APPLY, NaturalBatchViewModel.BusyKind.UNDO)
        filterButton.text = when (filter) { 1 -> "필드 제안"; 2 -> "확인 필요·참고"; 3 -> "관계 제안"; 4 -> "세력 제안"; else -> "전체" }
        rows.submit(snapshot, model.analysisContext)
        if (!restoredScroll && plan != null) {
            restoredScroll = true
            list.post { (list.layoutManager as? LinearLayoutManager)?.scrollToPosition(model.scrollPosition) }
        }
        val preview = model.preview
        if (preview == null) shownPreview = null
        else if (preview !== shownPreview && !model.busy) {
            shownPreview = preview
            showPreflight(preview)
        }
    }

    private fun chooseScope() {
        val choices = model.options
        if (choices.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext()).setTitle("분석 범위 선택")
            .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                val chosen = choices[index].scope
                if (model.snapshot?.plan != null && chosen != model.snapshot?.input?.scope) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle("범위 변경")
                        .setMessage("범위를 바꾸면 현재 분석과 선택이 오래된 결과가 됩니다. 원문은 유지하고 다시 분석할 수 있습니다.")
                        .setPositiveButton("범위 변경") { _, _ -> model.chooseScope(chosen) }
                        .setNegativeButton("취소", null).show()
                } else model.chooseScope(chosen)
            }.setNegativeButton("취소", null).show()
    }

    private fun requestExit() {
        when (model.busyKind) {
            NaturalBatchViewModel.BusyKind.APPLY, NaturalBatchViewModel.BusyKind.UNDO -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle("변경 처리 중")
                    .setMessage("실행 결과를 확인할 때까지 기다려 주세요. 완료 후 안전하게 이동할 수 있습니다.")
                    .setPositiveButton("확인", null).show()
                return
            }
            NaturalBatchViewModel.BusyKind.ANALYSIS -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle("AI 분석 중")
                    .setMessage("보낸 요청의 응답을 보관할 때까지 기다려 주세요. 이미 보낸 AI 요청은 비용이 발생할 수 있습니다.")
                    .setPositiveButton("계속 기다리기", null).show()
                return
            }
            else -> { findNavController().popBackStack(); return }
        }
    }

    private fun revealSource() {
        if (editingSource) { editingSource = false; render(); return }
        MaterialAlertDialogBuilder(requireContext()).setTitle("원문 수정")
            .setMessage("원문을 바꾸면 현재 분석과 선택이 오래된 결과가 됩니다. 수정 후 다시 분석해야 합니다.")
            .setPositiveButton("원문 열기") { _, _ -> editingSource = true; render() }
            .setNegativeButton("취소", null).show()
    }

    private fun confirmAnalysis() {
        val input = model.snapshot?.input ?: return
        if (input.text.isBlank()) return
        if (!AiService(requireContext()).hasUsableProvider()) {
            MaterialAlertDialogBuilder(requireContext()).setTitle("AI 설정 필요")
                .setMessage("사용할 AI 제공자와 키를 설정한 뒤 다시 분석해 주세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("AI 분석 시작")
            .setMessage("원문 ${input.segments().size}개 문단을 분석합니다. 예상 요청은 1회이며, 응답이 잘리거나 형식이 맞지 않아도 비용이 발생할 수 있습니다. 제안은 검토 전에는 저장되지 않습니다.")
            .setPositiveButton("분석") { _, _ -> editingSource = false; model.analyze() }
            .setNegativeButton("취소", null).show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("새 분석 준비")
            .setMessage("현재 제안과 선택을 비웁니다. 이미 적용한 변경과 되돌리기 기록은 유지됩니다. 다시 AI 분석을 시작하면 비용이 발생할 수 있습니다.")
            .setPositiveButton("제안 비우기") { _, _ -> model.clearAnalysis() }
            .setNegativeButton("취소", null).show()
    }

    private fun showPreflight(decisions: List<NaturalBatchFieldExecutor.Decision>) {
        val ready = decisions.count { it.status == NaturalBatchFieldExecutor.Status.READY }
        val details = decisions.joinToString("\n") { decision ->
            "· ${labelFor(decision.operationId)}: ${statusLabel(decision.status)}" +
                (if (decision.status == NaturalBatchFieldExecutor.Status.READY)
                    " (${decision.before ?: "비어 있음"} → ${decision.after ?: "비어 있음"})" else "") +
                (decision.reason.takeIf(String::isNotBlank)?.let { " — $it" } ?: "")
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("적용 전 재검증")
            .setMessage("적용 가능 ${ready}건 · 제외 ${decisions.size - ready}건\n\n$details\n\n적용 직전에도 현재 값을 다시 확인합니다.")
            .setPositiveButton(if (ready > 0) "적용 ${ready}건" else "확인") { _, _ ->
                if (ready > 0) model.applyPrepared()
            }
            .setNegativeButton("돌아가기", null).show()
    }

    private fun confirmUndo() {
        val choices = model.undoChoices
        if (choices.isEmpty()) return
        val dateFormat = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        MaterialAlertDialogBuilder(requireContext()).setTitle("되돌릴 실행 선택")
            .setItems(choices.map { record ->
                "${dateFormat.format(java.util.Date(record.createdAt))} · 적용 ${record.applied}건 · 되돌림 ${record.undone}건"
            }.toTypedArray()) { _, index ->
                MaterialAlertDialogBuilder(requireContext()).setTitle("변경 되돌리기")
                    .setMessage("이 실행에서 바뀐 행만 되돌립니다. 이후 다른 곳에서 수정된 행은 충돌로 남고 덮어쓰지 않습니다.")
                    .setPositiveButton("되돌리기") { _, _ -> model.undo(choices[index].id) }
                    .setNegativeButton("취소", null).show()
            }.setNegativeButton("취소", null).show()
    }

    private fun showResults() {
        val results = model.results ?: return
        MaterialAlertDialogBuilder(requireContext()).setTitle("실행 결과")
            .setMessage(results.groupingBy { it.status }.eachCount().entries.joinToString(" · ") {
                "${statusLabel(it.key)} ${it.value}건"
            } + "\n\n" + results.joinToString("\n") {
                "· ${labelFor(it.operationId)}: ${statusLabel(it.status)}" +
                    (it.reason.takeIf(String::isNotBlank)?.let { reason -> " — $reason" } ?: "")
            })
            .setPositiveButton("확인", null).show()
    }

    private fun labelFor(id: String): String {
        val operation = model.snapshot?.plan?.operations?.firstOrNull { it.id == id }
            ?: return id.substringAfter('|')
        val context = model.analysisContext
        val character = context?.characters?.get(operation.targetRef)?.name ?: "대상 확인"
        val field = if (operation.kind in NaturalBatchReviewSelection.relationshipKinds) {
            val old = operation.relationshipRef?.let { context?.relationships?.get(it) }
            val relatedId = if (old == null) operation.relatedRef?.let { context?.characters?.get(it)?.id }
                else if (old.firstId == context?.characters?.get(operation.targetRef)?.id) old.secondId else old.firstId
            "${context?.characters?.values?.firstOrNull { it.id == relatedId }?.name ?: "대상 확인"} · ${kindLabel(operation.kind)}"
        } else if (operation.kind in NaturalBatchReviewSelection.factionKinds)
            "${operation.factionRef?.let { context?.factions?.get(it)?.name } ?: "세력 확인 필요"} · ${kindLabel(operation.kind)}"
        else operation.fieldRef?.let { context?.fields?.get(it)?.name } ?: kindLabel(operation.kind)
        return "$character · $field"
    }

    private fun editValue(operation: NaturalBatchPlan.Operation) {
        val value = model.snapshot?.edits?.get(operation.id) ?: operation.value.orEmpty()
        val field = EditText(requireContext()).apply {
            setText(value); setSelection(text.length)
            setSingleLine(false); minLines = 2
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("제안값 직접 수정")
            .setMessage("수정한 값은 이 항목만 다시 확인해야 적용할 수 있습니다.")
            .setView(field)
            .setPositiveButton("수정", null)
            .setNegativeButton("취소", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = field.text.toString()
                if (next.isBlank()) { field.error = "값을 입력해 주세요"; return@setOnClickListener }
                model.editProposal(operation.id, next)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun relationshipDetails(operation: NaturalBatchPlan.Operation): String {
        val context = model.analysisContext ?: return "관계 자료 확인 필요"
        val old = operation.relationshipRef?.let(context.relationships::get)
        val draft = NaturalBatchRelationshipEdits.Draft.from(operation, context,
            model.snapshot?.edits?.get(operation.id))
        fun name(id: Long?) = context.characters.values.firstOrNull { it.id == id }?.name ?: "대상 확인 필요"
        val first = old?.firstId ?: context.characters[operation.targetRef]?.id
        val second = old?.secondId ?: operation.relatedRef?.let { context.characters[it]?.id }
        fun describe(type: String, description: String, intensity: Int, bidirectional: Boolean) =
            "${name(first)} ${if (bidirectional) "↔" else "→"} ${name(second)} · $type · 강도 $intensity" +
                description.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
        val before = old?.let { describe(it.type, it.description, it.intensity, it.bidirectional) } ?: "(관계 없음)"
        val after = if (operation.kind == NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP) "(관계 제거)"
            else draft?.let { describe(it.type, it.description, it.intensity, it.bidirectional) } ?: "제안 확인 필요"
        return "현재 $before\n변경 $after"
    }

    private fun editRelationship(operation: NaturalBatchPlan.Operation) {
        val context = model.analysisContext ?: return
        val draft = NaturalBatchRelationshipEdits.Draft.from(operation, context,
            model.snapshot?.edits?.get(operation.id)) ?: return
        val types = context.relationshipTypes.orEmpty()
        if (types.isEmpty()) return
        val form = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        form.addView(TextView(requireContext()).apply { text = relationshipDetails(operation) })
        form.addView(TextView(requireContext()).apply { text = "관계 유형" })
        val type = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(draft.type).coerceAtLeast(0))
        }
        form.addView(type)
        val both = CheckBox(requireContext()).apply { text = "양방향 관계"; isChecked = draft.bidirectional }
        form.addView(both)
        form.addView(TextView(requireContext()).apply {
            text = "양방향을 끄면 위에 표시된 첫 번째 인물에서 두 번째 인물로 향하는 관계가 됩니다."
            textSize = 13f
        })
        val intensity = EditText(requireContext()).apply {
            hint = "관계 강도 (1~10)"; setText(draft.intensity.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        form.addView(intensity)
        val description = EditText(requireContext()).apply {
            hint = "관계 설명"; setText(draft.description); minLines = 2; maxLines = 5
        }
        form.addView(description)
        val scroll = cappedScrollView(requireContext()).apply { addView(form) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("관계 제안 직접 수정")
            .setMessage("수정한 관계는 이 항목을 다시 선택해야 적용됩니다.")
            .setView(scroll).setPositiveButton("수정", null).setNegativeButton("취소", null).create()
        dialog.setValidatedPositiveButton {
            val number = intensity.text.toString().toIntOrNull()
            if (number == null || number !in 1..10) {
                intensity.showInlineError("1부터 10까지 입력하세요")
                false
            } else {
                model.editRelationship(operation.id, NaturalBatchRelationshipEdits.Draft(
                    types[type.selectedItemPosition], description.text.toString(), number, both.isChecked))
                true
            }
        }
        dialog.show()
    }

    private fun statusLabel(status: NaturalBatchFieldExecutor.Status) = when (status) {
        NaturalBatchFieldExecutor.Status.READY -> "적용 가능"
        NaturalBatchFieldExecutor.Status.APPLIED -> "적용됨"
        NaturalBatchFieldExecutor.Status.ALREADY_SATISFIED -> "이미 충족"
        NaturalBatchFieldExecutor.Status.ALREADY_APPLIED -> "이미 적용"
        NaturalBatchFieldExecutor.Status.STALE -> "원본 변경"
        NaturalBatchFieldExecutor.Status.MISSING_TARGET -> "대상 없음"
        NaturalBatchFieldExecutor.Status.MISSING_FIELD -> "필드 없음"
        NaturalBatchFieldExecutor.Status.FIELD_CHANGED -> "필드 설정 변경"
        NaturalBatchFieldExecutor.Status.INVALID_VALUE -> "값 확인 필요"
        NaturalBatchFieldExecutor.Status.UNSUPPORTED -> "아직 적용 불가"
        NaturalBatchFieldExecutor.Status.FAILED -> "실패"
        NaturalBatchFieldExecutor.Status.UNDONE -> "되돌림"
        NaturalBatchFieldExecutor.Status.UNDO_CONFLICT -> "되돌리기 충돌"
        NaturalBatchFieldExecutor.Status.ALREADY_UNDONE -> "이미 되돌림"
    }

    private fun factionDetails(operation: NaturalBatchPlan.Operation): String {
        val context = model.analysisContext ?: return "세력 자료 확인 필요"
        val faction = operation.factionRef?.let(context.factions::get) ?: return "세력 확인 필요"
        val target = context.characters[operation.targetRef] ?: return "인물 확인 필요"
        val memberships = context.memberships ?: return "이전 검토에는 소속 이력이 없습니다. 다시 분석해 주세요"
        val pair = memberships.filter { it.factionId == faction.id && it.characterId == target.id }
        val active = pair.filter { FactionStanding.isCurrent(it) }
        val draft = NaturalBatchFactionEdits.Draft.from(operation, model.snapshot?.edits?.get(operation.id))
            ?: return "소속 제안을 직접 수정해 주세요"
        val current = active.singleOrNull()?.let { "소속 중 · 가입 ${it.joinYear?.toString() ?: "시점 불명"}" }
            ?: if (active.size > 1) "활성 소속 여러 줄 · 이력 확인 필요" else "현재 소속 없음"
        val proposed = when {
            operation.kind == NaturalBatchPlan.Kind.JOIN_FACTION -> "가입 · ${draft.joinYear?.toString() ?: "시점 불명"}"
            operation.leaveMode == NaturalBatchPlan.LeaveMode.REMOVE -> "현재 소속 제거 · 자동 관계와 변화 이력도 삭제"
            else -> "설정상 탈퇴 · ${draft.leaveYear?.toString() ?: "연도 선택 필요"}\n" +
                "탈퇴 후 관계 ${draft.type ?: "유형 선택 필요"} · 강도 ${draft.intensity?.toString() ?: "선택 필요"}"
        }
        return "${faction.name}\n현재 $current\n변경 $proposed\n" +
            "과거 소속 이력 ${pair.count { !FactionStanding.isCurrent(it) }}건 유지 · 다른 세력 소속 유지\n" +
            "자동 관계의 생성·삭제·변화 수는 적용 전 확인에서 표시합니다."
    }

    private fun editFaction(operation: NaturalBatchPlan.Operation) {
        val context = model.analysisContext ?: return
        val draft = NaturalBatchFactionEdits.Draft.from(operation, model.snapshot?.edits?.get(operation.id)) ?: return
        val joining = operation.kind == NaturalBatchPlan.Kind.JOIN_FACTION
        val form = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        form.addView(TextView(requireContext()).apply { text = factionDetails(operation) })
        val year = EditText(requireContext()).apply {
            hint = if (joining) "가입 연도 (비우면 시점 불명)" else "탈퇴 연도"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText((if (joining) draft.joinYear else draft.leaveYear)?.toString().orEmpty())
        }
        form.addView(year)
        val types = listOf("관계 유형 선택") + context.relationshipTypes.orEmpty()
        val type = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(draft.type).coerceAtLeast(0))
        }
        val intensity = EditText(requireContext()).apply {
            hint = "탈퇴 후 관계 강도 (1~10)"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(draft.intensity?.toString().orEmpty())
        }
        if (!joining) {
            form.addView(TextView(requireContext()).apply { text = "탈퇴 후 관계 유형" })
            form.addView(type); form.addView(intensity)
        }
        val error = TextView(requireContext()).apply { textSize = 13f }
        form.addView(error)
        val scroll = cappedScrollView(requireContext()).apply { addView(form) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("소속 제안 직접 수정")
            .setMessage("수정 후 이 항목을 다시 선택하세요. 다른 소속과 과거 이력은 유지합니다.")
            .setView(scroll).setPositiveButton("수정", null).setNegativeButton("취소", null).create()
        dialog.setValidatedPositiveButton {
            val rawYear = year.text.toString().trim()
            val number = rawYear.toIntOrNull()
            val strength = intensity.text.toString().toIntOrNull()
            when {
                (rawYear.isNotEmpty() && number == null) || (!joining && number == null) -> {
                    year.showInlineError("정수 연도를 입력하세요"); false
                }
                !joining && type.selectedItemPosition == 0 -> {
                    error.text = "탈퇴 후 관계 유형을 선택하세요"; false
                }
                !joining && (strength == null || strength !in 1..10) -> {
                    intensity.showInlineError("1부터 10까지 입력하세요"); false
                }
                else -> {
                    model.editFaction(operation.id, NaturalBatchFactionEdits.Draft(
                        if (joining) number else null, if (joining) null else number,
                        if (joining) null else types[type.selectedItemPosition], if (joining) null else strength))
                    true
                }
            }
        }
        dialog.show()
    }

    private fun kindLabel(kind: NaturalBatchPlan.Kind) = when (kind) {
        NaturalBatchPlan.Kind.SET_FIELD_VALUE -> "값 지정"
        NaturalBatchPlan.Kind.ADD_FIELD_VALUE -> "값 추가"
        NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE -> "값 제거"
        NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE -> "값 비우기"
        NaturalBatchPlan.Kind.ADD_RELATIONSHIP -> "관계 추가"
        NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP -> "관계 변경"
        NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP -> "관계 제거"
        NaturalBatchPlan.Kind.JOIN_FACTION -> "세력 가입"
        NaturalBatchPlan.Kind.LEAVE_FACTION -> "세력 탈퇴"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class ReviewAdapter : RecyclerView.Adapter<ReviewAdapter.Holder>() {
        private var items: List<Row> = emptyList()
        private var snapshot: NaturalBatchReviewState.Snapshot? = null
        private var context: NaturalBatchContext? = null

        fun submit(next: NaturalBatchReviewState.Snapshot?, analysis: NaturalBatchContext?) {
            snapshot = next; context = analysis
            val plan = next?.plan
            val proposals = plan?.operations.orEmpty().map(Row::Proposal)
            fun source(ids: List<String>): String = next?.input?.segments()
                ?.filter { it.id in ids }?.joinToString("\n\n") { it.text }.orEmpty()
            val info = buildList {
                plan?.unresolved.orEmpty().forEach {
                    add(Row.Info(it.id, "해석 불가", "${it.text}\n${it.reason}\n원문: ${source(it.segmentIds)}"))
                }
                plan?.constraints.orEmpty().forEach {
                    add(Row.Info(it.id, "상대 조건 · ${it.comparison}",
                        "${it.description}\n원문: ${source(it.segmentIds)}"))
                }
                plan?.notes.orEmpty().forEach {
                    add(Row.Info(it.id, "창작 참고 메모", "${it.text}\n원문: ${source(it.segmentIds)}"))
                }
                plan?.incompleteSegments.orEmpty().forEach { id ->
                    add(Row.Info(id, "미처리 원문", next?.input?.segments()?.firstOrNull { it.id == id }?.text.orEmpty()))
                }
            }
            items = when (filter) {
                1 -> proposals.filter { it.operation.kind in NaturalBatchReviewSelection.fieldKinds }
                2 -> proposals.filter { !NaturalBatchReviewSelection.canBulkSelect(it.operation, plan!!.conflicts) } + info
                3 -> proposals.filter { it.operation.kind in NaturalBatchReviewSelection.relationshipKinds }
                4 -> proposals.filter { it.operation.kind in NaturalBatchReviewSelection.factionKinds }
                else -> proposals + info
            }
            notifyDataSetChanged()
        }

        inner class Holder(val card: MaterialCardView, val column: LinearLayout) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
            }
            val card = MaterialCardView(ctx).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
                addView(column)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), dp(4), dp(2), dp(8)) }
            }
            return Holder(card, column)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            val column = holder.column
            val ctx = column.context
            column.removeAllViews()
            when (row) {
                is Row.Info -> {
                    column.addView(TextView(ctx).apply { text = row.title; textSize = 16f })
                    column.addView(TextView(ctx).apply { text = row.body; textSize = 14f })
                }
                is Row.Proposal -> {
                    val operation = row.operation
                    val target = context?.characters?.get(operation.targetRef)?.name ?: "대상 확인 필요"
                    val field = operation.fieldRef?.let { context?.fields?.get(it)?.name }
                    val supported = operation.kind in NaturalBatchReviewSelection.supportedKinds
                    val conflict = operation.id in snapshot?.plan?.conflicts.orEmpty()
                    val title = if (operation.kind in NaturalBatchReviewSelection.relationshipKinds ||
                        operation.kind in NaturalBatchReviewSelection.factionKinds) labelFor(operation.id)
                        else if (field != null) "$target · $field · ${kindLabel(operation.kind)}"
                        else "$target · ${kindLabel(operation.kind)}"
                    column.addView(CheckBox(ctx).apply {
                        text = title
                        textSize = 16f
                        isChecked = model.snapshot?.selected?.contains(operation.id) == true
                        isEnabled = supported && !conflict && !model.busy && !model.storageFailed
                        setOnCheckedChangeListener { _, checked -> model.select(operation.id, checked) }
                    })
                    val origin = when (operation.origin) {
                        NaturalBatchPlan.Origin.EXTRACTED -> "원문 추출"
                        NaturalBatchPlan.Origin.DERIVED -> "추론 · 확인 필요"
                        NaturalBatchPlan.Origin.CREATIVE -> "AI 창작 · 기본 미선택"
                    }
                    val warning = when {
                        conflict -> "충돌 · 같은 대상의 다른 제안과 함께 적용할 수 없습니다"
                        !supported -> "지원하지 않는 제안"
                        !operation.evidence.matched -> "원문 인용 불일치 · 직접 확인 필요"
                        operation.destructive -> "제거 변경 · 직접 선택해야 적용됩니다"
                        operation.kind in NaturalBatchReviewSelection.relationshipKinds -> "양쪽 인물과 방향을 확인한 뒤 직접 선택하세요"
                        operation.kind in NaturalBatchReviewSelection.factionKinds -> "소속 이력과 자동 관계 파급을 확인한 뒤 직접 선택하세요"
                        else -> ""
                    }
                    column.addView(TextView(ctx).apply {
                        text = listOf(kindLabel(operation.kind), origin, warning)
                            .filter(String::isNotBlank).joinToString(" · ")
                        textSize = 12f
                    })
                    val before = operation.fieldRef?.let { context?.values?.get(operation.targetRef to it) }
                    val edited = model.snapshot?.edits?.get(operation.id)
                    column.addView(TextView(ctx).apply {
                        val current = before?.ifBlank { "(비어 있음)" } ?: "(비어 있음)"
                        val proposal = edited ?: operation.value
                        text = if (operation.kind in NaturalBatchReviewSelection.relationshipKinds)
                            relationshipDetails(operation) else if (operation.kind in NaturalBatchReviewSelection.factionKinds)
                            factionDetails(operation) else when (operation.kind) {
                            NaturalBatchPlan.Kind.ADD_FIELD_VALUE -> "현재 $current\n추가할 값 ${proposal.orEmpty()}"
                            NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE -> "현재 $current\n제거할 값 ${proposal.orEmpty()}"
                            else -> "$current  →  ${proposal ?: "(비우기)"}"
                        }
                        textSize = 15f
                        setPadding(0, dp(7), 0, dp(5))
                    })
                    val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    actions.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = if (model.isExpanded(operation.id)) "근거 접기" else "근거 보기"
                        setOnClickListener { model.toggleExpanded(operation.id) }
                    })
                    if (supported && !conflict && operation.kind !in setOf(
                            NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE, NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP) &&
                        !(operation.kind == NaturalBatchPlan.Kind.LEAVE_FACTION && operation.leaveMode == NaturalBatchPlan.LeaveMode.REMOVE)) {
                        actions.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            text = if (operation.kind in NaturalBatchReviewSelection.relationshipKinds) "관계 수정"
                                else if (operation.kind in NaturalBatchReviewSelection.factionKinds) "소속 수정" else "값 수정"
                            isEnabled = !model.busy && !model.storageFailed
                            setOnClickListener {
                                if (operation.kind in NaturalBatchReviewSelection.relationshipKinds) editRelationship(operation)
                                else if (operation.kind in NaturalBatchReviewSelection.factionKinds) editFaction(operation)
                                else editValue(operation)
                            }
                        })
                    }
                    column.addView(actions)
                    if (model.isExpanded(operation.id)) {
                        val source = snapshot?.input?.segments()?.filter { it.id in operation.evidence.segmentIds }
                            ?.joinToString("\n\n") { it.text }.orEmpty()
                        column.addView(TextView(ctx).apply {
                            text = "원문: $source\n인용: ${operation.evidence.quote}" +
                                if (operation.evidence.matched) "" else "\n인용이 원문과 일치하지 않아 확인이 필요합니다."
                            textSize = 13f
                        })
                    }
                }
            }
        }
    }
}
