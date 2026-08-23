package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [headerFitWidth] · [columnWidthFor] — **열 이름이 자기 열에 들어가는가.**
 *
 * '전체 캐릭터' 시트의 43열 중 8열이 머리글이 잘린 채 나가고 있었다(2026.08.23 실측).
 * 그 시트의 존재 이유가 *"정렬·필터·피벗을 걸 때 쓰세요"*인데 열 이름이 안 읽혔다.
 * 원인은 너비를 [customFieldColumnWidth]가 **필드 타입만 보고** 정하는 것인데,
 * 그 시트의 머리글에는 언제나 `(필드키)`가, 타입별로 갈릴 때는 `·타입`까지 붙는다.
 */
class ColumnWidthFitTest {

    /** 한 글자 = 256. 여백 두 칸을 더한다. */
    private fun cells(width: Int) = width / 256

    @Test
    fun `한글은 두 칸으로 센다`() {
        // '나이' 2글자 = 4칸 + 여백 2 = 6칸
        assertEquals(6, cells(headerFitWidth("나이")))
        // 영문 4글자 = 4칸 + 2 = 6칸
        assertEquals(6, cells(headerFitWidth("abcd")))
    }

    /** 실측에서 가장 심하게 잘렸던 머리글 — 15.6칸 열에 36칸짜리 이름이었다. */
    @Test
    fun `잘리던 머리글이 이제 들어간다`() {
        val header = "초월자 세대(transcendent_generation)"
        val spec = ColumnSpec(header, width = customFieldColumnWidth(FieldType.NUMBER, multiToken = false))
        assertTrue("종전 너비로는 잘렸다", spec.width < headerFitWidth(header))
        assertTrue("이제는 이름이 들어간다", cells(columnWidthFor(spec)) >= 36)
    }

    @Test
    fun `이름이 짧으면 스펙 너비를 그대로 쓴다`() {
        val spec = ColumnSpec("키", width = 6000)
        assertEquals(6000, columnWidthFor(spec))
    }

    /** 이름 하나가 표를 못 쓰게 만들지 않는다 — 필드 이름의 길이에는 상한이 없다. */
    @Test
    fun `아주 긴 이름은 상한에서 접힌다`() {
        val absurd = "가".repeat(500)
        assertEquals(16000, headerFitWidth(absurd))
        assertEquals(16000, columnWidthFor(ColumnSpec(absurd, width = 3000)))
    }

    @Test
    fun `빈 이름은 넓히지 않는다`() {
        assertEquals(0, headerFitWidth(""))
        assertEquals(3000, columnWidthFor(ColumnSpec("", width = 3000)))
    }

    /**
     * **버그가 살던 시트로 직접 잰다** — '전체 캐릭터'의 머리글에는 `(필드키)`가 언제나 붙고,
     * 같은 키가 타입별로 갈리면 `·타입`까지 붙는다. 실측에서 잘리던 여덟을 그대로 넣는다.
     */
    @Test
    fun `전체 캐릭터 시트의 머리글이 전부 자기 열에 들어간다`() {
        val headers = listOf(
            "초월자 세대(transcendent_generation)", "초월자 넘버(transcendent_number)",
            "초월자 여부(is_transcendent)", "전투력 등급(combat_rank)",
            "사망연도(death_year)", "출생연도(birth_year)",
            "신체 사이즈(body_size)", "키(height·NUMBER)"
        )
        val types = listOf(
            FieldType.NUMBER, FieldType.NUMBER, FieldType.SELECT, FieldType.GRADE,
            FieldType.NUMBER, FieldType.NUMBER, FieldType.BODY_SIZE, FieldType.NUMBER
        ).map { it.name }
        val spec = allCharactersSpec(headers, types)
        val clipped = spec.columns.filter { columnWidthFor(it) < headerFitWidth(it.header) }
        assertTrue("잘리는 열: " + clipped.map { it.header }, clipped.isEmpty())
        // 그리고 실제로 **넓어졌다** — 스펙 값 그대로였다면 여덟이 전부 잘린다.
        val wouldClip = spec.columns.count { it.width < headerFitWidth(it.header) }
        assertEquals(8, wouldClip)
    }

    /** 고정 열(세계관·이름·코드 …)은 이름이 짧아 넓어질 일이 없다 — 표 모양이 흔들리지 않는다. */
    @Test
    fun `고정 열은 넓어지지 않는다`() {
        for (col in allCharactersSpec().columns) {
            assertEquals("'${col.header}'", col.width, columnWidthFor(col))
        }
    }
}
