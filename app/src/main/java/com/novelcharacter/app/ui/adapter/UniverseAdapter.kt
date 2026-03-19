package com.novelcharacter.app.ui.adapter

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
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
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.ItemUniverseBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UniverseAdapter(
    private val onClick: (Universe) -> Unit,
    private val onEditClick: (Universe) -> Unit,
    private val onDeleteClick: (Universe) -> Unit,
    private val onFieldManageClick: (Universe) -> Unit,
    var onOrderChanged: ((List<Universe>) -> Unit)? = null
) : ListAdapter<Universe, UniverseAdapter.UniverseViewHolder>(UniverseDiffCallback()) {

    private var novelCounts: Map<Long, Int> = emptyMap()
    private var fieldCounts: Map<Long, Int> = emptyMap()
    private var isReorderMode = false
    var itemTouchHelper: ItemTouchHelper? = null
    private val reorderList = mutableListOf<Universe>()

    /** 세계관에 속한 캐릭터의 랜덤 이미지 경로를 반환하는 콜백 */
    var resolveRandomCharacterImage: ((universeId: Long, callback: (String?) -> Unit) -> Unit)? = null

    fun updateNovelCounts(counts: Map<Long, Int>) {
        novelCounts = counts
        notifyItemRangeChanged(0, itemCount)
    }

    fun updateFieldCounts(counts: Map<Long, Int>) {
        fieldCounts = counts
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
        if (from < 0 || to < 0 || from >= reorderList.size || to >= reorderList.size) return
        val item = reorderList.removeAt(from)
        reorderList.add(to, item)
        notifyItemMoved(from, to)
    }

    /** 드래그 완료 시 호출 — 즉시 자동 저장 */
    fun onDragCompleted() {
        if (isReorderMode) {
            onOrderChanged?.invoke(getReorderedList())
        }
    }

    fun getReorderedList(): List<Universe> = reorderList.mapIndexed { index, universe ->
        universe.copy(displayOrder = index.toLong())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UniverseViewHolder {
        val binding = ItemUniverseBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return UniverseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UniverseViewHolder, position: Int) {
        val item = if (isReorderMode && position < reorderList.size) reorderList[position] else getItem(position)
        holder.bind(item)
    }

    override fun getItemCount(): Int {
        return if (isReorderMode) reorderList.size else super.getItemCount()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class UniverseViewHolder(
        private val binding: ItemUniverseBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(universe: Universe) {
            binding.universeName.text = universe.name
            binding.universeDescription.text = universe.description.ifEmpty { binding.root.context.getString(R.string.no_description) }

            val nc = novelCounts[universe.id] ?: 0
            val fc = fieldCounts[universe.id] ?: 0
            binding.novelCount.text = binding.root.context.getString(R.string.novel_count_format, nc)
            binding.fieldCount.text = binding.root.context.getString(R.string.field_count_format, fc)

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

            binding.root.setOnClickListener { onClick(universe) }
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false

            binding.btnMore.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, R.string.menu_edit)
                popup.menu.add(0, 2, 1, R.string.menu_delete)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onEditClick(universe); true }
                        2 -> { onDeleteClick(universe); true }
                        else -> false
                    }
                }
                popup.show()
            }

            binding.btnFieldManage.setOnClickListener { onFieldManageClick(universe) }

            // 이미지 표시
            loadUniverseImage(universe)

            // Apply custom border color (Sprint D)
            val card = binding.root as? MaterialCardView
            if (card != null) {
                if (universe.borderColor.isNotBlank()) {
                    try {
                        card.strokeColor = Color.parseColor(universe.borderColor)
                        card.strokeWidth = (universe.borderWidthDp * binding.root.context.resources.displayMetrics.density).toInt()
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

        private fun loadUniverseImage(universe: Universe) {
            when (universe.imageMode) {
                Universe.IMAGE_MODE_CUSTOM -> {
                    if (universe.imagePath.isNotBlank()) {
                        val imageView = binding.universeImage
                        CoroutineScope(Dispatchers.IO).launch {
                            val file = File(universe.imagePath)
                            if (file.exists()) {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                                withContext(Dispatchers.Main) {
                                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                        imageView.setImageBitmap(bmp)
                                    }
                                }
                            }
                        }
                    } else {
                        binding.universeImage.setImageResource(R.drawable.ic_universe)
                    }
                }
                Universe.IMAGE_MODE_RANDOM_CHARACTER -> {
                    binding.universeImage.setImageResource(R.drawable.ic_universe)
                    resolveRandomCharacterImage?.invoke(universe.id) { imagePath ->
                        if (imagePath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val file = File(imagePath)
                                if (file.exists()) {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                                    withContext(Dispatchers.Main) {
                                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                            binding.universeImage.setImageBitmap(bmp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    binding.universeImage.setImageResource(R.drawable.ic_universe)
                }
            }
        }
    }

    class UniverseDiffCallback : DiffUtil.ItemCallback<Universe>() {
        override fun areItemsTheSame(oldItem: Universe, newItem: Universe) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Universe, newItem: Universe) = oldItem == newItem
    }
}
