package com.novelcharacter.app.data

import com.novelcharacter.app.data.dao.TrashSnapshotSummary
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.TrashFilter
import com.novelcharacter.app.data.repository.TrashListPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 화면의 행 짜기 — 접기와 거르기가 만나는 자리 (B-16 · B-50).
 *
 * **여기서 빠진 항목은 사용자에게 '없는 것'과 구별되지 않는다.** 그래서 Fragment가 아니라
 * 순수 계층에 두고 실행 검증한다 — 화면 안에 있으면 이 판정을 잴 방법이 없다.
 */
class TrashListPlanTest {

    private var nextId = 1L

    private fun snap(
        type: String,
        name: String,
        opId: String?,
        at: Long = 1_000L,
        kind: String? = null
    ) = TrashSnapshotSummary(
        id = nextId++, entityType = type, entityName = name,
        deletedAt = at, operationId = opId, operationKind = kind
    )

    private fun universeDeletion(op: String, characters: Int): List<TrashSnapshotSummary> =
        listOf(snap(TrashSnapshot.TYPE_UNIVERSE, "아스테리아", op)) +
            (1..characters).map { snap(TrashSnapshot.TYPE_CHARACTER, "인물$it", op) }

    private fun headers(rows: List<TrashListPlan.Row>) =
        rows.filterIsInstance<TrashListPlan.Row.Header>()

    private fun items(rows: List<TrashListPlan.Row>) =
        rows.filterIsInstance<TrashListPlan.Row.Item>()

    @Test
    fun `묶음은 기본적으로 접혀 머리글 한 줄만 낸다`() {
        // B-16이 고치는 것이 이것이다 — 세계관 하나가 수백 행으로 펼쳐지던 자리.
        val rows = TrashListPlan.build(universeDeletion("op1", 200))
        assertEquals(1, rows.size)
        val header = headers(rows).single()
        // 접었어도 **무엇이 몇 건인지** 말한다 — 원칙 04가 금지하는 것은 존재를 알 수 없는
        // 데이터이지 접힌 데이터가 아니다.
        assertEquals(201, header.itemCount)
        assertEquals(TrashSnapshot.TYPE_UNIVERSE, header.rootType)
        assertEquals("아스테리아", header.rootName)
        assertFalse(header.expanded)
    }

    @Test
    fun `펼친 묶음만 항목을 낸다`() {
        val snaps = universeDeletion("op1", 3)
        val key = TrashListPlan.operationRowKey("op1", editBackup = false)
        val rows = TrashListPlan.build(snaps, expandChoices = mapOf(key to true))
        assertTrue(headers(rows).single().expanded)
        assertEquals(4, items(rows).size)
    }

