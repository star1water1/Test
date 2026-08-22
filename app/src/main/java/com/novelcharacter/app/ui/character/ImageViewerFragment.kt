package com.novelcharacter.app.ui.character

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageViewerFragment : Fragment() {

    private var imagePaths: List<String> = emptyList()
    private var startPosition: Int = 0
    private var viewPager: ViewPager2? = null
    private var indexText: TextView? = null
    private var appDir: java.io.File? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var rotateButton: ImageView? = null
    /** 회전 중에는 다시 누르지 못하게 한다 — 같은 파일에 두 번 겹쳐 쓰면 결과가 정해지지 않는다. */
    private var rotating = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = android.widget.FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val pager = ViewPager2(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        viewPager = pager
        root.addView(pager)

        // Close button
        val density = resources.displayMetrics.density
        val closeBtn = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_close)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setColorFilter(android.graphics.Color.WHITE)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                topMargin = (16 * density).toInt()
                leftMargin = (8 * density).toInt()
            }
            setOnClickListener { findNavController().popBackStack() }
            contentDescription = getString(R.string.close_button_desc)
        }
        root.addView(closeBtn)

        // 회전 버튼 — **한 번 누울 때 손쓸 길이 이것뿐이다.**
        // `BitmapFactory`가 EXIF를 무시하던 동안 압축을 켜고 들여온 사진은 방향 정보가 사라진 채
        // 픽셀만 누워 저장됐고(그 부류는 자동으로 되살릴 방법이 없다), 앱에는 회전 수단이
        // 하나도 없었다. 여기서 90도씩 돌려 사용자가 직접 바로잡는다.
        val rotateBtn = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_rotate_right)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setColorFilter(android.graphics.Color.WHITE)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = (16 * density).toInt()
                rightMargin = (8 * density).toInt()
            }
            setOnClickListener { rotateCurrent() }
            contentDescription = getString(R.string.image_rotate_desc)
        }
        rotateButton = rotateBtn
        root.addView(rotateBtn)

        // Index indicator
        val idxText = TextView(requireContext()).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * density).toInt()
            }
        }
        indexText = idxText
        root.addView(idxText)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appDir = requireContext().filesDir

        val pathsJson = arguments?.getString("imagePaths") ?: "[]"
        // **회전하면 보던 장으로 돌아온다.** 종전에는 회전마다 인자의 진입 위치를 다시 읽어
        // 무조건 그 장으로 되돌렸다 — 스무 장을 넘겨 보던 사용자가 회전 한 번에 첫 장으로
        // 튕겼다. 페이저 자신의 계층 상태 복원도 무효였다(id를 `generateViewId()`로 매번
        // 새로 뽑으므로 복원 번들의 id와 맞지 않는다).
        startPosition = savedInstanceState?.getInt(STATE_POSITION)
            ?: arguments?.getInt("startPosition", 0) ?: 0

        val rawPaths: List<String> = try {
            com.google.gson.Gson().fromJson(pathsJson, com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        // 앱 디렉터리 밖 경로를 걸러 경로 우회를 막는다 — 판정은 [ImagePathMatch.isInside]
        // (B-106 ⓐ · R-39). `dir`이 null이면 그 함수가 전부 false로 접으므로 빈 목록이 된다.
        imagePaths = rawPaths.filter {
            com.novelcharacter.app.util.ImagePathMatch.isInside(it, appDir)
        }

        if (imagePaths.isEmpty()) {
            findNavController().popBackStack()
            return
        }

        setupViewPager()
    }

    private fun setupViewPager() {
        val pager = viewPager ?: return

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val zoomView = ZoomableImageView(parent.context)
                zoomView.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
                return object : RecyclerView.ViewHolder(zoomView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val zoomView = holder.itemView as ZoomableImageView
                zoomView.resetZoom()
                zoomView.setImageResource(R.drawable.ic_character_placeholder)

                val path = imagePaths[position]
                val boundPos = position
                val job = viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeBitmap(path)
                    }
                    if (bitmap != null && holder.bindingAdapterPosition == boundPos) {
                        zoomView.setImageBitmap(bitmap)
                    }
                }
                zoomView.tag = job
            }

            override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
                (holder.itemView.tag as? Job)?.cancel()
                (holder.itemView as? ImageView)?.setImageDrawable(null)
            }

            override fun getItemCount() = imagePaths.size
        }

        pager.setCurrentItem(startPosition, false)
        updateIndex(startPosition)

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 지금 보는 장을 든다 — 회전 뒤 되살릴 값이 이것이다.
                startPosition = position
                updateIndex(position)
            }
        }
        pager.registerOnPageChangeCallback(pageChangeCallback!!)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_POSITION, startPosition)
    }

    private companion object {
        /** 보던 장을 회전 너머로 나르는 키(싣는 쪽과 꺼내는 쪽이 이 상수 하나를 쓴다). */
        const val STATE_POSITION = "viewer_position"
    }

    private fun updateIndex(position: Int) {
        indexText?.text = getString(R.string.image_index_format, position + 1, imagePaths.size)
    }

    private fun decodeBitmap(path: String): android.graphics.Bitmap? {
        return try {
            if (!com.novelcharacter.app.util.ImagePathMatch.isInside(path, appDir)) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            var inSampleSize = 1
            val maxDim = 2048
            while (options.outWidth / inSampleSize > maxDim || options.outHeight / inSampleSize > maxDim) {
                inSampleSize *= 2
            }
            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false
            val decoded = BitmapFactory.decodeFile(path, options) ?: return null
            // 썸네일과 **같은 통로**로 EXIF 방향을 적용한다 — 한쪽만 적용하면 목록과 뷰어의
            // 방향이 달라지고, 그때 사용자는 어느 쪽이 진짜인지 알 수 없다.
            com.novelcharacter.app.util.ImageOrientationIo.applyFileOrientation(path, decoded)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 지금 보고 있는 이미지를 시계 방향 90도 돌려 **파일에 반영한다.**
     *
     * **먼저 EXIF 태그만 고쳐 본다** — JPEG면 픽셀을 다시 쓰지 않아 화질이 한 톨도 안 준다.
     * 네 번 돌려 제자리로 와도 원본 그대로다. 태그를 못 쓰는 포맷(투명도가 있어 PNG로 인코딩된
     * 파일 — 이 저장소는 파일명을 언제나 `.jpg`로 짓지만 실제 포맷은 내용이 정한다)일 때만
     * 픽셀을 돌려 다시 쓴다.
     *
     * 되돌리기는 **회전 버튼 자신이다** — 세 번 더 누르면 제자리다. 그래서 확인창을 두지 않는다
     * (R-4가 요구하는 것은 *되돌릴 수 없는* 처분의 사전 고지다).
     */
    private fun rotateCurrent() {
        if (rotating) return
        val position = viewPager?.currentItem ?: return
        val path = imagePaths.getOrNull(position) ?: return
        if (!com.novelcharacter.app.util.ImagePathMatch.isInside(path, appDir)) return
        rotating = true
        rotateButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // **빗장은 `finally`로 푼다.** 이 스코프는 뷰가 죽으면 취소되는데, 그때 아래
            // `isAdded` 검사에서 빠져나가게 두면 빗장이 걸린 채 남는다. 그 프래그먼트가
            // 백스택에서 다시 서면 **버튼은 멀쩡해 보이는데 눌러도 아무 일이 없다**
            // (새 뷰의 버튼은 enabled인데 빗장은 옛 값 그대로다).
            val ok = try {
                withContext(Dispatchers.IO) {
                    com.novelcharacter.app.util.ImageRotation.rotate(path, 90)
                }
            } finally {
                rotating = false
            }
            if (!isAdded || view == null) return@launch
            rotateButton?.isEnabled = true
            if (ok) {
                // 썸네일 캐시는 경로를 키로 들고 있어 그대로 두면 옛 방향이 목록에 남는다.
                com.novelcharacter.app.util.CharacterImageLoader.contentChanged()
                viewPager?.adapter?.notifyItemChanged(position)
            } else {
                android.widget.Toast.makeText(
                    requireContext(), R.string.image_rotate_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { viewPager?.unregisterOnPageChangeCallback(it) }
        // 뷰가 죽으면 빗장도 푼다 — 위 `finally`와 이중이지만, 둘의 실패 모양이 달라 둘 다 둔다
        // (`finally`는 취소를, 이쪽은 코루틴이 시작조차 못 한 경우를 덮는다).
        rotating = false
        rotateButton = null
        pageChangeCallback = null
        viewPager?.adapter = null
        viewPager = null
        indexText = null
        super.onDestroyView()
    }
}
