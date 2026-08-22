package com.novelcharacter.app.ui.common

import com.google.android.material.slider.Slider
import com.novelcharacter.app.util.SliderRange

/**
 * [SliderRange.Spec]을 슬라이더에 얹는다 — **대입 순서 자체가 제약이다.**
 *
 * 범위와 값을 아무 순서로나 넣으면 *중간 상태*가 제약을 어긴다: 새 값이 옛 범위 밖이거나,
 * 새 범위가 옛 눈금의 배수가 아니거나. 그 어김은 대입 시점이 아니라 **그리기 패스**에서
 * 예외로 나오므로 `try/catch`로는 못 막는다([SliderRange] 헤더).
 *
 * 그래서 순서를 여기 한 자리에 못박는다 — 눈금을 풀고 → 옛 범위와 새 범위를 **모두 덮게**
 * 넓히고 → 값을 넣고 → 좁히고 → 눈금을 다시 건다. 두 화면이 이 한 함수를 부른다.
 */
fun Slider.applyRange(spec: SliderRange.Spec) {
    stepSize = 0f
    valueFrom = minOf(valueFrom, spec.from)
    valueTo = maxOf(valueTo, spec.to)
    value = spec.value
    valueFrom = spec.from
    valueTo = spec.to
    stepSize = spec.step
}
