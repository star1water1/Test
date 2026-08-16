package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.ImageMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `util/ImageAdoptionPlanner.kt` — 입양(insert-or-get)의 일괄 판정 (B-244).
 *
 * **무엇을 재는가: 답이지 속도가 아니다.** 이 판이 없앤 것은 경로마다 도는 `adopt`(왕복 셋)이고,
 * 그 자리를 *읽기 하나 · 삽입 하나 · 승격 하나*가 대신한다. 옮기면서 갈릴 수 있는 것은 셋이고
 * **셋 다 DB를 봐서는 안 보인다**:
 * ⓐ **승격을 빠뜨리면** 그 행은 `adoptSource='auto'`로 남고, 다음 자동 재동기화의 자동 반납이
 *    **사용자가 태그를 붙인 행을 지운다** — 지워진 뒤에는 증거가 없다.
 * ⓑ **중복 경로의 id가 갈리면** 뒤따르는 태그·링크가 엉뚱한 행에 붙는다(행은 `path` 유니크라
 *    DB는 멀쩡해 보인다).
 * ⓒ **경합(`rowId == -1`)을 빠뜨리면** 그 경로만 조용히 id 없이 지나간다.
 *
 * **방어선의 뼈대는 ⑥의 대조 시험이다.** 옛 코드(경로마다 삽입-또는-조회하고 그 자리에서
 * 승격한다)를 [oracle]로 그대로 다시 적어, 무작위로 지은 수천 가지 입력에서 **id 지도와 행의
 * 최종 소유가 글자 그대로 같은지** 본다 — `ImageTagApplyPlannerTest`·`ImageLinkGroupPlannerTest`가
 * 같은 부류에 쓴 그 방법이다.
 */
class ImageAdoptionPlannerTest {

    // ── 가짜 DB — `image_meta`의 성질만 흉내 낸다(path 유니크 · IGNORE 삽입 · 경로별 승격) ──

    private class FakeDb {
        val rows = LinkedHashMap<String, ImageMeta>()
        private var nextId = 1L

        /** `@Insert(onConflict = IGNORE)` — 이미 있으면 `-1`. */
        fun insert(path: String, adoptSource: String?): Long {
            if (path in rows) return ImageAdoptionPlanner.NOT_INSERTED
            val id = nextId++
            rows[path] = ImageMeta(id = id, path = path, importedAt = 1L, adoptSource = adoptSource)
            return id
        }

        fun insertAll(paths: List<String>, adoptSource: String?): List<Long> =
            paths.map { insert(it, adoptSource) }

        fun getByPath(path: String): ImageMeta? = rows[path]

        /** `WHERE path IN (:paths)` — 없는 경로는 그냥 빠진다. */
        fun getByPaths(paths: List<String>): List<ImageMeta> = paths.mapNotNull { rows[it] }

        fun promote(paths: List<String>) {
            for (p in paths) rows[p]?.let { rows[p] = it.copy(adoptSource = null) }
        }

        /** 비교용 — 경로 → 소유. */
        fun ownership(): Map<String, String?> = rows.mapValues { (_, m) -> m.adoptSource }

        fun copy(): FakeDb {
            val other = FakeDb()
            for ((p, m) in rows) other.rows[p] = m
            other.nextId = nextId
            return other
        }
    }

    private val AUTO = ImageMeta.ADOPT_SOURCE_AUTO

