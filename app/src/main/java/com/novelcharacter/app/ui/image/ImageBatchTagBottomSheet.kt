package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetImageTagEditBinding
import com.novelcharacter.app.util.MultiValueInput
import kotlinx.coroutines.launch

/**
 * 이미지 일괄 태그 추가/제거 시트 — 캐릭터 BatchTagBottomSheet 구조의 콜백판.
 * 추가 모드: 콤마 입력 + 제안 칩(탭=입력 추가). 제거 모드: 선택 이미지들의 distinct 태그 checkable 칩.
 * 레이아웃은 단일 태그 편집 시트(bottom_sheet_image_tag_edit)를 재사용한다(동일 구성).
 */
class ImageBatchTagBottomSheet : BottomSheetDialogFragment() {

    var isRemoveMode: Boolean = false
    /** 링크 묶음 범위 고지(선택 N + 링크 M = 총 T) — null이면 숨긴다. 입력 전에 보여야 하므로 시트 안이다. */
    var linkNotice: String? = null
    var loadChips: (suspend () -> List<String>)? = null      // 추가: 제안 태그 / 제거: 대상(링크 확장 후) 이미지들의 태그
    var onConfirm: ((List<String>) -> Unit)? = null

    private var _binding: BottomSheetImageTagEditBinding? = null
    private val binding get() = _binding!!
    private val selectedForRemoval = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageTagEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (onConfirm == null) { dismissAllowingStateLoss(); return }

        binding.titleText.text = getString(
            if (isRemoveMode) R.string.batch_tag_remove_title else R.string.batch_tag_add_title
        )
        linkNotice?.let {
            binding.linkNoticeText.text = it
            binding.linkNoticeText.visibility = View.VISIBLE
        }
        binding.tagInputLayout.visibility = if (isRemoveMode) View.GONE else View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val chips = loadChips?.invoke() ?: emptyList()
            if (_binding == null) return@launch
            val ctx = context ?: return@launch
            if (chips.isEmpty()) {
                if (isRemoveMode) {
                    binding.suggestionsLabel.visibility = View.VISIBLE
                    binding.suggestionsLabel.text = getString(R.string.batch_tag_no_tags)
                    binding.btnConfirm.isEnabled = false
                }
                return@launch
            }
            binding.suggestionsLabel.visibility = View.VISIBLE
            binding.suggestionsLabel.text = getString(
                if (isRemoveMode) R.string.batch_tag_remove_title else R.string.image_tag_suggestions_label
            )
            binding.suggestionChipGroup.visibility = View.VISIBLE
            for (tag in chips) {
                binding.suggestionChipGroup.addView(Chip(ctx).apply {
                    text = tag
                    if (isRemoveMode) {
                        isCheckable = true
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedForRemoval.add(tag) else selectedForRemoval.remove(tag)
                        }
                    } else {
                        isClickable = true
                        isCheckable = false
                        setOnClickListener {
                            val existing = parseTags(binding.tagInput.text?.toString()).toMutableList()
                            if (tag !in existing) {
                                existing.add(tag)
                                binding.tagInput.setText(MultiValueInput.format(existing))
                            }
                        }
                    }
                })
            }
        }

        binding.btnConfirm.setOnClickListener {
            val tags = if (isRemoveMode) selectedForRemoval.toList()
            else parseTags(binding.tagInput.text?.toString())
            if (tags.isNotEmpty()) {
                onConfirm?.invoke(tags)
                dismiss()
            }
        }
    }

    private fun parseTags(raw: String?): List<String> =
        MultiValueInput.parse(raw).distinct()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "ImageBatchTagBottomSheet" }
}
