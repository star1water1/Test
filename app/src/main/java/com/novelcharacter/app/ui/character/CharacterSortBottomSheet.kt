package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.databinding.BottomSheetCharacterSortBinding
import kotlinx.coroutines.launch

/**
 * 캐릭터 목록 정렬 기준 선택 시트. 정렬 방향(오름/내림)은 목록 바의 방향 버튼이 담당하며
 * 여기서는 기준(기본/이름/생성일/최근/필드)과 대상 필드·BODY_SIZE 파트만 고른다.
 */
class CharacterSortBottomSheet : BottomSheetDialogFragment() {

    var currentSort: CharacterSort = CharacterSort()
    var loadSortableFields: (suspend () -> List<SortableField>)? = null
    var onSortSelected: ((CharacterSort) -> Unit)? = null

    private var _binding: BottomSheetCharacterSortBinding? = null
    private val binding get() = _binding!!
    private var fields: List<SortableField> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCharacterSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 현재 정렬 종류로 라디오 초기화
        binding.sortKindGroup.check(when (currentSort.kind) {
            CharacterListPreset.SORT_NAME -> binding.rbName.id
            CharacterListPreset.SORT_CREATED -> binding.rbCreated.id
            CharacterListPreset.SORT_RECENT -> binding.rbRecent.id
            CharacterListPreset.SORT_FIELD -> binding.rbField.id
            else -> binding.rbManual.id
        })

        binding.sortKindGroup.setOnCheckedChangeListener { _, _ -> updateFieldVisibility() }
        binding.spinnerSortField.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = updateBodyPartVisibility()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnApplySort.setOnClickListener { apply() }

        loadFields()
    }

    private fun loadFields() {
        viewLifecycleOwner.lifecycleScope.launch {
            fields = loadSortableFields?.invoke() ?: emptyList()
            if (!isAdded || _binding == null) return@launch
            val ctx = context ?: return@launch
            if (fields.isEmpty()) {
                binding.rbField.isEnabled = false
                binding.rbField.text = getString(com.novelcharacter.app.R.string.sort_field_none)
            } else {
                binding.spinnerSortField.adapter = ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item, fields.map { it.name }
                )
                // 현재 정렬이 필드면 해당 필드 선택
                val idx = fields.indexOfFirst { it.key == currentSort.fieldKey }
                if (idx >= 0) binding.spinnerSortField.setSelection(idx)
            }
            updateFieldVisibility()
        }
    }

    private fun updateFieldVisibility() {
        val isField = binding.sortKindGroup.checkedRadioButtonId == binding.rbField.id
        val vis = if (isField && fields.isNotEmpty()) View.VISIBLE else View.GONE
        binding.sortFieldLabel.visibility = vis
        binding.spinnerSortField.visibility = vis
        updateBodyPartVisibility()
    }

    private fun updateBodyPartVisibility() {
        val isField = binding.sortKindGroup.checkedRadioButtonId == binding.rbField.id
        val field = if (isField) fields.getOrNull(binding.spinnerSortField.selectedItemPosition) else null
        if (field != null && field.type == "BODY_SIZE" && field.bodySizePartLabels.isNotEmpty()) {
            val ctx = context ?: return
            binding.spinnerBodyPart.adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item, field.bodySizePartLabels
            )
            currentSort.bodySizePartIndex?.let { if (it < field.bodySizePartLabels.size) binding.spinnerBodyPart.setSelection(it) }
            binding.sortBodyPartLabel.visibility = View.VISIBLE
            binding.spinnerBodyPart.visibility = View.VISIBLE
        } else {
            binding.sortBodyPartLabel.visibility = View.GONE
            binding.spinnerBodyPart.visibility = View.GONE
        }
    }

    private fun apply() {
        val kind = when (binding.sortKindGroup.checkedRadioButtonId) {
            binding.rbName.id -> CharacterListPreset.SORT_NAME
            binding.rbCreated.id -> CharacterListPreset.SORT_CREATED
            binding.rbRecent.id -> CharacterListPreset.SORT_RECENT
            binding.rbField.id -> if (fields.isEmpty()) CharacterListPreset.SORT_MANUAL else CharacterListPreset.SORT_FIELD
            else -> CharacterListPreset.SORT_MANUAL
        }
        val field = if (kind == CharacterListPreset.SORT_FIELD) fields.getOrNull(binding.spinnerSortField.selectedItemPosition) else null
        val partIndex = if (field?.type == "BODY_SIZE") binding.spinnerBodyPart.selectedItemPosition.takeIf { it >= 0 } else null
        // 기준을 새로 고르면 합리적 기본 방향 지정(이후 목록 바에서 반전 가능)
        val ascending = defaultAscending(kind, field?.key)
        onSortSelected?.invoke(CharacterSort(kind, field?.key, ascending, partIndex))
        dismiss()
    }

    /** 기준 유지 시 현재 방향 보존, 바뀌면 합리적 기본값. */
    private fun defaultAscending(kind: String, fieldKey: String?): Boolean {
        if (kind == currentSort.kind && fieldKey == currentSort.fieldKey) return currentSort.ascending
        return when (kind) {
            CharacterListPreset.SORT_NAME -> true          // 가나다
            CharacterListPreset.SORT_CREATED -> false      // 최신순
            CharacterListPreset.SORT_RECENT -> false       // 최근 수정순
            CharacterListPreset.SORT_FIELD -> false        // 높은 값 먼저
            else -> true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "CharacterSortBottomSheet" }
}
