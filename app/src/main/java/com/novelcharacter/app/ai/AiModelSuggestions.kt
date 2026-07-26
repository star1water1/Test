package com.novelcharacter.app.ai

/**
 * 추천 모델 칩에 무엇을 몇 개 보여줄지 정하는 순수 로직.
 *
 * 종전에는 칩이 [AiPresets]의 **정적 스냅샷만** 썼다. 모델 선택 다이얼로그는 이미 서버에
 * 실시간 조회를 하는데 칩만 그 전환에서 빠져 있어서, 프리셋 목록이 낡는 순간 사용자에게는
 * "최신 모델을 추천하지 않는 앱"으로 보였다(제공사가 모델을 갱신해도 앱은 모른다).
 *
 * ## 순서 규칙
 * 1. **큐레이션 ∩ 실시간** — 프리셋이 고른 모델이 서버에도 있으면 프리셋 순서대로 먼저.
 *    사람이 고른 순서에는 "이 프로바이더에서 뭘 쓰면 되는가"라는 정보가 들어 있다.
 * 2. **실시간에만 있는 것** — 서버가 준 순서대로 뒤에 붙인다. 프리셋이 낡아도 새 모델이
 *    칩에 도달하는 경로가 이것이다.
 * 3. 중복 제거 후 [limit]개까지.
 *
 * 서버 목록이 비면(키 없음·조회 실패) 큐레이션만 쓴다 — 칩이 통째로 사라지면 원탭 선택이라는
 * 목적 자체가 없어지므로, 폴백은 '없음'이 아니라 '정적 추천값'이다.
 *
 * 전체 목록은 언제나 '모델 찾기'(검색 가능)가 담당한다. 칩은 러프 선택용이라 상한이 필요하다.
 */
object AiModelSuggestions {

    /** 칩으로 보여줄 최대 개수 — 한 줄 가로 스크롤에서 훑을 수 있는 수준. */
    const val CHIP_LIMIT = 8

    fun rank(
        live: List<String>,
        curated: List<String>,
        limit: Int = CHIP_LIMIT
    ): List<String> {
        if (limit <= 0) return emptyList()
        val liveClean = live.map { it.trim() }.filter { it.isNotEmpty() }
        val curatedClean = curated.map { it.trim() }.filter { it.isNotEmpty() }
        if (liveClean.isEmpty()) return curatedClean.distinct().take(limit)

        val liveSet = liveClean.toHashSet()
        val ordered = ArrayList<String>(liveClean.size + curatedClean.size)
        // 1) 큐레이션 중 서버에도 있는 것 — 프리셋 순서 유지
        curatedClean.forEach { if (it in liveSet) ordered.add(it) }
        // 2) 서버에만 있는 것 — 서버 순서 유지 (Anthropic은 최신순으로 준다)
        val curatedSet = curatedClean.toHashSet()
        liveClean.forEach { if (it !in curatedSet) ordered.add(it) }
        return ordered.distinct().take(limit)
    }
}
