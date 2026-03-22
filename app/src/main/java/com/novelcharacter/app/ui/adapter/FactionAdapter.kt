package com.novelcharacter.app.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.databinding.ItemFactionBinding

class FactionAdapter(
    private val onClick: (Faction) -> Unit,
    private val onLongClick: (Faction) -> Unit
) : ListAdapter<Faction, FactionAdapter.FactionViewHolder>(FactionDiffCallback()) {

    private var memberCounts: Map<Long, Int> = emptyMap()

    fun updateMemberCounts(counts: Map<Long, Int>) {
        memberCounts = counts
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FactionViewHolder {
        val binding = ItemFactionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FactionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FactionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FactionViewHolder(
        private val binding: ItemFactionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(faction: Faction) {
            binding.factionName.text = faction.name
            binding.factionDescription.text = faction.description.ifEmpty { faction.autoRelationType }

            val count = memberCounts[faction.id] ?: 0
            binding.factionMemberCount.text = "${count}명"

            // 색상 인디케이터
            try {
                val color = Color.parseColor(faction.color)
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                binding.factionColorIndicator.background = bg
            } catch (_: Exception) {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2196F3"))
                }
                binding.factionColorIndicator.background = bg
            }

            binding.root.setOnClickListener { onClick(faction) }
            binding.root.setOnLongClickListener {
                onLongClick(faction)
                true
            }
        }
    }

    class FactionDiffCallback : DiffUtil.ItemCallback<Faction>() {
        override fun areItemsTheSame(oldItem: Faction, newItem: Faction) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Faction, newItem: Faction) = oldItem == newItem
    }
}
