package com.novelcharacter.app.ui.adapter

import android.annotation.SuppressLint
import android.content.Context
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
    private val onFactionManageClick: (Universe) -> Unit = {},
    var onOrderChanged: ((List<Universe>) -> Unit)? = null
) : ListAdapter<Universe, UniverseAdapter.UniverseViewHolder>(UniverseDiffCallback()) {

    private var novelCounts: Map<Long, Int> = emptyMap()
    private var fieldCounts: Map<Long, Int> = emptyMap()
    private var isReorderMode = false
    var itemTouchHelper: ItemTouchHelper? = null
    private val reorderList = mutableListOf<Universe>()

    /** 세계관에 속한 캐릭터의 랜덤 이미지 경로를 반환하는 콜백 */
    var resolveRandomCharacterImage: ((universeId: Long, callback: (String?) -> Unit) -> Unit)? = null
    /** 특정 캐릭터의 이미지 경로를 반환하는 콜백 */
    var resolveCharacterImageById: ((characterId: Long, callback: (String?) -> Unit) -> Unit)? = null
    /** 세계관에 속한 작품의 랜덤 이미지 경로를 반환하는 콜백 */
    var resolveRandomNovelImage: ((universeId: Long, callback: (String?) -> Unit) -> Unit)? = null
    /** 특정 작품의 이미지 경로를 반환하는 콜백 */
    var resolveNovelImageById: ((novelId: Long, callback: (String?) -> Unit) -> Unit)? = null

    /** 세계관별 커스텀 이미지 인덱스 (영속 저장) */
    private val imageIndexMap = mutableMapOf<Long, Int>()
    private var prefsLoaded = false
    private companion object { const val ENTITY_TYPE = "universe" }

    /** 이미지 표시를 랜덤으로 재설정 (목록 새로고침 시 호출) */
    fun refreshRandomImages(context: android.content.Context? = null) {
        context?.let { com.novelcharacter.app.util.ImageIndexPrefs.clearAll(it, ENTITY_TYPE) }
        imageIndexMap.clear()
        prefsLoaded = false
    }

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
                popup.menu.add(0, 3, 2, R.string.faction_manage)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onEditClick(universe); true }
                        2 -> { onDeleteClick(universe); true }
                        3 -> { onFactionManageClick(universe); true }
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

            when (universe.imageMode) {
                Universe.IMAGE_MODE_CUSTOM -> {
                    val paths = parseImagePaths(universe.imagePaths)
                    if (paths.isNotEmpty()) {
                        val ctx = itemView.context
                        if (!prefsLoaded) {
                            imageIndexMap.putAll(com.novelcharacter.app.util.ImageIndexPrefs.loadAll(ctx, ENTITY_TYPE))
                            prefsLoaded = true
                        }
                        val idx = imageIndexMap.getOrPut(universe.id) {
                            val randomIdx = (0 until paths.size).random()
                            com.novelcharacter.app.util.ImageIndexPrefs.save(ctx, ENTITY_TYPE, universe.id, randomIdx)
                            randomIdx
                        }
                        loadImageFromPath(paths[idx % paths.size], universe.id)
                    }
                }
                Universe.IMAGE_MODE_RANDOM_CHARACTER -> {
                    resolveRandomCharacterImage?.invoke(universe.id) { resolvedPath ->
                        if (resolvedPath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            loadImageFromPath(resolvedPath, universe.id)
                        }
                    }
                }
                Universe.IMAGE_MODE_SELECT_CHARACTER -> {
                    val charId = universe.imageCharacterId
                    if (charId != null) {
                        resolveCharacterImageById?.invoke(charId) { resolvedPath ->
                            if (resolvedPath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                loadImageFromPath(resolvedPath, universe.id)
                            }
                        }
                    }
                }
                Universe.IMAGE_MODE_RANDOM_NOVEL -> {
                    resolveRandomNovelImage?.invoke(universe.id) { resolvedPath ->
                        if (resolvedPath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            loadImageFromPath(resolvedPath, universe.id)
                        }
                    }
                }
                Universe.IMAGE_MODE_SELECT_NOVEL -> {
                    val novelId = universe.imageNovelId
                    if (novelId != null) {
                        resolveNovelImageById?.invoke(novelId) { resolvedPath ->
                            if (resolvedPath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                loadImageFromPath(resolvedPath, universe.id)
                            }
                        }
                    }
                }
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
                    decodeSampledBitmap(binding.root.context, path, 192, 192)
                }
                if (bitmap != null) {
                    thumbnailCache.put(path, bitmap)
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        try {
                            val current = if (isReorderMode && pos < reorderList.size) reorderList[pos] else getItem(pos)
                            if (current.id == universeId) {
                                binding.universeImage.setImageBitmap(bitmap)
                            }
                        } catch (_: IndexOutOfBoundsException) {
                            // List may have been updated between position check and getItem
                        }
                    }
                }
            }
        }
    }

    private fun decodeSampledBitmap(context: Context, path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val file = File(path)
            val appDir = context.filesDir
            if (!file.canonicalPath.startsWith(appDir.canonicalPath + File.separator)) return null
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
            while (inSampleSize < 1024 && halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
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

    class UniverseDiffCallback : DiffUtil.ItemCallback<Universe>() {
        override fun areItemsTheSame(oldItem: Universe, newItem: Universe) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Universe, newItem: Universe) = oldItem == newItem
    }
}
