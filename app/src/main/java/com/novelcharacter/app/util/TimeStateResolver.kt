package com.novelcharacter.app.util

import android.util.Log
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition

class TimeStateResolver {

    companion object {
        private const val TAG = "TimeStateResolver"
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

        // Check alive status (use newValue for birth/death year when available)
        val deathChange = relevantChanges.findLast { it.fieldKey == CharacterStateChange.KEY_DEATH }
        val deathYear = deathChange?.let { change ->
            change.newValue.toIntOrNull()
                ?: if (change.newValue.isBlank()) change.year else null
        }
        if (deathYear != null && targetYear >= deathYear) {
            result[CharacterStateChange.KEY_ALIVE] = "false"
        } else if (birthYear != null && targetYear >= birthYear) {
            // If born and not yet dead, alive
            result[CharacterStateChange.KEY_ALIVE] = "true"
        }

        // Evaluate calculated fields
        val calculatedFields = fieldDefinitions.filter { it.type == "CALCULATED" }
        if (calculatedFields.isNotEmpty()) {
            val evaluator = FormulaEvaluator(result, fieldDefinitions)
            for (field in calculatedFields) {
                val config = try {
                    com.google.gson.Gson().fromJson<Map<String, Any>>(
                        field.config,
                        object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse config for field '${field.key}': ${e.message}")
                    emptyMap()
                }
                val formula = config["formula"] as? String ?: continue
                try {
                    val value = evaluator.evaluate(formula)
                    result[field.key] = if (value.isNaN() || value.isInfinite()) "오류" else value.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "Formula evaluation error for field '${field.key}', formula='$formula'", e)
                    result[field.key] = "오류"
                }
            }
        }

        return result
    }
}
