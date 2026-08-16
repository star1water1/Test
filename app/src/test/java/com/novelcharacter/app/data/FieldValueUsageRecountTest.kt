package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.repository.FieldValueRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1(U-0) 회귀 고정 — **수확 뒤 집계가 맞는가**.
 *
 * 종전 결함: 수확(캐릭터 저장마다)과 집계(앱 1회 백필·엑셀 임포트·해당 필드 화면 진입)가
 * 서로 다른 축에서 돌아, 저장으로 들어온 값이 `usageCount = 0`인 채 남았다
 * (실측 482엔트리 중 318 = 66%가 0인데 그 값이 전부 실제 데이터에 있었다).
 *
 * 여기서는 DB에서 떼어 낸 두 순수 판정을 합성 데이터로 고정한다:
 *  - [FieldValueRules.recountedEntries] — 집계 산수(토큰화·별칭·diff)
 *  - [FieldValueRules.recountTargetsForCharacter] — 재집계 **대상 선정**
 */
class FieldValueUsageRecountTest {

    private fun field(
        id: Long,
        universeId: Long? = 1L,
        entityType: String = FieldDefinition.ENTITY_CHARACTER,
        config: String = "{}"
    ) = FieldDefinition(
        id = id,
        universeId = universeId,
        key = "f$id",
        name = "필드$id",
        type = "TEXT",
        entityType = entityType,
        config = config
    )

    private fun entry(id: Long, value: String, usageCount: Int = 0, aliasesJson: String = "[]") =
        FieldValueEntry(
            id = id,
            fieldDefinitionId = 1L,
            value = value,
            usageCount = usageCount,
            aliasesJson = aliasesJson
        )

    // ===== 집계 산수 =====

    /** 수확 직후의 전형: 엔트리는 0으로 들어왔고 값은 실재한다 → 실재 수만큼 올라와야 한다. */
    @Test
    fun `수확으로 들어온 0짜리 엔트리가 실제 사용 수로 채워진다`() {
        val fd = field(1L)
        val entries = listOf(entry(10L, "남"), entry(11L, "여"))
        val values = listOf("남", "남", "여")

        val changed = FieldValueRules.recountedEntries(fd, entries, values)
            .associateBy { it.id }

        assertEquals(2, changed[10L]?.usageCount)
        assertEquals(1, changed[11L]?.usageCount)
    }

    /**
     * **이 계획의 핵심 회귀**: 이미 있는 값으로 바꾸면 새 토큰이 하나도 안 생기지만
     * 양쪽 집계가 모두 달라진다. 옛 값은 내려가고 새 값은 올라가야 한다.
     */
    @Test
    fun `기존 값으로 바꾼 편집이 양쪽 집계에 모두 반영된다`() {
        val fd = field(1L)
        // 편집 전 상태가 반영된 엔트리: 남 1, 여 0
        val entries = listOf(entry(10L, "남", usageCount = 1), entry(11L, "여", usageCount = 0))
        // 편집 후 실제 데이터: 그 캐릭터가 '여'가 됐다
        val values = listOf("여")

        val changed = FieldValueRules.recountedEntries(fd, entries, values)
            .associateBy { it.id }

        assertEquals("옛 값이 0으로 내려와야 한다", 0, changed[10L]?.usageCount)
        assertEquals("새 값이 1로 올라와야 한다", 1, changed[11L]?.usageCount)
    }

    /** 값을 지우면(빈 문자열) 집계가 0이 된다 — 조용히 옛 수치로 남지 않는다. */
    @Test
    fun `값을 비우면 집계가 0으로 내려간다`() {
        val fd = field(1L)
        val entries = listOf(entry(10L, "남", usageCount = 3))

        val changed = FieldValueRules.recountedEntries(fd, entries, listOf("", "  "))

        assertEquals(1, changed.size)
        assertEquals(0, changed[0].usageCount)
    }

