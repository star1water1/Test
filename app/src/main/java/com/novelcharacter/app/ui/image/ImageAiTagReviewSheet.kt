package com.novelcharacter.app.ui.image

import android.content.DialogInterface
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
 *
 * ## 회전을 넘긴다 (B-136)
 *
 * 이 시트가 들고 있는 것은 **이미 결제한 응답**이라, 재생성 시 콜백이 null이면 닫아 버리는
 * 이 저장소의 관행(`ImageBatchOperationBottomSheet` 등 — *주입 유실 시 안전 종료*)을 **여기에는
 * 쓸 수 없다.** 닫는 것이 곧 돈을 버리는 것이기 때문이다. 그래서 갈래를 둘로 나눈다:
 *
 * - **제안·고지·되받기 목록**은 [ImageManagerViewModel.aiTagResult]가 들고, 재생성 뒤
 *   호출부가 [rebind]로 다시 먹인다(`StatsFieldAnalysisDetailFragment`의 재배선 선례).
 * - **사용자의 검토 상태**(체크·필터·펼침)는 이 시트가 [onSaveInstanceState]로 지킨다.
 *   제안만 되살리고 체크를 초기화하면 **수십 장을 훑어 지운 일이 통째로 되돌아간다** —
 *   화면은 멀쩡해 보이므로 사용자는 그 사실을 알아채지도 못한다.
 */
class ImageAiTagReviewSheet : BottomSheetDialogFragment() {

    /** 이미지별 제안. 시트를 띄우기 전에 호출부가 채우고, 재생성 뒤에는 [rebind]가 다시 채운다. */
    var suggestions: List<ImageBatchTagSuggester.ImageSuggestion> = emptyList()

    /** 드롭·절단·실패 고지 문구(이미 조립된 줄들). 비어 있으면 그 영역을 감춘다(R-17). */
    var notices: List<String> = emptyList()

    /** 접힌 배치의 이미지들. 비어 있지 않으면 '1장씩 다시 보내기'가 열린다. */
    var retryPaths: List<String> = emptyList()

    /**
     * 묶음 단위 실행에서 이 경로의 태그가 붙을 **묶음 장수** (2장 이상만 실린다).
     * 행의 파일명 옆에 적는다 — 체크한 태그가 화면에 없는 장에도 붙는다는 사실을
     * 그 행에서 보게 한다(변수 제어 — 요약 고지만으로는 어느 행이 그런지 모른다).
     */
    var groupSizeByPath: Map<String, Int> = emptyMap()

    /** 사용자가 고른 것 — 경로 → 태그 목록. */
    var onApply: (Map<String, List<String>>) -> Unit = {}

    /**
     * '1장씩 다시 보내기'. **시트를 닫지 않는다** — 되받은 결과는 앞의 성공분 위에 얹혀
     * 이 시트로 돌아온다(B-140). 닫고 되돌려받던 종전 방식은 이미 받아 둔 제안을 통째로 버렸다.
     */
    var onRetryOneByOne: (List<String>) -> Unit = {}

    /**
     * 사용자가 검토를 **접었다**(취소 버튼·뒤로가기·바깥 탭). 호출부가 보관 중인 결과를
     * 여기서 버린다 — 남겨 두면 다음 회전에 시트가 혼자 되살아난다.
     *
     * **회전은 이 길로 오지 않는다** — `onCancel`은 사용자가 취소한 경우에만 불린다.
     */
    var onDismissed: () -> Unit = {}

    private val checked = LinkedHashMap<String, MutableSet<String>>()
    private val rows = ArrayList<Row>()
    private val expandedPaths = LinkedHashSet<String>()

    /** 행 하나의 뷰와 상태 — 접기·필터가 이 목록을 훑어 다시 그린다. */
    private class Row(
        val suggestion: ImageBatchTagSuggester.ImageSuggestion,
        val root: View,
        val chipGroup: ChipGroup,
        val expandButton: MaterialButton
    ) {
        var expanded: Boolean = false
    }

