package com.novelcharacter.app.ui.character

import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiErrorMessages
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.notifySuccess
import kotlinx.coroutines.launch

/**
 * AI 필드 값 추천 플로우 (온디맨드, docs/ai_integration.md 계약):
 * 프로바이더 가드 → 비용 고지(전체 모드: 입력된 필드 포함 여부 선택) → 실행
 * → 검토(필드 1개: 확인 다이얼로그 / 전체: 체크리스트 — 빈 필드 기본 선택, 덮어쓰기 기본 해제)
 * → 선택 적용(폼 위젯에만 기입 — 저장을 눌러야 영속화, __birth 동기화는 저장 체인이 수행).
 * AI 출력은 어떤 경우에도 자동 적용·DB 직접 기록되지 않는다.
 */
object AiFieldSuggestSheet {

    /** 필드 1개 추천 — 폼의 ✨ 버튼 진입점 */
    fun showForField(
        fragment: Fragment,
        field: FieldDefinition,
        formBuilder: DynamicFieldFormBuilder,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        val aiService = AiService(context)
        if (!guardProvider(fragment, aiService)) return

        val currentValue = currentValuesByFieldId(formBuilder)[field.id] ?: ""
        val spec = CharacterFieldAiSuggester.fieldSpecOf(field, currentValue) ?: return

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_suggest_title)
            .setMessage(fragment.getString(R.string.ai_field_cost_notice_single, field.name))
            .setPositiveButton(R.string.ai_field_run) { _, _ ->
                runSuggest(fragment, formBuilder, contextLoader, listOf(spec), aiService, singleMode = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 전체 필드 추천 — 편집 화면의 'AI 필드 추천' 버튼 진입점 */
    fun showForCharacter(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        val aiService = AiService(context)
        if (!guardProvider(fragment, aiService)) return

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

        fun refreshMessage() {
            val count = currentTargets().size
            message.text = if (count == 0) {
                fragment.getString(R.string.ai_field_no_empty_fields)
            } else {
                fragment.getString(R.string.ai_field_cost_notice, count)
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
                runSuggest(fragment, formBuilder, contextLoader, targets, aiService, singleMode = false)
            }
        }
        dialog.show()
    }

    // ===== 공통 파이프라인 =====

    private fun guardProvider(fragment: Fragment, aiService: AiService): Boolean {
        if (aiService.hasUsableProvider()) return true
        val context = fragment.requireContext()
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

    private fun runSuggest(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext,
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        aiService: AiService,
        singleMode: Boolean
    ) {
        val context = fragment.requireContext()
        val progress = MaterialAlertDialogBuilder(context)
            .setMessage(R.string.ai_field_running)
            .setCancelable(false)
            .show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val suggester = CharacterFieldAiSuggester(aiService)
            val outcome = try {
                val aiContext = contextLoader()
                suggester.suggest(aiContext, targets) { failure -> AiErrorMessages.of(context, failure) }
            } finally {
                progress.dismiss()
            }
            if (!fragment.isAdded) return@launch

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
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@launch
            }

            if (singleMode) {
                showSingleConfirm(fragment, formBuilder, targets, outcome)
            } else {
                showReviewDialog(fragment, formBuilder, targets, outcome)
            }
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
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        outcome: CharacterFieldAiSuggester.SuggestOutcome
    ) {
        val context = fragment.requireContext()
        val suggestion = outcome.suggestions.first()
        val spec = targets.first()
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
                val field = formBuilder.fieldDefinitions.firstOrNull { it.key == suggestion.fieldKey }
                    ?: return@setPositiveButton
                formBuilder.applyRandomValue(field, suggestion.value, showToast = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 전체 모드: 체크리스트 검토 — 빈 필드 제안 기본 선택, 덮어쓰기 제안은 명시 선택(변수 제어) */
    private fun showReviewDialog(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        outcome: CharacterFieldAiSuggester.SuggestOutcome
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val specByKey = targets.associateBy { it.key }

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
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.field_library_ai_select_all, null)
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
        }
    }
}