    /**
     * **`usageCount`는 "지금 쓰이는 횟수"다** — 쓰인 적 있는 횟수가 아니다
     * (2026.08.10 사용자 확정 20번 ㄱ1 · B-60).
     *
     * 여기 넘기는 `rawValues`는 **현재 값 세 표**(캐릭터·사건·작품)에서만 온다. 상태변화
     * 이력은 넘어오지 않으므로, 이력에만 있던 값의 엔트리는 **0으로 떨어지는 것이 옳다.**
     *
     * **이 시험이 지키는 것은 산수가 아니라 정의다.** 종전에는 수확이 이력까지 담고 집계는
     * 현재 값만 세서 두 쪽이 다른 모집단을 봤고, 그 엔트리는 `usageCount`가 영원히 0인 채
     * **'미사용 자동수집 정리'의 대상**이 됐다 — 살아 있는 값을 지우자고 권하는 자리였다.
     * 수확 쪽을 좁혀 맞췄고(`FieldValueLibraryRepository.harvestUniversesOrThrow`),
     * 그것이 다시 넓어지지 않는지는 `tools/check_harvest_population.sh`가 기계로 지킨다.
     */
    @Test
    fun `현재 값에 없는 엔트리는 이력에 있어도 0이다`() {
        val fd = field(1L)
        val entries = listOf(entry(10L, "흑발", usageCount = 1), entry(11L, "은발", usageCount = 1))
        // '은발'은 상태변화 이력에만 있던 값이라 현재 값 목록에 없다.
        val currentValues = listOf("흑발", "흑발")

        val changed = FieldValueRules.recountedEntries(fd, entries, currentValues)
            .associateBy { it.id }

        assertEquals(2, changed[10L]?.usageCount)
        assertEquals(0, changed[11L]?.usageCount)
    }

    /** 무변화면 갱신 대상이 0건이어야 한다 — 무효화 폭풍 방지(diff-only 계약). */
    @Test
    fun `이미 맞는 집계는 갱신 대상에 들어가지 않는다`() {
        val fd = field(1L)
        val entries = listOf(entry(10L, "남", usageCount = 2), entry(11L, "여", usageCount = 1))

        val changed = FieldValueRules.recountedEntries(fd, entries, listOf("남", "남", "여"))

        assertTrue("무변화면 0건 쓰기여야 한다", changed.isEmpty())
    }

    /** 별칭 표기는 canonical로 접혀서 집계된다 — 미trim·변형 표기가 따로 세어지지 않는다. */
    @Test
    fun `별칭 표기도 canonical 엔트리로 집계된다`() {
        val fd = field(1L)
        val entries = listOf(entry(10L, "남", aliasesJson = """["남성","male"]"""))

        val changed = FieldValueRules.recountedEntries(fd, entries, listOf("남", "남성", "male"))

        assertEquals(1, changed.size)
        assertEquals(3, changed[0].usageCount)
    }

    /** 콤마 구분 필드는 토큰마다 센다 — 한 캐릭터가 같은 필드에 여러 값을 가질 수 있다. */
    @Test
    fun `콤마 구분 필드는 토큰 단위로 집계된다`() {
        val fd = field(1L, config = """{"displayFormat":"comma_list"}""")
        val entries = listOf(entry(10L, "검술"), entry(11L, "마법"))

        val changed = FieldValueRules.recountedEntries(fd, entries, listOf("검술, 마법", "검술"))
            .associateBy { it.id }

        assertEquals(2, changed[10L]?.usageCount)
        assertEquals(1, changed[11L]?.usageCount)
    }

    // ===== 재집계 대상 선정 =====

