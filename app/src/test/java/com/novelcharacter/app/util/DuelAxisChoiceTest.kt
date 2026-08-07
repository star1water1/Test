package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelAxisChoice] 하네스 — 대결 축 고르기의 계약 (B-134).
 *
 * **여기서 지키는 것 둘이고, 둘 다 시험이 유일한 검출이다:**
 *
 * ① *"가리키던 축을 못 찾으면 고른 축이 없다"* — 이것이 무너지면 스피너가 **말없이 0번 축을
 *    고르고 그 축의 순위가 이 필드에 배정된다**(R-1 오배정). 화면 계층에서는 안 잡힌다:
 *    `AbsSpinner.setAdapter`가 스스로 0번을 고르는 것이라 **코드에 "0"이 적혀 있지 않고**,
 *    컴파일도 정적 검사도 통과한다. 2026.08.07 이전의 배선이 정확히 그 상태였고 주석은
 *    *"조용히 0번으로 옮기면 오배정이다"*라고 그 옆에 적혀 있었다.
 *
 * ② *"저장된 code가 없으면 첫 축을 고른 채로 연다"* — ①을 고치다 보면 **모든 경우에
 *    자리 표시용 항목을 세우고 싶어지는데**, 그러면 축 하나짜리 세계관에서 새 필드를 만들 때마다
 *    있으나 마나 한 고르기를 한 번 더 시킨다(원칙 04). 어긋날 약속이 없는 자리라 오배정이
 *    성립하지 않는다는 것이 이 갈래의 근거이고, **근거가 시험에 없으면 다음 사람이 통일한다.**
 */
class DuelAxisChoiceTest {

    private fun axis(code: String, name: String = code) =
        DuelAxis(id = 0, universeId = 1, name = name, code = code)

    private val axes = listOf(axis("AX-1", "강함"), axis("AX-2", "아름다움"))

    // ── ① 끊긴 참조 ──

    @Test
    fun `dangling code puts a placeholder first and selects nothing`() {
        val choice = DuelAxisChoice.resolve(axes, "AX-GONE")

        assertTrue("찾지 못한 참조는 댕글링이다", choice.dangling)
        assertEquals("자리 표시용 항목이 맨 앞에 선다", 3, choice.items.size)
        assertEquals(0, choice.selection)
        assertNull("고른 축이 없다 — 0번 축이 아니다", choice.axisAt(choice.selection))
    }

    @Test
    fun `dangling keeps every real axis pickable behind the placeholder`() {
        val choice = DuelAxisChoice.resolve(axes, "AX-GONE")

        // 자리 표시용 항목이 앞에 서므로 실재 축은 한 칸씩 밀린다. 그 계산을 화면이 따로
        // 하면 어긋나므로 axisAt이 든다.
        assertEquals("AX-1", choice.axisAt(1)?.code)
        assertEquals("AX-2", choice.axisAt(2)?.code)
        assertNull("범위 밖은 null이다", choice.axisAt(3))
    }

    // ── ② 저장된 참조가 없는 경우 ──

    @Test
    fun `no stored code opens on the first axis`() {
        val choice = DuelAxisChoice.resolve(axes, null)

        assertFalse(choice.dangling)
        assertEquals(0, choice.selection)
        assertEquals("AX-1", choice.axisAt(choice.selection)?.code)
        assertEquals("자리 표시용 항목을 세우지 않는다", 2, choice.items.size)
    }

    @Test
    fun `blank stored code is the same as none`() {
        // 손으로 고친 엑셀·손상 config에서 온다. `DuelGradeRef.fromConfig`가 빈 code를 통째로
        // 없는 것으로 읽으므로 여기서도 같은 판정이어야 한다(두 벌이 되면 한쪽이 낡는다).
        val choice = DuelAxisChoice.resolve(axes, "   ")

        assertFalse(choice.dangling)
        assertEquals("AX-1", choice.axisAt(choice.selection)?.code)
    }

    // ── 정상 ──

    @Test
    fun `stored code selects that axis`() {
        val choice = DuelAxisChoice.resolve(axes, "AX-2")

        assertFalse(choice.dangling)
        assertEquals(1, choice.selection)
        assertEquals("AX-2", choice.axisAt(choice.selection)?.code)
    }

    @Test
    fun `empty axis list has nothing to pick`() {
        val choice = DuelAxisChoice.resolve(emptyList(), "AX-GONE")

        // 축이 하나도 없는 것은 댕글링과 다른 사정이다 — 할 말이 *"이 세계관에 축이 없다"*이고
        // 교정 경로도 [축 만들기]로 다르다. 여기서 dangling을 켜면 화면이 그 둘을 못 가른다.
        assertFalse(choice.dangling)
        assertTrue(choice.items.isEmpty())
        assertNull(choice.axisAt(0))
    }
}
