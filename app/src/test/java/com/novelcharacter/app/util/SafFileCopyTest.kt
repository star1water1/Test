package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [SafFileCopy] — SAF 저장의 세 단계 계약.
 *
 * 이 하네스가 지키는 것은 넷이다:
 * 1. **열지 못하면 실패다** — 널을 성공으로 흘리면 0바이트 파일에 "저장되었습니다"가 붙는다.
 * 2. **실패하면 목적지를 지운다** — 만들어진 빈 문서를 두면 정상 사본과 구별되지 않는다.
 * 3. **지웠는지를 답이 말한다** — 지우기도 실패하므로 확인 없는 단정을 하지 않는다.
 * 4. **성공은 지우지 않는다** — 반대로 도는 순간 사용자의 파일이 사라진다.
 */
class SafFileCopyTest {

    private class Probe(
        val out: OutputStream? = ByteArrayOutputStream(),
        val source: ByteArray = "가온".toByteArray(),
        val openThrows: Exception? = null,
        val copyThrows: Boolean = false,
        val deleteResult: Boolean = true,
        val deleteThrows: Boolean = false
    ) {
        var deleteCalls = 0
        var closed = false

        fun run(): SafFileCopy.Result = SafFileCopy.copyOrDiscard(
            openOutput = {
                openThrows?.let { throw it }
                out?.let { target ->
                    object : OutputStream() {
                        override fun write(b: Int) = target.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
                        override fun close() { closed = true; target.close() }
                    }
                }
            },
            openInput = {
                if (copyThrows) {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("중간에 끊겼다")
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            throw IOException("중간에 끊겼다")
                    }
                } else {
                    ByteArrayInputStream(source)
                }
            },
            deleteDestination = {
                deleteCalls++
                if (deleteThrows) throw IllegalStateException("권한 없음")
                deleteResult
            }
        )
    }

    @Test
    fun `열지 못하면 실패이고 목적지를 지운다`() {
        val probe = Probe(out = null)
        val result = probe.run()
        val failed = result as SafFileCopy.Result.Failed
        assertNull("널은 예외가 아니라 답이다", failed.cause)
        assertTrue(failed.destinationRemoved)
        assertEquals(1, probe.deleteCalls)
    }

    @Test
    fun `여는 도중 터져도 목적지를 지운다`() {
        val probe = Probe(openThrows = IOException("provider 거절"))
        val failed = probe.run() as SafFileCopy.Result.Failed
        assertEquals("provider 거절", failed.cause?.message)
        assertEquals(1, probe.deleteCalls)
    }

    @Test
    fun `복사 도중 끊기면 반쪽 파일을 지운다`() {
        val probe = Probe(copyThrows = true)
        val failed = probe.run() as SafFileCopy.Result.Failed
        assertEquals("중간에 끊겼다", failed.cause?.message)
        assertTrue(failed.destinationRemoved)
        assertTrue("실패해도 스트림은 닫힌다", probe.closed)
    }

    @Test
    fun `지우지 못하면 지웠다고 말하지 않는다`() {
        val byResult = Probe(out = null, deleteResult = false).run() as SafFileCopy.Result.Failed
        assertTrue(!byResult.destinationRemoved)
        val byThrow = Probe(out = null, deleteThrows = true).run() as SafFileCopy.Result.Failed
        assertTrue("지우기가 터져도 답을 내야 한다", !byThrow.destinationRemoved)
    }

    @Test
    fun `성공하면 목적지를 지우지 않는다`() {
        val sink = ByteArrayOutputStream()
        val probe = Probe(out = sink)
        assertEquals(SafFileCopy.Result.Success, probe.run())
        assertEquals(0, probe.deleteCalls)
        assertEquals("가온", sink.toString("UTF-8"))
        assertTrue(probe.closed)
    }
}
