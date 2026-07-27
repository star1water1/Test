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

    companion object {
        /** 구간에서 스펙을 만든다 — 분포 생성 지점이 이 함수로만 스펙을 만들게 해 규칙 이탈을 막는다. */
        fun of(bin: NumericBinning.Bin, partIndex: Int, separator: String): NumericPartRange =
            NumericPartRange(partIndex, separator, bin.min, bin.max, bin.inclusiveMax)
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
    }

    /** 이미 파싱된 값이 있을 때 쓰는 형태. */
    fun matches(
        spec: FieldValueMatchSpec,
        rawValue: String,
        parsedValues: List<String>
    ): Boolean = matches(spec, rawValue) { parsedValues }
}
