package com.novelcharacter.app.data.model

/**
 * 캐릭터 커스텀 필드값 필터 조건. Global Search와 캐릭터 탭이 공유하는 단일 모델이며,
 * 대결 후보 필터(B-168)도 **같은 언어**를 쓴다 — 필터 모델을 하나 더 만들면 규칙이 갈린다.
 *
 * - 한 FieldFilter 안의 [values]는 OR로 결합(하나라도 매칭하면 통과).
 * - 여러 FieldFilter는 AND로 결합(모두 만족해야 통과).
 *
 * @param matchMode "exact"(정확히 일치) | "contains"(부분 일치)
 * @param fieldKey 필드를 **안정적으로** 가리키는 키 (B-168). [fieldId]는 기기 이전·복원에서
 *   재발급되므로(엑셀 왕복의 `PortableFieldFilters`가 겪은 그 문제) 축에 저장되는 필터는
 *   이 키를 정본으로 삼고, id는 해석 시점에 다시 찾는다. `sys:` 접두면 **캐릭터 표의 시스템
 *   열**이다(`DuelSystemFields` — 필드가 아예 없어 id 자체가 없다). null이면 종전 규약
 *   그대로 [fieldId]가 정본이다(검색·캐릭터 탭 프리셋이 그 부류다 — Gson이 없는 속성을
 *   무시하므로 옛 JSON과 충돌하지 않는다).
 */
data class FieldFilter(
    val fieldId: Long,
    val fieldName: String,
    val values: List<String>,
    val matchMode: String = "exact",
    val fieldKey: String? = null
)
