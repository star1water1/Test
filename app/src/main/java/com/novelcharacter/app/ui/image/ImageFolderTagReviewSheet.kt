package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.ImageFolderTagSuggester

/**
 * AI 태그 제안 **검토 시트** — 설계 `image_folder_tag_ai` 3-1의 계약 4번을 구현한다.
 *
 * **AI 출력은 절대 자동 적용하지 않는다**(`docs/ai_integration.md`). 폴더별로 제안을 보여 주고
 * 사용자가 체크한 것만 적용한다. 기존 어휘에 없던 태그는 `새 태그` 표식을 달아, 표기가
 * 갈라지는 것을 사용자가 그 자리에서 알아볼 수 있게 한다(어휘는 참고이지 허용 목록이 아니다).
 *
 * 기본값은 **전부 체크**다 — 어차피 사용자가 훑어 지우는 편이, 하나씩 켜는 것보다 짧다
 * (원칙 04). 잘못된 제안이 조용히 들어가는 것은 아니다. 이 화면을 통과해야 적용된다.
 */
class ImageFolderTagReviewSheet : BottomSheetDialogFragment() {

    /** 폴더별 제안. 시트를 띄우기 전에 호출부가 채운다. */
    var suggestions: List<ImageFolderTagSuggester.FolderSuggestion> = emptyList()

    /** 드롭·절단 고지 문구(이미 조립된 줄들). 비어 있으면 그 영역을 감춘다(R-17). */
    var notices: List<String> = emptyList()

    /** 사용자가 고른 것 — 폴더명 → 태그 목록. 하나도 안 고르면 빈 맵으로 부른다. */
    var onApply: (Map<String, List<String>>) -> Unit = {}

    private val checked = HashMap<String, MutableSet<String>>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_image_folder_tag_review, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<LinearLayout>(R.id.folderList)
        val noticeView = view.findViewById<TextView>(R.id.noticeText)
        val emptyView = view.findViewById<TextView>(R.id.emptyText)
        val applyButton = view.findViewById<MaterialButton>(R.id.applyButton)

        noticeView.visibility = if (notices.isEmpty()) View.GONE else View.VISIBLE
        noticeView.text = notices.joinToString("\n")

        // 제안이 0건인 것과 실패한 것은 다르다 — 사유는 notices가 말하고, 여기서는
        // "고를 것이 없다"만 말한다(R-17: 빈 결과는 사유를 말한다).
        emptyView.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.GONE
        applyButton.isEnabled = suggestions.isNotEmpty()

        val inflater = LayoutInflater.from(requireContext())
        for (folder in suggestions) {
            val row = inflater.inflate(R.layout.item_folder_tag_review, list, false)
            row.findViewById<TextView>(R.id.folderName).text = folder.folder
            val tagBox = row.findViewById<LinearLayout>(R.id.tagBox)
            val picked = checked.getOrPut(folder.folder) { LinkedHashSet() }
            for (s in folder.tags) {
                val box = MaterialCheckBox(requireContext())
                box.text = if (s.isNew) {
                    getString(R.string.image_tag_review_new_tag, s.tag)
                } else {
                    s.tag
                }
                box.isChecked = true
                picked.add(s.tag)
                box.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) picked.add(s.tag) else picked.remove(s.tag)
                }
                tagBox.addView(box)
            }
            list.addView(row)
        }

        applyButton.setOnClickListener {
            onApply(checked.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() })
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener { dismiss() }
    }

    companion object {
        const val TAG = "ImageFolderTagReviewSheet"
    }
}
