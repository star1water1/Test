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
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
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
            .setView(panel)
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
         * **보충(랜덤) 탭이 null인 것은 일부러다.** 그 화면은 `aiNarrativeBulkResult`를
         * 관측하지 않으므로, 버튼만 달면 **유료 실행이 끝나고도 검토 창이 뜨지 않는다** —
         * 눌렀는데 아무 일도 일어나지 않고 돈만 나가는 그 모양이다(B-144가 잡은 부류).
         * 잇는다면 **관측을 먼저 달고** 그다음에 이 인자를 넘긴다(백로그 B-184).
         *
         * **여기에 붙이는 이유:** 서술형이 일괄에서 빠진다는 사실을 사용자가 처음 아는 자리가
         * 이 창의 제외 고지다. 종전에는 *"필드별 ✨에서 작성합니다"*라고 **알려 주기만 하고**
         * 갈 길은 주지 않아, 필드 수만큼 ✨을 반복하는 수밖에 없었다(B-45가 적은 결함).
         */
        onNarrativeBulk: (() -> Unit)? = null,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
    ) {
        val context = fragment.requireContext()
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
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
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
            .setView(panel)
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
            val aiContext = contextLoader()
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
        val context = fragment.requireContext()
        val outcome = run.outcome
        if (outcome.suggestions.isEmpty()) {
            // 고지는 성공 경로와 **같은 조립기**를 쓴다 — 따로 적으면 한쪽에만 항목이 추가되는
            // (그래서 빈 결과일 때만 사유가 안 보이는) 어긋남이 생긴다. 실패 문구도 여기 포함된다.
            val message = buildString {
                append(fragment.getString(R.string.ai_field_nothing))
                buildNoticeLines(fragment, outcome).forEach { append("\n· ").append(it) }
            }
            val builder = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ai_field_suggest_title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm) { _, _ -> viewModel.clearAiSuggestResult() }
                .setOnCancelListener { viewModel.clearAiSuggestResult() }
            retryableTargets(run).takeIf { it.isNotEmpty() }?.let { retry ->
                builder.setNegativeButton(
                    fragment.getString(R.string.ai_field_retry_missing, retry.size)
                ) { _, _ ->
                    viewModel.clearAiSuggestResult()
                    runSuggest(
                        fragment, viewModel, contextLoader, retry,
                        singleMode = false, targetCharacterId = run.targetCharacterId,
                        // 첫 요청과 같은 그림으로 다시 묻는다 (A-7) — 근거가 조용히 바뀌지 않게
                        imagePaths = run.imagePaths
                    )
                }
            }
            builder.show()
            return
        }

        if (run.singleMode) {
            showSingleConfirm(fragment, formBuilder, viewModel, run, contextLoader)
        } else {
            showReviewDialog(fragment, formBuilder, viewModel, run, contextLoader)
        }
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
        CharacterFieldAiSuggester.MissingCause.INVALID,
        CharacterFieldAiSuggester.MissingCause.DUPLICATE
    )

    /**
     * 공통 상단 고지 — 수신 수·결손 명세·드롭·절단·부분 실패.
     * 요청 수와 수신 수를 **항상** 밝힌다: 열몇 개를 요청하고 서너 개만 받았을 때 그 사실이
     * 화면 어디에도 없으면 사용자는 앱이 제대로 동작한 줄 안다 (변수 제어 — 조용한 결손 금지).
     */
    private fun buildNoticeLines(
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

    private fun buildNotices(
        fragment: Fragment,
        outcome: CharacterFieldAiSuggester.SuggestOutcome
    ): String = buildNoticeLines(fragment, outcome).joinToString("\n")

    /** 필드 1개 모드: 체크리스트 대신 단일 확인 — 1건에 체크리스트는 조작 마찰만 추가 (원칙 04) */
    private fun showSingleConfirm(
        fragment: Fragment,
        formBuilder: DynamicFieldFormBuilder,
        viewModel: CharacterViewModel,
        run: CharacterViewModel.AiSuggestRun,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
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
            suggestion.confidence?.let { append("  [").append(it.label).append(']') }
            if (suggestion.outsideLibrary) {
                append("  [").append(fragment.getString(R.string.ai_field_outside_library)).append(']')
            }
            if (suggestion.reason.isNotBlank()) {
                append("\n\n").append(fragment.getString(R.string.ai_field_reason_format, suggestion.reason))
            }
            append("\n\n").append(buildNotices(fragment, outcome))
        }
        // 1건 모드에도 보완 경로를 준다 — 여기서 '취소'뿐이면 아쉬운 제안을 살릴 방법이 없다.
        // 체크박스가 없으므로 행 하나짜리 목록으로 같은 다이얼로그를 재사용한다.
        val row = Row(CheckBox(context), spec, suggestion)
        MaterialAlertDialogBuilder(context)
            .setTitle(spec.name)
            .setMessage(message)
            .setPositiveButton(R.string.ai_field_single_apply) { _, _ ->
                viewModel.clearAiSuggestResult()
                applySelected(fragment, formBuilder, listOf(row.suggestion))
            }
            .setNeutralButton(R.string.ai_field_refine) { d, _ ->
                d.dismiss()
                showRefineDialog(
                    fragment, viewModel, contextLoader, formBuilder, row, listOf(row),
                    targetCharacterId = run.targetCharacterId,
                    imagePaths = run.imagePaths,
                    dismissReview = {},
                    // 1건 모드에는 돌아갈 목록이 없다 — 수정 확정이 곧 적용이다(단계를 늘리지 않는다)
                    onEdited = { edited ->
                        viewModel.clearAiSuggestResult()
                        applySelected(fragment, formBuilder, listOf(edited.suggestion))
                    }
                )
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
        run: CharacterViewModel.AiSuggestRun,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext
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

        // 못 받은 필드를 다시 요청하는 경로 — 다이얼로그 버튼 3개가 이미 찼으므로 목록 안에 둔다.
        // 결손을 알리기만 하고 교정 경로를 안 주면 사용자는 전체 추천을 처음부터 다시 돌려야 한다.
        val retryTargets = retryableTargets(run)
        var dialogRef: AlertDialog? = null
        val rows = mutableListOf<Row>()
        if (retryTargets.isNotEmpty()) {
            list.addView(
                outlinedButton(context, density, fragment.getString(R.string.ai_field_retry_missing, retryTargets.size)) {
                    // 보완 재요청과 같은 규칙 — 재요청 전에 지금 고른 것을 폼에 적용해 지킨다
                    val keep = rows.filter { it.cb.isChecked }.map { it.suggestion }
                    if (keep.isNotEmpty()) applySelected(fragment, formBuilder, keep)
                    dialogRef?.dismiss()
                    viewModel.clearAiSuggestResult()
                    runSuggest(
                        fragment, viewModel, contextLoader, retryTargets,
                        singleMode = false, targetCharacterId = run.targetCharacterId,
                        imagePaths = run.imagePaths
                    )
                }
            )
        }

        // 행마다 [체크박스][보완] — 보완은 값 직접 수정과 지시를 단 재요청을 함께 연다.
        // 제안을 '받거나 버리거나' 둘뿐이면, 방향은 맞고 표현만 아쉬운 제안이 버려진다.
        for (s in outcome.suggestions) {
            val spec = specByKey[s.fieldKey] ?: continue
            val row = Row(cb = CheckBox(context), spec = spec, suggestion = s)
            // 폭은 addReviewRow가 정한다 — 가로 배치에서 가중치를 받던 종전 구조가
            // 여러 줄 텍스트의 높이를 잘못 잡아 사유가 잘렸다(실기기 보고, 2026.08.01).
            row.cb.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            // 빈 필드 채움은 비파괴 조작 — 기본 선택으로 마찰 최소화. 덮어쓰기와 '추측'은
            // 명시적 선택으로 남긴다(전체선택 한 번에 근거 얕은 값이 딸려 들어가지 않게).
            row.cb.isChecked = spec.currentValue.isBlank() &&
                s.confidence != CharacterFieldAiSuggester.Confidence.LOW
            renderRow(fragment, row)

            val refine = outlinedButton(context, density, fragment.getString(R.string.ai_field_refine)) {
                showRefineDialog(
                    fragment, viewModel, contextLoader, formBuilder, row, rows,
                    targetCharacterId = run.targetCharacterId,
                    imagePaths = run.imagePaths,
                    dismissReview = { dialogRef?.dismiss() },
                    onEdited = { edited ->
                        // 손수 고른 값은 곧 채택 의사다 — 체크를 켜 두어 한 번 더 누르게 하지 않는다
                        edited.cb.isChecked = true
                        renderRow(fragment, edited)
                    }
                )
            }
            addReviewRow(list, row, refine, density, isFirst = rows.isEmpty())
            rows.add(row)
        }

        // 내용만큼 자라되 화면을 넘으면 안에서 스크롤한다 — 상한이 없으면 긴 목록에서
        // 다이얼로그가 잘리고 끝까지 내려가지 않는다(공용 헬퍼가 단일 소스다).
        val scroll = cappedScrollView(context).apply { addView(list) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_review_title)
            .setView(scroll)
            .setPositiveButton(R.string.ai_field_apply, null)
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.clearAiSuggestResult() }
            .setNeutralButton(R.string.field_library_ai_select_all, null)
            .setOnCancelListener { viewModel.clearAiSuggestResult() }
            .create()
        dialogRef = dialog

        dialog.setOnShowListener {
            var allSelected = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                allSelected = !allSelected
                rows.forEach { it.cb.isChecked = allSelected }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = rows.filter { it.cb.isChecked }.map { it.suggestion }
                if (selected.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                viewModel.clearAiSuggestResult()
                applySelected(fragment, formBuilder, selected)
            }
        }
        dialog.show()
    }

    /** 검토 목록의 한 행 — 값이 수정될 수 있어 제안을 **가변**으로 들고 있는다 */
    private class Row(
        val cb: CheckBox,
        val spec: CharacterFieldAiSuggester.FieldSpec,
        var suggestion: CharacterFieldAiSuggester.Suggestion
    )

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    /** 목록 안에 두는 인라인 액션 버튼 — 폼의 🎲/✨ 버튼과 같은 외곽선 스타일로 통일한다 */
    private fun outlinedButton(
        context: android.content.Context,
        density: Float,
        label: String,
        action: () -> Unit
    ) = com.google.android.material.button.MaterialButton(
        context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        setOnClickListener { action() }
    }

    /**
     * 행 표시 갱신 — 값 수정 후에도 같은 규칙으로 다시 그리기 위해 한 곳에 둔다.
     *
     * **텍스트는 체크박스의 라벨로 둔다(밖으로 빼지 않는다).** 별도 TextView로 옮기면
     * 글을 눌러 켜고 끄던 넓은 탭 영역이 사라져, 보기 좋아지는 대신 누르기 어려워진다
     * (원칙 04). 그래서 계층은 **구조가 아니라 스팬**으로 준다 —
     * 필드 이름은 굵게, 근거 강도·수정 표시·사유는 작고 흐리게.
     * 항목끼리의 경계는 [addReviewRow]의 구분선이 맡는다.
     */
    private fun renderRow(fragment: Fragment, row: Row) {
        val s = row.suggestion
        val dim = row.cb.context.getColor(R.color.text_secondary)
        val sb = SpannableStringBuilder()

        // ① 필드 이름 — 어느 항목인지가 먼저 읽혀야 한다
        val nameStart = sb.length
        sb.append(row.spec.name).append(": ")
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), nameStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // ② 값 — 기본 스타일 그대로가 가장 잘 읽힌다
        if (row.spec.currentValue.isNotBlank()) {
            sb.append(fragment.getString(R.string.ai_field_overwrite_format, row.spec.currentValue, s.value))
        } else {
            sb.append(s.value)
        }

        // ③ 근거 강도·수정 표시 — 채택 판단의 핵심이라 값 옆에 두되, 값보다는 뒤로 물린다
        val markStart = sb.length
        s.confidence?.let { sb.append("  [").append(it.label).append(']') }
        if (s.editedByUser) sb.append("  [").append(fragment.getString(R.string.ai_field_edited)).append(']')
        // B-79 — 목록 밖 값은 버리지 않고 표시한다. 채택 판단의 재료이므로 강도와 같은 자리다.
        if (s.outsideLibrary) {
            sb.append("  [").append(fragment.getString(R.string.ai_field_outside_library)).append(']')
        }
        if (sb.length > markStart) {
            sb.setSpan(RelativeSizeSpan(0.9f), markStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(dim), markStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // ④ 사유 — 가장 길고 가장 덜 급한 부분. 작고 흐리게 두면 줄 수도 함께 준다
        if (s.reason.isNotBlank()) {
            val reasonStart = sb.length
            sb.append("\n  (").append(s.reason).append(')')
            sb.setSpan(RelativeSizeSpan(0.9f), reasonStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(dim), reasonStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        row.cb.text = sb
    }

    /**
     * 검토 목록에 항목 하나를 붙인다 — **항목 사이의 경계를 만드는 자리.**
     *
     * 종전에는 행이 그냥 세로로 쌓여, 다섯 줄짜리 사유 뒤에 다음 항목의 값이 바로 이어졌다.
     * 게다가 체크박스는 긴 라벨의 **세로 중앙**에 붙어 다음 항목의 글 옆에 놓였다 —
     * 어느 체크박스가 어느 항목인지 눈으로 짚을 수 없는 상태였다(사용자 보고, 2026.08.01).
     *
     * 셋으로 고친다: ① 체크박스와 '보완'을 **위로 정렬**해 항목의 첫 줄과 나란히 두고
     * ② 항목마다 위아래 여백을 주고 ③ 항목 사이에 **구분선**을 넣는다.
     * 색은 `outline_variant`라 밝은 테마·어두운 테마 양쪽이 함께 정의돼 있다.
     */
    private fun addReviewRow(list: LinearLayout, row: Row, refine: View, density: Float, isFirst: Boolean) {
        val context = list.context
        if (!isFirst) {
            list.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, (density).toInt()))
                setBackgroundColor(context.getColor(R.color.outline_variant))
            })
        }
        // 체크 표시가 라벨 세로 중앙이 아니라 **첫 줄 옆**에 오게 한다(CompoundButton의 내부 정렬).
        row.cb.gravity = Gravity.TOP or Gravity.START
        val vPad = (10 * density).toInt()
        // **세로로 쌓는다.** 종전에는 [체크박스(가중치 1)][보완]을 가로로 놓았는데,
        // 가로 LinearLayout 안에서 가중치를 받는 **여러 줄 텍스트**는 높이가 잘못 잡혀
        // 사유가 세 줄쯤에서 잘렸다(실기기 보고, 2026.08.01 — 스크린샷). 폭 계산과 높이 계산이
        // 두 번에 나뉘어 도는 자리라 조건이 맞으면 재발하며, 로컬에는 안드로이드 런타임이 없어
        // 그 조건을 재현·검증할 수단이 없다. 그래서 원인을 짐작해 한 줄 고치는 대신
        // **그 구조를 쓰지 않는다** — 텍스트가 폭을 통째로 쓰므로 줄 수도 함께 준다.
        (refine.layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.END
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, vPad, 0, vPad)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            addView(row.cb)
            addView(refine)
        })
    }

    /**
     * 보완 다이얼로그 — 한 제안에 대해 **직접 수정**과 **지시를 단 재요청**을 함께 연다.
     *
     * 재요청은 화면을 갈아 끼우므로(결과가 단일 확인으로 돌아온다) 그 전에 **지금 체크된 제안을
     * 폼에 먼저 적용**한다. 적용은 폼 위젯 기입일 뿐 저장이 아니라 되돌리기 쉽고, 그렇게 하지
     * 않으면 한 필드를 보완하려다 나머지 선택을 통째로 잃는다 (원칙 04 — 마찰 최소화).
     */
    private fun showRefineDialog(
        fragment: Fragment,
        viewModel: CharacterViewModel,
        contextLoader: suspend () -> CharacterFieldAiSuggester.CharacterAiContext,
        formBuilder: DynamicFieldFormBuilder,
        row: Row,
        allRows: List<Row>,
        targetCharacterId: Long,
        /** 첫 요청에 실었던 이미지 (A-7) — 보완도 같은 그림으로 묻는다 */
        imagePaths: List<String>,
        dismissReview: () -> Unit,
        /** 값 수정이 확정된 뒤 할 일 — 검토 목록은 다시 그리고, 1건 모드는 바로 적용한다 */
        onEdited: (Row) -> Unit
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val valueInput = EditText(context).apply {
            setText(row.suggestion.value)
            hint = fragment.getString(R.string.ai_field_refine_value_hint)
            setSingleLine(false)
        }
        val optionHint = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            text = buildString {
                row.spec.formatHint?.let { append(fragment.getString(R.string.ai_field_format_hint, it)) }
                if (row.spec.options.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(fragment.getString(R.string.ai_field_options_hint, row.spec.options.joinToString(", ")))
                }
            }
            isVisible = text.isNotEmpty()
        }
        val instruction = EditText(context).apply {
            hint = fragment.getString(R.string.ai_field_refine_instruction_hint)
            setSingleLine(false)
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(valueInput)
            addView(optionHint)
            // 창작도 칩 (A-4 §6-8) — 가장 값진 자리: "이 값은 뻔하다 → 올려서 다시"가 1탭이 된다
            addView(CreativityChipRow.create(fragment))
            addView(instruction)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(row.spec.name)
            .setView(panel)
            .setPositiveButton(R.string.ai_field_refine_use_value, null)
            .setNeutralButton(R.string.ai_field_refine_reask, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = valueInput.text?.toString().orEmpty().trim()
                if (typed.isEmpty()) {
                    valueInput.error = fragment.getString(R.string.ai_field_refine_value_required)
                    return@setOnClickListener
                }
                // 사용자가 직접 넣은 값도 **저장 시와 같은 규칙**으로 검증한다. 통과 못 한 값을
                // 폼에 넣으면 위젯이 조용히 무시하거나(Spinner) 저장 단계에서 튕긴다 —
                // 그 실패를 지금, 고칠 수 있는 자리에서 알린다 (변수 제어).
                when (val checked = CharacterFieldAiSuggester.normalizeChecked(typed, row.spec)) {
                    is CharacterFieldAiSuggester.Normalized.Ok -> {
                        // 목록 밖이어도 막지 않는다 (B-79) — 여기서 튕기면 사용자가 손으로 적은
                        // 값을 폼에서는 받고 이 창에서만 거절하는, 같은 비대칭의 축소판이 된다.
                        row.suggestion = row.suggestion.copy(
                            value = checked.value,
                            reason = fragment.getString(R.string.ai_field_edited_reason),
                            editedByUser = true,
                            outsideLibrary = checked.outsideLibrary
                        )
                        dialog.dismiss()
                        onEdited(row)
                    }
                    is CharacterFieldAiSuggester.Normalized.Rejected ->
                        valueInput.error = checked.cause.label
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val hint = instruction.text?.toString().orEmpty().trim()
                dialog.dismiss()
                // 선택분을 먼저 폼에 적용 — 재요청으로 검토 화면을 잃어도 고른 것은 남는다
                val keep = allRows.filter { it.cb.isChecked && it !== row }.map { it.suggestion }
                if (keep.isNotEmpty()) applySelected(fragment, formBuilder, keep)
                dismissReview()
                viewModel.clearAiSuggestResult()
                val target = row.spec.copy(
                    userInstruction = hint.ifEmpty { null },
                    rejectedValues = row.spec.rejectedValues + row.suggestion.value
                )
                // 콕 집어 다시 묻는 요청이므로 근거 강도 하한을 적용하지 않는다
                runSuggest(
                    fragment, viewModel, contextLoader, listOf(target),
                    singleMode = true, applyConfidenceFilter = false,
                    targetCharacterId = targetCharacterId,
                    imagePaths = imagePaths
                )
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
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
