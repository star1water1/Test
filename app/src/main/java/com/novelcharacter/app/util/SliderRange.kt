package com.novelcharacter.app.util

import kotlin.math.roundToInt

/**
 * 연도 슬라이더의 **범위·눈금·값을 한 벌로** 낸다 (순수 로직 — JVM 시험으로 검증).
 *
 * ## 왜 순수부를 따로 두는가 — 어기면 *그리기 패스*에서 죽는다
 *
 * Material `Slider`는 두 가지를 요구한다.
 * ① `(valueTo - valueFrom)`이 `stepSize`의 배수일 것
 * ② `value`가 `valueFrom`에서 `stepSize` 배수 자리에 있을 것
 *
 * **어겼을 때 예외가 나는 자리가 대입 시점이 아니다** — `validateConfigurationIfDirty()`가
 * 도는 측정·그리기 패스에서 `IllegalStateException`이 난다. 그래서 대입을 감싼 `try/catch`가
 * **원리적으로 못 잡고** 앱이 그대로 죽는다. 실제로 그랬다: 캐릭터 상세의 시점 슬라이더가
 * 폭에 따라 눈금만 1/10/100으로 바꾸고 범위는 실측 최소·최대를 그대로 넣어, 상태변화 연도
 * 폭이 1,000을 넘고 10의 배수가 아니면 **화면이 열리자마자** 죽었다(예: 1000년~2005년).
 *
 * 연표 화면은 같은 제약을 알고 경계를 눈금에 맞추고 있었다 — **판단이 두 벌로 적혀 한쪽만
 * 옳았다.** 그래서 그 판단을 여기 한 자리로 내리고 두 화면이 같은 함수를 부른다.
 */
object SliderRange {

    /**
     * @param from 슬라이더의 왼쪽 끝(눈금에 맞춰져 있다)
     * @param to 오른쪽 끝 — `(to - from)`은 언제나 [step]의 배수다
     * @param step 눈금
     * @param value 지금 값 — [from]에서 [step] 배수 자리에 있고 `[from, to]` 안이다
     */
    data class Spec(val from: Float, val to: Float, val step: Float, val value: Float)

    /**
     * 폭에 따른 눈금 — 1 / 10 / 100. **두 화면이 같은 잣대를 쓴다.**
     *
     * 눈금을 두는 이유는 폭이 넓을 때 한 칸이 1년이면 끝에서 끝까지 끄는 데 수천 번을
     * 움직여야 하기 때문이다(원칙 04).
     */
    fun stepFor(span: Int): Float = when {
        span > 10000 -> 100f
        span > 1000 -> 10f
        else -> 1f
    }

    /**
     * @param minYear 실측 최소 연도
     * @param maxYear 실측 최대 연도(최소보다 작으면 최소로 본다)
     * @param current 지금 고른 연도 — `null`이면 왼쪽 끝에서 시작한다
     * @param pad 실측 범위의 양쪽에 더할 여유 연수(연표는 10, 캐릭터 상세는 0)
     */
    fun of(minYear: Int, maxYear: Int, current: Int?, pad: Int = 0): Spec {
        val hi = maxOf(minYear, maxYear)
        val step = stepFor((hi - minYear) + 2 * pad)
        val s = step.toInt()

        // 경계를 눈금 격자에 맞춘다 — 이것이 제약 ①을 성립시킨다.
        // `floorDiv`를 쓰는 이유는 **음수 연도**다(기원전 표기 — `/`는 0 쪽으로 자른다).
        var from = (minYear - pad).floorDiv(s) * s
        var to = ((hi + pad) + s - 1).floorDiv(s) * s
        // 폭이 0이면 한 눈금을 벌린다 — `valueFrom < valueTo`가 아니면 슬라이더가 죽는다.
        if (from >= to) {
            from -= s
            to += s
        }

        val raw = (current ?: minYear).coerceIn(from, to)
        // 제약 ② — 값도 눈금 위에 세운다. 반올림이라 사용자가 고른 해에서 가장 가까운 눈금이다.
        val steps = ((raw - from).toFloat() / step).roundToInt()
        val aligned = (from + steps * s).coerceIn(from, to)
        return Spec(from.toFloat(), to.toFloat(), step, aligned.toFloat())
    }
}
