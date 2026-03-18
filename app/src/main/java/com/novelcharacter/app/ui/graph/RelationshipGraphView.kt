package com.novelcharacter.app.ui.graph

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.novelcharacter.app.R
import kotlinx.coroutines.*
import kotlin.math.*

data class GraphNode(
    val id: Long,
    val label: String,
    var x: Float = 0f,
    var y: Float = 0f
)

data class GraphEdge(
    val fromId: Long,
    val toId: Long,
    val label: String
)

class RelationshipGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.graph_node_fill)
        style = Paint.Style.FILL
    }

    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.graph_node_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.graph_edge)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.graph_node_text)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val edgeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.graph_edge_label)
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val nodeRadius = 40f
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var onNodeClickListener: ((Long) -> Unit)? = null
    private var layoutJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(0.3f, 3.0f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            translateX -= distanceX / scaleFactor
            translateY -= distanceY / scaleFactor
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tapX = (e.x - width / 2f) / scaleFactor - translateX
            val tapY = (e.y - height / 2f) / scaleFactor - translateY
            val hitNode = nodes.find { node ->
                val dx = tapX - node.x
                val dy = tapY - node.y
                sqrt(dx * dx + dy * dy) <= nodeRadius * 1.5f
            }
            hitNode?.let {
                performClick()
                onNodeClickListener?.invoke(it.id)
            }
            return true
        }
    })

    fun setOnNodeClickListener(listener: (Long) -> Unit) {
        onNodeClickListener = listener
    }

    fun setGraphData(nodeList: List<GraphNode>, edgeList: List<GraphEdge>) {
        nodes.clear()
        edges.clear()
        nodes.addAll(nodeList)
        edges.addAll(edgeList)
        resetTransform()
        layoutNodesAsync()
    }

    fun resetTransform() {
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
    }

    private fun layoutNodesAsync() {
        layoutJob?.cancel()
        if (nodes.isEmpty()) {
            invalidate()
            return
        }

        val count = nodes.size
        if (count == 1) {
            nodes[0].x = 0f
            nodes[0].y = 0f
            invalidate()
            return
        }

        // Take a snapshot for background computation
        val nodesCopy = nodes.map { it.copy() }
        val edgesCopy = edges.toList()

        layoutJob = scope.launch {
            val positions = withContext(Dispatchers.Default) {
                computeLayout(nodesCopy, edgesCopy)
            }
            // Apply computed positions back to nodes
            for (i in nodes.indices) {
                if (i < positions.size) {
                    nodes[i].x = positions[i].first
                    nodes[i].y = positions[i].second
                }
            }
            invalidate()
        }
    }

    private fun computeLayout(
        nodesCopy: List<GraphNode>,
        edgesCopy: List<GraphEdge>
    ): List<Pair<Float, Float>> {
        val count = nodesCopy.size
        val xs = FloatArray(count)
        val ys = FloatArray(count)

        // Circular initialization
        val radius = max(150f, count * 30f)
        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count
            xs[i] = (radius * cos(angle)).toFloat()
            ys[i] = (radius * sin(angle)).toFloat()
        }

        // Force-directed iterations
        val iterations = min(100, count * 5)
        val k = sqrt((radius * radius * 4) / count.toFloat())
        val temp = radius / 2f
        val nodeIndexMap = nodesCopy.withIndex().associate { (i, n) -> n.id to i }

        for (iter in 0 until iterations) {
            val cooling = temp * (1f - iter.toFloat() / iterations)

            val dispX = FloatArray(count)
            val dispY = FloatArray(count)

            // Repulsive forces
            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val dx = xs[i] - xs[j]
                    val dy = ys[i] - ys[j]
                    val dist = max(sqrt(dx * dx + dy * dy), 0.1f)
                    val force = k * k / dist
                    val fx = dx / dist * force
                    val fy = dy / dist * force
                    dispX[i] += fx; dispY[i] += fy
                    dispX[j] -= fx; dispY[j] -= fy
                }
            }

            // Attractive forces
            for (edge in edgesCopy) {
                val iIdx = nodeIndexMap[edge.fromId] ?: continue
                val jIdx = nodeIndexMap[edge.toId] ?: continue
                val dx = xs[iIdx] - xs[jIdx]
                val dy = ys[iIdx] - ys[jIdx]
                val dist = max(sqrt(dx * dx + dy * dy), 0.1f)
                val force = dist * dist / k
                val fx = dx / dist * force
                val fy = dy / dist * force
                dispX[iIdx] -= fx; dispY[iIdx] -= fy
                dispX[jIdx] += fx; dispY[jIdx] += fy
            }

            // Apply displacement with cooling
            for (i in 0 until count) {
                val dist = max(sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i]), 0.1f)
                xs[i] += dispX[i] / dist * min(dist, cooling)
                ys[i] += dispY[i] / dist * min(dist, cooling)
            }
        }

        return (0 until count).map { xs[it] to ys[it] }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (nodes.isEmpty()) return

        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(translateX, translateY)

        val nodeMap = nodes.associateBy { it.id }

        // Draw edges
        for (edge in edges) {
            val from = nodeMap[edge.fromId] ?: continue
            val to = nodeMap[edge.toId] ?: continue
            canvas.drawLine(from.x, from.y, to.x, to.y, edgePaint)

            // Edge label at midpoint
            val midX = (from.x + to.x) / 2
            val midY = (from.y + to.y) / 2
            canvas.drawText(edge.label, midX, midY - 6f, edgeLabelPaint)
        }

        // Draw nodes
        for (node in nodes) {
            canvas.drawCircle(node.x, node.y, nodeRadius, nodePaint)
            canvas.drawCircle(node.x, node.y, nodeRadius, nodeStrokePaint)

            // Truncate label to fit
            val label = if (node.label.length > 4) node.label.take(4) + "…" else node.label
            canvas.drawText(label, node.x, node.y + textPaint.textSize / 3, textPaint)
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        layoutJob?.cancel()
        scope.cancel()
    }
}
