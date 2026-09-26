package com.novelcharacter.app.ai

import com.google.gson.Gson
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor.Decision
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor.Status
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.NaturalBatchAppliedOperation
import com.novelcharacter.app.data.model.NaturalBatchRowChange
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.SqlInChunks

/** Manual relationships only. The caller owns the transaction and the shared execution journal. */
class NaturalBatchRelationshipEdits(private val db: AppDatabase) {
    data class Draft(val type: String, val description: String, val intensity: Int, val bidirectional: Boolean) {
        fun encode(): String = Gson().toJson(this)
        companion object {
            private data class SavedDraft(val type: String?, val description: String?,
                val intensity: Int?, val bidirectional: Boolean?)
            fun from(op: NaturalBatchPlan.Operation, context: NaturalBatchContext, edit: String? = null): Draft? {
                if (edit != null) return try {
                    val saved = Gson().fromJson(edit, SavedDraft::class.java)
                    Draft(requireNotNull(saved.type), requireNotNull(saved.description),
                        requireNotNull(saved.intensity), requireNotNull(saved.bidirectional))
                        .takeIf { it.type.isNotBlank() && it.intensity in 1..10 }
                } catch (_: Exception) { null }
                val old = op.relationshipRef?.let(context.relationships::get)
                return Draft(op.relationshipType ?: old?.type.orEmpty(),
                    op.relationshipDescription ?: old?.description.orEmpty(),
                    op.intensity ?: old?.intensity ?: 5, op.bidirectional ?: old?.bidirectional ?: true)
            }
        }
    }

    data class Item(
        val operation: NaturalBatchPlan.Operation,
        val targetId: Long?,
        val endpoints: List<NaturalBatchContext.Character>,
        val types: List<String>?,
        val original: NaturalBatchContext.Relationship?,
        val before: CharacterRelationship?,
        val after: CharacterRelationship?,
        val history: List<CharacterRelationshipChange>,
        val eventCodes: Map<Long, String?>
    )

    private data class Guard(
        val endpoints: List<NaturalBatchContext.Character>,
        val eventCodes: Map<Long, String?>
    )
    data class Live(
        val characters: Map<Long, Character>, val novels: Map<Long, Novel>,
        val universes: Map<Long, Universe>, val relationships: Map<Long, CharacterRelationship>,
        val history: Map<Long, List<CharacterRelationshipChange>>, val eventCodes: Map<Long, String?>
    )
    data class Preparation(val items: Map<String, Item>, val live: Live)
    private val gson = Gson()
    private val links get() = db.characterRelationshipDao()
    private val historyDao get() = db.characterRelationshipChangeDao()

    /** Read the affected links/history together, never the complete relationship database. */
    suspend fun prepare(operations: List<NaturalBatchPlan.Operation>, context: NaturalBatchContext,
        edits: Map<String, String>): Preparation {
        val ids = operations.flatMap { op ->
            val old = op.relationshipRef?.let(context.relationships::get)
            if (old != null) listOf(old.firstId, old.secondId)
            else listOfNotNull(context.characters[op.targetRef]?.id,
                op.relatedRef?.let { context.characters[it]?.id })
        }.distinct()
        val rows = (SqlInChunks.flat(ids) { links.getRelationshipsByEnd1(it) } +
            SqlInChunks.flat(ids) { links.getRelationshipsByEnd2(it) }).distinctBy { it.id }.associateBy { it.id }
        val removedIds = operations.filter { it.kind == NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP }
            .mapNotNull { it.relationshipRef?.let(context.relationships::get)?.id }.distinct()
        val history = SqlInChunks.flat(removedIds) { historyDao.getChangesForRelationships(it) }
            .groupBy { it.relationshipId }
        val eventIds = history.values.flatten().mapNotNull { it.eventId }.distinct()
        val eventCodes = SqlInChunks.flat(eventIds) { db.timelineDao().getEventsByIds(it) }
            .associate { it.id to it.code }
        val characters = SqlInChunks.flat(ids) { db.characterDao().getCharactersByIds(it) }.associateBy { it.id }
        val novels = SqlInChunks.flat(characters.values.mapNotNull { it.novelId }.distinct()) {
            db.novelDao().getNovelsByIds(it)
        }.associateBy { it.id }
        val universes = novels.values.mapNotNull { it.universeId }.distinct().mapNotNull { id ->
            db.universeDao().getUniverseById(id)?.let { id to it }
        }.toMap()
        val items = operations.associate { op ->
            val original = op.relationshipRef?.let(context.relationships::get)
            val endpointIds = if (original == null) listOfNotNull(context.characters[op.targetRef]?.id,
                op.relatedRef?.let { context.characters[it]?.id }) else listOf(original.firstId, original.secondId)
            val endpoints = endpointIds.mapNotNull { id -> context.characters.values.firstOrNull { it.id == id } }
            val before = original?.id?.let(rows::get)
            val draft = Draft.from(op, context, edits[op.id])
            val after = when (op.kind) {
                NaturalBatchPlan.Kind.ADD_RELATIONSHIP -> if (endpoints.size != 2 || draft == null) null else
                    CharacterRelationship(characterId1 = endpoints[0].id, characterId2 = endpoints[1].id,
                        relationshipType = draft.type, description = draft.description,
                        intensity = draft.intensity, isBidirectional = draft.bidirectional)
                NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP -> if (draft == null) null else before?.copy(
                    relationshipType = draft.type, description = draft.description,
                    intensity = draft.intensity, isBidirectional = draft.bidirectional)
                else -> null
            }
            op.id to Item(op, context.characters[op.targetRef]?.id, endpoints, context.relationshipTypes, original, before, after,
                original?.id?.let { history[it] }.orEmpty(), eventCodes)
        }
        return Preparation(items, Live(characters, novels, universes, rows, history, eventCodes))
    }

