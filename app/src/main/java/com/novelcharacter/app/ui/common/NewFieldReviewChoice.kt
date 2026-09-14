package com.novelcharacter.app.ui.common

import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object NewFieldReviewChoice {
    fun show(fragment: Fragment, reviews: List<Pair<String, String>>,
        choose: (String?) -> Unit, resume: () -> Unit): Boolean {
        if (reviews.isEmpty()) { choose(null); return false }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("새 구상 · 보관한 AI 검토")
            .setItems((listOf("새 검토 시작 · 이전 결과는 보관") + reviews.map {
                "이전 검토 열기: ${it.second.ifBlank { "이름 없는 구상" }}"
            }).toTypedArray()) { _, index ->
                choose(if (index == 0) null else reviews[index - 1].first)
                resume()
            }
            .setNegativeButton("닫기", null)
            .show()
        return true
    }
}
