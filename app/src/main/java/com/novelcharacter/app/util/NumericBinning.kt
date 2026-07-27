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
        for (decimals in (if (allIntegral) 0 else 1)..3) {
            val labels = bounds.map { label(it.first, it.second, decimals) }
            if (labels.toSet().size == labels.size) return decimals
        }
        return 3
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
