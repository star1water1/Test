package com.novelcharacter.app.ai

import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.NaturalBatchAppliedOperation
import com.novelcharacter.app.data.model.NaturalBatchRowChange
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.RequiredEnforcement
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.FieldValueLibraryRepository
import com.novelcharacter.app.data.repository.FieldValueRules
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.FieldValueResolver
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.SqlInChunks
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/** Confirmed field and manual relationship proposals share one execution and durable journal. */
class NaturalBatchFieldExecutor(private val db: AppDatabase) {
    enum class Status { READY, APPLIED, ALREADY_SATISFIED, ALREADY_APPLIED, STALE,
        MISSING_TARGET, MISSING_FIELD, FIELD_CHANGED, INVALID_VALUE, UNSUPPORTED, FAILED,
        UNDONE, UNDO_CONFLICT, ALREADY_UNDONE }

    data class Decision(
        val operationId: String, val status: Status, val reason: String = "",
        val before: String? = null, val after: String? = null,
        val sideEffects: Int = 0
    )

    /** The expected cell and field definition are kept for the second check inside apply's transaction. */
    @ConsistentCopyVisibility
    data class Prepared internal constructor(
        val input: NaturalBatchInput,
        val items: List<Item>
    )

    @ConsistentCopyVisibility
    data class Item internal constructor(
        val operation: NaturalBatchPlan.Operation,
        val targetId: Long?, val fieldId: Long?,
        val expectedValue: String?, val expectedType: String?, val expectedConfig: String?,
        val expectedKey: String?, val expectedUniverseId: Long?,
        val expectedCharacterCode: String?, val expectedNovelCode: String?,
        val expectedUniverseCode: String?,
        val editedValue: String?, val decision: Decision,
        val relationship: NaturalBatchRelationshipEdits.Item? = null
    )

    private val gson = Gson()
    private val journal get() = db.naturalBatchJournalDao()
    private val library = FieldValueLibraryRepository(db)
    private val relationships = NaturalBatchRelationshipEdits(db)
    private val characterRepository = CharacterRepository(db, db.characterDao(), db.characterFieldValueDao(),
        db.characterStateChangeDao(), db.characterTagDao(), db.characterRelationshipDao(), db.nameBankDao())
    private val sync = SemanticFieldSyncHelper(characterRepository,
        UniverseRepository(db, db.universeDao(), db.fieldDefinitionDao(), db.novelDao()),
        NovelRepository(db, db.novelDao()))

    private data class Live(
        val characters: Map<Long, Character>,
        val novels: Map<Long, Novel>,
        val universes: Map<Long, Universe>,
        val fields: Map<Long, FieldDefinition>,
        val values: Map<Pair<Long, Long>, CharacterFieldValue>,
        val entries: Map<Long, List<FieldValueEntry>>,
        val appliedKeys: Set<String>
    )

    private suspend fun load(input: NaturalBatchInput, items: List<Item>): Live {
        val characterIds = items.mapNotNull { it.targetId }.distinct()
        val fieldIds = items.mapNotNull { it.fieldId }.distinct()
        val characters = SqlInChunks.flat(characterIds) { db.characterDao().getCharactersByIds(it) }
            .associateBy { it.id }
        val novels = SqlInChunks.flat(characters.values.mapNotNull { it.novelId }.distinct()) {
            db.novelDao().getNovelsByIds(it)
        }.associateBy { it.id }
        val universes = novels.values.mapNotNull { it.universeId }.distinct().mapNotNull { id ->
            db.universeDao().getUniverseById(id)?.let { id to it }
        }.toMap()
        val fields = SqlInChunks.flat(fieldIds) { db.fieldDefinitionDao().getFieldsByIds(it) }
            .associateBy { it.id }
        val values = SqlInChunks.flat(fieldIds, reservedBinds = SqlInChunks.LIMIT / 2) { ids ->
            SqlInChunks.flat(characterIds, reservedBinds = ids.size) { characterChunk ->
                db.characterFieldValueDao().getNaturalBatchValues(characterChunk, ids)
            }
        }.associateBy { it.characterId to it.fieldDefinitionId }
        val appliedKeys = SqlInChunks.flat(items.map { key(input, it.operation.id) }) {
            journal.existingKeys(it)
        }.toSet()
        return Live(characters, novels, universes, fields, values,
            library.entriesForFields(fieldIds), appliedKeys)
    }