    /** Detect collisions after resolving references, including an add against a referenced update/removal. */
    fun conflicts(items: Collection<Item>): Set<String> {
        fun keys(item: Item): Set<String> {
            if (item.endpoints.size != 2) return emptySet()
            val pair = item.endpoints.map { it.id }.sorted().joinToString(":")
            return listOfNotNull(item.before?.relationshipType, item.after?.relationshipType)
                .map { "$pair:$it" }.toSet()
        }
        return items.filter { item -> items.any { other ->
            other.operation.id != item.operation.id && keys(item).intersect(keys(other)).isNotEmpty()
        } }.map { it.operation.id }.toSet()
    }

    private suspend fun validEndpoints(input: NaturalBatchInput?, endpoints: List<NaturalBatchContext.Character>,
        live: Live? = null): Boolean {
        if (endpoints.size != 2 || endpoints[0].id == endpoints[1].id) return false
        for (expected in endpoints) {
            val current = (if (live == null) db.characterDao().getCharacterById(expected.id)
                else live.characters[expected.id]) ?: return false
            if (expected.code.isBlank() || current.code != expected.code || current.novelId != expected.novelId) return false
            val novel = current.novelId?.let { if (live == null) db.novelDao().getNovelById(it)
                else live.novels[it] } ?: return false
            if (expected.novelCode.isNullOrBlank() || novel.code != expected.novelCode ||
                novel.universeId != expected.universeId) return false
            val universe = novel.universeId?.let { if (live == null) db.universeDao().getUniverseById(it)
                else live.universes[it] }
            if (universe?.code != expected.universeCode ||
                (novel.universeId != null && expected.universeCode.isNullOrBlank())) return false
            if (input != null && when (input.scope.kind) {
                    NaturalBatchInput.ScopeKind.WORK -> current.novelId != input.scope.id
                    NaturalBatchInput.ScopeKind.UNIVERSE -> novel.universeId != input.scope.id
                }) return false
        }
        return endpoints[0].universeId == endpoints[1].universeId
    }

    private fun snapshotMatches(row: CharacterRelationship, snapshot: NaturalBatchContext.Relationship) =
        !snapshot.code.isNullOrBlank() && row.code == snapshot.code && row.factionId == null &&
            row.characterId1 == snapshot.firstId && row.characterId2 == snapshot.secondId &&
            row.relationshipType == snapshot.type && row.description == snapshot.description &&
            row.intensity == snapshot.intensity && row.isBidirectional == snapshot.bidirectional

    private fun overlaps(left: CharacterRelationship, right: CharacterRelationship): Boolean =
        left.relationshipType == right.relationshipType &&
            ((left.characterId1 == right.characterId1 && left.characterId2 == right.characterId2) ||
                (left.characterId1 == right.characterId2 && left.characterId2 == right.characterId1 &&
                    (left.isBidirectional || right.isBidirectional)))

