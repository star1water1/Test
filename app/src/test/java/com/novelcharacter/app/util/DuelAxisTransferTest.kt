package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.FieldFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 다른 세계관의 대결 축 불러오기 (B-116).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 넷이고, 앞의 둘은 서로 반대 방향의 사고를 막는다.**
 *
 * 1. **경계를 넘는 값은 전부 새로 난다** — 특히 `code`. 축 code는 **전역 유니크**라 그대로
 *    복사하면 삽입이 죽고, 죽지 않는 경로에서는 `duelGrade.axisCode`를 든 남의 필드 config가
 *    **이 축을 정확히 찾는다.** 오배정은 생략보다 나쁘다(R-1 · R-35). `isBasisAxis`도 같은
 *    부류다 — 켜진 채 들어오면 `saveAxis`가 **받는 세계관의 기존 기준을 조용히 내린다.**
 * 2. **그런데 필드 연결은 그대로 온다.** (**반대편이다** — 1만 있으면 *"세계관을 넘으니 다
 *    지운다"*는 구현이 통과하는데, 그러면 이 기능이 하려던 일 자체가 없어진다. 축을 옮기는
 *    까닭이 *'강함' 축의 구성을 다시 만들지 않으려는 것*이고, 연결이 그 구성의 전부다.
 *    없는 키는 **지우는 것이 아니라 세어서 알린다** — 나중에 같은 키의 필드를 만들면 이어진다.)
 * 3. **후보 필터는 키만 온다.** `fieldId`는 남의 세계관 것인데 `DuelCandidateFilter.resolve`에
 *    **키가 없을 때 id로 찾는 갈래**가 있어, 그 id가 받는 쪽에서 우연히 실재하면 **엉뚱한
 *    필드로 정확히 해석된다.** 조용해서 화면에서 원인이 안 보이는 부류다.
 * 4. **이름이 겹치면 넣지 않는다.** 유니크가 `(세계관, 대상, 이름)`이라 넣으면 삽입이 죽고,
 *    덮어쓰면 **받는 쪽에 쌓인 판이 남의 축 정의를 뒤집어쓴다.** 대상이 다르면 겹치지
 *    않는다는 것도 함께 잰다 — 캐릭터 축 '아름다움'과 이미지 축 '아름다움'은 다른 축이다.
 */
class DuelAxisTransferTest {

    private fun axis(
        id: Long = 7L,
        universeId: Long = 1L,
        name: String = "강함",
        targetType: String = DuelAxis.TARGET_CHARACTER,
        influences: String = "[]",
        outcomes: String = "[]",
        profiles: String = "[]",
        filters: String? = null,
        basis: Boolean = false,
        code: String = "SRC-CODE"
    ) = DuelAxis(
        id = id,
        universeId = universeId,
        name = name,
        targetType = targetType,
        displayOrder = 3,
        createdAt = 1_000L,
        influenceFieldKeys = influences,
        outcomeFieldKeys = outcomes,
        profileFieldKeys = profiles,
        candidateFiltersJson = filters,
        isBasisAxis = basis,
        code = code
    )

    private fun filtersJson(vararg filters: FieldFilter): String =
        DuelCandidateFilter.encode(filters.toList())

    // ── 1. 경계를 넘는 값은 새로 난다 ──

    @Test
    fun `옮긴 축은 새 code와 새 세계관을 갖는다`() {
        val moved = DuelAxisTransfer.transfer(
            source = axis(),
            targetUniverseId = 42L,
            displayOrder = 9,
            code = "NEW-CODE",
            createdAt = 2_000L
        )
        assertEquals(0L, moved.id)
        assertEquals(42L, moved.universeId)
        assertEquals(9, moved.displayOrder)
        assertEquals("NEW-CODE", moved.code)
        assertNotEquals("SRC-CODE", moved.code)
        assertEquals(2_000L, moved.createdAt)
        // 이름과 대상은 그대로다 — 옮기는 것은 *같은 축*이다.
        assertEquals("강함", moved.name)
        assertEquals(DuelAxis.TARGET_CHARACTER, moved.targetType)
    }

    @Test
    fun `기준 축 표식은 따라오지 않는다`() {
        val moved = DuelAxisTransfer.transfer(
            source = axis(targetType = DuelAxis.TARGET_IMAGE, basis = true),
            targetUniverseId = 42L, displayOrder = 0, code = "N", createdAt = 1L
        )
        assertFalse(moved.isBasisAxis)
        assertFalse(moved.isImageBasis)
    }

    // ── 2. 반대편: 연결은 그대로 온다 ──

