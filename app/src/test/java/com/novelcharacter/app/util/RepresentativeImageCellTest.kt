package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 엑셀 `대표이미지` 열의 왕복 계약(B-103 D8).
 *
 * 지키는 것 셋: **사람이 읽을 수 있게 쓴다**(파일명, 겹치면 전체 경로) ·
 * **다섯 단으로 수용한다**(거부가 아니라 교정 — 개발 의도 4번) ·
 * **못 찾아도 버리지 않는다**(경고 + 기존 값 유지).
 */
class RepresentativeImageCellTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"
    private val a = "$dir/char_aaa.jpg"
    private val b = "$dir/char_bbb.jpg"
    private val c = "$dir/char_ccc.jpg"
    private val paths = listOf(a, b, c)

    // ── 쓰기 ──

    @Test fun toCell_writesFileNameSoHumansCanEditIt() {
        assertEquals("char_bbb.jpg", RepresentativeImageCell.toCell(b, paths))
    }

    @Test fun toCell_isEmptyWhenNotPinned() {
        assertEquals("", RepresentativeImageCell.toCell(null, paths))
        assertEquals("", RepresentativeImageCell.toCell("", paths))
    }

    @Test fun toCell_isEmptyWhenPinnedFileIsNotInTheRow() {
        // 없는 이름을 내보내면 그것이 곧 다음 왕복의 '못 찾음' 경고가 된다.
        assertEquals("", RepresentativeImageCell.toCell("$dir/char_zzz.jpg", paths))
    }

    @Test fun toCell_fallsBackToFullPathWhenFileNamesCollide() {
        // 한 행 안에서 이름이 겹치면 이름만으로는 어느 장인지 정해지지 않는다.
        val dup = listOf("$dir/sub1/img.jpg", "$dir/sub2/img.jpg")
        assertEquals("$dir/sub2/img.jpg", RepresentativeImageCell.toCell("$dir/sub2/img.jpg", dup))
    }

    // ── 읽기: 다섯 단 ──

    @Test fun resolve_rung1_exactPath() {
        val r = RepresentativeImageCell.resolve(b, paths)
        assertEquals(RepresentativeImageCell.Resolution.Matched(b, RepresentativeImageCell.Rung.EXACT), r)
    }

    @Test fun resolve_rung2_remappedPath() {
        // 다른 기기에서 온 파일 — 그 기기의 경로가 셀에 실려 있다.
        val foreign = "/storage/emulated/0/other/char_bbb_original.jpg"
        val r = RepresentativeImageCell.resolve(foreign, paths, mapOf(foreign to b))
        assertEquals(
            RepresentativeImageCell.Resolution.Matched(b, RepresentativeImageCell.Rung.REMAPPED), r
        )
    }

    @Test fun resolve_rung3_uniqueFileName() {
        val r = RepresentativeImageCell.resolve("char_ccc.jpg", paths)
        assertEquals(
            RepresentativeImageCell.Resolution.Matched(c, RepresentativeImageCell.Rung.FILE_NAME), r
        )
    }

    @Test fun resolve_rung3_refusesAmbiguousFileName() {
        // 겹치는 이름은 확정하지 않는다 — 번호도 아니므로 '못 찾음'으로 떨어진다.
        val dup = listOf("$dir/sub1/img.jpg", "$dir/sub2/img.jpg")
        val r = RepresentativeImageCell.resolve("img.jpg", dup)
        assertTrue("겹치면 확정하지 않는다", r is RepresentativeImageCell.Resolution.Unresolved)
    }

    @Test fun resolve_rung4_oneBasedOrdinal() {
        assertEquals(
            RepresentativeImageCell.Resolution.Matched(a, RepresentativeImageCell.Rung.ORDINAL),
            RepresentativeImageCell.resolve("1", paths)
        )
        assertEquals(
            RepresentativeImageCell.Resolution.Matched(c, RepresentativeImageCell.Rung.ORDINAL),
            RepresentativeImageCell.resolve("3", paths)
        )
    }

    @Test fun resolve_rung4_rejectsOutOfRangeOrdinal() {
        // 0은 1부터 세는 규약에 없고, 4는 목록을 넘는다 — 둘 다 조용히 맞추지 않는다.
        assertTrue(RepresentativeImageCell.resolve("0", paths) is RepresentativeImageCell.Resolution.Unresolved)
        assertTrue(RepresentativeImageCell.resolve("4", paths) is RepresentativeImageCell.Resolution.Unresolved)
    }

    @Test fun resolve_rung5_keepsRawSoCallerCanWarnAndKeepOldValue() {
        val r = RepresentativeImageCell.resolve("전혀 다른 이름.jpg", paths)
        assertEquals(RepresentativeImageCell.Resolution.Unresolved("전혀 다른 이름.jpg"), r)
    }

    // ── 빈 칸·열 없음 ──

    @Test fun resolve_blankCellClearsThePin() {
        assertEquals(RepresentativeImageCell.Resolution.Cleared, RepresentativeImageCell.resolve("", paths))
        assertEquals(RepresentativeImageCell.Resolution.Cleared, RepresentativeImageCell.resolve("   ", paths))
    }

    @Test fun resolve_absentColumnLeavesValueAlone() {
        // 열이 없는 것과 빈 칸은 **다른 상태**다 — 열 없음은 "말하지 않았다"이고
        // 빈 칸은 "지정 없음으로 하라"이다. 섞으면 열 없는 파일이 지정을 전부 지운다.
        assertEquals(RepresentativeImageCell.Resolution.Absent, RepresentativeImageCell.resolve(null, paths))
    }

    @Test fun resolve_emptyRowNeverMatches() {
        assertTrue(
            RepresentativeImageCell.resolve("char_aaa.jpg", emptyList())
                is RepresentativeImageCell.Resolution.Unresolved
        )
        assertEquals(
            RepresentativeImageCell.Resolution.Cleared,
            RepresentativeImageCell.resolve("", emptyList())
        )
    }

    // ── 왕복 ──

    @Test fun roundTrip_survivesExportThenImport() {
        for (pinned in paths) {
            val cell = RepresentativeImageCell.toCell(pinned, paths)
            val back = RepresentativeImageCell.resolve(cell, paths)
            assertEquals(
                "왕복에서 $pinned 이(가) 살아남아야 한다",
                RepresentativeImageCell.Resolution.Matched(pinned, RepresentativeImageCell.Rung.FILE_NAME),
                back
            )
        }
    }

    @Test fun roundTrip_survivesWhenFileNamesCollide() {
        val dup = listOf("$dir/sub1/img.jpg", "$dir/sub2/img.jpg")
        for (pinned in dup) {
            val cell = RepresentativeImageCell.toCell(pinned, dup)
            assertEquals(
                RepresentativeImageCell.Resolution.Matched(pinned, RepresentativeImageCell.Rung.EXACT),
                RepresentativeImageCell.resolve(cell, dup)
            )
        }
    }
}
