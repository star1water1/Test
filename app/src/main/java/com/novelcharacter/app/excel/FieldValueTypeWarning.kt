package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.FieldValueTypeMismatch

/**
 * **가져오기가 저장한 값이 제 필드 타입으로 읽히지 않을 때의 고지** — 거부가 아니라 수용·교정
 * (개발 의도 4번). 값은 그대로 저장하고, 통계·수식에서 빠질 수 있다는 사실만 말한다.
 *
 * ## 판정은 여기서 하지 않는다 — [FieldValueTypeMismatch]가 정본이다
 *
 * 종전에는 `ExcelImportService`가 `when (field.fieldType)`을 **손으로 한 벌 더** 들고 있었고,
 * 그 한 벌이 정본과 **세 자리에서 달랐다**:
 *
 * | | 가져오기(종전) | 정본([FieldValueTypeMismatch]) |
 * |---|---|---|
 * | NUMBER | `toDoubleOrNull()` | 같다(`FieldTypeCompatibility`에 위임) |
 * | GRADE | `resolveForDisplay` — **표가 없으면 기본 등급표로 폴백** | `resolveFromConfig` — 표가 없으면 불일치 |
 * | BODY_SIZE | `value.any { it.isDigit() }` — 숫자가 한 글자라도 있으면 통과 | 부위를 갈라 **수치로 읽히는 파트가 있는가**(`1a2`는 걸린다) |
 *
 * **갈리는 방향이 나빴다.** 가져오기는 조용히 넘기고 통계의 「타입이 안 맞는 값」은 같은 값을
 * 잡았다 — 사용자는 *경고 없이 들여온 값*을 나중에 건강도 화면에서 처음 듣는다. 그 반대도
 * 성립한다(가져오기만 경고하고 고칠 자리에서는 안 보인다). 규약 R-34가 금지하는 모양이며,
 * [FieldValueTypeMismatch]의 KDoc이 *"전파가 센 것을 건강도가 못 찾는다"*로 이미 적어 둔
 * 바로 그 어긋남이다.
 *
 * 그래서 이 객체는 **문구만 든다.** 판정이 바뀌면 두 화면이 함께 바뀐다.
 *
 * ## 행 접두는 부르는 쪽이 붙인다
 *
 * 경로마다 행을 가리키는 말이 다르다(`캐릭터 행 7` · `작품 필드값 행 12` …). 접두를 여기서
 * 지으려면 경로 종류를 이 파일이 알아야 하고, 새 시트가 늘 때마다 여기가 뒤처진다.
 */
object FieldValueTypeWarning {

    /**
     * [value]가 [field]의 타입으로 읽히지 않으면 그 사유 문구, 읽히면 `null`.
     *
     * 빈 값은 언제나 `null`이다 — 비움은 잘못이 아니다([FieldValueTypeMismatch] 규약).
     */
    fun messageOf(field: FieldDefinition, value: String): String? =
        when (FieldValueTypeMismatch.reasonFor(field, value)) {
            FieldValueTypeMismatch.Reason.NOT_A_NUMBER ->
                "숫자 필드 '${field.name}'에 숫자가 아닌 값 '$value'이(가) 저장됨 — 통계에서 제외될 수 있습니다"

            FieldValueTypeMismatch.Reason.UNKNOWN_GRADE ->
                "등급 필드 '${field.name}'의 값 '$value'을(를) 이 필드의 등급표에서 찾을 수 없습니다 — 통계·수식에서 제외될 수 있습니다"

            // 종전 문구는 *"숫자가 없어"*였는데 판정이 보는 것은 **부위별 수치**다 — 한 토막에
            // 수가 둘인 값(`1a2`)은 숫자를 품고도 걸리므로 문구가 사실과 어긋났다.
            FieldValueTypeMismatch.Reason.NOT_MEASUREMENTS ->
                "신체 사이즈 필드 '${field.name}'의 값 '$value'에서 부위 수치를 읽을 수 없어 통계에 반영되지 않을 수 있습니다"

            null -> null
        }

    /** 상한을 넘겨 접을 때의 꼬리 한 줄 — [ImportWarningFold]가 부른다. */
    fun foldTail(sheetLabel: String, remaining: Int, total: Int): String =
        "$sheetLabel: 타입과 맞지 않는 값이 ${remaining}건 더 있습니다(모두 ${total}건) — " +
        "통계의 「타입이 안 맞는 값」에서 전수를 보고 그 자리에서 고칠 수 있습니다"
}
