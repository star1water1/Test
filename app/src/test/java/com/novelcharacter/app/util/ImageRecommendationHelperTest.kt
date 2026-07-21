package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRecommendationHelperTest {

    private fun cand(path: String, tags: Set<String>, group: String? = null, at: Long = 0L) =
        ImageRecommendationHelper.Candidate(path, tags, group, at)

    @Test
    fun `점수는 일치 태그 수 - 내림차순 정렬`() {
        val result = ImageRecommendationHelper.recommend(
            characterTags = listOf("기사", "흑발", "장검"),
            candidates = listOf(
                cand("/a.jpg", setOf("기사")),
                cand("/b.jpg", setOf("기사", "흑발", "장검")),
                cand("/c.jpg", setOf("기사", "흑발"))
            ),
            excludePaths = emptySet()
        )
        assertEquals(listOf("/b.jpg", "/c.jpg", "/a.jpg"), result.map { it.candidate.path })
        assertEquals(3, result[0].score)
        assertEquals(listOf("기사", "장검", "흑발"), result[0].matchedTags)
    }

    @Test
    fun `동점은 importedAt 최신 우선 - 그다음 경로 오름차순`() {
        val result = ImageRecommendationHelper.recommend(
            characterTags = listOf("기사"),
            candidates = listOf(
                cand("/old.jpg", setOf("기사"), at = 100L),
                cand("/new.jpg", setOf("기사"), at = 200L),
                cand("/b.jpg", setOf("기사"), at = 100L),
                cand("/a.jpg", setOf("기사"), at = 100L)
            ),
            excludePaths = emptySet()
        )
        assertEquals(listOf("/new.jpg", "/a.jpg", "/b.jpg", "/old.jpg"), result.map { it.candidate.path })
    }

    @Test
    fun `일치 없는 후보와 제외 경로는 걸러진다`() {
        val result = ImageRecommendationHelper.recommend(
            characterTags = listOf("기사"),
            candidates = listOf(
                cand("/match.jpg", setOf("기사")),
                cand("/nomatch.jpg", setOf("마법사")),
                cand("/attached.jpg", setOf("기사"))
            ),
            excludePaths = setOf("/attached.jpg")
        )
        assertEquals(listOf("/match.jpg"), result.map { it.candidate.path })
    }

    @Test
    fun `빈 캐릭터 태그면 추천 없음 - 공백만 있어도 없음`() {
        val candidates = listOf(cand("/a.jpg", setOf("기사")))
        assertTrue(ImageRecommendationHelper.recommend(emptyList(), candidates, emptySet()).isEmpty())
        assertTrue(ImageRecommendationHelper.recommend(listOf(" ", ""), candidates, emptySet()).isEmpty())
    }

    @Test
    fun `태그 비교는 trim 후 정확 일치`() {
        val result = ImageRecommendationHelper.recommend(
            characterTags = listOf(" 기사 "),
            candidates = listOf(
                cand("/trim.jpg", setOf("기사 ")),
                cand("/partial.jpg", setOf("기사단"))  // 부분 일치는 불인정 (정확 일치 계약)
            ),
            excludePaths = emptySet()
        )
        assertEquals(listOf("/trim.jpg"), result.map { it.candidate.path })
    }

    @Test
    fun `limit 초과분은 잘린다`() {
        val candidates = (1..30).map { cand("/img_%02d.jpg".format(it), setOf("기사"), at = it.toLong()) }
        val result = ImageRecommendationHelper.recommend(listOf("기사"), candidates, emptySet(), limit = 20)
        assertEquals(20, result.size)
        assertEquals("/img_30.jpg", result[0].candidate.path)  // 최신 우선
    }
}
