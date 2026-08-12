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
import com.google.android.material.card.MaterialCardView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.ItemNovelBinding
import com.novelcharacter.app.util.CardFieldSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NovelAdapter(
    private val coroutineScope: CoroutineScope,
    private val onClick: (Novel) -> Unit,
    private val onEditClick: (Novel) -> Unit,
    private val onDeleteClick: (Novel) -> Unit,
    private val onPinClick: ((Novel) -> Unit)? = null,
    var onOrderChanged: ((List<Novel>) -> Unit)? = null
) : ListAdapter<Novel, NovelAdapter.NovelViewHolder>(NovelDiffCallback()) {

    private var isReorderMode = false
    var itemTouchHelper: ItemTouchHelper? = null
    private val reorderList = mutableListOf<Novel>()
    private var universeBorderColor: String = ""
    private var universeBorderWidthDp: Float = 1.5f

    /** 작품에 속한 캐릭터의 랜덤/선택 이미지 경로를 반환하는 콜백 */
    var resolveCharacterImage: ((novelId: Long, characterId: Long?, callback: (String?) -> Unit) -> Unit)? = null

    /**
     * 이 화면 진입의 랜덤 시드 (B-106 ⓑ · 확정 7-3) — **캐릭터와 같은 주기다.**
     *
     * 종전에는 `ImageIndexPrefs`에 **인덱스를 영속 저장**했고 주기도 달랐다(재방출마다 재추첨).
     * 그 방식의 실질적 결함은 튀는 것이 아니라 **`idx % size`가 가려 두던 것**이다 —
     * 카드에서 이미지를 빼 목록이 줄면 저장된 인덱스가 **다른 그림을 가리킨다.**
     * 캐릭터 몫에서 B-103이 이미 없앤 병이고, 이제 작품·세계관도 같은 규칙을 쓴다.
     *
     * 시드 하나 = 화면 진입 한 번: 같은 화면 안에서는 스크롤 재바인드·정렬·필터에 그림이
     * 튀지 않고(같은 시드 → 같은 결과) 나갔다 들어오면 바뀐다.
     * **상태를 저장하지 않으므로 쓰기가 0이다.**
     */
    private var imageSeed: Long = com.novelcharacter.app.util.CharacterRepresentativeImage.newSeed()

    /** 이미지 표시를 랜덤으로 재설정 (화면 진입 1회 호출) */
    fun refreshRandomImages() {
        imageSeed = com.novelcharacter.app.util.CharacterRepresentativeImage.newSeed()
    }

    /**
     * 작품 id → 카드에 얹을 필드값 요약 (B-67, 외부에서 설정).
     *
     * 요약은 목록 방출보다 **한 걸음 늦게** 온다(별도 조회다). 그때 통짜로 다시 그리면
     * 카드마다 이미지 로드가 취소·재시작되므로, [PAYLOAD_FIELD_SUMMARY]로 **그 두 줄만**
     * 갈아 끼운다. 목록이 자주 재방출되는 화면이라 이 차이가 스크롤에 그대로 붙는다.
     */
    var fieldSummaries: Map<Long, CardFieldSummary.Summary> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_FIELD_SUMMARY)
        }

    private val thumbnailCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 16
        object : LruCache<String, Bitmap>(cacheSize.coerceIn(512, 10 * 1024)) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
    }

    fun setUniverseBorder(color: String, widthDp: Float) {
        universeBorderColor = color
        universeBorderWidthDp = widthDp
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

    fun getReorderedList(): List<Novel> = reorderList.mapIndexed { index, novel ->
        novel.copy(displayOrder = index.toLong())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NovelViewHolder {
        val binding = ItemNovelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NovelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NovelViewHolder, position: Int) {
        holder.bind(itemAt(position))
    }

    /**
     * 요약만 바뀐 갱신은 **그 두 줄만** 다시 그린다(B-67). payload가 섞여 오면(다른 이유의
     * 갱신이 함께 들어오면) 통짜 재바인드로 떨어뜨린다 — 부분 갱신은 *전부 이 payload일 때만*
     * 안전하다.
     */
    override fun onBindViewHolder(holder: NovelViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_FIELD_SUMMARY }) {
            holder.bindFieldSummary(itemAt(position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun itemAt(position: Int): Novel =
        if (isReorderMode && position < reorderList.size) reorderList[position] else getItem(position)

    override fun getItemCount(): Int = if (isReorderMode) reorderList.size else super.getItemCount()

    override fun onViewRecycled(holder: NovelViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
        holder.binding.novelImage.setImageDrawable(null)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        thumbnailCache.evictAll()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class NovelViewHolder(
        val binding: ItemNovelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(novel: Novel) {
            binding.novelTitle.text = if (novel.isPinned) {
                binding.root.context.getString(R.string.pinned_name_format, novel.title)
            } else {
                novel.title
            }
            binding.novelDescription.text = novel.description.ifEmpty { binding.root.context.getString(R.string.no_description) }
            bindFieldSummary(novel)

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

            loadNovelImage(novel)

            binding.root.setOnClickListener { onClick(novel) }
            binding.root.setOnLongClickListener(null)
            binding.root.isLongClickable = false

            binding.btnMore.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                val pinLabel = if (novel.isPinned) R.string.unpin else R.string.pin
                popup.menu.add(0, 3, 0, pinLabel)
                popup.menu.add(0, 1, 1, R.string.menu_edit)
                popup.menu.add(0, 2, 2, R.string.menu_delete)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        3 -> { onPinClick?.invoke(novel); true }
                        1 -> { onEditClick(novel); true }
                        2 -> { onDeleteClick(novel); true }
                        else -> false
                    }
                }
                popup.show()
            }

            val card = binding.root as? MaterialCardView
            if (card != null) {
                val effectiveColor = if (novel.borderColor.isNotBlank()) {
                    novel.borderColor
                } else if (novel.inheritUniverseBorder && universeBorderColor.isNotBlank()) {
                    universeBorderColor
                } else ""

                val effectiveWidth = if (novel.borderColor.isNotBlank()) {
                    novel.borderWidthDp
                } else if (novel.inheritUniverseBorder && universeBorderColor.isNotBlank()) {
                    universeBorderWidthDp
                } else 0f

                if (effectiveColor.isNotBlank()) {
                    try {
                        card.strokeColor = Color.parseColor(effectiveColor)
                        card.strokeWidth = (effectiveWidth * binding.root.context.resources.displayMetrics.density).toInt()
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

        /**
         * 작품 커스텀 필드값 (B-67). 상한을 넘어 잘린 줄은 **개수로 존재를 알린다** —
         * 열어보지 않으면 값이 더 있다는 것조차 모르는 상태를 만들지 않는다(원칙 04).
         */
        fun bindFieldSummary(novel: Novel) {
            val summary = fieldSummaries[novel.id] ?: CardFieldSummary.empty()
            if (summary.isEmpty) {
                binding.novelFieldsText.visibility = View.GONE
                binding.novelFieldsMoreText.visibility = View.GONE
                return
            }
            val ctx = binding.root.context
            binding.novelFieldsText.text = summary.lines.joinToString("\n") {
                ctx.getString(R.string.card_field_line_format, it.label, it.value)
            }
            binding.novelFieldsText.visibility = View.VISIBLE
            if (summary.hiddenCount > 0) {
                binding.novelFieldsMoreText.text =
                    ctx.getString(R.string.card_field_more_format, summary.hiddenCount)
                binding.novelFieldsMoreText.visibility = View.VISIBLE
            } else {
                binding.novelFieldsMoreText.visibility = View.GONE
            }
        }

        private fun loadNovelImage(novel: Novel) {
            loadJob?.cancel()
            binding.novelImage.visibility = View.GONE
            binding.novelImage.setImageDrawable(null)

            when (novel.imageMode) {
                Novel.IMAGE_MODE_CUSTOM -> {
                    val paths = parseImagePaths(novel.imagePaths)
                    if (paths.isNotEmpty()) {
                        // 시드 하나로 고른다 (B-106 ⓑ) — 목록 크기를 그때그때 넘기므로
                        // `idx % size`가 다른 그림을 가리키던 자리가 사라진다.
                        val idx = com.novelcharacter.app.util.CharacterRepresentativeImage
                            .randomIndex(imageSeed, novel.id, paths.size)
                        binding.novelImage.visibility = View.VISIBLE
                        loadImageFromPath(paths[idx], novel.id)
                    }
                }
                Novel.IMAGE_MODE_RANDOM_CHARACTER, Novel.IMAGE_MODE_SELECT_CHARACTER -> {
                    val charId = if (novel.imageMode == Novel.IMAGE_MODE_SELECT_CHARACTER) novel.imageCharacterId else null
                    resolveCharacterImage?.invoke(novel.id, charId) { resolvedPath ->
                        if (resolvedPath != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            binding.novelImage.visibility = View.VISIBLE
                            loadImageFromPath(resolvedPath, novel.id)
                        }
                    }
                }
            }
        }

        private fun loadImageFromPath(path: String, novelId: Long) {
            val cached = thumbnailCache.get(path)
            if (cached != null) {
                binding.novelImage.setImageBitmap(cached)
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
                            if (current.id == novelId) {
                                binding.novelImage.setImageBitmap(bitmap)
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
        // 공용 유틸 위임 — filesDir 경로 가드 + 총 픽셀 상한(파노라마/세로 스크롤 OOM 방지, P2-6).
        // 정상 이미지는 기존과 동일한 다운샘플 결과(앱 표준 알고리즘)라 화질 보존.
        return com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, context.filesDir, reqWidth)
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

    class NovelDiffCallback : DiffUtil.ItemCallback<Novel>() {
        override fun areItemsTheSame(oldItem: Novel, newItem: Novel) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Novel, newItem: Novel) = oldItem == newItem
    }

    companion object {
        /** 필드값 요약만 갈아 끼우는 부분 갱신 표식 (B-67) */
        private const val PAYLOAD_FIELD_SUMMARY = "fieldSummary"
    }
}
