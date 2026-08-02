package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 🎲 생성기의 지각 3축 계약 (설계 9장 P5).
 *
 * 이 파일이 지키는 것은 **고른 말과 돌아오는 말이 같은가**다. 축 셀렉터에서 '슬림'을
 * 골랐는데 요약이 '소프트'라고 답하면 셀렉터는 이름표일 뿐 조작이 아니다 —
 * 흔들림 폭이 밴드를 넘지 않는다는 사실을 난수 여러 벌로 확인한다.
 */
class BodyGeneratorTest {

    private val options = BodyGenerator.GenerationPreset()
    private val heights = options.heightOptions

    /**
     * 축 하나를 여러 난수·여러 키로 굴려 본다 — 흔들림이 밴드를 넘는지는 표본으로만 보인다.
     *
     * 기본 보정은 분석 규약과 같은 값이다. 종전에는 여기만 0이라 **0으로 굴리고 6으로 재는**
     * 비정합 표본이었다 — 가슴 축이 증가량이던 시절엔 우연히 상쇄돼 통과했다.
     */
    private fun roll(
        torso: Int = 1, bust: Int = 1, hip: Int = 1, cupDiff: Double? = null,
        ribOffset: Double = BodyAnalysisConfig.DEFAULT_RIB_OFFSET
    ): List<BodyGenerator.GeneratedBody> = buildList {
        for (h in heights.indices) {
            for (seed in 0 until 40) {
                add(
                    BodyGenerator.generate(
                        heights[h], options.torsoOptions[torso], options.bustOptions[bust],
                        options.hipOptions[hip], cupDiff, ribOffset, Random(seed * 31 + h)
                    )
                )
            }
        }
    }

    private fun measures(b: BodyGenerator.GeneratedBody) =
        BodySilhouetteSpec.Measures(b.height, BodySilhouetteSpec.BASE.shoulder, b.bust, b.waist, b.hip)

    // ── 축이 요약과 일치하는가 ───────────────────────────────────────────

    @Test
    fun `몸통 축은 요약의 몸통 라벨과 일치한다`() {
        for ((i, option) in options.torsoOptions.withIndex()) {
            for (body in roll(torso = i)) {
                assertEquals(
                    "키 ${body.height} 허리 ${body.waist}",
                    option.label, BodySilhouetteSpec.axisSummary(measures(body)).torso
                )
            }
        }
    }

    @Test
    fun `힙 축은 요약의 힙 라벨과 일치한다`() {
        for ((i, option) in options.hipOptions.withIndex()) {
            for (body in roll(hip = i)) {
                assertEquals(
                    "허리 ${body.waist} 엉덩이 ${body.hip}",
                    option.label, BodySilhouetteSpec.axisSummary(measures(body)).hip
                )
            }
        }
    }

    @Test
    fun `가슴 축은 단조 증가한다`() {
        val means = options.bustOptions.indices.map { i ->
            roll(bust = i).map { BodySilhouetteSpec.cupDiff(measures(it)) }.average()
        }
        for (i in 1 until means.size) assertTrue("$means", means[i] > means[i - 1])
    }

    @Test
    fun `가장 작은 가슴 축도 컵차 8에서 12 사이다`() {
        // P5-② 장르 상향 — 슬렌더가 절벽이 되지 않는다는 설계의 수치 그대로다.
        for (body in roll(bust = 0)) {
            val cd = BodySilhouetteSpec.cupDiff(measures(body))
            assertTrue("컵차 $cd (가슴 ${body.bust} 허리 ${body.waist})", cd in 8.0..12.0)
        }
    }

    @Test
    fun `가슴 축의 컵차는 흉곽 보정과 무관하다`() {
        // 축의 의미가 컵차이므로 보정이 얼마든 고른 축과 읽히는 컵이 같아야 한다 —
        // 종전(증가량 의미)에는 보정 0에서 아담이 D로 읽혔다(B-92 잔여 결함).
        for (offset in listOf(0.0, 6.0, 10.0)) {
            for (option in options.bustOptions) {
                for (body in roll(bust = options.bustOptions.indexOf(option), ribOffset = offset)) {
                    val m = BodySilhouetteSpec.Measures(
                        body.height, BodySilhouetteSpec.BASE.shoulder,
                        body.bust, body.waist, body.hip, ribOffset = offset
                    )
                    val cd = BodySilhouetteSpec.cupDiff(m)
                    // 흔들림 ±2 + 정수 반올림 ±1
                    assertTrue(
                        "보정 $offset 축 ${option.label} 컵차 $cd",
                        kotlin.math.abs(cd - option.cupDiff) <= 3.0
                    )
                }
            }
        }
    }

