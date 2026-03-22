package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
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

/** Fixed (non-dynamic-field) column headers in character sheets. */
val CHARACTER_FIXED_HEADERS = setOf("이름", "성", "이름(First)", "이명", "이미지경로", "작품", "메모", "태그", "코드", "작품코드", "정렬순서", "고정")

/** Default border color presets for color picker UI. */
val BORDER_COLOR_PRESETS = listOf(
    "#5C6BC0", "#FF7043", "#26A69A", "#AB47BC",
    "#EF5350", "#42A5F5", "#66BB6A", "#FFA726",
    "#EC407A", "#7E57C2", "#29B6F6", "#D4E157"
)

/** All reserved (non-universe) sheet names used by the app. */
val RESERVED_SHEET_NAMES = setOf(
    "사용 안내",
    universeSpec().sheetName,
    novelSpec(emptyList()).sheetName,
    fieldDefinitionSpec(emptyList()).sheetName,
    "미분류 캐릭터",
    timelineSpec(emptyList()).sheetName,
    stateChangeSpec().sheetName,
    relationshipSpec().sheetName,
    relationshipChangeSpec().sheetName,
    nameBankSpec().sheetName,
    userPresetTemplateSpec().sheetName,
    searchPresetSpec().sheetName,
    appSettingsSpec().sheetName
)

/** Split a comma-separated string into a trimmed, non-blank list. */
fun splitCsv(value: String): List<String> =
    value.split(",").map { it.trim() }.filter { it.isNotBlank() }

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
        ColumnSpec("이미지캐릭터ID", width = 5000),
        ColumnSpec("이미지작품ID", width = 5000)
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
        ColumnSpec("이미지캐릭터ID", width = 5000),
        ColumnSpec("테두리상속", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("고정", dropdownOptions = listOf("Y", "N"), width = 3000),
        ColumnSpec("표준연도", width = 3000)
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
        ColumnSpec("세계관코드", readOnly = true, width = 4000)
    )
)

fun characterSpec(fields: List<FieldDefinition>, novelTitles: List<String>) = SheetSpec(
    sheetName = "",  // Sheet name is set dynamically (universe name or "미분류 캐릭터")
    columns = buildList {
        add(ColumnSpec("이름", required = true, width = 6000))
        add(ColumnSpec("성", width = 4000))
        add(ColumnSpec("이름(First)", width = 4000))
        add(ColumnSpec("이명", width = 6000))
        // Dynamic field columns
        for (field in fields) {
            val options = if (field.type == "SELECT") {
                try {
                    val json = org.json.JSONObject(field.config)
                    val arr = json.optJSONArray("options")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) } else null
                } catch (_: Exception) { null }
            } else null
            add(ColumnSpec(field.name, required = field.isRequired, dropdownOptions = options))
        }
        add(ColumnSpec("이미지경로", readOnly = true, width = 4000))
        add(ColumnSpec("작품", dropdownOptions = novelTitles.takeIf { it.isNotEmpty() }, width = 6000))
        add(ColumnSpec("메모", width = 10000))
        add(ColumnSpec("태그", width = 8000))
        add(ColumnSpec("코드", readOnly = true, width = 4000))
        add(ColumnSpec("작품코드", readOnly = true, width = 4000))
        add(ColumnSpec("정렬순서", width = 3000))
        add(ColumnSpec("고정", dropdownOptions = listOf("Y", "N"), width = 3000))
    }
)

fun timelineSpec(novelTitles: List<String>) = SheetSpec(
    sheetName = "사건 연표",
    columns = listOf(
        ColumnSpec("연도", required = true, width = 3000),
        ColumnSpec("월", width = 2000),
        ColumnSpec("일", width = 2000),
        ColumnSpec("역법", width = 3000),
        ColumnSpec("사건 설명", required = true, width = 15000),
        ColumnSpec("관련 작품", dropdownOptions = novelTitles.takeIf { it.isNotEmpty() }, width = 6000),
        ColumnSpec("관련 캐릭터", width = 10000),
        ColumnSpec("관련작품코드", readOnly = true, width = 4000),
        ColumnSpec("정렬순서", width = 3000),
        ColumnSpec("임시배치", dropdownOptions = listOf("Y", "N"), width = 3000)
    )
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
        ColumnSpec("캐릭터코드", readOnly = true, width = 4000)
    )
)

fun relationshipSpec(customTypes: List<String> = emptyList()) = SheetSpec(
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
        ColumnSpec("캐릭터2코드", readOnly = true, width = 4000)
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
        ColumnSpec("연결사건ID", width = 4000),
        ColumnSpec("캐릭터1코드", readOnly = true, width = 4000),
        ColumnSpec("캐릭터2코드", readOnly = true, width = 4000)
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
        ColumnSpec("사용캐릭터코드", readOnly = true, width = 4000)
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

fun searchPresetSpec() = SheetSpec(
    sheetName = "검색 프리셋",
    columns = listOf(
        ColumnSpec("이름", required = true, width = 8000),
        ColumnSpec("검색어", width = 10000),
        ColumnSpec("필터(JSON)", width = 15000),
        ColumnSpec("정렬모드", dropdownOptions = listOf("relevance", "name", "tag", "recent"), width = 5000),
        ColumnSpec("기본값", dropdownOptions = listOf("Y", "N"), width = 4000),
        ColumnSpec("생성일", readOnly = true, width = 6000),
        ColumnSpec("수정일", readOnly = true, width = 6000)
    )
)

fun appSettingsSpec() = SheetSpec(
    sheetName = "앱 설정",
    columns = listOf(
        ColumnSpec("설정키", required = true, width = 8000),
        ColumnSpec("설정값", width = 10000)
    )
)
