package com.novelcharacter.app.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * **백업 암호 형식의 순수 계층** (B-230 ⓐ, 2026.08.16) — 다섯 형식의 틀 짜기와 읽기를 전부
 * 든다. `javax.crypto`만 쓰고 `android.*`를 하나도 import 하지 않는 것이 이 파일의 존재 이유다.
 *
 * ## 왜 갈랐는가 — 이 저장소가 이미 두 번 쓴 처방이다
 *
 * [BackupEncryptor]는 426줄에 **감사도 시험도 0**이었다(엑셀 전수 감사 B-230 ⓐ). 닿지 못한
 * 이유는 로직이 어려워서가 아니라 파일 머리의 `android.security.keystore` import 둘 때문이다
 * — 그 둘이 있으면 순수 JVM 하네스가 이 파일을 **컴파일조차 못 한다**(스텁이 없다).
 * 그래서 *키를 어디서 얻는가*(AndroidKeyStore — 하네스 밖)와 *형식을 어떻게 쓰고 읽는가*
 * (순수)를 갈랐다. 같은 처방을 `DropdownListSheet`가 `ExcelExporter`에서, `ImportLookupIndex`가
 * `ExcelImportService`에서 이미 썼다.
 *
 * **복호화 결함 하나가 전 백업 복원 불가**라 이 계층이 로컬에서 잠기는 것의 값이 특히 크다.
 *
 * ## 형식 — **쓰는 것은 둘, 읽는 것은 다섯**
 *
 * ```
 * NCB3 : MAGIC "NCB3"(4B) + chunkSize(4B)                              + 청크*   ← 현행(기기 키)
 * NCP2 : MAGIC "NCP2"(4B) + iterations(4B) + salt(16B) + chunkSize(4B) + 청크*   ← 현행(패스프레이즈)
 * NCB2 : MAGIC "NCB2"(4B) + chunkSize(4B)                              + 청크*   ← 읽기 전용
 * NCP1 : MAGIC "NCP1"(4B) + iterations(4B) + salt(16B) + chunkSize(4B) + 청크*   ← 읽기 전용
 * v1   : IV(12B) + 전체 암호문 + GCM 태그                                          ← 읽기 전용(전체 로드)
 * 청크 : IV(12B) + 암호문 + GCM 태그(16B)
 * AAD  : 헤더 전체 || 색인(8B, BE) || 마지막인가(1B)      ← NCB3·NCP2만. 옛 셋은 묶지 않는다
 * ```
 *
 * **옛 셋을 계속 읽는 것이 이 판올림의 조건이다**(R-2 취지) — 읽는 경로를 지우면 그 전에 뜬
 * 백업이 **전부 안 열린다.** 새로 쓰는 것은 NCB3·NCP2뿐이고, 옛 셋의 쓰는 쪽은 없다.
 *
 * ## ⚠️ 불변식 ① — **마지막을 뺀 모든 청크는 정확히 `chunkSize`다**
 *
 * 청크에는 **길이 칸이 없다.** 읽는 쪽은 `chunkSize + 16`을 통째로 집어 그것을 한 청크로 보므로,
 * 쓰는 쪽이 중간에 **짧은 청크**를 하나라도 흘리면 읽는 쪽은 그 뒤 청크의 IV까지 함께 삼켜
 * **GCM 태그 검증이 깨지고 그 지점부터 파일 전체가 복원 불가**가 된다. 그리고 그 파손은
 * *백업을 뜨는 시점에는 아무 증상이 없다* — 몇 달 뒤 복원할 때 처음 드러난다.
 *
 * 그래서 [writeChunks]는 `InputStream.read`의 반환값을 그대로 믿지 않고 [readFully]로 채운다.
 * `read(buffer)`는 **버퍼를 채운다고 약속하지 않는다** — 규격이 "1개 이상 maxSize 이하"만
 * 보장하고, FUSE로 얹힌 외부 저장소(안드로이드 11+의 `/sdcard`)는 실제로 짧게 준다.
 * 종전 구현은 `fis.read(buffer)` 한 번의 반환값을 그대로 청크 길이로 삼았다(B-230 ⓐ에서 수리).
 *
 * ## ⚠️ 불변식 ② — **파일은 자기가 끝났다는 것을 말한다** (B-248, 2026.08.16)
 *
 * GCM은 *각 청크가 진짜인가*만 답한다. **청크가 전부 있는가·순서가 맞는가는 아무도 안 봤다** —
 * NCB2·NCP1에서는 청크마다 `[IV][암호문+태그]`가 전부여서, 뒤쪽 청크가 통째로 잘린 파일
 * (전송 중단·저장 공간 소진·동기화 중 복사)을 [readChunks]가 EOF로 읽고 **정상 종료해 짧은
 * 평문을 냈다.** 그것이 사고로 안 이어지던 것은 하류의 zip 열기가 잡아 주기 때문이고,
 * **그것은 이 계층의 보장이 아니라 운이다** — 평문이 zip이 아닌 용도로 쓰이는 순간 그 그물이 없다.
 *
 * 그래서 NCB3·NCP2는 **청크 색인과 "마지막인가"를 헤더째 AAD로 묶는다**([chunkAad]).
 * 잘림·재배열·중복·삽입·헤더 조작이 전부 태그 검증에서 죽는다.
 *
 * **총 청크 수를 헤더에 싣지 않은 것은 일부러다.** [writeChunks]는 `InputStream`을 흘려 쓰는
 * 함수라 총수를 미리 알 수 없고, 부르는 쪽의 `File.length()`로 대신 세면 *선언한 수와 실제로
 * 쓴 수가 갈리는* 새 실패 축이 생긴다 — **쓰는 시점에 증상이 없는 파손**이라 B-230 ⓐ가 고친
 * 것과 똑같은 부류다. 마지막 표시는 그 위험 없이 같은 것을 잡는다.
 */
