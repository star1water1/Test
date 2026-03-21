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

    // 체형 분류
    val bodyType: String? = null,

    // 컵 사이즈
    val cupSize: String? = null,
    val bustDiff: Double? = null,

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

        // 1. 컵 사이즈
        val underbust = waist  // config.underbustEstimation에 따라 확장 가능
        val diff = bust - underbust
        val cupSize = config.cupMapping
            .sortedBy { it.maxDiff }
            .firstOrNull { diff <= it.maxDiff }?.label ?: "?"

        // 2. 체형 분류 (config 기반 rule 매칭)
        val bmi = if (heightCm != null && weightKg != null && heightCm > 0 && weightKg > 0) {
            weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
        } else null

        val computedValues = mutableMapOf(
            "bust" to bust,
            "waist" to waist,
            "hip" to hip,
            "bustWaistDiff" to bustWaistDiff,
            "waistHipDiff" to waistHipDiff,
            "bustHipDiff" to bustHipDiff,
            "whr" to whr,
            "bustHipRatio" to bustHipRatio
        )
        heightCm?.let { computedValues["height"] = it }
        weightKg?.let { computedValues["weight"] = it }
        bmi?.let { computedValues["bmi"] = it }

        val bodyType = config.bodyTypeRules
            .sortedBy { it.priority }
            .firstOrNull { rule ->
                rule.conditions.all { (key, range) ->
                    val v = computedValues[key] ?: return@all false
                    (range.min == null || v >= range.min) && (range.max == null || v <= range.max)
                }
            }?.label ?: config.defaultBodyType

        // 3. BMI 카테고리 (소설 캐릭터 맥락 용어)
        val bmiCategory = bmi?.let {
            when {
                it < 18.5 -> "마른 편"
                it < 25.0 -> "보통"
                it < 30.0 -> "통통한 편"
                else -> "풍만한 편"
            }
        }

        // 4. 정규화 비율 (bust=1 기준)
        val bwhRatioDisplay = "${bust.roundToInt()} : ${waist.roundToInt()} : ${hip.roundToInt()}"
        val normalizedRatio = if (bust > 0) {
            "%.2f : %.2f : %.2f".format(1.0, waist / bust, hip / bust)
        } else bwhRatioDisplay

        // 5. 키 대비 비율 (heightCm이 0이면 null 처리)
        val safeHeight = heightCm?.takeIf { it > 0 }
        val bustHeightRatio = safeHeight?.let { bust / it }
        val waistHeightRatio = safeHeight?.let { waist / it }
        val hipHeightRatio = safeHeight?.let { hip / it }

        // 6. 골든 비율 점수 (유효한 키가 있을 때만)
        val goldenRatioDetails = if (safeHeight != null) {
            val items = mutableListOf<GoldenRatioItem>()

            // W/H 이상: 0.70
            items.add(goldenRatioItem("허리/엉덩이", whr, 0.70))

            // B/H 이상: 1.00
            items.add(goldenRatioItem("가슴/엉덩이", bustHipRatio, 1.00))

            // waist/height 이상: 0.40
            val whrHeight = waist / safeHeight
            items.add(goldenRatioItem("허리/키", whrHeight, 0.40))

            // bust/height 이상: 0.52
            items.add(goldenRatioItem("가슴/키", bust / safeHeight, 0.52))

            items
        } else null

        val goldenRatioScore = goldenRatioDetails?.let { details ->
            // 각 항목 편차의 평균을 100에서 감산 (편차 0% → 100점)
            val avgDeviation = details.map { abs(it.deviationPercent) }.average()
            (100.0 - avgDeviation * 5).coerceIn(0.0, 100.0)
        }

        // 7. 실루엣 설명 생성
        val silhouetteDescription = buildSilhouetteDescription(
            bodyType, bustWaistDiff, waistHipDiff, heightCm, cupSize
        )

        return BodyAnalysisResult(
            bust = bust,
            waist = waist,
            hip = hip,
            height = heightCm,
            weight = weightKg,
            bodyType = bodyType,
            cupSize = cupSize,
            bustDiff = diff,
            bmi = bmi,
            bmiCategory = bmiCategory,
            whr = whr,
            bustWaistDiff = bustWaistDiff,
            waistHipDiff = waistHipDiff,
            bustHipDiff = bustHipDiff,
            bustHipRatio = bustHipRatio,
            normalizedRatio = normalizedRatio,
            bwhRatioDisplay = bwhRatioDisplay,
            bustHeightRatio = bustHeightRatio,
            waistHeightRatio = waistHeightRatio,
            hipHeightRatio = hipHeightRatio,
            goldenRatioScore = goldenRatioScore,
            goldenRatioDetails = goldenRatioDetails,
            silhouetteDescription = silhouetteDescription
        )
    }

    private fun goldenRatioItem(label: String, actual: Double, ideal: Double): GoldenRatioItem {
        val deviation = if (ideal != 0.0) ((actual - ideal) / ideal) * 100.0 else 0.0
        return GoldenRatioItem(label, actual, ideal, deviation)
    }

    private fun buildSilhouetteDescription(
        bodyType: String,
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

        parts.add("$bodyType")

        return parts.joinToString(" ")
    }

    companion object {
        fun parseNumericFromText(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val cleaned = text.trim().replace(Regex("[^0-9.]"), "")
            return cleaned.toDoubleOrNull()
        }

        /**
         * 여러 캐릭터의 BWH 데이터로부터 순위를 계산한다.
         * @param currentBust 현재 캐릭터의 가슴 측정값
         * @param allBustValues 모든 캐릭터의 가슴 측정값 리스트
         * @return 순위 (1 = 최대)
         */
        fun computeRank(currentValue: Double, allValues: List<Double>): Int {
            if (allValues.isEmpty()) return 0
            // 자기보다 큰 값의 수 + 1 = 순위 (동점은 같은 순위)
            val higherCount = allValues.count { it > currentValue }
            return higherCount + 1
        }
    }
}
