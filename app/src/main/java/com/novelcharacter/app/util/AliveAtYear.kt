package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange

/**
 * **어느 해에 살아 있는가** — 이 판정의 단일 소스 (순수 로직, JVM 시험 대상).
 *
 * ## 왜 모았는가 — 어휘가 갈려 있었다
 *
 * 상태변화 표의 `__alive` 행이 담는 값은 `"dead"`·`"alive"`·`"unknown"`이다. 그런데 그
 * 값을 화면 표시용으로 번역한 **출력 어휘**가 `"false"`·`"true"`이고, 관계도 시간뷰가
 * **원본 행을 그 출력 어휘로 읽고 있었다** — `newValue == "false"`. 원본에 `"false"`가
 * 들어오는 경로는 없으므로 그 비교는 **언제나 거짓**이고, 게다가 바로 다음 줄의 `continue`가
 * `__death` 폴백까지 막았다. 결과: `__alive` 행이 있는 캐릭터는 시간뷰에서 †도 회색도
 * 뜨지 않는다 — *사망을 적어 두었는데 시간뷰만 모른다.*
 *
 * 그 자리가 놓친 보정이 하나 더 있었다: `"dead"`라도 **사망연도 이전 시점**이면 그 해에는
 * 살아 있다. 해석 계층은 그 보정을 하고 있었고 관계도만 하지 않았다.
 *
 * 그래서 판정을 여기 한 자리로 내리고 두 소비처가 이것을 부른다. 어휘가 다시 갈릴 자리가
 * 원리적으로 없어진다(R-51 — 같은 술어·같은 범위·같은 표본).
 */
object AliveAtYear {

    /**
     * @property DEAD 그 해에 죽어 있다
     * @property ALIVE 살아 있다
     * @property UNKNOWN *모른다고 적혀 있다* — 사용자가 그렇게 고른 것이다(빈 표시)
     * @property UNSET 판정할 재료가 없다 — [UNKNOWN]과 다르다(표시 자체를 하지 않는다)
     */
    enum class Verdict { DEAD, ALIVE, UNKNOWN, UNSET }

    /**
     * @param changes 한 캐릭터의 상태변화 전량(연도 필터는 이 함수가 한다)
     * @param targetYear 판정할 해
     */
    fun resolve(changes: List<CharacterStateChange>, targetYear: Int): Verdict {
        val relevant = changes
            .filter { it.year <= targetYear }
            .sortedWith(compareBy({ it.year }, { it.month ?: 0 }, { it.day ?: 0 }, { it.id }))

        fun yearOf(change: CharacterStateChange?): Int? = change?.let {
            it.newValue.toIntOrNull() ?: if (it.newValue.isBlank()) it.year else null
        }

        val deathYear = yearOf(relevant.findLast { it.fieldKey == CharacterStateChange.KEY_DEATH })
        val birthYear = yearOf(relevant.findLast { it.fieldKey == CharacterStateChange.KEY_BIRTH })
        val aliveChange = relevant.findLast { it.fieldKey == CharacterStateChange.KEY_ALIVE }

        if (aliveChange != null) {
            // 사망연도 기반 보정 — 사망 이전 시점이면 살아 있다(적힌 것보다 시점이 이긴다).
            if (deathYear != null && targetYear < deathYear) return Verdict.ALIVE
            return when (aliveChange.newValue) {
                "dead" -> Verdict.DEAD
                "alive" -> Verdict.ALIVE
                else -> Verdict.UNKNOWN   // "unknown" 및 사용자 자유 입력
            }
        }
        // 하위호환: `__alive`가 없으면 출생·사망으로 셈한다.
        if (deathYear != null && targetYear >= deathYear) return Verdict.DEAD
        if (birthYear != null && targetYear >= birthYear) return Verdict.ALIVE
        return Verdict.UNSET
    }
}
