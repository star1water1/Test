package com.novelcharacter.app.ui.search

import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetSearchFilterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 검색의 필드 필터 시트.
 *
 * ## 꽂는 배선이 없다 (R-65)
 *
 * 종전에는 호스트가 람다 넷(재료 로더 셋 + 적용 하나)을 인스턴스에 꽂았다. 회전하면
 * FragmentManager가 이 시트를 **기본 생성자로 다시 세우고** 호스트는 다시 꽂지 않았다 —
 * 시트는 빈 스피너로 멀쩡히 떠 있고 [적용]은 아무 데도 닿지 않는다. **말없는 무동작이라
 * 고장과 구분되지 않는다.** 되살릴 배선을 다시 세우는 대신 **배선 자체를 없앴다**:
 * 재료도 처분도 [GlobalSearchViewModel]에 있고, 이 시트는 `childFragmentManager`로 뜨므로
 * 부모의 ViewModelStore가 곧 이 시트의 것이다(호스트와 **같은 인스턴스**를 든다).
 *
 * ## 고르던 것도 지킨다 (R-41)
 *
 * 재료는 비동기라 회전 뒤 스피너·칩이 다시 서기 전까지는 고른 것을 되살릴 자리가 없다.
 * 그래서 **고른 세계관·필드·값**을 [onSaveInstanceState]로 들고 있다가 각 목록이 선 뒤에
 * 되살린다. 회전이 지키는 것은 저장 가능한 한 벌이 아니라 *고르던 것*이다.
 */
class SearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSearchFilterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GlobalSearchViewModel by viewModels(ownerProducer = { requireParentFragment() })

    private var universes: List<Universe> = emptyList()
    private var fields: List<FieldDefinition> = emptyList()
    private var fieldValues: List<String> = emptyList()

    /** 조회 회차 — 늦게 온 답이 지금 고른 것을 덮지 않게 앞 회차를 끊는다(쌍둥이 시트와 같은 규약). */
    private var fieldLoadJob: Job? = null
    private var valueLoadJob: Job? = null

    /** 회전 전에 고르던 것 — 목록이 다시 선 뒤에 이 값으로 되살린다. `null`이면 되살릴 것이 없다. */
    private var pendingUniverseId: Long? = null
    private var pendingFieldId: Long? = null
    private var pendingValues: Set<String> = emptySet()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.let { state ->
            if (state.containsKey(STATE_UNIVERSE_ID)) pendingUniverseId = state.getLong(STATE_UNIVERSE_ID)
            if (state.containsKey(STATE_FIELD_ID)) pendingFieldId = state.getLong(STATE_FIELD_ID)
            pendingValues = state.getStringArray(STATE_VALUES)?.toSet() ?: emptySet()
        }
        setupUniverseSpinner()
        setupFieldSpinner()
        setupApplyButton()
    }

    private fun setupUniverseSpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            universes = viewModel.getAllUniverses()
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
                    // 되살릴 자리를 **떠나는 순간** 들고 있던 선택을 버린다 — 안 버리면 다른
                    // 세계관의 목록에 옛 선택이 되살아난다(같은 이름의 값이 겹칠 수 있다).
                    if (universe.id != pendingUniverseId) {
                        pendingUniverseId = null
                        pendingFieldId = null
                        pendingValues = emptySet()
                    }
                    loadFieldsForUniverse(universe.id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // 회전 전에 고르던 세계관이 있으면 그것을, 없으면 첫 번째를 편다.
            // (스피너의 첫 선택 통지도 같은 자리를 부르므로 되살리기는 **여러 번 불려도 같은 결과**여야 한다.)
            val restoreIndex = pendingUniverseId?.let { id -> universes.indexOfFirst { it.id == id } } ?: -1
            if (restoreIndex > 0) binding.spinnerUniverse.setSelection(restoreIndex)
            loadFieldsForUniverse(universes[restoreIndex.coerceAtLeast(0)].id)
        }
    }

    /**
     * **앞 회차를 끊고 시작한다** — 세계관을 빠르게 갈아 대면 늦게 온 조회가 뒤에 도착해
     * 지금 고른 세계관의 필드 목록을 덮고, 그 목록으로 값 조회가 다시 걸린다.
     * 딸린 값 로드까지 함께 끊는다(쌍둥이 시트 `CharacterListControlsBottomSheet`와 같은 모양).
     */
    private fun loadFieldsForUniverse(universeId: Long) {
        fieldLoadJob?.cancel()
        valueLoadJob?.cancel()
        fieldLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            fields = viewModel.getFieldDefinitions(universeId)
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
                    if (field.id != pendingFieldId) {
                        pendingFieldId = null
                        pendingValues = emptySet()
                    }
                    loadValuesForField(field.id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // 세계관과 같은 결 — 고르던 필드가 남아 있으면 그것을 편다.
            val restoreIndex = pendingFieldId?.let { id -> fields.indexOfFirst { it.id == id } } ?: -1
            if (restoreIndex > 0) binding.spinnerField.setSelection(restoreIndex)
            loadValuesForField(fields[restoreIndex.coerceAtLeast(0)].id)
        }
    }

    private fun setupFieldSpinner() {
        // Initial setup handled by universe selection
    }

    /**
     * **앞 회차를 끊고 시작한다** — 필드를 빠르게 갈아 대면 늦게 온 조회가 칩을 덮어
     * **다른 필드의 값이 붙은 필터**가 저장된다. 쌍둥이 시트가 같은 판에서 이미 막아 둔 자리다.
     */
    private fun loadValuesForField(fieldDefId: Long) {
        valueLoadJob?.cancel()
        valueLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            fieldValues = viewModel.getFieldValues(fieldDefId)
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
                    // 회전 전에 고른 값은 그대로 골라진 채로 선다(칩은 동적이라 뷰 상태 저장이 못 든다).
                    isChecked = value in pendingValues
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

            // 키를 함께 싣는다(B-11) — 전역 뷰라 세계관 A에서 고른 '성별'이 B의 '성별'에도 걸려야 한다.
            val filter = FieldFilter(
                fieldId = field.id,
                fieldName = field.name,
                values = selectedValues,
                matchMode = matchMode,
                fieldKey = field.key
            )

            viewModel.addFieldFilter(filter)
            dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val b = _binding ?: return
        universes.getOrNull(b.spinnerUniverse.selectedItemPosition)?.let {
            outState.putLong(STATE_UNIVERSE_ID, it.id)
        }
        fields.getOrNull(b.spinnerField.selectedItemPosition)?.let {
            outState.putLong(STATE_FIELD_ID, it.id)
        }
        val checked = (0 until b.valueChipGroup.childCount)
            .mapNotNull { b.valueChipGroup.getChildAt(it) as? Chip }
            .filter { it.isChecked }
            .map { it.text.toString() }
        outState.putStringArray(STATE_VALUES, checked.toTypedArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SearchFilterBottomSheet"
        private const val STATE_UNIVERSE_ID = "state_universe_id"
        private const val STATE_FIELD_ID = "state_field_id"
        private const val STATE_VALUES = "state_values"
    }
}
