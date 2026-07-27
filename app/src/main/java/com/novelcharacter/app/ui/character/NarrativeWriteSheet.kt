package com.novelcharacter.app.ui.character

import android.widget.Toast
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
            askLength(fragment, viewModel, contextLoader, field, characterId, spec, modes.first())
            return
        }
        val labels = modes.map { fragment.getString(modeLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(fragment.getString(R.string.ai_narrative_title, field.name))
            .setItems(labels) { _, which ->
                askLength(fragment, viewModel, contextLoader, field, characterId, spec, modes[which])
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
        mode: NarrativeFieldAiWriter.Mode
    ) {
        val context = fragment.requireContext()
        val lengths = NarrativeFieldAiWriter.Length.entries
        val labels = lengths.map { fragment.getString(lengthLabel(it), it.hint) }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_narrative_length_title)
            // 요청은 1건 — 후보 여러 개를 한 요청으로 받으므로 비용이 후보 수에 비례하지 않는다.
            .setMessage(
                fragment.getString(R.string.ai_narrative_cost_notice, NarrativeFieldAiWriter.DEFAULT_VARIANTS)
            )
            .setItems(labels) { _, which ->
                run(fragment, viewModel, contextLoader, field, characterId, spec, mode, lengths[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun run(
        fragment: Fragment,
        viewModel: CharacterViewModel,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext,
        field: FieldDefinition,
        characterId: Long,
        spec: NarrativeFieldAiWriter.FieldSpec,
        mode: NarrativeFieldAiWriter.Mode,
        length: NarrativeFieldAiWriter.Length
    ) {
        // 컨텍스트 조립은 뷰 접근이라 뷰 스코프(이 단계 취소는 과금 전이므로 무해),
        // 실행은 VM 위임(회전 생존).
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val aiContext = contextLoader()
            if (!fragment.isAdded) return@launch
            val started = viewModel.runAiNarrative(
                aiContext, field.id, characterId, spec, mode, length, NarrativeFieldAiWriter.DEFAULT_VARIANTS
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
        val context = fragment.requireContext()
        val outcome = run.outcome
        val notices = buildNotices(fragment, outcome)

        if (outcome.drafts.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(fragment.getString(R.string.ai_narrative_title, run.fieldName))
                .setMessage(
                    buildString {
                        append(fragment.getString(R.string.ai_field_nothing))
                        if (notices.isNotEmpty()) append("\n").append(notices)
                    }
                )
                .setPositiveButton(R.string.confirm) { _, _ -> viewModel.clearAiNarrativeResult() }
                .setOnCancelListener { viewModel.clearAiNarrativeResult() }
                .show()
            return
        }

        val field = fieldOf(run.fieldId)
        if (field == null) {
            // 검토 중 필드가 사라졌다(다른 화면에서 삭제 등) — 조용히 닫지 않는다.
            MaterialAlertDialogBuilder(context)
                .setTitle(fragment.getString(R.string.ai_narrative_title, run.fieldName))
                .setMessage(R.string.ai_narrative_field_gone)
                .setPositiveButton(R.string.confirm) { _, _ -> viewModel.clearAiNarrativeResult() }
                .setOnCancelListener { viewModel.clearAiNarrativeResult() }
                .show()
            return
        }

        // 후보를 고르게 한다. 이어쓰기는 원문 뒤에 붙는다는 사실이 미리보기에 드러나야 한다.
        val isContinue = run.mode == NarrativeFieldAiWriter.Mode.CONTINUE
        val labels = outcome.drafts.mapIndexed { i, d ->
            val head = fragment.getString(R.string.ai_narrative_draft_label, i + 1)
            "$head\n${d.text.take(PREVIEW_CHARS)}${if (d.text.length > PREVIEW_CHARS) "…" else ""}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(
                fragment.getString(
                    if (isContinue) R.string.ai_narrative_pick_continue else R.string.ai_narrative_pick,
                    run.fieldName
                )
            )
            .setMessage(notices.takeIf { it.isNotEmpty() })
            .setItems(labels) { _, which ->
                val chosen = outcome.drafts[which].text
                // 이어쓰기는 **원문 뒤에 붙인다** — 실행 시점의 원문을 기준으로 조립하므로
                // 검토 중 사용자가 원문을 더 고쳤다면 그 편집이 사라질 수 있다. 그래서
                // 지금 폼에 있는 값을 다시 읽어 그 뒤에 잇는다(사용자 입력 우선).
                val live = formBuilder.collectFieldValues(0L)
                    .firstOrNull { it.fieldDefinitionId == run.fieldId }?.value.orEmpty()
                val finalText = if (isContinue && live.isNotBlank()) {
                    live.trimEnd() + "\n\n" + chosen
                } else chosen
                formBuilder.applyRandomValue(field, finalText)
                viewModel.clearAiNarrativeResult()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.clearAiNarrativeResult() }
            .setOnCancelListener { viewModel.clearAiNarrativeResult() }
            .show()
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
