package com.novelcharacter.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilhouetteRebindRuleTest {

    @Test
    fun `bootstrap global form before the gate opens does not dismiss`() {
        // 회전 복원의 0번 콜백 — 전역 필드 폼에는 체형 필드가 없다
        assertFalse(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = false, formHasFields = true, formSettled = false))
    }

    @Test
    fun `settled form without the field dismisses`() {
        assertTrue(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = false, formHasFields = true, formSettled = true))
    }

    @Test
    fun `empty list still loading does not dismiss`() {
        assertFalse(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = false, formHasFields = false, formSettled = true))
    }

    @Test
    fun `field present never dismisses`() {
        assertFalse(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = true, fieldInForm = true, formHasFields = true, formSettled = true))
    }

    @Test
    fun `already built sheet is never dismissed here`() {
        assertFalse(SilhouetteRebindRule.shouldDismiss(
            awaitingRestore = false, fieldInForm = false, formHasFields = true, formSettled = true))
    }
}
