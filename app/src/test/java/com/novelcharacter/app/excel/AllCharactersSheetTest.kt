package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '전체 캐릭터' 시트(U-12a)의 공유 필드 열 판정과 시트 정체성.
 *
 * 이 시트의 값어치는 **전 인원에 피벗을 걸 수 있다**는 것이고, 그 값어치를 깨는 길이 둘이다:
 * 열이 전 세계관 필드의 합집합이 되어 다시 넓어지거나(피벗 불가), 가져오기가 이 시트를
 * 캐릭터 시트로 오인해 읽는 것(왕복 오염). 아래가 그 둘을 고정한다.
 */
class AllCharactersSheetTest {

    private fun field(id: Long, universeId: Long, key: String, name: String = key, type: String = "TEXT") =
        FieldDefinition(id = id, universeId = universeId, key = key, name = name, type = type)

    @Test
    fun `두 세계관 이상이 쓰는 필드만 열이 된다`() {
        val fields = listOf(
            field(1, 10, "gender", "성별"),
            field(2, 20, "gender", "성별"),
            field(3, 10, "residence", "거주지")   // 한 세계관에만 있다
        )
        val shared = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L))
        assertEquals(listOf("gender"), shared.map { it.key })
        assertEquals(2, shared.single().universeCount)
    }

    @Test
    fun `타입이 다르면 같은 키라도 합치지 않는다`() {
        // 통계의 세계관 병합과 같은 (필드키, 타입) 규칙이다 — 등급 'S'와 숫자 4를 한 열에 섞으면
        // 그 열로 만든 피벗이 조용히 틀린다.
        val fields = listOf(
            field(1, 10, "rank", "등급", type = "GRADE"),
            field(2, 20, "rank", "등급", type = "TEXT")
        )
        assertTrue(AllCharactersSheet.sharedFields(fields, setOf(10L, 20L)).isEmpty())
    }

    @Test
    fun `캐릭터가 없는 세계관은 세지 않는다`() {
        // 그 세계관은 행을 하나도 만들지 않으면서 열만 늘린다 — 전부 빈 칸인 열은 소음이다.
        val fields = listOf(
            field(1, 10, "gender", "성별"),
            field(2, 99, "gender", "성별")   // 세계관 99에는 캐릭터가 없다
        )
        assertTrue(AllCharactersSheet.sharedFields(fields, setOf(10L)).isEmpty())
    }

    @Test
    fun `열 이름에 필드키를 항상 병기한다`() {
        // 같은 키의 필드가 세계관마다 다른 이름을 가질 수 있어, 이름만으로는 어느 축인지 확정되지 않는다.
        val fields = listOf(
            field(1, 10, "gender", "성별"),
            field(2, 20, "gender", "성별"),
            field(3, 30, "gender", "젠더")
        )
        val shared = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L, 30L)).single()
        assertEquals("성별(gender)", shared.header)   // 다수결로 '성별', 키는 항상 병기
    }

    @Test
    fun `이름이 동수면 사전순으로 갈라 출력이 흔들리지 않는다`() {
        val fields = listOf(
            field(1, 10, "gender", "젠더"),
            field(2, 20, "gender", "성별")
        )
        val shared = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L)).single()
        assertEquals("성별(gender)", shared.header)
    }

    @Test
    fun `많이 쓰는 축이 앞에 온다`() {
        val fields = listOf(
            field(1, 10, "b_key"), field(2, 20, "b_key"),
            field(3, 10, "a_key"), field(4, 20, "a_key"), field(5, 30, "a_key")
        )
        val shared = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L, 30L))
        assertEquals(listOf("a_key", "b_key"), shared.map { it.key })
    }

    @Test
    fun `첫 열이 이름이 아니라 가져오기가 캐릭터 시트로 오인하지 않는다`() {
        // looksLikeCharacterSheet은 '첫 열이 이름'을 전제로 판정한다 — '캐릭터 필드값' 시트와 같은 방어.
        val headers = allCharactersSpec().columns.map { it.header }
        assertFalse("첫 열이 '이름'이면 캐릭터 시트로 오인된다", headers.first() == "이름")
        assertEquals("세계관", headers.first())
    }

    @Test
    fun `예약명이라 세계관 시트에 자리를 빼앗기지 않는다`() {
        assertTrue(ALL_CHARACTERS_SHEET_NAME in RESERVED_SHEET_NAMES)
        val used = mutableSetOf<String>()
        // 세계관 이름이 같아도 예약명은 소유자(이 시트)가 갖는다 — 세계관 쪽이 밀린다.
        val mine = assignSheetName(ALL_CHARACTERS_SHEET_NAME, used, ownerOf = ALL_CHARACTERS_SHEET_NAME)
        val universeSheet = assignSheetName(ALL_CHARACTERS_SHEET_NAME, used)
        assertEquals(ALL_CHARACTERS_SHEET_NAME, mine)
        assertTrue(universeSheet != ALL_CHARACTERS_SHEET_NAME)
        assertTrue("밀려난 세계관 시트를 가져오기가 되찾을 수 있어야 한다",
            isSuffixedVariantOf(universeSheet, ALL_CHARACTERS_SHEET_NAME))
    }

    @Test
    fun `모든 열이 읽기 전용이다`() {
        // 가져오기가 읽지 않는 시트라 편집 가능한 회색 아닌 헤더가 있으면 그 자체가 거짓 신호다.
        val spec = allCharactersSpec(listOf("성별(gender)"))
        assertTrue(spec.columns.all { it.readOnly })
        assertFalse(spec.columns.any { it.required })
    }

    @Test
    fun `공유 필드 열이 고정 열 뒤에 붙는다`() {
        val fixed = allCharactersSpec().columns.size
        val spec = allCharactersSpec(listOf("성별(gender)", "거주지(residence)"))
        assertEquals(fixed + 2, spec.columns.size)
        assertEquals("성별(gender)", spec.columns[fixed].header)
    }

    // ── 머리 유일성 (2026.08.23 — 실제로 내보낸 파일에 `키(height)` 열이 둘 있었다) ──

    @Test
    fun `타입이 갈려 머리가 겹치면 타입을 병기한다`() {
        // 바로 위 시험이 잠근 *합치지 않는다*의 뒷면이다. 각 타입 그룹이 세계관 2곳 이상이면
        // 열이 **둘 다 선다** — 그때 머리가 같으면 어느 열이 어느 축인지 알 방법이 없다.
        val fields = listOf(
            field(1, 10, "height", "키", type = "NUMBER"),
            field(2, 20, "height", "키", type = "NUMBER"),
            field(3, 30, "height", "키", type = "TEXT"),
            field(4, 40, "height", "키", type = "TEXT")
        )
        val shared = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L, 30L, 40L))
        assertEquals(2, shared.size)
        assertEquals(
            setOf("키(height·NUMBER)", "키(height·TEXT)"),
            shared.mapTo(HashSet()) { it.header }
        )
    }

    @Test
    fun `머리가 안 겹치면 타입을 붙이지 않는다`() {
        // 겹칠 때만 붙인다 — 안 그러면 모든 열이 `성별(gender·SELECT)`이 되어 길어지기만 한다.
        val fields = listOf(
            field(1, 10, "gender", "성별", type = "SELECT"),
            field(2, 20, "gender", "성별", type = "SELECT")
        )
        assertEquals("성별(gender)", AllCharactersSheet.sharedFields(fields, setOf(10L, 20L)).single().header)
    }

    @Test
    fun `같은 키라도 이름이 갈려 있으면 병기하지 않는다`() {
        // 판정이 *키가 겹치는가*가 아니라 **머리가 겹치는가**인 이유다 — 이미 갈려 있다.
        val fields = listOf(
            field(1, 10, "height", "키", type = "NUMBER"),
            field(2, 20, "height", "키", type = "NUMBER"),
            field(3, 30, "height", "신장", type = "TEXT"),
            field(4, 40, "height", "신장", type = "TEXT")
        )
        val shared = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L, 30L, 40L))
        assertEquals(setOf("키(height)", "신장(height)"), shared.mapTo(HashSet()) { it.header })
    }

    @Test
    fun `이름 자체가 병기 꼴이어도 머리는 유일하다`() {
        // 필드 이름은 사용자가 자유롭게 짓는다(원칙 01) — 병기가 남의 머리와 부딪힐 수 있다.
        // 형제 시트가 `규모(명)`에서 겪은 부류라 번호 단으로 닫는다.
        val fields = listOf(
            field(1, 10, "height", "키", type = "NUMBER"),
            field(2, 20, "height", "키", type = "NUMBER"),
            field(3, 30, "height", "키", type = "TEXT"),
            field(4, 40, "height", "키", type = "TEXT"),
            field(5, 10, "h2", "키(height·NUMBER)"),
            field(6, 20, "h2", "키(height·NUMBER)")
        )
        val headers = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L, 30L, 40L)).map { it.header }
        assertEquals("머리가 겹치면 안 된다", headers.size, headers.toSet().size)
    }

    @Test
    fun `열 이름은 spec의 열 이름과 그대로 같다`() {
        // 머리를 짓는 자리와 열을 그리는 자리가 갈리면 값이 다른 열에 앉는다.
        val fields = listOf(
            field(1, 10, "height", "키", type = "NUMBER"),
            field(2, 20, "height", "키", type = "NUMBER"),
            field(3, 30, "height", "키", type = "TEXT"),
            field(4, 40, "height", "키", type = "TEXT")
        )
        val shared = AllCharactersSheet.sharedFields(fields, setOf(10L, 20L, 30L, 40L))
        val spec = allCharactersSpec(shared.map { it.header }, shared.map { it.type })
        val headers = spec.columns.map { it.header }
        assertEquals("시트 전체에서 열 이름이 유일해야 한다", headers.size, headers.toSet().size)
    }
}
