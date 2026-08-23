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
        val ordered = changes
            .sortedWith(compareBy({ it.year }, { it.month ?: 0 }, { it.day ?: 0 }, { it.id }))
        val relevant = ordered.filter { it.year <= targetYear }

        fun yearOf(change: CharacterStateChange?): Int? = change?.let {
            it.newValue.toIntOrNull() ?: if (it.newValue.isBlank()) it.year else null
        }

        val deathYear = yearOf(relevant.findLast { it.fieldKey == CharacterStateChange.KEY_DEATH })
        val birthYear = yearOf(relevant.findLast { it.fieldKey == CharacterStateChange.KEY_BIRTH })
        val aliveChange = relevant.findLast { it.fieldKey == CharacterStateChange.KEY_ALIVE }
        // **걸러내지 않은 목록에서 읽는 출생** — 아래 갈래가 쓴다. 연도 필터가 두 행을
        // 비대칭으로 다루기 때문이다: `__alive` 행은 `year = 0`으로 들어오므로
        // (`SemanticFieldSyncHelper.upsertAliveStateChange`) **어느 시점에서도 남는데**
        // 출생 행은 미래라 걸러진다. 그래서 `__alive`가 있는 캐릭터는 태어나기 전 해에도
        // '생존'으로 판정됐고, `__alive`가 없는 하위호환 갈래는 같은 데이터에 UNSET을 냈다 —
        // **행 하나의 유무가 답을 갈랐다.**
        //
        // **가장 이른 것을 고른다.** `__birth`는 캐릭터당 한 행이 불변식이지만 엑셀·복원이
        // 두 행을 만들 수 있고, 그때 *마지막*을 고르면 두 갈래가 **다시** 갈린다:
        // 500과 1000 두 행에 targetYear 700이면 이쪽은 1000을 보고 UNSET, 아래 갈래는
        // 걸러진 목록에서 500을 보고 ALIVE다. 가장 이른 것을 고르면 *어느 선언보다도 앞선
        // 해*에만 UNSET이라 두 갈래의 답이 모든 경우에 같아진다.
        val declaredBirthYear = ordered
            .filter { it.fieldKey == CharacterStateChange.KEY_BIRTH }
            .mapNotNull { yearOf(it) }
            .minOrNull()

        if (aliveChange != null) {
            // 태어나기 전이면 판정할 것이 없다 — 아래 하위호환 갈래와 **같은 답**이다.
            if (declaredBirthYear != null && targetYear < declaredBirthYear) return Verdict.UNSET
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
