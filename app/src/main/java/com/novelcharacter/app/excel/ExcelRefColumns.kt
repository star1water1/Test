package com.novelcharacter.app.excel

/**
 * 편집 가능한 '이름' 열 + readOnly '코드' 열로 이루어진 **참조 열 쌍**의 해석 규약
 * (캐릭터 관계 시트의 '세력'/'세력코드'가 첫 적용 대상).
 *
 * 핵심: **참조의 '유무'는 편집 가능한 이름 열이, '대상'은 코드가 정한다.**
 * 이래야 (가) 사용자가 회색 코드 열을 건드리지 않고도 참조를 해제할 수 있고
 * (나) 대상 개명·기기 이전에도 코드가 대상을 지켜준다.
 *
 * 이 규약이 없으면 "설정은 받고 해제는 무시하는" 비대칭이 생긴다 — 사용자가 명시적으로 비운 셀을
 * 무음 폐기하는 것이며, 사용 안내의 "칸을 비우면 값이 지워집니다"와도 어긋난다.
 */
enum class RefIntent {
    /** 두 열 다 없음 → 기존 값 유지 (F1-A) */
    KEEP,

    /** 이름 열이 있는데 칸이 빔(또는 이름 열 없이 코드 칸이 빔) → 해제 의도 */
    CLEAR,

    /** 값이 있음 → 대상을 조회한다 (코드 우선 → 이름 폴백) */
    LOOKUP
}

/**
 * 참조 열 쌍의 의도를 판정한다 (순수 함수 — 단위 테스트 대상).
 * 대상 조회는 호출측이 한다 — 조회 규칙(동명 좁히기 등)은 참조 종류마다 다르기 때문이다.
 *
 * - 이름 열이 있고 칸이 비면 CLEAR. 남아 있는 코드 셀은 **읽지 않는다**(내보내기 잔재이므로,
 *   읽으면 사용자가 이름을 지워도 해제되지 않는다).
 * - 이름 열이 아예 없으면(구버전 파일·열 삭제) 코드 열이 유무까지 결정한다.
 * - 둘 다 없으면 KEEP.
 */
fun refColumnIntent(
    nameColumnPresent: Boolean,
    codeColumnPresent: Boolean,
    nameCell: String,
    codeCell: String
): RefIntent {
    if (!nameColumnPresent && !codeColumnPresent) return RefIntent.KEEP
    if (nameColumnPresent) return if (nameCell.isBlank()) RefIntent.CLEAR else RefIntent.LOOKUP
    return if (codeCell.isBlank()) RefIntent.CLEAR else RefIntent.LOOKUP
}