    // ── 컵 역산 ──────────────────────────────────────────────────────────

    @Test
    fun `컵 역산은 가슴 축보다 우선한다`() {
        val target = 20.0
        for (body in roll(bust = 0, cupDiff = target, ribOffset = 2.0)) {
            val actual = body.bust - (body.waist + 2.0)
            assertTrue("컵차 $actual", kotlin.math.abs(actual - target) <= 2.0)
        }
    }

    // ── 프리셋 ───────────────────────────────────────────────────────────

    @Test
    fun `프리셋은 세 축을 한 번에 세운다`() {
        val (torso, bust, hip) = BodyGenerator.axesOf(options.bodyPresets.first(), options)
        assertEquals(options.torsoOptions[0], torso)
        assertEquals(options.bustOptions[0], bust)
        assertEquals(options.hipOptions[0], hip)
    }

    @Test
    fun `프리셋 인덱스가 목록 밖이면 가운데 축으로 접는다`() {
        val (torso, bust, hip) = BodyGenerator.axesOf(
            BodyGenerator.BodyPreset("깨진 프리셋", torso = 9, bust = 9, hip = 9), options
        )
        assertEquals(options.torsoOptions[options.torsoOptions.size / 2], torso)
        assertEquals(options.bustOptions[options.bustOptions.size / 2], bust)
        assertEquals(options.hipOptions[options.hipOptions.size / 2], hip)
    }

    @Test
    fun `모든 프리셋이 실제 축을 가리킨다`() {
        for (preset in options.bodyPresets) {
            assertTrue(preset.label, preset.torso in options.torsoOptions.indices)
            assertTrue(preset.label, preset.bust in options.bustOptions.indices)
            assertTrue(preset.label, preset.hip in options.hipOptions.indices)
        }
    }

    // ── 산출 한계 ────────────────────────────────────────────────────────

    @Test
    fun `어떤 축 조합에서도 값이 산출 한계 안에 있다`() {
        for (t in options.torsoOptions.indices) for (b in options.bustOptions.indices) {
            for (body in roll(torso = t, bust = b, hip = options.hipOptions.size - 1)) {
                assertTrue(body.height in BodyEditorModel.HEIGHT_RANGE)
                assertTrue(body.weight in BodyEditorModel.WEIGHT_RANGE)
                assertTrue(body.bust in 60.0..150.0)
                assertTrue(body.waist in 45.0..110.0)
                assertTrue(body.hip in 60.0..150.0)
            }
        }
    }

    @Test
    fun `가슴이 클수록 몸무게가 무겁다`() {
        val light = roll(bust = 0).map { it.weight }.average()
        val heavy = roll(bust = options.bustOptions.size - 1).map { it.weight }.average()
        assertTrue("$light → $heavy", heavy > light)
    }

    // ── 상대 생성 ────────────────────────────────────────────────────────

    @Test
    fun `상대 생성은 기준 캐릭터의 비율을 따라간다`() {
        val bigger = BodyGenerator.generateRelative(
            baseHeight = 165.0, baseWaist = 62.0, baseBust = 86.0, baseHip = 90.0, baseWeight = 55.0,
            heightMultiplier = 1.075, volumeMultiplier = 1.075, random = Random(7)
        )
        assertTrue(bigger.height > 165.0)
        assertTrue(bigger.bust > 86.0)
    }

    @Test
    fun `분석은 생성 결과를 그대로 읽는다`() {
        val body = BodyGenerator.generate(
            heights[1], options.torsoOptions[1], options.bustOptions[2], options.hipOptions[2],
            random = Random(3)
        )
        val result = BodyGenerator.analyzeGenerated(body, BodyAnalysisConfig.DEFAULT)
        assertEquals(body.bwhString, "${body.bust.toInt()}-${body.waist.toInt()}-${body.hip.toInt()}")
        assertTrue(result.bodyTags.isNotEmpty() || result.bodyType != null)
    }
}
