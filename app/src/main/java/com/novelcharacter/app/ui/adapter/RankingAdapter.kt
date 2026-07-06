package com.novelcharacter.app.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemRankingCardBinding
import com.novelcharacter.app.ui.stats.RankingEntry
import kotlinx.coroutines.*

class RankingAdapter(
    private val onItemClick: ((characterId: Long) -> Unit)? = null
) : RecyclerView.Adapter<RankingAdapter.ViewHolder>() {

    private var items: List<RankingEntry> = emptyList()
    private val gson = Gson()
    private val imagePathsType = object : TypeToken<List<String>>() {}.type
    // KB 단위 상한(≈10MB) — sizeOf 오버라이드로 실제 비트맵 KB를 센다(P1-G: 없으면 '엔트리 수'로 세어
    // ~수백MB까지 부풀던 단위 불일치 버그). 형제 어댑터와 동일한 메모리 예산.
    private val imageCache = object : LruCache<String, android.graphics.Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt().coerceIn(512, 10240)
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount / 1024
    }
    private var scope: CoroutineScope? = null

    fun submitList(newItems: List<RankingEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope?.cancel()
        scope = null
        imageCache.evictAll()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRankingCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemRankingCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: RankingEntry) {
            // 순위 뱃지
            binding.rankBadge.text = entry.rank.toString()
            val rankBg = binding.rankBadge.background
            if (rankBg is GradientDrawable) {
                rankBg.setColor(getRankColor(entry.rank))
            } else {
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getRankColor(entry.rank))
                }
                binding.rankBadge.background = drawable
            }

            // 캐릭터 이름
            binding.characterName.text = "#${entry.rank} ${entry.characterName}"

            // 작품명
            binding.novelName.text = entry.novelTitle ?: ""

            // 값 뱃지
            binding.valueBadge.text = entry.displayValue

            // 이미지 로딩
            binding.characterImage.setImageResource(R.drawable.ic_character_placeholder)
            loadImage(entry)

            // 클릭
            binding.root.setOnClickListener {
                onItemClick?.invoke(entry.characterId)
            }
        }

        private fun loadImage(entry: RankingEntry) {
            val paths: List<String> = try {
                gson.fromJson(entry.imagePaths, imagePathsType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val path = paths.firstOrNull() ?: return

            val cached = imageCache.get(path)
            if (cached != null) {
                binding.characterImage.setImageBitmap(cached)
                return
            }

            val currentId = entry.characterId
            scope?.launch {
                // 공용 유틸 위임 — filesDir 경로 가드(P1-G: 없던 것) + 총 픽셀 상한(파노라마 OOM 방지) 획득.
                val bitmap = withContext(Dispatchers.IO) {
                    com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, binding.root.context.filesDir, 112)
                }
                if (bitmap != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    val item = items.getOrNull(bindingAdapterPosition)
                    if (item?.characterId == currentId) {
                        imageCache.put(path, bitmap)
                        binding.characterImage.setImageBitmap(bitmap)
                    }
                }
            }
        }

        private fun getRankColor(rank: Int): Int = when (rank) {
            1 -> Color.parseColor("#FFD700")    // Gold
            2 -> Color.parseColor("#C0C0C0")    // Silver
            3 -> Color.parseColor("#CD7F32")    // Bronze
            else -> Color.parseColor("#5C6BC0") // Primary-ish
        }
    }
}
