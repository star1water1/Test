package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImportLookupIndex] — 가져오기 시트 루프의 행별 조회를 **시트마다 한 번 세우는 색인**으로
 * 내린 자리 (B-210).
 *
 * **이 시험이 지키는 것은 속도가 아니라 정확성이다.** 빨라진 것은 조회 횟수가 줄어서이고
 * 그것은 세지 않아도 참이다. 위험한 쪽은 **색인이 DB와 갈리는 것**이며, 갈리면 셋 다 조용히
 * 틀린다 — 오류도 고지도 없이 *합쳐지는 상대*만 바뀐다. 그래서 아래 절들은 *"색인이 무엇을
 * 답해야 하는가"*만 잰다. (**절 수를 적지 않는다** — 아래 `── ① ~ ──` 머리가 세는 법이다.)
 */
class ImportLookupIndexTest {

    private data class Row(val id: Long, val code: String, val name: String = "")

    private fun byCode() = ImportLookupIndex<String, Row>(
        idOf = { it.id },
        keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
    )

    // ── ① `LIMIT 1`의 답을 그대로 낸다 — 먼저 실은 것이 이긴다 ──────────────────
    // 대체하는 질의는 `SELECT … WHERE code = :code LIMIT 1` 꼴이고 ORDER BY가 없어
    // 사실상 rowid가 작은 행이 나온다. `associateBy`는 반대로 **뒤엣것**을 남기므로,
    // 같은 키가 둘인 파일에서 **합쳐지는 상대가 바뀐다.**

    @Test
    fun `같은 키가 둘이면 먼저 실은 행을 준다`() {
        val index = byCode()
        index.load(listOf(Row(1L, "C-1"), Row(2L, "C-1")))
        assertEquals(1L, index.first("C-1")?.id)
    }

    @Test
    fun `키가 든 행 전부를 볼 수도 있다 — LIMIT 없는 질의의 자리`() {
        val index = byCode()
        index.load(listOf(Row(1L, "C-1"), Row(2L, "C-1"), Row(3L, "C-2")))
        assertEquals(listOf(1L, 2L), index.all("C-1").map { it.id })
        assertEquals(emptyList<Long>(), index.all("없는키"))
    }

    @Test
    fun `갱신은 자리를 지킨다 — 뒤로 밀면 LIMIT 1의 답이 바뀐다`() {
        val index = byCode()
        index.load(listOf(Row(1L, "C-1", "가"), Row(2L, "C-1", "나")))
        index.put(Row(1L, "C-1", "가-고침"))
        assertEquals(1L, index.first("C-1")?.id)
        assertEquals("가-고침", index.first("C-1")?.name)
        assertEquals(listOf(1L, 2L), index.all("C-1").map { it.id })
    }

    // ── ② 쓴 것은 곧바로 읽힌다 ────────────────────────────────────────────────
    // 한 시트에 같은 코드가 두 번 있으면 이 저장소는 *"덮어쓴다"*고 고지하고 받는다.
    // 두 번째 행이 첫 번째가 만든 엔티티를 못 보면 **있는 것을 없다고 보고 insert를 쳐**
    // 한 파일에서 같은 사건이 둘로 갈린다.

    @Test
    fun `방금 넣은 행이 다음 조회에서 그대로 보인다`() {
        val index = byCode()
        index.load(emptyList())
        assertNull(index.first("C-9"))
        index.put(Row(7L, "C-9", "새 사건"))
        assertEquals(7L, index.first("C-9")?.id)
        assertTrue(index.contains("C-9"))
    }

    @Test
    fun `지운 행은 사라진다`() {
        val index = byCode()
        index.load(listOf(Row(1L, "C-1")))
        index.remove(Row(1L, "C-1"))
        assertNull(index.first("C-1"))
        assertFalse(index.contains("C-1"))
    }

    @Test
    fun `id로도 지울 수 있다 — 행 전체가 손에 없는 자리`() {
        val index = byCode()
        index.load(listOf(Row(1L, "C-1"), Row(2L, "C-1")))
        index.removeById(1L)
        assertEquals(2L, index.first("C-1")?.id)
    }

    // ── ③ 키가 바뀌면 옛 키는 그 행을 잊는다 ───────────────────────────────────
    // 캐릭터 시트는 이름·코드를 고칠 수 있고, 고친 뒤 SQL로 옛 이름을 물으면 없다.
    // 맵을 갱신만 하는 손코딩은 옛 키가 그 행을 계속 가리켜 **옛 이름으로도 잡힌다.**

    @Test
    fun `키를 바꾸면 옛 키로는 더 이상 찾을 수 없다`() {
        val index = ImportLookupIndex<String, Row>(idOf = { it.id }, keyOf = { it.name })
        index.load(listOf(Row(1L, "C-1", "옛이름")))
        index.put(Row(1L, "C-1", "새이름"))
        assertNull(index.first("옛이름"))
        assertEquals(1L, index.first("새이름")?.id)
    }

