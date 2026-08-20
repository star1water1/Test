package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.CsvTokens
import com.novelcharacter.app.util.FieldValueTokenizer
import org.apache.poi.ss.usermodel.Row

/**
 * Single source of truth for Excel sheet column definitions.
 * Used by both ExcelExporter and ExcelImporter to ensure format consistency.
 */
data class ColumnSpec(
    val header: String,
    val required: Boolean = false,
    val readOnly: Boolean = false,
    val dropdownOptions: List<String>? = null,
    val width: Int = 5000,
    // ── 표현 속성(시각 개편 2026.08.14 — Q-1~Q-3 사용자 확정) — 스타일만 정하고 값·헤더는 건드리지 않는다 ──
    /** 장문 열: 줄바꿈을 보이게 그린다(wrapText + 세로 상단). 행 높이는 [estimateWrapLines]로 함께 기록한다. */
    val wrap: Boolean = false,
    /** 13자리 epoch millis 열: 숫자 서식 `0` — 값은 그대로 두고 과학표기만 차단한다(B-222 ①). */
    val millis: Boolean = false,
    /** CALCULATED 열: 소수 값에 서식 `0.00` — 앱 표시([FormulaDisplay.format] `%.2f`)와 글자까지 같게 그린다. */
    val calc: Boolean = false
)

data class SheetSpec(
    val sheetName: String,
    val columns: List<ColumnSpec>,
    /**
     * 틀 고정 열 수(시각 개편 V-6) — `createFreezePane(freezeCols, 1)`.
     * 행의 정체를 이루는 왼쪽 열들만 고정한다(캐릭터=이름, 전체 캐릭터=세계관·작품·이름).
     * 0이면 종전과 같이 헤더 행만 고정된다.
     */
    val freezeCols: Int = 0
) {
    val firstColumnHeader: String get() = columns.firstOrNull()?.header ?: ""

    /** Find column index by header name in an actual Excel header row (exact match). */
    fun findColumn(headerRow: Row, headerName: String): Int {
        val lastCol = headerRow.lastCellNum.toInt()
        for (col in 0 until lastCol) {
            val cell = headerRow.getCell(col) ?: continue
            val cellValue = try {
                cell.stringCellValue?.trim()
            } catch (_: Exception) {
                null
            }
            if (cellValue == headerName) return col
        }
        return -1
    }

    /** Find column index using normalized alias matching (tolerant import). */
    fun findColumnTolerant(headerRow: Row, headerName: String): Int {
        // Try exact match first
        val exact = findColumn(headerRow, headerName)
        if (exact >= 0) return exact
        // Try normalized comparison
        val normalized = headerName.trim().lowercase().replace(Regex("[\\s_\\-()（）]"), "")
        val lastCol = headerRow.lastCellNum.toInt()
        for (col in 0 until lastCol) {
            val cell = headerRow.getCell(col) ?: continue
            val cellValue = try {
                cell.stringCellValue?.trim()?.lowercase()?.replace(Regex("[\\s_\\-()（）]"), "")
            } catch (_: Exception) { null }
            if (cellValue == normalized) return col
        }
        return -1
    }
}

/**
 * 캐릭터 시트의 고정(커스텀 필드가 아닌) 열 헤더 — characterSpec과 가져오기 해석이 공유하는 단일 소스.
 * 커스텀 필드명이 이 목록과 겹치면 열 정체가 흔들리므로 내보내기가 필드키를 병기한다.
 */
val CHARACTER_FIXED_HEADERS = setOf(
    "이름", "성", "이름(First)", "이명", "이미지경로", "대표이미지", "작품", "메모", "태그",
    "코드", "작품코드", "정렬순서", "고정", "생성일"
)

/**
 * 커스텀 필드 헤더가 고정 열과 충돌하는지 — 가져오기와 **같은 별칭 규칙**으로 판정한다.
 * ('비고'는 별칭상 '메모'로 접히므로 이름이 달라도 충돌이다)
 */
fun collidesWithFixedHeader(fieldName: String): Boolean =
    ExcelHeaderAliases.canonical(fieldName) in CHARACTER_FIXED_HEADERS ||
        fieldName in CHARACTER_FIXED_HEADERS

/** 커스텀 필드 열 헤더에 병기하는 안정 식별자 형식 — 가져오기가 이 키로 열을 확정한다 */
fun characterFieldHeader(fieldName: String, fieldKey: String, disambiguate: Boolean): String =
    if (disambiguate) "$fieldName($fieldKey)" else fieldName

/** Default border color presets for color picker UI. */
val BORDER_COLOR_PRESETS = listOf(
    "#5C6BC0", "#FF7043", "#26A69A", "#AB47BC",
    "#EF5350", "#42A5F5", "#66BB6A", "#FFA726",
    "#EC407A", "#7E57C2", "#29B6F6", "#D4E157"
)

/** 사용 안내 시트명 — 내보내기·가져오기 공용 상수 */
const val GUIDE_SHEET_NAME = "사용 안내"

/** 세계관에 속하지 않은 캐릭터를 모으는 시트명 — 내보내기·가져오기 공용 상수 */
const val UNCLASSIFIED_SHEET_NAME = "미분류 캐릭터"

/** 전 세계관 캐릭터를 한 장에 모으는 읽기 전용 시트명 (U-12a) — 내보내기·가져오기 공용 상수 */
const val ALL_CHARACTERS_SHEET_NAME = "전체 캐릭터"

/**
 * 긴 드롭다운 목록을 담는 **숨김 시트**명 (B-221) — 내보내기·가져오기 공용 상수.
 *
 * 명시 목록 수식의 엑셀 한도(255자)를 넘는 목록은 이 시트의 한 열에 적고 범위 참조로 건다
 * ([DropdownListLimits]). **가져오기는 읽지 않는다** — 예약명이라 '인식되지 않은 시트' 경고에서도
 * 빠지고, 사용자가 편집할 것도 아니라 숨긴다. 필요한 export에만 생긴다.
 */
const val DROPDOWN_LIST_SHEET_NAME = "목록 데이터"

/** All reserved (non-universe) sheet names used by the app. */
val RESERVED_SHEET_NAMES = setOf(
    GUIDE_SHEET_NAME,
    universeSpec().sheetName,
    novelSpec(emptyList()).sheetName,
    fieldDefinitionSpec(emptyList()).sheetName,
    gradeSystemSpec().sheetName,
    defaultFieldSpec().sheetName,
    UNCLASSIFIED_SHEET_NAME,
    timelineSpec(emptyList()).sheetName,
    stateChangeSpec().sheetName,
    quoteSpec().sheetName,
    relationshipSpec().sheetName,
    relationshipChangeSpec().sheetName,
    nameBankSpec().sheetName,
    factionSpec().sheetName,
    factionMembershipSpec().sheetName,
    factionRelationshipSpec().sheetName,
    userPresetTemplateSpec().sheetName,
    searchPresetSpec().sheetName,
    characterListPresetSpec().sheetName,
    appSettingsSpec().sheetName,
    imageMetaSpec().sheetName,
    fieldValueLibrarySpec().sheetName,
    characterFieldValueSpec().sheetName,
    novelFieldValueSpec().sheetName,
    eventFieldValueSpec().sheetName,
    duelAxisSpec().sheetName,
    duelMatchSpec().sheetName,
    duelVerdictSpec().sheetName,
    ALL_CHARACTERS_SHEET_NAME,
    DROPDOWN_LIST_SHEET_NAME
)

/**
 * 엑셀 시트명 정규화 — **내보내기·가져오기 단일 소스**.
 *
 * POI(5.2.5)의 실제 제약을 반영한다:
 * - 금칙문자 `[ ] * / \ ? :` 제거, 31자 제한
 * - 앞뒤 아포트로피 금지 (`createSheet`가 `IllegalArgumentException`으로 죽는다)
 * - 전부 제거되어 빈 이름이 되면 `Sheet`
 *
 * 가져오기 쪽이 이 함수를 쓰지 않고 같은 정규식을 따로 갖고 있으면 반드시 드리프트한다
 * (7장 규약: 헤더 규칙·유효값을 양쪽에 따로 두지 않는다).
 */
fun sanitizeSheetNameBase(name: String): String {
    val cleaned = name.replace(Regex("[\\[\\]*/\\\\?:]"), "").take(31).trim('\'')
    return if (cleaned.isBlank()) "Sheet" else cleaned
}

/**
 * 워크북 안에서 유일한 시트명을 배정한다 — 4-5 규약을 '호출 순서'가 아니라 '규칙'으로 만든다.
 *
 * 종전에는 예약 시트가 캐릭터 시트보다 **앞줄에서 생성되는지**에 따라 이름을 지켜냈다.
 * 그래서 예약 시트 20개 중 실제로 보호되는 것은 7개뿐이었고, 그중 6개도 "옵션 ON + 데이터
 * 있음"일 때만이었다. 세계관 이름이 '세력'이면 세력 시트가, '이름 은행'이면 이름 은행 시트가
 * 이름을 빼앗겨 가져오기에서 통째로 무시됐다 — 첫 열 헤더가 '이름'인 spec들은 캐릭터 시트를
 * 그대로 통과시키기까지 해서, 4-6 삭제 가드(`canRestore`)도 함께 무력화됐다.
 *
 * 이제 **예약명은 그 소유자만 가질 수 있다.** 세계관 캐릭터 시트는 [ownerOf] 없이 부르므로
 * 어떤 예약명도 차지하지 못하고 `(2)` 접미사로 밀려나며, 가져오기의 접미사 루프가 구제한다.
 * 호출 순서·옵션·데이터 유무와 무관하게 성립한다.
 *
 * POI의 중복 판정이 **대소문자 무시**라는 점도 반영한다 — `myworld` 다음 `MyWorld`는
 * 대소문자를 구분하는 집합으로는 못 걸러 `createSheet`가 예외로 죽는다.
 *
 * @param ownerOf 이 시트가 소유권을 주장하는 예약명(예약 시트 자신). 세계관 시트는 null.
 */
fun assignSheetName(name: String, usedNames: MutableSet<String>, ownerOf: String? = null): String {
    val base = sanitizeSheetNameBase(name)

    fun taken(candidate: String): Boolean {
        if (usedNames.any { it.equals(candidate, ignoreCase = true) }) return true
        val reserved = RESERVED_SHEET_NAMES.any { it.equals(candidate, ignoreCase = true) }
        return reserved && !candidate.equals(ownerOf, ignoreCase = true)
    }

    var result = base
    var counter = 2
    while (taken(result)) {
        val suffix = "($counter)"
        // 접미사를 붙이므로 마지막 글자는 항상 ')'다 — 여기서 아포스트로피를 더 다듬으면
        // 이름이 한 글자 더 짧아져 `isSuffixedVariantOf`가 원명의 변형으로 알아보지 못하고,
        // 가져오기가 밀려난 시트를 영영 못 찾는다.
        result = base.take(31 - suffix.length) + suffix
        counter++
    }
    usedNames.add(result)
    return result
}

