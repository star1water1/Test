package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Novel

/**
 * 작품 제목 해석 결과 — 연표 '관련 작품' 제목 폴백용.
 *
 * novels 테이블의 유니크 인덱스는 code 하나뿐이고 제목은 세계관 간(같은 세계관 안에서도)
 * 중복이 허용된다. 제목만으로 first-match 하면 동명 작품 중 아무 것에나 사건이 연결되고,
 * 연표는 그 작품의 세계관을 사건 소속으로까지 유도하므로 오배정이 소속으로 번진다(I2-2).
 * [FactionIndex.resolve]와 동형이다(4-3 규약 — 동명 참조는 범위로 좁히고, 안 되면 모호를 선언한다).
 */
sealed class NovelTitleLookup {
    data class Found(val novel: Novel) : NovelTitleLookup()

    /** 동명 작품이 여럿이고 세계관 힌트로도 하나로 좁혀지지 않음 */
    data class Ambiguous(val count: Int, val candidates: List<Novel>) : NovelTitleLookup()

    data object NotFound : NovelTitleLookup()
}

/** 하나로 좁혀지지 않은 제목 토큰 — 호출부가 정책(기존 유지·연결 제외)과 함께 고지한다. */
data class AmbiguousNovelRef(val title: String, val candidates: List<Novel>)

/**
 * 한 시트를 처리하는 동안 재사용하는 작품 제목 색인 (순수 JVM — 단위 테스트 대상).
 * 행마다 전 작품 목록을 훑지 않는다(종전 `allNovels.find`는 행×토큰×작품 선형 탐색).
 *
 * 코드 축은 여기 없다 — 코드 해석은 이 가져오기가 앞 시트에서 방금 만든 작품까지 봐야
 * 하므로 가져오기 수명 색인(`novelByCode`)이 답한다. 제목 축은 시트 시작 스냅샷이다
 * (연표 시트는 작품을 만들지 않으므로 종전 `allNovels`와 같은 재료다).
 */
class NovelTitleIndex(novels: List<Novel>) {
    private val byTitle: Map<String, List<Novel>> = novels.groupBy { it.title }

    /**
     * 동명이 **여럿일 때만** [preferredUniverseId]로 좁힌다 — [FactionIndex.resolve]와 동형.
     * 후보가 하나면 힌트 없이도 Found다(세계관 열이 없는 구버전 파일이 무조건 실패하지 않게 —
     * 무조건 skip은 조작 마찰 최소화 원칙에 역행한다).
     */
    fun resolve(title: String, preferredUniverseId: Long?): NovelTitleLookup {
        if (title.isBlank()) return NovelTitleLookup.NotFound
        val matches = byTitle[title] ?: return NovelTitleLookup.NotFound
        if (matches.size == 1) return NovelTitleLookup.Found(matches[0])
        val narrowed = preferredUniverseId?.let { uid -> matches.filter { it.universeId == uid } } ?: emptyList()
        return if (narrowed.size == 1) NovelTitleLookup.Found(narrowed[0])
        else NovelTitleLookup.Ambiguous(matches.size, matches)
    }
}

/**
 * 동명 작품 모호 상세 — `'제목'(세계관: A, B)` 꼴을 토큰마다 이어 붙인다.
 * 정책 문구(기존 유지 / 연결 제외)는 호출부의 몫이고, 여기는 **어느 제목이 어디에서
 * 겹치는가**만 든다 — 교정 경로를 고르려면 사용자가 그것부터 알아야 한다.
 * [universeNameOf]는 무소속(`universeId == null`)의 표기도 정한다.
 */
fun novelAmbiguityDetail(
    ambiguous: List<AmbiguousNovelRef>,
    universeNameOf: (Long?) -> String?
): String = ambiguous.joinToString(", ") { ref ->
    val where = ref.candidates.mapNotNull { universeNameOf(it.universeId) }.distinct().joinToString(", ")
    "'${ref.title}'" + (if (where.isNotBlank()) "(세계관: $where)" else "(${ref.candidates.size}개)")
}
