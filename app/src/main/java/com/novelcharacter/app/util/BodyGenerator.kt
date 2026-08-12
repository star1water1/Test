package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.BodyPreset
import com.novelcharacter.app.data.model.BodyAnalysisConfig.BustOption
import com.novelcharacter.app.data.model.BodyAnalysisConfig.GenerationPreset
import com.novelcharacter.app.data.model.BodyAnalysisConfig.HipOption
import com.novelcharacter.app.data.model.BodyAnalysisConfig.TorsoOption
import kotlin.random.Random

/**
 * 신체 수치 자동 생성기.
 * 키 + 지각 3축(몸통·가슴·힙) 선택 → 랜덤 BWH/키/몸무게 생성 → BodyAnalysisHelper로 즉시 분석.
 *
 * **축 구성은 설계 9장 P5가 정본이다** — 사람이 보는 것은 "체형 하나"가 아니라 몸통의
 * 마름 정도·가슴·힙이 **각각 따로**이고, 수치 실체(허리/키 · 컵차 · 힙−허리)도 이미
 * 독립이다. 그래서 축 셀렉터 셋(정밀)과 프리셋(러프)의 이중 경로를 제공한다(원칙 04).
 *
 * **축과 프리셋 자체는 사용자 것이다**(B-93 · 확정 7번) — 값은
 * [BodyAnalysisConfig.GenerationPreset]에 세계관 단위로 담기고, 이 파일은 *그 값으로
 * 무엇을 하는가*만 든다. 그래서 생성 함수는 축 목록과 **인덱스**를 함께 받는다:
 * 축의 구간이 목록에서의 자리에서 파생되므로(앞 축의 끝 → 자기 끝), 옵션 객체만으로는
 * 겨눌 폭을 알 수 없다.
 *
 * 축의 목표값은 [BodySilhouetteSpec.axisSummary]가 그 라벨을 돌려주는 구간의 한가운데를
 * 겨눈다 — **고른 말과 돌아오는 말이 같아야** 생성기가 쓸모를 갖는다(슬림을 고르면 요약이
 * 슬림이라고 답한다). 분포는 장르 상향돼 있다(P5-② — 가장 작은 가슴 축도 컵차 8~12).
 */
object BodyGenerator {

    data class GeneratedBody(
        val height: Double,
        val weight: Double,
        val bust: Double,
        val waist: Double,
        val hip: Double
    ) {
        val bwhString: String get() = "${bust.toInt()}-${waist.toInt()}-${hip.toInt()}"
    }

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

    /**
     * 목록 밖 인덱스는 가운데 축으로 접는다 — **빈손으로 돌려보내지 않는다.**
     * 빈 목록은 호출부가 [BodyAnalysisConfig.GenerationPreset.usable]로 미리 막는다.
     */
    private fun <T> List<T>.fold(index: Int): Int = if (index in indices) index else size / 2

    /**
     * 흔들림 — 폭이 0 이하면 흔들지 않는다.
     *
     * [Random.nextDouble]은 `from >= until`에 예외를 던진다. 축의 폭이 사용자 값이 된
     * 이상(B-93) 0은 *"정확히 이 키로"*라는 정당한 설정이고, 그것으로 🎲가 죽어서는 안 된다.
     */
    private fun Random.jitter(width: Double): Double =
        if (width > 0) nextDouble(-width, width) else 0.0

    /**
     * 몸무게 보정의 기준점 — 축 기본값(내추럴 · 힙 표준)에서 0이 되게 잡는다.
     * 가슴 쪽 21 = 내추럴 컵차 15 + 기본 흉곽 보정 6 (실제 가슴−허리를 재므로 보정이
     * 크면 몸무게도 조금 붙는다 — 흉곽이 큰 몸이 무거운 것은 물리적으로 맞다).
     */
    private const val BUST_BONUS_REF = 21.0
    private const val HIP_BONUS_REF = 27.0

    /** 목표 비율의 장르 기준이 파생되는 **기본** 프리셋 라벨 — 존재는 테스트가 못 박는다. */
    const val GENRE_IDEAL_PRESET_LABEL = "아워글래스"

    /**
     * 장르 기준이 파생되는 프리셋의 **자리**(위 라벨이 기본 목록에서 선 자리).
     *
     * 이름이 아니라 자리로 찾는 것이 B-93 이후의 규약이다 — 사용자가 프리셋 이름을 바꿀 수
     * 있게 된 순간 라벨로 찾는 코드는 **못 찾거나(빈손) 예외로 죽는다.** 자리로 찾으면
     * *"X라인 중심 칸"*이라는 뜻이 이름과 무관하게 남고, 사용자가 그 칸을 다시 정의하면
     * 목표 비율 기준도 함께 움직인다(세계관마다 장르가 다르다 — 이 항목의 출발점).
     */
    val GENRE_IDEAL_PRESET_INDEX =
        BodyAnalysisConfig.DEFAULT_BODY_PRESETS.indexOfFirst { it.label == GENRE_IDEAL_PRESET_LABEL }