    @Test
    fun `항목이 하나뿐인 묶음은 머리글을 만들지 않는다`() {
        // 만들면 같은 내용이 두 줄로 반복된다.
        val rows = TrashListPlan.build(listOf(snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1")))
        assertTrue(headers(rows).isEmpty())
        assertEquals(1, items(rows).size)
    }

    @Test
    fun `편집 직전 백업도 접히지만 일괄 동작은 받지 못한다`() {
        // **이 판이 넓힌 자리다.** 엑셀 임포트 한 번이 편집 백업 수백 개를 만드는데, 종전에는
        // 그 묶음에 머리글이 아예 없어 수백 줄로 펼쳐졌다(B-16이 말한 그 증상이 그대로다).
        // 그렇다고 '전체 복원'을 주면 지워진 적 없는 항목이 원클릭으로 복제된다 — 그래서 갈랐다.
        val snaps = (1..5).map {
            snap(TrashSnapshot.TYPE_CHARACTER, "인물$it", "op1", kind = TrashSnapshot.KIND_EDIT_BACKUP)
        }
        val header = headers(TrashListPlan.build(snaps)).single()
        assertTrue("편집 백업 묶음도 접혀야 한다", header.editBackup)
        assertFalse("'전체 복원'을 주면 원클릭 복제가 된다", header.allowsBulkActions)
        assertEquals(5, header.itemCount)
    }

    @Test
    fun `삭제 묶음은 일괄 동작을 받는다`() {
        val header = headers(TrashListPlan.build(universeDeletion("op1", 2))).single()
        assertFalse(header.editBackup)
        assertTrue(header.allowsBulkActions)
    }

    @Test
    fun `한 작업이 삭제와 편집 백업으로 갈리면 머리글 키도 갈린다`() {
        // 엑셀 임포트가 실제로 만드는 모양이다(R-3/R-9로 operationId를 공유한다).
        // 키가 갈리지 않으면 한쪽을 펼칠 때 다른 쪽도 함께 펼쳐지고, DiffUtil도 둘을 한 행으로 본다.
        val snaps = listOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "지운1", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "지운2", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "덮은1", "op1", kind = TrashSnapshot.KIND_EDIT_BACKUP),
            snap(TrashSnapshot.TYPE_CHARACTER, "덮은2", "op1", kind = TrashSnapshot.KIND_EDIT_BACKUP)
        )
        val keys = headers(TrashListPlan.build(snaps)).map { it.stableKey }
        assertEquals("머리글이 둘이어야 한다", 2, keys.size)
        assertEquals("두 키가 서로 달라야 한다", 2, keys.toSet().size)
    }

    @Test
    fun `머리글 키는 접힘 기억과 DiffUtil이 같은 것을 쓴다`() {
        // 두 벌로 적으면 한쪽만 고쳐도 컴파일되고, 증상은 "펼친 묶음이 갱신마다 도로 접힌다"다.
        val header = headers(TrashListPlan.build(universeDeletion("op1", 2))).single()
        assertEquals(
            TrashListPlan.operationRowKey(header.opKey, header.editBackup),
            header.stableKey
        )
    }

