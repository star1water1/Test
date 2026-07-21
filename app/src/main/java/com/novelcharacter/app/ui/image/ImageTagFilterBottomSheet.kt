package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.databinding.BottomSheetImageTagFilterBinding
import kotlinx.coroutines.launch

/**
 * 이미지 태그 필터 시트 — distinct 이미지 태그를 multi-select 칩으로 제공(OR 의미론).
 * 로더/콜백 주입식. "초기화" = 빈 집합 적용.
 */
class ImageTagFilterBottomSheet : BottomSheetDialogFragment() {

    var currentTags: Set<String> = emptySet()
    var loadAllTags: (suspend () -> List<String>)? = null
    var onApply: ((Set<String>) -> Unit)? = null

    private var _binding: BottomSheetImageTagFilterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageTagFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (onApply == null) { dismissAllowingStateLoss(); return }

        viewLifecycleOwner.lifecycleScope.launch {
            val tags = loadAllTags?.invoke() ?: emptyList()
            if (_binding == null) return@launch
            val ctx = context ?: return@launch
            if (tags.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                return@launch
            }
            for (tag in tags) {
                binding.tagChipGroup.addView(Chip(ctx).apply {
                    text = tag
                    isCheckable = true
                    isChecked = tag in currentTags
                    textSize = 13f
                })
            }
        }

        binding.btnReset.setOnClickListener {
            onApply?.invoke(emptySet())
            dismiss()
        }
        binding.btnApply.setOnClickListener {
            val selected = mutableSetOf<String>()
            for (i in 0 until binding.tagChipGroup.childCount) {
                (binding.tagChipGroup.getChildAt(i) as? Chip)?.let {
                    if (it.isChecked) selected.add(it.text.toString())
                }
            }
            onApply?.invoke(selected)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "ImageTagFilterBottomSheet" }
}
