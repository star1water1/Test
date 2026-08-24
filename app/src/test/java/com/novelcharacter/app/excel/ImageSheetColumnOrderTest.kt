package com.novelcharacter.app.excel

import com.novelcharacter.app.util.AutoLinkPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '이미지' 시트의 열 차례 — 셀을 **번호로** 쓰는 자리가 있어 열을 끼우면 값이 밀린다
 * (`ExcelExporter.exportImageMeta`의 `createCell(0..5)`). 형제 시험
 * [CharacterSpecColumnOrderTest]가 캐릭터 시트에 대해 같은 것을 지킨다.
 *
 * '링크 캐릭터'는 2026.08.24에 들어왔다 — 자동 링크 토큰이 `char:<내부 id>`인데 이 워크북의
 * 다른 참조는 전부 16자리 코드라, **그 숫자를 캐릭터로 되짚을 길이 파일 어디에도 없었다**
 * (실측: 사용자 파일 1,443행 중 1,437행이 그 꼴).
 */
class ImageSheetColumnOrderTest {

    private fun headers() = imageMetaSpec().columns.map { it.header }

    @Test
    fun `열 차례가 내보내기의 셀 번호와 같다`() {
        assertEquals(
            listOf(IMAGE_SHEET_IDENTITY_COLUMN, "태그", "링크그룹", "링크 캐릭터", "뗀날짜", "뗀곳"),
            headers()
        )
    }

    @Test
    fun `링크 캐릭터는 링크그룹 바로 옆이다`() {
        // 토큰과 그 이름이 떨어져 있으면 옆 칸을 보는 것만으로 읽히지 않는다.
        assertEquals(headers().indexOf("링크그룹") + 1, headers().indexOf("링크 캐릭터"))
    }

    @Test
    fun `링크 캐릭터는 앱이 채우는 열이다`() {
        // 여기에 이름을 적어도 링크는 바뀌지 않는다 — 바꾸는 칸은 '링크그룹'이다.
        val col = imageMetaSpec().columns.first { it.header == "링크 캐릭터" }
        assertTrue(col.readOnly)
        assertFalse(col.required)
    }

    @Test
    fun `자동 토큰만 이름 칸을 갖는다`() {
        // 이름 칸을 채우는 판정 — 수동 묶음(UUID)은 캐릭터가 없으므로 빈칸이다.
        assertEquals(273L, AutoLinkPlanner.characterIdOf("char:273"))
        assertNull(AutoLinkPlanner.characterIdOf("3f2a9c10-1b7e-4f0a-9c31-2d8e4b6a1c55"))
        assertNull(AutoLinkPlanner.characterIdOf(null))
        assertNull(AutoLinkPlanner.characterIdOf("char:아무개"))
    }
}