    @Test
    fun `항목 행의 키는 스냅샷 id로 갈린다`() {
        // 이름으로 견주면 동명이인 둘이 한 행으로 접힌다.
        val snaps = listOf(
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1"),
            snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op2")
        )
        val keys = items(TrashListPlan.build(snaps)).map { it.stableKey }
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `걸러 보는 중에는 묶음이 펼쳐진다`() {
        // 접힌 채로 두면 찾은 것이 머리글 뒤에 숨어 검색 결과가 "없다"와 구별되지 않는다.
        val rows = TrashListPlan.build(
            universeDeletion("op1", 5),
            criteria = TrashFilter.Criteria(query = "인물3")
        )
        val header = headers(rows).single()
        assertTrue("펼쳐 두지 않으면 검색이 쓸모없다", header.expanded)
        assertEquals(listOf("인물3"), items(rows).map { it.snapshot.entityName })
    }

    @Test
    fun `거르는 중에도 사용자가 접으면 접힌다 — 죽은 버튼을 만들지 않는다`() {
        // **마무리 콜드 검토가 잡은 자리다.** 처음에는 `criteria.isActive || 고른 것`이라
        // 적어 두어, 거르는 동안에는 머리글을 눌러도 **아무 일이 없었다.** 코드는 멀쩡해
        // 보이고 시험도 초록인데(펼쳐지는 것만 쟀으므로), 사용자에게는 고장과 구별되지 않는다.
        val key = TrashListPlan.operationRowKey("op1", editBackup = false)
        val rows = TrashListPlan.build(
            universeDeletion("op1", 5),
            criteria = TrashFilter.Criteria(query = "인물"),
            expandChoices = mapOf(key to false)
        )
        val header = headers(rows).single()
        assertFalse("고른 것이 기본값을 이겨야 한다", header.expanded)
        assertTrue("접었으면 항목이 안 보여야 한다", items(rows).isEmpty())
        // 머리글은 남아 무엇이 몇 건 걸렸는지 계속 말한다 — 접었다고 존재까지 감추지 않는다.
        assertEquals(5, header.matchedCount)
    }

    @Test
    fun `평소에도 사용자가 펼치면 펼쳐진다`() {
        val key = TrashListPlan.operationRowKey("op1", editBackup = false)
        val rows = TrashListPlan.build(universeDeletion("op1", 3), expandChoices = mapOf(key to true))
        assertTrue(headers(rows).single().expanded)
        assertEquals(4, items(rows).size)
    }

    @Test
    fun `걸러도 머리글의 전체 수는 묶음 전체다`() {
        // **머리글의 두 수가 다른 것을 뜻한다:** 보이는 수(matchedCount)와 일괄 동작이
        // 건드리는 수(itemCount). 하나로 합치면 "항목 1개"를 누른 사용자가 6개를 복원한다.
        val rows = TrashListPlan.build(
            universeDeletion("op1", 5),
            criteria = TrashFilter.Criteria(query = "인물3")
        )
        val header = headers(rows).single()
        assertEquals(1, header.matchedCount)
        assertEquals(6, header.itemCount)
    }

    @Test
    fun `형제 수는 일괄 동작이 있는 묶음에서만 센다`() {
        // 편집 백업은 항목끼리 의존하지 않아 '형제를 못 쓰게 만든다'는 경고가 해당하지 않는다.
        val key = TrashListPlan.operationRowKey("op1", editBackup = true)
        val edits = (1..4).map {
            snap(TrashSnapshot.TYPE_CHARACTER, "덮은$it", "op1", kind = TrashSnapshot.KIND_EDIT_BACKUP)
        }
        assertTrue(
            items(TrashListPlan.build(edits, expandChoices = mapOf(key to true))).all { it.siblingCount == 0 }
        )

        val delKey = TrashListPlan.operationRowKey("op2", editBackup = false)
        val deletes = universeDeletion("op2", 3)
        assertTrue(
            items(TrashListPlan.build(deletes, expandChoices = mapOf(delKey to true))).all { it.siblingCount == 3 }
        )
    }

    @Test
    fun `머리글이 없는 홀 항목은 형제 수를 갖지 않는다`() {
        val rows = TrashListPlan.build(listOf(snap(TrashSnapshot.TYPE_CHARACTER, "가온", "op1")))
        assertEquals(0, items(rows).single().siblingCount)
    }

    @Test
    fun `조건에 맞는 것이 없으면 행이 하나도 없다`() {
        // 화면은 이 상태를 '휴지통이 비었다'와 다른 문구로 말해야 한다 — 같은 문구를 쓰면
        // 사용자가 백업이 사라진 줄 안다.
        val rows = TrashListPlan.build(
            universeDeletion("op1", 3),
            criteria = TrashFilter.Criteria(query = "없는이름")
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `머리글은 자기 항목 바로 앞에 온다`() {
        val k1 = TrashListPlan.operationRowKey("op1", false)
        val k2 = TrashListPlan.operationRowKey("op2", false)
        val snaps = universeDeletion("op1", 2) +
            listOf(
                snap(TrashSnapshot.TYPE_UNIVERSE, "베텔", "op2", at = 2_000L),
                snap(TrashSnapshot.TYPE_CHARACTER, "베텔인", "op2", at = 2_000L)
            )
        val rows = TrashListPlan.build(snaps, expandChoices = mapOf(k1 to true, k2 to true))
        // 최신 삭제(op2)가 먼저 온다 — 그 순서는 TrashGrouping이 정한다.
        assertTrue(rows.first() is TrashListPlan.Row.Header)
        assertEquals("op2", (rows.first() as TrashListPlan.Row.Header).opKey)
        val firstOp1 = rows.indexOfFirst { it is TrashListPlan.Row.Header && it.opKey == "op1" }
        // op1 머리글 다음 줄부터 op1의 항목이다.
        assertTrue(rows[firstOp1 + 1] is TrashListPlan.Row.Item)
    }
}
