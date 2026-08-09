package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.CharacterNameAiSuggester.Candidate
import com.novelcharacter.app.ai.CharacterNameAiSuggester.Mark
import com.novelcharacter.app.ai.CharacterNameAiSuggester.Mode
import com.novelcharacter.app.ai.CharacterNameAiSuggester.NameContext
import com.novelcharacter.app.ai.CharacterNameAiSuggester.Pool
import com.novelcharacter.app.ai.CharacterNameAiSuggester.RoundRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 이름 추천의 순수 계층 회귀 테스트 (B-123 · 설계 `feature_roadmap_2026-08.md` 7장).
 *
 * **이 파일의 방어선은 건수가 아니라 그 안의 다섯이다:**
 *
 * ① *"같은 이름인가"는 [com.novelcharacter.app.util.NameBankMatch]가 정한다* — 되풀이 판정도
 *    은행 중복도 그 함수여야 한다. 여기서 `lowercase()`나 정확 일치를 새로 적으면 **담을 때는
 *    새 이름인데 저장 때는 중복인** 상태가 나고, 그 어긋남은 화면 어디에도 안 보인다(설계 8-5 ⓐ).
 * ② *✕는 목록에서 사라지지 않는다* — 회피 목록의 재료이자 담기의 재료다. "거절했으니 지운다"가
 *    자연스러워 보이는 자리라, 지우면 **다음 라운드가 같은 이름을 또 내고**(회피 못 함) 담기
 *    목록에서도 통째로 빠진다(설계 7-2·7-4).
 * ③ *이미 캐릭터가 쓰는 이름은 거르지 않고 표식이다* — **목업 주석이 반대로 적혀 있다**
 *    (*"중복(은행·풀·사용 중) 제외 후 수"*). 설계 정본 7-4가 표식이라 했고, 거르면 사용자가
 *    "왜 안 담겼는지" 모른 채 이름을 잃는다.
 * ④ *회피 목록은 최근 것을 남기고 자른다* — 앞에서 자르면(`take`) **방금 거절한 이름이 회피에서
 *    빠져** 바로 다음 라운드에 되돌아온다. 코드는 멀쩡해 보이고 증상은 "AI가 말을 안 듣는다"다.
 * ⑤ *모드를 바꾸면 풀을 비운다* — 전체 이름과 이명이 한 목록에 섞이면 적용 칸도 담기의 성별도
 *    갈린다. 섞이는 쪽이 "아깝지 않아" 보이는 자리라 시험으로 못 박는다.
 */
class CharacterNameAiSuggesterTest {

    private fun pool(vararg names: String): Pool =
        Pool(mode = Mode.FULL).addRound(names.map { Candidate(it, "") }, "", emptyList())

    // ===== ① 같음의 판정은 NameBankMatch 하나다 =====

    @Test
    fun 되풀이_판정은_앞뒤_공백과_ASCII_대소문자를_접는다() {
        val fresh = pool("Aria", "세레닌").freshOnly(
            listOf(Candidate(" aria ", "표기만 다르다"), Candidate("이스핀", ""))
        )
        assertEquals(1, fresh.repeated)
        assertEquals(listOf("이스핀"), fresh.candidates.map { it.name })
    }

    @Test
    fun 내부_공백은_접지_않는다_다른_이름이다() {
        // NameBankMatch가 그렇게 정했다 — 창작자가 구별해 쓰는 다른 이름이다.
        val fresh = pool("홍 길동").freshOnly(listOf(Candidate("홍길동", "")))
        assertEquals(0, fresh.repeated)
    }

    @Test
    fun 은행_중복은_표기가_달라도_걸러진다() {
        val plan = CharacterNameAiSuggester.depositPlan(
            pool("Aria", "세레닌"), bankNames = listOf(" ARIA "), characterNames = emptyList()
        )
        assertEquals(1, plan.filteredInBank)
        assertEquals(listOf("세레닌"), plan.items.map { it.name })
    }

