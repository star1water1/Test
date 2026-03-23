package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 커스텀 필드(FieldDefinition)와 시스템 특수 필드(CharacterStateChange)를
 * SemanticRole 기반으로 양방향 동기화하는 헬퍼.
 */
class SemanticFieldSyncHelper(
    private val characterRepository: CharacterRepository,
    private val universeRepository: UniverseRepository,
    private val novelRepository: NovelRepository? = null
) {
    private val standardYearSyncHelper = StandardYearSyncHelper(characterRepository, universeRepository)
    // 양방향 동기화를 직렬화하는 단일 뮤텍스.
    // 동시에 양쪽 방향이 실행되어 비결정적 상태가 되는 것을 방지.
    private val syncMutex = Mutex()

    /**
     * 방향 1: 커스텀 필드값 저장 후 → CharacterStateChange 동기화.
     * 캐릭터의 필드값 목록에서 semanticRole이 있는 필드를 찾아 대응하는 상태변화를 생성/갱신.
     */
    suspend fun syncFieldToStateChange(
        characterId: Long,
        universeId: Long,
        values: List<CharacterFieldValue>
    ) = syncMutex.withLock {
        val fields = universeRepository.getFieldsByUniverseList(universeId)
        val fieldMap = fields.associateBy { it.id }

        for (value in values) {
            val field = fieldMap[value.fieldDefinitionId] ?: continue
            val role = SemanticRole.fromConfig(field.config) ?: continue

            when (role) {
                SemanticRole.BIRTH_YEAR -> {
                    val year = value.value.trim().toIntOrNull() ?: continue
                    upsertStateChange(characterId, CharacterStateChange.KEY_BIRTH, year, null, null)
                    // standardYear가 있고 연동 활성이면 age 필드도 갱신
                    syncBirthYearToAge(characterId, year, fields)
                }
                SemanticRole.BIRTH_DATE -> {
                    val parts = parseBirthDate(value.value) ?: continue
                    val existingBirth = findStateChange(characterId, CharacterStateChange.KEY_BIRTH)
                    val year = existingBirth?.year ?: 0
                    upsertStateChange(characterId, CharacterStateChange.KEY_BIRTH, year, parts.first, parts.second)
                }
                SemanticRole.DEATH_YEAR -> {
                    val year = value.value.trim().toIntOrNull() ?: continue
                    upsertStateChange(characterId, CharacterStateChange.KEY_DEATH, year, null, null)
                }
                SemanticRole.AGE -> {
                    // 나이 → 출생연도 역산: birthYear = standardYear - age
                    val age = value.value.trim().toIntOrNull() ?: continue
                    val novel = getNovelForCharacter(characterId) ?: continue
                    val stdYear = novel.standardYear ?: continue
                    if (!standardYearSyncHelper.isLinked(characterId)) continue
                    val birthYear = stdYear - age
                    upsertStateChange(characterId, CharacterStateChange.KEY_BIRTH, birthYear, null, null)
                    // birth_year 필드도 갱신
                    val birthYearField = findFieldByRole(fields, SemanticRole.BIRTH_YEAR)
                    if (birthYearField != null) {
                        upsertFieldValue(characterId, birthYearField.id, birthYear.toString())
                    }
                }
                else -> { /* HEIGHT, WEIGHT, BODY_SIZE — 동기화 불필요 */ }
            }
        }
    }

    /**
     * 방향 2: CharacterStateChange 저장 후 → 커스텀 필드값 동기화.
     * 해당 유니버스에 대응 semanticRole 필드가 있으면 CharacterFieldValue를 갱신.
     */
    suspend fun syncStateChangeToField(
        characterId: Long,
        universeId: Long,
        change: CharacterStateChange
    ) = syncMutex.withLock {
        val fields = universeRepository.getFieldsByUniverseList(universeId)

        when (change.fieldKey) {
            CharacterStateChange.KEY_BIRTH -> {
                // year → birth_year 필드
                val birthYearField = findFieldByRole(fields, SemanticRole.BIRTH_YEAR)
                if (birthYearField != null && change.year != 0) {
                    upsertFieldValue(characterId, birthYearField.id, change.year.toString())
                }
                // month/day → birth_date 필드
                val birthDateField = findFieldByRole(fields, SemanticRole.BIRTH_DATE)
                if (birthDateField != null && change.month != null && change.day != null) {
                    val dateStr = String.format("%02d-%02d", change.month, change.day)
                    upsertFieldValue(characterId, birthDateField.id, dateStr)
                }
            }
            CharacterStateChange.KEY_DEATH -> {
                val deathYearField = findFieldByRole(fields, SemanticRole.DEATH_YEAR)
                if (deathYearField != null && change.year != 0) {
                    upsertFieldValue(characterId, deathYearField.id, change.year.toString())
                }
            }
        }
    }

    private fun findFieldByRole(fields: List<FieldDefinition>, role: SemanticRole): FieldDefinition? {
        return fields.find { SemanticRole.fromConfig(it.config) == role }
    }

    /**
     * "MM-DD" 또는 "M-D" 형식의 생일 문자열을 파싱.
     * @return Pair(month, day) 또는 null
     */
    private fun parseBirthDate(value: String): Pair<Int, Int>? {
        val trimmed = value.trim()
        val parts = trimmed.split("-", "/", ".")
        if (parts.size != 2) return null
        val month = parts[0].trim().toIntOrNull() ?: return null
        val day = parts[1].trim().toIntOrNull() ?: return null
        if (month !in 1..12 || !isValidDay(month, day)) return null
        return month to day
    }

    private suspend fun findStateChange(characterId: Long, fieldKey: String): CharacterStateChange? {
        val changes = characterRepository.getChangesByCharacterList(characterId)
        return changes.find { it.fieldKey == fieldKey }
    }

    private suspend fun upsertStateChange(
        characterId: Long,
        fieldKey: String,
        year: Int,
        month: Int?,
        day: Int?
    ) {
        val existing = findStateChange(characterId, fieldKey)
        if (existing != null) {
            characterRepository.updateStateChange(
                existing.copy(
                    year = if (year != 0) year else existing.year,
                    month = month ?: existing.month,
                    day = day ?: existing.day,
                    newValue = buildStateChangeValue(fieldKey, if (year != 0) year else existing.year)
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
                    newValue = buildStateChangeValue(fieldKey, year)
                )
            )
        }
    }

    private fun buildStateChangeValue(fieldKey: String, year: Int): String {
        return when (fieldKey) {
            CharacterStateChange.KEY_BIRTH -> year.toString()
            CharacterStateChange.KEY_DEATH -> year.toString()
            else -> ""
        }
    }

    /**
     * 출생연도 변경 시, standardYear가 있고 연동 활성이면 age 필드를 자동 갱신.
     */
    private suspend fun syncBirthYearToAge(characterId: Long, birthYear: Int, fields: List<FieldDefinition>) {
        val novel = getNovelForCharacter(characterId) ?: return
        val stdYear = novel.standardYear ?: return
        if (!standardYearSyncHelper.isLinked(characterId)) return
        val ageField = findFieldByRole(fields, SemanticRole.AGE) ?: return
        val age = stdYear - birthYear
        upsertFieldValue(characterId, ageField.id, if (age >= 0) age.toString() else "")
    }

    private suspend fun getNovelForCharacter(characterId: Long): com.novelcharacter.app.data.model.Novel? {
        if (novelRepository == null) return null
        val character = characterRepository.getCharacterById(characterId) ?: return null
        val novelId = character.novelId ?: return null
        return novelRepository.getNovelById(novelId)
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
}
