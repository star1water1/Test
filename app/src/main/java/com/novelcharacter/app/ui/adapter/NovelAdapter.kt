package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.ItemNovelBinding

class NovelAdapter(
    private val onClick: (Novel) -> Unit,
    private val onLongClick: (Novel) -> Unit
) : ListAdapter<Novel, NovelAdapter.NovelViewHolder>(NovelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovelViewHolder {
        val binding = ItemNovelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NovelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NovelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NovelViewHolder(
        private val binding: ItemNovelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(novel: Novel) {
            binding.novelTitle.text = novel.title
            binding.novelDescription.text = novel.description.ifEmpty { binding.root.context.getString(com.novelcharacter.app.R.string.no_description) }

            binding.root.setOnClickListener { onClick(novel) }
            binding.root.setOnLongClickListener {
                onLongClick(novel)
                true
            }
        }
    }

    class NovelDiffCallback : DiffUtil.ItemCallback<Novel>() {
        override fun areItemsTheSame(oldItem: Novel, newItem: Novel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Novel, newItem: Novel) = oldItem == newItem
    }
}
