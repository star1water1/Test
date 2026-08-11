package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.FieldValueTokenizer

/**
 * **한 시트가 전 세계관의 커스텀 필드를 동적 열로 싣는 시트**의 열 헤더 규칙 —
 * 내보내기와 가져오기의 단일 소스 (순수 JVM, 단위 테스트 대상).
 *
 * 쓰는 곳은 둘이다: **연표 시트의 사건 필드**(B-10)와 **작품 시트의 작품 필드**(확-3).
 * 두 시트 모두 세계관별로 나뉘지 않으므로(캐릭터 시트와 다르다) 같은 규칙이 그대로 성립한다 —
 * 규칙을 시트마다 따로 두면 반드시 드리프트한다(7장 규약).
 *
 * 헤더는 `필드:{이름}`이고, 같은 이름의 필드가 여러 구역에 있을 때만 `필드:{이름}({구역})`으로
 * 한정한다. 가져오기가 이 규칙을 정규식으로 되짚으면 이름이 괄호로 끝나는 필드(`규모(명)`)를 세계관
 * 한정으로 오인해 열 전체가 버려지므로, **기대 헤더 → 필드 정확 일치**를 최우선으로 둔다(무편집 왕복 무결).
 * 정확 일치에 실패한 헤더(손편집·구버전)만 관대 폴백으로 해석한다.
 *
 * ## 2026.08.11(6판)에 셋이 바뀌었다 — **쓰기는 하나, 읽기는 둘**(R-2의 취지)
 *
 * ⓐ **쉼표를 쪼개는 필드에는 `(쉼표 구분)` 접미사를 붙인다**(B-177). 캐릭터 시트는 B-38에서
 *   이미 붙였는데 이 두 시트에는 분기가 처음부터 없어서, 사건·작품 필드는 외부 편집자가 구분자를
 *   모른 채 값을 넣었다 — 원칙 04(*"일일이 확인하지 않으면 존재를 알 수 없는 데이터가 있어서는
 *   안 된다"*)가 정한 자리다. 판정자는 앱이 이미 쓰는 단일 소스([FieldValueTokenizer.isMultiToken])다.
 * ⓑ **전역(무소속) 구역의 한정자가 `null`이었다.** 종전에는 `universeLabelById[f.universeId]
 *   ?: f.universeId.toString()`이라 전역 필드가 이름 충돌을 겪으면 열 머리에 **`필드:이름(null)`**이
 *   그대로 나갔다(B-129 곁다리). 사람이 읽는 열 머리에 `null`이 뜨는 것 자체가 스타일 위반이고,
 *   B-65가 한정자로 구역을 되짚기 시작하면 *"null이라는 이름의 세계관"*을 찾게 된다.
 * ⓒ **읽기는 옛 헤더도 받는다** — [expectedHeaders]가 새 머리와 접미사 없는 옛 머리를 함께 담고,
 *   [parseFallback]이 접미사를 벗긴 뒤 같은 사다리를 다시 탄다. 이것이 없으면 이미 내보낸 파일의
 *   그 열이 통째로 '알 수 없는 열'이 되어 **값이 조용히 버려진다**(개발 의도 4번).
 */
object EntityFieldHeaders {

    const val PREFIX = "필드:"

    /**
     * 다중값 필드의 열 머리 접미사 (B-177). 캐릭터 시트(`SheetSpec.characterSpec`)와 **같은 글자**다 —
     * 두 시트가 다른 말로 같은 규칙을 안내하면 외부 편집자가 시트마다 다시 배워야 한다.
     */
    const val MULTI_SUFFIX = " (쉼표 구분)"

    /**
     * 전역(무소속) 구역의 한정자. 사람이 읽는 말이라 `null`도 id도 쓸 수 없고,
     * 되짚을 수 있어야 하므로 [parseFallback]이 이 글자를 구역으로 알아본다.
     *
     * **실존 세계관 이름이 이 글자와 같으면 세계관이 이긴다** — 정확 일치 표가 먼저 걸리므로
     * 무편집 왕복은 어느 쪽이든 무결하고, 폴백에서만 갈리는데 그때는 *있는 것*을 고르는 쪽이 안전하다.
     */
    const val GLOBAL_QUALIFIER = "전역"

