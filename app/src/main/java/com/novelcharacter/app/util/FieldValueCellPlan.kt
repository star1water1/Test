package com.novelcharacter.app.util

/**
 * 한 (소유자, 필드, 셀)이 가져오기에서 받는 **처분** (B-187).
 *
 * 필드값은 캐릭터·작품·사건 셋이 각각 **본 시트의 필드 열**과 **오버플로 시트의 행** 두 경로로
 * 들어와 자리가 여섯인데, 한 칸의 처분은 여섯 자리에서 **글자 그대로 같다.** 같은 규칙을
 * 여섯 벌 손으로 적으면 갈리므로 [FieldValueCellPlan]이 혼자 판정한다(규약 R-33의 여섯째 짝).
 */
enum class FieldValueCellEffect {
    /** 값이 있고 기존 값이 없다 — 새 행이 들어간다. */
    NEW,

    /** 값이 있고 기존 값과 다르다 — 기존 행이 덮인다. */
    UPDATE,

    /** 값이 있고 기존 값과 같다 — **쓰지 않는다.** */
    UNCHANGED,

    /** 셀이 비었고 그 열이 파일에 있으며 기존 값이 있다 — 기존 행이 **지워진다**(F1-A: 비움 의도). */
    CLEAR,

    /** 아무 일도 일어나지 않는다 — 셀도 비었고 지울 것도 없거나, 값 열 자체가 파일에 없다. */
    NONE
}

/**
 * 필드값 한 칸의 처분을 정하는 **순수 판정** — 가져오기의 쓰기와 미리보기의 계수가 함께 부른다.
 *
 * ## 왜 한 자리인가
 *
 * 이 규칙은 여섯 자리에 손으로 적혀 있었다(`importCharacterRows` · `importCharacterFieldValues` ·
 * `importNovelFieldValues` · `importEventFieldValues` · `applyNovelFieldColumns` ·
 * `importTimeline`). 그리고 **미리보기에는 한 자리도 없었다** — 복원 미리보기의 범주 스물하나
 * 중 필드값을 세는 것이 하나도 없어서, `mergeCharacter`가 `Character` 엔티티 열만 견주는 동안
 * **필드값이 통째로 덮여도 '동일'이라 말했다**(B-187).
 *
 * 미리보기가 그 계수를 새로 짜면 그것이 곧 **일곱째 벌**이라, 판정을 여기로 내리고
 * 일곱 자리가 모두 이것을 부른다.
 *
 * ## [columnPresent]가 갈래를 하나 더 만드는 이유
 *
 * 빈 셀의 뜻은 **그 열이 파일에 있었는가**에 달렸다. 열이 있는데 셀이 비었으면 *비움 의도*이고
 * (F1-A), 열 자체가 없으면 *이 파일은 그 값에 대해 아무 말도 하지 않았다*이므로 기존 값을
 * 그대로 둔다. 오버플로 시트는 '값' 열이 없을 수 있어 이 갈래가 실제로 갈린다.
 *
 * ## [UNCHANGED]가 따로 있는 이유 — 두 가지가 함께 걸린다
 *
 * 1. **미리보기가 '동일'을 말해야 한다.** 신규·변경과 한 통에 넣으면 무편집 왕복이
 *    "필드값 36,510건 변경"이라 예고한다.
 * 2. **가져오기가 그 쓰기를 건너뛴다.** 종전에는 같은 값을 그대로 `update`로 다시 썼다 —
 *    엑셀을 내보내 한 칸만 고쳐 되가져오는 것이 이 앱의 주 경로인데, 그때 필드값 표 전체가
 *    한 행씩 다시 쓰였다(목표 규모에서 36,510회. `docs/scalability_performance_2026-07.md` 2장).
 *    같은 값을 쓰는 것은 결과가 같고 비용만 드는 일이라, 판정이 한 자리로 모이면서 함께 없어진다.
 */
object FieldValueCellPlan {

    /**
     * @param cellValue 파일의 셀 값(이미 정규화된 문자열). 공백만 있는 값은 빈 칸으로 본다 —
     *   가져오기 여섯 자리가 전부 `isNotBlank()`로 갈랐고 그 판정을 그대로 옮긴다.
     * @param existingValue 지금 저장돼 있는 값. 행이 없으면 `null`이다.
     *   **`null`과 빈 문자열은 다르다** — 전자는 행이 없는 것이고 후자는 빈 값이 저장된 것이다.
     *
     *   ⚠️ **부르는 쪽의 불변식: `existingValue == null` ⟺ 행이 없다.** 지금은 세 값 표의
     *   `value`가 전부 non-null `String`이라 `행?.value`가 그 등가를 공짜로 준다. 그 열이
     *   nullable이 되면 등가가 깨지고, 그때 [FieldValueCellEffect.NEW] 갈래가 **이미 있는
     *   행에 insert를 쳐** `(소유자, 필드)` 유니크 색인을 깬다 — 가져오기가 통째로 실패한다.
     * @param columnPresent 이 값을 담을 열이 파일에 있었는가. 없으면 빈 셀은 비움 의도가 아니다.
     */
    fun effectOf(
        cellValue: String,
        existingValue: String?,
        columnPresent: Boolean = true
    ): FieldValueCellEffect = when {
        cellValue.isNotBlank() -> when {
            existingValue == null -> FieldValueCellEffect.NEW
            existingValue != cellValue -> FieldValueCellEffect.UPDATE
            else -> FieldValueCellEffect.UNCHANGED
        }
        columnPresent && existingValue != null -> FieldValueCellEffect.CLEAR
        else -> FieldValueCellEffect.NONE
    }
}
