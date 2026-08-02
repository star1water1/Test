package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 체형 수치 해석의 계약 (설계 3장).
 *
 * 가장 중요한 사실 하나: **부위는 파트 라벨이 아니라 슬롯이 든다.** 종전에는 읽기 화면이
 * "구분자로 쪼개 앞 3개가 B/W/H"를 인라인으로 갖고 있어, 라벨을 다르게 지은 필드나
 * 파트 순서가 다른 필드는 조용히 분석에서 빠졌다.
 */
class BodyMeasurementsTest {

    private fun field(config: String) = FieldDefinition(
        universeId = 1, key = "body", name = "신체 사이즈", type = "BODY_SIZE", config = config
    )

    private fun structured(vararg labels: String, separator: String = "-"): String {
        val parts = labels.joinToString(",") { """{"label":"$it","suffix":"cm","inputType":"number"}""" }
        return """{"structuredInput":{"enabled":true,"separator":"$separator","parts":[$parts]}}"""
    }

    // ── 사다리 1단: 명시 매핑 ──────────────────────────────────────────────

    @Test
    fun `명시 매핑은 라벨과 순서를 모두 이긴다`() {
        // 라벨은 B-W-H인데 사용자가 뒤집어 매핑했다 — 설정이 이겨야 한다.
        val config = BodyAnalysisConfig.DEFAULT.copy(
            partSlots = listOf(BodySlot.HIP, BodySlot.WAIST, BodySlot.BUST)
        )
        val m = BodyMeasurements.resolve(
            field(structured("B", "W", "H")), "90-60-85", config = config
        )
        assertEquals(BodyMeasurements.MappingMode.EXPLICIT, m.mode)
        assertEquals(90.0, m.hip!!, 1e-9)
        assertEquals(60.0, m.waist!!, 1e-9)
        assertEquals(85.0, m.bust!!, 1e-9)
    }

    @Test
    fun `명시 매핑이 파트보다 짧아도 남은 파트는 미매핑으로 남는다`() {
        val config = BodyAnalysisConfig.DEFAULT.copy(partSlots = listOf(BodySlot.BUST))
        val m = BodyMeasurements.resolve(
            field(structured("가슴", "허리", "엉덩이")), "88-60-90", config = config
        )
        assertEquals(BodyMeasurements.MappingMode.EXPLICIT, m.mode)
        assertEquals(88.0, m.bust!!, 1e-9)
        assertNull("설정이 모르는 파트를 라벨로 몰래 메우면 명시의 뜻이 깨진다", m.waist)
        assertEquals(listOf("허리", "엉덩이"), m.unmappedParts)
    }

    // ── 사다리 2단: 라벨 추론 ─────────────────────────────────────────────

    @Test
    fun `라벨로 부위를 추론한다`() {
        val m = BodyMeasurements.resolve(field(structured("B", "W", "H")), "88-60-90")
        assertEquals(BodyMeasurements.MappingMode.INFERRED, m.mode)
        assertEquals(88.0, m.bust!!, 1e-9)
        assertEquals(60.0, m.waist!!, 1e-9)
        assertEquals(90.0, m.hip!!, 1e-9)
    }

    @Test
    fun `추론은 대소문자와 공백과 한글 표기를 가리지 않는다`() {
        val m = BodyMeasurements.resolve(
            field(structured(" 가슴 ", "Waist", "HIP", "어깨 너비")), "88-60-90-38"
        )
        assertEquals(BodyMeasurements.MappingMode.INFERRED, m.mode)
        assertEquals(88.0, m.bust!!, 1e-9)
        assertEquals(60.0, m.waist!!, 1e-9)
        assertEquals(90.0, m.hip!!, 1e-9)
        assertEquals(38.0, m.shoulder!!, 1e-9)
    }

    @Test
    fun `파트 순서가 달라도 라벨이 이긴다`() {
        // 순서가 H-B-W인 필드 — 위치 폴백이었다면 전부 틀리게 읽었을 자리.
        val m = BodyMeasurements.resolve(field(structured("힙", "가슴", "허리")), "90-88-60")
        assertEquals(90.0, m.hip!!, 1e-9)
        assertEquals(88.0, m.bust!!, 1e-9)
        assertEquals(60.0, m.waist!!, 1e-9)
    }

