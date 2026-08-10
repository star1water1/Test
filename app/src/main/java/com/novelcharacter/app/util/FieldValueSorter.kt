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
     * 문자열형 필드값 → 가나다 비교키(소문자 정규화).
     * **여러 토큰을 담는 필드는 토큰 중 사전순 최소값**이 대표값이다. 빈 값은 null → 최후순.
     *
     * **쪼갤지 말지를 여기서 판단하지 않는다 (B-37).** 종전에는 `field.type == "MULTI_TEXT"`를
     * 직접 견줘, **쉼표 목록 표시 형식의 TEXT가 `"검, 활"` 통문자열로 정렬**됐다 — 같은 값이
     * 통계에서는 두 토큰인데 목록 정렬에서는 하나였다(S-18이 순위에서 없앤 하드코딩의 쌍둥이).
     * 이제 [FieldValueTokenizer]가 그 판단의 단일 소스이므로, 다중 토큰 타입이 늘어도
     * 이 함수는 따라 움직인다.
     *
     * **대표값 규칙은 바뀌지 않았다** — `minOrNull()`은 종전 `MULTI_TEXT`가 쓰던 그대로이고
     * (KDoc도 *"토큰 중 사전순 최소값"*이라 적어 두었다), 확정 7-5가 그것을 나머지 타입으로
     * 넓히기로 정했다. 그래서 **기존 `MULTI_TEXT` 필드의 정렬 결과는 하나도 안 바뀐다** —
     * 기각된 쪽(맨 앞 토큰)이었다면 전부 조용히 달라졌을 자리다.
     */
    fun textValue(field: FieldDefinition, rawValue: String): String? =
        FieldValueTokenizer.tokenize(field, rawValue).minOrNull()?.lowercase()
}
