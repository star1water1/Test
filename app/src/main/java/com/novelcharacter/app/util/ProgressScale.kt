package com.novelcharacter.app.util

/**
 * 작업형 진행도의 **바이트 눈금 환산**(규약 R-26 · B-51 완결).
 *
 * 바이트를 세는 구간은 [com.novelcharacter.app.ui.common.TaskProgressDialog]에 그대로 갈 수 없다 —
 * 그 창은 `Int`를 받는데 이 앱의 상한은 **외부 파일 4GB · ZIP 이미지 총량 8GB**로
 * `Int`(약 2.1GB)를 넘는다. 넘긴 채로 그리면 막대가 음수로 뒤집혀 "진행도가 거꾸로 간다"가
 * 된다 — R-26이 금지하는 **거짓 진행도**의 가장 노골적인 형태다.
 *
 * 그래서 바이트 구간은 **MB 눈금**으로 환산해 보고한다. 사용자가 파일을 이야기할 때 쓰는
 * 단위이기도 하다("350MB / 750MB").
 *
 * 두 가지를 계약으로 잠근다:
 * 1. **총량에 닿은 진행은 반드시 총량 눈금으로 보고된다.** 내림만 쓰면 1MB 미만 파일이
 *    끝까지 0/1(0%)로 남아 "다 됐는데 안 끝난 것처럼 보이는" 창이 된다.
 * 2. **중간 진행은 총량 눈금을 넘지 않는다.** 올림만 쓰면 막대가 100%를 지나쳤다가 돌아온다.
 *
 * 총량을 알 수 없는 스트림(`statSize`가 -1을 주는 콘텐츠 프로바이더)은 [totalSteps]가 0이며,
 * 그 경우 창은 퍼센트 대신 불확정 막대와 "지금까지 N MB"를 그린다 — 총량을 모르는데 퍼센트를
 * 붙이지 않는다는 R-26 후단 그대로다.
 */
class ProgressScale private constructor(
    /** 총량 눈금(MB). 총량을 모르면 0이다. */
    val totalSteps: Int,
    private val totalBytes: Long
) {

    /**
     * 진행 바이트 → 눈금.
     *
     * 총량을 아는 경우 결과는 항상 `0..totalSteps`이고, [bytes]가 총량에 닿으면 정확히
     * [totalSteps]다. 총량을 모르면 지금까지의 MB를 그대로 돌려준다(창이 퍼센트를 그리지 않는다).
     */
    fun stepsFor(bytes: Long): Int {
        if (bytes <= 0L) return 0
        if (totalSteps == 0) return floorMegabytes(bytes)
        if (bytes >= totalBytes) return totalSteps
        return floorMegabytes(bytes).coerceIn(0, totalSteps)
    }

    companion object {
        const val MEGABYTE: Long = 1_048_576L

        /**
         * 총량 [totalBytes]짜리 바이트 구간의 눈금자를 만든다.
         *
         * 0 이하(= 총량을 알 수 없음)면 총량 눈금이 0이다. 0보다 크면 **올림**이라
         * 1바이트짜리 파일도 총량 1을 갖는다 — 0/0은 퍼센트를 그릴 수 없는 상태이고,
         * 그것을 창에 넘기면 "0 / 0 (0%)"라는 아무 말도 아닌 표시가 된다.
         */
        fun forBytes(totalBytes: Long): ProgressScale {
            if (totalBytes <= 0L) return ProgressScale(0, 0L)
            val steps = (totalBytes + MEGABYTE - 1L) / MEGABYTE
            return ProgressScale(steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), totalBytes)
        }

        private fun floorMegabytes(bytes: Long): Int =
            (bytes / MEGABYTE).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
