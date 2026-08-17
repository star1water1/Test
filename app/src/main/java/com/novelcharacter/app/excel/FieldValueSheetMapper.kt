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

    /**
     * 별칭 열의 머리 — **쓰는 쪽(SheetSpec)·읽는 쪽(ExcelImportService 둘)·안내(ExcelExporter)가
     * 이 한 글자를 든다**(B-222 ②). 종전에는 넷이 `"별칭(콤마구분)"`을 각자 적고 있었고, 그래서
     * 이 개명이 *네 자리를 동시에 고치는 일*이 됐다 — 다음 개명은 이 상수 하나다.
     *
     * 접미사가 [EntityFieldHeaders.MULTI_SUFFIX]인 것이 이 항목의 요점이다. B-177이 쉼표를
     * 쪼개는 열의 표준 접미사를 ` (쉼표 구분)`으로 세웠는데 이 열만 `(콤마구분)`이라, **같은
     * 규칙을 두 말로 안내하고 있었다** — 외부 편집자는 시트마다 그 뜻을 다시 배워야 한다.
     */
    const val ALIAS_HEADER = "별칭" + EntityFieldHeaders.MULTI_SUFFIX

    /**
     * 2026.08.16 이전에 내보낸 파일의 별칭 열 머리 — **읽기 전용 폴백**(R-2의 취지).
     *
     * 실제로 이 글자를 다시 만나는 자리는 [ExcelHeaderAliases]다(거기서 [ALIAS_HEADER]의
     * 별칭으로 등재된다) — 그래야 ⓐ 옛 파일의 그 열이 새 이름으로 잡히고 ⓑ '인식하지 못해
     * 무시했습니다' 경고가 **거짓으로** 뜨지 않는다. 폴백이 없으면 이 개명 자체가
     * 이미 내보낸 파일의 별칭을 통째로 버리는 변경이 된다(개발 의도 4번).
     */
    const val LEGACY_ALIAS_HEADER = "별칭(콤마구분)"

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

    /** 별칭 결합 — 쉼표를 품은 별칭은 감싼다 (B-27 ② · 규약 R-47). */
    fun aliasesToCsv(entry: FieldValueEntry): String = joinCsv(entry.aliases())

    /**
     * 콤마 구분 별칭 파싱 — 전각 콤마(，·、)도 수용, trim·중복 제거.
     * 쪼개기 규칙 자체는 [splitCsv] 단일 소스가 든다(따옴표로 감싼 별칭이 파손되지 않게).
     * `、`는 [toHalfWidth]의 범위(U+FF01–FF5E) 밖이라 여기서 먼저 낮춘다 — 이 시트 고유의 관대함이다.
     */
    fun csvToAliases(csv: String?): List<String> =
        splitCsv(csv.orEmpty().replace('、', ',')).distinct()

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
     * 시트 한 행이 가리키는 기존 엔트리 — **코드 우선, 없으면 같은 필드 안의 같은 값.**
     * 코드가 적혀 있으나 그 코드를 가진 형제가 없으면 값으로 되돌아간다(외부에서 코드만 지운 파일).
     *
     * [siblings]는 **같은 필드(fieldDefinitionId)의 엔트리만**이어야 한다 —
     * 전체 엔트리에서 코드로 찾으면 다른 필드의 엔트리를 집어 온다.
     */
    fun match(siblings: List<FieldValueEntry>, row: ImportedRow): FieldValueEntry? {
        val code = row.code?.trim().orEmpty()
        if (code.isNotEmpty()) siblings.find { it.code == code }?.let { return it }
        return siblings.find { it.value == row.trimmedValue }
    }

    /**
     * [mergeRow]의 결과 — 최종 엔트리와, 가져오기가 사용자에게 고지해야 할 사실들.
     *
     * 고지 문구는 가져오기가 만들고 이 자료형은 **무엇이 일어났는가만** 든다.
     * 미리보기 분석은 같은 자료형에서 '변경/동일/건너뜀'을 읽는다.
     */
    data class MergeOutcome(
        /** 저장될 엔트리. null이면 값이 비어 매핑 자체가 불가하다. */
        val entry: FieldValueEntry?,
        /** 코드 매칭으로 값 이름이 바뀐 경우의 구 값 (별칭으로 보존된다). */
        val renamedFrom: String? = null,
        /** 다른 엔트리와 충돌해 제외된 별칭들. */
        val droppedAliases: List<String> = emptyList(),
        /** 바뀐 값을 다른 엔트리가 이미 쓰고 있다 → 가져오기는 **이 행을 건너뛴다.** */
        val valueTaken: Boolean = false
    )

    /**
     * 가져오기가 이 행으로 **실제로 저장할 것**을 [applyRow] 위에 끝까지 계산한다 —
     * 이름 변경 시 구 값 별칭 보존, 값 충돌 판정, 충돌 별칭 제외까지.
     *
     * 순서가 곧 의미다(가져오기 본문과 같다): 병합 → 이름 변경 별칭 보존 → **값 충돌이면 중단** →
     * 별칭 충돌 제외. 값 충돌 행은 가져오기가 통째로 건너뛰므로 그 뒤 단계를 밟지 않는다.
     */
    fun mergeRow(
        existing: FieldValueEntry?,
        fieldDefId: Long,
        row: ImportedRow,
        siblings: List<FieldValueEntry>
    ): MergeOutcome {
        var candidate = applyRow(existing, fieldDefId, row) ?: return MergeOutcome(null)
        var renamedFrom: String? = null
        if (existing != null && existing.value != candidate.value) {
            renamedFrom = existing.value
            candidate = candidate.copy(
                aliasesJson = FieldValueEntry.aliasesToJson(candidate.aliases() + existing.value)
            )
        }
        if (siblings.any { it.id != candidate.id && it.value == candidate.value }) {
            return MergeOutcome(candidate, renamedFrom, valueTaken = true)
        }
        val conflicts = conflictingAliases(candidate, siblings)
        if (conflicts.isNotEmpty()) {
            candidate = candidate.copy(
                aliasesJson = FieldValueEntry.aliasesToJson(candidate.aliases().filter { it !in conflicts })
            )
        }
        return MergeOutcome(candidate, renamedFrom, conflicts)
    }

    /** 이 행이 가져오기에서 실제로 하는 일 — 복원 미리보기가 세는 단위다. */
    enum class RowEffect { NEW, UPDATED, UNCHANGED, SKIPPED }

    /**
     * 이 행을 넣으면 실제로 무슨 일이 일어나는가 — 미리보기의 '신규/변경/동일'을 가르는 자리(B-87).
     * 가져오기가 쓰는 [mergeRow]와 **같은 함수**로 판정하므로 예고와 실제가 어긋나지 않는다.
     *
     * **`updatedAt`은 비교하지 않는다.** 갱신 시각은 가져오기가 무조건 새로 찍는 기록용 값이라
     * 이것까지 세면 '동일'이 구조적으로 도달 불가능해져 B-87을 고치는 의미가 없어진다.
     *
     * [SKIPPED]는 가져오기가 그 행을 통째로 건너뛰는 경우다(값이 비었거나, 바뀐 값을 다른
     * 엔트리가 이미 쓰고 있다) — 바뀌는 것이 없으므로 '변경'도 '동일'도 아니다.
     */
    fun effectOf(
        existing: FieldValueEntry?,
        fieldDefId: Long,
        row: ImportedRow,
        siblings: List<FieldValueEntry>
    ): RowEffect = effectOf(existing, mergeRow(existing, fieldDefId, row, siblings))

    /**
     * 이미 계산한 [mergeRow] 결과에서 같은 판정을 읽는다.
     *
     * **판정과 결과를 둘 다 쓰는 호출부를 위한 자리다**(복원 미리보기 — 세고 나서 그 결과를
     * 형제 목록에 되돌려 놓는다). 위 갈래를 그대로 부르면 **행마다 [mergeRow]가 두 번** 돌고,
     * 그 안의 [conflictingAliases]는 형제 전부의 별칭으로 집합을 짓는 O(형제) 작업이라
     * 값이 많은 필드에서 그대로 두 배가 된다.
     *
     * **판정 자체는 여기 한 벌뿐이고 위 갈래가 이것을 부른다** — 갈래를 손으로 두 벌 적으면
     * 그것이 바로 R-33이 없애려던 모양이다.
     */
    fun effectOf(existing: FieldValueEntry?, outcome: MergeOutcome): RowEffect {
        val merged = outcome.entry ?: return RowEffect.SKIPPED
        if (outcome.valueTaken) return RowEffect.SKIPPED
        if (existing == null) return RowEffect.NEW
        return if (merged.copy(updatedAt = existing.updatedAt) != existing) RowEffect.UPDATED
        else RowEffect.UNCHANGED
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
