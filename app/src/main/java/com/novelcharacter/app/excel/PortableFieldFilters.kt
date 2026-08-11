package com.novelcharacter.app.excel

import org.json.JSONArray
import org.json.JSONObject

/**
 * 프리셋 필드 필터(FieldFilterHelper 직렬화 규약)의 엑셀 왕복 이식성 처리 (순수 JVM — 단위 테스트 대상).
 *
 * 인앱 JSON은 기기 로컬 DB의 fieldId를 담는데, 이 id는 기기 이전·덮어쓰기 복원에서 재발급되므로
 * 그대로 왕복하면 프리셋 필터가 죽거나 엉뚱한 필드를 가리킨다. 내보내기 시 각 필터에 안정 식별자
 * (universeCode + fieldKey — 필드키는 세계관 내 유니크)를 덧붙이고, 가져오기 시 아래 사다리로
 * fieldId를 재해석한 뒤 표준 속성만 남겨 저장한다. 덧붙는 속성은 인앱 Gson 역직렬화(FieldFilter)가
 * 무시하므로 규약과 충돌하지 않는다.
 *
 * **해석 사다리 (규약: 코드 우선 → 자연키 폴백. DB id는 어떤 단계에서도 단독 근거가 아니다)**
 *  1. (세계관코드, 필드키) 정확 일치
 *  2. (세계관코드 별칭, 필드키) — 세계관이 코드가 아니라 '이름'으로 매칭된 복원에서도 살아남게
 *  3. 필드명 자연키 — 후보 1개면 채택, 다수면 세계관으로 좁히고, 그래도 다수면
 *     파일 fieldId가 후보 안에 있을 때만 채택(이름·번호 두 신호가 합치할 때만)
 *
 * 구버전 파일(안정 식별자 없음)은 "3번부터 시작"일 뿐 별도 규칙이 아니다. 낡은 fieldId를 존재검사만으로
 * 신뢰하면 id가 재발급된 기기에서 '성별' 라벨 아래 실제로는 '거주지'를 거르는 필터가 된다.
 *
 * 해석 실패 필터는 조용히 버리지 않고 사유·교정 경로가 담긴 경고로 보고한다(변수 제어).
 */
object PortableFieldFilters {

    /** 필드의 안정 식별자: 세계관코드 + 필드키 */
    data class StableKey(val universeCode: String, val fieldKey: String)

    /** 이 기기의 캐릭터 필드 1건 (Room 비의존 — 순수 JVM 테스트 대상). universeName은 경고 문구용. */
    data class DeviceField(
        val id: Long,
        val universeCode: String,
        val universeName: String,
        val key: String,
        val name: String
    )

    /**
     * 해석 색인 — 시트 1개당 1회만 만든다(행마다 재구성 금지).
     * [universeCodeAliases]는 "파일의 세계관코드 → 이 기기의 세계관코드"로, `importUniverses`가
     * 세계관을 이름으로 매칭했을 때만 쌓인다. 정확 일치를 먼저 시도하므로 별칭이 실재 코드를 가리지 않는다.
     */
    class Index(
        fields: List<DeviceField>,
        private val universeCodeAliases: Map<String, String> = emptyMap()
    ) {
        private val idByStableKey: Map<StableKey, Long> =
            fields.associate { StableKey(it.universeCode, it.key) to it.id }
        private val byName: Map<String, List<DeviceField>> = fields.groupBy { normalizeName(it.name) }
        private val byId: Map<Long, DeviceField> = fields.associateBy { it.id }

        fun deviceUniverseCode(fileCode: String): String = universeCodeAliases[fileCode] ?: fileCode

        fun idForStableKey(universeCode: String, fieldKey: String): Long? =
            idByStableKey[StableKey(universeCode, fieldKey)]
                ?: universeCode.takeIf { it.isNotBlank() }
                    ?.let { idByStableKey[StableKey(deviceUniverseCode(it), fieldKey)] }

        fun candidatesByName(fieldName: String): List<DeviceField> =
            if (fieldName.isBlank()) emptyList() else byName[normalizeName(fieldName)].orEmpty()

        fun fieldById(id: Long): DeviceField? = byId[id]
    }

