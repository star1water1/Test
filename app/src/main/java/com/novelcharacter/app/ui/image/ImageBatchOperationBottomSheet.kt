package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetImageBatchOperationsBinding

/**
 * 이미지 선택 모드 "작업" 시트 — 8개 일괄 작업의 단일 진입점
 * (캐릭터 목록 BatchOperationBottomSheet 패턴 준용). 콜백 주입식.
 */
class ImageBatchOperationBottomSheet : BottomSheetDialogFragment() {

    enum class Action { ASSIGN, TAG_ADD, TAG_REMOVE, LINK, UNLINK, UNASSIGN, RECOMPRESS, DELETE }

    var onAction: ((Action) -> Unit)? = null

    private var _binding: BottomSheetImageBatchOperationsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageBatchOperationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val handler = onAction
        if (handler == null) { dismissAllowingStateLoss(); return }  // 재생성으로 콜백 유실 — 안전 종료

        val count = arguments?.getInt(ARG_COUNT) ?: 0
        binding.titleText.text = getString(R.string.image_batch_title, count)

        fun run(action: Action) { dismiss(); handler(action) }
        binding.opAssign.setOnClickListener { run(Action.ASSIGN) }
        binding.opTagAdd.setOnClickListener { run(Action.TAG_ADD) }
        binding.opTagRemove.setOnClickListener { run(Action.TAG_REMOVE) }
        binding.opLink.setOnClickListener { run(Action.LINK) }
        binding.opUnlink.setOnClickListener { run(Action.UNLINK) }
        binding.opUnassign.setOnClickListener { run(Action.UNASSIGN) }
        binding.opRecompress.setOnClickListener { run(Action.RECOMPRESS) }
        binding.opDelete.setOnClickListener { run(Action.DELETE) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ImageBatchOperationBottomSheet"
        private const val ARG_COUNT = "count"

        fun newInstance(count: Int): ImageBatchOperationBottomSheet =
            ImageBatchOperationBottomSheet().apply {
                arguments = Bundle().apply { putInt(ARG_COUNT, count) }
            }
    }
}