object BackupChunkFormat {

    /**
     * **뒤가 잘렸다는 것이 증명된** 백업. 태그 불일치(`AEADBadTagException`)와 갈라 두는 이유는
     * 화면이 그 둘에 서로 다르게 답하기 때문이다 — 태그 불일치는 *암호가 틀렸다*로 읽혀
     * 암호 재입력으로 되돌아가는데(`SettingsFragment.restoreFromPortableFile`), 잘린 파일에
     * 그렇게 답하면 사용자는 **맞는 암호를 넣고도 영영 되묻힌다.**
     *
     * `IOException`을 무는 것이 그 갈래를 잇는 자리다 — 화면의 일반 오류 갈래로 간다.
     */
    class TruncatedBackupException(message: String) : IOException(message)

    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH = 128                      // bits
    const val GCM_TAG_BYTES = GCM_TAG_LENGTH / 8        // 16 bytes
    const val GCM_IV_LENGTH = 12                        // bytes

    /** v2(NCB2) 기본 청크 크기. 피크 메모리는 청크 하나 + 그 복호화 결과다. */
    const val CHUNK_SIZE = 1024 * 1024
    const val FORMAT_HEADER_SIZE = 8                    // MAGIC(4) + chunkSize(4)

    /** v1 레거시 상한 — 전체 로드 방식이라 메모리 보호용. */
    const val MAX_LEGACY_FILE_SIZE = 256L * 1024 * 1024

    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 200_000
    const val MAX_PBKDF2_ITERATIONS = 5_000_000         // 헤더 조작으로 인한 CPU 소진 방지
    const val SALT_LENGTH = 16
    const val MIN_PASSPHRASE_LENGTH = 6

    /** MAGIC(4) + iterations(4) + salt(16) + chunkSize(4). NCP1·NCP2가 같은 배치다. */
    const val PORTABLE_HEADER_SIZE = 4 + 4 + SALT_LENGTH + 4

