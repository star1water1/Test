package com.novelcharacter.app.ui.character

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.cappedScrollView
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

    /** 필드 1개 추천 — 폼의 ✨ 버튼 진입점. [targetCharacterId]는 오적용 검출 축(A-3, 신규는 -1) */
    fun showForField(
        fragment: Fragment,
        field: FieldDefinition,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        targetCharacterId: Long,
        /** 폼의 라이브 이미지 목록 (A-7) — 첨부 줄이 여기서 고른다 */
        imagePaths: List<String> = emptyList(),
        representativePath: String? = null,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        if (viewModel.recoverAiSuggest(targetCharacterId)) return
        if (!guardProvider(fragment)) return

        val currentValue = currentValuesByFieldId(formBuilder)[field.id] ?: ""
        val spec = CharacterFieldAiSuggester.fieldSpecOf(field, currentValue) ?: return

        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val attach = AiImageAttachRow.create(fragment, imagePaths, representativePath)
        val costLine = TextView(context).apply {
            textSize = 15f
            text = fragment.getString(R.string.ai_field_cost_notice_single, field.name)
        }
        // 이미지가 붙으면 그 사실과 값을 **같은 자리에서** 말한다 — 기존 약속("이미지를
        // 모델에 보내지 않는다")을 뒤집는 지점이라 침묵이 허용되지 않는다.
        val imageCostLine = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = false
        }
        val briefingInput = briefingInput(fragment, viewModel, targetCharacterId, formBuilder, contextLoader)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(briefingInput)
            addView(costLine)
            addView(imageCostLine)
            attach?.let { addView(it.view) }
        }
        fun refreshImageCost() {
            val count = attach?.selected?.size ?: 0
            imageCostLine.isVisible = count > 0
            if (count > 0) imageCostLine.text = imageCostText(fragment, count, requestCount = 1)
        }
        attach?.onChanged { refreshImageCost() }
        refreshImageCost()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_suggest_title)
            .setView(cappedScrollView(context).apply { addView(panel) })
            .setPositiveButton(R.string.ai_field_run) { _, _ ->
                runSuggest(
                    fragment, viewModel, contextLoader, listOf(spec),
                    singleMode = true, targetCharacterId = targetCharacterId,
                    imagePaths = attach?.selected.orEmpty()
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 이미지 비용 고지의 **단일 소스** — 붙는 자리가 셋(필드 1개·전체·서술형)이라
     * 따로 적으면 한 곳만 고쳐지고 나머지가 낡는다.
     *
     * 연인원을 말하는 이유: 짧은 값 추천은 대상을 청킹해 **요청마다 같은 그림을 다시
     * 싣는다.** 1장이라고만 적으면 사용자는 1장 값을 예상하고 요청 수만큼을 낸다(R-4).
     */
    private fun imageCostText(fragment: Fragment, count: Int, requestCount: Int): String {
        val total = com.novelcharacter.app.ai.AiPromptPolicy.imageSendCount(count, requestCount)
        return fragment.getString(
            R.string.ai_image_cost_notice,
            count, total,
            com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MIN,
            com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MAX
        )
    }

    /**
     * 전체 필드 추천 — 편집 화면·보충 탭의 'AI 필드 추천' 버튼 진입점.
     * [extraNote]는 비용 고지에 덧붙일 맥락 한 줄(보충 플로우의 기대치 조정 등). null이면 없음.
     */
    fun showForCharacter(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        targetCharacterId: Long,
        extraNote: String? = null,
        /** 폼의 라이브 이미지 목록 (A-7) — 첨부 줄이 여기서 고른다 */
        imagePaths: List<String> = emptyList(),
        representativePath: String? = null,
        /**
         * 서술형 일괄 초안으로 넘어가는 길 (B-45). null이면 그 버튼을 띄우지 않는다.
         *
         * **넘기기 전에 부르는 화면이 갖춰야 할 것이 있다** — `aiNarrativeBulkResult` 관측
         * (없으면 유료 실행이 끝나고도 검토 창이 안 뜬다. 눌렀는데 아무 일도 일어나지 않고
         * 돈만 나가는 그 모양 — B-144가 잡은 부류) · `aiNarrativeBulkRunning`을 *실행 중*
         * 판정에 넣는 것(빠지면 대상마다 순차로 도는 동안 다른 캐릭터로 옮겨 갈 수 있어,
         * A를 근거로 결제한 초안이 B의 폼에 들어간다) · 진행 표시(`aiNarrativeBulkProgress` —
         * 필드마다 요청 하나라 몇 번째인지 안 보이면 멈춘 것으로 읽힌다).
         * **지금 넘기는 화면은 둘이고 둘 다 그것을 갖췄다** — 편집 화면 · 보충(랜덤) 탭
         * (B-184에서 관측을 먼저 달고 이 인자를 이었다).
         *
         * **여기에 붙이는 이유:** 서술형이 일괄에서 빠진다는 사실을 사용자가 처음 아는 자리가
         * 이 창의 제외 고지다. 종전에는 *"필드별 ✨에서 작성합니다"*라고 **알려 주기만 하고**
         * 갈 길은 주지 않아, 필드 수만큼 ✨을 반복하는 수밖에 없었다(B-45가 적은 결함).
         */
        onNarrativeBulk: (() -> Unit)? = null,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
        if (viewModel.recoverAiSuggest(targetCharacterId)) return
        if (!guardProvider(fragment)) return

        // 대상 규칙의 단일 소스 — 보충(랜덤) 탭도 같은 함수를 쓴다. 여기서 필터를 직접
        // 조립하면 두 화면이 갈린다 (A-1: AI 꺼짐·서술형·계산 필드가 사유별로 제외된다).
        val currentValues = currentValuesByFieldId(formBuilder)
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(formBuilder.fieldDefinitions, currentValues)
        // 서술형 일괄 초안으로 넘길 것이 몇 개인가 (B-45) — 대상 규칙은 그쪽이 단일 소스라
        // 여기서 조건을 다시 적지 않는다(적으면 두 화면이 갈린다).
        val narrativeCount = if (onNarrativeBulk == null) 0 else
            com.novelcharacter.app.ai.NarrativeFieldAiWriter
                .bulkDraftTargetsOf(formBuilder.fieldDefinitions, currentValues).targets.size

        if (bulk.targets.isEmpty()) {
            // 대상 0이어도 왜 0인지는 밝힌다 — 전부 꺼짐/서술형일 때 침묵하면 기능 고장으로 읽힌다
            val message = buildString {
                append(fragment.getString(R.string.ai_field_no_targets))
                CharacterFieldAiSuggester.bulkExcludedDetailLines(bulk.excluded)
                    .forEach { append("\n· ").append(it) }
            }
            MaterialAlertDialogBuilder(context)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .apply {
                    // **짧은 값 대상이 0인데 서술형은 있는 경우가 이 기능의 대표 사례다** —
                    // 성격·배경만 채워 넣은 폼이 정확히 그 모양이다. 여기서 길을 주지 않으면
                    // 사용자는 "AI 추천이 안 된다"로 읽고 창을 닫는다.
                    if (narrativeCount > 0) {
                        setNeutralButton(
                            fragment.getString(R.string.ai_narrative_bulk_entry, narrativeCount)
                        ) { _, _ -> onNarrativeBulk?.invoke() }
                    }
                }
                .show()
            return
        }
        val emptyIds = formBuilder.emptyEditableFieldIds()
        val emptySpecs = bulk.targets.filter { it.fieldId in emptyIds }.map { it.spec }
        val allSpecList = bulk.targets.map { it.spec }

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
        // 제외 고지 — 사라진 것을 개수로 남긴다(R-14). 계산 필드 제외는 종전에도 있었지만
        // 아무도 알려주지 않았다 — 이 줄이 그것도 함께 메운다.
        val excludedSummary = CharacterFieldAiSuggester.bulkExcludedSummary(bulk.excluded)
        val excludedText = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = excludedSummary != null
            text = excludedSummary?.let { fragment.getString(R.string.ai_field_excluded_line, it) }
        }
        val excludedDetail = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = false
            text = CharacterFieldAiSuggester.bulkExcludedDetailLines(bulk.excluded)
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
        val briefingInput = briefingInput(fragment, viewModel, targetCharacterId, formBuilder, contextLoader)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(briefingInput)
            addView(message)
            addView(excludedText)
            addView(showExcludedLink)
            addView(excludedDetail)
            if (extraNote != null) {
                addView(TextView(context).apply {
                    textSize = 12f
                    setTextColor(context.getColor(R.color.text_secondary))
                    text = extraNote
                    setPadding(0, (4 * density).toInt(), 0, 0)
                })
            }
            // 창작도 칩 (A-4 §6-8) — 실행 직전 1탭. 값은 설정과 같은 단일 소스다.
            addView(CreativityChipRow.create(fragment))
            addView(includeFilledCheck)
        }
        // 이미지 첨부 줄 (A-7) — 비용 문구 바로 아래에 붙어 "몇 장이 몇 번 나가는가"를
        // 대상 수와 한 화면에서 보여 준다.
        val imageCostLine = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = false
        }
        val attach = AiImageAttachRow.create(fragment, imagePaths, representativePath)
        panel.addView(imageCostLine)
        attach?.let { panel.addView(it.view) }

        fun currentTargets(): List<CharacterFieldAiSuggester.FieldSpec> =
            if (includeFilledCheck.isChecked) allSpecList else emptySpecs

        // 청킹은 활성 프로바이더의 출력 상한에서 파생되므로, 고지도 **같은 값**으로 계산해야 한다.
        // 상수로 계산하면 사용자가 상한을 올렸을 때 고지된 요청 수와 실제가 어긋난다.
        val budget = AiService(context).effectiveMaxTokens()

        fun refreshMessage() {
            val count = currentTargets().size
            val requests = CharacterFieldAiSuggester.requestCountFor(count, budget)
            message.text = if (count == 0) {
                fragment.getString(R.string.ai_field_no_empty_fields)
            } else {
                // 요청 수는 청킹 규칙과 같은 계산 — 사전 고지 정확성 (R-4)
                fragment.getString(R.string.ai_field_cost_notice, count, requests)
            }
            // 이미지는 **요청마다 다시 실린다** — 대상 수가 바뀌면 요청 수가 바뀌고,
            // 그러면 이미지 연인원도 함께 바뀐다. 둘을 같은 함수에서 그리는 이유다.
            val images = attach?.selected?.size ?: 0
            imageCostLine.isVisible = images > 0 && count > 0
            if (images > 0 && count > 0) {
                imageCostLine.text = imageCostText(fragment, images, requests)
            }
        }
        attach?.onChanged { refreshMessage() }
        refreshMessage()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_suggest_title)
            .setView(cappedScrollView(context).apply { addView(panel) })
            .setPositiveButton(R.string.ai_field_run, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                // 서술형이 이 일괄에서 빠진다는 사실을 위 제외 고지가 말한 그 자리에서
                // 갈 길도 함께 준다 (B-45).
                if (narrativeCount > 0) {
                    setNeutralButton(
                        fragment.getString(R.string.ai_narrative_bulk_entry, narrativeCount)
                    ) { _, _ -> onNarrativeBulk?.invoke() }
                }
            }
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
                runSuggest(
                    fragment, viewModel, contextLoader, targets,
                    singleMode = false, targetCharacterId = targetCharacterId,
                    imagePaths = attach?.selected.orEmpty()
                )
            }
        }
        dialog.show()
    }

    // ===== 공통 파이프라인 =====

    fun briefingInput(fragment: Fragment, viewModel: CharacterViewModel, characterId: Long,
        formBuilder: DynamicFieldFormBuilder,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext): LinearLayout =
        com.novelcharacter.app.ui.common.NaturalLanguageInput.create(fragment, "briefing:$characterId",
            "캐릭터 구상 (선택) · 생각나는 대로 적어 주세요", viewModel.aiBriefingDrafts[characterId].orEmpty(),
            onChanged={viewModel.aiBriefingDrafts[characterId]=it},
            terms={viewModel.speechTerms(contextLoader(),characterId,formBuilder.fieldDefinitions)})

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
        singleMode: Boolean,
        applyConfidenceFilter: Boolean = true,
        targetCharacterId: Long = -1L,
        /**
         * 함께 보낼 이미지 경로 (A-7). 보완 재요청은 **첫 요청과 같은 것**을 넘긴다 —
         * 다시 고르게 하면 사용자가 이미 정한 선택을 되풀이시키는 마찰이고, 비우면
         * 같은 검토 화면 안에서 근거가 조용히 바뀐다.
         */
        imagePaths: List<String> = emptyList()
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val aiContext = contextLoader().copy(briefing=viewModel.aiBriefingDrafts[targetCharacterId].orEmpty())
            if (!fragment.isAdded) return@launch
            if (!viewModel.runAiSuggest(
                    aiContext, targets, singleMode, applyConfidenceFilter, targetCharacterId, imagePaths
                )
            ) {
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
        run: CharacterViewModel.AiSuggestRun,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        com.novelcharacter.app.ui.common.FieldSuggestionReviewDialog.show(
            fragment, run.targets, run.outcome, viewModel.aiReviewState,
            "요청 당시 캐릭터: ${run.context?.name.orEmpty()}\n" + buildNotices(fragment, run.outcome),
            onApply = { selected ->
                if (applySelected(fragment, formBuilder, selected)) {
                    viewModel.clearAiSuggestResult()
                    true
                } else false
            },
            onClose = { viewModel.clearAiSuggestResult() },
            onRefine = { keys, instruction ->
                val context = run.context
                if (context == null) {
                    fragment.notifyError(fragment.getString(R.string.ai_field_apply_none))
                    false
                } else {
                    val targets = run.targets.filter { it.key in keys }.map { spec ->
                        val current = run.outcome.suggestions.firstOrNull { it.fieldKey == spec.key }
                            ?.let { viewModel.aiReviewState.current(it) }
                        spec.copy(userInstruction=instruction.takeIf { it.isNotBlank() },
                            rejectedValues=spec.rejectedValues + listOfNotNull(current?.value))
                    }
                    val started = viewModel.runAiSuggest(context, targets, run.singleMode, false,
                        run.targetCharacterId, run.imagePaths, carryOver=run)
                    if (!started) Toast.makeText(fragment.requireContext(), R.string.ai_field_running,
                        Toast.LENGTH_SHORT).show()
                    started
                }
            },
            retryKeys = retryableTargets(run).map { it.key },
            imageCount = run.imagePaths.size,
            running = viewModel.aiSuggestRunning
        )
    }

    /**
     * 다시 요청할 만한 대상 — 결손 중 **재요청으로 달라질 수 있는 것**만 고른다.
     * 현재 값과 같아서 빠진 것(SAME_AS_CURRENT)이나 모델이 사유를 밝힌 것(DECLINED)까지
     * 자동으로 다시 부르면 사용자가 같은 답에 두 번 과금된다.
     */
    private fun retryableTargets(
        run: CharacterViewModel.AiSuggestRun
    ): List<CharacterFieldAiSuggester.FieldSpec> {
        val specByKey = run.targets.associateBy { it.key }
        return run.outcome.missing
            .filter { it.cause in RETRYABLE_CAUSES }
            .mapNotNull { specByKey[it.fieldKey] }
    }

    private val RETRYABLE_CAUSES = setOf(
        CharacterFieldAiSuggester.MissingCause.NOT_RETURNED,
        CharacterFieldAiSuggester.MissingCause.TRUNCATED,
        CharacterFieldAiSuggester.MissingCause.UNREADABLE,
        CharacterFieldAiSuggester.MissingCause.REQUEST_FAILED,
        CharacterFieldAiSuggester.MissingCause.NOT_REQUESTED,
        CharacterFieldAiSuggester.MissingCause.CANCELLED,
        CharacterFieldAiSuggester.MissingCause.INVALID,
        CharacterFieldAiSuggester.MissingCause.DUPLICATE
    )

    /**
     * 공통 상단 고지 — 수신 수·결손 명세·드롭·절단·부분 실패.
     * 요청 수와 수신 수를 **항상** 밝힌다: 열몇 개를 요청하고 서너 개만 받았을 때 그 사실이
     * 화면 어디에도 없으면 사용자는 앱이 제대로 동작한 줄 안다 (변수 제어 — 조용한 결손 금지).
     */
    /**
     * 결과 고지 줄 — **무엇을 말하는가의 단일 소스**다 (사건 축도 이것을 쓴다, B-43).
     *
     * 토큰 사용량·결손 사유·'목록 밖' 뜻·환각 key·드롭 수·절단·실패는 전부 *유료 응답에서
     * 무엇이 어떻게 됐는가*이고, 그 판단은 축을 타지 않는다. 축마다 따로 적으면 같은 사건을
     * 화면마다 다른 말로 알리게 된다(이 저장소가 반복해 겪은 모양).
     */
    fun buildNoticeLines(
        fragment: Fragment,
        outcome: CharacterFieldAiSuggester.SuggestOutcome
    ): List<String> = buildList {
        add(fragment.getString(R.string.field_library_ai_token_usage, outcome.inputTokens, outcome.outputTokens))
        if (outcome.requestedCount > 1) {
            add(
                CharacterFieldAiSuggester.receivedSummary(
                    outcome.requestedCount, outcome.suggestions.size
                )
            )
        }
        addAll(CharacterFieldAiSuggester.missingLines(outcome.missing))
        // B-79 — '목록 밖' 표식이 무엇을 뜻하는지는 표식 옆이 아니라 여기서 말한다
        CharacterFieldAiSuggester.outsideLibraryLine(outcome.suggestions)?.let { add(it) }
        if (outcome.unknownKeys.isNotEmpty()) {
            add(fragment.getString(R.string.ai_field_unknown_keys, outcome.unknownKeys.size))
        }
        if (outcome.droppedCount > 0) {
            add(fragment.getString(R.string.ai_field_dropped, outcome.droppedCount))
        }
        outcome.truncationNotes.forEach {
            add(fragment.getString(R.string.ai_field_truncated_prefix, it))
        }
        addAll(outcome.failures)
    }

    fun buildNotices(
        fragment: Fragment,
        outcome: CharacterFieldAiSuggester.SuggestOutcome
    ): String = buildNoticeLines(fragment, outcome).joinToString("\n")

    /**
     * 제공사 가드 — 쓸 수 있는 프로바이더가 없으면 **설정으로 가는 길과 함께** 막는다.
     * 사건 축도 같은 것을 쓴다: 문구가 갈리면 같은 상태를 두 화면이 다르게 설명한다.
     */
    fun guardUsableProvider(fragment: Fragment): Boolean = guardProvider(fragment)

    /** 필드 1개 모드: 체크리스트 대신 단일 확인 — 1건에 체크리스트는 조작 마찰만 추가 (원칙 04) */
    private fun applySelected(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        selected: List<CharacterFieldAiSuggester.Suggestion>
    ): Boolean {
        val fieldByKey = formBuilder.fieldDefinitions.associateBy { it.key }
        var applied = 0
        for (s in selected) {
            val field = fieldByKey[s.fieldKey]
            if (field == null || !formBuilder.applyReviewedValue(field, s.value)) continue
            applied++
        }
        if (applied == selected.size && applied > 0) {
            fragment.notifySuccess(fragment.getString(R.string.ai_field_applied, applied))
        } else {
            // 회전 직후 폼 재구축 전 등 — 무통보 no-op 금지, 재시도 경로 안내 (변수 제어)
            fragment.notifyError(fragment.getString(R.string.ai_field_apply_none))
        }
        return applied == selected.size && applied > 0
    }
}
