package com.novelcharacter.app.pcbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTokenTest {

    /** 결정적 난수원 — 발급 글자가 알파벳 순서를 그대로 따라가게 둔다. */
    private fun counter(): (Int) -> Int {
        var i = 0
        return { bound -> (i++) % bound }
    }

    @Test
    fun generate_hasDeclaredLength() {
        assertEquals(BridgeToken.LENGTH, BridgeToken.generate(counter()).length)
    }

    @Test
    fun generate_usesOnlyUrlSafeUnambiguousChars() {
        val t = BridgeToken.generate { bound -> (0 until bound).random() }
        // URL 질의에 그대로 담기고 손으로 베껴 적을 수 있어야 한다.
        assertTrue(t.all { it.isLetterOrDigit() })
        // 헷갈리는 글자는 발급되지 않는다.
        for (c in listOf('0', 'O', '1', 'l', 'I')) {
            assertFalse("발급 토큰에 $c 가 있다: $t", t.contains(c))
        }
    }

    @Test
    fun matches_sameTokenPasses() {
        val t = BridgeToken.generate(counter())
        assertTrue(BridgeToken.matches(t, t))
    }

    @Test
    fun matches_differentTokenFails() {
        val t = BridgeToken.generate(counter())
        val other = t.dropLast(1) + if (t.last() == 'a') 'b' else 'a'
        assertFalse(BridgeToken.matches(t, other))
    }

    @Test
    fun matches_lengthMismatchFails_bothDirections() {
        val t = BridgeToken.generate(counter())
        assertFalse(BridgeToken.matches(t, t.dropLast(1)))
        assertFalse(BridgeToken.matches(t, t + "a"))
    }

    /**
     * **저장된 토큰이 없으면 무엇도 통과하지 않는다.**
     * 서버를 한 번도 켜지 않은 상태가 "아무 글자나 통과"가 되면 안 된다.
     */
    @Test
    fun matches_emptyStoredNeverPasses() {
        assertFalse(BridgeToken.matches(null, "anything"))
        assertFalse(BridgeToken.matches("", "anything"))
        assertFalse(BridgeToken.matches("", ""))
        assertFalse(BridgeToken.matches(null, null))
    }

    @Test
    fun matches_nullPresentedFails() {
        val t = BridgeToken.generate(counter())
        assertFalse(BridgeToken.matches(t, null))
    }

    /**
     * 접두만 맞는 글자는 통과하지 않는다 — 길이를 먼저 흘리지 않는 구현이
     * *접두 일치*를 통과로 접지 않았는지 보는 자리다.
     */
    @Test
    fun matches_prefixOnlyFails() {
        assertFalse(BridgeToken.matches("abcdef", "abc"))
        assertFalse(BridgeToken.matches("abc", "abcdef"))
    }
}