    /**
     * 대상은 '새 토큰이 생긴 필드'가 아니라 **그 캐릭터가 건드릴 수 있는 필드 전체**여야 한다.
     * 좁히면 위 `기존 값으로 바꾼 편집`이 아예 재집계되지 않아 결함이 그대로 남는다.
     */
    @Test
    fun `대상은 세계관의 캐릭터 필드 전체다 — 토큰이 안 생긴 필드도 포함`() {
        val defs = listOf(field(1L), field(2L), field(3L))

        // 저장에서 토큰이 새로 생긴 필드는 1번 하나뿐이지만…
        val targets = FieldValueRules.recountTargetsForCharacter(1L, defs, setOf(1L))

        assertEquals("세계관의 캐릭터 필드가 모두 대상이어야 한다", setOf(1L, 2L, 3L), targets)
    }

    /** 다른 세계관·다른 엔티티 타입의 필드는 끌어오지 않는다 — 대상을 넓히지도 않는다. */
    @Test
    fun `다른 세계관과 사건 필드는 대상에서 빠진다`() {
        val defs = listOf(
            field(1L, universeId = 1L),
            field(2L, universeId = 2L),
            field(3L, universeId = 1L, entityType = FieldDefinition.ENTITY_EVENT)
        )

        val targets = FieldValueRules.recountTargetsForCharacter(1L, defs, emptySet())

        assertEquals(setOf(1L), targets)
    }

    /**
     * 세계관이 없는 캐릭터(작품 미배정)라도 **건드린 필드는 반드시 재집계**한다 —
     * 세계관을 못 찾았다고 집계를 통째로 건너뛰면 그 값이 영원히 0으로 남는다.
     *
     * 여기 defs는 전부 세계관 1 소속이라 **끌어올 전역 필드가 없다** — 그래서 답이 건드린 것뿐이다.
     * 전역 필드가 있을 때 어떻게 되는지는 아래 시험이 든다(B-203이 연 자리다).
     */
    @Test
    fun `세계관이 없으면 건드린 필드는 그대로 대상이다`() {
        val defs = listOf(field(1L), field(2L))

        val targets = FieldValueRules.recountTargetsForCharacter(null, defs, setOf(7L))

        assertEquals(setOf(7L), targets)
    }

    /**
     * **전역(무소속) 구역도 구역이다** (B-203).
     *
     * 구역 전체를 대상에 넣는 이유가 *"이 저장이 다른 소유자에서 쓰이던 값을 지웠을 수도 있다"*인데,
     * 그 사정은 전역 구역에서 **더 세게** 성립한다 — 그 구역의 필드는 무소속 소유자 전부가 공유한다.
     * 종전에는 `if (universeId != null)` 때문에 이 갈래가 통째로 빠져, **값을 비운 전역 필드의
     * `usageCount`가 높은 채로 남았다**(비운 필드는 새 토큰이 없어 `touched`에도 안 든다).
     */
    @Test
    fun `무소속 소유자는 전역 구역의 필드를 대상으로 삼는다`() {
        val defs = listOf(
            field(1L, universeId = null),
            field(2L, universeId = null),
            field(3L, universeId = 1L),                                        // 남의 구역
            field(4L, universeId = null, entityType = FieldDefinition.ENTITY_EVENT)  // 남의 축
        )

        val targets = FieldValueRules.recountTargetsForCharacter(null, defs, setOf(7L))

        assertEquals(setOf(7L, 1L, 2L), targets)
    }

    /** 사건·작품 축도 같은 함수를 지난다 — 세 축이 갈리면 R-29의 짝 규칙이 깨진다. */
    @Test
    fun `사건과 작품 축도 전역 구역을 함께 본다`() {
        val defs = listOf(
            field(1L, universeId = null, entityType = FieldDefinition.ENTITY_EVENT),
            field(2L, universeId = null, entityType = FieldDefinition.ENTITY_NOVEL),
            field(3L, universeId = null)   // 캐릭터 축 — 두 호출 어느 쪽에도 안 든다
        )

        assertEquals(
            setOf(1L),
            FieldValueRules.recountTargets(null, FieldDefinition.ENTITY_EVENT, defs, emptySet())
        )
        assertEquals(
            setOf(2L),
            FieldValueRules.recountTargets(null, FieldDefinition.ENTITY_NOVEL, defs, emptySet())
        )
    }
}
