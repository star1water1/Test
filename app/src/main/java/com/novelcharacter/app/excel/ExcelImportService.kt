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
import com.novelcharacter.app.data.model.CharacterQuote
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
import com.novelcharacter.app.util.CalculatedCellEcho
import com.novelcharacter.app.util.stringOr
import com.novelcharacter.app.util.CharacterValueLedger
import com.novelcharacter.app.util.DuelCandidateFilter
import com.novelcharacter.app.util.FactionStanding
import com.novelcharacter.app.util.SqlInChunks
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.FormulaValidator
import com.novelcharacter.app.util.ImportLookupIndex
import com.novelcharacter.app.util.PreviewIdMinter
import com.novelcharacter.app.util.ANALYSIS_CREATED_NOVEL_ID
import com.novelcharacter.app.util.DuelAxisIndexes
import com.novelcharacter.app.util.DuelAxisNameKey
import com.novelcharacter.app.util.DuelMatchIndexes
import com.novelcharacter.app.util.DuelVerdictMemberKey
import com.novelcharacter.app.util.EventNaturalKey
import com.novelcharacter.app.util.FactionIdentityIndexes
import com.novelcharacter.app.util.FactionNameKey
import com.novelcharacter.app.util.DefaultFieldTemplateIndexes
import com.novelcharacter.app.util.FieldDefinitionIndexes
import com.novelcharacter.app.util.guardDefaultFieldSlot
import com.novelcharacter.app.util.CharacterFieldColumns
import com.novelcharacter.app.util.ColumnFieldOutcome
import com.novelcharacter.app.util.FieldValueCellEffect
import com.novelcharacter.app.util.FieldValueCellPlan
import com.novelcharacter.app.util.FieldValueOverlay
import com.novelcharacter.app.util.FieldValueScan
import com.novelcharacter.app.util.NovelTitleKey
import com.novelcharacter.app.util.RelChangeNaturalKey
import com.novelcharacter.app.util.RelationshipChangeIndexes
import com.novelcharacter.app.util.RelationshipIndexes
import com.novelcharacter.app.util.QuoteIndexes
import com.novelcharacter.app.util.QuoteNaturalKey
import com.novelcharacter.app.util.StateChangeIndexes
import com.novelcharacter.app.util.StateChangeNaturalKey
import com.novelcharacter.app.util.ImportedFormulaAudit
import com.novelcharacter.app.util.PresetLimit
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.CharacterRepresentativeImage
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

// '가져오기가 새로 만들 작품' 표지값(B-217)은 **미리보기 임시 id 발급기와 같은 수직선을 쓴다** —
// 그래서 둘의 정의가 `util/PreviewCreations.kt` 한 자리에 함께 산다(B-233). 여기 다시 적으면
// 발급기가 그 자리를 다시 내주는 순간 미리보기가 서로 다른 둘을 같은 것으로 본다.

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
     *
     * **모호(동명이인·동명 세력·동명 작품)도 여기 센다** — 그 행은 선행 범주를 함께 가져와도
     * 해소되지 않아 **영원히** 거부되므로, '신규'로 세면 실행되지 않을 숫자를 예고하게 된다
     * (B-232 — 미리보기 다섯 자리가 그 상태였다).
     *
     * **필수 칸이 비었거나 해석되지 않는 행도 여기 센다**(B-237). 종전에는 그런 행이
     * [inBackup] **앞에서** 버려져 *건너뜀*도 *백업에 있음*도 아닌 **무존재**였다 —
     * 짝 가져오기는 같은 행을 `skippedRows`로 세고 소리 내어 거부하는데, 미리보기의
     * '백업에 N건'만 파일의 실제 행보다 작았고 그 차이가 어디로 갔는지 화면 어디에도 없었다.
     * **[inBackup]의 뜻을 여기서 못박는다: *파일이 이 시트에 적어 둔 행*이다**(가져오기가
     * 받아들일 행이 아니다). 그래야 `inBackup = new + update + unchanged + skipped`가 성립하고,
     * 사용자가 파일의 행 수와 곧장 견줄 수 있다.
     *
     * > **"정상 복원에서는 이 값이 0이다"는 그대로 성립한다** — 필수 칸이 빈 파일은
     * > 정상 복원이 아니기 때문이다. 앱이 내보낸 파일은 필수 칸을 언제나 채워 내보내므로,
     * > 이 값이 0이 아니라는 것은 **외부 편집이 행을 상하게 했다**는 뜻이고 그것이야말로
     * > 미리보기가 말해야 할 사실이다(개발 의도 2번).
     *
     * **완전히 빈 행은 여기 세지 않는다** — 가져오기도 세지 않는다(고지 없이 `continue`).
     * 표 아래 여백은 파일이 적어 둔 행이 아니다.
     */
    val skippedCount: Int = 0,
    /**
     * **이번 가져오기가 지울 값의 수** (B-187 — 지금은 필드값 범주 셋만 낸다).
     *
     * 신규도 변경도 아니면서 표를 바꾸는 유일한 처분이라 칸이 따로 있다. 셋을 한 통에 넣으면
     * *'필드값 40건이 지워진다'*가 *'변경 40건'*으로 보이는데, **비움은 유실이고 변경은
     * 덮어쓰기다** — 사용자가 취소할지 정하는 근거가 서로 다르다(개발 의도 2번 '변수 제어').
     *
     * 이 칸이 붙으면서 [inBackup]의 항등식이 한 자리 넓어진다:
     * `inBackup = new + update + unchanged + cleared + skipped`.
     * 다른 범주는 값을 지우는 처분이 없어 언제나 0이다(그쪽에서 지워지는 것은 *항목 전체*이고,
     * 그것은 '덮어쓰기' 전략이 [onlyInDb]로 이미 말한다).
     */
    val clearedCount: Int = 0,
    /**
     * **덮어쓰기가 이 범주의 [onlyInDb]를 실제로 지우는가** (B-263 ⓑ에서 신설).
     *
     * 거의 모든 범주는 참이고, 그래서 미리보기의 덮어쓰기 경고가 *"…을 삭제합니다"*라고
     * 말할 때 [onlyInDb]를 그대로 든다. **앱 설정은 거짓이다** — 가져오기가 설정을 지우는
     * 경로가 아예 없고([importAppSettings]는 쓰기만 한다), 파일이 안 실은 설정은 그냥
     * 그대로 남는다. 깃발 없이 범주를 더하면 *"앱 설정 5개를 삭제합니다"*라는
     * **거짓 고지**가 경고문에 실린다(개발 의도 2번이 금지하는 그것이다).
     *
     * 숫자 자체는 그대로 낸다 — *"백업에 없음: 5개"*는 참이고 **파일이 무엇을 안 실었는지**를
     * 말해 준다(비밀 제외로 내보낸 파일이 그렇다). 거짓인 것은 그것을 *삭제 대상*이라 부르는
     * 자리뿐이라, 숫자를 접지 않고 **그 자리만** 가른다.
     */
    val deletedByOverwrite: Boolean = true
) {
    /**
     * **이 파일이 갱신하지 않는** DB 항목 수 ([deletedByOverwrite]인 범주에서는 덮어쓰기 시 삭제 대상).
     *
     * **이름과 문구가 "백업에 없음"이면 안 되는 이유**(2026.08.22). 이 수는 삭제 대상으로는
     * 정확하다 — 삭제는 `id !in matchedIds`로 도는데 [skippedCount]에 잡힌 행은 매칭 id를
     * 만들지 못하므로 그 항목은 **실제로 지워진다.** 그러나 그 항목은 **백업에 있다**:
     * 앱이 그 행을 못 읽었을 뿐이다(필수 칸이 비었거나, 이 버전이 모르는 값이거나).
     *
     * 그래서 *"백업에 없음"* · *"DB에만 있는 데이터"*라는 종전 문구는 **수는 맞는데 사유가
     * 틀린 고지**였고, 사용자가 취소할지 정하는 근거가 바로 그 사유다. 화면 문구를
     * *"이 파일이 갱신하지 않는"*으로 바꾸고, [skippedCount]가 0이 아닌 범주에서는
     * 덮어쓰기 경고가 **섞임을 한 줄로 밝힌다**(`restore_overwrite_unread_note`).
     *
     * 식에서 [skippedCount]를 빼지 않는 것도 그 때문이다 — 빼면 *지워질 수*를 적게 말한다.
     */
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
    /** 명대사 (사용자 요청 2026.08.20). */
    var newQuotes: Int = 0,
    var updatedQuotes: Int = 0,
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
    var deletedQuotes: Int = 0,
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

    /**
     * 시트별 **헤더 행의 자리** (B-231 ⓑ) — [locateHeaderRow]가 심고 [mergedTopLeftValue]가 읽는다.
     * 비어 있으면 0으로 본다(헤더를 아직 못 찾은 시트 = 종전 가정과 같다).
     */
    private val headerRowIndexBySheet = HashMap<Sheet, Int>()
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

    /**
     * 이 가져오기가 **빈 셀로 지운** 시맨틱 역할 필드 (캐릭터 id → 필드 id들).
     *
     * 빈 셀은 값 삭제이고(F1-A 규칙 가), 그러면 그 값에서 파생된 `__birth`·`__death`도
     * 함께 정리돼야 한다 — 안 그러면 생일을 지운 파일을 들여도 알림이 계속 울린다
     * (개발 의도 4번 — 내보내기 → 빈 칸 → 들이기가 비움을 반영해야 한다).
     * 값 목록만으로는 *지워짐*을 볼 수 없으므로 지운 자리에서 세어 둔다.
     */
    private val pendingSyncClearedFields = mutableMapOf<Long, MutableSet<Long>>()

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

    // 캐릭터 시트로 이미 소비된 시트명 — 같은 시트를 세계관용·미분류용으로 두 번 돌지 않게 하고,
    // sanitize 결과가 같은 두 세계관이 같은 시트를 받지 않게 한다(2026.08.20 — [UniverseSheetFinder]).
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
    /**
     * 같은 색인의 **제목** 축 (B-236) — `getNovelByTitleAndUniverse`·`getNovelByTitleNoUniverse`를
     * 행마다 물던 자리다. **코드 축과 함께 두는 것이 요점이다:** 작품 시트는 제목을 고칠 수 있고,
     * 고친 뒤에도 옛 제목으로 잡히면 한 파일이 같은 작품을 둘로 가른다
     * ([ImportLookupIndex] 성질 3이 [rememberNovel] 한 자리에서 그것을 끊는다).
     */
    private val novelTitleKeys = ImportLookupIndex<NovelTitleKey, Novel>(
        idOf = { it.id }, keyOf = { NovelTitleKey(it.title, it.universeId) }
    )
    /**
     * 세계관을 가리지 않는 **제목만**의 축 (B-236) — `getNovelsByTitleList(title).firstOrNull()`의
     * 자리다. 유령 작품을 새로 만들기 전에 *"다른 세계관에 같은 제목이 있는가"*를 묻는 갈래가 쓴다.
     */
    private val novelTitlesAnyUniverse = ImportLookupIndex<String, Novel>(
        idOf = { it.id }, keyOf = { it.title }
    )
    private var novelIndexLoaded = false
    /**
     * 세계관 색인 — 코드·이름·id (B-210). **행마다 세계관을 되찾는 자리가 이 파일에서 가장 많다**
     * (세계관 · 작품 · 필드 정의 · 등급 체계 · 세력 · 대결 축 · 연표 · 미리보기 여섯 자리).
     * 쓰는 자리는 [importUniverses]와 [applyDeferredUniverseNovelRefs]·[applyDeferredCharacterRefs]뿐이고
     * 셋 다 쓴 뒤에 갱신한다.
     */
    private val universeCodes = ImportLookupIndex<String, Universe>(
        idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
    )
    private val universeNames = ImportLookupIndex<String, Universe>(
        idOf = { it.id }, keyOf = { it.name }
    )
    private val universesByUniverseId = ImportLookupIndex<Long, Universe>(
        idOf = { it.id }, keyOf = { it.id }
    )
    private var universeIndexLoaded = false

    /**
     * 복원 미리보기가 *이 파일이 앞서 만든 항목*에 매기는 임시 id 발급기 (B-233).
     *
     * **수명이 색인과 같아야 한다 — 그래서 여기 있다.** 위 색인들은 서비스에 살고 `analyzeAll`이
     * 시작할 때 비운다. 발급기를 시트 함수의 지역 변수로 두면 **한 색인에 발급기가 여럿 붙을 수
     * 있고**, 그 순간 서로 다른 함수가 같은 음수를 내준다 — `ImportLookupIndex.put`은 같은 id를
     * *같은 행이 옮겨 온 것*으로 보아 **먼저 등재된 쪽의 키를 끊는다.**
     *
     * **가상의 위험이 아니다.** 이 판의 첫 구현이 정확히 그랬다: `analyzeCharacterSheet`는
     * **세계관 시트마다 한 번 + 미분류 시트에 한 번** 불리는데 발급기가 그 안에 있어,
     * 둘째 시트의 첫 신규가 첫째 시트의 첫 신규와 같은 id(-2)를 받았다. 그러면 **첫째 시트가 만든
     * 캐릭터가 색인에서 사라져**, 뒤 시트(상태 변화·관계·이름 은행)가 그 캐릭터를 못 찾는다 —
     * 이 판이 없애려던 바로 그 증상이 **이 판의 수리 자체 때문에** 되살아나는 모양이다.
     *
     * 처방은 규칙을 더 적는 것이 아니라 **자리를 옮기는 것**이다: 분석 하나에 id 공간 하나이면
     * 겹칠 방법이 없다. `Long` 하나라 [ImportLookupIndex]의 '메모리' 절이 걱정하는 부류도 아니다.
     */
    private var previewIds = PreviewIdMinter()

    /**
     * **이 파일이 만들 필드 정의** — 미리보기의 필드값 범주 셋이 이것까지 봐야 한다 (B-187).
     *
     * `analyzeFieldDefinitions`가 신규로 판정한 행을 여기 남긴다. 그 함수의 지역 색인만으로는
     * 못 쓰는 이유는 **빈 DB로 복원하는 파일** 때문이다: 필드 정의도 같은 파일에 실려 있고
     * 가져오기는 정의를 **먼저** 심으므로 필드값 행이 전부 해석되는데, 미리보기가 DB만 보면
     * *"필드를 찾을 수 없음"*으로 세어 **신규 기기 복원이라는 가장 흔한 경로에서** 통째로 어긋난다.
     *
     * 비어 있다는 것은 *'필드 정의' 시트가 없거나 그 범주를 안 돌렸다*는 뜻이고, 그때는
     * 가져오기도 정의를 심지 않으므로 DB만 보는 것이 맞는 답이다.
     *
     * **싣는 순서가 규약이다** — 이 목록은 DB 행 **뒤에** 붙인다([ImportLookupIndex] 성질 1:
     * 먼저 실린 것이 답이다). 임시 id가 음수라 앞에 실으면 DB의 같은 키를 가린다.
     */
    private val analysisCreatedFields = mutableListOf<FieldDefinition>()

    /**
     * **이 파일이 만들 세계관** — 캐릭터 시트를 훑는 자리가 이것까지 봐야 한다 (B-254).
     *
     * **왜 색인이 아니라 목록인가:** `analyzeCharacters`가 필요한 것은 *찾기*가 아니라
     * **열거**다(세계관마다 시트가 하나씩 있어 그 목록으로 훑는다). [ImportLookupIndex]에는
     * 열거 API가 없고, 있어야 할 이유도 여기 하나뿐이라 형제 색인 전부를 넓히지 않는다 —
     * 바로 위 [analysisCreatedFields]가 같은 사유로 선 목록이다.
     *
     * **왜 필요한가:** 짝인 [importCharacterSheets]도 세계관을 `getAllUniversesList()`로 훑지만,
     * 그때는 [importUniverses]가 **먼저** 심어 목록이 차 있다. 미리보기는 쓰지 않으므로
     * **빈 DB 복원에서 그 목록이 비어 루프가 한 번도 돌지 않았다** — 파일에 캐릭터가 200명
     * 있어도 미분류 시트 하나만 세어졌다(유실은 없고 *예고가 통째로 비는* 자리다).
     */
    private val analysisCreatedUniverses = mutableListOf<Universe>()

    /**
     * **이 파일이 만들 대결 축** — 기록·상성 시트를 훑는 자리가 이것까지 봐야 한다.
     *
     * 형제 범주는 이 재료를 이미 갖고 있다(세계관은 [analysisCreatedUniverses], 필드는
     * [analysisCreatedFields]). 대결 축만 `analyzeDuelAxes` 안의 지역 색인으로 끝나 함수와
     * 함께 죽었고, 그래서 기록·상성 분석은 *파일이 만들 축*과 *오타*를 가릴 재료가 없었다 —
     * 빈 DB 복원을 살리려던 낙관 가지가 **둘을 함께 '신규'로** 셌다. 없는 축을 가리킨 행은
     * 가져오기가 영원히 거부하므로 그것은 '건너뜀'이어야 한다.
     *
     * **싣는 순서는 형제와 같다** — DB 행 뒤에 붙인다(먼저 실린 것이 답이다).
     */
    private val analysisCreatedDuelAxes = mutableListOf<com.novelcharacter.app.data.model.DuelAxis>()

    /**
     * **이 파일이 세계관을 옮길 캐릭터** — 그 캐릭터의 필드값 칸은 예고하지 않는다 (B-253).
     *
     * 이동은 필드값에 두 가지를 한다: ⓐ 그 캐릭터의 값을 **새 세계관의 같은 key 필드로 전량
     * 재매핑**하고(짝이 없는 key는 유실 — 휴지통 스냅샷으로 간다) ⓑ '캐릭터 필드값' 시트의
     * **옛 키 행을 적용하지 않는다**(방금 정리한 값이 되살아나므로).
     *
     * 그래서 이 캐릭터의 칸은 **칸 단위 처분을 약속할 수 없다** — 미리보기의 처분은
     * `(소유자, 필드)` 짝 위에서 나는데 이동이 그 짝을 통째로 갈아치운다. '동일'이라 세어 둔
     * 칸조차 실제로는 다른 필드로 옮겨 앉는다. **약속하지 않는 것을 '건너뜀'으로 세는 이유**가
     * 그것이고, 그 갈래는 [FieldValueScan.skip]의 KDoc이 든다.
     */
    private val analysisUniverseMovedCharacterIds = mutableSetOf<Long>()

    private val matchedEventIds = mutableSetOf<Long>()
    private val matchedRelationshipIds = mutableSetOf<Long>()
    private val matchedRelationshipChangeIds = mutableSetOf<Long>()
    private val matchedStateChangeIds = mutableSetOf<Long>()
    private val matchedQuoteIds = mutableSetOf<Long>()
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
    /**
     * 지연 이미지 참조 — 코드와 **병합 전 원본 id**를 함께 든다 (B-217 · 확정 7-2).
     * 병합은 지연 몫을 null로 저장하므로 '실제로 바뀌었는가'는 되붙는 자리만 알 수 있다 —
     * 원본을 실어 두어야 해석값과 견주어 무편집 행을 '갱신'으로 세지 않는다.
     */
    private data class DeferredImageRef(val code: String, val originalId: Long?)

    private val deferredUniverseImageCharCodes = mutableMapOf<Long, DeferredImageRef>()  // universeId → (charCode, 원본)
    private val deferredUniverseImageNovelCodes = mutableMapOf<Long, DeferredImageRef>() // universeId → (novelCode, 원본)
    private val deferredNovelImageCharCodes = mutableMapOf<Long, DeferredImageRef>()     // novelId → (charCode, 원본)

    // 병합 계수가 '동일'로 센 항목 — 지연 해석이 이미지 연동의 순효과를 바꾸면 '갱신'으로
    // 승격한다(두 축이 다 있어도 집합 remove로 한 번만 — 행 단위 계수 보존).
    private val universesCountedUnchanged = mutableSetOf<Long>()
    private val novelsCountedUnchanged = mutableSetOf<Long>()

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
                    "캐릭터 행 ${excelRow(rowIndex)} ($characterName): '대표이미지' 값 \"${r.raw}\"에 해당하는 이미지를 찾을 수 없어 기존 대표 지정을 유지했습니다"
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
        pendingSyncClearedFields.clear()
        importAliasResolvers.clear()
        processedRowsSoFar = 0
        truncatedFieldCount = 0
        wipedByOverwrite.clear()
        createdAfterWipe.clear()
        truncatedDetails.clear()
        mergedCellMaps.clear()
        headerRowIndexBySheet.clear()
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
                // **"내용 있는 데이터 행이 1개 이상인가"**다. 물리 행 번호(lastRowNum)로 재면
                // 내용만 지우고 스타일만 남은 행이 잔존하는 시트를 복원 재료로 오판한다 —
                // 사유·경계는 그 파일의 KDoc에 있다.
                fun classify(spec: SheetSpec): RestoreSource =
                    OverwriteGuard.classify(
                        sheetHasDataRow(resolveSpecSheet(workbook, spec), spec.firstColumnHeader)
                    )
                /** 이 spec의 시트를 근거로 기존 데이터를 지워도 되는가(= 데이터 행이 있는가). */
                fun canRestore(spec: SheetSpec): Boolean = classify(spec) == RestoreSource.HAS_ROWS
                /**
                 * **왜 안 지웠는가**의 앞머리를 짓는 단일 함수 — `RestoreSource`가 세 값인 뜻이
                 * 자리마다 버려지지 않게 한다. 시트가 *없는* 것과 *비어 있는* 것은 사용자가 할
                 * 일이 다르다(전자는 그 시트에 행을 적기, 후자는 다시 내보내기).
                 *
                 * 종전에는 캐릭터·세계관·필드 정의 셋이 불리언 하나(`canRestore`)로 접혀
                 * MISSING과 EMPTY가 같은 false가 됐고, 그 순간 어느 쪽인지가 사라진 채 문구는
                 * 언제나 MISSING 쪽('시트가 없어')으로 고정됐다 — 내용만 지운 시트에 "시트가
                 * 없다"고 말하면 사용자는 문구가 시키는 대로 다시 내보내기를 하고 진짜 할 일에는
                 * 영영 닿지 못한다. 같은 사실을 네 자리가 각자 적으면 반드시 갈리므로 한 자리에 둔다.
                 *
                 * @param label 이미 완성된 시트 이름 어구(`"'세계관'"` · `"캐릭터"`)
                 */
                fun keepReason(source: RestoreSource, label: String): String = when (source) {
                    RestoreSource.EMPTY -> "$label 시트에 데이터 행이 없어"
                    RestoreSource.MISSING -> "백업에 $label 시트가 없어"
                    // 지울 수 있는 상태라 사유가 필요 없다 — 부르는 쪽이 이 값을 넘기면 그것이 결함이다.
                    RestoreSource.HAS_ROWS -> "$label 시트를 복원 재료로 쓸 수 없어"
                }
                /** 선택됐고 백업으로 복원 가능할 때만 true. 복원 불가면 삭제를 건너뛰고 사용자에게 알린다. */
                fun shouldDelete(enabled: Boolean, spec: SheetSpec): Boolean {
                    if (!enabled) return false
                    val source = classify(spec)
                    if (source == RestoreSource.HAS_ROWS) return true
                    result.warnings.add(
                        keepReason(source, "'${spec.sheetName}'") +
                            " 기존 데이터를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)"
                    )
                    return false
                }
                // 캐릭터는 세계관별 시트 + 미분류 시트로 나뉘므로 별도 판정
                // 캐릭터 시트도 같은 규칙이다(B-88) — 헤더만 있는 시트는 복원 재료가 아니다.
                // 내보내기가 캐릭터 0명인 세계관에도 시트를 만들게 됐으므로, 헤더 검사만 두면
                // **캐릭터가 하나도 없는 세계관의 빈 시트 하나가 전 캐릭터 삭제를 허가한다.**
                // **불리언이 아니라 RestoreSource를 낸다** — 접으면 '없음'과 '비었음'이 같아진다.
                fun charSheetSource(sheet: Sheet?): RestoreSource =
                    // 헤더가 0행이 아닐 수 있다(B-231 ⓑ) — 가드가 0행에 묶여 있으면
                    // 가져오기는 읽는 시트를 가드만 *복원 재료가 아니다*로 보고,
                    // 덮어쓰기가 조용히 병합으로 바뀐다(바로 위 문단이 막는 그 모양).
                    if (sheet == null || SheetResolver.locateHeader(sheet, "이름") == null) RestoreSource.MISSING
                    else OverwriteGuard.classify(sheetHasDataRow(sheet, "이름"))
                // 배정은 실제 가져오기와 같은 판정([UniverseSheetFinder] — 계획 우선 + 소비 추적)을
                // 지나야 한다. 이름만으로 찾으면 동명 세계관 둘이 같은 시트를 보고, 기본명 시트만
                // 비어 있는 파일에서 `(2)` 시트가 있는데도 "복원 재료 없음"으로 오판한다.
                // `any`가 참으로 끊기면 소비 집합이 미완이지만, 그때는 `||`도 끊겨 미분류 조회가
                // 일어나지 않는다 — 거짓으로 끝났을 때만 전 세계관이 소비를 마친 상태다.
                val guardUniverses = db.universeDao().getAllUniversesList()
                val guardSheets = UniverseSheetFinder(workbook, guardUniverses)
                // **끊지 않고 한 번에 전부 잰다.** 종전의 `any { } || …` 단락은 판정만 필요했을
                // 때의 모양인데, 이제 *못 지운 사유*까지 말해야 하고 그 사유는 재질문으로 얻을 수
                // 없다(소비 추적이 있어 `find`를 두 번 부르면 같은 답이 나오지 않는다).
                // **판정은 한 글자도 바뀌지 않는다** — 하나라도 HAS_ROWS면 복원 가능이고,
                // 그 경우 미분류 조회 결과는 사유에만 쓰여 결론에 닿지 않는다.
                val charSheetSources = guardUniverses.map { u -> charSheetSource(guardSheets.find(u)) } +
                    charSheetSource(findUnclassifiedSheet(workbook, guardSheets.consumed))
                val charactersRestorable = charSheetSources.any { it == RestoreSource.HAS_ROWS }

                if (shouldDelete(effectiveOptions.relationshipChanges, relationshipChangeSpec())) db.characterRelationshipChangeDao().deleteAll()
                if (shouldDelete(effectiveOptions.relationships, relationshipSpec())) db.characterRelationshipDao().deleteAll()
                if (shouldDelete(effectiveOptions.factionMemberships, factionMembershipSpec())) db.factionMembershipDao().deleteAll()
                if (shouldDelete(effectiveOptions.factionRelationships, factionRelationshipSpec())) db.factionRelationshipDao().deleteAll()
                if (shouldDelete(effectiveOptions.factions, factionSpec())) { db.factionDao().deleteAll(); wipedByOverwrite.add("factions") }
                if (shouldDelete(effectiveOptions.stateChanges, stateChangeSpec())) db.characterStateChangeDao().deleteAll()
                if (shouldDelete(effectiveOptions.quotes, quoteSpec())) db.characterQuoteDao().deleteAll()
                // 대결(B-104) — **축을 지우면 그 아래 판이 CASCADE로 함께 죽는다.** 그래서
                // 축 시트만 보고 지우면 *"기록 시트가 빈 파일"* 하나가 수만 판을 없앤다.
                // 대원칙 그대로 — 백업이 복원할 수 없는 것은 지우지 않는다: 기록 시트도 함께
                // 복원 가능하거나, 애초에 지울 판이 없을 때만 축을 비운다.
                if (shouldDelete(effectiveOptions.duels, duelAxisSpec())) {
                    // 이 갈래는 반대 방향으로 같은 접힘을 갖고 있었다 — 시트가 **없어도**
                    // "데이터 행이 없어"라고 말했다. 같은 함수를 지나게 한다.
                    val matchSource = classify(duelMatchSpec())
                    val existingMatches = db.duelMatchDao().countAll()
                    if (matchSource == RestoreSource.HAS_ROWS || existingMatches == 0) {
                        db.duelAxisDao().deleteAll()
                    } else {
                        result.warnings.add(
                            keepReason(matchSource, "'${duelMatchSpec().sheetName}'") +
                                " 대결 축을 삭제하지 않고 유지했습니다 " +
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
                            // 시트가 하나라도 *있는데 비어 있으면* '데이터 행이 없다'가 사실이다 —
                            // 전부 없을 때만 '시트가 없다'로 말한다.
                            result.warnings.add(
                                keepReason(
                                    if (charSheetSources.any { it == RestoreSource.EMPTY }) RestoreSource.EMPTY
                                    else RestoreSource.MISSING,
                                    "캐릭터"
                                ) + " 기존 캐릭터를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)"
                            )
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
                    val uSource = classify(uSpec)
                    val fdSource = classify(fdSpec)
                    when {
                        uSource != RestoreSource.HAS_ROWS ->
                            result.warnings.add(
                                keepReason(uSource, "'${uSpec.sheetName}'") +
                                    " 기존 세계관을 삭제하지 않고 유지했습니다 (덮어쓰기 제외)"
                            )
                        fdSource != RestoreSource.HAS_ROWS ->
                            result.warnings.add(
                                keepReason(fdSource, "'${fdSpec.sheetName}'") +
                                    " 세계관을 삭제하지 않았습니다 — 세계관을 지우면 모든 필드 정의와 캐릭터·사건 필드값이 함께 사라지는데 복원할 수 없습니다"
                            )
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
            resetUniverseIndex()
            matchedEventIds.clear()
            matchedRelationshipIds.clear()
            matchedRelationshipChangeIds.clear()
            matchedStateChangeIds.clear()
            matchedQuoteIds.clear()
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
            universesCountedUnchanged.clear()
            novelsCountedUnchanged.clear()
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
            // 명대사도 캐릭터 뒤다 — 임자가 심겨 있어야 코드·이름으로 찾을 수 있다.
            if (effectiveOptions.quotes) importQuotes(workbook, result, onProgress, totalRows)
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
                    resetUniverseIndex()
                }
                // U-12b: 꺼진 종류는 남겨 뒀다는 사실을 그 자리에서 고지한다(삭제 뒤 최종 상태 기준).
                countKeptUnmatchedEntities(effectiveOptions, result)
            }

            // Phase 6: 시맨틱 필드 동기화 (출생/사망연도 ↔ 상태변화 ↔ 생존여부)
            if (pendingSyncCharacters.isNotEmpty()) {
                runPostImportSemanticSync(result)
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
        // 배정도 가져오기와 같은 판정(계획 우선 + 소비 추적)이다 — 이름만으로 찾으면 동명
        // 세계관 둘이 같은 시트를 보고, 캐릭터를 끄고 가져올 때 `(2)` 시트가 '인식되지 않음'
        // 경고에 잘못 오른다.
        val warnUniverses = db.universeDao().getAllUniversesList()
        val warnSheets = UniverseSheetFinder(workbook, warnUniverses)
        for (u in warnUniverses) {
            warnSheets.find(u)?.let { recognizedSheets.add(it.sheetName) }
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

    private fun readUniverseRow(row: Row, c: UniverseCols, ctx: String, result: ImportResult?): UniverseRowValues {
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
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
    /**
     * 이 행이 **만들** 세계관 — 규약 R-33의 셋째 짝(B-233).
     *
     * `read*Row`(같은 것을 읽는가) · `merge*`(같은 것을 쓰는가) 옆의 세 번째다:
     * **같은 것을 만드는가.** 가져오기가 insert 하는 값을 미리보기도 이 함수로 지어,
     * 같은 시트의 뒤 행이 그것과 매칭될 수 있게 등재한다(파일 안 중복 행).
     *
     * 이미지 참조는 여기서 null이고 2단계가 코드로 되붙인다 — 가져오기와 같다.
     */
    private fun newUniverseFrom(r: UniverseRowValues, code: String, rowIndex: Int, now: Long): Universe =
        Universe(
            name = r.name, description = r.description ?: "", code = code,
            displayOrder = r.displayOrder ?: rowIndex.toLong(),
            borderColor = r.borderColor ?: "", borderWidthDp = r.borderWidthDp ?: 1.5f,
            imagePaths = r.imagePaths ?: "[]", imageMode = r.imageMode ?: "none",
            customRelationshipTypes = r.customRelationshipTypes ?: "",
            customRelationshipColors = r.customRelationshipColors ?: "",
            imageCharacterId = null, // deferred
            imageNovelId = null,     // deferred
            createdAt = r.createdAt ?: now
        )

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

    private fun readNovelRow(row: Row, c: NovelCols, ctx: String, result: ImportResult?): NovelRowValues {
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
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

    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 작품(R-33 셋째 · B-233). */
    private fun newNovelFrom(
        r: NovelRowValues, code: String, universeId: Long?, rowIndex: Int, now: Long
    ): Novel = Novel(
        title = r.title, description = r.description ?: "", universeId = universeId,
        code = code, displayOrder = r.displayOrder ?: rowIndex.toLong(),
        borderColor = r.borderColor ?: "", borderWidthDp = r.borderWidthDp ?: 1.5f,
        inheritUniverseBorder = r.inheritUniverseBorder, isPinned = r.isPinned,
        imagePaths = r.imagePaths ?: "[]", imageMode = r.imageMode ?: "none",
        imageCharacterId = null, // deferred
        standardYear = r.standardYear,
        createdAt = r.createdAt ?: now
    )

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
        /** null = 열 없음(기존 유지 — R-36). 빈 문자열 = 열 있음+빈칸(가져오기가 행을 거부한다). */
        val autoRelationType: String?,
        /** null = 열 없음(기존 유지 — R-36). 종전에는 열을 지운 파일이 전 세력의 강도를 5로 무음 리셋했다. */
        val autoRelationIntensity: Int?,
        val displayOrder: Int?,
        val createdAt: Long?
    )

    private fun readFactionRow(row: Row, c: FactionCols, ctx: String, result: ImportResult?): FactionRowValues =
        FactionRowValues(
            name = getCellString(row, c.name),
            code = getCellCode(row, c.code, ctx, result),
            universeName = if (c.universeName >= 0) getCellString(row, c.universeName) else "",
            universeCode = getCellCode(row, c.universeCode, ctx, result),
            // F1-A: 열 없음 → null(기존 유지). 열 있음 → 셀 값(빈칸 = 비움 의도 존중).
            description = if (c.desc >= 0) getCellString(row, c.desc) else null,
            color = if (c.color >= 0) getCellString(row, c.color).ifBlank { "#2196F3" } else null,
            autoRelationType = if (c.autoRelType >= 0) getCellString(row, c.autoRelType) else null,
            autoRelationIntensity = if (c.autoRelIntensity >= 0) (parseIntensityWithWarn(row, c.autoRelIntensity, 5, ctx, result) ?: 5) else null,
            displayOrder = if (c.order >= 0) getCellString(row, c.order).let { if (it.isBlank()) null else parseNumber(it)?.toInt() } else null,
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
        )

    /**
     * [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 세력(R-33 셋째 · B-233).
     * [autoRelationType]을 따로 받는 것은 *"열이 없으면 새 세력을 만들지 않는다"*(R-36)가
     * 호출부의 가드이기 때문이다 — 여기서 기본값을 지어 내면 그 규칙이 조용히 무너진다.
     */
    private fun newFactionFrom(
        r: FactionRowValues, code: String, universeId: Long, autoRelationType: String, rowIndex: Int, now: Long
    ): Faction = Faction(
        name = r.name, universeId = universeId,
        description = r.description ?: "", color = r.color ?: "#2196F3",
        autoRelationType = autoRelationType, autoRelationIntensity = r.autoRelationIntensity ?: 5,
        code = code, displayOrder = r.displayOrder ?: rowIndex, createdAt = r.createdAt ?: now
    )

    private fun mergeFaction(existing: Faction, r: FactionRowValues, universeId: Long): Faction = existing.copy(
        name = r.name,
        universeId = universeId,
        description = r.description ?: existing.description,
        color = r.color ?: existing.color,
        // 열 없음(null) = 기존 값 유지(R-36) — 캐릭터 관계 시트의 hasIntensityCol과 같은 규약.
        autoRelationType = r.autoRelationType ?: existing.autoRelationType,
        autoRelationIntensity = r.autoRelationIntensity ?: existing.autoRelationIntensity,
        displayOrder = r.displayOrder ?: existing.displayOrder,
        createdAt = r.createdAt ?: existing.createdAt
    )

    private class NameBankCols(cols: Map<String, Int>) {
        val name = cols["이름"] ?: 0
        // 위치 폴백 금지 — 열을 지우면 이웃 열을 오독한다('성별' 열을 지운 파일에서 성별이
        // 출처 텍스트로 무음 오염되던 자리). 열 없음(-1) = 말한 바 없음 = 기존 유지(F1-A) —
        // 같은 클래스의 사용여부·사용 캐릭터와 같은 규약이다.
        val gender = cols["성별"] ?: -1
        val origin = cols["출처"] ?: -1
        val notes = cols["메모"] ?: -1
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
        /** null = 열 없음(기존 유지). 빈 문자열 = 비움 의도(F1-A). */
        val gender: String?,
        val origin: String?,
        val notes: String?,
        val usedFlag: Boolean?,
        val usedByCharName: String,
        val usedByCharCode: String,
        val usedIntent: RefIntent,
        val createdAt: Long?,
        val code: String
    ) {
        /**
         * 자연키(이름+성별). 기존 항목 쪽은 [mapKeyForNameBank]가 **같은 식으로** 만든다.
         * 성별 열이 없는 파일은 자연키를 지을 수 없다(null) — 호출부는 이름 단독 유일 폴백을 쓴다.
         */
        val mapKey: String? get() = gender?.let { nameBankKey(name, it) }
    }

    private fun readNameBankRow(row: Row, c: NameBankCols, ctx: String, result: ImportResult?): NameBankRowValues {
        val usedByCharName = if (c.usedBy >= 0) getCellString(row, c.usedBy) else ""
        val usedByCharCode = getCellCode(row, c.charCode, ctx, result)
        return NameBankRowValues(
            name = getCellString(row, c.name),
            gender = if (c.gender >= 0) getCellString(row, c.gender) else null,
            origin = if (c.origin >= 0) getCellString(row, c.origin) else null,
            notes = if (c.notes >= 0) getCellString(row, c.notes) else null,
            usedFlag = sheetBooleanOrKeep(c.used >= 0, getCellString(row, c.used)),
            usedByCharName = usedByCharName,
            usedByCharCode = usedByCharCode,
            // 사용 캐릭터는 편집 가능한 '사용 캐릭터' + readOnly '사용캐릭터코드'의 참조 열 쌍이다
            // (관계 시트의 '세력'/'세력코드'와 동형): 유무는 이름 열이, 대상은 코드가 정한다.
            usedIntent = refColumnIntent(c.usedBy >= 0, c.charCode >= 0, usedByCharName, usedByCharCode),
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result),
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
            // 열 없음(null) = 기존 값 유지(F1-A) — 열을 지운 파일이 값을 지우면 안 된다.
            name = r.name, gender = r.gender ?: existing.gender,
            origin = r.origin ?: existing.origin, notes = r.notes ?: existing.notes,
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result),
            // '생성일'과 **같은 통로를 지난다** — 종전에는 이 줄만 밖에 있어 해석 불가가
            // 무경고로 버려졌다(같은 행의 이웃 열이 서로 다르게 처분되던 자리).
            updatedAt = readEpochMillisCell(
                row, c.updatedAt, ctx, "수정일", result,
                consequence = "빈 칸과 같게 처리합니다(기존 항목은 수정 시각 유지)"
            )
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result),
            // '생성일'과 **같은 통로를 지난다** — 종전에는 이 줄만 밖에 있어 해석 불가가
            // 무경고로 버려졌다(같은 행의 이웃 열이 서로 다르게 처분되던 자리).
            updatedAt = readEpochMillisCell(
                row, c.updatedAt, ctx, "수정일", result,
                consequence = "빈 칸과 같게 처리합니다(기존 항목은 수정 시각 유지)"
            )
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
        // 이 시트의 생성일은 값이 아니라 매칭 키다 — 해석 불가의 뜻이 달라 문구만 다르다.
        val createdAt = readCreatedAtCell(row, c.createdAt, ctx, result, consequence = "이름으로 매칭합니다")
        return PresetTemplateRowValues(
            name = getCellString(row, c.name),
            // F1-A: 열이 없으면 null(기존 값 유지), 열이 있고 빈칸이면 비움 의도로 존중
            description = if (c.desc >= 0) getCellString(row, c.desc) else null,
            fieldsJson = if (c.fieldsJson >= 0) getCellString(row, c.fieldsJson).ifBlank { "[]" } else null,
            isBuiltIn = sheetBooleanOrKeep(c.builtIn >= 0, getCellString(row, c.builtIn)),
            createdAt = createdAt,
            // '생성일'과 **같은 통로를 지난다** — 종전에는 이 줄만 밖에 있어 해석 불가가
            // 무경고로 버려졌다(같은 행의 이웃 열이 서로 다르게 처분되던 자리).
            updatedAt = readEpochMillisCell(
                row, c.updatedAt, ctx, "수정일", result,
                consequence = "빈 칸과 같게 처리합니다(기존 항목은 수정 시각 유지)"
            )
        )
    }

    /** createdAt은 이 시트의 정체성이라 파일 값으로 덮지 않는다(코드 열과 동일 취급). */
    /**
     * [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 필드 템플릿(R-33 셋째 · B-233).
     * 신규는 엔티티 기본값이다(갱신=F1-A, 신규=기본값 분리 규약).
     */
    private fun newPresetTemplateFrom(r: PresetTemplateRowValues, now: Long): UserPresetTemplate =
        UserPresetTemplate(
            name = r.name,
            description = r.description ?: "",
            fieldsJson = r.fieldsJson ?: "[]",
            isBuiltIn = r.isBuiltIn ?: false,
            createdAt = r.createdAt ?: now,
            updatedAt = r.updatedAt ?: now
        )

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
        // 위치 폴백 금지 — **형제 선택 열 여덟이 이미 -1인데 이 한 칸만 남아 있었다**(B-223).
        // 5번 자리를 폴백으로 두면 '그룹' 열을 지운 파일에서 이웃 `순서` 열이 그룹명이 되고,
        // 앞 네 열만 남긴 최소 파일에서는 전 필드의 그룹이 리셋된다 — **둘 다 무경고**이고
        // 미리보기도 같은 코드라 못 잡는다(R-36 · 개발 의도 2번).
        val group = cols["그룹"] ?: -1
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
        /**
         * 열이 없으면 **null** — *말한 바 없음*이라 기존 값을 지킨다 (R-36 · B-223).
         * 형제 시트('기본 필드')가 이미 이 모양이었고 이 시트만 갈려 있었다.
         */
        val groupName: String?,
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
            groupName = if (c.group >= 0) getCellString(row, c.group).ifBlank { "기본 정보" } else null,
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
                "필드 정의 행 ${excelRow(rowIndex)}: 필드 '$fieldName'이(가) 가리키는 기본 필드 '$code'을(를) " +
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

    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 필드 정의(R-33 셋째 · B-233). */
    private fun newFieldDefinitionFrom(
        r: FieldDefRowValues, universeId: Long?, mergedConfig: String, rowIndex: Int
    ): FieldDefinition = FieldDefinition(
        universeId = universeId, key = r.key, name = r.name, type = r.type,
        // 새 행에서는 null이 곧 기본값이다 — 지킬 기존 값이 없다(R-36 후반부).
        config = mergedConfig, groupName = r.groupName ?: "기본 정보", displayOrder = r.displayOrder ?: rowIndex,
        isRequired = r.isRequired ?: false, entityType = r.entityType
    )

    private fun mergeFieldDefinition(existing: FieldDefinition, r: FieldDefRowValues, mergedConfig: String): FieldDefinition =
        existing.copy(
            name = r.name, type = r.type, config = mergedConfig,
            groupName = r.groupName ?: existing.groupName, displayOrder = r.displayOrder ?: existing.displayOrder,
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

    private fun readTimelineRow(row: Row, c: TimelineCols, ctx: String, result: ImportResult?): TimelineRowValues {
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
        )
    }

    /** 사건의 작품 연결과 세계관 소속 해석 — 갱신·미리보기·필드값 적용이 **같은 값**을 봐야 한다. */
    private data class TimelineLinks(
        val novelIds: List<Long>,
        val universeId: Long?,
        /** 해석에 실패한 작품 코드/제목 토큰 — 비어 있지 않으면 파일의 연결 목록을 전부 읽지 못한 것이다. */
        val unresolvedNovelTokens: List<String> = emptyList(),
        /**
         * 동명 작품이 여럿이라 하나로 좁혀지지 않은 제목 토큰 — 미해석과 **다른 사실**이고
         * 섞어 말하면 거짓 경고다(4-3 규약: 모호를 '찾을 수 없음'으로 보고하지 말 것).
         * 비어 있지 않으면 호출부는 미해석과 같은 정책(기존 유지)을 쓰되 문구를 가른다.
         */
        val ambiguousNovels: List<AmbiguousNovelRef> = emptyList()
    )

    private suspend fun resolveTimelineLinks(
        row: Row, c: TimelineCols, r: TimelineRowValues, novelTitles: NovelTitleIndex, ctx: String, result: ImportResult?
    ): TimelineLinks {
        // 세계관 소속: 명시 열(코드 우선 → 이름) 우선, 없으면 관련 작품에서 유도(구버전 호환).
        // **제목 해석보다 먼저 푼다** — 동명 작품을 이 열로 좁히기 때문이다(4-3 규약).
        val explicitUniverse = run {
            val uCode = getCellCode(row, c.universeCode, ctx, result)
            val uName = if (c.universeName >= 0) getCellString(row, c.universeName) else ""
            if (uCode.isBlank() && uName.isBlank()) return@run null
            val resolved = universeByCodeOrName(uCode, uName)
            if (resolved == null) {
                result?.warnings?.add("$ctx: 세계관 '${uName.ifBlank { uCode }}'을(를) 찾을 수 없어 관련 작품에서 유도합니다")
            }
            resolved
        }
        // 작품 해석: 콤마 구분 복수 작품 지원.
        // 미해석 토큰을 조용히 버리지 않는다 — 부분 해석된 목록으로 기존 연결을 교체하면
        // 미해석 몫의 연결이 무음 삭제된다(선택 가져오기·기기 이전·오타에서 실제로 걸리는 경로).
        // 수집해 두고 호출부가 정책(기존 유지+경고)을 정한다.
        val unresolvedNovelTokens = mutableListOf<String>()
        val ambiguousNovels = mutableListOf<AmbiguousNovelRef>()
        val novelCodeCells = splitCsv(r.novelCode)
        var resolvedNovels = if (novelCodeCells.isNotEmpty()) {
            novelCodeCells.mapNotNull { code -> novelByCode(code).also { if (it == null) unresolvedNovelTokens.add(code) } }
        } else emptyList()
        if (resolvedNovels.isEmpty()) {
            // 코드 전량 미해석(또는 코드 열 빈칸) → 제목 폴백(구버전·손수 파일).
            // 동명 제목은 first-match 하지 않는다 — 세계관 열로 좁히고, 안 되면 모호로 선언한다
            // (종전에는 아무 작품에나 연결되고 그 세계관이 사건 소속으로까지 번졌다 — I2-2).
            unresolvedNovelTokens.clear()
            resolvedNovels = splitCsv(r.novelTitle).mapNotNull { title ->
                when (val lookup = novelTitles.resolve(title, explicitUniverse?.id)) {
                    is NovelTitleLookup.Found -> lookup.novel
                    is NovelTitleLookup.Ambiguous -> {
                        ambiguousNovels.add(AmbiguousNovelRef(title, lookup.candidates)); null
                    }
                    NovelTitleLookup.NotFound -> {
                        unresolvedNovelTokens.add(title); null
                    }
                }
            }
            // 제목 폴백까지 비었고 코드 토큰이 있었다면 그 코드가 미해석 사실이다(제목 열이 빈 파일).
            if (resolvedNovels.isEmpty() && unresolvedNovelTokens.isEmpty() &&
                ambiguousNovels.isEmpty() && novelCodeCells.isNotEmpty()
            ) {
                unresolvedNovelTokens.addAll(novelCodeCells)
            }
        }
        val derivedUniverseId = resolvedNovels.firstOrNull()?.universeId
        if (explicitUniverse != null && derivedUniverseId != null && explicitUniverse.id != derivedUniverseId) {
            result?.warnings?.add("$ctx: 세계관 열('${explicitUniverse.name}')과 관련 작품의 세계관이 달라 세계관 열을 우선합니다")
        }
        val novelIds = resolvedNovels.map { it.id }
        // 세계관 열이 명시됐으면 그 값, 아니면 작품 해석 성공 시 유도값, 둘 다 없으면 호출부가 기존 세계관을 보존한다.
        // 모호 토큰은 해석에 들지 않으므로 소속 유도의 근거도 되지 않는다(무근거 배정 금지).
        return TimelineLinks(
            novelIds = novelIds,
            universeId = if (explicitUniverse != null || novelIds.isNotEmpty()) (explicitUniverse?.id ?: derivedUniverseId) else null,
            unresolvedNovelTokens = unresolvedNovelTokens,
            ambiguousNovels = ambiguousNovels
        )
    }

    /**
     * [backfillCode]는 **기존 코드도 파일 코드도 없을 때 새로 붙일 코드**다.
     * 가져오기는 실제 코드를 발급하고, 미리보기는 [CODE_BACKFILL_PREVIEW]를 넘긴다 —
     * 값이 무엇이든 기존이 null이면 결과가 non-null이 되어 '변경'으로 잡히는데, **그것이 사실이다**
     * (가져오기가 그 행에 코드를 심는다). 미리보기가 매번 다른 난수를 만들면 안 되므로 상수를 쓴다.
     */
    /**
     * [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 사건(R-33 셋째 · B-233).
     * [year]를 따로 받는 것은 호출부의 가드가 이미 null을 걸렀기 때문이다(`!!`를 쓰지 않는다).
     */
    private fun newTimelineEventFrom(
        r: TimelineRowValues, year: Int, links: TimelineLinks, rowIndex: Int, now: Long, code: String
    ): TimelineEvent = TimelineEvent(
        year = year, month = r.month, day = r.day,
        calendarType = r.calendarType, description = r.description,
        eventType = r.eventType,
        universeId = links.universeId,
        displayOrder = r.displayOrder ?: rowIndex, isTemporary = r.isTemporary,
        createdAt = r.createdAt ?: now,
        code = code
    )

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

    private fun readStateChangeRow(row: Row, c: StateChangeCols, ctx: String, result: ImportResult?): StateChangeRowValues {
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
        )
    }

    /** [mergeTimelineEvent]와 같은 코드 백필 규약. */
    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 상태 변화(R-33 셋째 · B-233). */
    private fun newStateChangeFrom(
        r: StateChangeRowValues, characterId: Long, year: Int, now: Long, code: String
    ): CharacterStateChange = CharacterStateChange(
        characterId = characterId, year = year, month = r.month, day = r.day,
        fieldKey = r.fieldKey, newValue = r.newValue, description = r.description,
        createdAt = r.createdAt ?: now, code = code
    )

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

    // ── 캐릭터 명대사 (사용자 요청 2026.08.20) ──

    private class QuoteCols(cols: Map<String, Int>, val text: Int) {
        val charName = cols["캐릭터"] ?: 0
        // 위치 폴백 금지 (R-36) — 열을 지운 파일에서 엉뚱한 칸을 작품·상황으로 읽는다.
        val novel = cols["작품"] ?: -1
        val occasion = cols["상황"] ?: -1
        val note = cols["출처"] ?: -1
        val sortOrder = cols["표시순서"] ?: -1
        val charCode = cols["캐릭터코드"] ?: -1
        val code = cols["코드"] ?: -1
        val createdAt = cols["생성일"] ?: -1
    }

    private data class QuoteRowValues(
        val charName: String,
        val charCode: String,
        val novelTitle: String,
        val text: String,
        val hasOccasionCol: Boolean, val occasionKey: String,
        val hasNoteCol: Boolean, val note: String,
        val hasSortOrderCol: Boolean, val sortOrder: Int?,
        val fileCode: String,
        val createdAt: Long?
    )

    private fun readQuoteRow(row: Row, c: QuoteCols, ctx: String, result: ImportResult?): QuoteRowValues =
        QuoteRowValues(
            charName = getCellString(row, c.charName),
            charCode = getCellCode(row, c.charCode, ctx, result),
            novelTitle = getCellString(row, c.novel),
            text = getCellString(row, c.text),
            // **상황은 글자 그대로다.** `__birthday`를 '생일'로 고쳐 적은 파일을 되읽으면
            // 그것은 사용자가 만든 상황 이름이지 예약 자리가 아니다 — 고쳐 읽으면 사용자가
            // 적은 구분이 앱의 짐작으로 덮인다(개발 의도 2번).
            hasOccasionCol = c.occasion >= 0, occasionKey = getCellString(row, c.occasion),
            hasNoteCol = c.note >= 0, note = getCellString(row, c.note),
            hasSortOrderCol = c.sortOrder >= 0,
            sortOrder = if (c.sortOrder >= 0) parseNumber(getCellString(row, c.sortOrder))?.toInt() else null,
            fileCode = getCellCode(row, c.code, ctx, result),
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
        )

    /** [newStateChangeFrom]과 같은 규약 — 이 행이 **만들** 명대사(R-33 셋째). */
    private fun newQuoteFrom(
        r: QuoteRowValues, characterId: Long, now: Long, code: String
    ): CharacterQuote = CharacterQuote(
        characterId = characterId,
        text = r.text,
        occasionKey = r.occasionKey,
        note = r.note,
        sortOrder = r.sortOrder ?: 0,
        createdAt = r.createdAt ?: now,
        code = code
    )

    /** [mergeStateChange]와 같은 규약 — 열 없음은 **기존값 유지**다(R-36). */
    private fun mergeQuote(
        existing: CharacterQuote,
        r: QuoteRowValues,
        characterId: Long,
        backfillCode: String
    ): CharacterQuote = existing.copy(
        // 코드 매칭 시 자연키 구성 요소(대사 글자)도 편집 가능하다.
        characterId = characterId,
        text = r.text,
        // 열 없음 = 기존값 유지 (열 삭제로 인한 무음 손실 방지)
        occasionKey = if (r.hasOccasionCol) r.occasionKey else existing.occasionKey,
        note = if (r.hasNoteCol) r.note else existing.note,
        sortOrder = if (r.hasSortOrderCol) (r.sortOrder ?: existing.sortOrder) else existing.sortOrder,
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

    private fun readRelationshipRow(row: Row, c: RelationshipCols, ctx: String, result: ImportResult?): RelationshipRowValues {
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
        )
    }

    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 관계(R-33 셋째 · B-233). */
    private fun newRelationshipFrom(
        r: RelationshipRowValues, characterId1: Long, characterId2: Long,
        factionId: Long?, rowIndex: Int, now: Long, code: String
    ): CharacterRelationship = CharacterRelationship(
        characterId1 = characterId1, characterId2 = characterId2,
        relationshipType = r.relationshipType, description = r.description,
        intensity = r.intensity, isBidirectional = r.isBidirectional,
        displayOrder = r.displayOrder ?: rowIndex, factionId = factionId,
        createdAt = r.createdAt ?: now, code = code
    )

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

    private fun readRelChangeRow(row: Row, c: RelChangeCols, ctx: String, result: ImportResult?): RelChangeRowValues {
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
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

    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 관계 변화(R-33 셋째 · B-233). */
    private fun newRelationshipChangeFrom(
        r: RelChangeRowValues, relationshipId: Long, year: Int, eventId: Long?, now: Long, code: String
    ): CharacterRelationshipChange = CharacterRelationshipChange(
        relationshipId = relationshipId,
        year = year, month = r.month, day = r.day,
        relationshipType = r.relationshipType, description = r.description,
        intensity = r.intensity, isBidirectional = r.isBidirectional,
        eventId = eventId, createdAt = r.createdAt ?: now, code = code
    )

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

    private fun readCharacterRow(row: Row, c: CharacterCols, ctx: String, result: ImportResult?): CharacterRowValues {
        // imageColIndex < 0 means column is missing: use null sentinel to preserve existing images
        val rawImagePaths: String? = if (c.image >= 0) {
            val cell = getCellString(row, c.image).ifBlank { "[]" }
            // **읽을 수 없는 값은 비움(배정 해제)이 아니라 기존 유지 + 경고다** (콜드 검토
            // 2026.08.20 — 생성일과 같은 처분). 종전에는 깨진 편집이 그대로 저장돼 모든 읽는
            // 자리가 빈 목록으로 해석했다 — 오타 하나가 이미지 배정 전체를 무고지로 풀었다.
            // 빈 칸·유효한 빈 배열('[]'·'[ ]')만 비움 의도로 읽는다(F1-A). 미리보기도 이
            // 함수를 지나므로 예고와 처분이 갈리지 않는다(R-33 — result=null이면 값만 든다).
            if (CharacterRepresentativeImage.isPathListJson(cell)) cell
            else {
                result?.warnings?.add(
                    "$ctx: 이미지경로 '${truncateForCell(cell, SETTING_VALUE_IN_WARNING)}'을(를) 목록으로 읽을 수 없어 기존 이미지 배정을 유지합니다 — 배정을 지우려면 칸을 비우세요"
                )
                null
            }
        } else null
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
            createdAt = readCreatedAtCell(row, c.createdAt, ctx, result)
        )
    }

    /**
     * [now]는 `updatedAt`에 찍을 시각이다 — **내용이 그대로면 찍지 않는다**(프리셋과 같은 규약, 설계 2-2).
     * 종전 가져오기는 아무것도 안 바뀐 행에도 시각을 새로 찍었고, 그러면 미리보기가
     * 모든 행을 '변경'이라 말하거나(쓸모 없음) 비교에서 빼고 거짓말하거나 둘 중 하나가 된다.
     */
    /**
     * [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 캐릭터(R-33 셋째 · B-233).
     * 대표 이미지 셀 해석까지 포함한다: 가져오기가 insert 하는 값이 곧 그 결과다.
     */
    private fun newCharacterFrom(
        r: CharacterRowValues, code: String, novelId: Long?, rowIndex: Int, now: Long, result: ImportResult?
    ): Character = applyRepresentativeCell(
        Character(
            name = r.name, firstName = r.firstName ?: "", lastName = r.lastName ?: "",
            anotherName = r.anotherName ?: "", novelId = if (r.novelColumnsPresent) novelId else null,
            imagePaths = r.imagePaths ?: "[]", memo = r.memo ?: "", code = code,
            displayOrder = r.displayOrder ?: rowIndex.toLong(),
            isPinned = r.isPinned ?: false, createdAt = r.createdAt ?: now
        ),
        r.representativeCell, r.name, rowIndex, result
    )

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
            // 태그는 **조회 없이 그대로 저장된다** — 전각을 낮춰 읽으면 조용한 개명이다.
            tags = if (c.tag >= 0) splitCsvIdentity(getCellString(row, c.tag)).toSet() else emptySet(),
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
        resetUniverseIndex()
        // 시트별 캐시도 여기서 비운다 — 위 색인들과 같은 사유다(미리보기는 직전 가져오기 뒤에
        // 돌 수 있고, 이 맵들은 `Sheet` 객체를 키로 들고 있어 비우지 않으면 그 참조가 남는다).
        // 종전에는 `importAll`에서만 비웠다. `headerRowIndexBySheet`는 B-231 ⓑ가 세운 것이고,
        // `mergedCellMaps`는 그때부터 같은 모양이었다.
        mergedCellMaps.clear()
        headerRowIndexBySheet.clear()
        // 임시 id 공간도 **색인과 같은 자리에서** 비운다 — 수명이 갈리면 한 색인에 두 id 공간이
        // 붙는다(위 [previewIds] KDoc의 실제 사고). 여기 있으면 그 갈림이 생길 수 없다.
        previewIds = PreviewIdMinter()
        // 이 파일이 만들 필드 정의도 같은 자리에서 비운다 — 남으면 지난 분석이 만든 필드로
        // 이번 파일의 필드값이 해석된다(형제 색인들과 같은 근거).
        analysisCreatedFields.clear()
        // 이 파일이 만들 세계관도 같은 자리에서 비운다 (B-254) — 남으면 지난 분석이 만든
        // 세계관 이름으로 이번 파일의 캐릭터 시트를 찾는다(형제 목록·색인들과 같은 근거).
        analysisCreatedUniverses.clear()
        // 이 파일이 만들 대결 축도 같은 자리에서 비운다 — 수명이 갈리면 지난 분석의 축으로
        // 이번 파일의 기록·상성을 해석한다(형제 목록·색인들과 같은 근거).
        analysisCreatedDuelAxes.clear()
        analysisUniverseMovedCharacterIds.clear()
        // 작품→세계관 메모도 여기서 비운다 — **직전 가져오기가 작품의 세계관을 옮겼을 수 있다**
        // (`migrateCharacterToUniverse` 경로). 위 색인들을 비우는 사유가 그대로 걸리는데
        // 이 메모만 빠져 있었고, 세계관 이동 판정(B-253)이 이 값 위에 선다.
        novelUniverseCache.clear()
        val totalRows = countTotalRows(workbook)

        // 필드값 범주 셋 (B-187). **겹은 미리보기 시작에 한 번만 싣는다** — 표를 행마다 묻지
        // 않고(R-53), 같은 읽기가 '현재 DB' 총계도 먹인다(B-236과 같은 처방).
        //
        // **순서가 규약이다.** 본 시트(캐릭터·작품·연표)가 먼저 세고 오버플로 시트가 뒤에 센다 —
        // 가져오기가 그 순서라서 *"본 시트가 권위"* 판정에 쓸 쌍이 그때 채워져 있다.
        // 그래서 각 겹은 **자기 소유자 범주를 도는 동안** 살아 있어야 하고, 여기 산다.
        val novelValues = if (options.novels) FieldValueScan(
            FieldValueOverlay.of(db.novelFieldValueDao().getAllValuesList()) {
                Triple(it.novelId, it.fieldDefinitionId, it.value)
            }
        ) else null
        val characterValues = if (options.characters) FieldValueScan(
            FieldValueOverlay.of(db.characterFieldValueDao().getAllValuesList()) {
                Triple(it.characterId, it.fieldDefinitionId, it.value)
            }
        ) else null
        val eventValues = if (options.timeline) FieldValueScan(
            FieldValueOverlay.of(db.eventFieldValueDao().getAllValuesList()) {
                Triple(it.eventId, it.fieldDefinitionId, it.value)
            }
        ) else null

        if (options.universes) categories.add(analyzeUniverses(workbook, onProgress, totalRows))
        if (options.fieldDefinitions) {
            categories.add(analyzeGradeSystems(workbook, options, onProgress, totalRows))
            categories.add(analyzeDefaultFieldTemplates(workbook, onProgress, totalRows))
            categories.add(analyzeFieldDefinitions(workbook, options, onProgress, totalRows))
            categories.add(analyzeFieldValueLibrary(workbook, onProgress, totalRows))
        }
        // **필드 정의 다음에 작품을 본다** — 가져오기가 그 순서이기 때문이다(확-3의 근거가
        // 그대로 여기 걸린다: 작품 시트의 '필드:' 열은 정의를 찾아야 값이 붙는다). 종전에는
        // 미리보기만 작품을 먼저 봐서, **빈 DB 복원**에서 그 열이 통째로 해석에 실패했다 —
        // 가져오기는 같은 파일의 정의로 전부 쓰는데 미리보기는 "작품 필드값 0건"이라 말한다(B-187).
        if (options.novels) categories.add(analyzeNovels(workbook, onProgress, totalRows, novelValues))
        if (options.characters) {
            val charResult = analyzeCharacters(workbook, onProgress, totalRows, characterValues)
            categories.add(charResult.category)
            characterConflicts = charResult.conflicts
        }
        if (options.timeline) categories.add(analyzeTimeline(workbook, onProgress, totalRows, eventValues))

        // 오버플로 시트는 본 시트 **뒤에** 센다(위 순서 규약). 범주는 소유자 범주 바로 뒤에
        // 서는 것이 자연스럽지만, 계수가 이 시점에야 완성되므로 자리는 여기다.
        if (novelValues != null) {
            analyzeNovelFieldValueSheet(workbook, novelValues)
            categories.add(fieldValueCategory("novelFieldValues", "작품 필드값", novelValues))
        }
        if (characterValues != null) {
            analyzeCharacterFieldValueSheet(workbook, characterValues)
            categories.add(fieldValueCategory("characterFieldValues", "캐릭터 필드값", characterValues))
        }
        if (eventValues != null) {
            analyzeEventFieldValueSheet(workbook, eventValues)
            categories.add(fieldValueCategory("eventFieldValues", "사건 필드값", eventValues))
        }
        if (options.stateChanges) categories.add(analyzeStateChanges(workbook, options, onProgress, totalRows))
        if (options.quotes) categories.add(analyzeQuotes(workbook, options, onProgress, totalRows))
        if (options.relationships) categories.add(analyzeRelationships(workbook, options, onProgress, totalRows))
        if (options.relationshipChanges) categories.add(analyzeRelationshipChanges(workbook, options, onProgress, totalRows))
        if (options.nameBank) categories.add(analyzeNameBank(workbook, onProgress, totalRows))
        if (options.factions) categories.add(analyzeFactions(workbook, options, onProgress, totalRows))
        if (options.factionMemberships) categories.add(analyzeFactionMemberships(workbook, options, onProgress, totalRows))
        if (options.factionRelationships) categories.add(analyzeFactionRelationships(workbook, onProgress, totalRows))
        if (options.presetTemplates) categories.add(analyzePresetTemplates(workbook, onProgress, totalRows))
        if (options.searchPresets) categories.add(analyzeSearchPresets(workbook, onProgress, totalRows))
        if (options.characterListPresets) categories.add(analyzeCharacterListPresets(workbook, onProgress, totalRows))
        if (options.imageMeta) categories.add(analyzeImageMeta(workbook, onProgress, totalRows))
        // **축 → 기록 → 상성** — 가져오기가 그 순서다(정의가 기록보다 앞이다).
        if (options.duels) {
            categories.add(analyzeDuelAxes(workbook, options, onProgress, totalRows))
            categories.add(analyzeDuelMatches(workbook, options, onProgress, totalRows))
            categories.add(analyzeDuelVerdicts(workbook, options, onProgress, totalRows))
        }
        // **맨 뒤다 — 가져오기가 그 순서이기 때문이다**(`importAll`은 앱 설정을 커밋 뒤에
        // 적용한다. DataStore·SharedPreferences는 DB 트랜잭션이 되돌리지 못한다).
        if (options.appSettings) categories.add(analyzeAppSettings(workbook, onProgress, totalRows))

        return RestoreAnalysis(categories, characterConflicts)
    }

    // ── 대결 복원 미리보기 (R-33 — 가져오기와 **같은 read/merge 쌍**으로 판정한다) ──

    private suspend fun analyzeDuelAxes(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = duelAxisSpec()
        val label = "대결 축"
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236).
        val allAxes = db.duelAxisDao().getAllList()
        val existingTotal = allAxes.size
        val sheet = sheetForAnalysis(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("duelAxes", label, 0, 0, 0, 0, existingTotal)
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("duelAxes", label, 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val now = System.currentTimeMillis()
        // 정체성 색인도 가져오기와 **같은 클래스**다(B-236).
        val axes = DuelAxisIndexes(allAxes)
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readDuelAxisRow(row, cols, now)
            if (r.name.isBlank()) continue
            inBackup++

            // B-102 ⓑ: '세계관'을 함께 가져오면 그것이 먼저 생기므로 '신규'가 맞고,
            // 빼놓았으면 가져오기가 이 행을 거부한다. 종전에는 무조건 skipped라 신규 기기
            // 복원 미리보기가 축 전부를 '실행 안 함'으로 예고했다(B-217).
            // **참조가 통째로 빈 행은 다르다** — 세계관 시트가 만들어 줄 수도 없으므로
            // 가져오기가 영원히 거부한다. '미해석'과 '참조 부재'를 가른다.
            val universe = universeByCodeOrName(r.universeCode, r.universeName)
            if (universe == null) {
                val universeRefPresent = r.universeName.isNotBlank() || r.universeCode.isNotBlank()
                if (universeRefPresent && options.universes) newCount++ else skippedCount++
                continue
            }

            val existing = (if (r.code.isNotBlank()) axes.byCode.first(r.code) else null)
                ?: axes.byNameKey.first(DuelAxisNameKey(universe.id, r.targetType, r.name))
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만든 축 — 같은 코드·같은 (세계관, 대상, 이름)을 든 뒷 행이
                // 이것과 매칭된다. 가져오기 쪽은 유니크 색인이 던져 막는 자리다(B-233).
                val created = newDuelAxisFrom(r, universe.id, r.code).copy(id = previewIds.mint())
                axes.remember(created)
                // 기록·상성 분석이 이것을 봐야 '파일이 만들 축'과 '오타'를 가른다.
                analysisCreatedDuelAxes.add(created)
                continue
            }
            val merged = mergeDuelAxis(existing, r, universe.id)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "대결 축 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("duelAxes", label, inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeDuelMatches(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = duelMatchSpec()
        val label = "대결 기록"
        val existingTotal = db.duelMatchDao().countAll()
        val sheet = sheetForAnalysis(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("duelMatches", label, 0, 0, 0, 0, existingTotal)
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("duelMatches", label, 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val now = System.currentTimeMillis()
        // 가져오기와 **같은 색인**을 세운다 — 행마다 조회하면 수만 행에서 미리보기가 멎는다.
        // **DB 먼저, 이 파일이 만들 축이 뒤**(형제 목록들과 같은 순서 규약) —
        // 이것이 없으면 *파일이 만들 축*과 *오타*를 가릴 재료가 없다.
        val axes = db.duelAxisDao().getAllList() + analysisCreatedDuelAxes
        // **빈 코드는 키가 아니다**(콜드 검토 2026.08.21). '대결 축' 시트에 코드 칸이 빈 축이
        // 둘 이상 있으면 — 손편집 파일이나 '코드' 열이 없는 옛 파일이 그렇다 — 종전 색인은
        // `""` 키 하나에 **마지막 축**만 남겼고, 축코드 없이 이름만 적은 기록·상성 행이
        // 전부 그 엉뚱한 축에 붙었다(이름은 보지도 않는다). 형제 색인이 이미 쓰는 규약이다
        // (`ImportLookupIndex`의 `takeIf { it.isNotBlank() }`).
        val axisByCode = axes.filter { it.code.isNotBlank() }.associateBy { it.code }
        val axesByName = axes.groupBy { it.name }
        val codeByName = db.characterDao().getAllCharactersList()
            .groupBy({ it.displayName }, { it.code })
        // **판 자신에는 그 근거가 적용되지 않아 행마다 `getByCode`가 남아 있었다**(B-236 — 가져오기
        // 쪽이 B-210에서 겪은 것과 같은 자리다). 코드 열에 인덱스가 없어 그 하나하나가 풀스캔이다.
        val matches = DuelMatchIndexes(db.duelMatchDao().getAllList())

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readDuelMatchRow(row, cols, now)
            if (r.axisName.isBlank() && r.axisCode.isBlank()) continue
            inBackup++

            // B-102 ⓑ: 축이 지금 DB에 없어도 같은 파일의 '대결 축' 시트가 먼저 만든다 —
            // **그 축은 이제 색인에 실려 있으므로**(analysisCreatedDuelAxes) 여기서 null인 것은
            // *파일에도 DB에도 없다*만 뜻한다. 가져오기가 영원히 거부하는 행이므로 '건너뜀'이다.
            //
            // 종전에는 색인이 DB만 봐서 *파일이 만들 축*과 *오타*를 가릴 재료가 없었고,
            // 낙관 가지가 **둘을 함께 '신규'로** 셌다 — 없는 축을 가리킨 행이 '신규'로 예고된 뒤
            // 실행에서 조용히 빠졌다. `options.duels` 조건도 죽어 있었다(세 분석이 전부
            // `if (options.duels)` 안에서만 불리므로 언제나 참이다).
            val axis = r.axisCode.takeIf { it.isNotBlank() }?.let { axisByCode[it] }
                ?: axesByName[r.axisName]?.singleOrNull()
            if (axis == null) {
                skippedCount++
                continue
            }

            val aCode = r.aCode.ifBlank { codeByName[r.aName]?.singleOrNull().orEmpty() }
            val bCode = r.bCode.ifBlank { codeByName[r.bName]?.singleOrNull().orEmpty() }
            if (aCode.isBlank() || bCode.isBlank()) {
                val nameAmbiguous = (r.aCode.isBlank() && (codeByName[r.aName]?.size ?: 0) > 1) ||
                    (r.bCode.isBlank() && (codeByName[r.bName]?.size ?: 0) > 1)
                // 참가자 참조(코드든 이름이든)가 통째로 빈 쪽이 있으면 캐릭터 시트가 만들어
                // 줄 수도 없다 — 가져오기가 영원히 거부하므로 '신규'가 아니라 skipped다.
                val bothRefsPresent = (r.aCode.isNotBlank() || r.aName.isNotBlank()) &&
                    (r.bCode.isNotBlank() || r.bName.isNotBlank())
                if (!nameAmbiguous && bothRefsPresent && options.characters) newCount++ else skippedCount++
                continue
            }
            if (aCode == bCode) { skippedCount++; continue }
            // 승자 해석은 가져오기와 **같은 함수·같은 갈래**다(R-33) — 모호(동명 참가자)·미상은 스킵.
            val winnerCode = when (val winner = resolveDuelWinner(r.winnerText, aCode, r.aName, bCode, r.bName)) {
                is DuelWinner.Resolved -> winner.code
                DuelWinner.Ambiguous, DuelWinner.Unknown -> { skippedCount++; continue }
            }

            val existing = if (r.code.isNotBlank()) matches.byCode.first(r.code) else null
            if (existing == null) {
                newCount++
                // 같은 코드를 든 뒷 행이 이것과 매칭된다(B-233). 이 범주는 매칭이 **코드뿐**이라
                // 코드 칸이 빈 행은 등재해도 아무도 찾지 않는다 — 빈 코드가 색인의 키에서 빠지는
                // 것이 그대로 그 뜻이다(세계관 갈래의 긴 설명과 같은 규약).
                matches.remember(
                    newDuelMatchFrom(
                        r, axis.id, aCode, bCode, winnerCode, r.groupId.ifBlank { null },
                        r.code
                    ).copy(id = previewIds.mint())
                )
                continue
            }
            // 가져오기와 같은 참가자 검증(R-33) — 코드로 찾은 판과 행의 참가자가 다르면 병합하지 않는다.
            if (setOf(aCode, bCode) != setOf(existing.aCode, existing.bCode)) { skippedCount++; continue }
            val merged = mergeDuelMatch(existing, r, winnerCode, r.groupId.ifBlank { null })
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "대결 기록 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("duelMatches", label, inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    /**
     * 대결 상성의 복원 미리보기 (B-263 ⓐ).
     *
     * **이 범주는 미리보기에 통째로 없었다** — `analyzeAll`이 축·기록은 세면서 상성만 세지
     * 않아, *덮어쓰는* 축이 **예고 없이** 들어왔다(B-256의 census가 찾았다). 유실은 아니지만
     * 사용자가 **되돌릴 기회를 갖지 못하는** 것이 이 자리의 무게다(개발 의도 2번).
     *
     * 해석 사다리는 짝 [importDuelVerdicts]와 **같은 함수들**을 지난다(R-33) —
     * [readDuelVerdictRow] · [DuelRecords.resolveMembers] · [resolveDuelVerdictKind] ·
     * [newDuelVerdictFrom] · [mergeDuelVerdict].
     */
    private suspend fun analyzeDuelVerdicts(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = duelVerdictSpec()
        val label = "대결 상성"
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236) — 형제 둘과 같은 규약이다.
        val allVerdicts = db.duelCounterVerdictDao().getAllList().sortedBy { it.id }
        val existingTotal = allVerdicts.size
        val sheet = sheetForAnalysis(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("duelVerdicts", label, 0, 0, 0, 0, existingTotal)
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("duelVerdicts", label, 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val now = System.currentTimeMillis()
        // 가져오기와 **같은 색인**을 세운다 — 행마다 조회하면 수만 행에서 미리보기가 멎는다(R-53).
        // **DB 먼저, 이 파일이 만들 축이 뒤**(형제 목록들과 같은 순서 규약) —
        // 이것이 없으면 *파일이 만들 축*과 *오타*를 가릴 재료가 없다.
        val axes = db.duelAxisDao().getAllList() + analysisCreatedDuelAxes
        // **빈 코드는 키가 아니다**(콜드 검토 2026.08.21). '대결 축' 시트에 코드 칸이 빈 축이
        // 둘 이상 있으면 — 손편집 파일이나 '코드' 열이 없는 옛 파일이 그렇다 — 종전 색인은
        // `""` 키 하나에 **마지막 축**만 남겼고, 축코드 없이 이름만 적은 기록·상성 행이
        // 전부 그 엉뚱한 축에 붙었다(이름은 보지도 않는다). 형제 색인이 이미 쓰는 규약이다
        // (`ImportLookupIndex`의 `takeIf { it.isNotBlank() }`).
        val axisByCode = axes.filter { it.code.isNotBlank() }.associateBy { it.code }
        val axesByName = axes.groupBy { it.name }
        val codeByName = db.characterDao().getAllCharactersList()
            .groupBy({ it.displayName }, { it.code })
        val verdictCodes = ImportLookupIndex<String, DuelCounterVerdict>(
            idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
        )
        val verdictMemberKeys = ImportLookupIndex<DuelVerdictMemberKey, DuelCounterVerdict>(
            idOf = { it.id }, keyOf = { DuelVerdictMemberKey(it.axisId, it.memberKey) }
        )
        verdictCodes.load(allVerdicts)
        verdictMemberKeys.load(allVerdicts)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readDuelVerdictRow(row, cols, now)
            if (r.axisName.isBlank() && r.axisCode.isBlank()) continue
            inBackup++

            // 위 `analyzeDuelMatches`와 같은 판정이다 — 색인이 *파일이 만들 축*까지 들고 있으므로
            // 여기서 null인 것은 *파일에도 DB에도 없다*만 뜻한다(가져오기가 영원히 거부한다).
            val axis = r.axisCode.takeIf { it.isNotBlank() }?.let { axisByCode[it] }
                ?: axesByName[r.axisName]?.singleOrNull()
            if (axis == null) {
                skippedCount++
                continue
            }

            // 참가자 해석도 같은 사다리다. **동명이인은 가져오기가 영원히 거부하므로 skipped**이고,
            // *아직 없는 이름*은 캐릭터 시트가 만들어 줄 수 있어 '신규'다(`analyzeDuelMatches`와 같은 갈래).
            val members = when (val m = DuelRecords.resolveMembers(r.rawCodes, r.names, codeByName)) {
                is DuelRecords.MemberResolution.Resolved -> m.members
                is DuelRecords.MemberResolution.Unresolved -> {
                    if (!m.ambiguous && options.characters) newCount++ else skippedCount++
                    continue
                }
            }
            val shape = DuelRecords.shapeOf(members)
            if (shape == null) { skippedCount++; continue }

            val memberKey = DuelRecords.memberKey(members)
            val existing = (if (r.code.isNotBlank()) verdictCodes.first(r.code) else null)
                ?: verdictMemberKeys.first(DuelVerdictMemberKey(axis.id, memberKey))
            val kind = resolveDuelVerdictKind(r.kindLabel) ?: existing?.kind ?: DuelCounterVerdict.KIND_COUNTER
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만들 상성 — 같은 코드·같은 (축, 구성원키)를 든 뒷 행이 이것과
                // 매칭된다(B-233). 가져오기 쪽은 유니크 색인이 던져 막는 자리다.
                val minted = newDuelVerdictFrom(r, axis.id, members, memberKey, shape, kind, r.code)
                    .copy(id = previewIds.mint())
                verdictCodes.put(minted)
                verdictMemberKeys.put(minted)
                continue
            }
            val merged = mergeDuelVerdict(existing, axis.id, members, memberKey, shape, kind)
            if (merged != existing) updateCount++ else unchangedCount++
            // **갱신도 색인에 되싣는다** — 짝 가져오기가 그렇게 한다(B-233의 규약은 *쓴 행을
            // 그대로 넘긴다*이지 *새 행만*이 아니다). 이 범주는 **병합이 키를 바꾼다**
            // (`memberKey`·`axisId`) — 형제인 '대결 기록'은 병합이 코드를 건드리지 않아 되싣지
            // 않아도 답이 같지만, 여기서 빠뜨리면 **구성원을 바꾼 앞 행을 뒷 행이 못 찾아**
            // 미리보기만 '신규'로 세고 가져오기는 유니크 색인에 걸린다.
            verdictCodes.put(merged)
            verdictMemberKeys.put(merged)
        }
        reportProgress(onProgress, "대결 상성 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("duelVerdicts", label, inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeFieldValueLibrary(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = fieldValueLibrarySpec()
        val label = "필드 데이터"
        val existingEntries = db.fieldValueEntryDao().getAllList()
        val existingTotal = existingEntries.size
        val sheet = sheetForAnalysis(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("fieldValueLibrary", label, 0, 0, 0, 0, existingTotal)
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("fieldValueLibrary", label, 0, 0, 0, 0, existingTotal)

        val cols = resolveHeaderColumns(headerRow)
        val universeCol = cols["세계관"] ?: 0
        val keyCol = cols["필드키"] ?: 1
        val entityCol = cols["대상"] ?: -1
        // 가져오기와 **같은 판정**이다(R-33) — '값' 열이 없으면 그쪽이 시트를 건너뛰므로
        // 여기서 위치 폴백으로 세면 미리보기만 있지도 않을 건수를 말한다.
        val valueCol = cols["값"]
            ?: return CategoryAnalysis("fieldValueLibrary", label, 0, 0, 0, 0, existingTotal)
        val labelCol = cols["표시라벨"] ?: -1
        // 옛 머리('별칭(콤마구분)')는 [ExcelHeaderAliases]가 이 이름으로 접어 주므로 여기서
        // 다시 묻지 않는다. 종전의 `?: cols["별칭"]`은 **닿을 수 없는 가지였다** —
        // 맨 '별칭'은 별칭 표가 이미 '이명'으로 가져가서 이 이름으로는 결코 들어오지 않는다
        // (그 열은 '인식하지 못해 무시했습니다'로 보고된다 — 조용히 엉뚱한 열에 붙는 것보다 낫다).
        val aliasCol = cols[FieldValueSheetMapper.ALIAS_HEADER] ?: -1
        val categoryCol = cols["카테고리"] ?: -1
        val descCol = cols["설명"] ?: -1
        val hiddenCol = cols["숨김"] ?: -1
        val codeCol = cols["코드"] ?: -1
        val sourceCol = cols["출처"] ?: -1

        // 실제 가져오기와 같은 경로로 필드를 찾는다: 세계관명 → 필드(키, 대상). 행마다 쿼리하지
        // 않으려고 한 번에 읽어 (세계관, 키, 대상)으로 색인할 뿐, getFieldByKey와 같은 결과다.
        val universesByName = analysisUniverses().associateBy { it.name }
        // 필드도 **DB의 것 + 이 파일이 만들 것**이다(B-254와 같은 근거) — 짝인
        // `importFieldValueLibrary`는 `importFieldDefinitions`가 **먼저** 심어 DAO 조회로 답이
        // 맞는다. 종전에는 여기만 DB 스냅샷이라, 빈 DB 복원에서 이 시트의 행이 **전부**
        // `fd == null`로 떨어져 '신규'로 세어졌다 — 같은 (필드, 값)을 두 번 적은 파일에서
        // 가져오기는 *신규 1 + 갱신 1*을 하는데 미리보기는 *신규 2*라 말한다(B-233의 그 모양).
        // 겹 처리도 함께 옳아진다: `associateBy`는 나중 것을 남기는데 짝 조회는 `LIMIT 1`이라
        // **먼저 실린 것**이 답이고, 이 색인이 그 순서를 지킨다.
        val fieldDefs = analysisFieldIndex()
        // 가져오기가 굴리는 것과 **같은 형제 목록**이다(B-233) — 가져오기는 넣은 엔트리를 즉시
        // `siblings`에 더하고 갱신한 엔트리를 갈아 끼운다. 미리보기가 읽기 전용 스냅샷을 쓰면
        // 같은 (필드, 값)을 두 번 적은 파일이 '신규 2'가 되는데 가져오기는 둘째를 첫째와 잇는다.
        // 형제 목록은 값 충돌·별칭 충돌 판정의 재료이기도 해서, 갈리면 '건너뜀'도 함께 갈린다.
        val entriesByField = HashMap<Long, MutableList<com.novelcharacter.app.data.model.FieldValueEntry>>()
        for (e in existingEntries) entriesByField.getOrPut(e.fieldDefinitionId) { mutableListOf() }.add(e)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (i in dataRows(sheet, headerRow)) {
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
                fieldDefs.find(null, fieldKey, imported.entityType)
            } else {
                universe?.let { fieldDefs.find(it.id, fieldKey, imported.entityType) }
            }
            if (fd == null) { newCount++; continue }

            // '동일'은 매칭 키가 아니라 **가져오기가 실제로 쓰는 값 전체**로 가른다(B-87).
            // 건너뛸 행(값 충돌 등)은 바뀌는 것이 없으므로 어느 쪽으로도 세지 않는다 —
            // 세력 소속 분석이 해석 불가 행을 세지 않는 것과 같은 처분이다.
            val siblings = entriesByField.getOrPut(fd.id) { mutableListOf() }
            val existing = FieldValueSheetMapper.match(siblings, imported)
            // **한 번만 병합한다** — 판정과 등재가 같은 결과를 쓴다(두 번 부르면 형제 전부의
            // 별칭으로 집합을 짓는 O(형제) 작업이 그대로 두 배가 된다).
            val outcome = FieldValueSheetMapper.mergeRow(existing, fd.id, imported, siblings)
            val effect = FieldValueSheetMapper.effectOf(existing, outcome)
            when (effect) {
                FieldValueSheetMapper.RowEffect.NEW -> newCount++
                FieldValueSheetMapper.RowEffect.UPDATED -> updateCount++
                FieldValueSheetMapper.RowEffect.UNCHANGED -> unchangedCount++
                FieldValueSheetMapper.RowEffect.SKIPPED -> {}
            }
            // 가져오기가 형제 목록을 옮기는 자리와 **같은 갈래**다(B-233): 건너뛴 행은 아무것도
            // 쓰지 않으므로 목록도 그대로다. 값을 만드는 것도 [FieldValueSheetMapper.mergeRow]
            // 하나라 미리보기가 제 손으로 짓지 않는다 — 그것이 R-33 셋째 짝의 자리다.
            if (effect != FieldValueSheetMapper.RowEffect.SKIPPED) {
                outcome.entry?.let { merged ->
                    if (existing == null) siblings.add(merged.copy(id = previewIds.mint()))
                    else {
                        siblings.removeAll { it.id == merged.id }
                        siblings.add(merged)
                    }
                }
            }
        }
        reportProgress(onProgress, label, sheet.lastRowNum, totalRows)
        return CategoryAnalysis("fieldValueLibrary", label, inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    // 세는 자리 없음(행을 먼저 모아 `sheetRows.size`로 세므로 *세기 전에 버리는* 자리가 없다 —
    //   접혀 밀린 행과 파일을 못 찾은 행은 그 뒤에 skippedCount로 센다. B-233이 정한 모양)
    private suspend fun analyzeImageMeta(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = imageMetaSpec()
        val label = "이미지 태그·링크"
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236) — 종전에는 총계로 표를 통째로
        // 읽고도 **행마다 다시** `getByPath`를 물었다.
        val allImageMeta = db.imageMetaDao().getAllList()
        val existingTotal = allImageMeta.size
        val sheet = sheetForAnalysis(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("imageMeta", label, 0, 0, 0, 0, existingTotal)
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("imageMeta", label, 0, 0, 0, 0, existingTotal)

        // **위치 폴백 금지 — 가져오기와 같은 규칙이다.** 종전에는 분석만 `?: 1`·`?: 2`였고,
        // 그래서 '태그' 열을 지운 시트에서 분석은 1번 열(=링크그룹)을 태그로 읽어 '변경'이라
        // 말하는데 가져오기는 태그를 손대지 않았다.
        val c = ImageMetaCols(resolveHeaderColumns(headerRow))

        // 리맵을 접는 것도 가져오기와 **같은 함수**다(R-33 · B-233 콜드 검토). 손으로 지으면
        // 그 규칙이 갈린다 — 실제로 갈려 있었다: 이쪽은 `HashMap` + last-wins라 **basename이
        // 겹칠 때 가져오기와 다른 원본을 골랐고, 그나마 순서가 결정적이지도 않았다.**
        // 단일 소스는 **원경로 사전순 first-wins**로 고른다(복원마다 결과가 흔들리지 않게).
        // 경고는 버린다 — 분석에는 경고를 낼 자리가 없다(`result = null` 규약과 같다).
        val remapByBasename = ImageMetaRowResolver.buildRemapByBasename(imagePathRemap).byBasename
        val filesDir = appContext?.filesDir
        // `getByPath`(LIMIT 1)의 자리 — id 오름차순이 그 답의 순서다.
        val metaByPath = ImportLookupIndex<String, com.novelcharacter.app.data.model.ImageMeta>(
            idOf = { it.id }, keyOf = { it.path }
        )
        metaByPath.load(allImageMeta.sortedBy { it.id })
        // 태그는 **열이 있을 때만** 읽는다 — 없으면 비교 대상이 아니라 조회도 낭비다
        // (행마다 `getTagsByImageList`를 묻던 자리를 표 한 번 읽기로 내린다).
        val tagsByImage: Map<Long, Set<String>> =
            if (c.tag >= 0) db.imageTagDao().getAllList().groupBy({ it.imageId }, { it.tag })
                .mapValues { (_, v) -> v.toSet() }
            else emptyMap()

        // **행을 경로로 접는 것도 가져오기와 같은 함수다**(R-33 · B-233 콜드 검토).
        // `plan`은 같은 경로를 든 행을 **하나로 접고**(마지막 행 우선 + 고지) 해석되지 않은
        // 파일명을 따로 모은다. 종전에는 미리보기만 행마다 세어, 같은 그림을 두 번 적은 파일에서
        // **가져오기가 한 번 하는 일을 '신규 2'로 예고했다** — 이 범주는 등재의 census 밖이었다
        // (그 census는 `existing == null → newCount++` 모양을 셌는데 이 자리의 갈림은
        // *등재하지 않는 것*이 아니라 **접지 않는 것**이라 모양이 다르다).
        // **데이터 행의 시작은 헤더가 정한다**(B-231 ⓑ) — `dataRows` 한 통로를 지난다.
        // 종전에는 이 자리와 짝인 가져오기만 1행 고정이라, 표 위에 제목 줄을 얹으면 헤더 행이
        // 데이터 행으로 세어졌다('파일명'이라는 글자가 있어 빈칸 가드에 안 걸린다) → 있지도 않은
        // '건너뜀 1'과 "파일을 찾을 수 없어…" 경고가 붙어 없는 유실을 쫓게 했다.
        val sheetRows = dataRows(sheet, headerRow).mapNotNull { i ->
            val row = sheet.getRow(i) ?: return@mapNotNull null
            val fileName = getCellString(row, c.file)
            if (fileName.isBlank()) null else i to fileName
        }
        val plan = ImageMetaRowResolver.plan(sheetRows, remapByBasename) { fileName ->
            filesDir?.let { dir -> java.io.File(dir, fileName).takeIf { it.exists() }?.absolutePath }
        }

        // **`inBackup`은 접기 전이다** — 그 값의 뜻이 *파일이 이 시트에 적어 둔 행*이라
        // 사용자가 파일의 행 수와 곧장 견줄 수 있어야 한다(B-237이 못박은 정의).
        // **실행되지 않는 행은 '건너뜀'으로 센다** — 접혀 밀린 앞 행(마지막 행 우선)과
        // 파일을 찾지 못한 행 둘 다다. 그래야 `inBackup = new + update + unchanged + skipped`가
        // 성립한다. 종전에는 미해석 행이 `inBackup`에만 들고 **어디에도 안 세어져** 그 항등식이
        // 깨져 있었고, 검사 ⑥이 `inBackup++` **앞의** 가드만 보므로 그 사각에 있던 자리다.
        val inBackup = sheetRows.size
        val skippedCount = sheetRows.size - plan.rows.size
        var newCount = 0; var updateCount = 0; var unchangedCount = 0
        for (planned in plan.rows) {
            // `plan.rows`의 `rowIndex`는 **마지막 행**이다(접기가 마지막 행 우선이므로) —
            // 그래서 여기서 읽는 셀이 가져오기가 실제로 적용할 값과 같다.
            // 아래 `?: continue`는 방어일 뿐 걸리지 않는다: `sheetRows`가 이미
            // `sheet.getRow(i)`가 null이 아닌 행만 골라 만든 것이다(가져오기 쪽과 같은 모양).
            val row = sheet.getRow(planned.rowIndex) ?: continue
            val r = readImageMetaRow(row, c, result = null)
            val existing = metaByPath.first(planned.path)
            if (existing == null) { newCount++; continue }
            val current = ImageMetaState(
                tags = if (r.hasTagCol) tagsByImage[existing.id].orEmpty() else emptySet(),
                linkGroupId = existing.linkGroupId,
                detachedAt = existing.detachedAt
            )
            if (mergeImageMetaState(current, r) != current) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "이미지 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("imageMeta", label, inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeUniverses(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = universeSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        // DB 전용 허용(현재 DB 총계 — *지금 몇 개 있는가*라, 이 파일이 만들 것을 더하면 뜻이 깨진다)
        val existingTotal = db.universeDao().getAllUniversesList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)

        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("universes", "세계관", 0, 0, 0, 0, existingTotal)

        val c = UniverseCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val now = System.currentTimeMillis()

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            // 읽기도 가져오기와 **같은 함수**다 — 비교식만 맞추고 리더를 각자 두면
            // 같은 결함이 한 겹 아래에서 되살아난다(설계 1-1).
            val r = readUniverseRow(row, c, "세계관 행 ${excelRow(i)}", result = null)
            if (r.name.isBlank()) {
                // 필수 칸만 빈 행은 '건너뜀'으로 센다 — 외부 편집이 상하게 한 행이다.
                // **완전히 빈 행(표 아래 여백)은 침묵한다**([rowCarriesValue] · skippedCount 계약).
                if (rowCarriesValue(row)) { inBackup++; skippedCount++ }
                continue
            }
            inBackup++

            // 매칭도 가져오기와 같다: 코드가 있으나 DB에 없으면 **이름으로 폴백**한다.
            // 종전에는 그 행을 '신규'로 셌으나 가져오기는 기존 세계관을 덮어쓴다.
            val existing = universeByCode(r.code) ?: universeByName(r.name)
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만든 세계관 — 뒤 행이 이것과 매칭된다(가져오기와 같다, B-233).
                //
                // **코드 칸이 빈 행은 코드도 비워 등재한다.** 가져오기는 그 자리에 임의 코드를
                // 발급하는데 미리보기가 그 값을 알아맞힐 길이 없고, 알아맞힐 필요도 없다 —
                // *발급된 코드로 뒷 행이 이 항목을 찾는 일은 없기 때문이다*(파일에 적혀 있지
                // 않은 코드다). 자리표시자를 넣으면 되레 해롭다: 코드 색인이 빈 코드를 키에서
                // 빼도록 지어져 있어(`takeIf { isNotBlank() }`) 빈 값은 아무 통도 만들지 않는데,
                // 자리표시자는 **모든 신규 행이 한 통에 쌓이는 키**가 된다.
                // 코드 없이 같은 이름을 두 번 적은 파일은 그대로 **이름**으로 잡힌다.
                val created = newUniverseFrom(r, r.code, i, now)
                    .copy(id = previewIds.mint())
                rememberUniverse(created)
                // **캐릭터 시트를 훑는 자리도 이것을 봐야 한다**(B-254) — 그쪽은 이 함수 밖에서
                // 돌므로 색인이 아니라 목록으로 넘긴다(위 [analysisCreatedUniverses]).
                analysisCreatedUniverses.add(created)
                continue
            }
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
        return CategoryAnalysis("universes", "세계관", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeNovels(
        workbook: Workbook,
        onProgress: (ImportProgress) -> Unit,
        totalRows: Int,
        /** 작품 시트의 '필드:' 열이 여기 실린다 (B-187) — 근거는 `analyzeCharacters`의 같은 인자. */
        fieldValues: FieldValueScan? = null
    ): CategoryAnalysis {
        val spec = novelSpec(emptyList())
        val sheet = sheetForAnalysis(workbook, spec)
        val existingTotal = db.novelDao().getAllNovelsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)

        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("novels", "작품", 0, 0, 0, 0, existingTotal)

        val c = NovelCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val now = System.currentTimeMillis()

        // 필드 열 수집도 가져오기와 **같은 재료·같은 해석기**다(`EntityFieldHeaders` → `EntityFieldColumnResolver`).
        val novelFields = if (fieldValues == null) emptyList() else analysisEntityFields(FieldDefinition.ENTITY_NOVEL)
        val universeIdsByName = if (fieldValues == null) emptyMap() else
            analysisUniverses().associate { it.name to it.id }
        val fieldColumns = if (fieldValues == null) emptyList()
        else analysisEntityFieldColumns(headerRow, novelFields)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readNovelRow(row, c, "작품 행 ${excelRow(i)}", result = null)
            if (r.title.isBlank()) {
                // 필수 칸만 빈 행은 '건너뜀'으로 센다 — 외부 편집이 상하게 한 행이다.
                // **완전히 빈 행(표 아래 여백)은 침묵한다**([rowCarriesValue] · skippedCount 계약).
                if (rowCarriesValue(row)) { inBackup++; skippedCount++ }
                continue
            }
            inBackup++

            // 세계관 해석도 가져오기와 같다 — 코드 우선, 이름 폴백.
            val universeId = universeByCodeOrName(r.universeCode, r.universeName)?.id
            // 매칭도 가져오기와 같다: 코드가 있으나 DB에 없으면 제목+세계관으로 폴백한다.
            // **폴백 갈래가 한 함수가 되며 함정 하나가 사라졌다**(B-236) — 종전에는 세계관 유무로
            // 갈린 `if/else`를 엘비스 오른쪽에 두느라 **괄호가 필수**였고, 괄호를 빠뜨리면 엘비스가
            // `else` 가지에만 붙어 코드 미해석 시 제목 폴백이 조용히 죽었다.
            val existing = novelByCode(r.code) ?: novelByTitle(r.title, universeId)
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만든 작품 — 뒤 행도, 뒤 시트(캐릭터)도 이것과 매칭된다(B-233).
                val created = newNovelFrom(r, r.code, universeId, i, now)
                    .copy(id = previewIds.mint())
                rememberNovel(created)
                countEntityFieldColumns(
                    row, fieldColumns, created.id, universeId, fieldValues, novelFields, universeIdsByName
                )
                continue
            }
            val merged = mergeNovel(
                existing, r,
                effectiveUniverseId = effectiveNovelUniverseId(existing, r, universeId),
                imageCharacterId = r.imageCharCode?.let { characterByCode(it)?.id }
            )
            if (merged != existing) updateCount++ else unchangedCount++
            // 필드 열은 **소속이 확정된 뒤**에 센다 — 구역 해석이 그 값에 걸린다(가져오기와 같다).
            countEntityFieldColumns(
                row, fieldColumns, merged.id,
                effectiveNovelUniverseId(existing, r, universeId), fieldValues,
                novelFields, universeIdsByName
            )
        }
        reportProgress(onProgress, "작품 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("novels", "작품", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeFieldDefinitions(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = fieldDefinitionSpec(emptyList())
        val sheet = sheetForAnalysis(workbook, spec)
        // 캐릭터+사건 필드 모두 시트에 실리므로 기존 총계도 전 타입 기준 (프리뷰 정확성)
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236).
        val allFieldDefs = db.fieldDefinitionDao().getAllFieldsAllTypes()
        val existingTotal = allFieldDefs.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("fieldDefinitions", "필드 정의", 0, 0, 0, 0, existingTotal)
        val c = FieldDefCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        // 행마다 `getFieldByKey`·`getGlobalFieldByKey`를 묻던 자리 (B-236).
        val fieldDefs = FieldDefinitionIndexes(allFieldDefs)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readFieldDefRow(row, c, "필드 행 ${excelRow(i)}", result = null)
            // 세계관·코드 둘 다 빈 행 = 전역 구역(B-119 확장) — 가져오기와 **같은 판정**(R-33).
            val globalScope = FieldScopeCell.isGlobal(r.universeName, r.universeCode)
            if (r.universeName.isBlank() && !globalScope) continue
            if (r.key.isBlank()) continue
            inBackup++

            val universe = if (globalScope) null else {
                val found = universeByCode(r.universeCode) ?: universeByName(r.universeName)
                if (found == null) {
                    // B-102 ⓑ: '세계관'을 함께 가져오면 그것이 먼저 생기므로 '신규'가 맞고,
                    // 빼놓았으면 가져오기가 이 행을 경고와 함께 **건너뛴다**.
                    if (options.universes) newCount++ else skippedCount++
                    continue
                }
                found
            }

            // **타입 게이트도 미리보기에 있어야 한다 (B-256).** 짝 가져오기는 타입이 비었거나
            // 아는 이름이 아니면 그 행을 **쓰지 않고** 오류와 함께 건너뛴다. 여기 없으면
            // 미리보기가 그 행을 '신규'로 세어 **예고한 수가 실제와 갈리고**, 더 나쁘게는
            // 아래에서 `fieldDefs.remember`로 *생기지도 않을 정의*를 색인에 심어
            // **그 필드를 가리키는 값 시트 행들까지 연쇄로 잘못 센다**(B-187이 겪은 모양).
            // 판정 문구는 짝과 글자 그대로 같아야 한다 — 갈리면 이 자리가 다시 벌어진다.
            if (r.type.isBlank() || FieldType.fromName(r.type) == null) {
                skippedCount++
                continue
            }

            val existing = fieldDefs.find(universe?.id, r.key, r.entityType)
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만든 필드 정의 — 같은 (구역, 키, 대상)을 든 뒷 행이 이것과
                // 매칭된다(가져오기는 직전 insert가 같은 트랜잭션에서 이미 보인다, B-233).
                val created = newFieldDefinitionFrom(
                    r, universe?.id, resolveFieldDefConfig(universe?.id, i, r, null, result = null), i
                ).copy(id = previewIds.mint())
                fieldDefs.remember(created)
                // **필드값 범주 셋도 이것을 봐야 한다**(B-187) — 그쪽은 이 함수 밖에서 돌므로
                // 색인이 아니라 목록으로 넘긴다(위 [analysisCreatedFields]).
                analysisCreatedFields.add(created)
                continue
            }
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

    // 세는 자리 없음(행을 직접 세지 않는다 — 세계관별 `analyzeCharacterSheet`의 결과를 합칠
    //   뿐이고 행 가드는 그쪽이 든다. 그 함수는 이 축이 본다)
    private suspend fun analyzeCharacters(
        workbook: Workbook,
        onProgress: (ImportProgress) -> Unit,
        totalRows: Int,
        /**
         * 캐릭터 시트의 **필드 열**이 여기 실린다 (B-187) — `null`이면 세지 않는다.
         *
         * **왜 이 함수 안에서 세는가:** 값이 어느 캐릭터에 붙는지는 그 행의 매칭 사다리가 정하고,
         * 그 사다리는 여기서 한 번 돌면서 색인을 **바꾼다**(이 파일이 만들 캐릭터를 등재한다).
         * 밖에서 한 번 더 돌면 **같은 사다리를 손으로 두 벌 적는 것**일 뿐 아니라, 두 번째 통과는
         * 첫 통과가 남긴 색인 위에서 돌아 *다른 캐릭터에* 값을 붙인다(R-33이 막는 그 모양).
         */
        fieldValues: FieldValueScan? = null
    ): CharacterAnalysisResult {
        val existingTotal = db.characterDao().getAllCharactersList().size
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        val allConflicts = mutableListOf<CharacterConflict>()

        // 세계관별 캐릭터 시트 분석 — **DB의 것 + 이 파일이 만들 것**(B-254).
        //
        // 짝인 [importCharacterSheets]는 같은 질의 하나만 쓰는데, 그때는 [importUniverses]가
        // 먼저 심어 목록이 차 있다. 미리보기는 쓰지 않으므로 **빈 DB 복원에서 이 목록이 비어
        // 루프가 한 번도 돌지 않았다.** 정렬 키를 DAO와 같게 두는 것도 그 짝 맞추기다
        // (`ORDER BY displayOrder ASC, createdAt DESC`) — 계수 자체는 순서에 무관하지만,
        // 같은 캐릭터가 두 시트에 있을 때 *어느 시트 행이 신규인가*가 순서로 갈린다.
        val universes = analysisUniverses()
            .sortedWith(compareBy<Universe> { it.displayOrder }.thenByDescending { it.createdAt })
        // 미리보기도 본 임포트와 같은 배정을 봐야 한다(R-33) — [UniverseSheetFinder]가 계획
        // 우선 + 소비 추적으로 판정한다. 같은 시트를 두 번 세면 건수가 부풀고 충돌 항목이
        // 중복되며(conflictKey가 "$sheetLabel:$i"라 양쪽이 같은 키를 만든다), 소비가 없으면
        // 동명 세계관의 `(2)` 시트가 예고에서 통째로 빠진다. 소비 집합의 수명은 이 분석
        // 한 번이다(R-33 ⑦) — 아래 미분류 조회까지 같은 집합을 본다.
        val analyzedSheetNames = mutableSetOf<String>()
        val sheetFinder = UniverseSheetFinder(workbook, universes, analyzedSheetNames)
        for (universe in universes) {
            val sheet = sheetFinder.find(universe) ?: continue
            val headerRow = locateHeaderRow(sheet, "이름") ?: continue
            val result = analyzeCharacterSheet(sheet, headerRow, universe.name, universe.id, fieldValues)
            inBackup += result.first; newCount += result.second; updateCount += result.third
            skippedCount += result.sixth
            // 건너뛴 행은 '변경 없음'이 아니다 — 빼지 않으면 상한 행이 정상 행으로 세어진다.
            unchangedCount += (result.first - result.second - result.third - result.fifth - result.sixth)
            allConflicts.addAll(result.fourth)
        }

        // 미분류 캐릭터 분석
        val unclSheet = findUnclassifiedSheet(workbook, analyzedSheetNames)
        if (unclSheet != null) {
            // 세계관 시트와 같은 통로다 — `headerRowOrFirst` + `isValidHeader`는 이것과 같은
            // 답을 내면서 두 줄을 쓴다(찾지 못하면 0행이 돌아오고 그 행은 반드시 검증에 걸린다).
            val headerRow = locateHeaderRow(unclSheet, "이름")
            if (headerRow != null) {
                val result = analyzeCharacterSheet(
                    unclSheet, headerRow, UNCLASSIFIED_SHEET_NAME, universeId = null, fieldValues = fieldValues
                )
                inBackup += result.first; newCount += result.second; updateCount += result.third
                skippedCount += result.sixth
                unchangedCount += (result.first - result.second - result.third - result.fifth - result.sixth)
                allConflicts.addAll(result.fourth)
            }
        }

        reportProgress(onProgress, "캐릭터 분석", 0, totalRows)
        val category = CategoryAnalysis("characters", "캐릭터", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
        return CharacterAnalysisResult(category, allConflicts)
    }

    /** first=inBackup, second=newCount, third=updateCount, fourth=conflicts, fifth=conflictCount, sixth=skipped */
    private data class SheetAnalysis(
        val first: Int, val second: Int, val third: Int,
        val fourth: List<CharacterConflict>, val fifth: Int = 0,
        /** 필수 칸('이름')만 빈 행 — [CategoryAnalysis.skippedCount]로 올라간다 */
        val sixth: Int = 0
    )

    private suspend fun analyzeCharacterSheet(
        sheet: Sheet,
        headerRow: Row,
        sheetLabel: String,
        universeId: Long?,
        fieldValues: FieldValueScan? = null
    ): SheetAnalysis {
        // 실제 임포트와 동일한 고정 열 우선 해석 — 미리보기 건수가 임포트 결과와 어긋나지 않게 한다
        val cols = resolveHeaderColumns(headerRow, reservedHeaders = CHARACTER_FIXED_HEADERS)
        val c = CharacterCols(cols)
        val now = System.currentTimeMillis()

        // 필드 열 계획도 가져오기와 **같은 함수**다([CharacterFieldColumns] · B-187) — 고정 열
        // 인덱스를 짓는 식까지 `importCharacterRows`에서 그대로 옮겨 온다(손으로 나열하면 갈린다).
        // 수식이 참조할 수 있는 정의들 — 계산 열 판정([CalculatedCellEcho])이 이 목록을 쓴다.
        // 열 계획과 **같은 목록**이어야 한다(두 번 조회하면 그 사이에 갈릴 자리다).
        val planFields = analysisCharacterFields(universeId)
        val columnPlan = if (fieldValues == null) emptyMap() else CharacterFieldColumns.plan(
            readHeaderCells(headerRow),
            planFields,
            // 기대 헤더 표는 **열을 그린 그 함수**가 낸다 — 미리보기와 쓰기가 같은 표를 본다(R-33).
            CharacterFieldHeaders.expectedHeaders(planFields),
            (CHARACTER_FIXED_HEADERS.mapNotNull { cols[it] } + c.name).filter { it >= 0 }.toSet(),
            CHARACTER_FIXED_HEADERS,
            hasUniverse = universeId != null,
            multiSuffix = EntityFieldHeaders.MULTI_SUFFIX
        )
        // 계산 열이 이 계획에 하나라도 있는가 — **행 밖에서 한 번** 잰다(가져오기 쪽과 같은 결).
        val planHasCalculatedColumn = columnPlan.values.any {
            it is ColumnFieldOutcome.Matched && it.field.fieldType == FieldType.CALCULATED
        }
        // 자동 생성될 필드의 자리 — **열마다 하나**이고 그 열의 모든 행이 같은 필드를 가리킨다.
        // 아직 없는 필드라 실존 id가 없고, 미리보기 id 공간에서 하나씩 꺼내 쓴다(음수라 기존
        // 값과 짝지어질 수 없다 = 이 열의 값은 전부 신규다. `util/PreviewCreations.kt`).
        val autoFieldIds: Map<Int, Long> = columnPlan.entries
            .filter { it.value is ColumnFieldOutcome.AutoCreate }
            .associate { it.key to previewIds.mint() }

        /**
         * 이 행의 필드 열을 센다 — **캐릭터가 확정된 뒤**에 부른다.
         * [charId]가 null이면 확정되지 않은 행(동명이인 충돌)이라 값이 든 칸만 '건너뜀'이다:
         * 사용자가 충돌을 어떻게 정하느냐에 따라 결과가 갈리므로 약속하지 않는다.
         */
        fun countFieldColumns(row: Row, charId: Long?, createdHere: Boolean = false) {
            val scan = fieldValues ?: return
            // **세계관을 옮기는 캐릭터도 미정이다**(B-253) — 가져오기가 이 칸들을 쓰기는 하지만
            // 그 직후 값을 새 세계관 필드로 전량 재매핑하므로, `(소유자, 필드)` 짝 위에서 내린
            // 칸 단위 처분이 그대로 서지 못한다. 확정되지 않은 행과 **같은 처리**로 모은다.
            val decided: Long? =
                if (charId != null && charId !in analysisUniverseMovedCharacterIds) charId else null
            // 계산 열 판정의 재료 — **열 루프에 들어가기 전에 굳힌다**(가져오기 쪽과 같은
            // 이유·같은 순서. 종전 `by lazy`는 같은 루프의 `scan.count`가 이미 갱신한 겹을
            // 읽어, 미리보기와 가져오기가 서로 다른 재료를 볼 수도 있었다 — R-33의 결).
            //
            // **이 행이 캐릭터를 만드는 경우는 재료를 행에서 짓는다** — 가져오기가 같은
            // 자리에서 하는 것과 글자 그대로 같다(R-33). 새 소유자에게는 저장된 값이 없어
            // 재료가 통째로 비고, 그러면 수식이 재료 없이 평가돼 셀과 어긋난다.
            //
            // **`decided < 0`(미리보기 임시 id)으로 추론하지 않는다** — 같은 파일의 뒤 행이
            // 앞 행이 만든 캐릭터를 가리킬 때 가져오기 쪽은 그것을 *기존*으로 보고 장부값을
            // 쓰므로, 음수 id로 가르면 그 자리에서 미리보기와 가져오기가 갈린다.
            val storedByKey: Map<String, String> = when {
                !planHasCalculatedColumn -> emptyMap()
                createdHere -> CalculatedCellEcho.materialsFromRow(
                    columnPlan.mapNotNull { (col, outcome) ->
                        (outcome as? ColumnFieldOutcome.Matched)?.let { it.field to getCellString(row, col) }
                    }
                )
                decided == null -> emptyMap()
                else -> planFields.asSequence()
                    .mapNotNull { f -> scan.stored(decided, f.id)?.let { f.key to it } }
                    .toMap()
            }
            for ((col, outcome) in columnPlan) {
                val cellValue = getCellString(row, col)
                when (outcome) {
                    is ColumnFieldOutcome.Matched -> {
                        // 계산 필드는 저장하지 않는다(F4) — 가져오기가 `continue`하는 그 자리다.
                        if (outcome.field.fieldType == FieldType.CALCULATED) {
                            // **앱 자신이 적어 낸 산출값은 세지 않는다** — 그것까지 세면
                            // 한 글자도 안 고친 파일에서도 '건너뜀'에 숫자가 붙는다
                            // (`FieldValueScan.skip`의 KDoc이 스스로 금지한 상태다).
                            if (cellValue.isNotBlank() &&
                                !CalculatedCellEcho.isAppOutput(
                                    outcome.field, cellValue, planFields, storedByKey
                                )
                            ) scan.skip()
                        } else if (decided == null) {
                            if (cellValue.isNotBlank()) scan.skip()
                        } else {
                            scan.count(decided, outcome.field.id, cellValue)
                        }
                    }
                    // 가져오기가 필드를 새로 만들어 붙이므로 기존 값이 있을 수 없다 — 값이 든 칸은 신규다.
                    // 아직 없는 필드라 id가 없고, 미리보기 id 공간에서 하나 꺼내 그 열에 고정한다.
                    is ColumnFieldOutcome.AutoCreate -> {
                        if (decided == null) { if (cellValue.isNotBlank()) scan.skip() }
                        else scan.count(decided, autoFieldIds.getValue(col), cellValue)
                    }
                    // 열이 통째로 버려지는 자리 — 값이 적혀 있으면 그것이 반영되지 않는다는 사실이 중요하다.
                    is ColumnFieldOutcome.Ambiguous, is ColumnFieldOutcome.Unresolved,
                    is ColumnFieldOutcome.Duplicate -> if (cellValue.isNotBlank()) scan.skip()
                }
            }
        }

        var inBackup = 0; var newCount = 0; var updateCount = 0; var conflictCount = 0; var skippedCount = 0
        val conflicts = mutableListOf<CharacterConflict>()

        // 작품 제목 해석도 가져오기와 **같은 사다리**다([resolveNovelId]의 읽기 갈래 — 시트의
        // 세계관 스코프 우선 → 타 세계관 동일 제목. 종전의 전 목록 first-match는 동명 작품에서
        // 가져오기와 다른 작품을 골랐다, B-217). 어디에도 없으면 가져오기는 **새 작품을 만들어
        // 배정**하므로, 그 행은 기존의 어떤 novelId와도 다른 값으로 세어야 같은 답이 된다 —
        // 미리보기는 쓰지 않으므로 실존하지 않는 표지값이 그 자리다.
        suspend fun analysisNovelIdByTitle(novelTitle: String): Long? {
            if (novelTitle.isBlank()) return null
            return novelByTitle(novelTitle, universeId)?.id
                ?: novelByTitleAnyUniverse(novelTitle)?.id
                ?: ANALYSIS_CREATED_NOVEL_ID
        }

        // 미리보기가 예고하는 '변경'을 판정하는 자리 — 가져오기와 **같은 함수**를 쓴다(규약 R-33).
        // 종전에는 이름·메모·이명 셋만 봤다: 성·이름(First)·작품·정렬순서·고정·생성일·
        // 이미지경로·대표이미지를 고쳐도 '변경 없음'이라 말했다.
        suspend fun countAgainst(existing: Character, r: CharacterRowValues, rowIndex: Int): Long {
            val resolvedNovelId = novelByCode(r.novelCode)?.id
                ?: (if (r.novelTitle.isNotBlank()) analysisNovelIdByTitle(r.novelTitle) else null)
            // 가져오기와 같은 정책(R-33): 작품코드가 적혀 있는데 미해석이고 제목도 없으면
            // 배정을 해제하지 않고 기존 값을 유지한다 — 미리보기도 같은 판정으로 세야 한다.
            val novelId = if (resolvedNovelId == null && r.novelCode.isNotBlank() && r.novelTitle.isBlank()) {
                existing.novelId
            } else resolvedNovelId
            // 이 행이 캐릭터의 세계관을 옮기면 그 캐릭터의 필드값 칸은 **약속하지 않는다**
            // (B-253) — 판정은 가져오기와 같은 함수다(R-33). 기록만 하고 처분은
            // `countFieldColumns`와 '캐릭터 필드값' 시트가 읽는다.
            if (universeMoveOf(existing, r.novelColumnsPresent, novelId) != null) {
                analysisUniverseMovedCharacterIds.add(existing.id)
            }
            val merged = mergeCharacter(existing, r, novelId, rowIndex, now, result = null)
            if (merged != existing) updateCount++
            // 갱신된 값도 되돌려 놓는다 — 같은 캐릭터를 가리키는 뒤 행은 이 결과 위에서 판정되고,
            // put이 옛 키를 끊는다(파일 안에서 개명한 뒤 옛 이름 행이 또 나오는 경우).
            rememberCharacter(merged)
            return merged.id
        }

        /** 이 시트가 방금 만들 캐릭터를 등재한다 — 뒤 행·뒤 시트가 이것과 매칭된다(B-233). */
        suspend fun rememberCreated(r: CharacterRowValues, rowIndex: Int): Long {
            val novelId = novelByCode(r.novelCode)?.id
                ?: (if (r.novelTitle.isNotBlank()) analysisNovelIdByTitle(r.novelTitle) else null)
            val created = newCharacterFrom(
                r, r.code,
                // 가져오기와 같다: 작품이 어디에도 없으면 새로 만들어 배정하고, 그 표지값은
                // 실존 id가 아니므로 캐릭터의 소속으로도 그대로 둔다(계수 전용).
                novelId?.takeIf { it != ANALYSIS_CREATED_NOVEL_ID }, rowIndex, now, result = null
            ).copy(id = previewIds.mint())
            rememberCharacter(created)
            return created.id
        }

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readCharacterRow(row, c, "캐릭터 행 ${excelRow(i)}", result = null)
            val name = r.name
            if (name.isBlank()) {
                // 필수 칸만 빈 행은 '건너뜀'으로 센다 — 외부 편집이 상하게 한 행이다.
                // **완전히 빈 행(표 아래 여백)은 침묵한다**([rowCarriesValue] · skippedCount 계약).
                if (rowCarriesValue(row)) { inBackup++; skippedCount++ }
                continue
            }
            inBackup++

            if (r.code.isNotBlank()) {
                // 코드 기반 매칭: 충돌 없음 (코드가 권위적)
                val existing = characterByCode(r.code)
                if (existing != null) {
                    countFieldColumns(row, countAgainst(existing, r, i))
                    continue
                }
                // F1-C: 가져오기는 코드 미해석 시 이름 폴백으로 **기존 캐릭터를 갱신**한다 —
                // 그 행을 '신규'로 세면 두 기기를 병합하는 파일(코드가 전부 상대 기기 것)에서
                // 미리보기가 덮어쓰기 위험을 고지하지 못한다(R-33 — 세계관·작품 분석이
                // 먼저 고친 그 결함이 캐릭터에만 남아 있었다). 사다리도 가져오기와 같다.
                val novelId = novelByCode(r.novelCode)?.id
                    ?: (if (r.novelTitle.isNotBlank()) analysisNovelIdByTitle(r.novelTitle) else null)
                val byName = if (novelId != null) characterByNameAndNovel(name, novelId) else characterByName(name)
                val byNameId = if (byName != null) countAgainst(byName, r, i)
                    else { newCount++; rememberCreated(r, i) }
                countFieldColumns(row, byNameId, createdHere = byName == null)
            } else {
                // 코드 없음: 이름 기반 매칭 — 동명이인 충돌 가능
                val allMatches = charactersByName(name)
                // **이 파일이 방금 만든 것과 실존 캐릭터를 가른다**(B-233). 동명이인 충돌은
                // *실존끼리*의 일이다 — 대화상자가 사용자에게 보여 주고 `selectedExistingId`로
                // 되돌려 받는 것이 실존 id이므로, 여기 미리보기 임시 id가 섞이면 사용자가
                // '기존 갱신'을 골라도 가져오기가 그 id를 못 찾아 조용히 새로 만든다.
                // 이 파일이 만든 것은 가져오기와 **같은 first-match 사다리**로 잡는다.
                // **이름은 `existing…`을 피한다.** R-33 검사 ①은 `existing*.<필드> ==` 꼴을
                // *손으로 짠 필드 비교*로 읽는데, 여기 `.size`는 엔티티의 필드가 아니라 목록의
                // 크기다. 검사를 느슨하게 푸는 대신 이름을 사실대로 적는다 — 이 목록의 성질은
                // *실존한다*가 아니라 **DB에서 왔다**는 것이고, 그것이 미리보기가 만든 것과
                // 가르는 축이다.
                val dbMatches = allMatches.filter { it.id > 0 }
                if (dbMatches.isEmpty()) {
                    val madeByThisFile = allMatches.firstOrNull()
                    val rowCharId = if (madeByThisFile != null) countAgainst(madeByThisFile, r, i)
                        else { newCount++; rememberCreated(r, i) }
                    countFieldColumns(row, rowCharId, createdHere = madeByThisFile == null)
                } else if (dbMatches.size == 1) {
                    countFieldColumns(row, countAgainst(dbMatches[0], r, i))
                } else {
                    // 다중 매칭: 충돌 발생
                    conflicts.add(CharacterConflict(
                        excelRowIndex = i,
                        sheetName = sheetLabel,
                        excelName = name,
                        excelNovelTitle = r.novelTitle.ifBlank { null },
                        existingCharacters = dbMatches
                    ))
                    // 충돌 행은 사용자 결정 전까지 분류 미정 — 별도 카운트
                    conflictCount++
                    // 그 행의 필드값도 미정이다(어느 캐릭터에 붙는지가 결정에 달렸다) — 약속하지 않는다.
                    countFieldColumns(row, charId = null)
                }
            }
        }
        return SheetAnalysis(inBackup, newCount, updateCount, conflicts, conflictCount, skippedCount)
    }

    // ── 필드값 미리보기 (B-187) — 캐릭터·작품·사건이 각각 **독립 범주**다 ──
    //
    // 범주 하나가 **두 경로**에서 값을 받는다: 본 시트의 필드 열(`analyzeCharacterSheet`의
    // 지역 `countFieldColumns`와 아래 `countEntityFieldColumns`)과 오버플로 시트(아래 셋). 계수를 한
    // [FieldValueScan]에 모으는 이유는 그 파일의 KDoc이 든다 — 갈라 두면 두 경로가 서로의
    // 쓰기를 못 보고, 같은 파일이 *신규 2*로 세어진다.
    //
    // **왜 소유 엔티티의 변경에 접지 않는가**(B-187 판정 ⑴): 오버플로 시트의 행은 **본 시트의
    // 행이 아니라서**, 접으면 `inBackup = new + update + unchanged + cleared + skipped`가 깨진다.
    // 게다가 접으려면 차분을 어차피 계산해야 하므로 건수는 이미 손에 있고, 숨기는 것은 순손실이다
    // — 사용자가 알아야 할 것은 *'이 캐릭터가 바뀐다'*가 아니라 *'필드값 40건이 덮인다'*이다.

    /**
     * 이 시트가 볼 수 있는 캐릭터 필드 — DB의 것 **뒤에** 이 파일이 만들 것을 붙인다.
     * 순서가 규약인 이유는 [analysisCreatedFields]가 든다.
     */
    private suspend fun analysisCharacterFields(universeId: Long?): List<FieldDefinition> {
        val existing = when {
            universeId == null -> db.fieldDefinitionDao().getGlobalFieldsList()
            // **이 파일이 만들 세계관**은 DB에 행이 없다(음수 임시 id — B-254). 물어도 빈 목록이
            // 돌아오므로 답은 같고, 묻지 않는 편이 시트마다 왕복 하나를 던다.
            universeId < 0 -> emptyList()
            else -> db.fieldDefinitionDao().getFieldsByUniverseList(universeId)
        }
        if (analysisCreatedFields.isEmpty()) return existing
        return existing + analysisCreatedFields.filter {
            it.universeId == universeId && it.entityType == FieldDefinition.ENTITY_CHARACTER
        }
    }

    /** 오버플로 시트가 필드키로 묻는 색인 — 같은 순서 규약이다(DB가 먼저, 이 파일이 만들 것이 뒤). */
    private suspend fun analysisFieldIndex(): FieldDefinitionIndexes =
        FieldDefinitionIndexes(db.fieldDefinitionDao().getAllFieldsAllTypes())
            .also { index -> analysisCreatedFields.forEach { index.remember(it) } }

    /**
     * '캐릭터 필드값' 시트 — [importCharacterFieldValues]의 짝 (R-33).
     *
     * 해석 순서를 가져오기와 **글자 그대로** 맞춘다: 캐릭터(코드 → 이름) → 구역 → 필드 →
     * 계산 필드 제외 → **본 시트가 이미 처리한 쌍이면 무시** → 값 처분.
     */
    private suspend fun analyzeCharacterFieldValueSheet(workbook: Workbook, scan: FieldValueScan) {
        val spec = characterFieldValueSpec()
        val sheet = sheetForAnalysis(workbook, spec) ?: return
        // 헤더 판정도 가져오기와 같다 — 첫 행이 없거나 첫 열이 틀리면 가져오기는 시트를
        // **통째로** 건너뛴다(`headerRowOrReport`). 미리보기가 그대로 읽으면 엉뚱한 열을 센다.
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return
        val cols = resolveHeaderColumns(headerRow)
        val keyCol = cols["필드키"] ?: -1
        // 가져오기는 이 시트를 통째로 건너뛴다(기존 값 유지) — 셀 하나도 반영되지 않는다.
        if (keyCol < 0) return
        val charCodeCol = cols["캐릭터코드"] ?: 0
        val charNameCol = cols["캐릭터이름"] ?: -1
        val uNameCol = cols["세계관"] ?: -1
        val uCodeCol = cols["세계관코드"] ?: -1
        val entityCol = cols["대상"] ?: -1
        val valueCol = cols["값"] ?: -1

        val fieldDefs = analysisFieldIndex()
        val allUniverses = analysisUniverses()
        val universesByName = allUniverses.associateBy { it.name }
        val universesByCode = allUniverses.associateBy { it.code }

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val charCode = getCellCode(row, charCodeCol, "", result = null)
            val charName = if (charNameCol >= 0) getCellString(row, charNameCol) else ""
            val fieldKey = getCellString(row, keyCol)
            // 완전히 빈 행은 세지 않는다 — 가져오기도 고지 없이 지나간다(R-33 ⑥).
            if (charCode.isBlank() && charName.isBlank() && fieldKey.isBlank()) continue
            val cell = if (valueCol >= 0) getCellString(row, valueCol) else ""

            // 매칭 규약: 코드(안정 식별자) 우선 → 자연키 폴백. 동명이인이면 가져오기가 거부한다.
            var character = characterByCode(charCode)
            if (character == null && charName.isNotBlank()) {
                // **이름은 `byName`을 피한다**(B-233 선례) — R-33 검사 ①은 그 이름의 `.<필드> ==`를
                // *손으로 짠 필드 비교*로 읽는데 여기 `.size`는 목록의 크기다. 검사를 느슨하게
                // 푸는 대신 이름을 사실대로 적는다. 동명이 여럿이면 가져오기가 그 행을 거부한다.
                val nameMatches = charactersByName(charName)
                if (nameMatches.size == 1) character = nameMatches.first()
            }
            if (character == null) { if (cell.isNotBlank()) scan.skip(); continue }
            // **이번 파일이 세계관을 옮긴 캐릭터**의 옛 키 행은 가져오기가 적용하지 않는다 —
            // 방금 재매핑한 값이 되살아나기 때문이다(`universeMovedCharacterIds`의 `continue`).
            // 미리보기도 같은 자리에서 같은 판정을 읽는다 (B-253) — 캐릭터 시트가 먼저 돌아
            // 그때 채워진 집합이다(범주 순서 규약: 본 시트 → 오버플로 시트).
            if (character.id in analysisUniverseMovedCharacterIds) {
                if (cell.isNotBlank()) scan.skip()
                continue
            }

            val uName = if (uNameCol >= 0) getCellString(row, uNameCol) else ""
            val uCode = if (uCodeCol >= 0) getCellCode(row, uCodeCol, "", result = null) else ""
            val globalScope = FieldScopeCell.isGlobal(uName, uCode)
            val universe = if (globalScope) null else {
                uCode.takeIf { it.isNotBlank() }?.let { universesByCode[it] } ?: universesByName[uName]
            }
            if (universe == null && !globalScope) { if (cell.isNotBlank()) scan.skip(); continue }

            val entityType = FieldValueSheetMapper.entityTypeOf(if (entityCol >= 0) getCellString(row, entityCol) else null)
            val fd = fieldDefs.find(universe?.id, fieldKey, entityType)
            // 계산 필드는 저장하지 않는다 — 가져오기와 같은 두 갈래다.
            if (fd == null || fd.fieldType == FieldType.CALCULATED) { if (cell.isNotBlank()) scan.skip(); continue }
            // 캐릭터 시트가 권위인 쌍은 가져오기가 **고지만 하고 아무 일도 하지 않는다** —
            // 그 칸은 이미 본 시트 쪽에서 세었으므로 여기서 또 세면 두 번 세는 것이다.
            if (scan.isOwned(character.id, fd.id)) continue

            scan.count(character.id, fd.id, cell, columnPresent = valueCol >= 0)
        }
    }

    /** '작품 필드값' 시트 — [importNovelFieldValues]의 짝 (R-33). */
    private suspend fun analyzeNovelFieldValueSheet(workbook: Workbook, scan: FieldValueScan) {
        val spec = novelFieldValueSpec()
        val sheet = sheetForAnalysis(workbook, spec) ?: return
        // 헤더 판정도 가져오기와 같다 — 첫 행이 없거나 첫 열이 틀리면 가져오기는 시트를
        // **통째로** 건너뛴다(`headerRowOrReport`). 미리보기가 그대로 읽으면 엉뚱한 열을 센다.
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return
        val cols = resolveHeaderColumns(headerRow)
        val keyCol = cols["필드키"] ?: -1
        if (keyCol < 0) return
        val codeCol = cols["작품코드"] ?: 0
        val titleCol = cols["작품제목"] ?: -1
        val uNameCol = cols["세계관"] ?: -1
        val uCodeCol = cols["세계관코드"] ?: -1
        val valueCol = cols["값"] ?: -1

        val fieldDefs = analysisFieldIndex()
        val allUniverses = analysisUniverses()
        val universesByName = allUniverses.associateBy { it.name }
        val universesByCode = allUniverses.associateBy { it.code }

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val code = getCellCode(row, codeCol, "", result = null)
            val title = if (titleCol >= 0) getCellString(row, titleCol) else ""
            val fieldKey = getCellString(row, keyCol)
            if (code.isBlank() && title.isBlank() && fieldKey.isBlank()) continue
            val cell = if (valueCol >= 0) getCellString(row, valueCol) else ""

            var novel = novelByCode(code)
            if (novel == null && title.isNotBlank()) {
                // 가져오기는 여기서 전 작품 목록을 제목으로 묶어 **유일할 때만** 받는다.
                // 미리보기는 같은 답을 **색인**에서 얻는다(R-53 — 행마다 표를 뜨지 않는다).
                // 그 색인은 *이 파일이 앞서 만들 작품*도 들고 있어(rememberNovel) 답이 같다.
                val titleMatches = novelsByTitleAll(title)
                if (titleMatches.size == 1) novel = titleMatches.first()
            }
            if (novel == null) { if (cell.isNotBlank()) scan.skip(); continue }

            val uName = if (uNameCol >= 0) getCellString(row, uNameCol) else ""
            val uCode = if (uCodeCol >= 0) getCellCode(row, uCodeCol, "", result = null) else ""
            val globalScope = FieldScopeCell.isGlobal(uName, uCode)
            val universe = if (globalScope) null else {
                uCode.takeIf { it.isNotBlank() }?.let { universesByCode[it] } ?: universesByName[uName]
            }
            if (universe == null && !globalScope) { if (cell.isNotBlank()) scan.skip(); continue }

            val fd = fieldDefs.find(universe?.id, fieldKey, FieldDefinition.ENTITY_NOVEL)
            if (fd == null || fd.fieldType == FieldType.CALCULATED) { if (cell.isNotBlank()) scan.skip(); continue }
            if (scan.isOwned(novel.id, fd.id)) continue

            scan.count(novel.id, fd.id, cell, columnPresent = valueCol >= 0)
        }
    }

    /** '사건 필드값' 시트 — [importEventFieldValues]의 짝. **정체는 사건 코드 하나뿐이다**(R-1). */
    private suspend fun analyzeEventFieldValueSheet(workbook: Workbook, scan: FieldValueScan) {
        val spec = eventFieldValueSpec()
        val sheet = sheetForAnalysis(workbook, spec) ?: return
        // 헤더 판정도 가져오기와 같다 — 첫 행이 없거나 첫 열이 틀리면 가져오기는 시트를
        // **통째로** 건너뛴다(`headerRowOrReport`). 미리보기가 그대로 읽으면 엉뚱한 열을 센다.
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return
        val cols = resolveHeaderColumns(headerRow)
        val keyCol = cols["필드키"] ?: -1
        if (keyCol < 0) return
        val codeCol = cols["사건코드"] ?: 0
        val descCol = cols["사건설명"] ?: -1
        val uNameCol = cols["세계관"] ?: -1
        val uCodeCol = cols["세계관코드"] ?: -1
        val valueCol = cols["값"] ?: -1

        val fieldDefs = analysisFieldIndex()
        val allUniverses = analysisUniverses()
        val universesByName = allUniverses.associateBy { it.name }
        val universesByCode = allUniverses.associateBy { it.code }

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val code = getCellCode(row, codeCol, "", result = null)
            val desc = if (descCol >= 0) getCellString(row, descCol) else ""
            val fieldKey = getCellString(row, keyCol)
            if (code.isBlank() && desc.isBlank() && fieldKey.isBlank()) continue
            val cell = if (valueCol >= 0) getCellString(row, valueCol) else ""

            val event = if (code.isBlank()) null else eventByCode(code)
            if (event == null) { if (cell.isNotBlank()) scan.skip(); continue }

            val uName = if (uNameCol >= 0) getCellString(row, uNameCol) else ""
            val uCode = if (uCodeCol >= 0) getCellCode(row, uCodeCol, "", result = null) else ""
            val globalScope = FieldScopeCell.isGlobal(uName, uCode)
            val universe = if (globalScope) null else {
                uCode.takeIf { it.isNotBlank() }?.let { universesByCode[it] } ?: universesByName[uName]
            }
            if (universe == null && !globalScope) { if (cell.isNotBlank()) scan.skip(); continue }

            val fd = fieldDefs.find(universe?.id, fieldKey, FieldDefinition.ENTITY_EVENT)
            if (fd == null || fd.fieldType == FieldType.CALCULATED) { if (cell.isNotBlank()) scan.skip(); continue }
            if (scan.isOwned(event.id, fd.id)) continue

            scan.count(event.id, fd.id, cell, columnPresent = valueCol >= 0)
        }
    }

    /**
     * 작품·연표 시트의 `필드:` 열을 모은다 — **가져오기 두 자리와 미리보기 두 자리가 함께 쓴다**.
     *
     * 종전에는 작품과 연표가 **글자까지 같은 이 코드를 각자** 들고 있었고, 미리보기가 세기
     * 시작하면 그것이 넷이 된다(R-33이 모으는 넷 중 '열 해석'). 정확 일치를 최우선으로 두는
     * 이유는 이름이 괄호로 끝나는 필드('규모(명)')를 세계관 한정으로 오인하면 열 전체가
     * 버려지기 때문이다.
     */
    private fun collectEntityFieldColumns(
        headerRow: Row,
        fields: List<FieldDefinition>,
        universeNamesById: Map<Long, String>,
        result: ImportResult?,
        sheetLabel: String
    ): List<EventFieldColumn> {
        val knownUniverseNames = universeNamesById.values.toHashSet()
        val knownFieldNames = fields.mapTo(HashSet()) { it.name }
        // 내보내기와 동일 규칙(EntityFieldHeaders)으로 기대 헤더 맵을 만들어 정확 일치를 최우선 조회한다
        val expected = EntityFieldHeaders.expectedHeaders(fields, universeNamesById)
        val columns = mutableListOf<EventFieldColumn>()
        val headersSeen = mutableSetOf<String>()
        for (ci in 0 until headerRow.lastCellNum) {
            val header = getCellString(headerRow, ci)
            if (!header.startsWith(EntityFieldHeaders.PREFIX)) continue
            if (!headersSeen.add(header)) {
                result?.warnings?.add("$sheetLabel: 필드 열 '$header'이(가) 중복되어 뒤쪽 열을 무시했습니다 — 필드명이 겹치지 않는지 확인하세요")
                continue
            }
            val exact = expected[header]
            if (exact != null) {
                // 내보낸 그대로의 헤더 — 이름·세계관명에 어떤 문자가 있어도 정확히 복원된다
                columns.add(EventFieldColumn(ci, header, exact.name, exact.universeId?.let { universeNamesById[it] }, exact))
                continue
            }
            val parsed = EntityFieldHeaders.parseFallback(header, knownFieldNames, knownUniverseNames) ?: continue
            columns.add(EventFieldColumn(ci, header, parsed.fieldName, parsed.universeName, null))
        }
        return columns
    }

    /** 이 대상(작품·사건)의 필드 — DB의 것 **뒤에** 이 파일이 만들 것을 붙인다([analysisCreatedFields]). */
    private suspend fun analysisEntityFields(entityType: String): List<FieldDefinition> {
        val existing = db.fieldDefinitionDao().getAllFieldsList(entityType)
        if (analysisCreatedFields.isEmpty()) return existing
        return existing + analysisCreatedFields.filter { it.entityType == entityType }
    }

    /**
     * 미리보기가 `필드:` 열을 모을 때 쓰는 짧은 꼴.
     * **필드 목록은 받는다** — 부르는 쪽이 같은 목록을 `EntityFieldColumnResolver`에도 넘기므로,
     * 여기서 다시 뜨면 같은 표를 한 번 더 읽는다.
     */
    private suspend fun analysisEntityFieldColumns(
        headerRow: Row,
        fields: List<FieldDefinition>
    ): List<EventFieldColumn> = collectEntityFieldColumns(
        headerRow, fields,
        db.universeDao().getAllUniversesList().associate { it.id to it.name },
        result = null, sheetLabel = ""
    )

    /**
     * 작품·연표 시트 한 행의 `필드:` 열을 센다 — 구역 해석은 가져오기와 **같은 함수**다
     * ([EntityFieldColumnResolver] · B-65).
     */
    private suspend fun countEntityFieldColumns(
        row: Row,
        columns: List<EventFieldColumn>,
        ownerId: Long,
        ownerUniverseId: Long?,
        scan: FieldValueScan?,
        fields: List<FieldDefinition>,
        universeIdsByName: Map<String, Long>
    ) {
        if (scan == null || columns.isEmpty()) return
        for (col in columns) {
            val cell = getCellString(row, col.colIndex)
            val fieldDef = EntityFieldColumnResolver.resolve(
                col.resolved, col.fieldName, col.universeName, ownerUniverseId, fields, universeIdsByName
            )
            // 해석 실패·계산 필드는 가져오기가 `resolvedFieldIds`에도 넣지 않는다 —
            // 기존 값을 건드리지 않으므로, 값이 적혀 있으면 그것이 반영되지 않는다는 사실만 남는다.
            if (fieldDef == null || fieldDef.fieldType == FieldType.CALCULATED) {
                if (cell.isNotBlank()) scan.skip()
                continue
            }
            scan.count(ownerId, fieldDef.id, cell)
        }
    }

    /** [FieldValueScan]의 계수를 화면이 읽는 꼴로 옮긴다 — 자리가 셋이라 여기서 한 벌로 짓는다. */
    private fun fieldValueCategory(key: String, label: String, scan: FieldValueScan) = CategoryAnalysis(
        key, label, scan.inBackup, scan.newCount, scan.updateCount, scan.unchangedCount,
        scan.existingTotal, scan.skippedCount, scan.clearedCount
    )

    private suspend fun analyzeTimeline(
        workbook: Workbook,
        onProgress: (ImportProgress) -> Unit,
        totalRows: Int,
        /** 연표 시트의 '필드:' 열이 여기 실린다 (B-187) — 근거는 `analyzeCharacters`의 같은 인자. */
        fieldValues: FieldValueScan? = null
    ): CategoryAnalysis {
        val spec = timelineSpec(emptyList())
        val sheet = sheetForAnalysis(workbook, spec)
        val existingTotal = db.timelineDao().getAllEventsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일한 설명 열 해석 — 없으면 가져오기도 시트를 통째로 건너뛴다.
        val descColIndex = TimelineCols.descColumn(cols)
            ?: return CategoryAnalysis("timeline", "사건 연표", 0, 0, 0, 0, existingTotal)
        val c = TimelineCols(cols, descColIndex)
        val now = System.currentTimeMillis()
        // 가져오기와 **같은 재료·같은 판정**의 제목 색인이다(규약 R-33).
        val novelTitles = NovelTitleIndex(db.novelDao().getAllNovelsList())
        // 사건 필드 열도 작품 시트와 같은 재료로 같은 해석기를 부른다(B-65 · B-187).
        val eventFields = if (fieldValues == null) emptyList() else analysisEntityFields(FieldDefinition.ENTITY_EVENT)
        val universeIdsByName = if (fieldValues == null) emptyMap() else
            analysisUniverses().associate { it.name to it.id }
        val fieldColumns = if (fieldValues == null) emptyList()
        else analysisEntityFieldColumns(headerRow, eventFields)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readTimelineRow(row, c, "연표 행 ${excelRow(i)}", result = null)
            // 연도가 해석되지 않는 행을 가져오기는 **세고 소리 내어 거부한다** — 미리보기도
            // 같은 조건으로 센다(B-237). 조건까지 같아야 하는 이유는 가져오기가 **완전히 빈 행**
            // (연도 원문도 설명도 빈 것)은 세지 않기 때문이다: 표 아래 여백까지 '건너뜀'으로
            // 세면 정상 파일에서도 숫자가 붙는다.
            val year = r.year
            if (year == null) {
                if (r.yearRaw.isNotBlank() || r.description.isNotBlank()) { inBackup++; skippedCount++ }
                continue
            }
            if (r.description.isBlank()) {
                // 필수 칸만 빈 행은 '건너뜀'으로 센다 — 외부 편집이 상하게 한 행이다.
                // **완전히 빈 행(표 아래 여백)은 침묵한다**([rowCarriesValue] · skippedCount 계약).
                if (rowCarriesValue(row)) { inBackup++; skippedCount++ }
                continue
            }
            inBackup++

            // 작품 연결·세계관 소속 해석도 가져오기와 **같은 함수**다. 가져오기와 마찬가지로
            // 매칭보다 **먼저** 푼다 — 신규 갈래도 이 값으로 사건을 짓기 때문이다(B-233).
            val links = resolveTimelineLinks(row, c, r, novelTitles, "연표 행 ${excelRow(i)}", result = null)
            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val existing = eventByCode(r.fileCode) ?: eventByNaturalKey(year, r.description)
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만든 사건 — 같은 코드·같은 자연키를 든 뒷 행이 이것과 매칭된다.
                // 못 보면 한 파일에서 같은 사건이 둘로 갈린다(가져오기 쪽 주석과 같은 사유).
                val created = newTimelineEventFrom(
                    r, year, links, i, now,
                    code = r.fileCode
                ).copy(id = previewIds.mint())
                rememberEvent(created)
                countEntityFieldColumns(
                    row, fieldColumns, created.id, links.universeId, fieldValues, eventFields, universeIdsByName
                )
                continue
            }
            // 종전에는 **자연키로 매칭된 행을 무조건 '동일'**로 셌다 — 월·일·역법·유형·세계관·
            // 정렬순서·임시배치를 고쳐도 미리보기가 '변경 없음'이라 말했다.
            val merged = mergeTimelineEvent(existing, r, links, CODE_BACKFILL_PREVIEW)
            if (merged != existing) updateCount++ else unchangedCount++
            // 구역은 가져오기와 같은 식으로 고른다 — 이번 행이 옮긴 소속이 우선이고 없으면 기존 소속이다.
            countEntityFieldColumns(
                row, fieldColumns, merged.id, links.universeId ?: existing.universeId,
                fieldValues, eventFields, universeIdsByName
            )
        }
        reportProgress(onProgress, "사건 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("timeline", "사건 연표", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeStateChanges(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = stateChangeSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236) — 종전에는 총계로 표를 통째로
        // 읽고도 **행마다 다시** 물었다.
        val allChanges = db.characterStateChangeDao().getAllChangesList()
        val existingTotal = allChanges.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일하게 필수 열이 없으면 시트를 통째로 건너뛴다(위치 폴백 금지).
        val yearColIndex = cols["연도"] ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val fieldKeyColIndex = cols["필드키"] ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val newValueColIndex = cols["새 값"] ?: return CategoryAnalysis("stateChanges", "상태 변화", 0, 0, 0, 0, existingTotal)
        val c = StateChangeCols(cols, yearColIndex, fieldKeyColIndex, newValueColIndex)
        val now = System.currentTimeMillis()

        // 가져오기와 **같은 판정**의 제목 색인이다(규약 R-33) — 동명 작품이면 힌트를 포기한다.
        val novelTitles = NovelTitleIndex(db.novelDao().getAllNovelsList())
        // 정체성 색인도 가져오기와 **같은 클래스**다(B-236) — 키 모양과 싣는 순서가 갈리면
        // 같은 `merge*`를 써도 **비교 상대**가 달라져 예고가 거짓이 된다(R-33).
        val changes = StateChangeIndexes(allChanges)
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readStateChangeRow(row, c, "상태변화 행 ${excelRow(i)}", result = null)
            if (r.charName.isBlank()) continue
            // 아래 둘은 가져오기가 **세고 고지하며** 거부하는 행이다 — 미리보기도 센다(B-237).
            // 이름만 빈 행과 갈리는 자리다: 그쪽은 가져오기도 조용히 지나간다(빈 행).
            val year = r.year
            if (year == null) { inBackup++; skippedCount++; continue }
            if (r.fieldKey.isBlank()) continue
            if (r.newValue.isBlank()) { inBackup++; skippedCount++; continue }
            inBackup++

            // 캐릭터 해석은 가져오기와 **같은 사다리**다(규약 R-33 — B-232).
            // 코드가 적혀 있으면 코드가 전부다: 가져오기는 코드 미해석을 이름으로 폴백하지 않고
            // 그 행을 거부한다. 종전에는 미리보기만 이름으로 내려가, 코드가 낡은 행을
            // '갱신'으로 예고하고 실제로는 아무것도 들어가지 않았다.
            val character: Character? = if (r.charCode.isNotBlank()) {
                characterByCode(r.charCode)
            } else {
                val novelId = (novelTitles.resolve(r.novelTitle, null) as? NovelTitleLookup.Found)?.novel?.id
                when (val resolved = resolveCharByNameNovel(r.charName, novelId)) {
                    is CharLookupResult.Found -> resolved.character
                    // 동명이인은 캐릭터 시트를 함께 가져와도 해소되지 않는다 — 가져오기가
                    // 영원히 거부하므로 '신규'가 아니라 '건너뜀'이다(B-102 ⓑ).
                    is CharLookupResult.Ambiguous -> { skippedCount++; continue }
                    CharLookupResult.NotFound -> null
                }
            }
            // 캐릭터가 아직 없을 뿐이면, 같은 파일의 캐릭터 시트가 함께 오면 먼저 생긴다(B-102 ⓑ).
            if (character == null) {
                if (options.characters) newCount++ else skippedCount++
                continue
            }

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val existing = (if (r.fileCode.isNotBlank()) changes.byCode.first(r.fileCode) else null)
                ?: changes.byNaturalKey.first(StateChangeNaturalKey(character.id, year, r.fieldKey, r.newValue))
            if (existing == null) {
                newCount++
                changes.remember(
                    newStateChangeFrom(
                        r, character.id, year, now, r.fileCode
                    ).copy(id = previewIds.mint())
                )
                continue
            }
            // 종전에는 자연키로 매칭된 행을 무조건 '동일'로 셌다 — 월·일·설명을 고쳐도 그랬다.
            val merged = mergeStateChange(existing, r, character.id, CODE_BACKFILL_PREVIEW)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "상태 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("stateChanges", "상태 변화", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    /**
     * 명대사 미리보기 — [importQuotes]와 **같은 함수들**을 지난다(R-33).
     *
     * `readQuoteRow`·`newQuoteFrom`·`mergeQuote`와 `QuoteIndexes`가 양쪽의 단일 소스다.
     * 하나라도 여기서 따로 적으면 같은 파일을 두고 예고와 결과가 갈린다 — 그리고 그 어긋남은
     * **가져오고 나서야** 보인다.
     */
    private suspend fun analyzeQuotes(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = quoteSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236).
        val allQuotes = db.characterQuoteDao().getAllQuotesList()
        val existingTotal = allQuotes.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("quotes", "명대사", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("quotes", "명대사", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일하게 필수 열이 없으면 시트를 통째로 건너뛴다(위치 폴백 금지).
        val textColIndex = cols["대사"] ?: return CategoryAnalysis("quotes", "명대사", 0, 0, 0, 0, existingTotal)
        val c = QuoteCols(cols, textColIndex)
        val now = System.currentTimeMillis()

        // 가져오기와 **같은 판정**의 제목 색인이다(R-33) — 동명 작품이면 힌트를 포기한다.
        val novelTitles = NovelTitleIndex(db.novelDao().getAllNovelsList())
        // 정체성 색인도 가져오기와 **같은 클래스**다 — 키 모양과 싣는 순서가 갈리면 같은
        // `merge*`를 써도 **비교 상대**가 달라져 예고가 거짓이 된다(R-33).
        val quotes = QuoteIndexes(allQuotes)
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readQuoteRow(row, c, "명대사 행 ${excelRow(i)}", result = null)
            if (r.charName.isBlank()) continue
            // 가져오기가 **세고 고지하며** 거부하는 행은 미리보기도 센다(B-237).
            if (r.text.isBlank()) { inBackup++; skippedCount++; continue }
            inBackup++

            // 캐릭터 해석은 가져오기와 **같은 사다리**다(R-33) — 코드가 적혀 있으면 코드가 전부다.
            val character: Character? = if (r.charCode.isNotBlank()) {
                characterByCode(r.charCode)
            } else {
                val novelId = (novelTitles.resolve(r.novelTitle, null) as? NovelTitleLookup.Found)?.novel?.id
                when (val resolved = resolveCharByNameNovel(r.charName, novelId)) {
                    is CharLookupResult.Found -> resolved.character
                    // 동명이인은 캐릭터 시트를 함께 가져와도 해소되지 않는다(B-102 ⓑ).
                    is CharLookupResult.Ambiguous -> { skippedCount++; continue }
                    CharLookupResult.NotFound -> null
                }
            }
            // 캐릭터가 아직 없을 뿐이면, 같은 파일의 캐릭터 시트가 함께 오면 먼저 생긴다.
            if (character == null) {
                if (options.characters) newCount++ else skippedCount++
                continue
            }

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val existing = (if (r.fileCode.isNotBlank()) quotes.byCode.first(r.fileCode) else null)
                ?: quotes.byNaturalKey.first(QuoteNaturalKey(character.id, r.text))
            if (existing == null) {
                newCount++
                quotes.remember(
                    newQuoteFrom(r, character.id, now, r.fileCode).copy(id = previewIds.mint())
                )
                continue
            }
            val merged = mergeQuote(existing, r, character.id, CODE_BACKFILL_PREVIEW)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "명대사 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("quotes", "명대사", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeRelationships(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = relationshipSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236).
        val allRelationships = db.characterRelationshipDao().getAllRelationships()
        val existingTotal = allRelationships.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일하게 필수 열이 없으면 시트를 통째로 건너뛴다(위치 폴백 금지).
        val char2NameColIndex = cols["캐릭터2"] ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val typeColIndex = cols["관계 유형"] ?: return CategoryAnalysis("relationships", "관계", 0, 0, 0, 0, existingTotal)
        val c = RelationshipCols(cols, char2NameColIndex, typeColIndex)
        val now = System.currentTimeMillis()
        // 세력 참조 해석은 FactionIndex(단일 소스)로 — 전 세계관 first-match 금지
        val factionRefUsed = c.faction >= 0 || c.factionCode >= 0
        val factionIndex = FactionIndex(if (factionRefUsed) db.factionDao().getAllFactionsList() else emptyList())
        // 정체성 색인도 가져오기와 **같은 클래스**다(B-236). 종전에는 **행마다** 캐릭터1의 관계
        // 전부를 다시 읽어(`getRelationshipsForCharacterList`) 여기서 쌍을 걸렀다.
        val rels = RelationshipIndexes(allRelationships)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readRelationshipRow(row, c, "관계 행 ${excelRow(i)}", result = null)
            if (r.char1Name.isBlank() || r.char2Name.isBlank()) continue
            // 두 캐릭터가 채워졌는데 유형만 빈 행을 가져오기는 세고 고지한다 — 미리보기도 센다(B-237).
            if (r.relationshipType.isBlank()) { inBackup++; skippedCount++; continue }
            inBackup++

            // 캐릭터 해석은 가져오기와 **같은 사다리**다(규약 R-33 — B-232). 순서까지 같다:
            // 가져오기는 관계 코드를 보기 **전에** 두 캐릭터를 확정하고, 확정되지 않으면
            // 코드가 기존 관계를 가리켜도 그 행을 거부한다.
            val char1 = when (val res = findCharacterStrict(r.char1Name, r.char1Code)) {
                is CharLookupResult.Found -> res.character
                // 동명이인은 캐릭터 시트를 함께 가져와도 해소되지 않는다 — 영원히 거부되므로 '건너뜀'.
                is CharLookupResult.Ambiguous -> { skippedCount++; continue }
                CharLookupResult.NotFound -> null
            }
            val char2 = when (val res = findCharacterStrict(r.char2Name, r.char2Code)) {
                is CharLookupResult.Found -> res.character
                is CharLookupResult.Ambiguous -> { skippedCount++; continue }
                CharLookupResult.NotFound -> null
            }
            // 아직 없을 뿐이면 같은 파일의 캐릭터 시트가 먼저 만들어 준다(B-102 ⓑ).
            if (char1 == null || char2 == null) {
                if (options.characters) newCount++ else skippedCount++
                continue
            }
            // 자기 자신과의 관계는 가져오기가 언제나 거부한다 — 캐릭터 시트와 무관하다.
            if (char1.id == char2.id) { skippedCount++; continue }

            // 세력 해석은 매칭보다 **먼저**다 — 가져오기와 같은 순서이고, 신규 갈래도 이 값으로
            // 관계를 짓는다(B-233).
            // 세력 미해석(NotFound·Ambiguous)은 가져오기와 **같은 갈래**로 KEEP으로 강등한다 —
            // LOOKUP인 채 null을 넘기면 '해제'가 되어, 기존 세력이 붙은 관계를 파일이 손대지
            // 않았는데도 '변경'으로 세었다(B-217 — 가져오기는 기존 연결을 지킨다).
            var factionIntent = r.factionIntent
            var factionId: Long? = null
            if (factionIntent == RefIntent.LOOKUP) {
                // 세계관 힌트도 가져오기와 같다 — 캐릭터1이 미분류면 캐릭터2로 넘어간다.
                val hintUniverseId = universeIdOfCharacter(char1) ?: universeIdOfCharacter(char2)
                when (val fr = factionIndex.resolve(r.factionName, r.factionCode, hintUniverseId)) {
                    is FactionLookupResult.Found -> factionId = fr.faction.id
                    else -> factionIntent = RefIntent.KEEP
                }
            }

            // 매칭은 가져오기와 **같은 함수**다(R-33·R-53) — 코드 우선 → 자연키 폴백이되,
            // 코드가 다른 쌍을 가리키면 이 행의 것이 아니다(그러면 여기서도 '새로 만듦'으로 센다).
            val match = rels.matchRow(r.relCode, char1.id, char2.id, r.relationshipType)
            val existing = match.existing
            if (existing == null) {
                newCount++
                // 같은 쌍·같은 코드를 든 뒷 행이 이것을 봐야 한다 — 못 보면 같은 관계가
                // 한 파일에서 둘로 갈린다(가져오기 쪽 주석과 같은 사유, B-233).
                rels.remember(
                    newRelationshipFrom(
                        r, char1.id, char2.id, factionId, i, now,
                        // 가져오기와 같은 갈래 — 남이 든 코드는 새 관계가 물려받지 못한다.
                        r.relCode.takeIf { match.canReuseFileCode }.orEmpty()
                    ).copy(id = previewIds.mint())
                )
                continue
            }
            // 종전에는 설명 하나만 봤다 — 강도·양방향·표시순서·세력을 고쳐도 '동일'이라 말했다.
            val merged = mergeRelationship(
                existing, r,
                effectiveRelationshipFactionId(existing, r.copy(factionIntent = factionIntent), factionId),
                CODE_BACKFILL_PREVIEW
            )
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationships", "관계", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeRelationshipChanges(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        // 시트 조회도 가져오기와 **같은 spec·같은 판정**이다 — 종전에는 여기만 리터럴이었다(B-217).
        val sheet = sheetForAnalysis(workbook, relationshipChangeSpec())
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236).
        val allRelChanges = db.characterRelationshipChangeDao().getAllChanges()
        val existingTotal = allRelChanges.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, "이름") ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val cols = resolveHeaderColumns(headerRow)
        // 실제 임포트와 동일하게 필수 열이 없으면 시트를 통째로 건너뛴다(위치 폴백 금지).
        val char2NameColIndex = cols["캐릭터2"] ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val yearColIndex = cols["연도"] ?: return CategoryAnalysis("relationshipChanges", "관계 변화", 0, 0, 0, 0, existingTotal)
        val c = RelChangeCols(cols, char2NameColIndex, yearColIndex)
        val now = System.currentTimeMillis()
        // 정체성 색인도 가져오기와 **같은 클래스**다(B-236) — 이 시트가 행마다 물던 표는 둘이다:
        // 부모 **관계**(쌍)와 **관계 변화**(코드·자연키).
        val parentRels = RelationshipIndexes(db.characterRelationshipDao().getAllRelationships())
        val relChanges = RelationshipChangeIndexes(allRelChanges)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readRelChangeRow(row, c, "관계변화 행 ${excelRow(i)}", result = null)
            if (r.char1Name.isBlank() || r.char2Name.isBlank()) continue
            // 연도 미해석은 가져오기가 세고 고지한다 — 미리보기도 센다(B-237).
            val year = r.year
            if (year == null) { inBackup++; skippedCount++; continue }
            inBackup++

            // 캐릭터 해석은 가져오기와 **같은 사다리**다(규약 R-33 — B-232).
            val char1 = when (val res = findCharacterStrict(r.char1Name, r.char1Code)) {
                is CharLookupResult.Found -> res.character
                // 동명이인은 캐릭터 시트를 함께 가져와도 해소되지 않는다 — 영원히 거부되므로 '건너뜀'.
                is CharLookupResult.Ambiguous -> { skippedCount++; continue }
                CharLookupResult.NotFound -> null
            }
            val char2 = when (val res = findCharacterStrict(r.char2Name, r.char2Code)) {
                is CharLookupResult.Found -> res.character
                is CharLookupResult.Ambiguous -> { skippedCount++; continue }
                CharLookupResult.NotFound -> null
            }
            // 아직 없을 뿐이면 같은 파일의 캐릭터 시트가 먼저 만들어 준다(B-102 ⓑ).
            if (char1 == null || char2 == null) {
                if (options.characters) newCount++ else skippedCount++
                continue
            }

            // 부모 관계 해석도 가져오기와 **같은 함수**다 — 종전에는 쌍의 첫 관계를 골라
            // 유형이 다른 이력을 엉뚱한 관계에 붙여 세었다.
            val pairRelationships = parentRels.pair(char1.id, char2.id)
            // 관계가 없거나 확정되지 않으면 가져오기가 행을 거부한다(B-102 ⓑ).
            if (pairRelationships.isEmpty()) {
                if (options.relationships) newCount++ else skippedCount++
                continue
            }
            val relationship = resolveRelChangeParent(r, pairRelationships, char1.id, char2.id, "관계변화 행 ${excelRow(i)}", result = null)
            if (relationship == null) { skippedCount++; continue }

            // 실제 임포트와 동일한 매칭: 코드 우선 → 자연키 폴백
            val eventId = resolveRelChangeEventId(r, "관계변화 행 ${excelRow(i)}", result = null)
            val existing = (if (r.fileCode.isNotBlank()) relChanges.byCode.first(r.fileCode) else null)
                ?: relChanges.byNaturalKey.first(RelChangeNaturalKey(relationship.id, year, r.month, r.day))
            if (existing == null) {
                newCount++
                relChanges.remember(
                    newRelationshipChangeFrom(
                        r, relationship.id, year, eventId, now,
                        r.fileCode
                    ).copy(id = previewIds.mint())
                )
                continue
            }
            // 종전에는 자연키로 매칭된 행을 **무조건 '동일'**로 셌다 —
            // 관계 유형·설명·강도·양방향·연결 사건을 고쳐도 '변경 없음'이라 말했다.
            val merged = mergeRelationshipChange(
                existing, r, relationship.id, eventId, CODE_BACKFILL_PREVIEW
            )
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "관계 변화 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("relationshipChanges", "관계 변화", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeNameBank(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = nameBankSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        val existingNames = db.nameBankDao().getAllNamesList()
        val existingTotal = existingNames.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("nameBank", "이름 은행", 0, 0, 0, 0, existingTotal)
        val c = NameBankCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        // 색인도, 사다리도 가져오기와 **같다**([ImportLookupIndex] 셋 — B-217). 종전의 손코딩
        // 맵은 `associateBy`(마지막 승자)로 실려 색인(id 오름차순 첫째 = LIMIT 1)과 다른 상대를
        // 골랐고, 갱신·신규 등재가 옛 키를 끊지 않아(성질 ③ 위반) 한 파일 안에서 개명한 뒤
        // 옛 이름 행이 또 나오면 방금 개명한 항목과 매칭됐다 — 가져오기는 신규로 만든다.
        // 이름 단독 폴백도 자연키 맵 위에서는 같은 (이름, 성별)이 하나로 접혀 '유일'을 오판했다.
        val sortedNames = existingNames.sortedBy { it.id }
        val nameBankNaturalKeys = ImportLookupIndex<String, NameBankEntry>(
            idOf = { it.id }, keyOf = { it.mapKeyForNameBank() }
        )
        val nameBankCodes = ImportLookupIndex<String, NameBankEntry>(
            idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
        )
        val nameBankByName = ImportLookupIndex<String, NameBankEntry>(
            idOf = { it.id }, keyOf = { it.name }
        )
        nameBankNaturalKeys.load(sortedNames)
        nameBankCodes.load(sortedNames)
        nameBankByName.load(sortedNames)
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readNameBankRow(row, c, "이름 은행 행 ${excelRow(i)}", result = null)
            if (r.name.isBlank()) continue
            inBackup++

            // F3-D: 코드 우선 매칭(이름/성별을 편집해도 같은 항목 인식) → 자연키(이름+성별) 폴백
            // → 성별 열이 없는 파일은 이름 단독 유일 폴백 (가져오기와 같은 사다리 — R-33)
            val existing = (if (r.code.isNotBlank()) nameBankCodes.first(r.code) else null)
                ?: r.mapKey?.let { nameBankNaturalKeys.first(it) }
                ?: (if (r.gender == null) nameBankByName.all(r.name).singleOrNull() else null)
            if (existing == null) {
                newCount++
                // 이 시트가 방금 만든 항목 — 뒤 행이 이것과 매칭될 수 있다(가져오기와 같은 성질 ②).
                val created = NameBankEntry(
                    name = r.name, gender = r.gender ?: "", origin = r.origin ?: "", notes = r.notes ?: "",
                    isUsed = r.usedFlag ?: false,
                    usedByCharacterId = resolveNameBankUsedBy(r, null, "이름 은행 행 ${excelRow(i)}", result = null),
                    createdAt = r.createdAt ?: now,
                    code = r.code.ifBlank { "" }
                ).copy(id = previewIds.mint())
                nameBankNaturalKeys.put(created)
                nameBankCodes.put(created)
                nameBankByName.put(created)
                continue
            }
            val merged = mergeNameBankEntry(existing, r, resolveNameBankUsedBy(r, existing, "이름 은행 행 ${excelRow(i)}", result = null))
            if (merged != existing) updateCount++ else unchangedCount++
            // 갱신된 값도 되돌려 놓는다 — 같은 항목을 가리키는 뒤 행은 이 결과 위에서 판정되고,
            // put이 옛 키를 끊는다(개명 시 옛 이름으로는 더 잡히지 않는다).
            nameBankNaturalKeys.put(merged)
            nameBankCodes.put(merged)
            nameBankByName.put(merged)
        }
        reportProgress(onProgress, "이름 은행 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("nameBank", "이름 은행", inBackup, newCount, updateCount, unchangedCount, existingTotal)
    }

    private suspend fun analyzeFactions(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236).
        val allFactions = db.factionDao().getAllFactionsList()
        val existingTotal = allFactions.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("factions", "세력", 0, 0, 0, 0, existingTotal)
        val c = FactionCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val now = System.currentTimeMillis()
        // 정체성 색인도 가져오기와 **같은 클래스**다(B-236).
        val factions = FactionIdentityIndexes(allFactions)

        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readFactionRow(row, c, "세력 행 ${excelRow(i)}", result = null)
            if (r.name.isBlank()) continue
            inBackup++

            // 자동관계유형이 '열 있음+빈칸'이면 가져오기가 행을 거부한다 — '신규'가 아니다.
            // 열 자체가 없으면 기존 수정은 통과하고 신규만 거부된다(R-36 — 가져오기와 같은 갈래).
            if (r.autoRelationType != null && r.autoRelationType.isBlank()) { skippedCount++; continue }
            // 세계관 해석도 가져오기와 같다 — 코드 우선, 이름 폴백(종전에는 이름만 봤다).
            val resolvedUniverse = universeByCodeOrName(r.universeCode, r.universeName)
            // 세계관 참조가 있는데 해석되지 않으면 가져오기가 행을 거부한다(B-102 ⓑ).
            if (resolvedUniverse == null && (r.universeName.isNotBlank() || r.universeCode.isNotBlank())) {
                if (options.universes) newCount++ else skippedCount++
                continue
            }
            val existing = (if (r.code.isNotBlank()) factions.byCode.first(r.code) else null)
                ?: resolvedUniverse?.let { factions.byNameKey.first(FactionNameKey(r.name, it.id)) }
            if (existing == null) {
                // 자동관계유형 열이 아예 없는 파일에서 가져오기는 **신규를 만들지 않는다**(R-36) —
                // 만들지 않는 행은 등재할 것도 없다.
                if (r.autoRelationType == null) { skippedCount++; continue }
                newCount++
                // 이 시트가 방금 만든 세력 — 같은 코드·같은 (이름, 세계관)을 든 뒷 행도,
                // 뒤 시트(세력 소속·세력 관계·관계)도 이것을 본다(B-233).
                resolvedUniverse?.let { u ->
                    factions.remember(
                        newFactionFrom(r, r.code, u.id, r.autoRelationType, i, now)
                            .copy(id = previewIds.mint())
                    )
                }
                continue
            }
            val merged = mergeFaction(existing, r, universeId = resolvedUniverse?.id ?: existing.universeId)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세력 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factions", "세력", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeFactionMemberships(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionMembershipSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        val existingTotal = db.factionMembershipDao().getAllMembershipsList().size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("factionMemberships", "세력 소속", 0, 0, 0, 0, existingTotal)
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
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val factionName = getCellString(row, factionNameColIndex)
            if (factionName.isBlank()) continue
            val charName = getCellString(row, charNameColIndex)
            if (charName.isBlank()) continue
            inBackup++

            val factionCode = if (factionCodeColIndex >= 0) getCellString(row, factionCodeColIndex) else ""
            val charCode = if (charCodeColIndex >= 0) getCellString(row, charCodeColIndex) else ""

            // 캐릭터를 먼저 — 세력의 동명 해소 힌트가 된다. 해석도 가져오기와 **같은 함수**다
            // (findCharacterStrict — 동명이인 모호 감지 포함. 종전의 first-match는 가져오기가
            // 거부하는 모호 행을 아무나 골라 '변경/동일'로 세었다, B-217).
            // 미해석은 B-102 ⓑ: 캐릭터·세력을 함께 가져오면 먼저 생기므로 '신규'가 맞고,
            // 빼놓았으면 가져오기가 거부한다 — 종전엔 무계수라 신규 기기 복원 미리보기가
            // 소속 전부에 대해 '신규 0'을 말했다.
            val character = when (val cr = findCharacterStrict(charName, charCode)) {
                is CharLookupResult.Found -> cr.character
                is CharLookupResult.Ambiguous -> { skippedCount++; continue }
                CharLookupResult.NotFound -> {
                    if (options.characters) newCount++ else skippedCount++
                    continue
                }
            }
            val faction = when (val fr = factionIndex.resolve(factionName, factionCode, universeIdOfCharacter(character))) {
                is FactionLookupResult.Found -> fr.faction
                is FactionLookupResult.Ambiguous -> { skippedCount++; continue }
                FactionLookupResult.NotFound -> {
                    if (options.factions) newCount++ else skippedCount++
                    continue
                }
            }

            // 매칭 규칙은 실제 가져오기와 **같은 함수**를 쓴다(FactionMembershipMatcher).
            // 활성만 보던 종전 규칙은 탈퇴 이력 행을 매번 '신규'로, 나아가 '백업에 없음'으로
            // 세어 아무것도 안 고친 파일에 삭제를 예고했다 — 실제로는 매칭돼 그대로 남는데도.
            // 미리보기도 가져오기와 **같은 함수**로 읽는다 (R-33) — 갈리면 예고한 건수와
            // 실제가 어긋난다. 경고는 결과 창의 것이라 여기서는 싣지 않는다(`result = null`).
            val analyzedLeaveYear = readYearCell(row, leaveYearColIndex, "", "탈퇴연도", null)
            val rowValues = FactionMembershipMatcher.RowValues(
                joinYear = readYearCell(row, joinYearColIndex, "", "가입연도", null),
                leaveYear = analyzedLeaveYear,
                // 가져오기가 바로잡는 반쪽 표식을 미리보기도 **같은 함수로** 바로잡는다 (B-206 · R-33) —
                // 안 그러면 '변경'으로 셀 행을 '동일'이라 말한다.
                leaveType = FactionStanding.leaveTypeForImportedRow(
                    analyzedLeaveYear,
                    parseFactionLeaveType(if (leaveTypeColIndex >= 0) getCellString(row, leaveTypeColIndex) else ""),
                    leaveTypeColIndex >= 0
                ),
                departedRelationType = if (departedRelTypeColIndex >= 0) getCellString(row, departedRelTypeColIndex).ifBlank { null } else null,
                departedIntensity = if (departedIntensityColIndex >= 0) parseNumber(getCellString(row, departedIntensityColIndex))?.toInt() else null,
                // 가져오기와 **같은 함수**로 읽는다(R-33) — result가 null이라 값만 낸다.
                createdAt = readCreatedAtCell(row, createdAtColIndex, "세력 소속 행 ${excelRow(i)}", result = null)
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
        return CategoryAnalysis("factionMemberships", "세력 소속", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzeFactionRelationships(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = factionRelationshipSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        val existingRels = db.factionRelationshipDao().getAllRelationshipsList()
        val existingTotal = existingRels.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("factionRelationships", "세력 관계", 0, 0, 0, 0, existingTotal)

        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("factionRelationships", "세력 관계", 0, 0, 0, 0, existingTotal)
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
        // 가져오기가 굴리는 것과 **같은 맵**이다(B-233) — 새로 만든 행을 즉시 등재해야
        // 같은 쌍·같은 유형이 시트에 두 번 나올 때 둘째 행이 첫째와 매칭된다.
        val existingByKey = existingRels.associateByTo(mutableMapOf()) {
            FactionRelationshipMatcher.key(it.factionId1, it.factionId2, it.relationType)
        }
        val presence = factionRelationshipPresence(descColIndex, intensityColIndex, bidirectionalColIndex, orderColIndex)

        val now = System.currentTimeMillis()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val f1Name = getCellString(row, faction1ColIndex)
            if (f1Name.isBlank()) continue
            val f2Name = getCellString(row, faction2ColIndex)
            if (f2Name.isBlank()) continue
            val relType = getCellString(row, typeColIndex).trim()
            // 두 세력이 채워졌는데 유형만 빈 행을 가져오기는 세고 고지한다 — 미리보기도 센다(B-237).
            if (relType.isBlank()) { inBackup++; skippedCount++; continue }
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
            val rowValues = factionRelationshipRowValues(
                row, descColIndex, intensityColIndex, bidirectionalColIndex, orderColIndex,
                "세력 관계 행 ${excelRow(i)}", null
            )
            val existing = FactionRelationshipMatcher.match(existingByKey, f1.id, f2.id, relType)
            if (existing == null) {
                newCount++
                existingByKey[FactionRelationshipMatcher.key(f1.id, f2.id, relType)] =
                    newFactionRelationshipFrom(rowValues, f1.id, f2.id, relType, now)
                        .copy(id = previewIds.mint())
                continue
            }
            if (FactionRelationshipMatcher.changes(existing, rowValues, presence)) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "세력 관계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("factionRelationships", "세력 관계", inBackup, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
    }

    private suspend fun analyzePresetTemplates(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = userPresetTemplateSpec()
        val sheet = sheetForAnalysis(workbook, spec)
        val existingTemplates = db.userPresetTemplateDao().getAllTemplatesList()
        val existingTotal = existingTemplates.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("presetTemplates", "필드 템플릿", 0, 0, 0, 0, existingTotal)

        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("presetTemplates", "필드 템플릿", 0, 0, 0, 0, existingTotal)
        val c = PresetTemplateCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        // 미리보기도 실제 임포트와 같은 매칭기를 태운다 — 이름 맵으로 세면 동명 템플릿이 접혀
        // 실제로는 N건이 갱신되는데 1건으로 보고된다(사실과 다른 미리보기).
        val matcher = PresetTemplateMatcher(
            existingTemplates.map { PresetTemplateMatcher.Candidate(it.id, it.name, it.createdAt) }
        )
        val byId = existingTemplates.associateByTo(mutableMapOf()) { it.id }
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readPresetTemplateRow(row, c, "필드 템플릿 행 ${excelRow(i)}", result = null)
            if (r.name.isBlank()) continue
            inBackup++

            val match = matcher.claim(r.name, r.createdAt, i)
            if (match !is PresetTemplateMatcher.Match.Matched) {
                newCount++
                // 이 시트가 방금 만든 템플릿을 매칭기에 등재한다 — 가져오기의 `register`와 같은
                // 자리다(B-233). 안 하면 같은 이름을 두 번 적은 파일이 '신규 2'가 되는데
                // 가져오기는 둘째를 첫째와 잇는다.
                val created = newPresetTemplateFrom(r, now).copy(id = previewIds.mint())
                byId[created.id] = created
                matcher.register(created.id, created.name, created.createdAt, i)
                continue
            }
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
        val sheet = sheetForAnalysis(workbook, spec)
        val existingPresets = db.searchPresetDao().getAllPresetsList()
        val existingTotal = existingPresets.size
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("searchPresets", "검색 프리셋", 0, 0, 0, 0, existingTotal)

        // 목록 프리셋 분석과 동형 — 헤더가 어긋난 시트를 억지로 읽어 사실과 다른 미리보기를 내지 않는다
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("searchPresets", "검색 프리셋", 0, 0, 0, 0, existingTotal)
        val c = SearchPresetCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        // 가져오기가 굴리는 것과 **같은 맵**이다 — 새로 만든 행을 즉시 등재해야
        // 시트 안에 같은 이름이 두 번 나올 때 둘째 행이 첫째와 매칭된다(B-102 ⓐ).
        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readSearchPresetRow(row, c, "검색 프리셋 행 ${excelRow(i)}", filterIndex, result = null)
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
        val sheet = sheetForAnalysis(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("characterListPresets", label, 0, 0, 0, 0, existingTotal)
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("characterListPresets", label, 0, 0, 0, 0, existingTotal)

        val c = ListPresetCols(resolveHeaderColumns(headerRow))
        val now = System.currentTimeMillis()

        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()
        var inBackup = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0

        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readListPresetRow(row, c, "목록 프리셋 행 ${excelRow(i)}", filterIndex, result = null)
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

    /** 첫 열 헤더 검증 — 판정 원문은 [SheetResolver](가져오기·미리보기·시험의 단일 소스, B-217). */
    private fun isValidHeader(headerRow: Row, expectedFirstHeader: String): Boolean =
        SheetResolver.isValidHeader(headerRow, expectedFirstHeader)

    /**
     * **헤더 행을 찾는 서비스 단일 통로** (B-231 ⓑ) — 판정은 [SheetResolver.locateHeader]가 하고,
     * 여기서는 찾은 **자리를 기록**한다.
     *
     * 자리를 기록하는 이유는 [mergedTopLeftValue]다 — 그 보정은 *헤더 행*을 제외해야 하는데
     * (채우면 같은 이름이 여러 열에 복제돼 [resolveHeaderColumns]의 열 배정이 밀린다),
     * 헤더가 0행이 아닐 수 있게 된 순간 `rowNum == 0` 가드가 **틀린 행을 지키게** 된다.
     */
    private fun locateHeaderRow(sheet: Sheet, expectedFirstHeader: String): Row? {
        val found = SheetResolver.locateHeader(sheet, expectedFirstHeader) ?: return null
        headerRowIndexBySheet[sheet] = found.index
        return found.row
    }

    /**
     * **데이터 루프의 범위** — 헤더 **다음 행**부터 끝까지 (B-231 ⓑ).
     *
     * 종전에는 마흔일곱 자리가 `for (i in 1..sheet.lastRowNum)`이라 **1행이 곧 첫 데이터**였다.
     * 헤더가 3행일 수 있게 된 순간 그 상수는 **제목·메모 행을 데이터로 읽는다** — 이름 칸이
     * '내 캐릭터 목록'인 유령 캐릭터가 생기는 식이고, **아무도 그것을 말하지 않는다.**
     *
     * 시작을 헤더 행에서 파생시키면 자리마다 정할 것이 없어진다. 기계는
     * `tools/check_header_row_report.sh`가 본다(생 `1..lastRowNum`을 금지한다).
     */
    /**
     * 헤더 행 — 찾으면 그 행, **못 찾으면 0행** (B-231 ⓑ).
     *
     * 헤더 첫 열을 **검증하지 않는 자리**의 통로다. 미리보기 여럿과 이미지 메타는 첫 열이
     * 어긋난 시트도 읽고(구버전 헤더·손편집 파일의 관대 수용 — `sheetForRead`의 정확명 폴백이
     * 그 앞단이다) **필수 열이 없을 때** 비로소 시트를 건너뛴다. 여기서 `locateHeaderRow`만
     * 쓰면 그 관대함이 사라져 **B-231이 고치려던 것과 반대 방향으로** 파일을 더 거부하게 된다.
     *
     * 즉 이 함수가 바꾸는 것은 **헤더가 아래에 있는 파일뿐이다** — 0행이 헤더면 답이 종전과 같다.
     */
    private fun headerRowOrFirst(sheet: Sheet, expectedFirstHeader: String): Row? =
        locateHeaderRow(sheet, expectedFirstHeader) ?: sheet.getRow(0)

    private fun dataRows(sheet: Sheet, headerRow: Row): IntRange =
        (headerRow.rowNum + 1)..sheet.lastRowNum

    /**
     * 0-기반 행 색인(POI·스트리밍 공통) → **엑셀 화면의 행 번호**(1-기반).
     *
     * 경고·오류가 적는 행 번호는 전부 이것을 지난다 — 색인을 그대로 적으면 사용자가
     * 엑셀에서 보는 행 머리글과 한 행 어긋난 자리를 고치게 된다(절단 경고의 `rowNum + 1`과
     * 같은 결과 창에서 표기가 갈리던 결함의 수리, 2026.08.20).
     */
    private fun excelRow(index: Int): Int = index + 1

    /**
     * 0-기반 열 색인 → **엑셀 화면의 열 문자**(A·Z·AA…).
     *
     * [excelRow]와 같은 이유다 — 경고가 *"열13"*이라 적으면 사용자는 엑셀 머리글이 `M`인
     * 그 칸에 닿으려고 열을 손으로 세어야 한다. 행은 2026.08.20에 맞춰졌는데 열은 그대로였다.
     *
     * **새로 짜지 않고 이미 있는 변환을 지난다** — 두 벌로 적으면 `AA` 경계에서 갈린다.
     */
    private fun excelColumn(index: Int): String =
        com.novelcharacter.app.excel.DropdownListLimits.columnLetter(index)

    /**
     * 헤더 첫 컬럼 검증 + 실패 시 시트를 건너뛰는 이유를 오류로 보고 (무통보 스킵 방지).
     *
     * **문구가 헤더의 *자리*를 함께 말한다**(B-231) — 표 위에 제목·메모 행을 끼워 넣으면
     * 앱은 그 행을 헤더로 읽고 여기서 걸린다. 종전 문구는 *"첫 번째 컬럼은 X이어야 합니다"*만
     * 말해, 사용자가 **열 이름**을 고치러 가서 아무리 고쳐도 낫지 않았다 — 틀린 것은 열이
     * 아니라 **행**이었다.
     */
    private fun checkHeaderOrReport(sheet: Sheet, headerRow: Row, expectedFirstHeader: String, result: ImportResult): Boolean {
        if (isValidHeader(headerRow, expectedFirstHeader)) return true
        val actual = getCellString(headerRow, 0)
        result.errors.add(
            "시트 '${sheet.sheetName}': 첫 번째 컬럼은 '$expectedFirstHeader'이어야 합니다 (현재: '$actual') — 시트를 건너뛰었습니다. 열 이름 행은 시트 맨 앞 ${SheetResolver.HEADER_SEARCH_ROWS}행 안에 있어야 합니다(제목·메모 줄이 그보다 길면 줄이세요). 다른 컬럼 순서는 바꿔도 되지만 첫 컬럼은 고정입니다."
        )
        return false
    }

    /**
     * **헤더 행을 집는 단 하나의 자리** — 없으면 말하고, 틀리면 말한다 (B-231).
     *
     * 종전에는 시트마다 `sheet.getRow(0) ?: return`이 **스물여섯 자리**에 흩어져 있었고,
     * 그 `?: return`은 **아무 말도 하지 않았다.** 첫 행이 통째로 빈 시트(표 위에 빈 줄을
     * 넣었거나, 편집기가 행을 지운 파일)는 그래서 **경고 한 줄 없이 사라졌다** — 짝인
     * '인식되지 않아 무시되었습니다' 경고도 뜨지 않는다. [findSheet]가 그 시트를 이미
     * `consumedSheetNames`에 넣어 그 경고를 **억제하기 때문**이다.
     *
     * 헤더가 *틀린* 파일은 소리 내어 거부하면서 헤더가 *없는* 파일은 조용히 버린 셈이라,
     * 사용자가 알아챌 길이 어디에도 없었다(개발 의도 2번 — 말없이 버리지 않는다).
     *
     * **한 자리로 모으는 것이 수리의 요점이다** — 스물여섯 자리에 같은 두 줄을 적어 두면
     * 새 시트가 하나만 빠뜨려도 그 시트에서만 침묵이 되살아난다.
     */
    private fun headerRowOrReport(sheet: Sheet, expectedFirstHeader: String, result: ImportResult): Row? {
        // **앞쪽 몇 행에서 찾는다**(B-231 ⓑ) — 표 위의 제목·메모·빈 줄을 넘어간다.
        // 찾으면 그 행이 헤더이고 검증은 이미 끝난 것이므로 아래 두 갈래는 *못 찾은* 경우다.
        locateHeaderRow(sheet, expectedFirstHeader)?.let { return it }
        // 여기 온 것은 **찾지 못했다**는 뜻이다 — 아래 두 갈래는 *무엇을 보고 그렇게 말하는가*만 가른다.
        // 첫 행이 아예 없으면 보여 줄 '현재 값'이 없으므로 문구가 갈린다(B-231 ⓐ가 세운 구분).
        val headerRow = sheet.getRow(0)
        if (headerRow == null) {
            result.errors.add(
                "시트 '${sheet.sheetName}': 열 이름 행을 찾지 못했습니다 — 시트를 건너뛰었습니다. 맨 앞 ${SheetResolver.HEADER_SEARCH_ROWS}행 안에 첫 열이 '$expectedFirstHeader'인 행이 있어야 합니다(표 위의 제목·메모·빈 줄이 그보다 길면 줄이세요)."
            )
            return null
        }
        return if (checkHeaderOrReport(sheet, headerRow, expectedFirstHeader, result)) headerRow else null
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
    /**
     * 예약 시트 해석의 **단일 판정** — 판정 원문은 [SheetResolver]로 내렸다(B-217).
     * 삭제 가드(`canRestore`) · 실제 조회(`findSheet`) · 미리보기(`analyze*`)가 같은 결과를 본다.
     */
    private fun resolveSpecSheet(workbook: Workbook, spec: SheetSpec): Sheet? =
        SheetResolver.resolveSpecSheet(workbook, spec)

    /** 캐릭터 시트의 지문 — 판정 원문은 [SheetResolver](B-217). */
    private fun looksLikeCharacterSheet(sheet: Sheet): Boolean =
        SheetResolver.looksLikeCharacterSheet(sheet)

    /**
     * 미리보기(`analyze*`)의 시트 조회 — [findSheet]와 **같은 답**이다(경고·소비 기록만 없다).
     *
     * 종전에는 미리보기 열아홉 자리가 `workbook.getSheet(정확명)`으로 이 판정을 우회해,
     * 예약명을 빼앗긴 레거시 백업에서 가져오기는 밀린 시트를 되찾아 읽는데 미리보기는
     * "시트 없음"이라 말했고, 이름을 차지한 캐릭터 시트를 데이터 시트로 읽어 엉뚱한 건수를
     * 예고했다(B-217 · R-33). `check_restore_preview_parity.sh` ④가 우회의 재발을 막는다.
     */
    private fun sheetForAnalysis(workbook: Workbook, spec: SheetSpec): Sheet? =
        SheetResolver.sheetForRead(workbook, spec)

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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val c = UniverseCols(cols, spec.firstColumnHeader)
        val nowMillis = System.currentTimeMillis()

        // Build code index for duplicate detection within file
        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33) — F1-A(열 없음 = 기존 유지)도 그 안에 있다.
                val r = readUniverseRow(row, c, "세계관 행 ${excelRow(i)}", result)
                val name = r.name
                if (name.isBlank()) {
                    // 미리보기와 **같은 판정**이다(R-33) — 예고한 '건너뜀'이 여기서 실현된다.
                    if (rowCarriesValue(row)) {
                        result.skippedRows++
                        result.errors.add(
                            "세계관 행 ${excelRow(i)}: '세계관명' 칸이 비어 있어 행을 건너뛰었습니다 — 필수 항목입니다"
                        )
                    }
                    continue
                }

                val descriptionFromExcel: String? = r.description
                val code = r.code
                val imagePathsFromExcel: String? = r.imagePaths
                val imageCharCode = r.imageCharCode
                val imageNovelCode = r.imageNovelCode

                // Duplicate code detection within file (last-write-wins)
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("세계관: 코드 '$code'가 행 ${excelRow(prevRow)} 과 행 ${excelRow(i)} 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Code-first matching (Sprint A strict rule)
                val existing: Universe?
                val matchedByName: Boolean
                if (code.isNotBlank()) {
                    val byCode = universeByCode(code)
                    if (byCode != null) {
                        existing = byCode
                        matchedByName = false
                    } else {
                        // F1-C: 코드가 있으나 DB에 없음 → 조용히 신규 생성하지 않고 이름 폴백 + 경고
                        val byName = universeByName(name)
                        if (byName != null) {
                            existing = byName
                            matchedByName = true
                            result.nameBasedMappings++
                            // 세계관 이름 열이 없는 참조(프리셋 필드 필터의 universeCode)가 이 결정을 따라가게 한다
                            universeCodeAliases.note(code, byName.code)
                            result.warnings.add("세계관 행 ${excelRow(i)}: 코드 '$code'를 찾지 못해 이름 '$name'으로 매칭함 — 의도한 새 세계관이면 코드를 비우세요")
                        } else {
                            existing = null
                            matchedByName = false
                            warnCreatedNewByCode("universes", "세계관 행 ${excelRow(i)}: 코드 '$code'가 기존 세계관에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                        }
                    }
                } else {
                    existing = universeByName(name)
                    matchedByName = existing != null
                    if (matchedByName) {
                        result.nameBasedMappings++
                        result.warnings.add("세계관 행 ${excelRow(i)}: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("세계관 행 ${excelRow(i)}: 행 ${excelRow(prevRow)} 과 같은 항목('$name')을 다시 덮어씀 — 별개의 세계관으로 넣으려면 '코드' 칸을 비우고 이름을 다르게 한 뒤 다시 가져오세요")
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
                    // 이름·코드가 바뀌었을 수 있다 — 옛 키를 끊어야 뒷 시트가 SQL과 같은 답을 본다(B-210).
                    rememberUniverse(mergedUniverse)
                    if (imageCharCode != null) deferredUniverseImageCharCodes[existing.id] = DeferredImageRef(imageCharCode, existing.imageCharacterId)
                    if (imageNovelCode != null) deferredUniverseImageNovelCodes[existing.id] = DeferredImageRef(imageNovelCode, existing.imageNovelId)
                    // '갱신'은 실제로 바뀌는 행이다(확정 7-2 — B-111). 이미지 연동 열은 지연 해석
                    // 몫이라 여기서는 null이고, 그대로 비교하면 연동 있는 항목이 무편집 파일에서도
                    // '갱신'으로 계수된다(미리보기는 순효과로 '동일'이라 말한 그 행 — B-217).
                    // 이미지 축을 중립화해 세고, 순효과 변화는 지연 해석 자리가 승격한다.
                    val changedBeyondImages = mergedUniverse !=
                        existing.copy(imageCharacterId = mergedUniverse.imageCharacterId, imageNovelId = mergedUniverse.imageNovelId)
                    if (changedBeyondImages) result.updatedUniverses++ else {
                        result.unchangedRows++
                        universesCountedUnchanged.add(existing.id)
                    }
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newUniverse = newUniverseFrom(r, newCode, i, nowMillis)
                    val newId = db.universeDao().insert(newUniverse)
                    // 방금 만든 세계관을 곧바로 읽히게 한다 — 작품·필드 정의·세력 시트가
                    // 이 코드·이름으로 소속을 찾는다.
                    rememberUniverse(newUniverse.copy(id = newId))
                    if (imageCharCode != null) deferredUniverseImageCharCodes[newId] = DeferredImageRef(imageCharCode, originalId = null)
                    if (imageNovelCode != null) deferredUniverseImageNovelCodes[newId] = DeferredImageRef(imageNovelCode, originalId = null)
                    entitySeen[newId] = i
                    result.newUniverses++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세계관 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "세계관", sheet.lastRowNum, totalRows)
    }

    // ── 작품 가져오기 ──

    private suspend fun importNovels(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = novelSpec(emptyList())
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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
        // 정의 없는 "필드:" 열의 값 유실 고지용 — (헤더명) 단위로 1회만 경고
        val droppedNovelFieldHeaders = mutableSetOf<String>()
        // 열 수집은 연표 시트·미리보기와 **같은 함수**다([collectEntityFieldColumns] · B-187).
        val novelFieldColumns = collectEntityFieldColumns(headerRow, allNovelFields, universeNamesById, result, "작품")
        // 필드 열이 있을 때만 기존 값을 싣는다 — **표를 한 번** 읽고(R-53) 그 뒤의 물음에
        // 메모리로 답한다. 이것이 없던 동안 이 자리는 *비움*을 세지 못했고(오버플로 시트 쪽은
        // 세는데) 아무것도 바뀌지 않는 행에서도 삭제+삽입이 그대로 돌았다(B-187).
        val novelValues = if (novelFieldColumns.isEmpty()) null else FieldValueOverlay.of(
            db.novelFieldValueDao().getAllValuesList()
        ) { Triple(it.novelId, it.fieldDefinitionId, it.value) }

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33) — F1-A(열 없음 = 기존 유지)도 그 안에 있다.
                val r = readNovelRow(row, nc, "작품 행 ${excelRow(i)}", result)
                val title = r.title
                if (title.isBlank()) {
                    // 미리보기와 **같은 판정**이다(R-33).
                    if (rowCarriesValue(row)) {
                        result.skippedRows++
                        result.errors.add(
                            "작품 행 ${excelRow(i)}: '제목' 칸이 비어 있어 행을 건너뛰었습니다 — 필수 항목입니다"
                        )
                    }
                    continue
                }

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
                        result.warnings.add("작품: 코드 '$code'가 행 ${excelRow(prevRow)} 과 행 ${excelRow(i)} 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Resolve universe: code-first, then name.
                // 괄호 필수 — 괄호 없이 쓰면 엘비스가 else 가지(null)에만 붙어 코드 미해석 시 이름 폴백이 죽는다.
                val universeColumnPresent = r.universeColumnPresent
                val universeRefProvided = r.universeRefProvided
                val universeId = universeByCodeOrName(universeCode, universeName)?.id
                if (universeRefProvided && universeId == null) {
                    result.warnings.add("작품 행 ${excelRow(i)}: 세계관 '${universeName.ifBlank { universeCode }}'을(를) 찾을 수 없음 — 기존 작품은 소속 유지, 새 작품은 세계관 미지정으로 생성")
                }

                // Code-first matching (Sprint A) + F1-C: 미지 코드 → 자연키 폴백 + 경고
                val existing: Novel?
                if (code.isNotBlank()) {
                    val byCode = novelByCode(code)
                    if (byCode != null) {
                        existing = byCode
                    } else {
                        val byTitle = novelByTitle(title, universeId)
                        if (byTitle != null) {
                            existing = byTitle
                            result.nameBasedMappings++
                            result.warnings.add("작품 행 ${excelRow(i)}: 코드 '$code'를 찾지 못해 제목 '$title'으로 매칭함 — 의도한 새 작품이면 코드를 비우세요")
                        } else {
                            existing = null
                            warnCreatedNewByCode("novels", "작품 행 ${excelRow(i)}: 코드 '$code'가 기존 작품에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                        }
                    }
                } else {
                    // No code => fallback to title+universe
                    existing = novelByTitle(title, universeId)
                    if (existing != null) {
                        result.nameBasedMappings++
                        result.warnings.add("작품 행 ${excelRow(i)}: 이름 기반 매칭 ('$title') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    // 이 행이 확정하는 소속 — 아래 update와 필드값 적용이 **같은 값**을 봐야 한다.
                    // 두 곳에 따로 쓰면 한쪽만 고쳐질 때 방금 옮긴 작품에 옛 세계관 필드가 붙는다.
                    val effectiveUniverseId = effectiveNovelUniverseId(existing, r, universeId)
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("작품 행 ${excelRow(i)}: 행 ${excelRow(prevRow)} 과 같은 항목('$title')을 다시 덮어씀 — 별개의 작품으로 넣으려면 '코드' 칸을 비우고 제목을 다르게 한 뒤 다시 가져오세요")
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
                    if (novelImageCharCode != null) deferredNovelImageCharCodes[existing.id] = DeferredImageRef(novelImageCharCode, existing.imageCharacterId)
                    // 이미지 축 중립 계수 — 세계관 쪽과 같은 이유(확정 7-2 · B-217. 순효과 변화는
                    // 지연 해석 자리가 승격한다).
                    val changedBeyondImages = mergedNovel != existing.copy(imageCharacterId = mergedNovel.imageCharacterId)
                    if (changedBeyondImages) result.updatedNovels++ else {
                        result.unchangedRows++
                        novelsCountedUnchanged.add(existing.id)
                    }
                    // 소속이 이 행에서 바뀌었으면 **새 소속**의 필드가 적용 대상이다(위 val과 같은 값).
                    applyNovelFieldColumns(
                        row, existing.id, effectiveUniverseId,
                        novelFieldColumns, allNovelFields, universeIdsByName,
                        droppedNovelFieldHeaders, result, novelValues
                    )
                } else {
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newNovel = newNovelFrom(r, newCode, universeId, i, nowMillis)
                    val newId = db.novelDao().insert(newNovel)
                    // 방금 만든 작품을 곧바로 읽히게 한다 — 캐릭터 시트가 이 코드로 소속을 찾는다.
                    rememberNovel(newNovel.copy(id = newId))
                    if (novelImageCharCode != null) deferredNovelImageCharCodes[newId] = DeferredImageRef(novelImageCharCode, originalId = null)
                    entitySeen[newId] = i
                    result.newNovels++
                    applyNovelFieldColumns(
                        row, newId, universeId,
                        novelFieldColumns, allNovelFields, universeIdsByName,
                        droppedNovelFieldHeaders, result, novelValues
                    )
                    // 세계관 미해석 경고는 위(해석 지점)에서 신규/기존 공통으로 1회 보고한다
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("작품 행 ${excelRow(i)}: ${e.message}")
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
        result: ImportResult,
        /** 기존 값의 겹 — 처분을 [FieldValueCellPlan]으로 가르고 *비움*을 센다(B-187). */
        values: FieldValueOverlay?
    ) {
        if (columns.isEmpty()) return
        val newValues = mutableListOf<com.novelcharacter.app.data.model.NovelFieldValue>()
        val resolvedFieldIds = mutableListOf<Long>()
        // 이 행이 실제로 표를 건드리는가 — 전부 '동일'·'없음'이면 삭제+삽입을 통째로 건너뛴다.
        var touchesTable = false
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
            // 처분은 오버플로 시트와 **같은 함수**가 정한다(R-33) — 이 자리가 세지 못하던
            // *비움*이 그래서 세어진다.
            when (FieldValueCellPlan.effectOf(cellValue, values?.get(novelId, fieldDef.id))) {
                FieldValueCellEffect.NEW, FieldValueCellEffect.UPDATE -> {
                    touchesTable = true
                    values?.put(novelId, fieldDef.id, cellValue)
                }
                FieldValueCellEffect.CLEAR -> {
                    touchesTable = true
                    values?.remove(novelId, fieldDef.id)
                    result.clearedFields++
                }
                FieldValueCellEffect.UNCHANGED, FieldValueCellEffect.NONE -> Unit
            }
            if (cellValue.isNotBlank()) {
                newValues.add(
                    com.novelcharacter.app.data.model.NovelFieldValue(
                        novelId = novelId, fieldDefinitionId = fieldDef.id, value = cellValue
                    )
                )
            }
        }
        // **아무것도 바뀌지 않으면 쓰지 않는다.** 겹을 못 받은 경우(구경로)에는 종전대로 언제나 쓴다 —
        // 모르면서 건너뛰는 것이 아니라, 알 때만 건너뛴다.
        val mustWrite = values == null || touchesTable
        if (mustWrite && (resolvedFieldIds.isNotEmpty() || newValues.isNotEmpty())) {
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
        // 이 한 번의 읽기가 총계와 색인 둘 다를 먹인다 (B-236과 같은 처방 · B-252).
        val allTemplates = dao.getAllList()
        val existingTotal = allTemplates.size
        val sheet = resolveSpecSheet(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) {
            return CategoryAnalysis("defaultFields", label, 0, 0, 0, 0, existingTotal)
        }
        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader)
            ?: return CategoryAnalysis("defaultFields", label, 0, 0, 0, 0, existingTotal)

        val c = DefaultFieldCols(resolveHeaderColumns(headerRow))

        // 해석·자리 판정 둘 다 **색인 하나**를 지난다 (B-252) — 짝인 가져오기와 같은 함수다(R-33).
        // 이 시트가 앞서 만든 템플릿도 여기 `remember`로 실린다(B-233): 가져오기 쪽은 같은
        // 트랜잭션의 직전 insert가 그대로 보이므로 그것에 해당한다.
        val templates = DefaultFieldTemplateIndexes(allTemplates)
        val now = System.currentTimeMillis()
        var total = 0; var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skipped = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            // 읽기·해석·판정 셋 다 가져오기와 **같은 함수**다 (R-33).
            val r = readDefaultFieldRow(row, c, "기본 필드 행 ${excelRow(i)}", result = null)
            if (r.key.isBlank() && r.name.isBlank()) continue
            total++
            if (r.key.isBlank() || r.name.isBlank()) { skipped++; continue }
            // 게이트는 가져오기와 **같은 함수**다(R-33) — 미리보기가 예고한 수와 실제가 갈리면
            // 그 자체가 결함이다.
            if (r.type != null && !isKnownFieldType(r.type)) { skipped++; continue }
            // 해석 사다리는 가져오기와 **같은 함수**다(코드 → 자리). 앞 행이 만든 것은 색인에
            // 실려 있어 자연히 먼저 잡힌다.
            val existing = templates.resolve(r.code, r.entityType, r.key)
            if (existing == null) {
                newCount++
                // 순서 기본값은 표의 현재 최댓값 다음이다 — 미리보기는 쓰지 않으므로 그 값이
                // 무엇이든 뒤 행의 매칭(코드·자리)에 쓰이지 않는다. 0으로 둔다.
                templates.remember(
                    newDefaultFieldTemplateFrom(r, r.code, displayOrder = r.displayOrder ?: 0, now = now)
                        .copy(id = previewIds.mint())
                )
            } else {
                // **자리 충돌 게이트도 가져오기와 같은 함수다**(B-252) — 자리를 옮기려다
                // 되돌려지는 행을 '변경'이라 예고하던 자리다.
                val kept = guardDefaultFieldSlot(
                    existing, mergeDefaultFieldTemplate(existing, r), templates::slotOwner
                )
                if (kept != existing) {
                    updateCount++
                    // 갱신된 값도 색인에 되돌려 둔다 — 같은 템플릿을 가리키는 뒤 행은 이 결과
                    // 위에서 판정된다(파일 안에서 키를 바꾼 뒤 옛 키 행이 또 나오는 경우).
                    templates.remember(kept)
                } else unchangedCount++
            }
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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return
        reportUnknownColumns(headerRow, spec, result)

        val c = DefaultFieldCols(resolveHeaderColumns(headerRow, result, spec.sheetName))
        val dao = db.defaultFieldTemplateDao()
        // 정체 해석·자리 판정·코드 유일성·순서 기본값이 **표 한 번 읽기** 위에 선다 (B-252) —
        // 짝인 미리보기와 같은 색인·같은 게이트다(R-33). 종전에는 행마다 조회가 둘·셋이었다.
        // 이 루프가 이 표의 **유일한 쓰기 경로**라, `remember`만 빠뜨리지 않으면 색인이 낡지 않는다.
        val templates = DefaultFieldTemplateIndexes(dao.getAllList())
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val r = readDefaultFieldRow(row, c, "기본 필드 행 ${excelRow(i)}", result)
            // 둘 다 비면 빈 행이라 조용히 넘긴다. 하나만 비면 사람이 적다 만 것이라 말한다
            // ('등급 체계' 시트와 같은 관례 — 무음 폐기와 구별한다).
            if (r.key.isBlank() && r.name.isBlank()) continue
            if (r.key.isBlank() || r.name.isBlank()) {
                result.skippedRows++
                result.errors.add("기본 필드 행 ${excelRow(i)}: 필드키·필드명은 필수입니다")
                continue
            }
            // **타입 게이트** — 짝인 '필드 정의' 시트가 이미 갖고 있던 것이 이 시트에만 없었다.
            // 두 시트는 같은 어휘를 쓴다(같은 드롭다운·같은 필수 표식 — 시험이 그 동일성을
            // 못박는다). 모르는 글자가 통과하면 그것이 **템플릿**에 들어가고, 템플릿은
            // 세계관을 만들 때마다 자동으로 심기므로 **이후 만들어지는 모든 세계관**에
            // 물질화된다. 그 뒤로는 폼이 맨 텍스트로 그리고, 값 라이브러리에서 빠지고,
            // 다시 내보낸 파일을 신규 기기에서 들이면 '필드 정의' 게이트가 그 행을 통째로
            // 건너뛴다 — 왕복이 비대칭이 된다(개발 의도 4번).
            // `null`은 *파일이 말하지 않았다*이고 그때는 종전 그대로다(새 행은 TEXT, 기존 행은
            // 그 값을 유지) — **없는 열과 틀린 값은 다르다**(R-36). 말했는데 모르는 글자일 때만 막는다.
            val declaredType = r.type
            if (declaredType != null && !isKnownFieldType(declaredType)) {
                result.skippedRows++
                result.errors.add(unknownFieldTypeMessage("기본 필드 행 ${excelRow(i)}", declaredType))
                continue
            }
            val existing = templates.resolve(r.code, r.entityType, r.key)

            if (existing != null) {
                val merged = mergeDefaultFieldTemplate(existing, r)
                // 자리를 옮기는 편집이 남의 자리와 부딪치면 되돌린다 — 유니크 (대상, 필드키).
                // **판정은 미리보기와 같은 함수다**(R-33 · B-252).
                //
                // **`merged != existing` 조건 밖에서 센다.** 종전에는 이 블록 전체가 그 if
                // 안에 있어, 파일이 DB와 애초에 같은 행(merged == existing)이 '갱신'에도
                // '동일'에도 안 남았다 — 결과 창의 "바뀐 것 없음 (N행이 파일과 이미 같습니다)"
                // 수가 그만큼 적었고, 이 시트만 든 파일은 그 분기(B-111)가 죽어 "데이터 없음"으로
                // 보고됐다. 짝인 analyzeDefaultFieldTemplates는 조건 밖에서 갈라 세므로 미리보기와
                // 결과가 갈렸다(R-33). 나머지 열여덟 시트가 이미 쓰는 관용구로 되돌린다.
                val kept = guardDefaultFieldSlot(existing, merged, templates::slotOwner)
                if (kept != merged) {
                    result.warnings.add(
                        "기본 필드 행 ${excelRow(i)}: '${merged.key}'(${merged.entityType}) 자리를 이미 다른 " +
                            "기본 필드가 쓰고 있어 키·대상은 바꾸지 않았습니다"
                    )
                }
                if (kept != existing) {
                    dao.update(kept)
                    // 방금 쓴 값을 색인에 되돌려 둔다 — 뒤 행이 이 결과 위에서 판정된다.
                    templates.remember(kept)
                    result.updatedDefaultFields++
                } else result.unchangedRows++
            } else {
                // 코드가 이미 남의 템플릿과 겹치면 재발급한다(전역 유니크 — 정체를 빼앗지 않는다).
                val wanted = r.code.takeIf { it.isNotBlank() }
                val safeCode = wanted?.takeIf { templates.codeOwner(it) == null }
                    ?: com.novelcharacter.app.data.model.generateEntityCode().also {
                        if (wanted != null) result.newCodesGenerated++
                    }
                // 새 행에서는 **null이 곧 기본값**이다 — 지킬 기존 값이 없다(R-36 후반부).
                val created = newDefaultFieldTemplateFrom(
                    r, safeCode,
                    displayOrder = r.displayOrder ?: ((templates.maxOrder(r.entityType) ?: -1) + 1),
                    now = System.currentTimeMillis()
                )
                // 방금 만든 것을 **곧바로 읽히게** 한다 — 같은 파일의 뒷 행이 코드·자리로 이것을
                // 찾는다(빠뜨리면 *있는 것을 없다고* 보고 유니크 색인에 부딪친다).
                templates.remember(created.copy(id = dao.insert(created)))
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
        // 내보내기가 싣는 생성일을 되읽는다 — 안 읽으면 신규 기기 복원마다 생성일이 현재
        // 시각으로 갈려 왕복이 멱등이 아니게 된다(다른 전 시트는 되읽어 보존한다).
        val createdAt = cols["생성일"] ?: -1
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
        val createdAt: Long?,
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
        createdAt = readCreatedAtCell(row, c.createdAt, ctx, result),
        aiColumnPresent = c.aiSuggest >= 0,
        aiCellText = getCellString(row, c.aiSuggest),
        descriptionColumnPresent = c.description >= 0,
        descriptionCellText = getCellString(row, c.description)
    )

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
    /**
     * [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 기본 필드 템플릿(R-33 셋째 · B-233).
     * 새 행에서는 **null이 곧 기본값**이다 — 지킬 기존 값이 없다(R-36 후반부).
     * [displayOrder]를 따로 받는 것은 그 기본값이 표의 현재 최댓값이라 순수하지 않기 때문이다.
     */
    private fun newDefaultFieldTemplateFrom(
        r: DefaultFieldRowValues, code: String, displayOrder: Int, now: Long
    ): DefaultFieldTemplate = DefaultFieldTemplate(
        key = r.key,
        name = r.name,
        type = r.type ?: FieldType.TEXT.name,
        config = resolveDefaultFieldConfig(r, existing = null),
        groupName = r.groupName ?: "기본 정보",
        displayOrder = displayOrder,
        isRequired = r.isRequired ?: false,
        entityType = r.entityType,
        code = code,
        createdAt = r.createdAt ?: now
    )

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
        entityType = r.entityType,
        createdAt = r.createdAt ?: existing.createdAt
    )

    // ── 등급 체계 가져오기 (U-1 — 필드 정의 직전: '등급체계' 열이 여기서 만든 체계를 찾는다) ──

    // 세는 자리 없음(행이 아니라 **무리**를 센다 — `groups.size + 미해석 무리`가 곧 전량이고
    //   무리마다 신규·변경·동일·건너뜀 중 하나로 반드시 세므로 *세기 전에 버리는* 자리가 없다)
    private suspend fun analyzeGradeSystems(workbook: Workbook, options: ExportOptions, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = gradeSystemSpec()
        val label = "등급 체계"
        val existingTotal = db.gradeSystemDao().getAllList().size
        val sheet = sheetForAnalysis(workbook, spec)
        if (sheet == null || sheet.lastRowNum < 1) return CategoryAnalysis("gradeSystems", label, 0, 0, 0, 0, existingTotal)
        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: return CategoryAnalysis("gradeSystems", label, 0, 0, 0, 0, existingTotal)

        var newCount = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        // 이 시트가 앞서 만든 체계 — 해석이 DAO 직접 조회라 색인이 없어 여기에 겹쳐 둔다(B-233).
        val previewCreated = mutableListOf<com.novelcharacter.app.data.model.GradeSystem>()
        val scan = collectGradeSystemRows(sheet, headerRow, result = null)
        val groups = scan.groups
        // B-102 ⓑ: 세계관 미해석 무리 — '세계관'을 함께 가져오면 그것이 먼저 생기므로 '신규'가
        // 맞고, 빼놓았으면 가져오기가 행을 거부한다. 종전에는 무리 자체가 계수 밖이라 신규 기기
        // 복원 미리보기가 체계 전부에 대해 아무 말도 하지 않았다(B-217).
        if (options.universes) newCount += scan.unresolvedUniverseGroups else skippedCount += scan.unresolvedUniverseGroups
        for (group in groups) {
            // 유효한 등급 행이 하나도 없으면 가져오기가 무리를 통째로 거부한다 — '신규'가 아니다(B-102 ⓑ).
            if (com.novelcharacter.app.data.model.GradeSystemRef.gradesFromJson(group.gradesJson()).isEmpty()) {
                skippedCount++
                continue
            }
            // 앞 무리가 만든 체계를 **먼저** 본다 — 가져오기 쪽은 같은 트랜잭션의 직전 insert가
            // 그대로 보이므로, 한 파일에 같은 (세계관, 이름)이 두 무리 있으면 둘째가 첫째와
            // 매칭된다(B-233). 해석 사다리는 가져오기와 같다(코드 → 세계관+이름).
            val existing = previewCreated.firstOrNull { g ->
                (group.code.isNotBlank() && g.code == group.code && g.universeId == group.universeId) ||
                    (g.universeId == group.universeId && g.name == group.name)
            } ?: resolveGradeSystem(group)
            if (existing == null) {
                newCount++
                previewCreated.add(
                    newGradeSystemFrom(group, group.gradesJson(), group.code, displayOrder = previewCreated.size)
                        .copy(id = previewIds.mint())
                )
                continue
            }
            // 적용도 가져오기와 **같은 함수**다(규약 R-33) — 이름 충돌 판정까지 같이 본다.
            // **충돌 판정도 이 파일이 만든 것을 본다**(B-233 콜드 검토): 가져오기 쪽 `getByUniverseAndName`은
            // 같은 트랜잭션의 직전 insert를 보므로, 이 파일이 방금 만든 이름으로 개명하려 들면
            // **가져오기는 이름을 지키는데** 미리보기만 DB를 못 봐 '변경'이라 예고한다.
            val rename = group.name != existing.name &&
                previewCreated.none { it.universeId == group.universeId && it.name == group.name } &&
                db.gradeSystemDao().getByUniverseAndName(group.universeId, group.name) == null
            val merged = mergeGradeSystem(existing, group.name, group.gradesJson(), rename)
            if (merged != existing) updateCount++ else unchangedCount++
        }
        reportProgress(onProgress, "등급 체계 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("gradeSystems", label, groups.size + scan.unresolvedUniverseGroups, newCount, updateCount, unchangedCount, existingTotal, skippedCount)
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

    /** [collectGradeSystemRows]의 산출 — 무리 목록 + 세계관 미해석 무리 수(미리보기의 B-102 ⓑ 계수용). */
    private class GradeSheetScan(val groups: List<GradeSystemGroup>, val unresolvedUniverseGroups: Int)

    /**
     * 시트를 (세계관, 코드|체계명) 무리로 접는다. 세계관을 못 찾는 행은 건너뛰고 [result]에
     * 보고한다(분석 경로는 result가 null이라 개수만 영향을 받는다 — 가져오기와 같은 판정).
     * 미해석 행도 같은 키 규칙으로 접어 **무리 수**를 센다 — 미리보기가 '가져오기가 만들
     * 체계 수'를 예고하는 데 쓴다(행 수로 세면 등급 행 수만큼 부푼다).
     */
    private suspend fun collectGradeSystemRows(
        sheet: Sheet,
        headerRow: Row,
        result: ImportResult?
    ): GradeSheetScan {
        val cols = resolveHeaderColumns(headerRow)
        val universeColIndex = cols["세계관"] ?: 0
        val nameColIndex = cols["체계명"] ?: 1
        val labelColIndex = cols["등급"] ?: 2
        val valueColIndex = cols["기본숫자"] ?: 3
        val universeCodeColIndex = cols["세계관코드"] ?: -1
        val codeColIndex = cols["코드"] ?: -1

        val groups = LinkedHashMap<String, GradeSystemGroup>()
        val unresolvedGroupKeys = mutableSetOf<String>()
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val universeName = getCellString(row, universeColIndex)
            val systemName = getCellString(row, nameColIndex)
            if (universeName.isBlank() && systemName.isBlank()) continue
            if (universeName.isBlank() || systemName.isBlank()) {
                result?.let {
                    it.skippedRows++
                    it.errors.add("등급 체계 행 ${excelRow(i)}: 세계관·체계명은 필수입니다")
                }
                continue
            }
            val universeCode = if (universeCodeColIndex >= 0) getCellString(row, universeCodeColIndex) else ""
            val code = if (codeColIndex >= 0) getCellString(row, codeColIndex) else ""
            val universe = universeByCode(universeCode) ?: universeByName(universeName)
            if (universe == null) {
                // 무리 키 규칙은 아래와 같다 — 세계관 id가 없으므로 이름으로 대신한다.
                unresolvedGroupKeys.add(if (code.isNotBlank()) "code:$code" else "name:$universeName:$systemName")
                result?.let {
                    it.skippedRows++
                    it.errors.add("등급 체계 행 ${excelRow(i)}: 세계관 '${universeName}'을(를) 찾을 수 없음")
                }
                continue
            }
            // 무리 키는 코드 우선, 없으면 (세계관, 체계명) — 다른 시트의 매칭 규약과 같다.
            val key = if (code.isNotBlank()) "code:$code" else "name:${universe.id}:$systemName"
            val group = groups.getOrPut(key) {
                GradeSystemGroup(universe.id, universe.name, systemName, code, i)
            }
            group.rows.add(getCellString(row, labelColIndex) to getCellString(row, valueColIndex))
        }
        return GradeSheetScan(groups.values.toList(), unresolvedGroupKeys.size)
    }

    /** 무리 → 기존 체계. 코드 우선(같은 세계관일 때만), 다음 (세계관, 이름). */
    /**
     * [newUniverseFrom]과 같은 규약 — 이 무리가 **만들** 등급 체계(R-33 셋째 · B-233).
     * [displayOrder]를 따로 받는 것은 그 값이 *이번 가져오기가 지금까지 손댄 체계 수*라
     * 순수하지 않기 때문이다.
     */
    private fun newGradeSystemFrom(
        group: GradeSystemGroup, gradesJson: String, code: String, displayOrder: Int
    ): com.novelcharacter.app.data.model.GradeSystem =
        com.novelcharacter.app.data.model.GradeSystem(
            universeId = group.universeId,
            name = group.name,
            gradesJson = gradesJson,
            displayOrder = displayOrder,
            code = code
        )

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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return
        reportUnknownColumns(headerRow, spec, result)

        val repository = com.novelcharacter.app.data.repository.GradeSystemRepository(db)
        for (group in collectGradeSystemRows(sheet, headerRow, result).groups) {
            // 행 검증은 필드의 등급 표와 같은 규칙(GradeTable) — 유효 행만 반영하고, 문제는
            // 행 단위로 보고한다(무리 전체 거부는 관대 수용이 아니다).
            val outcome = com.novelcharacter.app.util.GradeTable.build(group.rows)
            for (problem in outcome.problems) {
                result.warnings.add(
                    "등급 체계 '${group.name}' (행 ${excelRow(group.firstRowNum)}부터): " + gradeProblemText(problem)
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
                    newGradeSystemFrom(group, gradesJson, safeCode, displayOrder = matchedGradeSystemIds.size)
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
        // 가져오기([importGradeSystems])가 읽은 **그 시트**의 존재를 물어야 한다 — 정확명만
        // 보면 밀린 시트를 읽어 매칭까지 해 놓고 "시트가 없다"며 정리를 건너뛴다(B-217).
        // 헤더 행은 0행이 아닐 수 있다(B-231 ⓑ) — 가져오기가 읽은 그 행을 같은 함수로 찾는다.
        val header = SheetResolver.sheetForRead(workbook, spec)
            ?.let { SheetResolver.locateHeader(it, spec.firstColumnHeader) }
        if (header == null) {
            result.warnings.add("백업에 '${spec.sheetName}' 시트가 없어 기존 등급 체계를 삭제하지 않고 유지했습니다 (덮어쓰기 제외)")
            return
        }
        if (matchedGradeSystemIds.isEmpty()) {
            result.warnings.add("덮어쓰기: '${spec.sheetName}' 시트에서 처리된 등급 체계가 하나도 없어 기존 체계를 삭제하지 않았습니다 — 위의 행 오류를 먼저 확인하세요")
            return
        }
        val repository = com.novelcharacter.app.data.repository.GradeSystemRepository(db)
        // **구역 근거를 필드 정의와 같은 함수로 묻는다**(`ScopedPrune`). 종전에는 전 세계관의
        // 체계를 떠서 매칭되지 않은 것을 전부 지웠다 — 세계관 하나만 담긴 부분 백업을
        // 덮어쓰기로 들이면 **말한 적도 없는 다른 세계관의 등급 체계가 통째로 사라졌다**
        // (참조하던 필드는 독자 표로 강등되어, 되돌리려면 표를 손으로 다시 만들어야 한다).
        // 바로 아래 필드 정의 정리는 B-130 이후 이미 이 판정을 쓰고 있었고, 그 짝만 빠져 있었다.
        val pruned = ScopedPrune.plan(
            existing, matchedGradeSystemIds, idOf = { it.id }, scopeOf = { it.universeId }
        )
        val stale = pruned.stale
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
        // 남긴 것도 반드시 알린다 — 필드 정의 정리와 같은 결(조용히 남기면 그 세계관만 옛
        // 체계가 살아 있는 상태를 일일이 확인해야만 알게 된다).
        if (pruned.protectedItems.isNotEmpty()) {
            val universeNames = db.universeDao().getAllUniversesList().associate { it.id to it.name }
            val scopeLabels = pruned.protectedItems.map { it.universeId }.distinct()
                .joinToString(", ") { universeNames[it] ?: "세계관 #$it" }
            result.warnings.add(
                "덮어쓰기: '${spec.sheetName}' 시트가 다루지 않은 세계관($scopeLabels)의 등급 체계 " +
                "${pruned.protectedItems.size}개는 삭제하지 않고 유지했습니다 — 그 세계관의 행이 " +
                "백업에 하나도 없어 '지워라'인지 '말한 바 없음'인지 가릴 수 없습니다"
            )
        }
    }

    private suspend fun importFieldDefinitions(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = fieldDefinitionSpec(emptyList())
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readFieldDefRow(row, fdc, "필드 행 ${excelRow(i)}", result)
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
                        universeByCode(r.universeCode)
                    } else null)
                        ?: universeByName(universeName)
                    if (found == null) {
                        result.skippedRows++
                        result.errors.add("필드 정의 행 ${excelRow(i)}: 세계관 '${universeName}'을(를) 찾을 수 없음")
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
                    result.errors.add("필드 정의 행 ${excelRow(i)}: 필드 타입이 비어 있음 (허용: ${FieldType.entries.joinToString { it.name }})")
                    continue
                }
                if (!isKnownFieldType(type)) {
                    result.skippedRows++
                    result.errors.add(unknownFieldTypeMessage("필드 정의 행 ${excelRow(i)}", type))
                    continue
                }
                // F4: 설정(JSON)이 손상(절단·구문 오류)됐으면 조용히 넘기지 않고 경고 (필드 동작 무력화 방지)
                // 열이 없으면(null) 잴 것이 없다 — 그 파일은 설정을 말하지 않았다(B-162).
                if (r.config != null && r.config != "{}" && !isValidJson(r.config)) {
                    result.warnings.add("필드 정의 행 ${excelRow(i)}: 필드 '$name'의 설정(JSON)이 올바른 형식이 아닙니다(절단·오타 가능) — 그대로 저장되나 해당 기능이 동작하지 않을 수 있습니다")
                }
                // 없앤 설정이 담긴 옛 파일을 조용히 삼키지 않는다(P-5 확정 — 한 줄 고지).
                if (r.config != null) {
                    val unused = BodyAnalysisConfig.unusedKeysIn(r.config)
                    if (unused.isNotEmpty()) {
                        unusedConfigFields++
                        for (k in unused) unusedConfigKeys[k] = (unusedConfigKeys[k] ?: 0) + 1
                    }
                }
                // (`groupName`은 여기서 읽지 않는다 — 적용은 `newFieldDefinitionFrom`·
                //  `mergeFieldDefinition`이 하고, 그 둘이 R-36의 null 갈래를 든다. B-223에서
                //  아무도 읽지 않는 지역 변수였음을 확인하고 걷었다.)
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
                        result.warnings.add("필드 정의 행 ${excelRow(i)}: 행 ${excelRow(prevRow)} 과 같은 항목('$name')을 다시 덮어씀 — 별개의 필드로 넣으려면 '필드키'를 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    if (existing.type != type && type.isNotBlank()) {
                        result.warnings.add("필드 정의 행 ${excelRow(i)}: 필드 '$name'의 타입이 '${existing.type}'에서 '$type'(으)로 변경됨 — 기존 값 호환성을 확인하세요")
                    }
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedFieldDef = mergeFieldDefinition(existing, r, mergedConfig)
                    db.fieldDefinitionDao().update(mergedFieldDef)
                    matchedFieldDefinitionIds.add(existing.id)
                    if (mergedFieldDef != existing) result.updatedFields++ else result.unchangedRows++
                } else {
                    // 전역키 보증(위 `existing` 선조회 — universe가 null이면 getGlobalFieldByKey,
                    // 아니면 getFieldByKey다. **가져오기는 전역 구역에 쓰는 둘째 경로이고**,
                    // 그 구역에서는 유니크 색인이 NULL을 통과시키므로 이 선조회가 유일한 방어다.
                    // 같은 파일 안 같은 key의 둘째 행도 이 조회가 잡는다 — 직전 insert가
                    // 같은 트랜잭션에서 이미 보이기 때문이다)
                    val newId = db.fieldDefinitionDao()
                        .insert(newFieldDefinitionFrom(r, universe?.id, mergedConfig, i))
                    entitySeen[newId] = i
                    matchedFieldDefinitionIds.add(newId)
                    result.newFields++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 정의 행 ${excelRow(i)}: ${e.message}")
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
        is FormulaValidator.Problem.NonNumericKeys ->
            "${problem.keys.joinToString(", ")} 필드는 값이 수로 읽힌다는 보장이 없습니다 " +
                "(글자가 들어 있으면 그 자리는 0으로 계산됩니다)"
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
                    "필드 정의 행 ${excelRow(rowIndex)}: 필드 '$fieldName'의 등급 ${merge.droppedLabels.joinToString(", ")}은(는) " +
                        "체계 '${system.name}'에 없어 빠졌습니다 — 체계에 등급을 추가하거나 '등급체계' 칸을 비워 독자 표로 두세요"
                )
            }
            return merge.config
        }

        fun demoteWithNotice(pointer: String): String {
            result?.warnings?.add(
                "필드 정의 행 ${excelRow(rowIndex)}: 필드 '$fieldName'이(가) 가리키는 등급 체계 '$pointer'을(를) 찾을 수 없어 " +
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
        // 가져오기([importFieldDefinitions] — findSheet)가 읽은 **그 시트**의 존재를 물어야
        // 한다 — 정확명만 보면 밀린 시트에서 매칭해 놓고 정리를 건너뛴다(B-217).
        // 헤더 행은 0행이 아닐 수 있다(B-231 ⓑ) — 가져오기가 읽은 그 행을 같은 함수로 찾는다.
        val header = SheetResolver.sheetForRead(workbook, spec)
            ?.let { SheetResolver.locateHeader(it, spec.firstColumnHeader) }
        if (header == null) {
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
        // **`대상` 열이 없으면 그 파일은 캐릭터 외의 종류를 말하지 않은 것이다**(R-36) —
        // 모든 행이 캐릭터로 읽히므로 사건·작품 정의는 원리적으로 매칭될 수 없다.
        // 그때까지 세계관 하나를 구역으로 보면, 캐릭터 행이 매칭됐다는 이유로 그 세계관의
        // 사건·작품 정의가 값과 함께 CASCADE로 사라진다.
        val entityTypeStated = resolveHeaderColumns(header.row).containsKey("대상")
        val outcome = FieldDefinitionPrune.plan(
            db.fieldDefinitionDao().getAllFieldsAllTypes(), matchedFieldDefinitionIds, entityTypeStated
        )
        // 인앱 삭제와 **같은 함수**로 지운다 — 그래야 휴지통 스냅샷과 이력 손질 범위가
        // 한 벌이 된다(R-33). 종전에는 여기만 DAO를 직접 불러 값·엔트리·이력이
        // 아무 고지도 없이 영구 소멸했다.
        val fieldTrash = trashForImport()
        val fieldRepository = UniverseRepository(
            db, db.universeDao(), db.fieldDefinitionDao(), db.novelDao()
        )
        for (field in outcome.stale) {
            fieldRepository.deleteField(field, fieldTrash)
        }
        if (outcome.stale.isNotEmpty()) {
            val names = outcome.stale.take(5).joinToString(", ") { it.name }
            val more = if (outcome.stale.size > 5) " 외 ${outcome.stale.size - 5}개" else ""
            result.warnings.add("덮어쓰기: 백업에 없는 필드 정의 ${outcome.stale.size}개($names$more)를 관련 필드값과 함께 삭제했습니다 — 휴지통에서 복구할 수 있습니다")
        }
        // 남긴 것도 반드시 알린다 — 조용히 남기면 사용자는 덮어쓰기가 끝난 줄 알고,
        // 그 구역만 옛 정의가 살아 있는 상태를 일일이 확인해야만 알게 된다(원칙 04).
        if (outcome.protectedFields.isNotEmpty()) {
            val universeNames = db.universeDao().getAllUniversesList().associate { it.id to it.name }
            // 구역은 (세계관, 종류)다 — **어느 종류가 남았는지도 말한다**(그 자리를 찾아가야 한다).
            val scopeLabels = outcome.protectedScopes.joinToString(", ") { (scopeId, entityType) ->
                val universeLabel =
                    if (scopeId == null) FieldScopeCell.GLOBAL_LABEL else universeNames[scopeId] ?: "세계관 #$scopeId"
                // 종류는 그것이 구역의 일부일 때만 붙는다(`대상` 열이 없던 파일).
                if (entityType == null) universeLabel
                else "$universeLabel·${FieldValueSheetMapper.entityLabel(entityType)}"
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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val universeCol = cols["세계관"] ?: 0
        val keyCol = cols["필드키"] ?: 1
        val entityCol = cols["대상"] ?: -1
        // **'값'에는 위치 폴백을 두지 않는다** — 이 열이 엔트리의 정체다.
        // 종전 `?: 4`는 그 열을 지운 파일에서 4번 자리에 있던 '표시라벨'을 값으로 읽어,
        // 코드로 매칭된 기존 엔트리의 값을 **라벨로 갈아 끼웠다**(그 값을 참조하던 캐릭터
        // 필드값이 그 순간 라이브러리에서 미아가 된다). 없으면 소리 내어 건너뛴다.
        val valueCol = requiredCol(cols, "값", sheet.sheetName, result) ?: return
        val labelCol = cols["표시라벨"] ?: -1
        // 옛 머리('별칭(콤마구분)')는 [ExcelHeaderAliases]가 이 이름으로 접어 주므로 여기서
        // 다시 묻지 않는다. 종전의 `?: cols["별칭"]`은 **닿을 수 없는 가지였다** —
        // 맨 '별칭'은 별칭 표가 이미 '이명'으로 가져가서 이 이름으로는 결코 들어오지 않는다
        // (그 열은 '인식하지 못해 무시했습니다'로 보고된다 — 조용히 엉뚱한 열에 붙는 것보다 낫다).
        val aliasCol = cols[FieldValueSheetMapper.ALIAS_HEADER] ?: -1
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

        for (i in dataRows(sheet, headerRow)) {
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
                    result.warnings.add("필드 데이터 행 ${excelRow(i)}: 세계관 '$universeName'을(를) 찾을 수 없음")
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
                    result.warnings.add("필드 데이터 행 ${excelRow(i)}: 필드 키 '$fieldKey'(${imported.entityLabel ?: "캐릭터"})을(를) ${where}에서 찾을 수 없음")
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
                        "필드 데이터 행 ${excelRow(i)}: 출처 '${sourceCell.raw}'을(를) 인식할 수 없어 $outcome — " +
                        "AUTO·MANUAL·IMPORT·AI 중 하나로 고쳐 다시 가져오면 반영됩니다"
                    )
                }

                // 병합(이름 변경 별칭 보존 · 값 충돌 판정 · 충돌 별칭 제외)도 분석과 같은 함수다.
                // 여기서는 그 결과를 고지로 옮기기만 한다 — 무엇이 일어났는가는 mergeRow가 안다.
                val outcome = FieldValueSheetMapper.mergeRow(existing, fd.id, imported, siblings)
                val merged = outcome.entry
                if (merged == null) {
                    result.skippedRows++
                    result.warnings.add("필드 데이터 행 ${excelRow(i)}: 값이 비어 있어 건너뜀")
                    continue
                }
                var candidate = merged
                // 시트에서 값 이름이 바뀐 경우(코드 매칭): 인앱 이름변경과 달리 데이터 전파가 없으므로
                // 구 값을 별칭으로 보존해 재수확 시 중복 엔트리로 갈라지지 않게 한다 + 고지
                if (outcome.renamedFrom != null) {
                    result.warnings.add("필드 데이터 행 ${excelRow(i)}: 값 '${outcome.renamedFrom}' → '${candidate.value}' 이름 변경 감지 — 구 값을 별칭으로 보존 (캐릭터 값 일괄 변경은 인앱 라이브러리의 이름 변경 사용)")
                }
                // 값 이름이 다른 엔트리와 충돌(코드 매칭으로 이름이 바뀐 경우) — 거부 대신 그 행만 스킵
                if (outcome.valueTaken) {
                    result.skippedRows++
                    result.warnings.add("필드 데이터 행 ${excelRow(i)}: 값 '${candidate.value}'이(가) 이미 존재해 건너뜀")
                    continue
                }
                // 별칭 충돌은 해당 별칭만 제외 + 경고 (관대 수용)
                if (outcome.droppedAliases.isNotEmpty()) {
                    result.warnings.add("필드 데이터 행 ${excelRow(i)}: 별칭 ${outcome.droppedAliases.joinToString(", ")}이(가) 다른 값과 충돌해 제외됨")
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
                result.errors.add("필드 데이터 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "필드 데이터", sheet.lastRowNum, totalRows)
    }

    // ── 세계관별 캐릭터 시트 가져오기 ──

    // 미리보기 짝: analyzeCharacterSheet (세계관별 시트를 같은 함수가 훑는다)
    private suspend fun importCharacterSheets(workbook: Workbook, result: ImportResult, resolvedConflicts: Map<String, CharacterConflict>, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val universes = db.universeDao().getAllUniversesList()
        // 배정은 [UniverseSheetFinder]가 든다 — 계획 우선 + 소비 추적. 소비 집합은
        // [consumedCharacterSheetNames]를 그대로 먹인다: 미분류 조회([importUnclassifiedCharacters])와
        // '인식되지 않음' 경고 억제가 같은 집합을 보므로, 찾은 시트는 그 자리에서 소비된다.
        val sheetFinder = UniverseSheetFinder(workbook, universes, consumedCharacterSheetNames)

        if (universes.any { sanitizeSheetNameBase(it.name) == UNCLASSIFIED_SHEET_NAME }) {
            unclassifiedNameCollidesWithUniverse = true
        }
        for (universe in universes) {
            val sheet = sheetFinder.find(universe) ?: continue
            // 첫 행이 없는 세계관 시트도 소리 내어 건너뛴다 (B-231) — 종전에는 이 `?: continue`가
            // 캐릭터 시트 하나를 통째로, 경고 한 줄 없이 버렸다.
            val headerRow = headerRowOrReport(sheet, "이름", result) ?: continue

            val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
            // U-3: 겹치는 세계관이 **접미사 시트**를 잡았으면 신규 형식이 확정된 배치다
            // (내보내기는 예약명을 미분류 시트에 주고 세계관 시트를 '(2)'로 민다).
            // 판정은 [UniverseSheetFinder]가 이미 끝냈고, 여기서는 그 결과를 읽어 문구만 가른다.
            if (sanitizeSheetNameBase(universe.name) == UNCLASSIFIED_SHEET_NAME &&
                isSuffixedVariantOf(sheet.sheetName, UNCLASSIFIED_SHEET_NAME)
            ) {
                unclassifiedUniverseTookSuffixedSheet = true
            }
            // 헤더 검증을 통과해 실제로 처리한 세계관만 삭제 범위에 넣는다 (시트 없는 세계관은 건드리지 않음)
            importedCharacterSheetUniverseIds.add(universe.id)
            importCharacterRows(sheet, headerRow, universe, fields, result, resolvedConflicts, universe.name, onProgress, totalRows)
        }
    }

    // ── 미분류 캐릭터 가져오기 ──

    // 미리보기 짝: analyzeCharacters (그 함수가 미분류 시트도 함께 훑는다)
    private suspend fun importUnclassifiedCharacters(workbook: Workbook, result: ImportResult, resolvedConflicts: Map<String, CharacterConflict>, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val sheet = findUnclassifiedSheet(workbook, consumedCharacterSheetNames) ?: return
        val headerRow = headerRowOrReport(sheet, "이름", result) ?: return
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
    // 미리보기 짝: analyzeCharacterFieldValueSheet
    private suspend fun importCharacterFieldValues(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = characterFieldValueSpec()
        // F1-A: 시트가 없으면 기존 값 유지 (구버전 백업 호환) — 없는 것이 정상이라 경고하지 않는다.
        // **삭제 가드(canRestore)와 같은 해석**을 써야 한다. 가드만 접미사 변형을 수용하면
        // '복원 가능'이라 판정한 시트를 정작 판독기가 못 읽어 캐릭터가 통째로 사라진다.
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val rowLabel = "캐릭터 필드값 행 ${excelRow(i)}"
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
                    result.warnings.add("$rowLabel: 행 ${excelRow(prev)} 과(와) 같은 항목('${ch.name}'/'${fd.name}')을 다시 덮어썼습니다 (마지막 행 우선)")
                }

                val value = if (valueCol >= 0) getCellString(row, valueCol) else ""
                if (!valueLedger.isLoaded(ch.id)) {
                    valueLedger.load(ch.id, db.characterFieldValueDao().getValuesByCharacterList(ch.id))
                }
                val existing = valueLedger.get(ch.id, fd.id)
                when (FieldValueCellPlan.effectOf(value, existing?.value, columnPresent = valueCol >= 0)) {
                    FieldValueCellEffect.NEW -> {
                        val fresh = CharacterFieldValue(
                            characterId = ch.id, fieldDefinitionId = fd.id, value = value
                        )
                        valueLedger.put(fresh.copy(id = db.characterFieldValueDao().insert(fresh)))
                    }
                    FieldValueCellEffect.UPDATE -> {
                        val updated = existing!!.copy(value = value)
                        db.characterFieldValueDao().update(updated)
                        valueLedger.put(updated)
                    }
                    FieldValueCellEffect.UNCHANGED -> Unit
                    FieldValueCellEffect.CLEAR -> {
                        // F1-A: 열이 있고 셀이 빈칸 = 비움 의도
                        db.characterFieldValueDao().deleteValue(ch.id, fd.id)
                        valueLedger.remove(ch.id, fd.id)
                        result.clearedFields++
                    }
                    FieldValueCellEffect.NONE -> Unit
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("캐릭터 필드값 행 ${excelRow(i)}: ${e.message}")
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
    // 미리보기 짝: analyzeNovelFieldValueSheet
    private suspend fun importNovelFieldValues(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = novelFieldValueSpec()
        // F1-A: 시트가 없으면 기존 값 유지 (구버전 백업 호환) — 없는 것이 정상이라 경고하지 않는다.
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val rowLabel = "작품 필드값 행 ${excelRow(i)}"
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
                    result.warnings.add("$rowLabel: 행 ${excelRow(prev)} 과(와) 같은 항목('${nv.title}'/'${fd.name}')을 다시 덮어썼습니다 (마지막 행 우선)")
                }

                val value = if (valueCol >= 0) getCellString(row, valueCol) else ""
                val existing = db.novelFieldValueDao().getValue(nv.id, fd.id)
                when (FieldValueCellPlan.effectOf(value, existing?.value, columnPresent = valueCol >= 0)) {
                    FieldValueCellEffect.NEW -> db.novelFieldValueDao().insertAll(listOf(
                        com.novelcharacter.app.data.model.NovelFieldValue(
                            novelId = nv.id, fieldDefinitionId = fd.id, value = value
                        )
                    ))
                    FieldValueCellEffect.UPDATE -> db.novelFieldValueDao().update(existing!!.copy(value = value))
                    FieldValueCellEffect.UNCHANGED -> Unit
                    FieldValueCellEffect.CLEAR -> {
                        // F1-A: 열이 있고 셀이 빈칸 = 비움 의도
                        db.novelFieldValueDao().delete(existing!!)
                        result.clearedFields++
                    }
                    FieldValueCellEffect.NONE -> Unit
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("작품 필드값 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "작품 필드값", sheet.lastRowNum, totalRows)
    }

    /**
     * 연표 시트가 열로 담지 못한 사건 필드값을 복원한다 (B-65 · 확정 15번 ㄴ1).
     * 규약은 [importNovelFieldValues]와 같고, **정체는 사건 코드 하나뿐**이다 —
     * 연도·설명으로 되짚으면 같은 해의 비슷한 문장에 값이 붙는다(R-1: 오배정은 생략보다 나쁘다).
     */
    // 미리보기 짝: analyzeEventFieldValueSheet
    private suspend fun importEventFieldValues(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = eventFieldValueSpec()
        val sheet = resolveSpecSheet(workbook, spec) ?: return
        consumedSheetNames.add(sheet.sheetName)
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val rowLabel = "사건 필드값 행 ${excelRow(i)}"
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
                    result.warnings.add("$rowLabel: 행 ${excelRow(prev)} 과(와) 같은 항목을 다시 덮어썼습니다 (마지막 행 우선)")
                }

                val value = if (valueCol >= 0) getCellString(row, valueCol) else ""
                val existing = db.eventFieldValueDao().getValue(event.id, fd.id)
                when (FieldValueCellPlan.effectOf(value, existing?.value, columnPresent = valueCol >= 0)) {
                    FieldValueCellEffect.NEW -> db.eventFieldValueDao().insertAll(listOf(
                        com.novelcharacter.app.data.model.EventFieldValue(
                            eventId = event.id, fieldDefinitionId = fd.id, value = value
                        )
                    ))
                    FieldValueCellEffect.UPDATE -> db.eventFieldValueDao().update(existing!!.copy(value = value))
                    FieldValueCellEffect.UNCHANGED -> Unit
                    FieldValueCellEffect.CLEAR -> {
                        db.eventFieldValueDao().delete(existing!!)
                        result.clearedFields++
                    }
                    FieldValueCellEffect.NONE -> Unit
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("사건 필드값 행 ${excelRow(i)}: ${e.message}")
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
        // 고정 열 인덱스는 CHARACTER_FIXED_HEADERS에서 **기계적으로 파생**한다 — 손으로 나열하면
        // 새 고정 열이 빠진다. 실제로 '대표이미지'가 빠져 있었고, 그러면 그 열이 커스텀 필드로
        // 오인되어 무편집 왕복이 세계관마다 '대표이미지' TEXT 필드를 만들고 파일명을 필드값으로
        // 저장했다. 헤더 집합과 spec의 일치는 CharacterSpecColumnOrderTest가 이미 잠그므로,
        // 여기서 파생하면 세 자리(스펙·헤더 집합·이 인덱스)가 한 소스로 움직인다.
        val fixedColIndices = (CHARACTER_FIXED_HEADERS.mapNotNull { cols[it] } + cc.name).filter { it >= 0 }.toSet()
        val columnFieldMap = buildColumnFieldMap(headerRow, fields, fixedColIndices, universe, result, sheetLabel)
        // 계산 열이 이 시트에 하나라도 있는가 — **행 밖에서 한 번** 잰다. 이 술어가 참일 때만
        // 행마다 재료 스냅샷을 뜬다(종전의 `by lazy`가 노리던 절약을 루프 밖에서 그대로 얻는다).
        val sheetHasCalculatedColumn = columnFieldMap.values.any { it.fieldType == FieldType.CALCULATED }

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()
        // "이 캐릭터에 태그가 있었는가"만 묻던 자리 — 빈 셀로 태그를 비우는 행마다 표를 다시
        // 읽었다(목표 규모에서 캐릭터 6,420명). 태그표를 **한 번** 읽어 가진 캐릭터의 id 집합으로
        // 답하고, 아래 교체·삭제가 그 집합을 함께 옮긴다 (B-210).
        // **이 시트 함수는 세계관마다 다시 불리므로 집합도 그때마다 새로 뜬다** — 앞 시트의
        // 쓰기는 이미 표에 있으니 다시 읽는 쪽이 옳다.
        val charactersWithTags = db.characterTagDao().getAllTagsList().mapTo(HashSet()) { it.characterId }

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readCharacterRow(row, cc, "캐릭터 행 ${excelRow(i)}", result)
                val name = r.name
                if (name.isBlank()) {
                    // 미리보기와 **같은 판정**이다(R-33).
                    if (rowCarriesValue(row)) {
                        result.skippedRows++
                        result.errors.add(
                            "캐릭터 행 ${excelRow(i)}: '이름' 칸이 비어 있어 행을 건너뛰었습니다 — 필수 항목입니다"
                        )
                    }
                    continue
                }

                val code = r.code
                val novelCode = r.novelCode
                val novelTitle = r.novelTitle

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("캐릭터: 코드 '$code'가 행 ${excelRow(prevRow)} 과 행 ${excelRow(i)} 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Resolve novel: code-first, then title. F1-A: 작품/작품코드 열이 모두 없으면 기존 배정을 유지한다(아래 적용).
                // 열이 있으면 셀 해석(빈칸=미배정, 사용자 의도 존중). resolveNovelId는 제목이 있을 때만 호출(빈 제목 유령 생성 방지).
                val novelColumnsPresent = r.novelColumnsPresent
                // 작품코드가 적혀 있는데 해석되지 않고 제목도 없으면 배정을 해제하지 않는다 —
                // 셀에 참조를 적어 둔 것은 '빈칸=미배정 의도'가 아니다(연표·작품 시트가 같은
                // 상황에서 기존 연결을 유지하는 것과 같은 정책). 종전에는 이 경로가 무경고로
                // 기존 배정을 null로 덮었다.
                var novelCodeUnresolved = false
                val novelId: Long? = novelByCode(novelCode)?.id
                    ?: when {
                        novelTitle.isNotBlank() -> resolveNovelId(novelTitle, universe?.id, result, "캐릭터 행 ${excelRow(i)}")
                        novelCode.isNotBlank() -> { novelCodeUnresolved = true; null }
                        else -> null
                    }

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
                            result.warnings.add("캐릭터 행 ${excelRow(i)}: 코드 '$code'를 찾지 못해 이름 '$name'으로 매칭함 — 의도한 새 캐릭터라면 코드를 비우세요")
                        } else {
                            existingChar = null
                            warnCreatedNewByCode("characters", "캐릭터 행 ${excelRow(i)}: 코드 '$code'가 기존 캐릭터에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
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
                        result.warnings.add("캐릭터 행 ${excelRow(i)}: 이름 기반 매칭 ('$name') — 코드 사용 권장")
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
                    // '이미지경로' 열 있음+빈칸은 "[]"로 읽혀 병합이 이미지 배정을 통째로 푼다 —
                    // 텍스트 열과 같은 규약으로 '지워진 값'에 집계해 무고지 삭제가 되지 않게 한다.
                    // 판정은 글자가 아니라 **파싱 결과**다(콜드 검토 2026.08.20) — '[ ]'처럼
                    // 표기만 다른 유효한 빈 목록도 배정을 푸는 것은 같으므로, 리터럴 "[]"만
                    // 세면 그 갈래가 계수에서 빠진다. 읽을 수 없는 값은 여기 오지 않는다 —
                    // readCharacterRow가 기존-유지(null) + 경고로 걸렀다.
                    if (imagePathsFromExcel != null &&
                        CharacterRepresentativeImage.paths(imagePathsFromExcel).isEmpty() &&
                        CharacterRepresentativeImage.paths(existingChar.imagePaths).isNotEmpty()
                    ) result.clearedFields++
                    // imagePaths는 `withImagePaths`로 넘긴다 — 대표 포인터(B-103)가 재매핑을
                    // 따라가고, 다른 기기에서 온 목록에 그 파일이 없으면 풀린다(D5).
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    // **한 번만 부른다** — 이 함수는 `result`를 받아 대표 셀 고지를 쌓으므로
                    // 세는 김에 다시 부르면 그 고지가 두 번 붙는다.
                    if (novelCodeUnresolved) {
                        result.warnings.add("캐릭터 행 ${excelRow(i)}: 작품코드 '$novelCode'을(를) 찾을 수 없어 기존 작품 배정을 유지합니다 — 코드를 바로잡거나 '작품' 칸에 제목을 적어 주세요")
                    }
                    val mergedChar = mergeCharacter(
                        existingChar, r,
                        if (novelCodeUnresolved) existingChar.novelId else novelId,
                        i, nowMillis, result
                    )
                    db.characterDao().update(mergedChar)
                    // 정체성 색인도 함께 옮긴다 — 이름·코드가 바뀌었으면 **옛 키로는 더 이상
                    // 잡히지 않아야** SQL과 같은 답이 된다(B-210).
                    rememberCharacter(mergedChar)
                    if (mergedChar != existingChar) result.updatedCharacters++ else result.unchangedRows++

                    // 사용자가 이전 세계관 필드값 삭제를 선택한 경우 정리
                    if (universe != null && conflict?.cleanupOldFields == true) {
                        // 처분 무관(칸 단위가 아니라 **캐릭터 단위 정리**다 — 파일의 어떤 셀이
                        // 시킨 것이 아니라 사용자가 충돌 대화상자에서 고른 것이라, 미리보기가
                        // 예고할 대상도 아니다. 그 결정은 이 창 뒤에 온다).
                        db.characterFieldValueDao().deleteValuesNotInUniverse(charId, universe.id)
                        // 장부가 이 캐릭터를 이미 실었다면 그 사본은 방금 지운 값을 아직 들고 있다.
                        // 내려 두면 아래에서 다시 읽는다 — 같은 캐릭터가 시트에 두 번 나오는
                        // 파일(중복 행 고지 대상)에서만 실제로 갈리는 자리다.
                        valueLedger.forget(charId)
                    }
                } else {
                    if (novelCodeUnresolved) {
                        result.warnings.add("캐릭터 행 ${excelRow(i)}: 작품코드 '$novelCode'을(를) 찾을 수 없어 작품 미지정으로 생성됨 — 코드를 바로잡거나 '작품' 칸에 제목을 적어 주세요")
                    }
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newCharacter = newCharacterFrom(r, newCode, novelId, i, nowMillis, result)
                    charId = db.characterDao().insert(newCharacter)
                    // 방금 만든 캐릭터를 **곧바로 읽히게** 한다 — 같은 파일의 뒷 행·뒷 시트가
                    // 코드·이름으로 이 캐릭터를 찾는다(연표 참가자·상태변화·이름 은행).
                    // 빠뜨리면 *있는 것을 없다고* 보고 같은 캐릭터가 둘로 갈린다(B-210).
                    rememberCharacter(newCharacter.copy(id = charId))
                    result.newCharacters++
                }

                // F3-A: 엑셀에서 작품이 바뀌어 세계관이 이동했는지 감지 (existingChar.novelId=이동 전, novelId=이동 후).
                // 이동이면 아래 필드 기록 후 편집화면과 동일한 P0 로직으로 재매핑·정리한다.
                // **판정은 미리보기와 같은 함수다**(R-33 · B-253).
                val movedToUniverseId: Long? =
                    universeMoveOf(existingChar, novelColumnsPresent, novelId)

                // matched ID 추적 (deleteNotInExcel용) — 시트 세계관과 무관하게 전역 등록.
                // 엑셀 편집으로 캐릭터가 다른 세계관 작품으로 이동해도 삭제 대상이 되지 않게 한다.
                matchedCharacterIds.add(charId)

                val prevRow = entitySeen[charId]
                if (prevRow != null) {
                    result.warnings.add("캐릭터 행 ${excelRow(i)}: 행 ${excelRow(prevRow)} 과 같은 항목('$name')을 다시 덮어씀 — 별개의 캐릭터로 넣으려면 '코드' 칸을 비우고 이름을 다르게 한 뒤 다시 가져오세요")
                }
                entitySeen[charId] = i

                // 태그 가져오기 — F1-A 규칙 가: 열 없음(colIndex<0)=기존 유지, 빈 셀=삭제(요약 고지), 값 있음=교체
                if (tagsColIndex >= 0) {
                    val tagsStr = getCellString(row, tagsColIndex)
                    if (tagsStr.isNotBlank()) {
                        db.characterTagDao().deleteAllByCharacter(charId)
                        // 태그는 **조회 없이 그대로 저장된다**(아래 `CharacterTag(tag = tag)`) —
                        // 전각을 낮춰 읽으면 무편집 왕복에서 태그가 조용히 개명된다.
                        val tags = splitCsvIdentity(tagsStr)
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
                // 계산 열 판정의 재료 — **열 루프에 들어가기 전에 굳힌다.**
                //
                // 내보내기가 그 셀에 쓴 값은 *저장된 값*으로 계산한 것이므로 재료도 그것이어야
                // 한다([CalculatedCellEcho]의 계약). 종전에는 `by lazy`라 **그 행의 첫 계산
                // 열에서야** 만들어졌는데, 열 순서는 열 번호 오름차순이고 계산 필드는 보통
                // 참조 필드 오른쪽에 온다 — 그래서 앞 열의 `valueLedger.put(...)`이 **이미
                // 파일 값으로 갈아엎은 장부**를 읽었다(콜드 검토 2026.08.21).
                //
                // 결과: 엑셀에서 **입력 열만 고친** 정상 왕복이 계산 열마다 거짓 경고와
                // '건너뜀'을 냈다 — 이 판정이 없애려던 잡음을 자기가 만들었고, 하필
                // *"내보내기 → 엑셀에서 수치 편집 → 들이기"*라는 가장 흔한 경로다.
                //
                // **이 행이 캐릭터를 만드는 경우는 재료를 행에서 짓는다**: 새 소유자에게는
                // 저장된 값이 없어 재료가 통째로 비고, 그러면 수식이 재료 없이 평가돼 셀과
                // 어긋난다 — 아무것도 안 고친 파일에서도 **새 캐릭터 행마다** 거짓 경고가
                // 났다(빈 DB 복원이면 전 행이 그렇다). 그 행에서는 내보낸 기기의 저장값이
                // 곧 이 행의 입력 칸이다([CalculatedCellEcho.materialsFromRow]).
                val storedByKey: Map<String, String> = when {
                    !sheetHasCalculatedColumn -> emptyMap()
                    existingChar == null -> CalculatedCellEcho.materialsFromRow(
                        columnFieldMap.map { (ci, f) -> f to getCellString(row, ci) }
                    )
                    else -> fields.asSequence()
                        .mapNotNull { f -> valueLedger.get(charId, f.id)?.value?.let { f.key to it } }
                        .toMap()
                }
                for ((colIndex, field) in columnFieldMap) {
                    // F4: CALCULATED는 다른 필드로부터 실시간 산출되는 파생값 — 저장하지 않는다(읽기 전용).
                    // 내보내기 시 계산 결과를 표시하지만 가져오기 때 저장하면 stale 중복 데이터가 된다.
                    if (field.fieldType == FieldType.CALCULATED) {
                        // U-10: 종전에는 **무통보 폐기**였다 — 엑셀에서 계산 열에 값을 적어 넣고
                        // 가져와도 아무 말 없이 사라져, 사용자는 반영된 줄 안다.
                        // 형제 경고(사건 시트·'캐릭터 필드값' 시트)와 같은 문구로 맞춘다.
                        //
                        // **앱 자신이 적어 낸 산출값에는 말하지 않는다** — 그 칸에 값을 적은 것은
                        // 사용자가 아니라 내보내기다. 종전에는 '비었는가'만 보아, 한 글자도 안 고친
                        // 왕복이 세계관·필드마다 이 경고를 한 줄씩 냈다(정상 파일이 상한 파일처럼
                        // 보이면 사용자는 진짜 경고를 그 잡음 속에서 잃는다).
                        val cell = getCellString(row, colIndex)
                        if (cell.isNotBlank() &&
                            !CalculatedCellEcho.isAppOutput(field, cell, fields, storedByKey) &&
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
                                result.warnings.add("캐릭터 행 ${excelRow(i)}: 숫자 필드 '${field.name}'에 숫자가 아닌 값 '$value'이(가) 저장됨 — 통계에서 제외될 수 있습니다")
                            }
                            FieldType.GRADE -> if (GradeValueResolver.resolveForDisplay(field, value) == null) {
                                result.warnings.add("캐릭터 행 ${excelRow(i)}: 등급 필드 '${field.name}'의 값 '$value'을(를) 인식할 수 없습니다 — 통계·수식에서 제외될 수 있습니다")
                            }
                            FieldType.BODY_SIZE -> if (!value.any { it.isDigit() }) {
                                result.warnings.add("캐릭터 행 ${excelRow(i)}: 신체 사이즈 필드 '${field.name}'의 값 '$value'에 숫자가 없어 통계에 반영되지 않을 수 있습니다")
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
                            result.warnings.add("캐릭터 행 ${excelRow(i)}: '${field.name}' 값 ${aliasTokens.joinToString(", ") { "'$it'" }}은(는) 라이브러리 별칭 표기입니다 — 원문대로 저장됨, 라이브러리에서 표기를 정리할 수 있습니다")
                        }
                    }
                    val existingValue = valueLedger.get(charId, field.id)
                    // 처분은 [FieldValueCellPlan] 하나가 정한다 — 미리보기가 세는 것과 **같은 판정**이다(R-33).
                    when (FieldValueCellPlan.effectOf(value, existingValue?.value)) {
                        FieldValueCellEffect.NEW -> {
                            val fresh = CharacterFieldValue(
                                characterId = charId, fieldDefinitionId = field.id, value = value
                            )
                            valueLedger.put(fresh.copy(id = db.characterFieldValueDao().insert(fresh)))
                        }
                        FieldValueCellEffect.UPDATE -> {
                            val updated = existingValue!!.copy(value = value)
                            db.characterFieldValueDao().update(updated)
                            valueLedger.put(updated)
                        }
                        // 같은 값을 다시 쓰지 않는다 — 결과가 같고 비용만 든다(무편집 왕복이 표 전체를 다시 쓰던 자리).
                        FieldValueCellEffect.UNCHANGED -> Unit
                        FieldValueCellEffect.CLEAR -> {
                            // 빈 셀 = 값 삭제 (F1-A 규칙 가: 요약 집계)
                            db.characterFieldValueDao().deleteValue(charId, field.id)
                            valueLedger.remove(charId, field.id)
                            result.clearedFields++
                            // 역할 필드를 지웠으면 파생 이력도 정리 대상이다 — 값 목록만으로는
                            // *지워짐*을 볼 수 없어 여기서 세어 둔다.
                            if (SemanticRole.fromConfig(field.config) != null) {
                                pendingSyncClearedFields.getOrPut(charId) { mutableSetOf() }.add(field.id)
                                hasSemanticField = true
                            }
                        }
                        FieldValueCellEffect.NONE -> Unit
                    }
                    // **동기화는 처분과 별개다** — 값이 그대로여도 파생값(나이 등)이 낡아 있을 수 있어,
                    // 종전처럼 '값이 실린 칸'을 기준으로 삼는다.
                    if (value.isNotBlank() && !hasSemanticField &&
                        SemanticRole.fromConfig(field.config) != null
                    ) {
                        hasSemanticField = true
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
                                "캐릭터 행 ${excelRow(i)}: '$name' 세계관 이동 감지 — 대응 없는 필드값 ${counts.removedValues}개·타 세계관 세력소속 ${counts.removedMemberships}개 정리(휴지통에 스냅샷 보관), ${counts.remappedValues}개 다시 연결")
                            counts.remappedValues > 0 -> result.warnings.add(
                                "캐릭터 행 ${excelRow(i)}: '$name' 세계관 이동 감지 — 필드값 ${counts.remappedValues}개를 새 세계관 필드로 다시 연결")
                        }
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                val sheetLabel = universe?.name ?: "미분류 캐릭터"
                result.errors.add("$sheetLabel 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, universe?.name ?: "미분류 캐릭터", sheet.lastRowNum, totalRows)
    }

    // ── 연표 가져오기 ──

    private suspend fun importTimeline(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = timelineSpec(emptyList())
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        // 미리보기와 **같은 재료·같은 판정**의 제목 색인이다(규약 R-33).
        val novelTitles = NovelTitleIndex(db.novelDao().getAllNovelsList())
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
        // 열 수집은 작품 시트·미리보기와 **같은 함수**다([collectEntityFieldColumns] · B-187).
        val eventFieldColumns = collectEntityFieldColumns(headerRow, allEventFields, universeNamesById, result, "사건 연표")
        // 작품 시트와 같은 근거로 기존 값을 **한 번** 싣는다(B-187 — 위 `importNovels`의 짝).
        val eventValues = if (eventFieldColumns.isEmpty()) null else FieldValueOverlay.of(
            db.eventFieldValueDao().getAllValuesList()
        ) { Triple(it.eventId, it.fieldDefinitionId, it.value) }

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readTimelineRow(row, tc, "연표 행 ${excelRow(i)}", result)
                val year = r.year
                if (year == null) {
                    // 행이 조용히 사라지지 않도록 보고 (빈 행은 제외)
                    if (r.yearRaw.isNotBlank() || r.description.isNotBlank()) {
                        result.skippedRows++
                        result.errors.add("연표 행 ${excelRow(i)}: 연도 '${r.yearRaw}'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    }
                    continue
                }
                val description = r.description
                if (description.isBlank()) {
                    // 연도 갈래(바로 위)와 같은 처분이다 — 그쪽만 세고 이쪽은 침묵하던 비대칭을 없앤다.
                    if (rowCarriesValue(row)) {
                        result.skippedRows++
                        result.errors.add(
                            "연표 행 ${excelRow(i)}: '사건 설명' 칸이 비어 있어 행을 건너뛰었습니다 — 필수 항목입니다"
                        )
                    }
                    continue
                }

                val novelTitle = r.novelTitle
                val novelCode = r.novelCode
                // 작품 연결·세계관 소속 해석도 미리보기와 **같은 함수**다.
                val links = resolveTimelineLinks(row, tc, r, novelTitles, "연표 행 ${excelRow(i)}", result)
                val novelIds = links.novelIds
                // 동명 작품 모호 상세 — 어느 제목이 어디에서 겹치는지까지 들어야 교정 경로를 고를 수 있다.
                val ambiguousNovelDetail = if (links.ambiguousNovels.isEmpty()) "" else
                    novelAmbiguityDetail(links.ambiguousNovels) { id -> if (id == null) "무소속" else universeNamesById[id] }

                val fileCode = r.fileCode
                if (fileCode.isNotBlank() && !eventCodesSeen.add(fileCode)) {
                    result.warnings.add("연표 행 ${excelRow(i)}: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 사건을 덮어씁니다")
                }
                // 매칭: 코드 우선(설명·연도 편집을 같은 사건으로 인식) → 자연키 폴백(구버전 파일 호환)
                val eventByCodeMatch = eventByCode(fileCode)
                val existingEvent = eventByCodeMatch ?: eventByNaturalKey(year, description)
                // 파일 내 중복 고지는 코드 갈래만 있었다(위 줄) — 자연키 갈래도 같은 규약으로
                // 고지한다(I2-5). 이 시트가 이미 쓴 사건을 자연키로 다시 잡으면 그 행이 앞 행을
                // 무고지로 덮는다(형제 시트 전부가 코드 중복을 고지하는데 이 갈래만 침묵이었다).
                if (eventByCodeMatch == null && existingEvent != null && existingEvent.id in matchedEventIds) {
                    result.warnings.add("연표 행 ${excelRow(i)}: 같은 연도·설명의 행이 파일 내에서 중복되어 같은 사건을 덮어씁니다 — 다른 사건이라면 설명을 구분해 주세요")
                }

                val eventId: Long
                if (existingEvent != null) {
                    eventId = existingEvent.id
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedEvent = mergeTimelineEvent(existingEvent, r, links, generateEntityCode())
                    db.timelineDao().update(mergedEvent)
                    // 연도·설명·코드가 바뀌었을 수 있다 — 색인이 옛 키를 계속 가리키면 뒤 행이
                    // **이미 옮겨 간 사건을 옛 이름으로** 다시 잡는다(B-210).
                    rememberEvent(mergedEvent)
                    // 작품이 **전부** 해석된 경우에만 M2M 교체; 하나라도 실패·모호면 기존 관계 유지 + 경고.
                    // 부분 해석된 부분집합으로 교체하면 미해석·모호 몫의 기존 연결이 무음 삭제된다 —
                    // 전량 실패(종전부터 유지+경고)보다 부분 실패가 더 조용하면 안 된다.
                    if (novelIds.isNotEmpty() && links.unresolvedNovelTokens.isEmpty() && links.ambiguousNovels.isEmpty()) {
                        db.timelineDao().replaceEventNovels(eventId, novelIds)
                        eventsWithNovelLinks.add(eventId)
                    } else if (links.unresolvedNovelTokens.isNotEmpty() || links.ambiguousNovels.isNotEmpty()) {
                        // 모호와 미해석은 다른 사실이다 — 섞어 말하면 거짓 경고다(4-3 규약).
                        // 둘 다 있으면 각자 제 문구로 말한다(교정 경로가 서로 다르다).
                        if (links.ambiguousNovels.isNotEmpty()) {
                            result.warnings.add(
                                "사건 행 ${excelRow(i)}: 동명 작품 ${ambiguousNovelDetail}이(가) 있어 하나로 확정할 수 없습니다 — 기존 작품 연결을 유지합니다. '관련작품코드' 열에 '작품' 시트의 코드를 적거나 '세계관' 열로 좁혀 주세요"
                            )
                        }
                        if (links.unresolvedNovelTokens.isNotEmpty()) {
                            result.warnings.add(
                                "사건 행 ${excelRow(i)}: 관련 작품 '${links.unresolvedNovelTokens.joinToString(", ")}'을(를) 찾을 수 없어 기존 작품 연결을 유지합니다 — 코드·제목을 바로잡은 뒤 다시 가져오세요"
                            )
                        }
                    } else if (novelTitle.isNotBlank() || novelCode.isNotBlank()) {
                        result.warnings.add("사건 행 ${excelRow(i)}: 작품 '${novelTitle}'을(를) 찾을 수 없어 기존 작품 연결을 유지합니다")
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
                    val newEvent = newTimelineEventFrom(
                        r, year, links, i, nowMillis,
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
                    // 신규 사건은 지킬 기존 연결이 없다 — 해석된 몫은 걸고, 미해석·모호 토큰은
                    // 사실대로 알린다(종전에는 제목만 검사해 코드만 실린 미해석 행이 무고지로
                    // 연결 없는 사건이 됐다 — 기존 사건 갈래와 비대칭이었다).
                    if (links.ambiguousNovels.isNotEmpty()) {
                        result.warnings.add(
                            if (novelIds.isEmpty()) {
                                "사건 행 ${excelRow(i)}: 동명 작품 ${ambiguousNovelDetail}이(가) 있어 하나로 확정할 수 없습니다 — 작품 미지정 상태로 생성됨. '관련작품코드' 열에 '작품' 시트의 코드를 적거나 '세계관' 열로 좁혀 주세요"
                            } else {
                                "사건 행 ${excelRow(i)}: 동명 작품 ${ambiguousNovelDetail}이(가) 있어 하나로 확정할 수 없습니다 — 연결에서 빠졌습니다. '관련작품코드' 열에 '작품' 시트의 코드를 적거나 '세계관' 열로 좁혀 주세요"
                            }
                        )
                    }
                    if (links.unresolvedNovelTokens.isNotEmpty()) {
                        result.warnings.add(
                            if (novelIds.isEmpty()) {
                                "사건 행 ${excelRow(i)}: 관련 작품 '${links.unresolvedNovelTokens.joinToString(", ")}'을(를) 찾을 수 없어 작품 미지정 상태로 생성됨"
                            } else {
                                "사건 행 ${excelRow(i)}: 관련 작품 '${links.unresolvedNovelTokens.joinToString(", ")}'을(를) 찾을 수 없어 연결에서 빠졌습니다 — 코드·제목을 바로잡은 뒤 다시 가져오세요"
                            }
                        )
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
                    // 이 행이 실제로 표를 건드리는가 — 전부 '동일'·'없음'이면 삭제+삽입을 건너뛴다(작품 시트와 같다).
                    var touchesTable = false
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
                        // 처분은 오버플로 시트와 **같은 함수**가 정한다(R-33 · B-187).
                        when (FieldValueCellPlan.effectOf(cellValue, eventValues?.get(eventId, fieldDef.id))) {
                            FieldValueCellEffect.NEW, FieldValueCellEffect.UPDATE -> {
                                touchesTable = true
                                eventValues?.put(eventId, fieldDef.id, cellValue)
                            }
                            FieldValueCellEffect.CLEAR -> {
                                touchesTable = true
                                eventValues?.remove(eventId, fieldDef.id)
                                result.clearedFields++
                            }
                            FieldValueCellEffect.UNCHANGED, FieldValueCellEffect.NONE -> Unit
                        }
                        if (cellValue.isNotBlank()) {
                            newValues.add(com.novelcharacter.app.data.model.EventFieldValue(
                                eventId = eventId, fieldDefinitionId = fieldDef.id, value = cellValue
                            ))
                        }
                    }
                    // 해석된 열만 교체 — 시트에 없던 필드의 기존 값은 그대로 살아남는다(F1-A).
                    // 열이 있고 셀이 빈칸이면 resolvedFieldIds에 포함되므로 비움 의도는 존중된다.
                    // **아무것도 바뀌지 않으면 쓰지 않는다**(겹을 못 받은 구경로는 종전대로 언제나 쓴다).
                    if (eventValues == null || touchesTable) {
                        db.eventFieldValueDao().replaceForFields(eventId, resolvedFieldIds, newValues)
                    }
                }

                // 관련 캐릭터 해석 — **코드 우선**(동명이인 오결합 방지, P1-I), 코드 없는 항목은 이름 매칭.
                // 코드로 이미 잡힌 캐릭터와 동명인 이름 항목은 중복 추가하지 않는다(코드가 권위).
                val charCodeStr = getCellCode(row, charCodeColIndex, "연표 행 ${excelRow(i)}", result)
                val characterNames = getCellString(row, charColIndex)
                if (charCodeStr.isNotBlank() || characterNames.isNotBlank()) {
                    val resolved = LinkedHashMap<Long, com.novelcharacter.app.data.model.Character>()
                    // 미해석 토큰을 조용히 버리지 않는다 — 부분 해석된 부분집합으로 기존 참가자를
                    // 교체하면 미해석 몫의 연결이 무음 삭제된다(관련 작품과 같은 정책).
                    val unresolvedParticipantTokens = mutableListOf<String>()
                    if (charCodeStr.isNotBlank()) {
                        for (code in splitCsv(charCodeStr)) {
                            val ch = characterByCode(code)
                            if (ch != null) resolved[ch.id] = ch
                            else {
                                // 종전에는 코드 미해석이 무경고 탈락이었다(이름 항목만 경고) — 비대칭 정정.
                                unresolvedParticipantTokens.add(code)
                                result.warnings.add("사건 행 ${excelRow(i)}: 연결 캐릭터 코드 '$code'을(를) 찾을 수 없음")
                            }
                        }
                    }
                    if (characterNames.isNotBlank()) {
                        val coveredNames = resolved.values.mapTo(HashSet()) { it.name }
                        for (charName in splitCsv(characterNames)) {
                            if (charName in coveredNames) continue  // 코드로 이미 해석된 동명 항목
                            // F3-B: 동명이인 안전 — LIMIT 1로 아무나 고르지 않고, 모호하면 경고 후 스킵
                            when (val r = resolveCharByNameNovel(charName, novelIds.firstOrNull())) {
                                is CharLookupResult.Found -> resolved[r.character.id] = r.character
                                is CharLookupResult.Ambiguous -> {
                                    unresolvedParticipantTokens.add(charName)
                                    result.warnings.add("사건 행 ${excelRow(i)}: 연결 캐릭터 '$charName' 동명이인 ${r.count}명 — 관련캐릭터코드 열로 지정하세요")
                                }
                                CharLookupResult.NotFound -> {
                                    unresolvedParticipantTokens.add(charName)
                                    result.warnings.add("사건 행 ${excelRow(i)}: 연결 캐릭터 '${charName}'을(를) 찾을 수 없음")
                                }
                            }
                        }
                    }
                    val resolvedCharacters = resolved.values.toList()
                    // 부분 미해석 + 기존 참가자 있음 → 교체하지 않고 기존 연결을 지킨다(관련 작품과
                    // 같은 정책: 전량 실패보다 부분 실패가 더 조용하면 안 된다). 신규 사건이거나
                    // 기존 참가자가 없으면 해석된 몫이라도 거는 쪽이 유실이 아니다.
                    if (resolvedCharacters.isNotEmpty() && unresolvedParticipantTokens.isNotEmpty() &&
                        eventsWithParticipants.contains(eventId)
                    ) {
                        result.warnings.add(
                            "사건 행 ${excelRow(i)}: 관련 캐릭터 일부('${unresolvedParticipantTokens.joinToString(", ")}')를 확정할 수 없어 기존 캐릭터 연결을 유지합니다 — 코드·이름을 바로잡은 뒤 다시 가져오세요"
                        )
                    } else if (resolvedCharacters.isNotEmpty()) {
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
                result.errors.add("연표 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "사건 연표", sheet.lastRowNum, totalRows)
    }

    // ── 상태변화 가져오기 ──

    private suspend fun importStateChanges(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = stateChangeSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        // 필수 컬럼: 위치 폴백을 쓰면 컬럼 삭제 시 이웃 컬럼 데이터가 그대로 기록되므로 검증 후 스킵
        val yearColIndex = requiredCol(cols, "연도", sheet.sheetName, result) ?: return
        val fieldKeyColIndex = requiredCol(cols, "필드키", sheet.sheetName, result) ?: return
        val newValueColIndex = requiredCol(cols, "새 값", sheet.sheetName, result) ?: return
        val scc = StateChangeCols(cols, yearColIndex, fieldKeyColIndex, newValueColIndex)
        val nowMillis = System.currentTimeMillis()

        // 미리보기와 **같은 판정**의 제목 색인이다(규약 R-33). 동명 작품을 first-match로 골라
        // 힌트로 쓰면 동명이인 좁히기가 엉뚱한 작품 기준이 된다 — 동명이면 힌트를 포기하고
        // 캐릭터 쪽 모호 감지(resolveCharByNameNovel)가 코드 안내를 내게 둔다(4-3 규약).
        val novelTitles = NovelTitleIndex(db.novelDao().getAllNovelsList())
        // 상태변화 정체성 색인 (B-210) — 행마다 코드·자연키로 표를 묻던 자리.
        // **연표가 만든 birth/death 행도 여기 실린다** — 그 시트가 먼저 돌고 이 색인은 지금 읽는다.
        // **키 모양과 싣는 순서는 미리보기와 같은 클래스가 든다**(B-236 — `util/ImportIdentityIndexes.kt`).
        val changes = StateChangeIndexes(db.characterStateChangeDao().getAllChangesList())
        val changeCodesSeen = mutableSetOf<String>()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readStateChangeRow(row, scc, "상태변화 행 ${excelRow(i)}", result)
                val charName = r.charName
                if (charName.isBlank()) continue

                val year = r.year
                if (year == null) {
                    result.skippedRows++
                    result.errors.add("상태변화 행 ${excelRow(i)}: 연도 '${r.yearRaw}'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    continue
                }

                val fieldKey = r.fieldKey
                if (fieldKey.isBlank()) continue
                val newValue = r.newValue
                if (newValue.isBlank()) {
                    result.skippedRows++
                    result.warnings.add("상태변화 행 ${excelRow(i)}: 빈 값은 허용되지 않습니다")
                    continue
                }
                val charCode = r.charCode

                // Resolve character: code-first, then strict name lookup (동명이인 모호성 감지)
                val character: Character = when {
                    charCode.isNotBlank() -> {
                        val found = characterByCode(charCode)
                        if (found == null) {
                            result.skippedRows++
                            result.errors.add("상태변화 행 ${excelRow(i)}: 코드 '${charCode}'에 해당하는 캐릭터를 찾을 수 없음")
                            continue
                        }
                        found
                    }
                    // 시트의 '작품' 열을 동명이인 해소 힌트로 쓴다 — 내보내면서 실어놓고 쓰지 않으면
                    // 코드 열이 없는 구버전 파일에서 해소 가능한 행이 불필요하게 거부된다.
                    else -> {
                        val hintNovelId = (novelTitles.resolve(r.novelTitle, null) as? NovelTitleLookup.Found)?.novel?.id
                        when (val resolved = resolveCharByNameNovel(charName, hintNovelId)) {
                            is CharLookupResult.Found -> resolved.character
                            is CharLookupResult.Ambiguous -> {
                                result.skippedRows++
                                result.errors.add("상태변화 행 ${excelRow(i)}: '${charName}' 이름의 캐릭터가 ${resolved.count}명 존재합니다. '작품' 열이나 캐릭터코드 열로 구분하세요.")
                                continue
                            }
                            is CharLookupResult.NotFound -> {
                                result.skippedRows++
                                result.errors.add("상태변화 행 ${excelRow(i)}: 캐릭터 '${charName}'을(를) 찾을 수 없음")
                                continue
                            }
                        }
                    }
                }

                val fileCode = r.fileCode
                if (fileCode.isNotBlank() && !changeCodesSeen.add(fileCode)) {
                    result.warnings.add("상태변화 행 ${excelRow(i)}: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }
                // 매칭: 코드 우선(연도·필드키·값 편집을 같은 이력으로 인식) → 자연키 폴백(구버전 파일 호환)
                val changeByCodeMatch = if (fileCode.isNotBlank()) changes.byCode.first(fileCode) else null
                val existing = changeByCodeMatch
                    ?: changes.byNaturalKey.first(StateChangeNaturalKey(character.id, year, fieldKey, newValue))
                // 파일 내 중복 고지는 코드 갈래만 있었다(위 줄) — 자연키 갈래도 같은 규약으로 고지한다
                // (연표 I2-5와 같은 모양 — 이 시트가 이미 쓴 이력을 자연키로 다시 잡으면 무고지로 덮었다).
                if (changeByCodeMatch == null && existing != null && existing.id in matchedStateChangeIds) {
                    result.warnings.add("상태변화 행 ${excelRow(i)}: 같은 캐릭터·연도·필드·값의 행이 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }

                if (existing != null) {
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedStateChange = mergeStateChange(existing, r, character.id, generateEntityCode())
                    db.characterStateChangeDao().update(mergedStateChange)
                    // 자연키의 칸(연도·필드키·새 값)이 바뀌었을 수 있다 — 옛 키를 끊지 않으면
                    // 뒤 행이 **이미 다른 이력이 된 행**을 옛 키로 다시 잡는다(B-210).
                    changes.remember(mergedStateChange)
                    matchedStateChangeIds.add(existing.id)
                    if (mergedStateChange != existing) result.updatedStateChanges++ else result.unchangedRows++
                } else {
                    val newChange = newStateChangeFrom(
                        r, character.id, year, nowMillis,
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    )
                    val newId = db.characterStateChangeDao().insert(newChange)
                    changes.remember(newChange.copy(id = newId))
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
                result.errors.add("상태변화 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "상태변화", sheet.lastRowNum, totalRows)
    }

    // ── 캐릭터 명대사 가져오기 (사용자 요청 2026.08.20) ──

    /**
     * 명대사 시트 — [importStateChanges]와 **같은 뼈대**다(캐릭터의 자식 표라 같은 부류다).
     *
     * 갈리는 자리는 둘뿐이다:
     * - 자연키가 `(캐릭터, 대사 글자)`다. 차례로 잡으면 목록을 끌어 옮긴 파일에서 엉뚱한
     *   대사끼리 이어져 **두 대사의 내용이 서로 바뀐다.**
     * - `상황` 칸을 **고쳐 읽지 않는다.** `__birthday`를 '생일'로 적어 온 파일은 그것을
     *   사용자가 만든 상황으로 받는다 — 앱이 짐작해 예약 자리로 되돌리면 사용자가 그은
     *   구분이 덮인다(개발 의도 2번).
     */
    private suspend fun importQuotes(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = quoteSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        // 필수 컬럼: 위치 폴백을 쓰면 컬럼 삭제 시 이웃 컬럼 데이터가 그대로 기록된다(R-36).
        val textColIndex = requiredCol(cols, "대사", sheet.sheetName, result) ?: return
        val qc = QuoteCols(cols, textColIndex)
        val nowMillis = System.currentTimeMillis()

        // 미리보기와 **같은 판정**의 제목 색인이다(R-33) — 상태변화와 같은 근거.
        val novelTitles = NovelTitleIndex(db.novelDao().getAllNovelsList())
        // 키 모양과 싣는 순서는 미리보기와 **같은 클래스**가 든다(`util/ImportIdentityIndexes.kt`).
        val quotes = QuoteIndexes(db.characterQuoteDao().getAllQuotesList())
        val quoteCodesSeen = mutableSetOf<String>()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(R-33).
                val r = readQuoteRow(row, qc, "명대사 행 ${excelRow(i)}", result)
                val charName = r.charName
                if (charName.isBlank()) continue

                if (r.text.isBlank()) {
                    result.skippedRows++
                    result.warnings.add("명대사 행 ${excelRow(i)}: 대사가 비어 있어 건너뛰었습니다")
                    continue
                }
                val charCode = r.charCode

                // 임자 찾기: 코드 우선 → 이름 엄격 조회(동명이인 모호성 감지). 상태변화와 같다.
                val character: Character = when {
                    charCode.isNotBlank() -> {
                        val found = characterByCode(charCode)
                        if (found == null) {
                            result.skippedRows++
                            result.errors.add("명대사 행 ${excelRow(i)}: 코드 '${charCode}'에 해당하는 캐릭터를 찾을 수 없음")
                            continue
                        }
                        found
                    }
                    else -> {
                        val hintNovelId = (novelTitles.resolve(r.novelTitle, null) as? NovelTitleLookup.Found)?.novel?.id
                        when (val resolved = resolveCharByNameNovel(charName, hintNovelId)) {
                            is CharLookupResult.Found -> resolved.character
                            is CharLookupResult.Ambiguous -> {
                                result.skippedRows++
                                result.errors.add("명대사 행 ${excelRow(i)}: '${charName}' 이름의 캐릭터가 ${resolved.count}명 존재합니다. '작품' 열이나 캐릭터코드 열로 구분하세요.")
                                continue
                            }
                            is CharLookupResult.NotFound -> {
                                result.skippedRows++
                                result.errors.add("명대사 행 ${excelRow(i)}: 캐릭터 '${charName}'을(를) 찾을 수 없음")
                                continue
                            }
                        }
                    }
                }

                val fileCode = r.fileCode
                if (fileCode.isNotBlank() && !quoteCodesSeen.add(fileCode)) {
                    result.warnings.add("명대사 행 ${excelRow(i)}: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 대사를 덮어씁니다")
                }
                // 매칭: 코드 우선(대사 글자 편집을 같은 행으로 인식) → 자연키 폴백(구버전 파일 호환)
                val quoteByCodeMatch = if (fileCode.isNotBlank()) quotes.byCode.first(fileCode) else null
                val existing = quoteByCodeMatch
                    ?: quotes.byNaturalKey.first(QuoteNaturalKey(character.id, r.text))
                if (quoteByCodeMatch == null && existing != null && existing.id in matchedQuoteIds) {
                    result.warnings.add("명대사 행 ${excelRow(i)}: 같은 캐릭터·같은 대사가 파일 내에서 중복되어 같은 행을 덮어씁니다")
                }

                if (existing != null) {
                    // 적용도 미리보기와 **같은 함수**다(R-33).
                    val merged = mergeQuote(existing, r, character.id, generateEntityCode())
                    db.characterQuoteDao().update(merged)
                    // 자연키의 칸(대사 글자)이 바뀌었을 수 있다 — 옛 키를 끊지 않으면 뒤 행이
                    // **이미 다른 대사가 된 행**을 옛 글자로 다시 잡는다.
                    quotes.remember(merged)
                    matchedQuoteIds.add(existing.id)
                    if (merged != existing) result.updatedQuotes++ else result.unchangedRows++
                } else {
                    val newQuote = newQuoteFrom(
                        r, character.id, nowMillis,
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    )
                    val newId = db.characterQuoteDao().insert(newQuote)
                    quotes.remember(newQuote.copy(id = newId))
                    matchedQuoteIds.add(newId)
                    result.newQuotes++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("명대사 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "명대사", sheet.lastRowNum, totalRows)
    }

    // ── 캐릭터 관계 가져오기 ──

    private suspend fun importRelationships(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = relationshipSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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
        // **키 모양과 싣는 순서(SQL의 `ORDER BY displayOrder ASC, createdAt DESC`)는 미리보기와
        // 같은 클래스가 든다**(B-236 — `util/ImportIdentityIndexes.kt`).
        val rels = RelationshipIndexes(db.characterRelationshipDao().getAllRelationships())

        // 세력 참조 해석은 FactionIndex(단일 소스)로 — 전 세계관 first-match 금지
        val factionRefUsed = rc.faction >= 0 || rc.factionCode >= 0
        val factionIndex = FactionIndex(if (factionRefUsed) db.factionDao().getAllFactionsList() else emptyList())
        val universeNames = if (factionRefUsed) db.universeDao().getAllUniversesList().associate { it.id to it.name } else emptyMap()
        // 수동 관계 → 세력 자동 관계로 승격된 행. 루프 종료 후 1회 고지한다(행마다 경고하면 잡음).
        val factionAttachedRows = mutableListOf<Int>()
        val entitySeen = mutableMapOf<Long, Int>()
        // 코드 열이 없는 구버전 파일에서만: 새 관계를 만든 쌍 → (행번호, 표시명). 루프 종료 후 잔여 관계를 1회 집계한다.
        val touchedPairs = mutableMapOf<Set<Long>, Pair<Int, String>>()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val rv = readRelationshipRow(row, rc, "관계 행 ${excelRow(i)}", result)
                val char1Name = rv.char1Name
                val char2Name = rv.char2Name
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val relationshipType = rv.relationshipType
                if (relationshipType.isBlank()) {
                    // F1-B: 두 캐릭터가 채워졌는데 관계 유형만 비어 있으면 조용히 버리지 않고 경고
                    result.skippedRows++
                    result.warnings.add("관계 행 ${excelRow(i)}: '$char1Name'–'$char2Name' 관계 유형이 비어 있어 건너뜀 (필수 항목)")
                    continue
                }
                val description = rv.description
                val char1Code = rv.char1Code
                val char2Code = rv.char2Code
                val char1 = when (val r = findCharacterStrict(char1Name, char1Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계 행 ${excelRow(i)}: '${char1Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터1코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계 행 ${excelRow(i)}: 캐릭터1 '${char1Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                val char2 = when (val r = findCharacterStrict(char2Name, char2Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계 행 ${excelRow(i)}: '${char2Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터2코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계 행 ${excelRow(i)}: 캐릭터2 '${char2Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                if (char1.id == char2.id) {
                    result.skippedRows++
                    result.errors.add("관계 행 ${excelRow(i)}: 자기 자신과의 관계는 허용되지 않습니다")
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
                            warnFactionCodeFallback("관계 행 ${excelRow(i)}", factionCode, fr, factionName, result)
                            warnFactionUniverseMismatch("관계 행 ${excelRow(i)}", fr.faction, hintUniverseId, "캐릭터 '${char1.name}'", universeNames, result)
                            // 이름과 코드가 서로 다른 세력을 가리키면 코드를 따르고 교정 경로를 안내한다
                            if (fr.matchedByCode && factionName.isNotBlank() && fr.faction.name != factionName) {
                                result.warnings.add("관계 행 ${excelRow(i)}: '세력'('$factionName')과 '세력코드'가 가리키는 세력('${fr.faction.name}')이 달라 코드를 따랐습니다 — 다른 세력으로 바꾸려면 '세력코드' 칸도 함께 비우세요")
                            }
                            resolvedFaction = fr.faction
                        }
                        is FactionLookupResult.Ambiguous -> {
                            // 모호를 '찾을 수 없음'으로 보고하면 사실과 다른 경고가 된다 — 사유를 정확히 밝힌다
                            result.warnings.add(factionAmbiguityMessage("관계 행 ${excelRow(i)}", factionName, fr, "세력코드", universeNames::get) + unresolvedTail)
                            factionIntent = RefIntent.KEEP
                        }
                        FactionLookupResult.NotFound -> {
                            result.warnings.add("관계 행 ${excelRow(i)}: 세력 '${factionName.ifBlank { factionCode }}'을(를) 찾을 수 없음$unresolvedTail ('세력' 시트를 함께 가져오세요)")
                            factionIntent = RefIntent.KEEP
                        }
                    }
                }
                val factionId = resolvedFaction?.id

                // 매칭 규약은 **미리보기와 같은 함수**가 든다(R-33·R-53) — 코드(안정 식별자)
                // 우선 → 자연키(쌍+유형) 폴백이되, **코드가 이 행과 같은 두 사람의 것일 때만** 따른다.
                val relCode = rv.relCode
                val match = rels.matchRow(relCode, char1.id, char2.id, relationshipType)
                if (relCode.isNotBlank() && match.existing == null && match.codeOfOtherPair == null) {
                    result.warnings.add("관계 행 ${excelRow(i)}: 코드 '$relCode'를 찾지 못해 캐릭터·유형으로 매칭합니다 — 의도한 새 관계면 코드를 비우세요")
                }
                match.codeOfOtherPair?.let { other ->
                    // **행을 복사하면 회색 '코드' 칸이 따라온다** — 그 코드를 따르면 남의 관계가
                    // 이 행의 값으로 덮이고 이 행이 말한 관계는 만들어지지 않는다(둘 다 말이 없다).
                    val otherPair = listOfNotNull(
                        db.characterDao().getCharacterById(other.characterId1)?.name,
                        db.characterDao().getCharacterById(other.characterId2)?.name
                    ).joinToString("–").ifBlank { "다른 두 사람" }
                    result.warnings.add("관계 행 ${excelRow(i)}: 코드 '$relCode'는 '$otherPair'의 관계를 가리켜 이 행에는 쓰지 않았습니다 — 행을 복사했다면 '코드' 칸을 비우세요 (이 행은 '${char1Name}'–'${char2Name}'의 관계로 처리합니다)")
                }
                val existing = match.existing
                if (existing != null && relCode.isNotBlank() && match.codeOfOtherPair == null &&
                    existing.relationshipType != relationshipType) {
                    result.warnings.add("관계 행 ${excelRow(i)}: '${char1Name}'–'${char2Name}' 관계 유형을 '${existing.relationshipType}' → '$relationshipType'(으)로 변경했습니다 (코드로 같은 관계 인식)")
                }

                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("관계 행 ${excelRow(i)}: 행 ${excelRow(prevRow)} 과 같은 항목을 다시 덮어씀 — 별개의 관계로 넣으려면 '코드' 칸을 비우고 '관계 유형'을 다르게 한 뒤 다시 가져오세요")
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
                    rels.remember(mergedRelationship)
                    matchedRelationshipIds.add(existing.id)
                    if (mergedRelationship != existing) result.updatedRelationships++ else result.unchangedRows++
                } else {
                    // 잔여 관계 고지는 행마다 하지 않는다 — 같은 쌍의 다른 행이 아직 처리되지 않았을 뿐인데
                    // "정리하세요"라고 안내하면 사용자가 멀쩡한 데이터를 지운다. 루프 종료 후 1회 집계한다.
                    if (rc.relCode < 0) touchedPairs.putIfAbsent(setOf(char1.id, char2.id), i to "${char1Name}–${char2Name}")
                    val newRelationship = newRelationshipFrom(
                        rv, char1.id, char2.id, factionId, i, nowMillis,
                        // 파일의 코드를 보존해 기기 이전 후에도 왕복 정체성 유지 (없으면 자동 생성).
                        // **남이 이미 든 코드는 못 쓴다** — 코드 열이 유니크라 넣기 자체가 실패한다.
                        code = relCode.takeIf { it.isNotBlank() && match.canReuseFileCode }
                            ?: generateEntityCode()
                    )
                    val newId = db.characterRelationshipDao().insert(newRelationship)
                    // 같은 쌍·같은 코드를 든 뒷 행이 이것을 봐야 한다 — 못 보면 같은 관계가
                    // 한 파일에서 둘로 갈린다(위 '다시 덮어씀' 고지가 그때 안 뜬다).
                    rels.remember(newRelationship.copy(id = newId))
                    matchedRelationshipIds.add(newId)
                    entitySeen[newId] = i
                    result.newRelationships++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("관계 행 ${excelRow(i)}: ${e.message}")
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
                    "관계(행 ${excelRow(rowNo)} 부근): '$label'에 시트에 없는 기존 관계(${leftovers.joinToString("/") { it.relationshipType }})가 남아 있습니다 — 관계 유형을 고쳐 쓴 것이라면 앱에서 정리하세요"
                )
            }
        }
        // 세력 연결 부여는 관계의 수명을 바꾼다(세력 삭제·멤버 탈퇴 시 함께 삭제) — 1회 집계해 알린다.
        // 무편집 왕복에서는 factionId가 이미 같아 계수되지 않으므로 거짓 경고가 나가지 않는다.
        if (factionAttachedRows.isNotEmpty()) {
            val sample = factionAttachedRows.take(5).joinToString(", ") { excelRow(it).toString() }
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
        val headerRow = headerRowOrReport(sheet, "캐릭터1", result) ?: return

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
        // **그 순서를 미리보기와 같은 클래스가 든다**(B-236 — `util/ImportIdentityIndexes.kt`).
        val parentRels = RelationshipIndexes(db.characterRelationshipDao().getAllRelationships())
        val relChanges = RelationshipChangeIndexes(db.characterRelationshipChangeDao().getAllChanges())

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val rv = readRelChangeRow(row, rcc, "관계변화 행 ${excelRow(i)}", result)
                val char1Name = rv.char1Name
                val char2Name = rv.char2Name
                if (char1Name.isBlank() || char2Name.isBlank()) continue

                val year = rv.year
                if (year == null) {
                    result.skippedRows++
                    result.errors.add("관계 변화 행 ${excelRow(i)}: 연도 '${rv.yearRaw}'을(를) 숫자로 해석할 수 없어 행을 건너뛰었습니다")
                    continue
                }
                val description = rv.description
                val eventId = resolveRelChangeEventId(rv, "관계변화 행 ${excelRow(i)}", result)
                val char1Code = rv.char1Code
                val char2Code = rv.char2Code

                val char1 = when (val r = findCharacterStrict(char1Name, char1Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 ${excelRow(i)}: '${char1Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터1코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 ${excelRow(i)}: 캐릭터1 '${char1Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }
                val char2 = when (val r = findCharacterStrict(char2Name, char2Code)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 ${excelRow(i)}: '${char2Name}' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터2코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("관계변화 행 ${excelRow(i)}: 캐릭터2 '${char2Name}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                // 부모 관계 해석: 부모관계유형 열 우선 → 쌍 후보가 유일할 때만 폴백.
                // 같은 쌍에 유형이 다른 관계가 여러 개일 수 있으므로(유니크 키가 쌍+유형),
                // 근거 없이 first-match로 고르면 이력이 엉뚱한 관계에 붙는다.
                val pairRelationships = parentRels.pair(char1.id, char2.id)
                if (pairRelationships.isEmpty()) {
                    result.skippedRows++
                    result.errors.add("관계변화 행 ${excelRow(i)}: '${char1Name}'과(와) '${char2Name}' 간의 관계를 찾을 수 없음")
                    continue
                }
                // 부모 관계 해석도 미리보기와 **같은 함수**다(규약 R-33).
                val relationship = resolveRelChangeParent(rv, pairRelationships, char1.id, char2.id, "관계변화 행 ${excelRow(i)}", result)
                if (relationship == null) {
                    result.skippedRows++
                    result.errors.add(
                        "관계변화 행 ${excelRow(i)}: '${char1Name}'–'${char2Name}' 사이에 관계가 ${pairRelationships.size}개 있어 어느 관계의 이력인지 확정할 수 없습니다 — '부모관계유형' 열에 대상 관계의 유형(${pairRelationships.joinToString("/") { it.relationshipType }})을 적으세요"
                    )
                    continue
                }

                val fileCode = rv.fileCode
                if (fileCode.isNotBlank() && !changeCodesSeen.add(fileCode)) {
                    result.warnings.add("관계변화 행 ${excelRow(i)}: 코드 '${fileCode}'가 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }
                // 매칭: 코드 우선(연월일 편집을 같은 이력으로 인식) → 자연키 폴백(구버전 파일 호환)
                val relChangeByCodeMatch = if (fileCode.isNotBlank()) relChanges.byCode.first(fileCode) else null
                val existing = relChangeByCodeMatch
                    ?: relChanges.byNaturalKey.first(RelChangeNaturalKey(relationship.id, year, rv.month, rv.day))
                // 파일 내 중복 고지는 코드 갈래만 있었다(위 줄) — 자연키 갈래도 같은 규약으로 고지한다
                // (연표 I2-5와 같은 모양 — 이 시트가 이미 쓴 이력을 자연키로 다시 잡으면 무고지로 덮었다).
                if (relChangeByCodeMatch == null && existing != null && existing.id in matchedRelationshipChangeIds) {
                    result.warnings.add("관계변화 행 ${excelRow(i)}: 같은 관계·연월일의 행이 파일 내에서 중복되어 같은 이력을 덮어씁니다")
                }
                if (existing != null) {
                    // 빈칸=삭제 집계(변수 제어): 열이 있고 값이 비었는데 기존값이 있으면 초기화로 계수
                    if (rv.hasDescCol && description == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedRelChange =
                        mergeRelationshipChange(existing, rv, relationship.id, eventId, generateEntityCode())
                    db.characterRelationshipChangeDao().update(mergedRelChange)
                    // 자연키의 칸(부모 관계·연월일)이 바뀌었을 수 있다 — 옛 키를 끊어야
                    // 뒤 행이 **이미 옮겨 간 이력**을 옛 키로 다시 잡지 않는다(B-210).
                    relChanges.remember(mergedRelChange)
                    matchedRelationshipChangeIds.add(existing.id)
                    if (mergedRelChange != existing) result.updatedRelationshipChanges++ else result.unchangedRows++
                } else {
                    val newRelChange = newRelationshipChangeFrom(
                        rv, relationship.id, year, eventId, nowMillis,
                        code = fileCode.takeIf { it.isNotBlank() } ?: generateEntityCode()
                    )
                    val newId = db.characterRelationshipChangeDao().insert(newRelChange)
                    relChanges.remember(newRelChange.copy(id = newId))
                    matchedRelationshipChangeIds.add(newId)
                    result.newRelationshipChanges++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("관계 변화 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "관계 변화", sheet.lastRowNum, totalRows)
    }

    // ── 이름 은행 가져오기 ──

    private suspend fun importNameBank(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = nameBankSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val nbc = NameBankCols(resolveHeaderColumns(headerRow))
        val nowMillis = System.currentTimeMillis()

        // 자연키 맵은 이미 있었다 — **코드 축만 빠져 있어 행마다 표를 다시 물었다**(B-210).
        // 같은 목록에서 함께 짓는다. id 오름차순이 `LIMIT 1`의 순서다.
        //
        // **자연키 쪽도 같은 색인으로 옮겼다 — 손코딩 맵이 성질 ③을 어기고 있었다.**
        // `mergeNameBankEntry`는 **이름·성별을 고친다**(코드로 매칭된 행의 편집을 반영한다).
        // 종전 맵은 새 키만 더하고 **옛 키를 그대로 두었다.** 그래서 한 파일 안에서
        // *코드로 '가/남' → '나/남'으로 개명한 뒤 코드 없는 '가/남' 행이 또 나오면*, 그 행이
        // 새 항목이 되는 대신 **방금 개명한 항목을 '가'로 되돌렸다** — 표를 다시 물었다면
        // '가'인 항목은 없다. 색인이 그 되돌림을 없앤다(`put`이 옛 키를 끊는다).
        // **같은 자연키가 둘일 때 고르는 쪽도 바뀐다**(`associateBy`의 마지막 → 먼저 실린 것) —
        // 이 자리는 SQL 질의가 아니라 순수 메모리 맵이라 흉내 낼 원본이 없고, 파일의 나머지가
        // 전부 *먼저 실린 것*으로 서 있으므로 그쪽에 맞춘다.
        val allNames = db.nameBankDao().getAllNamesList().sortedBy { it.id }
        val nameBankNaturalKeys = ImportLookupIndex<String, NameBankEntry>(
            idOf = { it.id }, keyOf = { it.mapKeyForNameBank() }
        )
        val nameBankCodes = ImportLookupIndex<String, NameBankEntry>(
            idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
        )
        // 성별 열이 없는 파일의 폴백 — 자연키(이름+성별)를 지을 수 없으므로 이름 단독으로
        // 유일할 때만 매칭한다(여럿이면 근거 없이 고르지 않는다 — 4-3 규약).
        val nameBankByName = ImportLookupIndex<String, NameBankEntry>(
            idOf = { it.id }, keyOf = { it.name }
        )
        nameBankNaturalKeys.load(allNames)
        nameBankCodes.load(allNames)
        nameBankByName.load(allNames)
        val nameBankCodesSeen = mutableSetOf<String>()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readNameBankRow(row, nbc, "이름 은행 행 ${excelRow(i)}", result)
                val name = r.name
                if (name.isBlank()) continue

                // 파일 내 중복은 형제 시트(연표·상태변화·관계변화·대결 기록) 전부가 고지하는데
                // 이 시트만 침묵이었다(IMP3-7) — 같은 규약(마지막 행 우선 + 고지)으로 말한다.
                if (r.code.isNotBlank() && !nameBankCodesSeen.add(r.code)) {
                    result.warnings.add("이름 은행 행 ${excelRow(i)}: 코드 '${r.code}'가 파일 내에서 중복되어 같은 항목을 덮어씁니다")
                }
                // F3-D: 코드 우선 매칭(이름/성별을 편집해도 같은 항목 인식) → 자연키(이름+성별) 폴백
                // → 성별 열이 없는 파일은 이름 단독 유일 폴백
                val nameBankByCodeMatch = if (r.code.isNotBlank()) nameBankCodes.first(r.code) else null
                val existing = nameBankByCodeMatch
                    ?: r.mapKey?.let { nameBankNaturalKeys.first(it) }
                    ?: (if (r.gender == null) nameBankByName.all(name).singleOrNull() else null)
                // 자연키(이름+성별)·이름 단독 갈래도 같은 규약으로 고지한다(연표 I2-5와 같은 모양).
                if (nameBankByCodeMatch == null && existing != null && existing.id in matchedNameBankIds) {
                    result.warnings.add("이름 은행 행 ${excelRow(i)}: 같은 이름의 행이 파일 내에서 중복되어 같은 항목을 덮어씁니다 — 다른 항목이라면 코드로 구분해 주세요")
                }

                val usedByCharacterId = resolveNameBankUsedBy(r, existing, "이름 은행 행 ${excelRow(i)}", result)
                val effectiveUsed = r.usedFlag ?: existing?.isUsed ?: false
                // 참조를 조회했는데 해석에 실패하면 연결이 조용히 끊긴다 —
                // 사용 표시는 보존하고 연결만 비운 뒤 고지한다(무음 상태 변경 금지).
                if (effectiveUsed && r.usedIntent == RefIntent.LOOKUP && usedByCharacterId == null) {
                    result.warnings.add(
                        "이름 은행 행 ${excelRow(i)}: 사용 캐릭터 '${r.usedByCharName.ifBlank { r.usedByCharCode }}'을(를) 찾을 수 없어 " +
                        "연결 없이 '사용 중'으로 남겨둡니다 — '사용캐릭터코드' 열로 지정하거나 '사용 캐릭터' 칸을 비워 해제하세요"
                    )
                }

                if (existing != null) {
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val merged = mergeNameBankEntry(existing, r, usedByCharacterId)
                    db.nameBankDao().update(merged)
                    nameBankNaturalKeys.put(merged)
                    nameBankCodes.put(merged)
                    nameBankByName.put(merged)
                    matchedNameBankIds.add(existing.id)
                    if (merged != existing) result.updatedNameBank++ else result.unchangedRows++
                } else {
                    // 파일의 코드를 보존해 백업/기기이전 후에도 왕복 정체성 유지 (없으면 자동 생성)
                    val newCode = if (r.code.isNotBlank()) r.code else generateEntityCode()
                    val newEntry = NameBankEntry(
                        name = name, gender = r.gender ?: "", origin = r.origin ?: "", notes = r.notes ?: "",
                        isUsed = r.usedFlag ?: false, usedByCharacterId = usedByCharacterId,
                        createdAt = r.createdAt ?: nowMillis, code = newCode
                    )
                    val newId = db.nameBankDao().insert(newEntry)
                    matchedNameBankIds.add(newId)
                    // 같은 코드·같은 자연키를 든 뒷 행이 이것을 봐야 한다.
                    nameBankNaturalKeys.put(newEntry.copy(id = newId))
                    nameBankCodes.put(newEntry.copy(id = newId))
                    nameBankByName.put(newEntry.copy(id = newId))
                    result.newNameBank++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("이름 은행 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "이름 은행", sheet.lastRowNum, totalRows)
    }

    // ── 필드 템플릿 가져오기 ──

    // 미리보기 짝: analyzePresetTemplates
    private suspend fun importUserPresetTemplates(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = userPresetTemplateSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readPresetTemplateRow(row, ptc, "필드 템플릿 행 ${excelRow(i)}", result)
                val name = r.name
                if (name.isBlank()) continue

                when (val match = matcher.claim(name, r.createdAt, i)) {
                    is PresetTemplateMatcher.Match.Matched -> {
                        match.warnings.forEach { result.warnings.add("필드 템플릿 행 ${excelRow(i)}: $it") }
                        if (match.nameBased) result.nameBasedMappings++
                        val existing = db.userPresetTemplateDao().getTemplateById(match.id)
                        if (existing == null) {
                            // 이론상 도달 불가(같은 트랜잭션 안) — 무음 스킵 금지 차원의 방어
                            result.skippedRows++
                            result.errors.add("필드 템플릿 행 ${excelRow(i)}: 템플릿(id=${match.id})을 다시 읽지 못해 건너뛰었습니다")
                        } else {
                            // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                            val mergedTemplate = mergePresetTemplate(existing, r, nowMillis)
                            db.userPresetTemplateDao().update(mergedTemplate)
                            if (mergedTemplate != existing) result.updatedPresetTemplates++ else result.unchangedRows++
                        }
                    }
                    is PresetTemplateMatcher.Match.New -> {
                        match.warnings.forEach { result.warnings.add("필드 템플릿 행 ${excelRow(i)}: $it") }
                        // 신규는 엔티티 기본값 (갱신=F1-A, 신규=기본값 분리 규약)
                        val newTemplate = newPresetTemplateFrom(r, nowMillis)
                        val newId = db.userPresetTemplateDao().insert(newTemplate)
                        matcher.register(newId, newTemplate.name, newTemplate.createdAt, i)
                        result.newPresetTemplates++
                    }
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("필드 템플릿 행 ${excelRow(i)}: ${e.message}")
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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val spc = SearchPresetCols(resolveHeaderColumns(headerRow))
        val nowMillis = System.currentTimeMillis()

        val existingPresets = db.searchPresetDao().getAllPresetsList()
        val existingByName = existingPresets.associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readSearchPresetRow(row, spc, "검색 프리셋 행 ${excelRow(i)}", filterIndex, result)
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
                result.errors.add("검색 프리셋 행 ${excelRow(i)}: ${e.message}")
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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val lpc = ListPresetCols(resolveHeaderColumns(headerRow))
        val nowMillis = System.currentTimeMillis()

        val existingByName = db.characterListPresetDao().getAllPresetsList()
            .associateBy { it.name }.toMutableMap()
        val filterIndex = fieldFilterIndex()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readListPresetRow(row, lpc, "목록 프리셋 행 ${excelRow(i)}", filterIndex, result)
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
                result.errors.add("목록 프리셋 행 ${excelRow(i)}: ${e.message}")
            }
        }

        val totalAfter = db.characterListPresetDao().getPresetCount()
        if (PresetLimit.exceeded(totalAfter)) {
            result.warnings.add("목록 프리셋이 ${totalAfter}개로 인앱 권장 한도(${PresetLimit.RECOMMENDED_MAX}개)를 초과했습니다 — 캐릭터 탭에서 정리할 수 있습니다")
        }
        reportProgress(onProgress, "목록 프리셋", sheet.lastRowNum, totalRows)
    }

    // ── 앱 설정 가져오기 ──

    /**
     * '앱 설정'의 복원 미리보기 (B-263 ⓑ) — [importAppSettings]의 짝.
     *
     * **'신규'는 0으로 둔다.** 설정 키는 늘 존재하므로 그 셈이 이 범주에서 성립하지 않는다.
     * 나머지 셋(갱신·동일·건너뜀)은 그대로 성립한다 — [AppSettingsBindings.Binding]이
     * `write`와 함께 `read`를 들고 있어 **지금 값을 떠 견줄 수 있기 때문**이다.
     * 처분 판정은 순수 [AppSettingsDiff]가 든다(R-33 — 짝이 같은 술어를 봐야 한다).
     *
     * **`existingTotal`은 이 버전이 아는 설정의 수**이고, 그래서 `onlyInDb`는
     * *파일이 안 실은 설정의 수*가 된다(비밀 제외로 내보낸 파일이 그렇다). 그 숫자는 참이지만
     * **덮어쓰기가 그것을 지우지는 않으므로** `deletedByOverwrite = false`를 함께 낸다.
     */
    private suspend fun analyzeAppSettings(workbook: Workbook, onProgress: (ImportProgress) -> Unit, totalRows: Int): CategoryAnalysis {
        val spec = appSettingsSpec()
        val label = "앱 설정"
        // 이 버전이 아는 설정의 수 — 비밀까지 포함해 센다(파일이 실었으면 그것도 대상이다).
        val existingTotal = AppSettingsKeys.exported(includeSecrets = true).size
        // 한 줄로 적는다 — `check_restore_preview_parity.sh` 축 ⑥이 세는 칸을
        // `CategoryAnalysis(…)` **한 줄 안의 셋째 인자**에서 뜬다(형제 analyze들과 같은 꼴).
        fun empty() = CategoryAnalysis("appSettings", label, 0, 0, 0, 0, existingTotal, skippedCount = 0, deletedByOverwrite = false)
        val sheet = sheetForAnalysis(workbook, spec) ?: return empty()
        if (sheet.lastRowNum < 1) return empty()
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return empty()

        val cols = resolveHeaderColumns(headerRow)
        val keyColIndex = cols["설정키"] ?: 0
        val valueColIndex = cols["설정값"] ?: -1
        val ctx = appContext

        var inBackup = 0; var updateCount = 0; var unchangedCount = 0; var skippedCount = 0
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            val key = getCellString(row, keyColIndex)
            // 완전히 빈 행은 세지 않는다 — 가져오기도 고지 없이 넘긴다(표 아래 여백이다).
            if (key.isBlank()) continue
            inBackup++
            // **'설정값' 열이 없으면 가져오기는 시트를 통째로 건너뛴다** — 그 행들은 실행되지
            // 않으므로 전부 건너뜀이다(값 열이 없으면 시트가 값을 말할 수 없다).
            // `ctx`가 없는 것도 같은 처분이다 — 지금 값을 뜰 수 없으면 견줄 근거가 없고,
            // 모르는 것을 '동일'로 접으면 바뀔 설정이 미리보기에서 사라진다.
            if (valueColIndex < 0 || ctx == null) { skippedCount++; continue }
            val binding = AppSettingsBindings.bindingOf(key)
            // 읽기가 터진 것도 `null`로 접는다 — *값이 없다*와 *못 읽었다*는 다르지만
            // **처분은 같다**(둘 다 견줄 근거가 없으니 파일 값이 들어간다고 봐야 한다).
            // 반대로 접으면 바뀔 설정을 '동일'이라 예고하게 되고, 그것이 유실 쪽 거짓이다.
            val current = if (binding == null) null else runCatching { binding.read(ctx) }.getOrNull()
            when (AppSettingsDiff.effectOf(binding?.spec, getCellString(row, valueColIndex), current)) {
                AppSettingsDiff.Effect.UPDATE -> updateCount++
                AppSettingsDiff.Effect.UNCHANGED -> unchangedCount++
                AppSettingsDiff.Effect.SKIPPED -> skippedCount++
            }
        }
        reportProgress(onProgress, "앱 설정 분석", sheet.lastRowNum, totalRows)
        return CategoryAnalysis("appSettings", label, inBackup, 0, updateCount, unchangedCount, existingTotal, skippedCount = skippedCount, deletedByOverwrite = false)
    }

    // 미리보기 짝: analyzeAppSettings
    private suspend fun importAppSettings(workbook: Workbook, result: ImportResult) {
        val spec = appSettingsSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow)
        val keyColIndex = cols["설정키"] ?: 0
        // 위치 폴백 금지 — '설정값' 열을 지운 파일에서 이웃 열(또는 빈칸)이 값으로 읽혀
        // 불리언·테마 등 빈 값을 유효값으로 읽는 바인딩 전부가 무음 초기화되고 '복원 N건'으로
        // 계수됐다. 값 열이 없으면 시트가 값을 말할 수 없으므로 통째로 건너뛰고 사유를 남긴다.
        val valueColIndex = cols["설정값"] ?: -1
        if (valueColIndex < 0) {
            result.warnings.add("앱 설정: '설정값' 열을 찾을 수 없어 앱 설정을 건너뜁니다 — 열 이름을 되돌리거나 열을 추가해 주세요")
            return
        }
        // 같은 키가 여러 행에 있어도 한 번만 센다(집합) — 행 수가 아니라 *무엇을* 모르는가가 사실이다.
        val unknownSettingKeys = linkedSetOf<String>()

        for (i in dataRows(sheet, headerRow)) {
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
                    is AppSettingsBindings.Applied.Adjusted -> {
                        // 값이 들어가긴 했으므로 복원으로 세되, **적힌 그대로가 아니라는 사실을
                        // 함께 싣는다** — 종전에는 접힘이 Yes로 계수되어 '복원 N건' 뒤에 숨었다
                        // (image_quality_percent에 500을 적으면 100으로 접히는데 아무 말이 없었다).
                        result.restoredSettings++
                        val cut = truncateForCell(value, SETTING_VALUE_IN_WARNING)
                        val shown = if (cut.length < value.length) cut + "…" else value
                        result.warnings.add("앱 설정 행 ${excelRow(i)}: $key 값 '$shown' — ${applied.reason}")
                    }
                    is AppSettingsBindings.Applied.No -> {
                        // **값을 통째로 싣지 않는다** — AI 메시지 양식은 한 칸이 수천 자라
                        // 경고 한 줄이 화면을 통째로 밀어낸다(그 줄을 읽을 수 없게 된다).
                        // 자르는 것은 truncateForCell(단일 소스) — `take`는 UTF-16 유닛 단위라
                        // 경계에 이모지가 걸리면 짝 잃은 반쪽이 마지막 글자로 남는다.
                        val cut = truncateForCell(value, SETTING_VALUE_IN_WARNING)
                        val shown = if (cut.length < value.length) cut + "…" else value
                        result.warnings.add("앱 설정 행 ${excelRow(i)}: $key 값 '$shown' — ${applied.reason}")
                    }
                }
            } catch (e: Exception) {
                result.errors.add("앱 설정 행 ${excelRow(i)}: ${e.message}")
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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val fc = FactionCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val nowMillis = System.currentTimeMillis()

        // 세력 정체성 색인 (B-210) — 행마다 코드·(이름,세계관)으로 표를 묻던 자리.
        // 목표 규모에서 세력은 150개뿐이라 **여기서 아끼는 시간은 작다.** 그래도 함께 고치는 것은
        // 남겨 두면 *"같은 모양이 어디까지 고쳐졌는가"*를 다음 사람이 다시 세야 하기 때문이다 —
        // 이 저장소가 B-76에서 겪은 그 모양이다.
        // **키 모양과 싣는 순서는 미리보기와 같은 클래스가 든다**(B-236 — `util/ImportIdentityIndexes.kt`).
        val factions = FactionIdentityIndexes(db.factionDao().getAllFactionsList())

        val codesSeen = mutableMapOf<String, Int>()
        val entitySeen = mutableMapOf<Long, Int>()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                // 읽기는 미리보기와 **같은 함수**다(규약 R-33).
                val r = readFactionRow(row, fc, "세력 행 ${excelRow(i)}", result)
                val name = r.name
                if (name.isBlank()) continue

                val universeName = r.universeName
                val universeCode = r.universeCode
                val descriptionFromExcel: String? = r.description
                // 열 있음+빈칸만 거부한다. 열 자체가 없으면(지운 파일·구버전) 기존 세력의
                // 수정은 유형을 지키며 통과하고, 신규 생성만 아래에서 거부된다(R-36).
                if (r.autoRelationType != null && r.autoRelationType.isBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 행 ${excelRow(i)}: 자동관계유형이 비어 있음")
                    continue
                }
                val code = r.code

                // Resolve universe (코드 우선 매칭 — 작품 가져오기와 동일 패턴)
                val universeId: Long
                val resolvedUniverse = universeByCodeOrName(universeCode, universeName)
                if (resolvedUniverse != null) {
                    universeId = resolvedUniverse.id
                } else if (universeName.isNotBlank() || universeCode.isNotBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 행 ${excelRow(i)}: 세계관 '$universeName'을(를) 찾을 수 없음")
                    continue
                } else {
                    result.skippedRows++
                    result.errors.add("세력 행 ${excelRow(i)}: 세계관이 지정되지 않음")
                    continue
                }

                // Duplicate code detection
                if (code.isNotBlank()) {
                    val prevRow = codesSeen[code]
                    if (prevRow != null) {
                        result.warnings.add("세력: 코드 '$code'가 행 ${excelRow(prevRow)} 과 행 ${excelRow(i)} 에 중복됨 (마지막 행 우선)")
                    }
                    codesSeen[code] = i
                }

                // Code-first matching + F1-C: 미지 코드 → 자연키 폴백 + 경고
                val existing: Faction?
                val matchedByName: Boolean
                if (code.isNotBlank()) {
                    val byCode = factions.byCode.first(code)
                    if (byCode != null) {
                        existing = byCode
                        matchedByName = false
                    } else {
                        val byName = factions.byNameKey.first(FactionNameKey(name, universeId))
                        if (byName != null) {
                            existing = byName
                            matchedByName = true
                            result.nameBasedMappings++
                            result.warnings.add("세력 행 ${excelRow(i)}: 코드 '$code'를 찾지 못해 이름 '$name'으로 매칭함 — 의도한 새 세력이면 코드를 비우세요")
                        } else {
                            existing = null
                            matchedByName = false
                            warnCreatedNewByCode("factions", "세력 행 ${excelRow(i)}: 코드 '$code'가 기존 세력에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                        }
                    }
                } else {
                    existing = factions.byNameKey.first(FactionNameKey(name, universeId))
                    matchedByName = existing != null
                    if (matchedByName) {
                        result.nameBasedMappings++
                        result.warnings.add("세력 행 ${excelRow(i)}: 이름 기반 매칭 ('$name') — 코드 사용 권장")
                    }
                }

                if (existing != null) {
                    val prevRow = entitySeen[existing.id]
                    if (prevRow != null) {
                        result.warnings.add("세력 행 ${excelRow(i)}: 행 ${excelRow(prevRow)} 과 같은 항목('$name')을 다시 덮어씀 — 별개의 세력으로 넣으려면 '코드' 칸을 비우고 이름을 다르게 한 뒤 다시 가져오세요")
                    }
                    entitySeen[existing.id] = i
                    if (descriptionFromExcel == "" && existing.description.isNotBlank()) result.clearedFields++
                    // 적용도 미리보기와 **같은 함수**다(규약 R-33).
                    val mergedFaction = mergeFaction(existing, r, universeId)
                    db.factionDao().update(mergedFaction)
                    // 이름·세계관·코드가 바뀌었을 수 있다 — 옛 키를 끊는다(B-210).
                    factions.remember(mergedFaction)
                    matchedFactionIds.add(existing.id)
                    if (mergedFaction != existing) result.updatedFactions++ else result.unchangedRows++
                } else {
                    // 새 세력은 자동관계유형이 있어야 선다 — 열이 없는 파일에서는 만들 수 없다.
                    // 문구는 사실대로 '열이 없다'고 말한다(빈칸과 열 없음은 다른 사실이다 — R-36).
                    if (r.autoRelationType == null) {
                        result.skippedRows++
                        result.errors.add("세력 행 ${excelRow(i)}: '자동관계유형' 열이 없어 새 세력을 만들 수 없음 — 열을 추가해 값을 적어 주세요 (기존 세력의 수정에는 영향 없음)")
                        continue
                    }
                    val newCode = if (code.isNotBlank()) code else generateEntityCode()
                    if (code.isBlank()) result.newCodesGenerated++
                    val newFaction = newFactionFrom(r, newCode, universeId, r.autoRelationType, i, nowMillis)
                    val newId = db.factionDao().insert(newFaction)
                    factions.remember(newFaction.copy(id = newId))
                    matchedFactionIds.add(newId)
                    entitySeen[newId] = i
                    result.newFactions++
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("세력 행 ${excelRow(i)}: ${e.message}")
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
    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 세력 관계(R-33 셋째 · B-233). */
    private fun newFactionRelationshipFrom(
        r: FactionRelationshipMatcher.RowValues,
        factionId1: Long, factionId2: Long, relationType: String, createdAt: Long
    ): FactionRelationship = FactionRelationship(
        factionId1 = factionId1, factionId2 = factionId2, relationType = relationType,
        description = r.description, intensity = r.intensity,
        isBidirectional = r.isBidirectional, displayOrder = r.displayOrder,
        createdAt = createdAt
    )

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
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val factionName = getCellString(row, factionNameColIndex)
                if (factionName.isBlank()) continue
                val charName = getCellString(row, charNameColIndex)
                if (charName.isBlank()) continue

                val factionCode = getCellCode(row, factionCodeColIndex, "세력 가입 행 ${excelRow(i)}", result)
                val charCode = getCellCode(row, charCodeColIndex, "세력 가입 행 ${excelRow(i)}", result)

                // Resolve character (동명이인 모호성 감지 포함)
                // ※ 세력보다 먼저 해석한다 — 동명 세력을 캐릭터의 세계관으로 좁혀야 하기 때문
                val character: com.novelcharacter.app.data.model.Character = when (val r = findCharacterStrict(charName, charCode)) {
                    is CharLookupResult.Found -> r.character
                    is CharLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("세력 소속 행 ${excelRow(i)}: '$charName' 이름의 캐릭터가 ${r.count}명입니다 — '캐릭터코드' 열에 코드를 적어 한 명을 지정하세요")
                        continue
                    }
                    is CharLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 소속 행 ${excelRow(i)}: 캐릭터 '$charName'을(를) 찾을 수 없음")
                        continue
                    }
                }

                // Resolve faction — 코드 우선 → 이름. 동명 세력은 캐릭터의 세계관으로 좁힌다.
                // 전 세계관 first-match 는 B 캐릭터를 A 세력에 무경고로 소속시키고 그 소속으로
                // 자동 관계까지 만들어 오염을 번지게 한다.
                val hintUniverseId = universeIdOfCharacter(character)
                val faction: Faction = when (val fr = factionIndex.resolve(factionName, factionCode, hintUniverseId)) {
                    is FactionLookupResult.Found -> {
                        warnFactionCodeFallback("세력 소속 행 ${excelRow(i)}", factionCode, fr, factionName, result)
                        warnFactionUniverseMismatch("세력 소속 행 ${excelRow(i)}", fr.faction, hintUniverseId, "캐릭터 '${character.name}'", universeNames, result)
                        fr.faction
                    }
                    is FactionLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add(factionAmbiguityMessage("세력 소속 행 ${excelRow(i)}", factionName, fr, "세력코드", universeNames::get))
                        continue
                    }
                    FactionLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 소속 행 ${excelRow(i)}: 세력 '${factionName.ifBlank { factionCode }}'을(를) 찾을 수 없음")
                        continue
                    }
                }

                // 해석 불가는 **경고와 함께** 빈 값이 된다 — 조용히 접으면 DB의 연도가 지워진다.
                val yearRowLabel = "세력 가입 행 ${excelRow(i)}"
                val joinYear = readYearCell(row, joinYearColIndex, yearRowLabel, "가입연도", result)
                val leaveYear = readYearCell(row, leaveYearColIndex, yearRowLabel, "탈퇴연도", result)
                val rawLeaveType = parseFactionLeaveType(if (leaveTypeColIndex >= 0) getCellString(row, leaveTypeColIndex) else "")
                // 탈퇴연도만 적히고 탈퇴유형이 빈 행을 바로잡는다 (B-206) — 판정은 순수
                // (FactionStanding), 미리보기 분석도 **같은 함수**를 쓴다(R-33).
                val leaveType = FactionStanding.leaveTypeForImportedRow(leaveYear, rawLeaveType, leaveTypeColIndex >= 0)
                if (leaveType != rawLeaveType) {
                    result.warnings.add(
                        "세력 소속 행 ${excelRow(i)}: '탈퇴연도'만 적혀 있고 '탈퇴유형'이 비어 있어 '설정상탈퇴'로 채웠습니다 — " +
                            "'순수제거'였거나 아직 탈퇴가 아니라면 그 칸을 고쳐 다시 가져오세요"
                    )
                }
                val departedRelationType = if (departedRelTypeColIndex >= 0) getCellString(row, departedRelTypeColIndex).ifBlank { null } else null
                val departedIntensity = parseIntensityWithWarn(row, departedIntensityColIndex, null, "세력 소속 행 ${excelRow(i)}", result)
                // 열이 없거나 해석 불가면 null — 기존 이력의 생성일을 **유지**한다.
                // 종전에는 해석 불가일 때 현재 시각을 찍어, 매칭은 자연키로 되면서도 생성일만
                // 조용히 바뀌었다(왕복할 때마다 안정 식별자가 흔들리는 자리였다).
                // 해석 불가는 공용 읽기가 경고를 싣는다(변수 제어).
                val parsedCreatedAt = readCreatedAtCell(row, createdAtColIndex, "세력 소속 행 ${excelRow(i)}", result)

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
                        result.warnings.add("세력 소속 행 ${excelRow(i)}: '${faction.name}'–'${character.name}' 소속 상태를 $before → $after (으)로 갱신했습니다")
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
                result.errors.add("세력 소속 행 ${excelRow(i)}: ${e.message}")
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
        var skippedByConflict = 0
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
                // **센 것과 들어간 것을 갈라 센다** — DAO가 `OnConflictStrategy.IGNORE`라
                // 유니크 색인 `(characterId1, characterId2, relationshipType)`에 걸린 행은
                // 예외 없이 -1을 돌려주고 조용히 빠진다. 위 걸러내기는 *이 세력의* 자동 관계와
                // 이번 파일이 기술한 쌍만 보므로, **다른 세력이 같은 유형으로 이미 이어 둔 쌍**은
                // 여기까지 와서 색인에 걸린다. 종전에는 반환값을 버리고 `newRelationships.size`를
                // 그대로 더해 **넣지도 않은 건수를 "생성했습니다"라고 보고했다**(B-230 ⓔ —
                // 인앱 `FactionRepository.insertAutoRelations`는 처음부터 -1을 세고 있었고,
                // 그 둘이 갈려 있던 자리다. 개발 의도 2번 — 거짓 고지 금지).
                val insertedIds = db.characterRelationshipDao().insertAll(newRelationships)
                val ignored = insertedIds.count { it == -1L }
                // **방금 만든 것은 '엑셀에 없는 관계'가 아니다** — 등재하지 않으면 같은
                // 가져오기의 정리('엑셀에 없는 관계 삭제')가 곧바로 도로 지우고, 결과 창은
                // "자동 관계 N건을 생성했습니다"와 "삭제: 관계 N"을 **동시에** 말한다.
                // 이 관계의 근거는 관계 시트가 아니라 **세력 소속 시트**이므로, 관계 시트가
                // 그 쌍을 기술하지 않은 것이 '지워라'가 아니다(같은 파일이 방금 만들라고 했다).
                matchedRelationshipIds.addAll(insertedIds.filter { it != -1L })
                created += insertedIds.size - ignored
                skippedByConflict += ignored
            }
        }
        pendingAutoRelationMemberships.clear()
        if (created > 0) {
            result.warnings.add("세력 소속에 따라 자동 관계 ${created}건을 생성했습니다 (백업의 관계 시트가 기술한 쌍은 그대로 유지)")
        }
        if (skippedByConflict > 0) {
            // 인앱 경로가 이미 세어 보고하던 축이다 — 가져오기만 침묵하면 같은 상황이
            // 어디서 왔느냐에 따라 보이거나 안 보인다.
            result.warnings.add("세력 자동 관계 ${skippedByConflict}건은 같은 두 캐릭터 사이에 같은 유형의 관계가 이미 있어 건너뛰었습니다")
        }
    }

    // ── 세력 관계 (B-3) ──

    private suspend fun importFactionRelationships(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = factionRelationshipSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

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

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val f1Name = getCellString(row, faction1ColIndex)
                if (f1Name.isBlank()) continue
                val f2Name = getCellString(row, faction2ColIndex)
                if (f2Name.isBlank()) continue
                val relType = getCellString(row, typeColIndex).trim()
                if (relType.isBlank()) {
                    result.skippedRows++
                    result.errors.add("세력 관계 행 ${excelRow(i)}: 관계 유형이 비어 있음")
                    continue
                }

                val f1Code = getCellCode(row, faction1CodeColIndex, "세력 관계 행 ${excelRow(i)}", result)
                val f2Code = getCellCode(row, faction2CodeColIndex, "세력 관계 행 ${excelRow(i)}", result)
                // 이 시트에는 세계관 열이 없다 — 한쪽이 확정되면 그 세계관을 상대편 동명 해소의 힌트로 쓴다
                // (인앱 세력 관계는 같은 세계관 안에서만 만들어진다)
                val (r1, r2) = resolveFactionPair(factionIndex, f1Name, f1Code, f2Name, f2Code)
                val faction1 = when (r1) {
                    is FactionLookupResult.Found -> {
                        warnFactionCodeFallback("세력 관계 행 ${excelRow(i)}", f1Code, r1, f1Name, result); r1.faction
                    }
                    is FactionLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add(factionAmbiguityMessage("세력 관계 행 ${excelRow(i)}", f1Name, r1, "세력1코드", universeNames::get))
                        continue
                    }
                    FactionLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 관계 행 ${excelRow(i)}: 세력 '${f1Name.ifBlank { f1Code }}'을(를) 찾을 수 없음")
                        continue
                    }
                }
                val faction2 = when (r2) {
                    is FactionLookupResult.Found -> {
                        warnFactionCodeFallback("세력 관계 행 ${excelRow(i)}", f2Code, r2, f2Name, result); r2.faction
                    }
                    is FactionLookupResult.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add(factionAmbiguityMessage("세력 관계 행 ${excelRow(i)}", f2Name, r2, "세력2코드", universeNames::get))
                        continue
                    }
                    FactionLookupResult.NotFound -> {
                        result.skippedRows++
                        result.errors.add("세력 관계 행 ${excelRow(i)}: 세력 '${f2Name.ifBlank { f2Code }}'을(를) 찾을 수 없음")
                        continue
                    }
                }
                if (faction1.universeId != faction2.universeId) {
                    // 앱은 같은 세계관 안에서만 세력 관계를 만든다 — 그대로 저장하되 조용히 넘기지 않는다
                    result.warnings.add("세력 관계 행 ${excelRow(i)}: '${faction1.name}'(${universeNames[faction1.universeId] ?: "?"})과(와) '${faction2.name}'(${universeNames[faction2.universeId] ?: "?"})은 서로 다른 세계관의 세력입니다 — '세력1코드'·'세력2코드' 열로 확정하세요")
                }
                if (faction1.id == faction2.id) {
                    result.skippedRows++
                    result.errors.add("세력 관계 행 ${excelRow(i)}: 세력1과 세력2가 동일함")
                    continue
                }

                val rowValues = factionRelationshipRowValues(
                    row, descColIndex, intensityColIndex, bidirectionalColIndex, orderColIndex,
                    "세력 관계 행 ${excelRow(i)}", result
                )
                // 신규 행에만 쓰인다(병합은 생성일을 건드리지 않는다) — 빈칸·해석 불가는 지금 시각.
                val createdAt = readCreatedAtCell(row, createdAtColIndex, "세력 관계 행 ${excelRow(i)}", result)
                    ?: System.currentTimeMillis()

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
                    val newRel = newFactionRelationshipFrom(rowValues, faction1.id, faction2.id, relType, createdAt)
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
                result.errors.add("세력 관계 행 ${excelRow(i)}: ${e.message}")
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
        // 헤더 행을 못 찾으면 소리 내어 건너뛴다 (B-231) — 아래 헤더 불일치는 경고를 내면서
        // 헤더 **행 자체**가 없는 파일만 조용히 사라지던 자리다.
        // **문구가 창을 말한다**(B-231 ⓑ) — 이제 맨 앞 몇 행을 훑으므로 *첫 행*이라 말하면
        // 사용자가 줄 하나만 지우고 다시 시도한다(`headerRowOrReport`가 같은 이유로 고쳐졌다).
        val headerRow = headerRowOrFirst(sheet, spec.firstColumnHeader) ?: run {
            result.warnings.add("'${spec.sheetName}' 시트에서 열 이름 행을 찾지 못해 이미지 태그·링크 가져오기를 건너뛰었습니다 — 맨 앞 ${SheetResolver.HEADER_SEARCH_ROWS}행 안에 첫 열이 '${spec.firstColumnHeader}'인 행이 있어야 합니다")
            return
        }
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
        // 미리보기와 **같은 통로**다(B-231 ⓑ · R-33) — 1행 고정이면 헤더 행이 데이터로 세어진다.
        val sheetRows = dataRows(sheet, headerRow).mapNotNull { i ->
            val row = sheet.getRow(i) ?: return@mapNotNull null
            val fileName = getCellString(row, imc.file)
            if (fileName.isBlank()) null else i to fileName
        }
        val plan = ImageMetaRowResolver.plan(sheetRows, remap.byBasename) { fileName ->
            filesDir?.let { dir -> java.io.File(dir, fileName).takeIf { it.exists() }?.absolutePath }
        }
        plan.warnings.forEach { result.warnings.add(it) }

        // 행마다 묻던 정체성·태그 조회를 **시트 크기만큼의 일괄 조회**로 내린다 (B-238).
        // 위 `plan`이 같은 경로를 하나로 접으므로(LinkedHashMap 키가 path — 마지막 행 우선)
        // 루프 안에서 한 경로는 한 번만 돌고, 그래서 **이 색인이 루프 도중 낡을 창이 없다**:
        // 뒤 행이 앞 행이 만든 행을 다시 만나는 경우가 원리적으로 없다.
        //
        // **미리보기(`analyzeImageMeta`)와 달리 표를 통째로 읽지 않는다.** 그쪽은 총계
        // (`existingTotal`)로 어차피 전량을 읽어야 해서 그 읽기 하나가 색인까지 먹이지만,
        // 가져오기는 총계가 필요 없다 — 여기서 `getAllList()`를 쓰면 **시트가 10행이어도
        // 이미지 표 전량을 싣는** 새 비용이 생긴다(비용이 시트가 아니라 DB 크기에 붙는다).
        // 그래서 짝의 모양을 그대로 옮기지 않고 **필요한 경로만** 키로 물었다.
        val wantedPaths = plan.rows.map { it.path }
        val existingByPath: Map<String, com.novelcharacter.app.data.model.ImageMeta> =
            SqlInChunks.flat(wantedPaths) { db.imageMetaDao().getByPaths(it) }
                .associateBy { it.path }
        // 태그는 **열이 있을 때만** 읽는다 — 없으면 비교 대상이 아니라 조회도 낭비다
        // (루프 안의 판정이 `r.hasTagCol`로 갈리는 것과 같은 조건이다).
        val tagsByImage: Map<Long, Set<String>> =
            if (imc.tag >= 0) {
                SqlInChunks.flat(existingByPath.values.map { it.id }) { db.imageTagDao().getTagsByImages(it) }
                    .groupBy({ it.imageId }, { it.tag })
                    .mapValues { (_, v) -> v.toSet() }
            } else emptyMap()

        val now = System.currentTimeMillis()
        // 행마다 왕복 셋을 치르던 `adopt`를 **새로 만들 행만 골라 한 번**으로 내린다 (B-244).
        // 색인과 같은 이유로 시트가 쓴 경로만 본다 — 비용을 DB 크기가 아니라 시트에 묶는다.
        //
        // **실패의 자리가 갈리지 않는 것이 이 모양의 요점이다.** 이 루프에는 감싸는 트랜잭션이
        // 없고 실패는 **행마다** `skippedRows` + "이미지 행 N"으로 보고된다. 그래서 일괄 입양이
        // 죽어도 통째로 던지지 않고 **빈 지도**로 받는다 — 그러면 아래에서 그 행의 `try`가
        // 종전과 같은 자리에서 같은 메시지로 잡는다(B-241 ⑪이 남긴 물음: *실패가 어디서
        // 보고되던 것인가*).
        //
        // **미리 입양하는 것이 "건너뛸 행까지 입양"이 되지 않는 근거:** 루프의
        // `sheet.getRow(i) ?: continue`는 방어일 뿐 실제로 걸리지 않는다 — `plan.rows`의 `i`는
        // 위에서 `sheet.getRow(i)`가 **null이 아닌 행만** 골라 만든 것이다.
        // **남는 차이 하나는 적어 둔다:** `readImageMetaRow`가 던지면 종전에는 그 행이 입양되지
        // 않았고 지금은 이미 입양돼 있다. 결과는 *라이브러리 행 하나가 더 생기는 것*이고
        // 유실이 아니다(고지도 그대로 — `newImageMeta`는 그 줄에 닿아야 오른다).
        val adoptAttempt = runCatching {
            com.novelcharacter.app.util.ImageAdoption.adoptAll(
                db, wantedPaths.filterNot { it in existingByPath }, now
            )
        }
        val adoptedByPath: Map<String, Long> = adoptAttempt.getOrDefault(emptyMap())
        val skippedMissing = plan.unresolved.size
        val groupMembers = mutableMapOf<String, MutableList<Long>>()
        // 빈 칸 = 링크 해제(F1-A 규약 — 태그 열과 같다). 종전에는 빈 칸이 아무 일도 하지 않아
        // "엑셀에서 링크를 지웠는데 그대로"였고 고지도 없었다(설계 9장 C-3).
        val clearedGroupIds = mutableListOf<Long>()
        // 이 가져오기가 **식구를 잃은** 묶음 토큰 — 칸을 비워 해제한 것과 다른 묶음으로
        // 옮겨 간 것을 함께 담는다. 둘은 같은 뒤처리(1장만 남은 묶음의 표식 걷기)를 받는다.
        val clearedGroupTokens = mutableSetOf<String>()
        val vacatedGroupTokens = mutableSetOf<String>()
        var clearedAutoLinks = 0

        for ((i, _, path) in plan.rows) {
            try {
                val row = sheet.getRow(i) ?: continue

                // 읽기도 미리보기와 **같은 함수**다(규약 R-33).
                val r = readImageMetaRow(row, imc, result)

                // 위에서 한 번에 읽어 둔 색인이다 (B-238).
                val existing = existingByPath[path]
                val imageId = existing?.id ?: adoptedByPath[path] ?: throw (
                    adoptAttempt.exceptionOrNull()
                        ?: IllegalStateException("이미지 라이브러리 행을 만들지 못했습니다")
                    )
                if (existing == null) result.newImageMeta++

                // 무엇이 바뀌는가의 판정도 같은 함수다. 태그는 열이 있을 때만 읽는다 —
                // 없으면 비교 대상이 아니라 조회도 낭비다.
                // 갓 `adopt`한 행은 색인에 없고 태그도 없다 — `orEmpty()`가 곧 그 사실이다.
                val current = ImageMetaState(
                    tags = if (r.hasTagCol) tagsByImage[imageId].orEmpty() else emptySet(),
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
                    // 링크 확장은 하지 않는다 — 엑셀 왕복 무결성은 파일이 말한 그대로의 행 단위
                    // 복원이 계약이다(태그 공유 불변식의 유일한 예외 — LinkGroupFold 헤더가 정본).
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
                        result.warnings.add("이미지 행 ${excelRow(i)}: '뗀날짜' 값 \"${r.detachedRaw}\"을(를) 읽을 수 없어 그대로 두었습니다")
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
                        // **떠난 자리도 정리 대상이다** (B-227 ①). 종전에는 '비웠다'만 세고
                        // '옮겼다'는 세지 않아, X에서 Y로 옮긴 뒤 X에 1장만 남으면 그 1장이
                        // 계속 '링크됨'으로 보였다 — 인앱 해제·엑셀 해제 어느 쪽에서도
                        // 남지 않는 표식이라, 같은 파일을 다시 들여도 사라지지 않는다.
                        val leftBehind = existing?.linkGroupId
                        if (leftBehind != null && leftBehind != groupToken) {
                            vacatedGroupTokens.add(leftBehind)
                        }
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
                result.errors.add("이미지 행 ${excelRow(i)}: ${e.message}")
            }
        }

        // 해제를 먼저 반영한다 — 같은 가져오기에서 A가 빠지고 B가 들어오는 경우, 해제를 뒤에
        // 하면 방금 만든 묶음을 도로 지운다.
        if (clearedGroupIds.isNotEmpty()) {
            // **청크가 필요하다 (B-242):** `setGroup`은 `WHERE id IN (:ids)`이고 여기 실리는 것은
            // *시트가 링크를 지운 행 전부*라 상한이 없다. 999를 넘으면 질의가 죽고, 이 자리는
            // 가져오기 트랜잭션 안이라 **한 줄 때문에 가져오기 전체가 되돌아간다.**
            // 같은 부류를 B-51이 이미 한 번 겪었다(그때는 `catch`가 삼켜 조용한 유실이었다).
            SqlInChunks.each(clearedGroupIds) { db.imageMetaDao().setGroup(it, null) }
            result.warnings.add("이미지 ${clearedGroupIds.size}건: '링크그룹' 칸이 비어 있어 링크를 해제했습니다")
            if (clearedAutoLinks > 0) {
                result.warnings.add(
                    "그중 ${clearedAutoLinks}건은 캐릭터 자동 링크라, 그 캐릭터에 계속 등록되어 있으면 " +
                        "다음 재동기화가 다시 묶습니다 (이미지 설정에서 자동 링크를 끌 수 있습니다)"
                )
            }
        }

        // 토큰마다 묻던 `getByGroup`을 **시트가 든 토큰만큼의 일괄 조회 한 번**으로 내린다 (B-239).
        // 읽는 시점이 규약이다 — 해제(위 블록)를 반영한 **뒤**여야 종전 조회가 보던 상태와 같다.
        //
        // **앞의 두 조회(B-238)와 달리 색인 하나로는 끝나지 않는다:** 이 루프는 제 안에서 쓰고
        // (`setGroup(ids, token)`) 그 쓰기가 뒤 토큰의 답을 바꾼다(이미지가 X에서 Y로 옮겨 가면
        // Y를 처리한 뒤의 X 조회는 그것을 식구로 세지 않는다). 그래서 판정을 순수로 내려
        // **쓰기를 흉내 내는 메모리 겹** 위에서 돌린다 — 겹이 없으면 `>= 2` 가드의 답이 조용히
        // 달라진다. 가드·순서·겹 갱신의 정본은 [ImageLinkGroupPlanner]이고 시험이 그것을 든다.
        if (groupMembers.isNotEmpty()) {
            val currentByToken: Map<String, Set<Long>> =
                SqlInChunks.flat(groupMembers.keys) { db.imageMetaDao().getByGroups(it) }
                    .mapNotNull { meta -> meta.linkGroupId?.let { it to meta.id } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, ids) -> ids.toSet() }
            for ((token, ids) in com.novelcharacter.app.util.ImageLinkGroupPlanner.plan(groupMembers, currentByToken)) {
                // 여기도 상한이 없다 — 한 묶음에 실리는 행 수는 시트가 정한다 (B-242).
                // 갈라 불러도 답이 같다: 같은 값을 적는 쓰기라 읽고-고쳐-쓰기가 아니다.
                SqlInChunks.each(ids) { db.imageMetaDao().setGroup(it, token) }
            }
        }

        // 1장만 남은 묶음의 잔존 표식은 오해를 부른다 — 인앱 해제와 같은 정리를 건다.
        // 토큰마다 돌던 쓰기를 일괄판으로 내렸다 (B-239) — 갈라 불러도 답이 같은 근거는
        // `clearSingletonGroups`의 주석(묶음이 행을 나눠 가지므로 서로의 인원을 못 바꾼다).
        //
        // **자리를 옮겼다** (B-227 ①): 종전에는 해제 블록 안에서 돌아 *이동*으로 빈 묶음을
        // 못 봤고, 게다가 **아직 이동 쓰기가 반영되기 전의 인원**을 셌다. 인원을 세는 질의라
        // 모든 쓰기가 끝난 뒤가 유일하게 옳은 자리다 — 이제 해제와 이동이 한 규칙을 받는다.
        val emptiedTokens = clearedGroupTokens + vacatedGroupTokens
        if (emptiedTokens.isNotEmpty()) {
            SqlInChunks.each(emptiedTokens) { db.imageMetaDao().clearSingletonGroups(it) }
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
         * '대상' 칸에 **못 알아본 글자**가 적혀 있었다(빈 칸은 여기 오지 않는다).
         * 값은 기본값(캐릭터)으로 이어 가고 가져오기가 경고 한 줄을 낸다 —
         * 이 열은 축의 정체라 잘못 굳으면 되돌릴 길이 없다(`DuelSheetLabels.targetTypeOrNull`).
         */
        val unknownTargetLabel: String? = null,
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
        /** null = 열 없음·빈칸·해석 불가 — 병합은 기존 순서를 지킨다(다른 전 시트와 같은 규약). */
        val displayOrder: Int?,
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
        // 한국어 말과 저장값을 모두 받는다(대소문자·공백·전각 포함) — 외부 도구가 저장값을
        // 그대로 쓸 수도 있다. **못 알아본 글자는 삼키지 않는다**(아래 `unknownTargetLabel`).
        val parsedTarget = DuelSheetLabels.targetTypeOrNull(targetLabel)
        return DuelAxisRowValues(
            name = cell("축이름"),
            universeName = cell("세계관"),
            universeCode = cell("세계관코드"),
            targetType = parsedTarget ?: DuelAxis.TARGET_CHARACTER,
            unknownTargetLabel = if (parsedTarget == null) targetLabel.trim() else null,
            // 사람이 적은 차례가 곧 영향력 순위다(프로필은 표시 차례다) — 정렬하지 않는다.
            influenceFieldKeys = links("영향필드"),
            outcomeFieldKeys = links("산출필드"),
            profileFieldKeys = links("프로필필드"),
            candidateFiltersJson = filterCell as? DuelCandidateFilter.SheetCell.Value,
            candidateFiltersMalformed = filterCell is DuelCandidateFilter.SheetCell.Malformed,
            isBasisAxis = sheetBooleanOrKeep(cols.containsKey("기준축"), cell("기준축")),
            // ?: 0으로 접지 않는다 — 열을 지우거나 칸을 비운 파일이 모든 축의 순서를 0으로
            // 리셋하던 자리다. null이면 병합이 기존 순서를 지킨다(R-36).
            displayOrder = cell("정렬순서").toDoubleOrNull()?.toInt(),
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
            "대결 축 행 ${excelRow(rowIndex)}: 이 앱에 없는 시스템 열 ${unknown.joinToString(", ")} — " +
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
    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 대결 축(R-33 셋째 · B-233). */
    private fun newDuelAxisFrom(r: DuelAxisRowValues, universeId: Long, code: String): DuelAxis =
        DuelAxis(
            universeId = universeId,
            name = r.name,
            targetType = r.targetType,
            displayOrder = r.displayOrder ?: 0,
            createdAt = r.createdAt,
            influenceFieldKeys = newAxisLinks(r.influenceFieldKeys),
            outcomeFieldKeys = newAxisLinks(r.outcomeFieldKeys),
            profileFieldKeys = newAxisLinks(r.profileFieldKeys),
            candidateFiltersJson = r.candidateFiltersJson?.json,
            // 새 축이라 지킬 기존 값이 없다 — 열이 없으면 엔티티 기본값(꺼짐)이다.
            isBasisAxis = r.isBasisAxis ?: false,
            code = code
        )

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
            displayOrder = r.displayOrder ?: existing.displayOrder
        )

    private suspend fun importDuelAxes(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = duelAxisSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow, result, spec.sheetName)
        val now = System.currentTimeMillis()

        // 대결 축 정체성 색인 (B-210) — 행마다 코드·(세계관,대상,이름)으로 표를 묻던 자리.
        // **키 모양과 싣는 순서는 미리보기와 같은 클래스가 든다**(B-236 — `util/ImportIdentityIndexes.kt`).
        val allAxes = db.duelAxisDao().getAllList()
        val axes = DuelAxisIndexes(allAxes)
        // 이 시트에는 **행 하나가 여러 행을 고치는 자리**가 있다 — `기준축`을 세우면
        // `clearBasisExcept`가 같은 (세계관, 대상)의 **다른 축을 SQL로 통째로 내린다.**
        // 색인은 그 일괄 갱신을 못 보므로 여기서 같은 조건으로 **함께 내린다**(아래 `applyBasisAxis`).
        // **지금 이 순간에는 그 낡음이 결과를 바꾸지 않는다** — `mergeDuelAxis`가 `existing.isBasisAxis`를
        // 읽는 것은 `r.isBasisAxis == null`일 때뿐이고, 그것은 **`기준축` 열이 아예 없다**는 뜻이라
        // (`sheetBooleanOrKeep`) 그 파일에서는 `clearBasisExcept`가 애초에 돌지 않는다. **둘이 서로
        // 배타적이라 살아 있는 결함이 아니다.** 그래도 맞춰 두는 이유는 그 배타가 **다른 파일의 한 줄**에
        // 걸려 있기 때문이다 — 빈 셀을 null로 읽도록 바꾸는 순간 조용히 살아난다(고지도 오류도 없이
        // 기준 축이 앞뒤로 튄다). 값이 아니라 **결합을 없앤다.**
        val axesById = LinkedHashMap<Long, DuelAxis>()
        fun putAxis(axis: DuelAxis) {
            axesById[axis.id] = axis
            axes.remember(axis)
        }
        for (axis in allAxes.sortedBy { it.id }) axesById[axis.id] = axis
        suspend fun applyBasisAxis(universeId: Long, targetType: String, axisId: Long, isBasis: Boolean) {
            enforceSingleBasisAxis(universeId, targetType, axisId, isBasis)
            // 위 함수가 실제로 SQL을 친 조건과 **같은 조건**으로만 미러링한다.
            if (!isBasis || axisId <= 0 || targetType != DuelAxis.TARGET_IMAGE) return
            for (other in axesById.values.toList()) {
                if (other.id != axisId && other.universeId == universeId &&
                    other.targetType == targetType && other.isBasisAxis
                ) {
                    putAxis(other.copy(isBasisAxis = false))
                }
            }
        }

        // 파일 내 중복은 형제 시트 전부가 고지하는데 이 시트만 침묵이었다(B-224 착수 검토 —
        // IMP3-7의 이름 은행·대결 상성과 같은 모양이 여기 하나 더 있었다).
        val axisCodesSeen = mutableSetOf<String>()
        val writtenAxisIds = mutableSetOf<Long>()
        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val r = readDuelAxisRow(row, cols, now)
                if (r.name.isBlank()) continue

                val universe = universeByCodeOrName(r.universeCode, r.universeName)
                if (universe == null) {
                    result.skippedRows++
                    result.errors.add("대결 축 행 ${excelRow(i)}: 세계관 '${r.universeName}'을(를) 찾을 수 없음")
                    continue
                }

                if (r.code.isNotBlank() && !axisCodesSeen.add(r.code)) {
                    result.warnings.add("대결 축 행 ${excelRow(i)}: 코드 '${r.code}'가 파일 내에서 중복되어 같은 축을 덮어씁니다")
                }
                val axisByCodeMatch = if (r.code.isNotBlank()) axes.byCode.first(r.code) else null
                val existing = axisByCodeMatch
                    ?: axes.byNameKey.first(DuelAxisNameKey(universe.id, r.targetType, r.name))
                // (세계관, 대상, 이름) 갈래도 같은 규약으로 고지한다(연표 I2-5와 같은 모양).
                if (axisByCodeMatch == null && existing != null && existing.id in writtenAxisIds) {
                    result.warnings.add("대결 축 행 ${excelRow(i)}: 같은 세계관·대상·이름의 행이 파일 내에서 중복되어 같은 축을 덮어씁니다")
                }
                r.unknownTargetLabel?.let { unknown ->
                    // **정체 열이라 잘못 굳으면 되돌릴 길이 없다** — 축은 (세계관, 대상, 이름)이
                    // 자연키이고 만든 뒤에는 편집 창이 '겨루는 대상' 구역을 감춘다.
                    // 그래서 값은 이어 가되(행을 버리면 나머지 멀쩡한 칸까지 잃는다) 사실과
                    // 고칠 길을 함께 말한다(개발 의도 2번 — 검증 → 알림 → 교정 경로).
                    result.warnings.add(
                        "대결 축 행 ${excelRow(i)}: '대상' 칸의 '$unknown'을(를) 알 수 없어 '${DuelSheetLabels.TARGET_CHARACTER}' 축으로 읽었습니다 — 이미지 축이면 그 칸을 '${DuelSheetLabels.TARGET_IMAGE}'로 고쳐 다시 가져오세요(만든 뒤에는 앱에서 바꿀 수 없습니다)"
                    )
                }
                if (r.candidateFiltersMalformed) {
                    // 기존 값을 지키고 그 사실을 말한다 — 괄호 하나 틀린 손편집이 멀쩡한
                    // 필터를 조용히 지우면 안 된다(개발 의도 2번·4번).
                    result.warnings.add(
                        "대결 축 행 ${excelRow(i)}: 후보필터(JSON) 칸을 읽을 수 없어 기존 필터를 유지했습니다 — 앱에서 다시 내보낸 파일의 형식을 참고해 고쳐 주세요"
                    )
                }
                // 이 앱이 모르는 `sys:` 키 — 오타는 **영영 살아나지 않는다**(B-172).
                // 값은 그대로 담고 사실만 말한다: 거부하면 나머지 멀쩡한 연결까지 잃는다.
                warnUnknownSystemKeys(r, i, result)
                if (existing == null) {
                    // 같은 (세계관, 대상, 이름)이 이미 있으면 유니크 인덱스가 던진다 — 위에서
                    // 이미 찾아봤으므로 여기 오는 것은 진짜 새 축이다.
                    val newAxis = newDuelAxisFrom(r, universe.id, r.code.ifBlank { generateEntityCode() })
                    val newId = db.duelAxisDao().insert(newAxis)
                    // 같은 코드·같은 (세계관,대상,이름)을 든 뒷 행이 이것을 봐야 한다 —
                    // 못 보면 유니크 인덱스가 던져 가져오기가 통째로 실패한다.
                    putAxis(newAxis.copy(id = newId))
                    writtenAxisIds.add(newId)
                    applyBasisAxis(universe.id, r.targetType, newId, r.isBasisAxis ?: false)
                    result.newDuelAxes++
                    if (r.code.isNotBlank()) {
                        warnCreatedNewByCode("duelAxes", "대결 축 행 ${excelRow(i)}: 코드 '${r.code}'가 기존 축에 없어 새로 생성됨 — 오타·삭제 여부를 확인하세요", result)
                    }
                } else {
                    val merged = mergeDuelAxis(existing, r, universe.id)
                    if (merged != existing) {
                        db.duelAxisDao().update(merged)
                        result.updatedDuelAxes++
                    } else result.unchangedRows++
                    putAxis(merged)
                    writtenAxisIds.add(existing.id)
                    // **대상은 기존 축의 것이다** — `mergeDuelAxis`가 targetType을 바꾸지 않으므로
                    // 행에 적힌 대상이 아니라 실제 축의 대상으로 판정해야 한다.
                    applyBasisAxis(
                        universe.id, merged.targetType, merged.id, merged.isBasisAxis
                    )
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("대결 축 행 ${excelRow(i)}: ${e.message}")
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
        // R-36: 열 없음("이 파일은 그것을 말하지 않는다")과 빈칸("무승부/묶음 해제")은 다른 사실이다.
        // 이 두 열이 없던 파일·지운 파일을 들이는 것만으로 전 판의 승패·묶음이 리셋되면 안 된다.
        val hasWinnerCol: Boolean,
        val winnerText: String,
        val hasGroupCol: Boolean,
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
            hasWinnerCol = cols.containsKey("승자"),
            winnerText = cell("승자"),
            hasGroupCol = cols.containsKey("묶음"),
            groupId = cell("묶음"),
            code = cell("코드"),
            decidedAt = cell("판정일", dateHint = true).toDoubleOrNull()?.toLong() ?: now
        )
    }

    /** [newUniverseFrom]과 같은 규약 — 이 행이 **만들** 대결 기록(R-33 셋째 · B-233). */
    private fun newDuelMatchFrom(
        r: DuelMatchRowValues, axisId: Long, aCode: String, bCode: String,
        winnerCode: String?, groupId: String?, code: String
    ): DuelMatch = DuelMatch(
        axisId = axisId, aCode = aCode, bCode = bCode,
        winnerCode = winnerCode, groupId = groupId,
        decidedAt = r.decidedAt, code = code
    )

    private fun mergeDuelMatch(
        existing: DuelMatch,
        r: DuelMatchRowValues,
        winnerCode: String?,
        groupId: String?
    ): DuelMatch =
        // 참가자와 시각은 바꾸지 않는다 — 바꾸면 그 행은 *다른 판*이다(기록 화면과 같은 규약).
        // 열 없음 = 기존 값 유지(R-36) — 빈칸=무승부/해제 규약은 열이 있을 때만 적용된다.
        existing.copy(
            winnerCode = if (r.hasWinnerCol) winnerCode else existing.winnerCode,
            groupId = if (r.hasGroupCol) groupId else existing.groupId
        )

    private sealed class DuelWinner {
        data class Resolved(val code: String?) : DuelWinner()
        /** 두 참가자의 이름이 같아 이름만으로는 승자를 정할 수 없다 — 코드 기입을 안내한다. */
        object Ambiguous : DuelWinner()
        /** 두 참가자 중 어느 쪽도 아니다. */
        object Unknown : DuelWinner()
    }

    /**
     * 승자 칸을 코드로 옮긴다.
     *
     * **빈 칸과 '비슷함'만 무승부다.** 이름을 적었는데 두 참가자 중 어느 쪽도 아니면 그 행은
     * 거부한다 — 조용히 무승부로 접으면 사용자가 고른 승패가 왕복 한 번에 사라지고, 그것은
     * 이 앱이 금지하는 무음 유실이다(개발 의도 2번·4번).
     *
     * **코드가 이름보다 먼저다** — 코드는 유일하지만 이름은 겹칠 수 있다. 두 참가자의 이름이
     * 같은 판(동명이인 대결)에서 승자 칸이 그 이름이면 모호를 선언한다 — first-match로 참가자1을
     * 고르면 무편집 왕복만으로 승패가 뒤집힌다(내보내기는 이 경우 코드를 적는다).
     */
    private fun resolveDuelWinner(
        text: String,
        aCode: String, aName: String,
        bCode: String, bName: String
    ): DuelWinner {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == DuelSheetLabels.WINNER_DRAW) return DuelWinner.Resolved(null)
        return when (trimmed) {
            aCode -> DuelWinner.Resolved(aCode)
            bCode -> DuelWinner.Resolved(bCode)
            aName -> if (aName == bName) DuelWinner.Ambiguous else DuelWinner.Resolved(aCode)
            bName -> DuelWinner.Resolved(bCode)
            else -> DuelWinner.Unknown
        }
    }

    private suspend fun importDuelMatches(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = duelMatchSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow, result, spec.sheetName)
        val now = System.currentTimeMillis()

        // 축과 캐릭터 이름은 **루프 밖에서 한 번** 색인한다 — 이 시트는 수만 행이 될 수 있어
        // 행마다 조회하면 그 비용이 행 수만큼 곱해진다.
        val axes = db.duelAxisDao().getAllList()
        // 빈 코드는 키가 아니다 — 미리보기 색인과 같은 규약이라야 예고와 실행이 갈리지 않는다(R-33).
        val axisByCode = axes.filter { it.code.isNotBlank() }.associateBy { it.code }
        val axesByName = axes.groupBy { it.name }
        val codeByName = db.characterDao().getAllCharactersList()
            .groupBy({ it.displayName }, { it.code })
        // 판 자신도 같은 대접을 한다 (B-210) — 바로 위 문단이 축·캐릭터에 세운 근거가
        // **판에는 적용되지 않아** 행마다 `getByCode`가 남아 있었다(코드 열에 인덱스가 없어
        // 그 하나하나가 풀스캔이다).
        // **키 모양과 싣는 순서는 미리보기와 같은 클래스가 든다**(B-236 — `util/ImportIdentityIndexes.kt`).
        val matches = DuelMatchIndexes(db.duelMatchDao().getAllList())

        val seenCodes = HashSet<String>()
        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val r = readDuelMatchRow(row, cols, now)
                if (r.axisName.isBlank() && r.axisCode.isBlank()) continue

                val axis = r.axisCode.takeIf { it.isNotBlank() }?.let { axisByCode[it] }
                    ?: axesByName[r.axisName]?.let { candidates ->
                        if (candidates.size == 1) candidates.first() else null
                    }
                if (axis == null) {
                    result.skippedRows++
                    result.errors.add(
                        if ((axesByName[r.axisName]?.size ?: 0) > 1) {
                            "대결 기록 행 ${excelRow(i)}: 축 '${r.axisName}'이(가) 여럿이라 어느 축인지 정할 수 없음 — 축코드를 함께 적어 주세요"
                        } else {
                            "대결 기록 행 ${excelRow(i)}: 축 '${r.axisName}'을(를) 찾을 수 없음"
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
                    result.errors.add("대결 기록 행 ${excelRow(i)}: 참가자를 정할 수 없음 ('${r.aName}' · '${r.bName}') — 참가자 코드를 함께 적어 주세요")
                    continue
                }

                val winnerCode = when (val winner = resolveDuelWinner(r.winnerText, aCode, r.aName, bCode, r.bName)) {
                    is DuelWinner.Resolved -> winner.code
                    DuelWinner.Ambiguous -> {
                        result.skippedRows++
                        result.errors.add("대결 기록 행 ${excelRow(i)}: 두 참가자의 이름이 '${r.aName}'(으)로 같아 승자를 정할 수 없음 — 승자 칸에 참가자 코드를 적어 주세요")
                        continue
                    }
                    DuelWinner.Unknown -> {
                        result.skippedRows++
                        result.errors.add("대결 기록 행 ${excelRow(i)}: 승자 '${r.winnerText}'이(가) 두 참가자 중 어느 쪽도 아님 — 비겼으면 '${DuelSheetLabels.WINNER_DRAW}'이라고 적어 주세요")
                        continue
                    }
                }
                val groupId = r.groupId.ifBlank { null }

                if (r.code.isNotBlank() && !seenCodes.add(r.code)) {
                    result.warnings.add("대결 기록: 코드 '${r.code}'가 두 행에 중복됨 (마지막 행 우선)")
                }

                val existing = if (r.code.isNotBlank()) matches.byCode.first(r.code) else null
                // 코드로 찾은 기존 판과 행의 참가자가 다르면 병합하지 않는다 — 행 복사 후 이름만
                // 바꾼 파일(회색 코드 열 잔존)에서 남의 판에 사실과 다른 승자가 무음 기록되거나,
                // 승자가 참가자가 아닌 모순 판이 생긴다. 참가자를 바꾸는 것은 '다른 판'이다(위 merge 규약).
                if (existing != null && setOf(aCode, bCode) != setOf(existing.aCode, existing.bCode)) {
                    result.skippedRows++
                    result.errors.add("대결 기록 행 ${excelRow(i)}: 코드 '${r.code}'는 다른 참가자의 판입니다 — 새 판이면 코드 칸을 비워 주세요")
                    continue
                }
                if (existing == null) {
                    val newMatch = newDuelMatchFrom(
                        r, axis.id, aCode, bCode, winnerCode, groupId,
                        r.code.ifBlank { generateEntityCode() }
                    )
                    val newId = db.duelMatchDao().insert(newMatch)
                    // 같은 코드를 든 뒷 행이 이것을 봐야 한다(위 '두 행에 중복' 고지의 상대다).
                    matches.remember(newMatch.copy(id = newId))
                    result.newDuelMatches++
                } else {
                    val merged = mergeDuelMatch(existing, r, winnerCode, groupId)
                    if (merged != existing) {
                        db.duelMatchDao().update(merged)
                        result.updatedDuelMatches++
                    } else result.unchangedRows++
                    matches.remember(merged)
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("대결 기록 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "대결 기록 가져오기", sheet.lastRowNum, totalRows)
    }

    private data class DuelVerdictRowValues(
        val axisName: String,
        val axisCode: String,
        /** '참가자코드들' 열 — **정체이므로 그대로 쓴다**(있으면 이름 열은 보지 않는다). */
        val rawCodes: List<String>,
        /** '참가자들' 열 — 사람이 읽는 몫이라 코드가 비었을 때만 되찾기에 쓴다. */
        val names: List<String>,
        val code: String,
        // R-36: 열 없음("이 파일은 종류를 말하지 않는다")과 빈칸은 다른 사실이다.
        val hasKindCol: Boolean,
        val kindLabel: String,
        val decidedAt: Long
    )

    private fun readDuelVerdictRow(row: Row, cols: Map<String, Int>, now: Long): DuelVerdictRowValues {
        fun cell(header: String, dateHint: Boolean = false) =
            getCellString(row, cols[header] ?: -1, dateHint = dateHint)
        return DuelVerdictRowValues(
            axisName = cell("축"),
            axisCode = cell("축코드"),
            rawCodes = splitCsv(cell("참가자코드들")),
            names = splitCsv(cell("참가자들")),
            code = cell("코드"),
            hasKindCol = cols.containsKey("종류"),
            kindLabel = cell("종류"),
            decidedAt = cell("판정일", dateHint = true).toDoubleOrNull()?.toLong() ?: now
        )
    }

    /**
     * '종류' 열의 표기를 저장값으로 — **해석 실패는 `null`이고 그 처분은 부르는 쪽이 정한다**
     * (가져오기는 경고와 함께 기존 값을 지키고, 미리보기는 같은 값에 조용히 이른다).
     *
     * 유효값 목록과 파서 수용값이 갈리면 안 되므로 해석은 [matchDropdownValue]로 한다.
     */
    private fun resolveDuelVerdictKind(label: String): String? = when {
        matchDropdownValue(label, listOf(DuelSheetLabels.KIND_UNDECIDED, DuelCounterVerdict.KIND_UNDECIDED)) != null ->
            DuelCounterVerdict.KIND_UNDECIDED
        matchDropdownValue(label, listOf(DuelSheetLabels.KIND_COUNTER, DuelCounterVerdict.KIND_COUNTER)) != null ->
            DuelCounterVerdict.KIND_COUNTER
        else -> null
    }

    /** [newDuelMatchFrom]과 같은 규약 — 이 행이 **만들** 상성(R-33 셋째 · B-233). */
    private fun newDuelVerdictFrom(
        r: DuelVerdictRowValues, axisId: Long, members: List<String>,
        memberKey: String, shape: String, kind: String, code: String
    ): DuelCounterVerdict = DuelCounterVerdict(
        axisId = axisId, kind = kind, shape = shape,
        memberCodes = DuelRecords.encodeMembers(members),
        memberKey = memberKey, decidedAt = r.decidedAt, code = code
    )

    /**
     * 매칭된 상성에 이 행을 얹는다 — **미리보기의 '변경/동일'이 이 한 줄로 판정된다**(R-33).
     *
     * 판정일은 바꾸지 않는다: 같은 관계에 대한 판정은 하나뿐이고(유니크), 그것을 언제 내렸는지는
     * 처음 적은 때가 사실이다([mergeDuelMatch]가 참가자·시각을 지키는 것과 같은 규약).
     */
    private fun mergeDuelVerdict(
        existing: DuelCounterVerdict, axisId: Long, members: List<String>,
        memberKey: String, shape: String, kind: String
    ): DuelCounterVerdict = existing.copy(
        axisId = axisId, kind = kind, shape = shape,
        memberCodes = DuelRecords.encodeMembers(members), memberKey = memberKey
    )

    // 미리보기 짝: analyzeDuelVerdicts
    private suspend fun importDuelVerdicts(workbook: Workbook, result: ImportResult, onProgress: (ImportProgress) -> Unit, totalRows: Int) {
        val spec = duelVerdictSpec()
        val sheet = findSheet(workbook, spec, result) ?: return
        val headerRow = headerRowOrReport(sheet, spec.firstColumnHeader, result) ?: return

        reportUnknownColumns(headerRow, spec, result)
        val cols = resolveHeaderColumns(headerRow, result, spec.sheetName)
        val now = System.currentTimeMillis()

        val axes = db.duelAxisDao().getAllList()
        // 빈 코드는 키가 아니다 — 미리보기 색인과 같은 규약이라야 예고와 실행이 갈리지 않는다(R-33).
        val axisByCode = axes.filter { it.code.isNotBlank() }.associateBy { it.code }
        val axesByName = axes.groupBy { it.name }
        val codeByName = db.characterDao().getAllCharactersList()
            .groupBy({ it.displayName }, { it.code })
        // 상성도 행마다 코드·구성원키로 표를 물었다 (B-210). 둘 다 한 번 읽어 답한다.
        val allVerdicts = db.duelCounterVerdictDao().getAllList().sortedBy { it.id }
        val verdictCodes = ImportLookupIndex<String, DuelCounterVerdict>(
            idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
        )
        val verdictMemberKeys = ImportLookupIndex<DuelVerdictMemberKey, DuelCounterVerdict>(
            idOf = { it.id }, keyOf = { DuelVerdictMemberKey(it.axisId, it.memberKey) }
        )
        verdictCodes.load(allVerdicts)
        verdictMemberKeys.load(allVerdicts)
        // 파일 내 중복은 형제 시트 전부가 고지하는데 이 시트만 침묵이었다(IMP3-7).
        val verdictCodesSeen = mutableSetOf<String>()
        val writtenVerdictIds = mutableSetOf<Long>()

        for (i in dataRows(sheet, headerRow)) {
            try {
                val row = sheet.getRow(i) ?: continue
                val r = readDuelVerdictRow(row, cols, now)

                if (r.axisName.isBlank() && r.axisCode.isBlank()) continue
                val axis = r.axisCode.takeIf { it.isNotBlank() }?.let { axisByCode[it] }
                ?: axesByName[r.axisName]?.singleOrNull()
                if (axis == null) {
                    result.skippedRows++
                    // 모호를 '찾을 수 없음'으로 보고하지 않는다(4-3 규약) — 축은 실재하는데
                    // 없다고 말하면 사실과 다른 경고다. '대결 기록' 시트와 같은 갈래다.
                    result.errors.add(
                        if ((axesByName[r.axisName]?.size ?: 0) > 1) {
                            "대결 상성 행 ${excelRow(i)}: 축 '${r.axisName}'이(가) 여럿이라 어느 축인지 정할 수 없음 — 축코드를 함께 적어 주세요"
                        } else {
                            "대결 상성 행 ${excelRow(i)}: 축 '${r.axisName}'을(를) 찾을 수 없음"
                        }
                    )
                    continue
                }

                // 코드 목록이 정체이고 이름은 사람이 읽는 몫이다 — 순서에 뜻이 있으므로 지키고,
                // 이름으로 되찾을 때도 적힌 차례 그대로 옮긴다.
                //
                // **사다리는 순수 계층이 든다**(`DuelRecords.resolveMembers` — R-33 · B-263 ⓐ).
                // 해석 실패 인원을 조용히 빼지 않는다 — 인원이 줄어든 상성은 사용자가 적은
                // 것과 **다른 판정**이고, 그것을 그대로 저장하면 무음 왜곡이다('대결 기록'의
                // resolveParticipant와 같은 규약: 동명이인·미등록은 거부하고 코드를 안내한다).
                val members = when (val m = DuelRecords.resolveMembers(r.rawCodes, r.names, codeByName)) {
                    is DuelRecords.MemberResolution.Resolved -> m.members
                    is DuelRecords.MemberResolution.Unresolved -> {
                        result.skippedRows++
                        result.errors.add(
                            "대결 상성 행 ${excelRow(i)}: 참가자 '${m.names.joinToString("', '")}'을(를) 확정할 수 없음(동명이인 또는 미등록) — '참가자코드들' 열에 코드를 적어 주세요"
                        )
                        continue
                    }
                }
                val shape = DuelRecords.shapeOf(members)
                if (shape == null) {
                    result.skippedRows++
                    result.errors.add("대결 상성 행 ${excelRow(i)}: 참가자가 둘 이상이어야 판정할 관계가 있습니다")
                    continue
                }

                val code = r.code
                val memberKey = DuelRecords.memberKey(members)
                if (code.isNotBlank() && !verdictCodesSeen.add(code)) {
                    result.warnings.add("대결 상성 행 ${excelRow(i)}: 코드 '${code}'가 파일 내에서 중복되어 같은 판정을 덮어씁니다")
                }
                // 같은 관계는 축 안에서 하나뿐이다(유니크). 코드로 못 찾으면 그 키로 찾아
                // **덮어쓴다** — 그러지 않으면 유니크 인덱스가 예외로 죽는다.
                val verdictByCodeMatch = if (code.isNotBlank()) verdictCodes.first(code) else null
                val existing = verdictByCodeMatch
                    ?: verdictMemberKeys.first(DuelVerdictMemberKey(axis.id, memberKey))
                // 구성원 조합 갈래도 같은 규약으로 고지한다(연표 I2-5와 같은 모양 — 이 시트가
                // 이미 쓴 판정을 같은 조합으로 다시 잡으면 그 행이 앞 행을 무고지로 덮었다).
                if (verdictByCodeMatch == null && existing != null && existing.id in writtenVerdictIds) {
                    result.warnings.add("대결 상성 행 ${excelRow(i)}: 같은 축의 같은 참가자 조합이 파일 내에서 중복되어 같은 판정을 덮어씁니다")
                }

                // '종류'는 드롭다운 열이다 — 해석은 [resolveDuelVerdictKind]가 단일 소스이고
                // 미리보기도 같은 함수를 지난다(R-33). R-36: 열 없음 = 기존 유지.
                // 종전에는 열 부재·빈칸·오타가 전부 무경고로 '상성'이 되어, '미정'으로 보류해 둔
                // 사용자 처분이 왕복 한 번에 '상성' 확정으로 조용히 뒤집혔다.
                val kindResolved = resolveDuelVerdictKind(r.kindLabel)
                if (r.hasKindCol && r.kindLabel.isNotBlank() && kindResolved == null) {
                    result.warnings.add(
                        "대결 상성 행 ${excelRow(i)}: 종류 '${r.kindLabel}'을(를) 해석할 수 없어 " +
                            (if (existing != null) "기존 값을 유지합니다" else "'${DuelSheetLabels.KIND_COUNTER}'(으)로 두었습니다") +
                            " — '${DuelSheetLabels.KIND_COUNTER}' 또는 '${DuelSheetLabels.KIND_UNDECIDED}' 중 하나를 적어 주세요"
                    )
                }
                val kind = kindResolved ?: existing?.kind ?: DuelCounterVerdict.KIND_COUNTER

                if (existing == null) {
                    val newVerdict = newDuelVerdictFrom(
                        r, axis.id, members, memberKey, shape, kind,
                        code.ifBlank { generateEntityCode() }
                    )
                    val newId = db.duelCounterVerdictDao().upsert(newVerdict)
                    // 같은 축의 같은 구성원 조합을 든 뒷 행이 이것을 봐야 한다 — 못 보면
                    // 유니크 인덱스가 예외로 죽는다(바로 위 주석이 말하는 그 자리다).
                    val insertedVerdict = newVerdict.copy(id = newId)
                    verdictCodes.put(insertedVerdict)
                    verdictMemberKeys.put(insertedVerdict)
                    writtenVerdictIds.add(newId)
                    result.newDuelVerdicts++
                } else {
                    val merged = mergeDuelVerdict(existing, axis.id, members, memberKey, shape, kind)
                    if (merged != existing) {
                        db.duelCounterVerdictDao().update(merged)
                        result.updatedDuelVerdicts++
                    } else result.unchangedRows++
                    verdictCodes.put(merged)
                    verdictMemberKeys.put(merged)
                    writtenVerdictIds.add(existing.id)
                }
            } catch (e: Exception) {
                result.skippedRows++
                result.errors.add("대결 상성 행 ${excelRow(i)}: ${e.message}")
            }
        }
        reportProgress(onProgress, "대결 상성 가져오기", sheet.lastRowNum, totalRows)
    }

    // ── 유틸리티 메서드 ──

    /** 정확(대소문자 구분) 일치 시트 — POI `getSheet`는 대소문자를 무시하므로 직접 찾는다. */
    private fun exactSheetNamed(workbook: Workbook, name: String): Sheet? {
        for (idx in 0 until workbook.numberOfSheets) {
            if (workbook.getSheetName(idx) == name) return workbook.getSheetAt(idx)
        }
        return null
    }

    /**
     * 파일의 '세계관' 시트에서 배정표를 재현한다 — 미리보기·가져오기·덮어쓰기 가드·인식 시트
     * 집계가 **같은 함수**로 같은 배정표를 본다(R-33의 '시트 조회' 축). 시트가 없거나(캐릭터만
     * 내보낸 파일) 헤더를 못 찾으면 null — 그때는 휴리스틱만 남는다.
     */
    private fun universeSheetPlanOf(workbook: Workbook): UniverseSheetPlan? {
        val spec = universeSpec()
        val sheet = resolveSpecSheet(workbook, spec) ?: return null
        val headerRow = locateHeaderRow(sheet, spec.firstColumnHeader) ?: return null
        // 열 해석은 importUniverses와 같은 사다리다(UniverseCols) — 따로 적으면 드리프트한다.
        val c = UniverseCols(resolveHeaderColumns(headerRow), spec.firstColumnHeader)
        val rows = ArrayList<UniverseSheetPlan.Row>()
        for (i in dataRows(sheet, headerRow)) {
            val row = sheet.getRow(i) ?: continue
            rows.add(
                UniverseSheetPlan.Row(
                    name = getCellString(row, c.name),
                    code = if (c.code >= 0) getCellString(row, c.code) else "",
                    // 내보내기 정렬 키의 재구성 재료(UniverseSheetPlan KDoc) — 행 순서를 믿으면
                    // 엑셀에서 정렬한 파일이 동명 세계관 둘의 배정을 맞바꾼다. 읽기는 가져오기와
                    // 같은 사다리다(정렬순서 parseNumber · 생성일 readCreatedAtCell — 경고는
                    // importUniverses가 같은 행에서 이미 싣므로 여기서는 result 없이 값만 뜬다).
                    displayOrder = if (c.order >= 0) parseNumber(getCellString(row, c.order))?.toInt() else null,
                    createdAt = readCreatedAtCell(row, c.createdAt, "세계관 행 ${excelRow(i)}", result = null)
                )
            )
        }
        return UniverseSheetPlan.build(rows)
    }

    /**
     * 세계관 캐릭터 시트 배정 — **계획 우선, 휴리스틱 폴백** (2026.08.20).
     *
     * 내보내기는 sanitize 결과가 같은 두 세계관(완전 동명 · 31자 절단 · 금칙문자 제거 ·
     * 대소문자만 다름)에 공유 usedNames로 `이름`/`이름(2)`를 갈라 주는데, 종전 조회는 이름만
     * 받아 소비 추적 없이 첫 일치를 돌려줬다 — 두 세계관이 같은 시트를 받아 캐릭터가 중복
     * 삽입되고, `(2)` 시트는 무독으로 남고, '엑셀에 없는 항목 삭제'가 둘째 세계관의 실제
     * 캐릭터를 지웠다. 수리는 두 겹이다:
     *
     * 1. **배정표([UniverseSheetPlan])가 코드로 지목한 시트를 먼저 찾는다** — 세계관 순서가
     *    내보내기 이후 바뀌어도(정렬 편집·병합 가져오기) 코드가 제 시트를 찾는다.
     * 2. 배정표 밖(코드 없는 행·외부 편집·레거시 파일)은 종전 휴리스틱으로 폴백하되,
     *    **소비된 시트와 남의 배정 시트는 모든 갈래에서 건너뛴다.** 폴백의 동명 배정 순서는
     *    양쪽이 같은 정렬을 쓰는 데 기댄다 — 내보내기 배정 루프와 가져오기 루프 모두
     *    `getAllUniversesList`(displayOrder ASC, createdAt DESC)이고 두 열은 '세계관' 시트로
     *    왕복 보존된다(내보내기 이후 정렬이 바뀐 파일은 배정표가 흡수한다).
     *
     * 소비 집합의 수명은 **한 번의 분석/가져오기/판정 루프**다(R-33 ⑦ — 발급기의 수명은 자기가
     * 먹이는 색인과 같다). 네 호출부(미리보기·가져오기·덮어쓰기 가드·인식 시트 집계)가 각자
     * 한 벌씩 만들며, 넷이 같은 판정을 지나는 것이 R-33이다. 소비는 **찾은 시점**에 기록한다 —
     * 헤더가 깨진 시트도 그 세계관의 것이므로, 소비하지 않으면 동명 둘째 세계관이 같은 깨진
     * 시트에 다시 걸려 제 `(2)` 시트를 영영 못 받는다.
     */
    private inner class UniverseSheetFinder(
        private val workbook: Workbook,
        universes: List<Universe>,
        /** 소비된 시트명 — 호출부가 미분류 조회([findUnclassifiedSheet])와 합칠 수 있게 밖에서도 든다. */
        val consumed: MutableSet<String> = mutableSetOf()
    ) {
        private val plan: UniverseSheetPlan? = universeSheetPlanOf(workbook)

        // 이 루프에 실재하는 세계관 코드 — 배정표가 **다른** 코드에 지목한 시트를 휴리스틱이
        // 가로채지 못하게 하는 판정에 쓴다. 루프에 없는 코드의 배정 시트는 막지 않는다 —
        // 코드가 전부 낯선 파일(다른 기기 백업의 병합)에서 이름 매칭까지 죽으면 안 된다.
        private val presentCodes: Set<String> =
            universes.asSequence().map { it.code }.filter { it.isNotBlank() }.toSet()

        fun find(universe: Universe): Sheet? {
            val skip = fun(name: String): Boolean {
                if (name in consumed) return true
                val owner = plan?.plannedOwnerOf(name) ?: return false
                return owner != universe.code && owner in presentCodes
            }
            // ── 계획 우선 — 완전 동명 세계관도 코드로 제 시트를 찾는다 ──
            val planned = plan?.sheetNameFor(universe.code)
            if (planned != null && planned !in consumed) {
                val candidate = exactSheetNamed(workbook, planned)
                // 지문 확인: 레거시 배치에서는 `예약명(2)` 자리에 진짜 예약 데이터 시트가 있을 수
                // 있다(규칙 도입 전 백업 — 세계관이 평명을 차지하고 예약 시트가 밀린 반대 배치).
                // 캐릭터 시트가 아니면 아래 휴리스틱(레거시 구제 갈래 포함)에 맡긴다.
                if (candidate != null && looksLikeCharacterSheet(candidate)) {
                    consumed.add(candidate.sheetName)
                    return candidate
                }
            }
            // ── 휴리스틱 폴백 — 종전 판정 그대로, 소비·배정표 회피만 얹는다 ──
            val found = findSheetForUniverse(workbook, universe.name, RESERVED_SHEET_NAMES, skip)
            if (found != null) consumed.add(found.sheetName)
            return found
        }
    }

    /**
     * 세계관 캐릭터 시트의 휴리스틱 조회 — [UniverseSheetFinder]를 통해서만 부른다.
     * @param skip 이 이름의 시트를 후보에서 뺄 것인가(이미 소비됐거나 배정표가 남에게 지목).
     *   **모든 갈래**(접미사·정확 일치·대소문자 폴백)가 지나야 한다 — 한 갈래만 빠뜨리면
     *   동명 세계관이 그 갈래로 같은 시트를 다시 받는다.
     */
    private fun findSheetForUniverse(
        workbook: Workbook,
        universeName: String,
        reservedNames: Set<String>,
        skip: (String) -> Boolean
    ): Sheet? {
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
                if (skip(sheetName)) continue
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
        fun exactSheet(name: String): Sheet? =
            if (skip(name)) null else exactSheetNamed(workbook, name)

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
            .filter { workbook.getSheetName(it).equals(sanitized, ignoreCase = true) && !skip(workbook.getSheetName(it)) }
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

    /**
     * 캐릭터 시트의 열 머리를 읽어 [CharacterFieldColumns.plan]에 넘길 꼴로 만든다.
     * **미리보기도 이 함수로 읽는다** — 열을 세는 범위(`0 until lastCellNum`)가 갈리면
     * 사다리가 같아도 답이 갈린다(R-33이 모으는 넷 중 '열 해석').
     */
    private fun readHeaderCells(headerRow: Row): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (col in 0 until headerRow.lastCellNum.toInt()) {
            val name = getCellString(headerRow, col)
            if (name.isNotBlank()) out[col] = name
        }
        return out
    }

    /**
     * 해석 사다리는 [CharacterFieldColumns]가 든다(순수) — 여기서는 그 결과에 **쓰기와 고지**만 붙인다.
     * 갈라 둔 이유는 그 파일 KDoc이 든다: 미리보기가 같은 사다리를 손으로 한 벌 더 짜지 않게 하는 것이다(B-187).
     */
    private suspend fun buildColumnFieldMap(
        headerRow: Row,
        fields: List<FieldDefinition>,
        fixedColIndices: Set<Int>,
        universe: Universe?,
        result: ImportResult,
        sheetLabel: String
    ): Map<Int, FieldDefinition> {
        val plan = CharacterFieldColumns.plan(
            readHeaderCells(headerRow), fields,
            CharacterFieldHeaders.expectedHeaders(fields), fixedColIndices,
            CHARACTER_FIXED_HEADERS, hasUniverse = universe != null,
            multiSuffix = EntityFieldHeaders.MULTI_SUFFIX
        )
        val map = mutableMapOf<Int, FieldDefinition>()
        var autoCreateCount = 0
        val maxOrder = fields.maxOfOrNull { it.displayOrder } ?: 0

        for ((col, outcome) in plan) {
            when (outcome) {
                is ColumnFieldOutcome.Matched -> map[col] = outcome.field
                is ColumnFieldOutcome.AutoCreate -> {
                    // 매칭 실패 → 자동 필드 생성 (TEXT 타입)
                    val baseKey = "auto_${outcome.header.lowercase().replace(Regex("[^a-z0-9가-힣_]"), "_")}"
                    // key 충돌 방지
                    var autoKey = baseKey
                    var suffix = 1
                    while (db.fieldDefinitionDao().getFieldByKey(universe!!.id, autoKey) != null) {
                        autoKey = "${baseKey}_${++suffix}"
                    }
                    val newField = FieldDefinition(
                        universeId = universe.id,
                        key = autoKey,
                        name = outcome.header,
                        type = FieldType.TEXT.name,
                        groupName = "자동 생성",
                        displayOrder = maxOrder + 1 + autoCreateCount++
                    )
                    // 전역키 보증(이 갈래는 `universe != null`이라 비-null이고, 위 while이
                    // getFieldByKey로 빈 autoKey를 찾을 때까지 접미사를 올린다)
                    val newId = db.fieldDefinitionDao().insert(newField)
                    map[col] = newField.copy(id = newId)
                    result.warnings.add("$sheetLabel: 컬럼 '${outcome.header}' → TEXT 필드로 자동 생성됨")
                    result.newFields++
                }
                is ColumnFieldOutcome.Ambiguous -> result.warnings.add(
                    "$sheetLabel: 열 '${outcome.header}'과(와) 이름이 같은 필드가 ${outcome.candidates}개 있어 어느 필드인지 확정할 수 없습니다 — 헤더를 '이름(필드키)' 형식으로 바꾸거나 앱에서 필드명을 구분해 주세요"
                )
                // 미분류 캐릭터 시트: 세계관 없어 자동 생성 불가 → 경고만
                is ColumnFieldOutcome.Unresolved -> result.warnings.add(
                    "$sheetLabel: 컬럼 '${outcome.header}'에 대한 필드 정의를 찾을 수 없어 무시됨"
                )
                // 단사(injective) 보장: 한 필드에 2개 이상의 열이 붙으면 뒤 열을 버린다.
                // (동명 헤더가 든 기존 파일 보호 — 두 열이 같은 필드에 쓰이면 앞 열 값이 뒤 열 값으로 조용히 덮인다)
                is ColumnFieldOutcome.Duplicate -> result.errors.add(
                    "$sheetLabel: 열 ${excelColumn(col)}과(와) 열 ${excelColumn(outcome.keptColumn)}이(가) 같은 필드 '${outcome.field.name}'에 대응해 열 ${excelColumn(col)}을(를) 무시했습니다 — 중복 헤더를 정리해 주세요"
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
        val inScope = novelByTitle(novelTitle, universeId)
        if (inScope != null) {
            novelIdCache[cacheKey] = inScope.id
            return inScope.id
        }
        // 2. F3-C: 생성 전 타 세계관에서 동일 제목 조회 — 있으면 유령 중복 생성 대신 재사용 + 경고
        val elsewhere = novelByTitleAnyUniverse(novelTitle)
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

    /**
     * 코드/이름 기반 캐릭터 조회 — 동명이인 모호성 감지 포함.
     * 판정은 [CharacterRefLadder.codeFirst](순수)가 하고 여기는 **재료만** 뜬다.
     * 코드가 답했으면 이름 색인은 묻지 않는다 — 코드가 권위라는 사다리의 뜻 그대로다.
     */
    private suspend fun findCharacterStrict(name: String, code: String): CharLookupResult {
        val byCode = characterByCode(code)
        val matches = if (byCode != null) emptyList() else charactersByName(name)
        return CharacterRefLadder.codeFirst(byCode, name, matches)
    }

    // ── 자연키 (B-210) → `util/ImportIdentityIndexes.kt`로 옮겼다 (B-236) ────────
    // 코드가 없는 구버전 파일의 폴백 경로가 쓰는 키다. **타입이 있는 `data class`로 짓는다** —
    // 손쉬워 보이는 `listOf(charId, year, …)`는 코틀린이 원소 타입을 첫 원소에서 추론해
    // 숫자 리터럴을 올려 버려, `Int` 칸에서 실은 키와 **영영 같지 않다.** 컴파일도 되고 예외도
    // 없어 **모든 조회가 빗나가는데도 조용하고**, 그러면 가져오기가 *"기존 행이 없다"*고 보고
    // 전부 새로 만든다(`ImportLookupIndexTest` ⑤가 그 실패를 붙잡아 둔다).
    //
    // **옮긴 이유는 미리보기가 같은 키를 쓰게 됐기 때문이다(B-236).** 이 파일 안에 private으로
    // 두면 미리보기 쪽은 볼 수 있어도(같은 클래스다) **순수 시험이 닿지 못한다** — 키 모양과
    // 싣는 순서는 순수인데 여기 있으면 잴 수 없다.

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
     *
     * **먼저 싣고 나서 쓴다.** 게으른 적재보다 쓰기가 앞서면 — 첫 행이 *충돌 해결된 행*이라
     * 코드·이름으로 물어보지 않고 곧장 insert로 가는 경우가 그것이다 — 나중에 도는 [load]가
     * **이미 있는 버킷의 뒤에 붙어** 순서가 뒤집힌다. 그러면 동명이인에서 `LIMIT 1`이 고르던
     * 상대가 바뀐다([ImportLookupIndex] 성질 1). *"쓰기 앞에 반드시 읽기가 있다"*에 기대지
     * 않는 것이 이 한 줄의 값이다.
     */
    private suspend fun rememberCharacter(character: Character) {
        ensureCharacterIndex()
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

    /**
     * 사건을 썼다고 기록한다(insert·update 양쪽). 연도·설명·코드가 바뀌면 옛 키는 색인이 끊는다.
     * [rememberCharacter]와 같은 이유로 **먼저 싣는다** — 쓰기가 적재보다 앞서면 순서가 뒤집힌다.
     */
    private suspend fun rememberEvent(event: TimelineEvent) {
        ensureEventIndex()
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
        novelTitleKeys.reset()
        novelTitlesAnyUniverse.reset()
        novelIndexLoaded = false
    }

    private suspend fun ensureNovelIndex() {
        if (novelIndexLoaded) return
        novelIndexLoaded = true
        for (novel in db.novelDao().getAllNovelsList().sortedBy { it.id }) rememberNovel(novel)
    }

    /**
     * 작품을 썼다고 기록한다 — 코드·제목이 바뀌었으면 옛 키는 색인이 끊는다.
     * [rememberCharacter]와 같은 이유로 **먼저 싣는다**(코드 칸이 빈 첫 행은 코드로 물어보지
     * 않고 제목 폴백으로 가므로, 적재보다 쓰기가 앞서는 경로가 실재한다).
     */
    private suspend fun rememberNovel(novel: Novel) {
        ensureNovelIndex()
        novelCodes.put(novel)
        novelTitleKeys.put(novel)
        novelTitlesAnyUniverse.put(novel)
    }

    /** `getNovelByCode`의 자리. */
    private suspend fun novelByCode(code: String): Novel? {
        if (code.isBlank()) return null
        ensureNovelIndex()
        return novelCodes.first(code)
    }

    /**
     * `getNovelByTitleAndUniverse`(LIMIT 1) / `getNovelByTitleNoUniverse`(LIMIT 1)의 자리 (B-236).
     * **세계관 미지정(null)은 별개의 키다** — SQL도 `universeId IS NULL`을 따로 묻는다.
     */
    private suspend fun novelByTitle(title: String, universeId: Long?): Novel? {
        ensureNovelIndex()
        return novelTitleKeys.first(NovelTitleKey(title, universeId))
    }

    /** `getNovelsByTitleList(title).firstOrNull()`의 자리 (B-236). */
    private suspend fun novelByTitleAnyUniverse(title: String): Novel? {
        ensureNovelIndex()
        return novelTitlesAnyUniverse.first(title)
    }

    /**
     * `getAllNovelsList().groupBy { it.title }[title]`의 자리 (B-187) — **동명이 몇인지**를 묻는
     * 자리라 [novelByTitleAnyUniverse]와 갈린다. 하나로 좁혀지지 않으면 가져오기가 거부하므로
     * 개수 자체가 답의 일부다.
     */
    private suspend fun novelsByTitleAll(title: String): List<Novel> {
        ensureNovelIndex()
        return novelTitlesAnyUniverse.all(title)
    }

    // ── 세계관 색인 (B-210) ────────────────────────────────────────────────────

    private fun resetUniverseIndex() {
        universeCodes.reset()
        universeNames.reset()
        universesByUniverseId.reset()
        universeIndexLoaded = false
    }

    private suspend fun ensureUniverseIndex() {
        if (universeIndexLoaded) return
        universeIndexLoaded = true
        for (universe in db.universeDao().getAllUniversesList().sortedBy { it.id }) rememberUniverse(universe)
    }

    /**
     * 세계관을 썼다고 기록한다 — 이름·코드가 바뀌면 옛 키는 색인이 끊는다.
     * [rememberCharacter]와 같은 이유로 **먼저 싣는다.**
     */
    private suspend fun rememberUniverse(universe: Universe) {
        ensureUniverseIndex()
        universeCodes.put(universe)
        universeNames.put(universe)
        universesByUniverseId.put(universe)
    }

    /** `getUniverseByCode`의 자리. */
    private suspend fun universeByCode(code: String): Universe? {
        if (code.isBlank()) return null
        ensureUniverseIndex()
        return universeCodes.first(code)
    }

    /** `getUniverseByName`(LIMIT 1)의 자리. */
    private suspend fun universeByName(name: String): Universe? {
        ensureUniverseIndex()
        return universeNames.first(name)
    }

    /** `getUniverseById`의 자리. */
    private suspend fun universeById(id: Long): Universe? {
        ensureUniverseIndex()
        return universesByUniverseId.first(id)
    }

    /**
     * 코드 우선 → 이름 폴백. 이 파일에서 **가장 자주 되풀이되던 두 줄**을 한 자리에 모은다
     * (열 몇 자리가 같은 꼴이었다). 빈 칸은 조회하지 않는 기존 규약을 그대로 지킨다.
     */
    private suspend fun universeByCodeOrName(code: String, name: String): Universe? =
        universeByCode(code) ?: (if (name.isNotBlank()) universeByName(name) else null)

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

    /**
     * F3-B: 이름 기반 캐릭터 조회(동명이인 안전) — '작품' 열을 해소 힌트로 쓰는 시트용
     * (상태 변화 · 연표 참가자 · 이름은행 사용캐릭터처럼 캐릭터코드 열이 없거나 비는 경로).
     * 판정은 [CharacterRefLadder.nameWithNovelHint](순수)가 하고 여기는 **재료만** 뜬다.
     */
    private suspend fun resolveCharByNameNovel(name: String, preferredNovelId: Long?): CharLookupResult {
        if (name.isBlank()) return CharLookupResult.NotFound
        return CharacterRefLadder.nameWithNovelHint(name, charactersByName(name), preferredNovelId)
    }

    /**
     * 미리보기가 세계관을 찾을 때 보는 목록 — **DB의 것 + 이 파일이 만들 것** (B-254).
     *
     * 짝인 `import*`는 [importUniverses]가 **먼저** 심으므로 `getAllUniversesList()` 하나로 같은
     * 답을 얻는다. 미리보기는 쓰지 않아 그 목록이 **빈 DB 복원에서 통째로 비고**, 그러면
     * *어느 시트를 여는가*(캐릭터 시트 훑기)와 *행의 세계관 칸을 무엇으로 읽는가*(필드값 세
     * 시트 · 값 라이브러리 · 작품·연표의 필드 열)가 **함께** 무너진다. 자리마다 손으로 이어
     * 붙이면 다음에 생기는 자리가 빠지므로 원천을 하나로 둔다.
     *
     * **싣는 순서는 DB가 먼저다** — [analysisCreatedFields]와 같은 근거(먼저 실린 것이 답이다).
     */
    private suspend fun analysisUniverses(): List<Universe> =
        db.universeDao().getAllUniversesList() + analysisCreatedUniverses

    /**
     * 이 행이 캐릭터의 **세계관을 옮기는가** — 옮기면 새 세계관 id, 아니면 null (R-33 · B-253).
     *
     * 짝인 [importCharacterRows]와 `analyzeCharacterSheet`가 **같은 함수**를 쓴다. 갈라 두면
     * 미리보기가 이동을 못 알아보고, 이동이 일으키는 것(필드값 전량 재매핑 · 오버플로 행 무적용)이
     * 통째로 예고에서 빠진다 — 종전이 그 상태였다.
     *
     * **음수 작품 id는 이동이 아니다.** 미리보기에서 그 값은 *"가져오기가 새로 만들어 배정할
     * 작품"*을 뜻하는데([ANALYSIS_CREATED_NOVEL_ID]·[PreviewIdMinter]), 그 작품은 **시트의
     * 세계관에** 생기므로(`resolveNovelId`의 생성 갈래) 세계관을 넘지 않는다. 가져오기 쪽
     * 인자는 언제나 실존 id라 이 가드가 그쪽 답을 바꾸지 않는다.
     */
    private suspend fun universeMoveOf(
        existing: Character?,
        novelColumnsPresent: Boolean,
        novelId: Long?
    ): Long? {
        if (existing == null || !novelColumnsPresent || novelId == existing.novelId) return null
        if (novelId != null && novelId < 0) return null
        // 같은 작품이 시트 안에서 되풀이되므로 메모된 helper로 답한다(B-210).
        val oldU = existing.novelId?.let { universeIdOfNovel(it) }
        val newU = novelId?.let { universeIdOfNovel(it) }
        return if (oldU != null && newU != null && oldU != newU) newU else null
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
    /**
     * 아는 필드 타입인가 — **'필드 정의'와 '기본 필드' 두 시트가 같은 잣대를 쓴다.**
     *
     * 두 시트는 같은 어휘를 쓴다(같은 드롭다운·같은 필수 표식이고 시험이 그 동일성을
     * 못박는다). 그런데 게이트는 한쪽에만 있었다 — 판정을 두 벌로 두지 않으려고 여기 모은다.
     */
    private fun isKnownFieldType(type: String): Boolean =
        type.isNotBlank() && FieldType.fromName(type) != null

    /** 모르는 타입을 말하는 한 문장 — 허용 목록을 문구에 박지 않고 열거에서 낸다(R-14). */
    private fun unknownFieldTypeMessage(where: String, type: String): String =
        "$where: 알 수 없는 필드 타입 '$type' (허용: ${FieldType.entries.joinToString { it.name }})"

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
        // **색 글자 자체를 본다.** 형식이 JSON이어도 값이 색이 아니면 화면이 그것을 칠하려다
        // 죽거나(감싸지 않은 자리가 하나 있었다) 조용히 회색으로 떨어진다 — 어느 쪽이든
        // 사용자는 자기가 적은 색이 안 먹은 이유를 알 수 없다(개발 의도 2번).
        if (isValidJson(raw, '{')) {
            return filterRelColors(
                org.json.JSONObject(raw), raw, rowLabel, universeName, result
            )
        }
        val pairs = parseRelColorTokens(raw)
        if (pairs.isNotEmpty()) {
            val obj = org.json.JSONObject()
            pairs.forEach { (k, v) -> obj.put(k, v) }
            result?.warnings?.add(
                "$rowLabel: 세계관 '$universeName'의 커스텀관계색상이 JSON 객체 형식이 아니어서 '유형=색상' 목록으로 해석했습니다(${pairs.size}개) — 정확한 형식은 {\"연인\":\"#E91E63\"} 입니다"
            )
            return filterRelColors(obj, raw, rowLabel, universeName, result)
        }
        result?.warnings?.add(
            "$rowLabel: 세계관 '$universeName'의 커스텀관계색상 '${raw.take(40)}'을(를) 해석할 수 없어 적용하지 않고 기존 설정을 유지했습니다 — 형식은 {\"연인\":\"#E91E63\"} 또는 '연인=#E91E63' 쉼표 나열입니다. 비우면 기본 색상으로 돌아갑니다"
        )
        return null
    }

    /**
     * 색이 아닌 값을 떨어뜨리고 **어느 유형의 무엇이 왜 안 실렸는지** 말한다.
     *
     * 전부 불합격이면 `null`을 돌려 **기존 설정을 파괴하지 않는다** — 해석 불가 입력이
     * 유효 설정을 지우지 않는다는 이 함수의 대원칙 그대로다.
     */
    private fun filterRelColors(
        obj: org.json.JSONObject,
        raw: String,
        rowLabel: String,
        universeName: String,
        result: ImportResult?
    ): String? {
        val kept = org.json.JSONObject()
        val dropped = mutableListOf<String>()
        for (key in obj.keys()) {
            // `optString`은 기기(libcore)에서 JSON null에 `"null"`을 준다 — 그 값이 색 검사를
            // 통과할 리는 없지만, 경고 문구에 그대로 실려 사용자를 헷갈리게 한다(R-70).
            val value = obj.stringOr(key, "")
            if (com.novelcharacter.app.util.ColorHex.isValidHex(value)) kept.put(key, value.trim())
            else dropped.add("$key=$value")
        }
        if (dropped.isNotEmpty()) {
            result?.warnings?.add(
                "$rowLabel: 세계관 '$universeName'의 커스텀관계색상 ${dropped.size}개가 색 형식이 아니어서 싣지 않았습니다(${dropped.take(3).joinToString(", ")}) — 형식은 #RRGGBB 입니다"
            )
        }
        if (kept.length() == 0) {
            if (dropped.isNotEmpty()) {
                result?.warnings?.add(
                    "$rowLabel: 세계관 '$universeName'의 커스텀관계색상 '${raw.take(40)}'에 쓸 수 있는 색이 하나도 없어 기존 설정을 유지했습니다"
                )
            }
            return null
        }
        return kept.toString()
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
    /**
     * 연도 칸 하나 — **해석 불가를 조용히 null로 접지 않는다** (2026.08.22).
     *
     * 종전에는 세력 소속의 가입·탈퇴연도가 맨 `parseNumber(getCellString(...))`이라
     * `1990년`·`미상`·`1,990` 같은 꼴이 **경고 한 줄 없이 null**이 됐다. 그 null이
     * 그대로 매처에 실리는데, **열이 시트에 있으면 `presence = true`**라
     * `joinYear = if (presence.joinYear) row.joinYear else existing.joinYear`가
     * **DB에 있던 연도를 null로 덮었다.** 매칭이 끊기지도 않는다 — '생성일' 열의 안정
     * 식별자로 행은 그대로 붙고 **값만 지워진다.**
     *
     * 같은 행 안에 비대칭이 있었다: 바로 아래 '강도'는 [parseIntensityWithWarn]이,
     * '생성일'은 [readCreatedAtCell]이 각각 경고를 실었고 **연도 두 칸만 맨손이었다.**
     *
     * 빈 칸은 경고하지 않는다 — *비움*은 사용자가 적어 넣은 뜻이라 해석 실패와 다르다.
     */
    private fun readYearCell(
        row: Row,
        colIndex: Int,
        rowLabel: String,
        columnName: String,
        result: ImportResult?
    ): Int? {
        if (colIndex < 0) return null
        val raw = getCellString(row, colIndex)
        val parsed = parseNumber(raw)?.toInt()
        if (raw.isNotBlank() && parsed == null) {
            result?.warnings?.add(
                "$rowLabel: $columnName '$raw'을(를) 숫자로 읽을 수 없어 빈 값으로 처리합니다 — 기존에 적혀 있던 연도가 지워집니다"
            )
        }
        return parsed
    }

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
     * '생성일' 셀 공용 읽기 — 이웃 열들과 같은 규약(R-36)으로 접는다:
     * **열 없음·빈칸 = null(기존 항목은 생성 시각 유지 — `merge*`가 그렇게 해석한다).**
     * 신규 행의 '지금 시각' 폴백은 각 `new*From` 생성자의 `?: now`가 진다 — 읽기 자리에서
     * 폴백하면 열 있음+빈칸이 기존 행의 생성 시각을 '지금'으로 조용히 덮는다(그 결함의 자리다).
     *
     * 비어 있지 않은데 수로 안 읽히는 셀은 조용히 null로 접지 않고 경고한다
     * (변수 제어 — 프리셋 템플릿 자리의 선례). [consequence]는 그 시트에서 해석 불가가
     * 갖는 뜻이고, [result]가 null이면 경고 없이 같은 값만 낸다(R-33 미리보기 경로).
     */
    private fun readCreatedAtCell(
        row: Row,
        colIndex: Int,
        rowLabel: String,
        result: ImportResult?,
        consequence: String = "빈 칸과 같게 처리합니다(기존 항목은 생성 시각 유지, 새 항목은 지금 시각)"
    ): Long? = readEpochMillisCell(row, colIndex, rowLabel, "생성일", result, consequence)

    /**
     * 밀리초 시각 열 하나를 읽는다 — **열 이름을 받는 것이 요점이다.**
     *
     * 종전에는 이 몸통이 `readCreatedAtCell` 안에 있으면서 문구에 `"생성일"`을 박아 두었고,
     * 그래서 **바로 옆 '수정일' 열이 이 통로 밖에 남았다**: 프리셋 세 시트가
     * `parseNumber(getCellString(row, c.updatedAt))?.toLong()` 한 줄로 읽어 **해석 불가를
     * 조용히 버렸다.** 한 행 안에서 두 이웃 열의 처분이 갈려, 사용자는 '생성일' 경고만
     * 고치고 '수정일'은 반영된 줄 알았다(그 열도 회색 readOnly라 손대지 말라는 자리인데,
     * 손댄 사실 자체가 안 알려졌다).
     *
     * 이름을 인자로 올린 이유가 그것이다 — 문구에 박아 두면 **다음 밀리초 열이 또 밖에 선다.**
     */
    private fun readEpochMillisCell(
        row: Row,
        colIndex: Int,
        rowLabel: String,
        columnName: String,
        result: ImportResult?,
        consequence: String
    ): Long? {
        if (colIndex < 0) return null
        val raw = getCellString(row, colIndex)
        val parsed = parseNumber(raw)?.toLong()
        if (raw.isNotBlank() && parsed == null) {
            result?.warnings?.add("$rowLabel: $columnName '$raw'을(를) 숫자로 읽을 수 없어 $consequence — '$columnName' 열은 수정하지 마세요")
        }
        return parsed
    }

    /**
     * 시트에 **내용이 있는** 데이터 행이 1개 이상인가 — 덮어쓰기 가드([OverwriteGuard])의 입력.
     * null = 시트 없음. lazy Sequence라 첫 발견 즉시 멈춘다 — 정상 파일에서는 첫 행에서 끝난다.
     *
     * [getCellString]을 쓰지 않는 이유: 그 함수는 병합 셀 보정·절단을 경고 집계에 얹으므로,
     * 존재 판정 스캔이 그 건수를 부풀린다. 여기서는 원시 정규화만 본다.
     */
    private fun sheetHasDataRow(sheet: Sheet?, expectedFirstHeader: String): Boolean? {
        if (sheet == null) return null
        // **헤더 다음 행부터 센다**(B-231 ⓑ) — 종전에는 1행 고정이라, 헤더가 3행인 파일에서
        // 위의 제목·메모 행이 **데이터 행으로 세어졌다.** 그러면 헤더만 있는 시트가
        // *복원 재료 있음*으로 분류돼 **덮어쓰기가 기존 데이터를 지우고 한 건도 넣지 못한다.**
        // 헤더를 못 찾으면 0행으로 보아 종전과 같이 1행부터 센다.
        val headerIndex = SheetResolver.locateHeader(sheet, expectedFirstHeader)?.index ?: 0
        val rows = ((headerIndex + 1)..sheet.lastRowNum).asSequence()
            .mapNotNull { sheet.getRow(it) }
            .map { row ->
                (0 until maxOf(row.lastCellNum.toInt(), 0)).asSequence().map { c ->
                    val cell = row.getCell(c)
                    if (cell != null) ExcelCellValue.normalize(cell.primitives(), dateHint = false) else ""
                }
            }
        return OverwriteGuard.hasDataRow(rows)
    }

    /**
     * @param dateHint true이면 숫자 셀을 적극적으로 날짜 변환 시도 (생일 등 날짜 필드용)
     */
    /**
     * 이 행에 **값이 든 칸이 하나라도 있는가** — '표 아래 여백'과 '외부 편집이 상하게 한 행'을 가른다.
     *
     * 필수 칸(캐릭터 '이름' · 세계관 '세계관명' · 작품 '제목' · 연표 '사건 설명')이 빈 행을
     * 종전에는 미리보기·가져오기가 **둘 다** 계수 앞에서 무조건 `continue`했다. 그래서 그 행은
     * *건너뜀*도 *백업에 있음*도 아닌 **무존재**였다 — 화면 어디에도 한 줄이 없어 사용자는
     * 그 행이 들어간 줄 알거나 왜 안 들어왔는지 알 길이 없었다. [CategoryAnalysis.skippedCount]의
     * 계약(*"필수 칸이 비었거나 해석되지 않는 행도 여기 센다"* · `inBackup = new + update +
     * unchanged + cleared + skipped`)이 그 자리에서 깨져 있었다.
     *
     * **완전히 빈 행은 여전히 침묵한다** — 표 아래 여백은 파일이 적어 둔 행이 아니다.
     * 그래서 판정을 '필수 칸'이 아니라 **행 전체**에 대고 묻는다: 필드값 열만 채워진 행도
     * 사용자가 적은 행이므로 사라져서는 안 된다.
     *
     * 세는 부작용이 있는 [getCellString]을 쓰지 않는다 — 한 행을 통째로 훑으면 절단 경고가
     * 열 수만큼 부풀고, 그 경고는 이 판정의 것이 아니다.
     */
    private fun rowCarriesValue(row: Row): Boolean {
        // 셀이 하나도 없는 행의 `lastCellNum`은 **-1**이라 이 범위가 비고, 그것이 곧 '빈 행'이다.
        for (ci in 0 until row.lastCellNum) {
            val cell = row.getCell(ci) ?: continue
            if (ExcelCellValue.normalize(cell.primitives(), dateHint = false).isNotBlank()) return true
        }
        return false
    }

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
            // 열도 행처럼 **엑셀 화면의 표기**로 적는다 — "열13"이라 적으면 머리글이 `M`인
            // 그 칸에 닿으려고 사용자가 열을 손으로 세어야 한다.
            truncatedDetails.add("${sheetName} 행${excelRow(row.rowNum)} 열${excelColumn(cellIndex)}")
            // 경계 처리는 truncateForCell(단일 소스) — 내보내기 절단과 같은 함수라 서러게이트
            // 쌍이 반쪽으로 남지 않는다.
            return truncateForCell(raw, maxLength)
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
        val sheet = row.sheet ?: return null
        // **헤더 행 이하는 제외한다** — 종전에는 `rowNum == 0`이었는데, 헤더가 0행이 아닐 수
        // 있게 된 순간(B-231 ⓑ) 그 상수는 *틀린 행*을 지킨다. 헤더 위의 제목·메모 행은
        // 데이터로 읽히지 않으므로 함께 제외해도 잃는 것이 없다.
        if (row.rowNum <= (headerRowIndexBySheet[sheet] ?: 0)) return null
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

    // ── Deferred FK 해석 (코드 기반) ──

    // 미해석 코드는 조용히 버리지 않는다 — Phase 1에서 참조를 이미 null로 지운 뒤라
    // 여기서 무음 continue하면 기존 대표 이미지 설정이 경고 없이 사라진다(변수 제어).

    /** 지연 해석이 이미지 연동의 순효과를 바꿨다 — '동일'로 세었던 행이면 '갱신'으로 승격한다(확정 7-2 · B-217). */
    private fun promoteUniverseIfCountedUnchanged(universeId: Long, result: ImportResult) {
        if (universesCountedUnchanged.remove(universeId)) {
            result.updatedUniverses++
            result.unchangedRows--
        }
    }

    private fun promoteNovelIfCountedUnchanged(novelId: Long, result: ImportResult) {
        if (novelsCountedUnchanged.remove(novelId)) {
            result.updatedNovels++
            result.unchangedRows--
        }
    }

    private suspend fun applyDeferredUniverseNovelRefs(result: ImportResult) {
        for ((universeId, ref) in deferredUniverseImageNovelCodes) {
            val universe = universeById(universeId) ?: continue
            val novelId = novelByCode(ref.code)?.id
            if (novelId == null) {
                result.warnings.add("세계관 '${universe.name}': 이미지 작품코드 '${ref.code}'에 해당하는 작품이 없어 대표 이미지 연동이 해제되었습니다")
                // 있던 연동이 끊겼다 — 순효과 변화이므로 이 행은 '갱신'이다.
                if (ref.originalId != null) promoteUniverseIfCountedUnchanged(universeId, result)
                continue
            }
            val relinked = universe.copy(imageNovelId = novelId)
            db.universeDao().update(relinked)
            rememberUniverse(relinked)
            if (novelId != ref.originalId) promoteUniverseIfCountedUnchanged(universeId, result)
        }
        deferredUniverseImageNovelCodes.clear()
    }

    private suspend fun applyDeferredCharacterRefs(result: ImportResult) {
        for ((novelId, ref) in deferredNovelImageCharCodes) {
            val novel = db.novelDao().getNovelById(novelId) ?: continue
            val charId = characterByCode(ref.code)?.id
            if (charId == null) {
                result.warnings.add("작품 '${novel.title}': 이미지 캐릭터코드 '${ref.code}'에 해당하는 캐릭터가 없어 대표 이미지 연동이 해제되었습니다")
                if (ref.originalId != null) promoteNovelIfCountedUnchanged(novelId, result)
                continue
            }
            val relinkedNovel = novel.copy(imageCharacterId = charId)
            db.novelDao().update(relinkedNovel)
            rememberNovel(relinkedNovel)
            if (charId != ref.originalId) promoteNovelIfCountedUnchanged(novelId, result)
        }
        deferredNovelImageCharCodes.clear()

        for ((universeId, ref) in deferredUniverseImageCharCodes) {
            val universe = universeById(universeId) ?: continue
            val charId = characterByCode(ref.code)?.id
            if (charId == null) {
                result.warnings.add("세계관 '${universe.name}': 이미지 캐릭터코드 '${ref.code}'에 해당하는 캐릭터가 없어 대표 이미지 연동이 해제되었습니다")
                if (ref.originalId != null) promoteUniverseIfCountedUnchanged(universeId, result)
                continue
            }
            val relinked = universe.copy(imageCharacterId = charId)
            db.universeDao().update(relinked)
            rememberUniverse(relinked)
            if (charId != ref.originalId) promoteUniverseIfCountedUnchanged(universeId, result)
        }
        deferredUniverseImageCharCodes.clear()
    }

    // ── 엑셀에 없는 항목 삭제 ──

    /**
     * 엑셀에 없는 항목을 지운다 — **처분은 인앱 삭제 경로가 정한다.**
     *
     * 인앱이 휴지통을 지나는 것(캐릭터·사건·세력·상태변화)은 여기서도 지나고, 인앱도
     * 영구 삭제인 것(관계·관계 변화·명대사·이름 은행·세력 관계·세력 소속)은 여기서도
     * 영구 삭제다. 기준을 시트 종류로 잡으면 **같은 데이터가 경로에 따라 다르게 사라진다.**
     *
     * **실패는 삼키지 않는다.** 지우지 못한 행은 그대로 남아 다음 왕복에서 다시 후보가
     * 되는데, 아무 말이 없으면 사용자는 지워졌다고 믿는다(개발 의도 2번 '변수 제어').
     */
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
            SqlInChunks.each(doomed) { chunk ->
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
                    catch (e: Exception) {
                        result.warnings.add("관계 1건(#$id) 삭제에 실패해 건너뛰었습니다: ${e.message}")
                    }
                }
            }
        }

        // 관계 변화
        if (del.relationshipChanges && matchedRelationshipChangeIds.isNotEmpty()) {
            val allIds = db.characterRelationshipChangeDao().getAllChangeIds()
            for (id in allIds) {
                if (id !in matchedRelationshipChangeIds) {
                    try { db.characterRelationshipChangeDao().deleteById(id); result.deletedRelationshipChanges++ }
                    catch (e: Exception) {
                        result.warnings.add("관계 변화 1건(#$id) 삭제에 실패해 건너뛰었습니다: ${e.message}")
                    }
                }
            }
        }

        // 상태 변화 — 인앱 삭제와 동일 경로로 휴지통 스냅샷을 남긴다.
        // 종전에는 이 갈래만 우회해, **같은 데이터가 인앱에서는 되돌릴 수 있고 엑셀에서는
        // 영영 사라졌다.** 처분을 가르는 근거는 인앱 경로이지 시트 종류가 아니다.
        if (del.stateChanges && matchedStateChangeIds.isNotEmpty()) {
            val trash = trashForImport()
            val allIds = db.characterStateChangeDao().getAllChangeIds()
            val doomed = allIds.filter { it !in matchedStateChangeIds }
            val doomedChanges = SqlInChunks.flat(doomed) { db.characterStateChangeDao().getChangesByIds(it) }
            for (change in doomedChanges) {
                try {
                    trash.snapshotStateChange(change)
                    db.characterStateChangeDao().deleteById(change.id)
                    result.deletedStateChanges++
                } catch (e: Exception) {
                    result.warnings.add("상태변화 '${change.fieldKey} → ${change.newValue}' 삭제에 실패해 건너뛰었습니다: ${e.message}")
                }
            }
            if (result.deletedStateChanges > 0) {
                result.warnings.add("엑셀에 없는 상태변화 ${result.deletedStateChanges}건을 삭제했습니다 — 휴지통에서 복구할 수 있습니다")
            }
        }

        // 명대사 (사용자 요청 2026.08.20) — 상태 변화와 같은 규약이다.
        if (del.quotes && matchedQuoteIds.isNotEmpty()) {
            val allIds = db.characterQuoteDao().getAllQuotesList().map { it.id }
            for (id in allIds) {
                if (id !in matchedQuoteIds) {
                    try { db.characterQuoteDao().deleteById(id); result.deletedQuotes++ }
                    catch (e: Exception) {
                        result.warnings.add("명대사 1건(#$id) 삭제에 실패해 건너뛰었습니다: ${e.message}")
                    }
                }
            }
        }

        // 이름 은행
        if (del.nameBank && matchedNameBankIds.isNotEmpty()) {
            val allIds = db.nameBankDao().getAllEntryIds()
            for (id in allIds) {
                if (id !in matchedNameBankIds) {
                    try { db.nameBankDao().deleteById(id); result.deletedNameBank++ }
                    catch (e: Exception) {
                        result.warnings.add("이름 은행 1건(#$id) 삭제에 실패해 건너뛰었습니다: ${e.message}")
                    }
                }
            }
        }

        // 세력 관계
        if (del.factionRelationships && matchedFactionRelationshipIds.isNotEmpty()) {
            val allIds = db.factionRelationshipDao().getAllRelationshipIds()
            for (id in allIds) {
                if (id !in matchedFactionRelationshipIds) {
                    try { db.factionRelationshipDao().deleteById(id); result.deletedFactionRelationships++ }
                    catch (e: Exception) {
                        result.warnings.add("세력 관계 1건(#$id) 삭제에 실패해 건너뛰었습니다: ${e.message}")
                    }
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
            val doomedFactions = SqlInChunks.flat(doomed) { db.factionDao().getByIds(it) }
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
                    catch (e: Exception) {
                        result.warnings.add("세력 소속 1건(#$id) 삭제에 실패해 건너뛰었습니다: ${e.message}")
                    }
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
        if (!del.quotes && matchedQuoteIds.isNotEmpty()) {
            val n = db.characterQuoteDao().getAllQuotesList().count { it.id !in matchedQuoteIds }
            note(n, "명대사 ${n}건")
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

    /**
     * 가져오기 뒤 시맨틱 필드 동기화 — **한 방향이다**: 필드값 → 상태변화 (B-227 ②).
     *
     * 종전 KDoc은 *"필드값 → 상태변화, 상태변화 → 필드값 **양방향**"*이라 적혀 있었는데
     * 부르는 것은 [SemanticFieldSyncHelper.syncFieldToStateChange] 하나다. 반대 방향은 인앱
     * 편집 경로가 하고, 여기서 걸면 파일이 말한 필드값을 **DB에 있던 상태변화가 도로 덮는다** —
     * 가져오기에서는 파일이 권위이므로 그 방향을 걸지 않는 것이 맞다.
     *
     * **실패는 삼키지 않는다.** 이 단계는 커밋 뒤 부가 작업이라 실패해도 가져오기를 되돌리지
     * 않지만(되돌리면 들어온 데이터를 통째로 버린다), 종전에는 Logcat에만 남아
     * **사용자에게는 성공으로 보였다** — 생존여부·사망연도 같은 파생 값이 조용히 옛것으로
     * 남는 자리라 결과에 적어 고친다(개발 의도 2번 — 검증 → 알림 → 바로잡을 경로).
     */
    private suspend fun runPostImportSemanticSync(result: ImportResult) {
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

        val failedIds = mutableListOf<Long>()
        // **세계관 필드 목록은 루프 불변량이다** — 캐릭터마다 다시 읽으면 이 꼬리가
        // 가져오기 트랜잭션 안에서 캐릭터 수만큼 같은 질의를 친다. 대상은 세계관이
        // 섞이므로 표로 든다.
        val fieldsByUniverse = HashMap<Long, List<com.novelcharacter.app.data.model.FieldDefinition>>()
        for ((characterId, universeId) in pendingSyncCharacters) {
            try {
                val fieldValues = db.characterFieldValueDao().getValuesByCharacterList(characterId)
                val fields = fieldsByUniverse.getOrPut(universeId) {
                    universeRepository.getFieldsByUniverseList(universeId)
                }
                syncHelper.syncFieldToStateChange(
                    characterId, fields, fieldValues,
                    // **이 파일이 실제로 지운 칸만** 비움으로 읽는다. 값 전량을 넘기므로
                    // 부재를 곧 비움으로 읽으면 연표 사건으로 생긴 `__birth`까지 지워진다.
                    clearableFieldIds = pendingSyncClearedFields[characterId]
                )
            } catch (e: Exception) {
                android.util.Log.w("ExcelImport", "Post-import sync failed for character $characterId", e)
                failedIds.add(characterId)
            }
        }
        if (failedIds.isNotEmpty()) {
            // 이름으로 말한다 — id는 사용자가 화면에서 찾을 수 있는 값이 아니다.
            // 이름 조회 자체가 실패해도 고지는 남아야 하므로 실패분은 id로 적는다.
            val names = failedIds.take(5).map { id ->
                runCatching { db.characterDao().getCharacterById(id)?.name }.getOrNull() ?: "#$id"
            }
            val detail = names.joinToString(", ") + if (failedIds.size > names.size) " 외 ${failedIds.size - names.size}명" else ""
            result.warnings.add(
                "캐릭터 ${failedIds.size}명: 출생·사망연도와 상태변화를 맞추지 못했습니다 ($detail) — " +
                    "데이터는 그대로 들어왔고, 해당 캐릭터를 열어 저장하면 다시 맞춰집니다"
            )
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

        /**
         * 앱 설정 경고 한 줄에 되비칠 값의 길이 상한.
         *
         * AI 메시지 양식 행은 한 칸이 수천 자라(2026.08.20), 값을 통째로 실으면
         * **그 경고 자체를 읽을 수 없게 된다.** 어느 행이 왜 거절됐는지는 키와 사유가 말한다.
         */
        private const val SETTING_VALUE_IN_WARNING = 60
        // 시트명 상수는 SheetSpec.kt가 단일 소스다 (내보내기도 같은 상수를 본다).
    }
}