    /** The same evaluator runs at preview time and again under the write transaction. */
    suspend fun preflight(review: NaturalBatchReviewState.Snapshot, context: NaturalBatchContext): Prepared = db.withTransaction {
        val plan = requireNotNull(review.plan) { "No reviewed plan" }
        require(plan.sessionId == review.input.sessionId &&
            plan.scopeRevision == review.input.scopeRevision &&
            plan.inputRevision == review.input.inputRevision) { "Stale review" }
        require(review.selected.all { selected ->
            selected in review.confirmed && selected !in plan.conflicts &&
                plan.operations.any { it.id == selected }
        })
        val selected = plan.operations.filter { it.id in review.selected }
        val relationshipPreparation = relationships.prepare(selected.filter {
            it.kind in NaturalBatchReviewSelection.relationshipKinds
        }, context, review.edits)
        val relationshipItems = relationshipPreparation.items
        val relationConflicts = relationships.conflicts(relationshipItems.values)
        val seeds = selected.map { op ->
            val targetId = context.characters[op.targetRef]?.id
            val target = context.characters[op.targetRef]
            val fieldId = op.fieldRef?.let { context.fields[it]?.id }
            val original = if (op.fieldRef != null) context.values[op.targetRef to op.fieldRef] else null
            val contextField = op.fieldRef?.let(context.fields::get)
            val seed = Item(op, targetId, fieldId, original, contextField?.type,
                contextField?.config, contextField?.key, contextField?.universeId,
                target?.code, target?.novelCode, target?.universeCode,
                review.edits[op.id], Decision(op.id, Status.UNSUPPORTED), relationshipItems[op.id])
            seed
        }
        val live = load(review.input, seeds)
        Prepared(review.input, seeds.map { it.copy(decision = if (it.operation.id in relationConflicts)
            Decision(it.operation.id, Status.STALE, "같은 인물 쌍과 관계 유형의 제안이 겹칩니다. 하나만 선택해 주세요")
            else if (it.relationship != null) {
                if (key(review.input, it.operation.id) in live.appliedKeys) Decision(it.operation.id, Status.ALREADY_APPLIED)
                else relationships.evaluate(review.input, it.relationship, relationshipPreparation.live)
            } else evaluate(review.input, it, live)) })
    }

