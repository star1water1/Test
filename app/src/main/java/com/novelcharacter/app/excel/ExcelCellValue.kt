package com.novelcharacter.app.excel

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 엑셀 셀 값 정규화의 **단일 소스**.
 *
 * 가져오기의 값 해석 로직(숫자 정수/소수, 날짜 감지·포맷, 불리언 Y/N, 문자열 trim)을
 * `Cell`에 의존하지 않는 순수 함수로 분리한다. DOM 경로([ExcelImportService.getCellString])와
 * 향후 SAX 스트리밍 경로가 **동일한 이 로직**을 태우게 하여, 두 경로 간 값 왜곡(변수 제어 위반)을
 * 구조적으로 차단한다(로직 비분기). 분리 시점 동작은 기존 getCellString과 1:1 동일했고,
 * 이후 B-7이 원문 보존을 강화했다(0 패딩 서식 복원·과학표기 금지 — [normalizeNumeric] 참조).
 * 순수 JVM 단위 테스트가 동작을 고정한다.
 */
object ExcelCellValue {

    /**
     * 정규화에 필요한, `Cell`과 무관한 원시 입력.
     *
     * **[type]은 셀의 껍데기가 아니라 값의 타입이다** — 수식 셀은 캐시 결과의 타입
     * (STRING/NUMERIC/BOOLEAN/ERROR/BLANK)으로 실린다. 스트리밍 SAX는 `<c t>`가 캐시 값의
     * 타입이라 원리적으로 그렇게밖에 못 내는데, DOM([fromCell])이 FORMULA를 그대로 실으면
     * 같은 셀의 원시값 모양이 경로마다 갈린다(B-218 ④가 그 갈림에서 "0" 날조까지 갔다).
     *
     * @param numericValue NUMERIC(수식의 숫자 캐시 포함) 셀의 값
     * @param javaDate 날짜로 해석할 때 쓸 값 (DateUtil.getJavaDate 결과). null이면 formatDate가 numericValue로 폴백.
     * @param isDateFormatted POI가 날짜 서식으로 판정했는지(DateUtil.isCellDateFormatted 상당)
     * @param dataFormatString 셀 스타일의 표시 서식 문자열(없으면 null)
     */
    data class Primitives(
        val type: CellType,
        // STRING(수식의 문자열 캐시 포함)에서만 비-null. 숫자·불리언·오류·빈 셀은 null.
        val stringValue: String? = null,
        val numericValue: Double = 0.0,
        val javaDate: Date? = null,
        val isDateFormatted: Boolean = false,
        val dataFormatString: String? = null,
        val booleanValue: Boolean = false
    )

    /**
     * 원시 입력을 가져오기가 기대하는 표준 문자열로 정규화한다(길이 제한 미적용 — 절단은 호출측 책임).
     * getCellString의 when(cellType) 분기와 동일.
     *
     * **문자열 셀의 앞뒤 공백은 다듬는다** — 외부 편집기가 붙이는 공백을 그대로 받으면
     * `" 붉은 머리 "`가 `"붉은 머리"`와 **다른 값**이 되어 값 라이브러리·태그·매칭이 전부
     * 갈린다. 관대 수용이 이 앱의 규약이므로(개발 의도 4번) 이쪽은 없앨 것이 아니다.
     *
     * **그래서 짝은 쓰는 쪽이 진다:** 저장이 앞뒤 공백을 들이지 않아야 *무편집 왕복이
     * 값을 바꾸지 않는다*(내보내기는 원문을 그대로 쓴다). 편집 화면의 텍스트 칸은 전부
     * `trim()`을 지나며, 마지막까지 밖에 있던 캐릭터 메모도 2026.08.23에 들여왔다
     * (`CharacterSaveCoordinator.buildCharacterFromForm`). **새 텍스트 칸을 만들 때
     * 그 규약을 함께 지킬 것** — 여기서 다듬는 것을 그쪽에서 안 다듬으면 그 필드만
     * 왕복에서 조용히 달라진다.
     */
    fun normalize(p: Primitives, dateHint: Boolean): String {
        return when (p.type) {
            CellType.STRING -> p.stringValue?.trim() ?: ""
            CellType.NUMERIC -> normalizeNumeric(p, dateHint)
            CellType.BOOLEAN -> if (p.booleanValue) "Y" else "N"
            // FORMULA는 여기 오지 않는다 — [fromCell]도 스트리밍도 캐시 값의 타입으로 싣는다
            // (Primitives KDoc). ERROR·BLANK와 함께 값 없는 자리로 접힌다.
            else -> ""
        }
    }

