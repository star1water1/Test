package com.novelcharacter.app.ui.assistant

/**
 * 어시스턴트 카드 목록의 **보기 정렬**(B-85).
 *
 * ## 비파괴 계약 — 이 파일이 지키는 단 하나의 불변식
 * 정렬은 **보는 순서만** 바꾼다. 어떤 카드가 목록에 있는지는 절대 바꾸지 않는다.
 * 그래서 [sort]는 받은 목록을 그대로 재배열해 돌려줄 뿐 걸러내지 않는다.
 *
 * 이 계약이 중요한 이유는 [AssistantViewModel]의 편향 카드 상한(`capBiasCards`) 때문이다.
 * 그 상한은 **심각도순 목록에서 상위 N장**을 남기므로, 정렬을 상한보다 **먼저** 걸면
 * 고른 정렬에 따라 살아남는 편향 카드가 달라진다 — 즉 정렬이 목록의 **내용**을 바꾼다.
 * 그래서 호출 순서는 반드시 **상한 → 정렬**이며, 그 순서는 뷰모델이 지킨다.
 *
 * ## 확장(원칙 01)
 * 새 정렬 기준은 [Mode]에 상수 하나와 [comparator]의 분기 하나를 더하면 끝난다 —
 * 라벨은 프래그먼트가 `sortLabelRes`로 붙이므로 이 계층은 안드로이드를 모른다(순수 JVM 검증 대상).
 */
object AssistantSort {

    /** 정렬 기준. [SEVERITY]가 기본이며 종전(엔진 정렬) 동작과 정확히 같다. */
    enum class Mode { SEVERITY, CATEGORY, COUNT }

    val DEFAULT = Mode.SEVERITY

    /** 저장값 → [Mode]. 모르는 값(구버전·손상)은 기본으로 떨어뜨린다 — 정렬 하나 때문에 화면이 죽지 않는다. */
    fun parse(raw: String?): Mode =
        Mode.values().firstOrNull { it.name == raw } ?: DEFAULT

    /**
     * [list]를 [mode]대로 재배열한다. **원소는 하나도 늘지도 줄지도 않는다**(위 비파괴 계약).
     *
     * 모든 기준의 최종 타이브레이크는 `severity DESC → id ASC`다. id까지 가면 완전 순서라
     * 같은 데이터에서 같은 화면이 나오고(회전·새로고침에서 카드가 들썩이지 않는다),
     * 기본 기준은 종전 엔진 정렬과 한 글자도 다르지 않다.
     */
    fun sort(list: List<AssistantInsight>, mode: Mode): List<AssistantInsight> =
        list.sortedWith(comparator(mode))

    fun comparator(mode: Mode): Comparator<AssistantInsight> {
        // 종전 엔진 정렬과 동일 — 모든 기준이 이것을 마지막 타이브레이크로 깐다.
        val bySeverity = compareByDescending<AssistantInsight> { it.severity }.thenBy { it.id }
        return when (mode) {
            Mode.SEVERITY -> bySeverity
            // 분류 순서는 InsightCategory의 선언 순서(정합성 → 건강 → 편향 → 넛지)를 그대로 쓴다 —
            // 별도 순위표를 두면 분류가 늘 때 한쪽만 갱신돼 갈린다.
            Mode.CATEGORY -> compareBy<AssistantInsight> { it.category.ordinal }.then(bySeverity)
            // 항목 수가 많은 카드 = 한 번에 가장 많이 정리되는 카드. 0(개수 없는 카드)은 자연히 뒤로 간다.
            Mode.COUNT -> compareByDescending<AssistantInsight> { it.count }.then(bySeverity)
        }
    }
}
