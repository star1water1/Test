package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.NovelAxisLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 작품 축 차트 라벨 (R-20 — 라벨은 매칭 키가 아니다).
 *
 * 이 시험이 지키는 것은 하나다: **라벨이 유일하다.** 유일하지 않으면 차트가 다시
 * 같은 자리에서 무너진다 — 동명 작품 둘이 한 막대로 접히거나(합산) 뒤가 앞을
 * 덮어써 작품 하나가 통째로 사라진다(덮어쓰기). 종전에 그 둘이 실제로 났다.
 */
class NovelAxisLabelsTest {

    private val universes = listOf(
        Universe(id = 100L, name = "동방"),
        Universe(id = 200L, name = "서방")
    )

    @Test
    fun `동명 작품은 세계관명으로 갈린다`() {
        val novels = listOf(
            Novel(id = 1L, title = "외전", universeId = 100L),
            Novel(id = 2L, title = "외전", universeId = 200L)
        )
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        assertEquals("외전 (동방)", labels[1L])
        assertEquals("외전 (서방)", labels[2L])
    }

    @Test
    fun `이름이 겹치지 않으면 제목 그대로다`() {
        val novels = listOf(
            Novel(id = 1L, title = "본편", universeId = 100L),
            Novel(id = 2L, title = "외전", universeId = 200L)
        )
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        assertEquals("본편", labels[1L])
        assertEquals("외전", labels[2L])
    }

    @Test
    fun `세계관까지 같은 동명은 id로 갈린다`() {
        // 라벨이 못생겨지더라도 두 작품이 한 막대로 접히는 것보다는 낫다.
        val novels = listOf(
            Novel(id = 1L, title = "외전", universeId = 100L),
            Novel(id = 2L, title = "외전", universeId = 100L)
        )
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        assertEquals(2, labels.values.filter { it != "미지정" }.distinct().size)
    }

    @Test
    fun `세계관이 없는 동명도 갈린다`() {
        val novels = listOf(
            Novel(id = 1L, title = "외전", universeId = null),
            Novel(id = 2L, title = "외전", universeId = null)
        )
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        assertEquals(2, labels.values.filter { it != "미지정" }.distinct().size)
    }

    @Test
    fun `제목이 하필 미지정인 작품도 합성 라벨과 갈린다`() {
        // 종전에는 이 작품의 사건 수에 **작품 미연결 사건 수까지** 더해졌다.
        val novels = listOf(Novel(id = 1L, title = "미지정", universeId = 100L))
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        assertTrue(labels[1L] != labels[null])
    }

    @Test
    fun `동명 작품의 건수가 합쳐지지도 덮이지도 않는다`() {
        // 이 시험이 곧 결함의 재현이다 — 종전 캐릭터 축은 7만 남겼고(덮어쓰기),
        // 사건 축은 13으로 합쳤다(합산). 옳은 답은 12와 7 둘 다 서는 것이다.
        val novels = listOf(
            Novel(id = 1L, title = "외전", universeId = 100L),
            Novel(id = 2L, title = "외전", universeId = 200L)
        )
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        val counts = NovelAxisLabels.toLabeledCounts(mapOf(1L to 12, 2L to 7), labels)
        assertEquals(2, counts.size)
        assertEquals(12, counts["외전 (동방)"])
        assertEquals(7, counts["외전 (서방)"])
    }

    @Test
    fun `소속 없음 몫은 널 키로 들어온다`() {
        val novels = listOf(Novel(id = 1L, title = "본편", universeId = 100L))
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        val counts = NovelAxisLabels.toLabeledCounts(mapOf(1L to 3, null to 5), labels)
        assertEquals(5, counts["미지정"])
        assertEquals(3, counts["본편"])
    }

    @Test
    fun `건수 내림차순으로 서고 동수는 라벨 순이다`() {
        // 동수에서 순서가 흔들리면 같은 화면을 두 번 열 때 막대가 뒤바뀌어 보인다.
        val novels = listOf(
            Novel(id = 1L, title = "나", universeId = 100L),
            Novel(id = 2L, title = "가", universeId = 100L),
            Novel(id = 3L, title = "다", universeId = 100L)
        )
        val labels = NovelAxisLabels.of(novels, universes, "미지정")
        val counts = NovelAxisLabels.toLabeledCounts(mapOf(1L to 4, 2L to 4, 3L to 9), labels)
        assertEquals(listOf("다", "가", "나"), counts.keys.toList())
    }
}
