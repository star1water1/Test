package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetBatchMemoBinding

class BatchMemoBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBatchMemoBinding? = null
    private val binding get() = _binding!!
    private val batchViewModel: BatchEditViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBatchMemoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val count = batchViewModel.selectedCount.value ?: 0
        binding.btnConfirm.text = getString(R.string.batch_memo_confirm, count)

        binding.btnConfirm.setOnClickListener {
            val text = binding.memoInput.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                val prepend = binding.radioPrepend.isChecked
                batchViewModel.appendMemo(text, prepend)
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BatchMemoBottomSheet"

        fun newInstance(): BatchMemoBottomSheet = BatchMemoBottomSheet()
    }
}
