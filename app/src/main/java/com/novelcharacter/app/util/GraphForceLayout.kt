package com.novelcharacter.app.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 관계도의 force-directed 배치 계산 — **이 앱에서 캐릭터 수에 대해 비용이 2차식인 유일한 계산**이다.
 *
 * 반복마다 **모든 노드 쌍**을 훑고(반발력), 반복 수는 `min(300, max(100, n*8))`이라 n ≥ 38이면
 * 300으로 고정된다. 즉 바닥 비용이 `300 × n(n-1)/2`이고 여기에 엣지 인력과 세력 인력이 얹힌다.
 * 노드 수를 가두는 일은 **이 계산의 몫이 아니다**(R-19 — 자르는 것은 표시 계층이다):
 * 상한은 [DisplayCap.GRAPH_NODE_LIMIT]이고 고르는 일은 [DisplayCap.rankedCap]이 한다.
 *
 * ### 왜 순수 계층인가
 *
 * 종전에는 이 계산이 `RelationshipGraphView` 안에 있었다. 그 파일은 `View` 하위라
 * **이 저장소의 어떤 로컬 검증도 값을 못 본다** — 커스텀 뷰 프로브는 *컴파일되는가*만 보고,
 * 순수 JVM 시험과 실클래스패스 프로브는 `ui/` 계층을 통째로 뺀다. 규모 프로브
 * (`tools/usage-probe/GraphLayoutScaleProbe.java`)도 같은 이유로 코드를 **베껴서** 재야 했다.
 * 여기로 내리면 셋 다 진짜 코드를 본다(`DisplayCap`·`CrossTableFold`·`CharacterValueLedger`가 같은 이유로 내려왔다).
 *
 * ### 누적 순서가 계약이다
 *
 * 힘은 부동소수 덧셈으로 쌓이는데 이 계산은 300회를 도는 **되먹임**이라, 누적 순서가 한 자리만
 * 달라져도 마지막 배치가 눈에 띄게 달라진다(실측: 세력 쌍을 다른 순서로 주면 좌표 최대 차 37,660).
 * 그래서 [factionPairs]는 **(i, j) 사전순**으로 세우고 엣지는 **입력 순서**를 지킨다 —
 * 종전 중첩 루프가 만들던 순서가 정확히 그것이라, 이 두 규칙이 *"고쳐도 그림이 그대로다"*를 만든다.
 * 시험이 그 순서를 직접 든다.
 */
object GraphForceLayout {

    /** 배치가 보는 노드 — 화면 타입(`GraphNode`)에서 이 둘만 쓴다. */
    data class Node(val id: Long, val factionIds: List<Long> = emptyList())

    /** 배치가 보는 엣지 — 힘은 쌍에만 걸리므로 방향은 보지 않는다. */
    data class Edge(val fromId: Long, val toId: Long)

    /** 반복 수 — n ≥ 38이면 상한 300에 붙는다. */
    fun iterationsFor(nodeCount: Int): Int = min(300, max(100, nodeCount * 8))

    /**
     * 같은 노드 쌍을 잇는 엣지가 여럿이면 **한 번만** 인력을 준다(중복 엣지의 인력 합산 방지).
     * 양 끝이 모두 노드 집합에 있는 것만 남기고 **입력 순서를 지킨다.**
     *
     * 종전에는 이 걸러내기가 **반복 안에서** 돌았다 — 입력이 반복 중에 변하지 않는데도
     * 300번 다시 지었고, 키가 `Long`이라 그때마다 박싱했다. 한 번만 짓는다.
     */
    fun uniqueEdgePairs(nodes: List<Node>, edges: List<Edge>): List<IntArray> {
        val indexById = HashMap<Long, Int>(nodes.size * 2)
        nodes.forEachIndexed { i, n -> indexById[n.id] = i }
        val seen = HashSet<Long>(edges.size * 2)
        val out = ArrayList<IntArray>(edges.size)
        for (edge in edges) {
            val i = indexById[edge.fromId] ?: continue
            val j = indexById[edge.toId] ?: continue
            // 키를 **인덱스**로 짓는다. 종전에는 행 id로 지어(`min.shl(32) or max`) id가 크면
            // 두 비트 마당이 겹칠 수 있었는데, 인덱스는 노드 수 미만이라 원리적으로 겹치지 않는다.
            // 인덱스와 id는 이 집합 안에서 일대일이라 **묶이는 쌍은 종전과 같다.**
            val key = if (i <= j) i.toLong().shl(32) or j.toLong() else j.toLong().shl(32) or i.toLong()
            if (!seen.add(key)) continue
            out.add(intArrayOf(i, j))
        }
        return out
    }

