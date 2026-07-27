package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.*
import com.novelcharacter.app.util.FieldValueMatchSpec
import com.novelcharacter.app.util.FieldValueMatcher
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.FormulaEvaluator
import com.novelcharacter.app.util.NumericBinning
import com.novelcharacter.app.util.StatsFieldPolicy
import com.novelcharacter.app.util.ValueDistributions

/**
 * 통계 데이터를 한 번에 로딩하여 캐싱하는 데이터 제공자.
 * 각 상세 화면에서 필요한 통계를 여기서 뽑아간다.
 */
data class StatsSnapshot(
    val characters: List<Character>,
    val novels: List<Novel>,
    val universes: List<Universe>,
    val events: List<TimelineEvent>,
    val relationships: List<CharacterRelationship>,
    val relationshipChanges: List<CharacterRelationshipChange>,
    val tags: List<CharacterTag>,
    val nameBank: List<NameBankEntry>,
    val stateChanges: List<CharacterStateChange>,
    val fieldDefinitions: List<FieldDefinition>,
    val fieldValues: List<CharacterFieldValue>,
    val crossRefs: List<TimelineCharacterCrossRef>,
    val factions: List<Faction> = emptyList(),
    val factionMemberships: List<FactionMembership> = emptyList(),
    val eventNovelCrossRefs: List<TimelineEventNovelCrossRef> = emptyList(),
    // 사건 커스텀 필드 (B-10) — "모든 필드가 통계에서 분석 가능해야 한다"(원칙 02)
    val eventFieldDefinitions: List<FieldDefinition> = emptyList(),
    val eventFieldValues: List<EventFieldValue> = emptyList(),
    // 값 데이터 라이브러리 — 별칭 접기·표시 라벨·카테고리의 단일 소스 (구 valueLabels/valueCategories 대체)
    val valueEntries: List<com.novelcharacter.app.data.model.FieldValueEntry> = emptyList(),
    /**
     * "작품 미배정" 스코프 표시 — novels/universes가 비므로 캐릭터 모수·필드 완성도를
     * novelId 경유 대신 스냅샷 자체(캐릭터 전체·보존 정의) 기준으로 계산해야 한다.
     * [StatsDataProvider.filterByNovel]의 sentinel 분기만 true로 만든다.
     */
    val unassignedScope: Boolean = false
)

// ===== 요약 통계 =====
data class SummaryStats(
    val totalCharacters: Int,
    val totalNovels: Int,
    val totalUniverses: Int,
    val totalEvents: Int,
    val totalRelationships: Int,
    val totalNames: Int,
    // 인사이트 요약
    val mostActiveNovel: String?,
    val mostConnectedChar: String?,
    val dataHealthIssueCount: Int,
    val avgFieldCompletion: Float,
    val recentActivityCount: Int, // 최근 7일 생성/수정 캐릭터
    // 분석적 인사이트 (원칙 02: 입력량보다 분석 우선)
    val specializationDist: Map<String, Int> = emptyMap(), // 특화유형 분포
    val topFieldValues: List<Triple<String, String, Int>> = emptyList(), // (필드명, 값, 개수) TOP N
    val eventDensityPeak: String? = null // 사건 밀도 최고 시기
)

// ===== 캐릭터 분석 =====
data class CharacterStats(
    val tagDistribution: Map<String, Int>,
    val novelCharacterCounts: Map<String, Int>,
    val relationshipTypeDist: Map<String, Int>,
    val topRelationshipChars: List<Pair<String, Int>>,
    val topEventLinkedChars: List<Pair<String, Int>>,
    val fieldCompletionRates: List<Pair<String, Float>>,
    val survivalPeriods: List<Pair<String, Int>>,
    // 신규
    val fieldCompletionByGroup: Map<String, Float>,
    val complexityScores: List<CharacterComplexity>,
    val memoStats: MemoUsageStats,
    val anotherNameRate: Float, // 별명 보유율 (%)
    val totalAliasCount: Int = 0,
    val lastNameDistribution: List<Pair<String, Int>> = emptyList()
)

data class CharacterComplexity(
    val name: String,
    val relationshipCount: Int,
    val eventLinkCount: Int,
    val fieldCompletionRate: Float?, // null = 작품 미배정으로 산출 불가
    val stateChangeCount: Int,
    val totalScore: Float,
    val overallPotential: PotentialGrade = PotentialGrade.D,
    val specialization: Specialization = Specialization.NONE,
    val hasNovelAssignment: Boolean = true // false면 작품 미배정 → 필드 완성도 산출 불가
) {
    /** 종합 잠재력 등급 */
    enum class PotentialGrade(val label: String, val colorKey: String) {
        S("S", "potential_s"),
        A("A", "potential_a"),
        B("B", "potential_b"),
        C("C", "potential_c"),
        D("D", "potential_d");

        companion object {
            fun fromScore(score: Float): PotentialGrade = when {
                score >= 30f -> S
                score >= 18f -> A
                score >= 10f -> B
                score >= 4f  -> C
                else         -> D
            }
        }
    }

    /** 특화 잠재력 유형 */
    enum class Specialization(val label: String, val icon: String) {
        RELATIONSHIP("관계형", "\uD83E\uDD1D"),   // 🤝
        EVENT("사건형", "\u26A1"),                  // ⚡
        DETAIL("설정형", "\uD83D\uDCDD"),          // 📝
        DYNAMIC("변화형", "\uD83D\uDD04"),          // 🔄
        BALANCED("균형형", "\u2696\uFE0F"),         // ⚖️
        NONE("미측정", "");

        companion object {
            fun determine(relWeight: Float, evtWeight: Float, fieldWeight: Float, stateWeight: Float): Specialization {
                val total = relWeight + evtWeight + fieldWeight + stateWeight
                if (total <= 0f) return NONE

                val relRatio = relWeight / total
                val evtRatio = evtWeight / total
                val fieldRatio = fieldWeight / total
                val stateRatio = stateWeight / total

                // 한 차원이 45% 이상 차지하면 그 쪽 특화
                val threshold = 0.45f
                return when {
                    relRatio >= threshold -> RELATIONSHIP
                    evtRatio >= threshold -> EVENT
                    fieldRatio >= threshold -> DETAIL
                    stateRatio >= threshold -> DYNAMIC
                    else -> BALANCED
                }
            }
        }
    }
}

data class MemoUsageStats(
    val withMemo: Int,
    val withoutMemo: Int,
    val avgMemoLength: Float
)

// ===== 사건 분석 =====
data class EventStats(
    val yearDensity: Map<Int, Int>,
    val novelEventCounts: Map<String, Int>,
    val avgCharsPerEvent: Float,
    val orphanEventCount: Int,
    val monthDistribution: Map<Int, Int>,
    // 신규
    val calendarTypeDistribution: Map<String, Int>,
    val timePrecision: TimePrecisionStats,
    val narrativeDensityCurve: List<Pair<Int, Int>>, // 연속 연도 밀도 (빈 연도 포함)
    val eventDescriptionLengthAvg: Float
)

data class TimePrecisionStats(
    val yearOnly: Int,
    val yearMonth: Int,
    val yearMonthDay: Int
)

// ===== 관계 분석 =====
data class RelationshipStats(
    val typeDistribution: Map<String, Int>,
    val topConnectedChars: List<Pair<String, Int>>,
    val isolatedCharacters: List<String>,
    // 네트워크 메트릭
    val networkDensity: Float,
    val descriptionCompleteness: Float, // 설명이 있는 관계 비율 (%)
    val emptyDescriptionCount: Int,
    val reciprocalPairCount: Int, // 양방향 관계 쌍 수
    val avgConnectionsPerChar: Float,
    // 강도/방향성 분석
    val intensityDistribution: Map<Int, Int>,      // 강도값(1~10) → 개수
    val avgIntensity: Float,                        // 평균 강도
    val bidirectionalCount: Int,                    // 양방향 관계 수
    val unidirectionalCount: Int,                   // 단방향 관계 수
    // 시간 추세 (RelationshipChange 기반)
    val changeTimeline: List<Pair<Int, Int>>,        // 연도 → 해당 연도 변화 수
    val typeChangeTrends: Map<String, List<Pair<Int, Int>>> // 유형별 연도→변화 수
)

// ===== 이름뱅크 =====
data class NameBankStats(
    val usageRate: Float,
    val totalNames: Int,
    val usedNames: Int,
    val genderDistribution: Map<String, Int>,
    val originDistribution: Map<String, Int>,
    // 신규
    val nameLengthDistribution: Map<Int, Int>,
    val firstCharDistribution: Map<String, Int>,
    val unusedNames: List<String>,
    val avgNameLength: Float
)

// ===== 데이터 건강도 =====
data class DataHealthStats(
    val noImageChars: List<String>,
    val incompleteFieldChars: List<Pair<String, Float>>,
    val isolatedChars: List<String>,
    val unlinkedChars: List<String>,
    val duplicateTags: List<String>,
    // 신규
    val noMemoChars: List<String>,
    val emptyDescRelationships: Int,
    val fieldCompletionByGroup: Map<String, Float>,
    val noAnotherNameChars: List<String>,
    val lowPrecisionEvents: Int, // 년도만 있는 사건 수
    val noNovelChars: List<String> = emptyList() // 작품 미배정 캐릭터
)

// ===== 커스텀 필드 분석 (레거시 - 호환용) =====
data class FieldAnalysisStats(
    val fieldValueDistributions: List<FieldValueDistribution>,
    val numberFieldSummaries: List<NumberFieldSummary>,
    val fieldCompletionByField: List<FieldCompletionDetail>,
    val stateChangesByField: Map<String, Int>
)

data class FieldValueDistribution(
    val fieldDefId: Long = 0,
    val fieldName: String,
    val fieldType: String,
    val groupName: String,
    /** 분포 **전량**(건수 내림차순). 표시 상한은 화면이 [ValueDistributions.view]로 적용한다(R-14). */
    val distribution: Map<String, Int>,
    /**
     * 라벨 → 드릴다운 매치 스펙 (S-16).
     *
     * 라벨이 값 자체인 분포는 [FieldValueMatchSpec.Values]이고, 라벨이 계산 결과인 분포
     * (BODY_SIZE 파트별 자동 구간)는 그 구간을 담은 [FieldValueMatchSpec.NumericPartRange]다.
     * 라벨 문자열을 매칭 키로 재사용하면 후자는 어떤 입력에서도 0명이 된다.
     */
    val matchSpecs: Map<String, FieldValueMatchSpec> = emptyMap(),
    /**
     * 순서 자체가 정보인 분포인가(수치 구간). 표시 계층이 건수순으로 재정렬하면
     * 인접 구간이 흩어져 '어디에 몰렸는가'를 읽을 수 없다.
     */
    val orderedByValue: Boolean = false
)

data class NumberFieldSummary(
    val fieldName: String,
    val min: Float,
    val max: Float,
    val avg: Float,
    val median: Float,
    val count: Int,
    val values: List<Float> = emptyList()
)

data class FieldCompletionDetail(
    val fieldName: String,
    val groupName: String,
    val filledCount: Int,
    val totalCount: Int,
    val completionRate: Float
)

// ===== 필드 인사이트 (신규) =====
data class FieldInsightResult(
    val fieldDefinition: FieldDefinition,
    val statsConfig: FieldStatsConfig,
    val analysisResults: List<AnalysisResult>,
    val totalCount: Int,
    val filledCount: Int,
    val universeName: String = "",
    /**
     * 이 카드가 **실제로 합산한** 필드 정의 id 전체 (Pre-Analysis Merge된 (key, type) 그룹).
     *
     * 차트는 그룹 전체를 [fieldDefinition](= 그룹의 기준 def) config로 파싱해 합산하므로,
     * 드릴다운도 반드시 같은 집합·같은 기준 def로 조회해야 조각 수치와 목록 인원이 일치한다.
     * 대표 id 하나만 넘기면 전체 세계관 보기에서 조용한 과소집계가 된다(S-7).
     * 첫 원소는 항상 [fieldDefinition].id — 파싱 기준 def다.
     */
    val mergedFieldDefIds: List<Long> = listOf(fieldDefinition.id)
)

data class AnalysisResult(
    val entry: FieldStatsConfig.AnalysisEntry,
    val distributionData: Map<String, Int>?,
    val numericSummary: NumericSummaryData?
)

data class NumericSummaryData(
    val min: Float,
    val max: Float,
    val avg: Float,
    val median: Float,
    val stdDev: Float,
    val histogram: Map<String, Int>
)

// ===== 교차 분석 (신규) =====

/**
 * 교차분석의 집계 단위(축). 셀 값이 "무엇의 개수"인지를 정한다 (B-4).
 *
 * 캐릭터 축과 사건 축은 **한 표에 섞이지 않는다** — 섞으면 셀의 의미가 무너지기 때문이다.
 * 축이 다른 필드를 함께 고른 경우는 조용히 버리지 않고 [CrossAxisResolution.Mismatch]로 고지한다.
 */
enum class CrossAxis { CHARACTER, EVENT }

/** 고른 필드들이 어느 축에 속하는지의 판정 결과 — 실패도 이유를 담아 돌려준다 (변수 제어). */
sealed class CrossAxisResolution {
    data class Resolved(val axis: CrossAxis) : CrossAxisResolution()
    /** 캐릭터 필드와 사건 필드를 함께 고름 — 어느 쪽 필드가 걸렸는지 이름으로 고지한다. */
    data class Mismatch(val characterFieldName: String, val eventFieldName: String) : CrossAxisResolution()
    /** 현재 스냅샷(작품 필터 포함)에 없는 필드 id — 필터를 바꾼 뒤 옛 선택으로 실행한 경우 등. */
    object UnknownField : CrossAxisResolution()
}

data class CrossAnalysisResult(
    val field1Name: String,
    val field2Name: String,
    val filterFieldName: String?,
    val filterValue: String?,
    val crossTable: Map<String, Map<String, Int>>,
    val totalCount: Int,
    val filteredCount: Int,
    /** 다중값 필드 포함 여부 — true면 한 캐릭터/사건이 여러 칸에 집계될 수 있음을 UI가 고지한다 */
    val multiValue: Boolean = false,
    /** 셀 값의 단위 — CHARACTER면 캐릭터 수, EVENT면 사건 수 */
    val axis: CrossAxis = CrossAxis.CHARACTER,
    /**
     * 표에 합산된 세계관 수. 인사이트 목록이 같은 (key, type) 필드를 세계관 통합으로 보여주므로
     * 교차분석도 같은 범위를 집계한다 — 2 이상이면 UI가 통합 집계임을 고지한다.
     */
    val mergedUniverseCount: Int = 1
)

// ===== 작품별 비교 분석 (신규 - 원칙 05) =====
data class CrossNovelComparison(
    val novels: List<NovelComparisonEntry>
)

data class NovelComparisonEntry(
    val novelId: Long,
    val novelTitle: String,
    val characterCount: Int,
    val eventCount: Int,
    val relationshipCount: Int,
    val avgComplexity: Float,
    val specializationDist: Map<String, Int>,
    val topFieldValues: List<Pair<String, Int>> // (필드값, 개수) TOP 5
)

// ===== 데이터 현황 (신규 - 기존 여러 Stats 통합) =====
data class DataOverviewStats(
    val totalCharacters: Int,
    val totalNovels: Int,
    val totalUniverses: Int,
    val totalEvents: Int,
    val totalRelationships: Int,
    val totalNames: Int,
    val fieldCompletionByGroup: Map<String, Float>,
    val fieldCompletionByField: List<FieldCompletionDetail>,
    val yearDensity: Map<Int, Int>,
    val nameBankUsageRate: Float,
    val nameBankGenderDist: Map<String, Int>,
    val healthWarnings: HealthWarnings
)

data class HealthWarnings(
    val noImageCount: Int,
    val incompleteFieldCount: Int,
    val isolatedCharCount: Int,
    val unlinkedCharCount: Int
)

// ===== 패턴 감지 & 서사적 인사이트 (개선 3) =====

enum class PatternType(val label: String) {
    DOMINANCE("편중"),
    CLUSTER("집중"),
    ABSENCE("공백"),
    OUTLIER("이상치"),
    BALANCE("균형"),
    CROSS_NOVEL("작품 간 비교")
}

enum class PatternSeverity(val label: String) {
    HIGH("높음"),
    MEDIUM("보통"),
    LOW("정보")
}

data class PatternInsight(
    val type: PatternType,
    val severity: PatternSeverity,
    val title: String,
    val description: String,
    val suggestion: String,
    val fieldDefId: Long? = null,
    /**
     * 드릴다운(어시스턴트 '전체 보기')용 구조화 필드 — 문자열 파싱 없이 해당 캐릭터를 뽑기 위함.
     * [mergedFieldDefIds]: 같은 (key,type)로 묶인 전 세계관 fieldDefId 전체(단일 fieldDefId 저장 시
     * 다세계관 과소집계되던 문제 해결). [drilldownValues]: 그 값을 가진(또는 [drilldownExclude]면
     * 갖지 않은) 캐릭터를 시트에 펼친다. [population]: 스코프 내 값 총수(최소 모집단 게이트용).
     */
    val fieldKey: String? = null,
    val fieldType: String? = null,
    val mergedFieldDefIds: List<Long> = emptyList(),
    val drilldownValues: List<String> = emptyList(),
    val drilldownExclude: Boolean = false,
    val population: Int = 0
)

// ===== 세력 통계 =====
data class FactionStatsResult(
    val totalFactions: Int,
    val factionMemberCounts: Map<String, Int>,
    val multiMemberCharacters: Int,
    val autoRelationshipCount: Int,
    val departureCount: Int,
    val factionlessCharacterCount: Int
)

// ===== 차트 탭 → 캐릭터 목록 (개선 6) =====
data class FieldValueCharacter(
    val characterId: Long,
    val characterName: String,
    val fieldValue: String,
    val imageUri: String?
)

/** 사건 드릴다운 행 — [FieldValueCharacter]의 사건판 (S-9). */
data class FieldValueEvent(
    val eventId: Long,
    val description: String,
    val formattedDate: String,
    val year: Int,
    val fieldValue: String
)

data class SubgroupAnalysis(
    val targetFieldName: String,
    val distribution: Map<String, Int>,
    val totalCount: Int,
    /** 상한(SUBGROUP_DISTRIBUTION_LIMIT)에 걸려 표시되지 않은 값 종류 수 — 0보다 크면 UI가 고지한다(R-14). */
    val truncatedCount: Int = 0
)

/** 하위 그룹 분석이 한 번에 보여주는 값 종류 상한 — 문구도 이 상수로 채운다(R-14). */
const val SUBGROUP_DISTRIBUTION_LIMIT = 15

/**
 * 인사이트 카드와 같은 축으로 묶인 필드 하나 — 화면에 보이는 이름은 [primary],
 * 집계 대상은 [mergedFieldDefIds] 전체다.
 */
data class MergedFieldGroup(
    val primary: FieldDefinition,
    val mergedFieldDefIds: List<Long>
)

// ===== 순위 =====
data class RankingEntry(
    val characterId: Long,
    val characterName: String,
    val rank: Int,
    val value: Double,
    val displayValue: String,
    val imagePaths: String,
    val novelTitle: String?
)

data class RankingResult(
    val entries: List<RankingEntry>,
    val fieldName: String,
    val fieldType: String,
    val ascending: Boolean,
    val totalCharacters: Int,
    val excludedCount: Int
)

data class RankableField(
    val fieldDef: FieldDefinition,
    val bodySizeParts: List<String>?,
    val isNumeric: Boolean,
    /** 전체 세계관 모드에서 같은 key+type으로 머지된 모든 fieldDefId 목록 */
    val mergedFieldDefIds: List<Long> = listOf(fieldDef.id)
)

/**
 * 앱 인스턴스는 [loadSnapshot]만 필요로 한다 — 나머지 compute* 함수는 스냅샷만 보는 순수 계산이다.
 * 그래서 생성자에서 앱을 받지 않는다: Android 런타임 없이도 집계 규칙을 실제로 실행해 검증할 수 있다.
 */
