package com.novelcharacter.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ColorHex.isValidHex] — **들이는 문**의 색 판정 (순수라 여기서 잰다).
 *
 * 읽는 쪽([ColorHex.parseOrNull])은 플랫폼 파서라 순수 JVM에서 돌지 않는다. 그래서 이
 * 시험이 지키는 것은 *저장해도 되는 형식*의 계약이고, 죽지 않는 것은 그 함수의 `try/catch`가 든다.
 */
class ColorHexTest {

    @Test
    fun `여섯 자리 색을 받는다`() {
        assertTrue(ColorHex.isValidHex("#E91E63"))
        assertTrue(ColorHex.isValidHex("#000000"))
        assertTrue(ColorHex.isValidHex("#ffffff"))
    }

    @Test
    fun `세 자리와 여덟 자리도 받는다`() {
        assertTrue(ColorHex.isValidHex("#FFF"))
        assertTrue(ColorHex.isValidHex("#80E91E63"))
    }

    @Test
    fun `앞뒤 공백은 견딘다`() {
        assertTrue(ColorHex.isValidHex("  #E91E63 "))
    }

    /** 이 값들이 저장되면 화면이 칠하려다 죽거나 조용히 회색으로 떨어진다. */
    @Test
    fun `색이 아닌 글자는 거른다`() {
        assertFalse(ColorHex.isValidHex("빨강"))
        assertFalse(ColorHex.isValidHex("E91E63"))      // # 없음
        assertFalse(ColorHex.isValidHex("#E91E6"))      // 다섯 자리
        assertFalse(ColorHex.isValidHex("#GGGGGG"))     // 16진수 아님
        assertFalse(ColorHex.isValidHex("#E91E63 #FFF"))
        assertFalse(ColorHex.isValidHex(""))
        assertFalse(ColorHex.isValidHex(null))
    }

    /** 명명색은 **저장 형식으로는** 받지 않는다 — 앱이 스스로 내보내는 값은 언제나 #RRGGBB다. */
    @Test
    fun `명명색은 저장 형식이 아니다`() {
        assertFalse(ColorHex.isValidHex("red"))
    }
}