    /**
     * 같은 세력을 하나라도 공유하는 노드 쌍 — **(i, j) 사전순**이고 중복이 없다.
     *
     * 종전에는 이 쌍을 `for i { for j>i { iFactions.any { it in jFactions } } }`로 찾았다.
     * 그 모양의 비용은 **소속 여부와 무관하게 언제나 n²/2**인데, 실제로 힘을 받는 쌍은
     * 세력별 인원의 제곱합(Σ m²/2)이라 훨씬 작다 — 실측에서 노드 200일 때 83,844회를 훑어
     * 2,903쌍을 찾았고(29배), 841일 때는 904,505회를 훑어 12,272쌍을 찾았다(74배).
     * 세력별로 묶어 그 안에서만 짝지으면 찾는 일이 곧 결과가 된다.
     *
     * **여기서는 중복 제거가 진짜로 필요하다** — 두 노드가 세력 둘을 함께 쓰면 두 묶음에서
     * 같은 쌍이 나온다. 종전 중첩 루프에도 같은 이름의 장치가 있었지만 그쪽은 `i < j` 루프라
     * **한 쌍이 두 번 올 수 없어 원리적으로 아무것도 거르지 못했다**(반복마다 헛되이 지어진
     * 박싱 集合이었다). 장치가 제자리를 찾은 것이지 새로 생긴 것이 아니다.
     */
    fun factionPairs(nodes: List<Node>): List<IntArray> {
        val byFaction = LinkedHashMap<Long, MutableList<Int>>()
        nodes.forEachIndexed { i, node ->
            for (f in node.factionIds) byFaction.getOrPut(f) { ArrayList() }.add(i)
        }
        val seen = HashSet<Long>()
        val out = ArrayList<IntArray>()
        for (members in byFaction.values) {
            for (x in members.indices) {
                for (y in x + 1 until members.size) {
                    val a = members[x]
                    val b = members[y]
                    val i = min(a, b)
                    val j = max(a, b)
                    if (seen.add(i.toLong().shl(32) or j.toLong())) out.add(intArrayOf(i, j))
                }
            }
        }
        // 사전순 — 위 KDoc의 '누적 순서가 계약이다'가 여기 걸려 있다.
        out.sortWith(compareBy({ it[0] }, { it[1] }))
        return out
    }

    /**
     * 배치를 계산해 노드 순서대로 좌표를 돌려준다.
     *
     * [isCancelled]는 반복 머리에서 한 번씩 묻는다 — 배경 작업이 취소되면 그 자리에서 멈추고
     * **그때까지의 좌표를 그대로 돌려준다**(빈 목록이 아니다: 화면이 원형 초기 배치라도 그릴 수 있다).
     */
    fun compute(
        nodes: List<Node>,
        edges: List<Edge>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): List<Pair<Float, Float>> {
        val count = nodes.size
        if (count == 0) return emptyList()
        if (count == 1) return listOf(0f to 0f)

        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val radius = max(150f, count * 30f)
        for (i in 0 until count) {
            val angle = 2.0 * PI * i / count
            xs[i] = (radius * cos(angle)).toFloat()
            ys[i] = (radius * sin(angle)).toFloat()
        }

        val iterations = iterationsFor(count)
        val k = sqrt((radius * radius * 5) / count.toFloat())
        val temp = radius * 0.7f
        val factionWeight = 0.3f

        val edgePairs = uniqueEdgePairs(nodes, edges)
        val facPairs = factionPairs(nodes)

        // 반복마다 새 배열을 만들지 않는다 — 300회 × 2개 × n이 전부 쓰레기가 된다.
        val dispX = FloatArray(count)
        val dispY = FloatArray(count)

        for (iter in 0 until iterations) {
            if (isCancelled()) break
            val cooling = temp * (1f - iter.toFloat() / iterations)
            java.util.Arrays.fill(dispX, 0f)
            java.util.Arrays.fill(dispY, 0f)

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

            // 인력 (연결된 노드만)
            for (pair in edgePairs) {
                val i = pair[0]
                val j = pair[1]
                val dx = xs[i] - xs[j]
                val dy = ys[i] - ys[j]
                val dist = max(sqrt(dx * dx + dy * dy), 0.1f)
                val force = dist * dist / k
                val fx = dx / dist * force
                val fy = dy / dist * force
                dispX[i] -= fx; dispY[i] -= fy
                dispX[j] += fx; dispY[j] += fy
            }

            // 세력 클러스터링 — 같은 세력 소속끼리 약한 인력
            for (pair in facPairs) {
                val i = pair[0]
                val j = pair[1]
                val dx = xs[i] - xs[j]
                val dy = ys[i] - ys[j]
                val dist = max(sqrt(dx * dx + dy * dy), 0.1f)
                val force = dist * dist / k * factionWeight
                val fx = dx / dist * force
                val fy = dy / dist * force
                dispX[i] -= fx; dispY[i] -= fy
                dispX[j] += fx; dispY[j] += fy
            }

            for (i in 0 until count) {
                val dist = max(sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i]), 0.1f)
                xs[i] += dispX[i] / dist * min(dist, cooling)
                ys[i] += dispY[i] / dist * min(dist, cooling)
            }
        }

        return (0 until count).map { xs[it] to ys[it] }
    }
}
