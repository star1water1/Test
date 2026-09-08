package com.novelcharacter.app.ui.character

import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.NarrativeFieldAiWriter
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.setValidatedPositiveButton
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

/**
 * 서술형 필드 **일괄 초안** 시트 (B-45).
 *
 * 짧은 값 일괄([AiFieldSuggestSheet])이 서술형을 `NARRATIVE_PATH`로 빼는 것은 옳다 — 산문을
 * "근거 한 문장" 틀에 끼우면 산문 자리에 한 줄짜리 값이 나온다. 결함은 **빠진 뒤 갈 곳이
 * 없었다는 것**이다: "성격·배경을 한 번에"를 하려면 필드별 ✨을 필드 수만큼 반복해야 했다.
 *
 * ## 이 경로가 [NarrativeWriteSheet]와 다른 점 셋
 *
 * ① **모드가 초안 하나다.** 이어쓰기·다듬기는 원문을 보고 판단할 일이라 일괄에 맞지 않는다 —
 *    이미 쓴 필드는 아예 대상에서 빠지고(`ALREADY_WRITTEN`) 그 사실과 교정 경로를 함께 말한다.
 * ② **비용 단위가 요청 건수다.** 필드 하나에 요청 하나라 짧은 값 일괄(*필드 15개 = 요청 1건*)과
 *    자릿수가 다르다. 같은 문구로 고지하면 사용자가 비용을 오해한다.
 * ③ **후보가 1개다** ([NarrativeFieldAiWriter.BULK_DRAFT_VARIANTS]) — 이유는 그 상수의 주석에 있다.
 *
 * **원문은 절대 덮어쓰지 않는다.** 후보는 검토 창에서 고른 것만 폼 위젯에 들어가고,
 * 영속화는 기존 저장 체인이 한다.
 */
object NarrativeBulkSheet {

