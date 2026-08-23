package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 엑셀에 적힌 캐릭터 이름 → 코드 색인.
 *
 * 이 색인이 있는 이유는 **같은 사람이 두 글자로 적힐 수 있기 때문**이다
 * (`name` = 이름 칸 그대로 / `displayName` = 성·이름 칸을 `"성 이름"`으로 조립한 글자).
 * 내보내기가 하나로 통일된 뒤에도 **이미 나간 파일은 다른 쪽 글자를 들고 있다.**
 */
class CharacterNameIndexTest {

    private fun character(code: String, name: String, first: String = "", last: String = "") =
        Character(name = name, firstName = first, lastName = last, code = code)

    @Test
    fun `두 표기가 모두 키가 된다`() {
        // 실측(2026.08.24 내보낸 파일): 이름 칸 '엘레아' / 조립된 표기 '엘 레아'.
        val index = CharacterNameIndex.byWrittenName(
            listOf(character("c1", "엘레아", first = "레아", last = "엘"))
        )
        assertEquals(listOf("c1"), index["엘레아"])
        assertEquals(listOf("c1"), index["엘 레아"])
    }

    @Test
    fun `차례가 뒤집힌 조립 표기도 같은 사람으로 읽는다`() {
        val index = CharacterNameIndex.byWrittenName(
            listOf(character("c1", "유리엘 실라키아스", first = "유리엘", last = "실라키아스"))
        )
        assertEquals(listOf("c1"), index["유리엘 실라키아스"])
        assertEquals(listOf("c1"), index["실라키아스 유리엘"])
    }

    @Test
    fun `한 캐릭터가 한 키에서 두 번 세어지지 않는다`() {
        // 두 표기가 같으면 키도 하나다 — 두 번 담으면 자기 이름이 스스로 모호해져
        // 부르는 쪽이 '동명이인'으로 읽고 그 행을 거부한다.
        val index = CharacterNameIndex.byWrittenName(listOf(character("c1", "봉")))
        assertEquals(listOf("c1"), index["봉"])
    }

    @Test
    fun `동명이인은 코드가 둘이라 부르는 쪽이 모호를 선언한다`() {
        val index = CharacterNameIndex.byWrittenName(
            listOf(character("c1", "리안"), character("c2", "리안"))
        )
        assertEquals(2, index["리안"]?.size)
    }

    @Test
    fun `한쪽의 조립 표기가 다른 쪽의 이름과 겹치면 모호다`() {
        // 아무 쪽이나 고르면 승패·판이 남에게 붙는다 — 모호로 두는 것이 R-1의 처분이다.
        val index = CharacterNameIndex.byWrittenName(
            listOf(
                character("c1", "유리엘 실라키아스", first = "유리엘", last = "실라키아스"),
                character("c2", "실라키아스 유리엘")
            )
        )
        assertEquals(2, index["실라키아스 유리엘"]?.size)
    }

    @Test
    fun `코드가 빈 캐릭터는 싣지 않는다`() {
        val index = CharacterNameIndex.byWrittenName(listOf(character("", "이름없음코드")))
        assertNull(index["이름없음코드"])
    }

    @Test
    fun `코드에서 이름으로도 찾는다`() {
        val names = CharacterNameIndex.namesByCode(
            listOf(character("c1", "에녹 프로스트", first = "에녹", last = "프로스트"))
        )
        assertEquals(setOf("에녹 프로스트", "프로스트 에녹"), names["c1"])
    }

    @Test
    fun `이름 칸이 비면 조립 표기만 남는다`() {
        val names = CharacterNameIndex.namesByCode(
            listOf(character("c1", "", first = "레아", last = "엘"))
        )
        assertEquals(setOf("엘 레아"), names["c1"])
        assertTrue(CharacterNameIndex.byWrittenName(listOf(character("c1", "", first = "레아", last = "엘")))["엘 레아"] == listOf("c1"))
    }
}
