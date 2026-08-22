package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 덮어쓰기에서 지울 필드 정의를 고르는 규칙 (B-130).
 *
 * **이 판정이 틀리면 값이 영구히 사라진다** — `CharacterFieldValue.fieldDefinitionId`가
 * `onDelete = CASCADE`이고 이 경로는 휴지통을 지나지 않는다. 그래서 이 시험이 지키는 것은
 * '지우는가'가 아니라 **'지울 근거가 있는가'**다.
 *
 * 방어선은 셋이고, 셋이 서로 반대 방향으로 당긴다 —
 * ① 근거 없는 구역은 지우지 않는다(B-130이 낸 사고)
 * ② 근거 있는 구역의 미매칭은 **그대로 지운다**(①을 넓게 잡으면 덮어쓰기가 통째로 무력해진다)
 * ③ 전역 구역도 근거가 서면 지워진다(①을 영구 면제로 읽으면 지울 길 없는 행이 남는다).
 */
class FieldDefinitionPruneTest {

    private fun field(id: Long, universeId: Long?, key: String) =
        FieldDefinition(id = id, universeId = universeId, key = key, name = key, type = "TEXT")

    // ──────────────────────────────────────────────────────────────────────
    // ① 근거 없는 구역은 대상 밖 — B-130이 실제로 낸 사고
    // ──────────────────────────────────────────────────────────────────────

    /**
     * B-130의 재현 그대로다: 내보내기가 전역 구역을 싣지 않아 **앱이 만든 백업에 전역 행이 없고**,
     * 그 백업을 덮어쓰기로 되돌리면 전역 필드가 영원히 매칭되지 않는다.
     * 파일 단위 안전장치 둘(시트 부재·매칭 0건)은 세계관 필드가 매칭되므로 통과한다 —
     * **구역 단위로 보지 않으면 아무도 막지 못하는 자리다.**
     */
    @Test fun 전역_행이_없는_백업은_전역_필드를_지우지_않는다() {
        val all = listOf(
            field(1, 10, "gender"),
            field(2, 10, "residence"),
            field(3, null, "theme"),     // 전역 — 시트에 행이 없다
            field(4, null, "mood")
        )
        // 시트에는 세계관 10의 두 행만 있었다
        val outcome = FieldDefinitionPrune.plan(all, setOf(1L, 2L), entityTypeStated = true)

        assertTrue("전역 필드가 삭제 대상에 들어갔다 — 값이 CASCADE로 함께 사라진다", outcome.stale.isEmpty())
        assertEquals(listOf("theme", "mood"), outcome.protectedFields.map { it.key })
        assertEquals("고지할 구역은 전역 하나다", listOf<Pair<Long?, String?>>(null to null), outcome.protectedScopes)
    }

    /**
     * 세계관도 같은 규칙을 받는다 — 시트가 그 세계관을 통째로 다루지 않았다면
     * '지워라'인지 '말한 바 없음'인지 가릴 수 없다.
     */
    @Test fun 시트가_다루지_않은_세계관도_지키다() {
        val all = listOf(field(1, 10, "gender"), field(2, 20, "rank"))
        val outcome = FieldDefinitionPrune.plan(all, setOf(1L), entityTypeStated = true)

        assertTrue(outcome.stale.isEmpty())
        assertEquals(listOf<Pair<Long?, String?>>(20L to null), outcome.protectedScopes)
    }

    // ──────────────────────────────────────────────────────────────────────
    // ② 근거가 서면 그대로 지운다 — 덮어쓰기의 뜻이 남아 있어야 한다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **이 시험이 없으면 ①을 넓게 잡아 덮어쓰기를 통째로 무력화해도 아무도 모른다.**
     * 같은 구역에서 한 행이라도 매칭됐다면 시트는 그 구역을 다룬 것이고,
     * 남은 것은 진짜 '백업에 없는 정의'다.
     */
    @Test fun 근거_있는_구역의_미매칭은_지운다() {
        val all = listOf(
            field(1, 10, "gender"),
            field(2, 10, "residence"),   // 백업에서 지워진 것
            field(3, 10, "height")       // 백업에서 지워진 것
        )
        val outcome = FieldDefinitionPrune.plan(all, setOf(1L), entityTypeStated = true)

        assertEquals(listOf("residence", "height"), outcome.stale.map { it.key })
        assertTrue(outcome.protectedFields.isEmpty())
    }

    /**
     * ③ 전역 구역이 **영구 면제가 아니다.** 면제로 두면 한 번 심긴 전역 필드를
     * 엑셀로는 영영 지울 수 없고, 전역 구역에는 정의 관리 화면도 없어
     * (설계 1-9 — 삭제 처분이 세계관과 갈리는 이유) 지울 길 없는 행이 남는다.
     */
    @Test fun 전역_행이_있는_백업은_전역_미매칭을_지운다() {
        val all = listOf(field(1, null, "theme"), field(2, null, "mood"))
        val outcome = FieldDefinitionPrune.plan(all, setOf(1L), entityTypeStated = true)

        assertEquals(listOf("mood"), outcome.stale.map { it.key })
        assertTrue(outcome.protectedFields.isEmpty())
    }

