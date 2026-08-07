package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.ImageBatchTagSuggester
import com.novelcharacter.app.util.loadCharacterThumbnail

/**
 * 이미지 일괄 AI 태깅의 **검토 시트** (B-121 · 설계 feature_roadmap 2-3 4번).
 *
 * **AI 출력은 절대 자동 적용하지 않는다**(`docs/ai_integration.md`). 이미지별로 제안을 보여 주고
 * 사용자가 체크한 칩만 적용한다. 폴더판([ImageFolderTagReviewSheet])의 규칙을 그대로 넓히되,
 * **대상이 수십 장이라 조작 셋이 더 붙는다**(설계가 그렇게 적었다):
 *
 * - **[전체 해제 / 전체 선택]** — 기본은 전부 체크이므로 첫 조작은 보통 '전체 해제'다.
 * - **[새 태그만 보기]** — 기존 어휘 태그는 안심하고 일괄 승인하고 새 태그만 훑는 것이
 *   실제 검토 동선이다. 거르는 것은 **칩**이고, 그 결과 보일 칩이 하나도 없는 행은 함께
 *   접힌다(빈 줄만 수십 개 남으면 훑을 수가 없다). **접힌 행의 태그도 체크된 채 적용되므로
 *   접은 장수를 반드시 고지한다**(R-14 — 잘라낸 것은 개수로 존재를 알린다). 고른 칩 수를
 *   세는 [updateCount]도 접힌 것을 함께 센다.
 * - **행 접기** — 칩이 [AiPromptPolicy.IMAGE_TAG_ROW_COLLAPSE_AT]개를 넘으면 접고 `외 N개`로
 *   말한다. **접힌 칩도 체크 상태는 그대로다**(숨긴 것과 뺀 것은 다르다 — R-19).
 */
class ImageAiTagReviewSheet : BottomSheetDialogFragment() {

    /** 이미지별 제안. 시트를 띄우기 전에 호출부가 채운다. */
    var suggestions: List<ImageBatchTagSuggester.ImageSuggestion> = emptyList()

    /** 드롭·절단·실패 고지 문구(이미 조립된 줄들). 비어 있으면 그 영역을 감춘다(R-17). */
    var notices: List<String> = emptyList()

    /** 접힌 배치의 이미지들. 비어 있지 않으면 '1장씩 다시 보내기'가 열린다. */
    var retryPaths: List<String> = emptyList()

    /** 사용자가 고른 것 — 경로 → 태그 목록. */
    var onApply: (Map<String, List<String>>) -> Unit = {}

    /** '1장씩 다시 보내기'. 시트를 닫고 호출부가 다시 돌린다. */
    var onRetryOneByOne: (List<String>) -> Unit = {}

    private val checked = LinkedHashMap<String, MutableSet<String>>()
    private val rows = ArrayList<Row>()

    /** 행 하나의 뷰와 상태 — 접기·필터가 이 목록을 훑어 다시 그린다. */
    private class Row(
        val suggestion: ImageBatchTagSuggester.ImageSuggestion,
        val root: View,
        val chipGroup: ChipGroup,
        val expandButton: MaterialButton,
        var expanded: Boolean = false
    )

    private var newOnly = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_image_ai_tag_review, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<LinearLayout>(R.id.imageList)
        val noticeView = view.findViewById<TextView>(R.id.noticeText)
        val emptyView = view.findViewById<TextView>(R.id.emptyText)
        val toolRow = view.findViewById<View>(R.id.toolRow)
        val applyButton = view.findViewById<MaterialButton>(R.id.applyButton)
        val retryButton = view.findViewById<MaterialButton>(R.id.retryButton)
        val toggleAll = view.findViewById<MaterialButton>(R.id.toggleAllButton)
        val newOnlyChip = view.findViewById<Chip>(R.id.newOnlyChip)

        noticeView.visibility = if (notices.isEmpty()) View.GONE else View.VISIBLE
        noticeView.text = notices.joinToString("\n")

        // 제안이 0건인 것과 실패한 것은 다르다 — 사유는 notices가 말하고, 여기서는
        // "고를 것이 없다"만 말한다(R-17).
        val empty = suggestions.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        toolRow.visibility = if (empty) View.GONE else View.VISIBLE
        applyButton.isEnabled = !empty

        // 접힌 배치가 있으면 되받을 길을 그 자리에서 연다 — 장수 1이면 번호 사고가 원리적으로 없다.
        retryButton.visibility = if (retryPaths.isEmpty()) View.GONE else View.VISIBLE
        retryButton.setOnClickListener {
            val paths = retryPaths
            dismiss()
            onRetryOneByOne(paths)
        }

        val inflater = LayoutInflater.from(requireContext())
        for (s in suggestions) {
            val row = inflater.inflate(R.layout.item_image_tag_review, list, false)
            row.findViewById<TextView>(R.id.fileName).text = s.path.substringAfterLast('/')
            row.findViewById<ImageView>(R.id.thumbnail).loadCharacterThumbnail(
                s.path, viewLifecycleOwner.lifecycleScope, reqPx = THUMB_PX
            )
            val group = row.findViewById<ChipGroup>(R.id.tagChipGroup)
            val expand = row.findViewById<MaterialButton>(R.id.expandButton)
            val picked = checked.getOrPut(s.path) { LinkedHashSet() }
            picked.addAll(s.tags.map { it.tag })   // 기본은 전부 체크(원칙 04 — 훑어 지우는 편이 짧다)

            val entry = Row(s, row, group, expand)
            expand.setOnClickListener {
                entry.expanded = !entry.expanded
                bindRow(entry)
                updateCount(view)
            }
            rows.add(entry)
            bindRow(entry)
            list.addView(row)
        }

