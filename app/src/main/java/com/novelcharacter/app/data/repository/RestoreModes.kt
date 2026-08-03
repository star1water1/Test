package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.TrashSnapshot

/**
 * 스냅샷 복원의 **의미론 판정** (B-2/B-21) — 순수 로직.
 *
 * 저장소 안의 private 함수로 두지 않는 이유는 R-11에서 배운 그대로다: 실행해 볼 수 없는
 * 판정은 아무도 틀린 것을 발견하지 못한다. `RestoreModeTest`가 이 파일을 실제로 돌린다.
 */
enum class CharacterRestoreMode {
    /** 원본이 없다 — 되살린다(종전 동작). */
    REVIVE,

    /**
     * **삭제 백업**인데 같은 code가 살아 있다 — 사본으로 되살린다.
     *
     * 지워진 뒤 엑셀 덮어쓰기로 같은 code가 되살아난 경우가 여기다. 그때 사용자가 가진 것은
     * '엑셀에서 온 그것'이고 스냅샷은 '지워진 그것'이라 서로 다른 데이터다 — 덮어쓰면 방금
     * 임포트한 내용을 파괴한다. 사본 생성이 정답이며 실기기 확인까지 끝난 동작이다.
     */
    REVIVE_AS_COPY,

    /**
     * **편집 직전 백업**이고 원본이 살아 있다 — 되살리는 것이 아니라 **되돌린다**.
     *
     * 지워진 적이 없는 캐릭터를 한 번 더 만들면 같은 이름이 둘이 된다(B-2). 원본에 덮어쓰되,
     * 교체 범위는 그 편집이 실제로 파괴한 갈래로 한정한다([CharacterSnapshot.revertScope]).
     */
    REVERT
}

/** 상태변화 이력 복원의 판정 (같은 이력이 이미 있을 때). */
enum class StateChangeRestoreMode {
    /** 되살린다. */
    INSERT,

    /**
     * 같은 이력이 이미 있다 — **덮어쓰지 않고 건너뛰며 사실을 고지한다.**
     *
     * 같은 파일의 사건 복원(`applyEvent`)이 이미 쓰는 규약이다: "그 사이 사용자가 다시 만든
     * 것이므로 덮어쓰지 않는다". 덮어쓰기는 살아 있는 값을 백업 없이 파괴하는데, 복원은 성공
     * 즉시 스냅샷을 소각하므로 어느 쪽으로도 되돌릴 수 없게 된다(R-4).
     */
    SKIP_EXISTING
}

object RestoreModes {

    // ── 되돌리기 교체 범위 (편집 백업이 무엇을 파괴했는가) ──
    /** 캐릭터 행 자체(이름·메모 등)를 덮어썼다. */
    const val SCOPE_CHARACTER_ROW = "character_row"

    /** 캐릭터 필드값을 통째로 갈아 끼웠다(`replaceAllByCharacter`). */
    const val SCOPE_FIELD_VALUES = "field_values"

    /** 세력 소속을 지웠다(타 세계관 소속 정리). */
    const val SCOPE_MEMBERSHIPS = "memberships"

    /** 상태변화 이력을 지웠다(값 라이브러리 엔트리 삭제의 파급). */
    const val SCOPE_STATE_CHANGES = "state_changes"

    /**
     * 필드 **정의**를 덮어썼다 (B-89 — 프리셋 병합의 '덮어쓰기').
     *
     * 값 행은 손대지 않는다(`fieldDefinitionId`로 매달려 있다). 바뀌는 것은 이름·타입·설정·
     * 묶음·필수이며, **타입이 바뀌면 저장된 값의 해석이 달라진다** — 그것이 이 갈래를
     * 되돌릴 수 있어야 하는 이유다.
     */
    const val SCOPE_FIELD_DEFINITIONS = "field_definitions"

    /**
     * 범위를 기록하지 않은 구버전 편집 백업에 적용할 범위.
     *
     * 모든 편집 백업 경로가 공통으로 파괴하는 것은 필드값 하나뿐이다. 모르는 것은 되돌리지
     * 않는다 — 덜 되돌리는 것은 고지로 메울 수 있지만, 손대지 않은 데이터를 지우는 것은 메울 수 없다.
     */
    val LEGACY_REVERT_SCOPE = listOf(SCOPE_FIELD_VALUES)

