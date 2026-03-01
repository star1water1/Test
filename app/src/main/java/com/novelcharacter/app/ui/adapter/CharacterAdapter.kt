package com.novelcharacter.app.ui.adapter

import android.graphics.BitmapFactory
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

class CharacterAdapter(
    private val onClick: (Character) -> Unit,
    private val onLongClick: (Character) -> Unit
) : ListAdapter<Character, CharacterAdapter.CharacterViewHolder>(CharacterDiffCallback()) {

    companion object {
        private val gson = Gson()
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
        holder.clearImage()
    }

    inner class CharacterViewHolder(
        private val binding: ItemCharacterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(character: Character) {
            binding.characterName.text = character.name

            // 이미지
            loadCharacterImage(character)

            binding.root.setOnClickListener { onClick(character) }
            binding.root.setOnLongClickListener {
                onLongClick(character)
                true
            }
        }

        fun clearImage() {
            binding.characterImage.setImageDrawable(null)
        }

        private fun loadCharacterImage(character: Character) {
            val type = object : TypeToken<List<String>>() {}.type
            val paths: List<String> = try {
                gson.fromJson(character.imagePaths, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            if (paths.isNotEmpty()) {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(paths[0], options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    binding.characterImage.setImageResource(R.drawable.ic_character_placeholder)
                    return
                }
                options.inSampleSize = calculateInSampleSize(options, 200, 200)
                options.inJustDecodeBounds = false
                val bitmap = BitmapFactory.decodeFile(paths[0], options)
                if (bitmap != null) {
                    binding.characterImage.setImageBitmap(bitmap)
                    return
                }
            }
            binding.characterImage.setImageResource(R.drawable.ic_character_placeholder)
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
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
}
