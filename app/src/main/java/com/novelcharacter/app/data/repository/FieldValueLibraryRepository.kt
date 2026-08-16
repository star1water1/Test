package com.novelcharacter.app.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.FieldEntryCount
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.util.InitialFieldValues
import com.novelcharacter.app.util.FieldValueResolver
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.GsonTypes
import com.novelcharacter.app.util.SqlInChunks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * - 전파는 character_field_values·event_field_values·novel_field_values에 더해
 *   character_state_changes(이력)와
 *   필드 config의 값 리터럴(SELECT options/GRADE grades/시맨틱 aliveValue·deadValue)까지 포함한다.
 * - deleteEntry(alsoClearData)는 파괴 전 영향 캐릭터를 휴지통에 스냅샷한다.
 */
class FieldValueLibraryRepository(private val db: AppDatabase) {

    private val entryDao get() = db.fieldValueEntryDao()
    private val fieldDao get() = db.fieldDefinitionDao()
    private val charValueDao get() = db.characterFieldValueDao()
    private val eventValueDao get() = db.eventFieldValueDao()
    private val novelValueDao get() = db.novelFieldValueDao()
    private val stateChangeDao get() = db.characterStateChangeDao()

    // ===== 조회 =====

    suspend fun entriesForField(fieldDefId: Long): List<FieldValueEntry> = entryDao.getByField(fieldDefId)

    fun entriesForFieldLive(fieldDefId: Long): LiveData<List<FieldValueEntry>> = entryDao.getByFieldLive(fieldDefId)

    /**
     * 필드별 엔트리 요약(총수·미분류·미사용).
     *
     * `unusedCount`는 `usageCount`에서 나오므로 **예약된 재집계를 먼저 기다린다** —
     * 화면이 '미사용 N'을 말하기 전에 그 N이 맞아야 한다. 읽는 쪽마다 기다리게 하면
     * 언젠가 빠뜨리는 곳이 생기므로(라이브러리 홈·통계 요약이 같은 소스다) 여기 한 곳에서 막는다.
     */
    suspend fun entryCounts(): Map<Long, FieldEntryCount> {
        awaitPendingRecounts()
        return entryDao.countByField().associateBy { it.fieldDefinitionId }
    }

    /** 폼 자동완성용 — 세계관의 비숨김 엔트리를 필드별로 1쿼리 배치 로드 */
    suspend fun suggestionsForUniverse(universeId: Long, entityType: String): Map<Long, List<FieldValueEntry>> =
        entryDao.getForUniverse(universeId, entityType).groupBy { it.fieldDefinitionId }

    suspend fun resolverForField(fieldDefId: Long): FieldValueResolver =
        FieldValueResolver(entryDao.getByField(fieldDefId))

    /**
     * 필드 집합의 엔트리 일괄 조회 (IN 청크). 숨김·별칭까지 그대로 준다 —
     * 용도별 필터링(예: AI 용례는 숨김 제외, restricted 검증은 숨김 포함)은 호출측이 판단한다.
     */
    suspend fun entriesForFields(fieldDefIds: Collection<Long>): Map<Long, List<FieldValueEntry>> =
        SqlInChunks.flat(fieldDefIds.distinct()) { entryDao.getForFields(it) }
            .groupBy { it.fieldDefinitionId }

    /** 필드 집합의 해석기 일괄 생성 (IN 청크) — 통계 스냅샷·엑셀 임포트용 */
    suspend fun resolversForFields(fieldDefIds: Collection<Long>): Map<Long, FieldValueResolver> {
        val entries = entriesForFields(fieldDefIds)
        return fieldDefIds.associateWith { FieldValueResolver(entries[it].orEmpty()) }
    }

    // ===== 자동 수확 (insert-only, 실패 무해) =====

