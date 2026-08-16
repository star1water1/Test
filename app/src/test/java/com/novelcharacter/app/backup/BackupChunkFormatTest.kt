package com.novelcharacter.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BackupChunkFormat] — 백업 세 형식(NCB2 청크 · v1 레거시 · NCP1 패스프레이즈)의 왕복 (B-230 ⓐ).
 *
 * ## 왜 이 시험이 있는가
 *
 * 이 코드가 틀리면 **증상이 백업을 뜨는 시점에 없다.** 사용자는 초록 토스트를 보고 안심하고,
 * 몇 달 뒤 기기를 잃고 복원할 때 처음 알게 된다 — 그때는 되돌릴 것이 없다. 그런데 이 계층은
 * 2026.08.16까지 **감사도 시험도 0**이었다(엑셀 전수 감사 B-230 ⓐ). 닿지 못한 이유는
 * `BackupEncryptor` 머리의 `android.security.keystore` import 둘이었고, 그래서 형식을
 * [BackupChunkFormat]으로 갈라 내렸다.
 *
 * ## 이 시험이 재는 축 — **절 머리 `── ① ~ ──`가 세는 법이다**
 *
 * 왕복이 맞는가만 보지 않는다. **깨진 입력이 조용히 통과하지 않는가**를 함께 본다 —
 * 개발 의도 2번(변수 제어)이 걸리는 자리는 늘 뒤쪽이다.
 *
 * ## PBKDF2 반복 수를 낮춰 받는 이유
 *
 * 실제 값은 200,000이고 한 번 유도에 수백 ms가 든다. 반복 수는 **헤더에 실려 복호화가 그대로
 * 따르는 값**이라 낮춰도 형식의 성질은 같다 — 그래서 [FAST_ITERATIONS]로 잰다.
 * 기본값 자체가 낮아지지 않았음은 ⑥이 따로 잠근다.
 */
class BackupChunkFormatTest {

    private companion object {
        /** 시험용 PBKDF2 반복 수 — 형식은 같고 시간만 줄인다(위 클래스 주석). */
        const val FASTER_ITERATIONS = 1_000
        const val PASSPHRASE = "hunter2!"
    }

    private fun aesKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun bytes(n: Int, seed: Int = 7): ByteArray =
        ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private fun tempFile(name: String): File =
        File.createTempFile("bcf-$name", ".bin").also { it.deleteOnExit() }

