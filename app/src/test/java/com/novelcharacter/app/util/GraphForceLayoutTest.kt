package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GraphForceLayout] — 관계도 배치. 이 앱에서 **캐릭터 수에 대해 비용이 2차식인 유일한 계산**이다.
 *
 * 이 하네스가 지키는 것은 넷이고, **앞의 둘은 서로 반대 방향의 사고를 막는다.**
 *
 * 1. **누적 순서가 종전과 같다** — 세력 쌍은 `(i, j)` 사전순, 엣지는 입력 순서. 힘은 부동소수
 *    덧셈으로 쌓이는데 이 계산은 300회를 도는 **되먹임**이라, 순서가 한 자리만 달라져도 마지막
 *    배치가 눈에 띄게 달라진다(실측: 순서를 흩으면 좌표 최대 차 37,660). 성능을 고치면서 **그림이
 *    바뀌면** 사용자에게는 그것이 곧 회귀다.
 * 2. **그런데 세력 쌍을 n²/2로 찾지는 않는다**(**반대편이다** — 1만 있으면 *"종전 중첩 루프를
 *    그대로 두고 정렬만 하는"* 구현이 통과한다. 그러면 이 판이 없애려던 비용이 그대로다.
 *    쌍을 세력별 묶음에서 뽑았는지를 **만들어진 쌍의 수**로 잰다).
 * 3. **같은 쌍에 힘을 두 번 주지 않는다** — 세력 둘을 함께 쓰는 두 인물은 묶음 두 곳에서
 *    나오고, 엣지는 같은 쌍을 잇는 관계가 여럿일 수 있다. 두 번 주면 **그 쌍만 유난히 붙는다.**
 * 4. **취소는 그때까지의 좌표를 남긴다** — 빈 목록을 돌려주면 화면이 노드를 원점에 겹쳐 그린다.
 *
 * **다섯째는 시험이되 성격이 다르다** — *"같은 입력이면 같은 그림"*은 오늘 갈릴 수 없는 자리다
 * (난수를 안 쓴다). 그래도 적는 것은 **초기 배치가 노드 순서로 원을 그린다**는 사실을 잠그기
 * 때문이다: 상한이 노드를 골라낼 때 순서를 건드리면 **남은 노드의 좌표까지 함께 바뀐다**
 * ([DisplayCap.rankedCap]이 입력 순서를 지키는 이유가 그것이다).
 */
class GraphForceLayoutTest {

    private fun nodes(count: Int, factionsOf: (Int) -> List<Long> = { emptyList() }) =
        (0 until count).map { GraphForceLayout.Node(it.toLong() + 1, factionsOf(it)) }

    // ── 1. 누적 순서가 계약이다 ──────────────────────────────────────────────

    @Test
    fun `세력 쌍은 i j 사전순이다`() {
        // 세력 2가 먼저 채워지도록 소속을 엇갈리게 준다 — 묶음 순서대로 뱉으면 사전순이 깨진다.
        val ns = nodes(6) { i ->
            when (i) {
                0 -> listOf(9L)
                1 -> listOf(7L)
                2 -> listOf(9L)
                3 -> listOf(7L)
                4 -> listOf(9L)
                else -> emptyList()
            }
        }
        val pairs = GraphForceLayout.factionPairs(ns).map { it[0] to it[1] }
        assertEquals(listOf(0 to 2, 0 to 4, 1 to 3, 2 to 4), pairs)
    }

    @Test
    fun `엣지 쌍은 입력 순서를 지킨다`() {
        val ns = nodes(4)
        val es = listOf(
            GraphForceLayout.Edge(3L, 1L),   // 인덱스 (2,0)
            GraphForceLayout.Edge(2L, 4L),   // 인덱스 (1,3)
            GraphForceLayout.Edge(1L, 2L)    // 인덱스 (0,1)
        )
        val pairs = GraphForceLayout.uniqueEdgePairs(ns, es).map { it[0] to it[1] }
        assertEquals(listOf(2 to 0, 1 to 3, 0 to 1), pairs)
    }

