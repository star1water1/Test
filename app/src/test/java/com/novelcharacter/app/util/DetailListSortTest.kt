package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 상세 인라인 목록의 보기 정렬(B-85).
 *
 * 이 테스트가 지키는 핵심은 둘이다: ① 기본 기준이 **종전 화면 순서와 같다**(정렬을 열면서
 * 기본 화면이 바뀌면 안 된다) ② 관계의 **수동 순서만** 드래그 재정렬을 허용한다
 * (`allowsManualReorder` — 이 값이 뒤집히면 정렬된 화면의 위치가 `displayOrder`로 저장되어
 * 사용자가 손으로 잡아 둔 순서가 지워진다).
 */
class DetailListSortTest {

    // ── 관계 ──

    private data class Rel(val name: String, val intensity: Int, val type: String)

    private fun sortRels(rows: List<Rel>, mode: DetailListSort.RelationshipMode): List<String> {
        val cmp = DetailListSort.relationships<Rel>(mode, { it.name }, { it.intensity }, { it.type })
        return (if (cmp == null) rows else rows.sortedWith(cmp)).map { it.name }
    }

    @Test
    fun relationships_manual_doesNotSort() {
        assertNull(
            "수동 순서는 정렬하지 않는다(받은 순서 = 저장된 displayOrder 순서)",
            DetailListSort.relationships<Rel>(
                DetailListSort.RelationshipMode.MANUAL, { it.name }, { it.intensity }, { it.type }
            )
        )
        val rows = listOf(Rel("하늘", 1, "친구"), Rel("가람", 9, "적"))
        assertEquals(listOf("하늘", "가람"), sortRels(rows, DetailListSort.RelationshipMode.MANUAL))
    }

    @Test
    fun relationships_name_usesKoreanCollation() {
        val rows = listOf(Rel("하늘", 5, "친구"), Rel("가람", 5, "친구"), Rel("나무", 5, "친구"))
        assertEquals(
            listOf("가람", "나무", "하늘"),
            sortRels(rows, DetailListSort.RelationshipMode.NAME)
        )
    }

    @Test
    fun relationships_intensity_strongestFirst_tieByName() {
        val rows = listOf(
            Rel("하늘", 3, "친구"), Rel("가람", 10, "연인"), Rel("나무", 10, "적")
        )
        assertEquals(
            listOf("가람", "나무", "하늘"),
            sortRels(rows, DetailListSort.RelationshipMode.INTENSITY)
        )
    }

    @Test
    fun relationships_type_groupsByType_tieByName() {
        val rows = listOf(
            Rel("하늘", 5, "친구"), Rel("가람", 5, "적"), Rel("나무", 5, "친구")
        )
        // 유형은 '적' → '친구'(ㅈ이 ㅊ보다 앞), 같은 유형 안에서는 이름순.
        assertEquals(
            listOf("가람", "나무", "하늘"),
            sortRels(rows, DetailListSort.RelationshipMode.TYPE)
        )
    }

    @Test
    fun relationships_onlyManualAllowsDragReorder() {
        // 이 계약이 무너지면 정렬된 화면의 위치가 displayOrder로 저장돼 수동 순서가 지워진다.
        assertTrue(DetailListSort.RelationshipMode.MANUAL.allowsManualReorder)
        for (mode in DetailListSort.RelationshipMode.values()) {
            if (mode == DetailListSort.RelationshipMode.MANUAL) continue
            assertFalse("$mode 에서 드래그가 열려 있다", mode.allowsManualReorder)
        }
    }

    @Test
    fun relationships_sortingNeverChangesMembership() {
        val rows = listOf(
            Rel("하늘", 3, "친구"), Rel("가람", 10, "연인"), Rel("나무", 7, "적")
        )
        val expected = rows.map { it.name }.toSet()
        for (mode in DetailListSort.RelationshipMode.values()) {
            assertEquals("$mode 에서 구성이 변했다", expected, sortRels(rows, mode).toSet())
        }
    }

    // ── 상태 변화 ──

    private fun change(id: Long, year: Int, month: Int? = null, day: Int? = null, key: String = "__birth") =
        CharacterStateChange(
            id = id, characterId = 1L, year = year, month = month, day = day,
            fieldKey = key, newValue = "v"
        )

