package com.novelcharacter.app.excel

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FactionRelationship
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.DefaultFieldTemplate
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.UserPresetTemplate
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import com.novelcharacter.app.util.CharacterValueLedger
import com.novelcharacter.app.util.DuelCandidateFilter
import com.novelcharacter.app.util.FactionStanding
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.FormulaValidator
import com.novelcharacter.app.util.ImportLookupIndex
import com.novelcharacter.app.util.ImportedFormulaAudit
import com.novelcharacter.app.util.PresetLimit
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.withImagePaths
import com.novelcharacter.app.util.GradeValueResolver
import com.novelcharacter.app.util.FactionMembershipMatcher
import com.novelcharacter.app.util.FactionRelationshipMatcher
import org.apache.poi.ss.usermodel.CellType
// B-8(스트리밍 배선): 이 서비스는 이제 POI 타입이 아니라 [ImportSource]의 추상화를 읽는다.
// DOM([DomImportWorkbook])과 스트리밍([StreamingImportWorkbook])이 같은 인터페이스를 구현하므로
// 아래 5,700줄의 per-row 해석 로직은 **두 경로에서 글자 그대로 같은 코드**다(로직 비분기).
// 별칭으로 둔 이유: 이름을 바꾸면 본문 120여 곳이 함께 바뀌어, Android 계층을 로컬에서
// 컴파일할 수 없는 이 저장소에서 검증되지 않는 대형 diff가 된다. 접근면은 ImportSource.kt 참조.
import com.novelcharacter.app.excel.ImportRow as Row
import com.novelcharacter.app.excel.ImportSheet as Sheet
import com.novelcharacter.app.excel.ImportWorkbook as Workbook

data class ImportProgress(
    val currentPhase: String,
    val processedRows: Int,
    val totalRows: Int
)

/**
 * 이름 은행의 자연키 — **행과 기존 항목이 같은 식으로 만들어야** 매칭이 어긋나지 않는다.
 * 구분자가 NUL인 것은 이름·성별에 나타날 수 없어 두 값이 섞이지 않기 때문이다.
 *
 * 최상위에 두는 것은 중첩 클래스(`NameBankRowValues`)가 바깥 클래스의 멤버를 볼 수 없기 때문이다.
 */
private fun nameBankKey(name: String, gender: String): String = "$name\u0000$gender"

private fun NameBankEntry.mapKeyForNameBank(): String = nameBankKey(name, gender)

/**
 * 미리보기가 '코드 백필'을 예고할 때 쓰는 자리표시자.
 * 실제 발급은 가져오기가 하고, 미리보기는 **발급이 일어난다는 사실**만 알면 된다.
 */
private const val CODE_BACKFILL_PREVIEW = "(미리보기: 코드 발급 예정)"

data class CategoryAnalysis(
    val key: String,
    val label: String,
    val inBackup: Int,
    val newCount: Int,
    val updateCount: Int,
    val unchangedCount: Int,
    val existingTotal: Int,
    /**
     * **이번 가져오기가 실행하지 않을 행 수** (B-102 ⓑ).
     *
     * 행이 가리키는 선행 항목(세계관·캐릭터·관계)이 지금 DB에 없는데 **그것을 만들 범주가
     * 이번 선택에서 빠져 있으면** 가져오기는 그 행을 경고와 함께 건너뛴다.
     * 종전에는 그것을 '신규'로 세어, 실제로는 아무것도 들어가지 않는데 "신규 N건"이라 말했다.
     *
     * 선행 범주가 함께 선택돼 있으면 가져오기가 그것을 먼저 만들므로 '신규'가 맞다 —
     * **정상 복원에서는 이 값이 0이다.**
     */
    val skippedCount: Int = 0
) {
    /** 백업에 없고 DB에만 있는 항목 수 (덮어쓰기 시 삭제 대상) */
    val onlyInDb: Int get() = existingTotal - (updateCount + unchangedCount)
}

data class RestoreAnalysis(
    val categories: List<CategoryAnalysis>,
    val characterConflicts: List<CharacterConflict> = emptyList()
)

enum class ConflictResolution {
    SKIP,
    CREATE_NEW,
    UPDATE_EXISTING
}

/**
 * 동적 필드 열 하나 — **연표 시트의 사건 필드**와 **작품 시트의 작품 필드**가 함께 쓴다.
 *
 * [resolved]가 non-null이면 내보내기 규칙의 역함수로 필드가 확정된 열이다(이름·세계관명에
 * 괄호가 있어도 안전). null이면 헤더 추측 파싱 결과이므로 그 행의 세계관에서 이름으로 조회한다.
 */
data class EventFieldColumn(
    val colIndex: Int,
    val header: String,
    val fieldName: String,
    val universeName: String?,
    val resolved: FieldDefinition?
)

data class CharacterConflict(
    val excelRowIndex: Int,
    val sheetName: String,
    val excelName: String,
    val excelNovelTitle: String?,
    val existingCharacters: List<Character>,
    var resolution: ConflictResolution = ConflictResolution.CREATE_NEW,
    var selectedExistingId: Long? = null,
    var cleanupOldFields: Boolean = false
)

data class ImportResult(
    var newUniverses: Int = 0,
    var updatedUniverses: Int = 0,
    var newNovels: Int = 0,
    var updatedNovels: Int = 0,
    var newFields: Int = 0,
    var updatedFields: Int = 0,
    var newGradeSystems: Int = 0,
    var updatedGradeSystems: Int = 0,
    var deletedGradeSystems: Int = 0,
    // 전역 기본 필드 템플릿 (B-119)
    var newDefaultFields: Int = 0,
    var updatedDefaultFields: Int = 0,
    /**
     * `기본필드코드`가 가리키는 템플릿을 찾지 못해 **일반 필드로 둔** 연결 수.
     * 거부가 아니라 수용·교정이며, 조용히 버리지 않으므로 결과 창이 이 수를 말한다(설계 1-5).
     */
    var demotedDefaultFieldLinks: Int = 0,
    var newCharacters: Int = 0,
    var updatedCharacters: Int = 0,
    var newEvents: Int = 0,
    var updatedEvents: Int = 0,
    var newStateChanges: Int = 0,
    var updatedStateChanges: Int = 0,
    var newRelationships: Int = 0,
    var updatedRelationships: Int = 0,
    var newRelationshipChanges: Int = 0,
    var updatedRelationshipChanges: Int = 0,
    var newNameBank: Int = 0,
    var updatedNameBank: Int = 0,
    var newPresetTemplates: Int = 0,
    var updatedPresetTemplates: Int = 0,
    var newSearchPresets: Int = 0,
    var updatedSearchPresets: Int = 0,
    var newListPresets: Int = 0,
    var updatedListPresets: Int = 0,
    var newFactions: Int = 0,
    var updatedFactions: Int = 0,
    var newFactionMemberships: Int = 0,
    var updatedFactionMemberships: Int = 0,
    var newFactionRelationships: Int = 0,
    var updatedFactionRelationships: Int = 0,
    var deletedCharacters: Int = 0,
    var deletedRelationships: Int = 0,
    var deletedEvents: Int = 0,
    var deletedStateChanges: Int = 0,
    var deletedRelationshipChanges: Int = 0,
    var deletedNameBank: Int = 0,
    var deletedFields: Int = 0,
    var deletedFactions: Int = 0,
    var deletedFactionMemberships: Int = 0,
    var deletedFactionRelationships: Int = 0,
    var restoredSettings: Int = 0,
    var newImageMeta: Int = 0,
    var updatedImageMeta: Int = 0,
    var newFieldValueEntries: Int = 0,
    var updatedFieldValueEntries: Int = 0,
    var newDuelAxes: Int = 0,
    var updatedDuelAxes: Int = 0,
    var newDuelMatches: Int = 0,
    var updatedDuelMatches: Int = 0,
    var newDuelVerdicts: Int = 0,
    var updatedDuelVerdicts: Int = 0,
    /**
     * **찾아 맞췄으나 고칠 것이 없던 행** (B-111 · 확정 7-2).
     *
     * `updated*`가 *실제로 바뀐 행*만 세게 되면서 생긴 자리다. 이것이 없으면 아무것도 고치지
     * 않은 파일을 다시 넣었을 때 **모든 수가 0이 되어 결과창이 *"데이터 없음"*이라 말한다** —
     * 파일에는 데이터가 가득했고 단지 바꿀 것이 없었을 뿐인데, 사용자는 **가져오기가 실패했거나
     * 파일이 빈 것으로 읽는다.** 고치려던 거짓말(*"갱신 10"*)을 더 나쁜 거짓말로 바꾸는 셈이다.
     *
     * 그래서 *"바뀐 것 없음"*과 *"아무것도 없음"*을 가른다 — 왕복 멱등 확인(A7)이
     * 그 둘을 반드시 구별해야 하는 자리다.
     */
    var unchangedRows: Int = 0,
    var skippedRows: Int = 0,
    val errors: MutableList<String> = mutableListOf(),
    var nameBasedMappings: Int = 0,
    var autoRepairedValues: Int = 0,
    var newCodesGenerated: Int = 0,
    var clearedFields: Int = 0,
    /**
     * U-12b: 엑셀에서 행이 사라졌지만 삭제 옵션이 꺼져 있어 **그대로 남겨 둔** 항목 수.
     * [DeleteOptions]가 전부 기본 false라 엑셀에서 행을 지워도 앱 데이터는 지워지지 않는다(안전한 기본값).
     * 그 기본값을 바꾸지 않되, 기대 어긋남("엑셀에서 지웠는데 왜 남아 있지")을 요약에서 바로 해소한다.
     */
    var keptNotInExcel: Int = 0,
    val warnings: MutableList<String> = mutableListOf(),
    val pendingConflicts: MutableList<String> = mutableListOf()
)

class ExcelImportService(private val db: AppDatabase, private val appContext: android.content.Context? = null) {

    /** 캐릭터 행 임포트 중 별칭 표기 감지용 필드별 해석기 캐시 — 행마다 쿼리하지 않는다 (검토 A7) */
    private val importAliasResolvers = HashMap<Long, com.novelcharacter.app.util.FieldValueResolver>()

    private val novelIdCache = mutableMapOf<Pair<String, Long?>, Long?>()
    // 캐릭터의 세계관(작품→세계관) 캐시 — 세력 참조의 동명 해소 힌트용. 행마다 쿼리하지 않는다.
    private val novelUniverseCache = mutableMapOf<Long, Long?>()
    private var truncatedFieldCount = 0
    private val truncatedDetails = mutableListOf<String>()
    // 병합 셀 해석 (B-7) — 시트별 병합 범위 캐시와 좌상단 값 적용 집계 (경고로 고지).
    // 같은 셀이 두 번 읽혀도 한 번만 세도록 셀 좌표로 중복 제거한다 — 부풀린 개수는 거짓 고지다.
    private val mergedCellMaps = HashMap<Sheet, MergedCellMap>()
    private val mergedFilledCells = HashSet<String>()
    private val mergedFilledBySheet = LinkedHashMap<String, Int>()

    // F3-A: 엑셀 세계관 이동을 편집화면과 동일한 P0 로직으로 처리하기 위한 리포지토리 (지연 생성)
    private val characterRepository by lazy {
        CharacterRepository(
            db, db.characterDao(), db.characterFieldValueDao(),
            db.characterStateChangeDao(), db.characterTagDao(),
            db.characterRelationshipDao(), db.nameBankDao()
        )
    }

    // 임포트 후 시맨틱 동기화 대상 (characterId → universeId)
    private val pendingSyncCharacters = mutableMapOf<Long, Long>()

    // 이 임포트가 쓰는 단 하나의 휴지통 저장소 — 정리(pruneIfNeeded)는 커밋 이후에 수행한다.
    // **인스턴스를 공유해야 한다**: 정리는 "이 작업이 방금 만든 스냅샷"을 보호하는데,
    // 스냅샷을 만든 인스턴스와 정리하는 인스턴스가 다르면 보호 목록이 비어 방금 만든 백업을
    // 그대로 태운다. 한 임포트가 스냅샷을 남기는 경로는 두 곳(세계관 이동·엑셀에 없는 캐릭터
    // 삭제)이라 둘 다 이 인스턴스를 거쳐야 한다.
    private var trashForPrune: com.novelcharacter.app.data.repository.TrashRepository? = null

    /** 이 임포트 전용 휴지통 저장소 (없으면 만든다) — 스냅샷을 남기는 모든 경로가 공유한다. */
    private fun trashForImport(): com.novelcharacter.app.data.repository.TrashRepository {
        val existing = trashForPrune
        if (existing != null) return existing
        val created = com.novelcharacter.app.data.repository.TrashRepository(db)
        trashForPrune = created
        return created
    }

    // 세력 자동 관계 생성 대기열 (factionId → characterId). 관계 시트 처리 후에 소비한다.
    private val pendingAutoRelationMemberships = mutableListOf<Pair<Long, Long>>()

    // "엑셀에 없는 항목 삭제" 옵션용 — 임포트 중 매칭된 entity ID 추적
    // 엑셀이 인지한 캐릭터 전역 보호집합 — 세계관별로 나누지 않는다.
    // 시트의 세계관과 캐릭터의 실제 세계관은 다를 수 있고(엑셀 편집으로 작품 이동),
    // 삭제 판정은 실제 세계관 기준이라 분리하면 "엑셀에 있는데 삭제"가 발생한다.
    private val matchedCharacterIds = mutableSetOf<Long>()
    // 실제로 헤더 검증을 통과해 처리한 캐릭터 시트의 세계관 — 삭제 범위를 이 세계관들로 한정한다
    private val importedCharacterSheetUniverseIds = mutableSetOf<Long>()
    private var unclassifiedSheetImported = false

    // 캐릭터 시트로 이미 소비된 시트명 — 같은 시트를 세계관용·미분류용으로 두 번 돌지 않게 한다.
    private val consumedCharacterSheetNames = mutableSetOf<String>()

    // 세계관 이름이 '미분류 캐릭터'와 겹치는가 — 두 캐릭터 시트가 같은 이름을 다투는 상태.
    private var unclassifiedNameCollidesWithUniverse = false

    /**
     * U-3: 그 겹침이 **신규 형식으로 확정**됐는가 — 겹치는 세계관이 접미사 시트('… (2)')를
     * 헤더 확인까지 거쳐 잡았다는 사실(= assignSheetName의 ownerOf 규칙대로 밀려난 배치)이다.
     * 확정이면 배정이 모호하지 않으므로 경고를 **사실 고지로 낮춘다**.
     * 이 값은 **문구만 가른다** — 어느 시트를 누가 갖는지의 판정은 findSheetForUniverse가 그대로 한다.
     */
    private var unclassifiedUniverseTookSuffixedSheet = false

    // 예약 시트로 실제로 읽은 시트명 — '인식되지 않아 무시되었습니다' 경고에서 제외한다.
    // (읽지 않은 시트까지 억제하면 무음 유실이 된다)
    private val consumedSheetNames = mutableSetOf<String>()
    // 캐릭터 시트가 열로 이미 처리한 (캐릭터, 필드) 쌍 — '캐릭터 필드값' 시트와 다투지 않게 한다.
    // 초기화를 빠뜨리면 연속 가져오기에서 이전 실행의 쌍이 남아 정상 행을 무시한다(무음 유실).
    private val importedCharFieldPairs = mutableSetOf<Pair<Long, Long>>()
    // 같은 규약의 작품·사건판 (B-65) — 각 시트의 필드 열이 이미 처리한 (엔티티, 필드) 쌍.
    // 오버플로 시트가 같은 항목을 다시 쓰면 **본 시트에서 지운 값이 되살아난다**(F1-A 비움 의도).
    private val importedNovelFieldPairs = mutableSetOf<Pair<Long, Long>>()
    private val importedEventFieldPairs = mutableSetOf<Pair<Long, Long>>()
    // 이번 가져오기에서 세계관이 바뀐 캐릭터 — 필드값이 새 세계관 필드로 재매핑되었으므로
    // 옛 세계관 키를 담은 오버플로 행을 적용하면 방금 정리한 값이 되살아난다.
    private val universeMovedCharacterIds = mutableSetOf<Long>()
    // (캐릭터, 필드) 값의 현재 상태 장부 — 캐릭터 시트와 '캐릭터 필드값' 시트가 **함께** 쓴다(B-72 ②).
    // 둘이 같은 항목을 다룰 수 있으므로 장부도 하나여야 한다. 위 형제들과 같이 가져오기마다 비운다.
    private val valueLedger = CharacterValueLedger()
    /**
     * 캐릭터 **정체성** 색인 — 코드로 찾는 자리 (B-210. 값 쪽은 바로 위 [valueLedger]다).
     *
     * 캐릭터를 코드로 되찾는 시트가 다섯이다(캐릭터 · 캐릭터 필드값 · 연표 참가자 · 상태변화 ·
     * 이름 은행). 시트마다 따로 캐시하면 **캐릭터 시트가 방금 만든 캐릭터**를 뒷 시트가 못 보는
     * 창이 생기므로 색인은 하나여야 한다 — 그래서 시트가 아니라 **가져오기** 수명이고,
     * 캐릭터를 쓰는 유일한 자리([importCharacterRows])가 insert·update마다 갱신한다.
     *
     * 위 형제들과 같이 **가져오기마다 비운다** — 두 가져오기 사이에 사용자가 앱에서 캐릭터를
     * 고칠 수 있어, 안 비우면 둘째 가져오기가 DB가 아니라 지난번 사본을 보고 판단한다.
     */
    private val characterCodes = ImportLookupIndex<String, Character>(
        idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
    )
    /**
     * 같은 색인의 **이름** 축 — `getCharacterByName`(LIMIT 1)과 `getAllCharactersByName`이
     * 함께 쓴다. 이름은 코드와 달리 **겹치는 것이 정상**(동명이인)이라 `all`로 받아
     * 호출부가 모호성을 판정한다. 캐릭터 시트는 이름을 **고칠 수 있으므로**, 갱신은
     * [ImportLookupIndex.put]이 옛 이름을 끊어 주는 것에 기댄다.
     */
    private val characterNames = ImportLookupIndex<String, Character>(
        idOf = { it.id }, keyOf = { it.name }
    )
    /** 색인을 실었는가 — 가져오기마다 한 번만 읽는다(빈 DB 복원에서도 한 번은 친다). */
    private var characterIndexLoaded = false
    /**
     * 사건 정체성 색인 — 캐릭터와 같은 이유로 **가져오기** 수명이다 (B-210).
     * 사건을 되찾는 자리가 셋인데(연표 · 사건 필드값 · 관계 변화의 연결사건) 시트마다 따로
     * 캐시하면 **연표가 방금 만든 사건**을 뒤 둘이 못 보는 창이 생긴다. 쓰는 자리는
     * [importTimeline] 하나이고 그것이 insert·update마다 갱신한다.
     */
    private val eventCodes = ImportLookupIndex<String, TimelineEvent>(
        idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
    )
    /** 코드 없는 구버전 파일의 폴백 축 — `getEventByNaturalKey(연도, 설명)`의 자리. */
    private val eventNaturalKeys = ImportLookupIndex<EventNaturalKey, TimelineEvent>(
        idOf = { it.id }, keyOf = { EventNaturalKey(it.year, it.description) }
    )
    /** `getEventById`의 자리 — 구버전 파일의 '연결사건ID' 실존 검증이 쓴다. */
    private val eventsByEventId = ImportLookupIndex<Long, TimelineEvent>(
        idOf = { it.id }, keyOf = { it.id }
    )
    private var eventIndexLoaded = false
    /**
     * 작품 **코드** 색인 — 캐릭터·사건과 같은 이유로 가져오기 수명이다 (B-210).
     * 작품을 코드로 되찾는 자리가 셋이다(작품 시트 · 캐릭터 시트의 소속 · '작품 필드값').
     * 쓰는 자리는 [importNovels]와 [resolveNovelId] 둘이고, 둘 다 insert 뒤에 갱신한다.
     */
    private val novelCodes = ImportLookupIndex<String, Novel>(
        idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
    )
    private var novelIndexLoaded = false
    private val matchedEventIds = mutableSetOf<Long>()
    private val matchedRelationshipIds = mutableSetOf<Long>()
    private val matchedRelationshipChangeIds = mutableSetOf<Long>()
    private val matchedStateChangeIds = mutableSetOf<Long>()
    private val matchedNameBankIds = mutableSetOf<Long>()
    private val matchedFactionIds = mutableSetOf<Long>()
    private val matchedFactionMembershipIds = mutableSetOf<Long>()

    /**
     * 덮어쓰기로 **이번 가져오기가 직접 비운** 범주.
     *
     * 그 범주에서 "코드가 기존에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요"는
     * **앱이 스스로 지운 결과를 두고 사용자에게 되묻는 것**이라 행마다 알리지 않는다.
     * 덮어쓰기는 ① 먼저 전량 삭제 ② 그다음 행 읽기 순서라 **모든 행이 예외 없이** 이 분기에
     * 걸린다 — 실사용 보고에서 경고 376건 중 372건이 이 한 문구였고, 상세 창은 20건까지만
     * 보여 주므로 **진짜 경고(이름 기반 매칭·타입 불일치 등)를 볼 방법이 사라졌다.**
     * 병합에서는 값어치가 크므로(앱에서 지운 항목이 옛 파일로 되살아나는 것을 잡는다) 그대로 둔다.
     */
    private val wipedByOverwrite = mutableSetOf<String>()

    /** 위 범주에서 새로 만들어진 행 수 — 무음이 아니라 범주당 한 줄로 요약해 고지한다. */
    private val createdAfterWipe = mutableMapOf<String, Int>()
    private val matchedFactionRelationshipIds = mutableSetOf<Long>()
    // OVERWRITE에서 백업과 매칭된 필드 정의 id — 사전 deleteAll 대신 잔여분만 정리하기 위한 추적
    private val matchedFieldDefinitionIds = mutableSetOf<Long>()
    // 동상 — 등급 체계 (U-1)
    private val matchedGradeSystemIds = mutableSetOf<Long>()

    // Phase 1에서 FK 참조를 deferred 처리 (코드 기반 해석)
    private val deferredUniverseImageCharCodes = mutableMapOf<Long, String>()  // universeId → charCode
    private val deferredUniverseImageNovelCodes = mutableMapOf<Long, String>() // universeId → novelCode
    private val deferredNovelImageCharCodes = mutableMapOf<Long, String>()     // novelId → charCode

    // 세계관을 '이름'으로 매칭했을 때의 파일코드 → 기기코드 별칭.
    // 프리셋 필드 필터처럼 세계관 이름 열 없이 코드만 실려 오는 참조가 그 이름 매칭 결정을 따라가게 한다.
    // deferred 맵과 동형으로 시트 처리 순서에 의존하지 않게 서비스 수준에 쌓고, 실행 시작 시 비운다.
    // 수집 출처는 세계관 시트(정체성 소유)로 한정한다 — 작품·세력·연표의 '세계관' 열은 단순 참조라
    // 오타 한 줄이 필터를 엉뚱한 세계관으로 돌릴 수 있다.
    private val universeCodeAliases = UniverseCodeAliases()


    /** 가져올 이미지의 경로 재매핑: {원본경로 → 새경로} */
    var imagePathRemap: Map<String, String> = emptyMap()

    /** 단일 이미지 경로를 재매핑. 매핑 없으면 원본 반환. */
    private fun remapImagePath(path: String): String {
        if (imagePathRemap.isEmpty() || path.isBlank()) return path
        return imagePathRemap[path] ?: path
    }

    /**
     * `대표이미지` 셀을 캐릭터에 반영한다(B-103 D8).
     *
     * 해석은 `RepresentativeImageCell`이 다섯 단으로 한다 — 정확 일치 → 재매핑 → 그 행 안에서
     * 유일한 파일명 → 1부터 세는 번호 → **못 찾으면 경고 + 기존 값 유지.**
     * 마지막 단이 이 기능의 규약이다: 외부에서 편집된 파일의 오류를 이유로 사용자가 정해 둔
     * 대표를 조용히 날리지 않는다(개발 의도 2번·4번).
     */
    private fun applyRepresentativeCell(
        character: Character,
        cell: String?,
        characterName: String,
        rowIndex: Int,
        result: ImportResult?
    ): Character {
        val paths = com.novelcharacter.app.util.CharacterRepresentativeImage.paths(character.imagePaths)
        return when (val r = com.novelcharacter.app.util.RepresentativeImageCell.resolve(cell, paths, imagePathRemap)) {
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Absent -> character
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Cleared ->
                character.copy(representativeImagePath = "")
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Matched ->
                character.copy(representativeImagePath = r.path)
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Unresolved -> {
                result?.warnings?.add(
                    "캐릭터 행 $rowIndex ($characterName): '대표이미지' 값 \"${r.raw}\"에 해당하는 이미지를 찾을 수 없어 기존 대표 지정을 유지했습니다"
                )
                character
            }
        }
    }

    /** imagePaths JSON 배열 내 모든 경로를 재매핑. 레거시 단일 경로도 JSON 배열로 변환. */
    private fun remapImagePaths(imagePathsJson: String): String {
        if (imagePathsJson.isBlank() || imagePathsJson == "[]") return "[]"
        return try {
            val gson = com.google.gson.Gson()
            val paths = gson.fromJson(imagePathsJson, Array<String>::class.java)
                ?: return "[]"
            if (imagePathRemap.isEmpty()) gson.toJson(paths) else {
                val remapped = paths.map { remapImagePath(it) }
                gson.toJson(remapped)
            }
        } catch (_: Exception) {
            // 레거시: 단일 경로 문자열 → JSON 배열로 변환
            if (imagePathsJson.isNotBlank()) {
                val remapped = remapImagePath(imagePathsJson)
                org.json.JSONArray(listOf(remapped)).toString()
            } else "[]"
        }
    }

    suspend fun importAll(
        workbook: Workbook,
        options: ExportOptions = ExportOptions(),
        strategy: ImportStrategy = ImportStrategy.MERGE,
        resolvedConflicts: Map<String, CharacterConflict> = emptyMap(),
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult {
        val result = ImportResult()
        novelIdCache.clear()
        novelUniverseCache.clear()
        pendingSyncCharacters.clear()
        importAliasResolvers.clear()
        processedRowsSoFar = 0
        truncatedFieldCount = 0
        wipedByOverwrite.clear()
        createdAfterWipe.clear()
        truncatedDetails.clear()
        mergedCellMaps.clear()
        mergedFilledCells.clear()
        mergedFilledBySheet.clear()
        trashForPrune = null

        val totalRows = countTotalRows(workbook)

        // OVERWRITE 전략 시 CASCADE 종속 카테고리를 자동 포함하여 데이터 정합성 보장.
        // 예: 세계관 덮어쓰기 → 필드 정의·세력이 CASCADE 삭제되므로 재가져오기 필수.
        val effectiveOptions = if (strategy == ImportStrategy.OVERWRITE) {
            options.copy(
                // 세계관 삭제 → 필드 정의, 세력 CASCADE 삭제 → 재가져오기 필수
                fieldDefinitions = options.fieldDefinitions || options.universes,
                factions = options.factions || options.universes,
                // 세력 삭제 → 세력 소속·세력 관계 CASCADE 삭제 → 재가져오기 필수.
                // 캐릭터 삭제도 세력 소속을 CASCADE로 지운다(characterId FK).
                factionMemberships = options.factionMemberships || options.factions ||
                    options.universes || options.characters,
                factionRelationships = options.factionRelationships || options.factions || options.universes,
                // 캐릭터 삭제 → 관계, 상태변화 CASCADE 삭제 → 재가져오기 필수
                relationships = options.relationships || options.characters,
                relationshipChanges = options.relationshipChanges || options.characters || options.relationships,
                stateChanges = options.stateChanges || options.characters,
                // 캐릭터 삭제 → 사건-캐릭터 연결(crossref)도 CASCADE 삭제된다.
                // 사건 자체는 남고 참가자 연결만 사라지므로 연표를 재가져오지 않으면 복구되지 않는다.
                timeline = options.timeline || options.characters
            )
        } else {
            options
        }

        // 전체를 단일 트랜잭션으로 감싸서 부분 커밋 방지
        // Room은 중첩 withTransaction을 savepoint로 처리하므로 phase별 격리 유지
        db.withTransaction {
            // 덮어쓰기 전략: 선택된 카테고리의 기존 데이터를 먼저 삭제
            // CASCADE 안전 순서: 종속 데이터 → 상위 엔티티
            if (strategy == ImportStrategy.OVERWRITE) {
                // 덮어쓰기의 대원칙: **백업이 복원할 수 없는 것은 지우지 않는다.**
                // 시트가 없는 카테고리를 지우면 되돌릴 방법이 전혀 없으므로(휴지통도 거치지 않는다)
                // 모든 삭제를 "백업에 유효한 시트가 있는가"로 가드하고, 건너뛴 경우 사용자에게 알린다.
                // 삭제 가드와 실제 조회는 **반드시 같은 판정**이어야 한다. 가드만 정확 일치로
                // 두면 findSheet가 접미사 시트를 되찾아 읽는데 가드는 "시트가 없다"고 판단해,
                // 덮어쓰기가 조용히 병합으로 바뀌고 서로 모순되는 경고가 함께 뜬다.
                // 판정은 순수 계층 [OverwriteGuard]가 든다(B-88) — "시트가 있는가"가 아니라
                // **"데이터 행이 1개 이상인가"**다. 사유·경계는 그 파일의 KDoc에 있다.
                fun classify(spec: SheetSpec): RestoreSource =
                    OverwriteGuard.classify(resolveSpecSheet(workbook, spec)?.lastRowNum)
                /** 이 spec의 시트를 근거로 기존 데이터를 지워도 되는가(= 데이터 행이 있는가). */
                fun canRestore(spec: SheetSpec): Boolean = classify(spec) == RestoreSource.HAS_ROWS
                /** 선택됐고 백업으로 복원 가능할 때만 true. 복원 불가면 삭제를 건너뛰고 사용자에게 알린다. */
                fun shouldDelete(enabled: Boolean, spec: SheetSpec): Boolean {
                    if (!enabled) return false
                    // 시트가 없는 것과 비어 있는 것을 갈라 말한다 — 사용자가 할 일이 다르다
                    // (전자는 다시 내보내기, 후자는 그 시트에 행을 적기).
                    return when (classify(spec)) {
                        RestoreSource.HAS_ROWS -> true
                        RestoreSource.EMPTY -> {
                            result.warnings.add("'${spec.sheetName}' 시트에 데이터 행이 없어 기존 데이터를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)")
                            false
                        }
                        RestoreSource.MISSING -> {
                            result.warnings.add("백업에 '${spec.sheetName}' 시트가 없어 기존 데이터를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)")
                            false
                        }
                    }
                }
                // 캐릭터는 세계관별 시트 + 미분류 시트로 나뉘므로 별도 판정
                // 캐릭터 시트도 같은 규칙이다(B-88) — 헤더만 있는 시트는 복원 재료가 아니다.
                // 내보내기가 캐릭터 0명인 세계관에도 시트를 만들게 됐으므로, 헤더 검사만 두면
                // **캐릭터가 하나도 없는 세계관의 빈 시트 하나가 전 캐릭터 삭제를 허가한다.**
                fun charSheetRestorable(sheet: Sheet?): Boolean =
                    sheet != null &&
                        sheet.getRow(0)?.let { isValidHeader(it, "이름") } == true &&
                        OverwriteGuard.canRestore(sheet.lastRowNum)
                val charactersRestorable = db.universeDao().getAllUniversesList().any { u ->
                    charSheetRestorable(findSheetForUniverse(workbook, u.name, RESERVED_SHEET_NAMES))
                } || charSheetRestorable(findUnclassifiedSheet(workbook))

                if (shouldDelete(effectiveOptions.relationshipChanges, relationshipChangeSpec())) db.characterRelationshipChangeDao().deleteAll()
                if (shouldDelete(effectiveOptions.relationships, relationshipSpec())) db.characterRelationshipDao().deleteAll()
                if (shouldDelete(effectiveOptions.factionMemberships, factionMembershipSpec())) db.factionMembershipDao().deleteAll()
                if (shouldDelete(effectiveOptions.factionRelationships, factionRelationshipSpec())) db.factionRelationshipDao().deleteAll()
                if (shouldDelete(effectiveOptions.factions, factionSpec())) { db.factionDao().deleteAll(); wipedByOverwrite.add("factions") }
                if (shouldDelete(effectiveOptions.stateChanges, stateChangeSpec())) db.characterStateChangeDao().deleteAll()
                // 대결(B-104) — **축을 지우면 그 아래 판이 CASCADE로 함께 죽는다.** 그래서
                // 축 시트만 보고 지우면 *"기록 시트가 빈 파일"* 하나가 수만 판을 없앤다.
                // 대원칙 그대로 — 백업이 복원할 수 없는 것은 지우지 않는다: 기록 시트도 함께
                // 복원 가능하거나, 애초에 지울 판이 없을 때만 축을 비운다.
                if (shouldDelete(effectiveOptions.duels, duelAxisSpec())) {
                    val matchesRestorable = canRestore(duelMatchSpec())
                    val existingMatches = db.duelMatchDao().countAll()
                    if (matchesRestorable || existingMatches == 0) {
                        db.duelAxisDao().deleteAll()
                    } else {
                        result.warnings.add(
                            "'${duelMatchSpec().sheetName}' 시트에 데이터 행이 없어 대결 축을 삭제하지 않고 유지했습니다 " +
                                "(축을 지우면 쌓인 판 ${existingMatches}개가 함께 사라지는데 이 파일로는 되살릴 수 없습니다)"
                        )
                    }
                }
                if (shouldDelete(effectiveOptions.timeline, timelineSpec(emptyList()))) {
                    db.timelineDao().deleteAllCrossRefs()
                    db.timelineDao().deleteAllEvents()
                }
                if (effectiveOptions.characters) {
                    // 캐릭터 시트는 그 시트 세계관의 필드만 열로 담는다. 미분류 캐릭터·타 세계관 잔여
                    // 필드값은 '캐릭터 필드값' 시트로만 복원되므로, 그 시트가 없는 백업(구버전 파일)이면
                    // deleteAll의 FK CASCADE로 영구 소멸한다.
                    // 대원칙 그대로 — 백업이 복원할 수 없는 것은 지우지 않는다. shouldDelete와 같은 판정이다.
                    // (휴지통 스냅샷으로 때우지 않는다: 스냅샷은 fieldDefinitionId를 담는데 세계관을 함께
                    //  덮어쓰면 그 id가 재발급되어 복원해도 필드값이 하나도 살아나지 않는다.)
                    val unrestorable =
                        if (charactersRestorable && !canRestore(characterFieldValueSpec())) countUnrestorableFieldValues()
                        else null
                    when {
                        !charactersRestorable ->
                            result.warnings.add("백업에 캐릭터 시트가 없어 기존 캐릭터를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)")
                        unrestorable != null && unrestorable.values > 0 ->
                            result.warnings.add(
                                "백업에 '캐릭터 필드값' 시트가 없어 캐릭터 ${unrestorable.characters}명의 필드값 ${unrestorable.values}건을 " +
                                "되돌릴 수 없으므로 기존 캐릭터를 삭제하지 않고 유지했습니다 (덮어쓰기 제외) — " +
                                "이 값들은 미분류 캐릭터이거나 다른 세계관 필드를 가리켜 캐릭터 시트로는 복원되지 않습니다. " +
                                "완전한 덮어쓰기를 원하면 지금 데이터를 최신 형식으로 한 번 내보낸 뒤 다시 시도하세요"
                            )
                        else -> { db.characterDao().deleteAll(); wipedByOverwrite.add("characters") }
                    }
                }
                // 값 라이브러리(필드 데이터)는 시트가 있을 때만 비우고 재구성한다.
                if (shouldDelete(effectiveOptions.fieldDefinitions, fieldValueLibrarySpec())) {
                    db.fieldValueEntryDao().deleteAll()
                }
                if (shouldDelete(effectiveOptions.novels, novelSpec(emptyList()))) { db.novelDao().deleteAll(); wipedByOverwrite.add("novels") }
                // 세계관 삭제는 FK CASCADE로 필드 정의 → 캐릭터·사건 필드값·값 라이브러리까지 연쇄 삭제한다.
                // 따라서 '세계관' 시트만이 아니라 '필드 정의' 시트도 있어야 복원 가능하다.
                // (이 연쇄 때문에 pruneUnmatchedFieldDefinitions의 id 보존은 세계관을 함께 덮어쓸 때 성립하지 않는다)
                if (effectiveOptions.universes) {
                    val uSpec = universeSpec()
                    val fdSpec = fieldDefinitionSpec(emptyList())
                    when {
                        !canRestore(uSpec) ->
                            result.warnings.add("백업에 '${uSpec.sheetName}' 시트가 없어 기존 세계관을 삭제하지 않고 유지했습니다 (덮어쓰기 제외)")
                        !canRestore(fdSpec) ->
                            result.warnings.add("백업에 '${fdSpec.sheetName}' 시트가 없어 세계관을 삭제하지 않았습니다 — 세계관을 지우면 모든 필드 정의와 캐릭터·사건 필드값이 함께 사라지는데 복원할 수 없습니다")
                        else -> {
                            db.universeDao().deleteAll()
                            wipedByOverwrite.add("universes")
                            result.warnings.add("덮어쓰기: 세계관을 삭제하면서 필드 정의와 모든 필드값이 함께 삭제되었습니다 — 백업의 '필드 정의'·'필드 데이터'·캐릭터 시트로 재구성됩니다")
                        }
                    }
                }
                if (shouldDelete(effectiveOptions.nameBank, nameBankSpec())) db.nameBankDao().deleteAll()
                if (shouldDelete(effectiveOptions.presetTemplates, userPresetTemplateSpec())) db.userPresetTemplateDao().deleteAll()
                if (shouldDelete(effectiveOptions.searchPresets, searchPresetSpec())) db.searchPresetDao().deleteAll()
                if (shouldDelete(effectiveOptions.characterListPresets, characterListPresetSpec())) db.characterListPresetDao().deleteAll()
                // 이미지 태그는 image_meta FK CASCADE로 함께 삭제 — 파일은 지우지 않는다(백업 재가져오기로 보호 복원).
                // meta는 미배정 이미지의 유일한 삭제 보호막이라 복원하지 못하면 이후 고아 정리가 파일까지 지운다.
                if (shouldDelete(effectiveOptions.imageMeta, imageMetaSpec())) db.imageMetaDao().deleteAll()
            }

            // Matched ID 추적 초기화 (deleteNotInExcel 옵션용)
            matchedCharacterIds.clear()
            importedCharacterSheetUniverseIds.clear()
            unclassifiedSheetImported = false
            consumedCharacterSheetNames.clear()
            consumedSheetNames.clear()
            unclassifiedNameCollidesWithUniverse = false
            unclassifiedUniverseTookSuffixedSheet = false
            importedCharFieldPairs.clear()
            importedNovelFieldPairs.clear()
            importedEventFieldPairs.clear()
            universeMovedCharacterIds.clear()
            valueLedger.reset()
            resetCharacterIndex()
            resetEventIndex()
            resetNovelIndex()
            matchedEventIds.clear()
            matchedRelationshipIds.clear()
            matchedRelationshipChangeIds.clear()
            matchedStateChangeIds.clear()
            matchedNameBankIds.clear()
            matchedFactionIds.clear()
            matchedFactionMembershipIds.clear()
            matchedFactionRelationshipIds.clear()
            matchedFieldDefinitionIds.clear()
            matchedGradeSystemIds.clear()
            pendingAutoRelationMemberships.clear()

            // Phase 1: Schema definitions (universes, novels, field definitions)
            // imageCharacterId/imageNovelId는 null로 deferred 처리 (FK 안전)
            deferredUniverseImageCharCodes.clear()
            deferredUniverseImageNovelCodes.clear()
            deferredNovelImageCharCodes.clear()
            universeCodeAliases.clear()
            if (effectiveOptions.universes) importUniverses(workbook, result, onProgress, totalRows)
            if (effectiveOptions.fieldDefinitions) {
                // 등급 체계를 **필드 정의보다 먼저** — '필드 정의' 시트의 '등급체계' 열이 여기서
                // 만든 체계를 찾아야 한다. 뒤에 두면 신규 기기 복원(빈 DB)에서 같은 파일 안에
                // 체계가 실려 있는데도 참조가 전부 독자 표로 강등된다(확-3 "순서가 열보다 위험하다").
                importGradeSystems(workbook, result, onProgress, totalRows)
                // 전역 기본 필드도 **필드 정의보다 먼저** — '필드 정의' 시트의 '기본필드코드'가
                // 여기서 만든 템플릿을 찾아야 한다(B-119, 설계 1-5). 등급 체계와 같은 근거다.
                importDefaultFieldTemplates(workbook, result, onProgress, totalRows)
                importFieldDefinitions(workbook, result, onProgress, totalRows)
                // OVERWRITE: 사전 deleteAll 대신 매칭 후 잔여 정의만 정리 — 매칭된 정의는 id가 보존되어
                // 캐릭터·사건 필드값과 값 라이브러리가 FK CASCADE로 전멸하지 않는다 (위 삭제 블록 주석 참조)
                if (strategy == ImportStrategy.OVERWRITE) {
                    pruneUnmatchedFieldDefinitions(workbook, result)
                    pruneUnmatchedGradeSystems(workbook, result)
                }
                importFieldValueLibrary(workbook, result, onProgress, totalRows)
            }
            // **필드 정의 다음에 작품을 가져온다**(확-3). 작품 시트의 '필드:' 열은 정의를 찾아야
            // 값이 붙는데, 신규 기기 복원(빈 DB)에서 작품이 먼저 오면 그 열이 전부 해석에 실패해
            // "'필드 정의' 시트를 함께 가져오세요"라는 **거짓 경고**와 함께 값이 유실된다 —
            // 같은 파일 안에 정의가 실려 있는데도. 연표(사건 필드)가 이미 정의 뒤에 있는 이유와 같다.
            if (effectiveOptions.novels) {
                importNovels(workbook, result, onProgress, totalRows)
                // 작품 시트가 열로 담지 못한 값 — 작품 시트 다음이어야 "작품 시트 우선" 판정에
                // 쓸 (작품, 필드) 쌍이 채워져 있다 ('캐릭터 필드값'과 같은 순서 근거)
                importNovelFieldValues(workbook, result, onProgress, totalRows)
            }
            // Novel 임포트 완료 (또는 기존 DB 유지) → Universe의 imageNovelId 코드 해석
            applyDeferredUniverseNovelRefs(result)

            // Phase 2: Entity data (characters)
            if (effectiveOptions.characters) {
                importCharacterSheets(workbook, result, resolvedConflicts, onProgress, totalRows)
                importUnclassifiedCharacters(workbook, result, resolvedConflicts, onProgress, totalRows)
                // 캐릭터 시트가 열로 담지 못한 값(미분류·타 세계관 잔여분) — 캐릭터 시트 다음에 처리해야
                // "캐릭터 시트 우선" 판정에 쓸 (캐릭터, 필드) 쌍이 모두 채워져 있다
                importCharacterFieldValues(workbook, result, onProgress, totalRows)
            }
            // Character 임포트 완료 → imageCharacterId 코드 해석 가능
            applyDeferredCharacterRefs(result)

            // Phase 3: Relationships and references
            if (effectiveOptions.timeline) {
                importTimeline(workbook, result, onProgress, totalRows)
                // 연표 시트가 열로 담지 못한 값 — 연표 다음이어야 사건 코드가 다 심겨 있다
                importEventFieldValues(workbook, result, onProgress, totalRows)
            }
            if (effectiveOptions.stateChanges) importStateChanges(workbook, result, onProgress, totalRows)
            // 세력을 관계보다 먼저 가져온다 — 관계 시트의 세력(자동 관계) 연결이
            // 신규 기기 복원(빈 DB)에서도 해석되게 한다 (뒤에 두면 factionId가 전부 유실돼 수동 관계로 강등)
            if (effectiveOptions.factions) importFactions(workbook, result, onProgress, totalRows)
            if (effectiveOptions.factionMemberships) importFactionMemberships(workbook, result, onProgress, totalRows)
            if (effectiveOptions.factionRelationships) importFactionRelationships(workbook, result, onProgress, totalRows)
            if (effectiveOptions.relationships) importRelationships(workbook, result, onProgress, totalRows)
            // 관계 시트가 권위 — 시트가 기술하지 않은 co-member 쌍만 자동 관계로 채운다
            drainPendingAutoRelations(result)
            if (effectiveOptions.relationshipChanges) importRelationshipChanges(workbook, result, onProgress, totalRows)
            if (effectiveOptions.nameBank) importNameBank(workbook, result, onProgress, totalRows)

            // Phase 4: User settings and presets
            if (effectiveOptions.presetTemplates) importUserPresetTemplates(workbook, result, onProgress, totalRows)
            if (effectiveOptions.searchPresets) importSearchPresets(workbook, result, onProgress, totalRows)
            // 목록 프리셋은 작품코드를 해석하므로 작품 임포트(Phase 1) 이후에 처리
            if (effectiveOptions.characterListPresets) importCharacterListPresets(workbook, result, onProgress, totalRows)
            // 앱 설정은 DataStore/SharedPreferences에 쓰므로 DB 트랜잭션이 되돌리지 못한다 →
            // 커밋 이후 블록으로 뺐다(아래 참조). 여기에 두면 롤백 시 "실패했다고 알리면서 설정만 바뀐"
            // 부분 커밋이 된다.
            if (effectiveOptions.imageMeta) importImageMeta(workbook, result, onProgress, totalRows)

            // 대결(B-104) — **축 → 기록 → 상성** 순서가 규약이다(정의가 기록보다 앞이다).
            // 캐릭터보다 뒤인 것도 필수다: 판의 참가자를 이름으로 되찾는 경로가 캐릭터를 본다.
            if (effectiveOptions.duels) {
                importDuelAxes(workbook, result, onProgress, totalRows)
                importDuelMatches(workbook, result, onProgress, totalRows)
                importDuelVerdicts(workbook, result, onProgress, totalRows)
            }

            // 덮어쓰기로 비운 범주의 신규 생성은 여기서 한 줄로 요약한다(행마다 알리지 않는다).
            reportCreatedAfterWipe(result)

            // Phase 5: 엑셀에 없는 항목 삭제 (MERGE + deleteOptions)
            if (strategy == ImportStrategy.MERGE) {
                if (effectiveOptions.deleteOptions.hasAny) {
                    deleteUnmatchedEntities(effectiveOptions, result)
                    // 이 패스는 **표에서 행을 지운다** — 정체성 색인이 든 사본은 그 순간 낡는다.
                    // 지금은 뒤에서 읽는 자리가 없지만(Phase 6은 DAO·리포지터리로 간다),
                    // 색인을 살려 두면 다음에 뒤쪽에 붙는 코드가 **지워진 행을 찾아낸다.**
                    // 비워 두면 그때는 표를 다시 읽는다 — 늦게 틀리느니 다시 읽는 편이 낫다 (B-210).
                    resetCharacterIndex()
                    resetEventIndex()
                    resetNovelIndex()
                }
                // U-12b: 꺼진 종류는 남겨 뒀다는 사실을 그 자리에서 고지한다(삭제 뒤 최종 상태 기준).
                countKeptUnmatchedEntities(effectiveOptions, result)
            }

            // Phase 6: 시맨틱 필드 동기화 (출생/사망연도 ↔ 상태변화 ↔ 생존여부)
            if (pendingSyncCharacters.isNotEmpty()) {
                runPostImportSemanticSync()
            }
        }

        // 값 장부를 여기서 놓는다 — **일이 끝났으면 들고 있지 않는다** (B-72 ②).
        // 형제 상태들과 달리 이 장부가 담는 것은 id가 아니라 **행 전체**(문자열 포함)라
        // 규모에서 자릿수가 다르다(×30에서 36,510행). 그런데 이 서비스는 화면과 수명이 같아
        // (`ExcelTransferController` → `ExcelImporter` → 이 객체) 놓지 않으면 **가져오기가 끝난
        // 뒤에도 다음 가져오기까지 그대로 남는다** — 봉우리를 올리지는 않지만 **바닥을 올려**,
        // 곧이어 도는 내보내기·자동 백업의 봉우리가 그만큼 위에서 시작한다(그 둘이 B-72가
        // 지키는 바로 그 경로다). 앞머리의 `reset()`은 그대로 둔다 — 실패·취소로 여기 못 닿은
        // 가져오기가 남긴 찌꺼기를 다음 실행이 보지 않게 하는 것이 그쪽의 몫이다.
        valueLedger.reset()

        // 앱 설정(테마·백업·이미지 압축)은 DB 트랜잭션이 되돌릴 수 없는 저장소(DataStore/SharedPreferences)에
        // 쓴다. 그래서 커밋이 확정된 뒤에만 적용한다 — 가져오기가 실패하거나 취소되면 설정도 손대지 않은
        // 상태로 남는다. 여기서 실패해도 DB는 이미 커밋됐으므로 전체를 실패로 되돌리지 않고 경고 + 교정 안내를 낸다.
        if (effectiveOptions.appSettings) {
            runCatching { importAppSettings(workbook, result) }.onFailure { e ->
                result.warnings.add(
                    "앱 설정 복원에 실패했습니다 — 데이터 자체는 정상 복원되었습니다. " +
                    "설정 화면에서 테마·백업·이미지 압축 항목을 직접 확인해 주세요 (${e.message})"
                )
            }
        }

        // 휴지통 정리는 커밋 이후 — 트랜잭션 안에서 하면 스냅샷과 정리가 한 단위로 묶여 롤백 시 함께 사라진다
        val trashToPrune = trashForPrune
        if (trashToPrune != null) {
            runCatching { trashToPrune.pruneIfNeeded() }
            trashForPrune = null
        }

        // zip 복원 파일 중 이번 가져오기에서 어떤 엔티티에도 연결되지 않고 meta도 없는 파일은
        // 라이브러리(미배정)로 입양한다 — "이미지"만 체크하고 "이미지 태그·링크"를 끈 복원에서
        // 라이브러리 파일이 어느 UI에도 보이지 않는 고아(이후 정리에 삭제됨)로 남지 않게(변수 제어).
        // adoptOrphans는 참조·meta·휴지통 보호 중인 경로를 스킵하므로 정상 복원분은 건드리지 않는다.
        if (imagePathRemap.isNotEmpty()) {
            val adopted = runCatching {
                com.novelcharacter.app.util.ImageOwnershipGuard.adoptOrphans(db, appContext, imagePathRemap.values)
            }.getOrDefault(0)
            if (adopted > 0) {
                result.warnings.add("복원 이미지 ${adopted}장이 어디에도 연결되지 않아 라이브러리(미배정)로 편입했습니다")
            }
        }

        // 캐릭터 자동 링크 재동기화 — 가져오기는 대량 등록 이벤트다. 새로 들어온 캐릭터의
        // 이미지들을 지금 묶고, 시트가 실어 온 "char:<옛id>" 토큰(다른 기기·재발급 id)도 현재
        // id 기준으로 치유한다. 커밋 이후 부가 단계라 실패해도 가져오기를 되돌리지 않는다
        // (링크는 다음 저장·배정의 재동기화가 수렴시킨다).
        if (effectiveOptions.characters || effectiveOptions.imageMeta) {
            runCatching {
                com.novelcharacter.app.util.CharacterImageAutoLinker.resyncIfEnabled(appContext, db)
            }
        }

        // 인식되지 않은 시트 경고 — 개명된 캐릭터 시트 등이 무통보로 무시되지 않도록 한다
        val recognizedSheets = mutableSetOf(GUIDE_SHEET_NAME, UNCLASSIFIED_SHEET_NAME)
        recognizedSheets.addAll(RESERVED_SHEET_NAMES)
        for (u in db.universeDao().getAllUniversesList()) {
            findSheetForUniverse(workbook, u.name, RESERVED_SHEET_NAMES)?.let { recognizedSheets.add(it.sheetName) }
        }
        // 실제로 읽은 시트만 억제한다. "예약명의 접미사 변형이면 무조건 억제"로 두면,
        // 세계관을 rename/삭제한 뒤 옛 백업을 가져올 때처럼 **아무도 읽지 않은** '세력(2)'까지
        // 조용히 삼켜 무음 유실이 된다.
        recognizedSheets.addAll(consumedSheetNames)
        recognizedSheets.addAll(consumedCharacterSheetNames)
        for (idx in 0 until workbook.numberOfSheets) {
            val name = workbook.getSheetName(idx)
            if (name in recognizedSheets) continue
            result.warnings.add("시트 '$name'은(는) 인식되지 않아 무시되었습니다 — 캐릭터 시트라면 이름이 세계관 이름과 일치해야 합니다")
        }

        if (truncatedFieldCount > 0) {
            val detail = if (truncatedDetails.size <= 5) {
                truncatedDetails.joinToString(", ")
            } else {
                truncatedDetails.take(5).joinToString(", ") + " 외 ${truncatedDetails.size - 5}건"
            }
            result.warnings.add("${truncatedFieldCount}개 필드값이 ${MAX_FIELD_LENGTH}자 제한으로 잘렸습니다. ($detail)")
        }

        if (mergedFilledCells.isNotEmpty()) {
            // B-7: 조용한 보정 금지 — 무엇을 어떻게 해석했는지와 교정 경로를 함께 알린다
            val detail = mergedFilledBySheet.entries.joinToString(", ") { "'${it.key}' ${it.value}건" }
            result.warnings.add(
                "병합 셀 ${mergedFilledCells.size}건을 병합 범위의 좌상단 값으로 해석했습니다 ($detail) — " +
                    "의도한 값이 아니면 엑셀에서 병합을 해제하고 각 칸에 값을 채운 뒤 다시 가져오세요"
            )
        }

        // 값 라이브러리 수확 — 임포트 트랜잭션 커밋 '후' 전체 재수확 + 재계산 (검토 A6/A7).
        // 임포트는 여러 세계관을 건드릴 수 있어 전체 대상이며, 도중에 프로세스가 죽으면
        // harvest_pending 플래그로 다음 앱 시작 시 재시도된다 (수확은 INSERT OR IGNORE 멱등).
        val migrationPrefs = appContext?.getSharedPreferences("app_migrations", android.content.Context.MODE_PRIVATE)
        migrationPrefs?.edit()?.putBoolean("field_library_harvest_pending", true)?.apply()
        val fieldLibrary = com.novelcharacter.app.data.repository.FieldValueLibraryRepository(db)
        // 구버전 파일의 설정(JSON)에 남아 있는 valueLabels/valueCategories → 라이브러리로 이관
        // (조용한 이중 소스 방지 — 시드와 같은 병합 규칙이라 큐레이션을 덮어쓰지 않는다)
        var legacyMigrated = 0
        for (fd in db.fieldDefinitionDao().getAllFieldsAllTypes()) {
            val stats = com.novelcharacter.app.data.model.FieldStatsConfig.fromConfig(fd.config)
            if (stats.valueLabels.isNotEmpty() || stats.valueCategories.isNotEmpty()) {
                legacyMigrated += runCatching { fieldLibrary.seedFromStatsConfig(fd) }.getOrDefault(0)
            }
        }
        if (legacyMigrated > 0) {
            result.warnings.add("구버전 필드 설정의 값 라벨·카테고리 ${legacyMigrated}건을 필드 데이터 라이브러리로 이관했습니다")
        }
        // throwing 변형 + runCatching: 성공 시에만 pending 해제 — 실패하면 플래그가 남아
        // 다음 앱 시작 시 재수확된다 (임포트 결과 자체는 이미 커밋됨)
        val harvested = runCatching {
            fieldLibrary.harvestAllOrThrow()
            // 필드별 호출 대신 배치 — 집계 구현은 하나이고, 여기서는 왕복만 줄인다.
            // 여기서 **동기로** 끝낸다: 성공해야 pending 플래그를 내릴 수 있으므로
            // 예약(비동기)에 맡기면 "아직 안 끝난 일"을 끝났다고 기록하게 된다.
            fieldLibrary.recountUsageForFieldsOrThrow(
                db.fieldDefinitionDao().getAllFieldsAllTypes().map { it.id }
            )
        }.isSuccess
        if (harvested) {
            migrationPrefs?.edit()?.putBoolean("field_library_harvest_pending", false)?.apply()
        } else {
            result.warnings.add("필드 데이터 라이브러리 수확이 지연되었습니다 — 다음 앱 시작 시 자동 재시도됩니다")
        }

        return result
    }

    // ── 복원 미리보기 분석 (읽기 전용) ──

    // ────────────────────────────────────────────────────────────────────────────
    // 복원 미리보기 ↔ 가져오기 정합 — 읽기·적용의 단일 소스 (B-101/B-102, 규약 R-33)
    //
    // 범주마다 **열 해석(`*Cols`) · 읽기(`read*Row`) · 적용(`merge*`)** 셋을 한 자리에 두고
    // `import*`와 `analyze*`가 **같은 함수**를 부른다. 종전에는 둘이 각자 규칙을 들고 있어
    // 갈렸고 방향은 늘 **'바뀌는데 안 바뀐다'는 거짓 안심**이었다 — 미리보기가 '동일'이라
    // 말한 행을 가져오기가 덮어써서, 사용자가 되돌릴 기회를 갖지 못했다.
    // 전수 대조와 설계는 `docs/restore_preview_parity_2026-08.md`.
    //
    // **null의 뜻은 하나다: 그 열이 시트에 없다(= 말한 바 없음 → 기존값 유지).**
    // 빈칸("")은 다르다 — 비우라는 뜻이므로 그대로 적용한다(F1-A).
    // ────────────────────────────────────────────────────────────────────────────

    private class UniverseCols(cols: Map<String, Int>, firstHeader: String) {
        val name = cols[firstHeader] ?: cols["이름"] ?: 0
        val desc = cols["설명"] ?: 1
        val hasDesc = cols.containsKey("설명")
        val code = cols["코드"] ?: -1
        val order = cols["정렬순서"] ?: -1
        val borderColor = cols["테두리색"] ?: -1
        val borderWidth = cols["테두리두께"] ?: -1
        val imagePath = cols["이미지경로"] ?: -1
        val imageMode = cols["이미지모드"] ?: -1
        val relTypes = cols["커스텀관계유형"] ?: -1
        val relColors = cols["커스텀관계색상"] ?: -1
        val imageCharCode = cols["이미지캐릭터코드"] ?: -1
        val imageNovelCode = cols["이미지작품코드"] ?: -1
        val createdAt = cols["생성일"] ?: -1
    }

    private data class UniverseRowValues(
        val name: String,
        val code: String,
        val description: String?,
        val displayOrder: Long?,
        val borderColor: String?,
        val borderWidthDp: Float?,
        val imagePaths: String?,
        val imageMode: String?,
        val customRelationshipTypes: String?,
        val customRelationshipColors: String?,
        val imageCharCode: String?,
        val imageNovelCode: String?,
        val hasImageCharCol: Boolean,
        val hasImageNovelCol: Boolean,
        val createdAt: Long?
    )

    private fun readUniverseRow(row: Row, c: UniverseCols, ctx: String, now: Long, result: ImportResult?): UniverseRowValues {
        val name = getCellString(row, c.name)
        return UniverseRowValues(
            name = name,
            code = getCellCode(row, c.code, ctx, result),
            description = if (c.hasDesc) getCellString(row, c.desc) else null,
            displayOrder = if (c.order >= 0) getCellString(row, c.order).let { if (it.isBlank()) null else parseNumber(it)?.toLong() } else null,
            borderColor = if (c.borderColor >= 0) getCellString(row, c.borderColor) else null,
            borderWidthDp = if (c.borderWidth >= 0) (parseNumber(getCellString(row, c.borderWidth))?.toFloat() ?: 1.5f) else null,
            imagePaths = if (c.imagePath >= 0) remapImagePaths(getCellString(row, c.imagePath).ifBlank { "[]" }) else null,
            imageMode = if (c.imageMode >= 0) getCellString(row, c.imageMode).ifBlank { "none" } else null,
            // 두 열은 JSON이다. 소비처가 파싱 실패를 무음으로 삼키고 기본값으로 돌아가므로 여기서 검증한다.
            // null = 열 없음 또는 해석 불가 → 기존 값 유지.
            customRelationshipTypes = if (c.relTypes >= 0) normalizeRelTypesCell(getCellString(row, c.relTypes), ctx, name, result) else null,
            customRelationshipColors = if (c.relColors >= 0) normalizeRelColorsCell(getCellString(row, c.relColors), ctx, name, result) else null,
            imageCharCode = getCellCode(row, c.imageCharCode, ctx, result).ifBlank { null },
            imageNovelCode = getCellCode(row, c.imageNovelCode, ctx, result).ifBlank { null },
            hasImageCharCol = c.imageCharCode >= 0,
            hasImageNovelCol = c.imageNovelCode >= 0,
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )
    }

    /**
     * 행을 기존 세계관에 적용한 결과 — **가져오기가 쓰는 값이 곧 미리보기가 비교하는 값이다.**
     *
     * [imageCharacterId]·[imageNovelId]는 **2단계 지연 해석** 몫이라 호출부가 정한다:
     * 가져오기는 이 시점에 그 캐릭터·작품이 아직 없을 수 있어 `null`을 넣고 나중에 코드로 되붙이고,
     * 분석은 **되붙은 뒤의 순효과**를 알아야 하므로 현행 DB에서 코드를 해석해 넣는다.
     * 중간의 null을 그대로 비교하면 그 열이 있는 모든 행이 '변경'으로 뜬다(설계 2-3).
     */
    private fun mergeUniverse(
        existing: Universe,
        r: UniverseRowValues,
        imageCharacterId: Long?,
        imageNovelId: Long?
    ): Universe = existing.copy(
        name = r.name,
        description = r.description ?: existing.description,
        displayOrder = r.displayOrder ?: existing.displayOrder,
        borderColor = r.borderColor ?: existing.borderColor,
        borderWidthDp = r.borderWidthDp ?: existing.borderWidthDp,
        imagePaths = r.imagePaths ?: existing.imagePaths,
        imageMode = r.imageMode ?: existing.imageMode,
        customRelationshipTypes = r.customRelationshipTypes ?: existing.customRelationshipTypes,
        customRelationshipColors = r.customRelationshipColors ?: existing.customRelationshipColors,
        imageCharacterId = if (r.hasImageCharCol) imageCharacterId else existing.imageCharacterId,
        imageNovelId = if (r.hasImageNovelCol) imageNovelId else existing.imageNovelId,
        createdAt = r.createdAt ?: existing.createdAt
    )

    private class NovelCols(cols: Map<String, Int>, firstHeader: String) {
        val title = cols[firstHeader] ?: cols["제목"] ?: 0
        val desc = cols["설명"] ?: 1
        val hasDesc = cols.containsKey("설명")
        // 위치 폴백 금지 — 열을 지우거나 개명한 파일에서 이웃 열('설명' 등)을 세계관명으로 오독하고,
        // 미해석 경고가 행마다 거짓으로 쏟아진다. 열 없음(-1)이면 F1-A대로 기존 소속을 유지한다.
        val universeName = cols["세계관"] ?: -1
        val code = cols["코드"] ?: -1
        val universeCode = cols["세계관코드"] ?: -1
        val order = cols["정렬순서"] ?: -1
        val borderColor = cols["테두리색"] ?: -1
        val borderWidth = cols["테두리두께"] ?: -1
        val imagePath = cols["이미지경로"] ?: -1
        val imageMode = cols["이미지모드"] ?: -1
        val imageCharCode = cols["이미지캐릭터코드"] ?: -1
        val inheritBorder = cols["테두리상속"] ?: -1
        val pinned = cols["고정"] ?: -1
        val standardYear = cols["표준연도"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        val universeColumnPresent = cols.containsKey("세계관") || universeCode >= 0
    }

    private data class NovelRowValues(
        val title: String,
        val code: String,
        val universeName: String,
        val universeCode: String,
        val universeColumnPresent: Boolean,
        val description: String?,
        val displayOrder: Long?,
        val borderColor: String?,
        val borderWidthDp: Float?,
        val imagePaths: String?,
        val imageMode: String?,
        val imageCharCode: String?,
        val hasImageCharCol: Boolean,
        val hasInheritCol: Boolean,
        val inheritUniverseBorder: Boolean,
        val hasPinnedCol: Boolean,
        val isPinned: Boolean,
        val hasStandardYearCol: Boolean,
        val standardYear: Int?,
        val createdAt: Long?
    ) {
        val universeRefProvided: Boolean get() = universeCode.isNotBlank() || universeName.isNotBlank()
    }

    private fun readNovelRow(row: Row, c: NovelCols, ctx: String, now: Long, result: ImportResult?): NovelRowValues {
        val borderColor = if (c.borderColor >= 0) getCellString(row, c.borderColor) else null
        return NovelRowValues(
            title = getCellString(row, c.title),
            code = getCellCode(row, c.code, ctx, result),
            universeName = getCellString(row, c.universeName),
            universeCode = getCellCode(row, c.universeCode, ctx, result),
            universeColumnPresent = c.universeColumnPresent,
            description = if (c.hasDesc) getCellString(row, c.desc) else null,
            displayOrder = if (c.order >= 0) getCellString(row, c.order).let { if (it.isBlank()) null else parseNumber(it)?.toLong() } else null,
            borderColor = borderColor,
            borderWidthDp = if (c.borderWidth >= 0) (parseNumber(getCellString(row, c.borderWidth))?.toFloat() ?: 1.5f) else null,
            imagePaths = if (c.imagePath >= 0) remapImagePaths(getCellString(row, c.imagePath).ifBlank { "[]" }) else null,
            imageMode = if (c.imageMode >= 0) getCellString(row, c.imageMode).ifBlank { "none" } else null,
            imageCharCode = getCellCode(row, c.imageCharCode, ctx, result).ifBlank { null },
            hasImageCharCol = c.imageCharCode >= 0,
            hasInheritCol = c.inheritBorder >= 0,
            // 열이 없을 때의 값은 **새 작품을 만들 때만** 쓰인다(기존 작품은 아래 merge가 기존값을 유지한다).
            inheritUniverseBorder = if (c.inheritBorder >= 0) parseBoolean(getCellString(row, c.inheritBorder)) else (borderColor ?: "").isBlank(),
            hasPinnedCol = c.pinned >= 0,
            isPinned = if (c.pinned >= 0) parseBoolean(getCellString(row, c.pinned)) else false,
            hasStandardYearCol = c.standardYear >= 0,
            standardYear = if (c.standardYear >= 0) parseNumber(getCellString(row, c.standardYear))?.toInt() else null,
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )
    }

    /**
     * 이 행이 확정하는 작품 소속 — **갱신과 필드값 적용, 그리고 미리보기가 같은 값을 봐야 한다.**
     * 열이 없으면 기존 유지, 참조가 있는데 미해석이면 무음 분리 대신 기존 유지(경고와 짝).
     */
    private fun effectiveNovelUniverseId(existing: Novel, r: NovelRowValues, resolvedUniverseId: Long?): Long? = when {
        !r.universeColumnPresent -> existing.universeId
        r.universeRefProvided && resolvedUniverseId == null -> existing.universeId
        else -> resolvedUniverseId
    }

    /** [mergeUniverse]와 같은 규약. [imageCharacterId]는 2단계 지연 해석 몫이라 호출부가 정한다. */
    private fun mergeNovel(
        existing: Novel,
        r: NovelRowValues,
        effectiveUniverseId: Long?,
        imageCharacterId: Long?
    ): Novel = existing.copy(
        title = r.title,
        description = r.description ?: existing.description,
        universeId = effectiveUniverseId,
        displayOrder = r.displayOrder ?: existing.displayOrder,
        borderColor = r.borderColor ?: existing.borderColor,
        borderWidthDp = r.borderWidthDp ?: existing.borderWidthDp,
        inheritUniverseBorder = if (r.hasInheritCol) r.inheritUniverseBorder else existing.inheritUniverseBorder,
        isPinned = if (r.hasPinnedCol) r.isPinned else existing.isPinned,
        imagePaths = r.imagePaths ?: existing.imagePaths,
        imageMode = r.imageMode ?: existing.imageMode,
        imageCharacterId = if (r.hasImageCharCol) imageCharacterId else existing.imageCharacterId,
        standardYear = if (r.hasStandardYearCol) r.standardYear else existing.standardYear,
        createdAt = r.createdAt ?: existing.createdAt
    )

    private class FactionCols(cols: Map<String, Int>, firstHeader: String) {
        val name = cols[firstHeader] ?: cols["이름"] ?: 0
        val universeName = cols["세계관"] ?: -1
        val universeCode = cols["세계관코드"] ?: -1
        val desc = cols["설명"] ?: -1
        val color = cols["색상"] ?: -1
        val autoRelType = cols["자동관계유형"] ?: -1
        val autoRelIntensity = cols["자동관계강도"] ?: -1
        val code = cols["코드"] ?: -1
        val order = cols["정렬순서"] ?: -1
        val createdAt = cols["생성일"] ?: -1
    }

    private data class FactionRowValues(
        val name: String,
        val code: String,
        val universeName: String,
        val universeCode: String,
        val description: String?,
        val color: String?,
        val autoRelationType: String,
        val autoRelationIntensity: Int,
        val displayOrder: Int?,
        val createdAt: Long?
    )

    private fun readFactionRow(row: Row, c: FactionCols, ctx: String, now: Long, result: ImportResult?): FactionRowValues =
        FactionRowValues(
            name = getCellString(row, c.name),
            code = getCellCode(row, c.code, ctx, result),
            universeName = if (c.universeName >= 0) getCellString(row, c.universeName) else "",
            universeCode = getCellCode(row, c.universeCode, ctx, result),
            // F1-A: 열 없음 → null(기존 유지). 열 있음 → 셀 값(빈칸 = 비움 의도 존중).
            description = if (c.desc >= 0) getCellString(row, c.desc) else null,
            color = if (c.color >= 0) getCellString(row, c.color).ifBlank { "#2196F3" } else null,
            autoRelationType = if (c.autoRelType >= 0) getCellString(row, c.autoRelType) else "",
            autoRelationIntensity = parseIntensityWithWarn(row, c.autoRelIntensity, 5, ctx, result) ?: 5,
            displayOrder = if (c.order >= 0) getCellString(row, c.order).let { if (it.isBlank()) null else parseNumber(it)?.toInt() } else null,
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )

    private fun mergeFaction(existing: Faction, r: FactionRowValues, universeId: Long): Faction = existing.copy(
        name = r.name,
        universeId = universeId,
        description = r.description ?: existing.description,
        color = r.color ?: existing.color,
        autoRelationType = r.autoRelationType,
        autoRelationIntensity = r.autoRelationIntensity,
        displayOrder = r.displayOrder ?: existing.displayOrder,
        createdAt = r.createdAt ?: existing.createdAt
    )

    private class NameBankCols(cols: Map<String, Int>) {
        val name = cols["이름"] ?: 0
        val gender = cols["성별"] ?: 1
        val origin = cols["출처"] ?: 2
        val notes = cols["메모"] ?: 3
        // 위치 폴백 금지 — 열을 지우면 '사용 캐릭터'를 사용여부로 오독한다
        val used = cols["사용여부"] ?: -1
        // 위치 폴백 금지 — 열을 지우면 이웃 열(생성일/코드)을 이름으로 오독한다
        val usedBy = cols["사용 캐릭터"] ?: -1
        val charCode = cols["사용캐릭터코드"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        val code = cols["코드"] ?: -1  // F3-D: 이름 은행 항목 자체 코드
    }

    private data class NameBankRowValues(
        val name: String,
        val gender: String,
        val origin: String,
        val notes: String,
        val usedFlag: Boolean?,
        val usedByCharName: String,
        val usedByCharCode: String,
        val usedIntent: RefIntent,
        val createdAt: Long?,
        val code: String
    ) {
        /** 자연키(이름+성별). 기존 항목 쪽은 [mapKeyForNameBank]가 **같은 식으로** 만든다. */
        val mapKey: String get() = nameBankKey(name, gender)
    }

    private fun readNameBankRow(row: Row, c: NameBankCols, ctx: String, now: Long, result: ImportResult?): NameBankRowValues {
        val usedByCharName = if (c.usedBy >= 0) getCellString(row, c.usedBy) else ""
        val usedByCharCode = getCellCode(row, c.charCode, ctx, result)
        return NameBankRowValues(
            name = getCellString(row, c.name),
            gender = getCellString(row, c.gender),
            origin = getCellString(row, c.origin),
            notes = getCellString(row, c.notes),
            usedFlag = sheetBooleanOrKeep(c.used >= 0, getCellString(row, c.used)),
            usedByCharName = usedByCharName,
            usedByCharCode = usedByCharCode,
            // 사용 캐릭터는 편집 가능한 '사용 캐릭터' + readOnly '사용캐릭터코드'의 참조 열 쌍이다
            // (관계 시트의 '세력'/'세력코드'와 동형): 유무는 이름 열이, 대상은 코드가 정한다.
            usedIntent = refColumnIntent(c.usedBy >= 0, c.charCode >= 0, usedByCharName, usedByCharCode),
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null,
            code = getCellCode(row, c.code, ctx, result)  // F4: 숫자 코드 방어
        )
    }

    /** 참조 열 쌍 규약: 열 없음 → 기존 연결 유지(F1-A) / 이름 칸 빈칸 → 명시적 해제 / 값 있음 → 해석 결과. */
    private suspend fun resolveNameBankUsedBy(
        r: NameBankRowValues,
        existing: NameBankEntry?,
        ctx: String,
        result: ImportResult?
    ): Long? = when (r.usedIntent) {
        RefIntent.KEEP -> existing?.usedByCharacterId
        RefIntent.CLEAR -> null
        RefIntent.LOOKUP ->
            (if (r.usedByCharCode.isNotBlank()) characterByCode(r.usedByCharCode)?.id else null)
                ?: when (val lr = resolveCharByNameNovel(r.usedByCharName, null)) {  // F3-B: 동명이인 안전
                    is CharLookupResult.Found -> lr.character.id
                    is CharLookupResult.Ambiguous -> {
                        result?.warnings?.add("$ctx: 사용 캐릭터 '${r.usedByCharName}' 동명이인 ${lr.count}명 — 사용캐릭터코드 열로 지정하세요")
                        null
                    }
                    CharLookupResult.NotFound -> null
                }
    }

    private fun mergeNameBankEntry(existing: NameBankEntry, r: NameBankRowValues, usedByCharacterId: Long?): NameBankEntry =
        existing.copy(
            // 코드 매칭 시 이름/성별 편집 반영 (code는 불변 유지 — 정체성)
            name = r.name, gender = r.gender,
            origin = r.origin, notes = r.notes,
            isUsed = r.usedFlag ?: existing.isUsed,
            usedByCharacterId = usedByCharacterId,
            createdAt = r.createdAt ?: existing.createdAt
        )

    // ── 프리셋 셋: 시계 필드(`updatedAt`) 규약 ──
    //
    // 셋 다 `ORDER BY ... updatedAt DESC`로 정렬된다 — **시각이 바뀌면 사용자 목록의 순서가
    // 실제로 바뀐다.** 그런데 종전 가져오기는 아무것도 안 바뀐 행에도 시각을 새로 찍었다.
    // 그대로 비교하면 모든 행이 '변경'이 되어 미리보기가 쓸모를 잃고(원칙 02),
    // 비교에서만 빼면 미리보기가 또 거짓말을 한다.
    //
    // → **내용이 그대로면 시각도 그대로 둔다.** `updatedAt`의 뜻이 "마지막으로 바뀐 때"이므로
    //   아무것도 바뀌지 않았으면 그 값도 바뀌지 않는 것이 맞다.
    //   '수정일' 열이 시트에 있으면 그 값 자체가 내용이다(사용자가 순서를 직접 지정할 수 있다).

    private class SearchPresetCols(cols: Map<String, Int>) {
        // 첫 열만 checkHeaderOrReport가 보증한다. 선택 열의 위치 폴백은 열 삭제 시 이웃을 오독한다.
        val name = cols["이름"] ?: 0
        val query = cols["검색어"] ?: -1
        val filters = cols["필터(JSON)"] ?: -1
        val sortMode = cols["정렬모드"] ?: -1
        val isDefault = cols["기본값"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        val updatedAt = cols["수정일"] ?: -1
    }

    private data class SearchPresetRowValues(
        val name: String,
        val query: String?,
        val filtersJson: String?,
        val sortMode: String?,
        val isDefault: Boolean?,
        val createdAt: Long?,
        val updatedAt: Long?
    )

    private fun readSearchPresetRow(
        row: Row, c: SearchPresetCols, ctx: String,
        filterIndex: PortableFieldFilters.Index, result: ImportResult?
    ): SearchPresetRowValues {
        val name = getCellString(row, c.name)
        // 인식할 수 없는 정렬모드를 조용히 저장하면 적용 시 relevance로 동작해 사용자가 틀린 줄 모른다.
        val sortModeRaw = if (c.sortMode >= 0) getCellString(row, c.sortMode).trim() else ""
        val sortMode: String? = when {
            sortModeRaw.isBlank() -> null
            else -> matchDropdownValue(sortModeRaw, SearchPreset.SORT_MODES) ?: run {
                result?.warnings?.add(
                    "$ctx ('$name'): 정렬모드 '$sortModeRaw'을(를) 인식할 수 없어 " +
                    "기본(${SearchPreset.SORT_RELEVANCE})으로 처리합니다 — ${SearchPreset.SORT_MODES.joinToString("/")} 중 하나로 입력하세요"
                )
                SearchPreset.SORT_RELEVANCE
            }
        }
        // 필드 필터의 fieldId를 안정 식별자(세계관코드+필드키)로 재해석 — 기기 이전·복원 후에도 필터가 살아있게
        val filtersJson = if (c.filters >= 0) {
            val resolved = PortableFieldFilters.resolve(getCellString(row, c.filters).ifBlank { "{}" }, filterIndex)
            for (w in resolved.warnings) result?.warnings?.add("$ctx ('$name'): $w")
            if (result != null) result.nameBasedMappings += resolved.nameBasedCount
            resolved.json
        } else null
        return SearchPresetRowValues(
            name = name,
            // 열 없음 = null = 기존값 유지(F1-A). 열 있음+빈칸 = 비움 의도.
            query = if (c.query >= 0) getCellString(row, c.query) else null,
            filtersJson = filtersJson,
            sortMode = sortMode,
            isDefault = if (c.isDefault >= 0) parseBoolean(getCellString(row, c.isDefault)) else null,
            createdAt = if (c.createdAt >= 0) parseNumber(getCellString(row, c.createdAt))?.toLong() else null,
            updatedAt = if (c.updatedAt >= 0) parseNumber(getCellString(row, c.updatedAt))?.toLong() else null
        )
    }

    private fun mergeSearchPreset(existing: SearchPreset, r: SearchPresetRowValues, now: Long): SearchPreset {
        val content = existing.copy(
            query = r.query ?: existing.query,
            filtersJson = r.filtersJson ?: existing.filtersJson,
            sortMode = r.sortMode ?: existing.sortMode,
            isDefault = r.isDefault ?: existing.isDefault,
            updatedAt = r.updatedAt ?: existing.updatedAt
        )
        return if (content == existing) existing else content.copy(updatedAt = r.updatedAt ?: now)
    }

    private class ListPresetCols(cols: Map<String, Int>) {
        val name = cols["이름"] ?: 0
        val tags = cols["태그(JSON)"] ?: -1
        val filters = cols["필드필터(JSON)"] ?: -1
        val sortKind = cols["정렬종류"] ?: -1
        val sortFieldKey = cols["정렬필드키"] ?: -1
        val sortDuelAxis = cols["대결축코드"] ?: -1
        val sortAsc = cols["정렬오름차순"] ?: -1
        val bodyPart = cols["신체파트번호"] ?: -1
        val novelCodes = cols["작품코드목록"] ?: -1
        val isDefault = cols["기본값"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        val updatedAt = cols["수정일"] ?: -1
    }

    private data class ListPresetRowValues(
        val name: String,
        val tagsJson: String?,
        val fieldFiltersJson: String?,
        val sortKind: String?,
        val hasSortFieldKeyCol: Boolean,
        val sortFieldKey: String?,
        val hasDuelAxisCol: Boolean,
        val sortDuelAxisCode: String?,
        val sortAscending: Boolean?,
        val hasBodyPartCol: Boolean,
        val bodySizePartIndex: Int?,
        val novelIdsJson: String?,
        val isDefault: Boolean?,
        val createdAt: Long?,
        val updatedAt: Long?
    )

    /**
     * 목록 프리셋 정렬종류 유효값 — **단일 소스는 엔티티 companion이다.**
     *
     * 종전에는 그렇게 적어 놓고 실제로는 다섯을 여기 다시 나열하고 있었다. 그래서 B-117이
     * 정렬 하나를 더했을 때 **엑셀에 `duel`이 적혀 있어도 조용히 '수동'이 됐다** —
     * 내보낸 파일을 그대로 되들이는 것만으로 정렬이 사라지는 자리다(개발 의도 4번).
     */
    private val validListSortKinds = CharacterListPreset.SORT_KINDS

    private suspend fun readListPresetRow(
        row: Row, c: ListPresetCols, ctx: String,
        filterIndex: PortableFieldFilters.Index, result: ImportResult?
    ): ListPresetRowValues {
        val name = getCellString(row, c.name)
        // 작품코드 → 이 기기의 작품 id (미해석 코드는 경고 후 제외)
        val novelIdsJson: String? = if (c.novelCodes >= 0) {
            val ids = mutableListOf<Long>()
            for (code in splitCsv(getCellString(row, c.novelCodes))) {
                val novel = novelByCode(code)
                if (novel != null) ids.add(novel.id)
                else result?.warnings?.add("$ctx: 작품코드 '$code'을(를) 찾을 수 없어 프리셋의 작품 필터에서 제외합니다")
            }
            org.json.JSONArray(ids).toString()
        } else null

        val sortKindRaw = if (c.sortKind >= 0) getCellString(row, c.sortKind).trim() else ""
        val sortKind: String? = when {
            c.sortKind < 0 || sortKindRaw.isBlank() -> null // 열 없음/빈칸 = 기존값 유지·기본값
            sortKindRaw.lowercase() in validListSortKinds -> sortKindRaw.lowercase()
            else -> {
                result?.warnings?.add("$ctx: 정렬종류 '$sortKindRaw'을(를) 인식할 수 없어 기본(manual)으로 처리합니다")
                CharacterListPreset.SORT_MANUAL
            }
        }
        val fieldFiltersJson = if (c.filters >= 0) {
            val resolved = PortableFieldFilters.resolve(getCellString(row, c.filters).ifBlank { "{}" }, filterIndex)
            for (w in resolved.warnings) result?.warnings?.add("$ctx ('$name'): $w")
            if (result != null) result.nameBasedMappings += resolved.nameBasedCount
            resolved.json
        } else null

        return ListPresetRowValues(
            name = name,
            tagsJson = if (c.tags >= 0) getCellString(row, c.tags).ifBlank { "[]" } else null,
            fieldFiltersJson = fieldFiltersJson,
            sortKind = sortKind,
            hasSortFieldKeyCol = c.sortFieldKey >= 0,
            sortFieldKey = if (c.sortFieldKey >= 0) getCellString(row, c.sortFieldKey).ifBlank { null } else null,
            hasDuelAxisCol = c.sortDuelAxis >= 0,
            sortDuelAxisCode = if (c.sortDuelAxis >= 0)
                getCellString(row, c.sortDuelAxis).trim().ifBlank { null } else null,
            // 불리언 열 규약(전 시트 공통): null은 '열 없음'(기존값 유지)만을 뜻한다.
            // 열이 있으면 빈칸도 해석 대상 — 빈칸 = N = 비움 의도(F1-A).
            sortAscending = sheetBooleanOrKeep(c.sortAsc >= 0, getCellString(row, c.sortAsc)),
            hasBodyPartCol = c.bodyPart >= 0,
            bodySizePartIndex = if (c.bodyPart >= 0) parseNumber(getCellString(row, c.bodyPart))?.toInt() else null,
            novelIdsJson = novelIdsJson,
            isDefault = sheetBooleanOrKeep(c.isDefault >= 0, getCellString(row, c.isDefault)),
            createdAt = if (c.createdAt >= 0) parseNumber(getCellString(row, c.createdAt))?.toLong() else null,
            updatedAt = if (c.updatedAt >= 0) parseNumber(getCellString(row, c.updatedAt))?.toLong() else null
        )
    }

    private fun mergeListPreset(existing: CharacterListPreset, r: ListPresetRowValues, now: Long): CharacterListPreset {
        val content = existing.copy(
            tagsJson = r.tagsJson ?: existing.tagsJson,
            fieldFiltersJson = r.fieldFiltersJson ?: existing.fieldFiltersJson,
            sortKind = r.sortKind ?: existing.sortKind,
            sortFieldKey = if (r.hasSortFieldKeyCol) r.sortFieldKey else existing.sortFieldKey,
            sortDuelAxisCode = if (r.hasDuelAxisCol) r.sortDuelAxisCode else existing.sortDuelAxisCode,
            sortAscending = r.sortAscending ?: existing.sortAscending,
            bodySizePartIndex = if (r.hasBodyPartCol) r.bodySizePartIndex else existing.bodySizePartIndex,
            novelIdsJson = r.novelIdsJson ?: existing.novelIdsJson,
            isDefault = r.isDefault ?: existing.isDefault,
            updatedAt = r.updatedAt ?: existing.updatedAt
        )
        return if (content == existing) existing else content.copy(updatedAt = r.updatedAt ?: now)
    }

    private class PresetTemplateCols(cols: Map<String, Int>) {
        // 첫 열('이름')은 checkHeaderOrReport가 보장하므로 0 폴백이 성립한다.
        // 나머지는 위치 폴백 금지 — 열을 지우면 이웃 열을 오독한다.
        val name = cols["이름"] ?: 0
        val desc = cols["설명"] ?: -1
        val fieldsJson = cols["설정(JSON)"] ?: -1
        val builtIn = cols["기본제공"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        val updatedAt = cols["수정일"] ?: -1
    }

    private data class PresetTemplateRowValues(
        val name: String,
        val description: String?,
        val fieldsJson: String?,
        val isBuiltIn: Boolean?,
        val createdAt: Long?,
        val updatedAt: Long?
    )

    private fun readPresetTemplateRow(row: Row, c: PresetTemplateCols, ctx: String, result: ImportResult?): PresetTemplateRowValues {
        val createdAtRaw = if (c.createdAt >= 0) getCellString(row, c.createdAt) else ""
        val createdAt: Long? = parseNumber(createdAtRaw)?.toLong()
        if (createdAtRaw.isNotBlank() && createdAt == null) {
            result?.warnings?.add("$ctx: 생성일 '$createdAtRaw'을(를) 숫자로 읽을 수 없어 이름으로 매칭합니다 — '생성일' 열은 수정하지 마세요")
        }
        return PresetTemplateRowValues(
            name = getCellString(row, c.name),
            // F1-A: 열이 없으면 null(기존 값 유지), 열이 있고 빈칸이면 비움 의도로 존중
            description = if (c.desc >= 0) getCellString(row, c.desc) else null,
            fieldsJson = if (c.fieldsJson >= 0) getCellString(row, c.fieldsJson).ifBlank { "[]" } else null,
            isBuiltIn = sheetBooleanOrKeep(c.builtIn >= 0, getCellString(row, c.builtIn)),
            createdAt = createdAt,
            updatedAt = if (c.updatedAt >= 0) parseNumber(getCellString(row, c.updatedAt))?.toLong() else null
        )
    }

    /** createdAt은 이 시트의 정체성이라 파일 값으로 덮지 않는다(코드 열과 동일 취급). */
    private fun mergePresetTemplate(existing: UserPresetTemplate, r: PresetTemplateRowValues, now: Long): UserPresetTemplate {
        val content = existing.copy(
            name = r.name,                                 // 생성일 매칭이면 rename 반영
            description = r.description ?: existing.description,
            fieldsJson = r.fieldsJson ?: existing.fieldsJson,
            isBuiltIn = r.isBuiltIn ?: existing.isBuiltIn,
            updatedAt = r.updatedAt ?: existing.updatedAt
        )
        return if (content == existing) existing else content.copy(updatedAt = r.updatedAt ?: now)
    }

    private class FieldDefCols(cols: Map<String, Int>, firstHeader: String) {
        val universeName = cols[firstHeader] ?: cols["세계관"] ?: 0
        val key = cols["필드키"] ?: 1
        val name = cols["필드명"] ?: 2
        val type = cols["타입"] ?: 3
        // 위치 폴백 금지 — 이 열만 앞쪽에서 내렸다(B-162, 사용자 판정 2026.08.08).
        // 4번 자리를 폴백으로 두면 **열을 지운 파일에서 5번의 `그룹`이 올라와** 그룹명이
        // config가 되고 `options`·`formula`·등급표를 통째로 덮는다. 없으면 -1이고,
        // 그때는 기존 설정을 지킨다(R-36 — `FieldConfigColumns.merge`가 그렇게 받는다).
        // **앞의 넷은 그대로 둔다** — 행의 정체를 이루는 열이라 없으면 행 자체를 못 읽는다.
        val config = cols["설정(JSON)"] ?: -1
        val group = cols["그룹"] ?: 5
        val order = cols["순서"] ?: -1
        // 위치 폴백 금지 — 열을 지우면 '세계관코드'를 필수여부로 오독한다
        val required = cols["필수여부"] ?: -1
        val universeCode = cols["세계관코드"] ?: -1
        // 대상(캐릭터/사건) — 열이 없는 구버전 파일은 캐릭터로 간주 (관대 수용)
        val entityType = cols["대상"] ?: -1
        // config 파생 전용 열(A-1·A-2) — 열/JSON 키/기존값 3분기 병합 (FieldConfigColumns.merge)
        val aiSuggest = cols[FieldConfigColumns.COLUMN_AI_SUGGEST] ?: -1
        val description = cols[FieldConfigColumns.COLUMN_DESCRIPTION] ?: -1
        // 등급 체계 참조 열(U-1) — 같은 3분기 문법. 해석은 코드 우선, 없으면 (세계관, 이름).
        val gradeSystem = cols["등급체계"] ?: -1
        val gradeSystemCode = cols["등급체계코드"] ?: -1
        // 전역 기본 필드 연결(B-119) — **이름 열이 없는 코드 단독 참조 열**이다.
        // 위치 폴백 금지: 열이 없으면 -1이고, 그때는 기존 연결을 그대로 둔다(R-36).
        val defaultFieldCode = cols["기본필드코드"] ?: -1
    }

    private data class FieldDefRowValues(
        val universeName: String,
        val universeCode: String,
        val key: String,
        val name: String,
        val type: String,
        /** `null`이면 *"이 파일은 설정 열을 말하지 않는다"* — 기존 설정을 지킨다(R-36 · B-162). */
        val config: String?,
        val groupName: String,
        val displayOrder: Int?,
        val isRequired: Boolean?,
        val entityType: String,
        val aiColumnPresent: Boolean,
        val aiCellText: String,
        val descriptionColumnPresent: Boolean,
        val descriptionCellText: String,
        val gradeNameColumnPresent: Boolean,
        val gradeCellName: String,
        val gradeCodeColumnPresent: Boolean,
        val gradeCellCode: String,
        /**
         * R-36 — **열의 존재를 값에 담는다.** `false`면 *"이 파일은 연결을 말하지 않는다"*이고,
         * `true`면 (빈 칸이라도) 그 칸이 뜻이다. `getCellString(row, -1)`이 두 사실에 똑같이
         * `""`를 내는 것이 이 함정의 뿌리라, 판별을 `cols.containsKey` 쪽으로 옮긴다.
         */
        val defaultFieldColumnPresent: Boolean,
        val defaultFieldCellCode: String
    )

    private fun readFieldDefRow(row: Row, c: FieldDefCols, ctx: String, result: ImportResult?): FieldDefRowValues =
        FieldDefRowValues(
            universeName = getCellString(row, c.universeName),
            universeCode = getCellCode(row, c.universeCode, ctx, result),
            key = getCellString(row, c.key),
            name = getCellString(row, c.name),
            type = getCellString(row, c.type),
            // 열이 없으면 null — 빈 칸("{}", 설정을 비워라)과 구별한다(R-36 · B-162).
            // 형제 시트('기본 필드')가 B-142에서 세운 형태 그대로다.
            config = if (c.config >= 0) getCellString(row, c.config).ifBlank { "{}" } else null,
            groupName = getCellString(row, c.group).ifBlank { "기본 정보" },
            displayOrder = if (c.order >= 0) getCellString(row, c.order).let { if (it.isBlank()) null else parseNumber(it)?.toInt() } else null,
            isRequired = sheetBooleanOrKeep(c.required >= 0, getCellString(row, c.required)),
            entityType = FieldValueSheetMapper.entityTypeOf(if (c.entityType >= 0) getCellString(row, c.entityType) else null),
            aiColumnPresent = c.aiSuggest >= 0,
            aiCellText = getCellString(row, c.aiSuggest),
            descriptionColumnPresent = c.description >= 0,
            descriptionCellText = getCellString(row, c.description),
            gradeNameColumnPresent = c.gradeSystem >= 0,
            gradeCellName = if (c.gradeSystem >= 0) getCellString(row, c.gradeSystem) else "",
            gradeCodeColumnPresent = c.gradeSystemCode >= 0,
            gradeCellCode = if (c.gradeSystemCode >= 0) getCellString(row, c.gradeSystemCode) else "",
            defaultFieldColumnPresent = c.defaultFieldCode >= 0,
            defaultFieldCellCode = if (c.defaultFieldCode >= 0) getCellString(row, c.defaultFieldCode) else ""
        )

    /**
     * config 열의 3분기 병합 둘을 이어 붙인 결과 — **가져오기와 미리보기가 같은 값을 봐야 한다.**
     * config는 필드 정의에서 가장 잘 바뀌는 자리인데(AI추천·필드설명·등급체계가 전부 여기 산다)
     * 종전 미리보기는 이 열을 아예 비교하지 않았다.
     */
    private suspend fun resolveFieldDefConfig(
        universeId: Long?,
        rowIndex: Int,
        r: FieldDefRowValues,
        existing: FieldDefinition?,
        result: ImportResult?
    ): String {
        // AI추천·필드설명 병합 — 열이 있으면 셀이 값, 없으면 JSON 키 유지, 둘 다 없으면
        // 기존 DB 값 보존(빠뜨리면 전용 열을 지운 파일에서 설명이 무통보 유실된다)
        val portableMerged = FieldConfigColumns.merge(
            sheetConfig = r.config,
            aiColumnPresent = r.aiColumnPresent,
            aiCellText = r.aiCellText,
            descriptionColumnPresent = r.descriptionColumnPresent,
            descriptionCellText = r.descriptionCellText,
            existingConfig = existing?.config
        )
        // 등급 체계 참조 병합(U-1) — 참조가 해석되면 실효 표를 다시 물질화하고,
        // 가리키는 체계가 없으면 거부 대신 독자 표로 내려앉히고 고지한다(관대 수용).
        val gradeMerged = mergeGradeSystemColumn(
            universeId = universeId,
            rowIndex = rowIndex,
            fieldName = r.name,
            fieldType = r.type,
            config = portableMerged,
            nameColumnPresent = r.gradeNameColumnPresent,
            codeColumnPresent = r.gradeCodeColumnPresent,
            cellName = r.gradeCellName,
            cellCode = r.gradeCellCode,
            existingConfig = existing?.config,
            result = result
        )
        // 전역 기본 필드 연결 병합(B-119) — 같은 3분기 문법이되 **코드 열 하나뿐**이다.
        return mergeDefaultFieldColumn(
            rowIndex = rowIndex,
            fieldName = r.name,
            config = gradeMerged,
            columnPresent = r.defaultFieldColumnPresent,
            cellCode = r.defaultFieldCellCode,
            existingConfig = existing?.config,
            result = result
        )
    }

    /**
     * `기본필드코드` 열 병합 (B-119, 설계 1-5) — **R-36의 자리다.**
     *
     * | 열 | 칸 | 뜻 |
     * |---|---|---|
     * | 없음 | — | *"이 파일은 연결을 말하지 않는다"* → **기존 연결을 그대로 둔다** |
     * | 있음 | 빔 | *"연결을 지워라"* → 강등 |
     * | 있음 | 값 | 그 템플릿을 찾는다. 없으면 **강등하고 센다** |
     *
     * 위 첫 줄이 없으면 **B-119 이전에 내보낸 모든 파일**을 다시 들이는 것만으로 전 세계관의
     * 기본 필드 연결이 지워진다. 판정은 [refColumnIntent]가 단일 소스다 — 이름 열 없이 코드
     * 열만 있는 형태로 부른다(그쪽이 이미 *"이름 열이 아예 없으면 코드 열이 유무까지 결정한다"*를
     * 규약으로 들고 있다).
     *
     * 찾지 못한 연결을 **거부하지 않고 강등하는 것**이 개발 의도 4번이다(수용·교정). 다만
     * 조용히 하지는 않는다 — 결과 창이 건수를 말한다.
     */
    private suspend fun mergeDefaultFieldColumn(
        rowIndex: Int,
        fieldName: String,
        config: String,
        columnPresent: Boolean,
        cellCode: String,
        existingConfig: String?,
        result: ImportResult?
    ): String {
        val ref = com.novelcharacter.app.data.model.DefaultFieldRef

        suspend fun linkOrDemote(code: String): String {
            if (db.defaultFieldTemplateDao().getByCode(code) != null) return ref.write(config, code)
            result?.warnings?.add(
                "필드 정의 행 $rowIndex: 필드 '$fieldName'이(가) 가리키는 기본 필드 '$code'을(를) " +
                    "찾을 수 없어 일반 필드로 들였습니다 (필드와 값은 그대로입니다)"
            )
            result?.let { it.demotedDefaultFieldLinks++ }
            return ref.remove(config)
        }

        return when (refColumnIntent(false, columnPresent, "", cellCode)) {
            RefIntent.CLEAR -> ref.remove(config)
            RefIntent.LOOKUP -> linkOrDemote(cellCode)
            // 열이 없다 — config JSON이 표식을 들고 있으면 그것을, 아니면 기존 DB 값을 지킨다.
            RefIntent.KEEP -> {
                val jsonCode = ref.codeFromConfig(config)
                when {
                    jsonCode != null -> linkOrDemote(jsonCode)
                    else -> {
                        val existingCode = existingConfig?.let { ref.codeFromConfig(it) } ?: return config
                        // 이미 있던 연결은 **다시 검증하지 않는다** — 가져오기가 말하지 않은 것을
                        // 근거로 기존 상태를 바꾸지 않는다(R-36의 뜻 그대로).
                        ref.write(config, existingCode)
                    }
                }
            }
        }
    }

    private fun mergeFieldDefinition(existing: FieldDefinition, r: FieldDefRowValues, mergedConfig: String): FieldDefinition =
        existing.copy(
            name = r.name, type = r.type, config = mergedConfig,
            groupName = r.groupName, displayOrder = r.displayOrder ?: existing.displayOrder,
            isRequired = r.isRequired ?: existing.isRequired
        )

    private class TimelineCols(cols: Map<String, Int>, val desc: Int) {
        val year = cols["연도"] ?: 0
        // 선택 속성 열: 위치 폴백을 쓰면 열 삭제 시 이웃 열을 오독하므로 -1(=없음). 열 없음이면 UPDATE에서 기존값 유지.
        val month = cols["월"] ?: -1
        val day = cols["일"] ?: -1
        val calendar = cols["역법"] ?: -1
        val eventType = cols["사건 유형"] ?: -1
        // 선택 연결 열: 위치 폴백 금지 — 열 없음(-1)이면 기존 연결 유지
        val novel = cols["관련 작품"] ?: -1
        val novelCode = cols["관련작품코드"] ?: -1
        val displayOrder = cols["정렬순서"] ?: -1
        val isTemporary = cols["임시배치"] ?: -1
        val code = cols["코드"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        // 세계관 소속 열 — 열이 없는 구버전 파일은 기존처럼 관련 작품에서 유도한다(하위 호환).
        val universeName = cols["세계관"] ?: -1
        val universeCode = cols["세계관코드"] ?: -1
        val novelLinkColumnPresent = cols.containsKey("관련 작품") || novelCode >= 0

        companion object {
            /** '사건 설명'은 필수 열이다 — 위치 폴백을 쓰면 이웃 열이 설명으로 오기록된다. */
            fun descColumn(cols: Map<String, Int>): Int? = cols["사건 설명"]
                ?: cols.entries.firstOrNull { it.key.contains("설명") }?.value
        }
    }

    private data class TimelineRowValues(
        val year: Int?,
        val yearRaw: String,
        val description: String,
        val hasMonthCol: Boolean, val month: Int?,
        val hasDayCol: Boolean, val day: Int?,
        val hasCalendarCol: Boolean, val calendarType: String,
        val hasEventTypeCol: Boolean, val eventType: String,
        val novelTitle: String,
        val novelCode: String,
        val displayOrder: Int?,
        val hasTemporaryCol: Boolean, val isTemporary: Boolean,
        val fileCode: String,
        val createdAt: Long?
    )

    private fun readTimelineRow(row: Row, c: TimelineCols, ctx: String, now: Long, result: ImportResult?): TimelineRowValues {
        val yearRaw = getCellString(row, c.year)
        // F1-B: 범위 밖/해석 불가 월·일은 조용히 버리지 않고 연도처럼 경고
        val month = parseMonthWithWarn(row, c.month, ctx, result)
        val eventType = if (c.eventType >= 0) {
            val label = getCellString(row, c.eventType)
            val mapped = labelToEventType(label)
            // F4: 인식 못한 사건 유형(오타)은 조용히 '일반'으로 떨어뜨리지 않고 경고
            if (label.isNotBlank() && mapped == TimelineEvent.TYPE_NONE &&
                label.trim() !in setOf("일반", "general", "normal")) {
                result?.warnings?.add("$ctx: 사건 유형 '$label'을(를) 인식할 수 없어 '일반'으로 처리 — 탄생/사망/일반 중 선택")
            }
            mapped
        } else TimelineEvent.TYPE_NONE
        return TimelineRowValues(
            year = parseNumber(yearRaw)?.toInt(),
            yearRaw = yearRaw,
            description = getCellString(row, c.desc),
            hasMonthCol = c.month >= 0, month = month,
            hasDayCol = c.day >= 0, day = parseDayWithWarn(row, c.day, month, ctx, result),
            hasCalendarCol = c.calendar >= 0, calendarType = getCellString(row, c.calendar).ifBlank { "천개력" },
            hasEventTypeCol = c.eventType >= 0, eventType = eventType,
            novelTitle = getCellString(row, c.novel),
            novelCode = getCellCode(row, c.novelCode, ctx, result),
            displayOrder = if (c.displayOrder >= 0) getCellString(row, c.displayOrder).let { if (it.isBlank()) null else parseNumber(it)?.toInt() } else null,
            hasTemporaryCol = c.isTemporary >= 0, isTemporary = if (c.isTemporary >= 0) parseBoolean(getCellString(row, c.isTemporary)) else false,
            fileCode = getCellCode(row, c.code, ctx, result),
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )
    }

    /** 사건의 작품 연결과 세계관 소속 해석 — 갱신·미리보기·필드값 적용이 **같은 값**을 봐야 한다. */
    private data class TimelineLinks(val novelIds: List<Long>, val universeId: Long?)

    private suspend fun resolveTimelineLinks(
        row: Row, c: TimelineCols, r: TimelineRowValues, allNovels: List<Novel>, ctx: String, result: ImportResult?
    ): TimelineLinks {
        // 작품 해석: 콤마 구분 복수 작품 지원
        val novelCodeCells = splitCsv(r.novelCode)
        val resolvedNovels = (if (novelCodeCells.isNotEmpty()) novelCodeCells.mapNotNull { novelByCode(it) } else emptyList())
            .ifEmpty { splitCsv(r.novelTitle).mapNotNull { title -> allNovels.find { it.title == title } } }
        // 세계관 소속: 명시 열(코드 우선 → 이름) 우선, 없으면 관련 작품에서 유도(구버전 호환).
        val explicitUniverse = run {
            val uCode = getCellCode(row, c.universeCode, ctx, result)
            val uName = if (c.universeName >= 0) getCellString(row, c.universeName) else ""
            if (uCode.isBlank() && uName.isBlank()) return@run null
            val resolved = (if (uCode.isNotBlank()) db.universeDao().getUniverseByCode(uCode) else null)
                ?: (if (uName.isNotBlank()) db.universeDao().getUniverseByName(uName) else null)
            if (resolved == null) {
                result?.warnings?.add("$ctx: 세계관 '${uName.ifBlank { uCode }}'을(를) 찾을 수 없어 관련 작품에서 유도합니다")
            }
            resolved
        }
        val derivedUniverseId = resolvedNovels.firstOrNull()?.universeId
        if (explicitUniverse != null && derivedUniverseId != null && explicitUniverse.id != derivedUniverseId) {
            result?.warnings?.add("$ctx: 세계관 열('${explicitUniverse.name}')과 관련 작품의 세계관이 달라 세계관 열을 우선합니다")
        }
        val novelIds = resolvedNovels.map { it.id }
        // 세계관 열이 명시됐으면 그 값, 아니면 작품 해석 성공 시 유도값, 둘 다 없으면 호출부가 기존 세계관을 보존한다.
        return TimelineLinks(
            novelIds = novelIds,
            universeId = if (explicitUniverse != null || novelIds.isNotEmpty()) (explicitUniverse?.id ?: derivedUniverseId) else null
        )
    }

    /**
     * [backfillCode]는 **기존 코드도 파일 코드도 없을 때 새로 붙일 코드**다.
     * 가져오기는 실제 코드를 발급하고, 미리보기는 [CODE_BACKFILL_PREVIEW]를 넘긴다 —
     * 값이 무엇이든 기존이 null이면 결과가 non-null이 되어 '변경'으로 잡히는데, **그것이 사실이다**
     * (가져오기가 그 행에 코드를 심는다). 미리보기가 매번 다른 난수를 만들면 안 되므로 상수를 쓴다.
     */
    private fun mergeTimelineEvent(
        existing: TimelineEvent,
        r: TimelineRowValues,
        links: TimelineLinks,
        backfillCode: String
    ): TimelineEvent =
        existing.copy(
            // 코드 매칭 시 연도·설명은 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
            year = r.year ?: existing.year, description = r.description,
            // 열 없음 = 기존값 유지, 열 있음+빈칸 = 삭제/무값 (외부 편집에서 열 삭제 시 무음 손실 방지)
            month = if (r.hasMonthCol) r.month else existing.month,
            day = if (r.hasDayCol) r.day else existing.day,
            calendarType = if (r.hasCalendarCol) r.calendarType else existing.calendarType,
            eventType = if (r.hasEventTypeCol) r.eventType else existing.eventType,
            universeId = links.universeId ?: existing.universeId,
            displayOrder = r.displayOrder ?: existing.displayOrder,
            isTemporary = if (r.hasTemporaryCol) r.isTemporary else existing.isTemporary,
            createdAt = r.createdAt ?: existing.createdAt,
            // 코드 없는 기존 행은 점진 백필 (기존 코드는 절대 덮어쓰지 않음 — 외부 참조 보호)
            code = existing.code ?: r.fileCode.takeIf { it.isNotBlank() } ?: backfillCode
        )

    private class StateChangeCols(cols: Map<String, Int>, val year: Int, val fieldKey: Int, val newValue: Int) {
        val charName = cols["캐릭터"] ?: 0
        // 위치 폴백 금지 — 열을 지운 파일에서 '연도' 열을 작품 제목으로 읽어(제목이 숫자면 실제로 매칭된다)
        // 동명이인을 엉뚱한 작품 기준으로 무근거 선택한다. 열 없음이면 힌트 없이 엄격 해석한다.
        val novel = cols["작품"] ?: -1
        val month = cols["월"] ?: -1
        val day = cols["일"] ?: -1
        val desc = cols["설명"] ?: -1
        val charCode = cols["캐릭터코드"] ?: -1
        val code = cols["코드"] ?: -1
        val createdAt = cols["생성일"] ?: -1
    }

    private data class StateChangeRowValues(
        val charName: String,
        val charCode: String,
        val novelTitle: String,
        val year: Int?, val yearRaw: String,
        val hasMonthCol: Boolean, val month: Int?,
        val hasDayCol: Boolean, val day: Int?,
        val fieldKey: String,
        val newValue: String,
        val hasDescCol: Boolean, val description: String,
        val fileCode: String,
        val createdAt: Long?
    )

    private fun readStateChangeRow(row: Row, c: StateChangeCols, ctx: String, now: Long, result: ImportResult?): StateChangeRowValues {
        val yearRaw = getCellString(row, c.year)
        val month = parseMonthWithWarn(row, c.month, ctx, result)
        return StateChangeRowValues(
            charName = getCellString(row, c.charName),
            charCode = getCellCode(row, c.charCode, ctx, result),
            novelTitle = getCellString(row, c.novel),
            year = parseNumber(yearRaw)?.toInt(), yearRaw = yearRaw,
            hasMonthCol = c.month >= 0, month = month,
            hasDayCol = c.day >= 0, day = parseDayWithWarn(row, c.day, month, ctx, result),
            fieldKey = getCellString(row, c.fieldKey),
            newValue = getCellString(row, c.newValue),
            hasDescCol = c.desc >= 0, description = getCellString(row, c.desc),
            fileCode = getCellCode(row, c.code, ctx, result),
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )
    }

    /** [mergeTimelineEvent]와 같은 코드 백필 규약. */
    private fun mergeStateChange(
        existing: CharacterStateChange,
        r: StateChangeRowValues,
        characterId: Long,
        backfillCode: String
    ): CharacterStateChange = existing.copy(
        // 코드 매칭 시 자연키 구성 요소도 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
        characterId = characterId, year = r.year ?: existing.year, fieldKey = r.fieldKey, newValue = r.newValue,
        // 열 없음 = 기존값 유지 (열 삭제로 인한 무음 손실 방지)
        month = if (r.hasMonthCol) r.month else existing.month,
        day = if (r.hasDayCol) r.day else existing.day,
        description = if (r.hasDescCol) r.description else existing.description,
        createdAt = r.createdAt ?: existing.createdAt,
        code = existing.code ?: r.fileCode.takeIf { it.isNotBlank() } ?: backfillCode
    )

    private class RelationshipCols(cols: Map<String, Int>, val char2Name: Int, val type: Int) {
        val char1Name = cols["캐릭터1"] ?: 0
        val desc = cols["설명"] ?: -1
        val intensity = cols["강도"] ?: -1
        val bidirectional = cols["양방향"] ?: -1
        val displayOrder = cols["표시순서"] ?: -1
        val char1Code = cols["캐릭터1코드"] ?: -1
        val char2Code = cols["캐릭터2코드"] ?: -1
        val faction = cols["세력"] ?: -1
        val factionCode = cols["세력코드"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        // 관계 자체의 안정 식별자 — 있으면 '관계 유형'을 고쳐도 같은 관계로 인식한다
        val relCode = cols["코드"] ?: -1
    }

    private data class RelationshipRowValues(
        val char1Name: String, val char2Name: String,
        val char1Code: String, val char2Code: String,
        val relationshipType: String,
        val hasDescCol: Boolean, val description: String,
        val hasIntensityCol: Boolean, val intensity: Int,
        val hasBidirectionalCol: Boolean, val isBidirectional: Boolean,
        val displayOrder: Int?,
        val factionName: String, val factionCode: String, val factionIntent: RefIntent,
        val relCode: String,
        val createdAt: Long?
    )

    private fun readRelationshipRow(row: Row, c: RelationshipCols, ctx: String, now: Long, result: ImportResult?): RelationshipRowValues {
        val factionName = if (c.faction >= 0) getCellString(row, c.faction) else ""
        val factionCode = getCellCode(row, c.factionCode, ctx, result)
        return RelationshipRowValues(
            char1Name = getCellString(row, c.char1Name),
            char2Name = getCellString(row, c.char2Name),
            char1Code = getCellCode(row, c.char1Code, ctx, result),
            char2Code = getCellCode(row, c.char2Code, ctx, result),
            relationshipType = getCellString(row, c.type),
            hasDescCol = c.desc >= 0, description = getCellString(row, c.desc),
            hasIntensityCol = c.intensity >= 0, intensity = parseIntensityWithWarn(row, c.intensity, 5, ctx, result) ?: 5,
            hasBidirectionalCol = c.bidirectional >= 0,
            isBidirectional = if (c.bidirectional >= 0) parseBoolean(getCellString(row, c.bidirectional)) else true,
            displayOrder = if (c.displayOrder >= 0) getCellString(row, c.displayOrder).let { if (it.isBlank()) null else parseNumber(it)?.toInt() } else null,
            factionName = factionName, factionCode = factionCode,
            factionIntent = refColumnIntent(c.faction >= 0, c.factionCode >= 0, factionName, factionCode),
            relCode = getCellCode(row, c.relCode, ctx, result),
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )
    }

    private fun mergeRelationship(
        existing: CharacterRelationship,
        r: RelationshipRowValues,
        effectiveFactionId: Long?,
        backfillCode: String
    ): CharacterRelationship = existing.copy(
        // 코드 매칭 시 유형은 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
        relationshipType = r.relationshipType,
        description = if (r.hasDescCol) r.description else existing.description,
        intensity = if (r.hasIntensityCol) r.intensity else existing.intensity,
        isBidirectional = if (r.hasBidirectionalCol) r.isBidirectional else existing.isBidirectional,
        displayOrder = r.displayOrder ?: existing.displayOrder,
        factionId = effectiveFactionId,
        createdAt = r.createdAt ?: existing.createdAt,
        code = existing.code ?: r.relCode.takeIf { it.isNotBlank() } ?: backfillCode
    )

    /** 참조 열 쌍 규약: 열 없음·미해석 → 기존 유지 / 빈칸 → 해제 / 해석 성공 → 교체. */
    private fun effectiveRelationshipFactionId(existing: CharacterRelationship, r: RelationshipRowValues, factionId: Long?): Long? =
        when (r.factionIntent) {
            RefIntent.KEEP -> existing.factionId
            RefIntent.CLEAR -> null
            RefIntent.LOOKUP -> factionId
        }

    private class RelChangeCols(cols: Map<String, Int>, val char2Name: Int, val year: Int) {
        val char1Name = cols["캐릭터1"] ?: 0
        val month = cols["월"] ?: -1
        val day = cols["일"] ?: -1
        // 선택 속성 열: 위치 폴백 제거(-1) — 열 삭제 시 이웃 열 오독·무음 손실 방지.
        val relType = cols["관계 유형"] ?: -1
        val desc = cols["설명"] ?: -1
        val intensity = cols["강도"] ?: -1
        val bidirectional = cols["양방향"] ?: -1
        val eventId = cols["연결사건ID"] ?: -1
        val eventCode = cols["연결사건코드"] ?: -1
        val code = cols["코드"] ?: -1
        val char1Code = cols["캐릭터1코드"] ?: -1
        val char2Code = cols["캐릭터2코드"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        // 부모 관계 식별자 — 코드 우선, 없으면 유형, 둘 다 없으면 쌍 기반 폴백(모호하면 경고)
        val parentType = cols["부모관계유형"] ?: -1
        val parentCode = cols["관계코드"] ?: -1
        val eventColumnPresent = eventCode >= 0 || eventId >= 0
    }

    private data class RelChangeRowValues(
        val char1Name: String, val char2Name: String,
        val char1Code: String, val char2Code: String,
        val year: Int?, val yearRaw: String,
        val hasMonthCol: Boolean, val month: Int?,
        val hasDayCol: Boolean, val day: Int?,
        val hasRelTypeCol: Boolean, val relationshipType: String,
        val hasDescCol: Boolean, val description: String,
        val hasIntensityCol: Boolean, val intensity: Int,
        val hasBidirectionalCol: Boolean, val isBidirectional: Boolean,
        val eventColumnPresent: Boolean, val eventCode: String, val rawEventId: Long?, val hasEventIdCol: Boolean,
        val parentType: String, val parentCode: String,
        val fileCode: String,
        val createdAt: Long?
    )

    private fun readRelChangeRow(row: Row, c: RelChangeCols, ctx: String, now: Long, result: ImportResult?): RelChangeRowValues {
        val yearRaw = getCellString(row, c.year)
        val month = parseMonthWithWarn(row, c.month, ctx, result)
        return RelChangeRowValues(
            char1Name = getCellString(row, c.char1Name),
            char2Name = getCellString(row, c.char2Name),
            char1Code = getCellCode(row, c.char1Code, ctx, result),
            char2Code = getCellCode(row, c.char2Code, ctx, result),
            year = parseNumber(yearRaw)?.toInt(), yearRaw = yearRaw,
            hasMonthCol = c.month >= 0, month = month,
            hasDayCol = c.day >= 0, day = parseDayWithWarn(row, c.day, month, ctx, result),
            hasRelTypeCol = c.relType >= 0, relationshipType = getCellString(row, c.relType),
            hasDescCol = c.desc >= 0, description = getCellString(row, c.desc),
            hasIntensityCol = c.intensity >= 0, intensity = parseIntensityWithWarn(row, c.intensity, 5, ctx, result) ?: 5,
            hasBidirectionalCol = c.bidirectional >= 0,
            // 열 없음 → 엔티티 기본값(양방향 true) — 관계·세력 관계 시트와 동일 규칙
            isBidirectional = if (c.bidirectional >= 0) parseBoolean(getCellString(row, c.bidirectional)) else true,
            eventColumnPresent = c.eventColumnPresent,
            eventCode = getCellCode(row, c.eventCode, ctx, result),
            rawEventId = if (c.eventId >= 0) parseNumber(getCellString(row, c.eventId))?.toLong() else null,
            hasEventIdCol = c.eventId >= 0,
            parentType = if (c.parentType >= 0) getCellString(row, c.parentType) else "",
            parentCode = getCellCode(row, c.parentCode, ctx, result),
            fileCode = getCellCode(row, c.code, ctx, result),
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )
    }

    /** 연결 사건 해석: 코드 우선 (id는 복원·기기 이전 시 변하므로 구버전 폴백 전용). */
    private suspend fun resolveRelChangeEventId(r: RelChangeRowValues, ctx: String, result: ImportResult?): Long? = when {
        r.eventCode.isNotBlank() -> {
            val found = eventByCode(r.eventCode)?.id
            if (found == null) {
                result?.warnings?.add("$ctx: 연결사건코드 '${r.eventCode}'에 해당하는 사건을 찾을 수 없어 연결을 비웁니다")
            }
            found
        }
        r.hasEventIdCol -> {
            // 구버전 id 폴백도 실존 검증 — 복원 후 재발급된 id가 엉뚱한 사건을 가리키거나 FK 오류로 행이 죽는 것 방지
            val rawId = r.rawEventId
            if (rawId != null && eventById(rawId) == null) {
                result?.warnings?.add("$ctx: 연결사건ID '$rawId'에 해당하는 사건이 없어 연결을 비웁니다 — 최신 백업의 연결사건코드 열을 사용하세요")
                null
            } else rawId
        }
        else -> null
    }

    /**
     * 부모 관계 해석: 부모관계유형 열 우선 → 쌍 후보가 유일할 때만 폴백.
     * 같은 쌍에 유형이 다른 관계가 여러 개일 수 있으므로(유니크 키가 쌍+유형),
     * 근거 없이 first-match로 고르면 이력이 엉뚱한 관계에 붙는다.
     */
    private suspend fun resolveRelChangeParent(
        r: RelChangeRowValues,
        pairRelationships: List<CharacterRelationship>,
        char1Id: Long, char2Id: Long,
        ctx: String, result: ImportResult?
    ): CharacterRelationship? {
        val byParentCode = if (r.parentCode.isNotBlank()) {
            pairRelationships.find { it.code == r.parentCode }
                ?: db.characterRelationshipDao().getByCode(r.parentCode)?.takeIf { rel ->
                    (rel.characterId1 == char1Id && rel.characterId2 == char2Id) ||
                    (rel.characterId1 == char2Id && rel.characterId2 == char1Id)
                }
        } else null
        return when {
            // 코드가 최우선 — 부모 관계의 유형이 편집돼도 이력이 정확히 따라간다
            byParentCode != null -> byParentCode
            r.parentType.isNotBlank() -> {
                val exact = pairRelationships.filter { it.relationshipType == r.parentType }
                when {
                    exact.size == 1 -> exact.first()
                    exact.size > 1 -> exact.first()  // 유니크 키상 도달 불가지만 방어적으로 결정적 선택
                    pairRelationships.size == 1 -> {
                        result?.warnings?.add("$ctx: 부모관계유형 '${r.parentType}'과 일치하는 관계가 없어 유일한 '${pairRelationships.first().relationshipType}' 관계에 연결했습니다")
                        pairRelationships.first()
                    }
                    else -> null
                }
            }
            pairRelationships.size == 1 -> pairRelationships.first()
            else -> null
        }
    }

    private fun mergeRelationshipChange(
        existing: CharacterRelationshipChange,
        r: RelChangeRowValues,
        relationshipId: Long,
        eventId: Long?,
        backfillCode: String
    ): CharacterRelationshipChange = existing.copy(
        // 코드 매칭 시 자연키 구성 요소도 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
        // 열 없음 = 기존값 유지 (열 삭제로 인한 무음 손실 방지)
        relationshipId = relationshipId, year = r.year ?: existing.year,
        month = if (r.hasMonthCol) r.month else existing.month,
        day = if (r.hasDayCol) r.day else existing.day,
        relationshipType = if (r.hasRelTypeCol) r.relationshipType else existing.relationshipType,
        description = if (r.hasDescCol) r.description else existing.description,
        intensity = if (r.hasIntensityCol) r.intensity else existing.intensity,
        isBidirectional = if (r.hasBidirectionalCol) r.isBidirectional else existing.isBidirectional,
        eventId = if (r.eventColumnPresent) eventId else existing.eventId,
        createdAt = r.createdAt ?: existing.createdAt,
        code = existing.code ?: r.fileCode.takeIf { it.isNotBlank() } ?: backfillCode
    )

    private class CharacterCols(cols: Map<String, Int>) {
        val name = cols["이름"] ?: 0
        val anotherName = cols["이명"] ?: -1
        val lastName = cols["성"] ?: -1
        val firstName = cols["이름(First)"] ?: -1
        val image = cols["이미지경로"] ?: -1
        val representative = cols["대표이미지"] ?: -1
        val novel = cols["작품"] ?: -1
        val memo = cols["메모"] ?: -1
        val tags = cols["태그"] ?: -1
        val code = cols["코드"] ?: -1
        val novelCode = cols["작품코드"] ?: -1
        val order = cols["정렬순서"] ?: -1
        val pinned = cols["고정"] ?: -1
        val createdAt = cols["생성일"] ?: -1
        // 열이 있으면 셀 해석(빈칸=미배정, 사용자 의도 존중)
        val novelColumnsPresent = novel >= 0 || novelCode >= 0
    }

    private data class CharacterRowValues(
        val name: String,
        val code: String,
        val novelTitle: String,
        val novelCode: String,
        val novelColumnsPresent: Boolean,
        val anotherName: String?,
        val lastName: String?,
        val firstName: String?,
        val imagePaths: String?,
        /** null = '대표이미지' 열 없음(기존 유지). 빈 문자열 = 지정 없음으로 하라(해제). */
        val representativeCell: String?,
        val memo: String?,
        val displayOrder: Long?,
        val isPinned: Boolean?,
        val createdAt: Long?
    )

    private fun readCharacterRow(row: Row, c: CharacterCols, ctx: String, now: Long, result: ImportResult?): CharacterRowValues {
        // imageColIndex < 0 means column is missing: use null sentinel to preserve existing images
        val rawImagePaths: String? = if (c.image >= 0) getCellString(row, c.image).ifBlank { "[]" } else null
        return CharacterRowValues(
            name = getCellString(row, c.name),
            code = getCellCode(row, c.code, ctx, result),  // F4: 숫자 코드 방어
            novelTitle = if (c.novel >= 0) getCellString(row, c.novel) else "",
            novelCode = getCellCode(row, c.novelCode, ctx, result),
            novelColumnsPresent = c.novelColumnsPresent,
            // 열이 없으면(colIndex<0) null → 기존 값 유지. 열이 있으면 셀 값(빈칸="" = 비움, 의도 존중) — F1-A.
            anotherName = if (c.anotherName >= 0) getCellString(row, c.anotherName) else null,
            lastName = if (c.lastName >= 0) getCellString(row, c.lastName) else null,
            firstName = if (c.firstName >= 0) getCellString(row, c.firstName) else null,
            imagePaths = rawImagePaths?.let { remapImagePaths(it) },
            // 대표이미지 열(B-103 D8). **열 없음과 빈 칸은 다른 상태다** —
            // 열 없음은 "말하지 않았다"(기존 유지), 빈 칸은 "지정 없음으로 하라"(해제).
            representativeCell = if (c.representative >= 0) getCellString(row, c.representative) else null,
            memo = if (c.memo >= 0) getCellString(row, c.memo) else null,
            displayOrder = if (c.order >= 0) getCellString(row, c.order).let { if (it.isBlank()) null else parseNumber(it)?.toLong() } else null,
            isPinned = if (c.pinned >= 0) parseBoolean(getCellString(row, c.pinned)) else null,
            createdAt = if (c.createdAt >= 0) (parseNumber(getCellString(row, c.createdAt))?.toLong() ?: now) else null
        )
    }

    /**
     * [now]는 `updatedAt`에 찍을 시각이다 — **내용이 그대로면 찍지 않는다**(프리셋과 같은 규약, 설계 2-2).
     * 종전 가져오기는 아무것도 안 바뀐 행에도 시각을 새로 찍었고, 그러면 미리보기가
     * 모든 행을 '변경'이라 말하거나(쓸모 없음) 비교에서 빼고 거짓말하거나 둘 중 하나가 된다.
     */
    private fun mergeCharacter(
        existing: Character,
        r: CharacterRowValues,
        novelId: Long?,
        rowIndex: Int,
        now: Long,
        result: ImportResult?
    ): Character {
        val content = existing.copy(
            name = r.name,
            firstName = r.firstName ?: existing.firstName,
            lastName = r.lastName ?: existing.lastName,
            anotherName = r.anotherName ?: existing.anotherName,
            novelId = if (r.novelColumnsPresent) novelId else existing.novelId,
            memo = r.memo ?: existing.memo,
            displayOrder = r.displayOrder ?: existing.displayOrder,
            isPinned = r.isPinned ?: existing.isPinned,
            createdAt = r.createdAt ?: existing.createdAt
        ).withImagePaths(r.imagePaths ?: existing.imagePaths, imagePathRemap)
            .let { applyRepresentativeCell(it, r.representativeCell, r.name, rowIndex, result) }
        return if (content == existing) existing else content.copy(updatedAt = now)
    }

    private class ImageMetaCols(cols: Map<String, Int>) {
        val file = cols["파일명"] ?: 0
        // **위치 폴백 금지** — 열을 지우면 이웃 열을 오독한다.
        // 열 없음(-1) = 말한 바 없음 = 기존 유지(F1-A).
        val tag = cols["태그"] ?: -1
        val group = cols["링크그룹"] ?: -1
        // 뗀 이미지 서랍(B-107 D1) — 같은 규약이다.
        val detachedAt = cols["뗀날짜"] ?: -1
    }

    private data class ImageMetaRowValues(
        val fileName: String,
        val hasTagCol: Boolean, val tags: Set<String>,
        val hasGroupCol: Boolean, val groupToken: String?,
        val hasDetachedCol: Boolean, val detachedRaw: String, val detachedAt: Long?
    )

    /**
     * 이미지 한 장의 '표' 상태.
     *
     * 이 범주만 [mergeUniverse] 같은 `copy` 한 줄로 표현되지 않는다 — 상태가 **엔티티 셋**
     * (`ImageMeta` · `ImageTag` 행들 · 링크 그룹)에 걸쳐 있고 가져오기도 세 갈래로 나눠 쓴다.
     * 그래서 상태를 따로 세우고 그 위에서 병합한다. 규약은 다른 범주와 같다.
     */
    private data class ImageMetaState(val tags: Set<String>, val linkGroupId: String?, val detachedAt: Long?)

    private fun readImageMetaRow(row: Row, c: ImageMetaCols, result: ImportResult?): ImageMetaRowValues {
        val detachedRaw = if (c.detachedAt >= 0) getCellString(row, c.detachedAt).trim() else ""
        return ImageMetaRowValues(
            fileName = getCellString(row, c.file),
            hasTagCol = c.tag >= 0,
            tags = if (c.tag >= 0) splitCsv(getCellString(row, c.tag)).toSet() else emptySet(),
            hasGroupCol = c.group >= 0,
            groupToken = if (c.group >= 0) getCellString(row, c.group).trim().ifBlank { null } else null,
            hasDetachedCol = c.detachedAt >= 0,
            detachedRaw = detachedRaw,
            detachedAt = detachedRaw.toDoubleOrNull()?.toLong()?.takeIf { it > 0L }
        )
    }

    /** 행을 기존 상태에 적용한 결과 — 가져오기와 미리보기의 단일 소스(규약 R-33). */
    private fun mergeImageMetaState(existing: ImageMetaState, r: ImageMetaRowValues): ImageMetaState =
        ImageMetaState(
            // F1-A: '태그' 열이 없으면 기존 태그 유지. 열이 있고 빈칸이면 비움 의도로 존중.
            tags = if (r.hasTagCol) r.tags else existing.tags,
            // 빈 칸 = 링크 해제(태그 열과 같은 규약).
            linkGroupId = if (r.hasGroupCol) r.groupToken else existing.linkGroupId,
            // 값이 있는데 숫자가 아니면 **손대지 않는다** — 조용히 버리면 사용자가 무엇을
            // 잘못 적었는지 알 길이 없다(가져오기는 경고만 하고 그대로 둔다).
            detachedAt = when {
                !r.hasDetachedCol -> existing.detachedAt
                r.detachedRaw.isNotBlank() && r.detachedAt == null -> existing.detachedAt
                else -> r.detachedAt
            }
        )

    /**
     * 등급 체계 한 무리를 기존 체계에 적용한 결과 — 가져오기와 미리보기의 단일 소스(규약 R-33).
     *
     * [rename]은 **이름 변경이 같은 세계관의 다른 체계와 충돌하지 않는가**이며 DB 조회가 필요해
     * 호출부가 정한다. 충돌하면 이름을 유지한다(유니크).
     */
    private fun mergeGradeSystem(
        existing: com.novelcharacter.app.data.model.GradeSystem,
        name: String,
        gradesJson: String,
        rename: Boolean
    ): com.novelcharacter.app.data.model.GradeSystem = existing.copy(
        name = if (rename) name else existing.name,
        gradesJson = gradesJson
    )

    suspend fun analyzeAll(
        workbook: Workbook,
        options: ExportOptions = ExportOptions(),
        onProgress: (ImportProgress) -> Unit = {}
    ): RestoreAnalysis {
        val categories = mutableListOf<CategoryAnalysis>()
        var characterConflicts = emptyList<CharacterConflict>()
        processedRowsSoFar = 0
        // 분석은 세계관을 실제로 가져오지 않아 별칭을 쌓지 않는다. 비우지 않으면 직전 가져오기의
        // 별칭이 남아 이 파일과 무관한 세계관으로 프리셋 필터를 해석한다(같은 인스턴스 재사용).
        universeCodeAliases.clear()
        // 캐릭터 정체성 색인도 같은 이유로 비운다 (B-210) — 미리보기는 **직전 가져오기 뒤에**
        // 돌 수 있고, 그 사이에 캐릭터가 바뀌었다. 안 비우면 미리보기가 DB가 아니라
        // 지난 실행의 사본을 세어 *"바뀔 것"*을 사실과 다르게 말한다. 분석은 쓰지 않으므로
        // 여기서 실린 색인은 이 한 번의 분석 동안만 답한다.
        resetCharacterIndex()
        resetEventIndex()
        resetNovelIndex()
        val totalRows = countTotalRows(workbook)

        if (options.universes) categories.add(analyzeUniverses(workbook, onProgress, totalRows))
        if (options.novels) categories.add(analyzeNovels(workbook, onProgress, totalRows))
        if (options.fieldDefinitions) {
            categories.add(analyzeGradeSystems(workbook, onProgress, totalRows))
            categories.add(analyzeDefaultFieldTemplates(workbook, onProgress, totalRows))
            categories.add(analyzeFieldDefinitions(workbook, options, onProgress, totalRows))
            categories.add(analyzeFieldValueLibrary(workbook, onProgress, totalRows))
        }
        if (options.characters) {
            val charResult = analyzeCharacters(workbook, onProgress, totalRows)
            categories.add(charResult.category)
            characterConflicts = charResult.conflicts
        }
        if (options.timeline) categories.add(analyzeTimeline(workbook, onProgress, totalRows))
        if (options.stateChanges) categories.add(analyzeStateChanges(workbook, options, onProgress, totalRows))
        if (options.relationships) categories.add(analyzeRelationships(workbook, options, onProgress, totalRows))
        if (options.relationshipChanges) categories.add(analyzeRelationshipChanges(workbook, options, onProgress, totalRows))
        if (options.nameBank) categories.add(analyzeNameBank(workbook, onProgress, totalRows))
        if (options.factions) categories.add(analyzeFactions(workbook, options, onProgress, totalRows))
        if (options.factionMemberships) categories.add(analyzeFactionMemberships(workbook, onProgress, totalRows))
        if (options.factionRelationships) categories.add(analyzeFactionRelationships(workbook, onProgress, totalRows))
        if (options.presetTemplates) categories.add(analyzePresetTemplates(workbook, onProgress, totalRows))
        if (options.searchPresets) categories.add(analyzeSearchPresets(workbook, onProgress, totalRows))
        if (options.characterListPresets) categories.add(analyzeCharacterListPresets(workbook, onProgress, totalRows))
        if (options.imageMeta) categories.add(analyzeImageMeta(workbook, onProgress, totalRows))
        if (options.duels) {
            categories.add(analyzeDuelAxes(workbook, onProgress, totalRows))
            categories.add(analyzeDuelMatches(workbook, onProgress, totalRows))
        }

        return RestoreAnalysis(categories, characterConflicts)
    }

    // ── 대결 복원 미리보기 (R-33 — 가져오기와 **같은 read/merge 쌍**으로 판정한다) ──

    private suspend fun analyzeDuelAxes(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = duelAxisSpec()
        val label = "대결 축"
        val existingTotal = db.duelAxisDao().getAllList().size
        val sheet = workbook.getSheet(spec.sheetName)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("duelAxes", label, 0, 0, 0, 0, existingTotal)
        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("duelAxes", label, 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("duelAxes", label, 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val now = System.currentTimeMillis()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readDuelAxisRow(row, cols, now)
            if (r.name.isBlank()) continue
            inBackup++

            val universe = (if (r.universeCode.isNotBlank()) db.universeDao().getUniverseByCode(r.universeCode) else null)
                ?: (if (r.universeName.isNotBlank()) db.universeDao().getUniverseByName(r.universeName) else null)
            if (universe == null) { skippedCount++; continue }   // 가져오기도 이 행을 거부한다

            val existing = (if (r.code.isNotBlank()) db.duelAxisDao().getByCode(r.code) else null)
                ?: db.duelAxisDao().getByUniverseAndName(universe.id, r.targetType, r.name)
            if (existing == null) { newCount++; continue }
            val merged = mergeDuelAxis(existing, r, universe.id)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "대결 축 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("duelAxes", label, inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeDuelMatches(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = duelMatchSpec()
        val label = "대결 기록"
        val existingTotal = db.duelMatchDao().countAll()
        val sheet = workbook.getSheet(spec.sheetName)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("duelMatches", label, 0, 0, 0, 0, existingTotal)
        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("duelMatches", label, 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("duelMatches", label, 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val now = System.currentTimeMillis()
        // 가져오기와 **같은 색인**을 세운다 — 행마다 조회하면 수만 행에서 미리보기가 멎는다.
        val axes = db.duelAxisDao().getAllList()
        val axisByCode = axes.associateBy { it.code }
        val axesByName = axes.groupBy { it.name }
        val codeByName = db.characterDao().getAllCharactersList()
            .groupBy({ it.displayName }, { it.code })

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readDuelMatchRow(row, cols, now)
            if (r.axisName.isBlank() && r.axisCode.isBlank()) continue
            inBackup++

            val axis = axisByCode[r.axisCode] ?: axesByName[r.axisName]?.singleOrNull()
            if (axis == null) { skippedCount++; continue }

            val aCode = r.aCode.ifBlank { codeByName[r.aName]?.singleOrNull().orEmpty() }
            val bCode = r.bCode.ifBlank { codeByName[r.bName]?.singleOrNull().orEmpty() }
            if (aCode.isBlank() || bCode.isBlank() || aCode == bCode) { skippedCount++; continue }
            val winner = resolveDuelWinner(r.winnerText, aCode, r.aName, bCode, r.bName)
            if (winner.isFailure) { skippedCount++; continue }

            val existing = if (r.code.isNotBlank()) db.duelMatchDao().getByCode(r.code) else null
            if (existing == null) { newCount++; continue }
            val merged = mergeDuelMatch(existing, winner.getOrNull(), r.groupId.ifBlank { null })
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "대결 기록 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("duelMatches", label, inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeFieldValueLibrary(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = fieldValueLibrarySpec()
        val label = "필드 데이터"
        val existingEntries = db.fieldValueEntryDao().getAllList()
        val existingTotal = existingEntries.size
        val sheet = workbook.getSheet(spec.sheetName)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("fieldValueLibrary", label, 0, 0, 0, 0, existingTotal)
        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("fieldValueLibrary", label, 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("fieldValueLibrary", label, 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val universeCol = cols["세계관"] ?: 0
        val keyCol = cols["필드키"] ?: 1
        val entityCol = cols["대상"] ?: -1
        val valueCol = cols["값"] ?: 4
        val labelCol = cols["표시라벨"] ?: -1
        val aliasCol = cols["별칭(콤마구분)"] ?: cols["별칭"] ?: -1
        val categoryCol = cols["카테고리"] ?: -1
        val descCol = cols["설명"] ?: -1
        val hiddenCol = cols["숨김"] ?: -1
        val codeCol = cols["코드"] ?: -1
        val sourceCol = cols["출처"] ?: -1

        // 실제 가져오기와 같은 경로로 필드를 찾는다: 세계관명 → 필드(키, 대상). 행마다 쿼리하지
        // 않으려고 한 번에 읽어 (세계관, 키, 대상)으로 색인할 뿐, getFieldByKey와 같은 결과다.
        val universesByName = db.universeDao().getAllUniversesList().associateBy { it.name }
        val fieldsByKey = db.fieldDefinitionDao().getAllFieldsAllTypes()
            .associateBy { Triple(it.universeId, it.key, it.entityType) }
        val entriesByField = existingEntries.groupBy { it.fieldDefinitionId }

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val value = getCellString(row, valueCol)
            if (value.isBlank()) continue
            inBackup++
            val universeName = getCellString(row, universeCol)
            val fieldKey = getCellString(row, keyCol)
            val imported = FieldValueSheetMapper.ImportedRow(
                universeName = universeName,
                fieldKey = fieldKey,
                entityLabel = if (entityCol >= 0) getCellString(row, entityCol) else null,
                value = value,
                displayLabel = if (labelCol >= 0) getCellString(row, labelCol) else null,
                aliasesCsv = if (aliasCol >= 0) getCellString(row, aliasCol) else null,
                category = if (categoryCol >= 0) getCellString(row, categoryCol) else null,
                description = if (descCol >= 0) getCellString(row, descCol) else null,
                hiddenFlag = if (hiddenCol >= 0) getCellString(row, hiddenCol) else null,
                code = if (codeCol >= 0) getCellString(row, codeCol) else null,
                sourceFlag = if (sourceCol >= 0) getCellString(row, sourceCol) else null
            )
            // 세계관·필드가 아직 없으면 **신규**다 — 같은 가져오기가 세계관·필드 정의를 먼저
            // 만들고 나서 이 시트를 처리하므로(임포트 순서), 실제로 새 엔트리가 된다.
            //
            // 세계관 칸이 빈 행 = 전역 구역(무소속)이라는 판정은 가져오기와 **같아야 한다**(R-33).
            // 종전에는 여기만 그 갈래가 없어 전역 행을 전부 '신규'로 세었는데, 정작 가져오기는
            // 그 행을 건너뛰었다 — 미리보기가 약속한 건수가 실제와 다른 거짓 예고였다(B-130).
            val globalScope = FieldScopeCell.isGlobal(universeName)
            val universe = if (globalScope) null else universesByName[universeName]
            val fd = if (globalScope) {
                fieldsByKey[Triple(null, fieldKey, imported.entityType)]
            } else {
                universe?.let { fieldsByKey[Triple(it.id, fieldKey, imported.entityType)] }
            }
            if (fd == null) { newCount++; continue }

            // '동일'은 매칭 키가 아니라 **가져오기가 실제로 쓰는 값 전체**로 가른다(B-87).
            // 건너뛸 행(값 충돌 등)은 바뀌는 것이 없으므로 어느 쪽으로도 세지 않는다 —
            // 세력 소속 분석이 해석 불가 행을 세지 않는 것과 같은 처분이다.
            val siblings = entriesByField[fd.id] ?: emptyList()
            val existing = FieldValueSheetMapper.match(siblings, imported)
            when (FieldValueSheetMapper.effectOf(existing, fd.id, imported, siblings)) {
                FieldValueSheetMapper.RowEffect.NEW -> newCount++
                FieldValueSheetMapper.RowEffect.UPDATED -> updateCount++
                FieldValueSheetMapper.RowEffect.UNCHANGED -> unchangedCount++
                FieldValueSheetMapper.RowEffect.SKIPPED -> {}
            }
        }
        reportProgress(onProgress, label, sheet.lastRowNum, totalRows)
        return CategoryAnalysis("fieldValueLibrary", label, inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeImageMeta(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = imageMetaSpec()
        val label = "이미지 태그·링크"
        val existingTotal = db.imageMetaDao().getAllList().size
        val sheet = workbook.getSheet(spec.sheetName)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("imageMeta", label, 0, 0, 0, 0, existingTotal)
        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("imageMeta", label, 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("imageMeta", label, 0, 0, 0, 0, existingTotal)

        // **위치 폴백 금지 — 가져오기와 같은 규칙이다.** 종전에는 분석만 `?: 1`·`?: 2`였고,
        // 그래서 '태그' 열을 지운 시트에서 분석은 1번 열(=링크그룹)을 태그로 읽어 '변경'이라
        // 말하는데 가져오기는 태그를 손대지 않았다.
        val c = ImageMetaCols(resolveHeaderColumns(headerRow))

        val remapByBasename = HashMap<String, String>()
        for ((origPath, newPath) in imagePathRemap) {
            remapByBasename[java.io.File(origPath).name] = newPath
        }
        val filesDir = appContext?.filesDir

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readImageMetaRow(row, c, result = null)
            if (r.fileName.isBlank()) continue
            inBackup++
            val path = remapByBasename[r.fileName]
                ?: filesDir?.let { dir -> java.io.File(dir, r.fileName).takeIf { it.exists() }?.absolutePath }
                ?: continue  // 파일 미해석 행은 new/update에 계상하지 않음 (가져오기에서 스킵 경고)
            val existing = db.imageMetaDao().getByPath(path)
            if (existing == null) { newCount++; continue }
            // 태그는 열이 있을 때만 읽는다 — 열이 없으면 비교 대상이 아니라 조회도 낭비다.
            val current = ImageMetaState(
                tags = if (r.hasTagCol) db.imageTagDao().getTagsByImageList(existing.id).map { it.tag }.toSet() else emptySet(),
                linkGroupId = existing.linkGroupId,
                detachedAt = existing.detachedAt
            )
            if (mergeImageMetaState(current, r) != current) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "이미지 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("imageMeta", label, inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeUniverses(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = universeSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.universeDao().getAllUniversesList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)

        val c = UniverseCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val now = System.currentTimeMillis()

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            // 읽기도 가져오기와 **같은 함수**다 — 비교식만 맞추고 리더를 각자 두면
            // 같은 결함이 한 겹 아래에서 되살아난다(설계 1-1).
            val r = readUniverseRow(row, c, "세계관 행 $i", now, result = null)
            if (r.name.isBlank()) continue
            inBackup++

            // 매칭도 가져오기와 같다: 코드가 있으나 DB에 없으면 **이름으로 폴백**한다.
            // 종전에는 그 행을 '신규'로 셌으나 가져오기는 기존 세계관을 덮어쓴다.
            val existing = (if (r.code.isNotBlank()) db.universeDao().getUniverseByCode(r.code) else null)
                ?: db.universeDao().getUniverseByName(r.name)
            if (existing == null) { newCount++; continue }
            // 지연 해석 열은 **되붙은 뒤의 순효과**로 비교한다(설계 2-3).
            // 코드가 비었거나 해석되지 않으면 가져오기도 결국 null로 두므로 null이 맞다.
            val merged = mergeUniverse(
                existing, r,
                imageCharacterId = r.imageCharCode?.let { characterByCode(it)?.id },
                imageNovelId = r.imageNovelCode?.let { novelByCode(it)?.id }
            )
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세계관 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("universes", "세계관", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeNovels(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = novelSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.novelDao().getAllNovelsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)

        val c = NovelCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val now = System.currentTimeMillis()

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readNovelRow(row, c, "작품 행 $i", now, result = null)
            if (r.title.isBlank()) continue
            inBackup++

            // 세계관 해석도 가져오기와 같다 — 코드 우선, 이름 폴백.
            val universeId = (if (r.universeCode.isNotBlank()) db.universeDao().getUniverseByCode(r.universeCode)?.id else null)
                ?: (if (r.universeName.isNotBlank()) db.universeDao().getUniverseByName(r.universeName)?.id else null)
            // 매칭도 가져오기와 같다: 코드가 있으나 DB에 없으면 제목+세계관으로 폴백한다.
            // 괄호 필수 — 괄호 없이 쓰면 엘비스가 `else` 가지(null)에만 붙어
            // 코드 미해석 시 제목 폴백이 죽는다(가져오기 쪽 주석과 같은 함정).
            val existing = novelByCode(r.code)
                ?: (if (universeId != null) db.novelDao().getNovelByTitleAndUniverse(r.title, universeId)
                    else db.novelDao().getNovelByTitleNoUniverse(r.title))
            if (existing == null) { newCount++; continue }
            val merged = mergeNovel(
                existing, r,
                effectiveUniverseId = effectiveNovelUniverseId(existing, r, universeId),
                imageCharacterId = r.imageCharCode?.let { characterByCode(it)?.id }
            )
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "작품 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("novels", "작품", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFieldDefinitions(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = fieldDefinitionSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName)
        // 캐릭터+사건 필드 모두 시트에 실리므로 기존 총계도 전 타입 기준 (프리뷰 정확성)
        val existingTotal = db.fieldDefinitionDao().getAllFieldsAllTypes().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)
        val c = FieldDefCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readFieldDefRow(row, c, "필드 행 $i", result = null)
            // 세계관·코드 둘 다 빈 행 = 전역 구역(B-119 확장) — 가져오기와 **같은 판정**(R-33).
            val globalScope = FieldScopeCell.isGlobal(r.universeName, r.universeCode)
            if (r.universeName.isBlank() && !globalScope) continue
            if (r.key.isBlank()) continue
            inBackup++

            val universe = if (globalScope) null else {
                val found = (if (r.universeCode.isNotBlank()) db.universeDao().getUniverseByCode(r.universeCode) else null)
                    ?: db.universeDao().getUniverseByName(r.universeName)
                if (found == null) {
                    // B-102 ⓑ: '세계관'을 함께 가져오면 그것이 먼저 생기므로 '신규'가 맞고,
                    // 빼놓았으면 가져오기가 이 행을 경고와 함께 **건너뛴다**.
                    if (options.universes) newCount++ else skippedCount++
                    continue
                }
                found
            }

            val existing = if (universe != null) {
                db.fieldDefinitionDao().getFieldByKey(universe.id, r.key, r.entityType)
            } else {
                db.fieldDefinitionDao().getGlobalFieldByKey(r.key, r.entityType)
            }
            if (existing == null) { newCount++; continue }
            val merged = mergeFieldDefinition(
                existing, r,
                resolveFieldDefConfig(universe?.id, i, r, existing, result = null)
            )
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "필드 정의 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("fieldDefinitions", "필드 정의", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    data class CharacterAnalysisResult(
        val category: CategoryAnalysis,
        val conflicts: List<CharacterConflict>
    )

    private suspend fun analyzeCharacters(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CharacterAnalysisResult {
        val existingTotal = db.characterDao().getAllCharactersList().size
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        val allConflicts = mutableListOf<CharacterConflict>()

        // 세계관별 캐릭터 시트 분석
        val universes = db.universeDao().getAllUniversesList()
        val reservedNames = RESERVED_SHEET_NAMES
        // 미리보기도 본 임포트와 같은 배정을 봐야 한다 — 같은 시트를 두 번 세면 건수가 부풀고
        // 충돌 항목이 중복된다(conflictKey가 "$sheetLabel:$i"라 양쪽이 같은 키를 만든다).
        val analyzedSheetNames = mutableSetOf<String>()
        for (universe in universes) {
            val sheet = findSheetForUniverse(workbook, universe.name, reservedNames) ?: continue
            val headerRow = sheet.getRow(0) ?: continue
            if (!isValidHeader(headerRow, "이름")) continue
            analyzedSheetNames.add(sheet.sheetName)
            val result = analyzeCharacterSheet(sheet, headerRow, universe.name)
            inBackup += result.first; newCount += result.second; updateCount += result.third
            unchangedCount += (result.first - result.second - result.third - result.fifth)
            allConflicts.addAll(result.fourth)
        }

        // 미분류 캐릭터 분석
        val unclSheet = findUnclassifiedSheet(workbook, analyzedSheetNames)
        if (unclSheet != null) {
            val headerRow = unclSheet.getRow(0)
            if (headerRow != null && isValidHeader(headerRow, "이름")) {
                val result = analyzeCharacterSheet(unclSheet, headerRow, UNCLASSIFIED_SHEET_NAME)
                inBackup += result.first; newCount += result.second; updateCount += result.third
                unchangedCount += (result.first - result.second - result.third - result.fifth)
                allConflicts.addAll(result.fourth)
            }
        }

        reportProgress(onProgress, "캐릭터 분석", 0, totalRows)
        val category = CategoryAnalysis("characters", "캐릭터", inBackup, newCount, updateCount, unchangedCount, existingTotal)
        return CharacterAnalysisResult(category, allConflicts)
    }

    /** first=inBackup, second=newCount, third=updateCount, fourth=conflicts, fifth=conflictCount */
    private data class SheetAnalysis(val first: Int, val second: Int, val third: Int, val fourth: List<CharacterConflict>, val fifth: Int = 0)

    private suspend fun analyzeCharacterSheet(sheet: Sheet, headerRow: Row, sheetLabel: String): SheetAnalysis {
        // 실제 임포트와 동일한 고정 열 우선 해석 — 미리보기 건수가 임포트 결과와 어긋나지 않게 한다
        val c = CharacterCols(resolveHeaderColumns(headerRow, reservedHeaders = CHARACTER_FIXED_HEADERS))
        val now = System.currentTimeMillis()

        var inBackup = 0; var newCount = 0; var updateCount = 0; var conflictCount = 0
        val conflicts = mutableListOf<CharacterConflict>()

        // 미리보기가 예고하는 '변경'을 판정하는 자리 — 가져오기와 **같은 함수**를 쓴다(규약 R-33).
        // 종전에는 이름·메모·이명 셋만 봤다: 성·이름(First)·작품·정렬순서·고정·생성일·
        // 이미지경로·대표이미지를 고쳐도 '변경 없음'이라 말했다.
        suspend fun countAgainst(existing: Character, r: CharacterRowValues, rowIndex: Int) {
            val novelId = novelByCode(r.novelCode)?.id
                ?: (if (r.novelTitle.isNotBlank()) db.novelDao().getAllNovelsList().find { it.title == r.novelTitle }?.id else null)
            if (mergeCharacter(existing, r, novelId, rowIndex, now, result = null) != existing) updateCount++
        }

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readCharacterRow(row, c, "캐릭터 행 $i", now, result = null)
            val name = r.name
            if (name.isBlank()) continue
            inBackup++

            if (r.code.isNotBlank()) {
                // 코드 기반 매칭: 충돌 없음 (코드가 권위적)
                val existing = characterByCode(r.code)
                if (existing == null) { newCount++; continue }
                countAgainst(existing, r, i)
            } else {
                // 코드 없음: 이름 기반 매칭 — 동명이인 충돌 가능
                val allMatches = charactersByName(name)
                if (allMatches.isEmpty()) {
                    newCount++
                } else if (allMatches.size == 1) {
                    countAgainst(allMatches[0], r, i)
                } else {
                    // 다중 매칭: 충돌 발생
                    conflicts.add(CharacterConflict(
                        excelRowIndex = i,
                        sheetName = sheetLabel,
                        excelName = name,
                        excelNovelTitle = r.novelTitle.ifBlank { null },
                        existingCharacters = allMatches
                    ))
                    // 충돌 행은 사용자 결정 전까지 분류 미정 — 별도 카운트
                    conflictCount++
                }
            }
        }
        return SheetAnalysis(inBackup, newCount, updateCount, conflicts, conflictCount)
    }

    private suspend fun analyzeTimeline(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = timelineSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.timelineDao().getAllEventsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일한 설명 열 해석 — 없으면 가져오기도 시트를 통째로 건너뛴다.
        val descColIndex = TimelineCols.descColumn(cols)
            ?: return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)
        val c = TimelineCols(cols, descColIndex)
        val now = System.currentTimeMillis()
        val allNovels = db.novelDao().getAllNovelsList()

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readTimelineRow(row, c, "연표 행 $i", now, result = null)
            val year = r.year ?: continue
            if (r.description.isBlank()) continue
            inBackup++

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val existing = eventByCode(r.fileCode) ?: eventByNaturalKey(year, r.description)
            if (existing == null) { newCount++; continue }
            // 종전에는 **자연키로 매칭된 행을 무조건 '동일'**로 셌다 — 월·일·역법·유형·세계관·
            // 정렬순서·임시배치를 고쳐도 미리보기가 '변경 없음'이라 말했다.
            val links = resolveTimelineLinks(row, c, r, allNovels, "연표 행 $i", result = null)
            val merged = mergeTimelineEvent(existing, r, links, CODE_BACKFILL_PREVIEW)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "사건 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("timeline", "사건 연표", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeStateChanges(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = stateChangeSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.characterStateChangeDao().getAllChangesList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일하게 필수 열이 없으면 시트를 통째로 건너뛴다(위치 폴백 금지).
        val yearColIndex = cols["연도"] ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val fieldKeyColIndex = cols["필드키"] ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val newValueColIndex = cols["새 값"] ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val c = StateChangeCols(cols, yearColIndex, fieldKeyColIndex, newValueColIndex)
        val now = System.currentTimeMillis()

        val allNovels = db.novelDao().getAllNovelsList()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readStateChangeRow(row, c, "상태변화 행 $i", now, result = null)
            if (r.charName.isBlank()) continue
            val year = r.year ?: continue
            if (r.fieldKey.isBlank()) continue
            if (r.newValue.isBlank()) continue
            inBackup++

            val character = characterByCode(r.charCode)
                ?: run {
                    val novelId = if (r.novelTitle.isNotBlank()) allNovels.find { it.title == r.novelTitle }?.id else null
                    findCharacterByName(r.charName, novelId)
                }
            // 캐릭터가 해석되지 않으면 가져오기가 행을 거부한다(B-102 ⓑ).
            if (character == null) {
                if (options.characters) newCount++ else skippedCount++
                continue
            }

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val existing = (if (r.fileCode.isNotBlank()) db.characterStateChangeDao().getChangeByCode(r.fileCode) else null)
                ?: db.characterStateChangeDao().getChangeByNaturalKey(character.id, year, r.fieldKey, r.newValue)
            if (existing == null) { newCount++; continue }
            // 종전에는 자연키로 매칭된 행을 무조건 '동일'로 셌다 — 월·일·설명을 고쳐도 그랬다.
            val merged = mergeStateChange(existing, r, character.id, CODE_BACKFILL_PREVIEW)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "상태 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("stateChanges", "상태 변화", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeRelationships(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = relationshipSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.characterRelationshipDao().getAllRelationships().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일하게 필수 열이 없으면 시트를 통째로 건너뛴다(위치 폴백 금지).
        val char2NameColIndex = cols["캐릭터2"] ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val typeColIndex = cols["관계 유형"] ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val c = RelationshipCols(cols, char2NameColIndex, typeColIndex)
        val now = System.currentTimeMillis()
        // 세력 참조 해석은 FactionIndex(단일 소스)로 — 전 세계관 first-match 금지
        val factionRefUsed = c.faction >= 0 || c.factionCode >= 0
        val factionIndex = FactionIndex(if (factionRefUsed) db.factionDao().getAllFactionsList() else emptyList())

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readRelationshipRow(row, c, "관계 행 $i", now, result = null)
            if (r.char1Name.isBlank() || r.char2Name.isBlank()) continue
            if (r.relationshipType.isBlank()) continue
            inBackup++

            val char1 = characterByCode(r.char1Code)
                ?: findCharacterByName(r.char1Name, null)
            val char2 = characterByCode(r.char2Code)
                ?: findCharacterByName(r.char2Name, null)

            // 실제 임포트와 동일한 매칭 규약: 코드(안정 식별자) 우선 → 자연키(쌍+유형) 폴백
            val byCode = if (r.relCode.isNotBlank()) db.characterRelationshipDao().getByCode(r.relCode) else null
            val existing = byCode ?: run {
                if (char1 == null || char2 == null || char1.id == char2.id) return@run null
                db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id).find { rel ->
                    ((rel.characterId1 == char1.id && rel.characterId2 == char2.id) ||
                        (rel.characterId1 == char2.id && rel.characterId2 == char1.id)) && rel.relationshipType == r.relationshipType
                }
            }
            if (existing == null) {
                // 캐릭터가 해석되지 않으면 가져오기가 행을 거부한다(B-102 ⓑ).
                if (char1 == null || char2 == null || char1.id == char2.id) {
                    if (options.characters) newCount++ else skippedCount++
                    continue
                }
                newCount++
                continue
            }
            // 종전에는 설명 하나만 봤다 — 강도·양방향·표시순서·세력을 고쳐도 '동일'이라 말했다.
            val factionId = if (r.factionIntent != RefIntent.LOOKUP) null else
                (factionIndex.resolve(r.factionName, r.factionCode, char1?.let { universeIdOfCharacter(it) })
                    as? FactionLookupResult.Found)?.faction?.id
            val merged = mergeRelationship(
                existing, r,
                effectiveRelationshipFactionId(existing, r, factionId),
                CODE_BACKFILL_PREVIEW
            )
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationships", "관계", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeRelationshipChanges(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val sheet = workbook.getSheet("관계 변화")
        val existingTotal = db.characterRelationshipChangeDao().getAllChanges().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일하게 필수 열이 없으면 시트를 통째로 건너뛴다(위치 폴백 금지).
        val char2NameColIndex = cols["캐릭터2"] ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val yearColIndex = cols["연도"] ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val c = RelChangeCols(cols, char2NameColIndex, yearColIndex)
        val now = System.currentTimeMillis()

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readRelChangeRow(row, c, "관계변화 행 $i", now, result = null)
            if (r.char1Name.isBlank() || r.char2Name.isBlank()) continue
            val year = r.year ?: continue
            inBackup++

            val char1 = characterByCode(r.char1Code)
                ?: findCharacterByName(r.char1Name, null)
            val char2 = characterByCode(r.char2Code)
                ?: findCharacterByName(r.char2Name, null)
            // 캐릭터가 해석되지 않으면 가져오기가 행을 거부한다(B-102 ⓑ).
            if (char1 == null || char2 == null) {
                if (options.characters) newCount++ else skippedCount++
                continue
            }

            // 부모 관계 해석도 가져오기와 **같은 함수**다 — 종전에는 쌍의 첫 관계를 골라
            // 유형이 다른 이력을 엉뚱한 관계에 붙여 세었다.
            val pairRelationships = db.characterRelationshipDao()
                .getRelationshipsForCharacterList(char1.id)
                .filter { rel ->
                    (rel.characterId1 == char1.id && rel.characterId2 == char2.id) ||
                    (rel.characterId1 == char2.id && rel.characterId2 == char1.id)
                }
            // 관계가 없거나 확정되지 않으면 가져오기가 행을 거부한다(B-102 ⓑ).
            if (pairRelationships.isEmpty()) {
                if (options.relationships) newCount++ else skippedCount++
                continue
            }
            val relationship = resolveRelChangeParent(r, pairRelationships, char1.id, char2.id, "관계변화 행 $i", result = null)
            if (relationship == null) { skippedCount++; continue }

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val existing = (if (r.fileCode.isNotBlank()) db.characterRelationshipChangeDao().getChangeByCode(r.fileCode) else null)
                ?: db.characterRelationshipChangeDao().getChangeByNaturalKey(relationship.id, year, r.month, r.day)
            if (existing == null) { newCount++; continue }
            // 종전에는 자연키로 매칭된 행을 **무조건 '동일'**로 셌다 —
            // 관계 유형·설명·강도·양방향·연결 사건을 고쳐도 '변경 없음'이라 말했다.
            val merged = mergeRelationshipChange(
                existing, r, relationship.id,
                resolveRelChangeEventId(r, "관계변화 행 $i", result = null),
                CODE_BACKFILL_PREVIEW
            )
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationshipChanges", "관계 변화", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeNameBank(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = nameBankSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingNames = db.nameBankDao().getAllNamesList()
        val existingTotal = existingNames.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)
        val c = NameBankCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        // 가져오기가 굴리는 것과 **같은 맵**이다 — 새로 만든 행을 즉시 등재해야
        // 시트 안에 같은 이름이 두 번 나올 때 둘째 행이 첫째와 매칭된다(B-102 ⓐ).
        // 등재하지 않으면 미리보기만 '신규 2'라 말하고 가져오기는 '신규 1 + 변경/동일 1'을 한다.
        val existingMap = existingNames.associateBy { it.mapKeyForNameBank() }.toMutableMap()
        val existingByCode = existingNames.filter { it.code.isNotBlank() }.associateBy { it.code }.toMutableMap()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readNameBankRow(row, c, "이름 은행 행 $i", now, result = null)
            if (r.name.isBlank()) continue
            inBackup++

            // F3-D: 코드 우선 매칭(이름/성별을 편집해도 같은 항목 인식) → 자연키(이름+성별) 폴백
            val existing = (if (r.code.isNotBlank()) existingByCode[r.code] ?: db.nameBankDao().getByCode(r.code) else null)
                ?: existingMap[r.mapKey]
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만든 항목 — 뒤 행이 이것과 매칭될 수 있다.
                val created = NameBankEntry(
                    name = r.name, gender = r.gender, origin = r.origin, notes = r.notes,
                    isUsed = r.usedFlag ?: false,
                    usedByCharacterId = resolveNameBankUsedBy(r, null, "이름 은행 행 $i", result = null),
                    createdAt = r.createdAt ?: now,
                    code = r.code.ifBlank { "" }
                )
                existingMap[r.mapKey] = created
                if (created.code.isNotBlank()) existingByCode[created.code] = created
                continue
            }
            val merged = mergeNameBankEntry(existing, r, resolveNameBankUsedBy(r, existing, "이름 은행 행 $i", result = null))
            if (merged != existing) updateCount++ else unchangedCount++
            // 갱신된 값도 되돌려 놓는다 — 같은 항목을 가리키는 뒤 행은 이 결과 위에서 판정된다.
            existingMap[merged.mapKeyForNameBank()] = merged
            if (merged.code.isNotBlank()) existingByCode[merged.code] = merged
        }
        reportProgress(onProgress, "이름 은행 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("nameBank", "이름 은행", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFactions(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.factionDao().getAllFactionsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)
        val c = FactionCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val now = System.currentTimeMillis()

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readFactionRow(row, c, "세력 행 $i", now, result = null)
            if (r.name.isBlank()) continue
            inBackup++

            // 자동관계유형이 비면 가져오기가 행을 거부한다 — '신규'가 아니다.
            if (r.autoRelationType.isBlank()) { skippedCount++; continue }
            // 세계관 해석도 가져오기와 같다 — 코드 우선, 이름 폴백(종전에는 이름만 봤다).
            val resolvedUniverse = (if (r.universeCode.isNotBlank()) db.universeDao().getUniverseByCode(r.universeCode) else null)
                ?: (if (r.universeName.isNotBlank()) db.universeDao().getUniverseByName(r.universeName) else null)
            // 세계관 참조가 있는데 해석되지 않으면 가져오기가 행을 거부한다(B-102 ⓑ).
            if (resolvedUniverse == null && (r.universeName.isNotBlank() || r.universeCode.isNotBlank())) {
                if (options.universes) newCount++ else skippedCount++
                continue
            }
            val existing = (if (r.code.isNotBlank()) db.factionDao().getByCode(r.code) else null)
                ?: resolvedUniverse?.let { db.factionDao().getByNameAndUniverse(r.name, it.id) }
            if (existing == null) { newCount++; continue }
            val merged = mergeFaction(existing, r, universeId = resolvedUniverse?.id ?: existing.universeId)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세력 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factions", "세력", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeFactionMemberships(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionMembershipSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.factionMembershipDao().getAllMembershipsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val factionNameColIndex = cols[spec.firstColumnHeader] ?: cols["세력"] ?: 0
        val charNameColIndex = cols["캐릭터"] ?: -1
        val factionCodeColIndex = cols["세력코드"] ?: -1
        val charCodeColIndex = cols["캐릭터코드"] ?: -1
        val joinYearColIndex = cols["가입연도"] ?: -1
        val leaveYearColIndex = cols["탈퇴연도"] ?: -1
        val leaveTypeColIndex = cols["탈퇴유형"] ?: -1
        val departedRelTypeColIndex = cols["탈퇴후관계유형"] ?: -1
        val departedIntensityColIndex = cols["탈퇴후강도"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        if (charNameColIndex < 0) return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)

        // 루프 밖에서 1회 — 행마다 전체 세력/소속을 다시 읽지 않는다.
        // 소속은 위에서 existingTotal 때문에 이미 한 번 읽었으므로 그 목록을 쌍으로 묶어 재사용한다.
        val factionIndex = FactionIndex(db.factionDao().getAllFactionsList())
        val membershipsByPair = db.factionMembershipDao().getAllMembershipsList()
            .groupBy { it.factionId to it.characterId }
        val presence = factionMembershipPresence(
            joinYearColIndex, leaveYearColIndex, leaveTypeColIndex,
            departedRelTypeColIndex, departedIntensityColIndex, createdAtColIndex
        )
        // 한 쌍에 이력이 여럿일 수 있으므로 실제 가져오기처럼 **이미 가져간 행을 뺀다**.
        val takenIds = mutableSetOf<Long>()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val factionName = getCellString(row, factionNameColIndex)
            if (factionName.isBlank()) continue
            val charName = getCellString(row, charNameColIndex)
            if (charName.isBlank()) continue
            inBackup++

            val factionCode = if (factionCodeColIndex >= 0) getCellString(row, factionCodeColIndex) else ""
            val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""

            // 캐릭터를 먼저 — 세력의 동명 해소 힌트가 된다(실제 임포트와 같은 순서)
            val character = characterByCode(charCode)
                ?: characterByName(charName)
            if (character == null) { continue }
            // NotFound·Ambiguous 모두 기존 null 분기와 동일하게 처리해 계수 의미를 보존한다
            val faction = (factionIndex.resolve(factionName, factionCode, universeIdOfCharacter(character))
                as? FactionLookupResult.Found)?.faction
            if (faction == null) { continue }

            // 매칭 규칙은 실제 가져오기와 **같은 함수**를 쓴다(FactionMembershipMatcher).
            // 활성만 보던 종전 규칙은 탈퇴 이력 행을 매번 '신규'로, 나아가 '백업에 없음'으로
            // 세어 아무것도 안 고친 파일에 삭제를 예고했다 — 실제로는 매칭돼 그대로 남는데도.
            val rowValues = FactionMembershipMatcher.RowValues(
                joinYear = if (joinYearColIndex >= 0) parseNumber(getCellString(row, joinYearColIndex))?.toInt() else null,
                leaveYear = if (leaveYearColIndex >= 0) parseNumber(getCellString(row, leaveYearColIndex))?.toInt() else null,
                leaveType = parseFactionLeaveType(if (leaveTypeColIndex >= 0) getCellString(row, leaveTypeColIndex) else ""),
                departedRelationType = if (departedRelTypeColIndex >= 0) getCellString(row, departedRelTypeColIndex).ifBlank { null } else null,
                departedIntensity = if (departedIntensityColIndex >= 0) parseNumber(getCellString(row, departedIntensityColIndex))?.toInt() else null,
                createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() else null
            )
            val candidates = (membershipsByPair[faction.id to character.id] ?: emptyList())
                .filter { it.id !in takenIds }
            val existingMembership = FactionMembershipMatcher.match(candidates, rowValues)
            if (existingMembership == null) {
                newCount++
            } else {
                takenIds.add(existingMembership.id)
                if (FactionMembershipMatcher.changes(existingMembership, rowValues, presence)) updateCount++ else unchangedCount++
            }
        }
        reportProgress(onProgress, "세력 소속 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factionMemberships", "세력 소속", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFactionRelationships(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionRelationshipSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingRels = db.factionRelationshipDao().getAllRelationshipsList()
        val existingTotal = existingRels.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factionRelationships", "세력 관계", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("factionRelationships", "세력 관계", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val faction1ColIndex = cols[spec.firstColumnHeader] ?: cols["세력1"] ?: 0
        val faction2ColIndex = cols["세력2"] ?: -1
        val typeColIndex = cols["관계 유형"] ?: -1
        val faction1CodeColIndex = cols["세력1코드"] ?: -1
        val faction2CodeColIndex = cols["세력2코드"] ?: -1
        val descColIndex = cols["설명"] ?: -1
        val intensityColIndex = cols["강도"] ?: -1
        val bidirectionalColIndex = cols["양방향"] ?: -1
        val orderColIndex = cols["표시순서"] ?: -1
        if (faction2ColIndex < 0 || typeColIndex < 0) return CategoryAnalysis("factionRelationships", "세력 관계", 0, 0, 0, 0, existingTotal)

        val factionIndex = FactionIndex(db.factionDao().getAllFactionsList())
        val existingByKey = existingRels.associateBy {
            FactionRelationshipMatcher.key(it.factionId1, it.factionId2, it.relationType)
        }
        val presence = factionRelationshipPresence(descColIndex, intensityColIndex, bidirectionalColIndex, orderColIndex)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val f1Name = getCellString(row, faction1ColIndex)
            if (f1Name.isBlank()) continue
            val f2Name = getCellString(row, faction2ColIndex)
            if (f2Name.isBlank()) continue
            val relType = getCellString(row, typeColIndex).trim()
            if (relType.isBlank()) continue
            inBackup++

            val f1Code = if (faction1CodeColIndex >= 0) getCellString(row, faction1CodeColIndex) else ""
            val f2Code = if (faction2CodeColIndex >= 0) getCellString(row, faction2CodeColIndex) else ""
            // 실제 임포트와 같은 해석 — 상대 세력을 힌트로 동명을 좁힌다.
            // Ambiguous는 기존 null 분기와 동일하게 처리해 계수 의미를 보존한다.
            val (pr1, pr2) = resolveFactionPair(factionIndex, f1Name, f1Code, f2Name, f2Code)
            val f1 = (pr1 as? FactionLookupResult.Found)?.faction
            val f2 = (pr2 as? FactionLookupResult.Found)?.faction
            // 세력이 아직 없으면 **신규**다 — 같은 가져오기가 세력을 먼저 만들고 이 시트를 처리한다.
            if (f1 == null || f2 == null) { newCount++; continue }

            // 매칭은 (세력1, 세력2, 유형)이되 **'동일'은 가져오기가 실제로 쓰는 값 전체**로 가른다(B-87).
            // 종전에는 unchanged 자리에 상수 0이 박혀 있어, 설명·강도·양방향·표시순서가 한 글자도
            // 다르지 않아도 매칭된 행을 전부 '변경'이라 말했다 — 아무것도 고치지 않은 파일을 그대로
            // 다시 넣어도 미리보기가 "변경 N"이라 해 왕복 멱등 확인(A7)에서 어긋남으로 읽혔다.
            val existing = FactionRelationshipMatcher.match(existingByKey, f1.id, f2.id, relType)
            if (existing == null) { newCount++; continue }
            val rowValues = factionRelationshipRowValues(
                row, descColIndex, intensityColIndex, bidirectionalColIndex, orderColIndex,
                "세력 관계 행 $i", null
            )
            if (FactionRelationshipMatcher.changes(existing, rowValues, presence)) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세력 관계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factionRelationships", "세력 관계", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzePresetTemplates(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = userPresetTemplateSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTemplates = db.userPresetTemplateDao().getAllTemplatesList()
        val existingTotal = existingTemplates.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("presetTemplates", "필드 템플릿", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("presetTemplates", "필드 템플릿", 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("presetTemplates", "필드 템플릿", 0, 0, 0, 0, existingTotal)
        val c = PresetTemplateCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        // 미리보기도 실제 임포트와 같은 매칭기를 태운다 — 이름 맵으로 세면 동명 템플릿이 접혀
        // 실제로는 N건이 갱신되는데 1건으로 보고된다(사실과 다른 미리보기).
        val matcher = PresetTemplateMatcher(
            existingTemplates.map { PresetTemplateMatcher.Candidate(it.id, it.name, it.createdAt) }
        )
        val byId = existingTemplates.associateBy { it.id }
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readPresetTemplateRow(row, c, "필드 템플릿 행 $i", result = null)
            if (r.name.isBlank()) continue
            inBackup++

            val match = matcher.claim(r.name, r.createdAt, i)
            if (match !is PresetTemplateMatcher.Match.Matched) { newCount++; continue }
            val existing = byId[match.id]
            if (existing == null) { newCount++; continue }
            val merged = mergePresetTemplate(existing, r, now)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "필드 템플릿 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("presetTemplates", "필드 템플릿", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeSearchPresets(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = searchPresetSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingPresets = db.searchPresetDao().getAllPresetsList()
        val existingTotal = existingPresets.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("searchPresets", "검색 프리셋", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("searchPresets", "검색 프리셋", 0, 0, 0, 0, existingTotal)
        // 목록 프리셋 분석과 동형 — 헤더가 어긋난 시트를 억지로 읽어 사실과 다른 미리보기를 내지 않는다
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("searchPresets", "검색 프리셋", 0, 0, 0, 0, existingTotal)
        val c = SearchPresetCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        // 가져오기가 굴리는 것과 **같은 맵**이다 — 새로 만든 행을 즉시 등재해야
        // 시트 안에 같은 이름이 두 번 나올 때 둘째 행이 첫째와 매칭된다(B-102 ⓐ).
        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readSearchPresetRow(row, c, "검색 프리셋 행 $i", filterIndex, result = null)
            if (r.name.isBlank()) continue
            inBackup++

            val existing = existingByName[r.name]
            if (existing == null) {
                newCount++
                existingByName[r.name] = SearchPreset(
                    name = r.name,
                    query = r.query ?: "",
                    filtersJson = r.filtersJson ?: "{}",
                    sortMode = r.sortMode ?: SearchPreset.SORT_RELEVANCE,
                    isDefault = r.isDefault ?: false,
                    createdAt = r.createdAt ?: now,
                    updatedAt = r.updatedAt ?: now
                )
                continue
            }
            val merged = mergeSearchPreset(existing, r, now)
            if (merged != existing) updateCount++ else unchangedCount++
            existingByName[r.name] = merged
        }
        reportProgress(onProgress, "검색 프리셋 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("searchPresets", "검색 프리셋", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeCharacterListPresets(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = characterListPresetSpec()
        val label = "목록 프리셋"
        val existingPresets = db.characterListPresetDao().getAllPresetsList()
        val existingTotal = existingPresets.size
        val sheet = workbook.getSheet(spec.sheetName)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("characterListPresets", label, 0, 0, 0, 0, existingTotal)
        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("characterListPresets", label, 0, 0, 0, 0, existingTotal)
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) return CategoryAnalysis("characterListPresets", label, 0, 0, 0, 0, existingTotal)

        val c = ListPresetCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readListPresetRow(row, c, "목록 프리셋 행 $i", filterIndex, result = null)
            if (r.name.isBlank()) continue
            inBackup++

            val existing = existingByName[r.name]
            if (existing == null) {
                newCount++
                existingByName[r.name] = CharacterListPreset(
                    name = r.name,
                    tagsJson = r.tagsJson ?: "[]",
                    fieldFiltersJson = r.fieldFiltersJson ?: "{}",
                    sortKind = r.sortKind ?: CharacterListPreset.SORT_MANUAL,
                    sortFieldKey = r.sortFieldKey,
                    sortDuelAxisCode = r.sortDuelAxisCode,
                    sortAscending = r.sortAscending ?: true,
                    bodySizePartIndex = r.bodySizePartIndex,
                    novelIdsJson = r.novelIdsJson ?: "[]",
                    isDefault = r.isDefault ?: false,
                    createdAt = r.createdAt ?: now,
                    updatedAt = r.updatedAt ?: now
                )
                continue
            }
            val merged = mergeListPreset(existing, r, now)
            if (merged != existing) updateCount++ else unchangedCount++
            existingByName[r.name] = merged
        }
        reportProgress(onProgress, "목록 프리셋 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("characterListPresets", label, inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private fun countTotalRows(workbook: Workbook): Int {
        var total = 0
        for (i in 0 until workbook.numberOfSheets) {
            val sheetName = workbook.getSheetName(i)
            if (sheetName == GUIDE_SHEET_NAME) continue
            total += maxOf(0, workbook.getSheetAt(i).lastRowNum)
        }
        return maxOf(total, 1)
    }

    private var processedRowsSoFar = 0

    private fun reportProgress(onProgress: (ImportProgress) -> Unit, phase: String, rowsInPhase: Int, totalRows: Int) {
        processedRowsSoFar += rowsInPhase
        onProgress(ImportProgress(phase, processedRowsSoFar, totalRows))
    }

    // ── Tolerant header matching (Sprint C) ──

    /**
     * 헤더 행 → {표준 헤더명 → 열 인덱스}.
     *
     * [reservedHeaders]가 주어지면 **정확 일치 열을 먼저 예약**한 뒤 나머지에만 별칭 해석을 적용한다.
     * 캐릭터 시트에서 커스텀 필드명이 고정 열(또는 그 별칭)과 겹칠 때 필드 열이 고정 슬롯을
     * 빼앗아 값이 뒤바뀌는 것을 막는다. [report]/[sheetLabel]을 주면 같은 표준명으로 접힌
     * 중복 헤더를 무음 폐기하지 않고 경고한다(변수 제어).
     */
    /**
     * 시트의 표준 헤더 목록과 대조해 **인식하지 못한 열**을 1회 보고한다.
     *
     * 표기가 조금만 달라도(별칭 미등록, 전각 괄호 등) 그 열은 조용히 무시되는데, 덮어쓰기에서는
     * "열 없음 = 비움"이라 사용자가 입력한 값이 경고 없이 사라진다. 동적 열(커스텀 필드)이 있는
     * 시트는 [dynamicColumnPrefixes]/[allowUnknownColumns]로 제외한다.
     */
    private fun reportUnknownColumns(
        headerRow: Row,
        spec: SheetSpec,
        result: ImportResult,
        sheetLabel: String? = null,
        dynamicColumnPrefixes: List<String> = emptyList()
    ) {
        val known = spec.columns.mapTo(HashSet()) { it.header }
        val unknown = mutableListOf<String>()
        for (col in 0 until headerRow.lastCellNum.toInt()) {
            val raw = getCellString(headerRow, col)
            if (raw.isBlank()) continue
            if (dynamicColumnPrefixes.any { raw.startsWith(it) }) continue
            val canonical = ExcelHeaderAliases.map[raw.normalizeHeader()] ?: raw
            if (canonical in known || raw in known) continue
            unknown.add(raw)
        }
        if (unknown.isNotEmpty()) {
            result.warnings.add(
                "시트 '${sheetLabel ?: spec.sheetName}': 열 ${unknown.joinToString(", ") { "'$it'" }}을(를) 인식하지 못해 무시했습니다 — 표준 헤더: ${spec.columns.joinToString(", ") { it.header }}"
            )
        }
    }

    private fun resolveHeaderColumns(
        headerRow: Row,
        report: ImportResult? = null,
        sheetLabel: String? = null,
        reservedHeaders: Set<String> = emptySet()
    ): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        // 셀이 하나도 없는 행의 lastCellNum은 **-1**이라 그대로 쓰면 arrayOfNulls가
        // NegativeArraySizeException으로 죽는다(손편집으로 헤더 행이 비워진 파일에서 실재).
        // [matchesSpecHeader]가 이미 같은 이유로 coerceAtLeast(0)를 달고 있다 — 같은 함정의 다른 자리다.
        val lastCol = headerRow.lastCellNum.toInt().coerceAtLeast(0)
        val raws = arrayOfNulls<String>(lastCol)
        for (col in 0 until lastCol) {
            val cell = headerRow.getCell(col) ?: continue
            // 종전 POI `cell.stringCellValue`(+ 예외 시 "")와 **같은 값**을 낸다:
            // Primitives.stringValue는 STRING과 '문자열 결과 수식'에서만 비-null이고, 숫자·불리언·
            // 오류·빈 셀에서는 null인데 — 그 셋이 정확히 POI가 예외를 던지거나 ""를 주던 경우다.
            // 헤더는 이름이므로 숫자 셀은 헤더가 아니라는 판정도 그대로 보존된다.
            val rawHeader = cell.primitives().stringValue?.trim() ?: ""
            if (rawHeader.isBlank()) continue
            raws[col] = rawHeader
        }
        // 1패스: 예약 헤더와 문자 그대로 일치하는 열 (별칭 확장 없음)
        if (reservedHeaders.isNotEmpty()) {
            for (col in 0 until lastCol) {
                val raw = raws[col] ?: continue
                if (raw in reservedHeaders && raw !in result) result[raw] = col
            }
        }
        // 2패스: 나머지 열에 별칭 해석 적용 (이미 확정된 표준명은 덮어쓰지 않음)
        for (col in 0 until lastCol) {
            val raw = raws[col] ?: continue
            val canonical = ExcelHeaderAliases.map[raw.normalizeHeader()] ?: raw
            if (canonical !in result) {
                result[canonical] = col
            } else if (result[canonical] != col && report != null) {
                val prevRaw = raws[result[canonical]!!] ?: canonical
                report.warnings.add(
                    "${sheetLabel ?: headerRow.sheet?.sheetName ?: "시트"}: 헤더 '$prevRaw'와(과) '$raw'이(가) 같은 열 '$canonical'로 해석되어 뒤쪽 열을 무시했습니다 — 커스텀 필드명이 고정 열 이름과 겹치지 않는지 확인하세요"
                )
            }
        }
        return result
    }

    /**
     * Check if header row is valid for a sheet by looking for expected first column header
     * using alias-aware matching.
     */
    private fun isValidHeader(headerRow: Row, expectedFirstHeader: String): Boolean {
        val firstCell = getCellString(headerRow, 0)
        if (firstCell.isBlank()) return false
        val normalized = firstCell.normalizeHeader()
        val canonical = ExcelHeaderAliases.map[normalized] ?: firstCell
        return canonical == expectedFirstHeader || firstCell == expectedFirstHeader
    }

    /**
     * 헤더 첫 컬럼 검증 + 실패 시 시트를 건너뛰는 이유를 오류로 보고 (무통보 스킵 방지).
     */
    private fun checkHeaderOrReport(sheet: Sheet, headerRow: Row, expectedFirstHeader: String, result: ImportResult): Boolean {
        if (isValidHeader(headerRow, expectedFirstHeader)) return true
        val actual = getCellString(headerRow, 0)
        result.errors.add(
            "시트 '${sheet.sheetName}': 첫 번째 컬럼은 '$expectedFirstHeader'이어야 합니다 (현재: '$actual') — 시트를 건너뛰었습니다. 다른 컬럼 순서는 바꿔도 되지만 첫 컬럼은 고정입니다."
        )
        return false
    }

    /** 필수 컬럼 조회. 없으면 오류를 보고하고 null 반환 — 하드코딩 위치 폴백으로 이웃 컬럼을 오독하지 않도록 한다. */
    private fun requiredCol(cols: Map<String, Int>, name: String, sheetName: String, result: ImportResult): Int? {
        val idx = cols[name]
        if (idx == null) {
            result.errors.add("시트 '$sheetName': 필수 컬럼 '$name'을(를) 찾을 수 없어 시트를 건너뛰었습니다.")
        }
        return idx
    }

    /**
     * 시트를 찾고, 못 찾으면 경고를 남기고 null 반환.
     */
    /**
     * 예약 시트 조회 — 이름을 빼앗긴 레거시 백업까지 되찾는다.
     *
     * 규칙 도입 전 내보내기는 세계관 캐릭터 시트를 먼저 만들었기 때문에, 세계관 이름이
     * '세력'·'이름 은행'·'필드 템플릿'처럼 예약명과 같으면 **진짜 예약 시트가 `이름(2)`로
     * 밀려났다**. 정확 일치만 보던 종전 구현은 그 캐릭터 시트를 잡아 헤더 오류로 건너뛰고,
     * 밀려난 진짜 데이터는 아무도 읽지 않았다(무음 유실).
     *
     * 정확 일치의 **헤더가 spec과 맞을 때만** 그것을 쓰고, 아니면 헤더가 맞는 접미사 변형을
     * 찾는다 — 이름이 아니라 헤더가 시트의 정체를 정한다.
     *
     * 접미사 후보의 판정은 **첫 열 하나로는 부족하다.** 세계관·이름 은행·세력·필드 템플릿·
     * 검색 프리셋·목록 프리셋의 첫 열은 전부 '이름'이라 캐릭터 시트와 구분되지 않는다.
     * 세력이 0건이라 '세력' 시트가 아예 없고 같은 이름의 세계관 캐릭터 시트가 '세력(2)'로
     * 밀려 있으면, 첫 열만 보는 판정은 **캐릭터 시트를 세력 시트로 넘겨준다.**
     * 그래서 spec 앞쪽 열들이 **자리까지 일치**할 것을 요구한다(내보내기가 spec 순서대로 쓰므로
     * 진짜 그 시트라면 반드시 일치한다).
     */
    private fun matchesSpecHeader(sheet: Sheet, spec: SheetSpec): Boolean {
        val header = sheet.getRow(0) ?: return false
        if (!isValidHeader(header, spec.firstColumnHeader)) return false
        val lastCol = header.lastCellNum.toInt().coerceAtLeast(0)
        val headers = (0 until lastCol).map { getCellString(header, it) }
        return headersMatchSpec(headers, spec)
    }

    /**
     * 예약 시트 해석의 **단일 판정** — 경고를 내지 않는 순수 조회.
     * 삭제 가드(`canRestore`)와 실제 조회(`findSheet`)가 같은 결과를 보게 한다.
     */
    private fun resolveSpecSheet(workbook: Workbook, spec: SheetSpec): Sheet? {
        val exact = workbook.getSheet(spec.sheetName)
        // 예약 데이터 시트는 **결코 캐릭터 시트가 아니다.** 첫 열이 '이름'인 spec 6개
        // (세계관·이름 은행·세력·필드 템플릿·검색 프리셋·목록 프리셋)는 isValidHeader만으로는
        // 같은 이름의 세계관 캐릭터 시트와 구분되지 않는다. 레거시 백업(캐릭터 시트가 평명을
        // 차지한 파일)에서 이 판정이 그 캐릭터 시트를 데이터 시트로 넘겨줬고, 같은 판정을 쓰는
        // 4-6 삭제 가드까지 통과시켜 **원본을 먼저 지우고 한 건도 복원하지 못했다.**
        if (exact != null &&
            exact.getRow(0)?.let { isValidHeader(it, spec.firstColumnHeader) } == true &&
            !looksLikeCharacterSheet(exact)
        ) {
            return exact
        }
        for (idx in 0 until workbook.numberOfSheets) {
            val name = workbook.getSheetName(idx)
            if (!isSuffixedVariantOf(name, spec.sheetName)) continue
            val candidate = workbook.getSheetAt(idx)
            if (looksLikeCharacterSheet(candidate)) continue
            if (matchesSpecHeader(candidate, spec)) return candidate
        }
        return null
    }

    /**
     * 캐릭터 시트의 지문 — 첫 열이 '이름'이고 캐릭터 전용 헤더가 하나라도 있는가.
     *
     * '이미지경로'는 세계관 시트에도 있어 제외하고, 아래 4개는 세계관/이름 은행/세력/프리셋
     * 어디에도 없는 캐릭터 전용 헤더다. 시트 이름이 겹칠 때 정체를 가르는 단일 판정이므로
     * 세계관 시트 조회와 예약 시트 조회가 **같은 함수**를 봐야 한다.
     */
    private fun looksLikeCharacterSheet(sheet: Sheet): Boolean {
        val header = sheet.getRow(0) ?: return false
        if (getCellString(header, 0) != "이름") return false
        val distinctive = CHARACTER_SHEET_FINGERPRINT
        val lastCol = header.lastCellNum.toInt()
        for (col in 1 until lastCol) {
            if (getCellString(header, col) in distinctive) return true
        }
        return false
    }

    private fun findSheet(workbook: Workbook, spec: SheetSpec, result: ImportResult): Sheet? {
        val exact = workbook.getSheet(spec.sheetName)
        val resolved = resolveSpecSheet(workbook, spec)
        if (resolved != null) {
            if (resolved.sheetName != exact?.sheetName) {
                result.warnings.add(
                    "'${spec.sheetName}' 시트가 같은 이름의 세계관에 밀려 '${resolved.sheetName}'으로 저장되어 있어 그 시트를 읽었습니다"
                )
            }
            consumedSheetNames.add(resolved.sheetName)
            return resolved
        }
        if (exact == null) {
            result.warnings.add("'${spec.sheetName}' 시트를 찾을 수 없어 해당 데이터를 건너뛰었습니다.")
        } else {
            // 이름은 맞지만 헤더가 spec과 다르다 — 헤더 경고는 호출부(checkHeaderOrReport)가 낸다.
            consumedSheetNames.add(exact.sheetName)
        }
        // 헤더가 맞지 않는 정확-일치 시트는 그대로 돌려준다 — 헤더 검증·경고는 호출부가 한다
        // (구버전 헤더·사용자 편집 파일을 관대하게 수용하던 기존 경로를 유지).
        return exact
    }

    // ── 세계관 가져오기 ──

    private suspend fun importUniverses(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = universeSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val c = UniverseCols(cols, spec.firstColumnHeader)
        val nowMillis = System.currentTimeMillis()

        // Build code index for duplicate detection within file
        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33) — F1-A(열 없음 = 기존 유지)도 그 안에 있다.
                val r = readUniverseRow(row, c, "세계관 행 $i", nowMillis, result)
                val name = r.name
                if (name.isBlank()) continue

                val descriptionFromExcel: String? = r.description
                val code = r.code
                val imagePathsFromExcel: String? = r.imagePaths
                val imageCharCode = r.imageCharCode
                val imageNovelCode = r.imageNovelCode

                // Duplicate code detection within file (last-write-wins)
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("세계관: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Code-first matching (Sprint A strict rule)
                val existing: Universe?
                val matchedByName: Boolean
                if (code.isNotBlank()) {
                    val byCode = db.universeDao().getUniverseByCode(code)
                    if (byCode != null) {
                        existing = byCode
                        matchedByName = false
                    } else {
                        // F1-C: 코드가 있으나 DB에 없음 → 조용히 신규 생성하지 않고 이름 폴백 + 경고
                        val byName = db.universeDao().getUniverseByName(name)
                        if (byName != null) {
                            existing = byName
                            matchedByName = true
                            result.nameBasedMappings++
                            // 세계관 이름 열이 없는 참조(프리셋 필드 필터의 universeCode)가 이 결정을 따라가게 한다
                            universeCodeAliases.note(code, byName.code)
                            result.warnings.add("세계관 행 $i: 코드 '$code'를 찾지 못해 이름 '$name'으로 매칭함 — 의도한 새 세계관이면 코드를 비우세요")
                        } else {
                            existing = null
                            matchedByName = false
                            warnCreatedNewByCode("universes", "세계관 행 $i: 코드 '$code'가 기존 세계관에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                        }
                    }
                } else {
                    existing = db.universeDao().getUniverseByName(name)
                    matchedByName = existing != null
                    if (matchedByName) {
                        result.nameBasedMappings++
                        result.warnings.add("세계관 행 $i: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("세계관 행 $i: 행 $prevRow 과 같은 항목('$name')을 다시 덮어씀 — 별개의 세계관으로 넣으려면 '코드' 칸을 비우고 이름을 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    // F1-A: 이미지 열이 있으나 비어 기존 이미지를 지우는 경우 요약 고지
                    if (imagePathsFromExcel != null && (imagePathsFromExcel == "[]" || imagePathsFromExcel.isBlank()) &&
                        existing.imagePaths.isNotBlank() && existing.imagePaths != "[]") {
                        result.clearedFields++
                    }
                    // 설명 빈칸으로 기존 값이 지워지는 경우도 요약 집계(변수 제어)
                    if (descriptionFromExcel == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    // 이미지 참조는 여기서 null로 두고 Phase 2가 코드로 되붙인다 — 그 캐릭터·작품이
                    // 아직 없을 수 있기 때문이다. 미리보기는 되붙은 뒤의 값을 넣어 같은 함수를 부른다.
                    val mergedUniverse = mergeUniverse(existing, r, imageCharacterId = null, imageNovelId = null)
                    db.universeDao().update(mergedUniverse)
                    if (imageCharCode != null) deferredUniverseImageCharCodes[existing.id] = imageCharCode
                    if (imageNovelCode != null) deferredUniverseImageNovelCodes[existing.id] = imageNovelCode
                    if (mergedUniverse != existing) result.updatedUniverses++ else result.unchangedRows++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newId = db.universeDao().insert(Universe(
                        name = name, description = r.description ?: "", code = newCode,
                        displayOrder = r.displayOrder ?: i.toLong(), borderColor = r.borderColor ?: "", borderWidthDp = r.borderWidthDp ?: 1.5f,
                        imagePaths = r.imagePaths ?: "[]", imageMode = r.imageMode ?: "none",
                        customRelationshipTypes = r.customRelationshipTypes ?: "",
                        customRelationshipColors = r.customRelationshipColors ?: "",
                        imageCharacterId = null, // deferred
                        imageNovelId = null,     // deferred
                        createdAt = r.createdAt ?: nowMillis
                    ))
                    if (imageCharCode != null) deferredUniverseImageCharCodes[newId] = imageCharCode
                    if (imageNovelCode != null) deferredUniverseImageNovelCodes[newId] = imageNovelCode
                    entitySeen[newId] = i
                    result.newUniverses++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세계관 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "세계관", sheet.lastRowNum, totalRows)
    }

    // ── 작품 가져오기 ──

    private suspend fun importNovels(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = novelSpec(emptyList())
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        // 작품 커스텀 필드는 동적 열이므로 미인식 대상에서 제외한다(해석 실패는 아래에서 따로 고지)
        reportUnknownColumns(headerRow, spec, result, dynamicColumnPrefixes = listOf(EntityFieldHeaders.PREFIX))
        val cols = resolveHeaderColumns(headerRow)
        val nc = NovelCols(cols, spec.firstColumnHeader)
        val nowMillis = System.currentTimeMillis()

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        // 작품 커스텀 필드 컬럼 (확-3) — 연표 시트와 **같은 규칙**(EntityFieldHeaders)의 역함수다.
        // 정확 일치를 최우선으로 두는 이유는 이름이 괄호로 끝나는 필드를 세계관 한정으로
        // 오인하면 열 전체가 버려지기 때문이다.
        val allNovelFields = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_NOVEL)
        val universeNamesById = db.universeDao().getAllUniversesList().associate { it.id to it.name }
        // 한정자(세계관명) → id. 열 하나가 어느 구역의 필드를 가리키는지 되짚는 데 쓴다(B-65).
        val universeIdsByName = universeNamesById.entries.associate { (id, name) -> name to id }
        val knownUniverseNames = universeNamesById.values.toHashSet()
        val knownNovelFieldNames = allNovelFields.mapTo(HashSet()) { it.name }
        val expectedNovelHeaders = EntityFieldHeaders.expectedHeaders(allNovelFields, universeNamesById)
        val novelFieldColumns = mutableListOf<EventFieldColumn>()
        val novelFieldHeadersSeen = mutableSetOf<String>()
        // 정의 없는 "필드:" 열의 값 유실 고지용 — (헤더명) 단위로 1회만 경고
        val droppedNovelFieldHeaders = mutableSetOf<String>()
        for (ci in 0 until headerRow.lastCellNum) {
            val header = getCellString(headerRow, ci)
            if (!header.startsWith(EntityFieldHeaders.PREFIX)) continue
            if (!novelFieldHeadersSeen.add(header)) {
                result.warnings.add("작품: 필드 열 '$header'이(가) 중복되어 뒤쪽 열을 무시했습니다 — 필드명이 겹치지 않는지 확인하세요")
                continue
            }
            val exact = expectedNovelHeaders[header]
            if (exact != null) {
                novelFieldColumns.add(
                    EventFieldColumn(ci, header, exact.name, exact.universeId?.let { universeNamesById[it] }, exact)
                )
                continue
            }
            val parsed = EntityFieldHeaders
                .parseFallback(header, knownNovelFieldNames, knownUniverseNames) ?: continue
            novelFieldColumns.add(EventFieldColumn(ci, header, parsed.fieldName, parsed.universeName, null))
        }

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33) — F1-A(열 없음 = 기존 유지)도 그 안에 있다.
                val r = readNovelRow(row, nc, "작품 행 $i", nowMillis, result)
                val title = r.title
                if (title.isBlank()) continue

                val descriptionFromExcel: String? = r.description
                val universeName = r.universeName
                val code = r.code
                val universeCode = r.universeCode
                val novelImagePathsFromExcel: String? = r.imagePaths
                val novelImageCharCode = r.imageCharCode

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("작품: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Resolve universe: code-first, then name.
                // 괄호 필수 — 괄호 없이 쓰면 엘비스가 else 가지(null)에만 붙어 코드 미해석 시 이름 폴백이 죽는다.
                val universeColumnPresent = r.universeColumnPresent
                val universeRefProvided = r.universeRefProvided
                val universeId = (if (universeCode.isNotBlank()) db.universeDao().getUniverseByCode(universeCode)?.id else null)
                    ?: (if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null)
                if (universeRefProvided && universeId == null) {
                    result.warnings.add("작품 행 $i: 세계관 '${universeName.ifBlank { universeCode }}'을(를) 찾을 수 없음 — 기존 작품은 소속 유지, 새 작품은 세계관 미지정으로 생성")
                }

                // Code-first matching (Sprint A) + F1-C: 미지 코드 → 자연키 폴백 + 경고
                val existing: Novel?
                if (code.isNotBlank()) {
                    val byCode = novelByCode(code)
                    if (byCode != null) {
                        existing = byCode
                    } else {
                        val byTitle = if (universeId != null) {
                            db.novelDao().getNovelByTitleAndUniverse(title, universeId)
                        } else {
                            db.novelDao().getNovelByTitleNoUniverse(title)
                        }
                        if (byTitle != null) {
                            existing = byTitle
                            result.nameBasedMappings++
                            result.warnings.add("작품 행 $i: 코드 '$code'를 찾지 못해 제목 '$title'으로 매칭함 — 의도한 새 작품이면 코드를 비우세요")
                        } else {
                            existing = null
                            warnCreatedNewByCode("novels", "작품 행 $i: 코드 '$code'가 기존 작품에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                        }
                    }
                } else {
                    // No code => fallback to title+universe
                    existing = if (universeId != null) {
                        db.novelDao().getNovelByTitleAndUniverse(title, universeId)
                    } else {
                        db.novelDao().getNovelByTitleNoUniverse(title)
                    }
                    if (existing != null) {
                        result.nameBasedMappings++
                        result.warnings.add("작품 행 $i: 이름 기반 매칭 ('$title') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    // 이 행이 확정하는 소속 — 아래 update와 필드값 적용이 **같은 값**을 봐야 한다.
                    // 두 곳에 따로 쓰면 한쪽만 고쳐질 때 방금 옮긴 작품에 옛 세계관 필드가 붙는다.
                    val effectiveUniverseId = effectiveNovelUniverseId(existing, r, universeId)
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("작품 행 $i: 행 $prevRow 과 같은 항목('$title')을 다시 덮어씀 — 별개의 작품으로 넣으려면 '코드' 칸을 비우고 제목을 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    // F1-A: 이미지 열이 있으나 비어 기존 이미지를 지우는 경우 요약 고지
                    if (novelImagePathsFromExcel != null && (novelImagePathsFromExcel == "[]" || novelImagePathsFromExcel.isBlank()) &&
                        existing.imagePaths.isNotBlank() && existing.imagePaths != "[]") {
                        result.clearedFields++
                    }
                    if (descriptionFromExcel == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    // 이미지 캐릭터는 여기서 null로 두고 Phase 2가 코드로 되붙인다.
                    val mergedNovel = mergeNovel(existing, r, effectiveUniverseId, imageCharacterId = null)
                    db.novelDao().update(mergedNovel)
                    // 코드가 바뀌었을 수 있다 — 옛 코드를 끊어야 뒤 행·뒤 시트가 SQL과 같은 답을 본다(B-210).
                    rememberNovel(mergedNovel)
                    if (novelImageCharCode != null) deferredNovelImageCharCodes[existing.id] = novelImageCharCode
                    if (mergedNovel != existing) result.updatedNovels++ else result.unchangedRows++
                    // 소속이 이 행에서 바뀌었으면 **새 소속**의 필드가 적용 대상이다(위 val과 같은 값).
                    applyNovelFieldColumns(
                        row, existing.id, effectiveUniverseId,
                        novelFieldColumns, allNovelFields, universeIdsByName,
                        droppedNovelFieldHeaders, result
                    )
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newNovel = Novel(
                        title = title, description = r.description ?: "", universeId = universeId,
                        code = newCode, displayOrder = r.displayOrder ?: i.toLong(),
                        borderColor = r.borderColor ?: "", borderWidthDp = r.borderWidthDp ?: 1.5f,
                        inheritUniverseBorder = r.inheritUniverseBorder, isPinned = r.isPinned,
                        imagePaths = r.imagePaths ?: "[]", imageMode = r.imageMode ?: "none",
                        imageCharacterId = null, // deferred
                        standardYear = r.standardYear,
                        createdAt = r.createdAt ?: nowMillis
                    )
                    val newId = db.novelDao().insert(newNovel)
                    // 방금 만든 작품을 곧바로 읽히게 한다 — 캐릭터 시트가 이 코드로 소속을 찾는다.
                    rememberNovel(newNovel.copy(id = newId))
                    if (novelImageCharCode != null) deferredNovelImageCharCodes[newId] = novelImageCharCode
                    entitySeen[newId] = i
                    result.newNovels++
                    applyNovelFieldColumns(
                        row, newId, universeId,
                        novelFieldColumns, allNovelFields, universeIdsByName,
                        droppedNovelFieldHeaders, result
                    )
                    // 세계관 미해석 경고는 위(해석 지점)에서 신규/기존 공통으로 1회 보고한다
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("작품 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "작품", sheet.lastRowNum, totalRows)
    }

    /**
     * 작품 시트의 커스텀 필드 열을 그 작품에 반영한다 (확-3).
     *
     * 연표 시트의 사건 필드와 **같은 규약**이다:
     * - 해석에 성공한 열만 교체 대상이다 — 시트에 없던 필드의 기존 값은 살아남는다(F1-A 열 단위).
     * - 열이 있고 셀이 빈칸이면 교체 대상에 들어가므로 **비움 의도는 존중**된다.
     * - 구역 해석은 [EntityFieldColumnResolver]가 단일 소스다 — **열 이름이 맞으면 세계관이
     *   달라도 받는다**(B-65 · 사용자 확정 15번 ㄱ1). 종전에는 이 자리와 사건 쪽이 같은 `when`을
     *   각자 들고 있었고 둘 다 *"이 행의 세계관 필드"*만 받아, 소유자가 세계관을 옮겨 생긴 값이
     *   왕복에서 사라졌다.
     * - **무소속 작품도 건너뛰지 않는다.** 종전에는 `novelUniverseId == null`이면 열을 통째로
     *   무시해, 전역 구역 필드에 담긴 값이 내보내기에는 나가고 되돌아오지는 못했다.
     * - 계산 필드는 저장하지 않는다(R-16) — 수식으로 산출되는 파생값이다.
     */
    private suspend fun applyNovelFieldColumns(
        row: Row,
        novelId: Long,
        novelUniverseId: Long?,
        columns: List<EventFieldColumn>,
        allNovelFields: List<FieldDefinition>,
        universeIdsByName: Map<String, Long>,
        droppedHeaders: MutableSet<String>,
        result: ImportResult
    ) {
        if (columns.isEmpty()) return
        val newValues = mutableListOf<com.novelcharacter.app.data.model.NovelFieldValue>()
        val resolvedFieldIds = mutableListOf<Long>()
        for (col in columns) {
            val ci = col.colIndex
            val fieldDef = EntityFieldColumnResolver.resolve(
                col.resolved, col.fieldName, col.universeName, novelUniverseId,
                allNovelFields, universeIdsByName
            )
            if (fieldDef == null) {
                if (getCellString(row, ci).isNotBlank() && droppedHeaders.add(col.header)) {
                    // 고칠 길이 두 가지라 문구를 가른다 — 정의가 아예 없는 것과, 이름은 있는데
                    // 어느 구역인지 못 정하는 것은 사용자가 할 일이 다르다(개발 의도 2번의 교정 경로).
                    result.warnings.add(
                        if (allNovelFields.any { it.name == col.fieldName }) {
                            "작품 시트의 필드 열 '${col.header}'이(가) 어느 세계관의 '${col.fieldName}' 필드인지 확정할 수 없어 값이 반영되지 않았습니다 — 열 머리를 '필드:${col.fieldName}(세계관명)' 꼴로 적어 주세요"
                        } else {
                            "작품 시트의 필드 열 '${col.header}'에 해당하는 작품 필드 정의를 찾을 수 없어 값이 반영되지 않았습니다 — '필드 정의' 시트(대상=작품)를 함께 가져오세요"
                        }
                    )
                }
                continue
            }
            if (fieldDef.fieldType == FieldType.CALCULATED) {
                if (getCellString(row, ci).isNotBlank() && droppedHeaders.add(col.header)) {
                    result.warnings.add(
                        "작품 시트의 '${col.header}' 열은 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨)"
                    )
                }
                continue
            }
            resolvedFieldIds.add(fieldDef.id)
            // 이 (작품, 필드)는 작품 시트가 권위 — '작품 필드값' 시트의 같은 항목은 무시된다
            importedNovelFieldPairs.add(novelId to fieldDef.id)
            val cellValue = getCellString(row, ci)
            if (cellValue.isNotBlank()) {
                newValues.add(
                    com.novelcharacter.app.data.model.NovelFieldValue(
                        novelId = novelId, fieldDefinitionId = fieldDef.id, value = cellValue
                    )
                )
            }
        }
        if (resolvedFieldIds.isNotEmpty() || newValues.isNotEmpty()) {
            db.novelFieldValueDao().replaceForFields(novelId, resolvedFieldIds, newValues)
        }
    }

    // ── 필드 정의 가져오기 ──

    // ── 전역 기본 필드 템플릿 가져오기 (B-119 — 필드 정의 직전) ──

    /**
     * 미리보기 — **가져오기와 같은 판정을 쓴다**(R-33).
     *
     * 정체 해석(코드 우선 → (대상, 필드키))과 '바뀌는가'의 판정이 [importDefaultFieldTemplates]와
     * 어긋나면 *"바뀐다고 말해 놓고 안 바꾸거나, 조용히 바꾸는"* 상태가 된다.
     */
    private suspend fun analyzeDefaultFieldTemplates(
        workbook: Workbook,
        onProgress: (ImportProgress) -> Unit,
        totalRows: Int
    ): CategoryAnalysis {
        val spec = defaultFieldSpec()
        val label = "기본 필드"
        val dao = db.defaultFieldTemplateDao()
        val existingTotal = dao.getAllList().size
        val sheet = resolveSpecSheet(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) {
            return CategoryAnalysis("defaultFields", label, 0, 0, 0, 0, existingTotal)
        }
        val headerRow = sheet.getRow(0)
            ?: return CategoryAnalysis("defaultFields", label, 0, 0, 0, 0, existingTotal)

        val c = DefaultFieldCols(resolveHeaderColumns(headerRow))

        var total = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skipped = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            // 읽기·해석·판정 셋 다 가져오기와 **같은 함수**다 (R-33).
            val r = readDefaultFieldRow(row, c, "기본 필드 행 $i", result = null)
            if (r.key.isBlank() && r.name.isBlank()) continue
            total++
            if (r.key.isBlank() || r.name.isBlank()) { skipped++; continue }
            val existing = resolveDefaultFieldTemplate(r)
            if (existing == null) newCount++
            else if (mergeDefaultFieldTemplate(existing, r) != existing) updateCount++
            else unchangedCount++
        }
        reportProgress(onProgress, "기본 필드 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("defaultFields", label, total, newCount, updateCount, unchangedCount, existingTotal, skipped)
    }

    /**
     * '기본 필드' 시트 → [DefaultFieldTemplate].
     *
     * **필드 정의보다 먼저 돈다** — '필드 정의' 시트의 `기본필드코드`가 여기서 만든 템플릿을
     * 찾아야 한다(설계 1-5). 뒤에 두면 신규 기기 복원(빈 DB)에서 같은 파일 안에 템플릿이
     * 실려 있는데도 연결이 전부 강등된다 — 등급 체계가 필드 정의보다 앞인 것과 같은 근거다.
     *
     * **심지 않는다.** 같은 파일의 '필드 정의' 시트가 심긴 필드를 이미 담고 있으므로 여기서
     * 심으면 그 삽입과 부딪친다. 템플릿만 있고 심긴 필드가 없는 파일(손으로 만든 것)은 기본
     * 필드 관리 화면의 **'다시 심기'**가 따라잡는다.
     *
     * B-119 이전 백업에는 이 시트가 없는 것이 정상이라 경고하지 않는다 — '등급 체계' 시트와
     * 같은 관례다.
     */
    private suspend fun importDefaultFieldTemplates(
        workbook: Workbook,
        result: ImportResult,
        onProgress: (ImportProgress) -> Unit,
        totalRows: Int
    ) {
        val spec = defaultFieldSpec()
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return
        reportUnknownColumns(headerRow, spec, result)

        val c = DefaultFieldCols(resolveHeaderColumns(headerRow, result, spec.sheetName))
        val dao = db.defaultFieldTemplateDao()
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val r = readDefaultFieldRow(row, c, "기본 필드 행 $i", result)
            // 둘 다 비면 빈 행이라 조용히 넘긴다. 하나만 비면 사람이 적다 만 것이라 말한다
            // ('등급 체계' 시트와 같은 관례 — 무음 폐기와 구별한다).
            if (r.key.isBlank() && r.name.isBlank()) continue
            if (r.key.isBlank() || r.name.isBlank()) {
                result.skippedRows++
                result.errors.add("기본 필드 행 $i: 필드키·필드명은 필수입니다")
                continue
            }
            val existing = resolveDefaultFieldTemplate(r)

            if (existing != null) {
                val merged = mergeDefaultFieldTemplate(existing, r)
                if (merged != existing) {
                    // 자리를 옮기는 편집이 남의 자리와 부딪치면 되돌린다 — 유니크 (대상, 필드키).
                    val clash = dao.getBySlot(merged.entityType, merged.key)
                        ?.takeIf { it.id != existing.id }
                    if (clash != null) {
                        result.warnings.add(
                            "기본 필드 행 $i: '${merged.key}'(${merged.entityType}) 자리를 이미 다른 " +
                                "기본 필드가 쓰고 있어 키·대상은 바꾸지 않았습니다"
                        )
                        val kept = merged.copy(key = existing.key, entityType = existing.entityType)
                        if (kept != existing) { dao.update(kept); result.updatedDefaultFields++ }
                        else result.unchangedRows++
                    } else {
                        dao.update(merged)
                        if (merged != existing) result.updatedDefaultFields++ else result.unchangedRows++
                    }
                }
            } else {
                // 코드가 이미 남의 템플릿과 겹치면 재발급한다(전역 유니크 — 정체를 빼앗지 않는다).
                val wanted = r.code.takeIf { it.isNotBlank() }
                val safeCode = wanted?.takeIf { dao.getByCode(it) == null }
                    ?: com.novelcharacter.app.data.model.generateEntityCode().also {
                        if (wanted != null) result.newCodesGenerated++
                    }
                // 새 행에서는 **null이 곧 기본값**이다 — 지킬 기존 값이 없다(R-36 후반부).
                dao.insert(
                    DefaultFieldTemplate(
                        key = r.key,
                        name = r.name,
                        type = r.type ?: FieldType.TEXT.name,
                        config = resolveDefaultFieldConfig(r, existing = null),
                        groupName = r.groupName ?: "기본 정보",
                        displayOrder = r.displayOrder ?: ((dao.getMaxOrder(r.entityType) ?: -1) + 1),
                        isRequired = r.isRequired ?: false,
                        entityType = r.entityType,
                        code = safeCode
                    )
                )
                result.newDefaultFields++
            }
        }
        reportProgress(onProgress, "기본 필드", sheet.lastRowNum, totalRows)
    }

    private class DefaultFieldCols(cols: Map<String, Int>) {
        val key = cols["필드키"] ?: 0
        val name = cols["필드명"] ?: 1
        // 위치 폴백 금지 — 선택 열은 없으면 -1이고, 그때는 기존 값을 지킨다(R-36).
        val type = cols["타입"] ?: -1
        val config = cols["설정(JSON)"] ?: -1
        val group = cols["그룹"] ?: -1
        val order = cols["순서"] ?: -1
        val required = cols["필수여부"] ?: -1
        val aiSuggest = cols[FieldConfigColumns.COLUMN_AI_SUGGEST] ?: -1
        val description = cols[FieldConfigColumns.COLUMN_DESCRIPTION] ?: -1
        val entityType = cols["대상"] ?: -1
        val code = cols["코드"] ?: -1
    }

    /**
     * '기본 필드' 시트 한 행. **null은 *"이 파일은 그것을 말하지 않는다"*** 이다(R-36) —
     * [mergeDefaultFieldTemplate]이 그때 기존 값을 지킨다.
     */
    private data class DefaultFieldRowValues(
        val key: String,
        val name: String,
        val type: String?,
        val sheetConfig: String?,
        val groupName: String?,
        val displayOrder: Int?,
        val isRequired: Boolean?,
        val entityType: String,
        val code: String,
        val aiColumnPresent: Boolean,
        val aiCellText: String,
        val descriptionColumnPresent: Boolean,
        val descriptionCellText: String
    )

    private fun readDefaultFieldRow(
        row: Row,
        c: DefaultFieldCols,
        ctx: String,
        result: ImportResult?
    ): DefaultFieldRowValues = DefaultFieldRowValues(
        key = getCellString(row, c.key),
        name = getCellString(row, c.name),
        type = if (c.type >= 0) getCellString(row, c.type).takeIf { it.isNotBlank() } else null,
        // 열이 없으면 null — 빈 칸("{}", 설정을 비워라)과 구별한다(R-36).
        // 형제 시트가 이미 쓰는 형태다([PresetTemplateRowValues.fieldsJson]).
        sheetConfig = if (c.config >= 0) getCellString(row, c.config).ifBlank { "{}" } else null,
        groupName = if (c.group >= 0) getCellString(row, c.group).ifBlank { "기본 정보" } else null,
        displayOrder = if (c.order >= 0) {
            getCellString(row, c.order).takeIf { it.isNotBlank() }?.let { parseNumber(it)?.toInt() }
        } else null,
        isRequired = sheetBooleanOrKeep(c.required >= 0, getCellString(row, c.required)),
        entityType = FieldValueSheetMapper.entityTypeOf(
            if (c.entityType >= 0) getCellString(row, c.entityType) else null
        ),
        code = if (c.code >= 0) getCellCode(row, c.code, ctx, result) else "",
        aiColumnPresent = c.aiSuggest >= 0,
        aiCellText = getCellString(row, c.aiSuggest),
        descriptionColumnPresent = c.description >= 0,
        descriptionCellText = getCellString(row, c.description)
    )

    /** 정체는 **코드 우선, 없으면 (대상, 필드키)** — 다른 시트와 같은 규약이다(R-1). */
    private suspend fun resolveDefaultFieldTemplate(r: DefaultFieldRowValues): DefaultFieldTemplate? {
        val dao = db.defaultFieldTemplateDao()
        return r.code.takeIf { it.isNotBlank() }?.let { dao.getByCode(it) }
            ?: dao.getBySlot(r.entityType, r.key)
    }

    /**
     * 이 행이 만들 `config`.
     *
     * AI추천·필드설명의 3분기 병합은 '필드 정의' 시트와 **같은 함수**를 쓰고
     * ([FieldConfigColumns.merge]), 그 위에 **세계관 한정 참조를 걷어낸다** — 템플릿은 전역이라
     * 그런 참조를 가질 수 없는데(설계 1-2) 손으로 고친 파일이 실어 올 수 있고, 그대로 심으면
     * 모든 세계관에 유령 참조가 하나씩 생긴다.
     *
     * **`설정(JSON)` 열이 없는 파일에서 베이스가 기존 config인 것도 그 함수가 든다**(B-142) —
     * 여기서 `?: "{}"`로 받으면 열 없는 파일을 들이는 것만으로 `options`·`formula`·체형 설정·
     * 물질화된 등급표가 사라지고, 이어 '전파'가 그 빈 설정을 모든 세계관으로 퍼뜨린다.
     */
    private fun resolveDefaultFieldConfig(
        r: DefaultFieldRowValues,
        existing: DefaultFieldTemplate?
    ): String = com.novelcharacter.app.data.model.FieldConfigTransfer.demoteAcrossUniverse(
        FieldConfigColumns.merge(
            sheetConfig = r.sheetConfig,
            aiColumnPresent = r.aiColumnPresent,
            aiCellText = r.aiCellText,
            descriptionColumnPresent = r.descriptionColumnPresent,
            descriptionCellText = r.descriptionCellText,
            existingConfig = existing?.config
        )
    )

    /**
     * 기존 템플릿에 이 행을 얹은 결과 — **미리보기와 가져오기가 부르는 같은 함수**다(R-33).
     *
     * 미리보기가 손으로 짠 비교를 쓰면 *"바뀐다고 말해 놓고 안 바꾸거나, 조용히 바꾸는"* 상태가
     * 된다. `merged != existing` 하나가 두 자리의 판정을 겸한다.
     */
    private fun mergeDefaultFieldTemplate(
        existing: DefaultFieldTemplate,
        r: DefaultFieldRowValues
    ): DefaultFieldTemplate = existing.copy(
        key = r.key,
        name = r.name,
        type = r.type ?: existing.type,
        config = resolveDefaultFieldConfig(r, existing),
        groupName = r.groupName ?: existing.groupName,
        displayOrder = r.displayOrder ?: existing.displayOrder,
        isRequired = r.isRequired ?: existing.isRequired,
        entityType = r.entityType
    )

    // ── 등급 체계 가져오기 (U-1 — 필드 정의 직전: '등급체계' 열이 여기서 만든 체계를 찾는다) ──

    private suspend fun analyzeGradeSystems(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = gradeSystemSpec()
        val label = "등급 체계"
        val existingTotal = db.gradeSystemDao().getAllList().size
        val sheet = workbook.getSheet(spec.sheetName)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("gradeSystems", label, 0, 0, 0, 0, existingTotal)
        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("gradeSystems", label, 0, 0, 0, 0, existingTotal)

        var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        val groups = collectGradeSystemRows(sheet, headerRow, result = null)
        for (group in groups) {
            // 유효한 등급 행이 하나도 없으면 가져오기가 무리를 통째로 거부한다 — '신규'가 아니다(B-102 ⓑ).
            if (com.novelcharacter.app.data.model.GradeSystemRef.gradesFromJson(group.gradesJson()).isEmpty()) {
                skippedCount++
                continue
            }
            val existing = resolveGradeSystem(group)
            if (existing == null) { newCount++; continue }
            // 적용도 가져오기와 **같은 함수**다(규약 R-33) — 이름 충돌 판정까지 같이 본다.
            val rename = group.name != existing.name &&
                db.gradeSystemDao().getByUniverseAndName(group.universeId, group.name) == null
            val merged = mergeGradeSystem(existing, group.name, group.gradesJson(), rename)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "등급 체계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("gradeSystems", label, groups.size, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    /** 시트 한 무리 = 체계 하나. 행은 (라벨, 수치 원문)으로 모으고 검증은 GradeTable이 한다. */
    private class GradeSystemGroup(
        val universeId: Long,
        val universeName: String,
        val name: String,
        val code: String,
        val firstRowNum: Int
    ) {
        val rows = mutableListOf<Pair<String, String>>()
        var built: com.novelcharacter.app.util.GradeTable.Outcome? = null
        fun gradesJson(): String {
            val outcome = built ?: com.novelcharacter.app.util.GradeTable.build(rows).also { built = it }
            return com.novelcharacter.app.data.model.GradeSystemRef.gradesToJson(outcome.grades)
        }
    }

    /**
     * 시트를 (세계관, 코드|체계명) 무리로 접는다. 세계관을 못 찾는 행은 건너뛰고 [result]에
     * 보고한다(분석 경로는 result가 null이라 개수만 영향을 받는다 — 가져오기와 같은 판정).
     */
    private suspend fun collectGradeSystemRows(
        sheet: Sheet,
        headerRow: Row,
        result: ImportResult?
    ): List<GradeSystemGroup> {
        val cols = resolveHeaderColumns(headerRow)
        val universeColIndex = cols["세계관"] ?: 0
        val nameColIndex = cols["체계명"] ?: 1
        val labelColIndex = cols["등급"] ?: 2
        val valueColIndex = cols["기본숫자"] ?: 3
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        val codeColIndex = cols["코드"] ?: -1

        val groups = LinkedHashMap<String, GradeSystemGroup>()
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val universeName = getCellString(row, universeColIndex)
            val systemName = getCellString(row, nameColIndex)
            if (universeName.isBlank() && systemName.isBlank()) continue
            if (universeName.isBlank() || systemName.isBlank()) {
                result?.let {
                    it.skippedRows++
                    it.errors.add("등급 체계 행 $i: 세계관·체계명은 필수입니다")
                }
                continue
            }
            val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
            val universe = (if (universeCode.isNotBlank()) db.universeDao().getUniverseByCode(universeCode) else null)
                ?: db.universeDao().getUniverseByName(universeName)
            if (universe == null) {
                result?.let {
                    it.skippedRows++
                    it.errors.add("등급 체계 행 $i: 세계관 '${universeName}'을(를) 찾을 수 없음")
                }
                continue
            }
            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            // 무리 키는 코드 우선, 없으면 (세계관, 체계명) — 다른 시트의 매칭 규약과 같다.
            val key = if (code.isNotBlank()) "code:$code" else "name:${universe.id}:$systemName"
            val group = groups.getOrPut(key) {
                GradeSystemGroup(universe.id, universe.name, systemName, code, i)
            }
            group.rows.add(getCellString(row, labelColIndex) to getCellString(row, valueColIndex))
        }
        return groups.values.toList()
    }

    /** 무리 → 기존 체계. 코드 우선(같은 세계관일 때만), 다음 (세계관, 이름). */
    private suspend fun resolveGradeSystem(group: GradeSystemGroup): com.novelcharacter.app.data.model.GradeSystem? {
        if (group.code.isNotBlank()) {
            val byCode = db.gradeSystemDao().getByCode(group.code)
            // 코드가 다른 세계관의 체계를 가리키면 무시하고 이름 매칭으로 넘어간다 — 체계의
            // 세계관 소속은 조용히 옮기지 않는다(참조가 세계관 안에서만 성립하기 때문).
            if (byCode != null && byCode.universeId == group.universeId) return byCode
        }
        return db.gradeSystemDao().getByUniverseAndName(group.universeId, group.name)
    }

    private suspend fun importGradeSystems(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = gradeSystemSpec()
        // U-1 이전 백업에는 이 시트가 없는 것이 정상이라 경고하지 않는다 — '캐릭터 필드값'
        // 시트와 같은 관례(없으면 기존 체계 유지). 접미사 변형까지 같은 해석으로 되찾는다.
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return
        reportUnknownColumns(headerRow, spec, result)

        val repository = com.novelcharacter.app.data.repository.GradeSystemRepository(db)
        for (group in collectGradeSystemRows(sheet, headerRow, result)) {
            // 행 검증은 필드의 등급 표와 같은 규칙(GradeTable) — 유효 행만 반영하고, 문제는
            // 행 단위로 보고한다(무리 전체 거부는 관대 수용이 아니다).
            val outcome = com.novelcharacter.app.util.GradeTable.build(group.rows)
            for (problem in outcome.problems) {
                result.warnings.add(
                    "등급 체계 '${group.name}' (행 ${group.firstRowNum}부터): " + gradeProblemText(problem)
                )
            }
            if (outcome.grades.isEmpty()) {
                result.skippedRows++
                result.errors.add("등급 체계 '${group.name}': 유효한 등급 행이 없어 건너뜀")
                continue
            }
            val gradesJson = com.novelcharacter.app.data.model.GradeSystemRef.gradesToJson(outcome.grades)
            val existing = resolveGradeSystem(group)
            if (existing != null) {
                // 이름 변경이 같은 세계관의 다른 체계와 충돌하면 이름은 유지한다(유니크).
                val rename = group.name != existing.name &&
                    db.gradeSystemDao().getByUniverseAndName(group.universeId, group.name) == null
                if (!rename && group.name != existing.name) {
                    result.warnings.add(
                        "등급 체계 '${existing.name}': 같은 세계관에 '${group.name}'이(가) 이미 있어 이름을 바꾸지 않았습니다"
                    )
                }
                // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                val saved = mergeGradeSystem(existing, group.name, gradesJson, rename)
                if (saved != existing) {
                    // 참조 필드 전파까지 한 몸으로 — 엑셀 경로는 라벨 개명을 추적할 수 없으므로
                    // (행의 정체가 라벨 그 자체다) 라벨 변경은 삭제+추가로 다룬다.
                    repository.saveSystem(saved)
                    result.updatedGradeSystems++
                } else result.unchangedRows++  // 변경 없음은 '갱신'이 아니라 '동일'로 센다 (B-111)
                matchedGradeSystemIds.add(existing.id)
            } else {
                // 코드가 다른 세계관의 체계와 겹치면 재발급한다(전역 유니크 — 소속을 옮기지 않는다).
                val wantedCode = group.code.takeIf { it.isNotBlank() }
                val safeCode = wantedCode?.takeIf { db.gradeSystemDao().getByCode(it) == null }
                    ?: com.novelcharacter.app.data.model.generateEntityCode().also {
                        if (wantedCode != null) result.newCodesGenerated++
                    }
                val newId = db.gradeSystemDao().insert(
                    com.novelcharacter.app.data.model.GradeSystem(
                        universeId = group.universeId,
                        name = group.name,
                        gradesJson = gradesJson,
                        displayOrder = matchedGradeSystemIds.size,
                        code = safeCode
                    )
                )
                matchedGradeSystemIds.add(newId)
                result.newGradeSystems++
            }
        }
        reportProgress(onProgress, "등급 체계", sheet.lastRowNum, totalRows)
    }

    /** GradeTable의 타입 문제 → 가져오기 결과 문구 (다이얼로그 문구와 별개 — 결과 창은 서술형이다). */
    private fun gradeProblemText(problem: com.novelcharacter.app.util.GradeTable.Problem): String = when (problem) {
        is com.novelcharacter.app.util.GradeTable.Problem.BlankLabel ->
            "수치 '${problem.valueText}'에 등급 이름이 없어 그 행을 건너뛰었습니다"
        is com.novelcharacter.app.util.GradeTable.Problem.BadNumber ->
            "등급 '${problem.label}'의 수치 '${problem.valueText}'을(를) 숫자로 해석할 수 없어 건너뛰었습니다"
        is com.novelcharacter.app.util.GradeTable.Problem.DuplicateLabel ->
            "등급 '${problem.label}'이(가) 중복되어 뒤의 행을 건너뛰었습니다"
        is com.novelcharacter.app.util.GradeTable.Problem.SignPrefixedLabel ->
            "등급 이름 '${problem.label}'은(는) -나 +로 시작할 수 없어 건너뛰었습니다"
        com.novelcharacter.app.util.GradeTable.Problem.Empty ->
            "유효한 등급 행이 없습니다"
    }

    /**
     * OVERWRITE 전략에서 백업에 없는 등급 체계만 삭제한다 — [pruneUnmatchedFieldDefinitions]와
     * 같은 보호를 쓴다(시트 부재·전건 실패 시 삭제하지 않음). 삭제되는 체계를 참조하던 필드는
     * 독자 표로 내려앉는다(실효 표가 남아 필드값·통계는 그대로다).
     */
    private suspend fun pruneUnmatchedGradeSystems(workbook: Workbook, result: ImportResult) {
        val existing = db.gradeSystemDao().getAllList()
        if (existing.isEmpty()) return
        val spec = gradeSystemSpec()
        val header = workbook.getSheet(spec.sheetName)?.getRow(0)
        if (header == null || !isValidHeader(header, spec.firstColumnHeader)) {
            result.warnings.add("백업에 '${spec.sheetName}' 시트가 없어 기존 등급 체계를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)")
            return
        }
        if (matchedGradeSystemIds.isEmpty()) {
            result.warnings.add("덮어쓰기: '${spec.sheetName}' 시트에서 처리된 등급 체계가 하나도 없어 기존 체계를 삭제하지 않았습니다 — 위의 행 오류를 먼저 확인하세요")
            return
        }
        val repository = com.novelcharacter.app.data.repository.GradeSystemRepository(db)
        val stale = existing.filter { it.id !in matchedGradeSystemIds }
        var demoted = 0
        for (system in stale) {
            for (field in repository.referencingFields(system)) {
                db.fieldDefinitionDao().update(
                    field.copy(config = com.novelcharacter.app.data.model.GradeSystemRef.demote(field.config))
                )
                demoted++
            }
            db.gradeSystemDao().delete(system)
            result.deletedGradeSystems++
        }
        if (stale.isNotEmpty()) {
            val names = stale.take(5).joinToString(", ") { it.name }
            val more = if (stale.size > 5) " 외 ${stale.size - 5}개" else ""
            val demotedNote = if (demoted > 0) " — 참조하던 필드 ${demoted}개는 독자 등급 표로 전환했습니다(표 내용은 그대로)" else ""
            result.warnings.add("덮어쓰기: 백업에 없는 등급 체계 ${stale.size}개($names$more)를 삭제했습니다$demotedNote")
        }
    }

    private suspend fun importFieldDefinitions(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = fieldDefinitionSpec(emptyList())
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val fdc = FieldDefCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)

        val entitySeen = mutableMapOf<Long, Int>()
        // 파싱이 읽지 않는 `설정(JSON)` 키 — 행마다 고지하면 수백 줄이 되므로 모아서 한 줄로
        // 낸다(P-5 · B-95). 재는 대상은 **파일이 말한 config**이지 병합 결과가 아니다 —
        // 열이 없는 파일은 기존 DB config가 베이스가 되는데(B-142), 그것까지 세면
        // 이번 파일과 무관한 옛 흔적을 이 파일 탓으로 고지하게 된다.
        val unusedConfigKeys = linkedMapOf<String, Int>()
        var unusedConfigFields = 0
        // 이번 파일이 넣거나 고친 필드 — 구역(세계관 · 대상)별로 모은다. 수식 검증은
        // **행을 다 읽은 뒤** 이것을 가지고 돈다(B-54): 행마다 검사하면 뒷 행에 정의된
        // 필드를 참조하는 수식이 전부 거짓 경고를 받는다. 시트의 행 차례는 사용자가 정하는
        // 것이지 의존 순서가 아니다.
        val touchedFormulaScopes = linkedMapOf<Pair<Long?, String>, MutableSet<String>>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readFieldDefRow(row, fdc, "필드 행 $i", result)
                val universeName = r.universeName
                // 세계관·세계관코드가 **둘 다 빈 행은 전역 구역**(무소속 — B-119 확장)이다.
                // 내보내기가 전역 필드를 그렇게 싣는다. 종전에는 이 행을 조용히 건너뛰었는데,
                // 그 시절 내보내기는 빈 세계관 행을 만들지 않았으므로 옛 파일의 뜻이 바뀌는
                // 것은 아니다(손으로 만든 빈 행이 있었다면 이제 전역 필드로 들어온다 —
                // 버려지던 것이 실리는 방향이라 유실이 아니다).
                val globalScope = FieldScopeCell.isGlobal(universeName, r.universeCode)
                if (universeName.isBlank() && !globalScope) continue

                val universe = if (globalScope) null else {
                    val found = (if (r.universeCode.isNotBlank()) {
                        db.universeDao().getUniverseByCode(r.universeCode)
                    } else null)
                        ?: db.universeDao().getUniverseByName(universeName)
                    if (found == null) {
                        result.skippedRows++
                        result.errors.add("필드 정의 행 $i: 세계관 '${universeName}'을(를) 찾을 수 없음")
                        continue
                    }
                    found
                }

                val key = r.key
                if (key.isBlank()) continue

                val name = r.name
                val type = r.type
                // Validate field type against known types
                if (type.isBlank()) {
                    result.skippedRows++
                    result.errors.add("필드 정의 행 $i: 필드 타입이 비어 있음 (허용: ${FieldType.entries.joinToString { it.name }})")
                    continue
                }
                if (FieldType.fromName(type) == null) {
                    result.skippedRows++
                    result.errors.add("필드 정의 행 $i: 알 수 없는 필드 타입 '$type' (허용: ${FieldType.entries.joinToString { it.name }})")
                    continue
                }
                // F4: 설정(JSON)이 손상(절단·구문 오류)됐으면 조용히 넘기지 않고 경고 (필드 동작 무력화 방지)
                // 열이 없으면(null) 잴 것이 없다 — 그 파일은 설정을 말하지 않았다(B-162).
                if (r.config != null && r.config != "{}" && !isValidJson(r.config)) {
                    result.warnings.add("필드 정의 행 $i: 필드 '$name'의 설정(JSON)이 올바른 형식이 아닙니다(절단·오타 가능) — 그대로 저장되나 해당 기능이 동작하지 않을 수 있습니다")
                }
                // 없앤 설정이 담긴 옛 파일을 조용히 삼키지 않는다(P-5 확정 — 한 줄 고지).
                if (r.config != null) {
                    val unused = BodyAnalysisConfig.unusedKeysIn(r.config)
                    if (unused.isNotEmpty()) {
                        unusedConfigFields++
                        for (k in unused) unusedConfigKeys[k] = (unusedConfigKeys[k] ?: 0) + 1
                    }
                }
                val groupName = r.groupName
                val displayOrder: Int? = r.displayOrder
                val isRequired = r.isRequired
                val entityType = r.entityType

                val existing = if (universe != null) {
                    db.fieldDefinitionDao().getFieldByKey(universe.id, key, entityType)
                } else {
                    db.fieldDefinitionDao().getGlobalFieldByKey(key, entityType)
                }
                // 이 행이 건드리는 자리를 적어 둔다 — 실제로 저장됐는지는 아래에서 갈리지만,
                // 타입이 바뀌거나 병합 결과가 그대로여도 **이 파일이 말한 필드**인 것은 같다.
                touchedFormulaScopes
                    .getOrPut(universe?.id to entityType) { linkedSetOf() }
                    .add(key)
                val mergedConfig = resolveFieldDefConfig(universe?.id, i, r, existing, result)
                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("필드 정의 행 $i: 행 $prevRow 과 같은 항목('$name')을 다시 덮어씀 — 별개의 필드로 넣으려면 '필드키'를 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    if (existing.type != type && type.isNotBlank()) {
                        result.warnings.add("필드 정의 행 $i: 필드 '$name'의 타입이 '${existing.type}'에서 '$type'(으)로 변경됨 — 기존 값 호환성을 확인하세요")
                    }
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedFieldDef = mergeFieldDefinition(existing, r, mergedConfig)
                    db.fieldDefinitionDao().update(mergedFieldDef)
                    matchedFieldDefinitionIds.add(existing.id)
                    if (mergedFieldDef != existing) result.updatedFields++ else result.unchangedRows++
                } else {
                    val newId = db.fieldDefinitionDao().insert(FieldDefinition(
                        universeId = universe?.id, key = key, name = name, type = type,
                        config = mergedConfig, groupName = groupName, displayOrder = displayOrder ?: i,
                        isRequired = isRequired ?: false, entityType = entityType
                    ))
                    entitySeen[newId] = i
                    matchedFieldDefinitionIds.add(newId)
                    result.newFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 정의 행 $i: ${e.message}")
            }
        }
        if (unusedConfigKeys.isNotEmpty()) {
            result.warnings.add(
                "필드 정의: 더 이상 쓰지 않는 설정 ${unusedConfigKeys.size}개를 건너뛰었습니다" +
                    "(${unusedConfigKeys.keys.joinToString(", ")}) — 필드 ${unusedConfigFields}개에 있었습니다. " +
                    "나머지 설정은 그대로 들어왔고, 해당 필드를 앱에서 한 번 저장하면 이 설정이 사라집니다"
            )
        }
        // 수식 검증은 **전량 적재 뒤**에 돈다 (B-54).
        warnImportedFormulaProblems(touchedFormulaScopes, result)
        reportProgress(onProgress, "필드 정의", sheet.lastRowNum, totalRows)
    }

    /**
     * **엑셀로 들어온 수식의 문제를 결과에 싣는다** (B-54).
     *
     * 종전에는 `FormulaValidator`가 **필드 편집 창에서만** 돌아, 필드 정의 시트로 들어온
     * 수식(자기참조·미지 함수·미인식 잔여)이 아무 고지 없이 저장됐다. 화면에서 `오류`로
     * 보이는 것은 **평가가 NaN을 낼 때뿐**이고, 흔한 실수 여럿은 NaN조차 내지 않는다 —
     * 알아보지 못한 글자가 조용히 버려져 남은 조각만으로 **그럴듯한 수**가 나온다.
     *
     * **거부가 아니라 고지다** — 값은 이미 저장됐고 여기서 되돌리지 않는다(왕복 무결성상
     * 들어온 값은 들어와야 한다). 4장 B-54 행이 그렇게 못박아 두었다.
     *
     * 판정은 [ImportedFormulaAudit]가 하고 여기서는 문구만 입힌다 — 아는 키의 범위와
     * *이번 파일이 건드린 것*의 경계가 그 계층에서 갈리고, 그래야 순수 JVM 시험이 닿는다.
     *
     * **구역의 필드 목록을 DB에서 다시 읽는 것**은 이 시점이 적재 뒤라 그 목록이 곧
     * *최종 상태*이기 때문이다. 편집 창이 아는 키를 세는 범위(같은 세계관·같은 대상)와
     * 같은 자리를 읽어 **두 경로가 같은 답을 내게** 한다.
     */
    private suspend fun warnImportedFormulaProblems(
        touchedScopes: Map<Pair<Long?, String>, Set<String>>,
        result: ImportResult
    ) {
        for ((scope, keys) in touchedScopes) {
            val (universeId, entityType) = scope
            val scopeFields = if (universeId == null) {
                db.fieldDefinitionDao().getGlobalFieldsList(entityType)
            } else {
                db.fieldDefinitionDao().getFieldsByUniverseList(universeId, entityType)
            }
            for (finding in ImportedFormulaAudit.audit(scopeFields, keys)) {
                result.warnings.add(
                    "필드 정의: 계산 필드 '${finding.name}'의 수식을 확인하세요 — " +
                        finding.problems.joinToString("; ") { formulaProblemText(it) } +
                        ". 값은 그대로 저장됐습니다"
                )
            }
        }
    }

    /**
     * 수식 문제 한 줄 — **필드 편집 창의 리소스 문구와 일부러 나눠 둔다.**
     *
     * 그쪽은 *"그대로 저장하시겠습니까?"*를 묻는 대화라 고치는 법까지 적고(쓸 수 있는 함수
     * 목록 등), 이쪽은 **여러 필드가 한 목록에 쌓이는 보고**라 한 줄이 짧아야 한다.
     * 같은 사실을 두 자리에서 말하는 것이 아니라 **부르는 자리가 아예 다르다** — 이 서비스는
     * `appContext`가 nullable이라 리소스를 쓸 수도 없다(이 파일의 다른 고지가 전부 그렇다).
     */
    private fun formulaProblemText(problem: FormulaValidator.Problem): String = when (problem) {
        is FormulaValidator.Problem.UnbalancedParen -> "괄호 짝이 맞지 않습니다"
        is FormulaValidator.Problem.SelfReference -> "자기 자신('${problem.key}')을 참조합니다"
        is FormulaValidator.Problem.CircularReference ->
            "수식이 서로 돌아옵니다(${problem.path.joinToString(" → ")})"
        is FormulaValidator.Problem.UnknownKeys ->
            "이 세계관에 없는 필드 키 ${problem.keys.joinToString(", ")} (그 자리는 0으로 계산됩니다)"
        is FormulaValidator.Problem.PaddedKeys ->
            "필드 키 앞뒤의 공백까지 이름으로 읽습니다: ${problem.keys.joinToString(", ")}"
        is FormulaValidator.Problem.UnknownFunctions ->
            "없는 함수 ${problem.names.joinToString(", ")} (이름은 버려지고 괄호 안 값만 남습니다)"
        is FormulaValidator.Problem.MalformedCalls ->
            "함수 표기가 어긋나 계산에서 빠집니다: ${problem.names.joinToString(", ")}"
        is FormulaValidator.Problem.UnrecognizedText ->
            "계산에서 빠지는 부분이 있습니다: ${problem.fragments.joinToString(", ")}"
    }

    /**
     * '필드 정의' 시트의 등급 체계 참조 병합 (U-1) — [FieldConfigColumns.merge]와 같은 3분기 문법.
     *
     * ① 참조 열 쌍('등급체계'/'등급체계코드')의 유무·해제·조회 의도는 [refColumnIntent]가
     *    정한다 — 관계 시트의 '세력'/'세력코드'와 같은 규약이다. **이름 칸을 비우면 코드 셀이
     *    남아 있어도 독자 표다**(코드 열은 회색 잔재라, 읽으면 사용자가 이름을 지워도 해제되지
     *    않는다). 조회는 코드 우선 → 이름 폴백이고, 해석 실패는 독자 표 강등 + 고지다.
     * ② 열이 없고 JSON에 참조 키가 있으면 그 참조를 존중한다(손편집·구형식 파일).
     * ③ 열도 키도 없으면 기존 DB의 참조를 다시 얹는다 — 이 분기를 빠뜨리면 두 열을 지운
     *    파일을 들일 때 참조가 무통보로 풀린다.
     *
     * 참조가 해석되면 실효 표를 다시 물질화한다([GradeSystemRef.mergeResolved]) — 파일의
     * `grades`가 사용자가 보고 고친 값이므로 체계 기본과 다른 라벨은 재정의로 남고,
     * 체계에 없는 라벨은 빠지되 반드시 고지한다(조용한 좁힘 금지).
     */
    private suspend fun mergeGradeSystemColumn(
        universeId: Long?,
        rowIndex: Int,
        fieldName: String,
        fieldType: String,
        config: String,
        nameColumnPresent: Boolean,
        codeColumnPresent: Boolean,
        cellName: String,
        cellCode: String,
        existingConfig: String?,
        result: ImportResult?
    ): String {
        // 들어온 글자를 타입으로 좁혀 견준다 (B-55) — 모르는 글자는 등급이 아니므로 그대로 나간다.
        if (FieldType.fromName(fieldType) != FieldType.GRADE) return config
        val ref = com.novelcharacter.app.data.model.GradeSystemRef

        suspend fun resolve(code: String?, name: String?): com.novelcharacter.app.data.model.GradeSystem? {
            // 전역 구역(universeId null — B-119 확장)은 체계를 참조할 수 없다(설계 1-2:
            // 등급 체계는 세계관 단위다). 해석 실패로 두면 아래 강등+고지 경로를 그대로 탄다.
            if (universeId == null) return null
            if (!code.isNullOrBlank()) {
                db.gradeSystemDao().getByCode(code)
                    ?.takeIf { it.universeId == universeId }
                    ?.let { return it }
            }
            if (!name.isNullOrBlank()) {
                db.gradeSystemDao().getByUniverseAndName(universeId, name)?.let { return it }
            }
            return null
        }

        fun applyResolved(system: com.novelcharacter.app.data.model.GradeSystem): String {
            val merge = ref.mergeResolved(config, system.code, system.gradesJson)
            if (merge.droppedLabels.isNotEmpty()) {
                result?.warnings?.add(
                    "필드 정의 행 $rowIndex: 필드 '$fieldName'의 등급 ${merge.droppedLabels.joinToString(", ")}은(는) " +
                        "체계 '${system.name}'에 없어 빠졌습니다 — 체계에 등급을 추가하거나 '등급체계' 칸을 비워 독자 표로 두세요"
                )
            }
            return merge.config
        }

        fun demoteWithNotice(pointer: String): String {
            result?.warnings?.add(
                "필드 정의 행 $rowIndex: 필드 '$fieldName'이(가) 가리키는 등급 체계 '$pointer'을(를) 찾을 수 없어 " +
                    "독자 등급 표로 들였습니다 (표 내용은 그대로입니다)"
            )
            return ref.demote(config)
        }

        val jsonCode = ref.codeFromConfig(config)
        return when (refColumnIntent(nameColumnPresent, codeColumnPresent, cellName, cellCode)) {
            RefIntent.CLEAR -> ref.demote(config)
            RefIntent.LOOKUP ->
                resolve(cellCode, cellName)?.let { applyResolved(it) }
                    ?: demoteWithNotice(cellName.ifBlank { cellCode })
            RefIntent.KEEP -> when {
                jsonCode != null ->
                    resolve(jsonCode, null)?.let { applyResolved(it) } ?: demoteWithNotice(jsonCode)
                else -> {
                    val existingCode = existingConfig?.let { ref.codeFromConfig(it) } ?: return config
                    resolve(existingCode, null)?.let { applyResolved(it) }
                        ?: try {
                            // 참조가 이미 끊겨 있던 기존 상태 그대로 — 가져오기가 상태를 조용히 바꾸지 않는다.
                            org.json.JSONObject(config).put(ref.CONFIG_KEY, existingCode).toString()
                        } catch (_: Exception) {
                            config
                        }
                }
            }
        }
    }

    /**
     * OVERWRITE 전략에서 백업에 없는 필드 정의만 삭제한다.
     *
     * 사전 deleteAll은 캐릭터·사건 필드값과 값 라이브러리를 FK CASCADE로 전멸시키고,
     * 정의 재삽입 시 id가 재발급되어 어떤 재가져오기로도 복구되지 않는다. 매칭 후 잔여분만
     * 정리하면 매칭된 정의의 종속 데이터가 보존되면서 덮어쓰기 의미(백업에 없는 정의 제거)는 유지된다.
     * 백업에 '필드 정의' 시트 자체가 없으면 아무것도 삭제하지 않는다(이미지 태그와 동일 보호).
     */
    private suspend fun pruneUnmatchedFieldDefinitions(workbook: Workbook, result: ImportResult) {
        val spec = fieldDefinitionSpec(emptyList())
        val header = workbook.getSheet(spec.sheetName)?.getRow(0)
        if (header == null || !isValidHeader(header, spec.firstColumnHeader)) {
            result.warnings.add("백업에 '${spec.sheetName}' 시트가 없어 기존 필드 정의를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)")
            return
        }
        // 시트는 있는데 한 건도 매칭되지 않았다면 정상적인 "전부 삭제"가 아니라 행 단위 실패다
        // (세계관 미해석·타입 오류 등). 이때 정리를 강행하면 행 오류가 전 필드값 소멸로 증폭된다.
        if (matchedFieldDefinitionIds.isEmpty()) {
            result.warnings.add("덮어쓰기: '${spec.sheetName}' 시트에서 처리된 필드 정의가 하나도 없어 기존 필드 정의를 삭제하지 않았습니다 — 위의 행 오류를 먼저 확인하세요")
            return
        }
        // 구역(세계관/전역)마다 매칭 근거를 따로 본다 — 판정의 단일 소스는 순수 로직이다.
        // '백업에 없다'와 '백업이 그 구역을 말하지 않았다'를 가르지 않으면, 시트가 다루지도
        // 않은 구역의 정의가 값과 함께 CASCADE로 사라진다(B-130).
        val outcome = FieldDefinitionPrune.plan(
            db.fieldDefinitionDao().getAllFieldsAllTypes(), matchedFieldDefinitionIds
        )
        for (field in outcome.stale) db.fieldDefinitionDao().delete(field)
        if (outcome.stale.isNotEmpty()) {
            val names = outcome.stale.take(5).joinToString(", ") { it.name }
            val more = if (outcome.stale.size > 5) " 외 ${outcome.stale.size - 5}개" else ""
            result.warnings.add("덮어쓰기: 백업에 없는 필드 정의 ${outcome.stale.size}개($names$more)를 관련 필드값과 함께 삭제했습니다 — 의도한 것이 아니면 삭제 전 백업으로 되돌리세요")
        }
        // 남긴 것도 반드시 알린다 — 조용히 남기면 사용자는 덮어쓰기가 끝난 줄 알고,
        // 그 구역만 옛 정의가 살아 있는 상태를 일일이 확인해야만 알게 된다(원칙 04).
        if (outcome.protectedFields.isNotEmpty()) {
            val universeNames = db.universeDao().getAllUniversesList().associate { it.id to it.name }
            val scopeLabels = outcome.protectedScopes.joinToString(", ") { scopeId ->
                if (scopeId == null) FieldScopeCell.GLOBAL_LABEL else universeNames[scopeId] ?: "세계관 #$scopeId"
            }
            result.warnings.add(
                "덮어쓰기: '${spec.sheetName}' 시트가 다루지 않은 구역($scopeLabels)의 필드 정의 " +
                "${outcome.protectedFields.size}개는 삭제하지 않고 유지했습니다 — 그 구역의 행이 " +
                "백업에 하나도 없어 '지워라'인지 '말한 바 없음'인지 가릴 수 없습니다. " +
                "정말 지우려면 지금 데이터를 한 번 내보낸 뒤 그 파일에서 해당 행을 지우고 다시 가져오세요"
            )
        }
    }

    // ── 필드 데이터 라이브러리 가져오기 (필드 정의 직후 — 필드가 먼저 존재해야 함) ──

    private suspend fun importFieldValueLibrary(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = fieldValueLibrarySpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val universeCol = cols["세계관"] ?: 0
        val keyCol = cols["필드키"] ?: 1
        val entityCol = cols["대상"] ?: -1
        val valueCol = cols["값"] ?: 4
        val labelCol = cols["표시라벨"] ?: -1
        val aliasCol = cols["별칭(콤마구분)"] ?: cols["별칭"] ?: -1
        val categoryCol = cols["카테고리"] ?: -1
        val descCol = cols["설명"] ?: -1
        val hiddenCol = cols["숨김"] ?: -1
        val codeCol = cols["코드"] ?: -1
        val sourceCol = cols["출처"] ?: -1

        // 배치 로드: 행마다 쿼리하지 않는다 (검토 A7 — 대용량 파일 성능)
        val universesByName = db.universeDao().getAllUniversesList().associateBy { it.name }
        val entriesByField = HashMap<Long, MutableList<com.novelcharacter.app.data.model.FieldValueEntry>>()
        // 코드 유일성 확인도 **같은 목록**에서 답한다 (B-210) — 종전에는 신규 엔트리마다
        // `getByCode`를 한 번 더 쳤는데, 그 행들은 방금 전부 읽어 손에 들고 있었다.
        // 빈 DB로 복원하면 그 조회 수가 곧 엔트리 수다(목표 규모 14,460).
        val entryCodes = ImportLookupIndex<String, com.novelcharacter.app.data.model.FieldValueEntry>(
            idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
        )
        for (e in db.fieldValueEntryDao().getAllList().sortedBy { it.id }) {
            entriesByField.getOrPut(e.fieldDefinitionId) { mutableListOf() }.add(e)
            entryCodes.put(e)
        }
        // null 키 = 전역 구역(무소속) — '필드 정의' 시트와 같은 어휘다(B-119 확장).
        val fieldCache = HashMap<Triple<Long?, String, String>, FieldDefinition?>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val universeName = getCellString(row, universeCol)
                val fieldKey = getCellString(row, keyCol)
                val value = getCellString(row, valueCol)
                if (universeName.isBlank() && fieldKey.isBlank() && value.isBlank()) continue

                // 세계관 칸이 빈 행 = 전역 구역(무소속 — B-119 확장). **내보내기가 이미 그렇게
                // 싣는다**(전역 필드의 값은 `universeMap[null]`이 없어 빈 칸으로 나간다) —
                // 이 갈래가 없던 동안 그 행들은 전부 skippedRows로 버려져, 덮어쓰기로 지워진
                // 전역 값을 **파일로 되돌릴 길이 없었다**(B-130).
                val globalScope = FieldScopeCell.isGlobal(universeName)
                val universe = if (globalScope) null else universesByName[universeName]
                if (universe == null && !globalScope) {
                    result.skippedRows++
                    result.warnings.add("필드 데이터 행 $i: 세계관 '$universeName'을(를) 찾을 수 없음")
                    continue
                }
                val imported = FieldValueSheetMapper.ImportedRow(
                    universeName = universeName,
                    fieldKey = fieldKey,
                    entityLabel = if (entityCol >= 0) getCellString(row, entityCol) else null,
                    value = value,
                    displayLabel = if (labelCol >= 0) getCellString(row, labelCol) else null,
                    aliasesCsv = if (aliasCol >= 0) getCellString(row, aliasCol) else null,
                    category = if (categoryCol >= 0) getCellString(row, categoryCol) else null,
                    description = if (descCol >= 0) getCellString(row, descCol) else null,
                    hiddenFlag = if (hiddenCol >= 0) getCellString(row, hiddenCol) else null,
                    code = if (codeCol >= 0) getCellString(row, codeCol) else null,
                    sourceFlag = if (sourceCol >= 0) getCellString(row, sourceCol) else null
                )
                val fd = fieldCache.getOrPut(Triple(universe?.id, fieldKey, imported.entityType)) {
                    if (universe == null) {
                        db.fieldDefinitionDao().getGlobalFieldByKey(fieldKey, imported.entityType)
                    } else {
                        db.fieldDefinitionDao().getFieldByKey(universe.id, fieldKey, imported.entityType)
                    }
                }
                if (fd == null) {
                    result.skippedRows++
                    val where = if (globalScope) FieldScopeCell.GLOBAL_LABEL else "세계관 '$universeName'"
                    result.warnings.add("필드 데이터 행 $i: 필드 키 '$fieldKey'(${imported.entityLabel ?: "캐릭터"})을(를) ${where}에서 찾을 수 없음")
                    continue
                }

                val sourceCell = FieldValueSheetMapper.parseSourceCell(imported.sourceFlag)

                val siblings = entriesByField.getOrPut(fd.id) { mutableListOf() }
                // 매칭(코드 우선 → 같은 필드의 같은 값)은 복원 미리보기 분석과 **같은 함수**를 쓴다 —
                // 규칙을 두 곳이 각자 들면 갈린다(B-87. 세력 소속이 그렇게 갈렸던 것이 1-ac다)
                val existing = FieldValueSheetMapper.match(siblings, imported)

                // 출처 오타는 조용히 수용하지도, 기존 값을 파괴하지도 않는다 — 고지 + 교정 경로 안내.
                // 실제 저장될 값이 갱신/신규에 따라 다르므로 매칭 이후에 판정한다(사실과 다른 경고 금지).
                if (sourceCell is FieldValueSheetMapper.SourceCell.Unknown) {
                    val outcome = if (existing != null) "기존 출처(${existing.source})를 유지했습니다"
                    else "새 값이라 참고할 기존 출처가 없어 ${com.novelcharacter.app.data.model.FieldValueEntry.SOURCE_IMPORT}로 등록했습니다"
                    result.warnings.add(
                        "필드 데이터 행 $i: 출처 '${sourceCell.raw}'을(를) 인식할 수 없어 $outcome — " +
                        "AUTO·MANUAL·IMPORT·AI 중 하나로 고쳐 다시 가져오면 반영됩니다"
                    )
                }

                // 병합(이름 변경 별칭 보존 · 값 충돌 판정 · 충돌 별칭 제외)도 분석과 같은 함수다.
                // 여기서는 그 결과를 고지로 옮기기만 한다 — 무엇이 일어났는가는 mergeRow가 안다.
                val outcome = FieldValueSheetMapper.mergeRow(existing, fd.id, imported, siblings)
                val merged = outcome.entry
                if (merged == null) {
                    result.skippedRows++
                    result.warnings.add("필드 데이터 행 $i: 값이 비어 있어 건너뜀")
                    continue
                }
                var candidate = merged
                // 시트에서 값 이름이 바뀐 경우(코드 매칭): 인앱 이름변경과 달리 데이터 전파가 없으므로
                // 구 값을 별칭으로 보존해 재수확 시 중복 엔트리로 갈라지지 않게 한다 + 고지
                if (outcome.renamedFrom != null) {
                    result.warnings.add("필드 데이터 행 $i: 값 '${outcome.renamedFrom}' → '${candidate.value}' 이름 변경 감지 — 구 값을 별칭으로 보존 (캐릭터 값 일괄 변경은 인앱 라이브러리의 이름 변경 사용)")
                }
                // 값 이름이 다른 엔트리와 충돌(코드 매칭으로 이름이 바뀐 경우) — 거부 대신 그 행만 스킵
                if (outcome.valueTaken) {
                    result.skippedRows++
                    result.warnings.add("필드 데이터 행 $i: 값 '${candidate.value}'이(가) 이미 존재해 건너뜀")
                    continue
                }
                // 별칭 충돌은 해당 별칭만 제외 + 경고 (관대 수용)
                if (outcome.droppedAliases.isNotEmpty()) {
                    result.warnings.add("필드 데이터 행 $i: 별칭 ${outcome.droppedAliases.joinToString(", ")}이(가) 다른 값과 충돌해 제외됨")
                }

                if (existing != null) {
                    db.fieldValueEntryDao().update(candidate)
                    siblings.removeAll { it.id == candidate.id }
                    siblings.add(candidate)
                    // 코드가 바뀌었을 수 있다(파일의 코드를 받는 갈래) — 색인이 옛 코드를
                    // 계속 가리키면 그 코드를 든 다음 행이 **남의 것을 자기 것으로 본다**.
                    entryCodes.put(candidate)
                    if (candidate != existing) result.updatedFieldValueEntries++ else result.unchangedRows++
                } else {
                    // 코드 전역 유니크: 다른 필드의 엔트리가 이미 소유한 코드면 재발급 (관대 수용).
                    // **같은 파일 안에서 방금 만든 엔트리도 상대다** — 그래서 색인은 아래에서 함께 갱신한다.
                    val codeOwner = entryCodes.first(candidate.code)
                    if (codeOwner != null) {
                        candidate = candidate.copy(code = generateEntityCode())
                        result.newCodesGenerated++
                    }
                    val newId = db.fieldValueEntryDao().insert(candidate)
                    val inserted = candidate.copy(id = newId)
                    siblings.add(inserted)
                    entryCodes.put(inserted)
                    result.newFieldValueEntries++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 데이터 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "필드 데이터", sheet.lastRowNum, totalRows)
    }

    // ── 세계관별 캐릭터 시트 가져오기 ──

    private suspend fun importCharacterSheets(workbook: Workbook, result: ImportResult, resolvedConflicts: Map<String, CharacterConflict>, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val universes = db.universeDao().getAllUniversesList()
        val reservedNames = RESERVED_SHEET_NAMES

        if (universes.any { sanitizeSheetNameBase(it.name) == UNCLASSIFIED_SHEET_NAME }) {
            unclassifiedNameCollidesWithUniverse = true
        }
        for (universe in universes) {
            val sheet = findSheetForUniverse(workbook, universe.name, reservedNames) ?: continue
            val headerRow = sheet.getRow(0) ?: continue
            if (!checkHeaderOrReport(sheet, headerRow, "이름", result)) continue

            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            // U-3: 겹치는 세계관이 **접미사 시트**를 잡았으면 신규 형식이 확정된 배치다
            // (내보내기는 예약명을 미분류 시트에 주고 세계관 시트를 '(2)'로 민다).
            // 판정은 findSheetForUniverse가 이미 끝냈고, 여기서는 그 결과를 읽어 문구만 가른다.
            if (sanitizeSheetNameBase(universe.name) == UNCLASSIFIED_SHEET_NAME &&
                isSuffixedVariantOf(sheet.sheetName, UNCLASSIFIED_SHEET_NAME)
            ) {
                unclassifiedUniverseTookSuffixedSheet = true
            }
            // 헤더 검증을 통과해 실제로 처리한 세계관만 삭제 범위에 넣는다 (시트 없는 세계관은 건드리지 않음)
            importedCharacterSheetUniverseIds.add(universe.id)
            // 같은 시트를 미분류 경로가 또 돌지 않게 소비 목록에 넣는다 (상호배제)
            consumedCharacterSheetNames.add(sheet.sheetName)
            importCharacterRows(sheet, headerRow, universe, fields, result, resolvedConflicts, universe.name, onProgress, totalRows)
        }
    }

    // ── 미분류 캐릭터 가져오기 ──

    private suspend fun importUnclassifiedCharacters(workbook: Workbook, result: ImportResult, resolvedConflicts: Map<String, CharacterConflict>, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val sheet = findUnclassifiedSheet(workbook, consumedCharacterSheetNames) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, "이름", result)) return
        consumedCharacterSheetNames.add(sheet.sheetName)
        // 세계관 이름이 '미분류 캐릭터'인 백업은 두 캐릭터 시트가 같은 이름을 다투는데, 둘 다
        // 캐릭터 시트라 헤더로 구분할 수 없다. 신규 형식(예약명은 미분류 시트가 갖는다)으로
        // 해석하되, 구버전 백업이면 반대일 수 있으므로 **모호함을 선언한다**(규약 4-3:
        // 좁혀지지 않으면 조용히 고르지 않는다). 사용자는 엑셀에서 시트 이름을 바꿔 교정할 수 있다.
        //
        // U-3: 다만 **신규 형식이 확정된 배치**(겹치는 세계관이 '(2)' 접미사 시트를 헤더 확인까지
        // 거쳐 잡았고, 미분류가 예약명 시트를 그대로 받은 경우)에서는 배정이 모호하지 않다.
        // 그런데도 "두 시트의 이름을 서로 바꾸라"고 권하면 **권유대로 했을 때 오히려 틀어진다** —
        // 맞게 배정된 것을 사용자가 손으로 뒤집게 만드는 거짓 교정 권유다. 사실 고지로 낮춘다.
        if (unclassifiedNameCollidesWithUniverse) {
            val newFormatConfirmed = unclassifiedUniverseTookSuffixedSheet &&
                sheet.sheetName == UNCLASSIFIED_SHEET_NAME
            result.warnings.add(
                if (newFormatConfirmed) {
                    "'$UNCLASSIFIED_SHEET_NAME'이라는 이름의 세계관이 있어 시트 이름이 겹칩니다 — " +
                    "'${sheet.sheetName}' 시트를 미분류 캐릭터로, 접미사가 붙은 시트를 그 세계관으로 읽었습니다(내보내기 규칙대로입니다)"
                } else {
                    "'$UNCLASSIFIED_SHEET_NAME'이라는 이름의 세계관이 있어 시트 두 개가 같은 이름을 다툽니다 — " +
                    "'${sheet.sheetName}' 시트를 미분류 캐릭터로 읽었습니다. 구버전 백업이라 반대로 저장돼 있었다면 " +
                    "엑셀에서 두 시트의 이름을 서로 바꾼 뒤 다시 가져오세요"
                }
            )
        }

        unclassifiedSheetImported = true
        // 전역 구역의 필드를 넘긴다 (B-149) — 내보내기가 이 시트에 그 열을 싣기 때문이다.
        // **넘기지 않으면 값이 사라진다:** 내보내기가 전역 필드를 `coveredFieldIds`에 넣어
        // 오버플로 시트에서 뺐으므로 그 값은 이제 이 시트에만 있는데, 필드 목록이 비어 있으면
        // `buildColumnFieldMap`이 열마다 *"필드 정의를 찾을 수 없어 무시됨"*으로 흘려 버린다.
        // 세계관이 null이라 자동 필드 생성은 여전히 안 된다 — 전역 필드는 템플릿이 심는 것이라
        // 엑셀의 낯선 열을 근거로 만들어 낼 대상이 아니고, 그 경고는 종전 그대로 남는다.
        val globalFields = db.fieldDefinitionDao().getGlobalFieldsList()
        importCharacterRows(sheet, headerRow, null, globalFields, result, resolvedConflicts, UNCLASSIFIED_SHEET_NAME, onProgress, totalRows)
    }

    /** [countUnrestorableFieldValues] 결과 — 캐릭터 시트로는 복원할 수 없는 필드값의 규모. */
    private data class UnrestorableFieldValues(val characters: Int, val values: Int)

    /**
     * 캐릭터 시트가 열로 표현할 수 없는 필드값(미분류 캐릭터 + 타 세계관 잔여값)의 규모를 센다.
     * 덮어쓰기 삭제 전 "이 백업으로 되돌릴 수 있는가"를 판정하는 데만 쓴다 — 쓰기는 하지 않는다.
     * 삭제 직전 대형 트랜잭션 안에서 도는 코드이므로 전량 배치 로드로 N+1을 피한다.
     */
    private suspend fun countUnrestorableFieldValues(): UnrestorableFieldValues {
        val allValues = db.characterFieldValueDao().getAllValuesList()
        if (allValues.isEmpty()) return UnrestorableFieldValues(0, 0)
        val fieldsById = db.fieldDefinitionDao().getAllFieldsAllTypes().associateBy { it.id }
        val universeIdByNovelId = db.novelDao().getAllNovelsList().associate { it.id to it.universeId }
        val universeIdByCharId = db.characterDao().getAllCharactersList()
            .associate { it.id to it.novelId?.let { nid -> universeIdByNovelId[nid] } }

        // 판정은 **내보내기가 실제로 오버플로 시트에 담는 규칙과 같아야 한다.**
        // 종전에는 여기만 `fd.universeId != ownUniverseId`라는 다른 식을 써서, 자기 세계관의
        // **사건(entityType=event) 필드 정의**를 가리키는 값이 갈렸다 — 그 값은 캐릭터 시트가
        // 열로 담지 못해 오버플로 시트에만 있는데, 이 가드는 '복원 가능'으로 세어
        // 구버전 백업 덮어쓰기에서 무경고로 소멸시켰다. 단일 소스(CharacterFieldValueOverflow)를 쓴다.
        // null 키 = 전역 구역(무소속)의 필드 묶음 (B-119 확장)
        val charFieldIdsByUniverse: Map<Long?, Set<Long>> = fieldsById.values
            .filter { it.entityType == FieldDefinition.ENTITY_CHARACTER }
            .groupBy { it.universeId }
            .mapValues { (_, list) -> list.mapTo(HashSet()) { it.id } }

        var characters = 0
        var values = 0
        for ((charId, charValues) in allValues.groupBy { it.characterId }) {
            val ownUniverseId = universeIdByCharId[charId]
            // 무소속(null)도 **자기 묶음이 있다** — 전역 구역의 필드다 (B-149).
            // 종전에는 여기서 null을 빈 집합으로 떨어뜨려, '미분류 캐릭터' 시트가 이제 열로
            // 담는 값까지 *"이 백업으로는 복원할 수 없다"*고 세었다. 그러면 덮어쓰기 직전
            // 경고가 실제보다 크게 나와, **되돌릴 수 있는 백업을 되돌릴 수 없다고 말한다.**
            val covered = charFieldIdsByUniverse[ownUniverseId] ?: emptySet()
            val n = CharacterFieldValueOverflow.select(charValues, covered, fieldsById).size
            if (n > 0) { characters++; values += n }
        }
        return UnrestorableFieldValues(characters, values)
    }

    // ── 캐릭터 필드값 오버플로 가져오기 ──

    /**
     * 캐릭터 시트가 열로 담지 못한 필드값을 복원한다 — 미분류 캐릭터 + 타 세계관 잔여값.
     * 정체성은 (캐릭터, 세계관+필드키+대상)이며 캐릭터 시트가 이미 처리한 항목은 캐릭터 시트가 권위다.
     * 캐릭터·필드 정의 임포트가 모두 끝난 뒤 호출해야 (세계관, 키) 해석이 성립한다.
     */
    private suspend fun importCharacterFieldValues(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = characterFieldValueSpec()
        // F1-A: 시트가 없으면 기존 값 유지 (구버전 백업 호환) — 없는 것이 정상이라 경고하지 않는다.
        // **삭제 가드(canRestore)와 같은 해석**을 써야 한다. 가드만 접미사 변형을 수용하면
        // '복원 가능'이라 판정한 시트를 정작 판독기가 못 읽어 캐릭터가 통째로 사라진다.
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        // 위치 폴백 금지 — 첫 열만 checkHeaderOrReport가 보증한다
        val charCodeCol = cols["캐릭터코드"] ?: 0
        val charNameCol = cols["캐릭터이름"] ?: -1
        val uNameCol = cols["세계관"] ?: -1
        val uCodeCol = cols["세계관코드"] ?: -1
        val keyCol = cols["필드키"] ?: -1
        val entityCol = cols["대상"] ?: -1
        val valueCol = cols["값"] ?: -1

        if (keyCol < 0) {
            result.warnings.add("시트 '${spec.sheetName}': '필드키' 열이 없어 값의 정체를 확정할 수 없습니다 — 이 시트를 건너뜁니다(기존 값은 유지)")
            return
        }

        val allUniverses = db.universeDao().getAllUniversesList()
        val universesByName = allUniverses.associateBy { it.name }
        val universesByCode = allUniverses.associateBy { it.code }
        // null 키 = 전역 구역(무소속 — B-119 확장). '필드 정의' 시트와 같은 어휘다.
        val fieldCache = HashMap<Triple<Long?, String, String>, FieldDefinition?>()
        val seen = HashMap<Pair<Long, Long>, Int>()
        // **캐릭터와 그 기존 값도 세계관·필드와 같은 대접을 한다** (B-72 ②). 이 시트는 값 한 건이
        // 한 행이라 **같은 캐릭터가 자기 필드 수만큼 되풀이해 나온다**(실사용 ×30 = 36,510행에
        // 캐릭터는 6,420명 — 한 명당 평균 5.7행). 종전에는 그 행마다 캐릭터를 다시 조회하고
        // 값도 한 건씩 조회했다. 바로 위 두 줄이 이미 세우고 있던 것과 **같은 꼴로** 캐시한다.
        // **못 찾은 코드도 담는다**(값이 `null`) — 못 찾는 캐릭터일수록 같은 코드가 여러 행에
        // 되풀이되므로, 실패를 캐시하지 않으면 가장 나쁜 입력에서 캐시가 아무 일도 하지 않는다.
        //
        // **메모리 축을 새로 열지 않는다**(B-72가 지키는 그 축이다). 이 둘이 드는 최대량은
        // *이 시트가 가리키는 캐릭터*인데, `StreamingImportWorkbook`은 이미 **시트 하나를
        // 통째로** 들고 있고(행×열의 정규화 전 셀) 그 양이 여기 담기는 것보다 항상 크다 —
        // 캐릭터 한 명이 최소 한 행이기 때문이다. 함수가 끝나면 함께 풀린다.
        // **B-210에서 시트 지역 캐시를 걷었다.** 위 문단이 세운 두 맵(`charByCode`·`charsByName`)은
        // *처음 만나는 코드마다* 한 번씩 쳤고, 이 시트는 캐릭터가 6,420명이면 그만큼 다른 코드를
        // 만난다 — 캐시가 없앤 것은 되풀이분뿐이었다. 정체성 색인은 표를 **한 번** 읽어
        // 그 6,420회까지 없애고, 게다가 **캐릭터 시트가 방금 만든 캐릭터**도 함께 본다
        // (지역 캐시는 그 창을 못 봤다 — 시트가 갈리면 캐시도 갈렸다).

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val rowLabel = "캐릭터 필드값 행 $i"
                val charCode = getCellCode(row, charCodeCol, rowLabel, result)
                val charName = if (charNameCol >= 0) getCellString(row, charNameCol) else ""
                val uName = if (uNameCol >= 0) getCellString(row, uNameCol) else ""
                val uCode = if (uCodeCol >= 0) getCellCode(row, uCodeCol, rowLabel, result) else ""
                val fieldKey = getCellString(row, keyCol)
                if (charCode.isBlank() && charName.isBlank() && fieldKey.isBlank()) continue

                // 매칭 규약: 코드(안정 식별자) 우선 → 자연키 폴백 + 고지
                var character = characterByCode(charCode)
                if (character == null && charName.isNotBlank()) {
                    val byName = charactersByName(charName)
                    when {
                        byName.size == 1 -> {
                            character = byName.first()
                            result.nameBasedMappings++
                            result.warnings.add("$rowLabel: 코드로 찾지 못해 이름 '$charName'으로 매칭했습니다 — '캐릭터코드' 열을 확인하세요")
                        }
                        byName.size > 1 -> {
                            result.skippedRows++
                            result.warnings.add("$rowLabel: 이름 '$charName'인 캐릭터가 ${byName.size}명이라 확정할 수 없습니다 — '캐릭터코드' 열을 채워 주세요")
                            continue
                        }
                    }
                }
                val ch = character
                if (ch == null) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 캐릭터(코드 '$charCode' / 이름 '$charName')를 찾을 수 없습니다 — 캐릭터 시트를 함께 가져왔는지 확인하세요")
                    continue
                }

                // 세계관 이동이 감지된 캐릭터는 필드값이 새 세계관 필드로 이미 재매핑됐다.
                // 옛 세계관 키를 담은 이 행을 적용하면 방금 정리한 값이 되살아난다.
                if (ch.id in universeMovedCharacterIds) {
                    result.warnings.add("$rowLabel: '${ch.name}'은(는) 이번 가져오기에서 세계관이 바뀌어 필드값이 새 필드로 다시 연결되었습니다 — 이 행은 적용하지 않았습니다")
                    continue
                }

                // 세계관·세계관코드가 **둘 다 빈 행은 전역 구역**(무소속 — B-119 확장).
                // '필드 정의' 시트와 같은 판정이고(R-33), 이 시트의 내보내기가 이미 그렇게
                // 싣는다 — **미분류 캐릭터는 필드 열이 아예 없어 값 전부가 이 시트로 오므로**,
                // 갈래가 없던 동안 무소속 캐릭터의 전역 필드값은 되돌릴 길이 없었다(B-130).
                val globalScope = FieldScopeCell.isGlobal(uName, uCode)
                val universe = if (globalScope) null else {
                    uCode.takeIf { it.isNotBlank() }?.let { universesByCode[it] } ?: universesByName[uName]
                }
                if (universe == null && !globalScope) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 세계관 '${uName.ifBlank { uCode }}'을(를) 찾을 수 없습니다 — '세계관' 시트를 함께 가져오세요")
                    continue
                }

                val entityType = FieldValueSheetMapper.entityTypeOf(if (entityCol >= 0) getCellString(row, entityCol) else null)
                val fd = fieldCache.getOrPut(Triple(universe?.id, fieldKey, entityType)) {
                    if (universe == null) db.fieldDefinitionDao().getGlobalFieldByKey(fieldKey, entityType)
                    else db.fieldDefinitionDao().getFieldByKey(universe.id, fieldKey, entityType)
                }
                if (fd == null) {
                    result.skippedRows++
                    val where = if (globalScope) FieldScopeCell.GLOBAL_LABEL else "세계관 '${universe?.name}'"
                    result.warnings.add("$rowLabel: 필드 키 '$fieldKey'을(를) ${where}에서 찾을 수 없습니다 — '필드 정의' 시트를 함께 가져오세요")
                    continue
                }
                // 계산 필드는 수식으로 산출되는 파생값 — 내보내기와 대칭으로 저장하지 않는다
                if (fd.fieldType == FieldType.CALCULATED) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: '${fd.name}'은(는) 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨)")
                    continue
                }

                // 캐릭터 시트가 같은 (캐릭터, 필드)를 이미 처리했으면 캐릭터 시트가 권위 — 두 열이 다투지 않게 한다
                if ((ch.id to fd.id) in importedCharFieldPairs) {
                    result.warnings.add("$rowLabel: '${ch.name}'의 '${fd.name}'은(는) 캐릭터 시트에서 이미 처리되어 이 행을 무시했습니다 — 값은 캐릭터 시트에서 수정하세요")
                    continue
                }
                seen.put(ch.id to fd.id, i)?.let { prev ->
                    result.warnings.add("$rowLabel: 행 $prev 과(와) 같은 항목('${ch.name}'/'${fd.name}')을 다시 덮어썼습니다 (마지막 행 우선)")
                }

                val value = if (valueCol >= 0) getCellString(row, valueCol) else ""
                if (!valueLedger.isLoaded(ch.id)) {
                    valueLedger.load(ch.id, db.characterFieldValueDao().getValuesByCharacterList(ch.id))
                }
                val existing = valueLedger.get(ch.id, fd.id)
                if (value.isNotBlank()) {
                    if (existing != null) {
                        val updated = existing.copy(value = value)
                        db.characterFieldValueDao().update(updated)
                        valueLedger.put(updated)
                    } else {
                        val fresh = CharacterFieldValue(
                            characterId = ch.id, fieldDefinitionId = fd.id, value = value
                        )
                        valueLedger.put(fresh.copy(id = db.characterFieldValueDao().insert(fresh)))
                    }
                } else if (valueCol >= 0 && existing != null) {
                    // F1-A: 열이 있고 셀이 빈칸 = 비움 의도
                    db.characterFieldValueDao().deleteValue(ch.id, fd.id)
                    valueLedger.remove(ch.id, fd.id)
                    result.clearedFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("캐릭터 필드값 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "캐릭터 필드값", sheet.lastRowNum, totalRows)
    }

    // ── 작품·사건 필드값 오버플로 가져오기 (B-65) ──

    /**
     * 작품 시트가 열로 담지 못한 작품 필드값을 복원한다 (B-65 · 확정 15번 ㄴ1).
     *
     * '캐릭터 필드값' 시트와 **같은 규약**이다 — 정체성은 (작품, 구역+필드키)이고, 본 시트가
     * 이미 처리한 항목은 본 시트가 권위이며, 값 칸을 비우면 그 값이 지워진다. 규약을 시트마다
     * 따로 적으면 갈리므로 여기서도 같은 순서로 적는다.
     *
     * **대상(entityType) 열이 없는 것은 의도다** — 이 시트는 작품 필드만 담으므로 대상이 시트로
     * 이미 정해져 있다. 캐릭터판은 한 시트가 여러 대상의 값을 담을 수 있어 그 열이 필요했다.
     */
    private suspend fun importNovelFieldValues(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = novelFieldValueSpec()
        // F1-A: 시트가 없으면 기존 값 유지 (구버전 백업 호환) — 없는 것이 정상이라 경고하지 않는다.
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        // 위치 폴백 금지 — 첫 열만 checkHeaderOrReport가 보증한다
        val codeCol = cols["작품코드"] ?: 0
        val titleCol = cols["작품제목"] ?: -1
        val uNameCol = cols["세계관"] ?: -1
        val uCodeCol = cols["세계관코드"] ?: -1
        val keyCol = cols["필드키"] ?: -1
        val valueCol = cols["값"] ?: -1

        if (keyCol < 0) {
            result.warnings.add("시트 '${spec.sheetName}': '필드키' 열이 없어 값의 정체를 확정할 수 없습니다 — 이 시트를 건너뜁니다(기존 값은 유지)")
            return
        }

        val allUniverses = db.universeDao().getAllUniversesList()
        val universesByName = allUniverses.associateBy { it.name }
        val universesByCode = allUniverses.associateBy { it.code }
        // null 키 = 전역 구역(무소속 — B-119 확장). '필드 정의' 시트와 같은 어휘다.
        val fieldCache = HashMap<Pair<Long?, String>, FieldDefinition?>()
        val seen = HashMap<Pair<Long, Long>, Int>()
        // 제목 폴백용 색인 — **처음 필요할 때 한 번만** 뜬다. 행마다 전량 조회를 하면 행 수 ×
        // 작품 수가 되어, 코드 칸을 지운 파일 하나가 가져오기를 통째로 느리게 만든다(개발 의도 1).
        var novelsByTitle: Map<String, List<Novel>>? = null

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val rowLabel = "작품 필드값 행 $i"
                val code = getCellCode(row, codeCol, rowLabel, result)
                val title = if (titleCol >= 0) getCellString(row, titleCol) else ""
                val uName = if (uNameCol >= 0) getCellString(row, uNameCol) else ""
                val uCode = if (uCodeCol >= 0) getCellCode(row, uCodeCol, rowLabel, result) else ""
                val fieldKey = getCellString(row, keyCol)
                if (code.isBlank() && title.isBlank() && fieldKey.isBlank()) continue

                // 매칭 규약: 코드(안정 식별자) 우선 → 제목 폴백 + 고지 (캐릭터판과 같은 순서)
                var novel = novelByCode(code)
                if (novel == null && title.isNotBlank()) {
                    val index = novelsByTitle ?: db.novelDao().getAllNovelsList()
                        .groupBy { it.title }.also { novelsByTitle = it }
                    val byTitle = index[title].orEmpty()
                    when {
                        byTitle.size == 1 -> {
                            novel = byTitle.first()
                            result.nameBasedMappings++
                            result.warnings.add("$rowLabel: 코드로 찾지 못해 제목 '$title'으로 매칭했습니다 — '작품코드' 열을 확인하세요")
                        }
                        byTitle.size > 1 -> {
                            result.skippedRows++
                            result.warnings.add("$rowLabel: 제목 '$title'인 작품이 ${byTitle.size}개라 확정할 수 없습니다 — '작품코드' 열을 채워 주세요")
                            continue
                        }
                    }
                }
                val nv = novel
                if (nv == null) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 작품(코드 '$code' / 제목 '$title')을 찾을 수 없습니다 — '작품' 시트를 함께 가져왔는지 확인하세요")
                    continue
                }

                // 세계관·세계관코드가 **둘 다 빈 행은 전역 구역**(무소속). 판정은 FieldScopeCell 하나가 한다.
                val globalScope = FieldScopeCell.isGlobal(uName, uCode)
                val universe = if (globalScope) null else {
                    uCode.takeIf { it.isNotBlank() }?.let { universesByCode[it] } ?: universesByName[uName]
                }
                if (universe == null && !globalScope) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 세계관 '${uName.ifBlank { uCode }}'을(를) 찾을 수 없습니다 — '세계관' 시트를 함께 가져오세요")
                    continue
                }

                val fd = fieldCache.getOrPut(universe?.id to fieldKey) {
                    if (universe == null) db.fieldDefinitionDao().getGlobalFieldByKey(fieldKey, FieldDefinition.ENTITY_NOVEL)
                    else db.fieldDefinitionDao().getFieldByKey(universe.id, fieldKey, FieldDefinition.ENTITY_NOVEL)
                }
                if (fd == null) {
                    result.skippedRows++
                    val where = if (globalScope) FieldScopeCell.GLOBAL_LABEL else "세계관 '${universe?.name}'"
                    result.warnings.add("$rowLabel: 필드 키 '$fieldKey'을(를) ${where}에서 찾을 수 없습니다 — '필드 정의' 시트(대상=작품)를 함께 가져오세요")
                    continue
                }
                // 계산 필드는 수식으로 산출되는 파생값 — 내보내기와 대칭으로 저장하지 않는다
                if (fd.fieldType == FieldType.CALCULATED) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: '${fd.name}'은(는) 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨)")
                    continue
                }

                // 작품 시트가 같은 (작품, 필드)를 이미 처리했으면 작품 시트가 권위 — 두 열이 다투지 않게 한다
                if ((nv.id to fd.id) in importedNovelFieldPairs) {
                    result.warnings.add("$rowLabel: '${nv.title}'의 '${fd.name}'은(는) 작품 시트에서 이미 처리되어 이 행을 무시했습니다 — 값은 작품 시트에서 수정하세요")
                    continue
                }
                seen.put(nv.id to fd.id, i)?.let { prev ->
                    result.warnings.add("$rowLabel: 행 $prev 과(와) 같은 항목('${nv.title}'/'${fd.name}')을 다시 덮어썼습니다 (마지막 행 우선)")
                }

                val value = if (valueCol >= 0) getCellString(row, valueCol) else ""
                val existing = db.novelFieldValueDao().getValue(nv.id, fd.id)
                if (value.isNotBlank()) {
                    if (existing != null) db.novelFieldValueDao().update(existing.copy(value = value))
                    else db.novelFieldValueDao().insertAll(listOf(
                        com.novelcharacter.app.data.model.NovelFieldValue(
                            novelId = nv.id, fieldDefinitionId = fd.id, value = value
                        )
                    ))
                } else if (valueCol >= 0 && existing != null) {
                    // F1-A: 열이 있고 셀이 빈칸 = 비움 의도
                    db.novelFieldValueDao().delete(existing)
                    result.clearedFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("작품 필드값 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "작품 필드값", sheet.lastRowNum, totalRows)
    }

    /**
     * 연표 시트가 열로 담지 못한 사건 필드값을 복원한다 (B-65 · 확정 15번 ㄴ1).
     * 규약은 [importNovelFieldValues]와 같고, **정체는 사건 코드 하나뿐**이다 —
     * 연도·설명으로 되짚으면 같은 해의 비슷한 문장에 값이 붙는다(R-1: 오배정은 생략보다 나쁘다).
     */
    private suspend fun importEventFieldValues(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = eventFieldValueSpec()
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val codeCol = cols["사건코드"] ?: 0
        val descCol = cols["사건설명"] ?: -1
        val uNameCol = cols["세계관"] ?: -1
        val uCodeCol = cols["세계관코드"] ?: -1
        val keyCol = cols["필드키"] ?: -1
        val valueCol = cols["값"] ?: -1

        if (keyCol < 0) {
            result.warnings.add("시트 '${spec.sheetName}': '필드키' 열이 없어 값의 정체를 확정할 수 없습니다 — 이 시트를 건너뜁니다(기존 값은 유지)")
            return
        }

        val allUniverses = db.universeDao().getAllUniversesList()
        val universesByName = allUniverses.associateBy { it.name }
        val universesByCode = allUniverses.associateBy { it.code }
        val fieldCache = HashMap<Pair<Long?, String>, FieldDefinition?>()
        val seen = HashMap<Pair<Long, Long>, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val rowLabel = "사건 필드값 행 $i"
                val code = getCellCode(row, codeCol, rowLabel, result)
                val desc = if (descCol >= 0) getCellString(row, descCol) else ""
                val uName = if (uNameCol >= 0) getCellString(row, uNameCol) else ""
                val uCode = if (uCodeCol >= 0) getCellCode(row, uCodeCol, rowLabel, result) else ""
                val fieldKey = getCellString(row, keyCol)
                if (code.isBlank() && desc.isBlank() && fieldKey.isBlank()) continue

                if (code.isBlank()) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: '사건코드'가 비어 어느 사건의 값인지 확정할 수 없습니다 — 연표 시트의 '코드' 칸에서 값을 복사해 채워 주세요")
                    continue
                }
                val event = eventByCode(code)
                if (event == null) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 사건(코드 '$code')을 찾을 수 없습니다 — '사건 연표' 시트를 함께 가져왔는지 확인하세요")
                    continue
                }

                val globalScope = FieldScopeCell.isGlobal(uName, uCode)
                val universe = if (globalScope) null else {
                    uCode.takeIf { it.isNotBlank() }?.let { universesByCode[it] } ?: universesByName[uName]
                }
                if (universe == null && !globalScope) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 세계관 '${uName.ifBlank { uCode }}'을(를) 찾을 수 없습니다 — '세계관' 시트를 함께 가져오세요")
                    continue
                }

                val fd = fieldCache.getOrPut(universe?.id to fieldKey) {
                    if (universe == null) db.fieldDefinitionDao().getGlobalFieldByKey(fieldKey, FieldDefinition.ENTITY_EVENT)
                    else db.fieldDefinitionDao().getFieldByKey(universe.id, fieldKey, FieldDefinition.ENTITY_EVENT)
                }
                if (fd == null) {
                    result.skippedRows++
                    val where = if (globalScope) FieldScopeCell.GLOBAL_LABEL else "세계관 '${universe?.name}'"
                    result.warnings.add("$rowLabel: 필드 키 '$fieldKey'을(를) ${where}에서 찾을 수 없습니다 — '필드 정의' 시트(대상=사건)를 함께 가져오세요")
                    continue
                }
                if (fd.fieldType == FieldType.CALCULATED) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: '${fd.name}'은(는) 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨)")
                    continue
                }

                if ((event.id to fd.id) in importedEventFieldPairs) {
                    result.warnings.add("$rowLabel: 사건 '${event.description.take(20)}'의 '${fd.name}'은(는) 연표 시트에서 이미 처리되어 이 행을 무시했습니다 — 값은 연표 시트에서 수정하세요")
                    continue
                }
                seen.put(event.id to fd.id, i)?.let { prev ->
                    result.warnings.add("$rowLabel: 행 $prev 과(와) 같은 항목을 다시 덮어썼습니다 (마지막 행 우선)")
                }

                val value = if (valueCol >= 0) getCellString(row, valueCol) else ""
                val existing = db.eventFieldValueDao().getValue(event.id, fd.id)
                if (value.isNotBlank()) {
                    if (existing != null) db.eventFieldValueDao().update(existing.copy(value = value))
                    else db.eventFieldValueDao().insertAll(listOf(
                        com.novelcharacter.app.data.model.EventFieldValue(
                            eventId = event.id, fieldDefinitionId = fd.id, value = value
                        )
                    ))
                } else if (valueCol >= 0 && existing != null) {
                    db.eventFieldValueDao().delete(existing)
                    result.clearedFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("사건 필드값 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "사건 필드값", sheet.lastRowNum, totalRows)
    }

    /**
     * Shared character import logic for both universe and unclassified sheets.
     * Sprint A: Strict code-first matching with conflict detection
     * Sprint B: Scope-based displayOrder
     * Sprint C: Tolerant header resolution
     */
    private suspend fun importCharacterRows(
        sheet: Sheet,
        headerRow: Row,
        universe: Universe?,
        fields: List<FieldDefinition>,
        result: ImportResult,
        resolvedConflicts: Map<String, CharacterConflict>,
        sheetLabel: String,
        onProgress: (ImportProgress) -> Unit,
        totalRows: Int
    ) {
        // 캐릭터 시트는 고정 열 **정확 일치**를 별칭 해석보다 우선한다 —
        // 커스텀 필드명이 '메모'(또는 별칭인 '비고')여도 고정 '메모' 열을 빼앗지 못하게 한다.
        val cols = resolveHeaderColumns(headerRow, result, sheetLabel, CHARACTER_FIXED_HEADERS)
        // U-10: 계산 필드 열에 값을 써 넣었지만 저장되지 않은 열 — 열 단위로 한 번만 알린다
        // (행마다 내면 214행짜리 시트에서 같은 경고가 214번 쌓인다). 사건 시트의 형제 경고와 같은 방식.
        val droppedCalculatedHeaders = mutableSetOf<String>()
        val cc = CharacterCols(cols)
        val nowMillis = System.currentTimeMillis()
        val nameColIndex = cc.name
        val tagsColIndex = cc.tags
        val fixedColIndices = setOf(cc.name, cc.anotherName, cc.lastName, cc.firstName, cc.image, cc.novel, cc.memo, cc.tags, cc.code, cc.novelCode, cc.order, cc.pinned, cc.createdAt).filter { it >= 0 }.toSet()
        val columnFieldMap = buildColumnFieldMap(headerRow, fields, fixedColIndices, universe, result, sheetLabel)

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()
        // "이 캐릭터에 태그가 있었는가"만 묻던 자리 — 빈 셀로 태그를 비우는 행마다 표를 다시
        // 읽었다(목표 규모에서 캐릭터 6,420명). 태그표를 **한 번** 읽어 가진 캐릭터의 id 집합으로
        // 답하고, 아래 교체·삭제가 그 집합을 함께 옮긴다 (B-210).
        // **이 시트 함수는 세계관마다 다시 불리므로 집합도 그때마다 새로 뜬다** — 앞 시트의
        // 쓰기는 이미 표에 있으니 다시 읽는 쪽이 옳다.
        val charactersWithTags = db.characterTagDao().getAllTagsList().mapTo(HashSet()) { it.characterId }

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readCharacterRow(row, cc, "캐릭터 행 $i", nowMillis, result)
                val name = r.name
                if (name.isBlank()) continue

                val code = r.code
                val novelCode = r.novelCode
                val novelTitle = r.novelTitle

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("캐릭터: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Resolve novel: code-first, then title. F1-A: 작품/작품코드 열이 모두 없으면 기존 배정을 유지한다(아래 적용).
                // 열이 있으면 셀 해석(빈칸=미배정, 사용자 의도 존중). resolveNovelId는 제목이 있을 때만 호출(빈 제목 유령 생성 방지).
                val novelColumnsPresent = r.novelColumnsPresent
                val novelId: Long? = novelByCode(novelCode)?.id
                    ?: if (novelTitle.isNotBlank()) {
                        resolveNovelId(novelTitle, universe?.id, result, "캐릭터 행 $i")
                    } else null

                val imagePathsFromExcel: String? = r.imagePaths
                val memoFromExcel: String? = r.memo
                val displayOrder: Long? = r.displayOrder

                // 충돌 해결 확인
                val conflictKey = "$sheetLabel:$i"
                val conflict = resolvedConflicts[conflictKey]
                // 충돌 대화상자에 이름이 오른 캐릭터는 '엑셀이 인지한 캐릭터'다 — 어떤 결정(SKIP/신규 생성/기존 갱신)에서도
                // 자동 삭제 대상이 될 수 없다. SKIP은 아래에서 continue로 빠지므로 반드시 그 전에 등록한다.
                if (conflict != null) {
                    for (candidate in conflict.existingCharacters) matchedCharacterIds.add(candidate.id)
                }
                if (conflict != null && conflict.resolution == ConflictResolution.SKIP) {
                    result.skippedRows++
                    continue
                }

                // Code-first matching (Sprint A strict rule)
                val existingChar: Character?
                if (conflict != null) {
                    // 충돌이 해결된 행: 사용자 결정에 따라 매칭
                    existingChar = when (conflict.resolution) {
                        ConflictResolution.CREATE_NEW -> null // 강제 신규 생성
                        ConflictResolution.UPDATE_EXISTING -> {
                            conflict.selectedExistingId?.let { db.characterDao().getCharacterById(it) }
                        }
                        ConflictResolution.SKIP -> null // 이미 위에서 처리됨
                    }
                } else if (code.isNotBlank()) {
                    val byCode = characterByCode(code)
                    if (byCode != null) {
                        existingChar = byCode
                    } else {
                        // F1-C: 코드가 있으나 DB에 없음 → 조용히 신규 생성하지 않고 자연키(이름) 폴백 + 경고
                        val byName = if (novelId != null) {
                            characterByNameAndNovel(name, novelId)
                        } else {
                            characterByName(name)
                        }
                        if (byName != null) {
                            existingChar = byName
                            result.nameBasedMappings++
                            result.warnings.add("캐릭터 행 $i: 코드 '$code'를 찾지 못해 이름 '$name'으로 매칭함 — 의도한 새 캐릭터라면 코드를 비우세요")
                        } else {
                            existingChar = null
                            warnCreatedNewByCode("characters", "캐릭터 행 $i: 코드 '$code'가 기존 캐릭터에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                        }
                    }
                } else {
                    // No code => fallback with warning
                    existingChar = if (novelId != null) {
                        characterByNameAndNovel(name, novelId)
                    } else {
                        characterByName(name)
                    }
                    if (existingChar != null) {
                        result.nameBasedMappings++
                        result.warnings.add("캐릭터 행 $i: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                val charId: Long
                if (existingChar != null) {
                    charId = existingChar.id
                    // 빈칸으로 기존 텍스트가 지워지는 경우 요약("지워진 값 N건")에 집계 — 조용한 per-cell 삭제 방지(변수 제어).
                    // (열 없음이면 xFromExcel==null → 유지되어 카운트 안 됨. 열 있음+빈칸("")+기존값 있음일 때만 삭제로 집계.)
                    if (r.firstName == "" && existingChar.firstName.isNotBlank()) result.clearedFields++
                    if (r.lastName == "" && existingChar.lastName.isNotBlank()) result.clearedFields++
                    if (r.anotherName == "" && existingChar.anotherName.isNotBlank()) result.clearedFields++
                    if (memoFromExcel == "" && existingChar.memo.isNotBlank()) result.clearedFields++
                    // imagePaths는 `withImagePaths`로 넘긴다 — 대표 포인터(B-103)가 재매핑을
                    // 따라가고, 다른 기기에서 온 목록에 그 파일이 없으면 풀린다(D5).
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    // **한 번만 부른다** — 이 함수는 `result`를 받아 대표 셀 고지를 쌓으므로
                    // 세는 김에 다시 부르면 그 고지가 두 번 붙는다.
                    val mergedChar = mergeCharacter(existingChar, r, novelId, i, nowMillis, result)
                    db.characterDao().update(mergedChar)
                    // 정체성 색인도 함께 옮긴다 — 이름·코드가 바뀌었으면 **옛 키로는 더 이상
                    // 잡히지 않아야** SQL과 같은 답이 된다(B-210).
                    rememberCharacter(mergedChar)
                    if (mergedChar != existingChar) result.updatedCharacters++ else result.unchangedRows++

                    // 사용자가 이전 세계관 필드값 삭제를 선택한 경우 정리
                    if (universe != null && conflict?.cleanupOldFields == true) {
                        db.characterFieldValueDao().deleteValuesNotInUniverse(charId, universe.id)
                        // 장부가 이 캐릭터를 이미 실었다면 그 사본은 방금 지운 값을 아직 들고 있다.
                        // 내려 두면 아래에서 다시 읽는다 — 같은 캐릭터가 시트에 두 번 나오는
                        // 파일(중복 행 고지 대상)에서만 실제로 갈리는 자리다.
                        valueLedger.forget(charId)
                    }
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newCharacter = applyRepresentativeCell(
                        Character(
                            name = name, firstName = r.firstName ?: "", lastName = r.lastName ?: "",
                            anotherName = r.anotherName ?: "", novelId = if (novelColumnsPresent) novelId else null,
                            imagePaths = imagePathsFromExcel ?: "[]", memo = memoFromExcel ?: "", code = newCode, displayOrder = displayOrder ?: i.toLong(),
                            isPinned = r.isPinned ?: false, createdAt = r.createdAt ?: nowMillis
                        ),
                        r.representativeCell, name, i, result
                    )
                    charId = db.characterDao().insert(newCharacter)
                    // 방금 만든 캐릭터를 **곧바로 읽히게** 한다 — 같은 파일의 뒷 행·뒷 시트가
                    // 코드·이름으로 이 캐릭터를 찾는다(연표 참가자·상태변화·이름 은행).
                    // 빠뜨리면 *있는 것을 없다고* 보고 같은 캐릭터가 둘로 갈린다(B-210).
                    rememberCharacter(newCharacter.copy(id = charId))
                    result.newCharacters++
                }

                // F3-A: 엑셀에서 작품이 바뀌어 세계관이 이동했는지 감지 (existingChar.novelId=이동 전, novelId=이동 후).
                // 이동이면 아래 필드 기록 후 편집화면과 동일한 P0 로직으로 재매핑·정리한다.
                val movedToUniverseId: Long? = if (existingChar != null && novelColumnsPresent && novelId != existingChar.novelId) {
                    // 같은 작품이 시트 안에서 되풀이되므로 메모된 helper로 답한다(B-210).
                    val oldU = existingChar.novelId?.let { universeIdOfNovel(it) }
                    val newU = novelId?.let { universeIdOfNovel(it) }
                    if (oldU != null && newU != null && oldU != newU) newU else null
                } else null

                // matched ID 추적 (deleteNotInExcel용) — 시트 세계관과 무관하게 전역 등록.
                // 엑셀 편집으로 캐릭터가 다른 세계관 작품으로 이동해도 삭제 대상이 되지 않게 한다.
                matchedCharacterIds.add(charId)

                val prevRow = entitySeen[charId]
                if (prevRow != null) {
                    result.warnings.add("캐릭터 행 $i: 행 $prevRow 과 같은 항목('$name')을 다시 덮어씀 — 별개의 캐릭터로 넣으려면 '코드' 칸을 비우고 이름을 다르게 한 뒤 다시 가져오세요")
                }
                entitySeen[charId] = i

                // 태그 가져오기 — F1-A 규칙 가: 열 없음(colIndex<0)=기존 유지, 빈 셀=삭제(요약 고지), 값 있음=교체
                if (tagsColIndex >= 0) {
                    val tagsStr = getCellString(row, tagsColIndex)
                    if (tagsStr.isNotBlank()) {
                        db.characterTagDao().deleteAllByCharacter(charId)
                        val tags = splitCsv(tagsStr)
                        tags.forEach { tag ->
                            db.characterTagDao().insert(CharacterTag(characterId = charId, tag = tag))
                        }
                        if (tags.isEmpty()) charactersWithTags.remove(charId) else charactersWithTags.add(charId)
                    } else if (existingChar != null) {
                        // 빈 셀 = 태그 비움. 기존 태그가 있었을 때만 삭제·요약 집계
                        if (charactersWithTags.contains(charId)) {
                            db.characterTagDao().deleteAllByCharacter(charId)
                            charactersWithTags.remove(charId)
                            result.clearedFields++
                        }
                    }
                }

                // 동적 필드 값 가져오기 (빈 셀 = 기존 값 삭제)
                var hasSemanticField = false
                // **이 캐릭터의 기존 값을 한 번에 든다** (B-72 ② · [CharacterValueLedger]).
                // 종전에는 열마다 `getValue`를 한 건씩 쳤다 — 셀 단위 조회라 **행 수가 아니라
                // (행 × 필드 열) 수만큼** 늘었다(실사용 ×30 = 6,420행 × 90열 ≈ 57만 회).
                // **싣는 자리가 규약이다** — 세계관 이동 정리(`deleteValuesNotInUniverse`)보다
                // **뒤**여야 방금 지운 값을 되살리지 않는다. 새로 만든 캐릭터는 값이 있을 수
                // 없으므로 빈 목록으로 실어 조회 자체를 건너뛴다.
                if (!valueLedger.isLoaded(charId)) {
                    valueLedger.load(charId,
                        if (existingChar == null) emptyList()
                        else db.characterFieldValueDao().getValuesByCharacterList(charId))
                }
                for ((colIndex, field) in columnFieldMap) {
                    // F4: CALCULATED는 다른 필드로부터 실시간 산출되는 파생값 — 저장하지 않는다(읽기 전용).
                    // 내보내기 시 계산 결과를 표시하지만 가져오기 때 저장하면 stale 중복 데이터가 된다.
                    if (field.fieldType == FieldType.CALCULATED) {
                        // U-10: 종전에는 **무통보 폐기**였다 — 엑셀에서 계산 열에 값을 적어 넣고
                        // 가져와도 아무 말 없이 사라져, 사용자는 반영된 줄 안다.
                        // 형제 경고(사건 시트·'캐릭터 필드값' 시트)와 같은 문구로 맞춘다.
                        if (getCellString(row, colIndex).isNotBlank() &&
                            droppedCalculatedHeaders.add(field.name)
                        ) {
                            result.warnings.add(
                                "$sheetLabel 시트의 '${field.name}' 열은 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨) — " +
                                "값을 직접 넣으려면 그 필드의 타입을 계산 필드에서 바꾸세요"
                            )
                        }
                        continue
                    }
                    // 이 (캐릭터, 필드)는 캐릭터 시트가 권위 — '캐릭터 필드값' 시트의 같은 항목은 무시된다
                    importedCharFieldPairs.add(charId to field.id)
                    val isDateField = SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE
                    val value = getCellString(row, colIndex, dateHint = isDateField)
                    // 필드 타입 검증 (F1-B): 거부하지 않고 저장하되 경고 (수용·교정 원칙 — 통계 누락을 인지시킴)
                    if (value.isNotBlank()) {
                        // **타입이 늘면 여기가 컴파일을 깬다** (B-55). 코틀린은 enum을 받는
                        // `when` **문**도 전부 덮기를 요구하므로, 새 타입은 "이 값을 어떻게
                        // 검증하는가"를 여기서 반드시 답하게 된다 — 종전(문자열 분기)에는
                        // 아무 말 없이 검증 없는 타입이 하나 늘었다.
                        when (field.fieldType) {
                            FieldType.NUMBER -> if (value.toDoubleOrNull() == null) {
                                result.warnings.add("캐릭터 행 $i: 숫자 필드 '${field.name}'에 숫자가 아닌 값 '$value'이(가) 저장됨 — 통계에서 제외될 수 있습니다")
                            }
                            FieldType.GRADE -> if (GradeValueResolver.resolveForDisplay(field, value) == null) {
                                result.warnings.add("캐릭터 행 $i: 등급 필드 '${field.name}'의 값 '$value'을(를) 인식할 수 없습니다 — 통계·수식에서 제외될 수 있습니다")
                            }
                            FieldType.BODY_SIZE -> if (!value.any { it.isDigit() }) {
                                result.warnings.add("캐릭터 행 $i: 신체 사이즈 필드 '${field.name}'의 값 '$value'에 숫자가 없어 통계에 반영되지 않을 수 있습니다")
                            }
                            // 글자·선택·복수 텍스트는 어떤 값이든 그 타입의 값이다 — 잴 것이 없다.
                            FieldType.TEXT, FieldType.SELECT, FieldType.MULTI_TEXT -> Unit
                            // 계산 필드는 위에서 이미 `continue`로 걸러졌다(저장 대상이 아니다).
                            FieldType.CALCULATED -> Unit
                            // 모르는 타입 — 잣대가 없으니 재지 않는다. 값은 그대로 저장된다
                            // (거부가 아니라 수용·교정. 개발 의도 4번).
                            null -> Unit
                        }
                    }
                    // 별칭 표기 감지 — F1-B 원문 저장 원칙 유지(무편집 왕복 불변), 치환하지 않고 고지만 한다.
                    // 정규화는 인앱 라이브러리(병합·AI 정리)가 확인 후 수행하는 교정 경로.
                    if (value.isNotBlank() && com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(field)) {
                        val resolver = importAliasResolvers.getOrPut(field.id) {
                            com.novelcharacter.app.util.FieldValueResolver(db.fieldValueEntryDao().getByField(field.id))
                        }
                        val aliasTokens = com.novelcharacter.app.util.FieldValueTokenizer.tokenize(field, value)
                            .filter { resolver.canonical(it) != it }
                        if (aliasTokens.isNotEmpty()) {
                            result.warnings.add("캐릭터 행 $i: '${field.name}' 값 ${aliasTokens.joinToString(", ") { "'$it'" }}은(는) 라이브러리 별칭 표기입니다 — 원문대로 저장됨, 라이브러리에서 표기를 정리할 수 있습니다")
                        }
                    }
                    val existingValue = valueLedger.get(charId, field.id)
                    if (value.isNotBlank()) {
                        if (existingValue != null) {
                            val updated = existingValue.copy(value = value)
                            db.characterFieldValueDao().update(updated)
                            valueLedger.put(updated)
                        } else {
                            val fresh = CharacterFieldValue(
                                characterId = charId, fieldDefinitionId = field.id, value = value
                            )
                            valueLedger.put(fresh.copy(id = db.characterFieldValueDao().insert(fresh)))
                        }
                        if (!hasSemanticField && SemanticRole.fromConfig(field.config) != null) {
                            hasSemanticField = true
                        }
                    } else if (existingValue != null) {
                        // 빈 셀 = 값 삭제 (F1-A 규칙 가: 요약 집계)
                        db.characterFieldValueDao().deleteValue(charId, field.id)
                        valueLedger.remove(charId, field.id)
                        result.clearedFields++
                    }
                }
                // 시맨틱 역할 필드가 임포트되었으면 동기화 대상에 추가 (이동 시엔 새 세계관 기준)
                if (hasSemanticField) {
                    val syncUniverseId = movedToUniverseId ?: universe?.id
                    if (syncUniverseId != null) pendingSyncCharacters[charId] = syncUniverseId
                }

                // F3-A: 세계관 이동이면 편집화면과 동일한 P0 로직으로 필드값 재매핑·타 세계관 세력 소속 정리·스냅샷.
                // 필드 기록 이후 호출해 방금 쓴 값도 새 세계관 필드로 key 기준 재매핑되게 한다(유실은 휴지통 스냅샷).
                if (movedToUniverseId != null) {
                    universeMovedCharacterIds.add(charId)
                    // 재매핑은 이 캐릭터의 값을 **장부를 거치지 않고** 통째로 다시 쓴다(옛 필드 →
                    // 새 세계관의 같은 key 필드). 사본을 그대로 두면 장부가 옛 필드 id를 들고 있어,
                    // 같은 캐릭터가 시트에 다시 나올 때 **이미 있는 (캐릭터, 새 필드)에 insert를 쳐
                    // 유니크 색인을 깬다.** `deleteValuesNotInUniverse` 자리와 같은 처방이다 —
                    // **`character_field_values`를 장부 밖에서 건드리는 자리는 반드시 여기서 내린다**
                    // (`tools/check_value_ledger_sync.sh`가 그 짝을 기계로 본다).
                    valueLedger.forget(charId)
                    db.characterDao().getCharacterById(charId)?.let { moved ->
                        // 이 임포트가 만드는 모든 스냅샷은 같은 저장소 인스턴스가 남긴다 —
                        // 커밋 후 정리가 "이 작업이 만든 백업"을 알아보고 보호해야 하기 때문이다.
                        // (캐릭터마다 새 인스턴스를 쓰면 같은 임포트의 정리가 그 백업을 태운다)
                        val counts = characterRepository.migrateCharacterToUniverse(
                            moved, movedToUniverseId, trashForImport()
                        )
                        when {
                            counts.hasRemoval -> result.warnings.add(
                                "캐릭터 행 $i: '$name' 세계관 이동 감지 — 대응 없는 필드값 ${counts.removedValues}개·타 세계관 세력소속 ${counts.removedMemberships}개 정리(휴지통에 스냅샷 보관), ${counts.remappedValues}개 다시 연결")
                            counts.remappedValues > 0 -> result.warnings.add(
                                "캐릭터 행 $i: '$name' 세계관 이동 감지 — 필드값 ${counts.remappedValues}개를 새 세계관 필드로 다시 연결")
                        }
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                val sheetLabel = universe?.name ?: "미분류 캐릭터"
                result.errors.add("$sheetLabel 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, universe?.name ?: "미분류 캐릭터", sheet.lastRowNum, totalRows)
    }

    // ── 연표 가져오기 ──

    private suspend fun importTimeline(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = timelineSpec(emptyList())
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        // 사건 커스텀 필드는 동적 열이므로 미인식 대상에서 제외한다(해석 실패는 아래에서 따로 고지)
        reportUnknownColumns(headerRow, spec, result, dynamicColumnPrefixes = listOf(EntityFieldHeaders.PREFIX))
        val cols = resolveHeaderColumns(headerRow)
        // 필수 컬럼: 위치 폴백을 쓰면 컬럼 삭제 시 이웃 컬럼 데이터가 사건 설명으로 오기록되므로 검증 후 스킵
        val descColIndex = TimelineCols.descColumn(cols)
            ?: requiredCol(cols, "사건 설명", sheet.sheetName, result) ?: return
        val tc = TimelineCols(cols, descColIndex)
        val nowMillis = System.currentTimeMillis()
        val charColIndex = cols["관련 캐릭터"] ?: -1
        val charCodeColIndex = cols["관련캐릭터코드"] ?: -1
        // F1-A: 참가자 열이 실제로 헤더에 존재하는지 (위치 폴백만으로는 "빈칸=삭제" 규칙을 적용하지 않음 — 구버전 파일 오삭제 방지)
        val participantColumnPresent = cols.containsKey("관련 캐릭터") || charCodeColIndex >= 0

        val allNovels = db.novelDao().getAllNovelsList()
        // 사건 정체성 색인은 **가져오기 수명**이다(위 [eventCodes] 문단) — 이 시트가 만든 사건을
        // '사건 필드값'·'관계 변화' 시트가 곧이어 코드로 찾는다.
        //
        // "이 사건에 연결이 있는가"만 묻던 두 자리 — 교차표를 한 번 읽어 **가진 사건의 id 집합**으로
        // 답한다. 아래 교체·삭제가 이 집합을 함께 옮긴다(안 옮기면 *비움 의도*를 두 번 세거나
        // 아예 못 센다).
        val eventsWithNovelLinks = db.timelineDao().getAllEventNovelCrossRefs().mapTo(HashSet()) { it.eventId }
        val eventsWithParticipants = db.timelineDao().getAllCrossRefs().mapTo(HashSet()) { it.eventId }
        val eventCodesSeen = mutableSetOf<String>()
        // 정의 없는 "필드:" 열의 값 유실 고지용 — (헤더명) 단위로 1회만 경고
        val droppedEntityFieldHeaders = mutableSetOf<String>()

        // 사건 커스텀 필드 컬럼 (B-10): "필드:{이름}" 또는 "필드:{이름}({세계관})" 헤더 스캔.
        // 정규식 추측 파싱은 이름이 괄호로 끝나는 필드('규모(명)')를 세계관 한정으로 오인하므로,
        // **내보내기 규칙의 결정론적 역함수**(기대 헤더 → 필드)를 최우선으로 조회한다.
        val allEventFields = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_EVENT)
        val universeNamesById = db.universeDao().getAllUniversesList().associate { it.id to it.name }
        // 한정자(세계관명) → id. 작품 시트와 같은 재료로 같은 해석기를 부른다(B-65).
        val universeIdsByName = universeNamesById.entries.associate { (id, name) -> name to id }
        val knownUniverseNames = universeNamesById.values.toHashSet()
        val knownEventFieldNames = allEventFields.mapTo(HashSet()) { it.name }
        // 내보내기와 동일 규칙(EntityFieldHeaders)으로 기대 헤더 맵을 만들어 정확 일치를 최우선 조회한다
        val expectedEventHeaders = EntityFieldHeaders.expectedHeaders(allEventFields, universeNamesById)
        val eventFieldColumns = mutableListOf<EventFieldColumn>()
        val eventFieldHeadersSeen = mutableSetOf<String>()
        for (ci in 0 until headerRow.lastCellNum) {
            val header = getCellString(headerRow, ci)
            if (!header.startsWith(EntityFieldHeaders.PREFIX)) continue
            if (!eventFieldHeadersSeen.add(header)) {
                result.warnings.add("사건 연표: 필드 열 '$header'이(가) 중복되어 뒤쪽 열을 무시했습니다 — 필드명이 겹치지 않는지 확인하세요")
                continue
            }
            val exact = expectedEventHeaders[header]
            if (exact != null) {
                // 내보낸 그대로의 헤더 — 이름·세계관명에 어떤 문자가 있어도 정확히 복원된다
                eventFieldColumns.add(EventFieldColumn(ci, header, exact.name, exact.universeId?.let { universeNamesById[it] }, exact))
                continue
            }
            val parsed = EntityFieldHeaders.parseFallback(header, knownEventFieldNames, knownUniverseNames) ?: continue
            eventFieldColumns.add(EventFieldColumn(ci, header, parsed.fieldName, parsed.universeName, null))
        }

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readTimelineRow(row, tc, "연표 행 $i", nowMillis, result)
                val year = r.year
                if (year == null) {
                    // 행이 조용히 사라지지 않도록 보고 (빈 행은 제외)
                    if (r.yearRaw.isNotBlank() || r.description.isNotBlank()) {
                        result.skippedRows++
                        result.errors.add("연표 행 $i: 연도 '${r.yearRaw}'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    }
                    continue
                }
                val description = r.description
                if (description.isBlank()) continue

                val novelTitle = r.novelTitle
                val novelCode = r.novelCode
                // 작품 연결·세계관 소속 해석도 미리보기와 **같은 함수**다.
                val links = resolveTimelineLinks(row, tc, r, allNovels, "연표 행 $i", result)
                val novelIds = links.novelIds

                val fileCode = r.fileCode
                if (fileCode.isNotBlank() && !eventCodesSeen.add(fileCode)) {
                    result.warnings.add("연표 행 $i: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 사건을 덮어씁니다")
                }
                // 매칭: 코드 우선(설명·연도 편집을 같은 사건으로 인식) → 자연키 폴백(구버전 파일 호환)
                val existingEvent = eventByCode(fileCode) ?: eventByNaturalKey(year, description)

                val eventId: Long
                if (existingEvent != null) {
                    eventId = existingEvent.id
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedEvent = mergeTimelineEvent(existingEvent, r, links, generateEntityCode())
                    db.timelineDao().update(mergedEvent)
                    // 연도·설명·코드가 바뀌었을 수 있다 — 색인이 옛 키를 계속 가리키면 뒤 행이
                    // **이미 옮겨 간 사건을 옛 이름으로** 다시 잡는다(B-210).
                    rememberEvent(mergedEvent)
                    // 작품이 해석된 경우에만 M2M 교체; 해석 실패 시 기존 관계 유지 + 경고
                    if (novelIds.isNotEmpty()) {
                        db.timelineDao().replaceEventNovels(eventId, novelIds)
                        eventsWithNovelLinks.add(eventId)
                    } else if (novelTitle.isNotBlank() || novelCode.isNotBlank()) {
                        result.warnings.add("사건 행 $i: 작품 '${novelTitle}'을(를) 찾을 수 없어 기존 작품 연결을 유지합니다")
                    } else if (tc.novelLinkColumnPresent) {
                        // F1-A 규칙 가: 작품 열이 있으나 비어 있음 → 기존 작품 연결 삭제 (요약 집계, 세계관 소속은 universeId로 유지)
                        if (eventsWithNovelLinks.contains(eventId)) {
                            db.timelineDao().deleteEventNovelCrossRefsByEvent(eventId)
                            eventsWithNovelLinks.remove(eventId)
                            result.clearedFields++
                        }
                    }
                    if (mergedEvent != existingEvent) result.updatedEvents++ else result.unchangedRows++
                } else {
                    val newEvent = TimelineEvent(
                        year = year, month = r.month, day = r.day,
                        calendarType = r.calendarType, description = description,
                        eventType = r.eventType,
                        universeId = links.universeId,
                        displayOrder = r.displayOrder ?: i, isTemporary = r.isTemporary,
                        createdAt = r.createdAt ?: nowMillis,
                        // 파일의 코드를 보존해 기기 이전 후에도 왕복 정체성 유지 (없으면 자동 생성)
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    )
                    eventId = db.timelineDao().insert(newEvent)
                    // 방금 만든 사건을 곧바로 읽히게 한다 — 같은 코드를 든 뒷 행이 이것을
                    // 못 보면 한 파일에서 같은 사건이 둘로 갈린다.
                    rememberEvent(newEvent.copy(id = eventId))
                    db.timelineDao().replaceEventNovels(eventId, novelIds)
                    if (novelIds.isEmpty()) eventsWithNovelLinks.remove(eventId) else eventsWithNovelLinks.add(eventId)
                    result.newEvents++
                    if (novelIds.isEmpty() && novelTitle.isNotBlank()) {
                        result.warnings.add("사건 행 $i: 작품 '${novelTitle}'을(를) 찾을 수 없어 작품 미지정 상태로 생성됨")
                    }
                }
                matchedEventIds.add(eventId)

                // 사건 커스텀 필드 값 반영 (B-10) — 빈 셀 = 기존 값 삭제 (캐릭터 필드와 동일 규약)
                // 소속 해석은 위 update와 **같은 값**을 봐야 한다 — 두 곳에 따로 쓰면
                // 한쪽만 고쳐질 때 방금 옮긴 사건에 옛 세계관 필드가 붙는다.
                val eventUniverseId = links.universeId ?: existingEvent?.universeId
                if (eventFieldColumns.isNotEmpty()) {
                    val newValues = mutableListOf<com.novelcharacter.app.data.model.EventFieldValue>()
                    // 시트에 실제로 존재하고 해석에 성공한 필드만 교체 대상 — 열이 없던 필드값은 보존(F1-A 열 단위)
                    val resolvedFieldIds = mutableListOf<Long>()
                    for (col in eventFieldColumns) {
                        val ci = col.colIndex
                        // 구역 해석은 작품 시트와 **같은 함수**다 — 열 이름이 맞으면 세계관이
                        // 달라도 받는다(B-65 · 확정 15번 ㄱ1). 종전에는 정확 일치로 필드를
                        // 확정해 놓고도 그 필드의 세계관이 이 사건과 다르면 값을 버렸다.
                        val fieldDef = EntityFieldColumnResolver.resolve(
                            col.resolved, col.fieldName, col.universeName, eventUniverseId,
                            allEventFields, universeIdsByName
                        )
                        if (fieldDef == null) {
                            // 셀에 값이 있을 때만 1회 경고한다. 키는 원본 헤더 — 필드명으로 묶으면
                            // 서로 다른 세계관의 동명 열이 하나로 뭉쳐 경고가 누락된다.
                            // 작품 시트와 **같은 두 갈래 문구**다(고칠 길이 다르다).
                            if (getCellString(row, ci).isNotBlank() && droppedEntityFieldHeaders.add(col.header)) {
                                result.warnings.add(
                                    if (allEventFields.any { it.name == col.fieldName }) {
                                        "사건 시트의 필드 열 '${col.header}'이(가) 어느 세계관의 '${col.fieldName}' 필드인지 확정할 수 없어 값이 반영되지 않았습니다 — 열 머리를 '필드:${col.fieldName}(세계관명)' 꼴로 적어 주세요"
                                    } else {
                                        "사건 시트의 필드 열 '${col.header}'에 해당하는 사건 필드 정의를 찾을 수 없어 값이 반영되지 않았습니다 — '필드 정의' 시트(대상=사건)를 함께 가져오세요"
                                    }
                                )
                            }
                            continue
                        }
                        // 계산 필드는 수식으로 산출되는 파생값 — 캐릭터 시트(F4)와 대칭으로 저장하지 않는다.
                        // resolvedFieldIds에도 넣지 않아 기존 행을 건드리지 않는다(잔여 행 정리는
                        // 편집 저장의 커버 규칙이 맡는다 — EventFieldValueMerge).
                        if (fieldDef.fieldType == FieldType.CALCULATED) {
                            if (getCellString(row, ci).isNotBlank() && droppedEntityFieldHeaders.add(col.header)) {
                                result.warnings.add(
                                    "사건 시트의 '${col.header}' 열은 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨)"
                                )
                            }
                            continue
                        }
                        resolvedFieldIds.add(fieldDef.id)
                        // 이 (사건, 필드)는 연표 시트가 권위 — '사건 필드값' 시트의 같은 항목은 무시된다
                        importedEventFieldPairs.add(eventId to fieldDef.id)
                        val cellValue = getCellString(row, ci)
                        if (cellValue.isNotBlank()) {
                            newValues.add(com.novelcharacter.app.data.model.EventFieldValue(
                                eventId = eventId, fieldDefinitionId = fieldDef.id, value = cellValue
                            ))
                        }
                    }
                    // 해석된 열만 교체 — 시트에 없던 필드의 기존 값은 그대로 살아남는다(F1-A).
                    // 열이 있고 셀이 빈칸이면 resolvedFieldIds에 포함되므로 비움 의도는 존중된다.
                    db.eventFieldValueDao().replaceForFields(eventId, resolvedFieldIds, newValues)
                }

                // 관련 캐릭터 해석 — **코드 우선**(동명이인 오결합 방지, P1-I), 코드 없는 항목은 이름 매칭.
                // 코드로 이미 잡힌 캐릭터와 동명인 이름 항목은 중복 추가하지 않는다(코드가 권위).
                val charCodeStr = getCellCode(row, charCodeColIndex, "연표 행 $i", result)
                val characterNames = getCellString(row, charColIndex)
                if (charCodeStr.isNotBlank() || characterNames.isNotBlank()) {
                    val resolved = LinkedHashMap<Long, com.novelcharacter.app.data.model.Character>()
                    if (charCodeStr.isNotBlank()) {
                        for (code in splitCsv(charCodeStr)) {
                            characterByCode(code)?.let { resolved[it.id] = it }
                        }
                    }
                    if (characterNames.isNotBlank()) {
                        val coveredNames = resolved.values.mapTo(HashSet()) { it.name }
                        for (charName in splitCsv(characterNames)) {
                            if (charName in coveredNames) continue  // 코드로 이미 해석된 동명 항목
                            // F3-B: 동명이인 안전 — LIMIT 1로 아무나 고르지 않고, 모호하면 경고 후 스킵
                            when (val r = resolveCharByNameNovel(charName, novelIds.firstOrNull())) {
                                is CharLookupResult.Found -> resolved[r.character.id] = r.character
                                is CharLookupResult.Ambiguous -> result.warnings.add("사건 행 $i: 연결 캐릭터 '$charName' 동명이인 ${r.count}명 — 관련캐릭터코드 열로 지정하세요")
                                CharLookupResult.NotFound -> result.warnings.add("사건 행 $i: 연결 캐릭터 '${charName}'을(를) 찾을 수 없음")
                            }
                        }
                    }
                    val resolvedCharacters = resolved.values.toList()
                    if (resolvedCharacters.isNotEmpty()) {
                        db.timelineDao().deleteCrossRefsByEvent(eventId)
                        for (character in resolvedCharacters) {
                            db.timelineDao().insertCrossRef(
                                TimelineCharacterCrossRef(eventId = eventId, characterId = character.id)
                            )
                        }
                        eventsWithParticipants.add(eventId)
                        // birth/death 사건이면 관련 캐릭터의 상태변화 동기화 대상에 추가
                        if (r.eventType == TimelineEvent.TYPE_BIRTH || r.eventType == TimelineEvent.TYPE_DEATH) {
                            val stateKey = if (r.eventType == TimelineEvent.TYPE_BIRTH) CharacterStateChange.KEY_BIRTH else CharacterStateChange.KEY_DEATH
                            for (character in resolvedCharacters) {
                                val existing = db.characterStateChangeDao()
                                    .getChangeByNaturalKey(character.id, year, stateKey, year.toString())
                                if (existing == null) {
                                    db.characterStateChangeDao().insert(CharacterStateChange(
                                        characterId = character.id, year = year, month = r.month, day = r.day,
                                        fieldKey = stateKey, newValue = year.toString()
                                    ))
                                }
                                // 작품→세계관은 이미 메모된 helper가 있다 — 참가자마다 작품을
                                // 다시 읽던 자리다(B-210. 같은 작품을 든 참가자가 되풀이된다).
                                val uId = eventUniverseId ?: universeIdOfCharacter(character)
                                if (uId != null) {
                                    pendingSyncCharacters[character.id] = uId
                                }
                            }
                        }
                    }
                } else if (participantColumnPresent && existingEvent != null) {
                    // F1-A 규칙 가: 참가자 열이 있으나 셀이 비어 있음 → 기존 참가자 연결 삭제 (요약 집계)
                    if (eventsWithParticipants.contains(eventId)) {
                        db.timelineDao().deleteCrossRefsByEvent(eventId)
                        eventsWithParticipants.remove(eventId)
                        result.clearedFields++
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("연표 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "사건 연표", sheet.lastRowNum, totalRows)
    }

    // ── 상태변화 가져오기 ──

    private suspend fun importStateChanges(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = stateChangeSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        // 필수 컬럼: 위치 폴백을 쓰면 컬럼 삭제 시 이웃 컬럼 데이터가 그대로 기록되므로 검증 후 스킵
        val yearColIndex = requiredCol(cols, "연도", sheet.sheetName, result) ?: return
        val fieldKeyColIndex = requiredCol(cols, "필드키", sheet.sheetName, result) ?: return
        val newValueColIndex = requiredCol(cols, "새 값", sheet.sheetName, result) ?: return
        val scc = StateChangeCols(cols, yearColIndex, fieldKeyColIndex, newValueColIndex)
        val nowMillis = System.currentTimeMillis()

        val allNovels = db.novelDao().getAllNovelsList()
        // 상태변화 정체성 색인 (B-210) — 행마다 코드·자연키로 표를 묻던 자리. id 오름차순이
        // `LIMIT 1`의 순서다(`getAllChangesList()`는 캐릭터·연월일 순으로 나오므로 다시 정렬한다).
        // **연표가 만든 birth/death 행도 여기 실린다** — 그 시트가 먼저 돌고 이 색인은 지금 읽는다.
        val changesById = db.characterStateChangeDao().getAllChangesList().sortedBy { it.id }
        val changeCodes = ImportLookupIndex<String, CharacterStateChange>(
            idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
        )
        val changeNaturalKeys = ImportLookupIndex<StateChangeNaturalKey, CharacterStateChange>(
            idOf = { it.id },
            keyOf = { StateChangeNaturalKey(it.characterId, it.year, it.fieldKey, it.newValue) }
        )
        changeCodes.load(changesById)
        changeNaturalKeys.load(changesById)
        val changeCodesSeen = mutableSetOf<String>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readStateChangeRow(row, scc, "상태변화 행 $i", nowMillis, result)
                val charName = r.charName
                if (charName.isBlank()) continue

                val year = r.year
                if (year == null) {
                    result.skippedRows++
                    result.errors.add("상태변화 행 $i: 연도 '${r.yearRaw}'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    continue
                }

                val fieldKey = r.fieldKey
                if (fieldKey.isBlank()) continue
                val newValue = r.newValue
                if (newValue.isBlank()) {
                    result.skippedRows++
                    result.warnings.add("상태변화 행 $i: 빈 값은 허용되지 않습니다")
                    continue
                }
                val charCode = r.charCode

                // Resolve character: code-first, then strict name lookup (동명이인 모호성 감지)
                val character: Character = when {
                    charCode.isNotBlank() -> {
                        val found = characterByCode(charCode)
                        if (found == null) {
                            result.skippedRows++
                            result.errors.add("상태변화 행 $i: 코드 '${charCode}'에 해당하는 캐릭터를 찾을 수 없음")
                            continue
                        }
                        found
                    }
                    // 시트의 '작품' 열을 동명이인 해소 힌트로 쓴다 — 내보내면서 실어놓고 쓰지 않으면
                    // 코드 열이 없는 구버전 파일에서 해소 가능한 행이 불필요하게 거부된다.
                    else -> {
                        val hintNovelId = if (r.novelTitle.isBlank()) null else allNovels.find { it.title == r.novelTitle }?.id
                        when (val resolved = resolveCharByNameNovel(charName, hintNovelId)) {
                            is CharLookupResult.Found -> resolved.character
                            is CharLookupResult.Ambiguous -> {
                                result.skippedRows++
                                result.errors.add("상태변화 행 $i: '${charName}' 이름의 캐릭터가 ${resolved.count}명 존재합니다. '작품' 열이나 캐릭터코드 열로 구분하세요.")
                                continue
                            }
                            is CharLookupResult.NotFound -> {
                                result.skippedRows++
                                result.errors.add("상태변화 행 $i: 캐릭터 '${charName}'을(를) 찾을 수 없음")
                                continue
                            }
                        }
                    }
                }

                val fileCode = r.fileCode
                if (fileCode.isNotBlank() && !changeCodesSeen.add(fileCode)) {
                    result.warnings.add("상태변화 행 $i: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }
                // 매칭: 코드 우선(연도·필드키·값 편집을 같은 이력으로 인식) → 자연키 폴백(구버전 파일 호환)
                val existing = (if (fileCode.isNotBlank()) changeCodes.first(fileCode) else null)
                    ?: changeNaturalKeys.first(StateChangeNaturalKey(character.id, year, fieldKey, newValue))

                if (existing != null) {
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedStateChange = mergeStateChange(existing, r, character.id, generateEntityCode())
                    db.characterStateChangeDao().update(mergedStateChange)
                    // 자연키의 칸(연도·필드키·새 값)이 바뀌었을 수 있다 — 옛 키를 끊지 않으면
                    // 뒤 행이 **이미 다른 이력이 된 행**을 옛 키로 다시 잡는다(B-210).
                    changeCodes.put(mergedStateChange)
                    changeNaturalKeys.put(mergedStateChange)
                    matchedStateChangeIds.add(existing.id)
                    if (mergedStateChange != existing) result.updatedStateChanges++ else result.unchangedRows++
                } else {
                    val newChange = CharacterStateChange(
                        characterId = character.id, year = year, month = r.month, day = r.day,
                        fieldKey = fieldKey, newValue = newValue, description = r.description,
                        createdAt = r.createdAt ?: nowMillis,
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    )
                    val newId = db.characterStateChangeDao().insert(newChange)
                    changeCodes.put(newChange.copy(id = newId))
                    changeNaturalKeys.put(newChange.copy(id = newId))
                    matchedStateChangeIds.add(newId)
                    result.newStateChanges++
                }

                // __death/__birth 상태변화 임포트 시 필드 동기화 대상에 추가
                if (fieldKey == CharacterStateChange.KEY_DEATH || fieldKey == CharacterStateChange.KEY_BIRTH) {
                    // 같은 캐릭터·같은 작품이 여러 행에 되풀이되므로 메모된 helper로 답한다(B-210).
                    val uId = universeIdOfCharacter(character)
                    if (uId != null) {
                        pendingSyncCharacters[character.id] = uId
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("상태변화 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "상태변화", sheet.lastRowNum, totalRows)
    }

    // ── 캐릭터 관계 가져오기 ──

    private suspend fun importRelationships(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = relationshipSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        // 필수 컬럼: 위치 폴백으로 이웃 컬럼을 오독하지 않도록 검증 후 스킵
        val char2NameColIndex = requiredCol(cols, "캐릭터2", sheet.sheetName, result) ?: return
        val typeColIndex = requiredCol(cols, "관계 유형", sheet.sheetName, result) ?: return
        val rc = RelationshipCols(cols, char2NameColIndex, typeColIndex)
        val nowMillis = System.currentTimeMillis()

        // 관계 정체성 색인 (B-210) — 종전에는 **행마다** 캐릭터1의 관계 전부를 다시 읽고
        // (`getRelationshipsForCharacterList`) 코드로 한 번 더 물었다. 목표 규모에서 관계 4,050건이라
        // 그 둘만으로 8,000회가 넘는다. 표를 한 번 읽어 **쌍**과 **코드**로 답한다.
        //
        // **싣는 순서가 SQL의 `ORDER BY displayOrder ASC, createdAt DESC`와 같아야 한다** —
        // 호출부가 그 목록에서 `find { 유형이 같다 }`로 하나를 고르므로, 같은 쌍에 같은 유형이
        // 둘인 파일에서 **고르는 상대가 바뀐다.**
        val relsOrdered = db.characterRelationshipDao().getAllRelationships()
            .sortedWith(compareBy<CharacterRelationship> { it.displayOrder }.thenByDescending { it.createdAt })
        val relsByPair = ImportLookupIndex<CharacterPairKey, CharacterRelationship>(
            idOf = { it.id }, keyOf = { CharacterPairKey.of(it.characterId1, it.characterId2) }
        )
        val relCodes = ImportLookupIndex<String, CharacterRelationship>(
            idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
        )
        relsByPair.load(relsOrdered)
        relCodes.load(relsOrdered)
        fun rememberRelationship(rel: CharacterRelationship) {
            relsByPair.put(rel)
            relCodes.put(rel)
        }

        // 세력 참조 해석은 FactionIndex(단일 소스)로 — 전 세계관 first-match 금지
        val factionRefUsed = rc.faction >= 0 || rc.factionCode >= 0
        val factionIndex = FactionIndex(if (factionRefUsed) db.factionDao().getAllFactionsList() else emptyList())
        val universeNames = if (factionRefUsed) db.universeDao().getAllUniversesList().associate { it.id to it.name } else emptyMap()
        // 수동 관계 → 세력 자동 관계로 승격된 행. 루프 종료 후 1회 고지한다(행마다 경고하면 잡음).
        val factionAttachedRows = mutableListOf<Int>()
        val entitySeen = mutableMapOf<Long, Int>()
        // 코드 열이 없는 구버전 파일에서만: 새 관계를 만든 쌍 → (행번호, 표시명). 루프 종료 후 잔여 관계를 1회 집계한다.
        val touchedPairs = mutableMapOf<Set<Long>, Pair<Int, String>>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val rv = readRelationshipRow(row, rc, "관계 행 $i", nowMillis, result)
                val char1Name = rv.char1Name
                val char2Name = rv.char2Name
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val relationshipType = rv.relationshipType
                if (relationshipType.isBlank()) {
                    // F1-B: 두 캐릭터가 채워졌는데 관계 유형만 비어 있으면 조용히 버리지 않고 경고
                    result.skippedRows++
                    result.warnings.add("관계 행 $i: '$char1Name'–'$char2Name' 관계 유형이 비어 있어 건너뜀 (필수 항목)")
                    continue
                }
                val description = rv.description
                val char1Code = rv.char1Code
                val char2Code = rv.char2Code
                val char1 = when (val r = findCharacterStrict(char1Name, char1Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계 행 $i: '${char1Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터1코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계 행 $i: 캐릭터1 '${char1Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                val char2 = when (val r = findCharacterStrict(char2Name, char2Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계 행 $i: '${char2Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터2코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계 행 $i: 캐릭터2 '${char2Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                if (char1.id == char2.id) {
                    result.skippedRows++
                    result.errors.add("관계 행 $i: 자기 자신과의 관계는 허용되지 않습니다")
                    continue
                }

                // 세력(자동 관계) 참조 해석 — 참조 열 쌍 규약(refColumnIntent):
                //  · 유무는 편집 가능한 '세력' 열이 결정(열 있음 + 빈칸 = 해제, 회색 '세력코드' 셀은 읽지 않음)
                //  · 대상은 코드 우선 → 이름 폴백. 동명 세력은 캐릭터의 세계관으로 좁힌다.
                // ※ 캐릭터 해석 뒤에 둔다 — 타이브레이커에 캐릭터의 세계관이 필요하다.
                val factionName = rv.factionName
                val factionCode = rv.factionCode
                var factionIntent = rv.factionIntent
                var resolvedFaction: Faction? = null
                if (factionIntent == RefIntent.LOOKUP) {
                    val hintUniverseId = universeIdOfCharacter(char1) ?: universeIdOfCharacter(char2)
                    val unresolvedTail = " — 기존 관계는 세력 연결 유지, 새 관계는 수동 관계로 생성"
                    when (val fr = factionIndex.resolve(factionName, factionCode, hintUniverseId)) {
                        is FactionLookupResult.Found -> {
                            warnFactionCodeFallback("관계 행 $i", factionCode, fr, factionName, result)
                            warnFactionUniverseMismatch("관계 행 $i", fr.faction, hintUniverseId, "캐릭터 '${char1.name}'", universeNames, result)
                            // 이름과 코드가 서로 다른 세력을 가리키면 코드를 따르고 교정 경로를 안내한다
                            if (fr.matchedByCode && factionName.isNotBlank() && fr.faction.name != factionName) {
                                result.warnings.add("관계 행 $i: '세력'('$factionName')과 '세력코드'가 가리키는 세력('${fr.faction.name}')이 달라 코드를 따랐습니다 — 다른 세력으로 바꾸려면 '세력코드' 칸도 함께 비우세요")
                            }
                            resolvedFaction = fr.faction
                        }
                        is FactionLookupResult.Ambiguous -> {
                            // 모호를 '찾을 수 없음'으로 보고하면 사실과 다른 경고가 된다 — 사유를 정확히 밝힌다
                            result.warnings.add(factionAmbiguityMessage("관계 행 $i", factionName, fr, "세력코드", universeNames::get) + unresolvedTail)
                            factionIntent = RefIntent.KEEP
                        }
                        FactionLookupResult.NotFound -> {
                            result.warnings.add("관계 행 $i: 세력 '${factionName.ifBlank { factionCode }}'을(를) 찾을 수 없음$unresolvedTail ('세력' 시트를 함께 가져오세요)")
                            factionIntent = RefIntent.KEEP
                        }
                    }
                }
                val factionId = resolvedFaction?.id

                // 쌍으로 바로 받는다 — 종전에는 캐릭터1의 관계 **전부**를 읽어 여기서 걸렀다.
                val pairRels = relsByPair.all(CharacterPairKey.of(char1.id, char2.id))
                // 매칭 규약: 코드(안정 식별자) 우선 → 자연키(쌍+유형) 폴백.
                // 코드로 잡히면 '관계 유형' 편집이 rename으로 인식되어 관계가 분열하지 않는다.
                val relCode = rv.relCode
                val byCode = if (relCode.isNotBlank()) relCodes.first(relCode) else null
                if (relCode.isNotBlank() && byCode == null) {
                    result.warnings.add("관계 행 $i: 코드 '$relCode'를 찾지 못해 캐릭터·유형으로 매칭합니다 — 의도한 새 관계면 코드를 비우세요")
                }
                val existing = byCode ?: pairRels.find { it.relationshipType == relationshipType }
                if (byCode != null && byCode.relationshipType != relationshipType) {
                    result.warnings.add("관계 행 $i: '${char1Name}'–'${char2Name}' 관계 유형을 '${byCode.relationshipType}' → '$relationshipType'(으)로 변경했습니다 (코드로 같은 관계 인식)")
                }

                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("관계 행 $i: 행 $prevRow 과 같은 항목을 다시 덮어씀 — 별개의 관계로 넣으려면 '코드' 칸을 비우고 '관계 유형'을 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    // 빈칸=삭제 집계(변수 제어): 열이 있고 값이 비었는데 기존값이 있으면 초기화로 계수(세력 패턴과 일치)
                    if (rv.hasDescCol && description == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 세력 해석이 모호·미발견으로 KEEP으로 내려앉았을 수 있으므로 갱신된 의도로 판정한다.
                    val effectiveFactionId = effectiveRelationshipFactionId(existing, rv.copy(factionIntent = factionIntent), factionId)
                    if (existing.factionId != null && effectiveFactionId == null) result.clearedFields++
                    if (existing.factionId == null && effectiveFactionId != null) factionAttachedRows.add(i)
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedRelationship =
                        mergeRelationship(existing, rv, effectiveFactionId, generateEntityCode())
                    db.characterRelationshipDao().update(mergedRelationship)
                    rememberRelationship(mergedRelationship)
                    matchedRelationshipIds.add(existing.id)
                    if (mergedRelationship != existing) result.updatedRelationships++ else result.unchangedRows++
                } else {
                    // 잔여 관계 고지는 행마다 하지 않는다 — 같은 쌍의 다른 행이 아직 처리되지 않았을 뿐인데
                    // "정리하세요"라고 안내하면 사용자가 멀쩡한 데이터를 지운다. 루프 종료 후 1회 집계한다.
                    if (rc.relCode < 0) touchedPairs.putIfAbsent(setOf(char1.id, char2.id), i to "${char1Name}–${char2Name}")
                    val newRelationship = CharacterRelationship(
                        characterId1 = char1.id, characterId2 = char2.id,
                        relationshipType = relationshipType, description = description,
                        intensity = rv.intensity, isBidirectional = rv.isBidirectional,
                        displayOrder = rv.displayOrder ?: i, factionId = factionId,
                        createdAt = rv.createdAt ?: nowMillis,
                        // 파일의 코드를 보존해 기기 이전 후에도 왕복 정체성 유지 (없으면 자동 생성)
                        code = relCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    )
                    val newId = db.characterRelationshipDao().insert(newRelationship)
                    // 같은 쌍·같은 코드를 든 뒷 행이 이것을 봐야 한다 — 못 보면 같은 관계가
                    // 한 파일에서 둘로 갈린다(위 '다시 덮어씀' 고지가 그때 안 뜬다).
                    rememberRelationship(newRelationship.copy(id = newId))
                    matchedRelationshipIds.add(newId)
                    entitySeen[newId] = i
                    result.newRelationships++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("관계 행 $i: ${e.message}")
            }
        }
        // 구버전 파일(코드 열 없음): 시트가 기술하지 않은 기존 관계가 남았는지 쌍 단위로 1회 고지한다.
        // 행 순서와 무관하게 판정되므로 "같은 쌍의 다른 행"을 잔여로 오인하지 않는다.
        for ((pair, info) in touchedPairs) {
            val (rowNo, label) = info
            val anyId = pair.firstOrNull() ?: continue
            val leftovers = db.characterRelationshipDao().getRelationshipsForCharacterList(anyId)
                .filter { setOf(it.characterId1, it.characterId2) == pair && it.id !in matchedRelationshipIds }
            if (leftovers.isNotEmpty()) {
                result.warnings.add(
                    "관계(행 $rowNo 부근): '$label'에 시트에 없는 기존 관계(${leftovers.joinToString("/") { it.relationshipType }})가 남아 있습니다 — 관계 유형을 고쳐 쓴 것이라면 앱에서 정리하세요"
                )
            }
        }
        // 세력 연결 부여는 관계의 수명을 바꾼다(세력 삭제·멤버 탈퇴 시 함께 삭제) — 1회 집계해 알린다.
        // 무편집 왕복에서는 factionId가 이미 같아 계수되지 않으므로 거짓 경고가 나가지 않는다.
        if (factionAttachedRows.isNotEmpty()) {
            val sample = factionAttachedRows.take(5).joinToString(", ")
            val more = if (factionAttachedRows.size > 5) " 외" else ""
            result.warnings.add(
                "관계 ${factionAttachedRows.size}건에 '세력' 열로 세력 연결을 부여했습니다(행 $sample$more) — " +
                "세력 연결이 있는 관계는 자동 관계로 취급되어 그 세력을 삭제하거나 멤버가 탈퇴할 때 함께 삭제될 수 있습니다. " +
                "수동 관계로 되돌리려면 '세력' 칸을 비우세요"
            )
        }
        reportProgress(onProgress, "관계", sheet.lastRowNum, totalRows)
    }

    // ── 관계 변화 가져오기 ──

    private suspend fun importRelationshipChanges(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val sheet = findSheet(workbook, relationshipChangeSpec(), result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, "캐릭터1", result)) return

        reportUnknownColumns(headerRow, relationshipChangeSpec(), result)
        val cols = resolveHeaderColumns(headerRow)
        // 필수 컬럼: 위치 폴백으로 이웃 컬럼을 오독하지 않도록 검증 후 스킵
        val char2NameColIndex = requiredCol(cols, "캐릭터2", sheet.sheetName, result) ?: return
        val yearColIndex = requiredCol(cols, "연도", sheet.sheetName, result) ?: return
        val rcc = RelChangeCols(cols, char2NameColIndex, yearColIndex)
        val nowMillis = System.currentTimeMillis()
        val changeCodesSeen = mutableSetOf<String>()

        // 이 시트가 행마다 물던 표는 둘이다 (B-210):
        // ① **관계** — 부모 관계를 쌍으로 찾고 코드로도 찾는다(이 시트는 관계를 쓰지 않으므로 읽기만)
        // ② **관계 변화** — 코드·자연키로 자기 자신을 찾는다(갱신은 아래 update·insert가 맡는다)
        // 싣는 순서는 각 질의의 `ORDER BY`를 그대로 따른다 — 호출부가 목록에서 하나를 고르므로
        // 순서가 곧 **어느 관계에 이력이 붙는가**다.
        val parentRelsByPair = ImportLookupIndex<CharacterPairKey, CharacterRelationship>(
            idOf = { it.id }, keyOf = { CharacterPairKey.of(it.characterId1, it.characterId2) }
        )
        val parentRelCodes = ImportLookupIndex<String, CharacterRelationship>(
            idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
        )
        db.characterRelationshipDao().getAllRelationships()
            .sortedWith(compareBy<CharacterRelationship> { it.displayOrder }.thenByDescending { it.createdAt })
            .forEach { parentRelsByPair.put(it); parentRelCodes.put(it) }
        val relChangesById = db.characterRelationshipChangeDao().getAllChanges().sortedBy { it.id }
        val relChangeCodes = ImportLookupIndex<String, CharacterRelationshipChange>(
            idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
        )
        val relChangeNaturalKeys = ImportLookupIndex<RelChangeNaturalKey, CharacterRelationshipChange>(
            idOf = { it.id },
            keyOf = { RelChangeNaturalKey(it.relationshipId, it.year, it.month, it.day) }
        )
        relChangeCodes.load(relChangesById)
        relChangeNaturalKeys.load(relChangesById)

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val rv = readRelChangeRow(row, rcc, "관계변화 행 $i", nowMillis, result)
                val char1Name = rv.char1Name
                val char2Name = rv.char2Name
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val year = rv.year
                if (year == null) {
                    result.skippedRows++
                    result.errors.add("관계 변화 행 $i: 연도 '${rv.yearRaw}'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    continue
                }
                val description = rv.description
                val eventId = resolveRelChangeEventId(rv, "관계변화 행 $i", result)
                val char1Code = rv.char1Code
                val char2Code = rv.char2Code

                val char1 = when (val r = findCharacterStrict(char1Name, char1Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 $i: '${char1Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터1코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 $i: 캐릭터1 '${char1Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }
                val char2 = when (val r = findCharacterStrict(char2Name, char2Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 $i: '${char2Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터2코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 $i: 캐릭터2 '${char2Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                // 부모 관계 해석: 부모관계유형 열 우선 → 쌍 후보가 유일할 때만 폴백.
                // 같은 쌍에 유형이 다른 관계가 여러 개일 수 있으므로(유니크 키가 쌍+유형),
                // 근거 없이 first-match로 고르면 이력이 엉뚱한 관계에 붙는다.
                val pairRelationships = parentRelsByPair.all(CharacterPairKey.of(char1.id, char2.id))
                if (pairRelationships.isEmpty()) {
                    result.skippedRows++
                    result.errors.add("관계변화 행 $i: '${char1Name}'과(와) '${char2Name}' 간의 관계를 찾을 수 없음")
                    continue
                }
                // 부모 관계 해석도 미리보기와 **같은 함수**다(규약 R-33).
                val relationship = resolveRelChangeParent(rv, pairRelationships, char1.id, char2.id, "관계변화 행 $i", result)
                if (relationship == null) {
                    result.skippedRows++
                    result.errors.add(
                        "관계변화 행 $i: '${char1Name}'–'${char2Name}' 사이에 관계가 ${pairRelationships.size}개 있어 어느 관계의 이력인지 확정할 수 없습니다 — '부모관계유형' 열에 대상 관계의 유형(${pairRelationships.joinToString("/") { it.relationshipType }})을 적으세요"
                    )
                    continue
                }

                val fileCode = rv.fileCode
                if (fileCode.isNotBlank() && !changeCodesSeen.add(fileCode)) {
                    result.warnings.add("관계변화 행 $i: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }
                // 매칭: 코드 우선(연월일 편집을 같은 이력으로 인식) → 자연키 폴백(구버전 파일 호환)
                val existing = (if (fileCode.isNotBlank()) relChangeCodes.first(fileCode) else null)
                    ?: relChangeNaturalKeys.first(RelChangeNaturalKey(relationship.id, year, rv.month, rv.day))
                if (existing != null) {
                    // 빈칸=삭제 집계(변수 제어): 열이 있고 값이 비었는데 기존값이 있으면 초기화로 계수
                    if (rv.hasDescCol && description == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedRelChange =
                        mergeRelationshipChange(existing, rv, relationship.id, eventId, generateEntityCode())
                    db.characterRelationshipChangeDao().update(mergedRelChange)
                    // 자연키의 칸(부모 관계·연월일)이 바뀌었을 수 있다 — 옛 키를 끊어야
                    // 뒤 행이 **이미 옮겨 간 이력**을 옛 키로 다시 잡지 않는다(B-210).
                    relChangeCodes.put(mergedRelChange)
                    relChangeNaturalKeys.put(mergedRelChange)
                    matchedRelationshipChangeIds.add(existing.id)
                    if (mergedRelChange != existing) result.updatedRelationshipChanges++ else result.unchangedRows++
                } else {
                    val newRelChange = CharacterRelationshipChange(
                        relationshipId = relationship.id,
                        year = year, month = rv.month, day = rv.day,
                        relationshipType = rv.relationshipType, description = description,
                        intensity = rv.intensity, isBidirectional = rv.isBidirectional,
                        eventId = eventId, createdAt = rv.createdAt ?: nowMillis,
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    )
                    val newId = db.characterRelationshipChangeDao().insert(newRelChange)
                    relChangeCodes.put(newRelChange.copy(id = newId))
                    relChangeNaturalKeys.put(newRelChange.copy(id = newId))
                    matchedRelationshipChangeIds.add(newId)
                    result.newRelationshipChanges++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("관계 변화 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "관계 변화", sheet.lastRowNum, totalRows)
    }

    // ── 이름 은행 가져오기 ──

    private suspend fun importNameBank(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = nameBankSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val nbc = NameBankCols(resolveHeaderColumns(headerRow))
        val nowMillis = System.currentTimeMillis()

        // 자연키 맵은 이미 있었다 — **코드 축만 빠져 있어 행마다 표를 다시 물었다**(B-210).
        // 같은 목록에서 함께 짓는다. id 오름차순이 `LIMIT 1`의 순서다.
        val allNames = db.nameBankDao().getAllNamesList()
        val existingNamesMap = allNames.associateBy { it.mapKeyForNameBank() }.toMutableMap()
        val nameBankCodes = ImportLookupIndex<String, NameBankEntry>(
            idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
        )
        nameBankCodes.load(allNames.sortedBy { it.id })

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readNameBankRow(row, nbc, "이름 은행 행 $i", nowMillis, result)
                val name = r.name
                if (name.isBlank()) continue

                // F3-D: 코드 우선 매칭(이름/성별을 편집해도 같은 항목 인식) → 자연키(이름+성별) 폴백
                val existing = (if (r.code.isNotBlank()) nameBankCodes.first(r.code) else null)
                    ?: existingNamesMap[r.mapKey]

                val usedByCharacterId = resolveNameBankUsedBy(r, existing, "이름 은행 행 $i", result)
                val effectiveUsed = r.usedFlag ?: existing?.isUsed ?: false
                // 참조를 조회했는데 해석에 실패하면 연결이 조용히 끊긴다 —
                // 사용 표시는 보존하고 연결만 비운 뒤 고지한다(무음 상태 변경 금지).
                if (effectiveUsed && r.usedIntent == RefIntent.LOOKUP && usedByCharacterId == null) {
                    result.warnings.add(
                        "이름 은행 행 $i: 사용 캐릭터 '${r.usedByCharName.ifBlank { r.usedByCharCode }}'을(를) 찾을 수 없어 " +
                        "연결 없이 '사용 중'으로 남겨둡니다 — '사용캐릭터코드' 열로 지정하거나 '사용 캐릭터' 칸을 비워 해제하세요"
                    )
                }

                if (existing != null) {
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val merged = mergeNameBankEntry(existing, r, usedByCharacterId)
                    db.nameBankDao().update(merged)
                    existingNamesMap[merged.mapKeyForNameBank()] = merged
                    nameBankCodes.put(merged)
                    matchedNameBankIds.add(existing.id)
                    if (merged != existing) result.updatedNameBank++ else result.unchangedRows++
                } else {
                    // 파일의 코드를 보존해 백업/기기이전 후에도 왕복 정체성 유지 (없으면 자동 생성)
                    val newCode = if (r.code.isNotBlank()) r.code else generateEntityCode()
                    val newEntry = NameBankEntry(
                        name = name, gender = r.gender, origin = r.origin, notes = r.notes,
                        isUsed = r.usedFlag ?: false, usedByCharacterId = usedByCharacterId,
                        createdAt = r.createdAt ?: nowMillis, code = newCode
                    )
                    val newId = db.nameBankDao().insert(newEntry)
                    matchedNameBankIds.add(newId)
                    existingNamesMap[r.mapKey] = newEntry.copy(id = newId)
                    // 같은 코드를 든 뒷 행이 이것을 봐야 한다 — 자연키 맵은 이미 그렇게 하고 있었다.
                    nameBankCodes.put(newEntry.copy(id = newId))
                    result.newNameBank++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("이름 은행 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "이름 은행", sheet.lastRowNum, totalRows)
    }

    // ── 필드 템플릿 가져오기 ──

    private suspend fun importUserPresetTemplates(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = userPresetTemplateSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val ptc = PresetTemplateCols(resolveHeaderColumns(headerRow))
        val nowMillis = System.currentTimeMillis()

        // 동명 템플릿은 DB가 허용한다(name 유니크 인덱스 없음) — 이름 맵으로 접으면 편집이 유실된다.
        // 생성일을 안정 식별자로 쓰는 claim 기반 매칭기로 대체한다.
        // (검색·목록 프리셋은 name 유니크 인덱스가 정체성을 보장하므로 대상이 아니다)
        val matcher = PresetTemplateMatcher(
            db.userPresetTemplateDao().getAllTemplatesList()
                .map { PresetTemplateMatcher.Candidate(it.id, it.name, it.createdAt) }
        )

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readPresetTemplateRow(row, ptc, "필드 템플릿 행 $i", result)
                val name = r.name
                if (name.isBlank()) continue

                when (val match = matcher.claim(name, r.createdAt, i)) {
                    is PresetTemplateMatcher.Match.Matched -> {
                        match.warnings.forEach { result.warnings.add("필드 템플릿 행 $i: $it") }
                        if (match.nameBased) result.nameBasedMappings++
                        val existing = db.userPresetTemplateDao().getTemplateById(match.id)
                        if (existing == null) {
                            // 이론상 도달 불가(같은 트랜잭션 안) — 무음 스킵 금지 차원의 방어
                            result.skippedRows++
                            result.errors.add("필드 템플릿 행 $i: 템플릿(id=${match.id})을 다시 읽지 못해 건너뛰었습니다")
                        } else {
                            // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                            val mergedTemplate = mergePresetTemplate(existing, r, nowMillis)
                            db.userPresetTemplateDao().update(mergedTemplate)
                            if (mergedTemplate != existing) result.updatedPresetTemplates++ else result.unchangedRows++
                        }
                    }
                    is PresetTemplateMatcher.Match.New -> {
                        match.warnings.forEach { result.warnings.add("필드 템플릿 행 $i: $it") }
                        // 신규는 엔티티 기본값 (갱신=F1-A, 신규=기본값 분리 규약)
                        val newTemplate = UserPresetTemplate(
                            name = name,
                            description = r.description ?: "",
                            fieldsJson = r.fieldsJson ?: "[]",
                            isBuiltIn = r.isBuiltIn ?: false,
                            createdAt = r.createdAt ?: nowMillis,
                            updatedAt = r.updatedAt ?: nowMillis
                        )
                        val newId = db.userPresetTemplateDao().insert(newTemplate)
                        matcher.register(newId, newTemplate.name, newTemplate.createdAt, i)
                        result.newPresetTemplates++
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 템플릿 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "필드 템플릿", sheet.lastRowNum, totalRows)
    }

    // ── 검색 프리셋 가져오기 ──

    /**
     * 프리셋 필드 필터 해석 색인 (필터 대상은 캐릭터 필드).
     * 안정 식별자(세계관코드+필드키) → 세계관코드 별칭 → 필드명 자연키 순으로 해석한다.
     * 별칭 스냅샷을 넘겨, 이후 시트 처리가 맵을 바꿔도 진행 중인 해석이 흔들리지 않게 한다.
     */
    private suspend fun fieldFilterIndex(): PortableFieldFilters.Index {
        val universeById = db.universeDao().getAllUniversesList().associateBy { it.id }
        val fields = db.fieldDefinitionDao().getAllFieldsList().map { f ->
            val u = universeById[f.universeId]
            PortableFieldFilters.DeviceField(
                id = f.id,
                universeCode = u?.code ?: "",
                universeName = u?.name ?: "",
                key = f.key,
                name = f.name
            )
        }
        return PortableFieldFilters.Index(fields, universeCodeAliases.snapshot())
    }

    private suspend fun importSearchPresets(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = searchPresetSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val spc = SearchPresetCols(resolveHeaderColumns(headerRow))
        val nowMillis = System.currentTimeMillis()

        val existingPresets = db.searchPresetDao().getAllPresetsList()
        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readSearchPresetRow(row, spc, "검색 프리셋 행 $i", filterIndex, result)
                val name = r.name
                if (name.isBlank()) continue

                val existing = existingByName[name]
                if (existing != null) {
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedSearchPreset = mergeSearchPreset(existing, r, nowMillis)
                    db.searchPresetDao().update(mergedSearchPreset)
                    if (mergedSearchPreset != existing) result.updatedSearchPresets++ else result.unchangedRows++
                } else {
                    val newPreset = SearchPreset(
                        name = name,
                        query = r.query ?: "",
                        filtersJson = r.filtersJson ?: "{}",
                        sortMode = r.sortMode ?: SearchPreset.SORT_RELEVANCE,
                        isDefault = r.isDefault ?: false,
                        createdAt = r.createdAt ?: nowMillis,
                        updatedAt = r.updatedAt ?: nowMillis
                    )
                    val newId = db.searchPresetDao().insert(newPreset)
                    existingByName[name] = newPreset.copy(id = newId)
                    result.newSearchPresets++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("검색 프리셋 행 $i: ${e.message}")
            }
        }

        // 목록 프리셋 쪽에만 있던 초과 고지를 여기에도 세운다(B-75) — 같은 권고 한도인데
        // **한쪽만 말하고 있었다.** 인앱 저장이 하드 차단이던 시절에는 검색 프리셋이 20개를
        // 넘는 길이 파일뿐이었고, 그 유일한 길에 고지가 없었다.
        val totalAfter = db.searchPresetDao().getPresetCount()
        if (PresetLimit.exceeded(totalAfter)) {
            result.warnings.add("검색 프리셋이 ${totalAfter}개로 인앱 권장 한도(${PresetLimit.RECOMMENDED_MAX}개)를 초과했습니다 — 검색 화면에서 정리할 수 있습니다")
        }
        reportProgress(onProgress, "검색 프리셋", sheet.lastRowNum, totalRows)
    }

    // ── 캐릭터 목록 프리셋 가져오기 ──

    /**
     * 이름이 유니크 키. 작품 필터는 작품코드 콤마 목록으로 왕복하며(기기 간 이식성),
     * 해석 실패한 코드는 조용히 버리지 않고 경고한다. 열이 없으면 기존값 유지(F1-A).
     */
    private suspend fun importCharacterListPresets(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = characterListPresetSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val lpc = ListPresetCols(resolveHeaderColumns(headerRow))
        val nowMillis = System.currentTimeMillis()

        val existingByName = db.characterListPresetDao().getAllPresetsList()
            .associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readListPresetRow(row, lpc, "목록 프리셋 행 $i", filterIndex, result)
                val name = r.name
                if (name.isBlank()) continue

                val existing = existingByName[name]
                if (existing != null) {
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedListPreset = mergeListPreset(existing, r, nowMillis)
                    db.characterListPresetDao().update(mergedListPreset)
                    if (mergedListPreset != existing) result.updatedListPresets++ else result.unchangedRows++
                } else {
                    val newPreset = CharacterListPreset(
                        name = name,
                        tagsJson = r.tagsJson ?: "[]",
                        fieldFiltersJson = r.fieldFiltersJson ?: "{}",
                        sortKind = r.sortKind ?: CharacterListPreset.SORT_MANUAL,
                        sortFieldKey = r.sortFieldKey,
                        sortDuelAxisCode = r.sortDuelAxisCode,
                        sortAscending = r.sortAscending ?: true,
                        bodySizePartIndex = r.bodySizePartIndex,
                        novelIdsJson = r.novelIdsJson ?: "[]",
                        isDefault = r.isDefault ?: false,
                        createdAt = r.createdAt ?: nowMillis,
                        updatedAt = r.updatedAt ?: nowMillis
                    )
                    val newId = db.characterListPresetDao().insert(newPreset)
                    existingByName[name] = newPreset.copy(id = newId)
                    result.newListPresets++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("목록 프리셋 행 $i: ${e.message}")
            }
        }

        val totalAfter = db.characterListPresetDao().getPresetCount()
        if (PresetLimit.exceeded(totalAfter)) {
            result.warnings.add("목록 프리셋이 ${totalAfter}개로 인앱 권장 한도(${PresetLimit.RECOMMENDED_MAX}개)를 초과했습니다 — 캐릭터 탭에서 정리할 수 있습니다")
        }
        reportProgress(onProgress, "목록 프리셋", sheet.lastRowNum, totalRows)
    }

    // ── 앱 설정 가져오기 ──

    private suspend fun importAppSettings(workbook: Workbook, result: ImportResult) {
        val spec = appSettingsSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val keyColIndex = cols["설정키"] ?: 0
        val valueColIndex = cols["설정값"] ?: 1
        // 같은 키가 여러 행에 있어도 한 번만 센다(집합) — 행 수가 아니라 *무엇을* 모르는가가 사실이다.
        val unknownSettingKeys = linkedSetOf<String>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val key = getCellString(row, keyColIndex)
                if (key.isBlank()) continue
                val value = getCellString(row, valueColIndex)

                val ctx = appContext ?: continue
                // 무엇을 어떻게 쓰는가는 여기서 정하지 않는다 — [AppSettingsBindings]가 단일
                // 소스다(B-105). 종전에는 이 자리가 손수 짠 `when (key)`라 **내보내기의 나열과
                // 짝으로만 늘 수 있었고, 그래서 둘 다 늘지 않았다.**
                //
                // 저장소 setter가 대개 자체 클램프를 하므로 범위 밖 값도 안전하게 수용된다(관대 임포트).
                // 다만 **뜻을 알 수 없는 값은 조용히 넘기지 않는다** — 사용자가 적은 것이 왜
                // 안 먹었는지 알 길이 없으면 그것이 말없는 유실이다(개발 의도 2번).
                val binding = AppSettingsBindings.bindingOf(key)
                if (binding == null) {
                    // 알 수 없는 키는 세어서 한 줄로 알린다 — 상위 버전 파일을 하위 버전 앱에서
                    // 열면 정상적으로 생기는 일이라 행마다 경고할 것은 아니지만, 아무 말도 없으면
                    // 오타로 적은 키가 영영 안 먹는 채로 남는다.
                    unknownSettingKeys.add(key.trim())
                    continue
                }
                when (val applied = binding.write(ctx, value)) {
                    is AppSettingsBindings.Applied.Yes -> result.restoredSettings++
                    is AppSettingsBindings.Applied.No ->
                        result.warnings.add("앱 설정 행 $i: $key 값 '$value' — ${applied.reason}")
                }
            } catch (e: Exception) {
                result.errors.add("앱 설정 행 $i: ${e.message}")
            }
        }
        if (unknownSettingKeys.isNotEmpty()) {
            result.warnings.add(
                "앱 설정: 이 버전이 모르는 설정 ${unknownSettingKeys.size}개를 건너뛰었습니다" +
                    "(${unknownSettingKeys.take(8).joinToString(", ")}${if (unknownSettingKeys.size > 8) " 외" else ""}) — " +
                    "앱을 올리면 들어올 수 있고, 키를 잘못 적었다면 고쳐서 다시 가져오세요"
            )
        }
    }

    // ── 세력 가져오기 ──

    private suspend fun importFactions(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = factionSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val fc = FactionCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val nowMillis = System.currentTimeMillis()

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readFactionRow(row, fc, "세력 행 $i", nowMillis, result)
                val name = r.name
                if (name.isBlank()) continue

                val universeName = r.universeName
                val universeCode = r.universeCode
                val descriptionFromExcel: String? = r.description
                if (r.autoRelationType.isBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 행 $i: 자동관계유형이 비어 있음")
                    continue
                }
                val code = r.code

                // Resolve universe (코드 우선 매칭 — 작품 가져오기와 동일 패턴)
                val universeId: Long
                val resolvedUniverse = (if (universeCode.isNotBlank()) db.universeDao().getUniverseByCode(universeCode) else null)
                    ?: (if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName) else null)
                if (resolvedUniverse != null) {
                    universeId = resolvedUniverse.id
                } else if (universeName.isNotBlank() || universeCode.isNotBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 행 $i: 세계관 '$universeName'을(를) 찾을 수 없음")
                    continue
                } else {
                    result.skippedRows++
                    result.errors.add("세력 행 $i: 세계관이 지정되지 않음")
                    continue
                }

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("세력: 코드 '$code'가 행 $prevRow 과 행 $i 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Code-first matching + F1-C: 미지 코드 → 자연키 폴백 + 경고
                val existing: Faction?
                val matchedByName: Boolean
                if (code.isNotBlank()) {
                    val byCode = db.factionDao().getByCode(code)
                    if (byCode != null) {
                        existing = byCode
                        matchedByName = false
                    } else {
                        val byName = db.factionDao().getByNameAndUniverse(name, universeId)
                        if (byName != null) {
                            existing = byName
                            matchedByName = true
                            result.nameBasedMappings++
                            result.warnings.add("세력 행 $i: 코드 '$code'를 찾지 못해 이름 '$name'으로 매칭함 — 의도한 새 세력이면 코드를 비우세요")
                        } else {
                            existing = null
                            matchedByName = false
                            warnCreatedNewByCode("factions", "세력 행 $i: 코드 '$code'가 기존 세력에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                        }
                    }
                } else {
                    existing = db.factionDao().getByNameAndUniverse(name, universeId)
                    matchedByName = existing != null
                    if (matchedByName) {
                        result.nameBasedMappings++
                        result.warnings.add("세력 행 $i: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("세력 행 $i: 행 $prevRow 과 같은 항목('$name')을 다시 덮어씀 — 별개의 세력으로 넣으려면 '코드' 칸을 비우고 이름을 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    if (descriptionFromExcel == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedFaction = mergeFaction(existing, r, universeId)
                    db.factionDao().update(mergedFaction)
                    matchedFactionIds.add(existing.id)
                    if (mergedFaction != existing) result.updatedFactions++ else result.unchangedRows++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newId = db.factionDao().insert(Faction(
                        name = name,
                        universeId = universeId,
                        description = r.description ?: "",
                        color = r.color ?: "#2196F3",
                        autoRelationType = r.autoRelationType,
                        autoRelationIntensity = r.autoRelationIntensity,
                        code = newCode,
                        displayOrder = r.displayOrder ?: i,
                        createdAt = r.createdAt ?: nowMillis
                    ))
                    matchedFactionIds.add(newId)
                    entitySeen[newId] = i
                    result.newFactions++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세력 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "세력", sheet.lastRowNum, totalRows)
    }

    // ── 세력 소속 가져오기 ──

    /**
     * '탈퇴유형' 셀 해석 — 가져오기와 미리보기 분석이 **같은 함수**를 쓴다.
     * 종전에는 두 곳이 각자 `when`을 들고 있었고, 분석 쪽은 상수 대신 문자열 리터럴을 썼다.
     */
    private fun parseFactionLeaveType(raw: String): String? = when (raw.trim()) {
        "순수제거", "removed" -> FactionMembership.LEAVE_REMOVED
        "설정상탈퇴", "departed" -> FactionMembership.LEAVE_DEPARTED
        else -> null
    }

    /** 열 존재 여부 묶음 — 없는 열은 기존값을 유지한다(F1-A). 가져오기·분석 공용. */
    private fun factionMembershipPresence(
        joinYearColIndex: Int,
        leaveYearColIndex: Int,
        leaveTypeColIndex: Int,
        departedRelTypeColIndex: Int,
        departedIntensityColIndex: Int,
        createdAtColIndex: Int
    ) = FactionMembershipMatcher.ColumnPresence(
        joinYear = joinYearColIndex >= 0,
        leaveYear = leaveYearColIndex >= 0,
        leaveType = leaveTypeColIndex >= 0,
        departedRelationType = departedRelTypeColIndex >= 0,
        departedIntensity = departedIntensityColIndex >= 0,
        createdAt = createdAtColIndex >= 0
    )

    private fun factionRelationshipPresence(
        descColIndex: Int,
        intensityColIndex: Int,
        bidirectionalColIndex: Int,
        orderColIndex: Int
    ) = FactionRelationshipMatcher.ColumnPresence(
        description = descColIndex >= 0,
        intensity = intensityColIndex >= 0,
        isBidirectional = bidirectionalColIndex >= 0,
        displayOrder = orderColIndex >= 0
    )

    /**
     * '세력 관계' 한 행이 말하는 값 — **가져오기와 복원 미리보기가 같은 함수로 읽는다**(B-87).
     * 따로 읽으면 강도 보정·불리언 토큰 해석이 갈려 '동일'이 사실과 어긋난다.
     */
    private fun factionRelationshipRowValues(
        row: Row,
        descColIndex: Int,
        intensityColIndex: Int,
        bidirectionalColIndex: Int,
        orderColIndex: Int,
        rowLabel: String,
        result: ImportResult?
    ) = FactionRelationshipMatcher.RowValues(
        description = if (descColIndex >= 0) getCellString(row, descColIndex) else "",
        intensity = parseIntensityWithWarn(row, intensityColIndex, 5, rowLabel, result) ?: 5,
        // 다른 관계 시트와 동일하게 parseBoolean 사용(P2-10) — 예전 `!= "N"`은 FALSE/0/false 같은
        // falsey 값을 true로 뒤집었다. 열이 없으면 기본 양방향(true).
        isBidirectional = if (bidirectionalColIndex >= 0) parseBoolean(getCellString(row, bidirectionalColIndex)) else true,
        displayOrder = if (orderColIndex >= 0) parseNumber(getCellString(row, orderColIndex))?.toInt() ?: 0 else 0
    )

    private suspend fun importFactionMemberships(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = factionMembershipSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val factionNameColIndex = cols[spec.firstColumnHeader] ?: cols["세력"] ?: 0
        val charNameColIndex = cols["캐릭터"] ?: -1
        val joinYearColIndex = cols["가입연도"] ?: -1
        val leaveYearColIndex = cols["탈퇴연도"] ?: -1
        val leaveTypeColIndex = cols["탈퇴유형"] ?: -1
        val departedRelTypeColIndex = cols["탈퇴후관계유형"] ?: -1
        val departedIntensityColIndex = cols["탈퇴후강도"] ?: -1
        val factionCodeColIndex = cols["세력코드"] ?: -1
        val charCodeColIndex = cols["캐릭터코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        if (charNameColIndex < 0) {
            result.errors.add("세력 소속 시트: '캐릭터' 컬럼을 찾을 수 없음")
            return
        }

        // 행마다 전체 세력을 다시 읽지 않는다(대형 파일 대비) + 세력 참조 해석 단일 소스
        val factionIndex = FactionIndex(db.factionDao().getAllFactionsList())
        val universeNames = db.universeDao().getAllUniversesList().associate { it.id to it.name }

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val factionName = getCellString(row, factionNameColIndex)
                if (factionName.isBlank()) continue
                val charName = getCellString(row, charNameColIndex)
                if (charName.isBlank()) continue

                val factionCode = getCellCode(row, factionCodeColIndex, "세력 가입 행 $i", result)
                val charCode = getCellCode(row, charCodeColIndex, "세력 가입 행 $i", result)

                // Resolve character (동명이인 모호성 감지 포함)
                // ※ 세력보다 먼저 해석한다 — 동명 세력을 캐릭터의 세계관으로 좁혀야 하기 때문
                val character: com.novelcharacter.app.data.model.Character = when (val r = findCharacterStrict(charName, charCode)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("세력 소속 행 $i: '$charName' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 소속 행 $i: 캐릭터 '$charName'을(를) 찾을 수 없음")
                        continue
                    }
                }

                // Resolve faction — 코드 우선 → 이름. 동명 세력은 캐릭터의 세계관으로 좁힌다.
                // 전 세계관 first-match 는 B 캐릭터를 A 세력에 무경고로 소속시키고 그 소속으로
                // 자동 관계까지 만들어 오염을 번지게 한다.
                val hintUniverseId = universeIdOfCharacter(character)
                val faction: Faction = when (val fr = factionIndex.resolve(factionName, factionCode, hintUniverseId)) {
                    is FactionLookupResult.Found -> {
                        warnFactionCodeFallback("세력 소속 행 $i", factionCode, fr, factionName, result)
                        warnFactionUniverseMismatch("세력 소속 행 $i", fr.faction, hintUniverseId, "캐릭터 '${character.name}'", universeNames, result)
                        fr.faction
                    }
                    is FactionLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add(factionAmbiguityMessage("세력 소속 행 $i", factionName, fr, "세력코드", universeNames::get))
                        continue
                    }
                    FactionLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 소속 행 $i: 세력 '${factionName.ifBlank { factionCode }}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                val joinYear = if (joinYearColIndex >= 0) parseNumber(getCellString(row, joinYearColIndex))?.toInt() else null
                val leaveYear = if (leaveYearColIndex >= 0) parseNumber(getCellString(row, leaveYearColIndex))?.toInt() else null
                val leaveType = parseFactionLeaveType(if (leaveTypeColIndex >= 0) getCellString(row, leaveTypeColIndex) else "")
                val departedRelationType = if (departedRelTypeColIndex >= 0) getCellString(row, departedRelTypeColIndex).ifBlank { null } else null
                val departedIntensity = parseIntensityWithWarn(row, departedIntensityColIndex, null, "세력 소속 행 $i", result)
                // 열이 없거나 해석 불가면 null — 기존 이력의 생성일을 **유지**한다.
                // 종전에는 해석 불가일 때 현재 시각을 찍어, 매칭은 자연키로 되면서도 생성일만
                // 조용히 바뀌었다(왕복할 때마다 안정 식별자가 흔들리는 자리였다).
                val parsedCreatedAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() else null

                // 소속 매칭: 활성/탈퇴 구분 없는 단일 계층 규칙 (활성만 보던 기존 방식은 탈퇴 이력을
                // 영원히 매칭하지 못해 재가져오기마다 중복 행을 만들었다 — 왕복 비멱등).
                // 규칙 원문은 FactionMembershipMatcher — 미리보기 분석도 **같은 함수**를 쓴다
                // (규칙을 두 벌로 두었다가 분석만 옛 규칙에 남아 거짓 삭제 예고를 낸 적이 있다).
                // F1-A: 열이 없으면 기존값 유지 (탈퇴연도만 지운 시트가 예정 탈퇴를 무음 초기화하지 않게)
                val rowValues = FactionMembershipMatcher.RowValues(
                    joinYear = joinYear,
                    leaveYear = leaveYear,
                    leaveType = leaveType,
                    departedRelationType = departedRelationType,
                    departedIntensity = departedIntensity,
                    createdAt = parsedCreatedAt
                )
                val presence = factionMembershipPresence(
                    joinYearColIndex, leaveYearColIndex, leaveTypeColIndex,
                    departedRelTypeColIndex, departedIntensityColIndex, createdAtColIndex
                )
                val pairMemberships = db.factionMembershipDao()
                    .getAllMembershipsForPair(faction.id, character.id)
                    .filter { it.id !in matchedFactionMembershipIds }
                val existingMembership: FactionMembership? = FactionMembershipMatcher.match(pairMemberships, rowValues)

                if (existingMembership != null) {
                    val updated = FactionMembershipMatcher.apply(existingMembership, rowValues, presence)
                    if (updated.leaveType != existingMembership.leaveType) {
                        val before = existingMembership.leaveType ?: "활성"
                        val after = updated.leaveType ?: "활성"
                        result.warnings.add("세력 소속 행 $i: '${faction.name}'–'${character.name}' 소속 상태를 $before → $after (으)로 갱신했습니다")
                    }
                    db.factionMembershipDao().update(updated)
                    matchedFactionMembershipIds.add(existingMembership.id)
                    if (updated != existingMembership) result.updatedFactionMemberships++ else result.unchangedRows++
                } else {
                    // Insert new membership
                    val membershipId = db.factionMembershipDao().insert(FactionMembership(
                        factionId = faction.id,
                        characterId = character.id,
                        joinYear = joinYear,
                        leaveYear = leaveYear,
                        leaveType = leaveType,
                        departedRelationType = departedRelationType,
                        departedIntensity = departedIntensity,
                        createdAt = parsedCreatedAt ?: System.currentTimeMillis()
                    ))

                    // 활성 소속이면 세력 자동 관계 생성 대상 — 단, **여기서 만들지 않고 큐에 넣는다**.
                    // 세력을 관계 시트보다 먼저 가져오므로(세력코드 해석에 필요), 여기서 생성하면
                    // 백업의 관계 시트를 읽기도 전에 관계가 생겨 ① 수동 관계가 세력 관계로 오염되고
                    // ② 캐릭터1/2 순서가 (min,max)로 정규화돼 단방향 방향이 뒤집히며
                    // ③ 세력의 자동관계유형이 바뀐 경우 관계가 중복 증식한다.
                    // 관계 시트가 권위이므로 시트 처리가 끝난 뒤에 남은 쌍만 채운다.
                    if (FactionStanding.isCurrent(leaveType) && membershipId > 0) {
                        pendingAutoRelationMemberships.add(faction.id to character.id)
                    }

                    matchedFactionMembershipIds.add(membershipId)
                    result.newFactionMemberships++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세력 소속 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "세력 소속", sheet.lastRowNum, totalRows)
    }

    /**
     * 세력 자동 관계 생성 — 관계 시트 처리가 끝난 뒤에 호출한다.
     *
     * 백업 파일의 '캐릭터 관계' 시트가 관계 그래프의 권위다. 따라서 시트가 이미 기술한 쌍은
     * 유형이 무엇이든 건드리지 않고, 시트에 없는 co-member 쌍만 채운다. 이렇게 해야
     * 무편집 왕복이 멱등이고(자동관계유형이 바뀌어도 중복 증식 없음), 인앱에서 지운 자동 관계가
     * 복원 때 되살아나지 않으며, 수동 관계가 세력 관계로 승격되지 않는다.
     */
    private suspend fun drainPendingAutoRelations(result: ImportResult) {
        if (pendingAutoRelationMemberships.isEmpty()) return
        val byFaction = pendingAutoRelationMemberships.groupBy({ it.first }, { it.second })
        var created = 0
        for ((factionId, characterIds) in byFaction) {
            val faction = db.factionDao().getById(factionId) ?: continue
            val activeMemberIds = db.factionMembershipDao().getActiveMembershipsByFaction(factionId)
                .map { it.characterId }
            val newRelationships = mutableListOf<CharacterRelationship>()
            val plannedPairs = mutableSetOf<Set<Long>>()
            for (characterId in characterIds.distinct()) {
                for (otherId in activeMemberIds) {
                    if (otherId == characterId) continue
                    val pair = setOf(characterId, otherId)
                    if (!plannedPairs.add(pair)) continue
                    val pairRels = db.characterRelationshipDao().getRelationshipsForCharacterList(characterId)
                        .filter { setOf(it.characterId1, it.characterId2) == pair }
                    // 이 세력의 자동 관계가 이미 있거나, 이번 가져오기의 관계 시트가 그 쌍을 기술했으면 건너뛴다
                    if (pairRels.any { it.factionId == factionId || it.id in matchedRelationshipIds }) continue
                    val (c1, c2) = if (characterId < otherId) characterId to otherId else otherId to characterId
                    newRelationships.add(CharacterRelationship(
                        characterId1 = c1,
                        characterId2 = c2,
                        relationshipType = faction.autoRelationType,
                        intensity = faction.autoRelationIntensity,
                        isBidirectional = true,
                        factionId = factionId
                    ))
                }
            }
            if (newRelationships.isNotEmpty()) {
                db.characterRelationshipDao().insertAll(newRelationships)
                created += newRelationships.size
            }
        }
        pendingAutoRelationMemberships.clear()
        if (created > 0) {
            result.warnings.add("세력 소속에 따라 자동 관계 ${created}건을 생성했습니다 (백업의 관계 시트가 기술한 쌍은 그대로 유지)")
        }
    }

    // ── 세력 관계 (B-3) ──

    private suspend fun importFactionRelationships(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = factionRelationshipSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val faction1ColIndex = cols[spec.firstColumnHeader] ?: cols["세력1"] ?: 0
        val faction2ColIndex = cols["세력2"] ?: -1
        val typeColIndex = cols["관계 유형"] ?: -1
        val descColIndex = cols["설명"] ?: -1
        val intensityColIndex = cols["강도"] ?: -1
        val bidirectionalColIndex = cols["양방향"] ?: -1
        val orderColIndex = cols["표시순서"] ?: -1
        val faction1CodeColIndex = cols["세력1코드"] ?: -1
        val faction2CodeColIndex = cols["세력2코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        if (faction2ColIndex < 0 || typeColIndex < 0) {
            result.errors.add("세력 관계 시트: '세력2' 또는 '관계 유형' 컬럼을 찾을 수 없음")
            return
        }

        val factionIndex = FactionIndex(db.factionDao().getAllFactionsList())
        val universeNames = db.universeDao().getAllUniversesList().associate { it.id to it.name }
        val existingByKey = db.factionRelationshipDao().getAllRelationshipsList()
            .associateBy { FactionRelationshipMatcher.key(it.factionId1, it.factionId2, it.relationType) }
            .toMutableMap()
        val presence = factionRelationshipPresence(descColIndex, intensityColIndex, bidirectionalColIndex, orderColIndex)

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val f1Name = getCellString(row, faction1ColIndex)
                if (f1Name.isBlank()) continue
                val f2Name = getCellString(row, faction2ColIndex)
                if (f2Name.isBlank()) continue
                val relType = getCellString(row, typeColIndex).trim()
                if (relType.isBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 관계 행 $i: 관계 유형이 비어 있음")
                    continue
                }

                val f1Code = getCellCode(row, faction1CodeColIndex, "세력 관계 행 $i", result)
                val f2Code = getCellCode(row, faction2CodeColIndex, "세력 관계 행 $i", result)
                // 이 시트에는 세계관 열이 없다 — 한쪽이 확정되면 그 세계관을 상대편 동명 해소의 힌트로 쓴다
                // (인앱 세력 관계는 같은 세계관 안에서만 만들어진다)
                val (r1, r2) = resolveFactionPair(factionIndex, f1Name, f1Code, f2Name, f2Code)
                val faction1 = when (r1) {
                    is FactionLookupResult.Found -> {
                        warnFactionCodeFallback("세력 관계 행 $i", f1Code, r1, f1Name, result); r1.faction
                    }
                    is FactionLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add(factionAmbiguityMessage("세력 관계 행 $i", f1Name, r1, "세력1코드", universeNames::get))
                        continue
                    }
                    FactionLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 관계 행 $i: 세력 '${f1Name.ifBlank { f1Code }}'을(를) 찾을 수 없음")
                        continue
                    }
                }
                val faction2 = when (r2) {
                    is FactionLookupResult.Found -> {
                        warnFactionCodeFallback("세력 관계 행 $i", f2Code, r2, f2Name, result); r2.faction
                    }
                    is FactionLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add(factionAmbiguityMessage("세력 관계 행 $i", f2Name, r2, "세력2코드", universeNames::get))
                        continue
                    }
                    FactionLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 관계 행 $i: 세력 '${f2Name.ifBlank { f2Code }}'을(를) 찾을 수 없음")
                        continue
                    }
                }
                if (faction1.universeId != faction2.universeId) {
                    // 앱은 같은 세계관 안에서만 세력 관계를 만든다 — 그대로 저장하되 조용히 넘기지 않는다
                    result.warnings.add("세력 관계 행 $i: '${faction1.name}'(${universeNames[faction1.universeId] ?: "?"})과(와) '${faction2.name}'(${universeNames[faction2.universeId] ?: "?"})은 서로 다른 세계관의 세력입니다 — '세력1코드'·'세력2코드' 열로 확정하세요")
                }
                if (faction1.id == faction2.id) {
                    result.skippedRows++
                    result.errors.add("세력 관계 행 $i: 세력1과 세력2가 동일함")
                    continue
                }

                val rowValues = factionRelationshipRowValues(
                    row, descColIndex, intensityColIndex, bidirectionalColIndex, orderColIndex,
                    "세력 관계 행 $i", result
                )
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                // 정방향/역방향 모두 매칭하여 중복 생성 방지 — 규칙은 미리보기 분석과 같은 함수다(B-87)
                val existing = FactionRelationshipMatcher.match(existingByKey, faction1.id, faction2.id, relType)

                if (existing != null) {
                    // 열 없음 = 기존값 유지(무음 손실 방지), 빈칸=삭제 집계 — 캐릭터 관계 시트와 동일 의미론
                    if (descColIndex >= 0 && rowValues.description == "" && existing.description.isNotBlank()) result.clearedFields++
                    val mergedFactionRel = FactionRelationshipMatcher.apply(existing, rowValues, presence)
                    db.factionRelationshipDao().update(mergedFactionRel)
                    matchedFactionRelationshipIds.add(existing.id)
                    if (mergedFactionRel != existing) result.updatedFactionRelationships++ else result.unchangedRows++
                } else {
                    val newRel = FactionRelationship(
                        factionId1 = faction1.id,
                        factionId2 = faction2.id,
                        relationType = relType,
                        description = rowValues.description,
                        intensity = rowValues.intensity,
                        isBidirectional = rowValues.isBidirectional,
                        displayOrder = rowValues.displayOrder,
                        createdAt = createdAt
                    )
                    val newId = db.factionRelationshipDao().insert(newRel)
                    if (newId > 0) {
                        existingByKey[FactionRelationshipMatcher.key(faction1.id, faction2.id, relType)] = newRel.copy(id = newId)
                        matchedFactionRelationshipIds.add(newId)
                        result.newFactionRelationships++
                    } else {
                        // 시트 내 중복 행 (IGNORE 충돌)
                        result.skippedRows++
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세력 관계 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "세력 관계", sheet.lastRowNum, totalRows)
    }

    // ── 이미지 라이브러리 메타 (G3) ──

    /**
     * "이미지" 시트(파일명/태그/링크그룹)를 라이브러리 메타로 복원한다.
     *
     * 경로 해석(파일명 기반 — 절대경로는 기기 간 이식성 없음):
     * ① zip 복원 리맵([imagePathRemap]의 원경로 basename 매칭) → 새 경로
     * ② 로컬 filesDir에 같은 파일명이 존재 → 그 경로 (xlsx 단독 왕복)
     * ③ 둘 다 아니면 스킵 계수 + 요약 경고 (무음 유실 금지)
     *
     * 링크그룹은 내보낸 토큰을 그대로 보존해 재가져오기가 멱등이다. 단, 해석된 멤버와
     * 기존 DB 멤버의 합이 2장 미만이면 설정하지 않는다(잔존 singleton 배지 방지).
     */
    private suspend fun importImageMeta(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = imageMetaSpec()
        // 시트 부재는 타 카테고리와 같은 관례로 경고를 남긴다(G3 이전 백업 — 조용한 스킵 금지)
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        // 3중 방어 ③: 예약명이라도 실제 이미지 형식인지 헤더로 검증 — 레거시 백업의
        // 세계관 "이미지" 캐릭터 시트(첫 헤더 "이름")를 이미지 메타로 오파싱하지 않는다.
        if (!isValidHeader(headerRow, spec.firstColumnHeader)) {
            result.warnings.add("'${spec.sheetName}' 시트의 첫 헤더가 '${spec.firstColumnHeader}'이(가) 아니어서 이미지 태그·링크 가져오기를 건너뛰었습니다")
            return
        }

        reportUnknownColumns(headerRow, spec, result)
        // 열 해석은 미리보기와 **같은 클래스**다(규약 R-33).
        // '뗀날짜'의 출처는 readOnly라 **읽지 않는다**(사용자가 적은 코드를 믿으면 없는 캐릭터를
        // 가리키게 된다). 시각만 읽고, 새로 붙는 표식의 출처는 앱이 아는 값으로만 채운다.
        val imc = ImageMetaCols(resolveHeaderColumns(headerRow))

        // zip 리맵 키(원 절대경로)의 basename → 복원된 새 경로. basename 충돌은 무음 last-wins 대신 고지한다.
        val remap = ImageMetaRowResolver.buildRemapByBasename(imagePathRemap)
        remap.warnings.forEach { result.warnings.add(it) }
        val filesDir = appContext?.filesDir

        // 같은 이미지를 가리키는 행이 둘 이상이면 뒤 행이 앞 행의 태그를 통째로 지운다 —
        // 다른 시트의 코드 중복과 같은 규약(마지막 행 우선 + 고지)으로 접는다.
        val sheetRows = (1..sheet.lastRowNum).mapNotNull { i ->
            val row = sheet.getRow(i) ?: return@mapNotNull null
            val fileName = getCellString(row, imc.file)
            if (fileName.isBlank()) null else i to fileName
        }
        val plan = ImageMetaRowResolver.plan(sheetRows, remap.byBasename) { fileName ->
            filesDir?.let { dir -> java.io.File(dir, fileName).takeIf { it.exists() }?.absolutePath }
        }
        plan.warnings.forEach { result.warnings.add(it) }

        val now = System.currentTimeMillis()
        val skippedMissing = plan.unresolved.size
        val groupMembers = mutableMapOf<String, MutableList<Long>>()
        // 빈 칸 = 링크 해제(F1-A 규약 — 태그 열과 같다). 종전에는 빈 칸이 아무 일도 하지 않아
        // "엑셀에서 링크를 지웠는데 그대로"였고 고지도 없었다(설계 9장 C-3).
        val clearedGroupIds = mutableListOf<Long>()
        val clearedGroupTokens = mutableSetOf<String>()
        var clearedAutoLinks = 0

        for ((i, _, path) in plan.rows) {
            try {
                val row = sheet.getRow(i) ?: continue

                // 읽기도 미리보기와 **같은 함수**다(규약 R-33).
                val r = readImageMetaRow(row, imc, result)

                val existing = db.imageMetaDao().getByPath(path)
                val imageId = existing?.id ?: db.imageMetaDao().adopt(path, now)
                if (existing == null) result.newImageMeta++

                // 무엇이 바뀌는가의 판정도 같은 함수다. 태그는 열이 있을 때만 읽는다 —
                // 없으면 비교 대상이 아니라 조회도 낭비다.
                val current = ImageMetaState(
                    tags = if (r.hasTagCol) db.imageTagDao().getTagsByImageList(imageId).map { it.tag }.toSet() else emptySet(),
                    linkGroupId = existing?.linkGroupId,
                    detachedAt = existing?.detachedAt
                )
                val target = mergeImageMetaState(current, r)
                // '갱신'은 **실제로 바뀐 행**이다 (B-111 · 확정 7-2) — 미리보기가 쓰는 판정과
                // 글자 그대로 같다(`mergeImageMetaState(current, r) != current`).
                if (existing != null) {
                    if (target != current) result.updatedImageMeta++ else result.unchangedRows++
                }

                // F1-A: '태그' 열이 없으면 기존 태그 유지. 열이 있고 빈칸이면 비움 의도로 존중.
                // 바뀌지 않으면 쓰지 않는다 — replaceAllForImage는 전량 삭제+재삽입이다.
                if (r.hasTagCol && target.tags != current.tags) {
                    db.imageTagDao().replaceAllForImage(
                        imageId,
                        target.tags.map { com.novelcharacter.app.data.model.ImageTag(imageId = imageId, tag = it) }
                    )
                }

                // 뗀 표식(B-107) — 태그·링크그룹과 같은 규약. 값이 있으면 그 시각으로 두고,
                // 빈칸이면 서랍에서 뺀다. **출처 열은 읽지 않는다**(readOnly): 앱이 아는 출처를
                // 사용자가 적은 문자열로 덮어쓰면 화면이 없는 캐릭터를 가리키게 된다.
                if (r.hasDetachedCol) {
                    // 값이 있는데 숫자가 아니면 **손대지 않고 알린다** — 조용히 버리면
                    // 사용자가 무엇을 잘못 적었는지 알 길이 없다(개발 의도 2번).
                    // 병합 결과가 기존과 같다는 것이 곧 '손대지 않는다'이다.
                    if (r.detachedRaw.isNotBlank() && r.detachedAt == null) {
                        result.warnings.add("이미지 행 $i: '뗀날짜' 값 \"${r.detachedRaw}\"을(를) 읽을 수 없어 그대로 두었습니다")
                    }
                    val targetDetachedAt = target.detachedAt
                    when {
                        targetDetachedAt == current.detachedAt -> Unit  // 바뀌지 않으면 쓰지 않는다
                        targetDetachedAt != null ->
                            db.imageMetaDao().markDetachedByPaths(
                                listOf(path), targetDetachedAt, existing?.detachedFromCode
                            )
                        // 빈칸(또는 0) = 서랍에서 빼기. 이미 빠져 있으면 아무 일도 없다.
                        else -> if (existing?.isDetached == true) {
                            db.imageMetaDao().clearDetachedByPaths(listOf(path))
                        }
                    }
                }

                if (r.hasGroupCol) {
                    val groupToken = target.linkGroupId
                    if (groupToken != null) {
                        groupMembers.getOrPut(groupToken) { mutableListOf() }.add(imageId)
                    } else {
                        val currentGroup = existing?.linkGroupId
                        if (currentGroup != null) {
                            clearedGroupIds.add(imageId)
                            clearedGroupTokens.add(currentGroup)
                            // 자동 링크는 재동기화가 도로 묶는다 — 해제가 조용히 되돌아가지
                            // 않도록 그 수를 센다(인앱 '링크 해제'의 autoRelinkable과 같은 취지).
                            if (com.novelcharacter.app.util.AutoLinkPlanner.isAutoToken(currentGroup)) {
                                clearedAutoLinks++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("이미지 행 $i: ${e.message}")
            }
        }

        // 해제를 먼저 반영한다 — 같은 가져오기에서 A가 빠지고 B가 들어오는 경우, 해제를 뒤에
        // 하면 방금 만든 묶음을 도로 지운다.
        if (clearedGroupIds.isNotEmpty()) {
            db.imageMetaDao().setGroup(clearedGroupIds, null)
            // 1장만 남은 묶음의 잔존 표식은 오해를 부른다 — 인앱 해제와 같은 정리를 건다.
            clearedGroupTokens.forEach { db.imageMetaDao().clearGroupIfSingleton(it) }
            result.warnings.add("이미지 ${clearedGroupIds.size}건: '링크그룹' 칸이 비어 있어 링크를 해제했습니다")
            if (clearedAutoLinks > 0) {
                result.warnings.add(
                    "그중 ${clearedAutoLinks}건은 캐릭터 자동 링크라, 그 캐릭터에 계속 등록되어 있으면 " +
                        "다음 재동기화가 다시 묶습니다 (이미지 설정에서 자동 링크를 끌 수 있습니다)"
                )
            }
        }

        for ((token, ids) in groupMembers) {
            val existingIds = db.imageMetaDao().getByGroup(token).map { it.id }
            if ((ids + existingIds).toSet().size >= 2) {
                db.imageMetaDao().setGroup(ids, token)
            }
        }

        if (skippedMissing > 0) {
            result.warnings.add("이미지 ${skippedMissing}건: 파일을 찾을 수 없어 태그·링크 복원을 건너뛰었습니다 (이미지 포함 ZIP 백업으로 가져오면 함께 복원됩니다)")
        }
        reportProgress(onProgress, "이미지", sheet.lastRowNum, totalRows)
    }

    // ── 대결 가져오기 (B-104 ㄹ1) ──
    //
    // **순서가 규약이다: 축 → 기록 → 상성.** 판·처분은 축을 가리키므로 축이 먼저 들어와야
    // 붙을 자리가 있다(확-3의 교훈 — 정의가 기록보다 앞이다). 축을 못 찾은 행은 조용히 버리지
    // 않고 **세어서 알린다**(개발 의도 2번).
    //
    // 읽기(`readDuel*Row`)와 병합(`mergeDuel*`)을 함수로 뽑은 것은 규약 R-33이다 —
    // 복원 미리보기(`analyzeDuel*`)와 실제 가져오기가 **같은 함수**로 판정해야 '변경/동일'이
    // 거짓말을 하지 않는다.

    private data class DuelAxisRowValues(
        val name: String,
        val universeName: String,
        val universeCode: String,
        val targetType: String,
        /**
         * 필드 연결 셋. **null은 "그 열이 파일에 없다"**이고 `"[]"`는 *"열은 있는데 비었다"*다 —
         * 앞은 이 파일이 그 사실을 말하지 않는 것이고 뒤는 **지우라는 뜻**이라, 둘을 같게
         * 다루면 그 열이 없던 시절에 내보낸 파일을 다시 들이는 것만으로 연결이 지워진다
         * (`프로필필드`는 v52에 생겼으므로 그전 파일 전부가 이 경우다).
         */
        val influenceFieldKeys: String?,
        val outcomeFieldKeys: String?,
        val profileFieldKeys: String?,
        /**
         * 후보 필터 (B-168). 겹으로 nullable인 것이 사실 셋을 가른다 —
         * 바깥 null = *열이 없다*(v55 전 파일 · **읽을 수 없는 칸**도 여기로 접어 기존 값을
         * 지킨다), 안쪽 null = *지워라*, 값 = *이 필터로 바꿔라*.
         */
        val candidateFiltersJson: DuelCandidateFilter.SheetCell.Value?,
        /** 후보필터 칸이 있는데 읽을 수 없었다 — 가져오기가 경고 한 줄을 낸다. */
        val candidateFiltersMalformed: Boolean,
        /**
         * 기준 축인가 (B-104 ⓑ·ⓒ). **null은 "그 열이 파일에 없다"**이고 false는 *"내려라"*다 —
         * F1-A 불리언 규약(`sheetBooleanOrKeep`)이며, 둘을 같게 다루면 v56 전에 내보낸 파일을
         * 다시 들이는 것만으로 **대표 그림의 기준이 조용히 풀린다.**
         */
        val isBasisAxis: Boolean?,
        val displayOrder: Int,
        val code: String,
        val createdAt: Long
    )

    private fun readDuelAxisRow(row: Row, cols: Map<String, Int>, now: Long): DuelAxisRowValues {
        fun cell(header: String, dateHint: Boolean = false) =
            getCellString(row, cols[header] ?: -1, dateHint = dateHint)

        /** 열이 있을 때만 읽는다 — 없으면 null이고, 병합이 그 칸을 건드리지 않는다. */
        fun links(header: String): String? =
            if (cols.containsKey(header)) {
                DuelFieldLinks.encode(DuelFieldLinks.parseText(cell(header)))
            } else {
                null
            }

        // 후보 필터 — 같은 "없는 열은 건드리지 않는다" 규약에 해석 실패 한 갈래가 더 있다.
        val filterCell = if (cols.containsKey("후보필터(JSON)")) {
            DuelCandidateFilter.fromSheetCell(cell("후보필터(JSON)"))
        } else {
            null
        }

        val targetLabel = cell("대상")
        return DuelAxisRowValues(
            name = cell("축이름"),
            universeName = cell("세계관"),
            universeCode = cell("세계관코드"),
            // 한국어 말과 저장값을 모두 받는다 — 외부 도구가 저장값을 그대로 쓸 수도 있다.
            targetType = when (targetLabel) {
                DuelSheetLabels.TARGET_IMAGE, DuelAxis.TARGET_IMAGE -> DuelAxis.TARGET_IMAGE
                else -> DuelAxis.TARGET_CHARACTER
            },
            // 사람이 적은 차례가 곧 영향력 순위다(프로필은 표시 차례다) — 정렬하지 않는다.
            influenceFieldKeys = links("영향필드"),
            outcomeFieldKeys = links("산출필드"),
            profileFieldKeys = links("프로필필드"),
            candidateFiltersJson = filterCell as? DuelCandidateFilter.SheetCell.Value,
            candidateFiltersMalformed = filterCell is DuelCandidateFilter.SheetCell.Malformed,
            isBasisAxis = sheetBooleanOrKeep(cols.containsKey("기준축"), cell("기준축")),
            displayOrder = cell("정렬순서").toDoubleOrNull()?.toInt() ?: 0,
            code = cell("코드"),
            createdAt = cell("생성일", dateHint = true).toDoubleOrNull()?.toLong() ?: now
        )
    }

    /** 새 축의 연결 — 열이 없으면 빈 연결이다(기존 값이 없으므로 지킬 것도 없다). */
    private fun newAxisLinks(value: String?): String = value ?: "[]"

    /**
     * **이 앱이 모르는 `sys:` 키를 알린다** (B-172).
     *
     * `sys:anothername`(밑줄 빠짐)처럼 사람이 낸 오타는 화면에 글자 그대로 뜨고 값은 영영
     * 비어 있다. **모르는 커스텀 필드 키와 겉모습이 같지만 성질이 다르다** — 그쪽은 나중에
     * 그 필드를 만들면 살아나므로 남겨 두는 것이 옳고, 이쪽은 살아날 길이 없다.
     *
     * **왜 가져오기 결과인가**(자리를 하나로 정한 근거 — 4장 B-172 행에도 적었다):
     * ⓐ 이 키는 **엑셀 경로로만 생긴다** — 고르는 창은 아는 열만 내므로 오타를 만들 수 없다.
     * 그래서 결과 창 앞에 선 사람이 곧 그것을 만든 사람이다.
     * ⓑ 축 편집 창은 축을 **하나씩 열어야** 보이므로, 축이 여럿인 파일에서는 오타가 있다는
     * 사실 자체를 알려면 전부 열어 봐야 한다(원칙 04).
     * ⓒ 축 편집 창이 이미 말하는 둘(`profileBlocked`·`outcomeBlocked`)은 *자리* 위반이라
     * 이어지는 상태이고, 이것은 *존재* 문제라 파일을 읽는 순간의 사실이다 — 물음이 달라
     * 같은 말이 두 자리에서 뜨는 것이 아니다.
     *
     * **열이 없는 칸은 재지 않는다** — null은 *"이 파일이 그것을 말하지 않았다"*라
     * 이번 파일 탓으로 고지할 것이 없다(연결 셋의 nullable 규약과 같은 근거).
     */
    private fun warnUnknownSystemKeys(r: DuelAxisRowValues, rowIndex: Int, result: ImportResult) {
        val axis = DuelFieldLinks.Axis(
            influences = DuelFieldLinks.decode(r.influenceFieldKeys),
            outcomes = DuelFieldLinks.decode(r.outcomeFieldKeys),
            profiles = DuelFieldLinks.decode(r.profileFieldKeys)
        )
        val unknown = axis.unknownSystemKeys
        if (unknown.isEmpty()) return
        result.warnings.add(
            "대결 축 행 $rowIndex: 이 앱에 없는 시스템 열 ${unknown.joinToString(", ")} — " +
                "값이 채워지지 않습니다. 연결은 지우지 않았으니 철자를 고쳐 다시 가져오거나 " +
                "축 편집에서 지우세요(쓸 수 있는 이름은 '사용 안내' 시트에 있습니다)"
        )
    }

    /**
     * 기준 축은 **세계관의 이미지 축들 사이에서 하나** — 켠 행이 나오면 형제의 표식을 내린다
     * (B-104 ⓑ·ⓒ).
     *
     * `DuelRepository.saveAxis`가 화면 경로에서 지키는 그 계약을 가져오기 경로에서도 지킨다.
     * 여기가 저장소를 부르지 않고 DAO를 직접 쓰는 자리라, 빠뜨리면 **엑셀로 들어온 파일만
     * 기준이 둘인 상태**가 되고 그때 대표 그림이 어느 축을 따르는지 아무도 말할 수 없다.
     *
     * **대상이 이미지일 때만 내린다.** 시트는 모든 축 행에 Y/N 드롭다운을 싣는데, 캐릭터 축
     * 행에 `Y`를 적는 것은 시트 설명이 *"값은 남되 아무 일도 하지 않는다"*고 약속한 자리다.
     * 그것으로 형제를 내리면 **한 칸 손편집이 살아 있는 이미지 축의 기준을 조용히 푼다** —
     * 그리고 다음 내보내기가 `N`을 적어 **파일에서 되살릴 길도 사라진다**(개발 의도 2번).
     *
     * 한 세계관에 `Y`가 여럿 적힌 파일은 **거부하지 않는다** — 행 차례대로 켜면서 형제를
     * 내리므로 마지막 행이 이기고, 그것이 *"거부가 아니라 유연한 수용·교정"*이다(개발 의도 4번).
     */
    private suspend fun enforceSingleBasisAxis(
        universeId: Long,
        targetType: String,
        axisId: Long,
        isBasis: Boolean
    ) {
        if (!isBasis || axisId <= 0 || targetType != DuelAxis.TARGET_IMAGE) return
        db.duelAxisDao().clearBasisExcept(universeId, targetType, axisId)
    }

    /** 병합 규칙의 단일 소스 — 미리보기와 가져오기가 함께 부른다(R-33). */
    private fun mergeDuelAxis(existing: DuelAxis, r: DuelAxisRowValues, universeId: Long): DuelAxis =
        existing.copy(
            name = r.name,
            universeId = universeId,
            // **대상은 바꾸지 않는다** — 바꾸면 쌓인 판의 참가자가 통째로 뜻을 잃는다(엔티티 주석).
            targetType = existing.targetType,
            influenceFieldKeys = r.influenceFieldKeys ?: existing.influenceFieldKeys,
            outcomeFieldKeys = r.outcomeFieldKeys ?: existing.outcomeFieldKeys,
            profileFieldKeys = r.profileFieldKeys ?: existing.profileFieldKeys,
            // 열이 없거나 읽을 수 없으면 기존 값을 지킨다(위 KDoc — 사실 셋을 가른다).
            candidateFiltersJson = if (r.candidateFiltersJson != null) {
                r.candidateFiltersJson.json
            } else {
                existing.candidateFiltersJson
            },
            // 열이 없으면 기존 값을 지킨다(F1-A) — 그러지 않으면 v56 전 파일이 기준을 푼다.
            isBasisAxis = r.isBasisAxis ?: existing.isBasisAxis,
            displayOrder = r.displayOrder
        )

    private suspend fun importDuelAxes(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = duelAxisSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow, result, spec.sheetName)
        val now = System.currentTimeMillis()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val r = readDuelAxisRow(row, cols, now)
                if (r.name.isBlank()) continue

                val universe = (if (r.universeCode.isNotBlank()) db.universeDao().getUniverseByCode(r.universeCode) else null)
                    ?: (if (r.universeName.isNotBlank()) db.universeDao().getUniverseByName(r.universeName) else null)
                if (universe == null) {
                    result.skippedRows++
                    result.errors.add("대결 축 행 $i: 세계관 '${r.universeName}'을(를) 찾을 수 없음")
                    continue
                }

                val existing = (if (r.code.isNotBlank()) db.duelAxisDao().getByCode(r.code) else null)
                    ?: db.duelAxisDao().getByUniverseAndName(universe.id, r.targetType, r.name)
                if (r.candidateFiltersMalformed) {
                    // 기존 값을 지키고 그 사실을 말한다 — 괄호 하나 틀린 손편집이 멀쩡한
                    // 필터를 조용히 지우면 안 된다(개발 의도 2번·4번).
                    result.warnings.add(
                        "대결 축 행 $i: 후보필터(JSON) 칸을 읽을 수 없어 기존 필터를 유지했습니다 — 앱에서 다시 내보낸 파일의 형식을 참고해 고쳐 주세요"
                    )
                }
                // 이 앱이 모르는 `sys:` 키 — 오타는 **영영 살아나지 않는다**(B-172).
                // 값은 그대로 담고 사실만 말한다: 거부하면 나머지 멀쩡한 연결까지 잃는다.
                warnUnknownSystemKeys(r, i, result)
                if (existing == null) {
                    // 같은 (세계관, 대상, 이름)이 이미 있으면 유니크 인덱스가 던진다 — 위에서
                    // 이미 찾아봤으므로 여기 오는 것은 진짜 새 축이다.
                    val newId = db.duelAxisDao().insert(
                        DuelAxis(
                            universeId = universe.id,
                            name = r.name,
                            targetType = r.targetType,
                            displayOrder = r.displayOrder,
                            createdAt = r.createdAt,
                            influenceFieldKeys = newAxisLinks(r.influenceFieldKeys),
                            outcomeFieldKeys = newAxisLinks(r.outcomeFieldKeys),
                            profileFieldKeys = newAxisLinks(r.profileFieldKeys),
                            candidateFiltersJson = r.candidateFiltersJson?.json,
                            // 새 축이라 지킬 기존 값이 없다 — 열이 없으면 엔티티 기본값(꺼짐)이다.
                            isBasisAxis = r.isBasisAxis ?: false,
                            code = r.code.ifBlank { generateEntityCode() }
                        )
                    )
                    enforceSingleBasisAxis(universe.id, r.targetType, newId, r.isBasisAxis ?: false)
                    result.newDuelAxes++
                    if (r.code.isNotBlank()) {
                        warnCreatedNewByCode("duelAxes", "대결 축 행 $i: 코드 '${r.code}'가 기존 축에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                    }
                } else {
                    val merged = mergeDuelAxis(existing, r, universe.id)
                    if (merged != existing) {
                        db.duelAxisDao().update(merged)
                        result.updatedDuelAxes++
                    } else result.unchangedRows++
                    // **대상은 기존 축의 것이다** — `mergeDuelAxis`가 targetType을 바꾸지 않으므로
                    // 행에 적힌 대상이 아니라 실제 축의 대상으로 판정해야 한다.
                    enforceSingleBasisAxis(
                        universe.id, merged.targetType, merged.id, merged.isBasisAxis
                    )
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("대결 축 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "대결 축 가져오기", sheet.lastRowNum, totalRows)
    }

    private data class DuelMatchRowValues(
        val axisName: String,
        val axisCode: String,
        val aName: String,
        val aCode: String,
        val bName: String,
        val bCode: String,
        val winnerText: String,
        val groupId: String,
        val code: String,
        val decidedAt: Long
    )

    private fun readDuelMatchRow(row: Row, cols: Map<String, Int>, now: Long): DuelMatchRowValues {
        fun cell(header: String, dateHint: Boolean = false) =
            getCellString(row, cols[header] ?: -1, dateHint = dateHint)
        return DuelMatchRowValues(
            axisName = cell("축"),
            axisCode = cell("축코드"),
            aName = cell("참가자1"),
            aCode = cell("참가자1코드"),
            bName = cell("참가자2"),
            bCode = cell("참가자2코드"),
            winnerText = cell("승자"),
            groupId = cell("묶음"),
            code = cell("코드"),
            decidedAt = cell("판정일", dateHint = true).toDoubleOrNull()?.toLong() ?: now
        )
    }

    private fun mergeDuelMatch(existing: DuelMatch, winnerCode: String?, groupId: String?): DuelMatch =
        // 참가자와 시각은 바꾸지 않는다 — 바꾸면 그 행은 *다른 판*이다(기록 화면과 같은 규약).
        existing.copy(winnerCode = winnerCode, groupId = groupId)

    /**
     * 승자 칸을 코드로 옮긴다.
     *
     * **빈 칸과 '비슷함'만 무승부다.** 이름을 적었는데 두 참가자 중 어느 쪽도 아니면 그 행은
     * 거부한다 — 조용히 무승부로 접으면 사용자가 고른 승패가 왕복 한 번에 사라지고, 그것은
     * 이 앱이 금지하는 무음 유실이다(개발 의도 2번·4번).
     */
    private fun resolveDuelWinner(
        text: String,
        aCode: String, aName: String,
        bCode: String, bName: String
    ): Result<String?> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == DuelSheetLabels.WINNER_DRAW) return Result.success(null)
        return when (trimmed) {
            aCode, aName -> Result.success(aCode)
            bCode, bName -> Result.success(bCode)
            else -> Result.failure(IllegalArgumentException(trimmed))
        }
    }

    private suspend fun importDuelMatches(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = duelMatchSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow, result, spec.sheetName)
        val now = System.currentTimeMillis()

        // 축과 캐릭터 이름은 **루프 밖에서 한 번** 색인한다 — 이 시트는 수만 행이 될 수 있어
        // 행마다 조회하면 그 비용이 행 수만큼 곱해진다.
        val axes = db.duelAxisDao().getAllList()
        val axisByCode = axes.associateBy { it.code }
        val axesByName = axes.groupBy { it.name }
        val codeByName = db.characterDao().getAllCharactersList()
            .groupBy({ it.displayName }, { it.code })

        val seenCodes = HashSet<String>()
        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val r = readDuelMatchRow(row, cols, now)
                if (r.axisName.isBlank() && r.axisCode.isBlank()) continue

                val axis = axisByCode[r.axisCode] ?: axesByName[r.axisName]?.let { candidates ->
                    if (candidates.size == 1) candidates.first() else null
                }
                if (axis == null) {
                    result.skippedRows++
                    result.errors.add(
                        if ((axesByName[r.axisName]?.size ?: 0) > 1) {
                            "대결 기록 행 $i: 축 '${r.axisName}'이(가) 여럿이라 어느 축인지 정할 수 없음 — 축코드를 함께 적어 주세요"
                        } else {
                            "대결 기록 행 $i: 축 '${r.axisName}'을(를) 찾을 수 없음"
                        }
                    )
                    continue
                }

                // 참가자는 **코드가 정체**다. 코드가 비어 있을 때만 이름으로 찾고, 동명이인이면
                // 거부한다 — 아무나 골라 붙이는 것이 R-1이 말하는 오배정이다.
                fun resolveParticipant(code: String, name: String): String? {
                    if (code.isNotBlank()) return code
                    val candidates = codeByName[name].orEmpty()
                    return if (candidates.size == 1) candidates.first() else null
                }
                val aCode = resolveParticipant(r.aCode, r.aName)
                val bCode = resolveParticipant(r.bCode, r.bName)
                if (aCode.isNullOrBlank() || bCode.isNullOrBlank() || aCode == bCode) {
                    result.skippedRows++
                    result.errors.add("대결 기록 행 $i: 참가자를 정할 수 없음 ('${r.aName}' · '${r.bName}') — 참가자 코드를 함께 적어 주세요")
                    continue
                }

                val winner = resolveDuelWinner(r.winnerText, aCode, r.aName, bCode, r.bName)
                if (winner.isFailure) {
                    result.skippedRows++
                    result.errors.add("대결 기록 행 $i: 승자 '${r.winnerText}'이(가) 두 참가자 중 어느 쪽도 아님 — 비겼으면 '${DuelSheetLabels.WINNER_DRAW}'이라고 적어 주세요")
                    continue
                }
                val winnerCode = winner.getOrNull()
                val groupId = r.groupId.ifBlank { null }

                if (r.code.isNotBlank() && !seenCodes.add(r.code)) {
                    result.warnings.add("대결 기록: 코드 '${r.code}'가 두 행에 중복됨 (마지막 행 우선)")
                }

                val existing = if (r.code.isNotBlank()) db.duelMatchDao().getByCode(r.code) else null
                if (existing == null) {
                    db.duelMatchDao().insert(
                        DuelMatch(
                            axisId = axis.id,
                            aCode = aCode,
                            bCode = bCode,
                            winnerCode = winnerCode,
                            groupId = groupId,
                            decidedAt = r.decidedAt,
                            code = r.code.ifBlank { generateEntityCode() }
                        )
                    )
                    result.newDuelMatches++
                } else {
                    val merged = mergeDuelMatch(existing, winnerCode, groupId)
                    if (merged != existing) {
                        db.duelMatchDao().update(merged)
                        result.updatedDuelMatches++
                    } else result.unchangedRows++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("대결 기록 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "대결 기록 가져오기", sheet.lastRowNum, totalRows)
    }

    private suspend fun importDuelVerdicts(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = duelVerdictSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow, result, spec.sheetName)
        val now = System.currentTimeMillis()

        val axes = db.duelAxisDao().getAllList()
        val axisByCode = axes.associateBy { it.code }
        val axesByName = axes.groupBy { it.name }
        val codeByName = db.characterDao().getAllCharactersList()
            .groupBy({ it.displayName }, { it.code })

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                fun cell(header: String, dateHint: Boolean = false) =
                    getCellString(row, cols[header] ?: -1, dateHint = dateHint)

                val axisName = cell("축")
                val axisCode = cell("축코드")
                if (axisName.isBlank() && axisCode.isBlank()) continue
                val axis = axisByCode[axisCode] ?: axesByName[axisName]?.singleOrNull()
                if (axis == null) {
                    result.skippedRows++
                    result.errors.add("대결 상성 행 $i: 축 '$axisName'을(를) 찾을 수 없음")
                    continue
                }

                // 코드 목록이 정체이고 이름은 사람이 읽는 몫이다 — 순서에 뜻이 있으므로 지키고,
                // 이름으로 되찾을 때도 적힌 차례 그대로 옮긴다.
                val rawCodes = splitCsv(cell("참가자코드들"))
                val members = rawCodes.ifEmpty {
                    splitCsv(cell("참가자들"))
                        .mapNotNull { name -> codeByName[name]?.singleOrNull() }
                }
                val shape = DuelRecords.shapeOf(members)
                if (shape == null) {
                    result.skippedRows++
                    result.errors.add("대결 상성 행 $i: 참가자가 둘 이상이어야 판정할 관계가 있습니다")
                    continue
                }

                val kindLabel = cell("종류")
                val kind = when (kindLabel) {
                    DuelSheetLabels.KIND_UNDECIDED, DuelCounterVerdict.KIND_UNDECIDED -> DuelCounterVerdict.KIND_UNDECIDED
                    else -> DuelCounterVerdict.KIND_COUNTER
                }
                val code = cell("코드")
                val memberKey = DuelRecords.memberKey(members)
                // 같은 관계는 축 안에서 하나뿐이다(유니크). 코드로 못 찾으면 그 키로 찾아
                // **덮어쓴다** — 그러지 않으면 유니크 인덱스가 예외로 죽는다.
                val existing = (if (code.isNotBlank()) db.duelCounterVerdictDao().getByCode(code) else null)
                    ?: db.duelCounterVerdictDao().getByMemberKey(axis.id, memberKey)

                val decidedAt = cell("판정일", dateHint = true).toDoubleOrNull()?.toLong() ?: now
                if (existing == null) {
                    db.duelCounterVerdictDao().upsert(
                        DuelCounterVerdict(
                            axisId = axis.id,
                            kind = kind,
                            shape = shape,
                            memberCodes = DuelRecords.encodeMembers(members),
                            memberKey = memberKey,
                            decidedAt = decidedAt,
                            code = code.ifBlank { generateEntityCode() }
                        )
                    )
                    result.newDuelVerdicts++
                } else {
                    val merged = existing.copy(
                        axisId = axis.id,
                        kind = kind,
                        shape = shape,
                        memberCodes = DuelRecords.encodeMembers(members),
                        memberKey = memberKey
                    )
                    if (merged != existing) {
                        db.duelCounterVerdictDao().update(merged)
                        result.updatedDuelVerdicts++
                    } else result.unchangedRows++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("대결 상성 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "대결 상성 가져오기", sheet.lastRowNum, totalRows)
    }

    // ── 유틸리티 메서드 ──

    private fun findSheetForUniverse(workbook: Workbook, universeName: String, reservedNames: Set<String>): Sheet? {
        // 3중 방어 ②: 세계관 이름이 예약 시트명("이미지" 등)과 겹치면 정확-일치 결과가 데이터 시트일 수
        // 있다. 예약명 충돌 시에는 헤더 피크(looksLikeCharacterSheet — 예약 시트 조회와 **같은 판정**)로
        // 레거시 캐릭터 시트(예약 시트 도입 전 백업)만 구제한다.
        // 신규 내보내기는 캐릭터 시트가 "(2)" 접미사로 sanitize되므로 아래 접미사 루프가 찾는다.

        // 시트명 정규화는 내보내기와 같은 함수를 쓴다(따로 두면 반드시 드리프트한다).
        val sanitized = sanitizeSheetNameBase(universeName)
        val isReserved = universeName in reservedNames || sanitized in reservedNames

        /**
         * @param requireCharacterHeader 예약명과 겹칠 때는 **캐릭터 시트임을 헤더로 확인**해야 한다.
         *   레거시 백업은 반대 배치(세계관이 평명, 진짜 예약 시트가 `(2)`)라, 확인 없이 접미사
         *   후보를 잡으면 세력 시트를 캐릭터 시트로 넘겨받아 세력 행이 캐릭터로 삽입된다.
         */
        fun findSuffixed(requireCharacterHeader: Boolean): Sheet? {
            for (idx in 0 until workbook.numberOfSheets) {
                val sheetName = workbook.getSheetName(idx)
                if (sheetName in reservedNames) continue
                if (!isSuffixedVariantOf(sheetName, sanitized)) continue
                val candidate = workbook.getSheetAt(idx)
                if (requireCharacterHeader && !looksLikeCharacterSheet(candidate)) continue
                return candidate
            }
            return null
        }

        // 세계관 이름이 예약명과 겹치면 **신규 내보내기는 반드시 캐릭터 시트를 "(2)"로 민다**
        // (assignSheetName의 ownerOf 규칙). 그러므로 접미사 후보를 먼저 본다 — 정확 일치를
        // 먼저 보면 같은 이름의 예약 시트(예: 진짜 '미분류 캐릭터' 시트)를 가로챈다.
        if (isReserved) {
            findSuffixed(requireCharacterHeader = true)?.let { return it }
        }

        // POI의 getSheet는 **대소문자를 무시**한다. 대소문자만 다른 두 세계관('MyWorld'/'myworld')이
        // 있으면 둘 다 첫 시트로 해석돼 한쪽 캐릭터가 남의 세계관으로 들어간다.
        // 정확(대소문자 구분) 일치를 먼저 찾고, 없을 때만 POI의 관대한 조회로 폴백한다.
        fun exactSheet(name: String): Sheet? {
            for (idx in 0 until workbook.numberOfSheets) {
                if (workbook.getSheetName(idx) == name) return workbook.getSheetAt(idx)
            }
            return null
        }

        exactSheet(universeName)?.let {
            // 레거시 백업(규칙 도입 전): 세계관이 예약명을 차지한 파일은 헤더 피크로만 구제한다.
            if (!isReserved || looksLikeCharacterSheet(it)) return it
        }
        if (sanitized != universeName) {
            exactSheet(sanitized)?.let {
                if (!isReserved || looksLikeCharacterSheet(it)) return it
            }
        }
        // 대소문자 차이만 있는 시트로의 폴백 — 대소문자 변형이 유일할 때만 안전하다.
        val caseInsensitive = (0 until workbook.numberOfSheets)
            .filter { workbook.getSheetName(it).equals(sanitized, ignoreCase = true) }
        if (caseInsensitive.size == 1) {
            val only = workbook.getSheetAt(caseInsensitive[0])
            if (!isReserved || looksLikeCharacterSheet(only)) return only
        }
        // 예약명과 겹치지 않는 평범한 이름이면 접미사 후보는 동명 세계관의 시트뿐이라
        // 헤더 확인 없이 받아도 안전하다(종전 동작 유지).
        findSuffixed(requireCharacterHeader = isReserved)?.let { return it }
        return null
    }

    /**
     * '미분류 캐릭터' 시트 조회 — 세계관 시트와 **상호배제**한다.
     *
     * 종전에는 세 곳(본 임포트·분석·덮어쓰기 가드)이 각자 `workbook.getSheet(정확 일치)`를
     * 불렀다. 세계관 이름이 '미분류 캐릭터'인 백업에서는 그 세계관 시트가 평명을 차지해
     * **같은 시트가 세계관용으로 한 번, 미분류용으로 또 한 번** 처리됐고(미리보기 건수 부풀림 +
     * 중복 충돌 항목), 진짜 미분류 시트는 어느 경로도 읽지 않아 무음 유실이 됐다.
     *
     * @param alreadyConsumed 세계관 캐릭터 시트로 이미 소비된 시트명 — 여기서는 잡지 않는다.
     */
    private fun findUnclassifiedSheet(workbook: Workbook, alreadyConsumed: Set<String> = emptySet()): Sheet? {
        val exact = workbook.getSheet(UNCLASSIFIED_SHEET_NAME)
        if (exact != null && exact.sheetName !in alreadyConsumed) return exact
        // 세계관이 평명을 가져간 레거시 백업 — 밀려난 접미사 시트가 진짜 미분류 시트다.
        for (idx in 0 until workbook.numberOfSheets) {
            val name = workbook.getSheetName(idx)
            if (name in alreadyConsumed) continue
            if (isSuffixedVariantOf(name, UNCLASSIFIED_SHEET_NAME)) return workbook.getSheetAt(idx)
        }
        return null
    }

    private suspend fun buildColumnFieldMap(
        headerRow: Row,
        fields: List<FieldDefinition>,
        fixedColIndices: Set<Int>,
        universe: Universe?,
        result: ImportResult,
        sheetLabel: String
    ): Map<Int, FieldDefinition> {
        val map = mutableMapOf<Int, FieldDefinition>()
        val lastCol = headerRow.lastCellNum.toInt()
        var autoCreateCount = 0
        val maxOrder = fields.maxOfOrNull { it.displayOrder } ?: 0

        for (col in 0 until lastCol) {
            if (col in fixedColIndices) continue
            val headerName = getCellString(headerRow, col)
            if (headerName.isBlank()) continue
            val trimmedHeader = headerName.trim()
                .removeSuffix(EntityFieldHeaders.MULTI_SUFFIX)

            // 매칭 규약(안정 식별자 우선 → 자연키 폴백):
            // 0차: "이름(필드키)" 병기 헤더 — 내보내기가 충돌·동명 시 붙인다
            var field: FieldDefinition? = null
            val keyed = Regex("""^(.+)\((.+)\)$""").find(trimmedHeader)
            if (keyed != null) {
                val k = keyed.groupValues[2].trim()
                field = fields.find { it.key == k } ?: fields.find { it.key.equals(k, ignoreCase = true) }
            }
            // 1차: key 완전 일치 (사용자가 key를 헤더로 사용한 경우)
            if (field == null) field = fields.find { it.key == trimmedHeader }
            if (field == null) field = fields.find { it.key.equals(trimmedHeader, ignoreCase = true) }
            // 2차: name 일치 — **후보가 유일할 때만**. 동명 필드가 둘이면 근거 없이 고르지 않는다.
            if (field == null) {
                val byName = fields.filter { it.name == trimmedHeader }
                    .ifEmpty { fields.filter { it.name.equals(trimmedHeader, ignoreCase = true) } }
                when {
                    byName.size == 1 -> field = byName.first()
                    byName.size > 1 -> {
                        result.warnings.add(
                            "$sheetLabel: 열 '$trimmedHeader'과(와) 이름이 같은 필드가 ${byName.size}개 있어 어느 필드인지 확정할 수 없습니다 — 헤더를 '이름(필드키)' 형식으로 바꾸거나 앱에서 필드명을 구분해 주세요"
                        )
                        continue
                    }
                }
            }

            if (field != null) {
                map[col] = field
            } else if (universe != null) {
                // 매칭 실패 → 자동 필드 생성 (TEXT 타입)
                val baseKey = "auto_${trimmedHeader.lowercase().replace(Regex("[^a-z0-9가-힣_]"), "_")}"
                // key 충돌 방지
                var autoKey = baseKey
                var suffix = 1
                while (db.fieldDefinitionDao().getFieldByKey(universe.id, autoKey) != null) {
                    autoKey = "${baseKey}_${++suffix}"
                }
                val newField = FieldDefinition(
                    universeId = universe.id,
                    key = autoKey,
                    name = trimmedHeader,
                    type = FieldType.TEXT.name,
                    groupName = "자동 생성",
                    displayOrder = maxOrder + 1 + autoCreateCount++
                )
                val newId = db.fieldDefinitionDao().insert(newField)
                val created = newField.copy(id = newId)
                map[col] = created
                result.warnings.add("$sheetLabel: 컬럼 '$trimmedHeader' → TEXT 필드로 자동 생성됨")
                result.newFields++
            } else {
                // 미분류 캐릭터 시트: 세계관 없어 자동 생성 불가 → 경고만
                result.warnings.add("$sheetLabel: 컬럼 '$trimmedHeader'에 대한 필드 정의를 찾을 수 없어 무시됨")
            }
        }
        // 단사(injective) 보장: 한 필드에 2개 이상의 열이 붙으면 뒤 열을 버린다.
        // (동명 헤더가 든 기존 파일 보호 — 두 열이 같은 필드에 쓰이면 앞 열 값이 뒤 열 값으로 조용히 덮인다)
        val byField = map.entries.groupBy { it.value.id }
        for ((_, entries) in byField) {
            if (entries.size <= 1) continue
            val keep = entries.minByOrNull { it.key }!!
            for (e in entries) {
                if (e.key == keep.key) continue
                map.remove(e.key)
                result.errors.add(
                    "$sheetLabel: 열 ${e.key + 1}과(와) 열 ${keep.key + 1}이(가) 같은 필드 '${e.value.name}'에 대응해 열 ${e.key + 1}을(를) 무시했습니다 — 중복 헤더를 정리해 주세요"
                )
            }
        }
        return map
    }

    /**
     * 제목으로 작품 id를 해석한다. F3-C: 지정 세계관에 없으면 (1) 타 세계관의 동일 제목 작품을 재사용하고
     * 경고(유령 작품 중복 생성 방지), (2) 어디에도 없으면 새로 생성하되 그 사실을 경고(변수 제어 — 말없는 유령 생성 금지).
     * result/rowLabel이 null이면(구경로 호환) 경고 없이 기존 동작.
     */
    private suspend fun resolveNovelId(
        novelTitle: String,
        universeId: Long? = null,
        result: ImportResult? = null,
        rowLabel: String? = null
    ): Long? {
        if (novelTitle.isBlank()) return null
        val cacheKey = novelTitle to universeId
        novelIdCache[cacheKey]?.let { return it }
        // 1. 지정 세계관(또는 무소속)에서 조회
        val inScope = if (universeId != null) {
            db.novelDao().getNovelByTitleAndUniverse(novelTitle, universeId)
        } else {
            db.novelDao().getNovelByTitleNoUniverse(novelTitle)
        }
        if (inScope != null) {
            novelIdCache[cacheKey] = inScope.id
            return inScope.id
        }
        // 2. F3-C: 생성 전 타 세계관에서 동일 제목 조회 — 있으면 유령 중복 생성 대신 재사용 + 경고
        val elsewhere = db.novelDao().getNovelsByTitleList(novelTitle).firstOrNull()
        if (elsewhere != null) {
            result?.warnings?.add("${rowLabel ?: "작품"}: '$novelTitle'이(가) 지정 세계관에 없어 다른 세계관의 동일 제목 작품에 연결했습니다 — 세계관 지정을 확인하세요")
            novelIdCache[cacheKey] = elsewhere.id
            return elsewhere.id
        }
        // 3. 어디에도 없으면 새로 생성 + 경고 (말없는 유령 작품 생성 금지)
        val ghostNovel = Novel(title = novelTitle, universeId = universeId, code = generateEntityCode())
        val newId = db.novelDao().insert(ghostNovel)
        rememberNovel(ghostNovel.copy(id = newId))
        result?.warnings?.add("${rowLabel ?: "작품"}: 작품 '$novelTitle'을(를) 찾지 못해 새로 생성했습니다 — 오타·세계관 지정을 확인하세요")
        novelIdCache[cacheKey] = newId
        return newId
    }

    private suspend fun findCharacterByName(name: String, preferredNovelId: Long?): Character? {
        if (preferredNovelId != null) {
            val match = characterByNameAndNovel(name, preferredNovelId)
            if (match != null) return match
        }
        return characterByName(name)
    }

    /** 코드/이름 기반 캐릭터 조회 — 동명이인 모호성 감지 포함 */
    private sealed class CharLookupResult {
        data class Found(val character: Character) : CharLookupResult()
        data class Ambiguous(val count: Int) : CharLookupResult()
        data object NotFound : CharLookupResult()
    }

    private suspend fun findCharacterStrict(name: String, code: String): CharLookupResult {
        if (code.isNotBlank()) {
            val byCode = characterByCode(code)
            if (byCode != null) return CharLookupResult.Found(byCode)
        }
        if (name.isBlank()) return CharLookupResult.NotFound
        val matches = charactersByName(name)
        return when {
            matches.isEmpty() -> CharLookupResult.NotFound
            matches.size == 1 -> CharLookupResult.Found(matches[0])
            else -> CharLookupResult.Ambiguous(matches.size)
        }
    }

    /**
     * F3-B: 이름 기반 캐릭터 조회(동명이인 안전). 여러 명이면 preferredNovelId로 좁히고,
     * 그래도 모호하면 Ambiguous 반환(호출부가 경고 후 스킵). findCharacterByName(LIMIT 1)의
     * "조용히 아무나 선택" 문제를 대체한다 — 연표 참가자·이름은행 사용캐릭터처럼 코드 폴백이 없는 경로용.
     */
    // ── 자연키 (B-210) ──────────────────────────────────────────────────────────
    // 코드가 없는 구버전 파일의 폴백 경로가 쓰는 키다. **타입이 있는 `data class`로 짓는다** —
    // 손쉬워 보이는 `listOf(charId, year, …)`는 코틀린이 원소 타입을 첫 원소에서 추론해
    // 숫자 리터럴을 올려 버려, `Int` 칸에서 실은 키와 **영영 같지 않다.** 컴파일도 되고 예외도
    // 없어 **모든 조회가 빗나가는데도 조용하고**, 그러면 가져오기가 *"기존 행이 없다"*고 보고
    // 전부 새로 만든다(`ImportLookupIndexTest` ⑤가 그 실패를 붙잡아 둔다).

    private data class EventNaturalKey(val year: Int, val description: String)
    private data class StateChangeNaturalKey(
        val characterId: Long, val year: Int, val fieldKey: String, val newValue: String
    )
    private data class RelChangeNaturalKey(
        val relationshipId: Long, val year: Int, val month: Int?, val day: Int?
    )

    /**
     * 관계의 **쌍** 키 — 관계는 두 캐릭터에 함께 매달려 방향이 없다.
     * 작은 id를 앞에 두어 `(가, 나)`와 `(나, 가)`가 한 키가 된다(호출부가 그렇게 걸러 왔다).
     */
    private data class CharacterPairKey(val low: Long, val high: Long) {
        companion object {
            fun of(a: Long, b: Long) = CharacterPairKey(minOf(a, b), maxOf(a, b))
        }
    }

    // ── 캐릭터 정체성 색인 (B-210) ──────────────────────────────────────────────
    // 아래 넷이 대체하는 질의는 전부 `characters`를 **한 행씩** 물었다. 캐릭터를 코드·이름으로
    // 되찾는 시트가 다섯이라(캐릭터 · 캐릭터 필드값 · 연표 참가자 · 상태변화 · 이름 은행),
    // 목표 규모(×30)에서 그 합이 캐릭터 시트만으로도 6,420행 × 최대 2회다.
    // **표를 한 번 읽어 색인으로 답한다** — 갱신은 캐릭터를 쓰는 유일한 자리가 맡는다.

    /**
     * 색인을 아직 안 실었으면 한 번 싣는다.
     * **id 오름차순으로 싣는 것이 요점이다** — 대체하는 질의들이 `ORDER BY` 없는 `LIMIT 1`이라
     * 사실상 rowid가 작은 행을 주는데, `getAllCharactersList()`는 고정 · 표시순 · 이름순으로
     * 정렬돼 나온다. 그대로 실으면 **동명이인에서 합쳐지는 상대가 바뀐다.**
     */
    /** 색인을 비운다 — **가져오기·미리보기 한 번마다** 부른다(형제 상태들과 같은 규약). */
    private fun resetCharacterIndex() {
        characterCodes.reset()
        characterNames.reset()
        characterIndexLoaded = false
    }

    private suspend fun ensureCharacterIndex() {
        if (characterIndexLoaded) return
        characterIndexLoaded = true
        for (ch in db.characterDao().getAllCharactersList().sortedBy { it.id }) {
            characterCodes.put(ch)
            characterNames.put(ch)
        }
    }

    /**
     * 캐릭터를 썼다고 기록한다(insert·update 양쪽 — DB에 쓴 **그 행**을 넘길 것).
     * 이름·코드가 바뀌었으면 옛 키는 색인이 끊는다([ImportLookupIndex] 성질 3).
     */
    private fun rememberCharacter(character: Character) {
        characterCodes.put(character)
        characterNames.put(character)
    }

    /** `getCharacterByCode`의 자리. 빈 코드는 조회하지 않는다(호출부의 기존 규약과 같다). */
    private suspend fun characterByCode(code: String): Character? {
        if (code.isBlank()) return null
        ensureCharacterIndex()
        return characterCodes.first(code)
    }

    /** `getCharacterByName`(LIMIT 1)의 자리. */
    private suspend fun characterByName(name: String): Character? {
        ensureCharacterIndex()
        return characterNames.first(name)
    }

    /**
     * `getCharacterByNameAndNovel`(LIMIT 1)의 자리 —
     * SQL이 `(novelId = :novelId OR (:novelId IS NULL AND novelId IS NULL))`로 **null도 값으로**
     * 대조하므로 여기서도 그렇게 거른다.
     */
    private suspend fun characterByNameAndNovel(name: String, novelId: Long?): Character? {
        ensureCharacterIndex()
        return characterNames.all(name).firstOrNull { it.novelId == novelId }
    }

    /** `getAllCharactersByName`(LIMIT 없음)의 자리. */
    private suspend fun charactersByName(name: String): List<Character> {
        ensureCharacterIndex()
        return characterNames.all(name)
    }

    // ── 사건 정체성 색인 (B-210) ────────────────────────────────────────────────

    /** 색인을 비운다 — 가져오기·미리보기 한 번마다 부른다(캐릭터 축과 같은 규약). */
    private fun resetEventIndex() {
        eventCodes.reset()
        eventNaturalKeys.reset()
        eventsByEventId.reset()
        eventIndexLoaded = false
    }

    /** id 오름차순으로 싣는다 — 자연키가 겹치는 사건이 둘일 때 `LIMIT 1`과 같은 답을 내려면 그렇다. */
    private suspend fun ensureEventIndex() {
        if (eventIndexLoaded) return
        eventIndexLoaded = true
        for (event in db.timelineDao().getAllEventsList().sortedBy { it.id }) rememberEvent(event)
    }

    /** 사건을 썼다고 기록한다(insert·update 양쪽). 연도·설명·코드가 바뀌면 옛 키는 색인이 끊는다. */
    private fun rememberEvent(event: TimelineEvent) {
        eventCodes.put(event)
        eventNaturalKeys.put(event)
        eventsByEventId.put(event)
    }

    /** `getEventByCode`의 자리. */
    private suspend fun eventByCode(code: String): TimelineEvent? {
        if (code.isBlank()) return null
        ensureEventIndex()
        return eventCodes.first(code)
    }

    /** `getEventByNaturalKey`(LIMIT 1)의 자리. */
    private suspend fun eventByNaturalKey(year: Int, description: String): TimelineEvent? {
        ensureEventIndex()
        return eventNaturalKeys.first(EventNaturalKey(year, description))
    }

    /** `getEventById`의 자리. */
    private suspend fun eventById(id: Long): TimelineEvent? {
        ensureEventIndex()
        return eventsByEventId.first(id)
    }

    // ── 작품 코드 색인 (B-210) ──────────────────────────────────────────────────

    private fun resetNovelIndex() {
        novelCodes.reset()
        novelIndexLoaded = false
    }

    private suspend fun ensureNovelIndex() {
        if (novelIndexLoaded) return
        novelIndexLoaded = true
        for (novel in db.novelDao().getAllNovelsList().sortedBy { it.id }) novelCodes.put(novel)
    }

    /** 작품을 썼다고 기록한다 — 코드가 바뀌었으면 옛 코드는 색인이 끊는다. */
    private fun rememberNovel(novel: Novel) {
        novelCodes.put(novel)
    }

    /** `getNovelByCode`의 자리. */
    private suspend fun novelByCode(code: String): Novel? {
        if (code.isBlank()) return null
        ensureNovelIndex()
        return novelCodes.first(code)
    }

    /**
     * 작품 → 세계관. [universeIdOfCharacter]가 쓰던 메모를 **작품 단위로 갈라** 캐릭터를 거치지
     * 않는 자리도 쓰게 한 것이다 (B-210) — 캐릭터 시트의 세계관 이동 판정이 행마다 작품을
     * 두 번 읽고 있었는데, 같은 작품이 시트 안에서 되풀이된다(작품 510개에 캐릭터 6,420명).
     */
    private suspend fun universeIdOfNovel(novelId: Long): Long? {
        if (novelUniverseCache.containsKey(novelId)) return novelUniverseCache[novelId]
        val uid = db.novelDao().getNovelById(novelId)?.universeId
        novelUniverseCache[novelId] = uid
        return uid
    }

    private suspend fun resolveCharByNameNovel(name: String, preferredNovelId: Long?): CharLookupResult {
        if (name.isBlank()) return CharLookupResult.NotFound
        val matches = charactersByName(name)
        return when {
            matches.isEmpty() -> CharLookupResult.NotFound
            matches.size == 1 -> CharLookupResult.Found(matches[0])
            else -> {
                val narrowed = preferredNovelId?.let { nid -> matches.filter { it.novelId == nid } } ?: emptyList()
                if (narrowed.size == 1) CharLookupResult.Found(narrowed[0])
                else CharLookupResult.Ambiguous(matches.size)
            }
        }
    }

    /**
     * 캐릭터가 속한 세계관 id (작품→세계관). 미분류 캐릭터(작품 없음)는 null.
     * 세력 참조가 동명일 때의 타이브레이커로만 쓰인다 — null 이어도 해석을 포기하지 않는다.
     */
    private suspend fun universeIdOfCharacter(character: Character): Long? {
        val novelId = character.novelId ?: return null
        return universeIdOfNovel(novelId)
    }

    /** 세력코드가 적혀 있는데 못 찾아 이름으로 폴백한 경우 고지 (importFactions 와 같은 규약) */
    private fun warnFactionCodeFallback(
        rowLabel: String, code: String, r: FactionLookupResult.Found, name: String, result: ImportResult
    ) {
        if (code.isNotBlank() && !r.matchedByCode) {
            result.nameBasedMappings++
            result.warnings.add("$rowLabel: 세력코드 '$code'를 찾지 못해 이름 '$name'으로 매칭했습니다 — '세력' 시트를 함께 가져왔는지 확인하세요")
        }
    }

    /**
     * 해석된 세력이 대상(캐릭터)의 세계관과 다르면 고지한다.
     * 앱은 교차 세계관 소속을 무효로 취급하며(deleteMembershipsNotInUniverse), 세계관 이동 시
     * 조용히 삭제한다 — 무음으로 만들지 않고 알린다. 행은 버리지 않는다(조작 마찰 최소화).
     */
    private fun warnFactionUniverseMismatch(
        rowLabel: String, faction: Faction, hintUniverseId: Long?, subject: String,
        universeNames: Map<Long, String>, result: ImportResult
    ) {
        if (hintUniverseId == null || faction.universeId == hintUniverseId) return
        result.warnings.add(
            "$rowLabel: 세력 '${faction.name}'(세계관 ${universeNames[faction.universeId] ?: "?"})이 " +
            "$subject(세계관 ${universeNames[hintUniverseId] ?: "?"})와(과) 다른 세계관입니다 — " +
            "그대로 저장하지만 세계관 이동 시 이 소속은 제거됩니다. '세력코드' 열로 확정하세요"
        )
    }

    /**
     * Tolerant number parsing (Sprint C): handles "12", "12.0", " 12 ", etc.
     */
    private fun parseNumber(value: String): Double? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return trimmed.toDoubleOrNull()
    }

    /**
     * 관대한 불리언 파싱 — 판정 규칙의 단일 소스는 [parseSheetBoolean](SheetSpec.kt)이다.
     *
     * **빈칸은 false(비움 의도)다.** 불리언 열에서 "열 없음"을 표현하려면 파서가 아니라 호출부에서
     * `sheetBooleanOrKeep(colIndex >= 0, cell)` 형태로 구분한다 — 빈칸을 null로 접는 변형을 만들면
     * 같은 이름의 드롭다운 열이 시트에 따라 반대로 동작한다(F1-A 단일 규약).
     */
    private fun parseBoolean(value: String): Boolean = parseSheetBoolean(value)

    /**
     * F4: 코드 셀 방어 읽기. 코드는 항상 텍스트여야 하나, 외부 편집 중 엑셀이 숫자로 자동 변환하면
     * 정밀도 손실·지수표기로 매칭이 조용히 어긋난다. 숫자 셀이면 경고하고 문자열로 읽는다(변수 제어).
     */
    private fun getCellCode(row: Row, cellIndex: Int, rowLabel: String, result: ImportResult?): String {
        if (cellIndex < 0) return ""
        val cell = row.getCell(cellIndex) ?: return ""
        if (cell.cellType == CellType.NUMERIC) {
            result?.warnings?.add("$rowLabel: 코드가 숫자 형식으로 저장되어 있습니다 — 코드 열은 수정하지 마세요(정밀도 손실로 매칭이 어긋날 수 있습니다)")
        }
        return getCellString(row, cellIndex)
    }

    /**
     * F4: JSON 문자열이 파싱 가능한지 검증한다 (내보내기 32,767자 절단·외부 편집 구문 오류 감지용).
     * 빈 문자열은 유효로 본다. object/array만 최상위로 허용.
     */
    private fun isValidJson(value: String, requireTop: Char? = null): Boolean {
        val t = value.trim()
        if (t.isEmpty()) return true
        val top = t.first()
        // 배열을 기대하는 열에 객체가 들어와도 소비처는 예외를 삼키고 기본값으로 돌아간다 —
        // "파싱은 되지만 소비처가 못 읽는" 값을 유효로 판정하면 구멍이 남는다.
        if (requireTop != null && top != requireTop) return false
        return try {
            when (top) {
                '{' -> { org.json.JSONObject(t); true }
                '[' -> { org.json.JSONArray(t); true }
                else -> false
            }
        } catch (_: Exception) { false }
    }

    /**
     * 세계관 '커스텀관계유형' 셀 정규화.
     * 소비처(Universe.getRelationshipTypes)가 파싱 실패를 무음으로 삼키고 기본 유형으로 돌아가므로
     * 가져오기가 형식을 확인해 해석하거나 경고한다. 검증은 소비처와 같은 org.json 파서를 쓴다.
     *
     * @return null = 이 셀로는 설정을 복원할 수 없음 → 기존 값 유지
     *         (해석 불가 입력이 유효 설정을 파괴하지 않게 — 덮어쓰기 대원칙)
     */
    private fun normalizeRelTypesCell(raw: String, rowLabel: String, universeName: String, result: ImportResult?): String? {
        if (raw.isBlank()) return ""                      // F1-A: 열 있음 + 빈칸 = 비움 의도 존중
        if (isValidJson(raw, '[')) return raw
        val tokens = parseRelTypeTokens(raw)
        if (tokens.isNotEmpty()) {
            result?.warnings?.add(
                "$rowLabel: 세계관 '$universeName'의 커스텀관계유형이 JSON 배열 형식이 아니어서 쉼표 구분으로 해석했습니다(${tokens.size}개: ${tokens.joinToString("/")}) — 정확한 형식은 [\"연인\",\"라이벌\"] 입니다"
            )
            return org.json.JSONArray(tokens).toString()
        }
        result?.warnings?.add(
            "$rowLabel: 세계관 '$universeName'의 커스텀관계유형 '${raw.take(40)}'을(를) 해석할 수 없어 적용하지 않고 기존 설정을 유지했습니다 — 형식은 [\"연인\",\"라이벌\"] 또는 쉼표 구분(연인, 라이벌)입니다. 비우면 기본 유형으로 돌아갑니다"
        )
        return null
    }

    /**
     * 세계관 '커스텀관계색상' 셀 정규화. 규칙은 [normalizeRelTypesCell]과 동형이며
     * 소비처는 Universe.getRelationshipColorMap이다.
     */
    private fun normalizeRelColorsCell(raw: String, rowLabel: String, universeName: String, result: ImportResult?): String? {
        if (raw.isBlank()) return ""
        if (isValidJson(raw, '{')) return raw
        val pairs = parseRelColorTokens(raw)
        if (pairs.isNotEmpty()) {
            val obj = org.json.JSONObject()
            pairs.forEach { (k, v) -> obj.put(k, v) }
            result?.warnings?.add(
                "$rowLabel: 세계관 '$universeName'의 커스텀관계색상이 JSON 객체 형식이 아니어서 '유형=색상' 목록으로 해석했습니다(${pairs.size}개) — 정확한 형식은 {\"연인\":\"#E91E63\"} 입니다"
            )
            return obj.toString()
        }
        result?.warnings?.add(
            "$rowLabel: 세계관 '$universeName'의 커스텀관계색상 '${raw.take(40)}'을(를) 해석할 수 없어 적용하지 않고 기존 설정을 유지했습니다 — 형식은 {\"연인\":\"#E91E63\"} 또는 '연인=#E91E63' 쉼표 나열입니다. 비우면 기본 색상으로 돌아갑니다"
        )
        return null
    }

    /** 월에 맞는 유효한 일수인지 검증 (월이 null이면 1..31 범위만 체크) */
    private fun isValidDay(month: Int?, day: Int): Boolean {
        if (month == null) return day in 1..31
        val maxDay = when (month) {
            2 -> 29  // 윤년 가능성 허용
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..maxDay
    }

    // ── F1-B: 범위 밖 값을 조용히 버리지 않고 연도처럼 경고하는 공용 파서 (변수 제어) ──

    /** 월 파싱 + 범위 경고: 열 없음/빈 셀=null(정상), 해석 불가·1~12 밖=경고 후 null */
    private fun parseMonthWithWarn(row: Row, colIndex: Int, rowLabel: String, result: ImportResult?): Int? {
        if (colIndex < 0) return null
        val raw = getCellString(row, colIndex)
        if (raw.isBlank()) return null
        val month = parseNumber(raw)?.toInt()?.takeIf { it in 1..12 }
        if (month == null) {
            result?.warnings?.add("$rowLabel: 월 '$raw'을(를) 1~12 범위의 숫자로 해석할 수 없어 무시됨")
        }
        return month
    }

    /** 일 파싱 + 유효성 경고: 열 없음/빈 셀=null(정상), 1~31·월별 일수 밖=경고 후 null */
    private fun parseDayWithWarn(row: Row, colIndex: Int, month: Int?, rowLabel: String, result: ImportResult?): Int? {
        if (colIndex < 0) return null
        val raw = getCellString(row, colIndex)
        if (raw.isBlank()) return null
        val day = parseNumber(raw)?.toInt()?.takeIf { d -> d in 1..31 && isValidDay(month, d) }
        if (day == null) {
            result?.warnings?.add("$rowLabel: 일 '$raw'이(가) 유효하지 않아(1~31·월별 일수) 무시됨")
        }
        return day
    }

    /** 강도 파싱 + 범위 경고: 열 없음/빈 셀=default, 해석 불가=경고 후 default, 1~10 밖=클램프 후 경고 */
    /**
     * [result]가 null이면 **고지 없이 같은 값만** 낸다 — 복원 미리보기 분석이 쓰는 경로다.
     * 분석이 따로 파싱하면 가져오기와 강도가 갈려 '동일' 판정이 어긋난다(B-87).
     */
    private fun parseIntensityWithWarn(row: Row, colIndex: Int, default: Int?, rowLabel: String, result: ImportResult?): Int? {
        if (colIndex < 0) return default
        val raw = getCellString(row, colIndex)
        if (raw.isBlank()) return default
        val parsed = parseNumber(raw)?.toInt()
        if (parsed == null) {
            result?.warnings?.add("$rowLabel: 강도 '$raw'을(를) 숫자로 해석할 수 없어 기본값 적용")
            return default
        }
        val clamped = parsed.coerceIn(1, 10)
        if (clamped != parsed) {
            result?.warnings?.add("$rowLabel: 강도 $parsed 이(가) 범위(1~10)를 벗어나 ${clamped}(으)로 조정됨")
        }
        return clamped
    }

    /**
     * @param dateHint true이면 숫자 셀을 적극적으로 날짜 변환 시도 (생일 등 날짜 필드용)
     */
    private fun getCellString(row: Row, cellIndex: Int, maxLength: Int = MAX_FIELD_LENGTH, dateHint: Boolean = false): String {
        if (cellIndex < 0) return ""
        val cell = row.getCell(cellIndex)
        // 값 정규화는 ExcelCellValue(단일 소스)에 위임한다 — 그리고 **여기가 그 자리다.**
        // 호출부가 정한 dateHint를 아는 곳이 여기뿐이므로, 리더가 미리 문자열로 굳히면
        // 같은 셀이 경로에 따라 다른 값이 된다(B-8). DOM·스트리밍 어느 쪽이든 primitives()가
        // 같은 원시값을 주므로 결과가 같다 — 값 왜곡을 구조적으로 차단.
        var raw = if (cell != null) ExcelCellValue.normalize(cell.primitives(), dateHint) else ""
        if (raw.isEmpty()) {
            // 병합 셀의 피복 칸(좌상단이 아닌 칸)은 파일에서 빈 셀/셀 없음으로 읽히지만,
            // 스프레드시트 화면에서는 좌상단 값이 걸쳐 보였다. 빈칸으로 두면 덮어쓰기의
            // '빈칸=삭제' 규약에 걸려 무통보 유실이 되므로 좌상단 값으로 해석하고
            // 건수를 모아 경고로 고지한다 (B-7: 감지 → 경고 → 교정 안내).
            mergedTopLeftValue(row, cellIndex, dateHint)?.let { raw = it }
        }
        if (raw.length > maxLength) {
            truncatedFieldCount++
            val sheetName = row.sheet?.sheetName ?: "?"
            truncatedDetails.add("${sheetName} 행${row.rowNum + 1} 열${cellIndex + 1}")
            return raw.substring(0, maxLength)
        }
        return raw
    }

    /**
     * (행, 열)이 병합 범위의 피복 칸이면 좌상단 값을 돌려주고 적용 건수를 센다 (B-7).
     *
     * 헤더 행(0행)은 제외한다 — 헤더를 채우면 같은 이름이 여러 열에 복제되어
     * [resolveHeaderColumns]의 열 배정이 밀리고, 그것은 유실보다 나쁜 오배정이다.
     * 좌상단 값이 그 자체로 빈 값이면 채울 것이 없으므로 null (집계하지 않음).
     */
    private fun mergedTopLeftValue(row: Row, cellIndex: Int, dateHint: Boolean): String? {
        if (row.rowNum == 0) return null
        val sheet = row.sheet ?: return null
        // 두 경로 모두 getMergedRegion이 이미 MergedCellMap.Region을 준다
        // (DOM은 POI CellRangeAddress를, 스트리밍은 시트 XML의 mergeCells를 같은 형태로 옮긴다).
        val map = mergedCellMaps.getOrPut(sheet) {
            MergedCellMap((0 until sheet.numMergedRegions).map { i -> sheet.getMergedRegion(i) })
        }
        if (map.isEmpty || !map.isCoveredCell(row.rowNum, cellIndex)) return null
        val tl = map.topLeftOf(row.rowNum, cellIndex) ?: return null
        val tlCell = sheet.getRow(tl.row)?.getCell(tl.column) ?: return null
        val value = ExcelCellValue.normalize(tlCell.primitives(), dateHint)
        if (value.isEmpty()) return null
        val name = sheet.sheetName
        // 구분자 ':'는 엑셀 시트명 금지 문자 — 시트명과 좌표가 섞여 다른 셀이 같은 키가 되지 않는다
        if (mergedFilledCells.add("$name:${row.rowNum}:$cellIndex")) {
            mergedFilledBySheet[name] = (mergedFilledBySheet[name] ?: 0) + 1
        }
        return value
    }

    // 날짜 감지·포맷·숫자 정규화는 ExcelCellValue(단일 소스)로 이관됨(로직 비분기).
    // 기존 isCellLikelyDate/formatDateCell는 getCellString이 위임하면서 제거되었다.

    /**
     * 임포트 후 시맨틱 필드 동기화.
     * 필드값 → 상태변화, 상태변화 → 필드값 양방향 동기화 수행.
     */
    // ── Deferred FK 해석 (코드 기반) ──

    // 미해석 코드는 조용히 버리지 않는다 — Phase 1에서 참조를 이미 null로 지운 뒤라
    // 여기서 무음 continue하면 기존 대표 이미지 설정이 경고 없이 사라진다(변수 제어).
    private suspend fun applyDeferredUniverseNovelRefs(result: ImportResult) {
        for ((universeId, novelCode) in deferredUniverseImageNovelCodes) {
            val universe = db.universeDao().getUniverseById(universeId) ?: continue
            val novelId = novelByCode(novelCode)?.id
            if (novelId == null) {
                result.warnings.add("세계관 '${universe.name}': 이미지 작품코드 '$novelCode'에 해당하는 작품이 없어 대표 이미지 연동이 해제되었습니다")
                continue
            }
            db.universeDao().update(universe.copy(imageNovelId = novelId))
        }
        deferredUniverseImageNovelCodes.clear()
    }

    private suspend fun applyDeferredCharacterRefs(result: ImportResult) {
        for ((novelId, charCode) in deferredNovelImageCharCodes) {
            val novel = db.novelDao().getNovelById(novelId) ?: continue
            val charId = characterByCode(charCode)?.id
            if (charId == null) {
                result.warnings.add("작품 '${novel.title}': 이미지 캐릭터코드 '$charCode'에 해당하는 캐릭터가 없어 대표 이미지 연동이 해제되었습니다")
                continue
            }
            db.novelDao().update(novel.copy(imageCharacterId = charId))
        }
        deferredNovelImageCharCodes.clear()

        for ((universeId, charCode) in deferredUniverseImageCharCodes) {
            val universe = db.universeDao().getUniverseById(universeId) ?: continue
            val charId = characterByCode(charCode)?.id
            if (charId == null) {
                result.warnings.add("세계관 '${universe.name}': 이미지 캐릭터코드 '$charCode'에 해당하는 캐릭터가 없어 대표 이미지 연동이 해제되었습니다")
                continue
            }
            db.universeDao().update(universe.copy(imageCharacterId = charId))
        }
        deferredUniverseImageCharCodes.clear()
    }

    // ── 엑셀에 없는 항목 삭제 ──

    private suspend fun deleteUnmatchedEntities(options: ExportOptions, result: ImportResult) {
        val del = options.deleteOptions

        // 캐릭터: 실제로 처리한 시트의 범위 내에서, 엑셀이 인지하지 못한 캐릭터만.
        // 보호집합은 전역(matchedCharacterIds)이라 세계관을 이동한 캐릭터도 삭제되지 않는다.
        if (del.characters) {
            val candidates = LinkedHashSet<Long>()
            for (universeId in importedCharacterSheetUniverseIds) {
                candidates.addAll(db.characterDao().getCharacterIdsByUniverse(universeId))
            }
            if (unclassifiedSheetImported) {
                candidates.addAll(db.characterDao().getUnclassifiedCharacterIds())
            }
            val doomed = candidates.filter { it !in matchedCharacterIds }
            if (doomed.isNotEmpty()) {
                // 인앱 삭제와 동일 경로 — 휴지통 스냅샷을 남겨 되돌릴 수 있게 한다(무통보 영구 삭제 금지).
                CharacterRepository.deleteCharactersCascade(db, trashForImport(), doomed)
                result.deletedCharacters += doomed.size
                result.warnings.add("엑셀에 없는 캐릭터 ${doomed.size}명을 삭제했습니다 — 휴지통에서 복구할 수 있습니다")
            }
        }

        // 사건 연표 — 인앱 삭제와 동일 경로로 휴지통 스냅샷을 남긴다 (B-1).
        // 종전에는 사건 필드값·참가 캐릭터/작품 연결·관계변화의 사건 연결이 무통보 영구 삭제였다.
        if (del.timeline && matchedEventIds.isNotEmpty()) {
            val trash = trashForImport()
            val allIds = db.timelineDao().getAllEventIds()
            val doomed = allIds.filter { it !in matchedEventIds }
            for (chunk in doomed.chunked(IN_CLAUSE_CHUNK)) {
                for (event in db.timelineDao().getEventsByIds(chunk)) {
                    try {
                        trash.snapshotEvent(event)
                        db.timelineDao().deleteById(event.id)
                        result.deletedEvents++
                    } catch (e: Exception) {
                        result.warnings.add("사건 '${event.description}' 삭제에 실패해 건너뛰었습니다: ${e.message}")
                    }
                }
            }
            if (result.deletedEvents > 0) {
                result.warnings.add("엑셀에 없는 사건 ${result.deletedEvents}건을 삭제했습니다 — 휴지통에서 복구할 수 있습니다")
            }
        }

        // 관계
        if (del.relationships && matchedRelationshipIds.isNotEmpty()) {
            val allIds = db.characterRelationshipDao().getAllRelationshipIds()
            for (id in allIds) {
                if (id !in matchedRelationshipIds) {
                    try { db.characterRelationshipDao().deleteById(id); result.deletedRelationships++ }
                    catch (_: Exception) { }
                }
            }
        }

        // 관계 변화
        if (del.relationshipChanges && matchedRelationshipChangeIds.isNotEmpty()) {
            val allIds = db.characterRelationshipChangeDao().getAllChangeIds()
            for (id in allIds) {
                if (id !in matchedRelationshipChangeIds) {
                    try { db.characterRelationshipChangeDao().deleteById(id); result.deletedRelationshipChanges++ }
                    catch (_: Exception) { }
                }
            }
        }

        // 상태 변화
        if (del.stateChanges && matchedStateChangeIds.isNotEmpty()) {
            val allIds = db.characterStateChangeDao().getAllChangeIds()
            for (id in allIds) {
                if (id !in matchedStateChangeIds) {
                    try { db.characterStateChangeDao().deleteById(id); result.deletedStateChanges++ }
                    catch (_: Exception) { }
                }
            }
        }

        // 이름 은행
        if (del.nameBank && matchedNameBankIds.isNotEmpty()) {
            val allIds = db.nameBankDao().getAllEntryIds()
            for (id in allIds) {
                if (id !in matchedNameBankIds) {
                    try { db.nameBankDao().deleteById(id); result.deletedNameBank++ }
                    catch (_: Exception) { }
                }
            }
        }

        // 세력 관계
        if (del.factionRelationships && matchedFactionRelationshipIds.isNotEmpty()) {
            val allIds = db.factionRelationshipDao().getAllRelationshipIds()
            for (id in allIds) {
                if (id !in matchedFactionRelationshipIds) {
                    try { db.factionRelationshipDao().deleteById(id); result.deletedFactionRelationships++ }
                    catch (_: Exception) { }
                }
            }
        }

        // 세력 — 인앱 삭제와 동일 경로로 휴지통 스냅샷을 남긴다 (B-1).
        // 종전에는 세력 소속·세력 간 관계가 무통보 영구 삭제였고, 자동 관계의 '세력' 지정도
        // SET_NULL로 조용히 끊겼다.
        if (del.factions && matchedFactionIds.isNotEmpty()) {
            val trash = trashForImport()
            val allIds = db.factionDao().getAllFactionIds()
            val doomed = allIds.filter { it !in matchedFactionIds }
            // **전부 스냅샷한 뒤 전부 삭제한다.** 하나씩 스냅샷→삭제하면, 먼저 지운 세력의
            // FK CASCADE가 세력 간 관계 행을 이미 없애 버려 두 번째 세력의 payload에는
            // 그 관계가 담기지 않는다(faction_relationships에는 code가 없어 그 행이 유일본이다).
            val doomedFactions = doomed.chunked(IN_CLAUSE_CHUNK).flatMap { db.factionDao().getByIds(it) }
            for (faction in doomedFactions) {
                try {
                    // 세력만 지우므로 관계는 살아남고 factionId만 null이 된다(SET_NULL).
                    trash.snapshotFaction(faction, deleteRelationships = false)
                } catch (e: Exception) {
                    result.warnings.add("세력 '${faction.name}' 백업에 실패해 삭제하지 않았습니다: ${e.message}")
                }
            }
            for (faction in doomedFactions) {
                try {
                    db.factionDao().deleteById(faction.id)
                    result.deletedFactions++
                } catch (e: Exception) {
                    result.warnings.add("세력 '${faction.name}' 삭제에 실패해 건너뛰었습니다: ${e.message}")
                }
            }
            if (result.deletedFactions > 0) {
                result.warnings.add("엑셀에 없는 세력 ${result.deletedFactions}개를 삭제했습니다 — 휴지통에서 복구할 수 있습니다")
            }
        }

        // 세력 소속
        if (del.factionMemberships && matchedFactionMembershipIds.isNotEmpty()) {
            val allIds = db.factionMembershipDao().getAllMembershipIds()
            for (id in allIds) {
                if (id !in matchedFactionMembershipIds) {
                    try { db.factionMembershipDao().deleteById(id); result.deletedFactionMemberships++ }
                    catch (_: Exception) { }
                }
            }
        }
    }

    /**
     * U-12b — 삭제 옵션이 꺼진 채 병합했을 때 **남겨 둔** 항목을 센다(지우지 않는다).
     *
     * [deleteUnmatchedEntities]와 **같은 후보 판정**을 쓴다. 두 곳이 다른 규칙을 쓰면
     * "남은 것 없음"이라 말한 뒤 옵션을 켜면 뭔가 지워지는 모순이 생긴다.
     * 시트를 실제로 처리한 종류만 센다 — 시트가 아예 없었으면 '엑셀에 없던 항목'이라는 말 자체가
     * 성립하지 않고, 손대지 않은 데이터를 남았다고 세면 거짓 고지가 된다.
     *
     * 삭제(옵션 켜짐) **이후에** 부른다: 켜진 종류의 삭제가 연쇄로 지운 것까지 반영된 최종 상태를 센다.
     */
    /**
     * 요약 문구의 이름과 세는 단위 — 단위가 종류마다 다르므로 함께 든다 (R-13).
     * 목적격 조사도 함께 드는 이유는 받침이 단위를 따라가기 때문이다(9개**를** / 214명**을**).
     * 조사를 문자열에 박아 두면 한쪽이 반드시 틀린다.
     */
    private val wipedCategoryLabels = mapOf(
        "universes" to Triple("세계관", "개", "를"),
        "novels" to Triple("작품", "개", "를"),
        "characters" to Triple("캐릭터", "명", "을"),
        "factions" to Triple("세력", "개", "를")
    )

    /**
     * "코드가 기존에 없어 새로 생성됨" 고지.
     *
     * 병합에서는 **행마다** 알린다 — 앱에서 지운 항목이 옛 파일로 되살아나는 것을 잡는 자리라
     * 어느 행·어느 코드인지가 곧 값어치다. 덮어쓰기로 방금 비운 범주에서는 세기만 하고
     * [reportCreatedAfterWipe]가 범주당 한 줄로 요약한다(사유는 [wipedByOverwrite] 참조).
     */
    private fun warnCreatedNewByCode(category: String, message: String, result: ImportResult) {
        if (category in wipedByOverwrite) {
            createdAfterWipe[category] = (createdAfterWipe[category] ?: 0) + 1
            return
        }
        result.warnings.add(message)
    }

    /**
     * 덮어쓰기로 비운 범주에서 새로 만든 건수를 범주당 한 줄로 고지한다.
     * **무음이 아니라 요약이다** — 몇 개가 어떻게 들어왔는지는 그대로 말하되,
     * 확인을 요구하지 않는다(사용자가 확인할 것이 없는 사실이기 때문이다).
     */
    private fun reportCreatedAfterWipe(result: ImportResult) {
        for ((category, count) in createdAfterWipe) {
            if (count <= 0) continue
            val (label, unit, particle) = wipedCategoryLabels[category] ?: Triple(category, "개", "를")
            result.warnings.add(
                "덮어쓰기: $label $count$unit$particle 백업의 코드 그대로 새로 만들었습니다 — " +
                "덮어쓰기가 먼저 지운 자리라 확인하실 것이 없습니다"
            )
        }
    }

    private suspend fun countKeptUnmatchedEntities(options: ExportOptions, result: ImportResult) {
        val del = options.deleteOptions
        val parts = mutableListOf<String>()
        fun note(count: Int, label: String) {
            if (count > 0) {
                result.keptNotInExcel += count
                parts.add(label)
            }
        }

        if (!del.characters) {
            val candidates = LinkedHashSet<Long>()
            for (universeId in importedCharacterSheetUniverseIds) {
                candidates.addAll(db.characterDao().getCharacterIdsByUniverse(universeId))
            }
            if (unclassifiedSheetImported) {
                candidates.addAll(db.characterDao().getUnclassifiedCharacterIds())
            }
            val n = candidates.count { it !in matchedCharacterIds }
            note(n, "캐릭터 ${n}명")
        }
        if (!del.timeline && matchedEventIds.isNotEmpty()) {
            val n = db.timelineDao().getAllEventIds().count { it !in matchedEventIds }
            note(n, "사건 ${n}건")
        }
        if (!del.relationships && matchedRelationshipIds.isNotEmpty()) {
            val n = db.characterRelationshipDao().getAllRelationshipIds().count { it !in matchedRelationshipIds }
            note(n, "관계 ${n}건")
        }
        if (!del.relationshipChanges && matchedRelationshipChangeIds.isNotEmpty()) {
            val n = db.characterRelationshipChangeDao().getAllChangeIds().count { it !in matchedRelationshipChangeIds }
            note(n, "관계 변화 ${n}건")
        }
        if (!del.stateChanges && matchedStateChangeIds.isNotEmpty()) {
            val n = db.characterStateChangeDao().getAllChangeIds().count { it !in matchedStateChangeIds }
            note(n, "상태 변화 ${n}건")
        }
        if (!del.nameBank && matchedNameBankIds.isNotEmpty()) {
            val n = db.nameBankDao().getAllEntryIds().count { it !in matchedNameBankIds }
            note(n, "이름 은행 ${n}개")
        }
        if (!del.factions && matchedFactionIds.isNotEmpty()) {
            val n = db.factionDao().getAllFactionIds().count { it !in matchedFactionIds }
            note(n, "세력 ${n}개")
        }
        if (!del.factionMemberships && matchedFactionMembershipIds.isNotEmpty()) {
            val n = db.factionMembershipDao().getAllMembershipIds().count { it !in matchedFactionMembershipIds }
            note(n, "세력 소속 ${n}건")
        }
        if (!del.factionRelationships && matchedFactionRelationshipIds.isNotEmpty()) {
            val n = db.factionRelationshipDao().getAllRelationshipIds().count { it !in matchedFactionRelationshipIds }
            note(n, "세력 관계 ${n}건")
        }

        if (parts.isEmpty()) return
        // 옵션을 켜라고 **권하지 않는다** — 기본값(안 지움)이 안전한 기본값이고, 여기서는
        // 무슨 일이 있었는지와 어디서 정할 수 있는지만 말한다.
        result.warnings.add(
            "엑셀에 없던 항목 ${result.keptNotInExcel}개는 삭제하지 않고 그대로 두었습니다(${parts.joinToString(" · ")}) — " +
            "가져오기의 기본 동작입니다. 삭제 여부는 가져오기 옵션의 '엑셀에 없는 항목 삭제'에서 정할 수 있습니다"
        )
    }

    private suspend fun runPostImportSemanticSync() {
        val characterRepository = CharacterRepository(
            db, db.characterDao(), db.characterFieldValueDao(),
            db.characterStateChangeDao(), db.characterTagDao(),
            db.characterRelationshipDao(), db.nameBankDao()
        )
        val universeRepository = UniverseRepository(
            db, db.universeDao(), db.fieldDefinitionDao(), db.novelDao()
        )
        val novelRepository = NovelRepository(db, db.novelDao())
        val syncHelper = SemanticFieldSyncHelper(characterRepository, universeRepository, novelRepository)

        for ((characterId, universeId) in pendingSyncCharacters) {
            try {
                val fieldValues = db.characterFieldValueDao().getValuesByCharacterList(characterId)
                syncHelper.syncFieldToStateChange(characterId, universeId, fieldValues)
            } catch (e: Exception) {
                android.util.Log.w("ExcelImport", "Post-import sync failed for character $characterId", e)
            }
        }
    }

    private fun labelToEventType(label: String): String = when (label.trim()) {
        "탄생", "birth" -> TimelineEvent.TYPE_BIRTH
        "사망", "death" -> TimelineEvent.TYPE_DEATH
        else -> TimelineEvent.TYPE_NONE
    }

    companion object {
        // 가져오기 저장 한도 = 내보내기 절단 한도 (SheetSpec 단일 소스).
        // 값이 어긋나면 순수 왕복만으로 데이터가 잘리므로 별도 값을 두지 않는다.
        private const val MAX_FIELD_LENGTH = EXCEL_CELL_TEXT_LIMIT
        // 시트명 상수는 SheetSpec.kt가 단일 소스다 (내보내기도 같은 상수를 본다).

        /** IN 절 변수 한도 회피용 청크 크기 (저장소 공통 관례와 동일) */
        private const val IN_CLAUSE_CHUNK = 900
    }
}

