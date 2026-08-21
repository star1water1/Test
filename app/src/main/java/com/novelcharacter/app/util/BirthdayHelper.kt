package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

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
        val today = Calendar.getInstance(Locale.US)
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
    fun getTodayBirthdayCharacterIds(birthChanges: List<CharacterStateChange>): List<Long> =
        todayBirthdays(birthChanges).map { it.characterId }

    /**
     * 오늘 생일인 캐릭터의 **그 상태 변화 행** — 판정과 함께 *어느 행이 맞았는가*를 돌려준다.
     *
     * ## 왜 id만으로는 모자란가 (2026.08.20)
     * `__birth`는 **DB가 강제하는 유일 행이 아니다** — 휴지통 복원만 [SINGLETON_STATE_KEYS]로
     * 막고, 엑셀은 `(캐릭터·연도·필드키·값)`이 다르면 두 번째 행을 만든다. 그래서 한 캐릭터가
     * 3/15와 8/20 두 생일 행을 들 수 있다.
     *
     * 부르는 쪽이 id만 받아 **자기가 다시 행을 고르면** 오늘 맞은 행이 아니라 아무 행이나
     * 집을 수 있고, 그러면 8/20에 뜬 축하 창이 *"3월 15일"*이라고 적는다. 맞은 행을 아는 것은
     * 여기뿐이라 여기서 돌려준다.
     *
     * 같은 캐릭터가 여러 행으로 맞으면 **처음 맞은 행**이다([getTodayBirthdayCharacterIds]의
     * `distinct()`가 종전에 남기던 것과 같다).
     */
    fun todayBirthdays(birthChanges: List<CharacterStateChange>): List<CharacterStateChange> {
        val today = Calendar.getInstance(Locale.US)
        val todayMonth = today.get(Calendar.MONTH) + 1
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val isLeapYear = GregorianCalendar().isLeapYear(today.get(Calendar.YEAR))

        val matched = mutableListOf<CharacterStateChange>()
        for (change in birthChanges) {
            val m = change.month ?: continue
            val d = change.day ?: continue
            if (m == todayMonth && d == todayDay) {
                matched.add(change)
            } else if (!isLeapYear && todayMonth == 2 && todayDay == 28 && m == 2 && d == 29) {
                // 비윤년 2/28: 2/29 생일도 오늘로 간주
                matched.add(change)
            }
        }
        return matched.distinctBy { it.characterId }
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
        val today = Calendar.getInstance(Locale.US).apply {
            set(Calendar.YEAR, todayYear)
            set(Calendar.MONTH, todayMonth - 1)
            set(Calendar.DAY_OF_MONTH, todayDay)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val effectiveDay = adjustLeapDay(birthMonth, birthDay, todayYear)

        val birthday = Calendar.getInstance(Locale.US).apply {
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
        val cal = Calendar.getInstance(Locale.US).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return day.coerceAtMost(maxDay)
    }
}
