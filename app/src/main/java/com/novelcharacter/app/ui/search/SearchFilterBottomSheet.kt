package com.novelcharacter.app.ui.search

import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetSearchFilterBinding
import kotlinx.coroutines.launch

class SearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSearchFilterBinding? = null
    private val binding get() = _binding!!

    private var universes: List<Universe> = emptyList()
    private var fields: List<FieldDefinition> = emptyList()
    private var fieldValues: List<String> = emptyList()

    var onFilterApplied: ((FieldFilter) -> Unit)? = null
    var loadUniverses: (suspend () -> List<Universe>)? = null
    var loadFields: (suspend (Long) -> List<FieldDefinition>)? = null
    var loadFieldValues: (suspend (Long) -> List<String>)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUniverseSpinner()
        setupFieldSpinner()
        setupApplyButton()
    }

    private fun setupUniverseSpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            universes = loadUniverses?.invoke() ?: emptyList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch

            if (universes.isEmpty()) {
                binding.spinnerUniverse.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.search_filter_no_universes))
                )
                return@launch
            }

            val names = universes.map { it.name }
            binding.spinnerUniverse.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, names
            )

            binding.spinnerUniverse.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val universe = universes.getOrNull(position) ?: return
                    loadFieldsForUniverse(universe.id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // 첫 번째 세계관의 필드 로드
            if (universes.isNotEmpty()) {
                loadFieldsForUniverse(universes[0].id)
            }
        }
    }

    private fun loadFieldsForUniverse(universeId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            fields = loadFields?.invoke(universeId) ?: emptyList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch

            if (fields.isEmpty()) {
                binding.spinnerField.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.search_filter_no_fields))
                )
                binding.valueChipGroup.removeAllViews()
                return@launch
            }

            val names = fields.map { it.name }
            binding.spinnerField.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, names
            )

            binding.spinnerField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    val field = fields.getOrNull(position) ?: return
                    loadValuesForField(field.id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // 첫 번째 필드의 값 로드
            if (fields.isNotEmpty()) {
                loadValuesForField(fields[0].id)
            }
        }
    }

    private fun setupFieldSpinner() {
        // Initial setup handled by universe selection
    }

    private fun loadValuesForField(fieldDefId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            fieldValues = loadFieldValues?.invoke(fieldDefId) ?: emptyList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch

            binding.valueChipGroup.removeAllViews()

            if (fieldValues.isEmpty()) {
                binding.emptyValuesText.visibility = View.VISIBLE
                return@launch
            }
            binding.emptyValuesText.visibility = View.GONE

            for (value in fieldValues) {
                val chip = Chip(ctx).apply {
                    text = value
                    isCheckable = true
                    isChecked = false
                    textSize = 13f
                }
                binding.valueChipGroup.addView(chip)
            }
        }
    }

    private fun setupApplyButton() {
        binding.btnApplyFilter.setOnClickListener {
            val fieldPosition = binding.spinnerField.selectedItemPosition
            val field = fields.getOrNull(fieldPosition) ?: return@setOnClickListener

            val selectedValues = mutableListOf<String>()
            for (i in 0 until binding.valueChipGroup.childCount) {
                val chip = binding.valueChipGroup.getChildAt(i) as? Chip
                if (chip?.isChecked == true) {
                    selectedValues.add(chip.text.toString())
                }
            }

            if (selectedValues.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.search_filter_select_values), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val matchMode = if (binding.radioContains.isChecked) "contains" else "exact"

            val filter = FieldFilter(
                fieldId = field.id,
                fieldName = field.name,
                values = selectedValues,
                matchMode = matchMode
            )

            onFilterApplied?.invoke(filter)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SearchFilterBottomSheet"
    }
}
