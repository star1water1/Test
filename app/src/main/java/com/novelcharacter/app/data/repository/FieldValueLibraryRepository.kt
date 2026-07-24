package com.novelcharacter.app.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.FieldEntryCount
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.util.FieldValueResolver
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.GsonTypes
import org.json.JSONObject

/**
 * 필드 데이터 라이브러리 저장소 — 필드별 정규화 값 카탈로그의 수확·큐레이션·전파.
 *
 * 계약 (적대적 검토 반영):
 * - 수확(harvest*)은 insert-only이며 절대 던지지 않는다(runCatching) — 저장 트랜잭션 커밋 후
 *   호출되고, 수확 실패가 사용자 저장을 실패시키면 안 된다. usageCount는 건드리지 않는다.
 * - usageCount는 최종 일관성 캐시 — [recountUsage]가 diff-only로 변한 행만 UPDATE한다
 *   (무효화 폭풍 방지). 갱신 지점: 라이브러리 화면 진입·엑셀 임포트 후·유지보수 잡.
 * - rename/merge/delete 전파는 SQL 정확일치가 아니라 로드→토큰화(trim)→치환→재조합으로
 *   수행한다 — 미trim 잔여 데이터("서울 ")도 함께 정규화하며 그 건수를 보고한다.
 * - 전파는 character_field_values·event_field_values에 더해 character_state_changes(이력)와
 *   필드 config의 값 리터럴(SELECT options/GRADE grades/시맨틱 aliveValue·deadValue)까지 포함한다.
 * - deleteEntry(alsoClearData)는 파괴 전 영향 캐릭터를 휴지통에 스냅샷한다.
 */
class FieldValueLibraryRepository(private val db: AppDatabase) {

    private val entryDao get() = db.fieldValueEntryDao()
    private val fieldDao get() = db.fieldDefinitionDao()
    private val charValueDao get() = db.characterFieldValueDao()
    private val eventValueDao get() = db.eventFieldValueDao()
    private val stateChangeDao get() = db.characterStateChangeDao()

    // ===== 조회 =====

    suspend fun entriesForField(fieldDefId: Long): List<FieldValueEntry> = entryDao.getByField(fieldDefId)

    fun entriesForFieldLive(fieldDefId: Long): LiveData<List<FieldValueEntry>> = entryDao.getByFieldLive(fieldDefId)

    suspend fun entryCounts(): Map<Long, FieldEntryCount> =
        entryDao.countByField().associateBy { it.fieldDefinitionId }

    /** 폼 자동완성용 — 세계관의 비숨김 엔트리를 필드별로 1쿼리 배치 로드 */
    suspend fun suggestionsForUniverse(universeId: Long, entityType: String): Map<Long, List<FieldValueEntry>> =
        entryDao.getForUniverse(universeId, entityType).groupBy { it.fieldDefinitionId }

    suspend fun resolverForField(fieldDefId: Long): FieldValueResolver =
        FieldValueResolver(entryDao.getByField(fieldDefId))

    /** 필드 집합의 해석기 일괄 생성 (IN 청크) — 통계 스냅샷·엑셀 임포트용 */
    suspend fun resolversForFields(fieldDefIds: Collection<Long>): Map<Long, FieldValueResolver> {
        val entries = fieldDefIds.distinct().chunked(CHUNK_SIZE)
            .flatMap { entryDao.getForFields(it) }
            .groupBy { it.fieldDefinitionId }
        return fieldDefIds.associateWith { FieldValueResolver(entries[it].orEmpty()) }
    }

    // ===== 자동 수확 (insert-only, 실패 무해) =====

