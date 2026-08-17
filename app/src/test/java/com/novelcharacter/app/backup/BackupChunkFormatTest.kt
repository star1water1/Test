package com.novelcharacter.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
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

    /** 시험용 헤더 — 청크가 묶이는 바이트. 실제 헤더처럼 매직 + 청크 크기 모양이다. */
    private fun header(chunkSize: Int, magic: ByteArray = BackupChunkFormat.deviceMagicV3()): ByteArray =
        ByteBuffer.allocate(8).put(magic).putInt(chunkSize).array()

    private fun writeBound(plain: ByteArray, key: SecretKey, chunkSize: Int, head: ByteArray): ByteArray =
        ByteArrayOutputStream().also {
            BackupChunkFormat.writeChunks(ByteArrayInputStream(plain), it, key, head, chunkSize)
        }.toByteArray()

    private fun readBound(
        cipherText: ByteArray,
        key: SecretKey,
        chunkSize: Int,
        head: ByteArray?
    ): ByteArray = ByteArrayOutputStream().also {
        BackupChunkFormat.readChunks(ByteArrayInputStream(cipherText), it, chunkSize, key, head)
    }.toByteArray()

    /**
     * **옛(묶이지 않은) 청크를 손으로 짠다** — 새 [BackupChunkFormat.writeChunks]는 언제나
     * 묶으므로, 이 방법으로만 *실제로 디스크에 있는 옛 백업*의 바이트를 만들 수 있다.
     * 종전 구현의 루프를 그대로 옮겼다: 빈 입력은 청크를 하나도 쓰지 않았다.
     */
    private fun legacyChunks(plain: ByteArray, key: SecretKey, chunkSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < plain.size) {
            val n = minOf(chunkSize, plain.size - off)
            val cipher = Cipher.getInstance(BackupChunkFormat.TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            out.write(cipher.iv)
            out.write(cipher.doFinal(plain, off, n))
            off += n
        }
        return out.toByteArray()
    }

    /** NCP1의 키 유도 — 그 형식이 실제로 쓰던 계산을 시험 쪽에서 되짚는다. */
    private fun pbkdf2(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance(BackupChunkFormat.PBKDF2_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun roundTripChunks(
        plain: ByteArray,
        chunkSize: Int,
        wrap: (InputStream) -> InputStream = { it }
    ): ByteArray {
        val key = aesKey()
        val head = header(chunkSize)
        val cipherText = ByteArrayOutputStream()
        BackupChunkFormat.writeChunks(
            wrap(ByteArrayInputStream(plain)), cipherText, key, head, chunkSize
        )
        return readBound(cipherText.toByteArray(), key, chunkSize, head)
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

    /**
     * **빈 입력도 마지막 청크 하나를 남긴다**(B-248). 종전에는 0바이트를 썼는데, 그러면
     * *뒤가 통째로 날아가 헤더만 남은 파일*과 *빈 백업*이 바이트로 구별되지 않는다.
     */
    @Test
    fun chunks_emptyInputStillWritesOneFinalChunk() {
        val key = aesKey()
        val head = header(64)
        val out = ByteArrayOutputStream()
        BackupChunkFormat.writeChunks(ByteArrayInputStream(ByteArray(0)), out, key, head, 64)
        assertEquals(
            "빈 입력도 마지막 표시를 남긴다 — IV(12) + 태그(16)",
            BackupChunkFormat.GCM_IV_LENGTH + BackupChunkFormat.GCM_TAG_BYTES,
            out.size()
        )
        assertEquals(0, readBound(out.toByteArray(), key, 64, head).size)
    }

    /**
     * 같은 평문·같은 키라도 청크마다 새 IV라 암호문이 매번 달라야 한다 — 같아지면 IV를
     * 재사용하고 있다는 뜻이고, GCM에서 IV 재사용은 키를 통째로 무너뜨린다.
     */
    @Test
    fun chunks_ivIsFreshPerRun() {
        val key = aesKey()
        val plain = bytes(200)
        val head = header(64)
        fun once(): ByteArray = writeBound(plain, key, 64, head)
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
        val head = header(64)
        fun frameSizes(wrap: (InputStream) -> InputStream): Int {
            val out = ByteArrayOutputStream()
            BackupChunkFormat.writeChunks(wrap(ByteArrayInputStream(plain)), out, key, head, 64)
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
        val head = header(64)
        val corrupted = writeBound(bytes(200), key, 64, head).also { it[40] = (it[40] + 1).toByte() }
        try {
            readBound(corrupted, key, 64, head)
            fail("한 바이트를 고쳤는데 통과했다 — GCM 태그 검증이 죽어 있다")
        } catch (e: AEADBadTagException) {
            // 기대한 자리
        }
    }

    @Test
    fun chunks_truncatedIvIsRejected() {
        val key = aesKey()
        val head = header(64)
        // IV 한가운데에서 자른다 — EOF(0)와 구별돼야 한다.
        val cut = writeBound(bytes(200), key, 64, head).copyOfRange(0, 64 + 16 + 12 + 5)
        try {
            readBound(cut, key, 64, head)
            fail("잘린 IV가 통과했다")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("청크 IV가 잘렸습니다"))
        }
    }

    @Test
    fun chunks_wrongKeyIsRejected() {
        val head = header(64)
        val cipherText = writeBound(bytes(100), aesKey(), 64, head)
        try {
            readBound(cipherText, aesKey(), 64, head)
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
            assertTrue(e.message!!.contains("최소 ${BackupChunkFormat.MIN_PASSPHRASE_LENGTH}자"))
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
            assertTrue(e.message!!.contains("반복 횟수가 올바르지 않습니다"))
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

    /** NCP1로 만든 옛 백업의 바이트를 손으로 짠다 — 실제 디스크에 있는 모양이다. */
    private fun legacyPortableFile(plain: ByteArray, chunkSize: Int): File {
        val salt = bytes(BackupChunkFormat.SALT_LENGTH, seed = 3)
        val key = pbkdf2(PASSPHRASE.toCharArray(), salt, FASTER_ITERATIONS)
        val raw = ByteArrayOutputStream().apply {
            write(BackupChunkFormat.portableMagicV1())
            write(ByteBuffer.allocate(4).putInt(FASTER_ITERATIONS).array())
            write(salt)
            write(ByteBuffer.allocate(4).putInt(chunkSize).array())
            write(legacyChunks(plain, key, chunkSize))
        }.toByteArray()
        return tempFile("ncp1").apply { writeBytes(raw) }
    }

    /**
     * **옛 이식 백업(NCP1)이 계속 열리는가** — B-248 판올림의 조건이다. 이 길이 끊기면
     * 사용자가 기기를 옮기려고 따로 만들어 둔 백업이 전부 죽는다.
     */
    @Test
    fun portable_v1LegacyStillReads() {
        val plain = bytes(700)
        val enc = legacyPortableFile(plain, chunkSize = 64)
        val dec = tempFile("dec")

        assertTrue("옛 매직도 '이식 가능'으로 알아봐야 암호를 묻는다", BackupChunkFormat.isPortableFormat(enc))
        BackupChunkFormat.decryptFilePortable(enc, dec, PASSPHRASE.toCharArray())
        assertArrayEquals("NCP1을 못 읽으면 그 백업들은 영영 못 연다", plain, dec.readBytes())
    }

    @Test
    fun portable_v2WritesTheNewMagic() {
        val plain = tempFile("plain").apply { writeBytes(bytes(100)) }
        val enc = tempFile("enc")
        BackupChunkFormat.encryptFilePortable(
            plain, enc, PASSPHRASE.toCharArray(), FASTER_ITERATIONS
        )
        assertArrayEquals(
            "새로 쓰는 것은 NCP2다",
            BackupChunkFormat.portableMagicV2(),
            enc.readBytes().copyOfRange(0, 4)
        )
    }

    /**
     * **뒤가 잘린 이식 백업은 '암호가 틀렸다'가 아니다** — 이 갈래가 이 판의 요점 하나다.
     * `SettingsFragment.restoreFromPortableFile`은 `AEADBadTagException`을 잡아 **암호를
     * 다시 묻는다.** 잘린 파일에 그렇게 답하면 사용자는 맞는 암호를 넣고도 영영 되묻힌다.
     */
    @Test
    fun portable_truncatedTailIsNotReportedAsWrongPassphrase() {
        val plain = tempFile("plain").apply { writeBytes(bytes(4000)) }
        val enc = tempFile("enc")
        BackupChunkFormat.encryptFilePortable(
            plain, enc, PASSPHRASE.toCharArray(), FASTER_ITERATIONS
        )
        // 청크 크기가 1MB라 4000바이트는 한 청크다 — 헤더만 남기고 잘라 '뒤가 없음'을 만든다.
        enc.writeBytes(enc.readBytes().copyOfRange(0, BackupChunkFormat.PORTABLE_HEADER_SIZE))

        val dec = tempFile("dec").also { it.delete() }
        try {
            BackupChunkFormat.decryptFilePortable(enc, dec, PASSPHRASE.toCharArray())
            fail("뒤가 통째로 잘린 파일이 통과했다")
        } catch (e: AEADBadTagException) {
            fail("잘림을 '암호가 틀렸다'로 답했다 — 화면이 암호를 영영 되묻는다")
        } catch (e: BackupChunkFormat.TruncatedBackupException) {
            // 기대한 자리
        }
        assertFalse("실패했는데 결과 파일이 생겼다", dec.exists())
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
     * **NCB2(묶이지 않은 청크)를 계속 읽는가** — B-248 판올림의 조건이다. 손으로 짠 옛 바이트로
     * 잰다(새 쓰는 쪽은 언제나 묶으므로 그 길로는 이 모양을 만들 수 없다).
     */
    @Test
    fun deviceFormat_v2LegacyChunksStillRead() {
        val key = aesKey()
        val plain = bytes(700)
        val chunkSize = 64
        val old = ByteArrayOutputStream().apply {
            write(BackupChunkFormat.deviceMagicV2())
            write(ByteBuffer.allocate(4).putInt(chunkSize).array())
            write(legacyChunks(plain, key, chunkSize))
        }.toByteArray()
        val enc = tempFile("ncb2").apply { writeBytes(old) }
        val dec = tempFile("dec")

        BackupChunkFormat.decryptFileWithKey(enc, dec, { key })
        assertArrayEquals("NCB2를 못 읽으면 그 백업들은 영영 못 연다", plain, dec.readBytes())
    }

    @Test
    fun deviceFormat_v3WritesTheNewMagic() {
        val plain = tempFile("plain").apply { writeBytes(bytes(100)) }
        val enc = tempFile("enc")
        BackupChunkFormat.encryptFileWithKey(plain, enc, aesKey())
        assertArrayEquals(
            "새로 쓰는 것은 NCB3다 — 옛 매직으로 쓰면 묶임이 조용히 없는 파일이 된다",
            BackupChunkFormat.deviceMagicV3(),
            enc.readBytes().copyOfRange(0, 4)
        )
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
            assertTrue(e.message!!.contains("너무 짧습니다"))
        }
        assertFalse("헤더가 깨졌는데 키를 요구했다", asked)
    }

    /** 기기 백업도 같은 갈래를 탄다 — 잘림은 태그 불일치가 아니라 잘림으로 답한다. */
    @Test
    fun deviceFormat_v3TruncatedTailIsRejected() {
        val key = aesKey()
        val plain = tempFile("plain").apply { writeBytes(bytes(3000)) }
        val enc = tempFile("enc")
        BackupChunkFormat.encryptFileWithKey(plain, enc, key)
        enc.writeBytes(enc.readBytes().copyOfRange(0, BackupChunkFormat.FORMAT_HEADER_SIZE))

        val dec = tempFile("dec").also { it.delete() }
        try {
            BackupChunkFormat.decryptFileWithKey(enc, dec, { key })
            fail("뒤가 잘린 기기 백업이 통과했다")
        } catch (e: BackupChunkFormat.TruncatedBackupException) {
            // 기대한 자리
        }
        assertFalse("실패했는데 결과 파일이 생겼다", dec.exists())
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
            assertTrue(e.message!!.contains("청크 크기가 올바르지 않습니다"))
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
            assertTrue(e.message!!.contains("너무 짧습니다"))
        }
    }

    // ── ⑥ 청크 묶기 — 이 판이 고친 결함 (B-248) ──
    //
    // GCM은 *각 청크가 진짜인가*만 답한다. 아래 다섯은 전부 **청크 하나하나는 진짜인 채로**
    // 파일 전체가 거짓인 모양이라, 묶기 전에는 하나도 걸리지 않았다.

    /**
     * **회귀 시험 — 이 판의 본체.** 뒤쪽 청크가 통째로 잘린 파일(전송 중단·저장 공간 소진·
     * 동기화 중 복사)을 종전 `readChunks`는 EOF로 읽고 **정상 종료해 짧은 평문을 냈다.**
     *
     * 되돌려 빨간불을 봤다: `writeChunks`/`readChunks`에서 `updateAAD`를 빼면 이 시험이
     * *예외 없이 통과해* `fail`에 닿는다.
     */
    @Test
    fun chunks_truncatedTailIsProvenAndRejected() {
        val key = aesKey()
        val head = header(64)
        val plain = bytes(200)                          // 청크 넷(64·64·64·8)
        val full = writeBound(plain, key, 64, head)
        // 마지막 두 청크를 통째로 뗀다 — 남은 것은 꽉 찬 청크 둘이라 EOF가 곧 끝처럼 보인다.
        val cut = full.copyOfRange(0, 2 * (12 + 64 + 16))

        try {
            readBound(cut, key, 64, head)
            fail("뒤가 잘린 파일이 '복호화 성공'으로 짧은 평문을 냈다")
        } catch (e: BackupChunkFormat.TruncatedBackupException) {
            // 태그 불일치가 아니라 **잘림으로 특정**돼야 한다 — 화면이 그 둘에 다르게 답한다.
            assertTrue(e.message!!.contains("백업 파일이 잘렸습니다"))
        }
    }

    /** 헤더만 남고 뒤가 통째로 날아간 파일 — 종전에는 "빈 백업"으로 조용히 통과했다. */
    @Test
    fun chunks_noChunksAtAllIsRejected() {
        try {
            readBound(ByteArray(0), aesKey(), 64, header(64))
            fail("청크가 하나도 없는데 통과했다")
        } catch (e: BackupChunkFormat.TruncatedBackupException) {
            assertTrue(e.message!!.contains("마지막 청크가 없습니다"))
        }
    }

    /** 순서를 바꾼 청크 — 하나하나는 진짜지만 파일은 거짓이다. */
    @Test
    fun chunks_reorderedChunksAreRejected() {
        val key = aesKey()
        val head = header(64)
        val full = writeBound(bytes(192), key, 64, head)   // 꽉 찬 청크 셋
        val size = 12 + 64 + 16
        val swapped = full.copyOf().also {
            System.arraycopy(full, size, it, 0, size)      // 1번을 0번 자리로
            System.arraycopy(full, 0, it, size, size)      // 0번을 1번 자리로
        }
        try {
            readBound(swapped, key, 64, head)
            fail("청크 순서를 바꿨는데 통과했다")
        } catch (e: AEADBadTagException) {
            // 색인이 묶여 있어야 여기 온다
        }
    }

    /** 같은 청크를 한 번 더 끼운 파일 — 색인이 묶이지 않으면 평문이 늘어난다. */
    @Test
    fun chunks_duplicatedChunkIsRejected() {
        val key = aesKey()
        val head = header(64)
        val full = writeBound(bytes(192), key, 64, head)
        val size = 12 + 64 + 16
        val spliced = ByteArrayOutputStream().apply {
            write(full, 0, size)
            write(full, 0, size)                          // 0번을 두 번
            write(full, size, full.size - size)
        }.toByteArray()
        try {
            readBound(spliced, key, 64, head)
            fail("같은 청크가 두 번 들어갔는데 통과했다")
        } catch (e: AEADBadTagException) {
            // 기대한 자리
        }
    }

    /**
     * 헤더 한 바이트만 고친 파일. 헤더는 청크 밖이라 **종전에는 태그가 아무 말도 하지 않았다** —
     * `chunkSize` 같은 값은 범위 검사만 통과하면 그대로 믿겼다.
     */
    @Test
    fun chunks_headerTamperingBreaksEveryTag() {
        val key = aesKey()
        val head = header(64)
        val full = writeBound(bytes(100), key, 64, head)
        val tampered = head.copyOf().also { it[7] = (it[7] + 1).toByte() }
        try {
            readBound(full, key, 64, tampered)
            fail("헤더가 바뀌었는데 청크가 열렸다")
        } catch (e: AEADBadTagException) {
            // 기대한 자리
        }
    }

    /** 옛 형식은 묶이지 않는다 — 그 경로가 살아 있어야 옛 백업이 열린다. */
    @Test
    fun chunks_legacyUnboundStreamStillReads() {
        val key = aesKey()
        val plain = bytes(300)
        assertArrayEquals(plain, readBound(legacyChunks(plain, key, 64), key, 64, null))
    }

    // ── ⑦ 형식 상수 — 바뀌면 옛 백업이 안 열린다 ──

    /**
     * 이 값들은 **디스크에 이미 쓰인 파일이 기대는 값**이다. 고치면 그 전에 뜬 백업이 전부
     * 안 열리므로, 바꾸려면 형식 판올림(새 매직)을 함께 해야 한다 — 그 사실을 여기서 못박는다.
     *
     * **매직 넷을 전부 든다**(B-248) — 옛 둘은 *읽는 길이 살아 있다*는 계약이라 새 둘과
     * 똑같이 얼어 있어야 한다.
     */
    @Test
    fun formatConstants_areFrozen() {
        assertEquals(12, BackupChunkFormat.GCM_IV_LENGTH)
        assertEquals(128, BackupChunkFormat.GCM_TAG_LENGTH)
        assertEquals(16, BackupChunkFormat.GCM_TAG_BYTES)
        assertEquals(16, BackupChunkFormat.SALT_LENGTH)
        assertEquals(1024 * 1024, BackupChunkFormat.CHUNK_SIZE)
        assertEquals(8, BackupChunkFormat.FORMAT_HEADER_SIZE)
        assertEquals(28, BackupChunkFormat.PORTABLE_HEADER_SIZE)
        assertEquals("AES/GCM/NoPadding", BackupChunkFormat.TRANSFORMATION)
        assertEquals("PBKDF2WithHmacSHA256", BackupChunkFormat.PBKDF2_ALGORITHM)
        assertArrayEquals("NCB3".toByteArray(), BackupChunkFormat.deviceMagicV3())
        assertArrayEquals("NCP2".toByteArray(), BackupChunkFormat.portableMagicV2())
        assertArrayEquals("NCB2".toByteArray(), BackupChunkFormat.deviceMagicV2())
        assertArrayEquals("NCP1".toByteArray(), BackupChunkFormat.portableMagicV1())
        // 시험이 빠르라고 낮춘 것은 인자이지 기본값이 아니다(클래스 주석).
        assertEquals(200_000, BackupChunkFormat.PBKDF2_ITERATIONS)
        assertEquals(6, BackupChunkFormat.MIN_PASSPHRASE_LENGTH)
    }

    /** 매직은 부를 때마다 새 배열 — 고쳐도 다음 부름이 오염되지 않는다. */
    @Test
    fun formatMagic_isNotSharedMutableState() {
        BackupChunkFormat.deviceMagicV3()[0] = 0
        assertNotEquals(0.toByte(), BackupChunkFormat.deviceMagicV3()[0])
        BackupChunkFormat.portableMagicV2()[0] = 0
        assertNotEquals(0.toByte(), BackupChunkFormat.portableMagicV2()[0])
        BackupChunkFormat.deviceMagicV2()[0] = 0
        assertNotEquals(0.toByte(), BackupChunkFormat.deviceMagicV2()[0])
        BackupChunkFormat.portableMagicV1()[0] = 0
        assertNotEquals(0.toByte(), BackupChunkFormat.portableMagicV1()[0])
    }
}
