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
 * **판정을 구역(universeId)마다 따로 둔다** — 한 구역에서 한 행이라도 매칭됐다면 그 시트는
 * 그 구역을 다룬 것이므로 나머지는 진짜 '백업에 없는 것'이고, 한 행도 매칭되지 않았다면
 * 그 시트는 그 구역에 대해 **아무 말도 하지 않은 것**이라 지울 근거가 없다. 이것은 기존
 * '매칭 0건이면 삭제하지 않는다' 가드를 파일 전체가 아니라 구역 단위로 적용한 것과 같다.
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
        val protectedFields: List<FieldDefinition>
    ) {
        /** 보호된 구역 목록 — `null`이 전역(무소속) 구역이다. 고지 문구를 만드는 데 쓴다. */
        val protectedScopes: List<Long?> get() = protectedFields.map { it.universeId }.distinct()
    }

    /**
     * @param all 현재 DB의 전 필드 정의(전 entityType — 시트가 전 종류를 싣는다)
     * @param matchedIds 이번 가져오기가 시트의 행으로 실제 처리한 정의 id (갱신 + 신규)
     */
    fun plan(all: List<FieldDefinition>, matchedIds: Set<Long>): Outcome {
        // 매칭된 정의가 있는 구역 = 시트가 다룬 구역. 신규 삽입분도 matchedIds에 들어 있으므로,
        // 그 구역의 정의가 전부 새로 생긴 경우에도 근거가 선다.
        val scopesWithEvidence: Set<Long?> =
            all.filterTo(ArrayList()) { it.id in matchedIds }.mapTo(HashSet()) { it.universeId }

        val stale = ArrayList<FieldDefinition>()
        val protectedFields = ArrayList<FieldDefinition>()
        for (field in all) {
            if (field.id in matchedIds) continue
            if (field.universeId in scopesWithEvidence) stale.add(field) else protectedFields.add(field)
        }
        return Outcome(stale, protectedFields)
    }
}
