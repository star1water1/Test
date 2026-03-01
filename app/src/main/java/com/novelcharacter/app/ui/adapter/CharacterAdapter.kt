package com.novelcharacter.app.ui.adapter

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CombatRank
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
            binding.characterRace.text = buildString {
                if (character.race.isNotEmpty()) append(character.race)
                if (character.jobTitle.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(character.jobTitle)
                }
            }

            // 전투력 등급 배지
            if (character.combatRank.isNotEmpty()) {
                binding.combatRankBadge.text = character.combatRank
                binding.combatRankBadge.visibility = android.view.View.VISIBLE
                val color = getRankColor(character.combatRank)
                val bg = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = 8f
                }
                binding.combatRankBadge.background = bg
            } else {
                binding.combatRankBadge.visibility = android.view.View.GONE
            }

            // 생존 상태 표시
            val aliveColor = when (character.isAlive) {
                true -> ContextCompat.getColor(binding.root.context, R.color.alive_color)
                false -> ContextCompat.getColor(binding.root.context, R.color.dead_color)
                null -> ContextCompat.getColor(binding.root.context, R.color.unknown_color)
            }
            val aliveBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(aliveColor)
            }
            binding.aliveIndicator.background = aliveBg

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

        private fun getRankColor(rank: String): Int {
            val context = binding.root.context
            return when (rank) {
                "EX" -> ContextCompat.getColor(context, R.color.rank_ex)
                "S+" -> ContextCompat.getColor(context, R.color.rank_s_plus)
                "S" -> ContextCompat.getColor(context, R.color.rank_s)
                "A+" -> ContextCompat.getColor(context, R.color.rank_a_plus)
                "A" -> ContextCompat.getColor(context, R.color.rank_a)
                "A-" -> ContextCompat.getColor(context, R.color.rank_a_minus)
                "B+" -> ContextCompat.getColor(context, R.color.rank_b_plus)
                "B" -> ContextCompat.getColor(context, R.color.rank_b)
                "B-" -> ContextCompat.getColor(context, R.color.rank_b_minus)
                "C+" -> ContextCompat.getColor(context, R.color.rank_c_plus)
                "C" -> ContextCompat.getColor(context, R.color.rank_c)
                "C-" -> ContextCompat.getColor(context, R.color.rank_c_minus)
                "D" -> ContextCompat.getColor(context, R.color.rank_d)
                else -> ContextCompat.getColor(context, R.color.rank_d)
            }
        }
    }

    class CharacterDiffCallback : DiffUtil.ItemCallback<Character>() {
        override fun areItemsTheSame(oldItem: Character, newItem: Character) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Character, newItem: Character) = oldItem == newItem
    }
}
