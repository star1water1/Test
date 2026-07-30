package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry

/**
 * "필드 데이터" 시트 행 ↔ FieldValueEntry 매핑 (순수 JVM — 단위 테스트 대상).
 *
 * 왕복 무결성 원칙: 무편집 왕복은 데이터를 바꾸지 않고, 외부 편집의 형식 이탈은
 * 거부가 아니라 관대 수용(전각 콤마, Y/y/예 등)으로 처리한다.
 */
object FieldValueSheetMapper {

    const val ENTITY_LABEL_CHARACTER = "캐릭터"
    const val ENTITY_LABEL_EVENT = "사건"
    const val ENTITY_LABEL_NOVEL = "작품"

    /** 시트의 '대상' 열이 쓰는 라벨 — 드롭다운 목록도 이 값을 단일 소스로 삼는다(SheetSpec). */
    val ENTITY_LABELS = listOf(ENTITY_LABEL_CHARACTER, ENTITY_LABEL_EVENT, ENTITY_LABEL_NOVEL)

    fun entityLabel(entityType: String): String = when (entityType) {
        FieldDefinition.ENTITY_EVENT -> ENTITY_LABEL_EVENT
        FieldDefinition.ENTITY_NOVEL -> ENTITY_LABEL_NOVEL
        else -> ENTITY_LABEL_CHARACTER
    }

    /**
     * "사건"/"event"는 사건, "작품"/"novel"은 작품, 그 외(빈 값 포함)는 캐릭터 —
     * 구버전·수기 편집 관대 수용. **캐릭터가 기본값인 것은 이 열이 없던 파일 때문이다** —
     * 종류를 늘릴 때 여기에 등재하지 않으면 그 종류의 정의가 왕복에서 캐릭터로 접힌다(R-29).
     */
    fun entityTypeOf(label: String?): String =
        when (label?.trim()?.lowercase()) {
            ENTITY_LABEL_EVENT, "event" -> FieldDefinition.ENTITY_EVENT
            ENTITY_LABEL_NOVEL, "novel" -> FieldDefinition.ENTITY_NOVEL
            else -> FieldDefinition.ENTITY_CHARACTER
        }

    fun aliasesToCsv(entry: FieldValueEntry): String = entry.aliases().joinToString(", ")

    /** 콤마 구분 별칭 파싱 — 전각 콤마(，·、)도 수용, trim·중복 제거 */
    fun csvToAliases(csv: String?): List<String> =
        csv.orEmpty()
            .replace('，', ',')
            .replace('、', ',')
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 불리언 판정은 [parseSheetBoolean](SheetSpec.kt) 단일 소스에 위임한다 — 시트마다 다른
     * 토큰 집합을 두면 같은 Y/N 열이 시트에 따라 다르게 읽힌다. '숨김'만 이 시트 고유 토큰이다.
     */
    fun parseHidden(flag: String?): Boolean =
        flag != null && (parseSheetBoolean(flag) || flag.trim() == "숨김")

    /**
     * 출처 셀 3상태 — F1-A와 오타 교정을 구분한다.
     * 열 없음(Absent) / 열 있음+빈칸(Blank) / 인식 값(Known) / 인식 불가(Unknown).
     * Blank를 "기존 유지"로 두면 시트별 규칙 분기가 다시 생기므로, 열 있음+빈칸은
     * 엔티티 기본값(AUTO)으로 비운다(불리언 열 규약과 동형).
     */
    sealed class SourceCell {
        object Absent : SourceCell()
        object Blank : SourceCell()
        data class Known(val value: String) : SourceCell()
        data class Unknown(val raw: String) : SourceCell()
    }

    fun parseSourceCell(raw: String?): SourceCell = when {
        raw == null -> SourceCell.Absent
        raw.isBlank() -> SourceCell.Blank
        else -> when (toHalfWidth(raw).trim().lowercase()) {
            "auto", "자동", "수확", "자동수집" -> SourceCell.Known(FieldValueEntry.SOURCE_AUTO)
            "manual", "수동", "직접", "직접등록", "큐레이션" -> SourceCell.Known(FieldValueEntry.SOURCE_MANUAL)
            "import", "가져오기", "엑셀" -> SourceCell.Known(FieldValueEntry.SOURCE_IMPORT)
            "ai", "에이아이", "ai정리" -> SourceCell.Known(FieldValueEntry.SOURCE_AI)
            else -> SourceCell.Unknown(raw.trim())
        }
    }

