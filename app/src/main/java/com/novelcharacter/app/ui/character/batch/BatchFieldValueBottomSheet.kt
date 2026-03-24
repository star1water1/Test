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

        val count = batchViewModel.selectedCount.value ?: 0

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
            } else {
                val value = getInputValue()
                if (value.isNotBlank()) {
                    batchViewModel.setFieldValue(field.id, value)
                }
            }
            dismiss()
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
        val count = batchViewModel.selectedCount.value ?: 0

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
            }
        }
        binding.btnConfirm.text = getString(R.string.batch_field_set_confirm, count, field.name)
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
        val baseGrades = listOf("C", "B", "A", "S")
        return try {
            val config: Map<String, Any> = gson.fromJson(configJson, GsonTypes.STRING_ANY_MAP)
            val allowNegative = config["allowNegative"] as? Boolean ?: false
            if (allowNegative) {
                baseGrades.flatMap { listOf("-$it", it) }
            } else baseGrades
        } catch (_: Exception) { baseGrades }
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
