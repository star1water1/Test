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
    // 신규: 인사이트 요약
    val mostActiveNovel: String?,
    val mostConnectedChar: String?,
    val dataHealthIssueCount: Int,
    val avgFieldCompletion: Float,
    val recentActivityCount: Int // 최근 7일 생성/수정 캐릭터
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
    val totalScore: Float
)

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
    // 신규
    val networkDensity: Float,
    val descriptionCompleteness: Float, // 설명이 있는 관계 비율 (%)
    val emptyDescriptionCount: Int,
    val reciprocalPairCount: Int, // 양방향 관계 쌍 수
    val avgConnectionsPerChar: Float
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
    val lowPrecisionEvents: Int // 년도만 있는 사건 수
)

// ===== 커스텀 필드 분석 (레거시 - 호환용) =====
data class FieldAnalysisStats(
    val fieldValueDistributions: List<FieldValueDistribution>,
    val numberFieldSummaries: List<NumberFieldSummary>,
    val fieldCompletionByField: List<FieldCompletionDetail>,
    val stateChangesByField: Map<String, Int>
)

data class FieldValueDistribution(
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
    val count: Int
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
    val filledCount: Int
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

class StatsDataProvider(private val app: NovelCharacterApp) {

    suspend fun loadSnapshot(): StatsSnapshot {
        val db = app.database
        return StatsSnapshot(
            characters = app.characterRepository.getAllCharactersList(),
            novels = app.novelRepository.getAllNovelsList(),
            universes = app.universeRepository.getAllUniversesList(),
            events = app.timelineRepository.getAllEventsList(),
            relationships = app.characterRepository.getAllRelationships(),
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
        return s.copy(
            characters = s.characters.filter { it.novelId == novelId },
            novels = listOf(novel),
            events = s.events.filter { it.novelId == novelId },
            relationships = s.relationships.filter { it.characterId1 in charIds || it.characterId2 in charIds },
            tags = s.tags.filter { it.characterId in charIds },
            stateChanges = s.stateChanges.filter { it.characterId in charIds },
            fieldDefinitions = s.fieldDefinitions.filter { it.universeId == novel.universeId },
            fieldValues = s.fieldValues.filter { it.characterId in charIds },
            crossRefs = s.crossRefs.filter { it.characterId in charIds || it.eventId in eventIds }
        )
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
            recentActivityCount = recentCount
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

        val fieldCompletions = s.characters.mapNotNull { char ->
            val novelId = char.novelId ?: return@mapNotNull null
            val novel = novelMap[novelId] ?: return@mapNotNull null
            val totalFields = universeFieldCounts[novel.universeId] ?: return@mapNotNull null
            if (totalFields == 0) return@mapNotNull null
            val filled = charFieldCounts[char.id] ?: 0
            char.name to (filled.toFloat() / totalFields * 100f)
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

        // 신규: 캐릭터 복잡도 스코어
        val complexityScores = s.characters.map { char ->
            val relCnt = relCount[char.id] ?: 0
            val evtCnt = eventCountMap[char.id] ?: 0
            val completion = fieldCompletions.find { it.first == char.name }?.second ?: 0f
            val stateChangeCnt = stateChangesByChar[char.id]?.size ?: 0
            val score = relCnt * 2f + evtCnt * 1.5f + completion * 0.3f + stateChangeCnt * 1f
            CharacterComplexity(char.name, relCnt, evtCnt, completion, stateChangeCnt, score)
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
            fieldCompletionRates = fieldCompletions,
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
        val maxPossible = if (n > 1) n * (n - 1) / 2f else 1f
        val density = s.relationships.size / maxPossible

        // 신규: 설명 완성도
        val emptyDescCount = s.relationships.count { it.description.isBlank() }
        val descCompleteness = if (s.relationships.isNotEmpty()) {
            (s.relationships.size - emptyDescCount).toFloat() / s.relationships.size * 100f
        } else 0f

        // 신규: 양방향 관계 쌍 (A→B, B→A 동일 유형)
        val pairSet = mutableSetOf<String>()
        var reciprocalCount = 0
        s.relationships.forEach { rel ->
            val key = "${minOf(rel.characterId1, rel.characterId2)}-${maxOf(rel.characterId1, rel.characterId2)}-${rel.relationshipType}"
            if (!pairSet.add(key)) reciprocalCount++
        }

        // 신규: 캐릭터당 평균 연결
        val avgConn = if (s.characters.isNotEmpty()) {
            connCount.values.sum().toFloat() / s.characters.size
        } else 0f

        return RelationshipStats(
            typeDistribution = typeDist,
            topConnectedChars = topConnected,
            isolatedCharacters = isolated,
            networkDensity = density,
            descriptionCompleteness = descCompleteness,
            emptyDescriptionCount = emptyDescCount,
            reciprocalPairCount = reciprocalCount,
            avgConnectionsPerChar = avgConn
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

        // 중복 태그
        val dupTags = s.tags.groupBy { it.tag.lowercase().trim() }
            .filter { it.value.size > 1 && it.value.map { t -> t.tag }.distinct().size > 1 }
            .keys.toList()

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
            lowPrecisionEvents = lowPrecision
        )
    }

    // ===== 필드 인사이트 (신규) =====
    fun computeFieldInsights(s: StatsSnapshot): List<FieldInsightResult> {
        val novelMap = s.novels.associateBy { it.id }
        val valuesByFieldDef = s.fieldValues.filter { it.value.isNotBlank() }
            .groupBy { it.fieldDefinitionId }

        return s.fieldDefinitions.mapNotNull { fd ->
            val statsConfig = FieldStatsConfig.fromConfig(fd.config)
            if (!statsConfig.enabled) return@mapNotNull null
            if (fd.type == "CALCULATED") return@mapNotNull null

            val rawValues = valuesByFieldDef[fd.id] ?: emptyList()

            // 해당 유니버스 캐릭터 수
            val universeNovels = s.novels.filter { it.universeId == fd.universeId }.map { it.id }.toSet()
            val totalCount = s.characters.count { it.novelId in universeNovels }
            val filledCount = rawValues.size

            val analysisResults = statsConfig.analyses.map { entry ->
                when (entry.type) {
                    FieldStatsConfig.StatsType.DISTRIBUTION -> {
                        val dist = computeFieldDistribution(fd, rawValues, statsConfig, entry.limit)
                        AnalysisResult(entry, dist, null)
                    }
                    FieldStatsConfig.StatsType.NUMERIC -> {
                        val numericValues = rawValues.mapNotNull { it.value.toFloatOrNull() }
                        val summary = if (numericValues.isNotEmpty()) {
                            computeNumericSummary(numericValues, statsConfig.binning)
                        } else null
                        AnalysisResult(entry, null, summary)
                    }
                    FieldStatsConfig.StatsType.RANKING -> {
                        val dist = computeFieldDistribution(fd, rawValues, statsConfig, entry.limit)
                        val ranked = dist.entries.sortedByDescending { it.value }
                            .take(entry.limit)
                            .associate { it.key to it.value }
                        AnalysisResult(entry, ranked, null)
                    }
                }
            }

            FieldInsightResult(fd, statsConfig, analysisResults, totalCount, filledCount)
        }
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
            fd.type == "BODY_SIZE" -> {
                // BODY_SIZE 다각적 분석: 전체 값 + 개별 구성요소
                val separator = try {
                    org.json.JSONObject(fd.config).optString("separator", "-")
                } catch (_: Exception) { "-" }
                val parts = rawValue.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
                // 전체 값을 하나의 항목으로, 그리고 각 파트도 개별로
                val result = mutableListOf(rawValue.trim())
                if (parts.size >= 2) {
                    parts.forEachIndexed { idx, part ->
                        val label = when (idx) {
                            0 -> "키:$part"
                            1 -> "체중:$part"
                            else -> "사이즈${idx + 1}:$part"
                        }
                        result.add(label)
                    }
                }
                result
            }
            format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST ->
                rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            fd.type == "MULTI_TEXT" ->
                rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(rawValue.trim())
        }

        // Step 2: 값 라벨 매핑 적용
        val labeled = splitValues.map { statsConfig.applyLabel(it) }

        // Step 3: NUMBER + binning
        if (fd.type == "NUMBER" && statsConfig.binning != null && statsConfig.binning.mode == "custom") {
            return labeled.mapNotNull { v ->
                v.toFloatOrNull()?.let { statsConfig.applyBinning(it) }
            }
        }

        return labeled
    }

    private fun computeNumericSummary(
        values: List<Float>,
        binning: FieldStatsConfig.BinningConfig?
    ): NumericSummaryData {
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
            val novelId = char.novelId ?: return@count false
            val novel = novelMap[novelId] ?: return@count false
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

        // 필드별 값 분포 (SELECT, GRADE, MULTI_TEXT, BODY_SIZE 타입)
        val distributionTypes = setOf("SELECT", "GRADE", "MULTI_TEXT", "BODY_SIZE")
        val valuesByFieldDef = s.fieldValues.filter { it.value.isNotBlank() }
            .groupBy { it.fieldDefinitionId }

        val fieldValueDists = s.fieldDefinitions
            .filter { it.type in distributionTypes }
            .mapNotNull { fd ->
                val values = valuesByFieldDef[fd.id] ?: return@mapNotNull null
                val dist = values.groupBy { it.value }.mapValues { it.value.size }
                    .entries.sortedByDescending { it.value }.associate { it.key to it.value }
                if (dist.isEmpty()) return@mapNotNull null
                FieldValueDistribution(fd.name, fd.type, fd.groupName, dist)
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
                    count = values.size
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
}