    @Test
    fun `필드 연결 셋은 글자 그대로 따라온다`() {
        val src = axis(
            influences = DuelFieldLinks.encode(
                listOf(DuelFieldLinks.Link("마력량"), DuelFieldLinks.Link("나이", higherWins = false))
            ),
            outcomes = DuelFieldLinks.encode(listOf(DuelFieldLinks.Link("강함"))),
            profiles = DuelFieldLinks.encode(listOf(DuelFieldLinks.Link("sys:faction")))
        )
        val moved = DuelAxisTransfer.transfer(src, 42L, 0, "N", 1L)
        assertEquals(src.influenceFieldKeys, moved.influenceFieldKeys)
        assertEquals(src.outcomeFieldKeys, moved.outcomeFieldKeys)
        assertEquals(src.profileFieldKeys, moved.profileFieldKeys)
        // 순위(차례)와 방향 표식까지 살아 있어야 옮긴 축이 원본과 같은 답을 낸다.
        val links = moved.fieldLinks
        assertEquals(listOf("마력량", "나이"), links.influences.map { it.key })
        assertFalse(links.influences[1].higherWins)
    }

    @Test
    fun `없는 키는 세지만 지우지는 않는다`() {
        val src = axis(
            influences = DuelFieldLinks.encode(
                listOf(DuelFieldLinks.Link("마력량"), DuelFieldLinks.Link("검술"))
            ),
            profiles = DuelFieldLinks.encode(listOf(DuelFieldLinks.Link("sys:faction")))
        )
        val missing = DuelAxisTransfer.missingKeys(src, setOf("마력량"))
        // 시스템 열은 세계관을 타지 않으므로 결손이 아니다.
        assertEquals(listOf("검술"), missing)
        // 그래도 연결에서 빠지지는 않는다 — 나중에 '검술'을 만들면 되살아난다.
        val moved = DuelAxisTransfer.transfer(src, 42L, 0, "N", 1L)
        assertEquals(listOf("마력량", "검술"), moved.fieldLinks.influences.map { it.key })
    }

    @Test
    fun `모르는 sys 키는 결손으로 세지 않는다`() {
        // `sys:anothername`(밑줄 빠짐)은 *이 세계관에 없는 것*이 아니라 *어디에도 없는 것*이고
        // 원본에서도 이미 죽어 있었다. 함께 세면 옮기기가 고칠 수 있는 결손과 섞인다.
        val src = axis(profiles = DuelFieldLinks.encode(listOf(DuelFieldLinks.Link("sys:anothername"))))
        assertEquals(emptyList<String>(), DuelAxisTransfer.missingKeys(src, emptySet()))
    }

    @Test
    fun `같은 키가 두 자리에 걸려 있어도 한 번만 센다`() {
        val src = axis(
            influences = DuelFieldLinks.encode(listOf(DuelFieldLinks.Link("검술"))),
            profiles = DuelFieldLinks.encode(listOf(DuelFieldLinks.Link("검술")))
        )
        assertEquals(listOf("검술"), DuelAxisTransfer.missingKeys(src, emptySet()))
    }

    // ── 3. 후보 필터는 키만 온다 ──

    @Test
    fun `옮긴 필터는 남의 필드 id를 들고 오지 않는다`() {
        val src = axis(
            filters = filtersJson(
                FieldFilter(fieldId = 55L, fieldName = "성별", values = listOf("여성"), fieldKey = "성별")
            )
        )
        val moved = DuelAxisTransfer.transfer(src, 42L, 0, "N", 1L)
        val carried = DuelCandidateFilter.parse(moved.candidateFiltersJson)
        assertEquals(1, carried.size)
        assertEquals(0L, carried[0].fieldId)
        assertEquals("성별", carried[0].fieldKey)
        assertEquals(listOf("여성"), carried[0].values)
    }

    @Test
    fun `id만 든 필터는 옮겨도 남의 필드를 집지 않는다`() {
        // 손으로 만든 엑셀 파일에서 오는 부류. 받는 세계관에 id 55의 필드가 실재하면
        // 종전 사다리는 그것을 **정확히** 집었다 — 그 길을 막는 것이 이 시험의 요점이다.
        val src = axis(
            filters = filtersJson(
                FieldFilter(fieldId = 55L, fieldName = "성별", values = listOf("여성"), fieldKey = null)
            )
        )
        val moved = DuelAxisTransfer.transfer(src, 42L, 0, "N", 1L)
        val carried = DuelCandidateFilter.parse(moved.candidateFiltersJson)
        val stranger = com.novelcharacter.app.data.model.FieldDefinition(
            id = 55L, universeId = 42L, key = "머리색", name = "머리색", type = "TEXT"
        )
        val resolved = DuelCandidateFilter.resolve(carried, listOf(stranger))
        assertTrue("남의 id로 해석되면 안 된다", resolved.single().unresolved)
        assertNull(resolved.single().field)
    }

