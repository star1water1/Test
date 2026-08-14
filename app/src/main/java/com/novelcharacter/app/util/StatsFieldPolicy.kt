package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig

/**
 * '통계에 포함'([FieldStatsConfig.enabled]) 설정을 해석하는 **단일 소스**.
 *
 * 종전에는 이 판정이 화면마다 따로 있었다 — 인사이트 목록·순위 목록·요약 TOP5는 걸렀고,
 * 패턴 인사이트·레거시 필드 분석·교차분석 그룹 확장은 걸르지 않아서, 같은 필드를 끈 사용자가
 * 화면마다 다른 답을 받았다(S-14·B-35). 판정은 여기 하나뿐이어야 한다.
 *
 * ## 규칙: 자동 선택은 설정을 따르고, 명시적 선택은 존중한다
 *
 * - **앱이 스스로 필드를 고르는 경로**(패턴 감지, 인사이트 목록, 레거시 분포, 요약 TOP5,
 *   순위 가능 필드 목록)는 [analyzable]로 거른다. 끈 필드가 스스로 나타나서는 안 된다.
 * - **사용자가 목록에서 직접 고른 필드**(교차분석 축, 순위 대상, 드릴다운)는 다시 거르지 않는다.
 *   목록이 이미 걸러진 것이고, 계산 단계가 한 번 더 걸러 빈 결과를 내면 "고를 수 있는데
 *   아무 일도 일어나지 않는" 조용한 실패가 된다(R-17).
 * - 그 사이에 있는 것이 **자동 확장**이다: 사용자가 고른 것은 필드 하나인데 계산은 같은
 *   (key, type)의 형제 세계관 def까지 합산한다. 형제는 사용자가 고른 적이 없으므로
 *   설정을 따라야 한다 — [expandGroup]이 그 규칙이다(고른 def는 설정과 무관하게 남긴다).
 *
 * ## 이 판정을 쓰지 않는 곳과 그 이유
 *
 * - **필드 완성도·데이터 건강**은 '입력 현황'이지 '분석'이 아니다. 메모성 필드를 통계에서
 *   뺀 사용자가 그 필드의 입력 누락까지 안 보이길 원한다고 볼 근거가 없으므로 거르지 않는다.
 *   (예외를 둘 때는 이유를 코드에 적는다 — R-16의 규약.)
 *
 * ## 파싱 캐시는 여기 없다
 *
 * 종전의 `ConfigCache`(호출부마다 새로 만드는 def당 1회 캐시)는 B-215가 걷었다 — 통계
 * 경로의 config 정본은 이제 StatsDataProvider의 스냅샷 단위 캐시(`statsConfigsOf`)이고,
 * 호출부별 인스턴스는 그 아래의 (파싱 def, 원문) 메모가 서지 못하게 막는 바로 그 갈림이었다.
 */
object StatsFieldPolicy {

    /** 자동 집계 경로에서 이 필드를 분석 대상으로 삼는가. */
    fun isAnalyzable(fd: FieldDefinition): Boolean =
        FieldStatsConfig.fromConfig(fd.config).enabled

    /** 자동 집계 경로용 필터. config 파싱은 def당 1회. */
    fun analyzable(defs: List<FieldDefinition>): List<FieldDefinition> =
        defs.filter { isAnalyzable(it) }

    /**
     * 사용자가 고른 [pickedId]의 (key, type) 그룹을 돌려준다 —
     * **고른 def는 설정과 무관하게 포함**하고, 자동으로 딸려오는 형제만 설정을 따른다.
     *
     * 고른 def를 항상 첫 원소로 둔다: 그룹 전체의 파싱 기준 def가 첫 원소라는 계약(R-15)을
     * 지키기 위해서다. 고른 def가 목록에 없으면 빈 목록 — 호출부가 "필드를 찾을 수 없음"으로
     * 고지한다(R-17: 못 찾음과 없음은 다르다).
     */
    fun expandGroup(defs: List<FieldDefinition>, pickedId: Long): List<FieldDefinition> {
        val picked = defs.find { it.id == pickedId } ?: return emptyList()
        val siblings = defs.filter {
            it.id != picked.id && it.key == picked.key && it.type == picked.type && isAnalyzable(it)
        }
        return listOf(picked) + siblings
    }

}
