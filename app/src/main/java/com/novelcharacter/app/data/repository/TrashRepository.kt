package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.TrashSnapshotImages
import com.novelcharacter.app.data.dao.operationLookup
import com.novelcharacter.app.data.dao.TrashSnapshotSummary
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterSnapshot
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.EntityRefs
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.EventSnapshot
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FactionRelationship
import com.novelcharacter.app.data.model.FactionSnapshot
import com.novelcharacter.app.data.model.FieldDefNaturalKey
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.GradeSystem
import com.novelcharacter.app.data.model.GradeSystemRef
import com.novelcharacter.app.data.model.GradeSystemSnapshot
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.NovelSnapshot
import com.novelcharacter.app.data.model.SnapshotRefs
import com.novelcharacter.app.data.model.StateChangeSnapshot
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.UniverseDataSnapshot
import com.novelcharacter.app.data.model.UniverseSnapshot
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.util.GsonTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 휴지통 (B-7 → B-1). 삭제 시점 스냅샷 보관 → 목록/복원/영구 삭제.
 *
 * 소프트 삭제(deletedAt 컬럼) 대신 스냅샷 방식을 채택 — 기존 조회 쿼리 20여 곳에
 * 필터를 심을 필요가 없고, FK CASCADE로 함께 사라지는 연관 데이터까지 복원 가능하다.
 * 이미지 파일은 스냅샷이 살아 있는 동안 유지되고 영구 삭제 시 함께 지워진다.
 *
 * ## 참조 정체성 (N1 / R-1)
 * payload는 연관 데이터의 참조를 DB id로 담되, **안정 식별자(코드/자연키)를 함께** 담는다
 * ([SnapshotRefs] / [EntityRefs]). 복원은 [SnapshotRefResolver]의 사다리로 대상을 다시 찾으므로
 * 덮어쓰기 임포트·세계관 재생성으로 id가 재발급돼도 같은 대상에 다시 붙는다.
 * 구버전 payload(refs 없음)는 종전대로 id 단독 해석으로 폴백한다.
 *
 * ## 캐릭터 외 엔티티 (B-1)
 * 세계관·작품·세력·사건도 스냅샷을 남긴다. 스냅샷들은 **겹치지 않고 이어붙는다** —
 * 두 엔티티를 잇는 데이터는 [TrashSnapshot.restorePriority]가 **더 나중에 복원되는 쪽**이
 * 담는다(같으면 결정적으로 한쪽). 그래야 복원 시점에 상대가 이미 존재하고, 같은 링크를
 * 두 스냅샷이 각자 복원하려다 "이미 있음/못 찾음"으로 **사실과 다른 경고**가 뜨지 않는다.
 *
 * 한 번의 삭제가 만든 스냅샷은 [TrashSnapshot.operationId]로 묶이고,
 * 정리도 복원도 **작업 단위**로 한다(B-14). 인스턴스 하나가 곧 작업 하나이므로
 * **한 삭제는 반드시 하나의 인스턴스를 공유해야 한다**(R-3).
 */