/**
 * 캐릭터 시트 전용 헤더 — 시트 이름이 겹칠 때 정체를 가르는 지문.
 *
 * '이미지경로'는 세계관 시트에도 있어 제외한다. 아래 4개는 어떤 예약 데이터 시트에도 없으므로
 * 첫 열이 '이름'으로 같아도 캐릭터 시트를 구분해 낸다. **여기에 항목을 더하거나 예약 spec에
 * 같은 헤더를 새로 넣으면 정상 시트가 거부된다** — `SheetNameAssignmentTest`가 그 충돌을 막는다.
 */
val CHARACTER_SHEET_FINGERPRINT = listOf("이명", "작품", "작품코드", "고정")

/**
 * 헤더 행이 [spec]의 시트인가 — **첫 열 하나로는 판별할 수 없다.**
 *
 * 세계관·이름 은행·세력·필드 템플릿·검색 프리셋·목록 프리셋의 첫 열은 전부 '이름'이라
 * 캐릭터 시트와 구분되지 않는다. 이름이 아니라 헤더가 시트의 정체를 정한다는 규약(R-7)을
 * 지키려면 앞쪽 열들이 **자리까지** 맞아야 한다 — 내보내기가 spec 순서대로 쓰므로
 * 진짜 그 시트라면 반드시 일치하고, 남의 시트는 두 번째 열에서 갈린다.
 */
fun headersMatchSpec(headers: List<String>, spec: SheetSpec): Boolean {
    // 견주는 열은 셋이되, **파일의 머리글이 더 짧으면 있는 만큼 견준다** (2026.08.20).
    // 열을 늘린 시트에서 옛 파일이 통째로 탈락하지 않게 하기 위해서다 — '앱 설정'이
    // 2열 → 4열이 되면서 실제로 그 자리가 생겼다(그 시트가 밀려 `앱 설정(2)`가 된 옛 백업은
    // 이 판정으로만 되찾는다).
    //
    // **다만 둘은 맞아야 한다.** 이 함수의 규약이 *"남의 시트는 두 번째 열에서 갈린다"*이고,
    // 하나로 줄이면 그 보장이 통째로 사라진다.
    val probe = minOf(3, spec.columns.size, headers.size)
    if (probe < minOf(2, spec.columns.size)) return false
    for (col in 0 until probe) {
        if (headers[col].trim() != spec.columns[col].header) return false
    }
    return true
}

/**
 * 시트명이 [base]의 접미사 변형(`이름(2)`)인가 — 가져오기가 밀려난 시트를 되찾는 판정.
 * 31자 절단으로 접미사 앞이 잘린 경우까지 받아들인다.
 */
fun isSuffixedVariantOf(sheetName: String, base: String): Boolean {
    if (sheetName == base) return false
    val stripped = sheetName.replace(Regex("\\(\\d+\\)$"), "")
    if (stripped == sheetName) return false   // 접미사가 없다
    return stripped == base || (base.startsWith(stripped) && sheetName.length >= 31)
}

/**
 * 전각 ASCII(U+FF01–FF5E)를 반각으로 정규화한다. 엑셀에 CJK 입력기로 넣은 전각 쉼표(，)·
 * 전각 영숫자(Ｙ／１ 등)를 관대하게 수용하기 위한 공용 유틸 (F4). CJK 문자(예/참 등)는 그대로 둔다.
 *
 * 정의는 [CsvTokens]에 있다 — 인앱 토큰 규칙(B-178)이 같은 정규화를 쓰는데 `util`은 `excel`을
 * 바라볼 수 없어서다(아키텍처 2장의 의존 방향). 이 이름은 `excel/`의 호출부를 위한 위임이다.
 */
fun toHalfWidth(value: String): String = CsvTokens.toHalfWidth(value)

/**
 * 목록 셀의 **분해** — 내보내기의 [joinCsv]와 한 쌍이다 (B-27 ② · 규약 R-47).
 *
 * **규칙의 정의는 [CsvTokens]에 있다**(2026.08.11, B-178) — 인앱 필드값 토큰([FieldValueTokenizer])이
 * 같은 규칙을 쓰는데 `util`은 `excel`을 바라볼 수 없어서다(아키텍처 2장의 의존 방향).
 * 종전에는 이 파일이 정의를 들고 있었고 인앱 쪽은 예외 없는 `split(",")`이라 **같은 앱 안에서
 * 같은 개념이 두 규칙**이었다. 이 이름은 `excel/`의 호출부(열한 자리)를 위한 위임으로 남긴다.
 *
 * 값 안의 쉼표는 **따옴표로 감싼다**(엑셀·CSV와 같은 방식 — 사용자 확정 2026.08.04,
 * `judgment_confirmations_2026-08.md` 7-6): `"Smith, John", Alice` → [`Smith, John`, `Alice`].
 * 옛 파일을 계속 읽는 경로와 그 한 자리 모호함(`"전체"` → `전체`)은 [CsvTokens]가 든다 —
 * **엑셀 셀은 좁히지 않는다**(CSV 관용 그대로. 좁히는 쪽은 인앱 값이고 사유는 그 문서에 있다).
 */
fun splitCsv(value: String): List<String> = CsvTokens.split(value)

/**
 * 목록 셀의 **결합** — 가져오기의 [splitCsv]와 한 쌍이다 (B-27 ② · 규약 R-47).
 * 값이 구분자를 품고 있으면 감싸고, 안의 따옴표는 겹쳐 쓴다(엑셀·CSV와 같은 방식).
 */
fun joinCsv(values: Collection<String>): String = CsvTokens.join(values)

/**
 * 셀렉터를 받는 짝 — 호출부가 `joinToString(", ") { ... }`로 되돌아가지 않게 한다.
 * **`List`가 아니라 `Collection`을 받는다** — 설정 저장소는 목록을 `Set`으로 들고 있어서
 * `List`로 좁히면 그 자리가 컴파일되지 않는데, 그 파일은 로컬 프로브 범위 밖이라 조용하다.
 */
fun <T> joinCsv(values: Collection<T>, selector: (T) -> String): String =
    CsvTokens.join(values, selector)

/** 한 값의 감싸기 판정 — 정의는 [CsvTokens.quote]다. */
fun quoteCsvToken(value: String): String = CsvTokens.quote(value)

/**
 * '커스텀관계유형' 셀이 JSON 배열이 아닐 때의 관대 해석 — 앱 공통 복수값 규약(쉼표 구분)을 그대로 쓴다.
 * JSON 파싱이 이미 실패한 값에만 적용되므로 유효 입력의 동작을 바꾸지 않는다.
 */
fun parseRelTypeTokens(raw: String): List<String> =
    splitCsv(raw).map { it.trim().trim('"') }.filter { it.isNotBlank() }.distinct()

/**
 * '커스텀관계색상' 셀이 JSON 객체가 아닐 때의 관대 해석 — '유형=#색상' 또는 '유형:#색상'을
 * 쉼표로 나열한 형태를 수용한다. 중괄호를 빠뜨린 JSON 조각도 따옴표를 벗겨 같은 규칙으로 걸린다.
 */
fun parseRelColorTokens(raw: String): List<Pair<String, String>> =
    splitCsv(raw).mapNotNull { token ->
        val sep = token.indexOfFirst { it == '=' || it == ':' }
        if (sep <= 0) return@mapNotNull null
        val k = token.substring(0, sep).trim().trim('"').trim('{').trim()
        val v = token.substring(sep + 1).trim().trim('"').trim('}').trim().trim('"')
        if (k.isBlank() || v.isBlank()) null else k to v
    }.distinctBy { it.first }

/**
 * 드롭다운(허용값) 열의 관대한 값 해석 (순수 함수 — 단위 테스트 대상).
 *
 * 표기 차이(앞뒤 공백·대소문자·전각 영숫자)만 흡수하고 **뜻이 다른 값은 해석하지 않는다**.
 * 반환값은 항상 [allowed]의 표준 표기라 저장값이 파일 표기에 오염되지 않는다.
 * 해석 실패는 null — 호출측이 경고 + 교정 경로를 안내한다(무음 폐기·무음 수용 모두 금지).
 * 빈 문자열은 '미지정'이므로 호출 전에 걸러야 한다.
 */
fun matchDropdownValue(raw: String, allowed: Collection<String>): String? {
    val normalized = toHalfWidth(raw).trim().lowercase()
    if (normalized.isEmpty()) return null
    return allowed.firstOrNull { toHalfWidth(it).trim().lowercase() == normalized }
}

/**
 * 엑셀 불리언 열 파싱 — 몸통은 [CsvTokens.parseBoolean]에 있다(위 [toHalfWidth]와 같은 꼴).
 *
 * **여기 다시 적지 않는 이유:** 이 파일은 POI를 import 해서 순수 JVM 시험이 닿지 못하는데,
 * 복원 미리보기의 '같은 값인가' 판정([AppSettingsDiff])이 같은 해석을 써야 한다(R-33).
 * 두 벌로 적으면 가져오기와 미리보기가 다른 값을 참이라 부르는 날이 온다.
 */
fun parseSheetBoolean(value: String): Boolean = CsvTokens.parseBoolean(value)

/**
 * F1-A 불리언 열 규약의 단일 소스.
 *
 * @return null = **열 자체가 없음** → 호출측은 `?: existing.x`(갱신) / `?: 엔티티기본값`(신규).
 *         비-null = 열이 있음 → 빈칸을 포함한 셀 값의 해석 결과.
 *
 * 빈칸을 null로 돌려주는 변형을 만들지 말 것 — 그 순간 '비움 의도'가 사라져
 * 같은 이름의 드롭다운 열이 시트에 따라 반대로 동작한다.
 */
fun sheetBooleanOrKeep(columnPresent: Boolean, cellText: String): Boolean? =
    if (!columnPresent) null else parseSheetBoolean(cellText)

/**
 * XLSX 셀 텍스트 규격 한도. 내보내기 절단과 가져오기 저장 한도가 반드시 같은 값을 참조해야
 * 왕복(내보내기→들여오기)에서 데이터가 추가로 잘리지 않는다 — 단일 소스로 여기서만 정의한다.
 */
const val EXCEL_CELL_TEXT_LIMIT = 32767

