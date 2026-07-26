package com.novelcharacter.app.ui.image

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.ui.character.ZoomableImageView
import com.novelcharacter.app.util.loadCharacterThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 이미지탭 갤러리뷰 페이저 어댑터 — 그리드와 **같은 filtered/sorted 목록**을 소비한다
 * (필터·검색·정렬·삭제가 두 모드에서 항상 일치).
 * 페이지는 [ZoomableImageView](핀치 1–5×, 줌 시 스와이프 양보)이고, 디코드는
 * ImageViewerFragment 관례(페이지별 Job을 태그에 보관, 재활용 시 취소)를 따른다.
 */
class GalleryPagerAdapter(
    private val scope: CoroutineScope
) : ListAdapter<ImageManagerViewModel.ManagedImage, GalleryPagerAdapter.PageHolder>(DIFF) {

    class PageHolder(val zoomView: ZoomableImageView) : RecyclerView.ViewHolder(zoomView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val zoomView = ZoomableImageView(parent.context).apply {
            // ViewPager2 페이지 루트는 MATCH_PARENT 필수
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
        }
        return PageHolder(zoomView)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val path = getItem(position).path
        holder.zoomView.resetZoom()
        (holder.zoomView.getTag(R.id.image_load_job) as? Job)?.cancel()
        val job = holder.zoomView.loadCharacterThumbnail(
            path, scope, reqPx = FULL_REQ_PX,
            isValid = {
                val pos = holder.bindingAdapterPosition
                pos != RecyclerView.NO_POSITION && pos < itemCount && getItem(pos).path == path
            }
        )
        holder.zoomView.setTag(R.id.image_load_job, job)
    }

    override fun onViewRecycled(holder: PageHolder) {
        (holder.zoomView.getTag(R.id.image_load_job) as? Job)?.cancel()
        holder.zoomView.setTag(R.id.image_load_job, null)
        holder.zoomView.setImageDrawable(null)
    }

    companion object {
        /** 전체 화면 페이지 디코드 목표 — 그리드 256px 대비 고해상(핀치줌 여유), 4M 픽셀 캡과의 절충 */
        const val FULL_REQ_PX = 1600

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
}
