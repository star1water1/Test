package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `util/ImageTagApplyPlanner.kt` — AI 태그 검토 시트의 적용 계획 (B-241).
 *
 * **무엇을 재는가: 답이지 속도가 아니다.** 이 판이 없앤 것은 경로마다 도는
 * `getTagsByImageList`이고, 그 자리를 **일괄 조회 한 번 + 쓰기를 흉내 내는 메모리 겹**이
 * 대신한다. 겹이 틀리면 같은 태그가 두 번 `fresh`로 세어져 **화면이 큰 수를 말한다** —
 * 행은 `(imageId, tag)` 유니크라 늘지 않으므로 **DB를 봐도 안 보이고 오류도 나지 않는다.**
 * 조용히 틀린 고지라 시험이 아니면 잡히지 않는다(개발 의도 2번).
 *
 * **방어선의 뼈대는 ⑤의 대조 시험이다.** 옛 코드(경로마다 DB를 묻고 즉시 쓴다)를 [oracle]로
 * 그대로 다시 적어, 무작위로 지은 수천 가지 입력에서 **계수와 쓸 것이 글자 그대로 같은지**
 * 본다 — `ImageLinkGroupPlannerTest`가 같은 부류에 쓴 그 방법이다.
 */
class ImageTagApplyPlannerTest {

    private fun plan(
        work: List<Pair<String, List<String>>>,
        idByPath: Map<String, Long>,
        existing: Map<Long, Set<String>> = emptyMap()
    ) = ImageTagApplyPlanner.plan(work, idByPath, existing)

    // ── ① 기본: 기존 태그와 합치고, 이미 있는 것은 세지 않는다 ────────────────

    @Test
    fun `기존에 없는 태그만 fresh다`() {
        val p = plan(listOf("a.png" to listOf("바다", "노을")), mapOf("a.png" to 1L), mapOf(1L to setOf("바다")))
        assertEquals(listOf("노을"), p.inserts.single().freshTags)
        assertEquals(1, p.tagCount)
        assertEquals(1, p.touchedPaths)
    }

    /**
     * **한 항목 안의 겹**(B-246) — 행은 `(imageId, tag)` 유니크라 실제로 늘어나는 행은 하나인데
     * 종전에는 `tagCount`가 2 올라 화면이 큰 수를 말했다. B-241이 *항목 사이*에서 없앤 거짓 고지가
     * *항목 안*에 남아 있던 자리다.
     *
     * `freshTags`도 함께 잠근다 — 겹이 남아 있으면 쓰기가 같은 행을 두 번 밀어 넣고, 그것을
     * DB가 접어 주는 데 기대게 된다(이 계층의 보장이 아니라 운이다).
     */
    @Test
    fun `한 항목 안에 같은 태그가 두 번 있어도 한 번만 센다`() {
        val p = plan(listOf("a.png" to listOf("노을", "노을", "바다")), mapOf("a.png" to 1L))
        assertEquals(listOf("노을", "바다"), p.inserts.single().freshTags)
        assertEquals(2, p.tagCount)
        assertEquals(1, p.touchedPaths)
    }

    @Test
    fun `이미 가진 태그와 항목 안 겹이 함께 있어도 수가 맞는다`() {
        val p = plan(
            listOf("a.png" to listOf("바다", "바다", "노을", "노을")),
            mapOf("a.png" to 1L),
            mapOf(1L to setOf("바다"))
        )
        assertEquals(listOf("노을"), p.inserts.single().freshTags)
        assertEquals(1, p.tagCount)
    }

    @Test
    fun `전부 이미 있으면 쓸 것이 없다`() {
        val p = plan(listOf("a.png" to listOf("바다")), mapOf("a.png" to 1L), mapOf(1L to setOf("바다")))
        assertEquals(emptyList<ImageTagApplyPlanner.Insert>(), p.inserts)
        assertEquals(0, p.tagCount)
        assertEquals(0, p.touchedPaths)
    }

    @Test
    fun `빈 태그 목록은 건너뛴다`() {
        val p = plan(listOf("a.png" to emptyList()), mapOf("a.png" to 1L))
        assertEquals(0, p.tagCount)
        assertEquals(0, p.touchedPaths)
    }

    @Test
    fun `id를 못 찾은 경로는 건너뛴다`() {
        val p = plan(listOf("a.png" to listOf("바다")), emptyMap())
        assertEquals(0, p.tagCount)
        assertEquals(emptyList<ImageTagApplyPlanner.Insert>(), p.inserts)
    }

    // ── ② 겹: 같은 이미지가 두 번 돌면 앞 차례의 쓰기가 보인다 ────────────────
    // **이 판의 핵심이다.** 읽기를 앞으로 모았으므로 겹이 없으면 두 번째 차례가 방금 넣은
    // 태그를 "없다"고 보고 다시 센다. 폴더판은 한 경로가 두 폴더에 실릴 수 있어 실재한다.

    @Test
    fun `같은 경로가 두 번 실리면 두 번째는 앞의 쓰기를 본다`() {
        val p = plan(
            listOf("a.png" to listOf("바다"), "a.png" to listOf("바다", "노을")),
            mapOf("a.png" to 1L)
        )
        assertEquals(listOf(listOf("바다"), listOf("노을")), p.inserts.map { it.freshTags })
        assertEquals(2, p.tagCount)
        // 같은 경로라 '건드린 경로'는 하나다.
        assertEquals(1, p.touchedPaths)
    }

