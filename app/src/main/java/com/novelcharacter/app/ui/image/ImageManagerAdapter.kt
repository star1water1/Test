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
 */
class ImageManagerAdapter(
    private val scope: CoroutineScope,
    private val onClick: (ImageManagerViewModel.ManagedImage) -> Unit
) : ListAdapter<ImageManagerViewModel.ManagedImage, ImageManagerAdapter.VH>(DIFF) {

    companion object {
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
        return VH(binding, scope, onClick, ::getItem)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
    }

    class VH(
        private val binding: ItemManagedImageBinding,
        private val scope: CoroutineScope,
        private val onClick: (ImageManagerViewModel.ManagedImage) -> Unit,
        private val itemAt: (Int) -> ImageManagerViewModel.ManagedImage
    ) : RecyclerView.ViewHolder(binding.root) {

        private var thumbJob: Job? = null

        init {
            binding.itemRoot.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(itemAt(pos))
            }
        }

        fun bind(item: ImageManagerViewModel.ManagedImage) {
            val ctx = binding.root.context
            binding.sizeText.text = StorageAnalyzer.formatBytes(item.sizeBytes)

            when (item.status) {
                ImageManagerViewModel.Status.ORPHAN -> {
                    binding.statusBadge.visibility = View.VISIBLE
                    binding.statusBadge.text = ctx.getString(R.string.image_manager_status_orphan)
                }
                ImageManagerViewModel.Status.TRASH_HELD -> {
                    binding.statusBadge.visibility = View.VISIBLE
                    binding.statusBadge.text = ctx.getString(R.string.image_manager_status_trash)
                }
                ImageManagerViewModel.Status.REFERENCED -> binding.statusBadge.visibility = View.GONE
            }

            binding.ownerText.text = ownerLabel(ctx, item)

            thumbJob?.cancel()
            thumbJob = binding.thumbnail.loadCharacterThumbnail(
                item.path, scope, reqPx = 256,
                isValid = { bindingAdapterPosition != RecyclerView.NO_POSITION }
            )
        }

        fun recycle() {
            thumbJob?.cancel()
        }

        private fun ownerLabel(ctx: android.content.Context, item: ImageManagerViewModel.ManagedImage): String {
            if (item.owners.isEmpty()) {
                return if (item.status == ImageManagerViewModel.Status.TRASH_HELD) {
                    ctx.getString(R.string.image_manager_owner_trash)
                } else {
                    ctx.getString(R.string.image_manager_owner_orphan)
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
