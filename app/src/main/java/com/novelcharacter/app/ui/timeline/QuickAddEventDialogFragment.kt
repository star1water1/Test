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
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError

/**
 * 간편 사건 추가 다이얼로그 (연도 + 한줄 설명).
 * DialogFragment이므로 회전/프로세스 킬에도 입력이 보존되고,
 * 결과는 FragmentResult로 호스트에 전달된다.
 */
class QuickAddEventDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val year = requireArguments().getInt(ARG_YEAR)
        val binding = DialogQuickAddEventBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.quick_add_event_title, year))
            .setView(binding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // R-27 — 설명이 비면 창을 닫지 않는다. 종전에는 `if (desc.isNotEmpty())`가 저장을
        // **감싸고** 있어서(가드-래핑), 비운 채 저장을 누르면 아무 말 없이 창만 닫히고
        // 적어 둔 것이 사라졌다. 이 창은 밀도 바 롱프레스로 들어오는 러프 입력 자리라
        // "썼는데 없다"가 특히 알아채기 어렵다(B-76 마지막 자리).
        dialog.setValidatedPositiveButton {
            val desc = binding.editQuickEventDesc.text.toString().trim()
            if (desc.isEmpty()) {
                binding.editQuickEventDesc.showInlineError(getString(R.string.event_description_required))
                false
            } else {
                parentFragmentManager.setFragmentResult(
                    RESULT_KEY,
                    bundleOf(RESULT_YEAR to year, RESULT_DESCRIPTION to desc)
                )
                true
            }
        }
        return dialog
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
