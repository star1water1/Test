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
    val isSecondary: Boolean = false,
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

    // 엣지 쌍별 개수 캐시 (setGraphData/updateEdges 시 갱신, onDraw마다 재생성 방지)
    private var cachedPairEdgeCount = mapOf<Long, Int>()

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

    // 라벨 배경 (가독성 향상)
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // 관계 타입별 색상 (외부에서 커스터마이즈 가능)
    private var relationshipColors: Map<String, Int> = DEFAULT_COLORS

    companion object {
        val DEFAULT_COLORS = mapOf(
            "연인" to Color.parseColor("#E91E63"),
            "적" to Color.parseColor("#F44336"),   // 다크 배경에서 보이도록 밝은 빨강
            "라이벌" to Color.parseColor("#FF5722"),
            "동료" to Color.parseColor("#2196F3"),
            "친구" to Color.parseColor("#4CAF50"),
            "부모-자식" to Color.parseColor("#9C27B0"),
            "형제자매" to Color.parseColor("#FF9800"),
            "멘토-제자" to Color.parseColor("#00BCD4"),
            "기타" to Color.parseColor("#9E9E9E")
        )
    }

    /**
     * 외부에서 관계 유형별 색상 맵을 설정한다.
     * @param colorMap 관계 유형 → 색상 hex 문자열 (예: "#E91E63")
     */
    fun setRelationshipColors(colorMap: Map<String, String>) {
        val parsed = mutableMapOf<String, Int>()
        for ((type, hex) in colorMap) {
            try {
                parsed[type] = Color.parseColor(hex)
            } catch (e: Exception) {
                android.util.Log.w("RelGraphView", "Invalid color '$hex' for type '$type'", e)
            }
        }
        // 기본 색상에 커스텀 색상을 오버레이
        relationshipColors = DEFAULT_COLORS + parsed
        invalidate()
    }

    private val nodeRadius = 40f
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var onNodeClickListener: ((Long) -> Unit)? = null
    private var onNodeLongClickListener: ((Long) -> Unit)? = null
    private var layoutJob: Job? = null
    private var supervisorJob = SupervisorJob()
    private var scope = CoroutineScope(supervisorJob + Dispatchers.Main)

    // 화살표 그리기용
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()
    private val dashEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)

    // 곡선 경로용
    private val curvePath = Path()

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

        override fun onLongPress(e: MotionEvent) {
            val tapX = (e.x - width / 2f) / scaleFactor - translateX
            val tapY = (e.y - height / 2f) / scaleFactor - translateY
            val hitNode = nodes.find { node ->
                val dx = tapX - node.x
                val dy = tapY - node.y
                sqrt(dx * dx + dy * dy) <= nodeRadius * 1.5f
            }
            hitNode?.let {
                performLongClick()
                onNodeLongClickListener?.invoke(it.id)
            }
        }
    })

    fun setOnNodeClickListener(listener: (Long) -> Unit) {
        onNodeClickListener = listener
    }

    fun setOnNodeLongClickListener(listener: (Long) -> Unit) {
        onNodeLongClickListener = listener
    }

    fun setGraphData(nodeList: List<GraphNode>, edgeList: List<GraphEdge>) {
        nodes = nodeList.map { it.copy() }
        edges = edgeList.toList()
        rebuildPairEdgeCache()
        resetTransform()
        layoutNodesAsync()
    }

    /**
     * 엣지 데이터만 갱신 (노드 레이아웃 유지). 시점 변경 시 사용.
     */
    fun updateEdges(edgeList: List<GraphEdge>) {
        edges = edgeList.toList()
        rebuildPairEdgeCache()
        invalidate()
    }

    private fun rebuildPairEdgeCache() {
        val counts = mutableMapOf<Long, Int>()
        for (edge in edges) {
            val key = min(edge.fromId, edge.toId).shl(32) or max(edge.fromId, edge.toId)
            counts[key] = (counts[key] ?: 0) + 1
        }
        cachedPairEdgeCount = counts
    }

    fun resetTransform() {
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
    }

    /**
     * 그래프 전체가 화면에 들어오도록 자동 줌/패닝 조정
     */
    private fun fitToScreen() {
        if (nodes.isEmpty() || width == 0 || height == 0) return

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (node in nodes) {
            minX = min(minX, node.x - nodeRadius)
            maxX = max(maxX, node.x + nodeRadius)
            minY = min(minY, node.y - nodeRadius)
            maxY = max(maxY, node.y + nodeRadius)
        }

        val graphWidth = maxX - minX
        val graphHeight = maxY - minY
        if (graphWidth < 1f || graphHeight < 1f) return

        // 뷰 크기에서 여백 제외
        val viewWidth = width * 0.85f
        val viewHeight = height * 0.85f

        scaleFactor = min(viewWidth / graphWidth, viewHeight / graphHeight).coerceIn(0.3f, 3.0f)

        // 그래프 중심을 뷰 중심에 맞춤
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        translateX = -centerX
        translateY = -centerY
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
            fitToScreen()
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
            fitToScreen()
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

        // 반복 횟수 증가 (더 안정적인 레이아웃)
        val iterations = min(300, max(100, count * 8))
        // 반발력 강화 — 노드 간 최소 거리를 넓혀 밀집 방지
        val k = sqrt((radius * radius * 6) / count.toFloat())
        val temp = radius
        val nodeIndexMap = nodesCopy.withIndex().associate { (i, n) -> n.id to i }

        val processedPairs = mutableSetOf<Long>()
        for (iter in 0 until iterations) {
            if (Thread.currentThread().isInterrupted) break
            val cooling = temp * (1f - iter.toFloat() / iterations)

            val dispX = FloatArray(count)
            val dispY = FloatArray(count)

            // 반발력 (모든 노드 쌍)
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

            // 인력 (연결된 노드만) — 중복 엣지의 인력 합산 방지
            processedPairs.clear()
            for (edge in edgesCopy) {
                val iIdx = nodeIndexMap[edge.fromId] ?: continue
                val jIdx = nodeIndexMap[edge.toId] ?: continue
                val pairKey = min(edge.fromId, edge.toId).shl(32) or max(edge.fromId, edge.toId)
                if (!processedPairs.add(pairKey)) continue  // 같은 쌍은 한 번만
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

        // 같은 노드 쌍 사이의 곡선 오프셋 인덱스
        val pairEdgeIndex = mutableMapOf<Long, Int>()

        // Draw edges with type-based colors and intensity-based width
        for (edge in edges) {
            val from = nodeMap[edge.fromId] ?: continue
            val to = nodeMap[edge.toId] ?: continue

            val edgeColor = relationshipColors[edge.label]
                ?: ContextCompat.getColor(context, R.color.graph_edge)

            edgePaint.color = if (edge.isActive) edgeColor else Color.argb(80, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            edgePaint.strokeWidth = (edge.intensity * 0.5f).coerceIn(1f, 5f)

            if (!edge.isActive) {
                edgePaint.pathEffect = dashEffect
            } else {
                edgePaint.pathEffect = null
            }

            // 같은 노드 쌍의 다중 엣지 → 곡선으로 분리
            val pairKey = min(edge.fromId, edge.toId).shl(32) or max(edge.fromId, edge.toId)
            val totalEdges = cachedPairEdgeCount[pairKey] ?: 1
            val edgeIdx = pairEdgeIndex.getOrPut(pairKey) { 0 }
            pairEdgeIndex[pairKey] = edgeIdx + 1

            // 곡선 오프셋 계산 (0이면 직선, 양수/음수로 좌우 분리)
            val curveOffset = if (totalEdges <= 1) {
                0f
            } else {
                val spread = 40f  // 곡선 간 간격
                val center = (totalEdges - 1) / 2f
                (edgeIdx - center) * spread
            }

            // 라벨 위치 계산
            val midX: Float
            val midY: Float

            if (abs(curveOffset) < 1f) {
                // 직선
                canvas.drawLine(from.x, from.y, to.x, to.y, edgePaint)
                midX = (from.x + to.x) / 2
                midY = (from.y + to.y) / 2

                if (!edge.isBidirectional) {
                    drawArrow(canvas, from.x, from.y, to.x, to.y, edgeColor)
                }
            } else {
                // 2차 베지어 곡선
                val mx = (from.x + to.x) / 2f
                val my = (from.y + to.y) / 2f
                val dx = to.x - from.x
                val dy = to.y - from.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 1f) continue
                // 법선 벡터 (선에 수직)
                val nx = -dy / dist
                val ny = dx / dist
                val ctrlX = mx + nx * curveOffset
                val ctrlY = my + ny * curveOffset

                curvePath.rewind()
                curvePath.moveTo(from.x, from.y)
                curvePath.quadTo(ctrlX, ctrlY, to.x, to.y)
                canvas.drawPath(curvePath, edgePaint)

                // 곡선의 중점 (t=0.5에서의 2차 베지어 점)
                midX = 0.25f * from.x + 0.5f * ctrlX + 0.25f * to.x
                midY = 0.25f * from.y + 0.5f * ctrlY + 0.25f * to.y

                if (!edge.isBidirectional) {
                    // 2차 베지어 t=0.75 지점에서 끝점 방향으로 화살표
                    val t = 0.75f
                    val arrowFromX = (1-t)*(1-t)*from.x + 2*(1-t)*t*ctrlX + t*t*to.x
                    val arrowFromY = (1-t)*(1-t)*from.y + 2*(1-t)*t*ctrlY + t*t*to.y
                    drawArrow(canvas, arrowFromX, arrowFromY, to.x, to.y, edgeColor)
                }
            }

            // Edge label — 반투명 배경으로 가독성 확보
            val labelText = edge.label
            val labelWidth = edgeLabelPaint.measureText(labelText)
            val labelHeight = edgeLabelPaint.textSize
            val labelY = midY - 6f

            labelBgPaint.color = if (edge.isActive) Color.argb(180, 0, 0, 0) else Color.argb(100, 0, 0, 0)
            canvas.drawRoundRect(
                midX - labelWidth / 2 - 4f,
                labelY - labelHeight + 2f,
                midX + labelWidth / 2 + 4f,
                labelY + 4f,
                4f, 4f,
                labelBgPaint
            )

            edgeLabelPaint.color = if (edge.isActive) edgeColor else Color.argb(80, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            canvas.drawText(labelText, midX, labelY, edgeLabelPaint)
        }

        // Reset edge paint
        edgePaint.pathEffect = null

        // Draw nodes
        for (node in nodes) {
            if (node.isSecondary) {
                nodePaint.alpha = 100
                nodeStrokePaint.pathEffect = dashEffect
            } else {
                nodePaint.alpha = 255
                nodeStrokePaint.pathEffect = null
            }
            canvas.drawCircle(node.x, node.y, nodeRadius, nodePaint)
            canvas.drawCircle(node.x, node.y, nodeRadius, nodeStrokePaint)

            textPaint.alpha = if (node.isSecondary) 120 else 255
            // 노드 이름: 6글자까지 표시 (원래 4글자)
            val label = if (node.label.length > 6) node.label.take(6) + "…" else node.label
            canvas.drawText(label, node.x, node.y + textPaint.textSize / 3, textPaint)
        }
        // Reset paint states
        nodePaint.alpha = 255
        nodeStrokePaint.pathEffect = null
        textPaint.alpha = 255

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
        arrowPath.rewind()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
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
