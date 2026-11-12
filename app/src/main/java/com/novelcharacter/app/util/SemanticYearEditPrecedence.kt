package com.novelcharacter.app.util

/**
 * **가져오기에서 `__birth`·`__death` 이력의 연도만 고친 파일** — 그 편집을 살릴지, 필드가 이길지
 * (순수 로직 — JVM 시험 대상).
 *
 * ## 왜 이 판단이 필요한가
 *
 * 가져오기 뒤 시맨틱 동기화는 **한 방향**이다: 필드값(출생연도·사망연도) → 상태변화 이력.
 * 그래서 '상태 변화' 시트에서 `__birth`의 연도만 970 → 980으로 고친 파일을 들이면, 이력 행은
 * 980으로 고쳐졌다가(결과: *"상태변화 1개 (업데이트 1개)"* · *"기존 행을 고쳤습니다"*) 곧이어
 * 같은 파일 캐릭터 시트의 '출생연도' 970(또는 DB의 970)이 **도로 덮었다.** 결과는 고쳤다고
 * 말하는데 DB는 그대로였다(S11 3-ㅇ-14 에뮬레이터 실측).
 *
 * ## 규칙
 *
 * 필드값이 **이력의 옛 연도 그대로**(또는 비었음)면 이 파일이 고친 것은 이력뿐이다 —
 * 그 편집을 필드로 옮긴다([APPLY_STATE]). 필드값이 **새 연도와 같으면** 둘이 이미 맞는다
 * ([CONSISTENT]). 필드값이 **둘 다와 다르면** 두 시트가 서로 다른 연도를 말한다 — 필드가
 * 정본이라 필드를 따르되 **조용히 덮지 않고 알린다**([FIELD_WINS_CONFLICT]).
 */
object SemanticYearEditPrecedence {

    enum class Decision { APPLY_STATE, CONSISTENT, FIELD_WINS_CONFLICT }

    /**
     * @param oldYear 이 가져오기 **전** 이력의 연도
     * @param newYear 이 가져오기가 이력에 쓴 연도
     * @param fieldValue 동기화 직전 그 역할 필드의 값(없으면 null)
     */
    fun decide(oldYear: Int, newYear: Int, fieldValue: String?): Decision {
        val fieldYear = fieldValue?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        return when {
            fieldValue.isNullOrBlank() -> Decision.APPLY_STATE
            fieldYear == newYear -> Decision.CONSISTENT
            fieldYear == oldYear -> Decision.APPLY_STATE
            else -> Decision.FIELD_WINS_CONFLICT
        }
    }
}
