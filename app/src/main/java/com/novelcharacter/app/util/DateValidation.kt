package com.novelcharacter.app.util

/** 월별 최대 일수 반환 (윤년 무시 — 판타지 세계관에서는 2월 29일 항상 허용) */
fun maxDayOfMonth(month: Int?): Int = when (month) {
    2 -> 29
    4, 6, 9, 11 -> 30
    else -> 31
}

/** 일(day) 값이 해당 월에 유효한지 검사 */
fun isValidDay(month: Int?, day: Int?): Boolean {
    if (day == null) return true
    if (day < 1 || day > 31) return false
    return day <= maxDayOfMonth(month)
}
