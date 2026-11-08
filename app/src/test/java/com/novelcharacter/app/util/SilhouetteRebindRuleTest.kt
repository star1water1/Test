package com.novelcharacter.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilhouetteRebindRuleTest {

    @Test
    fun `bootstrap global form before the gate opens does not dismiss`() {
        // 회전 복원의 0번 콜백 — 전역 필드 폼에는 체형 필드가 없다
        assertFalse(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = false, formSettled = false))
    }

    @Test
    fun `settled form without the field dismisses`() {
        assertTrue(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = false, formSettled = true))
    }

    @Test
    fun `settled empty form also dismisses`() {
        // 다 선 작품 폼이 비어 있다 = 체형 필드가 정말 없다. 남기면 영영 안 세워지는 껍데기다.
        // (빈 목록을 '다 섰다'고 부르지 않는 것은 호스트의 formSettled 몫이다.)
        assertTrue(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = false, formSettled = true))
    }

    @Test
    fun `field present never dismisses`() {
        assertFalse(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = true, formSettled = true))
    }

    @Test
    fun `already built sheet is never dismissed here`() {
        assertFalse(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = false, fieldInForm = false, formSettled = true))
    }
}