    /**
     * 목표 비율의 **장르 기준 이상값** (P8 재의미화 — 2026.08.02 사용자 요청 "장르문법상
     * 최적 비율").
     *
     * 숫자를 새로 발명하지 않는다 — **[GENRE_IDEAL_PRESET_LABEL] 프리셋(X라인 중심)의 세 축
     * 목표값에서 계산한다.** 축 상수는 목-⑤로 사용자 잠정 승인을 받은 자리이고, 작품 평균
     * 실측 교정이 오면 축과 이상값이 **함께** 움직인다(값을 두 벌 적으면 갈린다 — B-92의
     * 교훈 그대로).
     *
     * 키·흉곽 보정에 적응한다: 이상은 "같은 키·같은 세계 규약의 X라인 중심 몸"이다.
     * 150cm 캐릭터를 165cm 기준 몸과 비교하면 같은 체형인데도 점수가 깎인다.
     *
     * 종전 기본값(WHR .70 · B/H 1.00 · W/키 .40 · B/키 .52)은 실사 몸의 황금비 계열이라
     * P8이 "황금비 잔재 제거"로 판정한 자리다 — 이 함수가 그 제거의 이행이다.
     */
    fun genreTargetIdeals(
        heightCm: Double = BodySilhouetteSpec.BASE.height,
        ribOffset: Double = BodyAnalysisConfig.DEFAULT_RIB_OFFSET,
        options: GenerationPreset = BodyAnalysisConfig.DEFAULT_GENERATION
    ): Map<String, Double> {
        // 자리로 찾고, 그 자리가 비면 기본 프리셋으로 — **여기서 예외가 나면 안 된다.**
        // 목표 비율은 읽기 카드가 캐릭터마다 부르는 경로라, 한 세계관의 축 설정이 망가지면
        // 그 화면이 통째로 죽는다(축을 연 이 판이 새로 만든 위험이고, 그래서 여기서 막는다).
        val preset = options.bodyPresets.getOrNull(GENRE_IDEAL_PRESET_INDEX)
            ?: BodyAnalysisConfig.DEFAULT_BODY_PRESETS[GENRE_IDEAL_PRESET_INDEX]
        val (torso, bust, hip) = axesOf(preset, options)
        return idealsFromFigure(torso.waistRatio, bust.cupDiff, hip.hipBonus, heightCm, ribOffset)
    }

    /**
     * 사용자가 치수로 적은 이상 몸을 캐릭터 키에 맞춘 이상값으로 (P8 '사용자 이상형' —
     * 2026.08.02 사용자 요청 "사이즈 이상치를 직접 입력, 키에 따라 비율을 조정").
     *
     * 몸을 (허리/키 비율 · 컵차 cm · 힙−허리 cm)로 분해한 뒤 캐릭터 키에서 재구성한다 —
     * [genreTargetIdeals]와 같은 분해라 두 소스의 키 적응이 같은 문법으로 움직인다.
     * **cm 축을 비율로 늘리지 않는 것은 축 체계의 결**이다: 컵차·힙−허리의 밴드는 키와
     * 무관한 cm이고(D컵은 150cm에서도 D컵), 그래서 작은 키에서 비율이 "어느 정도" 더
     * 잘록해진다 — 몸매는 유지하고 뼈대만 줄인 몸과 비교한다.
     *
     * 기준 키가 없으면 기준 몸 키로 읽는다. **기준 키의 캐릭터에게는 적은 몸 그대로가
     * 이상이 된다**(분해→재구성이 항등 — 흉곽 보정이 얼마든 컵차 항이 상쇄한다).
     * 셋(B·W·H)이 안 갖춰지면 null — 효력 없음은 호출부가 장르 기준으로 잇는다.
     */
    fun idealsFromBody(
        body: BodyAnalysisConfig.IdealBody,
        characterHeightCm: Double,
        ribOffset: Double = BodyAnalysisConfig.DEFAULT_RIB_OFFSET
    ): Map<String, Double>? {
        if (!body.isComplete) return null
        val ref = body.heightCm?.takeIf { it > 0 } ?: BodySilhouetteSpec.BASE.height
        return idealsFromFigure(
            waistRatio = body.waist!! / ref,
            cupDiffCm = body.bust!! - body.waist!! - ribOffset,
            hipDiffCm = body.hip!! - body.waist!!,
            heightCm = characterHeightCm,
            ribOffset = ribOffset
        )
    }

    /**
     * 몸의 분해값(허리/키 비율 · 컵차 cm · 힙−허리 cm)을 키에서 재구성해 목표 비율
     * 이상값 4종으로. [genreTargetIdeals]·[idealsFromBody]가 공유하는 유일한 재구성이다.
     */
    fun idealsFromFigure(
        waistRatio: Double,
        cupDiffCm: Double,
        hipDiffCm: Double,
        heightCm: Double,
        ribOffset: Double
    ): Map<String, Double> {
        val h = if (heightCm > 0) heightCm else BodySilhouetteSpec.BASE.height
        val waist = waistRatio * h
        val bustCm = waist + ribOffset + cupDiffCm
        val hipCm = waist + hipDiffCm
        return mapOf(
            "whr" to waist / hipCm,
            "bustHipRatio" to bustCm / hipCm,
            "waistHeight" to waistRatio,
            "bustHeight" to bustCm / h
        )
    }

