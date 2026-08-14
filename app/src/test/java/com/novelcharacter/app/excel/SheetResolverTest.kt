package com.novelcharacter.app.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * [SheetResolver] — 예약 시트 해석의 단일 판정 (B-217 · 규약 R-33).
 *
 * 미리보기(`analyze*`)·가져오기(`findSheet`)·삭제 가드(`canRestore`)가 **같은 판정**을 보는
 * 것이 이 오브젝트의 존재 이유다. 종전에는 판정이 DB에 묶인 서비스 안에 있어 어떤 로컬
 * 검증도 닿지 못했고, 미리보기 열아홉 자리가 정확명 `getSheet`로 우회한 것을 아무것도 잡지
 * 못했다. 여기서는 판정의 답 자체를 **DOM·스트리밍 양 경로**에서 잠근다
 * ([ImportSourceEquivalenceTest]가 접근면의 동치를, 이 시험이 그 위 판정의 답을 잰다).
 *
 * 각 시나리오는 실제로 겪은 파일 모양이다 — 세계관 이름이 예약명과 같아 캐릭터 시트가
 * 평명을 차지하고 진짜 데이터 시트가 `이름(2)`로 밀려난 레거시 백업(무음 유실 사고의 그
 * 파일), 구버전 헤더의 정확명 시트(관대 수용 경로), 시트가 아예 없는 파일.
 */
class SheetResolverTest {

    private lateinit var file: File

    @Before fun setUp() { file = File.createTempFile("sheet-resolver", ".xlsx") }
    @After fun tearDown() { file.delete() }

    private fun writeAndOpen(wb: XSSFWorkbook): Pair<DomImportWorkbook, StreamingImportWorkbook> {
        FileOutputStream(file).use { wb.write(it) }
        val dom = DomImportWorkbook(XSSFWorkbook(file))
        val streaming = StreamingImportWorkbook(file)
        return dom to streaming
    }

    /** 판정은 경로와 무관해야 한다 — 두 경로 모두에서 같은 단언을 돌린다. */
    private fun onBothPaths(wb: XSSFWorkbook, assertions: (ImportWorkbook) -> Unit) {
        val (dom, streaming) = writeAndOpen(wb)
        assertions(dom)
        assertions(streaming)
        streaming.close()
    }

    /** 세계관 캐릭터 시트 — 첫 열 '이름' + 지문 헤더('이명' 등). 이름 은행과 첫 열이 같다. */
    private fun XSSFWorkbook.addCharacterSheet(name: String) {
        val sheet = createSheet(name)
        sheet.createRow(0).apply {
            createCell(0).setCellValue("이름")
            createCell(1).setCellValue("이명")
            createCell(2).setCellValue("작품")
            createCell(3).setCellValue("메모")
        }
        sheet.createRow(1).apply {
            createCell(0).setCellValue("홍길동")
            createCell(1).setCellValue("길동이")
        }
    }

    /** 진짜 '이름 은행' 시트 — spec 앞 3열(이름·성별·출처)이 자리까지 맞는다. */
    private fun XSSFWorkbook.addNameBankSheet(name: String) {
        val sheet = createSheet(name)
        sheet.createRow(0).apply {
            createCell(0).setCellValue("이름")
            createCell(1).setCellValue("성별")
            createCell(2).setCellValue("출처")
            createCell(3).setCellValue("메모")
        }
        sheet.createRow(1).apply {
            createCell(0).setCellValue("가온")
            createCell(1).setCellValue("여")
        }
    }

    // ── ① 정상 파일: 정확명 + spec 헤더 ──────────────────────────────────────

    @Test
    fun exactNameWithMatchingHeader_isResolved_onBothPaths() {
        val wb = XSSFWorkbook()
        wb.addNameBankSheet("이름 은행")
        onBothPaths(wb) { workbook ->
            assertEquals("이름 은행", SheetResolver.resolveSpecSheet(workbook, nameBankSpec())?.sheetName)
            assertEquals("이름 은행", SheetResolver.sheetForRead(workbook, nameBankSpec())?.sheetName)
        }
    }

    // ── ② 레거시 밀림: 캐릭터 시트가 평명을 차지, 진짜는 접미사로 ──────────────

