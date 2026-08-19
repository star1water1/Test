package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef
import com.novelcharacter.app.data.model.Universe

/**
 * 통계 계산의 입력이 되는 **인메모리 스냅샷** — 한 번 적재해 여러 계산이 나눠 쓴다.
 *
 * ## 왜 `util/`에 사는가 (B-251 ⓓ · 2026.08.19)
 *
 * 종전에는 `ui/stats/StatsDataProvider.kt` 안에 선언돼 있었는데, **그것이 계층을 거꾸로
 * 흐르게 했다.** 아키텍처 2장이 정한 방향은 `ui → repository → dao → Room`이고
 * **`util`은 아무도 바라보지 않는다**인데, [ConsistencyChecker]가 `util/`에 살면서
 * `ui.stats.StatsSnapshot`을 import 하고 있었다 — **`util` → `ui`, 유일하게 남은 역방향**이다.
 *
 * 대가는 문서상의 흠이 아니라 **검증의 구멍**이었다:
 *  · `tools/probe_compile.sh`는 `ui` 계층을 통째로 빼므로 [ConsistencyChecker]가
 *    **목록에 있으면서도 타입 검사되지 않았다**(오류 51건 — 전부 이 import 한 줄의 그림자).
 *  · `tools/run_jvm_tests.sh`도 그 파일을 싣지 못했다. **즉 컴파일 증명이 CI뿐이었고
 *    정합성 검사기 자체를 재는 시험은 0건이었다.**
 *
 * 이 타입은 **엔티티 목록과 [CompletionWeights]뿐**이라 애초에 순수하다 — 화면에 살 이유가
 * 없었고, 옮기니 프로브와 순수 하네스가 함께 닿는다.
 *
 * ## 담는 것과 담지 않는 것
 *
 * **목록은 담고 계산은 담지 않는다.** 한 축에서 수만 행이 될 수 있는 것(대결 판)은
 * 스냅샷에 넣지 않는다 — 넣으면 그것을 순회하는 계산이 스냅샷 하나에 통째로 붙는다
 * (`docs/scalability_performance_2026-07.md` 7장 4단계 3번이 묻는 그 질문이다).
 */
data class StatsSnapshot(
    val characters: List<Character>,
    val novels: List<Novel>,
    val universes: List<Universe>,
    val events: List<TimelineEvent>,
    val relationships: List<CharacterRelationship>,
    val relationshipChanges: List<CharacterRelationshipChange>,
    val tags: List<CharacterTag>,
    val nameBank: List<NameBankEntry>,
    val stateChanges: List<CharacterStateChange>,
    val fieldDefinitions: List<FieldDefinition>,
    val fieldValues: List<CharacterFieldValue>,
    val crossRefs: List<TimelineCharacterCrossRef>,
    val factions: List<Faction> = emptyList(),
    val factionMemberships: List<FactionMembership> = emptyList(),
    val eventNovelCrossRefs: List<TimelineEventNovelCrossRef> = emptyList(),
    // 사건 커스텀 필드 (B-10) — "모든 필드가 통계에서 분석 가능해야 한다"(원칙 02)
    val eventFieldDefinitions: List<FieldDefinition> = emptyList(),
    val eventFieldValues: List<EventFieldValue> = emptyList(),
    // 작품 커스텀 필드 (확-3) — 같은 원칙. 종류를 만들 수 있게 해 놓고 통계에서 빼면
    // 그 필드는 '있는데 분석되지 않는 필드'가 된다(원칙 02 위반의 가장 흔한 형태).
    val novelFieldDefinitions: List<FieldDefinition> = emptyList(),
    val novelFieldValues: List<NovelFieldValue> = emptyList(),
    // 값 데이터 라이브러리 — 별칭 접기·표시 라벨·카테고리의 단일 소스 (구 valueLabels/valueCategories 대체)
    val valueEntries: List<com.novelcharacter.app.data.model.FieldValueEntry> = emptyList(),
    /**
     * 대결 축 목록 (B-117) — 순위 화면이 *"무엇으로 줄 세울까"*의 선택지에 싣는다.
     *
     * **싣는 것은 축이지 판이 아니다.** 판은 한 축에서만 수만 행이 될 수 있어 스냅샷에 담으면
     * 그것을 순회하는 계산이 스냅샷 하나에 통째로 붙는다 —
     * `scalability_performance` 7장 4단계 3번이 묻는 바로 그 질문이고, 이 판의 답은
     * **"목록은 더하되 계산은 더하지 않는다"**이다. 점수는 사용자가 축 하나를 고른 뒤에
     * [com.novelcharacter.app.data.repository.DuelRepository.scoresOf]가 그때 낸다.
     */
    val duelAxes: List<com.novelcharacter.app.data.model.DuelAxis> = emptyList(),
    /**
     * 완성도 필수 가중 (B-100). [com.novelcharacter.app.ui.stats.StatsDataProvider.loadSnapshot]이 설정에서 읽어 싣는다 —
     * 계산 함수들이 `Context`를 모르게 하기 위해서다(순수 하네스가 그대로 돈다).
     * 필터본은 `copy`로 그대로 물려받는다.
     */
    val completionWeights: CompletionWeights = CompletionWeights.DEFAULT,
    /**
     * "작품 미배정" 스코프 표시 — novels/universes가 비므로 캐릭터 모수·필드 완성도를
     * novelId 경유 대신 스냅샷 자체(캐릭터 전체·보존 정의) 기준으로 계산해야 한다.
     * [com.novelcharacter.app.ui.stats.StatsDataProvider.filterByNovel]의 sentinel 분기만 true로 만든다.
     */
    val unassignedScope: Boolean = false,
    /**
     * 이 스코프에서 **산출할 수 없는** 계산(CALCULATED) 필드의 수 (B-30).
     *
     * 0이 아니면 화면이 *"작품 미배정이라 계산 필드는 산출할 수 없다"*를 한 줄로 알린다.
     * **값을 만들어 내지 않는다** — 확정 7-4가 기각한 쪽(필드값으로 세계관 역추적)은 참조
     * 필드가 빠지면 수식이 **조용히 다른 값**을 내어, 틀린 값이 맞는 값처럼 보인다.
     *
     * **왜 '빈 칸'이 아니라 '고지'여야 하는가:** 계산 필드는 저장 행이 없어
     * [com.novelcharacter.app.ui.stats.StatsDataProvider.filterByNovelUnassigned]의 *참조된 정의만 남긴다*는 규칙에
     * 걸려 **정의째 사라진다.** 그래서 사용자가 보는 것은 빈 값이 아니라 **필드의 부재**이고,
     * 부재는 *"값이 없구나"*가 아니라 *"내가 안 만들었나?"*로 읽힌다.
     * 같은 처분이 이미 옆에 있다 — [com.novelcharacter.app.ui.stats.CharacterComplexity.hasNovelAssignment]와
     * `fieldCompletionRate: Float?`가 완성도에서 *"작품 미배정으로 산출 불가"*를 정직하게
     * 고지한다. **완성도는 이미 말하고 있었고 계산 필드만 말없이 빠졌다.**
     */
    val calculatedUnavailable: Int = 0
)
