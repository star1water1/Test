package com.novelcharacter.app.excel

/**
 * 예약 시트 해석의 **단일 판정** (B-217 · 규약 R-33).
 *
 * ## 왜 있는가
 *
 * 시트가 어느 spec의 것인가 — 정확명인가, 같은 이름의 세계관 캐릭터 시트에 밀려
 * `이름 (2)`로 저장됐는가, 이름만 같고 정체는 캐릭터 시트인가 — 를 정하는 판정이
 * [ExcelImportService] 안에 있는 동안, 가져오기(`findSheet`)만 그것을 지나고
 * **미리보기(`analyze*`)는 열아홉 자리가 `workbook.getSheet(정확명)` 한 줄로 우회했다.**
 * 그래서 예약명을 빼앗긴 레거시 백업에서 가져오기는 밀린 시트를 되찾아 읽는데
 * 미리보기는 "시트 없음"이라 말하고, 이름을 차지한 캐릭터 시트를 데이터 시트로 읽어
 * 엉뚱한 건수를 예고했다 — 미리보기가 약속한 것과 실제가 갈리는 R-33 위반이다.
 *
 * 판정을 여기로 내리면 두 경로가 **같은 함수**를 지나고, [ImportWorkbook] 추상 위라
 * DB 없이 순수 JVM 시험이 DOM·스트리밍 양쪽에서 잰다(`SheetResolverTest`).
 * `ExcelImportService`는 DB에 묶여 어떤 로컬 검증도 닿지 않는 자리다 —
 * [com.novelcharacter.app.util.ImportLookupIndex]가 같은 이유로 내려온 전례다.
 *
 * ## 헤더 셀 읽기가 서비스의 `getCellString`과 같은 값인 근거
 *
 * 판정은 전부 **헤더 행**만 읽는다(그 행은 0행이 아닐 수 있다 — [locateHeader] · B-231 ⓑ).
 * 서비스 리더가 얹는 두 갈래는 여기 닿지 않는다 —
 * 병합 피복 보정은 헤더 행을 제외하고(`mergedTopLeftValue`의 첫 줄), 32,767자 절단·집계는
 * 헤더 이름 비교의 결과를 바꿀 수 없다(절단이 일어나는 길이에서 어떤 예약 헤더와도
 * 같아질 수 없다). 정규화는 같은 [ExcelCellValue.normalize] 한 벌이다.
 */
object SheetResolver {

    /** 헤더 행 전용 셀 읽기 — 값 정규화는 [ExcelCellValue](단일 소스)에 위임한다. */
    fun headerString(row: ImportRow, cellIndex: Int): String {
        if (cellIndex < 0) return ""
        val cell = row.getCell(cellIndex) ?: return ""
        return ExcelCellValue.normalize(cell.primitives(), dateHint = false)
    }

    /**
     * 헤더 행을 앞쪽에서 찾는 **창** — 표 위의 제목·메모·빈 줄을 이만큼까지 넘어간다 (B-231 ⓑ).
     *
     * **왜 상한이 있는가:** 창이 없으면 헤더가 없는 시트에서 **끝까지 훑고**, 그 비용이
     * 시트 정체 판정(R-7 — 워크북의 모든 시트를 훑는다)에 곱해진다. 열 개면 사람이 표 위에
     * 얹는 제목·부제·메모·빈 줄을 넉넉히 덮는다.
     */
    const val HEADER_SEARCH_ROWS = 10

    /**
     * 찾아낸 헤더 행과 **그 자리** (B-231 ⓑ).
     *
     * **[dataStart]가 이 타입의 존재 이유다.** 헤더를 3행에서 찾아 놓고 데이터 루프가 1행부터
     * 돌면 제목·메모 행이 **데이터로 읽힌다** — 이름 칸이 '내 캐릭터 목록'인 유령 캐릭터가
     * 생기는 식이다. 자리를 행과 함께 돌려주면 부르는 쪽이 그 둘을 갈라 들 수 없다.
     */
    data class SheetHeader(val row: ImportRow, val index: Int) {
        /** 데이터 루프의 첫 행 — `for (i in header.dataStart..sheet.lastRowNum)`. */
        val dataStart: Int get() = index + 1
    }