    /** 캐릭터 저장/복원/이동 후 — 해당 캐릭터의 값·상태변화를 수확 */
    suspend fun harvestForCharacter(characterId: Long) = safely("harvestForCharacter") {
        val values = charValueDao.getValuesByCharacterList(characterId)
        val defsById = supportedDefsById()
        val tokensByField = HashMap<Long, MutableSet<String>>()
        for (v in values) {
            val fd = defsById[v.fieldDefinitionId] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }
                .addAll(FieldValueTokenizer.tokenize(fd, v.value))
        }
        // 상태변화에만 존재하는 값도 라이브러리가 보게 한다 (A2)
        val universeId = universeOfCharacter(characterId)
        if (universeId != null) {
            val defsByKey = defsById.values
                .filter { it.universeId == universeId && it.entityType == FieldDefinition.ENTITY_CHARACTER }
                .associateBy { it.key }
            for (change in stateChangeDao.getChangesByCharacterList(characterId)) {
                if (change.fieldKey.startsWith("__")) continue
                val fd = defsByKey[change.fieldKey] ?: continue
                tokensByField.getOrPut(fd.id) { linkedSetOf() }
                    .addAll(FieldValueTokenizer.tokenize(fd, change.newValue))
            }
        }
        insertNewTokens(tokensByField)
    }

    /** 사건 저장 후 */
    suspend fun harvestForEvent(eventId: Long) = safely("harvestForEvent") {
        val values = eventValueDao.getValuesByEventList(eventId)
        val defsById = supportedDefsById()
        val tokensByField = HashMap<Long, MutableSet<String>>()
        for (v in values) {
            val fd = defsById[v.fieldDefinitionId] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }
                .addAll(FieldValueTokenizer.tokenize(fd, v.value))
        }
        insertNewTokens(tokensByField)
    }

    /** 일괄 편집 등 필드 단위 쓰기 후 */
    suspend fun harvestField(fieldDefId: Long) = safely("harvestField") {
        val fd = fieldDao.getFieldById(fieldDefId) ?: return@safely
        if (!FieldValueTokenizer.supportsLibrary(fd)) return@safely
        val tokens = linkedSetOf<String>()
        charValueDao.getValuesByFieldDef(fd.id).forEach { tokens.addAll(FieldValueTokenizer.tokenize(fd, it.value)) }
        eventValueDao.getValuesByFieldDef(fd.id).forEach { tokens.addAll(FieldValueTokenizer.tokenize(fd, it.value)) }
        insertNewTokens(mapOf(fd.id to tokens))
    }

    /** 상태변화 쓰기 후 — 지원 필드의 이력 값 수확 (시스템 키 스킵) */
    suspend fun harvestStateChange(characterId: Long, fieldKey: String, newValue: String) =
        safely("harvestStateChange") {
            if (fieldKey.startsWith("__") || newValue.isBlank()) return@safely
            val universeId = universeOfCharacter(characterId) ?: return@safely
            val fd = fieldDao.getFieldByKey(universeId, fieldKey) ?: return@safely
            if (!FieldValueTokenizer.supportsLibrary(fd)) return@safely
            insertNewTokens(mapOf(fd.id to FieldValueTokenizer.tokenize(fd, newValue).toSet()))
        }

    /** 엑셀 임포트 후 — 임포트가 건드린 세계관들 일괄 수확. null이면 전체(백필 시드). */
    suspend fun harvestUniverses(universeIds: Set<Long>?) = safely("harvestUniverses") {
        val defs = fieldDao.getAllFieldsAllTypes()
            .filter { FieldValueTokenizer.supportsLibrary(it) }
            .filter { universeIds == null || it.universeId in universeIds }
        if (defs.isEmpty()) return@safely
        val defsById = defs.associateBy { it.id }
        val tokensByField = HashMap<Long, MutableSet<String>>()

        for (v in charValueDao.getAllValuesList()) {
            val fd = defsById[v.fieldDefinitionId] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }.addAll(FieldValueTokenizer.tokenize(fd, v.value))
        }
        for (v in eventValueDao.getAllValuesList()) {
            val fd = defsById[v.fieldDefinitionId] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }.addAll(FieldValueTokenizer.tokenize(fd, v.value))
        }
        // 상태변화: 캐릭터 → 세계관 매핑 후 (세계관, key)로 필드 해석
        val universeByChar = characterUniverseMap()
        val defsByUniverseKey = defs
            .filter { it.entityType == FieldDefinition.ENTITY_CHARACTER }
            .associateBy { it.universeId to it.key }
        for (change in stateChangeDao.getAllChangesList()) {
            if (change.fieldKey.startsWith("__")) continue
            val uid = universeByChar[change.characterId] ?: continue
            val fd = defsByUniverseKey[uid to change.fieldKey] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }
                .addAll(FieldValueTokenizer.tokenize(fd, change.newValue))
        }
        insertNewTokens(tokensByField)
    }

    suspend fun harvestAll() = harvestUniverses(null)

    private suspend fun insertNewTokens(tokensByField: Map<Long, Set<String>>) {
        val fieldIds = tokensByField.filterValues { it.isNotEmpty() }.keys
        if (fieldIds.isEmpty()) return
        val existingByField = fieldIds.chunked(CHUNK_SIZE)
            .flatMap { entryDao.getForFields(it) }
            .groupBy { it.fieldDefinitionId }
        val toInsert = mutableListOf<FieldValueEntry>()
        for ((fieldId, tokens) in tokensByField) {
            val known = HashSet<String>()
            existingByField[fieldId].orEmpty().forEach { e ->
                known.add(e.value)
                known.addAll(e.aliases())
            }
            tokens.filter { it.isNotEmpty() && it !in known }.forEach { token ->
                toInsert.add(FieldValueEntry(fieldDefinitionId = fieldId, value = token))
            }
        }
        if (toInsert.isNotEmpty()) entryDao.insertAllIgnore(toInsert)
    }

    // ===== usageCount 재계산 (diff-only) =====

    /** 필드의 usageCount를 실제 값 기준으로 재계산. 변한 행만 UPDATE — 무변화면 0건 쓰기. */
    suspend fun recountUsage(fieldDefId: Long) = safely("recountUsage") {
        val fd = fieldDao.getFieldById(fieldDefId) ?: return@safely
        val entries = entryDao.getByField(fieldDefId)
        if (entries.isEmpty()) return@safely
        val resolver = FieldValueResolver(entries)
        val counts = HashMap<String, Int>()
        val countTokens = { raw: String ->
            for (token in FieldValueTokenizer.tokenize(fd, raw)) {
                val canonical = resolver.canonical(token)
                counts[canonical] = (counts[canonical] ?: 0) + 1
            }
        }
        charValueDao.getValuesByFieldDef(fieldDefId).forEach { countTokens(it.value) }
        eventValueDao.getValuesByFieldDef(fieldDefId).forEach { countTokens(it.value) }
        val changed = entries.mapNotNull { e ->
            val actual = counts[e.value] ?: 0
            if (actual != e.usageCount) e.copy(usageCount = actual) else null
        }
        if (changed.isNotEmpty()) entryDao.updateAll(changed)
    }

    // ===== 큐레이션 =====

    sealed class AddResult {
        data class Added(val entry: FieldValueEntry) : AddResult()
        /** 이미 canonical 또는 별칭으로 존재 */
        data class Duplicate(val conflictWith: FieldValueEntry, val asAlias: Boolean) : AddResult()
        object BlankValue : AddResult()
    }

    suspend fun addEntry(
        fieldDefId: Long,
        value: String,
        displayLabel: String = "",
        category: String = "",
        description: String = "",
        source: String = FieldValueEntry.SOURCE_MANUAL
    ): AddResult {
        val token = value.trim()
        if (token.isEmpty()) return AddResult.BlankValue
        val existing = entryDao.getByField(fieldDefId)
        conflictOf(existing, token, excludeId = null)?.let { (entry, asAlias) ->
            return AddResult.Duplicate(entry, asAlias)
        }
        val entry = FieldValueEntry(
            fieldDefinitionId = fieldDefId,
            value = token,
            displayLabel = displayLabel.trim(),
            category = category.trim(),
            description = description.trim(),
            source = source
        )
        return AddResult.Added(entry.copy(id = entryDao.insert(entry)))
    }

    sealed class UpdateResult {
        object Updated : UpdateResult()
        /** 추가하려는 별칭이 다른 엔트리의 canonical/별칭과 충돌 */
        data class AliasConflict(val alias: String, val conflictWith: FieldValueEntry) : UpdateResult()
    }

    /** 메타데이터(라벨·카테고리·설명·별칭·숨김) 갱신 — value 변경은 renameValue로만. */
    suspend fun updateEntry(entry: FieldValueEntry): UpdateResult {
        val others = entryDao.getByField(entry.fieldDefinitionId).filter { it.id != entry.id }
        val aliases = entry.aliases().filter { it != entry.value }
        for (alias in aliases) {
            conflictOf(others, alias, excludeId = entry.id)?.let { (conflict, _) ->
                return UpdateResult.AliasConflict(alias, conflict)
            }
        }
        entryDao.update(
            entry.copy(
                aliasesJson = FieldValueEntry.aliasesToJson(aliases),
                updatedAt = System.currentTimeMillis()
            )
        )
        return UpdateResult.Updated
    }

    data class PropagationReport(
        val characterValues: Int = 0,
        val eventValues: Int = 0,
        val stateChanges: Int = 0,
        /** 토큰은 같지만 공백 배치가 표준화된 행 수 (별도 고지용) */
        val whitespaceNormalized: Int = 0,
        val configUpdated: Boolean = false,
        val affectedCharacterIds: List<Long> = emptyList(),
        val snapshottedCharacters: Int = 0
    ) {
        val total: Int get() = characterValues + eventValues + stateChanges

        operator fun plus(o: PropagationReport) = PropagationReport(
            characterValues + o.characterValues,
            eventValues + o.eventValues,
            stateChanges + o.stateChanges,
            whitespaceNormalized + o.whitespaceNormalized,
            configUpdated || o.configUpdated,
            (affectedCharacterIds + o.affectedCharacterIds).distinct(),
            snapshottedCharacters + o.snapshottedCharacters
        )
    }

    sealed class RenameResult {
        data class Renamed(val report: PropagationReport, val entry: FieldValueEntry) : RenameResult()
        /** 새 이름이 이미 다른 엔트리로 존재 — 병합을 안내 */
        data class Conflict(val conflictWith: FieldValueEntry, val asAlias: Boolean) : RenameResult()
        object BlankValue : RenameResult()
    }

    /** 값 이름 변경 + 전 데이터 전파. [keepOldAsAlias]면 구 값을 별칭으로 보존(기본 권장). */
    suspend fun renameValue(
        fd: FieldDefinition,
        entry: FieldValueEntry,
        newValue: String,
        keepOldAsAlias: Boolean
    ): RenameResult {
        val newToken = newValue.trim()
        if (newToken.isEmpty()) return RenameResult.BlankValue
        if (newToken == entry.value) {
            return RenameResult.Renamed(PropagationReport(), entry)
        }
        val others = entryDao.getByField(fd.id).filter { it.id != entry.id }
        conflictOf(others, newToken, excludeId = entry.id)?.let { (conflict, asAlias) ->
            return RenameResult.Conflict(conflict, asAlias)
        }
        val report = db.withTransaction {
            val propagation = rewriteTokens(fd, matchTokens = setOf(entry.value), replacement = newToken)
            val configChanged = rewriteConfigLiterals(fd, mapOf(entry.value to newToken))
            val newAliases = (entry.aliases() + if (keepOldAsAlias) listOf(entry.value) else emptyList())
                .filter { it != newToken }
            entryDao.update(
                entry.copy(
                    value = newToken,
                    aliasesJson = FieldValueEntry.aliasesToJson(newAliases),
                    updatedAt = System.currentTimeMillis()
                )
            )
            propagation.copy(configUpdated = configChanged)
        }
        return RenameResult.Renamed(report, entry.copy(value = newToken))
    }

    data class MergeOutcome(val report: PropagationReport, val target: FieldValueEntry)

    /** 값 병합: source들의 canonical·별칭이 전부 target으로 접힌다. 메타데이터는 무손실 이관. */
    suspend fun mergeValues(
        fd: FieldDefinition,
        targetId: Long,
        sourceIds: List<Long>,
        keepSourceAsAlias: Boolean = true
    ): MergeOutcome? {
        val all = entryDao.getByField(fd.id).associateBy { it.id }
        val target = all[targetId] ?: return null
        val sources = sourceIds.mapNotNull { all[it] }.filter { it.id != targetId }
        if (sources.isEmpty()) return MergeOutcome(PropagationReport(), target)

        val matchTokens = buildSet {
            sources.forEach { s ->
                add(s.value)
                addAll(s.aliases())
            }
        }
        var mergedEntry = target
        val report = db.withTransaction {
            val propagation = rewriteTokens(fd, matchTokens, replacement = target.value)
            val configChanged = rewriteConfigLiterals(fd, matchTokens.associateWith { target.value })

            val mergedAliases = buildList {
                addAll(target.aliases())
                if (keepSourceAsAlias) addAll(sources.map { it.value })
                addAll(sources.flatMap { it.aliases() })
            }.filter { it != target.value }
            // 설명·카테고리·라벨은 절대 조용히 유실하지 않는다 — 빈 곳 채우기 + 설명은 개행 연결
            val mergedDescription = (listOf(target.description) + sources.map { it.description })
                .filter { it.isNotBlank() }.distinct().joinToString("\n")
            val merged = target.copy(
                displayLabel = target.displayLabel.ifBlank { sources.firstNotNullOfOrNull { s -> s.displayLabel.takeIf { it.isNotBlank() } } ?: "" },
                category = target.category.ifBlank { sources.firstNotNullOfOrNull { s -> s.category.takeIf { it.isNotBlank() } } ?: "" },
                description = mergedDescription,
                aliasesJson = FieldValueEntry.aliasesToJson(mergedAliases),
                source = if (target.source == FieldValueEntry.SOURCE_AUTO) FieldValueEntry.SOURCE_MANUAL else target.source,
                updatedAt = System.currentTimeMillis()
            )
            entryDao.update(merged)
            sources.forEach { entryDao.delete(it) }
            mergedEntry = merged
            propagation.copy(configUpdated = configChanged)
        }
        return MergeOutcome(report, mergedEntry)
    }

    /**
     * 엔트리 삭제.
     * [alsoClearData]=false: 카탈로그 항목만 삭제(캐릭터 값 유지 — 재수확 시 다시 나타남).
     * [alsoClearData]=true: 캐릭터·사건 값과 상태변화 이력에서도 해당 토큰 제거.
     *   파괴 전 영향 캐릭터를 휴지통에 스냅샷한다 (되돌리기 가능).
     */
    suspend fun deleteEntry(fd: FieldDefinition, entry: FieldValueEntry, alsoClearData: Boolean): PropagationReport {
        if (!alsoClearData) {
            entryDao.delete(entry)
            return PropagationReport()
        }
        val matchTokens = setOf(entry.value) + entry.aliases()
        return db.withTransaction {
            // 파괴 전 스냅샷 — CharacterRepository.deleteCharacter 선례
            val affected = collectAffectedCharacterIds(fd, matchTokens)
            val trash = TrashRepository(db)
            var snapshotted = 0
            for (character in db.characterDao().getCharactersByIds(affected)) {
                trash.snapshotCharacter(character, parseImagePathList(character.imagePaths))
                snapshotted++
            }
            val propagation = rewriteTokens(fd, matchTokens, replacement = null)
            entryDao.delete(entry)
            propagation.copy(snapshottedCharacters = snapshotted)
        }
    }

    /** 전파 없이 영향 규모만 산출 — 확인 다이얼로그·AI 정리 드라이런용 */
    suspend fun previewPropagation(fd: FieldDefinition, tokens: Set<String>): PropagationReport {
        var charRows = 0
        var eventRows = 0
        var stateRows = 0
        val affected = mutableListOf<Long>()
        for (row in charValueDao.getValuesByFieldDef(fd.id)) {
            if (FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens }) {
                charRows++; affected.add(row.characterId)
            }
        }
        for (row in eventValueDao.getValuesByFieldDef(fd.id)) {
            if (FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens }) eventRows++
        }
        if (fd.entityType == FieldDefinition.ENTITY_CHARACTER) {
            for (change in stateChangeDao.getChangesByFieldKeyForUniverse(fd.universeId, fd.key)) {
                if (FieldValueTokenizer.tokenize(fd, change.newValue).any { it in tokens }) stateRows++
            }
        }
        return PropagationReport(charRows, eventRows, stateRows, affectedCharacterIds = affected.distinct())
    }

    data class UsageDrilldown(
        val characters: List<Character>,
        val events: List<TimelineEvent>,
        val stateChangeCount: Int
    )

    /** 값 사용처 — 원칙 04: 숨은 데이터 없음 */
    suspend fun usageDrilldown(fd: FieldDefinition, entry: FieldValueEntry): UsageDrilldown {
        val tokens = setOf(entry.value) + entry.aliases()
        val charIds = collectAffectedCharacterIds(fd, tokens)
        val eventIds = eventValueDao.getValuesByFieldDef(fd.id)
            .filter { row -> FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens } }
            .map { it.eventId }.distinct()
        var stateCount = 0
        if (fd.entityType == FieldDefinition.ENTITY_CHARACTER) {
            stateCount = stateChangeDao.getChangesByFieldKeyForUniverse(fd.universeId, fd.key)
                .count { change -> FieldValueTokenizer.tokenize(fd, change.newValue).any { it in tokens } }
        }
        val characters = charIds.chunked(CHUNK_SIZE).flatMap { db.characterDao().getCharactersByIds(it) }
        val events = eventIds.mapNotNull { db.timelineDao().getEventById(it) }
        return UsageDrilldown(characters, events, stateCount)
    }

    // ===== 시드 (valueLabels/valueCategories → 라이브러리 이관) =====

    /**
     * 구 FieldStatsConfig.valueLabels/valueCategories를 엔트리로 이관하고 config에서 제거.
     * 필드 단위 단일 트랜잭션: 엔트리 병합이 config 제거보다 반드시 먼저 커밋 대상이 된다.
     * 충돌(이미 수확된 AUTO 엔트리)은 IGNORE가 아니라 "빈 칸만 채우는" 병합 UPDATE — 경합·재실행에도
     * 라벨이 유실되지 않는다. 큐레이션된 비어있지 않은 값은 절대 덮어쓰지 않는다.
     *
     * @return 이관된 (신규 + 병합) 엔트리 수
     */
    suspend fun seedFromStatsConfig(fd: FieldDefinition): Int {
        val stats = FieldStatsConfig.fromConfig(fd.config)
        if (stats.valueLabels.isEmpty() && stats.valueCategories.isEmpty()) return 0
        if (!FieldValueTokenizer.supportsLibrary(fd)) {
            // 미지원 타입(NUMBER 등)의 라벨/카테고리는 통계 폴백 경로가 계속 쓰므로 건드리지 않는다
            return 0
        }
        return db.withTransaction {
            // 라벨의 값(라벨 문자열) 집합 — 구 카테고리는 라벨 키공간으로 조회되던 동작 보존 (A3)
            val labelValues = stats.valueLabels.values.toSet()
            val rawKeys = LinkedHashSet<String>().apply {
                addAll(stats.valueLabels.keys)
                // 카테고리 키 중 다른 값의 라벨인 것은 독립 엔트리로 만들지 않는다
                addAll(stats.valueCategories.keys.filter { it !in labelValues })
            }.map { it.trim() }.filter { it.isNotEmpty() }

            val existing = entryDao.getByField(fd.id).associateBy { it.value }.toMutableMap()
            var migrated = 0
            for (raw in rawKeys) {
                val label = stats.valueLabels[raw].orEmpty().trim()
                val category = (stats.valueCategories[label.ifBlank { raw }] ?: stats.valueCategories[raw])
                    .orEmpty().trim()
                if (label.isEmpty() && category.isEmpty()) continue
                val current = existing[raw]
                if (current == null) {
                    val entry = FieldValueEntry(
                        fieldDefinitionId = fd.id,
                        value = raw,
                        displayLabel = label,
                        category = category,
                        source = FieldValueEntry.SOURCE_MANUAL
                    )
                    existing[raw] = entry.copy(id = entryDao.insert(entry))
                    migrated++
                } else {
                    // 빈 칸만 채운다 — 사용자가 이미 큐레이션한 값은 보존
                    val updated = current.copy(
                        displayLabel = current.displayLabel.ifBlank { label },
                        category = current.category.ifBlank { category },
                        source = if (current.source == FieldValueEntry.SOURCE_AUTO)
                            FieldValueEntry.SOURCE_MANUAL else current.source,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (updated != current) {
                        entryDao.update(updated)
                        migrated++
                    }
                }
            }
            // 엔트리가 라벨을 확보한 뒤에야 config에서 두 맵을 제거 (같은 트랜잭션)
            val stripped = FieldStatsConfig.applyToConfig(
                fd.config,
                stats.copy(valueLabels = emptyMap(), valueCategories = emptyMap())
            )
            fieldDao.update(fd.copy(config = stripped))
            migrated
        }
    }

    // ===== 검증 =====

    companion object {
        const val CHUNK_SIZE = 900

        /** RESTRICTED 모드 위반 토큰 — 순수 함수 (단위 테스트 대상) */
        fun validateRestricted(
            fd: FieldDefinition,
            raw: String,
            entries: List<FieldValueEntry>
        ): List<String> {
            if (raw.isBlank()) return emptyList()
            val allowed = HashSet<String>()
            for (e in entries) {
                allowed.add(e.value)
                allowed.addAll(e.aliases())
            }
            return FieldValueTokenizer.tokenize(fd, raw).filter { it !in allowed }
        }

        /** 값/별칭 충돌 검사 — (충돌 엔트리, 별칭과의 충돌 여부) */
        fun conflictOf(
            entries: List<FieldValueEntry>,
            token: String,
            excludeId: Long?
        ): Pair<FieldValueEntry, Boolean>? {
            for (e in entries) {
                if (excludeId != null && e.id == excludeId) continue
                if (e.value == token) return e to false
                if (token in e.aliases()) return e to true
            }
            return null
        }
    }

    // ===== 내부 =====

    /** 토큰 치환 전파. [replacement]=null이면 토큰 제거(빈 행은 삭제). 트랜잭션 안에서 호출할 것. */
    private suspend fun rewriteTokens(
        fd: FieldDefinition,
        matchTokens: Set<String>,
        replacement: String?
    ): PropagationReport {
        var charRows = 0
        var eventRows = 0
        var stateRows = 0
        var whitespaceOnly = 0
        val affected = mutableListOf<Long>()

        fun mapTokens(rawTokens: List<String>): List<String>? {
            if (rawTokens.none { it in matchTokens }) return null
            return rawTokens.mapNotNull { t -> if (t in matchTokens) replacement else t }.distinct()
        }

        for (row in charValueDao.getValuesByFieldDef(fd.id)) {
            val tokens = FieldValueTokenizer.tokenize(fd, row.value)
            val mapped = mapTokens(tokens) ?: continue
            affected.add(row.characterId)
            if (mapped.isEmpty()) {
                charValueDao.deleteValue(row.characterId, row.fieldDefinitionId)
                charRows++
            } else {
                val joined = FieldValueTokenizer.join(mapped)
                if (joined != row.value) {
                    charValueDao.update(row.copy(value = joined))
                    charRows++
                    if (mapped == tokens) whitespaceOnly++
                }
            }
        }
        for (row in eventValueDao.getValuesByFieldDef(fd.id)) {
            val tokens = FieldValueTokenizer.tokenize(fd, row.value)
            val mapped = mapTokens(tokens) ?: continue
            if (mapped.isEmpty()) {
                eventValueDao.delete(row)
                eventRows++
            } else {
                val joined = FieldValueTokenizer.join(mapped)
                if (joined != row.value) {
                    eventValueDao.update(row.copy(value = joined))
                    eventRows++
                    if (mapped == tokens) whitespaceOnly++
                }
            }
        }
        // 상태변화 이력 — 연도 슬라이더·성장 그래프가 읽는 시간축 데이터 (A2: 무통보 이력 파손 방지)
        if (fd.entityType == FieldDefinition.ENTITY_CHARACTER) {
            for (change in stateChangeDao.getChangesByFieldKeyForUniverse(fd.universeId, fd.key)) {
                val tokens = FieldValueTokenizer.tokenize(fd, change.newValue)
                val mapped = mapTokens(tokens) ?: continue
                affected.add(change.characterId)
                if (mapped.isEmpty()) {
                    stateChangeDao.delete(change)
                    stateRows++
                } else {
                    val joined = FieldValueTokenizer.join(mapped)
                    if (joined != change.newValue) {
                        stateChangeDao.update(change.copy(newValue = joined))
                        stateRows++
                        if (mapped == tokens) whitespaceOnly++
                    }
                }
            }
        }
        return PropagationReport(charRows, eventRows, stateRows, whitespaceOnly, false, affected.distinct())
    }

    /**
     * 필드 config의 값 리터럴 치환 (A5) — SELECT options / GRADE grades / 시맨틱 aliveValue·deadValue.
     * 저장값과 config가 어긋나면 스피너·등급 해석·생사 동기화가 조용히 깨지므로 같은 트랜잭션에서 갱신한다.
     */
    private suspend fun rewriteConfigLiterals(fd: FieldDefinition, mapping: Map<String, String>): Boolean {
        if (mapping.isEmpty()) return false
        val root = try {
            JSONObject(fd.config)
        } catch (_: Exception) {
            return false
        }
        var changed = false

        // SELECT options
        val options = root.optJSONArray("options")
        if (options != null) {
            val mapped = LinkedHashSet<String>()
            var optionChanged = false
            for (i in 0 until options.length()) {
                val opt = options.optString(i)
                val replaced = mapping[opt.trim()] ?: opt
                if (replaced != opt) optionChanged = true
                mapped.add(replaced)
            }
            if (optionChanged) {
                root.put("options", org.json.JSONArray().apply { mapped.forEach { put(it) } })
                changed = true
            }
        }

        // GRADE grades (등급명 → 점수 맵) — 키 치환, 대상 키가 이미 있으면 소스 키 제거
        val grades = root.optJSONObject("grades")
        if (grades != null) {
            val newGrades = JSONObject()
            var gradeChanged = false
            for (key in grades.keys().asSequence().toList()) {
                val replaced = mapping[key.trim()] ?: key
                if (replaced != key) gradeChanged = true
                if (!newGrades.has(replaced)) newGrades.put(replaced, grades.get(key))
            }
            if (gradeChanged) {
                root.put("grades", newGrades)
                changed = true
            }
        }

        // 시맨틱 ALIVE — SemanticFieldSyncHelper가 정확 일치 비교하는 값
        for (key in listOf("aliveValue", "deadValue")) {
            val current = root.optString(key, "")
            val replaced = mapping[current.trim()]
            if (current.isNotEmpty() && replaced != null && replaced != current) {
                root.put(key, replaced)
                changed = true
            }
        }

        if (changed) {
            fieldDao.update(fd.copy(config = root.toString()))
        }
        return changed
    }

    private suspend fun collectAffectedCharacterIds(fd: FieldDefinition, tokens: Set<String>): List<Long> =
        charValueDao.getValuesByFieldDef(fd.id)
            .filter { row -> FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens } }
            .map { it.characterId }.distinct()

    private suspend fun supportedDefsById(): Map<Long, FieldDefinition> =
        fieldDao.getAllFieldsAllTypes()
            .filter { FieldValueTokenizer.supportsLibrary(it) }
            .associateBy { it.id }

    private suspend fun universeOfCharacter(characterId: Long): Long? {
        val character = db.characterDao().getCharacterById(characterId) ?: return null
        val novelId = character.novelId ?: return null
        return db.novelDao().getNovelById(novelId)?.universeId
    }

    private suspend fun characterUniverseMap(): Map<Long, Long> {
        val novels = db.novelDao().getAllNovelsList().associate { it.id to it.universeId }
        return db.characterDao().getAllCharactersList()
            .mapNotNull { c -> c.novelId?.let { nid -> novels[nid]?.let { c.id to it } } }
            .toMap()
    }

    private fun parseImagePathList(imagePathsJson: String): List<String> {
        return try {
            val raw: List<String?>? = Gson().fromJson(imagePathsJson, GsonTypes.STRING_LIST)
            raw?.filterNotNull() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun safely(op: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // 수확·재계산은 보조 인덱스 작업 — 실패해도 사용자 저장에 영향을 주지 않고,
            // 다음 갱신 지점(화면 진입·임포트·유지보수)에서 자연 복구된다.
            Log.w("FieldValueLibrary", "$op failed", e)
        }
    }
}
