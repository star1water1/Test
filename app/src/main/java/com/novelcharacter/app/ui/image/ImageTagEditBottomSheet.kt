package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.databinding.BottomSheetImageTagEditBinding
import com.novelcharacter.app.util.MultiValueInput
import kotlinx.coroutines.launch

/**
 * 단일 이미지 태그 편집 시트 — 콤마 구분 입력 + 제안 칩(이미지∪캐릭터 distinct 태그).
 * 호출측이 [currentTags]/[loadSuggestions]/[onSave]를 주입한다(CharacterFilterBottomSheet의
 * public var 주입 패턴). 빈 입력 저장 = 태그 전체 제거(유효한 의도).
 *
 * 링크 묶음이면 호출측이 [currentTags]에 **묶음 합집합**을, [linkNotice]에 범위 고지를
 * 넣는다 — 저장은 묶음 전원 교체이므로(공유 불변식) 보이는 것과 바뀌는 것이 같아야 한다.
 *
 * 주의: 프로세스 재생성(회전 등) 시 주입 람다가 유실되므로 그 경우 조용히 닫힌다(기존 시트들과 동일 트레이드오프).
 */
class ImageTagEditBottomSheet : BottomSheetDialogFragment() {

    var currentTags: List<String> = emptyList()
    /** 링크 묶음 범위 고지 — null이면 숨긴다(미링크). 입력 전에 보여야 하므로 시트 안이다. */
    var linkNotice: String? = null
    var loadSuggestions: (suspend () -> List<String>)? = null
    var onSave: ((List<String>) -> Unit)? = null

    private var _binding: BottomSheetImageTagEditBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageTagEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (onSave == null) { dismissAllowingStateLoss(); return }  // 재생성으로 주입 유실 — 안전 종료

        linkNotice?.let {
            binding.linkNoticeText.text = it
            binding.linkNoticeText.visibility = View.VISIBLE
        }
        binding.tagInput.setText(MultiValueInput.format(currentTags))

        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = loadSuggestions?.invoke() ?: emptyList()
            if (_binding == null) return@launch
            if (suggestions.isEmpty()) return@launch
            binding.suggestionsLabel.visibility = View.VISIBLE
            binding.suggestionChipGroup.visibility = View.VISIBLE
            val ctx = context ?: return@launch
            for (tag in suggestions) {
                val chip = Chip(ctx).apply {
                    text = tag
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
                binding.suggestionChipGroup.addView(chip)
            }
        }

        binding.btnConfirm.setOnClickListener {
            onSave?.invoke(parseTags(binding.tagInput.text?.toString()))
            dismiss()
        }
    }

    private fun parseTags(raw: String?): List<String> =
        MultiValueInput.parse(raw).distinct()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "ImageTagEditBottomSheet" }
}
