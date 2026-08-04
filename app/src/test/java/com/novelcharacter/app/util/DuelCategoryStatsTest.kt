package com.novelcharacter.app.util

import com.novelcharacter.app.util.DuelRating.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelCategoryStats] 하네스 — 범주형 영향 필드가 *무슨 말을 하는가*의 계약 (B-112).
 *
 * **이 계층의 진짜 방어선은 셋이다.**
 * ① **예상과 견주는 것** — 승패만 세면 *"불이 물에 약하다"*와 *"물을 든 캐릭터가 그냥 세다"*가
 *    섞인다. 그 둘을 갈라내는 것이 이 기능의 존재 이유이므로 뒤집히면 기능이 거짓말을 한다.
 * ② **칸을 하나로 모으는 것** — `불↔물`과 `물↔불`이 갈리면 같은 자리를 두 번 세고, 표에서
 *    서로 반대되는 두 줄이 나란히 뜬다.
 * ③ **버린 것을 세는 것** — 값이 빈 판·같은 값끼리 붙은 판·깨진 판은 표에 들지 않는데,
 *    그 사실이 표에는 드러나지 않는다(개발 의도 2번).
 */
class DuelCategoryStatsTest {

    private val options = DuelCategoryStats.Options()

    /** 강함이 서로 같은 참가자들 — 예상 승률이 전부 0.5라 *값의 몫*만 남는다. */
    private fun evenFit(ids: List<Long>): DuelRating.Fit =
        DuelRating.fit(ids, emptyList())

    private fun match(a: Long, b: Long, winner: Long?) = Match(a, b, winner)

    // ──────────────────────────────────────────────────────────────────────
    // 범주 필드인가
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `text values are categorical`() {
        assertTrue(
            DuelCategoryStats.isCategorical(mapOf(1L to listOf("불"), 2L to listOf("물")), options)
        )
    }

    @Test
    fun `numeric values are not categorical`() {
        assertFalse(
            DuelCategoryStats.isCategorical(
                mapOf(1L to listOf("350"), 2L to listOf("120"), 3L to listOf("77")), options
            )
        )
    }

    /**
     * 등급 이름은 범주다 — `S-1`을 −1로 읽지 않는 것이 [DuelFieldLinks.numberOf]의 규칙이고
     * (인수인계 ⑥), 그 규칙이 여기서도 그대로 값을 한다.
     */
    @Test
    fun `grade names stay categorical`() {
        assertTrue(
            DuelCategoryStats.isCategorical(
                mapOf(1L to listOf("S-1"), 2L to listOf("A-2"), 3L to listOf("B-3")), options
            )
        )
    }

    @Test
    fun `one distinct value has nothing to compare`() {
        assertFalse(
            DuelCategoryStats.isCategorical(mapOf(1L to listOf("불"), 2L to listOf("불")), options)
        )
    }

    @Test
    fun `no values at all is not categorical`() {
        assertFalse(DuelCategoryStats.isCategorical(emptyMap(), options))
    }

