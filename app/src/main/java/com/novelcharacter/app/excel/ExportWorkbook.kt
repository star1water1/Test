package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

/**
 * 내보내기 워크북의 **두 구현** — DOM과 스트리밍 (B-72 · scalability S7).
 * 가져오기의 [ImportWorkbook]과 대칭이며, 그쪽이 세운 규약을 그대로 따른다.
 *
 * ## 왜 있는가
 *
 * [ExcelExporter]는 `XSSFWorkbook`(DOM)에 워크북 **전량**을 세운다. 그 객체 그래프의 무게는
 * 가져오기 쪽에서 이미 실제 사고로 확인됐다 — *"`XSSFWorkbook`은 모든 시트의 XMLBeans 객체
 * 그래프를 동시에 들고 있어 메모리가 파일 크기에 비례한다(그래서 128MB 상한이 있었고,
 * **앱이 만든 백업을 앱이 못 읽었다**)"*([StreamingImportWorkbook]). 쓰는 쪽은 **같은 그래프를
 * 만드는 쪽**이고, 그중 자동 백업은 배경에서 도는 마지막 방어선 경로다(B-72).
 *
 * ## 두 구현이 같은 파일을 낸다는 것이 이 파일의 전제다
 *
 * 어느 구현으로 썼는지는 **사용자에게 알리지 않는다** — 가져오기가 *"두 경로의 결과가 같다는
 * 것이 이 설계의 전제이므로, 알릴 것이 있다면 그것은 결함이지 정보가 아니다"*라고 정한 것과
 * 같다. 그 전제는 `ExportWorkbookParityTest`가 **두 구현에 같은 쓰기를 시켜 나온 파일을
 * 되읽어** 잠근다.
 *
 * ## 스트리밍이 요구하는 것 하나 — 되돌아가 쓰지 않는다
 *
 * 스트리밍 시트는 창(window)을 넘긴 행을 디스크로 흘려보내고 **메모리에서 버린다.** 그래서
 * `sheet.getRow(옛 행)`이 `null`이 되고, 다 쓴 뒤 되돌아가 스타일을 입히는 코드는 **조용히
 * 아무 일도 하지 않는다.** 실측이 그 모양을 확인했다(POI 5.3.0, 창 100, 500행):
 * 되돌아가는 방식은 DOM에서 500행에 스타일이 붙고 스트리밍에서는 **100행만** 붙는다.
 * 그래서 [ExcelExporter]는 읽기 전용 칸 스타일을 **그 행을 쓰는 자리에서** 입힌다
 * (`finishDataRow`). 그 뒤로는 창 크기가 **정확성 매개변수가 아니다** — 성능·메모리 손잡이일 뿐이다.
 *
 * 시트 수준 서식은 둘 다 그대로 된다(같은 실측: 고정 창·자동 필터·열 너비·유효성 검사 전부).
 */
object ExportWorkbooks {

    /**
     * 메모리에 들고 있는 행 수 — POI 기본값과 같다.
     *
     * **작게 두는 것이 안전한 이유가 이 판의 산출물이다**: 되돌아가 쓰는 자리를 없앴으므로
     * 이 값은 정확성에 관여하지 않는다. 종전 코드였다면 이 숫자가 *"몇 행까지 스타일이
     * 살아남는가"*를 정하는 값이었고, 그런 값은 조용히 틀리는 종류다.
     */
    const val ROW_WINDOW = 100

    /**
     * 임시 파일을 앱이 아는 자리에 만든다.
     *
     * 스트리밍 워크북은 시트 데이터를 임시 파일에 흘려보내는데, 그 자리를 정하지 않으면
     * `java.io.tmpdir`이 정한다 — 안드로이드에서 그것이 어디인지는 기기·판올림에 따라
     * 달라지는 값이라 **앱이 지울 수도, 용량을 셀 수도 없는 자리**에 백업 크기의 임시 파일이
     * 생길 수 있다. 캐시 밑으로 못 박으면 실패 시 남은 것도 앱이 치울 수 있다.
     */
    fun useTempDirectory(cacheDir: File) {
        val poiTemp = File(cacheDir, "poi-temp")
        poiTemp.mkdirs()
        org.apache.poi.util.TempFile.setTempFileCreationStrategy(
            org.apache.poi.util.DefaultTempFileCreationStrategy(poiTemp)
        )
    }

    /**
     * @param streaming true면 스트리밍(메모리가 데이터 양에 비례하지 않는다), false면 DOM.
     *   **DOM 구현을 남겨 두는 것은 의도다** — 실기기 측정이 스트리밍의 대가(임시 파일 I/O·
     *   디스크)를 문제로 지목하면 이 인자 하나로 되돌릴 수 있어야 하고, 두 구현이 같은 파일을
     *   낸다는 것을 시험이 계속 잠그고 있어야 그 되돌리기가 안전하다.
     */
    fun create(streaming: Boolean): Workbook =
        if (streaming) {
            // compressTmpFiles=false: 임시 XML을 gzip하지 않는다. 배경 백업은 시간 상한이 있는
            // 경로라(WorkManager) CPU를 더 쓰는 쪽보다 디스크를 조금 더 쓰는 쪽을 고른다.
            // useSharedStringsTable=false: 공유 문자열 표는 **전량을 메모리에 든다** —
            // 켜면 이 판이 없애려는 그 비례를 문자열 축으로 다시 들여온다.
            SXSSFWorkbook(null, ROW_WINDOW, false, false)
        } else {
            XSSFWorkbook()
        }

    /**
     * 워크북과 그것이 만든 임시 파일을 함께 놓는다.
     *
     * `close()`가 임시 파일까지 치우는 것은 POI 구현 세부라(실측: 닫은 뒤 `dispose()`는
     * 더 지울 것이 없다고 답한다) **판올림에 기대지 않고 둘 다 부른다.** 임시 파일이 남으면
     * 그 크기가 백업 한 판 분량이고, 배경에서 도는 경로라 아무도 알아채지 못한다.
     */
    fun release(workbook: Workbook?) {
        if (workbook == null) return
        try {
            workbook.close()
        } finally {
            if (workbook is SXSSFWorkbook) workbook.dispose()
        }
    }
}
