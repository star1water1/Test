package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `util/ImportIdentityIndexes.kt` — 가져오기와 **복원 미리보기**가 함께 쓰는 정체성 색인 (B-236).
 *
 * **무엇을 재는가:** 속도가 아니라 *답*이다. 색인이 대체하는 것은 `… WHERE key = :key LIMIT 1`
 * 꼴의 질의이고, 그 질의가 고르는 행은 **싣는 순서**가 정한다. 순서가 갈리면 같은 `merge*`를
 * 써도 **비교 상대**가 달라져 미리보기가 '변경/동일'을 가져오기와 다르게 말한다(규약 R-33) —
 * 오류도 고지도 없이.
 *
 * **왜 여기서 재는가:** 미리보기의 계수 자체는 DB에 묶여 있어 순수 시험이 원리적으로 닿지
 * 못한다. **닿는 것은 이 둘 — 키 모양과 싣는 순서 — 이고, 갈리는 것도 그 둘이다.**
 *
 * (**절 수를 적지 않는다** — 아래 `── ① ~ ──` 머리가 세는 법이다.)
 */
class ImportIdentityIndexesTest {

    // ── ① DAO가 주는 순서가 아니라 `LIMIT 1`의 순서로 싣는다 ────────────────────
    // 이 색인들이 대체하는 질의는 `ORDER BY`가 없는 `LIMIT 1`이라 사실상 **rowid가 작은 행**을
    // 준다. 그런데 재료를 뜨는 `getAll*`은 표시순·연월일순으로 나온다 — 그대로 실으면
    // 같은 키를 든 행이 둘인 파일에서 **합쳐지는 상대가 바뀐다.**

    private fun change(id: Long, code: String?, charId: Long = 1L, year: Int = 1993, key: String = "직업", value: String = "기사") =
        CharacterStateChange(
            id = id, characterId = charId, year = year, fieldKey = key, newValue = value, code = code
        )

    @Test
    fun `상태변화 — 재료가 뒤섞여 와도 id가 작은 행이 답이다`() {
        // `getAllChangesList()`는 캐릭터·연월일 순이라 id 순서가 아니다.
        val indexes = StateChangeIndexes(listOf(change(9L, "SC-1"), change(2L, "SC-1")))
        assertEquals(2L, indexes.byCode.first("SC-1")?.id)
    }

    @Test
    fun `상태변화 — 자연키는 네 칸이 모두 같아야 잡힌다`() {
        val indexes = StateChangeIndexes(listOf(change(1L, "SC-1")))
        assertEquals(1L, indexes.byNaturalKey.first(StateChangeNaturalKey(1L, 1993, "직업", "기사"))?.id)
        // 한 칸만 달라도 다른 이력이다 — 아니면 가져오기가 남의 행을 덮어쓴다.
        assertNull(indexes.byNaturalKey.first(StateChangeNaturalKey(1L, 1993, "직업", "기사단장")))
        assertNull(indexes.byNaturalKey.first(StateChangeNaturalKey(1L, 1994, "직업", "기사")))
        assertNull(indexes.byNaturalKey.first(StateChangeNaturalKey(2L, 1993, "직업", "기사")))
    }

    @Test
    fun `대결 축 — 재료가 표시순으로 와도 id가 작은 행이 답이다`() {
        // `getAllList()`는 `ORDER BY universeId ASC, displayOrder ASC, id ASC`다.
        val axes = DuelAxisIndexes(
            listOf(
                DuelAxis(id = 8L, universeId = 1L, name = "강함", displayOrder = 0),
                DuelAxis(id = 3L, universeId = 1L, name = "강함", displayOrder = 1)
            )
        )
        assertEquals(3L, axes.byNameKey.first(DuelAxisNameKey(1L, DuelAxis.TARGET_CHARACTER, "강함"))?.id)
    }

    @Test
    fun `세력 — 재료가 표시순으로 와도 id가 작은 행이 답이다`() {
        // `getAllFactionsList()`는 `ORDER BY displayOrder ASC, createdAt DESC`다.
        val factions = FactionIdentityIndexes(
            listOf(
                Faction(id = 7L, universeId = 1L, name = "기사단", autoRelationType = "동료", displayOrder = 0),
                Faction(id = 4L, universeId = 1L, name = "기사단", autoRelationType = "동료", displayOrder = 1)
            )
        )
        assertEquals(4L, factions.byNameKey.first(FactionNameKey("기사단", 1L))?.id)
    }

    @Test
    fun `대결 기록 — 코드가 겹치면 id가 작은 판이 답이다`() {
        val matches = DuelMatchIndexes(
            listOf(
                DuelMatch(id = 5L, axisId = 1L, aCode = "A", bCode = "B", code = "DM-1"),
                DuelMatch(id = 1L, axisId = 1L, aCode = "C", bCode = "D", code = "DM-1")
            )
        )
        assertEquals(1L, matches.byCode.first("DM-1")?.id)
    }

