package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 이 필드의 셀을 **날짜 힌트로 읽는가**(`getCellString(dateHint = …)`) — 가져오기와 미리보기의
 * 단일 소스.
 *
 * ## 왜 힌트가 필요한가
 *
 * 생일 칸에 `5-30`을 적으면 **엑셀이 그것을 날짜 셀로 바꾼다**(값은 시리얼 숫자, 서식만 날짜).
 * 힌트 없이 읽으면 그 칸은 `45812` 같은 수가 되고, 힌트를 주면 `05-30`이 된다. 앱이 내보내는
 * 생일은 글자라 이 갈래는 **사용자가 손댄 파일에서만** 열리는데, 그것이 이 칸의 흔한 경로다.
 *
 * ## 왜 여기 사는가 — 한쪽만 힌트를 주면 갈린다 (R-33)
 *
 * 가져오기는 힌트를 줬고 **미리보기는 안 줬다.** 그래서 날짜 셀이 된 생일 한 칸에서 두 쪽이
 * **다른 글자를 보고** 서로 다른 답을 냈다 — 미리보기가 *"바뀐다"*고 예고한 칸을 가져오기가
 * 그대로 두거나(`45812` vs 저장된 `05-30`), 반대로 *"동일"*이라 한 칸을 덮는다.
 * 규약 R-33이 없애려는 그 갈림이고, 판정이 두 자리에 손으로 적혀 있던 것이 원인이다.
 *
 * `null`(어느 필드에도 안 붙은 열)은 힌트가 없다 — 붙을 필드가 없으면 그 열의 값은 버려진다.
 */
fun readsCellAsDate(field: FieldDefinition?): Boolean = BirthDateFormat.isBirthDateField(field)

/** [readsCellAsDate]의 열 단위 짝 — 미리보기는 필드가 아니라 열의 처분을 들고 돈다. */
fun ColumnFieldOutcome.readsCellAsDate(): Boolean =
    readsCellAsDate((this as? ColumnFieldOutcome.Matched)?.field)

/**
 * 읽어 온 셀 글자를 그 필드의 **저장 모양**으로 다듬는다 — 지금 대상은 생일 하나다.
 *
 * ## 왜 읽는 자리에서 다듬는가 — 값 라이브러리가 먼저 지나간다
 *
 * 종전에는 파일이 말한 글자를 그대로 저장하고, 가져오기 꼬리의 시맨틱 동기화가 나중에
 * 저장 모양으로 되썼다. 그 사이에 **값 라이브러리 수확이 지나간다**(`harvestForCharacter`는
 * 저장 직후에 돈다) — 그러면 라이브러리에 `5-30`이 한 줄 남고, 뒤이어 값은 `05-30`이 되어
 * **아무 캐릭터도 쓰지 않는 값**이 자동완성·통계 목록에 서 있게 된다.
 *
 * 읽는 자리에서 다듬으면 저장·수확·동기화가 **처음부터 같은 글자**를 본다. 무편집 왕복도
 * 그때 비로소 멱등이 된다 — 옛 파일의 `5-30`은 한 번 `05-30`으로 들어오고, 같은 파일을 다시
 * 들여도 그 칸은 '동일'이다(종전에는 들일 때마다 '변경'이었다).
 *
 * **읽을 수 없는 글자는 그대로 둔다** — 우리가 못 읽는다고 사용자가 적은 것을 바꾸지 않는다.
 */
fun normalizeFieldCell(field: FieldDefinition?, raw: String): String =
    if (BirthDateFormat.isBirthDateField(field)) BirthDateFormat.canonicalOrNull(raw) ?: raw else raw

/** [normalizeFieldCell]의 열 단위 짝 — [ColumnFieldOutcome.readsCellAsDate]와 같은 이유다. */
fun ColumnFieldOutcome.normalizeFieldCell(raw: String): String =
    normalizeFieldCell((this as? ColumnFieldOutcome.Matched)?.field, raw)

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
 * ## 사다리의 순서 — **기대 헤더 정확 일치**가 최우선, 그 아래가 관대 폴백
 *
 * 0. **내보내기가 만들 머리와 글자까지 같은가**([expectedHeaders] 인자 — 단일 소스는
 *    `excel/CharacterFieldHeaders`). 걸리면 즉시 확정한다.
 * 1. `이름(필드키)` 병기 머리 — 내보내기가 이름 충돌·동명 시 붙인다
 * 2. `key` 완전 일치 → 대소문자 무시 일치
 * 3. `name` 일치 — **후보가 유일할 때만**(둘이면 근거 없이 고르지 않는다)
 *
 * 그 뒤 **단사(injective) 보장**이 온다: 한 필드에 두 열이 붙으면 **앞 열만** 남긴다.
 *
 * **0단이 왜 맨 위인가** — 1단의 정규식 `^(.+)\((.+)\)$`는 *이름 자체가 괄호로 끝나는
 * 필드*를 병기 머리로 오인한다. `총합(마력)`이라는 이름의 필드가 있고 같은 목록에 키가
 * `마력`인 다른 필드가 있으면, 그 열이 **남의 필드로 확정된다**(무편집 왕복 한 번에 값이
 * 뒤바뀌고 고지도 없다). 형제 시트는 이 위험을 명시로 다루고 같은 순서를 골랐다
 * ([com.novelcharacter.app.excel.EntityFieldHeaders] 머리). 1~3단은 **손편집·구버전 머리**를
 * 받는 폴백으로 남는다 — 그 자리에서만 "키가 이름보다 앞선다"가 성립한다.
 */
