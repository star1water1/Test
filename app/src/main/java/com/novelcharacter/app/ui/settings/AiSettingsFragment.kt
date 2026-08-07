package com.novelcharacter.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.text.util.LinkifyCompat
import androidx.core.util.PatternsCompat
import androidx.core.view.children
import androidx.core.widget.doOnTextChanged
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiCreativity
import com.novelcharacter.app.ai.AiErrorMessages
import com.novelcharacter.app.ai.AiKeyStore
import com.novelcharacter.app.ai.AiModelInfo
import com.novelcharacter.app.ai.AiModelListResult
import com.novelcharacter.app.ai.AiModelSuggestions
import com.novelcharacter.app.ai.AiPreset
import com.novelcharacter.app.ai.AiPresets
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.AiProtocolCodec
import com.novelcharacter.app.ai.AiProviderConfig
import com.novelcharacter.app.ai.AiProviderStore
import com.novelcharacter.app.ai.AiResult
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.AiTokenPolicy
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.databinding.DialogAiModelPickerBinding
import com.novelcharacter.app.databinding.DialogAiProviderEditBinding
import com.novelcharacter.app.databinding.FragmentAiSettingsBinding
import com.novelcharacter.app.databinding.ItemAiModelBinding
import com.novelcharacter.app.databinding.ItemAiProviderBinding
import com.novelcharacter.app.util.AppLogger
import com.novelcharacter.app.util.setValidatedPositiveButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AI 연동 설정 화면 — BYOK(사용자 본인 키) 방식의 프로바이더 등록·편집·삭제·활성 선택.
 *
 * 원칙 반영:
 * - 프리셋은 출발점일 뿐, 생성된 항목은 전 필드 편집 가능(원칙 01).
 * - 키 등록 → 즉시 '연결 테스트'로 검증 → 실패 시 분류된 안내 + 교정 경로(변수 제어).
 * - 발급 가이드·콘솔 딥링크로 처음 쓰는 사람도 헤매지 않게(조작 마찰 최소화, 원칙 04).
 */
class AiSettingsFragment : Fragment() {

    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var providerStore: AiProviderStore
    private lateinit var keyStore: AiKeyStore
    private lateinit var aiService: AiService

    private val adapter = ProviderAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        providerStore = AiProviderStore(ctx)
        keyStore = AiKeyStore(ctx)
        aiService = AiService(ctx)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.providerList.layoutManager = LinearLayoutManager(ctx)
        binding.providerList.adapter = adapter
        binding.addProviderButton.setOnClickListener { showPresetPicker() }

