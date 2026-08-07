package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldType

/**
 * **저장된 값이 새 타입으로 살아남는가** — 타입 변경 영향 판정의 단일 소스.
 *
 * 원래 `FieldEditDialog.isValueCompatible`의 private 함수였고, 그 화면 하나만 물었다.
 * B-119가 **두 번째로 묻는 자리**를 만든다: 전역 기본 필드의 전파 미리보기는 *"이 타입 변경을
 * 저 세계관에 밀면 값이 몇 개나 못 버티는가"*를 세계관마다 말해야 한다(설계 1-3).
 *
 * 두 벌로 두면 반드시 갈린다(R-7). 갈리는 방향이 특히 나쁘다 — **미리보기가 '괜찮다'고 한
 * 전파가 값을 지우는** 모양이 되고, 그것은 미리보기가 있으나 마나 한 상태다(개발 의도 2번).
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
     * 화면이 *"값 N개 중 M개가 초기화됩니다"*를 말하는 자리이며, 세는 규칙이 판정과 같은
     * 파일에 있어야 *"괜찮다고 해 놓고 지우는"* 어긋남이 생길 자리가 없다.
     */
    fun incompatibleCount(values: List<String>, newType: String): Int =
        values.count { it.isNotBlank() && !isValueCompatible(it, newType) }
}
