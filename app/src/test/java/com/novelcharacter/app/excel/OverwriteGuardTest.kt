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

    // ── 부모 통삭제의 CASCADE 가드 (S22) ──

    private fun child(
        existing: Int,
        source: RestoreSource = RestoreSource.HAS_ROWS,
        missing: List<String> = emptyList(),
        notSelected: Boolean = false,
        keptOwner: Boolean = false
    ) = OverwriteGuard.CascadeChild("자식", existing, source, missing, notSelected, keptOwner)

    @Test
    fun `지금 없는 자식은 무엇이 빠져도 막지 않는다`() {
        val none = listOf(
            child(0, RestoreSource.MISSING),
            child(0, RestoreSource.EMPTY),
            child(0, missing = listOf("설정(JSON)")),
            child(0, notSelected = true),
            child(0, keptOwner = true)
        )
        assertTrue(OverwriteGuard.cascadeBlockers(none).isEmpty())
    }

    @Test
    fun `되살릴 수 있는 자식만 있으면 부모를 지운다`() {
        assertTrue(OverwriteGuard.cascadeBlockers(listOf(child(90), child(6))).isEmpty())
    }

    @Test
    fun `시트가 없거나 비었으면 부모를 막는다 - 유지했다고 말한 자식이 CASCADE로 사라지던 자리`() {
        // 3-ㄴ-4: 세력 소속 시트 내용만 지움 → "유지했습니다" 뒤에 캐릭터 통삭제가 90건을 지웠다.
        // 3-ㅅ-12: 대결 축 시트를 뺌 → 세계관 통삭제가 축 6개·판 131개를 지웠다.
        val blockers = OverwriteGuard.cascadeBlockers(
            listOf(child(90, RestoreSource.EMPTY), child(6, RestoreSource.MISSING), child(3))
        )
        assertEquals(listOf(90, 6), blockers.map { it.existing })
    }

    @Test
    fun `값이 든 열이 빠지면 행이 있어도 막는다`() {
        // 3-ㄴ-5: '설정(JSON)' 열을 지운 파일이 선택지·계산식·등급표를 전부 {}로 만들었다.
        val blockers = OverwriteGuard.cascadeBlockers(listOf(child(53, missing = listOf("설정(JSON)"))))
        assertEquals(1, blockers.size)
    }

    @Test
    fun `가져올 항목에서 빠진 자식과 임자를 남긴 값은 시트가 있어도 막는다`() {
        assertEquals(1, OverwriteGuard.cascadeBlockers(listOf(child(131, notSelected = true))).size)
        assertEquals(1, OverwriteGuard.cascadeBlockers(listOf(child(8, keptOwner = true))).size)
    }

    @Test
    fun `사유는 사용자가 할 일이 갈리게 적는다`() {
        val text = OverwriteGuard.describeBlockers(
            listOf(
                OverwriteGuard.CascadeChild("대결 축", 6, RestoreSource.MISSING),
                OverwriteGuard.CascadeChild("세력 소속", 90, RestoreSource.EMPTY),
                OverwriteGuard.CascadeChild("필드 정의", 53, RestoreSource.HAS_ROWS, listOf("설정(JSON)")),
                OverwriteGuard.CascadeChild("대결 기록", 131, RestoreSource.HAS_ROWS, notSelected = true),
                OverwriteGuard.CascadeChild("캐릭터 필드값", 8, RestoreSource.HAS_ROWS, keptOwner = true)
            )
        )
        assertEquals(
            "'대결 축' 6건(시트 없음), '세력 소속' 90건(데이터 행 없음), " +
                "'필드 정의' 53건('설정(JSON)' 열 없음), '대결 기록' 131건(가져올 항목에서 빠짐), " +
                "'캐릭터 필드값' 8건(임자를 남겨 둠)",
            text
        )
    }
}