/**
 * [limit] 한도로 자른다 — **서러게이트 쌍 경계를 지킨다** (순수 함수 — 단위 테스트 대상).
 * `String.take`는 UTF-16 유닛 단위라 한도 경계에 보충 평면 문자(이모지 등)가 걸리면 짝 잃은
 * 반쪽 서러게이트가 마지막 글자로 남는다. 한도 안이면 원문 그대로다 — 내보내기 절단과
 * 가져오기 저장 한도가 같은 함수를 써야 경계 처리도 함께 움직인다.
 */
fun truncateForCell(value: String, limit: Int = EXCEL_CELL_TEXT_LIMIT): String {
    if (value.length <= limit) return value
    val cut = value.take(limit)
    return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

// ── 시각 개편(2026.08.14 사용자 확정 Q-1~Q-3) — 표현 계층의 순수 판정들 ──

/**
 * NUMBER 필드값을 숫자 셀로 실어도 되는가 — 되면 그 수를, 아니면 null (Q-1 ⓐ).
 *
 * 조건은 **왕복 멱등성**이다: 가져오기의 숫자 정규화([ExcelCellValue.normalizeNumeric])가 이 수를
 * 도로 문자열로 만들었을 때 **저장 원문과 글자까지 같아야** 한다. 다르면 무편집 왕복에서 저장
 * 값이 조용히 바뀐다 — `"24.50"`→24.5→`"24.5"`, `"007"`→7, `"1e5"`→100000이 그 부류라 전부
 * 문자열 셀로 남긴다. 견주는 두 표현(정수는 `Long.toString`, 소수는
 * `BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()`)은 normalizeNumeric이 쓰는 바로
 * 그것이라, 그쪽 규칙이 바뀌면 이 판정도 같은 자리에서 함께 틀어지는 대신 시험이 잡는다.
 *
 * ⚠️ 호출측 단서 하나: [SemanticRole.BIRTH_DATE] 필드에는 쓰지 말 것 — 가져오기가 그 열만
 * `dateHint=true`로 읽어, 서식 없는 정수 숫자 셀(1~2958465)을 날짜로 해석한다
 * (`ExcelImportService`의 `isDateField`). 판정은 내보내기 쪽 호출부가 같은
 * `SemanticRole.fromConfig` 하나로 한다.
 */
fun numericExportValueOrNull(raw: String): Double? {
    if (raw.isEmpty() || raw != raw.trim()) return null
    val d = raw.toDoubleOrNull() ?: return null
    if (!d.isFinite()) return null
    val roundTrip = if (d == d.toLong().toDouble()) {
        d.toLong().toString()
    } else {
        java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
    }
    return if (roundTrip == raw) d else null
}

/**
 * wrap 행 높이의 상한(줄) — 접힌 데이터를 보이게 하되(원칙 04) 초장문 메모가 화면을
 * 통째로 차지하지는 않게 한다. 넘친 줄은 셀을 열면 그대로 있다(값 불변).
 */
const val WRAP_MAX_LINES = 4

/**
 * wrap 열의 표시 줄수 추정(V-4 구현 노트) — **엑셀은 파일을 열 때 행 높이를 재계산하지 않는다.**
 * 명시 높이가 없으면 기본 15pt 한 줄로 그려 wrap이 있어도 첫 줄만 보인다(LibreOffice·구글시트는
 * 재계산하므로 렌더 검증에서는 드러나지 않는 함정). 그래서 내보내기가 줄수를 추정해 행 높이를
 * 함께 기록한다 — 셀 서식과 같은 자리(행을 쓰는 자리, R-49)에서.
 *
 * 근사 규칙: 한글·CJK·전각(U+1100 이상)은 표준 폭 2칸, 그 외 1칸. 열 폭은 1/256 문자 단위.
 * 정밀 측정이 아니라 "여러 줄임이 보이는가"의 추정이라 이 정도로 충분하다.
 */
fun estimateWrapLines(text: String, columnWidthUnits: Int): Int {
    if (text.isEmpty()) return 1
    val charsPerLine = (columnWidthUnits / 256.0).coerceAtLeast(4.0)
    var lines = 0
    for (seg in text.splitToSequence('\n')) {
        var units = 0.0
        for (ch in seg) units += if (ch.code >= 0x1100) 2.0 else 1.0
        lines += maxOf(1, kotlin.math.ceil(units / charsPerLine).toInt())
        if (lines >= WRAP_MAX_LINES) return WRAP_MAX_LINES
    }
    return lines
}

/**
 * 커스텀 필드 열의 타입별 기본 너비(V-8) — 시스템 열은 손튜닝인데 사용자가 만든 필드만
 * 일률 5000이라 쉼표 목록·접미사 붙은 헤더가 잘렸다. 쉼표 목록은 넓게, 등급·숫자처럼
 * 짧은 값은 좁게. 모르는 타입(null 포함)은 자유 입력으로 보고 넉넉히 둔다.
 */
fun customFieldColumnWidth(type: FieldType?, multiToken: Boolean): Int = when {
    multiToken -> 8000
    type == FieldType.NUMBER || type == FieldType.GRADE -> 4000
    type == FieldType.SELECT || type == FieldType.CALCULATED || type == FieldType.BODY_SIZE -> 4500
    else -> 6000
}

/**
 * 내보내는 워크북 전체의 글꼴 (P-10 · 2026.08.17 사용자 지시 *"엑셀파일의 폰트를 고딕으로"*).
 *
 * ## 왜 이 자리에 상수 하나인가
 *
 * 시각 개편(`excel_visual_design_review_2026-08.md`)은 **폰트 축을 손대지 않았다** — 그 문서가
 * 스스로 *"색·폰트·열 너비·틀 고정·정렬·셀 서식은 어느 문서에도 결정이 없다"*고 적어 두고
 * P-1~P-9로 나머지만 정했다. 그래서 지금까지 글꼴은 **POI 기본값(Calibri)**이었고, Calibri에는
 * 한글 글자체가 없어 **한글은 뷰어가 고른 대체 글꼴로, 숫자·영문만 Calibri로** 그려졌다 —
 * 한 셀 안에서 두 글꼴이 섞이는 모양이다. 이름을 못박으면 그 섞임이 없어진다.
 *
 * `맑은 고딕`은 한글 고딕(산세리프)의 사실상 표준이다. **다른 고딕으로 바꾸려면 이 줄 하나만
 * 고치면 된다** — 워크북의 모든 셀이 [applyExportBaseFont]와 [createExportFont] 둘 중 하나를
 * 지나기 때문이다. 앱 전체에서 폰트·스타일을 만드는 자리가 `ExcelExporter.ExcelStyles`
 * 하나뿐인 것은 실측으로 확인했고(`createFont`·`createCellStyle` 전수 검색 — 다른 파일 0건),
 * 그 뒤로는 `tools/check_export_font.sh`가 기계로 지킨다(사본이 생기면 빨간불이다).
 *
 * ## 왕복에 관여하지 않는다
 *
 * 가져오기는 **셀의 값만** 읽고 글꼴은 보지 않는다 — 스타일에서 들여다보는 것은
 * [ExcelCellValue.Primitives]의 `dataFormatString`(표시 서식) 하나뿐이고, 글꼴 이름은 그 접근면에
 * 아예 없다. 그래서 이 값을 바꿔도 교환 형식·왕복 멱등성이 갈리지 않는다(시각 개편 5-0의
 * "스타일 레이어만 만진다"와 같은 갈래).
 *
 * ## ⚠️ 그러나 **레이아웃은 이 값에 묶여 있다** (콜드 검토 2026.08.17)
 *
 * *"스타일 레이어만 만진다"*를 **레이아웃도 안 움직인다**로 읽으면 틀린다. OOXML에서
 * **열 너비의 단위가 '기본 글꼴의 최대 숫자 폭'**이고 행 높이는 그리는 글꼴에 견주는 값이라,
 * 이 상수를 바꾸면 **저장된 숫자는 그대로인 채 그 숫자의 뜻이 바뀐다.**
 *
 * 실측(POI 5.3.0): `setColumnWidth(5000)`은 글꼴과 무관하게 **5000 그대로 저장되고**,
 * `defaultRowHeightInPoints`도 **15.0 그대로**다. 즉 POI는 환산해 주지 않는다 —
 * 환산은 여는 쪽이 하고, 그 기준이 방금 바뀐 것이다.
 *
 * 걸리는 자리 둘: [ColumnSpec.width](손튜닝된 값들 — 이름 6000·메모 10000·설명 15000)와
 * `finishDataRow`의 `lines * defaultRowHeightInPoints`([estimateWrapLines]가 준 줄 수).
 *
 * **다만 한글 몫은 대체로 그대로다** — 한글 글자는 이 변경 전에도 Calibri에 없어
 * *뷰어가 고른 한글 글꼴*이 그렸다. 새로 움직이는 것은 숫자·영문 쪽 기준이고,
 * 열 너비 단위가 하필 **최대 *숫자* 폭**이라 그 축이 직접 걸린다.
 *
 * **크기는 로컬에서 잴 수 없다**(글꼴 파일이 있어야 한다). 실기기 확인 항목은
 * `remaining_work_2026-07.md` **3-121 ㅇ**이고, 거기서 *헤더가 잘리는가 · wrap 여러 줄이
 * 여전히 보이는가*를 본다 — 후자는 시각 개편 V-4가 고친 그 결함의 자리다.
 *
 * 없는 글꼴은 뷰어가 대체한다(맥·안드로이드·구글시트). 그것은 **지금도 일어나는 일**이라
 * 이 변경이 새로 들여오는 위험이 아니다 — 오히려 대체의 출발점이 한글 글꼴이 된다.
 */
const val EXPORT_FONT_NAME = "맑은 고딕"

/**
 * 워크북의 **기본 글꼴**(폰트 0번)을 제자리에서 [EXPORT_FONT_NAME]으로 못박는다.
 *
 * 글꼴을 거는 자리는 둘이고 **한쪽만으로는 워크북이 덮이지 않는다** — 이 함수가 그중 하나다.
 * 아래 [createExportFont]만 쓰면 헤더·읽기전용·안내 제목까지만 덮이고 **평범한 데이터 셀이
 * 남는다**(`ExcelStyles.dataStyle`의 비-읽기전용 갈래와 `guideBody`는 `setFont`를 부르지 않는다).
 *
 * 실측(POI 5.3.0, DOM·스트리밍 양쪽 — `ExportPresentationSpecTest`가 잠근다): ⓐ 스타일이 아예
 * 없는 셀과 ⓑ 폰트를 걸지 않은 스타일의 셀이 **둘 다 폰트 0번으로 떨어지고**, ⓒ 이 제자리
 * 수정이 파일에 써서 되읽는 왕복에서 살아남는다.
 *
 * 갓 만든 워크북에는 폰트 0번이 반드시 있다(POI가 기본 스타일과 함께 세운다).
 * 빌린 워크북을 받는 자리가 생기면 그 전제가 깨진다.
 */
fun applyExportBaseFont(workbook: org.apache.poi.ss.usermodel.Workbook) {
    workbook.getFontAt(0).fontName = EXPORT_FONT_NAME
}

/**
 * 글꼴을 건 **새 폰트** — 글꼴을 거는 나머지 한 자리다([applyExportBaseFont]가 다른 하나).
 *
 * `workbook.createFont()`를 직접 부르면 안 된다: POI는 **매번 새 폰트를 Calibri로 세워
 * 돌려주므로**(실측) 기본 폰트를 고쳐 두어도 그 폰트에는 미치지 않는다. 굵기·크기·색은
 * 부르는 쪽이 얹는다.
 */
fun createExportFont(workbook: org.apache.poi.ss.usermodel.Workbook): org.apache.poi.ss.usermodel.Font =
    workbook.createFont().apply { fontName = EXPORT_FONT_NAME }

/**
 * 데이터 셀 스타일의 종류 — 열 명세와 값 성질(소수 CALCULATED)로 정해지는 순수 판정.
 * 행 홀짝(밴딩)은 여기 없다 — 그것은 시트 단위 결정과 rowNum으로 [ExcelExporter]가 얹는다.
 *
 * 우선순위가 곧 규칙이다: **읽기전용은 바탕·글자를 정할 뿐 서식을 가리지 않는다** — 밀리초 `0`과
 * CALCULATED 소수 `0.00`은 읽기전용 열에서도 붙는다. 처음 시행은 이 분기가 사적 클래스 안에 있어
 * 시험이 못 닿았고, 전 열이 읽기전용인 '전체 캐릭터'의 CALCULATED 소수만 캐릭터 시트("78.50")·앱과
 * 다른 글자("78.5")로 보였다 — 우선순위는 `ExportPresentationSpecTest`가 잠근다.
 */
enum class CellStyleKind {
    READ_ONLY_MILLIS, READ_ONLY_CALC_DECIMAL, READ_ONLY_WRAP, READ_ONLY,
    CALC_DECIMAL, MILLIS, WRAP, PLAIN
}

fun cellStyleKindFor(col: ColumnSpec, fractionalCalc: Boolean): CellStyleKind = when {
    col.readOnly && col.millis -> CellStyleKind.READ_ONLY_MILLIS
    col.readOnly && fractionalCalc -> CellStyleKind.READ_ONLY_CALC_DECIMAL
    // **읽기 전용이 wrap을 가리면 안 된다** (2026.08.20) — 종전에는 `col.readOnly`가 먼저
    // 걸려 `wrap`이 통째로 죽었다. 그때는 겹치는 열이 하나도 없어 드러나지 않았는데,
    // '앱 설정'의 `설명`·`입력 가능한 값`이 둘 다인 첫 열이다. 그 두 칸은 **여러 줄 안내가
    // 본체**라, wrap이 죽으면 행 높이만 늘고 글은 첫 줄에서 잘려 기능이 통째로 무의미해진다.
    col.readOnly && col.wrap -> CellStyleKind.READ_ONLY_WRAP
    col.readOnly -> CellStyleKind.READ_ONLY
    fractionalCalc -> CellStyleKind.CALC_DECIMAL
    col.millis -> CellStyleKind.MILLIS
    col.wrap -> CellStyleKind.WRAP
    else -> CellStyleKind.PLAIN
}

/**
 * 탭 색 그룹(P-8) — 20여 개 탭을 다섯 구획으로 가른다. 시트 수준 속성이라 값·왕복과 무관하고,
 * 가져오기는 탭 색을 읽지 않는다.
 *
 * **모든 예약 시트는 어느 한 그룹에 명시로 속해야 한다** — 새 예약 시트를 만들면
 * `ExportPresentationSpecTest`의 전수 분할 시험이 그룹 배정을 요구하며 깨진다(등재 누락이
 * 침묵이 되지 않게).
 * 예약명 밖(세계관 캐릭터 시트, `(2)` 접미사 변형)은 전부 캐릭터색이다.
 */
object SheetTabColors {
    val GUIDE = byteArrayOf(0x28, 0x35, 0x93.toByte())
    val STRUCTURE = byteArrayOf(0x39, 0x49, 0xAB.toByte())
    val CHARACTERS = byteArrayOf(0x26, 0xA6.toByte(), 0x9A.toByte())
    val RECORDS = byteArrayOf(0xEF.toByte(), 0x6C, 0x00)
    val TOOLS = byteArrayOf(0x75, 0x7F, 0x8C.toByte())
    val DERIVED = byteArrayOf(0x54, 0x6E, 0x7A)

    /** 구조 — 참조되는 정의들(세계관·작품·필드 계열). 가져오기 순서도 이쪽이 앞이다. */
    val STRUCTURE_SHEETS = setOf(
        universeSpec().sheetName, novelSpec(emptyList()).sheetName, gradeSystemSpec().sheetName,
        defaultFieldSpec().sheetName, fieldDefinitionSpec(emptyList()).sheetName,
        fieldValueLibrarySpec().sheetName
    )

    /** 기록 — 사건·관계·대결처럼 시간·관계를 적는 시트. */
    val RECORD_SHEETS = setOf(
        timelineSpec(emptyList()).sheetName, stateChangeSpec().sheetName, quoteSpec().sheetName,
        relationshipSpec().sheetName,
        relationshipChangeSpec().sheetName, factionSpec().sheetName, factionMembershipSpec().sheetName,
        factionRelationshipSpec().sheetName, duelAxisSpec().sheetName, duelMatchSpec().sheetName,
        duelVerdictSpec().sheetName
    )

    /** 도구·설정 — 프리셋·이름 은행·앱 설정·이미지 메타. */
    val TOOL_SHEETS = setOf(
        nameBankSpec().sheetName, userPresetTemplateSpec().sheetName, searchPresetSpec().sheetName,
        characterListPresetSpec().sheetName, appSettingsSpec().sheetName, imageMetaSpec().sheetName
    )

    /**
     * 파생·오버플로 — 읽기 전용 집계와 잔여값 보관처.
     *
     * 드롭다운 목록 시트(B-221)도 여기다 — 데이터에서 파생된 읽기 전용 보관처이고
     * 가져오기가 읽지 않는다. **탭 색은 실제로 보이지 않지만**(숨김 시트) 이 표는
     * *예약명 전수가 한 그룹에 속하는가*를 잠그는 자리라 배정이 있어야 한다
     * (`ExportPresentationSpecTest` — 새 예약 시트가 등재 없이 빠지는 것을 막는 그물).
     */
    val DERIVED_SHEETS = setOf(
        ALL_CHARACTERS_SHEET_NAME, characterFieldValueSpec().sheetName,
        novelFieldValueSpec().sheetName, eventFieldValueSpec().sheetName,
        DROPDOWN_LIST_SHEET_NAME
    )

    /** 예약명 중 캐릭터 그룹 — 나머지 캐릭터 시트는 예약명 밖이라 자동으로 이 색이다. */
    val CHARACTER_SHEETS = setOf(UNCLASSIFIED_SHEET_NAME)

    fun forSheet(sheetName: String): ByteArray = when (sheetName) {
        GUIDE_SHEET_NAME -> GUIDE
        in STRUCTURE_SHEETS -> STRUCTURE
        in RECORD_SHEETS -> RECORDS
        in TOOL_SHEETS -> TOOLS
        in DERIVED_SHEETS -> DERIVED
        else -> CHARACTERS  // 세계관 캐릭터 시트 · 미분류 · 접미사 변형
    }
}

// ── Sheet Spec factories ──

fun universeSpec() = SheetSpec(
    sheetName = "세계관",
    freezeCols = 1,
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("설명", width = 15000, wrap = true),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("테두리색", width = 4000),
        ColumnSpec("테두리두께", width = 3000),
        ColumnSpec("이미지경로", width = 8000),
        ColumnSpec("이미지모드", dropdownOptions = listOf("none", "custom", "random_character", "select_character", "random_novel", "select_novel"), width = 5000),
        ColumnSpec("커스텀관계유형", width = 10000),
        ColumnSpec("커스텀관계색상", width = 10000),
        ColumnSpec("이미지캐릭터코드", width = 5000),
        ColumnSpec("이미지작품코드", width = 5000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

/**
 * 작품 시트. [novelFieldHeaders]는 **작품 커스텀 필드**(확-3)의 동적 열이며 규칙은
 * [EntityFieldHeaders]가 단일 소스다 — 연표 시트의 사건 필드 열과 같은 규칙을 쓴다.
 */
fun novelSpec(
    universeNames: List<String>,
    novelFieldHeaders: List<String> = emptyList()
) = SheetSpec(
    sheetName = "작품",
    freezeCols = 1,
    columns = listOf(
        ColumnSpec("제목", required = true, width = 8000),
        ColumnSpec("설명", width = 15000, wrap = true),
        ColumnSpec("세계관", dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 8000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("테두리색", width = 4000),
        ColumnSpec("테두리두께", width = 3000),
        ColumnSpec("이미지경로", width = 8000),
        ColumnSpec("이미지모드", dropdownOptions = listOf("none", "custom", "random_character", "select_character"), width = 5000),
        ColumnSpec("이미지캐릭터코드", width = 5000),
        ColumnSpec("테두리상속", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("고정", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("표준연도", width = 3000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    ) + novelFieldHeaders.map { ColumnSpec(it, width = 6000) }  // 작품 커스텀 필드 (확-3)
)

fun fieldDefinitionSpec(
    universeNames: List<String>,
    gradeSystemNames: List<String> = emptyList()
) = SheetSpec(
    sheetName = "필드 정의",
    freezeCols = 3,
    columns = listOf(
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", required = true, width = 5000),
        // 목록을 손으로 적지 않는다 (B-55) — 적으면 새 타입이 **엑셀에서만 못 고르는** 타입이 된다.
        // 앱에서는 만들 수 있는데 파일로는 표현이 안 되는 상태이고, 그것이 왕복 무결성이 깨지는 모양이다.
        ColumnSpec("타입", required = true, dropdownOptions = FieldType.entries.map { it.name }, width = 4000),
        ColumnSpec("설정(JSON)", width = 10000),
        ColumnSpec("그룹", width = 5000),
        ColumnSpec("순서", width = 3000),
        ColumnSpec("필수여부", dropdownOptions = listOf("Y", "N"), width = 4000),
        // config 파생 전용 열(A-1·A-2) — 사람이 고치는 값이라 JSON 셀에 뭉치지 않고 열을 판다.
        // 내보내기는 설정(JSON) 셀에서 두 키를 제거하고 여기에만 싣는다(FieldConfigColumns).
        ColumnSpec(FieldConfigColumns.COLUMN_AI_SUGGEST, dropdownOptions = FieldConfigColumns.AI_CELL_OPTIONS, width = 3500),
        ColumnSpec(FieldConfigColumns.COLUMN_DESCRIPTION, width = 12000, wrap = true),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        // 캐릭터/사건/작품 필드 구분 — 이 열이 없던 구버전 파일은 캐릭터로 간주(관대 수용).
        // 모든 종류의 정의가 왕복되어야 신규 기기 복원 시 그 종류의 필드값이 유실되지 않는다.
        // 목록은 FieldValueSheetMapper가 단일 소스다 — 라벨을 두 벌 두면 반드시 갈린다.
        ColumnSpec("대상", dropdownOptions = FieldValueSheetMapper.ENTITY_LABELS, width = 3500),
        // GRADE 필드의 등급 체계 참조(U-1) — 같은 세계관 '등급 체계' 시트의 체계명.
        // config JSON의 참조 키는 내보내기에서 제거되고 이 열이 그 자리다(같은 사실 두 벌 금지).
        // 해석은 '등급체계코드' 우선, 없으면 (세계관, 이름) — 다른 참조 열들과 같은 규약이다.
        ColumnSpec("등급체계", dropdownOptions = gradeSystemNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("등급체계코드", readOnly = true, width = 4000),
        // 전역 기본 필드 연결(B-119) — '기본 필드' 시트의 '코드'다. 빈 칸 = 연결 없음.
        //
        // ⚠️ **이 열은 없으면 건드리지 않는다**(R-36). B-119 이전에 내보낸 모든 파일이
        // *"이 열이 없는 파일"*이라, 없는 열을 빈 칸("해제하라")으로 읽으면 **다시 들이는
        // 것만으로 전 세계관의 기본 필드 연결이 지워진다.** 판별은 `cols.containsKey`이며
        // 해석은 [refColumnIntent]가 단일 소스다(이름 열 없이 코드 열만 있는 형태).
        //
        // readOnly로 두지 않는 이유: 엑셀에서 필드를 무더기로 만들 때 이 칸에 코드를 적어
        // 한 번에 연결하는 것이 실사용 가치가 있다('출처' 열과 같은 판단).
        ColumnSpec("기본필드코드", width = 4000)
    )
)

/**
 * **전역 기본 필드 템플릿** 시트 (B-119 — 설계 1-5).
 *
 * `필드 정의` 시트와 **같은 열에서 `세계관`을 뺀 것** + `코드`다. 전역이라 소속이 없고,
 * 그 하나가 이 시트가 따로 있는 이유 전부다 — 세계관 열에 무엇을 적어도 뜻이 없다.
 *
 * **가져오기 순서는 이 시트 → `필드 정의`**다(설계 1-5). `필드 정의`의 `기본필드코드`가
 * 여기서 만든 템플릿을 찾아야 하므로, 뒤에 두면 신규 기기 복원(빈 DB)에서 같은 파일 안에
 * 템플릿이 실려 있는데도 연결이 전부 강등된다 — 등급 체계가 필드 정의보다 앞인 것과
 * **같은 근거**이며, 확-3이 *"순서가 열보다 위험하다"*로 남긴 교훈이다.
 *
 * 첫 열이 '이름'이 아니라 캐릭터 시트 지문(`looksLikeCharacterSheet`)에 걸리지 않고,
 * 예약명이라 세계관 시트에 자리를 빼앗기지 않는다 — '필드 정의' 시트와 같은 두 겹 방어다.
 */
fun defaultFieldSpec() = SheetSpec(
    sheetName = "기본 필드",
    freezeCols = 2,
    columns = listOf(
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", required = true, width = 5000),
        // 목록을 손으로 적지 않는다 (B-55) — 적으면 새 타입이 **엑셀에서만 못 고르는** 타입이 된다.
        // 앱에서는 만들 수 있는데 파일로는 표현이 안 되는 상태이고, 그것이 왕복 무결성이 깨지는 모양이다.
        ColumnSpec("타입", required = true, dropdownOptions = FieldType.entries.map { it.name }, width = 4000),
        ColumnSpec("설정(JSON)", width = 10000),
        ColumnSpec("그룹", width = 5000),
        ColumnSpec("순서", width = 3000),
        ColumnSpec("필수여부", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec(FieldConfigColumns.COLUMN_AI_SUGGEST, dropdownOptions = FieldConfigColumns.AI_CELL_OPTIONS, width = 3500),
        ColumnSpec(FieldConfigColumns.COLUMN_DESCRIPTION, width = 12000, wrap = true),
        ColumnSpec("대상", dropdownOptions = FieldValueSheetMapper.ENTITY_LABELS, width = 3500),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

/**
 * 세계관 등급 체계 (U-1) — **한 행 = 등급 하나**. '필드 데이터' 시트가 값 하나를 한 행으로
 * 다루는 것과 같은 형태라, 엑셀에서 필터·정렬·일괄 편집이 그대로 된다.
 *
 * 체계의 정체성은 '코드' 우선, 없으면 (세계관, 체계명)이다. 등급 순서 열이 없는 이유:
 * 앱의 등급 순서는 어디서나 숫자 오름차순으로 파생되므로(GradeTable·FieldOptionParser)
 * 순서를 따로 실으면 두 사실이 갈릴 자리만 생긴다.
 *
 * 첫 열이 '이름'이 아니라 캐릭터 시트 지문(looksLikeCharacterSheet)에 걸리지 않고,
 * 예약명이라 세계관 시트에 자리를 빼앗기지 않는다 — '필드 정의' 시트와 같은 두 겹 방어다.
 */
fun gradeSystemSpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "등급 체계",
    freezeCols = 2,
    columns = listOf(
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("체계명", required = true, width = 6000),
        ColumnSpec("등급", required = true, width = 4000),
        ColumnSpec("기본숫자", required = true, width = 4000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("코드", readOnly = true, width = 4000)
    )
)

/** 필드 데이터 라이브러리 — 값 카탈로그 왕복 (별칭·표시라벨·카테고리·설명이 외부 편집 가능) */
fun fieldValueLibrarySpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "필드 데이터",
    freezeCols = 2,
    columns = listOf(
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", readOnly = true, width = 5000),
        ColumnSpec("대상", dropdownOptions = FieldValueSheetMapper.ENTITY_LABELS, width = 3500),
        ColumnSpec("값", required = true, width = 6000),
        ColumnSpec("표시라벨", width = 5000),
        ColumnSpec(FieldValueSheetMapper.ALIAS_HEADER, width = 8000),
        ColumnSpec("카테고리", width = 5000),
        ColumnSpec("설명", width = 8000, wrap = true),
        ColumnSpec("숨김", dropdownOptions = listOf("Y", "N"), width = 3000),
        // 출처: AUTO(수확)·MANUAL(직접/큐레이션)·IMPORT(엑셀)·AI(AI 정리).
        // 복원이 이 값을 재현하지 못하면 '미사용 자동수집 정리'(source='AUTO' 필터)가 영구히 0건이 된다.
        // readOnly로 두지 않는 이유: 특정 값을 MANUAL로 올려 정리 대상에서 빼는 것은 실사용 가치가 있다.
        ColumnSpec("출처", dropdownOptions = listOf("AUTO", "MANUAL", "IMPORT", "AI"), width = 3500),
        ColumnSpec("사용횟수", readOnly = true, width = 3500),
        ColumnSpec("코드", readOnly = true, width = 4000)
    )
)

/**
 * 캐릭터 필드값 오버플로 — 캐릭터 시트가 **열로 표현할 수 없는** 필드값을 담는다.
 *
 * 캐릭터 시트는 그 시트의 세계관에 속한 필드만 열로 만든다. 따라서 (가) 미분류 캐릭터(세계관이
 * 없어 필드 열 자체가 없다), (나) 자기 세계관 소속이 아닌 필드 정의를 참조하는 잔여 값은
 * 캐릭터 시트로는 왕복할 수 없어 그대로 두면 무음 유실된다. 이 시트가 그 전부를 담는다.
 *
 * 정체성은 '필드 정의'·'필드 데이터' 시트와 **같은 (세계관, 필드키, 대상) 삼중키**를 쓴다 —
 * 새 헤더 문법을 만들면 내보내기/가져오기가 반드시 드리프트한다.
 * 첫 열이 "이름"이 아니어야 `findSheetForUniverse`가 이 시트를 캐릭터 시트로 오인하지 않는다.
 */
fun characterFieldValueSpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "캐릭터 필드값",
    freezeCols = 2,
    columns = listOf(
        ColumnSpec("캐릭터코드", required = true, readOnly = true, width = 4000),
        ColumnSpec("캐릭터이름", readOnly = true, width = 6000),
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", readOnly = true, width = 5000),
        ColumnSpec("대상", readOnly = true, dropdownOptions = FieldValueSheetMapper.ENTITY_LABELS, width = 3500),
        ColumnSpec("값", width = 8000, wrap = true)
    )
)

/**
 * 작품 필드값 오버플로 — 작품 시트가 **열로 표현할 수 없는** 작품 필드값을 담는다 (B-65).
 *
 * 작품 시트는 전 구역의 작품 필드를 열로 싣지만, 같은 이름의 필드가 한 구역에 둘 있으면
 * 두 열의 머리가 글자까지 같아져 **열은 하나만 선다**([EntityFieldHeaders.plan]). 그 나머지가
 * 이 시트로 온다 — 종전에는 같은 이름의 열이 둘 그려졌고 가져오기가 뒤쪽을 경고와 함께 버려,
 * 그 필드의 값은 내보내도 결코 되돌아오지 못했다.
 *
 * 정체성은 '캐릭터 필드값' 시트와 **같은 (구역, 필드키, 대상) 삼중키**다 — 새 헤더 문법을
 * 만들면 내보내기/가져오기가 반드시 드리프트한다. 첫 열이 '제목'이 아니어야 이 시트가 작품
 * 시트로 오인되지 않는다(`characterFieldValueSpec`이 첫 열을 '캐릭터코드'로 둔 것과 같은 이유).
 */
fun novelFieldValueSpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "작품 필드값",
    columns = listOf(
        ColumnSpec("작품코드", required = true, readOnly = true, width = 4000),
        ColumnSpec("작품제목", readOnly = true, width = 8000),
        ColumnSpec("세계관", dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", readOnly = true, width = 5000),
        ColumnSpec("값", width = 8000, wrap = true)
    )
)

/**
 * 사건 필드값 오버플로 — 연표 시트가 **열로 표현할 수 없는** 사건 필드값을 담는다 (B-65).
 * 근거와 삼중키는 [novelFieldValueSpec]과 같다.
 *
 * **정체는 사건 코드 하나로만 잡는다.** 연도·설명으로 되짚으면 같은 해에 비슷한 문장이 둘 있을 때
 * 값이 남의 사건에 붙는데, 오배정은 생략보다 나쁘다(R-1). 코드가 빈 행은 경고하고 건너뛴다.
 */
fun eventFieldValueSpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "사건 필드값",
    columns = listOf(
        ColumnSpec("사건코드", required = true, readOnly = true, width = 4000),
        ColumnSpec("사건설명", readOnly = true, width = 10000),
        ColumnSpec("세계관", dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", readOnly = true, width = 5000),
        ColumnSpec("값", width = 8000, wrap = true)
    )
)

/**
 * 대결(B-104) 시트 셋이 쓰는 **화면 밖 어휘** — 내보내기·가져오기 공용 단일 소스.
 *
 * 두 곳에 따로 적으면 반드시 드리프트한다(규약 R-7). 코드값이 아니라 사람이 읽는 말을 싣는
 * 것은 **엑셀이 사람이 고치는 자리**이기 때문이다 — `character`·`counter`를 적으라고 할 수는 없다.
 * 되읽을 때는 **한국어 말과 저장값을 모두** 받는다(외부 도구가 저장값을 그대로 쓸 수도 있다).
 */
object DuelSheetLabels {
    const val TARGET_CHARACTER = "캐릭터"
    const val TARGET_IMAGE = "이미지"
    val TARGETS = listOf(TARGET_CHARACTER, TARGET_IMAGE)

    const val KIND_COUNTER = "상성"
    const val KIND_UNDECIDED = "미정"
    val KINDS = listOf(KIND_COUNTER, KIND_UNDECIDED)

    const val SHAPE_DIRECT = "천적"
    const val SHAPE_CYCLE = "순환"

    /** 무승부를 적는 말. 빈 칸도 무승부로 읽지만, 내보낼 때는 **적어서** 뜻을 분명히 한다. */
    const val WINNER_DRAW = "비슷함"
}

/**
 * 대결 **축** 시트 (B-104 ㄹ1 — 사용자 확정 "엑셀 포함").
 *
 * **정의가 기록보다 앞이다** — 판·처분은 축을 가리키므로 축이 먼저 들어와야 붙을 자리가 있다
 * (확-3의 교훈). 시트 순서와 가져오기 순서를 모두 그렇게 둔다.
 *
 * 필드 연결(층 C·B-122)은 **키를 쉼표로 이은 글**이다. 앞머리 `▼`는 *작을수록 유리*이며
 * 해석은 `util/DuelFieldLinks`가 단일 소스다 — 사람이 손으로 고칠 수 있는 자리라 JSON을
 * 싣지 않는다. `프로필필드`에는 앞머리 `▼`가 뜻이 없다(견주지 않으므로 유리한 방향이 없다).
 *
 * **표식이 `-`에서 `▼`로 바뀌었다**(B-173) — 엑셀이 `-`로 시작하는 입력을 수식으로 읽어
 * `-age`를 손으로 치면 `#NAME?`가 떴다. *"사람이 손으로 고칠 수 있는 자리"*라 선언해 둔
 * 이 칸이 정작 그 한 갈래에서만 막혀 있던 셈이다. **읽기는 `▼`·`-`·`'-` 셋 다 받고 쓰기는
 * `▼` 하나**라, 이미 내보낸 파일과 저장된 축이 그대로 살아 있다(R-2).
 *
 * **키에는 커스텀 필드 말고 시스템 열도 적을 수 있다**(B-167) — `sys:another_name`(이명) ·
 * `sys:name` · `sys:first_name` · `sys:last_name` · `sys:memo` · `sys:novel`(작품 제목) ·
 * `sys:tags` · `sys:faction`(지금 속한 세력 — 겸직이면 쉼표로 여럿이다).
 * 종류의 원문은 `util/DuelSystemFields`의 `Column`이고 **여기 두 벌로 적지
 * 않는다**(적으면 낡는다 — 위 목록은 읽는 사람을 위한 예시다).
 * `▼` 앞머리와 겹쳐 `▼sys:name`처럼 적을 수 있다.
 * ⚠️ **`산출필드`에는 시스템 열이 성립하지 않는다** — 대결 결과를 이명·메모에 써 넣을 수
 * 없어서다. 적어 들어와도 연결은 지우지 않고(사용자가 적은 것을 버리지 않는다) 순위표의
 * 대조에서 빠지며, 축 편집 창이 그 사실을 사유와 함께 말한다.
 *
 * ⚠️ **연결 셋과 `후보필터(JSON)`은 열이 없으면 건드리지 않는다**
 * (`ExcelImportService.readDuelAxisRow`) — 그 열이 없던 시절에 내보낸 파일을 다시 들이는
 * 것만으로 값이 지워지면 안 되기 때문이다.
 * *빈 칸*("지워라")과 *없는 열*("이 파일은 그것을 말하지 않는다")은 다른 사실이다.
 *
 * `기준축`(B-104 ⓑ·ⓒ)은 *"이 축을 대표·정리의 기준으로"*다 — 켜진 축의 순위가 대표 이미지
 * 추첨의 가중치와 걸러낼 후보 제안을 정한다. **세계관당 하나뿐이고**, 한 세계관에 `Y`가
 * 여럿 적혀 들어오면 **마지막 행이 이긴다**(가져오기가 행 차례대로 켜면서 형제를 내리므로
 * 자연히 그렇게 된다 — 거부하지 않고 받아 정리하는 쪽이 개발 의도 4번에 맞는다).
 * 이미지 축이 아닌 행에 적혀 있으면 값은 남되 아무 일도 하지 않는다.
 *
 * `후보필터(JSON)`(B-168)은 연결 셋과 달리 **JSON을 그대로 싣는다** — 값 목록·일치 방식이
 * 딸린 구조라 쉼표 문법으로 펴면 값 안의 쉼표와 충돌한다. 프리셋 시트의 `필드필터(JSON)`과
 * 같은 규약이되, 필드를 id가 아니라 **키**(`fieldKey`)로 가리키므로 그쪽의 id 재해석
 * (`PortableFieldFilters`)이 필요 없다 — 키는 세계관 안에서 그 자체로 이식된다.
 */
fun duelAxisSpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "대결 축",
    columns = listOf(
        ColumnSpec("축이름", required = true, width = 6000),
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("대상", dropdownOptions = DuelSheetLabels.TARGETS, width = 3500),
        ColumnSpec("영향필드", width = 10000),
        ColumnSpec("산출필드", width = 8000),
        ColumnSpec("프로필필드", width = 10000),
        ColumnSpec("후보필터(JSON)", width = 12000),
        ColumnSpec("기준축", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

/**
 * 대결 **기록** 시트 — 한 행이 한 판이다.
 *
 * ⚠️ **이 앱에서 가장 큰 시트가 될 수 있다**(수만 행 — `scalability_performance` 4장).
 *
 * 참가자를 **이름과 코드 둘 다** 싣는 것은 다른 시트와 같은 규약이다: 코드가 정체이고
 * 이름은 사람이 읽는 몫이다. 승자는 **이름으로 적는다** — 코드를 적으라고 하면 사람이 고칠 수
 * 없고, 그러면 이 시트를 엑셀에 싣는 뜻이 없어진다. 비겼으면 `비슷함`이다.
 */
fun duelMatchSpec(axisNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "대결 기록",
    freezeCols = 1,
    columns = listOf(
        ColumnSpec("축", required = true, dropdownOptions = axisNames.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("축코드", readOnly = true, width = 4000),
        ColumnSpec("참가자1", required = true, width = 6000),
        ColumnSpec("참가자1코드", readOnly = true, width = 4000),
        ColumnSpec("참가자2", required = true, width = 6000),
        ColumnSpec("참가자2코드", readOnly = true, width = 4000),
        ColumnSpec("승자", width = 6000),
        ColumnSpec("묶음", width = 4000),
        ColumnSpec("판정일", readOnly = true, width = 5000, millis = true),
        ColumnSpec("코드", readOnly = true, width = 4000)
    )
)

/**
 * 대결 **상성** 시트 — 층 B의 사용자 처분(②미정 · ③상성).
 *
 * **파생이 아니라 판정이라 싣는다.** 점수·순위·상성 후보는 기록에서 다시 낼 수 있지만
 * 이것은 사용자가 고른 것이라 잃으면 되돌릴 수 없다.
 *
 * '모양'을 읽기 전용으로 둔 것은 **참가자 수가 그것을 정하기 때문**이다(둘이면 천적, 셋 이상이면
 * 순환). 사람이 적은 모양과 참가자 수가 어긋나면 어느 쪽이 진짜인지 알 수 없어진다.
 */
fun duelVerdictSpec(axisNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "대결 상성",
    columns = listOf(
        ColumnSpec("축", required = true, dropdownOptions = axisNames.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("축코드", readOnly = true, width = 4000),
        ColumnSpec("종류", required = true, dropdownOptions = DuelSheetLabels.KINDS, width = 3500),
        ColumnSpec("모양", readOnly = true, width = 3500),
        ColumnSpec("참가자들", required = true, width = 10000),
        ColumnSpec("참가자코드들", readOnly = true, width = 8000),
        ColumnSpec("판정일", readOnly = true, width = 5000, millis = true),
        ColumnSpec("코드", readOnly = true, width = 4000)
    )
)

fun characterSpec(fields: List<FieldDefinition>, novelTitles: List<String>) = SheetSpec(
    sheetName = "",  // Sheet name is set dynamically (universe name or "미분류 캐릭터")
    freezeCols = 1,  // 이름 열 — 오른쪽으로 스크롤해도 행의 주인이 보인다 (V-6)
    columns = buildList {
        add(ColumnSpec("이름", required = true, width = 6000))
        add(ColumnSpec("성", width = 4000))
        add(ColumnSpec("이름(First)", width = 4000))
        add(ColumnSpec("이명", width = 6000))
        // Dynamic field columns — 고정 열과 겹치거나 동명 필드가 둘 이상이면 필드키를 병기해
        // 열 정체를 확정한다(병기하지 않으면 가져오기가 first-wins로 값을 뒤바꾼다).
        val fieldNameCounts = fields.groupingBy { it.name }.eachCount()
        for (field in fields) {
            val options = if (field.fieldType == FieldType.SELECT) {
                try {
                    val json = org.json.JSONObject(field.config)
                    val arr = json.optJSONArray("options")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) } else null
                } catch (_: Exception) { null }
            } else null
            val disambiguate = collidesWithFixedHeader(field.name) || (fieldNameCounts[field.name] ?: 0) > 1
            val core = characterFieldHeader(field.name, field.key, disambiguate)
            // 쉼표를 쪼개는 **모든** 필드에 안내를 붙인다 (B-38). 종전에는 `type == "MULTI_TEXT"`
            // 하나만 보았고, 그래서 '콤마 목록' 표시 형식의 TEXT 필드는 외부 편집자가 쉼표의 뜻을
            // 모른 채 값을 넣었다 — 안내 없이 넣은 쉼표는 들이기에서 토큰 구조를 조용히 가른다.
            // 판정은 앱이 이미 쓰는 단일 소스가 든다(B-37이 정렬에서 없앤 하드코딩의 쌍둥이).
            // 접미사 글자는 [EntityFieldHeaders.MULTI_SUFFIX] 하나가 든다 — 연표·작품 시트도 같은 말을
            // 쓰고(B-177), 시트마다 다른 말로 안내하면 외부 편집자가 시트마다 다시 배운다.
            // 리터럴을 두 벌로 두면 한쪽만 고쳐질 때 **가져오기가 그 열을 못 알아본다.**
            val multiToken = FieldValueTokenizer.isMultiToken(field)
            val headerName = if (multiToken) core + EntityFieldHeaders.MULTI_SUFFIX else core
            add(ColumnSpec(
                headerName,
                required = field.isRequired,
                dropdownOptions = options,
                // 타입별 기본 너비(V-8) — 시스템 열은 손튜닝인데 커스텀 필드만 일률 5000이었다.
                width = customFieldColumnWidth(field.fieldType, multiToken),
                calc = field.fieldType == FieldType.CALCULATED
            ))
        }
        // 편집이 반영되는 열이다 — 세계관·작품 시트의 같은 열, 사용 안내(B-222 WD-6)와 한 규약.
        // 회색(readOnly)으로 잠그면 범례("앱이 채우는 열 — 그대로 두세요")를 믿는 사용자가
        // 문서화된 편집 능력을 영영 못 쓴다.
        add(ColumnSpec("이미지경로", width = 4000))
        // 대표 이미지(B-103 D8). 사람이 읽고 고칠 수 있어야 값어치가 있고,
        // 그래서 내부 경로 원문이 아니라 파일명을 싣는다.
        add(ColumnSpec("대표이미지", width = 5000))
        add(ColumnSpec("작품", dropdownOptions = novelTitles.takeIf { it.isNotEmpty() }, width = 6000))
        add(ColumnSpec("메모", width = 10000, wrap = true))
        add(ColumnSpec("태그", width = 8000))
        add(ColumnSpec("코드", readOnly = true, width = 4000))
        add(ColumnSpec("작품코드", readOnly = true, width = 4000))
        add(ColumnSpec("정렬순서", width = 3000))
        add(ColumnSpec("고정", dropdownOptions = listOf("Y", "N"), width = 3000))
        add(ColumnSpec("생성일", readOnly = true, width = 5000, millis = true))
    }
)

/**
 * 전 세계관 캐릭터를 한 장에 모으는 **읽기 전용** 시트 (U-12a).
 *
 * 캐릭터 시트는 세계관마다 갈리고 열 구성도 다르다(실사용 표본은 10장 · 49·38·42·13열).
 * 그래서 엑셀의 최대 강점인 **전체 정렬·필터·피벗을 전 인원에 걸 수 없다** — 이 시트가 그 자리다.
 *
 * **가져오기는 이 시트를 읽지 않는다.** 예약명이라 세계관 캐릭터 시트로 오인되지 않고,
 * 첫 열이 '이름'이 아니라 `looksLikeCharacterSheet`의 지문에도 걸리지 않는다
 * (`characterFieldValueSpec`이 첫 열을 '캐릭터코드'로 둔 것과 같은 이유다).
 * 왕복 규약을 건드리지 않으므로 **고칠 곳은 여전히 캐릭터 시트**이며, 그 사실을 사용 안내가 말한다.
 *
 * [sharedFieldHeaders]는 **여러 세계관이 함께 쓰는 필드**의 열이다. 한 세계관에만 있는 필드까지
 * 실으면 열이 전 세계관의 합집합이 되어 피벗이라는 이 시트의 쓸모가 사라진다. 판정은
 * **(필드키, 타입)** — 통계의 세계관 병합과 같은 규칙이라 새 개념을 만들지 않는다.
 */
fun allCharactersSpec(
    sharedFieldHeaders: List<String> = emptyList(),
    /** [sharedFieldHeaders]와 같은 차례의 [FieldType.name] — 열 너비·CALCULATED 소수 서식 판정용(시각 개편). 비우면 기본 치수. */
    sharedFieldTypes: List<String> = emptyList()
) = SheetSpec(
    sheetName = ALL_CHARACTERS_SHEET_NAME,
    freezeCols = 3,  // 세계관·작품·이름 — 피벗용 넓은 시트의 정체 열 (V-6)
    columns = buildList {
        add(ColumnSpec("세계관", readOnly = true, width = 5000))
        add(ColumnSpec("작품", readOnly = true, width = 6000))
        add(ColumnSpec("이름", readOnly = true, width = 6000))
        add(ColumnSpec("성", readOnly = true, width = 4000))
        add(ColumnSpec("이름(First)", readOnly = true, width = 4000))
        add(ColumnSpec("이명", readOnly = true, width = 6000))
        add(ColumnSpec("태그", readOnly = true, width = 8000))
        add(ColumnSpec("고정", readOnly = true, width = 3000))
        add(ColumnSpec("정렬순서", readOnly = true, width = 3000))
        add(ColumnSpec("생성일", readOnly = true, width = 5000, millis = true))
        add(ColumnSpec("코드", readOnly = true, width = 4000))
        add(ColumnSpec("작품코드", readOnly = true, width = 4000))
        sharedFieldHeaders.forEachIndexed { i, header ->
            val type = sharedFieldTypes.getOrNull(i)?.let { FieldType.fromName(it) }
            add(ColumnSpec(
                header, readOnly = true,
                width = customFieldColumnWidth(type, multiToken = type == FieldType.MULTI_TEXT),
                calc = type == FieldType.CALCULATED
            ))
        }
    }
)

fun timelineSpec(
    novelTitles: List<String>,
    eventFieldHeaders: List<String> = emptyList(),
    universeNames: List<String> = emptyList()
) = SheetSpec(
    sheetName = "사건 연표",
    freezeCols = 1,
    columns = listOf(
        ColumnSpec("연도", required = true, width = 3000),
        ColumnSpec("월", width = 2000),
        ColumnSpec("일", width = 2000),
        ColumnSpec("역법", width = 3000),
        ColumnSpec("사건 유형", dropdownOptions = listOf("일반", "탄생", "사망"), width = 3000),
        ColumnSpec("사건 설명", required = true, width = 15000, wrap = true),
        ColumnSpec("관련 작품", dropdownOptions = novelTitles.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("관련 캐릭터", width = 10000),
        ColumnSpec("관련작품코드", readOnly = true, width = 4000),
        // 관련 캐릭터를 코드로도 싣는다 — 동명이인 오결합 방지(P1-I). 가져오기는 코드 우선, 없으면 이름 매칭.
        ColumnSpec("관련캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("임시배치", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true),
        // 사건의 세계관 소속 — 작품 미연결 사건도 신규 기기 복원 시 세계관을 잃지 않게 한다.
        // 이 열이 없던 구버전 파일은 기존처럼 관련 작품의 세계관에서 유도한다(하위 호환).
        ColumnSpec("세계관", dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000)
    ) + eventFieldHeaders.map { ColumnSpec(it, width = 6000) }  // 사건 커스텀 필드 (B-10)
)

fun stateChangeSpec() = SheetSpec(
    sheetName = "캐릭터 상태변화",
    freezeCols = 1,
    columns = listOf(
        ColumnSpec("캐릭터", required = true, width = 5000),
        ColumnSpec("작품", width = 5000),
        ColumnSpec("연도", required = true, width = 3000),
        ColumnSpec("월", width = 2000),
        ColumnSpec("일", width = 2000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("새 값", width = 5000),
        ColumnSpec("설명", width = 10000, wrap = true),
        ColumnSpec("캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

/**
 * 캐릭터 명대사 (사용자 요청 2026.08.20).
 *
 * 첫 열이 `이름`이 아니라 `캐릭터`인 것은 형제 기록 시트의 규약이다 — 캐릭터 시트와 앞
 * 세 열이 겹치면 [headersMatchSpec]이 시트 정체를 가릴 수 없다(R-7).
 *
 * `상황` 칸은 **빈 값이 일반이고 `__birthday`가 생일 전용**이다. 사람이 손으로 고칠 수
 * 있어야 하므로(개발 의도 4번) 예약 글자를 그대로 싣고, 드롭다운으로 둘을 제안하되
 * **닫지는 않는다** — 사용자가 자기 상황을 적어 넣을 수 있다(원칙 01).
 */
fun quoteSpec() = SheetSpec(
    sheetName = "캐릭터 명대사",
    freezeCols = 1,
    columns = listOf(
        ColumnSpec("캐릭터", required = true, width = 5000),
        ColumnSpec("작품", width = 5000),
        ColumnSpec("대사", required = true, width = 14000, wrap = true),
        ColumnSpec("상황", width = 4000),
        ColumnSpec("출처", width = 8000, wrap = true),
        ColumnSpec("표시순서", width = 2500),
        ColumnSpec("캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

fun relationshipSpec(
    customTypes: List<String> = emptyList(),
    factionNames: List<String> = emptyList()
) = SheetSpec(
    sheetName = "캐릭터 관계",
    freezeCols = 2,
    columns = listOf(
        ColumnSpec("캐릭터1", required = true, width = 6000),
        ColumnSpec("캐릭터2", required = true, width = 6000),
        ColumnSpec("관계 유형", required = true, dropdownOptions = (Universe.DEFAULT_RELATIONSHIP_TYPES + customTypes).distinct(), width = 5000),
        ColumnSpec("설명", width = 10000, wrap = true),
        ColumnSpec("강도", width = 3000),
        ColumnSpec("양방향", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("표시순서", width = 3000),
        ColumnSpec("캐릭터1코드", readOnly = true, width = 4000),
        ColumnSpec("캐릭터2코드", readOnly = true, width = 4000),
        // 편집 가능한 참조 열 — 비우면 자동 관계가 수동 관계로 풀린다('세력 소속' 시트의 '세력'과 동형).
        // 참조의 '유무'는 이 열이, '대상'은 아래 '세력코드'가 정한다(refColumnIntent).
        ColumnSpec("세력", dropdownOptions = factionNames.takeIf { it.isNotEmpty() }, width = 5000),
        // 세력 자동 관계의 소속을 코드로도 싣는다 — 이름 충돌·기기 이전에도 연결이 유지되게(코드 우선 해석)
        ColumnSpec("세력코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true),
        // 관계 자체의 안정 식별자 — 이 열이 있으면 '관계 유형'을 고쳐도 같은 관계로 인식한다
        // (자연키가 캐릭터1+캐릭터2+유형이라 코드 없이는 rename과 신규를 구별할 수 없다)
        ColumnSpec("코드", readOnly = true, width = 4000)
    )
)

fun relationshipChangeSpec() = SheetSpec(
    sheetName = "관계 변화",
    columns = listOf(
        ColumnSpec("캐릭터1", required = true, width = 6000),
        ColumnSpec("캐릭터2", required = true, width = 6000),
        ColumnSpec("연도", required = true, width = 3000),
        ColumnSpec("월", width = 2000),
        ColumnSpec("일", width = 2000),
        ColumnSpec("관계 유형", width = 5000),
        ColumnSpec("설명", width = 10000, wrap = true),
        ColumnSpec("강도", width = 3000),
        ColumnSpec("양방향", dropdownOptions = listOf("Y", "N"), width = 3000),
        // 사건 참조는 코드 기반 — DB id는 복원/기기 이전 시 변해 참조로 부적합.
        // 가져오기는 구버전 "연결사건ID" 컬럼도 계속 인식한다 (하위 호환).
        ColumnSpec("연결사건코드", readOnly = true, width = 4000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("캐릭터1코드", readOnly = true, width = 4000),
        ColumnSpec("캐릭터2코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true),
        // 이 이력이 붙은 **부모 관계**의 유형. 위 '관계 유형'(변화 시점의 유형)과 다른 값이다.
        // 같은 두 캐릭터 사이에 유형이 다른 관계가 여러 개일 수 있어(유니크 키가 쌍+유형),
        // 이 열이 없으면 이력이 어느 관계의 것인지 파일만으로 알 수 없다.
        ColumnSpec("부모관계유형", readOnly = true, width = 5000),
        // 부모 관계의 안정 식별자 — 유형까지 편집된 파일에서도 이력이 정확히 따라간다(코드 우선)
        ColumnSpec("관계코드", readOnly = true, width = 4000)
    )
)

fun nameBankSpec() = SheetSpec(
    sheetName = "이름 은행",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 5000),
        ColumnSpec("성별", width = 3000),
        ColumnSpec("출처", width = 5000),
        ColumnSpec("메모", width = 8000, wrap = true),
        ColumnSpec("사용여부", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("사용 캐릭터", width = 5000),
        ColumnSpec("사용캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true),
        // 이름 은행 항목 자체의 안정 식별자 (F3-D) — 왕복 매칭 기준, 수정 금지
        ColumnSpec("코드", readOnly = true, width = 4000)
    )
)

fun userPresetTemplateSpec() = SheetSpec(
    sheetName = "필드 템플릿",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("설명", width = 15000, wrap = true),
        ColumnSpec("설정(JSON)", width = 15000),
        ColumnSpec("기본제공", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 6000, millis = true),
        ColumnSpec("수정일", readOnly = true, width = 6000, millis = true)
    )
)

/**
 * 캐릭터 목록 프리셋(필터+정렬 조합) 왕복 — 이름이 유니크 키.
 * 작품 필터는 DB id가 기기마다 달라지므로 **작품코드 콤마 목록**으로 왕복한다(이식성).
 */
fun characterListPresetSpec() = SheetSpec(
    sheetName = "목록 프리셋",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("태그(JSON)", width = 10000),
        ColumnSpec("필드필터(JSON)", width = 15000),
        // 드롭다운 목록과 가져오기 유효값 검증이 **같은 상수**를 본다(검색 프리셋과 같은 규약) —
        // 리터럴로 나열해 두었더니 B-117이 정렬 하나를 더할 때 이 자리가 뒤처졌다.
        ColumnSpec("정렬종류", dropdownOptions = CharacterListPreset.SORT_KINDS.toList(), width = 4000),
        ColumnSpec("정렬필드키", width = 5000),
        // 대결 점수 정렬(B-117)의 대상 축. **코드**이지 이름이 아니다 — 축 이름은 세계관마다
        // 겹치고, 이 앱의 축은 판·처분·휴지통이 전부 코드로 가리킨다(R-1).
        ColumnSpec("대결축코드", width = 5000),
        ColumnSpec("정렬오름차순", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("신체파트번호", width = 4000),
        ColumnSpec("작품코드목록", width = 10000),
        ColumnSpec("기본값", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 6000, millis = true),
        ColumnSpec("수정일", readOnly = true, width = 6000, millis = true)
    )
)

fun searchPresetSpec() = SheetSpec(
    sheetName = "검색 프리셋",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("검색어", width = 10000),
        ColumnSpec("필터(JSON)", width = 15000),
        // 드롭다운 목록과 가져오기 유효값 검증이 같은 상수를 본다 — 규칙을 양쪽에 두면 드리프트한다
        ColumnSpec("정렬모드", dropdownOptions = SearchPreset.SORT_MODES, width = 5000),
        ColumnSpec("기본값", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 6000, millis = true),
        ColumnSpec("수정일", readOnly = true, width = 6000, millis = true)
    )
)

fun factionSpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "세력",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("세계관", dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 8000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("설명", width = 15000, wrap = true),
        ColumnSpec("색상", width = 4000),
        ColumnSpec("자동관계유형", required = true, width = 6000),
        ColumnSpec("자동관계강도", width = 3000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

fun factionMembershipSpec(factionNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "세력 소속",
    columns = listOf(
        ColumnSpec("세력", required = true, dropdownOptions = factionNames.takeIf { it.isNotEmpty() }, width = 8000),
        ColumnSpec("캐릭터", required = true, width = 8000),
        ColumnSpec("가입연도", width = 3000),
        ColumnSpec("탈퇴연도", width = 3000),
        ColumnSpec("탈퇴유형", dropdownOptions = listOf("", "순수제거", "설정상탈퇴"), width = 5000),
        ColumnSpec("탈퇴후관계유형", width = 6000),
        ColumnSpec("탈퇴후강도", width = 3000),
        ColumnSpec("세력코드", readOnly = true, width = 4000),
        ColumnSpec("캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

fun factionRelationshipSpec(
    factionNames: List<String> = emptyList(),
    customTypes: List<String> = emptyList()
) = SheetSpec(
    sheetName = "세력 관계",
    columns = listOf(
        ColumnSpec("세력1", required = true, dropdownOptions = factionNames.takeIf { it.isNotEmpty() }, width = 8000),
        ColumnSpec("세력2", required = true, dropdownOptions = factionNames.takeIf { it.isNotEmpty() }, width = 8000),
        ColumnSpec("관계 유형", required = true, dropdownOptions = (Universe.DEFAULT_RELATIONSHIP_TYPES + customTypes).distinct(), width = 5000),
        ColumnSpec("설명", width = 10000, wrap = true),
        ColumnSpec("강도", width = 3000),
        ColumnSpec("양방향", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("표시순서", width = 3000),
        ColumnSpec("세력1코드", readOnly = true, width = 4000),
        ColumnSpec("세력2코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000, millis = true)
    )
)

/**
 * '앱 설정' 시트 — `설정값`만 고치는 자리이고, 나머지 셋은 **읽는 자리**다.
 *
 * `설명`·`입력 가능한 값` 두 칸은 2026.08.20 사용자 요청으로 생겼다:
 * *"각각의 칸이 뭘 의미하고 어떤 입력을 받는지를 알 방법이 없거든?"* — 그전까지 허용 범위는
 * 전부 코드 안에만 있었고, 사용자가 알 수 있는 길은 **틀린 값을 넣고 가져오기를 돌려 경고를
 * 읽는 것**뿐이었다(범위 밖 숫자는 조용히 좁혀져 그 경고조차 안 떴다).
 *
 * 두 칸이 `readOnly`인 것이 안내의 절반이다 — 사용 안내의 색 범례가 이미 *"회색 헤더/셀 =
 * 앱이 채우는 열"*이라 말하고 있어, 따로 설명하지 않아도 고칠 칸이 어디인지 보인다.
 * 가져오기는 이 두 열을 읽지 않는다(`importAppSettings`가 `설정키`·`설정값`만 본다).
 *
 * **옛 파일(두 칸짜리)도 그대로 들어온다** — 열은 이름으로 찾고 `설정값`만 필수다.
 */
fun appSettingsSpec() = SheetSpec(
    sheetName = "앱 설정",
    columns = listOf(
        ColumnSpec("설정키", required = true, width = 8000),
        // AI 메시지 양식이 여러 줄이라 wrap을 켠다 — 안 켜면 셀을 열기 전에는 첫 줄만 보인다.
        ColumnSpec("설정값", width = 12000, wrap = true),
        ColumnSpec("설명", readOnly = true, width = 12000, wrap = true),
        ColumnSpec("입력 가능한 값", readOnly = true, width = 16000, wrap = true)
    )
)

/**
 * 이미지 라이브러리 메타(태그·링크 그룹) 시트 (G3).
 * 파일명은 basename만 기록한다 — 절대경로는 기기 간 이식성이 없으므로, 가져오기에서
 * zip 리맵(원경로 basename 매칭) 또는 로컬 filesDir 존재 확인으로 해석한다.
 * 링크그룹 토큰은 내보낸 값을 그대로 보존해 재가져오기가 멱등이 되게 한다.
 */
fun imageMetaSpec() = SheetSpec(
    sheetName = "이미지",
    columns = listOf(
        ColumnSpec("파일명", required = true, readOnly = true, width = 10000),
        ColumnSpec("태그", width = 10000),
        // 편집 가능(결정 D2) — 같은 문자열을 쓴 행끼리 한 묶음이 된다. 회색으로 두면 "앱이 채우는
        // 열"로 읽혀 아무도 손대지 않는다. 여는 이상 규약도 태그 열과 같아야 한다:
        // 열이 없으면 기존 유지, 열이 있고 빈칸이면 해제(설계 9장 C-3).
        ColumnSpec("링크그룹", width = 9000),
        // 뗀 이미지 서랍(B-107 D1) — **빈칸 = 뗀 적 없음**이라 열 하나가 값과 상태를 겸한다.
        // 편집 가능한 이유는 서랍을 엑셀에서도 비울 수 있어야 하기 때문이다(개발 의도 4번).
        // 규약은 태그·링크그룹과 같다: 열이 없으면 기존 유지, 열이 있고 빈칸이면 해제.
        ColumnSpec("뗀날짜", width = 5000, millis = true),
        // 출처는 **읽기 전용**이다 — 사용자가 여기 캐릭터 코드를 손으로 적을 이유가 없고,
        // 없는 코드를 적으면 화면이 "지워진 캐릭터"라고 말하게 된다(고칠 길 없는 거짓).
        ColumnSpec("뗀곳", readOnly = true, width = 6000)
    )
)
