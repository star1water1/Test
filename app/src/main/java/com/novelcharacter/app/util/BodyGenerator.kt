package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import kotlin.random.Random

/**
 * 신체 수치 자동 생성기.
 * 키 + 지각 3축(몸통·가슴·힙) 선택 → 랜덤 BWH/키/몸무게 생성 → BodyAnalysisHelper로 즉시 분석.
 *
 * **축 구성은 설계 9장 P5가 정본이다** — 사람이 보는 것은 "체형 하나"가 아니라 몸통의
 * 마름 정도·가슴·힙이 **각각 따로**이고, 수치 실체(허리/키 · 컵차 · 힙−허리)도 이미
 * 독립이다. 그래서 축 셀렉터 셋(정밀)과 프리셋(러프)의 이중 경로를 제공한다(원칙 04).
 *
 * 축의 목표값은 [BodySilhouetteSpec.axisSummary]의 밴드 한가운데를 겨눈다 — **고른 말과
 * 돌아오는 말이 같아야** 생성기가 쓸모를 갖는다(슬림을 고르면 요약이 슬림이라고 답한다).
 * 분포는 장르 상향돼 있다(P5-② — 가장 작은 가슴 축도 절벽이 아니라 컵차 8~12).
 */
object BodyGenerator {

    data class HeightOption(val label: String, val center: Double, val variance: Double)

    /**
     * 몸통 축 — 허리를 키 비율로 잡는다(마름 정도의 수치 실체가 허리/키다).
     *
     * [ratioBand]는 [BodySilhouetteSpec.axisSummary]가 이 라벨을 돌려주는 구간이며
     * **경계 상수는 그쪽이 정본이다.** 생성 결과는 이 구간 안으로 접힌다.
     */
    data class TorsoOption(
        val label: String,
        val waistRatio: Double,     // 허리 ÷ 키 (구간 한가운데)
        val bmiTarget: Double,      // 목표 BMI (몸무게 역산용)
        val ratioBand: ClosedFloatingPointRange<Double>
    )

    /** 가슴 축 — 허리 대비 증가량(cm). 그림의 컵차는 여기서 [BodySilhouetteSpec.FIGURE_RIB_OFFSET_CM]을 뺀 값이다. */
    data class BustOption(val label: String, val bustBonus: Double)

    /** 힙 축 — 허리 대비 증가량(cm). [diffBand]의 근거는 [TorsoOption.ratioBand]와 같다. */
    data class HipOption(
        val label: String,
        val hipBonus: Double,
        val diffBand: ClosedFloatingPointRange<Double>
    )

    /**
     * 체형 프리셋 — 세 축을 한 번에 세우는 러프 경로. 인덱스는 아래 기본 목록 기준이다.
     * 프리셋을 고른 뒤 축을 따로 바꾸는 것이 정밀 경로다(둘은 배타가 아니다).
     */
    data class BodyPreset(val label: String, val torso: Int, val bust: Int, val hip: Int)

    data class GeneratedBody(
        val height: Double,
        val weight: Double,
        val bust: Double,
        val waist: Double,
        val hip: Double
    ) {
        val bwhString: String get() = "${bust.toInt()}-${waist.toInt()}-${hip.toInt()}"
    }

    // ── 기본 옵션 (어휘는 전부 긍정 프레이밍 — 고르는 말은 전부 매력적이어야 한다, P5-③) ──

    val DEFAULT_HEIGHT_OPTIONS = listOf(
        HeightOption("아담", 152.0, 5.0),
        HeightOption("보통", 163.0, 4.0),
        HeightOption("장신", 172.0, 4.0),
        HeightOption("초장신", 180.0, 5.0)
    )