    /**
     * 매직은 **부를 때마다 새 배열로 준다.**
     *
     * 종전 `BackupEncryptor`에서는 `private val`이라 밖에서 건드릴 수 없었다. 여기서 공개로
     * 올린 것은 **시험이 "이 네 바이트는 이미 디스크에 쓰여 있다"를 못박아야 하기 때문**이고
     * (`formatConstants_areFrozen`), 공개된 `val ByteArray`는 그 순간부터 **object의 공유 가변
     * 상태**가 된다 — 누가 한 바이트만 고쳐도 그 프로세스의 모든 백업이 조용히 다른 형식이 된다.
     * 즉 함수인 이유는 공개했기 때문이지, 종전 구현에 그 결함이 있었기 때문이 아니다.
     *
     * **이름에 판을 적는다**(B-248) — 매직이 넷이 된 뒤로 `formatMagic`·`portableMagic`이라는
     * 이름은 *어느 판인가*를 말하지 못한다. 옛 이름을 그대로 두면 다음 사람이 새로 쓰는 자리에
     * 읽기 전용 매직을 넣고도 컴파일이 통과한다.
     */
    fun deviceMagicV3(): ByteArray = byteArrayOf(0x4E, 0x43, 0x42, 0x33)    // "NCB3" — 현행

    fun portableMagicV2(): ByteArray = byteArrayOf(0x4E, 0x43, 0x50, 0x32)  // "NCP2" — 현행

    /** 읽기 전용 — 이 매직으로 **새로 쓰지 않는다.** 지우면 그 전에 뜬 백업이 전부 안 열린다. */
    fun deviceMagicV2(): ByteArray = byteArrayOf(0x4E, 0x43, 0x42, 0x32)    // "NCB2"

    /** 읽기 전용 — 위와 같다. */
    fun portableMagicV1(): ByteArray = byteArrayOf(0x4E, 0x43, 0x50, 0x31)  // "NCP1"

    // ──────────────────────────────────────────────────────────────────────
    // 청크 틀 — 이 파일의 심장. 쓰기와 읽기가 서로의 거울이어야 한다.
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 청크 하나가 묶이는 것 — **헤더 전체 · 몇 번째인가 · 마지막인가.**
     *
     * 헤더를 통째로 넣는 것이 요점이다. 매직이 들어가 형식이 갈리고, `chunkSize`가 들어가
     * 틀이 갈리며, NCP는 salt·iterations까지 갈린다 — 한 바이트라도 고쳐지면 **모든 청크의
     * 태그가 죽는다.** 색인은 재배열·중복·삽입을 막고, 마지막 표시는 **잘림**을 막는다.
     *
     * 부르는 쪽은 *디스크에서 읽은 그 바이트 배열*을 그대로 넘긴다(다시 짜지 않는다) —
     * 다시 짜면 그 코드가 디스크와 갈리는 순간 옛 백업이 조용히 안 열린다.
     */
    private fun chunkAad(header: ByteArray, index: Long, isFinal: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 8 + 1)
            .put(header)
            .putLong(index)
            .put(if (isFinal) 1 else 0)
            .array()

    /**
     * 뒤에 한 바이트라도 남았는가 — **되돌려 놓고 답한다.**
     *
     * 꽉 찬 청크는 그것만으로 마지막인지 알 수 없다(입력 길이가 [chunkSize][CHUNK_SIZE]의
     * 배수면 마지막 청크도 꽉 찬다). 쓰는 쪽과 읽는 쪽이 **같은 방법으로** 답해야
     * 마지막 표시가 서로 맞는다.
     */
    private fun hasMore(input: PushbackInputStream): Boolean {
        val probe = input.read()
        if (probe == -1) return false
        input.unread(probe)
        return true
    }

