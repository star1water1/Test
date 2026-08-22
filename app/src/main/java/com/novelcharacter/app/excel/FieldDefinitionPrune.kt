package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 덮어쓰기(OVERWRITE)에서 **지울 필드 정의를 고르는** 순수 로직 (Android 비의존 — 단위 테스트 대상).
 *
 * 덮어쓰기의 뜻은 *"백업에 없는 정의는 지운다"*이고, 그 삭제는 되돌릴 수 없다 —
 * `CharacterFieldValue.fieldDefinitionId`가 `onDelete = CASCADE`라 **정의를 지우면 값이 함께
 * 사라지고**, 이 경로는 휴지통을 지나지 않는다(`trashForImport` 미호출).
 *
 * **그래서 '백업에 없다'와 '백업이 말하지 않았다'를 갈라야 한다.** 종전 판정은 매칭되지 않은
 * 정의를 전부 지웠는데, 그러면 시트가 **애초에 다루지 않는 구역**의 정의가 *"백업에 없는 것"*으로
 * 오인된다. 실제로 그 일이 났다(B-130): 내보내기가 전역 구역(`universeId IS NULL`)을 싣지 않아
 * **앱이 만든 모든 백업에 전역 필드 행이 없었고**, 그 백업을 덮어쓰기로 되돌리면 전역 필드가
 * 영원히 매칭되지 않아 값과 함께 지워졌다. 파일 단위 안전장치 둘(시트 부재·매칭 0건)은
 * 세계관 필드가 매칭되므로 그대로 통과했다.
 *
 * **판정을 구역마다 따로 둔다** — 한 구역에서 한 행이라도 매칭됐다면 그 시트는
 * 그 구역을 다룬 것이므로 나머지는 진짜 '백업에 없는 것'이고, 한 행도 매칭되지 않았다면
 * 그 시트는 그 구역에 대해 **아무 말도 하지 않은 것**이라 지울 근거가 없다. 이것은 기존
 * '매칭 0건이면 삭제하지 않는다' 가드를 파일 전체가 아니라 구역 단위로 적용한 것과 같다.
 *
 * **`대상` 열이 없으면 종류도 구역의 일부다** (2026.08.22 확장 · R-36).
 * 필드 정의의 정체는 `(universeId, key, entityType)`이고 시트의 `대상` 열이 그 마지막 축을
 * 말한다. 그 열이 **있으면** 시트는 한 장에 전 종류를 실으므로 세계관 하나가 곧 구역이다
 * (캐릭터 행이 매칭됐으면 그 세계관의 사건 필드도 *백업이 다룬 것*이다).
 *
 * 그런데 그 열이 **없는 파일**(열이 생기기 전에 만든 백업 · 사용자가 그 회색 열을 지운 파일)은
 * 모든 행이 캐릭터로 읽힌다 — 그 파일은 사건·작품 필드에 대해 **아무 말도 하지 않은 것**이다.
 * 종전에는 그때도 세계관 하나를 구역으로 봐서, 캐릭터 행이 매칭됐다는 이유로
 * **같은 세계관의 사건·작품 필드 정의가 값과 함께 영구 삭제됐다**(B-130이 막으려던 바로 그
 * 모양의 다른 축이다 — *'없는 열'과 '빈 칸'은 다른 사실이다*, R-36).
 *
 * 근거 없는 삭제를 막는 쪽으로만 좁히므로 **옛 파일의 뜻이 바뀌지 않는다** — 종전에 지워지던
 * 것 중 *그 구역이 시트에 실제로 실려 있던* 것은 그대로 지워진다.
 */
object FieldDefinitionPrune {

