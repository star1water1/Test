package com.novelcharacter.app.ui.character

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
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch

/**
 * 서술형 필드(긴 글) AI 작성 시트 — 짧은 값 추천([AiFieldSuggestSheet])과 **경로가 다르다**.
 *
 * 무엇을 시킬지부터 고른다(초안 / 이어쓰기 / 다듬기 / 늘리기 / 줄이기). 빈 칸 채우기보다
 * **이미 쓴 글을 다루는 쪽**이 실사용에서 더 잦기 때문이다.
 *
 * **원문은 절대 덮어쓰지 않는다.** 후보를 원문과 나란히 보여주고 사용자가 고른 것만
 * 폼 위젯에 기입한다 — 영속화는 기존 저장 체인이 한다.
 */
object NarrativeWriteSheet {

    fun show(
        fragment: Fragment,
        field: FieldDefinition,
        /** 문체 참고에서 자기 자신을 빼기 위한 캐릭터 id. 미저장(-1)이면 뺄 자기 값이 없다. */
        characterId: Long,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        /**
         * 폼의 라이브 이미지 목록 (A-7). **서술형이 이 기능의 최대 수혜자다** —
         * 외모 묘사는 글로 적힌 정보가 가장 성긴 자리이고 그림에는 그것이 통째로 있다.
         */
        imagePaths: List<String> = emptyList(),
        representativePath: String? = null,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        if (!guardProvider(fragment)) return

        val current = formBuilder.collectFieldValues(0L)
            .firstOrNull { it.fieldDefinitionId == field.id }?.value.orEmpty()
        val spec = NarrativeFieldAiWriter.fieldSpecOf(field, current)
        val modes = NarrativeFieldAiWriter.availableModes(current)

        // 모드 선택 → 분량 선택 → 실행. 원문이 없으면 모드가 하나뿐이라 곧바로 분량으로 넘어간다.
        if (modes.size == 1) {
            askLength(
                fragment, viewModel, contextLoader, field, characterId, spec, modes.first(),
                imagePaths, representativePath
            )
            return
        }
        val labels = modes.map { fragment.getString(modeLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(fragment.getString(R.string.ai_narrative_title, field.name))
            .setItems(labels) { _, which ->
                askLength(
                    fragment, viewModel, contextLoader, field, characterId, spec, modes[which],
                    imagePaths, representativePath
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askLength(
        fragment: Fragment,
        viewModel: CharacterViewModel,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext,
        field: FieldDefinition,
        characterId: Long,
        spec: NarrativeFieldAiWriter.FieldSpec,
        mode: NarrativeFieldAiWriter.Mode,
        imagePaths: List<String>,
        representativePath: String?
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val lengths = NarrativeFieldAiWriter.Length.entries

        // 항목 리스트 대신 패널 — 비용 고지 + 창작도 칩(A-4) + 이미지 첨부(A-7)를
        // 분량 선택과 한 화면에 담는다.
        val imageCostLine = android.widget.TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = false
        }
        val attach = AiImageAttachRow.create(fragment, imagePaths, representativePath)
        val panel = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(com.novelcharacter.app.ui.common.NaturalLanguageInput.create(fragment,"briefing:$characterId",
                "캐릭터 구상 (선택) · 생각나는 대로 적어 주세요",viewModel.aiBriefingDrafts[characterId].orEmpty(),
                onChanged={viewModel.aiBriefingDrafts[characterId]=it},
                terms={viewModel.speechTerms(contextLoader(),characterId,listOf(field))}))
            addView(android.widget.TextView(context).apply {
                textSize = 14f
                text = fragment.getString(
                    R.string.ai_narrative_cost_notice, NarrativeFieldAiWriter.DEFAULT_VARIANTS
                )
            })
            addView(imageCostLine)
            addView(CreativityChipRow.create(fragment))
            attach?.let { addView(it.view) }
        }
        // 서술형은 후보 여러 개를 **한 요청**으로 받으므로 이미지도 한 번만 나간다 —
        // 짧은 값 경로와 달리 연인원이 장수 그대로다.
        fun refreshImageCost() {
            val count = attach?.selected?.size ?: 0
            imageCostLine.isVisible = count > 0
            if (count > 0) {
                imageCostLine.text = fragment.getString(
                    R.string.ai_image_cost_notice,
                    count, count,
                    com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MIN,
                    com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MAX
                )
            }
        }
        attach?.onChanged { refreshImageCost() }
        refreshImageCost()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_narrative_length_title)
            .setView(com.novelcharacter.app.util.cappedScrollView(context).apply {addView(panel)})
            .setNegativeButton(R.string.cancel, null)
            .create()
        for (length in lengths) {
            panel.addView(
                com.google.android.material.button.MaterialButton(
                    context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = fragment.getString(lengthLabel(length), length.hint)
                    setOnClickListener {
                        dialog.dismiss()
                        run(
                            fragment, viewModel, contextLoader, field, characterId, spec, mode, length,
                            attach?.selected.orEmpty()
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
        field: FieldDefinition,
        characterId: Long,
        spec: NarrativeFieldAiWriter.FieldSpec,
        mode: NarrativeFieldAiWriter.Mode,
        length: NarrativeFieldAiWriter.Length,
        imagePaths: List<String>
    ) {
        // 컨텍스트 조립은 뷰 접근이라 뷰 스코프(이 단계 취소는 과금 전이므로 무해),
        // 실행은 VM 위임(회전 생존).
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val aiContext = contextLoader().copy(briefing=viewModel.aiBriefingDrafts[characterId].orEmpty())
            if (!fragment.isAdded) return@launch
            val started = viewModel.runAiNarrative(
                aiContext, field.id, characterId, spec, mode, length,
                NarrativeFieldAiWriter.DEFAULT_VARIANTS, imagePaths
            )
            if (!started) {
                // 이미 실행 중 — 무통보로 삼키지 않는다
                Toast.makeText(fragment.requireContext(), R.string.ai_field_running, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 결과 검토 — 편집 화면의 `aiNarrativeResult` 관측이 호출한다.
     * 빈 결과도 반드시 고지한다(무통보 소멸 금지).
     */
    fun showResult(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        run: CharacterViewModel.AiNarrativeRun,
        fieldOf: (Long) -> FieldDefinition?
    ) {
        com.novelcharacter.app.ui.common.NarrativeReviewDialog.show(
            fragment,
            listOf(com.novelcharacter.app.ui.common.NarrativeReviewDialog.Item(run.fieldId, run.fieldName,
                run.originalValue, run.outcome.drafts, run.mode == NarrativeFieldAiWriter.Mode.CONTINUE,
                viewModel.narrativeImageCount(run.fieldId,false))),
            viewModel.aiNarrativeReviewState, buildNotices(fragment, run.outcome),
            onApply = { selected ->
                val field = fieldOf(run.fieldId)
                val chosen = selected[run.fieldId]
                val live = formBuilder.collectFieldValues(0L).firstOrNull { it.fieldDefinitionId == run.fieldId }?.value.orEmpty()
                val text = chosen?.let { com.novelcharacter.app.ai.NarrativeReviewState.appliedText(live, it,
                    run.mode == NarrativeFieldAiWriter.Mode.CONTINUE) }
                if (field != null && text != null && formBuilder.applyReviewedValue(field, text)) {
                    viewModel.clearAiNarrativeResult()
                    true
                } else {
                    Toast.makeText(fragment.requireContext(), R.string.ai_narrative_field_gone, Toast.LENGTH_LONG).show()
                    false
                }
            },
            onClose = { viewModel.clearAiNarrativeResult() },
            onRefine = { id, candidate, instruction ->
                val started = viewModel.refineAiNarrative(id, candidate, instruction, false)
                if (!started) Toast.makeText(fragment.requireContext(), R.string.ai_field_running, Toast.LENGTH_SHORT).show()
                started
            },
            running=viewModel.aiNarrativeRunning
        )
    }

    /** 토큰 사용·드롭·절단·실패 — 조용히 버린 것이 없음을 항상 보인다. */
    private fun buildNotices(
        fragment: Fragment,
        outcome: NarrativeFieldAiWriter.WriteOutcome
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

    private fun modeLabel(mode: NarrativeFieldAiWriter.Mode): Int = when (mode) {
        NarrativeFieldAiWriter.Mode.DRAFT -> R.string.ai_narrative_mode_draft
        NarrativeFieldAiWriter.Mode.CONTINUE -> R.string.ai_narrative_mode_continue
        NarrativeFieldAiWriter.Mode.POLISH -> R.string.ai_narrative_mode_polish
        NarrativeFieldAiWriter.Mode.EXPAND -> R.string.ai_narrative_mode_expand
        NarrativeFieldAiWriter.Mode.SHORTEN -> R.string.ai_narrative_mode_shorten
    }

    private fun lengthLabel(length: NarrativeFieldAiWriter.Length): Int = when (length) {
        NarrativeFieldAiWriter.Length.SHORT -> R.string.ai_narrative_length_short
        NarrativeFieldAiWriter.Length.MEDIUM -> R.string.ai_narrative_length_medium
        NarrativeFieldAiWriter.Length.LONG -> R.string.ai_narrative_length_long
    }

    private const val PREVIEW_CHARS = 160

    /** 미설정이면 조용히 아무 일도 하지 않는 대신 설정 경로를 안내한다(AiFieldSuggestSheet와 동일 규약). */
    private fun guardProvider(fragment: Fragment): Boolean {
        val context = fragment.requireContext()
        if (AiService(context).hasUsableProvider()) return true
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_narrative_title_plain)
            .setMessage(R.string.field_library_ai_not_configured)
            .setPositiveButton(R.string.ai_settings_title) { _, _ ->
                fragment.findNavController().navigate(R.id.aiSettingsFragment)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return false
    }
}
