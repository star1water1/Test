package com.novelcharacter.app.util

/**
 * 화면이 **한 번에 그리는 항목 수**의 상한 — 접는 장치이지 감추는 장치가 아니다(R-14 · R-19).
 *
 * 통계 탭의 명단·카드 자리들은 상한 없이 전량을 그리고 있었다. 그 목록은 전부
 * **캐릭터 축**이라(이미지 미등록·메모 미작성·별명 미작성·고립·사건 미연계·작품 미배정·
 * 완성도 미달·관계 고립) 목표 규모 ×30에서 한 목록이 6,420개의 `TextView`가 된다.
 * 그것들은 `NestedScrollView` 안이라 **재활용이 없고**, 만드는 일도 재는 일도 전부
 * 메인 스레드에서 한 번에 돈다.
 *
 * **상한을 두는 것만으로는 절반이다** — R-14가 요구하는 나머지 절반은 *잘라냈으면 개수로
 * 존재를 알리는 것*이고, R-19는 *누르면 그 내용을 볼 수 있어야 한다*를 더한다. 그래서 이 객체는
 * 두 가지 셈을 함께 든다: **무엇을 보일 것인가**와 **몇 개를 접었는가**.
 *
 * ### 왜 '펼치기'가 한 번에 전량이 아닌가 — [shownCount]
 *
 * 접기 자리의 흔한 모양은 *"더 보기 → 나머지 전부"*이고 이 저장소에도 이미 하나 있다
 * (`StatsDataOverviewFragment`의 필드 완성도 목록 — 그쪽은 **필드 축**이라 수십 개다).
 * 명단에 그 모양을 그대로 쓰면 **한 번의 탭이 6,420개를 만든다** — 상한을 두고도 사용자가
 * 누르는 순간 상한 이전으로 돌아가는 셈이다. 그래서 펼치기는 **묶음 단위로 는다**:
 * 어떤 탭도 [NAME_LIST_CHUNK]개보다 많이 만들지 않고, 그래도 **끝까지 갈 수 있다**
 * (감추지 않는다). 성능과 R-19를 함께 지키는 자리가 여기다.
 *
 * ### 상한 값은 이 파일이 단일 소스다
 *
 * R-14가 *"안내 문구에 숫자를 박아 두면 상한을 옮길 때 문구가 거짓이 된다"*고 못박은 그대로,
 * 화면 문구는 이 상수들로 채운다. 문구에 숫자를 적지 않는다.
 */
object DisplayCap {

    /**
     * 캐릭터 명단이 한 번에 그리는 수 — 첫 표시분이자 '더 보기' 한 번의 증가분.
     *
     * 50인 것은 **한 화면에 담기는 것보다 넉넉하되 한 번의 레이아웃으로 끝나는 수**라서다.
     * 실사용 표본(214명)에서는 대부분의 목록이 이 안에 들어와 종전과 화면이 같고,
     * ×30에서만 접힌다 — 즉 지금 쓰는 사람의 화면을 바꾸지 않으면서 규모를 막는다.
     */
    const val NAME_LIST_CHUNK = 50

    /**
     * 패턴 카드의 **축별** 상한 (캐릭터 · 사건 · 작품).
     *
     * 통째로 하나를 두지 않은 이유가 있다 — 카드는 심각도 순으로 서는데 캐릭터 축이 압도적으로
     * 많아서, 전체에 상한 하나를 걸면 **사건·작품 카드가 한 장도 못 뜨는 일이 보통이 된다.**
     * 그러면 상한이 "많아서 접었다"가 아니라 "그 축을 없앴다"가 된다.
     * 같은 탭의 타입 불일치 목록이 이미 축별 상한이다(`TypeMismatchList.DISPLAY_LIMIT_PER_OWNER`).
     */
    const val PATTERN_CARDS_PER_AXIS = 8

    /**
     * 교차 분석 표의 **행·열 각각**의 상한.
     *
     * 이 자리만 비용이 곱셈이다 — 셀 수가 `(값 종수 × 값 종수)`라, 자유 입력 필드 둘을 고르면
     * 캐릭터 수가 늘어난 만큼 **양쪽이 함께** 늘어난다(200종 × 200종 = 40,000칸).
     * 나머지 자리는 잘라도 안 잘라도 선형이지만 여기는 상한이 없으면 규모가 아니라 **차수**가 문제다.
     * 12는 한 화면에서 가로로 읽히는 열 수의 실질 한계이고, 넘는 것은 '기타'로 **합쳐서** 남는다
     * (버리지 않는다 — [CrossTableFold]가 합을 보존한다).
     */
    const val CROSS_AXIS_LIMIT = 12

    /**
     * 관계도가 한 번에 그리는 노드 수.
     *
     * **이 자리만 비용이 2차식이다** — 배치가 force-directed라 반복(n ≥ 38이면 300회)마다
     * **모든 노드 쌍**을 훑는다([GraphForceLayout]). 200에서 170 ms인 계산이 841에서 1,801 ms다
     * (데스크톱 JVM 실측 — 기기에서는 더 크다. `scalability_performance_2026-07.md` 3-12).
     *
     * **종전에는 상한이 아니라 술어였다.** 관여 캐릭터가 200을 넘으면 "요약 모드"로 내려갔는데
     * 그 모드가 남기는 것이 *연결 3개 이상인 캐릭터 **전부***라, **남는 수가 관계 수에 비례해
     * 함께 늘었다** — ×30에서 841명, 관계 밀도가 세 배면 4,660명이다. 상한을 넘어서 켜지는
     * 장치가 상한을 지키지 못했다.
     *
     * **뒤집히는 쪽도 같은 이유로 나빴다:** 술어라 노드 수가 **단조롭지 않았다** — ×3에서는
     * 관여 453명이 요약 모드를 켜고 **91명만** 남겼다. 상한을 넘지 않은 ×1의 158명보다 적다.
     * 즉 데이터가 늘었는데 그림이 성겨지는 구간이 있었다.
     *
     * 값이 종전 전환 문턱과 같은 200인 것은 **실사용 표본에서 화면이 달라지지 않게** 하기
     * 위해서다(관여 캐릭터 158명 — 상한 아래라 종전과 같은 그림이다).
     */
    const val GRAPH_NODE_LIMIT = 200

