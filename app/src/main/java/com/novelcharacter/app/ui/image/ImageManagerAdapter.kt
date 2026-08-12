package com.novelcharacter.app.ui.image

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemManagedImageBinding
import com.novelcharacter.app.util.StorageAnalyzer
import com.novelcharacter.app.util.loadCharacterThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 이미지 관리 그리드 어댑터. 셀마다 썸네일·크기·소유자·상태 배지를 보여준다.
 * 썸네일 디코드는 공용 [loadCharacterThumbnail](재활용-안전)을 쓰고, 반환 Job을 onViewRecycled에서 취소한다.
 *
 * 선택 모드에서는 셀 위에 선택 스크림·체크 표식을 덧씌우고, 탭이 상세 열기가 아니라 선택 토글로 동작한다.
 *
 * **디코드한 썸네일은 [thumbnailCache]에 남는다** (B-12) — 이 화면은 앱에서 그림이 가장
 * 촘촘한 격자라, 캐시가 없으면 스크롤을 되돌릴 때마다 같은 파일을 다시 디코드한다.
 */
class ImageManagerAdapter(
    private val scope: CoroutineScope,
    private val onClick: (ImageManagerViewModel.ManagedImage) -> Unit,
    private val onToggleSelect: (ImageManagerViewModel.ManagedImage) -> Unit,
    private val onLongPress: (ImageManagerViewModel.ManagedImage) -> Unit
) : ListAdapter<ImageManagerViewModel.ManagedImage, ImageManagerAdapter.VH>(DIFF) {

    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()

    /**
     * 썸네일 캐시 — **형제 넷(`CharacterAdapter`·`UniverseAdapter`·`NovelAdapter`·`RankingAdapter`)과
     * 같은 자리·같은 셈**이다(어댑터가 소유하고, 화면을 떠날 때 통째로 비운다).
     *
     * 공용 로더로 소유를 올리지 않은 것은 의도한 선택이다 — 자기 캐시를 이미 든 어댑터 넷과
     * 이중이 되고, 그 재편은 아직 미측정·미판정인 B-58의 몫이다(확정 16번 = ⓑ '아직').
     * 로더는 캐시를 **인자로 받기만** 하므로 넘기지 않는 화면 다섯은 그대로다.
     *
     * 몫을 형제 중 큰 쪽(`maxMemory/8`)에 맞춘 것은 이 격자가 한 화면에 드는 셀이 가장 많고
     * 셀당 요청 크기도 256px로 큰 축이기 때문이다. 상한을 두는 것은 [LruCache]가
     * `sizeOf`로 실제 바이트를 세기 때문에 큰 힙에서 캐시가 화면 하나를 위해 과하게 자라지
     * 않게 하려는 것이다(형제와 같은 관행).
     */
    private val thumbnailCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        object : LruCache<String, Bitmap>(cacheSize.coerceIn(1024, 20 * 1024)) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
    }

    /** 선택 모드/선택 집합 갱신 — 오버레이만 부분 갱신(payload)해 썸네일 재디코드를 피한다(선택 토글 잔렉 제거). */
    fun setSelectionState(mode: Boolean, selected: Set<String>) {
        selectionMode = mode
        selectedPaths = selected
        notifyItemRangeChanged(0, itemCount, SELECTION_PAYLOAD)
    }

    companion object {
        private const val SELECTION_PAYLOAD = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<ImageManagerViewModel.ManagedImage>() {
            override fun areItemsTheSame(
                a: ImageManagerViewModel.ManagedImage,
                b: ImageManagerViewModel.ManagedImage
            ) = a.path == b.path

            override fun areContentsTheSame(
                a: ImageManagerViewModel.ManagedImage,
                b: ImageManagerViewModel.ManagedImage
            ) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemManagedImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, scope, onClick, onToggleSelect, onLongPress, ::getItem,
            selectionModeProvider = { selectionMode },
            selectedProvider = { selectedPaths },
            thumbnailCache = thumbnailCache)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        // 선택 payload면 오버레이만 갱신(썸네일 재디코드 생략). 그 외(전체 갱신·diff)는 정규 bind.
        if (payloads.contains(SELECTION_PAYLOAD)) {
            holder.bindSelection(getItem(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
    }

    /** 화면을 떠나면 통째로 비운다 — 형제 넷과 같은 관행(들고 있을 이유가 없는 순간이다). */
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        thumbnailCache.evictAll()
    }

    class VH(
        private val binding: ItemManagedImageBinding,
        private val scope: CoroutineScope,
        private val onClick: (ImageManagerViewModel.ManagedImage) -> Unit,
        private val onToggleSelect: (ImageManagerViewModel.ManagedImage) -> Unit,
        private val onLongPress: (ImageManagerViewModel.ManagedImage) -> Unit,
        private val itemAt: (Int) -> ImageManagerViewModel.ManagedImage,
        private val selectionModeProvider: () -> Boolean,
        private val selectedProvider: () -> Set<String>,
        private val thumbnailCache: LruCache<String, Bitmap>
    ) : RecyclerView.ViewHolder(binding.root) {

        private var thumbJob: Job? = null

        init {
            binding.itemRoot.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = itemAt(pos)
                if (selectionModeProvider()) onToggleSelect(item) else onClick(item)
            }
            binding.itemRoot.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                if (!selectionModeProvider()) {
                    onLongPress(itemAt(pos))
                    true
                } else false
            }
        }

        fun bind(item: ImageManagerViewModel.ManagedImage) {
            val ctx = binding.root.context
            binding.sizeText.text = StorageAnalyzer.formatBytes(item.sizeBytes)

            when (item.status) {
                ImageManagerViewModel.Status.ORPHAN -> {
                    binding.statusBadge.visibility = View.VISIBLE
                    binding.statusBadge.setBackgroundColor(0xCC000000.toInt())
                    binding.statusBadge.text = ctx.getString(R.string.image_manager_status_orphan)
                }
                ImageManagerViewModel.Status.TRASH_HELD -> {
                    binding.statusBadge.visibility = View.VISIBLE
                    binding.statusBadge.setBackgroundColor(0xCC000000.toInt())
                    binding.statusBadge.text = ctx.getString(R.string.image_manager_status_trash)
                }
                ImageManagerViewModel.Status.UNASSIGNED -> {
                    binding.statusBadge.visibility = View.VISIBLE
                    binding.statusBadge.setBackgroundColor(0xCC2E7D32.toInt())  // 초록 계열 — 고아(회색)와 구분
                    binding.statusBadge.text = ctx.getString(R.string.image_manager_status_unassigned)
                }
                ImageManagerViewModel.Status.REFERENCED -> binding.statusBadge.visibility = View.GONE
            }

            binding.linkBadge.visibility =
                if (item.meta?.linkGroupId != null) View.VISIBLE else View.GONE

            binding.ownerText.text = ownerLabel(ctx, item)
            // 태그 줄은 항상 1줄 유지(빈 값 포함) — 그리드 행 높이 균일화(레이아웃 주석 참조).
            val tags = item.meta?.tags.orEmpty()
            binding.tagText.text = if (tags.isEmpty()) "" else tags.joinToString(" · ") { "#$it" }

            // 선택 오버레이
            bindSelection(item)

            thumbJob?.cancel()
            thumbJob = binding.thumbnail.loadCharacterThumbnail(
                item.path, scope, reqPx = 256,
                cache = thumbnailCache,
                isValid = { bindingAdapterPosition != RecyclerView.NO_POSITION }
            )
        }

        /** 선택 오버레이(스크림·체크)만 갱신 — 썸네일 재디코드 없이 payload 부분 갱신에 사용. */
        fun bindSelection(item: ImageManagerViewModel.ManagedImage) {
            val selected = selectionModeProvider() && selectedProvider().contains(item.path)
            binding.selectionScrim.visibility = if (selected) View.VISIBLE else View.GONE
            binding.selectionCheck.visibility = if (selected) View.VISIBLE else View.GONE
        }

        fun recycle() {
            thumbJob?.cancel()
        }

        /**
         * 소유자 줄. **뗀 이미지는 이 줄이 출처·시점을 싣는다**(B-107 D3, 사용자 판정).
         *
         * 줄을 새로 만들지 않은 이유: 뗀 이미지는 소유자가 없어 이 자리가 지금 "미배정"이라는
         * 낱말 하나만 싣고 있다. 줄을 더하면 그리드 칸 높이가 **모든 이미지에 대해** 늘어난다
         * (레이아웃이 태그 줄을 1줄 고정한 것과 같은 이유 — 행 높이 균일).
         *
         * 소유자가 남아 있으면(작품·세계관이 아직 쓰는 뗀 이미지) **소유자 표시가 이긴다** —
         * 그 정보는 여기서만 나오지만 뗀 사실은 칩·배지로도 알 수 있다.
         */
        private fun ownerLabel(ctx: android.content.Context, item: ImageManagerViewModel.ManagedImage): String {
            if (item.owners.isEmpty()) {
                val meta = item.meta
                if (meta?.detachedAt != null) {
                    val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(
                        meta.detachedAt, System.currentTimeMillis(),
                        android.text.format.DateUtils.DAY_IN_MILLIS
                    ).toString()
                    return when {
                        meta.detachedFromName != null ->
                            ctx.getString(R.string.image_manager_detached_from, meta.detachedFromName, whenText)
                        // 코드는 있는데 이름을 못 찾았다 = 그 캐릭터가 지워졌다.
                        meta.detachedFromCode != null ->
                            ctx.getString(R.string.image_manager_detached_from_unknown, whenText)
                        else -> ctx.getString(R.string.image_manager_detached_no_source, whenText)
                    }
                }
                return when (item.status) {
                    ImageManagerViewModel.Status.TRASH_HELD -> ctx.getString(R.string.image_manager_owner_trash)
                    ImageManagerViewModel.Status.UNASSIGNED -> ctx.getString(R.string.image_manager_owner_unassigned)
                    else -> ctx.getString(R.string.image_manager_owner_orphan)
                }
            }
            val first = item.owners.first()
            val typeLabel = when (first.type) {
                ImageManagerViewModel.OwnerType.CHARACTER -> ctx.getString(R.string.image_manager_type_character)
                ImageManagerViewModel.OwnerType.NOVEL -> ctx.getString(R.string.image_manager_type_novel)
                ImageManagerViewModel.OwnerType.UNIVERSE -> ctx.getString(R.string.image_manager_type_universe)
            }
            val base = "$typeLabel · ${first.name}"
            return if (item.owners.size > 1) {
                ctx.getString(R.string.image_manager_owner_more, base, item.owners.size - 1)
            } else base
        }
    }
}
