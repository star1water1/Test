package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.ItemUniverseBinding

class UniverseAdapter(
    private val onClick: (Universe) -> Unit,
    private val onLongClick: (Universe) -> Unit,
    private val onFieldManageClick: (Universe) -> Unit
) : ListAdapter<Universe, UniverseAdapter.UniverseViewHolder>(UniverseDiffCallback()) {

    private var novelCounts: Map<Long, Int> = emptyMap()
    private var fieldCounts: Map<Long, Int> = emptyMap()

    fun updateNovelCounts(counts: Map<Long, Int>) {
        novelCounts = counts
        for (i in 0 until itemCount) {
            notifyItemChanged(i)
        }
    }

    fun updateFieldCounts(counts: Map<Long, Int>) {
        fieldCounts = counts
        for (i in 0 until itemCount) {
            notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UniverseViewHolder {
        val binding = ItemUniverseBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return UniverseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UniverseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UniverseViewHolder(
        private val binding: ItemUniverseBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(universe: Universe) {
            binding.universeName.text = universe.name
            binding.universeDescription.text = universe.description.ifEmpty { "설명 없음" }

            val nc = novelCounts[universe.id] ?: 0
            val fc = fieldCounts[universe.id] ?: 0
            binding.novelCount.text = "작품 ${nc}개"
            binding.fieldCount.text = "필드 ${fc}개"

            binding.root.setOnClickListener { onClick(universe) }
            binding.root.setOnLongClickListener {
                onLongClick(universe)
                true
            }
            binding.btnFieldManage.setOnClickListener { onFieldManageClick(universe) }
        }
    }

    class UniverseDiffCallback : DiffUtil.ItemCallback<Universe>() {
        override fun areItemsTheSame(oldItem: Universe, newItem: Universe) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Universe, newItem: Universe) = oldItem == newItem
    }
}
