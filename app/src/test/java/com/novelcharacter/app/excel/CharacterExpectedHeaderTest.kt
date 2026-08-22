package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.util.CharacterFieldColumns
import com.novelcharacter.app.util.ColumnFieldOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 시트의 **무편집 왕복 무결** — 내보내기가 적은 열 머리를 가져오기가 같은 필드로 되짚는가.
 *
 * 형제 시트(연표·작품)는 [EntityFieldHeaders]가 이 계약을 이미 들고 있었고 그 시험도 있었다.
 * 캐릭터 시트만 머리를 짓는 식이 `characterSpec` 안에 인라인으로 있어, 읽는 쪽이 그 식을 모른 채
 * 정규식으로 되짚었다 — 그 어긋남이 값을 **남의 필드로** 보냈다.
 */
class CharacterExpectedHeaderTest {

    private val MULTI = EntityFieldHeaders.MULTI_SUFFIX

    private fun field(
        id: Long,
        key: String,
        name: String,
        type: FieldType = FieldType.TEXT
    ) = FieldDefinition(id = id, universeId = 1L, key = key, name = name, type = type.name)

    /** 내보내기 머리 → 가져오기 해석. 실제 두 경로가 부르는 그 조합이다. */
    private fun roundTrip(fields: List<FieldDefinition>): Map<String, ColumnFieldOutcome> {
        val columns = CharacterFieldHeaders.plan(fields).columns
        val headers = columns.mapIndexed { i, (_, header) -> i to header }.toMap()
        val out = CharacterFieldColumns.plan(
            headers,
            fields,
            CharacterFieldHeaders.expectedHeaders(fields),
            fixedColIndices = emptySet(),
            fixedHeaders = CHARACTER_FIXED_HEADERS,
            hasUniverse = true,
            multiSuffix = MULTI
        )
        return columns.mapIndexed { i, (_, header) -> header to out.getValue(i) }.toMap()
    }

    private fun matched(outcome: ColumnFieldOutcome): FieldDefinition =
        (outcome as ColumnFieldOutcome.Matched).field

    // ── ① 이름이 괄호로 끝나는 필드 — 이 결함이 실제로 값을 뒤바꾸던 자리 ──────────

    /**
     * `총합(마력)`이라는 **이름**의 필드와, 키가 `마력`인 다른 필드가 한 목록에 있다.
     * 병기 머리 정규식만으로는 앞 열이 뒤 필드로 확정된다 — 한 글자도 안 고친 왕복에서.
     */
    @Test
    fun `이름이 괄호로 끝나도 그 열은 제 필드로 돌아온다`() {
        val owner = field(1L, "total", "총합(마력)")
        val decoy = field(2L, "마력", "마나")
        val trip = roundTrip(listOf(owner, decoy))
        assertEquals(owner, matched(trip.getValue("총합(마력)")))
        assertEquals(decoy, matched(trip.getValue("마나")))
    }

    /** 그 필드가 열을 못 받고 버려지지도 않는다 — 단사 보장에 걸리던 자리다. */
    @Test
    fun `괄호 이름 필드가 중복으로 버려지지 않는다`() {
        val owner = field(1L, "total", "총합(마력)")
        val decoy = field(2L, "마력", "마나")
        val trip = roundTrip(listOf(owner, decoy))
        assertTrue(trip.values.none { it is ColumnFieldOutcome.Duplicate })
    }

    /** 앱이 스스로 심는 프리셋에도 이 모양이 있다(`생일(월/일)`). */
    @Test
    fun `프리셋의 괄호 이름도 안전하다`() {
        val birth = field(1L, "birthday", "생일(월/일)")
        val month = field(2L, "월/일", "표기")
        val trip = roundTrip(listOf(birth, month))
        assertEquals(birth, matched(trip.getValue("생일(월/일)")))
    }

    // ── ② 병기·접미사가 붙은 머리도 그대로 돌아온다 ───────────────────────────

    @Test
    fun `동명 필드의 병기 머리가 각자 제 필드로 돌아온다`() {
        val a = field(1L, "hairA", "머리색")
        val b = field(2L, "hairB", "머리색")
        val trip = roundTrip(listOf(a, b))
        assertEquals(a, matched(trip.getValue("머리색(hairA)")))
        assertEquals(b, matched(trip.getValue("머리색(hairB)")))
    }

