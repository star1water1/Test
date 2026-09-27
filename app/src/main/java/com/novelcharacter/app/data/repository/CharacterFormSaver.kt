package com.novelcharacter.app.data.repository

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.util.SemanticFieldSyncHelper

/** The form's entity, covered fields, semantic values and tags commit together. */
class CharacterFormSaver(
    private val db: AppDatabase,
    private val characters: CharacterRepository,
    private val universes: UniverseRepository,
    private val novels: NovelRepository
) {
    data class Saved(val id: Long, val preserved: Int, val keptGlobal: Int)

    suspend fun save(
        character: Character,
        values: List<CharacterFieldValue>,
        tags: List<String>,
        coveredIds: Set<Long>,
        updating: Boolean,
        crossUniverseId: Long?,
        leavingUniverse: Boolean
    ): Saved {
        val saved = db.withTransaction {
            // This synchronizer belongs to this transaction. A shared synchronizer can hold its
            // mutex while waiting for Room, producing the inverse lock order of an atomic form save.
            val semantic = SemanticFieldSyncHelper(characters, universes, novels)
            var preserved = 0
            var keptGlobal = 0
            val id: Long
            val syncedValues: List<CharacterFieldValue>
            val clearable: Set<Long>?
            if (!updating) {
                id = characters.insertCharacter(character)
                syncedValues = values.map { it.copy(characterId = id) }
                characters.saveAllFieldValues(id, syncedValues, runPostCommit = false)
                clearable = null
            } else {
                id = character.id
                when {
                    crossUniverseId != null -> {
                        val counts = characters.updateCharacterAcrossUniverse(character, values, crossUniverseId, runPostCommit = false)
                        keptGlobal = counts.keptGlobalValues
                    }
                    leavingUniverse -> {
                        val counts = characters.updateCharacterLeavingUniverse(character, values, coveredIds, runPostCommit = false)
                        preserved = counts.preserved
                        keptGlobal = counts.kept
                    }
                    else -> preserved = characters.updateCharacterWithFields(character, values, coveredIds, runPostCommit = false)
                }
                val moved = crossUniverseId != null || leavingUniverse
                syncedValues = if (moved) characters.getValuesByCharacterList(id) else values
                clearable = if (moved) null else coveredIds
            }
            val universeId = character.novelId?.let { novels.getNovelById(it)?.universeId }
            semantic.syncFieldToStateChange(id, universeId, syncedValues, clearable)
            characters.replaceAllTagsForCharacter(id, tags.map { CharacterTag(characterId = id, tag = it) })
            Saved(id, preserved, keptGlobal)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            characters.finishFormSave(saved.id, pruneMoveBackup = updating && crossUniverseId != null)
        }
        return saved
    }
}