    @Test
    fun reservedNameTakenByCharacterSheet_findsSuffixedRealSheet_onBothPaths() {
        val wb = XSSFWorkbook()
        // 세계관 이름이 '이름 은행'이라 그 세계관의 캐릭터 시트가 평명을 차지했다.
        wb.addCharacterSheet("이름 은행")
        wb.addNameBankSheet("이름 은행(2)")
        onBothPaths(wb) { workbook ->
            // 첫 열('이름')만 보면 캐릭터 시트가 통과한다 — 지문 배제가 그 시트를 걸러내고
            // 접미사 탐색이 밀려난 진짜를 되찾아야 한다(무음 유실 사고의 그 판정).
            assertEquals("이름 은행(2)", SheetResolver.resolveSpecSheet(workbook, nameBankSpec())?.sheetName)
            assertEquals("이름 은행(2)", SheetResolver.sheetForRead(workbook, nameBankSpec())?.sheetName)
        }
    }

    @Test
    fun suffixedCharacterSheet_isNotMistakenForRealSheet_onBothPaths() {
        val wb = XSSFWorkbook()
        // 평명도 접미사도 캐릭터 시트다 — 어느 쪽도 데이터 시트로 넘겨주면 안 된다.
        wb.addCharacterSheet("이름 은행")
        wb.addCharacterSheet("이름 은행(2)")
        onBothPaths(wb) { workbook ->
            assertNull(SheetResolver.resolveSpecSheet(workbook, nameBankSpec()))
            // 관대 폴백은 정확명 시트를 돌려준다 — 헤더 검증·경고는 호출부(checkHeaderOrReport) 몫,
            // 가져오기 findSheet와 같은 답이다.
            assertEquals("이름 은행", SheetResolver.sheetForRead(workbook, nameBankSpec())?.sheetName)
        }
    }

    // ── ③ 구버전 헤더: 정확명은 있는데 spec 헤더가 아니다 ────────────────────

    @Test
    fun exactNameWithForeignHeader_fallsBackToExact_onlyInSheetForRead_onBothPaths() {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("이름 은행")
        sheet.createRow(0).apply { createCell(0).setCellValue("옛헤더") }
        onBothPaths(wb) { workbook ->
            assertNull(SheetResolver.resolveSpecSheet(workbook, nameBankSpec()))
            assertEquals("이름 은행", SheetResolver.sheetForRead(workbook, nameBankSpec())?.sheetName)
        }
    }

    // ── ④ 시트 없음 ──────────────────────────────────────────────────────────

    @Test
    fun missingSheet_isNull_onBothPaths() {
        val wb = XSSFWorkbook()
        wb.addCharacterSheet("무관한 시트")
        onBothPaths(wb) { workbook ->
            assertNull(SheetResolver.resolveSpecSheet(workbook, nameBankSpec()))
            assertNull(SheetResolver.sheetForRead(workbook, nameBankSpec()))
        }
    }

    // ── ⑤ 캐릭터 시트 지문 ───────────────────────────────────────────────────

    @Test
    fun characterFingerprint_separatesCharacterSheetFromDataSheets_onBothPaths() {
        val wb = XSSFWorkbook()
        wb.addCharacterSheet("캐릭터들")
        wb.addNameBankSheet("이름 은행")
        // 세계관 시트 모양 — 첫 열 '이름'이지만 지문 헤더가 없다('이미지경로'는 지문에서 제외된 열).
        wb.createSheet("세계관").createRow(0).apply {
            createCell(0).setCellValue("이름")
            createCell(1).setCellValue("설명")
            createCell(2).setCellValue("이미지경로")
        }
        onBothPaths(wb) { workbook ->
            assertTrue(SheetResolver.looksLikeCharacterSheet(workbook.getSheet("캐릭터들")!!))
            assertFalse(SheetResolver.looksLikeCharacterSheet(workbook.getSheet("이름 은행")!!))
            assertFalse(SheetResolver.looksLikeCharacterSheet(workbook.getSheet("세계관")!!))
        }
    }

    // ── ⑥ 첫 열 별칭: 영문 헤더 파일도 spec 시트로 인정 ──────────────────────

    @Test
    fun aliasFirstHeader_isAccepted_onBothPaths() {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("이름 은행")
        sheet.createRow(0).apply {
            createCell(0).setCellValue("name")   // '이름'의 등록 별칭
            createCell(1).setCellValue("성별")
        }
        onBothPaths(wb) { workbook ->
            // isValidHeader가 별칭을 표준으로 되돌린다 — 다만 접미사 판정(headersMatchSpec)이
            // 아니라 정확명 판정이므로 resolveSpecSheet가 그 시트를 그대로 돌려준다.
            assertEquals("이름 은행", SheetResolver.resolveSpecSheet(workbook, nameBankSpec())?.sheetName)
        }
    }
}
