package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * **타입이 안 맞는 값을 고치러 가는 길** — 목적지와 인자의 단일 소스 (B-198).
 *
 * ## 왜 순수 객체인가
 *
 * 이 판정은 *어느 화면으로 무엇을 들고 가는가* 하나뿐인데, 그것이 **보내는 쪽과 받는 쪽
 * 두 자리에 걸린다.** 두 벌로 적으면 갈리는 방향이 특히 나쁘다 — 보내는 쪽이 `focusEventId`로
 * 싣고 받는 쪽이 다른 이름으로 읽으면 **오류도 고지도 없이 아무 일이 일어나지 않는다.**
 * 누른 사람은 화면이 그냥 뜬 줄 알고, 자기가 고치려던 칸을 다시 손으로 찾는다.
 * 그래서 인자 이름을 여기 한 벌만 두고, 화면 셋이 전부 이 상수를 지난다
 * (`tools/check_field_fix_route.sh`가 생문자열 재유입을 막는다).
 *
 * ## 목적지를 [enum]으로 두는 이유
 *
 * `R.id.*`는 안드로이드 자원이라 순수 JVM 시험이 닿지 않는다. **갈래는 여기서 잠그고**
 * (어느 종류가 어디로 가는가 · 무엇을 싣는가), 자원 id로의 번역만 화면에 남긴다 —
 * 시험이 지키는 몫과 못 지키는 몫을 자리로 가른다.
 *
 * ## 캐릭터 축에 칸 지정이 없는 이유
 *
 * 캐릭터는 **상세 화면**으로 가고 그곳은 입력 폼이 아니다(고치려면 거기서 편집으로 한 번 더
 * 들어간다). 사건·작품은 목적지가 곧 **편집 창**이라 그 자리에서 칸을 잡을 수 있다.
 * 없는 자리에 인자를 실으면 받는 쪽이 없어 조용히 버려지므로, 실을 수 있는 축에만 싣는다.
 */
object FieldValueFixRoute {

    /** 캐릭터 상세가 이미 쓰는 이름 — 새로 정하지 않고 현행을 그대로 든다. */
    const val ARG_CHARACTER_ID = "characterId"

    /** 연표가 열 사건. 0 이하면 요청이 없는 것이다. */
    const val ARG_FOCUS_EVENT_ID = "focusEventId"

    /** 작품 목록이 열 작품. 0 이하면 요청이 없는 것이다. */
    const val ARG_FOCUS_NOVEL_ID = "focusNovelId"

    /** 편집 창이 열린 뒤 잡을 칸의 필드 정의 id. 0이면 잡지 않는다. */
    const val ARG_FOCUS_FIELD_ID = "focusFieldId"

    /**
     * 잡을 칸의 이름 — **그 칸이 창에 없을 때 무엇을 못 찾았는지 말하기 위해** 함께 싣는다.
     *
     * 이름 없이 *"칸이 없습니다"*만 말하면 사용자는 어느 값 이야기인지 되짚어야 한다.
     * 창이 정의를 다시 조회해 이름을 얻을 수도 있지만, **없는 정의는 조회로도 안 나온다** —
     * 칸이 없는 사유 중 하나가 정확히 *그 창의 구역 밖 정의*라서다.
     */
    const val ARG_FOCUS_FIELD_NAME = "focusFieldName"

    /** 값이 붙은 대상의 종류가 정하는 목적지. */
    enum class Destination { CHARACTER_DETAIL, TIMELINE, NOVEL_LIST }

    /**
     * 한 줄이 데려갈 자리.
     *
     * @param ownerArg 대상 id를 실을 인자 이름 — 목적지마다 다르다.
     * @param fieldId 잡을 칸. **0이면 잡지 않는다**(캐릭터 축).
     * @param fieldName 못 찾았을 때 말할 이름. [fieldId]가 0이면 빈 문자열이다.
     */
    data class Route(
        val destination: Destination,
        val ownerArg: String,
        val ownerId: Long,
        val fieldId: Long,
        val fieldName: String
    )

    /**
     * [ownerType]의 값 한 칸을 고치러 갈 자리. 갈 곳이 없으면 `null`.
     *
     * `null`인 두 갈래는 성질이 다르고 **둘 다 참이다:**
     * - **모르는 종류** — 이 앱이 아직 화면을 갖지 않은 축이다. 아무 데나 보내면 엉뚱한
     *   화면이 뜨므로 누를 수 없는 줄로 남긴다.
     * - **id가 없는 대상** — 목록은 임자를 잃은 값도 `#id`로 말하고 지나가지 않는데(무음 유실
     *   금지), 그 줄에는 갈 곳이 없다.
     */
    fun of(ownerType: String, ownerId: Long, fieldId: Long, fieldName: String): Route? {
        if (ownerId <= 0L) return null
        // 칸 지정은 실을 수 있을 때만 싣는다 — 음수·0은 "지정 없음"으로 접는다.
        val field = if (fieldId > 0L) fieldId else 0L
        return when (ownerType) {
            FieldDefinition.ENTITY_CHARACTER ->
                Route(Destination.CHARACTER_DETAIL, ARG_CHARACTER_ID, ownerId, 0L, "")
            FieldDefinition.ENTITY_EVENT ->
                Route(
                    Destination.TIMELINE, ARG_FOCUS_EVENT_ID, ownerId,
                    field, if (field > 0L) fieldName else ""
                )
            FieldDefinition.ENTITY_NOVEL ->
                Route(
                    Destination.NOVEL_LIST, ARG_FOCUS_NOVEL_ID, ownerId,
                    field, if (field > 0L) fieldName else ""
                )
            else -> null
        }
    }
}