    // ── 2. 반대편 — 세력 쌍을 모든 쌍에서 걸러 찾지 않는다 ─────────────────────

    @Test
    fun `세력 쌍 수는 세력별 인원의 제곱합이지 전체 쌍이 아니다`() {
        // 60명이 세력 셋에 20명씩. 전체 쌍은 1,770이고 실제 같은 세력 쌍은 3 × C(20,2) = 570이다.
        val ns = nodes(60) { i -> listOf((i % 3).toLong()) }
        val pairs = GraphForceLayout.factionPairs(ns)
        assertEquals(570, pairs.size)
        assertTrue("전체 쌍보다 확실히 적어야 한다", pairs.size < 60 * 59 / 2)
    }

    @Test
    fun `세력이 없는 노드만 있으면 세력 쌍이 하나도 없다`() {
        assertEquals(0, GraphForceLayout.factionPairs(nodes(50)).size)
    }

    // ── 3. 같은 쌍에 두 번 주지 않는다 ───────────────────────────────────────

    @Test
    fun `세력 둘을 함께 쓰는 두 인물은 한 쌍으로만 센다`() {
        val ns = nodes(2) { listOf(1L, 2L) }
        assertEquals(1, GraphForceLayout.factionPairs(ns).size)
    }

    @Test
    fun `같은 쌍을 잇는 관계가 여럿이어도 인력은 한 번이다`() {
        val ns = nodes(3)
        val es = listOf(
            GraphForceLayout.Edge(1L, 2L),
            GraphForceLayout.Edge(2L, 1L),   // 방향만 반대 — 같은 쌍이다
            GraphForceLayout.Edge(1L, 2L),   // 같은 방향 중복
            GraphForceLayout.Edge(2L, 3L)
        )
        assertEquals(2, GraphForceLayout.uniqueEdgePairs(ns, es).size)
    }

    @Test
    fun `노드 집합에 없는 끝을 가진 관계는 버린다`() {
        // 상한이 인물을 접으면 한쪽 끝만 남은 관계가 생긴다 — 그리면 없는 노드로 선이 뻗는다.
        val ns = nodes(2)
        val es = listOf(GraphForceLayout.Edge(1L, 99L), GraphForceLayout.Edge(1L, 2L))
        assertEquals(1, GraphForceLayout.uniqueEdgePairs(ns, es).size)
    }

    // ── 4. 취소 ─────────────────────────────────────────────────────────────

    @Test
    fun `첫 반복 전에 취소해도 좌표는 노드 수만큼 돌아온다`() {
        val ns = nodes(5)
        val out = GraphForceLayout.compute(ns, emptyList(), isCancelled = { true })
        assertEquals(5, out.size)
        // 원형 초기 배치가 그대로 남는다 — 전부 원점이면 화면이 노드를 겹쳐 그린다.
        assertTrue(out.any { it.first != 0f || it.second != 0f })
    }

    // ── 5. 결정성과 초기 배치 ────────────────────────────────────────────────

    @Test
    fun `같은 입력이면 좌표가 정확히 같다`() {
        val ns = nodes(12) { i -> if (i % 2 == 0) listOf(1L) else emptyList() }
        val es = (0 until 11).map { GraphForceLayout.Edge(it.toLong() + 1, it.toLong() + 2) }
        assertEquals(GraphForceLayout.compute(ns, es), GraphForceLayout.compute(ns, es))
    }

