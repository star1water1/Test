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
        assertTrue(
            prompt.contains(
                """{"suggestions":[{"key":"필드키","value":"추천값","reason":"근거 한 문장","confidence":"high|medium|low"}]}"""
            )
        )
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
        aliases: List<String> = emptyList(),
        description: String = ""
    ) = FieldValueEntry(
        fieldDefinitionId = 1L,
        value = value,
        usageCount = usage,
        isHidden = hidden,
        aliasesJson = aliases.joinToString(",", "[", "]") { "\"$it\"" },
        description = description
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
    fun withLibraryUsage_예시_개수는_사용자_설정을_따르되_정확성은_끄지_못한다() {
        val entries = listOf(
            entry("검은 머리", 5, aliases = listOf("흑발")), entry("은발", 3), entry("금발", 1)
        )
        val few = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), entries, exampleLimit = 1)
        assertEquals(listOf("검은 머리"), few.usageExamples)

        // 0 = "프롬프트에 싣지 마라"이지, "별칭 교정을 끄라"가 아니다 (토큰 절약 ≠ 정확성 포기)
        val off = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), entries, exampleLimit = 0)
        assertTrue(off.usageExamples.isEmpty())
        assertEquals("검은 머리", CharacterFieldAiSuggester.normalizeValue("흑발", off))
    }

    @Test
    fun withLibraryUsage_restricted는_설정이_0이어도_허용_목록을_싣는다() {
        // 목록을 안 주고 "목록 밖이라 드롭"할 수는 없다 — 허용 목록은 토큰 설정의 대상이 아니다.
        val entries = listOf(entry("북부", 3), entry("남부", 1))
        val off = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(restricted = true), entries, exampleLimit = 0
        )
        assertEquals(listOf("북부", "남부"), off.usageExamples)
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
        // 프롬프트 지시는 그대로다 — B-79가 바꾼 것은 **응답의 처분**이지 모델에 대한 요구가 아니다
        assertTrue(build.text.contains("(이 목록의 값만 허용)"))
        // 허용 목록을 다 못 실었으면 조용히 두지 않는다 (R-14). 다만 이제 그것은 결손 고지가
        // 아니라 정확도 고지다 — 드롭되는 것이 아니라 '목록 밖' 표시가 느는 것이다.
        assertTrue(build.truncationNotes.any { it.contains("거주지") && it.contains("9종 중 2개") })
        assertTrue(build.truncationNotes.any { it.contains("'목록 밖' 표시가 늘 수 있음") })
        assertTrue(build.truncationNotes.none { it.contains("제외됨") })
    }

    @Test
    fun systemPrompt_표기_기조_규칙을_지시한다() {
        val prompt = CharacterFieldAiSuggester.buildSystemPrompt()
        assertTrue(prompt.contains("기존 사용값"))
        assertTrue(prompt.contains("이 목록의 값만 허용"))
    }

    // ===== 값의 뜻 (값 라이브러리 엔트리 설명) — B-46 =====

    @Test
    fun withLibraryUsage_값_설명은_실제로_실린_값에만_붙는다() {
        val entries = listOf(
            entry("북부", 5, description = "왕도 이북의 한랭지"),
            entry("남부", 3, description = "곡창지대")
        )
        val all = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), entries)
        assertEquals(
            listOf("북부" to "왕도 이북의 한랭지", "남부" to "곡창지대"),
            all.usageDescriptions
        )

        // 예시에서 빠진 값의 뜻은 싣지 않는다 — 모델이 못 쓰는 선택지를 설명하는 토큰이다
        val few = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), entries, exampleLimit = 1)
        assertEquals(listOf("북부" to "왕도 이북의 한랭지"), few.usageDescriptions)
        assertEquals(0, few.usageDescriptionsOmitted)
    }

    @Test
    fun withLibraryUsage_숨김_값의_뜻은_실리지_않는다() {
        // 숨김 값은 예시에도 없으므로 그 뜻을 실을 자리도 없다(집합이 같아야 한다)
        val entries = listOf(
            entry("북부", 5, description = "왕도 이북의 한랭지"),
            entry("폐기값", 9, hidden = true, description = "쓰지 않기로 한 값")
        )
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), entries)
        assertEquals(listOf("북부" to "왕도 이북의 한랭지"), enriched.usageDescriptions)
    }

    @Test
    fun withLibraryUsage_설명이_없으면_값_뜻_자체가_없다() {
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(libSpec(), listOf(entry("북부", 5)))
        assertTrue(enriched.usageDescriptions.isEmpty())
        assertEquals(0, enriched.usageDescriptionsOmitted)
        assertEquals(0, enriched.usageDescriptionsTruncated)
        // 빈 절을 프롬프트에 붙이지 않는다
        assertFalse(
            CharacterFieldAiSuggester.buildUserPrompt(context(), listOf(enriched)).text.contains("값 뜻")
        )
    }

    @Test
    fun selectUsageDescriptions_줄바꿈은_한_줄로_접는다() {
        // 프롬프트 한 줄에 실리므로 원문 개행이 필드 경계를 흉내 내면 안 된다
        val out = CharacterFieldAiSuggester.selectUsageDescriptions(
            listOf("북부"), listOf(entry("북부", description = "왕도 이북\n한랭지"))
        )
        assertEquals(listOf("북부" to "왕도 이북 한랭지"), out.entries)
    }

    @Test
    fun selectUsageDescriptions_긴_설명은_자르고_수를_보고한다() {
        val long = "가".repeat(CharacterFieldAiSuggester.MAX_USAGE_DESCRIPTION_CHARS + 5)
        val out = CharacterFieldAiSuggester.selectUsageDescriptions(
            listOf("북부"), listOf(entry("북부", description = long))
        )
        assertEquals(1, out.truncated)
        assertEquals(
            "가".repeat(CharacterFieldAiSuggester.MAX_USAGE_DESCRIPTION_CHARS) + "…",
            out.entries.single().second
        )
    }

    @Test
    fun selectUsageDescriptions_총_예산을_넘으면_뒤쪽부터_빠지고_수를_보고한다() {
        val values = listOf("북부", "남부", "동부")
        val entries = values.map { entry(it, description = "설명".repeat(5)) }
        val out = CharacterFieldAiSuggester.selectUsageDescriptions(
            values, entries, maxTotalChars = 20
        )
        // 앞쪽(더 대표적인 값)이 남고 뒤쪽이 빠진다 — selectUsageExamples와 같은 자르기 방향
        assertEquals(listOf("북부"), out.entries.map { it.first })
        assertEquals(2, out.omitted)
    }

    @Test
    fun 값_설명_예산은_허용_목록을_짧게_만들지_않는다() {
        // B-46 등재가 경고한 결함: 설명을 값 목록과 한 예산에서 자르면 restricted 필드의
        // **허용 목록 자체가 반만 간다.** 예산을 갈라 둔 것이 그것을 막는다.
        val gloss = "가".repeat(CharacterFieldAiSuggester.MAX_USAGE_DESCRIPTION_CHARS)
        val values = (1..12).map { "값%02d".format(it) }
        val described = values.mapIndexed { i, v -> entry(v, usage = 12 - i, description = gloss) }
        val bare = values.mapIndexed { i, v -> entry(v, usage = 12 - i) }

        val withGloss = CharacterFieldAiSuggester.withLibraryUsage(libSpec(restricted = true), described)
        val without = CharacterFieldAiSuggester.withLibraryUsage(libSpec(restricted = true), bare)

        assertEquals("허용 목록은 설명 유무와 무관하다", without.usageExamples, withGloss.usageExamples)
        assertEquals(values, withGloss.usageExamples)
        // 설명 쪽만 예산에 걸린다 — 그리고 걸린 사실을 센다
        assertTrue(withGloss.usageDescriptions.isNotEmpty())
        assertTrue(withGloss.usageDescriptions.size < values.size)
        assertEquals(
            values.size - withGloss.usageDescriptions.size,
            withGloss.usageDescriptionsOmitted
        )
    }

    @Test
    fun userPrompt_값_뜻은_목록과_분리돼_실린다() {
        val target = libSpec(key = "region", restricted = true).copy(
            name = "거주지",
            usageExamples = listOf("북부", "남부"),
            usageTotal = 2,
            usageDescriptions = listOf("북부" to "왕도 이북의 한랭지", "남부" to "곡창지대")
        )
        val text = CharacterFieldAiSuggester.buildUserPrompt(context(), listOf(target)).text
        // 목록은 모델이 그대로 베껴야 하는 계약이라 한 글자도 섞지 않는다
        assertTrue(text.contains("기존 사용값: 북부, 남부 (이 목록의 값만 허용)"))
        assertTrue(text.contains("값 뜻: 북부 = 왕도 이북의 한랭지 · 남부 = 곡창지대"))
    }

    @Test
    fun userPrompt_값_설명의_절단과_누락을_고지한다() {
        val target = libSpec(key = "region").copy(
            name = "거주지",
            usageExamples = listOf("북부"),
            usageTotal = 1,
            usageDescriptions = listOf("북부" to "왕도 이북"),
            usageDescriptionsTruncated = 1,
            usageDescriptionsOmitted = 3
        )
        val notes = CharacterFieldAiSuggester.buildUserPrompt(context(), listOf(target)).truncationNotes
        assertTrue(notes.any { it.contains("거주지") && it.contains("1건") && it.contains("절단") })
        assertTrue(notes.any { it.contains("거주지") && it.contains("3건") && it.contains("싣지 못함") })
    }

    @Test
    fun systemPrompt_값_뜻_규칙을_지시한다() {
        val prompt = CharacterFieldAiSuggester.buildSystemPrompt()
        assertTrue(prompt.contains("'값 뜻'이 함께 오면"))
        // 설명을 값에 붙여 답하면 restricted에서 '목록 밖'이 된다 — 그것을 프롬프트가 먼저 막는다
        assertTrue(prompt.contains("설명을 값에 붙여 적지 마라"))
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
    fun normalize_restricted는_목록_밖_값을_버리지_않고_표시한다() {
        // B-79 — 저장 경로는 같은 값을 받아 주면서 '추가하고 저장 / 입력 수정'을 묻는다.
        // 유료 응답에서만 버리던 비대칭을 없앤다. **드롭이 아니라 표식이다.**
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(restricted = true), listOf(entry("북부", 3), entry("남부", 1))
        )
        val inList = CharacterFieldAiSuggester.normalizeChecked("북부", enriched)
        assertEquals(CharacterFieldAiSuggester.Normalized.Ok("북부", false), inList)

        val outside = CharacterFieldAiSuggester.normalizeChecked("동부", enriched)
        assertEquals(CharacterFieldAiSuggester.Normalized.Ok("동부", true), outside)
        // 값 자체는 살아남는다 — 이것이 종전과 갈리는 자리다(옛 동작은 null이었다)
        assertEquals("동부", CharacterFieldAiSuggester.normalizeValue("동부", enriched))
    }

    @Test
    fun normalize_restricted라도_라이브러리가_비면_표시하지_않는다() {
        // 허용 목록을 준 적이 없는데 '목록 밖'이라 표시하면 사용자가 고칠 수 없는 표식이 된다
        val empty = CharacterFieldAiSuggester.withLibraryUsage(libSpec(restricted = true), emptyList())
        assertEquals(
            CharacterFieldAiSuggester.Normalized.Ok("동부", false),
            CharacterFieldAiSuggester.normalizeChecked("동부", empty)
        )
    }

    @Test
    fun normalize_restricted가_아니면_목록_밖_표시도_붙지_않는다() {
        // 표식은 restricted 필드의 것이다 — 제안 모드 필드에까지 붙으면 뜻 없는 경고가 된다
        val suggestMode = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(restricted = false), listOf(entry("북부", 3))
        )
        assertEquals(
            CharacterFieldAiSuggester.Normalized.Ok("동부", false),
            CharacterFieldAiSuggester.normalizeChecked("동부", suggestMode)
        )
    }

    @Test
    fun normalize_restricted_복수값은_한_토큰만_벗어나도_표시된다() {
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(type = FieldType.MULTI_TEXT, multiToken = true, restricted = true),
            listOf(entry("검술", 3), entry("마법", 2))
        )
        assertEquals(
            CharacterFieldAiSuggester.Normalized.Ok("검술, 마법", false),
            CharacterFieldAiSuggester.normalizeChecked("검술, 마법", enriched)
        )
        assertEquals(
            CharacterFieldAiSuggester.Normalized.Ok("검술, 요리", true),
            CharacterFieldAiSuggester.normalizeChecked("검술, 요리", enriched)
        )
    }

    @Test
    fun normalize_형식_위반은_여전히_거부된다() {
        // B-79가 연 것은 '목록 밖'뿐이다. 형식·옵션은 계약이므로 그대로 거부한다 —
        // 완화를 한 칸 더 밀면 SELECT 옵션 밖 값이 저장 폼에 들어간다.
        val enriched = CharacterFieldAiSuggester.withLibraryUsage(
            libSpec(type = FieldType.SELECT, restricted = true).copy(options = listOf("남", "여")),
            listOf(entry("남", 3))
        )
        assertEquals(
            CharacterFieldAiSuggester.Normalized.Rejected(
                CharacterFieldAiSuggester.MissingCause.INVALID
            ),
            CharacterFieldAiSuggester.normalizeChecked("무성", enriched)
        )
    }

    @Test
    fun outsideLibraryLine_표식이_없으면_줄도_없다() {
        assertNull(CharacterFieldAiSuggester.outsideLibraryLine(emptyList()))
        assertNull(
            CharacterFieldAiSuggester.outsideLibraryLine(
                listOf(CharacterFieldAiSuggester.Suggestion("k", "v", ""))
            )
        )
    }

    @Test
    fun outsideLibraryLine_개수와_교정_경로를_함께_말한다() {
        val line = CharacterFieldAiSuggester.outsideLibraryLine(
            listOf(
                CharacterFieldAiSuggester.Suggestion("a", "동부", "", outsideLibrary = true),
                CharacterFieldAiSuggester.Suggestion("b", "북부", ""),
                CharacterFieldAiSuggester.Suggestion("c", "서부", "", outsideLibrary = true)
            )
        )!!
        assertTrue(line.contains("2개"))
        // 표식의 뜻만 말하고 다음에 무엇이 일어나는지를 빼면 저장 버튼을 누른 뒤에야 알게 된다
        assertTrue(line.contains("라이브러리에 추가"))
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

    // ===== 결손 회계 (요청했는데 안 온 필드) =====

    @Test
    fun systemPrompt_전량응답을_요구하고_생략을_금지한다() {
        val prompt = CharacterFieldAiSuggester.buildSystemPrompt()
        assertTrue("전부에 대해 항목을 내라는 지시", prompt.contains("전부"))
        assertTrue("생략 대신 빈 값+사유 표기", prompt.contains("""value를 빈 문자열("")로"""))
        assertFalse("생략을 허용하는 종전 지시가 남아 있으면 안 된다", prompt.contains("응답에서 생략한다"))
    }

    @Test
    fun userPrompt_대상_개수를_명시한다() {
        val targets = listOf(spec("a"), spec("b"), spec("c"))
        val text = CharacterFieldAiSuggester.buildUserPrompt(context(), targets).text
        assertTrue(text.contains("총 3개"))
        assertTrue(text.contains("총 3개 항목으로 응답하라"))
    }

    @Test
    fun parse_응답에_없는_필드는_NOT_RETURNED로_집계된다() {
        val targets = listOf(spec("mood", name = "분위기"), spec("hobby", name = "취미"))
        val text = """{"suggestions":[{"key":"mood","value":"차분함","reason":"메모"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(1, parsed.suggestions.size)
        assertEquals(0, parsed.droppedCount)
        assertEquals(1, parsed.missing.size)
        assertEquals("hobby", parsed.missing[0].fieldKey)
        assertEquals(CharacterFieldAiSuggester.MissingCause.NOT_RETURNED, parsed.missing[0].cause)
    }

    @Test
    fun parse_제안과_결손을_합치면_요청_대상_전체다() {
        val targets = listOf(
            spec("mood"),
            spec("gender", type = FieldType.SELECT, options = listOf("남", "여")),
            spec("height", type = FieldType.NUMBER),
            spec("hobby"),
            spec("job", currentValue = "검사")
        )
        val text = """{"suggestions":[
            {"key":"mood","value":"차분함","reason":"메모"},
            {"key":"gender","value":"무성","reason":"옵션 밖"},
            {"key":"height","value":"","reason":"신체 정보가 없음"},
            {"key":"job","value":"검사","reason":"현재와 동일"}
        ]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(targets.size, parsed.suggestions.size + parsed.missing.size)
        val causeByKey = parsed.missing.associate { it.fieldKey to it.cause }
        assertEquals(CharacterFieldAiSuggester.MissingCause.INVALID, causeByKey["gender"])
        assertEquals(CharacterFieldAiSuggester.MissingCause.DECLINED, causeByKey["height"])
        assertEquals(CharacterFieldAiSuggester.MissingCause.NOT_RETURNED, causeByKey["hobby"])
        assertEquals(CharacterFieldAiSuggester.MissingCause.SAME_AS_CURRENT, causeByKey["job"])
    }

    @Test
    fun parse_빈값_사유표기는_드롭이_아니라_추천불가다() {
        val targets = listOf(spec("mood", name = "분위기"))
        val text = """{"suggestions":[{"key":"mood","value":"","reason":"메모에 단서가 없음"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(0, parsed.droppedCount)
        assertEquals(CharacterFieldAiSuggester.MissingCause.DECLINED, parsed.missing[0].cause)
        assertEquals("메모에 단서가 없음", parsed.missing[0].detail)
        assertTrue(parsed.missing[0].describe().contains("메모에 단서가 없음"))
    }

    @Test
    fun parse_환각_key는_unknownKeys로도_고지된다() {
        val targets = listOf(spec("mood"))
        val text = """{"suggestions":[
            {"key":"ghost","value":"여","reason":"환각"},
            {"key":"mood","value":"차분함","reason":"메모"}
        ]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(listOf("ghost"), parsed.unknownKeys)
        assertEquals(1, parsed.droppedCount)
        assertTrue("실제 대상은 다 채워졌다", parsed.missing.isEmpty())
    }

    @Test
    fun parse_suggestions_배열이_없으면_전량_결손이다() {
        val targets = listOf(spec("mood"), spec("hobby"))
        val parsed = CharacterFieldAiSuggester.parseResponse("{}", targets)!!
        assertEquals(2, parsed.missing.size)
        assertTrue(parsed.missing.all { it.cause == CharacterFieldAiSuggester.MissingCause.NOT_RETURNED })
    }

    @Test
    fun parse_중복제안이_뒤에_와도_성공한_필드는_결손이_아니다() {
        val targets = listOf(spec("mood"))
        val text = """{"suggestions":[
            {"key":"mood","value":"차분함","reason":"첫 건"},
            {"key":"mood","value":"활발함","reason":"중복"}
        ]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals(1, parsed.suggestions.size)
        assertEquals(1, parsed.droppedCount)
        assertTrue(parsed.missing.isEmpty())
    }

    // ===== 옵션 매칭 (표기 차이 교정) =====

    @Test
    fun option_공백_대소문자_차이는_옵션_원문으로_교정된다() {
        val targets = listOf(spec("gender", type = FieldType.SELECT, options = listOf("남 성", "Female")))
        val text = """{"suggestions":[{"key":"gender","value":"female","reason":"태그"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, targets)!!
        assertEquals("Female", parsed.suggestions[0].value)
        assertEquals("남 성", CharacterFieldAiSuggester.matchOption("남성", listOf("남 성", "Female")))
    }

    @Test
    fun option_뜻이_다른_값은_여전히_드롭된다() {
        assertNull(CharacterFieldAiSuggester.matchOption("무성", listOf("남", "여")))
        assertNull(CharacterFieldAiSuggester.matchOption("   ", listOf("남", "여")))
    }

    // ===== 근거 강도 (받아올 범위 설정) =====

    private fun confidenceJson(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", """{"suggestions":[""", "]}") { (key, conf) ->
            """{"key":"$key","value":"값$key","reason":"근거","confidence":"$conf"}"""
        }

    @Test
    fun confidence_기본은_전부_받기다() {
        val targets = listOf(spec("a"), spec("b"), spec("c"))
        val parsed = CharacterFieldAiSuggester.parseResponse(
            confidenceJson("a" to "high", "b" to "medium", "c" to "low"), targets
        )!!
        assertEquals(3, parsed.suggestions.size)
        assertTrue(parsed.missing.isEmpty())
        assertEquals(CharacterFieldAiSuggester.Confidence.LOW, parsed.suggestions[2].confidence)
    }

    @Test
    fun confidence_하한을_두면_그_아래는_사유를_달고_제외된다() {
        val targets = listOf(spec("a"), spec("b"), spec("c"))
        val parsed = CharacterFieldAiSuggester.parseResponse(
            confidenceJson("a" to "high", "b" to "medium", "c" to "low"),
            targets,
            CharacterFieldAiSuggester.Confidence.MEDIUM
        )!!
        assertEquals(2, parsed.suggestions.size)
        assertEquals(1, parsed.missing.size)
        assertEquals(CharacterFieldAiSuggester.MissingCause.BELOW_CONFIDENCE, parsed.missing[0].cause)
        // 설정 때문에 빠졌다는 사실이 문구에 남아야 되돌릴 수 있다
        assertTrue(parsed.missing[0].describe().contains("추측"))
    }

    @Test
    fun confidence_미표기는_하한과_무관하게_통과한다() {
        // 강도를 모른다는 이유로 유료 응답을 버리면 모델이 생략한 것과 결과가 같다
        val targets = listOf(spec("a"))
        val text = """{"suggestions":[{"key":"a","value":"값","reason":"근거"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(
            text, targets, CharacterFieldAiSuggester.Confidence.HIGH
        )!!
        assertEquals(1, parsed.suggestions.size)
        assertNull(parsed.suggestions[0].confidence)
    }

    @Test
    fun confidence_알_수_없는_표기는_등급을_지어내지_않는다() {
        assertNull(CharacterFieldAiSuggester.Confidence.fromWire("아주높음"))
        assertNull(CharacterFieldAiSuggester.Confidence.fromWire(""))
        assertNull(CharacterFieldAiSuggester.Confidence.fromWire(null))
        assertEquals(
            CharacterFieldAiSuggester.Confidence.HIGH,
            CharacterFieldAiSuggester.Confidence.fromWire(" HIGH ")
        )
    }

    @Test
    fun confidence_하한이_있을_때만_프롬프트에_지시가_붙는다() {
        assertFalse(CharacterFieldAiSuggester.buildSystemPrompt(null).contains("근거 강도 '"))
        val strict = CharacterFieldAiSuggester.buildSystemPrompt(
            CharacterFieldAiSuggester.Confidence.HIGH
        )
        assertTrue(strict.contains("근거 강도 'high' 이상만"))
        // 스키마에는 언제나 confidence가 있어야 검토 화면이 강도를 표시할 수 있다
        assertTrue(CharacterFieldAiSuggester.buildSystemPrompt(null).contains(""""confidence""""))
    }

    // ===== 지시를 달아 다시 요청 (2차 질문) =====

    @Test
    fun 재요청_지시와_물린_값이_프롬프트에_실린다() {
        val target = spec("mood", name = "분위기").copy(
            userInstruction = "더 어둡게",
            rejectedValues = listOf("차분함")
        )
        val text = CharacterFieldAiSuggester.buildUserPrompt(context(), listOf(target)).text
        assertTrue(text.contains("사용자 지시: 더 어둡게"))
        assertTrue(text.contains("이미 물린 값(다시 내지 말 것): 차분함"))
        assertTrue(
            "지시를 우선하라는 규칙이 시스템 프롬프트에 있어야 한다",
            CharacterFieldAiSuggester.buildSystemPrompt().contains("'사용자 지시'가 붙은 필드")
        )
    }

    @Test
    fun 재요청_같은_값을_되풀이하면_사유를_달고_제외된다() {
        val target = spec("mood", name = "분위기").copy(rejectedValues = listOf("차분함"))
        val text = """{"suggestions":[{"key":"mood","value":"차분함","reason":"또 같은 값"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, listOf(target))!!
        assertTrue(parsed.suggestions.isEmpty())
        assertEquals(CharacterFieldAiSuggester.MissingCause.REPEATED, parsed.missing[0].cause)
    }

    @Test
    fun 재요청_다른_값이면_통과한다() {
        val target = spec("mood").copy(rejectedValues = listOf("차분함"))
        val text = """{"suggestions":[{"key":"mood","value":"음울함","reason":"지시 반영"}]}"""
        val parsed = CharacterFieldAiSuggester.parseResponse(text, listOf(target))!!
        assertEquals("음울함", parsed.suggestions[0].value)
    }

    // ===== 결손 고지 문구 =====

    @Test
    fun missingLines_상한을_넘으면_접되_총수를_밝힌다() {
        val many = (1..CharacterFieldAiSuggester.MAX_MISSING_LINES + 3).map {
            CharacterFieldAiSuggester.MissingField(
                "k$it", "필드$it", CharacterFieldAiSuggester.MissingCause.NOT_RETURNED
            )
        }
        val lines = CharacterFieldAiSuggester.missingLines(many)
        assertEquals(CharacterFieldAiSuggester.MAX_MISSING_LINES + 1, lines.size)
        assertTrue(lines.last().contains("외 3개"))
        assertTrue(CharacterFieldAiSuggester.missingLines(emptyList()).isEmpty())
    }
}