    /** 필드명 자연키 정규화 — 전각/앞뒤·연속 공백/대소문자 차이만 흡수(괄호 등 의미 문자는 보존). */
    internal fun normalizeName(name: String): String =
        toHalfWidth(name).trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * [json] 저장 가능한 인앱 규약 JSON,
     * [warnings] 사용자에게 보고할 완성 문장(행 접두사만 호출측이 붙인다 — 문구 단일 소스),
     * [nameBasedCount] 자연키(필드명)로 해석된 필터 수 → result.nameBasedMappings 합산용.
     */
    data class Resolution(
        val json: String,
        val warnings: List<String>,
        val nameBasedCount: Int = 0
    )

    /**
     * 내보내기: 각 필터 객체에 fieldKey/universeCode를 덧붙이고 표시명(fieldName)을 현재 필드명으로 갱신한다.
     * 표시명은 가져오기의 자연키 폴백 키이자 사용자에게 보이는 라벨이므로, 인앱에서 이름을 바꾼 뒤에도
     * 파일이 진실을 담아야 한다(라벨-대상 불일치 차단). 파싱 불가 입력은 원문 유지.
     */
    fun augment(filtersJson: String, fieldsById: Map<Long, DeviceField>): String {
        if (filtersJson.isBlank() || filtersJson == "{}") return filtersJson
        return try {
            val arr = JSONArray(filtersJson)
            for (idx in 0 until arr.length()) {
                val obj = arr.optJSONObject(idx) ?: continue
                val f = fieldsById[obj.optLong("fieldId", -1L)] ?: continue
                obj.put("fieldKey", f.key)
                obj.put("universeCode", f.universeCode)
                if (f.name.isNotBlank()) obj.put("fieldName", f.name)
            }
            arr.toString()
        } catch (_: Exception) {
            filtersJson
        }
    }

    /**
     * 가져오기: 클래스 KDoc의 해석 사다리로 fieldId를 재해석하고 표준 속성만 남긴다.
     * 파싱 불가 입력 → 원문 유지 (인앱 filtersFromJson이 빈 목록으로 관대 처리).
     */
    fun resolve(filtersJson: String, index: Index): Resolution {
        if (filtersJson.isBlank() || filtersJson == "{}") return Resolution("{}", emptyList())
        return try {
            val arr = JSONArray(filtersJson)
            val kept = JSONArray()
            val warnings = mutableListOf<String>()
            var nameBased = 0
            for (idx in 0 until arr.length()) {
                val obj = arr.optJSONObject(idx) ?: continue
                val name = obj.optString("fieldName")
                val fieldKey = obj.optString("fieldKey")
                val universeCode = obj.optString("universeCode")
                val fileFieldId = obj.optLong("fieldId", -1L)
                val label = name.ifBlank { fieldKey.ifBlank { "?" } }

                var resolvedId: Long? =
                    if (fieldKey.isNotBlank()) index.idForStableKey(universeCode, fieldKey) else null

                if (resolvedId == null) {
                    val candidates = narrowByName(index, name, universeCode, fileFieldId)
                    when {
                        candidates.size == 1 -> { resolvedId = candidates[0].id; nameBased++ }
                        candidates.isEmpty() -> warnings.add(buildString {
                            append("필드 '").append(label).append("'을(를) 찾을 수 없어 필터에서 제외했습니다")
                            index.fieldById(fileFieldId)?.let {
                                append(" (파일의 필드 번호는 이 기기에서 다른 필드 '").append(it.name)
                                append("'을(를) 가리켜 사용하지 않았습니다)")
                            }
                            append(" — '필드 정의' 시트에 이 필드가 있는지 확인한 뒤 앱에서 필터를 다시 지정하세요")
                        })
                        else -> warnings.add(buildString {
                            append("필드 '").append(label).append("'과(와) 이름이 같은 필드가 여러 개(")
                            append(candidates.joinToString { it.universeName.ifBlank { it.universeCode.ifBlank { "?" } } })
                            append(") 있어 어느 것인지 확정할 수 없어 필터에서 제외했습니다")
                            append(" — 앱에서 다시 내보낸 최신 파일을 쓰면 필드키로 정확히 복원됩니다")
                        })
                    }
                }
                val finalId = resolvedId ?: continue
                val deviceField = index.fieldById(finalId)
                kept.put(JSONObject().apply {
                    put("fieldId", finalId)
                    // 라벨은 이 기기의 현재 필드명으로 — 파일의 낡은 표시명이 라벨-대상 불일치를 만들지 않게
                    put("fieldName", deviceField?.name?.ifBlank { name } ?: name)
                    put("values", obj.optJSONArray("values") ?: JSONArray())
                    put("matchMode", obj.optString("matchMode").ifBlank { "exact" })
                    // **키를 다시 싣는다 (B-11).** 인앱 필터의 정본이 키로 올라갔으므로, 여기서
                    // 표준 속성만 남기며 키를 떨어뜨리면 **엑셀 왕복이 그 정본을 조용히 지운다** —
                    // 내보내기 전에는 세계관 A·B의 '성별'이 함께 걸리던 필터가, 한 번 다녀오면
                    // id 하나짜리로 주저앉아 한쪽만 거른다(사용자에게는 캐릭터가 줄어든 것으로만 보인다).
                    // **파일의 키가 아니라 해석된 기기 필드의 키다** — id를 이 기기 것으로 바꿔 놓고
                    // 키만 파일 것을 남기면 둘이 서로 다른 필드를 가리킬 수 있다(자연키 폴백으로
                    // 해석된 경우가 정확히 그 자리다).
                    deviceField?.key?.takeIf { it.isNotBlank() }?.let { put("fieldKey", it) }
                })
            }
            Resolution(if (kept.length() == 0) "{}" else kept.toString(), warnings, nameBased)
        } catch (_: Exception) {
            Resolution(filtersJson, emptyList())
        }
    }