    private fun sortChanges(
        rows: List<CharacterStateChange>,
        mode: DetailListSort.StateChangeMode
    ): List<Long> = rows.sortedWith(DetailListSort.stateChanges(mode)).map { it.id }

    @Test
    fun stateChanges_chrono_matchesDaoOrder_yearMonthDay() {
        val rows = listOf(
            change(1, 1500, 3, 2), change(2, 1490), change(3, 1500, 1, 9), change(4, 1500, 3, 1)
        )
        assertEquals(
            listOf(2L, 3L, 4L, 1L),
            sortChanges(rows, DetailListSort.StateChangeMode.CHRONO)
        )
    }

    @Test
    fun stateChanges_chrono_yearOnlyComesFirstWithinItsYear() {
        // 월이 비면 0으로 본다 = DAO의 NULL 먼저와 같은 결과.
        val rows = listOf(change(1, 1500, 1), change(2, 1500))
        assertEquals(listOf(2L, 1L), sortChanges(rows, DetailListSort.StateChangeMode.CHRONO))
    }

    @Test
    fun stateChanges_chronoDesc_reversesTimeAxisOnly() {
        val rows = listOf(change(1, 1490), change(2, 1500, 3), change(3, 1500, 1))
        assertEquals(
            listOf(2L, 3L, 1L),
            sortChanges(rows, DetailListSort.StateChangeMode.CHRONO_DESC)
        )
    }

    @Test
    fun stateChanges_chronoDesc_sameDateKeepsAscendingIdTiebreak() {
        // 시간축만 뒤집는다 — 동일 날짜 항목이 정렬을 오갈 때 자리를 바꾸면 데이터가 변한 줄 읽는다.
        val rows = listOf(change(2, 1500, 5, 5), change(1, 1500, 5, 5))
        assertEquals(listOf(1L, 2L), sortChanges(rows, DetailListSort.StateChangeMode.CHRONO_DESC))
        assertEquals(listOf(1L, 2L), sortChanges(rows, DetailListSort.StateChangeMode.CHRONO))
    }

    @Test
    fun stateChanges_field_groupsByFieldThenChrono() {
        val rows = listOf(
            change(1, 1500, key = "직업"), change(2, 1480, key = "직업"), change(3, 1490, key = "__birth")
        )
        assertEquals(
            listOf(3L, 2L, 1L),
            sortChanges(rows, DetailListSort.StateChangeMode.FIELD)
        )
    }

    // ── 관련 사건 ──

    private fun event(
        id: Long, year: Int, month: Int? = null, day: Int? = null,
        displayOrder: Int = 0, createdAt: Long = 0L
    ) = TimelineEvent(
        id = id, year = year, month = month, day = day, description = "e$id",
        displayOrder = displayOrder, createdAt = createdAt
    )

    private fun sortEvents(rows: List<TimelineEvent>, mode: DetailListSort.EventMode): List<Long> =
        rows.sortedWith(DetailListSort.events(mode)).map { it.id }

    @Test
    fun events_chrono_matchesDaoOrder_includingDisplayOrder() {
        val rows = listOf(
            event(1, 1500, 1, 1, displayOrder = 2),
            event(2, 1500, 1, 1, displayOrder = 1),
            event(3, 1499)
        )
        assertEquals(listOf(3L, 2L, 1L), sortEvents(rows, DetailListSort.EventMode.CHRONO))
    }

    @Test
    fun events_chronoDesc_reversesTimeAxisOnly() {
        val rows = listOf(event(1, 1499), event(2, 1500, 5), event(3, 1500, 2))
        assertEquals(listOf(2L, 3L, 1L), sortEvents(rows, DetailListSort.EventMode.CHRONO_DESC))
    }

    @Test
    fun events_recentAdded_newestFirst_regardlessOfYear() {
        val rows = listOf(
            event(1, 1900, createdAt = 100L),
            event(2, 1000, createdAt = 300L),
            event(3, 1500, createdAt = 200L)
        )
        assertEquals(listOf(2L, 3L, 1L), sortEvents(rows, DetailListSort.EventMode.RECENT_ADDED))
    }

    @Test
    fun events_sortingNeverChangesMembership() {
        val rows = listOf(event(1, 1500, 3), event(2, 1490), event(3, 1500, 1, createdAt = 5L))
        val expected = rows.map { it.id }.toSet()
        for (mode in DetailListSort.EventMode.values()) {
            assertEquals("$mode 에서 구성이 변했다", expected, sortEvents(rows, mode).toSet())
            assertNotNull(DetailListSort.events(mode))
        }
    }