        setupConsistencySliders()
        setupCreativityGroup()
        refreshList()
    }

    /**
     * 창작도 (A-4) — 4택 라디오, 고르는 순간이 곧 확정(저장 단계 없음).
     * 자유·실험은 근거 강도 하한과의 충돌 가드([CreativityChipRow.applyWithConflictGuard])를
     * 거친다 — 칩 표면과 같은 규칙·같은 값이다. 프로토콜 상한 고지(§6-4)는 활성 프로바이더 기준.
     */
    private fun setupCreativityGroup() {
        val settings = AiPromptSettings(requireContext())
        val idOf = { c: AiCreativity ->
            when (c) {
                AiCreativity.PRECISE -> R.id.creativityPrecise
                AiCreativity.BALANCED -> R.id.creativityBalanced
                AiCreativity.FREE -> R.id.creativityFree
                AiCreativity.BOLD -> R.id.creativityBold
            }
        }
        var suppress = true
        binding.creativityGroup.check(idOf(settings.creativity))
        suppress = false
        binding.creativityGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppress) return@setOnCheckedChangeListener
            val chosen = when (checkedId) {
                R.id.creativityPrecise -> AiCreativity.PRECISE
                R.id.creativityFree -> AiCreativity.FREE
                R.id.creativityBold -> AiCreativity.BOLD
                else -> AiCreativity.BALANCED
            }
            if (chosen == settings.creativity) return@setOnCheckedChangeListener
            com.novelcharacter.app.ui.character.CreativityChipRow.applyWithConflictGuard(this, chosen) {
                // 취소·적용 어느 쪽이든 라디오를 실제 저장값으로 되돌린다(화면이 거짓이 되지 않게)
                if (_binding != null) {
                    suppress = true
                    binding.creativityGroup.check(idOf(settings.creativity))
                    suppress = false
                }
            }
        }
        renderCreativityProtocolNote()
    }

    /** §6-4 — Anthropic처럼 상한 1.0인 프로토콜에서는 자유·실험 차이가 작다는 사실을 밝힌다. */
    private fun renderCreativityProtocolNote() {
        val protocol = providerStore.active()?.protocol
        binding.creativityProtocolNote.visibility =
            if (protocol != null && AiCreativity.narrowTopRange(protocol)) View.VISIBLE else View.GONE
    }

    /**
     * AI 추천 일관성 — 프롬프트에 참고 자료를 얼마나 실을지.
     *
     * 저장 버튼을 두지 않는다: 슬라이더를 놓는 순간이 곧 확정이다(원칙 04 — 저장에 여러 단계 금지).
     * 값 표시와 **토큰 비용 추정**을 함께 즉시 갱신해, 늘렸을 때 무엇을 지불하는지 슬라이더를
     * 움직이는 자리에서 보이게 한다(무엇을 얻고 무엇을 내는지 모른 채 고르게 두지 않는다).
     * 눈금·범위는 레이아웃이 아니라 [AiPromptPolicy]가 단일 소스다 — 저장값이 눈금 밖이면
     * Material Slider가 예외로 죽는다.
     */
    private fun setupConsistencySliders() {
        val settings = AiPromptSettings(requireContext())

        binding.usageExamplesSlider.valueFrom = 0f
        binding.usageExamplesSlider.valueTo = AiPromptPolicy.USAGE_EXAMPLES_MAX.toFloat()
        binding.usageExamplesSlider.stepSize = AiPromptPolicy.USAGE_EXAMPLES_STEP.toFloat()
        binding.usageExamplesSlider.value = settings.usageExampleCount.toFloat()
        renderUsageExamples(settings.usageExampleCount)
        binding.usageExamplesSlider.addOnChangeListener { _, value, fromUser ->
            val count = AiPromptPolicy.clampUsageExamples(value.toInt())
            renderUsageExamples(count)
            if (fromUser) settings.usageExampleCount = count
        }

        binding.styleSamplesSlider.valueFrom = 0f
        binding.styleSamplesSlider.valueTo = AiPromptPolicy.STYLE_SAMPLES_MAX.toFloat()
        binding.styleSamplesSlider.stepSize = 1f
        binding.styleSamplesSlider.value = settings.styleSampleCount.toFloat()
        renderStyleSamples(settings.styleSampleCount)
        binding.styleSamplesSlider.addOnChangeListener { _, value, fromUser ->
            val count = AiPromptPolicy.clampStyleSamples(value.toInt())
            renderStyleSamples(count)
            if (fromUser) settings.styleSampleCount = count
        }

        // AI에 함께 보낼 이미지 (A-7) — 적재량 슬라이더의 형제다. 여기서 정하는 것은
        // **기본값**이고, 실행 창의 첨부 줄이 요청마다 바꾼다(자율성 우선).
        binding.attachImagesSlider.valueFrom = 0f
        binding.attachImagesSlider.valueTo = AiPromptPolicy.ATTACH_IMAGES_MAX.toFloat()
        binding.attachImagesSlider.stepSize = 1f
        binding.attachImagesSlider.value = settings.attachImageCount.toFloat()
        renderAttachImages(settings.attachImageCount)
        binding.attachImagesSlider.addOnChangeListener { _, value, fromUser ->
            val count = AiPromptPolicy.clampAttachImages(value.toInt())
            renderAttachImages(count)
            if (fromUser) settings.attachImageCount = count
        }
        binding.attachRepresentativeSwitch.isChecked = settings.attachRepresentativeFirst
        binding.attachRepresentativeSwitch.setOnCheckedChangeListener { _, checked ->
            settings.attachRepresentativeFirst = checked
        }

        // 받아올 근거 강도 — 라디오를 고르는 순간이 곧 확정(저장 단계를 두지 않는다).
        // 저장값 ↔ 버튼 매핑은 여기 한 곳뿐이라 선택지가 늘어도 어긋날 자리가 없다.
        binding.confidenceGroup.check(
            when (settings.minConfidence) {
                CharacterFieldAiSuggester.Confidence.HIGH -> R.id.confidenceHigh
                CharacterFieldAiSuggester.Confidence.MEDIUM -> R.id.confidenceMedium
                else -> R.id.confidenceAll
            }
        )
        binding.confidenceGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.minConfidence = when (checkedId) {
                R.id.confidenceHigh -> CharacterFieldAiSuggester.Confidence.HIGH
                R.id.confidenceMedium -> CharacterFieldAiSuggester.Confidence.MEDIUM
                else -> null
            }
        }

        // AI 이미지 태그 기조 — 자유 서술이라 '저장' 버튼을 두지 않고 포커스가 떠날 때 확정한다
        // (원칙 04: 저장을 위해 버튼 단계를 거치지 않는다). 화면을 떠날 때도 한 번 더 쓴다.
        binding.imageTagPolicyEdit.setText(settings.imageTagPolicy)
        binding.imageTagPolicyEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) settings.imageTagPolicy = binding.imageTagPolicyEdit.text?.toString().orEmpty()
        }
    }

    private fun renderUsageExamples(count: Int) {
        binding.usageExamplesValue.text = countLabel(count)
        binding.usageExamplesCost.text = if (count == 0) {
            getString(R.string.ai_settings_prompt_cost_none)
        } else {
            getString(
                R.string.ai_settings_usage_examples_cost,
                AiPromptPolicy.estimatedUsageTokensPerField(count)
            )
        }
    }

    private fun renderStyleSamples(count: Int) {
        binding.styleSamplesValue.text = countLabel(count)
        binding.styleSamplesCost.text = if (count == 0) {
            getString(R.string.ai_settings_prompt_cost_none)
        } else {
            getString(
                R.string.ai_settings_style_samples_cost,
                AiPromptPolicy.estimatedStyleTokensPerRequest(count)
            )
        }
    }

    /**
     * 첨부 장수 표시 (A-7). 0은 "0장"이 아니라 **보내지 않음**이다 — 다른 두 슬라이더와
     * 같은 태도이며, 그래야 0이 설정 실수가 아니라 선택으로 읽힌다.
     */
    private fun renderAttachImages(count: Int) {
        binding.attachImagesValue.text = if (count == 0) {
            getString(R.string.ai_attach_images_off)
        } else {
            getString(R.string.ai_attach_images_value, count)
        }
        // 대표 스위치는 첨부가 꺼져 있으면 아무 일도 하지 않는다 — 성립하지 않는 조합의
        // 설정은 조작할 수 없게 둔다(R-24). 감추지 않고 흐리게 두는 이유는 켰을 때
        // 무엇이 함께 켜지는지 보이게 하기 위해서다.
        binding.attachRepresentativeSwitch.isEnabled = count > 0
    }

    /** 0은 숫자가 아니라 상태다 — "0개"보다 "보내지 않음"이 무슨 일이 벌어지는지 말한다. */
    private fun countLabel(count: Int): String =
        if (count == 0) getString(R.string.ai_settings_prompt_off)
        else getString(R.string.ai_settings_prompt_count, count)

    private fun refreshList() {
        val configs = providerStore.list()
        adapter.submit(configs, providerStore.activeId())
        binding.emptyText.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        binding.providerList.visibility = if (configs.isEmpty()) View.GONE else View.VISIBLE
        // 활성 프로바이더가 바뀌면 창작도 프로토콜 고지(§6-4)도 그 프로토콜 기준으로 갱신한다
        renderCreativityProtocolNote()
    }

    // ── 추가: 프리셋 선택 ──────────────────────────────────────────────────────

    private fun showPresetPicker() {
        val labels = AiPresets.ALL.map { preset ->
            val name = presetLabel(preset)
            if (preset.hasFreeTier) "$name  ·  ${getString(R.string.ai_preset_free_badge)}" else name
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_settings_add_provider)
            .setItems(labels) { _, which ->
                val preset = AiPresets.ALL[which]
                val template = AiProviderConfig(
                    id = providerStore.newId(),
                    protocol = preset.protocol,
                    displayName = presetLabel(preset),
                    baseUrl = preset.baseUrl,
                    model = preset.defaultModel,
                    presetId = preset.id
                )
                showEditDialog(template, isNew = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun presetLabel(preset: AiPreset): String =
        if (preset.id == AiPresets.CUSTOM_ID) getString(R.string.ai_preset_custom)
        else preset.displayName

    // ── 추가/편집 다이얼로그 ────────────────────────────────────────────────────

    private fun showEditDialog(config: AiProviderConfig, isNew: Boolean) {
        val ctx = requireContext()
        val dialogBinding = DialogAiProviderEditBinding.inflate(layoutInflater)
        val preset = AiPresets.byId(config.presetId)
        var testJob: Job? = null

        dialogBinding.nameInput.setText(config.displayName)
        dialogBinding.modelInput.setText(config.model)
        dialogBinding.baseUrlInput.setText(config.baseUrl)

        // ── 출력 토큰 상한 슬라이더 ──
        // 상한을 아예 두지 않는 선택지는 없다(Anthropic은 max_tokens가 필수). 그래서 "둘 것인가"가
        // 아니라 "무엇을 근거로 둘 것인가"의 문제이고, 슬라이더의 **최대값이 곧 탐지된 모델 상한**이라
        // 자동 탐지와 수동 설정이 따로 놀지 않는다.
        var detectedLimit: Int? = config.detectedOutputLimit

        fun renderMaxTokens() {
            val max = AiTokenPolicy.sliderMax(config.copy(detectedOutputLimit = detectedLimit))
            dialogBinding.maxTokensSlider.valueFrom = AiTokenPolicy.FLOOR.toFloat()
            dialogBinding.maxTokensSlider.valueTo = max.toFloat()
            val current = AiTokenPolicy.snapToStep(
                config.maxOutputTokens ?: AiTokenPolicy.DEFAULT_REQUEST, max
            )
            dialogBinding.maxTokensSlider.value = current.toFloat()
            dialogBinding.maxTokensValue.text = detectedLimit?.let {
                getString(R.string.ai_edit_max_tokens_value_detected, current, it)
            } ?: getString(R.string.ai_edit_max_tokens_value, current)
        }
        renderMaxTokens()
        dialogBinding.maxTokensSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            dialogBinding.maxTokensValue.text = detectedLimit?.let {
                getString(R.string.ai_edit_max_tokens_value_detected, v, it)
            } ?: getString(R.string.ai_edit_max_tokens_value, v)
        }

        /** 모델 목록이 상한을 알려주면(Gemini) 슬라이더 범위를 그 자리에서 좁힌다. */
        fun learnLimitFrom(models: List<AiModelInfo>, model: String) {
            val found = AiProtocolCodec.detectedLimitFor(models, model) ?: return
            if (found == detectedLimit) return
            detectedLimit = found
            renderMaxTokens()
        }

        // 추천 모델 칩 — 직접 타이핑 없이 원탭으로 선택(러프 입력), 세부 수정은 필드에서(원칙 04).
        // 현재 입력값과 일치하는 칩은 체크 상태로 표시해 무엇이 선택됐는지 한눈에 보인다.
        //
        // 목록의 출처는 **키가 있으면 서버**다. 프리셋의 정적 추천값만 쓰던 종전 구현은 제공사가
        // 새 모델을 내놓아도 앱이 모르는 구조라, 사용자에게는 "최신 모델을 추천하지 않는 앱"이었다.
        // 이제 모델 선택 다이얼로그와 같은 실시간 조회를 칩에도 쓰고, 정적 목록은 폴백과
        // 정렬 우선순위로만 남는다(AiModelSuggestions).
        val curatedModels = preset?.suggestedModels.orEmpty()
        var modelFetchJob: Job? = null
        var keyDebounceJob: Job? = null
        var lastFetchedSignature: String? = null

        fun syncModelChips(current: String?) {
            dialogBinding.modelChipGroup.children.forEach { view ->
                (view as? Chip)?.isChecked = view.text?.toString() == current
            }
        }

        fun renderModelChips(models: List<String>, @StringRes labelRes: Int) {
            dialogBinding.modelChipGroup.removeAllViews()
            if (models.isEmpty()) {
                dialogBinding.suggestedModelsLabel.visibility = View.GONE
                dialogBinding.modelChipScroll.visibility = View.GONE
                return
            }
            dialogBinding.suggestedModelsLabel.setText(labelRes)
            dialogBinding.suggestedModelsLabel.visibility = View.VISIBLE
            dialogBinding.modelChipScroll.visibility = View.VISIBLE
            models.forEach { model ->
                dialogBinding.modelChipGroup.addView(
                    Chip(ctx).apply {
                        text = model
                        isCheckable = true
                        setOnClickListener {
                            dialogBinding.modelInput.setText(model)
                            dialogBinding.modelInput.setSelection(model.length)
                            syncModelChips(model) // 같은 칩 재탭 시 체크 해제되는 것 방지
                        }
                    }
                )
            }
            syncModelChips(dialogBinding.modelInput.text?.toString()?.trim())
        }

        /**
         * 키·주소가 갖춰졌으면 서버 목록으로 칩을 갱신한다.
         * 실패해도 조용히 정적 추천값을 유지한다 — 칩은 보조 수단이고, 사유가 필요한 사용자는
         * '모델 찾기'가 오류를 그대로 보여준다(같은 실패를 두 곳에서 두 번 알리지 않는다).
         */
        fun refreshModelChips() {
            val baseUrl = dialogBinding.baseUrlInput.text?.toString()?.trim()?.trimEnd('/').orEmpty()
            val uri = runCatching { Uri.parse(baseUrl) }.getOrNull()
            if (baseUrl.isEmpty() || uri?.scheme != "https" || uri.host.isNullOrBlank()) return
            val key = dialogBinding.apiKeyInput.text?.toString()?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: keyStore.getKey(config.id).orEmpty()
            if (key.length < MIN_KEY_LENGTH_FOR_LOOKUP) return
            // 같은 (주소, 키)로는 다시 묻지 않는다 — 타이핑마다 API를 때리지 않기 위해서.
            val signature = "$baseUrl\u0000$key"
            if (signature == lastFetchedSignature) return
            lastFetchedSignature = signature
            modelFetchJob?.cancel()
            modelFetchJob = viewLifecycleOwner.lifecycleScope.launch {
                val result = aiService.listModels(config.copy(baseUrl = baseUrl), key)
                if (!dialogBinding.root.isAttachedToWindow) return@launch
                if (result is AiModelListResult.Success) {
                    // 목록이 출력 상한을 알려주는 프로토콜(Gemini)이면 여기서 배운다.
                    learnLimitFrom(result.models, dialogBinding.modelInput.text?.toString()?.trim().orEmpty())
                    renderModelChips(
                        AiModelSuggestions.rank(result.ids, curatedModels),
                        R.string.ai_edit_models_from_server_label
                    )
                } else {
                    // 다음 키 수정 때 다시 시도할 수 있게 서명을 비운다.
                    lastFetchedSignature = null
                }
            }
        }

        // 키 입력 전에도 원탭 선택이 가능하도록 정적 추천값을 먼저 그린다.
        renderModelChips(curatedModels, R.string.ai_edit_suggested_models_label)
        dialogBinding.modelInput.doOnTextChanged { text, _, _, _ ->
            syncModelChips(text?.toString()?.trim())
        }
        // 저장된 키가 있는 편집 진입은 즉시, 새로 입력하는 경우는 타이핑이 멎은 뒤에 조회한다.
        refreshModelChips()
        dialogBinding.apiKeyInput.doOnTextChanged { _, _, _, _ ->
            keyDebounceJob?.cancel()
            keyDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(KEY_LOOKUP_DEBOUNCE_MS)
                if (dialogBinding.root.isAttachedToWindow) refreshModelChips()
            }
        }

        // 편집 시 기존 키는 그대로 유지 — 빈칸이면 변경 없음(입력 유실 방지 안내를 헬퍼로).
        val existingHint = if (!isNew) keyStore.keyHint(config.id) else null
        if (existingHint != null) {
            dialogBinding.apiKeyInputLayout.helperText =
                getString(R.string.ai_edit_key_keep_helper, existingHint)
        }

        // 발급 가이드 — 프리셋이 있으면 해당 안내, 없으면(과거 데이터 등) 공통 안내.
        // 본문 속 주소(console.anthropic.com 등)도 눌러서 바로 열리게 링크화한다(원칙 04).
        val guideSpannable = SpannableString(getString(preset?.guideRes ?: R.string.ai_guide_custom))
        LinkifyCompat.addLinks(guideSpannable, PatternsCompat.AUTOLINK_WEB_URL, "https://")
        dialogBinding.guideText.text = guideSpannable
        dialogBinding.guideText.movementMethod = LinkMovementMethod.getInstance()

        val consoleUrl = preset?.consoleUrl.orEmpty()
        dialogBinding.openConsoleButton.visibility =
            if (consoleUrl.isBlank()) View.GONE else View.VISIBLE
        dialogBinding.openConsoleButton.setOnClickListener { openUrl(consoleUrl) }

        // 최신 모델명 문서 — "문서에서 확인하세요" 안내를 실제 이동 경로로 뒷받침한다.
        val modelDocsUrl = preset?.modelDocsUrl.orEmpty()
        dialogBinding.openModelDocsButton.visibility =
            if (modelDocsUrl.isBlank()) View.GONE else View.VISIBLE
        dialogBinding.openModelDocsButton.setOnClickListener { openUrl(modelDocsUrl) }

        // 모델 선택 — 키가 있으면 서버에 실시간으로 물어보고, 없거나 실패하면 정적
        // 추천값으로 폴백한다(러프 선택 → 필드에서 정밀 수정, 이중 경로 원칙 04).
        // 프리셋 추천값이 없는 커스텀 서버에서도 실시간 조회는 그대로 유용하므로 항상 노출.
        dialogBinding.suggestModelButton.setOnClickListener {
            val baseUrl = dialogBinding.baseUrlInput.text?.toString()?.trim()?.trimEnd('/').orEmpty()
            val uri = runCatching { Uri.parse(baseUrl) }.getOrNull()
            if (baseUrl.isEmpty() || uri?.scheme != "https" || uri.host.isNullOrBlank()) {
                Toast.makeText(ctx, R.string.ai_edit_error_https, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val candidate = config.copy(baseUrl = baseUrl)
            val enteredKey = dialogBinding.apiKeyInput.text?.toString()?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: keyStore.getKey(config.id).orEmpty()
            showModelPicker(candidate, enteredKey, preset, dialogBinding.modelInput)
        }

        // 연결 테스트 — 저장 전에 현재 입력값 그대로 검증한다.
        dialogBinding.testButton.setOnClickListener {
            val candidate = readConfig(dialogBinding, config, detectedLimit) ?: return@setOnClickListener
            val enteredKey = dialogBinding.apiKeyInput.text?.toString()?.trim().orEmpty()
            if (enteredKey.isEmpty() && !keyStore.hasKey(config.id)) {
                showTestResult(dialogBinding, success = false, getString(R.string.ai_error_no_key))
                return@setOnClickListener
            }
            dialogBinding.testProgress.visibility = View.VISIBLE
            dialogBinding.testButton.isEnabled = false
            dialogBinding.testResultText.visibility = View.GONE
            testJob?.cancel()
            testJob = viewLifecycleOwner.lifecycleScope.launch {
                val result = aiService.testConnection(candidate, enteredKey.ifEmpty { null })
                // 다이얼로그가 이미 닫혔으면 뷰 갱신 생략(코루틴은 dismiss 시 취소되지만 방어).
                if (!dialogBinding.root.isAttachedToWindow) return@launch
                dialogBinding.testProgress.visibility = View.GONE
                dialogBinding.testButton.isEnabled = true
                when (result) {
                    is AiResult.Success ->
                        showTestResult(dialogBinding, success = true, getString(R.string.ai_test_success))
                    is AiResult.Failure ->
                        showTestResult(dialogBinding, success = false, AiErrorMessages.of(ctx, result))
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isNew) R.string.ai_edit_title_new else R.string.ai_edit_title_edit)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null) // 검증 통과 시에만 닫힘
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setValidatedPositiveButton {
            val candidate = readConfig(dialogBinding, config, detectedLimit) ?: return@setValidatedPositiveButton false
            // R-23 고지 — 학습값이 실제로 있었고 모델·주소 변경으로 초기화됐을 때만.
            // 조용히 바꾸지 않는다(변수 제어): 사용자는 상한이 왜 되돌아갔는지 볼 수 있어야 한다.
            val learnedReset = config.hasLearnedFacts() &&
                (candidate.model != config.model || candidate.baseUrl != config.baseUrl)
            providerStore.save(candidate)
            val enteredKey = dialogBinding.apiKeyInput.text?.toString()?.trim().orEmpty()
            if (enteredKey.isNotEmpty()) keyStore.putKey(candidate.id, enteredKey)
            refreshList()
            if (learnedReset) {
                Toast.makeText(ctx, R.string.ai_edit_learned_reset, Toast.LENGTH_LONG).show()
            }
            true
        }
        dialog.setOnDismissListener { testJob?.cancel() }
        dialog.show()
    }

    // ── 모델 선택(실시간 조회 + 검색 + 정적 추천값 폴백) ────────────────────────

    /**
     * 키가 있으면 [candidate] 서버에 실제 물어봐 지금 쓸 수 있는 모델 목록을 보여준다.
     * 키가 없거나 조회에 실패하면 조용히 정적 추천값으로 폴백하되, 왜 폴백했는지는
     * 안내문으로 투명하게 알린다(변수 제어 — 조용한 실패 금지). 목록은 검색으로
     * 걸러낼 수 있어 항목이 많은 프로바이더(OpenRouter 등)에서도 받쳐준다.
     */
    private fun showModelPicker(
        candidate: AiProviderConfig,
        enteredKey: String,
        preset: AiPreset?,
        modelInputTarget: TextInputEditText
    ) {
        val ctx = requireContext()
        val pickerBinding = DialogAiModelPickerBinding.inflate(layoutInflater)
        var allModels: List<String> = emptyList()
        var fetchJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ai_edit_suggest_models)
            .setView(pickerBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { fetchJob?.cancel() }

        val adapter = ModelPickerAdapter { modelId ->
            modelInputTarget.setText(modelId)
            dialog.dismiss()
        }
        pickerBinding.modelList.layoutManager = LinearLayoutManager(ctx)
        pickerBinding.modelList.adapter = adapter

        fun applyFilter(query: String) {
            val filtered = if (query.isBlank()) allModels
            else allModels.filter { it.contains(query, ignoreCase = true) }
            adapter.submit(filtered)
            pickerBinding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
        pickerBinding.searchInput.doOnTextChanged { text, _, _, _ -> applyFilter(text?.toString().orEmpty()) }

        fun showFallback(note: String) {
            allModels = preset?.suggestedModels.orEmpty()
            pickerBinding.noteText.visibility = View.VISIBLE
            pickerBinding.noteText.text = note
            applyFilter(pickerBinding.searchInput.text?.toString().orEmpty())
        }

        if (enteredKey.isBlank()) {
            // 키가 없으면 인증이 필요한 조회를 시도해 봐야 실패만 하므로 바로 폴백.
            showFallback(getString(R.string.ai_model_picker_need_key))
        } else {
            pickerBinding.progress.visibility = View.VISIBLE
            fetchJob = viewLifecycleOwner.lifecycleScope.launch {
                val result = aiService.listModels(candidate, enteredKey)
                if (!pickerBinding.root.isAttachedToWindow) return@launch
                pickerBinding.progress.visibility = View.GONE
                when (result) {
                    is AiModelListResult.Success -> {
                        allModels = result.ids
                        applyFilter(pickerBinding.searchInput.text?.toString().orEmpty())
                    }
                    is AiModelListResult.Failure ->
                        showFallback(
                            getString(
                                R.string.ai_model_picker_fetch_failed,
                                AiErrorMessages.of(ctx, result.failure)
                            )
                        )
                }
            }
        }
        dialog.show()
    }

    private class ModelPickerAdapter(
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<ModelPickerAdapter.VH>() {

        private var items: List<String> = emptyList()

        fun submit(models: List<String>) {
            items = models
            notifyDataSetChanged() // 목록이 필터 때마다 통째로 갈리므로 diff가 오히려 과함
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAiModelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val model = items[position]
            holder.binding.root.text = model
            holder.binding.root.setOnClickListener { onSelect(model) }
        }

        class VH(val binding: ItemAiModelBinding) : RecyclerView.ViewHolder(binding.root)
    }

    /** 입력 필드 → 설정 객체. 검증 실패 시 해당 필드에 오류를 표시하고 null(다이얼로그 유지). */
    private fun readConfig(
        b: DialogAiProviderEditBinding, base: AiProviderConfig, detectedLimit: Int? = base.detectedOutputLimit
    ): AiProviderConfig? {
        val name = b.nameInput.text?.toString()?.trim().orEmpty()
        val model = b.modelInput.text?.toString()?.trim().orEmpty()
        val baseUrl = b.baseUrlInput.text?.toString()?.trim()?.trimEnd('/').orEmpty()

        b.nameInputLayout.error = null
        b.modelInputLayout.error = null
        b.baseUrlInputLayout.error = null

        var valid = true
        if (name.isEmpty()) {
            b.nameInputLayout.error = getString(R.string.ai_edit_error_required); valid = false
        }
        if (model.isEmpty()) {
            b.modelInputLayout.error = getString(R.string.ai_edit_error_required); valid = false
        }
        val uri = runCatching { Uri.parse(baseUrl) }.getOrNull()
        if (baseUrl.isEmpty() || uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            b.baseUrlInputLayout.error = getString(R.string.ai_edit_error_https); valid = false
        }
        if (!valid) return null
        // R-23: 오류 응답·목록 조회에서 학습한 값은 **그 모델·주소에 한정된 사실**이다.
        // 대상이 바뀌면 같은 저장 시점에 전부 버린다 — 남겨 두면 4k 모델에서 배운 상한이
        // 128k 모델의 슬라이더와 요청값을 사용자가 원인을 볼 수 없는 채로 계속 깎는다.
        // 첫 요청이 다시 배우므로 손실이 없고, 초기화 사실은 저장 시 한 줄로 고지한다.
        val identityChanged = model != base.model || baseUrl != base.baseUrl
        return base.copy(
            displayName = name,
            model = model,
            baseUrl = baseUrl,
            maxOutputTokens = b.maxTokensSlider.value.toInt(),
            detectedOutputLimit = if (identityChanged) null else detectedLimit,
            temperatureUnsupported = if (identityChanged) null else base.temperatureUnsupported,
            // 이미지 거부도 같은 부류다 (A-7) — 비전 지원은 같은 프로토콜 안에서도 모델마다
            // 갈리므로, 안 받던 모델에서 배운 사실을 받는 모델에 물려주면 **첨부가 영영
            // 조용히 빠진다**. 새 값을 여기 빠뜨리면 두 학습값의 규칙이 갈린다(R-23 본문).
            imagesUnsupported = if (identityChanged) null else base.imagesUnsupported
        )
    }

    /** 외부 링크 열기 — 브라우저가 없으면 조용히 죽지 않고 안내한다(변수 제어). */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLogger.error(TAG, "링크 열기 실패: $url", e)
            Toast.makeText(requireContext(), R.string.ai_edit_open_console_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTestResult(b: DialogAiProviderEditBinding, success: Boolean, message: String) {
        b.testResultText.visibility = View.VISIBLE
        b.testResultText.text = message
        b.testResultText.setTextColor(
            if (success) MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorPrimary)
            else MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorError)
        )
    }

    // ── 행 메뉴 ────────────────────────────────────────────────────────────────

    private fun showRowMenu(config: AiProviderConfig, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_EDIT, 0, getString(R.string.edit))
        popup.menu.add(0, MENU_TEST, 1, getString(R.string.ai_edit_test_connection))
        popup.menu.add(0, MENU_DELETE, 2, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> showEditDialog(config, isNew = false)
                MENU_TEST -> testStoredProvider(config)
                MENU_DELETE -> confirmDelete(config)
            }
            true
        }
        popup.show()
    }

    /** 저장된 키로 연결 테스트(행 메뉴). 결과는 다이얼로그로 분명하게 보여준다. */
    private fun testStoredProvider(config: AiProviderConfig) {
        val ctx = requireContext()
        Toast.makeText(ctx, R.string.ai_test_running, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = aiService.testConnection(config)
            if (_binding == null) return@launch
            val (title, message) = when (result) {
                is AiResult.Success ->
                    getString(R.string.ai_test_success_title) to getString(R.string.ai_test_success)
                is AiResult.Failure ->
                    getString(R.string.ai_test_failed_title) to AiErrorMessages.of(ctx, result)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    private fun confirmDelete(config: AiProviderConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.ai_delete_confirm, config.displayName))
            .setPositiveButton(R.string.delete) { _, _ ->
                providerStore.delete(config.id) // 암호화 키도 함께 삭제됨
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        // 포커스를 둔 채 뒤로 가면 focusChange가 오지 않는다 — 여기서 한 번 더 확정하지 않으면
        // 방금 쓴 기조가 조용히 사라진다(입력 유실 금지, R-27과 같은 취지).
        _binding?.let { b ->
            AiPromptSettings(requireContext()).imageTagPolicy =
                b.imageTagPolicyEdit.text?.toString().orEmpty()
        }
        super.onDestroyView()
        _binding = null
    }

    // ── 목록 어댑터 ────────────────────────────────────────────────────────────

    private inner class ProviderAdapter : RecyclerView.Adapter<ProviderAdapter.VH>() {

        private var items: List<AiProviderConfig> = emptyList()
        private var activeId: String? = null

        fun submit(configs: List<AiProviderConfig>, active: String?) {
            items = configs
            activeId = active
            notifyDataSetChanged() // 목록이 소수라 diff 계산이 오히려 과함
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemAiProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemAiProviderBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(config: AiProviderConfig) {
                b.nameText.text = config.displayName
                val host = runCatching { Uri.parse(config.baseUrl).host }.getOrNull() ?: config.baseUrl
                b.detailText.text = "${config.model} · $host"
                val hint = keyStore.keyHint(config.id)
                b.keyStatusText.text =
                    if (hint != null) getString(R.string.ai_key_status_registered, hint)
                    else getString(R.string.ai_key_status_missing)
                b.activeRadio.isChecked = config.id == activeId
                b.root.setOnClickListener {
                    providerStore.setActiveId(config.id)
                    refreshList()
                }
                b.overflowButton.setOnClickListener { showRowMenu(config, it) }
            }
        }
    }

    companion object {
        private const val TAG = "AiSettings"

        /** 이보다 짧은 입력은 키가 아직 다 안 들어온 것으로 보고 조회하지 않는다. */
        private const val MIN_KEY_LENGTH_FOR_LOOKUP = 12

        /** 키 타이핑이 멎은 뒤 조회하기까지의 대기 — 글자마다 API를 때리지 않기 위해서. */
        private const val KEY_LOOKUP_DEBOUNCE_MS = 700L
        private const val MENU_EDIT = 1
        private const val MENU_TEST = 2
        private const val MENU_DELETE = 3
    }
}