    @Test
    fun `초기 배치는 노드 순서로 원을 그린다`() {
        // **순서가 곧 그림이다** — 상한이 인물을 골라낼 때 순서를 건드리면 남은 노드의 좌표까지
        // 함께 바뀐다. [DisplayCap.rankedCap]이 고른 순서가 아니라 입력 순서로 돌려주는 이유가 이것이고,
        // 그 사실을 여기서 못박아 둔다(반복을 돌리지 않고 초기 배치만 본다).
        val out = GraphForceLayout.compute(nodes(4), emptyList(), isCancelled = { true })
        val r = 150f   // max(150f, 4 × 30f)
        assertEquals(r, out[0].first, 0.01f); assertEquals(0f, out[0].second, 0.01f)
        assertEquals(0f, out[1].first, 0.01f); assertEquals(r, out[1].second, 0.01f)
        assertEquals(-r, out[2].first, 0.01f); assertEquals(0f, out[2].second, 0.01f)
        assertEquals(0f, out[3].first, 0.01f); assertEquals(-r, out[3].second, 0.01f)
    }

    @Test
    fun `노드가 없거나 하나면 계산 없이 돌아온다`() {
        assertEquals(emptyList<Pair<Float, Float>>(), GraphForceLayout.compute(emptyList(), emptyList()))
        assertEquals(listOf(0f to 0f), GraphForceLayout.compute(nodes(1), emptyList()))
    }

    // ── 6. 종전 구현과의 대조 — "고쳐도 그림이 그대로다" ─────────────────────
    //
    // 아래 [legacyCompute]는 이 판이 걷어낸 **종전 코드 그대로**다(`RelationshipGraphView.computeLayout`).
    // 성능만 고쳤다는 주장은 **두 산출이 같다**로만 증명되고, 이 계산은 300회를 도는 되먹임이라
    // 눈으로 읽어서는 증명되지 않는다 — 실제로 초판에서 세력 쌍 순서 하나 때문에 좌표가 37,660만큼
    // 갈렸고 코드는 멀쩡해 보였다. `ExportWorkbookParityTest`가 두 워크북 구현에 대해 같은 일을 한다.

