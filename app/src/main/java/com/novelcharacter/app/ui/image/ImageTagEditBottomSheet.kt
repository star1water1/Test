package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetImageTagEditBinding
import com.novelcharacter.app.util.MultiValueInput
import kotlinx.coroutines.launch

/**
 * 단일 이미지 태그 편집 시트 — 콤마 구분 입력 + 제안 칩(이미지∪캐릭터 distinct 태그).
 * 빈 입력 저장 = 태그 전체 제거(유효한 의도).
 *
 * 링크 묶음이면 **묶음 합집합**을 보여 주고 범위를 고지한다 — 저장은 묶음 전원 교체이므로
 * (공유 불변식) 보이는 것과 바뀌는 것이 같아야 한다. 재료는 [ImageManagerViewModel]이 든다.
 *
 * ## 회전에서 적던 것을 잃지 않는다 (R-65 · R-41-a)
 *
 * 종전에는 호스트가 람다를 꽂았고, 재생성되면 **조용히 닫혔다**(안전 종료). 그 처분은
 * *현재 상태의 사본*만 든 시트의 것이지, **사용자가 그 자리에서 적은 것**을 든 시트의 것이
 * 아니다(확정 15장 5번). 여기서 사라지는 것은 방금 손으로 친 태그다.
 *
 * 그래서 꽂는 배선을 없앴다 — 대상은 인자 번들(`ARG_PATH`)이 나르고, 재료는 부모의
 * 뷰모델에서 곧장 들며(`childFragmentManager`로 뜨는 것이 그 계약이다), 결과는
 * [RESULT_KEY]로 건넨다. 적던 글자는 뷰 상태 저장이 그대로 지킨다(칸에 id가 있다).
 */
class ImageTagEditBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: ImageManagerViewModel by viewModels(ownerProducer = { requireParentFragment() })

    /** 편집 대상 경로 — 인자 번들이 나른다(회전을 넘는다). */
    private val path: String get() = arguments?.getString(ARG_PATH).orEmpty()

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
        if (path.isBlank()) { dismissAllowingStateLoss(); return }  // 대상을 모른다 — 열 자리가 없다

        // 묶음이면 합집합을 보여 주고 범위를 고지한다(저장이 전원 교체라 보이는 것과 같아야 한다).
        val family = viewModel.linkedFamily(path)
        // **목록이 아직 없으면 열지 않는다** — 프로세스가 죽어 다시 서는 길에서는 뷰모델의
        // 목록이 비어 있을 수 있고, 그때 빈 칸을 보여 주면 [확인] 한 번이 **있던 태그를
        // 통째로 지운다**(빈 입력 저장 = 전체 제거가 유효한 의도이기 때문이다).
        if (family.isEmpty()) { dismissAllowingStateLoss(); return }
        if (family.size > 1) {
            binding.linkNoticeText.text = getString(R.string.image_tag_edit_link_notice, family.size)
            binding.linkNoticeText.visibility = View.VISIBLE
        }
        // **적던 글자가 있으면 그것이 이긴다** — 회전 뒤에는 뷰 상태 저장이 칸을 이미 채워 둔다.
        if (savedInstanceState == null) {
            val currentTags = family.flatMap { it.meta?.tags.orEmpty() }.distinct().sorted()
            binding.tagInput.setText(MultiValueInput.format(currentTags))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = viewModel.getTagSuggestions()
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
            // 쓰기·고지는 호스트가 한다 — 시트는 **무엇을 저장할지**만 건넨다(R-65).
            setFragmentResult(RESULT_KEY, Bundle().apply {
                putString(RESULT_PATH, path)
                putStringArray(RESULT_TAGS, parseTags(binding.tagInput.text?.toString()).toTypedArray())
            })
            dismiss()
        }
    }

    private fun parseTags(raw: String?): List<String> =
        MultiValueInput.parse(raw).distinct()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ImageTagEditBottomSheet"

        /** 저장 결과 — 호스트가 쓰기와 고지를 맡는다(시트는 창의 결과를 받을 자리가 없다). */
        const val RESULT_KEY = "image_tag_edit_result"
        const val RESULT_PATH = "path"
        const val RESULT_TAGS = "tags"

        private const val ARG_PATH = "arg_path"

        fun newInstance(path: String) = ImageTagEditBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_PATH, path) }
        }
    }
}