    /**
     * 헤더 행을 찾는다 — **0행이 아니어도 된다** (B-231 ⓑ, 사용자 판정 2026.08.18:
     * *"언제나 최고의 기능을 추구하면 돼"* → 원칙 03 · 개발 의도 4번의 *유연한 수용*).
     *
     * 종전에는 `getRow(0)` 하나였다. 사용자가 표 위에 제목·메모 행을 얹으면 그 행이 헤더로
     * 읽혀 **시트가 통째로 거부**됐다(B-231 ⓐ가 그 거부를 *말하게* 만들었고, 여기서 *받아들인다*).
     *
     * **판정은 하나뿐이다: 첫 열이 그 spec의 헤더인 첫 행.** 더 영리하게(겹치는 헤더 수를 세는
     * 식으로) 굴리지 않는 이유는 **오검출이 조용하기 때문**이다 — 잘못 고른 행을 헤더로 삼으면
     * 그 아래가 통째로 어긋나는데 아무도 말하지 않는다. 첫 열 일치는 [isValidHeader]가 이미
     * 시트 정체를 가르는 데 쓰는 판정이라, **새 기준을 만들지 않는다**(R-7과 같은 눈).
     *
     * **오검출 위험이 종전보다 낮다:** 0행이 유효한 헤더면 그 자리에서 멈추므로 종전과 답이
     * 같고, 창이 열리는 것은 **종전에 통째로 거부되던 파일**뿐이다.
     *
     * **두 경로에 같게 걸린다** — [ImportSheet] 추상 위라 DOM·스트리밍이 이 함수 하나를 본다
     * (등재가 *"두 경로·모든 분석기에 같게"*를 조건으로 단 자리다).
     */
    fun locateHeader(sheet: ImportSheet, expectedFirstHeader: String): SheetHeader? {
        // **`lastRowNum`을 묻지 않는다** — 끝을 넘은 자리는 `getRow`가 null을 준다. 스트리밍에서
        // `lastRowNum`은 시트 XML을 한 번 더 훑는 색인 패스라, 정체 판정(모든 시트 × 이 함수)에
        // 그 비용을 곱하게 된다. 창이 상수라 여기서 알아야 할 것도 없다.
        for (i in 0 until HEADER_SEARCH_ROWS) {
            val row = sheet.getRow(i) ?: continue
            if (isValidHeader(row, expectedFirstHeader)) return SheetHeader(row, i)
        }
        return null
    }

    /** 첫 열 헤더가 spec의 것인가 — 별칭 인지 매칭(가져오기·미리보기 공통 판정). */
    fun isValidHeader(headerRow: ImportRow, expectedFirstHeader: String): Boolean {
        val firstCell = headerString(headerRow, 0)
        if (firstCell.isBlank()) return false
        val canonical = ExcelHeaderAliases.canonical(firstCell)
        return canonical == expectedFirstHeader || firstCell == expectedFirstHeader
    }

