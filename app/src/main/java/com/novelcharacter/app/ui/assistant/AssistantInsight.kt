package com.novelcharacter.app.ui.assistant

/**
 * 어시스턴트가 사용자에게 능동적으로 제시하는 한 장의 카드.
 *
 * 2단 모델(설계): [InsightCategory.isError]가 true인 정합성 문제(진짜 모순)와
 * false인 제안·인사이트를 구분한다. 오류는 교정 경로를 붙여 진지하게, 제안은 부드럽게 다룬다.
 *
 * 행위자화(Phase A2): 카드는 **기본 액션 1개 + 부가 액션 여러 개**를 가진다. 기본은 눈에 띄는
 * 버튼으로, 부가 액션과 '숨기기'는 ⋮ 오버플로로 — 대처의 수는 늘리되(자율성) 화면은 안 어지럽게.
 *
 * 확장(원칙 01): 검사/카드 종류는 [InsightProvider] 추가만으로 늘어나고, 새 행동 유형은
 * [InsightAction]의 sealed 서브타입 추가만으로 붙는다(렌더는 타입 스위치라 무변경).
 */
data class AssistantInsight(
    /** 안정적 식별자 — 숨김/재노출 판정 키. 유형·대상 단위로 고정되어야 한다. */
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
    /** 카드의 대표 행동(눈에 띄는 버튼). null이면 기본 버튼 없음. */
    val primaryAction: InsightAction? = null,
    /** ⋮ 오버플로에 들어가는 부가 행동들. '숨기기'는 별도로 뷰가 항상 덧붙인다. */
    val secondaryActions: List<InsightAction> = emptyList(),
    /**
     * 이 카드가 대표하는 **영향 캐릭터 전체 목록**(대량 데이터에서 "대표 1명"만 처리되던 문제 해결).
     * N>1이면 기본 액션이 '전체 보기'가 되어 이 목록을 시트로 펼친다. 카드 키가 캐릭터가 아닌
     * 카드(중복 태그·빈 관계 설명·미사용 이름·편향 등)는 비어 있다.
     */
    val affected: List<AffectedRow> = emptyList(),
    val dismissible: Boolean = true
)

/**
 * '전체 보기' 시트의 한 행 = 영향받는 캐릭터 하나. 회전 안전을 위해 시트 arguments로 JSON 직렬화되므로
 * (프로젝트에 kotlin-parcelize 미도입) 평범한 직렬화 가능 데이터 클래스로 둔다.
 * @property subtitle 무엇이 문제인지 한 줄(예: "나이 25 · 출생 1470", "3일 뒤"). null이면 이름만.
 * @property fixType null이면 열기 전용, 아니면 시트 행에서 그 자리 교정 가능.
 */
data class AffectedRow(
    val characterId: Long,
    val name: String,
    val subtitle: String? = null,
    val fixType: AffectedFixType? = null,
    /** 첫 이미지 절대경로(내부 저장소). null이면 플레이스홀더. 시트 행 썸네일용. */
    val imagePath: String? = null,
    /** 시트 '최근 수정순' 정렬용(provider가 스냅샷에서 채움). null이면 그 정렬에서 뒤로. */
    val updatedAt: Long? = null
)

/** 시트 행에서 실행 가능한 교정 종류(그 외는 열기 전용). */
enum class AffectedFixType { AGE_LINKAGE, ASSIGN_NOVEL }

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
 * 카드의 행동 유도.
 * - [Navigate] : 대상 화면으로 이동(읽기 전용 접근).
 * - [Fix]      : 그 자리에서 교정(통계탭이 못 하는 차별점). 실제 적용은 프래그먼트가 디스패치.
 *
 * Phase B에서 `Suggest`(데이터 생성 제안) 등을 sealed 서브타입으로 추가해도 렌더는 그대로다.
 */
sealed interface InsightAction {
    val label: String

    /** [destId]는 nav_graph 목적지 id. [characterId]가 있으면 characterDetail 등에 인자로 전달. */
    data class Navigate(
        val destId: Int,
        val characterId: Long? = null,
        override val label: String
    ) : InsightAction

    /** 그 자리에서 해결하는 행동. [kind]가 어떤 교정인지 구분. */
    data class Fix(
        val kind: FixKind,
        override val label: String
    ) : InsightAction

    /** 영향 캐릭터 [AssistantInsight.affected] 전체를 시트로 펼쳐 각각 열기/교정한다. */
    data class ShowAffected(override val label: String) : InsightAction

    /** 교정 종류. 프래그먼트가 이 타입으로 분기해 다이얼로그/적용을 수행한다. */
    sealed interface FixKind {
        /** 나이-출생연도 모순 → 라이브 재검출 후 교정 다이얼로그. */
        data class AgeLinkage(val characterId: Long) : FixKind

        /** 작품 미배정 → 작품 선택 다이얼로그로 배정. [characterName]은 다이얼로그 제목용. */
        data class AssignNovel(val characterId: Long, val characterName: String) : FixKind
    }
}
