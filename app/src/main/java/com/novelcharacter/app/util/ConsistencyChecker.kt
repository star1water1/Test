package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.ui.stats.StatsSnapshot

/**
 * 스냅샷 하나만으로 캐릭터 데이터의 "진짜 모순"을 배치 검사한다.
 *
 * 변수 제어(개발 의도): 잘못된 입력을 조용히 넘기지 않고 찾아내 사용자에게 알린다.
 * 단건 DB 조회(N회)를 피하고 [StatsSnapshot] 인메모리 데이터만 사용하므로,
 * 캐릭터 수백 명·필드 수십 개에서도 한 번의 계산으로 끝난다(받쳐주는 확장성).
 *
 * 검사 로직은 기존 `CharacterViewModel.detectAgeLinkageConflict`(저장 전 단건 검사)와
 * 동일한 규칙을 배치화한 것이다.
 */
object ConsistencyChecker {

    /** 나이 필드값과 출생연도 필드값이 작품 표준연도와 어긋나는 경우. */
    data class AgeMismatch(
        val characterId: Long,
        val characterName: String,
        val novelId: Long,
        val inputAge: Int,
        val inputBirthYear: Int,
        val standardYear: Int,
        val expectedAge: Int,
        /** 나이를 신뢰할 때의 올바른 출생연도(= 표준연도 - 나이). */
        val suggestedBirthYear: Int
    )

    /** 사망연도가 출생연도보다 앞서는 경우(__birth/__death 상태변화 비교). */
    data class DeathBeforeBirth(
        val characterId: Long,
        val characterName: String,
        val birthYear: Int,
        val deathYear: Int
    )

    data class Result(
        val ageMismatches: List<AgeMismatch>,
        val deathBeforeBirth: List<DeathBeforeBirth>
    ) {
        val total: Int get() = ageMismatches.size + deathBeforeBirth.size
    }

    fun check(s: StatsSnapshot): Result =
        Result(
            ageMismatches = checkAgeMismatches(s),
            deathBeforeBirth = checkDeathBeforeBirth(s)
        )

    private fun checkAgeMismatches(s: StatsSnapshot): List<AgeMismatch> {
        val novelsWithStdYear = s.novels.filter { it.standardYear != null && it.universeId != null }
        if (novelsWithStdYear.isEmpty()) return emptyList()

        val fieldsByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val valuesByChar = s.fieldValues.groupBy { it.characterId }
        // 표준연도 연동을 명시적으로 해제(__std_year_link = none)한 캐릭터는
        // 나이/출생연도 불일치가 의도된 것이므로 검사에서 제외한다.
        val unlinkedCharIds = s.stateChanges
            .filter {
                it.fieldKey == StandardYearSyncHelper.KEY_STD_YEAR_LINK &&
                    it.newValue == StandardYearSyncHelper.LINK_NONE
            }
            .map { it.characterId }
            .toSet()

        val result = mutableListOf<AgeMismatch>()
        for (novel in novelsWithStdYear) {
            val stdYear = novel.standardYear ?: continue
            val fields = fieldsByUniverse[novel.universeId] ?: continue
            val ageField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.AGE } ?: continue
            val birthYearField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.BIRTH_YEAR }
                ?: continue

            for (char in s.characters) {
                if (char.novelId != novel.id) continue
                if (char.id in unlinkedCharIds) continue
                val vals = valuesByChar[char.id] ?: continue
                val age = vals.find { it.fieldDefinitionId == ageField.id }
                    ?.value?.trim()?.toIntOrNull() ?: continue
                val birthYear = vals.find { it.fieldDefinitionId == birthYearField.id }
                    ?.value?.trim()?.toIntOrNull() ?: continue

                val expectedAge = stdYear - birthYear
                if (age != expectedAge) {
                    result.add(
                        AgeMismatch(
                            characterId = char.id,
                            characterName = char.name,
                            novelId = novel.id,
                            inputAge = age,
                            inputBirthYear = birthYear,
                            standardYear = stdYear,
                            expectedAge = expectedAge,
                            suggestedBirthYear = stdYear - age
                        )
                    )
                }
            }
        }
        return result
    }

    private fun checkDeathBeforeBirth(s: StatsSnapshot): List<DeathBeforeBirth> {
        val changesByChar = s.stateChanges.groupBy { it.characterId }
        val charById = s.characters.associateBy { it.id }

        val result = mutableListOf<DeathBeforeBirth>()
        for ((charId, changes) in changesByChar) {
            val char = charById[charId] ?: continue
            val birth = changes.find { it.fieldKey == CharacterStateChange.KEY_BIRTH }?.year
            val death = changes.find { it.fieldKey == CharacterStateChange.KEY_DEATH }?.year
            // year 0은 "연도 미상" placeholder일 수 있으므로 오탐 방지를 위해 제외한다.
            if (birth != null && death != null && birth != 0 && death != 0 && death < birth) {
                result.add(
                    DeathBeforeBirth(
                        characterId = charId,
                        characterName = char.name,
                        birthYear = birth,
                        deathYear = death
                    )
                )
            }
        }
        return result
    }
}
