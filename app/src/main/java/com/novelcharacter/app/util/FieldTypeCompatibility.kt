package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldType

/**
 * **저장된 값이 새 타입으로 살아남는가** — 타입 변경 영향 판정의 단일 소스.
 *
 * 원래 `FieldEditDialog.isValueCompatible`의 private 함수였고, 그 화면 하나만 물었다.
 * B-119가 **두 번째로 묻는 자리**를 만든다: 전역 기본 필드의 전파 미리보기는 *"이 타입 변경을
 * 저 세계관에 밀면 값이 몇 개나 못 버티는가"*를 세계관마다 말해야 한다(설계 1-3).
 *
 * 두 벌로 두면 반드시 갈린다(R-7). 갈리는 방향이 특히 나쁘다 — **한 화면이 '괜찮다'고 한 값을
 * 다른 화면이 못 버틴다고 세는** 모양이 되고, 그것은 미리보기가 있으나 마나 한 상태다
 * (개발 의도 2번).
 *
 * ## 세는 규칙은 한 벌이지만 **처분은 두 벌이다** (B-137)
 *
 * 두 소비처가 이 함수에 같은 것을 묻고 **다르게 처분한다.** 여기 적어 두지 않으면 다음 사람이
 * 하나로 합친다.
 *
 * | 부르는 곳 | 무엇을 하는가 |
 * |---|---|
 * | `FieldEditDialog` (단일 필드 타입 변경) | 확인을 받고 **못 버티는 값을 `""`로 초기화한다** |
 * | 전역 기본 필드 **전파** ([DefaultFieldPlan.planPropagate]) | **고지만 한다 — 값은 그대로 둔다** |
 *
 * 갈린 것이 아니라 **가른 것이다**(사용자 확정, 설계 1-11). 전파는 세계관 여럿을 한 번에 덮고
 * 기본 선택이 *아직 못 받은 곳*을 전부 켜므로, 같은 처분을 쓰면 탭 한 번에 전 세계관의 값이
 * 사라진다. 게다가 전파에는 되돌리기가 붙어 있는데(`FieldDefinitionSnapshot`) 그 스냅샷은
 * 정의만 담아, 값을 지우면 **되돌려도 값은 돌아오지 않는다.**
 *
 * ## 이 판정이 답하지 **않는** 질문 (B-156)
 *
 * 여기 있는 것은 **미래형**이다 — *"이 값을 **저** 타입으로 바꾸면 살아남는가"*. 그래서 아는
 * 것이 값과 타입 이름뿐이고 config를 모른다.
 *
 * *"이 값이 지금 **제** 필드의 타입으로 읽히는가"*(현재형)는 [FieldValueTypeMismatch]가
 * 답한다. 시제가 다르면 답도 다르다 — 아래 두 줄을 현재형에 그대로 쓰면 **정상인 등급 값과
 * 체형 값 전부가 불일치로 잡힌다**(등급 값은 정수가 아니라 라벨이고, 체형 값은 언제나
 * `false`를 받는다). 그쪽이 [FieldType.NUMBER]에서만 이 함수에 위임하는 이유가 그것이다.
 */
object FieldTypeCompatibility {

    /**
     * [value]가 [newType]으로 바뀌어도 뜻을 유지하는가.
     *
     * **빈 값은 묻지 않는다** — 부르는 쪽이 미리 거른다(빈 값은 어느 타입으로도 빈 값이다).
     */
    fun isValueCompatible(value: String, newType: String): Boolean = when (newType) {
        FieldType.NUMBER.name -> value.toDoubleOrNull() != null
        FieldType.GRADE.name -> value.toIntOrNull() != null
        FieldType.SELECT.name, FieldType.TEXT.name, FieldType.MULTI_TEXT.name -> true
        // 구조화 입력은 기존 텍스트와 호환되지 않는다.
        FieldType.BODY_SIZE.name -> false
        // 수식 필드의 값은 파생이라 기존 저장값과 무관하다.
        FieldType.CALCULATED.name -> false
        else -> true
    }

    /**
     * 값 목록 중 **못 버티는 것의 수**. 빈 값은 세지 않는다.
     *
     * 화면이 *"값 N개 중 M개"*를 말하는 자리이며, 세는 규칙이 판정과 같은 파일에 있어야
     * *"괜찮다고 해 놓고 못 버틴다고 세는"* 어긋남이 생길 자리가 없다.
     *
     * **이 수가 곧 "지워질 수"는 아니다** — 무엇을 할지는 부르는 쪽이 정한다(위 표).
     */
    fun incompatibleCount(values: List<String>, newType: String): Int =
        values.count { it.isNotBlank() && !isValueCompatible(it, newType) }
}
