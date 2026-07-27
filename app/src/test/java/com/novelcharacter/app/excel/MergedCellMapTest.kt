package com.novelcharacter.app.excel

import com.novelcharacter.app.excel.MergedCellMap.CellRef
import com.novelcharacter.app.excel.MergedCellMap.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 병합 셀 범위 조회 (B-7).
 * 피복 칸 → 좌상단 해석, 좌상단 자신은 피복이 아님, 범위 밖 null을 고정한다.
 */
class MergedCellMapTest {

    @Test
    fun `피복 칸은 좌상단으로 해석된다`() {
        // B2:D4 병합 (0-기준: 행1~3, 열1~3)
        val map = MergedCellMap(listOf(Region(1, 3, 1, 3)))
        assertEquals(CellRef(1, 1), map.topLeftOf(2, 2))
        assertEquals(CellRef(1, 1), map.topLeftOf(3, 3))
        assertEquals(CellRef(1, 1), map.topLeftOf(1, 3))
        assertTrue(map.isCoveredCell(2, 2))
    }

    @Test
    fun `좌상단 자신은 피복 칸이 아니다`() {
        // 좌상단을 피복으로 치면 값이 정말 빈 병합 범위에서 자기 자신을 다시 읽는 무한 순환 소지
        val map = MergedCellMap(listOf(Region(1, 3, 1, 3)))
        assertEquals(CellRef(1, 1), map.topLeftOf(1, 1))
        assertFalse(map.isCoveredCell(1, 1))
    }

    @Test
    fun `범위 밖은 null`() {
        val map = MergedCellMap(listOf(Region(1, 3, 1, 3)))
        assertNull(map.topLeftOf(0, 0))
        assertNull(map.topLeftOf(4, 2))
        assertNull(map.topLeftOf(2, 4))
        assertFalse(map.isCoveredCell(0, 1))
    }

    @Test
    fun `여러 범위가 독립적으로 해석된다`() {
        val map = MergedCellMap(
            listOf(Region(0, 0, 0, 5), Region(2, 4, 1, 1), Region(2, 2, 3, 4))
        )
        assertEquals(CellRef(0, 0), map.topLeftOf(0, 4))
        assertEquals(CellRef(2, 1), map.topLeftOf(4, 1))
        assertEquals(CellRef(2, 3), map.topLeftOf(2, 4))
        assertNull(map.topLeftOf(3, 3))
    }

    @Test
    fun `뒤집힌 범위는 무시된다`() {
        // 방어: last < first 인 비정상 범위 — 파일 손상/외부 도구 산출물
        val map = MergedCellMap(listOf(Region(3, 1, 1, 3), Region(1, 3, 3, 1)))
        assertTrue(map.isEmpty)
        assertNull(map.topLeftOf(2, 2))
    }

    @Test
    fun `범위가 없으면 isEmpty`() {
        assertTrue(MergedCellMap(emptyList()).isEmpty)
        assertFalse(MergedCellMap(listOf(Region(0, 1, 0, 1))).isEmpty)
    }
}