    @Test
    fun `밑가슴 라벨은 선택 슬롯으로 잡힌다`() {
        val m = BodyMeasurements.resolve(
            field(structured("가슴", "밑가슴", "허리", "엉덩이")), "88-72-60-90"
        )
        assertEquals(72.0, m.underbust!!, 1e-9)
        // 실측이 있으면 근사(허리+보정)를 쓰지 않는다.
        assertEquals(72.0, m.effectiveUnderbust(BodyAnalysisConfig.DEFAULT)!!, 1e-9)
    }

    @Test
    fun `밑가슴이 없으면 허리에 보정을 더한 근사를 쓴다`() {
        val m = BodyMeasurements.resolve(field(structured("B", "W", "H")), "88-60-90")
        val config = BodyAnalysisConfig.DEFAULT.copy(ribOffset = 6.0)
        assertEquals(66.0, m.effectiveUnderbust(config)!!, 1e-9)
    }

    // ── 사다리 3단: 위치 폴백 (현행 동작 보존) ────────────────────────────

    @Test
    fun `라벨을 못 알아보면 앞 세 숫자를 B W H로 본다`() {
        val m = BodyMeasurements.resolve(field(structured("①", "②", "③")), "88-60-90")
        assertEquals(BodyMeasurements.MappingMode.POSITIONAL, m.mode)
        assertEquals(88.0, m.bust!!, 1e-9)
        assertEquals(60.0, m.waist!!, 1e-9)
        assertEquals(90.0, m.hip!!, 1e-9)
    }

    @Test
    fun `구조화 설정이 없는 값도 종전 구분자로 읽힌다`() {
        // 기존 데이터 보존 — 인라인 파서가 쓰던 구분자(-, /, 공백)가 그대로 통해야 한다.
        for (raw in listOf("88-60-90", "88/60/90", "88 60 90")) {
            val m = BodyMeasurements.resolve(field("{}"), raw)
            assertEquals("'$raw' 해석", BodyMeasurements.MappingMode.POSITIONAL, m.mode)
            assertEquals(88.0, m.bust!!, 1e-9)
            assertEquals(60.0, m.waist!!, 1e-9)
            assertEquals(90.0, m.hip!!, 1e-9)
        }
    }

    @Test
    fun `숫자가 세 개 미만이면 위치 폴백을 쓰지 않는다`() {
        val m = BodyMeasurements.resolve(field("{}"), "88-60")
        assertEquals(BodyMeasurements.MappingMode.NONE, m.mode)
        assertTrue(m.values.isEmpty())
        assertFalse("무엇이 안 읽혔는지 말없이 버리지 않는다", m.unmappedParts.isEmpty())
    }

    @Test
    fun `빈 값은 아무것도 만들지 않는다`() {
        val m = BodyMeasurements.resolve(field(structured("B", "W", "H")), "")
        assertEquals(BodyMeasurements.MappingMode.NONE, m.mode)
        assertTrue(m.values.isEmpty())
        assertTrue(m.unmappedParts.isEmpty())
    }

    // ── 값 읽기 ───────────────────────────────────────────────────────────

    @Test
    fun `단위 접미사와 소수점을 견딘다`() {
        assertEquals(88.5, BodyMeasurements.parseNumber("88.5cm")!!, 1e-9)
        assertEquals(165.0, BodyMeasurements.parseNumber(" 165 cm ")!!, 1e-9)
        assertEquals(52.0, BodyMeasurements.parseNumber("52kg")!!, 1e-9)
        assertNull(BodyMeasurements.parseNumber("모름"))
        assertNull(BodyMeasurements.parseNumber(null))
        assertNull(BodyMeasurements.parseNumber(""))
    }

    @Test
    fun `분석 헬퍼의 수치 해석은 같은 규칙을 쓴다`() {
        // 규칙을 두 벌로 두면 갈린다 — 헬퍼는 이 객체에 위임한다.
        for (raw in listOf("88.5cm", " 165 cm ", "모름", "")) {
            assertEquals(
                "'$raw'",
                BodyMeasurements.parseNumber(raw),
                BodyAnalysisHelper.parseNumericFromText(raw)
            )
        }
    }