    /**
     * 청크 단위로 암호화해 [out]에 쓴다. 청크마다 새 IV로 `[IV(12B)][암호문+GCM 태그]`.
     *
     * **마지막을 뺀 모든 청크가 정확히 [chunkSize]임을 이 함수가 보장한다** — 클래스 주석의
     * 불변식 ①이고, 그래서 [readFully]로 버퍼를 채운 뒤에만 청크를 닫는다.
     *
     * **그리고 마지막 청크가 자기가 마지막임을 말한다** — 불변식 ②. 빈 입력도 예외가 아니라
     * *빈 마지막 청크 하나*를 쓴다(28바이트). 그래서 **헤더만 남기고 잘린 파일과 빈 백업이
     * 갈린다** — 안 그러면 뒤가 통째로 날아간 파일이 "빈 백업"으로 조용히 통과한다.
     *
     * @param boundHeader 이 청크들이 묶일 헤더 — **디스크에 쓴 그 바이트 그대로**([chunkAad]).
     * @param onProgress 지금까지 읽은 **입력(평문) 바이트** 누적.
     */
    fun writeChunks(
        input: InputStream,
        out: OutputStream,
        key: SecretKey,
        boundHeader: ByteArray,
        chunkSize: Int = CHUNK_SIZE,
        onProgress: ((Long) -> Unit)? = null
    ) {
        require(chunkSize > 0) { "청크 크기가 0 이하입니다: $chunkSize" }
        val src = PushbackInputStream(input, 1)
        var consumed = 0L
        var index = 0L
        val buffer = ByteArray(chunkSize)
        while (true) {
            // read() 한 번이 아니라 readFully — 짧은 읽기가 청크 경계를 어긋내면
            // 그 백업은 복원 시점에 통째로 죽는다(불변식 ①).
            val bytesRead = readFully(src, buffer)
            // 덜 찼으면 EOF를 이미 만난 것이고, 꽉 찼으면 아직 모른다 — 한 바이트만 본다.
            val isFinal = bytesRead < buffer.size || !hasMore(src)
            consumed += bytesRead
            onProgress?.invoke(consumed)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(chunkAad(boundHeader, index, isFinal))
            val iv = cipher.iv                          // 12 bytes, 청크마다 새 IV
            val encrypted = cipher.doFinal(buffer, 0, bytesRead)

            out.write(iv)
            out.write(encrypted)                        // 암호문 + GCM 태그
            index++
            if (isFinal) break
        }
    }

    /**
     * 청크 단위 복호화. 청크마다 GCM 태그를 검증하고, [boundHeader]가 있으면 **색인과
     * 마지막 표시까지** 함께 검증한다(불변식 ②).
     * 피크 메모리: 암호문 청크 + 복호화 결과.
     *
     * @param boundHeader 청크가 묶인 헤더 — **`null`이면 옛 형식**(NCB2·NCP1)이라 묶이지 않는다.
     *   옛 파일을 읽는 자리에서만 `null`이고, 새로 쓰는 자리에는 언제나 값이 있다.
     * @param consumedBefore 헤더로 이미 읽은 바이트 — [onProgress]가 **입력 파일 기준**
     *   누적을 내도록 맞춘다(총량은 파일 길이다. R-26 · B-51).
     */
    fun readChunks(
        input: InputStream,
        out: OutputStream,
        chunkSize: Int,
        key: SecretKey,
        boundHeader: ByteArray?,
        onProgress: ((Long) -> Unit)? = null,
        consumedBefore: Long = 0L
    ) {
        val maxEncryptedChunkSize = chunkSize + GCM_TAG_BYTES
        val src = PushbackInputStream(input, 1)
        // 청크는 이미 MB급이라 청크마다 보고해도 갱신이 작업보다 비싸지지 않는다.
        var consumed = consumedBefore
        var index = 0L
        var sawFinal = false
        while (true) {
            val iv = ByteArray(GCM_IV_LENGTH)
            val ivRead = readFully(src, iv)
            if (ivRead == 0) break                      // EOF
            require(ivRead == GCM_IV_LENGTH) { "청크 IV가 잘렸습니다: ${ivRead}바이트만 읽혔습니다" }

            // 마지막 청크는 chunkSize보다 짧을 수 있다.
            val encryptedChunk = readChunkData(src, maxEncryptedChunkSize)
            require(encryptedChunk.isNotEmpty()) { "암호화된 청크가 비어 있습니다" }

            // 옛 형식에는 마지막 표시가 없다 — 그쪽은 종전대로 EOF가 끝을 말한다.
            // 한 바이트 엿보기도 그때는 하지 않는다(옛 경로에 새 비용을 얹지 않는다).
            val isFinal = boundHeader != null &&
                (encryptedChunk.size < maxEncryptedChunkSize || !hasMore(src))

            out.write(decryptChunk(encryptedChunk, iv, key, boundHeader, index, isFinal))

            consumed += ivRead + encryptedChunk.size
            onProgress?.invoke(consumed)
            index++
            if (isFinal) { sawFinal = true; break }
        }
        // 청크가 하나도 없는 NCB3/NCP2 = 헤더만 남기고 잘린 파일이다. 쓰는 쪽이 빈 입력에도
        // 마지막 청크 하나를 남기므로, 여기 닿는 길은 잘림뿐이다.
        if (boundHeader != null && !sawFinal) {
            throw TruncatedBackupException(
                "백업 파일이 잘렸습니다: 청크 ${index}개까지만 있고 마지막 청크가 없습니다."
            )
        }
    }

