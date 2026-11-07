package com.novelcharacter.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinnerChangeGateTest {

    private val spinner = Any()

    @Test
    fun `initial layout callback at the registered position is not a change`() {
        val gate = SpinnerChangeGate()
        gate.markProgrammatic(spinner, 0)
        assertFalse(gate.onSelected(spinner, 0))
    }

    @Test
    fun `late callback of a load arriving after the initial one is not a change`() {
        // 종전 결함의 순서: 첫 레이아웃(0) → 적재 setSelection(2) → 뒤늦은 콜백(2)
        val gate = SpinnerChangeGate()
        gate.markProgrammatic(spinner, 0)
        assertFalse(gate.onSelected(spinner, 0))
        gate.markProgrammatic(spinner, 2)
        assertFalse(gate.onSelected(spinner, 2))
    }

    @Test
    fun `load before first layout yields a single quiet callback`() {
        val gate = SpinnerChangeGate()
        gate.markProgrammatic(spinner, 0)
        gate.markProgrammatic(spinner, 2)
        assertFalse(gate.onSelected(spinner, 2))
    }

    @Test
    fun `user pick after load is a change`() {
        val gate = SpinnerChangeGate()
        gate.markProgrammatic(spinner, 2)
        assertFalse(gate.onSelected(spinner, 2))
        assertTrue(gate.onSelected(spinner, 1))
        // 같은 위치의 반복 콜백은 다시 세지 않는다
        assertFalse(gate.onSelected(spinner, 1))
    }

    @Test
    fun `unregistered spinner callback is not counted`() {
        val gate = SpinnerChangeGate()
        assertFalse(gate.onSelected(spinner, 3))
        assertTrue(gate.onSelected(spinner, 1))
    }

    @Test
    fun `clear forgets baselines`() {
        val gate = SpinnerChangeGate()
        gate.markProgrammatic(spinner, 1)
        gate.clear()
        assertFalse(gate.onSelected(spinner, 4))
    }
}
