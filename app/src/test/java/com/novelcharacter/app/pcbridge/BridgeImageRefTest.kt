package com.novelcharacter.app.pcbridge

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeImageRefTest {

    private fun tempDir(): File = Files.createTempDirectory("bridge-img").toFile()

    private fun fileIn(dir: File, name: String): String {
        val f = File(dir, name)
        f.writeText("x")
        return f.absolutePath
    }

    @Test
    fun id_isStableAndOpaque() {
        val a = BridgeImageRef.idFor("/data/app/files/char_1.jpg")
        assertEquals(a, BridgeImageRef.idFor("/data/app/files/char_1.jpg"))
        assertEquals(BridgeImageRef.ID_LENGTH, a.length)
        // 경로가 되돌려지지 않는다 — id에 경로 조각이 남아 있지 않다.
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, BridgeImageRef.idFor("/data/app/files/char_2.jpg"))
    }

    @Test
    fun resolve_findsInsidePath() {
        val dir = tempDir()
        val p = fileIn(dir, "char_a.jpg")
        val r = BridgeImageRef.resolve(BridgeImageRef.idFor(p), listOf(p), dir)
        assertEquals(BridgeImageRef.Resolution.Found(p), r)
    }

    /**
     * 후보에 있지만 앱 저장소 밖 — **막힌 것이고 없는 것이 아니다** (R-39 · R-17).
     * 엑셀 '이미지' 열로 들어온 외부 경로가 실제로 이 모양으로 DB에 산다.
     */
    @Test
    fun resolve_outsideAppDirIsBlockedNotMissing() {
        val dir = tempDir()
        val outside = tempDir()
        val p = fileIn(outside, "char_b.jpg")
        val r = BridgeImageRef.resolve(BridgeImageRef.idFor(p), listOf(p), dir)
        assertEquals(BridgeImageRef.Resolution.Blocked(p), r)
    }

    @Test
    fun resolve_unknownIdIsNotFound() {
        val dir = tempDir()
        val p = fileIn(dir, "char_c.jpg")
        val other = BridgeImageRef.idFor("/nowhere/char_zz.jpg")
        assertEquals(BridgeImageRef.Resolution.NotFound, BridgeImageRef.resolve(other, listOf(p), dir))
    }

    @Test
    fun resolve_wrongLengthIdIsNotFound() {
        val dir = tempDir()
        val p = fileIn(dir, "char_d.jpg")
        assertEquals(BridgeImageRef.Resolution.NotFound, BridgeImageRef.resolve("abc", listOf(p), dir))
        assertEquals(BridgeImageRef.Resolution.NotFound, BridgeImageRef.resolve("", listOf(p), dir))
    }

    /** filesDir를 모르면 막는다. */
    @Test
    fun resolve_nullFilesDirBlocks() {
        val dir = tempDir()
        val p = fileIn(dir, "char_e.jpg")
        assertEquals(
            BridgeImageRef.Resolution.Blocked(p),
            BridgeImageRef.resolve(BridgeImageRef.idFor(p), listOf(p), null)
        )
    }

    /** 잘라낸 것은 **개수로 존재를 알린다** (R-14). */
    @Test
    fun servable_countsBlocked() {
        val dir = tempDir()
        val outside = tempDir()
        val inA = fileIn(dir, "char_f.jpg")
        val inB = fileIn(dir, "char_g.jpg")
        val out1 = fileIn(outside, "char_h.jpg")
        val s = BridgeImageRef.servable(listOf(inA, out1, inB, "  "), dir)
        assertEquals(2, s.entries.size)
        assertEquals(1, s.blockedCount)
        assertEquals(setOf(inA, inB), s.entries.map { it.second }.toSet())
        for ((id, path) in s.entries) assertEquals(BridgeImageRef.idFor(path), id)
    }

    @Test
    fun servable_emptyCandidates() {
        val dir = tempDir()
        val s = BridgeImageRef.servable(emptyList(), dir)
        assertTrue(s.entries.isEmpty())
        assertEquals(0, s.blockedCount)
    }
}
