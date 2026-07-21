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

        binding.typeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentType = when (checkedId) {
                binding.typeNovel.id -> ImageManagerViewModel.OwnerType.NOVEL
                binding.typeUniverse.id -> ImageManagerViewModel.OwnerType.UNIVERSE
                else -> ImageManagerViewModel.OwnerType.CHARACTER
            }
            loadRows()
        }
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { applyFilter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadRows()
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