    @Test
    fun 풀_안_표기_중복도_한_번만_담는다() {
        // 라운드가 갈리면 freshOnly가 막지만, 담기는 **최종 방어선**이다 —
        // 여기까지 온 중복을 그대로 넣으면 창고에 쌍둥이가 생기고 자동 대조가 둘 중 하나를 문다.
        val p = Pool(mode = Mode.FULL)
            .addRound(listOf(Candidate("Aria", "")), "", emptyList())
            .addRound(listOf(Candidate("aria", "")), "", emptyList())
        val plan = CharacterNameAiSuggester.depositPlan(p, emptyList(), emptyList())
        assertEquals(1, plan.filteredDuplicate)
        assertEquals(1, plan.items.size)
    }

    // ===== ② ✕는 남는다 =====

    @Test
    fun 거절한_이름도_담기_목록에_남고_기본만_해제된다() {
        val p = pool("세레닌", "그라브니르").withMark("그라브니르", Mark.REJECT)
        val plan = CharacterNameAiSuggester.depositPlan(p, emptyList(), emptyList())
        assertEquals(2, plan.items.size)
        assertTrue(plan.items.first { it.name == "그라브니르" }.rejected)
        assertEquals(listOf("세레닌"), plan.defaultChecked.map { it.name })
    }

    @Test
    fun 표식은_라운드를_가리지_않고_모인다() {
        val p = Pool(mode = Mode.FULL)
            .addRound(listOf(Candidate("세레닌", "")), "", emptyList())
            .addRound(listOf(Candidate("이스핀", ""), Candidate("류넬", "")), "부드럽게", emptyList())
            .withMark("세레닌", Mark.HEART)
            .withMark("류넬", Mark.HEART)
            .withMark("이스핀", Mark.REJECT)
        assertEquals(listOf("세레닌", "류넬"), p.namesMarked(Mark.HEART))
        assertEquals(listOf("이스핀"), p.namesMarked(Mark.REJECT))
        assertEquals(1, p.countMarked(p.rounds[1], Mark.HEART))
        assertEquals(1, p.countMarked(p.rounds[1], Mark.REJECT))
    }

    @Test
    fun 표식_해제는_지운다_빈_표식을_들고_다니지_않는다() {
        val p = pool("세레닌").withMark("세레닌", Mark.HEART).withMark("세레닌", Mark.NONE)
        assertEquals(Mark.NONE, p.markOf("세레닌"))
        assertTrue(p.marks.isEmpty())
    }

    // ===== ③ 사용 중은 표식이지 거름이 아니다 =====

    @Test
    fun 이미_캐릭터가_쓰는_이름은_담기에서_빠지지_않고_표시된다() {
        val plan = CharacterNameAiSuggester.depositPlan(
            pool("세레닌", "아리네"), bankNames = emptyList(), characterNames = listOf("아리네")
        )
        assertEquals(2, plan.items.size)
        assertEquals(0, plan.filteredInBank)
        assertTrue(plan.items.first { it.name == "아리네" }.usedByCharacter)
        assertFalse(plan.items.first { it.name == "세레닌" }.usedByCharacter)
        // 표식이지 해제가 아니다 — 기본 체크에 그대로 남는다.
        assertEquals(2, plan.defaultChecked.size)
    }

    // ===== ④ 유지·회피 목록의 절단 =====

