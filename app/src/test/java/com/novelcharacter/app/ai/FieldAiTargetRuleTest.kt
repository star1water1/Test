package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.CharacterFieldAiSuggester.BulkExcludeCause
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.CharacterAiContext
import com.novelcharacter.app.ai.CharacterFieldAiSuggester.FieldSpec
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-1 일괄 추천 대상 규칙(bulkTargetsOf — 단일 소스) + A-2 필드 설명의 프롬프트 전달.
 * 핵심 계약: targets + excluded = 입력 전체(조용한 결손 금지), 서술형은 짧은 값 일괄에서
 * 제외(기존 결함 수리), 설명은 대상 필드에만 실리고 초과분은 truncationNotes로 고지(R-14).
 */
class FieldAiTargetRuleTest {

    private var nextId = 1L

    private fun field(
        key: String,
        type: String = "TEXT",
        config: String = "{}",
        entityType: String = FieldDefinition.ENTITY_CHARACTER
    ) = FieldDefinition(
        id = nextId++, universeId = 1L, key = key, name = "이름_$key",
        type = type, config = config, entityType = entityType
    )

    // ===== bulkTargetsOf =====

    @Test
    fun bulkTargets_partitionsByCause_andSumEqualsInput() {
        val fields = listOf(
            field("normal"),
            field("calc", type = "CALCULATED"),
            field("unknownType", type = "MYSTERY"),
            field("disabled", config = """{"aiSuggest":false}"""),
            field("narrative", config = """{"narrativeMode":"narrative"}"""),
            field("select", type = "SELECT", config = """{"options":["A","B"]}""")
        )
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(fields, emptyMap())

        assertEquals(listOf("normal", "select"), bulk.targets.map { it.spec.key })
        assertEquals(
            listOf("이름_calc", "이름_unknownType"),
            bulk.excluded[BulkExcludeCause.UNSUPPORTED_TYPE]
        )
        assertEquals(listOf("이름_disabled"), bulk.excluded[BulkExcludeCause.AI_DISABLED])
        assertEquals(listOf("이름_narrative"), bulk.excluded[BulkExcludeCause.NARRATIVE_PATH])
        // 합 = 입력 전체 — 어떤 필드도 흔적 없이 사라지지 않는다
        assertEquals(fields.size, bulk.targets.size + bulk.excludedCount)
    }

    @Test
    fun bulkTargets_autoMultilineTextCountsAsNarrative() {
        // narrativeMode 미설정 + 표시형식 여러 줄 = AUTO 판정으로 서술형 (isNarrative와 동일 소스)
        val fields = listOf(field("bg", config = """{"displayFormat":"multiline"}"""))
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(fields, emptyMap())
        assertTrue(bulk.targets.isEmpty())
        assertEquals(listOf("이름_bg"), bulk.excluded[BulkExcludeCause.NARRATIVE_PATH])
    }

    @Test
    fun bulkTargets_disabledWinsOverNarrative() {
        // 꺼짐 + 서술형이면 사유는 AI_DISABLED — 사용자가 끈 결정이 경로 분기보다 앞선 원인이다
        val fields = listOf(
            field("both", config = """{"aiSuggest":false,"narrativeMode":"narrative"}""")
        )
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(fields, emptyMap())
        assertEquals(listOf("이름_both"), bulk.excluded[BulkExcludeCause.AI_DISABLED])
        assertFalse(bulk.excluded.containsKey(BulkExcludeCause.NARRATIVE_PATH))
    }

    @Test
    fun bulkTargets_carriesCurrentValues() {
        val f = field("hair")
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(listOf(f), mapOf(f.id to "흑발"))
        assertEquals("흑발", bulk.targets.single().spec.currentValue)
        assertEquals(f.id, bulk.targets.single().fieldId)
    }

