package com.novelcharacter.app.ui.character

import android.os.Bundle
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
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetCharacterFilterBinding
import kotlinx.coroutines.launch

/**
 * 캐릭터 목록 필터 시트 — 태그(다중 선택) + 필드 값(세계관→필드→값, exact/contains)을 한 시트에.
 * `SearchFilterBottomSheet`의 필드-값 흐름을 답습하되 중첩 시트 없이 태그까지 한 곳에서 처리한다.
 * 적용 시 태그 집합을 통째로 반영하고, 필드+값이 선택돼 있으면 필드 필터 하나를 추가한다.
 */
class CharacterFilterBottomSheet : BottomSheetDialogFragment() {

    var currentTags: Set<String> = emptySet()
    var loadAllTags: (suspend () -> List<String>)? = null
    var loadUniverses: (suspend () -> List<Universe>)? = null
    var loadFields: (suspend (Long) -> List<FieldDefinition>)? = null
    var loadFieldValues: (suspend (Long) -> List<String>)? = null
    var onApply: ((tags: Set<String>, fieldFilter: FieldFilter?) -> Unit)? = null

    private var _binding: BottomSheetCharacterFilterBinding? = null
    private val binding get() = _binding!!

    private var universes: List<Universe> = emptyList()
    private var fields: List<FieldDefinition> = emptyList()
    private var fieldValues: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCharacterFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTags()
        setupUniverseSpinner()
        binding.btnApplyFilter.setOnClickListener { apply() }
    }

    private fun setupTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = loadAllTags?.invoke() ?: emptyList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (tags.isEmpty()) {
                binding.noTagsText.visibility = View.VISIBLE
                return@launch
            }
            binding.noTagsText.visibility = View.GONE
            for (tag in tags) {
                val chip = Chip(ctx).apply {
                    text = tag
                    isCheckable = true
                    isChecked = tag in currentTags
                    textSize = 13f
                }
                binding.tagChipGroup.addView(chip)
            }
        }
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
            binding.spinnerUniverse.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, universes.map { it.name }
            )
            binding.spinnerUniverse.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    universes.getOrNull(position)?.let { loadFieldsForUniverse(it.id) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            loadFieldsForUniverse(universes[0].id)
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
            binding.spinnerField.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, fields.map { it.name }
            )
            binding.spinnerField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    fields.getOrNull(position)?.let { loadValuesForField(it.id) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            loadValuesForField(fields[0].id)
        }
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

    private fun apply() {
        // 태그: 시트의 선택 상태를 통째로 반영
        val selectedTags = mutableSetOf<String>()
        for (i in 0 until binding.tagChipGroup.childCount) {
            (binding.tagChipGroup.getChildAt(i) as? Chip)?.let { if (it.isChecked) selectedTags.add(it.text.toString()) }
        }
        // 필드 값: 필드+값이 선택돼 있으면 필터 하나 생성
        val field = fields.getOrNull(binding.spinnerField.selectedItemPosition)
        val selectedValues = mutableListOf<String>()
        for (i in 0 until binding.valueChipGroup.childCount) {
            (binding.valueChipGroup.getChildAt(i) as? Chip)?.let { if (it.isChecked) selectedValues.add(it.text.toString()) }
        }
        val filter = if (field != null && selectedValues.isNotEmpty()) {
            val matchMode = if (binding.radioContains.isChecked) "contains" else "exact"
            FieldFilter(field.id, field.name, selectedValues, matchMode)
        } else null

        onApply?.invoke(selectedTags, filter)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "CharacterFilterBottomSheet" }
}
