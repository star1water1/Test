package com.novelcharacter.app.ui.character

import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.notifyError
import com.novelcharacter.app.util.notifySuccess
import kotlinx.coroutines.launch

/**
 * AI 필드 값 추천 플로우 (온디맨드, docs/ai_integration.md 계약):
 * 프로바이더 가드 → 비용 고지(전체 모드: 입력된 필드 포함 여부 선택) → 실행
 * → 검토(필드 1개: 확인 다이얼로그 / 전체: 체크리스트 — 빈 필드 기본 선택, 덮어쓰기 기본 해제)
 * → 선택 적용(폼 위젯에만 기입 — 저장을 눌러야 영속화, __birth 동기화는 저장 체인이 수행).
 * AI 출력은 어떤 경우에도 자동 적용·DB 직접 기록되지 않는다.
 *
 * 실행 자체는 [CharacterViewModel.runAiSuggest](회전 생존)가 수행하고, 이 오브젝트는
 * 진입 다이얼로그와 결과 표시([showResult] — 편집 화면의 aiSuggestResult 관측이 호출)만 담당한다.
 * 결과 소비(clear)는 결과 다이얼로그의 액션 시점 — 검토 중 회전해도 유료 응답이 생존한다.
 */
object AiFieldSuggestSheet {

    /** 필드 1개 추천 — 폼의 ✨ 버튼 진입점 */
    fun showForField(
        fragment: Fragment,
        field: FieldDefinition,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        if (!guardProvider(fragment)) return

        val currentValue = currentValuesByFieldId(formBuilder)[field.id] ?: ""
        val spec = CharacterFieldAiSuggester.fieldSpecOf(field, currentValue) ?: return

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_suggest_title)
            .setMessage(fragment.getString(R.string.ai_field_cost_notice_single, field.name))
            .setPositiveButton(R.string.ai_field_run) { _, _ ->
                runSuggest(fragment, viewModel, contextLoader, listOf(spec), singleMode = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 전체 필드 추천 — 편집 화면의 'AI 필드 추천' 버튼 진입점 */
    fun showForCharacter(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        if (!guardProvider(fragment)) return

        val currentValues = currentValuesByFieldId(formBuilder)
        val allSpecs = formBuilder.fieldDefinitions.mapNotNull { fd ->
            CharacterFieldAiSuggester.fieldSpecOf(fd, currentValues[fd.id] ?: "")?.let { fd.id to it }
        }
        if (allSpecs.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.ai_field_no_targets)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        val emptyIds = formBuilder.emptyEditableFieldIds()
        val emptySpecs = allSpecs.filter { it.first in emptyIds }.map { it.second }
        val allSpecList = allSpecs.map { it.second }

        // 비용 고지 + '이미 입력된 필드도 포함' 선택 (기본: 빈 필드만)
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val message = TextView(context).apply {
            textSize = 15f
        }
        val includeFilledCheck = CheckBox(context).apply {
            text = fragment.getString(R.string.ai_field_include_filled)
            isChecked = false
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(message)
            addView(includeFilledCheck)
        }

        fun currentTargets(): List<CharacterFieldAiSuggester.FieldSpec> =
            if (includeFilledCheck.isChecked) allSpecList else emptySpecs

        // 청킹은 활성 프로바이더의 출력 상한에서 파생되므로, 고지도 **같은 값**으로 계산해야 한다.
        // 상수로 계산하면 사용자가 상한을 올렸을 때 고지된 요청 수와 실제가 어긋난다.
        val budget = AiService(context).effectiveMaxTokens()

        fun refreshMessage() {
            val count = currentTargets().size
            message.text = if (count == 0) {
                fragment.getString(R.string.ai_field_no_empty_fields)
            } else {
                // 요청 수는 청킹 규칙과 같은 계산 — 사전 고지 정확성 (R-4)
                fragment.getString(
                    R.string.ai_field_cost_notice,
                    count, CharacterFieldAiSuggester.requestCountFor(count, budget)
                )
            }
        }
        refreshMessage()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_suggest_title)
            .setView(panel)
            .setPositiveButton(R.string.ai_field_run, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val runButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            runButton.isEnabled = currentTargets().isNotEmpty()
            includeFilledCheck.setOnCheckedChangeListener { _, _ ->
                refreshMessage()
                runButton.isEnabled = currentTargets().isNotEmpty()
            }
            runButton.setOnClickListener {
                val targets = currentTargets()
                if (targets.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                runSuggest(fragment, viewModel, contextLoader, targets, singleMode = false)
            }
        }
        dialog.show()
    }

    // ===== 공통 파이프라인 =====

    private fun guardProvider(fragment: Fragment): Boolean {
        val context = fragment.requireContext()
        if (AiService(context).hasUsableProvider()) return true
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_suggest_title)
            .setMessage(R.string.field_library_ai_not_configured)
            .setPositiveButton(R.string.ai_settings_title) { _, _ ->
                fragment.findNavController().navigate(R.id.aiSettingsFragment)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return false
    }

    /** 폼 라이브 위젯의 현재 값 (fieldDefinitionId → 값). 빈 값 필드는 미포함 */
    private fun currentValuesByFieldId(formBuilder: DynamicFieldFormBuilder): Map<Long, String> =
        formBuilder.collectFieldValues(0L).associate { it.fieldDefinitionId to it.value }

    /**
     * 컨텍스트 조립(뷰 접근이라 뷰 수명 스코프 — 이 단계 취소는 과금 전이므로 무해) 후
     * 실행은 VM에 위임한다. 진행 표시·결과 수신은 편집 화면의 관측자가 담당.
     */
    private fun runSuggest(
        fragment: Fragment,
        viewModel: CharacterViewModel,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext,
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        singleMode: Boolean
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val aiContext = contextLoader()
            if (!fragment.isAdded) return@launch
            if (!viewModel.runAiSuggest(aiContext, targets, singleMode)) {
                // 이미 실행 중 — 무통보로 삼키지 않는다
                Toast.makeText(fragment.requireContext(), R.string.ai_field_running, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 결과 표시 진입점 — 편집 화면의 aiSuggestResult 관측이 호출한다.
     * 빈 결과(전량 실패·전량 드롭)도 반드시 고지한다 (변수 제어 — 무통보 소멸 금지).
     */
    fun showResult(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        run: CharacterViewModel.AiSuggestRun
    ) {
        val context = fragment.requireContext()
        val outcome = run.outcome
        if (outcome.suggestions.isEmpty()) {
            val message = buildString {
                append(fragment.getString(R.string.ai_field_nothing))
                outcome.failures.forEach { append("\n· ").append(it) }
                if (outcome.droppedCount > 0) {
                    append("\n· ").append(fragment.getString(R.string.ai_field_dropped, outcome.droppedCount))
                }
                outcome.truncationNotes.forEach {
                    append("\n· ").append(fragment.getString(R.string.ai_field_truncated_prefix, it))
                }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ai_field_suggest_title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm) { _, _ -> viewModel.clearAiSuggestResult() }
                .setOnCancelListener { viewModel.clearAiSuggestResult() }
                .show()
            return
        }

        if (run.singleMode) {
            showSingleConfirm(fragment, formBuilder, viewModel, run)
        } else {
            showReviewDialog(fragment, formBuilder, viewModel, run)
        }
    }

    /** 공통 상단 고지 — 토큰 사용·드롭·절단·부분 실패 (변수 제어: 조용히 버린 것 없음) */
    private fun buildNotices(
        fragment: Fragment,
        outcome: CharacterFieldAiSuggester.SuggestOutcome
    ): String = buildList {
        add(fragment.getString(R.string.field_library_ai_token_usage, outcome.inputTokens, outcome.outputTokens))
        if (outcome.droppedCount > 0) {
            add(fragment.getString(R.string.ai_field_dropped, outcome.droppedCount))
        }
        outcome.truncationNotes.forEach {
            add(fragment.getString(R.string.ai_field_truncated_prefix, it))
        }
        addAll(outcome.failures)
    }.joinToString("\n")

    /** 필드 1개 모드: 체크리스트 대신 단일 확인 — 1건에 체크리스트는 조작 마찰만 추가 (원칙 04) */
    private fun showSingleConfirm(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        run: CharacterViewModel.AiSuggestRun
    ) {
        val context = fragment.requireContext()
        val outcome = run.outcome
        val suggestion = outcome.suggestions.first()
        val spec = run.targets.first()
        val message = buildString {
            if (spec.currentValue.isNotBlank()) {
                append(fragment.getString(R.string.ai_field_overwrite_format, spec.currentValue, suggestion.value))
            } else {
                append(spec.name).append(": ").append(suggestion.value)
            }
            if (suggestion.reason.isNotBlank()) {
                append("\n\n").append(fragment.getString(R.string.ai_field_reason_format, suggestion.reason))
            }
            append("\n\n").append(buildNotices(fragment, outcome))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(spec.name)
            .setMessage(message)
            .setPositiveButton(R.string.ai_field_single_apply) { _, _ ->
                viewModel.clearAiSuggestResult()
                applySelected(fragment, formBuilder, listOf(suggestion))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.clearAiSuggestResult() }
            .setOnCancelListener { viewModel.clearAiSuggestResult() }
            .show()
    }

    /** 전체 모드: 체크리스트 검토 — 빈 필드 제안 기본 선택, 덮어쓰기 제안은 명시 선택(변수 제어) */
    private fun showReviewDialog(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        run: CharacterViewModel.AiSuggestRun
    ) {
        val context = fragment.requireContext()
        val outcome = run.outcome
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val specByKey = run.targets.associateBy { it.key }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        list.addView(TextView(context).apply {
            text = buildNotices(fragment, outcome)
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, pad / 2, 0, pad / 4)
        })

        val checks = mutableListOf<Pair<CheckBox, CharacterFieldAiSuggester.Suggestion>>()
        for (s in outcome.suggestions) {
            val spec = specByKey[s.fieldKey] ?: continue
            val overwrite = spec.currentValue.isNotBlank()
            val cb = CheckBox(context).apply {
                text = buildString {
                    append(spec.name).append(": ")
                    if (overwrite) {
                        append(fragment.getString(R.string.ai_field_overwrite_format, spec.currentValue, s.value))
                    } else {
                        append(s.value)
                    }
                    if (s.reason.isNotBlank()) append("\n  (").append(s.reason).append(')')
                }
                // 빈 필드 채움은 비파괴 조작 — 기본 선택으로 마찰 최소화. 덮어쓰기만 명시적 선택.
                isChecked = !overwrite
            }
            list.addView(cb)
            checks.add(cb to s)
        }

        val scroll = ScrollView(context).apply { addView(list) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_review_title)
            .setView(scroll)
            .setPositiveButton(R.string.ai_field_apply, null)
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.clearAiSuggestResult() }
            .setNeutralButton(R.string.field_library_ai_select_all, null)
            .setOnCancelListener { viewModel.clearAiSuggestResult() }
            .create()

        dialog.setOnShowListener {
            var allSelected = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                allSelected = !allSelected
                checks.forEach { it.first.isChecked = allSelected }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = checks.filter { it.first.isChecked }.map { it.second }
                if (selected.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                viewModel.clearAiSuggestResult()
                applySelected(fragment, formBuilder, selected)
            }
        }
        dialog.show()
    }

    private fun applySelected(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        selected: List<CharacterFieldAiSuggester.Suggestion>
    ) {
        val fieldByKey = formBuilder.fieldDefinitions.associateBy { it.key }
        var applied = 0
        for (s in selected) {
            val field = fieldByKey[s.fieldKey] ?: continue
            formBuilder.applyRandomValue(field, s.value, showToast = false)
            applied++
        }
        if (applied > 0) {
            fragment.notifySuccess(fragment.getString(R.string.ai_field_applied, applied))
        } else {
            // 회전 직후 폼 재구축 전 등 — 무통보 no-op 금지, 재시도 경로 안내 (변수 제어)
            fragment.notifyError(fragment.getString(R.string.ai_field_apply_none))
        }
    }
}
