package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetImageLibraryPickerBinding
import com.novelcharacter.app.databinding.ItemManagedImageBinding
import com.novelcharacter.app.util.ImageFilterHelper
import com.novelcharacter.app.util.LibraryPickerRow
import com.novelcharacter.app.util.LibraryPickerRows
import com.novelcharacter.app.util.LinkGroupFold
import com.novelcharacter.app.util.loadCharacterThumbnail
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job

/**
 * 라이브러리에서 이미지를 골라 캐릭터에 붙이는 피커.
 *
 * **왜 생겼는가:** 편집창에서 라이브러리로 가는 문이 추천 스트립 하나뿐이었고, 그 문은
 * *캐릭터 태그와 이미지 태그가 정확히 겹칠 때만* 열린다(교집합 0이면 섹션이 통째로 숨는다).
 * 즉 **태그를 쓰지 않는 사용자에게는 라이브러리가 없는 것과 같았다.** 이 피커는 태그와
 * 무관하게 항상 열리는 두 번째 경로이고, 추천 스트립은 그대로 둔다
 * (원칙 04의 이중 경로 — 러프하게 훑기 / 태그로 좁혀 받기).
 *
 * **선별은 [ImageFilterHelper]가 한다 — 여기서 다시 짜지 않는다.** 이미지 탭이 쓰는 그
 * 규칙을 그대로 쓰므로 "탭에서 검색되던 것이 피커에서는 안 나오는" 갈림이 생기지 않는다.
 *
 * 호출부 계약: [images]·[onConfirm]을 세팅한 뒤 show한다. 되돌려 주는 것은 **고른 경로들**이며,
 * 링크 그룹 확장·중복 제거·실제 첨부는 **호출부의 몫**이다(추천 첨부와 같은 자리를 지나야
 * 두 경로의 결과가 갈리지 않는다).
 */
class ImageLibraryPickerBottomSheet : BottomSheetDialogFragment() {

    var images: List<LibraryPickerRow> = emptyList()

    /** 이미 붙어 있는 경로(canonical 비교는 호출부가 맞춰 넣는다) — 목록에서 뺀다. */
    var excludePaths: Set<String> = emptySet()

    var onConfirm: ((List<String>) -> Unit)? = null

    private var _binding: BottomSheetImageLibraryPickerBinding? = null
    private val binding get() = _binding!!

    private val selected = LinkedHashSet<String>()

    /**
     * 링크 묶음은 대표 한 칸으로 접힌다([LinkGroupFold] — 이미지 탭 묶어 보기와 같은 규칙).
     *
     * 피커에서 접는 것이 **정직한 표시다**: 첨부는 어차피 묶음 전원으로 확장된다
     * (호출부 계약 — `CharacterEditFragment.attachLibraryImages`가 `ImageLinkResolver.expand`를
     * 지난다). 장마다 펼쳐 보이면 한 장만 고른 사용자가 여러 장이 붙는 것을 첨부 뒤에야 안다.
     */
    private var shown: List<LinkGroupFold.Stack<LibraryPickerRow>> = emptyList()
    private val adapter = RowAdapter()

    // 라이브러리 **전체** 기준의 묶음 지도 — 배지·선택 수가 이것으로 센다. 화면(필터 통과분)
    // 기준으로 세면 검색어·'미배정만' 칩이 가린 식구가 수에서 빠지는데, 첨부는 그 식구까지
    // 확장되므로(호출부의 ImageLinkResolver.expand) 화면이 실제보다 적게 약속하게 된다.
    private var fullMembersByGroup: Map<String, List<String>> = emptyMap()
    private var groupByPath: Map<String, String> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageLibraryPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 회전 등으로 콜백이 사라지면 조용한 무동작이 된다 — 그 상태로 남기지 않는다
        // (`SearchFilterBottomSheet`의 콜백 유실이 같은 부류로 색출된 적이 있다).
        if (onConfirm == null) { dismissAllowingStateLoss(); return }

        fullMembersByGroup = images.filter { it.linkGroupId != null }
            .groupBy({ it.linkGroupId!! }, { it.path })
            .filterValues { it.size > 1 }
        groupByPath = images.mapNotNull { row ->
            row.linkGroupId?.takeIf { it in fullMembersByGroup }?.let { row.path to it }
        }.toMap()

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

