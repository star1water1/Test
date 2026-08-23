package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageSheetRows] — '이미지' 시트가 **파일이 쓰는 그림을 전부** 싣는가.
 *
 * 사용자가 내보낸 파일에서 그림이 한 장뿐인 캐릭터 **여섯 명 전원**의 그림이 이 시트에
 * 없었다(3장 이상인 54명은 전원 있었다). 라이브러리 편입이 태그·묶음·뗀 표식으로만
 * 일어나고 자동 링크는 두 장 이상일 때만 묶기 때문이다.
 */
class ImageSheetRowsTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"

    @Test
    fun `라이브러리 행이 먼저 받은 차례 그대로다`() {
        val library = listOf("$dir/b.jpg", "$dir/a.jpg")
        val rows = ImageSheetRows.plan(library, emptyList())
        assertEquals(listOf("b.jpg", "a.jpg"), rows.map { it.fileName })
        assertTrue(rows.all { it.inLibrary })
    }

    @Test
    fun `라이브러리에 없는 참조도 실린다`() {
        val rows = ImageSheetRows.plan(listOf("$dir/b.jpg"), listOf("$dir/a.jpg"))
        assertEquals(listOf("b.jpg", "a.jpg"), rows.map { it.fileName })
        assertEquals(listOf(true, false), rows.map { it.inLibrary })
    }

    @Test
    fun `덧붙는 행은 파일명 순이라 두 번 내보내도 같은 파일이다`() {
        val extras = listOf("$dir/z.jpg", "$dir/a.jpg", "$dir/m.jpg")
        val first = ImageSheetRows.plan(emptyList(), extras)
        val second = ImageSheetRows.plan(emptyList(), extras.reversed())
        assertEquals(listOf("a.jpg", "m.jpg", "z.jpg"), first.map { it.fileName })
        assertEquals(first, second)
    }

    /** 시트의 정체는 **파일명**이다 — 두 행이 같은 이름이면 가져오기가 서로를 덮는다. */
    @Test
    fun `이미 라이브러리가 쓰는 파일명은 덧붙지 않는다`() {
        val rows = ImageSheetRows.plan(listOf("$dir/a.jpg"), listOf("/other/dir/a.jpg"))
        assertEquals(1, rows.size)
        assertEquals("$dir/a.jpg", rows.single().path)
    }

    @Test
    fun `덧붙는 것끼리 파일명이 겹쳐도 하나만 남는다`() {
        val rows = ImageSheetRows.plan(emptyList(), listOf("/z/a.jpg", "/b/a.jpg"))
        assertEquals(1, rows.size)
        // 경로 순으로 앞선 것 — 어느 것이 남는지가 실행마다 흔들리지 않는다.
        assertEquals("/b/a.jpg", rows.single().path)
    }

    @Test
    fun `이미 라이브러리에 있는 참조는 두 번 실리지 않는다`() {
        val rows = ImageSheetRows.plan(listOf("$dir/a.jpg"), listOf("$dir/a.jpg", "$dir/b.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg"), rows.map { it.fileName })
        assertEquals(listOf(true, false), rows.map { it.inLibrary })
    }

    @Test
    fun `빈 경로와 중복은 걸러진다`() {
        val rows = ImageSheetRows.plan(emptyList(), listOf("", "   ", "$dir/a.jpg", "$dir/a.jpg"))
        assertEquals(listOf("a.jpg"), rows.map { it.fileName })
    }

    /** 실측 그대로 — 라이브러리 1,437장 + 참조만 있는 6장 = 1,443행. */
    @Test
    fun `실측 규모에서 여섯이 더 실린다`() {
        val library = (1..1437).map { "$dir/img_%04d.jpg".format(it) }
        val referenced = library.take(960) + (1..6).map { "$dir/char_only_$it.jpg" }
        val rows = ImageSheetRows.plan(library, referenced)
        assertEquals(1443, rows.size)
        assertEquals(6, rows.count { !it.inLibrary })
    }
}
