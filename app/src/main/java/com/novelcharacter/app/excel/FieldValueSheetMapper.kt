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

    fun entityLabel(entityType: String): String =
        if (entityType == FieldDefinition.ENTITY_EVENT) ENTITY_LABEL_EVENT else ENTITY_LABEL_CHARACTER

    /** "사건"/"event"만 사건, 그 외(빈 값 포함)는 캐릭터 — 구버전·수기 편집 관대 수용 */
    fun entityTypeOf(label: String?): String =
        when (label?.trim()?.lowercase()) {
            ENTITY_LABEL_EVENT, "event" -> FieldDefinition.ENTITY_EVENT
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

    fun parseHidden(flag: String?): Boolean =
        when (flag?.trim()?.lowercase()) {
            "y", "yes", "true", "예", "숨김", "1" -> true
            else -> false
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
        val code: String?
    ) {
        val entityType: String get() = entityTypeOf(entityLabel)
        val trimmedValue: String get() = value.trim()
    }

    /**
     * 임포트 병합: 기존 엔트리가 있으면 시트 내용으로 갱신(외부 편집이 최신),
     * 없으면 신규 생성(source=IMPORT). 코드가 유효하면 보존해 다음 왕복의 매칭 기준이 된다.
     * 반환 null = 값이 비어 매핑 불가 (호출측이 경고 리포트).
     */
    fun applyRow(existing: FieldValueEntry?, fieldDefId: Long, row: ImportedRow): FieldValueEntry? {
        val value = row.trimmedValue
        if (value.isEmpty()) return null
        val aliases = csvToAliases(row.aliasesCsv).filter { it != value }
        return if (existing != null) {
            existing.copy(
                value = value,
                displayLabel = row.displayLabel.orEmpty().trim(),
                aliasesJson = FieldValueEntry.aliasesToJson(aliases),
                category = row.category.orEmpty().trim(),
                description = row.description.orEmpty().trim(),
                isHidden = parseHidden(row.hiddenFlag),
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
                source = FieldValueEntry.SOURCE_IMPORT
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
