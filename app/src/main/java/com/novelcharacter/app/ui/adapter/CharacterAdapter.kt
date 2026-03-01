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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CharacterViewHolder {
        val binding = ItemCharacterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CharacterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CharacterViewHolder, position: Int) {
        holder.bind(getItem(position))
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

        private fun loadCharacterImage(character: Character) {
            val gson = Gson()
            val type = object : TypeToken<List<String>>() {}.type
            val paths: List<String> = try {
                gson.fromJson(character.imagePaths, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            if (paths.isNotEmpty()) {
                val bitmap = BitmapFactory.decodeFile(paths[0])
                if (bitmap != null) {
                    binding.characterImage.setImageBitmap(bitmap)
                    return
                }
            }
            binding.characterImage.setImageResource(R.drawable.ic_character_placeholder)
        }
    }

    class CharacterDiffCallback : DiffUtil.ItemCallback<Character>() {
        override fun areItemsTheSame(oldItem: Character, newItem: Character) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Character, newItem: Character) = oldItem == newItem
    }
}