    /** Each independent operation commits with its journal; a bad row cannot undo safe rows. */
    suspend fun apply(prepared: Prepared, executionId: String): List<Decision> {
        require(executionId.isNotBlank())
        require(journal.operations(executionId).all {
            it.sessionId == prepared.input.sessionId &&
                it.scopeRevision == prepared.input.scopeRevision &&
                it.inputRevision == prepared.input.inputRevision
        }) { "Execution ID belongs to another review" }
        val result = prepared.items.map { item ->
            if (item.decision.status != Status.READY) return@map item.decision
            try {
                db.withTransaction {
                    val key = key(prepared.input, item.operation.id)
                    if (journal.operation(key) != null)
                        return@withTransaction Decision(item.operation.id, Status.ALREADY_APPLIED)
                    val fresh = evaluate(prepared.input, item)
                    if (fresh.status != Status.READY || fresh.after != item.decision.after ||
                        fresh.before != item.decision.before) return@withTransaction fresh
                    val characterId = requireNotNull(item.targetId)
                    if (item.relationship != null) {
                        val (changes, guard) = relationships.apply(item.relationship, key)
                        journal.insert(NaturalBatchAppliedOperation(operationKey = key,
                            executionId = executionId, sessionId = prepared.input.sessionId,
                            scopeRevision = prepared.input.scopeRevision, inputRevision = prepared.input.inputRevision,
                            operationId = item.operation.id, characterId = characterId,
                            characterNovelId = db.characterDao().getCharacterById(characterId)?.novelId,
                            fieldId = null, fieldUniverseId = null, fieldKey = "", fieldType = "", fieldConfig = "",
                            entityKind = NaturalBatchRelationshipEdits.ENTITY_KIND, guardJson = guard))
                        journal.insertChanges(changes)
                        return@withTransaction fresh.copy(status = Status.APPLIED)
                    }
                    val fieldId = requireNotNull(item.fieldId)
                    val beforeValues = db.characterFieldValueDao().getValuesByCharacterList(characterId)
                    val beforeStates = db.characterStateChangeDao().getChangesByCharacterList(characterId)
                    val cell = db.characterFieldValueDao().getValue(characterId, fieldId)
                    if (fresh.after.isNullOrEmpty()) {
                        if (cell != null) db.characterFieldValueDao().deleteValue(characterId, fieldId)
                    } else if (cell == null) {
                        db.characterFieldValueDao().insert(CharacterFieldValue(
                            characterId = characterId, fieldDefinitionId = fieldId, value = fresh.after))
                    } else {
                        db.characterFieldValueDao().update(cell.copy(value = fresh.after))
                    }
                    val character = requireNotNull(db.characterDao().getCharacterById(characterId))
                    val universeId = character.novelId?.let { db.novelDao().getNovelById(it)?.universeId }
                    val fields = if (universeId == null)
                        db.fieldDefinitionDao().getGlobalFieldsList(FieldDefinition.ENTITY_CHARACTER)
                    else db.fieldDefinitionDao().getFieldsByUniverseList(universeId, FieldDefinition.ENTITY_CHARACTER)
                    sync.syncFieldToStateChange(characterId, fields,
                        db.characterFieldValueDao().getValuesByCharacterList(characterId), setOf(fieldId))
                    val afterValues = db.characterFieldValueDao().getValuesByCharacterList(characterId)
                    val afterStates = db.characterStateChangeDao().getChangesByCharacterList(characterId)
                    val changes = rowChanges(key, beforeValues, afterValues, beforeStates, afterStates)
                    val field = requireNotNull(db.fieldDefinitionDao().getFieldById(fieldId))
                    journal.insert(NaturalBatchAppliedOperation(operationKey = key,
                        executionId = executionId, sessionId = prepared.input.sessionId,
                        scopeRevision = prepared.input.scopeRevision,
                        inputRevision = prepared.input.inputRevision,
                        operationId = item.operation.id, characterId = characterId,
                        characterNovelId = character.novelId, fieldId = fieldId,
                        fieldUniverseId = field.universeId, fieldKey = field.key,
                        fieldType = field.type, fieldConfig = field.config))
                    journal.insertChanges(changes)
                    val primaryRowId = afterValues.firstOrNull { it.fieldDefinitionId == fieldId }?.id ?: cell?.id
                    fresh.copy(status = Status.APPLIED, sideEffects = changes.count {
                        it.rowKind != FIELD || it.rowId != primaryRowId
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Decision(item.operation.id, Status.FAILED, e.message ?: "Database write failed")
            }
        }
        // Existing form/batch path treats the library as a post-commit index. Recount both after apply and Undo.
        prepared.items.zip(result).filter { it.second.status == Status.APPLIED }
            .mapNotNull { it.first.fieldId }.distinct().forEach { library.harvestField(it) }
        return result
    }

    /** Undo is atomic per operation. Every changed row must still equal its recorded after image. */
    suspend fun undo(executionId: String): List<Decision> {
        require(executionId.isNotBlank())
        val operations = journal.operations(executionId).asReversed()
        if (operations.isEmpty()) return listOf(Decision(executionId, Status.UNDO_CONFLICT,
            "Execution record was removed or is unavailable"))
        val changedFields = mutableSetOf<Long>()
        val result = operations.map { operation ->
            try {
                db.withTransaction {
                    val current = journal.operation(operation.operationKey)
                        ?: return@withTransaction Decision(operation.operationId, Status.UNDO_CONFLICT, "Execution record missing")
                    if (current.undone) return@withTransaction Decision(operation.operationId, Status.ALREADY_UNDONE)
                    if (current.entityKind == NaturalBatchRelationshipEdits.ENTITY_KIND) {
                        if (!relationships.undo(current, journal.changes(operation.operationKey)))
                            return@withTransaction Decision(operation.operationId, Status.UNDO_CONFLICT,
                                "관계·양쪽 인물·변화 이력 또는 연결 사건이 이후 바뀌었습니다")
                        journal.markUndone(operation.operationKey)
                        return@withTransaction Decision(operation.operationId, Status.UNDONE)
                    }
                    if (current.entityKind != "field" || current.fieldId == null)
                        return@withTransaction Decision(operation.operationId, Status.UNDO_CONFLICT, "알 수 없는 실행 기록입니다")
                    val character = db.characterDao().getCharacterById(current.characterId)
                    val field = db.fieldDefinitionDao().getFieldById(current.fieldId)
                    if (character == null || character.novelId != current.characterNovelId ||
                        field == null || field.universeId != current.fieldUniverseId || field.key != current.fieldKey ||
                        field.type != current.fieldType || field.config != current.fieldConfig)
                        return@withTransaction Decision(operation.operationId, Status.UNDO_CONFLICT,
                            "Character scope or field definition changed")
                    val changes = journal.changes(operation.operationKey)
                    if (changes.any { !matchesAfter(it) } ||
                        changes.any { !canRestore(it) })
                        return@withTransaction Decision(operation.operationId, Status.UNDO_CONFLICT,
                            "A changed row or its owner no longer matches the applied result")
                    changes.asReversed().forEach { restore(it) }
                    journal.markUndone(operation.operationKey)
                    changes.filter { it.rowKind == FIELD }.forEach { change ->
                        val row = change.beforeJson ?: change.afterJson
                        if (row != null) changedFields += gson.fromJson(row, CharacterFieldValue::class.java).fieldDefinitionId
                    }
                    Decision(operation.operationId, Status.UNDONE, sideEffects = changes.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Decision(operation.operationId, Status.UNDO_CONFLICT, e.message ?: "Undo failed")
            }
        }
        changedFields.forEach { library.harvestField(it) }
        return result
    }

    private suspend fun evaluate(input: NaturalBatchInput, item: Item, live: Live? = null): Decision {
        val op = item.operation
        fun result(status: Status, reason: String = "", before: String? = null, after: String? = null) =
            Decision(op.id, status, reason, before, after)
        if (if (live == null) journal.operation(key(input, op.id)) != null
            else key(input, op.id) in live.appliedKeys) return result(Status.ALREADY_APPLIED)
        if (item.relationship != null) return relationships.evaluate(input, item.relationship)
        if (op.kind !in FIELD_KINDS) return result(Status.UNSUPPORTED, "세력 변경은 아직 적용할 수 없습니다")
        val characterId = item.targetId ?: return result(Status.MISSING_TARGET)
        val fieldId = item.fieldId ?: return result(Status.MISSING_FIELD)
        val character = (live?.characters?.get(characterId) ?: if (live == null)
            db.characterDao().getCharacterById(characterId) else null) ?: return result(Status.MISSING_TARGET)
        if (item.expectedCharacterCode.isNullOrBlank() || character.code != item.expectedCharacterCode)
            return result(Status.STALE, "인물 식별자가 분석 당시와 달라졌습니다. 다시 분석해 주세요")
        val novel = character.novelId?.let { if (live == null) db.novelDao().getNovelById(it)
            else live.novels[it] } ?: return result(Status.STALE, "작품 식별자가 분석 당시와 달라졌습니다. 다시 분석해 주세요")
        if (item.expectedNovelCode.isNullOrBlank() || novel.code != item.expectedNovelCode)
            return result(Status.STALE, "작품 식별자가 분석 당시와 달라졌습니다. 다시 분석해 주세요")
        val universe = novel.universeId?.let { if (live == null) db.universeDao().getUniverseById(it)
            else live.universes[it] }
        if (universe?.code != item.expectedUniverseCode ||
            (novel.universeId != null && item.expectedUniverseCode.isNullOrBlank()))
            return result(Status.STALE, "세계관 식별자가 분석 당시와 달라졌습니다. 다시 분석해 주세요")
        val scopeOk = when (input.scope.kind) {
            NaturalBatchInput.ScopeKind.WORK -> character.novelId == input.scope.id
            NaturalBatchInput.ScopeKind.UNIVERSE -> novel.universeId == input.scope.id
        }
        if (!scopeOk) return result(Status.STALE, "Character left the selected scope")
        val field = (live?.fields?.get(fieldId) ?: if (live == null)
            db.fieldDefinitionDao().getFieldById(fieldId) else null) ?: return result(Status.MISSING_FIELD)
        if (field.entityType != FieldDefinition.ENTITY_CHARACTER ||
            field.universeId != novel?.universeId || field.universeId != item.expectedUniverseId ||
            field.key != item.expectedKey || field.type != item.expectedType ||
            field.config != item.expectedConfig) return result(Status.FIELD_CHANGED)
        val cell = if (live == null) db.characterFieldValueDao().getValue(characterId, fieldId)
            else live.values[characterId to fieldId]
        val before = cell?.value?.takeIf { it.isNotEmpty() }
        if (before != item.expectedValue?.takeIf { it.isNotEmpty() })
            return result(Status.STALE, "Value changed since analysis", before)
        val spec = CharacterFieldAiSuggester.fieldSpecOf(field, before.orEmpty())
            ?: return result(Status.UNSUPPORTED, "Calculated or unknown field type", before)
        val raw = item.editedValue ?: op.value
        var tokenEntries: List<FieldValueEntry>? = null
        val desired = when (op.kind) {
            NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE -> null
            NaturalBatchPlan.Kind.SET_FIELD_VALUE -> raw?.takeIf { it.isNotBlank() }
                ?: return result(Status.INVALID_VALUE, "Empty Set value", before)
            NaturalBatchPlan.Kind.ADD_FIELD_VALUE, NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE -> {
                if (!FieldValueTokenizer.isMultiToken(field))
                    return result(Status.INVALID_VALUE, "Add and Remove require a multi-value field", before)
                val tokens = FieldValueTokenizer.tokenize(field, raw.orEmpty())
                if (tokens.isEmpty()) return result(Status.INVALID_VALUE, "Empty token list", before)
                val old = FieldValueTokenizer.tokenize(field, before.orEmpty())
                val entries = if (live == null) db.fieldValueEntryDao().getByField(field.id)
                    else live.entries[field.id].orEmpty()
                tokenEntries = entries
                val resolver = FieldValueResolver(entries)
                fun canonical(token: String) = if (spec.libraryEligible) resolver.canonical(token) else token
                val requested = tokens.map(::canonical).toSet()
                val next = if (op.kind == NaturalBatchPlan.Kind.ADD_FIELD_VALUE) {
                    old + tokens.map(::canonical).distinct().filterNot { token ->
                        old.any { canonical(it) == token }
                    }
                } else old.filterNot { canonical(it) in requested }
                if (next == old) return result(Status.ALREADY_SATISFIED, before = before, after = before)
                FieldValueTokenizer.join(next)
            }
            else -> return result(Status.UNSUPPORTED)
        }
        val normalized = if (desired.isNullOrBlank()) null else {
            val checked = CharacterFieldAiSuggester.normalizeChecked(desired, spec)
            (checked as? CharacterFieldAiSuggester.Normalized.Ok)?.value
                ?: return result(Status.INVALID_VALUE, "Value does not fit field type", before)
        }
        if (normalized == null && field.isRequired &&
            RequiredEnforcement.resolve(field.config, field.entityType) == RequiredEnforcement.BLOCK)
            return result(Status.INVALID_VALUE, "Required field cannot be cleared", before)
        if (normalized != null &&
            com.novelcharacter.app.data.model.FieldValueLibraryConfig.fromConfig(field.config).isRestricted &&
            FieldValueTokenizer.supportsLibrary(field)) {
            val entries: List<FieldValueEntry> = tokenEntries ?: if (live == null)
                db.fieldValueEntryDao().getByField(field.id) else live.entries[field.id].orEmpty()
            if (FieldValueRules.validateRestricted(field, normalized, entries).isNotEmpty())
                return result(Status.INVALID_VALUE, "Value is outside the restricted library", before)
        }
        return if (normalized == before) result(Status.ALREADY_SATISFIED, before = before, after = normalized)
        else result(Status.READY, before = before, after = normalized)
    }

    private fun rowChanges(key: String, beforeValues: List<CharacterFieldValue>, afterValues: List<CharacterFieldValue>,
        beforeStates: List<CharacterStateChange>, afterStates: List<CharacterStateChange>): List<NaturalBatchRowChange> {
        fun <T> diff(kind: String, old: List<T>, new: List<T>, id: (T) -> Long): List<NaturalBatchRowChange> {
            val a = old.associateBy(id); val b = new.associateBy(id)
            return (a.keys + b.keys).distinct().sorted().mapNotNull { rowId ->
                val before = a[rowId]; val after = b[rowId]
                if (before == after) null else NaturalBatchRowChange(operationKey = key,
                    rowKind = kind, rowId = rowId,
                    beforeJson = before?.let(gson::toJson), afterJson = after?.let(gson::toJson))
            }
        }
        return diff(FIELD, beforeValues, afterValues) { it.id } +
            diff(STATE, beforeStates, afterStates) { it.id }
    }

    private suspend fun matchesAfter(change: NaturalBatchRowChange): Boolean = when (change.rowKind) {
        FIELD -> db.characterFieldValueDao().getValueById(change.rowId) ==
            change.afterJson?.let { gson.fromJson(it, CharacterFieldValue::class.java) }
        STATE -> db.characterStateChangeDao().getChangeById(change.rowId) ==
            change.afterJson?.let { gson.fromJson(it, CharacterStateChange::class.java) }
        else -> false
    }

    private suspend fun canRestore(change: NaturalBatchRowChange): Boolean {
        val raw = change.beforeJson ?: return true
        return when (change.rowKind) {
            FIELD -> {
                val row = gson.fromJson(raw, CharacterFieldValue::class.java)
                db.characterDao().getCharacterById(row.characterId) != null &&
                    db.fieldDefinitionDao().getFieldById(row.fieldDefinitionId) != null
            }
            STATE -> db.characterDao().getCharacterById(
                gson.fromJson(raw, CharacterStateChange::class.java).characterId) != null
            else -> false
        }
    }

    private suspend fun restore(change: NaturalBatchRowChange) {
        when (change.rowKind) {
            FIELD -> {
                if (change.beforeJson == null) {
                    db.characterFieldValueDao().deleteById(change.rowId)
                } else {
                    val row = gson.fromJson(change.beforeJson, CharacterFieldValue::class.java)
                    if (change.afterJson == null) db.characterFieldValueDao().insert(row)
                    else db.characterFieldValueDao().update(row)
                }
            }
            STATE -> {
                if (change.beforeJson == null) {
                    db.characterStateChangeDao().deleteById(change.rowId)
                } else {
                    val row = gson.fromJson(change.beforeJson, CharacterStateChange::class.java)
                    if (change.afterJson == null) db.characterStateChangeDao().insert(row)
                    else db.characterStateChangeDao().update(row)
                }
            }
        }
    }

    private fun key(input: NaturalBatchInput, id: String): String {
        val source = listOf(input.sessionId, input.scopeRevision, input.inputRevision, id)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FIELD = "field"
        const val STATE = "state"
        val FIELD_KINDS = setOf(NaturalBatchPlan.Kind.SET_FIELD_VALUE,
            NaturalBatchPlan.Kind.ADD_FIELD_VALUE, NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE,
            NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE)
    }
}
