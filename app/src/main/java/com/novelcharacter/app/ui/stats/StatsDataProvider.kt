package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.*
import com.novelcharacter.app.util.CompletionRate
import com.novelcharacter.app.util.FactionStanding
import com.novelcharacter.app.util.DuelScoreIndex
import com.novelcharacter.app.util.CompletionWeights
import com.novelcharacter.app.util.FieldValueMatchSpec
import com.novelcharacter.app.util.FieldValueSorter
import com.novelcharacter.app.util.FieldValueMatcher
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.FieldValueTypeMismatch
import com.novelcharacter.app.util.FormulaEvaluator
import com.novelcharacter.app.util.NumericBinning
import com.novelcharacter.app.util.RequiredFieldGaps
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
    // 작품 커스텀 필드 (확-3) — 같은 원칙. 종류를 만들 수 있게 해 놓고 통계에서 빼면
    // 그 필드는 '있는데 분석되지 않는 필드'가 된다(원칙 02 위반의 가장 흔한 형태).
    val novelFieldDefinitions: List<FieldDefinition> = emptyList(),
    val novelFieldValues: List<NovelFieldValue> = emptyList(),
    // 값 데이터 라이브러리 — 별칭 접기·표시 라벨·카테고리의 단일 소스 (구 valueLabels/valueCategories 대체)
    val valueEntries: List<com.novelcharacter.app.data.model.FieldValueEntry> = emptyList(),
    /**
     * 대결 축 목록 (B-117) — 순위 화면이 *"무엇으로 줄 세울까"*의 선택지에 싣는다.
     *
     * **싣는 것은 축이지 판이 아니다.** 판은 한 축에서만 수만 행이 될 수 있어 스냅샷에 담으면
     * 그것을 순회하는 계산이 스냅샷 하나에 통째로 붙는다 —
     * `scalability_performance` 7장 4단계 3번이 묻는 바로 그 질문이고, 이 판의 답은
     * **"목록은 더하되 계산은 더하지 않는다"**이다. 점수는 사용자가 축 하나를 고른 뒤에
     * [com.novelcharacter.app.data.repository.DuelRepository.scoresOf]가 그때 낸다.
     */
    val duelAxes: List<com.novelcharacter.app.data.model.DuelAxis> = emptyList(),
    /**
     * 완성도 필수 가중 (B-100). [StatsDataProvider.loadSnapshot]이 설정에서 읽어 싣는다 —
     * 계산 함수들이 `Context`를 모르게 하기 위해서다(순수 하네스가 그대로 돈다).
     * 필터본은 `copy`로 그대로 물려받는다.
     */
    val completionWeights: CompletionWeights = CompletionWeights.DEFAULT,
    /**
     * "작품 미배정" 스코프 표시 — novels/universes가 비므로 캐릭터 모수·필드 완성도를
     * novelId 경유 대신 스냅샷 자체(캐릭터 전체·보존 정의) 기준으로 계산해야 한다.
     * [StatsDataProvider.filterByNovel]의 sentinel 분기만 true로 만든다.
     */
    val unassignedScope: Boolean = false,
    /**
     * 이 스코프에서 **산출할 수 없는** 계산(CALCULATED) 필드의 수 (B-30).
     *
     * 0이 아니면 화면이 *"작품 미배정이라 계산 필드는 산출할 수 없다"*를 한 줄로 알린다.
     * **값을 만들어 내지 않는다** — 확정 7-4가 기각한 쪽(필드값으로 세계관 역추적)은 참조
     * 필드가 빠지면 수식이 **조용히 다른 값**을 내어, 틀린 값이 맞는 값처럼 보인다.
     *
     * **왜 '빈 칸'이 아니라 '고지'여야 하는가:** 계산 필드는 저장 행이 없어
     * [StatsDataProvider.filterByNovelUnassigned]의 *참조된 정의만 남긴다*는 규칙에
     * 걸려 **정의째 사라진다.** 그래서 사용자가 보는 것은 빈 값이 아니라 **필드의 부재**이고,
     * 부재는 *"값이 없구나"*가 아니라 *"내가 안 만들었나?"*로 읽힌다.
     * 같은 처분이 이미 옆에 있다 — [CharacterComplexity.hasNovelAssignment]와
     * `fieldCompletionRate: Float?`가 완성도에서 *"작품 미배정으로 산출 불가"*를 정직하게
     * 고지한다. **완성도는 이미 말하고 있었고 계산 필드만 말없이 빠졌다.**
     */
    val calculatedUnavailable: Int = 0
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

/**
 * 타입과 맞지 않게 된 값 하나 (B-156).
 *
 * **어느 칸인지까지 말한다.** 종전에 이 부류를 말하는 자리는 전파 미리보기의
 * *"값 N개가 새 타입과 맞지 않게 됩니다"* 하나였고, 그것은 **창을 닫으면 사라지는 고지**에
 * 세계관별 개수뿐이라 고치러 갈 수가 없었다(개발 의도 2번 — 검증 → 알림 → **바로잡을 경로**).
 */
data class TypeMismatchedValue(
    /** 값이 붙은 대상의 종류 — [FieldDefinition.ENTITY_CHARACTER] 등. */
    val ownerType: String,
    /** 대상 id. 캐릭터라면 화면이 이 id로 상세를 연다. */
    val ownerId: Long,
    val ownerName: String,
    /**
     * 값이 매달린 필드 정의 id — **편집 창이 열린 뒤 그 칸을 잡는 열쇠다**(B-198).
     *
     * 이름으로는 못 잡는다: 같은 이름의 필드가 구역마다 따로 설 수 있고, 창이 든 칸의
     * 열쇠는 언제나 정의 id다(`eventFieldInputMap`·`NovelFieldSection.inputs`).
     */
    val fieldDefId: Long,
    val fieldName: String,
    /** [FieldType] 이름. 화면이 *"숫자 필드인데"*를 말하는 근거다. */
    val fieldType: String,
    val value: String,
    val reason: FieldValueTypeMismatch.Reason
)

/**
 * 타입 불일치 목록의 **표시 상한 적용** (R-14 · S-17과 같은 분업).
 *
 * 계산은 전량을 들고 다니고([DataHealthStats.typeMismatchedValues]) 자르는 일은 표시 직전에
 * 여기서 한 번만 한다 — 계산이 자르면 위에 뜨는 총 건수와 목록이 서로 다른 모집단을 말한다.
 *
 * **상한을 축마다 따로 두는 것이 이 함수의 요점이다.** 통짜 상한이면 캐릭터 값 하나가
 * 무더기로 어긋났을 때 그것만으로 자리가 차서 **사건·작품 축이 통째로 안 보인다** — 이 판이
 * 없애려는 바로 그 상태(*"일일이 확인하지 않으면 존재를 알 수 없는 데이터"*, 원칙 04)를
 * 표시 계층에서 다시 만드는 꼴이다.
 */
object TypeMismatchList {

    /**
     * 축(캐릭터·사건·작품)마다의 표시 상한. 문구는 이 상수로 채운다(R-14).
     *
     * **버려지는 것은 같은 축의 뒷부분**이고, 개수는 축마다 [View.hiddenByOwnerType]가 든다.
     * 전체 건수는 목록 위 요약이 여전히 전량으로 말하므로 *존재*가 사라지지는 않는다.
     */
    const val DISPLAY_LIMIT_PER_OWNER = 50

    data class View(
        val shown: List<TypeMismatchedValue>,
        /** 축 → 잘린 수. 빈 맵이면 전부 보이고 있다. */
        val hiddenByOwnerType: Map<String, Int>
    )

    fun view(
        all: List<TypeMismatchedValue>,
        limitPerOwnerType: Int = DISPLAY_LIMIT_PER_OWNER
    ): View {
        val shown = mutableListOf<TypeMismatchedValue>()
        val hidden = LinkedHashMap<String, Int>()
        // groupBy는 첫 등장 순서를 지킨다 — 수집 순서(캐릭터 → 사건 → 작품)가 그대로 남는다.
        all.groupBy { it.ownerType }.forEach { (ownerType, rows) ->
            shown.addAll(rows.take(limitPerOwnerType))
            val over = rows.size - limitPerOwnerType
            if (over > 0) hidden[ownerType] = over
        }
        return View(shown, hidden)
    }
}

/**
 * **입력 현황** — "아직 안 썼다"이지 잘못이 아닌 것 (B-59).
 *
 * 이 셋은 종전에 [DataHealthStats]에 평평하게 얹혀 있었고, 그래서 통계 메인 카드의
 * *"발견 사항 N건"*이 **메모를 안 쓴 캐릭터 수를 문제로 셌다.** CLAUDE.md 통계 Don't의 첫 줄
 * (*"'데이터 입력량'만 보여주는 통계"*)에 정면으로 걸리는 자리이며, 해악은 문구가 아니라
 * **묻힘**이다 — 진짜 점검거리(타입이 안 맞는 값 몇 개)가 입력량 수백 건에 섞여 보이지 않는다.
 *
 * **지우지 않고 가른 이유**(B-59가 *"삭제가 아니라 재배치"*라 적은 그대로): 이미지·메모·별명이
 * 비었다는 사실 자체는 쓸모가 있다. 잘못인 것은 그것을 **문제로 세는 것**이다.
 *
 * 중첩으로 둔 것은 주석이 아니라 **구조로** 가르기 위해서다 — 평평하게 두면 다음 사람이
 * 다시 합계에 더한다. 이제 더하려면 `inputProgress.`를 지나야 하고, 그 이름이 무엇인지 말한다.
 */
data class DataInputProgress(
    val noImageChars: List<String>,
    val noMemoChars: List<String>,
    val noAnotherNameChars: List<String>
)

data class DataHealthStats(
    // ── 점검: 앱이 "틀렸다" 또는 "끊겼다"고 말할 수 있는 것 ──
    /** 타입과 맞지 않게 된 값 (B-156). 캐릭터·사건·작품 세 축을 모두 훑는다. */
    val typeMismatchedValues: List<TypeMismatchedValue>,
    val incompleteFieldChars: List<Pair<String, Float>>,
    val isolatedChars: List<String>,
    val unlinkedChars: List<String>,
    val duplicateTags: List<String>,
    val emptyDescRelationships: Int,
    val fieldCompletionByGroup: Map<String, Float>,
    val lowPrecisionEvents: Int, // 년도만 있는 사건 수
    val noNovelChars: List<String> = emptyList(), // 작품 미배정 캐릭터
    // ── 입력 현황: 문제가 아니다 (B-59) ──
    val inputProgress: DataInputProgress
) {
    /**
     * 통계 메인 건강도 카드가 말하는 **"발견 사항 N건"** (B-59).
     *
     * 세는 기준은 하나다 — **앱이 *"틀렸다"* 또는 *"끊겼다"*고 말할 수 있는가.**
     *
     * | | 세는가 | 왜 |
     * |---|---|---|
     * | 타입이 안 맞는 값 | ✅ | 값이 수식에서 0으로 읽힌다 — 틀렸다 |
     * | 필드 미입력률·관계 고립·사건 미연계·관계 설명 없음 | ✅ | 연결이 끊겼거나 산출이 안 된다 |
     * | **중복 태그·작품 미배정** | ✅ | 둘 다 실제 결함이고 도우미가 이미 고칠 카드를 세운다 |
     * | 시간 정밀도 낮은 사건 | ❌ | 연도만 적힌 사건은 **틀린 것이 아니다.** 아래 요약이 따로 말한다 |
     * | [inputProgress] | ❌ | *"아직 안 썼다"*이지 잘못이 아니다 |
     *
     * **왜 [inputProgress]를 뺐는가:** 종전에는 이미지·메모 미작성이 이 합계에 들어가
     * *"발견 사항 300건"*의 대부분이 *"아직 안 썼다"*였고, 그래서 진짜 점검거리(타입이
     * 안 맞는 값 몇 개)가 그 안에 묻혔다 — 상한도 필터도 없는 화면에서 **묻히는 것은 곧
     * 없는 것**이다(원칙 04).
     *
     * **왜 중복 태그·작품 미배정을 더했는가:** 상세 화면이 점검거리로 보여 주는데 카드가
     * 안 세면, 카드가 약속한 범위와 그 카드에서 뻗는 경로의 범위가 갈린다(R-15). 종전에
     * 실제로 갈려 있었고, *"입력량은 세면서 진짜 결함은 안 세는"* 모양이라 B-59가 지적한
     * 것과 같은 잘못의 반대편이다.
     *
     * **화면이 아니라 여기 있는 이유:** 합계가 Fragment 안의 덧셈이면 이 표를 잠글 시험이
     * 없다. 실제로 그 자리가 그렇게 어긋난 채 있었다.
     */
    val issueCount: Int
        get() = typeMismatchedValues.size +
            incompleteFieldChars.size +
            isolatedChars.size +
            unlinkedChars.size +
            emptyDescRelationships +
            duplicateTags.size +
            noNovelChars.size
}

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
    /**
     * 드릴다운 대상 필드 (B-39). 종전에는 이 요약이 **이름 문자열만** 들고 있어서
     * 히스토그램에 리스너를 달아도 어느 필드를 조회할지 정할 수 없었다 —
     * BODY_SIZE 파트 요약의 이름은 `"신체 — 키"`처럼 조립된 것이라 되짚을 수도 없다.
     */
    val fieldDefId: Long = 0,
    /**
     * 구간 라벨 → 건수. 구간 생성은 [NumericBinning]이 단일 소스다 (B-39).
     *
     * 종전에는 화면이 값 목록을 받아 **자체 8등분**을 했다. 그래서 같은 필드가 인사이트
     * 화면(5등분)과 이 화면(8등분)에서 다른 모양으로 보였고, 폭이 좁으면 라벨이 겹쳐
     * 맵 키가 충돌했다(그 부류를 막으려고 [NumericBinning]이 만들어졌다).
     */
    val histogram: Map<String, Int> = emptyMap(),
    /** 라벨 → 드릴다운 매치 스펙. 근거는 [NumericSummaryData.matchSpecs]와 같다. */
    val matchSpecs: Map<String, FieldValueMatchSpec> = emptyMap()
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
    val numericSummary: NumericSummaryData?,
    /**
     * 분포 라벨 → 드릴다운 매치 스펙. **`null`이면 라벨이 곧 값이다** — 표시 계층이
     * [FieldValueMatchSpec.Values]로 만들어도 맞는 경우이고, 그것이 지금까지의 전부였다.
     *
     * 자동 구간으로 접은 분포(B-196)에서는 라벨이 **구간**이라 값이 아니다. 스펙을 여기까지
     * 실어 나르지 않으면 그 조각을 눌렀을 때 `Values("160~170")`으로 조회해 **어떤 입력에서도
     * 0명**이 나온다(S-16이 BODY_SIZE 파이에서 겪은 그 결함이고, B-196 등재가 *"안 고치면 그
     * 조각이 다시 0명이 된다"*고 미리 적어 둔 함정이다).
     *
     * 구간 스펙이 [FieldValueMatchSpec.NumericPartRange]가 아니라 **접힌 값들의
     * [FieldValueMatchSpec.Values]**인 것이 요점이다 — 여기서 접는 대상은 원문이 아니라
     * **이미 파싱된 통계 키**라(라벨·카테고리가 적용된 뒤다) 어느 키가 어느 구간에 들었는지를
     * 접는 쪽이 정확히 안다. 그 집합을 그대로 실으면 라벨·카테고리 설정이 살아 있는 필드에서도
     * 조각 수치와 목록 인원이 어긋나지 않는다.
     */
    val distributionSpecs: Map<String, FieldValueMatchSpec>? = null,
    /**
     * 값 종류가 표시 상한을 넘쳐 **자동 구간으로 접었는가** (B-196 · 확정 15장 3번).
     *
     * 표시 계층이 둘을 위해 본다: ⓐ 구간 순서를 건수순으로 재정렬하지 않기 위해(인접 구간이
     * 흩어지면 '어디에 몰렸는가'를 읽을 수 없다 — 순서 자체가 정보다) ⓑ **접었다고 말하기**
     * 위해(R-14 — 상한은 감추는 장치가 아니라 접는 장치다).
     */
    val autoBinned: Boolean = false,
    /**
     * 접기 **전**의 값 종류 수 — [autoBinned]가 참일 때만 뜻이 있다(그 밖에는 0).
     *
     * 고지가 *"값이 몇 종이라 묶었다"*를 말하려면 이 수가 필요한데, [distributionData]는 접힌
     * 뒤라 구간 수만 들고 있다. 접는 쪽만 아는 값이므로 함께 실어 보낸다.
     */
    val preFoldKinds: Int = 0
)

data class NumericSummaryData(
    val min: Float,
    val max: Float,
    val avg: Float,
    val median: Float,
    val stdDev: Float,
    val histogram: Map<String, Int>,
    /**
     * 히스토그램 막대 라벨 → 드릴다운 매치 스펙 (B-39).
     *
     * 구간 라벨은 **계산 결과**라 저장값과 같을 수 없다. 라벨을 그대로 매칭 키로 넘기면
     * 어떤 입력에서도 0명이 나온다(S-16이 BODY_SIZE 파이에서 겪은 그 결함) — 그래서
     * 막대를 만든 구간 규칙 자체를 [FieldValueMatchSpec.NumericPartRange]로 실어 보낸다.
     * 자동 구간과 사용자 구간이 **같은 종류의 스펙**을 쓰는 것도 요점이다: 사용자 구간은
     * 라벨 매칭으로도 우연히 동작하지만(그쪽만 `getFieldValues`가 라벨을 돌려준다)
     * BODY_SIZE 파트에서는 그 우연이 성립하지 않아 한쪽만 조용히 죽는다.
     */
    val matchSpecs: Map<String, FieldValueMatchSpec> = emptyMap()
)

// ===== 교차 분석 (신규) =====

/**
 * 교차분석의 집계 단위(축). 셀 값이 "무엇의 개수"인지를 정한다 (B-4).
 *
 * 캐릭터 축과 사건 축은 **한 표에 섞이지 않는다** — 섞으면 셀의 의미가 무너지기 때문이다.
 * 축이 다른 필드를 함께 고른 경우는 조용히 버리지 않고 [CrossAxisResolution.Mismatch]로 고지한다.
 */
enum class CrossAxis { CHARACTER, EVENT }

/**
 * 드릴다운·하위 그룹 분석이 다루는 **대상 축** — 확-3에서 셋이 됐다.
 *
 * 종전에는 `isEventAxis: Boolean` 하나가 축을 날랐다. 축이 둘일 때는 성립했지만 셋이 되는
 * 순간 "사건이 아니면 캐릭터"라는 전제가 **작품을 캐릭터로 흘려보낸다** — 그러면 조각을
 * 눌렀을 때 0명짜리 빈 시트가 뜨고, 그것이 S-9가 사건 축에서 겪은 결함 그대로다.
 * 종류로 갈리는 분기는 한 자리에 모은다(R-29).
 */
enum class StatsEntityAxis {
    CHARACTER, EVENT, NOVEL;

    fun definitionsIn(s: StatsSnapshot): List<FieldDefinition> = when (this) {
        CHARACTER -> s.fieldDefinitions
        EVENT -> s.eventFieldDefinitions
        NOVEL -> s.novelFieldDefinitions
    }
}

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
    val healthWarnings: HealthWarnings,
    /**
     * 이 스코프에서 완성도 가중이 실제로 걸리는 칸의 수 (B-100).
     * 0이면 화면이 *"필수로 표시한 칸이 없어 모든 칸을 같게 셉니다"*라고 말한다 —
     * 가중 설정을 만져도 숫자가 안 움직이는 이유를 사용자가 알아야 한다(원칙 04).
     */
    val requiredSlotCount: Int = 0,
    /** 지금 적용 중인 필수 배수 — 화면이 "몇 배로 세고 있는가"를 말할 때 쓴다. */
    val requiredWeight: Float = CompletionWeights.DEFAULT_REQUIRED_WEIGHT
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

/**
 * 패턴 카드가 **무엇을 세는가** — 셀 단위(R-13).
 *
 * 종전에는 축이 하나뿐이라 이 구분이 필요 없었다. 사건·작품 필드가 편입되면서
 * 같은 카드 목록에 **단위가 다른 숫자**가 섞이므로, 축을 값으로 들고 다닌다.
 * 그러지 않으면 두 가지가 조용히 어긋난다:
 *
 * - **문구** — "각 1명에게만 해당됩니다"가 사건 카드에 그대로 나간다.
 * - **드릴다운** — 어시스턴트 편향 카드는 `mergedFieldDefIds`를 캐릭터 조회에 넘기는데,
 *   사건 필드 id를 넘기면 캐릭터 정의를 못 찾아 **빈 목록으로 떨어진 뒤 아무 말 없이**
 *   필드 화면 이동으로 대체된다. R-13이 금지하는 *"계산 못 하는 것과 조용히 안 하는 것"*의
 *   그 자리다 — 축을 물어보고 애초에 넘기지 않는다.
 */
enum class PatternAxis(
    /**
     * 카드 제목 접두. **캐릭터만 비어 있다** — 대다수라 접두를 달면 소음이고,
     * 나머지 둘은 접두가 없으면 같은 이름의 필드가 축마다 있을 때 두 카드가 똑같이 보인다
     * (원칙 04 — 일일이 열어봐야 아는 데이터를 만들지 않는다).
     */
    val titlePrefix: String,
    /** 개수의 단위 — "각 1**명**"·"각 1**건**"·"각 1**개**". */
    val countUnit: String,
    /** 제안 문구가 가리키는 대상 — "다른 값을 가진 **캐릭터** 추가". */
    val entityWord: String,
    /**
     * 희소 값을 어떻게 되물을 것인가. 축마다 다른 이유는 *드문 값이 무엇을 뜻하는가*가
     * 다르기 때문이다 — 캐릭터의 드문 값은 대개 일부러 준 개성이고, 사건·작품의 드문 값은
     * 그냥 오타이기 쉽다. 한 문장으로 뭉뚱그리면 캐릭터 쪽 조언이 값을 잃는다.
     */
    val rarityHint: String
) {
    CHARACTER("", "명", "캐릭터", "의도적인 개성 부여인지, 오입력인지 확인하세요."),
    EVENT("사건 · ", "건", "사건", "의도한 구분인지, 오입력인지 확인하세요."),
    NOVEL("작품 · ", "개", "작품", "의도한 구분인지, 오입력인지 확인하세요.")
}

