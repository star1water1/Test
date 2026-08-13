package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterValueLedger] — 가져오기의 값 조회를 **값 단위에서 캐릭터 단위로** 내린 장부 (B-72 ②).
 *
 * **이 시험이 지키는 것은 속도가 아니라 정확성이다.** 빨라진 것은 조회 횟수가 줄어서이고 그것은
 * 세지 않아도 참이다. 위험한 쪽은 **사본이 DB와 갈리는 것**이며, 갈리면 전부 조용히 틀린다 —
 * 그래서 아래 넷은 *"장부가 무엇을 답해야 하는가"*만 잰다.
 */
class CharacterValueLedgerTest {

    private fun value(charId: Long, fieldId: Long, v: String, id: Long = 0) =
        CharacterFieldValue(id = id, characterId = charId, fieldDefinitionId = fieldId, value = v)

    // ── ① 쓴 것은 곧바로 읽힌다 ────────────────────────────────────────────────
    // 두 시트가 같은 (캐릭터, 필드)를 담거나 한 시트에 같은 행이 두 번 있을 때 두 번째가
    // 첫 번째의 결과를 봐야 한다. 못 보면 *있는 값을 없다고 보고* insert를 치는데,
    // 그 표에는 (characterId, fieldDefinitionId) 유니크 색인이 있어 가져오기가 통째로 실패한다.

    @Test
    fun `방금 쓴 값이 다음 조회에서 그대로 보인다`() {
        val ledger = CharacterValueLedger()
        ledger.load(1L, emptyList())
        ledger.put(value(1L, 7L, "붉은색", id = 42L))
        val read = ledger.get(1L, 7L)
        assertEquals("붉은색", read?.value)
        assertEquals(42L, read?.id)
    }

    @Test
    fun `덮어쓴 값은 마지막 것이 남는다`() {
        val ledger = CharacterValueLedger()
        ledger.load(1L, listOf(value(1L, 7L, "처음", id = 42L)))
        ledger.put(value(1L, 7L, "나중", id = 42L))
        assertEquals("나중", ledger.get(1L, 7L)?.value)
    }

    // ── ② 지운 것은 사라진다 ──────────────────────────────────────────────────
    // 남아 있으면 이미 지운 행을 update하려 들고(무동작), 사용자에게는 "지웠다"고 세어 놓고
    // 값이 그대로인 것처럼 보인다.

    @Test
    fun `지운 값은 다시 조회되지 않는다`() {
        val ledger = CharacterValueLedger()
        ledger.load(1L, listOf(value(1L, 7L, "붉은색", id = 42L)))
        ledger.remove(1L, 7L)
        assertNull(ledger.get(1L, 7L))
    }

    @Test
    fun `지운 뒤 다시 쓰면 되살아난다`() {
        val ledger = CharacterValueLedger()
        ledger.load(1L, listOf(value(1L, 7L, "붉은색", id = 42L)))
        ledger.remove(1L, 7L)
        ledger.put(value(1L, 7L, "푸른색", id = 43L))
        assertEquals("푸른색", ledger.get(1L, 7L)?.value)
    }

    // ── ③ 안 실은 캐릭터와 값이 없는 캐릭터를 가른다 ───────────────────────────
    // 이 둘이 똑같이 보이면 새 캐릭터마다 헛조회가 돌거나, 반대로 기존 값을 못 보고 덮어쓴다.
    // **반대편이다** — 앞의 시험들은 "실린 뒤"만 재므로 [isLoaded]가 늘 true인 구현도 통과한다.

    @Test
    fun `안 실은 캐릭터는 안 실었다고 답한다`() {
        val ledger = CharacterValueLedger()
        assertFalse(ledger.isLoaded(1L))
        ledger.load(1L, emptyList())
        assertTrue("값이 0건이어도 '실었다'여야 한다 — 아니면 캐릭터마다 헛조회가 돈다", ledger.isLoaded(1L))
    }

    @Test
    fun `내린 캐릭터는 다시 안 실린 상태가 된다`() {
        val ledger = CharacterValueLedger()
        ledger.load(1L, listOf(value(1L, 7L, "붉은색", id = 42L)))
        ledger.forget(1L)
        assertFalse(ledger.isLoaded(1L))
        assertNull("내렸으면 사본도 함께 사라져야 한다", ledger.get(1L, 7L))
    }

    @Test
    fun `이미 실린 캐릭터를 다시 실어도 기록한 쓰기를 잃지 않는다`() {
        // 세계관 이동 정리처럼 DB를 건드린 자리는 forget으로 **명시해서** 내린다.
        // load가 덮어쓰면 그 명시가 없는 자리에서도 조용히 되돌아간다.
        val ledger = CharacterValueLedger()
        ledger.load(1L, listOf(value(1L, 7L, "처음", id = 42L)))
        ledger.put(value(1L, 7L, "나중", id = 42L))
        ledger.load(1L, listOf(value(1L, 7L, "처음", id = 42L)))
        assertEquals("나중", ledger.get(1L, 7L)?.value)
    }

    // ── ④ 캐릭터끼리 섞이지 않는다 ────────────────────────────────────────────
    // 필드 id는 캐릭터 사이에서 공유되므로(같은 필드 정의를 여럿이 쓴다) 키가 필드만이면
    // 한 캐릭터의 값이 다른 캐릭터의 답이 된다 — 그리고 두 값 모두 그럴듯해 보인다.

    @Test
    fun `같은 필드라도 캐릭터가 다르면 다른 값이다`() {
        val ledger = CharacterValueLedger()
        ledger.load(1L, listOf(value(1L, 7L, "붉은색", id = 42L)))
        ledger.load(2L, listOf(value(2L, 7L, "검은색", id = 43L)))
        assertEquals("붉은색", ledger.get(1L, 7L)?.value)
        assertEquals("검은색", ledger.get(2L, 7L)?.value)
        ledger.remove(1L, 7L)
        assertEquals("한쪽을 지워도 다른 캐릭터의 같은 필드는 남는다", "검은색", ledger.get(2L, 7L)?.value)
    }

    @Test
    fun `실리지 않은 캐릭터에도 쓰기는 기록된다`() {
        // put은 load 없이도 성립해야 한다 — 새로 만든 캐릭터는 빈 목록으로 실리지만,
        // 그 순서를 뒤집는 호출부가 생겨도 값이 사라지지는 않아야 한다.
        val ledger = CharacterValueLedger()
        ledger.put(value(9L, 3L, "새 값", id = 1L))
        assertEquals("새 값", ledger.get(9L, 3L)?.value)
    }

    // ── ⑤ 가져오기마다 비운다 ────────────────────────────────────────────────
    // 장부는 서비스와 수명이 같고 서비스는 연속 가져오기에서 재사용된다. 두 가져오기 사이에
    // 사용자가 앱에서 값을 고칠 수 있으므로, 안 비우면 둘째 가져오기가 DB가 아니라
    // **지난번 사본**을 보고 판단한다.

    @Test
    fun `비우면 실린 것도 기록한 것도 남지 않는다`() {
        val ledger = CharacterValueLedger()
        ledger.load(1L, listOf(value(1L, 7L, "붉은색", id = 42L)))
        ledger.put(value(2L, 8L, "다른 값", id = 43L))
        ledger.reset()
        assertFalse(ledger.isLoaded(1L))
        assertNull(ledger.get(1L, 7L))
        assertNull(ledger.get(2L, 8L))
    }
}
