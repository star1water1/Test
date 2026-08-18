package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.util.BodySilhouetteSpec.Measures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실루엣 편집기 회전 보존의 왕복 (B-201 · 확정 15장 5번).
 *
 * **왜 이 시험이 방어선인가:** 회전은 실기기에서만 보이고, 담는 자리(`onSaveInstanceState`)와
 * 푸는 자리는 화면 계층이라 순수 JVM도 프로브(화면 계층 제외)도 원리적으로 못 본다. 그래서
 * 왕복 자체를 [BodyEditorState]로 내리고 여기서 잠근다 — 시트에 남은 것은 한 줄씩의 호출뿐이라
 * *담는 것을 빠뜨렸는가*가 이 파일에서 드러난다.
 */
class BodyEditorStateTest {

    private fun measures(
        height: Double = 165.0,
        shoulder: Double = 38.0,
        bust: Double = 86.0,
        waist: Double = 62.0,
        hip: Double = 90.0,
        underbust: Double? = 72.5,
        estimated: Set<BodySlot> = emptySet(),
        heightKnown: Boolean = true,
        ribOffset: Double = 10.0
    ) = Measures(
        height = height, shoulder = shoulder, bust = bust, waist = waist, hip = hip,
        underbust = underbust, estimated = estimated, heightKnown = heightKnown, ribOffset = ribOffset
    )