    /** 시트에서 읽은 한 행의 값 필드들 */
    data class ImportedRow(
        val universeName: String,
        val fieldKey: String,
        val entityLabel: String?,
        val value: String,
        val displayLabel: String?,
        val aliasesCsv: String?,
        val category: String?,
        val description: String?,
        val hiddenFlag: String?,
        val code: String?,
        val sourceFlag: String? = null
    ) {
        val entityType: String get() = entityTypeOf(entityLabel)
        val trimmedValue: String get() = value.trim()
    }

    /**
     * 임포트 병합: 기존 엔트리가 있으면 시트 내용으로 갱신(외부 편집이 최신),
     * 없으면 신규 생성(source=IMPORT). 코드가 유효하면 보존해 다음 왕복의 매칭 기준이 된다.
     * 반환 null = 값이 비어 매핑 불가 (호출측이 경고 리포트).
     *
     * F1-A: 선택 속성의 null은 "열 없음"(축약 시트) → 기존값 유지, 빈 문자열은
     * "열 있음 + 빈칸" → 비움 의도 존중. 구분하지 않으면 세계관·필드키·값만 남긴
     * 축약 시트 왕복에서 별칭·카테고리·설명·숨김이 통째로 지워진다.
     */
    fun applyRow(existing: FieldValueEntry?, fieldDefId: Long, row: ImportedRow): FieldValueEntry? {
        val value = row.trimmedValue
        if (value.isEmpty()) return null
        val aliases = csvToAliases(row.aliasesCsv).filter { it != value }
        val sourceCell = parseSourceCell(row.sourceFlag)
        return if (existing != null) {
            existing.copy(
                value = value,
                displayLabel = row.displayLabel?.trim() ?: existing.displayLabel,
                aliasesJson = if (row.aliasesCsv != null) FieldValueEntry.aliasesToJson(aliases) else existing.aliasesJson,
                category = row.category?.trim() ?: existing.category,
                description = row.description?.trim() ?: existing.description,
                isHidden = if (row.hiddenFlag != null) parseHidden(row.hiddenFlag) else existing.isHidden,
                // 열 없음·오타 → 기존 유지 / 빈칸 → 엔티티 기본값 / 인식 값 → 그 값
                source = when (sourceCell) {
                    is SourceCell.Known -> sourceCell.value
                    SourceCell.Blank -> FieldValueEntry.SOURCE_AUTO
                    else -> existing.source
                },
                updatedAt = System.currentTimeMillis()
            )
        } else {
            val entry = FieldValueEntry(
                fieldDefinitionId = fieldDefId,
                value = value,
                displayLabel = row.displayLabel.orEmpty().trim(),
                aliasesJson = FieldValueEntry.aliasesToJson(aliases),
                category = row.category.orEmpty().trim(),
                description = row.description.orEmpty().trim(),
                isHidden = parseHidden(row.hiddenFlag),
                // 구버전 파일(열 없음)·오타·빈칸은 정보가 없으므로 IMPORT 유지 — 기존 계약 불변
                source = (sourceCell as? SourceCell.Known)?.value ?: FieldValueEntry.SOURCE_IMPORT
            )
            val code = row.code?.trim().orEmpty()
            if (code.isNotEmpty()) entry.copy(code = code) else entry
        }
    }

    /**
     * 별칭 충돌 검사: 같은 필드의 다른 엔트리 canonical/별칭과 겹치는 별칭 목록.
     * 임포트는 거부 대신 충돌 별칭만 스킵 + 경고한다.
     */
    fun conflictingAliases(
        candidate: FieldValueEntry,
        others: List<FieldValueEntry>
    ): List<String> {
        val taken = HashSet<String>()
        for (o in others) {
            if (o.id == candidate.id) continue
            taken.add(o.value)
            taken.addAll(o.aliases())
        }
        return candidate.aliases().filter { it in taken || it == candidate.value }
    }
}