object CharacterFieldColumns {

    /** `이름(필드키)` 병기 머리에서 괄호 안을 집는다. */
    private val KEYED_HEADER = Regex("""^(.+)\((.+)\)$""")

    /**
     * @param headers 열 번호 → 열 머리 원문. 빈 머리는 부르는 쪽이 빼고 넣는다.
     * @param fields 이 시트가 볼 수 있는 필드 정의 — **미리보기는 이 파일이 앞서 만들 필드도 함께
     *   넣는다**(가져오기는 '필드 정의' 시트를 먼저 처리하므로 그때 이미 DB에 있다).
     * @param expectedHeaders 내보내기가 [fields]로 만들 머리 → 필드
     *   (`CharacterFieldHeaders.expectedHeaders`). **기본값을 두지 않는다** — 두면 부르는 쪽이
     *   이 단을 조용히 건너뛰고, 그 순간 무편집 왕복이 다시 깨진다.
     * @param fixedColIndices 고정 열 번호 — 커스텀 필드로 오인되면 안 되는 자리.
     * @param fixedHeaders 고정 열 머리 글자 집합 — 2차 방어다. 같은 고정 머리가 두 번 든 파일에서
     *   [fixedColIndices]에 안 잡힌 잔존 열이 가짜 필드를 만드는 것을 막는다.
     * @param hasUniverse 세계관 시트인가. 미분류 시트는 자동 생성을 할 수 없다.
     * @param multiSuffix 다중값 열 머리의 접미사 — 붙어 있으면 떼고 대조한다.
     */
    fun plan(
        headers: Map<Int, String>,
        fields: List<FieldDefinition>,
        expectedHeaders: Map<String, FieldDefinition>,
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
            // **원문 먼저, 접미사 제거는 그 다음이다** — [EntityFieldHeaders.parseBody]가 이미 든
            // 두 단 사다리와 같은 순서다. 필드 이름이 실제로 `성격 (쉼표 구분)`일 수 있고, 그때
            // 접미사를 먼저 벗기면 있지도 않은 `성격`을 찾다가 값이 엉뚱한 필드에 붙는다(R-1).
            // 종전에는 이 시트만 무조건 벗겨서, **한 글자도 고치지 않은 왕복**만으로 그 필드의
            // 값이 새로 만들어진 딴 필드로 옮겨 붙었다(같은 값이 두 필드에 갈려 통계가 두 번 센다).
            val raw = headerName.trim()
            val stripped = raw.removeSuffix(multiSuffix)
            if (raw in fixedHeaders || stripped in fixedHeaders) continue

            // 0단 — 내보내기가 적은 그 글자인가. 걸리면 사다리를 타지 않는다(위 KDoc).
            val matched = expectedHeaders[raw]
                ?: matchField(raw, fields) ?: matchField(stripped, fields)
            out[col] = when {
                matched != null -> ColumnFieldOutcome.Matched(matched)
                else -> {
                    // 모호 고지의 이름표는 **후보를 센 그 글자**여야 한다 — 원문으로 후보가
                    // 잡혔으면 원문을, 아니면 접미사를 뗀 글자를 센다.
                    val rawCount = countByName(raw, fields)
                    val label = if (rawCount > 0) raw else stripped
                    val sameName = if (rawCount > 0) rawCount else countByName(stripped, fields)
                    when {
                        sameName > 1 -> ColumnFieldOutcome.Ambiguous(label, sameName)
                        // 새로 만드는 이름은 접미사를 뗀 것이다 — `태그 (쉼표 구분)` 열이
                        // 없는 필드를 가리키면 만들 것은 `태그`다(종전 동작 그대로).
                        hasUniverse -> ColumnFieldOutcome.AutoCreate(stripped)
                        else -> ColumnFieldOutcome.Unresolved(stripped)
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
