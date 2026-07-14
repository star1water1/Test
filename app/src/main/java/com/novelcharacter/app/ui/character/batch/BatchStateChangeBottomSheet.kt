package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.BottomSheetBatchStateChangeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 선택 캐릭터들에 동일한 상태변화(시점 + 필드 + 값)를 한 번에 기록하는 시트.
 * 개별 상태변화 다이얼로그([com.novelcharacter.app.ui.character.StateChangeHelper])와 같은 입력 구성이되,
 * 필드는 선택 세계관 기준으로 채운다. 특수키(출생/사망/생존)는 세계관 무관 전체 적용(universeId=null),
 * 커스텀 필드는 그 세계관 캐릭터에만 적용(universeId 지정) — 타 세계관엔 없는 필드라 무의미한 기록을 막는다.
 */
class BatchStateChangeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBatchStateChangeBinding? = null
    private val binding get() = _binding!!
    private val batchViewModel: BatchEditViewModel by activityViewModels()

    /** 필드 선택지. universeId==null이면 특수키(전체 적용), 아니면 그 세계관 커스텀 필드(스코프 적용). */
    private data class FieldOpt(val key: String, val name: String, val universeId: Long?)

    private var universes: List<Universe> = emptyList()
    private var fieldOptions: List<FieldOpt> = emptyList()
    private var fieldLoadJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBatchStateChangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            val universeIds = batchViewModel.getUniverseIdsForSelection()
            if (_binding == null) return@launch
            val app = batchViewModel.getApplication<NovelCharacterApp>()
            universes = universeIds.mapNotNull { app.universeRepository.getUniverseById(it) }
            if (_binding == null) return@launch

            if (universes.isEmpty()) {
                // 소속 세계관이 없어도 특수키(출생/사망/생존)는 기록 가능 — 세계관 스피너를 숨기고 특수키만 노출.
                binding.universeLabel.visibility = View.GONE
                binding.universeSpinner.visibility = View.GONE
                buildFieldOptions(null)
            } else {
                if (universes.size > 1) binding.multiUniverseInfo.visibility = View.VISIBLE
                val names = universes.map { it.name }
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.universeSpinner.adapter = adapter
                binding.universeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        loadFields(universes[position])
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }

        binding.btnConfirm.setOnClickListener { confirm() }
    }

    private fun loadFields(universe: Universe) {
        fieldLoadJob?.cancel()  // 세계관 빠른 전환 시 경합 방지
        fieldLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val fields = batchViewModel.getFieldsByUniverseList(universe.id).filter { it.type != "CALCULATED" }
            if (_binding == null) return@launch
            buildFieldOptions(universe.id, fields)
        }
    }

    private fun buildFieldOptions(universeId: Long?, fields: List<FieldDefinition> = emptyList()) {
        val opts = mutableListOf<FieldOpt>()
        // 특수키 — 세계관 무관, 전체 적용
        opts.add(FieldOpt(CharacterStateChange.KEY_BIRTH, getString(R.string.birth), null))
        opts.add(FieldOpt(CharacterStateChange.KEY_DEATH, getString(R.string.death), null))
        opts.add(FieldOpt(CharacterStateChange.KEY_ALIVE, getString(R.string.alive_status), null))
        // 커스텀 필드 — 해당 세계관 캐릭터에만
        for (f in fields) opts.add(FieldOpt(f.key, f.name, universeId))
        fieldOptions = opts

        val names = opts.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.fieldSpinner.adapter = adapter
    }

    private fun confirm() {
        val year = binding.editYear.text?.toString()?.trim()?.toIntOrNull()
        if (year == null) { toast(R.string.year_required); return }
        val month = binding.editMonth.text?.toString()?.trim()?.toIntOrNull()
        if (month != null && month !in 1..12) { toast(R.string.month_valid_range); return }
        val day = binding.editDay.text?.toString()?.trim()?.toIntOrNull()
        if (day != null && !com.novelcharacter.app.util.isValidDay(month, day)) { toast(R.string.day_valid_range); return }
        val opt = fieldOptions.getOrNull(binding.fieldSpinner.selectedItemPosition)
        if (opt == null) { toast(R.string.field_required); return }
        val newValue = binding.editNewValue.text?.toString()?.trim().orEmpty()
        if (newValue.isEmpty()) { toast(R.string.value_required); return }
        val description = binding.editDescription.text?.toString()?.trim().orEmpty()

        batchViewModel.addStateChange(year, month, day, opt.key, newValue, description, opt.universeId)
        dismiss()
    }

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BatchStateChangeBottomSheet"
        fun newInstance() = BatchStateChangeBottomSheet()
    }
}