    @Test
    fun `키 없는 필터는 결손으로 세어 이름으로 말한다`() {
        val src = axis(
            filters = filtersJson(
                FieldFilter(fieldId = 55L, fieldName = "성별", values = listOf("여성"), fieldKey = null)
            )
        )
        assertEquals(listOf("성별"), DuelAxisTransfer.missingKeys(src, setOf("성별")))
    }

    @Test
    fun `필터의 없는 키도 결손으로 센다`() {
        // 연결만 보면 *"필드는 다 있는데 후보가 0"*인 축이 조용히 들어온다.
        val src = axis(
            filters = filtersJson(
                FieldFilter(fieldId = 0L, fieldName = "출신", values = listOf("북부"), fieldKey = "출신")
            )
        )
        assertEquals(listOf("출신"), DuelAxisTransfer.missingKeys(src, setOf("성별")))
    }

    /**
     * **연결과 필터의 잣대가 갈리는 자리** — 같은 오타 키가 한쪽에서는 결손이 아니고
     * 다른 쪽에서는 결손이다. 무게가 다르기 때문이다: 연결은 카드에서 한 줄이 비고,
     * **필터는 후보를 0으로 닫아 축이 통째로 못 쓰게 된다.**
     */
    @Test
    fun `모르는 sys 키가 필터에 있으면 결손으로 센다`() {
        val src = axis(
            profiles = DuelFieldLinks.encode(listOf(DuelFieldLinks.Link("sys:facton"))),
            filters = filtersJson(
                FieldFilter(fieldId = 0L, fieldName = "세력", values = listOf("기사단"), fieldKey = "sys:facton")
            )
        )
        // 연결 쪽은 봐준다(어디에도 없는 것이지 *이 세계관에 없는 것*이 아니다).
        // 필터 쪽은 못 봐준다 — 그 한 줄이 후보를 0으로 만든다.
        assertEquals(listOf("sys:facton"), DuelAxisTransfer.missingKeys(src, emptySet()))
        // 아는 sys 키는 어느 쪽에서도 결손이 아니다 — 세계관을 타지 않는다.
        val ok = axis(
            filters = filtersJson(
                FieldFilter(fieldId = 0L, fieldName = "세력", values = listOf("기사단"), fieldKey = "sys:faction")
            )
        )
        assertEquals(emptyList<String>(), DuelAxisTransfer.missingKeys(ok, emptySet()))
    }

    @Test
    fun `조건이 없으면 저장 표기는 null 하나다`() {
        assertNull(DuelAxisTransfer.transfer(axis(filters = null), 42L, 0, "N", 1L).candidateFiltersJson)
        assertNull(DuelAxisTransfer.transfer(axis(filters = "{}"), 42L, 0, "N", 1L).candidateFiltersJson)
    }

    // ── 4. 이름 겹침 ──

    @Test
    fun `같은 대상의 같은 이름은 가져올 수 없다`() {
        val plans = DuelAxisTransfer.plan(
            sources = listOf(axis(name = "강함"), axis(id = 8, name = "아름다움")),
            targetAxes = listOf(axis(id = 99, universeId = 42L, name = "강함", code = "X")),
            targetFieldKeys = emptySet()
        )
        assertEquals(listOf(true, false), plans.map { it.duplicateName })
        assertEquals(listOf(false, true), plans.map { it.importable })
    }

    @Test
    fun `대상이 다르면 같은 이름이라도 겹치지 않는다`() {
        val plans = DuelAxisTransfer.plan(
            sources = listOf(axis(name = "아름다움", targetType = DuelAxis.TARGET_IMAGE)),
            targetAxes = listOf(
                axis(id = 99, universeId = 42L, name = "아름다움", code = "X")  // 캐릭터 축
            ),
            targetFieldKeys = emptySet()
        )
        assertFalse(plans.single().duplicateName)
        assertTrue(plans.single().importable)
    }

    @Test
    fun `계획은 넘긴 차례를 지킨다`() {
        val sources = listOf(axis(id = 1, name = "다"), axis(id = 2, name = "가"), axis(id = 3, name = "나"))
        val plans = DuelAxisTransfer.plan(sources, emptyList(), emptySet())
        assertEquals(listOf("다", "가", "나"), plans.map { it.source.name })
    }
}
