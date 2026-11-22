package com.novelcharacter.app.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이미지 형식 판별(매직 바이트)과 이름 바로잡기의 계약. */
class ImageFileFormatTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun pad(b: ByteArray, n: Int = 16) = b.copyOf(maxOf(n, b.size))

    @Test fun sniffsCommonFormats() {
        assertEquals(ImageFileFormat.Kind.JPEG, ImageFileFormat.sniff(pad(bytes(0xFF, 0xD8, 0xFF, 0xE0))))
        assertEquals(ImageFileFormat.Kind.PNG, ImageFileFormat.sniff(pad(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))))
        assertEquals(ImageFileFormat.Kind.WEBP, ImageFileFormat.sniff(pad(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBPVP8 "))))
        assertEquals(ImageFileFormat.Kind.GIF, ImageFileFormat.sniff(pad(ascii("GIF89a"))))
        assertEquals(ImageFileFormat.Kind.GIF, ImageFileFormat.sniff(pad(ascii("GIF87a"))))
        assertEquals(ImageFileFormat.Kind.BMP, ImageFileFormat.sniff(pad(ascii("BM"))))
    }

    @Test fun sniffsIsoMediaBrands() {
        fun ftyp(brand: String) = pad(bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii(brand))
        assertEquals(ImageFileFormat.Kind.HEIF, ImageFileFormat.sniff(ftyp("heic")))
        assertEquals(ImageFileFormat.Kind.HEIF, ImageFileFormat.sniff(ftyp("mif1")))
        assertEquals(ImageFileFormat.Kind.AVIF, ImageFileFormat.sniff(ftyp("avif")))
        // 동영상(mp4)은 그림이 아니다
        assertNull(ImageFileFormat.sniff(ftyp("isom")))
    }

    @Test fun unknownOrShortHeadersAreNull() {
        assertNull(ImageFileFormat.sniff(ByteArray(0)))
        assertNull(ImageFileFormat.sniff(bytes(0xFF, 0xD8)))
        assertNull(ImageFileFormat.sniff(pad(ascii("%PDF-1.7"))))
        assertNull(ImageFileFormat.sniff(ascii("RIFF")))
    }

    @Test fun renamesOnlyWhenTheExtensionDisagrees() {
        val png = ImageFileFormat.Kind.PNG
        assertEquals("char_1.png", ImageFileFormat.withExtensionFor("char_1.jpg", png))
        assertEquals("char_1.png", ImageFileFormat.withExtensionFor("char_1.png", png))
        assertEquals("a-01.0123456789ab.png", ImageFileFormat.withExtensionFor("a-01.0123456789ab.jpg", png))
        // 같은 형식의 다른 표기는 그대로 둔다
        assertEquals("a.jpeg", ImageFileFormat.withExtensionFor("a.jpeg", ImageFileFormat.Kind.JPEG))
        assertEquals("a.HEIF", ImageFileFormat.withExtensionFor("a.HEIF", ImageFileFormat.Kind.HEIF))
        // 확장자가 없으면 붙이고, 모르는 내용이면 손대지 않는다
        assertEquals("noext.gif", ImageFileFormat.withExtensionFor("noext", ImageFileFormat.Kind.GIF))
        assertEquals("char_1.jpg", ImageFileFormat.withExtensionFor("char_1.jpg", null))
    }

    @Test fun everyKindsExtensionIsAnImageExtension() {
        for (kind in ImageFileFormat.Kind.values()) {
            assertTrue(kind.name, kind.extension in ImageFileFormat.EXTENSIONS)
            assertEquals(kind, ImageFileFormat.kindOfExtension(kind.extension))
        }
        assertTrue(ImageFileFormat.hasImageExtension("char_1.PNG"))
        assertFalse(ImageFileFormat.hasImageExtension("crash_log.txt"))
        assertFalse(ImageFileFormat.hasImageExtension("jpg"))
    }

    @Test fun sniffsFilesAndNeverThrows() {
        val dir = Files.createTempDirectory("fmt").toFile()
        try {
            val f = File(dir, "x.jpg").apply { writeBytes(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)) }
            assertEquals(ImageFileFormat.Kind.PNG, ImageFileFormat.sniff(f))
            assertNull(ImageFileFormat.sniff(File(dir, "missing.jpg")))
            assertNull(ImageFileFormat.sniff(File(dir, "empty.jpg").apply { writeBytes(ByteArray(0)) }))
        } finally {
            dir.deleteRecursively()
        }
    }
}
