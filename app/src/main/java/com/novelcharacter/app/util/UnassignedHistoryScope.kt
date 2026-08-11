package com.novelcharacter.app.util

/**
 * **필드 key를 바꿀 때, 미분류 캐릭터의 상태변화 이력을 함께 옮길 것인가** — 순수 판정 (B-13).
 *
 * ## 무엇이 문제인가
 *
 * `CharacterStateChangeDao.migrateFieldKeyForUniverse`는 이관 대상을
 * `JOIN novels n ON c.novelId = n.id`로 고르므로 **`novelId IS NULL`인 캐릭터가 원리적으로
 * 빠진다**(NULL 외래키는 내부 조인에서 떨어진다). 그 캐릭터의 이력은 옛 키를 계속 가리키고,
 * 나중에 작품을 배정받는 순간 **어느 필드에도 걸리지 않는 고아**가 된다.
 *
 * 미분류 캐릭터가 생기는 길은 둘인데 **둘째가 조용하다**:
 * ⓐ 사용자가 작품을 비워 두는 운용(N2 — 정상 경로다)
 * ⓑ **`Character.novelId`의 `onDelete = SET_NULL`** — 작품을 지우면 그 캐릭터들이 통째로
 *    미분류가 된다. **ⓑ에서 온 캐릭터의 이력은 전부 그 세계관의 키 공간에서 쓰인 것**이라,
 *    바로 이 부류가 이관돼야 하는 쪽이다.
 *
 * ## 왜 일괄 UPDATE가 아닌가
 *
 * `WHERE fieldKey = :oldKey`만으로 미분류 캐릭터를 통째로 갈아엎을 수는 없다. 키는
 * `(universeId, entityType, key)`에서만 유일하므로 **세계관 A의 `성별`과 세계관 B의 `성별`은
 * 다른 필드에 같은 키**이고, `character_state_changes`에는 세계관도 `fieldDefinitionId`도
 * 없이 **문자열 `fieldKey` 하나**뿐이다. 이력 행 하나만 보고는 그것이 어느 세계관의 것인지
 * 알 수 없다 — A의 키를 고쳤는데 B의 이력이 함께 바뀌면 그것은 교정이 아니라 오염이다.
 *
 * ## 그래서 무엇으로 가리는가 — 보관 값이 든 세계관
 *
 * 이력과 달리 **값은 세계관을 되찾을 수 있다.** `CharacterFieldValue`는 `fieldDefinitionId`로
 * 정의를 직접 가리키고, 그 정의가 `universeId`를 안다. 미분류 캐릭터가 세계관에서 나올 때
 * 짝을 찾지 못한 값은 **보관 값**으로 그 세계관의 정의를 계속 가리킨 채 남으므로(N2 · B-128),
 * 그것이 *"이 캐릭터의 데이터가 어느 세계관 것인가"*의 단서다. **B-128이 세운 것과 같은
 * 단서를 쓴다** — 여기서 둘째 잣대를 만들면 같은 물음에 두 답이 생긴다(원칙 05).
 *
 * ## 규칙
 *
 * - 보관 값이 **이 세계관만** 가리킨다 → 옮긴다.
 * - 이 세계관을 **포함해 여럿**을 가리킨다 → [Skip.AMBIGUOUS]. 그 캐릭터는 두 세계관을
 *   거쳤으므로 같은 키의 이력이 어느 쪽 것인지 갈리지 않는다.
 * - 단서가 **아예 없다** → [Skip.UNATTRIBUTED]. 처음부터 미분류로 만들어져 전역 구역
 *   필드에 이력을 쓴 캐릭터가 이 부류다.
 * - **이 세계관이 아닌 곳**을 가리킨다 → [Skip.FOREIGN]. 남의 세계관 이력이다.
 *
 * **모르는 것을 앱이 정하지 않는다**(개발 의도 2번 — B-128이 덮어쓰기를 열지 않은 것과 같은
 * 이유다). 다만 조용히 넘기지도 않는다: 가리지 못한 것은 [reportable]이 세어 호출부가 말한다.
 * **[Skip.FOREIGN]만 세지 않는다** — 그쪽은 *모르는 것*이 아니라 *남의 것으로 판명된 것*이라,
 * A의 필드를 고친 사용자에게 B의 데이터를 보고하면 자기 조작과 무관한 숫자를 받는다.
 *
 * ## 왜 순수 계층인가
 *
 * 이 판정이 틀리면 증상은 *"고치지도 않은 세계관의 연표가 조용히 어긋났다"*인데, 저장이 끝난
 * 한참 뒤에야 드러나고 코드는 멀쩡해 보인다. 저장소에 두면 Android 의존이라 어떤 로컬 검사도
 * 닿지 못한다(B-128·B-132가 실증한 자리와 같은 부류다).
 */
object UnassignedHistoryScope {

    /** 옮기지 않은 사유. 호출부가 그대로 말할 수 있어야 하므로 뭉뚱그리지 않는다. */
    enum class Skip {
        /** 이 세계관을 포함해 여러 세계관을 거쳤다 — 같은 키의 이력이 어느 쪽 것인지 갈리지 않는다. */
        AMBIGUOUS,

        /** 어느 세계관도 가리키지 않는다 — 처음부터 미분류였거나 값이 남아 있지 않다. */
        UNATTRIBUTED,

        /** 다른 세계관을 가리킨다 — 이 이름 변경과 무관한 이력이다. */
        FOREIGN
    }

    /**
     * @param migrate 이력을 새 키로 옮길 캐릭터 id. 입력 순서를 보존한다.
     * @param skipped 옮기지 않은 캐릭터 id → 사유. 이력은 그대로 살아 있다(유실 아님).
     */
    data class Plan(
        val migrate: List<Long> = emptyList(),
        val skipped: Map<Long, Skip> = emptyMap()
    ) {
        /**
         * **사용자에게 말할 것** — 가리지 못해 남긴 캐릭터.
         *
         * [Skip.FOREIGN]은 빠진다(위 KDoc의 이유). 이 값이 0이면 호출부는 아무 말도 하지
         * 않는다 — 정상 사용에 잔소리를 달지 않는다(원칙 04).
         */
        val reportable: List<Long>
            get() = skipped.entries.filter { it.value != Skip.FOREIGN }.map { it.key }
    }

    /**
     * @param candidates **미분류이면서 옛 키의 이력을 실제로 든** 캐릭터 id. 이력이 없는
     *   캐릭터까지 넘기면 [Plan.reportable]이 아무 일도 없던 캐릭터를 세어 거짓 고지가 된다.
     * @param attribution 캐릭터 id → 그 캐릭터의 값이 가리키는 **세계관 id 집합**.
     *   전역 구역(`universeId IS NULL`)은 담지 않는다 — 그것은 어느 세계관도 아니다.
     *   목록에 없는 캐릭터는 빈 집합과 같게 본다.
     * @param targetUniverseId 키를 바꾼 필드가 속한 세계관.
     */
    fun plan(
        candidates: List<Long>,
        attribution: Map<Long, Set<Long>>,
        targetUniverseId: Long
    ): Plan {
        val migrate = ArrayList<Long>()
        val skipped = LinkedHashMap<Long, Skip>()
        for (id in candidates) {
            val universes = attribution[id].orEmpty()
            when {
                universes.isEmpty() -> skipped[id] = Skip.UNATTRIBUTED
                targetUniverseId !in universes -> skipped[id] = Skip.FOREIGN
                universes.size > 1 -> skipped[id] = Skip.AMBIGUOUS
                else -> migrate.add(id)
            }
        }
        return Plan(migrate, skipped)
    }
}
