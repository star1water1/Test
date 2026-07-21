package com.novelcharacter.app.util

/**
 * 캐릭터 편집창 이미지 추천의 순수 매칭 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * 계약(D9): 캐릭터 태그와 이미지 태그의 **정확 일치 교집합**이 1개 이상인 미배정 라이브러리
 * 이미지를 추천한다. 점수 = 교집합 크기. 정렬: 점수 내림차순 → importedAt 내림차순(최신 우선)
 * → 경로 오름차순(결정적 순서 — 테스트·UI 안정성). 이미 첨부된 경로는 제외한다.
 */
object ImageRecommendationHelper {

    /** 추천 후보: 미배정 라이브러리 이미지 1장의 메타. */
    data class Candidate(
        val path: String,
        val tags: Set<String>,
        val linkGroupId: String?,
        val importedAt: Long
    )

    /** 추천 결과: 후보 + 일치한 태그(정렬됨) + 점수(=일치 태그 수). */
    data class Recommendation(
        val candidate: Candidate,
        val matchedTags: List<String>,
        val score: Int
    )

    /**
     * @param characterTags 캐릭터의 현재 태그(콤마 분해 후) — trim 후 빈 값 제거해 비교
     * @param excludePaths 이미 첨부된 경로(원형 그대로 비교 — 호출부가 후보와 같은 저장형으로 전달)
     */
    fun recommend(
        characterTags: Collection<String>,
        candidates: List<Candidate>,
        excludePaths: Set<String>,
        limit: Int = 20
    ): List<Recommendation> {
        val wanted = characterTags.mapNotNullTo(mutableSetOf()) { raw ->
            raw.trim().ifEmpty { null }
        }
        if (wanted.isEmpty() || candidates.isEmpty()) return emptyList()

        return candidates.asSequence()
            .filter { it.path !in excludePaths }
            .mapNotNull { c ->
                val matched = c.tags.mapNotNull { t ->
                    val trimmed = t.trim()
                    if (trimmed in wanted) trimmed else null
                }.distinct().sorted()
                if (matched.isEmpty()) null else Recommendation(c, matched, matched.size)
            }
            .sortedWith(
                compareByDescending<Recommendation> { it.score }
                    .thenByDescending { it.candidate.importedAt }
                    .thenBy { it.candidate.path }
            )
            .take(limit)
            .toList()
    }
}
