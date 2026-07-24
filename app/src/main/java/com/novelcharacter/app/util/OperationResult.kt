package com.novelcharacter.app.util

/**
 * 데이터 처리 조작의 결과 — ViewModel이 emit하고, 즉시 알림(Toast/Snackbar)과
 * 작업 이력(OperationLog)에 함께 쓰이는 단일 결과 모델.
 *
 * CLAUDE.md 변수 제어 원칙: 모든 의미있는 조작은 "무엇이 몇 건 처리됐는지"를 알리고,
 * 모든 실패는 항상 알린다.
 *
 * @param category 이력 화면 분류/아이콘용 식별자 (Category 상수 사용)
 * @param summary 사용자에게 보일 한 줄 요약 (예: "세계관 '아르카나' 저장됨", "관계 3건 삭제")
 * @param success 성공 여부 — 실패면 알림이 오류 스타일로 표시된다
 * @param detail 상세 내역 (경고·오류 목록 등). 있으면 알림에 '상세' 액션이 붙는다
 */
data class OpResult(
    val category: String,
    val summary: String,
    val success: Boolean = true,
    val detail: String? = null,
) {
    companion object {
        // 분류 (이력 화면 그룹/아이콘) — 도메인 단위
        const val CAT_CHARACTER = "character"
        const val CAT_UNIVERSE = "universe"
        const val CAT_NOVEL = "novel"
        const val CAT_FACTION = "faction"
        const val CAT_FIELD = "field"
        const val CAT_RELATIONSHIP = "relationship"
        const val CAT_EVENT = "event"
        const val CAT_NAMEBANK = "namebank"
        const val CAT_PRESET = "preset"
        const val CAT_EXCEL = "excel"
        const val CAT_BACKUP = "backup"
        const val CAT_SHARE = "share"
        const val CAT_TRASH = "trash"
        const val CAT_MAINTENANCE = "maintenance"
        const val CAT_BATCH = "batch"
        const val CAT_FIELD_LIBRARY = "field_library"

        fun success(category: String, summary: String, detail: String? = null) =
            OpResult(category, summary, success = true, detail = detail)

        fun failure(category: String, summary: String, detail: String? = null) =
            OpResult(category, summary, success = false, detail = detail)
    }
}