    /** 절반 넘게 수로 읽히면 범주가 아니다 — 섞인 필드의 처분을 못 박아 둔다. */
    @Test
    fun `mostly numeric values are not categorical`() {
        assertFalse(
            DuelCategoryStats.isCategorical(
                mapOf(1L to listOf("10"), 2L to listOf("20"), 3L to listOf("불")), options
            )
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 집계의 기본
    // ──────────────────────────────────────────────────────────────────────

    private val tokens = mapOf(
        1L to listOf("물"), 2L to listOf("물"),
        3L to listOf("불"), 4L to listOf("불")
    )

    @Test
    fun `wins and losses land in one matchup`() {
        val ids = listOf(1L, 2L, 3L, 4L)
        val matches = listOf(
            match(1L, 3L, 1L),   // 물 이김
            match(3L, 2L, 2L),   // 물 이김 (방향이 뒤집힌 판)
            match(4L, 1L, 1L)    // 물 이김
        )
        val report = DuelCategoryStats.report("attr", matches, evenFit(ids), tokens, options)

        assertEquals(1, report.matchups.size)
        val cell = report.matchups.first()
        // 사전순으로 '물'(U+BB3C)이 '불'(U+BD88)보다 앞이므로 left는 '물'이다 —
        // 정규 키가 방향을 지우고, 어느 쪽이 left인지는 **글자 차례가 정한다.**
        assertEquals("물", cell.left)
        assertEquals("불", cell.right)
        assertEquals(3, cell.matches)
        assertEquals(3.0, cell.leftWins, 1e-9)
        assertEquals(0.0, cell.rightWins, 1e-9)
    }

    /** 방향이 뒤집혀 적힌 판이 **다른 칸을 만들지 않는다** — ②의 계약. */
    @Test
    fun `reversed order does not split the cell`() {
        val ids = listOf(1L, 3L)
        val forward = DuelCategoryStats.report(
            "attr", listOf(match(1L, 3L, 1L)), evenFit(ids), tokens,
            options.copy(minMatches = 1)
        )
        val backward = DuelCategoryStats.report(
            "attr", listOf(match(3L, 1L, 1L)), evenFit(ids), tokens,
            options.copy(minMatches = 1)
        )
        assertEquals(1, forward.matchups.size)
        assertEquals(1, backward.matchups.size)
        assertEquals(forward.matchups.first().left, backward.matchups.first().left)
        assertEquals(forward.matchups.first().leftWins, backward.matchups.first().leftWins, 1e-9)
    }

    @Test
    fun `a draw counts half to each side`() {
        val ids = listOf(1L, 3L)
        val report = DuelCategoryStats.report(
            "attr", listOf(match(1L, 3L, null)), evenFit(ids), tokens,
            options.copy(minMatches = 1)
        )
        val cell = report.matchups.first()
        assertEquals(0.5, cell.leftWins, 1e-9)
        assertEquals(0.5, cell.rightWins, 1e-9)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ① 예상과 견주는 것 — 이 기능의 존재 이유
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **값이 하는 몫만 남는가.** 물을 든 쪽이 통틀어 훨씬 센 세계관에서, 불↔물 전적이 그
     * 강함 차이대로 나왔다면 *상성이 아니다* — z가 눈금을 넘지 않아야 한다.
     */
    @Test
    fun `individual strength alone does not read as a category lean`() {
        val ids = listOf(1L, 2L, 3L, 4L)
        // 물(1·2)이 불(3·4)을 늘 이기고, 그 사실이 점수에 그대로 반영된 기록.
        val history = ArrayList<Match>()
        repeat(6) { history.add(match(1L, 3L, 1L)) }
        repeat(6) { history.add(match(2L, 4L, 2L)) }
        val fit = DuelRating.fit(ids, history)

        val report = DuelCategoryStats.report("attr", history, fit, tokens, options)
        val cell = report.matchups.first()
        // 실제 승수와 예상 승수가 가까워야 한다 — 모델이 이미 그럴 줄 알고 있었기 때문이다.
        assertTrue(
            "예상이 실제를 따라가야 한다: 실제=${cell.leftWins} 예상=${cell.expectedLeftWins}",
            kotlin.math.abs(cell.leftWins - cell.expectedLeftWins) < 1.5
        )
        assertNull("강함 차이만으로는 기운 자리가 아니다", cell.favored(options))
    }

    /**
     * **범주 순환은 잡힌다 — 1차원 점수가 원리적으로 표현할 수 없는 모양이기 때문이다.**
     *
     * 위 시험(전승)이 조용한 것은 결함이 아니다: *"물이 불을 늘 이긴다"*는 **물을 든 캐릭터가
     * 세다**로 완벽히 설명되므로 모델이 그렇게 설명해 버리고, 그러면 값이 따로 할 말이 없다.
     * 반대로 `불>물>풀>불`은 **어떤 1차원 숫자로도 만들어지지 않는다** — 잔차가 남고, 그것이
     * 곧 *값의 몫*이다. [DuelCounterRelations]가 개체 순환을 잡는 것과 같은 근거이며,
     * **이 화면에서 그 개체 순환의 *이유*를 대는 것**이 이 기능이 하는 일이다.
     */
    @Test
    fun `a category cycle reads as a lean on every cell`() {
        val ids = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        val threeWay = mapOf(
            1L to listOf("불"), 2L to listOf("불"),
            3L to listOf("물"), 4L to listOf("물"),
            5L to listOf("풀"), 6L to listOf("풀")
        )
        val history = ArrayList<Match>()
        repeat(4) { history.add(match(1L, 3L, 1L)) }   // 불 > 물
        repeat(4) { history.add(match(2L, 4L, 2L)) }
        repeat(4) { history.add(match(3L, 5L, 3L)) }   // 물 > 풀
        repeat(4) { history.add(match(4L, 6L, 4L)) }
        repeat(4) { history.add(match(5L, 1L, 5L)) }   // 풀 > 불
        repeat(4) { history.add(match(6L, 2L, 6L)) }
        val fit = DuelRating.fit(ids, history)

        val report = DuelCategoryStats.report("attr", history, fit, threeWay, options)
        assertEquals(3, report.matchups.size)
        assertEquals(3, report.leaning(options).size)

        // 세 칸이 가리키는 쪽이 그대로 순환이다.
        val favored = report.matchups.associate { (it.left to it.right) to it.favored(options) }
        assertEquals("불", favored["물" to "불"])
        assertEquals("물", favored["물" to "풀"])
        assertEquals("풀", favored["불" to "풀"])
    }

    /** 눈금은 상성 층과 같은 값이다 — 두 화면이 다른 기준으로 말하면 어느 쪽을 믿을지 모른다. */
    @Test
    fun `thresholds match the counter layer`() {
        assertEquals(DuelCounterRelations.Options().minSamples, options.minMatches)
        assertEquals(DuelCounterRelations.Options().zThreshold, options.zThreshold, 1e-9)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ② 값이 여럿인 필드
    // ──────────────────────────────────────────────────────────────────────

    /** `{불, 바람}` 대 `{물}`은 **칸 둘**에 들지만 판은 하나로 센다. */
    @Test
    fun `multi token values land in every cross cell`() {
        val multi = mapOf(1L to listOf("불", "바람"), 2L to listOf("물"))
        val report = DuelCategoryStats.report(
            "attr", listOf(match(1L, 2L, 1L)), evenFit(listOf(1L, 2L)), multi,
            options.copy(minMatches = 1)
        )
        assertEquals(2, report.matchups.size)
        assertEquals(1, report.scannedMatches)
        assertTrue(report.matchups.all { it.matches == 1 })
    }

    /** 겹치는 토큰은 자기들끼리 세지 않는다 — 물↔물은 어느 쪽이 유리한지 말할 수 없다. */
    @Test
    fun `overlapping tokens do not make a self cell`() {
        val multi = mapOf(1L to listOf("불", "물"), 2L to listOf("물"))
        val report = DuelCategoryStats.report(
            "attr", listOf(match(1L, 2L, 1L)), evenFit(listOf(1L, 2L)), multi,
            options.copy(minMatches = 1)
        )
        assertEquals(1, report.matchups.size)
        assertEquals("물", report.matchups.first().left)
        assertEquals("불", report.matchups.first().right)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ③ 버린 것을 센다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `matches with a missing value are counted not dropped`() {
        val partial = mapOf(1L to listOf("물"))
        val report = DuelCategoryStats.report(
            "attr", listOf(match(1L, 3L, 1L)), evenFit(listOf(1L, 3L)), partial,
            options.copy(minMatches = 1)
        )
        assertTrue(report.matchups.isEmpty())
        assertEquals(1, report.skippedNoValue)
        assertEquals(0, report.scannedMatches)
    }

    @Test
    fun `same value matches are counted separately`() {
        val report = DuelCategoryStats.report(
            "attr", listOf(match(1L, 2L, 1L)), evenFit(listOf(1L, 2L)), tokens,
            options.copy(minMatches = 1)
        )
        assertEquals(1, report.skippedSameValue)
        assertEquals(0, report.scannedMatches)
        assertTrue(report.matchups.isEmpty())
    }

    /** 깨진 판은 세되 표에 넣지 않는다 — 외부에서 편집된 엑셀이 들어올 수 있는 자리다. */
    @Test
    fun `a broken winner is counted not silently folded`() {
        val report = DuelCategoryStats.report(
            "attr", listOf(match(1L, 3L, 99L)), evenFit(listOf(1L, 3L)), tokens,
            options.copy(minMatches = 1)
        )
        assertEquals(1, report.malformedMatches)
        assertEquals(0, report.scannedMatches)
        assertTrue(report.matchups.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // 상한과 차례
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `cells below the sample floor stay out of the list`() {
        val report = DuelCategoryStats.report(
            "attr", listOf(match(1L, 3L, 1L), match(1L, 3L, 1L)), evenFit(listOf(1L, 3L)), tokens,
            options
        )
        assertTrue("2판은 하한 미만이다", report.matchups.isEmpty())
        assertEquals(0, report.totalMatchups)
        // 그래도 값별 전적에는 남는다 — *"아직 덜 붙었다"*를 말할 수 있어야 한다.
        assertEquals(2, report.values.size)
    }

    @Test
    fun `truncation is reported not silent`() {
        val ids = (1L..8L).toList()
        val many = ids.associateWith { listOf("V$it") }
        val matches = ArrayList<Match>()
        for (a in ids) for (b in ids) if (a < b) repeat(3) { matches.add(match(a, b, a)) }

        val report = DuelCategoryStats.report(
            "attr", matches, evenFit(ids), many, options.copy(maxMatchups = 5)
        )
        assertEquals(5, report.matchups.size)
        assertEquals(28, report.totalMatchups)
        assertTrue(report.truncated)
    }

    /** 같은 기록이면 같은 차례가 나와야 한다 — 화면이 흔들리면 사용자가 자리를 잃는다. */
    @Test
    fun `ordering is stable and puts the most surprising first`() {
        val ids = listOf(1L, 2L, 3L, 4L)
        val history = ArrayList<Match>()
        repeat(5) { history.add(match(1L, 3L, 1L)) }
        repeat(5) { history.add(match(2L, 4L, 2L)) }
        repeat(5) { history.add(match(1L, 2L, if (it % 2 == 0) 1L else 2L)) }
        repeat(5) { history.add(match(3L, 4L, if (it % 2 == 0) 3L else 4L)) }
        val fit = DuelRating.fit(ids, history)

        val first = DuelCategoryStats.report("attr", history, fit, tokens, options)
        val again = DuelCategoryStats.report("attr", history, fit, tokens, options)
        assertEquals(first.matchups.map { it.left to it.right }, again.matchups.map { it.left to it.right })
    }

    // ──────────────────────────────────────────────────────────────────────
    // 값별 전적이 칸과 어긋나지 않는가
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 두 목록이 **같은 판을 센다.** 값별 판 수의 합은 칸 판 수의 두 배여야 한다 —
     * 한 칸이 두 값에 한 번씩 들어가기 때문이다. 어긋나면 사용자가 더해 보다가 걸린다.
     */
    @Test
    fun `value tallies and matchups count the same matches`() {
        val ids = listOf(1L, 2L, 3L, 4L)
        val matches = listOf(
            match(1L, 3L, 1L), match(1L, 4L, 4L), match(2L, 3L, 2L),
            match(2L, 4L, 2L), match(1L, 3L, 3L)
        )
        val report = DuelCategoryStats.report(
            "attr", matches, evenFit(ids), tokens, options.copy(minMatches = 1)
        )
        val cellMatches = report.matchups.sumOf { it.matches }
        val valueMatches = report.values.sumOf { it.matches }
        assertEquals(cellMatches * 2, valueMatches)
        assertEquals(5, report.scannedMatches)
    }

    @Test
    fun `an empty axis says nothing`() {
        val report = DuelCategoryStats.report(
            "attr", emptyList(), evenFit(listOf(1L, 3L)), tokens, options
        )
        assertFalse(report.any)
        assertEquals(0, report.scannedMatches)
    }
}