    /**
     * `read()`를 한 번에 [limit] 바이트까지만 채워 주는 스트림.
     *
     * **이것이 이 시험의 핵심 도구다** — `InputStream.read(b)`는 버퍼를 채운다고 약속하지
     * 않으며(규격은 "1개 이상"만 보장한다), FUSE로 얹힌 안드로이드 외부 저장소가 실제로
     * 짧게 준다. 진짜 파일로는 이 갈래를 밟을 수 없어 스트림으로 흉내 낸다.
     */
    private class ShortReadStream(source: InputStream, private val limit: Int) :
        FilterInputStream(source) {
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, minOf(len, limit))
    }

    private fun roundTripChunks(
        plain: ByteArray,
        chunkSize: Int,
        wrap: (InputStream) -> InputStream = { it }
    ): ByteArray {
        val key = aesKey()
        val cipherText = ByteArrayOutputStream()
        BackupChunkFormat.writeChunks(wrap(ByteArrayInputStream(plain)), cipherText, key, chunkSize)
        val out = ByteArrayOutputStream()
        BackupChunkFormat.readChunks(ByteArrayInputStream(cipherText.toByteArray()), out, chunkSize, key)
        return out.toByteArray()
    }

    // ── ① 청크 왕복 — 경계 크기 전부 ──

    @Test
    fun chunks_roundTrip_acrossBoundarySizes() {
        val chunk = 64
        // 빈 입력 · 1바이트 · 청크 직전 · 정확히 한 청크 · 한 청크+1 · 여러 청크 · 마지막이 짧은 것
        for (size in listOf(0, 1, 63, 64, 65, 128, 200)) {
            val plain = bytes(size, seed = size)
            assertArrayEquals("size=$size", plain, roundTripChunks(plain, chunk))
        }
    }

    @Test
    fun chunks_emptyInputWritesNoChunks() {
        val out = ByteArrayOutputStream()
        BackupChunkFormat.writeChunks(ByteArrayInputStream(ByteArray(0)), out, aesKey(), 64)
        assertEquals("빈 입력은 청크를 하나도 쓰지 않는다", 0, out.size())
    }

    /**
     * 같은 평문·같은 키라도 청크마다 새 IV라 암호문이 매번 달라야 한다 — 같아지면 IV를
     * 재사용하고 있다는 뜻이고, GCM에서 IV 재사용은 키를 통째로 무너뜨린다.
     */
    @Test
    fun chunks_ivIsFreshPerRun() {
        val key = aesKey()
        val plain = bytes(200)
        fun once(): ByteArray = ByteArrayOutputStream().also {
            BackupChunkFormat.writeChunks(ByteArrayInputStream(plain), it, key, 64)
        }.toByteArray()
        assertFalse("같은 평문·키인데 암호문이 같다 = IV 재사용", once().contentEquals(once()))
    }

    // ── ② 짧은 읽기 — 이 판이 고친 결함 ──

    /**
     * **회귀 시험.** 종전 구현은 `fis.read(buffer)` **한 번의 반환값**을 그대로 청크 길이로
     * 삼았다. 스트림이 짧게 주면 중간 청크가 `chunkSize`보다 짧아지는데, 청크에는 길이 칸이
     * 없어 읽는 쪽은 `chunkSize + 16`을 통째로 집는다 — 그 다음 청크의 IV까지 삼켜
     * **GCM 태그 검증이 깨지고 그 지점부터 백업 전체가 복원 불가**가 된다.
     *
     * 되돌려 빨간불을 봤다: `writeChunks`의 `readFully`를 `input.read(buffer)`로 돌리면
     * 이 시험이 `AEADBadTagException`으로 죽는다.
     */
    @Test
    fun chunks_survivesShortReads() {
        val plain = bytes(500)
        // 한 번에 10바이트만 주는 스트림 — 청크(64)보다 훨씬 작다.
        val restored = roundTripChunks(plain, chunkSize = 64) { ShortReadStream(it, 10) }
        assertArrayEquals("짧은 읽기가 청크 경계를 어긋내면 안 된다", plain, restored)
    }

    @Test
    fun chunks_shortReadsProduceIdenticalFraming() {
        val key = aesKey()
        val plain = bytes(500)
        fun frameSizes(wrap: (InputStream) -> InputStream): Int {
            val out = ByteArrayOutputStream()
            BackupChunkFormat.writeChunks(wrap(ByteArrayInputStream(plain)), out, key, 64)
            return out.size()
        }
        // 틀이 같으면 바이트 수도 같다 — 청크 수가 달라지면 IV 수가 달라져 길이가 갈린다.
        assertEquals(
            "짧게 읽어도 청크 수가 같아야 한다",
            frameSizes { it },
            frameSizes { ShortReadStream(it, 10) }
        )
    }

    // ── ③ 깨진 입력은 조용히 통과하지 않는다 ──

    @Test
    fun chunks_tamperedCiphertextIsRejected() {
        val key = aesKey()
        val cipherText = ByteArrayOutputStream()
        BackupChunkFormat.writeChunks(ByteArrayInputStream(bytes(200)), cipherText, key, 64)
        val corrupted = cipherText.toByteArray().also { it[40] = (it[40] + 1).toByte() }
        try {
            BackupChunkFormat.readChunks(
                ByteArrayInputStream(corrupted), ByteArrayOutputStream(), 64, key
            )
            fail("한 바이트를 고쳤는데 통과했다 — GCM 태그 검증이 죽어 있다")
        } catch (e: AEADBadTagException) {
            // 기대한 자리
        }
    }

    @Test
    fun chunks_truncatedIvIsRejected() {
        val key = aesKey()
        val cipherText = ByteArrayOutputStream()
        BackupChunkFormat.writeChunks(ByteArrayInputStream(bytes(200)), cipherText, key, 64)
        // IV 한가운데에서 자른다 — EOF(0)와 구별돼야 한다.
        val cut = cipherText.toByteArray().copyOfRange(0, 64 + 16 + 12 + 5)
        try {
            BackupChunkFormat.readChunks(ByteArrayInputStream(cut), ByteArrayOutputStream(), 64, key)
            fail("잘린 IV가 통과했다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Incomplete chunk IV"))
        }
    }

    @Test
    fun chunks_wrongKeyIsRejected() {
        val cipherText = ByteArrayOutputStream()
        BackupChunkFormat.writeChunks(ByteArrayInputStream(bytes(100)), cipherText, aesKey(), 64)
        try {
            BackupChunkFormat.readChunks(
                ByteArrayInputStream(cipherText.toByteArray()), ByteArrayOutputStream(), 64, aesKey()
            )
            fail("다른 키로 복호화가 됐다")
        } catch (e: AEADBadTagException) {
            // 기대한 자리
        }
    }

    // ── ④ NCP1(패스프레이즈) 왕복 — 기기 KeyStore 비의존이라 통째로 잰다 ──

    @Test
    fun portable_roundTrip() {
        val plain = tempFile("plain").apply { writeBytes(bytes(3000)) }
        val enc = tempFile("enc")
        val dec = tempFile("dec")

        BackupChunkFormat.encryptFilePortable(
            plain, enc, PASSPHRASE.toCharArray(), FASTER_ITERATIONS
        )
        assertTrue("NCP1 매직이 붙어야 한다", BackupChunkFormat.isPortableFormat(enc))
        BackupChunkFormat.decryptFilePortable(enc, dec, PASSPHRASE.toCharArray())

        assertArrayEquals(plain.readBytes(), dec.readBytes())
    }

    @Test
    fun portable_wrongPassphraseIsRejectedAndLeavesNoOutput() {
        val plain = tempFile("plain").apply { writeBytes(bytes(500)) }
        val enc = tempFile("enc")
        val dec = tempFile("dec").also { it.delete() }

        BackupChunkFormat.encryptFilePortable(
            plain, enc, PASSPHRASE.toCharArray(), FASTER_ITERATIONS
        )
        try {
            BackupChunkFormat.decryptFilePortable(enc, dec, "wrongpass".toCharArray())
            fail("틀린 암호가 통과했다")
        } catch (e: AEADBadTagException) {
            // 기대한 자리
        }
        // **반쪽 파일을 남기지 않는다** — 실패한 복원이 빈 파일을 놓아 두면 복원 화면이
        // 그것을 결과물로 집는다.
        assertFalse("실패했는데 결과 파일이 생겼다", dec.exists())
        assertFalse("임시 파일이 남았다", File(dec.parentFile, dec.name + ".tmp").exists())
    }

    @Test
    fun portable_shortPassphraseIsRejected() {
        val plain = tempFile("plain").apply { writeBytes(bytes(10)) }
        try {
            BackupChunkFormat.encryptFilePortable(
                plain, tempFile("enc"), "abc".toCharArray(), FASTER_ITERATIONS
            )
            fail("최소 길이보다 짧은 암호가 통과했다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least"))
        }
    }

    @Test
    fun portable_headerIterationsAreBounded() {
        val plain = tempFile("plain").apply { writeBytes(bytes(100)) }
        val enc = tempFile("enc")
        BackupChunkFormat.encryptFilePortable(
            plain, enc, PASSPHRASE.toCharArray(), FASTER_ITERATIONS
        )
        // 헤더의 반복 수를 20억으로 조작한다 — 그대로 따르면 기기가 몇 분을 태운다.
        val raw = enc.readBytes()
        raw[4] = 0x7F; raw[5] = 0xFF.toByte(); raw[6] = 0xFF.toByte(); raw[7] = 0xFF.toByte()
        enc.writeBytes(raw)
        try {
            BackupChunkFormat.decryptFilePortable(enc, tempFile("dec"), PASSPHRASE.toCharArray())
            fail("조작된 반복 수가 통과했다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid iteration count"))
        }
    }

    @Test
    fun portable_progressReachesFileLength() {
        val plain = tempFile("plain").apply { writeBytes(bytes(2000)) }
        val enc = tempFile("enc")
        val dec = tempFile("dec")
        BackupChunkFormat.encryptFilePortable(
            plain, enc, PASSPHRASE.toCharArray(), FASTER_ITERATIONS
        )

        val seen = mutableListOf<Long>()
        BackupChunkFormat.decryptFilePortable(enc, dec, PASSPHRASE.toCharArray()) { seen.add(it) }

        assertTrue("진행 보고가 하나도 없다", seen.isNotEmpty())
        assertEquals("누적이 증가해야 한다", seen.sorted(), seen)
        // R-26: 총량은 입력 파일 길이다 — 마지막 보고가 그 값과 같아야 막대가 끝까지 찬다.
        assertEquals("마지막 보고가 파일 길이와 달라 막대가 안 찬다", enc.length(), seen.last())
    }

    @Test
    fun portable_nonPortableFileIsNotMistakenForOne() {
        val notPortable = tempFile("plain").apply { writeBytes(bytes(100)) }
        assertFalse(BackupChunkFormat.isPortableFormat(notPortable))
        assertFalse("없는 파일", BackupChunkFormat.isPortableFormat(File("/nope/missing.bin")))
    }

    // ── ⑤ NCB2(v2) · v1 레거시 — 키를 손으로 대어 하네스가 든다 ──

    @Test
    fun deviceFormat_v2RoundTrip() {
        val key = aesKey()
        val plain = tempFile("plain").apply { writeBytes(bytes(5000)) }
        val enc = tempFile("enc")
        val dec = tempFile("dec")

        BackupChunkFormat.encryptFileWithKey(plain, enc, key)
        assertFalse("v2는 NCP1이 아니다", BackupChunkFormat.isPortableFormat(enc))
        BackupChunkFormat.decryptFileWithKey(enc, dec, { key })

        assertArrayEquals(plain.readBytes(), dec.readBytes())
    }

    @Test
    fun deviceFormat_v1LegacyStillReads() {
        // v1 = IV(12) + 전체 암호문 + 태그. 옛 백업이 실제로 이 모양이라 계속 읽혀야 한다.
        val key = aesKey()
        val plain = bytes(400)
        val legacy = tempFile("v1").apply { writeBytes(BackupChunkFormat.encrypt(plain, key)) }
        val dec = tempFile("dec")

        BackupChunkFormat.decryptFileWithKey(legacy, dec, { key })
        assertArrayEquals("옛 형식을 읽지 못하면 그 백업들은 영영 못 연다", plain, dec.readBytes())
    }

    /**
     * 헤더가 깨져 갈래에 닿지 못하면 **키를 만들지 않는다** — `getOrCreateKey`는 이름 그대로
     * 없으면 만드는 함수라, 실패한 복원이 KeyStore에 새 키를 심어 두면 그 뒤의 진짜 백업이
     * 열리지 않는다.
     */
    @Test
    fun deviceFormat_badHeaderDoesNotAskForKey() {
        val tooShort = tempFile("short").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var asked = false
        try {
            BackupChunkFormat.decryptFileWithKey(tooShort, tempFile("dec"), { asked = true; aesKey() })
            fail("8바이트도 안 되는 파일이 통과했다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too short"))
        }
        assertFalse("헤더가 깨졌는데 키를 요구했다", asked)
    }

    @Test
    fun deviceFormat_invalidChunkSizeIsRejected() {
        val key = aesKey()
        val plain = tempFile("plain").apply { writeBytes(bytes(100)) }
        val enc = tempFile("enc")
        BackupChunkFormat.encryptFileWithKey(plain, enc, key)
        // 헤더의 chunkSize를 상한 밖으로 — 그대로 믿으면 그 크기의 배열을 잡는다(OOM 유도).
        val raw = enc.readBytes()
        raw[4] = 0x7F; raw[5] = 0xFF.toByte(); raw[6] = 0xFF.toByte(); raw[7] = 0xFF.toByte()
        enc.writeBytes(raw)
        try {
            BackupChunkFormat.decryptFileWithKey(enc, tempFile("dec"), { key })
            fail("조작된 청크 크기가 통과했다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid chunk size"))
        }
    }

    @Test
    fun bytes_roundTripAndTooShortRejected() {
        val key = aesKey()
        val plain = bytes(64)
        assertArrayEquals(plain, BackupChunkFormat.decrypt(BackupChunkFormat.encrypt(plain, key), key))
        try {
            BackupChunkFormat.decrypt(ByteArray(BackupChunkFormat.GCM_IV_LENGTH), key)
            fail("IV 길이뿐인 입력이 통과했다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too short"))
        }
    }

    // ── ⑥ 형식 상수 — 바뀌면 옛 백업이 안 열린다 ──

    /**
     * 이 값들은 **디스크에 이미 쓰인 파일이 기대는 값**이다. 고치면 그 전에 뜬 백업이 전부
     * 안 열리므로, 바꾸려면 형식 판올림(새 매직)을 함께 해야 한다 — 그 사실을 여기서 못박는다.
     */
    @Test
    fun formatConstants_areFrozen() {
        assertEquals(12, BackupChunkFormat.GCM_IV_LENGTH)
        assertEquals(128, BackupChunkFormat.GCM_TAG_LENGTH)
        assertEquals(16, BackupChunkFormat.GCM_TAG_BYTES)
        assertEquals(16, BackupChunkFormat.SALT_LENGTH)
        assertEquals(1024 * 1024, BackupChunkFormat.CHUNK_SIZE)
        assertEquals(8, BackupChunkFormat.FORMAT_HEADER_SIZE)
        assertEquals("AES/GCM/NoPadding", BackupChunkFormat.TRANSFORMATION)
        assertEquals("PBKDF2WithHmacSHA256", BackupChunkFormat.PBKDF2_ALGORITHM)
        assertArrayEquals("NCB2".toByteArray(), BackupChunkFormat.formatMagic())
        assertArrayEquals("NCP1".toByteArray(), BackupChunkFormat.portableMagic())
        // 시험이 빠르라고 낮춘 것은 인자이지 기본값이 아니다(클래스 주석).
        assertEquals(200_000, BackupChunkFormat.PBKDF2_ITERATIONS)
        assertEquals(6, BackupChunkFormat.MIN_PASSPHRASE_LENGTH)
    }

    /** 매직은 부를 때마다 새 배열 — 고쳐도 다음 부름이 오염되지 않는다. */
    @Test
    fun formatMagic_isNotSharedMutableState() {
        BackupChunkFormat.formatMagic()[0] = 0
        assertNotEquals(0.toByte(), BackupChunkFormat.formatMagic()[0])
    }
}