        toggleAll.setOnClickListener {
            // 하나라도 켜져 있으면 '전체 해제', 전부 꺼져 있으면 '전체 선택'이다.
            val anyChecked = checked.values.any { it.isNotEmpty() }
            for (row in rows) {
                val picked = checked.getValue(row.suggestion.path)
                picked.clear()
                if (!anyChecked) picked.addAll(row.suggestion.tags.map { it.tag })
                bindRow(row)
            }
            updateToggleLabel(toggleAll)
            updateCount(view)
        }

        newOnlyChip.setOnCheckedChangeListener { _, isChecked ->
            newOnly = isChecked
            for (row in rows) bindRow(row)
            updateHiddenNotice(view)
        }

        applyButton.setOnClickListener {
            onApply(checked.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() })
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener { dismiss() }

        updateToggleLabel(toggleAll)
        updateCount(view)
        updateHiddenNotice(view)
    }

    /**
     * '새 태그만 보기'로 접힌 행이 몇 개인지 말한다.
     *
     * 접힌 행의 태그는 **체크된 채 그대로 적용된다** — 말하지 않으면 사용자는 자기가 훑은
     * 것만 들어간다고 믿는다. 필터를 끄면 이 줄도 사라진다(R-17: 할 말이 없으면 감춘다).
     */
    private fun updateHiddenNotice(root: View) {
        val view = root.findViewById<TextView>(R.id.hiddenText)
        val hidden = if (!newOnly) 0 else rows.count { row -> row.suggestion.tags.none { it.isNew } }
        view.visibility = if (hidden == 0) View.GONE else View.VISIBLE
        if (hidden > 0) view.text = getString(R.string.image_ai_tag_review_hidden, hidden)
    }

    /**
     * 한 행의 칩을 다시 그린다 — 필터·접기·체크가 전부 여기 모인다.
     *
     * 다시 그리는 쪽을 고른 이유: 세 상태가 각자 칩을 건드리면 "필터를 켠 채 접었다 폈을 때"
     * 같은 조합에서 어긋난다. 상태는 [checked]에만 있고 화면은 그것의 함수다.
     */
    private fun bindRow(row: Row) {
        val ctx = context ?: return
        val picked = checked.getOrPut(row.suggestion.path) { LinkedHashSet() }
        val visible = row.suggestion.tags.filter { !newOnly || it.isNew }
        val limit = AiPromptPolicy.IMAGE_TAG_ROW_COLLAPSE_AT
        val shown = if (row.expanded || visible.size <= limit) visible else visible.take(limit)

        row.chipGroup.removeAllViews()
        for (s in shown) {
            row.chipGroup.addView(Chip(ctx).apply {
                text = if (s.isNew) getString(R.string.image_tag_review_new_tag, s.tag) else s.tag
                isCheckable = true
                isChecked = s.tag in picked
                textSize = 13f
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) picked.add(s.tag) else picked.remove(s.tag)
                    view?.let { updateCount(it) }
                }
            })
        }

        // 보일 칩이 하나도 없는 행은 접는다 — 필터를 켠 목적이 '새 태그만 훑기'인데
        // 빈 줄이 사이에 끼면 훑을 수가 없다. 접은 장수는 updateHiddenNotice가 말한다.
        row.root.visibility = if (visible.isEmpty() && newOnly) View.GONE else View.VISIBLE

        val hidden = visible.size - shown.size
        row.expandButton.visibility = if (hidden > 0 || row.expanded && visible.size > limit) {
            View.VISIBLE
        } else {
            View.GONE
        }
        row.expandButton.text =
            if (row.expanded) getString(R.string.image_ai_tag_review_collapse)
            else getString(R.string.image_ai_tag_review_more, hidden)
    }

    private fun updateToggleLabel(button: MaterialButton) {
        val anyChecked = checked.values.any { it.isNotEmpty() }
        button.setText(
            if (anyChecked) R.string.image_ai_tag_review_deselect_all
            else R.string.image_ai_tag_review_select_all
        )
    }

    /**
     * 고른 칩 수를 센다 — **접히거나 걸러진 칩도 함께 센다.**
     * 화면에 보이는 것만 세면 '새 태그만 보기'를 켠 순간 수가 줄어, 사용자는 선택이 풀린 줄 안다.
     */
    private fun updateCount(root: View) {
        val total = checked.values.sumOf { it.size }
        root.findViewById<TextView>(R.id.countText).text =
            getString(R.string.image_ai_tag_review_selected, total)
        root.findViewById<MaterialButton>(R.id.applyButton).isEnabled = total > 0
        root.findViewById<MaterialButton>(R.id.toggleAllButton)?.let { updateToggleLabel(it) }
    }

    companion object {
        const val TAG = "ImageAiTagReviewSheet"
        private const val THUMB_PX = 128
    }
}
