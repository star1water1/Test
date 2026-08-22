package com.novelcharacter.app.util

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
 * 옳았다.** 그래서 그 판단을 여기 한 자리로 내리고 세 화면(연표 · 캐릭터 상세 · 관계도)이
 * 같은 함수를 부른다.
 *
 * ## 셈은 `Long`으로, 경계는 `Float`가 담을 수 있는 자리로
 *
 * 연도에는 상한이 없다(판타지 역법 — 월·일에는 범위 검사가 있는데 연도에만 없다). 그래서
 * 두 가지가 걸린다.
 *
 * ① **`maxYear - minYear`가 `Int`를 넘친다** — 기원전 표기와 큰 연도가 섞이면 음수가 되고,
 *   그러면 눈금 판정이 거꾸로 서서 *좁은 폭*으로 읽힌다. 여기서는 전부 `Long`으로 센다.
 * ② **`Float`는 큰 정수를 전부 담지 못한다** — 슬라이더의 세 값이 `Float`인데, 2²⁴를 넘으면
 *   표현 가능한 정수의 간격이 2, 4, 8…로 벌어진다. 눈금이 그 간격의 배수가 아니면 대입한
 *   값이 반올림되어 **격자에서 벗어나고, 그 어김은 다시 그리기 패스에서 터진다.**
 *   그래서 필요한 만큼 **눈금을 2배씩 넓혀** 격자와 부동소수 격자를 일치시킨다. 이 보정은
 *   연도가 천만을 넘을 때만 걸리므로 보통의 연표에서는 눈금이 종전과 같다.
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
     * 폭에 따른 눈금 — 1 / 10 / 100. **세 화면이 같은 잣대를 쓴다.**
     *
     * 눈금을 두는 이유는 폭이 넓을 때 한 칸이 1년이면 끝에서 끝까지 끄는 데 수천 번을
     * 움직여야 하기 때문이다(원칙 04).
     */
    fun stepFor(span: Long): Float = when {
        span > 10000 -> 100f
        span > 1000 -> 10f
        else -> 1f
    }

    /**
     * `Float`가 이 크기 근처에서 **정확히 담을 수 있는 정수의 간격**.
     *
     * 가수가 24비트라 2²⁴ 아래는 모든 정수가 정확하고, 그 위로는 크기가 두 배 될 때마다
     * 간격이 두 배가 된다. 눈금이 이 간격의 배수여야 격자 위의 값이 반올림되지 않는다.
     */
    private fun floatGranularity(magnitude: Long): Long {
        var gap = 1L
        var limit = 1L shl 24
        while (magnitude >= limit) {
            gap *= 2
            limit *= 2
        }
        return gap
    }

    /**
     * @param minYear 실측 최소 연도
     * @param maxYear 실측 최대 연도(최소보다 작으면 최소로 본다)
     * @param current 지금 고른 연도 — `null`이면 왼쪽 끝에서 시작한다
     * @param pad 실측 범위의 양쪽에 더할 여유 연수(연표는 10, 캐릭터 상세·관계도는 0)
     */
    fun of(minYear: Int, maxYear: Int, current: Int?, pad: Int = 0): Spec {
        val lo = minYear.toLong()
        val hi = maxOf(lo, maxYear.toLong())
        val p = pad.toLong()
        var step = stepFor((hi - lo) + 2 * p).toLong()

        var from: Long
        var to: Long
        while (true) {
            // 경계를 눈금 격자에 맞춘다 — 이것이 제약 ①을 성립시킨다.
            // `floorDiv`를 쓰는 이유는 **음수 연도**다(기원전 표기 — `/`는 0 쪽으로 자른다).
            from = Math.floorDiv(lo - p, step) * step
            to = Math.floorDiv(hi + p + step - 1, step) * step
            // 폭이 0이면 한 눈금을 벌린다 — `valueFrom < valueTo`가 아니면 슬라이더가 죽는다.
            if (from >= to) {
                from -= step
                to += step
            }
            // 눈금이 부동소수 격자의 배수인가 — 아니면 넓혀서 다시 맞춘다(위 ②).
            val magnitude = maxOf(kotlin.math.abs(from), kotlin.math.abs(to))
            if (step % floatGranularity(magnitude) == 0L) break
            step *= 2
        }

        val raw = (current?.toLong() ?: lo).coerceIn(from, to)
        // 제약 ② — 값도 눈금 위에 세운다. 반올림이라 사용자가 고른 해에서 가장 가까운 눈금이다.
        val steps = Math.round((raw - from).toDouble() / step)
        val aligned = (from + steps * step).coerceIn(from, to)
        return Spec(from.toFloat(), to.toFloat(), step.toFloat(), aligned.toFloat())
    }
}