    /** 작품/세계관 이동이 파괴하는 것 — 필드값 재매핑 + 타 세계관 세력 소속 정리. */
    val SCOPE_UNIVERSE_MOVE = listOf(SCOPE_FIELD_VALUES, SCOPE_MEMBERSHIPS)

    /** 편집 화면 저장의 세계관 이동 — 위에 더해 캐릭터 행 자체를 덮어쓴다. */
    val SCOPE_UNIVERSE_MOVE_WITH_ROW =
        listOf(SCOPE_CHARACTER_ROW, SCOPE_FIELD_VALUES, SCOPE_MEMBERSHIPS)

    /** 값 라이브러리 엔트리 삭제(데이터 함께 지우기) — 필드값과 상태변화를 고쳐 쓴다. */
    val SCOPE_LIBRARY_ENTRY_DELETE = listOf(SCOPE_FIELD_VALUES, SCOPE_STATE_CHANGES)

    /**
     * 이 스냅샷을 복원하면 무슨 일이 일어나는가.
     *
     * @param operationKind 행에 기록된 종류. **null(구버전)은 삭제 백업**이다 — R-9가 정한
     *   대로 백필하지 않으며, null은 이미 "독립된 삭제"라는 올바른 의미를 갖는다.
     * @param livingSameCode 같은 code의 캐릭터가 지금 살아 있는가. **code로만** 판정할 것 —
     *   id 폴백으로 정하면 재발급된 옛 id의 남의 캐릭터를 덮어쓴다(R-1이 막으려는 오배정).
     */
    fun characterRestoreMode(operationKind: String?, livingSameCode: Boolean): CharacterRestoreMode =
        when {
            !livingSameCode -> CharacterRestoreMode.REVIVE
            operationKind == TrashSnapshot.KIND_EDIT_BACKUP -> CharacterRestoreMode.REVERT
            else -> CharacterRestoreMode.REVIVE_AS_COPY
        }

    /**
     * 되돌릴 갈래를 정한다. 편집 백업이 아니면 되돌릴 것이 없다(빈 집합).
     *
     * 모르는 이름은 버린다 — 뒷 버전이 추가한 갈래를 옛 앱이 받으면 의미를 모른 채 지우게 된다.
     */
    fun revertScopeOf(raw: List<String>?): Set<String> {
        val known = setOf(
            SCOPE_CHARACTER_ROW, SCOPE_FIELD_VALUES, SCOPE_MEMBERSHIPS, SCOPE_STATE_CHANGES,
            SCOPE_FIELD_DEFINITIONS
        )
        val source = raw ?: LEGACY_REVERT_SCOPE
        return source.filter { it in known }.toSet()
    }

    /**
     * 부활 경로가 이름 은행 링크를 되붙일 것인가 (B-3).
     *
     * **사본을 만드는 복원(REVIVE_AS_COPY, 동의 없는 REVERT 폴백)은 되붙이지도 세지도 않는다** —
     * 원본이 살아서 링크를 쥐고 있는 것이 정상 상태다. 사본 쪽에서 relink를 돌리면 "남이 쓰는 중"
     * 판정이 전부 유실로 집계되어, 미리보기(0건 예고)와 결과가 어긋나 '예상 밖 유실' 경고까지
     * 울린다 — R-11에서 고친 유령 유실과 같은 부류다. (되돌리기 분기는 이 판정을 거치지 않고
     * 항상 되붙인다 — 대상이 원본 그 자체이기 때문이다.)
     */
    fun revivalRelinksNameBank(mode: CharacterRestoreMode): Boolean =
        mode == CharacterRestoreMode.REVIVE

    /**
     * 같은 (캐릭터, 필드키) 이력이 이미 있으면 건너뛴다.
     *
     * @param sameSlotLives 특수키(`__birth`·`__death`·`__alive`)는 캐릭터당 하나를 전제로
     *   읽히므로(`SemanticFieldSyncHelper.findStateChange`는 첫 건을 쓴다) 두 줄이 되면 어느
     *   쪽이 진짜인지 알 수 없게 된다 — code가 달라도 슬롯이 차 있으면 건너뛴다.
     */
    fun stateChangeRestoreMode(
        sameCodeLives: Boolean,
        sameSlotLives: Boolean
    ): StateChangeRestoreMode =
        if (sameCodeLives || sameSlotLives) StateChangeRestoreMode.SKIP_EXISTING
        else StateChangeRestoreMode.INSERT
}
