package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 복원 미리보기의 **임시 id 규칙** (B-233).
 *
 * 미리보기의 계수 자체는 DB에 묶여 순수 시험이 원리적으로 닿지 못한다(`ExcelImportService`).
 * 그러나 *"이 파일이 앞서 만든 항목을 뒤 행이 보게 한다"*가 성립하는 **근거**는 순수하다 —
 * 임시 id가 서로 겹치지 않는 것, 그리고 그 id로 색인에 넣었을 때 앞 항목이 살아남는 것.
 *
 * **그 둘이 이 기능의 유일한 실패 지점이다:** 겹치면 [ImportLookupIndex.put]이 *같은 행이
 * 옮겨 온 것*으로 보아 앞 항목의 키를 끊고, 그러면 미리보기가 고치려던 바로 그 증상
 * (같은 파일의 뒷 행이 앞 행의 쓰기를 못 봄)이 **다른 원인으로 되살아난다.**
 */
class PreviewCreationsTest {

    private fun universe(id: Long, name: String, code: String = "") =
        Universe(name = name, code = code).copy(id = id)

    @Test
    fun `발급한 id는 서로 겹치지 않는다`() {
        val ids = PreviewIdMinter()
        val minted = (1..100).map { ids.mint() }
        assertEquals("겹치는 id가 있습니다", minted.size, minted.toSet().size)
    }

    @Test
    fun `발급한 id는 전부 음수라 실존 행 id와 겹치지 않는다`() {
        val ids = PreviewIdMinter()
        repeat(50) { assertTrue("Room의 id는 1부터다", ids.mint() < 0) }
    }

    @Test
    fun `발급한 id는 '새로 만들 작품' 표지값을 다시 내주지 않는다`() {
        val ids = PreviewIdMinter()
        val minted = (1..50).map { ids.mint() }
        assertTrue(
            "표지값을 발급하면 미리보기가 '새로 만들 작품'과 '방금 만든 항목'을 같은 것으로 본다",
            ANALYSIS_CREATED_NOVEL_ID !in minted
        )
    }

    /**
     * 이 시험이 이 파일의 요점이다 — **id를 나눠 주지 않으면 어떻게 무너지는가**를 함께 잡는다.
     * 아래 두 시험은 같은 색인에 같은 절차를 밟고 id 규칙만 다르다.
     */
    @Test
    fun `임시 id로 등재한 신규 둘이 색인에 함께 남는다`() {
        val byName = ImportLookupIndex<String, Universe>(idOf = { it.id }, keyOf = { it.name })
        val ids = PreviewIdMinter()

        byName.put(universe(ids.mint(), "아르카디아"))
        byName.put(universe(ids.mint(), "베르단트"))

        assertNotNull("첫 신규가 색인에서 사라졌습니다", byName.first("아르카디아"))
        assertNotNull(byName.first("베르단트"))
    }

    @Test
    fun `id를 나눠 주지 않으면 앞 신규가 색인에서 끊긴다 — 임시 id가 필요한 이유`() {
        val byName = ImportLookupIndex<String, Universe>(idOf = { it.id }, keyOf = { it.name })

        // 전부 같은 id(예: 저장 전 기본값 0)로 넣으면 색인은 *같은 행이 개명한 것*으로 본다.
        byName.put(universe(0L, "아르카디아"))
        byName.put(universe(0L, "베르단트"))

        assertNull("이 갈래가 살아 있으면 B-233의 증상이 다른 원인으로 되살아난다", byName.first("아르카디아"))
    }

    /**
     * **한 색인에 발급기가 둘 붙으면 안 된다** — B-233 콜드 검토가 잡은 실제 사고다.
     *
     * 캐릭터 시트 분석은 *세계관 시트마다 한 번 + 미분류 시트에 한 번* 불리는데 색인은 그
     * 바깥에 하나뿐이다. 발급기를 그 함수의 지역 변수로 두면 **둘째 시트의 첫 신규가 첫째
     * 시트의 첫 신규와 같은 id를 받아**, 첫째 시트가 만든 캐릭터가 색인에서 사라진다 —
     * 이 기능이 고치려던 증상이 **수리 자체 때문에** 되살아나는 모양이다.
     *
     * 아래 두 시험이 그 갈림을 나란히 잡는다: 발급기가 둘이면 죽고, 하나면 산다.
     */
    @Test
    fun `발급기가 둘이면 서로의 신규를 색인에서 끊는다 — 수명이 색인과 같아야 하는 이유`() {
        val byName = ImportLookupIndex<String, Universe>(idOf = { it.id }, keyOf = { it.name })

        // 시트마다 발급기를 새로 만드는 모양(첫 구현이 그랬다).
        val sheet1Ids = PreviewIdMinter()
        val sheet2Ids = PreviewIdMinter()
        byName.put(universe(sheet1Ids.mint(), "아르카디아"))
        byName.put(universe(sheet2Ids.mint(), "베르단트"))

        assertNull(
            "둘째 시트의 첫 신규가 첫째 시트의 첫 신규와 같은 id를 받아 그 키를 끊는다",
            byName.first("아르카디아")
        )
    }

    @Test
    fun `발급기가 하나면 시트를 넘어도 서로를 끊지 않는다`() {
        val byName = ImportLookupIndex<String, Universe>(idOf = { it.id }, keyOf = { it.name })

        // 분석 하나에 id 공간 하나 — 색인을 비우는 자리에서 함께 새로 만든다.
        val ids = PreviewIdMinter()
        byName.put(universe(ids.mint(), "아르카디아"))
        byName.put(universe(ids.mint(), "베르단트"))

        assertNotNull(byName.first("아르카디아"))
        assertNotNull(byName.first("베르단트"))
    }

    @Test
    fun `코드 칸이 빈 신규는 코드 색인에 통을 만들지 않는다`() {
        // 가져오기는 그 자리에 임의 코드를 발급하고 미리보기는 그 값을 알 수 없다. 자리표시자를
        // 넣으면 **모든 신규 행이 한 통에 쌓이는 키**가 되므로, 빈 코드를 그대로 둔다.
        val byCode = ImportLookupIndex<String, Universe>(
            idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
        )
        val ids = PreviewIdMinter()

        byCode.put(universe(ids.mint(), "아르카디아", code = ""))
        byCode.put(universe(ids.mint(), "베르단트", code = ""))

        assertNull(byCode.first(""))
        assertTrue(byCode.all("").isEmpty())
    }
}
