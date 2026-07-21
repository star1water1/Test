package com.novelcharacter.app.ui.image

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
 */
class ImageManagerAdapter(
    private val scope: CoroutineScope,
    private val onClick: (ImageManagerViewModel.ManagedImage) -> Unit,
    private val onToggleSelect: (ImageManagerViewModel.ManagedImage) -> Unit,
    private val onLongPress: (ImageManagerViewModel.ManagedImage) -> Unit
) : ListAdapter<ImageManagerViewModel.ManagedImage, ImageManagerAdapter.VH>(DIFF) {

    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()

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
            selectedProvider = { selectedPaths })
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

    class VH(
        private val binding: ItemManagedImageBinding,
        private val scope: CoroutineScope,
        private val onClick: (ImageManagerViewModel.ManagedImage) -> Unit,
        private val onToggleSelect: (ImageManagerViewModel.ManagedImage) -> Unit,
        private val onLongPress: (ImageManagerViewModel.ManagedImage) -> Unit,
        private val itemAt: (Int) -> ImageManagerViewModel.ManagedImage,
        private val selectionModeProvider: () -> Boolean,
        private val selectedProvider: () -> Set<String>
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

        private fun ownerLabel(ctx: android.content.Context, item: ImageManagerViewModel.ManagedImage): String {
            if (item.owners.isEmpty()) {
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
