package com.novelcharacter.app.util

/**
 * 다이얼로그 본문 스크롤의 **높이 상한 규칙**(규약 R-31)의 순수 계산부.
 *
 * **왜 뷰에서 떼어 냈는가:** 상한을 쓰는 자리가 셋이 됐다 — 코드로 만드는
 * [cappedScrollView], XML 루트용 `CappedScrollView`, 그리고 중첩 스크롤이 필요한
 * `CappedNestedScrollView`. 세 곳이 각자 `minOf`를 적으면 **규약이 갈린다**(R-31이
 * "단일 소스"라 적은 것이 이 자리다). 계산을 여기 한 벌로 두면 뷰 셋은 껍데기가 되고,
 * 규칙 자체는 순수 JVM에서 실제로 검증된다 — 그리기 계층은 CI와 실기기의 몫이지만
 * **이 산수는 그럴 필요가 없다.**
 */
object DialogScrollCap {

    /**
     * 화면 높이 중 다이얼로그 본문이 쓸 수 있는 몫. 제목·버튼이 차지할 자리를 남긴다.
     * 이 값이 상한 규약의 기본값이며, 다른 자리에서 숫자를 다시 적지 않는다.
     */
    const val DEFAULT_FRACTION = 0.7f

    /**
     * 화면 높이에서 상한 픽셀을 구한다.
     *
     * [fraction]이 0 이하이거나 1을 넘으면 [DEFAULT_FRACTION]으로 되돌린다 —
     * 호출부의 오타가 "상한 0(아무것도 안 보임)"이나 "화면보다 큰 상한(상한 없음과 같음)"으로
     * 조용히 굳는 것을 막는다. 어느 쪽이든 증상이 상한 자체의 부재와 구별되지 않는다.
     */
    fun capPx(screenHeightPx: Int, fraction: Float = DEFAULT_FRACTION): Int {
        val safe = if (fraction > 0f && fraction <= 1f) fraction else DEFAULT_FRACTION
        return (screenHeightPx * safe).toInt()
    }

    /**
     * 실제로 측정에 쓸 높이. [parentBounded]가 참이면 부모가 준 [parentSizePx]와
     * 상한 중 **작은 쪽**을 따른다.
     *
     * 부모를 이기지 않는 것이 요점이다 — 상한을 무조건 밀어붙이면 가로 모드나 작은
     * 화면에서 되레 넘쳐 **고치려던 증상을 다른 자리에서 재현한다**(R-31).
     */
    fun limitPx(capPx: Int, parentBounded: Boolean, parentSizePx: Int): Int =
        if (parentBounded) minOf(capPx, parentSizePx) else capPx
}
