package com.novelcharacter.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestOnlyLauncherTest {

    /**
     * 회전 복원의 경쟁 그대로다: 초기 콜백(전역 폼)이 DB 조회에서 멈춘 사이 작품 콜백이
     * 복원을 끝낸다. 앞의 것이 늦게 깨어나 폼에 DB 값을 쓰면 안 된다.
     */
    @Test
    fun `superseded request never writes after it resumes`() {
        val scope = TestScope(StandardTestDispatcher())
        val launcher = LatestOnlyLauncher()
        val slowDbRead = CompletableDeferred<String>()
        val writes = mutableListOf<String>()

        launcher.launch(scope) {
            val dbValue = slowDbRead.await()   // 콜드 DB 조회
            writes += "global:$dbValue"
        }
        scope.runCurrent()                       // 첫 요청이 조회에서 멈춘다

        launcher.launch(scope) { writes += "novel:restored" }
        scope.runCurrent()

        slowDbRead.complete("970")               // 앞 요청의 조회가 뒤늦게 돌아온다
        scope.advanceUntilIdle()

        assertEquals(listOf("novel:restored"), writes)
    }

    @Test
    fun `latest request runs to completion`() {
        val scope = TestScope(StandardTestDispatcher())
        val launcher = LatestOnlyLauncher()
        val gate = CompletableDeferred<Unit>()
        var done = false

        val job = launcher.launch(scope) { gate.await(); done = true }
        scope.runCurrent()
        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertTrue(done)
        assertTrue(job.isCompleted && !job.isCancelled)
    }

    /**
     * 같은 자리의 거듭된 스피너 콜백을 건너뛸지는 *진행 중인 요청이 없는가*로 가린다 —
     * 진행 중이면 그것이 다른 작품의 폼을 짓는 중일 수 있어 건너뛰면 안 된다.
     */
    @Test
    fun `isRunning is true only while the latest request is in flight`() {
        val scope = TestScope(StandardTestDispatcher())
        val launcher = LatestOnlyLauncher()
        assertFalse(launcher.isRunning)

        val gate = CompletableDeferred<Unit>()
        launcher.launch(scope) { gate.await() }
        scope.runCurrent()
        assertTrue(launcher.isRunning)

        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertFalse(launcher.isRunning)
    }

    @Test
    fun `earlier request is cancelled even before it started`() {
        val scope = TestScope(StandardTestDispatcher())
        val launcher = LatestOnlyLauncher()
        val ran = mutableListOf<Int>()

        val first = launcher.launch(scope) { ran += 1 }
        launcher.launch(scope) { ran += 2 }
        scope.advanceUntilIdle()

        assertTrue(first.isCancelled)
        assertEquals(listOf(2), ran)
    }
}
