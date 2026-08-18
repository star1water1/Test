package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DefaultFieldTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [guardDefaultFieldSlot] · [DefaultFieldTemplateIndexes] — '기본 필드'의 자리 충돌 게이트 (B-252).
 *
 * **여기가 잠그는 것은 숫자가 아니라 성질 셋이다:**
 * ⓐ **자리를 옮기려다 남의 자리와 부딪치면 키·대상만 되돌린다** — 나머지 편집은 살아 있다.
 *    미리보기에 이 게이트가 없어 *되돌려질 행을 '변경'이라 예고*하던 것이 B-252다.
 * ⓑ **부딪쳤는가는 돌려준 값이 `merged`와 다른가로 알 수 있다** — 가져오기의 경고가 그 위에 선다.
 * ⓒ 정체 사다리는 **코드 우선 → (대상, 필드키)**이고, 색인이 그 답을 DB 왕복 없이 준다(R-53).
 *
 * **왜 순수 시험인가:** 짝인 `analyzeDefaultFieldTemplates`·`importDefaultFieldTemplates`는
 * `ExcelImportService`(Room)에 있어 순수가 닿지 못한다. **닿는 것은 판정 자체**라 여기로 내렸고,
 * 두 자리가 이 함수를 부르는가는 `check_restore_preview_parity.sh`가 본다.
 */
class DefaultFieldSlotGuardTest {

    private fun t(
        id: Long, key: String, name: String = "이름",
        entityType: String = "character", code: String = "C$id", order: Int = 0
    ) = DefaultFieldTemplate(
        id = id, key = key, name = name, type = "TEXT",
        entityType = entityType, code = code, displayOrder = order, createdAt = 0L
    )

    // ── ① 게이트 ────────────────────────────────────────────────────────────────

    @Test
    fun `자리를 옮기지 않는 편집은 게이트를 지나도 그대로다`() {
        val existing = t(1, "age", name = "나이")
        val merged = existing.copy(name = "연령")
        // 그 자리를 자기가 쓰고 있어도 물을 것이 없다 — slotOwner를 부르지도 않는다.
        val kept = guardDefaultFieldSlot(existing, merged) { _, _ ->
            throw AssertionError("자리를 옮기지 않으면 묻지 않아야 한다")
        }
        assertSame(merged, kept)
    }

    @Test
    fun `자리가 비어 있으면 옮긴 그대로 선다`() {
        val existing = t(1, "age")
        val merged = existing.copy(key = "years", name = "연령")
        val kept = guardDefaultFieldSlot(existing, merged) { _, _ -> null }
        assertEquals(merged, kept)
        assertEquals("years", kept.key)
    }

    @Test
    fun `그 자리의 주인이 자기 자신이면 부딪친 것이 아니다`() {
        val existing = t(1, "age")
        val merged = existing.copy(key = "years")
        // 색인이 아직 옛 키로 답하는 상황을 흉내낸다 — id가 같으면 남이 아니다.
        val kept = guardDefaultFieldSlot(existing, merged) { _, _ -> existing }
        assertEquals(merged, kept)
    }

    @Test
    fun `남이 쓰는 자리로 옮기면 키와 대상만 되돌아온다`() {
        val existing = t(1, "age", name = "나이", entityType = "character")
        val other = t(2, "years", entityType = "event")
        val merged = existing.copy(key = "years", entityType = "event", name = "연령")

        val kept = guardDefaultFieldSlot(existing, merged) { _, _ -> other }

        // 되돌아온 것은 자리뿐이다 — 이름 편집은 살아 있어야 한다(가져오기가 그렇게 쓴다).
        assertEquals("age", kept.key)
        assertEquals("character", kept.entityType)
        assertEquals("연령", kept.name)
        // 부딪쳤는가의 신호 — 가져오기의 경고가 이 비교 위에 선다.
        assertNotEquals(merged, kept)
    }

    @Test
    fun `자리만 옮기던 행은 되돌려지면 아무것도 바뀌지 않는다`() {
        val existing = t(1, "age")
        val other = t(2, "years")
        val merged = existing.copy(key = "years")

        val kept = guardDefaultFieldSlot(existing, merged) { _, _ -> other }

        // 부르는 쪽은 이것을 보고 '변경'이 아니라 '동일'로 센다 — 미리보기가 틀렸던 그 자리다.
        assertEquals(existing, kept)
    }