    private var newOnly = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_image_ai_tag_review, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        restoreReviewState(savedInstanceState)
        // 리스너를 붙이기 **전에** 맞춘다 — 복원값을 나중에 넣으면 그 대입이 리스너를 깨워
        // 아직 그리지도 않은 행들을 훑는다.
        view.findViewById<Chip>(R.id.newOnlyChip).isChecked = newOnly
        bindStaticActions(view)
        render(view)
    }

    /**
     * 재생성 뒤 호출부가 제안을 다시 먹인다 — **체크 상태는 건드리지 않는다.**
     * 되받기 결과가 도착했을 때도 이 길로 들어온다(합친 결과가 통째로 다시 온다).
     */
    fun rebind(
        suggestions: List<ImageBatchTagSuggester.ImageSuggestion>,
        notices: List<String>,
        retryPaths: List<String>,
        groupSizeByPath: Map<String, Int> = emptyMap()
    ) {
        this.suggestions = suggestions
        this.notices = notices
        this.retryPaths = retryPaths
        this.groupSizeByPath = groupSizeByPath
        view?.let { render(it) }
    }

    /** 수명을 넘지 않는 배선 — 버튼의 클릭 처리는 뷰마다 한 번만 붙이면 된다. */
    private fun bindStaticActions(view: View) {
        view.findViewById<MaterialButton>(R.id.retryButton).setOnClickListener {
            // **닫지 않는다** — 이 시트가 들고 있는 제안이 곧 이미 결제한 몫이다(B-140).
            onRetryOneByOne(retryPaths)
        }
        view.findViewById<MaterialButton>(R.id.applyButton).setOnClickListener {
            onApply(checked.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() })
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
            onDismissed()
            dismiss()
        }
        view.findViewById<Chip>(R.id.newOnlyChip).setOnCheckedChangeListener { _, isChecked ->
            newOnly = isChecked
            for (row in rows) bindRow(row)
            updateHiddenNotice(view)
        }
        val toggleAll = view.findViewById<MaterialButton>(R.id.toggleAllButton)
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
    }

    /** 제안에서 나오는 것 전부를 다시 그린다 — 첫 표시와 재생성·되받기 도착이 같은 길을 쓴다. */
    private fun render(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.imageList)
        val noticeView = view.findViewById<TextView>(R.id.noticeText)
        val emptyView = view.findViewById<TextView>(R.id.emptyText)
        val toolRow = view.findViewById<View>(R.id.toolRow)
        val applyButton = view.findViewById<MaterialButton>(R.id.applyButton)
        val retryButton = view.findViewById<MaterialButton>(R.id.retryButton)
        val toggleAll = view.findViewById<MaterialButton>(R.id.toggleAllButton)

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

        rows.clear()
        list.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (s in suggestions) {
            val row = inflater.inflate(R.layout.item_image_tag_review, list, false)
            val name = s.path.substringAfterLast('/')
            // 묶음 단위 실행이면 이 행의 태그가 몇 장에 붙는지 파일명 옆에 적는다.
            row.findViewById<TextView>(R.id.fileName).text = groupSizeByPath[s.path]
                ?.let { getString(R.string.image_ai_tag_review_group, name, it) } ?: name
            row.findViewById<ImageView>(R.id.thumbnail).loadCharacterThumbnail(
                s.path, viewLifecycleOwner.lifecycleScope, reqPx = THUMB_PX
            )
            val group = row.findViewById<ChipGroup>(R.id.tagChipGroup)
            val expand = row.findViewById<MaterialButton>(R.id.expandButton)

            // **처음 보는 경로만 전부 체크한다**(원칙 04 — 훑어 지우는 편이 짧다).
            // 이미 아는 경로에 다시 부으면 회전·되받기 때마다 **사용자가 지운 체크가 되살아난다.**
            val fresh = s.path !in checked
            val picked = checked.getOrPut(s.path) { LinkedHashSet() }
            if (fresh) picked.addAll(s.tags.map { it.tag })

            val entry = Row(s, row, group, expand)
            entry.expanded = s.path in expandedPaths
            expand.setOnClickListener {
                entry.expanded = !entry.expanded
                if (entry.expanded) expandedPaths.add(s.path) else expandedPaths.remove(s.path)
                bindRow(entry)
                updateCount(view)
            }
            rows.add(entry)
            bindRow(entry)
            list.addView(row)
        }

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

    /**
     * 사용자가 접었다 — 보관 중인 결과를 버리라고 알린다.
     * **회전은 여기 오지 않는다**(`onCancel`은 뒤로가기·바깥 탭에서만 불린다).
     */
    override fun onCancel(dialog: DialogInterface) {
        onDismissed()
        super.onCancel(dialog)
    }

    /**
     * 검토 상태를 지킨다 — 제안은 ViewModel이 들지만 **어느 칩을 껐는지는 여기밖에 모른다.**
     * 이것이 없으면 회전 뒤 시트가 되살아나도 훑어 지운 일이 전부 되돌아간다(B-136).
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val picked = Bundle()
        for ((path, tags) in checked) picked.putStringArrayList(path, ArrayList(tags))
        outState.putBundle(STATE_CHECKED, picked)
        outState.putBoolean(STATE_NEW_ONLY, newOnly)
        outState.putStringArrayList(STATE_EXPANDED, ArrayList(expandedPaths))
    }

    private fun restoreReviewState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        newOnly = state.getBoolean(STATE_NEW_ONLY, false)
        expandedPaths.clear()
        expandedPaths.addAll(state.getStringArrayList(STATE_EXPANDED).orEmpty())
        checked.clear()
        val picked = state.getBundle(STATE_CHECKED) ?: return
        for (path in picked.keySet()) {
            checked[path] = LinkedHashSet(picked.getStringArrayList(path).orEmpty())
        }
    }

    companion object {
        const val TAG = "ImageAiTagReviewSheet"
        private const val THUMB_PX = 128
        private const val STATE_CHECKED = "checkedTags"
        private const val STATE_NEW_ONLY = "newOnly"
        private const val STATE_EXPANDED = "expandedPaths"
    }
}
