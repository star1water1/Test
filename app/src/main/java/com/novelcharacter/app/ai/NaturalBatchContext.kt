package com.novelcharacter.app.ai

/** Only these app-owned references may be returned by a batch analysis request. */
data class NaturalBatchContext(
    val characters: Map<String, Character>,
    val fields: Map<String, Field>,
    val factions: Map<String, Faction>,
    val relationships: Map<String, Relationship>,
    val values: Map<Pair<String, String>, String>,
    val omitted: List<String>
) {
    data class Character(
        val id: Long, val novelId: Long?, val universeId: Long?, val name: String,
        val aliases: List<String>, val code: String, val workTitle: String?,
        val novelCode: String? = null, val universeCode: String? = null
    )
    data class Field(
        val id: Long, val universeId: Long?, val name: String, val key: String,
        val type: String, val config: String
    )
    data class Faction(val id: Long, val universeId: Long, val name: String, val code: String)
    data class Relationship(
        val id: Long, val firstId: Long, val secondId: Long, val type: String,
        val description: String, val bidirectional: Boolean, val intensity: Int
    )

    fun refs() = NaturalBatchRefs(characters.keys, fields.keys, relationships.keys, factions.keys)
}

/** Scope checks are repeated here even when the database query was scoped. */
object NaturalBatchContextSelector {
    fun mentionedFieldIds(input: NaturalBatchInput, fields: Collection<NaturalBatchContext.Field>): Set<Long> =
        fields.filter { field -> listOf(field.name, field.key).any { term ->
            term.isNotBlank() && input.text.contains(term, ignoreCase = true)
        } }.map { it.id }.toSet()

    fun select(
        input: NaturalBatchInput,
        workUniverseId: Long?,
        characters: List<NaturalBatchContext.Character>,
        fields: List<NaturalBatchContext.Field>,
        factions: List<NaturalBatchContext.Faction>,
        relationships: List<NaturalBatchContext.Relationship>,
        values: Map<Pair<Long, Long>, String>
    ): NaturalBatchContext {
        val scope = input.scope
        val universeId = if (scope.kind == NaturalBatchInput.ScopeKind.UNIVERSE) scope.id else workUniverseId
        val scopedCharacters = characters.filter { character ->
            if (scope.kind == NaturalBatchInput.ScopeKind.WORK) character.novelId == scope.id &&
                character.universeId == workUniverseId
            else character.universeId == scope.id
        }
        // Names and aliases remain separate candidates. A duplicate must never resolve to the first row.
        val mentioned = scopedCharacters.filter { character ->
            (listOf(character.name, character.code) + character.aliases).any { term ->
                term.isNotBlank() && input.text.contains(term, ignoreCase = true)
            }
        }.sortedBy { it.id }
        val selectedIds = mentioned.map { it.id }.toSet()
        val selectedFields = fields.filter {
            it.universeId == universeId && it.type.isNotBlank()
        }.sortedBy { it.id }
        val selectedFactions = if (universeId == null) emptyList() else factions.filter {
            it.universeId == universeId && listOf(it.name, it.code).any { term ->
                term.isNotBlank() && input.text.contains(term, ignoreCase = true)
            }
        }.sortedBy { it.id }
        val selectedRelationships = relationships.filter {
            it.firstId in selectedIds && it.secondId in selectedIds
        }.sortedBy { it.id }
        val characterRefs = mentioned.mapIndexed { index, it -> "c${index + 1}" to it }.toMap()
        val fieldRefs = selectedFields.mapIndexed { index, it -> "f${index + 1}" to it }.toMap()
        val factionRefs = selectedFactions.mapIndexed { index, it -> "a${index + 1}" to it }.toMap()
        val relationshipRefs = selectedRelationships.mapIndexed { index, it -> "r${index + 1}" to it }.toMap()
        val characterRefById = characterRefs.map { (ref, character) -> character.id to ref }.toMap()
        val fieldRefById = fieldRefs.map { (ref, field) -> field.id to ref }.toMap()
        val includedIds = fieldRefById.keys
        val valueFieldIds = mentionedFieldIds(input, selectedFields)
        val scopedValues = buildMap {
            values.forEach { (ids, value) ->
                val characterRef = characterRefById[ids.first]
                val fieldRef = fieldRefById[ids.second]
                if (characterRef != null && fieldRef != null && ids.second in valueFieldIds)
                    put(characterRef to fieldRef, value)
            }
        }
        val omitted = buildList {
            if (mentioned.isEmpty()) add("입력에서 범위 안의 인물을 찾지 못했습니다. 대상은 해석 불가로 남겨야 합니다.")
            val otherFields = includedIds.size - valueFieldIds.size
            if (otherFields > 0) add("입력에 이름이나 키가 없는 ${otherFields}개 필드의 기존값은 전송하지 않았습니다.")
            val unrelatedValues = values.keys.count { it.first in selectedIds && it.second !in includedIds }
            if (unrelatedValues > 0) add("현재 범위 밖 필드값 ${unrelatedValues}건은 전송하지 않았습니다.")
        }
        return NaturalBatchContext(characterRefs, fieldRefs, factionRefs, relationshipRefs,
            scopedValues, omitted)
    }
}
