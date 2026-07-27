package com.novelcharacter.app.util

/**
 * 값 분포의 **상한 적용과 비율 계산**을 한곳에 모은 순수 계산 (S-17, 규약 R-14).
 *
 * 종전에는 계산 계층([StatsDataProvider.computeFieldDistribution])이 상위 N개만 남기고
 * 나머지를 **버린 뒤** 화면에 넘겼다. 그래서
 *
 * - 비율(%)의 분모가 '상위 N개의 합'이 되어 실제 점유율보다 부풀려졌고
 *   (거주지 30종 중 상위 10종만 남으면 10종의 합이 100%가 된다),
 * - 몇 종·몇 건이 잘렸는지 **알 길 자체가 사라졌다**.
 *
 * 상한은 감추는 장치가 아니라 접는 장치다(R-14). 그래서 분포는 **전량**을 들고 다니고,
 * 자르는 일은 표시 직전에 [view]가 한 번만 하며, 잘린 것은 항상 종수·건수로 함께 보고한다.
 */
object ValueDistributions {

    /** 인사이트/레거시 분포 화면이 한 번에 그리는 값의 기본 상한. 문구는 이 상수로 채운다(R-14). */
    const val DEFAULT_DISPLAY_LIMIT = 10

    /** 값 목록 → 내림차순 전체 분포(동수는 값 이름 오름차순으로 안정 정렬). */
    fun of(values: List<String>): Map<String, Int> =
        values.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associateTo(LinkedHashMap()) { it.key to it.value }

    /** 이미 집계된 분포를 같은 규칙으로 재정렬한다(합산·병합 뒤 순서를 고정할 때). */
    fun sorted(distribution: Map<String, Int>): Map<String, Int> =
        distribution.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associateTo(LinkedHashMap()) { it.key to it.value }

    /**
     * 전체 분포에 표시 상한을 적용한 결과.
     *
     * @param shown 화면에 그릴 상위 항목
     * @param hiddenLabels 잘려 나간 값들 — '기타' 조각을 눌렀을 때 그대로 드릴다운 인자가 된다
     *                     (개수만 알리고 내용을 못 보는 것은 R-14의 절반만 지키는 것이다)
     * @param totalCount 잘리기 **전** 전체 건수. 모든 비율의 분모다.
     */
    data class View(
        val shown: List<Slice>,
        val hiddenLabels: List<String>,
        val hiddenCount: Int,
        val totalCount: Int
    ) {
        val hiddenKinds: Int get() = hiddenLabels.size
        val hasHidden: Boolean get() = hiddenLabels.isNotEmpty()

        /** 전체 대비 비율(%). 분모는 언제나 [totalCount] — 잘라낸 뒤의 합이 아니다. */
        fun ratioOf(count: Int): Float =
            if (totalCount > 0) count * 100f / totalCount else 0f

        /** '기타' 묶음의 비율(%). */
        fun hiddenRatio(): Float = ratioOf(hiddenCount)

        /** 표시분만 담은 맵 — 차트처럼 (라벨, 값) 쌍만 필요한 곳에 준다. */
        fun shownMap(): Map<String, Int> =
            shown.associateTo(LinkedHashMap()) { it.label to it.count }
    }

    data class Slice(val label: String, val count: Int)

    /**
     * 전체 분포에서 상위 [limit]개만 남기고 나머지를 '기타'로 접는다.
     *
     * [limit]이 0 이하이면 상한이 없는 것으로 보고 전량을 표시한다 — 상한을 끄는 설정이
     * 조용히 "아무것도 안 보임"이 되지 않게 하기 위해서다.
     */
    fun view(
        distribution: Map<String, Int>,
        limit: Int,
        preserveOrder: Boolean = false
    ): View {
        // [preserveOrder]는 **순서 자체가 정보인 분포**를 위한 것이다 — 수치 구간 분포를 건수순으로
        // 재정렬하면 인접 구간이 흩어져 '어디에 몰렸는가'를 읽을 수 없다.
        // 정렬은 여기서 한 번만 한다(맵 사본을 만들지 않는다 — 이 함수는 카드마다 메인 스레드에서 돈다).
        val ordered = if (preserveOrder) {
            distribution.entries.map { Slice(it.key, it.value) }
        } else {
            distribution.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { Slice(it.key, it.value) }
        }
        val total = ordered.sumOf { it.count }
        if (limit <= 0 || ordered.size <= limit) {
            return View(ordered, emptyList(), 0, total)
        }
        val shown = ordered.take(limit)
        val hidden = ordered.drop(limit)
        return View(
            shown = shown,
            hiddenLabels = hidden.map { it.label },
            hiddenCount = hidden.sumOf { it.count },
            totalCount = total
        )
    }
}