    /**
     * 근거는 **신규 삽입으로도 선다.** 가져오기는 새로 만든 정의의 id도
     * `matchedFieldDefinitionIds`에 넣는데, 그것을 근거로 세지 않으면
     * *"그 구역의 필드가 전부 새로 생긴"* 정상 복원에서 옛 행이 살아남는다.
     */
    @Test fun 신규_삽입만으로도_구역_근거가_선다() {
        val all = listOf(
            field(1, 10, "old"),     // 백업에 없다
            field(2, 10, "fresh")    // 이번 가져오기가 새로 만들었다
        )
        val outcome = FieldDefinitionPrune.plan(all, setOf(2L), entityTypeStated = true)

        assertEquals(listOf("old"), outcome.stale.map { it.key })
    }

    // ──────────────────────────────────────────────────────────────────────
    // 경계
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 한 행도 매칭되지 않으면 **아무 구역도 근거가 없다** — 전부 보호된다.
     * 호출부의 파일 단위 가드(`matchedFieldDefinitionIds.isEmpty()`)가 먼저 걸러
     * 여기까지 오지 않지만, 그 가드가 사라져도 이 함수가 혼자 안전해야 한다.
     */
    @Test fun 매칭이_하나도_없으면_아무것도_지우지_않는다() {
        val all = listOf(field(1, 10, "gender"), field(2, null, "theme"))
        val outcome = FieldDefinitionPrune.plan(all, emptySet(), entityTypeStated = true)

        assertTrue(outcome.stale.isEmpty())
        assertEquals(2, outcome.protectedFields.size)
    }

    /** 구역이 여럿 보호되면 고지도 여럿이다 — 중복 없이 한 번씩. */
    @Test fun 보호된_구역은_중복_없이_센다() {
        val all = listOf(
            field(1, 10, "matched"),
            field(2, 20, "a"), field(3, 20, "b"),
            field(4, null, "g1"), field(5, null, "g2")
        )
        val outcome = FieldDefinitionPrune.plan(all, setOf(1L), entityTypeStated = true)

        assertEquals(listOf<Pair<Long?, String?>>(20L to null, null to null), outcome.protectedScopes)
        assertEquals(4, outcome.protectedFields.size)
    }

    /**
     * `대상` 열이 **있으면** 같은 세계관 안에서 entityType이 달라도 구역은 하나다.
     * 시트가 전 종류를 한 장에 싣기 때문이다(R-29) — 캐릭터 행이 매칭됐으면
     * 그 세계관의 사건 필드도 '백업이 다룬 것'이라 미매칭분은 지워진다.
     */
    @Test fun 대상_열이_있으면_구역_판정은_entityType을_가르지_않는다() {
        val all = listOf(
            FieldDefinition(id = 1, universeId = 10, key = "gender", name = "성별", type = "TEXT"),
            FieldDefinition(
                id = 2, universeId = 10, key = "place", name = "장소", type = "TEXT",
                entityType = FieldDefinition.ENTITY_EVENT
            )
        )
        val outcome = FieldDefinitionPrune.plan(all, setOf(1L), entityTypeStated = true)

        assertEquals(listOf("place"), outcome.stale.map { it.key })
    }

    /**
     * **`대상` 열이 없는 파일은 캐릭터 외의 종류를 말하지 않은 것이다**(R-36 — *없는 열*과
     * *빈 칸*은 다른 사실). 그 파일에서는 모든 행이 캐릭터로 읽히므로 사건·작품 정의는
     * 원리적으로 매칭될 수 없다 — 그때도 세계관 하나를 구역으로 보면, 캐릭터 행이
     * 매칭됐다는 이유로 **그 세계관의 사건·작품 정의가 값과 함께 CASCADE로 사라진다.**
     */
    @Test fun 대상_열이_없으면_사건_작품_정의를_지키다() {
        val all = listOf(
            FieldDefinition(id = 1, universeId = 10, key = "gender", name = "성별", type = "TEXT"),
            FieldDefinition(id = 2, universeId = 10, key = "old", name = "옛필드", type = "TEXT"),
            FieldDefinition(
                id = 3, universeId = 10, key = "place", name = "장소", type = "TEXT",
                entityType = FieldDefinition.ENTITY_EVENT
            ),
            FieldDefinition(
                id = 4, universeId = 10, key = "genre", name = "장르", type = "TEXT",
                entityType = FieldDefinition.ENTITY_NOVEL
            )
        )
        val outcome = FieldDefinitionPrune.plan(all, setOf(1L), entityTypeStated = false)

        // 캐릭터 구역은 근거가 섰으므로 미매칭 캐릭터 필드는 그대로 지워진다(덮어쓰기의 뜻).
        assertEquals(listOf("old"), outcome.stale.map { it.key })
        // 사건·작품은 그 파일이 말한 적이 없다 — 남기고 고지한다.
        assertEquals(listOf("place", "genre"), outcome.protectedFields.map { it.key })
        assertEquals(
            listOf<Pair<Long?, String?>>(
                10L to FieldDefinition.ENTITY_EVENT,
                10L to FieldDefinition.ENTITY_NOVEL
            ),
            outcome.protectedScopes
        )
    }
}