    private fun legacyCompute(
        nodesCopy: List<GraphForceLayout.Node>,
        edgesCopy: List<GraphForceLayout.Edge>
    ): List<Pair<Float, Float>> {
        val count = nodesCopy.size
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val radius = kotlin.math.max(150f, count * 30f)
        for (i in 0 until count) {
            val angle = 2.0 * kotlin.math.PI * i / count
            xs[i] = (radius * kotlin.math.cos(angle)).toFloat()
            ys[i] = (radius * kotlin.math.sin(angle)).toFloat()
        }
        val iterations = kotlin.math.min(300, kotlin.math.max(100, count * 8))
        val k = kotlin.math.sqrt((radius * radius * 5) / count.toFloat())
        val temp = radius * 0.7f
        val nodeIndexMap = nodesCopy.withIndex().associate { (i, n) -> n.id to i }
        val processedPairs = mutableSetOf<Long>()
        for (iter in 0 until iterations) {
            val cooling = temp * (1f - iter.toFloat() / iterations)
            val dispX = FloatArray(count)
            val dispY = FloatArray(count)
            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val dx = xs[i] - xs[j]
                    val dy = ys[i] - ys[j]
                    val dist = kotlin.math.max(kotlin.math.sqrt(dx * dx + dy * dy), 0.1f)
                    val force = k * k / dist
                    val fx = dx / dist * force
                    val fy = dy / dist * force
                    dispX[i] += fx; dispY[i] += fy
                    dispX[j] -= fx; dispY[j] -= fy
                }
            }
            processedPairs.clear()
            for (edge in edgesCopy) {
                val iIdx = nodeIndexMap[edge.fromId] ?: continue
                val jIdx = nodeIndexMap[edge.toId] ?: continue
                val pairKey = kotlin.math.min(edge.fromId, edge.toId).shl(32) or
                    kotlin.math.max(edge.fromId, edge.toId)
                if (!processedPairs.add(pairKey)) continue
                val dx = xs[iIdx] - xs[jIdx]
                val dy = ys[iIdx] - ys[jIdx]
                val dist = kotlin.math.max(kotlin.math.sqrt(dx * dx + dy * dy), 0.1f)
                val force = dist * dist / k
                val fx = dx / dist * force
                val fy = dy / dist * force
                dispX[iIdx] -= fx; dispY[iIdx] -= fy
                dispX[jIdx] += fx; dispY[jIdx] += fy
            }
            val factionWeight = 0.3f
            processedPairs.clear()
            for (i in 0 until count) {
                val iFactions = nodesCopy[i].factionIds
                if (iFactions.isEmpty()) continue
                for (j in i + 1 until count) {
                    val jFactions = nodesCopy[j].factionIds
                    if (jFactions.isEmpty()) continue
                    if (!iFactions.any { it in jFactions }) continue
                    val pairKey = i.toLong().shl(32) or j.toLong()
                    if (!processedPairs.add(pairKey)) continue
                    val dx = xs[i] - xs[j]
                    val dy = ys[i] - ys[j]
                    val dist = kotlin.math.max(kotlin.math.sqrt(dx * dx + dy * dy), 0.1f)
                    val force = dist * dist / k * factionWeight
                    val fx = dx / dist * force
                    val fy = dy / dist * force
                    dispX[i] -= fx; dispY[i] -= fy
                    dispX[j] += fx; dispY[j] += fy
                }
            }
            for (i in 0 until count) {
                val dist = kotlin.math.max(kotlin.math.sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i]), 0.1f)
                xs[i] += dispX[i] / dist * kotlin.math.min(dist, cooling)
                ys[i] += dispY[i] / dist * kotlin.math.min(dist, cooling)
            }
        }
        return (0 until count).map { xs[it] to ys[it] }
    }

    @Test
    fun `세력과 관계가 얽힌 그래프에서 종전 구현과 좌표가 정확히 같다`() {
        // 세력이 겹치고(두 세력을 함께 쓰는 인물 포함) 중복 엣지·역방향 엣지가 섞인 입력 —
        // 세 갈래(반발·인력·세력)가 전부 도는 자리라야 누적 순서가 실제로 대조된다.
        val ns = (0 until 40).map { i ->
            GraphForceLayout.Node(
                id = i.toLong() + 1,
                factionIds = when {
                    i % 7 == 0 -> listOf(1L, 2L)
                    i % 3 == 0 -> listOf(1L)
                    i % 5 == 0 -> listOf(2L)
                    i % 11 == 0 -> listOf(3L)
                    else -> emptyList()
                }
            )
        }
        val es = buildList {
            for (i in 0 until 39) add(GraphForceLayout.Edge(i.toLong() + 1, i.toLong() + 2))
            add(GraphForceLayout.Edge(2L, 1L))    // 역방향 중복
            add(GraphForceLayout.Edge(1L, 2L))    // 같은 방향 중복
            add(GraphForceLayout.Edge(1L, 20L))
            add(GraphForceLayout.Edge(40L, 999L)) // 노드 집합 밖 — 버려져야 한다
        }
        assertEquals(legacyCompute(ns, es), GraphForceLayout.compute(ns, es))
    }

    @Test
    fun `세력이 하나도 없는 그래프에서도 종전 구현과 같다`() {
        val ns = (0 until 25).map { GraphForceLayout.Node(it.toLong() + 1) }
        val es = (0 until 24).map { GraphForceLayout.Edge(it.toLong() + 1, ((it * 7) % 25).toLong() + 1) }
        assertEquals(legacyCompute(ns, es), GraphForceLayout.compute(ns, es))
    }

    @Test
    fun `반복 수는 노드가 늘어도 300을 넘지 않는다`() {
        // 비용이 `반복 × n²`이라 반복까지 n에 비례하면 3차식이 된다.
        assertEquals(100, GraphForceLayout.iterationsFor(2))
        assertEquals(300, GraphForceLayout.iterationsFor(38))
        assertEquals(300, GraphForceLayout.iterationsFor(6420))
    }
}
