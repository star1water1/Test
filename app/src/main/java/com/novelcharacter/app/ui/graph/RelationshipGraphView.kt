package com.novelcharacter.app.ui.graph

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.novelcharacter.app.R
import com.novelcharacter.app.util.GraphForceLayout
import kotlinx.coroutines.*
import kotlin.math.*

data class GraphNode(
    val id: Long,
    val label: String,
    val isSecondary: Boolean = false,
    val factionIds: List<Long> = emptyList(),
    val factionColors: List<Int> = emptyList(),  // parsed Color ints
    val isDeceased: Boolean = false,             // 시간뷰에서 해당 시점 사망 상태 (회색 + †)
    var x: Float = 0f,
    var y: Float = 0f
)

data class GraphEdge(
    val fromId: Long,
    val toId: Long,
    val label: String,
    val intensity: Int = 5,           // 1~10 관계 강도 → 선 굵기
    val isBidirectional: Boolean = true,
    val isActive: Boolean = true,      // false이면 점선으로 표시 (해당 시점에 아직 없는 관계)
    val factionId: Long? = null,
    val isSecondary: Boolean = false   // 세력 필터 시 해당 세력 관계가 아니면 흐리게
)

/** 세력 간 관계 엣지 (B-3) — 세력 영역 중심점 사이에 그려진다 */
data class FactionRelationEdge(
    val factionId1: Long,
    val factionId2: Long,
    val label: String,
    val intensity: Int = 5,
    val isBidirectional: Boolean = true
)

class RelationshipGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * **그리기 기하는 `onDraw`가 아니라 여기서 짓는다**(B-214의 프레임 할당 축).
     *
     * `onDraw`는 확대·이동 제스처가 도는 동안 **프레임마다** 불린다(캔버스 변환만 바뀌고
     * 노드 좌표는 그대로다). 그런데 종전에는 그 안에서 노드 색인(`associateBy`)·다중 엣지
     * 색인 맵·잘린 라벨 문자열·세력 볼록껍질·세력 중심점을 **매번 새로 지었다** — 노드 N,
     * 엣지 E, 세력 F에 대해 프레임마다 `O(N + E + F·N log N)` 할당이 붙고, 그 쓰레기가
     * 곧 손가락 아래에서 도는 GC다.
     *
     * 그 값들은 전부 **데이터가 바뀔 때만** 바뀌므로 설정자를 통로로 삼아 그때 한 번 짓는다.
     * 통로를 속성 설정자에 둔 것이 요점이다 — 대입 자리가 셋(갱신·단일 노드·배치 완료)이라
     * 호출을 손으로 달면 **한 자리가 빠지고 캐시만 낡는다**(같은 부류를 이 저장소가
     * `cachedPairEdgeCount`에서 이미 한 번 겪었다).
     */
    private var nodes = listOf<GraphNode>()
        set(value) {
            field = value
            rebuildNodeGeometry()
        }
    private var edges = listOf<GraphEdge>()
        set(value) {
            field = value
            rebuildEdgeGeometry()
        }
    private var factionRelationEdges = listOf<FactionRelationEdge>()

    /** 노드 id → 노드 (엣지 양 끝을 찾는다) */
    private var nodeById = mapOf<Long, GraphNode>()

    /** 노드와 **같은 차례**의 그리기 라벨 — 6글자 절단과 사망 † 접두가 이미 붙어 있다 */
    private var nodeDrawLabels = listOf<String>()

    /** 세력 영역(볼록껍질/단일 원) — 노드 좌표가 바뀔 때만 다시 짓는다 */
    private var factionAreas = listOf<FactionArea>()

    /** 세력 id → 멤버 중심점 (세력 간 관계 엣지의 양 끝) */
    private var factionCentroids = mapOf<Long, PointF>()

    /** 엣지와 **같은 차례**의 곡선 오프셋 — 0이면 직선이다 */
    private var edgeCurveOffsets = FloatArray(0)

    /**
     * 세력 영역 한 덩어리의 그리기 기하.
     *
     * [path]가 있으면 다각형이고, 없으면 ([cx], [cy]) 중심의 원이다(멤버가 한 명인 세력).
     */
    private class FactionArea(val color: Int, val path: Path?, val cx: Float, val cy: Float)

    /** 세력 간 관계 엣지 설정 (B-3) — 세력 영역 표시가 켜져 있을 때 함께 그려진다 */
    fun setFactionRelationEdges(edgeList: List<FactionRelationEdge>) {
        factionRelationEdges = edgeList
        invalidate()
    }

    /** 세력 표기: 영역(배경 영역) 토글 */
    var showFactionArea: Boolean = true
        set(value) { field = value; invalidate() }

    /** 세력 표기: 선(노드 링) 토글 */
    var showFactionBorder: Boolean = true
        set(value) { field = value; invalidate() }

    /** 세력 표기: 관계(세력 자동 엣지) 토글 */
    var showFactionEdges: Boolean = true
        set(value) { field = value; invalidate() }

    private val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // 엣지 쌍별 개수 캐시 (setGraphData 시 갱신, onDraw마다 재생성 방지)
    private var cachedPairEdgeCount = mapOf<Long, Int>()

    private val defaultNodeColor = ContextCompat.getColor(context, R.color.graph_node_fill)
    private val deceasedNodeColor = 0xFF9E9E9E.toInt() // 시간뷰 사망 노드 회색

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = defaultNodeColor
        style = Paint.Style.FILL
    }

    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.graph_node_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /** 관계 유형 색이 없을 때의 기본 엣지 색 — 종전에는 **엣지마다 프레임마다** 리소스를 물었다 */
    private val defaultEdgeColor = ContextCompat.getColor(context, R.color.graph_edge)

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = defaultEdgeColor
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

    // 라벨 배경 (가독성 향상) — 라이트/다크 모드별 대비 확보
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkMode) Color.argb(180, 0, 0, 0) else Color.argb(210, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // 라벨 배경 테두리 (라이트모드에서 경계 구분용)
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDarkMode) Color.TRANSPARENT else Color.argb(60, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // 세력 배경 페인트
    private val factionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 세력 링 페인트
    private val factionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // 관계 타입별 기본 색 — 리소스 유래(라이트/다크는 리소스 한정자가 자동 반영).
    // 사용자 커스텀(setRelationshipColors)이 이 위에 오버레이된다.
    private val defaultRelationshipColors: Map<String, Int> = mapOf(
        "연인" to ContextCompat.getColor(context, R.color.graph_rel_lover),
        "적" to ContextCompat.getColor(context, R.color.graph_rel_enemy),
        "라이벌" to ContextCompat.getColor(context, R.color.graph_rel_rival),
        "동료" to ContextCompat.getColor(context, R.color.graph_rel_ally),
        "친구" to ContextCompat.getColor(context, R.color.graph_rel_friend),
        "부모-자식" to ContextCompat.getColor(context, R.color.graph_rel_parent_child),
        "형제자매" to ContextCompat.getColor(context, R.color.graph_rel_sibling),
        "멘토-제자" to ContextCompat.getColor(context, R.color.graph_rel_mentor),
        "기타" to ContextCompat.getColor(context, R.color.graph_rel_other)
    )

    // 관계 타입별 색상 (외부에서 커스터마이즈 가능)
    private var relationshipColors: Map<String, Int> = defaultRelationshipColors

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
        relationshipColors = defaultRelationshipColors + parsed
        invalidate()
    }

    private val nodeRadius = 40f

    /**
     * 세력 영역의 둥근 모서리 — 값이 상수([nodeRadius])라 프레임마다 새로 만들 것이 아니다.
     *
     * **선언이 [nodeRadius] 뒤인 것이 요점이다** — 앞에 두면 초기화 차례상 0으로 지어진다.
     */
    private val factionCornerEffect = CornerPathEffect(nodeRadius * 2f * 0.6f)
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
            scaleFactor = scaleFactor.coerceIn(0.1f, 3.0f)
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

    /**
     * 그래프 데이터를 갈아 끼운다.
     *
     * **같은 인물 집합이면 배치도 확대·이동도 그대로 둔다.** 이 화면은 필터 칩 하나,
     * 시점 슬라이더 한 칸에도 이 함수를 다시 부른다 — 그때마다 시야를 되돌리고 배치를
     * 처음부터 계산하면 ⓐ 사용자가 맞춰 놓은 확대·이동이 **조작할 때마다 사라지고**(원칙 04)
     * ⓑ 노드 수의 제곱인 힘-배치(B-213)가 칩·틱마다 다시 돈다.
     * 배치가 뜻을 잃는 것은 **노드가 더해지거나 빠졌을 때**뿐이므로 그때만 다시 계산하고
     * 화면에 맞춘다([fitToScreen]).
     *
     * 좌표는 id로 물려받고 나머지 속성(라벨·세력색·사망 표시)은 새 값이 이긴다 —
     * 시점이 바뀌면 좌표는 그대로여야 하고 색과 †는 따라와야 한다.
     */
    fun setGraphData(nodeList: List<GraphNode>, edgeList: List<GraphEdge>) {
        val keptPositions = nodes.associate { it.id to (it.x to it.y) }
        val sameNodeSet = keptPositions.size == nodeList.size && nodeList.all { it.id in keptPositions }
        nodes = nodeList.map { node ->
            val kept = keptPositions[node.id]
            if (kept != null) node.copy(x = kept.first, y = kept.second) else node.copy()
        }
        edges = edgeList.toList()
        if (sameNodeSet) {
            // 배치가 그대로다 — 그리기만 다시 한다(계산 중인 배치가 있으면 그쪽이 좌표를 마저 얹는다).
            invalidate()
        } else {
            resetTransform()
            layoutNodesAsync()
        }
    }

    /**
     * 엣지에서 파생되는 기하 — 쌍별 개수와 **엣지마다의 곡선 오프셋**.
     *
     * 오프셋은 종전에 `onDraw` 안에서 `mutableMapOf`에 등장 차례를 세며 구했다. 엣지 차례가
     * 고정이라 답이 프레임마다 같으므로 여기서 한 번 짓는다.
     */
    private fun rebuildEdgeGeometry() {
        // **양 끝이 다 있는 엣지만 센다.** `onDraw`가 끝을 못 찾은 엣지를 건너뛰므로
        // (관계도는 노드에 상한이 있고 필터가 캐릭터를 좁히니 매달린 엣지가 실재한다)
        // 세는 모수도 그것과 같아야 한다. 종전에는 **총수는 매달린 것까지 세고 차례는
        // 안 세어** 다중 엣지의 부채꼴이 한쪽으로 밀려 그려졌다 — 같은 값을 두 잣대로 재던 자리다.
        val counts = mutableMapOf<Long, Int>()
        for (edge in edges) {
            if (edge.fromId !in nodeById || edge.toId !in nodeById) continue
            val key = min(edge.fromId, edge.toId).shl(32) or max(edge.fromId, edge.toId)
            counts[key] = (counts[key] ?: 0) + 1
        }
        cachedPairEdgeCount = counts

        val offsets = FloatArray(edges.size)
        val seen = mutableMapOf<Long, Int>()
        for ((i, edge) in edges.withIndex()) {
            if (edge.fromId !in nodeById || edge.toId !in nodeById) continue
            val key = min(edge.fromId, edge.toId).shl(32) or max(edge.fromId, edge.toId)
            val total = counts[key] ?: 1
            val idx = seen.getOrPut(key) { 0 }
            seen[key] = idx + 1
            // 곡선 오프셋 (0이면 직선, 양수/음수로 좌우 분리) — 종전 onDraw의 셈 그대로다.
            offsets[i] = if (total <= 1) 0f else {
                val spread = 40f  // 곡선 간 간격
                val center = (total - 1) / 2f
                (idx - center) * spread
            }
        }
        edgeCurveOffsets = offsets
    }

    /**
     * 노드에서 파생되는 기하 — 색인 · 라벨 · 세력 영역 · 세력 중심점.
     *
     * 세력 영역의 볼록껍질은 `O(멤버 수 log 멤버 수)`라 프레임마다 돌 것이 아니다.
     * 노드 좌표는 배치가 끝날 때만 바뀌므로 그때 한 번 짓는다.
     */
    private fun rebuildNodeGeometry() {
        nodeById = nodes.associateBy { it.id }

        val labels = ArrayList<String>(nodes.size)
        for (node in nodes) {
            // 노드 이름: 6글자까지 표시, 시간뷰 사망 시 † 접두
            val base = if (node.label.length > 6) node.label.take(6) + "…" else node.label
            labels.add(if (node.isDeceased) "†$base" else base)
        }
        nodeDrawLabels = labels

        rebuildFactionGeometry()
        // **엣지 기하가 노드에 딸린다** — 매달린 엣지 판정([nodeById])이 바뀌기 때문이다.
        // 여기서 함께 짓지 않으면 배치가 끝난 뒤 곡선 부채꼴이 옛 모수로 남는다.
        rebuildEdgeGeometry()
    }

    /** 세력 영역(볼록껍질/단일 원)과 세력별 멤버 중심점을 짓는다 */
    private fun rebuildFactionGeometry() {
        // factionId → (color, list of member nodes) 매핑
        val factionNodes = mutableMapOf<Long, Pair<Int, MutableList<GraphNode>>>()
        for (node in nodes) {
            for (i in node.factionIds.indices) {
                if (i >= node.factionColors.size) break
                val fId = node.factionIds[i]
                val fColor = node.factionColors[i]
                val entry = factionNodes.getOrPut(fId) { fColor to mutableListOf() }
                entry.second.add(node)
            }
        }

        val padding = nodeRadius * 2f
        val areas = ArrayList<FactionArea>(factionNodes.size)
        for ((_, pair) in factionNodes) {
            val (factionColor, memberNodes) = pair
            if (memberNodes.size < 2) {
                // 단일 노드: 원으로 표시
                if (memberNodes.size == 1) {
                    val n = memberNodes[0]
                    areas.add(FactionArea(factionColor, null, n.x, n.y))
                }
                continue
            }

            // Convex hull 계산 (Graham scan)
            val points = memberNodes.map { PointF(it.x, it.y) }
            val hull = computeConvexHull(points)
            if (hull.size < 2) continue

            // hull + padding 으로 둥근 영역 그리기
            val path = Path()
            if (hull.size == 2) {
                // 두 점: 둥근 사각형으로 처리
                val p0 = hull[0]
                val p1 = hull[1]
                val dx = p1.x - p0.x
                val dy = p1.y - p0.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 1f) continue
                val nx = -dy / dist * padding
                val ny = dx / dist * padding
                path.moveTo(p0.x + nx, p0.y + ny)
                path.lineTo(p1.x + nx, p1.y + ny)
                path.lineTo(p1.x - nx, p1.y - ny)
                path.lineTo(p0.x - nx, p0.y - ny)
                path.close()
            } else {
                // 다각형을 padding만큼 확장 (각 꼭짓점을 중심에서 바깥으로)
                val cx = hull.map { it.x }.average().toFloat()
                val cy = hull.map { it.y }.average().toFloat()
                val expanded = hull.map { p ->
                    val dx = p.x - cx
                    val dy = p.y - cy
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    PointF(p.x + dx / dist * padding, p.y + dy / dist * padding)
                }
                path.moveTo(expanded[0].x, expanded[0].y)
                for (i in 1 until expanded.size) {
                    path.lineTo(expanded[i].x, expanded[i].y)
                }
                path.close()
            }
            areas.add(FactionArea(factionColor, path, 0f, 0f))
        }
        factionAreas = areas

        // 세력별 멤버 노드 중심점 — 세력 간 관계 엣지(B-3)의 양 끝이다.
        // **`factionAreas`와 모수가 다르다**: 색을 못 받은 세력(factionColors가 짧은 경우)도
        // 관계 엣지는 그린다. 그래서 영역 셈을 재활용하지 않고 따로 센다.
        val sums = mutableMapOf<Long, FloatArray>()  // factionId -> [sumX, sumY, count]
        for (node in nodes) {
            for (fId in node.factionIds) {
                val acc = sums.getOrPut(fId) { floatArrayOf(0f, 0f, 0f) }
                acc[0] += node.x
                acc[1] += node.y
                acc[2] += 1f
            }
        }
        factionCentroids = sums.mapValues { (_, acc) -> PointF(acc[0] / acc[2], acc[1] / acc[2]) }
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

        scaleFactor = min(viewWidth / graphWidth, viewHeight / graphHeight).coerceAtMost(3.0f)

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
            // **좌표만 id로 얹는다** — 계산이 도는 동안 속성만 바뀐 갱신([setGraphData]의 같은
            // 인물 집합 갈래)이 지나갔을 수 있고, 계산에 넣은 옛 벌로 통째로 갈아 끼우면
            // 그 갱신이 **말없이 되돌아간다**(시점을 옮겼는데 사망 표시가 옛 시점으로 돌아가는 모양).
            val laidOut = nodesCopy.mapIndexedNotNull { i, node ->
                positions.getOrNull(i)?.let { node.id to it }
            }.toMap()
            nodes = nodes.map { node ->
                laidOut[node.id]?.let { node.copy(x = it.first, y = it.second) } ?: node
            }
            fitToScreen()
            invalidate()
        }
    }

    /**
     * 배치 계산은 [GraphForceLayout]이 한다 — 이 파일은 `View` 하위라 **로컬 검증이 값을 못 본다**
     * (커스텀 뷰 프로브는 컴파일만 보고, 순수 시험·실클래스패스 프로브는 `ui/` 계층을 통째로 뺀다).
     * 노드 수의 상한은 이 계산이 아니라 화면이 정한다(R-19) — `RelationshipGraphFragment`.
     */
    private fun computeLayout(
        nodesCopy: List<GraphNode>,
        edgesCopy: List<GraphEdge>
    ): List<Pair<Float, Float>> = GraphForceLayout.compute(
        nodes = nodesCopy.map { GraphForceLayout.Node(it.id, it.factionIds) },
        edges = edgesCopy.map { GraphForceLayout.Edge(it.fromId, it.toId) }
    )

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

        // Draw faction backgrounds (before edges and nodes)
        if (showFactionArea) {
            drawFactionBackgrounds(canvas)
            drawFactionRelationEdges(canvas)
        }

        // Draw edges with type-based colors and intensity-based width
        // **`withIndex()`를 쓰지 않는다** — 항목마다 `IndexedValue`를 하나씩 만든다.
        // 이 루프의 할당을 없애려던 것이 이 수리라 그 자리에 다른 할당을 넣지 않는다.
        for (edgeIndex in edges.indices) {
            val edge = edges[edgeIndex]
            val from = nodeById[edge.fromId] ?: continue
            val to = nodeById[edge.toId] ?: continue

            val edgeColor = relationshipColors[edge.label] ?: defaultEdgeColor

            // isSecondary(세력 필터), isActive(시간뷰) 모두 반영
            val edgeAlpha = when {
                !edge.isActive -> 80
                edge.isSecondary -> 60
                else -> 255
            }
            edgePaint.color = Color.argb(edgeAlpha, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            edgePaint.strokeWidth = (edge.intensity * 0.5f).coerceIn(1f, 5f)

            if (!edge.isActive) {
                edgePaint.pathEffect = dashEffect
            } else {
                edgePaint.pathEffect = null
            }

            // 같은 노드 쌍의 다중 엣지 → 곡선으로 분리 ([rebuildEdgeGeometry]가 미리 잰다)
            val curveOffset = edgeCurveOffsets.getOrElse(edgeIndex) { 0f }

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

            val bgAlpha = when {
                edge.isSecondary -> if (isDarkMode) 60 else 80
                !edge.isActive -> if (isDarkMode) 100 else 140
                else -> if (isDarkMode) 180 else 210
            }
            labelBgPaint.color = if (isDarkMode) {
                Color.argb(bgAlpha, 0, 0, 0)
            } else {
                Color.argb(bgAlpha, 255, 255, 255)
            }
            val labelLeft = midX - labelWidth / 2 - 4f
            val labelTop = labelY - labelHeight + 2f
            val labelRight = midX + labelWidth / 2 + 4f
            val labelBottom = labelY + 4f
            canvas.drawRoundRect(labelLeft, labelTop, labelRight, labelBottom, 4f, 4f, labelBgPaint)
            if (!isDarkMode) {
                canvas.drawRoundRect(labelLeft, labelTop, labelRight, labelBottom, 4f, 4f, labelBorderPaint)
            }

            edgeLabelPaint.color = Color.argb(edgeAlpha, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            canvas.drawText(labelText, midX, labelY, edgeLabelPaint)
        }

        // Reset edge paint
        edgePaint.pathEffect = null

        // Draw nodes
        val showFactionRings = showFactionBorder
        for (nodeIndex in nodes.indices) {
            val node = nodes[nodeIndex]
            // Draw faction rings outside the node circle
            if (showFactionRings && node.factionColors.isNotEmpty()) {
                val ringWidth = 4f
                val ringGap = 1f
                for ((ringIdx, factionColor) in node.factionColors.withIndex()) {
                    val ringRadius = nodeRadius + (ringIdx + 1) * (ringWidth + ringGap)
                    factionRingPaint.color = factionColor
                    factionRingPaint.strokeWidth = ringWidth
                    factionRingPaint.alpha = if (node.isSecondary) 80 else 220
                    canvas.drawCircle(node.x, node.y, ringRadius, factionRingPaint)
                }
            }

            // 시간뷰: 해당 시점 사망 캐릭터는 회색 처리.
            // Paint.color 설정은 알파를 함께 초기화하므로 반드시 알파보다 먼저 설정한다
            nodePaint.color = if (node.isDeceased) deceasedNodeColor else defaultNodeColor
            if (node.isSecondary) {
                nodePaint.alpha = if (node.isDeceased) 80 else 100
                nodeStrokePaint.pathEffect = dashEffect
            } else {
                nodePaint.alpha = if (node.isDeceased) 160 else 255
                nodeStrokePaint.pathEffect = null
            }
            canvas.drawCircle(node.x, node.y, nodeRadius, nodePaint)
            canvas.drawCircle(node.x, node.y, nodeRadius, nodeStrokePaint)

            textPaint.alpha = if (node.isSecondary) 120 else 255
            // 이름 절단(6글자)과 사망 † 접두는 [rebuildNodeGeometry]가 미리 붙여 둔다
            val label = nodeDrawLabels.getOrElse(nodeIndex) { node.label }
            canvas.drawText(label, node.x, node.y + textPaint.textSize / 3, textPaint)
        }
        // Reset paint states
        nodePaint.color = defaultNodeColor
        nodePaint.alpha = 255
        nodeStrokePaint.pathEffect = null
        textPaint.alpha = 255

        canvas.restore()
    }

    /**
     * 세력별 멤버 노드들을 감싸는 반투명 배경(convex hull + padding)을 그린다.
     *
     * **기하는 [rebuildFactionGeometry]가 짓는다** — 여기서는 칠하기만 한다.
     */
    private fun drawFactionBackgrounds(canvas: Canvas) {
        if (factionAreas.isEmpty()) return
        val padding = nodeRadius * 2f
        for (area in factionAreas) {
            factionBgPaint.color = area.color
            factionBgPaint.alpha = 40
            val path = area.path
            if (path == null) {
                canvas.drawCircle(area.cx, area.cy, padding, factionBgPaint)
                continue
            }
            factionBgPaint.pathEffect = factionCornerEffect
            canvas.drawPath(path, factionBgPaint)
            factionBgPaint.pathEffect = null
        }
    }

    /**
     * 세력 간 관계 엣지 (B-3) — 각 세력의 멤버 노드 중심점 사이에
     * 관계 유형 색/강도 굵기의 선과 라벨을 그린다. 단방향이면 화살표.
     */
    private fun drawFactionRelationEdges(canvas: Canvas) {
        if (factionRelationEdges.isEmpty()) return
        if (factionCentroids.isEmpty()) return

        for (edge in factionRelationEdges) {
            // 세력별 멤버 중심점은 [rebuildFactionGeometry]가 미리 잰다
            val from = factionCentroids[edge.factionId1] ?: continue
            val to = factionCentroids[edge.factionId2] ?: continue
            val fromX = from.x
            val fromY = from.y
            val toX = to.x
            val toY = to.y

            val edgeColor = relationshipColors[edge.label] ?: defaultEdgeColor

            edgePaint.color = Color.argb(200, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            edgePaint.strokeWidth = (edge.intensity * 0.8f).coerceIn(2f, 8f)
            edgePaint.pathEffect = null
            canvas.drawLine(fromX, fromY, toX, toY, edgePaint)
            if (!edge.isBidirectional) {
                drawArrow(canvas, fromX, fromY, toX, toY, edgeColor)
            }

            // 라벨 (반투명 배경)
            val midX = (fromX + toX) / 2
            val midY = (fromY + toY) / 2
            val labelWidth = edgeLabelPaint.measureText(edge.label)
            val labelHeight = edgeLabelPaint.textSize
            val labelY = midY - 6f
            labelBgPaint.color = if (isDarkMode) Color.argb(180, 0, 0, 0) else Color.argb(210, 255, 255, 255)
            canvas.drawRoundRect(
                midX - labelWidth / 2 - 4f, labelY - labelHeight + 2f,
                midX + labelWidth / 2 + 4f, labelY + 4f, 4f, 4f, labelBgPaint
            )
            edgeLabelPaint.color = Color.argb(255, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            canvas.drawText(edge.label, midX, labelY, edgeLabelPaint)
        }
    }

    /**
     * Graham scan으로 convex hull 계산
     */
    private fun computeConvexHull(points: List<PointF>): List<PointF> {
        if (points.size <= 1) return points.toList()
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))

        fun cross(o: PointF, a: PointF, b: PointF): Float =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val lower = mutableListOf<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = mutableListOf<PointF>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        // Remove last point of each half because it's repeated
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
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
