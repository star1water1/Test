package com.novelcharacter.app.ui.adapter

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import android.graphics.Typeface
import com.google.android.material.card.MaterialCardView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.ItemUniverseBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UniverseAdapter(
    private val coroutineScope: CoroutineScope,
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

    // 이미지 캐시 — CharacterAdapter 패턴
    private val thumbnailCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 16
        object : LruCache<String, Bitmap>(cacheSize.coerceIn(512, 10 * 1024)) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
    }

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

    fun onDragCompleted() {
        if (isReorderMode) {
            onOrderChanged?.invoke(getReorderedList())
        }
    }

    fun getReorderedList(): List<Universe> = reorderList.mapIndexed { index, universe ->
        universe.copy(displayOrder = index.toLong())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UniverseViewHolder {
        val binding = ItemUniverseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UniverseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UniverseViewHolder, position: Int) {
        val item = if (isReorderMode && position < reorderList.size) reorderList[position] else getItem(position)
        holder.bind(item)
    }

    override fun getItemCount(): Int = if (isReorderMode) reorderList.size else super.getItemCount()

    override fun onViewRecycled(holder: UniverseViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        thumbnailCache.evictAll()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class UniverseViewHolder(
        private val binding: ItemUniverseBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(universe: Universe) {
            binding.universeName.text = universe.name
            if (universe.description.isNotEmpty()) {
                binding.universeDescription.text = universe.description
                binding.universeDescription.setTypeface(null, Typeface.NORMAL)
                binding.universeDescription.alpha = 1f
            } else {
                binding.universeDescription.text = binding.root.context.getString(R.string.no_description)
                binding.universeDescription.setTypeface(null, Typeface.ITALIC)
                binding.universeDescription.alpha = 0.6f
            }

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

            loadUniverseImage(universe)

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

        private fun loadUniverseImage(universe: Universe) {
            loadJob?.cancel()
            binding.universeImage.setImageResource(R.drawable.ic_universe)

            val imagePath = when (universe.imageMode) {
                Universe.IMAGE_MODE_CUSTOM -> universe.imagePath.takeIf { it.isNotBlank() }
                Universe.IMAGE_MODE_RANDOM_CHARACTER -> null // 콜백으로 처리
                else -> null
            }

            if (universe.imageMode == Universe.IMAGE_MODE_RANDOM_CHARACTER) {
                resolveRandomCharacterImage?.invoke(universe.id) { resolvedPath ->
                    if (resolvedPath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        loadImageFromPath(resolvedPath, universe.id)
                    }
                }
                return
            }

            if (imagePath != null) {
                loadImageFromPath(imagePath, universe.id)
            }
        }

        private fun loadImageFromPath(path: String, universeId: Long) {
            val cached = thumbnailCache.get(path)
            if (cached != null) {
                binding.universeImage.setImageBitmap(cached)
                return
            }

            loadJob = coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(path, 128, 128)
                }
                if (bitmap != null) {
                    thumbnailCache.put(path, bitmap)
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val current = if (isReorderMode && pos < reorderList.size) reorderList[pos] else getItem(pos)
                        if (current.id == universeId) {
                            binding.universeImage.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    class UniverseDiffCallback : DiffUtil.ItemCallback<Universe>() {
        override fun areItemsTheSame(oldItem: Universe, newItem: Universe) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Universe, newItem: Universe) = oldItem == newItem
    }
}