    @Test
    fun bulkExcludedSummary_ordersAndCounts() {
        val fields = listOf(
            field("d1", config = """{"aiSuggest":false}"""),
            field("d2", config = """{"aiSuggest":false}"""),
            field("n1", config = """{"narrativeMode":"narrative"}"""),
            field("c1", type = "CALCULATED")
        )
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(fields, emptyMap())
        val summary = CharacterFieldAiSuggester.bulkExcludedSummary(bulk.excluded)
        // enum 선언 순(미지원 → 꺼짐 → 서술형)으로 안정적으로 나열된다
        assertEquals("계산·미지원 타입 1개 · AI 추천 꺼짐 2개 · 서술형 1개", summary)
    }

    // ===== B-80: 3단 — '개별만'은 끈 것이 아니다 =====

    /** 개별만은 일괄에서 빠지되 **끄기와 다른 사유로** 빠져야 한다(고지가 거짓이 되지 않게). */
    @Test
    fun bulkTargets_manualOnly_excludedUnderItsOwnCause() {
        val fields = listOf(
            field("all"),
            field("manual", config = """{"aiSuggest":"manual"}"""),
            field("off", config = """{"aiSuggest":false}""")
        )
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(fields, emptyMap())

        assertEquals(listOf("all"), bulk.targets.map { it.spec.key })
        assertEquals(listOf("이름_manual"), bulk.excluded[BulkExcludeCause.MANUAL_ONLY])
        assertEquals(listOf("이름_off"), bulk.excluded[BulkExcludeCause.AI_DISABLED])
    }

    /** 제외가 '기능 부재'로 읽히지 않도록 교정 경로를 병기한다(서술형 줄과 같은 규칙). */
    @Test
    fun bulkExcludedDetail_manualOnlyLineCarriesCorrectionPath() {
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(
            listOf(field("m", config = """{"aiSuggest":"manual"}""")), emptyMap()
        )
        val line = CharacterFieldAiSuggester.bulkExcludedDetailLines(bulk.excluded).single()
        assertTrue(line.startsWith("개별 추천만 1개: "))
        assertTrue(line.contains("✨"))
    }

    @Test
    fun bulkTargets_sumStillEqualsInputWithThreeModes() {
        val fields = listOf(
            field("a"), field("b", config = """{"aiSuggest":"manual"}"""),
            field("c", config = """{"aiSuggest":false}"""), field("d", type = "CALCULATED")
        )
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(fields, emptyMap())
        assertEquals(fields.size, bulk.targets.size + bulk.excluded.values.sumOf { it.size })
    }

    @Test
    fun bulkExcludedSummary_emptyIsNull() {
        assertEquals(null, CharacterFieldAiSuggester.bulkExcludedSummary(emptyMap()))
    }

    @Test
    fun bulkExcludedDetail_capsNamesButKeepsTotal() {
        val many = (1..15).map { field("f$it", config = """{"aiSuggest":false}""") }
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(many, emptyMap())
        val line = CharacterFieldAiSuggester.bulkExcludedDetailLines(bulk.excluded).single()
        assertTrue(line.startsWith("AI 추천 꺼짐 15개: "))
        assertTrue(line.contains("외 3개"))
    }

    @Test
    fun bulkExcludedDetail_narrativeLineCarriesCorrectionPath() {
        val bulk = CharacterFieldAiSuggester.bulkTargetsOf(
            listOf(field("n", config = """{"narrativeMode":"narrative"}""")), emptyMap()
        )
        val line = CharacterFieldAiSuggester.bulkExcludedDetailLines(bulk.excluded).single()
        assertTrue(line.contains("필드별 ✨에서 초안·이어쓰기로 작성합니다"))
    }

    // ===== A-2: 필드 설명 프롬프트 전달 =====

    private fun spec(key: String, description: String = "", currentValue: String = "") = FieldSpec(
        key = key, name = "이름_$key", type = FieldType.TEXT, options = emptyList(),
        isBirthDate = false, numberRange = null, currentValue = currentValue,
        description = description
    )

    private fun context() = CharacterAiContext(
        name = "한서린", aliases = emptyList(), tags = emptyList(), memo = "",
        filledFields = listOf("이름_other" to "값"), imageTags = emptyList(),
        factions = emptyList(), relationships = emptyList()
    )

