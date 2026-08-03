package com.novelcharacter.app.ui.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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

    /**
     * 이 화면 진입의 랜덤 시드(B-103 D3). **사용자 요청 ①이 여기서 이뤄진다** —
     * 배너는 종전에 언제나 `paths[0]`이었다. 대표를 지정한 캐릭터는 그 장이 나오고,
     * 지정하지 않았으면 화면에 들어올 때마다 다른 장이 나온다.
     *
     * 재추첨 함수를 따로 두지 않는다 — 이 어댑터는 `setupBirthdaySection()`에서 **뷰 생성마다
     * 새로 만들어지므로** 생성자의 시드가 곧 "화면 진입 1회"다(D3이 정의한 그 단위).
     * 목록·상세와 **같은 주기**가 된다(사용자 확정 P-3).
     */
    private val imageSeed: Long = com.novelcharacter.app.util.CharacterRepresentativeImage.newSeed()

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
            // 대표가 있으면 그 장, 없으면 시드 랜덤 (B-103 D2·D4).
            val path = com.novelcharacter.app.util.CharacterRepresentativeImage.path(
                item.character.imagePaths,
                item.character.representativeImagePath,
                imageSeed,
                item.character.id
            ) ?: return
            val appDir = binding.root.context.filesDir
            loadJob = coroutineScope.launch {
                // 공용 유틸 위임 — filesDir 경로 가드 + 총 픽셀 상한(파노라마 OOM 방지, P2-6).
                val bitmap = withContext(Dispatchers.IO) {
                    com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, appDir, 128)
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

}
