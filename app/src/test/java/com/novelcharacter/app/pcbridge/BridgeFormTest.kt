package com.novelcharacter.app.pcbridge

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeFormTest {

    private fun field(
        id: Long,
        type: String,
        key: String = "k$id",
        name: String = "필드$id",
        config: String = "{}",
        required: Boolean = false,
        group: String = "기본 정보"
    ) = FieldDefinition(
        id = id,
        universeId = 1L,
        key = key,
        name = name,
        type = type,
        config = config,
        groupName = group,
        isRequired = required
    )

    private fun viewOf(f: FieldDefinition, value: String = "", calc: Map<Long, String> = emptyMap()) =
        BridgeForm.describe(listOf(f), mapOf(f.id to value), calc).fields.single()

    // ───────── 타입별 기술자 ─────────

    @Test
    fun text_isPlainLine() {
        val v = viewOf(field(1, FieldType.TEXT.name), "안녕")
        assertEquals(BridgeForm.Input.Line("안녕"), v.input)
        assertFalse(v.readOnly)
        assertNull(v.notice)
        assertEquals("TEXT", v.type)
    }

    @Test
    fun number_isNumericButCarriedAsText() {
        // 소수·단위가 섞인 기존 값을 숫자로 접지 않는다.
        val v = viewOf(field(2, FieldType.NUMBER.name), "173.5")
        assertEquals(BridgeForm.Input.Numeric("173.5"), v.input)
    }

    @Test
    fun select_carriesOptionsFromConfig() {
        val v = viewOf(field(3, FieldType.SELECT.name, config = """{"options":["남","여","?"]}"""), "여")
        assertEquals(BridgeForm.Input.Choice("여", listOf("남", "여", "?")), v.input)
    }

    @Test
    fun grade_usesGradeOptionsIncludingDefault() {
        val v = viewOf(field(4, FieldType.GRADE.name), "A")
        assertEquals(BridgeForm.Input.Choice("A", listOf("C", "B", "A", "S")), v.input)
    }

    /** 다중값은 **저장된 줄 그대로** 나른다 — 쪼개 다시 붙이면 따옴표 관용이 갈린다(R-63). */
    @Test
    fun multiText_carriesStoredLineVerbatim() {
        val stored = """"별칭, 둘", 본명"""
        val v = viewOf(field(5, FieldType.MULTI_TEXT.name), stored)
        assertEquals(BridgeForm.Input.Line(stored), v.input)
    }

    /** R-16 — 계산 필드는 저장 행이 없고, 웹에서도 읽기 전용이다. */
    @Test
    fun calculated_isReadOnlyWithReason() {
        val f = field(6, FieldType.CALCULATED.name)
        val v = viewOf(f, "무시됨", mapOf(6L to "42"))
        assertEquals(BridgeForm.Input.Readonly("42"), v.input)
        assertTrue(v.readOnly)
        assertEquals(BridgeForm.NOTICE_CALCULATED, v.notice)
    }

    /** 계산 실패를 0으로 접지 않는다 — 값이 없으면 빈 글자다. */
    @Test
    fun calculated_missingDisplayIsBlankNotZero() {
        val v = viewOf(field(7, FieldType.CALCULATED.name), "", emptyMap())
        assertEquals(BridgeForm.Input.Readonly(""), v.input)
    }

    /**
     * **모르는 타입을 TEXT로 접지 않는다.** 월드패키지·프리셋 JSON·손으로 고친 DB가
     * 모르는 글자를 실어 올 수 있고, 조용히 접으면 저장이 값의 모양을 바꾼다.
     */
    @Test
    fun unknownType_isReadOnlyWithReason_notFoldedToText() {
        val v = viewOf(field(8, "SOMETHING_NEW"), "원래 값")
        assertEquals(BridgeForm.TYPE_UNKNOWN, v.type)
        assertEquals(BridgeForm.Input.Readonly("원래 값"), v.input)
        assertTrue(v.readOnly)
        assertEquals(BridgeForm.NOTICE_UNKNOWN_TYPE, v.notice)
    }

    // ───────── 구조화 입력 ─────────

    /** BODY_SIZE는 설정이 없어도 B/W/H 3칸이다 — `DEFAULT_BODY_SIZE`가 단일 소스다. */
    @Test
    fun bodySize_defaultsToThreeNumericPartsWithUnit() {
        val v = viewOf(field(9, FieldType.BODY_SIZE.name), "88-60-90")
        val parts = (v.input as BridgeForm.Input.Parts).parts
        assertEquals(listOf("B", "W", "H"), parts.map { it.label })
        assertEquals(listOf("88", "60", "90"), parts.map { it.value })
        assertEquals(listOf("B (cm)", "W (cm)", "H (cm)"), parts.map { it.hint })
        assertTrue(parts.all { it.numeric })
    }

    @Test
    fun structuredText_usesConfiguredParts() {
        val cfg = """{"structuredInput":{"enabled":true,"separator":"-",
            "parts":[{"label":"월","inputType":"number"},{"label":"일","inputType":"number"}]}}"""
        val v = viewOf(field(10, FieldType.TEXT.name, config = cfg), "03-15")
        val parts = (v.input as BridgeForm.Input.Parts).parts
        assertEquals(listOf("월", "일"), parts.map { it.label })
        assertEquals(listOf("03", "15"), parts.map { it.value })
        // 단위가 없으면 힌트는 라벨 그대로다.
        assertEquals(listOf("월", "일"), parts.map { it.hint })
    }

    /** 빈 파트는 자리를 지킨다 — 어느 칸이 비었는지 PC에서도 보여야 한다. */
    @Test
    fun structured_emptyPartsKeepTheirSlots() {
        val v = viewOf(field(11, FieldType.BODY_SIZE.name), "88--90")
        val parts = (v.input as BridgeForm.Input.Parts).parts
        assertEquals(listOf("88", "", "90"), parts.map { it.value })
    }

    /**
     * 구조화를 켜기 전에 적은 **구분자 없는 자유 글**은 첫 파트로 몰린다.
     * 앱 편집 폼이 그렇게 채우므로(`DynamicFieldFormBuilder` 659행) PC도 같아야 한다 —
     * 읽기 화면의 `displayValue`를 닮으면 저장이 값을 바꾼다.
     */
    @Test
    fun structured_legacyFreeTextGoesToFirstPart_matchingAppEditForm() {
        val v = viewOf(field(12, FieldType.BODY_SIZE.name), "보통 체형")
        val parts = (v.input as BridgeForm.Input.Parts).parts
        assertEquals(listOf("보통 체형", "", ""), parts.map { it.value })
    }

    // ───────── 기술자 부가 정보 ─────────

    @Test
    fun describe_carriesNameKeyGroupRequiredDescription() {
        val f = field(
            13, FieldType.TEXT.name, key = "mana", name = "마나친화", required = true,
            group = "능력", config = """{"description":"타고난 친화도"}"""
        )
        val v = viewOf(f)
        assertEquals("mana", v.key)
        assertEquals("마나친화", v.name)
        assertEquals("능력", v.group)
        assertTrue(v.required)
        assertEquals("타고난 친화도", v.description)
    }

    // ───────── 커버 집합 (R-5) ─────────

    /**
     * 커버 집합은 **기술자를 만든 목록 전체**이고 CALCULATED도 든다 —
     * 앱 폼의 `coveredFieldDefinitionIds()`와 같은 모양이다.
     */
    @Test
    fun coverSet_isEveryDescribedField_includingCalculated() {
        val fields = listOf(
            field(20, FieldType.TEXT.name),
            field(21, FieldType.CALCULATED.name),
            field(22, "WHO_KNOWS")
        )
        val d = BridgeForm.describe(fields, emptyMap())
        assertEquals(setOf(20L, 21L, 22L), d.coveredFieldDefinitionIds)
        assertEquals(3, d.fields.size)
    }

    @Test
    fun coverSet_isEmptyWhenNothingRendered() {
        // "값이 전부 비었다"와 "애초에 렌더할 필드가 없다"는 다른 사실이다 (R-5).
        val d = BridgeForm.describe(emptyList(), emptyMap())
        assertTrue(d.coveredFieldDefinitionIds.isEmpty())
        assertTrue(d.fields.isEmpty())
    }

    // ───────── 값 수집 ─────────

    @Test
    fun collect_skipsCalculatedAndUnknownType() {
        val fields = listOf(
            field(30, FieldType.TEXT.name),
            field(31, FieldType.CALCULATED.name),
            field(32, "WHO_KNOWS")
        )
        val got = BridgeForm.collect(
            fields,
            mapOf(
                30L to BridgeForm.Submitted.Text("값"),
                31L to BridgeForm.Submitted.Text("계산값"),
                32L to BridgeForm.Submitted.Text("되돌려받은 값")
            ),
            characterId = 7L
        )
        assertEquals(listOf(30L), got.map { it.fieldDefinitionId })
        assertEquals("값", got.single().value)
        assertEquals(7L, got.single().characterId)
    }

    /** 빈 값은 담지 않는다 — 담지 않는 것이 "사용자가 비웠다"의 표현이다. */
    @Test
    fun collect_dropsBlankSoMergeCanDelete() {
        val fields = listOf(field(33, FieldType.TEXT.name), field(34, FieldType.TEXT.name))
        val got = BridgeForm.collect(
            fields,
            mapOf(
                33L to BridgeForm.Submitted.Text("   "),
                34L to BridgeForm.Submitted.Text(" 남는 값 ")
            ),
            characterId = 1L
        )
        assertEquals(listOf(34L), got.map { it.fieldDefinitionId })
        assertEquals("남는 값", got.single().value)
    }

    /** 보내지 않은 필드는 빈 값과 같게 다룬다. */
    @Test
    fun collect_missingSubmissionIsBlank() {
        val fields = listOf(field(35, FieldType.TEXT.name))
        assertTrue(BridgeForm.collect(fields, emptyMap(), 1L).isEmpty())
    }

    @Test
    fun collect_joinsStructuredPartsWithConfiguredSeparator() {
        val fields = listOf(field(36, FieldType.BODY_SIZE.name))
        val got = BridgeForm.collect(
            fields,
            mapOf(36L to BridgeForm.Submitted.Parts(listOf("88", "60", "90"))),
            characterId = 2L
        )
        assertEquals("88-60-90", got.single().value)
    }

    /** 파트가 전부 비면 값이 없는 것이다 — `-​-` 같은 껍데기를 저장하지 않는다. */
    @Test
    fun collect_allBlankPartsIsNoValue() {
        val fields = listOf(field(37, FieldType.BODY_SIZE.name))
        val got = BridgeForm.collect(
            fields,
            mapOf(37L to BridgeForm.Submitted.Parts(listOf("", "  ", ""))),
            characterId = 2L
        )
        assertTrue(got.isEmpty())
    }

    /** 일부만 비면 자리를 지켜 저장한다 — 앱 폼과 같은 모양이다. */
    @Test
    fun collect_partiallyFilledPartsKeepSlots() {
        val fields = listOf(field(38, FieldType.BODY_SIZE.name))
        val got = BridgeForm.collect(
            fields,
            mapOf(38L to BridgeForm.Submitted.Parts(listOf("88", "", "90"))),
            characterId = 2L
        )
        assertEquals("88--90", got.single().value)
    }

    @Test
    fun collect_multiTextStoresTheLineAsGiven() {
        val fields = listOf(field(39, FieldType.MULTI_TEXT.name))
        val got = BridgeForm.collect(
            fields,
            mapOf(39L to BridgeForm.Submitted.Text(""""별칭, 둘", 본명""")),
            characterId = 3L
        )
        assertEquals(""""별칭, 둘", 본명""", got.single().value)
    }

    /** 구조화가 꺼진 필드에 파트가 와도 앱의 비구조화 경로와 같게 붙인다. */
    @Test
    fun collect_partsOnNonStructuredFieldUsesDashJoin() {
        val fields = listOf(field(40, FieldType.NUMBER.name))
        val got = BridgeForm.collect(
            fields,
            mapOf(40L to BridgeForm.Submitted.Parts(listOf("1", "2"))),
            characterId = 4L
        )
        assertEquals("1-2", got.single().value)
    }
}
