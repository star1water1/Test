package com.novelcharacter.app.data.model

/**
 * 캐릭터 커스텀 필드값 필터 조건. Global Search와 캐릭터 탭이 공유하는 단일 모델.
 *
 * - 한 FieldFilter 안의 [values]는 OR로 결합(하나라도 매칭하면 통과).
 * - 여러 FieldFilter는 AND로 결합(모두 만족해야 통과).
 *
 * @param matchMode "exact"(정확히 일치) | "contains"(부분 일치)
 */
data class FieldFilter(
    val fieldId: Long,
    val fieldName: String,
    val values: List<String>,
    val matchMode: String = "exact"
)