    @Test
    fun `대상만 바꿔도 자리 이동이라 게이트를 지난다`() {
        val existing = t(1, "age", entityType = "character")
        val other = t(2, "age", entityType = "event")
        val merged = existing.copy(entityType = "event")

        val kept = guardDefaultFieldSlot(existing, merged) { _, _ -> other }
        assertEquals("character", kept.entityType)
    }

    @Test
    fun `묻는 자리는 옮겨 갈 자리다`() {
        val existing = t(1, "age", entityType = "character")
        val merged = existing.copy(key = "years", entityType = "event")
        var asked: Pair<String, String>? = null
        guardDefaultFieldSlot(existing, merged) { et, k -> asked = et to k; null }
        // 옛 자리를 물으면 언제나 자기가 잡혀 게이트가 죽는다.
        assertEquals("event" to "years", asked)
    }

    // ── ② 색인 — 사다리·겹·순서 ─────────────────────────────────────────────────

    @Test
    fun `정체는 코드가 먼저다`() {
        val byCode = t(1, "age", code = "AAA")
        val bySlot = t(2, "height", code = "BBB")
        val idx = DefaultFieldTemplateIndexes(listOf(byCode, bySlot))
        // 코드가 가리키는 것과 자리가 가리키는 것이 다르면 코드가 이긴다(R-1).
        assertEquals(byCode, idx.resolve("AAA", "character", "height"))
    }

    @Test
    fun `코드가 비면 자리로 내려간다`() {
        val row = t(1, "age")
        val idx = DefaultFieldTemplateIndexes(listOf(row))
        assertEquals(row, idx.resolve("", "character", "age"))
    }

    @Test
    fun `코드가 있어도 못 찾으면 자리로 내려간다`() {
        val row = t(1, "age", code = "AAA")
        val idx = DefaultFieldTemplateIndexes(listOf(row))
        assertEquals(row, idx.resolve("ZZZ", "character", "age"))
    }

    @Test
    fun `빈 코드끼리는 서로의 답이 되지 않는다`() {
        val a = t(1, "age", code = "")
        val b = t(2, "height", code = "")
        val idx = DefaultFieldTemplateIndexes(listOf(a, b))
        // 빈 코드를 한 통에 실으면 이 조회가 a를 준다 — 자리로 물어야 b가 나온다.
        assertEquals(b, idx.resolve("", "character", "height"))
        assertEquals(null, idx.codeOwner(""))
    }

    @Test
    fun `같은 자리에 둘이 있으면 먼저 실린 것이 답이다`() {
        // 대체하는 질의가 `LIMIT 1`(= rowid 순)이라 id 오름차순이 규약이다.
        val idx = DefaultFieldTemplateIndexes(listOf(t(9, "age", code = "I"), t(3, "age", code = "J")))
        assertEquals(3L, idx.slotOwner("character", "age")?.id)
    }

    @Test
    fun `방금 만든 것을 뒤 행이 곧바로 찾는다`() {
        val idx = DefaultFieldTemplateIndexes(emptyList())
        val created = t(-1, "age", code = "NEW")
        idx.remember(created)
        assertEquals(created, idx.resolve("NEW", "character", "age"))
        assertEquals(created, idx.resolve("", "character", "age"))
        assertEquals(created, idx.slotOwner("character", "age"))
    }

    @Test
    fun `키를 바꾸면 옛 자리는 더는 답하지 않는다`() {
        val row = t(1, "age", code = "AAA")
        val idx = DefaultFieldTemplateIndexes(listOf(row))
        idx.remember(row.copy(key = "years"))
        // 옛 키가 살아 있으면 뒤 행이 *비어 있는 자리*를 점유된 것으로 보고 되돌아간다.
        assertEquals(null, idx.slotOwner("character", "age"))
        assertEquals("years", idx.slotOwner("character", "years")?.key)
    }

    @Test
    fun `순서 기본값은 그 종류의 최댓값을 본다`() {
        val idx = DefaultFieldTemplateIndexes(
            listOf(t(1, "a", order = 4), t(2, "b", entityType = "event", order = 9))
        )
        assertEquals(4, idx.maxOrder("character"))
        assertEquals(9, idx.maxOrder("event"))
        assertEquals(null, idx.maxOrder("novel"))
        // 방금 넣은 것도 함께 센다 — 같은 파일의 뒷 신규가 같은 순서를 받으면 안 된다.
        idx.remember(t(3, "c", order = 7))
        assertEquals(7, idx.maxOrder("character"))
    }
}
