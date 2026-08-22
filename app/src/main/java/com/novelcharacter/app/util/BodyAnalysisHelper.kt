package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import kotlin.math.abs
import kotlin.math.roundToInt

data class BodyAnalysisResult(
    // 기본 측정값
    val bust: Double,
    val waist: Double,
    val hip: Double,
    val height: Double? = null,
    val weight: Double? = null,

    // 체형 분류 (기존 호환)
    val bodyType: String? = null,

    // 다층 태그 (V2)
    val bodyTags: List<String> = emptyList(),

    // 컵 사이즈
    val cupSize: String? = null,
    val bustDiff: Double? = null,
    val adjustedUnderbust: Double? = null,

    // BMI
    val bmi: Double? = null,
    val bmiCategory: String? = null,

    // WHR
    val whr: Double? = null,

    // 차이 분석
    val bustWaistDiff: Double = 0.0,
    val waistHipDiff: Double = 0.0,
    val bustHipDiff: Double = 0.0,

    // 비율 분석
    val bustHipRatio: Double = 1.0,
    val normalizedRatio: String = "",
    val bwhRatioDisplay: String = "",

    // 키 대비 비율
    val bustHeightRatio: Double? = null,
    val waistHeightRatio: Double? = null,
    val hipHeightRatio: Double? = null,

    // 프레임/프로포션 (V2)
    val frameSize: String? = null,
    val volumeIndex: Double? = null,
    val curvesIndex: Double? = null,

    // 골든 비율
    val goldenRatioScore: Double? = null,
    val goldenRatioDetails: List<GoldenRatioItem>? = null,

    /**
     * 그 점수가 **무엇과의 거리인가** (B-94). 카드가 기준을 말할 때 이것을 읽는다 —
     * 표시 계층이 설정을 다시 해석하면 *"쟀다고 말하는 것"*과 *"실제로 잰 것"*이 갈린다.
     */
    val targetRatioBasis: BodyTargetRatio.Basis? = null,

    // 실루엣 설명
    val silhouetteDescription: String? = null,

    // 작품 내 순위 (외부에서 주입)
    val rankingInNovel: RankingInfo? = null
)

data class GoldenRatioItem(
    val label: String,
    val actual: Double,
    val ideal: Double,
    val deviationPercent: Double
)

data class RankingInfo(
    val bustRank: Int? = null,
    val waistRank: Int? = null,
    val hipRank: Int? = null,
    val heightRank: Int? = null,
    val weightRank: Int? = null,
    val totalCharacters: Int = 0
)

class BodyAnalysisHelper {

