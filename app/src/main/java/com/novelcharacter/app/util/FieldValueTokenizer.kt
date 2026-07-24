package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.StructuredInputConfig
import org.json.JSONObject

/**
 * 필드 값 토큰화의 단일 소스.
 *
 * 통계(StatsDataProvider)·폼 자동완성(DynamicFieldFormBuilder)·필드 데이터 라이브러리(수확/전파/검색)가
 * 각자 콤마 분리를 재구현하면 매칭 규칙이 어긋나므로, 모든 분리·재조합은 여기로 모은다.
 *
 * - [tokenize]/[join]: 라이브러리·검색·자동완성용. trim 후 빈 토큰 제거.
 * - [splitForStats]: 통계 전용 분리(BODY_SIZE·구조화 입력 파트 분기 포함) — StatsDataProvider의
 *   기존 Step 1 로직을 그대로 옮긴 것으로, 통계 결과가 바뀌면 안 된다.
 */
object FieldValueTokenizer {

    /** 라이브러리가 값 카탈로그를 관리하는 타입인가.
     *  제외: CALCULATED(파생값), NUMBER(연속값 — 통계 binning 담당),
     *  BODY_SIZE·구조화 입력(파트별 측정치는 카탈로그 값이 아님). */
    fun supportsLibrary(fd: FieldDefinition): Boolean {
        if (fd.type !in LIBRARY_TYPES) return false
        return !StructuredInputConfig.fromConfig(fd.config).enabled
    }

    /** 한 캐릭터/사건이 이 필드에 여러 토큰(콤마 구분)을 가질 수 있는가 */
    fun isMultiToken(fd: FieldDefinition): Boolean {
        if (fd.type == "MULTI_TEXT") return true
        val format = DisplayFormat.fromConfig(fd.config)
        return format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST
    }

    /** 라이브러리·검색·자동완성용 토큰화: trim + 빈 토큰 제거 */
    fun tokenize(fd: FieldDefinition, rawValue: String): List<String> {
        if (rawValue.isBlank()) return emptyList()
        return if (isMultiToken(fd)) {
            rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(rawValue.trim())
        }
    }

    /** 통계용 분리 — 기존 StatsDataProvider.getFieldValues Step 1과 동일 동작 (변경 금지) */
    fun splitForStats(fd: FieldDefinition, rawValue: String): List<String> {
        return when {
            fd.type == "BODY_SIZE" || StructuredInputConfig.fromConfig(fd.config).enabled -> {
                // 구조화 입력: config의 파트 라벨로 개별 분석
                val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
                if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                    structuredConfig.labeledParts(rawValue)
                } else {
                    // 구조화 설정 없는 BODY_SIZE — separator로 분리 (파트별 값만 반환)
                    val separator = try {
                        JSONObject(fd.config).optString("separator", "-")
                    } catch (_: Exception) { "-" }
                    val parts = rawValue.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size >= 2) parts else listOf(rawValue.trim())
                }
            }
            isMultiToken(fd) -> rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(rawValue.trim())
        }
    }

    /** rename/merge 전파 시 토큰 재조합. 다중값 렌더러·폼 관례인 ", " 결합을 따른다. */
    fun join(tokens: List<String>): String = tokens.joinToString(", ")

    private val LIBRARY_TYPES = setOf("TEXT", "SELECT", "MULTI_TEXT", "GRADE")
}
