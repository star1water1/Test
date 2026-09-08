package com.novelcharacter.app.ui.timeline

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.EventFieldAiSuggester
import com.novelcharacter.app.ui.character.AiFieldSuggestSheet
import com.novelcharacter.app.util.cappedScrollView
import kotlinx.coroutines.launch

/** Event-specific context and form application; character/event result review uses the same UI and validator. */
object EventAiSuggestSheet {

    /**
     * 필드 1개 추천 — 사건 필드 줄의 ✨ 진입점.
     *
     * 이미지 첨부 줄이 없다(캐릭터 축의 A-7). 사건에는 이미지 목록 자체가 없어서이고,
     * 그래서 비용 고지도 요청 1건짜리 한 줄로 끝난다.
     */
    fun showForField(
        fragment: Fragment,
        fieldName: String,
        spec: CharacterFieldAiSuggester.FieldSpec,
        viewModel: EventFieldAiViewModel,
        eventId: Long,
        contextLoader: suspend () -> EventFieldAiSuggester.EventAiContext?
    ) {
        val context = fragment.requireContext()
        if (!AiFieldSuggestSheet.guardUsableProvider(fragment)) return

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_suggest_title)
            .setMessage(fragment.getString(R.string.ai_field_cost_notice_single, fieldName))
            .setPositiveButton(R.string.ai_field_run) { _, _ ->
                // 컨텍스트 조립은 창의 위젯을 읽으므로 창 수명 스코프에서 한다 —
                // **이 단계의 취소는 과금 전이라 무해하다.** 과금이 붙는 실행부터는 VM 스코프다.
                fragment.onDialogScope {
                    val aiContext = contextLoader()
                    if (!fragment.isAdded) return@onDialogScope
                    if (aiContext == null) {
                        Toast.makeText(context, R.string.ai_event_context_unavailable, Toast.LENGTH_LONG).show()
                        return@onDialogScope
                    }
                    if (!viewModel.run(aiContext, listOf(spec), eventId)) {
                        // 이미 실행 중 — 무통보로 삼키지 않는다
                        Toast.makeText(context, R.string.ai_event_field_running, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 결과 검토.
     *
     * **제안이 하나도 없어도 창을 연다** — 유료 실행이 아무 흔적 없이 끝나면 사용자는
     * 눌렀는데 아무 일도 안 일어난 것으로 본다(B-144가 이름 붙인 그 결함). 그때 창이
     * 담는 것은 고지뿐이고, 그것이 정확히 사용자가 알아야 할 전부다.
     */
    fun showResult(
        fragment: Fragment,
        viewModel: EventFieldAiViewModel,
        run: EventFieldAiViewModel.Run,
        /** 지금 창이 편집 중인 사건 id — 요청 시점과 다르면 적용하지 않는다 */
        currentEventId: Long,
        /** 채택분을 폼에 기입한다. 성공 여부를 돌려준다 — 실패하면 유료 응답을 되살린다 */
        applyValues: (List<CharacterFieldAiSuggester.Suggestion>) -> Boolean
    ) {
        val mismatched = run.eventId != currentEventId
        val notices = AiFieldSuggestSheet.buildNoticeLines(fragment, run.outcome).toMutableList()
        if(mismatched) notices.add(fragment.getString(R.string.ai_event_target_changed))
        com.novelcharacter.app.ui.common.FieldSuggestionReviewDialog.show(
            fragment,run.targets,run.outcome,viewModel.reviewState,notices.joinToString("\n"),
            onApply={ selected ->
                if(applyValues(selected)) {
                    viewModel.clearResult()
                    Toast.makeText(fragment.requireContext(),fragment.getString(R.string.ai_field_applied,selected.size),Toast.LENGTH_SHORT).show()
                    true
                } else {
                    Toast.makeText(fragment.requireContext(),R.string.ai_field_apply_none,Toast.LENGTH_LONG).show()
                    false // Retain the open dialog and response, including direct edits.
                }
            },
            onClose={viewModel.clearResult()},
            onRefine={ keys,instruction ->
                val context=run.context
                if(context==null || mismatched) false else {
                    val targets=run.targets.filter {it.key in keys}.map { spec ->
                        val current=run.outcome.suggestions.firstOrNull {it.fieldKey==spec.key}
                            ?.let {viewModel.reviewState.current(it)}
                        spec.copy(userInstruction=instruction.takeIf {it.isNotBlank()},
                            rejectedValues=spec.rejectedValues+listOfNotNull(current?.value))
                    }
                    viewModel.run(context,targets,run.eventId,carryOver=run)
                }
            },
            retryKeys=run.outcome.missing.filter {it.cause!=CharacterFieldAiSuggester.MissingCause.SAME_AS_CURRENT &&
                it.cause!=CharacterFieldAiSuggester.MissingCause.DECLINED}.map {it.fieldKey},
            canApply=!mismatched,
            running=viewModel.running
        )
    }

    /**
     * 컨텍스트 조립용 스코프.
     *
     * **`viewLifecycleOwner`가 아니라 창 자신의 수명**이다 — 사건 편집 창은 `onCreateDialog`로
     * 만들어 뷰 수명 소유자가 없는 구간이 있고, 거기서 `viewLifecycleOwner`를 건드리면 죽는다.
     * 과금 전 단계라 창이 사라지면 함께 사라지는 것이 맞다(과금이 붙는 실행은 VM 스코프다).
     */
    private fun Fragment.onDialogScope(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }
}
