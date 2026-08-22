package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스피너가 **저장된 값을 표현할 수 있는가**의 계약.
 *
 * 못 그리는 값을 그냥 두면 위젯은 0번에 서고, 저장은 그 자리를 *사용자의 선택*으로 읽는다 —
 * 관계 유형이 첫 유형으로 바뀌고 사건 연결이 끊긴다. 화면에는 아무 흔적도 남지 않는다.
 */
class SpinnerChoiceTest {

    @Test
    fun `목록에 있으면 그 자리를 고른다`() {
        val r = SpinnerChoice.resolve(listOf("친구", "라이벌", "연인"), "라이벌")
        assertEquals(1, r.index)
        assertFalse(r.appended)
        assertEquals(listOf("친구", "라이벌", "연인"), r.items)
    }

    @Test
    fun `목록에 없으면 끼워 넣고 그 자리를 고른다`() {
        val r = SpinnerChoice.resolve(listOf("친구", "라이벌"), "숙적")
        assertTrue(r.appended)
        assertEquals(listOf("친구", "라이벌", "숙적"), r.items)
        assertEquals(2, r.index)
        assertEquals("숙적", r.items[r.index])
    }

    /** **건드리지 않으면 그대로** — 되짚어 읽은 값이 저장된 값과 같아야 한다. */
    @Test
    fun `끼워 넣은 자리를 그대로 읽으면 저장된 값이 나온다`() {
        for (stored in listOf("숙적", "", "친구")) {
            val options = listOf("친구", "라이벌")
            val r = SpinnerChoice.resolve(options, stored)
            assertEquals("stored=$stored", stored, r.items[r.index])
        }
    }

    @Test
    fun `저장된 것이 없으면 첫 자리다`() {
        val r = SpinnerChoice.resolve(listOf("없음", "친구"), null)
        assertEquals(0, r.index)
        assertFalse(r.appended)
    }

    @Test
    fun `목록도 저장된 값도 없으면 고를 자리가 없다`() {
        val r = SpinnerChoice.resolve(emptyList<String>(), null)
        assertEquals(-1, r.index)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `목록이 비어도 저장된 값은 표현된다`() {
        val r = SpinnerChoice.resolve(emptyList<String>(), "친구")
        assertEquals(listOf("친구"), r.items)
        assertEquals(0, r.index)
        assertTrue(r.appended)
    }

    /** 사건 스피너는 id 목록이고 맨 앞이 '없음'(null)이다 — 위치 산수를 없앤 그 축. */
    @Test
    fun `널을 첫 항목으로 둔 id 목록에서도 성립한다`() {
        val options = listOf<Long?>(null, 10L, 20L)
        assertEquals(0, SpinnerChoice.resolve(options, null).index)
        assertEquals(2, SpinnerChoice.resolve(options, 20L).index)

        // 이 작품 밖 사건 — 목록에 없지만 연결은 살아 있어야 한다.
        val outside = SpinnerChoice.resolve(options, 99L)
        assertTrue(outside.appended)
        assertEquals(99L, outside.items[outside.index])
        assertEquals(4, outside.items.size)
    }

    @Test
    fun `이미 있는 값을 다시 끼워 넣지 않는다`() {
        val r = SpinnerChoice.resolve(listOf("친구", "라이벌"), "친구")
        assertFalse(r.appended)
        assertEquals(2, r.items.size)
    }
}
