package com.novelcharacter.app.ui.image

import android.content.DialogInterface
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
 *
 * ## 회전을 넘긴다 (B-136)
 *
 * 이 시트가 보여 주는 것은 **이미 결제한 응답**이라, 재생성 시 콜백이 null이면 닫아 버리는
 * 관행을 쓸 수 없다(닫는 것이 곧 돈을 버리는 것이다). 이미지판([ImageAiTagReviewSheet])과
 * 같은 갈래를 쓴다 — **제안은 `ImageManagerViewModel.folderTagResult`가 들고 호출부가
 * [rebind]로 다시 먹이고, 사용자의 체크는 이 시트가 [onSaveInstanceState]로 지킨다.**
 */
class ImageFolderTagReviewSheet : BottomSheetDialogFragment() {

    /** 폴더별 제안. 시트를 띄우기 전에 호출부가 채우고, 재생성 뒤에는 [rebind]가 다시 채운다. */
    var suggestions: List<ImageFolderTagSuggester.FolderSuggestion> = emptyList()

    /** 드롭·절단 고지 문구(이미 조립된 줄들). 비어 있으면 그 영역을 감춘다(R-17). */
    var notices: List<String> = emptyList()

    /** 사용자가 고른 것 — 폴더명 → 태그 목록. 하나도 안 고르면 빈 맵으로 부른다. */
    var onApply: (Map<String, List<String>>) -> Unit = {}

    /**
     * 사용자가 검토를 **접었다**(취소 버튼·뒤로가기·바깥 탭). 호출부가 보관 중인 결과를
     * 여기서 버린다 — 남겨 두면 다음 회전에 시트가 혼자 되살아난다.
     */
    var onDismissed: () -> Unit = {}

    private val checked = LinkedHashMap<String, MutableSet<String>>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_image_folder_tag_review, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        restoreReviewState(savedInstanceState)

        view.findViewById<MaterialButton>(R.id.applyButton).setOnClickListener {
            onApply(checked.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() })
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
            onDismissed()
            dismiss()
        }
        render(view)
    }

    /**
     * 재생성 뒤 호출부가 제안을 다시 먹인다 — **체크 상태는 건드리지 않는다.**
     */
    fun rebind(
        suggestions: List<ImageFolderTagSuggester.FolderSuggestion>,
        notices: List<String>
    ) {
        this.suggestions = suggestions
        this.notices = notices
        view?.let { render(it) }
    }

    private fun render(view: View) {
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

        list.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (folder in suggestions) {
            val row = inflater.inflate(R.layout.item_folder_tag_review, list, false)
            row.findViewById<TextView>(R.id.folderName).text = folder.folder
            val tagBox = row.findViewById<LinearLayout>(R.id.tagBox)

            // **처음 보는 폴더만 전부 체크한다** — 이미 아는 폴더에 다시 부으면
            // 회전 때마다 사용자가 지운 체크가 되살아난다.
            val fresh = folder.folder !in checked
            val picked = checked.getOrPut(folder.folder) { LinkedHashSet() }
            if (fresh) picked.addAll(folder.tags.map { it.tag })

            for (s in folder.tags) {
                val box = MaterialCheckBox(requireContext())
                box.text = if (s.isNew) {
                    getString(R.string.image_tag_review_new_tag, s.tag)
                } else {
                    s.tag
                }
                box.isChecked = s.tag in picked
                box.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) picked.add(s.tag) else picked.remove(s.tag)
                }
                tagBox.addView(box)
            }
            list.addView(row)
        }
    }

    /**
     * 사용자가 접었다 — 보관 중인 결과를 버리라고 알린다.
     * **회전은 여기 오지 않는다**(`onCancel`은 뒤로가기·바깥 탭에서만 불린다).
     */
    override fun onCancel(dialog: DialogInterface) {
        onDismissed()
        super.onCancel(dialog)
    }

    /** 검토 상태를 지킨다 — 제안은 ViewModel이 들지만 **어느 칸을 껐는지는 여기밖에 모른다.** */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val picked = Bundle()
        for ((folder, tags) in checked) picked.putStringArrayList(folder, ArrayList(tags))
        outState.putBundle(STATE_CHECKED, picked)
    }

    private fun restoreReviewState(savedInstanceState: Bundle?) {
        val picked = savedInstanceState?.getBundle(STATE_CHECKED) ?: return
        checked.clear()
        for (folder in picked.keySet()) {
            checked[folder] = LinkedHashSet(picked.getStringArrayList(folder).orEmpty())
        }
    }

    companion object {
        const val TAG = "ImageFolderTagReviewSheet"
        private const val STATE_CHECKED = "checkedTags"
    }
}