    /**
     * @param stale 지울 정의 — 그 구역이 시트에 실려 있었고 그중 매칭되지 않은 것
     * @param protectedFields 지우지 않고 남긴 정의 — 그 구역에 매칭 근거가 하나도 없다.
     *                        **말없이 남기지 않는다**(개발 의도 2번) — 호출부가 이것으로 고지한다.
     */
    data class Outcome(
        val stale: List<FieldDefinition>,
        val protectedFields: List<FieldDefinition>,
        /** 구역에 종류가 들어가 있지 않은가 — 고지가 종류를 말할지 정한다. */
        private val entityTypeStated: Boolean = true
    ) {
        private fun entityTypeInScope(field: FieldDefinition): String? =
            if (entityTypeStated) null else field.entityType

        /**
         * 보호된 구역 목록 — `universeId`의 `null`이 전역(무소속)이다. 고지 문구를 만드는 데 쓴다.
         *
         * 둘째 칸은 **종류가 구역의 일부일 때만** 채워진다(`대상` 열이 없던 파일). 그때는
         * *어느 종류가 남았는지*까지 말해야 사용자가 그 자리를 찾아갈 수 있다.
         */
        val protectedScopes: List<Pair<Long?, String?>>
            get() = protectedFields.map { it.universeId to entityTypeInScope(it) }.distinct()
    }

    /**
     * @param all 현재 DB의 전 필드 정의(전 entityType — 시트가 전 종류를 싣는다)
     * @param matchedIds 이번 가져오기가 시트의 행으로 실제 처리한 정의 id (갱신 + 신규)
     * @param entityTypeStated 시트에 `대상` 열이 **있었는가**. 없으면 그 파일은 캐릭터 외의
     *   종류에 대해 아무 말도 하지 않은 것이므로, 종류가 구역의 일부가 되어 보호된다.
     */
    fun plan(all: List<FieldDefinition>, matchedIds: Set<Long>, entityTypeStated: Boolean): Outcome {
        val outcome = ScopedPrune.plan(
            all, matchedIds,
            idOf = { it.id },
            scopeOf = { it.universeId to if (entityTypeStated) null else it.entityType }
        )
        return Outcome(outcome.stale, outcome.protectedItems, entityTypeStated)
    }
}

/**
 * 덮어쓰기의 **구역 근거 판정** — 필드 정의와 등급 체계가 같은 함수를 쓴다.
 *
 * [FieldDefinitionPrune]의 KDoc이 든 논리를 종류에 매이지 않게 꺼낸 것이다. 두 정리가 판정을
 * 따로 들고 있던 동안 **한쪽만 옳았다**: 필드 정의는 B-130 이후 구역마다 근거를 물었지만,
 * 바로 다음 줄에서 불리는 등급 체계 정리는 전 세계관의 체계를 떠서 매칭되지 않은 것을 전부
 * 지웠다 — 세계관 하나만 담긴 부분 백업을 덮어쓰기로 들이면 **다른 세계관의 등급 체계가
 * 통째로 사라진다**(그 체계를 참조하던 필드는 독자 표로 강등되어, 되돌리려면 표를 손으로
 * 다시 만들어야 한다).
 *
 * 정리 대상이 또 늘어도 이 함수를 부르면 같은 보호가 따라온다.
 */
object ScopedPrune {

    data class Outcome<T>(
        /** 지울 것 — 그 구역이 시트에 실려 있었고 그중 매칭되지 않은 것. */
        val stale: List<T>,
        /** 지우지 않고 남긴 것 — 그 구역에 매칭 근거가 하나도 없다(호출부가 고지한다). */
        val protectedItems: List<T>
    )

    /**
     * @param all 현재 DB의 전량(구역을 가리지 않는다)
     * @param matchedIds 이번 가져오기가 시트의 행으로 실제 처리한 id (갱신 + 신규)
     * @param scopeOf 그 항목이 속한 **구역** — 시트가 그 구역을 다뤘는지가 삭제의 근거다
     */
    fun <T, S> plan(
        all: List<T>,
        matchedIds: Set<Long>,
        idOf: (T) -> Long,
        scopeOf: (T) -> S
    ): Outcome<T> {
        // 매칭된 항목이 있는 구역 = 시트가 다룬 구역. 신규 삽입분도 matchedIds에 들어 있으므로,
        // 그 구역의 항목이 전부 새로 생긴 경우에도 근거가 선다.
        val scopesWithEvidence = HashSet<S>()
        for (item in all) if (idOf(item) in matchedIds) scopesWithEvidence.add(scopeOf(item))

        val stale = ArrayList<T>()
        val protectedItems = ArrayList<T>()
        for (item in all) {
            if (idOf(item) in matchedIds) continue
            if (scopeOf(item) in scopesWithEvidence) stale.add(item) else protectedItems.add(item)
        }
        return Outcome(stale, protectedItems)
    }
}
