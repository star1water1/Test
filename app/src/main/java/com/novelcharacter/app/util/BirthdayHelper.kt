package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * 생일 관련 공용 유틸리티.
 * BirthdayWorker, TodayCharacterWidget, 생일 배너에서 공통 사용.
 */
object BirthdayHelper {

    data class UpcomingBirthday(
        val characterId: Long,
        val birthMonth: Int,
        val birthDay: Int,
        val daysUntil: Int  // 0=오늘, 1=내일, ...
    )

    /**
     * 오늘 기준으로 [daysAhead]일 이내 생일인 캐릭터를 필터링한다.
     * 월/연 경계(12월→1월) 및 윤년(2/29) 처리 포함.
     * @param birthChanges __birth 상태변경 목록 (month/day가 non-null인 것만 전달할 것)
     * @param daysAhead 검색 범위 (기본 7일)
     * @return daysUntil 오름차순 정렬된 결과, characterId 중복 제거
     */
    fun filterUpcoming(
        birthChanges: List<CharacterStateChange>,
        daysAhead: Int = 7
    ): List<UpcomingBirthday> {
        val today = Calendar.getInstance()
        val todayMonth = today.get(Calendar.MONTH) + 1
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val todayYear = today.get(Calendar.YEAR)

        return birthChanges
            .filter { it.month != null && it.day != null }
            .mapNotNull { change ->
                val days = daysUntilBirthday(todayMonth, todayDay, todayYear, change.month!!, change.day!!)
                if (days in 0 until daysAhead) {
                    UpcomingBirthday(
                        characterId = change.characterId,
                        birthMonth = change.month,
                        birthDay = change.day,
                        daysUntil = days
                    )
                } else null
            }
            .distinctBy { it.characterId }
            .sortedBy { it.daysUntil }
    }

    /**
     * 오늘 생일인 캐릭터 ID를 반환한다. 비윤년에서 2/29 생일 포함.
     * BirthdayWorker와 TodayCharacterWidget에서 사용.
     */
    fun getTodayBirthdayCharacterIds(birthChanges: List<CharacterStateChange>): List<Long> {
        val today = Calendar.getInstance()
        val todayMonth = today.get(Calendar.MONTH) + 1
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val isLeapYear = GregorianCalendar().isLeapYear(today.get(Calendar.YEAR))

        val ids = mutableListOf<Long>()
        for (change in birthChanges) {
            val m = change.month ?: continue
            val d = change.day ?: continue
            if (m == todayMonth && d == todayDay) {
                ids.add(change.characterId)
            } else if (!isLeapYear && todayMonth == 2 && todayDay == 28 && m == 2 && d == 29) {
                // 비윤년 2/28: 2/29 생일도 오늘로 간주
                ids.add(change.characterId)
            }
        }
        return ids.distinct()
    }

    /**
     * 생일까지 남은 일수를 계산한다.
     * - 월/연 경계 처리 (12월→1월)
     * - 비윤년에서 2/29 생일 → 2/28로 보정
     * @return 0=오늘, 1=내일, ..., 364=내년 같은 날 직전
     */
    fun daysUntilBirthday(todayMonth: Int, todayDay: Int, todayYear: Int,
                          birthMonth: Int, birthDay: Int): Int {
        // DST 전환일 오차 방지를 위해 정오(12시) 기준 계산
        val today = Calendar.getInstance().apply {
            set(Calendar.YEAR, todayYear)
            set(Calendar.MONTH, todayMonth - 1)
            set(Calendar.DAY_OF_MONTH, todayDay)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val effectiveDay = adjustLeapDay(birthMonth, birthDay, todayYear)

        val birthday = Calendar.getInstance().apply {
            set(Calendar.YEAR, todayYear)
            set(Calendar.MONTH, birthMonth - 1)
            set(Calendar.DAY_OF_MONTH, effectiveDay)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 올해 생일이 이미 지났으면 내년 생일로
        if (birthday.before(today)) {
            val nextYear = todayYear + 1
            val nextEffectiveDay = adjustLeapDay(birthMonth, birthDay, nextYear)
            birthday.set(Calendar.YEAR, nextYear)
            birthday.set(Calendar.MONTH, birthMonth - 1) // year 변경 시 month 재설정 보장
            birthday.set(Calendar.DAY_OF_MONTH, nextEffectiveDay)
        }

        val diffMs = birthday.timeInMillis - today.timeInMillis
        return (diffMs / (1000L * 60 * 60 * 24)).toInt()
    }

    /**
     * 비윤년에서 2/29 → 2/28로 보정.
     * 유효하지 않은 날짜(예: 월이 31일까지 없는 달의 31일)도 해당 월 최대일로 보정.
     */
    fun adjustLeapDay(month: Int, day: Int, year: Int): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return day.coerceAtMost(maxDay)
    }
}
