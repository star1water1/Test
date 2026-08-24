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
            val aiContext = contextLoader()
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
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        // **초안은 받았는데 필드가 사라진 것**(검토 중 다른 화면에서 삭제 등)을 따로 센다.
        // 종전 판은 이것을 `usable` 필터로 걸러 내기만 하고 고지에서도 빠뜨려, **결제한 초안이
        // 아무 말 없이 사라졌다** — 이 저장소가 가장 경계하는 그 모양이다(콜드 검토가 잡았다).
        val gone = run.items.filter { it.hasDraft && fieldOf(it.fieldId) == null }
        val usable = run.items.filter { it.hasDraft && fieldOf(it.fieldId) != null }
        val notices = buildNotices(fragment, run, gone)

        if (usable.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ai_narrative_bulk_title)
                .setMessage(
                    buildString {
                        append(fragment.getString(R.string.ai_narrative_bulk_nothing))
                        if (notices.isNotEmpty()) append("\n\n").append(notices)
                    }
                )
                .setPositiveButton(R.string.confirm) { _, _ -> viewModel.clearAiNarrativeBulkResult() }
                .setOnCancelListener { viewModel.clearAiNarrativeBulkResult() }
                .show()
            return
        }

        // 필드마다 체크박스 + 초안 미리보기. 기본은 전부 켬 — 일괄의 목적이 '한 번에 깔기'라
        // 하나씩 켜게 하면 그 목적이 사라진다(조작 마찰). 마음에 안 드는 것만 끄면 된다.
        //
        // **체크는 뷰모델이 든다**(R-38) — 종전에는 아래 `CheckBox` 위젯에만 살아서, 회전
        // 한 번에 껐던 필드가 전부 다시 켜졌다. 체크가 풀린 화면은 *"내가 아직 안 골랐다"*와
        // 구별되지 않으므로, 사용자는 마음에 안 들던 초안이 깔리는 줄 모른 채 [적용]을 누른다.
        // 기본 켜기는 **첫 조립에서만** 심는다 — 두 번째부터 심으면 회전이 판단을 덮는다.
        val state = viewModel.aiNarrativeBulkReviewState
        state.seedDefaults(usable.map { it.fieldId })
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            if (notices.isNotEmpty()) {
                addView(TextView(context).apply {
                    textSize = 12f
                    setTextColor(context.getColor(R.color.text_secondary))
                    text = notices
                    setPadding(0, 0, 0, (8 * density).toInt())
                })
            }
            for (item in usable) {
                val box = CheckBox(context).apply {
                    text = item.fieldName
                    isChecked = state.isChecked(item.fieldId)
                    setOnCheckedChangeListener { _, on -> state.setChecked(item.fieldId, on) }
                }
                addView(box)
                addView(TextView(context).apply {
                    textSize = 13f
                    setTextColor(context.getColor(R.color.text_secondary))
                    val draft = item.outcome.drafts.first().text
                    text = draft.take(PREVIEW_CHARS) + if (draft.length > PREVIEW_CHARS) "…" else ""
                    setPadding((12 * density).toInt(), 0, 0, (10 * density).toInt())
                })
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_narrative_bulk_pick)
            // 본문 스크롤에는 높이 상한이 있어야 한다 (R-31) — 필드가 많으면 [적용] 버튼이
            // 화면 밖으로 밀려 **고른 것을 넣을 길이 없어진다.**
            .setView(com.novelcharacter.app.util.cappedScrollView(context).apply { addView(panel) })
            .setPositiveButton(R.string.ai_narrative_bulk_apply, null) // 검증 통과 시에만 닫힘
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.clearAiNarrativeBulkResult() }
            .setOnCancelListener { viewModel.clearAiNarrativeBulkResult() }
            .create()
        // **비우는 것은 적용이 성립한 뒤다** (B-163 — 형제 검토 창들이 지키는 그 규칙).
        // 종전에는 [적용]이 무조건 결과를 비워서, 고른 것이 0개거나 검토 중 필드가 지워져
        // 아무것도 못 넣은 경우에도 **결제한 초안이 통째로 사라졌다** — 짧은 값 검토 창은
        // 빈 선택을 막는데(R-17) 이 창만 조용히 비웠다.
        dialog.setValidatedPositiveButton {
            val picked = usable.filter { state.isChecked(it.fieldId) }
            if (picked.isEmpty()) {
                Toast.makeText(
                    context, R.string.ai_review_pick_none, Toast.LENGTH_SHORT
                ).show()
                return@setValidatedPositiveButton false
            }
            var applied = 0
            var vanished = 0
            for (item in picked) {
                // 검토 중 다른 화면에서 필드가 지워졌을 수 있다 — 조용히 덜 세지 않고 갈라 센다.
                val field = fieldOf(item.fieldId)
                if (field == null) {
                    vanished++
                    continue
                }
                // 필드마다 토스트를 띄우지 않는다 — 일괄이라 N번 겹쳐 뜨고, 그러면
                // 정작 읽어야 할 합계 고지가 그 뒤에 가린다.
                formBuilder.applyRandomValue(field, item.outcome.drafts.first().text, showToast = false)
                applied++
            }
            Toast.makeText(
                context,
                if (vanished == 0) fragment.getString(R.string.ai_narrative_bulk_applied, applied)
                else fragment.getString(R.string.ai_narrative_bulk_applied_vanished, applied, vanished),
                Toast.LENGTH_SHORT
            ).show()
            viewModel.clearAiNarrativeBulkResult()
            true
        }
        dialog.show()
    }

    /**
     * 토큰 사용·실패·보내지 않은 필드 — **조용히 버린 것이 없음을 항상 보인다.**
     * 토큰은 필드마다 따로 나오므로 합해서 한 줄로 말한다(필드별로 늘어놓으면 검토가 안 읽힌다).
     */
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
