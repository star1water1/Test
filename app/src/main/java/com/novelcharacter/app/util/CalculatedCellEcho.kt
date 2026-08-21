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

    /** 이 정의의 수식. 없거나 못 읽으면 `null`. */
    fun formulaOf(field: FieldDefinition): String? = try {
        JSONObject(field.config).optString("formula", "").takeIf { it.isNotBlank() }
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
        val expected = FormulaDisplay.evaluateForDisplay(formula, evaluator::evaluate)
        return cell.trim() == expected.trim()
    }
}
