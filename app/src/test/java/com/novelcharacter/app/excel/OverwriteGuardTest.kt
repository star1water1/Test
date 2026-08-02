package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 덮어쓰기 가드의 계약(B-88).
 *
 * **이 테스트가 지키는 것은 "빈 시트는 삭제 지시가 아니다"이다.** 내보내기가 빈 범주에도
 * 시트를 만들게 됐으므로(엑셀 편집 경로), 가드가 옛 규칙("시트가 있는가")으로 되돌아가면
 * **헤더만 있는 시트 하나가 그 종류를 통째로 지운다** — 휴지통을 거치지 않는 `deleteAll`이다.
 */
class OverwriteGuardTest {

    @Test
    fun `시트가 없으면 MISSING이고 지우지 않는다`() {
        assertEquals(RestoreSource.MISSING, OverwriteGuard.classify(null))
        assertFalse(OverwriteGuard.canRestore(null))
    }

    @Test
    fun `헤더만 있는 시트는 EMPTY이고 지우지 않는다`() {
        // lastRowNum 0 = 헤더(0행)뿐이거나 아예 빈 시트. 내보내기가 만드는 빈 시트가 이것이다.
        assertEquals(RestoreSource.EMPTY, OverwriteGuard.classify(0))
        assertFalse("빈 시트가 전부 삭제를 허가하면 안 된다", OverwriteGuard.canRestore(0))
    }

    @Test
    fun `데이터 행이 하나라도 있으면 HAS_ROWS이고 지운다`() {
        assertEquals(RestoreSource.HAS_ROWS, OverwriteGuard.classify(1))
        assertTrue(OverwriteGuard.canRestore(1))
        assertTrue(OverwriteGuard.canRestore(500))
    }

    @Test
    fun `경계는 1이다`() {
        // 0과 1 사이가 이 가드의 전부다 — 옮기면 둘 중 하나가 깨진다.
        assertFalse(OverwriteGuard.canRestore(0))
        assertTrue(OverwriteGuard.canRestore(1))
    }

    @Test
    fun `MISSING과 EMPTY는 구분된다 — 사용자가 할 일이 다르다`() {
        // 둘 다 "지우지 않는다"이지만 안내 문구가 갈린다:
        // 시트가 없으면 '다시 내보내기', 비어 있으면 '그 시트에 행을 적기'.
        assertEquals(RestoreSource.MISSING, OverwriteGuard.classify(null))
        assertEquals(RestoreSource.EMPTY, OverwriteGuard.classify(0))
        assertFalse(OverwriteGuard.canRestore(null))
        assertFalse(OverwriteGuard.canRestore(0))
    }

    @Test
    fun `음수 lastRowNum도 비어 있는 것으로 읽는다`() {
        // POI 구현에 따라 완전히 빈 시트가 -1을 내는 경우가 있다(계약은 0이지만 방어한다).
        assertEquals(RestoreSource.EMPTY, OverwriteGuard.classify(-1))
        assertFalse(OverwriteGuard.canRestore(-1))
    }
}