    /** 허리/키 목표 — 구간은 `axisSummary`의 경계 상수가 가른다. 각 값은 구간 한가운데다. */
    val DEFAULT_TORSO_OPTIONS = listOf(
        TorsoOption("슬림", .350, 18.5, .0..BodySilhouetteSpec.TORSO_SLIM_MAX_RATIO),
        TorsoOption("표준", .382, 20.5, BodySilhouetteSpec.TORSO_SLIM_MAX_RATIO..BodySilhouetteSpec.TORSO_STANDARD_MAX_RATIO),
        TorsoOption("소프트", .420, 23.0, BodySilhouetteSpec.TORSO_STANDARD_MAX_RATIO..1.0)
    )

    /**
     * 가슴 − 허리(cm). 그림 컵차 = 이 값 − 6이므로 8 / 13 / 18 / 24 → 대략 A · C · E · G다.
     * 가장 작은 축이 컵차 8~12인 것이 P5-② '장르 상향'의 이행이다(종전 최소는 8, 즉 컵차 2).
     */
    val DEFAULT_BUST_OPTIONS = listOf(
        BustOption("아담", 16.0),
        BustOption("내추럴", 21.0),
        BustOption("볼륨", 26.0),
        BustOption("글래머", 32.0)
    )

    /** 엉덩이 − 허리(cm). 구간은 `axisSummary`의 경계 상수가 가른다. */
    val DEFAULT_HIP_OPTIONS = listOf(
        HipOption("힙 슬림", 22.0, .0..BodySilhouetteSpec.HIP_SLIM_MAX_DIFF),
        HipOption("힙 표준", 27.0, BodySilhouetteSpec.HIP_SLIM_MAX_DIFF..BodySilhouetteSpec.HIP_STANDARD_MAX_DIFF),
        HipOption("볼륨힙", 34.0, BodySilhouetteSpec.HIP_STANDARD_MAX_DIFF..99.0)
    )

    /** 세 축 세트. 네 귀퉁이 + 곡선형 하나 — 프리셋만으로도 폭이 나오게 골랐다. */
    val DEFAULT_BODY_PRESETS = listOf(
        BodyPreset("슬렌더", torso = 0, bust = 0, hip = 0),
        BodyPreset("내추럴", torso = 1, bust = 1, hip = 1),
        BodyPreset("아워글래스", torso = 0, bust = 2, hip = 2),
        BodyPreset("글래머", torso = 2, bust = 3, hip = 2)
    )

    /**
     * 축 구간의 안쪽 여백(cm) — 산출값은 정수 cm로 반올림되므로 경계에 붙으면
     * 반올림 한 번에 옆 구간으로 넘어간다(0.5 + 여유).
     */
    private const val BAND_INSET_CM = .6

    /** 값을 축 구간 안으로 접는다. 구간이 여백보다 좁으면 한가운데로 둔다. */
    private fun bandFold(value: Double, lowCm: Double, highCm: Double): Double {
        val lo = lowCm + BAND_INSET_CM
        val hi = highCm - BAND_INSET_CM
        return if (lo >= hi) (lowCm + highCm) / 2 else value.coerceIn(lo, hi)
    }

    /** 몸무게 보정의 기준점 — 축 기본값(내추럴 · 힙 표준)에서 0이 되게 잡는다. */
    private const val BUST_BONUS_REF = 21.0
    private const val HIP_BONUS_REF = 27.0

    data class GenerationPreset(
        val heightOptions: List<HeightOption> = DEFAULT_HEIGHT_OPTIONS,
        val torsoOptions: List<TorsoOption> = DEFAULT_TORSO_OPTIONS,
        val bustOptions: List<BustOption> = DEFAULT_BUST_OPTIONS,
        val hipOptions: List<HipOption> = DEFAULT_HIP_OPTIONS,
        val bodyPresets: List<BodyPreset> = DEFAULT_BODY_PRESETS
    )