    @Test
    fun fieldSpecOf_readsDescriptionFromConfig() {
        val f = field("mana", config = """{"description":"마나 친화도. 0~100."}""")
        val spec = CharacterFieldAiSuggester.fieldSpecOf(f, "")!!
        assertEquals("마나 친화도. 0~100.", spec.description)
    }

    @Test
    fun userPrompt_descriptionOnTargetLineOnly() {
        val prompt = CharacterFieldAiSuggester.buildUserPrompt(
            context(), listOf(spec("mana", description = "마나 친화도 정의"))
        )
        assertTrue(prompt.text.contains("/ 설명: 마나 친화도 정의"))
        // 컨텍스트([입력된 필드]) 절에는 설명이 실리지 않는다 — 설명 표기는 대상 줄 하나뿐
        assertEquals(1, Regex("/ 설명: ").findAll(prompt.text).count())
        assertTrue(prompt.truncationNotes.isEmpty())
    }

    @Test
    fun userPrompt_noDescription_noMarker() {
        val prompt = CharacterFieldAiSuggester.buildUserPrompt(context(), listOf(spec("plain")))
        assertFalse(prompt.text.contains("/ 설명:"))
    }

    @Test
    fun userPrompt_longDescriptionTruncatedWithNote() {
        val long = "설".repeat(CharacterFieldAiSuggester.MAX_DESCRIPTION_PROMPT_CHARS + 50)
        val prompt = CharacterFieldAiSuggester.buildUserPrompt(
            context(), listOf(spec("mana", description = long))
        )
        assertTrue(prompt.truncationNotes.any { it.contains("필드 설명") && it.contains("초과분 생략") })
        // 잘린 본문 + 말줄임 표기가 실린다
        assertTrue(
            prompt.text.contains(
                "설".repeat(CharacterFieldAiSuggester.MAX_DESCRIPTION_PROMPT_CHARS) + "…"
            )
        )
    }

    @Test
    fun systemPrompt_rule14_descriptionContract() {
        val prompt = CharacterFieldAiSuggester.buildSystemPrompt()
        assertTrue(prompt.contains("14."))
        assertTrue(prompt.contains("설명과 어긋나는 값을 내지 마라"))
        // 기본 규칙은 15번(대결 우열)까지이고, 근거 강도 하한이 켜지면 16번으로 이어진다
        // (규칙 번호 충돌 없음 — 붙이는 규칙이 늘면 이 자리가 함께 움직여야 한다)
        assertTrue(prompt.contains("15."))
        val withFloor =
            CharacterFieldAiSuggester.buildSystemPrompt(CharacterFieldAiSuggester.Confidence.HIGH)
        assertTrue(withFloor.contains("16."))
    }

    @Test
    fun narrativeWriter_promptCarriesDescription() {
        val fd = field("bg", config = """{"description":"유년기 배경 전용. 성인기 사건 금지."}""")
        val spec = NarrativeFieldAiWriter.fieldSpecOf(fd, "")
        assertEquals("유년기 배경 전용. 성인기 사건 금지.", spec.description)
        val prompt = NarrativeFieldAiWriter.buildUserPrompt(
            context(), spec, NarrativeFieldAiWriter.Mode.DRAFT, NarrativeFieldAiWriter.Length.MEDIUM, 2
        )
        assertTrue(prompt.text.contains("설명: 유년기 배경 전용. 성인기 사건 금지."))
    }

    @Test
    fun organizer_systemPromptCarriesDescription() {
        val fd = field("hair", config = """{"description":"머리색은 자연색만 등재한다."}""")
        val prompt = FieldLibraryAiOrganizer.buildSystemPrompt(fd)
        assertTrue(prompt.contains("머리색은 자연색만 등재한다."))
        // 설명 없는 필드에는 설명 블록 자체가 없다
        val plain = FieldLibraryAiOrganizer.buildSystemPrompt(field("plain"))
        assertFalse(plain.contains("필드 설명(사용자 작성)"))
    }
}
