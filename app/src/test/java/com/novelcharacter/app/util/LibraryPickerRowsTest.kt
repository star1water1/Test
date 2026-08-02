package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 라이브러리 피커 선별 규칙의 계약.
 *
 * **이 테스트가 있는 이유는 화면이 검사 밖이기 때문이다.** 피커 시트는 `ui/`에 있어
 * 실클래스패스 프로브가 열지 않는다 — 거기 규칙을 두면 CI 전까지 아무도 못 본다.
 * 특히 [LibraryPickerRows.factsOf]의 배정→상태 매핑은 틀려도 **오류처럼 보이지 않는다**
 * ('아직 안 쓴 것만' 칩이 전부 보여 주거나 아무것도 안 보여 줄 뿐이다).
 */
class LibraryPickerRowsTest {

    private fun row(
        path: String,
        tags: List<String> = emptyList(),
        owners: List<String> = emptyList(),
        kinds: Set<ImageFilterHelper.OwnerKind> = emptySet(),
        link: String? = null,
        importedAt: Long = 0L
    ) = LibraryPickerRow(path, tags, owners, kinds, link, importedAt)

    // ── 배정 여부 → 상태 (칩이 여기 걸린다) ──────────────────────────────

    @Test
    fun `소유자가 없으면 미배정이다`() {
        val r = row("/a.png")
        assertTrue(!r.isAssigned)
        assertEquals(ImageFilterHelper.StatusKind.UNASSIGNED, LibraryPickerRows.factsOf(r).status)
    }

    @Test
    fun `소유자가 있으면 참조됨이다`() {
        val r = row("/a.png", owners = listOf("홍길동"))
        assertTrue(r.isAssigned)
        assertEquals(ImageFilterHelper.StatusKind.REFERENCED, LibraryPickerRows.factsOf(r).status)
    }

    @Test
    fun `미배정만 칩이 배정된 것을 거른다`() {
        val rows = listOf(
            row("/free.png"),
            row("/used.png", owners = listOf("홍길동"))
        )
        val onlyUnassigned = LibraryPickerRows.visible(
            rows,
            ImageFilterHelper.Criteria(base = ImageFilterHelper.BaseFilter.UNASSIGNED),
            emptySet()
        )
        assertEquals(listOf("/free.png"), onlyUnassigned.map { it.path })
    }

    @Test
    fun `칩을 끄면 배정된 것도 보인다`() {
        // 사용자 판정(2026.08.02): "보이되 표시한다" — 감추면 같은 삽화를 둘이 쓰는 경우가 막힌다.
        val rows = listOf(row("/free.png"), row("/used.png", owners = listOf("홍길동")))
        val all = LibraryPickerRows.visible(rows, ImageFilterHelper.Criteria(), emptySet())
        assertEquals(2, all.size)
    }

    // ── 검색 (이미지 탭과 같은 규칙을 쓰는지) ───────────────────────────

    @Test
    fun `파일명으로 찾는다`() {
        val rows = listOf(row("/dir/붉은망토.png"), row("/dir/푸른늑대.png"))
        val hit = LibraryPickerRows.visible(rows, ImageFilterHelper.Criteria(query = "붉은"), emptySet())
        assertEquals(listOf("/dir/붉은망토.png"), hit.map { it.path })
    }

    @Test
    fun `태그로도 배정된 이름으로도 찾는다`() {
        val rows = listOf(
            row("/a.png", tags = listOf("흑발")),
            row("/b.png", owners = listOf("홍길동")),
            row("/c.png")
        )
        assertEquals(
            listOf("/a.png"),
            LibraryPickerRows.visible(rows, ImageFilterHelper.Criteria(query = "흑발"), emptySet()).map { it.path }
        )
        assertEquals(
            listOf("/b.png"),
            LibraryPickerRows.visible(rows, ImageFilterHelper.Criteria(query = "홍길"), emptySet()).map { it.path }
        )
    }

    // ── 제외·정렬 ────────────────────────────────────────────────────────

    @Test
    fun `이미 붙어 있는 것은 목록에서 빠진다`() {
        val rows = listOf(row("/a.png"), row("/b.png"))
        val visible = LibraryPickerRows.visible(rows, ImageFilterHelper.Criteria(), setOf("/a.png"))
        assertEquals(listOf("/b.png"), visible.map { it.path })
    }

    @Test
    fun `최신 들여온 것이 먼저다`() {
        val rows = listOf(
            row("/old.png", importedAt = 100L),
            row("/new.png", importedAt = 300L),
            row("/mid.png", importedAt = 200L)
        )
        assertEquals(
            listOf("/new.png", "/mid.png", "/old.png"),
            LibraryPickerRows.sorted(rows).map { it.path }
        )
    }

    @Test
    fun `같은 시각이면 경로순 — 순서가 흔들리지 않는다`() {
        val rows = listOf(row("/b.png", importedAt = 5L), row("/a.png", importedAt = 5L))
        assertEquals(listOf("/a.png", "/b.png"), LibraryPickerRows.sorted(rows).map { it.path })
    }

    @Test
    fun `파일명은 경로의 마지막 조각이다`() {
        assertEquals("c.png", row("/a/b/c.png").fileName)
        assertEquals("c.png", row("c.png").fileName)
    }
}
