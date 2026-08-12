package com.novelcharacter.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 구조화 입력 파트가 몸의 어느 부위를 재는가.
 *
 * 파트 라벨은 사용자가 자유롭게 짓는다("B"·"가슴"·"윗둘레"…). 그래서 분석·실루엣이
 * 라벨을 직접 읽으면 새 표기마다 조용히 빠진다 — 부위는 라벨이 아니라 이 슬롯이 든다.
 * 해석 사다리(명시 매핑 → 라벨 추론 → 위치 폴백)는 [com.novelcharacter.app.util.BodyMeasurements]가
 * 단일 소스로 갖는다.
 *
 * [UNDERBUST]는 선택 슬롯이다 — 매핑돼 있으면 컵 계산이 실측 밑가슴을 쓰고,
 * 없으면 현행 근사(허리 + [BodyAnalysisConfig.ribOffset])로 동작한다.
 */
enum class BodySlot { BUST, UNDERBUST, WAIST, HIP, SHOULDER, NONE }

/**
 * 체형 분석 인사이트 설정.
 * FieldDefinition.config JSON의 "bodyAnalysis" 객체에 저장됨.
 *
 * - cupMapping: 컵 사이즈 산출 매핑 (bust-underbust 차이 → 라벨)
 * - bodyTypeRules: 체형 분류 규칙 (조건 기반, priority 순 평가)
 * - enabledInsights: 인사이트 항목별 표시/숨김 토글
 * - partSlots: 구조화 입력 파트 인덱스 → [BodySlot] 명시 매핑 (비어 있으면 추론)
 * - generation: 🎲 생성 축·프리셋 ([GenerationPreset] — B-93)
 *
 * config에 "bodyAnalysis" 키가 없으면 DEFAULT를 사용하므로
 * 기존 데이터의 마이그레이션이 불필요하다.
 *
 * **파싱이 읽지 않는 키는 [unusedKeysIn]이 센다**(B-95 · P-5). 없앤 설정이 담긴 옛 파일을
 * 들일 때 그 사실을 조용히 삼키지 않기 위해서다 — 세는 쪽이 [READ_KEYS] 하나이므로
 * 키를 새로 읽기 시작하면 그 순간 '모르는 키'에서 빠진다(적어 두면 낡는 부류를 만들지 않는다).
 */
