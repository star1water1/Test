package com.novelcharacter.app.excel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * 이미지 엔트리를 STORED로 쓰는 계약(S25 4-ㄴ2).
 *
 * **이 시험이 지키는 것은 "수준 전환 없이 deflate와 STORED가 한 ZIP에 섞여도 그대로 읽힌다"이다.**
 * 종전 구현은 엔트리 사이에서 `setLevel`을 바꿨고, 안드로이드에서 그 직후 엔트리가 깨졌다 —
 * JVM은 그 결함을 재현하지 못하므로, 여기서는 *전환 자체를 쓰지 않는* 새 모양이 백업 형식
 * (data.xlsx → images/… → image_map.json)으로 왕복하는지를 잠근다.
 */
class StoredZipEntryTest {

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    @Test
    fun `측정은 크기와 CRC32를 낸다`() {
        val data = bytes(200_000, 1)
        val (size, crc) = StoredZipEntry.measure(ByteArrayInputStream(data))
        assertEquals(data.size.toLong(), size)
        assertEquals(CRC32().apply { update(data) }.value, crc)
    }

    @Test
    fun `백업 모양 그대로 deflate와 STORED가 섞여도 모든 엔트리가 원본과 같다`() {
        val dir = createTempDir()
        try {
            // 첫 이미지가 깨지던 자리를 그대로 만든다 — data.xlsx(deflate) 바로 뒤의 이미지.
            val xlsx = ByteArray(300_000) { (it % 7).toByte() }      // 잘 압축되는 바이트
            val images = listOf(bytes(2004, 2), bytes(1412, 3), bytes(70_000, 4), ByteArray(0))
            val files = images.mapIndexed { i, b -> File(dir, "img$i.jpg").apply { writeBytes(b) } }
            val json = """{"a":"images/img0.jpg"}""".repeat(500).toByteArray()

            val zipFile = File(dir, "backup.zip")
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("data.xlsx")); zip.write(xlsx); zip.closeEntry()
                files.forEachIndexed { i, f ->
                    // 짝수 장은 메모리 사본, 홀수 장은 임시 파일 사본 갈래로 쓴다 — 둘 다 같은 바이트여야 한다.
                    StoredZipEntry.write(zip, "images/img$i.jpg", f, scratchDir = dir, inMemoryLimit = if (i % 2 == 0) Long.MAX_VALUE else 0)
                }
                zip.putNextEntry(ZipEntry("image_map.json")); zip.write(json); zip.closeEntry()
            }

            // 복원 경로(ZipFile)로 읽는다.
            ZipFile(zipFile).use { z ->
                assertArrayEquals(xlsx, z.getInputStream(z.getEntry("data.xlsx")).readBytes())
                assertEquals(ZipEntry.DEFLATED, z.getEntry("data.xlsx").method)
                images.forEachIndexed { i, b ->
                    val e = z.getEntry("images/img$i.jpg")
                    assertEquals("이미지는 무압축이어야 한다(D8)", ZipEntry.STORED, e.method)
                    assertArrayEquals(b, z.getInputStream(e).readBytes())
                }
                assertEquals(ZipEntry.DEFLATED, z.getEntry("image_map.json").method)
                assertArrayEquals(json, z.getInputStream(z.getEntry("image_map.json")).readBytes())
            }
            // 임시 파일 갈래가 사본을 남기지 않는다.
            assertEquals(emptyList<String>(), dir.list()!!.filter { it.startsWith("zipimg_") })
            // 스트리밍 리더(ZipInputStream)도 STORED 엔트리를 끝까지 읽는다.
            ZipInputStream(zipFile.inputStream()).use { zin ->
                var count = 0
                while (zin.nextEntry != null) { zin.readBytes(); count++ }
                assertEquals(2 + images.size, count)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `한 장이 실패해도 뒤 엔트리와 ZIP 마감이 멀쩡하다`() {
        // 리뷰(PR #432): 실패한 STORED 엔트리가 열린 채 남으면 뒤 엔트리·마감이 함께 죽는다.
        val dir = createTempDir()
        try {
            val ok = File(dir, "ok.jpg").apply { writeBytes(bytes(5000, 9)) }
            val missing = File(dir, "gone.jpg")
            val zipFile = File(dir, "b.zip")
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                try { StoredZipEntry.write(zip, "images/gone.jpg", missing, dir) } catch (_: java.io.IOException) { }
                StoredZipEntry.write(zip, "images/ok.jpg", ok, dir)
                zip.putNextEntry(ZipEntry("image_map.json")); zip.write("{}".toByteArray()); zip.closeEntry()
            }
            ZipFile(zipFile).use { z ->
                assertEquals(listOf("images/ok.jpg", "image_map.json"), z.entries().toList().map { it.name })
                assertArrayEquals(ok.readBytes(), z.getInputStream(z.getEntry("images/ok.jpg")).readBytes())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `원본을 못 열면 엔트리를 남기지 않고 던진다`() {
        val out = ByteArrayOutputStream()
        val missing = File(createTempDir(), "gone.jpg")
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("data.xlsx")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            try {
                StoredZipEntry.write(zip, "images/gone.jpg", missing, scratchDir = missing.parentFile!!)
            } catch (_: java.io.IOException) {
            }
        }
        val names = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zin ->
            generateSequence { zin.nextEntry?.name }.toList()
        }
        assertEquals(listOf("data.xlsx"), names)
    }

    @Test
    fun `중단으로 남은 오래된 사본만 지운다`() {
        val dir = createTempDir()
        try {
            val now = 10_000_000_000L
            val age = 6L * 60 * 60 * 1000
            val stale = File(dir, "zipimg_1.tmp").apply { writeText("x"); setLastModified(now - age - 1) }
            val fresh = File(dir, "zipimg_2.tmp").apply { writeText("x"); setLastModified(now - 1000) }
            val other = File(dir, "backup_3.zip").apply { writeText("x"); setLastModified(now - age - 1) }
            assertEquals(1, StoredZipEntry.sweepStaleScratch(dir, age, now))
            assertEquals(false, stale.exists())
            assertEquals(true, fresh.exists())
            assertEquals(true, other.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