    /**
     * @param peerAverage 같은 작품 캐릭터의 평균 몸 — 목표 비율 '작품 평균' 기준의 재료다
     *   (B-94). 호스트가 주입하지 않으면 그 기준은 성립하지 않고 카드가 그 사실을 말한다.
     * @param targetRatioOverride 카드에서 즉석 전환한 기준(확정 8번 ㄱ2). `null`이면
     *   필드 설정의 기본 기준이다.
     * @param measuredUnderbust **실측 밑가슴**(cm). 부위 칸을 밑가슴에 연결했으면 그 값이고,
     *   `null`이면 근사(허리 + `ribOffset`)로 떨어진다.
     *
     *   이 인자가 없어서 **한 카드가 컵을 두 개 말했다.** 그림 계층
     *   ([BodySilhouetteSpec.figureUnderbust])은 실측을 이미 쓰고 있었는데 분석 계층은 받을
     *   통로가 없어 무조건 근사를 썼고, 두 값이 같은 카드 같은 열에 서른 줄 간격으로 섰다
     *   (가슴 88 · 허리 60 · 실측 밑가슴 74면 그림은 `C`, 분석은 `F` — 세 칸 차이다).
     *   컵 줄 바로 아래 문구가 *"밑가슴을 재는 파트를 연결하면 실측으로 바뀝니다"*라고
     *   **글로 약속하고 있었다.**
     *
     *   맨 뒤에 두는 이유는 위치 인자로 부르는 자리가 여럿이기 때문이다.
     */
    fun analyze(
        bust: Double, waist: Double, hip: Double,
        heightCm: Double?, weightKg: Double?,
        config: BodyAnalysisConfig = BodyAnalysisConfig.DEFAULT,
        peerAverage: BodyAnalysisConfig.IdealBody? = null,
        targetRatioOverride: BodyAnalysisConfig.TargetRatioSource? = null,
        measuredUnderbust: Double? = null
    ): BodyAnalysisResult {
        // 기본 차이/비율 계산
        val bustWaistDiff = bust - waist
        val waistHipDiff = hip - waist
        val bustHipDiff = bust - hip
        val whr = if (hip > 0) waist / hip else 0.0
        val bustHipRatio = if (hip > 0) bust / hip else 0.0

        // 1. 컵 사이즈 — 실측 밑가슴이 있으면 그것이 이긴다(없으면 흉곽 보정 근사 · V2).
        // 그림 계층([BodySilhouetteSpec.figureUnderbust])이 쓰는 규칙과 **같은 규칙**이다 —
        // 갈리면 같은 카드가 컵을 두 개 말한다.
        val underbust = measuredUnderbust ?: (waist + config.ribOffset)
        val diff = bust - underbust
        val cupSize = if (diff > 0) {
            config.cupMapping
                .sortedBy { it.maxDiff }
                .firstOrNull { diff <= it.maxDiff }?.label ?: "?"
        } else "—"
        val cupIndex = if (diff > 0) {
            config.cupMapping
                .sortedBy { it.maxDiff }
                .indexOfFirst { diff <= it.maxDiff }
        } else -1

        // 2. BMI
        val bmi = if (heightCm != null && weightKg != null && heightCm > 0 && weightKg > 0) {
            weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
        } else null

        // 3. computedValues (조건 평가용)
        val computedValues = mutableMapOf(
            "bust" to bust,
            "waist" to waist,
            "hip" to hip,
            "bustWaistDiff" to bustWaistDiff,
            "waistHipDiff" to waistHipDiff,
            "bustHipDiff" to bustHipDiff,
            "whr" to whr,
            "bustHipRatio" to bustHipRatio,
            "cupIndex" to cupIndex.toDouble()
        )
        heightCm?.let { computedValues["height"] = it }
        weightKg?.let { computedValues["weight"] = it }
        bmi?.let { computedValues["bmi"] = it }

        // 4. 기존 체형 분류 (하위호환)
        val bodyType = config.bodyTypeRules
            .sortedBy { it.priority }
            .firstOrNull { matchesRule(it.conditions, computedValues) }
            ?.label ?: config.defaultBodyType

        // 5. 다층 태그 분류 (V2)
        val effectiveTagRules = if (config.bodyTagRules.isNotEmpty()) {
            config.bodyTagRules
        } else if (config.bodyTypeRules == BodyAnalysisConfig.DEFAULT_BODY_TYPE_RULES) {
            // 기본 규칙 → DEFAULT_BODY_TAG_RULES 사용 (build/silhouette/special 전체)
            BodyAnalysisConfig.DEFAULT_BODY_TAG_RULES
        } else {
            // 사용자 커스텀 bodyTypeRules → silhouette 레이어로 변환
            config.bodyTypeRules.map {
                BodyAnalysisConfig.BodyTagRule(it.label, "silhouette", it.conditions, it.priority)
            }
        }
        val bodyTags = mutableListOf<String>()
        for (layer in listOf("build", "silhouette", "special")) {
            val layerRules = effectiveTagRules.filter { it.layer == layer }.sortedBy { it.priority }
            if (layer == "special") {
                // special: 조건 만족하는 모두 (누적)
                bodyTags.addAll(layerRules.filter { matchesRule(it.conditions, computedValues) }.map { it.label })
            } else {
                // build/silhouette: 첫 매칭만 (배타적)
                layerRules.firstOrNull { matchesRule(it.conditions, computedValues) }?.let { bodyTags.add(it.label) }
            }
        }

        // 6. BMI 카테고리
        val bmiCategory = bmi?.let {
            when {
                it < 18.5 -> "마른 편"
                it < 25.0 -> "보통"
                it < 30.0 -> "통통한 편"
                else -> "풍만한 편"
            }
        }

        // 7. 정규화 비율
        val bwhRatioDisplay = "${bust.roundToInt()} : ${waist.roundToInt()} : ${hip.roundToInt()}"
        val normalizedRatio = if (bust > 0) {
            // 화면에 보이기만 하는 비율이다 — `DynamicFieldRenderer`의 addRow가 유일한 소비처이고
            // 저장·엑셀·PDF 어디에도 안 나간다. R-22가 *"사람에게 보이기만 하는 문구는 그대로
            // 기본 로케일을 쓴다"*고 가른 쪽이라, 독일어 기기에서 `0,85`로 보이는 것이 옳다.
            // platform-parity-ok: 표시 전용 — 저장·되파싱·키 어디에도 쓰이지 않는다
            "%.2f : %.2f : %.2f".format(1.0, waist / bust, hip / bust)
        } else bwhRatioDisplay

        // 8. 키 대비 비율
        val safeHeight = heightCm?.takeIf { it > 0 }
        val bustHeightRatio = safeHeight?.let { bust / it }
        val waistHeightRatio = safeHeight?.let { waist / it }
        val hipHeightRatio = safeHeight?.let { hip / it }

        // 9. 프레임 사이즈 (V2 — 키 기반)
        val frameSize = safeHeight?.let {
            when {
                it < 158 -> "소형"
                it < 168 -> "중형"
                it < 175 -> "준대형"
                else -> "대형"
            }
        }

        // 10. 키 대비 볼륨/곡선 지수 (V2)
        val volumeIndex = safeHeight?.let { (bust + waist + hip) / (3.0 * it) }
        val curvesIndex = safeHeight?.let { (bustWaistDiff + waistHipDiff) / it }

        // 11. 목표 비율 점수 (P8 재의미화 — 종전 '골든 비율') — 공식은 무변경이고 이상값의
        //     근거만 바뀐다. **기준을 정하고 이상값을 내는 일은 [BodyTargetRatio] 하나가 한다**
        //     (B-94): 카드의 즉석 전환이 같은 함수를 부르므로 두 자리가 갈릴 수 없다.
        val targetRatioBasis = safeHeight?.let {
            BodyTargetRatio.basis(config, it, peerAverage, targetRatioOverride)
        }
        // `!!`를 쓰지 않는다 — 여기서 그것은 *`targetRatioBasis`가 있으면 키도 있다*는 추론이고,
        // 둘 중 하나만 조건이 바뀌는 날 조용히 죽는다. 두 값을 함께 검사하면 그 추론이 필요 없다.
        val goldenRatioDetails = if (safeHeight != null && targetRatioBasis != null) {
            BodyTargetRatio.items(bust, waist, hip, safeHeight, targetRatioBasis)
                .takeIf { it.isNotEmpty() }
        } else null

        val goldenRatioScore = goldenRatioDetails?.let { BodyTargetRatio.score(it) }

        // 12. 실루엣 설명 — 다층 태그 통합 (V2)
        val silhouetteDescription = buildSilhouetteDescription(
            bodyTags.ifEmpty { listOf(bodyType) },
            bustWaistDiff, waistHipDiff, heightCm
        )

        return BodyAnalysisResult(
            bust = bust, waist = waist, hip = hip,
            height = heightCm, weight = weightKg,
            bodyType = bodyType,
            bodyTags = bodyTags,
            cupSize = cupSize, bustDiff = diff, adjustedUnderbust = underbust,
            bmi = bmi, bmiCategory = bmiCategory,
            whr = whr,
            bustWaistDiff = bustWaistDiff, waistHipDiff = waistHipDiff, bustHipDiff = bustHipDiff,
            bustHipRatio = bustHipRatio, normalizedRatio = normalizedRatio, bwhRatioDisplay = bwhRatioDisplay,
            bustHeightRatio = bustHeightRatio, waistHeightRatio = waistHeightRatio, hipHeightRatio = hipHeightRatio,
            frameSize = frameSize, volumeIndex = volumeIndex, curvesIndex = curvesIndex,
            goldenRatioScore = goldenRatioScore, goldenRatioDetails = goldenRatioDetails,
            targetRatioBasis = targetRatioBasis,
            silhouetteDescription = silhouetteDescription
        )
    }

