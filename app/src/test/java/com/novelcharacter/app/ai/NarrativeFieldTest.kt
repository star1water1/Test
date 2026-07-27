package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.NarrativeMode
import com.novelcharacter.app.data.model.SemanticRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서술형 필드 판정([NarrativeMode])과 긴 글 작성 보조([NarrativeFieldAiWriter])의 회귀 테스트.
 *
 * 판정의 요점: **사용자가 직접 정한다**(자율성 우선). 다만 아무 설정 없이도 동작해야 하므로
 * 기본값 AUTO는 표시 형식('여러 줄')을 근거로 삼는다 — 존재를 알 수 없는 기능이 되면 안 된다(원칙 04).
 * 그리고 타입 계약(SELECT의 옵션, NUMBER의 형식)을 산문이 만족할 수 없는 조합은 애초에 성립하지 않는다.
 */
class NarrativeFieldTest {

    private fun field(
        type: FieldType = FieldType.TEXT,
        config: String = "{}",
        key: String = "desc",
        name: String = "설명"
    ) = FieldDefinition(
        id = 1, universeId = 1, key = key, name = name, type = type.name, config = config
    )

    private fun cfg(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { """"${it.first}":"${it.second}"""" }

    // ===== 판정 =====

    @Test
    fun 기본은_자동이고_한_줄_텍스트는_서술형이_아니다() {
        assertEquals(NarrativeMode.AUTO, NarrativeMode.fromConfig("{}"))
        assertFalse(NarrativeMode.isNarrative(field()))
    }

    @Test
    fun 자동일_때_여러_줄_표시면_서술형이다() {
        val f = field(config = cfg("displayFormat" to DisplayFormat.MULTILINE.key))
        assertTrue("설정 없이도 바로 동작해야 한다", NarrativeMode.isNarrative(f))
    }

    @Test
    fun 사용자가_명시하면_표시_형식을_이긴다() {
        // 한 줄로 보여주지만 초안은 AI로 쓰고 싶은 필드 — 실재하는 조합이다.
        val forced = field(config = cfg("narrativeMode" to "narrative"))
        assertTrue(NarrativeMode.isNarrative(forced))

        // 여러 줄로 보여주지만 짧은 목록인 필드 — 반대 방향도 성립해야 한다.
        val denied = field(
            config = """{"displayFormat":"${DisplayFormat.MULTILINE.key}","narrativeMode":"short"}"""
        )
        assertFalse(NarrativeMode.isNarrative(denied))
    }

    @Test
    fun 타입_계약을_어기는_조합은_성립하지_않는다() {
        // 값이 옵션 중 하나여야 하는 타입에 산문을 넣을 수는 없다 — 설정을 무시하는 게 아니라
        // 편집 화면에서 설정 자체가 보이지 않는다(isEligibleType).
        for (t in listOf(FieldType.SELECT, FieldType.GRADE, FieldType.NUMBER,
                         FieldType.BODY_SIZE, FieldType.CALCULATED, FieldType.MULTI_TEXT)) {
            assertFalse("$t 는 서술형 대상이 아니다", NarrativeMode.isEligibleType(t.name))
            assertFalse(
                "$t 에 narrative를 박아도 서술형이 되면 안 된다",
                NarrativeMode.isNarrative(field(type = t, config = cfg("narrativeMode" to "narrative")))
            )
        }
        assertTrue(NarrativeMode.isEligibleType(FieldType.TEXT.name))
    }

    @Test
    fun 형식이_강제되는_TEXT도_서술형이_아니다() {
        val birth = field(config = """{"semanticRole":"${SemanticRole.BIRTH_DATE.key}","narrativeMode":"narrative"}""")
        assertFalse("생일은 MM-DD 형식이 강제된다", NarrativeMode.isNarrative(birth))

        val structured = field(
            config = """{"structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"A"},{"label":"B"}]},"narrativeMode":"narrative"}"""
        )
        assertFalse("구조화 입력은 파트 위젯이 형식을 강제한다", NarrativeMode.isNarrative(structured))
    }

    @Test
    fun 설정_왕복이_보존된다() {
        var c = NarrativeMode.applyToConfig("{}", NarrativeMode.NARRATIVE)
        assertEquals(NarrativeMode.NARRATIVE, NarrativeMode.fromConfig(c))
        c = NarrativeMode.applyToConfig(c, NarrativeMode.SHORT)
        assertEquals(NarrativeMode.SHORT, NarrativeMode.fromConfig(c))
        // AUTO는 키를 지운다 — 기본값과 '명시적 자동'을 구분할 이유가 없다
        c = NarrativeMode.applyToConfig(c, NarrativeMode.AUTO)
        assertFalse(c.contains("narrativeMode"))
        assertEquals(NarrativeMode.AUTO, NarrativeMode.fromConfig(c))
    }

    @Test
    fun 다른_설정을_보존한다() {
        val c = NarrativeMode.applyToConfig(
            cfg("displayFormat" to "multiline"), NarrativeMode.NARRATIVE
        )
        assertEquals(DisplayFormat.MULTILINE, DisplayFormat.fromConfig(c))
        assertEquals(NarrativeMode.NARRATIVE, NarrativeMode.fromConfig(c))
    }

    @Test
    fun 깨진_config는_기본값으로_떨어진다() {
        assertEquals(NarrativeMode.AUTO, NarrativeMode.fromConfig("not json"))
        assertFalse(NarrativeMode.isNarrative(field(config = "not json")))
    }

    // ===== 모드 가용성 =====

    @Test
    fun 빈_필드에는_초안만_제시한다() {
        assertEquals(
            listOf(NarrativeFieldAiWriter.Mode.DRAFT),
            NarrativeFieldAiWriter.availableModes("")
        )
        assertEquals(listOf(NarrativeFieldAiWriter.Mode.DRAFT), NarrativeFieldAiWriter.availableModes("   "))
    }

    @Test
    fun 원문이_있으면_이어쓰기_다듬기_계열을_제시한다() {
        val modes = NarrativeFieldAiWriter.availableModes("이미 쓴 글")
        assertTrue(NarrativeFieldAiWriter.Mode.CONTINUE in modes)
        assertTrue(NarrativeFieldAiWriter.Mode.POLISH in modes)
        assertTrue(NarrativeFieldAiWriter.Mode.EXPAND in modes)
        assertTrue(NarrativeFieldAiWriter.Mode.SHORTEN in modes)
        assertFalse("원문이 있으면 '새로 쓰기'는 원문을 버리는 일이라 기본 제시하지 않는다",
            NarrativeFieldAiWriter.Mode.DRAFT in modes)
    }

    // ===== 프롬프트 =====

    private fun ctx(
        filled: List<Pair<String, String>> = emptyList(),
        loadFailures: List<String> = emptyList()
    ) = CharacterFieldAiSuggester.CharacterAiContext(
        name = "가나다", aliases = listOf("별명"), tags = listOf("검사"), memo = "메모",
        filledFields = filled, imageTags = emptyList(), factions = listOf("문파"),
        relationships = listOf("나 – 친구"), loadFailures = loadFailures
    )

    private val spec = NarrativeFieldAiWriter.FieldSpec("desc", "설명", "기본", "원래 있던 글")

    @Test
    fun 원문이_필요한_모드만_원문을_싣는다() {
        val draft = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(), spec.copy(currentValue = ""), NarrativeFieldAiWriter.Mode.DRAFT,
            NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertFalse(draft.text.contains("[원문]"))

        val cont = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(), spec, NarrativeFieldAiWriter.Mode.CONTINUE,
            NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertTrue(cont.text.contains("[원문]"))
        assertTrue(cont.text.contains("원래 있던 글"))
    }

    @Test
    fun 대상_필드_자신은_다른_필드_목록에서_빠진다() {
        // 안 빼면 원문이 두 번 실려 토큰을 낭비하고 모델이 헷갈린다.
        val p = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(filled = listOf("설명" to "원래 있던 글", "직업" to "검사")),
            spec, NarrativeFieldAiWriter.Mode.POLISH, NarrativeFieldAiWriter.Length.SHORT, 2
        )
        assertFalse("대상 필드가 [다른 필드]에 중복되면 안 된다", p.text.contains("- 설명:"))
        assertTrue(p.text.contains("- 직업: 검사"))
    }

    @Test
    fun 절단은_반드시_고지한다() {
        val long = "가".repeat(NarrativeFieldAiWriter.MAX_EXISTING_CHARS + 500)
        val p = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(), spec.copy(currentValue = long), NarrativeFieldAiWriter.Mode.POLISH,
            NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertTrue("원문 절단을 조용히 하면 안 된다(R-14)",
            p.truncationNotes.any { it.contains("원문") })

        val v = "나".repeat(NarrativeFieldAiWriter.MAX_CONTEXT_VALUE_CHARS + 100)
        val p2 = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(filled = listOf("배경" to v)), spec, NarrativeFieldAiWriter.Mode.POLISH,
            NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertTrue(p2.truncationNotes.any { it.contains("배경") })
    }

    @Test
    fun 조회_실패도_같은_경로로_고지한다() {
        val p = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(loadFailures = listOf("관계")), spec, NarrativeFieldAiWriter.Mode.DRAFT,
            NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertTrue(p.truncationNotes.any { it.contains("관계") })
    }

    @Test
    fun 서술형_원문_상한은_짧은_값_경로보다_넉넉하다() {
        // 300자로 자르면 성격 묘사를 근거로 쓸 수 없다 — 경로를 나눈 이유 자체다.
        assertTrue(
            NarrativeFieldAiWriter.MAX_EXISTING_CHARS > CharacterFieldAiSuggester.MAX_VALUE_CHARS
        )
    }

    // ===== 응답 파싱 =====

    @Test
    fun 초안_배열을_읽는다() {
        val r = NarrativeFieldAiWriter.parseResponse("""{"drafts":["첫 번째","두 번째"]}""", 2)
        assertEquals(listOf("첫 번째", "두 번째"), r.drafts.map { it.text })
        assertEquals(0, r.droppedCount)
    }

    @Test
    fun 코드펜스와_잡담을_수용한다() {
        val text = "네, 준비했습니다.\n```json\n{\"drafts\":[\"본문\"]}\n```\n도움이 되길."
        assertEquals(listOf("본문"), NarrativeFieldAiWriter.parseResponse(text, 2).drafts.map { it.text })
    }

    @Test
    fun 라벨과_감싼_따옴표를_정리한다() {
        assertEquals("본문", NarrativeFieldAiWriter.cleanDraft("초안1: 본문"))
        assertEquals("본문", NarrativeFieldAiWriter.cleanDraft("\"본문\""))
        assertEquals("그는 \"안녕\"이라 했다", NarrativeFieldAiWriter.cleanDraft("그는 \"안녕\"이라 했다"))
    }

    @Test
    fun 빈_후보와_중복_후보는_버리고_수를_보고한다() {
        val r = NarrativeFieldAiWriter.parseResponse("""{"drafts":["A","","A","B"]}""", 3)
        assertEquals(listOf("A", "B"), r.drafts.map { it.text })
        assertEquals("조용히 버리지 않는다", 2, r.droppedCount)
    }

    @Test
    fun 요청_개수를_넘는_후보는_버린다() {
        val r = NarrativeFieldAiWriter.parseResponse("""{"drafts":["A","B","C"]}""", 2)
        assertEquals(2, r.drafts.size)
        assertEquals(1, r.droppedCount)
    }

    // ===== 문체 참고 (표기 기조의 서술형판) =====

    private fun prose(len: Int, mark: String) = mark + "가".repeat((len - mark.length).coerceAtLeast(0))

    @Test
    fun 문체_참고는_길이_중앙값에_가까운_글을_고른다() {
        // 가장 긴 글을 고르면 모델이 그만큼 쓰려 하고, 가장 짧은 글을 고르면 성의 없는 결과가 된다.
        val values = listOf(prose(60, "A"), prose(200, "B"), prose(210, "C"), prose(2000, "D"))
        val picked = NarrativeFieldAiWriter.selectStyleSamples(values, limit = 2)
        assertEquals(listOf(prose(210, "C"), prose(200, "B")), picked)
        assertEquals("난수 없음 — 같은 데이터면 같은 참고", picked,
            NarrativeFieldAiWriter.selectStyleSamples(values, limit = 2))
    }

    @Test
    fun 문체_참고는_너무_짧은_값과_중복을_거른다() {
        val values = listOf("한 줄 메모", "", prose(300, "A"), prose(300, "A"))
        val picked = NarrativeFieldAiWriter.selectStyleSamples(values, limit = 3)
        assertEquals("메모 조각을 문체로 제시하면 '이 작품은 한 줄만 쓴다'는 거짓 신호가 된다",
            listOf(prose(300, "A")), picked)
    }

    @Test
    fun 문체_참고는_상한까지만_싣고_0이면_아무것도_싣지_않는다() {
        val long = prose(NarrativeFieldAiWriter.STYLE_SAMPLE_CHARS + 500, "A")
        val picked = NarrativeFieldAiWriter.selectStyleSamples(listOf(long), limit = 1)
        assertEquals(NarrativeFieldAiWriter.STYLE_SAMPLE_CHARS + 1, picked[0].length)  // +1 = 말줄임표
        assertTrue(picked[0].endsWith("…"))
        assertTrue(NarrativeFieldAiWriter.selectStyleSamples(listOf(long), limit = 0).isEmpty())
    }

    @Test
    fun 문체_참고가_프롬프트에_실리고_내용_차용을_금지한다() {
        val p = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(), spec.copy(styleSamples = listOf("다른 인물의 글")),
            NarrativeFieldAiWriter.Mode.DRAFT, NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertTrue(p.text.contains("[문체 참고]"))
        assertTrue(p.text.contains("1) 다른 인물의 글"))
        assertTrue("문체만 따르라는 지시가 없으면 남의 설정을 베낀다", p.text.contains("내용은 가져오지 마라"))
        assertTrue(NarrativeFieldAiWriter.buildSystemPrompt().contains("[문체 참고]"))

        // 참고가 없으면 절 자체가 없다 — 빈 라벨로 토큰을 쓰지 않는다
        val none = NarrativeFieldAiWriter.buildUserPrompt(
            ctx(), spec, NarrativeFieldAiWriter.Mode.DRAFT, NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertFalse(none.text.contains("[문체 참고]"))
    }

    // ===== 프롬프트 적재량 설정 =====

    @Test
    fun 적재량_설정은_범위와_눈금_안으로_가둔다() {
        // 눈금 밖 값이 저장되면 Material Slider가 예외로 죽는다 — 저장 전에 여기서 막는다.
        assertEquals(0, AiPromptPolicy.clampUsageExamples(-5))
        assertEquals(AiPromptPolicy.USAGE_EXAMPLES_MAX, AiPromptPolicy.clampUsageExamples(999))
        assertEquals(0, AiPromptPolicy.clampUsageExamples(1) % AiPromptPolicy.USAGE_EXAMPLES_STEP)
        assertEquals(0, AiPromptPolicy.clampUsageExamples(13) % AiPromptPolicy.USAGE_EXAMPLES_STEP)
        assertEquals(0, AiPromptPolicy.clampStyleSamples(-1))
        assertEquals(AiPromptPolicy.STYLE_SAMPLES_MAX, AiPromptPolicy.clampStyleSamples(9))
    }

    @Test
    fun 적재량_기본값은_종전_동작과_같고_비용_추정은_0에서_0이다() {
        assertEquals(
            "설정을 열기 전 동작이 바뀌면 안 된다",
            CharacterFieldAiSuggester.MAX_USAGE_EXAMPLES, AiPromptPolicy.USAGE_EXAMPLES_DEFAULT
        )
        assertEquals(0, AiPromptPolicy.estimatedUsageTokensPerField(0))
        assertEquals(0, AiPromptPolicy.estimatedStyleTokensPerRequest(0))
        assertTrue(AiPromptPolicy.estimatedUsageTokensPerField(12) > 0)
        assertTrue(AiPromptPolicy.estimatedStyleTokensPerRequest(2) > 0)
    }

    @Test
    fun 해석_불가는_빈_결과다() {
        assertTrue(NarrativeFieldAiWriter.parseResponse("그냥 말", 2).drafts.isEmpty())
        assertTrue(NarrativeFieldAiWriter.parseResponse("""{"other":[]}""", 2).drafts.isEmpty())
        // 잘려서 괄호가 안 닫힌 응답 — 형식 오류와 구분은 호출측이 truncated로 한다
        assertTrue(NarrativeFieldAiWriter.parseResponse("""{"drafts":["앞부분""", 2).drafts.isEmpty())
    }
}
