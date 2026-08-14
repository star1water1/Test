package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 작품 제목 해석 — 연표 제목 폴백의 동명 first-match가 아무 작품에나 사건을 연결하고
 * 그 작품의 세계관을 사건 소속으로까지 번지게 하던 결함(I2-2)의 회귀 가드.
 * novels는 code만 유니크하고 제목은 세계관 간(같은 세계관 안에서도) 중복이 허용된다.
 */
class NovelRefResolverTest {

    private fun novel(id: Long, universeId: Long?, title: String) =
        Novel(id = id, title = title, universeId = universeId, code = "c$id")

    private val swordA = novel(1, 10, "검의 노래")
    private val swordB = novel(2, 20, "검의 노래")
    private val moonA = novel(3, 10, "달의 아이")
    private val index = NovelTitleIndex(listOf(swordA, swordB, moonA))

    @Test
    fun uniqueTitle_resolvesWithoutHint() {
        // 후보가 하나면 힌트 없이도 확정 — 세계관 열이 없는 구버전 파일이 무조건 실패하면 안 된다
        val r = index.resolve("달의 아이", preferredUniverseId = null) as NovelTitleLookup.Found
        assertEquals(moonA, r.novel)
    }

    @Test
    fun duplicateTitle_noHint_isAmbiguousNotFirstMatch() {
        // 핵심 회귀(I2-2): 종전 first-match는 아무 작품에나 연결하고 그 세계관을 소속으로 유도했다
        val r = index.resolve("검의 노래", preferredUniverseId = null)
        assertTrue(r is NovelTitleLookup.Ambiguous && r.count == 2)
    }

    @Test
    fun duplicateTitle_narrowedByUniverse() {
        // 세계관 20 사건의 '검의 노래'는 세계관 20의 작품이어야 한다
        val r = index.resolve("검의 노래", preferredUniverseId = 20L) as NovelTitleLookup.Found
        assertEquals(swordB, r.novel)
    }

    @Test
    fun duplicateTitle_hintMatchesNeither_isAmbiguous() {
        // 힌트 세계관에 후보가 없으면 추측하지 않는다 — 오배정보다 모호 선언 후 안내가 낫다(4-3)
        val r = index.resolve("검의 노래", preferredUniverseId = 99L)
        assertTrue(r is NovelTitleLookup.Ambiguous)
    }

    @Test
    fun duplicateTitle_stillDuplicateInsideUniverse_isAmbiguous() {
        // 같은 세계관 안 동명 두 작품은 세계관 힌트로도 갈리지 않는다 — 코드만이 답이다
        val twins = NovelTitleIndex(listOf(novel(4, 10, "쌍둥이"), novel(5, 10, "쌍둥이")))
        val r = twins.resolve("쌍둥이", preferredUniverseId = 10L)
        assertTrue(r is NovelTitleLookup.Ambiguous && r.count == 2)
    }

    @Test
    fun unassignedNovel_isNotPickedByUniverseHint() {
        // 무소속(universeId=null) 작품은 어느 세계관 힌트에도 걸리지 않는다 — 소속을 추측하지 않는다
        val mixed = NovelTitleIndex(listOf(novel(6, null, "표류기"), novel(7, 30, "표류기")))
        val r = mixed.resolve("표류기", preferredUniverseId = 30L) as NovelTitleLookup.Found
        assertEquals(7L, r.novel.id)
        assertTrue(mixed.resolve("표류기", preferredUniverseId = null) is NovelTitleLookup.Ambiguous)
    }

    @Test
    fun blankOrUnknownTitle_isNotFound() {
        assertTrue(index.resolve("", null) is NovelTitleLookup.NotFound)
        assertTrue(index.resolve("없는 작품", null) is NovelTitleLookup.NotFound)
    }

    @Test
    fun ambiguityDetail_namesUniversesAndCorrectionTarget() {
        val r = index.resolve("검의 노래", null) as NovelTitleLookup.Ambiguous
        val detail = novelAmbiguityDetail(listOf(AmbiguousNovelRef("검의 노래", r.candidates))) {
            mapOf<Long?, String>(10L to "세계관A", 20L to "세계관B")[it]
        }
        // 어느 제목이 어디에서 겹치는지까지 들어야 사용자가 교정 경로(코드/세계관 열)를 고를 수 있다
        assertTrue(detail.contains("'검의 노래'"))
        assertTrue(detail.contains("세계관A") && detail.contains("세계관B"))
    }

    @Test
    fun ambiguityDetail_fallsBackToCountWhenNoUniverseNames() {
        // 이름을 하나도 못 찾으면(전부 무소속 표기 불가 등) 개수라도 든다 — 빈 괄호는 정보가 아니다
        val detail = novelAmbiguityDetail(
            listOf(AmbiguousNovelRef("검의 노래", listOf(swordA, swordB)))
        ) { null }
        assertTrue(detail.contains("2개"))
    }
}
