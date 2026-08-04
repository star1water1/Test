package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.databinding.ItemDuelAxisBinding

/**
 * 대결 축 목록 (B-104 화면 계층).
 *
 * 요약 줄은 **판 수와 상성 건수**다 — 목록에서 *"이 축은 얼마나 쌓였고 볼 것이 있는가"*가
 * 바로 보여야 일일이 열어 보지 않는다(원칙 04).
 */
class DuelAxisAdapter(
    private val onClick: (DuelAxis) -> Unit,
    private val onEdit: (DuelAxis) -> Unit,
    private val onDelete: (DuelAxis) -> Unit,
    private val onStandings: (DuelAxis) -> Unit,
    private val onMatches: (DuelAxis) -> Unit
) : ListAdapter<DuelAxis, DuelAxisAdapter.AxisViewHolder>(DiffCallback()) {

    /** 축 id → 요약 문구. 판 수 집계가 비동기라 목록보다 늦게 온다. */
    private var summaries: Map<Long, String> = emptyMap()

    fun updateSummaries(values: Map<Long, String>) {
        summaries = values
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AxisViewHolder =
        AxisViewHolder(ItemDuelAxisBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: AxisViewHolder, position: Int) = holder.bind(getItem(position))

    inner class AxisViewHolder(
        private val binding: ItemDuelAxisBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(axis: DuelAxis) {
            binding.axisName.text = axis.name
            binding.axisSummary.text = summaries[axis.id]
                ?: binding.root.context.getString(R.string.duel_axis_summary_loading)

            binding.root.setOnClickListener { onClick(axis) }
            binding.btnAxisMenu.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, R.string.duel_standings)
                popup.menu.add(0, 2, 1, R.string.duel_matches_title)
                popup.menu.add(0, 3, 2, R.string.menu_edit)
                popup.menu.add(0, 4, 3, R.string.menu_delete)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onStandings(axis); true }
                        2 -> { onMatches(axis); true }
                        3 -> { onEdit(axis); true }
                        4 -> { onDelete(axis); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DuelAxis>() {
        override fun areItemsTheSame(oldItem: DuelAxis, newItem: DuelAxis) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DuelAxis, newItem: DuelAxis) = oldItem == newItem
    }
}
