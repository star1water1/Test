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
    val label: String,
    val intensity: Int = 5,           // 1~10 관계 강도 → 선 굵기
    val isBidirectional: Boolean = true,
    val isActive: Boolean = true       // false이면 점선으로 표시 (해당 시점에 아직 없는 관계)
)

class RelationshipGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var nodes = listOf<GraphNode>()
    private var edges = listOf<GraphEdge>()

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

    // 관계 타입별 색상
    companion object {
        val RELATIONSHIP_COLORS = mapOf(
            "연인" to Color.parseColor("#E91E63"),
            "적" to Color.parseColor("#212121"),
            "라이벌" to Color.parseColor("#FF5722"),
            "동료" to Color.parseColor("#2196F3"),
            "친구" to Color.parseColor("#4CAF50"),
            "부모-자식" to Color.parseColor("#9C27B0"),
            "형제자매" to Color.parseColor("#FF9800"),
            "멘토-제자" to Color.parseColor("#00BCD4"),
            "기타" to Color.parseColor("#9E9E9E")
        )
    }

    private val nodeRadius = 40f
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var onNodeClickListener: ((Long) -> Unit)? = null
    private var layoutJob: Job? = null
    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(supervisorJob + Dispatchers.Main)

    // 화살표 그리기용
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

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
        nodes = nodeList.map { it.copy() }
        edges = edgeList.toList()
        resetTransform()
        layoutNodesAsync()
    }

    /**
     * 엣지 데이터만 갱신 (노드 레이아웃 유지). 시점 변경 시 사용.
     */
    fun updateEdges(edgeList: List<GraphEdge>) {
        edges = edgeList.toList()
        invalidate()
    }

    fun resetTransform() {
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
    }

    private fun layoutNodesAsync() {
        layoutJob?.cancel()
        val currentNodes = nodes
        if (currentNodes.isEmpty()) {
            invalidate()
            return
        }

        val count = currentNodes.size
        if (count == 1) {
            nodes = listOf(currentNodes[0].copy(x = 0f, y = 0f))
            invalidate()
            return
        }

        val nodesCopy = currentNodes.map { it.copy() }
        val edgesCopy = edges

        layoutJob = scope.launch {
            val positions = withContext(Dispatchers.Default) {
                computeLayout(nodesCopy, edgesCopy)
            }
            // Atomically replace with new immutable list containing updated positions
            nodes = nodesCopy.mapIndexed { i, node ->
                if (i < positions.size) node.copy(x = positions[i].first, y = positions[i].second)
                else node
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

        val radius = max(150f, count * 30f)
        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count
            xs[i] = (radius * cos(angle)).toFloat()
            ys[i] = (radius * sin(angle)).toFloat()
        }

        val iterations = min(100, count * 5)
        val k = sqrt((radius * radius * 4) / count.toFloat())
        val temp = radius / 2f
        val nodeIndexMap = nodesCopy.withIndex().associate { (i, n) -> n.id to i }

        for (iter in 0 until iterations) {
            if (Thread.currentThread().isInterrupted) break
            val cooling = temp * (1f - iter.toFloat() / iterations)

            val dispX = FloatArray(count)
            val dispY = FloatArray(count)

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

        // Draw edges with type-based colors and intensity-based width
        for (edge in edges) {
            val from = nodeMap[edge.fromId] ?: continue
            val to = nodeMap[edge.toId] ?: continue

            val edgeColor = RELATIONSHIP_COLORS[edge.label]
                ?: ContextCompat.getColor(context, R.color.graph_edge)

            edgePaint.color = if (edge.isActive) edgeColor else Color.argb(80, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            edgePaint.strokeWidth = (edge.intensity * 0.5f).coerceIn(1f, 5f)

            if (!edge.isActive) {
                edgePaint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            } else {
                edgePaint.pathEffect = null
            }

            canvas.drawLine(from.x, from.y, to.x, to.y, edgePaint)

            // 단방향 관계일 때 화살표 그리기
            if (!edge.isBidirectional) {
                drawArrow(canvas, from.x, from.y, to.x, to.y, edgeColor)
            }

            // Edge label at midpoint
            val midX = (from.x + to.x) / 2
            val midY = (from.y + to.y) / 2
            edgeLabelPaint.color = if (edge.isActive) edgeColor else Color.argb(80, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            canvas.drawText(edge.label, midX, midY - 6f, edgeLabelPaint)
        }

        // Reset edge paint
        edgePaint.pathEffect = null

        // Draw nodes
        for (node in nodes) {
            canvas.drawCircle(node.x, node.y, nodeRadius, nodePaint)
            canvas.drawCircle(node.x, node.y, nodeRadius, nodeStrokePaint)

            val label = if (node.label.length > 4) node.label.take(4) + "…" else node.label
            canvas.drawText(label, node.x, node.y + textPaint.textSize / 3, textPaint)
        }

        canvas.restore()
    }

    private fun drawArrow(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, color: Int) {
        val dx = toX - fromX
        val dy = toY - fromY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f) return

        // 화살표를 노드 가장자리에 그리기
        val ratio = (dist - nodeRadius - 5f) / dist
        val tipX = fromX + dx * ratio
        val tipY = fromY + dy * ratio

        val arrowSize = 12f
        val angle = atan2(dy, dx)
        val x1 = tipX - arrowSize * cos(angle - PI / 6).toFloat()
        val y1 = tipY - arrowSize * sin(angle - PI / 6).toFloat()
        val x2 = tipX - arrowSize * cos(angle + PI / 6).toFloat()
        val y2 = tipY - arrowSize * sin(angle + PI / 6).toFloat()

        arrowPaint.color = color
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(x1, y1)
            lineTo(x2, y2)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Always create a fresh scope on attach to avoid using a cancelled scope
        supervisorJob = SupervisorJob()
        scope = CoroutineScope(supervisorJob + Dispatchers.Main)
    }

    override fun onDetachedFromWindow() {
        layoutJob?.cancel()
        layoutJob = null
        supervisorJob.cancel()
        super.onDetachedFromWindow()
    }
}
