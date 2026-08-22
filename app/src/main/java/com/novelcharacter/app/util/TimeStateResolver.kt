package com.novelcharacter.app.util

import android.util.Log
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType

class TimeStateResolver {

    companion object {
        private const val TAG = "TimeStateResolver"
        /**
         * 수식 평가 오류 시 표시할 문자열.
         * 정의는 [FormulaDisplay]로 옮겼다 — 오류 표식과 서식은 한 곳에서만 정한다(U-9).
         * 기존 호출부를 위해 이름은 남긴다.
         */
        const val FORMULA_ERROR_DISPLAY = FormulaDisplay.FORMULA_ERROR_DISPLAY
    }

    /**
     * Resolves a character's field values at a specific year by applying state changes chronologically.
     * @param baseValues Map of fieldKey -> baseValue (current/default values)
     * @param stateChanges All state changes for this character up to the target year, sorted chronologically
     * @param targetYear The year to resolve state for
     * @param fieldDefinitions The field definitions for computing calculated fields
     * @return Map of fieldKey -> resolvedValue at the target year
     */
    fun resolveStateAtYear(
        baseValues: Map<String, String>,
        stateChanges: List<CharacterStateChange>,
        targetYear: Int,
        fieldDefinitions: List<FieldDefinition>
    ): Map<String, String> {
        val result = baseValues.toMutableMap()

        // Apply state changes chronologically
        val relevantChanges = stateChanges
            .filter { it.year <= targetYear }
            .sortedWith(compareBy({ it.year }, { it.month ?: 0 }, { it.day ?: 0 }))

        for (change in relevantChanges) {
            result[change.fieldKey] = change.newValue
        }

        // Calculate age from birth year (newValue holds the actual birth year)
        // Use findLast to get the most recent entry in case of corrections/retcons
        val birthChange = relevantChanges.findLast { it.fieldKey == CharacterStateChange.KEY_BIRTH }
        val birthYear = birthChange?.let { change ->
            change.newValue.toIntOrNull()
                ?: if (change.newValue.isBlank()) change.year else null
        }
        if (birthYear != null) {
            val age = targetYear - birthYear
            result[CharacterStateChange.KEY_AGE] = if (age >= 0) age.toString() else ""
        }

        // Check alive status: __alive StateChange 우선, 없으면 birth/death 기반 계산
        val deathChange = relevantChanges.findLast { it.fieldKey == CharacterStateChange.KEY_DEATH }
        val deathYear = deathChange?.let { change ->
            change.newValue.toIntOrNull()
                ?: if (change.newValue.isBlank()) change.year else null
        }
        // **판정은 [AliveAtYear]가 든다** — 여기 남는 것은 *출력 어휘로 옮기는 일*뿐이다.
        // 종전에는 판정과 번역이 한 덩어리라, 관계도가 이 자리를 흉내 내면서 **출력 어휘로
        // 원본 행을 읽는** 실수를 했다(그 비교는 언제나 거짓이었다).
        //
        // `UNKNOWN`의 빈 문자열과 `UNSET`의 *키 없음*은 다르다 — 전자는 사용자가 '모른다'를
        // 고른 것이고 후자는 잴 재료가 없는 것이다. 화면이 그 둘을 다르게 그린다.
        when (AliveAtYear.resolve(stateChanges, targetYear)) {
            AliveAtYear.Verdict.DEAD -> result[CharacterStateChange.KEY_ALIVE] = "false"
            AliveAtYear.Verdict.ALIVE -> result[CharacterStateChange.KEY_ALIVE] = "true"
            AliveAtYear.Verdict.UNKNOWN -> result[CharacterStateChange.KEY_ALIVE] = ""
            AliveAtYear.Verdict.UNSET -> Unit
        }

        // Evaluate calculated fields
        val calculatedFields = fieldDefinitions.filter { it.fieldType == FieldType.CALCULATED }
        if (calculatedFields.isNotEmpty()) {
            val evaluator = FormulaEvaluator(result, fieldDefinitions)
            for (field in calculatedFields) {
                val config = try {
                    com.google.gson.Gson().fromJson<Map<String, Any>>(
                        field.config,
                        GsonTypes.STRING_ANY_MAP
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse config for field '${field.key}': ${e.message}")
                    emptyMap()
                }
                val formula = config["formula"] as? String ?: continue
                // 평가·서식·오류 표식은 [FormulaDisplay] 하나가 책임진다.
                // 종전의 `"%.2f".format`은 기본 로케일이라 소수점이 콤마인 환경에서 값이 달라졌다.
                result[field.key] = FormulaDisplay.evaluateForDisplay(formula) { f ->
                    try {
                        evaluator.evaluate(f)
                    } catch (e: Exception) {
                        Log.e(TAG, "Formula evaluation error for field '${field.key}', formula='$f'", e)
                        Double.NaN
                    }
                }
            }
        }

        return result
    }
}
