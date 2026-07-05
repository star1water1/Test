package com.novelcharacter.app.ui.assistant

/**
 * 어시스턴트가 사용자에게 능동적으로 제시하는 한 장의 카드.
 *
 * 2단 모델(설계): [InsightCategory.isError]가 true인 정합성 문제(진짜 모순)와
 * false인 제안·인사이트를 구분한다. 오류는 교정 경로를 붙여 진지하게, 제안은 부드럽게 다룬다.
 *
 * 확장(원칙 01): 검사/카드 종류는 [InsightProvider]를 추가하는 것만으로 늘어난다.
 * 이 데이터 클래스는 어떤 provider가 만들었는지 모른 채 심각도로만 병합·정렬된다.
 */
data class AssistantInsight(
    /** 안정적 식별자 — 숨김/재노출 판정 키. 유형·필드 단위로 고정되어야 한다. */
    val id: String,
    val category: InsightCategory,
    /** 정렬용. 높을수록 위로. 정합성 오류가 항상 최상단에 오도록 크게 준다. */
    val severity: Int,
    val title: String,
    val detail: String,
    /** 이 카드가 대표하는 항목 수(예: 관련 캐릭터 N명). 0이면 개수 배지 없음. */
    val count: Int = 0,
    /**
     * 재노출 판정값. 숨긴 시점보다 이 값이 커지면(상황 악화) 다시 나타난다.
     * 기본은 [count]. 편향 카드처럼 개수가 없는 경우 provider가 심각도 등으로 대체한다.
     */
    val resurfaceValue: Int = count,
    val action: InsightAction? = null,
    val dismissible: Boolean = true
)

/**
 * 인사이트 분류. 배지·정렬·카테고리 on/off의 단위이자 2단 모델의 기준.
 * @property isError 진짜 모순(정합성 오류)인지. true면 탭 배지 집계 대상.
 */
enum class InsightCategory(val isError: Boolean) {
    /** 정합성 문제 — 나이-출생연도 모순, 사망<출생 등 실제 오류. 교정 우선. */
    CONSISTENCY(true),

    /** 데이터 건강 제안 — 관계·사건·이미지 공백 등. 편집 판단이므로 부드럽게. */
    HEALTH(false),

    /** 편향·패턴 인사이트 — 필드 값 쏠림, 사건 밀도 등 군상 조감. 실질적 인사이트의 핵심. */
    BIAS(false),

    /** 넛지 — 임박한 생일, 미사용 이름, 오래 방치된 캐릭터 등 상황 알림. */
    NUDGE(false)
}

/**
 * 카드의 행동 유도. Phase A는 화면 이동만 다룬다(교정 자체는 기존 화면에서).
 * INLINE_FIX 등은 후속 Phase에서 sealed 서브타입 추가로 확장한다(원칙 01).
 */
sealed interface InsightAction {
    val label: String

    /**
     * 대상 화면으로 이동. [destId]는 nav_graph 목적지 id.
     * [characterId]가 있으면 characterDetailFragment 등에 인자로 전달된다.
     */
    data class Navigate(
        val destId: Int,
        val characterId: Long? = null,
        override val label: String
    ) : InsightAction
}
