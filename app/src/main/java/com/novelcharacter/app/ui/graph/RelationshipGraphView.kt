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
            hitNode?.let { onNodeClickListener?.invoke(it.id) }
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
        layoutNodes()
        resetTransform()
        invalidate()
    }

    fun resetTransform() {
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
    }

    private fun layoutNodes() {
        if (nodes.isEmpty()) return

        val count = nodes.size
        if (count == 1) {
            nodes[0].x = 0f
            nodes[0].y = 0f
            return
        }

        // Force-directed layout using simple circular initialization + spring iterations
        val radius = max(150f, count * 30f)
        nodes.forEachIndexed { i, node ->
            val angle = 2.0 * PI * i / count
            node.x = (radius * cos(angle)).toFloat()
            node.y = (radius * sin(angle)).toFloat()
        }

        // Run force-directed iterations
        val iterations = min(100, count * 5)
        val k = sqrt((radius * radius * 4) / count.toFloat()) // ideal distance
        val temp = radius / 2f
        val nodeIndexMap = nodes.withIndex().associate { (i, n) -> n.id to i }

        for (iter in 0 until iterations) {
            val cooling = temp * (1f - iter.toFloat() / iterations)

            // Repulsive forces between all node pairs
            val dispX = FloatArray(count)
            val dispY = FloatArray(count)

            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val dx = nodes[i].x - nodes[j].x
                    val dy = nodes[i].y - nodes[j].y
                    val dist = max(sqrt(dx * dx + dy * dy), 0.1f)
                    val force = k * k / dist
                    val fx = dx / dist * force
                    val fy = dy / dist * force
                    dispX[i] += fx; dispY[i] += fy
                    dispX[j] -= fx; dispY[j] -= fy
                }
            }

            // Attractive forces along edges
            for (edge in edges) {
                val iIdx = nodeIndexMap[edge.fromId] ?: continue
                val jIdx = nodeIndexMap[edge.toId] ?: continue
                val dx = nodes[iIdx].x - nodes[jIdx].x
                val dy = nodes[iIdx].y - nodes[jIdx].y
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
                nodes[i].x += dispX[i] / dist * min(dist, cooling)
                nodes[i].y += dispY[i] / dist * min(dist, cooling)
            }
        }
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
}