    /**
     * 상한 결과. [shown]은 **입력 순서**를 지킨다 — 이 자리에서 순서는 곧 그림이다
     * (관계도의 초기 배치가 노드 순서로 원을 그리므로, 순서를 바꾸면 남은 노드의 좌표까지 바뀐다).
     */
    data class Capped<T>(val shown: List<T>, val hiddenCount: Int, val totalCount: Int) {
        val hasHidden: Boolean get() = hiddenCount > 0
    }

    /**
     * [limit]개만 남기되 **무엇을 남길지는 [scoreOf] 내림차순**으로 고르고, 남은 것은
     * **입력 순서로** 돌려준다.
     *
     * 두 축을 가른 것이 이 함수의 요점이다 — *무엇을 남기는가*는 중요도(관계도에서는 연결 수)로,
     * *어떤 순서로 그리는가*는 입력 순서로 정한다. 골라낸 순서를 그대로 쓰면 상한에 걸리지 않은
     * 화면과 걸린 화면의 **배치 규칙이 달라진다.**
     *
     * [tieBreakOf]는 동점의 순서를 정한다 — **없으면 같은 데이터가 실행마다 다른 그림을 낸다.**
     * 관계도에서 연결 수 동점은 흔하다(연결 1개인 캐릭터가 대부분이다).
     *
     * 상한에 걸리지 않으면 [Capped.shown]은 입력 목록 그 자체다 — *"상한 아래에서는 한 픽셀도
     * 달라지지 않는다"*가 이 함수의 계약이고 시험이 그것을 든다.
     */
    fun <T> rankedCap(
        items: List<T>,
        limit: Int,
        scoreOf: (T) -> Int,
        tieBreakOf: (T) -> Long
    ): Capped<T> {
        if (limit <= 0) return Capped(emptyList(), items.size, items.size)
        if (items.size <= limit) return Capped(items, 0, items.size)
        val keep = items.indices.sortedWith(
            compareByDescending<Int> { scoreOf(items[it]) }.thenBy { tieBreakOf(items[it]) }
        ).take(limit).toHashSet()
        val shown = ArrayList<T>(limit)
        for (i in items.indices) if (i in keep) shown.add(items[i])
        return Capped(shown, items.size - limit, items.size)
    }

    /**
     * 축별 상한 결과. [hiddenByGroup]은 **첫 등장 순서**를 지킨다(입력 순서가 곧 표시 순서다).
     */
    data class GroupCapped<T, K>(
        val shown: List<T>,
        val hiddenByGroup: Map<K, Int>,
        val totalCount: Int
    ) {
        val hiddenCount: Int get() = hiddenByGroup.values.sum()
        val hasHidden: Boolean get() = hiddenCount > 0
    }

    /**
     * 축([groupOf])마다 따로 [limit]개씩 남기되 **원래 순서를 유지한다.**
     *
     * `groupBy` 뒤에 축마다 `take`하는 흔한 모양을 쓰지 않은 이유가 이것이다 — 그렇게 하면
     * 결과가 **축 순서로 재정렬되어** 심각도 순서가 깨진다. 패턴 카드는 심각도 내림차순으로
     * 서는 것이 그 목록의 뜻이므로(높음이 위), 순서를 건드리지 않고 축별 정원만 센다.
     */
    fun <T, K> capPerGroup(items: List<T>, limit: Int, groupOf: (T) -> K): GroupCapped<T, K> {
        if (limit <= 0) return GroupCapped(items, emptyMap(), items.size)
        val seen = HashMap<K, Int>()
        // 용량은 **입력 크기**로 잡는다. `limit × 축 수`로 어림하고 싶어지는 자리인데,
        // 상한을 크게 잡으면 그 곱이 `Int`를 넘어 음수가 되고 `ArrayList(음수)`는 **던진다** —
        // 지금 상수(8)로는 안 나지만 상한을 옮기는 순간 화면이 통째로 죽는 부류다.
        val shown = ArrayList<T>(items.size)
        val hidden = LinkedHashMap<K, Int>()
        for (item in items) {
            val key = groupOf(item)
            val used = seen.getOrDefault(key, 0)
            if (used < limit) {
                seen[key] = used + 1
                shown.add(item)
            } else {
                hidden[key] = (hidden[key] ?: 0) + 1
            }
        }
        return GroupCapped(shown, hidden, items.size)
    }

    /**
     * 묶음 단위 펼치기에서 **지금 몇 개가 보이는가.**
     *
     * [steps]는 사용자가 '더 보기'를 누른 횟수다(0 = 첫 표시). 언제나 [total] 이하이고,
     * 한 번의 증가분은 정확히 [chunk]다 — 이 함수가 도는 자리가 곧 *"한 탭이 만드는 뷰 수"*라,
     * 여기가 틀리면 상한을 둔 의미가 없어진다.
     */
    fun shownCount(total: Int, chunk: Int, steps: Int): Int {
        if (chunk <= 0) return total
        val wanted = chunk.toLong() * (steps.coerceAtLeast(0).toLong() + 1L)
        return if (wanted >= total) total else wanted.toInt()
    }
}