    /**
     * 프리셋이 가리키는 세 축을 꺼낸다. 인덱스가 목록 밖이면 가운데 축으로 접는다 —
     * 프리셋 목록만 바꾼 사용자가 빈손으로 돌아가지 않게 한다.
     */
    fun axesOf(
        preset: BodyPreset,
        options: GenerationPreset = GenerationPreset()
    ): Triple<TorsoOption, BustOption, HipOption> = Triple(
        options.torsoOptions.getOrNull(preset.torso) ?: options.torsoOptions[options.torsoOptions.size / 2],
        options.bustOptions.getOrNull(preset.bust) ?: options.bustOptions[options.bustOptions.size / 2],
        options.hipOptions.getOrNull(preset.hip) ?: options.hipOptions[options.hipOptions.size / 2]
    )

    // ── 생성 알고리즘 ──

    /**
     * 신체 수치 생성 — 키 + 지각 3축(P5).
     *
     * 축 목표값의 흔들림 폭은 **밴드를 넘지 않게** 잡혀 있다. 고른 축과 다른 요약이
     * 돌아오면 셀렉터가 거짓말을 하는 셈이라, 흔들림은 밴드 안에서만 준다.
     *
     * @param targetCupDiff non-null이면 컵 사이즈 역산으로 가슴 결정 (가슴 축보다 우선)
     * @param ribOffset 컵 역산용 흉곽 보정값 (BodyAnalysisConfig.ribOffset — 분석 쪽 규약)
     */
    fun generate(
        heightOption: HeightOption,
        torsoOption: TorsoOption,
        bustOption: BustOption,
        hipOption: HipOption,
        targetCupDiff: Double? = null,
        ribOffset: Double = 0.0,
        random: Random = Random.Default
    ): GeneratedBody {
        val height = (heightOption.center + random.nextDouble(-heightOption.variance, heightOption.variance))
            .coerceIn(140.0, 200.0)

        // 흔들림은 구간 안에서만 준다 — 넘으면 요약이 다른 축 이름을 돌려준다.
        // 반올림(정수 cm)이 경계를 다시 넘을 수 있어 [BAND_INSET_CM]만큼 안으로 물린다.
        // **허리는 먼저 반올림한다** — 가슴·엉덩이가 반올림 전 허리에서 자라면 두 번의
        // 반올림 오차가 겹쳐 차이(컵차·힙−허리)가 구간 밖으로 나간다.
        val waist = Math.round(
            bandFold(
                height * torsoOption.waistRatio + random.nextDouble(-1.5, 1.5),
                torsoOption.ratioBand.start * height, torsoOption.ratioBand.endInclusive * height
            ).coerceIn(45.0, 110.0)
        ).toDouble()

        val bust = if (targetCupDiff != null) {
            // 컵 사이즈 역산: underbust = waist + ribOffset, bust = underbust + cupDiff
            val underbust = waist + ribOffset
            (underbust + targetCupDiff + random.nextDouble(-1.5, 1.5)).coerceIn(60.0, 150.0)
        } else {
            (waist + bustOption.bustBonus + random.nextDouble(-2.0, 2.0)).coerceIn(60.0, 150.0)
        }

        val hip = bandFold(
            waist + hipOption.hipBonus + random.nextDouble(-2.5, 2.5),
            waist + hipOption.diffBand.start, waist + hipOption.diffBand.endInclusive
        ).coerceIn(60.0, 150.0)

        // 체중 보정: 가슴/엉덩이 축이 기준점에서 벗어난 만큼 체적 변화를 반영
        val bustDelta = (bust - waist) - BUST_BONUS_REF
        val hipDelta = hipOption.hipBonus - HIP_BONUS_REF
        val weightAdj = bustDelta * 0.04 + hipDelta * 0.03
        val weight = (torsoOption.bmiTarget * (height / 100.0) * (height / 100.0) + weightAdj + random.nextDouble(-2.0, 2.0))
            .coerceIn(30.0, 150.0)

        return GeneratedBody(
            height = Math.round(height * 10.0) / 10.0,
            weight = Math.round(weight * 10.0) / 10.0,
            bust = Math.round(bust).toDouble(),
            waist = waist,
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
