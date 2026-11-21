package com.novelcharacter.app.util

import com.novelcharacter.app.util.NovelPickerLabels.Item
import com.novelcharacter.app.data.model.Novel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 작품 이름표 계약 (워크스루 S25 관찰 수리, 2026.11.21).
 *
 * 두 방향을 함께 잠근다 — **구별이 필요한데 안 붙으면** 사용자가 엉뚱한 구역의 폼을 얻고,
 * **필요 없는데 붙으면** 세계관이 하나인 사용자의 모든 줄이 길어진다(원칙 04).
 */
class NovelPickerLabelsTest {

    @Test
    fun `제목이 겹치지 않으면 제목 그대로다`() {
        assertEquals(
            listOf("달의 노래", "검의 기록"),
            NovelPickerLabels.labels(listOf(Item("달의 노래", "A"), Item("검의 기록", "B")))
        )
    }

    @Test
    fun `겹치는 제목에만 세계관을 붙인다`() {
        assertEquals(
            listOf("달의 노래 (세계관: A)", "달의 노래 (세계관: B)", "검의 기록"),
            NovelPickerLabels.labels(
                listOf(Item("달의 노래", "A"), Item("달의 노래", "B"), Item("검의 기록", "A"))
            )
        )
    }

    @Test
    fun `무소속은 그 사실을 말한다 — 빈 칸으로 두지 않는다`() {
        assertEquals(
            listOf("달의 노래 (세계관: A)", "달의 노래 (세계관: ${NovelPickerLabels.UNASSIGNED})"),
            NovelPickerLabels.labels(listOf(Item("달의 노래", "A"), Item("달의 노래", null)))
        )
    }

    @Test
    fun `세계관 이름이 빈 글자여도 무소속과 같이 적는다`() {
        val out = NovelPickerLabels.labels(listOf(Item("달", " "), Item("달", "A")))
        assertEquals("달 (세계관: ${NovelPickerLabels.UNASSIGNED})", out[0])
    }

    /**
     * **순서와 길이는 계약이다** — 부르는 쪽이 고른 자리로 원래 작품을 되찾으므로,
     * 접거나 정렬하면 **다른 작품이 선택된다.**
     */
    @Test
    fun `순서와 길이를 바꾸지 않는다 — 세계관까지 같은 동명도 접지 않는다`() {
        val items = listOf(Item("달", "A"), Item("검", "A"), Item("달", "A"))
        val out = NovelPickerLabels.labels(items)
        assertEquals(items.size, out.size)
        assertEquals("달 (세계관: A)", out[0])
        assertEquals("검", out[1])
        assertEquals("달 (세계관: A)", out[2])
    }

    @Test
    fun `빈 목록은 빈 목록이다`() {
        assertEquals(emptyList<String>(), NovelPickerLabels.labels(emptyList()))
    }

    // ── 작품 목록에서 바로 짓기 (고르는 자리 열네 곳의 공용 경로) ─────────────

    private fun novel(id: Long, title: String, universeId: Long?) =
        Novel(id = id, title = title, universeId = universeId)

    @Test
    fun `작품 목록의 이름표는 세계관 표에서 이름을 찾는다`() {
        val novels = listOf(novel(1, "달", 10), novel(2, "달", null), novel(3, "검", 10))
        assertEquals(
            listOf("달 (세계관: A)", "달 (세계관: ${NovelPickerLabels.UNASSIGNED})", "검"),
            NovelPickerLabels.forNovels(novels, mapOf(10L to "A"))
        )
    }

    @Test
    fun `이름을 모르는 세계관은 미배정으로 지어내지 않고 제목만 둔다`() {
        // 세계관 목록이 아직 안 온 화면 — null(무소속)과 섞으면 거짓 구별이 된다.
        val novels = listOf(novel(1, "달", 10), novel(2, "달", null))
        assertEquals(
            listOf("달", "달 (세계관: ${NovelPickerLabels.UNASSIGNED})"),
            NovelPickerLabels.forNovels(novels, emptyMap())
        )
    }

    @Test
    fun `동명이 없으면 세계관 표를 읽지 않는다`() = runBlocking {
        var asked = false
        val out = NovelPickerLabels.forNovels(listOf(novel(1, "달", 10), novel(2, "검", 20))) {
            asked = true; emptyMap()
        }
        assertEquals(listOf("달", "검"), out)
        assertFalse(asked)
    }

    @Test
    fun `동명이 있으면 세계관 표를 읽어 붙인다`() = runBlocking {
        var asked = false
        val out = NovelPickerLabels.forNovels(listOf(novel(1, "달", 10), novel(2, "달", 20))) {
            asked = true; mapOf(10L to "A", 20L to "B")
        }
        assertEquals(listOf("달 (세계관: A)", "달 (세계관: B)"), out)
        assertTrue(asked)
    }
}
