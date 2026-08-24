package com.novelcharacter.app.util

/**
 * "미소속/미배정" 필터 sentinel과 매칭 술어 (순수 JVM — 단위 테스트 대상).
 *
 * 실제 엔티티 id는 Room autoGenerate 양수라 음수 sentinel과 충돌하지 않는다.
 * sentinel은 prefs/프리셋의 Long 리스트 직렬화를 그대로 왕복하므로 저장 경로 무수정.
 * sentinel 상수는 이 한 곳에만 정의한다 (산탄 방지).
 */
object UnassignedFilter {

    /** 세력 필터의 "미소속" — 어떤 세력에도 속하지 않은 캐릭터 선택 */
    const val NO_FACTION_ID = -1L

    /** 작품 필터의 "작품 미배정" — novelId == null 캐릭터 선택 */
    const val NO_NOVEL_ID = -1L

    /**
     * 작품 목록의 "세계관 미배정만" — universeId == null 작품만 보기.
     *
     * [NO_NOVEL_ID]·[NO_FACTION_ID]와 값(-1L)은 같지만 쓰이는 목록이 겹치지 않아 안전하다 —
     * 이쪽은 [com.novelcharacter.app.ui.novel.NovelViewModel]의 단일 선택 세계관 필터
     * (`_universeId`)가 "전체 보기" 뜻으로 이미 -1L을 쓰므로, 그것과 갈리는 **다른 값**을 쓴다.
     */
    const val UNASSIGNED_UNIVERSE_FILTER = -2L

    /** 작품 필터 매칭: 선택 목록의 실제 작품 or (sentinel 포함 시) 미배정(novelId null) */
    fun matchesNovel(charNovelId: Long?, selected: Set<Long>): Boolean =
        charNovelId in selected || (NO_NOVEL_ID in selected && charNovelId == null)

    /** 세력 필터 매칭: 활성 소속 중 선택 세력 or (sentinel 포함 시) 소속 없음 */
    fun matchesFaction(factionIds: Collection<Long>?, selected: Set<Long>): Boolean =
        (factionIds?.any { it in selected } == true) ||
            (NO_FACTION_ID in selected && factionIds.isNullOrEmpty())
}
