package com.novelcharacter.app.util

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 필드 타입별 랜덤 값 생성기.
 * - 생일(BIRTH_DATE): 계절 기반 MM-DD 생성
 * - NUMBER: 범위 내 숫자 (정수/소수)
 * - SELECT: 옵션 중 하나
 * - GRADE: 등급 중 하나
 */
object FieldRandomGenerator {

    enum class Season(val label: String, val months: List<Int>) {
        SPRING("봄", listOf(3, 4, 5)),
        SUMMER("여름", listOf(6, 7, 8)),
        AUTUMN("가을", listOf(9, 10, 11)),
        WINTER("겨울", listOf(12, 1, 2)),
        ANY("상관없음", (1..12).toList())
    }

    /**
     * 생일 랜덤 생성.
     * 계절 내 모든 유효 (월, 일) 쌍에서 균등 확률로 선택.
     * Feb 29는 윤년 독립성을 위해 제외.
     */
    fun generateBirthday(season: Season): String {
        val validDates = season.months.flatMap { month ->
            val maxDay = when (month) {
                2 -> 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            (1..maxDay).map { day -> month to day }
        }
        val (m, d) = validDates.random()
        return "%02d-%02d".format(m, d)
    }

    /**
     * NUMBER 필드 랜덤 생성.
     * @param min 최솟값
     * @param max 최댓값 (min > max이면 자동 swap)
     * @param decimalPlaces 소수점 자릿수 (0이면 정수)
     */
    fun generateNumber(min: Double, max: Double, decimalPlaces: Int = 0): String {
        val safeMin = minOf(min, max)
        val safeMax = maxOf(min, max)
        val value = if (safeMin == safeMax) safeMin
        else safeMin + Random.nextDouble() * (safeMax - safeMin)
        return if (decimalPlaces <= 0) value.roundToInt().toString()
        else "%.${decimalPlaces}f".format(value)
    }

    /** SELECT 필드: options 중 하나. 빈 리스트면 null. */
    fun generateSelect(options: List<String>): String? = options.randomOrNull()

    /** GRADE 필드: grades 중 하나. 빈 리스트면 null. */
    fun generateGrade(grades: List<String>): String? = grades.randomOrNull()
}