class TrashRepository(
    private val db: AppDatabase,
    /**
     * 이 인스턴스가 만드는 스냅샷이 **삭제 백업**인지 **파괴적 편집 직전 백업**인지 (B-2).
     *
     * 편집 백업은 원본이 살아 있으므로 복원이 되돌리기가 아니라 복제다. 묶음 머리글과
     * '전체 복원'을 내주면 "이 삭제로 지워진 12개 항목을 복원하시겠습니까?"라는 **거짓 안내**와
     * 원클릭 복제가 된다 — 종전에는 행마다 독립이라 묶이지 않았던 것이 작업 단위 보관(B-14)
     * 때문에 묶이게 됐다. 종류를 함께 적어 UI가 구분하게 한다.
     */
    private val operationKind: String = TrashSnapshot.KIND_DELETE
) {

    private val trashDao = db.trashSnapshotDao()
    private val gson = Gson()

    /**
     * 이 인스턴스가 대표하는 삭제 작업의 id.
     *
     * 인스턴스 하나 = 작업 하나다. 세계관 삭제처럼 한 번에 수백 건을 스냅샷하는 경로에서
     * 종전 정리는 그 백업을 스스로 태우고(이미지 파일까지) "휴지통에서 복구할 수 있습니다"라는
     * 안내만 남겼다 — 4-6 규약 위반이다. 이제 정리 단위가 작업이라 그 묶음은 통째로 남는다.
     */
    private val operationId: String = generateEntityCode()
    private var createdAny = false

    /** 이 작업이 만든 스냅샷 — 정리에서 **보호**한다(R-3). 아직 만든 게 없으면 보호할 것도 없다. */
    private val protectedOperationKeys: Set<String>
        get() = if (createdAny) setOf(operationId) else emptySet()

    // **스냅샷 생성 전용** 코드 캐시 — 삭제 트랜잭션 한 번 동안만 유효하다.
    //
    // 계단식 삭제는 캐릭터마다 snapshotCharacter를 부르는데, 관계 상대·세력·사건은 캐릭터
    // 사이에서 대량으로 겹친다(같은 세력 100명, 같은 사건에 20명). 캐시가 없으면 이 조회가
    // 삭제 트랜잭션 안에서 캐릭터 수 × 참조 수만큼 반복된다 — '받쳐주는 확장성' 기준 미달.
    //
    // **복원 경로에서는 쓰지 말 것.** 휴지통 화면이 쓰는 인스턴스는 앱 수명 내내 살아 있어
    // 그 사이 대상이 지워졌다 다시 만들어지면 같은 id에 다른 코드가 붙는다. 캐시된 옛 코드로
    // 해석하면 오배정이 되고, 오배정은 생략보다 나쁘다(R-1).
    private val universeCodeCache = HashMap<Long, String?>()
    private val fieldDefRefCache = HashMap<Long, FieldDefRef?>()
    private val characterCodeCache = HashMap<Long, String?>()
    private val factionCodeCache = HashMap<Long, String?>()
    private val eventCodeCache = HashMap<Long, String?>()
    private val novelCodeCache = HashMap<Long, String?>()

    /**
     * 목록용 스트림 — **payload를 읽지 않는다.**
     *
     * 캐릭터 전용 시절에는 행이 최대 30개라 전체 조회가 안전했지만, 이제 한 작업이 수백 행이고
     * 한도는 작업 30건이라 테이블이 수만 행이 될 수 있다. payload까지 읽으면 휴지통 화면을
     * 여는 것만으로 수십 MB를 메모리에 올린다 — 한도를 작업 단위로 올리면서 사라진 상한을
     * 여기서 다시 세운다('받쳐주는 확장성').
     */
    val allSnapshots: LiveData<List<TrashSnapshotSummary>> = trashDao.getAllSummaries()

    // ──────────────────────────────────────────────────────────────────────
    // 결과 / 미리보기 — 모든 엔티티 타입이 같은 형태를 쓴다
    // ──────────────────────────────────────────────────────────────────────

    /** 복원 자체가 불가능한 사유. null이 아니면 진행해도 되살아나지 않으므로 막는다. */
    enum class RestoreBlocker {
        /** 세력은 세계관 없이 존재할 수 없다(NOT NULL) — 세계관을 먼저 복원해야 한다. */
        MISSING_UNIVERSE,

        /** 상태변화 이력은 캐릭터 없이 존재할 수 없다(FK) — 캐릭터를 먼저 복원해야 한다. */
        MISSING_CHARACTER,

        /**
         * 같은 이력이 이미 살아 있다 — 되살리면 사본이 하나 더 생긴다 (B-21).
         *
         * **덮어쓰지 않는다**: 그 사이 사용자가 다시 만든 값을 백업 없이 파괴하게 되고, 복원은
         * 성공 시 스냅샷을 소각하므로 어느 쪽으로도 되돌릴 수 없다(사건 복원이 쓰는 규약과 같다).
         * **스냅샷도 태우지 않는다** — 살아 있는 값과 백업의 값이 다를 수 있으므로, 남겨 두어야
         * 사용자가 내용을 확인하고 직접 고를 수 있다(R-4).
         */
        ALREADY_EXISTS
    }

    /**
     * 복원 **전** 미리보기 — 무엇이 되살아나고 무엇이 안 되는지 먼저 알린다.
     *
     * 복원은 성공 시 스냅샷을 소각하므로, 되살릴 수 없는 부분이 있는데 그대로 진행하면
     * payload에만 남아 있던 원본이 그 순간 영구 소멸한다. 그래서 "검증 → 알림 → 교정 경로"의
     * 마지막 단계를 여기서 만든다 — 사용자가 취소하면 스냅샷은 손대지 않으므로,
     * 세계관/필드 정의를 먼저 복구한 뒤 다시 복원할 수 있다(R-4).
     */
    data class RestorePreview(
        val entityType: String,
        val entityName: String,
        val losses: RestoreLossCounts = RestoreLossCounts(),
        /** id가 재발급됐지만 코드/자연키로 다시 찾은 참조 건수 (R-1 수정의 실효) */
        val relinkedByCode: Int = 0,
        /**
         * 같은 코드의 엔티티가 아직 살아 있다 — 이 스냅샷은 '삭제 백업'이 아니라
         * '파괴적 편집 직전 백업'이며, 복원하면 되돌리기가 아니라 **사본이 하나 더 생긴다**.
         * 사실대로 먼저 알린다.
         */
        val duplicatesLivingEntity: Boolean = false,
        /** 구버전 payload라 안정 식별자가 없어 id 단독으로 판단한 참조가 있다 */
        val legacyPayload: Boolean = false,
        val blocker: RestoreBlocker? = null,
        /**
         * 이 복원은 되살리기가 아니라 **살아 있는 대상에 덮어쓰는 되돌리기**다 (B-21).
         *
         * 앱에서 유일하게 '복원'이 살아 있는 데이터를 파괴하는 경우이므로 반드시 동의를 받는다.
         * 덮이는 값은 실행 직전에 편집 백업으로 한 번 더 스냅샷하므로 되돌릴 수 있다(R-4).
         */
        val revertsInPlace: Boolean = false,
        /** 되돌리기가 교체할 갈래 ([RestoreModes]의 SCOPE_*) — 문구가 무엇이 덮이는지 말한다. */
        val revertScope: Set<String> = emptySet()
    ) {
        val hasSkipped: Boolean get() = losses.any

        /**
         * 구버전 payload는 유실이 없어도 알린다 — 안정 식별자가 없어 id 단독으로 판단하므로
         * 오배정 위험이 가장 큰 복원인데, 종전에는 그 사실이 사용자에게 전혀 도달하지 않았다.
         */
        val needsConfirmation: Boolean
            get() = hasSkipped || duplicatesLivingEntity || legacyPayload ||
                blocker != null || revertsInPlace
    }

    /** 복원 결과 — 어떤 연관 데이터가 참조 소실로 생략됐는지 사용자에게 알리기 위한 집계 */
    data class RestoreResult(
        val entityType: String,
        val restoredName: String,
        val losses: RestoreLossCounts = RestoreLossCounts(),
        val relinkedByCode: Int = 0,
        /**
         * 같은 것이 이미 있어 새로 만들지 않은 관계 수 — 유실이 아니므로 [losses]와 칸을 나눈다.
         * (상대 캐릭터를 먼저 복원했을 때 정상적으로 일어나는 일이다)
         */
        val duplicateRelationships: Int = 0,
        /**
         * 출생/사망 사건 복원이 되살린 캐릭터 상태변화 이력 수.
         * 유실이 아니라 **부분 복원 고지**다 — 이력은 돌아왔지만 파생 필드값(생년·나이·생존
         * 여부)은 그 사이 사용자가 고쳤을 수 있어 되돌리지 않았다는 사실을 함께 알려야 한다.
         */
        val restoredSemanticStateChanges: Int = 0,
        /**
         * 되살린 것이 아니라 **살아 있는 대상을 되돌렸다** (B-21).
         *
         * 미리보기 이후 원본이 사라져 부활로 내려갔을 수 있으므로, 결과 문구는 동의 시점의
         * 예고가 아니라 이 값을 보고 말해야 한다.
         */
        val revertedInPlace: Boolean = false,
        /** 되돌리기가 **보충한** 세력 소속 수 (이미 있던 것은 건드리지 않았다). */
        val revertedMemberships: Int = 0,
        /** 되돌리기가 **보충한** 상태변화 이력 수 (같은 이력이 있으면 건너뛴다). */
        val revertedStateChanges: Int = 0
    ) {
        val hasSkipped: Boolean get() = losses.any
    }

    /** 작업 전체 복원 결과 — 항목별 결과와 막힌 항목을 함께 돌려준다. */
    data class OperationRestoreResult(
        val restored: List<RestoreResult> = emptyList(),
        /** 되살리지 못한 항목의 이름 (막혔거나 payload가 깨졌거나 예외) */
        val failed: List<String> = emptyList()
    ) {
        val losses: RestoreLossCounts
            get() = restored.fold(RestoreLossCounts()) { acc, r -> acc + r.losses }
        val relinkedByCode: Int get() = restored.sumOf { it.relinkedByCode }
        val duplicateRelationships: Int get() = restored.sumOf { it.duplicateRelationships }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 스냅샷 생성
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 캐릭터+연관 데이터를 스냅샷으로 보관한다.
     * 반드시 삭제 트랜잭션 내에서, 실제 삭제 전에 호출할 것.
     *
     * @param kind 이 **호출**이 남기는 것이 삭제 백업인가 편집 직전 백업인가.
     *   인스턴스 기본값([operationKind])에 맡기지 말 것 — 한 작업이 하나의 인스턴스를 공유해야
     *   한다는 R-3/R-9 때문에 **삭제와 편집 백업이 같은 인스턴스를 쓰는 경로가 실재한다**
     *   (엑셀 임포트: 세계관 이동 편집 백업과 '엑셀에 없는 항목 삭제'가 같은 인스턴스다).
     *   종류는 인스턴스가 아니라 이 호출이 아는 사실이므로 호출부가 정한다.
     * @param revertScope 편집 백업일 때, 그 편집이 실제로 파괴하는 갈래([RestoreModes]의 SCOPE_*).
     *   되돌리기의 교체 범위가 되므로 **파괴하지 않는 것을 넣지 말 것** — 넣는 만큼 그대로
     *   새로운 파괴 경로가 된다.
     */
    suspend fun snapshotCharacter(
        character: Character,
        imagePaths: List<String>,
        kind: String = operationKind,
        revertScope: List<String>? = null
    ) {
        val relationships = db.characterRelationshipDao().getRelationshipsForCharacterList(character.id)
        val relationshipChanges = relationships.flatMap { rel ->
            db.characterRelationshipChangeDao().getChangesForRelationshipList(rel.id)
        }
        val fieldValues = db.characterFieldValueDao().getValuesByCharacterList(character.id)
        val factionMemberships = db.factionMembershipDao().getMembershipsByCharacterList(character.id)
        val eventIds = db.timelineDao().getEventIdsForCharacter(character.id)

        val snapshot = CharacterSnapshot(
            character = character,
            fieldValues = fieldValues,
            stateChanges = db.characterStateChangeDao().getChangesByCharacterList(character.id),
            tags = db.characterTagDao().getTagsByCharacterList(character.id),
            relationships = relationships,
            relationshipChanges = relationshipChanges,
            factionMemberships = factionMemberships,
            eventIds = eventIds,
            refs = buildRefs(character, fieldValues, relationships, relationshipChanges, factionMemberships, eventIds),
            // 사용 표시가 풀리기 **전에** 읽어야 한다 — 삭제 경로는 스냅샷 뒤에
            // resetUsageByCharacter를 부르므로 이 시점에는 아직 링크가 살아 있다 (B-3).
            nameBankCodes = db.nameBankDao().getEntriesUsedByCharacter(character.id)
                .map { it.code }.filter { it.isNotBlank() },
            revertScope = revertScope?.takeIf { kind == TrashSnapshot.KIND_EDIT_BACKUP }
        )
        insertSnapshot(TrashSnapshot.TYPE_CHARACTER, character.name, snapshot, imagePaths, kind)
    }

    /**
     * 세계관 스냅샷 — 반드시 삭제 트랜잭션 안에서, **어떤 삭제보다 먼저** 호출할 것.
     *
     * 담는 것은 "세계관과 함께 죽지만 다른 스냅샷이 담지 않는 것"이다:
     * 필드 정의(FK CASCADE), 값 라이브러리 엔트리, 그리고 **삭제되지 않는** 캐릭터·사건이
     * 갖고 있던 이 세계관 필드의 값. 마지막 항목이 핵심이다 — 미분류 캐릭터가 보관 중인
     * 값(N2)은 어떤 캐릭터 스냅샷에도 담기지 않으면서 필드 정의 CASCADE로 함께 소멸한다.
     *
     * 함께 삭제되는 캐릭터·사건의 값은 각자의 스냅샷이 담으므로 여기서 제외한다 — 그 제외는
     * **SQL 서브쿼리로** 한다. 전량을 읽어 메모리에서 거르면 캐릭터 300명·필드 50개 세계관에서
     * 1만 5천 행을 올려 거의 0행을 남기게 되고, 그 작업이 삭제 트랜잭션 안에서 벌어진다.
     */
    suspend fun snapshotUniverse(universe: Universe) {
        val fieldDefs = db.fieldDefinitionDao().getFieldsByUniverseAllTypes(universe.id)
        val fieldDefIds = fieldDefs.map { it.id }

        val entries = ArrayList<FieldValueEntry>()
        val charValues = ArrayList<CharacterFieldValue>()
        val eventValues = ArrayList<EventFieldValue>()
        val novelValues = ArrayList<NovelFieldValue>()
        for (chunk in fieldDefIds.chunked(IN_CLAUSE_CHUNK)) {
            // **큐레이션된 엔트리만 담는다.** 자동 수확(AUTO, 손대지 않은) 엔트리는 실제
            // 필드값에서 다시 만들어지므로(harvestUniverses) 담아 봐야 같은 것을 두 벌 갖는
            // 것이고, 수만 건이면 payload 한 행이 CursorWindow 한도(2MB)를 넘겨 **그 백업을
            // 영영 읽을 수 없게** 만든다 — 복원 가능하다고 안내한 뒤에.
            // 사용자가 손댄 것(표시 라벨·별칭·카테고리·설명·숨김·MANUAL/AI/IMPORT 출처)만이
            // 다시 만들 수 없는 데이터다.
            db.fieldValueEntryDao().getForFields(chunk).filterTo(entries) { it.isCurated() }
            charValues.addAll(
                db.characterFieldValueDao().getOrphanValuesForUniverseFields(chunk, universe.id)
            )
            eventValues.addAll(
                db.eventFieldValueDao().getOrphanValuesForUniverseFields(chunk, universe.id)
            )
            // 작품 필드값도 같은 이유로 담는다(확-3) — 세계관을 옮긴 작품이 옛 세계관 필드의
            // 값을 보관 중이면 어떤 작품 스냅샷에도 실리지 않은 채 정의 CASCADE로 사라진다.
            novelValues.addAll(
                db.novelFieldValueDao().getOrphanValuesForUniverseFields(chunk, universe.id)
            )
        }

        val characterCodes = HashMap<String, String>()
        collectCharacterCodes(
            charValues.map { it.characterId } + listOfNotNull(universe.imageCharacterId),
            characterCodes
        )
        val eventCodes = HashMap<String, String>()
        for (id in eventValues.map { it.eventId }.distinct()) {
            eventCode(id)?.let { eventCodes[id.toString()] = it }
        }
        val novelCodes = HashMap<String, String>()
        universe.imageNovelId?.let { id -> novelCode(id)?.let { novelCodes[id.toString()] = it } }
        for (id in novelValues.map { it.novelId }.distinct()) {
            novelCode(id)?.let { novelCodes[id.toString()] = it }
        }

        val snapshot = UniverseSnapshot(
            universe = universe,
            fieldDefinitions = fieldDefs,
            refs = EntityRefs(
                characters = characterCodes,
                events = eventCodes,
                novels = novelCodes
            ),
            // 등급 체계(U-1) — 필드 정의처럼 FK CASCADE로 함께 죽는다. 참조는 필드 config가
            // code로 들고 있으므로 여기 담긴 체계만 되살리면 다시 이어진다.
            gradeSystems = db.gradeSystemDao().getByUniverseList(universe.id)
        )
        insertSnapshot(
            TrashSnapshot.TYPE_UNIVERSE, universe.name, snapshot,
            parseImagePathStrings(universe.imagePaths)
        )

        // 크기가 자라는 부분은 **여러 행으로 쪼갠다.** 값 라이브러리와 고아 필드값은
        // 프로젝트 규모에 비례하는데, 한 행이 CursorWindow 한도(2MB)를 넘으면 그 백업을
        // 영영 읽지 못한다 — 복원 가능하다고 안내한 뒤에.
        val fieldDefRefs = HashMap<String, FieldDefRef>()
        for (fd in fieldDefs) {
            fieldDefRefs[fd.id.toString()] =
                FieldDefRef(universeCode = universe.code, entityType = fd.entityType, key = fd.key)
        }
        writeUniverseDataChunks(
            universe, entries, charValues, eventValues, novelValues,
            fieldDefRefs, characterCodes, eventCodes, novelCodes
        )
    }

    /**
     * 세계관 부가 데이터를 크기 예산([PAYLOAD_BUDGET_CHARS]) 단위로 잘라 저장한다.
     *
     * 참조는 전부 안정 식별자로 병기한다 — 이 행들은 세계관 본체보다 **나중에** 복원되므로
     * 그때는 필드 정의가 새 id로 다시 만들어져 있고, 옛 id로는 아무것도 찾을 수 없다(R-1).
     */
    private suspend fun writeUniverseDataChunks(
        universe: Universe,
        entries: List<FieldValueEntry>,
        charValues: List<CharacterFieldValue>,
        eventValues: List<EventFieldValue>,
        novelValues: List<NovelFieldValue>,
        fieldDefRefs: Map<String, FieldDefRef>,
        characterCodes: Map<String, String>,
        eventCodes: Map<String, String>,
        novelCodes: Map<String, String>
    ) {
        if (entries.isEmpty() && charValues.isEmpty() && eventValues.isEmpty() && novelValues.isEmpty()) return

        val pendingEntries = ArrayList<FieldValueEntry>()
        val pendingChars = ArrayList<CharacterFieldValue>()
        val pendingEvents = ArrayList<EventFieldValue>()
        val pendingNovels = ArrayList<NovelFieldValue>()
        var budget = 0

        suspend fun flush() {
            if (pendingEntries.isEmpty() && pendingChars.isEmpty() &&
                pendingEvents.isEmpty() && pendingNovels.isEmpty()
            ) return
            // 이 조각이 실제로 쓰는 참조만 담는다 — 조각마다 전체 맵을 복사하면 그 자체가 커진다.
            val usedDefIds = (pendingEntries.map { it.fieldDefinitionId } +
                pendingChars.map { it.fieldDefinitionId } +
                pendingEvents.map { it.fieldDefinitionId } +
                pendingNovels.map { it.fieldDefinitionId }).distinct()
            insertSnapshot(
                TrashSnapshot.TYPE_UNIVERSE_DATA, universe.name,
                UniverseDataSnapshot(
                    universeCode = universe.code,
                    fieldValueEntries = pendingEntries.toList(),
                    orphanCharacterFieldValues = pendingChars.toList(),
                    orphanEventFieldValues = pendingEvents.toList(),
                    orphanNovelFieldValues = pendingNovels.toList(),
                    refs = EntityRefs(
                        universeCode = universe.code,
                        fieldDefs = usedDefIds.mapNotNull { id ->
                            fieldDefRefs[id.toString()]?.let { id.toString() to it }
                        }.toMap(),
                        characters = pendingChars.mapNotNull { v ->
                            characterCodes[v.characterId.toString()]?.let { v.characterId.toString() to it }
                        }.toMap(),
                        events = pendingEvents.mapNotNull { v ->
                            eventCodes[v.eventId.toString()]?.let { v.eventId.toString() to it }
                        }.toMap(),
                        novels = pendingNovels.mapNotNull { v ->
                            novelCodes[v.novelId.toString()]?.let { v.novelId.toString() to it }
                        }.toMap()
                    )
                ),
                emptyList()
            )
            pendingEntries.clear(); pendingChars.clear(); pendingEvents.clear(); pendingNovels.clear()
            budget = 0
        }

        for (e in entries) {
            pendingEntries.add(e); budget += gson.toJson(e).length
            if (budget >= PAYLOAD_BUDGET_CHARS) flush()
        }
        for (v in charValues) {
            pendingChars.add(v); budget += gson.toJson(v).length
            if (budget >= PAYLOAD_BUDGET_CHARS) flush()
        }
        for (v in eventValues) {
            pendingEvents.add(v); budget += gson.toJson(v).length
            if (budget >= PAYLOAD_BUDGET_CHARS) flush()
        }
        for (v in novelValues) {
            pendingNovels.add(v); budget += gson.toJson(v).length
            if (budget >= PAYLOAD_BUDGET_CHARS) flush()
        }
        flush()
    }

    /**
     * 작품 스냅샷 — 삭제 트랜잭션 안에서, 삭제 전에 호출할 것.
     *
     * 소속 캐릭터는 각자 캐릭터 스냅샷으로 남는다. 사건은 살아남고 연결(cross ref)만 끊기므로
     * 그 연결을 담는다 — 단, 같은 작업이 사건도 지운다면 **사건 쪽이** 담는다(사건이 더 나중에
     * 복원되므로 그때 작품이 이미 존재한다).
     */
    suspend fun snapshotNovel(novel: Novel, doomedEventIds: Set<Long>) {
        val linkedEventIds = db.timelineDao().getEventIdsByNovel(novel.id)
            .filter { it !in doomedEventIds }
        val eventCodes = HashMap<String, String>()
        for (id in linkedEventIds) eventCode(id)?.let { eventCodes[id.toString()] = it }
        val characterCodes = HashMap<String, String>()
        collectCharacterCodes(listOfNotNull(novel.imageCharacterId), characterCodes)

        val snapshot = NovelSnapshot(
            novel = novel,
            linkedEventIds = linkedEventIds,
            refs = EntityRefs(
                universeCode = novel.universeId?.let { universeCode(it) },
                events = eventCodes,
                characters = characterCodes
            ),
            // 작품 커스텀 필드값(확-3) — CASCADE로 사라지므로 여기서 담지 않으면 복원해도 빈다.
            fieldValues = db.novelFieldValueDao().getValuesByNovelList(novel.id)
        )
        insertSnapshot(
            TrashSnapshot.TYPE_NOVEL, novel.title, snapshot,
            parseImagePathStrings(novel.imagePaths)
        )
    }

    /**
     * 세력 스냅샷 — 삭제 트랜잭션 안에서, 삭제 전에 호출할 것.
     *
     * @param deleteRelationships 자동 캐릭터 관계도 함께 지우는가. false면 관계는 살아남고
     *   `factionId`만 null이 되므로(FK SET_NULL) 되살릴 것은 '이 세력의 자동 관계'라는 지정뿐이다.
     *   세계관 계단식 삭제는 세력이 FK CASCADE로 죽으므로 이 경로도 false다.
     * @param doomedCharacterIds 같은 작업이 함께 삭제하는 캐릭터 — 그 소속·관계는 캐릭터 스냅샷이 담는다.
     */
    suspend fun snapshotFaction(
        faction: Faction,
        deleteRelationships: Boolean,
        doomedCharacterIds: Set<Long> = emptySet()
    ) {
        val memberships = db.factionMembershipDao().getMembershipsByFactionList(faction.id)
            .filter { it.characterId !in doomedCharacterIds }

        // 세력 간 관계는 **양쪽이 모두 담는다.** 한쪽만 담으면 그쪽이 먼저 복원될 때 상대가
        // 아직 없어 관계가 유실된다 — 세력끼리는 복원 우선순위가 같아 순서를 보장할 수 없다.
        // 양쪽이 담으면 나중에 복원되는 쪽이 실제로 만들고, 먼저 복원되는 쪽은 상대가 같은
        // 작업 안에 있음을 알고 조용히 건너뛴다(factionPlan의 pendingPeerCodes).
        val factionRels = db.factionRelationshipDao().getRelationshipsForFactionList(faction.id)

        // 자동 캐릭터 관계: 한쪽이라도 이번에 지워지는 캐릭터면 그 캐릭터 스냅샷이 담는다.
        val autoRels = db.characterRelationshipDao().getByFactionList(faction.id)
            .filter { it.characterId1 !in doomedCharacterIds && it.characterId2 !in doomedCharacterIds }

        // 관계마다 단건 조회하지 않는다 — 세력 하나에 100명이 소속되면 자동 관계가 약 5천 개라
        // 삭제 트랜잭션이 그대로 멈춘다('받쳐주는 확장성').
        val autoChanges = if (deleteRelationships && autoRels.isNotEmpty()) {
            autoRels.map { it.id }.chunked(IN_CLAUSE_CHUNK).flatMap {
                db.characterRelationshipChangeDao().getChangesForRelationships(it)
            }
        } else {
            emptyList()
        }

        val characterCodes = HashMap<String, String>()
        collectCharacterCodes(
            memberships.map { it.characterId } +
                autoRels.flatMap { listOf(it.characterId1, it.characterId2) },
            characterCodes
        )
        val factionCodes = HashMap<String, String>()
        for (rel in factionRels) {
            val other = if (rel.factionId1 == faction.id) rel.factionId2 else rel.factionId1
            factionCode(other)?.let { factionCodes[other.toString()] = it }
        }
        val eventCodes = HashMap<String, String>()
        for (id in autoChanges.mapNotNull { it.eventId }.distinct()) {
            eventCode(id)?.let { eventCodes[id.toString()] = it }
        }

        val snapshot = FactionSnapshot(
            faction = faction,
            memberships = memberships,
            factionRelationships = factionRels,
            autoRelationships = if (deleteRelationships) autoRels else emptyList(),
            autoRelationshipChanges = autoChanges,
            detachedRelationshipCodes = if (deleteRelationships) emptyList()
            else autoRels.mapNotNull { it.code?.takeIf { c -> c.isNotBlank() } },
            refs = EntityRefs(
                universeCode = universeCode(faction.universeId),
                characters = characterCodes,
                factions = factionCodes,
                events = eventCodes
            )
        )
        insertSnapshot(TrashSnapshot.TYPE_FACTION, faction.name, snapshot, emptyList())
    }

    /**
     * 사건 스냅샷 — 삭제 트랜잭션 안에서, 삭제 전에 호출할 것.
     *
     * 사건 필드값·작품 연결은 FK CASCADE로 죽고, **관계 변화 이력의 사건 연결은 SET_NULL로
     * 조용히 끊긴다** — 이력 자체는 살아남기 때문에 어떤 캐릭터 스냅샷도 그 손실을 담지 못한다.
     *
     * @param doomedCharacterIds 같은 작업이 함께 삭제하는 캐릭터 — 사건 연결은 캐릭터 쪽이 담는다.
     */
    suspend fun snapshotEvent(event: TimelineEvent, doomedCharacterIds: Set<Long> = emptySet()) {
        val fieldValues = db.eventFieldValueDao().getValuesByEventList(event.id)
        val characterIds = db.timelineDao().getCharacterIdsForEvent(event.id)
            .filter { it !in doomedCharacterIds }
        val novelIds = db.timelineDao().getNovelIdsForEvent(event.id)

        // 이 사건을 가리키는 관계 변화 이력 — 단, 그 관계의 캐릭터가 이번에 함께 지워진다면
        // 그 캐릭터 스냅샷이 이력을 통째로 담으므로 여기서 담지 않는다(중복 복원·거짓 경고 방지).
        val changes = db.characterRelationshipChangeDao().getChangesByEventList(event.id)
        val survivingChangeCodes = ArrayList<String>()
        if (changes.isNotEmpty()) {
            val relById = HashMap<Long, CharacterRelationship>()
            for (chunk in changes.map { it.relationshipId }.distinct().chunked(IN_CLAUSE_CHUNK)) {
                db.characterRelationshipDao().getByIds(chunk).forEach { relById[it.id] = it }
            }
            for (change in changes) {
                val rel = relById[change.relationshipId] ?: continue
                if (rel.characterId1 in doomedCharacterIds || rel.characterId2 in doomedCharacterIds) continue
                change.code?.takeIf { it.isNotBlank() }?.let { survivingChangeCodes.add(it) }
            }
        }

        // 출생/사망 사건은 삭제 시 캐릭터의 `__birth`/`__death` 이력까지 함께 지운다
        // (SemanticFieldSyncHelper의 역방향 처리 — FK가 아니라 코드가 지운다).
        // 담지 않으면 사건만 되살아나고 캐릭터의 출생·사망 기록은 영영 사라진다.
        val stateKey = when (event.eventType) {
            TimelineEvent.TYPE_BIRTH -> CharacterStateChange.KEY_BIRTH
            TimelineEvent.TYPE_DEATH -> CharacterStateChange.KEY_DEATH
            else -> null
        }
        val linkedStateChanges = ArrayList<CharacterStateChange>()
        if (stateKey != null) {
            // 함께 지워지는 캐릭터의 이력은 그 캐릭터 스냅샷이 통째로 담으므로 제외한다.
            for (charId in characterIds) {
                linkedStateChanges.addAll(
                    db.characterStateChangeDao().getChangesByField(charId, stateKey)
                )
            }
        }

        val characterCodes = HashMap<String, String>()
        collectCharacterCodes(characterIds + linkedStateChanges.map { it.characterId }, characterCodes)
        val novelCodes = HashMap<String, String>()
        for (id in novelIds) novelCode(id)?.let { novelCodes[id.toString()] = it }
        val fieldDefs = HashMap<String, FieldDefRef>()
        for (id in fieldValues.map { it.fieldDefinitionId }.distinct()) {
            fieldDefRef(id)?.let { fieldDefs[id.toString()] = it }
        }

        val snapshot = EventSnapshot(
            event = event,
            fieldValues = fieldValues,
            characterIds = characterIds,
            novelIds = novelIds,
            relationshipChangeCodes = survivingChangeCodes,
            linkedStateChanges = linkedStateChanges,
            refs = EntityRefs(
                universeCode = event.universeId?.let { universeCode(it) },
                characters = characterCodes,
                novels = novelCodes,
                fieldDefs = fieldDefs
            )
        )
        insertSnapshot(TrashSnapshot.TYPE_EVENT, event.description, snapshot, emptyList())
    }

    /**
     * 상태변화 이력 스냅샷 — 삭제 트랜잭션 안에서, 삭제 전에 호출할 것.
     *
     * **이력만 개별로 지우는 경로 전용이다.** 캐릭터 삭제는 [snapshotCharacter]가, 출생·사망
     * 사건 삭제는 [snapshotEvent]가 이미 이력을 담으므로 그 경로에서 이것을 함께 부르면
     * 같은 이력이 두 벌 남아 복원이 중복되고 거짓 경고가 뜬다(스냅샷은 겹치지 않고 이어붙는다).
     */
    suspend fun snapshotStateChange(change: CharacterStateChange) {
        val characterCodes = HashMap<String, String>()
        collectCharacterCodes(listOf(change.characterId), characterCodes)
        val snapshot = StateChangeSnapshot(
            change = change,
            characterCode = characterCodes[change.characterId.toString()],
            refs = EntityRefs(characters = characterCodes)
        )
        // 목록 이름은 이력의 내용 그대로 — 삭제 직전 다이얼로그가 보여 준 문구와 같은 형태라
        // 사용자가 휴지통에서 "방금 지운 그것"을 알아볼 수 있다.
        insertSnapshot(
            TrashSnapshot.TYPE_STATE_CHANGE,
            "${change.fieldKey} → ${change.newValue}",
            snapshot,
            emptyList()
        )
    }

    /**
     * 등급 체계 스냅샷 — 삭제 트랜잭션 안에서, 참조 필드를 **강등하기 전에** 호출할 것.
     *
     * **체계만 개별로 지우는 경로 전용이다.** 세계관 삭제는 [snapshotUniverse]가 체계를 담으므로
     * 그 경로에서 함께 부르면 같은 체계가 두 벌 남는다(스냅샷은 겹치지 않고 이어붙는다).
     *
     * @param referencingFields 삭제 시점에 이 체계를 참조하던 필드 — 강등 후에는 config에서
     *   참조가 사라져 알 수 없게 되므로, 지금 자연키로 담아야 복원이 다시 이을 수 있다(R-1).
     */
    suspend fun snapshotGradeSystem(system: GradeSystem, referencingFields: List<FieldDefinition>) {
        val uCode = universeCode(system.universeId)
        val snapshot = GradeSystemSnapshot(
            gradeSystem = system,
            referencingFields = referencingFields.map {
                FieldDefRef(universeCode = uCode, entityType = it.entityType, key = it.key)
            },
            universeCode = uCode,
            refs = EntityRefs(universeCode = uCode)
        )
        insertSnapshot(TrashSnapshot.TYPE_GRADE_SYSTEM, system.name, snapshot, emptyList())
    }

    private suspend fun insertSnapshot(
        entityType: String,
        entityName: String,
        payload: Any,
        imagePaths: List<String>,
        kind: String = operationKind
    ) {
        trashDao.insert(
            TrashSnapshot(
                entityType = entityType,
                entityName = entityName,
                payload = gson.toJson(payload),
                imagePaths = gson.toJson(imagePaths),
                operationId = operationId,
                operationKind = kind
            )
        )
        createdAny = true
    }

    /** payload가 담은 모든 DB id에 대한 안정 식별자를 수집한다 (N1). */
    private suspend fun buildRefs(
        character: Character,
        fieldValues: List<CharacterFieldValue>,
        relationships: List<CharacterRelationship>,
        relationshipChanges: List<CharacterRelationshipChange>,
        factionMemberships: List<FactionMembership>,
        eventIds: List<Long>
    ): SnapshotRefs {
        val novel = character.novelId?.let { db.novelDao().getNovelById(it) }

        val fieldDefs = HashMap<String, FieldDefRef>()
        for (id in fieldValues.map { it.fieldDefinitionId }.distinct()) {
            fieldDefRef(id)?.let { fieldDefs[id.toString()] = it }
        }

        val characterCodes = HashMap<String, String>()
        collectCharacterCodes(
            relationships.flatMap { listOf(it.characterId1, it.characterId2) }.filter { it != character.id },
            characterCodes
        )

        val factionCodes = HashMap<String, String>()
        val factionIds = (relationships.mapNotNull { it.factionId } + factionMemberships.map { it.factionId })
            .distinct()
        for (id in factionIds) {
            factionCode(id)?.let { factionCodes[id.toString()] = it }
        }

        val eventCodes = HashMap<String, String>()
        val allEventIds = (eventIds + relationshipChanges.mapNotNull { it.eventId }).distinct()
        for (id in allEventIds) {
            eventCode(id)?.let { eventCodes[id.toString()] = it }
        }

        return SnapshotRefs(
            version = SnapshotRefs.VERSION,
            novelCode = novel?.code?.takeIf { it.isNotBlank() },
            universeCode = novel?.universeId?.let { universeCode(it) },
            fieldDefs = fieldDefs,
            characters = characterCodes,
            factions = factionCodes,
            events = eventCodes
        )
    }

    /** 캐시에 없는 것만 한 번에 조회한다 — 계단식 삭제에서 참조 대상은 크게 겹친다. */
    private suspend fun collectCharacterCodes(ids: Collection<Long>, into: MutableMap<String, String>) {
        val distinct = ids.distinct()
        val uncached = distinct.filter { it !in characterCodeCache }
        for (chunk in uncached.chunked(IN_CLAUSE_CHUNK)) {
            val fetched = db.characterDao().getCharactersByIds(chunk).associateBy { it.id }
            for (id in chunk) characterCodeCache[id] = fetched[id]?.code
        }
        for (id in distinct) {
            characterCodeCache[id]?.takeIf { it.isNotBlank() }?.let { into[id.toString()] = it }
        }
    }

    private suspend fun universeCode(universeId: Long): String? =
        universeCodeCache.getOrPut(universeId) {
            db.universeDao().getUniverseById(universeId)?.code?.takeIf { it.isNotBlank() }
        }

    private suspend fun novelCode(novelId: Long): String? {
        if (novelCodeCache.containsKey(novelId)) return novelCodeCache[novelId]
        val code = db.novelDao().getNovelById(novelId)?.code?.takeIf { it.isNotBlank() }
        novelCodeCache[novelId] = code
        return code
    }

    private suspend fun factionCode(factionId: Long): String? {
        if (factionCodeCache.containsKey(factionId)) return factionCodeCache[factionId]
        val code = db.factionDao().getById(factionId)?.code?.takeIf { it.isNotBlank() }
        factionCodeCache[factionId] = code
        return code
    }

    private suspend fun eventCode(eventId: Long): String? {
        if (eventCodeCache.containsKey(eventId)) return eventCodeCache[eventId]
        val code = db.timelineDao().getEventById(eventId)?.code?.takeIf { it.isNotBlank() }
        eventCodeCache[eventId] = code
        return code
    }

    private suspend fun fieldDefRef(fieldDefinitionId: Long): FieldDefRef? {
        if (fieldDefRefCache.containsKey(fieldDefinitionId)) return fieldDefRefCache[fieldDefinitionId]
        val fd = db.fieldDefinitionDao().getFieldById(fieldDefinitionId)
        val ref = fd?.let {
            val uCode = universeCode(it.universeId) ?: return@let null
            FieldDefRef(universeCode = uCode, entityType = it.entityType, key = it.key)
        }
        fieldDefRefCache[fieldDefinitionId] = ref
        return ref
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 타입별 진입점을 하나로 모은다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 복원 전 미리보기. 스냅샷을 전혀 건드리지 않는다.
     *
     * @param pendingCodes 같은 작업으로 **함께 복원될 예정**인 엔티티의 코드. 지금 DB에 없어도
     *   복원 순서상 그때는 존재하므로 유실로 세지 않는다 — 넣지 않으면 작업 전체 복원 미리보기가
     *   "세력을 못 살린다"처럼 사실과 다른 경고를 낸다.
     */
    suspend fun previewRestore(
        snapshotId: Long,
        pendingCodes: Set<String> = emptySet(),
        peerCodes: PeerCodes = PeerCodes()
    ): RestorePreview? = withContext(Dispatchers.IO) {
        val snap = trashDao.getById(snapshotId) ?: return@withContext null
        when (snap.entityType) {
            TrashSnapshot.TYPE_CHARACTER ->
                characterPlan(snap, pendingCodes, pendingPeerCodes = peerCodes.characters)?.toPreview()
            TrashSnapshot.TYPE_UNIVERSE -> universePlan(snap, pendingCodes)?.toPreview()
            TrashSnapshot.TYPE_UNIVERSE_DATA -> universeDataPlan(snap, pendingCodes)?.toPreview()
            TrashSnapshot.TYPE_NOVEL -> novelPlan(snap, pendingCodes)?.toPreview()
            TrashSnapshot.TYPE_FACTION ->
                factionPlan(snap, pendingCodes, pendingPeerCodes = peerCodes.factions)?.toPreview()
            TrashSnapshot.TYPE_EVENT -> eventPlan(snap, pendingCodes)?.toPreview()
            TrashSnapshot.TYPE_STATE_CHANGE -> stateChangePlan(snap, pendingCodes)?.toPreview()
            TrashSnapshot.TYPE_GRADE_SYSTEM -> gradeSystemPlan(snap, pendingCodes)?.toPreview()
            else -> null
        }
    }

    /**
     * 같은 작업 안에 있는 **같은 복원 우선순위** 엔티티의 코드.
     *
     * 세력↔세력, 캐릭터↔캐릭터를 잇는 링크는 양쪽 스냅샷이 모두 담는다(우선순위가 같아
     * 어느 쪽이 먼저 복원될지 정할 수 없기 때문이다). 먼저 복원되는 쪽이 상대를 못 찾는 것은
     * 유실이 아니라 순서일 뿐이므로, 그 사실을 계획 단계에 알려 유령 유실을 막는다.
     */
    data class PeerCodes(
        val characters: Set<String> = emptySet(),
        val factions: Set<String> = emptySet()
    )



    /**
     * 스냅샷 하나를 복원한다. 성공하면 스냅샷을 소각한다.
     * 되살릴 수 없는 사유([RestorePreview.blocker])가 있으면 **아무것도 하지 않고 null**을 돌려준다 —
     * 스냅샷이 남아야 사용자가 상위 엔티티를 먼저 복원한 뒤 다시 시도할 수 있다(R-4).
     */
    suspend fun restoreSnapshot(
        snapshotId: Long,
        consentedRevert: Boolean = false
    ): RestoreResult? = withContext(Dispatchers.IO) {
        val snap = trashDao.getById(snapshotId) ?: return@withContext null
        var result: RestoreResult? = null
        var deferred: DeferredImageLink? = null
        var restoredCharacterId = -1L

        db.withTransaction {
            // 트랜잭션 안에서 다시 해석한다 — 미리보기 이후 DB가 바뀌었을 수 있다.
            when (snap.entityType) {
                TrashSnapshot.TYPE_CHARACTER -> {
                    val plan = characterPlan(snap) ?: return@withTransaction
                    val applied = applyCharacter(plan, null, consentedRevert)
                    result = applied.first
                    restoredCharacterId = applied.second
                }
                TrashSnapshot.TYPE_UNIVERSE -> {
                    val plan = universePlan(snap) ?: return@withTransaction
                    val applied = applyUniverse(plan, null)
                    result = applied.first
                    deferred = applied.second
                }
                TrashSnapshot.TYPE_UNIVERSE_DATA -> {
                    val plan = universeDataPlan(snap) ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyUniverseData(plan)
                }
                TrashSnapshot.TYPE_NOVEL -> {
                    val plan = novelPlan(snap) ?: return@withTransaction
                    val applied = applyNovel(plan, null)
                    result = applied.first
                    deferred = applied.second
                }
                TrashSnapshot.TYPE_FACTION -> {
                    val plan = factionPlan(snap) ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyFaction(plan, null)
                }
                TrashSnapshot.TYPE_EVENT -> {
                    val plan = eventPlan(snap) ?: return@withTransaction
                    result = applyEvent(plan, null)
                }
                TrashSnapshot.TYPE_STATE_CHANGE -> {
                    val plan = stateChangePlan(snap) ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyStateChange(plan)
                }
                TrashSnapshot.TYPE_GRADE_SYSTEM -> {
                    val plan = gradeSystemPlan(snap) ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyGradeSystem(plan)
                }
                else -> return@withTransaction
            }
        }

        var restored = result ?: return@withContext null

        // 복원 완료 — 스냅샷 제거 (이미지 파일은 복원된 엔티티가 소유)
        trashDao.deleteById(snapshotId)

        // 세계관을 되살렸으면 자동 수확분 라이브러리를 다시 만든다 — 스냅샷은 큐레이션된
        // 엔트리만 담으므로, 나머지는 실제 필드값에서 재생성되어야 원래 상태가 된다.
        if (snap.entityType == TrashSnapshot.TYPE_UNIVERSE) {
            runCatching {
                val code = parse(snap, UniverseSnapshot::class.java)?.universe?.code
                val restoredUniverseId = code?.let { db.universeDao().getUniverseByCode(it)?.id }
                if (restoredUniverseId != null) {
                    FieldValueLibraryRepository(db).harvestUniverses(setOf(restoredUniverseId))
                }
            }
        }

        // 대표 이미지 연동은 대상이 아직 복원되지 않았을 수 있어 마지막에 해석한다.
        deferred?.let { link ->
            val lost = resolveDeferredImageLinks(listOf(link), null)
            if (lost > 0) {
                restored = restored.copy(losses = restored.losses.copy(imageLinks = lost))
            }
        }

        // 복원도 필드값 쓰기 경로 — 라이브러리에서 정리된 뒤 복원된 값이 다시 보이게 수확 (검토 A6)
        if (restoredCharacterId != -1L) {
            FieldValueLibraryRepository(db).harvestForCharacter(restoredCharacterId)
        }

        restored
    }

    /**
     * 삭제 작업 하나를 통째로 복원한다 (B-1/B-14).
     *
     * 세계관 → 작품 → 세력 → 사건 → 캐릭터 순으로 복원하므로 하위 엔티티가 붙을 자리가
     * 먼저 생기고, 참조는 코드로 다시 이어진다(R-1). 항목별 트랜잭션이라 하나가 실패해도
     * 나머지는 되살아나고, 실패한 항목의 스냅샷은 그대로 남는다.
     */
    suspend fun restoreOperation(
        opKey: String,
        editBackup: Boolean = false
    ): OperationRestoreResult = withContext(Dispatchers.IO) {
        val (opId, legacyId) = operationLookup(opKey)
        val items = trashDao.getByOperation(opId, legacyId)
            .filter { it.isEditBackup == editBackup }
            .sortedWith(compareBy({ TrashSnapshot.restorePriority(it.entityType) }, { it.id }))
        if (items.isEmpty()) return@withContext OperationRestoreResult()

        val restored = ArrayList<RestoreResult>(items.size)
        val failed = ArrayList<String>()
        val deferredLinks = ArrayList<DeferredImageLink>()
        // 이 작업이 code를 재발급한 대상을 뒤 항목이 찾을 수 있게 하나를 공유한다.
        val session = RestoreSession()
        // 같은 우선순위끼리 잇는 링크(세력↔세력, 캐릭터↔캐릭터)는 양쪽 스냅샷이 모두 담는다.
        // 먼저 복원되는 쪽이 상대를 못 찾는 것은 유실이 아니므로 미리 알려 준다.
        val peerCodes = collectOperationCodes(items).peers
        val harvestTargets = ArrayList<Long>()

        for (item in items) {
            val outcome = try {
                restoreOne(item, deferredLinks, session, peerCodes, harvestTargets)
            } catch (_: Exception) {
                null
            }
            if (outcome == null) failed.add(item.entityName) else restored.add(outcome)
        }

        // 값 라이브러리 수확은 묶음이 끝난 뒤 세계관 단위로 한 번 — 캐릭터마다 부르면
        // 전 필드 정의와 라이브러리를 매번 다시 읽어 항목 수의 제곱이 된다.
        // 복원된 세계관도 대상이다 — 스냅샷은 큐레이션된 라이브러리 엔트리만 담으므로
        // 자동 수확분은 여기서 실제 필드값으로부터 다시 만들어진다.
        val restoredUniverseIds = items
            .filter { it.entityType == TrashSnapshot.TYPE_UNIVERSE }
            .mapNotNull { item ->
                parse(item, UniverseSnapshot::class.java)?.universe?.code
                    ?.let { session.lookup(TrashSnapshot.TYPE_UNIVERSE, it) }
            }.toSet()
        if (harvestTargets.isNotEmpty() || restoredUniverseIds.isNotEmpty()) {
            runCatching { harvestRestoredCharacters(harvestTargets, restoredUniverseIds) }
        }

        val lostImageLinks = resolveDeferredImageLinks(deferredLinks, session)
        val withImages = if (lostImageLinks > 0 && restored.isNotEmpty()) {
            restored.toMutableList().also { list ->
                val first = list[0]
                list[0] = first.copy(losses = first.losses.copy(imageLinks = lostImageLinks))
            }
        } else {
            restored
        }
        OperationRestoreResult(withImages, failed)
    }

    /**
     * 작업 전체 미리보기 — 같은 작업이 되살릴 코드는 유실로 세지 않는다.
     *
     * **IO로 넘긴다.** 항목마다 payload를 두 번 파싱하므로(코드 수집 + 계획 수립) 수백 항목짜리
     * 세계관 삭제에서는 UI 스레드에서 돌 경우 확인 다이얼로그가 뜨기 전에 화면이 멈춘다.
     */
    suspend fun previewOperation(
        opKey: String,
        editBackup: Boolean = false
    ): List<RestorePreview> = withContext(Dispatchers.IO) {
        val (opId, legacyId) = operationLookup(opKey)
        val items = trashDao.getByOperation(opId, legacyId)
            .filter { it.isEditBackup == editBackup }
            .sortedWith(compareBy({ TrashSnapshot.restorePriority(it.entityType) }, { it.id }))
        val codes = collectOperationCodes(items)
        items.mapNotNull { previewRestore(it.id, codes.pending, codes.peers) }
    }

    /**
     * 이 작업이 복원하면 존재하게 될 엔티티 코드 — 전체와, 같은 우선순위 동료끼리의 것.
     *
     * payload를 파싱해 얻는다(목록 행의 entityName만으로는 코드를 알 수 없다).
     * **한 번만 파싱한다** — 종전에는 pending/peer 수집이 각각 전량을 훑어, 수백 항목짜리
     * 세계관 삭제에서 같은 payload를 세 번 파싱했다.
     */
    private data class OperationCodes(val pending: Set<String>, val peers: PeerCodes)

    private fun collectOperationCodes(items: List<TrashSnapshot>): OperationCodes {
        val pending = HashSet<String>()
        val characters = HashSet<String>()
        val factions = HashSet<String>()
        for (item in items) {
            val code = when (item.entityType) {
                TrashSnapshot.TYPE_CHARACTER ->
                    parse(item, CharacterSnapshot::class.java)?.character?.code
                        ?.also { if (it.isNotBlank()) characters.add(it) }
                TrashSnapshot.TYPE_UNIVERSE -> parse(item, UniverseSnapshot::class.java)?.universe?.code
                TrashSnapshot.TYPE_NOVEL -> parse(item, NovelSnapshot::class.java)?.novel?.code
                TrashSnapshot.TYPE_FACTION ->
                    parse(item, FactionSnapshot::class.java)?.faction?.code
                        ?.also { if (it.isNotBlank()) factions.add(it) }
                TrashSnapshot.TYPE_EVENT -> parse(item, EventSnapshot::class.java)?.event?.code
                TrashSnapshot.TYPE_GRADE_SYSTEM ->
                    parse(item, GradeSystemSnapshot::class.java)?.gradeSystem?.code
                else -> null
            }
            code?.takeIf { it.isNotBlank() }?.let { pending.add(it) }
        }
        return OperationCodes(pending, PeerCodes(characters, factions))
    }

    private suspend fun restoreOne(
        item: TrashSnapshot,
        deferredLinks: MutableList<DeferredImageLink>,
        session: RestoreSession,
        peerCodes: PeerCodes = PeerCodes(),
        /** null이 아니면 값 라이브러리 수확을 **미룬다** — 복원된 캐릭터 id를 여기 모은다. */
        deferredHarvest: MutableList<Long>? = null
    ): RestoreResult? {
        var result: RestoreResult? = null
        var restoredCharacterId = -1L
        db.withTransaction {
            when (item.entityType) {
                TrashSnapshot.TYPE_CHARACTER -> {
                    val plan = characterPlan(item, session = session, pendingPeerCodes = peerCodes.characters)
                        ?: return@withTransaction
                    val applied = applyCharacter(plan, session)
                    result = applied.first
                    restoredCharacterId = applied.second
                }
                TrashSnapshot.TYPE_UNIVERSE -> {
                    val plan = universePlan(item, session = session) ?: return@withTransaction
                    val applied = applyUniverse(plan, session)
                    result = applied.first
                    applied.second?.let { deferredLinks.add(it) }
                }
                TrashSnapshot.TYPE_UNIVERSE_DATA -> {
                    val plan = universeDataPlan(item, session = session) ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyUniverseData(plan)
                }
                TrashSnapshot.TYPE_NOVEL -> {
                    val plan = novelPlan(item, session = session) ?: return@withTransaction
                    val applied = applyNovel(plan, session)
                    result = applied.first
                    applied.second?.let { deferredLinks.add(it) }
                }
                TrashSnapshot.TYPE_FACTION -> {
                    val plan = factionPlan(item, session = session, pendingPeerCodes = peerCodes.factions)
                        ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyFaction(plan, session)
                }
                TrashSnapshot.TYPE_EVENT -> {
                    val plan = eventPlan(item, session = session) ?: return@withTransaction
                    result = applyEvent(plan, session)
                }
                TrashSnapshot.TYPE_STATE_CHANGE -> {
                    val plan = stateChangePlan(item, session = session) ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyStateChange(plan)
                }
                TrashSnapshot.TYPE_GRADE_SYSTEM -> {
                    val plan = gradeSystemPlan(item, session = session) ?: return@withTransaction
                    if (plan.blocker != null) return@withTransaction
                    result = applyGradeSystem(plan)
                }
                else -> return@withTransaction
            }
        }
        val out = result ?: return null
        trashDao.deleteById(item.id)
        if (restoredCharacterId != -1L) {
            // 캐릭터마다 수확하면 전 필드 정의·값 라이브러리를 매번 다시 읽어 묶음 크기의
            // 제곱이 된다. 작업 전체 복원은 끝에 한 번만 수확한다.
            if (deferredHarvest != null) deferredHarvest.add(restoredCharacterId)
            else FieldValueLibraryRepository(db).harvestForCharacter(restoredCharacterId)
        }
        return out
    }

    /**
     * 복원된 캐릭터들의 값 라이브러리 수확 — **세계관 단위로 묶어** 한 번씩만 부른다.
     * (`harvestForCharacter`를 캐릭터마다 부르면 전 필드 정의·라이브러리를 매번 다시 읽는다)
     */
    private suspend fun harvestRestoredCharacters(characterIds: List<Long>, extraUniverseIds: Set<Long>) {
        val library = FieldValueLibraryRepository(db)
        val universeIds = HashSet<Long>(extraUniverseIds)
        for (chunk in characterIds.distinct().chunked(IN_CLAUSE_CHUNK)) {
            for (character in db.characterDao().getCharactersByIds(chunk)) {
                val universeId = character.novelId?.let { db.novelDao().getNovelById(it)?.universeId }
                if (universeId != null) universeIds.add(universeId)
            }
        }
        if (universeIds.isNotEmpty()) library.harvestUniverses(universeIds)
    }

    /**
     * 세계관·작품의 '대표 이미지 캐릭터/작품' 연동.
     *
     * 그 대상은 같은 작업으로 **나중에** 복원되므로 본체 복원 시점에는 아직 없다.
     * 그래서 전부 복원한 뒤 마지막에 한 번 더 해석한다(엑포트/임포트의 deferred 처리와 같은 형태).
     * @return 끝내 찾지 못해 연동이 해제된 건수
     */
    private data class DeferredImageLink(
        val entityType: String,
        val entityId: Long,
        val characterCode: String?,
        val novelCode: String?
    )

    private suspend fun resolveDeferredImageLinks(links: List<DeferredImageLink>, session: RestoreSession?): Int {
        if (links.isEmpty()) return 0
        var lost = 0
        for (link in links) {
            val charId = link.characterCode?.let {
                session?.lookup(TrashSnapshot.TYPE_CHARACTER, it) ?: db.characterDao().getCharacterByCode(it)?.id
            }
            val novelId = link.novelCode?.let {
                session?.lookup(TrashSnapshot.TYPE_NOVEL, it) ?: db.novelDao().getNovelByCode(it)?.id
            }
            if (link.characterCode != null && charId == null) lost++
            if (link.novelCode != null && novelId == null) lost++
            if (charId == null && novelId == null) continue
            when (link.entityType) {
                TrashSnapshot.TYPE_UNIVERSE -> {
                    val universe = db.universeDao().getUniverseById(link.entityId) ?: continue
                    db.universeDao().update(
                        universe.copy(
                            imageCharacterId = charId ?: universe.imageCharacterId,
                            imageNovelId = novelId ?: universe.imageNovelId
                        )
                    )
                }
                TrashSnapshot.TYPE_NOVEL -> {
                    val novel = db.novelDao().getNovelById(link.entityId) ?: continue
                    db.novelDao().update(novel.copy(imageCharacterId = charId ?: novel.imageCharacterId))
                }
            }
        }
        return lost
    }

    private fun <T> parse(snap: TrashSnapshot, type: Class<T>): T? = try {
        gson.fromJson(snap.payload, type)
    } catch (_: Exception) {
        null
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 캐릭터
    // ──────────────────────────────────────────────────────────────────────

    /** 복원 계획 — 미리보기와 실제 복원이 **같은 해석 결과**를 쓰도록 한 곳에서 만든다. */
    private data class CharacterPlan(
        val data: CharacterSnapshot,
        val novelId: Long?,
        /** 해석된 fieldDefinitionId로 치환된 필드값 */
        val fieldValues: List<CharacterFieldValue>,
        val relationships: List<PlannedRelationship>,
        val memberships: List<FactionMembership>,
        val eventIds: List<Long>,
        val losses: RestoreLossCounts,
        val relinkedByCode: Int,
        /** [RestoreTally.PENDING_ID] 자리표시자가 섞인 계획 — 미리보기 전용이며 쓰기에 쓰면 안 된다. */
        val previewOnly: Boolean,
        val legacyPayload: Boolean,
        val duplicatesLivingCharacter: Boolean,
        /** 이 복원이 무슨 일을 하는가 (B-2/B-21). */
        val mode: CharacterRestoreMode = CharacterRestoreMode.REVIVE,
        /** [CharacterRestoreMode.REVERT]일 때 덮어쓸 살아 있는 캐릭터 — **code로만** 찾은 것이다. */
        val revertTargetId: Long? = null,
        /** 되돌리기가 교체할 갈래 ([RestoreModes]의 SCOPE_*). 되돌리기가 아니면 비어 있다. */
        val revertScope: Set<String> = emptySet()
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_CHARACTER,
            entityName = data.character.name,
            losses = losses,
            relinkedByCode = relinkedByCode,
            duplicatesLivingEntity = duplicatesLivingCharacter,
            legacyPayload = legacyPayload,
            revertsInPlace = mode == CharacterRestoreMode.REVERT,
            revertScope = revertScope
        )
    }

    /**
     * 상대 캐릭터까지 해석이 끝난 관계.
     *
     * 자기 자신이 어느 슬롯인지를 **id 비교가 아니라 플래그로** 들고 다닌다 —
     * 옛 id가 다른 엔티티에 재사용된 상황에서 `characterId == data.character.id` 비교는
     * 상대 캐릭터를 복원 대상으로 오인할 수 있다(이 클래스가 막으려는 바로 그 오배정).
     */
    private data class PlannedRelationship(
        val relationship: CharacterRelationship,
        val selfIsFirst: Boolean,
        val selfIsSecond: Boolean,
        val changes: List<CharacterRelationshipChange>
    )

    /**
     * payload의 참조를 현행 DB에서 다시 찾아 복원 계획을 만든다.
     * 해석은 [SnapshotRefResolver]가, 조회는 여기가 담당한다 — 사다리 자체는 순수 로직이라
     * Android 없이 단위 테스트로 고정된다.
     */
    private suspend fun characterPlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null,
        pendingPeerCodes: Set<String> = emptySet()
    ): CharacterPlan? {
        val data = parse(snap, CharacterSnapshot::class.java) ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.character == null) return null   // Gson은 손상된 payload에 null을 주입할 수 있다

        val refs = data.refs
        val tally = RestoreTally(legacy = refs == null, pendingCodes = pendingCodes)

        // ── 작품 ──
        val novelIndex = buildIndex(
            oldIds = listOfNotNull(data.character.novelId),
            codes = listOfNotNull(refs?.novelCode),
            fetchByIds = { db.novelDao().getNovelsByIds(it) },
            fetchByCodes = { db.novelDao().getNovelsByCodes(it) },
            idOf = { it.id },
            codeOf = { it.code },
            entityType = TrashSnapshot.TYPE_NOVEL,
            session = session
        )
        var novelCleared = false
        val novelId = data.character.novelId?.let { old ->
            val res = tally.note(
                SnapshotRefResolver.resolveByCode(
                    old, refs?.novelCode, novelIndex.codeById, novelIndex.idByCode, novelIndex.liveIds
                ),
                refs?.novelCode
            )
            if (!res.found) novelCleared = true
            res.id
        }

        // ── 필드 정의 (code가 없어 자연키로 해석) ──
        val fieldRefs = refs?.fieldDefs.orEmpty()
        val fieldIndex = buildFieldDefIndex(
            oldIds = data.fieldValues.map { it.fieldDefinitionId }.distinct(),
            refs = fieldRefs.values,
            session = session
        )
        val resolvedFieldValues = ArrayList<CharacterFieldValue>(data.fieldValues.size)
        val usedFieldDefIds = HashSet<Long>()
        var skippedFieldValues = 0
        var mergedFieldValues = 0
        for (v in data.fieldValues) {
            val fieldRef = fieldRefs[v.fieldDefinitionId.toString()]
            val res = tally.noteFieldDef(
                SnapshotRefResolver.resolveFieldDef(
                    v.fieldDefinitionId,
                    fieldRef,
                    fieldIndex.naturalById,
                    fieldIndex.idByNatural
                ),
                fieldRef
            )
            val newId = res.id
            if (newId == null) {
                skippedFieldValues++
                continue
            }
            // (characterId, fieldDefinitionId) 유니크 — 서로 다른 옛 id가 같은 정의로 수렴하면
            // insertAll(ABORT)이 복원 전체를 실패시키므로 여기서 접는다. 정의는 멀쩡히 살아
            // 있으므로 '찾을 수 없음'과 같은 칸에 넣지 않는다(그러면 거짓 사유가 된다).
            // 보류 자리표시자(PENDING_ID)는 서로 다른 정의가 전부 같은 0이라 수렴처럼 보인다 —
            // 여기서 세면 거짓 경고의 칸만 '못 찾음'에서 '합쳐짐'으로 옮겨 갈 뿐이다.
            if (newId != RestoreTally.PENDING_ID && !usedFieldDefIds.add(newId)) {
                mergedFieldValues++
                continue
            }
            resolvedFieldValues.add(v.copy(fieldDefinitionId = newId))
        }

        // ── 관계 상대 캐릭터 ──
        val otherIds = data.relationships
            .flatMap { listOf(it.characterId1, it.characterId2) }
            .filter { it != data.character.id }
            .distinct()
        val charIndex = characterIndex(otherIds, refs?.characters?.values.orEmpty(), session)
        val factionIds = (data.relationships.mapNotNull { it.factionId } +
            data.factionMemberships.map { it.factionId }).distinct()
        val factionIndex = factionIndex(factionIds, refs?.factions?.values.orEmpty(), session)
        val eventIdsAll = (data.eventIds + data.relationshipChanges.mapNotNull { it.eventId }).distinct()
        val eventIndex = eventIndex(eventIdsAll, refs?.events?.values.orEmpty(), session)

        fun resolveCharacter(oldId: Long) = tally.note(
            SnapshotRefResolver.resolveByCode(
                oldId, refs?.characters?.get(oldId.toString()),
                charIndex.codeById, charIndex.idByCode, charIndex.liveIds
            ),
            refs?.characters?.get(oldId.toString())
        )

        fun resolveFaction(oldId: Long) = tally.note(
            SnapshotRefResolver.resolveByCode(
                oldId, refs?.factions?.get(oldId.toString()),
                factionIndex.codeById, factionIndex.idByCode, factionIndex.liveIds
            ),
            refs?.factions?.get(oldId.toString())
        )

        fun resolveEvent(oldId: Long) = tally.note(
            SnapshotRefResolver.resolveByCode(
                oldId, refs?.events?.get(oldId.toString()),
                eventIndex.codeById, eventIndex.idByCode, eventIndex.liveIds
            ),
            refs?.events?.get(oldId.toString())
        )

        val plannedRelationships = ArrayList<PlannedRelationship>(data.relationships.size)
        var skippedRelationships = 0
        var skippedRelationshipChanges = 0
        var clearedRelationshipFactions = 0
        var clearedChangeEvents = 0
        for (rel in data.relationships) {
            val changes = data.relationshipChanges.filter { it.relationshipId == rel.id }
            val selfIsFirst = rel.characterId1 == data.character.id
            val selfIsSecond = rel.characterId2 == data.character.id
            if (!selfIsFirst && !selfIsSecond) {
                // 어느 슬롯도 이 캐릭터가 아니다 — payload가 손상됐거나 남의 관계가 섞였다.
                // 복원하면 무관한 두 캐릭터를 잇게 되므로 생략하고 집계한다.
                skippedRelationships++
                skippedRelationshipChanges += changes.size
                continue
            }
            // 자기 자신과의 관계(양쪽 모두 자기)면 상대 해석이 필요 없다.
            var otherNew: Long? = null
            if (!(selfIsFirst && selfIsSecond)) {
                val otherOld = if (selfIsFirst) rel.characterId2 else rel.characterId1
                val res = resolveCharacter(otherOld)
                if (res.id == null) {
                    // 상대 캐릭터가 **같은 작업 안에** 있으면 유실이 아니다 — 관계는 양쪽
                    // 스냅샷이 모두 담으므로 나중에 복원되는 쪽이 실제로 만든다.
                    // 여기서 세면 먼저 복원되는 쪽마다 유령 유실이 쌓인다.
                    val otherCode = refs?.characters?.get(otherOld.toString())
                    if (otherCode != null && otherCode in pendingPeerCodes) continue
                    skippedRelationships++
                    // 관계가 사라지면 그 이력도 함께 사라진다 — 종전에는 집계조차 없었다.
                    skippedRelationshipChanges += changes.size
                    continue
                }
                otherNew = res.id
            }
            val newFactionId = rel.factionId?.let { old ->
                val res = resolveFaction(old)
                if (!res.found) clearedRelationshipFactions++
                res.id
            }
            val remapped = rel.copy(
                characterId1 = if (selfIsFirst) rel.characterId1 else otherNew!!,
                characterId2 = if (selfIsSecond) rel.characterId2 else otherNew!!,
                factionId = newFactionId
            )
            val remappedChanges = changes.map { change ->
                val newEventId = change.eventId?.let { old ->
                    val res = resolveEvent(old)
                    if (!res.found) clearedChangeEvents++
                    res.id
                }
                change.copy(eventId = newEventId)
            }
            plannedRelationships.add(
                PlannedRelationship(remapped, selfIsFirst, selfIsSecond, remappedChanges)
            )
        }

        val plannedMemberships = ArrayList<FactionMembership>(data.factionMemberships.size)
        // faction_memberships에는 유니크 제약이 없다(재가입 이력 보존). 서로 다른 옛 세력 id가
        // 같은 세력으로 수렴하면 실패가 아니라 **조용한 중복 행**이 되므로 여기서 접는다.
        // 접는 키는 임포터가 쓰는 자연키와 같다(ExcelImportService의 세력 소속 매칭 사다리).
        val seenMemberships = HashSet<List<Any?>>()
        var skippedMemberships = 0
        for (m in data.factionMemberships) {
            val res = resolveFaction(m.factionId)
            val newId = res.id
            if (newId == null) {
                skippedMemberships++
                continue
            }
            if (!seenMemberships.add(listOf(newId, m.joinYear, m.leaveYear, m.leaveType))) continue
            plannedMemberships.add(m.copy(factionId = newId))
        }

        val plannedEvents = LinkedHashSet<Long>()
        var skippedEvents = 0
        for (old in data.eventIds) {
            val res = resolveEvent(old)
            val newId = res.id
            // 서로 다른 옛 id가 같은 사건으로 수렴하는 것은 유실이 아니라 병합이다 — 집계하지 않는다.
            if (newId == null) skippedEvents++ else plannedEvents.add(newId)
        }

        // 같은 코드의 캐릭터가 아직 살아 있는가 — **되돌리기 대상 판정은 code 단독으로만** 한다.
        // 아래 id 폴백은 '사본이 생긴다'는 고지 판정에만 쓴다: 옛 id는 재발급되어 남의 캐릭터가
        // 물려받았을 수 있으므로(R-1) 그것을 덮어쓰기 대상으로 삼으면 오배정이 된다.
        val livingByCode = data.character.code.takeIf { it.isNotBlank() }
            ?.let { db.characterDao().getCharacterByCode(it) }
        val livingSame = livingByCode?.let { true }
            ?: (db.characterDao().getCharacterById(data.character.id) != null)

        val mode = RestoreModes.characterRestoreMode(
            operationKind = snap.operationKind,
            livingSameCode = livingByCode != null
        )
        val revertScope = if (mode == CharacterRestoreMode.REVERT) {
            RestoreModes.revertScopeOf(data.revertScope)
        } else emptySet()

        // 이름 은행 사용 링크 (B-3). **복제 복원에서는 세지 않는다** — 원본이 살아서 링크를
        // 쥐고 있는 것이 정상이고, 그것을 유실로 고지하면 규모에 비례하는 유령 유실이 된다(R-11 교훈).
        var lostNameBankLinks = 0
        if (mode != CharacterRestoreMode.REVIVE_AS_COPY) {
            for (code in data.nameBankCodes.orEmpty()) {
                val entry = db.nameBankDao().getByCode(code)
                if (entry == null || (entry.usedByCharacterId != null &&
                        entry.usedByCharacterId != livingByCode?.id)
                ) {
                    lostNameBankLinks++
                }
            }
        }

        return CharacterPlan(
            data = data,
            novelId = novelId,
            fieldValues = resolvedFieldValues,
            relationships = plannedRelationships,
            memberships = plannedMemberships,
            eventIds = plannedEvents.toList(),
            losses = RestoreLossCounts(
                fieldValues = skippedFieldValues,
                mergedFieldValues = mergedFieldValues,
                relationships = skippedRelationships,
                relationshipChanges = skippedRelationshipChanges,
                memberships = skippedMemberships,
                events = skippedEvents,
                relationshipFactions = clearedRelationshipFactions,
                changeEvents = clearedChangeEvents,
                novelCleared = novelCleared,
                nameBankLinks = lostNameBankLinks
            ),
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = tally.previewOnly,
            // 되돌리기는 사본을 만들지 않는다 — 복제 경고를 함께 띄우면 서로 반대되는 두 안내가 된다.
            duplicatesLivingCharacter = livingSame && mode != CharacterRestoreMode.REVERT,
            mode = mode,
            revertTargetId = livingByCode?.id?.takeIf { mode == CharacterRestoreMode.REVERT },
            revertScope = revertScope
        )
    }

    /**
     * 이름 은행 링크를 되붙인다 (B-3). 되살리기·되돌리기 **양쪽 분기에서** 부른다.
     *
     * **code 단독으로만 찾는다.** 임포터는 `getByCode(...) ?: 이름+성별`의 2단 사다리를 쓰지만
     * 그것은 사용자가 손으로 쓴 시트의 행을 맞추는 일이고, 이쪽은 소유 링크를 되붙이는 일이다.
     * 이름이 같은 다른 엔트리에 붙이면 남의 이름을 이 캐릭터가 쓰는 것으로 표시해 버린다 —
     * R-1이 말하는 그대로 **오배정은 생략보다 나쁘다.** 못 찾으면 세어서 고지한다.
     *
     * 남이 쓰고 있는 엔트리는 **빼앗지 않는다.** 링크를 끊으면 그 캐릭터의 이름이 이름 은행에서
     * 미사용으로 바뀌어, 같은 이름이 다시 배정될 수 있다.
     *
     * @return 되붙이지 못한 링크 수
     */
    private suspend fun relinkNameBank(codes: List<String>, targetId: Long): Int {
        var lost = 0
        for (code in codes) {
            val entry = db.nameBankDao().getByCode(code)
            when {
                entry == null -> lost++
                entry.usedByCharacterId == targetId -> Unit   // 이미 맞다
                entry.usedByCharacterId != null -> lost++     // 남이 쓰는 중 — 빼앗지 않는다
                else -> db.nameBankDao().markAsUsed(entry.id, targetId)
            }
        }
        return lost
    }

    /**
     * @param consentedRevert 사용자가 **되돌리기(덮어쓰기)에 동의**했는가.
     *
     *   미리보기는 트랜잭션 밖에서, 적용은 새 트랜잭션 안에서 계획을 다시 세운다. 그 사이에
     *   원본이 되살아나면 판정이 부활에서 되돌리기로 **뒤집힌다** — 사용자는 "복원"에 동의했을
     *   뿐인데 살아 있는 캐릭터가 통째로 덮어써진다. 동의가 없으면 덮어쓰지 않고 사본을
     *   만든다(종전 동작): 동의한 범위 안이고 파괴가 없다.
     *
     * @return (결과, 복원된 캐릭터 id)
     */
    private suspend fun applyCharacter(
        plan: CharacterPlan,
        session: RestoreSession?,
        consentedRevert: Boolean = false
    ): Pair<RestoreResult, Long> {
        // 미리보기 전용 계획은 아직 존재하지 않는 대상을 0번 id로 가리킨다 — 쓰면 오배정이다.
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        if (plan.mode == CharacterRestoreMode.REVERT && consentedRevert) {
            return applyCharacterRevert(plan, session)
        }
        val data = plan.data
        var character = data.character.copy(novelId = plan.novelId)
        // 코드 충돌 시 재발급 (code unique)
        if (db.characterDao().getCharacterByCode(character.code) != null) {
            character = character.copy(code = generateEntityCode())
        }

        // 원본 id가 비어 있으면 유지, 차 있으면 새 id
        val targetId: Long = if (db.characterDao().getCharacterById(character.id) == null) {
            db.characterDao().insert(character)
            character.id
        } else {
            db.characterDao().insert(character.copy(id = 0))
        }

        // 같은 작업의 뒤 항목이 옛 코드로 이것을 찾을 수 있게 등록한다 — code가 재발급됐다면
        // 등록하지 않을 경우 뒤 항목이 살아 있던 남의 엔티티에 붙는다(오배정).
        session?.record(TrashSnapshot.TYPE_CHARACTER, data.character.code, targetId)

        db.characterFieldValueDao().insertAll(
            plan.fieldValues.map { it.copy(id = 0, characterId = targetId) }
        )

        // 상태변화·태그 — 캐릭터 외 참조 없음
        // code 충돌 시 재발급 (캐릭터 code와 동일 규칙): 복원 전 엑셀 임포트로 같은 코드가
        // 재생성된 경우 유니크 위반으로 복원 전체가 실패하는 것을 방지. 레거시(null) 코드는 신규 발급.
        db.characterStateChangeDao().insertAll(
            data.stateChanges.map { change ->
                val safeCode = change.code
                    ?.takeIf { c -> db.characterStateChangeDao().getChangeByCode(c) == null }
                    ?: generateEntityCode()
                change.copy(id = 0, characterId = targetId, code = safeCode)
            }
        )
        db.characterTagDao().insertAll(
            data.tags.map { it.copy(id = 0, characterId = targetId) }
        )

        var duplicateRelationships = 0
        var duplicateChanges = 0
        for (planned in plan.relationships) {
            val rel = planned.relationship
            // code 충돌 시 재발급 — 상태변화·관계변화 복원과 동일 규칙.
            // character_relationships에는 code 유니크 인덱스가 있고 insert가 IGNORE라,
            // 옛 code를 그대로 넣으면 '편집 직전 백업' 복원(원본 관계가 살아 있다)에서
            // 관계가 통째로 무음 유실된다. 그러면 -1의 의미도 '중복'이 아니게 되어
            // 아래 집계가 사실과 달라진다.
            val safeCode = rel.code
                ?.takeIf { c -> db.characterRelationshipDao().getByCode(c) == null }
                ?: generateEntityCode()
            val remapped = rel.copy(
                id = 0,
                code = safeCode,
                characterId1 = if (planned.selfIsFirst) targetId else rel.characterId1,
                characterId2 = if (planned.selfIsSecond) targetId else rel.characterId2
            )
            val newRelId = db.characterRelationshipDao().insert(remapped)
            if (newRelId == -1L) {
                // code를 재발급했으므로 남은 충돌 원인은 (상대, 유형) 유니크뿐 —
                // 같은 관계가 이미 존재한다. 상대 캐릭터를 먼저 복원했을 때 정상적으로
                // 일어나는 일이므로 '참조 소실'과 구분해 집계한다.
                duplicateRelationships++
                // 그 관계에 매달린 이력은 붙일 자리가 없어 합쳐지지 않는다. 유실은 유실이지만
                // 사유가 '관계를 못 찾음'과 다르므로 칸을 나눈다(같은 칸에 넣으면 거짓 사유가 된다).
                duplicateChanges += planned.changes.size
                continue
            }
            val changes = planned.changes.map { change ->
                // code 충돌 시 재발급 — 상태변화 복원과 동일 규칙
                val safeChangeCode = change.code
                    ?.takeIf { c -> db.characterRelationshipChangeDao().getChangeByCode(c) == null }
                    ?: generateEntityCode()
                change.copy(id = 0, relationshipId = newRelId, code = safeChangeCode)
            }
            if (changes.isNotEmpty()) {
                db.characterRelationshipChangeDao().insertAll(changes)
            }
        }

        for (membership in plan.memberships) {
            db.factionMembershipDao().insert(membership.copy(id = 0, characterId = targetId))
        }

        for (eventId in plan.eventIds) {
            db.timelineDao().insertCrossRef(TimelineCharacterCrossRef(eventId, targetId))
        }

        // 이름 은행 사용 표시 되붙이기 (B-3) — 캐릭터 행이 생긴 뒤라야 FK가 성립한다.
        // 사본을 만든 복원(REVIVE_AS_COPY, 동의 없는 REVERT 폴백)은 되붙이지도 세지도 않는다 —
        // 원본이 링크를 쥐고 있는 것이 정상인데 세면 유령 유실이 되고, 미리보기(0건)와 어긋나
        // '예상 밖 유실' 경고까지 울린다.
        val lostNameBankLinks = if (RestoreModes.revivalRelinksNameBank(plan.mode)) {
            relinkNameBank(data.nameBankCodes.orEmpty(), targetId)
        } else 0

        val result = RestoreResult(
            entityType = TrashSnapshot.TYPE_CHARACTER,
            restoredName = data.character.name,
            losses = plan.losses.copy(
                duplicateRelationshipChanges = duplicateChanges,
                nameBankLinks = lostNameBankLinks
            ),
            relinkedByCode = plan.relinkedByCode,
            duplicateRelationships = duplicateRelationships
        )
        return result to targetId
    }

    /**
     * **되돌리기** — 살아 있는 캐릭터를 편집 직전 상태로 돌린다 (B-2/B-21).
     *
     * 부활과 갈라 두는 이유는 하는 일이 정반대이기 때문이다: 부활은 없는 것을 만들고, 되돌리기는
     * 있는 것을 고친다. 종전에는 둘 다 insert라 지워진 적 없는 캐릭터가 하나 더 생겼다.
     *
     * 지키는 것 셋:
     * - **교체 범위는 그 편집이 파괴한 갈래까지다**([CharacterSnapshot.revertScope]). 관계·관계
     *   이력·사건 연결은 어떤 편집 백업 경로도 파괴하지 않으므로 **읽지도 쓰지도 않는다** —
     *   통째로 갈아 끼우면 백업 이후 사용자가 만든 관계를 무통보로 지운다.
     * - **덮기 전에 지금 상태를 한 번 더 백업한다.** 복원은 성공 시 스냅샷을 소각하므로, 남기지
     *   않으면 되돌리기가 잘못됐을 때 어느 쪽으로도 갈 수 없다(R-4).
     * - **대상은 code로만 정해졌다**(`revertTargetId`). id 폴백으로 고른 대상을 덮어쓰면
     *   재발급된 옛 id의 남의 캐릭터를 파괴한다(R-1).
     */
    private suspend fun applyCharacterRevert(
        plan: CharacterPlan,
        session: RestoreSession?
    ): Pair<RestoreResult, Long> {
        val data = plan.data
        val targetId = plan.revertTargetId
        val living = targetId?.let { db.characterDao().getCharacterById(it) }
        // 계획 수립과 이 쓰기 사이에 원본이 사라졌다면 되돌릴 대상이 없다 — 되살리기로 내려간다.
        if (living == null) {
            return applyCharacter(
                plan.copy(mode = CharacterRestoreMode.REVIVE, revertTargetId = null, revertScope = emptySet()),
                session,
                consentedRevert = false
            )
        }
        val scope = plan.revertScope

        // 덮기 직전 백업 — 이 스냅샷이 되돌리기의 취소 경로다.
        // 지금 이미지 경로를 함께 담는다: 캐릭터 행을 되돌리면 imagePaths도 옛 것으로 바뀌어
        // 현재 파일이 참조를 잃으므로, 담아 두어야 ImageOwnershipGuard가 보호한다.
        snapshotCharacter(
            living,
            parseImagePathStrings(living.imagePaths),
            kind = TrashSnapshot.KIND_EDIT_BACKUP,
            revertScope = scope.toList()
        )

        if (RestoreModes.SCOPE_CHARACTER_ROW in scope) {
            // id와 code는 **살아 있는 행의 것**을 유지한다 — payload의 code를 쓰면 유니크 충돌과
            // 재발급이 얽혀 '복원이 실패하거나 code가 바뀌는' 새 경로가 생긴다.
            db.characterDao().update(
                data.character.copy(id = living.id, code = living.code, novelId = plan.novelId)
            )
        }

        if (RestoreModes.SCOPE_FIELD_VALUES in scope) {
            // 스냅샷은 그 시점 캐릭터의 필드값 **전량**을 담으므로 전체 교체가 곧 되돌리기다
            // (R-5의 '커버 집합'이 여기서는 캐릭터 전체다). 해석하지 못한 값은 복원되지 않고
            // losses.fieldValues로 이미 집계돼 동의 문구에 실린다.
            db.characterFieldValueDao().replaceAllByCharacter(
                living.id,
                plan.fieldValues.map { it.copy(id = 0, characterId = living.id) }
            )
        }

        // 아래 둘은 **보충만** 한다. 편집 경로가 '지운' 것을 되돌리는 일이므로, 지금 있는 것을
        // 먼저 비우면 백업 이후 사용자가 더한 것까지 사라진다.
        var restoredMemberships = 0
        if (RestoreModes.SCOPE_MEMBERSHIPS in scope) {
            val existing = db.factionMembershipDao().getMembershipsByCharacterList(living.id)
                .map { it.factionId }.toHashSet()
            for (membership in plan.memberships) {
                if (!existing.add(membership.factionId)) continue
                db.factionMembershipDao().insert(membership.copy(id = 0, characterId = living.id))
                restoredMemberships++
            }
        }

        var restoredStateChanges = 0
        if (RestoreModes.SCOPE_STATE_CHANGES in scope) {
            for (change in data.stateChanges) {
                // 같은 이력이 이미 있으면 그 사이 사용자가 다시 만든 것이다 — 덮어쓰지 않는다
                // (applyEvent와 같은 규약).
                val dao = db.characterStateChangeDao()
                val sameCode = change.code?.takeIf { it.isNotBlank() }
                    ?.let { dao.getChangeByCode(it) != null } ?: false
                if (sameCode) continue
                if (change.fieldKey in SINGLETON_STATE_KEYS &&
                    dao.getChangesByField(living.id, change.fieldKey).isNotEmpty()
                ) continue
                val safeCode = change.code?.takeIf { c -> dao.getChangeByCode(c) == null }
                    ?: generateEntityCode()
                dao.insertAll(listOf(change.copy(id = 0, characterId = living.id, code = safeCode)))
                restoredStateChanges++
            }
        }

        val lostNameBankLinks = relinkNameBank(data.nameBankCodes.orEmpty(), living.id)

        session?.record(TrashSnapshot.TYPE_CHARACTER, data.character.code, living.id)

        val result = RestoreResult(
            entityType = TrashSnapshot.TYPE_CHARACTER,
            restoredName = data.character.name,
            losses = plan.losses.copy(nameBankLinks = lostNameBankLinks),
            relinkedByCode = plan.relinkedByCode,
            revertedInPlace = true,
            revertedMemberships = restoredMemberships,
            revertedStateChanges = restoredStateChanges
        )
        return result to living.id
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 세계관
    // ──────────────────────────────────────────────────────────────────────

    private data class UniversePlan(
        val data: UniverseSnapshot,
        /** 옛 필드정의 id → payload의 정의 (복원 후 새 id로 치환하기 위한 원본) */
        val fieldDefinitions: List<FieldDefinition>,
        /** 함께 되살릴 등급 체계 (U-1). 구버전 payload는 빈 목록이다(R-2 — orEmpty로 받았다). */
        val gradeSystems: List<GradeSystem>,
        val imageCharacterCode: String?,
        val imageNovelCode: String?,
        val losses: RestoreLossCounts,
        val relinkedByCode: Int,
        /** [RestoreTally.PENDING_ID] 자리표시자가 섞인 계획 — 미리보기 전용이며 쓰기에 쓰면 안 된다. */
        val previewOnly: Boolean,
        val legacyPayload: Boolean,
        val duplicatesLivingEntity: Boolean
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_UNIVERSE,
            entityName = data.universe.name,
            losses = losses,
            relinkedByCode = relinkedByCode,
            duplicatesLivingEntity = duplicatesLivingEntity,
            legacyPayload = legacyPayload
        )
    }

    private suspend fun universePlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null
    ): UniversePlan? {
        val data = parse(snap, UniverseSnapshot::class.java) ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.universe == null) return null

        val refs = data.refs
        val tally = RestoreTally(legacy = refs == null, pendingCodes = pendingCodes)

        // 값 라이브러리·고아 필드값은 이제 이어붙임 행(TYPE_UNIVERSE_DATA)이 담는다 —
        // 한 행에 몰아넣으면 payload가 CursorWindow 한도를 넘겨 백업을 읽을 수 없게 된다.
        val charIndex = characterIndex(
            data.universe.imageCharacterId.asList(), refs?.characters?.values.orEmpty(), session
        )

        val livingSame = data.universe.code.takeIf { it.isNotBlank() }
            ?.let { db.universeDao().getUniverseByCode(it) != null } ?: false

        return UniversePlan(
            data = data,
            fieldDefinitions = data.fieldDefinitions.orEmpty(),
            gradeSystems = data.gradeSystems.orEmpty(),
            imageCharacterCode = data.universe.imageCharacterId
                ?.let { refs?.characters?.get(it.toString()) },
            imageNovelCode = data.universe.imageNovelId?.let { refs?.novels?.get(it.toString()) },
            losses = RestoreLossCounts(),
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = tally.previewOnly,
            duplicatesLivingEntity = livingSame
        )
    }

    private suspend fun applyUniverse(plan: UniversePlan, session: RestoreSession?): Pair<RestoreResult, DeferredImageLink?> {
        // 미리보기 전용 계획은 아직 존재하지 않는 대상을 0번 id로 가리킨다 — 쓰면 오배정이다.
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        val source = plan.data.universe
        var universe = source
        if (db.universeDao().getUniverseByCode(universe.code) != null) {
            universe = universe.copy(code = generateEntityCode())
        }
        // 대표 이미지 참조는 아직 복원되지 않았을 수 있으므로 일단 끊고 마지막에 되붙인다.
        // (FK가 걸려 있어 없는 id를 그대로 넣으면 복원 자체가 실패한다)
        universe = universe.copy(imageCharacterId = null, imageNovelId = null)

        val newUniverseId: Long = if (db.universeDao().getUniverseById(universe.id) == null) {
            db.universeDao().insert(universe)
            universe.id
        } else {
            db.universeDao().insert(universe.copy(id = 0))
        }

        // 같은 작업의 뒤 항목이 옛 코드로 이것을 찾을 수 있게 등록한다 — code가 재발급됐다면
        // 등록하지 않을 경우 뒤 항목이 살아 있던 남의 엔티티에 붙는다(오배정).
        session?.record(TrashSnapshot.TYPE_UNIVERSE, source.code, newUniverseId)

        // 등급 체계(U-1) — 필드 정의 config가 code로 참조하므로 **정의보다 먼저** 되살린다.
        // code가 살아 있는 남의 체계와 충돌하면 재발급하고, 이 payload의 정의 config를 새 code로
        // 다시 잇는다 — 옛 code로 두면 복원된 필드가 남의 체계에 붙는다(R-1의 교훈 그대로).
        var skippedSystems = 0
        val systemCodeRemap = HashMap<String, String>()
        val seenSystemNames = HashSet<String>()
        for (gs in plan.gradeSystems) {
            @Suppress("SENSELESS_COMPARISON")
            if (gs == null || gs.name == null || gs.gradesJson == null || gs.code == null) {
                skippedSystems++   // 손편집·손상 payload의 행 (R-2)
                continue
            }
            if (!seenSystemNames.add(gs.name)) {
                skippedSystems++   // payload 안 이름 중복 — 유니크 (universeId, name) 위반 방지
                continue
            }
            val safeCode = if (db.gradeSystemDao().getByCode(gs.code) == null) gs.code
            else generateEntityCode().also { systemCodeRemap[gs.code] = it }
            db.gradeSystemDao().insert(gs.copy(id = 0, universeId = newUniverseId, code = safeCode))
        }

        // 필드 정의 — 새 세계관 아래라 자연키 충돌은 payload 안에서만 가능하다(같은 key 중복).
        var skippedDefs = 0
        val newDefIdByOld = HashMap<Long, Long>()
        for (def in plan.fieldDefinitions) {
            val existing = db.fieldDefinitionDao().getFieldByKey(newUniverseId, def.key, def.entityType)
            if (existing != null) {
                skippedDefs++
                continue
            }
            val newId = db.fieldDefinitionDao().insert(
                def.copy(
                    id = 0, universeId = newUniverseId,
                    config = GradeSystemRef.remapCode(def.config, systemCodeRemap)
                )
            )
            newDefIdByOld[def.id] = newId
        }

        val deferred = if (plan.imageCharacterCode != null || plan.imageNovelCode != null) {
            DeferredImageLink(
                TrashSnapshot.TYPE_UNIVERSE, newUniverseId,
                plan.imageCharacterCode, plan.imageNovelCode
            )
        } else {
            null
        }

        val result = RestoreResult(
            entityType = TrashSnapshot.TYPE_UNIVERSE,
            restoredName = source.name,
            losses = plan.losses.copy(fieldDefinitions = skippedDefs, gradeSystems = skippedSystems),
            relinkedByCode = plan.relinkedByCode
        )
        return result to deferred
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 세계관 부가 데이터 (이어붙임 행)
    // ──────────────────────────────────────────────────────────────────────

    private data class UniverseDataPlan(
        val data: UniverseDataSnapshot,
        /** (해석된 필드정의 id, 엔트리) */
        val entries: List<Pair<Long, FieldValueEntry>>,
        /** (해석된 characterId, 해석된 필드정의 id, 값) */
        val characterValues: List<Triple<Long, Long, CharacterFieldValue>>,
        val eventValues: List<Triple<Long, Long, EventFieldValue>>,
        val novelValues: List<Triple<Long, Long, NovelFieldValue>>,
        val losses: RestoreLossCounts,
        val relinkedByCode: Int,
        val legacyPayload: Boolean,
        val previewOnly: Boolean,
        val blocker: RestoreBlocker?
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_UNIVERSE_DATA,
            entityName = data.universeCode.orEmpty(),
            losses = losses,
            relinkedByCode = relinkedByCode,
            legacyPayload = legacyPayload,
            blocker = blocker
        )
    }

    private suspend fun universeDataPlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null
    ): UniverseDataPlan? {
        val data = parse(snap, UniverseDataSnapshot::class.java) ?: return null
        val refs = data.refs
        val tally = RestoreTally(legacy = refs == null, pendingCodes = pendingCodes)

        val uCode = data.universeCode ?: refs?.universeCode
        val universeExists = uCode != null &&
            (session?.lookup(TrashSnapshot.TYPE_UNIVERSE, uCode) != null ||
                db.universeDao().getUniverseByCode(uCode) != null ||
                uCode in pendingCodes)

        val fieldRefs = refs?.fieldDefs.orEmpty()
        val entries = data.fieldValueEntries.orEmpty()
        val charValues = data.orphanCharacterFieldValues.orEmpty()
        val eventValues = data.orphanEventFieldValues.orEmpty()
        val novelValues = data.orphanNovelFieldValues.orEmpty()
        val fieldIndex = buildFieldDefIndex(
            oldIds = (entries.map { it.fieldDefinitionId } + charValues.map { it.fieldDefinitionId } +
                eventValues.map { it.fieldDefinitionId } + novelValues.map { it.fieldDefinitionId }).distinct(),
            refs = fieldRefs.values,
            session = session
        )

        fun resolveDef(oldId: Long): Long? {
            val ref = fieldRefs[oldId.toString()]
            return tally.noteFieldDef(
                SnapshotRefResolver.resolveFieldDef(
                    oldId, ref, fieldIndex.naturalById, fieldIndex.idByNatural
                ),
                ref
            ).id
        }

        var lostEntries = 0
        val plannedEntries = ArrayList<Pair<Long, FieldValueEntry>>(entries.size)
        for (e in entries) {
            val defId = resolveDef(e.fieldDefinitionId)
            if (defId == null) lostEntries++ else plannedEntries.add(defId to e)
        }

        val charIndex = characterIndex(
            charValues.map { it.characterId }, refs?.characters?.values.orEmpty(), session
        )
        val evIndex = eventIndex(eventValues.map { it.eventId }, refs?.events?.values.orEmpty(), session)
        val novIndex = novelIndex(novelValues.map { it.novelId }, refs?.novels?.values.orEmpty(), session)

        var orphanLost = 0
        var merged = 0
        val seenChars = HashSet<Pair<Long, Long>>()
        val plannedChars = ArrayList<Triple<Long, Long, CharacterFieldValue>>(charValues.size)
        for (v in charValues) {
            val code = refs?.characters?.get(v.characterId.toString())
            val owner = tally.note(
                SnapshotRefResolver.resolveByCode(
                    v.characterId, code, charIndex.codeById, charIndex.idByCode, charIndex.liveIds
                ),
                code
            ).id
            val defId = resolveDef(v.fieldDefinitionId)
            if (owner == null || defId == null) {
                orphanLost++
                continue
            }
            // 보류 자리표시자끼리는 전부 (0, 0)이라 수렴처럼 보인다 — 세지 않는다(위 주석과 같은 사유).
            if (isResolved(owner, defId) && !seenChars.add(owner to defId)) {
                merged++
                continue
            }
            plannedChars.add(Triple(owner, defId, v))
        }

        val seenEvents = HashSet<Pair<Long, Long>>()
        val plannedEvents = ArrayList<Triple<Long, Long, EventFieldValue>>(eventValues.size)
        for (v in eventValues) {
            val code = refs?.events?.get(v.eventId.toString())
            val owner = tally.note(
                SnapshotRefResolver.resolveByCode(
                    v.eventId, code, evIndex.codeById, evIndex.idByCode, evIndex.liveIds
                ),
                code
            ).id
            val defId = resolveDef(v.fieldDefinitionId)
            if (owner == null || defId == null) {
                orphanLost++
                continue
            }
            if (isResolved(owner, defId) && !seenEvents.add(owner to defId)) {
                merged++
                continue
            }
            plannedEvents.add(Triple(owner, defId, v))
        }

        val seenNovels = HashSet<Pair<Long, Long>>()
        val plannedNovels = ArrayList<Triple<Long, Long, NovelFieldValue>>(novelValues.size)
        for (v in novelValues) {
            val code = refs?.novels?.get(v.novelId.toString())
            val owner = tally.note(
                SnapshotRefResolver.resolveByCode(
                    v.novelId, code, novIndex.codeById, novIndex.idByCode, novIndex.liveIds
                ),
                code
            ).id
            val defId = resolveDef(v.fieldDefinitionId)
            if (owner == null || defId == null) {
                orphanLost++
                continue
            }
            if (isResolved(owner, defId) && !seenNovels.add(owner to defId)) {
                merged++
                continue
            }
            plannedNovels.add(Triple(owner, defId, v))
        }

        return UniverseDataPlan(
            data = data,
            entries = plannedEntries,
            characterValues = plannedChars,
            eventValues = plannedEvents,
            novelValues = plannedNovels,
            losses = RestoreLossCounts(
                fieldValueEntries = lostEntries,
                orphanFieldValues = orphanLost,
                mergedFieldValues = merged
            ),
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = tally.previewOnly,
            // 세계관이 없으면 필드 정의도 없어 붙을 자리가 없다 — 되살리지 않고 스냅샷을 남긴다(R-4).
            blocker = if (!universeExists) RestoreBlocker.MISSING_UNIVERSE else null
        )
    }

    private suspend fun applyUniverseData(plan: UniverseDataPlan): RestoreResult {
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        check(plan.blocker == null) { "복원할 수 없는 세계관 부가 데이터 계획이 적용 단계까지 왔다" }

        var skippedEntries = 0
        val takenCodes = HashSet<String>()
        for (chunk in plan.entries.map { it.second.code }.filter { it.isNotBlank() }.distinct()
            .chunked(IN_CLAUSE_CHUNK)) {
            db.fieldValueEntryDao().getByCodes(chunk).forEach { takenCodes.add(it.code) }
        }
        val rows = plan.entries.map { (defId, entry) ->
            val safeCode = entry.code.takeIf { it.isNotBlank() && takenCodes.add(it) } ?: generateEntityCode()
            entry.copy(id = 0, fieldDefinitionId = defId, code = safeCode)
        }
        if (rows.isNotEmpty()) {
            skippedEntries += db.fieldValueEntryDao().insertAllIgnore(rows).count { it == -1L }
        }

        var orphanLost = plan.losses.orphanFieldValues
        val existingByChar = HashMap<Long, MutableSet<Long>>()
        for (chunk in plan.characterValues.map { it.first }.distinct().chunked(IN_CLAUSE_CHUNK)) {
            for (v in db.characterFieldValueDao().getValuesForCharacters(chunk)) {
                existingByChar.getOrPut(v.characterId) { HashSet() }.add(v.fieldDefinitionId)
            }
        }
        val charRows = ArrayList<CharacterFieldValue>(plan.characterValues.size)
        for ((charId, defId, value) in plan.characterValues) {
            if (defId in existingByChar.getOrElse(charId) { emptySet<Long>() }) {
                orphanLost++
                continue
            }
            charRows.add(value.copy(id = 0, characterId = charId, fieldDefinitionId = defId))
        }
        if (charRows.isNotEmpty()) db.characterFieldValueDao().insertAll(charRows)

        val eventRows = ArrayList<EventFieldValue>(plan.eventValues.size)
        for ((eventId, defId, value) in plan.eventValues) {
            if (db.eventFieldValueDao().getValue(eventId, defId) != null) {
                orphanLost++
                continue
            }
            eventRows.add(value.copy(id = 0, eventId = eventId, fieldDefinitionId = defId))
        }
        if (eventRows.isNotEmpty()) db.eventFieldValueDao().insertAll(eventRows)

        val novelRows = ArrayList<NovelFieldValue>(plan.novelValues.size)
        for ((novelId, defId, value) in plan.novelValues) {
            if (db.novelFieldValueDao().getValue(novelId, defId) != null) {
                orphanLost++
                continue
            }
            novelRows.add(value.copy(id = 0, novelId = novelId, fieldDefinitionId = defId))
        }
        if (novelRows.isNotEmpty()) db.novelFieldValueDao().insertAll(novelRows)

        return RestoreResult(
            entityType = TrashSnapshot.TYPE_UNIVERSE_DATA,
            restoredName = plan.data.universeCode.orEmpty(),
            losses = plan.losses.copy(
                // 계획 단계의 유실(필드 정의를 못 찾은 엔트리)에 **더한다.** 종전에는 덮어써서
                // 그만큼이 결과에서 조용히 사라졌다 — orphanFieldValues 쪽과 같은 규칙이어야 한다.
                fieldValueEntries = plan.losses.fieldValueEntries + skippedEntries,
                orphanFieldValues = orphanLost
            ),
            relinkedByCode = plan.relinkedByCode
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 작품
    // ──────────────────────────────────────────────────────────────────────

    private data class NovelPlan(
        val data: NovelSnapshot,
        val universeId: Long?,
        val eventIds: List<Long>,
        val imageCharacterCode: String?,
        val losses: RestoreLossCounts,
        val relinkedByCode: Int,
        /** [RestoreTally.PENDING_ID] 자리표시자가 섞인 계획 — 미리보기 전용이며 쓰기에 쓰면 안 된다. */
        val previewOnly: Boolean,
        val legacyPayload: Boolean,
        val duplicatesLivingEntity: Boolean
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_NOVEL,
            entityName = data.novel.title,
            losses = losses,
            relinkedByCode = relinkedByCode,
            duplicatesLivingEntity = duplicatesLivingEntity,
            legacyPayload = legacyPayload
        )
    }

    private suspend fun novelPlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null
    ): NovelPlan? {
        val data = parse(snap, NovelSnapshot::class.java) ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.novel == null) return null

        val refs = data.refs
        val tally = RestoreTally(legacy = refs == null, pendingCodes = pendingCodes)

        val universe = resolveUniverse(data.novel.universeId, refs?.universeCode, tally, session)
        val linked = data.linkedEventIds.orEmpty()
        val evIndex = eventIndex(linked, refs?.events?.values.orEmpty(), session)
        val plannedEvents = LinkedHashSet<Long>()
        var lostEvents = 0
        for (old in linked) {
            val code = refs?.events?.get(old.toString())
            val res = tally.note(
                SnapshotRefResolver.resolveByCode(
                    old, code, evIndex.codeById, evIndex.idByCode, evIndex.liveIds
                ),
                code
            )
            if (res.id == null) lostEvents++ else plannedEvents.add(res.id)
        }

        val livingSame = data.novel.code.takeIf { it.isNotBlank() }
            ?.let { db.novelDao().getNovelByCode(it) != null } ?: false

        // 작품 커스텀 필드값(확-3) — 정의가 이미 지워진 값은 FK가 거부하므로 되살릴 수 없다.
        // **미리보기와 결과가 같은 수를 말하도록 여기서 센다**(RestoreLossCounts의 규약) —
        // 복원 시점에만 세면 사용자가 동의한 예고와 실제가 갈린다.
        val savedFieldValues = data.fieldValues.orEmpty()
        val lostNovelFieldValues = if (savedFieldValues.isEmpty()) 0 else {
            val liveDefIds = db.fieldDefinitionDao()
                .getFieldsByIds(savedFieldValues.map { it.fieldDefinitionId }.distinct())
                .mapTo(HashSet()) { it.id }
            savedFieldValues.count { it.fieldDefinitionId !in liveDefIds }
        }

        return NovelPlan(
            data = data,
            universeId = universe.id,
            eventIds = plannedEvents.toList(),
            imageCharacterCode = data.novel.imageCharacterId?.let { refs?.characters?.get(it.toString()) },
            losses = RestoreLossCounts(
                events = lostEvents,
                universeCleared = universe.cleared,
                novelFieldValues = lostNovelFieldValues
            ),
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = tally.previewOnly,
            duplicatesLivingEntity = livingSame
        )
    }

    private suspend fun applyNovel(plan: NovelPlan, session: RestoreSession?): Pair<RestoreResult, DeferredImageLink?> {
        // 미리보기 전용 계획은 아직 존재하지 않는 대상을 0번 id로 가리킨다 — 쓰면 오배정이다.
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        val source = plan.data.novel
        var novel = source.copy(universeId = plan.universeId, imageCharacterId = null)
        if (db.novelDao().getNovelByCode(novel.code) != null) {
            novel = novel.copy(code = generateEntityCode())
        }
        val newId: Long = if (db.novelDao().getNovelById(novel.id) == null) {
            db.novelDao().insert(novel)
            novel.id
        } else {
            db.novelDao().insert(novel.copy(id = 0))
        }

        // 같은 작업의 뒤 항목이 옛 코드로 이것을 찾을 수 있게 등록한다 — code가 재발급됐다면
        // 등록하지 않을 경우 뒤 항목이 살아 있던 남의 엔티티에 붙는다(오배정).
        session?.record(TrashSnapshot.TYPE_NOVEL, source.code, newId)

        for (eventId in plan.eventIds) {
            db.timelineDao().insertEventNovelCrossRef(TimelineEventNovelCrossRef(eventId, newId))
        }

        // 작품 커스텀 필드값(확-3) 되살리기. id는 새로 받고 novelId는 복원된 작품을 가리킨다.
        // **정의가 사라진 값은 넣지 않는다** — FK가 거부해 복원 전체가 실패하기 때문이며,
        // 버린 건수는 losses에 실려 사용자에게 고지된다(말없이 버리지 않는다).
        val savedValues = plan.data.fieldValues.orEmpty()
        if (savedValues.isNotEmpty()) {
            val liveDefIds = db.fieldDefinitionDao()
                .getFieldsByIds(savedValues.map { it.fieldDefinitionId }.distinct())
                .mapTo(HashSet()) { it.id }
            val restorable = savedValues.filter { it.fieldDefinitionId in liveDefIds }
            if (restorable.isNotEmpty()) {
                db.novelFieldValueDao().insertAll(
                    restorable.map { it.copy(id = 0, novelId = newId) }
                )
            }
        }

        val deferred = plan.imageCharacterCode?.let {
            DeferredImageLink(TrashSnapshot.TYPE_NOVEL, newId, it, null)
        }
        return RestoreResult(
            entityType = TrashSnapshot.TYPE_NOVEL,
            restoredName = source.title,
            losses = plan.losses,
            relinkedByCode = plan.relinkedByCode
        ) to deferred
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 세력
    // ──────────────────────────────────────────────────────────────────────

    private data class FactionPlan(
        val data: FactionSnapshot,
        val universeId: Long,
        val memberships: List<FactionMembership>,
        /** (관계, 상대 세력의 새 id 또는 null=자기 자신, selfIsFirst) */
        val factionRelationships: List<Triple<FactionRelationship, Long?, Boolean>>,
        val autoRelationships: List<Triple<CharacterRelationship, Long, Long>>,
        val autoChangesByOldRelId: Map<Long, List<CharacterRelationshipChange>>,
        val detachedCodes: List<String>,
        val losses: RestoreLossCounts,
        val relinkedByCode: Int,
        /** [RestoreTally.PENDING_ID] 자리표시자가 섞인 계획 — 미리보기 전용이며 쓰기에 쓰면 안 된다. */
        val previewOnly: Boolean,
        val legacyPayload: Boolean,
        val duplicatesLivingEntity: Boolean,
        val blocker: RestoreBlocker?
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_FACTION,
            entityName = data.faction.name,
            losses = losses,
            relinkedByCode = relinkedByCode,
            duplicatesLivingEntity = duplicatesLivingEntity,
            legacyPayload = legacyPayload,
            blocker = blocker
        )
    }

    private suspend fun factionPlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null,
        pendingPeerCodes: Set<String> = emptySet()
    ): FactionPlan? {
        val data = parse(snap, FactionSnapshot::class.java) ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.faction == null) return null

        val refs = data.refs
        val tally = RestoreTally(legacy = refs == null, pendingCodes = pendingCodes)
        val universe = resolveUniverse(data.faction.universeId, refs?.universeCode, tally, session)

        val memberships = data.memberships.orEmpty()
        val factionRels = data.factionRelationships.orEmpty()
        val autoRels = data.autoRelationships.orEmpty()
        val autoChanges = data.autoRelationshipChanges.orEmpty()
        val detached = data.detachedRelationshipCodes.orEmpty()

        val charIndex = characterIndex(
            memberships.map { it.characterId } + autoRels.flatMap { listOf(it.characterId1, it.characterId2) },
            refs?.characters?.values.orEmpty(), session
        )
        val facIndex = factionIndex(
            factionRels.flatMap { listOf(it.factionId1, it.factionId2) }.filter { it != data.faction.id },
            refs?.factions?.values.orEmpty(), session
        )

        fun resolveChar(oldId: Long): Long? {
            val code = refs?.characters?.get(oldId.toString())
            return tally.note(
                SnapshotRefResolver.resolveByCode(
                    oldId, code, charIndex.codeById, charIndex.idByCode, charIndex.liveIds
                ),
                code
            ).id
        }

        val plannedMemberships = ArrayList<FactionMembership>(memberships.size)
        var lostMemberships = 0
        for (m in memberships) {
            val newCharId = resolveChar(m.characterId)
            if (newCharId == null) lostMemberships++ else plannedMemberships.add(m.copy(characterId = newCharId))
        }

        val plannedFactionRels = ArrayList<Triple<FactionRelationship, Long?, Boolean>>(factionRels.size)
        var lostFactionRels = 0
        for (rel in factionRels) {
            val selfIsFirst = rel.factionId1 == data.faction.id
            val selfIsSecond = rel.factionId2 == data.faction.id
            if (!selfIsFirst && !selfIsSecond) {
                // 어느 쪽도 이 세력이 아니다 — payload 손상. 복원하면 남의 관계를 만든다.
                lostFactionRels++
                continue
            }
            if (selfIsFirst && selfIsSecond) {
                plannedFactionRels.add(Triple(rel, null, true))
                continue
            }
            val otherOld = if (selfIsFirst) rel.factionId2 else rel.factionId1
            val code = refs?.factions?.get(otherOld.toString())
            val res = tally.note(
                SnapshotRefResolver.resolveByCode(
                    otherOld, code, facIndex.codeById, facIndex.idByCode, facIndex.liveIds
                ),
                code
            )
            if (res.id == null) {
                // 상대 세력이 **같은 작업 안에** 있으면 유실이 아니다 — 그쪽 스냅샷도 이 관계를
                // 담고 있고, 아직 복원되지 않았을 뿐이다. 여기서 세면 유령 유실이 된다.
                if (code != null && code in pendingPeerCodes) continue
                lostFactionRels++
                continue
            }
            plannedFactionRels.add(Triple(rel, res.id, selfIsFirst))
        }

        val plannedAuto = ArrayList<Triple<CharacterRelationship, Long, Long>>(autoRels.size)
        var lostAuto = 0
        for (rel in autoRels) {
            val c1 = resolveChar(rel.characterId1)
            val c2 = resolveChar(rel.characterId2)
            if (c1 == null || c2 == null) {
                lostAuto++
                continue
            }
            plannedAuto.add(Triple(rel, c1, c2))
        }

        // 관계 변화의 사건 연결도 코드로 다시 찾는다 — 그러지 않으면 탈퇴 이력이 엉뚱한 사건에 붙는다.
        val evIndex = eventIndex(autoChanges.mapNotNull { it.eventId }, refs?.events?.values.orEmpty(), session)
        var clearedChangeEvents = 0
        val changesByRel = HashMap<Long, MutableList<CharacterRelationshipChange>>()
        for (change in autoChanges) {
            val newEventId = change.eventId?.let { old ->
                val code = refs?.events?.get(old.toString())
                val res = tally.note(
                    SnapshotRefResolver.resolveByCode(
                        old, code, evIndex.codeById, evIndex.idByCode, evIndex.liveIds
                    ),
                    code
                )
                if (!res.found) clearedChangeEvents++
                res.id
            }
            changesByRel.getOrPut(change.relationshipId) { ArrayList() }
                .add(change.copy(eventId = newEventId))
        }
        // 되살릴 수 없는 관계에 매달린 이력은 함께 사라진다 — 사실대로 센다.
        val liveRelIds = plannedAuto.map { it.first.id }.toSet()
        val lostChanges = changesByRel.filterKeys { it !in liveRelIds }.values.sumOf { it.size }

        val livingSame = data.faction.code.takeIf { it.isNotBlank() }
            ?.let { db.factionDao().getByCode(it) != null } ?: false

        return FactionPlan(
            data = data,
            universeId = universe.id ?: -1L,
            memberships = plannedMemberships,
            factionRelationships = plannedFactionRels,
            autoRelationships = plannedAuto,
            autoChangesByOldRelId = changesByRel,
            detachedCodes = detached,
            losses = RestoreLossCounts(
                memberships = lostMemberships,
                factionRelationships = lostFactionRels,
                relationships = lostAuto,
                relationshipChanges = lostChanges,
                changeEvents = clearedChangeEvents
            ),
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = tally.previewOnly,
            duplicatesLivingEntity = livingSame,
            // 세력은 세계관 없이 존재할 수 없다(NOT NULL). 아무 세계관에나 붙이면 오배정이므로
            // 되살리지 않고 스냅샷을 남긴다 — 세계관을 먼저 복원한 뒤 다시 시도할 수 있다(R-4).
            blocker = if (universe.id == null) RestoreBlocker.MISSING_UNIVERSE else null
        )
    }

    private suspend fun applyFaction(plan: FactionPlan, session: RestoreSession?): RestoreResult {
        // 미리보기 전용 계획은 아직 존재하지 않는 대상을 0번 id로 가리킨다 — 쓰면 오배정이다.
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        // 세력은 세계관 없이 존재할 수 없다. 호출부 둘 다 blocker를 보지만, 여기서도 막는다 —
        // 놓치면 universeId = -1L인 유령 세력이 만들어진다(FK가 없는 경로로 새면 더 나쁘다).
        check(plan.blocker == null) { "복원할 수 없는 세력 계획이 적용 단계까지 왔다: ${plan.blocker}" }
        val source = plan.data.faction
        var faction = source.copy(universeId = plan.universeId)
        if (db.factionDao().getByCode(faction.code) != null) {
            faction = faction.copy(code = generateEntityCode())
        }
        val newId: Long = if (db.factionDao().getById(faction.id) == null) {
            db.factionDao().insert(faction)
            faction.id
        } else {
            db.factionDao().insert(faction.copy(id = 0))
        }

        // 같은 작업의 뒤 항목이 옛 코드로 이것을 찾을 수 있게 등록한다 — code가 재발급됐다면
        // 등록하지 않을 경우 뒤 항목이 살아 있던 남의 엔티티에 붙는다(오배정).
        session?.record(TrashSnapshot.TYPE_FACTION, source.code, newId)

        for (m in plan.memberships) {
            db.factionMembershipDao().insert(m.copy(id = 0, factionId = newId))
        }

        var duplicates = 0
        for ((rel, otherNewId, selfIsFirst) in plan.factionRelationships) {
            val remapped = rel.copy(
                id = 0,
                factionId1 = if (selfIsFirst) newId else (otherNewId ?: newId),
                factionId2 = if (selfIsFirst) (otherNewId ?: newId) else newId
            )
            if (db.factionRelationshipDao().insert(remapped) == -1L) duplicates++
        }

        var lostAutoChanges = 0
        for ((rel, c1, c2) in plan.autoRelationships) {
            val safeCode = rel.code
                ?.takeIf { c -> db.characterRelationshipDao().getByCode(c) == null }
                ?: generateEntityCode()
            val newRelId = db.characterRelationshipDao().insert(
                rel.copy(id = 0, code = safeCode, characterId1 = c1, characterId2 = c2, factionId = newId)
            )
            val changes = plan.autoChangesByOldRelId[rel.id].orEmpty()
            if (newRelId == -1L) {
                duplicates++
                lostAutoChanges += changes.size
                continue
            }
            if (changes.isNotEmpty()) {
                db.characterRelationshipChangeDao().insertAll(
                    changes.map { change ->
                        val safeChangeCode = change.code
                            ?.takeIf { c -> db.characterRelationshipChangeDao().getChangeByCode(c) == null }
                            ?: generateEntityCode()
                        change.copy(id = 0, relationshipId = newRelId, code = safeChangeCode)
                    }
                )
            }
        }

        // '이 세력의 자동 관계'라는 지정만 되붙인다 — 관계 자체는 살아 있다.
        var lostDetached = 0
        for (code in plan.detachedCodes) {
            val rel = db.characterRelationshipDao().getByCode(code)
            if (rel == null) {
                lostDetached++
                continue
            }
            db.characterRelationshipDao().update(rel.copy(factionId = newId))
        }

        return RestoreResult(
            entityType = TrashSnapshot.TYPE_FACTION,
            restoredName = source.name,
            losses = plan.losses.copy(
                detachedRelationships = lostDetached,
                // 관계가 이미 있어 이력을 합치지 못한 것은 '관계를 못 찾음'과 사유가 다르다 —
                // 같은 칸에 넣으면 거짓 사유가 된다(applyCharacter와 동일한 구분).
                duplicateRelationshipChanges = lostAutoChanges
            ),
            relinkedByCode = plan.relinkedByCode,
            duplicateRelationships = duplicates
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 사건
    // ──────────────────────────────────────────────────────────────────────

    private data class EventPlan(
        val data: EventSnapshot,
        val universeId: Long?,
        val fieldValues: List<EventFieldValue>,
        val characterIds: List<Long>,
        val novelIds: List<Long>,
        val changeCodes: List<String>,
        /** 해석된 characterId로 치환된 출생/사망 이력 */
        val stateChanges: List<CharacterStateChange>,
        val losses: RestoreLossCounts,
        val relinkedByCode: Int,
        /** [RestoreTally.PENDING_ID] 자리표시자가 섞인 계획 — 미리보기 전용이며 쓰기에 쓰면 안 된다. */
        val previewOnly: Boolean,
        val legacyPayload: Boolean,
        val duplicatesLivingEntity: Boolean
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_EVENT,
            entityName = data.event.description,
            losses = losses,
            relinkedByCode = relinkedByCode,
            duplicatesLivingEntity = duplicatesLivingEntity,
            legacyPayload = legacyPayload
        )
    }

    private suspend fun eventPlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null
    ): EventPlan? {
        val data = parse(snap, EventSnapshot::class.java) ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.event == null) return null

        val refs = data.refs
        val tally = RestoreTally(legacy = refs == null, pendingCodes = pendingCodes)
        val universe = resolveUniverse(data.event.universeId, refs?.universeCode, tally, session)

        val values = data.fieldValues.orEmpty()
        val fieldRefs = refs?.fieldDefs.orEmpty()
        val fieldIndex = buildFieldDefIndex(
            oldIds = values.map { it.fieldDefinitionId }.distinct(),
            refs = fieldRefs.values,
            session = session
        )
        val plannedValues = ArrayList<EventFieldValue>(values.size)
        val usedDefIds = HashSet<Long>()
        var lostValues = 0
        var mergedValues = 0
        for (v in values) {
            val fieldRef = fieldRefs[v.fieldDefinitionId.toString()]
            val res = tally.noteFieldDef(
                SnapshotRefResolver.resolveFieldDef(
                    v.fieldDefinitionId, fieldRef,
                    fieldIndex.naturalById, fieldIndex.idByNatural
                ),
                fieldRef
            )
            val newId = res.id
            if (newId == null) {
                lostValues++
                continue
            }
            if (newId != RestoreTally.PENDING_ID && !usedDefIds.add(newId)) {
                mergedValues++
                continue
            }
            plannedValues.add(v.copy(fieldDefinitionId = newId))
        }

        val charIds = data.characterIds.orEmpty()
        val charIndex = characterIndex(charIds, refs?.characters?.values.orEmpty(), session)
        val plannedChars = LinkedHashSet<Long>()
        var lostChars = 0
        for (old in charIds) {
            val code = refs?.characters?.get(old.toString())
            val res = tally.note(
                SnapshotRefResolver.resolveByCode(
                    old, code, charIndex.codeById, charIndex.idByCode, charIndex.liveIds
                ),
                code
            )
            if (res.id == null) lostChars++ else plannedChars.add(res.id)
        }

        val novelIds = data.novelIds.orEmpty()
        val novIndex = buildIndex(
            oldIds = novelIds,
            codes = refs?.novels?.values.orEmpty(),
            fetchByIds = { db.novelDao().getNovelsByIds(it) },
            fetchByCodes = { db.novelDao().getNovelsByCodes(it) },
            idOf = { it.id },
            codeOf = { it.code },
            entityType = TrashSnapshot.TYPE_NOVEL,
            session = session
        )
        val plannedNovels = LinkedHashSet<Long>()
        var lostNovels = 0
        for (old in novelIds) {
            val code = refs?.novels?.get(old.toString())
            val res = tally.note(
                SnapshotRefResolver.resolveByCode(
                    old, code, novIndex.codeById, novIndex.idByCode, novIndex.liveIds
                ),
                code
            )
            if (res.id == null) lostNovels++ else plannedNovels.add(res.id)
        }

        // 출생/사망 이력의 주인 캐릭터도 코드로 다시 찾는다 — id 단독으로 붙이면
        // 남의 캐릭터에 남의 출생 기록을 심는다(오배정).
        val linkedChanges = data.linkedStateChanges.orEmpty()
        val plannedStateChanges = ArrayList<CharacterStateChange>(linkedChanges.size)
        var lostStateChanges = 0
        if (linkedChanges.isNotEmpty()) {
            val ownerIndex = characterIndex(
                linkedChanges.map { it.characterId }, refs?.characters?.values.orEmpty(), session
            )
            for (change in linkedChanges) {
                val code = refs?.characters?.get(change.characterId.toString())
                val res = tally.note(
                    SnapshotRefResolver.resolveByCode(
                        change.characterId, code, ownerIndex.codeById, ownerIndex.idByCode, ownerIndex.liveIds
                    ),
                    code
                )
                val newCharId = res.id
                if (newCharId == null) lostStateChanges++
                else plannedStateChanges.add(change.copy(characterId = newCharId))
            }
        }

        val livingSame = data.event.code?.takeIf { it.isNotBlank() }
            ?.let { db.timelineDao().getEventByCode(it) != null } ?: false

        return EventPlan(
            data = data,
            universeId = universe.id,
            fieldValues = plannedValues,
            characterIds = plannedChars.toList(),
            novelIds = plannedNovels.toList(),
            changeCodes = data.relationshipChangeCodes.orEmpty(),
            stateChanges = plannedStateChanges,
            losses = RestoreLossCounts(
                fieldValues = lostValues,
                mergedFieldValues = mergedValues,
                characterLinks = lostChars,
                novelLinks = lostNovels,
                stateChanges = lostStateChanges,
                universeCleared = universe.cleared
            ),
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = tally.previewOnly,
            duplicatesLivingEntity = livingSame
        )
    }

    private suspend fun applyEvent(plan: EventPlan, session: RestoreSession?): RestoreResult {
        // 미리보기 전용 계획은 아직 존재하지 않는 대상을 0번 id로 가리킨다 — 쓰면 오배정이다.
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        val source = plan.data.event
        var event = source.copy(universeId = plan.universeId)
        val existingCode = event.code?.takeIf { it.isNotBlank() }
        if (existingCode == null || db.timelineDao().getEventByCode(existingCode) != null) {
            event = event.copy(code = generateEntityCode())
        }
        val newId: Long = if (db.timelineDao().getEventById(event.id) == null) {
            db.timelineDao().insert(event)
            event.id
        } else {
            db.timelineDao().insert(event.copy(id = 0))
        }

        // 같은 작업의 뒤 항목이 옛 코드로 이것을 찾을 수 있게 등록한다 — code가 재발급됐다면
        // 등록하지 않을 경우 뒤 항목이 살아 있던 남의 엔티티에 붙는다(오배정).
        session?.record(TrashSnapshot.TYPE_EVENT, source.code, newId)

        if (plan.fieldValues.isNotEmpty()) {
            db.eventFieldValueDao().insertAll(plan.fieldValues.map { it.copy(id = 0, eventId = newId) })
        }
        for (characterId in plan.characterIds) {
            db.timelineDao().insertCrossRef(TimelineCharacterCrossRef(newId, characterId))
        }
        for (novelId in plan.novelIds) {
            db.timelineDao().insertEventNovelCrossRef(TimelineEventNovelCrossRef(newId, novelId))
        }

        // 출생/사망 이력 되살리기 — 이미 같은 (캐릭터, 키) 이력이 있으면 그 사이 사용자가
        // 다시 만든 것이므로 덮어쓰지 않는다.
        var restoredStateChanges = 0
        for (change in plan.stateChanges) {
            val existing = db.characterStateChangeDao().getChangesByField(change.characterId, change.fieldKey)
            if (existing.isNotEmpty()) continue
            val safeCode = change.code
                ?.takeIf { c -> db.characterStateChangeDao().getChangeByCode(c) == null }
                ?: generateEntityCode()
            db.characterStateChangeDao().insertAll(listOf(change.copy(id = 0, code = safeCode)))
            restoredStateChanges++
        }

        // 사건이 지워질 때 SET_NULL로 끊긴 관계 변화 이력을 되붙인다.
        var lostChanges = 0
        for (code in plan.changeCodes) {
            val change = db.characterRelationshipChangeDao().getChangeByCode(code)
            if (change == null) {
                lostChanges++
                continue
            }
            db.characterRelationshipChangeDao().update(change.copy(eventId = newId))
        }

        return RestoreResult(
            entityType = TrashSnapshot.TYPE_EVENT,
            restoredName = source.description,
            losses = plan.losses.copy(changeLinks = lostChanges),
            relinkedByCode = plan.relinkedByCode,
            // 파생 필드값(생년·나이·생존 여부)은 되돌리지 않는다 — 그 사이 사용자가 고쳤을 수
            // 있어 덮어쓰기가 되기 때문이다. 되살린 이력이 있으면 그 사실을 알린다.
            restoredSemanticStateChanges = restoredStateChanges
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 상태변화 이력
    // ──────────────────────────────────────────────────────────────────────

    private data class StateChangePlan(
        val data: StateChangeSnapshot,
        /** 해석된 주인 캐릭터 id. blocker가 있으면 null이다. */
        val characterId: Long?,
        val relinkedByCode: Int,
        val legacyPayload: Boolean,
        val previewOnly: Boolean,
        val duplicatesLivingEntity: Boolean,
        val blocker: RestoreBlocker?
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_STATE_CHANGE,
            entityName = "${data.change.fieldKey} → ${data.change.newValue}",
            relinkedByCode = relinkedByCode,
            duplicatesLivingEntity = duplicatesLivingEntity,
            legacyPayload = legacyPayload,
            blocker = blocker
        )
    }

    private suspend fun stateChangePlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null
    ): StateChangePlan? {
        val data = parse(snap, StateChangeSnapshot::class.java) ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.change == null) return null

        val refs = data.refs
        val tally = RestoreTally(legacy = refs == null, pendingCodes = pendingCodes)
        val code = data.characterCode ?: refs?.characters?.get(data.change.characterId.toString())
        val index = characterIndex(
            listOf(data.change.characterId), listOfNotNull(code), session
        )
        val res = tally.note(
            SnapshotRefResolver.resolveByCode(
                data.change.characterId, code, index.codeById, index.idByCode, index.liveIds
            ),
            code
        )

        // 같은 code의 이력이 아직 살아 있으면 이 스냅샷은 삭제 백업이 아니다(그 사이 다시
        // 만들어졌거나 편집 직전 백업이다) — 복원하면 되돌리기가 아니라 사본이 하나 더 생긴다.
        //
        // 특수키(`__birth`·`__death`·`__alive`)는 code가 달라도 마찬가지다. 캐릭터당 하나를
        // 전제로 읽히므로(`SemanticFieldSyncHelper.findStateChange`는 첫 건을 쓴다) 지운 뒤
        // 생년 필드를 고치면 앱이 **새 code로** 하나를 다시 만들어 둔다. code만 보면 "없다"고
        // 판단해 조용히 두 번째 줄을 만들고, 그때부터 어느 쪽이 진짜인지 알 수 없게 된다.
        val dao = db.characterStateChangeDao()
        val ownerId = res.id?.takeIf { it != RestoreTally.PENDING_ID }
        // **주인 캐릭터까지 대조한다.** `getChangeByCode`는 전역 조회라 code가 일치하는 행이
        // 남의 캐릭터 것일 수 있는데, 그것을 "이미 있다"로 세면 이 캐릭터의 이력이 조용히
        // 복원되지 않는다(반대로 덮어쓰기로 처리하면 남의 이력을 파괴한다).
        val sameCodeLives = data.change.code?.takeIf { it.isNotBlank() }
            ?.let { c -> dao.getChangeByCode(c)?.characterId == ownerId && ownerId != null } ?: false
        val sameSlotLives = data.change.fieldKey in SINGLETON_STATE_KEYS &&
            ownerId != null &&
            dao.getChangesByField(ownerId, data.change.fieldKey).isNotEmpty()
        val mode = RestoreModes.stateChangeRestoreMode(sameCodeLives, sameSlotLives)

        return StateChangePlan(
            data = data,
            characterId = res.id,
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = tally.previewOnly,
            // 이제 복제를 만들지 않고 막으므로 '사본이 생긴다'는 경고는 사실이 아니다.
            duplicatesLivingEntity = false,
            // 이력은 캐릭터 없이 존재할 수 없다(FK). 아무 캐릭터에나 붙이면 남의 기록이 되므로
            // 되살리지 않고 스냅샷을 남긴다 — 캐릭터를 먼저 복원한 뒤 다시 시도할 수 있다(R-4).
            blocker = when {
                res.id == null -> RestoreBlocker.MISSING_CHARACTER
                mode == StateChangeRestoreMode.SKIP_EXISTING -> RestoreBlocker.ALREADY_EXISTS
                else -> null
            }
        )
    }

    private suspend fun applyStateChange(plan: StateChangePlan): RestoreResult {
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        check(plan.blocker == null) { "복원할 수 없는 상태변화 계획이 적용 단계까지 왔다" }
        val characterId = requireNotNull(plan.characterId)
        val source = plan.data.change

        // code는 유니크 — 그 사이 같은 code가 다시 쓰이고 있으면 재발급한다(복원이 실패하는
        // 것보다 코드를 새로 받는 편이 낫다. 되살아나는 내용은 같다).
        val safeCode = source.code
            ?.takeIf { it.isNotBlank() && db.characterStateChangeDao().getChangeByCode(it) == null }
            ?: generateEntityCode()
        db.characterStateChangeDao().insertAll(
            listOf(source.copy(id = 0, characterId = characterId, code = safeCode))
        )

        // 복원도 값 쓰기 경로다 — 삽입 경로(insertStateChange)가 수확하는 것을 여기서 빠뜨리면
        // 되살린 값이 값 라이브러리에서만 사라진 채로 남는다. 특수키(__birth 등)는 이 함수가
        // 스스로 걸러 낸다.
        FieldValueLibraryRepository(db)
            .harvestStateChange(characterId, source.fieldKey, source.newValue)

        return RestoreResult(
            entityType = TrashSnapshot.TYPE_STATE_CHANGE,
            restoredName = "${source.fieldKey} → ${source.newValue}",
            relinkedByCode = plan.relinkedByCode,
            // 파생 필드값(생년·나이·생존 여부)은 되돌리지 않는다 — 사건 복원과 같은 규약이다.
            // 되살린 것이 출생·사망 이력이면 그 사실을 함께 알린다.
            restoredSemanticStateChanges = if (
                source.fieldKey == CharacterStateChange.KEY_BIRTH ||
                source.fieldKey == CharacterStateChange.KEY_DEATH
            ) 1 else 0
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 — 등급 체계 (U-1)
    // ──────────────────────────────────────────────────────────────────────

    private data class GradeSystemPlan(
        val data: GradeSystemSnapshot,
        /** 해석된 세계관 id. blocker가 있으면 null이다. */
        val universeId: Long?,
        val relinkedByCode: Int,
        val legacyPayload: Boolean,
        val previewOnly: Boolean,
        val blocker: RestoreBlocker?
    ) {
        fun toPreview() = RestorePreview(
            entityType = TrashSnapshot.TYPE_GRADE_SYSTEM,
            entityName = data.gradeSystem.name,
            relinkedByCode = relinkedByCode,
            legacyPayload = legacyPayload,
            blocker = blocker
        )
    }

    private suspend fun gradeSystemPlan(
        snap: TrashSnapshot,
        pendingCodes: Set<String> = emptySet(),
        session: RestoreSession? = null
    ): GradeSystemPlan? {
        val data = parse(snap, GradeSystemSnapshot::class.java) ?: return null
        @Suppress("SENSELESS_COMPARISON")
        if (data.gradeSystem == null || data.gradeSystem.name == null ||
            data.gradeSystem.gradesJson == null || data.gradeSystem.code == null
        ) return null

        val tally = RestoreTally(legacy = data.refs == null, pendingCodes = pendingCodes)
        val uCode = data.universeCode ?: data.refs?.universeCode
        val universeId = uCode?.let { code ->
            session?.lookup(TrashSnapshot.TYPE_UNIVERSE, code)
                ?: db.universeDao().getUniverseByCode(code)?.id
                ?: if (code in pendingCodes) RestoreTally.PENDING_ID else null
        }

        // 같은 code의 체계가 아직 살아 있으면 복원은 되돌리기가 아니라 사본이다 —
        // 상태변화 이력과 같은 규약으로 막고, 스냅샷은 남긴다(R-4).
        val sameCodeLives = db.gradeSystemDao().getByCode(data.gradeSystem.code) != null

        return GradeSystemPlan(
            data = data,
            universeId = universeId,
            relinkedByCode = tally.relinked,
            legacyPayload = tally.legacyGuess,
            previewOnly = universeId == RestoreTally.PENDING_ID,
            blocker = when {
                universeId == null -> RestoreBlocker.MISSING_UNIVERSE
                sameCodeLives -> RestoreBlocker.ALREADY_EXISTS
                else -> null
            }
        )
    }

    private suspend fun applyGradeSystem(plan: GradeSystemPlan): RestoreResult {
        check(!plan.previewOnly) { "미리보기 전용 계획으로 복원을 시도했다" }
        check(plan.blocker == null) { "복원할 수 없는 등급 체계 계획이 적용 단계까지 왔다" }
        val universeId = requireNotNull(plan.universeId)
        val source = plan.data.gradeSystem

        // 이름은 세계관 안 유니크 — 그 사이 같은 이름이 다시 쓰이고 있으면 변형해 되살린다
        // (복원이 실패하는 것보다 "이름 (2)"로 돌아오는 편이 낫다. 내용은 같다).
        var name = source.name
        var suffix = 2
        while (db.gradeSystemDao().getByUniverseAndName(universeId, name) != null) {
            name = "${source.name} ($suffix)"
            suffix++
        }
        // code 유니크 — 계획이 살아 있는 같은 code를 막았지만, 트랜잭션 안에서 한 번 더 확인한다.
        val safeCode = source.code
            .takeIf { it.isNotBlank() && db.gradeSystemDao().getByCode(it) == null }
            ?: generateEntityCode()
        db.gradeSystemDao().insert(source.copy(id = 0, universeId = universeId, name = name, code = safeCode))

        // 삭제 시점에 참조하던 필드를 다시 잇는다 — 단, **아직 독자 표이고 라벨 집합이 삭제
        // 시점 그대로인 필드만**. 그 사이 다른 체계를 골랐거나 라벨을 고쳤다면 그 편집이
        // 우선이다(복원이 사용자의 이후 편집을 덮어쓰면 되돌리기가 아니라 파괴다).
        val systemGrades = GradeSystemRef.gradesFromJson(source.gradesJson)
        var relinked = 0
        var lostLinks = 0
        for (ref in plan.data.referencingFields.orEmpty()) {
            val key = ref?.key
            val entityType = ref?.entityType
            if (key == null || entityType == null) { lostLinks++; continue }
            val field = db.fieldDefinitionDao().getFieldByKey(universeId, key, entityType)
            if (field == null || field.type != com.novelcharacter.app.data.model.FieldType.GRADE.name ||
                GradeSystemRef.codeFromConfig(field.config) != null
            ) { lostLinks++; continue }
            val current = GradeSystemRef.gradesFromConfig(field.config)
            if (current.keys != systemGrades.keys) { lostLinks++; continue }
            val overrides = GradeSystemRef.deriveOverrides(current, systemGrades)
            db.fieldDefinitionDao().update(
                field.copy(config = GradeSystemRef.materialize(field.config, safeCode, systemGrades, overrides))
            )
            relinked++
        }

        return RestoreResult(
            entityType = TrashSnapshot.TYPE_GRADE_SYSTEM,
            restoredName = name,
            losses = RestoreLossCounts(gradeSystemLinks = lostLinks),
            relinkedByCode = plan.relinkedByCode + relinked
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 해석 보조
    // ──────────────────────────────────────────────────────────────────────


    /**
     * 두 참조가 모두 **실제** id인가 — 미리보기의 보류 자리표시자([RestoreTally.PENDING_ID])는
     * 서로 다른 대상이 전부 같은 0으로 보이므로 중복(수렴) 판정에서 빼야 한다.
     */
    private fun isResolved(vararg ids: Long): Boolean = ids.none { it == RestoreTally.PENDING_ID }

    /** 세계관 해석 결과 — id가 null이면 못 찾은 것이고 [cleared]는 '있었는데 못 찾음'이다. */
    private data class UniverseResolution(val id: Long?, val cleared: Boolean)

    private suspend fun resolveUniverse(
        oldId: Long?, code: String?, tally: RestoreTally, session: RestoreSession?
    ): UniverseResolution {
        if (oldId == null) return UniverseResolution(null, false)
        val index = buildIndex(
            oldIds = listOf(oldId),
            codes = listOfNotNull(code),
            fetchByIds = { ids -> ids.mapNotNull { db.universeDao().getUniverseById(it) } },
            fetchByCodes = { codes -> codes.mapNotNull { db.universeDao().getUniverseByCode(it) } },
            idOf = { it.id },
            codeOf = { it.code },
            entityType = TrashSnapshot.TYPE_UNIVERSE,
            session = session
        )
        val res = tally.note(
            SnapshotRefResolver.resolveByCode(oldId, code, index.codeById, index.idByCode, index.liveIds),
            code
        )
        return UniverseResolution(res.id, !res.found)
    }

    private suspend fun characterIndex(
        oldIds: Collection<Long>, codes: Collection<String>, session: RestoreSession?
    ) = buildIndex(
        oldIds = oldIds,
        codes = codes,
        fetchByIds = { db.characterDao().getCharactersByIds(it) },
        fetchByCodes = { db.characterDao().getCharactersByCodes(it) },
        idOf = { it.id },
        codeOf = { it.code },
        entityType = TrashSnapshot.TYPE_CHARACTER,
        session = session
    )

    private suspend fun factionIndex(
        oldIds: Collection<Long>, codes: Collection<String>, session: RestoreSession?
    ) = buildIndex(
        oldIds = oldIds,
        codes = codes,
        fetchByIds = { db.factionDao().getByIds(it) },
        fetchByCodes = { db.factionDao().getByCodes(it) },
        idOf = { it.id },
        codeOf = { it.code },
        entityType = TrashSnapshot.TYPE_FACTION,
        session = session
    )

    private suspend fun novelIndex(
        oldIds: Collection<Long>, codes: Collection<String>, session: RestoreSession?
    ) = buildIndex(
        oldIds = oldIds,
        codes = codes,
        fetchByIds = { db.novelDao().getNovelsByIds(it) },
        fetchByCodes = { db.novelDao().getNovelsByCodes(it) },
        idOf = { it.id },
        codeOf = { it.code },
        entityType = TrashSnapshot.TYPE_NOVEL,
        session = session
    )

    private suspend fun eventIndex(
        oldIds: Collection<Long>, codes: Collection<String>, session: RestoreSession?
    ) = buildIndex(
        oldIds = oldIds,
        codes = codes,
        fetchByIds = { db.timelineDao().getEventsByIds(it) },
        fetchByCodes = { db.timelineDao().getEventsByCodes(it) },
        idOf = { it.id },
        codeOf = { it.code },
        entityType = TrashSnapshot.TYPE_EVENT,
        session = session
    )

    /**
     * 한 번의 '작업 전체 복원'이 공유하는 상태 — **코드 재발급 추적**.
     *
     * apply*는 같은 code가 이미 살아 있으면 유니크 제약을 피해 code를 재발급한다. 그런데
     * 뒤이어 복원되는 하위 항목은 payload의 **옛 코드**로 조회하므로, 그대로 두면 방금 만든
     * 사본이 아니라 **살아 있던 남의 엔티티**를 찾아 거기에 붙는다 — 정확히 R-1이 막으려는
     * 오배정이다(엑셀 덮어쓰기로 같은 코드가 되살아난 뒤 휴지통을 복원하면 실제로 일어난다).
     *
     * 재발급하지 않은 경우에도 기록해 둔다 — 결과가 같으므로 분기를 만들 이유가 없다.
     */
    private class RestoreSession {
        private val newIdByTypeAndCode = HashMap<String, Long>()

        fun record(entityType: String, oldCode: String?, newId: Long) {
            if (oldCode.isNullOrBlank()) return
            newIdByTypeAndCode[key(entityType, oldCode)] = newId
        }

        fun lookup(entityType: String, code: String?): Long? {
            if (code.isNullOrBlank()) return null
            return newIdByTypeAndCode[key(entityType, code)]
        }

        // 타입을 키에 섞는다 — 코드는 사실상 전역 유일하지만, 종류가 다른 두 엔티티가
        // 같은 문자열을 갖게 되는 날에도 서로를 덮지 않게 한다.
        private fun key(entityType: String, code: String) = entityType + "\u0000" + code
    }

    /** 해석에 필요한 최소 인덱스 — 스냅샷이 실제로 참조하는 id/코드만 조회한다. */
    private class RefIndex(
        val codeById: Map<Long, String>,
        val idByCode: Map<String, Long>,
        val liveIds: Set<Long>
    )

    /**
     * 참조 인덱스를 **일괄 조회로** 만든다.
     *
     * 단건 조회를 참조 수만큼 반복하면 세계관 복원(참조 수백~수천 건)에서 무너진다 —
     * '받쳐주는 확장성' 기준. IN 절 변수 한도를 피하려 청크로 나눈다.
     */
    private suspend fun <T : Any> buildIndex(
        oldIds: Collection<Long>,
        codes: Collection<String>,
        fetchByIds: suspend (List<Long>) -> List<T>,
        fetchByCodes: suspend (List<String>) -> List<T>,
        idOf: (T) -> Long,
        codeOf: (T) -> String?,
        entityType: String? = null,
        session: RestoreSession? = null
    ): RefIndex {
        val codeById = HashMap<Long, String>()
        val idByCode = HashMap<String, Long>()
        val liveIds = HashSet<Long>()
        val distinctIds = oldIds.distinct()
        for (chunk in distinctIds.chunked(IN_CLAUSE_CHUNK)) {
            for (row in fetchByIds(chunk)) {
                val id = idOf(row)
                liveIds.add(id)
                codeOf(row)?.takeIf { it.isNotBlank() }?.let { codeById[id] = it }
            }
        }
        val distinctCodes = codes.distinct().filter { it.isNotBlank() }
        for (chunk in distinctCodes.chunked(IN_CLAUSE_CHUNK)) {
            for (row in fetchByCodes(chunk)) {
                codeOf(row)?.takeIf { it.isNotBlank() }?.let { idByCode[it] = idOf(row) }
            }
        }
        // 같은 작업이 방금 복원하며 만든 사본이 있으면 **그쪽이 이긴다** — DB 조회는 옛 코드로
        // 살아 있던 원본을 찾아낼 수 있고, 그러면 뒤 항목이 사본이 아니라 원본에 붙는다(오배정).
        //
        // idByCode에 넣는 것만으로는 부족하다: `resolveByCode`는 `codeById[oldId] == code`면
        // idByCode를 **보지도 않고** 옛 id를 확정한다(ID_CONFIRMED). 원본이 옛 id에 그대로
        // 살아 있는 '편집 직전 백업' 복원이 정확히 그 상황이라, 그 지름길을 함께 끊어야 한다.
        if (entityType != null && session != null) {
            for (code in distinctCodes) {
                val restoredId = session.lookup(entityType, code) ?: continue
                idByCode[code] = restoredId
                val stale = codeById.filterValues { it == code }.keys.filter { it != restoredId }
                for (oldId in stale) codeById.remove(oldId)
            }
        }
        return RefIndex(codeById, idByCode, liveIds)
    }

    private class FieldDefIndex(
        val naturalById: Map<Long, FieldDefNaturalKey>,
        val idByNatural: Map<FieldDefNaturalKey, Long>
    )

    private suspend fun buildFieldDefIndex(
        oldIds: Collection<Long>,
        refs: Collection<FieldDefRef>,
        session: RestoreSession?
    ): FieldDefIndex {
        val naturalById = HashMap<Long, FieldDefNaturalKey>()
        val idByNatural = HashMap<FieldDefNaturalKey, Long>()
        // 복원 경로는 스냅샷 생성용 캐시를 쓰지 않는다. 그 캐시는 삭제 트랜잭션 한 번을 위한
        // 것인데, 휴지통 화면이 쓰는 인스턴스는 앱 수명 내내 살아 있다 — 세계관이 지워졌다
        // 다시 만들어지면 같은 id에 다른 코드가 붙고, 캐시된 옛 코드로 해석하면 **오배정**이
        // 된다(오배정은 생략보다 나쁘다 — R-1). 복원 한 번의 지역 캐시만 쓴다.
        val localUniverseCodes = HashMap<Long, String?>()
        // id 조회는 일괄로 — 캐릭터마다 필드 수만큼 단건 질의하면 세계관 복원이 수만 번을 돈다.
        val defsById = HashMap<Long, com.novelcharacter.app.data.model.FieldDefinition>()
        for (chunk in oldIds.distinct().chunked(IN_CLAUSE_CHUNK)) {
            for (fd in db.fieldDefinitionDao().getFieldsByIds(chunk)) defsById[fd.id] = fd
        }
        for ((id, fd) in defsById) {
            val uCode = localUniverseCodes.getOrPut(fd.universeId) {
                db.universeDao().getUniverseById(fd.universeId)?.code?.takeIf { it.isNotBlank() }
            }
            naturalById[id] = FieldDefNaturalKey(uCode, fd.entityType, fd.key)
        }
        // 세계관 코드 → id도 캐시한다. ref가 30개면 같은 세계관을 30번 조회하던 자리다.
        val universeIdByCode = HashMap<String, Long?>()
        for (ref in refs) {
            val uCode = ref.universeCode?.takeIf { it.isNotBlank() } ?: continue
            val type = ref.entityType?.takeIf { it.isNotBlank() } ?: continue
            val key = ref.key?.takeIf { it.isNotBlank() } ?: continue
            if (idByNatural.containsKey(FieldDefNaturalKey(uCode, type, key))) continue
            // 세계관도 같은 작업이 방금 복원하며 code를 재발급했을 수 있다 — 그쪽이 이긴다.
            val universeId = universeIdByCode.getOrPut(uCode) {
                session?.lookup(TrashSnapshot.TYPE_UNIVERSE, uCode)
                    ?: db.universeDao().getUniverseByCode(uCode)?.id
            } ?: continue
            val fd = db.fieldDefinitionDao().getFieldByKey(universeId, key, type) ?: continue
            idByNatural[FieldDefNaturalKey(uCode, type, key)] = fd.id
        }
        return FieldDefIndex(naturalById, idByNatural)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 영구 삭제 / 정리
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 영구 삭제 — 보류 중이던 이미지 파일도 함께 지운다.
     * 단, 라이브러리(image_meta)·다른 스냅샷·살아있는 엔티티가 여전히 쓰는 파일은 남긴다
     * (경로 공유 하 무음 파괴 방지). pruneIfNeeded의 자동 purge에도 동일하게 적용된다.
     */
    suspend fun purgeSnapshot(snapshotId: Long): Unit = withContext(Dispatchers.IO) {
        val item = trashDao.getImagesById(snapshotId) ?: return@withContext
        purgeAll(listOf(item))
    }

    /** 작업 하나를 통째로 영구 삭제한다. @return 지운 항목 수 */
    suspend fun purgeOperation(opKey: String, editBackup: Boolean = false): Int = withContext(Dispatchers.IO) {
        val (opId, legacyId) = operationLookup(opKey)
        val items = trashDao.getImagesByOperation(opId, legacyId, editBackup)
        purgeAll(items)
        items.size
    }

    /** 휴지통 비우기 — 영구 삭제한 스냅샷 개수를 반환한다(결과 통보용) */
    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val all = trashDao.getAllImages()
        val total = trashDao.count()
        purgeAll(all, deleteEverything = true)
        total
    }

    /**
     * 여러 스냅샷을 함께 영구 삭제한다.
     *
     * **보호 집합은 한 번만 계산한다.** 스냅샷마다 계산하면 그때마다 전 엔티티·전 스냅샷을
     * 훑어 항목 수의 제곱이 된다 — 휴지통이 캐릭터 전용(최대 30행)일 때는 견뎠지만 세계관
     * 삭제 하나가 수백 항목을 만드는 지금은 '휴지통 비우기'가 그대로 멈춘다.
     *
     * 지울 스냅샷 **전부**를 보호에서 제외한 뒤 계산한다 — 하나씩 제외하면 함께 지우는
     * 형제 스냅샷이 서로를 보호해 파일이 영원히 남는다.
     */
    private suspend fun purgeAll(snapshots: List<TrashSnapshotImages>, deleteEverything: Boolean = false) {
        if (snapshots.isEmpty() && !deleteEverything) return
        val candidates = snapshots.flatMap { parseImagePathStrings(it.imagePaths) }
        if (candidates.isNotEmpty()) {
            try {
                com.novelcharacter.app.util.ImageOwnershipGuard.deleteIfUnprotectedBatch(
                    db, null, candidates,
                    excludeTrashSnapshotIds = snapshots.map { it.id }.toSet()
                )
            } catch (_: Exception) {
                // 가드 실패 시 파일은 남긴다(삭제보다 보존이 안전) — 스냅샷 행 삭제는 계속 진행.
            }
        }
        // 행 단위 자동커밋을 피한다 — 수만 행이면 커밋 수만 번이 된다.
        if (deleteEverything) {
            trashDao.deleteAll()
        } else {
            db.withTransaction {
                snapshots.map { it.id }.chunked(IN_CLAUSE_CHUNK).forEach { trashDao.deleteByIds(it) }
            }
        }
    }

    /**
     * 보관 기한/개수 초과분 자동 정리 — 스냅샷 추가 후 (트랜잭션 커밋 뒤) 호출.
     *
     * **정리 단위는 작업이다**(B-14). 한 삭제가 만든 백업은 통째로 남거나 통째로 사라진다 —
     * 반쪽으로 남으면 "세계관은 되살아나는데 캐릭터 71명은 없는" 백업이 되고, 그것은 복원이
     * 아니라 조용한 유실이다.
     *
     * **이 인스턴스가 방금 만든 작업은 정리 대상에서 제외한다**(R-3). 한도를 넘긴 잔여분은
     * 다음 삭제 작업의 정리에서 자연히 소진된다.
     */
    suspend fun pruneIfNeeded() {
        // (작업, 종류) 단위 계획(S-4) — purge가 종류까지 맞는 행만 지우므로 계획도 같은 축.
        // 종류를 모르던 종전 구현은 순수 편집 백업 작업을 뽑고도 0행을 지워, 편집 백업이
        // 기한·한도 어느 쪽으로도 정리되지 않고 무한 축적됐다(한도 계산까지 왜곡).
        val operations = trashDao.getOperationsOldestFirst().map {
            TrashPruneSelector.Operation(it.opKey, it.newestAt, it.itemCount, it.editBackup)
        }
        if (operations.isEmpty()) return
        val targets = TrashPruneSelector.plan(
            operations,
            System.currentTimeMillis() - TrashSnapshot.RETENTION_MS,
            protectedOperationKeys,
            TrashSnapshot.MAX_OPERATIONS
        )
        for (t in targets) purgeOperation(t.key, editBackup = t.editBackup)
    }

    private fun parseImagePathStrings(json: String): List<String> {
        return try {
            gson.fromJson<List<String?>>(json, GsonTypes.STRING_LIST)?.filterNotNull() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun Long?.asList(): List<Long> = if (this == null) emptyList() else listOf(this)

    /**
     * 사용자가 손댄 값 라이브러리 엔트리인가 — 자동 수확분은 실제 필드값에서 다시 만들어지므로
     * 스냅샷이 담을 이유가 없다(담으면 payload만 부풀어 백업을 읽을 수 없게 된다).
     */
    private fun FieldValueEntry.isCurated(): Boolean =
        source != FieldValueEntry.SOURCE_AUTO ||
            displayLabel.isNotBlank() ||
            category.isNotBlank() ||
            description.isNotBlank() ||
            isHidden ||
            aliases().isNotEmpty()

    private companion object {
        /** IN 절 변수 한도 회피용 청크 크기 (저장소 공통 관례와 동일) */
        const val IN_CLAUSE_CHUNK = 900

        /**
         * payload 한 행의 크기 예산(문자 수).
         *
         * Android의 CursorWindow는 기본 2MB이고 **한 행이 그것을 넘으면 읽을 수 없다** —
         * 백업이 통째로 사라지는 것과 같다. UTF-8·UTF-16 확장과 Gson 이스케이프를 감안해
         * 넉넉히 낮춰 잡는다. 예산을 넘기면 다음 행으로 넘어간다(유실 없음).
         */
        const val PAYLOAD_BUDGET_CHARS = 400_000

        /**
         * 캐릭터당 **하나만** 있는 것으로 읽히는 상태변화 키.
         *
         * 일반 필드의 이력은 여러 줄이 정상이다(그것이 '상태변화'다). 이 셋만은 시맨틱 동기화가
         * 첫 건을 진실로 쓰므로, 복원이 두 번째 줄을 만들면 어느 쪽이 진짜인지 알 수 없게 된다.
         */
        val SINGLETON_STATE_KEYS = setOf(
            CharacterStateChange.KEY_BIRTH,
            CharacterStateChange.KEY_DEATH,
            CharacterStateChange.KEY_ALIVE
        )
    }
}
