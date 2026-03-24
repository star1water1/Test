package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.BottomSheetBatchNovelChangeBinding
import kotlinx.coroutines.launch

class BatchNovelChangeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBatchNovelChangeBinding? = null
    private val binding get() = _binding!!
    private val batchViewModel: BatchEditViewModel by activityViewModels()

    private var novels: List<Novel> = emptyList()
    private var selectedNovelId: Long? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBatchNovelChangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val count = batchViewModel.selectedCount.value ?: 0
        binding.btnConfirm.isEnabled = false // 데이터 로드 전 클릭 방지

        viewLifecycleOwner.lifecycleScope.launch {
            novels = batchViewModel.getAllNovelsList()
            // 선택 캐릭터들의 현재 세계관 ID 수집 (경고 표시용)
            val currentUniverseIds = batchViewModel.getUniverseIdsForSelection().toSet()
            if (_binding == null) return@launch

            val names = mutableListOf(getString(R.string.batch_novel_none))
            names.addAll(novels.map { it.title })

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.novelSpinner.adapter = adapter

            binding.novelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedNovelId = if (position > 0) novels[position - 1].id else null
                    val novelTitle = if (position > 0) novels[position - 1].title else getString(R.string.batch_novel_none)
                    binding.btnConfirm.text = getString(R.string.batch_novel_confirm, count, novelTitle)
                    binding.btnConfirm.isEnabled = true

                    // 다른 세계관으로 이동 시 경고 표시
                    val selectedUniverseId = if (position > 0) novels[position - 1].universeId else null
                    val isDifferentUniverse = currentUniverseIds.isNotEmpty() &&
                        (selectedUniverseId == null || selectedUniverseId !in currentUniverseIds)
                    binding.warningText.visibility = if (isDifferentUniverse) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        binding.btnConfirm.setOnClickListener {
            batchViewModel.changeNovel(selectedNovelId)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BatchNovelChangeBottomSheet"

        fun newInstance(): BatchNovelChangeBottomSheet = BatchNovelChangeBottomSheet()
    }
}
