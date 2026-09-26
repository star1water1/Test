package com.novelcharacter.app.ai

import com.google.gson.Gson
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor.Decision
import com.novelcharacter.app.ai.NaturalBatchFieldExecutor.Status
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.NaturalBatchAppliedOperation
import com.novelcharacter.app.data.model.NaturalBatchRowChange
import com.novelcharacter.app.data.repository.FactionRepository
import com.novelcharacter.app.util.SqlInChunks

/** Membership proposals reuse the repository's three meanings, with a journal for every affected row. */
class NaturalBatchFactionEdits(private val db: AppDatabase) {
    data class Draft(val joinYear: Int?, val leaveYear: Int?, val type: String?, val intensity: Int?) {
        fun encode(): String = Gson().toJson(this)
        companion object {
            fun from(op: NaturalBatchPlan.Operation, edit: String? = null): Draft? = try {
                if (edit == null) Draft(op.joinYear, op.leaveYear, op.relationshipType, op.intensity)
                else Gson().fromJson(edit, Draft::class.java)
            } catch (_: Exception) { null }
        }
    }

    data class Scene(val faction: Faction?, val universeCode: String?, val types: List<String>?,
        val memberships: List<FactionMembership>, val links: List<CharacterRelationship>,
        val history: List<CharacterRelationshipChange>, val people: List<NaturalBatchContext.Character>,
        val eventCodes: Map<Long, String?>)
    data class Item(val operation: NaturalBatchPlan.Operation, val target: NaturalBatchContext.Character?,
        val faction: NaturalBatchContext.Faction?, val expectedPair: List<FactionMembership>?,
        val types: List<String>?, val draft: Draft?, val scene: Scene?)
    private data class Guard(val targetId: Long, val before: Scene, val after: Scene)
    private val gson = Gson()
    private val memberships get() = db.factionMembershipDao()
    private val links get() = db.characterRelationshipDao()
    private val history get() = db.characterRelationshipChangeDao()
    private val repository = FactionRepository(db)

    /** One roster/link/history read per referenced faction; no unrelated world detail is loaded. */
    suspend fun read(factionId: Long, targetIds: List<Long>): Scene {
        val faction = db.factionDao().getById(factionId)
        val universe = faction?.let { db.universeDao().getUniverseById(it.universeId) }
        val rows = memberships.getMembershipsByFactionList(factionId).sortedBy { it.id }
        val peers = (rows.filter { it.leaveType == null }.map { it.characterId } + targetIds).toSet()
        val related = (SqlInChunks.flat(targetIds) { links.getRelationshipsByEnd1(it) } +
            SqlInChunks.flat(targetIds) { links.getRelationshipsByEnd2(it) }).distinctBy { it.id }
            .filter { it.factionId == factionId || (it.relationshipType == faction?.autoRelationType &&
                it.characterId1 in peers && it.characterId2 in peers) }
            .sortedBy { it.id }
        val changes = SqlInChunks.flat(related.filter { it.factionId == factionId }.map { it.id }) {
            history.getChangesForRelationships(it)
        }.sortedBy { it.id }
        val ids = (targetIds + rows.filter { it.leaveType == null }.map { it.characterId } +
            related.flatMap { listOf(it.characterId1, it.characterId2) }).distinct()
        val characters = SqlInChunks.flat(ids) { db.characterDao().getCharactersByIds(it) }
        val novels = SqlInChunks.flat(characters.mapNotNull { it.novelId }.distinct()) {
            db.novelDao().getNovelsByIds(it)
        }.associateBy { it.id }
        val universes = novels.values.mapNotNull { it.universeId }.distinct().mapNotNull {
            db.universeDao().getUniverseById(it)
        }.associateBy { it.id }
        val people = characters.map { row ->
            val novel = row.novelId?.let(novels::get)
            NaturalBatchContext.Character(row.id, row.novelId, novel?.universeId, row.name,
                emptyList(), row.code, novel?.title, novel?.code, novel?.universeId?.let { universes[it]?.code })
        }.sortedBy { it.id }
        val events = SqlInChunks.flat(changes.mapNotNull { it.eventId }.distinct()) {
            db.timelineDao().getEventsByIds(it)
        }.associate { it.id to it.code }
        return Scene(faction, universe?.code, universe?.getRelationshipTypes(), rows, related, changes, people, events)
    }

