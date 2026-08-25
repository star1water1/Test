package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '이미지경로' 칸의 표기 규약 (2026.08.25).
 *
 * 회귀 대상: 이 칸이 절대경로 배열을 그대로 실어 **한 캐릭터 68장이 6,053자**를 썼고,
 * 엑셀 셀 상한(32,767자)까지 약 368장밖에 남지 않았다. 넘으면 잘린 JSON이 배열로 읽히지
 * 않아 그 열이 통째로 무용해진다. 기기별 절대경로가 파일에 실리는 것도 함께 걸렸다.
 *
 * 시험이 잠그는 것 셋: **파일명으로 나간다** · **옛 표기도 그대로 들어온다** ·
 * **디렉터리를 벗어나는 이름을 붙이지 않는다.**
 */
class ImagePathCellTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"

    /** 파일명 하나를 이 기기의 경로로 — 가져오기가 넘기는 람다를 흉내 낸다. */
    private val resolve: (String) -> String? = { name -> "$dir/$name" }

    // ── toCell ──

    @Test
    fun `절대경로 목록이 파일명 목록으로 나간다`() {
        assertEquals(
            """["char_1.jpg","img_2.jpg"]""",
            ImagePathCell.toCell("""["$dir/char_1.jpg","$dir/img_2.jpg"]""")
        )
    }

    /** 이미 파일명인 값(손으로 적은 파일)은 그대로다 — 두 번 걸어도 같다(멱등). */
    @Test
    fun `파일명 목록은 그대로 나간다`() {
        val cell = """["char_1.jpg"]"""
        assertEquals(cell, ImagePathCell.toCell(cell))
        assertEquals(cell, ImagePathCell.toCell(ImagePathCell.toCell(cell)))
    }

    /** 빈 목록은 빈 칸 — 편집 가능한(파란) 칸에 리터럴 `[]`를 세우지 않는다. */
    @Test
    fun `빈 목록과 빈 값은 빈 칸이다`() {
        assertEquals("", ImagePathCell.toCell("[]"))
        assertEquals("", ImagePathCell.toCell("[ ]"))
        assertEquals("", ImagePathCell.toCell(""))
        assertEquals("", ImagePathCell.toCell(null))
    }

    /**
     * 목록으로 안 읽히는 값은 **지우지 않고 그대로 싣는다** — 내보내기가 앱의 글자를 지우면
     * 그 백업으로는 되돌릴 수 없고, 가져오기의 "읽을 수 없어 기존 배정 유지" 경고도 안 뜬다.
     */
    @Test
    fun `목록으로 안 읽히는 값은 원문 그대로다`() {
        assertEquals("[깨진", ImagePathCell.toCell("[깨진"))
        assertEquals("/a/b.jpg", ImagePathCell.toCell("/a/b.jpg"))
    }

    /**
     * 이 판이 노린 것 — 같은 목록의 셀 길이가 실제로 줄어든다.
     *
     * 실측 재현: 사용자 파일의 가장 긴 칸이 68장에 6,053자였다. 접두
     * `/data/user/0/com.novelcharacter.app/files/`가 항목마다 42자씩 붙던 몫이 사라진다.
     */
    @Test
    fun `파일명으로 적으면 셀이 눈에 띄게 짧아진다`() {
        val paths = (1..68).joinToString(",") { """"$dir/img_9eb0e4c1-ed4d-4268-86b2-3e5dbb9e2f7$it.jpg"""" }
        val long = "[$paths]"
        val short = ImagePathCell.toCell(long)
        assertTrue("긴 쪽이 실측(6,053자) 언저리여야 한다: ${long.length}", long.length > 5_500)
        assertTrue(
            "파일명 셀이 충분히 짧아지지 않았다: ${short.length} vs ${long.length}",
            short.length * 100 < long.length * 60
        )
    }

    // ── fromCell ──

    @Test
    fun `파일명은 이 기기의 경로로 붙는다`() {
        assertEquals(
            """["$dir/char_1.jpg","$dir/img_2.jpg"]""",
            ImagePathCell.fromCell("""["char_1.jpg","img_2.jpg"]""", resolve)
        )
    }

    /** 옛 파일(절대경로 배열)도 그대로 들어온다 — 고쳐 적을 필요가 없다. */
    @Test
    fun `옛 절대경로 표기도 그대로 읽는다`() {
        val cell = """["/old/device/files/char_1.jpg"]"""
        assertEquals(cell, ImagePathCell.fromCell(cell, resolve))
    }

    /** zip 복원의 재매핑은 절대경로 토큰에 종전 그대로 걸린다. */
    @Test
    fun `옛 절대경로는 재매핑을 탄다`() {
        val out = ImagePathCell.fromCell(
            """["/old/files/char_1.jpg"]""", resolve,
            remapPath = { if (it == "/old/files/char_1.jpg") "$dir/new_1.jpg" else it }
        )
        assertEquals("""["$dir/new_1.jpg"]""", out)
    }

    /** 파일명도 zip 복원의 basename 사다리를 탄다 — '이미지' 시트가 쓰는 그 차례다. */
    @Test
    fun `파일명은 복원 리맵을 먼저 본다`() {
        val out = ImagePathCell.fromCell(
            """["char_1.jpg"]""",
            resolveName = { name -> if (name == "char_1.jpg") "$dir/restored.jpg" else "$dir/$name" }
        )
        assertEquals("""["$dir/restored.jpg"]""", out)
    }

    @Test
    fun `빈 칸과 빈 배열은 빈 배열이다`() {
        assertEquals("[]", ImagePathCell.fromCell("", resolve))
        assertEquals("[]", ImagePathCell.fromCell("[]", resolve))
    }

    /** 레거시: 배열이 아니라 경로 한 줄만 적힌 값도 배열로 접는다(종전 catch 갈래). */
    @Test
    fun `단일 경로 문자열은 배열로 접힌다`() {
        assertEquals("""["/a/b.jpg"]""", ImagePathCell.fromCell("/a/b.jpg", resolve))
    }

    /**
     * **디렉터리를 벗어나는 이름을 붙이지 않는다.** 이 칸은 사용자가 손으로 적는 자리라
     * `..`이 그대로 통과하면 셀 한 칸으로 앱 저장소 밖을 가리킬 수 있다.
     * 걸린 토큰은 버리지 않고 **원문 그대로** 남긴다(유실 금지).
     */
    @Test
    fun `상위 디렉터리 이름은 경로로 붙지 않는다`() {
        assertEquals("""["..",".","a\\b"]""", ImagePathCell.fromCell("""["..",".","a\\b"]""", resolve))
        assertFalse(ImagePathCell.isPlainFileName(".."))
        assertFalse(ImagePathCell.isPlainFileName("."))
        assertFalse(ImagePathCell.isPlainFileName("a/b"))
        assertFalse(ImagePathCell.isPlainFileName("""a\b"""))
        assertTrue(ImagePathCell.isPlainFileName("char_1.jpg"))
    }

    // ── 왕복 ──

    /**
     * 내보내고 그대로 다시 들이면 **저장된 값이 한 글자도 달라지지 않는다** — 엑셀 왕복
     * 무결성(개발 의도 4번)이 이 칸에서 성립하는가를 재는 자리다.
     */
    @Test
    fun `왕복이 저장값을 바꾸지 않는다`() {
        val stored = """["$dir/char_1.jpg","$dir/img_2.jpg"]"""
        val back = ImagePathCell.fromCell(ImagePathCell.toCell(stored), resolve)
        assertEquals(stored, back)
    }
}