    @Test
    fun 회피_목록은_최근_것을_남긴다() {
        val avoid = (1..CharacterNameAiSuggester.MAX_AVOID_IN_PROMPT + 5).map { "이름$it" }
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(), RoundRequest(mode = Mode.FULL, avoid = avoid)
        )
        assertTrue("방금 거절한 것이 회피에 남아야 한다", built.text.contains("이름${avoid.size}"))
        assertFalse("가장 오래된 것이 잘린다", built.text.contains("[회피] 이름1,"))
        assertTrue(built.truncationNotes.any { it.contains("회피 목록") && it.contains("5") })
    }

    @Test
    fun 유지_목록도_상한을_넘으면_고지한다() {
        val keep = (1..CharacterNameAiSuggester.MAX_KEEP_IN_PROMPT + 2).map { "유지$it" }
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(), RoundRequest(mode = Mode.FULL, keep = keep)
        )
        assertTrue(built.truncationNotes.any { it.contains("유지 목록") && it.contains("2") })
    }

    @Test
    fun 상한_안이면_고지하지_않는다() {
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(), RoundRequest(mode = Mode.FULL, keep = listOf("세레닌"), avoid = listOf("그라브니르"))
        )
        assertTrue(built.text.contains("[유지] 세레닌"))
        assertTrue(built.text.contains("[회피] 그라브니르"))
        assertTrue(built.truncationNotes.isEmpty())
    }

    // ===== ⑤ 모드 =====

    @Test
    fun 모드를_바꾸면_풀을_비운다() {
        val p = pool("세레닌").withMark("세레닌", Mark.HEART).switchedTo(Mode.ALIAS)
        assertEquals(Mode.ALIAS, p.mode)
        assertTrue(p.isEmpty)
        assertTrue(p.marks.isEmpty())
    }

    @Test
    fun 성_고정_모드는_고정된_성을_싣고_성을_붙이지_말라고_말한다() {
        val text = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(fixedSurname = "프셀류트"), RoundRequest(mode = Mode.GIVEN)
        ).text
        assertTrue(text.contains("프셀류트"))
        assertTrue(text.contains("성을 붙이지 마라"))
    }

    @Test
    fun 이름_고정_모드는_성만_짓게_한다() {
        val text = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(fixedGiven = "아리네"), RoundRequest(mode = Mode.SURNAME)
        ).text
        assertTrue(text.contains("아리네"))
        assertTrue(text.contains("이름을 붙이지 마라"))
    }

    @Test
    fun 이명_모드는_본명이_아니라_칭호를_시킨다() {
        val text = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(), RoundRequest(mode = Mode.ALIAS)
        ).text
        assertTrue(text.contains("이명"))
    }

    @Test
    fun 고정_부분이_필요한_모드는_둘뿐이다() {
        assertTrue(Mode.GIVEN.needsFixedPart)
        assertTrue(Mode.SURNAME.needsFixedPart)
        assertFalse(Mode.FULL.needsFixedPart)
        assertFalse(Mode.ALIAS.needsFixedPart)
        assertEquals(Mode.FULL, Mode.fromKey(null))
        assertEquals(Mode.ALIAS, Mode.fromKey("alias"))
    }

    // ===== 견본 고르기 =====

    @Test
    fun 견본은_앞에서_자르지_않고_고르게_흩어_뽑는다() {
        val names = (1..90).map { "이름$it" }
        val picked = CharacterNameAiSuggester.selectNameSamples(names, 3)
        assertEquals(listOf("이름1", "이름31", "이름61"), picked)
    }

    @Test
    fun 견본은_중복과_빈_값을_걷는다() {
        val picked = CharacterNameAiSuggester.selectNameSamples(
            listOf(" 세레닌 ", "세레닌", "", "   ", "이스핀"), 10
        )
        assertEquals(listOf("세레닌", "이스핀"), picked)
    }

    @Test
    fun 견본이_상한을_넘으면_생략_수를_고지한다() {
        val names = (1..CharacterNameAiSuggester.MAX_NAME_SAMPLES + 7).map { "이름$it" }
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(existingNames = names), RoundRequest(mode = Mode.FULL)
        )
        assertTrue(built.truncationNotes.any { it.contains("이름 견본") && it.contains("7") })
    }

    @Test
    fun 은행_견본은_세계관_견본보다_작은_상한을_쓴다() {
        assertTrue(CharacterNameAiSuggester.MAX_BANK_SAMPLES < CharacterNameAiSuggester.MAX_NAME_SAMPLES)
        val bank = (1..40).map { "은행$it" }
        val text = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(bankNames = bank), RoundRequest(mode = Mode.FULL)
        ).text
        val listed = text.substringAfter("[모아 둔 이름]").substringBefore("[지시]")
        assertEquals(CharacterNameAiSuggester.MAX_BANK_SAMPLES, listed.split(",").count { it.contains("은행") })
    }

    // ===== 응답 파싱 =====

    @Test
    fun 정상_응답을_이름과_근거로_읽는다() {
        val parsed = CharacterNameAiSuggester.parseResponse(
            """{"names":[{"name":"아이렌 벨키아","why":"북부식 · 성씨 결 유지"},{"name":"세인 로베르","why":""}]}""",
            10
        )
        assertEquals(0, parsed.droppedCount)
        assertEquals(listOf("아이렌 벨키아", "세인 로베르"), parsed.candidates.map { it.name })
        assertEquals("북부식 · 성씨 결 유지", parsed.candidates[0].reason)
    }

    @Test
    fun 문자열만_준_응답도_받는다() {
        // {"names":["아리네"]}는 흔한 이탈이고 뜻이 명백하다 — 유료 응답을 형식으로 버리지 않는다.
        val parsed = CharacterNameAiSuggester.parseResponse("""{"names":["아리네","이스핀"]}""", 10)
        assertEquals(listOf("아리네", "이스핀"), parsed.candidates.map { it.name })
    }

    @Test
    fun 요청_개수를_넘으면_버리고_수를_남긴다() {
        val parsed = CharacterNameAiSuggester.parseResponse(
            """{"names":["가","나","다"]}""", 2
        )
        assertEquals(2, parsed.candidates.size)
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun 응답_안_중복과_빈_이름은_개수로_남는다() {
        val parsed = CharacterNameAiSuggester.parseResponse(
            """{"names":["아리네"," 아리네 ","","   "]}""", 10
        )
        assertEquals(1, parsed.candidates.size)
        assertEquals(3, parsed.droppedCount)
    }

    @Test
    fun 문장으로_답한_것은_이름이_아니라_버린다() {
        val long = "이 캐릭터에게는 " + "아".repeat(CharacterNameAiSuggester.MAX_NAME_CHARS) + " 이 어울립니다"
        val parsed = CharacterNameAiSuggester.parseResponse("""{"names":["$long","아리네"]}""", 10)
        assertEquals(listOf("아리네"), parsed.candidates.map { it.name })
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun 감싼_따옴표와_번호_접두는_거부가_아니라_정리한다() {
        assertEquals("아리네", CharacterNameAiSuggester.cleanName("\"아리네\""))
        assertEquals("아리네", CharacterNameAiSuggester.cleanName("1. 아리네"))
        assertEquals("아리네", CharacterNameAiSuggester.cleanName("“아리네”"))
        // 이름 안의 문장부호는 건드리지 않는다.
        assertEquals("아리네-2세", CharacterNameAiSuggester.cleanName(" 아리네-2세 "))
    }

    @Test
    fun 근거가_길면_잘라서_행에_들어가게_한다() {
        val why = "가".repeat(CharacterNameAiSuggester.MAX_REASON_CHARS + 20)
        val parsed = CharacterNameAiSuggester.parseResponse(
            """{"names":[{"name":"아리네","why":"$why"}]}""", 10
        )
        assertEquals(CharacterNameAiSuggester.MAX_REASON_CHARS + 1, parsed.candidates[0].reason.length)
    }

    @Test
    fun 스키마_밖_응답은_빈_결과다() {
        assertEquals(0, CharacterNameAiSuggester.parseResponse("이름을 지어 봤습니다", 10).candidates.size)
        assertEquals(0, CharacterNameAiSuggester.parseResponse("""{"items":["가"]}""", 10).candidates.size)
    }

    // ===== 다발 크기 =====

    @Test
    fun 다발_크기는_1에서_30_사이로_잠긴다() {
        assertEquals(1, AiPromptPolicy.clampNameSuggestBatch(0))
        assertEquals(1, AiPromptPolicy.clampNameSuggestBatch(-5))
        assertEquals(30, AiPromptPolicy.clampNameSuggestBatch(100))
        assertEquals(10, AiPromptPolicy.clampNameSuggestBatch(AiPromptPolicy.NAME_SUGGEST_BATCH_DEFAULT))
    }

    @Test
    fun 요청_개수는_프롬프트에_그대로_적힌다() {
        val text = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(), RoundRequest(mode = Mode.FULL, batchSize = 7)
        ).text
        assertTrue(text.contains("후보 7개"))
    }

    // ===== 컨텍스트 =====

    @Test
    fun 캐릭터_컨텍스트는_결의_재료만_싣는다() {
        val ctx = NameContext(
            character = CharacterFieldAiSuggester.CharacterAiContext(
                name = "무명",
                aliases = listOf("그림자"),
                tags = listOf("암살자"),
                memo = "메모는 이름의 결이 아니다",
                filledFields = listOf("종족" to "엘프", "출신" to "북부"),
                imageTags = emptyList(),
                factions = listOf("검은장미"),
                relationships = listOf("아리네 – 자매")
            ),
            universeName = "아르카디아",
            gender = "여성"
        )
        val text = CharacterNameAiSuggester.buildUserPrompt(ctx, RoundRequest(mode = Mode.FULL)).text
        assertTrue(text.contains("아르카디아"))
        assertTrue(text.contains("암살자"))
        assertTrue(text.contains("검은장미"))
        assertTrue(text.contains("종족: 엘프"))
        assertTrue(text.contains("성별: 여성"))
    }

    @Test
    fun 컨텍스트_필드가_많으면_잘라내고_고지한다() {
        val many = (1..CharacterNameAiSuggester.MAX_CONTEXT_FIELDS + 3).map { "필드$it" to "값$it" }
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(
                character = CharacterFieldAiSuggester.CharacterAiContext(
                    name = "무명", aliases = emptyList(), tags = emptyList(), memo = "",
                    filledFields = many, imageTags = emptyList(), factions = emptyList(),
                    relationships = emptyList()
                )
            ),
            RoundRequest(mode = Mode.FULL)
        )
        assertTrue(built.truncationNotes.any { it.contains("캐릭터 필드") && it.contains("3") })
    }

    @Test
    fun 컨텍스트_조회_실패는_고지로_표면화된다() {
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(
                character = CharacterFieldAiSuggester.CharacterAiContext(
                    name = "무명", aliases = emptyList(), tags = emptyList(), memo = "",
                    filledFields = emptyList(), imageTags = emptyList(), factions = emptyList(),
                    relationships = emptyList(), loadFailures = listOf("소속")
                )
            ),
            RoundRequest(mode = Mode.FULL)
        )
        assertTrue(built.truncationNotes.any { it.contains("소속") })
    }

    @Test
    fun 은행_진입은_캐릭터_없이도_프롬프트가_선다() {
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(universeName = "아르카디아", existingNames = listOf("세레닌")),
            RoundRequest(mode = Mode.FULL)
        )
        assertTrue(built.text.contains("[세계관] 아르카디아"))
        assertFalse(built.text.contains("[캐릭터]"))
        assertTrue(built.truncationNotes.isEmpty())
    }

    @Test
    fun 자유_지시가_길면_자르고_고지한다() {
        val long = "부".repeat(CharacterNameAiSuggester.MAX_INSTRUCTION_CHARS + 10)
        val built = CharacterNameAiSuggester.buildUserPrompt(
            NameContext(), RoundRequest(mode = Mode.FULL, instruction = long)
        )
        assertTrue(built.truncationNotes.any { it.contains("추가 지시") })
    }

    @Test
    fun 시스템_프롬프트는_스키마와_금지_사항을_말한다() {
        val sys = CharacterNameAiSuggester.buildSystemPrompt()
        assertTrue(sys.contains("\"names\""))
        assertTrue(sys.contains("[회피]"))
        assertTrue(sys.contains("[유지]"))
    }

    @Test
    fun 라운드_번호는_1부터_이어진다() {
        val p = Pool(mode = Mode.FULL)
            .addRound(listOf(Candidate("가", "")), "", emptyList())
            .addRound(listOf(Candidate("나", "")), "부드럽게", emptyList())
        assertEquals(listOf(1, 2), p.rounds.map { it.ordinal })
        assertEquals("부드럽게", p.rounds[1].instruction)
        assertEquals(2, p.candidates.size)
    }
}
