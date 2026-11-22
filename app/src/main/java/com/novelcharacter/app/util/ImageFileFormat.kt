package com.novelcharacter.app.util

import java.io.File

/**
 * 이미지 파일의 **실제 형식** — 파일 머리의 서명(매직 바이트)으로 판별한다. 순수 로직(JVM 시험이 잠근다).
 *
 * 앱은 가져온 그림을 내용과 무관하게 `<접두>_<UUID>.jpg`로 저장해 왔다. 투명도가 있어 PNG로
 * 다시 쓴 장, "원본 그대로" 설정으로 들인 PNG·WebP·GIF·HEIC가 전부 `.jpg`라는 이름을 달았다.
 * 앱 안에서는 디코더가 내용을 보므로 티가 나지 않았지만, **정리 폴더로 내보낸 사본을 PC의
 * 다른 프로그램이 열 때** 이름과 내용이 달라 열리지 않거나 투명도가 사라졌다.
 *
 * 그래서 이름을 정하는 자리(가져오기·재압축 확정)와 앱 밖으로 내보내는 자리(정리 폴더)가
 * 이 판별을 쓴다. 이미 저장된 파일의 이름은 바꾸지 않는다 — 경로가 곧 참조라서다(DB·엑셀·
 * 대결 기록이 그 경로를 든다). 그런 파일은 내보낼 때 사본의 이름만 바로잡는다.
 */
object ImageFileFormat {

    enum class Kind(val extension: String, val mimeType: String) {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp"),
        GIF("gif", "image/gif"),
        BMP("bmp", "image/bmp"),
        HEIF("heic", "image/heif"),
        AVIF("avif", "image/avif")
    }

    /**
     * 앱이 그림으로 다루는 확장자(점 없이, 소문자) — 이미지 탭 목록·초기화·폴더 받아오기가 같은 목록을 쓴다.
     * 가져오기가 이제 내용대로 이름을 달므로 [Kind]의 확장자는 전부 여기 있어야 한다(아니면 그 장이 목록에서 빠진다).
     */
    val EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    /** 판별에 읽는 머리 길이. */
    const val HEADER_BYTES = 16

    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
    private val AVIF_BRANDS = setOf("avif", "avis")

    /** 머리 바이트로 형식을 판별한다. 모르면 null. */
    fun sniff(header: ByteArray): Kind? {
        fun at(i: Int) = if (i < header.size) header[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) =
            text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Kind.JPEG
            at(0) == 0x89 && ascii(1, "PNG") && at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A -> Kind.PNG
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Kind.WEBP
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> Kind.GIF
            ascii(0, "BM") && header.size >= 14 -> Kind.BMP
            ascii(4, "ftyp") && header.size >= 12 -> {
                val brand = String(CharArray(4) { at(8 + it).toChar() }).lowercase()
                when {
                    brand in AVIF_BRANDS -> Kind.AVIF
                    brand in HEIF_BRANDS -> Kind.HEIF
                    else -> null
                }
            }
            else -> null
        }
    }

    /** 파일 머리를 읽어 판별한다. 읽지 못하면 null — 던지지 않는다. */
    fun sniff(file: File): Kind? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            sniff(buf.copyOf(read))
        }
    }.getOrNull()

    /** 이름의 확장자가 그림 확장자인가(대소문자 무시). */
    fun hasImageExtension(fileName: String): Boolean =
        fileName.contains('.') && fileName.substringAfterLast('.').lowercase() in EXTENSIONS

    /**
     * [fileName]의 확장자를 [kind]에 맞춘다. 이미 그 형식을 뜻하는 확장자면(`.jpeg`·`.heif` 포함)
     * 그대로 둔다. 확장자가 없으면 붙인다. [kind]가 null(모르는 내용)이면 손대지 않는다.
     */
    fun withExtensionFor(fileName: String, kind: Kind?): String {
        if (kind == null) return fileName
        val dot = fileName.lastIndexOf('.')
        val current = if (dot >= 0) fileName.substring(dot + 1).lowercase() else ""
        if (kindOfExtension(current) == kind) return fileName
        val stem = if (dot >= 0) fileName.substring(0, dot) else fileName
        return "$stem.${kind.extension}"
    }

    /** 확장자가 뜻하는 형식. 모르면 null. */
    fun kindOfExtension(extension: String): Kind? = when (extension.removePrefix(".").lowercase()) {
        "jpg", "jpeg" -> Kind.JPEG
        "png" -> Kind.PNG
        "webp" -> Kind.WEBP
        "gif" -> Kind.GIF
        "bmp" -> Kind.BMP
        "heic", "heif" -> Kind.HEIF
        "avif" -> Kind.AVIF
        else -> null
    }
}
