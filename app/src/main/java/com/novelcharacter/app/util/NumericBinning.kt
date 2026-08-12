package com.novelcharacter.app.util

import kotlin.math.abs

/**
 * 수치 구간(bin) 생성의 **단일 소스** (S-16).
 *
 * 분포를 만드는 쪽과 그 조각을 눌렀을 때 인원을 세는 쪽이 구간을 각자 계산하면 두 수가
 * 어긋난다. 종전 레거시 필드 분석은 더 나빠서, 분포는 구간 라벨(`"160~170"`)로 만들어 놓고
 * 드릴다운은 그 **라벨 문자열을 저장값과 비교**했다 — 저장값은 `"170-60-80"`이므로 어떤
 * 입력에서도 일치할 수 없어 **항상 0명**이었다.
 *
 * 그래서 구간의 경계·경계 규칙·라벨을 여기서 한 번만 만들고, 드릴다운에는 라벨이 아니라
 * 그 구간 자체([FieldValueMatchSpec.NumericPartRange])를 넘긴다.
 */
object NumericBinning {

    /** 자동 구간 분할의 기본 구간 수. 분포·드릴다운이 같은 값을 보도록 상수가 단일 소스다. */
    const val DEFAULT_BIN_COUNT = 5

    /** 라벨이 쓸 수 있는 소수 자리 상한. 넘어가면 축 라벨이 읽히지 않는다. */
    private const val MAX_DECIMALS = 3

    /**
     * 하나의 수치 구간.
     *
     * 경계 규칙은 **하한 포함 / 상한 미포함**이고, **마지막 구간만 상한을 포함**한다
     * ([inclusiveMax]). 이 규칙이 어긋나면 최댓값을 가진 항목이 어느 구간에도 속하지 않아
     * 분포 합이 모집단보다 작아진다.
     */
    data class Bin(
        val index: Int,
        val min: Float,
        val max: Float,
        val inclusiveMax: Boolean,
        val label: String
    ) {
        fun contains(value: Float): Boolean =
            value >= min && (if (inclusiveMax) value <= max else value < max)
    }

    /**
     * [values]의 최소~최대를 [binCount]등분한 구간 목록. 값이 2개 미만이거나 폭이 0이면
     * 빈 목록(구간을 나눌 수 없다 — 호출부가 분포를 만들지 않는다).
     *
     * 라벨은 **구간마다 유일**하도록 만든다. 폭이 좁아 정수 라벨이 겹치면 소수 자리를 늘린다 —
     * 겹친 라벨을 맵 키로 쓰면 뒤 구간이 앞 구간을 덮어써 건수가 조용히 사라진다.
     */
    fun autoBins(values: List<Float>, binCount: Int = DEFAULT_BIN_COUNT): List<Bin> {
        if (values.size < 2 || binCount <= 0) return emptyList()
        val min = values.min()
        val max = values.max()
        val range = max - min
        if (range <= 0f) return emptyList()

        val step = range / binCount
        val bounds = (0 until binCount).map { i ->
            val binMin = min + i * step
            val binMax = if (i == binCount - 1) max else min + (i + 1) * step
            Triple(i, binMin, binMax)
        }

        val decimals = firstUniqueDecimals(bounds.map { it.second to it.third })
        val base = bounds.map { (i, binMin, binMax) ->
            Triple(i, binMin to binMax, label(binMin, binMax, decimals))
        }
        // 소수 자리를 늘려도 겹치는 경우가 있다(엑셀 왕복의 부동소수 잔차처럼 폭이 1e-4인 분포).
        // 그때는 **순번을 붙여서라도** 유일하게 만든다 — 겹친 라벨은 맵 키에서 서로를 덮어써
        // 그 구간의 인원이 개수 고지도 없이 사라진다.
        val needsIndex = base.map { it.third }.toSet().size != base.size
        return base.map { (i, range, label) ->
            Bin(
                index = i,
                min = range.first,
                max = range.second,
                inclusiveMax = i == binCount - 1,
                label = if (needsIndex) "$label (${i + 1})" else label
            )
        }
    }

    /** 구간 하나와 그 구간에 든 건수. 분포를 만든 쪽과 드릴다운이 **같은 구간 객체**를 쓰게 한다. */
    data class BinCount(val bin: Bin, val count: Int)

