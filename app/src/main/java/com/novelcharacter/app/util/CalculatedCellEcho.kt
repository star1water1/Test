package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.json.JSONObject

/**
 * 계산 필드 열의 셀이 **앱 자신이 적어 낸 산출값 그대로인가**를 가른다.
 *
 * ## 왜 필요한가
 *
 * 내보내기는 계산 필드 열에 **평가된 값을 실제로 쓴다**(U-9·Q-1의 진단 가치 때문에 일부러
 * 그렇게 한다 — 깨진 수식은 오류 표식까지 쓴다). 그런데 되읽는 쪽은 그 열의 판정을
 * *"비었는가"* 하나로 했다. 그래서 **아무것도 고치지 않고 그대로 다시 넣는 왕복**만으로도
 *
 * - 복원 미리보기의 '캐릭터 필드값' 블록에 있지도 않은 '건너뜀'이 붙고
 * - 결과 창에 *"'{필드}' 열은 계산 필드라 저장하지 않습니다 … 값을 직접 넣으려면 그 필드의
 *   타입을 계산 필드에서 바꾸세요"*가 세계관·필드마다 한 줄씩 떴다.
 *
 * 둘 다 사실이 아니다 — 그 칸에 값을 적어 넣은 것은 사용자가 아니라 **앱의 내보내기**다.
 * 정상 파일이 상한 파일처럼 보이므로 사용자는 진짜 경고를 그 잡음 속에서 잃는다.
 * `FieldValueScan.skip`의 KDoc이 스스로 금지한 상태이기도 하다
 * (*"그것까지 세면 정상 파일에서도 '건너뜀'에 숫자가 붙는다"*).
 *
 * ## 무엇과 견주는가 — **내보내기가 썼을 그 값**
 *
 * 견주는 상대는 *지금 이 파일이 만들 값*이 아니라 **저장된 값으로 계산한 값**이다.
 * 그것이 내보내기가 그 셀에 쓴 바로 그 글자이기 때문이다. 이 선택이 세 경우를 모두 맞춘다:
 *
 * | 파일 | 셀의 내용 | 판정 |
 * |---|---|---|
 * | 아무것도 안 고침 | 저장값으로 계산한 값 | **앱의 출력** → 침묵 |
 * | 입력 필드를 고침(계산 열은 그대로) | 여전히 저장값으로 계산한 값 | **앱의 출력** → 침묵 |
 * | 계산 열에 직접 적음 | 다른 글자 | 사용자의 입력 → 경고·건너뜀 |
 *
 * *지금 파일의 값*으로 계산해 견주면 둘째 줄이 어긋나 **새 잡음**이 된다(입력만 고쳤는데
 * "계산 열에 적었다"고 말하게 된다).
 *
 * ## 저장은 어느 쪽에서도 하지 않는다
 *
 * 계산 필드는 저장 행이 없다(F4·R-16). 이 판정은 **말할 것인가**만 정한다.
 */
object CalculatedCellEcho {

    /**
     * **소유자가 이 가져오기에서 새로 생기는 행**의 판정 재료 — 그 행의 입력 칸 자체다.
     *
     * 저장된 값과 견주는 것이 이 판정의 계약인데, 새로 생기는 소유자에게는 저장된 값이
     * 없다. 그래서 재료가 통째로 비고, 수식이 **재료 없이** 평가돼 셀의 값과 어긋난다 —
     * 아무것도 고치지 않은 파일인데도 **새 캐릭터 행마다** 거짓 경고와 '건너뜀'이 붙었다
     * (빈 DB 복원이면 전 행이 그렇다).
     *
     * 그 행에서는 **내보낸 기기의 저장값이 곧 이 행의 입력 칸**이므로 재료를 행에서 짓는다.
     * 계산 필드 칸은 재료가 아니고(그것이 산출물이다), 빈 칸도 넣지 않는다 —
     * 내보내기가 평가에 쓰는 재료도 빈 값을 빼기 때문이다(`resolveFieldDisplayValues`).
     */
    fun materialsFromRow(cells: List<Pair<FieldDefinition, String>>): Map<String, String> =
        cells.asSequence()
            .filter { (field, value) ->
                field.fieldType != com.novelcharacter.app.data.model.FieldType.CALCULATED &&
                    value.isNotBlank()
            }
            .associate { (field, value) -> field.key to value }

    /** 이 정의의 수식. 없거나 못 읽으면 `null`. */
    fun formulaOf(field: FieldDefinition): String? = try {
        JSONObject(field.config).stringOr("formula", "").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * [cell]이 **앱이 그 셀에 썼을 값 그대로**인가.
     *
     * @param storedByKey 이 소유자의 **저장된** 필드값 (`fieldKey → value`) — 내보내기가
     *   평가에 쓴 것과 같은 재료다.
     * @param fieldsInScope 수식이 참조할 수 있는 정의들(등급 매핑·중첩 계산에 쓰인다).
     *
     * 수식이 없으면 `false`다 — 견줄 것이 없으므로 *앱의 출력이라고 단정하지 않는다*.
     * 그 열의 값은 종전대로 사용자의 입력으로 다뤄져 말이 나간다(침묵 쪽으로 기울지 않는다).
     */
    fun isAppOutput(
        field: FieldDefinition,
        cell: String,
        fieldsInScope: List<FieldDefinition>,
        storedByKey: Map<String, String>
    ): Boolean {
        val formula = formulaOf(field) ?: return false
        val evaluator = FormulaEvaluator(storedByKey, fieldsInScope)
        val expected = FormulaDisplay.evaluateForDisplay(formula, evaluator::evaluate).trim()
        val actual = cell.trim()
        if (actual == expected) return true
        // **글자만 견주면 소수 값이 언제나 어긋난다**(콜드 검토 2026.08.21).
        // 내보내기는 계산 값을 **숫자 셀**로 싣고([ExcelExporter.setFieldValue]), 되읽기는
        // 그 숫자를 최단 십진 표현으로 되돌린다 — 그래서 `3.50`으로 쓴 값이 `3.5`로 돌아온다.
        // 정수 결과에서만 글자가 맞아떨어져, 같은 파일 안에서 필드에 따라 침묵과 잡음이 갈렸다.
        //
        // 둘 다 수로 읽히면 **수로 견준다.** 같은 계산이 같은 자리를 왕복한 것이므로 근사
        // 비교는 필요 없고, 쓰면 오히려 서로 다른 값을 같다고 말하게 된다.
        // 수로 안 읽히는 쪽(예: `오류` 표식)은 글자 비교만 남는다 — 그 갈래는 위에서 끝났다.
        val actualNumber = actual.toDoubleOrNull() ?: return false
        val expectedNumber = expected.toDoubleOrNull() ?: return false
        return actualNumber == expectedNumber
    }
}