    private fun matchesRule(conditions: Map<String, BodyAnalysisConfig.RangeCondition>, values: Map<String, Double>): Boolean {
        return conditions.all { (key, range) ->
            val v = values[key] ?: return@all false
            (range.min == null || v >= range.min) && (range.max == null || v <= range.max)
        }
    }

    private fun buildSilhouetteDescription(
        tags: List<String>,
        bustWaistDiff: Double,
        waistHipDiff: Double,
        heightCm: Double?
    ): String {
        val parts = mutableListOf<String>()

        // 키 기반 수식어
        if (heightCm != null) {
            when {
                heightCm < 155 -> parts.add("작은 키에")
                heightCm < 160 -> parts.add("아담한 키에")
                heightCm > 175 -> parts.add("큰 키에")
                heightCm > 170 -> parts.add("늘씬한 키에")
            }
        }

        // 허리 기반
        when {
            bustWaistDiff >= 20 && waistHipDiff >= 20 -> parts.add("허리가 매우 잘록하고")
            bustWaistDiff >= 15 && waistHipDiff >= 15 -> parts.add("허리가 잘록하고")
            bustWaistDiff < 8 && waistHipDiff < 8 -> parts.add("전체적으로 일자 라인의")
        }

        // 가슴/엉덩이 균형
        val bhDiff = abs(bustWaistDiff - waistHipDiff)
        when {
            bhDiff < 3 -> parts.add("가슴과 엉덩이가 균형잡힌")
            bustWaistDiff > waistHipDiff + 5 -> parts.add("가슴이 강조된")
            waistHipDiff > bustWaistDiff + 5 -> parts.add("엉덩이가 강조된")
        }

        // 다층 태그 조합
        parts.add(tags.joinToString(" · "))

        return parts.joinToString(" ")
    }