/**
 * 패턴 감지의 **기준값** — 무엇을 편중이라 부를지의 정의 (B-70, 사용자 확정 11번).
 *
 * 종전에는 여섯 유형의 임계값이 전부 `detectPatterns` 본문의 리터럴이었다. 특히 '공백 100년'은
 * **스케일 의존**이라, 수천 년 단위 역법을 쓰는 사용자에게는 개발자가 재단한 숫자가 된다.
 * 어떤 편향을 편향이라 부를지는 작품마다 다르므로 사용자가 정한다(자율성 우선).
 *
 * **유형당 하나다.** 유형 on/off와 같은 축에 놓아야 설정 화면이 한 줄로 읽힌다 —
 * 한 유형이 여러 상수를 쓰는 경우(편중의 '높음' 승급선, 균형의 하한)는 **기준에서 파생**시켜
 * 손잡이가 늘지 않게 했다. 기본값은 현행 동작을 그대로 재현한다.
 *
 * 순수 데이터다 — `Context`를 쥐지 않으므로 순수 JVM 시험이 경계값을 그대로 잰다.
 * 저장·왕복은 [PatternTypePrefs]와 `AppSettingsKeys.STATS_PATTERN_SENSITIVITY`가 맡고,
 * **형식은 [encode]/[decode] 한 벌**이라 두 소비처가 갈리지 않는다(R-45의 취지).
 */
