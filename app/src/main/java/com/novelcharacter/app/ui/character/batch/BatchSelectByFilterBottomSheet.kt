package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.BottomSheetBatchSelectFilterBinding
import kotlinx.coroutines.launch

class BatchSelectByFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBatchSelectFilterBinding? = null
    private val binding get() = _binding!!
    private val batchViewModel: BatchEditViewModel by activityViewModels()

    private var novels: List<Novel> = emptyList()
    private val selectedFilterTags = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBatchSelectFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            // 작품 로드
            novels = batchViewModel.getAllNovelsList()
            if (_binding == null) return@launch

            val novelNames = mutableListOf(getString(R.string.batch_novel_none))
            novelNames.addAll(novels.map { it.title })
            val nAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, novelNames)
            nAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.filterNovelSpinner.adapter = nAdapter

            // 태그 로드
            val tags = batchViewModel.getAllDistinctTags()
            if (_binding == null) return@launch
            for (tag in tags) {
                val chip = Chip(requireContext()).apply {
                    text = tag
                    isCheckable = true
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedFilterTags.add(tag)
                        else selectedFilterTags.remove(tag)
                    }
                }
                binding.filterTagChipGroup.addView(chip)
            }
        }

        binding.btnApplyFilter.setOnClickListener {
            applyFilter()
        }
    }

    private fun applyFilter() {
        // UI 값을 코루틴 진입 전에 캡처 (dismiss 후 binding 접근 불가)
        val novelPosition = binding.filterNovelSpinner.selectedItemPosition
        val replaceMode = binding.radioReplaceSelection.isChecked
        val filterTags = selectedFilterTags.toSet()

        // 아무 필터도 선택하지 않은 경우 → 아무 작업 안 함
        if (novelPosition == 0 && filterTags.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            val app = batchViewModel.getApplication<NovelCharacterApp>()
            val matchingIds = mutableSetOf<Long>()

            // 작품별 필터
            if (novelPosition > 0) {
                val novelId = novels[novelPosition - 1].id
                val chars = app.characterRepository.getCharactersByNovelList(novelId)
                matchingIds.addAll(chars.map { it.id })
            }

            // 태그별 필터
            if (filterTags.isNotEmpty()) {
                val tagDao = app.database.characterTagDao()
                val allTags = tagDao.getAllTagsList()
                val charIdsWithTags = allTags
                    .filter { it.tag in filterTags }
                    .map { it.characterId }
                    .toSet()
                if (novelPosition > 0) {
                    // 작품 + 태그 교집합
                    matchingIds.retainAll(charIdsWithTags)
                } else {
                    matchingIds.addAll(charIdsWithTags)
                }
            }

            if (replaceMode) {
                batchViewModel.replaceSelection(matchingIds)
            } else {
                batchViewModel.addToSelection(matchingIds)
            }

            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BatchSelectByFilterBottomSheet"

        fun newInstance(): BatchSelectByFilterBottomSheet = BatchSelectByFilterBottomSheet()
    }
}
