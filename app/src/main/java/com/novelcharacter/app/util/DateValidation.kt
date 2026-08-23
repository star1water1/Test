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

/**
 * 월·일이 **역에 실재하는 날**인가 — 둘 다 있고, 월이 1~12이며, 일이 그 달에 있는 날인가.
 *
 * [isValidDay]와 갈라 두는 이유는 **부재의 처분이 반대**이기 때문이다. 그쪽은 입력 검증용이라
 * `day == null`을 *"아직 안 적었다"*로 보고 통과시키는데(연도만 적은 이력이 정상이다), 날짜를
 * **실제로 만드는** 자리는 하나라도 없으면 만들 수 없다. 월 범위를 함께 보는 것도 요점이다 —
 * `isValidDay`는 월을 [maxDayOfMonth]의 인자로만 쓰므로 `month = 13`을 그대로 통과시킨다.
 *
 * **이 술어가 없어서 크래시가 났다.** `BirthdayHelper.daysUntilBirthday`가 `LocalDate.of`에
 * 월·일을 그대로 넘기는데, 앱의 쓰기 문은 전부 범위를 막지만 **월드패키지 가져오기만 그 밖**이라
 * `month = 13`인 `__birth` 행이 들어올 수 있었다. 그러면 홈 대시보드가 열릴 때마다
 * `DateTimeException`으로 죽는다 — 시작 화면이라 앱을 쓸 수 없게 된다.
 *
 * **보정이 아니라 배제다.** 엉뚱한 날짜로 접어 축하하면 그것이 곧 거짓 고지다(개발 의도 2번).
 */
fun isRealMonthDay(month: Int?, day: Int?): Boolean =
    month != null && day != null && month in 1..12 && isValidDay(month, day)
