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
 * 이미지 일괄 태그 추가/제거 시트 — 캐릭터 BatchTagBottomSheet 구조의 결과판.
 * 추가 모드: 콤마 입력 + 제안 칩(탭=입력 추가). 제거 모드: 선택 이미지들의 distinct 태그 checkable 칩.
 * 레이아웃은 단일 태그 편집 시트(bottom_sheet_image_tag_edit)를 재사용한다(동일 구성).
 *
 * ## 회전에서 적던 것을 잃지 않는다 (R-65 · R-41-a)
 *
 * 종전에는 호스트가 람다를 꽂았고 재생성되면 **조용히 닫혔다**. 그 안전 종료는 *현재 상태의
 * 사본*만 든 시트의 처분이지, **사용자가 그 자리에서 적고 고른 것**을 든 시트의 것이 아니다
 * (확정 15장 5번). 여기서 사라지는 것은 손으로 친 태그와 체크한 제거 칩이다.
 *
 * 그래서 배선을 없앴다 — 모드는 인자 번들이 나르고, 대상과 재료는 부모의 뷰모델에서 곧장
 * 들며(선택 자체가 뷰모델에 산다), 결과는 [RESULT_KEY]로 건넨다. 체크한 제거 칩은
 * [onSaveInstanceState]가 지킨다(칩은 동적이라 뷰 상태 저장이 못 든다).
 */
class ImageBatchTagBottomSheet : BottomSheetDialogFragment() {

    private val viewModel: ImageManagerViewModel by viewModels(ownerProducer = { requireParentFragment() })

    private val isRemoveMode: Boolean get() = arguments?.getBoolean(ARG_REMOVE_MODE) ?: false

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
        savedInstanceState?.getStringArray(STATE_REMOVAL)?.let { selectedForRemoval.addAll(it) }

        // 대상은 **지금의 선택**에서 다시 센다 — 선택 자체가 뷰모델에 살아 회전을 넘는다.
        val expansion = viewModel.expandWithLinkedGroups(viewModel.selectedPaths)
        val targets = expansion.allPaths.toList()
        if (targets.isEmpty()) { dismissAllowingStateLoss(); return }  // 고른 것이 없다 — 열 자리가 없다

        binding.titleText.text = getString(
            if (isRemoveMode) R.string.batch_tag_remove_title else R.string.batch_tag_add_title
        )
        // 범위 고지는 **입력 전에** 시트 안에서 한다(배정의 확인 창과 같은 정보, 한 단계 앞).
        if (expansion.addedByLink.isNotEmpty()) {
            binding.linkNoticeText.text = getString(
                R.string.image_batch_tag_link_notice,
                viewModel.selectedPaths.size, expansion.addedByLink.size, targets.size
            )
            binding.linkNoticeText.visibility = View.VISIBLE
        }
        binding.tagInputLayout.visibility = if (isRemoveMode) View.GONE else View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            // 제거 칩은 **넓힌 목록**의 태그에서 뽑는다 — 형제만 가진 태그도 지울 수 있어야
            // 합집합 편집과 대칭이 맞는다.
            val chips =
                if (isRemoveMode) viewModel.getDistinctTagsForPaths(targets)
                else viewModel.getTagSuggestions()
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
                        // 회전 전에 체크한 칩은 체크된 채로 선다(칩은 동적이라 뷰 상태 저장이 못 든다).
                        isChecked = tag in selectedForRemoval
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
            if (tags.isEmpty()) {
                // 누르면 아무 일도 안 나는 자리를 만들지 않는다(R-17) — 사유를 말한다.
                android.widget.Toast.makeText(
                    requireContext(), getString(R.string.ai_review_pick_none), android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // 쓰기·진행 표시·고지는 호스트가 한다 — 시트는 **무엇을 붙이고 뗄지**만 건넨다.
            setFragmentResult(RESULT_KEY, Bundle().apply {
                putBoolean(RESULT_REMOVE, isRemoveMode)
                putStringArray(RESULT_TAGS, tags.toTypedArray())
            })
            dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_REMOVAL, selectedForRemoval.toTypedArray())
    }

    private fun parseTags(raw: String?): List<String> =
        MultiValueInput.parse(raw).distinct()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ImageBatchTagBottomSheet"

        /** 확인 결과 — 호스트가 쓰기와 진행 표시를 맡는다. */
        const val RESULT_KEY = "image_batch_tag_result"
        const val RESULT_REMOVE = "remove"
        const val RESULT_TAGS = "tags"

        private const val ARG_REMOVE_MODE = "arg_remove_mode"
        private const val STATE_REMOVAL = "state_removal"

        fun newInstance(removeMode: Boolean) = ImageBatchTagBottomSheet().apply {
            arguments = Bundle().apply { putBoolean(ARG_REMOVE_MODE, removeMode) }
        }
    }
}
