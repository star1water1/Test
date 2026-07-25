package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 연표 시트의 사건 커스텀 필드 열 헤더 규칙 — **내보내기와 가져오기의 단일 소스** (순수 JVM, 단위 테스트 대상).
 *
 * 헤더는 `필드:{이름}`이고, 같은 이름의 사건 필드가 여러 세계관에 있을 때만 `필드:{이름}({세계관명})`으로
 * 한정한다. 가져오기가 이 규칙을 정규식으로 되짚으면 이름이 괄호로 끝나는 필드(`규모(명)`)를 세계관
 * 한정으로 오인해 열 전체가 버려지므로, **기대 헤더 → 필드 정확 일치**를 최우선으로 둔다(무편집 왕복 무결).
 * 정확 일치에 실패한 헤더(손편집·구버전)만 관대 폴백으로 해석한다.
 */
object EventFieldHeaders {

    const val PREFIX = "필드:"

    /** 헤더 생성 — [ambiguous]는 같은 이름의 사건 필드가 2개 이상일 때 true */
    fun header(fieldName: String, universeLabel: String?, ambiguous: Boolean): String =
        if (ambiguous && !universeLabel.isNullOrBlank()) "$PREFIX$fieldName($universeLabel)" else "$PREFIX$fieldName"

    /** 필드 목록 → (필드, 헤더) 목록. 내보내기가 열을 만들 때 쓰는 규칙 그 자체. */
    fun headersFor(
        fields: List<FieldDefinition>,
        universeLabelById: Map<Long, String>
    ): List<Pair<FieldDefinition, String>> {
        val nameCounts = fields.groupingBy { it.name }.eachCount()
        return fields.map { f ->
            val label = universeLabelById[f.universeId] ?: f.universeId.toString()
            f to header(f.name, label, (nameCounts[f.name] ?: 0) > 1)
        }
    }

    /** 기대 헤더 → 필드 (가져오기의 정확 일치 조회용). 헤더가 겹치면 먼저 온 필드가 이긴다. */
    fun expectedHeaders(
        fields: List<FieldDefinition>,
        universeLabelById: Map<Long, String>
    ): Map<String, FieldDefinition> {
        val map = LinkedHashMap<String, FieldDefinition>()
        for ((field, header) in headersFor(fields, universeLabelById)) {
            if (header !in map) map[header] = field
        }
        return map
    }

    /** 폴백 파싱 결과 — [universeName]이 null이면 세계관 한정이 없는 열 */
    data class Parsed(val fieldName: String, val universeName: String?)

    private val QUALIFIED = Regex("""^(.+)\((.+)\)$""")

    /**
     * 정확 일치에 실패한 헤더의 관대 해석. 순서:
     * ① 본문 전체가 실존 필드명이면 그대로(이름에 괄호가 있어도 안전)
     * ② 괄호 접미사가 **실존 세계관명**일 때만 한정자로 분해
     * ③ 그 외에는 본문 전체를 필드명으로 간주
     * 접두사가 없는 헤더는 사건 필드 열이 아니므로 null.
     */
    fun parseFallback(
        header: String,
        knownFieldNames: Set<String>,
        knownUniverseNames: Set<String>
    ): Parsed? {
        if (!header.startsWith(PREFIX)) return null
        val body = header.removePrefix(PREFIX).trim()
        if (body in knownFieldNames) return Parsed(body, null)
        val m = QUALIFIED.find(body)
        if (m != null) {
            val name = m.groupValues[1].trim()
            val qualifier = m.groupValues[2].trim()
            if (qualifier in knownUniverseNames) return Parsed(name, qualifier)
        }
        return Parsed(body, null)
    }
}
