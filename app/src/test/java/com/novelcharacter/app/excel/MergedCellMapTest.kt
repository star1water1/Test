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

    // ── B-219 ②: 범위를 행 단위로 펼치지 않는다 ──

    @Test
    fun `전열 병합은 행 수가 아니라 병합 개수만큼만 든다`() {
        // 엑셀에서 열 머리를 눌러 병합하면 `A1:A1048576` 한 범위가 생긴다. 행마다 펼치던
        // 종전 구현은 여기서 **엔트리 백만 개 + 리스트 백만 개**를 만들었다 — 몇 KB짜리 파일
        // 하나가 가져오기를 통째로 죽이는 자리다.
        //
        // **재는 것이 시간이 아니라 힙인 이유:** 종전 구현의 실측은 이 입력에서 **366MB / 0.9초**다.
        // 시간으로 잠그면 기계가 빠를수록 통과해 버리고(실제로 이 저장소의 하네스는 힙이 3.4GB라
        // *옛 구현이 초록이었다* — 처음 쓴 시험이 그래서 아무것도 안 잠갔다), 힙 증가량은
        // 할당량이라 기계가 달라도 같다. 문턱 32MB는 실측 366MB의 11분의 1이다.
        val sheetLastRow = 1_048_575  // 0-기준
        val regions = (0 until 8).map { col -> Region(0, sheetLastRow, col, col) }

        val before = usedHeapAfterGc()
        val map = MergedCellMap(regions)
        // 답도 맞아야 한다 — 적게 쓰기만 하고 틀리면 더 나쁘다.
        assertFalse(map.isEmpty)
        assertEquals(CellRef(0, 3), map.topLeftOf(999_999, 3))
        assertTrue(map.isCoveredCell(999_999, 3))
        assertFalse(map.isCoveredCell(0, 3))     // 좌상단 자신
        assertNull(map.topLeftOf(999_999, 8))    // 병합되지 않은 열
        val grew = usedHeapAfterGc() - before
        // map을 살려 둔 채로 재야 한다 — GC가 걷어 가면 0이 나와 무엇도 못 잠근다.
        assertFalse(map.isEmpty)

        assertTrue("힙 증가 ${grew / 1024 / 1024}MB", grew < 32L * 1024 * 1024)
    }

    private fun usedHeapAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { rt.gc(); Thread.sleep(20) }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun `무작위 범위에서 펼친 정답지와 답이 같다`() {
        // 뼈대는 **옛 구현(행 단위 전개)을 정답지로 다시 적은 대조**다 — 자료 구조만 바꾼
        // 수리라 "같은 답인가"가 유일한 계약이고, 그것을 손으로 고른 몇 칸으로는 못 잠근다.
        val rnd = java.util.Random(20260816L)
        var covered = 0
        repeat(300) {
            val regions = (0 until rnd.nextInt(12)).map {
                val r0 = rnd.nextInt(40); val c0 = rnd.nextInt(12)
                Region(r0, r0 + rnd.nextInt(6), c0, c0 + rnd.nextInt(5))
            }
            val map = MergedCellMap(regions)
            // 정답지: 유효 범위를 **입력 순서대로** 훑어 처음 덮는 것의 좌상단.
            val valid = regions.filter { it.lastRow >= it.firstRow && it.lastColumn >= it.firstColumn }
            for (row in 0..46) {
                for (col in 0..17) {
                    val expected = valid.firstOrNull {
                        row in it.firstRow..it.lastRow && col in it.firstColumn..it.lastColumn
                    }?.let { CellRef(it.firstRow, it.firstColumn) }
                    assertEquals("row=$row col=$col regions=$regions", expected, map.topLeftOf(row, col))
                    if (expected != null) covered++
                }
            }
        }
        // 대조가 헛돌지 않았다는 것을 시험이 스스로 단언한다 — 전부 null이면 무엇도 못 잠근다.
        assertTrue("덮인 칸 $covered", covered > 5_000)
    }

    @Test
    fun `한 칸을 여러 범위가 덮으면 입력 순서가 이긴다`() {
        // 엑셀은 겹치는 병합을 만들지 않지만 외부 도구·손상 파일은 만든다. 정렬로 답이
        // 바뀌면 같은 파일이 판올림 전후로 다른 값을 읽는다.
        val later = Region(0, 5, 0, 5)
        val earlier = Region(2, 3, 2, 3)
        assertEquals(CellRef(2, 2), MergedCellMap(listOf(earlier, later)).topLeftOf(3, 3))
        assertEquals(CellRef(0, 0), MergedCellMap(listOf(later, earlier)).topLeftOf(3, 3))
    }

    @Test
    fun `행을 오가도 캐시가 답을 섞지 않는다`() {
        // 한 행의 후보를 캐시하므로, 행이 왔다 갔다 할 때 옛 행의 답이 새 행에 새면 안 된다.
        val map = MergedCellMap(listOf(Region(0, 1, 0, 1), Region(4, 6, 3, 4)))
        repeat(3) {
            assertEquals(CellRef(0, 0), map.topLeftOf(1, 1))
            assertEquals(CellRef(4, 3), map.topLeftOf(5, 4))
            assertNull(map.topLeftOf(2, 1))
            assertNull(map.topLeftOf(5, 1))
        }
    }
}
