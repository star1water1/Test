package com.novelcharacter.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 코드 충돌 정책 (S-5). R-1: 기존 코드와 충돌하는 패키지 엔티티는 살아 있는 남을
 * 가로채지 않고 재발급하며, 재발급 건수는 고지 대상으로 계수된다.
 */
class WorldPackageCodesTest {

    @Test
    fun `자유로운 코드는 그대로 쓰고 세지 않는다`() {
        val reg = WorldPackageCodes.Registry(listOf("EXIST1"))
        assertEquals("PKG1", reg.claim("PKG1"))
        assertEquals(0, reg.reissuedCount)
    }

    @Test
    fun `기존 코드와 충돌하면 재발급하고 센다`() {
        val reg = WorldPackageCodes.Registry(listOf("EXIST1"))
        val issued = reg.claim("EXIST1")
        assertNotEquals("EXIST1", issued)
        assertTrue(issued.isNotBlank())
        assertEquals(1, reg.reissuedCount)
    }

    @Test
    fun `null이나 공란은 신규 발급이지 재발급이 아니다`() {
        // 코드가 아예 없던 구버전 행의 발급을 '충돌'로 세면 거짓 경고가 된다
        val reg = WorldPackageCodes.Registry(emptyList())
        assertTrue(reg.claim(null).isNotBlank())
        assertTrue(reg.claim("").isNotBlank())
        assertTrue(reg.claim("  ").isNotBlank())
        assertEquals(0, reg.reissuedCount)
    }

    @Test
    fun `한 번 발급된 코드는 같은 레지스트리 안에서 다시 나오지 않는다`() {
        val reg = WorldPackageCodes.Registry(emptyList())
        val first = reg.claim("SAME")
        val second = reg.claim("SAME")
        assertEquals("SAME", first)
        assertNotEquals(first, second)
        assertEquals(1, reg.reissuedCount)
    }

    @Test
    fun `기존 목록의 null은 무시된다`() {
        // code가 nullable인 테이블(사건·상태변화 등)의 기존 행 — null끼리는 충돌이 아니다
        val reg = WorldPackageCodes.Registry(listOf(null, null, "A"))
        assertEquals("B", reg.claim("B"))
        assertEquals(0, reg.reissuedCount)
    }

    @Test
    fun `uniqueName - 겹치지 않으면 그대로`() {
        assertEquals("아르카나", WorldPackageCodes.uniqueName(setOf("다른곳"), "아르카나"))
    }

    @Test
    fun `uniqueName - 겹치면 빈 번호부터 붙인다`() {
        assertEquals("아르카나 (2)", WorldPackageCodes.uniqueName(setOf("아르카나"), "아르카나"))
        assertEquals(
            "아르카나 (4)",
            WorldPackageCodes.uniqueName(setOf("아르카나", "아르카나 (2)", "아르카나 (3)"), "아르카나")
        )
    }
}
