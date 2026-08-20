package com.novelcharacter.app.excel

/**
 * 세계관 캐릭터 시트 **배정표 재현** (순수) — 내보내기가 [assignSheetName]으로 배정한
 * 세계관별 캐릭터 시트 이름을, 파일의 '세계관' 시트 행(이름·코드, 행 순서 그대로)만으로 되짚는다.
 *
 * ## 왜 있는가 (2026.08.20 — 동명 세계관 시트 오배정 유실)
 *
 * 내보내기는 sanitize 결과가 같아지는 두 세계관(완전 동명 · 31자 절단 충돌 · 금칙문자 제거
 * 충돌 · 대소문자만 다름)에 공유 usedNames로 `이름`과 `이름(2)`를 갈라 준다. 종전 가져오기는
 * 세계관 **이름만으로 첫 일치 시트**를 찾아 두 세계관이 같은 기본명 시트를 받았고 —
 * 캐릭터가 양쪽에 중복 삽입되고, `(2)` 시트는 아무도 읽지 않았으며, '엑셀에 없는 항목 삭제'
 * 에서는 둘째 세계관의 실제 캐릭터가 파일에 없는 것으로 판정되어 삭제됐다.
 * 이 배정표는 **코드**로 각 세계관의 시트를 지목해 그 유실을 원천에서 없앤다. 배정표에 없는
 * 세계관(코드 없는 행·외부 편집·레거시 파일)은 호출부의 휴리스틱이 소비 추적과 함께 받는다.
 *
 * ## 빈 usedNames 재현이 내보내기와 같은 답인 근거
 *
 * 내보내기가 세계관 캐릭터 시트를 배정하는 시점의 usedNames에는 **예약명 시트**(안내·목록
 * 데이터·'전체 캐릭터'·오버플로 포함 — 전부 [RESERVED_SHEET_NAMES]의 소유자)와 **앞서 배정된
 * 세계관 시트**뿐이다. [assignSheetName]은 예약명을 usedNames와 무관하게 소유자 아닌 호출에
 * 내주지 않으므로, 빈 usedNames에서 세계관 이름만 같은 순서로 다시 돌리면 같은 답이 나온다.
 * '미분류 캐릭터' 시트는 세계관 루프 **뒤**에 배정되므로 영향이 없고, 내보내기 옵션으로 예약
 * 시트가 몇 개 빠져도 답이 같다. 그 순서는 '세계관' 시트의 행 순서다 — 내보내기가 같은 목록
 * (`getAllUniversesList`: displayOrder ASC, createdAt DESC)을 행 쓰기와 시트 배정에 함께 쓴다.
 * [UniverseSheetPlanTest]가 내보내기 배정 시뮬레이션과의 왕복 일치를 잠근다.
 */
class UniverseSheetPlan private constructor(
    private val nameByCode: Map<String, String>,
    private val ownerByName: Map<String, String>
) {

    /** '세계관' 시트의 한 행 — 배정 재현에 필요한 두 칸만 든다. */
    data class Row(val name: String, val code: String)

    /** 이 코드의 세계관에 내보내기가 준 시트명. 코드가 비었거나 배정표에 없으면 null. */
    fun sheetNameFor(universeCode: String): String? =
        if (universeCode.isBlank()) null else nameByCode[universeCode]

    /**
     * 이 시트명을 배정표가 지목한 세계관 코드. 배정표 밖의 시트명이면 null.
     * 휴리스틱 폴백이 **남의 배정 시트를 가로채지 않게** 하는 판정에 쓴다.
     */
    fun plannedOwnerOf(sheetName: String): String? = ownerByName[sheetName]

    companion object {
        /**
         * '세계관' 시트 행으로 배정표를 짓는다. 빈 이름 행은 세계관이 아니므로 건너뛴다
         * (importUniverses와 같은 갈래 — 내보내기는 빈 이름 행을 쓰지 않아 번호가 어긋나지 않는다).
         *
         * 코드가 빈 행은 배정(번호 소비)에는 참여하되 지목할 수 없고, 파일 안에서 **중복된
         * 코드는 청구권을 걷는다** — 어느 행이 진짜인지 좁혀지지 않으므로 조용히 고르지 않고
         * (규약 4-3) 그 세계관은 휴리스틱 폴백으로 내려보낸다.
         */
        fun build(rows: List<Row>): UniverseSheetPlan {
            val used = mutableSetOf<String>()
            val nameByCode = LinkedHashMap<String, String>()
            val ownerByName = LinkedHashMap<String, String>()
            val duplicated = mutableSetOf<String>()
            for (row in rows) {
                if (row.name.isBlank()) continue
                // 청구 여부와 무관하게 먼저 배정한다 — 뒤 행의 접미사 번호가 이 배정에 걸려 있다.
                val assigned = assignSheetName(row.name, used, ownerOf = null)
                val code = row.code
                if (code.isBlank()) continue
                if (code in duplicated) continue
                if (code in nameByCode) {
                    duplicated.add(code)
                    nameByCode.remove(code)?.let { ownerByName.remove(it) }
                    continue
                }
                nameByCode[code] = assigned
                ownerByName[assigned] = code
            }
            return UniverseSheetPlan(nameByCode, ownerByName)
        }
    }
}
