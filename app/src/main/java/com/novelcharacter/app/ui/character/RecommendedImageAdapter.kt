package com.novelcharacter.app.ui.character

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemRecommendedImageBinding
import com.novelcharacter.app.util.CharacterImageLoader
import com.novelcharacter.app.util.ImageRecommendationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 캐릭터 편집창 추천 이미지 가로 스트립 — [com.novelcharacter.app.ui.adapter.BirthdayBannerAdapter] 형판.
 * 80dp 썸네일 + 일치 태그 캡션 + 링크 배지. 탭 = attach 콜백(링크 그룹 확장은 호출부 담당).
 */
class RecommendedImageAdapter(
    private val coroutineScope: CoroutineScope,
    private val onClick: (ImageRecommendationHelper.Recommendation) -> Unit
) : ListAdapter<ImageRecommendationHelper.Recommendation, RecommendedImageAdapter.RecViewHolder>(RecDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecViewHolder {
        val binding = ItemRecommendedImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RecViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: RecViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
        holder.binding.recImage.setImageResource(R.drawable.ic_character_placeholder)
    }

    inner class RecViewHolder(
        val binding: ItemRecommendedImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(item: ImageRecommendationHelper.Recommendation) {
            loadJob?.cancel()
            binding.recTagText.text = item.matchedTags.joinToString(", ")
            binding.recLinkBadge.visibility =
                if (item.candidate.linkGroupId != null) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(item) }

            binding.recImage.setImageResource(R.drawable.ic_character_placeholder)
            val appDir = binding.root.context.filesDir
            val targetSize = (80 * binding.root.context.resources.displayMetrics.density).toInt()
            loadJob = coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    CharacterImageLoader.decodeThumbnail(item.candidate.path, appDir, targetSize)
                }
                if (bitmap != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    binding.recImage.setImageBitmap(bitmap)
                }
            }
        }
    }

    class RecDiffCallback : DiffUtil.ItemCallback<ImageRecommendationHelper.Recommendation>() {
        override fun areItemsTheSame(
            oldItem: ImageRecommendationHelper.Recommendation,
            newItem: ImageRecommendationHelper.Recommendation
        ) = oldItem.candidate.path == newItem.candidate.path

        override fun areContentsTheSame(
            oldItem: ImageRecommendationHelper.Recommendation,
            newItem: ImageRecommendationHelper.Recommendation
        ) = oldItem == newItem
    }
}
