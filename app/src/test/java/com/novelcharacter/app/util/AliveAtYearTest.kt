package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AliveAtYear] — 어느 해에 살아 있는가. **어휘가 갈리지 않게 잠근다.**
 *
 * 이 판정이 두 벌로 적혀 있던 동안 한쪽이 `__alive` 행을 *해석 계층의 출력 어휘*(`"false"`)로
 * 읽었다 — 원본에 그 값이 들어오는 경로가 없으므로 그 비교는 언제나 거짓이었고, 사망을 적어
 * 둔 캐릭터가 관계도 시간뷰에서만 살아 있는 것으로 그려졌다.
 */
class AliveAtYearTest {

    private fun change(key: String, year: Int, value: String, id: Long = 0) =
        CharacterStateChange(id = id, characterId = 1L, year = year, fieldKey = key, newValue = value)

    private val ALIVE = CharacterStateChange.KEY_ALIVE
    private val DEATH = CharacterStateChange.KEY_DEATH
    private val BIRTH = CharacterStateChange.KEY_BIRTH

    @Test
    fun `dead가 적혀 있으면 죽은 것이다`() {
        val changes = listOf(change(ALIVE, 1000, "dead"))
        assertEquals(AliveAtYear.Verdict.DEAD, AliveAtYear.resolve(changes, 1000))
        assertEquals(AliveAtYear.Verdict.DEAD, AliveAtYear.resolve(changes, 1005))
    }

    /** 원본 어휘는 `dead`/`alive`다 — 출력 어휘(`false`/`true`)로 읽으면 아무것도 안 걸린다. */
    @Test
    fun `출력 어휘는 원본이 아니다`() {
        val changes = listOf(change(ALIVE, 1000, "false"))
        assertEquals(AliveAtYear.Verdict.UNKNOWN, AliveAtYear.resolve(changes, 1000))
    }

    @Test
    fun `alive가 적혀 있으면 살아 있다`() {
        assertEquals(
            AliveAtYear.Verdict.ALIVE,
            AliveAtYear.resolve(listOf(change(ALIVE, 1000, "alive")), 1000)
        )
    }

    @Test
    fun `unknown과 자유 입력은 모른다`() {
        assertEquals(
            AliveAtYear.Verdict.UNKNOWN,
            AliveAtYear.resolve(listOf(change(ALIVE, 1000, "unknown")), 1000)
        )
        assertEquals(
            AliveAtYear.Verdict.UNKNOWN,
            AliveAtYear.resolve(listOf(change(ALIVE, 1000, "행방불명")), 1000)
        )
    }

    /** 적힌 것보다 **시점**이 이긴다 — 사망 이전 해에는 살아 있다. */
    @Test
    fun `사망연도 이전이면 dead여도 살아 있다`() {
        val changes = listOf(
            change(ALIVE, 1000, "dead", id = 1),
            change(DEATH, 1000, "1010", id = 2)
        )
        assertEquals(AliveAtYear.Verdict.ALIVE, AliveAtYear.resolve(changes, 1005))
        assertEquals(AliveAtYear.Verdict.DEAD, AliveAtYear.resolve(changes, 1010))
    }

    @Test
    fun `alive 행이 없으면 사망 기록으로 센다`() {
        val changes = listOf(change(DEATH, 1010, "1010"))
        assertEquals(AliveAtYear.Verdict.DEAD, AliveAtYear.resolve(changes, 1010))
        assertEquals(AliveAtYear.Verdict.UNSET, AliveAtYear.resolve(changes, 1005))
    }

    @Test
    fun `사망연도가 비어 있으면 기록된 해를 쓴다`() {
        val changes = listOf(change(DEATH, 1010, ""))
        assertEquals(AliveAtYear.Verdict.DEAD, AliveAtYear.resolve(changes, 1010))
    }

    @Test
    fun `출생 기록만 있으면 그 뒤로 살아 있다`() {
        val changes = listOf(change(BIRTH, 1000, "1000"))
        assertEquals(AliveAtYear.Verdict.ALIVE, AliveAtYear.resolve(changes, 1000))
        assertEquals(AliveAtYear.Verdict.UNSET, AliveAtYear.resolve(changes, 999))
    }

    /** 재료가 없으면 *모른다*가 아니라 **잴 수 없다**이고, 화면이 그 둘을 다르게 그린다. */
    @Test
    fun `재료가 없으면 UNSET이다`() {
        assertEquals(AliveAtYear.Verdict.UNSET, AliveAtYear.resolve(emptyList(), 1000))
    }

    /** 같은 해에 여러 행이면 마지막(월·일·id 순)이 이긴다. */
    @Test
    fun `같은 해의 마지막 기록이 이긴다`() {
        val changes = listOf(
            change(ALIVE, 1000, "dead", id = 1),
            change(ALIVE, 1000, "alive", id = 2)
        )
        assertEquals(AliveAtYear.Verdict.ALIVE, AliveAtYear.resolve(changes, 1000))
    }

    @Test
    fun `태어나기 전이면 alive 행이 있어도 UNSET이다`() {
        // **두 갈래가 같은 답을 낸다.** 연도 필터가 두 행을 비대칭으로 다뤄
        // (`__alive`는 year=0이라 늘 남고 출생 행은 미래라 걸린다) `__alive` 행 하나의
        // 유무가 답을 갈랐다 — 시점 상태 화면이 아직 태어나지 않은 인물을 '생존'으로 그렸다.
        val withAlive = listOf(
            change(ALIVE, 0, "alive"),
            change(BIRTH, 500, "")
        )
        val withoutAlive = listOf(change(BIRTH, 500, ""))
        assertEquals(AliveAtYear.Verdict.UNSET, AliveAtYear.resolve(withAlive, 300))
        assertEquals(AliveAtYear.resolve(withoutAlive, 300), AliveAtYear.resolve(withAlive, 300))
        // 태어난 뒤로는 종전대로 적힌 것이 산다.
        assertEquals(AliveAtYear.Verdict.ALIVE, AliveAtYear.resolve(withAlive, 500))
        assertEquals(AliveAtYear.Verdict.ALIVE, AliveAtYear.resolve(withAlive, 700))
    }
}