    suspend fun prepare(operations: List<NaturalBatchPlan.Operation>, context: NaturalBatchContext,
        edits: Map<String, String>): Map<String, Item> {
        val scenes = operations.groupBy { it.factionRef?.let(context.factions::get)?.id }
            .filterKeys { it != null }.map { (id, ops) -> requireNotNull(id) to
                read(id, ops.mapNotNull { context.characters[it.targetRef]?.id }.distinct()) }.toMap()
        return operations.associate { op ->
            val faction = op.factionRef?.let(context.factions::get)
            val target = context.characters[op.targetRef]
            op.id to Item(op, target, faction, context.memberships?.filter {
                it.factionId == faction?.id && it.characterId == target?.id
            }?.sortedBy { it.id }, context.relationshipTypes, Draft.from(op, edits[op.id]), faction?.id?.let(scenes::get))
        }
    }

    private fun identity(left: NaturalBatchContext.Character, right: NaturalBatchContext.Character?) =
        right != null && left.code.isNotBlank() && left.code == right.code &&
            left.novelId == right.novelId && !left.novelCode.isNullOrBlank() && left.novelCode == right.novelCode &&
            left.universeId == right.universeId && !left.universeCode.isNullOrBlank() && left.universeCode == right.universeCode

    private fun sameFaction(expected: NaturalBatchContext.Faction, scene: Scene) = scene.faction?.let {
        expected.code.isNotBlank() && expected.code == it.code && expected.universeId == it.universeId &&
            !expected.universeCode.isNullOrBlank() && expected.universeCode == scene.universeCode &&
            expected.autoRelationType == it.autoRelationType && expected.autoRelationIntensity == it.autoRelationIntensity
    } == true

    fun evaluate(input: NaturalBatchInput, item: Item, scene: Scene? = item.scene): Decision {
        val op = item.operation
        fun fail(status: Status, text: String) = Decision(op.id, status, text)
        val target = item.target ?: return fail(Status.MISSING_TARGET, "대상 인물을 찾을 수 없습니다")
        val faction = item.faction ?: return fail(Status.MISSING_TARGET, "세력을 찾을 수 없습니다")
        if (scene == null || !identity(target, scene.people.firstOrNull { it.id == target.id }) ||
            !sameFaction(faction, scene) || target.universeId != faction.universeId || when (input.scope.kind) {
                NaturalBatchInput.ScopeKind.WORK -> target.novelId != input.scope.id
                NaturalBatchInput.ScopeKind.UNIVERSE -> target.universeId != input.scope.id
            }) return fail(Status.STALE, "인물·작품·세력·세계관의 식별자 또는 범위가 바뀌었습니다. 다시 분석해 주세요")
        if (item.types == null || item.types != scene.types) return fail(Status.STALE,
            "관계 유형 설정이 바뀌었습니다. 다시 분석해 주세요")
        val pair = scene.memberships.filter { it.characterId == target.id }
        if (item.expectedPair == null || pair != item.expectedPair) return fail(Status.STALE,
            "소속 이력이 분석 당시와 다릅니다. 다시 분석해 주세요")
        val active = pair.filter { it.leaveType == null }
        if (active.size > 1) return fail(Status.STALE, "활성 소속이 여러 줄입니다. 세력 화면에서 이력을 확인해 주세요")
        if (op.kind == NaturalBatchPlan.Kind.JOIN_FACTION && active.isNotEmpty()) return fail(Status.ALREADY_SATISFIED,
            "이미 소속된 세력입니다. 기존 가입 연도와 이력은 유지합니다")
        if (op.kind == NaturalBatchPlan.Kind.LEAVE_FACTION && active.isEmpty()) return fail(Status.ALREADY_SATISFIED,
            "현재 소속이 없습니다. 과거 소속 이력은 유지합니다")
        val draft = item.draft ?: return fail(Status.INVALID_VALUE, "소속 제안을 직접 수정해 주세요")
        if (op.kind == NaturalBatchPlan.Kind.JOIN_FACTION) {
            if (scene.faction!!.autoRelationType !in scene.types.orEmpty() || scene.faction.autoRelationIntensity !in 1..10)
                return fail(Status.INVALID_VALUE, "세력 화면에서 자동 관계 유형과 강도를 확인해 주세요")
            if (draft.joinYear != null && pair.mapNotNull { it.leaveYear }.any { it > draft.joinYear })
                return fail(Status.INVALID_VALUE, "가입 연도가 이전 탈퇴 연도보다 빠릅니다. 이력을 확인하거나 가입 연도를 수정해 주세요")
        } else if (op.leaveMode == NaturalBatchPlan.LeaveMode.DEPART) {
            if (draft.leaveYear == null || draft.type !in scene.types.orEmpty() || draft.intensity == null || draft.intensity !in 1..10)
                return fail(Status.INVALID_VALUE, "탈퇴 연도와 탈퇴 후 관계 유형·강도를 직접 정해 주세요")
            if (active.single().joinYear?.let { it > draft.leaveYear } == true)
                return fail(Status.INVALID_VALUE, "탈퇴 연도가 가입 연도보다 빠릅니다")
        } else if (op.leaveMode != NaturalBatchPlan.LeaveMode.REMOVE) return fail(Status.INVALID_VALUE, "제거 또는 설정상 탈퇴를 확인해 주세요")
        if (scene.people.any { it.universeId != faction.universeId || it.code.isBlank() ||
                it.novelCode.isNullOrBlank() || it.universeCode != faction.universeCode }) return fail(Status.STALE,
            "자동 관계의 상대 인물 또는 범위를 확인할 수 없습니다. 세력 화면에서 확인해 주세요")
        if (scene.history.any { it.eventId != null && scene.eventCodes[it.eventId].isNullOrBlank() })
            return fail(Status.STALE, "자동 관계 변화에 연결된 사건을 확인할 수 없습니다")
        return effect(item, scene).first
    }

