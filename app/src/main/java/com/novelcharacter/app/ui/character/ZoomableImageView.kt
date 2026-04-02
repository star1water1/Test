package com.novelcharacter.app.ui.character

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrix = Matrix()
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var currentScale = 1f
    private var translateX = 0f
    private var translateY = 0f
    private val minScale = 1f
    private val maxScale = 5f

    init {
        scaleType = ScaleType.FIT_CENTER

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)
                val actualFactor = newScale / currentScale
                currentScale = newScale

                imageMatrix.postScale(actualFactor, actualFactor, detector.focusX, detector.focusY)
                applyMatrix()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.1f) {
                    resetZoom()
                } else {
                    currentScale = 3f
                    imageMatrix.reset()
                    imageMatrix.postScale(3f, 3f, e.x, e.y)
                    applyMatrix()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (currentScale > 1f) {
                    imageMatrix.postTranslate(-distanceX, -distanceY)
                    applyMatrix()
                    return true
                }
                return false
            }
        })
    }

    private fun applyMatrix() {
        scaleType = ScaleType.MATRIX
        setImageMatrix(imageMatrix)
    }

    fun resetZoom() {
        currentScale = 1f
        imageMatrix.reset()
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Disable ViewPager2 swipe when zoomed in
        if (currentScale > 1.1f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        return true
    }
}
