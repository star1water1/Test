package com.novelcharacter.app.ui.timeline

import android.app.Dialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.DialogQuickAddEventBinding

/**
 * 간편 사건 추가 다이얼로그 (연도 + 한줄 설명).
 * DialogFragment이므로 회전/프로세스 킬에도 입력이 보존되고,
 * 결과는 FragmentResult로 호스트에 전달된다.
 */
class QuickAddEventDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val year = requireArguments().getInt(ARG_YEAR)
        val binding = DialogQuickAddEventBinding.inflate(layoutInflater)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.quick_add_event_title, year))
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val desc = binding.editQuickEventDesc.text.toString().trim()
                if (desc.isNotEmpty()) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_KEY,
                        bundleOf(RESULT_YEAR to year, RESULT_DESCRIPTION to desc)
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "QuickAddEventDialog"
        const val RESULT_KEY = "quick_add_event_result"
        const val RESULT_YEAR = "year"
        const val RESULT_DESCRIPTION = "description"
        private const val ARG_YEAR = "year"

        fun show(fragmentManager: FragmentManager, year: Int) {
            val fragment = QuickAddEventDialogFragment()
            fragment.arguments = bundleOf(ARG_YEAR to year)
            fragment.show(fragmentManager, TAG)
        }
    }
}