    private fun touches(row: CharacterRelationship, id: Long) = row.characterId1 == id || row.characterId2 == id
    private fun pairKey(first: Long, second: Long, type: String) = "${minOf(first, second)}:${maxOf(first, second)}:$type"
    fun collisionKeys(item: Item, selected: Collection<Item> = emptyList()): Set<String> {
        val scene = item.scene ?: return emptySet()
        val target = item.target?.id ?: return emptySet()
        return if (item.operation.kind == NaturalBatchPlan.Kind.JOIN_FACTION)
            (scene.memberships.filter { it.leaveType == null }.map { it.characterId } +
                selected.filter { it.faction?.id == item.faction?.id && it.operation.kind == NaturalBatchPlan.Kind.JOIN_FACTION }
                    .mapNotNull { it.target?.id }).distinct().filter { it != target }.map {
                pairKey(target, it, scene.faction!!.autoRelationType)
            }.toSet()
        else scene.links.filter { it.factionId == item.faction?.id && touches(it, target) }
            .map { pairKey(it.characterId1, it.characterId2, it.relationshipType) }.toSet()
    }

    /** Simulates the selected order without touching Room. Later joins see earlier joins/removals. */
    fun preview(items: List<Item>, initial: Map<String, Decision>): Map<String, Decision> {
        val result = initial.toMutableMap()
        items.groupBy { it.faction?.id }.values.forEach { group ->
            var scene = group.first().scene ?: return@forEach
            group.forEach { item -> if (result[item.operation.id]?.status == Status.READY) {
                val (decision, next) = effect(item, scene)
                result[item.operation.id] = decision
                scene = next
            } }
        }
        return result
    }

