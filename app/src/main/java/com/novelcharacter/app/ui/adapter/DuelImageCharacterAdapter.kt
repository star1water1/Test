package com.novelcharacter.app.ui.adapter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemDuelImageCharacterBinding
import com.novelcharacter.app.util.DuelImageRoster

/**
 * 이미지 축에서 **누구의 그림을 겨룰지** 고르는 목록 (B-104 이미지 축, 설계 13장).
 *
 * 이미지 대결은 캐릭터마다 따로 돈다(사용자 확정) — 그래서 축 하나를 열면 곧바로 대결이
 * 아니라 **이 목록**이 먼저다.
 *
 * 요약 줄이 판 수와 **남은 짝**을 함께 말하는 것은 축 목록과 같은 근거다(원칙 04) —
 * *"어느 캐릭터가 아직 할 일이 남았는가"*가 눌러 보기 전에 보여야 하나씩 열어 보지 않는다.
 */
class DuelImageCharacterAdapter(
    private val onClick: (DuelImageRoster.Entry) -> Unit,
    /** 첫 그림의 섬네일 — 비동기라 목록보다 늦게 온다. 없으면 자리표시자가 남는다. */
    private val thumbnailOf: (DuelImageRoster.Entry) -> Bitmap?
) : ListAdapter<DuelImageRoster.Entry, DuelImageCharacterAdapter.EntryViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder =
        EntryViewHolder(
            ItemDuelImageCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class EntryViewHolder(
        private val binding: ItemDuelImageCharacterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DuelImageRoster.Entry) {
            val context = binding.root.context
            binding.characterName.text = entry.name
            // 아직 한 판도 안 한 캐릭터는 **그 사실을 그대로 말한다** — `0판 · 남은 짝 3`이라
            // 적으면 시작한 것과 같은 모양이 되어 어디부터 손댈지 가려지지 않는다.
            binding.characterSummary.text = if (entry.untouched) {
                context.getString(
                    R.string.duel_image_character_untouched, entry.imageCount, entry.totalPairs
                )
            } else {
                context.getString(
                    R.string.duel_image_character_summary,
                    entry.imageCount, entry.played, entry.remainingPairs
                )
            }

            val bitmap = thumbnailOf(entry)
            if (bitmap != null) {
                binding.thumbnail.setImageBitmap(bitmap)
            } else {
                binding.thumbnail.setImageResource(R.drawable.ic_character_placeholder)
            }

            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<DuelImageRoster.Entry>() {
        override fun areItemsTheSame(
            oldItem: DuelImageRoster.Entry,
            newItem: DuelImageRoster.Entry
        ): Boolean = oldItem.characterId == newItem.characterId

        override fun areContentsTheSame(
            oldItem: DuelImageRoster.Entry,
            newItem: DuelImageRoster.Entry
        ): Boolean = oldItem == newItem
    }
}
