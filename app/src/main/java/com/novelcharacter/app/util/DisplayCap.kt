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
     * 상한을 적용한 결과.
     *
     * @param shown 그릴 항목
     * @param hiddenCount 접힌 수 — 화면이 이 수를 그대로 말해야 R-14를 지킨다
     * @param totalCount 자르기 **전** 전체 수. 비율의 분모는 언제나 이것이다(R-19).
     */
    data class Capped<T>(
        val shown: List<T>,
        val hiddenCount: Int,
        val totalCount: Int
    ) {
        val hasHidden: Boolean get() = hiddenCount > 0
    }

    /**
     * 앞에서부터 [limit]개만 남긴다.
     *
     * [limit]이 0 이하이면 상한이 없는 것으로 본다 — 상한을 끄는 설정이 조용히
     * "아무것도 안 보임"이 되지 않게 하기 위해서다([ValueDistributions.view]와 같은 규칙).
     */
    fun <T> cap(items: List<T>, limit: Int): Capped<T> =
        if (limit <= 0 || items.size <= limit) {
            Capped(items, 0, items.size)
        } else {
            Capped(items.take(limit), items.size - limit, items.size)
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
        val shown = ArrayList<T>(minOf(items.size, limit * 4))
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
