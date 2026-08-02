package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실루엣 편집기 순수 계층의 계약 (설계 5-4-4).
 *
 * 여기서 지키는 것은 편집기가 **무엇을 건드리지 않는가**다 — 매핑 안 된 파트, 범위 밖
 * 값, 빈 칸으로 시작하는 새 캐릭터. 셋 다 화면에서 처리하면 증명할 수단이 없다.
 */
class BodyEditorModelTest {

    private fun measurements(
        bust: Double? = null, waist: Double? = null, hip: Double? = null, shoulder: Double? = null,
        underbust: Double? = null, height: Double? = null
    ) = BodyMeasurements(
        values = buildMap {
            bust?.let { put(BodySlot.BUST, it) }
            waist?.let { put(BodySlot.WAIST, it) }
            hip?.let { put(BodySlot.HIP, it) }
            shoulder?.let { put(BodySlot.SHOULDER, it) }
            underbust?.let { put(BodySlot.UNDERBUST, it) }
        },
        mode = BodyMeasurements.MappingMode.EXPLICIT,
        partSlots = emptyList(),
        partValues = emptyList(),
        unmappedParts = emptyList(),
        heightCm = height
    )

    // ── 슬라이더 범위 ────────────────────────────────────────────────────

    @Test
    fun `슬라이더 범위는 키에 비례한다`() {
        val short = BodyEditorModel.sliderRange(BodySlot.WAIST, 150.0)
        val tall = BodyEditorModel.sliderRange(BodySlot.WAIST, 180.0)
        assertTrue(short.endInclusive < tall.endInclusive)
        assertTrue(short.start < tall.start)
    }

    @Test
    fun `기준 몸의 값은 모든 부위에서 슬라이더 범위 안에 있다`() {
        val base = BodySilhouetteSpec.BASE
        for ((slot, value) in listOf(
            BodySlot.SHOULDER to base.shoulder,
            BodySlot.BUST to base.bust,
            BodySlot.WAIST to base.waist,
            BodySlot.HIP to base.hip
        )) {
            val range = BodyEditorModel.sliderRange(slot, base.height)
            assertTrue("$slot", value in range)
        }
    }

    @Test
    fun `키를 모르면 기준 몸 키로 범위를 잡는다`() {
        assertEquals(
            BodyEditorModel.sliderRange(BodySlot.HIP, BodySilhouetteSpec.BASE.height),
            BodyEditorModel.sliderRange(BodySlot.HIP, null)
        )
        // 0·음수도 같은 자리로 접힌다 — 빈 칸을 0으로 읽는 경로가 있다.
        assertEquals(
            BodyEditorModel.sliderRange(BodySlot.HIP, null),
            BodyEditorModel.sliderRange(BodySlot.HIP, 0.0)
        )
    }

    // ── 되쓰기 ───────────────────────────────────────────────────────────

