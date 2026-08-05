package com.novelcharacter.app.util

import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.NarrativeFieldAiWriter
import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결이 **AI 프롬프트 안에서** 무엇으로 보이는가의 계약 (B-104 소비처 ⓔ).
 *
 * 이 하네스가 지키는 것은 넷이고, 전부 *"코드를 읽어서는 알 수 없는 것"*이다:
 *
 * 1. **여기서 점수를 내지 않는다** — [DuelScoreIndex.AxisScores]의 수가 글자 그대로 실린다.
 *    프롬프트가 순위표와 다른 수를 말하면 사용자가 화면에서 본 것과 AI가 받은 것이 갈린다.
 * 2. **한 판도 안 치른 축은 싣지 않는다** — 앵커 1500을 실으면 모델이 그것을 *측정된 실력*으로
 *    읽는다. 의도적인 침묵이라 시험이 없으면 다음 사람이 *"빠뜨렸다"*고 보고 넣는다
 *    (B-112·B-117이 남긴 관행 — 코드가 하지 **않는** 일은 읽어서 알 수 없다).
 * 3. **상한에 걸려 뺀 축은 세어서 알린다** — 그리고 버려지는 것은 **판이 가장 적은 축**이다.
 * 4. **두 조립기가 같은 줄을 받는다** — 짧은 값 경로와 서술형 경로가 각자 자르면 같은
 *    캐릭터가 경로에 따라 다른 축을 받는다.
 */
class DuelAiContextTest {

