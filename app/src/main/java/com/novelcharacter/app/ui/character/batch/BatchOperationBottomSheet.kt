package com.novelcharacter.app.ui.character.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.databinding.BottomSheetBatchOperationsBinding
import kotlinx.coroutines.launch

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

        // dismiss() 후 parentFragmentManager 접근 시 detach 위험 방지: 참조를 미리 캡처
        val fm = parentFragmentManager

        binding.opChangeNovel.setOnClickListener {
            dismiss()
            BatchNovelChangeBottomSheet.newInstance().show(fm, BatchNovelChangeBottomSheet.TAG)
        }

        binding.opAddTags.setOnClickListener {
            dismiss()
            BatchTagBottomSheet.newInstance(isRemoveMode = false).show(fm, BatchTagBottomSheet.TAG)
        }

        binding.opRemoveTags.setOnClickListener {
            dismiss()
            BatchTagBottomSheet.newInstance(isRemoveMode = true).show(fm, BatchTagBottomSheet.TAG)
        }

        binding.opSetField.setOnClickListener {
            dismiss()
            BatchFieldValueBottomSheet.newInstance(isClearMode = false).show(fm, BatchFieldValueBottomSheet.TAG)
        }

        binding.opClearField.setOnClickListener {
            dismiss()
            BatchFieldValueBottomSheet.newInstance(isClearMode = true).show(fm, BatchFieldValueBottomSheet.TAG)
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
            BatchMemoBottomSheet.newInstance().show(fm, BatchMemoBottomSheet.TAG)
        }

        binding.opStateChange.setOnClickListener {
            dismiss()
            BatchStateChangeBottomSheet.newInstance().show(fm, BatchStateChangeBottomSheet.TAG)
        }

        binding.opDelete.setOnClickListener {
            showDeleteConfirmation(count)
        }
    }

    private fun showDeleteConfirmation(count: Int) {
        // 연쇄 영향(관계·상태변화·세력소속·사건연계)을 먼저 집계해 삭제 범위를 사전 고지한다.
        viewLifecycleOwner.lifecycleScope.launch {
            val impact = batchViewModel.getDeleteImpact()
            val ctx = context ?: return@launch
            if (!isAdded) return@launch
            AlertDialog.Builder(ctx)
                .setTitle(R.string.batch_delete_confirm_title)
                .setMessage(buildDeleteMessage(count, impact))
                .setPositiveButton(R.string.delete) { _, _ ->
                    batchViewModel.deleteSelected()
                    dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 기본 확인 문구 + (연관 데이터가 있으면) 연쇄 정리 규모 요약을 덧붙인다. */
    private fun buildDeleteMessage(count: Int, impact: CharacterRepository.DeleteImpact): String {
        val base = getString(R.string.batch_delete_confirm_message, count)
        if (!impact.hasLinkedData) return base
        val parts = mutableListOf<String>()
        if (impact.relationships > 0) parts.add(getString(R.string.batch_delete_impact_relationships, impact.relationships))
        if (impact.stateChanges > 0) parts.add(getString(R.string.batch_delete_impact_state_changes, impact.stateChanges))
        if (impact.factionMemberships > 0) parts.add(getString(R.string.batch_delete_impact_memberships, impact.factionMemberships))
        if (impact.eventLinks > 0) parts.add(getString(R.string.batch_delete_impact_events, impact.eventLinks))
        return "$base\n\n" + getString(R.string.batch_delete_impact_summary, parts.joinToString(", "))
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
