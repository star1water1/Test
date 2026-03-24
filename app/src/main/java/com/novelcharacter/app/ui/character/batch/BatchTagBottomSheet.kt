package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetBatchTagsBinding
import kotlinx.coroutines.launch

class BatchTagBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBatchTagsBinding? = null
    private val binding get() = _binding!!
    private val batchViewModel: BatchEditViewModel by activityViewModels()

    private val isRemoveMode: Boolean
        get() = arguments?.getBoolean(ARG_REMOVE_MODE) ?: false

    // 삭제 모드에서 선택된 태그
    private val selectedTagsForRemoval = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBatchTagsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val count = batchViewModel.selectedCount.value ?: 0

        if (isRemoveMode) {
            binding.titleText.text = getString(R.string.batch_tag_remove_title)
            binding.tagInputLayout.visibility = View.GONE
            binding.existingTagsLabel.text = getString(R.string.batch_tag_remove_title)
            loadSelectedCharactersTags()
        } else {
            binding.titleText.text = getString(R.string.batch_tag_add_title)
            binding.tagInputLayout.visibility = View.VISIBLE
            loadAllDistinctTags()

            // 입력 변경 시 확인 버튼 텍스트 실시간 업데이트
            binding.tagInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count2: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count2: Int) {
                    val tags = s.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
                    binding.btnConfirm.text = getString(R.string.batch_tag_add_confirm, tags.size, count)
                    binding.btnConfirm.isEnabled = tags.isNotEmpty()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        binding.btnConfirm.setOnClickListener {
            if (isRemoveMode) {
                if (selectedTagsForRemoval.isNotEmpty()) {
                    batchViewModel.removeTags(selectedTagsForRemoval.toList())
                    dismiss()
                }
            } else {
                val inputText = binding.tagInput.text?.toString()?.trim() ?: ""
                val tags = inputText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (tags.isNotEmpty()) {
                    batchViewModel.addTags(tags)
                    dismiss()
                }
            }
        }

        updateConfirmButton(count)
    }

    private fun loadAllDistinctTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = batchViewModel.getAllDistinctTags()
            if (_binding == null) return@launch
            if (tags.isEmpty()) {
                binding.existingTagsLabel.visibility = View.GONE
                binding.tagChipGroup.visibility = View.GONE
            } else {
                binding.existingTagsLabel.visibility = View.VISIBLE
                binding.tagChipGroup.visibility = View.VISIBLE
                for (tag in tags) {
                    val chip = Chip(requireContext()).apply {
                        text = tag
                        isClickable = true
                        isCheckable = false
                        setOnClickListener {
                            val current = binding.tagInput.text?.toString() ?: ""
                            val existing = current.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
                            if (tag !in existing) {
                                existing.add(tag)
                                binding.tagInput.setText(existing.joinToString(", "))
                            }
                        }
                    }
                    binding.tagChipGroup.addView(chip)
                }
            }
        }
    }

    private fun loadSelectedCharactersTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = batchViewModel.getDistinctTagsForCharacters()
            if (_binding == null) return@launch
            if (tags.isEmpty()) {
                binding.tagChipGroup.visibility = View.GONE
                binding.emptyTagsText.visibility = View.VISIBLE
                binding.btnConfirm.isEnabled = false
            } else {
                binding.tagChipGroup.visibility = View.VISIBLE
                binding.emptyTagsText.visibility = View.GONE
                for (tag in tags) {
                    val chip = Chip(requireContext()).apply {
                        text = tag
                        isCheckable = true
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) selectedTagsForRemoval.add(tag)
                            else selectedTagsForRemoval.remove(tag)
                            updateConfirmButton(batchViewModel.selectedCount.value ?: 0)
                        }
                    }
                    binding.tagChipGroup.addView(chip)
                }
            }
        }
    }

    private fun updateConfirmButton(charCount: Int) {
        if (isRemoveMode) {
            val tagCount = selectedTagsForRemoval.size
            binding.btnConfirm.text = getString(R.string.batch_tag_remove_confirm, tagCount, charCount)
            binding.btnConfirm.isEnabled = tagCount > 0
        } else {
            binding.btnConfirm.text = getString(R.string.batch_tag_add_confirm, 0, charCount)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BatchTagBottomSheet"
        private const val ARG_REMOVE_MODE = "removeMode"

        fun newInstance(isRemoveMode: Boolean): BatchTagBottomSheet {
            return BatchTagBottomSheet().apply {
                arguments = Bundle().apply { putBoolean(ARG_REMOVE_MODE, isRemoveMode) }
            }
        }
    }
}