    @Test
    fun `키와 체중을 함께 읽는다`() {
        val m = BodyMeasurements.resolve(
            field(structured("B", "W", "H")), "88-60-90",
            heightText = "165cm", weightText = "52kg"
        )
        assertEquals(165.0, m.heightCm!!, 1e-9)
        assertEquals(52.0, m.weightKg!!, 1e-9)
    }

    @Test
    fun `세 값이 다 있어야 분석 엔진 계약을 만족한다`() {
        assertTrue(BodyMeasurements.resolve(field(structured("B", "W", "H")), "88-60-90").hasCoreThree)
        assertFalse(BodyMeasurements.resolve(field(structured("가슴", "허리")), "88-60").hasCoreThree)
    }

    @Test
    fun `같은 부위가 두 번 배정되면 앞의 것을 남긴다`() {
        // 뒤엣것이 덮으면 파트 순서에 따라 결과가 달라져 재현이 안 된다.
        val config = BodyAnalysisConfig.DEFAULT.copy(
            partSlots = listOf(BodySlot.BUST, BodySlot.BUST, BodySlot.HIP)
        )
        val m = BodyMeasurements.resolve(field(structured("a", "b", "c")), "88-95-90", config = config)
        assertEquals(88.0, m.bust!!, 1e-9)
    }

    // ── config 왕복 ───────────────────────────────────────────────────────

    @Test
    fun `파트 매핑은 config JSON을 왕복한다`() {
        val slots = listOf(BodySlot.BUST, BodySlot.UNDERBUST, BodySlot.WAIST, BodySlot.HIP)
        val config = BodyAnalysisConfig.DEFAULT.copy(partSlots = slots)
        val json = BodyAnalysisConfig.applyToConfig("{}", config)
        assertEquals(slots, BodyAnalysisConfig.fromConfig(json).partSlots)
    }

    @Test
    fun `매핑을 안 했으면 config에 굽지 않는다`() {
        // 기본값(추론과 같음)을 저장하면 기존 필드 정의의 JSON이 이유 없이 불어난다.
        val json = BodyAnalysisConfig.applyToConfig("{}", BodyAnalysisConfig.DEFAULT)
        assertFalse("partSlots", json.contains("partSlots"))
        assertTrue(BodyAnalysisConfig.fromConfig(json).partSlots.isEmpty())
    }

    @Test
    fun `모르는 슬롯 이름은 자리를 지키며 NONE이 된다`() {
        // 버리면 뒤 파트의 인덱스가 밀려 매핑 전체가 어긋난다.
        val json = """{"bodyAnalysis":{"partSlots":["BUST","알수없음","HIP"]}}"""
        val parsed = BodyAnalysisConfig.fromConfig(json)
        assertEquals(listOf(BodySlot.BUST, BodySlot.NONE, BodySlot.HIP), parsed.partSlots)

        val m = BodyMeasurements.resolve(field(structured("a", "b", "c")), "88-60-90", config = parsed)
        assertEquals(88.0, m.bust!!, 1e-9)
        assertEquals(90.0, m.hip!!, 1e-9)
        assertNull(m.waist)
    }

    @Test
    fun `기존 config의 다른 설정은 매핑을 넣어도 살아남는다`() {
        val base = BodyAnalysisConfig.DEFAULT.copy(ribOffset = 6.0, defaultBodyType = "표준")
        val json = BodyAnalysisConfig.applyToConfig(
            """{"separator":"-"}""", base.copy(partSlots = listOf(BodySlot.BUST))
        )
        val parsed = BodyAnalysisConfig.fromConfig(json)
        assertEquals(6.0, parsed.ribOffset, 1e-9)
        assertEquals("표준", parsed.defaultBodyType)
        assertEquals(listOf(BodySlot.BUST), parsed.partSlots)
        assertTrue("다른 타입 설정도 보존돼야 한다", json.contains("separator"))
    }

    @Test
    fun `파트 라벨 없이 슬롯만으로도 해석된다`() {
        // 편집기처럼 필드 정의 없이 파트만 든 자리.
        val m = BodyMeasurements.resolveParts(
            labels = listOf("가슴", "허리", "엉덩이"),
            texts = listOf("88", "60", "90")
        )
        assertEquals(BodyMeasurements.MappingMode.INFERRED, m.mode)
        assertNotNull(m.bust)
    }
}
