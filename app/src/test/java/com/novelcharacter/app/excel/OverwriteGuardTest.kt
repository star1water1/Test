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
 *
 * **판정 입력은 물리 행 번호가 아니라 '내용 있는 행 존재'다.** 내보내기가 데이터 행에
 * 스타일을 입히므로, 엑셀에서 내용만 지운(Delete) 행이 스타일만 남은 물리 행으로 잔존한다 —
 * lastRowNum 기준 판정은 그런 사실상 빈 시트를 복원 재료로 오판해 가드가 뚫린다.
 */
class OverwriteGuardTest {

    @Test
    fun `시트가 없으면 MISSING이고 지우지 않는다`() {
        assertEquals(RestoreSource.MISSING, OverwriteGuard.classify(null))
        assertFalse(OverwriteGuard.canRestore(null))
    }

    @Test
    fun `내용 있는 행이 없으면 EMPTY이고 지우지 않는다`() {
        // 헤더뿐인 시트(내보내기가 만드는 빈 시트)와 내용만 지운 시트가 모두 이것이다.
        assertEquals(RestoreSource.EMPTY, OverwriteGuard.classify(false))
        assertFalse("빈 시트가 전부 삭제를 허가하면 안 된다", OverwriteGuard.canRestore(false))
    }

    @Test
    fun `내용 있는 행이 하나라도 있으면 HAS_ROWS이고 지운다`() {
        assertEquals(RestoreSource.HAS_ROWS, OverwriteGuard.classify(true))
        assertTrue(OverwriteGuard.canRestore(true))
    }

    @Test
    fun `MISSING과 EMPTY는 구분된다 — 사용자가 할 일이 다르다`() {
        // 둘 다 "지우지 않는다"이지만 안내 문구가 갈린다:
        // 시트가 없으면 '다시 내보내기', 비어 있으면 '그 시트에 행을 적기'.
        assertEquals(RestoreSource.MISSING, OverwriteGuard.classify(null))
        assertEquals(RestoreSource.EMPTY, OverwriteGuard.classify(false))
        assertFalse(OverwriteGuard.canRestore(null))
        assertFalse(OverwriteGuard.canRestore(false))
    }

    @Test
    fun `스타일만 남은 행은 데이터 행이 아니다 — 내용을 지운 시트는 EMPTY로 판정된다`() {
        // 물리 행은 있으나 셀 텍스트가 전부 빈 시트(엑셀에서 Delete로 내용만 지운 파일).
        // 종전 lastRowNum 기준 판정이 정확히 이 파일을 HAS_ROWS로 오판했다.
        val styledOnly = sequenceOf(
            sequenceOf("", "", ""),
            sequenceOf("", "")
        )
        assertFalse(OverwriteGuard.hasDataRow(styledOnly))
        assertEquals(RestoreSource.EMPTY, OverwriteGuard.classify(OverwriteGuard.hasDataRow(styledOnly)))
    }

    @Test
    fun `공백만 든 셀도 내용이 아니다`() {
        assertFalse(OverwriteGuard.hasDataRow(sequenceOf(sequenceOf("  ", "\t"))))
    }

    @Test
    fun `셀 하나라도 내용이 있으면 데이터 행이다`() {
        val rows = sequenceOf(
            sequenceOf("", "", ""),
            sequenceOf("", "홍길동")
        )
        assertTrue(OverwriteGuard.hasDataRow(rows))
    }

    @Test
    fun `첫 발견에서 멈춘다 — 뒤 행을 평가하지 않는다`() {
        // 대형 파일에서 스캔이 전 행을 만들지 않는 것이 이 API가 Sequence인 이유다.
        var evaluated = 0
        val rows = (1..1000).asSequence().map { i ->
            evaluated++
            sequenceOf("행$i")
        }
        assertTrue(OverwriteGuard.hasDataRow(rows))
        assertEquals("첫 행만 평가해야 한다", 1, evaluated)
    }

    @Test
    fun `행이 하나도 없으면 데이터 행 없음이다`() {
        assertFalse(OverwriteGuard.hasDataRow(emptySequence()))
    }
}
