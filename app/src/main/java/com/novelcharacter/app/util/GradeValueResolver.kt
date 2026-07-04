package com.novelcharacter.app.util

import android.util.Log
import com.google.gson.Gson
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * GRADE 필드의 등급 라벨 → 숫자 해석 단일 소스.
 *
 * 등급 해석은 수식 평가(FormulaEvaluator), 통계 랭킹(StatsDataProvider), 성장 차트
 * (CharacterGrowthFragment)가 모두 필요로 하는 도메인 규칙이다. 화면마다 별도 매핑을 두면
 * 같은 등급이 화면마다 다른 숫자가 되므로(하드코딩 금지 원칙), 반드시 이 유틸만 사용한다.
 */
object GradeValueResolver {

    /**
     * config에 grades가 없는 필드를 표시할 때의 폴백 기본값 (표준 등급 체계).
     * 수식·랭킹 등 "계산" 경로에서는 사용하지 않는다 — 계산은 사용자가 정의한 config만 신뢰한다.
     */
    val DEFAULT_GRADE_VALUES: Map<String, Double> = mapOf(
        "SSS" to 15.0, "SS" to 13.0, "S+" to 11.0, "S" to 10.0, "S-" to 9.0,
        "A+" to 8.5, "A" to 8.0, "A-" to 7.5,
        "B+" to 7.0, "B" to 6.0, "B-" to 5.5,
        "C+" to 5.0, "C" to 4.0, "C-" to 3.5,
        "D+" to 3.0, "D" to 2.0, "D-" to 1.5,
        "E" to 1.0, "F" to 0.0
    )

    /**
     * 필드 config의 grades 맵 기반 해석. allowNegative가 켜져 있으면 "-A" 형태의 접두 부호를 지원한다.
     * grades 맵이 없거나 라벨이 등록되지 않았으면 null — 폴백 정책은 호출부가 결정한다.
     */
    fun resolveFromConfig(fieldDef: FieldDefinition, gradeLabel: String): Double? {
        val config = try {
            Gson().fromJson<Map<String, Any>>(fieldDef.config, GsonTypes.STRING_ANY_MAP)
        } catch (e: Exception) {
            Log.w("GradeValueResolver", "Failed to parse grade config for field '${fieldDef.key}'", e)
            null
        } ?: return null
        val grades = (config["grades"] as? Map<*, *>) ?: return null
        val allowNegative = config["allowNegative"] as? Boolean ?: false
        val isNegative = allowNegative && gradeLabel.startsWith("-")
        val cleanLabel = gradeLabel.removePrefix("-").removePrefix("+")
        val baseValue = (grades[cleanLabel] as? Number)?.toDouble() ?: return null
        return if (isNegative) -baseValue else baseValue
    }

    /**
     * 표시(차트 등) 목적 해석: config 우선, 미정의 시 표준 기본맵 폴백.
     * 어느 쪽에도 없으면 null — 호출부가 스킵/0 처리 등을 선택한다.
     */
    fun resolveForDisplay(fieldDef: FieldDefinition?, gradeLabel: String): Double? {
        if (fieldDef != null) {
            resolveFromConfig(fieldDef, gradeLabel)?.let { return it }
        }
        val cleanLabel = gradeLabel.trim().uppercase()
        return DEFAULT_GRADE_VALUES[cleanLabel]
    }
}
