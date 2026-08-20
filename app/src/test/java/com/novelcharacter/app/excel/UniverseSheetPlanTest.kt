package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 배정표 재현의 왕복 계약 (2026.08.20 — 동명 세계관 시트 오배정 유실의 수리).
 *
 * 고정하는 계약: **내보내기가 [assignSheetName]으로 배정한 세계관 시트명을,
 * [UniverseSheetPlan]이 '세계관' 시트 행(이름·코드)만으로 똑같이 되짚는다.**
 * sanitize 결과가 같아지는 모든 충돌 부류(완전 동명 · 31자 절단 · 금칙문자 제거 ·
 * 대소문자 무시 · 예약명)에서 배정과 회수가 일치해야, 가져오기가 코드로 제 시트를 찾아
 * 캐릭터 중복 삽입·`(2)` 시트 무독·'엑셀에 없는 항목 삭제'의 오삭제가 생기지 않는다.
 */
class UniverseSheetPlanTest {

    /**
     * 내보내기 배정 시뮬레이션 — [ExcelExporter.populateWorkbook]과 같은 모양이다:
     * 예약 시트가 소유자로 먼저 이름을 받고, 세계관 캐릭터 시트가 같은 usedNames 위에서
     * 순서대로 배정된다('미분류 캐릭터' 시트는 세계관 루프 뒤라 배정에 영향이 없다).
     */
    private fun exportAssigned(universes: List<Pair<String, String>>, withReserved: Boolean = true): List<String> {
        val used = mutableSetOf<String>()
        if (withReserved) {
            for (reserved in RESERVED_SHEET_NAMES) assignSheetName(reserved, used, ownerOf = reserved)
        }
        return universes.map { (name, _) -> assignSheetName(name, used, ownerOf = null) }
    }

    private fun planOf(universes: List<Pair<String, String>>): UniverseSheetPlan =
        UniverseSheetPlan.build(universes.map { (name, code) -> UniverseSheetPlan.Row(name, code) })

    private fun assertRoundtrip(universes: List<Pair<String, String>>) {
        val assigned = exportAssigned(universes)
        val plan = planOf(universes)
        universes.forEachIndexed { i, (name, code) ->
            assertEquals("세계관 '$name'(코드 $code)의 배정과 회수가 갈렸다", assigned[i], plan.sheetNameFor(code))
            assertEquals("배정 시트의 소유 코드가 갈렸다", code, plan.plannedOwnerOf(assigned[i]))
        }
    }

    @Test
    fun `완전 동명 세계관 둘 - 배정과 회수가 왕복 일치한다`() {
        assertRoundtrip(listOf("아르카디아" to "U-1", "아르카디아" to "U-2", "아르카디아" to "U-3"))
    }

    @Test
    fun `31자 절단으로 겹치는 이름 - 배정과 회수가 왕복 일치한다`() {
        // 31자 이후만 다른 두 이름은 sanitize가 같은 기본명으로 접는다.
        assertRoundtrip(listOf(("가".repeat(31) + "하나") to "U-1", ("가".repeat(31) + "둘") to "U-2"))
    }

    @Test
    fun `금칙문자 제거로 겹치는 이름 - 배정과 회수가 왕복 일치한다`() {
        // '가/나'는 sanitize가 '가나'로 접어 둘째 세계관과 겹친다.
        assertRoundtrip(listOf("가/나" to "U-1", "가나" to "U-2"))
    }

    @Test
    fun `대소문자만 다른 이름 - 배정과 회수가 왕복 일치한다`() {
        // POI의 중복 판정이 대소문자 무시라 내보내기가 (2)로 가른다 — 회수도 같아야 한다.
        assertRoundtrip(listOf("myworld" to "U-1", "MyWorld" to "U-2"))
    }

    @Test
    fun `예약명과 같은 세계관 이름 - 배정과 회수가 왕복 일치한다`() {
        // 예약명은 소유자만 가질 수 있어 세계관 시트는 반드시 접미사로 밀린다.
        assertRoundtrip(
            listOf(UNCLASSIFIED_SHEET_NAME to "U-1", UNCLASSIFIED_SHEET_NAME to "U-2", "세력" to "U-3")
        )
    }

    @Test
    fun `내보내기 옵션으로 예약 시트가 빠져도 배정표는 같다`() {
        // 재현이 빈 usedNames에서 성립하는 근거: 예약명 보호는 usedNames가 아니라
        // assignSheetName의 규칙이 든다. 예약 시트를 하나도 안 만든 내보내기와 전부 만든
        // 내보내기가 세계관 시트에는 같은 이름을 줘야 한다.
        val universes = listOf("세력" to "U-1", "아르카디아" to "U-2", "아르카디아" to "U-3")
        assertEquals(
            exportAssigned(universes, withReserved = true),
            exportAssigned(universes, withReserved = false)
        )
    }

