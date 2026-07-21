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
 * 구조적으로 차단한다(로직 비분기). 동작은 기존 getCellString과 1:1 동일하며, 순수 JVM 단위
 * 테스트로 그 동치를 강제한다.
 */
object ExcelCellValue {

    /**
     * 정규화에 필요한, `Cell`과 무관한 원시 입력.
     * @param numericValue NUMERIC/FORMULA(숫자결과) 셀의 값
     * @param javaDate 날짜로 해석할 때 쓸 값 (DateUtil.getJavaDate 결과). null이면 formatDate가 numericValue로 폴백.
     * @param isDateFormatted POI가 날짜 서식으로 판정했는지(DateUtil.isCellDateFormatted 상당)
     * @param dataFormatString 셀 스타일의 표시 서식 문자열(없으면 null)
     */
    data class Primitives(
        val type: CellType,
        // FORMULA에서 null = 숫자결과(stringCellValue 접근이 예외), 비-null(빈문자 포함) = 문자열결과.
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
     */
    fun normalize(p: Primitives, dateHint: Boolean): String {
        return when (p.type) {
            CellType.STRING -> p.stringValue?.trim() ?: ""
            CellType.NUMERIC -> normalizeNumeric(p, dateHint)
            CellType.BOOLEAN -> if (p.booleanValue) "Y" else "N"
            CellType.FORMULA -> {
                // 수식: 문자열 결과(빈문자 포함)면 그 값을 trim해 반환, 숫자 결과(stringValue==null)면 숫자/날짜.
                // getCellString FORMULA 분기와 동일 — stringCellValue 접근이 예외를 던질 때만 숫자로 폴백.
                val s = p.stringValue
                if (s != null) s.trim() else normalizeNumeric(p, dateHint)
            }
            else -> ""
        }
    }

    /** NUMERIC 값 정규화: 날짜면 formatDate, 아니면 정수는 소수점 없이, 그 외 toString. NaN/Inf는 "". */
    private fun normalizeNumeric(p: Primitives, dateHint: Boolean): String {
        if (isLikelyDate(p, dateHint)) return formatDate(p)
        val value = p.numericValue
        return when {
            value.isNaN() || value.isInfinite() -> ""
            value == value.toLong().toDouble() -> value.toLong().toString()
            else -> value.toString()
        }
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
                val cal = Calendar.getInstance().apply { time = date }
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                if (year == 1900 || year == 1904) "%02d-%02d".format(month, day)
                else "%d-%02d-%02d".format(year, month, day)
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
     * FORMULA는 문자열 결과 접근이 예외를 던질 수 있어 안전하게 감싼다.
     */
    fun fromCell(cell: Cell): Primitives {
        val type = cell.cellType
        return when (type) {
            CellType.STRING -> Primitives(type, stringValue = cell.stringCellValue)
            CellType.BOOLEAN -> Primitives(type, booleanValue = cell.booleanCellValue)
            CellType.NUMERIC -> numericPrimitives(cell, type)
            CellType.FORMULA -> {
                // stringCellValue 접근이 예외 없이 반환되면(빈문자·null 포함) 문자열 결과로 취급,
                // 예외를 던지면 숫자 결과로 폴백 — getCellString FORMULA 분기와 동일한 분기 기준.
                var threw = false
                val s: String? = try { cell.stringCellValue } catch (_: Exception) { threw = true; null }
                if (!threw) {
                    Primitives(type, stringValue = s ?: "")
                } else {
                    try { numericPrimitives(cell, type) } catch (_: Exception) { Primitives(type) }
                }
            }
            else -> Primitives(type)
        }
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
