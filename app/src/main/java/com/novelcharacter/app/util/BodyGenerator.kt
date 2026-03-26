package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import kotlin.random.Random

/**
 * 신체 수치 자동 생성기.
 * 키/체형 옵션 선택 → 랜덤 BWH/키/몸무게 생성 → BodyAnalysisHelper로 즉시 분석.
 * 기준값은 사용자 정의 가능 (FieldDefinition.config "bodyAnalysis"."generation" 서브키).
 */
object BodyGenerator {

    data class HeightOption(val label: String, val center: Double, val variance: Double)
    data class BodyTypeOption(
        val label: String,
        val waistBase: Double,      // 키 160cm 기준 허리
        val bustBonus: Double,      // 허리 대비 가슴 증가량
        val hipBonus: Double,       // 허리 대비 엉덩이 증가량
        val bmiTarget: Double       // 목표 BMI (몸무게 역산용)
    )

    /** 가슴/엉덩이 독립 조절용 오버라이드 */
    data class SizeOverride(val label: String, val bonus: Double)

    data class GenerationPreset(
        val heightOptions: List<HeightOption> = DEFAULT_HEIGHT_OPTIONS,
        val bodyTypeOptions: List<BodyTypeOption> = DEFAULT_BODY_TYPE_OPTIONS,
        val bustOverrides: List<SizeOverride> = DEFAULT_BUST_OVERRIDES,
        val hipOverrides: List<SizeOverride> = DEFAULT_HIP_OVERRIDES
    )

    data class GeneratedBody(
        val height: Double,
        val weight: Double,
        val bust: Double,
        val waist: Double,
        val hip: Double
    ) {
        val bwhString: String get() = "${bust.toInt()}-${waist.toInt()}-${hip.toInt()}"
    }

    // ── 기본 프리셋 ──

    val DEFAULT_HEIGHT_OPTIONS = listOf(
        HeightOption("작음", 152.0, 5.0),
        HeightOption("보통", 163.0, 4.0),
        HeightOption("큼", 172.0, 4.0),
        HeightOption("매우 큼", 180.0, 5.0)
    )

    val DEFAULT_BODY_TYPE_OPTIONS = listOf(
        BodyTypeOption("마름", 57.0, 8.0, 10.0, 17.5),
        BodyTypeOption("보통-마름", 61.0, 13.0, 14.0, 19.5),
        BodyTypeOption("보통", 65.0, 18.0, 18.0, 21.0),
        BodyTypeOption("보통-풍만", 69.0, 25.0, 22.0, 22.5),
        BodyTypeOption("풍만", 73.0, 32.0, 26.0, 24.0)
    )

    val DEFAULT_BUST_OVERRIDES = listOf(
        SizeOverride("작음", 8.0),
        SizeOverride("보통-작음", 14.0),
        SizeOverride("보통", 20.0),
        SizeOverride("보통-큼", 26.0),
        SizeOverride("큼", 33.0)
    )

    val DEFAULT_HIP_OVERRIDES = listOf(
        SizeOverride("작음", 10.0),
        SizeOverride("보통-작음", 14.0),
        SizeOverride("보통", 18.0),
        SizeOverride("보통-큼", 22.0),
        SizeOverride("큼", 27.0)
    )

    // ── 생성 알고리즘 ──

    /**
     * 신체 수치 생성.
     * @param bustOverride null이면 체형의 bustBonus 사용, 비null이면 독립 오버라이드
     * @param hipOverride null이면 체형의 hipBonus 사용, 비null이면 독립 오버라이드
     */
    fun generate(
        heightOption: HeightOption,
        bodyTypeOption: BodyTypeOption,
        bustOverride: SizeOverride? = null,
        hipOverride: SizeOverride? = null,
        random: Random = Random.Default
    ): GeneratedBody {
        val height = (heightOption.center + random.nextDouble(-heightOption.variance, heightOption.variance))
            .coerceIn(140.0, 200.0)

        val waist = (bodyTypeOption.waistBase + (height - 160.0) * 0.15 + random.nextDouble(-2.0, 2.0))
            .coerceIn(45.0, 110.0)

        val effectiveBustBonus = bustOverride?.bonus ?: bodyTypeOption.bustBonus
        val effectiveHipBonus = hipOverride?.bonus ?: bodyTypeOption.hipBonus

        val bust = (waist + effectiveBustBonus + random.nextDouble(-3.0, 3.0))
            .coerceIn(60.0, 150.0)

        val hip = (waist + effectiveHipBonus + random.nextDouble(-3.0, 3.0))
            .coerceIn(60.0, 150.0)

        // 체중 보정: 가슴/엉덩이 오버라이드로 인한 체적 변화 반영
        val bustDelta = effectiveBustBonus - bodyTypeOption.bustBonus
        val hipDelta = effectiveHipBonus - bodyTypeOption.hipBonus
        val weightAdj = bustDelta * 0.04 + hipDelta * 0.03
        val weight = (bodyTypeOption.bmiTarget * (height / 100.0) * (height / 100.0) + weightAdj + random.nextDouble(-2.0, 2.0))
            .coerceIn(30.0, 150.0)

        return GeneratedBody(
            height = Math.round(height * 10.0) / 10.0,
            weight = Math.round(weight * 10.0) / 10.0,
            bust = Math.round(bust).toDouble(),
            waist = Math.round(waist).toDouble(),
            hip = Math.round(hip).toDouble()
        )
    }