data class PatternThresholds(
    /** 한 값이 이 %를 넘게 차지하면 '편중'. */
    val dominancePercent: Float = 60f,
    /** 모든 값이 이 % 이하로 들어오면 '균형'. */
    val balanceMaxPercent: Float = 35f,
    /** 1건짜리 값들이 전체의 이 % 이하일 때만 '희소'로 짚는다. */
    val outlierSingletonPercent: Float = 5f,
    /** 한 10년대에 이 % 이상 몰리면 '집중'. */
    val clusterPercent: Float = 50f,
    /** 사건이 이 연수를 넘게 비면 '공백'. */
    val absenceGapYears: Int = 100,
    /** 작품 간 캐릭터 수가 이 배수 이상 벌어지면 '불균형'. */
    val crossNovelRatio: Float = 3f
) {
    /**
     * 편중이 '보통'에서 '높음'으로 오르는 지점 — 기준과 100% 사이의 **한가운데**.
     * 기본 60%에서 정확히 80%가 되어 종전 동작과 같다. 손잡이를 하나 더 두지 않는 것은
     * 이 값이 기준을 따라 움직여야 뜻이 유지되기 때문이다(기준을 90%로 올려 두고
     * 승급선만 80%에 남으면 감지된 것이 **전부** '높음'이 된다).
     */
    val dominanceHighPercent: Float get() = dominancePercent + (100f - dominancePercent) / 2f

    /**
     * 균형의 하한 — 몫이 이보다 작은 값이 하나라도 있으면 '고르다'고 하지 않는다.
     * 상한과 달리 **고정**이다: 상한은 *얼마나 고르면 고른 것인가*라는 민감도지만,
     * 하한은 *존재감이 없는 값을 고른 축에 넣지 않는다*는 별개의 진술이라 함께 움직이면
     * 상한을 올릴수록 하한도 따라 올라 되레 덜 관대해진다(민감도가 단조롭지 않게 된다).
     * 상한을 하한 아래로 내린 경우에만 따라 내려간다 — 그러지 않으면 구간이 뒤집힌다.
     */
    val balanceMinPercent: Float get() = minOf(BALANCE_MIN_PERCENT, balanceMaxPercent)

    /** 사람이 엑셀에서 고치는 자리라 `키=값` 쉼표 목록이다 — 집합을 JSON으로 두면 손으로 못 만진다(원칙 04). */
    fun encode(): String = listOf(
        KEY_DOMINANCE to num(dominancePercent),
        KEY_BALANCE to num(balanceMaxPercent),
        KEY_OUTLIER to num(outlierSingletonPercent),
        KEY_CLUSTER to num(clusterPercent),
        KEY_ABSENCE to absenceGapYears.toString(),
        KEY_CROSS_NOVEL to num(crossNovelRatio)
    ).joinToString(",") { "${it.first}=${it.second}" }

    companion object {
        val DEFAULT = PatternThresholds()

        const val BALANCE_MIN_PERCENT = 10f

        const val KEY_DOMINANCE = "dominance"
        const val KEY_BALANCE = "balance"
        const val KEY_OUTLIER = "outlier"
        const val KEY_CLUSTER = "cluster"
        const val KEY_ABSENCE = "absence_years"
        const val KEY_CROSS_NOVEL = "cross_novel_ratio"

        /**
         * 손잡이마다 받아들이는 범위 — **여기가 단일 소스다.**
         *
         * [clamp]와 설정 창의 입력 검증이 각자 숫자를 들면 갈린다: 창이 통과시킨 값을 저장소가
         * 조용히 접어 **적은 것과 저장된 것이 달라진다.** 창에 뜨는 안내 문구("10부터 100까지")도
         * 이 값으로 채운다(R-14 — 숫자를 문구에 박지 않는다).
         *
         * 하한이 0이 아닌 이유: 편중 0%는 *모든 값이 편중*이라 카드가 필드 수만큼 쏟아져
         * 화면이 통째로 무의미해진다. 상한이 크게 열린 것(공백 100만 년)은 반대 이유다 —
         * 역법 스케일은 작품마다 다르고 앱이 재단할 자리가 아니다.
         */
        val DOMINANCE_RANGE = 10f..100f
        val BALANCE_RANGE = 10f..100f
        val OUTLIER_RANGE = 1f..100f
        val CLUSTER_RANGE = 10f..100f
        val ABSENCE_YEARS_RANGE = 1..1_000_000
        val CROSS_NOVEL_RANGE = 1.1f..1_000f

        /**
         * 받은 값을 쓸 수 있는 범위로 접는다. **거부가 아니라 교정이다** — 엑셀에서 손으로 적다
         * 0을 하나 더 붙인 값 때문에 가져오기 전체가 서는 것이 사용자에게 더 나쁘다.
         * 다만 [decode]가 접힌 사실을 따로 세어 알린다(말없이 바꾸지 않는다).
         *
         * 화면 쪽은 접지 않고 **되돌려 묻는다**(R-27) — 사람이 보는 앞에서는 고칠 기회를 주는
         * 것이 낫고, 파일에서 들어온 값은 물을 사람이 없으므로 접는다. 범위는 같다.
         */
        fun clamp(t: PatternThresholds) = PatternThresholds(
            dominancePercent = t.dominancePercent.coerceIn(DOMINANCE_RANGE.start, DOMINANCE_RANGE.endInclusive),
            balanceMaxPercent = t.balanceMaxPercent.coerceIn(BALANCE_RANGE.start, BALANCE_RANGE.endInclusive),
            outlierSingletonPercent = t.outlierSingletonPercent.coerceIn(OUTLIER_RANGE.start, OUTLIER_RANGE.endInclusive),
            clusterPercent = t.clusterPercent.coerceIn(CLUSTER_RANGE.start, CLUSTER_RANGE.endInclusive),
            absenceGapYears = t.absenceGapYears.coerceIn(ABSENCE_YEARS_RANGE.first, ABSENCE_YEARS_RANGE.last),
            crossNovelRatio = t.crossNovelRatio.coerceIn(CROSS_NOVEL_RANGE.start, CROSS_NOVEL_RANGE.endInclusive)
        )

        /**
         * [encode]의 역. **모르는 키와 못 읽는 값을 조용히 버리지 않는다** — 세어서 돌려주고,
         * 부르는 쪽이 사용자에게 말한다(개발 의도 2번 '변수 제어').
         *
         * **적히지 않은 키는 [base]를 그대로 둔다.** 내보내기는 언제나 여섯을 다 적으므로
         * *일부만 적힌 파일은 사람이 손으로 만든 것*이고, 그때 한 줄을 고친 사람의 뜻은
         * **그 한 줄을 바꾸라**이지 나머지를 지우라가 아니다. 그래서 엑셀 바인딩은 지금 저장된
         * 값을 [base]로 넘긴다 — `DEFAULT`로 두면 한 줄만 남긴 파일이 **나머지 다섯을 말없이
         * 초기화한다.** 순수 계약을 재는 자리에서는 기본값(`DEFAULT`)에서 시작한다.
         */
        fun decode(raw: String, base: PatternThresholds = DEFAULT): Decoded {
            var t = base
            val unknown = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            for (piece in raw.split(',')) {
                val token = piece.trim()
                if (token.isEmpty()) continue
                val eq = token.indexOf('=')
                if (eq <= 0) { invalid.add(token); continue }
                val key = token.substring(0, eq).trim().lowercase()
                val value = token.substring(eq + 1).trim()
                val f = value.toFloatOrNull()
                if (f == null || f.isNaN() || f.isInfinite()) {
                    // 키를 모르는 것과 값을 못 읽는 것을 가른다 — 사용자가 고칠 자리가 다르다.
                    if (key in KNOWN_KEYS) invalid.add(key) else unknown.add(key)
                    continue
                }
                when (key) {
                    KEY_DOMINANCE -> t = t.copy(dominancePercent = f)
                    KEY_BALANCE -> t = t.copy(balanceMaxPercent = f)
                    KEY_OUTLIER -> t = t.copy(outlierSingletonPercent = f)
                    KEY_CLUSTER -> t = t.copy(clusterPercent = f)
                    KEY_ABSENCE -> t = t.copy(absenceGapYears = f.toInt())
                    KEY_CROSS_NOVEL -> t = t.copy(crossNovelRatio = f)
                    else -> unknown.add(key)
                }
            }
            val clamped = clamp(t)
            return Decoded(clamped, unknown, invalid, coerced = clamped != t)
        }

        val KNOWN_KEYS = setOf(
            KEY_DOMINANCE, KEY_BALANCE, KEY_OUTLIER, KEY_CLUSTER, KEY_ABSENCE, KEY_CROSS_NOVEL
        )

        /** 정수면 소수점을 떼고 적는다 — `60`이 `60.0`으로 보이면 손으로 고치기 나쁘다. */
        private fun num(v: Float): String =
            if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }

    /** [decode]의 결과 — 무엇을 못 읽었는지까지 함께 돌려준다(R-17). */
    data class Decoded(
        val thresholds: PatternThresholds,
        val unknownKeys: List<String>,
        val invalidKeys: List<String>,
        /** 값이 범위 밖이라 접혔는가. 접힌 것도 '적은 대로 되지 않은 것'이라 말해야 한다. */
        val coerced: Boolean
    )
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
    val population: Int = 0,
    /**
     * [population]과 [mergedFieldDefIds]가 **무엇의** 것인가 (R-13).
     * 기본이 캐릭터인 것은 종전 카드가 전부 그랬기 때문이고, 새 축은 반드시 스스로 밝힌다 —
     * 밝히지 않은 카드가 캐릭터로 통하는 것이 이 기본값의 유일한 의미다.
     */
    val axis: PatternAxis = PatternAxis.CHARACTER
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

/**
 * 작품 드릴다운 행 — [FieldValueCharacter]의 작품판 (확-3).
 * [universeId]는 행을 눌렀을 때 그 작품이 있는 목록으로 보내기 위한 것이다(작품 상세 화면이 없다).
 */
data class FieldValueNovel(
    val novelId: Long,
    val title: String,
    val universeId: Long?,
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
 * '희소'를 말하기 전에 있어야 할 최소 값 건수.
 *
 * **민감도가 아니라 표본 조건이라 [PatternThresholds]에 넣지 않았다** — 값이 셋뿐인 필드에서
 * "1건짜리가 전체의 5% 이하"는 산술적으로 성립할 수가 없다(1/3 = 33%). 사용자가 이 수를
 * 내려도 카드가 늘지 않고, 올리면 감지가 조용히 죽는다. 손잡이는 [PatternThresholds]의
 * 비율 쪽 하나뿐이어야 뜻이 하나로 읽힌다.
 */
const val OUTLIER_MIN_VALUES = 10

/**
 * '작품별 주요값'의 기준 — **정의이지 민감도가 아니다**(과반이라는 말이 곧 50%다).
 * 편중 기준(사용자 조정)과 섞으면 카드가 말하는 "주요값"이 과반이 아닐 수도 있게 된다.
 */
const val CROSS_NOVEL_MAJORITY_PERCENT = 50f

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
    /**
     * 대표 이미지 포인터(B-103 D8). `imagePaths`를 이미 나르므로 **함께 나른다** —
     * 캐릭터당 짧은 문자열 하나이고 이것을 순회하는 계산이 없다(확장성 4단계 ③).
     * 없으면 순위 카드만 대표를 모르는 상태가 되어 다른 화면과 다른 그림을 보여 준다.
     */
    val representativeImagePath: String,
    val novelTitle: String?
)

data class RankingResult(
    val entries: List<RankingEntry>,
    val fieldName: String,
    val fieldType: String,
    val ascending: Boolean,
    val totalCharacters: Int,
    val excludedCount: Int,
    /**
     * 점수 분포 — **대결 축일 때만** 채워진다 (B-117. 백로그 원문의 *"점수 분포"*).
     *
     * 줄 세우기는 *"누가 위인가"*를 답하지만 **군상의 모양**은 답하지 않는다 —
     * 원칙 02가 요구하는 것이 후자다(*"편향이나 패턴을 발견할 수 있는 정보"*).
     * 점수가 좁은 띠에 몰려 있는지 양극으로 갈렸는지는 목록을 끝까지 훑어도 안 보인다.
     *
     * 구간은 [com.novelcharacter.app.util.NumericBinning]이 만든다 — 분포를 그리는 쪽과
     * 조각의 인원을 세는 쪽이 구간을 각자 계산해 **두 수가 어긋난 전례**가 이 저장소에 있다.
     * 비어 있으면 *나눌 폭이 없다*는 뜻이다(전원이 같은 점수이거나 둘 미만).
     */
    val scoreDistribution: List<Pair<String, Int>> = emptyList()
)

data class RankableField(
    val fieldDef: FieldDefinition,
    val bodySizeParts: List<String>?,
    val isNumeric: Boolean,
    /** 전체 세계관 모드에서 같은 key+type으로 머지된 모든 fieldDefId 목록 */
    val mergedFieldDefIds: List<Long> = listOf(fieldDef.id)
)

/**
 * 순위 화면이 **무엇으로 줄 세울지**의 선택지 (B-117) — 필드와 대결 축이 한 목록에 선다.
 *
 * 스피너 하나에 두 부류를 담으므로 **차례가 곧 계약**이다. 화면이 위치로 되짚기 때문에
 * 목록을 만드는 일을 여기(순수 계산)로 내렸다 — 화면 안에서 인덱스를 셈하면
 * 「세션 착수 규칙」 4번이 말한 *"자동 검증이 보지 못하는 자리"*가 된다.
 */
data class RankingSource(
    val isDuel: Boolean,
    /** 스피너에 보이는 이름. */
    val label: String,
    /** 괄호 안에 붙는 종류 표시("숫자"·"빈도"·"대결"). */
    val typeLabel: String,
    /**
     * 필드일 때만.
     *
     * 이름이 `field`가 아닌 것은 일부러다 — 커스텀 getter 안에서 `field`는 **뒷받침 필드**를
     * 가리키는 코틀린의 소프트 키워드라, 아래 [storageKey]에서 이 속성을 가리지 못한다.
     */
    val rankableField: RankableField? = null,
    /** 대결 축일 때만 — **코드**다(R-1: 이 앱의 축은 언제나 코드로 가리킨다). */
    val duelAxisCode: String? = null
) {
    /**
     * 고른 것을 저장·복원하는 열쇠.
     *
     * 필드와 축이 한 목록에 서므로 **접두사로 갈라야 한다** — 필드 key `강함`과 축 코드가
     * 우연히 같으면 다음에 열 때 엉뚱한 것이 골라진다. 위치(인덱스)로 저장하지 않는 것은
     * 축을 하나 만들기만 해도 뒤가 통째로 밀리기 때문이다.
     */
    val storageKey: String
        get() = if (isDuel) "$DUEL_KEY_PREFIX$duelAxisCode" else "$FIELD_KEY_PREFIX${rankableField?.fieldDef?.key}"

    /**
     * 저장된 열쇠가 이것을 가리키는가.
     *
     * **접두사 없는 값도 필드로 받는다** — B-117 이전에 저장된 선택은 필드 key를 그대로
     * 담고 있다. 이것을 받지 않으면 업데이트 한 번에 **모든 사용자의 순위 선택이 풀린다**
     * (앱이 죽지는 않으므로 눈에 띄지 않는 유실이고, 그래서 더 조용하다).
     */
    fun matches(savedKey: String): Boolean =
        savedKey == storageKey ||
            (!isDuel && !savedKey.startsWith(DUEL_KEY_PREFIX) &&
                savedKey.removePrefix(FIELD_KEY_PREFIX) == rankableField?.fieldDef?.key)

    companion object {
        const val FIELD_KEY_PREFIX = "field:"
        const val DUEL_KEY_PREFIX = "duel:"
    }
}

/**
 * 앱 인스턴스는 [loadSnapshot]만 필요로 한다 — 나머지 compute* 함수는 스냅샷만 보는 순수 계산이다.
 * 그래서 생성자에서 앱을 받지 않는다: Android 런타임 없이도 집계 규칙을 실제로 실행해 검증할 수 있다.
 */
class StatsDataProvider {


    /** 대결 점수 순위의 종류 표시 — 화면과 계산이 같은 문자열을 본다(B-117). */
    private val DUEL_TYPE_LABEL = "대결"

    /**
     * 드릴다운 목록의 캐릭터 그림을 고를 때 쓰는 랜덤 시드(B-103 D3).
     *
     * **이 provider 한 벌에 시드 하나다** — 통계 화면은 이 인스턴스를 들고 있고 드릴다운을
     * 여러 번 열어도 같은 캐릭터는 같은 그림이어야 한다. 화면을 나갔다 들어오면 provider가
     * 다시 만들어지므로 그때 새로 뽑힌다(D3이 정의한 "화면 진입 1회").
     */
    private val drilldownImageSeed: Long = com.novelcharacter.app.util.CharacterRepresentativeImage.newSeed()

    /**
     * @param weights 완성도 필수 가중(B-100). **설정 읽기는 호출부의 몫이다** —
     *   이 계층이 `Context`를 알게 되면 순수 하네스가 통째로 컴파일되지 않는다.
     */
    suspend fun loadSnapshot(app: NovelCharacterApp, weights: CompletionWeights): StatsSnapshot {
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
            novelFieldDefinitions = db.fieldDefinitionDao().getAllFieldsList(FieldDefinition.ENTITY_NOVEL),
            novelFieldValues = db.novelFieldValueDao().getAllValuesList(),
            valueEntries = db.fieldValueEntryDao().getAllList(),
            // 축만 든다 — 판은 싣지 않는다(위 duelAxes 주석: 스냅샷에 계산을 붙이지 않는다).
            duelAxes = db.duelAxisDao().getAllList(),
            completionWeights = weights
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
            eventFieldValues = s.eventFieldValues.filter { it.eventId in eventIdsForNovel },
            // 작품 스코프의 모수는 그 작품 하나다 — 값도 그 작품 것만 남긴다.
            novelFieldDefinitions = s.novelFieldDefinitions.filter { it.universeId == novel.universeId },
            novelFieldValues = s.novelFieldValues.filter { it.novelId == novelId },
            // 축은 세계관 단위라 그 작품이 속한 세계관의 것만 남는다(B-117).
            // **점수를 자르지는 않는다** — 점수는 축 전체의 기록에서 나온 값이고, 작품으로
            // 잘라 다시 적합하면 순위표와 다른 수가 된다. 작품 필터는 *무엇을 보는가*이지
            // *누가 겨뤘는가*가 아니다.
            duelAxes = s.duelAxes.filter { it.universeId == novel.universeId }
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
            // **작품 축은 이 스코프에 없다.** '작품 미배정'은 작품이 없는 캐릭터의 스코프라
            // 작품 모수가 0이고, 값만 실으면 카드가 "3/0개"라는 읽을 수 없는 완성도를 낸다
            // (캐릭터 축이 모수 0 모순을 피하려고 unassignedScope 분기를 둔 것과 같은 이유).
            // 세계관 없는 작품이 보관 중인 값은 전체 스코프의 카드와 엑셀 왕복이 다룬다.
            novelFieldDefinitions = emptyList(),
            novelFieldValues = emptyList(),
            // **대결 축도 이 스코프에 없다** — 축은 세계관 단위이고 이 스코프에는 세계관이 없다.
            // 남겨 두면 순위 화면이 축을 제시하는데, 고르면 그 세계관의 캐릭터가 이 스코프에
            // 하나도 없어 빈 표가 뜬다(고를 수 있는데 아무 일도 안 일어나는 자리 — 원칙 02).
            duelAxes = emptyList(),
            unassignedScope = true,
            // **계산 필드는 이 스코프에서 산출할 수 없고, 그 사실을 말한다** (B-30 · 확정 7-4).
            // `Character`에는 `universeId`가 없어 캐릭터는 **작품을 경유해야만** 세계관을 안다.
            // 작품이 없으면 *어느 세계관의 수식인가*를 구조적으로 알 수 없으므로 값을 만들지
            // 않는다 — 그러나 위 `fieldDefinitions` 필터가 **저장 행이 없는 계산 필드를
            // 정의째 걷어내므로**, 고지가 없으면 사용자에게는 그 필드가 *존재하지 않는* 것처럼
            // 보인다. 세는 것은 원본 스냅샷의 계산 필드다(이 스코프에는 이미 하나도 없다).
            calculatedUnavailable = s.fieldDefinitions.count { it.fieldType == FieldType.CALCULATED }
        )
    }

    /**
     * 캐릭터 id → **값이 비어 있지 않은** 필드 정의 id 집합 (B-100).
     *
     * 완성도의 분자는 언제나 이 집합과 *그 캐릭터에 적용되는 정의*의 교집합이다.
     * 종전에는 여섯 자리가 이 교집합 없이 값의 개수를 그냥 셌고, 그래서
     * [com.novelcharacter.app.data.repository.CharacterFieldValueMerge]가 일부러 남기는
     * 보존 값(다른 세계관·사건 정의를 가리키는 값)이 분자에 섞여 완성도를 부풀렸다.
     */
    private fun filledCharacterDefIds(s: StatsSnapshot): Map<Long, Set<Long>> =
        // 복잡도·요약·캐릭터 상세·데이터 건강 등 다섯 자리가 같은 스냅샷으로 부른다 —
        // 필드값 전량 훑기이므로 스냅샷 단위로 한 번만 짓는다(perSnapshot 규약).
        perSnapshot(filledDefIdsCache, s) { snap ->
            snap.fieldValues.asSequence()
                .filter { it.value.isNotBlank() }
                .groupBy({ it.characterId }, { it.fieldDefinitionId })
                .mapValues { (_, ids) -> ids.toSet() }
        }

    /**
     * 그룹별 완성도 평균 (B-100) — **세 화면이 같은 함수를 쓴다**(통계 캐릭터 상세·데이터 건강·
     * 데이터 개요). 종전에는 같은 22줄이 세 벌로 복사돼 있었고, 그런 자리는 한쪽만 고쳐지면
     * 화면마다 다른 답을 낸다(R-33이 다른 계층에서 잡은 것과 같은 부류).
     *
     * @param fieldsForChar 이 캐릭터에 적용되는 정의(계산 필드가 섞여 있어도 된다 —
     *   [CompletionRate]가 거른다). null이면 그 캐릭터는 셈에서 빠진다.
     */
    private fun groupCompletionAverages(
        characters: List<Character>,
        fieldsForChar: (Character) -> List<FieldDefinition>?,
        filledDefIdsByChar: Map<Long, Set<Long>>,
        weights: CompletionWeights
    ): Map<String, Float> {
        val groupRates = mutableMapOf<String, MutableList<Float>>()
        // 같은 정의 목록의 그룹 분해는 캐릭터마다 같다 — 목록 **동일성**으로 한 번만 짓는다
        // (S6 5차). 호출부 셋 모두 세계관별 공유 목록(또는 스냅샷 목록 그 자체)을 돌려주므로
        // 분해는 세계관 수만큼만 돌고, 새 호출부가 캐릭터마다 새 목록을 지어 와도 종전
        // (캐릭터마다 분해)과 같아질 뿐 틀리지 않는다.
        val groupedByFields =
            java.util.IdentityHashMap<List<FieldDefinition>, Map<String, List<FieldDefinition>>>()
        characters.forEach { char ->
            val fields = fieldsForChar(char) ?: return@forEach
            val filled = filledDefIdsByChar[char.id].orEmpty()
            val byGroup = groupedByFields.getOrPut(fields) { fields.groupBy { it.groupName } }
            byGroup.forEach { (group, groupFields) ->
                // 셀 칸이 없는 그룹(계산 필드뿐)은 평균에 넣지 않는다 — 0%로 넣으면
                // "사람이 채울 칸이 없는 그룹"이 "아무도 안 채운 그룹"으로 보인다.
                val rate = CompletionRate.percentOf(groupFields, filled, weights) ?: return@forEach
                groupRates.getOrPut(group) { mutableListOf() }.add(rate)
            }
        }
        return groupRates.mapValues { (_, rates) ->
            if (rates.isEmpty()) 0f else rates.average().toFloat()
        }
    }

    /** 캐릭터 복잡도 경량 계산 (Summary에서 특화 분포용) */
    private fun computeCharacterComplexities(s: StatsSnapshot): List<CharacterComplexity> =
        // 요약(특화 분포)과 교차분석이 같은 스냅샷으로 부른다. 순서는 s.characters 순서 그대로다 —
        // 교차분석이 index로 캐릭터 id를 잇는 계약이므로 캐시가 그 순서를 보존한다.
        perSnapshot(complexitiesCache, s) { buildCharacterComplexities(it) }

    private fun buildCharacterComplexities(s: StatsSnapshot): List<CharacterComplexity> {
        val relCount = mutableMapOf<Long, Int>()
        s.relationships.forEach {
            relCount[it.characterId1] = (relCount[it.characterId1] ?: 0) + 1
            relCount[it.characterId2] = (relCount[it.characterId2] ?: 0) + 1
        }
        val eventCountMap = s.crossRefs.groupBy { it.characterId }.mapValues { it.value.size }
        val stateChangesByChar = s.stateChanges.groupBy { it.characterId }

        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val filledDefIdsByChar = filledCharacterDefIds(s)

        return s.characters.map { char ->
            val relCnt = relCount[char.id] ?: 0
            val evtCnt = eventCountMap[char.id] ?: 0
            val stateChangeCnt = stateChangesByChar[char.id]?.size ?: 0

            val novel = char.novelId?.let { novelMap[it] }
            // 완성도 판정은 [CompletionRate] 하나다 — 칸 고르기(CALCULATED 제외)·분자 교집합·
            // 필수 가중이 전부 그 안에 있다. 작품 미배정이거나 셀 칸이 없으면 null(산출 불가)이다.
            val completion: Float? = novel?.let {
                CompletionRate.percentOf(
                    fieldDefByUniverse[it.universeId].orEmpty(),
                    filledDefIdsByChar[char.id].orEmpty(),
                    s.completionWeights
                )
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

        // 평균 필드 완성도 — 판정은 [CompletionRate] 하나다(칸 고르기·분자 교집합·필수 가중).
        val summaryFieldDefs = s.fieldDefinitions.groupBy { it.universeId }
        val summaryFilledDefIds = filledCharacterDefIds(s)
        val completions = s.characters.mapNotNull { char ->
            val novelId = char.novelId ?: return@mapNotNull null
            val novel = novelMap[novelId] ?: return@mapNotNull null
            CompletionRate.percentOf(
                summaryFieldDefs[novel.universeId].orEmpty(),
                summaryFilledDefIds[char.id].orEmpty(),
                s.completionWeights
            )
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
        // 계수는 건별 재료화가 아니라 접힌 값 표(원문 × 건수) 위에서 돈다 — S6 4차. 원문이
        // 크게 겹치므로([valueCountsOf]) 세는 답은 같고, 토큰 쌍의 재료화·건별 해싱만 없어진다.
        val fieldDefById = s.fieldDefinitions.associateBy { it.id }
        val statsConfigs = statsConfigsOf(s)
        val topCounts = LinkedHashMap<Pair<String, String>, Int>()
        for ((fieldDefId, byRaw) in valueCountsOf(s)) {
            val fd = fieldDefById[fieldDefId] ?: continue
            val cfg = statsConfigs[fieldDefId] ?: continue
            if (!cfg.enabled) continue
            for ((raw, n) in byRaw) {
                for (key in getFieldValues(s, fd, raw, cfg)) {
                    topCounts.merge(Pair(fd.name, key), n) { a, b -> a + b }
                }
            }
        }
        val topFieldValues = topCounts
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

        // 필드 완성도 — 판정은 [CompletionRate] 하나다(칸 고르기·분자 교집합·필수 가중).
        val statsFieldDefs = s.fieldDefinitions.groupBy { it.universeId }
        val filledDefIdsByChar = filledCharacterDefIds(s)

        val fieldCompletionById = mutableMapOf<Long, Float>()
        s.characters.forEach { char ->
            val novelId = char.novelId ?: return@forEach
            val novel = novelMap[novelId] ?: return@forEach
            val rate = CompletionRate.percentOf(
                statsFieldDefs[novel.universeId].orEmpty(),
                filledDefIdsByChar[char.id].orEmpty(),
                s.completionWeights
            ) ?: return@forEach
            fieldCompletionById[char.id] = rate
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

        // 신규: 그룹별 필드 완성도 — 세 화면이 같은 헬퍼를 쓴다(B-100).
        val fieldCompletionByGroup = groupCompletionAverages(
            characters = s.characters,
            fieldsForChar = { char ->
                char.novelId?.let { novelMap[it] }?.let { statsFieldDefs[it.universeId] }
            },
            filledDefIdsByChar = filledDefIdsByChar,
            weights = s.completionWeights
        )

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

        // 필드 미입력률 높은 캐릭터 — 판정은 [CompletionRate] 하나다(칸 고르기·분자 교집합·필수 가중).
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val filledDefIdsByChar = filledCharacterDefIds(s)

        // 미배정 스코프: novelId 경유가 불가 — 보존 정의 대비 채움률로 판정
        // (computeDataOverview의 incompleteCount와 같은 기준 — 개수·명단 일치)
        val fieldsForChar: (Character) -> List<FieldDefinition>? = { char ->
            if (s.unassignedScope) s.fieldDefinitions.ifEmpty { null }
            else char.novelId?.let { novelMap[it] }?.let { fieldDefByUniverse[it.universeId] }
        }
        val incomplete = s.characters.mapNotNull { char ->
            val fields = fieldsForChar(char) ?: return@mapNotNull null
            val rate = CompletionRate.percentOf(
                fields, filledDefIdsByChar[char.id].orEmpty(), s.completionWeights
            ) ?: return@mapNotNull null
            if (rate < INCOMPLETE_THRESHOLD_PERCENT) char.name to rate else null
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

        // 신규: 그룹별 필드 완성도 — 세 화면이 같은 헬퍼를 쓴다(B-100).
        val completionByGroup = groupCompletionAverages(
            characters = s.characters,
            fieldsForChar = { char ->
                char.novelId?.let { novelMap[it] }?.let { fieldDefByUniverse[it.universeId] }
            },
            filledDefIdsByChar = filledDefIdsByChar,
            weights = s.completionWeights
        )

        // 신규: 별명 없는 캐릭터
        val noAnotherName = s.characters.filter { it.anotherName.isBlank() }.map { it.name }

        // 신규: 시간 정밀도 낮은 사건
        val lowPrecision = s.events.count { it.month == null }

        // 작품 미배정 캐릭터
        val noNovel = s.characters.filter { it.novelId == null }.map { it.name }

        return DataHealthStats(
            typeMismatchedValues = collectTypeMismatchedValues(s),
            incompleteFieldChars = incomplete,
            isolatedChars = isolated,
            unlinkedChars = unlinked,
            duplicateTags = dupTags,
            emptyDescRelationships = emptyDescRels,
            fieldCompletionByGroup = completionByGroup,
            lowPrecisionEvents = lowPrecision,
            noNovelChars = noNovel,
            inputProgress = DataInputProgress(
                noImageChars = noImage,
                noMemoChars = noMemo,
                noAnotherNameChars = noAnotherName
            )
        )
    }

    /**
     * 타입과 맞지 않게 된 값 전수 (B-156). 판정은 [FieldValueTypeMismatch] 하나다.
     *
     * **원인을 가리지 않는다.** 전파가 만든 것뿐 아니라 단일 필드 편집·엑셀 가져오기가 만든
     * 것도 같은 부류이고, 사용자에게는 *어쩌다 그렇게 됐는지*가 아니라 *지금 어느 칸이
     * 틀렸는지*가 필요하다. 그래서 값을 보고 판정하지 출처를 묻지 않는다.
     *
     * **세 축을 모두 훑는다.** 사건·작품 커스텀 필드도 같은 타입 시스템을 쓰므로 같은
     * 방식으로 망가진다. 캐릭터 축만 세면 *"일일이 확인하지 않으면 존재를 알 수 없는
     * 데이터"*(원칙 04)를 두 축에 남기게 된다.
     *
     * **비용은 새 축을 만들지 않는다** — 세 값 목록은 스냅샷이 이미 싣고 있고 건강도가 이미
     * 그중 하나를 훑는다(`filledCharacterDefIds`). 여기 붙는 것은 그 축(필드값 수)에서의
     * 상수배 한 번이고, 정의는 id로 미리 색인해 값마다 다시 찾지 않는다
     * (`scalability_performance` 7장 2단계 — 새 상한도, 스냅샷의 새 목록도 없다).
     *
     * **축 안에서는 이름·필드 순으로 정렬해 돌려준다.** 표시 계층이 축마다 상한을 걸어
     * 뒷부분을 접으므로([TypeMismatchList]), 순서가 값 표의 행 순서면 **무엇이 접히는지가
     * 사실상 임의로 정해진다** — 같은 데이터를 다시 열었을 때 다른 50개가 보일 수 있다.
     * 접는 장치에는 결정적 순서가 함께 있어야 한다(R-14).
     */
    private fun collectTypeMismatchedValues(s: StatsSnapshot): List<TypeMismatchedValue> {
        val out = mutableListOf<TypeMismatchedValue>()

        fun <T> collect(
            defs: List<FieldDefinition>,
            values: List<T>,
            ownerType: String,
            ownerIdOf: (T) -> Long,
            fieldDefIdOf: (T) -> Long,
            valueOf: (T) -> String,
            ownerNames: Map<Long, String>,
            // 이 축의 def별 고유 원문 — 있는 축(캐릭터: 접힌 값 표 메모)은 그것을 그대로 쓰고,
            // 없는 축(사건·작품)은 null을 받아 행에서 모은다.
            foldedRaws: Map<Long, Collection<String>>? = null
        ) {
            if (defs.isEmpty() || values.isEmpty()) return
            val defById = defs.associateBy { it.id }
            // ① 판정 — (정의, 원문)의 순수 함수라 **고유 원문 위에서** 먼저 낸다(S6 5차).
            //    행마다 내면 등급 라벨 하나에 config 파싱이 그 라벨의 행 수만큼 돈다
            //    ([GradeValueResolver.resolveFromConfig]가 부를 때마다 JSON을 파싱한다).
            //    저장 블랭크 행은 접힌 표에 없지만 어느 타입으로도 불일치가 아니라 애초에
            //    판정 대상이 아니다([FieldValueTypeMismatch.reasonFor] 규약).
            val uniqueRaws: Map<Long, Collection<String>> = foldedRaws ?: run {
                val m = HashMap<Long, LinkedHashSet<String>>()
                for (row in values) {
                    val raw = valueOf(row)
                    if (raw.isBlank()) continue
                    m.getOrPut(fieldDefIdOf(row)) { LinkedHashSet() }.add(raw)
                }
                m
            }
            val mismatchByDef = HashMap<Long, HashMap<String, FieldValueTypeMismatch.Reason>>()
            for ((defId, raws) in uniqueRaws) {
                val def = defById[defId] ?: continue
                var byRaw: HashMap<String, FieldValueTypeMismatch.Reason>? = null
                for (raw in raws) {
                    val reason = FieldValueTypeMismatch.reasonFor(def, raw) ?: continue
                    (byRaw ?: HashMap<String, FieldValueTypeMismatch.Reason>()
                        .also { byRaw = it; mismatchByDef[defId] = it })[raw] = reason
                }
            }
            if (mismatchByDef.isEmpty()) return // 걸린 (정의, 원문)이 없으면 행을 지나지 않는다

            // ② 수집 — 걸린 (정의, 원문)의 행만 임자를 단다. 행 루프의 일은 맵 조회뿐이다.
            val found = mutableListOf<TypeMismatchedValue>()
            values.forEach { row ->
                val byRaw = mismatchByDef[fieldDefIdOf(row)] ?: return@forEach
                val raw = valueOf(row)
                val reason = byRaw[raw] ?: return@forEach
                val ownerId = ownerIdOf(row)
                found.add(
                    TypeMismatchedValue(
                        ownerType = ownerType,
                        ownerId = ownerId,
                        // 이름을 못 찾는 행은 대상이 지워졌다는 뜻이라 건너뛰지 않고 id로 말한다 —
                        // 조용히 빼면 개수와 목록이 갈린다.
                        ownerName = ownerNames[ownerId] ?: "#$ownerId",
                        // ①에서 defById에 있던 def만 mismatchByDef에 실린다.
                        fieldDefId = fieldDefIdOf(row),
                        fieldName = defById.getValue(fieldDefIdOf(row)).name,
                        fieldType = defById.getValue(fieldDefIdOf(row)).type,
                        value = raw,
                        reason = reason
                    )
                )
            }
            out.addAll(found.sortedWith(compareBy({ it.ownerName }, { it.fieldName })))
        }

        collect(
            s.fieldDefinitions, s.fieldValues, FieldDefinition.ENTITY_CHARACTER,
            { it.characterId }, { it.fieldDefinitionId }, { it.value },
            s.characters.associate { it.id to it.name },
            // 저장 비블랭크 (def, 원문) 쌍은 전부 augmented에 실리므로 접힌 표의 키가 곧
            // 이 축의 판정 대상이다(계산값 쌍이 더 실리지만 CALCULATED는 판정이 늘 null이다).
            foldedRaws = valueCountsOf(s).mapValues { it.value.keys }
        )
        collect(
            s.eventFieldDefinitions, s.eventFieldValues, FieldDefinition.ENTITY_EVENT,
            { it.eventId }, { it.fieldDefinitionId }, { it.value },
            // 사건에는 제목 칸이 없다 — 목록에서 사건을 알아보는 이름은 설명이다.
            s.events.associate { it.id to it.description }
        )
        collect(
            s.novelFieldDefinitions, s.novelFieldValues, FieldDefinition.ENTITY_NOVEL,
            { it.novelId }, { it.fieldDefinitionId }, { it.value },
            s.novels.associate { it.id to it.title }
        )
        return out
    }

    // ===== 필드 인사이트 (신규) =====
    fun computeFieldInsights(s: StatsSnapshot): List<FieldInsightResult> {
        val universeMap = s.universes.associateBy { it.id }

        // 저장 값 + CALCULATED 계산값의 **접힌 값 표** (원문 → 건수 — R-16 합성 규칙은
        // [valueCountsOf]가 [augmentedCharacterValues] 위에서 그대로 물려받는다). 분포·수치·
        // 건수가 전부 건수 가중으로 같은 답을 내므로([buildFieldInsight]) 값 행 문자열 목록을
        // 그룹마다 다시 재료화하지 않는다 (S6 5차).
        val countsByFieldDef = valueCountsOf(s)

        // 동일 필드를 (key, type) 기준으로 세계관 통합 (Pre-Analysis Merge)
        val fieldGroups = analyzableDefs(s, s.fieldDefinitions)
            .groupBy { it.key to it.type }

        // 모수는 세계관별로 한 번만 센다 — 그룹마다 캐릭터 전수를 세던 것(그룹×캐릭터)을
        // 걷었다(S6 5차 — computeFieldAnalysis 완성도 모수와 같은 선계수). 조건 무변경:
        // 캐릭터의 작품이 실재하고 그 작품이 해당 세계관 소속일 것.
        val charCountByUniverse = characterCountsByUniverse(s)

        val characterInsights = fieldGroups.map { (_, fds) ->
            val primaryFd = fds.first()
            val statsConfig = statsConfigOf(s, primaryFd)

            // 그룹 내 모든 필드의 접힌 표 병합 (CALCULATED 포함)
            val rawCounts = mergedRawCounts(countsByFieldDef, fds)

            // 관련 세계관 전체의 캐릭터 수. 미배정 스코프는 novels가 비어 있으므로
            // 스코프 캐릭터 전체가 모수 (novelId 경유 시 모수 0 → "채움 N / 전체 0" 모순 방지)
            val totalCount = if (s.unassignedScope) s.characters.size
                else fds.mapTo(HashSet()) { it.universeId }.sumOf { charCountByUniverse[it] ?: 0 }

            val universeName = if (fds.size == 1) {
                universeMap[primaryFd.universeId]?.name ?: ""
            } else ""

            buildFieldInsight(s, primaryFd, statsConfig, rawCounts, totalCount, universeName,
                mergedFieldDefIds = fds.map { it.id })
        }

        // ── 사건 필드 인사이트 (B-10 후속): 캐릭터 필드와 동일 규칙으로 편입 (원칙 02) ──
        // 이 축은 스냅샷 메모가 없어 여기서 접는다 — 저장 행 먼저, 계산값 나중(종전 연결 순서
        // 그대로라 첫 등장 순서도 같다). 블랭크를 거르는 것도 종전 그대로다.
        val eventValueCountsByFieldDef = HashMap<Long, LinkedHashMap<String, Int>>()
        for (fv in s.eventFieldValues) {
            if (fv.value.isBlank()) continue
            eventValueCountsByFieldDef.getOrPut(fv.fieldDefinitionId) { LinkedHashMap() }
                .merge(fv.value, 1) { a, b -> a + b }
        }
        for ((_, fieldMap) in computeAllEventCalculatedValues(s)) {
            for ((fieldDefId, value) in fieldMap) {
                if (value.isBlank()) continue
                eventValueCountsByFieldDef.getOrPut(fieldDefId) { LinkedHashMap() }
                    .merge(value, 1) { a, b -> a + b }
            }
        }
        val eventFieldGroups = analyzableDefs(s, s.eventFieldDefinitions)
            .groupBy { it.key to it.type }
        val eventInsights = eventFieldGroups.map { (_, fds) ->
            val primaryFd = fds.first()
            val statsConfig = statsConfigOf(s, primaryFd)
            val rawCounts = mergedRawCounts(eventValueCountsByFieldDef, fds)

            // 모수 = 해당 세계관들의 사건 수 (사건 필드는 세계관 소속 사건에만 부여 가능)
            val universeIds = fds.map { it.universeId }.toSet()
            val totalCount = s.events.count { it.universeId in universeIds }

            val universeName = if (fds.size == 1) {
                universeMap[primaryFd.universeId]?.name ?: ""
            } else ""

            buildFieldInsight(s, primaryFd, statsConfig, rawCounts, totalCount, universeName,
                mergedFieldDefIds = fds.map { it.id })
        }

        // ── 작품 필드 인사이트 (확-3): 같은 규칙으로 편입 (원칙 02) ──
        val novelValueCountsByFieldDef = HashMap<Long, LinkedHashMap<String, Int>>()
        for (fv in s.novelFieldValues) {
            if (fv.value.isBlank()) continue
            novelValueCountsByFieldDef.getOrPut(fv.fieldDefinitionId) { LinkedHashMap() }
                .merge(fv.value, 1) { a, b -> a + b }
        }
        for ((_, fieldMap) in computeAllNovelCalculatedValues(s)) {
            for ((fieldDefId, value) in fieldMap) {
                if (value.isBlank()) continue
                novelValueCountsByFieldDef.getOrPut(fieldDefId) { LinkedHashMap() }
                    .merge(value, 1) { a, b -> a + b }
            }
        }
        val novelFieldGroups = analyzableDefs(s, s.novelFieldDefinitions)
            .groupBy { it.key to it.type }
        val novelInsights = novelFieldGroups.map { (_, fds) ->
            val primaryFd = fds.first()
            val statsConfig = statsConfigOf(s, primaryFd)
            val rawCounts = mergedRawCounts(novelValueCountsByFieldDef, fds)

            // 모수 = 해당 세계관들의 작품 수 (작품 필드는 세계관 소속 작품에만 부여 가능)
            val universeIds = fds.map { it.universeId }.toSet()
            val totalCount = s.novels.count { it.universeId in universeIds }

            val universeName = if (fds.size == 1) {
                universeMap[primaryFd.universeId]?.name ?: ""
            } else ""

            buildFieldInsight(s, primaryFd, statsConfig, rawCounts, totalCount, universeName,
                mergedFieldDefIds = fds.map { it.id })
        }

        return characterInsights + eventInsights + novelInsights
    }

    /**
     * 필드 1개(세계관 통합 그룹)의 분석 결과 조립 — 캐릭터/사건/작품 필드 공용.
     *
     * [rawCounts]는 그룹의 **접힌 값 표**(원문 → 건수, 첫 등장 순서 — [mergedRawCounts])다.
     * 분포는 건수 가중 접기로, 수치는 고유 원문만 파싱해 건수만큼 싣는 것으로, 채움 건수는
     * 건수 합으로 — 셋 다 건별 목록과 같은 답을 낸다(S6 5차, StatsScanParityTest가 잠근다).
     */
    private fun buildFieldInsight(
        s: StatsSnapshot,
        primaryFd: FieldDefinition,
        statsConfig: FieldStatsConfig,
        rawCounts: Map<String, Int>,
        totalCount: Int,
        universeName: String,
        mergedFieldDefIds: List<Long>
    ): FieldInsightResult {
        val analysisResults = statsConfig.analyses.flatMap { entry ->
            when (entry.type) {
                FieldStatsConfig.StatsType.DISTRIBUTION -> {
                    // 값 종류가 표시 상한을 넘치면 자동 구간으로 접는다 (B-196 · 확정 15장 3번).
                    // **순위는 접지 않는다** — 그쪽은 "어떤 값 하나가 가장 많은가"를 묻는 그림이고,
                    // 구간으로 접으면 그 물음 자체가 사라진다.
                    val folded = foldNumericDistribution(
                        primaryFd, statsConfig,
                        computeFieldDistribution(s, primaryFd, rawCounts, statsConfig),
                        entry.limit
                    )
                    listOf(AnalysisResult(
                        entry, folded.counts, null,
                        folded.specs, folded.autoBinned, folded.preFoldKinds
                    ))
                }
                FieldStatsConfig.StatsType.NUMERIC -> {
                    computeNumericAnalysis(primaryFd, rawCounts, statsConfig, entry)
                }
                FieldStatsConfig.StatsType.RANKING -> {
                    // 분포와 같은 전량을 싣는다 — 상위 N만 남기는 일은 표시 계층이 하고,
                    // 잘린 나머지는 '기타 N종 M건'으로 존재를 알린다(R-14).
                    val dist = computeFieldDistribution(s, primaryFd, rawCounts, statsConfig)
                    listOf(AnalysisResult(entry, dist, null))
                }
            }
        }
        return FieldInsightResult(primaryFd, statsConfig, analysisResults, totalCount,
            rawCounts.values.sum(),
            universeName = universeName, mergedFieldDefIds = mergedFieldDefIds)
    }

    /**
     * NUMERIC 분석 생성. BODY_SIZE는 파트별 개별 수치 통계를 반환한다.
     * 접힌 값 표 기반 — 캐릭터/사건 등 어떤 엔티티의 필드값이든 동일하게 처리한다 (원칙 01).
     * 첫 키가 곧 건별 목록의 첫 원문이다(표가 첫 등장 순서라) — BODY_SIZE 파트 수 추정이
     * 종전(첫 행)과 같은 값을 본다.
     */
    private fun computeNumericAnalysis(
        fd: FieldDefinition,
        rawCounts: Map<String, Int>,
        statsConfig: FieldStatsConfig,
        entry: FieldStatsConfig.AnalysisEntry
    ): List<AnalysisResult> {
        if (fd.fieldType == FieldType.BODY_SIZE) {
            val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
            val separator = if (structuredConfig.enabled) structuredConfig.separator else "-"
            val partCount = if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                structuredConfig.parts.size
            } else {
                rawCounts.keys.firstOrNull()?.split(separator)?.size ?: 1
            }

            return (0 until partCount).mapNotNull { partIdx ->
                val partLabel = if (structuredConfig.enabled && partIdx < structuredConfig.parts.size) {
                    structuredConfig.parts[partIdx].label
                } else "칸${partIdx + 1}"

                // 값 추출은 [NumericBinning]이 단일 소스다 — 드릴다운도 같은 함수로 읽으므로
                // 여기서만 다른 규칙을 쓰면 조각의 수와 목록의 인원이 갈린다(B-39).
                val numericValues = NumericBinning.numericValuesOf(rawCounts, separator, partIdx)
                if (numericValues.isNotEmpty()) {
                    AnalysisResult(
                        entry.copy(label = partLabel),
                        null,
                        computeNumericSummary(numericValues, statsConfig.binning, partIdx, separator)
                    )
                } else null
            }
        }

        // 기본 NUMERIC 분석 — 파트가 없으므로 원문 전체가 0번 파트다.
        val numericValues = NumericBinning.numericValuesOf(rawCounts, "", 0)
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
        rawCounts: Map<String, Int>,
        statsConfig: FieldStatsConfig
    ): Map<String, Int> =
        // 호출부가 접힌 값 표(원문 → 건수)를 그대로 넘긴다 — 형제 def 병합은 [mergedRawCounts]가
        // 하고(그룹 파싱은 기준 [fd]의 규칙 그대로 — R-15), 고유 원문만 파싱을 지난다(S6 4차→5차).
        // [ValueDistributions.of]와 같은 계수·정렬이므로 답은 같다.
        ValueDistributions.sorted(foldStatsKeyCounts(s, fd, statsConfig, rawCounts))

    /** [foldNumericDistribution]의 산출 — 접었는지와, 접었다면 조각마다의 드릴다운 규칙. */
    private data class FoldedDistribution(
        val counts: Map<String, Int>,
        val specs: Map<String, FieldValueMatchSpec>?,
        val autoBinned: Boolean,
        /** 접기 전 값 종류 수 — 고지가 *"값이 몇 종이라 묶었다"*를 말하는 데 쓴다. */
        val preFoldKinds: Int = 0
    )

    /**
     * **값 분포를 고른 수치 필드가 값 하나마다 조각이 되던 자리** (B-196 · 확정 15장 3번).
     *
     * `getFieldValues`는 `binning.mode == "custom"`일 때만 구간 라벨을 돌려주므로, 자동 구간
     * 필드에 *값 분포*를 걸면 값이 그대로 조각이 된다 — 키 600명이면 조각이 60종이 되고 표시
     * 상한 10에 잘려 *"기타 50종"*으로 접힌다. **분포를 보려던 사람이 분포를 못 본다**(원칙 02).
     *
     * 확정이 고른 수위는 **상한을 넘칠 때만**이다: 값 종류가 상한 안이면 지금처럼 값 그대로
     * ('값 분포'라는 선택의 뜻을 보존한다 — 자녀 수 0~3 같은 필드가 그 부류다), 넘치면 자동
     * 구간으로 접고 접었다고 말한다(R-14). 항상-구간은 소수 값 필드의 선택 뜻을 깨고,
     * 설정 스위치는 이미 있는 명시 경로(수치 요약·사용자 구간)와 겹친다(원칙 04).
     *
     * **접는 대상이 원문이 아니라 [dist]의 통계 키인 것이 요점이다.** 여기는 모집단을 가진
     * 자리이고(확정 조건 ⓐ — `getFieldValues`는 값 하나씩 부르는 함수라 원리적으로 구간을
     * 못 만든다), 키는 이미 라벨·카테고리가 적용된 뒤다. 어느 키가 어느 구간에 들었는지를
     * 접는 쪽이 정확히 아므로 스펙을 **그 키 집합**으로 실을 수 있다.
     *
     * **구간 수를 상한으로 묶는다**(확정 조건 ⓒ) — 두 문턱이 갈리면 *잘리는데 안 접는* 구간이
     * 생긴다. 상한이 3이면 구간도 셋까지다.
     *
     * **수로 읽히지 않는 키는 접지 않고 그대로 남긴다** — 접으면 그 값이 어디에도 없다
     * (개발 의도 2번). 그래서 접은 뒤에도 합은 언제나 모집단이다.
     */
    private fun foldNumericDistribution(
        fd: FieldDefinition,
        statsConfig: FieldStatsConfig,
        dist: Map<String, Int>,
        limit: Int
    ): FoldedDistribution {
        val untouched = FoldedDistribution(dist, null, false)
        // 사용자 구간은 이미 구간 라벨이다(접을 것이 없다). 상한이 없으면(0 이하) 넘칠 일이 없다.
        if (!isBinnable(fd.fieldType) || statsConfig.binning?.mode == "custom") return untouched
        if (limit <= 0 || dist.size <= limit) return untouched

        // 파싱 규칙은 [NumericBinning]이 단일 소스다 — `toFloatOrNull()`을 직접 쓰면 줄바꿈 없는
        // 공백(U+00A0)이 낀 값에서 갈린다.
        val numericKeys = dist.keys.mapNotNull { k ->
            NumericBinning.partValue(k, "", 0)?.let { k to it }
        }
        // **비수치 키의 자리를 먼저 뺀다** — 구간이 상한을 다 차지하면 그 키들이 표시 밖으로
        // 밀려 '기타'로 접힌다. 종전 한 줄(`minOf(DEFAULT_BIN_COUNT, limit)`)은 상한이 작을 때
        // **지배적인 비수치 값을 낮은 건수의 구간들이 밀어냈다** — 실측: 상한 3 · 수치 20종 ·
        // `미상` 300건이면 구간 셋(7·6·7명)만 보이고 300건이 *"기타 1종"*으로 접혔다.
        // 접기 전에는 건수순 상위 셋에 `미상`이 들어 **보였으므로 이것은 접기가 만든 퇴행**이고,
        // 원칙 04(일일이 확인하지 않으면 존재를 알 수 없는 데이터를 만들지 않는다)가 걸린다.
        val nonNumericKinds = dist.size - numericKeys.size
        val binCount = minOf(NumericBinning.DEFAULT_BIN_COUNT, limit - nonNumericKinds)
        // **구간이 둘도 안 되면 접지 않는다.** 한 칸으로 뭉친 '분포'는 아무것도 말하지 않으므로,
        // 그 상태로 접는 것보다 종전 동작(건수순 상위 N + 기타)이 낫다 — 비수치 키가 상한을
        // 거의 다 쓰는 필드는 애초에 구간이 답이 아니다.
        if (binCount < 2) return untouched
        val bins = NumericBinning.autoBins(numericKeys.map { it.second }, binCount = binCount)
        // 나눌 폭이 없다(고유 수치가 둘 미만이거나 전부 같다) — 접어도 조각이 안 줄어든다.
        if (bins.isEmpty()) return untouched

        val counts = linkedMapOf<String, Int>()
        val specs = linkedMapOf<String, FieldValueMatchSpec>()
        for (bin in bins) {
            val keys = numericKeys.filter { bin.contains(it.second) }.map { it.first }
            counts[bin.label] = keys.sumOf { dist.getValue(it) }
            // 라벨이 아니라 **그 구간에 든 통계 키 전부**를 싣는다 — 값 일치로 정확히 되찾는다.
            specs[bin.label] = FieldValueMatchSpec.Values(keys.toSet())
        }
        val numericKeySet = numericKeys.mapTo(HashSet()) { it.first }
        for ((k, v) in dist) {
            if (k !in numericKeySet) {
                // **비수치 키가 구간 라벨과 같을 수 있다 — 덮어쓰지 않고 합친다.**
                // 구간 라벨은 `150~160` 꼴이고 사용자가 수치 칸에 대략적인 범위를 그대로 적는 것은
                // 이 앱이 받아들이려는 입력의 전형이다(그 원문은 수로 안 읽히므로 여기로 온다).
                // 종전 한 줄(`counts[k] = v`)은 **그 구간의 인원을 개수 고지도 없이 지웠다** —
                // `autoBins`가 자기 라벨끼리의 겹침을 순번으로 막아 둔 것과 같은 부류의 유실이다.
                // 합치면 그 조각은 *구간에 든 값들 + 그 라벨과 같은 원문*을 함께 가리키고,
                // 스펙도 합집합이라 조각 수치와 목록 인원이 그대로 맞는다.
                val existing = counts[k]
                if (existing == null) {
                    counts[k] = v
                    specs[k] = FieldValueMatchSpec.Values(k)
                } else {
                    counts[k] = existing + v
                    val merged = (specs[k] as? FieldValueMatchSpec.Values)?.values.orEmpty() + k
                    specs[k] = FieldValueMatchSpec.Values(merged)
                }
            }
        }
        return FoldedDistribution(counts, specs, autoBinned = true, preFoldKinds = dist.size)
    }

    /**
     * 통계 파싱의 단일 소스 — 원문 하나를 통계 키 목록으로 (토큰화 → 라벨/카테고리 → 구간).
     *
     * **스냅샷 단위 메모가 앞에 선다** (B-215). 요약 TOP5·인사이트·레거시 분석·교차분석·패턴이
     * 전부 같은 값 표를 이 함수로 지나는데, 결과는 (스냅샷, 파싱 def, 원문)의 순수 함수라
     * 소비처마다 다시 계산할 이유가 없다 — 값 원문은 소수 종으로 크게 겹치므로(값 라이브러리
     * 축) 한 소비처 안에서도 접힌다. 키가 defId 하나가 아니라 **(파싱 def, 원문)**인 이유:
     * 그룹 경로(인사이트·패턴·교차·하위군·드릴다운)는 형제 def의 값도 **그룹 기준 def의
     * 규칙**으로 파싱하므로(R-15, [groupValues]의 계약), 값의 소유 def로 키를 접으면 그
     * 경로의 값 공간이 조용히 바뀐다.
     *
     * **메모는 스냅샷 정본 config([statsParseCacheOf])로 부른 호출에만 적용된다.** 순위 빈도
     * 모드는 statsGroupBy를 "value"로 덮은 사본을 일부러 쓰므로(R-13 — 순위의 축은 값이다)
     * 정본이 아니고, 메모를 지나지 않고 그대로 계산한다 — 사본의 결과가 메모에 섞이면
     * 분포와 순위가 같은 필드에 다른 수를 세는 R-16 위반이 된다. 반환 목록은 공유 사본이다 —
     * 받은 쪽은 읽기만 한다([perSnapshot]과 같은 계약, StatsKeysParityTest가 잠근다).
     */
    private fun getFieldValues(
        s: StatsSnapshot,
        fd: FieldDefinition,
        rawValue: String,
        statsConfig: FieldStatsConfig
    ): List<String> {
        val parse = statsParseCacheOf(s)
        if (statsConfig !== parse.configs[fd.id]) {
            return computeStatsKeys(s, fd, rawValue, statsConfig)
        }
        val perDef = parse.keysByDef[fd.id]
            ?: parse.keysByDef.computeIfAbsent(fd.id) { java.util.concurrent.ConcurrentHashMap() }
        perDef[rawValue]?.let { return it }
        return perDef.computeIfAbsent(rawValue) { computeStatsKeys(s, fd, rawValue, statsConfig) }
    }

    /** [getFieldValues]의 본문 — 메모 없이 항상 그대로 계산한다(정본 밖 config 경로 포함). */
    private fun computeStatsKeys(
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
        if (isBinnable(fd.fieldType) && statsConfig.binning?.mode == "custom") {
            return categorized.map { v ->
                val numeric = v.toFloatOrNull()
                numeric?.let { statsConfig.applyBinning(it) } ?: OUT_OF_RANGE_LABEL
            }
        }

        return categorized
    }

    /**
     * [partIndex]·[separator]는 이 수치가 원문의 **어디에서 나왔는가**다 — 드릴다운 스펙이
     * 같은 자리를 다시 읽으려면 필요하다. 파트 없는 값(NUMBER·CALCULATED)은 기본값
     * (`0`·`""`)으로 원문 전체를 가리킨다.
     */
    private fun computeNumericSummary(
        values: List<Float>,
        binning: FieldStatsConfig.BinningConfig?,
        partIndex: Int = 0,
        separator: String = ""
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

        // 히스토그램 — 막대와 그 막대의 드릴다운 규칙을 **함께** 만든다(B-39).
        // 둘을 따로 만들면 라벨은 있는데 스펙이 없는 막대가 생기고, 그 막대는 눌러도
        // 아무 일이 없거나 0명짜리 시트가 뜬다.
        val histogram = linkedMapOf<String, Int>()
        val specs = linkedMapOf<String, FieldValueMatchSpec>()

        if (binning != null && binning.mode == "custom") {
            val ranges = binning.parseRanges()
            val lastRange = ranges.lastOrNull()
            // 구간 하나의 판정 — **막대를 세는 쪽과 여집합을 세는 쪽이 같은 식을 쓰게** 묶어 둔다.
            // 되풀어 적으면 마지막 구간의 상한 포함 규칙이 한쪽에만 남아, 최댓값이 막대에도 들고
            // 여집합에도 드는(또는 양쪽에서 다 빠지는) 상태가 된다.
            val inRange = { range: FieldStatsConfig.BinRange, v: Float ->
                if (range === lastRange) range.containsInclusive(v) else range.contains(v)
            }
            for (range in ranges) {
                histogram[range.label] = values.count { inRange(range, it) }
                // 열린 구간(`~100`·`200~`)은 ±무한으로 싣는다 — 스펙의 경계는 non-null이고,
                // 무한 비교는 유한값에 대해 '경계 없음'과 정확히 같은 뜻이다.
                specs[range.label] = FieldValueMatchSpec.NumericPartRange(
                    partIndex = partIndex,
                    separator = separator,
                    min = range.min ?: Float.NEGATIVE_INFINITY,
                    max = range.max ?: Float.POSITIVE_INFINITY,
                    inclusiveMax = range === lastRange
                )
            }
            // **어느 구간에도 안 드는 값을 어디에도 세지 않던 자리** (B-197). 구간마다 따로
            // 세므로 `~160`·`180~`처럼 사이가 빈 정의에서는 막대 합이 모집단보다 작아지는데,
            // 그 사실을 말하는 자리가 없었다 — 세계관마다 구간이 다른 필드를 합산하면 형제
            // 세계관의 값이 통째로 사라지기도 한다. 레거시 분포는 이미 반대로 한다
            // ([OUT_OF_RANGE_LABEL] 키로 보이고 드릴다운도 된다 — B-40). 히스토그램만 그 규약
            // 밖이었고, R-17(값이 있는데 안 보이는 것보다 어디에도 안 든다는 사실을 보이는 편이 낫다)의
            // 정반대다.
            //
            // **0이면 막대를 만들지 않는다** — 레거시 분포가 `counted[OUT_OF_RANGE_LABEL]?.let`으로
            // 세우는 그 규칙과 같다. 정의된 구간은 0이어도 남기지만(거기가 비었다는 정보다)
            // 여집합의 0은 *구간 정의가 값을 다 덮었다*는 뜻이라 막대로 말할 것이 없다.
            //
            // **구간 정의가 하나도 파싱되지 않는 구성**(mode는 custom인데 ranges가 빈 경우)에서는
            // 모든 값이 여기로 온다 — 종전에는 히스토그램이 통째로 비어 아무 말도 없었다.
            val outsideCount = values.count { v -> ranges.none { inRange(it, v) } }
            if (outsideCount > 0) {
                // **사용자가 구간 하나를 하필 이 라벨로 지었을 수 있다** — 그때 같은 키로 쓰면
                // 그 구간의 인원과 스펙이 조용히 덮인다(여집합은 그 구간을 *제외한* 것이라
                // 합칠 수도 없다: 뜻이 서로 다르다). 그래서 겹치면 순번을 붙여 갈라 둔다 —
                // `autoBins`가 자기 라벨끼리의 겹침에 대해 이미 쓰는 방식이다.
                var label = OUT_OF_RANGE_LABEL
                var n = 2
                while (label in histogram) label = "$OUT_OF_RANGE_LABEL ($n)".also { n++ }
                histogram[label] = outsideCount
                // 여집합 스펙은 **방금 분포를 그린 그 스펙 목록**을 받는다 — 구간을 다시 짓지 않는다.
                specs[label] = FieldValueMatchSpec.outside(
                    ranges.mapNotNull { specs[it.label] as? FieldValueMatchSpec.NumericPartRange },
                    partIndex,
                    separator
                )
            }
        } else {
            val range = max - min
            if (range <= 0) {
                // 값이 하나뿐이거나 전부 같다 — 나눌 구간이 없다. 구간 하나도 [NumericBinning]이
                // 만든다(`min.toString()`은 Float의 `"170.0"`이라 다른 막대와 모양이 갈렸고,
                // 라벨을 여기서 손으로 지으면 자릿수가 틀려 `170.5`가 `"170~170"`이 된다).
                val only = NumericBinning.singleBin(min)
                histogram[only.label] = values.size
                specs[only.label] = FieldValueMatchSpec.of(only, partIndex, separator)
            } else {
                // 구간 생성은 단일 소스([NumericBinning])를 쓴다 — 자체 5등분은 정수 범위가
                // 좁은 필드(자녀 수 0~2, 레벨 1~3)에서 라벨이 겹쳐 맵 키가 충돌했고,
                // 앞 구간의 인원이 개수 고지도 없이 사라졌다.
                for ((bin, count) in NumericBinning.autoDistribution(values)) {
                    histogram[bin.label] = count
                    specs[bin.label] = FieldValueMatchSpec.of(bin, partIndex, separator)
                }
            }
        }

        return NumericSummaryData(min, max, avg, median, stdDev, histogram, specs)
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
        val group1 = crossFieldGroup(s, s.fieldDefinitions, field1Id) ?: return null
        val group2 = crossFieldGroup(s, s.fieldDefinitions, field2Id) ?: return null
        val filterGroup = if (filterFieldId != null) {
            crossFieldGroup(s, s.fieldDefinitions, filterFieldId) ?: return null
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
        val group1 = crossFieldGroup(s, s.eventFieldDefinitions, field1Id) ?: return null
        val group2 = crossFieldGroup(s, s.eventFieldDefinitions, field2Id) ?: return null
        val filterGroup = if (filterFieldId != null) {
            crossFieldGroup(s, s.eventFieldDefinitions, filterFieldId) ?: return null
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
    private fun crossFieldGroup(s: StatsSnapshot, defs: List<FieldDefinition>, fieldId: Long): CrossFieldGroup? {
        val group = StatsFieldPolicy.expandGroup(defs, fieldId)
        if (group.isEmpty()) return null
        return CrossFieldGroup(
            defs = group.associateBy { it.id },
            configs = group.associate { it.id to statsConfigOf(s, it) }
        )
    }

    /**
     * 교차분석 한 축이 집계하는 필드 묶음. 통계 설정은 스냅샷 정본([statsConfigsOf])의 그
     * 인스턴스다 — 엔티티 루프 안에서 파싱하지 않을 뿐 아니라, [getFieldValues]의 스냅샷
     * 메모를 다른 소비처와 같은 키로 지난다(사본이면 정본 판정에서 갈려 메모를 못 쓴다).
     */
    private class CrossFieldGroup(
        val defs: Map<Long, FieldDefinition>,
        val configs: Map<Long, FieldStatsConfig>
    ) {
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
        if (fd.fieldType == FieldType.BODY_SIZE) return true
        if (StructuredInputConfig.fromConfig(fd.config).enabled) return true
        return FieldValueTokenizer.isMultiToken(fd)
    }

    // ===== 데이터 현황 (신규 - 기존 여러 compute 통합) =====
    fun computeDataOverview(s: StatsSnapshot): DataOverviewStats {
        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val filledDefIdsByChar = filledCharacterDefIds(s)
        // 채움 계수는 접힌 값 표로 — 호출마다 fieldValues 전량(앱에서 가장 큰 컬렉션)을
        // 재그룹하던 것을 걷었다(S6 6차, B-216). 비CALCULATED def의 augmented 버킷은 저장
        // 행 그대로이므로(합성은 CALCULATED def에만 행을 더한다 — [augmentedCharacterValues])
        // 건수 합이 종전의 '저장 비블랭크 계수'와 같다. 대조는 StatsOverviewParityTest.
        val countsByFieldDef = valueCountsOf(s)

        // 미배정 스코프는 novelId 경유가 불가 — 스냅샷에 보존된 정의 전체를 그 캐릭터의
        // 필드셋으로 쓴다. 계산 필드 거르기는 [CompletionRate]가 한다.
        val fieldsForChar: (Character) -> List<FieldDefinition>? = { char ->
            if (s.unassignedScope) s.fieldDefinitions.ifEmpty { null }
            else char.novelId?.let { novelMap[it] }?.let { fieldDefByUniverse[it.universeId] }
        }

        // 그룹별 필드 완성도 — 세 화면이 같은 헬퍼를 쓴다(B-100).
        val completionByGroup = groupCompletionAverages(
            characters = s.characters,
            fieldsForChar = fieldsForChar,
            filledDefIdsByChar = filledDefIdsByChar,
            weights = s.completionWeights
        )

        // 개별 필드별 완성도 (CALCULATED 필드 제외). 미배정 스코프 모수 = 스코프 캐릭터 전체.
        // 모수는 세계관별로 한 번만 센다 — def마다 작품·캐릭터 전수를 필터하던 것(def×캐릭터
        // 곱)을 걷었다(S6 6차 — fa 완성도가 S6 4차에 간 그 길, 세는 조건 무변경).
        val charCountByUniverse = characterCountsByUniverse(s)
        val fieldCompletionDetails = s.fieldDefinitions.filter { it.fieldType != FieldType.CALCULATED }.map { fd ->
            val filled = countsByFieldDef[fd.id]?.values?.sum() ?: 0
            val total = if (s.unassignedScope) s.characters.size
                else charCountByUniverse[fd.universeId] ?: 0
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
            // 전체 스코프에서 작품 미배정은 그대로 '미완성'이다(종전 판정 유지) — 미배정
            // 스코프에서는 그 판정이 스코프 전원을 미완성으로 만들므로 보존 정의 대비로 잰다.
            if (!s.unassignedScope && char.novelId == null) return@count true
            if (!s.unassignedScope && novelMap[char.novelId] == null) return@count true
            val fields = fieldsForChar(char) ?: return@count false
            val rate = CompletionRate.percentOf(
                fields, filledDefIdsByChar[char.id].orEmpty(), s.completionWeights
            ) ?: return@count false   // 셀 칸이 없으면 미완성이 아니다(기존 관용구)
            rate < INCOMPLETE_THRESHOLD_PERCENT
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
            healthWarnings = HealthWarnings(noImageCount, incompleteCount, isolatedCount, unlinkedCount),
            requiredSlotCount = s.fieldDefinitions.count { RequiredFieldGaps.countsAsSlot(it) },
            requiredWeight = s.completionWeights.requiredWeight
        )
    }

    // ===== 커스텀 필드 분석 (레거시) =====
    fun computeFieldAnalysis(s: StatsSnapshot): FieldAnalysisStats {
        // 분포·요약의 대상은 '통계에 포함'된 필드다. 이 화면은 사용자가 필드를 고르는 곳이
        // 아니라 앱이 전부 나열하는 곳이므로 설정을 따른다(S-15, [StatsFieldPolicy]).
        val analyzableFields = analyzableDefs(s, s.fieldDefinitions)

        val fieldValueDists = mutableListOf<FieldValueDistribution>()

        // 계수·파싱은 전부 접힌 값 표(원문 × 건수) 위에서 돈다 — S6 4차·6차. 저장 값 +
        // CALCULATED 계산값(R-16)은 [valueCountsOf]가 augmented 표에서 그대로 물려받고,
        // 건별 토큰·수치 재료화만 없어지고 세는 답은 같다(aug가 블랭크를 걸러 싣는다 —
        // 그 전제와 아래 대조는 StatsFoldParityTest가 잠근다).
        val countsByFieldDef = valueCountsOf(s)

        // ── 이산 값 분포: 값 자체가 분포 키이므로 드릴다운도 값 일치 ──
        for (fd in analyzableFields.filter { isDiscreteDistribution(it.fieldType) }) {
            if (fd.id !in countsByFieldDef) continue
            val statsConfig = statsConfigOf(s, fd)
            val dist = ValueDistributions.sorted(
                foldStatsKeyCounts(s, fd, statsConfig, countsByFieldDef[fd.id].orEmpty())
            )
            if (dist.isEmpty()) continue
            fieldValueDists.add(
                FieldValueDistribution(
                    fd.id, fd.name, fd.type, fd.groupName, dist,
                    matchSpecs = dist.keys.associateWith { FieldValueMatchSpec.Values(it) }
                )
            )
        }

        // 수치 파싱은 한 벌이다 — 자동 구간 분포와 아래 수치 요약이 **같은 원문을 각자
        // 파싱**하던 것을 걷었다(S6 4차). 블랭크·비수치는 파싱이 떨구므로
        // ([NumericBinning.partValue] → null) 블랭크 필터와 결과가 같다.
        // 파싱은 접힌 표의 쌍둥이로 — 고유 원문마다 한 번만 지난다(S6 6차, 인사이트가 쓰는
        // 그 판). 목록 순서는 (첫 등장 × 건수)로 행 순서와 다르나, 소비처(자동 구간·요약)는
        // 전부 다중집합 함수라 답이 같다 — 쌍둥이의 주석과 StatsFoldParityTest가 그 근거다.
        val numericByFieldDef = HashMap<Long, List<Float>>()
        for (fd in analyzableFields) {
            if (!isBinnable(fd.fieldType)) continue
            val counts = countsByFieldDef[fd.id] ?: continue
            numericByFieldDef[fd.id] = NumericBinning.numericValuesOf(counts, "", 0)
        }

        // ── 수치형(NUMBER·CALCULATED) 구간 분포 ──
        // 사용자 구간은 getFieldValues가 구간 라벨을 돌려주므로 라벨이 곧 파싱 값이다(값 일치).
        // 자동 구간은 라벨이 **계산 결과**라 값 일치가 성립하지 않으므로 구간 규칙 자체를
        // 스펙으로 싣는다 — BODY_SIZE 파트 분포가 아래에서 쓰는 그 길이다(B-39).
        for (fd in analyzableFields.filter { isBinnable(it.fieldType) }) {
            val statsConfig = statsConfigOf(s, fd)
            val numericValues = numericByFieldDef[fd.id] ?: continue
            if (statsConfig.binning?.mode != "custom") {
                // 파트가 없는 값이므로 원문 전체가 0번 파트다(B-39 행이 지시한 그 형태).
                val binned = NumericBinning.autoDistribution(numericValues)
                // 값이 2개 미만이거나 폭이 0이면 나눌 구간이 없다 — 분포를 만들지 않는다.
                // (수치 요약은 그래도 나온다: 최소·최대·평균은 값 하나로도 말이 된다.)
                if (binned.isEmpty()) continue
                val dist = linkedMapOf<String, Int>()
                val specs = linkedMapOf<String, FieldValueMatchSpec>()
                for ((bin, count) in binned) {
                    dist[bin.label] = count
                    specs[bin.label] = FieldValueMatchSpec.of(bin, partIndex = 0, separator = "")
                }
                fieldValueDists.add(
                    FieldValueDistribution(
                        fd.id, fd.name, fd.type, fd.groupName, dist,
                        matchSpecs = specs,
                        orderedByValue = true
                    )
                )
                continue
            }
            // **구간 순서를 유지한다** — 건수 내림차순으로 재정렬하면 인접 구간이 흩어져
            // 분포 모양을 읽을 수 없다(BODY_SIZE 자동 구간과 같은 규칙). 정의된 구간은
            // 값이 0이어도 남긴다: 빈 구간도 '거기가 비었다'는 정보다.
            val counted = foldStatsKeyCounts(s, fd, statsConfig, countsByFieldDef[fd.id].orEmpty())
            if (counted.isEmpty()) continue
            val dist = linkedMapOf<String, Int>()
            for (range in statsConfig.binning.parseRanges()) {
                dist[range.label] = counted[range.label] ?: 0
            }
            counted[OUT_OF_RANGE_LABEL]?.let { dist[OUT_OF_RANGE_LABEL] = it }
            // 구간 정의에 없는 키(라벨 변경 등 예외 상황)도 잃지 않는다.
            for ((k, v) in counted) if (k !in dist) dist[k] = v
            fieldValueDists.add(
                FieldValueDistribution(
                    fd.id, fd.name, fd.type, fd.groupName, dist,
                    matchSpecs = dist.keys.associateWith { FieldValueMatchSpec.Values(it) },
                    orderedByValue = true
                )
            )
        }

        // ── BODY_SIZE: 파트별 자동 구간 분포 + 수치 요약 ──
        // 라벨("160~170")은 **계산 결과**라 저장값과 절대 같지 않다. 종전에는 그 라벨을
        // 드릴다운 매칭 키로 그대로 넘겨 어떤 입력에서도 0명이 나왔다(S-16). 이제 구간을
        // 만든 규칙 자체를 스펙으로 실어 보내고, 구간 생성은 [NumericBinning]이 단일 소스다.
        // 파싱은 파트마다 한 벌이다 — 분포와 요약이 **행 전체를 각자 파싱하던 두 벌**을 접힌
        // 표 위의 한 벌로 걷었다(S6 6차). 파트 수의 표본은 접힌 표의 첫 키 — augmented가
        // 블랭크를 걸러 실으므로 첫 행의 값과 같다(전제·대조는 StatsFoldParityTest가 잠근다).
        // 요약은 목록 순서가 화면 순서다(수치 요약 먼저, BODY 나중) — 여기서 모아 두었다가
        // 아래 수치 요약 뒤에 싣는다.
        val bodySummaries = mutableListOf<NumberFieldSummary>()
        for (fd in analyzableFields.filter { it.fieldType == FieldType.BODY_SIZE }) {
            val counts = countsByFieldDef[fd.id] ?: continue
            val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
            val separator = if (structuredConfig.enabled) structuredConfig.separator else "-"
            val partCount = bodySizePartCount(structuredConfig, counts.keys.firstOrNull(), separator)
            val binning = statsConfigOf(s, fd).binning

            for (partIdx in 0 until partCount) {
                val partLabel = bodySizePartLabel(structuredConfig, partIdx)
                val numericValues = NumericBinning.numericValuesOf(counts, separator, partIdx)
                val bins = NumericBinning.autoBins(numericValues)
                if (bins.isNotEmpty()) {
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
                if (numericValues.isNotEmpty()) {
                    numberSummary(
                        "${fd.name} — $partLabel", fd.id, numericValues, binning, partIdx, separator
                    )?.let { bodySummaries.add(it) }
                }
            }
        }

        // ── 수치 요약 ──
        val numberSummaries = mutableListOf<NumberFieldSummary>()

        // NUMBER와 CALCULATED는 같은 수치다 — 수식 필드라고 요약에서 빠질 이유가 없다(S-15).
        // 파싱은 위에서 만든 한 벌을 그대로 쓴다 — 분포와 요약이 같은 수를 세는 근거이기도 하다.
        for (fd in analyzableFields.filter { isBinnable(it.fieldType) }) {
            val values = numericByFieldDef[fd.id] ?: continue
            if (values.isEmpty()) continue
            numberSummary(fd.name, fd.id, values, statsConfigOf(s, fd).binning)
                ?.let { numberSummaries.add(it) }
        }

        // BODY_SIZE 타입: 파트별 수치 요약 (min/max/avg/median) — 파싱·조립은 위 BODY 분포
        // 루프가 한 벌로 끝냈고(S6 6차), 여기는 목록 순서 계약(수치 요약 먼저)대로 싣기만 한다.
        numberSummaries.addAll(bodySummaries)

        // 개별 필드별 완성도 (CALCULATED 필드 제외 — 자동 계산 필드는 항상 100%이므로 의미 없음)
        // '통계에 포함'은 여기에 적용하지 않는다: 완성도는 '분석'이 아니라 '입력 현황'이다.
        // 메모성 필드를 분석에서 뺀 사용자가 그 필드의 입력 누락까지 안 보이길 원한다고
        // 볼 근거가 없다(예외에는 이유를 적는다 — R-16).
        // 모수는 세계관별로 한 번만 센다 — def마다 캐릭터 전수를 필터하던 것(def×캐릭터)을
        // 걷었다(S6 4차). 세는 조건은 종전 그대로다: 캐릭터의 작품이 그 세계관 소속일 것.
        // (인사이트 모수도 같은 셈을 쓴다 — S6 5차에 헬퍼로 모았다.)
        val charCountByUniverse = characterCountsByUniverse(s)
        val fieldCompletionDetails = s.fieldDefinitions
            .filter { it.fieldType != FieldType.CALCULATED }
            .map { fd ->
                // 이 필드가 속한 유니버스의 캐릭터 수. 미배정 스코프 모수 = 스코프 캐릭터 전체
                val filled = countsByFieldDef[fd.id]?.values?.sum() ?: 0
                val total = if (s.unassignedScope) s.characters.size
                    else charCountByUniverse[fd.universeId] ?: 0
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

    /** 구조화 입력 칸 라벨 — 설정이 없으면 '칸N'(가이드 5장 표3: 화면 용어는 '칸'). */
    private fun bodySizePartLabel(config: StructuredInputConfig, partIdx: Int): String =
        if (config.enabled && partIdx < config.parts.size) config.parts[partIdx].label
        else "칸${partIdx + 1}"

    /**
     * 수치 요약 조립 — min/max/avg/median 계산이 세 곳에 흩어지지 않게 한다.
     *
     * **히스토그램도 여기서 만든다**(B-39). 종전에는 이 요약이 값 목록을 통째로 실어 보내고
     * 화면이 자체 8등분을 했다 — 같은 필드가 화면마다 다른 모양이 됐고, 값 목록은 수치 필드
     * 수만큼 스냅샷 파생 객체에 그대로 남았다. 구간을 여기서 정하면 그 목록은 이 함수가
     * 끝나는 자리에서 버려진다.
     */
    private fun numberSummary(
        fieldName: String,
        fieldDefId: Long,
        values: List<Float>,
        binning: FieldStatsConfig.BinningConfig?,
        partIndex: Int = 0,
        separator: String = ""
    ): NumberFieldSummary? {
        // **인사이트 화면과 같은 함수가 전부 계산한다.** 종전에 이 함수는 min/max/median을
        // 자기가 다시 구했는데, 그러면 **같은 목록을 두 번 정렬하고 중앙값 식이 두 벌**이 된다 —
        // 짝수 개일 때의 처리 같은 것이 한쪽에서만 고쳐지면 두 화면의 숫자가 갈린다.
        val summary = computeNumericSummary(values, binning, partIndex, separator) ?: return null
        return NumberFieldSummary(
            fieldName = fieldName,
            min = summary.min,
            max = summary.max,
            avg = summary.avg,
            median = summary.median,
            count = values.size,
            fieldDefId = fieldDefId,
            histogram = summary.histogram,
            matchSpecs = summary.matchSpecs
        )
    }

    /**
     * 작품별 비교 분석 (원칙 05: 데이터 유기적 연결)
     * 전체 스냅샷(필터 미적용)에서 작품별 통계를 나란히 비교할 수 있도록 한다.
     */
    fun computeCrossNovelComparison(s: StatsSnapshot): CrossNovelComparison {
        val charsByNovel = s.characters.groupBy { it.novelId }
        val fieldDefMap = s.fieldDefinitions.associateBy { it.id }
        val statsConfigs = statsConfigsOf(s)
        // 캐릭터 → "필드명:값" 목록. 저장 값 + 계산값(R-16)을 통계 파싱 규칙으로 **한 번만** 풀어 두고,
        // 작품별 집계는 이 버킷을 나눠 쓴다.
        val parsedValuesByCharacter = HashMap<Long, MutableList<String>>()
        for ((fieldDefId, values) in augmentedCharacterValues(s)) {
            val fd = fieldDefMap[fieldDefId] ?: continue
            val cfg = statsConfigs[fieldDefId] ?: continue
            if (!cfg.enabled) continue
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
        val factionMemberCounts = FactionStanding.current(activeMemberships)
            .groupBy { it.factionId }
            .mapNotNull { (factionId, members) ->
                val factionName = factionMap[factionId]?.name ?: return@mapNotNull null
                factionName to members.size
            }
            .toMap()

        // 2개 이상 세력에 속한 캐릭터 수
        val membershipsByChar = FactionStanding.current(activeMemberships)
            .groupBy { it.characterId }
        val multiMemberCharacters = membershipsByChar.count { it.value.size >= 2 }

        // 세력 자동 관계 수 (factionId != null)
        val autoRelationshipCount = s.relationships.count { it.factionId != null }

        // 설정상 탈퇴 수
        val departureCount = s.factionMemberships.count { it.leaveType == FactionMembership.LEAVE_DEPARTED }

        // 세력 미소속 캐릭터 수
        val charsInFactions = FactionStanding.current(activeMemberships)
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

    /** 축 하나의 "이 대상이 이 값을 가졌다" 한 줄 — 저장 행이 없는 계산값을 담을 그릇. */
    private data class AxisValue(val ownerId: Long, val value: String)

    /**
     * 저장 값 + 계산 값을 fieldDefId 버킷으로 모은다 — [augmentedCharacterValues]의 축 일반형.
     * 계산 필드를 함께 싣는 것이 R-16이고, 빈 값을 거르는 것은 분포에 빈 칸이 값으로 서지 않게 하기 위함이다.
     *
     * **캐릭터 축은 이 함수를 쓰지 않는다** — 그쪽은 이미 버킷([augmentedCharacterValues])과
     * 접힌 표([valueCountsOf])가 스냅샷 메모로 서 있고, 여기 통과시키면 값 하나마다 그릇을
     * 새로 지어 *계산 중 할당*이 그만큼 늘어난다. 그래서 [detectFieldPatterns]는 버킷과
     * 접힌 표를 인자로 받고, 이 함수의 결과는 [rawCountsByDef]로 접어 같은 인자 자리에 선다.
     */
    private fun axisValues(
        stored: List<Triple<Long, Long, String>>,          // (fieldDefId, ownerId, value)
        calculated: Map<Long, Map<Long, String>>           // ownerId → (fieldDefId → value)
    ): Map<Long, List<AxisValue>> {
        val byDef = HashMap<Long, MutableList<AxisValue>>()
        for ((defId, ownerId, value) in stored) {
            if (value.isBlank()) continue
            byDef.getOrPut(defId) { mutableListOf() }.add(AxisValue(ownerId, value))
        }
        for ((ownerId, fieldMap) in calculated) {
            for ((defId, value) in fieldMap) {
                if (value.isBlank()) continue
                byDef.getOrPut(defId) { mutableListOf() }.add(AxisValue(ownerId, value))
            }
        }
        return byDef
    }

    /**
     * 한 축의 필드 편중·균형·희소를 감지해 [out]에 담는다 (B-36).
     *
     * **셀 단위는 [axis] 하나가 든다** — 백분율의 분모는 값 건수, 게이트의 모집단은 대상 수이고
     * 문구의 단위("명"/"건"/"개")도 여기서 나온다. 축을 인자로 받는 대신 함수를 세 벌 베끼면
     * 그중 하나만 고쳐지는 날이 오고, 그것이 이 항목이 열린 이유다.
     *
     * 집계는 [countsByDefId]의 **접힌 값 표** 위에서 돈다(S6 5차) — 종전에는 그룹마다 값 행을
     * flatMap으로 재료화하고 행마다 원문을 다시 접었는데, 캐릭터 축은 그 표가 이미 스냅샷
     * 메모([valueCountsOf])로 서 있다. 값의 **행 타입을 열어 둔 것**([ownerOf])은 취향이
     * 아니다 — 모집단(값을 가진 대상 수)은 접힌 표가 말할 수 없어 축마다 이미 갖고 있는
     * 버킷을 그대로 세야 하고, 그 셈은 카드가 실제로 서는 그룹에서만 한다(아래 [ownerCount]).
     */
    /**
     * **작품 간 필드 편중 비교** — 같은 키의 필드가 여러 세계관에 있을 때, 작품마다 주요값이
     * 무엇인지 나란히 놓는다 (*이 작품의 주인공들은 대부분 '검사', 저 작품은 '마법사'*).
     *
     * **축을 인자로 받는 이유는 바로 위 [detectFieldPatterns]와 같다** — 종전에는 이 루프가
     * 캐릭터 `fieldsByKey`만 돌았고(B-195), 12판이 편중·균형·희소를 세 축으로 열면서도 이
     * 자리는 그대로였다. 함수를 축마다 베끼면 그중 하나만 고쳐지는 날이 오고, 사건 축이
     * 통째로 없던 것(B-36)이 애초에 그렇게 생겼다.
     *
     * **작품 축은 부르지 않는다** — 작품 필드값은 작품당 하나라 '작품별 분포'가 자기 자신이다.
     *
     * 세계관 → 대표 작품 대응([firstNovelByUniverse])은 **축과 무관하게 성립한다**: 묶는 기준이
     * 값을 가진 대상이 아니라 **필드 정의가 속한 세계관**이고, 사건 필드 정의도 그것을 갖는다.
     */
    private fun detectCrossNovelFieldBias(
        s: StatsSnapshot,
        axis: PatternAxis,
        fieldGroups: Collection<List<FieldDefinition>>,
        countsByDefId: Map<Long, Map<String, Int>>,
        validUniverseIds: Set<Long>,
        firstNovelByUniverse: Map<Long, com.novelcharacter.app.data.model.Novel>,
        out: MutableList<PatternInsight>
    ) {
        for (fieldDefs in fieldGroups) {
            if (fieldDefs.size < 2) continue
            val novelPatterns = mutableListOf<Pair<String, String>>() // (작품명, 주요값)
            for (fd in fieldDefs) {
                val novel = fd.universeId.takeIf { it in validUniverseIds }
                    ?.let { firstNovelByUniverse[it] } ?: continue
                val statsConfig = statsConfigOf(s, fd)
                val keyCounts = foldStatsKeyCounts(
                    s, fd, statsConfig, countsByDefId[fd.id].orEmpty()
                )
                val total = keyCounts.values.sum()
                val topVal = ValueDistributions.sorted(keyCounts).entries.firstOrNull()
                if (topVal != null && total > 0) {
                    val pct = topVal.value * 100f / total
                    // **이 50%는 민감도가 아니라 정의다** — 작품마다의 '주요값'을 과반으로 잡는
                    // 것이고, 과반이라는 말이 곧 50%다. 편중 기준(사용자 조정)과 섞으면
                    // "주요값"이 작품별로 과반이 아닐 수도 있게 되어 카드 문구가 거짓이 된다.
                    if (pct >= CROSS_NOVEL_MAJORITY_PERCENT) {
                        novelPatterns.add(Pair(novel.title, "${topVal.key}(${String.format("%.0f", pct)}%)"))
                    }
                }
            }
            if (novelPatterns.size >= 2) {
                val fieldName = fieldDefs.first().name
                val desc = novelPatterns.joinToString(", ") { "${it.first}: ${it.second}" }
                out.add(PatternInsight(
                    type = PatternType.CROSS_NOVEL,
                    severity = PatternSeverity.LOW,
                    // 접두는 [axis]가 든다 — 같은 이름의 필드가 축마다 있으면 두 카드가
                    // 똑같이 보인다(원칙 04 — 일일이 열어봐야 아는 데이터를 만들지 않는다).
                    title = "${axis.titlePrefix}$fieldName: 작품별 편중 경향",
                    description = "$desc — 전체적으로 $fieldName 편중 경향이 보입니다.",
                    suggestion = "작품별 다양성 확보를 고려하세요.",
                    axis = axis
                ))
            }
        }
    }

    private fun <T> detectFieldPatterns(
        s: StatsSnapshot,
        axis: PatternAxis,
        fieldGroups: Collection<List<FieldDefinition>>,
        ownerValuesByDefId: Map<Long, List<T>>,
        countsByDefId: Map<Long, Map<String, Int>>,
        ownerOf: (T) -> Long,
        enabledTypes: Set<PatternType>,
        thresholds: PatternThresholds,
        out: MutableList<PatternInsight>
    ) {
        for (fieldDefs in fieldGroups) {
            // 동일 키의 모든 세계관 필드 접힌 표 병합 (단일 def 그룹은 공유 표 그대로 — 읽기만)
            val countsByRaw = mergedRawCounts(countsByDefId, fieldDefs)
            if (countsByRaw.isEmpty()) continue

            val fd = fieldDefs.first()
            val statsConfig = statsConfigOf(s, fd)
            val keyCounts = foldStatsKeyCounts(s, fd, statsConfig, countsByRaw)
            if (keyCounts.isEmpty()) continue

            // 집계·정렬 규칙은 인사이트 분포와 같은 단일 소스를 쓴다 — 동수일 때 두 화면이
            // 서로 다른 값을 '최다'로 지목하지 않게 한다.
            val dist = ValueDistributions.sorted(keyCounts)
            val total = keyCounts.values.sum()
            val fieldName = "${axis.titlePrefix}${fd.name}"
            // 게이트용 '모집단'은 값 개수(total)가 아니라 이 필드에 값을 가진 실제 대상 수(다값
            // 필드 보정). 값은 종전과 같고(같은 행·같은 집합 — 형제 def에 겹쳐 실린 대상은
            // 합집합으로 한 번만 센다), **패턴이 하나도 안 서는 그룹은 세지 않는다**(S6 5차 —
            // 이 수는 게이트 판정이 아니라 선 카드에 실리는 값이라, 셈을 카드가 정한다).
            var ownerCountMemo = -1
            fun ownerCount(): Int {
                var counted = ownerCountMemo
                if (counted < 0) {
                    val seen = HashSet<Long>()
                    for (d in fieldDefs) for (v in ownerValuesByDefId[d.id].orEmpty()) seen.add(ownerOf(v))
                    counted = seen.size
                    ownerCountMemo = counted
                }
                return counted
            }

            // 패턴 1: 편중 (한 값이 기준 % 이상)
            // 이미 (건수 내림차순, 값 이름 오름차순)으로 정렬돼 있다 — maxByOrNull은 동수에서
            // 첫 등장 순서를 따라 인사이트 차트의 1위와 다른 값을 지목할 수 있다.
            val topEntry = dist.entries.firstOrNull()
            if (PatternType.DOMINANCE in enabledTypes && topEntry != null) {
                val topPct = topEntry.value * 100f / total
                if (topPct >= thresholds.dominancePercent) {
                    out.add(PatternInsight(
                        type = PatternType.DOMINANCE,
                        severity = if (topPct >= thresholds.dominanceHighPercent) PatternSeverity.HIGH
                                   else PatternSeverity.MEDIUM,
                        title = "${fieldName}: '${topEntry.key}' 편중",
                        description = "${fieldName} 분포에서 '${topEntry.key}'이(가) ${String.format("%.0f", topPct)}%를 차지하여 편중되어 있습니다.",
                        suggestion = "다양성을 위해 다른 ${fd.name} 값을 가진 ${axis.entityWord} 추가를 고려하세요.",
                        fieldDefId = fd.id,
                        fieldKey = fd.key,
                        fieldType = fd.type,
                        // 기준 def(fd=first)를 맨 앞에 둔다 — 드릴다운도 이 def의 config로 파싱해 %와 일치.
                        mergedFieldDefIds = fieldDefs.map { it.id },
                        // 편중된 그 값(최빈)을 가진 대상을 그대로 펼친다 — "누가 이 편중을 이루나"가 직관적.
                        drilldownValues = listOf(topEntry.key),
                        drilldownExclude = false,
                        population = ownerCount(),
                        axis = axis
                    ))
                }
            }

            // 패턴 2: 균형 (모든 값이 하한~상한 구간에)
            if (PatternType.BALANCE in enabledTypes && dist.size >= 3) {
                val pcts = dist.values.map { it * 100f / total }
                val allBalanced = pcts.all { it in thresholds.balanceMinPercent..thresholds.balanceMaxPercent }
                if (allBalanced) {
                    out.add(PatternInsight(
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
                        population = ownerCount(),
                        axis = axis
                    ))
                }
            }

            // 패턴 3: 이상치 (1건짜리 희소 값이 전체의 기준 % 이하이고, 나머지는 밀집)
            if (PatternType.OUTLIER in enabledTypes && total >= OUTLIER_MIN_VALUES) {
                val singletons = dist.entries.filter { it.value == 1 }
                val singletonPct = singletons.size * 100f / total
                if (singletons.isNotEmpty() && singletonPct <= thresholds.outlierSingletonPercent && dist.size > 3) {
                    val outlierNames = singletons.take(3).joinToString(", ") { "'${it.key}'" }
                    out.add(PatternInsight(
                        type = PatternType.OUTLIER,
                        severity = PatternSeverity.LOW,
                        title = "${fieldName}: 희소 값 발견",
                        description = "${fieldName}에서 $outlierNames 등이 각 1${axis.countUnit}에만 해당됩니다.",
                        suggestion = axis.rarityHint,
                        fieldDefId = fd.id,
                        fieldKey = fd.key,
                        fieldType = fd.type,
                        mergedFieldDefIds = fieldDefs.map { it.id },
                        // 희소 값(각 1건)을 가진 대상 전부를 펼친다.
                        drilldownValues = singletons.map { it.key },
                        drilldownExclude = false,
                        population = ownerCount(),
                        axis = axis
                    ))
                }
            }
        }
    }

    fun detectPatterns(
        s: StatsSnapshot,
        enabledTypes: Set<PatternType> = PatternType.values().toSet(),
        thresholds: PatternThresholds = PatternThresholds.DEFAULT
    ): List<PatternInsight> {
        val insights = mutableListOf<PatternInsight>()

        // 필드값을 fieldDefinitionId로 **한 번만** 그룹화 — 필드 그룹마다 전체 테이블을 재필터하던
        // O(C·F²)를 O(C·F)로(받쳐주는 확장성). 아래 그룹 모집단 셈·작품별 비교가 이 버킷을 재사용한다.
        // 저장 행이 없는 CALCULATED 계산값도 함께 싣는다 — 인사이트 카드에는 분포가 그려지는
        // 수식 필드가 패턴 감지에서만 통째로 빠지면 같은 데이터에 두 화면이 다른 답을 준다(R-16).
        val valuesByDefId: Map<Long, List<CharacterFieldValue>> = augmentedCharacterValues(s)
        // 집계는 그 버킷의 접힌 모양(원문 → 건수) 위에서 돈다 — 캐릭터 축은 스냅샷 메모 그대로(S6 5차).
        val countsByDefId = valueCountsOf(s)
        // '통계에 포함'을 끈 필드는 스스로 나타나지 않는다 — 패턴 감지는 사용자가 필드를 고르는
        // 경로가 아니라 앱이 스스로 고르는 경로이므로 설정을 따른다(S-14, [StatsFieldPolicy]).
        // 작품별 비교에서 필드가 속한 세계관의 대표 작품 조회 — fd마다 novels/universes를 중첩 탐색하던 것 제거.
        val validUniverseIds = s.universes.mapTo(HashSet()) { it.id }
        val firstNovelByUniverse: Map<Long, com.novelcharacter.app.data.model.Novel> =
            s.novels.filter { it.universeId != null }.groupBy { it.universeId!! }.mapValues { it.value.first() }

        // 필드별 분포 패턴 감지
        val fieldsByKey = analyzableDefs(s, s.fieldDefinitions).groupBy { Pair(it.key, it.type) }

        // ── 세 축의 필드 편중·균형·희소 (B-36) ──
        // **축마다 함수를 다시 적지 않는다.** R-13이 나누라는 것은 *셀 단위가 섞이는 것*이지
        // 조립 자체가 아니고("공통 조립만 공유"), 이 자리에서 세 벌을 베끼면 **한쪽만 고쳐지는**
        // 그 결함이 그대로 재생산된다 — 사건 축이 통째로 없던 것(B-36)이 애초에 그렇게 생겼다.
        // 단위·문구·모집단은 [PatternAxis]가 들고 다니므로 섞일 자리가 없다.
        detectFieldPatterns(
            s, PatternAxis.CHARACTER, fieldsByKey.values,
            // 이미 만들어져 있는 버킷·접힌 표를 그대로 준다 — 이 축이 세 축 중 압도적으로 크다.
            ownerValuesByDefId = valuesByDefId,
            countsByDefId = countsByDefId,
            ownerOf = { it.characterId },
            enabledTypes, thresholds, insights
        )
        val eventAxisValues = axisValues(
            stored = s.eventFieldValues.map { Triple(it.fieldDefinitionId, it.eventId, it.value) },
            calculated = computeAllEventCalculatedValues(s)
        )
        // 접힌 표와 키 묶음을 **한 번만** 짓는다 — 아래 작품별 편중 비교(B-195)가 같은 것을 쓴다.
        // 인라인으로 두면 그쪽에서 또 접어 같은 값 표를 두 번 만든다.
        val eventCountsByDefId = rawCountsByDef(eventAxisValues) { it.value }
        val eventFieldsByKey =
            analyzableDefs(s, s.eventFieldDefinitions).groupBy { Pair(it.key, it.type) }
        detectFieldPatterns(
            s, PatternAxis.EVENT,
            eventFieldsByKey.values,
            ownerValuesByDefId = eventAxisValues,
            countsByDefId = eventCountsByDefId,
            ownerOf = { it.ownerId },
            enabledTypes, thresholds, insights
        )
        val novelAxisValues = axisValues(
            stored = s.novelFieldValues.map { Triple(it.fieldDefinitionId, it.novelId, it.value) },
            calculated = computeAllNovelCalculatedValues(s)
        )
        detectFieldPatterns(
            s, PatternAxis.NOVEL,
            analyzableDefs(s, s.novelFieldDefinitions).groupBy { Pair(it.key, it.type) }.values,
            ownerValuesByDefId = novelAxisValues,
            countsByDefId = rawCountsByDef(novelAxisValues) { it.value },
            ownerOf = { it.ownerId },
            enabledTypes, thresholds, insights
        )

        // 패턴 4: 사건 연도 집중 (특정 10년에 50%+ 집중)
        val clusterOrAbsence = PatternType.CLUSTER in enabledTypes || PatternType.ABSENCE in enabledTypes
        if (clusterOrAbsence && s.events.size >= 5) {
            val byDecade = s.events.groupBy { (it.year / 10) * 10 }
            val totalEvents = s.events.size
            val topDecade = byDecade.maxByOrNull { it.value.size }
            if (PatternType.CLUSTER in enabledTypes && topDecade != null) {
                val pct = topDecade.value.size * 100f / totalEvents
                if (pct >= thresholds.clusterPercent) {
                    insights.add(PatternInsight(
                        type = PatternType.CLUSTER,
                        severity = PatternSeverity.MEDIUM,
                        title = "사건 연대 집중",
                        description = "전체 사건의 ${String.format("%.0f", pct)}%가 ${topDecade.key}~${topDecade.key + 9}년에 집중되어 있습니다.",
                        suggestion = "서사적 밀도가 높은 시기입니다. 다른 시기에도 사건을 분산시킬지 검토하세요.",
                        axis = PatternAxis.EVENT
                    ))
                }
            }

            // 공백 구간 — 몇 년부터 '공백'인가는 역법 스케일에 달렸으므로 사용자가 정한다(B-70).
            val years = s.events.map { it.year }.sorted()
            if (PatternType.ABSENCE in enabledTypes && years.size >= 2) {
                val gaps = years.zipWithNext().filter { it.second - it.first > thresholds.absenceGapYears }
                for (gap in gaps.take(2)) {
                    insights.add(PatternInsight(
                        type = PatternType.ABSENCE,
                        severity = PatternSeverity.LOW,
                        title = "서사 공백 구간",
                        description = "${gap.first}년~${gap.second}년 사이에 사건이 없습니다 (${gap.second - gap.first}년 간격).",
                        suggestion = "의도적 공백기인지, 추가할 사건이 있는지 검토하세요.",
                        axis = PatternAxis.EVENT
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
                    if (ratio >= thresholds.crossNovelRatio) {
                        insights.add(PatternInsight(
                            type = PatternType.CROSS_NOVEL,
                            severity = PatternSeverity.MEDIUM,
                            title = "작품 간 캐릭터 수 불균형",
                            description = "'${largest.first}'(${largest.second}명)과 '${smallest.first}'(${smallest.second}명) 사이에 ${String.format("%.1f", ratio)}배 차이가 있습니다.",
                            suggestion = "작품별 서사 규모 차이가 의도적인지 확인하세요."
                        ))
                    }
                }
            }

            // 작품 간 필드 편중 비교 — **축마다 함수를 다시 적지 않는다**(바로 위 편중·균형·희소가
            // 세운 그 규율이고, 사건 축이 통째로 없던 것(B-36)이 애초에 베껴 쓴 탓이다).
            detectCrossNovelFieldBias(
                s, PatternAxis.CHARACTER, fieldsByKey.values, countsByDefId,
                validUniverseIds, firstNovelByUniverse, insights
            )
            // **사건 필드도 작품별로 비교된다** (B-195) — *이 작품의 사건은 대부분 '전투', 저
            // 작품은 '회담'*은 원칙 02가 말하는 실질적 인사이트의 전형이다. 12판이 편중·균형·희소를
            // 세 축으로 열었는데 이 자리까지는 오지 않았다(그 판의 산출물이 그 셋으로 못박혀
            // 있었기 때문이고, 넓히면 실기기 확인 항목이 함께 늘어서다).
            detectCrossNovelFieldBias(
                s, PatternAxis.EVENT, eventFieldsByKey.values, eventCountsByDefId,
                validUniverseIds, firstNovelByUniverse, insights
            )
            // **작품 필드 축은 부르지 않는다** — 작품 필드값은 작품당 하나라 '작품별 분포'가
            // 자기 자신이다(모든 작품이 100% 자기 값이라 카드가 언제나 뜨고 아무것도 말하지 않는다).
            // 빠뜨린 것이 아니라 뜻이 없어서라는 것을 여기 적어 둔다.
        }

        // 패턴: 세력 관련
        if (s.factions.isNotEmpty()) {
            val activeMemberships = FactionStanding.current(s.factionMemberships)
            val factionMemberCounts = activeMemberships.groupBy { it.factionId }.mapValues { it.value.size }

            // 세력이 존재하지만 멤버가 0명인 경우
            val emptyFactions = s.factions.filter { (factionMemberCounts[it.id] ?: 0) == 0 }
            if (PatternType.ABSENCE in enabledTypes && emptyFactions.isNotEmpty()) {
                insights.add(PatternInsight(
                    type = PatternType.ABSENCE,
                    severity = PatternSeverity.MEDIUM,
                    title = "멤버 없는 세력 발견",
                    description = "${emptyFactions.joinToString(", ") { "'${it.name}'" }} 세력에 활성 멤버가 없습니다.",
                    suggestion = "캐릭터를 세력에 배정하거나, 불필요한 세력을 정리하세요."
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
                            suggestion = "대립 구조나 다양성을 위해 다른 세력을 추가하는 것을 고려하세요."
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
     * 대상 필드 정의를 **저장형 / 계산형으로 한 번** 가른 결과 (B-209).
     *
     * @property storedIds 저장 행을 훑을 때 쓰는 id 집합. 값마다 이것 **하나만** 친다.
     * @property calcDefIds 계산(CALCULATED) def의 id — 곧이어 계산값 경로가 그대로 쓴다.
     * @property hasStored 저장 행 경로에 들어갈 것인가. `defById` 기준이며 [storedIds]와 다르다(아래 주).
     */
    private class TargetDefSplit(
        val storedIds: Set<Long>,
        val calcDefIds: List<Long>,
        val hasStored: Boolean
    )

    /**
     * 드릴다운·하위 그룹이 값을 훑기 **전에** 대상 def를 한 번 가른다 (B-209).
     *
     * 종전에는 값마다 `idSet`을 치고 곧바로 같은 id로 `defById`를 다시 쳐서
     * **같은 id로 Map을 두 번**(둘 다 `Long` 박싱) 물었다. 그런데 묻는 것
     * (`fieldType == CALCULATED`)은 **def의 성질**이라 값마다 달라지지 않는다.
     * 게다가 여섯 자리가 곧이어 `defById.values.filter{…}.map{it.id}`로 계산 def id를
     * **다시 지어** 같은 파생을 두 번씩 했다 — 여기서 한 번에 낸다.
     *
     * > **재 보면 이 치환의 값은 1.2배다**(`scalability_performance` 3-10). 비용은 훑는 **건수**에
     * > 있지 조회 횟수에 있지 않다 — 그래서 이 함수는 성능 대책이 아니라 **같은 파생을 한 번만
     * > 하자는 정리**이고, 그 값은 규모와 무관하게 늘 참이다.
     *
     * **[storedIds]가 `defById.keys`가 아니라 [idSet]에서 나오는 것이 이 함수의 핵심이다.**
     * 값은 자기 정의보다 오래 산다 — 캐릭터가 작품을 옮기면 [filterByNovel]이 def를 세계관으로
     * 걸러 내지만 그 캐릭터의 **보관 값은 스냅샷에 남는다**(순위 경로가 *"작품 이동 뒤의 보관 값"*이라
     * 부르는 그것이다). 종전 코드의 `defById[id]?.fieldType == CALCULATED`는 정의가 없을 때
     * **null이라 false**여서 그 값들을 **세고 있었다.** 여기서 `defById.keys`로 좁히면 그 값들이
     * **오류도 고지도 없이 빠진다** — 개발 의도 2번이 금지하는 바로 그 모양이라, 세던 것을 그대로 센다.
     *
     * [hasStored]도 같은 이유로 `defById` 기준이다: 대상이 전부 계산 def이면 종전 코드는 저장 행
     * 경로에 **아예 들어가지 않았고**, 그때 정의 없는 id가 [idSet]에 섞여 있어도 세지 않았다.
     */
    private fun splitTargetDefs(
        idSet: Set<Long>,
        defById: Map<Long, FieldDefinition>
    ): TargetDefSplit {
        val calcDefIds = defById.values.filter { it.fieldType == FieldType.CALCULATED }.map { it.id }
        return TargetDefSplit(
            storedIds = if (calcDefIds.isEmpty()) idSet else idSet - calcDefIds.toSet(),
            calcDefIds = calcDefIds,
            // `defById.values.any { it.fieldType != CALCULATED }`와 같은 값 — calcDefIds가
            // defById의 CALCULATED를 정확히 세므로 개수 비교로 같은 것을 묻는다.
            hasStored = calcDefIds.size < defById.size
        )
    }

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
        val refCfg = statsConfigOf(s, refDef)
        val charMap = s.characters.associateBy { it.id }

        // 캐릭터 한 명이 형제 def·다중값으로 여러 번 매칭돼도 목록에는 한 번만 — 차트 조각은
        // 엔티티 수를 세므로 목록도 같은 단위여야 한다.
        val result = LinkedHashMap<Long, FieldValueCharacter>()
        fun record(charId: Long, shownValue: String) {
            if (result.containsKey(charId)) return
            val char = charMap[charId] ?: return
            val images = try {
                DRILLDOWN_GSON.fromJson(char.imagePaths, Array<String>::class.java)?.toList() ?: emptyList()
            } catch (_: Exception) { emptyList() }
            result[charId] = FieldValueCharacter(
                characterId = char.id,
                characterName = char.name,
                fieldValue = shownValue,
                imageUri = com.novelcharacter.app.util.CharacterRepresentativeImage
                    .pickFrom(images, char.representativeImagePath, drilldownImageSeed, char.id).path
            )
        }

        // 저장 값을 가진 def (CALCULATED는 저장 행이 없다)
        val targets = splitTargetDefs(idSet, defById)
        if (targets.hasStored) {
            for (fv in s.fieldValues) {
                if (fv.fieldDefinitionId !in targets.storedIds) continue
                if (FieldValueMatcher.matches(spec, fv.value) { getFieldValues(s, refDef, fv.value, refCfg) }) {
                    record(fv.characterId, fv.value)
                }
            }
        }

        // CALCULATED def: FormulaEvaluator 계산값으로 매칭
        val calcDefIds = targets.calcDefIds
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
        val refCfg = statsConfigOf(s, refDef)
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

        val targets = splitTargetDefs(idSet, defById)
        if (targets.hasStored) {
            for (fv in s.eventFieldValues) {
                if (fv.fieldDefinitionId !in targets.storedIds) continue
                if (FieldValueMatcher.matches(spec, fv.value) { getFieldValues(s, refDef, fv.value, refCfg) }) {
                    record(fv.eventId, fv.value)
                }
            }
        }

        val calcDefIds = targets.calcDefIds
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
     * 차트 조각(값 하나)을 가진 **작품** 목록 — [getCharactersByFieldValue]의 작품판 (확-3).
     * 캐릭터·사건 축과 **대칭**이다: 카드를 만들어 놓고 조회 경로를 만들지 않으면 조각을 눌렀을 때
     * 항상 0개짜리 빈 시트가 뜬다(S-9가 사건 축에서 겪은 그대로다).
     */
    fun getNovelsByFieldValue(
        s: StatsSnapshot,
        fieldDefIds: List<Long>,
        spec: FieldValueMatchSpec
    ): List<FieldValueNovel>? {
        if (fieldDefIds.isEmpty()) return null
        val idSet = fieldDefIds.toSet()
        val defById = s.novelFieldDefinitions.filter { it.id in idSet }.associateBy { it.id }
        if (defById.isEmpty()) return null

        val refDef = defById[fieldDefIds.first()] ?: defById.values.first()
        val refCfg = statsConfigOf(s, refDef)
        val novelMap = s.novels.associateBy { it.id }

        val result = LinkedHashMap<Long, FieldValueNovel>()
        fun record(novelId: Long, shownValue: String) {
            if (result.containsKey(novelId)) return
            val novel = novelMap[novelId] ?: return
            result[novelId] = FieldValueNovel(
                novelId = novel.id,
                title = novel.title,
                universeId = novel.universeId,
                fieldValue = shownValue
            )
        }

        val targets = splitTargetDefs(idSet, defById)
        if (targets.hasStored) {
            for (fv in s.novelFieldValues) {
                if (fv.fieldDefinitionId !in targets.storedIds) continue
                if (FieldValueMatcher.matches(spec, fv.value) { getFieldValues(s, refDef, fv.value, refCfg) }) {
                    record(fv.novelId, fv.value)
                }
            }
        }

        val calcDefIds = targets.calcDefIds
        if (calcDefIds.isNotEmpty()) {
            val calculatedValues = computeAllNovelCalculatedValues(s)
            for ((novelId, fieldMap) in calculatedValues) {
                for (defId in calcDefIds) {
                    val computedValue = fieldMap[defId] ?: continue
                    if (FieldValueMatcher.matches(spec, computedValue) {
                            getFieldValues(s, refDef, computedValue, refCfg)
                        }) {
                        record(novelId, computedValue)
                    }
                }
            }
        }
        return result.values.sortedBy { it.title }
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
        if (refDef.fieldType != FieldType.CALCULATED) {
            val refCfg = statsConfigOf(s, refDef)
            // 관련 def 값만 순회 — 편향 카드마다 전체 fieldValues를 스캔하던 것 방지(P1-D).
            // 사전 그룹([valuesByDefId])이 있으면 재사용해 카드 수 × 전체스캔의 제곱 폭발을 없앤다.
            val relevant = if (valuesByDefId != null) idSet.flatMap { valuesByDefId[it].orEmpty() }
                           else s.fieldValues.filter { it.fieldDefinitionId in idSet }
            for (fv in relevant) {
                val parsed = getFieldValues(s, refDef, fv.value, refCfg)
                if (parsed.isNotEmpty()) perChar.getOrPut(fv.characterId) { mutableSetOf() }.addAll(parsed)
            }
        }
        val calcDefs = defById.values.filter { it.fieldType == FieldType.CALCULATED }
        if (calcDefs.isNotEmpty()) {
            val calc = computeAllCalculatedValues(s)
            for ((charId, fieldMap) in calc) {
                for (fd in calcDefs) {
                    val v = fieldMap[fd.id] ?: continue
                    val cfg = statsConfigOf(s, fd)
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
                DRILLDOWN_GSON.fromJson(char.imagePaths, Array<String>::class.java)?.toList() ?: emptyList()
            } catch (_: Exception) { emptyList() }
            result.add(
                FieldValueCharacter(
                    characterId = char.id,
                    characterName = char.name,
                    fieldValue = shownValue,
                    imageUri = com.novelcharacter.app.util.CharacterRepresentativeImage
                        .pickFrom(images, char.representativeImagePath, drilldownImageSeed, char.id).path
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
        val refCfg = statsConfigOf(s, refDef)

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
        val targets = splitTargetDefs(idSet, defById)
        for (fv in s.fieldValues) {
            if (fv.fieldDefinitionId !in targets.storedIds) continue
            if (fv.characterId !in characterIds) continue
            record(fv.characterId, fv.value)
        }

        // CALCULATED 계산값 — 부분집합(characterIds)만 집계한다
        val calcDefIds = targets.calcDefIds
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
        val refCfg = statsConfigOf(s, refDef)

        // 캐릭터 축과 **같은 처리**여야 한다(R-16의 짝 규칙) — 여기서도 값 건수가 아니라 대상 수다.
        val holders = HashMap<String, MutableSet<Long>>()
        fun record(entityId: Long, value: String) {
            for (key in getFieldValues(s, refDef, value, refCfg)) {
                holders.getOrPut(key) { HashSet() }.add(entityId)
            }
        }

        val targets = splitTargetDefs(idSet, defById)
        for (fv in s.eventFieldValues) {
            if (fv.fieldDefinitionId !in targets.storedIds) continue
            if (fv.eventId !in eventIds) continue
            record(fv.eventId, fv.value)
        }

        val calcDefIds = targets.calcDefIds
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
     * 작품 ID 집합에 대해 다른 **작품 필드**의 분포를 분석 — [computeSubgroupAnalysis]의 작품판 (확-3).
     * R-13대로 축마다 함수를 나눈다: 셀 단위가 작품 수다.
     */
    fun computeNovelSubgroupAnalysis(
        s: StatsSnapshot,
        novelIds: Set<Long>,
        targetFieldDefIds: List<Long>
    ): SubgroupAnalysis? {
        if (targetFieldDefIds.isEmpty()) return null
        val idSet = targetFieldDefIds.toSet()
        val defById = s.novelFieldDefinitions.filter { it.id in idSet }.associateBy { it.id }
        if (defById.isEmpty()) return null

        val refDef = defById[targetFieldDefIds.first()] ?: defById.values.first()
        val refCfg = statsConfigOf(s, refDef)

        val holders = HashMap<String, MutableSet<Long>>()
        fun record(entityId: Long, value: String) {
            for (key in getFieldValues(s, refDef, value, refCfg)) {
                holders.getOrPut(key) { HashSet() }.add(entityId)
            }
        }

        val targets = splitTargetDefs(idSet, defById)
        for (fv in s.novelFieldValues) {
            if (fv.fieldDefinitionId !in targets.storedIds) continue
            if (fv.novelId !in novelIds) continue
            record(fv.novelId, fv.value)
        }

        val calcDefIds = targets.calcDefIds
        if (calcDefIds.isNotEmpty()) {
            val calculated = computeAllNovelCalculatedValues(s)
            for (novelId in novelIds) {
                val fieldMap = calculated[novelId] ?: continue
                for (defId in calcDefIds) {
                    val v = fieldMap[defId] ?: continue
                    record(novelId, v)
                }
            }
        }

        val counted = ValueDistributions.sorted(holders.mapValues { it.value.size })
        val view = ValueDistributions.view(counted, SUBGROUP_DISTRIBUTION_LIMIT)

        return SubgroupAnalysis(
            targetFieldName = refDef.name,
            distribution = view.shownMap(),
            totalCount = novelIds.size,
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
    /**
     * 스피너가 괄호 안에 적는 종류 표시. **계산과 같은 표를 본다** — 리터럴을 화면에 따로
     * 두면 타입이 늘 때 목록만 뒤처져 사용자에게 거짓을 말한다(`getRankableFields`의 주석).
     */
    fun rankingTypeLabel(fd: FieldDefinition): String = when (fd.fieldType) {
        FieldType.NUMBER -> "숫자"
        FieldType.CALCULATED -> "계산"
        FieldType.GRADE -> "등급"
        FieldType.BODY_SIZE -> "신체"
        FieldType.SELECT, FieldType.TEXT, FieldType.MULTI_TEXT -> "빈도"
        // 모르는 타입은 **저장된 글자를 그대로 보인다** (B-55 — 종전 `else -> type`과 같은 답).
        // 정의를 통째로 받는 것이 그래서다: 이름을 잃으면 사용자가 무엇이 잘못됐는지 못 본다.
        null -> fd.type
    }

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
            val type = primaryFd.fieldType
            // 순위 화면이 "빈도"라고 표시할지 "수치"라고 표시할지는 계산과 **같은 표**를 봐야 한다.
            // 리터럴을 따로 두면 타입이 늘 때 목록 화면만 뒤처져 사용자에게 거짓을 말한다.
            val isNumeric = isNumericRanking(type)
            val bodySizeParts = if (type == FieldType.BODY_SIZE) {
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
     * 순위 화면의 선택지 전부 — **필드 다음에 대결 축**이다 (B-117).
     *
     * 차례를 이렇게 둔 것은 필드가 늘 있고 축은 없을 수도 있기 때문이다. 축을 앞에 두면
     * 축이 하나도 없는 사용자에게는 목록이 그대로인데 있는 사용자에게만 앞이 밀려,
     * *"내가 늘 고르던 그 자리"*가 사람마다 달라진다.
     *
     * **캐릭터 축만 싣는다.** 이미지 축의 참가자는 이미지 경로라 캐릭터 순위표를 만들 수
     * 없다 — 목록에 올려 두면 골랐을 때 빈 표가 뜨고, 그것이 원칙 02가 금지하는 겉핥기다.
     */
    fun rankingSources(s: StatsSnapshot, universeId: Long?): List<RankingSource> {
        val fields = getRankableFields(s, universeId).map { field ->
            RankingSource(
                isDuel = false,
                label = field.fieldDef.name,
                typeLabel = rankingTypeLabel(field.fieldDef),
                rankableField = field
            )
        }
        val axes = s.duelAxes
            .filter { it.targetType == com.novelcharacter.app.data.model.DuelAxis.TARGET_CHARACTER }
            .filter { universeId == null || it.universeId == universeId }
            .sortedWith(compareBy({ it.displayOrder }, { it.name }))
            .map { axis ->
                RankingSource(
                    isDuel = true,
                    label = axis.name,
                    typeLabel = DUEL_TYPE_LABEL,
                    duelAxisCode = axis.code
                )
            }
        return fields + axes
    }

    /**
     * 대결 점수로 매긴 순위 (B-117) — 점수는 [DuelScoreIndex]가 이미 낸 것을 **그대로** 쓴다.
     *
     * 여기서 하는 일은 *참가자 코드를 캐릭터에 잇는 것*뿐이다. 다시 계산하지 않는 것이
     * 이 기능의 요점이고([DuelScoreIndex]의 계약 1), 그래서 **순위표가 보이는 그 수**가
     * 통계에도 뜬다.
     *
     * @param scores 축 하나의 점수표. **스코프로 자르기 전의 것**이어야 한다 —
     *   점수는 축 전체의 기록에서 나온 값이고 작품으로 잘라 다시 적합하면 순위표와 갈린다.
     * @return 이 스코프에 보이는 캐릭터만 담은 표. [RankingResult.excludedCount]는
     *   **점수가 없어 빠진 인원**이다(한 판도 안 치른 캐릭터 — 조용히 빠지지 않는다).
     */
    fun computeDuelRanking(
        s: StatsSnapshot,
        scores: DuelScoreIndex.AxisScores,
        ascending: Boolean = false
    ): RankingResult {
        val novelMap = s.novels.associateBy { it.id }
        val scored = s.characters.filter { scores.scoreOf(it.code) != null }
        val ordered = DuelScoreIndex.sorted(
            scored, ascending, scores, { it.code }, { it.name }
        )

        val entries = ArrayList<RankingEntry>(ordered.size)
        var currentRank = 1
        var previous: Int? = null
        ordered.forEachIndexed { index, char ->
            val score = scores.scoreOf(char.code) ?: return@forEachIndexed
            // 표준 경쟁 순위 — 동점은 같은 등수이고 다음 등수는 그만큼 건너뛴다.
            // **여기서 다시 매기는 것은 이 스코프의 등수이기 때문이다**: 작품 필터가 걸리면
            // 축 전체의 등수(1, 4, 9…)가 뜨는데, 화면이 보이는 것은 그중 몇뿐이라
            // 사용자가 "2위가 없다"고 읽는다. 점수는 그대로이고 등수만 이 표의 것이다.
            if (index > 0 && score != previous) currentRank = index + 1
            previous = score
            entries.add(
                RankingEntry(
                    characterId = char.id,
                    characterName = char.name,
                    rank = currentRank,
                    value = score.toDouble(),
                    // 순위표가 `1523 ±37`로 말하므로 여기도 같은 모양이다 — **믿어도 되는
                    // 줄인가를 행이 스스로 말한다**([DuelStandings]의 규칙 2). 점수만 적으면
                    // 세 판 친 1520과 백 판 친 1520이 같아 보인다.
                    displayValue = scores.entryOf(char.code)
                        ?.let { "$score ±${it.scoreError}" } ?: score.toString(),
                    imagePaths = char.imagePaths,
                    representativeImagePath = char.representativeImagePath,
                    novelTitle = char.novelId?.let { novelMap[it] }?.title
                )
            )
        }

        return RankingResult(
            entries = entries,
            fieldName = scores.axisName,
            fieldType = DUEL_TYPE_LABEL,
            ascending = ascending,
            totalCharacters = entries.size,
            // 이 스코프에 있으면서 점수가 없는 캐릭터 — 한 판도 안 치른 쪽이다.
            excludedCount = s.characters.size - entries.size,
            // **분포는 축 전체로 낸다** — 점수와 같은 이유다(9-3장). 작품으로 잘라 다시 내면
            // *"이 세계관의 강함이 어떤 모양인가"*가 아니라 *"이 작품 사람들만 모은 모양"*이
            // 되는데, 축은 세계관 단위라 그 물음의 답이 아니다.
            scoreDistribution = DuelScoreIndex.distribution(scores).map { (bin, count) ->
                bin.label to count
            }
        )
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
        val isNumeric = isNumericRanking(fd.fieldType)

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
        if (fd.fieldType == FieldType.CALCULATED) {
            val calcDefIds = allFds.filter { it.fieldType == FieldType.CALCULATED }.map { it.id }.toSet()
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
            // 정본이 아니라 **일부러 사본**이다(statsGroupBy만 "value"로 덮는다). 그래서
            // [getFieldValues]의 스냅샷 메모를 지나지 않고 그대로 계산된다 — 카테고리 축
            // 정본의 결과와 이 사본의 결과가 한 메모에 섞이면 분포와 순위가 같은 필드에
            // 다른 수를 세는 R-16 위반이 된다(StatsKeysParityTest가 이 갈림을 잠근다).
            val refCfg = statsConfigOf(s, fd).copy(statsGroupBy = "value")
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

                when (fd.fieldType) {
                    FieldType.NUMBER -> {
                        val v = fv.value.toDoubleOrNull()
                        if (v != null && v.isFinite()) {
                            // 표시는 **저장 원문**이다(GRADE·BODY_SIZE와 같은 규칙). 다시 서식하면
                            // 분포 차트가 보여주는 문자열("170.250")과 순위의 문자열이 갈린다.
                            // 저장 행이 없는 CALCULATED만 서식이 필요하다.
                            putValue(CharValue(char.id, v, fv.value.trim()), ownerUniverseId)
                        } else parseFailed++
                    }
                    FieldType.GRADE -> {
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
                    FieldType.BODY_SIZE -> {
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
                    // 빈도 모드: SELECT, TEXT, MULTI_TEXT + 콤마 목록 표시 형식 TEXT까지 동일 경로.
                    // **CALCULATED는 여기 못 온다** — 바깥 갈래가 이미 갈랐다(아래 `} // else
                    // (non-CALCULATED)`). 그래도 적는 것은 `when`을 전부 덮게 해 새 타입이
                    // 조용히 빠지지 않게 하기 위해서다. 모르는 타입도 이 경로가 맞다 —
                    // 값을 **글자 그대로** 세므로 어떤 값이 와도 거짓을 말하지 않는다(B-55).
                    FieldType.SELECT, FieldType.TEXT, FieldType.MULTI_TEXT,
                    FieldType.CALCULATED, null -> {
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
                    representativeImagePath = char.representativeImagePath,
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
    private fun augmentedCharacterValues(s: StatsSnapshot): Map<Long, List<CharacterFieldValue>> =
        // 요약 TOP5·인사이트·레거시 분석·교차분석·패턴의 다섯 호출부가 같은 스냅샷으로 부른다 —
        // 앱에서 가장 큰 컬렉션(필드값)의 재그룹이므로 스냅샷 단위로 한 번만 짓는다(perSnapshot 규약).
        perSnapshot(augmentedCache, s) { snap ->
            val byFieldDef = snap.fieldValues.filter { it.value.isNotBlank() }
                .groupByTo(HashMap()) { it.fieldDefinitionId }

            for ((charId, fieldMap) in computeAllCalculatedValues(snap)) {
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
            byFieldDef
        }

    /**
     * def별 (원문 → 건수) — [augmentedCharacterValues] 값 표의 접힌 모양 (S6 4차).
     *
     * 값 원문은 소수 종으로 크게 겹치므로(3-14 실측: 행 대비 고유 (def, 원문) 쌍이 두 자릿수
     * 접힘), 건별로 토큰을 재료화해 세던 집계(요약 TOP5 · 이산 분포 · 사용자 구간 계수 ·
     * 작품별 편중)는 **고유 원문 × 건수** 위에서 같은 답을 낸다. 키가 소유 defId인 이유:
     * 원문 건수는 **파싱과 무관**해서다 — 어느 def의 규칙으로 접을지는 호출부가 정하므로
     * (R-15 그룹 파싱 · R-13 순위 사본 그대로), 파싱이 갈리는 자리에서도 이 표는 공유된다.
     *
     * 내부 맵은 **원문의 첫 등장 순서를 유지한다**(LinkedHashMap). 접은 결과의 순서가 화면
     * 순서가 되는 소비처(사용자 구간의 '구간 밖 잔여 키')가 종전과 같은 순서를 받아야 해서다.
     * 반환 맵은 공유 사본이다 — 받은 쪽은 읽기만 한다([perSnapshot] 계약).
     */
    private fun valueCountsOf(s: StatsSnapshot): Map<Long, Map<String, Int>> =
        perSnapshot(valueCountsCache, s) { snap ->
            val out = HashMap<Long, LinkedHashMap<String, Int>>()
            for ((defId, values) in augmentedCharacterValues(snap)) {
                val m = out.getOrPut(defId) { LinkedHashMap() }
                for (fv in values) {
                    if (fv.value.isBlank()) continue
                    m.merge(fv.value, 1) { a, b -> a + b }
                }
            }
            out
        }

    /**
     * (원문 → 건수)를 (통계 키 → 건수)로 접는다 — 원문마다 [getFieldValues]를 한 번만 지난다.
     * 파싱 def·config는 호출부가 정하므로 그룹 파싱(R-15)과 순위 사본(R-13)의 계약이 그대로다.
     * 반환 맵의 키 순서는 (원문 첫 등장 → 그 원문의 토큰 순서)로, 건별 통과의 첫 등장 순서와 같다.
     */
    private fun foldStatsKeyCounts(
        s: StatsSnapshot,
        parseFd: FieldDefinition,
        statsConfig: FieldStatsConfig,
        countsByRaw: Map<String, Int>
    ): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for ((raw, n) in countsByRaw) {
            for (key in getFieldValues(s, parseFd, raw, statsConfig)) {
                out.merge(key, n) { a, b -> a + b }
            }
        }
        return out
    }

    /**
     * (key, type) 그룹의 접힌 값 표 — 단일 def면 공유 표 **그대로**(사본 없음 — 받은 쪽은
     * 읽기만 한다, [perSnapshot] 계약), 여럿이면 def 순서로 병합한 새 표다 (S6 5차).
     *
     * 병합 키 순서는 건별 flatMap 연결의 첫 등장 순서와 같다 — def 안 순서는 각 표가
     * 보존하고([valueCountsOf]), 원문의 첫 등장은 그 원문을 가진 첫 def에서 일어나며,
     * 병합도 def 순서로 지나므로 두 순서가 정확히 겹친다(StatsScanParityTest가 잠근다).
     */
    private fun mergedRawCounts(
        countsByDef: Map<Long, Map<String, Int>>,
        fds: List<FieldDefinition>
    ): Map<String, Int> {
        if (fds.size == 1) return countsByDef[fds[0].id].orEmpty()
        val out = LinkedHashMap<String, Int>()
        for (fd in fds) {
            for ((raw, n) in countsByDef[fd.id].orEmpty()) out.merge(raw, n) { a, b -> a + b }
        }
        return out
    }

    /**
     * 축 값 버킷의 접힌 모양(def별 원문 → 건수) — [valueCountsOf]의 축 일반형.
     * 사건·작품 축은 스냅샷 메모가 없어 호출부가 이것으로 접는다(블랭크는 [axisValues]가
     * 이미 걸렀다). 키 순서는 각 버킷의 첫 등장 순서다.
     */
    private fun <T> rawCountsByDef(
        valuesByDef: Map<Long, List<T>>,
        valueOf: (T) -> String
    ): Map<Long, Map<String, Int>> {
        val out = HashMap<Long, LinkedHashMap<String, Int>>()
        for ((defId, values) in valuesByDef) {
            val m = out.getOrPut(defId) { LinkedHashMap() }
            for (v in values) m.merge(valueOf(v), 1) { a, b -> a + b }
        }
        return out
    }

    /**
     * 세계관별 캐릭터 수(작품 경유) — 모수를 def·그룹마다 캐릭터 전수로 세지 않기 위한
     * 선계수 (S6 4차 완성도 → S6 5차 인사이트 모수가 같은 셈을 쓴다 — R-7).
     * 조건은 종전 그대로다: 캐릭터의 작품이 실재하고, 세는 칸은 그 작품의 세계관이다.
     */
    private fun characterCountsByUniverse(s: StatsSnapshot): HashMap<Long?, Int> {
        val universeByNovelId = HashMap<Long, Long?>()
        for (n in s.novels) universeByNovelId[n.id] = n.universeId
        val out = HashMap<Long?, Int>()
        for (ch in s.characters) {
            val novelId = ch.novelId ?: continue
            if (novelId !in universeByNovelId) continue
            out.merge(universeByNovelId[novelId], 1) { a, b -> a + b }
        }
        return out
    }

    /**
     * 통계가 수치를 문자열로 보일 때의 서식 **단일 소스**.
     *
     * 종전에는 계산 필드 일괄 계산이 `%.2f`, 순위 계산의 자체 분기가 `%.1f`를 써서 **같은 필드가
     * 화면마다 다른 값**으로 보였다(23.46 vs 23.5). 서식이 곧 값의 정체성인 경로(분포 키·
     * 드릴다운 매칭)가 있으므로 서식도 한 곳에서만 정한다.
     */
    private fun formatStatsNumber(value: Double): String =
        // 서식 규칙은 [FormulaDisplay.format] 하나 — 로케일 고정 이유는 그쪽에 적었다.
        // 종전에는 이 함수가 유일하게 로케일을 고정했지만 `private`이라 다른 다섯 곳이
        // 기본 로케일 `"%.2f"`를 쓰고 있었다(U-9).
        //
        // **오류 표식은 여기서 쓰지 않는다.** 이 문자열은 표시용이 아니라 값의 정체성이다 —
        // 분포의 키가 되고, 드릴다운 매칭 키가 되고, 레거시 수치 요약에서 다시 toFloat로
        // 파싱된다. "오류"를 넣으면 그것이 분포 항목 하나로 잡히고 되파싱이 깨진다.
        // 통계에서 NaN·Inf는 [computeAllCalculatedNumbers]가 애초에 제외한다.
        com.novelcharacter.app.util.FormulaDisplay.format(value)

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
    private fun computeAllCalculatedNumbers(s: StatsSnapshot): Map<Long, Map<Long, Double>> =
        perSnapshot(calcCache, s) { evaluateAllCalculatedNumbers(it) }

    /**
     * 스냅샷 순수 함수의 **스냅샷 단위 메모이즈** — 이 파일의 캐시 규약 단일 자리.
     *
     * 통계 로딩은 계산 10개를 `async`로 동시에 돌리고(StatsViewModel), 그 사이 다른 화면이
     * **다른 스냅샷**(작품별 비교는 원본)으로 들어온다. 한 칸짜리 캐시는 서로를 밀어내고
     * 키/값을 따로 게시하면 짝이 어긋난다 — 스냅샷 동일성을 키로 하는 작은 동시 맵을 쓰고,
     * `computeIfAbsent`가 같은 스냅샷의 동시 첫 호출을 하나로 묶는다(하나가 짓고 나머지는
     * 결과를 기다린다 — 중복 CPU와 동시 힙 피크가 함께 준다).
     *
     * 종전에는 이 규약이 수식 평가([calcCache])와 라벨 해석([resolversOf])에만 있었고,
     * 같은 성질(불변 스냅샷의 순수 함수)인 [augmentedCharacterValues]·[filledCharacterDefIds]·
     * [computeCharacterComplexities]·사건/작품 계산값은 호출부마다 전부 다시 지었다 —
     * 그 겹이 화면 한 번 적재에서 차지하던 몫은 `scalability_performance_2026-07.md` 3-13이
     * 든다(수치를 여기 병기하지 않는 것은 일부러다 — 두 자리에 살면 한쪽이 낡는다).
     *
     * **돌려주는 값은 공유 사본이다 — 받은 쪽은 읽기만 한다.** 변조하면 같은 스냅샷의 다른
     * 계산이 오염된 결과를 본다. 이 계약은 StatsMemoParityTest가 "모든 계산을 돌린 뒤 캐시
     * 내용 = 새로 지은 것" 대조로 잠근다.
     */
    private fun <T : Any> perSnapshot(
        cache: java.util.concurrent.ConcurrentHashMap<IdentityKey, T>,
        s: StatsSnapshot,
        build: (StatsSnapshot) -> T
    ): T {
        if (cache.size > MAX_CACHED_SNAPSHOTS) cache.clear()
        return cache.computeIfAbsent(IdentityKey(s)) { build(s) }
    }

    /** 동일성(===)으로만 같은 키. 스냅샷은 불변이므로 같은 객체면 결과도 같다. */
    private class IdentityKey(val target: Any) {
        override fun hashCode(): Int = System.identityHashCode(target)
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.target === target
    }

    private val calcCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, Map<Long, Map<Long, Double>>>()
    private val augmentedCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, Map<Long, List<CharacterFieldValue>>>()
    private val valueCountsCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, Map<Long, Map<String, Int>>>()
    private val filledDefIdsCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, Map<Long, Set<Long>>>()
    private val complexitiesCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, List<CharacterComplexity>>()
    private val eventCalcCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, Map<Long, Map<Long, String>>>()
    private val novelCalcCache =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, Map<Long, Map<Long, String>>>()

    /**
     * 스냅샷 하나의 통계 파싱 캐시 (B-215) — 두 겹이 한 묶음이다.
     *
     * - [configs]: 통계 설정의 **정본** (defId → 파싱 결과, 세 축 전부). 종전에는 소비처마다
     *   `StatsFieldPolicy.ConfigCache`를 새로 만들어 같은 config JSON을 화면 적재당 소비처
     *   수만큼 다시 파싱했고, **인스턴스가 갈려 있어 그 아래의 어떤 공유도 설 수 없었다.**
     *   정의 테이블은 하나라 세 축의 id가 겹치지 않는다(entityType은 열이다).
     * - [keysByDef]: (파싱 defId, 원문) → 통계 키 목록 메모. [getFieldValues]가 정본 config로
     *   불린 호출만 여기 접는다.
     *
     * 묶음인 이유: 정본 판정과 메모 조회가 값마다 한 번씩 일어나므로, 스냅샷 키 조회를
     * 한 번으로 줄인다([statsParseCacheOf]의 슬롯과 함께 값 루프의 고정비를 없앤다).
     */
    private class StatsParseCache(val configs: Map<Long, FieldStatsConfig>) {
        val keysByDef =
            java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<String, List<String>>>()
    }

    /** 마지막 스냅샷의 파싱 캐시 fast path — 값마다 [IdentityKey]를 만들지 않기 위한 한 칸.
     *  (키와 값을 한 객체로 게시한다 — [ResolverCache]와 같은 이유다.) */
    private class ParseSlot(val snapshot: StatsSnapshot, val cache: StatsParseCache)

    @Volatile private var lastParseSlot: ParseSlot? = null
    private val statsParseCaches =
        java.util.concurrent.ConcurrentHashMap<IdentityKey, StatsParseCache>()

    private fun statsParseCacheOf(s: StatsSnapshot): StatsParseCache {
        lastParseSlot?.let { if (it.snapshot === s) return it.cache }
        val built = perSnapshot(statsParseCaches, s) { snap ->
            val configs = HashMap<Long, FieldStatsConfig>()
            for (fd in snap.fieldDefinitions) configs[fd.id] = FieldStatsConfig.fromConfig(fd.config)
            for (fd in snap.eventFieldDefinitions) configs[fd.id] = FieldStatsConfig.fromConfig(fd.config)
            for (fd in snap.novelFieldDefinitions) configs[fd.id] = FieldStatsConfig.fromConfig(fd.config)
            StatsParseCache(configs)
        }
        lastParseSlot = ParseSlot(s, built)
        return built
    }

    /** 통계 설정 정본 — 소비처는 이 맵(또는 [statsConfigOf])으로 config를 얻어야 메모를 지난다. */
    private fun statsConfigsOf(s: StatsSnapshot): Map<Long, FieldStatsConfig> = statsParseCacheOf(s).configs

    /** 정본 config 하나 — 스냅샷 밖 def(방어적 폴백)는 그 자리에서 파싱하되 메모 대상이 아니다. */
    private fun statsConfigOf(s: StatsSnapshot, fd: FieldDefinition): FieldStatsConfig =
        statsConfigsOf(s)[fd.id] ?: FieldStatsConfig.fromConfig(fd.config)

    /** '통계에 포함' 필터 — [StatsFieldPolicy.analyzable]과 같은 판정을 정본 config로 낸다. */
    private fun analyzableDefs(s: StatsSnapshot, defs: List<FieldDefinition>): List<FieldDefinition> =
        defs.filter { statsConfigOf(s, it).enabled }

    private fun evaluateAllCalculatedNumbers(s: StatsSnapshot): Map<Long, Map<Long, Double>> {
        val calculatedFields = s.fieldDefinitions.filter { it.fieldType == FieldType.CALCULATED }
        if (calculatedFields.isEmpty()) return emptyMap()

        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val allFieldDefById = s.fieldDefinitions.associateBy { it.id }
        val charFieldValues = s.fieldValues.groupBy { it.characterId }

        // 세계관별 CALCULATED 필드와 수식을 미리 파싱
        data class CalcFieldInfo(val fd: FieldDefinition, val formula: String)
        val calcFieldsByUniverse = mutableMapOf<Long?, List<CalcFieldInfo>>()
        for ((universeId, fields) in fieldDefByUniverse) {
            val calcInfos = fields.filter { it.fieldType == FieldType.CALCULATED }.mapNotNull { fd ->
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
    private fun computeAllEventCalculatedValues(s: StatsSnapshot): Map<Long, Map<Long, String>> =
        // 인사이트·패턴·교차 분석·드릴다운이 같은 스냅샷으로 부른다. 캐릭터 축(calcCache)과 달리
        // 이 축은 캐시가 아예 없어 부를 때마다 수식 전량을 재평가했다 — 같은 규약으로 맞춘다.
        perSnapshot(eventCalcCache, s) { buildAllEventCalculatedValues(it) }

    private fun buildAllEventCalculatedValues(s: StatsSnapshot): Map<Long, Map<Long, String>> {
        val calculatedFields = s.eventFieldDefinitions.filter { it.fieldType == FieldType.CALCULATED }
        if (calculatedFields.isEmpty()) return emptyMap()

        val fieldDefByUniverse = s.eventFieldDefinitions.groupBy { it.universeId }
        val allFieldDefById = s.eventFieldDefinitions.associateBy { it.id }
        val valuesByEvent = s.eventFieldValues.groupBy { it.eventId }

        data class CalcFieldInfo(val fd: FieldDefinition, val formula: String)
        val calcFieldsByUniverse = mutableMapOf<Long?, List<CalcFieldInfo>>()
        for ((universeId, fields) in fieldDefByUniverse) {
            val calcInfos = fields.filter { it.fieldType == FieldType.CALCULATED }.mapNotNull { fd ->
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
     * 작품 CALCULATED 필드 일괄 계산 — [computeAllEventCalculatedValues]의 작품판 (확-3).
     *
     * 축이 셋이 됐으므로 계산도 셋이다. 한 축만 두면 그 축의 계산 필드는 목록에는 뜨는데
     * 분포가 영원히 비고, 그것이 원칙 02가 말하는 '껍데기 구현'이다.
     * @return Map<novelId, Map<fieldDefinitionId, 계산값 문자열>>
     */
    private fun computeAllNovelCalculatedValues(s: StatsSnapshot): Map<Long, Map<Long, String>> =
        // 사건 축과 같은 이유 · 같은 규약([computeAllEventCalculatedValues] 참조).
        perSnapshot(novelCalcCache, s) { buildAllNovelCalculatedValues(it) }

    private fun buildAllNovelCalculatedValues(s: StatsSnapshot): Map<Long, Map<Long, String>> {
        val calculatedFields = s.novelFieldDefinitions.filter { it.fieldType == FieldType.CALCULATED }
        if (calculatedFields.isEmpty()) return emptyMap()

        val fieldDefByUniverse = s.novelFieldDefinitions.groupBy { it.universeId }
        val allFieldDefById = s.novelFieldDefinitions.associateBy { it.id }
        val valuesByNovel = s.novelFieldValues.groupBy { it.novelId }

        data class CalcFieldInfo(val fd: FieldDefinition, val formula: String)
        val calcFieldsByUniverse = mutableMapOf<Long?, List<CalcFieldInfo>>()
        for ((universeId, fields) in fieldDefByUniverse) {
            val calcInfos = fields.filter { it.fieldType == FieldType.CALCULATED }.mapNotNull { fd ->
                val formula = try {
                    org.json.JSONObject(fd.config).optString("formula", "")
                } catch (_: Exception) { "" }
                if (formula.isNotBlank()) CalcFieldInfo(fd, formula) else null
            }
            if (calcInfos.isNotEmpty()) calcFieldsByUniverse[universeId] = calcInfos
        }
        if (calcFieldsByUniverse.isEmpty()) return emptyMap()

        val result = mutableMapOf<Long, MutableMap<Long, String>>()
        for (novel in s.novels) {
            val universeId = novel.universeId ?: continue
            val calcInfos = calcFieldsByUniverse[universeId] ?: continue
            val universeFields = fieldDefByUniverse[universeId] ?: continue

            val values = valuesByNovel[novel.id] ?: emptyList()
            val fieldKeyValues = mutableMapOf<String, String>()
            for (fv in values) {
                val fDef = allFieldDefById[fv.fieldDefinitionId] ?: continue
                fieldKeyValues[fDef.key] = fv.value
            }

            val evaluator = FormulaEvaluator(fieldKeyValues, universeFields)
            val novelCalcValues = mutableMapOf<Long, String>()
            for ((fd, formula) in calcInfos) {
                try {
                    val value = evaluator.evaluate(formula)
                    if (!value.isNaN() && !value.isInfinite()) {
                        // 서식은 다른 축과 같은 단일 소스를 쓴다 — 같은 수식이 축마다 다른
                        // 문자열이 되면 분포 키가 갈라진다(R-22).
                        novelCalcValues[fd.id] = formatStatsNumber(value)
                    }
                } catch (_: Exception) { /* 평가 실패 시 해당 필드 제외 */ }
            }
            if (novelCalcValues.isNotEmpty()) {
                result[novel.id] = novelCalcValues
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
        /**
         * 드릴다운 목록이 캐릭터 이미지 경로를 읽는 데 쓰는 **공용** Gson.
         *
         * 종전에는 `record()` 안에서 `Gson()`을 **행마다 새로** 만들었다. Gson 생성은 타입
         * 어댑터 팩토리를 통째로 짓는 무거운 작업이라, 300명짜리 조각을 누르면 300번을 짓는다.
         * 인스턴스는 상태가 없고 스레드 안전하므로 한 벌을 돌려쓴다 — B-39가 히스토그램 둘을
         * 이 경로로 새로 흘려보내면서 함께 고쳤다.
         */
        private val DRILLDOWN_GSON = com.google.gson.Gson()

        /**
         * 사용자 구간(binning) 설정을 받는 **연속 수치** 타입.
         *
         * 집합이 아니라 `when`인 것이 요점이다 (B-55) — 종전 주석은 *"타입이 늘면 여기만
         * 고친다"*였는데, 집합은 **안 고쳐도 아무 일이 없다.** 새 타입은 구간 설정이 조용히
         * 안 뜨고 사용자는 그것이 의도인지 누락인지 알 길이 없다.
         *
         * [FieldType.GRADE]·[FieldType.BODY_SIZE]가 빠진 것은 수를 못 내서가 아니라
         * **연속값이 아니어서다** — 등급은 라벨이 유한하고, 체형은 파트를 먼저 골라야 한다.
         */
        private fun isBinnable(type: FieldType?): Boolean = when (type) {
            FieldType.NUMBER, FieldType.CALCULATED -> true
            FieldType.GRADE, FieldType.BODY_SIZE -> false
            FieldType.TEXT, FieldType.SELECT, FieldType.MULTI_TEXT -> false
            null -> false
        }

        /**
         * 순위에서 값을 수치로 해석하는 타입(그 외는 빈도 모드).
         *
         * **판정은 [FieldValueSorter.isNumericSortType]가 한다** (B-55) — 같은 집합이 종전에
         * 세 벌이었고(여기 · 그쪽 · 읽기 화면), 갈리면 목록에서 수로 줄 세워지는 필드가
         * 통계에서는 빈도로 세진다. 위 KDoc이 이미 *"계산과 같은 표를 본다"*고 적어 둔 약속이다.
         */
        private fun isNumericRanking(type: FieldType?): Boolean =
            FieldValueSorter.isNumericSortType(type)

        /** 사용자 구간 어디에도 들지 않는 값의 표시 키 — 조용히 버리지 않는다(R-17). */
        const val OUT_OF_RANGE_LABEL = "구간 밖"

        /** 계산값 캐시가 들고 있을 스냅샷 수 상한 — 넘으면 통째로 비운다(무한 축적 방지). */
        private const val MAX_CACHED_SNAPSHOTS = 4

        /** 레거시 필드 분석 화면이 이산 분포를 그리는 타입 (B-55 — 종전 집합). */
        private fun isDiscreteDistribution(type: FieldType?): Boolean = when (type) {
            FieldType.SELECT, FieldType.GRADE, FieldType.MULTI_TEXT, FieldType.TEXT -> true
            // 연속값은 구간(binning) 쪽이 그린다. CALCULATED는 저장 행이 없어 별도 경로다.
            FieldType.NUMBER, FieldType.CALCULATED, FieldType.BODY_SIZE -> false
            null -> false
        }

        /**
         * '입력이 미흡하다'로 세는 완성도 하한(%).
         *
         * 데이터 건강의 **명단**과 데이터 개요의 **개수**가 같아야 하므로 값은 하나여야 한다 —
         * 종전에는 `50f`와 `0.5f`로 두 곳에 따로 박혀 있었다.
         */
        const val INCOMPLETE_THRESHOLD_PERCENT = 50f
    }
}
