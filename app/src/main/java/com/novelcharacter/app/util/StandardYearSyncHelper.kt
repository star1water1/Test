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
    companion object {
        const val KEY_STD_YEAR_LINK = "__std_year_link"
        const val LINK_AUTO = "auto"
        const val LINK_NONE = "none"

        const val RULE_AGE_ANCHOR = "age_anchor"
        const val RULE_BIRTH_ANCHOR = "birth_anchor"
    }

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
                JSONObject(it.config).optString("linkageRule", RULE_AGE_ANCHOR)
            } catch (_: Exception) { RULE_AGE_ANCHOR }
        } ?: RULE_AGE_ANCHOR

        for (character in characters) {
            if (!isLinked(character.id)) continue
            val values = characterRepository.getValuesByCharacterList(character.id)

            val birthYearVal = birthYearField?.let { field ->
                values.find { it.fieldDefinitionId == field.id }?.value?.toIntOrNull()
            }
            val ageVal = ageField?.let { field ->
                values.find { it.fieldDefinitionId == field.id }?.value?.toIntOrNull()
            }

            when (linkageRule) {
                RULE_AGE_ANCHOR -> {
                    // age 유지, birthYear 재계산
                    if (ageVal != null && birthYearField != null) {
                        val newBirthYear = newStdYear - ageVal
                        upsertFieldValue(character.id, birthYearField.id, newBirthYear.toString())
                        upsertStateChange(character.id, CharacterStateChange.KEY_BIRTH, newBirthYear, null, null)
                    }
                }
                RULE_BIRTH_ANCHOR -> {
                    // birthYear 유지, age 재계산
                    if (birthYearVal != null && ageField != null) {
                        val newAge = newStdYear - birthYearVal
                        upsertFieldValue(character.id, ageField.id, if (newAge >= 0) newAge.toString() else "")
                    }
                }
            }
        }
    }

    /**
     * 캐릭터가 standardYear 연동이 활성인지 확인.
     * __std_year_link CharacterStateChange가 "none"이면 비활성, 그 외(없거나 "auto") 활성.
     */
    suspend fun isLinked(characterId: Long): Boolean {
        val changes = characterRepository.getChangesByCharacterList(characterId)
        val linkChange = changes.find { it.fieldKey == KEY_STD_YEAR_LINK }
        return linkChange?.newValue != LINK_NONE
    }

    /**
     * 캐릭터의 standardYear 연동 토글 설정.
     */
    suspend fun setLinked(characterId: Long, linked: Boolean) {
        val value = if (linked) LINK_AUTO else LINK_NONE
        val changes = characterRepository.getChangesByCharacterList(characterId)
        val existing = changes.find { it.fieldKey == KEY_STD_YEAR_LINK }
        if (existing != null) {
            characterRepository.updateStateChange(existing.copy(newValue = value))
        } else {
            characterRepository.insertStateChange(
                CharacterStateChange(
                    characterId = characterId,
                    year = 0,
                    fieldKey = KEY_STD_YEAR_LINK,
                    newValue = value
                )
            )
        }
    }

    private suspend fun upsertFieldValue(characterId: Long, fieldId: Long, value: String) {
        val existing = characterRepository.getFieldValue(characterId, fieldId)
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

    private suspend fun upsertStateChange(
        characterId: Long,
        fieldKey: String,
        year: Int,
        month: Int?,
        day: Int?
    ) {
        val changes = characterRepository.getChangesByCharacterList(characterId)
        val existing = changes.find { it.fieldKey == fieldKey }
        if (existing != null) {
            characterRepository.updateStateChange(
                existing.copy(
                    year = if (year != 0) year else existing.year,
                    month = month ?: existing.month,
                    day = day ?: existing.day,
                    newValue = year.toString()
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