    /**
     * 원문 목록에서 수치를 뽑는 **단일 소스**.
     *
     * 파트 없는 값(NUMBER·CALCULATED)은 `separator=""`·`partIndex=0`으로 부른다 —
     * 이 경우 [partValue]가 원문 전체를 하나의 파트로 본다.
     *
     * **호출부가 `toFloatOrNull()`을 직접 쓰지 않는 것이 요점이다.** 둘은 일반 공백에서는 같게
     * 동작하지만(`parseFloat`가 ASCII 공백을 건너뛴다) **줄바꿈 없는 공백(U+00A0)에서 갈린다** —
     * 코틀린 `trim()`은 `isSpaceChar`도 보므로 지우는데 `parseFloat`는 지우지 않는다.
     * 엑셀·웹에서 붙여 넣은 값이 실제로 그 문자를 달고 오고, 그때 분포를 만드는 쪽이
     * `toFloatOrNull()`을 쓰면 그 값이 **분포에서는 빠지고 드릴다운 목록에는 뜬다.**
     * 조각의 수와 목록의 인원이 어긋나는 그 모양이 S-16이 고친 결함이다.
     */
    fun numericValuesOf(rawValues: List<String>, separator: String, partIndex: Int): List<Float> =
        rawValues.mapNotNull { partValue(it, separator, partIndex) }

    /**
     * [values]를 자동 구간에 담아 (구간, 건수)로 돌려준다. 구간을 나눌 수 없으면 빈 목록.
     *
     * 세는 일을 여기 모으는 이유는 **경계 규칙이 한 벌이어야 하기 때문**이다 —
     * 각 호출부가 `count { it >= min && it < max }`를 손으로 적으면 마지막 구간의 상한 포함
     * ([Bin.inclusiveMax])을 한 곳이라도 빠뜨리는 순간 최댓값이 어느 구간에도 안 들어
     * 분포 합이 모집단보다 작아진다.
     */
    fun autoDistribution(values: List<Float>, binCount: Int = DEFAULT_BIN_COUNT): List<BinCount> =
        autoBins(values, binCount).map { bin -> BinCount(bin, values.count { bin.contains(it) }) }

    /**
     * 나눌 구간이 없는 분포(값이 하나뿐이거나 전부 같다)의 **유일한 구간**.
     *
     * [autoBins]는 이 경우 빈 목록을 돌려주므로(나눌 폭이 없다) 호출부가 막대 한 칸을
     * 직접 만들어야 하는데, **그 자리에서 라벨을 손으로 지으면 자릿수가 틀린다** —
     * [label]의 기본 자릿수가 0이라 값이 `170.5`인 필드가 `"170~170"`으로 보인다.
     * 라벨이 실제 값을 잘못 말하는 것이라, 자릿수는 **값이 되살아나는 최소 자리**로 정한다.
     */
    fun singleBin(value: Float): Bin {
        val decimals = if (value == value.toInt().toFloat()) 0
        else (1..MAX_DECIMALS).firstOrNull { d ->
            String.format("%.${d}f", value).toFloatOrNull() == value
        } ?: MAX_DECIMALS
        return Bin(
            index = 0,
            min = value,
            max = value,
            // 하한만 포함하면 그 값 자체가 어느 구간에도 안 든다 — 유일한 구간이므로 닫는다.
            inclusiveMax = true,
            label = label(value, value, decimals)
        )
    }

    /** 구간 라벨. [decimals]는 [autoBins]가 겹치지 않는 최소 자릿수로 정한다. */
    fun label(min: Float, max: Float, decimals: Int = 0): String =
        "${format(min, decimals)}~${format(max, decimals)}"

    /**
     * 구조화 입력(BODY_SIZE 등) 원문에서 [partIndex]번째 파트의 수치.
     * 분포 계산과 드릴다운이 **같은 함수**로 값을 꺼내야 두 수가 일치한다.
     */
    fun partValue(rawValue: String, separator: String, partIndex: Int): Float? {
        if (partIndex < 0) return null
        val parts = if (separator.isEmpty()) listOf(rawValue) else rawValue.split(separator)
        return parts.getOrNull(partIndex)?.trim()?.toFloatOrNull()
    }

    private fun firstUniqueDecimals(bounds: List<Pair<Float, Float>>): Int {
        // 경계가 정수가 아니면 0자리로 쓰지 않는다. 0 방향 절삭이라 -1.5는 "-1", 0.5는 "0"이 되어
        // **라벨이 실제 경계를 잘못 말하고**("0~0" 같은 뜻 모를 구간이 생긴다), 인접 구간이
        // 사람 눈에 구분되지 않는다. 유일성만 보는 것으로는 부족하다.
        val allIntegral = bounds.all { (lo, hi) ->
            lo == lo.toInt().toFloat() && hi == hi.toInt().toFloat()
        }
        for (decimals in (if (allIntegral) 0 else 1)..MAX_DECIMALS) {
            val labels = bounds.map { label(it.first, it.second, decimals) }
            if (labels.toSet().size == labels.size) return decimals
        }
        return MAX_DECIMALS
    }

    private fun format(value: Float, decimals: Int): String =
        if (decimals <= 0) {
            // 음수 절삭이 0을 "-0"으로 만들지 않게 한다
            val truncated = value.toInt()
            if (truncated == 0 && abs(value) < 1f) "0" else truncated.toString()
        } else {
            String.format("%.${decimals}f", value)
        }
}