    private fun effect(item: Item, scene: Scene): Pair<Decision, Scene> {
        val id = requireNotNull(item.target).id
        val faction = requireNotNull(scene.faction)
        val draft = requireNotNull(item.draft)
        val old = scene.memberships.filter { it.characterId == id && it.leaveType == null }
        val auto = scene.links.filter { it.factionId == faction.id && touches(it, id) }
        val deletedHistory = scene.history.filter { row -> auto.any { it.id == row.relationshipId } }
        var rows = scene.memberships
        var relations = scene.links
        var changes = scene.history
        val impact: Int
        val reason: String
        val after: String
        val before = old.singleOrNull()?.let { "현재 소속 · 가입 ${it.joinYear?.toString() ?: "시점 불명"}" } ?: "현재 소속 없음"
        var mockId = minOf(-1L, (rows.map { it.id } + relations.map { it.id } + changes.map { it.id }).minOrNull() ?: -1) - 1
        when {
            item.operation.kind == NaturalBatchPlan.Kind.JOIN_FACTION -> {
                val peers = rows.filter { it.leaveType == null && it.characterId != id }.map { it.characterId }.distinct()
                val available = peers.filter { peer -> relations.none {
                    pairKey(it.characterId1, it.characterId2, it.relationshipType) == pairKey(id, peer, faction.autoRelationType)
                } }
                rows = rows + FactionMembership(id = mockId--, factionId = faction.id, characterId = id, joinYear = draft.joinYear, createdAt = 0)
                relations = relations + available.map { peer -> CharacterRelationship(id = mockId--,
                    characterId1 = minOf(id, peer), characterId2 = maxOf(id, peer), relationshipType = faction.autoRelationType,
                    intensity = faction.autoRelationIntensity, isBidirectional = true, factionId = faction.id, createdAt = 0) }
                impact = available.size
                reason = "자동 관계 ${available.size}건 생성 · 기존 관계 ${peers.size - available.size}건 유지(겹침). 다른 소속과 과거 이력은 유지합니다"
                after = "가입 · ${draft.joinYear?.toString() ?: "시점 불명"}"
            }
            item.operation.leaveMode == NaturalBatchPlan.LeaveMode.REMOVE -> {
                rows = rows.filter { !(it.characterId == id && it.leaveType == null) }
                relations = relations.filter { it !in auto }
                changes = changes.filter { it !in deletedHistory }
                impact = auto.size + deletedHistory.size
                reason = "현재 소속 1건 · 자동 관계 ${auto.size}건 · 변화 이력 ${deletedHistory.size}건 삭제. 과거 소속과 다른 세력은 유지하며 삭제 행은 되돌리기에 보관합니다"
                after = "현재 소속 제거"
            }
            else -> {
                val active = old.single()
                rows = rows.map { if (it.id != active.id) it else it.copy(leaveYear = draft.leaveYear,
                    leaveType = FactionMembership.LEAVE_DEPARTED, departedRelationType = draft.type, departedIntensity = draft.intensity) }
                changes = changes + auto.map { row -> CharacterRelationshipChange(id = mockId--, relationshipId = row.id,
                    year = requireNotNull(draft.leaveYear), relationshipType = requireNotNull(draft.type),
                    intensity = requireNotNull(draft.intensity), isBidirectional = true, description = "") }
                impact = auto.size
                reason = "소속 이력과 자동 관계 유지 · ${auto.size}건의 관계 변화 추가"
                after = "설정상 탈퇴 · ${draft.leaveYear} · ${draft.type} · 강도 ${draft.intensity}"
            }
        }
        return Decision(item.operation.id, Status.READY, reason, before, after, impact) to
            scene.copy(memberships = rows, links = relations, history = changes)
    }

    /** Must be called inside the caller's group transaction, after every original scene is checked. */
    suspend fun apply(item: Item, key: String, targetIds: List<Long>): Triple<List<NaturalBatchRowChange>, String, Decision> {
        val id = requireNotNull(item.target).id
        val factionId = requireNotNull(item.faction).id
        val draft = requireNotNull(item.draft)
        val before = read(factionId, targetIds)
        val decision = effect(item, before).first
        when {
            item.operation.kind == NaturalBatchPlan.Kind.JOIN_FACTION -> {
                val result = repository.addMember(factionId, id, draft.joinYear)
                check(result.added == 1 && result.autoRelationsCreated == decision.sideEffects) { "가입 파급이 검토 내용과 다릅니다" }
            }
            item.operation.leaveMode == NaturalBatchPlan.LeaveMode.REMOVE -> repository.removeMember(factionId, id)
            else -> repository.departMember(factionId, id, requireNotNull(draft.leaveYear), requireNotNull(draft.type), requireNotNull(draft.intensity))
        }
        val after = read(factionId, targetIds)
        val diff = diff(key, MEMBERSHIP, before.memberships, after.memberships, { it.id }) +
            diff(key, RELATIONSHIP, before.links, after.links, { it.id }) +
            diff(key, HISTORY, before.history, after.history, { it.id })
        check(diff.isNotEmpty()) { "소속 변경을 저장하지 못했습니다" }
        return Triple(diff, gson.toJson(Guard(id, before, after)), decision.copy(status = Status.APPLIED))
    }

    private fun <T> diff(key: String, kind: String, before: List<T>, after: List<T>, id: (T) -> Long): List<NaturalBatchRowChange> {
        val old = before.associateBy(id); val next = after.associateBy(id)
        return (old.keys + next.keys).filter { old[it] != next[it] }.map { rowId ->
            NaturalBatchRowChange(operationKey = key, rowKind = kind, rowId = rowId,
                beforeJson = old[rowId]?.let(gson::toJson), afterJson = next[rowId]?.let(gson::toJson))
        }
    }

