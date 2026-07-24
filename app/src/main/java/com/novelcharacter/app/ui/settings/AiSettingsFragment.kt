package com.novelcharacter.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doOnTextChanged
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiErrorMessages
import com.novelcharacter.app.ai.AiKeyStore
import com.novelcharacter.app.ai.AiModelListResult
import com.novelcharacter.app.ai.AiPreset
import com.novelcharacter.app.ai.AiPresets
import com.novelcharacter.app.ai.AiProviderConfig
import com.novelcharacter.app.ai.AiProviderStore
import com.novelcharacter.app.ai.AiResult
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.databinding.DialogAiModelPickerBinding
import com.novelcharacter.app.databinding.DialogAiProviderEditBinding
import com.novelcharacter.app.databinding.FragmentAiSettingsBinding
import com.novelcharacter.app.databinding.ItemAiModelBinding
import com.novelcharacter.app.databinding.ItemAiProviderBinding
import com.novelcharacter.app.util.AppLogger
import com.novelcharacter.app.util.setValidatedPositiveButton
import kotlinx.coroutines.Job
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

        refreshList()
    }

    private fun refreshList() {
        val configs = providerStore.list()
        adapter.submit(configs, providerStore.activeId())
        binding.emptyText.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        binding.providerList.visibility = if (configs.isEmpty()) View.GONE else View.VISIBLE
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

        // 편집 시 기존 키는 그대로 유지 — 빈칸이면 변경 없음(입력 유실 방지 안내를 헬퍼로).
        val existingHint = if (!isNew) keyStore.keyHint(config.id) else null
        if (existingHint != null) {
            dialogBinding.apiKeyInputLayout.helperText =
                getString(R.string.ai_edit_key_keep_helper, existingHint)
        }

        // 발급 가이드 — 프리셋이 있으면 해당 안내, 없으면(과거 데이터 등) 공통 안내.
        dialogBinding.guideText.text =
            getString(preset?.guideRes ?: R.string.ai_guide_custom)
        val consoleUrl = preset?.consoleUrl.orEmpty()
        dialogBinding.openConsoleButton.visibility =
            if (consoleUrl.isBlank()) View.GONE else View.VISIBLE
        dialogBinding.openConsoleButton.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(consoleUrl)))
            } catch (e: Exception) {
                AppLogger.error(TAG, "콘솔 페이지 열기 실패", e)
                Toast.makeText(ctx, R.string.ai_edit_open_console_failed, Toast.LENGTH_SHORT).show()
            }
        }

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
            val candidate = readConfig(dialogBinding, config) ?: return@setOnClickListener
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
            val candidate = readConfig(dialogBinding, config) ?: return@setValidatedPositiveButton false
            providerStore.save(candidate)
            val enteredKey = dialogBinding.apiKeyInput.text?.toString()?.trim().orEmpty()
            if (enteredKey.isNotEmpty()) keyStore.putKey(candidate.id, enteredKey)
            refreshList()
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
                        allModels = result.models
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
        b: DialogAiProviderEditBinding, base: AiProviderConfig
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
        return base.copy(displayName = name, model = model, baseUrl = baseUrl)
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
        private const val MENU_EDIT = 1
        private const val MENU_TEST = 2
        private const val MENU_DELETE = 3
    }
}
