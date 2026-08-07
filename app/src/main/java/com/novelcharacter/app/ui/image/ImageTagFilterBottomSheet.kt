package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.databinding.BottomSheetImageTagFilterBinding
import com.novelcharacter.app.util.ImageFilterHelper
import kotlinx.coroutines.launch

/**
 * 이미지 태그 필터 시트 — distinct 이미지 태그를 multi-select 칩으로 제공(OR 의미론).
 * 로더/콜백 주입식. "초기화" = 빈 집합 적용.
 *
 * **'태그 없는 것만'은 칩으로 담을 수 없어 별도 스위치다** — 없는 것에는 이름이 없기 때문이다
 * ([ImageFilterHelper.TagFilter]). 둘은 함께 걸면 결과가 비므로 조작에서 배타로 묶는다:
 * 스위치를 켜면 칩을 잠그고, 칩을 고르면 스위치가 풀린다. 잠긴 것이 조용히 무시되는 것이
 * 아니라 **눌리지 않는 것으로 보이게** 하는 쪽이 이유를 그 자리에서 말해 준다.
 */
class ImageTagFilterBottomSheet : BottomSheetDialogFragment() {

    var currentTags: Set<String> = emptySet()
    var currentPresence: ImageFilterHelper.TagFilter = ImageFilterHelper.TagFilter.ANY
    var loadAllTags: (suspend () -> List<String>)? = null
    var onApply: ((Set<String>, ImageFilterHelper.TagFilter) -> Unit)? = null

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

        binding.untaggedCheck.isChecked = currentPresence == ImageFilterHelper.TagFilter.UNTAGGED
        binding.untaggedCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) clearTagChips()
            syncChipEnabled()
        }

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
                    // 태그를 고르는 순간 '태그 없는 것만'은 뜻을 잃는다 — 조용히 두면
                    // 적용 결과가 0건이고 사용자는 이유를 알 수 없다.
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) binding.untaggedCheck.isChecked = false
                    }
                })
            }
            syncChipEnabled()
        }

        binding.btnReset.setOnClickListener {
            onApply?.invoke(emptySet(), ImageFilterHelper.TagFilter.ANY)
            dismiss()
        }
        binding.btnApply.setOnClickListener {
            val selected = mutableSetOf<String>()
            for (i in 0 until binding.tagChipGroup.childCount) {
                (binding.tagChipGroup.getChildAt(i) as? Chip)?.let {
                    if (it.isChecked) selected.add(it.text.toString())
                }
            }
            val presence =
                if (binding.untaggedCheck.isChecked) ImageFilterHelper.TagFilter.UNTAGGED
                else ImageFilterHelper.TagFilter.ANY
            onApply?.invoke(if (presence == ImageFilterHelper.TagFilter.UNTAGGED) emptySet() else selected, presence)
            dismiss()
        }
    }

    /** 스위치가 켜져 있으면 칩은 고를 수 없다 — 배타를 눈에 보이게 하는 쪽이 조용한 0건보다 낫다. */
    private fun syncChipEnabled() {
        val locked = binding.untaggedCheck.isChecked
        for (i in 0 until binding.tagChipGroup.childCount) {
            binding.tagChipGroup.getChildAt(i).isEnabled = !locked
        }
    }

    private fun clearTagChips() {
        for (i in 0 until binding.tagChipGroup.childCount) {
            (binding.tagChipGroup.getChildAt(i) as? Chip)?.isChecked = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "ImageTagFilterBottomSheet" }
}
