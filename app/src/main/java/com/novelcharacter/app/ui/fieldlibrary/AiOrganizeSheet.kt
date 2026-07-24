package com.novelcharacter.app.ui.fieldlibrary

import android.view.View
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
import com.novelcharacter.app.ai.FieldLibraryAiOrganizer
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import kotlinx.coroutines.launch

/**
 * AI 정리 플로우 (온디맨드, docs/ai_integration.md 계약):
 * 프로바이더 가드 → 요청 수·비용 고지 → 실행 → 제안 체크리스트(전체 선택/해제, 근거 표시)
 * → 선택 항목 단일 통합 확인(전파 드라이런 합계) → 적용.
 * AI 출력은 어떤 경우에도 자동 적용되지 않는다.
 */
object AiOrganizeSheet {

    fun show(fragment: Fragment, fd: FieldDefinition, viewModel: FieldValueLibraryViewModel) {
        val context = fragment.requireContext()
        val aiService = AiService(context)

        if (!aiService.hasUsableProvider()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.field_library_menu_ai)
                .setMessage(R.string.field_library_ai_not_configured)
                .setPositiveButton(R.string.ai_settings_title) { _, _ ->
                    fragment.findNavController().navigate(R.id.aiSettingsFragment)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val entries = viewModel.repo.entriesForField(fd.id)
            if (entries.size < 2) {
                MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.field_library_ai_too_few)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@launch
            }
            val chunkCount = FieldLibraryAiOrganizer.chunkEntries(entries).size
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.field_library_menu_ai)
                .setMessage(fragment.getString(R.string.field_library_ai_cost_notice, entries.size, chunkCount))
                .setPositiveButton(R.string.field_library_ai_run) { _, _ ->
                    runOrganize(fragment, fd, viewModel, entries, aiService)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun runOrganize(
        fragment: Fragment,
        fd: FieldDefinition,
        viewModel: FieldValueLibraryViewModel,
        entries: List<FieldValueEntry>,
        aiService: AiService
    ) {
        val context = fragment.requireContext()
        val progress = MaterialAlertDialogBuilder(context)
            .setMessage(R.string.field_library_ai_running)
            .setCancelable(false)
            .show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val organizer = FieldLibraryAiOrganizer(aiService)
            val outcome = try {
                organizer.organize(fd, entries) { failure -> AiErrorMessages.of(context, failure) }
            } finally {
                progress.dismiss()
            }
            if (!fragment.isAdded) return@launch

            if (outcome.merges.isEmpty() && outcome.categories.isEmpty()) {
                val message = buildString {
                    append(fragment.getString(R.string.field_library_ai_nothing))
                    outcome.failures.forEach { append("\n· ").append(it) }
                    if (outcome.droppedCount > 0) {
                        append("\n").append(fragment.getString(
                            R.string.field_library_ai_dropped, outcome.droppedCount))
                    }
                }
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.field_library_menu_ai)
                    .setMessage(message)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@launch
            }
            showReviewDialog(fragment, fd, viewModel, entries, outcome)
        }
    }

    private fun showReviewDialog(
        fragment: Fragment,
        fd: FieldDefinition,
        viewModel: FieldValueLibraryViewModel,
        entries: List<FieldValueEntry>,
        outcome: FieldLibraryAiOrganizer.OrganizeOutcome
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        fun header(text: String) {
            list.addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, pad / 2, 0, pad / 4)
            })
        }

        // 상단 고지: 사용 토큰·드롭·부분 실패 (변수 제어 — 조용히 버린 것 없음)
        val notices = buildList {
            add(fragment.getString(R.string.field_library_ai_token_usage,
                outcome.totalInputTokens, outcome.totalOutputTokens))
            if (outcome.droppedCount > 0) {
                add(fragment.getString(R.string.field_library_ai_dropped, outcome.droppedCount))
            }
            addAll(outcome.failures)
        }
        header(notices.joinToString("\n"))

        val mergeChecks = mutableListOf<Pair<CheckBox, FieldLibraryAiOrganizer.MergeSuggestion>>()
        if (outcome.merges.isNotEmpty()) {
            header(fragment.getString(R.string.field_library_ai_merges_section))
            for (m in outcome.merges) {
                val cb = CheckBox(context).apply {
                    text = "${m.variants.joinToString(", ")} → ${m.canonical}" +
                        if (m.reason.isNotBlank()) "\n  (${m.reason})" else ""
                    isChecked = false
                }
                list.addView(cb)
                mergeChecks.add(cb to m)
            }
        }

        val categoryChecks = mutableListOf<Pair<CheckBox, FieldLibraryAiOrganizer.CategorySuggestion>>()
        if (outcome.categories.isNotEmpty()) {
            header(fragment.getString(R.string.field_library_ai_categories_section))
            for (c in outcome.categories) {
                val cb = CheckBox(context).apply {
                    text = "${c.value} → ${c.category}"
                    isChecked = false
                }
                list.addView(cb)
                categoryChecks.add(cb to c)
            }
        }

        val scroll = ScrollView(context).apply { addView(list) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.field_library_ai_review_title)
            .setView(scroll)
            .setPositiveButton(R.string.field_library_ai_apply, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.field_library_ai_select_all, null)
            .create()

        dialog.setOnShowListener {
            var allSelected = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                allSelected = !allSelected
                mergeChecks.forEach { it.first.isChecked = allSelected }
                categoryChecks.forEach { it.first.isChecked = allSelected }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val merges = mergeChecks.filter { it.first.isChecked }.map { it.second }
                val categories = categoryChecks.filter { it.first.isChecked }.map { it.second }
                if (merges.isEmpty() && categories.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                confirmAndApply(fragment, fd, viewModel, entries, merges, categories)
            }
        }
        dialog.show()
    }

    /** 선택 항목을 단일 통합 확인으로 적용 — 병합별 개별 확인 없음 (원칙 04) */
    private fun confirmAndApply(
        fragment: Fragment,
        fd: FieldDefinition,
        viewModel: FieldValueLibraryViewModel,
        entries: List<FieldValueEntry>,
        merges: List<FieldLibraryAiOrganizer.MergeSuggestion>,
        categories: List<FieldLibraryAiOrganizer.CategorySuggestion>
    ) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            // 드라이런: 선택된 병합 전체의 전파 규모 합산
            var totalChars = 0
            var totalEvents = 0
            var totalStates = 0
            for (m in merges) {
                val report = viewModel.previewPropagation(fd, m.variants.toSet())
                totalChars += report.characterValues
                totalEvents += report.eventValues
                totalStates += report.stateChanges
            }
            val message = buildString {
                if (merges.isNotEmpty()) {
                    append(fragment.getString(R.string.field_library_ai_apply_merges, merges.size))
                    append("\n")
                    append(fragment.getString(R.string.field_library_propagation_message,
                        totalChars, totalEvents, totalStates))
                }
                if (categories.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(fragment.getString(R.string.field_library_ai_apply_categories, categories.size))
                }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.field_library_ai_apply)
                .setMessage(message)
                .setPositiveButton(R.string.field_library_ai_apply) { _, _ ->
                    apply(fragment, fd, viewModel, entries, merges, categories)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun apply(
        fragment: Fragment,
        fd: FieldDefinition,
        viewModel: FieldValueLibraryViewModel,
        entries: List<FieldValueEntry>,
        merges: List<FieldLibraryAiOrganizer.MergeSuggestion>,
        categories: List<FieldLibraryAiOrganizer.CategorySuggestion>
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            var appliedMerges = 0
            var appliedCategories = 0
            var skipped = 0
            try {
                val byValue = entries.associateBy { it.value }.toMutableMap()
                for (m in merges) {
                    // 선행 병합이 canonical을 소모한 연쇄 제안은 건너뛰되 건수로 표면화 (조용히 버리지 않음)
                    val target = byValue[m.canonical]
                    val sourceIds = m.variants.mapNotNull { byValue[it]?.id }
                    if (target == null || sourceIds.isEmpty()) {
                        skipped++
                        continue
                    }
                    viewModel.repo.mergeValues(fd, target.id, sourceIds)
                    m.variants.forEach { byValue.remove(it) }
                    appliedMerges++
                }
                // 카테고리는 병합 반영 후 최신 엔트리 기준으로 적용 (source=AI 마킹)
                val fresh = viewModel.repo.entriesForField(fd.id).associateBy { it.value }
                for (c in categories) {
                    val entry = fresh[c.value]
                    if (entry == null) {
                        skipped++
                        continue
                    }
                    if (entry.category == c.category) continue
                    viewModel.repo.updateEntry(entry.copy(
                        category = c.category,
                        source = FieldValueEntry.SOURCE_AI
                    ))
                    appliedCategories++
                }
                viewModel.repo.recountUsage(fd.id)
                viewModel.notifyAiApplied(appliedMerges, appliedCategories, skipped)
            } catch (e: Exception) {
                // 부분 적용 중 실패 — 이미 적용된 건수와 함께 실패를 알린다 (앱 크래시 금지)
                android.util.Log.e("AiOrganizeSheet", "apply failed", e)
                viewModel.notifyAiApplyFailed(appliedMerges, appliedCategories, e.message)
            }
        }
    }
}