    companion object {
        /** 수치 해석은 [BodyMeasurements]가 단일 소스다 — 여기서 규칙을 복제하지 않는다. */
        fun parseNumericFromText(text: String?): Double? = BodyMeasurements.parseNumber(text)

        fun computeRank(currentValue: Double, allValues: List<Double>): Int {
            if (allValues.isEmpty()) return 0
            val higherCount = allValues.count { it > currentValue }
            return higherCount + 1
        }

        /** 볼륨 지수 해석 라벨 */
        fun volumeLabel(index: Double): String = when {
            index < 0.45 -> "마른"
            index < 0.50 -> "보통"
            index < 0.55 -> "볼륨감"
            else -> "매우 볼륨감"
        }

        /** 곡선 지수 해석 라벨 */
        fun curvesLabel(index: Double): String = when {
            index < 0.10 -> "일자형"
            index < 0.20 -> "보통"
            index < 0.30 -> "곡선적"
            else -> "매우 곡선적"
        }

        /**
         * BMI를 장르어로 (판정 P8 — 전문 지표는 장르어로 읽히고 수치는 부제로 내린다).
         *
         * **어휘는 3축 요약의 몸통 축과 같은 말을 쓴다**(`BodySilhouetteSpec.axisSummary` —
         * 슬림·표준·소프트). 같은 몸을 두 자리가 다른 낱말로 부르면 위계가 아니라 소음이다.
         * 엔진의 [BodyAnalysisResult.bmiCategory]는 그대로 둔다(계약 무변경) — 여기는
         * 표시 계층이며, 자세히 영역은 종전 낱말을 계속 보인다.
         */
        fun bmiToneLabel(bmi: Double): String = when {
            bmi < 18.5 -> "슬림"
            bmi < 25.0 -> "표준"
            bmi < 30.0 -> "소프트"
            else -> "글래머"
        }

        /**
         * WHR을 장르어로 (판정 P8 — "잘록함"). 낮을수록 잘록하다.
         *
         * 경계는 골든 비율 이상값(WHR .70)을 가장 잘록한 칸의 문턱으로 두고 위로 벌린 것이다.
         */
        fun waistlineLabel(whr: Double): String = when {
            whr <= 0.70 -> "깊음"
            whr <= 0.78 -> "뚜렷함"
            whr <= 0.85 -> "완만함"
            else -> "일자"
        }
    }
}