    private fun full() = BodyEditorState(
        fieldId = 77L,
        initial = BodyMeasurements(
            values = mapOf(BodySlot.BUST to 84.0, BodySlot.WAIST to 60.0),
            mode = BodyMeasurements.MappingMode.EXPLICIT,
            partSlots = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.NONE),
            partValues = listOf("84", "60", "허벅지 52"),
            unmappedParts = listOf("허벅지"),
            heightCm = 164.0,
            weightKg = 49.5
        ),
        partValues = listOf("84", "60", "허벅지 52"),
        writableSlots = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.NONE),
        hasHeightField = true,
        hasWeightField = true,
        positionalFallback = false,
        noWritableSlot = false,
        current = measures(),
        weightKg = 52.5,
        previous = measures(bust = 80.0) to 48.0,
        touched = setOf(BodySlot.BUST, BodySlot.HIP),
        cupMode = true,
        overlayOn = true,
        generatorOpen = true,
        sideFacing = true,
        axisHeight = 3,
        axisTorso = 0,
        axisBust = 2,
        axisHip = 4,
        cupSizeIndex = 5,
        relativeHeightIndex = 1,
        relativeVolumeIndex = 4,
        baseCharacterId = 909L
    )

    // ── 전 칸 왕복 ──────────────────────────────────────────────────────────

    /**
     * **칸을 더하고 [BodyEditorState.encode]에 넣기를 잊으면 여기서 죽는다.**
     * data class 동등성으로 통째로 견주므로 새 프로퍼티가 자동으로 이 그물에 들어온다 —
     * 시험을 고치지 않아도 다음 사람의 누락이 잡힌다는 것이 이 단언의 값이다(R-41).
     */
    @Test
    fun `모든 칸이 왕복을 넘는다`() {
        val state = full()
        assertEquals(state, BodyEditorState.decode(state.encode()))
    }

    @Test
    fun `null 자리도 왕복을 넘는다`() {
        val state = full().copy(
            weightKg = null,
            previous = null,
            baseCharacterId = null,
            touched = emptySet(),
            initial = BodyMeasurements(
                values = emptyMap(), mode = BodyMeasurements.MappingMode.NONE,
                partSlots = emptyList(), partValues = emptyList(), unmappedParts = emptyList()
            ),
            partValues = emptyList(),
            writableSlots = emptyList()
        )
        assertEquals(state, BodyEditorState.decode(state.encode()))
    }

    /** 굴린 뒤 되돌릴 자리는 있는데 체중만 없는 갈래(키·체중 필드가 없는 폼). */
    @Test
    fun `직전으로 한 단계는 체중이 비어도 살아남는다`() {
        val state = full().copy(previous = measures(hip = 95.0) to null)
        val back = BodyEditorState.decode(state.encode())
        assertNotNull(back!!.previous)
        assertEquals(95.0, back.previous!!.first.hip, 1e-9)
        assertNull(back.previous!!.second)
    }

    // ── 이 판이 존재하는 이유: 만든 값의 집합 ───────────────────────────────

    /**
     * `touched`가 왕복을 못 넘기면 **회전 뒤 [적용]이 방금 채운 칸을 말없이 뺀다** —
     * 화면에는 수치가 보이는데 저장이 안 되는 모양이라 사용자가 알아채지도 못한다.
     * 그 자리를 되쓰기 계약과 함께 잠근다.
     */
    @Test
    fun `이 자리에서 만든 값이 회전 뒤에도 되쓰기 대상으로 남는다`() {
        val state = full().copy(
            initial = BodyMeasurements(
                values = mapOf(BodySlot.BUST to 84.0),
                mode = BodyMeasurements.MappingMode.EXPLICIT,
                partSlots = listOf(BodySlot.BUST, BodySlot.HIP),
                partValues = listOf("84", ""),
                unmappedParts = emptyList()
            ),
            partValues = listOf("84", ""),
            writableSlots = listOf(BodySlot.BUST, BodySlot.HIP),
            touched = setOf(BodySlot.HIP)
        )
        val back = BodyEditorState.decode(state.encode())!!

        // 시트의 applyAndDismiss와 같은 식 — 원래 있던 값과 이 자리에서 만든 값만 되쓴다.
        val writable = BodyEditorModel.valuesOf(back.current)
            .filterKeys { it in back.touched || back.initial.values.containsKey(it) }
        val parts = BodyEditorModel.writeBack(back.writableSlots, back.partValues, writable)

        assertEquals(BodyEditorModel.formatValue(back.current.hip), parts[1])
        assertEquals(BodyEditorModel.formatValue(back.current.bust), parts[0])
    }

    /** 반대 축 — 만지지 않은 빈 칸은 회전을 넘어도 여전히 비어 있어야 한다(없는 데이터를 만들지 않는다). */
    @Test
    fun `만지지 않은 빈 칸은 회전 뒤에도 채워지지 않는다`() {
        val state = full().copy(
            initial = BodyMeasurements(
                values = mapOf(BodySlot.BUST to 84.0),
                mode = BodyMeasurements.MappingMode.EXPLICIT,
                partSlots = listOf(BodySlot.BUST, BodySlot.HIP),
                partValues = listOf("84", ""),
                unmappedParts = emptyList()
            ),
            partValues = listOf("84", ""),
            writableSlots = listOf(BodySlot.BUST, BodySlot.HIP),
            touched = emptySet()
        )
        val back = BodyEditorState.decode(state.encode())!!
        val writable = BodyEditorModel.valuesOf(back.current)
            .filterKeys { it in back.touched || back.initial.values.containsKey(it) }
        val parts = BodyEditorModel.writeBack(back.writableSlots, back.partValues, writable)

        assertEquals("", parts[1])
    }

    /** 되쓰기의 바탕이 되는 원문 — 실루엣이 모르는 칸은 회전을 넘어도 그대로다. */
    @Test
    fun `실루엣이 모르는 파트 원문은 회전을 넘어 보존된다`() {
        val back = BodyEditorState.decode(full().encode())!!
        val writable = BodyEditorModel.valuesOf(back.current)
            .filterKeys { it in back.touched || back.initial.values.containsKey(it) }
        val parts = BodyEditorModel.writeBack(back.writableSlots, back.partValues, writable)
        assertEquals("허벅지 52", parts[2])
    }

    // ── 자리가 뜻을 가지는 목록 ─────────────────────────────────────────────

    /**
     * 되쓸 자리 목록은 **파트 인덱스 순**이라 한 칸이 밀리면 옆 칸에 적는다.
     * 그래서 모르는 이름은 버리지 않고 [BodySlot.NONE]으로 남긴다.
     */
    @Test
    fun `모르는 부위 이름이 섞여도 되쓸 자리의 순서가 밀리지 않는다`() {
        val raw = full().encode().replace("\"WAIST\"", "\"WHATEVER\"")
        val back = BodyEditorState.decode(raw)!!
        assertEquals(3, back.writableSlots.size)
        assertEquals(BodySlot.BUST, back.writableSlots[0])
        assertEquals(BodySlot.NONE, back.writableSlots[1])
        assertEquals(BodySlot.NONE, back.writableSlots[2])
    }

    // ── 못 읽는 것의 처분 ───────────────────────────────────────────────────

    @Test
    fun `빈 문자열과 null은 복원하지 않는다`() {
        assertNull(BodyEditorState.decode(null))
        assertNull(BodyEditorState.decode(""))
        assertNull(BodyEditorState.decode("   "))
    }

    /**
     * 옛 판이 담은 것을 새 판이 읽는 자리라 모양이 어긋날 수 있다 —
     * **그때 죽으면 편집기가 아니라 화면이 통째로 죽는다.** 못 읽으면 null이고 예외는 없다.
     */
    @Test
    fun `깨진 문자열은 예외 대신 null이다`() {
        assertNull(BodyEditorState.decode("{"))
        assertNull(BodyEditorState.decode("깨진 것"))
        assertNull(BodyEditorState.decode("[1,2,3]"))
        assertNull(BodyEditorState.decode("{\"field\":1}"))
    }

    /** 그림이 설 수 없는 절반짜리 수치는 부분 복원하지 않는다 — 다섯 축이 전부 있어야 한다. */
    @Test
    fun `수치 축이 하나라도 없으면 복원하지 않는다`() {
        val raw = full().encode().replace("\"p\":90", "\"gone\":90")
        assertNull(BodyEditorState.decode(raw))
    }

    // ── 기본값 ──────────────────────────────────────────────────────────────

    /**
     * 옛 판이 담은 번들에는 없는 칸이 있을 수 있다. 그때 축은 **시트가 처음 세우는 자리**와
     * 같아야 한다 — 다른 값을 넣으면 복원이 사용자가 고른 적 없는 축을 고른 것이 된다.
     */
    @Test
    fun `없는 칸은 시트가 처음 세우는 자리로 돌아간다`() {
        val minimal = """{"cur":{"h":165.0,"s":38.0,"b":86.0,"w":62.0,"p":90.0}}"""
        val back = BodyEditorState.decode(minimal)!!
        assertEquals(BodyEditorState.DEFAULT_AXIS, back.axisHeight)
        assertEquals(BodyEditorState.DEFAULT_AXIS, back.axisTorso)
        assertEquals(BodyEditorState.DEFAULT_AXIS, back.axisBust)
        assertEquals(BodyEditorState.DEFAULT_AXIS, back.axisHip)
        assertEquals(BodyEditorState.NO_SELECTION, back.cupSizeIndex)
        assertEquals(BodyEditorState.DEFAULT_RELATIVE, back.relativeHeightIndex)
        assertEquals(BodyEditorState.DEFAULT_RELATIVE, back.relativeVolumeIndex)
        assertNull(back.baseCharacterId)
        assertTrue(back.touched.isEmpty())
        assertTrue(back.initial.values.isEmpty())
    }

    /** 추정 표시와 키 미입력은 그림의 성질이라 함께 넘어간다(점선·비활성 핸들). */
    @Test
    fun `추정 부위와 키 미입력 표시가 왕복을 넘는다`() {
        val state = full().copy(
            current = measures(
                estimated = setOf(BodySlot.SHOULDER, BodySlot.HIP),
                heightKnown = false,
                underbust = null
            )
        )
        val back = BodyEditorState.decode(state.encode())!!
        assertEquals(setOf(BodySlot.SHOULDER, BodySlot.HIP), back.current.estimated)
        assertEquals(false, back.current.heightKnown)
        assertNull(back.current.underbust)
    }

    /** 컵 계산이 쓰는 보정값 — 설정에서 온 것이라 기본값으로 되돌아가면 컵차가 갈린다. */
    @Test
    fun `밑가슴 보정값이 왕복을 넘는다`() {
        val state = full().copy(current = measures(ribOffset = 13.5))
        assertEquals(13.5, BodyEditorState.decode(state.encode())!!.current.ribOffset, 1e-9)
    }
}
