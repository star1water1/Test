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

        val count = batchViewModel.selectedCount
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

                    // 상태에 맞는 안내를 고른다. 종전에는 '작품 미지정'을 골라도 "새 세계관에 없는
                    // 필드값이 제거됩니다"를 띄웠는데, batchChangeNovel은 newUniverseId가 null이면
                    // 이관 블록 자체를 건너뛰어 **아무것도 제거하지 않는다** — 사실과 다른 경고였다
                    // (규약: 사실과 다른 경고는 무음보다 나쁘다).
                    val selectedUniverseId = if (position > 0) novels[position - 1].universeId else null
                    val movesIntoOtherUniverse = currentUniverseIds.isNotEmpty() &&
                        selectedUniverseId != null && selectedUniverseId !in currentUniverseIds
                    val leavesUniverse = currentUniverseIds.isNotEmpty() && selectedUniverseId == null
                    when {
                        movesIntoOtherUniverse -> {
                            binding.warningText.setText(R.string.batch_novel_universe_warning)
                            binding.warningText.visibility = View.VISIBLE
                        }
                        leavesUniverse -> {
                            binding.warningText.setText(R.string.batch_novel_unassign_notice)
                            binding.warningText.visibility = View.VISIBLE
                        }
                        else -> binding.warningText.visibility = View.GONE
                    }
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
