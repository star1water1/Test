package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.StructuredInputConfig

/**
 * 캐릭터 목록 정렬을 위해 저장된 필드값(String)을 비교키로 변환하는 단일 소스.
 *
 * 수치형(NUMBER/GRADE/BODY_SIZE/CALCULATED)의 변환 규칙은 통계 랭킹(`StatsDataProvider.computeRanking`)과
 * **동일한 하위 헬퍼**(`GradeValueResolver`·`StructuredInputConfig`·`FormulaEvaluator`)를 재사용해 일치시킨다.
 * TEXT/SELECT/MULTI_TEXT는 목록 탐색 직관성을 위해 **가나다순**(통계의 빈도순과 의도적으로 다름).
 *
 * CALCULATED는 캐릭터별 전체 필드맵이 필요하므로 여기서 다루지 않고 호출부(ViewModel)가
 * `FormulaEvaluator`로 계산한다. [isNumericSortType]로 수치/문자 정렬 경로를 구분한다.
 */
object FieldValueSorter {

    /** 필드 타입이 수치 정렬 대상인지. (CALCULATED 포함 — 실제 계산은 호출부가 수행) */
    fun isNumericSortType(type: String): Boolean =
        type == "NUMBER" || type == "GRADE" || type == "BODY_SIZE" || type == "CALCULATED"

    /**
     * 수치형 필드값 → Double 비교키. 반드시 값이 속한 [ownerField](세계관별 config 차이 대응)로 해석한다.
     * 파싱 불가/빈 값은 null → 호출부가 최후순 배치. CALCULATED는 대상 아님(null 반환).
     */
    fun numericValue(ownerField: FieldDefinition, rawValue: String, bodySizePartIndex: Int?): Double? {
        if (rawValue.isBlank()) return null
        return when (ownerField.type) {
            "NUMBER" -> rawValue.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            "GRADE" -> GradeValueResolver.resolveFromConfig(ownerField, rawValue.trim())
            "BODY_SIZE" -> {
                val sic = StructuredInputConfig.fromConfig(ownerField.config)
                val partIdx = (bodySizePartIndex ?: 0).coerceAtLeast(0)
                val parts = if (sic.enabled) {
                    rawValue.split(sic.separator).map { it.trim() }
                } else {
                    rawValue.split(Regex("[-/\\s]+")).map { it.trim() }
                }
                parts.getOrNull(partIdx)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            }
            else -> null
        }
    }

    /**
     * 문자열형 필드값 → 가나다 비교키(소문자 정규화). MULTI_TEXT는 토큰 중 사전순 최소값 기준.
     * 빈 값은 null → 최후순.
     */
    fun textValue(field: FieldDefinition, rawValue: String): String? {
        val v = when (field.type) {
            "MULTI_TEXT" -> rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }.minOrNull()
            else -> rawValue.trim().takeIf { it.isNotEmpty() }
        }
        return v?.lowercase()
    }
}