    fun show(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        characterId: Long,
        imagePaths: List<String> = emptyList(),
        representativePath: String? = null,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        if (!guardProvider(fragment)) return

        val bulk = NarrativeFieldAiWriter.bulkDraftTargetsOf(
            formBuilder.fieldDefinitions, currentValuesByFieldId(formBuilder)
        )
        if (bulk.targets.isEmpty()) {
            // 대상 0이어도 **왜 0인지는 밝힌다** — 침묵하면 기능 고장으로 읽힌다
            // (짧은 값 경로가 같은 자리에서 같은 규약을 지킨다).
            val message = buildString {
                append(fragment.getString(R.string.ai_narrative_bulk_no_targets))
                NarrativeFieldAiWriter.bulkDraftExcludedDetailLines(bulk.excluded)
                    .forEach { append("\n· ").append(it) }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ai_narrative_bulk_title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }

        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val targetCount = bulk.targets.size
        val requests = NarrativeFieldAiWriter.draftRequestCount(targetCount)

        val costText = TextView(context).apply {
            textSize = 15f
            text = fragment.getString(R.string.ai_narrative_bulk_cost_notice, targetCount, requests)
        }
        val excludedSummary = NarrativeFieldAiWriter.bulkDraftExcludedSummary(bulk.excluded)
        val excludedText = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = excludedSummary != null
            text = excludedSummary?.let {
                fragment.getString(R.string.ai_narrative_bulk_excluded_line, it)
            }
        }
        val excludedDetail = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = false
            text = NarrativeFieldAiWriter.bulkDraftExcludedDetailLines(bulk.excluded)
                .joinToString("\n") { "· $it" }
        }
        val showExcludedLink = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.primary))
            isVisible = excludedSummary != null
            text = fragment.getString(R.string.ai_field_excluded_show)
            setPadding(0, (4 * density).toInt(), 0, 0)
            setOnClickListener {
                excludedDetail.isVisible = true
                isVisible = false
            }
        }
        // 절단 위험 고지 — 분량을 고르는 그 화면에서 말해야 값이 있다. 잘린 뒤에 알리면
        // 이미 요청 수만큼 결제가 끝난 뒤다(변수 제어: 실행 전에 바로잡을 경로를 준다).
        val budgetWarning = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = false
        }
        val imageCostLine = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = false
        }
        val attach = AiImageAttachRow.create(fragment, imagePaths, representativePath)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(AiFieldSuggestSheet.briefingInput(fragment,viewModel,characterId,formBuilder,contextLoader))
            addView(costText)
            addView(excludedText)
            addView(showExcludedLink)
            addView(excludedDetail)
            addView(budgetWarning)
            addView(imageCostLine)
            addView(CreativityChipRow.create(fragment))
            attach?.let { addView(it.view) }
        }

        // 고지도 청킹과 **같은 값**에서 계산한다 — 상수로 세면 사용자가 상한을 올렸을 때
        // 고지와 실제가 어긋난다(짧은 값 경로와 같은 규약).
        val budget = AiService(context).effectiveMaxTokens()

        // 이미지는 **요청마다 다시 실린다** — 서술형 일괄은 요청이 필드 수만큼이라
        // 연인원이 장수 × 필드 수다. 필드 하나짜리 경로와 여기가 갈리는 자리다.
        fun refreshImageCost() {
            val count = attach?.selected?.size ?: 0
            imageCostLine.isVisible = count > 0
            if (count > 0) {
                imageCostLine.text = fragment.getString(
                    R.string.ai_image_cost_notice,
                    count,
                    com.novelcharacter.app.ai.AiPromptPolicy.imageSendCount(count, requests),
                    com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MIN,
                    com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MAX
                )
            }
        }
        attach?.onChanged { refreshImageCost() }
        refreshImageCost()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_narrative_bulk_title)
            .setView(panel)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // 이번 창에서 절단 경고를 이미 보인 분량 — 두 번째 누름은 그대로 진행한다.
        // **창마다 새로 만든다**: 객체 수준에 두면 앞서 연 창의 상태가 남아, 다음 사용자가
        // 경고를 못 보고 곧바로 실행된다.
        val warned = mutableSetOf<NarrativeFieldAiWriter.Length>()

        // 분량 버튼이 곧 실행 버튼이다 — 고른 분량으로 바로 돌린다(단계를 하나 줄인다).
        // 누르기 전에 그 분량의 절단 위험을 보여 주려고 버튼마다 미리 계산해 둔다.
        for (length in NarrativeFieldAiWriter.Length.entries) {
            panel.addView(
                com.google.android.material.button.MaterialButton(
                    context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = fragment.getString(lengthLabel(length), length.hint)
                    setOnClickListener {
                        if (NarrativeFieldAiWriter.draftFitsBudget(budget, length)) {
                            // **경고를 반드시 거둔다.** 종전 판은 켜기만 해서, [길게]로 경고를
                            // 띄운 뒤 [보통]을 누르면 *보통에는 해당하지 않는 경고*가 그대로
                            // 남은 채였다 — 거짓 고지다(콜드 검토가 잡았다).
                            budgetWarning.isVisible = false
                        } else {
                            // 막지 않는다 — 추정이지 확정이 아니다(자율성 우선). 한 번 보이고,
                            // 그래도 누르면 그대로 돌린다.
                            budgetWarning.isVisible = true
                            budgetWarning.text =
                                fragment.getString(R.string.ai_narrative_bulk_budget_warning, budget)
                            if (!warned.contains(length)) {
                                warned.add(length)
                                return@setOnClickListener
                            }
                        }
                        dialog.dismiss()
                        run(
                            fragment, viewModel, contextLoader, characterId,
                            bulk.targets, length, attach?.selected.orEmpty()
                        )
                    }
                }
            )
        }
        dialog.show()
    }

    private fun run(
        fragment: Fragment,
        viewModel: CharacterViewModel,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext,
        characterId: Long,
        targets: List<NarrativeFieldAiWriter.BulkDraftTarget>,
        length: NarrativeFieldAiWriter.Length,
        imagePaths: List<String>
    ) {
        // 컨텍스트 조립만 뷰 스코프(이 단계 취소는 과금 전이라 무해), 실행은 VM 위임(회전 생존).
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val aiContext = contextLoader().copy(briefing=viewModel.aiBriefingDrafts[characterId].orEmpty())
            if (!fragment.isAdded) return@launch
            val started = viewModel.runAiNarrativeBulk(aiContext, characterId, targets, length, imagePaths)
            if (!started) {
                // 이미 실행 중 — 무통보로 삼키지 않는다
                Toast.makeText(fragment.requireContext(), R.string.ai_field_running, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 결과 검토 — 편집 화면의 `aiNarrativeBulkResult` 관측이 호출한다.
     *
     * **여기서 결과를 비우는 것은 사용자가 끝냈을 때뿐이다.** 이 한 값에 요청 N건어치
     * 유료 응답이 들어 있어, 중간에 비우면 N번을 다시 결제해야 한다(R-38).
     *
     * 빈 실행도 반드시 고지한다 — 눌렀는데 아무 일도 일어나지 않는 것이 가장 나쁘다(B-144).
     */
    fun showResult(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        run: CharacterViewModel.AiNarrativeBulkRun,
        fieldOf: (Long) -> FieldDefinition?
    ) {
        val gone = run.items.filter { it.hasDraft && fieldOf(it.fieldId) == null }
        com.novelcharacter.app.ui.common.NarrativeReviewDialog.show(
            fragment,
            run.items.map { com.novelcharacter.app.ui.common.NarrativeReviewDialog.Item(it.fieldId,
                it.fieldName, viewModel.narrativeOriginal(it.fieldId), it.outcome.drafts,
                imageCount=viewModel.narrativeImageCount(it.fieldId,true)) },
            viewModel.aiNarrativeBulkReviewState, buildNotices(fragment, run, gone),
            onApply = { selected ->
                val applied = selected.count { (id, text) ->
                    fieldOf(id)?.let { formBuilder.applyReviewedValue(it, text) } == true
                }
                Toast.makeText(fragment.requireContext(), fragment.getString(R.string.ai_narrative_bulk_applied, applied),
                    Toast.LENGTH_LONG).show()
                if (applied == selected.size && applied > 0) {
                    viewModel.clearAiNarrativeBulkResult()
                    true
                } else false
            },
            onClose = { viewModel.clearAiNarrativeBulkResult() },
            onRefine = { id, candidate, instruction ->
                val started = viewModel.refineAiNarrative(id, candidate, instruction, true)
                if (!started) Toast.makeText(fragment.requireContext(), R.string.ai_field_running, Toast.LENGTH_SHORT).show()
                started
            },
            running=viewModel.aiNarrativeBulkRunning
        )
    }

    private fun buildNotices(
        fragment: Fragment,
        run: CharacterViewModel.AiNarrativeBulkRun,
        gone: List<CharacterViewModel.AiNarrativeBulkItem> = emptyList()
    ): String = buildList {
        val inTokens = run.items.sumOf { it.outcome.inputTokens }
        val outTokens = run.items.sumOf { it.outcome.outputTokens }
        add(fragment.getString(R.string.field_library_ai_token_usage, inTokens, outTokens))
        // 실행 자체가 도중에 깨진 경우 — 이미 받은 초안은 아래에 그대로 있고, 왜 중간에
        // 멈췄는지를 여기서 말한다(말하지 않으면 *"AI가 그만큼만 답했다"*와 구별되지 않는다).
        run.runFailure?.let { add(fragment.getString(R.string.ai_narrative_bulk_run_failed, it)) }
        for (item in run.items.filter { !it.hasDraft }) {
            add(fragment.getString(R.string.ai_narrative_bulk_field_failed, item.fieldName))
        }
        for (item in gone) {
            add(fragment.getString(R.string.ai_narrative_bulk_field_gone, item.fieldName))
        }
        // 형식이 틀려 버린 후보 — 후보가 1개라 대개 실패로 잡히지만, 0이 아니면 반드시 말한다.
        val dropped = run.items.sumOf { it.outcome.droppedCount }
        if (dropped > 0) add(fragment.getString(R.string.ai_field_dropped, dropped))
        // 프롬프트에 다 못 실은 컨텍스트 (R-14) — 필드마다 같은 사유가 나오므로 중복을 접는다.
        run.items.flatMap { it.outcome.truncationNotes }.distinct().forEach {
            add(fragment.getString(R.string.ai_field_truncated_prefix, it))
        }
        // 실패 문구는 같은 것이 필드마다 되풀이되므로 중복을 접는다 — 열 필드가 같은 이유로
        // 실패하면 같은 줄이 열 번 뜨고, 그러면 사용자가 읽기를 그만둔다.
        addAll(run.items.flatMap { it.outcome.failures }.distinct())
        if (run.notRequested.isNotEmpty()) {
            add(
                fragment.getString(
                    R.string.ai_narrative_bulk_not_requested,
                    run.notRequested.size,
                    run.notRequested.joinToString(", ")
                )
            )
        }
    }.joinToString("\n")

    private fun currentValuesByFieldId(formBuilder: DynamicFieldFormBuilder): Map<Long, String> =
        formBuilder.collectFieldValues(0L).associate { it.fieldDefinitionId to it.value }

    private fun lengthLabel(length: NarrativeFieldAiWriter.Length): Int = when (length) {
        NarrativeFieldAiWriter.Length.SHORT -> R.string.ai_narrative_length_short
        NarrativeFieldAiWriter.Length.MEDIUM -> R.string.ai_narrative_length_medium
        NarrativeFieldAiWriter.Length.LONG -> R.string.ai_narrative_length_long
    }

    private const val PREVIEW_CHARS = 120

    /** 미설정이면 조용히 아무 일도 하지 않는 대신 설정 경로를 안내한다(다른 AI 시트와 동일 규약). */
    private fun guardProvider(fragment: Fragment): Boolean {
        val context = fragment.requireContext()
        if (AiService(context).hasUsableProvider()) return true
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_narrative_bulk_title)
            .setMessage(R.string.field_library_ai_not_configured)
            .setPositiveButton(R.string.ai_settings_title) { _, _ ->
                fragment.findNavController().navigate(R.id.aiSettingsFragment)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return false
    }
}
