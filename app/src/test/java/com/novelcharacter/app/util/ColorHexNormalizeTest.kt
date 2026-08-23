package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 색 글자의 저장 형식 다듬기.
 *
 * 실측(2026.08.24 내보낸 파일): '세계관' 여덟 행 중 한 행이 `000000`이고 나머지 일곱은
 * `#RRGGBB`였다 — 같은 뜻의 값이 파일 안에서 두 글자로 갈렸고, 파일이 스스로의 안내
 * (*"테두리색(HEX)"*)와 어긋났다.
 */
class ColorHexNormalizeTest {

    @Test
    fun `샵이 빠진 글자에 샵을 붙인다`() {
        assertEquals("#000000", ColorHex.normalizedOrNull("000000"))
        assertEquals("#ABC", ColorHex.normalizedOrNull("ABC"))
        assertEquals("#80FF0000", ColorHex.normalizedOrNull("80FF0000"))
    }

    @Test
    fun `이미 맞는 글자는 그대로다`() {
        assertEquals("#7E57C2", ColorHex.normalizedOrNull("#7E57C2"))
    }

    @Test
    fun `앞뒤 공백은 다듬는다`() {
        assertEquals("#7E57C2", ColorHex.normalizedOrNull("  #7E57C2  "))
        assertEquals("#7E57C2", ColorHex.normalizedOrNull(" 7E57C2 "))
    }

    @Test
    fun `알아볼 수 없는 글자는 null이다`() {
        // 부르는 쪽이 '그대로 둔다'를 고를 수 있어야 한다 — 여기서 버리면 무음 유실이다.
        assertNull(ColorHex.normalizedOrNull("red"))
        assertNull(ColorHex.normalizedOrNull("#GGGGGG"))
        assertNull(ColorHex.normalizedOrNull("12345"))
        assertNull(ColorHex.normalizedOrNull(""))
        assertNull(ColorHex.normalizedOrNull(null))
    }

    @Test
    fun `명명색은 여전히 받지 않는다`() {
        // 들이는 문에서는 저장 형식을 좁히는 쪽이 안전하다는 isValidHex의 근거 그대로다.
        assertNull(ColorHex.normalizedOrNull("BLUE"))
    }
}
