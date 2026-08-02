package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자동 백업 회차 처분의 계약(B-96).
 *
 * **이 테스트가 드는 것은 "취소는 실패가 아니다"이다.** 종전에는 WorkManager가 워커를
 * stop하면 그것이 예외로 떨어져 상태 카드에 '백업 실패'가 남고 재시도가 소진되면 알림까지
 * 떴다 — 사용자가 한 일도, 고칠 것도 없는데. 그 동작으로 되돌리면 여기가 깨진다.
 */
class BackupWorkerPolicyTest {

    // ── 취소 갈래 (B-96이 바꾼 것) ────────────────────────────────────────────

    @Test
    fun `취소는 실패로 기록하지 않는다`() {
        val outcome = BackupWorkerPolicy.outcome(cancelled = true, failed = true, runAttemptCount = 0)
        assertEquals(BackupOutcome.CANCELLED, outcome)
        assertFalse("취소를 실패로 기록하면 상태 카드가 거짓말을 한다", outcome.recordsFailure)
        assertFalse("취소로 알림을 띄우면 사용자를 헛되이 깨운다", outcome.notifiesUser)
    }

    @Test
    fun `취소된 회차는 다시 돈다`() {
        val outcome = BackupWorkerPolicy.outcome(cancelled = true, failed = true, runAttemptCount = 0)
        assertTrue("취소는 백업을 포기하는 것이 아니다 — 다음 회차가 처음부터 한다", outcome.retries)
    }

    @Test
    fun `재시도가 소진된 뒤에 취소돼도 실패가 아니다`() {
        // 취소는 재시도 횟수보다 **먼저** 본다 — stop된 회차는 몇 번째든 실패가 아니다.
        val outcome = BackupWorkerPolicy.outcome(cancelled = true, failed = true, runAttemptCount = 99)
        assertEquals(BackupOutcome.CANCELLED, outcome)
        assertFalse(outcome.recordsFailure)
        assertTrue(outcome.retries)
    }

    // ── 종전 동작 보존 (B-96은 취소 갈래만 바꾼다) ──────────────────────────

    @Test
    fun `성공은 아무것도 기록하지 않고 다시 돌지 않는다`() {
        val outcome = BackupWorkerPolicy.outcome(cancelled = false, failed = false, runAttemptCount = 0)
        assertEquals(BackupOutcome.SUCCESS, outcome)
        assertFalse(outcome.recordsFailure)
        assertFalse(outcome.notifiesUser)
        assertFalse(outcome.retries)
    }

    @Test
    fun `재시도가 남은 실패는 기록하되 알리지 않는다`() {
        for (attempt in 0 until BackupWorkerPolicy.MAX_RETRY_ATTEMPTS) {
            val outcome = BackupWorkerPolicy.outcome(cancelled = false, failed = true, runAttemptCount = attempt)
            assertEquals("attempt=$attempt", BackupOutcome.RETRY, outcome)
            assertTrue(outcome.recordsFailure)
            assertFalse("재시도가 남았는데 알림을 띄우면 매 회차 깨운다", outcome.notifiesUser)
            assertTrue(outcome.retries)
        }
    }

    @Test
    fun `재시도가 소진된 실패만 알린다`() {
        val outcome = BackupWorkerPolicy.outcome(
            cancelled = false, failed = true, runAttemptCount = BackupWorkerPolicy.MAX_RETRY_ATTEMPTS
        )
        assertEquals(BackupOutcome.FAILED, outcome)
        assertTrue(outcome.recordsFailure)
        assertTrue(outcome.notifiesUser)
        assertFalse(outcome.retries)
    }

    @Test
    fun `재시도 경계는 종전 조건과 같다`() {
        // 종전 코드는 `runAttemptCount < 3`이었다. 경계를 옮기면 여기가 깨진다.
        assertEquals(3, BackupWorkerPolicy.MAX_RETRY_ATTEMPTS)
        assertTrue(BackupWorkerPolicy.outcome(false, true, 2).retries)
        assertFalse(BackupWorkerPolicy.outcome(false, true, 3).retries)
    }
}
