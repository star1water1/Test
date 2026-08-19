package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.RankingSource
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.util.StatsSnapshot
import com.novelcharacter.app.util.DuelCounterRelations
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.DuelScoreIndex
import com.novelcharacter.app.util.DuelStandings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 점수가 **통계 순위 화면**에 들어오는 자리의 계약 (B-117).
 *
 * 지키는 것 넷:
 *
 * 1. **선택지는 필드 다음에 대결 축**이다 — 차례가 바뀌면 *"내가 늘 고르던 그 자리"*가 움직인다.
 * 2. **이미지 축은 목록에 없다** — 참가자가 이미지 경로라 캐릭터 순위표를 만들 수 없다.
 *    고를 수 있는데 빈 표가 뜨는 것이 원칙 02가 금지하는 겉핥기다.
 * 3. **점수는 순위표의 그 수이고, 작품 필터가 그것을 바꾸지 않는다** — 필터는
 *    *무엇을 보이는가*이지 *누가 겨뤘는가*가 아니다. 등수만 이 표의 것으로 다시 매긴다.
 * 4. **저장된 선택은 업데이트를 넘어 살아남는다** — B-117 이전 값은 접두사가 없다.
 */
class DuelRankingTest {

    private val provider = StatsDataProvider()

    private val uniA = 1L
    private val uniB = 2L

    private fun character(id: Long, name: String, code: String, novelId: Long?) = Character(
        id = id, name = name, code = code, novelId = novelId
    )

    private fun axis(
        id: Long, name: String, code: String, universeId: Long = uniA,
        target: String = DuelAxis.TARGET_CHARACTER, order: Int = 0
    ) = DuelAxis(
        id = id, universeId = universeId, name = name, targetType = target,
        displayOrder = order, code = code
    )

