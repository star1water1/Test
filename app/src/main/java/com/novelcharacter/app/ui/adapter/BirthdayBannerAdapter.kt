package com.novelcharacter.app.ui.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BirthdayCharacterItem
import com.novelcharacter.app.databinding.ItemBirthdayCharacterBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BirthdayBannerAdapter(
    private val coroutineScope: CoroutineScope,
    private val onClick: (BirthdayCharacterItem) -> Unit
) : ListAdapter<BirthdayCharacterItem, BirthdayBannerAdapter.BirthdayViewHolder>(BirthdayDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BirthdayViewHolder {
        val binding = ItemBirthdayCharacterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return BirthdayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BirthdayViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: BirthdayViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
        holder.binding.birthdayCharImage.setImageResource(R.drawable.ic_character_placeholder)
    }

    inner class BirthdayViewHolder(
        val binding: ItemBirthdayCharacterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(item: BirthdayCharacterItem) {
            loadJob?.cancel()
            binding.birthdayCharName.text = item.character.name

            // D-day 라벨
            val ctx = binding.root.context
            when (item.daysUntil) {
                0 -> {
                    binding.birthdayDDay.text = ctx.getString(R.string.birthday_today)
                    binding.birthdayDDay.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                }
                1 -> {
                    binding.birthdayDDay.text = ctx.getString(R.string.birthday_tomorrow)
                    binding.birthdayDDay.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
                else -> {
                    binding.birthdayDDay.text = ctx.getString(R.string.birthday_d_day, item.daysUntil)
                    binding.birthdayDDay.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
            }

            // 오늘 생일: 카드 보더 강조
            val cardView = binding.root as? com.google.android.material.card.MaterialCardView
            if (item.daysUntil == 0 && cardView != null) {
                cardView.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
                cardView.strokeColor = ContextCompat.getColor(ctx, R.color.primary)
            } else if (cardView != null) {
                cardView.strokeWidth = 0
            }

            binding.root.setOnClickListener { onClick(item) }

            // 이미지 로딩
            loadCharacterImage(item)
        }

        private fun loadCharacterImage(item: BirthdayCharacterItem) {
            binding.birthdayCharImage.setImageResource(R.drawable.ic_character_placeholder)
            val paths: List<String> = try {
                gson.fromJson(item.character.imagePaths, imagePathsType) ?: emptyList()
            } catch (_: Exception) { emptyList() }

            if (paths.isEmpty()) return

            val path = paths[0]
            val appDir = binding.root.context.filesDir
            loadJob = coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val file = java.io.File(path)
                        if (!file.canonicalPath.startsWith(appDir.canonicalPath + java.io.File.separator)) return@withContext null
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, options)
                        val (h, w) = options.outHeight to options.outWidth
                        var inSampleSize = 1
                        if (h > 128 || w > 128) {
                            while (inSampleSize < 512 && h / (inSampleSize * 2) >= 128 && w / (inSampleSize * 2) >= 128) {
                                inSampleSize *= 2
                            }
                        }
                        options.inSampleSize = inSampleSize
                        options.inJustDecodeBounds = false
                        BitmapFactory.decodeFile(path, options)
                    } catch (_: Exception) { null }
                }
                if (bitmap != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    binding.birthdayCharImage.setImageBitmap(bitmap)
                }
            }
        }
    }

    class BirthdayDiffCallback : DiffUtil.ItemCallback<BirthdayCharacterItem>() {
        override fun areItemsTheSame(oldItem: BirthdayCharacterItem, newItem: BirthdayCharacterItem) =
            oldItem.character.id == newItem.character.id

        override fun areContentsTheSame(oldItem: BirthdayCharacterItem, newItem: BirthdayCharacterItem) =
            oldItem == newItem
    }

    companion object {
        private val gson = Gson()
        private val imagePathsType = object : TypeToken<List<String>>() {}.type
    }
}
