package com.novelcharacter.app.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 경로 대조 단일 소스의 계약(B-103 D2).
 *
 * 핵심은 둘이다: **던지지 않는다**(메인스레드 bind에서 부른다) · **"지정 없음"은 무엇과도
 * 같지 않다**(빈 것끼리 같다고 하면 대표 판정이 통째로 틀린다).
 */
class ImagePathMatchTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"

    @Test fun canonical_normalizesDotSegments() {
        assertEquals("$dir/a.jpg", ImagePathMatch.canonical("$dir/./a.jpg"))
        assertEquals("$dir/a.jpg", ImagePathMatch.canonical("$dir/sub/../a.jpg"))
        assertEquals("$dir/a.jpg", ImagePathMatch.canonical("$dir//a.jpg"))
    }

    @Test fun canonical_trimsAndFoldsBlankToEmpty() {
        assertEquals("", ImagePathMatch.canonical(null))
        assertEquals("", ImagePathMatch.canonical(""))
        assertEquals("", ImagePathMatch.canonical("    "))
        assertEquals(ImagePathMatch.canonical("$dir/a.jpg"), ImagePathMatch.canonical("  $dir/a.jpg  "))
    }

    @Test fun canonical_neverThrows() {
        // 널바이트·초장문·상대경로 — 외부에서 편집된 엑셀이 무엇을 넣든 죽지 않아야 한다.
        for (bad in listOf("a\u0000b", "x".repeat(5000), "relative/path.jpg", "~/a.jpg", "\\\\server\\a.jpg")) {
            ImagePathMatch.canonical(bad)
        }
    }

    @Test fun same_matchesAcrossNotations() {
        assertTrue(ImagePathMatch.same("$dir/a.jpg", "$dir/./a.jpg"))
        assertFalse(ImagePathMatch.same("$dir/a.jpg", "$dir/b.jpg"))
    }

    @Test fun same_treatsEmptyAsMatchingNothing() {
        assertFalse(ImagePathMatch.same(null, null))
        assertFalse(ImagePathMatch.same("", ""))
        assertFalse(ImagePathMatch.same("", "$dir/a.jpg"))
        assertFalse(ImagePathMatch.same("$dir/a.jpg", null))
    }

    @Test fun indexIn_findsFirstMatchOrMinusOne() {
        val paths = listOf("$dir/a.jpg", "$dir/b.jpg", "$dir/c.jpg")
        assertEquals(1, ImagePathMatch.indexIn(paths, "$dir/./b.jpg"))
        assertEquals(-1, ImagePathMatch.indexIn(paths, "$dir/z.jpg"))
        assertEquals(-1, ImagePathMatch.indexIn(paths, null))
        assertEquals(-1, ImagePathMatch.indexIn(emptyList(), "$dir/a.jpg"))
    }

    @Test fun containedIn_agreesWithIndexIn() {
        val paths = listOf("$dir/a.jpg")
        assertTrue(ImagePathMatch.containedIn(paths, "$dir/a.jpg"))
        assertFalse(ImagePathMatch.containedIn(paths, "$dir/b.jpg"))
        assertFalse(ImagePathMatch.containedIn(paths, ""))
    }

    // ── isInside — 봉쇄 가드 (B-141) ──
    //
    // 이 판정의 소비처는 **파일 바이트를 제3자 서버로 올려도 되는가**라, 위 대조 함수들과
    // 실패 처분이 반대다(모르면 막는다). 아래 시험은 그 반대됨까지 함께 잠근다.

    @Test fun isInside_acceptsFilesUnderTheRoot() {
        val root = File(dir)
        assertTrue(ImagePathMatch.isInside("$dir/a.jpg", root))
        assertTrue(ImagePathMatch.isInside("$dir/sub/a.jpg", root))
        assertTrue(ImagePathMatch.isInside("$dir/./a.jpg", root))
        assertTrue(ImagePathMatch.isInside("  $dir/a.jpg  ", root))
    }

    @Test fun isInside_rejectsSiblingSharingThePrefix() {
        // `startsWith(root)`만 쓰면 통과하는 자리다 — 경계 문자를 붙여야 갈린다.
        // 형제 디렉터리 이름이 접두어를 공유하는 것은 실제로 흔하다(`files` / `files_backup`).
        val root = File(dir)
        assertFalse(ImagePathMatch.isInside("${dir}_backup/a.jpg", root))
        assertFalse(ImagePathMatch.isInside("${dir}2/a.jpg", root))
    }

    @Test fun isInside_rejectsEscapeByDotSegments() {
        // 엑셀 '이미지' 열로 외부에서 들어오는 값이라 `..`은 실재하는 입력이다.
        val root = File(dir)
        assertFalse(ImagePathMatch.isInside("$dir/../../../etc/passwd", root))
        assertFalse(ImagePathMatch.isInside("/sdcard/Download/a.jpg", root))
    }

    @Test fun isInside_rejectsTheRootItselfAndBlanks() {
        val root = File(dir)
        // 루트 자신은 '안의 파일'이 아니다 — 디렉터리를 통째로 실을 자리는 없다.
        assertFalse(ImagePathMatch.isInside(dir, root))
        assertFalse(ImagePathMatch.isInside(null, root))
        assertFalse(ImagePathMatch.isInside("", root))
        assertFalse(ImagePathMatch.isInside("   ", root))
    }

    @Test fun isInside_refusesWhenRootIsUnknown() {
        // 대조 함수들은 실패하면 원본을 들고 가지만(버리지 않는 규약), 봉쇄는 반대다 —
        // 여기서 true를 내면 그것이 곧 유출이다.
        assertFalse(ImagePathMatch.isInside("$dir/a.jpg", null))
    }

    @Test fun isInside_rejectsSymlinkPointingOutOfTheRoot() {
        // 가드가 canonical을 쓰는 **유일한 이유**가 이것이다. 이름만 보면 루트 안이다.
        val root = Files.createTempDirectory("files").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        val secret = File(outside, "secret.jpg").apply { writeText("x") }
        val plain = File(root, "plain.jpg").apply { writeText("x") }
        val link = File(root, "link.jpg")
        try {
            Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (_: Exception) {
            return // 심볼릭 링크를 못 만드는 파일 시스템이면 이 축은 건너뛴다
        }
        assertTrue(ImagePathMatch.isInside(plain.path, root))
        assertFalse(ImagePathMatch.isInside(link.path, root))
    }

    @Test fun isInside_neverThrows() {
        val root = File(dir)
        for (bad in listOf("a\u0000b", "x".repeat(5000), "relative/path.jpg", "~/a.jpg", "\\\\server\\a.jpg")) {
            assertFalse(ImagePathMatch.isInside(bad, root))
        }
    }

    // ===== 원문 지름길이 답을 바꾸지 않는가 (2026.08.23) =====

    @Test fun indexIn_keepsTheEarliestMatchEvenWhenALaterOneMatchesLiterally() {
        // **지름길이 차례를 바꾸면 안 된다.** 앞자리가 *다른 표기로 같은 파일*을 가리키면
        // 답은 그 앞자리다 — 목록을 통째로 원문 대조한 뒤 정규로 되짚으면 여기서 갈린다.
        assertEquals(0, ImagePathMatch.indexIn(listOf("$dir/./same.jpg", "$dir/same.jpg"), "$dir/same.jpg"))
    }

    @Test fun indexIn_findsTheLiteralMatchIgnoringSurroundingSpace() {
        assertEquals(1, ImagePathMatch.indexIn(listOf("$dir/b.jpg", "$dir/a.jpg"), " $dir/a.jpg "))
    }

    @Test fun indexIn_stillMatchesThroughCanonicalWhenTheTextDiffers() {
        assertEquals(0, ImagePathMatch.indexIn(listOf("$dir/sub/../a.jpg"), "$dir/a.jpg"))
    }

    @Test fun indexIn_missIsStillMinusOne() {
        assertEquals(-1, ImagePathMatch.indexIn(listOf("$dir/c.jpg"), "$dir/none.jpg"))
        assertEquals(-1, ImagePathMatch.indexIn(listOf("$dir/c.jpg"), null))
        assertEquals(-1, ImagePathMatch.indexIn(listOf("$dir/c.jpg"), "   "))
        assertEquals(-1, ImagePathMatch.indexIn(emptyList(), "$dir/c.jpg"))
    }

    @Test fun same_shortcutAgreesWithCanonical() {
        assertTrue(ImagePathMatch.same("$dir/d.jpg", " $dir/d.jpg "))
        assertTrue(ImagePathMatch.same("$dir/d.jpg", "$dir/./d.jpg"))
        assertFalse(ImagePathMatch.same("$dir/d.jpg", "$dir/e.jpg"))
        assertFalse(ImagePathMatch.same("", ""))
        assertFalse(ImagePathMatch.same(null, null))
    }

    // ── isDeletableImage — 이미지 삭제 문지기 ──
    // 엑셀 '이미지' 칸의 절대경로는 그대로 저장되므로, 지우는 자리는 앱이 만든 그림만 지워야 한다.

    private val pkg = "/data/user/0/com.novelcharacter.app"

    @Test fun isDeletableImage_acceptsManagedImagesAtTheRoot() {
        val root = File(dir)
        assertTrue(ImagePathMatch.isDeletableImage("$dir/char_1.jpg", root))
        assertTrue(ImagePathMatch.isDeletableImage("$dir/universe_1.png", root))
        assertTrue(ImagePathMatch.isDeletableImage("$dir/novel_1.webp", root))
        assertTrue(ImagePathMatch.isDeletableImage("$dir/img_1.jpg", root))
        assertTrue(ImagePathMatch.isDeletableImage("$dir/./char_1.jpg", root))
        // 복원은 원래 확장자를 잇는다 — 접두로 판정하므로 확장자가 달라도 지운다.
        assertTrue(ImagePathMatch.isDeletableImage("$dir/char_1.gif", root))
    }

    @Test fun isDeletableImage_rejectsAppFilesThatAreNotImages() {
        val root = File(dir)
        assertFalse(ImagePathMatch.isDeletableImage("$pkg/databases/novel_character_database", root))
        assertFalse(ImagePathMatch.isDeletableImage("$pkg/shared_prefs/settings.xml", root))
        assertFalse(ImagePathMatch.isDeletableImage("$dir/backups/char_backup.zip", root))
        assertFalse(ImagePathMatch.isDeletableImage("$dir/crash_log.txt", root))
        assertFalse(ImagePathMatch.isDeletableImage("$dir/../databases/char_x.db", root))
        assertFalse(ImagePathMatch.isDeletableImage("${dir}_backup/char_1.jpg", root))
        assertFalse(ImagePathMatch.isDeletableImage("/sdcard/DCIM/char_1.jpg", root))
    }

    @Test fun isDeletableImage_blocksWhenUnknown() {
        assertFalse(ImagePathMatch.isDeletableImage("$dir/char_1.jpg", null))
        assertFalse(ImagePathMatch.isDeletableImage("", File(dir)))
        assertFalse(ImagePathMatch.isDeletableImage(null, File(dir)))
        assertFalse(ImagePathMatch.isDeletableImage("   ", File(dir)))
    }

    @Test fun isDeletableImage_followsSymlinks() {
        val root = Files.createTempDirectory("files").toFile()
        val outside = Files.createTempDirectory("databases").toFile()
        try {
            val target = File(outside, "app.db").apply { writeText("db") }
            val link = File(root, "char_link.jpg")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            // 이름은 그림이지만 실체는 밖의 파일이다 — 정규화 후 판정하므로 막힌다.
            assertFalse(ImagePathMatch.isDeletableImage(link.path, root))
            val real = File(root, "char_real.jpg").apply { writeText("x") }
            assertTrue(ImagePathMatch.isDeletableImage(real.path, root))
        } finally {
            root.deleteRecursively(); outside.deleteRecursively()
        }
    }
}