    // ── ② 관계만 순서 규약이 다르다 — id가 아니라 표시순이다 ────────────────────
    // 호출부는 쌍의 목록에서 `find { 유형이 같다 }`로 하나를 고르는데, 그 목록을 주던 질의가
    // `getRelationshipsForCharacterList`(= `ORDER BY displayOrder ASC, createdAt DESC`)다.
    // **여기서 id로 실으면 같은 쌍·같은 유형이 둘인 파일에서 고르는 상대가 바뀐다.**

    private fun rel(id: Long, a: Long, b: Long, type: String = "친구", order: Int = 0, createdAt: Long = 100L, code: String? = null) =
        CharacterRelationship(
            id = id, characterId1 = a, characterId2 = b, relationshipType = type,
            displayOrder = order, createdAt = createdAt, code = code
        )

    @Test
    fun `관계 — 표시순이 앞선 행이 먼저다 (id가 더 커도)`() {
        val rels = RelationshipIndexes(listOf(rel(2L, 1L, 2L, order = 5), rel(9L, 1L, 2L, order = 1)))
        assertEquals(listOf(9L, 2L), rels.pair(1L, 2L).map { it.id })
    }

    @Test
    fun `관계 — 표시순이 같으면 나중에 만들어진 행이 먼저다`() {
        val rels = RelationshipIndexes(
            listOf(rel(1L, 1L, 2L, order = 0, createdAt = 100L), rel(2L, 1L, 2L, order = 0, createdAt = 500L))
        )
        assertEquals(listOf(2L, 1L), rels.pair(1L, 2L).map { it.id })
    }

    @Test
    fun `관계 — 쌍은 방향이 없다`() {
        val rels = RelationshipIndexes(listOf(rel(1L, 7L, 3L)))
        assertEquals(listOf(1L), rels.pair(3L, 7L).map { it.id })
        assertEquals(rels.pair(3L, 7L), rels.pair(7L, 3L))
    }

    @Test
    fun `관계 — 다른 쌍은 섞이지 않는다`() {
        val rels = RelationshipIndexes(listOf(rel(1L, 1L, 2L), rel(2L, 1L, 3L)))
        assertEquals(listOf(1L), rels.pair(1L, 2L).map { it.id })
        assertEquals(emptyList<Long>(), rels.pair(2L, 3L).map { it.id })
    }

    // ── ③ null은 값이다 — 전역·미지정을 별개의 키로 가른다 ──────────────────────
    // SQL이 `universeId IS NULL`을 따로 묻는 자리들이다. 한 키로 뭉치면 **전역 필드와 세계관
    // 필드가 서로를 찾아** 가져오기가 남의 정의를 덮어쓴다.

    @Test
    fun `필드 정의 — 전역과 세계관 소속은 서로를 찾지 않는다`() {
        val defs = FieldDefinitionIndexes(
            listOf(
                FieldDefinition(id = 1L, universeId = null, key = "나이", name = "나이", type = "NUMBER"),
                FieldDefinition(id = 2L, universeId = 5L, key = "나이", name = "나이", type = "NUMBER")
            )
        )
        assertEquals(1L, defs.find(null, "나이", FieldDefinition.ENTITY_CHARACTER)?.id)
        assertEquals(2L, defs.find(5L, "나이", FieldDefinition.ENTITY_CHARACTER)?.id)
        assertNull(defs.find(9L, "나이", FieldDefinition.ENTITY_CHARACTER))
    }

    @Test
    fun `필드 정의 — 대상 종류가 다르면 다른 필드다`() {
        val defs = FieldDefinitionIndexes(
            listOf(FieldDefinition(id = 1L, universeId = 5L, key = "나이", name = "나이", type = "NUMBER"))
        )
        assertNull(defs.find(5L, "나이", FieldDefinition.ENTITY_EVENT))
    }

    @Test
    fun `작품 제목 — 세계관 미지정은 별개의 키다`() {
        val index = ImportLookupIndex<NovelTitleKey, Novel>(
            idOf = { it.id }, keyOf = { NovelTitleKey(it.title, it.universeId) }
        )
        index.load(
            listOf(
                Novel(id = 1L, title = "은하수", universeId = null),
                Novel(id = 2L, title = "은하수", universeId = 3L)
            )
        )
        assertEquals(1L, index.first(NovelTitleKey("은하수", null))?.id)
        assertEquals(2L, index.first(NovelTitleKey("은하수", 3L))?.id)
        assertNull(index.first(NovelTitleKey("은하수", 9L)))
    }

    @Test
    fun `관계 변화 — 월·일 미기입은 별개의 키다`() {
        val changes = RelationshipChangeIndexes(
            listOf(
                CharacterRelationshipChange(id = 1L, relationshipId = 10L, year = 1993, relationshipType = "친구"),
                CharacterRelationshipChange(id = 2L, relationshipId = 10L, year = 1993, month = 5, relationshipType = "친구")
            )
        )
        assertEquals(1L, changes.byNaturalKey.first(RelChangeNaturalKey(10L, 1993, null, null))?.id)
        assertEquals(2L, changes.byNaturalKey.first(RelChangeNaturalKey(10L, 1993, 5, null))?.id)
        assertNull(changes.byNaturalKey.first(RelChangeNaturalKey(10L, 1993, 5, 1)))
    }