    /** Unchanged peers, stable codes, current auto links/history, and every after-image are required. */
    suspend fun undo(record: NaturalBatchAppliedOperation, changes: List<NaturalBatchRowChange>): Boolean {
        val guard = record.guardJson?.let { gson.fromJson(it, Guard::class.java) } ?: return false
        val expected = guard.after
        val faction = expected.faction ?: return false
        val live = read(faction.id, listOf(guard.targetId))
        if (live.faction?.code != faction.code || live.faction?.universeId != faction.universeId ||
            live.faction?.autoRelationType != faction.autoRelationType || live.faction?.autoRelationIntensity != faction.autoRelationIntensity ||
            live.universeCode != expected.universeCode || live.types != expected.types) return false
        val people = (guard.before.people + expected.people).distinctBy { it.id }
        // Some peers were removed from the active roster; read their identities explicitly too.
        for (person in people) {
            val row = db.characterDao().getCharacterById(person.id) ?: return false
            val novel = row.novelId?.let { db.novelDao().getNovelById(it) } ?: return false
            val universe = novel.universeId?.let { db.universeDao().getUniverseById(it) }
            if (!identity(person, NaturalBatchContext.Character(row.id, row.novelId, novel.universeId, row.name,
                    emptyList(), row.code, novel.title, novel.code, universe?.code))) return false
        }
        fun relevantMemberships(scene: Scene) = scene.memberships.filter { it.characterId == guard.targetId || it.leaveType == null }.sortedBy { it.id }
        fun auto(scene: Scene) = scene.links.filter { it.factionId == faction.id && touches(it, guard.targetId) }.sortedBy { it.id }
        fun autoHistory(scene: Scene) = scene.history.filter { row -> auto(scene).any { it.id == row.relationshipId } }.sortedBy { it.id }
        if (relevantMemberships(live) != relevantMemberships(expected) || auto(live) != auto(expected) ||
            autoHistory(live) != autoHistory(expected)) return false
        if (changes.isEmpty() || changes.any { it.rowKind !in setOf(MEMBERSHIP, RELATIONSHIP, HISTORY) }) return false
        for (change in changes) {
            val current = when (change.rowKind) {
                MEMBERSHIP -> memberships.getById(change.rowId)
                RELATIONSHIP -> links.getById(change.rowId)
                else -> history.getById(change.rowId)
            }
            if (current?.let(gson::toJson) != change.afterJson) return false
            if (change.beforeJson != null && change.rowKind == RELATIONSHIP) {
                val old = gson.fromJson(change.beforeJson, CharacterRelationship::class.java)
                if (old.code.isNullOrBlank() || links.getByCode(old.code)?.id?.let { it != old.id } == true) return false
                if (links.getRelationshipsForCharacterList(old.characterId1).any { it.id != old.id &&
                        pairKey(it.characterId1, it.characterId2, it.relationshipType) == pairKey(old.characterId1, old.characterId2, old.relationshipType) }) return false
            }
            if (change.beforeJson != null && change.rowKind == HISTORY) {
                val old = gson.fromJson(change.beforeJson, CharacterRelationshipChange::class.java)
                if (old.code.isNullOrBlank() || history.getChangeByCode(old.code)?.id?.let { it != old.id } == true) return false
                if (old.eventId != null && (guard.before.eventCodes[old.eventId].isNullOrBlank() ||
                        db.timelineDao().getEventById(old.eventId)?.code != guard.before.eventCodes[old.eventId])) return false
            }
        }
        // Delete newly-created history first, then links, then memberships. Restore parents before children.
        for (kind in listOf(HISTORY, RELATIONSHIP, MEMBERSHIP)) changes.filter { it.rowKind == kind && it.beforeJson == null }.forEach {
            when (kind) { HISTORY -> history.deleteById(it.rowId); RELATIONSHIP -> links.deleteById(it.rowId); else -> memberships.deleteById(it.rowId) }
        }
        for (kind in listOf(MEMBERSHIP, RELATIONSHIP, HISTORY)) changes.filter { it.rowKind == kind && it.beforeJson != null }.forEach {
            when (kind) {
                MEMBERSHIP -> gson.fromJson(it.beforeJson, FactionMembership::class.java).let { row ->
                    if (it.afterJson == null) memberships.insert(row) else memberships.update(row) }
                RELATIONSHIP -> gson.fromJson(it.beforeJson, CharacterRelationship::class.java).let { row ->
                    if (it.afterJson == null) check(links.insert(row) > 0) else links.update(row) }
                else -> gson.fromJson(it.beforeJson, CharacterRelationshipChange::class.java).let { row ->
                    if (it.afterJson == null) history.insert(row) else history.update(row) }
            }
        }
        return true
    }

    companion object {
        const val ENTITY_KIND = "faction"
        private const val MEMBERSHIP = "faction_membership"
        private const val RELATIONSHIP = "relationship"
        private const val HISTORY = "relationship_history"
    }
}
