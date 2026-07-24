package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetBatchFieldValueBinding
import com.novelcharacter.app.util.GsonTypes
import kotlinx.coroutines.launch

class BatchFieldValueBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBatchFieldValueBinding? = null
    private val binding get() = _binding!!
    private val batchViewModel: BatchEditViewModel by activityViewModels()
    private val gson = Gson()

    private val isClearMode: Boolean
        get() = arguments?.getBoolean(ARG_CLEAR_MODE) ?: false

    private var universes: List<Universe> = emptyList()
    private var fields: List<FieldDefinition> = emptyList()
    private var selectedField: FieldDefinition? = null
    private var fieldLoadJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBatchFieldValueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val count = batchViewModel.selectedCount

        binding.titleText.text = if (isClearMode) {
            getString(R.string.batch_field_title_clear)
        } else {
            getString(R.string.batch_field_title_set)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val universeIds = batchViewModel.getUniverseIdsForSelection()
            if (_binding == null) return@launch

            if (universeIds.size > 1) {
                binding.multiUniverseInfo.visibility = View.VISIBLE
            }

            // 세계관 로드
            val app = batchViewModel.getApplication<NovelCharacterApp>()
            universes = universeIds.mapNotNull { app.universeRepository.getUniverseById(it) }
            if (_binding == null) return@launch

            if (universes.isEmpty()) {
                binding.btnConfirm.isEnabled = false
                return@launch
            }

            val universeNames = universes.map { it.name }
            val uAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, universeNames)
            uAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.universeSpinner.adapter = uAdapter

            binding.universeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    loadFieldsForUniverse(universes[position].id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        binding.btnConfirm.setOnClickListener {
            val field = selectedField ?: return@setOnClickListener
            if (isClearMode) {
                batchViewModel.clearFieldValue(field.id)
                dismiss()
                return@setOnClickListener
            }
            val value = getInputValue()
            if (value.isBlank()) {
                dismiss()
                return@setOnClickListener
            }
            confirmWithRestrictedGuard(field, value)
        }
    }

    /** restricted 필드는 적용 전 검증 — 차단 대신 사유 + 교정 경로 (추가하고 적용 / 입력 수정) */
    private fun confirmWithRestrictedGuard(field: FieldDefinition, value: String) {
        val app = requireActivity().application as com.novelcharacter.app.NovelCharacterApp
        viewLifecycleOwner.lifecycleScope.launch {
            val isRestricted = com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(field) &&
                com.novelcharacter.app.data.model.FieldValueLibraryConfig.fromConfig(field.config).isRestricted
            val violations = if (isRestricted) {
                com.novelcharacter.app.data.repository.FieldValueLibraryRepository.validateRestricted(
                    field, value, app.fieldValueLibraryRepository.entriesForField(field.id))
            } else emptyList()
            if (violations.isEmpty()) {
                batchViewModel.setFieldValue(field.id, value)
                dismiss()
                return@launch
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.field_library_restricted_violation_title)
                .setMessage(getString(R.string.field_library_restricted_violation_line,
                    field.name, violations.joinToString(", ")))
                .setPositiveButton(R.string.field_library_restricted_add_and_save) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        violations.forEach { app.fieldValueLibraryRepository.addEntry(field.id, it) }
                        batchViewModel.setFieldValue(field.id, value)
                        dismiss()
                    }
                }
                .setNegativeButton(R.string.field_library_restricted_edit_input, null)
                .show()
        }
    }

    private fun loadFieldsForUniverse(universeId: Long) {
        fieldLoadJob?.cancel() // 이전 로드 취소 → 세계관 빠른 전환 시 경합 방지
        fieldLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val allFields = batchViewModel.getFieldsByUniverseList(universeId)
            // CALCULATED 필드는 제외 (자동 계산 → 수동 설정 불가)
            fields = allFields.filter { it.type != "CALCULATED" }
            if (_binding == null) return@launch

            val fieldNames = fields.map { it.name }
            val fAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fieldNames)
            fAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.fieldSpinner.adapter = fAdapter

            binding.fieldSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedField = fields.getOrNull(position)
                    setupValueInput(selectedField)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun setupValueInput(field: FieldDefinition?) {
        if (field == null) return
        val count = batchViewModel.selectedCount

        if (isClearMode) {
            binding.valueInputLayout.visibility = View.GONE
            binding.valueSpinner.visibility = View.GONE
            binding.btnConfirm.text = getString(R.string.batch_field_clear_confirm, count, field.name)
            binding.btnConfirm.isEnabled = true
            return
        }

        when (field.type) {
            "SELECT" -> {
                binding.valueInputLayout.visibility = View.GONE
                binding.valueSpinner.visibility = View.VISIBLE
                val options = parseSelectOptions(field.config)
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.valueSpinner.adapter = adapter
                binding.btnConfirm.isEnabled = options.isNotEmpty()
            }
            "GRADE" -> {
                binding.valueInputLayout.visibility = View.GONE
                binding.valueSpinner.visibility = View.VISIBLE
                val grades = parseGradeOptions(field.config)
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, grades)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.valueSpinner.adapter = adapter
                binding.btnConfirm.isEnabled = grades.isNotEmpty()
            }
            "NUMBER" -> {
                binding.valueInputLayout.visibility = View.VISIBLE
                binding.valueSpinner.visibility = View.GONE
                binding.valueInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                binding.valueInputLayout.hint = field.name
                binding.btnConfirm.isEnabled = true
            }
            else -> {
                // TEXT, MULTI_TEXT, BODY_SIZE
                binding.valueInputLayout.visibility = View.VISIBLE
                binding.valueSpinner.visibility = View.GONE
                binding.valueInput.inputType = InputType.TYPE_CLASS_TEXT
                binding.valueInputLayout.hint = field.name
                binding.btnConfirm.isEnabled = true
                attachLibrarySuggestions(field)
            }
        }
        binding.btnConfirm.text = getString(R.string.batch_field_set_confirm, count, field.name)
    }

    /** 값 라이브러리 제안 장착 — 일괄 편집 TEXT 입력의 자동완성 공백 해소 (검토 §5.5) */
    private fun attachLibrarySuggestions(field: FieldDefinition) {
        binding.valueInput.setAdapter(null)
        if (!com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(field)) return
        if (!com.novelcharacter.app.data.model.FieldValueLibraryConfig.fromConfig(field.config).isSuggestEnabled) return
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = (requireActivity().application as com.novelcharacter.app.NovelCharacterApp)
                .fieldValueLibraryRepository.entriesForField(field.id)
            if (_binding == null || selectedField?.id != field.id) return@launch
            if (entries.isNotEmpty()) {
                binding.valueInput.threshold = 1
                binding.valueInput.setAdapter(
                    com.novelcharacter.app.ui.fieldlibrary.LibrarySuggestionAdapter(requireContext(), entries))
            }
        }
    }

    private fun getInputValue(): String {
        val field = selectedField ?: return ""
        return when (field.type) {
            "SELECT", "GRADE" -> binding.valueSpinner.selectedItem?.toString() ?: ""
            else -> binding.valueInput.text?.toString()?.trim() ?: ""
        }
    }

    private fun parseSelectOptions(configJson: String): List<String> {
        return try {
            val config: Map<String, Any> = gson.fromJson(configJson, GsonTypes.STRING_ANY_MAP)
            @Suppress("UNCHECKED_CAST")
            (config["options"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun parseGradeOptions(configJson: String): List<String> {
        val defaultGrades = listOf("C", "B", "A", "S")
        return try {
            val config: Map<String, Any> = gson.fromJson(configJson, GsonTypes.STRING_ANY_MAP)
            @Suppress("UNCHECKED_CAST")
            val gradesMap = config["grades"] as? Map<String, Any>
            val gradeKeys = if (gradesMap != null) {
                gradesMap.entries
                    .sortedBy { (it.value as? Number)?.toDouble() ?: 0.0 }
                    .map { it.key }
            } else {
                defaultGrades
            }
            val allowNegative = config["allowNegative"] as? Boolean ?: false
            if (allowNegative) {
                gradeKeys.flatMap { listOf("-$it", it) }
            } else gradeKeys
        } catch (_: Exception) { defaultGrades }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BatchFieldValueBottomSheet"
        private const val ARG_CLEAR_MODE = "clearMode"

        fun newInstance(isClearMode: Boolean): BatchFieldValueBottomSheet {
            return BatchFieldValueBottomSheet().apply {
                arguments = Bundle().apply { putBoolean(ARG_CLEAR_MODE, isClearMode) }
            }
        }
    }
}
