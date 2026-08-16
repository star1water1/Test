package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `util/ImageLinkGroupPlanner.kt` — "이미지" 시트의 링크 묶음 반영 계획 (B-239).
 *
 * **무엇을 재는가: 답이지 속도가 아니다.** 이 판이 없앤 것은 토큰마다 도는 `getByGroup`이고,
 * 그 자리를 **일괄 조회 한 번 + 쓰기를 흉내 내는 메모리 겹**이 대신한다. 겹이 틀리면
 * `>= 2` 가드가 다른 답을 내는데 — 1장짜리 묶음이 생기거나, 생겨야 할 묶음이 안 생긴다 —
 * **오류도 고지도 없다.** 조용한 행동 변화라 시험이 아니면 잡히지 않는다.
 *
 * **왜 겹이 필요한가(=이 클래스가 있는 이유):** 반영 루프는 제 안에서 쓰고, 그 쓰기가 뒤
 * 토큰의 조회 답을 바꾼다. 그래서 *읽기 전 일괄 조회*만으로는 안 되고 — B-238이 같은 함수
 * 앞쪽에서 쓴 모양이 그것이라 **본보기가 되지 못한다** — 쓰기를 순서대로 흉내 내야 한다.
 *
 * **방어선의 뼈대는 ③의 대조 시험이다.** 옛 코드(토큰마다 DB를 묻고 즉시 쓴다)를 [oracle]로
 * 그대로 다시 적어, 무작위로 지은 수천 가지 상태에서 **최종 DB 상태가 글자 그대로 같은지**
 * 본다. 손으로 고른 사례는 내가 생각해 낸 함정만 덮지만, 대조는 *내가 생각 못 한 함정*도 덮는다.
 *
 * (**절 수를 적지 않는다** — 아래 `── ① ~ ──` 머리가 세는 법이다.)
 */
class ImageLinkGroupPlannerTest {

    /** 편의: 계획을 (토큰 → id들) 쌍의 목록으로 편다. */
    private fun planOf(
        desired: Map<String, List<Long>>,
        current: Map<String, Set<Long>>
    ): List<Pair<String, List<Long>>> =
        ImageLinkGroupPlanner.plan(desired, current).map { it.token to it.ids }

    // ── ① 가드: 1장짜리 묶음은 만들지 않는다 ─────────────────────────────────
    // SQL 시절의 `(ids + existingIds).toSet().size >= 2` 그대로다.

    @Test
    fun `혼자인 토큰은 쓰지 않는다`() {
        assertEquals(emptyList<Pair<String, List<Long>>>(), planOf(mapOf("T" to listOf(1L)), emptyMap()))
    }

    @Test
    fun `시트가 둘을 들면 쓴다`() {
        assertEquals(listOf("T" to listOf(1L, 2L)), planOf(mapOf("T" to listOf(1L, 2L)), emptyMap()))
    }

    @Test
    fun `혼자라도 그 토큰에 이미 다른 식구가 있으면 쓴다`() {
        assertEquals(listOf("T" to listOf(1L)), planOf(mapOf("T" to listOf(1L)), mapOf("T" to setOf(9L))))
    }

    @Test
    fun `이미 그 토큰인 식구는 인원으로 두 번 세지 않는다`() {
        // 시트가 든 1과 DB의 1은 같은 이미지다 — toSet()이 접으므로 인원은 1이고 쓰지 않는다.
        assertEquals(emptyList<Pair<String, List<Long>>>(), planOf(mapOf("T" to listOf(1L)), mapOf("T" to setOf(1L))))
    }

    // ── ② 쓸 것이 없으면 쓰지 않는다 ─────────────────────────────────────────
    // 같은 백업을 다시 들이는 흔한 경로에서 쓰기가 통째로 사라진다. 겹이 있어
    // "이미 그 토큰인가"를 왕복 없이 알 수 있는 것이 근거다.

    @Test
    fun `전원이 이미 그 토큰이면 계획이 비어 있다`() {
        assertEquals(
            emptyList<Pair<String, List<Long>>>(),
            planOf(mapOf("T" to listOf(1L, 2L)), mapOf("T" to setOf(1L, 2L, 3L)))
        )
    }

    @Test
    fun `한 명이라도 새로 들어오면 쓴다`() {
        assertEquals(
            listOf("T" to listOf(1L, 2L)),
            planOf(mapOf("T" to listOf(1L, 2L)), mapOf("T" to setOf(1L)))
        )
    }

    // ── ③ 겹이 쓰기를 흉내 낸다 — 이 판의 요점 ────────────────────────────────
    // 이미지가 묶음 X에서 Y로 옮겨 가면, Y를 처리한 **뒤**의 X 조회는 그것을 식구로 세지
    // 않는다. 겹 없이 일괄 조회만 쓰면 X가 아직 그를 식구로 세어 가드의 답이 뒤집힌다.

    @Test
    fun `앞 토큰이 데려간 식구는 뒤 토큰의 인원에서 빠진다`() {
        // X에는 2번만 있었고, 그 2번을 Y가 데려간다 → X는 시트의 1번만 남아 혼자다.
        val desired = linkedMapOf("Y" to listOf(2L, 3L), "X" to listOf(1L))
        val current = mapOf("X" to setOf(2L))
        assertEquals(listOf("Y" to listOf(2L, 3L)), planOf(desired, current))
    }

