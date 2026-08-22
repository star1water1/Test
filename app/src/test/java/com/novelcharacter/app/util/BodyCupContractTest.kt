package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 컵 규약이 **한 벌인가** — 그림과 분석이 같은 밑가슴·같은 표에서 컵을 낸다(B-92).
 *
 * 이 계약이 깨지면 읽기 카드가 자기모순이 된다: 같은 카드 안에서 실루엣은 한 컵으로
 * 그려지고 글자는 다른 컵을 말한다. 종전에는 그림이 전용 상수 6을, 분석이 설정
 * 기본값 0을 써서 **기본 설정에서 두 컵 넘게 갈렸다.**
 */
class BodyCupContractTest {

    private fun measurements(
        bust: Double = 88.0,
        waist: Double = 60.0,
        hip: Double = 90.0,
        height: Double? = 162.0,
        underbust: Double? = null
    ) = BodyMeasurements(
        values = buildMap {
            put(BodySlot.BUST, bust)
            put(BodySlot.WAIST, waist)
            put(BodySlot.HIP, hip)
            underbust?.let { put(BodySlot.UNDERBUST, it) }
        },
        mode = BodyMeasurements.MappingMode.EXPLICIT,
        partSlots = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP),
        partValues = listOf("$bust", "$waist", "$hip"),
        unmappedParts = emptyList(),
        heightCm = height
    )

    /**
     * 분석 엔진이 내는 컵 글자 — **앱 화면과 같은 인자로 부른다.**
     * 종전에는 실측 밑가슴을 안 넘겨서, 이 헬퍼가 근사만 재고 두 계층의 갈림을 못 봤다.
     */
    private fun analysisCup(m: BodyMeasurements, config: BodyAnalysisConfig): String? =
        BodyAnalysisHelper().analyze(
            m.bust!!, m.waist!!, m.hip!!, m.heightCm, null, config,
            measuredUnderbust = m.effectiveUnderbust(config)
        ).cupSize

    /** 그림(3축 요약)이 내는 컵 글자. */
    private fun figureCup(m: BodyMeasurements, config: BodyAnalysisConfig): String {
        val measures = BodySilhouetteSpec.measuresFrom(m, config.ribOffset)!!
        return BodySilhouetteSpec.axisSummary(measures, config).cup
    }

    // ── 기본 설정에서 두 계층이 같은 글자를 낸다 ──────────────────────────────

    @Test
    fun `기본 설정에서 그림과 분석의 컵이 같다`() {
        val config = BodyAnalysisConfig.DEFAULT
        for (bust in listOf(76.0, 82.0, 88.0, 95.0, 104.0, 120.0)) {
            val m = measurements(bust = bust)
            assertEquals(
                "가슴 ${bust}에서 갈렸다",
                analysisCup(m, config), figureCup(m, config)
            )
        }
    }

    @Test
    fun `보정값을 바꿔도 두 계층이 함께 움직인다`() {
        for (offset in listOf(0.0, 3.0, 6.0, 10.0)) {
            val config = BodyAnalysisConfig.DEFAULT.copy(ribOffset = offset)
            val m = measurements()
            assertEquals(
                "보정 ${offset}에서 갈렸다",
                analysisCup(m, config), figureCup(m, config)
            )
        }
    }

    /**
     * **이 시험은 이름이 약속한 것을 재지 않아 깨진 상태로 초록이었다** — 그림끼리만
     * 비교하고 분석 계층을 한 번도 부르지 않았다. 그동안 분석 엔진에는 실측 밑가슴을 받을
     * 인자 자체가 없었고, 같은 카드가 같은 열에 컵을 두 개(그림 `C` · 분석 `F`) 말했다.
     */
    @Test
    fun `실측 밑가슴이 있으면 양쪽 모두 그것을 쓴다`() {
        val config = BodyAnalysisConfig.DEFAULT
        // 허리 60 + 보정 6 = 66이 근사인데, 실측 74를 넣으면 컵차가 8cm 줄어든다.
        val approx = measurements()
        val measured = measurements(underbust = 74.0)
        // ⓐ 실측이 실제로 글자를 바꾼다 — 두 계층 각각에서.
        assertNotEquals(figureCup(approx, config), figureCup(measured, config))
        assertNotEquals(analysisCup(approx, config), analysisCup(measured, config))
        // ⓑ 그리고 두 계층이 같은 글자를 낸다(이것이 이 파일의 계약이다).
        assertEquals(analysisCup(measured, config), figureCup(measured, config))
        assertEquals(
            BodySilhouetteSpec.figureUnderbust(BodySilhouetteSpec.measuresFrom(measured, config.ribOffset)!!),
            74.0, 1e-9
        )
        assertEquals(measured.effectiveUnderbust(config)!!, 74.0, 1e-9)
    }

    /**
     * **컵 표를 그림 상한(40cm) 위로 넓힌 사용자** — 기본 표는 끝 칸이 열려 있어(999) 두
     * 계층이 우연히 같은 글자로 뭉개졌고, 그래서 이 축을 지나는 시험이 하나도 없었다.
     */
    @Test
    fun `컵 표가 그림 상한 위로 넓어져도 두 계층이 같다`() {
        val custom = listOf(
            BodyAnalysisConfig.CupMappingEntry(30.0, "I"),
            BodyAnalysisConfig.CupMappingEntry(40.0, "J"),
            BodyAnalysisConfig.CupMappingEntry(55.0, "K"),
            BodyAnalysisConfig.CupMappingEntry(999.0, "L")
        )
        val config = BodyAnalysisConfig.DEFAULT.copy(cupMapping = custom)
        // 가슴 120 · 허리 60 · 보정 6 → 컵차 54. 그림 상한에 걸리면 40으로 잘려 "J"가 된다.
        val m = measurements(bust = 120.0)
        assertEquals("K", analysisCup(m, config))
        assertEquals("상한이 글자에 걸렸다", analysisCup(m, config), figureCup(m, config))
    }

    /** 컵 줄 아래 표시하는 '보정 UB'도 실측을 말해야 한다 — 74를 적은 사용자에게 66을 보였다. */
    @Test
    fun `보정 밑가슴 표시가 실측을 말한다`() {
        val config = BodyAnalysisConfig.DEFAULT
        val measured = measurements(underbust = 74.0)
        val result = BodyAnalysisHelper().analyze(
            measured.bust!!, measured.waist!!, measured.hip!!, measured.heightCm, null, config,
            measuredUnderbust = measured.effectiveUnderbust(config)
        )
        assertEquals(74.0, result.adjustedUnderbust!!, 1e-9)
    }

    /** 밑가슴을 연결하지 않은 사용자의 결과는 한 글자도 바뀌지 않는다. */
    @Test
    fun `실측이 없으면 종전 근사 그대로다`() {
        val config = BodyAnalysisConfig.DEFAULT
        val m = measurements()
        val legacy = BodyAnalysisHelper().analyze(
            m.bust!!, m.waist!!, m.hip!!, m.heightCm, null, config
        )
        assertEquals(legacy.cupSize, analysisCup(m, config))
        assertEquals(m.waist!! + config.ribOffset, legacy.adjustedUnderbust!!, 1e-9)
    }

    @Test
    fun `사용자가 고친 컵 표를 그림도 따른다`() {
        // 한 칸을 통째로 넓힌 표 — 옛 그림 사다리는 이 표를 몰라 옛 글자를 계속 말했다.
        val custom = listOf(
            BodyAnalysisConfig.CupMappingEntry(20.0, "작음"),
            BodyAnalysisConfig.CupMappingEntry(999.0, "큼")
        )
        val config = BodyAnalysisConfig.DEFAULT.copy(cupMapping = custom)
        val m = measurements(bust = 80.0)   // 컵차 = 80 - (60+6) = 14 → "작음"
        assertEquals("작음", figureCup(m, config))
        assertEquals(analysisCup(m, config), figureCup(m, config))
    }

    // ── 기본값 자체 ─────────────────────────────────────────────────────────

    @Test
    fun `그림 상수와 설정 기본값이 같은 값이다`() {
        assertEquals(
            BodyAnalysisConfig.DEFAULT_RIB_OFFSET,
            BodySilhouetteSpec.FIGURE_RIB_OFFSET_CM,
            1e-9
        )
        assertEquals(BodyAnalysisConfig.DEFAULT_RIB_OFFSET, BodyAnalysisConfig.DEFAULT.ribOffset, 1e-9)
    }

    @Test
    fun `보정값이 없는 config는 기본값으로 읽힌다`() {
        // 종전 데이터에는 이 키가 없다(기본값이면 적지 않는 규칙이라 0은 저장된 적이 없다).
        val parsed = BodyAnalysisConfig.fromConfig("""{"bodyAnalysis":{}}""")
        assertEquals(BodyAnalysisConfig.DEFAULT_RIB_OFFSET, parsed.ribOffset, 1e-9)
    }

    @Test
    fun `근사를 끈 0은 명시 저장되어 왕복한다`() {
        // 기준이 6으로 옮겨졌으므로 0은 더 이상 '기본값과 같아서 생략'되지 않는다 —
        // 근사를 끄겠다는 선택이 저장되지 않으면 다음에 열 때 조용히 6으로 돌아간다.
        val json = BodyAnalysisConfig.applyToConfig("{}",BodyAnalysisConfig.DEFAULT.copy(ribOffset = 0.0))
        assertTrue("0이 저장되지 않았다: $json", json.contains("ribOffset"))
        assertEquals(0.0, BodyAnalysisConfig.fromConfig(json).ribOffset, 1e-9)
    }

    @Test
    fun `기본값이 아닌 보정값도 왕복한다`() {
        val json = BodyAnalysisConfig.applyToConfig("{}",BodyAnalysisConfig.DEFAULT.copy(ribOffset = 3.5))
        assertEquals(3.5, BodyAnalysisConfig.fromConfig(json).ribOffset, 1e-9)
    }

    // ── 표시 계층의 장르어 (판정 P8) ─────────────────────────────────────────

    @Test
    fun `BMI 장르어는 3축 몸통 어휘와 같은 말을 쓴다`() {
        assertEquals("슬림", BodyAnalysisHelper.bmiToneLabel(17.0))
        assertEquals("표준", BodyAnalysisHelper.bmiToneLabel(21.0))
        assertEquals("소프트", BodyAnalysisHelper.bmiToneLabel(27.0))
        assertEquals("글래머", BodyAnalysisHelper.bmiToneLabel(33.0))
        // 3축 요약의 몸통 축이 쓰는 낱말 안에 있어야 한다(두 자리가 다른 말을 하지 않게).
        val torsoWords = setOf("슬림", "표준", "소프트", "글래머")
        for (bmi in listOf(15.0, 18.4, 18.5, 24.9, 25.0, 29.9, 30.0, 45.0)) {
            assertTrue(BodyAnalysisHelper.bmiToneLabel(bmi) in torsoWords)
        }
    }

    @Test
    fun `잘록함은 WHR이 낮을수록 깊다`() {
        assertEquals("깊음", BodyAnalysisHelper.waistlineLabel(0.65))
        assertEquals("뚜렷함", BodyAnalysisHelper.waistlineLabel(0.75))
        assertEquals("완만함", BodyAnalysisHelper.waistlineLabel(0.83))
        assertEquals("일자", BodyAnalysisHelper.waistlineLabel(0.95))
    }
}