    /** "000" 류 순수 0 정수 서식 — 외부 편집기가 선행 0 값을 숫자+서식으로 바꿔 저장하는 형태 */
    private val ZERO_PAD_FORMAT = Regex("0{2,}")

    /**
     * NUMERIC 값 정규화: NaN/Inf는 "", 날짜면 formatDate, 정수는 소수점 없이, 그 외 평문 십진수.
     *
     * 원문 보존 (B-7):
     * - "000" 류 0 패딩 정수 서식이면 표시 폭만큼 0을 채운다 — 외부 편집기가 "007"을
     *   숫자 7 + 서식 "000"으로 저장한 셀에서 표시 원문("007")을 되살린다.
     * - 비정수·Long 범위 밖 값은 toString의 과학표기("1.0E20") 대신 평문 십진수로 낸다 —
     *   과학표기는 재가져오기 시 다른 값 문자열이 되어 왕복 무결성을 깬다.
     */
    private fun normalizeNumeric(p: Primitives, dateHint: Boolean): String {
        val value = p.numericValue
        // NaN/Infinity는 유효 값이 아니므로 날짜/숫자 해석 이전에 ""로 확정한다.
        // 유한값(실제 셀 100%)에는 무영향 — 날짜 판정 흐름 불변. FORMULA 숫자결과가 NaN/Inf이면서
        // 날짜서식인 극단 케이스에서도 쓰레기 문자열 대신 ""를 내어 무결성을 지킨다(구 formatDateCell 대체).
        if (value.isNaN() || value.isInfinite()) return ""
        if (isLikelyDate(p, dateHint)) return formatDate(p)
        if (value == value.toLong().toDouble()) {
            val long = value.toLong()
            val fmt = p.dataFormatString
            if (fmt != null && ZERO_PAD_FORMAT.matches(fmt)) {
                val digits = kotlin.math.abs(long).toString().padStart(fmt.length, '0')
                return if (long < 0) "-$digits" else digits
            }
            return long.toString()
        }
        // BigDecimal.valueOf는 Double.toString 기준 최단 십진 표현을 쓰므로 이진 오차 자릿수가 붙지 않는다.
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    /**
     * NUMERIC 셀이 날짜인지 판정 — getCellString이 쓰는 [isCellLikelyDate]와 동일한 3단계 로직.
     * 1) POI 날짜 서식 판정  2) 서식 문자열 패턴(CJK 년/월/일 포함)  3) dateHint 시 유효 시리얼 범위.
     */
    fun isLikelyDate(p: Primitives, dateHint: Boolean): Boolean {
        if (p.isDateFormatted) return true
        // getCellString과 동일: 서식 문자열이 없으면 날짜 아님(dateHint여도 false).
        val fmt = p.dataFormatString ?: return false
        if (fmt == "@") return false // 텍스트 서식 — 날짜 아님
        val lower = fmt.lowercase(Locale.ROOT)
        if (fmt != "General") {
            if ((lower.contains("y") && (lower.contains("d") || lower.contains("m"))) ||
                (lower.contains("d") && lower.contains("m") && !lower.contains("h") && !lower.contains("s")) ||
                lower.contains("년") || lower.contains("월") || lower.contains("일")
            ) {
                return true
            }
        }
        // 서식이 있으나 날짜 패턴이 아닐 때만 dateHint 시리얼 범위 판정(getCellString 흐름과 동일).
        return dateHintValid(p, dateHint)
    }

    private fun dateHintValid(p: Primitives, dateHint: Boolean): Boolean {
        if (!dateHint) return false
        val value = p.numericValue
        return value > 0 && value < 2958466 && value == kotlin.math.floor(value)
    }

    /** 날짜로 판정된 값을 MM-DD(엑셀 기준연도 1900/1904) 또는 YYYY-MM-DD로 변환 — formatDateCell과 동일. */
    fun formatDate(p: Primitives): String {
        val date = p.javaDate
        return if (date != null) {
            try {
                val cal = Calendar.getInstance(Locale.US).apply { time = date }
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                if (year == 1900 || year == 1904) String.format(Locale.US, "%02d-%02d", month, day)
                else String.format(Locale.US, "%d-%02d-%02d", year, month, day)
            } catch (_: Exception) {
                numericFallback(p.numericValue)
            }
        } else {
            numericFallback(p.numericValue)
        }
    }

    private fun numericFallback(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    /**
     * 실제 [Cell]에서 정규화 원시값을 추출한다(DOM 경로 어댑터).
     * FORMULA는 캐시 결과 타입으로 갈라 **값의 타입**으로 싣는다(Primitives KDoc — 스트리밍과 동형).
     */
    fun fromCell(cell: Cell): Primitives {
        val type = cell.cellType
        return when (type) {
            CellType.STRING -> Primitives(type, stringValue = cell.stringCellValue)
            CellType.BOOLEAN -> Primitives(type, booleanValue = cell.booleanCellValue)
            CellType.NUMERIC -> numericPrimitives(cell, type)
            CellType.FORMULA -> {
                // 캐시 결과의 값 타입으로 가른다 (B-218 ④). 종전의 "stringCellValue가 예외를
                // 던지는가" 분기는 불리언·오류·빈 캐시를 전부 숫자 폴백으로 보냈고, 접히면
                // numericValue 0.0이 되어 normalize가 "0"을 **날조**했다 — 스트리밍은 같은 셀을
                // Y/N·""로 내므로 두 경로가 갈렸다. 문자열·숫자 캐시의 산출 값은 종전과 동일하다.
                when (cachedValueType(cell)) {
                    CellType.STRING ->
                        Primitives(CellType.STRING, stringValue = (try { cell.stringCellValue } catch (_: Exception) { null }) ?: "")
                    CellType.BOOLEAN ->
                        Primitives(CellType.BOOLEAN, booleanValue = try { cell.booleanCellValue } catch (_: Exception) { false })
                    CellType.ERROR -> Primitives(CellType.ERROR)
                    CellType.NUMERIC ->
                        try { numericPrimitives(cell, CellType.NUMERIC) } catch (_: Exception) { Primitives(CellType.BLANK) }
                    // BLANK(캐시 안 실린 수식) · 판정 실패: 값이 없는 자리이므로 BLANK(→"")다.
                    // 숫자 0으로 지어내지 않는다.
                    else -> Primitives(CellType.BLANK)
                }
            }
            else -> Primitives(type)
        }
    }

    /**
     * 수식 셀 캐시 결과의 **값 타입** — [fromCell]과 [ImportCell.cellType](DOM 구현)이 같은
     * 판정을 쓴다(두 벌이면 갈린다).
     *
     * **`cachedFormulaResultType`만으로는 부족하다:** XSSF는 `t`의 XML 기본값이 n이라
     * **캐시가 아예 안 실린 수식(`<v>` 부재 — 외부 생성기가 평가 없이 쓴 파일)도 NUMERIC**으로
     * 돌려주고, 그대로 숫자를 읽으면 0.0이 나와 "0" 날조가 재현된다. 그 값은 0이 아니라
     * 미지이므로 BLANK다 — 스트리밍(`<v>` 없음 → BLANK_CELL)과 같은 답.
     */
    fun cachedValueType(cell: Cell): CellType {
        val cached = try { cell.cachedFormulaResultType } catch (_: Exception) { return CellType.BLANK }
        if (cached == CellType.NUMERIC &&
            (cell as? org.apache.poi.xssf.usermodel.XSSFCell)?.ctCell?.isSetV() == false
        ) return CellType.BLANK
        return cached
    }

    private fun numericPrimitives(cell: Cell, type: CellType): Primitives {
        val isDateFmt = try { DateUtil.isCellDateFormatted(cell) } catch (_: Exception) { false }
        val fmt = try { cell.cellStyle?.dataFormatString } catch (_: Exception) { null }
        val date = try { cell.dateCellValue } catch (_: Exception) { null }
        return Primitives(
            type = type,
            numericValue = cell.numericCellValue,
            javaDate = date,
            isDateFormatted = isDateFmt,
            dataFormatString = fmt
        )
    }
}
