package com.novelcharacter.app.ui.assistant

import android.content.Context
import com.novelcharacter.app.ui.stats.DataHealthStats
import com.novelcharacter.app.ui.stats.PatternInsight
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import com.novelcharacter.app.util.ConsistencyChecker

/**
 * 어시스턴트의 인사이트 생성기 하나. 독립적으로 [AssistantInsight] 목록을 만든다.
 *
 * 확장(원칙 01): 새 검사·카드는 provider를 추가하는 것만으로 늘어난다. 각 provider는
 * 다른 provider를 몰라도 되고, 엔진은 결과를 심각도로만 병합한다(가산적, 테스트 용이).
 */
interface InsightProvider {
    val category: InsightCategory
    fun provide(ctx: InsightContext): List<AssistantInsight>
}

/**
 * 모든 provider가 공유하는 입력. 무거운 계산(스냅샷 로드·건강·패턴·정합성)은
 * 엔진이 한 번만 수행해 여기에 담아 넘긴다 — provider가 각자 다시 계산하지 않는다(성능).
 */
data class InsightContext(
    val context: Context,
    val snapshot: StatsSnapshot,
    val dataHealth: DataHealthStats,
    val consistency: ConsistencyChecker.Result,
    val patterns: List<PatternInsight>,
    /** 분포·필드값 해석 등 추가 계산이 필요한 provider(편향 드릴다운 등)를 위한 재사용 진입점. */
    val statsProvider: StatsDataProvider
)

/** 카드 정렬용 심각도. 정합성 오류가 항상 최상단에 오도록 배치한다. */
object InsightSeverity {
    const val CONSISTENCY = 100

    const val NUDGE_BIRTHDAY = 65
    const val BIAS_HIGH = 60
    const val HEALTH_NO_NOVEL = 55
    const val BIAS_MEDIUM = 45
    const val HEALTH_ISOLATED = 44
    const val HEALTH_UNLINKED = 40
    const val HEALTH_DUP_TAGS = 38
    const val HEALTH_EMPTY_REL = 30
    const val HEALTH_NO_IMAGE = 28
    const val BIAS_LOW = 25
    const val NUDGE_STALE = 22
    const val NUDGE_UNUSED_NAMES = 18
    const val BIAS_INFO = 15
}

/**
 * 플러그형 provider 레지스트리. 스냅샷 1개로 공통 입력을 한 번 계산한 뒤,
 * 활성 카테고리의 provider만 돌려 결과를 심각도순으로 병합한다.
 *
 * [statsProvider]는 재사용 자산: 스냅샷 로드·건강도·패턴 계산 로직을 어시스턴트가
 * 그대로 호출한다(로직 중복 없음).
 */
class AssistantEngine(
    private val context: Context,
    private val providers: List<InsightProvider> = defaultProviders()
) {

    fun run(
        snapshot: StatsSnapshot,
        statsProvider: StatsDataProvider,
        enabledCategories: Set<InsightCategory>
    ): List<AssistantInsight> {
        val ctx = InsightContext(
            context = context,
            snapshot = snapshot,
            dataHealth = statsProvider.computeDataHealth(snapshot),
            consistency = ConsistencyChecker.check(snapshot),
            patterns = statsProvider.detectPatterns(snapshot),
            statsProvider = statsProvider
        )
        return providers
            .filter { it.category in enabledCategories }
            .flatMap { runCatching { it.provide(ctx) }.getOrDefault(emptyList()) }
            .sortedWith(compareByDescending<AssistantInsight> { it.severity }.thenBy { it.id })
    }

    companion object {
        fun defaultProviders(): List<InsightProvider> = listOf(
            ConsistencyProvider(),
            HealthProvider(),
            BiasProvider(),
            NudgeProvider()
        )
    }
}
