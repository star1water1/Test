package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook

/**
 * 한도를 넘은 드롭다운 목록을 담는 **숨김 시트** — 필요할 때만 생긴다 (B-221).
 *
 * 판정([DropdownListLimits])이 *"명시 목록으로는 못 싣는다"*고 답한 목록만 여기로 온다.
 * 값들을 시트에 적고 그 구간을 가리키는 절대 참조를 돌려주면, 부르는 쪽은 그것을
 * `createFormulaListConstraint`에 그대로 넣는다 — 범위 참조에는 개수 한도가 없다.
 *
 * ## 왜 열이 아니라 **한 열에 이어 붙이는가**
 *
 * 목록마다 열을 나누면 같은 행 번호를 여러 번 만들어야 하는데, 스트리밍 워크북은 창을 넘긴
 * 행을 디스크로 흘려보내고 메모리에서 버린다([ExportWorkbooks]) — 되돌아가 만든 행은
 * **조용히 사라진다.** 한 열에 이어 붙이면 행 번호가 언제나 증가해 두 구현이 같은 파일을 낸다.
 *
 * ## 같은 목록은 한 번만 적는다
 *
 * 세계관 이름·작품 제목 목록은 열 곳 넘는 시트가 함께 쓴다. 그대로 두면 같은 값이 워크북에
 * 열 벌 실린다 — 목록이 클수록 그 낭비가 커지는 자리라 참조를 캐시한다.
 *
 * **워크북 하나에 하나씩** 쓴다(시트와 다음 행 번호를 들고 있어 다음 내보내기로 넘어가면
 * 어긋난다). 한 워크북을 여러 스레드가 함께 쓰는 경로는 없다.
 */
class DropdownListSheet(private val sheetName: String) {

    private var sheet: Sheet? = null
    private var nextRow = 0
    private val cache = HashMap<String, String>()

    /** 실제로 시트를 만들었는가 — 안 만들었으면 그 워크북에는 이 시트가 없다. */
    val created: Boolean get() = sheet != null

    /** 지금까지 적은 행 수 — 이어 붙이기가 되돌아가지 않는다는 것의 관측점. */
    val rowsWritten: Int get() = nextRow

    /**
     * [options]를 담은 구간의 절대 참조. 같은 목록을 다시 물으면 **이미 적은 구간**을 준다.
     */
    fun referenceFor(workbook: Workbook, options: List<String>): String {
        // 구분자는 NUL — 셀 값에 들어올 수 없는 글자라 서로 다른 목록이 같은 키가 되지
        // 않는다(공백·쉼표로 이으면 ["가 나"]와 ["가","나"]가 한 키가 된다).
        val key = options.joinToString("\u0000")
        cache[key]?.let { return it }
        val target = sheet ?: workbook.createSheet(sheetName).also {
            sheet = it
            workbook.setSheetHidden(workbook.getSheetIndex(it), true)
        }
        val first = nextRow
        for (value in options) {
            target.createRow(nextRow).createCell(0).setCellValue(value)
            nextRow++
        }
        val ref = DropdownListLimits.rangeFormula(sheetName, LIST_COLUMN, first, nextRow - 1)
        cache[key] = ref
        return ref
    }

    private companion object {
        const val LIST_COLUMN = 0
    }
}