    /** 캐릭터 저장/복원/이동 후 — 해당 캐릭터의 **현재 값**을 수확 (이력은 대상이 아니다 — B-60) */
    suspend fun harvestForCharacter(characterId: Long) = safely("harvestForCharacter") {
        val values = charValueDao.getValuesByCharacterList(characterId)
        val defsById = supportedDefsById()
        val tokensByField = HashMap<Long, MutableSet<String>>()
        for (v in values) {
            val fd = defsById[v.fieldDefinitionId] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }
                .addAll(FieldValueTokenizer.tokenize(fd, v.value))
        }
        // 상태변화 이력은 수확하지 않는다 — 모집단 규약은 [harvestUniversesOrThrow]가 든다 (B-60).
        val universeId = universeOfCharacter(characterId)
        insertNewTokens(tokensByField)
        scheduleRecount(recountTargetsForCharacter(universeId, defsById.values, tokensByField.keys))
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
        // 캐릭터 쪽과 **같은 함수**로 구역 전체를 대상에 넣는다 (B-203 · R-29).
        // 사건을 못 찾은 것과 사건의 구역이 전역인 것을 가른다 — 종전 `?.let`은 둘을 같게 다뤄
        // 전역 구역의 사건 필드가 통째로 빠졌다.
        val event = db.timelineDao().getEventById(eventId)
        val targets = if (event != null) {
            FieldValueRules.recountTargets(
                event.universeId, FieldDefinition.ENTITY_EVENT, defsById.values, tokensByField.keys
            )
        } else {
            tokensByField.keys
        }
        scheduleRecount(targets)
    }

    /** 작품 저장 후 (확-3) — 사건판([harvestForEvent])과 같은 규칙 */
    suspend fun harvestForNovel(novelId: Long) = safely("harvestForNovel") {
        val values = novelValueDao.getValuesByNovelList(novelId)
        val defsById = supportedDefsById()
        val tokensByField = HashMap<Long, MutableSet<String>>()
        for (v in values) {
            val fd = defsById[v.fieldDefinitionId] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }
                .addAll(FieldValueTokenizer.tokenize(fd, v.value))
        }
        insertNewTokens(tokensByField)
        // 캐릭터·사건 축과 **같은 함수**로 구역 전체의 작품 필드를 넣는다 (B-203 · R-29) —
        // 이 저장이 다른 작품에서 쓰이던 값을 지웠을 수도 있다. 무소속 작품의 구역은 전역이다.
        val novel = db.novelDao().getNovelById(novelId)
        val targets = if (novel != null) {
            FieldValueRules.recountTargets(
                novel.universeId, FieldDefinition.ENTITY_NOVEL, defsById.values, tokensByField.keys
            )
        } else {
            tokensByField.keys
        }
        scheduleRecount(targets)
    }

    /** 일괄 편집 등 필드 단위 쓰기 후 */
    suspend fun harvestField(fieldDefId: Long) = safely("harvestField") {
        val fd = fieldDao.getFieldById(fieldDefId) ?: return@safely
        if (!FieldValueTokenizer.supportsLibrary(fd)) return@safely
        val tokens = linkedSetOf<String>()
        charValueDao.getValuesByFieldDef(fd.id).forEach { tokens.addAll(FieldValueTokenizer.tokenize(fd, it.value)) }
        eventValueDao.getValuesByFieldDef(fd.id).forEach { tokens.addAll(FieldValueTokenizer.tokenize(fd, it.value)) }
        novelValueDao.getValuesByFieldDef(fd.id).forEach { tokens.addAll(FieldValueTokenizer.tokenize(fd, it.value)) }
        insertNewTokens(mapOf(fd.id to tokens))
        scheduleRecount(listOf(fd.id))
    }

    /** 엑셀 임포트 후 — 임포트가 건드린 세계관들 일괄 수확. null이면 전체(백필 시드). */
    suspend fun harvestUniverses(universeIds: Set<Long>?) = safely("harvestUniverses") {
        harvestUniversesOrThrow(universeIds)
    }

    /**
     * 백필·임포트 재시도 플래그 관리자용 throwing 변형 — 실패가 조용히 삼켜지면
     * seeded/harvest_pending 플래그가 잘못 기록되어 재시도 계약이 깨진다 (검토 후속).
     * 훅 경로는 [harvestUniverses]의 안전 래퍼를 그대로 쓴다.
     *
     * ## 수확의 모집단 — **현재 값 세 표뿐이다** (B-60 · 확정 20번 ㄱ1)
     *
     * 캐릭터·사건·작품의 **현재** 필드값만 읽는다. **상태변화 이력은 읽지 않는다.**
     *
     * 종전에는 이력도 토큰화해 엔트리를 만들었는데, 집계([recountUsageForFieldsOrThrow])는
     * 처음부터 현재 값 세 표만 셌다 — **수확과 집계가 서로 다른 모집단을 본 것이다.**
     * 그 비대칭의 증상은 조용하다: 이력에만 있는 값은 엔트리로 들어오고도 `usageCount`가
     * **영원히 0**이라, '미사용 자동수집 정리'가 그것을 지우자고 권한다.
     *
     * 둘 중 어느 쪽에 맞출 것인가는 *집계의 의미*를 정하는 일이라 사용자 판정을 받았고,
     * 답은 **`usageCount` = "지금 쓰이는 횟수"**였다. 그래서 **수확 쪽을 좁혔다** —
     * 집계를 넓히면 라이브러리가 *지금은 아무도 안 쓰는 값*을 쓰이는 것으로 세게 된다.
     *
     * **대가를 적어 둔다:** 이력에만 있던 값은 이제 카탈로그에 자동으로 오르지 않으므로
     * 자동완성 후보에서도 빠진다. 그것이 이 확정이 고른 쪽이다 — 카탈로그는 *쓰이는 값*의
     * 목록이고, 이력의 값이 필요하면 사용자가 직접 등재한다(자율성 우선). **이력 자체는
     * 아무것도 잃지 않는다** — 전파(rename/merge/delete)는 `character_state_changes`를
     * 그대로 대상에 넣으므로 표기를 바꾸면 이력도 함께 따라간다.
     */
    suspend fun harvestUniversesOrThrow(universeIds: Set<Long>?) {
        val defs = fieldDao.getAllFieldsAllTypes()
            .filter { FieldValueTokenizer.supportsLibrary(it) }
            .filter { universeIds == null || it.universeId in universeIds }
        if (defs.isEmpty()) return
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
        for (v in novelValueDao.getAllValuesList()) {
            val fd = defsById[v.fieldDefinitionId] ?: continue
            tokensByField.getOrPut(fd.id) { linkedSetOf() }.addAll(FieldValueTokenizer.tokenize(fd, v.value))
        }
        insertNewTokens(tokensByField)
        // 수확 범위 **전체**를 예약한다(토큰이 새로 생긴 필드만이 아니다) — 임포트·복원은
        // 기존 값을 지우거나 다른 기존 값으로 바꾸기도 하고, 그때도 집계는 달라진다.
        scheduleRecount(defs.map { it.id })
    }

    suspend fun harvestAll() = harvestUniverses(null)

    /** 백필/임포트용 throwing 변형 — 플래그 기록 전에 실패를 표면화한다 */
    suspend fun harvestAllOrThrow() = harvestUniversesOrThrow(null)

    private suspend fun insertNewTokens(tokensByField: Map<Long, Set<String>>) {
        val fieldIds = tokensByField.filterValues { it.isNotEmpty() }.keys
        if (fieldIds.isEmpty()) return
        val existingByField = SqlInChunks.flat(fieldIds) { entryDao.getForFields(it) }
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
        recountUsageOrThrow(fieldDefId)
    }

    /** 백필/임포트용 throwing 변형 */
    suspend fun recountUsageOrThrow(fieldDefId: Long) = recountUsageForFieldsOrThrow(listOf(fieldDefId))

    /**
     * 여러 필드의 usageCount를 한 번에 재계산 — **집계 구현은 이것 하나**다.
     *
     * 필드마다 [recountUsageOrThrow]를 부르면 필드 수 × 4쿼리가 되므로, 엔트리·필드정의·값을
     * IN 청크로 묶어 읽는다(36필드 세계관에서 144쿼리 → 4쿼리 남짓). 읽는 행 수 자체는 같으므로
     * 이 배치는 쿼리 왕복만 줄인다 — 지연 문제는 [scheduleRecount]가 따로 맡는다.
     */
    suspend fun recountUsageForFieldsOrThrow(fieldDefIds: Collection<Long>) {
        val ids = fieldDefIds.distinct()
        if (ids.isEmpty()) return
        val entriesByField = SqlInChunks.flat(ids) { entryDao.getForFields(it) }
            .groupBy { it.fieldDefinitionId }
        // 엔트리가 없는 필드는 셀 대상이 없다 — 값 조회에서도 빼 불필요한 스캔을 없앤다.
        val targetIds = entriesByField.keys.toList()
        if (targetIds.isEmpty()) return

        val defsById = SqlInChunks.flat(targetIds) { fieldDao.getFieldsByIds(it) }
            .associateBy { it.id }
        val charValuesByField = SqlInChunks.flat(targetIds) { charValueDao.getValuesByFieldDefs(it) }
            .groupBy { it.fieldDefinitionId }
        val eventValuesByField = SqlInChunks.flat(targetIds) { eventValueDao.getValuesByFieldDefs(it) }
            .groupBy { it.fieldDefinitionId }
        // 종류가 셋이므로 세 표를 모두 센다 — 한 표를 빠뜨리면 그 종류의 usageCount가 영원히
        // 0이고 '미사용 정리'가 살아 있는 값을 지우자고 권한다(R-29 · B-60과 같은 부류).
        val novelValuesByField = SqlInChunks.flat(targetIds) { novelValueDao.getValuesByFieldDefs(it) }
            .groupBy { it.fieldDefinitionId }

        val changed = mutableListOf<FieldValueEntry>()
        for (fieldId in targetIds) {
            val fd = defsById[fieldId] ?: continue
            val entries = entriesByField[fieldId] ?: continue
            val raw = charValuesByField[fieldId].orEmpty().map { it.value } +
                eventValuesByField[fieldId].orEmpty().map { it.value } +
                novelValuesByField[fieldId].orEmpty().map { it.value }
            changed.addAll(recountedEntries(fd, entries, raw))
        }
        // 이 자리만 성격이 다르다 — `updateAll`은 `@Update`라 **IN 절이 아니라 문장 묶음**이고,
        // 나누는 이유도 바인드 변수가 아니라 한 번에 실리는 문장 수다. 그럼에도 같은 통로를
        // 지나는 것은 나누는 규칙이 같고(상한 아래로), 한 덩이면 원본을 그대로 넘겨 사본이
        // 아예 없기 때문이다. 이 구별을 적어 두는 것은 다음 사람이 다시 캐지 않게 하기 위함이다.
        if (changed.isNotEmpty()) SqlInChunks.each(changed) { entryDao.updateAll(it) }
    }

    // ===== 재집계 예약 (수확과 같은 축으로 묶는다) =====

    /**
     * 수확이 건드린 필드의 재집계를 **예약**한다.
     *
     * **왜 이 자리에 있나:** 종전에는 수확(캐릭터 저장마다)과 집계(앱 1회 백필·엑셀 임포트·
     * 해당 필드의 라이브러리 화면 진입)가 **서로 다른 축**에서 돌았다. 그래서 저장으로 새로
     * 들어온 값은 `usageCount = 0`인 채 남았고, 실측에서 482엔트리 중 318(66%)이 0인데
     * 그 값이 전부 실제 데이터에 있는 상태가 됐다(U-0). 수확 뒤에 집계가 **반드시** 따라오게 해
     * 축을 하나로 합친다.
     *
     * **왜 즉시 하지 않나:** 수확 훅은 상위 저장 트랜잭션 안에서 불릴 수 있고
     * (`CharacterRepository.saveAllFieldValues`의 주석 참조), 재집계는 필드당 전량 스캔이라
     * 큰 세계관·많은 캐릭터에서 쓰기 트랜잭션을 오래 붙든다.
     * **미루되 건너뛰지 않는다** — 예약은 큐에 남아 반드시 실행되고, 조건부로 생략하지 않는다
     * (조건부 집계가 곧 지금 고치는 결함이다). 지금 정확한 수치가 필요한 읽기 경로는
     * [awaitPendingRecounts]로 큐가 빌 때까지 기다린다.
     */
    private fun scheduleRecount(fieldDefIds: Collection<Long>) {
        if (fieldDefIds.isEmpty()) return
        synchronized(pendingRecountFields) {
            pendingRecountFields.addAll(fieldDefIds)
            if (recountJob?.isActive == true) return
            recountJob = recountScope.launch { drainRecountQueue() }
        }
    }

    private suspend fun drainRecountQueue() {
        while (true) {
            val batch = synchronized(pendingRecountFields) {
                if (pendingRecountFields.isEmpty()) {
                    // 큐를 비운 것과 일꾼을 놓는 것을 **같은 잠금 안에서** 한다 —
                    // 따로 하면 그 사이에 들어온 예약이 "일꾼이 살아 있다"고 오판돼 유실된다.
                    recountJob = null
                    return
                }
                val copy = pendingRecountFields.toList()
                pendingRecountFields.clear()
                copy
            }
            // **트랜잭션 안에서 센다** — 이 배치는 저장 훅이 예약한 것이고, 그 훅은 상위 저장
            // 트랜잭션 **안에서** 불릴 수 있다(`CharacterRepository.saveAllFieldValues`).
            // 트랜잭션 밖에서 그냥 읽으면 아직 커밋되지 않은 저장의 **이전 데이터**를 세어
            // 옛 수치를 그대로 다시 써 넣고, 그 뒤 커밋이 일어나도 재집계를 다시 부르는 것이 없다
            // — 고치려던 결함이 경합의 형태로 되살아난다.
            // Room이 이 블록을 쓰기 트랜잭션 뒤로 직렬화해 주므로 커밋된 데이터만 센다.
            //
            // 불변식: [awaitPendingRecounts]를 **트랜잭션 안에서 부르지 않는다** — 부르면
            // 그 트랜잭션이 자기 뒤에 줄 선 이 블록을 기다려 교착한다. 현재 호출부(라이브러리
            // 화면 요약·정리 다이얼로그)는 모두 트랜잭션 밖의 UI 경로다.
            safely("recountUsageForFields") {
                db.withTransaction { recountUsageForFieldsOrThrow(batch) }
            }
        }
    }

    /**
     * 예약된 재집계가 끝날 때까지 기다린다 — **수치를 읽기 전에** 부른다.
     *
     * 라이브러리 화면의 '미사용 N'이나 정리 대상 건수는 화면이 그 수를 말하기 전에 맞아야 한다.
     * fire-and-forget 갱신에는 happens-before 간선이 없어 화면이 옛 수치를 먼저 보여 줄 수 있다.
     */
    suspend fun awaitPendingRecounts() {
        while (true) {
            val job: Job = synchronized(pendingRecountFields) {
                if (pendingRecountFields.isEmpty() && recountJob == null) return
                recountJob
            } ?: return
            job.join()
        }
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

    /**
     * 필드 생성 창의 **값 사전 등록**분을 한 번에 등재한다 — 필드 관리·작품 편집·사건 편집 공용.
     *
     * 종전에는 같은 일을 두 곳이 각자 했고 **실패 처리가 갈렸다**(한쪽은 고지, 한쪽은 무음).
     * 여기로 모으면서 정책을 하나로 정한다: **개별 값의 실패는 전체를 실패로 만들지 않는다.**
     * 필드는 이미 만들어졌으므로 실패로 보고하면 사용자가 필드가 안 생긴 줄 안다.
     * 대신 **등재하지 못한 수를 돌려주어** 호출부가 고지할 수 있게 한다(무음 유실 금지).
     *
     * @return 등재 시도 수와 실패 수. 지원하지 않는 타입이거나 적은 값이 없으면 둘 다 0이다.
     */
    suspend fun registerInitialValues(
        fieldDefId: Long,
        field: FieldDefinition,
        raw: String
    ): InitialValueOutcome {
        val staged = InitialFieldValues.parse(raw, field)
        if (staged.isEmpty()) return InitialValueOutcome(0, 0)
        var failed = 0
        for (value in staged) {
            val ok = runCatching { addEntry(fieldDefId, value) }.getOrNull()
            // Duplicate는 실패가 아니다 — 이미 있는 값을 또 적은 것뿐이다.
            if (ok == null) failed++
        }
        return InitialValueOutcome(staged.size, failed)
    }

    /** [registerInitialValues]의 결과 — 실패분을 고지하려면 호출부가 이 수를 쓴다. */
    data class InitialValueOutcome(val attempted: Int, val failed: Int)

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
        val snapshottedCharacters: Int = 0,
        /** 작품 필드값 행 수 (확-3) — 종류가 늘면 합계에도 함께 들어와야 한다 */
        val novelValues: Int = 0
    ) {
        val total: Int get() = characterValues + eventValues + stateChanges + novelValues

        operator fun plus(o: PropagationReport) = PropagationReport(
            characterValues + o.characterValues,
            eventValues + o.eventValues,
            stateChanges + o.stateChanges,
            whitespaceNormalized + o.whitespaceNormalized,
            configUpdated || o.configUpdated,
            (affectedCharacterIds + o.affectedCharacterIds).distinct(),
            snapshottedCharacters + o.snapshottedCharacters,
            novelValues + o.novelValues
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
        // 정리는 커밋 이후에 — 종전에는 스냅샷만 남기고 pruneIfNeeded를 부르지 않아
        // 다음 삭제 작업까지 한도를 넘긴 채 쌓였다 (B-15).
        // **편집 직전 백업**이다 — 캐릭터는 지워지지 않고 필드값만 고쳐 쓰인다(B-2).
        val trash = TrashRepository(db, com.novelcharacter.app.data.model.TrashSnapshot.KIND_EDIT_BACKUP)
        val report = db.withTransaction {
            // 파괴 전 스냅샷 — CharacterRepository.deleteCharacter 선례 (IN 절 상한은 통로가 지킨다)
            val affected = collectAffectedCharacterIds(fd, matchTokens)
            var snapshotted = 0
            SqlInChunks.each(affected) { chunk ->
                for (character in db.characterDao().getCharactersByIds(chunk)) {
                    // 이 경로가 파괴하는 것은 필드값과 상태변화뿐이다(rewriteTokens) —
                    // 캐릭터 행·태그·세력 소속은 손대지 않으므로 되돌리기 범위에 넣지 않는다.
                    trash.snapshotCharacter(
                        character,
                        parseImagePathList(character.imagePaths),
                        kind = com.novelcharacter.app.data.model.TrashSnapshot.KIND_EDIT_BACKUP,
                        revertScope = RestoreModes.SCOPE_LIBRARY_ENTRY_DELETE
                    )
                    snapshotted++
                }
            }
            val propagation = rewriteTokens(fd, matchTokens, replacement = null)
            entryDao.delete(entry)
            propagation.copy(snapshottedCharacters = snapshotted)
        }
        trash.pruneIfNeeded()
        return report
    }

    /** 전파 없이 영향 규모만 산출 — 확인 다이얼로그·AI 정리 드라이런용 */
    suspend fun previewPropagation(fd: FieldDefinition, tokens: Set<String>): PropagationReport {
        var charRows = 0
        var eventRows = 0
        var stateRows = 0
        var novelRows = 0
        val affected = mutableListOf<Long>()
        for (row in charValueDao.getValuesByFieldDef(fd.id)) {
            if (FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens }) {
                charRows++; affected.add(row.characterId)
            }
        }
        for (row in eventValueDao.getValuesByFieldDef(fd.id)) {
            if (FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens }) eventRows++
        }
        for (row in novelValueDao.getValuesByFieldDef(fd.id)) {
            if (FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens }) novelRows++
        }
        val fdUniverseId = fd.universeId
        if (fd.entityType == FieldDefinition.ENTITY_CHARACTER && fdUniverseId != null) {
            // 전역 구역 필드(null)는 건너뛴다 — 상태변화는 세계관 캐릭터의 표라 무소속 값에는
            // 해당 행이 원리적으로 없다(0건 순회를 거르는 것이지 데이터를 거르는 것이 아니다).
            for (change in stateChangeDao.getChangesByFieldKeyForUniverse(fdUniverseId, fd.key)) {
                if (FieldValueTokenizer.tokenize(fd, change.newValue).any { it in tokens }) stateRows++
            }
        }
        return PropagationReport(
            charRows, eventRows, stateRows,
            affectedCharacterIds = affected.distinct(), novelValues = novelRows
        )
    }

    data class UsageDrilldown(
        val characters: List<Character>,
        val events: List<TimelineEvent>,
        val stateChangeCount: Int,
        /** 이 값을 쓰는 작품 (확-3) — 종류가 늘면 사용처 목록도 함께 늘어야 한다 */
        val novels: List<com.novelcharacter.app.data.model.Novel> = emptyList()
    )

    /** 값 사용처 — 원칙 04: 숨은 데이터 없음 */
    suspend fun usageDrilldown(fd: FieldDefinition, entry: FieldValueEntry): UsageDrilldown {
        val tokens = setOf(entry.value) + entry.aliases()
        val charIds = collectAffectedCharacterIds(fd, tokens)
        val eventIds = eventValueDao.getValuesByFieldDef(fd.id)
            .filter { row -> FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens } }
            .map { it.eventId }.distinct()
        var stateCount = 0
        val fdScope = fd.universeId
        if (fd.entityType == FieldDefinition.ENTITY_CHARACTER && fdScope != null) {
            // 전역 구역 필드(null)는 상태변화가 원리적으로 없다 — 연표는 세계관 기능이다.
            stateCount = stateChangeDao.getChangesByFieldKeyForUniverse(fdScope, fd.key)
                .count { change -> FieldValueTokenizer.tokenize(fd, change.newValue).any { it in tokens } }
        }
        val novelIds = novelValueDao.getValuesByFieldDef(fd.id)
            .filter { row -> FieldValueTokenizer.tokenize(fd, row.value).any { it in tokens } }
            .map { it.novelId }.distinct()
        val characters = SqlInChunks.flat(charIds) { db.characterDao().getCharactersByIds(it) }
        val events = eventIds.mapNotNull { db.timelineDao().getEventById(it) }
        val novels = SqlInChunks.flat(novelIds) { db.novelDao().getNovelsByIds(it) }
        return UsageDrilldown(characters, events, stateCount, novels)
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
        if (!FieldValueTokenizer.supportsLibrary(fd)) {
            // 미지원 타입(NUMBER 등)의 라벨/카테고리는 통계 폴백 경로가 계속 쓰므로 건드리지 않는다
            return 0
        }
        return db.withTransaction {
            // 스냅샷이 아니라 트랜잭션 안에서 최신 행을 다시 읽는다 — 백그라운드 백필이
            // 동시 편집된 필드를 stale 스냅샷으로 되돌리지 않게 (last-writer-wins 방지)
            val fresh = fieldDao.getFieldById(fd.id) ?: return@withTransaction 0
            val stats = FieldStatsConfig.fromConfig(fresh.config)
            if (stats.valueLabels.isEmpty() && stats.valueCategories.isEmpty()) return@withTransaction 0

            // 라벨 키공간 카테고리도 엔트리로 시드한다: 저장 데이터가 라벨 문자열 그대로일 수 있고
            // (구 통계는 그 경우도 그룹핑했음), 해석기의 라벨 체인이 이 엔트리를 통해 동작한다
            val rawKeys = LinkedHashSet<String>().apply {
                addAll(stats.valueLabels.keys)
                addAll(stats.valueCategories.keys)
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
            // 엔트리가 라벨을 확보한 뒤에야 config에서 두 맵을 제거 (같은 트랜잭션, 최신 행 기준)
            val stripped = FieldStatsConfig.applyToConfig(
                fresh.config,
                stats.copy(valueLabels = emptyMap(), valueCategories = emptyMap())
            )
            fieldDao.update(fresh.copy(config = stripped))
            migrated
        }
    }

    // ===== 검증 =====

    companion object {
        // ── 재집계 대기열 (프로세스 전역) ──
        // 이 리포지토리는 앱 싱글턴 말고도 여러 곳에서 직접 생성된다(휴지통 복원·월드팩 임포트).
        // 큐를 인스턴스 필드에 두면 생성자마다 큐가 쪼개져 합치는 의미가 사라지고, 어떤 인스턴스의
        // 예약은 아무도 비우지 않는 채로 남는다. DB가 하나이므로 큐도 하나다.
        private val recountScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pendingRecountFields = LinkedHashSet<Long>()
        private var recountJob: Job? = null

        /** 집계 산수·대상 판정은 [FieldValueRules]가 갖는다(순수 JVM 실행 검증 대상) — 여기서는 위임만 한다. */
        fun recountedEntries(
            fd: FieldDefinition,
            entries: List<FieldValueEntry>,
            rawValues: List<String>
        ): List<FieldValueEntry> = FieldValueRules.recountedEntries(fd, entries, rawValues)

        fun recountTargetsForCharacter(
            universeId: Long?,
            supportedDefs: Collection<FieldDefinition>,
            touchedFieldIds: Set<Long>
        ): Set<Long> = FieldValueRules.recountTargetsForCharacter(universeId, supportedDefs, touchedFieldIds)

        /** RESTRICTED 모드 위반 토큰 — 판정은 [FieldValueRules]에 있다(순수 JVM 실행 검증 대상). */
        fun validateRestricted(
            fd: FieldDefinition,
            raw: String,
            entries: List<FieldValueEntry>
        ): List<String> = FieldValueRules.validateRestricted(fd, raw, entries)

        /** 값/별칭 충돌 검사 — 판정은 [FieldValueRules]에 있다(순수 JVM 실행 검증 대상). */
        fun conflictOf(
            entries: List<FieldValueEntry>,
            token: String,
            excludeId: Long?
        ): Pair<FieldValueEntry, Boolean>? = FieldValueRules.conflictOf(entries, token, excludeId)
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
        var novelRows = 0
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
                    if (FieldValueTokenizer.join(tokens) != row.value) whitespaceOnly++
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
                    if (FieldValueTokenizer.join(tokens) != row.value) whitespaceOnly++
                }
            }
        }
        // 작품 필드값도 같은 규칙으로 고쳐 쓴다(확-3) — 빠뜨리면 이름을 바꾼 값이 작품에만
        // 옛 이름으로 남고, '데이터도 함께 삭제'가 작품 값을 남긴 채 지웠다고 말한다.
        for (row in novelValueDao.getValuesByFieldDef(fd.id)) {
            val tokens = FieldValueTokenizer.tokenize(fd, row.value)
            val mapped = mapTokens(tokens) ?: continue
            if (mapped.isEmpty()) {
                novelValueDao.delete(row)
                novelRows++
            } else {
                val joined = FieldValueTokenizer.join(mapped)
                if (joined != row.value) {
                    novelValueDao.update(row.copy(value = joined))
                    novelRows++
                    if (FieldValueTokenizer.join(tokens) != row.value) whitespaceOnly++
                }
            }
        }
        // 상태변화 이력 — 연도 슬라이더·성장 그래프가 읽는 시간축 데이터 (A2: 무통보 이력 파손 방지)
        val scopeForState = fd.universeId
        if (fd.entityType == FieldDefinition.ENTITY_CHARACTER && scopeForState != null) {
            for (change in stateChangeDao.getChangesByFieldKeyForUniverse(scopeForState, fd.key)) {
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
                        if (FieldValueTokenizer.join(tokens) != change.newValue) whitespaceOnly++
                    }
                }
            }
        }
        return PropagationReport(
            charRows, eventRows, stateRows, whitespaceOnly, false, affected.distinct(),
            novelValues = novelRows
        )
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