    /**
     * 청크 하나를 연다 — 그리고 **실패했을 때 그 이유를 증명한다.**
     *
     * 꽉 찬 청크가 파일 끝에 있으면 두 가지다: 진짜 마지막이거나, **뒤가 잘려서** 마지막이
     * 아닌 청크가 마지막 자리에 있거나. 앞엣것으로 물어 실패하면 **뒤엣것으로 되물어** 답을
     * 가른다 — 되물어 열리면 그 청크는 우리가 쓴 그 바이트이고 *뒤에 더 있었어야 한다*는
     * 뜻이므로, **잘림이 추측이 아니라 증명된다.**
     *
     * 이 갈래가 필요한 이유는 화면이 태그 불일치를 *암호가 틀렸다*로 읽기 때문이다
     * ([TruncatedBackupException]). 되묻는 비용은 **실패 경로에서만** 든다.
     */
    private fun decryptChunk(
        encryptedChunk: ByteArray,
        iv: ByteArray,
        key: SecretKey,
        boundHeader: ByteArray?,
        index: Long,
        isFinal: Boolean
    ): ByteArray {
        if (boundHeader == null) return gcmOpen(encryptedChunk, iv, key, null)
        try {
            return gcmOpen(encryptedChunk, iv, key, chunkAad(boundHeader, index, isFinal))
        } catch (e: AEADBadTagException) {
            if (!isFinal) throw e                       // 뒤가 더 있다 — 잘림이 아니다
            val authenticAsNonFinal = try {
                gcmOpen(encryptedChunk, iv, key, chunkAad(boundHeader, index, false))
                true
            } catch (_: AEADBadTagException) {
                false
            }
            if (!authenticAsNonFinal) throw e           // 진짜로 깨졌거나 키가 틀렸다
            throw TruncatedBackupException(
                "백업 파일이 잘렸습니다: ${index}번 청크는 온전하지만 마지막 청크가 아닙니다 — " +
                    "그 뒤가 모두 없습니다."
            )
        }
    }