    @Test
    fun `두 번째가 전부 겹치면 쓰지 않는다`() {
        val p = plan(
            listOf("a.png" to listOf("바다"), "a.png" to listOf("바다")),
            mapOf("a.png" to 1L)
        )
        assertEquals(1, p.inserts.size)
        assertEquals(1, p.tagCount)
    }

    // ── ③ 항목 안의 중복도 한 번만 센다 (B-246) ───────────────────────────────
    // **2026.08.16에 뒤집힌 계약이다.** 종전 이 자리는 *"둘 다 센다"*를 일부러 잠그고 있었고
    // 그 판단은 그 판(B-241, 성능)에서는 옳았다 — 읽기를 앞으로 모으는 변경이 계수 성질까지
    // 바꾸면 무엇이 무엇을 바꿨는지 갈리지 않는다. 그 몫을 여기서 닫는다.
    // 잠그는 자리는 위 ①의 두 시험(`한 항목 안에 같은 태그가…`)이고, 여기는 그 경위만 적는다.

    // ── ④ 순서와 계수 ─────────────────────────────────────────────────────────

    @Test
    fun `쓸 것이 있는 항목만 넣은 순서대로 실린다`() {
        val p = plan(
            listOf(
                "a.png" to listOf("바다"),
                "b.png" to listOf("숲"),
                "c.png" to listOf("숲")
            ),
            mapOf("a.png" to 1L, "b.png" to 2L, "c.png" to 3L),
            mapOf(2L to setOf("숲"))
        )
        assertEquals(listOf("a.png", "c.png"), p.inserts.map { ins -> ins.path })
        assertEquals(2, p.tagCount)
        assertEquals(2, p.touchedPaths)
    }

    @Test
    fun `서로 다른 이미지는 서로의 겹에 들지 않는다`() {
        val p = plan(
            listOf("a.png" to listOf("바다"), "b.png" to listOf("바다")),
            mapOf("a.png" to 1L, "b.png" to 2L)
        )
        assertEquals(2, p.tagCount)
        assertEquals(2, p.touchedPaths)
    }

    // ── ⑤ 대조: 옛 경로별 루프를 정답지로 다시 적어 무작위로 맞춘다 ────────────

    /**
     * 옛 코드 그대로 — 경로마다 DB를 묻고(`getTagsByImageList`) 곧바로 쓴다.
     * 돌려주는 것은 (쓸 것들, 태그 수, 건드린 경로 수)로 계획과 같은 모양이다.
     */
    private fun oracle(
        work: List<Pair<String, List<String>>>,
        idByPath: Map<String, Long>,
        existing: Map<Long, Set<String>>
    ): Triple<List<Pair<Long, List<String>>>, Int, Int> {
        // 흉내 내는 DB — `insertAll`은 `(imageId, tag)` 유니크라 집합과 같다.
        val db = HashMap<Long, MutableSet<String>>()
        for ((id, tags) in existing) db[id] = tags.toMutableSet()
        val writes = ArrayList<Pair<Long, List<String>>>()
        val touched = LinkedHashSet<String>()
        var count = 0
        for ((path, tags) in work) {
            if (tags.isEmpty()) continue
            val id = idByPath[path] ?: continue
            val cur = db.getOrPut(id) { mutableSetOf() }
            // 항목 안의 겹도 한 번만 센다 (B-246) — 흉내 내는 DB가 집합이라 **오라클 쪽에서도
            // 겹은 행을 늘리지 않는다.** 이 `distinct()`가 빠지면 오라클이 *DB가 접어 줄 쓰기*를
            // 두 번으로 세어, 실제 행 수와 어긋난 답을 정답이라 우기게 된다.
            val fresh = tags.filterNot { it in cur }.distinct()
            if (fresh.isEmpty()) continue
            writes.add(id to fresh)
            cur.addAll(fresh)
            count += fresh.size
            touched.add(path)
        }
        return Triple(writes, count, touched.size)
    }

    @Test
    fun `무작위 대조 — 계획이 옛 경로별 루프와 같은 답을 낸다`() {
        val rnd = Random(20260816)
        val vocab = listOf("바다", "노을", "숲", "설원", "야경")
        var changed = 0
        repeat(4000) {
            val pathCount = rnd.nextInt(1, 5)
            val paths = (1..pathCount).map { "p$it.png" }
            // 경로 → id. **일부러 겹치게 만든다** — 폴더판에서 같은 경로가 두 번 실리는 그 모양.
            val idByPath = paths.associateWith { (rnd.nextInt(1, 4)).toLong() }
            val existing = (1L..3L).associateWith { id ->
                vocab.filter { rnd.nextInt(4) == 0 }.toSet().takeIf { id % 2L == 0L } ?: emptySet()
            }
            val work = (0 until rnd.nextInt(1, 7)).map {
                paths.random(rnd) to (0 until rnd.nextInt(0, 4)).map { vocab.random(rnd) }
            }

            val (oWrites, oCount, oTouched) = oracle(work, idByPath, existing)
            val p = ImageTagApplyPlanner.plan(work, idByPath, existing)

            assertEquals(oWrites, p.inserts.map { it.imageId to it.freshTags })
            assertEquals(oCount, p.tagCount)
            assertEquals(oTouched, p.touchedPaths)
            if (oCount > 0) changed++
        }
        // **대조가 헛돌지 않는 것을 시험이 스스로 단언한다** — 전부 "쓸 것 없음"이면
        // 두 구현이 나란히 빈 답을 내는 것만 확인한 셈이다.
        assertTrue("실제로 쓴 판이 너무 적다: $changed", changed > 2000)
    }
}