    /**
     * 프리셋이 가리키는 세 축을 꺼낸다. 인덱스가 목록 밖이면 가운데 축으로 접는다 —
     * 프리셋 목록만 바꾼 사용자가 빈손으로 돌아가지 않게 한다.
     */
    fun axesOf(
        preset: BodyPreset,
        options: GenerationPreset = BodyAnalysisConfig.DEFAULT_GENERATION
    ): Triple<TorsoOption, BustOption, HipOption> = options.usable.let { o ->
        Triple(
            o.torsoOptions[o.torsoOptions.fold(preset.torso)],
            o.bustOptions[o.bustOptions.fold(preset.bust)],
            o.hipOptions[o.hipOptions.fold(preset.hip)]
        )
    }

    // ── 생성 알고리즘 ──

    /**
     * 신체 수치 생성 — 키 + 지각 3축(P5).
     *
     * 축 목표값의 흔들림 폭은 **밴드를 넘지 않게** 잡혀 있다. 고른 축과 다른 요약이
     * 돌아오면 셀렉터가 거짓말을 하는 셈이라, 흔들림은 밴드 안에서만 준다.
     *
     * **축을 인덱스로 받는 것이 B-93 이후의 형태다** — 구간이 목록에서의 자리에서
     * 파생되므로(앞 축의 끝 → 자기 끝) 옵션 객체만으로는 겨눌 폭을 알 수 없다.
     * 목록 밖을 가리키면 가운데 축으로 접는다([axesOf]와 같은 처분 — 빈손으로 돌려보내지 않는다).
     *
     * @param heightIndex 음수면 키 축을 무작위로 고른다('아무거나')
     * @param targetCupDiff non-null이면 컵 사이즈 역산으로 가슴 결정 (가슴 축보다 우선)
     * @param ribOffset 흉곽 보정값 (BodyAnalysisConfig.ribOffset) — 가슴 축·컵 역산 모두
     *   `가슴 = 허리 + 보정 + 컵차`로 여기를 지난다(B-92의 규약 한 벌이 생성에도 성립)
     */
    fun generate(
        options: GenerationPreset,
        heightIndex: Int,
        torsoIndex: Int,
        bustIndex: Int,
        hipIndex: Int,
        targetCupDiff: Double? = null,
        ribOffset: Double = BodyAnalysisConfig.DEFAULT_RIB_OFFSET,
        random: Random = Random.Default
    ): GeneratedBody {
        val opts = options.usable
        val heightOption = if (heightIndex < 0) opts.heightOptions.random(random)
        else opts.heightOptions[opts.heightOptions.fold(heightIndex)]
        val torsoAt = opts.torsoOptions.fold(torsoIndex)
        val bustAt = opts.bustOptions.fold(bustIndex)
        val hipAt = opts.hipOptions.fold(hipIndex)
        val torsoOption = opts.torsoOptions[torsoAt]
        val bustOption = opts.bustOptions[bustAt]
        val hipOption = opts.hipOptions[hipAt]
        val torsoBand = opts.torsoBand(torsoAt)
        val hipBand = opts.hipBand(hipAt)

        // 흔들림 폭 0은 "정확히 이 값"이라는 뜻이다 — 사용자가 정할 수 있는 값이므로
        // 난수 호출에 그대로 넘기지 않는다(`nextDouble(-0.0, 0.0)`은 예외를 던진다).
        val height = (heightOption.center + random.jitter(heightOption.variance))
            .coerceIn(140.0, 200.0)

        // 흔들림은 구간 안에서만 준다 — 넘으면 요약이 다른 축 이름을 돌려준다.
        // 반올림(정수 cm)이 경계를 다시 넘을 수 있어 [BAND_INSET_CM]만큼 안으로 물린다.
        // **허리는 먼저 반올림한다** — 가슴·엉덩이가 반올림 전 허리에서 자라면 두 번의
        // 반올림 오차가 겹쳐 차이(컵차·힙−허리)가 구간 밖으로 나간다.
        val waist = Math.round(
            bandFold(
                height * torsoOption.waistRatio + random.nextDouble(-1.5, 1.5),
                torsoBand.start * height, torsoBand.endInclusive * height
            ).coerceIn(45.0, 110.0)
        ).toDouble()

        // 가슴은 축이든 역산이든 같은 식 하나다: 가슴 = 밑가슴(허리+보정) + 컵차.
        val bust = if (targetCupDiff != null) {
            (waist + ribOffset + targetCupDiff + random.nextDouble(-1.5, 1.5)).coerceIn(60.0, 150.0)
        } else {
            (waist + ribOffset + bustOption.cupDiff + random.nextDouble(-2.0, 2.0)).coerceIn(60.0, 150.0)
        }

        val hip = bandFold(
            waist + hipOption.hipBonus + random.nextDouble(-2.5, 2.5),
            waist + hipBand.start, waist + hipBand.endInclusive
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