    /**
     * 캐릭터 시트의 지문 — 첫 열이 '이름'이고 캐릭터 전용 헤더가 하나라도 있는가.
     *
     * '이미지경로'는 세계관 시트에도 있어 제외하고, [CHARACTER_SHEET_FINGERPRINT]의 4개는
     * 세계관/이름 은행/세력/프리셋 어디에도 없는 캐릭터 전용 헤더다. 시트 이름이 겹칠 때
     * 정체를 가르는 단일 판정이므로 세계관 시트 조회와 예약 시트 조회가 **같은 함수**를 봐야 한다.
     */
    fun looksLikeCharacterSheet(sheet: ImportSheet): Boolean {
        // 헤더가 0행이 아닐 수 있다(B-231 ⓑ) — 정체 판정도 **찾아낸 그 행**을 봐야 한다.
        // 여기만 0행에 묶어 두면 제목 행이 얹힌 캐릭터 시트가 지문에 안 걸려, 예약명을
        // 다투는 자리에서 데이터 시트로 넘어간다(B-217이 고친 그 유실의 입구다).
        val header = locateHeader(sheet, "이름")?.row ?: return false
        val distinctive = CHARACTER_SHEET_FINGERPRINT
        val lastCol = header.lastCellNum.toInt()
        for (col in 1 until lastCol) {
            if (headerString(header, col) in distinctive) return true
        }
        return false
    }

    /** 접미사 후보의 판정 — spec 앞쪽 열들이 자리까지 일치할 것을 요구한다(첫 열 하나로는 부족). */
    fun matchesSpecHeader(sheet: ImportSheet, spec: SheetSpec): Boolean {
        val header = locateHeader(sheet, spec.firstColumnHeader)?.row ?: return false
        val lastCol = header.lastCellNum.toInt().coerceAtLeast(0)
        val headers = (0 until lastCol).map { headerString(header, it) }
        return headersMatchSpec(headers, spec)
    }

    /**
     * 예약 시트 해석의 **단일 판정** — 경고를 내지 않는 순수 조회.
     * 삭제 가드(`canRestore`) · 실제 조회(`findSheet`) · 미리보기(`analyze*`)가 같은 결과를 보게 한다.
     *
     * 예약 데이터 시트는 **결코 캐릭터 시트가 아니다.** 첫 열이 '이름'인 spec 6개
     * (세계관·이름 은행·세력·필드 템플릿·검색 프리셋·목록 프리셋)는 [isValidHeader]만으로는
     * 같은 이름의 세계관 캐릭터 시트와 구분되지 않는다. 레거시 백업(캐릭터 시트가 평명을
     * 차지한 파일)에서 이 판정이 그 캐릭터 시트를 데이터 시트로 넘겨줬고, 같은 판정을 쓰는
     * 4-6 삭제 가드까지 통과시켜 **원본을 먼저 지우고 한 건도 복원하지 못했다.**
     */
    fun resolveSpecSheet(workbook: ImportWorkbook, spec: SheetSpec): ImportSheet? {
        val exact = workbook.getSheet(spec.sheetName)
        if (exact != null &&
            locateHeader(exact, spec.firstColumnHeader) != null &&
            !looksLikeCharacterSheet(exact)
        ) {
            return exact
        }
        for (idx in 0 until workbook.numberOfSheets) {
            val name = workbook.getSheetName(idx)
            if (!isSuffixedVariantOf(name, spec.sheetName)) continue
            val candidate = workbook.getSheetAt(idx)
            if (looksLikeCharacterSheet(candidate)) continue
            if (matchesSpecHeader(candidate, spec)) return candidate
        }
        return null
    }

    /**
     * 읽기 경로의 시트 조회 — `findSheet`와 **같은 답**이다(경고·소비 기록만 없다).
     *
     * [resolveSpecSheet]가 실패해도 정확-일치 시트는 그대로 돌려준다 — 헤더가 spec과 다른
     * 시트의 검증·경고는 호출부 몫이다(구버전 헤더·사용자 편집 파일을 관대하게 수용하던
     * 기존 경로의 유지. 가져오기 쪽 `findSheet`가 정확히 이 폴백을 갖는다).
     * 미리보기(`analyze*`)는 전부 이것을 지나야 한다 — `check_restore_preview_parity.sh` ④가 지킨다.
     */
    fun sheetForRead(workbook: ImportWorkbook, spec: SheetSpec): ImportSheet? =
        resolveSpecSheet(workbook, spec) ?: workbook.getSheet(spec.sheetName)
}
