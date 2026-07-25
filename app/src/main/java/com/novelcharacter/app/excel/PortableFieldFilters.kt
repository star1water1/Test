package com.novelcharacter.app.excel

import org.json.JSONArray
import org.json.JSONObject

/**
 * 프리셋 필드 필터(FieldFilterHelper 직렬화 규약)의 엑셀 왕복 이식성 처리 (순수 JVM — 단위 테스트 대상).
 *
 * 인앱 JSON은 기기 로컬 DB의 fieldId를 담는데, 이 id는 기기 이전·덮어쓰기 복원에서 재발급되므로
 * 그대로 왕복하면 프리셋 필터가 죽거나 엉뚱한 필드를 가리킨다. 내보내기 시 각 필터에 안정 식별자
 * (universeCode + fieldKey — 필드키는 세계관 내 유니크)를 덧붙이고, 가져오기 시 이를 우선 해석해
 * fieldId를 재매핑한 뒤 표준 속성만 남겨 저장한다. 덧붙는 속성은 인앱 Gson 역직렬화(FieldFilter)가
 * 무시하므로 규약과 충돌하지 않는다. 해석 실패 필터는 조용히 버리지 않고 목록으로 보고한다(변수 제어).
 */
object PortableFieldFilters {

    /** 필드의 안정 식별자: 세계관코드 + 필드키 */
    data class StableKey(val universeCode: String, val fieldKey: String)

    /** [json]은 저장 가능한 인앱 규약 JSON, [droppedNames]는 해석 실패로 제외된 필터의 필드명 */
    data class Resolution(val json: String, val droppedNames: List<String>)

    /** 내보내기: 각 필터 객체에 fieldKey/universeCode를 덧붙인다. 파싱 불가 입력은 원문 유지. */
    fun augment(filtersJson: String, stableKeyByFieldId: Map<Long, StableKey>): String {
        if (filtersJson.isBlank() || filtersJson == "{}") return filtersJson
        return try {
            val arr = JSONArray(filtersJson)
            for (idx in 0 until arr.length()) {
                val obj = arr.optJSONObject(idx) ?: continue
                val key = stableKeyByFieldId[obj.optLong("fieldId", -1L)] ?: continue
                obj.put("fieldKey", key.fieldKey)
                obj.put("universeCode", key.universeCode)
            }
            arr.toString()
        } catch (_: Exception) {
            filtersJson
        }
    }

    /**
     * 가져오기: fieldKey/universeCode 우선으로 fieldId를 재해석하고 표준 속성만 남긴다.
     * - 안정 식별자 미해석 → 해당 필터 제외 + 보고 — 낡은 id를 신뢰하면 다른 필드를 가리킬 수 있다.
     * - 안정 식별자 없음(구버전 파일) → fieldId가 현존할 때만 유지, 아니면 제외 + 보고.
     * - 파싱 불가 입력 → 원문 유지 (인앱 filtersFromJson이 빈 목록으로 관대 처리).
     */
    fun resolve(
        filtersJson: String,
        fieldIdByStableKey: Map<StableKey, Long>,
        existingFieldIds: Set<Long>
    ): Resolution {
        if (filtersJson.isBlank() || filtersJson == "{}") return Resolution("{}", emptyList())
        return try {
            val arr = JSONArray(filtersJson)
            val kept = JSONArray()
            val dropped = mutableListOf<String>()
            for (idx in 0 until arr.length()) {
                val obj = arr.optJSONObject(idx) ?: continue
                val name = obj.optString("fieldName")
                val fieldKey = obj.optString("fieldKey")
                val universeCode = obj.optString("universeCode")
                val resolvedId: Long? = if (fieldKey.isNotBlank()) {
                    fieldIdByStableKey[StableKey(universeCode, fieldKey)]
                } else {
                    obj.optLong("fieldId", -1L).takeIf { it in existingFieldIds }
                }
                if (resolvedId == null) {
                    dropped.add(name.ifBlank { fieldKey.ifBlank { "?" } })
                    continue
                }
                kept.put(JSONObject().apply {
                    put("fieldId", resolvedId)
                    put("fieldName", name)
                    put("values", obj.optJSONArray("values") ?: JSONArray())
                    put("matchMode", obj.optString("matchMode").ifBlank { "exact" })
                })
            }
            Resolution(if (kept.length() == 0) "{}" else kept.toString(), dropped)
        } catch (_: Exception) {
            Resolution(filtersJson, emptyList())
        }
    }
}