    private fun sameMeaning(left: CharacterRelationship, right: CharacterRelationship) =
        overlaps(left, right) && left.isBidirectional == right.isBidirectional &&
            left.description == right.description && left.intensity == right.intensity

    suspend fun evaluate(input: NaturalBatchInput, item: Item, live: Live? = null): Decision {
        val op = item.operation
        fun result(status: Status, reason: String = "", before: String? = describe(item.before, item.endpoints),
            after: String? = describe(item.after, item.endpoints), impact: Int = 0) =
            Decision(op.id, status, reason, before, after, impact)
        if (!validEndpoints(input, item.endpoints, live)) return result(Status.STALE,
            "관계 양쪽 인물의 식별자 또는 범위가 바뀌었습니다. 다시 분석해 주세요")
        val targetId = item.targetId ?: return result(Status.MISSING_TARGET)
        if (item.endpoints.none { it.id == targetId }) return result(Status.MISSING_TARGET,
            "선택한 인물이 이 관계의 양쪽 대상에 포함되지 않습니다")
        val types = item.endpoints.first().universeId?.let {
            (if (live == null) db.universeDao().getUniverseById(it) else live.universes[it])?.getRelationshipTypes()
        } ?: Universe.DEFAULT_RELATIONSHIP_TYPES
        if (item.types == null || types != item.types) return result(Status.STALE,
            "관계 유형 설정이 분석 당시와 다릅니다. 다시 분석해 주세요")
        if (op.kind != NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP && item.after == null)
            return result(Status.INVALID_VALUE, "관계 제안을 직접 수정해 주세요")
        if (item.after != null && (item.after.relationshipType !in types || item.after.intensity !in 1..10))
            return result(Status.INVALID_VALUE, "현재 세계관의 관계 유형과 강도를 확인해 주세요")
        if (op.kind != NaturalBatchPlan.Kind.ADD_RELATIONSHIP) {
            val original = item.original ?: return result(Status.MISSING_TARGET, "검토한 관계를 찾을 수 없습니다")
            val current = (if (live == null) links.getById(original.id) else live.relationships[original.id])
                ?: return result(Status.MISSING_TARGET, "관계가 삭제됐습니다")
            if (!snapshotMatches(current, original) || current != item.before) return result(Status.STALE,
                "관계가 분석 또는 재검증 뒤 바뀌었습니다. 자동 관계는 직접 수정할 수 없습니다")
        }
        val candidates = (if (live == null) links.getRelationshipsForCharacterList(targetId)
            else live.relationships.values.filter { it.characterId1 == targetId || it.characterId2 == targetId })
            .filter { it.id != item.before?.id }
        val after = item.after
        if (after != null) {
            val duplicate = candidates.firstOrNull { overlaps(it, after) }
            if (duplicate != null) {
                if (duplicate.factionId != null) return result(Status.STALE,
                    "같은 유형의 세력 자동 관계가 있습니다. 세력 화면에서 소속을 확인해 주세요")
                if (op.kind == NaturalBatchPlan.Kind.ADD_RELATIONSHIP && sameMeaning(duplicate, after))
                    return result(Status.ALREADY_SATISFIED, "같은 관계가 이미 있습니다")
                return result(Status.STALE, "같은 방향 또는 양방향으로 겹치는 관계가 있습니다")
            }
            if (after == item.before) return result(Status.ALREADY_SATISFIED)
        }
        if (op.kind == NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP) {
            val rowId = requireNotNull(item.before).id
            val history = if (live == null) historyDao.getChangesForRelationshipList(rowId) else live.history[rowId].orEmpty()
            if (history.sortedBy { it.id } != item.history.sortedBy { it.id }) return result(Status.STALE,
                "삭제에 포함되는 관계 변화 이력이 바뀌었습니다. 적용 전 확인을 다시 해 주세요")
            if (history.mapNotNull { it.eventId }.any {
                    item.eventCodes[it].isNullOrBlank() || (if (live == null) db.timelineDao().getEventById(it)?.code
                        else live.eventCodes[it]) != item.eventCodes[it]
                }) return result(Status.STALE, "변화 이력에 연결된 사건이 바뀌었습니다. 적용 전 확인을 다시 해 주세요")
        }
        return result(Status.READY, if (item.history.isEmpty()) "" else
            "관계 변화 이력 ${item.history.size}건도 함께 삭제되며 되돌리기에 보관됩니다", impact = item.history.size)
    }

