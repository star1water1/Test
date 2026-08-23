package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe

/**
 * 작품 축 차트의 **라벨을 짓는 단일 소스** — 키는 `novelId`, 라벨은 표시 직전에 붙인다 (R-20).
 *
 * **왜 필요한가 — 제목을 맵 키로 쓰면 작품이 사라진다.** 통계의 작품 축 둘이 정확히 그
 * 모양이었고, 같은 실수가 증상만 다르게 났다:
 *
 * - `novelCharacterCounts`는 `groupBy { novelId }.mapKeys { 제목 }`이라 **뒤가 앞을 덮었다**.
 *   세계관 둘에 각각 '외전'이 있고 12명·7명이면 막대가 **하나만** 뜨고 값은 7이었다 —
 *   12명짜리 작품은 막대도 고지도 없이 없어졌다(`mapKeys`는 합치지 않고 덮어쓴다).
 * - `novelEventCounts`는 `counts[제목] = counts[제목] + 1`이라 **합쳐졌다**. 5건·8건짜리
 *   동명 작품 둘이 13건 막대 하나가 됐다 — 작품별 서사 규모를 견주려던 화면이 정확히
 *   그 비교를 못 하게 만들었다.
 *
 * 둘 다 합성 라벨 `"미지정"`을 **실제 제목과 같은 키 공간**에 두는 문제도 함께 있었다:
 * 작품 제목을 '미지정'으로 지은 사용자는 그 작품의 수에 미배정·미연결 몫까지 더해진 값을 봤다.
 *
 * 이 저장소는 같은 부류를 이미 규약으로 못박고(R-20 *"라벨은 매칭 키가 아니다"*) 세력 통계에서
 * 한 번 고쳤다(`StatsDataProvider`가 *"이름으로 접으면 동명 세력의 묶음이 서로를 덮어 멤버가
 * 통째로 사라진다"*며 id 키로 옮긴 자리). 작품 축만 그 처방에서 빠져 있었다.
 *
 * **계약:** 키 `null`은 *소속 없음*(미배정 캐릭터 · 작품 미연결 사건)이고, 실재하는 작품 id와
 * 절대 부딪치지 않는다. 돌려주는 라벨은 **입력 안에서 유일하다** — 유일하지 않으면 차트가
 * 다시 같은 자리에서 무너진다.
 */
object NovelAxisLabels {

    /**
     * `novelId → 차트에 적을 라벨`. 소속 없음 몫(`null` 키)은 [unassignedLabel]로 나간다.
     *
     * 동명 작품은 **세계관명을 덧붙여** 가른다(인사이트 카드가 같은 필드 이름을 가를 때 쓰는
     * 방식과 같다). 세계관까지 같거나 세계관이 없으면 마지막으로 **id를 덧붙인다** — 라벨이
     * 못생겨지더라도 **두 작품이 한 막대로 접히는 것보다는 낫다**(원칙 02: 겉보기보다 실질).
     *
     * @param novels 라벨을 붙일 작품 — 스코프가 걸린 목록을 그대로 넘긴다.
     * @param universes 동명 가르기에 쓸 세계관. 못 찾으면 세계관 칸 없이 간다.
     * @param unassignedLabel `null` 키에 적을 문구(호출부가 `strings.xml`에서 가져온다).
     */
    fun of(
        novels: List<Novel>,
        universes: List<Universe>,
        unassignedLabel: String
    ): Map<Long?, String> {
        val universeNames = universes.associate { it.id to it.name }
        val titleCounts = novels.groupingBy { it.title }.eachCount()

        val out = LinkedHashMap<Long?, String>(novels.size + 1)
        out[null] = unassignedLabel

        val used = HashSet<String>()
        used.add(unassignedLabel)
        for (novel in novels) {
            var label = novel.title
            if ((titleCounts[novel.title] ?: 0) > 1) {
                val universeName = novel.universeId?.let { universeNames[it] }?.takeIf { it.isNotBlank() }
                if (universeName != null) label = "${novel.title} ($universeName)"
            }
            // 세계관까지 같은 동명, 세계관이 없는 동명, 그리고 **제목이 하필
            // [unassignedLabel]과 같은 작품** — 셋 다 여기서 갈린다.
            if (!used.add(label)) {
                label = "$label #${novel.id}"
                used.add(label)
            }
            out[novel.id] = label
        }
        return out
    }

    /**
     * `novelId → 건수` 맵을 차트가 바로 쓰는 `라벨 → 건수`로 옮긴다.
     *
     * 라벨이 유일하므로 여기서 겹쳐 사라지는 몫이 없다 — 그것이 [of]의 계약이다.
     * 순서는 **건수 내림차순**(동수는 라벨 오름차순)으로 고정한다: 동수에서 순서가 흔들리면
     * 같은 화면을 두 번 열 때 막대가 뒤바뀌어 보인다.
     */
    fun toLabeledCounts(counts: Map<Long?, Int>, labels: Map<Long?, String>): Map<String, Int> =
        counts.entries
            .map { (novelId, count) -> (labels[novelId] ?: labels[null] ?: "") to count }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .associateTo(LinkedHashMap()) { it }
}