class StatsDataProvider {

    suspend fun loadSnapshot(app: NovelCharacterApp): StatsSnapshot {
        val db = app.database
        return StatsSnapshot(
            characters = app.characterRepository.getAllCharactersList(),
            novels = app.novelRepository.getAllNovelsList(),
            universes = app.universeRepository.getAllUniversesList(),
            events = app.timelineRepository.getAllEventsList(),
            relationships = app.characterRepository.getAllRelationships(),
            relationshipChanges = app.characterRepository.getAllRelationshipChanges(),
            tags = db.characterTagDao().getAllTagsList(),
            nameBank = db.nameBankDao().getAllNamesList(),
            stateChanges = db.characterStateChangeDao().getAllChangesList(),
            fieldDefinitions = db.fieldDefinitionDao().getAllFieldsList(),
            fieldValues = db.characterFieldValueDao().getAllValuesList(),
            crossRefs = db.timelineDao().getAllCrossRefs(),
            factions = app.factionRepository.getAllFactionsList(),
            factionMemberships = app.factionRepository.getAllMembershipsList(),
            eventNovelCrossRefs = db.timelineDao().getAllEventNovelCrossRefs(),
            eventFieldDefinitions = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_EVENT),
            eventFieldValues = db.eventFieldValueDao().getAllValuesList(),
            valueEntries = db.fieldValueEntryDao().getAllList()
        )
    }

    /**
     * 값 라이브러리 해석기 — **스냅샷에서만 파생한다**(라벨·카테고리·별칭 접기의 단일 소스).
     *
     * 종전에는 `loadSnapshot`이 채우는 provider의 가변 필드였다. 그래서
     * - 다른 인스턴스가 만든 스냅샷(또는 테스트가 조립한 스냅샷)을 넘기면 **별칭 접기가
     *   조용히 사라졌고**(폴백이 구 config 맵 경로라 예외조차 나지 않는다),
     * - 서로 다른 스냅샷을 동시에 계산하는 경로(작품별 비교는 원본, 나머지는 필터본)에서
     *   한 필드가 두 스냅샷을 오갔다.
     *
     * 스냅샷은 불변이므로 동일성(===)이 캐시 키다. 필터본은 `copy`로 `valueEntries`를
     * 그대로 물려받으므로 원본과 같은 해석기를 얻는다.
     */
    // 읽기는 **값 하나를 파싱할 때마다** 일어난다(캐릭터 수 × 필드 수). 그래서 둘을 지킨다:
    //
    // 1. **키는 스냅샷이 아니라 `valueEntries`다.** 필터본은 `copy`로 이 리스트를 그대로 물려받으므로
    //    원본과 필터본이 같은 캐시를 쓴다. 스냅샷 동일성으로 잡으면 원본·필터본을 오가는 경로에서
    //    (작품별 비교는 원본, 나머지는 필터본) 캐시가 매번 빗나가 **값마다 해석기 전량을 다시 만든다.**
    // 2. **키와 값은 한 객체로 게시한다.** 둘을 따로 쓰면 서로 다른 입력의 두 스레드가 교차 기록해
    //    "A의 키 + B의 값" 짝이 남는다(@Volatile은 각 필드의 가시성만 보장할 뿐 짝을 묶지 않는다).
    private class ResolverCache(
        val entries: List<com.novelcharacter.app.data.model.FieldValueEntry>,
        val resolvers: Map<Long, com.novelcharacter.app.util.FieldValueResolver>
    )

    @Volatile private var resolverCache: ResolverCache? = null

    private fun resolversOf(s: StatsSnapshot): Map<Long, com.novelcharacter.app.util.FieldValueResolver> {
        resolverCache?.let { if (it.entries === s.valueEntries) return it.resolvers }
        val built = s.valueEntries
            .groupBy { it.fieldDefinitionId }
            .mapValues { (_, entries) -> com.novelcharacter.app.util.FieldValueResolver(entries) }
        resolverCache = ResolverCache(s.valueEntries, built)
        return built
    }

    /** 스냅샷을 특정 작품으로 필터링. [UnassignedFilter.NO_NOVEL_ID]는 "작품 미배정" 스코프 */
    fun filterByNovel(s: StatsSnapshot, novelId: Long): StatsSnapshot {
        if (novelId == com.novelcharacter.app.util.UnassignedFilter.NO_NOVEL_ID) {
            return filterByNovelUnassigned(s)
        }
        val novel = s.novels.find { it.id == novelId } ?: return s
        val charIds = s.characters.filter { it.novelId == novelId }.map { it.id }.toSet()
        val eventIdsForNovel = s.eventNovelCrossRefs.filter { it.novelId == novelId }.map { it.eventId }.toSet()
        val eventIds = eventIdsForNovel
        val filteredRelationships = s.relationships.filter { it.characterId1 in charIds || it.characterId2 in charIds }
        val relIds = filteredRelationships.map { it.id }.toSet()
        // nameBank: 작품 필터 시 해당 작품 캐릭터가 사용한 이름만 포함 (미사용 이름 제외)
        val filteredNameBank = s.nameBank.filter { entry ->
            entry.usedByCharacterId != null && entry.usedByCharacterId in charIds
        }
        val universeIds = setOfNotNull(novel.universeId)
        val filteredFactions = s.factions.filter { it.universeId in universeIds }
        val factionIds = filteredFactions.map { it.id }.toSet()
        val filteredMemberships = s.factionMemberships.filter { it.factionId in factionIds && it.characterId in charIds }
        return s.copy(
            characters = s.characters.filter { it.novelId == novelId },
            novels = listOf(novel),
            universes = s.universes.filter { it.id == novel.universeId },
            events = s.events.filter { it.id in eventIdsForNovel },
            relationships = filteredRelationships,
            relationshipChanges = s.relationshipChanges.filter { it.relationshipId in relIds },
            tags = s.tags.filter { it.characterId in charIds },
            nameBank = filteredNameBank,
            stateChanges = s.stateChanges.filter { it.characterId in charIds },
            fieldDefinitions = s.fieldDefinitions.filter { it.universeId == novel.universeId },
            fieldValues = s.fieldValues.filter { it.characterId in charIds },
            crossRefs = s.crossRefs.filter { it.characterId in charIds || it.eventId in eventIds },
            factions = filteredFactions,
            factionMemberships = filteredMemberships,
            eventNovelCrossRefs = s.eventNovelCrossRefs.filter { it.eventId in eventIds },
            eventFieldDefinitions = s.eventFieldDefinitions.filter { it.universeId == novel.universeId },
            eventFieldValues = s.eventFieldValues.filter { it.eventId in eventIdsForNovel }
        )
    }

    /**
     * "작품 미배정" 스코프 — novelId 없는 캐릭터와 어느 작품에도 배정되지 않은 사건.
     * 세계관 스코프가 없으므로 novels/universes/factions는 비우되,
     * **필드 정의는 미배정 캐릭터가 실제 보존 중인 값이 참조하는 정의를 포함**한다
     * (원칙 02 — 미배정 캐릭터의 데이터도 통계에서 소외되지 않아야 함).
     */
    private fun filterByNovelUnassigned(s: StatsSnapshot): StatsSnapshot {
        val charIds = s.characters.filter { it.novelId == null }.map { it.id }.toSet()
        val assignedEventIds = s.eventNovelCrossRefs.map { it.eventId }.toSet()
        val eventIds = s.events.filter { it.id !in assignedEventIds }.map { it.id }.toSet()
        val filteredRelationships = s.relationships.filter { it.characterId1 in charIds || it.characterId2 in charIds }
        val relIds = filteredRelationships.map { it.id }.toSet()
        val filteredFieldValues = s.fieldValues.filter { it.characterId in charIds }
        val referencedDefIds = filteredFieldValues.map { it.fieldDefinitionId }.toSet()
        val filteredEventFieldValues = s.eventFieldValues.filter { it.eventId in eventIds }
        val referencedEventDefIds = filteredEventFieldValues.map { it.fieldDefinitionId }.toSet()
        return s.copy(
            characters = s.characters.filter { it.novelId == null },
            novels = emptyList(),
            universes = emptyList(),
            events = s.events.filter { it.id in eventIds },
            relationships = filteredRelationships,
            relationshipChanges = s.relationshipChanges.filter { it.relationshipId in relIds },
            tags = s.tags.filter { it.characterId in charIds },
            nameBank = s.nameBank.filter { it.usedByCharacterId != null && it.usedByCharacterId in charIds },
            stateChanges = s.stateChanges.filter { it.characterId in charIds },
            fieldDefinitions = s.fieldDefinitions.filter { it.id in referencedDefIds },
            fieldValues = filteredFieldValues,
            crossRefs = s.crossRefs.filter { it.characterId in charIds || it.eventId in eventIds },
            factions = emptyList(),
            factionMemberships = emptyList(),
            eventNovelCrossRefs = emptyList(),
            eventFieldDefinitions = s.eventFieldDefinitions.filter { it.id in referencedEventDefIds },
            eventFieldValues = filteredEventFieldValues,
            unassignedScope = true
        )
    }

    /** 캐릭터 복잡도 경량 계산 (Summary에서 특화 분포용) */
    private fun computeCharacterComplexities(s: StatsSnapshot): List<CharacterComplexity> {
        val relCount = mutableMapOf<Long, Int>()
        s.relationships.forEach {
            relCount[it.characterId1] = (relCount[it.characterId1] ?: 0) + 1
            relCount[it.characterId2] = (relCount[it.characterId2] ?: 0) + 1
        }
        val eventCountMap = s.crossRefs.groupBy { it.characterId }.mapValues { it.value.size }
        val stateChangesByChar = s.stateChanges.groupBy { it.characterId }

        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val charFieldValues = s.fieldValues.groupBy { it.characterId }

        return s.characters.map { char ->
            val relCnt = relCount[char.id] ?: 0
            val evtCnt = eventCountMap[char.id] ?: 0
            val stateChangeCnt = stateChangesByChar[char.id]?.size ?: 0

            val novel = char.novelId?.let { novelMap[it] }
            // CALCULATED 필드는 자동 계산이므로 완성도 산출에서 제외
            val fields = novel?.let { fieldDefByUniverse[it.universeId]?.filter { f -> f.type != "CALCULATED" } }
            val filledCount = charFieldValues[char.id]?.count { it.value.isNotBlank() } ?: 0
            // 작품 미배정 캐릭터는 필드 완성도 산출 불가 (null)
            val completion: Float? = if (fields != null && fields.isNotEmpty()) {
                filledCount.toFloat() / fields.size * 100f
            } else if (char.novelId == null) {
                null // 작품 미배정 → 산출 불가
            } else {
                0f // 작품은 있지만 필드 정의가 없음
            }

            val relWeight = relCnt * 2f
            val evtWeight = evtCnt * 1.5f
            // completion이 null이면 작품 미배정 → fieldWeight를 0으로 하되 점수 불이익 없이 제외
            val fieldWeight = if (completion != null) (completion / 100f) * 5f else 0f
            val stateWeight = stateChangeCnt * 1f
            val score = relWeight + evtWeight + fieldWeight + stateWeight

            CharacterComplexity(
                char.name, relCnt, evtCnt, completion, stateChangeCnt, score,
                CharacterComplexity.PotentialGrade.fromScore(score),
                CharacterComplexity.Specialization.determine(relWeight, evtWeight, fieldWeight, stateWeight),
                hasNovelAssignment = char.novelId != null
            )
        }
    }

    fun computeSummary(s: StatsSnapshot): SummaryStats {
        val novelMap = s.novels.associateBy { it.id }
        val charMap = s.characters.associateBy { it.id }

        // 가장 캐릭터가 많은 작품
        val mostActiveNovel = s.characters.groupBy { it.novelId }
            .maxByOrNull { it.value.size }?.let { entry ->
                entry.key?.let { novelMap[it]?.title }
            }

        // 관계가 가장 많은 캐릭터
        val connCount = mutableMapOf<Long, Int>()
        s.relationships.forEach {
            connCount[it.characterId1] = (connCount[it.characterId1] ?: 0) + 1
            connCount[it.characterId2] = (connCount[it.characterId2] ?: 0) + 1
        }
        val mostConnectedChar = connCount.maxByOrNull { it.value }?.let {
            charMap[it.key]?.name
        }

        // 데이터 건강 이슈
        val noImageCount = s.characters.count { it.imagePaths.isBlank() || it.imagePaths == "[]" }
        val relCharIds = s.relationships.flatMap { listOf(it.characterId1, it.characterId2) }.toSet()
        val isolatedCount = s.characters.count { it.id !in relCharIds }
        val healthIssues = noImageCount + isolatedCount

        // 평균 필드 완성도 (CALCULATED 필드 제외 — 자동 계산 필드는 완성도 대상이 아님)
        val universeFieldCounts = s.fieldDefinitions.filter { it.type != "CALCULATED" }
            .groupBy { it.universeId }.mapValues { it.value.size }
        val charFieldCounts = s.fieldValues.groupBy { it.characterId }
            .mapValues { it.value.count { fv -> fv.value.isNotBlank() } }
        val completions = s.characters.mapNotNull { char ->
            val novelId = char.novelId ?: return@mapNotNull null
            val novel = novelMap[novelId] ?: return@mapNotNull null
            val total = universeFieldCounts[novel.universeId] ?: return@mapNotNull null
            if (total == 0) return@mapNotNull null
            val filled = charFieldCounts[char.id] ?: 0
            filled.toFloat() / total * 100f
        }
        val avgCompletion = if (completions.isNotEmpty()) completions.average().toFloat() else 0f

        // 최근 7일 활동
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val recentCount = s.characters.count { it.updatedAt >= sevenDaysAgo }

        // 분석적 인사이트: 특화 유형 분포 (미측정 제외 — 분석적 인사이트만 표시)
        val complexities = computeCharacterComplexities(s)
        val specDist = complexities
            .filter { it.specialization != CharacterComplexity.Specialization.NONE }
            .groupBy { it.specialization.label }
            .mapValues { it.value.size }

        // 분석적 인사이트: 주요 필드 값 TOP 5 (필드 이름 기준으로 세계관 간 통합 집계)
        // 필드 인사이트와 **같은 데이터·같은 규칙**을 센다: 저장 값 + CALCULATED 계산값(R-16),
        // 같은 파싱(getFieldValues: 콤마/구조화/라벨/카테고리), 같은 '통계에 포함' 필터.
        // 종전에는 계산 필드만 빠져 이 주석이 약속한 일치가 수식 필드에서 깨져 있었다(B-33).
        val fieldDefById = s.fieldDefinitions.associateBy { it.id }
        val statsCache = StatsFieldPolicy.ConfigCache()
        val topFieldValues = augmentedCharacterValues(s)
            .flatMap { (fieldDefId, values) ->
                val fd = fieldDefById[fieldDefId] ?: return@flatMap emptyList<Pair<String, String>>()
                if (!statsCache.isAnalyzable(fd)) return@flatMap emptyList<Pair<String, String>>()
                val cfg = statsCache.of(fd)
                values.filter { it.value.isNotBlank() }
                    .flatMap { fv -> getFieldValues(s, fd, fv.value, cfg).map { Pair(fd.name, it) } }
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenBy { it.key.first }
                    .thenBy { it.key.second }
            )
            .take(5)
            .map { entry -> Triple(entry.key.first, entry.key.second, entry.value) }

        // 분석적 인사이트: 사건 밀도 최고 시기
        val eventDensityPeak = if (s.events.isNotEmpty()) {
            val yearCounts = s.events.groupBy { it.year }.mapValues { it.value.size }
            val peakYear = yearCounts.maxByOrNull { it.value }
            peakYear?.let { "${it.key}년 (${it.value}건)" }
        } else null

        return SummaryStats(
            totalCharacters = s.characters.size,
            totalNovels = s.novels.size,
            totalUniverses = s.universes.size,
            totalEvents = s.events.size,
            totalRelationships = s.relationships.size,
            totalNames = s.nameBank.size,
            mostActiveNovel = mostActiveNovel,
            mostConnectedChar = mostConnectedChar,
            dataHealthIssueCount = healthIssues,
            avgFieldCompletion = avgCompletion,
            recentActivityCount = recentCount,
            specializationDist = specDist,
            topFieldValues = topFieldValues,
            eventDensityPeak = eventDensityPeak
        )
    }

    fun computeCharacterStats(s: StatsSnapshot): CharacterStats {
        val charMap = s.characters.associateBy { it.id }
        val novelMap = s.novels.associateBy { it.id }

        // 태그 분포
        val tagDist = s.tags.groupBy { it.tag }.mapValues { it.value.size }
            .entries.sortedByDescending { it.value }.associate { it.key to it.value }

        // 소설별 캐릭터 수
        val novelCharCounts = s.characters.groupBy { it.novelId }
            .mapKeys { (novelId, _) -> novelId?.let { novelMap[it]?.title } ?: "미지정" }
            .mapValues { it.value.size }

        // 관계 유형 분포
        val relTypeDist = s.relationships.groupBy { it.relationshipType }
            .mapValues { it.value.size }

        // 관계 수 TOP 10
        val relCount = mutableMapOf<Long, Int>()
        s.relationships.forEach {
            relCount[it.characterId1] = (relCount[it.characterId1] ?: 0) + 1
            relCount[it.characterId2] = (relCount[it.characterId2] ?: 0) + 1
        }
        val topRelChars = relCount.entries.sortedByDescending { it.value }.take(10)
            .map { (charMap[it.key]?.name ?: "?") to it.value }

        // 사건 연계 TOP 10
        val eventCountMap = mutableMapOf<Long, Int>()
        s.crossRefs.forEach { ref ->
            eventCountMap[ref.characterId] = (eventCountMap[ref.characterId] ?: 0) + 1
        }
        val topEventChars = eventCountMap.entries.sortedByDescending { it.value }.take(10)
            .map { (charMap[it.key]?.name ?: "?") to it.value }

        // 필드 완성도 (CALCULATED 필드 제외 — 자동 계산 필드는 완성도 대상이 아님)
        val universeFieldCounts = s.fieldDefinitions.filter { it.type != "CALCULATED" }
            .groupBy { it.universeId }.mapValues { it.value.size }
        val charFieldCounts = s.fieldValues.groupBy { it.characterId }
            .mapValues { it.value.count { fv -> fv.value.isNotBlank() } }

        val fieldCompletionById = mutableMapOf<Long, Float>()
        s.characters.forEach { char ->
            val novelId = char.novelId ?: return@forEach
            val novel = novelMap[novelId] ?: return@forEach
            val totalFields = universeFieldCounts[novel.universeId] ?: return@forEach
            if (totalFields == 0) return@forEach
            val filled = charFieldCounts[char.id] ?: 0
            fieldCompletionById[char.id] = filled.toFloat() / totalFields * 100f
        }

        // 생존기간
        val stateChangesByChar = s.stateChanges.groupBy { it.characterId }
        val survivalPeriods = s.characters.mapNotNull { char ->
            val changes = stateChangesByChar[char.id] ?: return@mapNotNull null
            val birth = changes.find { it.fieldKey == CharacterStateChange.KEY_BIRTH }?.year
            val death = changes.find { it.fieldKey == CharacterStateChange.KEY_DEATH }?.year
            if (birth != null && death != null) {
                char.name to (death - birth)
            } else null
        }

        // 신규: 그룹별 필드 완성도 (CALCULATED 필드 제외)
        val fieldDefByUniverse = s.fieldDefinitions.filter { it.type != "CALCULATED" }
            .groupBy { it.universeId }
        val fieldDefIdToField = s.fieldDefinitions.associateBy { it.id }
        val charFieldValuesByChar = s.fieldValues.groupBy { it.characterId }

        val groupCompletions = mutableMapOf<String, MutableList<Float>>()
        s.characters.forEach { char ->
            val novelId = char.novelId ?: return@forEach
            val novel = novelMap[novelId] ?: return@forEach
            val fieldsForUniverse = fieldDefByUniverse[novel.universeId] ?: return@forEach
            val charValues = charFieldValuesByChar[char.id] ?: emptyList()
            val filledDefIds = charValues.filter { it.value.isNotBlank() }.map { it.fieldDefinitionId }.toSet()

            fieldsForUniverse.groupBy { it.groupName }.forEach { (group, fields) ->
                val rate = if (fields.isEmpty()) 0f else fields.count { it.id in filledDefIds }.toFloat() / fields.size * 100f
                groupCompletions.getOrPut(group) { mutableListOf() }.add(rate)
            }
        }
        val fieldCompletionByGroup = groupCompletions.mapValues { (_, rates) ->
            if (rates.isEmpty()) 0f else rates.average().toFloat()
        }

        // 신규: 캐릭터 복잡도 스코어 + 종합/특화 잠재력
        val complexityScores = s.characters.map { char ->
            val relCnt = relCount[char.id] ?: 0
            val evtCnt = eventCountMap[char.id] ?: 0
            val completion = fieldCompletionById[char.id] // null = 작품 미배정으로 산출 불가
            val stateChangeCnt = stateChangesByChar[char.id]?.size ?: 0

            val relWeight = relCnt * 2f
            val evtWeight = evtCnt * 1.5f
            // completion이 null이면 작품 미배정 → fieldWeight를 0으로 하되 점수 불이익 없이 제외
            val fieldWeight = if (completion != null) (completion / 100f) * 5f else 0f
            val stateWeight = stateChangeCnt * 1f
            val score = relWeight + evtWeight + fieldWeight + stateWeight

            val overallPotential = CharacterComplexity.PotentialGrade.fromScore(score)
            val specialization = CharacterComplexity.Specialization.determine(
                relWeight, evtWeight, fieldWeight, stateWeight
            )

            CharacterComplexity(
                char.name, relCnt, evtCnt, completion, stateChangeCnt, score,
                overallPotential, specialization,
                hasNovelAssignment = char.novelId != null
            )
        }.sortedByDescending { it.totalScore }

        // 신규: 메모 통계
        val withMemo = s.characters.count { it.memo.isNotBlank() }
        val memoLengths = s.characters.filter { it.memo.isNotBlank() }.map { it.memo.length }
        val avgMemoLen = if (memoLengths.isNotEmpty()) memoLengths.average().toFloat() else 0f

        // 신규: 별명 보유율
        val anotherNameRate = if (s.characters.isNotEmpty()) {
            s.characters.count { it.anotherName.isNotBlank() }.toFloat() / s.characters.size * 100f
        } else 0f

        // 총 별칭 개수
        val totalAliasCount = s.characters.sumOf { it.aliases.size }

        // 성씨 분포 (lastName 기반)
        val lastNameDist = s.characters
            .filter { it.lastName.isNotBlank() }
            .groupBy { it.lastName }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(10)

        return CharacterStats(
            tagDistribution = tagDist,
            novelCharacterCounts = novelCharCounts,
            relationshipTypeDist = relTypeDist,
            topRelationshipChars = topRelChars,
            topEventLinkedChars = topEventChars,
            fieldCompletionRates = fieldCompletionById.map { (id, rate) -> (charMap[id]?.name ?: "?") to rate },
            survivalPeriods = survivalPeriods,
            fieldCompletionByGroup = fieldCompletionByGroup,
            complexityScores = complexityScores,
            memoStats = MemoUsageStats(withMemo, s.characters.size - withMemo, avgMemoLen),
            anotherNameRate = anotherNameRate,
            totalAliasCount = totalAliasCount,
            lastNameDistribution = lastNameDist
        )
    }

    fun computeEventStats(s: StatsSnapshot): EventStats {
        val novelMap = s.novels.associateBy { it.id }

        val yearDensity = s.events.groupBy { it.year }.mapValues { it.value.size }

        // 크로스레프 기반: 하나의 사건이 여러 작품에 카운트될 수 있음
        val eventIdSet = s.events.map { it.id }.toSet()
        val novelEventCounts = run {
            val counts = mutableMapOf<String, Int>()
            val eventNovels = s.eventNovelCrossRefs.filter { it.eventId in eventIdSet }
            for (cr in eventNovels) {
                val title = novelMap[cr.novelId]?.title ?: "미지정"
                counts[title] = (counts[title] ?: 0) + 1
            }
            // 작품 미연결 사건 수
            val linkedEventIds = eventNovels.map { it.eventId }.toSet()
            val unlinkedCount = s.events.count { it.id !in linkedEventIds }
            if (unlinkedCount > 0) counts["미지정"] = (counts["미지정"] ?: 0) + unlinkedCount
            counts
        }

        val eventCharCounts = s.crossRefs.groupBy { it.eventId }.mapValues { it.value.size }
        val avgCharsPerEvent = if (s.events.isNotEmpty()) {
            eventCharCounts.values.sum().toFloat() / s.events.size
        } else 0f

        val linkedEventIds = s.crossRefs.map { it.eventId }.toSet()
        val orphanCount = s.events.count { it.id !in linkedEventIds }

        val monthDist = s.events.filter { it.month != null }
            .groupBy { it.month!! }.mapValues { it.value.size }

        // 신규: 달력 유형 분포
        val calendarTypeDist = s.events.groupBy { it.calendarType }
            .mapValues { it.value.size }

        // 신규: 시간 정밀도
        val yearOnly = s.events.count { it.month == null }
        val yearMonth = s.events.count { it.month != null && it.day == null }
        val yearMonthDay = s.events.count { it.month != null && it.day != null }

        // 신규: 서사 밀도 곡선 (빈 연도 포함)
        val narrativeDensity = if (yearDensity.isNotEmpty()) {
            val minYear = yearDensity.keys.min()
            val maxYear = yearDensity.keys.max()
            (minYear..maxYear).map { year -> year to (yearDensity[year] ?: 0) }
        } else emptyList()

        // 신규: 사건 설명 평균 길이
        val descLengths = s.events.map { it.description.length }
        val avgDescLen = if (descLengths.isNotEmpty()) descLengths.average().toFloat() else 0f

        return EventStats(
            yearDensity = yearDensity,
            novelEventCounts = novelEventCounts,
            avgCharsPerEvent = avgCharsPerEvent,
            orphanEventCount = orphanCount,
            monthDistribution = monthDist,
            calendarTypeDistribution = calendarTypeDist,
            timePrecision = TimePrecisionStats(yearOnly, yearMonth, yearMonthDay),
            narrativeDensityCurve = narrativeDensity,
            eventDescriptionLengthAvg = avgDescLen
        )
    }

    fun computeRelationshipStats(s: StatsSnapshot): RelationshipStats {
        val charMap = s.characters.associateBy { it.id }

        val typeDist = s.relationships.groupBy { it.relationshipType }
            .mapValues { it.value.size }

        val connCount = mutableMapOf<Long, Int>()
        s.relationships.forEach {
            connCount[it.characterId1] = (connCount[it.characterId1] ?: 0) + 1
            connCount[it.characterId2] = (connCount[it.characterId2] ?: 0) + 1
        }
        val topConnected = connCount.entries.sortedByDescending { it.value }.take(10)
            .map { (charMap[it.key]?.name ?: "?") to it.value }

        val relCharIds = s.relationships.flatMap { listOf(it.characterId1, it.characterId2) }.toSet()
        val isolated = s.characters.filter { it.id !in relCharIds }.map { it.name }

        // 신규: 네트워크 밀도 = 실제관계 / 가능한관계(n*(n-1)/2)
        val n = s.characters.size
        val density = if (n > 1) {
            val maxPossible = n.toLong() * (n - 1) / 2.0f
            (s.relationships.size / maxPossible).coerceAtMost(1f)
        } else 0f

        // 신규: 설명 완성도
        val emptyDescCount = s.relationships.count { it.description.isBlank() }
        val descCompleteness = if (s.relationships.isNotEmpty()) {
            (s.relationships.size - emptyDescCount).toFloat() / s.relationships.size * 100f
        } else 0f

        // 신규: 양방향 관계 쌍 (A→B, B→A 동일 유형) — 정규화 키로 그룹핑 후 2개 이상인 쌍만 카운트
        val pairCounts = mutableMapOf<String, Int>()
        s.relationships.forEach { rel ->
            val key = "${minOf(rel.characterId1, rel.characterId2)}-${maxOf(rel.characterId1, rel.characterId2)}-${rel.relationshipType}"
            pairCounts[key] = (pairCounts[key] ?: 0) + 1
        }
        val reciprocalCount = pairCounts.count { it.value > 1 }

        // 캐릭터당 평균 연결
        val avgConn = if (s.characters.isNotEmpty()) {
            connCount.values.sum().toFloat() / s.characters.size
        } else 0f

        // 강도 분포
        val intensityDist = s.relationships.groupBy { it.intensity }.mapValues { it.value.size }
        val avgIntensity = if (s.relationships.isNotEmpty()) {
            s.relationships.sumOf { it.intensity }.toFloat() / s.relationships.size
        } else 0f

        // 방향성 분석
        val biCount = s.relationships.count { it.isBidirectional }
        val uniCount = s.relationships.size - biCount

        // 시간 추세 (RelationshipChange 기반)
        val changeTimeline = s.relationshipChanges
            .groupBy { it.year }
            .mapValues { it.value.size }
            .toSortedMap()
            .map { it.key to it.value }

        val typeChangeTrends = s.relationshipChanges
            .groupBy { it.relationshipType }
            .mapValues { (_, changes) ->
                changes.groupBy { it.year }
                    .mapValues { it.value.size }
                    .toSortedMap()
                    .map { it.key to it.value }
            }

        return RelationshipStats(
            typeDistribution = typeDist,
            topConnectedChars = topConnected,
            isolatedCharacters = isolated,
            networkDensity = density,
            descriptionCompleteness = descCompleteness,
            emptyDescriptionCount = emptyDescCount,
            reciprocalPairCount = reciprocalCount,
            avgConnectionsPerChar = avgConn,
            intensityDistribution = intensityDist,
            avgIntensity = avgIntensity,
            bidirectionalCount = biCount,
            unidirectionalCount = uniCount,
            changeTimeline = changeTimeline,
            typeChangeTrends = typeChangeTrends
        )
    }

    fun computeNameBankStats(s: StatsSnapshot): NameBankStats {
        val used = s.nameBank.count { it.isUsed }
        val rate = if (s.nameBank.isNotEmpty()) used.toFloat() / s.nameBank.size * 100f else 0f

        val genderDist = s.nameBank.groupBy { it.gender.ifBlank { "미지정" } }
            .mapValues { it.value.size }

        val originDist = s.nameBank.filter { it.origin.isNotBlank() }
            .groupBy { it.origin }.mapValues { it.value.size }

        // 신규: 이름 길이 분포
        val lengthDist = s.nameBank.groupBy { it.name.length }
            .mapValues { it.value.size }
            .toSortedMap()

        // 신규: 첫 글자 분포
        val firstCharDist = s.nameBank.filter { it.name.isNotBlank() }
            .groupBy { it.name.first().toString() }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
            .associate { it.key to it.value }

        // 신규: 미사용 이름 목록
        val unusedNames = s.nameBank.filter { !it.isUsed }.map { it.name }

        // 신규: 평균 이름 길이
        val avgLen = if (s.nameBank.isNotEmpty()) {
            s.nameBank.map { it.name.length }.average().toFloat()
        } else 0f

        return NameBankStats(
            usageRate = rate,
            totalNames = s.nameBank.size,
            usedNames = used,
            genderDistribution = genderDist,
            originDistribution = originDist,
            nameLengthDistribution = lengthDist,
            firstCharDistribution = firstCharDist,
            unusedNames = unusedNames,
            avgNameLength = avgLen
        )
    }

    fun computeDataHealth(s: StatsSnapshot): DataHealthStats {
        val novelMap = s.novels.associateBy { it.id }

        // 이미지 없는 캐릭터
        val noImage = s.characters.filter {
            it.imagePaths.isBlank() || it.imagePaths == "[]"
        }.map { it.name }

        // 필드 미입력률 높은 캐릭터 (CALCULATED 필드 제외 — 자동 계산 필드이므로 수동 완성도에 포함시키지 않음)
        val fieldDefByUniverse = s.fieldDefinitions.filter { it.type != "CALCULATED" }
            .groupBy { it.universeId }
        val charFieldValuesByChar = s.fieldValues.groupBy { it.characterId }

        // 미배정 스코프: novelId 경유가 불가 — 보존 정의(CALCULATED 제외) 대비 채움률로 판정
        // (computeDataOverview의 incompleteCount와 같은 기준 — 개수·명단 일치)
        val unassignedFields = if (s.unassignedScope) {
            s.fieldDefinitions.filter { it.type != "CALCULATED" }
        } else emptyList()
        val incomplete = s.characters.mapNotNull { char ->
            val fields = if (s.unassignedScope) {
                unassignedFields.ifEmpty { return@mapNotNull null }
            } else {
                val novelId = char.novelId ?: return@mapNotNull null
                val novel = novelMap[novelId] ?: return@mapNotNull null
                fieldDefByUniverse[novel.universeId] ?: return@mapNotNull null
            }
            if (fields.isEmpty()) return@mapNotNull null
            val charValues = charFieldValuesByChar[char.id] ?: emptyList()
            val filled = charValues.count { it.value.isNotBlank() }
            val rate = filled.toFloat() / fields.size * 100f
            if (rate < 50f) char.name to rate else null
        }

        // 관계 없는 캐릭터
        val relCharIds = s.relationships.flatMap { listOf(it.characterId1, it.characterId2) }.toSet()
        val isolated = s.characters.filter { it.id !in relCharIds }.map { it.name }

        // 사건 미연계 캐릭터
        val eventCharIds = s.crossRefs.map { it.characterId }.toSet()
        val unlinked = s.characters.filter { it.id !in eventCharIds }.map { it.name }

        // 중복 태그 (대소문자/공백 차이로 중복된 태그)
        val dupTags = s.tags.groupBy { it.tag.lowercase().trim() }
            .filter { it.value.size > 1 }
            .flatMap { it.value.map { t -> t.tag }.distinct() }

        // 신규: 메모 미작성 캐릭터
        val noMemo = s.characters.filter { it.memo.isBlank() }.map { it.name }

        // 신규: 설명 없는 관계 수
        val emptyDescRels = s.relationships.count { it.description.isBlank() }

        // 신규: 그룹별 필드 완성도
        val groupCompletions = mutableMapOf<String, MutableList<Float>>()
        s.characters.forEach { char ->
            val novelId = char.novelId ?: return@forEach
            val novel = novelMap[novelId] ?: return@forEach
            val fieldsForUniverse = fieldDefByUniverse[novel.universeId] ?: return@forEach
            val charValues = charFieldValuesByChar[char.id] ?: emptyList()
            val filledDefIds = charValues.filter { it.value.isNotBlank() }.map { it.fieldDefinitionId }.toSet()

            fieldsForUniverse.groupBy { it.groupName }.forEach { (group, fields) ->
                val rate = if (fields.isEmpty()) 0f else fields.count { it.id in filledDefIds }.toFloat() / fields.size * 100f
                groupCompletions.getOrPut(group) { mutableListOf() }.add(rate)
            }
        }
        val completionByGroup = groupCompletions.mapValues { (_, rates) ->
            if (rates.isEmpty()) 0f else rates.average().toFloat()
        }

        // 신규: 별명 없는 캐릭터
        val noAnotherName = s.characters.filter { it.anotherName.isBlank() }.map { it.name }

        // 신규: 시간 정밀도 낮은 사건
        val lowPrecision = s.events.count { it.month == null }

        // 작품 미배정 캐릭터
        val noNovel = s.characters.filter { it.novelId == null }.map { it.name }

        return DataHealthStats(
            noImageChars = noImage,
            incompleteFieldChars = incomplete,
            isolatedChars = isolated,
            unlinkedChars = unlinked,
            duplicateTags = dupTags,
            noMemoChars = noMemo,
            emptyDescRelationships = emptyDescRels,
            fieldCompletionByGroup = completionByGroup,
            noAnotherNameChars = noAnotherName,
            lowPrecisionEvents = lowPrecision,
            noNovelChars = noNovel
        )
    }

    // ===== 필드 인사이트 (신규) =====
    fun computeFieldInsights(s: StatsSnapshot): List<FieldInsightResult> {
        val universeMap = s.universes.associateBy { it.id }
        val statsCache = StatsFieldPolicy.ConfigCache()

        // 저장 값 + CALCULATED 계산값 (계산 필드는 저장 행이 없다 — R-16). 이 합성 규칙은
        // 패턴 감지·레거시 분석·요약 TOP5도 같은 헬퍼로 공유한다.
        val augmentedValuesByFieldDef = augmentedCharacterValues(s)

        // 동일 필드를 (key, type) 기준으로 세계관 통합 (Pre-Analysis Merge)
        val fieldGroups = statsCache.analyzable(s.fieldDefinitions)
            .groupBy { it.key to it.type }

        val characterInsights = fieldGroups.map { (_, fds) ->
            val primaryFd = fds.first()
            val statsConfig = statsCache.of(primaryFd)

            // 그룹 내 모든 필드의 값을 합산 (CALCULATED 포함)
            val rawValues = fds.flatMap { fd -> augmentedValuesByFieldDef[fd.id] ?: emptyList() }
                .map { it.value }

            // 관련 세계관 전체의 캐릭터 수. 미배정 스코프는 novels가 비어 있으므로
            // 스코프 캐릭터 전체가 모수 (novelId 경유 시 모수 0 → "채움 N / 전체 0" 모순 방지)
            val universeIds = fds.map { it.universeId }.toSet()
            val relevantNovelIds = s.novels.filter { it.universeId in universeIds }.map { it.id }.toSet()
            val totalCount = if (s.unassignedScope) s.characters.size
                else s.characters.count { it.novelId in relevantNovelIds }

            val universeName = if (fds.size == 1) {
                universeMap[primaryFd.universeId]?.name ?: ""
            } else ""

            buildFieldInsight(s, primaryFd, statsConfig, rawValues, totalCount, universeName,
                mergedFieldDefIds = fds.map { it.id })
        }

        // ── 사건 필드 인사이트 (B-10 후속): 캐릭터 필드와 동일 규칙으로 편입 (원칙 02) ──
        val eventValueStringsByFieldDef = mutableMapOf<Long, MutableList<String>>()
        s.eventFieldValues.filter { it.value.isNotBlank() }.forEach { fv ->
            eventValueStringsByFieldDef.getOrPut(fv.fieldDefinitionId) { mutableListOf() }.add(fv.value)
        }
        for ((_, fieldMap) in computeAllEventCalculatedValues(s)) {
            for ((fieldDefId, value) in fieldMap) {
                eventValueStringsByFieldDef.getOrPut(fieldDefId) { mutableListOf() }.add(value)
            }
        }
        val eventFieldGroups = statsCache.analyzable(s.eventFieldDefinitions)
            .groupBy { it.key to it.type }
        val eventInsights = eventFieldGroups.map { (_, fds) ->
            val primaryFd = fds.first()
            val statsConfig = statsCache.of(primaryFd)
            val rawValues = fds.flatMap { fd -> eventValueStringsByFieldDef[fd.id] ?: emptyList() }

            // 모수 = 해당 세계관들의 사건 수 (사건 필드는 세계관 소속 사건에만 부여 가능)
            val universeIds = fds.map { it.universeId }.toSet()
            val totalCount = s.events.count { it.universeId in universeIds }

            val universeName = if (fds.size == 1) {
                universeMap[primaryFd.universeId]?.name ?: ""
            } else ""

            buildFieldInsight(s, primaryFd, statsConfig, rawValues, totalCount, universeName,
                mergedFieldDefIds = fds.map { it.id })
        }

        return characterInsights + eventInsights
    }

    /** 필드 1개(세계관 통합 그룹)의 분석 결과 조립 — 캐릭터/사건 필드 공용 */
    private fun buildFieldInsight(
        s: StatsSnapshot,
        primaryFd: FieldDefinition,
        statsConfig: FieldStatsConfig,
        rawValues: List<String>,
        totalCount: Int,
        universeName: String,
        mergedFieldDefIds: List<Long>
    ): FieldInsightResult {
        val analysisResults = statsConfig.analyses.flatMap { entry ->
            when (entry.type) {
                FieldStatsConfig.StatsType.DISTRIBUTION -> {
                    val dist = computeFieldDistribution(s, primaryFd, rawValues, statsConfig)
                    listOf(AnalysisResult(entry, dist, null))
                }
                FieldStatsConfig.StatsType.NUMERIC -> {
                    computeNumericAnalysis(primaryFd, rawValues, statsConfig, entry)
                }
                FieldStatsConfig.StatsType.RANKING -> {
                    // 분포와 같은 전량을 싣는다 — 상위 N만 남기는 일은 표시 계층이 하고,
                    // 잘린 나머지는 '기타 N종 M건'으로 존재를 알린다(R-14).
                    val dist = computeFieldDistribution(s, primaryFd, rawValues, statsConfig)
                    listOf(AnalysisResult(entry, dist, null))
                }
            }
        }
        return FieldInsightResult(primaryFd, statsConfig, analysisResults, totalCount, rawValues.size,
            universeName = universeName, mergedFieldDefIds = mergedFieldDefIds)
    }

    /**
     * NUMERIC 분석 생성. BODY_SIZE는 파트별 개별 수치 통계를 반환한다.
     * 값 문자열 목록 기반 — 캐릭터/사건 등 어떤 엔티티의 필드값이든 동일하게 처리한다 (원칙 01).
     */
    private fun computeNumericAnalysis(
        fd: FieldDefinition,
        rawValues: List<String>,
        statsConfig: FieldStatsConfig,
        entry: FieldStatsConfig.AnalysisEntry
    ): List<AnalysisResult> {
        if (fd.type == "BODY_SIZE") {
            val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
            val separator = if (structuredConfig.enabled) structuredConfig.separator else "-"
            val partCount = if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                structuredConfig.parts.size
            } else {
                rawValues.firstOrNull()?.split(separator)?.size ?: 1
            }

            return (0 until partCount).mapNotNull { partIdx ->
                val partLabel = if (structuredConfig.enabled && partIdx < structuredConfig.parts.size) {
                    structuredConfig.parts[partIdx].label
                } else "파트${partIdx + 1}"

                val numericValues = rawValues.mapNotNull { value ->
                    val parts = value.split(separator).map { it.trim() }
                    parts.getOrNull(partIdx)?.toFloatOrNull()
                }
                if (numericValues.isNotEmpty()) {
                    AnalysisResult(
                        entry.copy(label = partLabel),
                        null,
                        computeNumericSummary(numericValues, statsConfig.binning)
                    )
                } else null
            }
        }

        // 기본 NUMERIC 분석
        val numericValues = rawValues.mapNotNull { it.toFloatOrNull() }
        val summary = if (numericValues.isNotEmpty()) {
            computeNumericSummary(numericValues, statsConfig.binning)
        } else null
        return listOf(AnalysisResult(entry, null, summary))
    }

    /**
     * 값 분포 **전량**(건수 내림차순). 표시 상한은 여기서 적용하지 않는다.
     *
     * 종전에는 이 함수가 상위 N개만 남기고 나머지를 버렸다. 그래서 화면의 비율(%) 분모가
     * '상위 N개의 합'이 되어 실제 점유율보다 부풀려졌고, 몇 종·몇 건이 잘렸는지 알 방법이
     * 아예 없었다(S-17). 상한은 감추는 장치가 아니라 접는 장치이므로(R-14) 자르는 일은
     * 표시 직전에 [ValueDistributions.view]가 한 번만 하고, 잘린 것은 개수로 함께 알린다.
     */
    private fun computeFieldDistribution(
        s: StatsSnapshot,
        fd: FieldDefinition,
        rawValues: List<String>,
        statsConfig: FieldStatsConfig
    ): Map<String, Int> {
        val allValues = mutableListOf<String>()

        for (value in rawValues) {
            allValues.addAll(getFieldValues(s, fd, value, statsConfig))
        }

        return ValueDistributions.of(allValues)
    }

    private fun getFieldValues(
        s: StatsSnapshot,
        fd: FieldDefinition,
        rawValue: String,
        statsConfig: FieldStatsConfig
    ): List<String> {
        // Step 1: 값 분리 (토큰화 단일 소스 — 라이브러리 수확·검색과 규칙 공유)
        val splitValues = FieldValueTokenizer.splitForStats(fd, rawValue)

        // Step 2/2.5: 라벨·카테고리 해석 — 값 라이브러리(별칭 접기 포함)가 단일 소스.
        // 엔트리가 없는 필드(시드 전·구버전)는 기존 config 맵 경로로 폴백해 통계가 왜곡되지 않는다.
        val resolver = resolversOf(s)[fd.id]
        val categorized = if (resolver != null && !resolver.isEmpty) {
            splitValues.flatMap { resolver.statsKeys(it, statsConfig.statsGroupBy) }
        } else {
            val labeled = splitValues.map { statsConfig.applyLabel(it) }
            if (statsConfig.valueCategories.isNotEmpty()) {
                labeled.flatMap { statsConfig.resolveStatsKeys(it) }
            } else labeled
        }

        // Step 3: 수치형 + 사용자 구간. CALCULATED도 수치이므로 같은 규칙을 받는다 —
        // 타입 하나만 하드코딩해 두면 수식 필드에 구간을 설정한 사용자에게 그 설정이
        // 조용히 무시된다(원칙 01: 필드 타입이 늘어도 규칙은 한 곳).
        //
        // **어느 구간에도 안 드는 값은 버리지 않는다.** 종전에는 `mapNotNull`이 그런 값을 통째로
        // 지워, 카드가 "채움 20"이라 해놓고 분포 합은 15인 상태를 아무 설명 없이 만들었다.
        // 세계관마다 구간이 다른 필드를 합산할 때는 형제 세계관 값이 통째로 사라지기도 한다.
        // 값이 있는데 안 보이는 것보다, 어디에도 안 든다는 사실을 보여 주는 편이 낫다(R-17).
        if (fd.type in BINNABLE_TYPES && statsConfig.binning?.mode == "custom") {
            return categorized.map { v ->
                val numeric = v.toFloatOrNull()
                numeric?.let { statsConfig.applyBinning(it) } ?: OUT_OF_RANGE_LABEL
            }
        }

        return categorized
    }

    private fun computeNumericSummary(
        values: List<Float>,
        binning: FieldStatsConfig.BinningConfig?
    ): NumericSummaryData? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val min = sorted.first()
        val max = sorted.last()
        val avg = values.average().toFloat()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else sorted[sorted.size / 2]

        // 표준편차
        val variance = values.map { (it - avg) * (it - avg) }.average().toFloat()
        val stdDev = kotlin.math.sqrt(variance.toDouble()).toFloat()

        // 히스토그램
        val histogram = if (binning != null && binning.mode == "custom") {
            val ranges = binning.parseRanges()
            val lastRange = ranges.lastOrNull()
            val counts = mutableMapOf<String, Int>()
            for (range in ranges) {
                counts[range.label] = values.count {
                    if (range === lastRange) range.containsInclusive(it) else range.contains(it)
                }
            }
            counts
        } else {
            // 자동 5등분
            val range = max - min
            if (range <= 0) {
                mapOf(min.toString() to values.size)
            } else {
                // 구간 생성은 단일 소스([NumericBinning])를 쓴다 — 자체 5등분은 정수 범위가
                // 좁은 필드(자녀 수 0~2, 레벨 1~3)에서 라벨이 겹쳐 맵 키가 충돌했고,
                // 앞 구간의 인원이 개수 고지도 없이 사라졌다.
                val counts = linkedMapOf<String, Int>()
                for (bin in NumericBinning.autoBins(values)) {
                    counts[bin.label] = values.count { bin.contains(it) }
                }
                counts
            }
        }

        return NumericSummaryData(min, max, avg, median, stdDev, histogram)
    }

    // ===== 교차 분석 (신규) =====

    /**
     * 고른 필드 id들이 어느 축(캐릭터/사건)에 속하는지 판정한다 (B-4).
     *
     * 축이 섞이면 셀 값이 "캐릭터 수"인지 "사건 수"인지 정할 수 없으므로 계산하지 않고
     * [CrossAxisResolution.Mismatch]로 돌려준다 — 호출부가 사용자에게 알리기 위해서다.
     * 필터 필드도 같은 축이어야 한다(다른 축 필터는 대상 집합을 좁힐 수 없다).
     */
    fun resolveCrossAxis(
        s: StatsSnapshot,
        field1Id: Long,
        field2Id: Long,
        filterFieldId: Long? = null
    ): CrossAxisResolution {
        val charById = s.fieldDefinitions.associateBy { it.id }
        val eventById = s.eventFieldDefinitions.associateBy { it.id }

        val picked = mutableListOf<Pair<CrossAxis, FieldDefinition>>()
        for (id in listOfNotNull(field1Id, field2Id, filterFieldId)) {
            val charFd = charById[id]
            val eventFd = eventById[id]
            when {
                charFd != null -> picked.add(CrossAxis.CHARACTER to charFd)
                eventFd != null -> picked.add(CrossAxis.EVENT to eventFd)
                else -> return CrossAxisResolution.UnknownField
            }
        }
        val axes = picked.map { it.first }.toSet()
        if (axes.size > 1) {
            return CrossAxisResolution.Mismatch(
                characterFieldName = picked.first { it.first == CrossAxis.CHARACTER }.second.name,
                eventFieldName = picked.first { it.first == CrossAxis.EVENT }.second.name
            )
        }
        return CrossAxisResolution.Resolved(axes.firstOrNull() ?: CrossAxis.CHARACTER)
    }

    /**
     * 캐릭터 축 교차분석 — 셀 값은 그 (값1, 값2) 조합을 가진 **캐릭터 수**.
     * 사건 축은 [computeEventCrossAnalysis]가 따로 계산한다(한 표에 섞으면 셀의 의미가 무너진다).
     */
    fun computeCrossAnalysis(
        s: StatsSnapshot,
        field1Id: Long,
        field2Id: Long,
        filterFieldId: Long?,
        filterValue: String?
    ): CrossAnalysisResult? {
        val group1 = crossFieldGroup(s.fieldDefinitions, field1Id) ?: return null
        val group2 = crossFieldGroup(s.fieldDefinitions, field2Id) ?: return null
        val filterGroup = if (filterFieldId != null) {
            crossFieldGroup(s.fieldDefinitions, filterFieldId) ?: return null
        } else null

        val universeIds = mergedUniverseCountOf(group1, group2)

        // 저장된 값 + CALCULATED 계산값. 계산 필드는 저장 행이 없으므로 여기서 합치지 않으면
        // 인사이트 목록에는 보이는 필드가 교차분석에서만 빈 표로 나온다 (S-8).
        // 사건 축(computeEventCrossAnalysis)과 **같은 처리**여야 한다 — 한 축만 고치면
        // 나머지 축에 같은 조용한 실패가 남는다.
        val storedRows = s.fieldValues.groupBy({ it.characterId }, { it.fieldDefinitionId to it.value })
        val rowsByCharacter = mergeCalculatedRows(storedRows, computeAllCalculatedValues(s))

        return buildCrossAnalysis(
            s = s,
            axis = CrossAxis.CHARACTER,
            group1 = group1,
            group2 = group2,
            filterGroup = filterGroup,
            filterValue = filterValue,
            rowsByEntity = rowsByCharacter,
            populationIds = s.characters.map { it.id }.toSet(),
            mergedUniverseCount = universeIds
        )
    }

    /**
     * 사건 축 교차분석 (B-4) — 셀 값은 그 (값1, 값2) 조합을 가진 **사건 수**.
     *
     * 캐릭터 축과 함수를 나눈 이유: `computeCrossAnalysis`의 모수·셀 단위는 캐릭터이고
     * 여기서는 사건이다. 한 표에 섞으면 셀이 무엇의 개수인지 말할 수 없게 된다.
     */
    fun computeEventCrossAnalysis(
        s: StatsSnapshot,
        field1Id: Long,
        field2Id: Long,
        filterFieldId: Long?,
        filterValue: String?
    ): CrossAnalysisResult? {
        val group1 = crossFieldGroup(s.eventFieldDefinitions, field1Id) ?: return null
        val group2 = crossFieldGroup(s.eventFieldDefinitions, field2Id) ?: return null
        val filterGroup = if (filterFieldId != null) {
            crossFieldGroup(s.eventFieldDefinitions, filterFieldId) ?: return null
        } else null

        val universeIds = mergedUniverseCountOf(group1, group2)

        // 저장된 값 + CALCULATED 계산값. 계산 필드는 저장 행이 없으므로 여기서 합치지 않으면
        // 인사이트 목록에는 보이는 필드가 교차분석에서만 빈 표로 나온다.
        val storedRows = s.eventFieldValues.groupBy({ it.eventId }, { it.fieldDefinitionId to it.value })
        val rowsByEvent = mergeCalculatedRows(storedRows, computeAllEventCalculatedValues(s))

        return buildCrossAnalysis(
            s = s,
            axis = CrossAxis.EVENT,
            group1 = group1,
            group2 = group2,
            filterGroup = filterGroup,
            filterValue = filterValue,
            rowsByEntity = rowsByEvent,
            populationIds = s.events.map { it.id }.toSet(),
            mergedUniverseCount = universeIds
        )
    }

    /**
     * 필드 하나가 속한 **(key, type) 그룹**을 돌려준다 (id → 정의).
     *
     * 인사이트 목록이 같은 key·type 필드를 세계관 통합으로 한 장에 보여주고(Pre-Analysis Merge)
     * 그 목록에서 필드를 고르므로, 교차분석이 대표 id 하나만 집계하면 사용자가 본 것보다
     * 조용히 좁은 결과가 나온다. 그래서 같은 범위를 집계한다.
     *
     * **형제는 '통계에 포함' 설정을 따른다**(B-35). 인사이트 카드는 끈 def를 빼고 세는데
     * 여기서만 전부 합치면 같은 필드에 두 화면이 다른 수치를 주고, 화면은 "세계관 N개를
     * 합산했다"고 **사실과 다른 고지**까지 한다. 사용자가 직접 고른 def는 설정과 무관하게
     * 남긴다 — 고를 수 있는데 빈 표가 나오는 조용한 실패를 만들지 않기 위해서다([StatsFieldPolicy]).
     */
    private fun crossFieldGroup(defs: List<FieldDefinition>, fieldId: Long): CrossFieldGroup? {
        val group = StatsFieldPolicy.expandGroup(defs, fieldId)
        if (group.isEmpty()) return null
        return CrossFieldGroup(group.associateBy { it.id })
    }

    /**
     * 교차분석 한 축이 집계하는 필드 묶음. 통계 설정(JSON)을 **묶음당 한 번만** 파싱해 둔다 —
     * 엔티티 루프 안에서 파싱하면 캐릭터/사건 수에 비례해 JSON 파싱이 반복된다.
     */
    private class CrossFieldGroup(val defs: Map<Long, FieldDefinition>) {
        val configs: Map<Long, FieldStatsConfig> =
            defs.mapValues { (_, fd) -> FieldStatsConfig.fromConfig(fd.config) }
        val primary: FieldDefinition get() = defs.values.first()
    }

    /**
     * "세계관 N개의 같은 필드를 합산했습니다"라고 말할 수 있는 N.
     *
     * 두 축의 세계관을 **합집합**으로 세면 거짓이 된다: 축1이 세계관 A에만, 축2가 B에만 있으면
     * 어느 축도 합치지 않았는데 합집합은 2가 되어 "합산했다"고 알린다. 합산은 축 안에서
     * 일어나므로 **축별 세계관 수의 최댓값**이 그 사실이다.
     */
    private fun mergedUniverseCountOf(group1: CrossFieldGroup, group2: CrossFieldGroup): Int =
        maxOf(
            group1.defs.values.map { it.universeId }.distinct().size,
            group2.defs.values.map { it.universeId }.distinct().size
        )

    /** CALCULATED 계산값(엔티티 → 필드 → 값)을 저장된 값 행 목록에 합친다. */
    private fun mergeCalculatedRows(
        stored: Map<Long, List<Pair<Long, String>>>,
        calculated: Map<Long, Map<Long, String>>
    ): Map<Long, List<Pair<Long, String>>> {
        if (calculated.isEmpty()) return stored
        val merged = stored.toMutableMap()
        for ((entityId, byField) in calculated) {
            merged[entityId] = (merged[entityId] ?: emptyList()) + byField.map { it.key to it.value }
        }
        return merged
    }

    /**
     * 교차표 조립 — 축(캐릭터/사건)에 무관한 공통 계산.
     *
     * 셀 값은 해당 (값1, 값2) 조합을 가진 **엔티티 수**다. 엔티티당 중복 값 쌍은 distinct로
     * 1회만 집계한다. 다중값 필드로 한 엔티티가 서로 다른 여러 칸에 기여하는 것은 다중값의
     * 본질이므로 유지하고, multiValue 플래그로 UI가 해석 기준을 고지한다.
     *
     * [populationIds]에는 **축 전체**를 준다. 필드가 속한 세계관으로 좁히면, 작품이 없는(미분류)
     * 캐릭터나 세계관이 지워진(universeId=null) 사건이 값을 가진 채로 표에서 조용히 빠진다 —
     * 둘 다 실제로 존재하는 상태다. 모수가 다소 넓은 것이 값의 누락보다 낫다.
     */
    private fun buildCrossAnalysis(
        s: StatsSnapshot,
        axis: CrossAxis,
        group1: CrossFieldGroup,
        group2: CrossFieldGroup,
        filterGroup: CrossFieldGroup?,
        filterValue: String?,
        rowsByEntity: Map<Long, List<Pair<Long, String>>>,
        populationIds: Set<Long>,
        mergedUniverseCount: Int
    ): CrossAnalysisResult {
        // 필터 적용: 대상 엔티티 ID 세트 구하기
        val targetIds = if (filterGroup != null && filterValue != null) {
            rowsByEntity.filter { (_, rows) -> filterValue in groupValues(s, filterGroup, rows) }.keys
        } else {
            populationIds
        }

        val crossTable = mutableMapOf<String, MutableMap<String, Int>>()
        var filteredCount = 0

        for (entityId in targetIds) {
            val rows = rowsByEntity[entityId] ?: continue
            val values1 = groupValues(s, group1, rows)
            if (values1.isEmpty()) continue
            val values2 = groupValues(s, group2, rows)
            if (values2.isEmpty()) continue

            for (v1 in values1) {
                for (v2 in values2) {
                    crossTable.getOrPut(v1) { mutableMapOf() }
                        .merge(v2, 1) { old, new -> old + new }
                }
            }
            filteredCount++
        }

        val multiValue = group1.defs.values.any { isMultiValueField(it) } ||
            group2.defs.values.any { isMultiValueField(it) }

        return CrossAnalysisResult(
            field1Name = group1.primary.name,
            field2Name = group2.primary.name,
            filterFieldName = filterGroup?.primary?.name,
            filterValue = filterValue,
            crossTable = crossTable,
            totalCount = populationIds.size,
            filteredCount = filteredCount,
            multiValue = multiValue,
            axis = axis,
            mergedUniverseCount = mergedUniverseCount
        )
    }

    /**
     * 한 엔티티의 값 행 중 [group]에 속한 것들을 **기준 def 하나의 설정**으로 통계 키로 변환한다.
     *
     * (종전 계약은 "값마다 그 값을 소유한 def의 설정으로 해석한다"였다 — 아래 이유로 뒤집었다.)
     *
     * 값이 속한 def의 설정으로 각각 파싱하면, 세계관마다 값 라벨·카테고리가 다를 때 같은 저장값이
     * 서로 다른 칸으로 떨어진다 — 인사이트 카드는 그룹 전체를 기준 def로 파싱해 한 칸에 세는데
     * 교차표만 두 칸으로 갈리는 것이다. 카드가 약속한 값 공간이 그 카드에서 뻗는 경로의 값
     * 공간이어야 한다(R-15). 기준 def는 그룹의 첫 원소 = 사용자가 고른 def다.
     */
    private fun groupValues(
        s: StatsSnapshot,
        group: CrossFieldGroup,
        rows: List<Pair<Long, String>>
    ): List<String> {
        val refFd = group.primary
        val refCfg = group.configs.getValue(refFd.id)
        val out = mutableListOf<String>()
        for ((fieldDefId, raw) in rows) {
            if (fieldDefId !in group.defs) continue
            if (raw.isBlank()) continue
            out.addAll(getFieldValues(s, refFd, raw, refCfg))
        }
        return out.distinct()
    }

    /** 한 캐릭터가 여러 값을 가질 수 있는 필드인가 (교차분석 해석 고지용) */
    private fun isMultiValueField(fd: FieldDefinition): Boolean {
        if (fd.type == "BODY_SIZE") return true
        if (StructuredInputConfig.fromConfig(fd.config).enabled) return true
        return FieldValueTokenizer.isMultiToken(fd)
    }

    // ===== 데이터 현황 (신규 - 기존 여러 compute 통합) =====
    fun computeDataOverview(s: StatsSnapshot): DataOverviewStats {
        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.filter { it.type != "CALCULATED" }
            .groupBy { it.universeId }
        val charFieldValuesByChar = s.fieldValues.groupBy { it.characterId }
        val valuesByFieldDef = s.fieldValues.filter { it.value.isNotBlank() }.groupBy { it.fieldDefinitionId }

        // 그룹별 필드 완성도. 미배정 스코프는 novelId 경유가 불가 — 스냅샷에 보존된
        // 정의 전체(CALCULATED 제외)를 그 캐릭터의 필드셋으로 사용한다
        val unassignedFieldSet = if (s.unassignedScope) {
            s.fieldDefinitions.filter { it.type != "CALCULATED" }
        } else emptyList()
        val groupCompletions = mutableMapOf<String, MutableList<Float>>()
        s.characters.forEach { char ->
            val fieldsForUniverse = if (s.unassignedScope) {
                unassignedFieldSet.ifEmpty { return@forEach }
            } else {
                val novelId = char.novelId ?: return@forEach
                val novel = novelMap[novelId] ?: return@forEach
                fieldDefByUniverse[novel.universeId] ?: return@forEach
            }
            val charValues = charFieldValuesByChar[char.id] ?: emptyList()
            val filledDefIds = charValues.filter { it.value.isNotBlank() }.map { it.fieldDefinitionId }.toSet()

            fieldsForUniverse.groupBy { it.groupName }.forEach { (group, fields) ->
                val rate = if (fields.isEmpty()) 0f else fields.count { it.id in filledDefIds }.toFloat() / fields.size * 100f
                groupCompletions.getOrPut(group) { mutableListOf() }.add(rate)
            }
        }
        val completionByGroup = groupCompletions.mapValues { (_, rates) ->
            if (rates.isEmpty()) 0f else rates.average().toFloat()
        }

        // 개별 필드별 완성도 (CALCULATED 필드 제외). 미배정 스코프 모수 = 스코프 캐릭터 전체
        val fieldCompletionDetails = s.fieldDefinitions.filter { it.type != "CALCULATED" }.map { fd ->
            val universeNovels = s.novels.filter { it.universeId == fd.universeId }.map { it.id }.toSet()
            val relevantChars = if (s.unassignedScope) s.characters
                else s.characters.filter { it.novelId in universeNovels }
            val filled = valuesByFieldDef[fd.id]?.count { it.value.isNotBlank() } ?: 0
            val total = relevantChars.size
            val rate = if (total > 0) filled.toFloat() / total * 100f else 0f
            FieldCompletionDetail(fd.name, fd.groupName, filled, total, rate)
        }.sortedBy { it.completionRate }

        // 타임라인 밀도
        val yearDensity = s.events.groupBy { it.year }.mapValues { it.value.size }

        // 이름뱅크
        val used = s.nameBank.count { it.isUsed }
        val nameBankRate = if (s.nameBank.isNotEmpty()) used.toFloat() / s.nameBank.size * 100f else 0f
        val genderDist = s.nameBank.groupBy { it.gender.ifBlank { "미지정" } }
            .mapValues { it.value.size }

        // 건강도
        val noImageCount = s.characters.count { it.imagePaths.isBlank() || it.imagePaths == "[]" }
        val incompleteCount = s.characters.count { char ->
            if (s.unassignedScope) {
                // 미배정 스코프: '미배정 = 무조건 미완성' 판정은 스코프 전원을 미완성으로 만든다 —
                // 보존 정의 대비 실제 채움률로 판정 (정의가 없으면 미완성 아님, 기존 관용구)
                val fields = unassignedFieldSet
                if (fields.isEmpty()) return@count false
                val charValues = charFieldValuesByChar[char.id] ?: emptyList()
                val filled = charValues.count { it.value.isNotBlank() }
                return@count filled.toFloat() / fields.size < 0.5f
            }
            val novelId = char.novelId
            if (novelId == null) return@count true // 작품 미배정 = 미완성으로 간주 (전체 스코프)
            val novel = novelMap[novelId] ?: return@count true
            val fields = fieldDefByUniverse[novel.universeId] ?: return@count false
            if (fields.isEmpty()) return@count false
            val charValues = charFieldValuesByChar[char.id] ?: emptyList()
            val filled = charValues.count { it.value.isNotBlank() }
            filled.toFloat() / fields.size < 0.5f
        }
        val relCharIds = s.relationships.flatMap { listOf(it.characterId1, it.characterId2) }.toSet()
        val isolatedCount = s.characters.count { it.id !in relCharIds }
        val eventCharIds = s.crossRefs.map { it.characterId }.toSet()
        val unlinkedCount = s.characters.count { it.id !in eventCharIds }

        return DataOverviewStats(
            totalCharacters = s.characters.size,
            totalNovels = s.novels.size,
            totalUniverses = s.universes.size,
            totalEvents = s.events.size,
            totalRelationships = s.relationships.size,
            totalNames = s.nameBank.size,
            fieldCompletionByGroup = completionByGroup,
            fieldCompletionByField = fieldCompletionDetails,
            yearDensity = yearDensity,
            nameBankUsageRate = nameBankRate,
            nameBankGenderDist = genderDist,
            healthWarnings = HealthWarnings(noImageCount, incompleteCount, isolatedCount, unlinkedCount)
        )
    }

    // ===== 커스텀 필드 분석 (레거시) =====
    fun computeFieldAnalysis(s: StatsSnapshot): FieldAnalysisStats {
        val statsCache = StatsFieldPolicy.ConfigCache()

        // 저장 값 + CALCULATED 계산값. 수식 필드는 저장 행이 없어 이 화면에서만 통째로
        // 빠져 있었다 — 인사이트·순위에는 나오는 필드가 여기서만 사라지는 상태였다(S-15, R-16).
        val valuesByFieldDef = augmentedCharacterValues(s)

        // 분포·요약의 대상은 '통계에 포함'된 필드다. 이 화면은 사용자가 필드를 고르는 곳이
        // 아니라 앱이 전부 나열하는 곳이므로 설정을 따른다(S-15, [StatsFieldPolicy]).
        val analyzableFields = statsCache.analyzable(s.fieldDefinitions)

        val fieldValueDists = mutableListOf<FieldValueDistribution>()

        // ── 이산 값 분포: 값 자체가 분포 키이므로 드릴다운도 값 일치 ──
        for (fd in analyzableFields.filter { it.type in DISCRETE_DISTRIBUTION_TYPES }) {
            val values = valuesByFieldDef[fd.id] ?: continue
            val statsConfig = statsCache.of(fd)
            val dist = ValueDistributions.of(
                values.filter { it.value.isNotBlank() }
                    .flatMap { fv -> getFieldValues(s, fd, fv.value, statsConfig) }
            )
            if (dist.isEmpty()) continue
            fieldValueDists.add(
                FieldValueDistribution(
                    fd.id, fd.name, fd.type, fd.groupName, dist,
                    matchSpecs = dist.keys.associateWith { FieldValueMatchSpec.Values(it) }
                )
            )
        }

        // ── 수치형(NUMBER·CALCULATED) 사용자 구간 분포 ──
        // getFieldValues가 구간 라벨을 돌려주므로 라벨이 곧 파싱 값이다(드릴다운은 값 일치).
        // 자동 구간(binning=auto)의 분포는 여기서 만들지 않는다 — 별건 백로그(개-5)다.
        for (fd in analyzableFields.filter { it.type in BINNABLE_TYPES }) {
            val statsConfig = statsCache.of(fd)
            if (statsConfig.binning?.mode != "custom") continue
            val values = valuesByFieldDef[fd.id] ?: continue
            val dist = ValueDistributions.of(
                values.filter { it.value.isNotBlank() }
                    .flatMap { fv -> getFieldValues(s, fd, fv.value, statsConfig) }
            )
            if (dist.isEmpty()) continue
            fieldValueDists.add(
                FieldValueDistribution(
                    fd.id, fd.name, fd.type, fd.groupName, dist,
                    matchSpecs = dist.keys.associateWith { FieldValueMatchSpec.Values(it) }
                )
            )
        }

        // ── BODY_SIZE: 파트별 자동 구간 분포 ──
        // 라벨("160~170")은 **계산 결과**라 저장값과 절대 같지 않다. 종전에는 그 라벨을
        // 드릴다운 매칭 키로 그대로 넘겨 어떤 입력에서도 0명이 나왔다(S-16). 이제 구간을
        // 만든 규칙 자체를 스펙으로 실어 보내고, 구간 생성은 [NumericBinning]이 단일 소스다.
        for (fd in analyzableFields.filter { it.type == "BODY_SIZE" }) {
            val rawValues = valuesByFieldDef[fd.id] ?: continue
            val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
            val separator = if (structuredConfig.enabled) structuredConfig.separator else "-"
            val partCount = bodySizePartCount(structuredConfig, rawValues.firstOrNull()?.value, separator)

            for (partIdx in 0 until partCount) {
                val partLabel = bodySizePartLabel(structuredConfig, partIdx)
                val numericValues = rawValues.mapNotNull {
                    NumericBinning.partValue(it.value, separator, partIdx)
                }
                val bins = NumericBinning.autoBins(numericValues)
                if (bins.isEmpty()) continue

                val dist = linkedMapOf<String, Int>()
                val specs = linkedMapOf<String, FieldValueMatchSpec>()
                for (bin in bins) {
                    dist[bin.label] = numericValues.count { bin.contains(it) }
                    specs[bin.label] = FieldValueMatchSpec.of(bin, partIdx, separator)
                }
                fieldValueDists.add(
                    FieldValueDistribution(
                        fd.id, "${fd.name} — $partLabel", fd.type, fd.groupName, dist,
                        matchSpecs = specs,
                        orderedByValue = true
                    )
                )
            }
        }

        // ── 수치 요약 ──
        val numberSummaries = mutableListOf<NumberFieldSummary>()

        // NUMBER와 CALCULATED는 같은 수치다 — 수식 필드라고 요약에서 빠질 이유가 없다(S-15).
        for (fd in analyzableFields.filter { it.type in BINNABLE_TYPES }) {
            val values = valuesByFieldDef[fd.id]
                ?.mapNotNull { it.value.toFloatOrNull() }
                ?: continue
            if (values.isEmpty()) continue
            numberSummaries.add(numberSummary(fd.name, values))
        }

        // BODY_SIZE 타입: 파트별 수치 요약 (min/max/avg/median)
        for (fd in analyzableFields.filter { it.type == "BODY_SIZE" }) {
            val rawValues = valuesByFieldDef[fd.id] ?: continue
            val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
            val separator = if (structuredConfig.enabled) structuredConfig.separator else "-"
            val partCount = bodySizePartCount(structuredConfig, rawValues.firstOrNull()?.value, separator)

            for (partIdx in 0 until partCount) {
                val partLabel = bodySizePartLabel(structuredConfig, partIdx)
                val numericValues = rawValues.mapNotNull {
                    NumericBinning.partValue(it.value, separator, partIdx)
                }
                if (numericValues.isEmpty()) continue
                numberSummaries.add(numberSummary("${fd.name} — $partLabel", numericValues))
            }
        }

        // 개별 필드별 완성도 (CALCULATED 필드 제외 — 자동 계산 필드는 항상 100%이므로 의미 없음)
        // '통계에 포함'은 여기에 적용하지 않는다: 완성도는 '분석'이 아니라 '입력 현황'이다.
        // 메모성 필드를 분석에서 뺀 사용자가 그 필드의 입력 누락까지 안 보이길 원한다고
        // 볼 근거가 없다(예외에는 이유를 적는다 — R-16).
        val fieldCompletionDetails = s.fieldDefinitions
            .filter { it.type != "CALCULATED" }
            .map { fd ->
                // 이 필드가 속한 유니버스의 캐릭터들. 미배정 스코프 모수 = 스코프 캐릭터 전체
                val universeNovels = s.novels.filter { it.universeId == fd.universeId }.map { it.id }.toSet()
                val relevantChars = if (s.unassignedScope) s.characters
                    else s.characters.filter { it.novelId in universeNovels }
                val filled = valuesByFieldDef[fd.id]?.count { it.value.isNotBlank() } ?: 0
                val total = relevantChars.size
                val rate = if (total > 0) filled.toFloat() / total * 100f else 0f
                FieldCompletionDetail(fd.name, fd.groupName, filled, total, rate)
            }.sortedBy { it.completionRate }

        // 필드별 상태변화 수
        val stateChangesByField = s.stateChanges
            .filter { !it.fieldKey.startsWith("__") } // 특수 키 제외
            .groupBy { it.fieldKey }
            .mapValues { it.value.size }

        return FieldAnalysisStats(
            fieldValueDistributions = fieldValueDists,
            numberFieldSummaries = numberSummaries,
            fieldCompletionByField = fieldCompletionDetails,
            stateChangesByField = stateChangesByField
        )
    }

    /** 구조화 입력(BODY_SIZE)의 파트 수 — 분포·요약이 같은 규칙을 쓰도록 한 곳에 둔다. */
    private fun bodySizePartCount(
        config: StructuredInputConfig,
        sampleValue: String?,
        separator: String
    ): Int = if (config.enabled && config.parts.isNotEmpty()) {
        config.parts.size
    } else {
        sampleValue?.split(separator)?.size ?: 1
    }

    /** 구조화 입력 파트 라벨 — 설정이 없으면 '파트N'. */
    private fun bodySizePartLabel(config: StructuredInputConfig, partIdx: Int): String =
        if (config.enabled && partIdx < config.parts.size) config.parts[partIdx].label
        else "파트${partIdx + 1}"

    /** 수치 요약 조립 — min/max/avg/median 계산이 세 곳에 흩어지지 않게 한다. */
    private fun numberSummary(fieldName: String, values: List<Float>): NumberFieldSummary {
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else sorted[sorted.size / 2]
        return NumberFieldSummary(
            fieldName = fieldName,
            min = sorted.first(),
            max = sorted.last(),
            avg = values.average().toFloat(),
            median = median,
            count = values.size,
            values = sorted
        )
    }

    /**
     * 작품별 비교 분석 (원칙 05: 데이터 유기적 연결)
     * 전체 스냅샷(필터 미적용)에서 작품별 통계를 나란히 비교할 수 있도록 한다.
     */
    fun computeCrossNovelComparison(s: StatsSnapshot): CrossNovelComparison {
        val charsByNovel = s.characters.groupBy { it.novelId }
        val fieldDefMap = s.fieldDefinitions.associateBy { it.id }
        val statsCache = StatsFieldPolicy.ConfigCache()
        // 캐릭터 → "필드명:값" 목록. 저장 값 + 계산값(R-16)을 통계 파싱 규칙으로 **한 번만** 풀어 두고,
        // 작품별 집계는 이 버킷을 나눠 쓴다.
        val parsedValuesByCharacter = HashMap<Long, MutableList<String>>()
        for ((fieldDefId, values) in augmentedCharacterValues(s)) {
            val fd = fieldDefMap[fieldDefId] ?: continue
            if (!statsCache.isAnalyzable(fd)) continue
            val cfg = statsCache.of(fd)
            for (fv in values) {
                if (fv.value.isBlank()) continue
                val keys = getFieldValues(s, fd, fv.value, cfg)
                if (keys.isEmpty()) continue
                parsedValuesByCharacter.getOrPut(fv.characterId) { mutableListOf() }
                    .addAll(keys.map { "${fd.name}:$it" })
            }
        }
        // 크로스레프 기반 사건-작품 매핑
        val eventIdsByNovel = s.eventNovelCrossRefs.groupBy({ it.novelId }, { it.eventId })
        val eventById = s.events.associateBy { it.id }

        // 전체 복잡도를 한 번만 계산하고 캐릭터 ID로 매핑
        val allComplexities = computeCharacterComplexities(s)
        val complexityById = allComplexities.mapIndexed { i, c -> s.characters[i].id to c }.toMap()

        val entries = s.novels.map { novel ->
            val chars = charsByNovel[novel.id] ?: emptyList()
            val charIds = chars.map { it.id }.toSet()
            val events = (eventIdsByNovel[novel.id] ?: emptyList()).mapNotNull { eventById[it] }

            // 이 작품 캐릭터의 관계 수
            val relCount = s.relationships.count { it.characterId1 in charIds || it.characterId2 in charIds }

            // 복잡도 계산 — 전체 복잡도에서 이 작품 캐릭터만 필터
            val complexities = chars.mapNotNull { complexityById[it.id] }
            val avgComplexity = if (complexities.isNotEmpty()) {
                complexities.map { it.totalScore }.average().toFloat()
            } else 0f

            // 특화 유형 분포
            val specDist = complexities
                .filter { it.specialization != CharacterComplexity.Specialization.NONE }
                .groupBy { "${it.specialization.icon} ${it.specialization.label}" }
                .mapValues { it.value.size }

            // 자주 쓰인 필드 값 TOP 5 — 요약 화면의 TOP 5와 **같은 규칙**으로 센다(저장 값 + 계산값,
            // 통계 파싱, '통계에 포함' 필터). 파싱 결과는 작품 루프 **밖에서 한 번만** 만든다:
            // 작품마다 전체 값 테이블을 다시 훑고 다시 파싱하면 작품 수에 비례해 같은 일을 반복한다.
            val topValues = charIds
                .flatMap { charId -> parsedValuesByCharacter[charId].orEmpty() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(5)
                .map { it.key to it.value }

            NovelComparisonEntry(
                novelId = novel.id,
                novelTitle = novel.title,
                characterCount = chars.size,
                eventCount = events.size,
                relationshipCount = relCount,
                avgComplexity = avgComplexity,
                specializationDist = specDist,
                topFieldValues = topValues
            )
        }.sortedByDescending { it.characterCount }

        return CrossNovelComparison(novels = entries)
    }

    // ===== 세력 통계 =====

    fun computeFactionStats(s: StatsSnapshot): FactionStatsResult {
        val activeMemberships = s.factionMemberships.filter { it.leaveType != FactionMembership.LEAVE_REMOVED }
        val factionMap = s.factions.associateBy { it.id }

        // 세력별 활성 멤버 수
        val factionMemberCounts = activeMemberships
            .filter { it.leaveType == null } // 현재 활성 중인 멤버만
            .groupBy { it.factionId }
            .mapNotNull { (factionId, members) ->
                val factionName = factionMap[factionId]?.name ?: return@mapNotNull null
                factionName to members.size
            }
            .toMap()

        // 2개 이상 세력에 속한 캐릭터 수
        val membershipsByChar = activeMemberships
            .filter { it.leaveType == null }
            .groupBy { it.characterId }
        val multiMemberCharacters = membershipsByChar.count { it.value.size >= 2 }

        // 세력 자동 관계 수 (factionId != null)
        val autoRelationshipCount = s.relationships.count { it.factionId != null }

        // 설정상 탈퇴 수
        val departureCount = s.factionMemberships.count { it.leaveType == FactionMembership.LEAVE_DEPARTED }

        // 세력 미소속 캐릭터 수
        val charsInFactions = activeMemberships
            .filter { it.leaveType == null }
            .map { it.characterId }.toSet()
        val factionlessCharacterCount = s.characters.count { it.id !in charsInFactions }

        return FactionStatsResult(
            totalFactions = s.factions.size,
            factionMemberCounts = factionMemberCounts,
            multiMemberCharacters = multiMemberCharacters,
            autoRelationshipCount = autoRelationshipCount,
            departureCount = departureCount,
            factionlessCharacterCount = factionlessCharacterCount
        )
    }

    // ===== 개선 3: 패턴 감지 & 서사적 인사이트 =====

    fun detectPatterns(s: StatsSnapshot, enabledTypes: Set<PatternType> = PatternType.values().toSet()): List<PatternInsight> {
        val insights = mutableListOf<PatternInsight>()

        // 필드값을 fieldDefinitionId로 **한 번만** 그룹화 — 필드 그룹마다 전체 테이블을 재필터하던
        // O(C·F²)를 O(C·F)로(받쳐주는 확장성). 아래 모든 그룹·작품별 비교가 이 버킷을 재사용한다.
        // 저장 행이 없는 CALCULATED 계산값도 함께 싣는다 — 인사이트 카드에는 분포가 그려지는
        // 수식 필드가 패턴 감지에서만 통째로 빠지면 같은 데이터에 두 화면이 다른 답을 준다(R-16).
        val valuesByDefId: Map<Long, List<CharacterFieldValue>> = augmentedCharacterValues(s)
        // '통계에 포함'을 끈 필드는 스스로 나타나지 않는다 — 패턴 감지는 사용자가 필드를 고르는
        // 경로가 아니라 앱이 스스로 고르는 경로이므로 설정을 따른다(S-14, [StatsFieldPolicy]).
        val statsCache = StatsFieldPolicy.ConfigCache()
        // 작품별 비교에서 필드가 속한 세계관의 대표 작품 조회 — fd마다 novels/universes를 중첩 탐색하던 것 제거.
        val validUniverseIds = s.universes.mapTo(HashSet()) { it.id }
        val firstNovelByUniverse: Map<Long, com.novelcharacter.app.data.model.Novel> =
            s.novels.filter { it.universeId != null }.groupBy { it.universeId!! }.mapValues { it.value.first() }

        // 필드별 분포 패턴 감지
        val fieldsByKey = statsCache.analyzable(s.fieldDefinitions).groupBy { Pair(it.key, it.type) }

        for ((keyType, fieldDefs) in fieldsByKey) {
            // 동일 키의 모든 세계관 필드 값 합산 (사전 그룹 버킷 재사용)
            val rawValues = fieldDefs.flatMap { valuesByDefId[it.id].orEmpty() }
            if (rawValues.isEmpty()) continue

            val fd = fieldDefs.first()
            val statsConfig = statsCache.of(fd)
            val allValues = rawValues.flatMap { getFieldValues(s, fd, it.value, statsConfig) }
            if (allValues.isEmpty()) continue

            // 집계·정렬 규칙은 인사이트 분포와 같은 단일 소스를 쓴다 — 동수일 때 두 화면이
            // 서로 다른 값을 '최다'로 지목하지 않게 한다.
            val dist = ValueDistributions.of(allValues)
            val total = allValues.size
            val fieldName = fd.name
            // 게이트용 '모집단'은 값 개수(total)가 아니라 이 필드에 값을 가진 실제 캐릭터 수(다값 필드 보정).
            val peopleCount = rawValues.map { it.characterId }.distinct().size

            // 패턴 1: 편중 (단일 값 60%+)
            // 이미 (건수 내림차순, 값 이름 오름차순)으로 정렬돼 있다 — maxByOrNull은 동수에서
            // 첫 등장 순서를 따라 인사이트 차트의 1위와 다른 값을 지목할 수 있다.
            val topEntry = dist.entries.firstOrNull()
            if (PatternType.DOMINANCE in enabledTypes && topEntry != null) {
                val topPct = topEntry.value * 100f / total
                if (topPct >= 60f) {
                    insights.add(PatternInsight(
                        type = PatternType.DOMINANCE,
                        severity = if (topPct >= 80f) PatternSeverity.HIGH else PatternSeverity.MEDIUM,
                        title = "${fieldName}: '${topEntry.key}' 편중",
                        description = "${fieldName} 분포에서 '${topEntry.key}'이(가) ${String.format("%.0f", topPct)}%를 차지하여 편중되어 있습니다.",
                        suggestion = "다양성을 위해 다른 ${fieldName} 값을 가진 캐릭터 추가를 고려해보세요.",
                        fieldDefId = fd.id,
                        fieldKey = fd.key,
                        fieldType = fd.type,
                        // 기준 def(fd=first)를 맨 앞에 둔다 — 드릴다운도 이 def의 config로 파싱해 %와 일치.
                        mergedFieldDefIds = fieldDefs.map { it.id },
                        // 편중된 그 값(최빈)을 가진 캐릭터를 그대로 펼친다 — "누가 이 편중을 이루나"가 직관적.
                        drilldownValues = listOf(topEntry.key),
                        drilldownExclude = false,
                        population = peopleCount
                    ))
                }
            }

            // 패턴 2: 균형 (모든 값 10~30% 사이)
            if (PatternType.BALANCE in enabledTypes && dist.size >= 3) {
                val pcts = dist.values.map { it * 100f / total }
                val allBalanced = pcts.all { it in 10f..35f }
                if (allBalanced) {
                    insights.add(PatternInsight(
                        type = PatternType.BALANCE,
                        severity = PatternSeverity.LOW,
                        title = "${fieldName}: 균형 양호",
                        description = "${fieldName}의 값이 ${dist.size}개 범주에 고르게 분포되어 있습니다.",
                        suggestion = "",
                        fieldDefId = fd.id,
                        // 드릴다운은 없지만 최소 모집단 게이트가 적용되도록 필드 식별/모집단은 채운다.
                        fieldKey = fd.key,
                        fieldType = fd.type,
                        mergedFieldDefIds = fieldDefs.map { it.id },
                        population = peopleCount
                    ))
                }
            }

            // 패턴 3: 이상치 (1개 값만 가진 희소 항목이 전체의 2% 미만이고, 나머지는 밀집)
            if (PatternType.OUTLIER in enabledTypes && total >= 10) {
                val singletons = dist.entries.filter { it.value == 1 }
                val singletonPct = singletons.size * 100f / total
                if (singletons.isNotEmpty() && singletonPct <= 5f && dist.size > 3) {
                    val outlierNames = singletons.take(3).joinToString(", ") { "'${it.key}'" }
                    insights.add(PatternInsight(
                        type = PatternType.OUTLIER,
                        severity = PatternSeverity.LOW,
                        title = "${fieldName}: 희소 값 발견",
                        description = "${fieldName}에서 $outlierNames 등이 각 1명에게만 해당됩니다.",
                        suggestion = "의도적인 개성 부여인지, 오입력인지 확인해보세요.",
                        fieldDefId = fd.id,
                        fieldKey = fd.key,
                        fieldType = fd.type,
                        mergedFieldDefIds = fieldDefs.map { it.id },
                        // 희소 값(각 1명)을 가진 캐릭터 전부를 펼친다.
                        drilldownValues = singletons.map { it.key },
                        drilldownExclude = false,
                        population = peopleCount
                    ))
                }
            }
        }

        // 패턴 4: 사건 연도 집중 (특정 10년에 50%+ 집중)
        val clusterOrAbsence = PatternType.CLUSTER in enabledTypes || PatternType.ABSENCE in enabledTypes
        if (clusterOrAbsence && s.events.size >= 5) {
            val byDecade = s.events.groupBy { (it.year / 10) * 10 }
            val totalEvents = s.events.size
            val topDecade = byDecade.maxByOrNull { it.value.size }
            if (PatternType.CLUSTER in enabledTypes && topDecade != null) {
                val pct = topDecade.value.size * 100f / totalEvents
                if (pct >= 50f) {
                    insights.add(PatternInsight(
                        type = PatternType.CLUSTER,
                        severity = PatternSeverity.MEDIUM,
                        title = "사건 연대 집중",
                        description = "전체 사건의 ${String.format("%.0f", pct)}%가 ${topDecade.key}~${topDecade.key + 9}년에 집중되어 있습니다.",
                        suggestion = "서사적 밀도가 높은 시기입니다. 다른 시기에도 사건을 분산시킬지 검토해보세요."
                    ))
                }
            }

            // 공백 구간 (100년 이상 사건 없는 구간)
            val years = s.events.map { it.year }.sorted()
            if (PatternType.ABSENCE in enabledTypes && years.size >= 2) {
                val gaps = years.zipWithNext().filter { it.second - it.first > 100 }
                for (gap in gaps.take(2)) {
                    insights.add(PatternInsight(
                        type = PatternType.ABSENCE,
                        severity = PatternSeverity.LOW,
                        title = "서사 공백 구간",
                        description = "${gap.first}년~${gap.second}년 사이에 사건이 없습니다 (${gap.second - gap.first}년 간격).",
                        suggestion = "의도적 공백기인지, 추가할 사건이 있는지 검토해보세요."
                    ))
                }
            }
        }

        // 패턴 5: 작품 간 비교 (원칙05 유기적 연결)
        if (PatternType.CROSS_NOVEL in enabledTypes && s.novels.size >= 2) {
            val charByNovel = s.characters.groupBy { it.novelId }
            val novelSizes = charByNovel.mapNotNull { (nid, chars) ->
                val novel = s.novels.find { it.id == nid } ?: return@mapNotNull null
                Triple(novel.title, chars.size, nid)
            }.sortedByDescending { it.second }

            if (novelSizes.size >= 2) {
                val largest = novelSizes.first()
                val smallest = novelSizes.last()
                if (largest.second > 0 && smallest.second > 0) {
                    val ratio = largest.second.toFloat() / smallest.second
                    if (ratio >= 3f) {
                        insights.add(PatternInsight(
                            type = PatternType.CROSS_NOVEL,
                            severity = PatternSeverity.MEDIUM,
                            title = "작품 간 캐릭터 수 불균형",
                            description = "'${largest.first}'(${largest.second}명)과 '${smallest.first}'(${smallest.second}명) 사이에 ${String.format("%.1f", ratio)}배 차이가 있습니다.",
                            suggestion = "작품별 서사 규모 차이가 의도적인지 확인해보세요."
                        ))
                    }
                }
            }

            // 작품 간 필드 편중 비교
            for ((keyType, fieldDefs) in fieldsByKey) {
                if (fieldDefs.size < 2) continue
                val novelPatterns = mutableListOf<Pair<String, String>>() // (작품명, 주요값)
                for (fd in fieldDefs) {
                    val novel = fd.universeId.takeIf { it in validUniverseIds }
                        ?.let { firstNovelByUniverse[it] } ?: continue
                    val fvs = valuesByDefId[fd.id].orEmpty()
                    val statsConfig = statsCache.of(fd)
                    val values = fvs.flatMap { getFieldValues(s, fd, it.value, statsConfig) }
                    val topVal = ValueDistributions.of(values).entries.firstOrNull()
                    if (topVal != null && values.isNotEmpty()) {
                        val pct = topVal.value * 100f / values.size
                        if (pct >= 50f) {
                            novelPatterns.add(Pair(novel.title, "${topVal.key}(${String.format("%.0f", pct)}%)"))
                        }
                    }
                }
                if (novelPatterns.size >= 2) {
                    val desc = novelPatterns.joinToString(", ") { "${it.first}: ${it.second}" }
                    insights.add(PatternInsight(
                        type = PatternType.CROSS_NOVEL,
                        severity = PatternSeverity.LOW,
                        title = "${fieldDefs.first().name}: 작품별 편중 경향",
                        description = "$desc — 전체적으로 ${fieldDefs.first().name} 편중 경향이 보입니다.",
                        suggestion = "작품별 다양성 확보를 고려해보세요."
                    ))
                }
            }
        }

        // 패턴: 세력 관련
        if (s.factions.isNotEmpty()) {
            val activeMemberships = s.factionMemberships.filter { it.leaveType == null }
            val factionMemberCounts = activeMemberships.groupBy { it.factionId }.mapValues { it.value.size }

            // 세력이 존재하지만 멤버가 0명인 경우
            val emptyFactions = s.factions.filter { (factionMemberCounts[it.id] ?: 0) == 0 }
            if (PatternType.ABSENCE in enabledTypes && emptyFactions.isNotEmpty()) {
                insights.add(PatternInsight(
                    type = PatternType.ABSENCE,
                    severity = PatternSeverity.MEDIUM,
                    title = "멤버 없는 세력 발견",
                    description = "${emptyFactions.joinToString(", ") { "'${it.name}'" }} 세력에 활성 멤버가 없습니다.",
                    suggestion = "캐릭터를 세력에 배정하거나, 불필요한 세력을 정리해보세요."
                ))
            }

            // 모든 캐릭터가 동일한 단일 세력에 속한 경우
            if (PatternType.DOMINANCE in enabledTypes && s.characters.isNotEmpty()) {
                val charsInFactions = activeMemberships.map { it.characterId }.toSet()
                if (charsInFactions.size == s.characters.size && s.factions.size >= 1) {
                    val factionIds = activeMemberships.map { it.factionId }.distinct()
                    if (factionIds.size == 1) {
                        val factionName = s.factions.find { it.id == factionIds.first() }?.name ?: "?"
                        insights.add(PatternInsight(
                            type = PatternType.DOMINANCE,
                            severity = PatternSeverity.MEDIUM,
                            title = "세력 편중: 모든 캐릭터가 동일 세력",
                            description = "모든 캐릭터가 '${factionName}' 단일 세력에 소속되어 있습니다.",
                            suggestion = "대립 구조나 다양성을 위해 다른 세력을 추가하는 것을 고려해보세요."
                        ))
                    }
                }
            }
        }

        // severity 기준 정렬 (HIGH → MEDIUM → LOW)
        return insights.sortedBy { it.severity.ordinal }
    }

    // ===== 개선 6: 차트 탭 → 캐릭터 목록 =====

    /**
     * 특정 필드의 특정 값을 가진 캐릭터 목록 반환.
     * getFieldValues() 로직을 재활용하여 파싱된 값 기준으로 매칭.
     * CALCULATED 필드의 경우 FormulaEvaluator로 실시간 계산한 값으로 매칭.
     */
    /**
     * 차트 조각(값 하나)을 가진 캐릭터 목록 — **차트가 합산한 (key,type) 그룹 전체**를 조회한다.
     *
     * [fieldDefIds]에는 [FieldInsightResult.mergedFieldDefIds]를 그대로 준다. 대표 id 하나만
     * 주면 전체 세계관 보기에서 차트는 A+B 합산인데 목록은 A만 나오는 조용한 과소집계가 된다(S-7).
     * 파싱은 **첫 원소(기준 def)의 config**로 통일한다 — 차트도 그룹 전체를 기준 def로 파싱해
     * 분포를 냈으므로, 다르게 파싱하면 조각 수치와 인원이 어긋난다.
     *
     * @return 대상 필드 정의를 하나도 찾지 못하면 **null** — 빈 목록으로 위장하지 않는다.
     *   호출부가 "필드를 찾을 수 없음"을 사용자에게 고지한다(변수 제어: 검증→알림).
     */
    fun getCharactersByFieldValue(
        s: StatsSnapshot,
        fieldDefIds: List<Long>,
        targetValue: String
    ): List<FieldValueCharacter>? =
        getCharactersByFieldValue(s, fieldDefIds, FieldValueMatchSpec.Values(targetValue))

    /**
     * 매치 스펙판 (S-16·S-17). 라벨이 곧 값인 조각은 [FieldValueMatchSpec.Values],
     * 구간 라벨은 [FieldValueMatchSpec.NumericPartRange], 접힌 '기타' 묶음은 값 여러 개를
     * 담은 [FieldValueMatchSpec.Values]다 — 화면이 보여준 그 조각의 규칙을 그대로 받는다.
     */
    fun getCharactersByFieldValue(
        s: StatsSnapshot,
        fieldDefIds: List<Long>,
        spec: FieldValueMatchSpec
    ): List<FieldValueCharacter>? {
        if (fieldDefIds.isEmpty()) return null
        val idSet = fieldDefIds.toSet()
        val defById = s.fieldDefinitions.filter { it.id in idSet }.associateBy { it.id }
        if (defById.isEmpty()) return null

        // 기준 def = 차트가 파싱에 쓴 그 def(그룹의 primary). 값 공간을 차트와 일치시킨다.
        val refDef = defById[fieldDefIds.first()] ?: defById.values.first()
        val refCfg = FieldStatsConfig.fromConfig(refDef.config)
        val charMap = s.characters.associateBy { it.id }

        // 캐릭터 한 명이 형제 def·다중값으로 여러 번 매칭돼도 목록에는 한 번만 — 차트 조각은
        // 엔티티 수를 세므로 목록도 같은 단위여야 한다.
        val result = LinkedHashMap<Long, FieldValueCharacter>()
        fun record(charId: Long, shownValue: String) {
            if (result.containsKey(charId)) return
            val char = charMap[charId] ?: return
            val images = try {
                com.google.gson.Gson().fromJson(char.imagePaths, Array<String>::class.java)?.toList() ?: emptyList()
            } catch (_: Exception) { emptyList() }
            result[charId] = FieldValueCharacter(
                characterId = char.id,
                characterName = char.name,
                fieldValue = shownValue,
                imageUri = images.firstOrNull()
            )
        }

        // 저장 값을 가진 def (CALCULATED는 저장 행이 없다)
        if (defById.values.any { it.type != "CALCULATED" }) {
            for (fv in s.fieldValues) {
                if (fv.fieldDefinitionId !in idSet) continue
                if (defById[fv.fieldDefinitionId]?.type == "CALCULATED") continue
                if (FieldValueMatcher.matches(spec, fv.value) { getFieldValues(s, refDef, fv.value, refCfg) }) {
                    record(fv.characterId, fv.value)
                }
            }
        }

        // CALCULATED def: FormulaEvaluator 계산값으로 매칭
        val calcDefIds = defById.values.filter { it.type == "CALCULATED" }.map { it.id }
        if (calcDefIds.isNotEmpty()) {
            val calculatedValues = computeAllCalculatedValues(s)
            for ((charId, fieldMap) in calculatedValues) {
                for (defId in calcDefIds) {
                    val computedValue = fieldMap[defId] ?: continue
                    if (FieldValueMatcher.matches(spec, computedValue) {
                            getFieldValues(s, refDef, computedValue, refCfg)
                        }) {
                        record(charId, computedValue)
                    }
                }
            }
        }
        return result.values.sortedBy { it.characterName }
    }

    /**
     * 차트 조각(값 하나)을 가진 **사건** 목록 — [getCharactersByFieldValue]의 사건판 (S-9).
     *
     * 사건 필드 인사이트 카드도 캐릭터 카드와 같은 목록에 그려지고 같은 탭 인터랙션을 갖는데,
     * 조회 경로가 캐릭터 전용이라 사건 조각을 탭하면 항상 0명짜리 빈 시트가 떴다.
     * 캐릭터 축과 **대칭으로** 구현한다 — 리스너만 떼는 간소화는 원칙 03 위반이다.
     */
    fun getEventsByFieldValue(
        s: StatsSnapshot,
        fieldDefIds: List<Long>,
        targetValue: String
    ): List<FieldValueEvent>? =
        getEventsByFieldValue(s, fieldDefIds, FieldValueMatchSpec.Values(targetValue))

    /** 매치 스펙판 — 캐릭터 축([getCharactersByFieldValue])과 **대칭**이다(R-16의 짝 규칙). */
    fun getEventsByFieldValue(
        s: StatsSnapshot,
        fieldDefIds: List<Long>,
        spec: FieldValueMatchSpec
    ): List<FieldValueEvent>? {
        if (fieldDefIds.isEmpty()) return null
        val idSet = fieldDefIds.toSet()
        val defById = s.eventFieldDefinitions.filter { it.id in idSet }.associateBy { it.id }
        if (defById.isEmpty()) return null

        val refDef = defById[fieldDefIds.first()] ?: defById.values.first()
        val refCfg = FieldStatsConfig.fromConfig(refDef.config)
        val eventMap = s.events.associateBy { it.id }

        val result = LinkedHashMap<Long, FieldValueEvent>()
        fun record(eventId: Long, shownValue: String) {
            if (result.containsKey(eventId)) return
            val event = eventMap[eventId] ?: return
            result[eventId] = FieldValueEvent(
                eventId = event.id,
                description = event.description,
                formattedDate = event.getFormattedDate(),
                year = event.year,
                fieldValue = shownValue
            )
        }

        if (defById.values.any { it.type != "CALCULATED" }) {
            for (fv in s.eventFieldValues) {
                if (fv.fieldDefinitionId !in idSet) continue
                if (defById[fv.fieldDefinitionId]?.type == "CALCULATED") continue
                if (FieldValueMatcher.matches(spec, fv.value) { getFieldValues(s, refDef, fv.value, refCfg) }) {
                    record(fv.eventId, fv.value)
                }
            }
        }

        val calcDefIds = defById.values.filter { it.type == "CALCULATED" }.map { it.id }
        if (calcDefIds.isNotEmpty()) {
            val calculatedValues = computeAllEventCalculatedValues(s)
            for ((eventId, fieldMap) in calculatedValues) {
                for (defId in calcDefIds) {
                    val computedValue = fieldMap[defId] ?: continue
                    if (FieldValueMatcher.matches(spec, computedValue) {
                            getFieldValues(s, refDef, computedValue, refCfg)
                        }) {
                        record(eventId, computedValue)
                    }
                }
            }
        }
        return result.values.sortedWith(compareBy({ it.year }, { it.description }))
    }

    /**
     * (key,type)로 묶인 여러 fieldDefId 전체에 걸쳐, 주어진 [values] 중 하나라도 가진(또는 [exclude]면
     * 하나도 갖지 않은) 캐릭터를 반환한다. detectPatterns가 다세계관을 합산해 감지하므로, 드릴다운도
     * 단일 fieldDefId가 아니라 **병합 id 전체**를 순회해야 과소집계되지 않는다. getFieldValues 파싱 재사용.
     *
     * @return 형제 함수 [getCharactersByFieldValue]와 **같은 계약**이다 — 대상 필드 정의를 하나도
     *   찾지 못하면 빈 목록으로 위장하지 않고 **null**을 돌려준다(R-17). 아무것도 묻지 않은 호출
     *   (id·값이 빈 경우)은 그냥 빈 목록이다: 그것은 "못 찾음"이 아니라 "물은 것이 없음"이다.
     */
    fun getCharactersByFieldKeyValues(
        s: StatsSnapshot,
        fieldDefIds: List<Long>,
        values: Set<String>,
        exclude: Boolean = false,
        valuesByDefId: Map<Long, List<CharacterFieldValue>>? = null
    ): List<FieldValueCharacter>? {
        if (fieldDefIds.isEmpty() || values.isEmpty()) return emptyList()
        val idSet = fieldDefIds.toSet()
        val defById = s.fieldDefinitions.filter { it.id in idSet }.associateBy { it.id }
        if (defById.isEmpty()) return null
        val charMap = s.characters.associateBy { it.id }
        // 캐릭터별로 이 (key,type) 그룹에서 파싱된 값 집합을 모은다.
        val perChar = HashMap<Long, MutableSet<String>>()

        // detectPatterns가 그룹 전체를 '첫 번째' def의 config로 파싱해 %/분포를 냈으므로, 드릴다운도 동일한
        // 기준 def(fieldDefIds.first())로 파싱해야 값 공간이 일치하고 인원이 %와 어긋나지 않는다
        // (같은 필드라도 세계관별 config(값 카테고리 등)가 다를 때의 과소/과대집계 방지).
        val refDef = defById[fieldDefIds.first()] ?: defById.values.first()
        if (refDef.type != "CALCULATED") {
            val refCfg = FieldStatsConfig.fromConfig(refDef.config)
            // 관련 def 값만 순회 — 편향 카드마다 전체 fieldValues를 스캔하던 것 방지(P1-D).
            // 사전 그룹([valuesByDefId])이 있으면 재사용해 카드 수 × 전체스캔의 제곱 폭발을 없앤다.
            val relevant = if (valuesByDefId != null) idSet.flatMap { valuesByDefId[it].orEmpty() }
                           else s.fieldValues.filter { it.fieldDefinitionId in idSet }
            for (fv in relevant) {
                val parsed = getFieldValues(s, refDef, fv.value, refCfg)
                if (parsed.isNotEmpty()) perChar.getOrPut(fv.characterId) { mutableSetOf() }.addAll(parsed)
            }
        }
        val calcDefs = defById.values.filter { it.type == "CALCULATED" }
        if (calcDefs.isNotEmpty()) {
            val calc = computeAllCalculatedValues(s)
            for ((charId, fieldMap) in calc) {
                for (fd in calcDefs) {
                    val v = fieldMap[fd.id] ?: continue
                    val cfg = FieldStatsConfig.fromConfig(fd.config)
                    perChar.getOrPut(charId) { mutableSetOf() }.addAll(getFieldValues(s, fd, v, cfg))
                }
            }
        }

        val result = mutableListOf<FieldValueCharacter>()
        for ((charId, vals) in perChar) {
            val matched = vals.firstOrNull { it in values }
            val include = if (exclude) matched == null else matched != null
            if (!include) continue
            val char = charMap[charId] ?: continue
            val shownValue = matched ?: vals.firstOrNull { it !in values } ?: vals.firstOrNull() ?: ""
            val images = try {
                com.google.gson.Gson().fromJson(char.imagePaths, Array<String>::class.java)?.toList() ?: emptyList()
            } catch (_: Exception) { emptyList() }
            result.add(
                FieldValueCharacter(
                    characterId = char.id,
                    characterName = char.name,
                    fieldValue = shownValue,
                    imageUri = images.firstOrNull()
                )
            )
        }
        return result.sortedBy { it.characterName }
    }

    /**
     * 캐릭터 ID 집합에 대해 다른 필드의 분포를 분석 (하위 그룹 분석).
     *
     * [targetFieldDefIds]에는 **(key,type)로 머지된 def 집합 전체**를 준다([getRankableFields]의
     * `mergedFieldDefIds`와 같은 축). 대표 id 하나만 받으면 전체 세계관 스코프에서 형제 세계관의
     * 같은 필드 값이 통째로 빠진다. 파싱은 첫 원소(기준 def)의 config로 통일한다 —
     * 인사이트 차트와 같은 값 공간이어야 두 화면이 같은 답을 준다.
     *
     * **CALCULATED 필드는 저장 행이 없다.** 저장 값만 훑으면 자기 카드에서는 분포가 그려지는
     * 수식 필드가 하위 그룹 분석에서만 항상 '데이터 없음'이 된다(S-19). 목록이 고를 수 있게
     * 약속한 필드는 전부 실제로 분석돼야 하므로 계산값을 함께 합산한다 —
     * 선택 목록에서 CALCULATED를 숨기는 것은 기능 간소화(원칙 03 위반)라 금지다.
     *
     * @return 대상 필드 정의를 하나도 찾지 못하면 **null**(호출부가 사유를 고지한다).
     *   분포가 비어 있는 것은 "정말 값이 없다"는 뜻이며 그때만 '데이터 없음'이 표시된다.
     */
    fun computeSubgroupAnalysis(
        s: StatsSnapshot,
        characterIds: Set<Long>,
        targetFieldDefIds: List<Long>
    ): SubgroupAnalysis? {
        if (targetFieldDefIds.isEmpty()) return null
        val idSet = targetFieldDefIds.toSet()
        val defById = s.fieldDefinitions.filter { it.id in idSet }.associateBy { it.id }
        if (defById.isEmpty()) return null

        val refDef = defById[targetFieldDefIds.first()] ?: defById.values.first()
        val refCfg = FieldStatsConfig.fromConfig(refDef.config)

        // **대상 수**로 센다(값 건수가 아니다). 이 화면의 행 라벨은 '명'이고 제목은 'N명 기준'이라,
        // 다중값 필드에서 값 건수를 세면 10명 전원이 가진 값이 33%로 표시되고 행의 합이 모집단을
        // 넘는다. "이 그룹에서 이 값을 가진 대상이 몇인가"가 이 화면이 답하는 질문이다.
        val holders = HashMap<String, MutableSet<Long>>()
        fun record(entityId: Long, value: String) {
            for (key in getFieldValues(s, refDef, value, refCfg)) {
                holders.getOrPut(key) { HashSet() }.add(entityId)
            }
        }

        // 저장된 값
        for (fv in s.fieldValues) {
            if (fv.fieldDefinitionId !in idSet) continue
            if (fv.characterId !in characterIds) continue
            if (defById[fv.fieldDefinitionId]?.type == "CALCULATED") continue
            record(fv.characterId, fv.value)
        }

        // CALCULATED 계산값 — 부분집합(characterIds)만 집계한다
        val calcDefIds = defById.values.filter { it.type == "CALCULATED" }.map { it.id }
        if (calcDefIds.isNotEmpty()) {
            val calculated = computeAllCalculatedValues(s)
            for (charId in characterIds) {
                val fieldMap = calculated[charId] ?: continue
                for (defId in calcDefIds) {
                    val v = fieldMap[defId] ?: continue
                    record(charId, v)
                }
            }
        }

        val counted = ValueDistributions.sorted(holders.mapValues { it.value.size })
        val view = ValueDistributions.view(counted, SUBGROUP_DISTRIBUTION_LIMIT)

        return SubgroupAnalysis(
            targetFieldName = refDef.name,
            distribution = view.shownMap(),
            totalCount = characterIds.size,
            // R-14: 잘라냈으면 남은 개수로 존재를 알린다 — 상한은 이 상수가 단일 소스다.
            truncatedCount = view.hiddenKinds
        )
    }

    /**
     * 사건 ID 집합에 대해 다른 **사건 필드**의 분포를 분석 — [computeSubgroupAnalysis]의 사건판.
     *
     * R-13대로 축마다 함수를 나눈다: 셀 단위가 캐릭터 수가 아니라 사건 수다. 사건 드릴다운에서
     * 하위 그룹 버튼만 숨기는 것은 기능 간소화(원칙 03 위반)이므로 대칭으로 구현한다.
     */
    fun computeEventSubgroupAnalysis(
        s: StatsSnapshot,
        eventIds: Set<Long>,
        targetFieldDefIds: List<Long>
    ): SubgroupAnalysis? {
        if (targetFieldDefIds.isEmpty()) return null
        val idSet = targetFieldDefIds.toSet()
        val defById = s.eventFieldDefinitions.filter { it.id in idSet }.associateBy { it.id }
        if (defById.isEmpty()) return null

        val refDef = defById[targetFieldDefIds.first()] ?: defById.values.first()
        val refCfg = FieldStatsConfig.fromConfig(refDef.config)

        // 캐릭터 축과 **같은 처리**여야 한다(R-16의 짝 규칙) — 여기서도 값 건수가 아니라 대상 수다.
        val holders = HashMap<String, MutableSet<Long>>()
        fun record(entityId: Long, value: String) {
            for (key in getFieldValues(s, refDef, value, refCfg)) {
                holders.getOrPut(key) { HashSet() }.add(entityId)
            }
        }

        for (fv in s.eventFieldValues) {
            if (fv.fieldDefinitionId !in idSet) continue
            if (fv.eventId !in eventIds) continue
            if (defById[fv.fieldDefinitionId]?.type == "CALCULATED") continue
            record(fv.eventId, fv.value)
        }

        val calcDefIds = defById.values.filter { it.type == "CALCULATED" }.map { it.id }
        if (calcDefIds.isNotEmpty()) {
            val calculated = computeAllEventCalculatedValues(s)
            for (eventId in eventIds) {
                val fieldMap = calculated[eventId] ?: continue
                for (defId in calcDefIds) {
                    val v = fieldMap[defId] ?: continue
                    record(eventId, v)
                }
            }
        }

        val counted = ValueDistributions.sorted(holders.mapValues { it.value.size })
        val view = ValueDistributions.view(counted, SUBGROUP_DISTRIBUTION_LIMIT)

        return SubgroupAnalysis(
            targetFieldName = refDef.name,
            distribution = view.shownMap(),
            totalCount = eventIds.size,
            truncatedCount = view.hiddenKinds
        )
    }

    /**
     * '필드 하나 고르기' UI용 — 필드 정의를 인사이트 카드와 **같은 축((key,type) 머지)**으로 묶고
     * 같은 '통계에 포함' 필터를 쓴다. 머지하지 않으면 전체 세계관 보기에서 같은 필드가 세계관
     * 수만큼 중복 나열되고, 그중 하나를 고르면 그 세계관 값만 집계돼 카드와 다른 답이 나온다.
     *
     * **종전 결정을 뒤집은 것이다.** 이 함수는 "통계 비활성 필드도 부분집합 분석 대상으로
     * 허용해 왔다(자율성 우선)"는 이유로 일부러 거르지 않았고, 형제 목록인 [getRankableFields]는
     * 걸렀다. 같은 성격의 '필드 하나 고르는 목록' 둘이 반대로 동작한 것이다 —
     * 사용자에게는 한 설정이 화면마다 다르게 해석되는 상태이고, 그것이 로드맵 4가 없애려는 것이다.
     *
     * 거르는 쪽을 택한 근거: 토글의 문구가 '통계에 포함'이고, **자동으로 나열하는 목록은 앱이
     * 고르는 것**이다(사용자는 아직 아무것도 고르지 않았다). 규약 R-14도 "사용자가 직접 끈
     * 항목은 '잘린 것'이 아니다"라고 못박아 두었으므로 개수 고지 대상도 아니다. 되돌리려면
     * 필드 편집에서 토글을 켜면 되고, 이미 고른 필드는 설정과 무관하게 계산된다([StatsFieldPolicy]).
     */
    fun getMergedFieldGroups(defs: List<FieldDefinition>): List<MergedFieldGroup> =
        StatsFieldPolicy.analyzable(defs).groupBy { it.key to it.type }
            .map { (_, fds) -> MergedFieldGroup(fds.first(), fds.map { it.id }) }

    // ===== 순위 계산 =====

    /**
     * 순위를 매길 수 있는 필드 목록을 반환한다.
     * universeId가 null이면 모든 세계관의 필드를 (key, type) 기준으로 머지하여 중복 없이 반환한다.
     */
    fun getRankableFields(s: StatsSnapshot, universeId: Long?): List<RankableField> {
        val fields = if (universeId != null) {
            s.fieldDefinitions.filter { it.universeId == universeId }
        } else {
            s.fieldDefinitions
        }

        // 판정은 단일 소스를 탄다 — 여기만 직접 파싱하면 규칙이 바뀔 때 이 목록만 뒤처진다.
        val enabledFields = StatsFieldPolicy.analyzable(fields)

        // 전체 세계관: 같은 (key, type)의 필드를 하나로 머지
        val grouped = if (universeId == null) {
            enabledFields.groupBy { it.key to it.type }
        } else {
            // 단일 세계관: 각 필드를 개별 그룹으로
            enabledFields.map { (it.key to it.type) to listOf(it) }.toMap()
        }

        return grouped.map { (_, fds) ->
            val primaryFd = fds.first()
            val type = primaryFd.type
            // 순위 화면이 "빈도"라고 표시할지 "수치"라고 표시할지는 계산과 **같은 표**를 봐야 한다.
            // 리터럴을 따로 두면 타입이 늘 때 목록 화면만 뒤처져 사용자에게 거짓을 말한다.
            val isNumeric = type in NUMERIC_RANKING_TYPES
            val bodySizeParts = if (type == "BODY_SIZE") {
                val sic = StructuredInputConfig.fromConfig(primaryFd.config)
                if (sic.enabled && sic.parts.isNotEmpty()) {
                    sic.parts.map { it.label }
                } else {
                    listOf("가슴(B)", "허리(W)", "엉덩이(H)")
                }
            } else null
            RankableField(primaryFd, bodySizeParts, isNumeric,
                mergedFieldDefIds = fds.map { it.id })
        }
    }

    /**
     * 지정된 필드에 대해 캐릭터 순위를 계산한다.
     * fieldDefIds: 전체 세계관 모드에서 같은 key+type으로 머지된 모든 fieldDefId 목록.
     *              단일 세계관 모드에서는 [fieldDefId] 하나만 전달.
     */
    fun computeRanking(
        s: StatsSnapshot,
        fieldDefIds: List<Long>,
        ascending: Boolean = false,
        bodySizePartIndex: Int? = null
    ): RankingResult {
        // 빈 목록으로 부르는 것은 "물은 것이 없음"이다 — 예외로 죽지 않는다.
        val fieldDefId = fieldDefIds.firstOrNull()
            ?: return RankingResult(emptyList(), "", "", ascending, 0, 0)
        val fd = s.fieldDefinitions.find { it.id == fieldDefId }
            ?: return RankingResult(emptyList(), "", "", ascending, 0, 0)

        val charMap = s.characters.associateBy { it.id }
        val novelMap = s.novels.associateBy { it.id }
        val isNumeric = fd.type in NUMERIC_RANKING_TYPES

        // 관련 세계관 ID 집합 (머지된 모든 필드의 세계관). 미배정 스코프 모수 = 스코프 캐릭터 전체
        // (novelId 경유 시 모수 0 → noValueCount 음수 결함까지 함께 해소)
        val allFds = fieldDefIds.mapNotNull { id -> s.fieldDefinitions.find { it.id == id } }
        val relevantUniverseIds = allFds.map { it.universeId }.toSet()
        val relevantNovelIds = s.novels.filter { it.universeId in relevantUniverseIds }.map { it.id }.toSet()
        val relevantCharCount = if (s.unassignedScope) s.characters.size
            else s.characters.count { it.novelId in relevantNovelIds }

        data class CharValue(val charId: Long, val numericValue: Double, val displayValue: String)

        // 캐릭터당 한 행. 머지된 형제 def에 값이 남아 있으면(작품 이동 뒤의 보관 값 등) 한
        // 캐릭터가 순위표에 두 번 나올 수 있었다. **그 캐릭터의 현재 세계관 def 값**을 우선하고,
        // 없으면 먼저 만난 값을 쓴다.
        val charValues = LinkedHashMap<Long, CharValue>()
        val charValuePriority = HashMap<Long, Int>()
        val processedCharIds = mutableSetOf<Long>()
        var parseFailed = 0

        fun universeOf(charId: Long): Long? =
            charMap[charId]?.novelId?.let { novelMap[it] }?.universeId

        fun putValue(cv: CharValue, ownerUniverseId: Long?) {
            val priority = if (ownerUniverseId != null && ownerUniverseId == universeOf(cv.charId)) 2 else 1
            val existing = charValuePriority[cv.charId]
            if (existing != null && existing >= priority) return
            charValues[cv.charId] = cv
            charValuePriority[cv.charId] = priority
        }

        // ── CALCULATED 필드: 저장 행이 없으므로 수식으로 계산한다 ──
        // 계산은 [computeAllCalculatedNumbers] **하나**가 한다. 종전에는 이 자리에 수식 평가가
        // 다시 구현돼 있어 단일 소스 규약이 깨져 있었고, 서식이 `%.1f`(여기) 대 `%.2f`(그쪽)로
        // 갈려 같은 필드가 순위표와 분포에서 다른 값으로 보였다(B-33).
        if (fd.type == "CALCULATED") {
            val calcDefIds = allFds.filter { it.type == "CALCULATED" }.map { it.id }.toSet()
            val defUniverseById = allFds.associate { it.id to it.universeId }
            val numbers = computeAllCalculatedNumbers(s)
            for ((charId, byField) in numbers) {
                for ((defId, value) in byField) {
                    if (defId !in calcDefIds) continue
                    processedCharIds.add(charId)
                    putValue(
                        CharValue(charId, value, formatStatsNumber(value)),
                        defUniverseById[defId]
                    )
                }
            }
            // 값이 없는(수식 평가 실패 포함) 캐릭터는 아래 noValueCount로 함께 세어진다.
        } else {
            // ── NUMBER, GRADE, BODY_SIZE, SELECT, TEXT, MULTI_TEXT: 기존 DB 값 기반 ──
            val fieldDefIdSet = fieldDefIds.toSet()
            val rawValues = s.fieldValues.filter { it.fieldDefinitionId in fieldDefIdSet }

            // 머지된 필드별 FieldDefinition 역추적 맵 (GRADE/BODY_SIZE에서 올바른 config 사용)
            val fieldDefMap = allFds.associateBy { it.id }

            // 빈도 모드용 전체 빈도 — **통계 파싱 단일 소스**(getFieldValues)로 만든다.
            // 종전에는 `type == "MULTI_TEXT"`일 때만 콤마로 쪼갰다. 그래서 콤마 목록 표시 형식의
            // TEXT 필드는 "검, 활" 전체가 한 값이 되어 전원 1회 동률이 나왔고, 별칭 사전을 쓰는
            // 필드는 분포 차트와 순위의 수치가 서로 달랐다(S-18). 파싱은 기준 def(fd)의 설정으로
            // 통일한다 — 인사이트 차트가 그룹 전체를 그렇게 파싱하므로 같은 값 공간이어야 한다(R-15).
            // 키는 (캐릭터, 필드정의) — 행 id는 테스트 더미에서 0으로 겹칠 수 있고,
            // 이 쌍은 DB에서도 유니크 인덱스다.
            // **순위의 축은 값이다.** 통계 그룹핑을 '카테고리'/'둘 다'로 둔 필드에서 카드의 키 공간을
            // 그대로 쓰면 frequencyMap에 값 빈도와 카테고리 빈도가 섞이고(R-13 위반), 대표 토큰이
            // 거의 항상 카테고리가 되어 **캐릭터 순위가 카테고리 크기 순위로** 변한다
            // (같은 카테고리의 캐릭터가 전부 같은 값·같은 등수로 붙는다).
            // 카테고리 순위는 별개의 질문이므로 여기서 섞지 않는다 — 대신 값 축으로 통일한다.
            val refCfg = FieldStatsConfig.fromConfig(fd.config).copy(statsGroupBy = "value")
            val tokensByValue: Map<Pair<Long, Long>, List<String>> = if (!isNumeric) {
                // 빈 값은 인사이트 분포도 세지 않는다 — 키 공간을 정확히 맞춘다.
                rawValues.filter { it.value.isNotBlank() }.associate {
                    (it.characterId to it.fieldDefinitionId) to getFieldValues(s, fd, it.value, refCfg)
                }
            } else emptyMap()
            val frequencyMap = if (!isNumeric) {
                tokensByValue.values.flatten().groupingBy { it }.eachCount()
            } else emptyMap()

            for (fv in rawValues) {
                val char = charMap[fv.characterId] ?: continue
                processedCharIds.add(char.id)
                if (fv.value.isBlank()) { parseFailed++; continue }
                val ownerFd = fieldDefMap[fv.fieldDefinitionId] ?: fd
                val ownerUniverseId = ownerFd.universeId

                when (fd.type) {
                    "NUMBER" -> {
                        val v = fv.value.toDoubleOrNull()
                        if (v != null && v.isFinite()) {
                            // 표시는 **저장 원문**이다(GRADE·BODY_SIZE와 같은 규칙). 다시 서식하면
                            // 분포 차트가 보여주는 문자열("170.250")과 순위의 문자열이 갈린다.
                            // 저장 행이 없는 CALCULATED만 서식이 필요하다.
                            putValue(CharValue(char.id, v, fv.value.trim()), ownerUniverseId)
                        } else parseFailed++
                    }
                    "GRADE" -> {
                        // 값이 속한 FieldDefinition의 config으로 등급 해석 (세계관별 맵핑 차이 대응).
                        // 저장값이 **별칭**일 수 있다(값 라이브러리). 분포는 canonical로 접어 세므로
                        // 등급 해석만 원문으로 조회하면 차트에는 있는 캐릭터가 순위에서만 빠진다.
                        val canonical = resolversOf(s)[ownerFd.id]
                            ?.takeIf { !it.isEmpty }?.canonical(fv.value) ?: fv.value
                        val numericValue = resolveGradeValueForRanking(ownerFd, canonical)
                        if (numericValue != null) {
                            putValue(CharValue(char.id, numericValue, fv.value), ownerUniverseId)
                        } else parseFailed++
                    }
                    "BODY_SIZE" -> {
                        // 값이 속한 FieldDefinition의 config으로 파싱 (세계관별 separator 차이 대응)
                        val sic = StructuredInputConfig.fromConfig(ownerFd.config)
                        val partIdx = (bodySizePartIndex ?: 0).coerceAtLeast(0)
                        val parts = if (sic.enabled) {
                            fv.value.split(sic.separator).map { it.trim() }
                        } else {
                            fv.value.split(Regex("[-/\\s]+")).map { it.trim() }
                        }
                        val partValue = parts.getOrNull(partIdx)?.toDoubleOrNull()
                        if (partValue != null && partValue.isFinite()) {
                            putValue(CharValue(char.id, partValue, fv.value), ownerUniverseId)
                        } else parseFailed++
                    }
                    else -> {
                        // 빈도 모드: SELECT, TEXT, MULTI_TEXT + 콤마 목록 표시 형식 TEXT까지 동일 경로.
                        // 한 캐릭터가 여러 토큰을 가지면 **가장 흔한 토큰**을 대표로 삼는다
                        // (종전 MULTI_TEXT 규칙을 모든 다중값 필드로 넓힌 것이다).
                        val tokens = tokensByValue[fv.characterId to fv.fieldDefinitionId].orEmpty()
                        if (tokens.isEmpty()) { parseFailed++; continue }
                        val topToken = tokens.maxByOrNull { frequencyMap[it] ?: 0 }
                        val maxFreq = topToken?.let { frequencyMap[it] ?: 0 } ?: 0
                        if (topToken != null && maxFreq > 0) {
                            putValue(
                                CharValue(char.id, maxFreq.toDouble(), "$topToken (${maxFreq}회)"),
                                ownerUniverseId
                            )
                        } else parseFailed++
                    }
                }
            }
        } // else (non-CALCULATED)

        // 제외 카운트: 관련 세계관 캐릭터만 기준 (전체 세계관 모드에서 다른 세계관 캐릭터 제외)
        val noValueCount = relevantCharCount - processedCharIds.size
        val excludedCount = parseFailed + noValueCount

        // 정렬 및 순위 할당 (동점 시 표준 경쟁 순위: 1,2,2,4)
        val sorted = if (ascending) {
            charValues.values.sortedBy { it.numericValue }
        } else {
            charValues.values.sortedByDescending { it.numericValue }
        }

        val entries = mutableListOf<RankingEntry>()
        var currentRank = 1
        for (i in sorted.indices) {
            if (i > 0 && sorted[i].numericValue != sorted[i - 1].numericValue) {
                currentRank = i + 1  // 표준 경쟁 순위: 이전 동점 수만큼 건너뜀
            }
            val char = charMap[sorted[i].charId] ?: continue
            val novel = char.novelId?.let { novelMap[it] }
            entries.add(
                RankingEntry(
                    characterId = char.id,
                    characterName = char.name,
                    rank = currentRank,
                    value = sorted[i].numericValue,
                    displayValue = sorted[i].displayValue,
                    imagePaths = char.imagePaths,
                    novelTitle = novel?.title
                )
            )
        }

        return RankingResult(
            entries = entries,
            fieldName = fd.name,
            fieldType = fd.type,
            ascending = ascending,
            totalCharacters = entries.size,
            excludedCount = excludedCount
        )
    }

    // ===== CALCULATED 필드 유틸리티 =====

    /**
     * 저장 값에 CALCULATED 계산값을 합친 **필드 정의별 값 버킷** — R-16의 단일 소스.
     *
     * 계산 필드는 `character_field_values`에 행이 없으므로, 저장 값을 직접 읽는 경로는
     * 수식 필드를 조용히 빈 값으로 취급한다. 인사이트 카드는 이 합성을 하고 패턴 감지·레거시
     * 필드 분석·요약 TOP5는 하지 않아, **자기 카드에서는 분포가 그려지는 필드가 다른 화면에서만
     * 늘 비어 있었다**(B-33). 합성 규칙이 여러 곳에 흩어지면 또 갈라지므로 여기 하나만 둔다.
     *
     * 합성 행의 `id`는 0이다 — 이 버킷은 집계·계수용이며 DB 행으로 다시 쓰지 않는다.
     */
    private fun augmentedCharacterValues(s: StatsSnapshot): Map<Long, List<CharacterFieldValue>> {
        val byFieldDef = s.fieldValues.filter { it.value.isNotBlank() }
            .groupByTo(HashMap()) { it.fieldDefinitionId }

        for ((charId, fieldMap) in computeAllCalculatedValues(s)) {
            for ((fieldDefId, value) in fieldMap) {
                if (value.isBlank()) continue
                byFieldDef.getOrPut(fieldDefId) { mutableListOf() }.add(
                    CharacterFieldValue(
                        characterId = charId,
                        fieldDefinitionId = fieldDefId,
                        value = value
                    )
                )
            }
        }
        return byFieldDef
    }

    /**
     * 통계가 수치를 문자열로 보일 때의 서식 **단일 소스**.
     *
     * 종전에는 계산 필드 일괄 계산이 `%.2f`, 순위 계산의 자체 분기가 `%.1f`를 써서 **같은 필드가
     * 화면마다 다른 값**으로 보였다(23.46 vs 23.5). 서식이 곧 값의 정체성인 경로(분포 키·
     * 드릴다운 매칭)가 있으므로 서식도 한 곳에서만 정한다.
     */
    private fun formatStatsNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        // **로케일 고정**: 이 문자열은 표시용이 아니라 값의 정체성이다 — 분포의 키가 되고,
        // 드릴다운 매칭 키가 되고, 레거시 수치 요약에서 다시 toFloat로 파싱된다.
        // 기본 로케일을 쓰면 소수점이 콤마인 환경에서 "12,34"가 되어 되파싱이 실패하고
        // 그 캐릭터가 요약에서 조용히 사라진다.
        else String.format(java.util.Locale.US, "%.2f", value)

    /**
     * CALCULATED 필드의 값을 FormulaEvaluator로 일괄 계산.
     * 반환: characterId → (fieldDefinitionId → 계산된 값 문자열)
     *
     * StatsDataProvider 내 모든 CALCULATED 필드 처리에서 이 메서드를 사용하여
     * 일관된 계산 로직을 보장한다. 수치가 필요한 경로(순위)는 [computeAllCalculatedNumbers]를
     * 쓰고 표시에는 [formatStatsNumber]를 쓴다 — 수식을 두 번 구현하지 않는다.
     */
    private fun computeAllCalculatedValues(s: StatsSnapshot): Map<Long, Map<Long, String>> =
        computeAllCalculatedNumbers(s).mapValues { (_, byField) ->
            byField.mapValues { (_, v) -> formatStatsNumber(v) }
        }

    /**
     * CALCULATED 필드의 **수치** 계산값. 반환: characterId → (fieldDefinitionId → Double)
     *
     * 순위는 정렬에 원시 수치가 필요하다. 서식 문자열을 다시 파싱하면 `%.2f` 반올림이
     * 순위 경계를 바꾸므로 수치와 표시를 갈라 두되, **계산 자체는 이 함수 하나**다.
     */
    private fun computeAllCalculatedNumbers(s: StatsSnapshot): Map<Long, Map<Long, Double>> {
        // 한 칸짜리 캐시로는 부족하다 — 통계 로딩은 계산 4개를 동시에 띄우고, 그 사이 다른 화면이
        // **다른 스냅샷**(작품별 비교는 원본)으로 들어온다. 스냅샷 동일성을 키로 하는 작은 맵을 쓰고,
        // `computeIfAbsent`가 같은 스냅샷의 동시 요청을 하나로 묶는다(나머지는 결과를 기다린다).
        if (calcCache.size > MAX_CACHED_SNAPSHOTS) calcCache.clear()
        return calcCache.computeIfAbsent(IdentityKey(s)) { evaluateAllCalculatedNumbers(s) }
    }

    /**
     * 스냅샷 1개짜리 계산 결과 캐시.
     *
     * 수식 평가는 캐릭터 수 × 수식 수에 비례하는데, 이제 인사이트·패턴·레거시 분석·요약·
     * 교차분석·드릴다운이 모두 계산값을 합치므로 같은 스냅샷에 대해 여러 번 불린다.
     * 통계 로딩은 여러 계산을 `async`로 동시에 돌리고, 화면에 따라 **서로 다른 스냅샷**이
     * 동시에 계산된다(작품별 비교는 원본, 나머지는 필터본). 그래서 한 칸짜리 캐시는 서로를 밀어내고
     * 키/값을 따로 게시하면 짝이 어긋난다 — 스냅샷 동일성을 키로 하는 작은 동시 맵을 쓴다.
     */
    /** 동일성(===)으로만 같은 키. 스냅샷은 불변이므로 같은 객체면 결과도 같다. */
    private class IdentityKey(val target: Any) {
        override fun hashCode(): Int = System.identityHashCode(target)
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.target === target
    }

    private val calcCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, Map<Long, Map<Long, Double>>>()

    private fun evaluateAllCalculatedNumbers(s: StatsSnapshot): Map<Long, Map<Long, Double>> {
        val calculatedFields = s.fieldDefinitions.filter { it.type == "CALCULATED" }
        if (calculatedFields.isEmpty()) return emptyMap()

        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val allFieldDefById = s.fieldDefinitions.associateBy { it.id }
        val charFieldValues = s.fieldValues.groupBy { it.characterId }

        // 세계관별 CALCULATED 필드와 수식을 미리 파싱
        data class CalcFieldInfo(val fd: FieldDefinition, val formula: String)
        val calcFieldsByUniverse = mutableMapOf<Long, List<CalcFieldInfo>>()
        for ((universeId, fields) in fieldDefByUniverse) {
            val calcInfos = fields.filter { it.type == "CALCULATED" }.mapNotNull { fd ->
                val formula = try {
                    org.json.JSONObject(fd.config).optString("formula", "")
                } catch (_: Exception) { "" }
                if (formula.isNotBlank()) CalcFieldInfo(fd, formula) else null
            }
            if (calcInfos.isNotEmpty()) calcFieldsByUniverse[universeId] = calcInfos
        }
        if (calcFieldsByUniverse.isEmpty()) return emptyMap()

        val result = mutableMapOf<Long, MutableMap<Long, Double>>()

        for (char in s.characters) {
            val novel = char.novelId?.let { novelMap[it] } ?: continue
            val calcInfos = calcFieldsByUniverse[novel.universeId] ?: continue
            val universeFields = fieldDefByUniverse[novel.universeId] ?: continue

            val values = charFieldValues[char.id] ?: emptyList()
            val fieldKeyValues = mutableMapOf<String, String>()
            for (fv in values) {
                val fDef = allFieldDefById[fv.fieldDefinitionId] ?: continue
                fieldKeyValues[fDef.key] = fv.value
            }

            val evaluator = FormulaEvaluator(fieldKeyValues, universeFields)
            val charCalcValues = mutableMapOf<Long, Double>()

            for ((fd, formula) in calcInfos) {
                try {
                    val value = evaluator.evaluate(formula)
                    if (!value.isNaN() && !value.isInfinite()) {
                        charCalcValues[fd.id] = value
                    }
                } catch (_: Exception) { /* 평가 실패 시 해당 필드 제외 */ }
            }

            if (charCalcValues.isNotEmpty()) {
                result[char.id] = charCalcValues
            }
        }
        return result
    }

    /**
     * 사건 CALCULATED 필드 일괄 계산 — computeAllCalculatedValues의 사건판.
     * @return Map<eventId, Map<fieldDefinitionId, 계산값 문자열>>
     */
    private fun computeAllEventCalculatedValues(s: StatsSnapshot): Map<Long, Map<Long, String>> {
        val calculatedFields = s.eventFieldDefinitions.filter { it.type == "CALCULATED" }
        if (calculatedFields.isEmpty()) return emptyMap()

        val fieldDefByUniverse = s.eventFieldDefinitions.groupBy { it.universeId }
        val allFieldDefById = s.eventFieldDefinitions.associateBy { it.id }
        val valuesByEvent = s.eventFieldValues.groupBy { it.eventId }

        data class CalcFieldInfo(val fd: FieldDefinition, val formula: String)
        val calcFieldsByUniverse = mutableMapOf<Long, List<CalcFieldInfo>>()
        for ((universeId, fields) in fieldDefByUniverse) {
            val calcInfos = fields.filter { it.type == "CALCULATED" }.mapNotNull { fd ->
                val formula = try {
                    org.json.JSONObject(fd.config).optString("formula", "")
                } catch (_: Exception) { "" }
                if (formula.isNotBlank()) CalcFieldInfo(fd, formula) else null
            }
            if (calcInfos.isNotEmpty()) calcFieldsByUniverse[universeId] = calcInfos
        }
        if (calcFieldsByUniverse.isEmpty()) return emptyMap()

        val result = mutableMapOf<Long, MutableMap<Long, String>>()
        for (event in s.events) {
            val universeId = event.universeId ?: continue
            val calcInfos = calcFieldsByUniverse[universeId] ?: continue
            val universeFields = fieldDefByUniverse[universeId] ?: continue

            val values = valuesByEvent[event.id] ?: emptyList()
            val fieldKeyValues = mutableMapOf<String, String>()
            for (fv in values) {
                val fDef = allFieldDefById[fv.fieldDefinitionId] ?: continue
                fieldKeyValues[fDef.key] = fv.value
            }

            val evaluator = FormulaEvaluator(fieldKeyValues, universeFields)
            val eventCalcValues = mutableMapOf<Long, String>()
            for ((fd, formula) in calcInfos) {
                try {
                    val value = evaluator.evaluate(formula)
                    if (!value.isNaN() && !value.isInfinite()) {
                        // 서식은 캐릭터 축과 같은 단일 소스를 쓴다 — 같은 수식이 축마다 다른
                        // 문자열이 되면 분포 키가 갈라진다.
                        eventCalcValues[fd.id] = formatStatsNumber(value)
                    }
                } catch (_: Exception) { /* 평가 실패 시 해당 필드 제외 */ }
            }
            if (eventCalcValues.isNotEmpty()) {
                result[event.id] = eventCalcValues
            }
        }
        return result
    }

    /**
     * GRADE 필드의 라벨을 숫자 값으로 변환 — 등급 해석 단일 소스(GradeValueResolver) 위임.
     */
    private fun resolveGradeValueForRanking(fieldDef: FieldDefinition, gradeLabel: String): Double? {
        return com.novelcharacter.app.util.GradeValueResolver.resolveFromConfig(fieldDef, gradeLabel)
    }

    companion object {
        /** 사용자 구간(binning) 설정을 받는 수치형 타입. 타입이 늘면 여기만 고친다. */
        private val BINNABLE_TYPES = setOf("NUMBER", "CALCULATED")

        /** 순위에서 값을 수치로 해석하는 타입(그 외는 빈도 모드). */
        private val NUMERIC_RANKING_TYPES = setOf("NUMBER", "CALCULATED", "GRADE", "BODY_SIZE")

        /** 사용자 구간 어디에도 들지 않는 값의 표시 키 — 조용히 버리지 않는다(R-17). */
        const val OUT_OF_RANGE_LABEL = "구간 밖"

        /** 계산값 캐시가 들고 있을 스냅샷 수 상한 — 넘으면 통째로 비운다(무한 축적 방지). */
        private const val MAX_CACHED_SNAPSHOTS = 4

        /** 레거시 필드 분석 화면이 이산 분포를 그리는 타입. */
        private val DISCRETE_DISTRIBUTION_TYPES = setOf("SELECT", "GRADE", "MULTI_TEXT", "TEXT")
    }
}