    @Test
    fun `고정 열과 겹치는 이름은 병기되고 그대로 돌아온다`() {
        val memo = field(1L, "memo2", "메모")
        val trip = roundTrip(listOf(memo))
        assertEquals(memo, matched(trip.getValue("메모(memo2)")))
    }

    @Test
    fun `쉼표 접미사가 붙은 머리가 그대로 돌아온다`() {
        val tags = field(1L, "traits", "특성", FieldType.MULTI_TEXT)
        val trip = roundTrip(listOf(tags))
        assertEquals(tags, matched(trip.getValue("특성$MULTI")))
    }

    /** 접미사가 붙기 전(B-38 이전)에 내보낸 파일의 머리도 받는다 — 옛 머리 폴백. */
    @Test
    fun `접미사 없는 옛 머리도 같은 필드로 돌아온다`() {
        val tags = field(1L, "traits", "특성", FieldType.MULTI_TEXT)
        val expected = CharacterFieldHeaders.expectedHeaders(listOf(tags))
        assertEquals(tags, expected["특성"])
        assertEquals(tags, expected["특성$MULTI"])
    }

    /**
     * 이름 자체가 `X (쉼표 구분)`인 필드가 있으면 **그 필드가 자기 머리를 갖는다** —
     * 옛 머리 폴백은 남은 자리에만 든다(형제 시트와 같은 순서).
     */
    @Test
    fun `이름이 접미사로 끝나는 필드가 옛 머리 폴백보다 앞선다`() {
        val literal = field(1L, "lit", "특성$MULTI")
        val multi = field(2L, "traits", "특성", FieldType.MULTI_TEXT)
        val expected = CharacterFieldHeaders.expectedHeaders(listOf(literal, multi))
        assertEquals(literal, expected["특성$MULTI"])
    }

    // ── ③ 머리가 겹치는 두 필드 — 뒤 필드는 오버플로 몫이다 ────────────────────

    /**
     * 고정 열과 겹쳐 `메모(memo)`로 병기된 필드와, **이름 자체가** `메모(memo)`인 필드.
     * 종전에는 같은 머리의 열이 두 개 그려졌고 가져오기가 뒤 열을 버렸다 — 그 필드의 값은
     * 내보내도 결코 되돌아오지 못했다.
     */
    @Test
    fun `머리가 겹치면 열은 하나만 그리고 나머지는 오버플로 몫이다`() {
        val collided = field(1L, "memo", "메모")
        val literal = field(2L, "other", "메모(memo)")
        val plan = CharacterFieldHeaders.plan(listOf(collided, literal))
        assertEquals(1, plan.columns.size)
        assertEquals(collided, plan.columns.single().first)
        assertTrue(collided.id in plan.coveredFieldIds)
        assertFalse("열을 못 받은 필드는 covered가 아니다 — 아니면 오버플로에서도 빠진다",
            literal.id in plan.coveredFieldIds)
    }

    @Test
    fun `머리가 겹치지 않으면 모든 필드가 열을 받는다`() {
        val fields = listOf(field(1L, "a", "가"), field(2L, "b", "나"), field(3L, "c", "다"))
        val plan = CharacterFieldHeaders.plan(fields)
        assertEquals(3, plan.columns.size)
        assertEquals(fields.map { it.id }.toSet(), plan.coveredFieldIds)
    }

    // ── ④ 열 머리와 셀이 같은 목록에서 나온다 ────────────────────────────────

    /**
     * `characterSpec`(머리)과 `ExcelExporter.exportCharacterSheet`(셀)이 **같은 계획**을 돈다.
     * 어긋나면 그 행부터 모든 값이 한 칸씩 밀린다 — 이 파일이 막는 어느 결함보다 나쁘다.
     */
    @Test
    fun `스펙의 동적 열이 계획의 열과 글자까지 같다`() {
        val fields = listOf(
            field(1L, "memo", "메모"),
            field(2L, "other", "메모(memo)"),
            field(3L, "traits", "특성", FieldType.MULTI_TEXT)
        )
        val planned = CharacterFieldHeaders.plan(fields).columns.map { it.second }
        val specHeaders = characterSpec(fields, emptyList()).columns.map { it.header }
        // 고정 열을 걷어내고 남는 것이 동적 열이다.
        val dynamic = specHeaders.filter { it !in CHARACTER_FIXED_HEADERS }
        assertEquals(planned, dynamic)
    }
}