    @Test
    fun `매핑되지 않은 파트는 그대로 남는다`() {
        val slots = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP, BodySlot.NONE)
        val texts = listOf("86", "62", "90", "허벅지 52")
        val out = BodyEditorModel.writeBack(slots, texts, mapOf(BodySlot.WAIST to 58.0))
        assertEquals(listOf("86", "58", "90", "허벅지 52"), out)
    }

    @Test
    fun `편집하지 않은 부위의 원문도 그대로 남는다`() {
        val slots = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP)
        val texts = listOf("86cm", "62cm", "90cm")
        val out = BodyEditorModel.writeBack(slots, texts, emptyMap())
        assertEquals(texts, out)
    }

    @Test
    fun `파트 수가 값보다 많아도 짧아도 인덱스가 어긋나지 않는다`() {
        val out = BodyEditorModel.writeBack(
            listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP),
            listOf("86"),
            mapOf(BodySlot.HIP to 92.0)
        )
        assertEquals(listOf("86", "", "92"), out)
    }

    @Test
    fun `정수는 정수로 소수는 한 자리로 적는다`() {
        assertEquals("86", BodyEditorModel.formatValue(86.0))
        assertEquals("86", BodyEditorModel.formatValue(85.98))
        assertEquals("86.5", BodyEditorModel.formatValue(86.47))
    }

    // ── 되쓸 자리 ────────────────────────────────────────────────────────

    @Test
    fun `해석된 매핑이 있으면 그대로 쓴다`() {
        val slots = listOf(BodySlot.NONE, BodySlot.WAIST)
        assertEquals(slots, BodyEditorModel.writableSlots(slots, 2))
    }

    @Test
    fun `매핑이 하나도 없으면 앞 세 칸을 가슴 허리 엉덩이로 본다`() {
        val out = BodyEditorModel.writableSlots(listOf(BodySlot.NONE, BodySlot.NONE, BodySlot.NONE), 3)
        assertEquals(listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP), out)
    }

    @Test
    fun `파트가 아직 없어도 세 자리를 만든다`() {
        // 빈 새 캐릭터 — 해석은 아무것도 못 정하지만 편집기는 열려야 한다.
        val out = BodyEditorModel.writableSlots(emptyList(), 0)
        assertEquals(listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP), out)
    }

    @Test
    fun `파트가 셋보다 많으면 나머지는 매핑 없음으로 둔다`() {
        val out = BodyEditorModel.writableSlots(emptyList(), 5)
        assertEquals(5, out.size)
        assertEquals(listOf(BodySlot.NONE, BodySlot.NONE), out.drop(3))
    }

    @Test
    fun `사용자가 전부 사용 안 함으로 정했으면 위치 폴백이 덮지 않는다`() {
        // 설정에서 직접 정한 배정은 비어 있어도 그대로다 — 부위가 아니라고 말한 칸에
        // 편집기가 가슴·허리·엉덩이를 되쓰면 그 선택이 무의미해진다.
        val allNone = listOf(BodySlot.NONE, BodySlot.NONE, BodySlot.NONE)
        assertEquals(allNone, BodyEditorModel.writableSlots(allNone, 3, explicit = true))
        // 명시가 아니면 종전 그대로 폴백한다.
        assertEquals(
            listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP),
            BodyEditorModel.writableSlots(allNone, 3, explicit = false)
        )
    }

    // ── 초안 ─────────────────────────────────────────────────────────────

    @Test
    fun `값이 하나도 없으면 기준 몸을 띄운다`() {
        val d = BodyEditorModel.draftMeasures(measurements())
        assertEquals(BodySilhouetteSpec.BASE.bust, d.bust, 1e-9)
        assertEquals(BodySilhouetteSpec.BASE.waist, d.waist, 1e-9)
        assertTrue(d.estimated.isEmpty()) // 초안은 전부 만질 수 있어야 한다(핸들 비활성 금지)
        assertTrue(!d.heightKnown)
    }

    @Test
    fun `키만 있으면 기준 몸을 그 키로 늘린다`() {
        val d = BodyEditorModel.draftMeasures(measurements(height = 180.0))
        val f = 180.0 / BodySilhouetteSpec.BASE.height
        assertEquals(BodySilhouetteSpec.BASE.hip * f, d.hip, 1e-9)
        assertTrue(d.heightKnown)
    }

    @Test
    fun `값이 하나라도 있으면 초안이 아니라 해석 결과다`() {
        val d = BodyEditorModel.draftMeasures(measurements(waist = 55.0))
        assertEquals(55.0, d.waist, 1e-9)
        assertTrue(BodySlot.BUST in d.estimated) // 없는 부위는 추정으로 남는다
    }

    @Test
    fun `값 맵은 밑가슴이 없으면 넣지 않는다`() {
        val m = BodySilhouetteSpec.BASE
        assertNull(BodyEditorModel.valuesOf(m)[BodySlot.UNDERBUST])
        assertEquals(m.hip, BodyEditorModel.valuesOf(m)[BodySlot.HIP])
        assertEquals(70.0, BodyEditorModel.valuesOf(m.copy(underbust = 70.0))[BodySlot.UNDERBUST])
    }

    // ── 작품 평균 ────────────────────────────────────────────────────────

    @Test
    fun `작품 평균은 부위별로 값이 있는 캐릭터만 센다`() {
        val avg = BodyEditorModel.peerAverage(
            listOf(
                measurements(bust = 80.0, waist = 60.0, hip = 88.0, height = 160.0),
                measurements(bust = 90.0, waist = 64.0, height = 170.0) // 엉덩이 없음
            )
        )
        assertNotNull(avg)
        assertEquals(85.0, avg!!.bust, 1e-9)
        assertEquals(62.0, avg.waist, 1e-9)
        assertEquals(165.0, avg.height, 1e-9)
        // 한 명분만 있는 엉덩이는 그 한 명의 값이지 0으로 희석되지 않는다.
        assertEquals(88.0, avg.hip, 1e-9)
    }

    @Test
    fun `아무 수치도 없는 캐릭터들만 있으면 평균이 없다`() {
        assertNull(BodyEditorModel.peerAverage(emptyList()))
        assertNull(BodyEditorModel.peerAverage(listOf(measurements(height = 160.0), measurements())))
    }

    @Test
    fun `평균에 키가 하나도 없으면 기준 키로 그린다`() {
        val avg = BodyEditorModel.peerAverage(listOf(measurements(bust = 84.0, waist = 60.0, hip = 90.0)))
        assertNotNull(avg)
        assertEquals(BodySilhouetteSpec.BASE.height, avg!!.height, 1e-9)
        assertTrue(!avg.heightKnown)
    }
}