    /** 옛 코드 그대로 — `ImageMetaDao.adopt` / `adoptAuto`를 경로마다 부른다. */
    private fun oracle(db: FakeDb, paths: List<String>, auto: Boolean): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (p in paths) {
            val inserted = db.insert(p, if (auto) AUTO else null)
            val id = if (inserted != ImageAdoptionPlanner.NOT_INSERTED) {
                inserted
            } else {
                if (!auto) db.promote(listOf(p))       // `adopt`만 승격한다
                db.getByPath(p)!!.id                   // 옛 `requireNotNull`
            }
            out[p] = id
        }
        return out
    }

    /**
     * 새 코드 그대로 — [ImageAdoption]이 DB에 대고 하는 순서를 가짜 DB로 옮긴 것.
     *
     * @param racers 우리 `getByPaths` **이후에** 다른 연결이 넣어 버리는 경로(경합 흉내).
     */
    private fun batch(
        db: FakeDb,
        paths: List<String>,
        auto: Boolean,
        racers: Set<String> = emptySet()
    ): ImageAdoptionPlanner.Resolved {
        val existing = db.getByPaths(paths.toCollection(LinkedHashSet()).toList())
        val split = ImageAdoptionPlanner.split(paths, existing, promote = !auto)

        for (p in split.fresh) if (p in racers) db.insert(p, AUTO)   // 남이 먼저 넣었다

        val rowIds = if (split.fresh.isEmpty()) emptyList()
        else db.insertAll(split.fresh, if (auto) AUTO else null)

        val raced = ImageAdoptionPlanner.raced(split, rowIds)
        val recovered = if (raced.isEmpty()) emptyList() else db.getByPaths(raced)
        val resolved = ImageAdoptionPlanner.resolve(split, rowIds, recovered)
        if (resolved.promotePaths.isNotEmpty()) db.promote(resolved.promotePaths)
        return resolved
    }

    private fun meta(id: Long, path: String, source: String? = null) =
        ImageMeta(id = id, path = path, importedAt = 1L, adoptSource = source)

    // ── ① 가르기 — 있는 것과 없는 것 ──────────────────────────────────────────

    @Test
    fun `있는 것과 없는 것을 가른다`() {
        val s = ImageAdoptionPlanner.split(
            listOf("a.png", "b.png"), listOf(meta(7, "a.png")), promote = true
        )
        assertEquals(listOf("a.png", "b.png"), s.wanted)
        assertEquals(mapOf("a.png" to 7L), s.existingIdByPath)
        assertEquals(listOf("b.png"), s.fresh)
    }

    @Test
    fun `중복은 첫 자리에서 접히고 순서가 보존된다`() {
        val s = ImageAdoptionPlanner.split(listOf("b.png", "a.png", "b.png"), emptyList(), promote = true)
        assertEquals(listOf("b.png", "a.png"), s.wanted)
        assertEquals(listOf("b.png", "a.png"), s.fresh)
    }

    @Test
    fun `물어보지 않은 행이 섞여 와도 무시한다`() {
        // 청크로 갈라 물으면 호출부가 결과를 이어 붙인다 — 남의 행이 섞여도 답이 같아야 한다.
        val s = ImageAdoptionPlanner.split(
            listOf("a.png"), listOf(meta(7, "a.png"), meta(9, "z.png")), promote = true
        )
        assertEquals(mapOf("a.png" to 7L), s.existingIdByPath)
        assertEquals(emptyList<String>(), s.fresh)
    }

    @Test
    fun `빈 입력은 빈 답이다`() {
        val s = ImageAdoptionPlanner.split(emptyList(), emptyList(), promote = true)
        val r = ImageAdoptionPlanner.resolve(s, emptyList(), emptyList())
        assertEquals(emptyMap<String, Long>(), r.idByPath)
        assertEquals(emptyList<String>(), r.promotePaths)
        assertEquals(emptyList<String>(), r.unresolved)
    }

    // ── ② 승격 — 빠뜨리면 자동 반납이 사용자 행을 지운다 ──────────────────────

    @Test
    fun `이미 있던 행만 승격 대상이다`() {
        val s = ImageAdoptionPlanner.split(
            listOf("old.png", "new.png"), listOf(meta(7, "old.png", AUTO)), promote = true
        )
        val r = ImageAdoptionPlanner.resolve(s, listOf(11L), emptyList())
        assertEquals(listOf("old.png"), r.promotePaths)
        assertEquals(mapOf("old.png" to 7L, "new.png" to 11L), r.idByPath)
    }

    @Test
    fun `자동 입양은 승격하지 않는다`() {
        val s = ImageAdoptionPlanner.split(
            listOf("old.png"), listOf(meta(7, "old.png", AUTO)), promote = false
        )
        val r = ImageAdoptionPlanner.resolve(s, emptyList(), emptyList())
        assertEquals(emptyList<String>(), r.promotePaths)
        assertEquals(mapOf("old.png" to 7L), r.idByPath)
    }

    @Test
    fun `승격 순서는 입력 순서다`() {
        // 청크로 갈라 보내도 보내는 순서가 같아야 한다.
        val s = ImageAdoptionPlanner.split(
            listOf("c.png", "a.png", "b.png"),
            listOf(meta(1, "a.png"), meta(2, "b.png"), meta(3, "c.png")),
            promote = true
        )
        val r = ImageAdoptionPlanner.resolve(s, emptyList(), emptyList())
        assertEquals(listOf("c.png", "a.png", "b.png"), r.promotePaths)
    }

    // ── ③ 경합 — `rowId == -1` ────────────────────────────────────────────────

    @Test
    fun `경합한 경로는 다시 물어 채우고 승격 대상이 된다`() {
        val s = ImageAdoptionPlanner.split(listOf("a.png", "b.png"), emptyList(), promote = true)
        // a는 그 사이 남이 넣었다(-1), b는 우리가 넣었다.
        val raced = ImageAdoptionPlanner.raced(s, listOf(ImageAdoptionPlanner.NOT_INSERTED, 12L))
        assertEquals(listOf("a.png"), raced)

        val r = ImageAdoptionPlanner.resolve(
            s, listOf(ImageAdoptionPlanner.NOT_INSERTED, 12L), listOf(meta(99, "a.png", AUTO))
        )
        assertEquals(mapOf("a.png" to 99L, "b.png" to 12L), r.idByPath)
        // **이미 있던 행이므로 승격한다** — 옛 코드에서 `insert` 실패가 곧 승격이던 그 자리다.
        assertEquals(listOf("a.png"), r.promotePaths)
        assertEquals(emptyList<String>(), r.unresolved)
    }

    @Test
    fun `다시 물어도 없으면 미해결로 남긴다`() {
        val s = ImageAdoptionPlanner.split(listOf("a.png"), emptyList(), promote = true)
        val r = ImageAdoptionPlanner.resolve(s, listOf(ImageAdoptionPlanner.NOT_INSERTED), emptyList())
        assertEquals(emptyMap<String, Long>(), r.idByPath)
        assertEquals(listOf("a.png"), r.unresolved)   // 조용히 빠지지 않는다 — 호출부가 던진다
    }

    @Test
    fun `삽입 결과가 모자라면 남은 자리를 경합으로 본다`() {
        val s = ImageAdoptionPlanner.split(listOf("a.png", "b.png"), emptyList(), promote = true)
        assertEquals(listOf("b.png"), ImageAdoptionPlanner.raced(s, listOf(5L)))
    }

    // ── ④ 미해결은 승격 대상이 아니다 ────────────────────────────────────────

    @Test
    fun `미해결 경로는 승격 목록에 들지 않는다`() {
        val s = ImageAdoptionPlanner.split(listOf("a.png"), emptyList(), promote = true)
        val r = ImageAdoptionPlanner.resolve(s, listOf(ImageAdoptionPlanner.NOT_INSERTED), emptyList())
        assertEquals(emptyList<String>(), r.promotePaths)
    }

    // ── ⑤ 대조: 옛 경로별 루프를 정답지로 다시 적어 손으로 맞춘다 ─────────────

    @Test
    fun `기존 자동 행을 입양하면 사용자 소유가 된다`() {
        val db = FakeDb()
        db.insert("a.png", AUTO)
        val old = db.copy()

        val mine = batch(db, listOf("a.png"), auto = false)
        val theirs = oracle(old, listOf("a.png"), auto = false)

        assertEquals(theirs, mine.idByPath)
        assertEquals(old.ownership(), db.ownership())
        assertEquals(mapOf("a.png" to null), db.ownership())   // 자동 반납에서 살아남는다
    }

    // ── ⑥ 무작위 대조 — 옛 루프와 답도 최종 상태도 같아야 한다 ────────────────

    @Test
    fun `무작위 대조 — 계획이 옛 경로별 루프와 같은 답을 낸다`() {
        val rnd = Random(20260816)
        var withExisting = 0
        var withDup = 0
        var withAutoRow = 0

        repeat(4000) {
            val pool = (1..6).map { "p$it.png" }
            // 시작 상태 — 일부 행이 이미 있고, 그중 일부는 자동 입양분이다.
            val seed = FakeDb()
            for (p in pool) if (rnd.nextInt(3) == 0) {
                val auto = rnd.nextBoolean()
                seed.insert(p, if (auto) AUTO else null)
                if (auto) withAutoRow++
            }
            if (seed.rows.isNotEmpty()) withExisting++

            val n = rnd.nextInt(1, 8)
            val paths = (1..n).map { pool[rnd.nextInt(pool.size)] }
            if (paths.size != paths.distinct().size) withDup++
            val auto = rnd.nextBoolean()

            val mineDb = seed.copy()
            val theirsDb = seed.copy()
            val mine = batch(mineDb, paths, auto)
            val theirs = oracle(theirsDb, paths, auto)

            // id 지도가 같다 — 순서·중복 접기까지.
            assertEquals(theirs, mine.idByPath)
            assertEquals(theirs.keys.toList(), mine.idByPath.keys.toList())
            // **행의 최종 소유가 같다** — 승격을 빠뜨리면 여기서 갈린다(⑤의 조용한 유실).
            assertEquals(theirsDb.ownership(), mineDb.ownership())
            // 새로 생긴 행의 집합도 같다.
            assertEquals(theirsDb.rows.keys, mineDb.rows.keys)
            assertTrue(mine.unresolved.isEmpty())
        }

        // **대조가 헛돌지 않는 것을 시험이 스스로 단언한다** — 세 갈래가 실제로 쓰였는가.
        assertTrue("기존 행이 있는 판이 너무 적다: $withExisting", withExisting > 2000)
        assertTrue("중복 경로가 있는 판이 너무 적다: $withDup", withDup > 500)
        assertTrue("자동 입양 행이 있는 판이 너무 적다: $withAutoRow", withAutoRow > 500)
    }

    @Test
    fun `무작위 대조 — 경합이 끼어도 id와 소유가 옛 루프와 같다`() {
        val rnd = Random(20260817)
        var raced = 0

        repeat(2000) {
            val pool = (1..5).map { "q$it.png" }
            val seed = FakeDb()
            for (p in pool) if (rnd.nextInt(4) == 0) seed.insert(p, AUTO)

            val n = rnd.nextInt(1, 6)
            val paths = (1..n).map { pool[rnd.nextInt(pool.size)] }
            val racers = pool.filter { rnd.nextInt(4) == 0 }.toSet()
            val auto = rnd.nextBoolean()

            val mineDb = seed.copy()
            val mine = batch(mineDb, paths, auto, racers)
            if (paths.any { it in racers && it !in seed.rows }) raced++

            // 경합분은 남이 만든 행이라 **id 값 자체는 옛 루프와 다를 수 있다.**
            // 잠그는 것은 두 가지다: 전원이 id를 얻는가, 그리고 **소유가 옳은가.**
            assertTrue(mine.unresolved.isEmpty())
            assertEquals(paths.distinct(), mine.idByPath.keys.toList())
            for ((path, id) in mine.idByPath) assertEquals(id, mineDb.rows.getValue(path).id)

            if (!auto) {
                // `adopt`는 **건드린 경로를 전부 사용자 소유로** 남긴다 — 경합으로 남이 자동
                // 입양해 둔 행까지. 빠뜨리면 그 행이 자동 반납에 지워진다.
                for (path in paths.distinct()) {
                    assertEquals(
                        "경합분의 승격이 빠졌다: $path",
                        null, mineDb.rows.getValue(path).adoptSource
                    )
                }
            }
        }
        assertTrue("경합이 실제로 일어난 판이 너무 적다: $raced", raced > 300)
    }
}
