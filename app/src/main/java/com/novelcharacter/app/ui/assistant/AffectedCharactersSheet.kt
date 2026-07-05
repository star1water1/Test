package com.novelcharacter.app.ui.assistant

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemAffectedCharacterBinding
import com.novelcharacter.app.databinding.SheetAffectedCharactersBinding
import com.novelcharacter.app.ui.character.CharacterViewModel
import com.novelcharacter.app.util.loadCharacterThumbnail
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Job
import java.text.Collator
import java.util.Locale

/**
 * '전체 보기' 시트 — 집계 카드가 대표하는 **영향 캐릭터 전체**를 목록으로 펼친다(대량 데이터 대응).
 * 각 행: 탭→상세 이동, 교정 가능 행이면 그 자리에서 개별 교정 후 목록에서 제거.
 *
 * 회전 안전: 행 데이터는 arguments에 JSON으로 실어오고(람다 주입 X), 교정은 시트 자체
 * [CharacterViewModel]로 수행하며, 부모에는 dismiss 시 `setFragmentResult`로 **한 번만** 갱신 신호를 준다.
 * 교정으로 줄어든 `rows`와 `changed`는 [onSaveInstanceState]로 보존해 회전 후에도 진행/갱신 신호가 살아남는다.
 */
class AffectedCharactersSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAffectedCharactersBinding? = null
    private val binding get() = _binding!!

    // 교정용 — 정규 경로(검출/적용) 재사용. 시트 스코프라 짧게 살고 사라진다.
    private val cvm: CharacterViewModel by viewModels()

    private val rows = mutableListOf<AffectedRow>()
    private var changed = false
    private lateinit var rowAdapter: RowAdapter

    /** 정렬(비파괴): 보는 순서만 바꾼다. 캐릭터 목록 순서(displayOrder)엔 영향 없음. */
    private enum class SortMode { DEFAULT, NAME, RECENT, CREATED }
    private var sortMode = SortMode.DEFAULT
    /** '기본 순서' 기준 = provider 원래 순서. arguments 원본에서 고정(회전·교정과 무관하게 안정적). */
    private val arrivalIndex = HashMap<Long, Int>()
    private val nameComparator: Comparator<String> =
        Collator.getInstance(Locale.KOREAN).let { c -> Comparator { a, b -> c.compare(a, b) } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SheetAffectedCharactersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sheetTitle.text = requireArguments().getString(ARG_TITLE)

        // '기본 순서' 기준 = provider가 넣어준 원래 순서. arguments 원본에서 고정.
        val original = parseRows(requireArguments().getString(ARG_ROWS).orEmpty())
        arrivalIndex.clear()
        original.forEachIndexed { i, r -> arrivalIndex[r.characterId] = i }

        // 작업 목록: 회전 저장분(교정으로 줄어든 상태) 우선, 없으면 원본. 이렇게 안 하면 회전 시
        // 고친 행이 되살아나고 changed=false로 리셋돼 부모 refresh 신호가 유실된다.
        val working = savedInstanceState?.getString(STATE_ROWS)?.let(::parseRows) ?: original
        rows.clear()
        rows.addAll(working)
        changed = savedInstanceState?.getBoolean(STATE_CHANGED) ?: false
        sortMode = savedInstanceState?.getString(STATE_SORT)
            ?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.DEFAULT

        rowAdapter = RowAdapter()
        binding.affectedRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.affectedRecyclerView.adapter = rowAdapter
        binding.btnSheetSort.setOnClickListener { anchor -> showSortMenu(anchor) }
        applySort()   // 현재 sortMode대로 정렬 + 버튼 라벨 반영
        updateCount()
    }

    private fun parseRows(json: String): List<AffectedRow> = runCatching {
        Gson().fromJson<List<AffectedRow>>(json, object : TypeToken<List<AffectedRow>>() {}.type)
    }.getOrNull() ?: emptyList()

    /** 정렬 선택 메뉴 — 비파괴. 캐릭터 목록 순서는 건드리지 않는다. */
    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        SortMode.values().forEachIndexed { i, m -> popup.menu.add(0, i, i, sortLabelRes(m)) }
        popup.setOnMenuItemClickListener { item ->
            sortMode = SortMode.values()[item.itemId]
            applySort()
            true
        }
        popup.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applySort() {
        val cmp: Comparator<AffectedRow> = when (sortMode) {
            SortMode.DEFAULT -> compareBy { arrivalIndex[it.characterId] ?: Int.MAX_VALUE }
            SortMode.NAME -> compareBy(nameComparator) { it.name }
            // 최근 수정 우선(내림차순). updatedAt 없으면 뒤로, 동률은 이름순.
            SortMode.RECENT -> compareByDescending<AffectedRow> { it.updatedAt ?: Long.MIN_VALUE }
                .thenBy(nameComparator) { it.name }
            SortMode.CREATED -> compareBy { it.characterId }  // PK 증가분 ≈ 생성순
        }
        rows.sortWith(cmp)
        if (::rowAdapter.isInitialized) rowAdapter.notifyDataSetChanged()
        binding.btnSheetSort.setText(sortLabelRes(sortMode))
    }

    private fun sortLabelRes(m: SortMode): Int = when (m) {
        SortMode.DEFAULT -> R.string.assistant_sort_default
        SortMode.NAME -> R.string.assistant_sort_name
        SortMode.RECENT -> R.string.assistant_sort_recent
        SortMode.CREATED -> R.string.assistant_sort_created
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        // 대량 목록도 스크롤로 다룰 수 있게 높이 확보(화면의 90%).
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
        }
        sheet.requestLayout()
    }

    private fun updateCount() {
        binding.sheetCount.text = getString(R.string.assistant_affected_remaining, rows.size)
    }

    private fun openDetail(characterId: Long) {
        // 부모(AssistantFragment)의 NavController로 이동 후 시트 닫기 — 회전 안전.
        val nav = requireParentFragment().findNavController()
        dismiss()
        nav.navigateSafe(R.id.homeFragment, R.id.characterDetailFragment, bundleOf("characterId" to characterId))
    }

    private fun fixRow(row: AffectedRow) {
        when (row.fixType) {
            AffectedFixType.AGE_LINKAGE -> runAgeFix(cvm, row.characterId) { onRowDone(row.characterId) }
            AffectedFixType.ASSIGN_NOVEL -> runAssignNovel(cvm, row.characterId, row.name) { onRowDone(row.characterId) }
            null -> openDetail(row.characterId)
        }
    }

    /** 교정 완료(또는 이미 해결) → 해당 행 제거, 비면 시트 종료. characterId로 찾아 위치 이동에 안전. */
    private fun onRowDone(characterId: Long) {
        changed = true
        val idx = rows.indexOfFirst { it.characterId == characterId }
        if (idx >= 0) {
            rows.removeAt(idx)
            rowAdapter.notifyItemRemoved(idx)
            updateCount()
        }
        if (rows.isEmpty()) dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 교정으로 줄어든 현재 rows·changed·정렬 보존 → 회전 후에도 진행 상태와 refresh 신호·정렬 유지.
        outState.putString(STATE_ROWS, Gson().toJson(rows))
        outState.putBoolean(STATE_CHANGED, changed)
        outState.putString(STATE_SORT, sortMode.name)
    }

    override fun onDismiss(dialog: DialogInterface) {
        // 교정이 하나라도 있었으면 부모가 한 번만 새로고침(행마다 재로딩 방지).
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_CHANGED to changed))
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        binding.affectedRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH =
            RowVH(ItemAffectedCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size
        override fun onBindViewHolder(holder: RowVH, position: Int) = holder.bind(rows[position])
        override fun onViewRecycled(holder: RowVH) = holder.recycle()
    }

    private inner class RowVH(private val b: ItemAffectedCharacterBinding) : RecyclerView.ViewHolder(b.root) {
        private var thumbJob: Job? = null

        fun recycle() {
            thumbJob?.cancel()
            thumbJob = null
        }

        fun bind(row: AffectedRow) {
            b.nameText.text = row.name
            // 캐릭터 일러스트(있으면) — 앱 공용 유틸로 재활용-안전 로드, 재활용 시 취소.
            thumbJob?.cancel()
            thumbJob = b.thumbnail.loadCharacterThumbnail(
                row.imagePath, lifecycleScope,
                isValid = { bindingAdapterPosition != RecyclerView.NO_POSITION }
            )
            b.subtitleText.visibility = if (row.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            b.subtitleText.text = row.subtitle
            b.root.contentDescription = row.name
            b.root.setOnClickListener { openDetail(row.characterId) }

            if (row.fixType != null) {
                b.fixButton.visibility = View.VISIBLE
                b.fixButton.setText(
                    if (row.fixType == AffectedFixType.AGE_LINKAGE) R.string.assistant_action_fix
                    else R.string.assistant_action_assign_novel
                )
                b.fixButton.setOnClickListener { fixRow(row) }
            } else {
                b.fixButton.visibility = View.GONE
                b.fixButton.setOnClickListener(null)
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "affected_sheet_result"
        const val RESULT_CHANGED = "changed"
        const val TAG = "AffectedCharactersSheet"
        private const val ARG_TITLE = "title"
        private const val ARG_ROWS = "rows"
        private const val STATE_ROWS = "state_rows"
        private const val STATE_CHANGED = "state_changed"
        private const val STATE_SORT = "state_sort"

        fun newInstance(title: String, rows: List<AffectedRow>): AffectedCharactersSheet =
            AffectedCharactersSheet().apply {
                arguments = bundleOf(ARG_TITLE to title, ARG_ROWS to Gson().toJson(rows))
            }
    }
}
