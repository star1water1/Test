package com.novelcharacter.app.util

import com.google.gson.Gson

/**
 * 필드 config(JSON)에서 선택지 목록을 파싱하는 공용 유틸.
 * CharacterEditFragment와 EventEditDialogFragment(B-10)가 공유한다.
 */
object FieldOptionParser {

    private val gson = Gson()

    fun parseSelectOptions(configJson: String): List<String> {
        return try {
            val configMap: Map<String, Any> = gson.fromJson(configJson, GsonTypes.STRING_ANY_MAP)
            (configMap["options"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseGradeOptions(configJson: String): List<String> {
        val defaultGrades = listOf("C", "B", "A", "S")
        return try {
            val configMap: Map<String, Any> = gson.fromJson(configJson, GsonTypes.STRING_ANY_MAP)
            @Suppress("UNCHECKED_CAST")
            val gradesMap = configMap["grades"] as? Map<String, Any>
            val gradeKeys = if (gradesMap != null) {
                gradesMap.entries
                    .sortedBy { (it.value as? Number)?.toDouble() ?: 0.0 }
                    .map { it.key }
            } else {
                defaultGrades
            }
            val allowNegative = configMap["allowNegative"] as? Boolean ?: false
            if (allowNegative) {
                gradeKeys.flatMap { listOf("-$it", it) }
            } else {
                gradeKeys
            }
        } catch (e: Exception) {
            defaultGrades
        }
    }
}
