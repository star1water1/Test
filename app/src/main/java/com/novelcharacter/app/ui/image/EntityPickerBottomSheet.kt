package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.databinding.BottomSheetEntityPickerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 배정 대상 피커 — 유형(캐릭터/작품/세계관) 라디오 + 검색 + 목록.
 * 수백 명 캐릭터 스케일 대응(AlertDialog.setItems 선례는 검색이 없어 부적합).
 * 로더/콜백 주입식([ImageManagerViewModel.getAssignTargets] 연결).
 */
class EntityPickerBottomSheet : BottomSheetDialogFragment() {

    var loadTargets: (suspend (ImageManagerViewModel.OwnerType) -> List<ImageManagerViewModel.PickRow>)? = null
    var onPicked: ((ImageManagerViewModel.OwnerType, ImageManagerViewModel.PickRow) -> Unit)? = null

    /**
     * '새 캐릭터 만들기' — null이면 버튼을 감춘다.
     *
     * 목록은 [loadTargets]가 **이미 있는 것만** 싣는다. 그래서 아직 없는 캐릭터에 이미지를
     * 붙이려면 화면을 나가 캐릭터를 만들고 돌아와야 했다(사용자 지적 2026.08.02).
     * **캐릭터 유형일 때만** 보인다 — 작품·세계관은 이미지 탭에서 만드는 것이 자연스러운
     * 자리가 아니고, 지금 막힌 것도 캐릭터 축이다.
     */
    var onCreateNewCharacter: (() -> Unit)? = null

    /**
     * 유형을 하나로 못 박는다 — 라디오 줄이 사라지고 그 유형만 고른다 (B-124 ⓒ에서 추가).
     *
     * 이름은행의 **[사용 처리]**처럼 *"이 이름을 쓰는 **캐릭터**를 고른다"*가 이미 정해진
     * 자리에서는 유형 선택이 뜻을 갖지 않는다(작품·세계관을 고를 수 있으면 그것이 오히려
     * 오조작이다). **null이면 종전 그대로 셋 다 고를 수 있다** — 이미지 탭은 이 값을
     * 주지 않으므로 동작이 바뀌지 않는다.
     *
     * 이 시트를 재사용하는 것이 요점이다: `AlertDialog.setItems`는 **검색이 없어 부적합**
     * 하다는 판정이 이 파일 머리에 이미 적혀 있고, 목표 규모의 캐릭터는 6,420명이다.
     */
    var lockedType: ImageManagerViewModel.OwnerType? = null

    /**
     * 머리글 문구 교체 — null이면 종전의 이미지 배정 문구를 그대로 쓴다 (B-124 ⓒ에서 추가).
     *
     * [lockedType]과 짝이다: 유형을 못 박아 다른 화면이 이 시트를 빌리면 **제목이 그
     * 화면의 일을 말해야 한다.** 안 바꾸면 이름은행의 [사용 처리]가 *"이미지 배정 대상"*을
     * 띄운다 — 기능은 맞고 문구만 남의 것인, 눈으로만 잡히는 부류다.
     */
    var titleRes: Int? = null

    private var _binding: BottomSheetEntityPickerBinding? = null
    private val binding get() = _binding!!

    private var allRows: List<ImageManagerViewModel.PickRow> = emptyList()
    private var currentType = ImageManagerViewModel.OwnerType.CHARACTER
    private var loadJob: Job? = null
    private val adapter = RowAdapter { row ->
        val cb = onPicked
        dismiss()
        cb?.invoke(currentType, row)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetEntityPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (onPicked == null || loadTargets == null) { dismissAllowingStateLoss(); return }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        // 유형이 못 박혔으면 라디오 줄을 걷는다 — 고를 것이 없는 선택지는 마찰이다(원칙 04).
        lockedType?.let { locked ->
            currentType = locked
            binding.typeGroup.visibility = View.GONE
        }
        titleRes?.let { binding.titleText.setText(it) }

        binding.typeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentType = when (checkedId) {
                binding.typeNovel.id -> ImageManagerViewModel.OwnerType.NOVEL
                binding.typeUniverse.id -> ImageManagerViewModel.OwnerType.UNIVERSE
                else -> ImageManagerViewModel.OwnerType.CHARACTER
            }
            updateCreateButton()
            loadRows()
        }
        binding.createNewButton.setOnClickListener {
            val cb = onCreateNewCharacter
            dismiss()
            cb?.invoke()
        }
        updateCreateButton()
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { applyFilter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadRows()
    }

    /** 캐릭터 유형이고 호출부가 콜백을 준 경우에만 보인다. */
    private fun updateCreateButton() {
        val show = onCreateNewCharacter != null &&
            currentType == ImageManagerViewModel.OwnerType.CHARACTER
        binding.createNewButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadRows() {
        loadJob?.cancel()   // 유형을 빠르게 바꿀 때 이전 로드가 나중에 끝나 덮어쓰는 stale 방지
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            allRows = loadTargets?.invoke(currentType) ?: emptyList()
            if (_binding == null) return@launch
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = binding.searchEdit.text?.toString()?.trim()?.lowercase() ?: ""
        val rows = if (q.isEmpty()) allRows else allRows.filter {
            it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
        }
        adapter.submit(rows)
        binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 단순 2줄 행 어댑터 — 목록이 인메모리 필터라 DiffUtil 불요. */
    private class RowAdapter(
        private val onClick: (ImageManagerViewModel.PickRow) -> Unit
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        private var rows: List<ImageManagerViewModel.PickRow> = emptyList()

        fun submit(newRows: List<ImageManagerViewModel.PickRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
                )
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val title = TextView(ctx).apply { textSize = 15f; maxLines = 1 }
            val subtitle = TextView(ctx).apply { textSize = 12f; maxLines = 1; alpha = 0.7f }
            container.addView(title)
            container.addView(subtitle)
            return VH(container, title, subtitle)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.subtitle.text = row.subtitle
            holder.subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(row) }
        }

        class VH(root: View, val title: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(root)
    }

    companion object { const val TAG = "EntityPickerBottomSheet" }
}
