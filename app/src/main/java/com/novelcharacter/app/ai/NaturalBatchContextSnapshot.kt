package com.novelcharacter.app.ai

/** Flat journal form: Gson cannot safely reconstruct a map keyed by Pair references. */
data class NaturalBatchContextSnapshot(
    val characters: List<RefCharacter>, val fields: List<RefField>,
    val factions: List<RefFaction>, val relationships: List<RefRelationship>,
    val values: List<RefValue>, val omitted: List<String>,
    val relationshipTypes: List<String>? = null
) {
    data class RefCharacter(val ref: String, val value: NaturalBatchContext.Character)
    data class RefField(val ref: String, val value: NaturalBatchContext.Field)
    data class RefFaction(val ref: String, val value: NaturalBatchContext.Faction)
    data class RefRelationship(val ref: String, val value: NaturalBatchContext.Relationship)
    data class RefValue(val characterRef: String, val fieldRef: String, val value: String)

    fun restore() = NaturalBatchContext(
        characters.associate { it.ref to it.value }, fields.associate { it.ref to it.value },
        factions.associate { it.ref to it.value }, relationships.associate { it.ref to it.value },
        values.associate { (it.characterRef to it.fieldRef) to it.value }, omitted, relationshipTypes)

    companion object {
        fun from(context: NaturalBatchContext) = NaturalBatchContextSnapshot(
            context.characters.map { RefCharacter(it.key, it.value) },
            context.fields.map { RefField(it.key, it.value) },
            context.factions.map { RefFaction(it.key, it.value) },
            context.relationships.map { RefRelationship(it.key, it.value) },
            context.values.map { RefValue(it.key.first, it.key.second, it.value) },
            context.omitted, context.relationshipTypes)
    }
}