data class BodyAnalysisConfig(
    val cupMapping: List<CupMappingEntry> = DEFAULT_CUP_MAPPING,
    val bodyTypeRules: List<BodyTypeRule> = DEFAULT_BODY_TYPE_RULES,
    val defaultBodyType: String = "보통체형",
    val enabledInsights: Map<String, Boolean> = DEFAULT_ENABLED_INSIGHTS,
    val ribOffset: Double = DEFAULT_RIB_OFFSET,                          // 흉곽 보정 — 밑가슴 = 허리 + 이 값
    val bodyTagRules: List<BodyTagRule> = emptyList(),                   // 다층 태그 (비어있으면 bodyTypeRules에서 변환)
    // 목표 비율 이상값 — **비어 있으면 자동**(이상 몸 → 장르 기준 순 —
    // BodyGenerator.genreTargetIdeals, 키·흉곽 보정 적응). 키별로 직접 정한 값만 담기며,
    // 담긴 키가 자동을 이긴다.
    // 종전 기본(황금비 계열 .70/1.00/.40/.52)은 P8 '황금비 잔재 제거'로 소거됐다(2026.08.02).
    val goldenRatioIdeals: Map<String, Double> = emptyMap(),
    val partSlots: List<BodySlot> = emptyList(),                         // 파트 인덱스 → 부위 (비어있으면 추론)
    // 이상 몸(치수 입력 — P8 '사용자 이상형', 2026.08.02 사용자 요청). 셋(B·W·H)이 다
    // 있어야 효력이 있고, 비율 환산·키 적응은 BodyGenerator.idealsFromBody가 든다.
    // 부분 입력도 저장은 한다 — 적다 만 값을 버리면 말없는 유실이다(R-27 결).
    val idealBody: IdealBody? = null,
    // 🎲 생성 축·프리셋 — 세계관 단위 사용자 정의(B-93 · 확정 7번). 기본값이면 저장하지 않는다.
    val generation: GenerationPreset = GenerationPreset()
) {
    /**
     * 사용자가 치수로 적은 이상 몸. [heightCm]가 없으면 기준 몸 키로 읽는다.
     *
     * 저장은 적은 그대로(원문 보존), 해석은 평가 시점에 한다 — 흉곽 보정을 나중에 바꿔도
     * 이 몸의 컵차가 그 시점 규약으로 다시 계산된다(늦은 해석 — `BodyMeasurements` 선례).
     */
    data class IdealBody(
        val bust: Double? = null,
        val waist: Double? = null,
        val hip: Double? = null,
        val heightCm: Double? = null
    ) {
        /** 비율을 낼 수 있는가 — 세 치수가 전부 양수여야 한다. */
        val isComplete: Boolean
            get() = (bust ?: 0.0) > 0 && (waist ?: 0.0) > 0 && (hip ?: 0.0) > 0

        val isEmpty: Boolean
            get() = bust == null && waist == null && hip == null && heightCm == null
    }

    // ══════════════════════════════════════════════════════════════════════
    // 🎲 생성 축·프리셋 (B-93 · 확정 7번 ㄱ1·ㄴ1)
    // ══════════════════════════════════════════════════════════════════════

    /** 키 축 — 중심 키(cm)와 흔들림 폭. [variance]가 0이면 흔들리지 않는다(정확히 [center]). */
    data class HeightOption(val label: String, val center: Double, val variance: Double)

    /**
     * 몸통 축 — 허리를 키 비율로 잡는다(마름 정도의 수치 실체가 허리/키다).
     *
     * [maxRatio]는 **이 축이 끝나는 자리**(허리÷키)이고, 축의 구간은 목록 순서에서 파생된다 —
     * 앞 축의 [maxRatio]부터 자기 [maxRatio]까지, 첫 축은 0부터([GenerationPreset.torsoBand]).
     * **경계를 축마다 한 번씩만 적는 것이 이 형태의 요점이다**: 종전에는 구간의 양 끝을
     * 이웃한 두 축이 각각 들고 있어 한쪽만 고치면 틈이나 겹침이 생겼다.
     * 마지막 축의 [maxRatio]는 열린 끝이라 요약이 그 위의 값도 이 축으로 읽는다.
     */
    data class TorsoOption(
        val label: String,
        val waistRatio: Double,     // 허리 ÷ 키 (구간 한가운데를 겨눈다)
        val bmiTarget: Double,      // 목표 BMI (몸무게 역산용)
        val maxRatio: Double        // 이 축의 끝 (허리÷키)
    )

    /**
     * 가슴 축 — **컵차 목표(cm)**. 가슴 = 허리 + 흉곽 보정 + 이 값이다.
     *
     * 종전에는 "허리 대비 증가량"(보정 포함 값)이었다 — 보정이 기본 6일 때만 라벨과 컵이
     * 맞고, 사용자가 보정을 바꾸면 아담을 골라도 D가 나왔다(B-92).
     * 구간이 없는 것은 일부러다 — 돌아오는 컵 글자는 [cupMapping] 하나가 정하므로,
     * 여기 경계를 또 적으면 같은 사실이 두 자리에 산다.
     */
    data class BustOption(val label: String, val cupDiff: Double)

    /** 힙 축 — 허리 대비 증가량(cm). [maxDiff]의 뜻은 [TorsoOption.maxRatio]와 같다. */
    data class HipOption(val label: String, val hipBonus: Double, val maxDiff: Double)

    /**
     * 체형 프리셋 — 세 축을 한 번에 세우는 러프 경로. 인덱스는 같은 [GenerationPreset]의
     * 축 목록 기준이다. 프리셋을 고른 뒤 축을 따로 바꾸는 것이 정밀 경로다(둘은 배타가 아니다).
     */
    data class BodyPreset(val label: String, val torso: Int, val bust: Int, val hip: Int)

    /**
     * 🎲 생성기가 쓰는 축과 프리셋 한 벌 — **세계관 단위로 사용자가 정한다**
     * (B-93 · 확정 7번: ㄱ1 세계관 단위 · ㄴ1 수치와 이름만, **단계 개수는 열지 않는다**).
     *
     * 필드 정의가 이미 세계관 소속이므로 *"세계관 단위"*는 곧 이 config다 — 별도 표도,
     * 마이그레이션도 없다. `bodyAnalysis.generation`이 없는 필드는 아래 기본값 그대로 돈다.
     *
     * **개수를 열지 않는 것이 계약이다:** 파싱은 언제나 기본 목록과 같은 길이를 돌려준다
     * (모자라면 기본값으로 채우고 넘치면 자른다 — [sanitized]). 그래서 프리셋의 축 인덱스가
     * 가리킬 자리가 사라지지 않고, 화면도 늘 같은 수의 칸을 그린다.
     *
     * **축 구간은 이 목록에서 파생된다**([torsoBand]·[hipBand]) — 생성기가 겨누는 자리와
     * 요약이 가르는 자리가 같은 배열 하나에서 나오므로, 사용자가 경계를 옮겨도
     * *고른 축과 돌아오는 요약*이 갈라질 수 없다(`BodySilhouetteSpec.axisSummary`).
     */
    data class GenerationPreset(
        val heightOptions: List<HeightOption> = DEFAULT_HEIGHT_OPTIONS,
        val torsoOptions: List<TorsoOption> = DEFAULT_TORSO_OPTIONS,
        val bustOptions: List<BustOption> = DEFAULT_BUST_OPTIONS,
        val hipOptions: List<HipOption> = DEFAULT_HIP_OPTIONS,
        val bodyPresets: List<BodyPreset> = DEFAULT_BODY_PRESETS
    ) {
        /** 몸통 축 [index]의 구간(허리÷키). 앞 축의 끝에서 자기 끝까지, 첫 축은 0부터. */
        fun torsoBand(index: Int): ClosedFloatingPointRange<Double> =
            bandOf(index, torsoOptions.map { it.maxRatio })

        /** 힙 축 [index]의 구간(엉덩이−허리 cm). 뜻은 [torsoBand]와 같다. */
        fun hipBand(index: Int): ClosedFloatingPointRange<Double> =
            bandOf(index, hipOptions.map { it.maxDiff })

        private fun bandOf(index: Int, ends: List<Double>): ClosedFloatingPointRange<Double> {
            if (index !in ends.indices) return 0.0..0.0
            val low = if (index == 0) 0.0 else ends[index - 1]
            return low..ends[index]
        }

        /**
         * 사람이 손으로 고친 값도 도는 형태로 — **거부가 아니라 교정이다**(개발 의도 4번).
         *
         * 엑셀 '설정(JSON)' 칸은 외부에서 편집되므로 무엇이든 들어올 수 있다. 여기서 하는 일:
         * 개수를 기본과 맞추고(모자라면 채우고 넘치면 자른다 — 확정 ㄴ1),
         * 빈 이름을 기본 이름으로 되돌리고, 수가 아닌 값과 범위 밖 값을 눌러 담고,
         * 축의 끝을 **엄격한 오름차순으로** 밀어 올리고,
         * 프리셋이 없는 축을 가리키면 그 프리셋의 기본 축으로 되돌린다.
         */
        fun sanitized(): GenerationPreset = GenerationPreset(
            heightOptions = heightOptions.fit(DEFAULT_HEIGHT_OPTIONS) { v, d ->
                HeightOption(
                    label = v.label.trim().ifEmpty { d.label },
                    center = v.center.finiteOr(d.center).coerceIn(Limits.HEIGHT_CENTER),
                    variance = v.variance.finiteOr(d.variance).coerceIn(Limits.HEIGHT_VARIANCE)
                )
            },
            torsoOptions = ascending(
                torsoOptions.fit(DEFAULT_TORSO_OPTIONS) { v, d ->
                    TorsoOption(
                        label = v.label.trim().ifEmpty { d.label },
                        waistRatio = v.waistRatio.finiteOr(d.waistRatio).coerceIn(Limits.WAIST_RATIO),
                        bmiTarget = v.bmiTarget.finiteOr(d.bmiTarget).coerceIn(Limits.BMI),
                        maxRatio = v.maxRatio.finiteOr(d.maxRatio).coerceIn(Limits.TORSO_END)
                    )
                },
                Limits.TORSO_END_STEP, { it.maxRatio }, { o, m -> o.copy(maxRatio = m) }
            ),
            bustOptions = bustOptions.fit(DEFAULT_BUST_OPTIONS) { v, d ->
                BustOption(
                    label = v.label.trim().ifEmpty { d.label },
                    cupDiff = v.cupDiff.finiteOr(d.cupDiff).coerceIn(Limits.CUP_DIFF)
                )
            },
            hipOptions = ascending(
                hipOptions.fit(DEFAULT_HIP_OPTIONS) { v, d ->
                    HipOption(
                        label = v.label.trim().ifEmpty { d.label },
                        hipBonus = v.hipBonus.finiteOr(d.hipBonus).coerceIn(Limits.HIP_BONUS),
                        maxDiff = v.maxDiff.finiteOr(d.maxDiff).coerceIn(Limits.HIP_END)
                    )
                },
                Limits.HIP_END_STEP, { it.maxDiff }, { o, m -> o.copy(maxDiff = m) }
            ),
            bodyPresets = bodyPresets.fit(DEFAULT_BODY_PRESETS) { v, d ->
                BodyPreset(
                    label = v.label.trim().ifEmpty { d.label },
                    // 없는 축을 가리키면 **그 프리셋의 기본 축**으로 되돌린다 —
                    // 0번으로 접으면 프리셋 넷이 조용히 서로 닮아 버린다.
                    torso = v.torso.orDefaultIndex(d.torso, DEFAULT_TORSO_OPTIONS.size),
                    bust = v.bust.orDefaultIndex(d.bust, DEFAULT_BUST_OPTIONS.size),
                    hip = v.hip.orDefaultIndex(d.hip, DEFAULT_HIP_OPTIONS.size)
                )
            }
        )

        /** 손대지 않은 한 벌인가 — 기본값이면 config에 적지 않는다(파일이 불어나지 않게). */
        val isDefault: Boolean get() = this == DEFAULT_GENERATION

        /**
         * 빈 축이 하나도 없는 한 벌 — 비어 있는 축만 기본값으로 세운다.
         *
         * 파싱은 [sanitized]가 길이를 보장하므로 이 자리가 잡는 것은 **코드가 잘못 세운 한 벌**
         * 이다. 그럼에도 두는 이유는 소비처가 읽기 화면이 캐릭터마다 지나는 경로(목표 비율)라,
         * 빈 목록 하나가 세계관 하나의 화면을 통째로 죽일 수 있기 때문이다 —
         * 축을 사용자에게 연 이 판이 새로 만든 위험이라 이 판이 함께 막는다.
         *
         * 고칠 것이 없으면 **자기 자신을 그대로 돌려준다**(평소 경로에 할당이 붙지 않는다).
         */
        val usable: GenerationPreset
            get() = if (heightOptions.isNotEmpty() && torsoOptions.isNotEmpty() &&
                bustOptions.isNotEmpty() && hipOptions.isNotEmpty() && bodyPresets.isNotEmpty()
            ) this else GenerationPreset(
                heightOptions = heightOptions.ifEmpty { DEFAULT_HEIGHT_OPTIONS },
                torsoOptions = torsoOptions.ifEmpty { DEFAULT_TORSO_OPTIONS },
                bustOptions = bustOptions.ifEmpty { DEFAULT_BUST_OPTIONS },
                hipOptions = hipOptions.ifEmpty { DEFAULT_HIP_OPTIONS },
                bodyPresets = bodyPresets.ifEmpty { DEFAULT_BODY_PRESETS }
            )

        /**
         * 축 값이 설 수 있는 범위와 순서 규칙 — **창의 검증과 파일 교정이 같은 잣대를 쓴다.**
         *
         * 두 벌로 두면 *"창은 거부하는데 엑셀로는 들어오는"* 값이 생긴다 — 그 값은 화면
         * 어디에도 고칠 자리가 없고, 사용자는 왜 이상한지 알 길이 없다. 편집 창이 화면
         * 계층이라 순수 시험이 닿지 않으므로, **잣대만이라도 여기 두어야 잴 수 있다.**
         */
        object Limits {
            val HEIGHT_CENTER = 100.0..250.0
            val HEIGHT_VARIANCE = 0.0..30.0
            val WAIST_RATIO = .2..0.6
            val BMI = 10.0..40.0
            val TORSO_END = .2..1.0
            val CUP_DIFF = 0.0..60.0
            val HIP_BONUS = 0.0..60.0
            val HIP_END = 0.0..99.0

            /**
             * 축의 끝이 같아도 안 되는 이유 — 폭이 0인 구간은 **그 이름이 영영 안 돌아온다.**
             * 셀렉터에 있는데 요약이 절대 답하지 않는 축은 이름표일 뿐 조작이 아니다.
             * 그래서 교정은 겹친 끝을 이만큼 밀어 올린다(창은 같은 자리를 붉은 글씨로 막는다).
             */
            const val TORSO_END_STEP = .001
            const val HIP_END_STEP = .5

            /**
             * 오름차순을 깬 자리 — 창이 그 칸에 붉은 글씨를 단다.
             * [sanitized]는 사람이 없는 자리라 되묻지 못하므로 같은 자리를 밀어 올린다.
             * **둘이 같은 규칙을 쓰는 것이 요점이다** — 갈리면 창이 막는 값이 파일로 들어온다.
             */
            fun ascendingViolations(ends: List<Double>): List<Int> =
                ends.indices.filter { it > 0 && ends[it] <= ends[it - 1] }
        }
    }

    data class CupMappingEntry(val maxDiff: Double, val label: String)

    data class BodyTypeRule(
        val label: String,
        val conditions: Map<String, RangeCondition>,
        val priority: Int
    )

    data class BodyTagRule(
        val label: String,
        val layer: String,    // "build" | "silhouette" | "special"
        val conditions: Map<String, RangeCondition>,
        val priority: Int = 0
    )

    data class RangeCondition(val min: Double? = null, val max: Double? = null)

    fun isInsightEnabled(key: String): Boolean =
        enabledInsights[key] ?: DEFAULT_ENABLED_INSIGHTS[key] ?: true

    companion object {
        private const val KEY = "bodyAnalysis"

        /**
         * [fromConfig]가 실제로 읽는 `bodyAnalysis` 키 전수 — [unusedKeysIn]의 기준이다.
         *
         * **여기 없는 키는 파싱이 통째로 버린다.** 그 사실을 세어 알리는 것이 P-5의 처분이고
         * (B-95 — 없앤 `underbustEstimation`이 담긴 옛 파일이 실재한다), 알리지 않으면
         * 사용자가 적어 둔 설정이 말없이 사라진다(개발 의도 2번).
         *
         * **키를 새로 읽기 시작하면 이 목록에 함께 넣을 것** — 빠뜨리면 멀쩡한 설정이
         * *"더 이상 쓰지 않는 것"*으로 고지된다. 그 누락은 `BodyAnalysisConfigKeysTest`가
         * 잡는다(내보내기가 쓰는 키를 그대로 되읽혀 '모르는 키' 0을 요구한다) —
         * **손으로 대조하지 않아야 낡지 않는다.**
         */
        val READ_KEYS = setOf(
            "cupMapping", "bodyTypeRules", "defaultBodyType", "ribOffset",
            "bodyTagRules", "goldenRatioIdeals", "idealBody", "partSlots", "enabledInsights",
            "generation"
        )

        /**
         * `bodyAnalysis`에 담겼으나 [fromConfig]가 읽지 않는 키 — 이름 그대로 돌려준다.
         *
         * 손상 JSON이나 `bodyAnalysis`가 없는 config는 **빈 목록**이다(잴 것이 없는 것과
         * 버려지는 것은 다른 사실이고, 손상은 이미 가져오기가 따로 경고한다).
         */
        fun unusedKeysIn(configJson: String): List<String> = try {
            val obj = JSONObject(configJson).optJSONObject(KEY)
            if (obj == null) emptyList() else obj.keys().asSequence()
                .filterNot { it in READ_KEYS }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }

        // 인사이트 키 상수
        const val INSIGHT_BODY_TYPE = "bodyType"
        const val INSIGHT_CUP_SIZE = "cupSize"
        const val INSIGHT_BMI = "bmi"
        const val INSIGHT_WHR = "whr"
        const val INSIGHT_BWH_DIFF = "bwhDifferences"
        const val INSIGHT_NORMALIZED_RATIO = "normalizedRatio"
        const val INSIGHT_HEIGHT_RELATIVE = "heightRelative"
        const val INSIGHT_GOLDEN_RATIO = "goldenRatio"
        const val INSIGHT_SILHOUETTE = "silhouette"
        const val INSIGHT_RANKING = "ranking"
        const val INSIGHT_BODY_TAGS = "bodyTags"
        const val INSIGHT_FRAME_SIZE = "frameSize"
        const val INSIGHT_PROPORTION = "proportion"

        /**
         * 흉곽 보정 기본값(cm) — 실측 밑가슴이 없을 때 `밑가슴 = 허리 + 이 값`으로 본다.
         *
         * **이 값 하나가 그림과 글자를 함께 정한다**(B-92 해소, 2026.08.02). 종전에는
         * 기본이 0이라 분석은 `밑가슴 = 허리`로 컵을 재고 실루엣은 전용 상수 6으로 그려,
         * 같은 캐릭터의 컵 글자가 두 계층에서 두 컵 이상 갈렸다. 읽기 카드가 실루엣과
         * 컵 글자를 **한 카드에** 싣게 되면서 그 어긋남이 화면 안으로 들어오므로,
         * 근사 규약을 판정 P4가 확정한 장르 감각(밑가슴 ≈ 허리 + 6)으로 통일했다.
         *
         * 사용자가 이 값을 바꾸면 **그림도 함께 움직인다** — 설정과 화면이 갈리지 않는다.
         */
        const val DEFAULT_RIB_OFFSET = 6.0

        val DEFAULT_CUP_MAPPING = listOf(
            CupMappingEntry(7.5, "AA"),
            CupMappingEntry(10.0, "A"),
            CupMappingEntry(12.5, "B"),
            CupMappingEntry(15.0, "C"),
            CupMappingEntry(17.5, "D"),
            CupMappingEntry(20.0, "E"),
            CupMappingEntry(22.5, "F"),
            CupMappingEntry(25.0, "G"),
            CupMappingEntry(27.5, "H"),
            CupMappingEntry(30.0, "I"),
            CupMappingEntry(999.0, "J+")
        )

        val DEFAULT_BODY_TYPE_RULES = listOf(
            BodyTypeRule(
                "글래머", mapOf(
                    "bustWaistDiff" to RangeCondition(min = 18.0),
                    "whr" to RangeCondition(max = 0.72),
                    "bust" to RangeCondition(min = 88.0)
                ), priority = 1
            ),
            BodyTypeRule(
                "풍만형", mapOf(
                    "bust" to RangeCondition(min = 95.0),
                    "hip" to RangeCondition(min = 98.0)
                ), priority = 2
            ),
            BodyTypeRule(
                "날씬형", mapOf(
                    "bustWaistDiff" to RangeCondition(max = 12.0),
                    "waistHipDiff" to RangeCondition(max = 8.0),
                    "bust" to RangeCondition(max = 82.0)
                ), priority = 3
            ),
            BodyTypeRule(
                "소녀체형", mapOf(
                    "height" to RangeCondition(max = 158.0),
                    "bust" to RangeCondition(max = 80.0),
                    "hip" to RangeCondition(max = 85.0)
                ), priority = 4
            ),
            BodyTypeRule(
                "볼륨형", mapOf(
                    "bustWaistDiff" to RangeCondition(min = 12.0, max = 18.0),
                    "hip" to RangeCondition(min = 92.0),
                    "whr" to RangeCondition(min = 0.72, max = 0.82)
                ), priority = 5
            ),
            BodyTypeRule(
                "탄탄형", mapOf(
                    "whr" to RangeCondition(min = 0.70, max = 0.80),
                    "bustHipRatio" to RangeCondition(min = 0.93, max = 1.07)
                ), priority = 6
            )
        )

        val DEFAULT_BODY_TAG_RULES = listOf(
            // Build layer (BMI 기반)
            BodyTagRule("마른 체형", "build", mapOf("bmi" to RangeCondition(max = 18.5)), 1),
            BodyTagRule("표준 체형", "build", mapOf("bmi" to RangeCondition(min = 18.5, max = 25.0)), 2),
            BodyTagRule("풍만 체형", "build", mapOf("bmi" to RangeCondition(min = 25.0)), 3),
            // Silhouette layer (곡선/비율 기반)
            BodyTagRule("모래시계", "silhouette", mapOf(
                "whr" to RangeCondition(max = 0.72), "bustWaistDiff" to RangeCondition(min = 18.0)
            ), 1),
            BodyTagRule("슬렌더", "silhouette", mapOf(
                "bustWaistDiff" to RangeCondition(max = 13.0), "waistHipDiff" to RangeCondition(max = 13.0)
            ), 2),
            BodyTagRule("탄탄형", "silhouette", mapOf(
                "whr" to RangeCondition(min = 0.70, max = 0.80),
                "bustHipRatio" to RangeCondition(min = 0.93, max = 1.07)
            ), 3),
            BodyTagRule("배형", "silhouette", mapOf("whr" to RangeCondition(min = 0.85)), 4),
            // Special layer (누적)
            BodyTagRule("모델급", "special", mapOf(
                "height" to RangeCondition(min = 170.0), "whr" to RangeCondition(max = 0.73)
            ), 1),
            BodyTagRule("아담한", "special", mapOf(
                "height" to RangeCondition(max = 158.0), "bust" to RangeCondition(max = 82.0)
            ), 2),
            BodyTagRule("볼륨 압도적", "special", mapOf("cupIndex" to RangeCondition(min = 8.0)), 3)
        )

        val DEFAULT_ENABLED_INSIGHTS = mapOf(
            INSIGHT_BODY_TYPE to true,
            INSIGHT_CUP_SIZE to true,
            INSIGHT_BMI to true,
            INSIGHT_WHR to true,
            INSIGHT_BWH_DIFF to true,
            INSIGHT_NORMALIZED_RATIO to true,
            INSIGHT_HEIGHT_RELATIVE to true,
            INSIGHT_GOLDEN_RATIO to true,
            INSIGHT_SILHOUETTE to true,
            INSIGHT_RANKING to true,
            INSIGHT_BODY_TAGS to true,
            INSIGHT_FRAME_SIZE to true,
            INSIGHT_PROPORTION to true
        )

        // ── 🎲 생성 축·프리셋 기본값 (B-93) ────────────────────────────────
        // 어휘는 전부 긍정 프레이밍이다 — 고르는 말은 전부 매력적이어야 한다(P5-③).
        // **[DEFAULT] 선언보다 위에 있어야 한다** — 그쪽이 이 목록들을 읽어 세워진다.

        val DEFAULT_HEIGHT_OPTIONS = listOf(
            HeightOption("아담", 152.0, 5.0),
            HeightOption("보통", 163.0, 4.0),
            HeightOption("장신", 172.0, 4.0),
            HeightOption("초장신", 180.0, 5.0)
        )

        /**
         * 허리/키 목표와 축의 끝. 각 목표값은 자기 구간의 한가운데다.
         *
         * 경계(.362·.402)는 종전 `BodySilhouetteSpec`의 상수 자리였다 — 축이 사용자 것이
         * 되면서 **상수가 아니라 축의 일부**가 됐고, 요약도 이 목록을 읽는다.
         */
        val DEFAULT_TORSO_OPTIONS = listOf(
            TorsoOption("슬림", .350, 18.5, maxRatio = .362),
            TorsoOption("표준", .382, 20.5, maxRatio = .402),
            TorsoOption("소프트", .420, 23.0, maxRatio = 1.0)
        )

        /**
         * 컵차 목표(cm). 흔들림 ±2를 합치면 8~12 / 13~17 / 18~22 / 24~28 — 기본 컵 표로
         * **A~B · C~D · E~F · G~I**다. 가장 작은 축이 컵차 8~12인 것이 P5-② '장르 상향'의
         * 이행이다(종전 최소는 컵차 2 상당).
         */
        val DEFAULT_BUST_OPTIONS = listOf(
            BustOption("아담", 10.0),
            BustOption("내추럴", 15.0),
            BustOption("볼륨", 20.0),
            BustOption("글래머", 26.0)
        )

        /** 엉덩이 − 허리(cm)와 축의 끝. 마지막 축의 끝은 열린 끝이다. */
        val DEFAULT_HIP_OPTIONS = listOf(
            HipOption("힙 슬림", 22.0, maxDiff = 24.0),
            HipOption("힙 표준", 27.0, maxDiff = 31.0),
            HipOption("볼륨힙", 34.0, maxDiff = 99.0)
        )

        /** 세 축 세트. 네 귀퉁이 + 곡선형 하나 — 프리셋만으로도 폭이 나오게 골랐다. */
        val DEFAULT_BODY_PRESETS = listOf(
            BodyPreset("슬렌더", torso = 0, bust = 0, hip = 0),
            BodyPreset("내추럴", torso = 1, bust = 1, hip = 1),
            BodyPreset("아워글래스", torso = 0, bust = 2, hip = 2),
            BodyPreset("글래머", torso = 2, bust = 3, hip = 2)
        )

        /** 손대지 않은 한 벌 — [GenerationPreset.isDefault]의 기준이자 저장 생략의 기준이다. */
        val DEFAULT_GENERATION = GenerationPreset()

        val DEFAULT = BodyAnalysisConfig()

        fun fromConfig(configJson: String): BodyAnalysisConfig {
            return try {
                val root = JSONObject(configJson)
                val obj = root.optJSONObject(KEY) ?: return DEFAULT

                // Cup mapping
                val cupMapping = mutableListOf<CupMappingEntry>()
                val cupArr = obj.optJSONArray("cupMapping")
                if (cupArr != null) {
                    for (i in 0 until cupArr.length()) {
                        val entry = cupArr.getJSONObject(i)
                        cupMapping.add(
                            CupMappingEntry(
                                maxDiff = entry.optDouble("maxDiff", 999.0),
                                label = entry.optString("label", "?")
                            )
                        )
                    }
                }

                // Body type rules
                val bodyTypeRules = mutableListOf<BodyTypeRule>()
                val rulesArr = obj.optJSONArray("bodyTypeRules")
                if (rulesArr != null) {
                    for (i in 0 until rulesArr.length()) {
                        val ruleObj = rulesArr.getJSONObject(i)
                        val conditions = mutableMapOf<String, RangeCondition>()
                        val condObj = ruleObj.optJSONObject("conditions")
                        if (condObj != null) {
                            val keys = condObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val rangeObj = condObj.getJSONObject(k)
                                conditions[k] = RangeCondition(
                                    min = if (rangeObj.has("min")) rangeObj.getDouble("min") else null,
                                    max = if (rangeObj.has("max")) rangeObj.getDouble("max") else null
                                )
                            }
                        }
                        bodyTypeRules.add(
                            BodyTypeRule(
                                label = ruleObj.optString("label", ""),
                                conditions = conditions,
                                priority = ruleObj.optInt("priority", i)
                            )
                        )
                    }
                }

                val defaultBodyType = obj.optString("defaultBodyType", "보통체형")

                // Rib offset — 키가 없으면 기본값. 종전 기본이 0이던 동안 0은 저장된 적이
                // 없으므로(아래 toConfig의 기본값 생략 규칙), 이 갈아타기로 잃는 저장값은 없다.
                val ribOffset = obj.optDouble("ribOffset", DEFAULT_RIB_OFFSET)

                // Body tag rules (multi-tag)
                val bodyTagRules = mutableListOf<BodyTagRule>()
                val tagRulesArr = obj.optJSONArray("bodyTagRules")
                if (tagRulesArr != null) {
                    for (i in 0 until tagRulesArr.length()) {
                        val ruleObj = tagRulesArr.getJSONObject(i)
                        val conditions = mutableMapOf<String, RangeCondition>()
                        val condObj = ruleObj.optJSONObject("conditions")
                        if (condObj != null) {
                            val keys = condObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val rangeObj = condObj.getJSONObject(k)
                                conditions[k] = RangeCondition(
                                    min = if (rangeObj.has("min")) rangeObj.getDouble("min") else null,
                                    max = if (rangeObj.has("max")) rangeObj.getDouble("max") else null
                                )
                            }
                        }
                        bodyTagRules.add(BodyTagRule(
                            label = ruleObj.optString("label", ""),
                            layer = ruleObj.optString("layer", "silhouette"),
                            conditions = conditions,
                            priority = ruleObj.optInt("priority", i)
                        ))
                    }
                }

                // Golden ratio ideals
                val goldenRatioIdeals = mutableMapOf<String, Double>()
                val idealsObj = obj.optJSONObject("goldenRatioIdeals")
                if (idealsObj != null) {
                    val keys = idealsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        goldenRatioIdeals[k] = idealsObj.optDouble(k, 0.0)
                    }
                }

                // 이상 몸 — 있는 키만 읽는다(부분 입력 보존).
                val idealBodyObj = obj.optJSONObject("idealBody")
                fun bodyNum(key: String): Double? =
                    if (idealBodyObj != null && idealBodyObj.has(key))
                        idealBodyObj.optDouble(key).takeUnless { it.isNaN() }
                    else null
                val idealBody = if (idealBodyObj != null) IdealBody(
                    bust = bodyNum("bust"),
                    waist = bodyNum("waist"),
                    hip = bodyNum("hip"),
                    heightCm = bodyNum("height")
                ).takeUnless { it.isEmpty } else null

                // Part → BodySlot 명시 매핑. 모르는 이름은 NONE으로 받아 자리를 보존한다
                // (버리면 뒤 파트의 인덱스가 밀려 매핑 전체가 어긋난다).
                val partSlots = mutableListOf<BodySlot>()
                val slotsArr = obj.optJSONArray("partSlots")
                if (slotsArr != null) {
                    for (i in 0 until slotsArr.length()) {
                        val name = slotsArr.optString(i, "")
                        partSlots.add(
                            runCatching { BodySlot.valueOf(name.trim().uppercase()) }
                                .getOrDefault(BodySlot.NONE)
                        )
                    }
                }

                // Enabled insights
                val enabledInsights = mutableMapOf<String, Boolean>()
                val insightsObj = obj.optJSONObject("enabledInsights")
                if (insightsObj != null) {
                    val keys = insightsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        enabledInsights[k] = insightsObj.optBoolean(k, true)
                    }
                }

                // 🎲 생성 축·프리셋 — 없으면 기본값 한 벌. 있으면 읽은 뒤 반드시 교정한다
                // (손으로 고친 값이 그대로 생성기에 들어가지 않게 — GenerationPreset.sanitized).
                val generation = parseGeneration(obj.optJSONObject("generation"))

                BodyAnalysisConfig(
                    cupMapping = cupMapping.ifEmpty { DEFAULT_CUP_MAPPING },
                    bodyTypeRules = bodyTypeRules.ifEmpty { DEFAULT_BODY_TYPE_RULES },
                    defaultBodyType = defaultBodyType,
                    enabledInsights = if (enabledInsights.isEmpty()) DEFAULT_ENABLED_INSIGHTS else enabledInsights,
                    ribOffset = ribOffset,
                    bodyTagRules = bodyTagRules,
                    // 비어 있으면 빈 채로 둔다 — '장르 기준 자동'이라는 뜻이 있는 값이다.
                    goldenRatioIdeals = goldenRatioIdeals,
                    partSlots = partSlots,
                    idealBody = idealBody,
                    generation = generation
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }

        fun applyToConfig(existingConfig: String, config: BodyAnalysisConfig): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                JSONObject()
            }

            val obj = JSONObject().apply {
                // Cup mapping
                val cupArr = JSONArray()
                for (entry in config.cupMapping) {
                    cupArr.put(JSONObject().apply {
                        put("maxDiff", entry.maxDiff)
                        put("label", entry.label)
                    })
                }
                put("cupMapping", cupArr)

                // Body type rules
                val rulesArr = JSONArray()
                for (rule in config.bodyTypeRules) {
                    rulesArr.put(JSONObject().apply {
                        put("label", rule.label)
                        put("priority", rule.priority)
                        val condObj = JSONObject()
                        for ((k, range) in rule.conditions) {
                            condObj.put(k, JSONObject().apply {
                                range.min?.let { put("min", it) }
                                range.max?.let { put("max", it) }
                            })
                        }
                        put("conditions", condObj)
                    })
                }
                put("bodyTypeRules", rulesArr)

                put("defaultBodyType", config.defaultBodyType)

                // Rib offset — 기본값이면 적지 않는다(기존 config JSON이 불어나지 않게).
                // 기준이 [DEFAULT_RIB_OFFSET]으로 옮겨졌으므로 이제 **0은 명시 저장된다** —
                // 근사를 끄고 싶은 사용자의 선택이 기본값과 구분돼 왕복한다.
                if (config.ribOffset != DEFAULT_RIB_OFFSET) {
                    put("ribOffset", config.ribOffset)
                }

                // Body tag rules
                if (config.bodyTagRules.isNotEmpty()) {
                    val tagRulesArr = JSONArray()
                    for (rule in config.bodyTagRules) {
                        tagRulesArr.put(JSONObject().apply {
                            put("label", rule.label)
                            put("layer", rule.layer)
                            put("priority", rule.priority)
                            val condObj = JSONObject()
                            for ((k, range) in rule.conditions) {
                                condObj.put(k, JSONObject().apply {
                                    range.min?.let { put("min", it) }
                                    range.max?.let { put("max", it) }
                                })
                            }
                            put("conditions", condObj)
                        })
                    }
                    put("bodyTagRules", tagRulesArr)
                }

                // 목표 비율 이상값 — 직접 정한 키가 있을 때만 저장(빈 = 자동).
                if (config.goldenRatioIdeals.isNotEmpty()) {
                    val idealsObj = JSONObject()
                    for ((k, v) in config.goldenRatioIdeals) {
                        idealsObj.put(k, v)
                    }
                    put("goldenRatioIdeals", idealsObj)
                }

                // 이상 몸 — 적힌 값만 싣는다(부분 입력 보존, 빈 몸은 싣지 않는다).
                config.idealBody?.takeUnless { it.isEmpty }?.let { body ->
                    put("idealBody", JSONObject().apply {
                        body.bust?.let { put("bust", it) }
                        body.waist?.let { put("waist", it) }
                        body.hip?.let { put("hip", it) }
                        body.heightCm?.let { put("height", it) }
                    })
                }

                // Part → BodySlot 매핑은 명시했을 때만 싣는다 — 추론과 같은 기본값을 굽지 않아야
                // 기존 필드 정의의 JSON이 불어나지 않는다(ribOffset 선례).
                if (config.partSlots.isNotEmpty()) {
                    val slotsArr = JSONArray()
                    for (slot in config.partSlots) slotsArr.put(slot.name)
                    put("partSlots", slotsArr)
                }

                // Enabled insights
                val insightsObj = JSONObject()
                for ((k, v) in config.enabledInsights) {
                    insightsObj.put(k, v)
                }
                put("enabledInsights", insightsObj)

                // 🎲 생성 축·프리셋 — 손댔을 때만 싣는다(ribOffset·partSlots 선례).
                // 기본값을 구워 두면 기존 필드 정의의 JSON이 통째로 불어나고, 나중에
                // 기본값을 손보면 **옛 값이 굳은 필드만 옛 축으로 남는다**.
                if (!config.generation.isDefault) {
                    put("generation", generationToJson(config.generation))
                }
            }

            root.put(KEY, obj)
            return root.toString()
        }

        /**
         * `generation` 객체를 읽는다 — **읽은 뒤 반드시 [GenerationPreset.sanitized]를 지난다.**
         *
         * 없는 칸은 [JSONObject.optDouble]이 NaN을 돌려주고 그것을 교정이 기본값으로 세우므로,
         * 여기서 칸마다 기본값을 다시 적지 않는다(두 벌로 적으면 갈린다).
         * 프리셋의 축 인덱스만 −1을 표식으로 쓴다 — 0은 실제로 첫 축을 뜻하기 때문이다.
         */
        private fun parseGeneration(obj: JSONObject?): GenerationPreset {
            if (obj == null) return DEFAULT_GENERATION
            fun <T> read(name: String, item: (JSONObject) -> T): List<T> {
                val arr = obj.optJSONArray(name) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(item) }
            }
            return GenerationPreset(
                heightOptions = read("heights") {
                    HeightOption(it.optString("label"), it.optDouble("center"), it.optDouble("variance"))
                },
                torsoOptions = read("torsos") {
                    TorsoOption(
                        it.optString("label"), it.optDouble("waistRatio"),
                        it.optDouble("bmi"), it.optDouble("maxRatio")
                    )
                },
                bustOptions = read("busts") {
                    BustOption(it.optString("label"), it.optDouble("cupDiff"))
                },
                hipOptions = read("hips") {
                    HipOption(it.optString("label"), it.optDouble("hipBonus"), it.optDouble("maxDiff"))
                },
                bodyPresets = read("presets") {
                    BodyPreset(
                        it.optString("label"), it.optInt("torso", -1),
                        it.optInt("bust", -1), it.optInt("hip", -1)
                    )
                }
            ).sanitized()
        }

        /** [parseGeneration]의 짝. 키 이름이 갈리면 왕복이 조용히 기본값으로 돌아간다. */
        private fun generationToJson(gen: GenerationPreset): JSONObject = JSONObject().apply {
            put("heights", JSONArray().apply {
                for (o in gen.heightOptions) put(JSONObject().apply {
                    put("label", o.label); put("center", o.center); put("variance", o.variance)
                })
            })
            put("torsos", JSONArray().apply {
                for (o in gen.torsoOptions) put(JSONObject().apply {
                    put("label", o.label); put("waistRatio", o.waistRatio)
                    put("bmi", o.bmiTarget); put("maxRatio", o.maxRatio)
                })
            })
            put("busts", JSONArray().apply {
                for (o in gen.bustOptions) put(JSONObject().apply {
                    put("label", o.label); put("cupDiff", o.cupDiff)
                })
            })
            put("hips", JSONArray().apply {
                for (o in gen.hipOptions) put(JSONObject().apply {
                    put("label", o.label); put("hipBonus", o.hipBonus); put("maxDiff", o.maxDiff)
                })
            })
            put("presets", JSONArray().apply {
                for (p in gen.bodyPresets) put(JSONObject().apply {
                    put("label", p.label); put("torso", p.torso); put("bust", p.bust); put("hip", p.hip)
                })
            })
        }

        fun removeFromConfig(existingConfig: String): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                return existingConfig
            }
            root.remove(KEY)
            return root.toString()
        }
    }
}


