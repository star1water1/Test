package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.CharacterFieldAiSuggester.CharacterAiContext
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.FieldSpec
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 필드 추천 프롬프트 조립·응답 파싱·검증 (AiService 미호출 — 텍스트 주입).
 * 핵심 계약: 형식·옵션에 맞지 않는 제안은 드롭하고 드롭 수를 보고하며(변수 제어),
 * 컨텍스트 절단은 truncationNotes로 반드시 고지한다 (R-14).
 */
class CharacterFieldAiSuggesterTest {

    private fun spec(
        key: String,
        name: String = key,
        type: FieldType = FieldType.TEXT,
        options: List<String> = emptyList(),
        isBirthDate: Boolean = false,
        currentValue: String = ""
    ) = FieldSpec(
        key = key, name = name, type = type, options = options,
        isBirthDate = isBirthDate, numberRange = null, currentValue = currentValue
    )

    private fun context(
        name: String = "한서린",
        tags: List<String> = listOf("냉정", "검사"),
        memo: String = "북부 출신",
        filledFields: List<Pair<String, String>> = listOf("성격" to "과묵함"),
        imageTags: List<String> = listOf("은발", "갑옷"),
        factions: List<String> = listOf("은빛 기사단"),
        relationships: List<String> = listOf("강도윤 – 라이벌")
    ) = CharacterAiContext(
        name = name, aliases = listOf("서리"), tags = tags, memo = memo,
        filledFields = filledFields, imageTags = imageTags,
        factions = factions, relationships = relationships
    )

    // ===== 프롬프트 조립 =====

    @Test
    fun systemPrompt_containsJsonSchema() {
        val prompt = CharacterFieldAiSuggester.buildSystemPrompt()
        assertTrue(prompt.contains("""{"suggestions":[{"key":"필드키","value":"추천값","reason":"근거 한 문장"}]}"""))
    }

    @Test
    fun userPrompt_containsAllContextSections() {
        val targets = listOf(
            spec("gender", name = "성별", type = FieldType.SELECT, options = listOf("남", "여", "?")),
            spec("birth", name = "생일", isBirthDate = true).copy(formatHint = "MM-DD (월-일, 예: 03-15)"),
            spec("height", name = "키", type = FieldType.NUMBER).copy(numberRange = 140.0 to 200.0)
        )
        val build = CharacterFieldAiSuggester.buildUserPrompt(context(), targets)
        val text = build.text
        assertTrue(text.contains("이름: 한서린"))
        assertTrue(text.contains("이명: 서리"))
        assertTrue(text.contains("태그: 냉정, 검사"))
        assertTrue(text.contains("메모: 북부 출신"))
        assertTrue(text.contains("이미지 태그: 은발, 갑옷"))
        assertTrue(text.contains("소속 세력: 은빛 기사단"))
        assertTrue(text.contains("관계: 강도윤 – 라이벌"))
        assertTrue(text.contains("성격: 과묵함"))
        assertTrue(text.contains("key: gender"))
        assertTrue(text.contains("옵션: 남, 여, ?"))
        assertTrue(text.contains("형식: MM-DD"))
        assertTrue(text.contains("범위: 140~200"))
        assertTrue(build.truncationNotes.isEmpty())
    }

    @Test
    fun userPrompt_targetFieldExcludedFromFilledSection() {
        val targets = listOf(spec("gender", name = "성별", currentValue = "남"))
        val build = CharacterFieldAiSuggester.buildUserPrompt(
            context(filledFields = listOf("성별" to "남", "성격" to "과묵함")), targets
        )
        // 대상 필드의 현재 값은 [입력된 필드]가 아니라 필드 스펙 쪽에 실린다
        assertTrue(build.text.contains("현재 값: 남"))
        assertFalse(build.text.contains("성별: 남\n"))
        assertTrue(build.text.contains("성격: 과묵함"))
    }

    @Test
    fun userPrompt_truncation_reported() {
        val longMemo = "가".repeat(CharacterFieldAiSuggester.MAX_MEMO_CHARS + 100)
        val manyRelationships = (1..CharacterFieldAiSuggester.MAX_RELATIONSHIPS + 5).map { "인물$it – 지인" }
        val build = CharacterFieldAiSuggester.buildUserPrompt(
            context(memo = longMemo, relationships = manyRelationships),
            listOf(spec("birth", isBirthDate = true))
        )
        assertEquals(2, build.truncationNotes.size)
        assertTrue(build.truncationNotes.any { it.contains("메모") })
        assertTrue(build.truncationNotes.any { it.contains("관계") })
    }

    @Test
    fun userPrompt_longFieldValues_truncatedWithSingleNote() {
        val longValue = "나".repeat(CharacterFieldAiSuggester.MAX_VALUE_CHARS + 50)
        val build = CharacterFieldAiSuggester.buildUserPrompt(
            context(filledFields = listOf("설정" to longValue, "이력" to longValue)),
            listOf(spec("birth", isBirthDate = true))
        )
        assertEquals(1, build.truncationNotes.size)
        assertTrue(build.truncationNotes[0].contains("2건"))
    }

    // ===== 응답 파싱·검증 =====

