package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.GenerationPreset
import com.novelcharacter.app.util.BodyGenerator
import com.novelcharacter.app.util.BodySilhouetteSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * **들어갈 정수 cm가 없는 축을 잡아내는 잣대** (B-202 · 확정 15장 6번 — 막지 않고 경고만).
 *
 * 이 파일이 지키는 것은 셋이다.
 *
 * ① **경고가 예언한 것이 실제로 일어난다.** [GenerationPreset.emptyBands]가 잡은 축을 골라
 *    실제로 굴리면 요약이 **이웃 축의 이름**을 돌려준다 — 그것이 이 경고의 존재 이유다.
 *    반대로 잡히지 않은 축은 굴려도 제 이름으로 답한다. **두 방향을 다 재는 것이 요점이다** —
 *    한 방향만 재면 *늘 경고하는* 잣대도 통과한다.
 *
 * ② **잣대의 아래 끝은 열려 있다.** 요약은 `값 <= 축의 끝`을 앞에서부터 찾으므로 축 `i`가
 *    갖는 것은 `(앞 축의 끝, 자기 끝]`이다. 앞 축의 끝과 **같은** 정수는 앞 축의 몫이라
 *    이 축을 채우지 못한다 — 닫힌 구간으로 세면 그 자리를 놓친다.
 *
 * ③ **키는 이 한 벌이 실제로 내는 것만 본다.** 판정 초안은 140~200을 통째로 쓰라고 적었으나
 *    그 잣대로는 몸통 경고가 **원리적으로 뜰 수 없다**(끝값 `끝×키`가 그 구간을 지나며 12cm
 *    넘게 쓸려 언제나 정수를 지난다 — 이 파일의 마지막 시험이 그것을 그대로 잰다).
 */
class BodyBandGapTest {

    private fun configOf(gen: GenerationPreset) = BodyAnalysisConfig.DEFAULT.copy(generation = gen)

    private fun measures(b: BodyGenerator.GeneratedBody) =
        BodySilhouetteSpec.Measures(b.height, BodySilhouetteSpec.BASE.shoulder, b.bust, b.waist, b.hip)

    private fun rolls(
        gen: GenerationPreset, torso: Int = 1, hip: Int = 1
    ): List<BodyGenerator.GeneratedBody> = buildList {
        for (h in gen.heightOptions.indices) for (seed in 0 until 40) {
            add(BodyGenerator.generate(gen, h, torso, 1, hip, random = Random(seed * 31 + h)))
        }
    }

    /** 키를 한 값으로 못 박은 한 벌 — 흔들림 0은 *"정확히 이 키로"*라는 정당한 설정이다. */
    private fun fixedHeight(cm: Double) =
        listOf(BodyAnalysisConfig.HeightOption("고정", cm, 0.0))

    // ── ① 기본값은 조용하다 ────────────────────────────────────────────────

    @Test
    fun `손대지 않은 한 벌은 경고가 없다`() {
        val empty = BodyAnalysisConfig.DEFAULT_GENERATION.emptyBands()
        assertTrue("몸통 $empty", empty.torso.isEmpty())
        assertTrue("힙 $empty", empty.hip.isEmpty())
        assertTrue(empty.isEmpty)
    }

    // ── ② 힙 축 — 키와 무관하다 ────────────────────────────────────────────