    /** Mutation and all cascading deletions are journaled in the caller's transaction. */
    suspend fun apply(item: Item, key: String): Pair<List<NaturalBatchRowChange>, String> {
        val after = item.after
        val stored = if (after == null) {
            links.delete(requireNotNull(item.before)); null
        } else if (item.before == null) {
            val id = links.insert(after)
            check(id > 0) { "관계가 겹쳐 추가하지 못했습니다" }
            after.copy(id = id)
        } else {
            links.update(after); after
        }
        val row = NaturalBatchRowChange(operationKey = key, rowKind = RELATIONSHIP,
            rowId = stored?.id ?: requireNotNull(item.before).id,
            beforeJson = item.before?.let(gson::toJson), afterJson = stored?.let(gson::toJson))
        val history = item.history.map {
            NaturalBatchRowChange(operationKey = key, rowKind = HISTORY, rowId = it.id,
                beforeJson = gson.toJson(it), afterJson = null)
        }
        return (listOf(row) + history) to gson.toJson(Guard(item.endpoints, item.eventCodes))
    }

    /** Reject ID reuse, later relationship history, duplicate links and missing event identities. */
    suspend fun undo(record: NaturalBatchAppliedOperation, changes: List<NaturalBatchRowChange>): Boolean {
        val guard = record.guardJson?.let { gson.fromJson(it, Guard::class.java) } ?: return false
        if (!validEndpoints(null, guard.endpoints)) return false
        val relation = changes.singleOrNull { it.rowKind == RELATIONSHIP } ?: return false
        if (changes.any { it.rowKind !in setOf(RELATIONSHIP, HISTORY) }) return false
        val before = relation.beforeJson?.let { gson.fromJson(it, CharacterRelationship::class.java) }
        val after = relation.afterJson?.let { gson.fromJson(it, CharacterRelationship::class.java) }
        if (links.getById(relation.rowId) != after) return false
        if (before == null && historyDao.getChangesForRelationshipList(relation.rowId).isNotEmpty()) return false
        if (before != null) {
            val duplicates = links.getRelationshipsForCharacterList(before.characterId1)
                .filter { it.id != relation.rowId }
            if (duplicates.any { overlaps(it, before) || it.code == before.code }) return false
            // A code moved to another pair still belongs to somebody else.
            if (before.code.isNullOrBlank() || links.getByCode(before.code)?.id?.let { it != relation.rowId } == true)
                return false
            val types = guard.endpoints.first().universeId?.let {
                db.universeDao().getUniverseById(it)?.getRelationshipTypes()
            } ?: Universe.DEFAULT_RELATIONSHIP_TYPES
            if (before.relationshipType !in types) return false
        }
        val history = changes.filter { it.rowKind == HISTORY }.map {
            if (it.afterJson != null || it.beforeJson == null) return false
            gson.fromJson(it.beforeJson, CharacterRelationshipChange::class.java)
        }
        if (history.any { row -> historyDao.getById(row.id) != null ||
                row.code?.let { historyDao.getChangeByCode(it) } != null ||
                (row.eventId != null && (guard.eventCodes[row.eventId].isNullOrBlank() ||
                    db.timelineDao().getEventById(row.eventId)?.code != guard.eventCodes[row.eventId])) }) return false
        if (before == null) links.deleteById(relation.rowId)
        else if (after == null) check(links.insert(before) > 0) { "관계를 복원하지 못했습니다" }
        else links.update(before)
        historyDao.insertAll(history)
        return true
    }

    companion object {
        const val ENTITY_KIND = "relationship"
        private const val RELATIONSHIP = "relationship"
        private const val HISTORY = "relationship_history"

        fun describe(row: CharacterRelationship?, endpoints: List<NaturalBatchContext.Character>): String? {
            if (row == null) return null
            fun name(id: Long) = endpoints.firstOrNull { it.id == id }?.name ?: "대상 확인 필요"
            return "${name(row.characterId1)} ${if (row.isBidirectional) "↔" else "→"} ${name(row.characterId2)} · " +
                "${row.relationshipType} · 강도 ${row.intensity}" +
                row.description.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        }
    }
}