    @Test
    fun parse_validSuggestions() {
        val targets = listOf(
            spec("gender", type = FieldType.SELECT, options = listOf("남", "여")),
            spec("birth", isBirthDate = true)
        )
        val text = """{"suggestions":[
            {"key":"gender","value":"여","reason":"태그 기반"},
            {"key":"birth","value":"03-15","reason":"봄 이미지"}
        ]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(2, parsed.suggestions.size)
        assertEquals("여", parsed.suggestions[0].value)
        assertEquals("태그 기반", parsed.suggestions[0].reason)
        assertEquals(0, parsed.droppedCount)
    }

    @Test
    fun parse_codeFencedResponse_tolerated() {
        val targets = listOf(spec("mood"))
        val text = "```json\n{\"suggestions\":[{\"key\":\"mood\",\"value\":\"차분함\",\"reason\":\"메모\"}]}\n```"
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(1, parsed.suggestions.size)
    }

    @Test
    fun parse_unknownKey_droppedAndCounted() {
        val targets = listOf(spec("gender", type = FieldType.SELECT, options = listOf("남", "여")))
        val text = """{"suggestions":[{"key":"ghost","value":"여","reason":"환각 키"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertTrue(parsed.suggestions.isEmpty())
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun parse_hallucinatedSelectOption_dropped() {
        val targets = listOf(spec("gender", type = FieldType.SELECT, options = listOf("남", "여")))
        val text = """{"suggestions":[{"key":"gender","value":"무성","reason":"옵션에 없음"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertTrue(parsed.suggestions.isEmpty())
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun parse_duplicateKey_firstWins() {
        val targets = listOf(spec("mood"))
        val text = """{"suggestions":[
            {"key":"mood","value":"차분함","reason":"첫 건"},
            {"key":"mood","value":"활발함","reason":"중복"}
        ]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(1, parsed.suggestions.size)
        assertEquals("차분함", parsed.suggestions[0].value)
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun parse_sameAsCurrentValue_dropped() {
        val targets = listOf(spec("mood", currentValue = "차분함"))
        val text = """{"suggestions":[{"key":"mood","value":"차분함","reason":"동일값"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertTrue(parsed.suggestions.isEmpty())
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun parse_garbageText_returnsNull() {
        assertNull(CharacterFieldAiSuggester.parseResponse("추천해 드릴게요!", listOf(spec("mood"))))
    }

    @Test
    fun parse_missingSuggestionsArray_emptyNotNull() {
        val parsed = CharacterFieldAiSuggester.parseResponse("{}", listOf(spec("mood")))!!
        assertTrue(parsed.suggestions.isEmpty())
        assertEquals(0, parsed.droppedCount)
    }

    // ===== 생일 정규화 =====

    @Test
    fun birthDate_shortForm_normalized() {
        val targets = listOf(spec("birth", isBirthDate = true))
        val text = """{"suggestions":[{"key":"birth","value":"3-5","reason":"관용 수용"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals("03-05", parsed.suggestions[0].value)
    }

    @Test
    fun birthDate_invalidCalendar_dropped() {
        assertNull(CharacterFieldAiSuggester.normalizeBirthDate("13-40"))
        assertNull(CharacterFieldAiSuggester.normalizeBirthDate("00-10"))
        assertNull(CharacterFieldAiSuggester.normalizeBirthDate("04-31"))
        assertNull(CharacterFieldAiSuggester.normalizeBirthDate("02-30"))
        assertNull(CharacterFieldAiSuggester.normalizeBirthDate("2월 29일"))
    }

    @Test
    fun birthDate_leapDay_accepted() {
        assertEquals("02-29", CharacterFieldAiSuggester.normalizeBirthDate("02-29"))
        assertEquals("12-31", CharacterFieldAiSuggester.normalizeBirthDate("12-31"))
    }

    // ===== 숫자 정규화 =====

    @Test
    fun number_withUnit_leadingNumberExtracted() {
        val targets = listOf(spec("height", type = FieldType.NUMBER))
        val text = """{"suggestions":[{"key":"height","value":"172cm","reason":"단위 부착"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals("172", parsed.suggestions[0].value)
    }

    @Test
    fun number_nonNumeric_dropped() {
        val targets = listOf(spec("height", type = FieldType.NUMBER))
        val text = """{"suggestions":[{"key":"height","value":"큰 편","reason":"비수치"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertTrue(parsed.suggestions.isEmpty())
        assertEquals(1, parsed.droppedCount)
    }

    @Test
    fun number_decimalAndNegative_kept() {
        assertEquals("172.5", CharacterFieldAiSuggester.normalizeNumber("172.5"))
        assertEquals("-3", CharacterFieldAiSuggester.normalizeNumber("-3"))
    }

    // ===== FieldSpec 파생 =====

    private fun fieldDef(type: FieldType, config: String = "{}", key: String = "f") = FieldDefinition(
        id = 1L, universeId = 1L, key = key, name = "필드", type = type.name, config = config
    )

    @Test
    fun fieldSpecOf_calculatedAndUnknown_excluded() {
        assertNull(CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.CALCULATED), ""))
        val unknown = FieldDefinition(id = 1L, universeId = 1L, key = "f", name = "필드", type = "NOPE")
        assertNull(CharacterFieldAiSuggester.fieldSpecOf(unknown, ""))
    }

    @Test
    fun fieldSpecOf_birthDate_hasFormatHint() {
        val config = """{"semanticRole":"birth_date"}"""
        val spec = CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.TEXT, config), "")!!
        assertTrue(spec.isBirthDate)
        assertTrue(spec.formatHint!!.contains("MM-DD"))
    }

    @Test
    fun fieldSpecOf_selectOptions_parsed() {
        val config = """{"options":["남","여","?"]}"""
        val spec = CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.SELECT, config), "남")!!
        assertEquals(listOf("남", "여", "?"), spec.options)
        assertEquals("남", spec.currentValue)
    }

    @Test
    fun fieldSpecOf_bodySize_defaultStructuredHint() {
        val spec = CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.BODY_SIZE), "")!!
        assertTrue(spec.formatHint!!.contains("B-W-H"))
    }
}
