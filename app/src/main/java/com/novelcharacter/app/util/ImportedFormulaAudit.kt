package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.json.JSONObject

/**
 * **엑셀로 들어온 수식을 적재가 끝난 뒤 한 번에 검사한다** (B-54).
 *
 * ## 왜 이 계층이 따로 있는가
 *
 * [FormulaValidator]는 이미 있었지만 **부르는 자리가 필드 편집 창 하나뿐**이었다. 필드 정의
 * 시트로 들어온 수식은 아무 고지 없이 저장되고, 사용자는 나중에 캐릭터 화면에서 `오류`를
 * 보거나 — 더 나쁘게는 — **그럴듯한 오답**을 본다(`sqrt(4)`가 `4`가 되는 부류. 알아보지 못한
 * 글자를 어휘 분석이 조용히 버리기 때문이다). 값이 틀렸다는 신호 자체가 없어 그대로 통계·
 * 엑셀로 퍼진다.
 *
 * 배선을 서비스 안에 묻지 않고 여기 두는 것은 **그 배선이 시험의 대상이기 때문**이다.
 * 순수 계층은 이미 시험이 있으므로 이 판이 새로 만드는 위험은 전부 *"어느 키를 아는 키로
 * 치는가"*·*"어느 필드를 이번 파일 탓으로 세는가"*에 있고, 둘 다 여기서 갈린다.
 * `ExcelImportService`는 Room을 물고 있어 순수 JVM 시험이 닿지 않는다.
 *
 * ## 적재가 **끝난 뒤**에 도는 것이 이 검사의 전부다
 *
 * 행을 읽으면서 검사하면 **뒷 행에 정의된 필드를 참조하는 수식이 전부 거짓 경고**를 받는다 —
 * 시트의 행 차례는 사용자가 정하는 것이지 의존 순서가 아니다. 그래서 [audit]은 *그 구역의
 * 최종 필드 목록*을 받는다.
 *
 * ## 이번 파일이 건드린 것만 센다
 *
 * [touchedKeys]로 좁히는 것은 `unusedConfigKeys`가 *"파일이 말한 config"*만 재는 것과 같은
 * 근거다: 예전부터 깨져 있던 수식까지 세면 **이번 파일과 무관한 옛 흔적을 이 파일 탓으로
 * 고지하게 된다.** 사용자는 자기가 방금 고친 것과 경고를 맞춰 볼 수 없게 된다.
 *
 * ## 거부가 아니라 고지다
 *
 * 잡힌 수식도 **그대로 저장된다.** 아직 만들지 않은 필드를 먼저 참조해 두는 작업 순서는
 * 사용자의 자유이고(원칙 03·자율성 우선), 왕복 무결성상 들어온 값은 들어와야 한다.
 * 편집 창이 '그대로 저장' 선택지를 유지하는 것과 같은 규약이다.
 */
object ImportedFormulaAudit {

    /**
     * 한 필드에서 찾은 것.
     *
     * @property key 필드 키 — 사람이 시트에서 찾을 때 쓰는 것은 이름이지만, 같은 이름의
     *   필드가 둘일 수 있어 키를 함께 든다.
     * @property name 표시 이름.
     * @property problems 비어 있지 않다 — 문제가 없는 필드는 [audit]이 내지 않는다.
     */
    data class Finding(
        val key: String,
        val name: String,
        val problems: List<FormulaValidator.Problem>
    )

    /**
     * @param scopeFields **한 구역(같은 세계관·같은 대상)의 필드 전부** — 이번 파일이
     *   건드리지 않은 것까지 담아야 한다. 아는 키와 순환 참조 경로가 여기서 나오므로,
     *   건드린 것만 넘기면 **멀쩡한 참조가 전부 '없는 키'로 잡힌다.**
     * @param touchedKeys 이번 파일이 넣거나 고친 필드의 키. 여기 없는 필드는 검사하되
     *   **고지하지 않는다** — 옛 흔적을 이 파일 탓으로 돌리지 않기 위해서다.
     */
    fun audit(scopeFields: List<FieldDefinition>, touchedKeys: Set<String>): List<Finding> {
        if (touchedKeys.isEmpty()) return emptyList()

        val knownKeys = scopeFields.map { it.key }.toSet()
        // 타입까지 든다 — **있는 키인데 수를 못 내는** 참조도 그 자리는 0이 된다(형제 사유와
        // 결과가 같은데 종전에는 고지만 없었다).
        val knownFieldTypes = scopeFields.associate { it.key to it.fieldType }
        // 순환 참조는 **구역 전체**를 따라가야 보인다 — 건드린 필드만 담으면 A→B→A에서
        // B가 옛 필드일 때 고리가 끊긴 것처럼 보인다.
        val calculatedFormulas = scopeFields
            .filter { it.fieldType == FieldType.CALCULATED }
            .mapNotNull { def -> formulaOf(def)?.takeIf { it.isNotBlank() }?.let { def.key to it } }
            .toMap()

        val findings = mutableListOf<Finding>()
        for (def in scopeFields) {
            if (def.key !in touchedKeys) continue
            if (def.fieldType != FieldType.CALCULATED) continue
            val formula = formulaOf(def)?.takeIf { it.isNotBlank() } ?: continue
            val problems = FormulaValidator.validate(
                formula = formula,
                currentKey = def.key,
                knownKeys = knownKeys,
                // 자기 자신은 뺀다 — 편집 창과 같은 규약이다. 두면 모든 수식이
                // 자기 자신을 통해 돌아오는 것으로 보인다.
                calculatedFormulas = calculatedFormulas - def.key,
                knownFieldTypes = knownFieldTypes
            )
            if (problems.isNotEmpty()) findings.add(Finding(def.key, def.name, problems))
        }
        return findings
    }

    /**
     * 필드 설정에서 수식만. 읽히지 않으면 null이다 — **설정이 깨진 것은 이 검사의 몫이
     * 아니다**(가져오기가 이미 *"설정(JSON)이 올바른 형식이 아닙니다"*로 따로 말한다).
     * 여기서 또 말하면 한 사실이 두 줄로 뜬다.
     */
    private fun formulaOf(def: FieldDefinition): String? = try {
        JSONObject(def.config).stringOr("formula", "").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