        binding.searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refresh()
        })
        binding.chipUnassignedOnly.setOnCheckedChangeListener { _, _ -> refresh() }

        binding.cancelButton.setOnClickListener { dismiss() }
        binding.confirmButton.setOnClickListener {
            val cb = onConfirm
            val picked = selected.toList()
            dismiss()
            cb?.invoke(picked)
        }

        refresh()
        updateSelectionUi()
    }

    private fun refresh() {
        if (_binding == null) return
        val criteria = ImageFilterHelper.Criteria(
            base = if (binding.chipUnassignedOnly.isChecked) {
                ImageFilterHelper.BaseFilter.UNASSIGNED
            } else {
                ImageFilterHelper.BaseFilter.ALL
            },
            query = binding.searchEdit.text?.toString().orEmpty()
        )
        shown = LinkGroupFold.fold(
            LibraryPickerRows.visible(images, criteria, excludePaths)
        ) { it.linkGroupId }
        adapter.notifyDataSetChanged()
        binding.emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateSelectionUi() {
        if (_binding == null) return
        val count = expandedSelectionCount()
        binding.selectionCountText.text =
            if (count == 0) "" else getString(R.string.image_library_picker_selected, count)
        binding.confirmButton.isEnabled = selected.isNotEmpty()
    }

    /**
     * 실제로 붙을 장수 — 고른 경로를 **라이브러리 전체 기준** 묶음으로 넓힌 뒤, 이미 붙어
     * 있는 것(excludePaths)을 뺀다. 화면의 칸 수로 세면 필터가 가린 식구가 빠지는데,
     * 첨부는 그 식구까지 간다 — 수가 다르면 이 창의 약속이 첨부 뒤에 깨진다.
     */
    private fun expandedSelectionCount(): Int {
        if (selected.isEmpty()) return 0
        val expanded = LinkedHashSet(selected)
        for (path in selected) {
            groupByPath[path]?.let { g -> expanded.addAll(fullMembersByGroup[g].orEmpty()) }
        }
        return expanded.count { it !in excludePaths }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(val b: ItemManagedImageBinding) : RecyclerView.ViewHolder(b.root) {
            /** 재활용 시 취소한다 — 안 하면 스크롤 중 엉뚱한 칸에 이전 썸네일이 들어간다. */
            var thumbJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemManagedImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onViewRecycled(holder: VH) {
            holder.thumbJob?.cancel()
            holder.thumbJob = null
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val stack = shown[position]
            val item = stack.representative
            val memberPaths = stack.members.map { it.path }
            val b = holder.b
            val ctx = b.root.context

            holder.thumbJob?.cancel()
            holder.thumbJob = b.thumbnail.loadCharacterThumbnail(
                item.path, viewLifecycleOwner.lifecycleScope, reqPx = 256,
                isValid = { holder.bindingAdapterPosition != RecyclerView.NO_POSITION }
            )

            // 배정된 것도 보여 주되(사용자 판정) **어디에 붙어 있는지 말한다** — 감추면
            // 같은 삽화를 둘이 쓰는 경우가 막히고, 말하지 않으면 중복 배정을 실수로 한다.
            if (item.isAssigned) {
                b.ownerText.visibility = View.VISIBLE
                b.ownerText.text = ctx.getString(
                    R.string.image_library_picker_owner, item.ownerNames.joinToString(", ")
                )
            } else {
                b.ownerText.visibility = View.VISIBLE
                b.ownerText.text = ctx.getString(R.string.image_library_picker_owner_none)
            }

            b.tagText.text = if (item.tags.isEmpty()) "" else item.tags.joinToString(" · ") { "#$it" }
            b.linkBadge.visibility = if (item.linkGroupId != null) View.VISIBLE else View.GONE
            if (item.linkGroupId != null) {
                // 장수는 **라이브러리 전체 기준**이다 — 필터가 식구를 가려도 첨부는 묶음
                // 전원으로 확장되므로, 보이는 수를 적으면 이 칸의 약속이 첨부 뒤에 깨진다.
                val fullSize = fullMembersByGroup[item.linkGroupId]?.size ?: 1
                b.linkBadge.text = if (fullSize > 1) {
                    ctx.getString(R.string.image_manager_stack_badge, fullSize)
                } else {
                    ctx.getString(R.string.image_link_badge)
                }
            }
            // 이 피커에는 크기·상태 배지를 싣지 않는다 — 고르는 판단에 쓰이지 않는다.
            b.sizeText.visibility = View.GONE
            b.statusBadge.visibility = View.GONE

            // 식구 중 하나라도 선택이면 표시가 선다 — 필터 변경으로 식구가 늘어 '전원 선택'이
            // 깨져도, 선택이 걸린 칸이 화면에서 무표시가 되면 확정이 어디에 닿는지 알 수 없다
            // (이미지 탭 그리드의 displaySelectedPaths와 같은 정책).
            val isSel = memberPaths.any { it in selected }
            b.selectionScrim.visibility = if (isSel) View.VISIBLE else View.GONE
            b.selectionCheck.visibility = if (isSel) View.VISIBLE else View.GONE

            b.root.setOnClickListener {
                // 접힌 칸의 탭은 묶음 전체를 토글한다 — 선택 수 표시도 실제 붙을 장수를 센다.
                if (memberPaths.all { it in selected }) {
                    selected.removeAll(memberPaths)
                } else {
                    selected.addAll(memberPaths)
                }
                notifyItemChanged(holder.bindingAdapterPosition)
                updateSelectionUi()
            }
        }
    }
}
