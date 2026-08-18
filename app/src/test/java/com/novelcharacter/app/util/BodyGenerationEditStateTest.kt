package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.GenerationPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🎲 축·프리셋 편집 창이 회전을 넘겨 지키는 것의 계약 (B-260).
 *
 * **이 파일이 존재하는 이유가 곧 담는 벌을 순수 계층에 둔 이유다** — 창 안에서 `Bundle`에
 * 칸을 넣고 빼면 왕복이 맞는지 증명할 수단이 없다(회전은 실기기에서만 보인다).
 * 여기 내려오니 `data class` 동등성 하나로 **새 칸의 누락까지** 자동으로 잡힌다.
 */
class BodyGenerationEditStateTest {

    private val gen = BodyAnalysisConfig.DEFAULT_GENERATION

    /** 칸마다 서로 다른 글자를 넣는다 — 한 칸이라도 왕복에서 빠지면 동등성이 깨진다. */
    private fun filled(): BodyGenerationEditState {
        var n = 0
        fun next() = "v${n++}"
        val base = BodyGenerationEditState.initial(gen)
        fun rows(list: List<BodyGenerationEditState.RowText>) =
            list.map { r -> BodyGenerationEditState.RowText(next(), r.numbers.map { next() }) }
        return base.copy(
            heightRows = rows(base.heightRows),
            torsoRows = rows(base.torsoRows),
            bustRows = rows(base.bustRows),
            hipRows = rows(base.hipRows),
            presetRows = base.presetRows.mapIndexed { i, _ ->
                BodyGenerationEditState.PresetText(next(), i % 2, 0, i % 2)
            }
        )
    }

    @Test
    fun `전 칸 왕복 — 담았다 풀면 같은 것이다`() {
        val state = filled()
        assertEquals(state, BodyGenerationEditState.decode(state.encode()))
    }

    @Test
    fun `적던 중인 글자를 눌러 담지 않는다`() {
        // 이 창이 지키려는 것은 *저장 가능한 한 벌*이 아니라 **적던 중인 것**이다.
        // 수로 바꿔 담으면 회전 한 번이 사용자가 적던 값을 조용히 바꾼다.
        val state = BodyGenerationEditState.initial(gen).copy(
            heightRows = listOf(
                BodyGenerationEditState.RowText("적는 중", listOf("12.", "")),
                BodyGenerationEditState.RowText("", listOf("-", "1e5"))
            )
        )
        val back = BodyGenerationEditState.decode(state.encode())
        assertEquals(listOf("12.", ""), back?.heightRows?.get(0)?.numbers)
        assertEquals(listOf("-", "1e5"), back?.heightRows?.get(1)?.numbers)
        assertEquals("", back?.heightRows?.get(1)?.name)
    }

    @Test
    fun `한 벌도 함께 왕복한다 — 스피너 항목이 거기서 나온다`() {
        val custom = gen.copy(
            torsoOptions = gen.torsoOptions.mapIndexed { i, o -> o.copy(label = "몸통$i") }
        )
        val back = BodyGenerationEditState.decode(BodyGenerationEditState.initial(custom).encode())
        assertEquals(custom.torsoOptions.map { it.label }, back?.current?.torsoOptions?.map { it.label })
    }

    @Test
    fun `못 읽으면 null이다 — 기본값으로 눙치지 않는다`() {
        // 껍데기를 띄우면 [저장]이 사용자가 손대지도 않은 값을 진짜 설정 위에 덮어쓴다.
        assertNull(BodyGenerationEditState.decode(null))
        assertNull(BodyGenerationEditState.decode(""))
        assertNull(BodyGenerationEditState.decode("{"))
        assertNull(BodyGenerationEditState.decode("[]"))
        // 한 벌이 없으면 행 수를 알 수 없다 — 그것도 못 읽은 것이다.
        assertNull(BodyGenerationEditState.decode("""{"heights":[]}"""))
    }

    @Test
    fun `어긋난 축만 되돌린다 — 멀쩡한 나머지는 지킨다`() {
        val state = filled()
        val broken = state.copy(heightRows = state.heightRows.drop(1))
        val fixed = broken.aligned()
        // 어긋난 축은 한 벌의 값으로 돌아오고,
        assertEquals(BodyGenerationEditState.initial(gen).heightRows, fixed.heightRows)
        // 나머지는 적던 그대로다.
        assertEquals(state.torsoRows, fixed.torsoRows)
        assertEquals(state.bustRows, fixed.bustRows)
        assertEquals(state.presetRows, fixed.presetRows)
    }

    @Test
    fun `칸 수가 어긋난 축도 되돌린다`() {
        val state = filled()
        val broken = state.copy(
            torsoRows = state.torsoRows.map { it.copy(numbers = it.numbers.drop(1)) }
        )
        assertEquals(BodyGenerationEditState.initial(gen).torsoRows, broken.aligned().torsoRows)
        assertNotEquals(state.torsoRows, broken.aligned().torsoRows)
    }

    @Test
    fun `프리셋이 없는 축을 가리키면 눌러 담는다`() {
        val state = BodyGenerationEditState.initial(gen)
        val broken = state.copy(
            presetRows = state.presetRows.map { it.copy(torso = 99, bust = -3, hip = 0) }
        )
        val fixed = broken.aligned()
        assertEquals(gen.torsoOptions.size - 1, fixed.presetRows.first().torso)
        assertEquals(0, fixed.presetRows.first().bust)
    }

    @Test
    fun `멀쩡한 것은 aligned가 건드리지 않는다`() {
        val state = filled()
        assertEquals(state, state.aligned())
    }

    @Test
    fun `소수점 뒤 0을 달지 않고 교정 폭보다 잘게 적는다`() {
        assertEquals("24", BodyGenerationEditState.format(24.0))
        assertEquals("18.5", BodyGenerationEditState.format(18.5))
        // 교정이 미는 폭(TORSO_END_STEP = .001)보다 굵으면 열었다 저장만 해도 값이 반올림돼
        // 손대지 않은 설정이 조용히 바뀐다 — 그 폭이 글자로 살아남는지 본다.
        val step = GenerationPreset.Limits.TORSO_END_STEP
        assertEquals(step, BodyGenerationEditState.format(step).toDouble(), 0.0)
        assertEquals(0.4995, BodyGenerationEditState.format(0.4995).toDouble(), 0.0)
    }

    @Test
    fun `처음 여는 상태는 한 벌을 그대로 옮긴 것이다`() {
        val state = BodyGenerationEditState.initial(gen)
        assertEquals(gen.heightOptions.size, state.heightRows.size)
        assertEquals(gen.heightOptions.first().label, state.heightRows.first().name)
        assertEquals(
            BodyGenerationEditState.format(gen.heightOptions.first().center),
            state.heightRows.first().numbers.first()
        )
        assertEquals(gen.bodyPresets.map { it.torso }, state.presetRows.map { it.torso })
    }
}
