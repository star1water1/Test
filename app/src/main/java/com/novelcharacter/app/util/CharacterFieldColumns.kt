package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 캐릭터 시트의 **동적 필드 열 하나**가 무엇에 대응하는가 (B-187).
 *
 * [CharacterFieldColumns.plan]이 열마다 하나씩 낸다. 가져오기는 이것을 받아 실제로 쓰고
 * (자동 생성은 DB 삽입까지 간다), 미리보기는 **같은 결과를 받아 세기만** 한다.
 */
sealed class ColumnFieldOutcome {

    /** 기존(또는 이 파일이 앞서 만든) 필드에 붙었다. */
    data class Matched(val field: FieldDefinition) : ColumnFieldOutcome()

    /**
     * 어느 필드에도 붙지 않았고 세계관이 있어 **가져오기가 TEXT 필드를 새로 만든다.**
     * 새 필드에는 기존 값이 있을 수 없으므로 이 열의 값 있는 칸은 전부 '신규'다.
     */
    data class AutoCreate(val header: String) : ColumnFieldOutcome()

    /** 이름이 같은 필드가 둘 이상이라 확정하지 못했다 — 가져오기도 이 열을 버린다. */
    data class Ambiguous(val header: String, val candidates: Int) : ColumnFieldOutcome()

    /** 미분류 시트(세계관 없음)라 자동 생성도 못 한다 — 가져오기가 무시한다. */
    data class Unresolved(val header: String) : ColumnFieldOutcome()

    /**
     * 앞 열([keptColumn])이 이미 같은 필드에 붙어 이 열은 버려진다(단사 보장).
     * 두 열이 한 필드에 쓰이면 앞 열 값이 뒤 열 값으로 조용히 덮이기 때문이다.
     */
    data class Duplicate(val keptColumn: Int, val field: FieldDefinition) : ColumnFieldOutcome()
}

/**
 * 캐릭터 시트의 열 머리 → 필드 정의 **해석 사다리** — 가져오기와 미리보기가 함께 쓴다(규약 R-33).
 *
 * ## 왜 순수로 내렸는가
 *
 * 이 사다리는 `ExcelImportService.buildColumnFieldMap` 안에 있었고, 그 함수는 **해석하면서 동시에
 * DB에 자동 필드를 만든다.** 그래서 미리보기가 부를 수 없었고, 필드값을 세려면 같은 사다리를
 * **손으로 한 벌 더** 짜야 했다 — R-33이 없애려는 바로 그 모양이다(B-187).
 *
 * 갈라 놓으면 둘 다 이것을 부른다: 가져오기는 [ColumnFieldOutcome.AutoCreate]를 받아 실제로
 * 필드를 만들고, 미리보기는 같은 결과를 *"새 필드가 생긴다 → 이 열의 값은 전부 신규"*로 센다.
 * **자동 생성을 모형화하지 않으면 미리보기가 크게 어긋난다** — '필드 정의' 시트가 없는 파일에서는
 * 커스텀 열 **전부**가 이 갈래로 가고, 그러면 미리보기는 "필드값 0건"이라 말하면서 가져오기는
 * 수천 건을 쓴다.
 *
 * ## 사다리의 순서 — 안정 식별자 우선, 자연키 폴백
 *
 * 1. `이름(필드키)` 병기 머리 — 내보내기가 이름 충돌·동명 시 붙인다
 * 2. `key` 완전 일치 → 대소문자 무시 일치
 * 3. `name` 일치 — **후보가 유일할 때만**(둘이면 근거 없이 고르지 않는다)
 *
 * 그 뒤 **단사(injective) 보장**이 온다: 한 필드에 두 열이 붙으면 **앞 열만** 남긴다.
 */
object CharacterFieldColumns {

    /** `이름(필드키)` 병기 머리에서 괄호 안을 집는다. */
    private val KEYED_HEADER = Regex("""^(.+)\((.+)\)$""")

