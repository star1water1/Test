package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.CharacterFieldAiSuggester.CharacterAiContext
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.FieldSpec
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.FieldValueEntry
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

    // ===== 구조화 입력 검증 =====

    @Test
    fun structured_validFormat_kept() {
        val spec = spec("body", type = FieldType.BODY_SIZE)
            .copy(structuredSeparator = "-", structuredPartCount = 3)
        assertEquals("88-60-90", CharacterFieldAiSuggester.normalizeValue("88-60-90", spec))
    }

    @Test
    fun structured_formatViolation_dropped() {
        // 구분자 없는 통짜 문자열 — 첫 파트에 통째로 들어가 "값--"로 저장되는 오염 차단
        val spec = spec("body", type = FieldType.BODY_SIZE)
            .copy(structuredSeparator = "-", structuredPartCount = 3)
        assertNull(CharacterFieldAiSuggester.normalizeValue("가슴85 허리59 힙88", spec))
        assertNull(CharacterFieldAiSuggester.normalizeValue("88-60", spec))
        assertNull(CharacterFieldAiSuggester.normalizeValue("88--90", spec))
    }

    @Test
    fun structured_numberFieldWithStructuredConfig_notValidated() {
        // NUMBER는 폼이 구조화 위젯을 렌더하지 않으므로 config 잔존 구조화 설정을 무시해야 한다
        val config = """{"structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"a"},{"label":"b"}]}}"""
        val spec = CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.NUMBER, config), "")!!
        assertNull(spec.structuredPartCount)
        assertEquals("172", CharacterFieldAiSuggester.normalizeValue("172cm", spec))
    }

    @Test
    fun structured_textFieldWithStructuredConfig_specDerived() {
        val config = """{"structuredInput":{"enabled":true,"separator":"/","parts":[{"label":"a"},{"label":"b"}]}}"""
        val spec = CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.TEXT, config), "")!!
        assertEquals("/", spec.structuredSeparator)
        assertEquals(2, spec.structuredPartCount)
        assertEquals("갑/을", CharacterFieldAiSuggester.normalizeValue("갑/을", spec))
        assertNull(CharacterFieldAiSuggester.normalizeValue("갑을", spec))
    }

    // ===== 청킹 =====

    @Test
    fun chunkTargets_splitsByCountOnly() {
        val max = CharacterFieldAiSuggester.targetsPerRequest(AiTokenPolicy.DEFAULT_REQUEST)
        assertTrue(CharacterFieldAiSuggester.chunkTargets(emptyList()).isEmpty())
        val exactly = (1..max).map { spec("f$it") }
        assertEquals(1, CharacterFieldAiSuggester.chunkTargets(exactly).size)
        val oneMore = (1..max + 1).map { spec("f$it") }
        val chunks = CharacterFieldAiSuggester.chunkTargets(oneMore)
        assertEquals(2, chunks.size)
        assertEquals(max, chunks[0].size)
        assertEquals(1, chunks[1].size)
    }

    @Test
    fun requestCountFor_matchesChunkTargets() {
        // 사전 고지와 실제 청킹이 어긋나면 "요청 N건" 안내가 거짓이 된다 — 상한을 바꿔도 일치해야 한다.
        for (budget in listOf(1024, AiTokenPolicy.DEFAULT_REQUEST, 8192, 32768)) {
            val max = CharacterFieldAiSuggester.targetsPerRequest(budget)
            for (count in listOf(0, 1, max - 1, max, max + 1, max * 3)) {
                val targets = (1..count).map { spec("f$it") }
                assertEquals(
                    "budget=$budget count=$count",
                    CharacterFieldAiSuggester.chunkTargets(targets, budget).size,
                    CharacterFieldAiSuggester.requestCountFor(count, budget)
                )
            }
        }
    }

    @Test
    fun targetsPerRequest_기본값은_종전_상수_15와_같다() {
        // 회귀 방지: 상한 파생으로 바꾸되 기본 동작(4096 → 15개)은 그대로여야 한다.
        assertEquals(15, CharacterFieldAiSuggester.targetsPerRequest(AiTokenPolicy.DEFAULT_REQUEST))
    }

    @Test
    fun targetsPerRequest_상한에_비례하고_경계에서_안전하다() {
        val small = CharacterFieldAiSuggester.targetsPerRequest(AiTokenPolicy.FLOOR)
        assertTrue("아주 작은 상한에서도 최소 1개는 보낸다", small >= 1)
        val big = CharacterFieldAiSuggester.targetsPerRequest(1_000_000)
        assertEquals(
            "프롬프트 무한 확장 방지 — 절대 상한에서 멈춘다",
            CharacterFieldAiSuggester.HARD_MAX_TARGETS_PER_REQUEST, big
        )
        assertTrue(
            "상한이 크면 요청당 대상도 늘어난다",
            CharacterFieldAiSuggester.targetsPerRequest(8192) >
                CharacterFieldAiSuggester.targetsPerRequest(2048)
        )
    }

    // ===== 컨텍스트 결손 고지 =====

    @Test
    fun loadFailures_surfacedAsTruncationNotes() {
        val build = CharacterFieldAiSuggester.buildUserPrompt(
            context().copy(loadFailures = listOf("관계", "소속 세력")),
            listOf(spec("mood"))
        )
        assertTrue(build.truncationNotes.any { it.contains("관계 정보를 불러오지 못함") })
        assertTrue(build.truncationNotes.any { it.contains("소속 세력 정보를 불러오지 못함") })
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

    // ===== 기존 사용값(표기 기조) — 값 라이브러리 연동 =====

    private fun entry(
        value: String,
        usage: Int = 0,
        hidden: Boolean = false,
        aliases: List<String> = emptyList()
    ) = FieldValueEntry(
        fieldDefinitionId = 1L,
        value = value,
        usageCount = usage,
        isHidden = hidden,
        aliasesJson = aliases.joinToString(",", "[", "]") { "\"$it\"" }
    )

    private fun libSpec(
        key: String = "hair",
        type: FieldType = FieldType.TEXT,
        options: List<String> = emptyList(),
        multiToken: Boolean = false,
        restricted: Boolean = false
    ) = spec(key, type = type, options = options).copy(
        fieldId = 1L, libraryEligible = true, multiToken = multiToken, restrictedToLibrary = restricted
    )

    @Test
    fun fieldSpecOf_라이브러리_연동_플래그가_필드_설정을_따른다() {
        val text = CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.TEXT), "")!!
        assertTrue("TEXT 기본(suggest)은 카탈로그 대상", text.libraryEligible)
        assertFalse(text.restrictedToLibrary)
        assertFalse(text.multiToken)

        val multi = CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.MULTI_TEXT), "")!!
        assertTrue(multi.libraryEligible)
        assertTrue(multi.multiToken)

        val restricted = CharacterFieldAiSuggester.fieldSpecOf(
            fieldDef(FieldType.SELECT, """{"valueLibrary":{"inputMode":"restricted"}}"""), ""
        )!!
        assertTrue(restricted.restrictedToLibrary)

        // free = "이 필드엔 기존 값을 들이대지 마라" — 용례도 접기도 하지 않는다 (자율성)
        val free = CharacterFieldAiSuggester.fieldSpecOf(
            fieldDef(FieldType.TEXT, """{"valueLibrary":{"inputMode":"free"}}"""), ""
        )!!
        assertFalse(free.libraryEligible)

        // 라이브러리 비대상 타입은 연동하지 않는다 (판정은 FieldValueTokenizer 단일 소스)
        assertFalse(CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.NUMBER), "")!!.libraryEligible)
        assertFalse(CharacterFieldAiSuggester.fieldSpecOf(fieldDef(FieldType.BODY_SIZE), "")!!.libraryEligible)
    }

    @Test
    fun selectUsageExamples_상위빈도_더하기_균등샘플_결정적() {
        val entries = (1..10).map { entry("v%02d".format(it), usage = 11 - it) }
        val picked = CharacterFieldAiSuggester.selectUsageExamples(entries, limit = 6)
        // 앞 4개(ceil(6*2/3))는 빈도 상위, 나머지 2개는 잔여 6개를 균등 간격으로 훑는다
        assertEquals(listOf("v01", "v02", "v03", "v04", "v05", "v08"), picked)
        // 같은 입력이면 항상 같은 결과 (난수 없음)
        assertEquals(picked, CharacterFieldAiSuggester.selectUsageExamples(entries, limit = 6))
    }

    @Test
    fun selectUsageExamples_빈도가_전부_0이면_가나다_앞쪽이_아니라_전체를_훑는다() {
        val entries = (1..10).map { entry("v%02d".format(it)) }
        val picked = CharacterFieldAiSuggester.selectUsageExamples(entries, limit = 5)
        assertEquals(listOf("v01", "v03", "v05", "v07", "v09"), picked)
    }

    @Test
    fun selectUsageExamples_상한_이하면_전량_중복은_제거() {
        val entries = listOf(entry("흑발", 3), entry("은발", 1), entry("흑발", 3))
        assertEquals(listOf("흑발", "은발"), CharacterFieldAiSuggester.selectUsageExamples(entries, limit = 12))
    }

    @Test
    fun selectUsageExamples_산문_길이_값은_예시가_아니다() {
        val prose = "가".repeat(CharacterFieldAiSuggester.MAX_USAGE_EXAMPLE_VALUE_CHARS + 1)
        val picked = CharacterFieldAiSuggester.selectUsageExamples(listOf(entry(prose, 9), entry("흑발", 1)))
        assertEquals(listOf("흑발"), picked)
    }

    @Test
    fun selectUsageExamples_총_길이_상한을_넘지_않되_최소_1개는_남는다() {
        val entries = (1..5).map { entry("값".repeat(10) + it, usage = 6 - it) }
        val capped = CharacterFieldAiSuggester.selectUsageExamples(entries, limit = 5, maxTotalChars = 30)
        assertEquals(2, capped.size)
        val single = CharacterFieldAiSuggester.selectUsageExamples(entries, limit = 5, maxTotalChars = 1)
        assertEquals(1, single.size)
    }

    @Test
    fun withLibraryUsage_예시는_숨김_제외_접기표는_숨김_포함() {
        val entries = listOf(
            entry("검은 머리", usage = 5, aliases = listOf("흑발")),
            entry("은발", usage = 2),
            entry("폐기값", usage = 0, hidden = true)
        )
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), entries)
        assertEquals(listOf("검은 머리", "은발"), enriched.usageExamples)
        assertEquals("숨김은 '입력 제안에서 제외'이므로 종수에서도 빠진다", 2, enriched.usageTotal)
        // 접기표는 저장 시 검증 집합과 같아야 한다 — 숨김 값도 저장 가능한 값이다
        assertEquals("검은 머리", enriched.canonicalByVariant["흑발"])
        assertEquals("검은 머리", enriched.canonicalByVariant["검은 머리"])
        assertTrue(enriched.canonicalByVariant.containsKey("폐기값"))
    }

    @Test
    fun withLibraryUsage_canonical이_남의_별칭을_이긴다() {
        val entries = listOf(entry("은발", aliases = listOf("백발")), entry("백발"))
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), entries)
        assertEquals("백발", enriched.canonicalByVariant["백발"])
    }

    @Test
    fun withLibraryUsage_비대상_필드는_손대지_않는다() {
        val spec = spec("height", type = FieldType.NUMBER)  // libraryEligible = false
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(spec, listOf(entry("172", 3)))
        assertEquals(spec, enriched)
    }

    @Test
    fun userPrompt_기존_사용값이_실린다() {
        val target = libSpec(key = "hair").copy(
            name = "머리색", usageExamples = listOf("흑발", "은발", "금발"), usageTotal = 10
        )
        val build = CharacterFieldAiSuggester.buildUserPrompt(context(), listOf(target))
        assertTrue(build.text.contains("기존 사용값(총 10종 중 3개 예시): 흑발, 은발, 금발"))
        // 선별이 아니라 전량이면 "N종 중 M개" 군더더기를 붙이지 않는다
        val whole = CharacterFieldAiSuggester.buildUserPrompt(
            context(), listOf(target.copy(usageTotal = 3))
        )
        assertTrue(whole.text.contains("기존 사용값: 흑발, 은발, 금발"))
        assertTrue(build.truncationNotes.isEmpty())
    }

    @Test
    fun userPrompt_restricted는_허용_표시와_결손_고지를_함께_낸다() {
        val target = libSpec(key = "region", restricted = true).copy(
            name = "거주지", usageExamples = listOf("북부", "남부"), usageTotal = 9
        )
        val build = CharacterFieldAiSuggester.buildUserPrompt(context(), listOf(target))
        assertTrue(build.text.contains("(이 목록의 값만 허용)"))
        // 허용 목록을 다 못 실었으면 조용히 두지 않는다 — 목록 밖 제안은 드롭되므로 (R-14)
        assertTrue(build.truncationNotes.any { it.contains("거주지") && it.contains("9종 중 2개") })
    }

    @Test
    fun systemPrompt_표기_기조_규칙을_지시한다() {
        val prompt = CharacterFieldAiSuggester.buildSystemPrompt()
        assertTrue(prompt.contains("기존 사용값"))
        assertTrue(prompt.contains("이 목록의 값만 허용"))
    }

    // ===== 응답 접기·허용 검증 =====

    @Test
    fun normalize_별칭_표기를_canonical로_교정한다() {
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(), listOf(entry("검은 머리", 3, aliases = listOf("흑발")))
        )
        assertEquals("검은 머리", CharacterFieldAiSuggester.normalizeValue("흑발", enriched))
        // 미등록 표기는 손대지 않는다 (새 값 자체는 막지 않음)
        assertEquals("주황머리", CharacterFieldAiSuggester.normalizeValue("주황머리", enriched))
    }

    @Test
    fun normalize_SELECT는_접은_뒤_옵션과_대조한다() {
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(type = FieldType.SELECT, options = listOf("남", "여")),
            listOf(entry("남", 4, aliases = listOf("남성")))
        )
        // 종전에는 옵션에 없는 '남성'이 그대로 드롭됐다 — 접고 나서 대조하면 살릴 수 있다
        assertEquals("남", CharacterFieldAiSuggester.normalizeValue("남성", enriched))
        assertNull(CharacterFieldAiSuggester.normalizeValue("무성", enriched))
    }

    @Test
    fun normalize_복수값은_토큰_단위로_접고_중복을_없앤다() {
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(type = FieldType.MULTI_TEXT, multiToken = true),
            listOf(entry("검술", 5, aliases = listOf("검", "도검술")))
        )
        assertEquals(
            "검술, 마법",
            CharacterFieldAiSuggester.normalizeValue("검, 도검술, 마법", enriched)
        )
    }

    @Test
    fun normalize_restricted는_목록_밖_값을_드롭한다() {
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(restricted = true), listOf(entry("북부", 3), entry("남부", 1))
        )
        assertEquals("북부", CharacterFieldAiSuggester.normalizeValue("북부", enriched))
        assertNull("저장 시 가드와 같은 규칙", CharacterFieldAiSuggester.normalizeValue("동부", enriched))
    }

    @Test
    fun normalize_restricted라도_라이브러리가_비면_제한하지_않는다() {
        // 허용 목록을 준 적이 없는데 '목록 밖'이라고 드롭하면 유료 응답을 통째로 버리는 셈이다
        val empty = CharacterFieldAiSuggester.withLibraryUsage(libSpec(restricted = true), emptyList())
        assertEquals("동부", CharacterFieldAiSuggester.normalizeValue("동부", empty))
    }

    @Test
    fun normalize_restricted_복수값은_모든_토큰이_허용목록에_있어야_한다() {
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(type = FieldType.MULTI_TEXT, multiToken = true, restricted = true),
            listOf(entry("검술", 3), entry("마법", 2))
        )
        assertEquals("검술, 마법", CharacterFieldAiSuggester.normalizeValue("검술, 마법", enriched))
        assertNull(CharacterFieldAiSuggester.normalizeValue("검술, 요리", enriched))
    }

    @Test
    fun parse_접기로_현재값과_같아진_제안은_드롭된다() {
        // '흑발' 제안이 canonical '검은 머리'로 접히면 현재 값과 동일 — 이미 있는 값을 다시 권하지 않는다
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec().copy(currentValue = "검은 머리"),
            listOf(entry("검은 머리", 3, aliases = listOf("흑발")))
        )
        val parsed = CharacterFieldAiSuggester.parseResponse(
            """{"suggestions":[{"key":"hair","value":"흑발","reason":"태그"}]}""", listOf(enriched)
        )!!
        assertTrue(parsed.suggestions.isEmpty())
        assertEquals(1, parsed.droppedCount)
    }
}