// ══════════════════════════════════════════════════════════════════════════
// 생성 축 교정 도우미 — [BodyAnalysisConfig.GenerationPreset.sanitized]만 쓴다.
// ══════════════════════════════════════════════════════════════════════════

/**
 * 목록의 길이를 [defaults]에 맞춘다 — **개수를 열지 않기 때문에**(확정 ㄴ1) 파싱 결과는
 * 언제나 기본과 같은 길이다. 남는 것은 자르고, 모자란 자리는 기본값이 그대로 선다.
 */
private fun <T> List<T>.fit(defaults: List<T>, merge: (T, T) -> T): List<T> =
    defaults.mapIndexed { i, d -> getOrNull(i)?.let { merge(it, d) } ?: d }

/** 수가 아닌 값(NaN·무한대)은 기본값으로 — 한 칸이 망가져도 나머지 축은 그대로 돈다. */
private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback

/** 목록 밖을 가리키는 축 인덱스는 기본값으로. 파싱이 못 읽은 자리도 여기로 온다(−1). */
private fun Int.orDefaultIndex(fallback: Int, size: Int): Int = if (this in 0 until size) this else fallback

/**
 * 축의 끝을 **엄격한 오름차순**으로 밀어 올린다.
 *
 * 뒤집힌 채로 두면 구간이 음의 폭이 되어 생성기가 겨눌 자리가 사라지고, 같은 값이면 폭이
 * 0이라 그 축의 이름이 요약에서 영영 안 돌아온다(셀렉터가 이름표로 격하된다).
 * 값을 버리지 않고 앞 값 바로 위로 올리는 것은, 교정이 곧 사용자의 의도(*"여기서 나눈다"*)를
 * 최대한 남기는 길이기 때문이다. 판정 기준은 `Limits.ascendingViolations`와 한 벌이다.
 */
private fun <T> ascending(
    items: List<T>, step: Double, end: (T) -> Double, withEnd: (T, Double) -> T
): List<T> {
    var floor: Double? = null
    return items.map { item ->
        val raw = end(item)
        val previous = floor
        val e = if (previous == null || raw > previous) raw else previous + step
        floor = e
        if (e == raw) item else withEnd(item, e)
    }
}
