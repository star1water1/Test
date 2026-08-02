package com.novelcharacter.app.util

/**
 * 자동 백업 한 회차의 처분 — **순수 판정**(B-96).
 *
 * 이 판정을 워커 안의 `if`로 두면 검증할 수단이 없다(`AutoBackupWorker`는 안드로이드
 * 런타임에 묶여 순수 하네스가 못 든다). 그래서 "무엇을 기록하고 무엇을 알리고 다시 도는가"만
 * 떼어내 하네스가 계약으로 들게 한다 — `ResetPlan`·`ProgressScale`과 같은 결이다.
 *
 * **취소를 실패와 가르는 것이 이 파일이 생긴 이유다.** WorkManager가 실행 시간 초과 등으로
 * 워커를 stop하면 종전에는 그것이 예외로 떨어져 **실패로 기록되고 알림까지 떴다.**
 * 사용자가 한 일도, 고칠 것도 없는데 상태 카드가 "백업 실패"라고 말하면 그 카드는
 * 거짓말을 하는 것이다(개발 의도 2번 '변수 제어' — 앱이 스스로 만든 수치가 사실과 달라선 안 된다).
 */
enum class BackupOutcome(
    /** 상태 저장소·작업 이력에 **실패로** 남기는가. */
    val recordsFailure: Boolean,
    /** 시스템 알림으로 능동 통지하는가(재시도가 소진된 최종 실패에서만). */
    val notifiesUser: Boolean,
    /** WorkManager에 재시도를 요청하는가. */
    val retries: Boolean
) {
    /** 정상 완료. */
    SUCCESS(recordsFailure = false, notifiesUser = false, retries = false),

    /**
     * 취소로 접혔다 — **실패가 아니다.**
     * 산출물이 없으므로 기록할 실패도 없고, 다음 회차가 처음부터 다시 하면 된다.
     */
    CANCELLED(recordsFailure = false, notifiesUser = false, retries = true),

    /** 실패했으나 재시도가 남았다 — 상태에는 남기되 알림으로 깨우지는 않는다. */
    RETRY(recordsFailure = true, notifiesUser = false, retries = true),

    /** 재시도 소진 — 상태 기록 + 시스템 알림. */
    FAILED(recordsFailure = true, notifiesUser = true, retries = false)
}

object BackupWorkerPolicy {

    /**
     * 재시도를 계속할 `runAttemptCount` 상한.
     *
     * `runAttemptCount`는 첫 실행이 0이므로 0·1·2에서 재시도하고 3에서 최종 실패한다
     * (종전 `runAttemptCount < 3`과 **같은 동작**이다 — B-96은 취소 갈래만 바꾼다).
     */
    const val MAX_RETRY_ATTEMPTS = 3

    /**
     * @param cancelled 취소로 접혔는가(`ExportCancelledException`). 실패보다 **먼저** 본다 —
     *   stop된 회차는 그 뒤에 무슨 예외가 따라오든 사용자에게 실패가 아니다.
     * @param failed 취소가 아닌 실제 예외로 끝났는가.
     * @param runAttemptCount WorkManager가 주는 이번 회차 번호(첫 실행 0).
     */
    fun outcome(cancelled: Boolean, failed: Boolean, runAttemptCount: Int): BackupOutcome = when {
        cancelled -> BackupOutcome.CANCELLED
        !failed -> BackupOutcome.SUCCESS
        runAttemptCount < MAX_RETRY_ATTEMPTS -> BackupOutcome.RETRY
        else -> BackupOutcome.FAILED
    }
}
