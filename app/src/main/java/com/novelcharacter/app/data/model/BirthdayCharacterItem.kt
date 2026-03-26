package com.novelcharacter.app.data.model

/**
 * 생일 배너 UI용 데이터 클래스.
 * 캐릭터 정보 + 생일 날짜 + D-day를 함께 보관.
 */
data class BirthdayCharacterItem(
    val character: Character,
    val birthMonth: Int,
    val birthDay: Int,
    val daysUntil: Int  // 0=오늘, 1=내일, ...
)
