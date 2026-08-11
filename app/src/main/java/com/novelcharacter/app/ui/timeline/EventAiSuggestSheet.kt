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

/**
 * 사건 필드 AI 추천의 **비용 고지 → 실행 → 검토** 창 (B-43).
 *
 * ## 캐릭터 축의 시트를 그대로 쓰지 않는 이유
 *
 * 그쪽은 `DynamicFieldFormBuilder`(캐릭터 폼의 위젯 조립기)와 `CharacterViewModel`에
 * 묶여 있는데 사건 편집 창은 그 둘을 쓰지 않는다 — 위젯을 스피너·입력칸으로 직접 세우고,
 * 실행은 창 자신의 [EventFieldAiViewModel]이 든다. 억지로 한 시트에 밀어 넣으려면 폼 접근을
 * 인터페이스로 추상화해야 하는데, 그 수술은 **이미 잘 도는 캐릭터 화면을 건드린다**.
 *
 * 대신 **갈리면 안 되는 것은 전부 부른다** — 제공사 가드와 결과 고지 문구가 그것이고
 * ([AiFieldSuggestSheet.guardUsableProvider] · [AiFieldSuggestSheet.buildNoticeLines]),
 * 그 둘이 *같은 상태를 두 화면이 다르게 설명하는* 유일한 자리다. 검증·정규화·결손 분류는
 * 애초에 순수 계층 한 벌이다.
 */
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
        val context = fragment.requireContext()
        val outcome = run.outcome
        val notices = AiFieldSuggestSheet.buildNoticeLines(fragment, outcome).joinToString("\n")

        // 요청 도중 다른 사건으로 옮겨 갔다 — 남의 사건에 값을 심지 않는다.
        // 버리지도 않는다: 무엇이 왜 적용되지 않았는지 말하고 응답 내용은 그대로 보여 준다.
        val mismatched = run.eventId != currentEventId
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        if (mismatched) {
            list.addView(TextView(context).apply {
                text = fragment.getString(R.string.ai_event_target_changed)
                textSize = 13f
                setTextColor(context.getColor(R.color.error))
                setPadding(0, 0, 0, pad / 2)
            })
        }
        list.addView(TextView(context).apply {
            text = notices
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = notices.isNotBlank()
            setPadding(0, 0, 0, pad / 2)
        })

        // 진입점이 필드 하나짜리 ✨이고 파서가 같은 key의 중복을 접으므로 제안은 **최대 1건**이다.
        // 그래서 체크리스트가 아니라 단일 확인이다 — 1건에 체크박스는 조작 마찰만 늘린다(원칙 04).
        // 캐릭터 축이 같은 자리에서 같은 결론에 닿아 있다(`AiFieldSuggestSheet.showSingleConfirm`).
        val specByKey = run.targets.associateBy { it.key }
        val suggestion = outcome.suggestions.firstOrNull()
        val spec = suggestion?.let { specByKey[it.fieldKey] }
        if (suggestion != null && spec != null) {
            list.addView(TextView(context).apply {
                textSize = 15f
                text = buildString {
                    append(spec.name).append(": ").append(suggestion.value)
                    suggestion.confidence?.let { append("  [").append(it.label).append(']') }
                    if (suggestion.outsideLibrary) {
                        append("  [").append(fragment.getString(R.string.ai_field_outside_library)).append(']')
                    }
                    if (spec.currentValue.isNotBlank()) {
                        // 덮어쓰기가 되는 자리라는 것을 값과 **같은 줄에서** 보여 준다 (변수 제어)
                        append('\n').append(
                            fragment.getString(
                                R.string.ai_field_overwrite_format, spec.currentValue, suggestion.value
                            )
                        )
                    }
                    if (suggestion.reason.isNotBlank()) {
                        append("\n\n").append(
                            fragment.getString(R.string.ai_field_reason_format, suggestion.reason)
                        )
                    }
                }
            })
        } else {
            list.addView(TextView(context).apply {
                text = fragment.getString(R.string.ai_field_nothing)
                textSize = 14f
            })
        }

        // 근거가 길어도 창이 화면을 넘지 않고 안에서 스크롤된다 (R-31)
        val scroll = cappedScrollView(context).apply { addView(list) }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(spec?.name ?: fragment.getString(R.string.ai_field_review_title))
            .setView(scroll)
            .setOnCancelListener { viewModel.clearResult() }
        if (suggestion != null && !mismatched) {
            builder.setNegativeButton(R.string.cancel) { _, _ -> viewModel.clearResult() }
            builder.setPositiveButton(R.string.ai_field_single_apply) { _, _ ->
                // **비우는 것은 적용이 성공한 뒤다** (B-163) — 실패해 놓고 비우면
                // 되돌아가 다시 적용할 자리가 없어 재결제가 된다.
                if (applyValues(listOf(suggestion))) {
                    viewModel.clearResult()
                    Toast.makeText(
                        context, fragment.getString(R.string.ai_field_applied, 1), Toast.LENGTH_SHORT
                    ).show()
                } else {
                    viewModel.restoreResult(run)
                    Toast.makeText(context, R.string.ai_field_apply_none, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // 적용할 것이 없거나 남의 사건이다 — 닫는 길만 준다. 그래도 창은 뜬다(B-144).
            builder.setPositiveButton(R.string.confirm) { _, _ -> viewModel.clearResult() }
        }
        builder.show()
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