    /** 헤더 생성 — [ambiguous]는 같은 이름의 필드가 2개 이상일 때 true */
    fun header(fieldName: String, universeLabel: String?, ambiguous: Boolean, multiToken: Boolean = false): String {
        val core = if (ambiguous && !universeLabel.isNullOrBlank()) {
            "$PREFIX$fieldName($universeLabel)"
        } else {
            "$PREFIX$fieldName"
        }
        return if (multiToken) core + MULTI_SUFFIX else core
    }

    /**
     * 구역 이름 — 세계관이면 그 이름, 전역이면 [GLOBAL_QUALIFIER].
     *
     * **이름을 못 찾은 세계관은 전역으로 부르지 않는다.** 있을 수 없는 갈래이지만(내보내기가 전
     * 세계관으로 맵을 만든다), 그 자리를 `전역`으로 적으면 가져오기가 그 값을 **전역 구역의
     * 동명 필드에 붙인다** — 오배정은 생략보다 나쁘다(R-1). id를 적어 두면 정확 일치 표로만
     * 되짚히고 폴백에서는 이름의 일부로 남아 경고와 함께 멈춘다(종전 동작 그대로).
     */
    fun scopeLabel(field: FieldDefinition, universeLabelById: Map<Long, String>): String {
        val id = field.universeId ?: return GLOBAL_QUALIFIER
        return universeLabelById[id] ?: id.toString()
    }

    /** 필드 목록 → (필드, 헤더) 목록. 내보내기가 열을 만들 때 쓰는 규칙 그 자체. */
    fun headersFor(
        fields: List<FieldDefinition>,
        universeLabelById: Map<Long, String>
    ): List<Pair<FieldDefinition, String>> {
        val nameCounts = fields.groupingBy { it.name }.eachCount()
        return fields.map { f ->
            f to header(
                f.name,
                scopeLabel(f, universeLabelById),
                (nameCounts[f.name] ?: 0) > 1,
                FieldValueTokenizer.isMultiToken(f)
            )
        }
    }

    /**
     * 내보내기가 실제로 그릴 수 있는 열과, **열로 담을 수 없어 오버플로 시트로 가는 필드**를 가른다 (B-65).
     *
     * 같은 헤더를 두 필드가 만들 수 있다 — 유니크 색인은 `(구역, 대상, 키)`라 **한 구역에 같은
     * *이름*의 필드가 둘 있을 수 있고**, 그러면 한정자를 붙여도 두 머리가 글자까지 같아진다.
     * 종전에는 그 상태로 **같은 이름의 열이 두 개 그려졌고** 가져오기는 뒤쪽 열을 경고와 함께
     * 버렸다 — 즉 그 필드의 값은 내보내도 결코 되돌아오지 못했다. 이제 열은 하나만 그리고
     * 나머지는 오버플로 시트가 (구역, 필드키, 대상) 삼중키로 담는다.
     */
    data class Plan(
        /** 시트에 그릴 열 — 헤더가 유일하다. */
        val columns: List<Pair<FieldDefinition, String>>,
        /** 열로 담긴 필드 id — 오버플로 선별의 `covered`가 이 집합이다. */
        val coveredFieldIds: Set<Long>
    )

    fun plan(
        fields: List<FieldDefinition>,
        universeLabelById: Map<Long, String>
    ): Plan {
        val columns = ArrayList<Pair<FieldDefinition, String>>(fields.size)
        val covered = LinkedHashSet<Long>()
        val takenHeaders = HashSet<String>()
        for ((field, header) in headersFor(fields, universeLabelById)) {
            if (!takenHeaders.add(header)) continue   // 앞 필드가 이미 그 머리를 가졌다 — 오버플로 몫
            columns.add(field to header)
            covered.add(field.id)
        }
        return Plan(columns, covered)
    }

