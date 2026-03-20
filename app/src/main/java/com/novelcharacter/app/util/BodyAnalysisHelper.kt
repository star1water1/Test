package com.novelcharacter.app.util

import kotlin.math.abs

data class BodyAnalysisResult(
    val bmi: Double? = null,
    val bmiCategory: String? = null,
    val whr: Double? = null,
    val bodyType: String? = null,
    val cupSize: String? = null,
    val bustDiff: Double? = null
)

class BodyAnalysisHelper {

    fun analyze(
        bust: Double?, waist: Double?, hip: Double?,
        heightCm: Double?, weightKg: Double?
    ): BodyAnalysisResult? {
        if (bust == null || waist == null || hip == null) return null
        if (bust <= 0 || waist <= 0 || hip <= 0) return null

        // 1. 컵 사이즈 (bust - underbust 추정, underbust ≈ waist)
        val underbust = waist
        val diff = bust - underbust
        val cup = when {
            diff < 7.5 -> "AA"
            diff < 10.0 -> "A"
            diff < 12.5 -> "B"
            diff < 15.0 -> "C"
            diff < 17.5 -> "D"
            diff < 20.0 -> "E"
            diff < 22.5 -> "F"
            else -> "G+"
        }

        // 2. 체형 분류 (bust:waist:hip 비율)
        val bustHipRatio = bust / hip
        val waistHipRatio = waist / hip
        val waistBustRatio = waist / bust
        val bodyType = when {
            waistHipRatio <= 0.75 && abs(bustHipRatio - 1.0) <= 0.05 -> "모래시계형(Hourglass)"
            waistHipRatio <= 0.75 && bustHipRatio < 0.95 -> "배형(Pear)"
            waistBustRatio > 0.85 && waistHipRatio > 0.85 -> "사과형(Apple)"
            waistHipRatio <= 0.75 && bustHipRatio > 1.05 -> "역삼각형(Inverted Triangle)"
            else -> "직사각형(Rectangle)"
        }

        // 3. BMI
        val bmi = if (heightCm != null && weightKg != null && heightCm > 0 && weightKg > 0) {
            weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
        } else null
        val bmiCategory = bmi?.let {
            when {
                it < 18.5 -> "저체중"
                it < 25.0 -> "정상"
                it < 30.0 -> "과체중"
                else -> "비만"
            }
        }

        // 4. WHR
        val whr = waist / hip

        return BodyAnalysisResult(bmi, bmiCategory, whr, bodyType, cup, diff)
    }

    companion object {
        fun parseNumericFromText(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val cleaned = text.trim().replace(Regex("[^0-9.]"), "")
            return cleaned.toDoubleOrNull()
        }
    }
}