    /** 이름 후보 좁히기 — 세계관(별칭 포함) → 파일 fieldId 순. id는 이름이 이미 일치할 때만 쓰인다. */
    private fun narrowByName(
        index: Index,
        name: String,
        fileUniverseCode: String,
        fileFieldId: Long
    ): List<DeviceField> {
        var pool = index.candidatesByName(name)
        if (pool.size > 1 && fileUniverseCode.isNotBlank()) {
            val deviceCode = index.deviceUniverseCode(fileUniverseCode)
            val sameUniverse = pool.filter { it.universeCode == deviceCode }
            if (sameUniverse.isNotEmpty()) pool = sameUniverse   // 0건이면 좁히지 않는다(후보를 잃지 않기 위해)
        }
        if (pool.size > 1 && fileFieldId > 0) {
            pool.firstOrNull { it.id == fileFieldId }?.let { pool = listOf(it) }
        }
        return pool
    }
}

/**
 * 파일의 세계관코드 → 이 기기의 세계관코드 별칭 누적기 (순수 JVM — 단위 테스트 대상).
 *
 * 세계관을 코드가 아니라 '이름'으로 매칭한 순간에만 쌓는다. 코드만 실려 오고 이름 열이 없는 참조
 * (프리셋 필드 필터의 universeCode)가, 기기 이전·복원에서 재발급된 코드 때문에 통째로 폐기되는 것을
 * 막는다. 한 파일코드가 서로 다른 세계관 두 곳으로 폴백되면(파일 내 코드 중복 등) 추측하지 않고
 * 별칭을 폐기한다 — 오배정보다 미해석 후 보고가 낫다(변수 제어).
 */
class UniverseCodeAliases {
    private val map = mutableMapOf<String, String>()
    private val ambiguous = mutableSetOf<String>()

    /** 코드로 못 찾고 이름으로 매칭됐을 때만 호출한다. */
    fun note(fileCode: String, matchedCode: String) {
        if (fileCode.isBlank() || matchedCode.isBlank() || fileCode == matchedCode) return
        if (fileCode in ambiguous) return
        val prev = map.put(fileCode, matchedCode)
        if (prev != null && prev != matchedCode) {
            map.remove(fileCode)
            ambiguous.add(fileCode)
        }
    }

    fun clear() {
        map.clear()
        ambiguous.clear()
    }

    /** 해석 시점의 스냅샷 — 이후 시트 처리가 맵을 바꿔도 진행 중인 해석이 흔들리지 않는다. */
    fun snapshot(): Map<String, String> = map.toMap()
}
