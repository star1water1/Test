package com.novelcharacter.app.ui.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import com.google.android.material.card.MaterialCardView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.ItemNovelBinding

class NovelAdapter(
    private val onClick: (Novel) -> Unit,
    private val onEditClick: (Novel) -> Unit,
    private val onDeleteClick: (Novel) -> Unit
) : ListAdapter<Novel, NovelAdapter.NovelViewHolder>(NovelDiffCallback()) {

    private var isReorderMode = false
    var itemTouchHelper: ItemTouchHelper? = null
    private val reorderList = mutableListOf<Novel>()
    private var universeBorderColor: String = ""
    private var universeBorderWidthDp: Float = 1.5f

    fun setUniverseBorder(color: String, widthDp: Float) {
        universeBorderColor = color
        universeBorderWidthDp = widthDp
        notifyItemRangeChanged(0, itemCount)
    }

    fun setReorderMode(enabled: Boolean) {
        isReorderMode = enabled
        if (enabled) {
            reorderList.clear()
            reorderList.addAll(currentList)
        }
        notifyDataSetChanged()
    }

    fun isReorderMode() = isReorderMode

    fun onItemMove(from: Int, to: Int) {
        val item = reorderList.removeAt(from)
        reorderList.add(to, item)
        notifyItemMoved(from, to)
    }

    fun getReorderedList(): List<Novel> = reorderList.mapIndexed { index, novel ->
        novel.copy(displayOrder = index.toLong())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovelViewHolder {
        val binding = ItemNovelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NovelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NovelViewHolder, position: Int) {
        val item = if (isReorderMode && position < reorderList.size) reorderList[position] else getItem(position)
        holder.bind(item)
    }

    override fun getItemCount(): Int {
        return if (isReorderMode) reorderList.size else super.getItemCount()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class NovelViewHolder(
        private val binding: ItemNovelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(novel: Novel) {
            binding.novelTitle.text = novel.title
            binding.novelDescription.text = novel.description.ifEmpty { binding.root.context.getString(R.string.no_description) }

            binding.dragHandle.visibility = if (isReorderMode) View.VISIBLE else View.GONE
            binding.btnMore.setImageResource(R.drawable.ic_more_vert)
            binding.btnMore.visibility = if (isReorderMode) View.GONE else View.VISIBLE

            if (isReorderMode) {
                binding.dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(this)
                    }
                    false
                }
            } else {
                binding.dragHandle.setOnTouchListener(null)
            }

            binding.root.setOnClickListener { onClick(novel) }
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false

            binding.btnMore.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, R.string.menu_edit)
                popup.menu.add(0, 2, 1, R.string.menu_delete)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onEditClick(novel); true }
                        2 -> { onDeleteClick(novel); true }
                        else -> false
                    }
                }
                popup.show()
            }

            // Apply custom border color (Sprint D)
            val card = binding.root as? MaterialCardView
            if (card != null) {
                // Determine effective border: own color, or inherit from universe
                val effectiveColor = if (novel.borderColor.isNotBlank()) {
                    novel.borderColor
                } else if (novel.inheritUniverseBorder && universeBorderColor.isNotBlank()) {
                    universeBorderColor
                } else ""

                val effectiveWidth = if (novel.borderColor.isNotBlank()) {
                    novel.borderWidthDp
                } else if (novel.inheritUniverseBorder && universeBorderColor.isNotBlank()) {
                    universeBorderWidthDp
                } else 0f

                if (effectiveColor.isNotBlank()) {
                    try {
                        card.strokeColor = Color.parseColor(effectiveColor)
                        card.strokeWidth = (effectiveWidth * binding.root.context.resources.displayMetrics.density).toInt()
                    } catch (_: Exception) {
                        card.strokeColor = 0
                        card.strokeWidth = 0
                    }
                } else {
                    card.strokeColor = 0
                    card.strokeWidth = 0
                }
            }
        }
    }

    class NovelDiffCallback : DiffUtil.ItemCallback<Novel>() {
        override fun areItemsTheSame(oldItem: Novel, newItem: Novel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Novel, newItem: Novel) = oldItem == newItem
    }
}