    /**
     * @param headers 열 번호 → 열 머리 원문. 빈 머리는 부르는 쪽이 빼고 넣는다.
     * @param fields 이 시트가 볼 수 있는 필드 정의 — **미리보기는 이 파일이 앞서 만들 필드도 함께
     *   넣는다**(가져오기는 '필드 정의' 시트를 먼저 처리하므로 그때 이미 DB에 있다).
     * @param fixedColIndices 고정 열 번호 — 커스텀 필드로 오인되면 안 되는 자리.
     * @param fixedHeaders 고정 열 머리 글자 집합 — 2차 방어다. 같은 고정 머리가 두 번 든 파일에서
     *   [fixedColIndices]에 안 잡힌 잔존 열이 가짜 필드를 만드는 것을 막는다.
     * @param hasUniverse 세계관 시트인가. 미분류 시트는 자동 생성을 할 수 없다.
     * @param multiSuffix 다중값 열 머리의 접미사 — 붙어 있으면 떼고 대조한다.
     */
    fun plan(
        headers: Map<Int, String>,
        fields: List<FieldDefinition>,
        fixedColIndices: Set<Int>,
        fixedHeaders: Set<String>,
        hasUniverse: Boolean,
        multiSuffix: String
    ): Map<Int, ColumnFieldOutcome> {
        val out = LinkedHashMap<Int, ColumnFieldOutcome>()
        // 열 번호 오름차순으로 본다 — 단사 보장이 "앞 열을 남긴다"라 순서가 답을 정한다.
        for (col in headers.keys.sorted()) {
            if (col in fixedColIndices) continue
            val headerName = headers[col].orEmpty()
            if (headerName.isBlank()) continue
            val trimmed = headerName.trim().removeSuffix(multiSuffix)
            if (trimmed in fixedHeaders) continue

            val matched = matchField(trimmed, fields)
            out[col] = when {
                matched != null -> ColumnFieldOutcome.Matched(matched)
                else -> {
                    val sameName = countByName(trimmed, fields)
                    when {
                        sameName > 1 -> ColumnFieldOutcome.Ambiguous(trimmed, sameName)
                        hasUniverse -> ColumnFieldOutcome.AutoCreate(trimmed)
                        else -> ColumnFieldOutcome.Unresolved(trimmed)
                    }
                }
            }
        }

        // 단사 보장 — 한 필드에 둘 이상의 열이 붙으면 **앞 열만** 남기고 뒤 열은 버린다.
        // 자동 생성 열은 매번 다른 key를 받으므로 여기 걸리지 않는다.
        val firstColumnOfField = HashMap<Long, Int>()
        for (col in out.keys.sorted()) {
            val field = (out[col] as? ColumnFieldOutcome.Matched)?.field ?: continue
            val kept = firstColumnOfField.putIfAbsent(field.id, col)
            if (kept != null) out[col] = ColumnFieldOutcome.Duplicate(kept, field)
        }
        return out
    }

    /**
     * 사다리 본체 — 확정되면 그 필드, 아니면 `null`.
     * **동명 필드가 둘 이상이면 여기서도 `null`이다**(부르는 쪽이 [ColumnFieldOutcome.Ambiguous]와
     * 자동 생성을 가른다).
     */
    private fun matchField(trimmed: String, fields: List<FieldDefinition>): FieldDefinition? {
        KEYED_HEADER.find(trimmed)?.let { keyed ->
            val k = keyed.groupValues[2].trim()
            (fields.find { it.key == k } ?: fields.find { it.key.equals(k, ignoreCase = true) })
                ?.let { return it }
        }
        fields.find { it.key == trimmed }?.let { return it }
        fields.find { it.key.equals(trimmed, ignoreCase = true) }?.let { return it }
        val byName = fields.filter { it.name == trimmed }
            .ifEmpty { fields.filter { it.name.equals(trimmed, ignoreCase = true) } }
        return byName.singleOrNull()
    }

    /** 이름으로만 대조했을 때의 후보 수 — 확정 실패가 *모호*인지 *없음*인지 가른다. */
    private fun countByName(trimmed: String, fields: List<FieldDefinition>): Int {
        val exact = fields.count { it.name == trimmed }
        return if (exact > 0) exact else fields.count { it.name.equals(trimmed, ignoreCase = true) }
    }
}