    @Test
    fun `힙 구간에 정수가 없으면 그 자리를 잡는다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            hipOptions = listOf(
                BodyAnalysisConfig.HipOption("슬림", 22.0, maxDiff = 24.0),
                BodyAnalysisConfig.HipOption("표준", 24.2, maxDiff = 24.4),
                BodyAnalysisConfig.HipOption("볼륨", 34.0, maxDiff = 99.0)
            )
        )
        assertEquals(listOf(1), gen.emptyBands().hip)
    }

    @Test
    fun `앞 축의 끝과 같은 정수는 이 축을 채우지 못한다`() {
        fun hipEnds(low: Double, high: Double) = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            hipOptions = listOf(
                BodyAnalysisConfig.HipOption("슬림", 22.0, maxDiff = low),
                BodyAnalysisConfig.HipOption("표준", 24.0, maxDiff = high),
                BodyAnalysisConfig.HipOption("볼륨", 34.0, maxDiff = 99.0)
            )
        ).emptyBands().hip
        // (24.0, 24.4] — 24는 앞 축 몫이다. 닫힌 구간으로 세면 여기를 놓친다.
        assertEquals(listOf(1), hipEnds(24.0, 24.4))
        // (23.5, 24.4] — 24가 이 축의 것이라 채워진다.
        assertEquals(emptyList<Int>(), hipEnds(23.5, 24.4))
    }

    @Test
    fun `힙 첫 축은 잡히지 않는다 — 요약이 그 아래를 전부 갖는다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            hipOptions = listOf(
                BodyAnalysisConfig.HipOption("슬림", 0.2, maxDiff = 0.4),
                BodyAnalysisConfig.HipOption("표준", 27.0, maxDiff = 31.0),
                BodyAnalysisConfig.HipOption("볼륨", 34.0, maxDiff = 99.0)
            )
        )
        assertEquals(emptyList<Int>(), gen.emptyBands().hip)
    }

    // ── ③ 몸통 축 — 키가 정한다 ────────────────────────────────────────────

    @Test
    fun `키가 한 값으로 고정되면 좁은 몸통 구간이 잡힌다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            heightOptions = fixedHeight(163.0),
            torsoOptions = listOf(
                BodyAnalysisConfig.TorsoOption("슬림", .350, 18.5, maxRatio = .4000),
                BodyAnalysisConfig.TorsoOption("표준", .401, 20.5, maxRatio = .4020),
                BodyAnalysisConfig.TorsoOption("소프트", .420, 23.0, maxRatio = 1.0)
            )
        )
        // 163 × (.4000, .4020] = (65.2, 65.53] — 정수가 없다.
        assertEquals(listOf(1), gen.emptyBands().torso)
    }

    @Test
    fun `같은 구간도 키가 흔들리면 잡히지 않는다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            torsoOptions = listOf(
                BodyAnalysisConfig.TorsoOption("슬림", .350, 18.5, maxRatio = .4000),
                BodyAnalysisConfig.TorsoOption("표준", .401, 20.5, maxRatio = .4020),
                BodyAnalysisConfig.TorsoOption("소프트", .420, 23.0, maxRatio = 1.0)
            )
        )
        // 기본 키 축 넷(152±5 · 163±4 · 172±4 · 180±5)에는 이 구간을 채우는 키가 있다.
        assertEquals(emptyList<Int>(), gen.emptyBands().torso)
    }

    @Test
    fun `허리가 설 수 없는 자리에 놓인 구간도 잡는다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            heightOptions = fixedHeight(190.0),
            torsoOptions = listOf(
                // 190 × .2 = 38cm — 생성기가 45로 잘라 올리므로 이 축은 돌아올 수 없다.
                BodyAnalysisConfig.TorsoOption("슬림", .19, 18.5, maxRatio = .2),
                BodyAnalysisConfig.TorsoOption("표준", .382, 20.5, maxRatio = .402),
                BodyAnalysisConfig.TorsoOption("소프트", .420, 23.0, maxRatio = 1.0)
            )
        )
        assertEquals(listOf(0), gen.emptyBands().torso)
    }

    @Test
    fun `키 구간은 생성기가 자르는 자리와 같다`() {
        val low = BodyAnalysisConfig.DEFAULT_GENERATION
            .copy(heightOptions = fixedHeight(100.0)).heightSpans().single()
        val high = BodyAnalysisConfig.DEFAULT_GENERATION
            .copy(heightOptions = fixedHeight(250.0)).heightSpans().single()
        assertEquals(GenerationPreset.Limits.HEIGHT_REACH.start, low.start, 1e-9)
        assertEquals(GenerationPreset.Limits.HEIGHT_REACH.endInclusive, high.endInclusive, 1e-9)
    }

    // ── ④ 경고가 예언한 것이 실제로 일어난다 (양방향) ──────────────────────

    @Test
    fun `잡힌 몸통 축을 굴리면 요약이 이웃 축 이름으로 답한다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            heightOptions = fixedHeight(163.0),
            torsoOptions = listOf(
                BodyAnalysisConfig.TorsoOption("슬림", .350, 18.5, maxRatio = .4000),
                BodyAnalysisConfig.TorsoOption("표준", .401, 20.5, maxRatio = .4020),
                BodyAnalysisConfig.TorsoOption("소프트", .420, 23.0, maxRatio = 1.0)
            )
        )
        assertEquals(listOf(1), gen.emptyBands().torso)
        val config = configOf(gen)
        for (body in rolls(gen, torso = 1)) {
            assertNotEquals(
                "키 ${body.height} 허리 ${body.waist}",
                "표준", BodySilhouetteSpec.axisSummary(measures(body), config).torso
            )
        }
    }

    @Test
    fun `잡히지 않은 몸통 축은 굴려도 제 이름으로 답한다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION
        assertTrue(gen.emptyBands().isEmpty)
        val config = configOf(gen)
        for ((i, option) in gen.torsoOptions.withIndex()) {
            for (body in rolls(gen, torso = i)) {
                assertEquals(
                    "키 ${body.height} 허리 ${body.waist}",
                    option.label, BodySilhouetteSpec.axisSummary(measures(body), config).torso
                )
            }
        }
    }

    @Test
    fun `잡힌 힙 축을 굴리면 요약이 이웃 축 이름으로 답한다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            hipOptions = listOf(
                BodyAnalysisConfig.HipOption("슬림", 22.0, maxDiff = 24.0),
                BodyAnalysisConfig.HipOption("표준", 24.2, maxDiff = 24.4),
                BodyAnalysisConfig.HipOption("볼륨", 34.0, maxDiff = 99.0)
            )
        )
        assertEquals(listOf(1), gen.emptyBands().hip)
        val config = configOf(gen)
        for (body in rolls(gen, hip = 1)) {
            assertNotEquals(
                "허리 ${body.waist} 엉덩이 ${body.hip}",
                "표준", BodySilhouetteSpec.axisSummary(measures(body), config).hip
            )
        }
    }

    // ── ⑤ 경고는 막지도 고치지도 않는다 (확정 15장 6번) ────────────────────

    @Test
    fun `교정은 빈 구간을 손대지 않는다 — 경고일 뿐 강제가 아니다`() {
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            hipOptions = listOf(
                BodyAnalysisConfig.HipOption("슬림", 22.0, maxDiff = 24.0),
                BodyAnalysisConfig.HipOption("표준", 24.2, maxDiff = 24.4),
                BodyAnalysisConfig.HipOption("볼륨", 34.0, maxDiff = 99.0)
            )
        )
        val fixed = gen.sanitized()
        assertEquals(24.4, fixed.hipOptions[1].maxDiff, 1e-9)
        assertEquals(listOf(1), fixed.emptyBands().hip)
    }

    // ── ⑥ 판정 초안의 잣대(140~200 통째)가 왜 죽은 잣대인가 ────────────────

    @Test
    fun `키를 140에서 200까지 통째로 열면 좁은 몸통 구간도 채워진다`() {
        // 한 축이 140~200을 전부 낼 수 있는 한 벌 — 중심 170, 흔들림 30(상한).
        val wide = listOf(BodyAnalysisConfig.HeightOption("전부", 170.0, 30.0))
        // 폭이 0.1cm도 안 되는 구간(비율 .0005 ≈ 키 170에서 0.085cm)인데도 채워진다:
        // 끝값 `끝×키`가 140~200을 지나며 여러 정수를 지나기 때문이다.
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            heightOptions = wide,
            torsoOptions = listOf(
                BodyAnalysisConfig.TorsoOption("슬림", .350, 18.5, maxRatio = .3800),
                BodyAnalysisConfig.TorsoOption("표준", .380, 20.5, maxRatio = .3805),
                BodyAnalysisConfig.TorsoOption("소프트", .420, 23.0, maxRatio = 1.0)
            )
        )
        assertEquals(emptyList<Int>(), gen.emptyBands().torso)
        // 같은 구간이 키를 고정하면 곧바로 잡힌다 — 갈라놓는 것은 구간이 아니라 키다.
        // (170 × (.3800, .3805] = (64.6, 64.685] — 정수가 없다. 163이면 62가 들어와 안 잡힌다:
        //  **어느 키를 내는가가 답을 가르므로** 키 축을 빼고는 이 물음에 답할 수 없다.)
        assertFalse(gen.copy(heightOptions = fixedHeight(170.0)).emptyBands().torso.isEmpty())
    }
}