    @Test
    fun `코드가 빈 행도 접미사 번호는 소비한다`() {
        // 내보내기는 코드 유무를 모르고 순서대로 배정한다 — 재현이 빈 코드 행을 건너뛰면
        // 뒤 세계관의 접미사 번호가 한 칸 당겨져 남의 시트를 지목한다.
        val plan = planOf(listOf("아르카디아" to "", "아르카디아" to "U-2"))
        assertEquals("아르카디아(2)", plan.sheetNameFor("U-2"))
        assertNull(plan.plannedOwnerOf("아르카디아"))
    }

    @Test
    fun `빈 이름 행은 세계관이 아니므로 배정에서 빠진다`() {
        // importUniverses와 같은 갈래 — 내보내기는 빈 이름 행을 쓰지 않으므로 번호가 어긋나지 않는다.
        val plan = planOf(listOf("" to "U-0", "아르카디아" to "U-1"))
        assertEquals("아르카디아", plan.sheetNameFor("U-1"))
        assertNull(plan.sheetNameFor("U-0"))
    }

    @Test
    fun `파일 안 중복 코드는 청구권을 걷는다 - 조용히 고르지 않는다`() {
        // 어느 행이 진짜인지 좁혀지지 않으므로 배정표는 답하지 않고 휴리스틱 폴백에 맡긴다.
        val plan = planOf(listOf("아르카디아" to "U-1", "엘리시움" to "U-1", "온전한" to "U-2"))
        assertNull(plan.sheetNameFor("U-1"))
        assertNull(plan.plannedOwnerOf("아르카디아"))
        assertNull(plan.plannedOwnerOf("엘리시움"))
        // 중복과 무관한 세계관의 청구는 산다.
        assertEquals("온전한", plan.sheetNameFor("U-2"))
    }

    @Test
    fun `배정표 밖 조회는 null이다`() {
        val plan = planOf(listOf("아르카디아" to "U-1"))
        assertNull(plan.sheetNameFor(""))
        assertNull(plan.sheetNameFor("U-없음"))
        assertNull(plan.plannedOwnerOf("남의 시트"))
    }

    // ── 순서 재구성 (콜드 검토 2026.08.20 — 행 순서를 믿으면 정렬된 파일이 배정을 맞바꾼다) ──

    @Test
    fun `엑셀에서 행을 정렬해도 배정표는 내보내기 순서를 되짚는다`() {
        // 내보내기 순서: X(U-1)→'X', X(U-2)→'X(2)'. 정렬 키가 행과 함께 움직인다.
        val exportOrder = listOf(
            UniverseSheetPlan.Row("X", "U-1", displayOrder = 0, createdAt = 200L),
            UniverseSheetPlan.Row("X", "U-2", displayOrder = 1, createdAt = 100L)
        )
        // 사용자가 시트를 정렬해 행이 뒤집힌 파일 — 그대로 재현하면 배정이 맞바뀐다.
        val resorted = exportOrder.reversed()
        val plan = UniverseSheetPlan.build(resorted)
        assertEquals("X", plan.sheetNameFor("U-1"))
        assertEquals("X(2)", plan.sheetNameFor("U-2"))
    }

    @Test
    fun `동률 정렬순서의 동명 둘은 생성일 내림차순이 가른다`() {
        // getAllUniversesList = displayOrder ASC, createdAt DESC — 최신 생성이 먼저다.
        val rows = listOf(
            UniverseSheetPlan.Row("X", "U-old", displayOrder = 0, createdAt = 100L),
            UniverseSheetPlan.Row("X", "U-new", displayOrder = 0, createdAt = 200L)
        )
        val plan = UniverseSheetPlan.build(rows)
        assertEquals("X", plan.sheetNameFor("U-new"))
        assertEquals("X(2)", plan.sheetNameFor("U-old"))
    }

    @Test
    fun `정렬 키가 없는 레거시 행은 종전처럼 행 순서다`() {
        val rows = listOf(
            UniverseSheetPlan.Row("X", "U-1"),
            UniverseSheetPlan.Row("X", "U-2")
        )
        val plan = UniverseSheetPlan.build(rows)
        assertEquals("X", plan.sheetNameFor("U-1"))
        assertEquals("X(2)", plan.sheetNameFor("U-2"))
    }

    @Test
    fun `손으로 끼운 키 없는 행은 내보내진 행들의 접미사를 밀지 못한다`() {
        // 키 없는 동명 행을 맨 앞에 끼워도, 내보내진 두 행의 배정('X'·'X(2)')은 그대로다.
        val rows = listOf(
            UniverseSheetPlan.Row("X", "U-손", displayOrder = null, createdAt = null),
            UniverseSheetPlan.Row("X", "U-1", displayOrder = 0, createdAt = 200L),
            UniverseSheetPlan.Row("X", "U-2", displayOrder = 1, createdAt = 100L)
        )
        val plan = UniverseSheetPlan.build(rows)
        assertEquals("X", plan.sheetNameFor("U-1"))
        assertEquals("X(2)", plan.sheetNameFor("U-2"))
        // 끼운 행은 그 뒤 번호를 받는다 — 실재하지 않는 시트명이라 폴백으로 내려간다.
        assertEquals("X(3)", plan.sheetNameFor("U-손"))
    }
}
