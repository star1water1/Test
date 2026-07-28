package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.Universe
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
    val width: Int = 5000
)

data class SheetSpec(
    val sheetName: String,
    val columns: List<ColumnSpec>
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
    "이름", "성", "이름(First)", "이명", "이미지경로", "작품", "메모", "태그",
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

/** All reserved (non-universe) sheet names used by the app. */
val RESERVED_SHEET_NAMES = setOf(
    GUIDE_SHEET_NAME,
    universeSpec().sheetName,
    novelSpec(emptyList()).sheetName,
    fieldDefinitionSpec(emptyList()).sheetName,
    UNCLASSIFIED_SHEET_NAME,
    timelineSpec(emptyList()).sheetName,
    stateChangeSpec().sheetName,
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
    characterFieldValueSpec().sheetName
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
    val probe = minOf(3, spec.columns.size)
    if (headers.size < probe) return false
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
 */
fun toHalfWidth(value: String): String {
    if (value.none { it.code in 0xFF01..0xFF5E }) return value
    return buildString(value.length) {
        for (c in value) append(if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c)
    }
}

/** Split a comma-separated string into a trimmed, non-blank list. 전각 쉼표(，)도 관대 수용 (F4). */
fun splitCsv(value: String): List<String> =
    toHalfWidth(value).split(",").map { it.trim() }.filter { it.isNotBlank() }

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
 * 엑셀 불리언 열 파싱의 **단일 소스** (순수 함수 — 단위 테스트 대상).
 * Y/N·TRUE/FALSE·1/0·yes/no·T/F·예/참을 수용하고 전각 입력(Ｙ／１ 등)을 정규화한다.
 * **빈칸은 false다** — "열 있음 + 빈칸 = 비움 의도"(F1-A).
 */
fun parseSheetBoolean(value: String): Boolean =
    when (toHalfWidth(value.trim()).uppercase()) {
        "Y", "YES", "TRUE", "T", "1", "O", "예", "참" -> true
        else -> false
    }

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

// ── Sheet Spec factories ──

fun universeSpec() = SheetSpec(
    sheetName = "세계관",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("설명", width = 15000),
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
        ColumnSpec("생성일", readOnly = true, width = 5000)
    )
)

fun novelSpec(universeNames: List<String>) = SheetSpec(
    sheetName = "작품",
    columns = listOf(
        ColumnSpec("제목", required = true, width = 8000),
        ColumnSpec("설명", width = 15000),
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
        ColumnSpec("생성일", readOnly = true, width = 5000)
    )
)

fun fieldDefinitionSpec(universeNames: List<String>) = SheetSpec(
    sheetName = "필드 정의",
    columns = listOf(
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", required = true, width = 5000),
        ColumnSpec("타입", required = true, dropdownOptions = listOf("TEXT", "NUMBER", "SELECT", "MULTI_TEXT", "GRADE", "CALCULATED", "BODY_SIZE"), width = 4000),
        ColumnSpec("설정(JSON)", width = 10000),
        ColumnSpec("그룹", width = 5000),
        ColumnSpec("순서", width = 3000),
        ColumnSpec("필수여부", dropdownOptions = listOf("Y", "N"), width = 4000),
        // config 파생 전용 열(A-1·A-2) — 사람이 고치는 값이라 JSON 셀에 뭉치지 않고 열을 판다.
        // 내보내기는 설정(JSON) 셀에서 두 키를 제거하고 여기에만 싣는다(FieldConfigColumns).
        ColumnSpec(FieldConfigColumns.COLUMN_AI_SUGGEST, dropdownOptions = listOf("Y", "N"), width = 3500),
        ColumnSpec(FieldConfigColumns.COLUMN_DESCRIPTION, width = 12000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        // 캐릭터/사건 필드 구분 — 이 열이 없던 구버전 파일은 캐릭터로 간주(관대 수용).
        // 사건 필드 정의도 왕복되어야 신규 기기 복원 시 사건 필드값이 유실되지 않는다.
        ColumnSpec("대상", dropdownOptions = listOf("캐릭터", "사건"), width = 3500)
    )
)

/** 필드 데이터 라이브러리 — 값 카탈로그 왕복 (별칭·표시라벨·카테고리·설명이 외부 편집 가능) */
fun fieldValueLibrarySpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "필드 데이터",
    columns = listOf(
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", readOnly = true, width = 5000),
        ColumnSpec("대상", dropdownOptions = listOf("캐릭터", "사건"), width = 3500),
        ColumnSpec("값", required = true, width = 6000),
        ColumnSpec("표시라벨", width = 5000),
        ColumnSpec("별칭(콤마구분)", width = 8000),
        ColumnSpec("카테고리", width = 5000),
        ColumnSpec("설명", width = 8000),
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
    columns = listOf(
        ColumnSpec("캐릭터코드", required = true, readOnly = true, width = 4000),
        ColumnSpec("캐릭터이름", readOnly = true, width = 6000),
        ColumnSpec("세계관", required = true, dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 5000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("필드명", readOnly = true, width = 5000),
        ColumnSpec("대상", readOnly = true, dropdownOptions = listOf("캐릭터", "사건"), width = 3500),
        ColumnSpec("값", width = 8000)
    )
)

fun characterSpec(fields: List<FieldDefinition>, novelTitles: List<String>) = SheetSpec(
    sheetName = "",  // Sheet name is set dynamically (universe name or "미분류 캐릭터")
    columns = buildList {
        add(ColumnSpec("이름", required = true, width = 6000))
        add(ColumnSpec("성", width = 4000))
        add(ColumnSpec("이름(First)", width = 4000))
        add(ColumnSpec("이명", width = 6000))
        // Dynamic field columns — 고정 열과 겹치거나 동명 필드가 둘 이상이면 필드키를 병기해
        // 열 정체를 확정한다(병기하지 않으면 가져오기가 first-wins로 값을 뒤바꾼다).
        val fieldNameCounts = fields.groupingBy { it.name }.eachCount()
        for (field in fields) {
            val options = if (field.type == "SELECT") {
                try {
                    val json = org.json.JSONObject(field.config)
                    val arr = json.optJSONArray("options")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) } else null
                } catch (_: Exception) { null }
            } else null
            val disambiguate = collidesWithFixedHeader(field.name) || (fieldNameCounts[field.name] ?: 0) > 1
            val core = characterFieldHeader(field.name, field.key, disambiguate)
            val headerName = if (field.type == "MULTI_TEXT") "$core (쉼표 구분)" else core
            add(ColumnSpec(headerName, required = field.isRequired, dropdownOptions = options))
        }
        add(ColumnSpec("이미지경로", readOnly = true, width = 4000))
        add(ColumnSpec("작품", dropdownOptions = novelTitles.takeIf { it.isNotEmpty() }, width = 6000))
        add(ColumnSpec("메모", width = 10000))
        add(ColumnSpec("태그", width = 8000))
        add(ColumnSpec("코드", readOnly = true, width = 4000))
        add(ColumnSpec("작품코드", readOnly = true, width = 4000))
        add(ColumnSpec("정렬순서", width = 3000))
        add(ColumnSpec("고정", dropdownOptions = listOf("Y", "N"), width = 3000))
        add(ColumnSpec("생성일", readOnly = true, width = 5000))
    }
)

fun timelineSpec(
    novelTitles: List<String>,
    eventFieldHeaders: List<String> = emptyList(),
    universeNames: List<String> = emptyList()
) = SheetSpec(
    sheetName = "사건 연표",
    columns = listOf(
        ColumnSpec("연도", required = true, width = 3000),
        ColumnSpec("월", width = 2000),
        ColumnSpec("일", width = 2000),
        ColumnSpec("역법", width = 3000),
        ColumnSpec("사건 유형", dropdownOptions = listOf("일반", "탄생", "사망"), width = 3000),
        ColumnSpec("사건 설명", required = true, width = 15000),
        ColumnSpec("관련 작품", dropdownOptions = novelTitles.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("관련 캐릭터", width = 10000),
        ColumnSpec("관련작품코드", readOnly = true, width = 4000),
        // 관련 캐릭터를 코드로도 싣는다 — 동명이인 오결합 방지(P1-I). 가져오기는 코드 우선, 없으면 이름 매칭.
        ColumnSpec("관련캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("임시배치", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000),
        // 사건의 세계관 소속 — 작품 미연결 사건도 신규 기기 복원 시 세계관을 잃지 않게 한다.
        // 이 열이 없던 구버전 파일은 기존처럼 관련 작품의 세계관에서 유도한다(하위 호환).
        ColumnSpec("세계관", dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000)
    ) + eventFieldHeaders.map { ColumnSpec(it, width = 6000) }  // 사건 커스텀 필드 (B-10)
)

fun stateChangeSpec() = SheetSpec(
    sheetName = "캐릭터 상태변화",
    columns = listOf(
        ColumnSpec("캐릭터", required = true, width = 5000),
        ColumnSpec("작품", width = 5000),
        ColumnSpec("연도", required = true, width = 3000),
        ColumnSpec("월", width = 2000),
        ColumnSpec("일", width = 2000),
        ColumnSpec("필드키", required = true, width = 5000),
        ColumnSpec("새 값", width = 5000),
        ColumnSpec("설명", width = 10000),
        ColumnSpec("캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000)
    )
)

fun relationshipSpec(
    customTypes: List<String> = emptyList(),
    factionNames: List<String> = emptyList()
) = SheetSpec(
    sheetName = "캐릭터 관계",
    columns = listOf(
        ColumnSpec("캐릭터1", required = true, width = 6000),
        ColumnSpec("캐릭터2", required = true, width = 6000),
        ColumnSpec("관계 유형", required = true, dropdownOptions = (Universe.DEFAULT_RELATIONSHIP_TYPES + customTypes).distinct(), width = 5000),
        ColumnSpec("설명", width = 10000),
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
        ColumnSpec("생성일", readOnly = true, width = 5000),
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
        ColumnSpec("설명", width = 10000),
        ColumnSpec("강도", width = 3000),
        ColumnSpec("양방향", dropdownOptions = listOf("Y", "N"), width = 3000),
        // 사건 참조는 코드 기반 — DB id는 복원/기기 이전 시 변해 참조로 부적합.
        // 가져오기는 구버전 "연결사건ID" 컬럼도 계속 인식한다 (하위 호환).
        ColumnSpec("연결사건코드", readOnly = true, width = 4000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("캐릭터1코드", readOnly = true, width = 4000),
        ColumnSpec("캐릭터2코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000),
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
        ColumnSpec("메모", width = 8000),
        ColumnSpec("사용여부", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("사용 캐릭터", width = 5000),
        ColumnSpec("사용캐릭터코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000),
        // 이름 은행 항목 자체의 안정 식별자 (F3-D) — 왕복 매칭 기준, 수정 금지
        ColumnSpec("코드", readOnly = true, width = 4000)
    )
)

fun userPresetTemplateSpec() = SheetSpec(
    sheetName = "필드 템플릿",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("설명", width = 15000),
        ColumnSpec("설정(JSON)", width = 15000),
        ColumnSpec("기본제공", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 6000),
        ColumnSpec("수정일", readOnly = true, width = 6000)
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
        ColumnSpec("정렬종류", dropdownOptions = listOf("manual", "name", "created", "recent", "field"), width = 4000),
        ColumnSpec("정렬필드키", width = 5000),
        ColumnSpec("정렬오름차순", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("신체파트번호", width = 4000),
        ColumnSpec("작품코드목록", width = 10000),
        ColumnSpec("기본값", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 6000),
        ColumnSpec("수정일", readOnly = true, width = 6000)
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
        ColumnSpec("생성일", readOnly = true, width = 6000),
        ColumnSpec("수정일", readOnly = true, width = 6000)
    )
)

fun factionSpec(universeNames: List<String> = emptyList()) = SheetSpec(
    sheetName = "세력",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("세계관", dropdownOptions = universeNames.takeIf { it.isNotEmpty() }, width = 8000),
        ColumnSpec("세계관코드", readOnly = true, width = 4000),
        ColumnSpec("설명", width = 15000),
        ColumnSpec("색상", width = 4000),
        ColumnSpec("자동관계유형", required = true, width = 6000),
        ColumnSpec("자동관계강도", width = 3000),
        ColumnSpec("코드", readOnly = true, width = 4000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("생성일", readOnly = true, width = 5000)
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
        ColumnSpec("생성일", readOnly = true, width = 5000)
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
        ColumnSpec("설명", width = 10000),
        ColumnSpec("강도", width = 3000),
        ColumnSpec("양방향", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("표시순서", width = 3000),
        ColumnSpec("세력1코드", readOnly = true, width = 4000),
        ColumnSpec("세력2코드", readOnly = true, width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 5000)
    )
)

fun appSettingsSpec() = SheetSpec(
    sheetName = "앱 설정",
    columns = listOf(
        ColumnSpec("설정키", required = true, width = 8000),
        ColumnSpec("설정값", width = 10000)
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
        ColumnSpec("링크그룹", readOnly = true, width = 9000)
    )
)
