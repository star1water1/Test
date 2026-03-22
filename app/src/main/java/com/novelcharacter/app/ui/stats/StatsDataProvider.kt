package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.*

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
    val crossRefs: List<TimelineCharacterCrossRef>
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
    val fieldCompletionRate: Float,
    val stateChangeCount: Int,
    val totalScore: Float,
    val overallPotential: PotentialGrade = PotentialGrade.D,
    val specialization: Specialization = Specialization.NONE
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
    val distribution: Map<String, Int>
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
    val universeName: String = ""
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
data class CrossAnalysisResult(
    val field1Name: String,
    val field2Name: String,
    val filterFieldName: String?,
    val filterValue: String?,
    val crossTable: Map<String, Map<String, Int>>,
    val totalCount: Int,
    val filteredCount: Int
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
    val fieldDefId: Long? = null
)

// ===== 차트 탭 → 캐릭터 목록 (개선 6) =====
data class FieldValueCharacter(
    val characterId: Long,
    val characterName: String,
    val fieldValue: String,
    val imageUri: String?
)

data class SubgroupAnalysis(
    val targetFieldName: String,
    val distribution: Map<String, Int>,
    val totalCount: Int
)

class StatsDataProvider(private val app: NovelCharacterApp) {

    suspend fun loadSnapshot(): StatsSnapshot {
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
            crossRefs = db.timelineDao().getAllCrossRefs()
        )
    }

    /** 스냅샷을 특정 작품으로 필터링 */
    fun filterByNovel(s: StatsSnapshot, novelId: Long): StatsSnapshot {
        val novel = s.novels.find { it.id == novelId } ?: return s
        val charIds = s.characters.filter { it.novelId == novelId }.map { it.id }.toSet()
        val eventIds = s.events.filter { it.novelId == novelId }.map { it.id }.toSet()
        val filteredRelationships = s.relationships.filter { it.characterId1 in charIds || it.characterId2 in charIds }
        val relIds = filteredRelationships.map { it.id }.toSet()
        // nameBank: usedByCharacterId가 해당 작품 캐릭터이거나 미사용인 것만 포함
        val filteredNameBank = s.nameBank.filter { entry ->
            entry.usedByCharacterId == null || entry.usedByCharacterId in charIds
        }
        return s.copy(
            characters = s.characters.filter { it.novelId == novelId },
            novels = listOf(novel),
            universes = s.universes.filter { it.id == novel.universeId },
            events = s.events.filter { it.novelId == novelId },
            relationships = filteredRelationships,
            relationshipChanges = s.relationshipChanges.filter { it.relationshipId in relIds },
            tags = s.tags.filter { it.characterId in charIds },
            nameBank = filteredNameBank,
            stateChanges = s.stateChanges.filter { it.characterId in charIds },
            fieldDefinitions = s.fieldDefinitions.filter { it.universeId == novel.universeId },
            fieldValues = s.fieldValues.filter { it.characterId in charIds },
            crossRefs = s.crossRefs.filter { it.characterId in charIds || it.eventId in eventIds }
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
            val fields = novel?.let { fieldDefByUniverse[it.universeId] } ?: emptyList()
            val filledCount = charFieldValues[char.id]?.count { it.value.isNotBlank() } ?: 0
            val completion = if (fields.isNotEmpty()) filledCount.toFloat() / fields.size * 100f else 0f

            val relWeight = relCnt * 2f
            val evtWeight = evtCnt * 1.5f
            // completion은 0~100 퍼센트 → 0~1로 정규화 후 가중치 적용 (최대 ~5점)
            val fieldWeight = (completion / 100f) * 5f
            val stateWeight = stateChangeCnt * 1f
            val score = relWeight + evtWeight + fieldWeight + stateWeight

            CharacterComplexity(
                char.name, relCnt, evtCnt, completion, stateChangeCnt, score,
                CharacterComplexity.PotentialGrade.fromScore(score),
                CharacterComplexity.Specialization.determine(relWeight, evtWeight, fieldWeight, stateWeight)
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

        // 평균 필드 완성도
        val universeFieldCounts = s.fieldDefinitions.groupBy { it.universeId }.mapValues { it.value.size }
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
        val fieldDefById = s.fieldDefinitions.associateBy { it.id }
        val topFieldValues = s.fieldValues
            .filter { it.value.isNotBlank() }
            .mapNotNull { fv ->
                val fd = fieldDefById[fv.fieldDefinitionId] ?: return@mapNotNull null
                Pair(fd.name, fv.value)
            }
            .groupBy { it }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
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

        // 필드 완성도
        val universeFieldCounts = s.fieldDefinitions.groupBy { it.universeId }
            .mapValues { it.value.size }
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

        // 신규: 그룹별 필드 완성도
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
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
            val completion = fieldCompletionById[char.id] ?: 0f
            val stateChangeCnt = stateChangesByChar[char.id]?.size ?: 0

            val relWeight = relCnt * 2f
            val evtWeight = evtCnt * 1.5f
            // completion은 0~100 퍼센트 → 0~1로 정규화 후 가중치 적용 (최대 ~5점)
            val fieldWeight = (completion / 100f) * 5f
            val stateWeight = stateChangeCnt * 1f
            val score = relWeight + evtWeight + fieldWeight + stateWeight

            val overallPotential = CharacterComplexity.PotentialGrade.fromScore(score)
            val specialization = CharacterComplexity.Specialization.determine(
                relWeight, evtWeight, fieldWeight, stateWeight
            )

            CharacterComplexity(
                char.name, relCnt, evtCnt, completion, stateChangeCnt, score,
                overallPotential, specialization
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

        val novelEventCounts = s.events.groupBy { it.novelId }
            .mapKeys { (novelId, _) -> novelId?.let { novelMap[it]?.title } ?: "미지정" }
            .mapValues { it.value.size }

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

        // 필드 미입력률 높은 캐릭터
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val charFieldValuesByChar = s.fieldValues.groupBy { it.characterId }

        val incomplete = s.characters.mapNotNull { char ->
            val novelId = char.novelId ?: return@mapNotNull null
            val novel = novelMap[novelId] ?: return@mapNotNull null
            val fields = fieldDefByUniverse[novel.universeId] ?: return@mapNotNull null
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
        val valuesByFieldDef = s.fieldValues.filter { it.value.isNotBlank() }
            .groupBy { it.fieldDefinitionId }

        // 동일 필드를 (key, type) 기준으로 세계관 통합 (Pre-Analysis Merge)
        val fieldGroups = s.fieldDefinitions
            .filter { FieldStatsConfig.fromConfig(it.config).enabled }
            .groupBy { it.key to it.type }

        return fieldGroups.mapNotNull { (_, fds) ->
            val primaryFd = fds.first()
            val statsConfig = FieldStatsConfig.fromConfig(primaryFd.config)

            // 그룹 내 모든 필드의 값을 합산
            val rawValues = fds.flatMap { fd -> valuesByFieldDef[fd.id] ?: emptyList() }

            // 관련 세계관 전체의 캐릭터 수
            val universeIds = fds.map { it.universeId }.toSet()
            val relevantNovelIds = s.novels.filter { it.universeId in universeIds }.map { it.id }.toSet()
            val totalCount = s.characters.count { it.novelId in relevantNovelIds }
            val filledCount = rawValues.size

            val analysisResults = statsConfig.analyses.flatMap { entry ->
                when (entry.type) {
                    FieldStatsConfig.StatsType.DISTRIBUTION -> {
                        val dist = computeFieldDistribution(primaryFd, rawValues, statsConfig, entry.limit)
                        listOf(AnalysisResult(entry, dist, null))
                    }
                    FieldStatsConfig.StatsType.NUMERIC -> {
                        computeNumericAnalysis(primaryFd, rawValues, statsConfig, entry)
                    }
                    FieldStatsConfig.StatsType.RANKING -> {
                        val dist = computeFieldDistribution(primaryFd, rawValues, statsConfig, entry.limit)
                        val ranked = dist.entries.sortedByDescending { it.value }
                            .take(entry.limit)
                            .associate { it.key to it.value }
                        listOf(AnalysisResult(entry, ranked, null))
                    }
                }
            }

            val universeName = if (fds.size == 1) {
                universeMap[primaryFd.universeId]?.name ?: ""
            } else ""

            FieldInsightResult(primaryFd, statsConfig, analysisResults, totalCount, filledCount,
                universeName = universeName)
        }
    }

    /**
     * NUMERIC 분석 생성. BODY_SIZE는 파트별 개별 수치 통계를 반환한다.
     */
    private fun computeNumericAnalysis(
        fd: FieldDefinition,
        rawValues: List<CharacterFieldValue>,
        statsConfig: FieldStatsConfig,
        entry: FieldStatsConfig.AnalysisEntry
    ): List<AnalysisResult> {
        if (fd.type == "BODY_SIZE") {
            val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
            val separator = if (structuredConfig.enabled) structuredConfig.separator else "-"
            val partCount = if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                structuredConfig.parts.size
            } else {
                rawValues.firstOrNull()?.value?.split(separator)?.size ?: 1
            }

            return (0 until partCount).mapNotNull { partIdx ->
                val partLabel = if (structuredConfig.enabled && partIdx < structuredConfig.parts.size) {
                    structuredConfig.parts[partIdx].label
                } else "파트${partIdx + 1}"

                val numericValues = rawValues.mapNotNull { cfv ->
                    val parts = cfv.value.split(separator).map { it.trim() }
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
        val numericValues = rawValues.mapNotNull { it.value.toFloatOrNull() }
        val summary = if (numericValues.isNotEmpty()) {
            computeNumericSummary(numericValues, statsConfig.binning)
        } else null
        return listOf(AnalysisResult(entry, null, summary))
    }

    private fun computeFieldDistribution(
        fd: FieldDefinition,
        rawValues: List<CharacterFieldValue>,
        statsConfig: FieldStatsConfig,
        limit: Int
    ): Map<String, Int> {
        val allValues = mutableListOf<String>()

        for (fv in rawValues) {
            allValues.addAll(getFieldValues(fd, fv.value, statsConfig))
        }

        return allValues.groupBy { it }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
            .take(limit)
            .associate { it.key to it.value }
    }

    private fun getFieldValues(
        fd: FieldDefinition,
        rawValue: String,
        statsConfig: FieldStatsConfig
    ): List<String> {
        val format = DisplayFormat.fromConfig(fd.config)

        // Step 1: 값 분리
        val splitValues = when {
            fd.type == "BODY_SIZE" || StructuredInputConfig.fromConfig(fd.config).enabled -> {
                // 구조화 입력: config의 파트 라벨로 개별 분석
                val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
                if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                    structuredConfig.labeledParts(rawValue)
                } else {
                    // 구조화 설정 없는 BODY_SIZE — separator로 분리 (파트별 값만 반환)
                    val separator = try {
                        org.json.JSONObject(fd.config).optString("separator", "-")
                    } catch (_: Exception) { "-" }
                    val parts = rawValue.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size >= 2) parts else listOf(rawValue.trim())
                }
            }
            format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST ->
                rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            fd.type == "MULTI_TEXT" ->
                rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(rawValue.trim())
        }

        // Step 2: 값 라벨 매핑 적용
        val labeled = splitValues.map { statsConfig.applyLabel(it) }

        // Step 2.5: 카테고리 매핑 적용 (statsGroupBy 설정에 따라)
        val categorized = if (statsConfig.valueCategories.isNotEmpty()) {
            labeled.flatMap { statsConfig.resolveStatsKeys(it) }
        } else labeled

        // Step 3: NUMBER + binning
        if (fd.type == "NUMBER" && statsConfig.binning != null && statsConfig.binning.mode == "custom") {
            return categorized.mapNotNull { v ->
                v.toFloatOrNull()?.let { statsConfig.applyBinning(it) }
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
            val counts = mutableMapOf<String, Int>()
            for (range in ranges) {
                counts[range.label] = values.count { range.contains(it) }
            }
            counts
        } else {
            // 자동 5등분
            val range = max - min
            if (range <= 0) {
                mapOf(min.toString() to values.size)
            } else {
                val binSize = range / 5f
                val counts = mutableMapOf<String, Int>()
                for (i in 0 until 5) {
                    val binMin = min + i * binSize
                    val binMax = if (i == 4) max else min + (i + 1) * binSize
                    val label = "${binMin.toInt()}~${binMax.toInt()}"
                    counts[label] = values.count { it >= binMin && (if (i == 4) it <= binMax else it < binMax) }
                }
                counts
            }
        }

        return NumericSummaryData(min, max, avg, median, stdDev, histogram)
    }

    // ===== 교차 분석 (신규) =====
    fun computeCrossAnalysis(
        s: StatsSnapshot,
        field1Id: Long,
        field2Id: Long,
        filterFieldId: Long?,
        filterValue: String?
    ): CrossAnalysisResult? {
        val field1 = s.fieldDefinitions.find { it.id == field1Id } ?: return null
        val field2 = s.fieldDefinitions.find { it.id == field2Id } ?: return null
        val filterField = if (filterFieldId != null) s.fieldDefinitions.find { it.id == filterFieldId } else null

        val valuesByChar = s.fieldValues.groupBy { it.characterId }
        val config1 = FieldStatsConfig.fromConfig(field1.config)
        val config2 = FieldStatsConfig.fromConfig(field2.config)

        // 필터 적용: 대상 캐릭터 ID 세트 구하기
        val targetCharIds = if (filterField != null && filterValue != null) {
            val filterConfig = FieldStatsConfig.fromConfig(filterField.config)
            s.fieldValues.filter { it.fieldDefinitionId == filterField.id }
                .filter { fv ->
                    val vals = getFieldValues(filterField, fv.value, filterConfig)
                    filterValue in vals
                }
                .map { it.characterId }
                .toSet()
        } else {
            s.characters.map { it.id }.toSet()
        }

        // 교차표 구성
        val crossTable = mutableMapOf<String, MutableMap<String, Int>>()
        var filteredCount = 0

        for (charId in targetCharIds) {
            val charValues = valuesByChar[charId] ?: continue
            val val1Raw = charValues.find { it.fieldDefinitionId == field1Id }?.value ?: continue
            val val2Raw = charValues.find { it.fieldDefinitionId == field2Id }?.value ?: continue

            val values1 = getFieldValues(field1, val1Raw, config1)
            val values2 = getFieldValues(field2, val2Raw, config2)

            for (v1 in values1) {
                for (v2 in values2) {
                    crossTable.getOrPut(v1) { mutableMapOf() }
                        .merge(v2, 1) { old, new -> old + new }
                }
            }
            filteredCount++
        }

        return CrossAnalysisResult(
            field1Name = field1.name,
            field2Name = field2.name,
            filterFieldName = filterField?.name,
            filterValue = filterValue,
            crossTable = crossTable,
            totalCount = s.characters.size,
            filteredCount = filteredCount
        )
    }

    // ===== 데이터 현황 (신규 - 기존 여러 compute 통합) =====
    fun computeDataOverview(s: StatsSnapshot): DataOverviewStats {
        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val charFieldValuesByChar = s.fieldValues.groupBy { it.characterId }
        val valuesByFieldDef = s.fieldValues.filter { it.value.isNotBlank() }.groupBy { it.fieldDefinitionId }

        // 그룹별 필드 완성도
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

        // 개별 필드별 완성도
        val fieldCompletionDetails = s.fieldDefinitions.map { fd ->
            val universeNovels = s.novels.filter { it.universeId == fd.universeId }.map { it.id }.toSet()
            val relevantChars = s.characters.filter { it.novelId in universeNovels }
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
            val novelId = char.novelId
            if (novelId == null) return@count true // 작품 미배정 = 미완성으로 간주
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
        val novelMap = s.novels.associateBy { it.id }
        val fieldDefById = s.fieldDefinitions.associateBy { it.id }
        val charFieldValuesByChar = s.fieldValues.groupBy { it.characterId }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }

        // 필드별 값 분포 (이산 값을 가지는 필드 타입)
        val distributionTypes = setOf("SELECT", "GRADE", "MULTI_TEXT", "BODY_SIZE", "TEXT")
        val valuesByFieldDef = s.fieldValues.filter { it.value.isNotBlank() }
            .groupBy { it.fieldDefinitionId }

        val fieldValueDists = s.fieldDefinitions
            .filter { it.type in distributionTypes }
            .mapNotNull { fd ->
                val values = valuesByFieldDef[fd.id] ?: return@mapNotNull null
                val statsConfig = FieldStatsConfig.fromConfig(fd.config)
                val allKeys = values.flatMap { fv ->
                    getFieldValues(fd, fv.value, statsConfig)
                }
                val dist = allKeys.groupBy { it }.mapValues { it.value.size }
                    .entries.sortedByDescending { it.value }.associate { it.key to it.value }
                if (dist.isEmpty()) return@mapNotNull null
                FieldValueDistribution(fd.id, fd.name, fd.type, fd.groupName, dist)
            }

        // NUMBER 타입 필드 통계 요약
        val numberSummaries = s.fieldDefinitions
            .filter { it.type == "NUMBER" }
            .mapNotNull { fd ->
                val values = valuesByFieldDef[fd.id]
                    ?.mapNotNull { it.value.toFloatOrNull() }
                    ?: return@mapNotNull null
                if (values.isEmpty()) return@mapNotNull null
                val sorted = values.sorted()
                val median = if (sorted.size % 2 == 0) {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
                } else sorted[sorted.size / 2]
                NumberFieldSummary(
                    fieldName = fd.name,
                    min = sorted.first(),
                    max = sorted.last(),
                    avg = values.average().toFloat(),
                    median = median,
                    count = values.size,
                    values = sorted
                )
            }

        // 개별 필드별 완성도
        val fieldCompletionDetails = s.fieldDefinitions.map { fd ->
            // 이 필드가 속한 유니버스의 캐릭터들
            val universeNovels = s.novels.filter { it.universeId == fd.universeId }.map { it.id }.toSet()
            val relevantChars = s.characters.filter { it.novelId in universeNovels }
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

    /**
     * 작품별 비교 분석 (원칙 05: 데이터 유기적 연결)
     * 전체 스냅샷(필터 미적용)에서 작품별 통계를 나란히 비교할 수 있도록 한다.
     */
    fun computeCrossNovelComparison(s: StatsSnapshot): CrossNovelComparison {
        val charsByNovel = s.characters.groupBy { it.novelId }
        val eventsByNovel = s.events.groupBy { it.novelId }

        // 전체 복잡도를 한 번만 계산하고 캐릭터 ID로 매핑
        val allComplexities = computeCharacterComplexities(s)
        val complexityById = allComplexities.mapIndexed { i, c -> s.characters[i].id to c }.toMap()

        val entries = s.novels.map { novel ->
            val chars = charsByNovel[novel.id] ?: emptyList()
            val charIds = chars.map { it.id }.toSet()
            val events = eventsByNovel[novel.id] ?: emptyList()

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

            // 자주 쓰인 필드 값 TOP 5 (필드별로 구분하여 집계)
            val fieldDefMap = s.fieldDefinitions.associateBy { it.id }
            val novelFieldValues = s.fieldValues
                .filter { fv -> fv.characterId in charIds && fv.value.isNotBlank() }
            val topValues = novelFieldValues
                .mapNotNull { fv ->
                    val fdName = fieldDefMap[fv.fieldDefinitionId]?.name ?: return@mapNotNull null
                    Triple(fdName, fv.value.trim(), fv)
                }
                .groupBy { "${it.first}:${it.second}" }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }
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

    // ===== 개선 3: 패턴 감지 & 서사적 인사이트 =====

    fun detectPatterns(s: StatsSnapshot): List<PatternInsight> {
        val insights = mutableListOf<PatternInsight>()

        // 필드별 분포 패턴 감지
        val fieldsByKey = s.fieldDefinitions.groupBy { Pair(it.key, it.type) }

        for ((keyType, fieldDefs) in fieldsByKey) {
            // 동일 키의 모든 세계관 필드 값 합산
            val allFieldDefIds = fieldDefs.map { it.id }.toSet()
            val rawValues = s.fieldValues.filter { it.fieldDefinitionId in allFieldDefIds }
            if (rawValues.isEmpty()) continue

            val fd = fieldDefs.first()
            val statsConfig = FieldStatsConfig.fromConfig(fd.config)
            val allValues = rawValues.flatMap { getFieldValues(fd, it.value, statsConfig) }
            if (allValues.isEmpty()) continue

            val dist = allValues.groupBy { it }.mapValues { it.value.size }
            val total = allValues.size
            val fieldName = fd.name

            // 패턴 1: 편중 (단일 값 60%+)
            val topEntry = dist.maxByOrNull { it.value }
            if (topEntry != null) {
                val topPct = topEntry.value * 100f / total
                if (topPct >= 60f) {
                    insights.add(PatternInsight(
                        type = PatternType.DOMINANCE,
                        severity = if (topPct >= 80f) PatternSeverity.HIGH else PatternSeverity.MEDIUM,
                        title = "${fieldName}: '${topEntry.key}' 편중",
                        description = "${fieldName} 분포에서 '${topEntry.key}'이(가) ${String.format("%.0f", topPct)}%를 차지하여 편중되어 있습니다.",
                        suggestion = "다양성을 위해 다른 ${fieldName} 값을 가진 캐릭터 추가를 고려해보세요.",
                        fieldDefId = fd.id
                    ))
                }
            }

            // 패턴 2: 균형 (모든 값 10~30% 사이)
            if (dist.size >= 3) {
                val pcts = dist.values.map { it * 100f / total }
                val allBalanced = pcts.all { it in 10f..35f }
                if (allBalanced) {
                    insights.add(PatternInsight(
                        type = PatternType.BALANCE,
                        severity = PatternSeverity.LOW,
                        title = "${fieldName}: 균형 양호",
                        description = "${fieldName}의 값이 ${dist.size}개 범주에 고르게 분포되어 있습니다.",
                        suggestion = "",
                        fieldDefId = fd.id
                    ))
                }
            }

            // 패턴 3: 이상치 (1개 값만 가진 희소 항목이 전체의 2% 미만이고, 나머지는 밀집)
            if (total >= 10) {
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
                        fieldDefId = fd.id
                    ))
                }
            }
        }

        // 패턴 4: 사건 연도 집중 (특정 10년에 50%+ 집중)
        if (s.events.size >= 5) {
            val byDecade = s.events.groupBy { (it.year / 10) * 10 }
            val totalEvents = s.events.size
            val topDecade = byDecade.maxByOrNull { it.value.size }
            if (topDecade != null) {
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
            if (years.size >= 2) {
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
        if (s.novels.size >= 2) {
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
                    val novel = s.novels.find { n ->
                        s.universes.find { it.id == fd.universeId }?.let { uni ->
                            n.universeId == uni.id
                        } == true
                    } ?: continue
                    val fvs = s.fieldValues.filter { it.fieldDefinitionId == fd.id }
                    val statsConfig = FieldStatsConfig.fromConfig(fd.config)
                    val values = fvs.flatMap { getFieldValues(fd, it.value, statsConfig) }
                    val topVal = values.groupBy { it }.maxByOrNull { it.value.size }
                    if (topVal != null && values.isNotEmpty()) {
                        val pct = topVal.value.size * 100f / values.size
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

        // severity 기준 정렬 (HIGH → MEDIUM → LOW)
        return insights.sortedBy { it.severity.ordinal }
    }

    // ===== 개선 6: 차트 탭 → 캐릭터 목록 =====

    /**
     * 특정 필드의 특정 값을 가진 캐릭터 목록 반환.
     * getFieldValues() 로직을 재활용하여 파싱된 값 기준으로 매칭.
     */
    fun getCharactersByFieldValue(
        s: StatsSnapshot,
        fieldDefId: Long,
        targetValue: String
    ): List<FieldValueCharacter> {
        val fd = s.fieldDefinitions.find { it.id == fieldDefId } ?: return emptyList()
        val statsConfig = FieldStatsConfig.fromConfig(fd.config)
        val charMap = s.characters.associateBy { it.id }

        val result = mutableListOf<FieldValueCharacter>()
        val rawValues = s.fieldValues.filter { it.fieldDefinitionId == fieldDefId }

        for (fv in rawValues) {
            val parsedValues = getFieldValues(fd, fv.value, statsConfig)
            if (parsedValues.any { it == targetValue }) {
                val char = charMap[fv.characterId] ?: continue
                val images = char.imageUris.split(",").filter { it.isNotBlank() }
                result.add(
                    FieldValueCharacter(
                        characterId = char.id,
                        characterName = char.name,
                        fieldValue = fv.value,
                        imageUri = images.firstOrNull()
                    )
                )
            }
        }
        return result.sortedBy { it.characterName }
    }

    /**
     * 캐릭터 ID 집합에 대해 다른 필드의 분포를 분석 (하위 그룹 분석).
     */
    fun computeSubgroupAnalysis(
        s: StatsSnapshot,
        characterIds: Set<Long>,
        targetFieldDefId: Long
    ): SubgroupAnalysis? {
        val fd = s.fieldDefinitions.find { it.id == targetFieldDefId } ?: return null
        val statsConfig = FieldStatsConfig.fromConfig(fd.config)

        val rawValues = s.fieldValues.filter {
            it.fieldDefinitionId == targetFieldDefId && it.characterId in characterIds
        }

        val allValues = mutableListOf<String>()
        for (fv in rawValues) {
            allValues.addAll(getFieldValues(fd, fv.value, statsConfig))
        }

        val distribution = allValues.groupBy { it }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }
            .take(15)
            .associate { it.key to it.value }

        return SubgroupAnalysis(
            targetFieldName = fd.name,
            distribution = distribution,
            totalCount = characterIds.size
        )
    }
}
