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

    fun analyze(
        bust: Double, waist: Double, hip: Double,
        heightCm: Double?, weightKg: Double?,
        config: BodyAnalysisConfig = BodyAnalysisConfig.DEFAULT
    ): BodyAnalysisResult {
        // 기본 차이/비율 계산
        val bustWaistDiff = bust - waist
        val waistHipDiff = hip - waist
        val bustHipDiff = bust - hip
        val whr = if (hip > 0) waist / hip else 0.0
        val bustHipRatio = if (hip > 0) bust / hip else 0.0

        // 1. 컵 사이즈 — 흉곽 보정 (V2)
        val underbust = waist + config.ribOffset
        val diff = bust - underbust
        val cupSize = config.cupMapping
            .sortedBy { it.maxDiff }
            .firstOrNull { diff <= it.maxDiff }?.label ?: "?"
        val cupIndex = config.cupMapping
            .sortedBy { it.maxDiff }
            .indexOfFirst { diff <= it.maxDiff }

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
            .firstOrNull { rule ->
                rule.conditions.all { (key, range) ->
                    val v = computedValues[key] ?: return@all false
                    (range.min == null || v >= range.min) && (range.max == null || v <= range.max)
                }
            }?.label ?: config.defaultBodyType

        // 5. 다층 태그 분류 (V2)
        val effectiveTagRules = config.bodyTagRules.ifEmpty {
            // bodyTagRules 미설정 → 기존 bodyTypeRules를 silhouette 레이어로 변환
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

        // 11. 골든 비율 점수 — 사용자 정의 이상값 (V2)
        val ideals = config.goldenRatioIdeals
        val goldenRatioDetails = if (safeHeight != null) {
            listOf(
                goldenRatioItem("허리/엉덩이", whr, ideals["whr"] ?: 0.70),
                goldenRatioItem("가슴/엉덩이", bustHipRatio, ideals["bustHipRatio"] ?: 1.00),
                goldenRatioItem("허리/키", waist / safeHeight, ideals["waistHeight"] ?: 0.40),
                goldenRatioItem("가슴/키", bust / safeHeight, ideals["bustHeight"] ?: 0.52)
            )
        } else null

        val goldenRatioScore = goldenRatioDetails?.let { details ->
            val avgDeviation = details.map { abs(it.deviationPercent) }.average()
            (100.0 - avgDeviation * 5).coerceIn(0.0, 100.0)
        }

        // 12. 실루엣 설명 — 다층 태그 통합 (V2)
        val silhouetteDescription = buildSilhouetteDescription(
            bodyTags.ifEmpty { listOf(bodyType) },
            bustWaistDiff, waistHipDiff, heightCm, cupSize
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
            silhouetteDescription = silhouetteDescription
        )
    }

    private fun matchesRule(conditions: Map<String, BodyAnalysisConfig.RangeCondition>, values: Map<String, Double>): Boolean {
        return conditions.all { (key, range) ->
            val v = values[key] ?: return@all false
            (range.min == null || v >= range.min) && (range.max == null || v <= range.max)
        }
    }

    private fun goldenRatioItem(label: String, actual: Double, ideal: Double): GoldenRatioItem {
        val deviation = if (ideal != 0.0) ((actual - ideal) / ideal) * 100.0 else 0.0
        return GoldenRatioItem(label, actual, ideal, deviation)
    }

    private fun buildSilhouetteDescription(
        tags: List<String>,
        bustWaistDiff: Double,
        waistHipDiff: Double,
        heightCm: Double?,
        cupSize: String
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
        fun parseNumericFromText(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val cleaned = text.trim().replace(Regex("[^0-9.]"), "")
            return cleaned.toDoubleOrNull()
        }

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
    }
}
