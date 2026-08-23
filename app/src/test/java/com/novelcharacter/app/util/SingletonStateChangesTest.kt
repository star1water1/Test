package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SingletonStateChanges] — **캐릭터당 한 행**의 불변식과 정본 판정.
 *
 * 실제 사용자 데이터에서 `__birth`가 두 줄인 캐릭터가 둘 있었고, 읽는 자리마다 답이 갈렸다
 * (프로필 165살 · 연표 163살). 여기서 잠그는 것은 **정본이 늘 같은 행**이라는 것과,
 * 그 행이 *쓰는 쪽이 고치는 행*과 같다는 것이다.
 */
class SingletonStateChangesTest {

    private val BIRTH = CharacterStateChange.KEY_BIRTH
    private val DEATH = CharacterStateChange.KEY_DEATH
    private val ALIVE = CharacterStateChange.KEY_ALIVE

    private fun change(
        key: String, year: Int, value: String = "",
        id: Long = 0, month: Int? = null, day: Int? = null
    ) = CharacterStateChange(
        id = id, characterId = 1L, year = year, month = month, day = day,
        fieldKey = key, newValue = value
    )

    @Test
    fun `밑줄 키 셋만 캐릭터당 한 행이다`() {
        assertEquals(setOf(BIRTH, DEATH, ALIVE), SingletonStateChanges.KEYS)
        assertTrue(SingletonStateChanges.isSingleton(BIRTH))
        assertTrue(SingletonStateChanges.isSingleton(DEATH))
        assertTrue(SingletonStateChanges.isSingleton(ALIVE))
        // 일반 필드의 이력은 여러 줄이 정상이다 — 그것이 '상태변화'다.
        assertTrue(!SingletonStateChanges.isSingleton("affiliation"))
        assertTrue(!SingletonStateChanges.isSingleton(CharacterStateChange.KEY_AGE))
    }

    @Test
    fun `없으면 null이고 하나면 그것이다`() {
        assertNull(SingletonStateChanges.pick(emptyList(), BIRTH))
        assertNull(SingletonStateChanges.pick(listOf(change(DEATH, 1400)), BIRTH))
        assertEquals(1301, SingletonStateChanges.pick(listOf(change(BIRTH, 1301)), BIRTH)?.year)
    }

    /** 사용자 데이터에 실제로 남아 있던 모양 — 1301(정본)과 1303(둘째 줄). */
    @Test
    fun `둘이면 가장 이른 행이 정본이다`() {
        val rows = listOf(change(BIRTH, 1303, "1303", id = 9), change(BIRTH, 1301, "1301", id = 2))
        assertEquals(1301, SingletonStateChanges.pick(rows, BIRTH)?.year)
        // 실린 차례가 달라도 같은 답이다 — 부르는 쪽의 정렬에 기대지 않는다.
        assertEquals(1301, SingletonStateChanges.pick(rows.reversed(), BIRTH)?.year)
    }

    /**
     * DAO의 `ORDER BY year ASC, month ASC, day ASC`와 **같은 답**이어야 한다 —
     * SQLite의 `ASC`는 NULL을 앞에 둔다.
     */
    @Test
    fun `월 일이 비었으면 앞이다`() {
        val rows = listOf(
            change(BIRTH, 1301, id = 5, month = 3, day = 1),
            change(BIRTH, 1301, id = 7, month = null, day = null)
        )
        assertEquals(7L, SingletonStateChanges.pick(rows, BIRTH)?.id)
    }

    /** 연·월·일이 모두 같으면 먼저 만든 행(id)이 이긴다 — `LIMIT 1`이 집는 것과 같다. */
    @Test
    fun `모두 같으면 먼저 만든 행이다`() {
        val rows = listOf(change(ALIVE, 0, "unknown", id = 40), change(ALIVE, 0, "alive", id = 12))
        assertEquals(12L, SingletonStateChanges.pick(rows, ALIVE)?.id)
    }

    @Test
    fun `정상 데이터에는 지울 둘째 줄이 없다`() {
        val rows = listOf(change(BIRTH, 1301, id = 1), change(DEATH, 1400, id = 2), change(ALIVE, 0, "alive", id = 3))
        assertEquals(emptyList<CharacterStateChange>(), SingletonStateChanges.strays(rows))
    }

    @Test
    fun `둘째 줄부터가 정리 대상이고 정본은 남는다`() {
        val rows = listOf(
            change(BIRTH, 1301, "1301", id = 2),
            change(BIRTH, 1303, "1303", id = 9),
            change(ALIVE, 0, "alive", id = 3),
            change(ALIVE, 0, "dead", id = 8)
        )
        val strays = SingletonStateChanges.strays(rows)
        assertEquals(listOf(8L, 9L), strays.map { it.id }.sorted())
        // 정본은 정리 대상이 아니다.
        assertTrue(strays.none { it.id == 2L || it.id == 3L })
    }

    /** 일반 필드의 이력은 여러 줄이 정상이므로 **한 줄도 지우지 않는다.** */
    @Test
    fun `일반 필드의 이력은 정리하지 않는다`() {
        val rows = listOf(
            change("affiliation", 1300, "월아", id = 1),
            change("affiliation", 1350, "카노스", id = 2),
            change("affiliation", 1400, "엑실리온", id = 3)
        )
        assertEquals(emptyList<CharacterStateChange>(), SingletonStateChanges.strays(rows))
        // 그래도 pick은 답한다 — 그때 뜻은 '가장 이른 이력'이다.
        assertEquals(1300, SingletonStateChanges.pick(rows, "affiliation")?.year)
    }

    @Test
    fun `정리 뒤에는 정리할 것이 없다`() {
        val rows = listOf(
            change(BIRTH, 1301, id = 2), change(BIRTH, 1303, id = 9), change(BIRTH, 1305, id = 11)
        )
        val kept = rows - SingletonStateChanges.strays(rows).toSet()
        assertEquals(1, kept.size)
        assertEquals(emptyList<CharacterStateChange>(), SingletonStateChanges.strays(kept))
    }
}
