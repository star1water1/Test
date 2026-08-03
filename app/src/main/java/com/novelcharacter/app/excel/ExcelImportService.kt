package com.novelcharacter.app.excel

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FactionRelationship
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
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

data class CategoryAnalysis(
    val key: String,
    val label: String,
    val inBackup: Int,
    val newCount: Int,
    val updateCount: Int,
    val unchangedCount: Int,
    val existingTotal: Int
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
    // 이번 가져오기에서 세계관이 바뀐 캐릭터 — 필드값이 새 세계관 필드로 재매핑되었으므로
    // 옛 세계관 키를 담은 오버플로 행을 적용하면 방금 정리한 값이 되살아난다.
    private val universeMovedCharacterIds = mutableSetOf<Long>()
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
        result: ImportResult
    ): Character {
        val paths = com.novelcharacter.app.util.CharacterRepresentativeImage.paths(character.imagePaths)
        return when (val r = com.novelcharacter.app.util.RepresentativeImageCell.resolve(cell, paths, imagePathRemap)) {
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Absent -> character
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Cleared ->
                character.copy(representativeImagePath = "")
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Matched ->
                character.copy(representativeImagePath = r.path)
            is com.novelcharacter.app.util.RepresentativeImageCell.Resolution.Unresolved -> {
                result.warnings.add(
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
            universeMovedCharacterIds.clear()
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
            if (effectiveOptions.novels) importNovels(workbook, result, onProgress, totalRows)
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
            if (effectiveOptions.timeline) importTimeline(workbook, result, onProgress, totalRows)
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

            // 덮어쓰기로 비운 범주의 신규 생성은 여기서 한 줄로 요약한다(행마다 알리지 않는다).
            reportCreatedAfterWipe(result)

            // Phase 5: 엑셀에 없는 항목 삭제 (MERGE + deleteOptions)
            if (strategy == ImportStrategy.MERGE) {
                if (effectiveOptions.deleteOptions.hasAny) {
                    deleteUnmatchedEntities(effectiveOptions, result)
                }
                // U-12b: 꺼진 종류는 남겨 뒀다는 사실을 그 자리에서 고지한다(삭제 뒤 최종 상태 기준).
                countKeptUnmatchedEntities(effectiveOptions, result)
            }

            // Phase 6: 시맨틱 필드 동기화 (출생/사망연도 ↔ 상태변화 ↔ 생존여부)
            if (pendingSyncCharacters.isNotEmpty()) {
                runPostImportSemanticSync()
            }
        }

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
        val totalRows = countTotalRows(workbook)

        if (options.universes) categories.add(analyzeUniverses(workbook, onProgress, totalRows))
        if (options.novels) categories.add(analyzeNovels(workbook, onProgress, totalRows))
        if (options.fieldDefinitions) {
            categories.add(analyzeGradeSystems(workbook, onProgress, totalRows))
            categories.add(analyzeFieldDefinitions(workbook, onProgress, totalRows))
            categories.add(analyzeFieldValueLibrary(workbook, onProgress, totalRows))
        }
        if (options.characters) {
            val charResult = analyzeCharacters(workbook, onProgress, totalRows)
            categories.add(charResult.category)
            characterConflicts = charResult.conflicts
        }
        if (options.timeline) categories.add(analyzeTimeline(workbook, onProgress, totalRows))
        if (options.stateChanges) categories.add(analyzeStateChanges(workbook, onProgress, totalRows))
        if (options.relationships) categories.add(analyzeRelationships(workbook, onProgress, totalRows))
        if (options.relationshipChanges) categories.add(analyzeRelationshipChanges(workbook, onProgress, totalRows))
        if (options.nameBank) categories.add(analyzeNameBank(workbook, onProgress, totalRows))
        if (options.factions) categories.add(analyzeFactions(workbook, onProgress, totalRows))
        if (options.factionMemberships) categories.add(analyzeFactionMemberships(workbook, onProgress, totalRows))
        if (options.factionRelationships) categories.add(analyzeFactionRelationships(workbook, onProgress, totalRows))
        if (options.presetTemplates) categories.add(analyzePresetTemplates(workbook, onProgress, totalRows))
        if (options.searchPresets) categories.add(analyzeSearchPresets(workbook, onProgress, totalRows))
        if (options.characterListPresets) categories.add(analyzeCharacterListPresets(workbook, onProgress, totalRows))
        if (options.imageMeta) categories.add(analyzeImageMeta(workbook, onProgress, totalRows))

        return RestoreAnalysis(categories, characterConflicts)
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
            val universe = universesByName[universeName]
            val fd = universe?.let { fieldsByKey[Triple(it.id, fieldKey, imported.entityType)] }
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

        val cols = resolveHeaderColumns(headerRow)
        val fileColIndex = cols["파일명"] ?: 0
        val tagColIndex = cols["태그"] ?: 1
        val groupColIndex = cols["링크그룹"] ?: 2

        val remapByBasename = HashMap<String, String>()
        for ((origPath, newPath) in imagePathRemap) {
            remapByBasename[java.io.File(origPath).name] = newPath
        }
        val filesDir = appContext?.filesDir

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val fileName = getCellString(row, fileColIndex)
            if (fileName.isBlank()) continue
            inBackup++
            val path = remapByBasename[fileName]
                ?: filesDir?.let { dir -> java.io.File(dir, fileName).takeIf { it.exists() }?.absolutePath }
                ?: continue  // 파일 미해석 행은 new/update에 계상하지 않음 (가져오기에서 스킵 경고)
            val existing = db.imageMetaDao().getByPath(path)
            if (existing == null) { newCount++; continue }
            val existingTags = db.imageTagDao().getTagsByImageList(existing.id).map { it.tag }.toSet()
            val sheetTags = splitCsv(getCellString(row, tagColIndex)).toSet()
            val sheetGroup = getCellString(row, groupColIndex).trim().ifBlank { null }
            if (existingTags != sheetTags || existing.linkGroupId != sheetGroup) updateCount++ else unchangedCount++
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

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val codeColIndex = cols["코드"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++
            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val description = getCellString(row, descColIndex)

            val existing = if (code.isNotBlank()) db.universeDao().getUniverseByCode(code) else db.universeDao().getUniverseByName(name)
            if (existing == null) { newCount++; continue }
            if (existing.name != name || existing.description != description) updateCount++ else unchangedCount++
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

        val cols = resolveHeaderColumns(headerRow)
        val titleColIndex = cols[spec.firstColumnHeader] ?: cols["제목"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val codeColIndex = cols["코드"] ?: -1
        // 실제 임포트와 동일하게 위치 폴백 제거 (미리보기가 이웃 열을 세계관명으로 오독하지 않게)
        val universeNameColIndex = cols["세계관"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val title = getCellString(row, titleColIndex)
            if (title.isBlank()) continue
            inBackup++
            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val description = getCellString(row, descColIndex)
            val universeName = getCellString(row, universeNameColIndex)

            val existing = if (code.isNotBlank()) {
                db.novelDao().getNovelByCode(code)
            } else {
                val universeId = if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null
                if (universeId != null) db.novelDao().getNovelByTitleAndUniverse(title, universeId) else db.novelDao().getNovelByTitleNoUniverse(title)
            }
            if (existing == null) { newCount++; continue }
            if (existing.title != title || existing.description != description) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "작품 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("novels", "작품", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFieldDefinitions(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = fieldDefinitionSpec(emptyList())
        val sheet = workbook.getSheet(spec.sheetName)
        // 캐릭터+사건 필드 모두 시트에 실리므로 기존 총계도 전 타입 기준 (프리뷰 정확성)
        val existingTotal = db.fieldDefinitionDao().getAllFieldsAllTypes().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val universeNameColIndex = cols[spec.firstColumnHeader] ?: cols["세계관"] ?: 0
        val keyColIndex = cols["필드키"] ?: 1
        val nameColIndex = cols["필드명"] ?: 2
        val typeColIndex = cols["타입"] ?: 3
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        val entityTypeColIndex = cols["대상"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val universeName = getCellString(row, universeNameColIndex)
            if (universeName.isBlank()) continue
            val key = getCellString(row, keyColIndex)
            if (key.isBlank()) continue
            inBackup++

            val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
            val universe = (if (universeCode.isNotBlank()) db.universeDao().getUniverseByCode(universeCode) else null)
                ?: db.universeDao().getUniverseByName(universeName)
            if (universe == null) { newCount++; continue }

            val name = getCellString(row, nameColIndex)
            val type = getCellString(row, typeColIndex)
            val entityType = FieldValueSheetMapper.entityTypeOf(
                if (entityTypeColIndex >= 0) getCellString(row, entityTypeColIndex) else null
            )
            val existing = db.fieldDefinitionDao().getFieldByKey(universe.id, key, entityType)
            if (existing == null) { newCount++; continue }
            if (existing.name != name || existing.type != type) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "필드 정의 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("fieldDefinitions", "필드 정의", inBackup, newCount, updateCount, unchangedCount, existingTotal)
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
        val cols = resolveHeaderColumns(headerRow, reservedHeaders = CHARACTER_FIXED_HEADERS)
        val nameColIndex = cols["이름"] ?: 0
        val codeColIndex = cols["코드"] ?: -1
        val memoColIndex = cols["메모"] ?: -1
        val anotherNameColIndex = cols["이명"] ?: -1
        val novelColIndex = cols["작품"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var conflictCount = 0
        val conflicts = mutableListOf<CharacterConflict>()

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            if (code.isNotBlank()) {
                // 코드 기반 매칭: 충돌 없음 (코드가 권위적)
                val existing = db.characterDao().getCharacterByCode(code)
                if (existing == null) { newCount++; continue }
                val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""
                val anotherName = if (anotherNameColIndex >= 0) getCellString(row, anotherNameColIndex) else ""
                if (existing.name != name || existing.memo != memo || existing.anotherName != anotherName) updateCount++
            } else {
                // 코드 없음: 이름 기반 매칭 — 동명이인 충돌 가능
                val allMatches = db.characterDao().getAllCharactersByName(name)
                if (allMatches.isEmpty()) {
                    newCount++
                } else if (allMatches.size == 1) {
                    // 단일 매칭: 기존 동작과 동일
                    val existing = allMatches[0]
                    val memo = if (memoColIndex >= 0) getCellString(row, memoColIndex) else ""
                    val anotherName = if (anotherNameColIndex >= 0) getCellString(row, anotherNameColIndex) else ""
                    if (existing.name != name || existing.memo != memo || existing.anotherName != anotherName) updateCount++
                } else {
                    // 다중 매칭: 충돌 발생
                    val novelTitle = if (novelColIndex >= 0) getCellString(row, novelColIndex) else null
                    conflicts.add(CharacterConflict(
                        excelRowIndex = i,
                        sheetName = sheetLabel,
                        excelName = name,
                        excelNovelTitle = novelTitle?.ifBlank { null },
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
        val yearColIndex = cols["연도"] ?: 0
        // 실제 임포트와 동일한 설명 열 해석 — 스펙 위치는 5 (4로 폴백하면 사건 유형 열을 설명으로 오독)
        val descColIndex = cols["사건 설명"]
            ?: cols.entries.firstOrNull { it.key.contains("설명") }?.value
            ?: 5
        val codeColIndex = cols["코드"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val year = parseNumber(getCellString(row, yearColIndex))?.toInt() ?: continue
            val description = getCellString(row, descColIndex)
            if (description.isBlank()) continue
            inBackup++

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val fileCode = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val byCode = if (fileCode.isNotBlank()) db.timelineDao().getEventByCode(fileCode) else null
            val existing = byCode ?: db.timelineDao().getEventByNaturalKey(year, description)
            when {
                existing == null -> newCount++
                byCode != null && (existing.year != year || existing.description != description) -> updateCount++
                else -> unchangedCount++
            }
        }
        reportProgress(onProgress, "사건 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("timeline", "사건 연표", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeStateChanges(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = stateChangeSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.characterStateChangeDao().getAllChangesList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val charNameColIndex = cols["캐릭터"] ?: 0
        val yearColIndex = cols["연도"] ?: 2
        val fieldKeyColIndex = cols["필드키"] ?: 5
        val newValueColIndex = cols["새 값"] ?: 6
        val charCodeColIndex = cols["캐릭터코드"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        // 위치 폴백 금지 — 열을 지운 파일에서 '연도' 열을 작품 제목으로 읽어(제목이 숫자면 실제로 매칭된다)
        // 동명이인을 엉뚱한 작품 기준으로 무근거 선택한다. 열 없음이면 힌트 없이 엄격 해석한다.
        val novelColIndex = cols["작품"] ?: -1

        val allNovels = db.novelDao().getAllNovelsList()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val charName = getCellString(row, charNameColIndex)
            if (charName.isBlank()) continue
            val year = parseNumber(getCellString(row, yearColIndex))?.toInt() ?: continue
            val fieldKey = getCellString(row, fieldKeyColIndex)
            if (fieldKey.isBlank()) continue
            val newValue = getCellString(row, newValueColIndex)
            if (newValue.isBlank()) continue
            inBackup++

            val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""
            val novelTitle = getCellString(row, novelColIndex)
            val character = (if (charCode.isNotBlank()) db.characterDao().getCharacterByCode(charCode) else null)
                ?: run {
                    val novelId = if (novelTitle.isNotBlank()) allNovels.find { it.title == novelTitle }?.id else null
                    findCharacterByName(charName, novelId)
                }
            if (character == null) { continue }

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val fileCode = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val byCode = if (fileCode.isNotBlank()) db.characterStateChangeDao().getChangeByCode(fileCode) else null
            val existing = byCode ?: db.characterStateChangeDao().getChangeByNaturalKey(character.id, year, fieldKey, newValue)
            when {
                existing == null -> newCount++
                byCode != null && (existing.year != year || existing.fieldKey != fieldKey ||
                    existing.newValue != newValue || existing.characterId != character.id) -> updateCount++
                else -> unchangedCount++
            }
        }
        reportProgress(onProgress, "상태 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("stateChanges", "상태 변화", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeRelationships(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = relationshipSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.characterRelationshipDao().getAllRelationships().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        val char2NameColIndex = cols["캐릭터2"] ?: 1
        val typeColIndex = cols["관계 유형"] ?: 2
        val descColIndex = cols["설명"] ?: 3
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1
        val relCodeColIndex = cols["코드"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val char1Name = getCellString(row, char1NameColIndex)
            val char2Name = getCellString(row, char2NameColIndex)
            if (char1Name.isBlank() || char2Name.isBlank()) continue
            val relationshipType = getCellString(row, typeColIndex)
            if (relationshipType.isBlank()) continue
            inBackup++

            // 실제 임포트와 동일한 코드 우선 매칭 — 유형을 편집한 행이 미리보기에서만 '신규'로 잡히지 않게
            if (relCodeColIndex >= 0) {
                val rc = getCellString(row, relCodeColIndex)
                if (rc.isNotBlank()) {
                    val byCode = db.characterRelationshipDao().getByCode(rc)
                    if (byCode != null) {
                        val desc = if (descColIndex >= 0) getCellString(row, descColIndex) else byCode.description
                        if (byCode.relationshipType != relationshipType || byCode.description != desc) updateCount++
                        else unchangedCount++
                        continue
                    }
                }
            }

            val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
            val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""
            val char1 = (if (char1Code.isNotBlank()) db.characterDao().getCharacterByCode(char1Code) else null) ?: findCharacterByName(char1Name, null)
            if (char1 == null) { continue }
            val char2 = (if (char2Code.isNotBlank()) db.characterDao().getCharacterByCode(char2Code) else null) ?: findCharacterByName(char2Name, null)
            if (char2 == null) { continue }
            if (char1.id == char2.id) continue

            val existingRels = db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id)
            val existing = existingRels.find { rel ->
                ((rel.characterId1 == char1.id && rel.characterId2 == char2.id) || (rel.characterId1 == char2.id && rel.characterId2 == char1.id)) && rel.relationshipType == relationshipType
            }
            if (existing == null) { newCount++; continue }
            val description = getCellString(row, descColIndex)
            if (existing.description != description) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationships", "관계", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeRelationshipChanges(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val sheet = workbook.getSheet("관계 변화")
        val existingTotal = db.characterRelationshipChangeDao().getAllChanges().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        val char2NameColIndex = cols["캐릭터2"] ?: 1
        val yearColIndex = cols["연도"] ?: 2
        val monthColIndex = cols["월"] ?: 3
        val dayColIndex = cols["일"] ?: 4
        val codeColIndex = cols["코드"] ?: -1
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val char1Name = getCellString(row, char1NameColIndex)
            val char2Name = getCellString(row, char2NameColIndex)
            if (char1Name.isBlank() || char2Name.isBlank()) continue
            val year = parseNumber(getCellString(row, yearColIndex))?.toInt() ?: continue
            inBackup++

            val month = parseNumber(getCellString(row, monthColIndex))?.toInt()
            val day = parseNumber(getCellString(row, dayColIndex))?.toInt()

            // 실제 임포트와 동일한 매칭: 코드 우선 (캐릭터 해석 없이도 정체성 판정 가능)
            val fileCode = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val byCode = if (fileCode.isNotBlank()) db.characterRelationshipChangeDao().getChangeByCode(fileCode) else null
            if (byCode != null) {
                if (byCode.year != year || byCode.month != month || byCode.day != day) updateCount++ else unchangedCount++
                continue
            }

            val char1Code = if (char1CodeColIndex >= 0) getCellString(row, char1CodeColIndex) else ""
            val char2Code = if (char2CodeColIndex >= 0) getCellString(row, char2CodeColIndex) else ""
            val char1 = (if (char1Code.isNotBlank()) db.characterDao().getCharacterByCode(char1Code) else null) ?: findCharacterByName(char1Name, null)
            if (char1 == null) { continue }
            val char2 = (if (char2Code.isNotBlank()) db.characterDao().getCharacterByCode(char2Code) else null) ?: findCharacterByName(char2Name, null)
            if (char2 == null) { continue }

            val relationships = db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id)
            val relationship = relationships.find { rel ->
                (rel.characterId1 == char1.id && rel.characterId2 == char2.id) || (rel.characterId1 == char2.id && rel.characterId2 == char1.id)
            }
            if (relationship == null) { newCount++; continue }

            val existing = db.characterRelationshipChangeDao().getChangeByNaturalKey(relationship.id, year, month, day)
            if (existing == null) newCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationshipChanges", "관계 변화", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeNameBank(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = nameBankSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingNames = db.nameBankDao().getAllNamesList()
        val existingTotal = existingNames.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val genderColIndex = cols["성별"] ?: 1
        val originColIndex = cols["출처"] ?: 2
        val notesColIndex = cols["메모"] ?: 3

        val existingMap = existingNames.associateBy { "${it.name}\u0000${it.gender}" }
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val gender = getCellString(row, genderColIndex)
            val mapKey = "${name}\u0000${gender}"
            val existing = existingMap[mapKey]
            if (existing == null) { newCount++; continue }
            val origin = getCellString(row, originColIndex)
            val notes = getCellString(row, notesColIndex)
            if (existing.origin != origin || existing.notes != notes) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "이름 은행 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("nameBank", "이름 은행", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFactions(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionSpec()
        val sheet = workbook.getSheet(spec.sheetName)
        val existingTotal = db.factionDao().getAllFactionsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)

        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val universeNameColIndex = cols["세계관"] ?: -1

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val universeName = if (universeNameColIndex >= 0) getCellString(row, universeNameColIndex) else ""
            val universeId = if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null

            val existing = if (code.isNotBlank()) db.factionDao().getByCode(code)
            else if (universeId != null) db.factionDao().getByNameAndUniverse(name, universeId) else null

            if (existing == null) { newCount++; continue }
            val description = if (descColIndex >= 0) getCellString(row, descColIndex) else ""
            if (existing.name != name || existing.description != description) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세력 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factions", "세력", inBackup, newCount, updateCount, unchangedCount, existingTotal)
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
            val character = (if (charCode.isNotBlank()) db.characterDao().getCharacterByCode(charCode) else null)
                ?: db.characterDao().getCharacterByName(charName)
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
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: -1
        val fieldsJsonColIndex = cols["설정(JSON)"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        // 미리보기도 실제 임포트와 같은 매칭기를 태운다 — 이름 맵으로 세면 동명 템플릿이 접혀
        // 실제로는 N건이 갱신되는데 1건으로 보고된다(사실과 다른 미리보기).
        val matcher = PresetTemplateMatcher(
            existingTemplates.map { PresetTemplateMatcher.Candidate(it.id, it.name, it.createdAt) }
        )
        val byId = existingTemplates.associateBy { it.id }
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() else null
            val match = matcher.claim(name, createdAt, i)
            if (match !is PresetTemplateMatcher.Match.Matched) { newCount++; continue }
            val existing = byId[match.id]
            if (existing == null) { newCount++; continue }
            // 열이 없으면 임포트가 기존값을 유지하므로 비교도 기존값으로 (F1-A)
            val description = if (descColIndex >= 0) getCellString(row, descColIndex) else existing.description
            val fieldsJson = if (fieldsJsonColIndex >= 0) getCellString(row, fieldsJsonColIndex).ifBlank { "[]" } else existing.fieldsJson
            if (existing.name != name || existing.description != description || existing.fieldsJson != fieldsJson) updateCount++ else unchangedCount++
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
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val queryColIndex = cols["검색어"] ?: -1
        val filtersColIndex = cols["필터(JSON)"] ?: -1

        val existingByName = existingPresets.associateBy { it.name }
        val filterIndex = fieldFilterIndex()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val existing = existingByName[name]
            if (existing == null) { newCount++; continue }
            // 열이 없으면 임포트가 기존값을 유지하므로 비교도 기존값으로 — '변경 없음'이 사실이다(F1-A)
            val query = if (queryColIndex >= 0) getCellString(row, queryColIndex) else existing.query
            // 실제 임포트와 동일하게 안정 식별자를 fieldId로 재해석한 뒤 비교 (왕복 보조 속성 탓에 전부 '변경'으로 집계되지 않게)
            val filtersJson = if (filtersColIndex >= 0) PortableFieldFilters.resolve(
                getCellString(row, filtersColIndex).ifBlank { "{}" }, filterIndex).json
            else existing.filtersJson
            if (existing.query != query || existing.filtersJson != filtersJson) updateCount++ else unchangedCount++
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

        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val tagsColIndex = cols["태그(JSON)"] ?: -1
        val filtersColIndex = cols["필드필터(JSON)"] ?: -1

        val existingByName = existingPresets.associateBy { it.name }
        val filterIndex = fieldFilterIndex()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val name = getCellString(row, nameColIndex)
            if (name.isBlank()) continue
            inBackup++

            val existing = existingByName[name]
            if (existing == null) { newCount++; continue }
            val tagsJson = if (tagsColIndex >= 0) getCellString(row, tagsColIndex).ifBlank { "[]" } else existing.tagsJson
            // 실제 임포트와 동일하게 안정 식별자를 fieldId로 재해석한 뒤 비교 (왕복 보조 속성 탓에 전부 '변경'으로 집계되지 않게)
            val filtersJson = if (filtersColIndex >= 0) PortableFieldFilters.resolve(
                getCellString(row, filtersColIndex).ifBlank { "{}" }, filterIndex).json
            else existing.fieldFiltersJson
            if (existing.tagsJson != tagsJson || existing.fieldFiltersJson != filtersJson) updateCount++ else unchangedCount++
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
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        val codeColIndex = cols["코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val borderColorColIndex = cols["테두리색"] ?: -1
        val borderWidthColIndex = cols["테두리두께"] ?: -1
        val imagePathColIndex = cols["이미지경로"] ?: -1
        val imageModeColIndex = cols["이미지모드"] ?: -1
        val customRelTypesColIndex = cols["커스텀관계유형"] ?: -1
        val customRelColorsColIndex = cols["커스텀관계색상"] ?: -1
        val imageCharCodeColIndex = cols["이미지캐릭터코드"] ?: -1
        val imageNovelCodeColIndex = cols["이미지작품코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        // Build code index for duplicate detection within file
        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                // F1-A: 열 없음 → null(기존 유지). 열 있음 → 셀 값(빈칸=규칙 가에 따라 비움 의도 존중).
                val descriptionFromExcel: String? = if (cols.containsKey("설명")) getCellString(row, descColIndex) else null
                val code = getCellCode(row, codeColIndex, "세계관 행 $i", result)
                val displayOrder: Long? = if (orderColIndex >= 0) {
                    val raw = getCellString(row, orderColIndex)
                    if (raw.isBlank()) null else parseNumber(raw)?.toLong()
                } else null
                val borderColorFromExcel: String? = if (borderColorColIndex >= 0) getCellString(row, borderColorColIndex) else null
                val borderWidthFromExcel: Float? = if (borderWidthColIndex >= 0) (parseNumber(getCellString(row, borderWidthColIndex))?.toFloat() ?: 1.5f) else null
                val imagePathsFromExcel: String? = if (imagePathColIndex >= 0) remapImagePaths(getCellString(row, imagePathColIndex).ifBlank { "[]" }) else null
                val imageModeFromExcel: String? = if (imageModeColIndex >= 0) getCellString(row, imageModeColIndex).ifBlank { "none" } else null
                // 두 열은 JSON이다. 소비처가 파싱 실패를 무음으로 삼키고 기본값으로 돌아가므로 여기서 검증한다.
                // null = 열 없음 또는 해석 불가 → 기존 값 유지.
                val customRelTypes: String? = if (customRelTypesColIndex >= 0)
                    normalizeRelTypesCell(getCellString(row, customRelTypesColIndex), "세계관 행 $i", name, result) else null
                val customRelColors: String? = if (customRelColorsColIndex >= 0)
                    normalizeRelColorsCell(getCellString(row, customRelColorsColIndex), "세계관 행 $i", name, result) else null
                val imageCharCode = getCellCode(row, imageCharCodeColIndex, "세계관 행 $i", result).ifBlank { null }
                val imageNovelCode = getCellCode(row, imageNovelCodeColIndex, "세계관 행 $i", result).ifBlank { null }
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                    db.universeDao().update(existing.copy(
                        name = name,
                        description = descriptionFromExcel ?: existing.description,
                        displayOrder = displayOrder ?: existing.displayOrder,
                        borderColor = borderColorFromExcel ?: existing.borderColor,
                        borderWidthDp = borderWidthFromExcel ?: existing.borderWidthDp,
                        imagePaths = imagePathsFromExcel ?: existing.imagePaths,
                        imageMode = imageModeFromExcel ?: existing.imageMode,
                        customRelationshipTypes = customRelTypes ?: existing.customRelationshipTypes,
                        customRelationshipColors = customRelColors ?: existing.customRelationshipColors,
                        // imageCharacterId/imageNovelId: deferred (Phase 2 후 코드 기반 해석)
                        imageCharacterId = if (imageCharCodeColIndex >= 0) null else existing.imageCharacterId,
                        imageNovelId = if (imageNovelCodeColIndex >= 0) null else existing.imageNovelId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    if (imageCharCode != null) deferredUniverseImageCharCodes[existing.id] = imageCharCode
                    if (imageNovelCode != null) deferredUniverseImageNovelCodes[existing.id] = imageNovelCode
                    result.updatedUniverses++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newId = db.universeDao().insert(Universe(
                        name = name, description = descriptionFromExcel ?: "", code = newCode,
                        displayOrder = displayOrder ?: i.toLong(), borderColor = borderColorFromExcel ?: "", borderWidthDp = borderWidthFromExcel ?: 1.5f,
                        imagePaths = imagePathsFromExcel ?: "[]", imageMode = imageModeFromExcel ?: "none",
                        customRelationshipTypes = customRelTypes ?: "",
                        customRelationshipColors = customRelColors ?: "",
                        imageCharacterId = null, // deferred
                        imageNovelId = null,     // deferred
                        createdAt = createdAt
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
        val titleColIndex = cols[spec.firstColumnHeader] ?: cols["제목"] ?: 0
        val descColIndex = cols["설명"] ?: 1
        // 위치 폴백 금지 — 열을 지우거나 개명한 파일에서 이웃 열('설명' 등)을 세계관명으로 오독하고,
        // 미해석 경고가 행마다 거짓으로 쏟아진다. 열 없음(-1)이면 F1-A대로 기존 소속을 유지한다.
        val universeNameColIndex = cols["세계관"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val borderColorColIndex = cols["테두리색"] ?: -1
        val borderWidthColIndex = cols["테두리두께"] ?: -1
        val novelImagePathColIndex = cols["이미지경로"] ?: -1
        val novelImageModeColIndex = cols["이미지모드"] ?: -1
        val imageCharCodeColIndex = cols["이미지캐릭터코드"] ?: -1
        val inheritBorderColIndex = cols["테두리상속"] ?: -1
        val novelPinnedColIndex = cols["고정"] ?: -1
        val standardYearColIndex = cols["표준연도"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        // 작품 커스텀 필드 컬럼 (확-3) — 연표 시트와 **같은 규칙**(EntityFieldHeaders)의 역함수다.
        // 정확 일치를 최우선으로 두는 이유는 이름이 괄호로 끝나는 필드를 세계관 한정으로
        // 오인하면 열 전체가 버려지기 때문이다.
        val allNovelFields = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_NOVEL)
        val universeNamesById = db.universeDao().getAllUniversesList().associate { it.id to it.name }
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
                    EventFieldColumn(ci, header, exact.name, universeNamesById[exact.universeId], exact)
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
                val title = getCellString(row, titleColIndex)
                if (title.isBlank()) continue

                // F1-A: 열 없음 → null(기존 유지). 열 있음 → 셀 값(빈칸=규칙 가에 따라 비움 의도 존중).
                val descriptionFromExcel: String? = if (cols.containsKey("설명")) getCellString(row, descColIndex) else null
                val universeName = getCellString(row, universeNameColIndex)
                val code = getCellCode(row, codeColIndex, "작품 행 $i", result)
                val universeCode = getCellCode(row, universeCodeColIndex, "작품 행 $i", result)
                val displayOrder: Long? = if (orderColIndex >= 0) {
                    val raw = getCellString(row, orderColIndex)
                    if (raw.isBlank()) null else parseNumber(raw)?.toLong()
                } else null
                val borderColorFromExcel: String? = if (borderColorColIndex >= 0) getCellString(row, borderColorColIndex) else null
                val borderWidthFromExcel: Float? = if (borderWidthColIndex >= 0) (parseNumber(getCellString(row, borderWidthColIndex))?.toFloat() ?: 1.5f) else null
                val novelImagePathsFromExcel: String? = if (novelImagePathColIndex >= 0) remapImagePaths(getCellString(row, novelImagePathColIndex).ifBlank { "[]" }) else null
                val novelImageModeFromExcel: String? = if (novelImageModeColIndex >= 0) getCellString(row, novelImageModeColIndex).ifBlank { "none" } else null
                val novelImageCharCode = getCellCode(row, imageCharCodeColIndex, "작품 행 $i", result).ifBlank { null }
                val novelIsPinned = if (novelPinnedColIndex >= 0) parseBoolean(getCellString(row, novelPinnedColIndex)) else false
                val standardYear = if (standardYearColIndex >= 0) parseNumber(getCellString(row, standardYearColIndex))?.toInt() else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                val universeColumnPresent = cols.containsKey("세계관") || universeCodeColIndex >= 0
                val universeRefProvided = universeCode.isNotBlank() || universeName.isNotBlank()
                val universeId = (if (universeCode.isNotBlank()) db.universeDao().getUniverseByCode(universeCode)?.id else null)
                    ?: (if (universeName.isNotBlank()) db.universeDao().getUniverseByName(universeName)?.id else null)
                if (universeRefProvided && universeId == null) {
                    result.warnings.add("작품 행 $i: 세계관 '${universeName.ifBlank { universeCode }}'을(를) 찾을 수 없음 — 기존 작품은 소속 유지, 새 작품은 세계관 미지정으로 생성")
                }

                // Code-first matching (Sprint A) + F1-C: 미지 코드 → 자연키 폴백 + 경고
                val existing: Novel?
                if (code.isNotBlank()) {
                    val byCode = db.novelDao().getNovelByCode(code)
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

                val effectiveInherit = if (inheritBorderColIndex >= 0) parseBoolean(getCellString(row, inheritBorderColIndex)) else (borderColorFromExcel ?: "").isBlank()

                if (existing != null) {
                    // 이 행이 확정하는 소속 — 아래 update와 필드값 적용이 **같은 값**을 봐야 한다.
                    // 두 곳에 따로 쓰면 한쪽만 고쳐질 때 방금 옮긴 작품에 옛 세계관 필드가 붙는다.
                    val effectiveUniverseId = when {
                        !universeColumnPresent -> existing.universeId
                        universeRefProvided && universeId == null -> existing.universeId
                        else -> universeId
                    }
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
                    db.novelDao().update(existing.copy(
                        title = title,
                        description = descriptionFromExcel ?: existing.description,
                        // F1-A: 열 없음 = 기존 소속 유지. 참조가 있는데 미해석이면 무음 분리 대신 기존 유지(위 경고와 짝).
                        universeId = effectiveUniverseId,
                        displayOrder = displayOrder ?: existing.displayOrder,
                        borderColor = borderColorFromExcel ?: existing.borderColor,
                        borderWidthDp = borderWidthFromExcel ?: existing.borderWidthDp,
                        inheritUniverseBorder = if (inheritBorderColIndex >= 0) effectiveInherit else existing.inheritUniverseBorder,
                        isPinned = if (novelPinnedColIndex >= 0) novelIsPinned else existing.isPinned,
                        imagePaths = novelImagePathsFromExcel ?: existing.imagePaths,
                        imageMode = novelImageModeFromExcel ?: existing.imageMode,
                        // imageCharacterId: deferred (Phase 2 후 코드 기반 해석)
                        imageCharacterId = if (imageCharCodeColIndex >= 0) null else existing.imageCharacterId,
                        standardYear = if (standardYearColIndex >= 0) standardYear else existing.standardYear,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    if (novelImageCharCode != null) deferredNovelImageCharCodes[existing.id] = novelImageCharCode
                    result.updatedNovels++
                    // 소속이 이 행에서 바뀌었으면 **새 소속**의 필드가 적용 대상이다(위 val과 같은 값).
                    applyNovelFieldColumns(
                        row, existing.id, effectiveUniverseId,
                        novelFieldColumns, allNovelFields, universeNamesById,
                        droppedNovelFieldHeaders, result
                    )
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newId = db.novelDao().insert(Novel(
                        title = title, description = descriptionFromExcel ?: "", universeId = universeId,
                        code = newCode, displayOrder = displayOrder ?: i.toLong(),
                        borderColor = borderColorFromExcel ?: "", borderWidthDp = borderWidthFromExcel ?: 1.5f,
                        inheritUniverseBorder = effectiveInherit, isPinned = novelIsPinned,
                        imagePaths = novelImagePathsFromExcel ?: "[]", imageMode = novelImageModeFromExcel ?: "none",
                        imageCharacterId = null, // deferred
                        standardYear = standardYear,
                        createdAt = createdAt
                    ))
                    if (novelImageCharCode != null) deferredNovelImageCharCodes[newId] = novelImageCharCode
                    entitySeen[newId] = i
                    result.newNovels++
                    applyNovelFieldColumns(
                        row, newId, universeId,
                        novelFieldColumns, allNovelFields, universeNamesById,
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
     * - 이 작품의 세계관에 없는 필드 열은 정상(다른 세계관 열)이므로 값이 있을 때만 1회 경고한다.
     * - 계산 필드는 저장하지 않는다(R-16) — 수식으로 산출되는 파생값이다.
     */
    private suspend fun applyNovelFieldColumns(
        row: Row,
        novelId: Long,
        novelUniverseId: Long?,
        columns: List<EventFieldColumn>,
        allNovelFields: List<FieldDefinition>,
        universeNamesById: Map<Long, String>,
        droppedHeaders: MutableSet<String>,
        result: ImportResult
    ) {
        if (columns.isEmpty() || novelUniverseId == null) return
        val universeFields = allNovelFields.filter { it.universeId == novelUniverseId }
        val universeName = universeNamesById[novelUniverseId]
        val newValues = mutableListOf<com.novelcharacter.app.data.model.NovelFieldValue>()
        val resolvedFieldIds = mutableListOf<Long>()
        for (col in columns) {
            val ci = col.colIndex
            val fieldDef = when {
                col.resolved != null ->
                    if (col.resolved.universeId == novelUniverseId) col.resolved else null
                col.universeName != null && col.universeName != universeName -> null
                else -> universeFields.find { it.name == col.fieldName }
            }
            if (fieldDef == null) {
                if (getCellString(row, ci).isNotBlank() && droppedHeaders.add(col.header)) {
                    result.warnings.add(
                        "작품 시트의 필드 열 '${col.header}'에 해당하는 작품 필드 정의를 찾을 수 없어 값이 반영되지 않았습니다 — '필드 정의' 시트(대상=작품)를 함께 가져오세요"
                    )
                }
                continue
            }
            if (fieldDef.type == "CALCULATED") {
                if (getCellString(row, ci).isNotBlank() && droppedHeaders.add(col.header)) {
                    result.warnings.add(
                        "작품 시트의 '${col.header}' 열은 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨)"
                    )
                }
                continue
            }
            resolvedFieldIds.add(fieldDef.id)
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

    // ── 등급 체계 가져오기 (U-1 — 필드 정의 직전: '등급체계' 열이 여기서 만든 체계를 찾는다) ──

    private suspend fun analyzeGradeSystems(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = gradeSystemSpec()
        val label = "등급 체계"
        val existingTotal = db.gradeSystemDao().getAllList().size
        val sheet = workbook.getSheet(spec.sheetName)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("gradeSystems", label, 0, 0, 0, 0, existingTotal)
        val headerRow = sheet.getRow(0) ?: return CategoryAnalysis("gradeSystems", label, 0, 0, 0, 0, existingTotal)

        var newCount = 0; var updateCount = 0; var unchangedCount = 0
        val groups = collectGradeSystemRows(sheet, headerRow, result = null)
        for (group in groups) {
            val existing = resolveGradeSystem(group)
            when {
                existing == null -> newCount++
                existing.name != group.name ||
                    com.novelcharacter.app.data.model.GradeSystemRef.gradesFromJson(existing.gradesJson) !=
                        com.novelcharacter.app.data.model.GradeSystemRef.gradesFromJson(group.gradesJson()) -> updateCount++
                else -> unchangedCount++
            }
        }
        reportProgress(onProgress, "등급 체계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("gradeSystems", label, groups.size, newCount, updateCount, unchangedCount, existingTotal)
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
                val saved = existing.copy(
                    name = if (rename) group.name else existing.name,
                    gradesJson = gradesJson
                )
                if (saved != existing) {
                    // 참조 필드 전파까지 한 몸으로 — 엑셀 경로는 라벨 개명을 추적할 수 없으므로
                    // (행의 정체가 라벨 그 자체다) 라벨 변경은 삭제+추가로 다룬다.
                    repository.saveSystem(saved)
                    result.updatedGradeSystems++
                } // 변경 없음은 세지 않는다
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
        val cols = resolveHeaderColumns(headerRow)
        val universeNameColIndex = cols[spec.firstColumnHeader] ?: cols["세계관"] ?: 0
        val keyColIndex = cols["필드키"] ?: 1
        val nameColIndex = cols["필드명"] ?: 2
        val typeColIndex = cols["타입"] ?: 3
        val configColIndex = cols["설정(JSON)"] ?: 4
        val groupColIndex = cols["그룹"] ?: 5
        val orderColIndex = cols["순서"] ?: -1
        // 위치 폴백 금지 — 열을 지우면 '세계관코드'를 필수여부로 오독한다
        val requiredColIndex = cols["필수여부"] ?: -1
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        // 대상(캐릭터/사건) — 열이 없는 구버전 파일은 캐릭터로 간주 (관대 수용)
        val entityTypeColIndex = cols["대상"] ?: -1
        // config 파생 전용 열(A-1·A-2) — 열/JSON 키/기존값 3분기 병합 (FieldConfigColumns.merge)
        val aiSuggestColIndex = cols[FieldConfigColumns.COLUMN_AI_SUGGEST] ?: -1
        val descriptionColIndex = cols[FieldConfigColumns.COLUMN_DESCRIPTION] ?: -1
        // 등급 체계 참조 열(U-1) — 같은 3분기 문법. 해석은 코드 우선, 없으면 (세계관, 이름).
        val gradeSystemColIndex = cols["등급체계"] ?: -1
        val gradeSystemCodeColIndex = cols["등급체계코드"] ?: -1

        val entitySeen = mutableMapOf<Long, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val universeName = getCellString(row, universeNameColIndex)
                if (universeName.isBlank()) continue

                val universeCode = getCellCode(row, universeCodeColIndex, "필드 행 $i", result)
                val universe = (if (universeCode.isNotBlank()) {
                    db.universeDao().getUniverseByCode(universeCode)
                } else null)
                    ?: db.universeDao().getUniverseByName(universeName)
                if (universe == null) {
                    result.skippedRows++
                    result.errors.add("필드 정의 행 $i: 세계관 '${universeName}'을(를) 찾을 수 없음")
                    continue
                }

                val key = getCellString(row, keyColIndex)
                if (key.isBlank()) continue

                val name = getCellString(row, nameColIndex)
                val type = getCellString(row, typeColIndex)
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
                val config = getCellString(row, configColIndex).ifBlank { "{}" }
                // F4: 설정(JSON)이 손상(절단·구문 오류)됐으면 조용히 넘기지 않고 경고 (필드 동작 무력화 방지)
                if (config != "{}" && !isValidJson(config)) {
                    result.warnings.add("필드 정의 행 $i: 필드 '$name'의 설정(JSON)이 올바른 형식이 아닙니다(절단·오타 가능) — 그대로 저장되나 해당 기능이 동작하지 않을 수 있습니다")
                }
                val groupName = getCellString(row, groupColIndex).ifBlank { "기본 정보" }
                val displayOrder: Int? = if (orderColIndex >= 0) {
                    val raw = getCellString(row, orderColIndex)
                    if (raw.isBlank()) null else parseNumber(raw)?.toInt()
                } else null
                val isRequired = sheetBooleanOrKeep(requiredColIndex >= 0, getCellString(row, requiredColIndex))
                val entityType = FieldValueSheetMapper.entityTypeOf(
                    if (entityTypeColIndex >= 0) getCellString(row, entityTypeColIndex) else null
                )

                val existing = db.fieldDefinitionDao().getFieldByKey(universe.id, key, entityType)
                // AI추천·필드설명 병합 — 열이 있으면 셀이 값, 없으면 JSON 키 유지, 둘 다 없으면
                // 기존 DB 값 보존(빠뜨리면 전용 열을 지운 파일에서 설명이 무통보 유실된다)
                val portableMerged = FieldConfigColumns.merge(
                    sheetConfig = config,
                    aiColumnPresent = aiSuggestColIndex >= 0,
                    aiCellText = getCellString(row, aiSuggestColIndex),
                    descriptionColumnPresent = descriptionColIndex >= 0,
                    descriptionCellText = getCellString(row, descriptionColIndex),
                    existingConfig = existing?.config
                )
                // 등급 체계 참조 병합(U-1) — 참조가 해석되면 실효 표를 다시 물질화하고,
                // 가리키는 체계가 없으면 거부 대신 독자 표로 내려앉히고 고지한다(관대 수용).
                val mergedConfig = mergeGradeSystemColumn(
                    universeId = universe.id,
                    rowIndex = i,
                    fieldName = name,
                    fieldType = type,
                    config = portableMerged,
                    nameColumnPresent = gradeSystemColIndex >= 0,
                    codeColumnPresent = gradeSystemCodeColIndex >= 0,
                    cellName = if (gradeSystemColIndex >= 0) getCellString(row, gradeSystemColIndex) else "",
                    cellCode = if (gradeSystemCodeColIndex >= 0) getCellString(row, gradeSystemCodeColIndex) else "",
                    existingConfig = existing?.config,
                    result = result
                )
                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("필드 정의 행 $i: 행 $prevRow 과 같은 항목('$name')을 다시 덮어씀 — 별개의 필드로 넣으려면 '필드키'를 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    if (existing.type != type && type.isNotBlank()) {
                        result.warnings.add("필드 정의 행 $i: 필드 '$name'의 타입이 '${existing.type}'에서 '$type'(으)로 변경됨 — 기존 값 호환성을 확인하세요")
                    }
                    db.fieldDefinitionDao().update(existing.copy(
                        name = name, type = type, config = mergedConfig,
                        groupName = groupName, displayOrder = displayOrder ?: existing.displayOrder,
                        isRequired = isRequired ?: existing.isRequired
                    ))
                    matchedFieldDefinitionIds.add(existing.id)
                    result.updatedFields++
                } else {
                    val newId = db.fieldDefinitionDao().insert(FieldDefinition(
                        universeId = universe.id, key = key, name = name, type = type,
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
        reportProgress(onProgress, "필드 정의", sheet.lastRowNum, totalRows)
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
        universeId: Long,
        rowIndex: Int,
        fieldName: String,
        fieldType: String,
        config: String,
        nameColumnPresent: Boolean,
        codeColumnPresent: Boolean,
        cellName: String,
        cellCode: String,
        existingConfig: String?,
        result: ImportResult
    ): String {
        if (fieldType != FieldType.GRADE.name) return config
        val ref = com.novelcharacter.app.data.model.GradeSystemRef

        suspend fun resolve(code: String?, name: String?): com.novelcharacter.app.data.model.GradeSystem? {
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
                result.warnings.add(
                    "필드 정의 행 $rowIndex: 필드 '$fieldName'의 등급 ${merge.droppedLabels.joinToString(", ")}은(는) " +
                        "체계 '${system.name}'에 없어 빠졌습니다 — 체계에 등급을 추가하거나 '등급체계' 칸을 비워 독자 표로 두세요"
                )
            }
            return merge.config
        }

        fun demoteWithNotice(pointer: String): String {
            result.warnings.add(
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
        val stale = db.fieldDefinitionDao().getAllFieldsAllTypes().filter { it.id !in matchedFieldDefinitionIds }
        for (field in stale) db.fieldDefinitionDao().delete(field)
        if (stale.isNotEmpty()) {
            val names = stale.take(5).joinToString(", ") { it.name }
            val more = if (stale.size > 5) " 외 ${stale.size - 5}개" else ""
            result.warnings.add("덮어쓰기: 백업에 없는 필드 정의 ${stale.size}개($names$more)를 관련 필드값과 함께 삭제했습니다 — 의도한 것이 아니면 삭제 전 백업으로 되돌리세요")
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
        for (e in db.fieldValueEntryDao().getAllList()) {
            entriesByField.getOrPut(e.fieldDefinitionId) { mutableListOf() }.add(e)
        }
        val fieldCache = HashMap<Triple<Long, String, String>, FieldDefinition?>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val universeName = getCellString(row, universeCol)
                val fieldKey = getCellString(row, keyCol)
                val value = getCellString(row, valueCol)
                if (universeName.isBlank() && fieldKey.isBlank() && value.isBlank()) continue

                val universe = universesByName[universeName]
                if (universe == null) {
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
                val fd = fieldCache.getOrPut(Triple(universe.id, fieldKey, imported.entityType)) {
                    db.fieldDefinitionDao().getFieldByKey(universe.id, fieldKey, imported.entityType)
                }
                if (fd == null) {
                    result.skippedRows++
                    result.warnings.add("필드 데이터 행 $i: 필드 키 '$fieldKey'(${imported.entityLabel ?: "캐릭터"})을(를) 세계관 '$universeName'에서 찾을 수 없음")
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
                    result.updatedFieldValueEntries++
                } else {
                    // 코드 전역 유니크: 다른 필드의 엔트리가 이미 소유한 코드면 재발급 (관대 수용)
                    val codeOwner = db.fieldValueEntryDao().getByCode(candidate.code)
                    if (codeOwner != null) {
                        candidate = candidate.copy(code = generateEntityCode())
                        result.newCodesGenerated++
                    }
                    val newId = db.fieldValueEntryDao().insert(candidate)
                    siblings.add(candidate.copy(id = newId))
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
        importCharacterRows(sheet, headerRow, null, emptyList(), result, resolvedConflicts, UNCLASSIFIED_SHEET_NAME, onProgress, totalRows)
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
        val charFieldIdsByUniverse: Map<Long, Set<Long>> = fieldsById.values
            .filter { it.entityType == FieldDefinition.ENTITY_CHARACTER }
            .groupBy { it.universeId }
            .mapValues { (_, list) -> list.mapTo(HashSet()) { it.id } }

        var characters = 0
        var values = 0
        for ((charId, charValues) in allValues.groupBy { it.characterId }) {
            val ownUniverseId = universeIdByCharId[charId]
            val covered = ownUniverseId?.let { charFieldIdsByUniverse[it] } ?: emptySet()
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
        val fieldCache = HashMap<Triple<Long, String, String>, FieldDefinition?>()
        val seen = HashMap<Pair<Long, Long>, Int>()

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
                var character = charCode.takeIf { it.isNotBlank() }?.let { db.characterDao().getCharacterByCode(it) }
                if (character == null && charName.isNotBlank()) {
                    val byName = db.characterDao().getAllCharactersByName(charName)
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

                val universe = uCode.takeIf { it.isNotBlank() }?.let { universesByCode[it] } ?: universesByName[uName]
                if (universe == null) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 세계관 '${uName.ifBlank { uCode }}'을(를) 찾을 수 없습니다 — '세계관' 시트를 함께 가져오세요")
                    continue
                }

                val entityType = FieldValueSheetMapper.entityTypeOf(if (entityCol >= 0) getCellString(row, entityCol) else null)
                val fd = fieldCache.getOrPut(Triple(universe.id, fieldKey, entityType)) {
                    db.fieldDefinitionDao().getFieldByKey(universe.id, fieldKey, entityType)
                }
                if (fd == null) {
                    result.skippedRows++
                    result.warnings.add("$rowLabel: 필드 키 '$fieldKey'을(를) 세계관 '${universe.name}'에서 찾을 수 없습니다 — '필드 정의' 시트를 함께 가져오세요")
                    continue
                }
                // 계산 필드는 수식으로 산출되는 파생값 — 내보내기와 대칭으로 저장하지 않는다
                if (fd.type == "CALCULATED") {
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
                val existing = db.characterFieldValueDao().getValue(ch.id, fd.id)
                if (value.isNotBlank()) {
                    if (existing != null) db.characterFieldValueDao().update(existing.copy(value = value))
                    else db.characterFieldValueDao().insert(CharacterFieldValue(
                        characterId = ch.id, fieldDefinitionId = fd.id, value = value
                    ))
                } else if (valueCol >= 0 && existing != null) {
                    // F1-A: 열이 있고 셀이 빈칸 = 비움 의도
                    db.characterFieldValueDao().deleteValue(ch.id, fd.id)
                    result.clearedFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("캐릭터 필드값 행 $i: ${e.message}")
            }
        }
        reportProgress(onProgress, "캐릭터 필드값", sheet.lastRowNum, totalRows)
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
        val nameColIndex = cols["이름"] ?: 0
        val anotherNameColIndex = cols["이명"] ?: -1
        val lastNameColIndex = cols["성"] ?: -1
        val firstNameColIndex = cols["이름(First)"] ?: -1
        val imageColIndex = cols["이미지경로"] ?: -1
        val representativeColIndex = cols["대표이미지"] ?: -1
        val novelColIndex = cols["작품"] ?: -1
        val memoColIndex = cols["메모"] ?: -1
        val tagsColIndex = cols["태그"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val novelCodeColIndex = cols["작품코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val pinnedColIndex = cols["고정"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        val fixedColIndices = setOf(nameColIndex, anotherNameColIndex, lastNameColIndex, firstNameColIndex, imageColIndex, novelColIndex, memoColIndex, tagsColIndex, codeColIndex, novelCodeColIndex, orderColIndex, pinnedColIndex, createdAtColIndex).filter { it >= 0 }.toSet()
        val columnFieldMap = buildColumnFieldMap(headerRow, fields, fixedColIndices, universe, result, sheetLabel)

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val code = getCellCode(row, codeColIndex, "캐릭터 행 $i", result)  // F4: 숫자 코드 방어
                val novelCode = getCellCode(row, novelCodeColIndex, "캐릭터 행 $i", result)
                val novelTitle = if (novelColIndex >= 0) getCellString(row, novelColIndex) else ""

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
                val novelColumnsPresent = novelColIndex >= 0 || novelCodeColIndex >= 0
                val novelId: Long? = (if (novelCode.isNotBlank()) db.novelDao().getNovelByCode(novelCode)?.id else null)
                    ?: if (novelTitle.isNotBlank()) {
                        resolveNovelId(novelTitle, universe?.id, result, "캐릭터 행 $i")
                    } else null

                // 열이 없으면(colIndex<0) null → 아래에서 기존 값 유지. 열이 있으면 셀 값(빈칸="" = 비움, 의도 존중) — F1-A.
                val anotherNameFromExcel: String? = if (anotherNameColIndex >= 0) getCellString(row, anotherNameColIndex) else null
                val lastNameFromExcel: String? = if (lastNameColIndex >= 0) getCellString(row, lastNameColIndex) else null
                val firstNameFromExcel: String? = if (firstNameColIndex >= 0) getCellString(row, firstNameColIndex) else null
                // imageColIndex < 0 means column is missing: use null sentinel to preserve existing images
                val rawImagePaths: String? = if (imageColIndex >= 0) getCellString(row, imageColIndex).ifBlank { "[]" } else null
                val imagePathsFromExcel: String? = rawImagePaths?.let { remapImagePaths(it) }
                // 대표이미지 열(B-103 D8). **열 없음과 빈 칸은 다른 상태다** —
                // 열 없음은 "말하지 않았다"(기존 유지), 빈 칸은 "지정 없음으로 하라"(해제).
                val representativeCell: String? =
                    if (representativeColIndex >= 0) getCellString(row, representativeColIndex) else null
                val memoFromExcel: String? = if (memoColIndex >= 0) getCellString(row, memoColIndex) else null
                val displayOrder: Long? = if (orderColIndex >= 0) {
                    val raw = getCellString(row, orderColIndex)
                    if (raw.isBlank()) null else parseNumber(raw)?.toLong()
                } else null
                val pinnedFromExcel: Boolean? = if (pinnedColIndex >= 0) parseBoolean(getCellString(row, pinnedColIndex)) else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                    val byCode = db.characterDao().getCharacterByCode(code)
                    if (byCode != null) {
                        existingChar = byCode
                    } else {
                        // F1-C: 코드가 있으나 DB에 없음 → 조용히 신규 생성하지 않고 자연키(이름) 폴백 + 경고
                        val byName = if (novelId != null) {
                            db.characterDao().getCharacterByNameAndNovel(name, novelId)
                        } else {
                            db.characterDao().getCharacterByName(name)
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
                        db.characterDao().getCharacterByNameAndNovel(name, novelId)
                    } else {
                        db.characterDao().getCharacterByName(name)
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
                    if (firstNameFromExcel == "" && existingChar.firstName.isNotBlank()) result.clearedFields++
                    if (lastNameFromExcel == "" && existingChar.lastName.isNotBlank()) result.clearedFields++
                    if (anotherNameFromExcel == "" && existingChar.anotherName.isNotBlank()) result.clearedFields++
                    if (memoFromExcel == "" && existingChar.memo.isNotBlank()) result.clearedFields++
                    // imagePaths는 `withImagePaths`로 넘긴다 — 대표 포인터(B-103)가 재매핑을
                    // 따라가고, 다른 기기에서 온 목록에 그 파일이 없으면 풀린다(D5).
                    db.characterDao().update(existingChar.copy(
                        name = name,
                        firstName = firstNameFromExcel ?: existingChar.firstName,
                        lastName = lastNameFromExcel ?: existingChar.lastName,
                        anotherName = anotherNameFromExcel ?: existingChar.anotherName,
                        novelId = if (novelColumnsPresent) novelId else existingChar.novelId,
                        memo = memoFromExcel ?: existingChar.memo,
                        updatedAt = System.currentTimeMillis(),
                        displayOrder = displayOrder ?: existingChar.displayOrder,
                        isPinned = pinnedFromExcel ?: existingChar.isPinned,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existingChar.createdAt
                    ).withImagePaths(imagePathsFromExcel ?: existingChar.imagePaths, imagePathRemap)
                        .let { applyRepresentativeCell(it, representativeCell, name, i, result) })
                    result.updatedCharacters++

                    // 사용자가 이전 세계관 필드값 삭제를 선택한 경우 정리
                    if (universe != null && conflict?.cleanupOldFields == true) {
                        db.characterFieldValueDao().deleteValuesNotInUniverse(charId, universe.id)
                    }
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    charId = db.characterDao().insert(applyRepresentativeCell(
                        Character(
                            name = name, firstName = firstNameFromExcel ?: "", lastName = lastNameFromExcel ?: "",
                            anotherName = anotherNameFromExcel ?: "", novelId = if (novelColumnsPresent) novelId else null,
                            imagePaths = imagePathsFromExcel ?: "[]", memo = memoFromExcel ?: "", code = newCode, displayOrder = displayOrder ?: i.toLong(),
                            isPinned = pinnedFromExcel ?: false, createdAt = createdAt
                        ),
                        representativeCell, name, i, result
                    ))
                    result.newCharacters++
                }

                // F3-A: 엑셀에서 작품이 바뀌어 세계관이 이동했는지 감지 (existingChar.novelId=이동 전, novelId=이동 후).
                // 이동이면 아래 필드 기록 후 편집화면과 동일한 P0 로직으로 재매핑·정리한다.
                val movedToUniverseId: Long? = if (existingChar != null && novelColumnsPresent && novelId != existingChar.novelId) {
                    val oldU = existingChar.novelId?.let { db.novelDao().getNovelById(it)?.universeId }
                    val newU = novelId?.let { db.novelDao().getNovelById(it)?.universeId }
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
                    } else if (existingChar != null) {
                        // 빈 셀 = 태그 비움. 기존 태그가 있었을 때만 삭제·요약 집계
                        val hadTags = db.characterTagDao().getTagsByCharacterList(charId).isNotEmpty()
                        if (hadTags) {
                            db.characterTagDao().deleteAllByCharacter(charId)
                            result.clearedFields++
                        }
                    }
                }

                // 동적 필드 값 가져오기 (빈 셀 = 기존 값 삭제)
                var hasSemanticField = false
                for ((colIndex, field) in columnFieldMap) {
                    // F4: CALCULATED는 다른 필드로부터 실시간 산출되는 파생값 — 저장하지 않는다(읽기 전용).
                    // 내보내기 시 계산 결과를 표시하지만 가져오기 때 저장하면 stale 중복 데이터가 된다.
                    if (field.type == "CALCULATED") {
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
                        when (field.type) {
                            "NUMBER" -> if (value.toDoubleOrNull() == null) {
                                result.warnings.add("캐릭터 행 $i: 숫자 필드 '${field.name}'에 숫자가 아닌 값 '$value'이(가) 저장됨 — 통계에서 제외될 수 있습니다")
                            }
                            "GRADE" -> if (GradeValueResolver.resolveForDisplay(field, value) == null) {
                                result.warnings.add("캐릭터 행 $i: 등급 필드 '${field.name}'의 값 '$value'을(를) 인식할 수 없습니다 — 통계·수식에서 제외될 수 있습니다")
                            }
                            "BODY_SIZE" -> if (!value.any { it.isDigit() }) {
                                result.warnings.add("캐릭터 행 $i: 신체 사이즈 필드 '${field.name}'의 값 '$value'에 숫자가 없어 통계에 반영되지 않을 수 있습니다")
                            }
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
                    val existingValue = db.characterFieldValueDao().getValue(charId, field.id)
                    if (value.isNotBlank()) {
                        if (existingValue != null) {
                            db.characterFieldValueDao().update(existingValue.copy(value = value))
                        } else {
                            db.characterFieldValueDao().insert(CharacterFieldValue(
                                characterId = charId, fieldDefinitionId = field.id, value = value
                            ))
                        }
                        if (!hasSemanticField && SemanticRole.fromConfig(field.config) != null) {
                            hasSemanticField = true
                        }
                    } else if (existingValue != null) {
                        // 빈 셀 = 값 삭제 (F1-A 규칙 가: 요약 집계)
                        db.characterFieldValueDao().deleteValue(charId, field.id)
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
        val yearColIndex = cols["연도"] ?: 0
        // 선택 속성 열: 위치 폴백을 쓰면 열 삭제 시 이웃 열을 오독하므로 -1(=없음). 열 없음이면 UPDATE에서 기존값 유지.
        val monthColIndex = cols["월"] ?: -1
        val dayColIndex = cols["일"] ?: -1
        val calendarColIndex = cols["역법"] ?: -1
        val eventTypeColIndex = cols["사건 유형"] ?: -1
        // 필수 컬럼: 위치 폴백을 쓰면 컬럼 삭제 시 이웃 컬럼 데이터가 사건 설명으로 오기록되므로 검증 후 스킵
        val descColIndex = cols["사건 설명"]
            ?: cols.entries.firstOrNull { it.key.contains("설명") }?.value
            ?: requiredCol(cols, "사건 설명", sheet.sheetName, result) ?: return
        // 선택 연결 열: 위치 폴백 금지(스펙상 5·6은 사건 설명·관련 작품이라 폴백이 이웃 열을 오독) — 열 없음(-1)이면 기존 연결 유지
        val novelColIndex = cols["관련 작품"] ?: -1
        val charColIndex = cols["관련 캐릭터"] ?: -1
        val charCodeColIndex = cols["관련캐릭터코드"] ?: -1
        // F1-A: 참가자 열이 실제로 헤더에 존재하는지 (위치 폴백만으로는 "빈칸=삭제" 규칙을 적용하지 않음 — 구버전 파일 오삭제 방지)
        val participantColumnPresent = cols.containsKey("관련 캐릭터") || charCodeColIndex >= 0
        val novelCodeColIndex = cols["관련작품코드"] ?: -1
        // F1-A: 작품 연결 열이 실제로 헤더에 존재하는지 (동일 취지)
        val novelLinkColumnPresent = cols.containsKey("관련 작품") || novelCodeColIndex >= 0
        val displayOrderColIndex = cols["정렬순서"] ?: -1
        val isTemporaryColIndex = cols["임시배치"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        // 세계관 소속 열 — 열이 없는 구버전 파일은 기존처럼 관련 작품에서 유도한다(하위 호환).
        val eventUniverseNameColIndex = cols["세계관"] ?: -1
        val eventUniverseCodeColIndex = cols["세계관코드"] ?: -1

        val allNovels = db.novelDao().getAllNovelsList()
        val eventCodesSeen = mutableSetOf<String>()
        // 정의 없는 "필드:" 열의 값 유실 고지용 — (헤더명) 단위로 1회만 경고
        val droppedEntityFieldHeaders = mutableSetOf<String>()

        // 사건 커스텀 필드 컬럼 (B-10): "필드:{이름}" 또는 "필드:{이름}({세계관})" 헤더 스캔.
        // 정규식 추측 파싱은 이름이 괄호로 끝나는 필드('규모(명)')를 세계관 한정으로 오인하므로,
        // **내보내기 규칙의 결정론적 역함수**(기대 헤더 → 필드)를 최우선으로 조회한다.
        val allEventFields = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_EVENT)
        val universeNamesById = db.universeDao().getAllUniversesList().associate { it.id to it.name }
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
                eventFieldColumns.add(EventFieldColumn(ci, header, exact.name, universeNamesById[exact.universeId], exact))
                continue
            }
            val parsed = EntityFieldHeaders.parseFallback(header, knownEventFieldNames, knownUniverseNames) ?: continue
            eventFieldColumns.add(EventFieldColumn(ci, header, parsed.fieldName, parsed.universeName, null))
        }

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt()
                if (year == null) {
                    // 행이 조용히 사라지지 않도록 보고 (빈 행은 제외)
                    if (yearStr.isNotBlank() || !getCellString(row, descColIndex).isBlank()) {
                        result.skippedRows++
                        result.errors.add("연표 행 $i: 연도 '$yearStr'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    }
                    continue
                }
                val description = getCellString(row, descColIndex)
                if (description.isBlank()) continue

                // F1-B: 범위 밖/해석 불가 월·일은 조용히 버리지 않고 연도처럼 경고
                val month = parseMonthWithWarn(row, monthColIndex, "연표 행 $i", result)
                val day = parseDayWithWarn(row, dayColIndex, month, "연표 행 $i", result)
                val calendarType = getCellString(row, calendarColIndex).ifBlank { "천개력" }
                val eventType = if (eventTypeColIndex >= 0) {
                    val eventTypeLabel = getCellString(row, eventTypeColIndex)
                    val mapped = labelToEventType(eventTypeLabel)
                    // F4: 인식 못한 사건 유형(오타)은 조용히 '일반'으로 떨어뜨리지 않고 경고
                    if (eventTypeLabel.isNotBlank() && mapped == TimelineEvent.TYPE_NONE &&
                        eventTypeLabel.trim() !in setOf("일반", "general", "normal")) {
                        result.warnings.add("연표 행 $i: 사건 유형 '$eventTypeLabel'을(를) 인식할 수 없어 '일반'으로 처리 — 탄생/사망/일반 중 선택")
                    }
                    mapped
                } else TimelineEvent.TYPE_NONE
                val novelTitle = getCellString(row, novelColIndex)
                val novelCode = getCellCode(row, novelCodeColIndex, "연표 행 $i", result)
                val displayOrder: Int? = if (displayOrderColIndex >= 0) {
                    val raw = getCellString(row, displayOrderColIndex)
                    if (raw.isBlank()) null else parseNumber(raw)?.toInt()
                } else null
                val isTemporary = if (isTemporaryColIndex >= 0) parseBoolean(getCellString(row, isTemporaryColIndex)) else false
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                // 작품 해석: 콤마 구분 복수 작품 지원
                val novelTitles = splitCsv(novelTitle)
                val novelCodes = splitCsv(novelCode)
                val resolvedNovels = if (novelCodes.isNotEmpty()) {
                    novelCodes.mapNotNull { code -> db.novelDao().getNovelByCode(code) }
                } else {
                    emptyList()
                }.ifEmpty {
                    novelTitles.mapNotNull { title -> allNovels.find { it.title == title } }
                }
                val novelIds = resolvedNovels.map { it.id }

                // 세계관 소속: 명시 열(코드 우선 → 이름) 우선, 없으면 관련 작품에서 유도(구버전 호환).
                // 작품 미연결 사건도 이 열 덕분에 신규 기기 복원 시 세계관을 잃지 않는다.
                val explicitUniverse: com.novelcharacter.app.data.model.Universe? = run {
                    val uCode = getCellCode(row, eventUniverseCodeColIndex, "연표 행 $i", result)
                    val uName = if (eventUniverseNameColIndex >= 0) getCellString(row, eventUniverseNameColIndex) else ""
                    if (uCode.isBlank() && uName.isBlank()) return@run null
                    val resolved = (if (uCode.isNotBlank()) db.universeDao().getUniverseByCode(uCode) else null)
                        ?: (if (uName.isNotBlank()) db.universeDao().getUniverseByName(uName) else null)
                    if (resolved == null) {
                        result.warnings.add("연표 행 $i: 세계관 '${uName.ifBlank { uCode }}'을(를) 찾을 수 없어 관련 작품에서 유도합니다")
                    }
                    resolved
                }
                val derivedUniverseId = resolvedNovels.firstOrNull()?.universeId
                if (explicitUniverse != null && derivedUniverseId != null && explicitUniverse.id != derivedUniverseId) {
                    result.warnings.add("연표 행 $i: 세계관 열('${explicitUniverse.name}')과 관련 작품의 세계관이 달라 세계관 열을 우선합니다")
                }
                val universeId = explicitUniverse?.id ?: derivedUniverseId

                val fileCode = getCellCode(row, codeColIndex, "연표 행 $i", result)
                if (fileCode.isNotBlank() && !eventCodesSeen.add(fileCode)) {
                    result.warnings.add("연표 행 $i: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 사건을 덮어씁니다")
                }
                // 매칭: 코드 우선(설명·연도 편집을 같은 사건으로 인식) → 자연키 폴백(구버전 파일 호환)
                val existingEvent = (if (fileCode.isNotBlank()) db.timelineDao().getEventByCode(fileCode) else null)
                    ?: db.timelineDao().getEventByNaturalKey(year, description)

                val eventId: Long
                if (existingEvent != null) {
                    eventId = existingEvent.id
                    // 세계관 열이 명시됐으면 그 값, 아니면 작품 해석 성공 시 유도값, 둘 다 없으면 기존 세계관 보존
                    val effectiveUniverseId =
                        if (explicitUniverse != null || novelIds.isNotEmpty()) universeId else existingEvent.universeId
                    db.timelineDao().update(existingEvent.copy(
                        // 코드 매칭 시 연도·설명은 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
                        year = year, description = description,
                        // 열 없음(colIndex<0) = 기존값 유지, 열 있음+빈칸 = 삭제/무값 (외부 편집에서 열 삭제 시 무음 손실 방지)
                        month = if (monthColIndex >= 0) month else existingEvent.month,
                        day = if (dayColIndex >= 0) day else existingEvent.day,
                        calendarType = if (calendarColIndex >= 0) calendarType else existingEvent.calendarType,
                        eventType = if (eventTypeColIndex >= 0) eventType else existingEvent.eventType,
                        universeId = effectiveUniverseId,
                        displayOrder = displayOrder ?: existingEvent.displayOrder,
                        isTemporary = if (isTemporaryColIndex >= 0) isTemporary else existingEvent.isTemporary,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existingEvent.createdAt,
                        // 코드 없는 기존 행은 점진 백필 (기존 코드는 절대 덮어쓰지 않음 — 외부 참조 보호)
                        code = existingEvent.code ?: fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
                    // 작품이 해석된 경우에만 M2M 교체; 해석 실패 시 기존 관계 유지 + 경고
                    if (novelIds.isNotEmpty()) {
                        db.timelineDao().replaceEventNovels(eventId, novelIds)
                    } else if (novelTitle.isNotBlank() || novelCode.isNotBlank()) {
                        result.warnings.add("사건 행 $i: 작품 '${novelTitle}'을(를) 찾을 수 없어 기존 작품 연결을 유지합니다")
                    } else if (novelLinkColumnPresent) {
                        // F1-A 규칙 가: 작품 열이 있으나 비어 있음 → 기존 작품 연결 삭제 (요약 집계, 세계관 소속은 universeId로 유지)
                        if (db.timelineDao().getNovelIdsForEvent(eventId).isNotEmpty()) {
                            db.timelineDao().deleteEventNovelCrossRefsByEvent(eventId)
                            result.clearedFields++
                        }
                    }
                    result.updatedEvents++
                } else {
                    eventId = db.timelineDao().insert(TimelineEvent(
                        year = year, month = month, day = day,
                        calendarType = calendarType, description = description,
                        eventType = eventType,
                        universeId = universeId,
                        displayOrder = displayOrder ?: i, isTemporary = isTemporary,
                        createdAt = createdAt,
                        // 파일의 코드를 보존해 기기 이전 후에도 왕복 정체성 유지 (없으면 자동 생성)
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
                    db.timelineDao().replaceEventNovels(eventId, novelIds)
                    result.newEvents++
                    if (novelIds.isEmpty() && novelTitle.isNotBlank()) {
                        result.warnings.add("사건 행 $i: 작품 '${novelTitle}'을(를) 찾을 수 없어 작품 미지정 상태로 생성됨")
                    }
                }
                matchedEventIds.add(eventId)

                // 사건 커스텀 필드 값 반영 (B-10) — 빈 셀 = 기존 값 삭제 (캐릭터 필드와 동일 규약)
                val eventUniverseId = if (existingEvent != null) {
                    if (explicitUniverse != null || novelIds.isNotEmpty()) universeId else existingEvent.universeId
                } else {
                    universeId
                }
                if (eventFieldColumns.isNotEmpty() && eventUniverseId != null) {
                    val universeFields = allEventFields.filter { it.universeId == eventUniverseId }
                    val universeName = universeNamesById[eventUniverseId]
                    val newValues = mutableListOf<com.novelcharacter.app.data.model.EventFieldValue>()
                    // 시트에 실제로 존재하고 해석에 성공한 필드만 교체 대상 — 열이 없던 필드값은 보존(F1-A 열 단위)
                    val resolvedFieldIds = mutableListOf<Long>()
                    for (col in eventFieldColumns) {
                        val ci = col.colIndex
                        // 헤더 정확 일치로 이미 필드가 확정된 열은 그 필드의 세계관에서만 유효하다
                        val fieldDef = when {
                            col.resolved != null ->
                                if (col.resolved.universeId == eventUniverseId) col.resolved else null
                            col.universeName != null && col.universeName != universeName -> null
                            else -> universeFields.find { it.name == col.fieldName }
                        }
                        if (fieldDef == null) {
                            // 이 사건의 세계관에 해당 필드가 없는 것은 정상(다른 세계관 열)이므로,
                            // 셀에 값이 있을 때만 1회 경고한다. 키는 원본 헤더 — 필드명으로 묶으면
                            // 서로 다른 세계관의 동명 열이 하나로 뭉쳐 경고가 누락된다.
                            if (getCellString(row, ci).isNotBlank() && droppedEntityFieldHeaders.add(col.header)) {
                                result.warnings.add(
                                    "사건 시트의 필드 열 '${col.header}'에 해당하는 사건 필드 정의를 찾을 수 없어 값이 반영되지 않았습니다 — '필드 정의' 시트(대상=사건)를 함께 가져오세요"
                                )
                            }
                            continue
                        }
                        // 계산 필드는 수식으로 산출되는 파생값 — 캐릭터 시트(F4)와 대칭으로 저장하지 않는다.
                        // resolvedFieldIds에도 넣지 않아 기존 행을 건드리지 않는다(잔여 행 정리는
                        // 편집 저장의 커버 규칙이 맡는다 — EventFieldValueMerge).
                        if (fieldDef.type == "CALCULATED") {
                            if (getCellString(row, ci).isNotBlank() && droppedEntityFieldHeaders.add(col.header)) {
                                result.warnings.add(
                                    "사건 시트의 '${col.header}' 열은 계산 필드라 저장하지 않습니다(다른 필드로부터 산출됨)"
                                )
                            }
                            continue
                        }
                        resolvedFieldIds.add(fieldDef.id)
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
                            db.characterDao().getCharacterByCode(code)?.let { resolved[it.id] = it }
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
                        // birth/death 사건이면 관련 캐릭터의 상태변화 동기화 대상에 추가
                        if (eventType == TimelineEvent.TYPE_BIRTH || eventType == TimelineEvent.TYPE_DEATH) {
                            val stateKey = if (eventType == TimelineEvent.TYPE_BIRTH) CharacterStateChange.KEY_BIRTH else CharacterStateChange.KEY_DEATH
                            for (character in resolvedCharacters) {
                                val existing = db.characterStateChangeDao()
                                    .getChangeByNaturalKey(character.id, year, stateKey, year.toString())
                                if (existing == null) {
                                    db.characterStateChangeDao().insert(CharacterStateChange(
                                        characterId = character.id, year = year, month = month, day = day,
                                        fieldKey = stateKey, newValue = year.toString()
                                    ))
                                }
                                val uId = universeId ?: character.novelId?.let { db.novelDao().getNovelById(it)?.universeId }
                                if (uId != null) {
                                    pendingSyncCharacters[character.id] = uId
                                }
                            }
                        }
                    }
                } else if (participantColumnPresent && existingEvent != null) {
                    // F1-A 규칙 가: 참가자 열이 있으나 셀이 비어 있음 → 기존 참가자 연결 삭제 (요약 집계)
                    if (db.timelineDao().getCharacterIdsForEvent(eventId).isNotEmpty()) {
                        db.timelineDao().deleteCrossRefsByEvent(eventId)
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
        val charNameColIndex = cols["캐릭터"] ?: 0
        // 위치 폴백 금지 — 열을 지운 파일에서 '연도' 열을 작품 제목으로 읽어(제목이 숫자면 실제로 매칭된다)
        // 동명이인을 엉뚱한 작품 기준으로 무근거 선택한다. 열 없음이면 힌트 없이 엄격 해석한다.
        val novelColIndex = cols["작품"] ?: -1
        // 필수 컬럼: 위치 폴백을 쓰면 컬럼 삭제 시 이웃 컬럼 데이터가 그대로 기록되므로 검증 후 스킵
        val yearColIndex = requiredCol(cols, "연도", sheet.sheetName, result) ?: return
        val monthColIndex = cols["월"] ?: -1
        val dayColIndex = cols["일"] ?: -1
        val fieldKeyColIndex = requiredCol(cols, "필드키", sheet.sheetName, result) ?: return
        val newValueColIndex = requiredCol(cols, "새 값", sheet.sheetName, result) ?: return
        val descColIndex = cols["설명"] ?: -1
        val charCodeColIndex = cols["캐릭터코드"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        val allNovels = db.novelDao().getAllNovelsList()
        val changeCodesSeen = mutableSetOf<String>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val charName = getCellString(row, charNameColIndex)
                if (charName.isBlank()) continue

                val novelTitle = getCellString(row, novelColIndex)
                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt()
                if (year == null) {
                    result.skippedRows++
                    result.errors.add("상태변화 행 $i: 연도 '$yearStr'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    continue
                }

                val month = parseMonthWithWarn(row, monthColIndex, "상태변화 행 $i", result)
                val day = parseDayWithWarn(row, dayColIndex, month, "상태변화 행 $i", result)
                val fieldKey = getCellString(row, fieldKeyColIndex)
                if (fieldKey.isBlank()) continue
                val newValue = getCellString(row, newValueColIndex)
                if (newValue.isBlank()) {
                    result.skippedRows++
                    result.warnings.add("상태변화 행 $i: 빈 값은 허용되지 않습니다")
                    continue
                }
                val description = getCellString(row, descColIndex)
                val charCode = getCellCode(row, charCodeColIndex, "상태변화 행 $i", result)
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                // Resolve character: code-first, then strict name lookup (동명이인 모호성 감지)
                val character: Character = when {
                    charCode.isNotBlank() -> {
                        val found = db.characterDao().getCharacterByCode(charCode)
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
                        val hintNovelId = if (novelColIndex >= 0) {
                            val t = getCellString(row, novelColIndex)
                            if (t.isBlank()) null else allNovels.find { it.title == t }?.id
                        } else null
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

                val fileCode = getCellCode(row, codeColIndex, "상태변화 행 $i", result)
                if (fileCode.isNotBlank() && !changeCodesSeen.add(fileCode)) {
                    result.warnings.add("상태변화 행 $i: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }
                // 매칭: 코드 우선(연도·필드키·값 편집을 같은 이력으로 인식) → 자연키 폴백(구버전 파일 호환)
                val existing = (if (fileCode.isNotBlank()) db.characterStateChangeDao().getChangeByCode(fileCode) else null)
                    ?: db.characterStateChangeDao().getChangeByNaturalKey(character.id, year, fieldKey, newValue)

                if (existing != null) {
                    db.characterStateChangeDao().update(existing.copy(
                        // 코드 매칭 시 자연키 구성 요소도 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
                        characterId = character.id, year = year, fieldKey = fieldKey, newValue = newValue,
                        // 열 없음 = 기존값 유지 (열 삭제로 인한 무음 손실 방지)
                        month = if (monthColIndex >= 0) month else existing.month,
                        day = if (dayColIndex >= 0) day else existing.day,
                        description = if (descColIndex >= 0) description else existing.description,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt,
                        code = existing.code ?: fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
                    matchedStateChangeIds.add(existing.id)
                    result.updatedStateChanges++
                } else {
                    val newId = db.characterStateChangeDao().insert(CharacterStateChange(
                        characterId = character.id, year = year, month = month, day = day,
                        fieldKey = fieldKey, newValue = newValue, description = description,
                        createdAt = createdAt,
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
                    matchedStateChangeIds.add(newId)
                    result.newStateChanges++
                }

                // __death/__birth 상태변화 임포트 시 필드 동기화 대상에 추가
                if (fieldKey == CharacterStateChange.KEY_DEATH || fieldKey == CharacterStateChange.KEY_BIRTH) {
                    val novel = character.novelId?.let { db.novelDao().getNovelById(it) }
                    val uId = novel?.universeId
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
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        // 필수 컬럼: 위치 폴백으로 이웃 컬럼을 오독하지 않도록 검증 후 스킵
        val char2NameColIndex = requiredCol(cols, "캐릭터2", sheet.sheetName, result) ?: return
        val typeColIndex = requiredCol(cols, "관계 유형", sheet.sheetName, result) ?: return
        val descColIndex = cols["설명"] ?: -1
        val intensityColIndex = cols["강도"] ?: -1
        val bidirectionalColIndex = cols["양방향"] ?: -1
        val displayOrderColIndex = cols["표시순서"] ?: -1
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1
        val factionColIndex = cols["세력"] ?: -1
        val factionCodeColIndex = cols["세력코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        // 관계 자체의 안정 식별자 — 있으면 '관계 유형'을 고쳐도 같은 관계로 인식한다
        val relCodeColIndex = cols["코드"] ?: -1

        // 세력 참조 해석은 FactionIndex(단일 소스)로 — 전 세계관 first-match 금지
        val factionRefUsed = factionColIndex >= 0 || factionCodeColIndex >= 0
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
                val char1Name = getCellString(row, char1NameColIndex)
                val char2Name = getCellString(row, char2NameColIndex)
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val relationshipType = getCellString(row, typeColIndex)
                if (relationshipType.isBlank()) {
                    // F1-B: 두 캐릭터가 채워졌는데 관계 유형만 비어 있으면 조용히 버리지 않고 경고
                    result.skippedRows++
                    result.warnings.add("관계 행 $i: '$char1Name'–'$char2Name' 관계 유형이 비어 있어 건너뜀 (필수 항목)")
                    continue
                }
                val description = getCellString(row, descColIndex)
                val intensity = parseIntensityWithWarn(row, intensityColIndex, 5, "관계 행 $i", result) ?: 5
                val isBidirectional = if (bidirectionalColIndex >= 0) parseBoolean(getCellString(row, bidirectionalColIndex)) else true
                val displayOrder: Int? = if (displayOrderColIndex >= 0) {
                    val raw = getCellString(row, displayOrderColIndex)
                    if (raw.isBlank()) null else parseNumber(raw)?.toInt()
                } else null
                val char1Code = getCellCode(row, char1CodeColIndex, "관계 행 $i", result)
                val char2Code = getCellCode(row, char2CodeColIndex, "관계 행 $i", result)
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()
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
                val factionName = if (factionColIndex >= 0) getCellString(row, factionColIndex) else ""
                val factionCode = getCellCode(row, factionCodeColIndex, "관계 행 $i", result)
                var factionIntent = refColumnIntent(factionColIndex >= 0, factionCodeColIndex >= 0, factionName, factionCode)
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

                val existingRels = db.characterRelationshipDao().getRelationshipsForCharacterList(char1.id)
                val pairRels = existingRels.filter { rel ->
                    (rel.characterId1 == char1.id && rel.characterId2 == char2.id) ||
                    (rel.characterId1 == char2.id && rel.characterId2 == char1.id)
                }
                // 매칭 규약: 코드(안정 식별자) 우선 → 자연키(쌍+유형) 폴백.
                // 코드로 잡히면 '관계 유형' 편집이 rename으로 인식되어 관계가 분열하지 않는다.
                val relCode = getCellCode(row, relCodeColIndex, "관계 행 $i", result)
                val byCode = if (relCode.isNotBlank()) db.characterRelationshipDao().getByCode(relCode) else null
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
                    if (descColIndex >= 0 && description == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 참조 열 쌍 규약: 열 없음·미해석 → 기존 유지 / 빈칸 → 해제 / 해석 성공 → 교체.
                    // 설정만 받고 해제는 무시하던 비대칭을 없앤다(사용자가 명시적으로 비운 셀을 무음 폐기 금지).
                    val effectiveFactionId = when (factionIntent) {
                        RefIntent.KEEP -> existing.factionId
                        RefIntent.CLEAR -> null
                        RefIntent.LOOKUP -> factionId
                    }
                    if (existing.factionId != null && effectiveFactionId == null) result.clearedFields++
                    if (existing.factionId == null && effectiveFactionId != null) factionAttachedRows.add(i)
                    db.characterRelationshipDao().update(existing.copy(
                        // 코드 매칭 시 유형은 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
                        relationshipType = relationshipType,
                        description = if (descColIndex >= 0) description else existing.description,
                        intensity = if (intensityColIndex >= 0) intensity else existing.intensity,
                        isBidirectional = if (bidirectionalColIndex >= 0) isBidirectional else existing.isBidirectional,
                        displayOrder = displayOrder ?: existing.displayOrder,
                        factionId = effectiveFactionId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt,
                        // 코드 없는 기존 행은 점진 백필 (기존 코드는 절대 덮어쓰지 않음 — 외부 참조 보호)
                        code = existing.code ?: relCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
                    matchedRelationshipIds.add(existing.id)
                    result.updatedRelationships++
                } else {
                    // 잔여 관계 고지는 행마다 하지 않는다 — 같은 쌍의 다른 행이 아직 처리되지 않았을 뿐인데
                    // "정리하세요"라고 안내하면 사용자가 멀쩡한 데이터를 지운다. 루프 종료 후 1회 집계한다.
                    if (relCodeColIndex < 0) touchedPairs.putIfAbsent(setOf(char1.id, char2.id), i to "${char1Name}–${char2Name}")
                    val newId = db.characterRelationshipDao().insert(CharacterRelationship(
                        characterId1 = char1.id, characterId2 = char2.id,
                        relationshipType = relationshipType, description = description,
                        intensity = intensity, isBidirectional = isBidirectional,
                        displayOrder = displayOrder ?: i, factionId = factionId,
                        createdAt = createdAt,
                        // 파일의 코드를 보존해 기기 이전 후에도 왕복 정체성 유지 (없으면 자동 생성)
                        code = relCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
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
        val char1NameColIndex = cols["캐릭터1"] ?: 0
        // 필수 컬럼: 위치 폴백으로 이웃 컬럼을 오독하지 않도록 검증 후 스킵
        val char2NameColIndex = requiredCol(cols, "캐릭터2", sheet.sheetName, result) ?: return
        val yearColIndex = requiredCol(cols, "연도", sheet.sheetName, result) ?: return
        val monthColIndex = cols["월"] ?: -1
        val dayColIndex = cols["일"] ?: -1
        // 선택 속성 열: 위치 폴백 제거(-1) — 열 삭제 시 이웃 열 오독·무음 손실 방지. 열 없음이면 UPDATE에서 기존값 유지.
        val relTypeColIndex = cols["관계 유형"] ?: -1
        val descColIndex = cols["설명"] ?: -1
        val intensityColIndex = cols["강도"] ?: -1
        val bidirectionalColIndex = cols["양방향"] ?: -1
        val eventIdColIndex = cols["연결사건ID"] ?: -1
        val eventCodeColIndex = cols["연결사건코드"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val char1CodeColIndex = cols["캐릭터1코드"] ?: -1
        val char2CodeColIndex = cols["캐릭터2코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        // 부모 관계 식별자 — 코드 우선, 없으면 유형, 둘 다 없으면 쌍 기반 폴백(모호하면 경고)
        val parentTypeColIndex = cols["부모관계유형"] ?: -1
        val parentCodeColIndex = cols["관계코드"] ?: -1
        val changeCodesSeen = mutableSetOf<String>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val char1Name = getCellString(row, char1NameColIndex)
                val char2Name = getCellString(row, char2NameColIndex)
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val yearStr = getCellString(row, yearColIndex)
                val year = parseNumber(yearStr)?.toInt()
                if (year == null) {
                    result.skippedRows++
                    result.errors.add("관계 변화 행 $i: 연도 '$yearStr'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    continue
                }
                val month = parseMonthWithWarn(row, monthColIndex, "관계 변화 행 $i", result)
                val day = parseDayWithWarn(row, dayColIndex, month, "관계 변화 행 $i", result)
                val relationshipType = getCellString(row, relTypeColIndex)
                val description = getCellString(row, descColIndex)
                val intensity = parseIntensityWithWarn(row, intensityColIndex, 5, "관계 변화 행 $i", result) ?: 5
                // 열 없음 → 엔티티 기본값(양방향 true) — 관계·세력 관계 시트와 동일 규칙 (빈칸→false 오독으로 기본값 뒤집힘 방지)
                val isBidirectional = if (bidirectionalColIndex >= 0) parseBoolean(getCellString(row, bidirectionalColIndex)) else true
                // 연결 사건 해석: 코드 우선 (id는 복원/기기 이전 시 변하므로 구버전 폴백 전용)
                val eventColumnPresent = eventCodeColIndex >= 0 || eventIdColIndex >= 0
                val eventCode = getCellCode(row, eventCodeColIndex, "관계변화 행 $i", result)
                val eventId: Long? = when {
                    eventCode.isNotBlank() -> {
                        val found = db.timelineDao().getEventByCode(eventCode)?.id
                        if (found == null) {
                            result.warnings.add("관계변화 행 $i: 연결사건코드 '${eventCode}'에 해당하는 사건을 찾을 수 없어 연결을 비웁니다")
                        }
                        found
                    }
                    eventIdColIndex >= 0 -> {
                        // 구버전 id 폴백도 실존 검증 — 복원 후 재발급된 id가 엉뚱한 사건을 가리키거나 FK 오류로 행이 죽는 것 방지
                        val rawId = parseNumber(getCellString(row, eventIdColIndex))?.toLong()
                        if (rawId != null && db.timelineDao().getEventById(rawId) == null) {
                            result.warnings.add("관계변화 행 $i: 연결사건ID '${rawId}'에 해당하는 사건이 없어 연결을 비웁니다 — 최신 백업의 연결사건코드 열을 사용하세요")
                            null
                        } else rawId
                    }
                    else -> null
                }
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                val char1Code = getCellCode(row, char1CodeColIndex, "관계변화 행 $i", result)
                val char2Code = getCellCode(row, char2CodeColIndex, "관계변화 행 $i", result)

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
                val pairRelationships = db.characterRelationshipDao()
                    .getRelationshipsForCharacterList(char1.id)
                    .filter { rel ->
                        (rel.characterId1 == char1.id && rel.characterId2 == char2.id) ||
                        (rel.characterId1 == char2.id && rel.characterId2 == char1.id)
                    }
                if (pairRelationships.isEmpty()) {
                    result.skippedRows++
                    result.errors.add("관계변화 행 $i: '${char1Name}'과(와) '${char2Name}' 간의 관계를 찾을 수 없음")
                    continue
                }
                val parentType = if (parentTypeColIndex >= 0) getCellString(row, parentTypeColIndex) else ""
                val parentCode = getCellCode(row, parentCodeColIndex, "관계변화 행 $i", result)
                val byParentCode = if (parentCode.isNotBlank()) {
                    pairRelationships.find { it.code == parentCode }
                        ?: db.characterRelationshipDao().getByCode(parentCode)?.takeIf { rel ->
                            (rel.characterId1 == char1.id && rel.characterId2 == char2.id) ||
                            (rel.characterId1 == char2.id && rel.characterId2 == char1.id)
                        }
                } else null
                val relationship = when {
                    // 코드가 최우선 — 부모 관계의 유형이 편집돼도 이력이 정확히 따라간다
                    byParentCode != null -> byParentCode
                    parentType.isNotBlank() -> {
                        val exact = pairRelationships.filter { it.relationshipType == parentType }
                        when {
                            exact.size == 1 -> exact.first()
                            exact.size > 1 -> exact.first()  // 유니크 키상 도달 불가지만 방어적으로 결정적 선택
                            pairRelationships.size == 1 -> {
                                result.warnings.add("관계변화 행 $i: 부모관계유형 '$parentType'과 일치하는 관계가 없어 유일한 '${pairRelationships.first().relationshipType}' 관계에 연결했습니다")
                                pairRelationships.first()
                            }
                            else -> null
                        }
                    }
                    pairRelationships.size == 1 -> pairRelationships.first()
                    else -> null
                }
                if (relationship == null) {
                    result.skippedRows++
                    result.errors.add(
                        "관계변화 행 $i: '${char1Name}'–'${char2Name}' 사이에 관계가 ${pairRelationships.size}개 있어 어느 관계의 이력인지 확정할 수 없습니다 — '부모관계유형' 열에 대상 관계의 유형(${pairRelationships.joinToString("/") { it.relationshipType }})을 적으세요"
                    )
                    continue
                }

                val fileCode = getCellCode(row, codeColIndex, "관계변화 행 $i", result)
                if (fileCode.isNotBlank() && !changeCodesSeen.add(fileCode)) {
                    result.warnings.add("관계변화 행 $i: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }
                // 매칭: 코드 우선(연월일 편집을 같은 이력으로 인식) → 자연키 폴백(구버전 파일 호환)
                val existing = (if (fileCode.isNotBlank()) db.characterRelationshipChangeDao().getChangeByCode(fileCode) else null)
                    ?: db.characterRelationshipChangeDao().getChangeByNaturalKey(relationship.id, year, month, day)
                if (existing != null) {
                    // 빈칸=삭제 집계(변수 제어): 열이 있고 값이 비었는데 기존값이 있으면 초기화로 계수
                    if (descColIndex >= 0 && description == "" && existing.description.isNotBlank()) result.clearedFields++
                    db.characterRelationshipChangeDao().update(existing.copy(
                        // 코드 매칭 시 자연키 구성 요소도 편집 가능한 값 (자연키 매칭 시엔 동일값이라 무해)
                        // 열 없음 = 기존값 유지 (열 삭제로 인한 무음 손실 방지)
                        relationshipId = relationship.id, year = year,
                        month = if (monthColIndex >= 0) month else existing.month,
                        day = if (dayColIndex >= 0) day else existing.day,
                        relationshipType = if (relTypeColIndex >= 0) relationshipType else existing.relationshipType,
                        description = if (descColIndex >= 0) description else existing.description,
                        intensity = if (intensityColIndex >= 0) intensity else existing.intensity,
                        isBidirectional = if (bidirectionalColIndex >= 0) isBidirectional else existing.isBidirectional,
                        eventId = if (eventColumnPresent) eventId else existing.eventId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt,
                        code = existing.code ?: fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
                    matchedRelationshipChangeIds.add(existing.id)
                    result.updatedRelationshipChanges++
                } else {
                    val newId = db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(
                        relationshipId = relationship.id,
                        year = year, month = month, day = day,
                        relationshipType = relationshipType, description = description,
                        intensity = intensity, isBidirectional = isBidirectional,
                        eventId = eventId, createdAt = createdAt,
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    ))
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
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val genderColIndex = cols["성별"] ?: 1
        val originColIndex = cols["출처"] ?: 2
        val notesColIndex = cols["메모"] ?: 3
        // 위치 폴백 금지 — 열을 지우면 '사용 캐릭터'를 사용여부로 오독한다
        val usedColIndex = cols["사용여부"] ?: -1
        // 위치 폴백 금지 — 열을 지우면 이웃 열(생성일/코드)을 이름으로 오독한다
        val usedByColIndex = cols["사용 캐릭터"] ?: -1
        val charCodeColIndex = cols["사용캐릭터코드"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        val codeColIndex = cols["코드"] ?: -1  // F3-D: 이름 은행 항목 자체 코드

        val existingNamesMap = db.nameBankDao().getAllNamesList()
            .associateBy { "${it.name}\u0000${it.gender}" }
            .toMutableMap()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val gender = getCellString(row, genderColIndex)
                val origin = getCellString(row, originColIndex)
                val notes = getCellString(row, notesColIndex)
                val usedFlag = sheetBooleanOrKeep(usedColIndex >= 0, getCellString(row, usedColIndex))
                val usedByCharName = if (usedByColIndex >= 0) getCellString(row, usedByColIndex) else ""
                val usedByCharCode = getCellCode(row, charCodeColIndex, "이름 은행 행 $i", result)
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                // 사용 캐릭터는 편집 가능한 '사용 캐릭터' + readOnly '사용캐릭터코드'의 참조 열 쌍이다
                // (관계 시트의 '세력'/'세력코드'와 동형): 유무는 이름 열이, 대상은 코드가 정한다.
                val usedIntent = refColumnIntent(usedByColIndex >= 0, charCodeColIndex >= 0, usedByCharName, usedByCharCode)
                val resolvedUsedById: Long? = if (usedIntent != RefIntent.LOOKUP) null else {
                    (if (usedByCharCode.isNotBlank()) db.characterDao().getCharacterByCode(usedByCharCode)?.id else null)
                        ?: when (val r = resolveCharByNameNovel(usedByCharName, null)) {  // F3-B: 동명이인 안전
                            is CharLookupResult.Found -> r.character.id
                            is CharLookupResult.Ambiguous -> {
                                result.warnings.add("이름 은행 행 $i: 사용 캐릭터 '$usedByCharName' 동명이인 ${r.count}명 — 사용캐릭터코드 열로 지정하세요")
                                null
                            }
                            CharLookupResult.NotFound -> null
                        }
                }

                val mapKey = "${name}\u0000${gender}"
                val entryCode = getCellCode(row, codeColIndex, "이름 은행 행 $i", result)  // F4: 숫자 코드 방어
                // F3-D: 코드 우선 매칭(이름/성별을 편집해도 같은 항목 인식) → 자연키(이름+성별) 폴백
                val existing = (if (entryCode.isNotBlank()) db.nameBankDao().getByCode(entryCode) else null)
                    ?: existingNamesMap[mapKey]

                // 참조 열 쌍 규약: 열 없음 → 기존 연결 유지(F1-A) / 이름 칸 빈칸 → 명시적 해제 / 값 있음 → 해석 결과
                val effectiveUsed = usedFlag ?: existing?.isUsed ?: false
                val usedByCharacterId: Long? = when (usedIntent) {
                    RefIntent.KEEP -> existing?.usedByCharacterId
                    RefIntent.CLEAR -> null
                    RefIntent.LOOKUP -> resolvedUsedById
                }
                // 참조를 조회했는데 해석에 실패하면 연결이 조용히 끊긴다 —
                // 사용 표시는 보존하고 연결만 비운 뒤 고지한다(무음 상태 변경 금지).
                if (effectiveUsed && usedIntent == RefIntent.LOOKUP && usedByCharacterId == null) {
                    result.warnings.add(
                        "이름 은행 행 $i: 사용 캐릭터 '${usedByCharName.ifBlank { usedByCharCode }}'을(를) 찾을 수 없어 " +
                        "연결 없이 '사용 중'으로 남겨둡니다 — '사용캐릭터코드' 열로 지정하거나 '사용 캐릭터' 칸을 비워 해제하세요"
                    )
                }

                if (existing != null) {
                    db.nameBankDao().update(existing.copy(
                        name = name, gender = gender,  // 코드 매칭 시 이름/성별 편집 반영 (code는 불변 유지 — 정체성)
                        origin = origin, notes = notes,
                        isUsed = usedFlag ?: existing.isUsed, usedByCharacterId = usedByCharacterId,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    matchedNameBankIds.add(existing.id)
                    result.updatedNameBank++
                } else {
                    // 파일의 코드를 보존해 백업/기기이전 후에도 왕복 정체성 유지 (없으면 자동 생성)
                    val newCode = if (entryCode.isNotBlank()) entryCode else generateEntityCode()
                    val newEntry = NameBankEntry(
                        name = name, gender = gender, origin = origin, notes = notes,
                        isUsed = usedFlag ?: false, usedByCharacterId = usedByCharacterId,
                        createdAt = createdAt, code = newCode
                    )
                    val newId = db.nameBankDao().insert(newEntry)
                    matchedNameBankIds.add(newId)
                    existingNamesMap[mapKey] = newEntry.copy(id = newId)
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
        val cols = resolveHeaderColumns(headerRow)
        // 첫 열('이름')은 checkHeaderOrReport가 보장하므로 0 폴백이 성립한다.
        // 나머지는 위치 폴백 금지 — 열을 지우면 이웃 열을 오독한다.
        val nameColIndex = cols["이름"] ?: 0
        val descColIndex = cols["설명"] ?: -1
        val fieldsJsonColIndex = cols["설정(JSON)"] ?: -1
        val builtInColIndex = cols["기본제공"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        val updatedAtColIndex = cols["수정일"] ?: -1

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
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                // F1-A: 열이 없으면 null(기존 값 유지), 열이 있고 빈칸이면 비움 의도로 존중
                val description: String? = if (descColIndex >= 0) getCellString(row, descColIndex) else null
                val fieldsJson: String? = if (fieldsJsonColIndex >= 0) getCellString(row, fieldsJsonColIndex).ifBlank { "[]" } else null
                val isBuiltIn = sheetBooleanOrKeep(builtInColIndex >= 0, getCellString(row, builtInColIndex))

                val createdAtRaw = if (createdAtColIndex >= 0) getCellString(row, createdAtColIndex) else ""
                val createdAt: Long? = parseNumber(createdAtRaw)?.toLong()
                if (createdAtRaw.isNotBlank() && createdAt == null) {
                    result.warnings.add("필드 템플릿 행 $i: 생성일 '$createdAtRaw'을(를) 숫자로 읽을 수 없어 이름으로 매칭합니다 — '생성일' 열은 수정하지 마세요")
                }
                val updatedAt = if (updatedAtColIndex >= 0) parseNumber(getCellString(row, updatedAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

                when (val match = matcher.claim(name, createdAt, i)) {
                    is PresetTemplateMatcher.Match.Matched -> {
                        match.warnings.forEach { result.warnings.add("필드 템플릿 행 $i: $it") }
                        if (match.nameBased) result.nameBasedMappings++
                        val existing = db.userPresetTemplateDao().getTemplateById(match.id)
                        if (existing == null) {
                            // 이론상 도달 불가(같은 트랜잭션 안) — 무음 스킵 금지 차원의 방어
                            result.skippedRows++
                            result.errors.add("필드 템플릿 행 $i: 템플릿(id=${match.id})을 다시 읽지 못해 건너뛰었습니다")
                        } else {
                            // createdAt은 이 시트의 정체성이라 파일 값으로 덮지 않는다(코드 열과 동일 취급)
                            db.userPresetTemplateDao().update(existing.copy(
                                name = name,                                   // 생성일 매칭이면 rename 반영
                                description = description ?: existing.description,
                                fieldsJson = fieldsJson ?: existing.fieldsJson,
                                isBuiltIn = isBuiltIn ?: existing.isBuiltIn,
                                updatedAt = updatedAt
                            ))
                            result.updatedPresetTemplates++
                        }
                    }
                    is PresetTemplateMatcher.Match.New -> {
                        match.warnings.forEach { result.warnings.add("필드 템플릿 행 $i: $it") }
                        // 신규는 엔티티 기본값 (갱신=F1-A, 신규=기본값 분리 규약)
                        val newTemplate = UserPresetTemplate(
                            name = name,
                            description = description ?: "",
                            fieldsJson = fieldsJson ?: "[]",
                            isBuiltIn = isBuiltIn ?: false,
                            createdAt = createdAt ?: System.currentTimeMillis(),
                            updatedAt = updatedAt
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
        val cols = resolveHeaderColumns(headerRow)
        // 첫 열만 checkHeaderOrReport가 보증한다. 선택 열의 위치 폴백은 열 삭제 시 이웃을 오독한다.
        val nameColIndex = cols["이름"] ?: 0
        val queryColIndex = cols["검색어"] ?: -1
        val filtersColIndex = cols["필터(JSON)"] ?: -1
        val sortModeColIndex = cols["정렬모드"] ?: -1
        val isDefaultColIndex = cols["기본값"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        val updatedAtColIndex = cols["수정일"] ?: -1

        // 목록 프리셋 validSortKinds와 동형 — 유효값의 단일 소스는 엔티티 companion
        val validSortModes = SearchPreset.SORT_MODES

        val existingPresets = db.searchPresetDao().getAllPresetsList()
        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                // 열 없음 = null = 기존값 유지(F1-A). 열 있음+빈칸 = 비움 의도.
                val query = if (queryColIndex >= 0) getCellString(row, queryColIndex) else null
                // 필드 필터의 fieldId를 안정 식별자(세계관코드+필드키)로 재해석 — 기기 이전·복원 후에도 필터가 살아있게
                val filtersJson = if (filtersColIndex >= 0) {
                    val filtersResolved = PortableFieldFilters.resolve(
                        getCellString(row, filtersColIndex).ifBlank { "{}" }, filterIndex)
                    for (w in filtersResolved.warnings) {
                        result.warnings.add("검색 프리셋 행 $i ('$name'): $w")
                    }
                    result.nameBasedMappings += filtersResolved.nameBasedCount
                    filtersResolved.json
                } else null
                // 인식할 수 없는 정렬모드를 조용히 저장하면 적용 시 relevance로 동작해 사용자가 틀린 줄 모른다.
                // 목록 프리셋의 정렬종류 검증과 동형으로 경고 + 교정 경로를 안내한다.
                val sortModeRaw = if (sortModeColIndex >= 0) getCellString(row, sortModeColIndex).trim() else ""
                val sortMode: String? = when {
                    sortModeRaw.isBlank() -> null
                    else -> matchDropdownValue(sortModeRaw, validSortModes) ?: run {
                        result.warnings.add(
                            "검색 프리셋 행 $i ('$name'): 정렬모드 '$sortModeRaw'을(를) 인식할 수 없어 " +
                            "기본(${SearchPreset.SORT_RELEVANCE})으로 처리합니다 — ${validSortModes.joinToString("/")} 중 하나로 입력하세요"
                        )
                        SearchPreset.SORT_RELEVANCE
                    }
                }
                val isDefault = if (isDefaultColIndex >= 0) parseBoolean(getCellString(row, isDefaultColIndex)) else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() else null
                val updatedAt = if (updatedAtColIndex >= 0) parseNumber(getCellString(row, updatedAtColIndex))?.toLong() else null

                val existing = existingByName[name]
                if (existing != null) {
                    db.searchPresetDao().update(existing.copy(
                        query = query ?: existing.query,
                        filtersJson = filtersJson ?: existing.filtersJson,
                        sortMode = sortMode ?: existing.sortMode,
                        isDefault = isDefault ?: existing.isDefault,
                        updatedAt = updatedAt ?: System.currentTimeMillis()
                    ))
                    result.updatedSearchPresets++
                } else {
                    val newPreset = SearchPreset(
                        name = name,
                        query = query ?: "",
                        filtersJson = filtersJson ?: "{}",
                        sortMode = sortMode ?: SearchPreset.SORT_RELEVANCE,
                        isDefault = isDefault ?: false,
                        createdAt = createdAt ?: System.currentTimeMillis(),
                        updatedAt = updatedAt ?: System.currentTimeMillis()
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
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols["이름"] ?: 0
        val tagsColIndex = cols["태그(JSON)"] ?: -1
        val filtersColIndex = cols["필드필터(JSON)"] ?: -1
        val sortKindColIndex = cols["정렬종류"] ?: -1
        val sortFieldKeyColIndex = cols["정렬필드키"] ?: -1
        val sortAscColIndex = cols["정렬오름차순"] ?: -1
        val bodyPartColIndex = cols["신체파트번호"] ?: -1
        val novelCodesColIndex = cols["작품코드목록"] ?: -1
        val isDefaultColIndex = cols["기본값"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1
        val updatedAtColIndex = cols["수정일"] ?: -1

        val validSortKinds = setOf(
            CharacterListPreset.SORT_MANUAL, CharacterListPreset.SORT_NAME,
            CharacterListPreset.SORT_CREATED, CharacterListPreset.SORT_RECENT,
            CharacterListPreset.SORT_FIELD
        )
        val existingByName = db.characterListPresetDao().getAllPresetsList()
            .associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                // 작품코드 → 이 기기의 작품 id (미해석 코드는 경고 후 제외)
                val novelIdsJson: String? = if (novelCodesColIndex >= 0) {
                    val codes = splitCsv(getCellString(row, novelCodesColIndex))
                    val ids = mutableListOf<Long>()
                    for (code in codes) {
                        val novel = db.novelDao().getNovelByCode(code)
                        if (novel != null) ids.add(novel.id)
                        else result.warnings.add("목록 프리셋 행 $i: 작품코드 '$code'을(를) 찾을 수 없어 프리셋의 작품 필터에서 제외합니다")
                    }
                    org.json.JSONArray(ids).toString()
                } else null

                val sortKindRaw = if (sortKindColIndex >= 0) getCellString(row, sortKindColIndex).trim() else ""
                val sortKind: String? = when {
                    sortKindColIndex < 0 || sortKindRaw.isBlank() -> null // 열 없음/빈칸 = 기존값 유지·기본값
                    sortKindRaw.lowercase() in validSortKinds -> sortKindRaw.lowercase()
                    else -> {
                        result.warnings.add("목록 프리셋 행 $i: 정렬종류 '$sortKindRaw'을(를) 인식할 수 없어 기본(manual)으로 처리합니다")
                        CharacterListPreset.SORT_MANUAL
                    }
                }
                val tagsJson = if (tagsColIndex >= 0) getCellString(row, tagsColIndex).ifBlank { "[]" } else null
                // 필드 필터의 fieldId를 안정 식별자(세계관코드+필드키)로 재해석 — 기기 이전·복원 후에도 필터가 살아있게
                val fieldFiltersJson = if (filtersColIndex >= 0) {
                    val resolved = PortableFieldFilters.resolve(
                        getCellString(row, filtersColIndex).ifBlank { "{}" }, filterIndex)
                    for (w in resolved.warnings) {
                        result.warnings.add("목록 프리셋 행 $i ('$name'): $w")
                    }
                    result.nameBasedMappings += resolved.nameBasedCount
                    resolved.json
                } else null
                val sortFieldKey = if (sortFieldKeyColIndex >= 0) getCellString(row, sortFieldKeyColIndex).ifBlank { null } else null
                // 불리언 열 규약(전 시트 공통): null은 '열 없음'(기존값 유지)만을 뜻한다.
                // 열이 있으면 빈칸도 해석 대상 — 빈칸 = N = 비움 의도(F1-A). 그래야 '기본값'을 비워 해제할 수 있다.
                val sortAscending = sheetBooleanOrKeep(sortAscColIndex >= 0, getCellString(row, sortAscColIndex))
                val bodySizePartIndex = if (bodyPartColIndex >= 0) parseNumber(getCellString(row, bodyPartColIndex))?.toInt() else null
                val isDefault = sheetBooleanOrKeep(isDefaultColIndex >= 0, getCellString(row, isDefaultColIndex))
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() else null
                val updatedAt = if (updatedAtColIndex >= 0) parseNumber(getCellString(row, updatedAtColIndex))?.toLong() else null

                val existing = existingByName[name]
                if (existing != null) {
                    db.characterListPresetDao().update(existing.copy(
                        tagsJson = tagsJson ?: existing.tagsJson,
                        fieldFiltersJson = fieldFiltersJson ?: existing.fieldFiltersJson,
                        sortKind = sortKind ?: existing.sortKind,
                        sortFieldKey = if (sortFieldKeyColIndex >= 0) sortFieldKey else existing.sortFieldKey,
                        sortAscending = sortAscending ?: existing.sortAscending,
                        bodySizePartIndex = if (bodyPartColIndex >= 0) bodySizePartIndex else existing.bodySizePartIndex,
                        novelIdsJson = novelIdsJson ?: existing.novelIdsJson,
                        isDefault = isDefault ?: existing.isDefault,
                        updatedAt = updatedAt ?: System.currentTimeMillis()
                    ))
                    result.updatedListPresets++
                } else {
                    val newPreset = CharacterListPreset(
                        name = name,
                        tagsJson = tagsJson ?: "[]",
                        fieldFiltersJson = fieldFiltersJson ?: "{}",
                        sortKind = sortKind ?: CharacterListPreset.SORT_MANUAL,
                        sortFieldKey = sortFieldKey,
                        sortAscending = sortAscending ?: true,
                        bodySizePartIndex = bodySizePartIndex,
                        novelIdsJson = novelIdsJson ?: "[]",
                        isDefault = isDefault ?: false,
                        createdAt = createdAt ?: System.currentTimeMillis(),
                        updatedAt = updatedAt ?: System.currentTimeMillis()
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
        if (totalAfter > CharacterListPreset.MAX_PRESETS) {
            result.warnings.add("목록 프리셋이 ${totalAfter}개로 인앱 권장 한도(${CharacterListPreset.MAX_PRESETS}개)를 초과했습니다 — 캐릭터 탭에서 정리할 수 있습니다")
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

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val key = getCellString(row, keyColIndex)
                if (key.isBlank()) continue
                val value = getCellString(row, valueColIndex)

                val ctx = appContext ?: continue
                // 스토어 setter가 자체 클램프를 수행하므로 범위 밖 값도 안전하게 수용된다(관대 임포트)
                when (key) {
                    "theme_mode" -> {
                        val mode = parseNumber(value)?.toInt() ?: 0
                        val validMode = mode.coerceIn(0, 2)
                        com.novelcharacter.app.util.ThemeHelper.saveTheme(ctx, validMode)
                        result.restoredSettings++
                    }
                    "backup_include_images" -> {
                        com.novelcharacter.app.backup.BackupSettingsStore(ctx).setIncludeImages(parseBoolean(value))
                        result.restoredSettings++
                    }
                    "backup_max_backups" -> {
                        parseNumber(value)?.toInt()?.let {
                            com.novelcharacter.app.backup.BackupSettingsStore(ctx).setMaxBackups(it)
                            result.restoredSettings++
                        }
                    }
                    "image_compress_enabled" -> {
                        com.novelcharacter.app.util.ImageSettingsStore(ctx).setEnabled(parseBoolean(value))
                        result.restoredSettings++
                    }
                    "image_quality_percent" -> {
                        parseNumber(value)?.toInt()?.let {
                            com.novelcharacter.app.util.ImageSettingsStore(ctx).setQualityPercent(it)
                            result.restoredSettings++
                        }
                    }
                    "image_cap_dimension" -> {
                        com.novelcharacter.app.util.ImageSettingsStore(ctx).setCapDimension(parseBoolean(value))
                        result.restoredSettings++
                    }
                    "image_max_long_edge_px" -> {
                        parseNumber(value)?.toInt()?.let {
                            com.novelcharacter.app.util.ImageSettingsStore(ctx).setMaxLongEdgePx(it)
                            result.restoredSettings++
                        }
                    }
                    "image_skip_below_enabled" -> {
                        com.novelcharacter.app.util.ImageSettingsStore(ctx).setSkipBelowEnabled(parseBoolean(value))
                        result.restoredSettings++
                    }
                    "image_skip_below_bytes" -> {
                        parseNumber(value)?.toLong()?.let {
                            com.novelcharacter.app.util.ImageSettingsStore(ctx).setSkipBelowBytes(it)
                            result.restoredSettings++
                        }
                    }
                    "image_editor_remove_policy" -> {
                        val policy = com.novelcharacter.app.util.ImageSettingsStore.EditorRemovePolicy.entries
                            .firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                        if (policy != null) {
                            com.novelcharacter.app.util.ImageSettingsStore(ctx).setEditorRemovePolicy(policy)
                            result.restoredSettings++
                        } else if (value.isNotBlank()) {
                            result.warnings.add("앱 설정 행 $i: image_editor_remove_policy 값 '$value'을(를) 인식할 수 없어 기존 설정을 유지합니다")
                        }
                    }
                    "image_auto_link_by_character" -> {
                        com.novelcharacter.app.util.ImageSettingsStore(ctx).setAutoLinkByCharacter(parseBoolean(value))
                        result.restoredSettings++
                    }
                    // 알 수 없는 키는 조용히 무시 — 상위 버전 파일을 하위 버전 앱에서 가져올 때 대비
                }
            } catch (e: Exception) {
                result.errors.add("앱 설정 행 $i: ${e.message}")
            }
        }
    }

    // ── 세력 가져오기 ──

    private suspend fun importFactions(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = factionSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = sheet.getRow(0) ?: return
        if (!checkHeaderOrReport(sheet, headerRow, spec.firstColumnHeader, result)) return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val nameColIndex = cols[spec.firstColumnHeader] ?: cols["이름"] ?: 0
        val universeNameColIndex = cols["세계관"] ?: -1
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        val descColIndex = cols["설명"] ?: -1
        val colorColIndex = cols["색상"] ?: -1
        val autoRelTypeColIndex = cols["자동관계유형"] ?: -1
        val autoRelIntensityColIndex = cols["자동관계강도"] ?: -1
        val codeColIndex = cols["코드"] ?: -1
        val orderColIndex = cols["정렬순서"] ?: -1
        val createdAtColIndex = cols["생성일"] ?: -1

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        for (i in 1..sheet.lastRowNum) {
            try {
                val row = sheet.getRow(i) ?: continue
                val name = getCellString(row, nameColIndex)
                if (name.isBlank()) continue

                val universeName = if (universeNameColIndex >= 0) getCellString(row, universeNameColIndex) else ""
                val universeCode = getCellCode(row, universeCodeColIndex, "세력 행 $i", result)
                // F1-A: 열 없음 → null(기존 유지). 열 있음 → 셀 값(빈칸=규칙 가에 따라 비움 의도 존중).
                val descriptionFromExcel: String? = if (descColIndex >= 0) getCellString(row, descColIndex) else null
                val colorFromExcel: String? = if (colorColIndex >= 0) getCellString(row, colorColIndex).ifBlank { "#2196F3" } else null
                val autoRelationType = if (autoRelTypeColIndex >= 0) getCellString(row, autoRelTypeColIndex) else ""
                if (autoRelationType.isBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 행 $i: 자동관계유형이 비어 있음")
                    continue
                }
                val autoRelationIntensity = parseIntensityWithWarn(row, autoRelIntensityColIndex, 5, "세력 행 $i", result) ?: 5
                val code = getCellCode(row, codeColIndex, "세력 행 $i", result)
                val displayOrder: Int? = if (orderColIndex >= 0) {
                    val raw = getCellString(row, orderColIndex)
                    if (raw.isBlank()) null else parseNumber(raw)?.toInt()
                } else null
                val createdAt = if (createdAtColIndex >= 0) parseNumber(getCellString(row, createdAtColIndex))?.toLong() ?: System.currentTimeMillis() else System.currentTimeMillis()

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
                    db.factionDao().update(existing.copy(
                        name = name,
                        universeId = universeId,
                        description = descriptionFromExcel ?: existing.description,
                        color = colorFromExcel ?: existing.color,
                        autoRelationType = autoRelationType,
                        autoRelationIntensity = autoRelationIntensity,
                        displayOrder = displayOrder ?: existing.displayOrder,
                        createdAt = if (createdAtColIndex >= 0) createdAt else existing.createdAt
                    ))
                    matchedFactionIds.add(existing.id)
                    result.updatedFactions++
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newId = db.factionDao().insert(Faction(
                        name = name,
                        universeId = universeId,
                        description = descriptionFromExcel ?: "",
                        color = colorFromExcel ?: "#2196F3",
                        autoRelationType = autoRelationType,
                        autoRelationIntensity = autoRelationIntensity,
                        code = newCode,
                        displayOrder = displayOrder ?: i,
                        createdAt = createdAt
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
                    result.updatedFactionMemberships++
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
                    if (leaveType == null && membershipId > 0) {
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
                    db.factionRelationshipDao().update(FactionRelationshipMatcher.apply(existing, rowValues, presence))
                    matchedFactionRelationshipIds.add(existing.id)
                    result.updatedFactionRelationships++
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
        val cols = resolveHeaderColumns(headerRow)
        val fileColIndex = cols["파일명"] ?: 0
        // 위치 폴백 금지 — 열을 지우면 이웃 열을 오독한다
        val tagColIndex = cols["태그"] ?: -1
        val groupColIndex = cols["링크그룹"] ?: -1

        // zip 리맵 키(원 절대경로)의 basename → 복원된 새 경로. basename 충돌은 무음 last-wins 대신 고지한다.
        val remap = ImageMetaRowResolver.buildRemapByBasename(imagePathRemap)
        remap.warnings.forEach { result.warnings.add(it) }
        val filesDir = appContext?.filesDir

        // 같은 이미지를 가리키는 행이 둘 이상이면 뒤 행이 앞 행의 태그를 통째로 지운다 —
        // 다른 시트의 코드 중복과 같은 규약(마지막 행 우선 + 고지)으로 접는다.
        val sheetRows = (1..sheet.lastRowNum).mapNotNull { i ->
            val row = sheet.getRow(i) ?: return@mapNotNull null
            val fileName = getCellString(row, fileColIndex)
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

                val existing = db.imageMetaDao().getByPath(path)
                val imageId = existing?.id ?: db.imageMetaDao().adopt(path, now)
                if (existing != null) result.updatedImageMeta++ else result.newImageMeta++

                // F1-A: '태그' 열이 없으면 기존 태그 유지. 열이 있고 빈칸이면 비움 의도로 존중.
                if (tagColIndex >= 0) {
                    val tags = splitCsv(getCellString(row, tagColIndex))
                    db.imageTagDao().replaceAllForImage(
                        imageId,
                        tags.map { com.novelcharacter.app.data.model.ImageTag(imageId = imageId, tag = it) }
                    )
                }

                if (groupColIndex >= 0) {
                    val groupToken = getCellString(row, groupColIndex).trim()
                    if (groupToken.isNotBlank()) {
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
                .removeSuffix(" (쉼표 구분)")

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
                    type = "TEXT",
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
        val newId = db.novelDao().insert(Novel(title = novelTitle, universeId = universeId, code = generateEntityCode()))
        result?.warnings?.add("${rowLabel ?: "작품"}: 작품 '$novelTitle'을(를) 찾지 못해 새로 생성했습니다 — 오타·세계관 지정을 확인하세요")
        novelIdCache[cacheKey] = newId
        return newId
    }

    private suspend fun findCharacterByName(name: String, preferredNovelId: Long?): Character? {
        if (preferredNovelId != null) {
            val match = db.characterDao().getCharacterByNameAndNovel(name, preferredNovelId)
            if (match != null) return match
        }
        return db.characterDao().getCharacterByName(name)
    }

    /** 코드/이름 기반 캐릭터 조회 — 동명이인 모호성 감지 포함 */
    private sealed class CharLookupResult {
        data class Found(val character: Character) : CharLookupResult()
        data class Ambiguous(val count: Int) : CharLookupResult()
        data object NotFound : CharLookupResult()
    }

    private suspend fun findCharacterStrict(name: String, code: String): CharLookupResult {
        if (code.isNotBlank()) {
            val byCode = db.characterDao().getCharacterByCode(code)
            if (byCode != null) return CharLookupResult.Found(byCode)
        }
        if (name.isBlank()) return CharLookupResult.NotFound
        val matches = db.characterDao().getAllCharactersByName(name)
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
    private suspend fun resolveCharByNameNovel(name: String, preferredNovelId: Long?): CharLookupResult {
        if (name.isBlank()) return CharLookupResult.NotFound
        val matches = db.characterDao().getAllCharactersByName(name)
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
        if (novelUniverseCache.containsKey(novelId)) return novelUniverseCache[novelId]
        val uid = db.novelDao().getNovelById(novelId)?.universeId
        novelUniverseCache[novelId] = uid
        return uid
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
    private fun getCellCode(row: Row, cellIndex: Int, rowLabel: String, result: ImportResult): String {
        if (cellIndex < 0) return ""
        val cell = row.getCell(cellIndex) ?: return ""
        if (cell.cellType == CellType.NUMERIC) {
            result.warnings.add("$rowLabel: 코드가 숫자 형식으로 저장되어 있습니다 — 코드 열은 수정하지 마세요(정밀도 손실로 매칭이 어긋날 수 있습니다)")
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
    private fun normalizeRelTypesCell(raw: String, rowLabel: String, universeName: String, result: ImportResult): String? {
        if (raw.isBlank()) return ""                      // F1-A: 열 있음 + 빈칸 = 비움 의도 존중
        if (isValidJson(raw, '[')) return raw
        val tokens = parseRelTypeTokens(raw)
        if (tokens.isNotEmpty()) {
            result.warnings.add(
                "$rowLabel: 세계관 '$universeName'의 커스텀관계유형이 JSON 배열 형식이 아니어서 쉼표 구분으로 해석했습니다(${tokens.size}개: ${tokens.joinToString("/")}) — 정확한 형식은 [\"연인\",\"라이벌\"] 입니다"
            )
            return org.json.JSONArray(tokens).toString()
        }
        result.warnings.add(
            "$rowLabel: 세계관 '$universeName'의 커스텀관계유형 '${raw.take(40)}'을(를) 해석할 수 없어 적용하지 않고 기존 설정을 유지했습니다 — 형식은 [\"연인\",\"라이벌\"] 또는 쉼표 구분(연인, 라이벌)입니다. 비우면 기본 유형으로 돌아갑니다"
        )
        return null
    }

    /**
     * 세계관 '커스텀관계색상' 셀 정규화. 규칙은 [normalizeRelTypesCell]과 동형이며
     * 소비처는 Universe.getRelationshipColorMap이다.
     */
    private fun normalizeRelColorsCell(raw: String, rowLabel: String, universeName: String, result: ImportResult): String? {
        if (raw.isBlank()) return ""
        if (isValidJson(raw, '{')) return raw
        val pairs = parseRelColorTokens(raw)
        if (pairs.isNotEmpty()) {
            val obj = org.json.JSONObject()
            pairs.forEach { (k, v) -> obj.put(k, v) }
            result.warnings.add(
                "$rowLabel: 세계관 '$universeName'의 커스텀관계색상이 JSON 객체 형식이 아니어서 '유형=색상' 목록으로 해석했습니다(${pairs.size}개) — 정확한 형식은 {\"연인\":\"#E91E63\"} 입니다"
            )
            return obj.toString()
        }
        result.warnings.add(
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
    private fun parseMonthWithWarn(row: Row, colIndex: Int, rowLabel: String, result: ImportResult): Int? {
        if (colIndex < 0) return null
        val raw = getCellString(row, colIndex)
        if (raw.isBlank()) return null
        val month = parseNumber(raw)?.toInt()?.takeIf { it in 1..12 }
        if (month == null) {
            result.warnings.add("$rowLabel: 월 '$raw'을(를) 1~12 범위의 숫자로 해석할 수 없어 무시됨")
        }
        return month
    }

    /** 일 파싱 + 유효성 경고: 열 없음/빈 셀=null(정상), 1~31·월별 일수 밖=경고 후 null */
    private fun parseDayWithWarn(row: Row, colIndex: Int, month: Int?, rowLabel: String, result: ImportResult): Int? {
        if (colIndex < 0) return null
        val raw = getCellString(row, colIndex)
        if (raw.isBlank()) return null
        val day = parseNumber(raw)?.toInt()?.takeIf { d -> d in 1..31 && isValidDay(month, d) }
        if (day == null) {
            result.warnings.add("$rowLabel: 일 '$raw'이(가) 유효하지 않아(1~31·월별 일수) 무시됨")
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
            val novelId = db.novelDao().getNovelByCode(novelCode)?.id
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
            val charId = db.characterDao().getCharacterByCode(charCode)?.id
            if (charId == null) {
                result.warnings.add("작품 '${novel.title}': 이미지 캐릭터코드 '$charCode'에 해당하는 캐릭터가 없어 대표 이미지 연동이 해제되었습니다")
                continue
            }
            db.novelDao().update(novel.copy(imageCharacterId = charId))
        }
        deferredNovelImageCharCodes.clear()

        for ((universeId, charCode) in deferredUniverseImageCharCodes) {
            val universe = db.universeDao().getUniverseById(universeId) ?: continue
            val charId = db.characterDao().getCharacterByCode(charCode)?.id
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

