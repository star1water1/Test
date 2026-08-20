package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 타입이 안 맞는 값을 고치러 가는 길 (B-198).
 *
 * **이 갈래가 순수로 내려온 이유가 곧 이 시험이 잠그는 것이다** — 인자 이름이 보내는 쪽과
 * 받는 쪽 두 자리에 걸리는데, 갈리면 **오류도 고지도 없이 아무 일이 일어나지 않는다.**
 * 화면 계층에서는 그 침묵을 어떤 자동 검사도 볼 수 없다.
 */
class FieldValueFixRouteTest {

    @Test
    fun `캐릭터는 편집으로 가고 그 칸까지 든다`() {
        val r = FieldValueFixRoute.of(FieldDefinition.ENTITY_CHARACTER, 7L, 33L, "힘")!!
        // 종전에는 상세(읽기 화면)로 가서 칸을 못 잡았다 — 같은 목록의 줄이 축마다 다르게
        // 동작하던 자리다(B-259).
        assertEquals(FieldValueFixRoute.Destination.CHARACTER_EDIT, r.destination)
        assertEquals(FieldValueFixRoute.ARG_CHARACTER_ID, r.ownerArg)
        assertEquals(7L, r.ownerId)
        assertEquals(33L, r.fieldId)
        assertEquals("힘", r.fieldName)
    }

    /**
     * **세 축이 같은 모양인가** — B-259가 없앤 것은 목적지 하나가 아니라 *축별 예외*다.
     * 예외가 하나라도 남으면 목록의 줄이 어떤 것은 칸까지 가고 어떤 것은 화면만 뜬다.
     */
    @Test
    fun `세 축 모두 칸 지정을 싣는다`() {
        for (type in listOf(
            FieldDefinition.ENTITY_CHARACTER,
            FieldDefinition.ENTITY_EVENT,
            FieldDefinition.ENTITY_NOVEL
        )) {
            val r = FieldValueFixRoute.of(type, 5L, 41L, "칸")!!
            assertEquals("종류 $type", 41L, r.fieldId)
            assertEquals("종류 $type", "칸", r.fieldName)
        }
    }

    /** 목적지도 축마다 달라야 한다 — 같으면 한 화면이 남의 축 요청을 받는다. */
    @Test
    fun `목적지는 축마다 다르다`() {
        val dests = listOf(
            FieldDefinition.ENTITY_CHARACTER,
            FieldDefinition.ENTITY_EVENT,
            FieldDefinition.ENTITY_NOVEL
        ).map { FieldValueFixRoute.of(it, 1L, 1L, "f")!!.destination }
        assertEquals(dests.size, dests.toSet().size)
    }

    @Test
    fun `사건은 연표로 가고 그 칸까지 든다`() {
        val r = FieldValueFixRoute.of(FieldDefinition.ENTITY_EVENT, 12L, 55L, "규모")!!
        assertEquals(FieldValueFixRoute.Destination.TIMELINE, r.destination)
        assertEquals(FieldValueFixRoute.ARG_FOCUS_EVENT_ID, r.ownerArg)
        assertEquals(12L, r.ownerId)
        assertEquals(55L, r.fieldId)
        assertEquals("규모", r.fieldName)
    }

    @Test
    fun `작품은 작품 목록으로 가고 그 칸까지 든다`() {
        val r = FieldValueFixRoute.of(FieldDefinition.ENTITY_NOVEL, 3L, 9L, "연재처")!!
        assertEquals(FieldValueFixRoute.Destination.NOVEL_LIST, r.destination)
        assertEquals(FieldValueFixRoute.ARG_FOCUS_NOVEL_ID, r.ownerArg)
        assertEquals(3L, r.ownerId)
        assertEquals(9L, r.fieldId)
        assertEquals("연재처", r.fieldName)
    }

    /** 세 축의 대상 인자 이름이 서로 달라야 한다 — 같으면 받는 화면이 남의 요청을 집는다. */
    @Test
    fun `대상 인자 이름은 목적지마다 다르다`() {
        val names = listOf(
            FieldDefinition.ENTITY_CHARACTER,
            FieldDefinition.ENTITY_EVENT,
            FieldDefinition.ENTITY_NOVEL
        ).map { FieldValueFixRoute.of(it, 1L, 1L, "f")!!.ownerArg }
        assertEquals(names.size, names.toSet().size)
    }

    /**
     * 모르는 종류는 갈 곳이 없다 — 아무 데나 보내면 **엉뚱한 화면이 뜨고**, 누른 사람은
     * 자기가 무엇을 눌렀는지 알 수 없게 된다.
     */
    @Test
    fun `모르는 종류는 갈 곳이 없다`() {
        assertNull(FieldValueFixRoute.of("universe", 1L, 1L, "f"))
        assertNull(FieldValueFixRoute.of("", 1L, 1L, "f"))
    }

    /**
     * 임자를 잃은 값도 목록에는 `#id`로 서는데(무음 유실 금지) **그 줄에는 갈 곳이 없다.**
     * id가 0 이하인 것이 그 표식이다.
     */
    @Test
    fun `id가 없는 대상은 갈 곳이 없다`() {
        assertNull(FieldValueFixRoute.of(FieldDefinition.ENTITY_EVENT, 0L, 5L, "f"))
        assertNull(FieldValueFixRoute.of(FieldDefinition.ENTITY_NOVEL, -1L, 5L, "f"))
    }

    /**
     * 칸 지정이 없는 요청도 목적지까지는 간다 — 창을 여는 것 자체가 이 길의 몸통이고,
     * 칸 지정은 그 위에 얹는 마찰 절약이다.
     */
    @Test
    fun `칸 지정이 없으면 이름도 비운다`() {
        val r = FieldValueFixRoute.of(FieldDefinition.ENTITY_EVENT, 12L, 0L, "규모")!!
        assertEquals(FieldValueFixRoute.Destination.TIMELINE, r.destination)
        assertEquals(0L, r.fieldId)
        // 잡을 칸이 없는데 이름만 실으면 받는 쪽이 *못 찾았다*고 말할 근거가 생긴다 —
        // 요청하지 않은 것을 실패로 알리는 꼴이라 이름도 함께 비운다.
        assertEquals("", r.fieldName)
        assertEquals("", FieldValueFixRoute.of(FieldDefinition.ENTITY_NOVEL, 3L, -2L, "연재처")!!.fieldName)
        // 캐릭터 축도 같은 규칙을 받는다(B-259로 예외가 없어졌다).
        val c = FieldValueFixRoute.of(FieldDefinition.ENTITY_CHARACTER, 7L, 0L, "힘")!!
        assertEquals(FieldValueFixRoute.Destination.CHARACTER_EDIT, c.destination)
        assertEquals(0L, c.fieldId)
        assertEquals("", c.fieldName)
    }
}
