package com.novelcharacter.app.util

/**
 * 차트 조각 → 엔티티 목록 드릴다운의 **매칭 규칙**을 값이 아니라 스펙으로 넘기기 위한 타입 (S-16).
 *
 * 종전에는 화면에 보이는 **라벨 문자열**이 곧 매칭 키였다. 라벨이 파싱된 값과 우연히 같은
 * 경우(SELECT·TEXT의 값 그대로, NUMBER 커스텀 구간 라벨)에는 동작했지만, 라벨이 계산 결과인
 * 경우(BODY_SIZE 파트별 자동 구간)에는 어떤 입력에서도 일치하지 않아 **항상 0명**이 나왔다.
 * 우연한 일치에 기대는 대신, 분포를 만든 쪽이 자기가 쓴 규칙을 그대로 실어 보낸다.
 */
sealed interface FieldValueMatchSpec {

    /**
     * 파싱된 값 중 하나라도 [values]에 들어가면 일치.
     *
     * 조각 하나를 누른 경우는 원소 1개, 접힌 '기타' 묶음을 누른 경우는 잘린 값 전부다
     * (개수만 알리고 내용을 볼 수 없으면 R-14를 절반만 지키는 것이다).
     */
    data class Values(val values: Set<String>) : FieldValueMatchSpec {
        constructor(single: String) : this(setOf(single))
    }

    /**
     * 원문을 [separator]로 쪼갠 [partIndex]번째 수치가 구간에 들어가면 일치.
     *
     * 경계 규칙은 [NumericBinning.Bin]과 **같아야 한다** — 마지막 구간만 상한을 포함한다.
     * 분포를 만든 구간과 다른 규칙으로 세면 조각의 수와 목록의 인원이 어긋난다.
     */
    data class NumericPartRange(
        val partIndex: Int,
        val separator: String,
        val min: Float,
        val max: Float,
        val inclusiveMax: Boolean
    ) : FieldValueMatchSpec

    /**
     * 원문의 [partIndex]번째 수치가 **[ranges] 어디에도 들지 않으면** 일치 — 사용자 구간의
     * 여집합이다 (B-197).
     *
     * **왜 값 일치([Values])로 안 되는가:** 사용자 구간을 쓰는 NUMBER·CALCULATED는 통계 파싱이
     * 구간 밖 값을 `구간 밖` 라벨로 돌려주므로 라벨 일치가 *우연히* 성립한다. 그런데 히스토그램의
     * 사용자 구간 분기는 **BODY_SIZE 파트에서도 돈다**(그 타입은 `isBinnable`이 아니라 파싱이
     * 그 라벨을 만들지 않는다). 라벨로 실으면 **한쪽만 조용히 0명이 된다** — S-16이 자동 구간
     * 라벨에서 겪은 그 결함이라, 규칙을 실어 보내는 같은 처방을 쓴다.
     *
     * **수로 읽히지 않는 값은 일치하지 않는다.** 히스토그램의 모집단이 [NumericBinning.numericValuesOf]가
     * 뽑은 수치뿐이라, 여기서 비수치를 집으면 막대 높이보다 목록이 길어진다(레거시 분포는 비수치도
     * 같은 라벨에 담지만 그쪽 모집단은 파싱 키 전량이라 자기 막대와는 맞는다).
     */
    data class NumericPartOutsideRanges(
        val partIndex: Int,
        val separator: String,
        val ranges: List<NumericPartRange>
    ) : FieldValueMatchSpec {
        init {
            // 여집합이 *무엇의* 여집합인지가 갈리면 조각의 수와 목록의 인원이 어긋난다.
            require(ranges.all { it.partIndex == partIndex && it.separator == separator }) {
                "여집합 스펙의 구간은 같은 파트·구분자여야 한다"
            }
        }
    }

    companion object {
        /** 구간에서 스펙을 만든다 — 분포 생성 지점이 이 함수로만 스펙을 만들게 해 규칙 이탈을 막는다. */
        fun of(bin: NumericBinning.Bin, partIndex: Int, separator: String): NumericPartRange =
            NumericPartRange(partIndex, separator, bin.min, bin.max, bin.inclusiveMax)

        /**
         * 이미 만든 구간 스펙들의 여집합 — **분포를 그린 그 스펙 목록을 그대로 받는다.**
         * 구간을 다시 짓지 않는 것이 요점이다(다시 지으면 경계 규칙이 두 벌이 된다).
         */
        fun outside(
            ranges: List<NumericPartRange>,
            partIndex: Int,
            separator: String
        ): NumericPartOutsideRanges = NumericPartOutsideRanges(partIndex, separator, ranges)
    }
}

/**
 * 스펙 판정의 단일 소스. 저장 원문([rawValue])과 통계 파싱 결과([parsedValues])를 모두 받는다 —
 * 값 공간 매칭은 파싱 결과를, 수치 구간 매칭은 원문을 봐야 하기 때문이다.
 */
object FieldValueMatcher {

    /**
     * [parsedValues]는 **필요할 때만** 계산되도록 람다로 받는다 — 수치 구간 매칭은 원문만
     * 보면 되는데, 값 하나를 확인하려고 전체 테이블을 토큰화·별칭 해석하는 비용을 치를 이유가 없다.
     */
    fun matches(
        spec: FieldValueMatchSpec,
        rawValue: String,
        parsedValues: () -> List<String>
    ): Boolean = when (spec) {
        is FieldValueMatchSpec.Values ->
            parsedValues().any { it in spec.values }

        is FieldValueMatchSpec.NumericPartRange -> {
            val v = NumericBinning.partValue(rawValue, spec.separator, spec.partIndex)
            v != null && v >= spec.min && (if (spec.inclusiveMax) v <= spec.max else v < spec.max)
        }

        // 경계 판정을 되풀어 적지 않고 **위 갈래에 되물어** 여집합을 낸다 — 상한 포함 규칙이
        // 언젠가 바뀌어도 여집합이 저절로 따라온다(따로 적으면 그날 둘이 갈린다).
        is FieldValueMatchSpec.NumericPartOutsideRanges ->
            NumericBinning.partValue(rawValue, spec.separator, spec.partIndex) != null &&
                spec.ranges.none { matches(it, rawValue, parsedValues) }
    }

    /** 이미 파싱된 값이 있을 때 쓰는 형태. */
    fun matches(
        spec: FieldValueMatchSpec,
        rawValue: String,
        parsedValues: List<String>
    ): Boolean = matches(spec, rawValue) { parsedValues }
}
