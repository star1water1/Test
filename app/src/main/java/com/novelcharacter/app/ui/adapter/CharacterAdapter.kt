package com.novelcharacter.app.ui.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.databinding.ItemCharacterBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CharacterAdapter(
    private val coroutineScope: CoroutineScope,
    private val onClick: (Character) -> Unit,
    private val onLongClick: (Character) -> Unit
) : ListAdapter<Character, CharacterAdapter.CharacterViewHolder>(CharacterDiffCallback()) {

    private var isSelectionMode = false
    private var selectedIds = setOf<Long>()
    private var isMaxReached = false

    // Thumbnail cache - max 20MB
    private val thumbnailCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8  // Use 1/8th of available memory
        object : LruCache<String, Bitmap>(cacheSize.coerceIn(1024, 20 * 1024)) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        notifyDataSetChanged()
    }

    fun setSelectedIds(ids: Set<Long>) {
        selectedIds = ids
        notifyDataSetChanged()
    }

    /**
     * Update max-reached state without triggering notifyDataSetChanged.
     * Caller should batch this with setSelectedIds to avoid double refresh.
     */
    fun setMaxReached(maxReached: Boolean) {
        isMaxReached = maxReached
    }

    /**
     * Batch reset: clears selection mode, selected IDs, and max-reached in a single notify.
     */
    fun resetState() {
        isSelectionMode = false
        selectedIds = emptySet()
        isMaxReached = false
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CharacterViewHolder {
        val binding = ItemCharacterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CharacterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CharacterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: CharacterViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        thumbnailCache.evictAll()
    }

    inner class CharacterViewHolder(
        private val binding: ItemCharacterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(character: Character) {
            binding.characterName.text = character.name

            // Selection indicator
            if (isSelectionMode && selectedIds.contains(character.id)) {
                binding.root.alpha = 1.0f
                binding.root.setBackgroundColor(binding.root.context.getColor(R.color.primary_light))
            } else if (isSelectionMode && isMaxReached) {
                // Max selections reached - dim unselected items more
                binding.root.alpha = 0.3f
                binding.root.setBackgroundColor(0)
            } else if (isSelectionMode) {
                binding.root.alpha = 0.5f
                binding.root.setBackgroundColor(0)
            } else {
                binding.root.alpha = 1.0f
                binding.root.setBackgroundColor(0)
            }

            // 이미지 - 비동기로 로드
            loadCharacterImage(character)

            binding.root.setOnClickListener { onClick(character) }
            binding.root.setOnLongClickListener {
                onLongClick(character)
                true
            }
        }

        private fun loadCharacterImage(character: Character) {
            loadJob?.cancel()
            binding.characterImage.setImageResource(R.drawable.ic_character_placeholder)

            val paths: List<String> = try {
                gson.fromJson(character.imagePaths, imagePathsType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            if (paths.isNotEmpty()) {
                val path = paths[0]
                // Check cache first
                val cached = thumbnailCache.get(path)
                if (cached != null) {
                    binding.characterImage.setImageBitmap(cached)
                    return
                }

                val boundPath = path
                loadJob = coroutineScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeSampledBitmap(boundPath, 256, 256)
                    }
                    if (bitmap != null) {
                        thumbnailCache.put(boundPath, bitmap)
                        // Only set if this ViewHolder still shows the same character
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val currentPaths: List<String> = try {
                                val currentItem = getItem(pos)
                                gson.fromJson(currentItem.imagePaths, imagePathsType) ?: emptyList()
                            } catch (e: Exception) {
                                // List may have been updated between position check and getItem
                                emptyList()
                            }
                            if (currentPaths.isNotEmpty() && currentPaths[0] == boundPath) {
                                binding.characterImage.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }

        private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                val file = java.io.File(path)
                val appDir = binding.root.context.filesDir
                if (!file.canonicalPath.startsWith(appDir.canonicalPath)) {
                    return null
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(path, options)
            } catch (e: Exception) {
                null
            }
        }

        private fun calculateInSampleSize(
            options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
        ): Int {
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
    }

    class CharacterDiffCallback : DiffUtil.ItemCallback<Character>() {
        override fun areItemsTheSame(oldItem: Character, newItem: Character) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Character, newItem: Character) = oldItem == newItem
    }

    companion object {
        private val gson = Gson()
        private val imagePathsType = object : TypeToken<List<String>>() {}.type
    }
}