    // ── ④ 숫자 칸의 폭이 키를 조용히 죽이지 않는가 ─────────────────────────────
    // `listOf(charId, year, …)`로 키를 지으면 코틀린이 원소 타입을 첫 원소에서 추론해
    // `Int` 연도를 `Long`으로 올려 버리고, 그러면 **모든 조회가 null**이 되는데 컴파일도 되고
    // 예외도 없다. `data class`가 그 자리를 컴파일러에게 넘긴 것이라, 누가 다시 목록으로
    // 바꾸면 이 절이 먼저 죽는다.

    @Test
    fun `자연키의 Int 칸이 Long 칸과 섞이지 않는다`() {
        val indexes = StateChangeIndexes(listOf(change(1L, null, charId = 1993L, year = 1)))
        // 칸의 뜻이 뒤바뀐 키로는 잡히지 않아야 한다 — 목록 키였다면 둘 다 `[1993, 1]`이다.
        assertEquals(1L, indexes.byNaturalKey.first(StateChangeNaturalKey(1993L, 1, "직업", "기사"))?.id)
        assertNull(indexes.byNaturalKey.first(StateChangeNaturalKey(1L, 1993, "직업", "기사")))
    }

    // ── ⑤ 쓴 것은 곧바로 읽히고, 옛 키는 그 행을 잊는다 ────────────────────────
    // 가져오기 쪽 규약이다(미리보기는 쓰지 않는다). 한 묶음이 **두 축을 함께** 갱신하지 않으면
    // 뒤 행이 *이미 다른 이력이 된 행*을 옛 키로 다시 잡는다.

    @Test
    fun `상태변화 — 자연키가 바뀌면 두 축이 함께 옮겨간다`() {
        val indexes = StateChangeIndexes(listOf(change(1L, "SC-1", year = 1993)))
        indexes.remember(change(1L, "SC-1", year = 1994))
        assertNull(indexes.byNaturalKey.first(StateChangeNaturalKey(1L, 1993, "직업", "기사")))
        assertEquals(1L, indexes.byNaturalKey.first(StateChangeNaturalKey(1L, 1994, "직업", "기사"))?.id)
        assertEquals(1994, indexes.byCode.first("SC-1")?.year)
    }

    @Test
    fun `관계 — 쌍이 바뀌면 옛 쌍은 그 관계를 잊는다`() {
        val rels = RelationshipIndexes(listOf(rel(1L, 1L, 2L, code = "R-1")))
        rels.remember(rel(1L, 1L, 3L, code = "R-1"))
        assertEquals(emptyList<Long>(), rels.pair(1L, 2L).map { it.id })
        assertEquals(listOf(1L), rels.pair(1L, 3L).map { it.id })
        assertEquals(3L, rels.byCode.first("R-1")?.characterId2)
    }

    @Test
    fun `세력 — 방금 만든 행이 다음 행에 곧바로 보인다`() {
        val factions = FactionIdentityIndexes(emptyList())
        assertNull(factions.byNameKey.first(FactionNameKey("기사단", 1L)))
        factions.remember(Faction(id = 5L, universeId = 1L, name = "기사단", autoRelationType = "동료", code = "F-1"))
        assertEquals(5L, factions.byNameKey.first(FactionNameKey("기사단", 1L))?.id)
        assertEquals(5L, factions.byCode.first("F-1")?.id)
    }

    @Test
    fun `필드 정의 — 갱신은 자리를 지킨다`() {
        val defs = FieldDefinitionIndexes(
            listOf(
                FieldDefinition(id = 1L, universeId = 5L, key = "나이", name = "나이", type = "NUMBER"),
                FieldDefinition(id = 2L, universeId = 5L, key = "나이", name = "나이(중복)", type = "NUMBER")
            )
        )
        defs.remember(FieldDefinition(id = 1L, universeId = 5L, key = "나이", name = "연령", type = "NUMBER"))
        val found = defs.find(5L, "나이", FieldDefinition.ENTITY_CHARACTER)
        assertEquals(1L, found?.id)
        assertEquals("연령", found?.name)
    }

    // ── ⑥ 빈 코드는 실리지 않는다 — 빈 코드끼리 서로를 찾으면 남의 행을 덮어쓴다 ──

    @Test
    fun `빈 코드는 색인에 실리지 않는다`() {
        val indexes = StateChangeIndexes(listOf(change(1L, ""), change(2L, null)))
        assertNull(indexes.byCode.first(""))
        // 자연키 축에는 그대로 있다 — 코드가 없을 뿐 행은 실재한다.
        assertNotEquals(null, indexes.byNaturalKey.first(StateChangeNaturalKey(1L, 1993, "직업", "기사")))
    }
}