    /**
     * 상대 생성: 기준 캐릭터 대비 비율 조정.
     * @param multiplier 예: 1.05 = 5% 크게, 0.95 = 5% 작게
     */
    fun generateRelative(
        baseHeight: Double, baseWaist: Double, baseBust: Double, baseHip: Double, baseWeight: Double,
        heightMultiplier: Double = 1.0,
        volumeMultiplier: Double = 1.0,
        random: Random = Random.Default
    ): GeneratedBody {
        val height = (baseHeight * heightMultiplier + random.nextDouble(-1.5, 1.5))
            .coerceIn(140.0, 200.0)

        val waist = (baseWaist * volumeMultiplier + random.nextDouble(-2.0, 2.0))
            .coerceIn(45.0, 110.0)
        val bustDiff = baseBust - baseWaist
        val hipDiff = baseHip - baseWaist
        val bust = (waist + bustDiff * volumeMultiplier + random.nextDouble(-2.0, 2.0))
            .coerceIn(60.0, 150.0)
        val hip = (waist + hipDiff * volumeMultiplier + random.nextDouble(-2.0, 2.0))
            .coerceIn(60.0, 150.0)

        val baseBmi = baseWeight / ((baseHeight / 100.0) * (baseHeight / 100.0))
        val weight = (baseBmi * volumeMultiplier * (height / 100.0) * (height / 100.0) + random.nextDouble(-1.5, 1.5))
            .coerceIn(30.0, 150.0)

        return GeneratedBody(
            height = Math.round(height * 10.0) / 10.0,
            weight = Math.round(weight * 10.0) / 10.0,
            bust = Math.round(bust).toDouble(),
            waist = Math.round(waist).toDouble(),
            hip = Math.round(hip).toDouble()
        )
    }

    /**
     * 생성 결과를 즉시 분석.
     */
    fun analyzeGenerated(body: GeneratedBody, config: BodyAnalysisConfig = BodyAnalysisConfig.DEFAULT): BodyAnalysisResult {
        return BodyAnalysisHelper().analyze(body.bust, body.waist, body.hip, body.height, body.weight, config)
    }

    // ── 분포 분석 ──

    enum class BodyCategory { SLIM, NORMAL, VOLUPTUOUS }

    /**
     * 캐릭터의 체형 카테고리를 판정.
     * 키가 있으면 volumeIndex, 없으면 bustWaistDiff 기반.
     */
    fun categorize(bust: Double, waist: Double, hip: Double, height: Double?): BodyCategory {
        return if (height != null && height > 0) {
            val volumeIndex = (bust + waist + hip) / (3.0 * height)
            when {
                volumeIndex < 0.45 -> BodyCategory.SLIM
                volumeIndex < 0.52 -> BodyCategory.NORMAL
                else -> BodyCategory.VOLUPTUOUS
            }
        } else {
            val bustWaistDiff = bust - waist
            when {
                bustWaistDiff < 12 -> BodyCategory.SLIM
                bustWaistDiff < 20 -> BodyCategory.NORMAL
                else -> BodyCategory.VOLUPTUOUS
            }
        }
    }

    data class DistributionSummary(
        val slim: Int = 0,
        val normal: Int = 0,
        val voluptuous: Int = 0,
        val total: Int = 0
    ) {
        val recommendation: BodyCategory?
            get() {
                if (total == 0) return null
                return listOf(
                    BodyCategory.SLIM to slim,
                    BodyCategory.NORMAL to normal,
                    BodyCategory.VOLUPTUOUS to voluptuous
                ).minByOrNull { it.second }?.first
            }
    }

    // ── 상대 생성 배율 ──

    val RELATIVE_MULTIPLIERS = listOf(
        "훨씬 작게" to 0.875,    // -12.5%
        "조금 작게" to 0.925,    // -7.5%
        "비슷" to 1.0,           // ±0%
        "조금 크게" to 1.075,    // +7.5%
        "훨씬 크게" to 1.125     // +12.5%
    )
}