    private fun field(id: Long, key: String, name: String, universeId: Long = uniA) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = "NUMBER", config = "{}",
        entityType = FieldDefinition.ENTITY_CHARACTER
    )

    private fun snapshot(
        characters: List<Character> = emptyList(),
        axes: List<DuelAxis> = emptyList(),
        fields: List<FieldDefinition> = emptyList(),
        novels: List<Novel> = emptyList()
    ) = StatsSnapshot(
        characters = characters, novels = novels,
        universes = listOf(Universe(id = uniA, name = "아르카나"), Universe(id = uniB, name = "다른곳")),
        events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
        tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = fields, fieldValues = emptyList(), crossRefs = emptyList(),
        duelAxes = axes
    )

    /** 순위표가 서는 것과 **같은 경로**로 점수표를 만든다 — 계약 3의 시험 방법이다. */
    private fun scoresOf(
        axis: DuelAxis, codes: List<String>, matches: List<DuelMatch>
    ): DuelScoreIndex.AxisScores {
        val records = DuelRecords.resolve(codes, matches)
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants, matches = records.matches
        )
        return DuelScoreIndex.of(
            axisId = axis.id, axisCode = axis.code, axisName = axis.name,
            universeId = axis.universeId,
            rows = DuelStandings.rows(twoPass.fit, twoPass.report, records),
            fit = twoPass.fit, missingParticipants = records.missingParticipants,
            scanned = matches.size
        )
    }

    private fun match(a: String, b: String, winner: String?) =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner)

    // ──────────────────────────────────────────────────────────────────────
    // 1·2. 선택지 목록
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `선택지는 필드 다음에 대결 축이다`() {
        val s = snapshot(
            fields = listOf(field(1L, "power", "마력량")),
            axes = listOf(axis(1L, "강함", "DAX-1"))
        )

        val sources = provider.rankingSources(s, uniA)

        assertEquals(2, sources.size)
        assertFalse("필드가 먼저다 — 축은 없을 수도 있다", sources[0].isDuel)
        assertEquals("마력량", sources[0].label)
        assertTrue(sources[1].isDuel)
        assertEquals("강함", sources[1].label)
        assertEquals("대결", sources[1].typeLabel)
        assertEquals("DAX-1", sources[1].duelAxisCode)
    }

    @Test
    fun `이미지 축은 선택지에 없다`() {
        val s = snapshot(
            axes = listOf(
                axis(1L, "강함", "DAX-1"),
                axis(2L, "아름다움", "DAX-2", target = DuelAxis.TARGET_IMAGE)
            )
        )

        val sources = provider.rankingSources(s, uniA)

        assertEquals("참가자가 이미지 경로인 축은 캐릭터를 줄 세울 수 없다", 1, sources.size)
        assertEquals("DAX-1", sources[0].duelAxisCode)
    }

    @Test
    fun `세계관을 고르면 그 세계관의 축만 뜬다`() {
        val s = snapshot(
            axes = listOf(axis(1L, "강함", "DAX-1"), axis(2L, "강함", "DAX-2", universeId = uniB))
        )

        assertEquals(listOf("DAX-1"), provider.rankingSources(s, uniA).map { it.duelAxisCode })
        assertEquals(
            "전체 보기에서는 둘 다 뜬다 — 이름이 같아도 서로 다른 축이다",
            listOf("DAX-1", "DAX-2"), provider.rankingSources(s, null).map { it.duelAxisCode }
        )
    }

    @Test
    fun `작품 스코프는 그 세계관의 축만 남기고 미배정 스코프는 축을 비운다`() {
        val s = snapshot(
            characters = listOf(character(1L, "가", "CHR-1", 10L), character(2L, "나", "CHR-2", null)),
            axes = listOf(axis(1L, "강함", "DAX-1"), axis(2L, "빠름", "DAX-2", universeId = uniB)),
            novels = listOf(Novel(id = 10L, title = "1부", universeId = uniA))
        )

        assertEquals(
            listOf("DAX-1"), provider.filterByNovel(s, 10L).duelAxes.map { it.code }
        )
        assertTrue(
            "'작품 미배정'에는 세계관이 없다 — 축을 남기면 고를 수 있는데 빈 표가 뜬다",
            provider.filterByNovel(s, com.novelcharacter.app.util.UnassignedFilter.NO_NOVEL_ID)
                .duelAxes.isEmpty()
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. 점수는 순위표의 그 수 — 필터가 바꾸지 않는다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `순위 표의 점수가 순위표의 점수와 같다`() {
        val ax = axis(1L, "강함", "DAX-1")
        val chars = listOf(
            character(1L, "가", "CHR-1", 10L),
            character(2L, "나", "CHR-2", 10L),
            character(3L, "다", "CHR-3", 10L)
        )
        val scores = scoresOf(ax, chars.map { it.code }, listOf(
            match("CHR-1", "CHR-2", "CHR-1"), match("CHR-1", "CHR-2", "CHR-1"),
            match("CHR-2", "CHR-3", "CHR-2"), match("CHR-1", "CHR-3", "CHR-1")
        ))
        val s = snapshot(characters = chars, axes = listOf(ax))

        val result = provider.computeDuelRanking(s, scores, ascending = false)

        assertEquals(3, result.entries.size)
        assertEquals("강함", result.fieldName)
        for (entry in result.entries) {
            val code = chars.first { it.id == entry.characterId }.code
            assertEquals(
                "다시 계산하면 순위표와 다른 수가 된다",
                scores.scoreOf(code)!!.toDouble(), entry.value, 0.0001
            )
        }
        assertEquals("센 쪽이 먼저다", "가", result.entries.first().characterName)
        assertEquals(1, result.entries.first().rank)
    }

    @Test
    fun `작품 필터는 보이는 사람만 줄이고 점수는 그대로다`() {
        val ax = axis(1L, "강함", "DAX-1")
        val chars = listOf(
            character(1L, "가", "CHR-1", 10L),
            character(2L, "나", "CHR-2", 11L),   // 다른 작품
            character(3L, "다", "CHR-3", 10L)
        )
        val novels = listOf(
            Novel(id = 10L, title = "1부", universeId = uniA),
            Novel(id = 11L, title = "외전", universeId = uniA)
        )
        // 점수는 **축 전체**의 기록으로 낸다 — 작품으로 자르지 않는다.
        val scores = scoresOf(ax, chars.map { it.code }, listOf(
            match("CHR-1", "CHR-2", "CHR-1"), match("CHR-1", "CHR-2", "CHR-1"),
            match("CHR-2", "CHR-3", "CHR-2"), match("CHR-2", "CHR-3", "CHR-2")
        ))
        val s = snapshot(characters = chars, axes = listOf(ax), novels = novels)

        val all = provider.computeDuelRanking(s, scores)
        val scoped = provider.computeDuelRanking(provider.filterByNovel(s, 10L), scores)

        assertEquals(3, all.entries.size)
        assertEquals("1부의 둘만 보인다", 2, scoped.entries.size)
        // 같은 캐릭터의 점수가 두 표에서 같아야 한다 — 이것이 이 판의 핵심 계약이다.
        for (entry in scoped.entries) {
            val same = all.entries.first { it.characterId == entry.characterId }
            assertEquals(
                "필터를 걸 때마다 점수가 흔들리면 순위표와 갈린다",
                same.value, entry.value, 0.0001
            )
        }
        // 등수는 이 표의 것으로 다시 매긴다 — 그러지 않으면 "2위가 없다"가 된다.
        assertEquals(listOf(1, 2), scoped.entries.map { it.rank })
    }

    @Test
    fun `한 판도 안 치른 캐릭터는 표에 없고 제외로 세어진다`() {
        val ax = axis(1L, "강함", "DAX-1")
        val chars = listOf(
            character(1L, "가", "CHR-1", 10L),
            character(2L, "나", "CHR-2", 10L),
            character(3L, "쉬는사람", "CHR-3", 10L)
        )
        val scores = scoresOf(ax, chars.map { it.code }, List(3) { match("CHR-1", "CHR-2", "CHR-1") })
        val s = snapshot(characters = chars, axes = listOf(ax))

        val result = provider.computeDuelRanking(s, scores)

        assertEquals(2, result.entries.size)
        assertEquals(2, result.totalCharacters)
        assertEquals("조용히 빠지지 않는다 — 화면이 '제외 N명'으로 말한다", 1, result.excludedCount)
    }

    @Test
    fun `점수 분포가 순위 결과에 함께 실리고 인원 합이 참여 인원과 같다`() {
        // 줄 세우기는 *"누가 위인가"*를 답하지만 **군상의 모양**은 답하지 않는다(원칙 02).
        val ax = axis(1L, "강함", "DAX-1")
        val chars = (1..4).map { character(it.toLong(), "c$it", "CHR-$it", null) }
        val scores = scoresOf(ax, chars.map { it.code }, listOf(
            match("CHR-1", "CHR-2", "CHR-1"), match("CHR-1", "CHR-3", "CHR-1"),
            match("CHR-1", "CHR-4", "CHR-1"), match("CHR-2", "CHR-3", "CHR-2"),
            match("CHR-2", "CHR-4", "CHR-2"), match("CHR-3", "CHR-4", "CHR-3")
        ))
        val s = snapshot(characters = chars, axes = listOf(ax))

        val result = provider.computeDuelRanking(s, scores)

        assertTrue(result.scoreDistribution.isNotEmpty())
        assertEquals(
            "구간이 최댓값을 놓치면 합이 모집단보다 작아진다",
            result.entries.size, result.scoreDistribution.sumOf { it.second }
        )
    }

    @Test
    fun `분포는 작품 필터에 잘리지 않는다 — 축 전체의 모양이다`() {
        val ax = axis(1L, "강함", "DAX-1")
        val chars = listOf(
            character(1L, "가", "CHR-1", 10L), character(2L, "나", "CHR-2", 11L),
            character(3L, "다", "CHR-3", 10L), character(4L, "라", "CHR-4", 11L)
        )
        val novels = listOf(
            Novel(id = 10L, title = "1부", universeId = uniA),
            Novel(id = 11L, title = "외전", universeId = uniA)
        )
        val scores = scoresOf(ax, chars.map { it.code }, listOf(
            match("CHR-1", "CHR-2", "CHR-1"), match("CHR-1", "CHR-3", "CHR-1"),
            match("CHR-1", "CHR-4", "CHR-1"), match("CHR-2", "CHR-3", "CHR-2"),
            match("CHR-2", "CHR-4", "CHR-2"), match("CHR-3", "CHR-4", "CHR-3")
        ))
        val s = snapshot(characters = chars, axes = listOf(ax), novels = novels)

        val all = provider.computeDuelRanking(s, scores)
        val scoped = provider.computeDuelRanking(provider.filterByNovel(s, 10L), scores)

        assertEquals(2, scoped.entries.size)
        assertEquals(
            "작품으로 자르면 '이 세계관의 강함이 어떤 모양인가'의 답이 아니게 된다",
            all.scoreDistribution, scoped.scoreDistribution
        )
    }

    @Test
    fun `필드 순위에는 분포가 실리지 않는다`() {
        // 이 줄은 대결 축의 몫이다 — 필드 순위에 빈 목록이 오는 것이 계약이고,
        // 화면은 그때 줄을 통째로 감춘다("0명"처럼 읽히지 않게).
        val s = snapshot(fields = listOf(field(1L, "power", "마력량")))

        val result = provider.computeRanking(s, listOf(1L))

        assertTrue(result.scoreDistribution.isEmpty())
    }

    @Test
    fun `표시 값이 순위표처럼 오차를 함께 말한다`() {
        val ax = axis(1L, "강함", "DAX-1")
        val chars = listOf(character(1L, "가", "CHR-1", null), character(2L, "나", "CHR-2", null))
        val scores = scoresOf(ax, chars.map { it.code }, List(3) { match("CHR-1", "CHR-2", "CHR-1") })
        val s = snapshot(characters = chars, axes = listOf(ax))

        val result = provider.computeDuelRanking(s, scores)

        // 점수만 적으면 **세 판 친 1520과 백 판 친 1520이 같아 보인다** — 순위표가
        // `1523 ±37`로 말하는 것과 같은 모양이어야 두 화면이 같은 말을 한다.
        val top = result.entries.first()
        val entry = scores.entryOf("CHR-1")!!
        assertEquals("${entry.score} ±${entry.scoreError}", top.displayValue)
    }

    @Test
    fun `오름차순은 약한 쪽부터다`() {
        val ax = axis(1L, "강함", "DAX-1")
        val chars = listOf(character(1L, "가", "CHR-1", null), character(2L, "나", "CHR-2", null))
        val scores = scoresOf(ax, chars.map { it.code }, List(4) { match("CHR-1", "CHR-2", "CHR-1") })
        val s = snapshot(characters = chars, axes = listOf(ax))

        val asc = provider.computeDuelRanking(s, scores, ascending = true)

        assertEquals("나", asc.entries.first().characterName)
        assertEquals(listOf(1, 2), asc.entries.map { it.rank })
    }

    @Test
    fun `판이 하나도 없는 축을 고르면 빈 표이고 전원이 제외로 세어진다`() {
        val ax = axis(1L, "새 축", "DAX-1")
        val chars = listOf(character(1L, "가", "CHR-1", null), character(2L, "나", "CHR-2", null))
        val scores = scoresOf(ax, chars.map { it.code }, emptyList())
        val s = snapshot(characters = chars, axes = listOf(ax))

        val result = provider.computeDuelRanking(s, scores)

        assertTrue(result.entries.isEmpty())
        assertEquals(2, result.excludedCount)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. 저장된 선택
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `저장 열쇠가 필드와 축을 가른다`() {
        val s = snapshot(
            fields = listOf(field(1L, "겹치는키", "마력량")),
            axes = listOf(axis(1L, "강함", "겹치는키"))
        )
        val sources = provider.rankingSources(s, uniA)

        val fieldSource = sources.first { !it.isDuel }
        val duelSource = sources.first { it.isDuel }

        assertEquals("field:겹치는키", fieldSource.storageKey)
        assertEquals("duel:겹치는키", duelSource.storageKey)
        assertFalse("접두사가 없으면 우연히 같은 이름에서 엉뚱한 것이 골라진다",
            duelSource.matches(fieldSource.storageKey))
        assertFalse(fieldSource.matches(duelSource.storageKey))
    }

    @Test
    fun `B-117 이전에 저장된 선택은 접두사가 없어도 살아남는다`() {
        val s = snapshot(fields = listOf(field(1L, "power", "마력량")))
        val fieldSource = provider.rankingSources(s, uniA).first()

        assertTrue(
            "받지 않으면 업데이트 한 번에 모든 사용자의 순위 선택이 조용히 풀린다",
            fieldSource.matches("power")
        )
        assertTrue(fieldSource.matches("field:power"))
        assertFalse(fieldSource.matches("other"))
    }

    @Test
    fun `옛 접두사 없는 값이 축을 가리키지는 않는다`() {
        val s = snapshot(axes = listOf(axis(1L, "강함", "DAX-1")))
        val duelSource = provider.rankingSources(s, uniA).first()

        // B-117 이전에는 축을 고를 수 없었으므로 접두사 없는 값은 언제나 필드의 것이다.
        assertFalse(duelSource.matches("DAX-1"))
        assertTrue(duelSource.matches(RankingSource.DUEL_KEY_PREFIX + "DAX-1"))
    }
}