    @Test
    fun `키를 바꿔도 같은 키의 다른 행은 남는다`() {
        val index = ImportLookupIndex<String, Row>(idOf = { it.id }, keyOf = { it.name })
        index.load(listOf(Row(1L, "C-1", "동명"), Row(2L, "C-2", "동명")))
        index.put(Row(1L, "C-1", "갈라짐"))
        assertEquals(listOf(2L), index.all("동명").map { it.id })
        assertEquals(listOf(1L), index.all("갈라짐").map { it.id })
    }

    // ── ④ 키가 없는 행은 실리지 않는다 ────────────────────────────────────────
    // 빈 코드를 빈 문자열로 실으면 **빈 코드끼리 서로를 찾는다** — 코드가 없는 행들이
    // 한 엔티티로 합쳐지는데, 그 파일은 오류를 내지 않으므로 조용히 틀린다.

    @Test
    fun `키가 null이면 색인에 실리지 않는다`() {
        val index = byCode()
        index.load(listOf(Row(1L, ""), Row(2L, "")))
        assertFalse(index.contains(""))
        assertNull(index.first(""))
    }

    @Test
    fun `키를 지우면 옛 키에서도 빠진다`() {
        val index = byCode()
        index.load(listOf(Row(1L, "C-1")))
        index.put(Row(1L, ""))
        assertNull(index.first("C-1"))
        // 다시 코드를 달면 정상으로 돌아온다 — keyById가 새지 않는다는 확인이기도 하다
        index.put(Row(1L, "C-2"))
        assertEquals(1L, index.first("C-2")?.id)
    }

    // ── ⑤ 자연키 — 여러 칸이 한 키다 ──────────────────────────────────────────
    // `getChangeByNaturalKey(characterId, year, fieldKey, newValue)` 꼴의 자리다.
    // 칸에 null이 섞이며(월·일) SQL이 `(month = :m OR (month IS NULL AND :m IS NULL))`로
    // **null을 값으로** 대조하므로, 키도 null을 그대로 담아야 같은 답이 된다.
    //
    // **키는 반드시 타입이 있는 `data class`다.** 처음 이 절을 `listOf(charId, year, month)`로
    // 썼다가 전부 null이 나왔다: 코틀린이 원소 타입을 첫 원소에서 추론해 `listOf(10L, 1993, null)`의
    // `1993`을 **Long으로 올리고**, `Int` 칸에서 실은 `Integer(1993)`과 영영 같지 않았다.
    // 컴파일도 되고 예외도 없어 **모든 조회가 빗나가는데도 조용하다** — 그러면 가져오기는
    // *"기존 행이 없다"*고 보고 전부 새로 만든다. 그 실패를 이 절이 시험으로 붙잡아 둔다.

    private data class ChangeKey(val charId: Long, val year: Int, val month: Int?)

    @Test
    fun `여러 칸을 묶은 키가 그대로 대조된다`() {
        data class Change(val id: Long, val charId: Long, val year: Int, val month: Int?)
        val index = ImportLookupIndex<ChangeKey, Change>(
            idOf = { it.id },
            keyOf = { ChangeKey(it.charId, it.year, it.month) }
        )
        index.load(listOf(Change(1L, 10L, 1993, null), Change(2L, 10L, 1993, 5)))
        assertEquals(1L, index.first(ChangeKey(10L, 1993, null))?.id)
        assertEquals(2L, index.first(ChangeKey(10L, 1993, 5))?.id)
        assertNull(index.first(ChangeKey(11L, 1993, null)))
    }

    @Test
    fun `자연키의 null 칸은 값으로 대조된다 — 비어 있음끼리만 만난다`() {
        data class Change(val id: Long, val charId: Long, val year: Int, val month: Int?)
        val index = ImportLookupIndex<ChangeKey, Change>(
            idOf = { it.id },
            keyOf = { ChangeKey(it.charId, it.year, it.month) }
        )
        index.load(listOf(Change(1L, 10L, 1993, null)))
        assertNull(index.first(ChangeKey(10L, 1993, 1)))
        assertEquals(1L, index.first(ChangeKey(10L, 1993, null))?.id)
    }

    // ── ⑥ 가져오기마다 비운다 ────────────────────────────────────────────────
    // 시트를 넘어 사는 색인(캐릭터 정체성)은 서비스와 수명이 같은데, 두 가져오기 사이에
    // 사용자가 앱에서 값을 고칠 수 있다. 안 비우면 둘째 가져오기가 **DB가 아니라 지난번
    // 사본**을 보고 판단한다.

    @Test
    fun `reset은 색인을 통째로 비운다`() {
        val index = byCode()
        index.load(listOf(Row(1L, "C-1")))
        index.reset()
        assertNull(index.first("C-1"))
        // 비운 뒤 같은 id를 다시 실어도 옛 키가 되살아나지 않는다
        index.put(Row(1L, "C-2"))
        assertNull(index.first("C-1"))
        assertEquals(1L, index.first("C-2")?.id)
    }
}
