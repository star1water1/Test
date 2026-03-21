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
import com.google.android.material.card.MaterialCardView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.ItemNovelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NovelAdapter(
    private val coroutineScope: CoroutineScope,
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

    /** 작품별 커스텀 이미지 인덱스 (재바인드 시 동일 이미지 유지, 캐시 스래싱 방지) */
    private val imageIndexMap = mutableMapOf<Long, Int>()

    /** 이미지 표시를 랜덤으로 재설정 (목록 새로고침 시 호출) */
    fun refreshRandomImages() {
        imageIndexMap.clear()
    }

    private val thumbnailCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 16
        object : LruCache<String, Bitmap>(cacheSize.coerceIn(512, 10 * 1024)) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
    }

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

    fun onDragCompleted() {
        if (isReorderMode) {
            onOrderChanged?.invoke(getReorderedList())
        }
    }

    fun getReorderedList(): List<Novel> = reorderList.mapIndexed { index, novel ->
        novel.copy(displayOrder = index.toLong())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovelViewHolder {
        val binding = ItemNovelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NovelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NovelViewHolder, position: Int) {
        val item = if (isReorderMode && position < reorderList.size) reorderList[position] else getItem(position)
        holder.bind(item)
    }

    override fun getItemCount(): Int = if (isReorderMode) reorderList.size else super.getItemCount()

    override fun onViewRecycled(holder: NovelViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
        holder.binding.novelImage.setImageDrawable(null)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        thumbnailCache.evictAll()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class NovelViewHolder(
        val binding: ItemNovelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

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

            val card = binding.root as? MaterialCardView
            if (card != null) {
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

        private fun loadNovelImage(novel: Novel) {
            loadJob?.cancel()
            binding.novelImage.visibility = View.GONE
            binding.novelImage.setImageDrawable(null)

            when (novel.imageMode) {
                Novel.IMAGE_MODE_CUSTOM -> {
                    val paths = parseImagePaths(novel.imagePaths)
                    if (paths.isNotEmpty()) {
                        val idx = imageIndexMap.getOrPut(novel.id) { (0 until paths.size).random() }
                        binding.novelImage.visibility = View.VISIBLE
                        loadImageFromPath(paths[idx % paths.size], novel.id)
                    }
                }
                Novel.IMAGE_MODE_RANDOM_CHARACTER, Novel.IMAGE_MODE_SELECT_CHARACTER -> {
                    val charId = if (novel.imageMode == Novel.IMAGE_MODE_SELECT_CHARACTER) novel.imageCharacterId else null
                    resolveCharacterImage?.invoke(novel.id, charId) { resolvedPath ->
                        if (resolvedPath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            binding.novelImage.visibility = View.VISIBLE
                            loadImageFromPath(resolvedPath, novel.id)
                        }
                    }
                }
            }
        }

        private fun loadImageFromPath(path: String, novelId: Long) {
            val cached = thumbnailCache.get(path)
            if (cached != null) {
                binding.novelImage.setImageBitmap(cached)
                return
            }

            loadJob = coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(path, 192, 192)
                }
                if (bitmap != null) {
                    thumbnailCache.put(path, bitmap)
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val current = if (isReorderMode && pos < reorderList.size) reorderList[pos] else getItem(pos)
                        if (current.id == novelId) {
                            binding.novelImage.setImageBitmap(bitmap)
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

    private fun parseImagePaths(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    class NovelDiffCallback : DiffUtil.ItemCallback<Novel>() {
        override fun areItemsTheSame(oldItem: Novel, newItem: Novel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Novel, newItem: Novel) = oldItem == newItem
    }
}