    /**
     * 기대 헤더 → 필드 (가져오기의 정확 일치 조회용). 헤더가 겹치면 먼저 온 필드가 이긴다.
     *
     * **새 머리와 옛 머리를 함께 담는다**(B-177 ⓒ) — 접미사가 붙기 전에 내보낸 파일이 이미 있고,
     * 그 파일의 `필드:성격`은 지금 규칙으로는 `필드:성격 (쉼표 구분)`이라 정확 일치에 실패한다.
     * **새 머리를 먼저 다 넣고 옛 머리를 뒤에 넣는 것이 순서의 요점이다** — `성격 (쉼표 구분)`이라는
     * *이름*의 필드가 실제로 있으면 그 필드가 자기 머리를 가져야 하고, 옛 머리 폴백은 남은 자리에만 든다.
     */
    fun expectedHeaders(
        fields: List<FieldDefinition>,
        universeLabelById: Map<Long, String>
    ): Map<String, FieldDefinition> {
        val map = LinkedHashMap<String, FieldDefinition>()
        val pairs = headersFor(fields, universeLabelById)
        for ((field, header) in pairs) {
            if (header !in map) map[header] = field
        }
        for ((field, header) in pairs) {
            if (!header.endsWith(MULTI_SUFFIX)) continue
            val legacy = header.removeSuffix(MULTI_SUFFIX)
            if (legacy !in map) map[legacy] = field
        }
        return map
    }

    /** 폴백 파싱 결과 — [universeName]이 null이면 세계관 한정이 없는 열 */
    data class Parsed(val fieldName: String, val universeName: String?)

    private val QUALIFIED = Regex("""^(.+)\((.+)\)$""")

    /**
     * 정확 일치에 실패한 헤더의 관대 해석. 순서:
     * ① 본문 전체가 실존 필드명이면 그대로(이름에 괄호가 있어도 안전)
     * ② `(쉼표 구분)` 접미사가 붙어 있으면 벗기고 같은 사다리를 다시 탄다 (B-177)
     * ③ 괄호 접미사가 **실존 세계관명**(또는 전역 한정자)일 때만 한정자로 분해
     * ④ 그 외에는 본문 전체를 필드명으로 간주
     * 접두사가 없는 헤더는 필드 열이 아니므로 null.
     *
     * **①이 ②보다 먼저인 것이 순서의 요점이다** — 필드 이름이 실제로 `성격 (쉼표 구분)`일 수 있고,
     * 그때 접미사를 먼저 벗기면 있지도 않은 `성격` 필드를 찾다가 값이 엉뚱한 필드에 붙는다(R-1).
     */
    fun parseFallback(
        header: String,
        knownFieldNames: Set<String>,
        knownUniverseNames: Set<String>
    ): Parsed? {
        if (!header.startsWith(PREFIX)) return null
        val body = header.removePrefix(PREFIX).trim()
        return parseBody(body, knownFieldNames, knownUniverseNames, allowSuffixStrip = true)
    }

    private fun parseBody(
        body: String,
        knownFieldNames: Set<String>,
        knownUniverseNames: Set<String>,
        allowSuffixStrip: Boolean
    ): Parsed {
        if (body in knownFieldNames) return Parsed(body, null)
        if (allowSuffixStrip && body.endsWith(MULTI_SUFFIX)) {
            return parseBody(
                body.removeSuffix(MULTI_SUFFIX).trim(),
                knownFieldNames, knownUniverseNames, allowSuffixStrip = false
            )
        }
        val m = QUALIFIED.find(body)
        if (m != null) {
            val name = m.groupValues[1].trim()
            val qualifier = m.groupValues[2].trim()
            if (qualifier in knownUniverseNames || qualifier == GLOBAL_QUALIFIER) {
                return Parsed(name, qualifier)
            }
        }
        return Parsed(body, null)
    }
}
