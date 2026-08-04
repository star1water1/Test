package com.novelcharacter.app.data.model

/**
 * 대결(B-104)의 휴지통 payload.
 *
 * ## 왜 축과 판이 두 타입인가
 * 한 축의 판은 **수만 건이 될 수 있다**(이 앱에서 가장 큰 표 —
 * `docs/scalability_performance_2026-07.md` 4장). 한 행에 몰아넣으면 payload가 Android
 * CursorWindow 한도(2MB)를 넘겨 **그 백업을 영영 읽지 못한다** — "휴지통에 보관됩니다"라고
 * 안내한 뒤에 말이다. 그래서 [UniverseDataSnapshot]과 같은 규약으로 **크기 예산 단위로 쪼갠다**:
 * 축 본체는 한 행([TrashSnapshot.TYPE_DUEL_AXIS]), 판은 이어붙임 행들
 * ([TrashSnapshot.TYPE_DUEL_MATCHES])이며 같은 작업으로 묶여 순서대로 복원된다.
 *
 * ## R-2 (Gson 하위호환)
 * 모든 컬렉션·참조 필드는 nullable이고 읽는 쪽이 `orEmpty()`로 받는다. Gson은 Kotlin 생성자
 * 기본값을 실행하지 않으므로 구버전 payload에 없는 필드는 선언이 non-null이어도 런타임에
 * null이 주입된다. 계약은 `DuelSnapshotPayloadTest`가 고정한다.
 */

/**
 * 축 하나의 스냅샷 — 축 본체와 층 B의 **사용자 처분**.
 *
 * 판을 담지 않는 것은 크기 때문만이 아니다: 판은 이어붙임 행이 담으므로 **겹치지 않는다**
 * (스냅샷은 겹치지 않고 이어붙는다 — R-8).
 */
data class DuelAxisSnapshot(
    val axis: DuelAxis,
    /** 층 B의 처분(②미정·③상성) — 파생이 아니라 사용자 판정이라 되살려야 한다. */
    val verdicts: List<DuelCounterVerdict>? = null,
    /** 이 축이 속한 세계관의 코드 — 없으면 붙일 자리를 찾을 수 없다(R-1). */
    val universeCode: String? = null,
    /** 삭제 시점의 판 수 — 휴지통 목록이 "무엇이 얼마나 들어 있는가"를 말할 수 있게(원칙 04). */
    val matchCount: Int? = null,
    val refs: EntityRefs? = null
)

/**
 * 판의 이어붙임 행. 자기 축보다 **나중에** 복원되므로(restorePriority) 그때 축이 이미 있고,
 * 축은 **코드**로 찾는다 — id는 복원에서 재발급될 수 있다(R-1).
 *
 * 참가자는 payload 안에서도 코드다([DuelMatch.aCode]). 그래서 이 스냅샷은 캐릭터의 생사와
 * 무관하게 온전하다 — 복원 시점에 없는 참가자의 판도 그대로 되살아나고, 그 캐릭터가 나중에
 * 휴지통에서 돌아오면 **판이 저절로 다시 적합에 든다.**
 */
data class DuelMatchesSnapshot(
    /** 이 판들이 속한 축의 코드. */
    val axisCode: String? = null,
    val matches: List<DuelMatch>? = null,
    /** 축이 속한 세계관의 코드 — 축을 못 찾을 때 무엇이 없어졌는지 말할 수 있게 함께 담는다. */
    val universeCode: String? = null,
    val refs: EntityRefs? = null
)
