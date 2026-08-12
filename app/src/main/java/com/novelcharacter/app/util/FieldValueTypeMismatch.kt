package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType

/**
 * **저장된 값이 지금 제 필드의 타입으로 읽히는가** — 타입 불일치 판정의 단일 소스 (B-156).
 *
 * ## 왜 [FieldTypeCompatibility]와 다른 파일인가 — 묻는 시제가 다르다
 *
 * 둘이 비슷해 보여 다음 사람이 합치려 들 자리라, 먼저 갈라 적는다.
 *
 * | | 묻는 것 | 아는 것 |
 * |---|---|---|
 * | [FieldTypeCompatibility] | **미래형** — 이 값을 *저* 타입으로 바꾸면 살아남는가 | 값과 **타입 이름뿐**. 아직 없는 타입이라 config가 없다 |
 * | 이 객체 | **현재형** — 이 값이 *제* 필드의 타입으로 읽히는가 | 값과 **필드 정의 전체**. config·등급 표를 볼 수 있다 |
 *
 * 시제가 다르면 답도 다르다. 합치면 두 자리에서 곧바로 틀린다.
 *
 * - **GRADE** — [FieldTypeCompatibility]는 *"정수 텍스트만 등급으로 살아남는다"*고 답한다.
 *   그러나 등급 값은 정수가 아니라 **라벨**(`"S"`·`"A+"`)이고 숫자는 config의 실효 표가 준다.
 *   그 판정을 그대로 쓰면 **정상인 등급 값 전부가 불일치로 잡힌다.**
 * - **BODY_SIZE** — 그쪽은 언제나 `false`다(*"어떤 기존 텍스트도 체형 입력으로 살아남지
 *   않는다"*). 옳은 답이지만 현재형에 쓰면 **정상인 체형 값 전부가 불일치가 된다.**
 *
 * **[FieldType.NUMBER]에서만 두 답이 같고, 거기서는 위임한다** — 같은 규칙을 두 벌로 적으면
 * 갈리고(R-7), 갈리는 방향이 특히 나쁘다: 전파 미리보기가 *"값 N개가 맞지 않게 됩니다"*라
 * 셌는데 건강도가 그 값을 못 찾으면, 사용자는 **고지받은 것을 고치러 갈 수 없다.**
 *
 * ## 세지 않는 것과 그 이유
 *
 * - **빈 값** — 어느 타입으로도 빈 값이다. 빈 칸은 완성도 지표가 이미 센다.
 * - **TEXT·SELECT·MULTI_TEXT** — 어떤 문자열도 읽힌다. 선택지 목록에 없는 값은 *불일치*가
 *   아니라 **고아 값**이고, 폼 계층이 일부러 보존한다(`DynamicFieldFormBuilder`). 여기서
 *   세면 **보존하기로 확정한 것을 잘못으로 신고하는 꼴**이다.
 * - **CALCULATED** — 저장 행이 없는 것이 설계이고(R-16), 엑셀로 흘러든 정적 값은 수식이
 *   덮어 쓰므로 값의 잘못이 아니다. 수식이 없거나 순환인 것은 **필드의 문제**라 값 목록이
 *   아니라 다른 자리에서 다뤄야 한다.
 *
 * ## 이 판정이 가리키는 해악
 *
 * 여기서 걸리는 값은 수식에서 `0.0`으로 읽히고([FormulaEvaluator]) 수치 분포에서 빠진다
 * ([NumericBinning.numericValuesOf]). **틀린 값이 빠지는 것이 아니라 틀린 값이 0으로
 * 섞여 드는 것**이라, 화면 어디에도 이상이 보이지 않는다 — 찾아갈 길을 따로 세우지 않으면
 * 사용자는 영영 모른다(개발 의도 2번의 끊긴 셋째 고리).
 */
object FieldValueTypeMismatch {

    /** 왜 읽히지 않는가. 화면이 사용자에게 **고칠 방향**을 말하는 근거라 사유별로 가른다. */
    enum class Reason {
        /** 숫자 필드인데 수로 읽히지 않는다. */
        NOT_A_NUMBER,

        /** 등급 필드인데 이 필드의 등급 표에 없는 라벨이다. */
        UNKNOWN_GRADE,

        /** 체형 필드인데 어느 파트에서도 수치를 하나도 읽을 수 없다. */
        NOT_MEASUREMENTS
    }

    /**
     * [value]가 [fieldDef]의 타입으로 읽히지 않는 사유. 읽히면 `null`.
     *
     * 빈 값은 언제나 `null`이다(위 KDoc).
     */
    fun reasonFor(fieldDef: FieldDefinition, value: String): Reason? {
        if (value.isBlank()) return null
        return when (fieldDef.type) {
            // 같은 질문이라 위임한다 — 두 벌로 적으면 전파가 센 것을 건강도가 못 찾는다.
            FieldType.NUMBER.name ->
                if (FieldTypeCompatibility.isValueCompatible(value, FieldType.NUMBER.name)) null
                else Reason.NOT_A_NUMBER

            // 등급은 라벨이라 config의 실효 표를 봐야 안다. 표가 통째로 없는 필드도 여기서
            // 걸리는데, 그것도 참이다 — 그 필드의 모든 값이 수식에서 0.0으로 읽힌다.
            FieldType.GRADE.name ->
                if (GradeValueResolver.resolveFromConfig(fieldDef, value) != null) null
                else Reason.UNKNOWN_GRADE

            // 파트 하나라도 수치로 읽히면 체형 값으로 동작한다(부분 입력은 정상이다).
            // 하나도 못 읽는 것만이 "이 값은 체형이 아니다"이다.
            FieldType.BODY_SIZE.name ->
                if (BodyMeasurements.splitPlain(value).any { BodyMeasurements.parseNumber(it) != null }) null
                else Reason.NOT_MEASUREMENTS

            else -> null
        }
    }
}
