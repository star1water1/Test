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
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
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
        startPosition = arguments?.getInt("startPosition", 0) ?: 0

        val rawPaths: List<String> = try {
            com.google.gson.Gson().fromJson(pathsJson, com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        // Filter out paths outside app directory to prevent path traversal
        val dir = appDir
        imagePaths = if (dir != null) {
            rawPaths.filter { path ->
                try {
                    java.io.File(path).canonicalPath.startsWith(dir.canonicalPath)
                } catch (_: Exception) { false }
            }
        } else {
            emptyList()
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
                updateIndex(position)
            }
        }
        pager.registerOnPageChangeCallback(pageChangeCallback!!)
    }

    private fun updateIndex(position: Int) {
        indexText?.text = getString(R.string.image_index_format, position + 1, imagePaths.size)
    }

    private fun decodeBitmap(path: String): android.graphics.Bitmap? {
        return try {
            val file = java.io.File(path)
            val dir = appDir ?: return null
            if (!file.canonicalPath.startsWith(dir.canonicalPath)) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            var inSampleSize = 1
            val maxDim = 2048
            while (options.outWidth / inSampleSize > maxDim || options.outHeight / inSampleSize > maxDim) {
                inSampleSize *= 2
            }
            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroyView() {
        pageChangeCallback?.let { viewPager?.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        viewPager?.adapter = null
        viewPager = null
        indexText = null
        super.onDestroyView()
    }
}
