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
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.ItemNovelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NovelAdapter(
    private val onClick: (Novel) -> Unit,
    private val onEditClick: (Novel) -> Unit,
    private val onDeleteClick: (Novel) -> Unit,
    private val onPinClick: ((Novel) -> Unit)? = null,
    var onOrderChanged: ((List<Novel>) -> Unit)? = null
) : ListAdapter<Novel, NovelAdapter.NovelViewHolder>(NovelDiffCallback()) {

    private var isReorderMode = false
    var itemTouchHelper: ItemTouchHelper? = null
    private val reorderList = mutableListOf<Novel>()
    private var universeBorderColor: String = ""
    private var universeBorderWidthDp: Float = 1.5f

    /** 작품에 속한 캐릭터의 랜덤/선택 이미지 경로를 반환하는 콜백 */
    var resolveCharacterImage: ((novelId: Long, characterId: Long?, callback: (String?) -> Unit) -> Unit)? = null

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
            binding.novelTitle.text = if (novel.isPinned) {
                binding.root.context.getString(R.string.pinned_name_format, novel.title)
            } else {
                novel.title
            }
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

            // 이미지 표시
            loadNovelImage(novel)

            binding.root.setOnClickListener { onClick(novel) }
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false

            binding.btnMore.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                val pinLabel = if (novel.isPinned) R.string.unpin else R.string.pin
                popup.menu.add(0, 3, 0, pinLabel)
                popup.menu.add(0, 1, 1, R.string.menu_edit)
                popup.menu.add(0, 2, 2, R.string.menu_delete)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        3 -> { onPinClick?.invoke(novel); true }
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

        private fun loadNovelImage(novel: Novel) {
            when (novel.imageMode) {
                Novel.IMAGE_MODE_CUSTOM -> {
                    if (novel.imagePath.isNotBlank()) {
                        binding.novelImage.visibility = View.VISIBLE
                        CoroutineScope(Dispatchers.IO).launch {
                            val file = File(novel.imagePath)
                            if (file.exists()) {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                                withContext(Dispatchers.Main) {
                                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                        binding.novelImage.setImageBitmap(bmp)
                                    }
                                }
                            }
                        }
                    } else {
                        binding.novelImage.visibility = View.GONE
                    }
                }
                Novel.IMAGE_MODE_RANDOM_CHARACTER, Novel.IMAGE_MODE_SELECT_CHARACTER -> {
                    binding.novelImage.visibility = View.GONE
                    val charId = if (novel.imageMode == Novel.IMAGE_MODE_SELECT_CHARACTER) novel.imageCharacterId else null
                    resolveCharacterImage?.invoke(novel.id, charId) { imagePath ->
                        if (imagePath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val file = File(imagePath)
                                if (file.exists()) {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                                    withContext(Dispatchers.Main) {
                                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                            binding.novelImage.visibility = View.VISIBLE
                                            binding.novelImage.setImageBitmap(bmp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    binding.novelImage.visibility = View.GONE
                }
            }
        }
    }

    class NovelDiffCallback : DiffUtil.ItemCallback<Novel>() {
        override fun areItemsTheSame(oldItem: Novel, newItem: Novel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Novel, newItem: Novel) = oldItem == newItem
    }
}