    @Test
    fun `순서가 뒤바뀌면 답도 바뀐다 — 넣은 순서가 처리 순서라는 계약`() {
        // 같은 재료를 X 먼저 처리하면 X는 아직 2번을 식구로 세므로 둘이 되어 쓴다.
        val desired = linkedMapOf("X" to listOf(1L), "Y" to listOf(2L, 3L))
        val current = mapOf("X" to setOf(2L))
        assertEquals(listOf("X" to listOf(1L), "Y" to listOf(2L, 3L)), planOf(desired, current))
    }

    @Test
    fun `가드에 걸려 쓰지 않은 토큰은 겹도 바꾸지 않는다`() {
        // Z는 혼자라 쓰이지 않는다 → 4번은 여전히 W의 식구이고, 그래서 W는 둘이 된다.
        val desired = linkedMapOf("Z" to listOf(4L), "W" to listOf(5L))
        val current = mapOf("W" to setOf(4L))
        assertEquals(listOf("W" to listOf(5L)), planOf(desired, current))
    }

    // ── ④ 옛 코드와의 대조 — 무작위 상태에서 최종 DB가 같은가 ──────────────────

    /**
     * **수리 전 코드 그대로다.** 토큰마다 `getByGroup(token)`을 묻고 즉시 `setGroup(ids, token)`을
     * 쓴다. 이 함수가 이 시험의 정답지다 — B-239가 바꾼 것은 *어떻게 아는가*이지 *무엇을
     * 쓰는가*가 아니어야 한다.
     */
    private fun oracle(desired: Map<String, List<Long>>, initial: Map<Long, String?>): Map<Long, String?> {
        val db = initial.toMutableMap()
        for ((token, ids) in desired) {
            val existingIds = db.filterValues { it == token }.keys                 // getByGroup(token)
            if ((ids + existingIds).toSet().size >= 2) {
                for (id in ids) db[id] = token                                     // setGroup(ids, token)
            }
        }
        return db
    }

    /** 현행 경로: 호출부가 하는 일(시트가 든 토큰만 일괄 조회) + 계획대로 쓰기. */
    private fun viaPlanner(desired: Map<String, List<Long>>, initial: Map<Long, String?>): Map<Long, String?> {
        val db = initial.toMutableMap()
        val currentByToken = desired.keys
            .associateWith { token -> db.filterValues { it == token }.keys.toSet() }
            .filterValues { it.isNotEmpty() }
        for ((token, ids) in ImageLinkGroupPlanner.plan(desired, currentByToken)) {
            for (id in ids) db[id] = token
        }
        return db
    }

    @Test
    fun `무작위 상태 대조 — 옛 토큰별 루프와 최종 DB가 같다`() {
        val rnd = java.util.Random(20260815L)   // 씨앗 고정 — 실패가 재현된다
        // 토큰 이름을 좁게 잡아야 이동·충돌이 실제로 일어난다(넓으면 전부 서로 무관해진다).
        val tokens = listOf("A", "B", "C", "D")
        var moved = 0
        repeat(4000) {
            val idCount = 1 + rnd.nextInt(6)
            // 초기 DB: 일부는 무소속, 일부는 시트가 모르는 토큰("Z")에 속한다.
            val initial = (1L..idCount).associateWith {
                when (rnd.nextInt(4)) {
                    0 -> null
                    1 -> "Z"
                    else -> tokens[rnd.nextInt(tokens.size)]
                }
            }
            // 시트의 배정 — LinkedHashMap이라 넣은 순서가 처리 순서다(호출부와 같다).
            val desired = linkedMapOf<String, MutableList<Long>>()
            repeat(1 + rnd.nextInt(5)) {
                val token = tokens[rnd.nextInt(tokens.size)]
                val id = 1L + rnd.nextInt(idCount)
                val bucket = desired.getOrPut(token) { mutableListOf() }
                if (id !in bucket) bucket.add(id)   // 한 경로는 루프에서 한 번만 돈다(plan이 접는다)
            }
            val want = oracle(desired, initial)
            val got = viaPlanner(desired, initial)
            assertEquals("초기=$initial 배정=$desired", want, got)
            if (want != initial) moved++
        }
        // 대조가 **아무 일도 일어나지 않는 상태만** 본 것이 아님을 증명한다 — 그렇지 않으면
        // 이 시험은 통과하면서 아무것도 재지 않는다(검사 자기 증명과 같은 부류).
        // **문턱은 실측(2,248)에 맞춰 절반 위로 세운다.** 종전 `> 500`은 4,000판의 12%라
        // *"헛돌지 않는다"*를 사실상 보증하지 못했고, **문서 세 자리는 이미 "2,000 초과"라
        // 적고 있었다** — 병합 뒤 콜드 검토가 그 어긋남을 잡았다. 낮은 쪽을 문서에 맞추는 대신
        // 단언을 올린 것은, 이 줄이 지키려는 것이 *숫자*가 아니라 **대조의 뜻**이기 때문이다.
        assertTrue("상태가 실제로 바뀐 사례가 있어야 대조에 뜻이 있다 (실측 $moved)", moved > 2000)
    }
}
