package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.NameBankEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-124 ⓐ — 은행 선택 시트의 목록 구성 잠금. */
class NameBankPickOrderTest {

    private fun entry(
        id: Long,
        name: String,
        used: Boolean = false,
        gender: String = "",
        createdAt: Long = id,
        origin: String = "",
        notes: String = ""
    ) = NameBankEntry(
        id = id, name = name, gender = gender, origin = origin, notes = notes,
        isUsed = used, usedByCharacterId = if (used) 1L else null,
        createdAt = createdAt, code = "code$id"
    )

    @Test
    fun `미사용이 사용된 것보다 앞선다`() {
        val out = NameBankPickOrder.order(
            listOf(entry(1, "가", used = true), entry(2, "나")), "", null
        )
        assertEquals(listOf(2L, 1L), out.map { it.id })
    }

    @Test
    fun `사용된 엔트리를 빼지 않고 뒤로 민다 — 없어진 것처럼 보이면 안 된다`() {
        val out = NameBankPickOrder.order(listOf(entry(1, "가", used = true)), "", null)
        assertEquals(1, out.size)
    }

    @Test
    fun `미사용 안에서 성별이 맞는 쪽이 앞선다`() {
        val out = NameBankPickOrder.order(
            listOf(entry(1, "가", gender = "남성"), entry(2, "나", gender = "여성")), "", "여성"
        )
        assertEquals(listOf(2L, 1L), out.map { it.id })
    }

    @Test
    fun `성별 축은 미사용 축을 이기지 못한다`() {
        // 성별이 맞아도 사용 중이면 미사용 뒤다 — 걸 수 없는 것을 맨 위에 두면 헛걸음이 된다.
        val out = NameBankPickOrder.order(
            listOf(entry(1, "가", used = true, gender = "여성"), entry(2, "나", gender = "남성")),
            "", "여성"
        )
        assertEquals(listOf(2L, 1L), out.map { it.id })
    }

    @Test
    fun `성별이 비면 그 축은 무효다 — 최근 등록순으로 남는다`() {
        val out = NameBankPickOrder.order(
            listOf(entry(1, "가", gender = "남성"), entry(2, "나", gender = "여성")), "", ""
        )
        assertEquals(listOf(2L, 1L), out.map { it.id })
    }

    @Test
    fun `같은 등급이면 최근 등록순이다`() {
        val out = NameBankPickOrder.order(
            listOf(entry(1, "가", createdAt = 100), entry(2, "나", createdAt = 300),
                entry(3, "다", createdAt = 200)), "", null
        )
        assertEquals(listOf(2L, 3L, 1L), out.map { it.id })
    }

    @Test
    fun `createdAt이 같아도 순서가 흔들리지 않는다`() {
        val same = listOf(entry(1, "가", createdAt = 5), entry(2, "나", createdAt = 5))
        assertEquals(
            NameBankPickOrder.order(same, "", null).map { it.id },
            NameBankPickOrder.order(same.reversed(), "", null).map { it.id }
        )
    }

    @Test
    fun `검색은 이름 출처 메모 셋을 본다`() {
        val entries = listOf(
            entry(1, "아리아"), entry(2, "브린", origin = "북부 왕가"),
            entry(3, "케이", notes = "왕가의 방계"), entry(4, "델타")
        )
        assertEquals(listOf(2L, 3L), NameBankPickOrder.order(entries, "왕가", null).map { it.id }.sorted())
        assertEquals(listOf(1L), NameBankPickOrder.order(entries, "아리", null).map { it.id })
    }

    @Test
    fun `검색은 대소문자를 가리지 않고 앞뒤 공백을 무시한다`() {
        val entries = listOf(entry(1, "Aria"), entry(2, "브린"))
        assertEquals(listOf(1L), NameBankPickOrder.order(entries, "  aria ", null).map { it.id })
    }

    @Test
    fun `검색어가 비면 전부 남는다`() {
        val entries = listOf(entry(1, "가"), entry(2, "나"))
        assertTrue(NameBankPickOrder.order(entries, "   ", null).size == 2)
    }
}
