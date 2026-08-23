package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import org.json.JSONObject

/**
 * Novel의 standardYear가 변경되었을 때,
 * 연동 활성 캐릭터들의 birthYear / age를 일괄 재계산하는 헬퍼.
 */
class StandardYearSyncHelper(
    private val characterRepository: CharacterRepository,
    private val universeRepository: UniverseRepository
) {
    // 키·값과 '연동 중인가'의 판정은 [StandardYearLink]가 단일 소스다 (B-251 ⓓ).
    // 여기 companion으로 두면 Room을 아는 이 클래스를 함께 짊어져야 해서
    // 순수한 소비처(ConsistencyChecker)가 순수 하네스에 실리지 못한다.

    /**
     * Novel의 standardYear가 변경되었을 때 호출.
     * 해당 작품의 모든 캐릭터에 대해 연동 규칙에 따라 birthYear 또는 age를 재계산.
     */
    suspend fun onStandardYearChanged(novel: Novel, oldStdYear: Int?, newStdYear: Int?) {
        if (newStdYear == null) return
        val universeId = novel.universeId ?: return
        val characters = characterRepository.getCharactersByNovelList(novel.id)
        val fields = universeRepository.getFieldsByUniverseList(universeId)

        val ageField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.AGE }
        val birthYearField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.BIRTH_YEAR }
        if (ageField == null && birthYearField == null) return

        // 연동 규칙 확인 (세계관 레벨 설정, age 필드의 config에 저장)
        val linkageRule = ageField?.let {
            try {
                JSONObject(it.config).stringOr("linkageRule", StandardYearLink.RULE_AGE_ANCHOR)
            } catch (_: Exception) { StandardYearLink.RULE_AGE_ANCHOR }
        } ?: StandardYearLink.RULE_AGE_ANCHOR

        // **캐릭터마다 묻지 않는다.** 종전에는 이 루프가 인원마다 이력 한 번(연동 판정)과
        // 값 한 번을 물었고, 아래 `upsert*`가 다시 한 번씩 더 물어 **한 사람에 네 질의**였다.
        // 표준연도를 고치는 것은 평범한 조작이고 캐스트는 수백이 될 수 있다('받쳐주는 확장성').
        // 두 표를 먼저 뜨고 루프는 읽기만 한다 — 쓰기는 캐릭터마다 자기 몫만 건드리므로
        // 앞사람의 쓰기가 뒷사람의 표를 낡게 만들지 않는다.
        val ids = characters.map { it.id }
        val changesById = characterRepository.getChangesForCharacters(ids).groupBy { it.characterId }
        val valuesById = characterRepository.getValuesForCharacters(ids).groupBy { it.characterId }

        for (character in characters) {
            val changes = changesById[character.id].orEmpty()
            // 판정은 [StandardYearLink]가 단일 소스다 — [isLinked]가 쓰는 그 함수를 그대로 쓴다.
            if (StandardYearLink.isUnlinkedIn(changes)) continue
            val values = valuesById[character.id].orEmpty()

            val birthYearVal = birthYearField?.let { field ->
                values.find { it.fieldDefinitionId == field.id }?.value?.toIntOrNull()
            }
            val ageVal = ageField?.let { field ->
                values.find { it.fieldDefinitionId == field.id }?.value?.toIntOrNull()
            }

            when (linkageRule) {
                StandardYearLink.RULE_AGE_ANCHOR -> {
                    // age 유지, birthYear 재계산
                    if (ageVal != null && birthYearField != null) {
                        val newBirthYear = newStdYear - ageVal
                        upsertFieldValue(character.id, birthYearField.id, newBirthYear.toString(), values)
                        upsertStateChange(
                            character.id, CharacterStateChange.KEY_BIRTH, newBirthYear, null, null, changes
                        )
                    }
                }
                StandardYearLink.RULE_BIRTH_ANCHOR -> {
                    // birthYear 유지, age 재계산
                    if (birthYearVal != null && ageField != null) {
                        val newAge = newStdYear - birthYearVal
                        upsertFieldValue(
                            character.id, ageField.id, if (newAge >= 0) newAge.toString() else "", values
                        )
                    }
                }
            }
        }
    }

    /**
     * 캐릭터가 standardYear 연동이 활성인지 확인.
     *
     * 판정은 [StandardYearLink.isUnlinkedIn]이 내린다 — 같은 규칙을 [ConsistencyChecker]도
     * 쓰는데, 종전처럼 양쪽이 따로 적으면 **한쪽만 고쳤을 때 어긋난 사실을 아무도 모른다.**
     */
    suspend fun isLinked(characterId: Long): Boolean {
        val changes = characterRepository.getChangesByCharacterList(characterId)
        return !StandardYearLink.isUnlinkedIn(changes)
    }

    /**
     * 캐릭터의 standardYear 연동 토글 설정.
     */
    suspend fun setLinked(characterId: Long, linked: Boolean) {
        val value = StandardYearLink.valueFor(linked)
        val changes = characterRepository.getChangesByCharacterList(characterId)
        val existing = changes.find { it.fieldKey == StandardYearLink.KEY }
        if (existing != null) {
            characterRepository.updateStateChange(existing.copy(newValue = value))
        } else {
            characterRepository.insertStateChange(
                CharacterStateChange(
                    characterId = characterId,
                    year = 0,
                    fieldKey = StandardYearLink.KEY,
                    newValue = value
                )
            )
        }
    }

    /** @param known 이 캐릭터의 값 전체(미리 떠 온 것) — 여기서 찾으면 질의가 하나 준다. */
    private suspend fun upsertFieldValue(
        characterId: Long,
        fieldId: Long,
        value: String,
        known: List<CharacterFieldValue>
    ) {
        val existing = known.find { it.fieldDefinitionId == fieldId }
        if (existing != null) {
            characterRepository.updateFieldValue(existing.copy(value = value))
        } else {
            characterRepository.insertFieldValue(
                CharacterFieldValue(
                    characterId = characterId,
                    fieldDefinitionId = fieldId,
                    value = value
                )
            )
        }
    }

    /** @param changes 이 캐릭터의 이력 전체(미리 떠 온 것) — 여기서 찾으면 질의가 하나 준다. */
    private suspend fun upsertStateChange(
        characterId: Long,
        fieldKey: String,
        year: Int,
        month: Int?,
        day: Int?,
        changes: List<CharacterStateChange>
    ) {
        val existing = changes.find { it.fieldKey == fieldKey }
        if (existing != null) {
            val effectiveYear = if (year != 0) year else existing.year
            characterRepository.updateStateChange(
                existing.copy(
                    year = effectiveYear,
                    month = month ?: existing.month,
                    day = day ?: existing.day,
                    newValue = effectiveYear.toString()
                )
            )
        } else {
            characterRepository.insertStateChange(
                CharacterStateChange(
                    characterId = characterId,
                    year = year,
                    month = month,
                    day = day,
                    fieldKey = fieldKey,
                    newValue = year.toString()
                )
            )
        }
    }
}
