package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Universe

/**
 * 세계관·작품 카드에 **그림을 처음 붙였을 때** 표시 방식을 `custom`으로 올릴 것인가 —
 * 인앱 배정과 엑셀 가져오기가 함께 쓰는 단일 판정.
 *
 * ## 왜 판정이 필요한가
 *
 * 캐릭터와 달리 세계관·작품 카드는 **`imagePaths`만으로는 그림이 보이지 않는다.** 무엇을
 * 보여 줄지는 `imageMode`가 정하고(`none`·`custom`·`random_character`·…), `custom`이 아니면
 * `imagePaths`는 그냥 **안 쓰이는 값**이다.
 *
 * 그래서 그림을 붙이는 자리는 *"모드도 함께 올릴 것인가"*를 답해야 하는데, **인앱만 답하고
 * 엑셀은 답하지 않았다:**
 *
 * | 같은 일 | 인앱 이미지 관리 | 엑셀 가져오기 |
 * |---|---|---|
 * | `none`인 빈 카드에 그림을 붙인다 | `custom`으로 올리고 **알려 준다** | 값만 저장 — **아무 일도 안 일어난다** |
 *
 * 안내 시트는 *"캐릭터·세계관·작품 시트에서는 [이미지경로] 편집이 반영됩니다"*라 약속하는데,
 * 세계관·작품에 대해서는 그 문장이 참이 아니었다. 실측(2026.08.24 사용자가 내보낸 파일):
 * 세계관 8·작품 15가 **전부** `custom`이 아니었으므로, 안내대로 경로를 적어 넣은 사용자는
 * 어느 행에서도 그림을 보지 못한다 — 그리고 그 사실을 알려 주는 자리가 없었다.
 *
 * ## 규칙 — 인앱이 이미 정한 것 그대로다
 *
 * **`none`이고 그림이 하나도 없던 카드에 그림이 붙을 때만** `custom`으로 올린다.
 *
 * - `none`인데 **그림이 이미 있던** 카드는 사용자가 *일부러 안 보이게 둔 것*이다 — 존중한다.
 * - `random_character` 같은 **연동 모드**는 그림의 출처가 따로 있다 — 덮으면 사용자가 고른
 *   연동이 파일 한 번에 풀린다.
 * - 그래서 이 판정은 **한 방향으로만 움직인다**: 아무것도 아니던 카드를 보이게 만들 뿐,
 *   보이던 카드의 방식을 바꾸지 않는다.
 *
 * 두 카드 종류의 상수 값이 같아([Universe.IMAGE_MODE_NONE] == `Novel.IMAGE_MODE_NONE`)
 * 한 함수가 둘을 다 든다. 세계관 상수를 대표로 쓰는 것은 그 등가를 [modesAgree]가 지키기 때문이다.
 */
object CardImageAdoption {

    /**
     * 올릴 모드, 아니면 `null`(그대로 둔다).
     *
     * @param currentMode 지금 카드의 `imageMode`
     * @param hadImages 이 변경 **전에** 그림이 있었는가
     * @param hasImages 이 변경 **후에** 그림이 있는가
     */
    fun adoptedModeOrNull(currentMode: String?, hadImages: Boolean, hasImages: Boolean): String? =
        if (currentMode == Universe.IMAGE_MODE_NONE && !hadImages && hasImages) {
            Universe.IMAGE_MODE_CUSTOM
        } else {
            null
        }

    /**
     * 두 카드 종류의 모드 상수가 같은 글자인가 — 시험이 이것을 잰다.
     *
     * 같지 않게 되는 순간 위 함수가 **한쪽에만 맞는 답**을 내는데, 그 실패는 조용하다
     * (작품 카드만 안 올라가고 오류는 없다). 그래서 등가를 코드로 적어 시험이 지키게 한다.
     */
    fun modesAgree(): Boolean =
        Universe.IMAGE_MODE_NONE == com.novelcharacter.app.data.model.Novel.IMAGE_MODE_NONE &&
            Universe.IMAGE_MODE_CUSTOM == com.novelcharacter.app.data.model.Novel.IMAGE_MODE_CUSTOM
}