    private fun match(a: String, b: String, winner: String?): DuelMatch =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner)

    /** 순위표가 서는 것과 **같은 경로**로 축 하나를 세운다 — 그것이 계약 1의 시험 방법이다. */
    private fun scoresOf(
        codes: List<String>,
        matches: List<DuelMatch>,
        axisName: String = "강함"
    ): DuelScoreIndex.AxisScores {
        val records = DuelRecords.resolve(codes, matches, emptyList())
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs
        )
        return DuelScoreIndex.of(
            axisId = 1L,
            axisCode = "AX",
            axisName = axisName,
            universeId = 7L,
            rows = DuelStandings.rows(twoPass.fit, twoPass.report, records),
            fit = twoPass.fit,
            missingParticipants = records.missingParticipants,
            scanned = matches.size
        )
    }

    private fun standing(
        name: String,
        played: Int,
        rank: Int = 1,
        score: Int = 1500,
        scoreError: Int = 10,
        scored: Int = 5
    ) = DuelAiContext.Standing(name, score, scoreError, rank, scored, played)

    // ── 계약 1 — 점수를 다시 내지 않는다 ────────────────────────────────────

    @Test
    fun standing_carriesTheStandingsNumbersVerbatim() {
        val scores = scoresOf(
            listOf("A", "B", "C"),
            listOf(match("A", "B", "A"), match("A", "C", "A"), match("B", "C", "B"))
        )
        val entry = scores.entryOf("A")!!
        val standing = DuelAiContext.standingsOf("A", listOf(scores)).single()

        // 점수·오차·판 수가 **표의 것 그대로**여야 한다 — 하나라도 다시 계산하면
        // 프롬프트가 순위표와 다른 말을 한다. (등수만은 예외이고, 그 이유는 아래 시험에 있다.)
        assertEquals(entry.score, standing.score)
        assertEquals(entry.scoreError, standing.scoreError)
        assertEquals(entry.played, standing.played)
        assertEquals("강함", standing.axisName)
    }

    @Test
    fun rank_isRecountedAmongScored_soItNeverExceedsTheDenominator() {
        // D는 한 판도 안 쳤다 → 앵커 1500 그대로이고, 그 1500은 점수 순 정렬에서
        // **표 한가운데**에 앉는다(B보다 아래, C보다 위).
        val scores = scoresOf(
            listOf("A", "B", "C", "D"),
            listOf(match("A", "B", "A"), match("A", "C", "A"), match("B", "C", "B"))
        )
        // 순위표 줄의 등수는 D를 세므로 C가 4위다 — 화면에는 D의 줄도 함께 보이니 어긋남이 아니다.
        assertEquals(4, scores.entryOf("C")!!.rank)
        assertEquals(3, scores.scored)

        // 그러나 프롬프트에서는 D가 빠진다(계약 2). 등수를 그대로 옮기면 "3명 중 4위"가 된다.
        val standings = listOf("A", "B", "C").map { DuelAiContext.standingsOf(it, listOf(scores)).single() }
        assertEquals(listOf(1, 2, 3), standings.map { it.rank })
        standings.forEach { assertTrue(it.rank <= it.scored) }
        assertTrue(standings.last().line().contains("3명 중 3위"))
    }

    @Test
    fun rank_tiesShareARank_andTheNextRankSkips() {
        // B와 C가 서로 이겨 동점이 되는 자리 — 표준 경쟁 순위(1, 2, 2, 4)를 지킨다.
        val scores = scoresOf(
            listOf("A", "B", "C"),
            listOf(match("A", "B", "A"), match("A", "C", "A"), match("B", "C", "B"), match("C", "B", "C"))
        )
        val ranks = listOf("A", "B", "C")
            .associateWith { DuelAiContext.standingsOf(it, listOf(scores)).single().rank }
        assertEquals(1, ranks["A"])
        assertEquals(ranks["B"], ranks["C"])  // 동점이면 같은 등수다
    }

    @Test
    fun standing_scoredIsTheRankDenominator_notParticipantCount() {
        // D는 참가자로 등록만 되고 한 판도 안 쳤다 → 표에 없다.
        val scores = scoresOf(
            listOf("A", "B", "C", "D"),
            listOf(match("A", "B", "A"), match("A", "C", "A"), match("B", "C", "B"))
        )
        val standing = DuelAiContext.standingsOf("A", listOf(scores)).single()

        // 분모는 참가자 4명이 아니라 **점수가 매겨진 3명**이다. 4를 적으면 "4명 중 3위"라
        // 해 놓고 표에는 셋뿐인 어긋남이 된다.
        assertEquals(3, standing.scored)
        assertEquals(3, scores.scored)
        assertTrue(standing.line().contains("3명 중"))
    }

    // ── 계약 2 — 값 없는 축은 싣지 않는다 (의도적인 침묵) ──────────────────

    @Test
    fun unplayedAxis_isOmittedEntirely_notCarriedAsAnchor() {
        val scores = scoresOf(
            listOf("A", "B", "D"),
            listOf(match("A", "B", "A"))
        )
        // D는 참가자이고 DuelRating은 D에게도 앵커 1500을 준다. 그러나 표에는 없고,
        // 따라서 프롬프트에도 없어야 한다 — 있으면 모델이 1500을 '평균은 되는 실력'으로 읽는다.
        assertTrue(DuelAiContext.standingsOf("D", listOf(scores)).isEmpty())
        assertTrue(scores.unplayed > 0)
    }

    @Test
    fun participantOfAnotherAxisOnly_yieldsNothing() {
        val strong = scoresOf(listOf("A", "B"), listOf(match("A", "B", "A")), axisName = "강함")
        val beauty = scoresOf(listOf("C", "D"), listOf(match("C", "D", "C")), axisName = "아름다움")
        // A는 '아름다움' 축의 참가자가 아니다 — 그 축은 A에 대해 아무 말도 하지 않는다.
        val standings = DuelAiContext.standingsOf("A", listOf(strong, beauty))
        assertEquals(listOf("강함"), standings.map { it.axisName })
    }

    @Test
    fun noAxes_yieldsEmpty_andPromptSectionIsAbsent() {
        assertTrue(DuelAiContext.standingsOf("A", emptyList()).isEmpty())
        val prompt = CharacterFieldAiSuggester.buildUserPrompt(context(emptyList()), targets()).text
        // 빈 줄을 남기면 모델은 "대결을 했는데 결과가 없다"로 읽는다 — 절을 통째로 감춘다.
        assertFalse(prompt.contains(DuelAiContext.PROMPT_LABEL))
    }

    // ── 정렬 — 근거가 많은 축이 앞이다 ────────────────────────────────────

    @Test
    fun standings_sortByPlayedDescending_thenName() {
        val standings = DuelAiContext.standingsOf(
            "A",
            listOf(
                scoresOf(listOf("A", "B"), List(3) { match("A", "B", "A") }, axisName = "지능"),
                scoresOf(listOf("A", "B"), List(9) { match("A", "B", "A") }, axisName = "강함"),
                scoresOf(listOf("A", "B"), List(3) { match("A", "B", "A") }, axisName = "가창력")
            )
        )
        // 판이 많은 축이 먼저. 동수(3판)는 이름순이라 목록이 매번 같은 차례로 선다 —
        // 축 만든 순서를 그대로 두면 상한에 걸릴 때 '먼저 만든 축'이 이기게 되어,
        // 근거의 양과 무관한 기준이 된다.
        assertEquals(listOf("강함", "가창력", "지능"), standings.map { it.axisName })
    }

    // ── 계약 3 — 상한에 걸린 것은 세어서 알린다 ───────────────────────────

    @Test
    fun promptLines_underLimit_omitsNothing() {
        val lines = DuelAiContext.promptLines(List(3) { standing("축$it", played = 5) })
        assertEquals(3, lines.lines.size)
        assertEquals(0, lines.omitted)
    }

    @Test
    fun promptLines_overLimit_reportsCount_andDropsTheLeastPlayed() {
        // standingsOf가 이미 판 수 내림차순으로 세워 준 목록이 들어온다.
        val standings = (1..DuelAiContext.MAX_AXES + 3).map { standing("축$it", played = 100 - it) }
        val lines = DuelAiContext.promptLines(standings)

        assertEquals(DuelAiContext.MAX_AXES, lines.lines.size)
        assertEquals(3, lines.omitted)
        // 버려지는 것은 **판이 가장 적은 축**이다 (C-18: "누구의 것이 버려지는가"에 답이 있어야 한다).
        assertTrue(lines.lines.first().startsWith("축1:"))
        assertFalse(lines.lines.any { it.startsWith("축${DuelAiContext.MAX_AXES + 3}:") })
        assertTrue(DuelAiContext.omittedNote(3).contains("3건 생략"))
    }

    @Test
    fun overLimit_isSurfacedAsTruncationNote_inBothBuilders() {
        val many = (1..DuelAiContext.MAX_AXES + 2).map { standing("축$it", played = 100 - it) }

        // 짧은 값 경로 — 조용히 자르지 않는다 (R-14).
        val short = CharacterFieldAiSuggester.buildUserPrompt(context(many), targets())
        assertTrue(short.truncationNotes.any { it.contains("대결 축") && it.contains("2건 생략") })

        // 서술형 경로 — 같은 문구로 고지한다.
        val narrative = NarrativeFieldAiWriter.buildUserPrompt(
            context(many),
            NarrativeFieldAiWriter.FieldSpec(key = "bg", name = "배경", groupName = "기본", currentValue = ""),
            NarrativeFieldAiWriter.Mode.DRAFT,
            NarrativeFieldAiWriter.Length.MEDIUM,
            1
        )
        assertTrue(narrative.truncationNotes.any { it.contains("대결 축") && it.contains("2건 생략") })
    }

    // ── 계약 4 — 두 조립기가 같은 줄을 받는다 ─────────────────────────────

    @Test
    fun bothBuilders_carryTheSameLine() {
        val standings = listOf(standing("강함", played = 12, rank = 3, score = 1523, scoreError = 37, scored = 12))
        val expected = "강함: 12명 중 3위 · 1523 ±37 (12판)"
        assertEquals(expected, standings.single().line())

        val short = CharacterFieldAiSuggester.buildUserPrompt(context(standings), targets()).text
        val narrative = NarrativeFieldAiWriter.buildUserPrompt(
            context(standings),
            NarrativeFieldAiWriter.FieldSpec(key = "bg", name = "배경", groupName = "기본", currentValue = ""),
            NarrativeFieldAiWriter.Mode.DRAFT,
            NarrativeFieldAiWriter.Length.MEDIUM,
            1
        ).text

        assertTrue(short.contains(expected))
        assertTrue(narrative.contains(expected))
        // 출처를 밝히는 절 이름이 함께 간다 — 없으면 모델이 이 수를 필드값처럼 읽는다.
        assertTrue(short.contains(DuelAiContext.PROMPT_LABEL))
        assertTrue(narrative.contains(DuelAiContext.PROMPT_LABEL))
    }

    @Test
    fun systemPrompts_bindTheModelToTheUsersOrdering() {
        // 사용자가 직접 고른 결과라는 사실과 "어긋나게 쓰지 마라"가 둘 다 있어야 한다 —
        // 줄만 싣고 지시가 없으면 모델은 그것을 참고 정보로 흘려보낸다.
        val short = CharacterFieldAiSuggester.buildSystemPrompt()
        assertTrue(short.contains("대결 우열"))
        assertTrue(short.contains("어긋나는 값을 내지 마라"))

        val narrative = NarrativeFieldAiWriter.buildSystemPrompt()
        assertTrue(narrative.contains("대결 우열"))
        // 서술형은 등수를 본문에 적으면 안 된다 — 작업 기록이지 작품 설정이 아니다.
        assertTrue(narrative.contains("등수를 글에"))
    }

    // ── 조립 재료 ──────────────────────────────────────────────────────────

    private fun context(standings: List<DuelAiContext.Standing>) =
        CharacterFieldAiSuggester.CharacterAiContext(
            name = "홍길동",
            aliases = emptyList(),
            tags = emptyList(),
            memo = "",
            filledFields = emptyList(),
            imageTags = emptyList(),
            factions = emptyList(),
            relationships = emptyList(),
            duelStandings = standings
        )

    private fun targets() = listOf(
        CharacterFieldAiSuggester.FieldSpec(
            key = "str",
            name = "근력",
            type = com.novelcharacter.app.data.model.FieldType.NUMBER,
            options = emptyList(),
            isBirthDate = false,
            numberRange = null,
            currentValue = ""
        )
    )
}