    // ===== 필드 관리 보기 정렬 (B-48) =====
    //
    // **방어선은 그 안의 둘이다.**
    // ① *"기본은 정렬하지 않는다(comparator == null)"* — 이것이 **저장 순서와 보기 순서를
    //    가르는 유일한 신호**다. 여기에 비교자를 하나 끼우면 화면이 `displayOrder`가 아닌
    //    순서로 서고, 드래그가 열려 있으므로 그 순서가 그대로 저장된다 —
    //    **편집 폼의 칸 순서와 엑셀 열 순서가 함께 어긋난다**(확정 7-7이 짚은 고유 위험).
    // ② *"비기본 정렬은 드래그를 허용하지 않는다"* — 호출부가 잡기·쓰기 두 자리를 닫는 근거가
    //    이 플래그 하나다. 뒤집히면 코드는 멀쩡한데 순서가 조용히 덮인다.

    private data class Fd(val name: String, val type: String, val group: String)

    private fun sortFields(rows: List<Fd>, mode: DetailListSort.FieldMode): List<String> {
        val cmp = DetailListSort.fields<Fd>(
            mode, name = { it.name }, type = { it.type }, group = { it.group }
        )
        return (if (cmp == null) rows else rows.sortedWith(cmp)).map { it.name }
    }

    @Test
    fun fields_manual_doesNotSort() {
        // ① 저장 순서를 건드리지 않는다는 신호.
        assertNull(DetailListSort.fields<Fd>(DetailListSort.FieldMode.MANUAL, { "" }, { "" }, { "" }))
    }

    @Test
    fun fields_onlyManualAllowsDrag() {
        // ② 잡기·쓰기 두 자리가 이 플래그 하나를 본다.
        assertTrue(DetailListSort.FieldMode.MANUAL.allowsManualReorder)
        for (mode in DetailListSort.FieldMode.entries.filter { it != DetailListSort.FieldMode.MANUAL }) {
            assertFalse("$mode 에서 드래그가 열려 있다", mode.allowsManualReorder)
        }
    }

    @Test
    fun fields_name_isKoreanCollation() {
        val rows = listOf(Fd("힘", "NUMBER", ""), Fd("가문", "TEXT", ""), Fd("나이", "NUMBER", ""))
        assertEquals(listOf("가문", "나이", "힘"), sortFields(rows, DetailListSort.FieldMode.NAME))
    }

    @Test
    fun fields_type_groupsThenNames() {
        val rows = listOf(
            Fd("힘", "NUMBER", ""), Fd("가문", "TEXT", ""),
            Fd("나이", "NUMBER", ""), Fd("성격", "TEXT", "")
        )
        // 같은 종류 안에서는 이름으로 마무리한다 — 안 그러면 같은 종류끼리 순서가 흔들린다.
        assertEquals(listOf("나이", "힘", "가문", "성격"), sortFields(rows, DetailListSort.FieldMode.TYPE))
    }

    @Test
    fun fields_group_pushesUngroupedToTheEnd() {
        // 빈 문자열은 사전순 맨 앞이라, 그냥 두면 목록 머리가 '그룹 없음' 덩어리가 되어
        // 그룹으로 묶어 보려던 목적이 무너진다.
        val rows = listOf(
            Fd("무소속", "TEXT", ""), Fd("키", "NUMBER", "외형"), Fd("가문", "TEXT", "배경")
        )
        // 그룹은 가나다순('배경' → '외형'), 그룹 없는 것만 맨 뒤로 민다.
        assertEquals(listOf("가문", "키", "무소속"), sortFields(rows, DetailListSort.FieldMode.GROUP))
    }

    @Test
    fun fields_sortingNeverChangesMembership() {
        val rows = listOf(
            Fd("힘", "NUMBER", "능력"), Fd("가문", "TEXT", ""), Fd("나이", "NUMBER", "기본")
        )
        val expected = rows.map { it.name }.toSet()
        for (mode in DetailListSort.FieldMode.entries) {
            assertEquals("$mode 에서 구성이 변했다", expected, sortFields(rows, mode).toSet())
        }
    }
}