    private fun gcmOpen(data: ByteArray, iv: ByteArray, key: SecretKey, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    // ──────────────────────────────────────────────────────────────────────
    // NCB2 (기기 키) — 키 조달만 밖에서 받는다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * v3(NCB3) 형식으로 파일을 암호화한다. 키는 부르는 쪽이 댄다.
     *
     * **헤더에 적는 크기와 실제로 자르는 크기는 반드시 같은 값이어야 한다** — 갈리면 읽는 쪽이
     * 남의 청크 경계로 집어 B-230 ⓐ가 고친 것과 **똑같은 모양의 복원 불가**가 된다. 그래서
     * 헤더 배열을 한 번 짜서 **디스크에 쓰는 것과 청크에 묶는 것이 같은 바이트**가 되게 한다
     * — 두 번 짜면 그 둘이 갈리는 순간 자기가 쓴 파일을 자기가 못 연다.
     */
    fun encryptFileWithKey(inputFile: File, outputFile: File, key: SecretKey) {
        val chunkSize = CHUNK_SIZE
        val header = ByteBuffer.allocate(FORMAT_HEADER_SIZE)
            .put(deviceMagicV3())
            .putInt(chunkSize)
            .array()
        FileOutputStream(outputFile).use { fos ->
            fos.write(header)
            FileInputStream(inputFile).use { fis ->
                writeChunks(fis, fos, key, header, chunkSize)
            }
        }
    }

    /**
     * v3/v2/v1을 자동 감지해 복호화한다. 성공한 뒤에만 [outputFile]을 확정한다(임시 파일 경유).
     *
     * **옛 둘(NCB2·v1)을 읽는 길이 여기 있다** — 지우면 그 전에 뜬 백업이 전부 안 열린다.
     * 갈리는 것은 청크를 묶느냐뿐이고 나머지 틀은 NCB2와 NCB3가 같다.
     *
     * @param keyProvider 키는 **필요한 순간에** 받는다 — 헤더가 깨져 갈래에 닿지 못하면
     *   KeyStore를 건드리지 않는다(없는 키를 만들어 두는 부작용을 피한다).
     */
    fun decryptFileWithKey(
        inputFile: File,
        outputFile: File,
        keyProvider: () -> SecretKey,
        onProgress: ((Long) -> Unit)? = null
    ) {
        val tempFile = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            val fis = FileInputStream(inputFile)
            try {
                // 최소 8바이트: v3/v2 헤더 또는 v1 IV의 앞부분
                val header = ByteArray(FORMAT_HEADER_SIZE)
                val headerRead = readFully(fis, header)
                val complete = headerRead >= FORMAT_HEADER_SIZE
                val isV3 = complete && startsWith(header, deviceMagicV3())
                val isV2 = complete && startsWith(header, deviceMagicV2())

                if (isV3 || isV2) {
                    val chunkSize = ByteBuffer.wrap(header, 4, 4).int
                    require(chunkSize in 1..CHUNK_SIZE * 2) {
                        "백업 헤더의 청크 크기가 올바르지 않습니다: $chunkSize"
                    }
                    FileOutputStream(tempFile).use { fos ->
                        readChunks(
                            fis, fos, chunkSize, keyProvider(),
                            boundHeader = if (isV3) header else null,
                            onProgress = onProgress,
                            consumedBefore = headerRead.toLong()
                        )
                    }
                } else {
                    // v1: 헤더를 이미 소비했으므로 새로 열어야 한다.
                    fis.close()
                    decryptLegacy(inputFile, tempFile, keyProvider)
                    // 레거시는 한 번의 doFinal이라 중간 보고 지점이 없다 — 끝난 사실만 알린다.
                    onProgress?.invoke(inputFile.length())
                }
            } finally {
                try { fis.close() } catch (_: Exception) { }
            }
            commit(tempFile, outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * v1 레거시 복호화. 기존 백업 파일과의 하위호환.
     * 피크 메모리: ~2x 파일 크기 (암호문 + 복호화 결과) — 그래서 [MAX_LEGACY_FILE_SIZE] 상한이 있다.
     *
     * @param keyProvider **크기 검사를 통과한 뒤에** 부른다 — 인자로 미리 받아 두면 3바이트짜리
     *   깨진 파일에도 KeyStore가 새 키를 만들어, 그 뒤의 진짜 백업이 열리지 않는다
     *   (`BackupChunkFormatTest`의 `deviceFormat_badHeaderDoesNotAskForKey`가 그 자리를 잠근다 —
     *   B-230 ⓐ의 갈라내기에서 실제로 한 번 어긋났고 그 시험이 잡았다).
     */
    private fun decryptLegacy(inputFile: File, outputFile: File, keyProvider: () -> SecretKey) {
        val fileSize = inputFile.length()
        require(fileSize > GCM_IV_LENGTH) {
            "암호화된 파일이 너무 짧습니다: 최소 ${GCM_IV_LENGTH + 1}바이트가 필요합니다"
        }
        require(fileSize <= MAX_LEGACY_FILE_SIZE) {
            "암호화된 파일이 너무 큽니다: ${fileSize / (1024 * 1024)}MB로 상한 ${MAX_LEGACY_FILE_SIZE / (1024 * 1024)}MB를 넘습니다. " +
                "최신 버전 앱에서 백업을 다시 내보내면 청크 형식으로 저장되어 이 상한을 받지 않습니다."
        }

        FileInputStream(inputFile).use { fis ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val ivRead = readFully(fis, iv)
            if (ivRead != GCM_IV_LENGTH) {
                throw java.io.IOException(
                    "IV가 잘렸습니다: ${GCM_IV_LENGTH}바이트가 필요한데 ${ivRead}바이트만 읽혔습니다. 백업 파일이 손상되었을 수 있습니다."
                )
            }

            val ciphertextSize = (fileSize - GCM_IV_LENGTH).toInt()
            val ciphertext = ByteArray(ciphertextSize)
            val totalRead = readFully(fis, ciphertext)
            if (totalRead != ciphertextSize) {
                throw java.io.IOException(
                    "암호문이 잘렸습니다: ${ciphertextSize}바이트가 필요한데 ${totalRead}바이트만 읽혔습니다. 백업 파일이 손상되었거나 잘렸을 수 있습니다."
                )
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(ciphertext)

            FileOutputStream(outputFile).use { fos -> fos.write(decrypted) }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // NCP1 (패스프레이즈) — 기기 KeyStore 비의존이라 통째로 순수하다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 파일이 이식 가능(NCP2 또는 옛 NCP1) 형식인지 검사한다. 복원 진입 시 암호 입력 여부
     * 분기에 쓴다.
     *
     * **옛 매직을 여기서 빼면 옛 이식 백업이 *기기 백업*으로 흘러간다** — 암호를 묻지 않고
     * 기기 키로 열려다 실패하고, 사용자는 "다른 기기의 백업입니다"라는 엉뚱한 안내를 받는다.
     */
    fun isPortableFormat(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(4)
                readFully(fis, magic) == 4 &&
                    (magic.contentEquals(portableMagicV2()) || magic.contentEquals(portableMagicV1()))
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 패스프레이즈에서 AES-256 키를 유도한다. */
    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * 패스프레이즈 기반 이식 가능 암호화. 기기 이전/공유용.
     *
     * @param iterations PBKDF2 반복 수 — 헤더에 실려 복호화가 그대로 따른다. 기본값을 낮추지
     *   말 것(시험이 빠르라고 낮춰 받는 자리다).
     */
    fun encryptFilePortable(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray,
        iterations: Int = PBKDF2_ITERATIONS,
        onProgress: ((Long) -> Unit)? = null
    ) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "암호는 최소 ${MIN_PASSPHRASE_LENGTH}자 이상이어야 합니다"
        }
        require(iterations in 1..MAX_PBKDF2_ITERATIONS) { "반복 횟수가 올바르지 않습니다: $iterations" }
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt, iterations)
        // 헤더에 적는 크기와 실제로 자르는 크기는 한 값에서 나온다 — 갈리면 복원이 남의
        // 청크 경계로 집는다([encryptFileWithKey]의 같은 주석).
        val chunkSize = CHUNK_SIZE
        val header = ByteBuffer.allocate(PORTABLE_HEADER_SIZE)
            .put(portableMagicV2())
            .putInt(iterations)
            .put(salt)
            .putInt(chunkSize)
            .array()
        FileOutputStream(outputFile).use { fos ->
            fos.write(header)
            FileInputStream(inputFile).use { fis ->
                writeChunks(fis, fos, key, header, chunkSize, onProgress)
            }
        }
    }

    /**
     * 이식 가능(NCP2 · 옛 NCP1) 형식 복호화. 잘못된 암호는 첫 청크의 GCM 태그 검증 실패
     * (`javax.crypto.AEADBadTagException`)로 감지된다.
     *
     * **헤더를 한 번에 읽어 그 배열을 그대로 묶는다** — 값만 뽑아 다시 짜면 그 코드가
     * 디스크와 갈리는 순간 자기가 쓴 파일을 자기가 못 연다([chunkAad]).
     *
     * @param onProgress 지금까지 읽은 입력 바이트(누적). 총량은 `inputFile.length()`다(R-26 · B-51).
     */
    fun decryptFilePortable(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray,
        onProgress: ((Long) -> Unit)? = null
    ) {
        val tempFile = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            FileInputStream(inputFile).use { fis ->
                val header = ByteArray(PORTABLE_HEADER_SIZE)
                val headerRead = readFully(fis, header)
                val isV2 = headerRead >= 4 && startsWith(header, portableMagicV2())
                require(isV2 || (headerRead >= 4 && startsWith(header, portableMagicV1()))) {
                    "이식 가능 백업 파일이 아닙니다"
                }
                require(headerRead == PORTABLE_HEADER_SIZE) { "이식 가능 백업 헤더가 손상되었습니다" }

                val iterations = ByteBuffer.wrap(header, 4, 4).int
                require(iterations in 1..MAX_PBKDF2_ITERATIONS) { "반복 횟수가 올바르지 않습니다: $iterations" }
                val salt = header.copyOfRange(8, 8 + SALT_LENGTH)
                val chunkSize = ByteBuffer.wrap(header, 8 + SALT_LENGTH, 4).int
                require(chunkSize in 1..CHUNK_SIZE * 2) { "백업 헤더의 청크 크기가 올바르지 않습니다: $chunkSize" }

                val key = deriveKey(passphrase, salt, iterations)
                FileOutputStream(tempFile).use { fos ->
                    readChunks(
                        fis, fos, chunkSize, key,
                        boundHeader = if (isV2) header else null,
                        onProgress = onProgress,
                        consumedBefore = PORTABLE_HEADER_SIZE.toLong()
                    )
                }
            }
            commit(tempFile, outputFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 바이트 배열 한 벌 (작은 값 — 설정 스냅샷 등)
    // ──────────────────────────────────────────────────────────────────────

    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)
        // [IV (12 bytes)][encrypted data + GCM tag]
        return ByteBuffer.allocate(iv.size + encryptedData.size)
            .put(iv)
            .put(encryptedData)
            .array()
    }

    fun decrypt(data: ByteArray, key: SecretKey): ByteArray {
        require(data.size > GCM_IV_LENGTH) {
            "암호화된 데이터가 너무 짧습니다: 최소 ${GCM_IV_LENGTH + 1}바이트가 필요합니다"
        }
        val buffer = ByteBuffer.wrap(data)
        val iv = ByteArray(GCM_IV_LENGTH)
        buffer.get(iv)
        val encryptedData = ByteArray(buffer.remaining())
        buffer.get(encryptedData)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encryptedData)
    }

    // ── 유틸리티 ──

    /** [bytes]가 [prefix]로 시작하는가. 매직이 넷이라 자리마다 손으로 비교하지 않는다. */
    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }

    /** 복호화가 성공한 뒤에만 결과를 제자리에 놓는다 — 반쪽 파일을 남기지 않는다. */
    private fun commit(tempFile: File, outputFile: File) {
        if (!tempFile.renameTo(outputFile)) {
            tempFile.copyTo(outputFile, overwrite = true)
            tempFile.delete()
        }
    }

    /**
     * 스트림에서 정확히 `buffer.size` 바이트를 읽거나, EOF에 도달할 때까지 읽는다.
     *
     * **`InputStream.read(b)`를 한 번 부르는 것으로 갈음하지 말 것** — 규격이 버퍼를 채운다고
     * 약속하지 않는다. 이 파일의 청크 형식은 *마지막을 뺀 모든 청크가 꽉 찼다*에 통째로
     * 기대므로, 그 약속을 지키는 자리가 여기 하나다.
     *
     * @return 실제 읽은 바이트 수 (0이면 EOF)
     */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }

    /**
     * 스트림에서 최대 [maxSize] 바이트를 읽는다.
     * 마지막 청크는 [maxSize]보다 짧을 수 있으므로, 실제 읽은 크기만큼의 배열을 반환.
     */
    private fun readChunkData(input: InputStream, maxSize: Int): ByteArray {
        val buffer = ByteArray(maxSize)
        val read = readFully(input, buffer)
        return if (read == maxSize) buffer else buffer.copyOfRange(0, read)
    }
}
