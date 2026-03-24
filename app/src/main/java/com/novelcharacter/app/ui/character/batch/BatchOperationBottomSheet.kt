package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetBatchOperationsBinding

class BatchOperationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBatchOperationsBinding? = null
    private val binding get() = _binding!!
    private val batchViewModel: BatchEditViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBatchOperationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val count = arguments?.getInt(ARG_COUNT) ?: 0
        binding.selectedCountLabel.text = getString(R.string.batch_selected_count, count)

        binding.opChangeNovel.setOnClickListener {
            dismiss()
            BatchNovelChangeBottomSheet.newInstance()
                .show(parentFragmentManager, BatchNovelChangeBottomSheet.TAG)
        }

        binding.opAddTags.setOnClickListener {
            dismiss()
            BatchTagBottomSheet.newInstance(isRemoveMode = false)
                .show(parentFragmentManager, BatchTagBottomSheet.TAG)
        }

        binding.opRemoveTags.setOnClickListener {
            dismiss()
            BatchTagBottomSheet.newInstance(isRemoveMode = true)
                .show(parentFragmentManager, BatchTagBottomSheet.TAG)
        }

        binding.opSetField.setOnClickListener {
            dismiss()
            BatchFieldValueBottomSheet.newInstance(isClearMode = false)
                .show(parentFragmentManager, BatchFieldValueBottomSheet.TAG)
        }

        binding.opClearField.setOnClickListener {
            dismiss()
            BatchFieldValueBottomSheet.newInstance(isClearMode = true)
                .show(parentFragmentManager, BatchFieldValueBottomSheet.TAG)
        }

        binding.opPin.setOnClickListener {
            dismiss()
            batchViewModel.setPinned(true)
        }

        binding.opUnpin.setOnClickListener {
            dismiss()
            batchViewModel.setPinned(false)
        }

        binding.opAppendMemo.setOnClickListener {
            dismiss()
            BatchMemoBottomSheet.newInstance()
                .show(parentFragmentManager, BatchMemoBottomSheet.TAG)
        }

        binding.opDelete.setOnClickListener {
            dismiss()
            showDeleteConfirmation(count)
        }
    }

    private fun showDeleteConfirmation(count: Int) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.batch_delete_confirm_title)
            .setMessage(getString(R.string.batch_delete_confirm_message, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                batchViewModel.deleteSelected()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BatchOperationBottomSheet"
        private const val ARG_COUNT = "count"

        fun newInstance(count: Int): BatchOperationBottomSheet {
            return BatchOperationBottomSheet().apply {
                arguments = Bundle().apply { putInt(ARG_COUNT, count) }
            }
        }
    }
}
