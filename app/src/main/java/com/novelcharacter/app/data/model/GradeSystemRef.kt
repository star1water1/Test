package com.novelcharacter.app.data.model

import org.json.JSONObject
import com.novelcharacter.app.util.stringOr

/**
 * GRADE 필드 config의 **등급 체계 참조** 표현 — 단일 소스 (U-1).
 *
 * 사용자 확정안(repair_plan 7-2): 체계 = 라벨 집합 + 기본 숫자, 필드는 체계를 **참조**하되
 * 숫자는 **개별 재정의**할 수 있다. config에는 세 키가 함께 산다:
 *
 * - [GRADES_KEY] — **실효 표**(체계 기본에 재정의를 얹은 결과). 통계·수식·입력이 읽는 키는
 *   이것 하나이며(`GradeValueResolver` — 단일 소스 유지), 체계를 조회할 수 없는 곳
 *   (엑셀만 든 파일, 삭제된 체계)에서도 필드가 그대로 동작하는 근거다(관대 수용).
 * - [CONFIG_KEY] — 참조하는 체계의 code(안정 식별자). DB id가 아니라 code인 이유는 엑셀·
 *   월드패키지·휴지통을 건너도 같은 체계에 붙기 위해서다(R-1과 같은 원칙).
 * - [OVERRIDES_KEY] — 라벨 → 숫자 중 **기본과 다른 것만**. 체계의 기본 숫자가 바뀌어도
 *   재정의한 라벨은 필드의 값을 지킨다.
 *
 * 실효 표가 항상 물질화되어 있으므로 **체계 편집은 참조 필드들의 실효 표를 다시 쓰는
 * 전파([effective] 재계산)와 한 몸이다** — 전파를 빠뜨리면 참조가 장식이 된다.
 * 전파의 DB 왕복은 `GradeSystemRepository`가, 계산은 전부 이 객체가 담당한다.
 */
object GradeSystemRef {

    /** 참조하는 체계의 code. 없으면 독자 표(체계 무관)다. */
    const val CONFIG_KEY = "gradeSystem"

    /** 라벨 → 숫자 재정의(기본과 다른 것만). [CONFIG_KEY]가 있을 때만 뜻을 갖는다. */
    const val OVERRIDES_KEY = "gradeOverrides"

    /** 실효 표 — GradeValueResolver·FieldOptionParser·GradeTable이 읽는 기존 키 그대로다. */
    const val GRADES_KEY = "grades"

    /** config가 참조하는 체계 code. 키 없음·빈 문자열·손상 JSON = null(독자 표). */
    fun codeFromConfig(configJson: String): String? = try {
        JSONObject(configJson).stringOr(CONFIG_KEY, "").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** config의 재정의 표. 키 없음·손상 = 빈 맵. 숫자가 아닌 값은 재정의로 성립하지 않으므로 건너뛴다. */
    fun overridesFromConfig(configJson: String): LinkedHashMap<String, Double> = try {
        numberMap(JSONObject(configJson).optJSONObject(OVERRIDES_KEY))
    } catch (_: Exception) {
        LinkedHashMap()
    }

    /** config의 실효 표(숫자로 해석되는 항목만). GradeTable.fromConfigRows와 달리 계산용이다. */
    fun gradesFromConfig(configJson: String): LinkedHashMap<String, Double> = try {
        numberMap(JSONObject(configJson).optJSONObject(GRADES_KEY))
    } catch (_: Exception) {
        LinkedHashMap()
    }

    /** GradeSystem.gradesJson(라벨→기본 숫자 JSON 객체) 파싱. 손상 = 빈 맵. */
    fun gradesFromJson(gradesJson: String): LinkedHashMap<String, Double> = try {
        numberMap(JSONObject(gradesJson))
    } catch (_: Exception) {
        LinkedHashMap()
    }

    /** 라벨→숫자 맵 → GradeSystem.gradesJson. */
    fun gradesToJson(grades: Map<String, Double>): String {
        val json = JSONObject()
        for ((label, value) in grades) json.put(label, value)
        return json.toString()
    }

    /**
     * 실효 표 = 체계 기본 숫자 위에 재정의를 얹은 것. **라벨 집합은 체계가 정한다** —
     * 체계에 없는 라벨의 재정의는 얹을 자리가 없으므로 버려진다(체계에서 라벨을 지운 것이
     * 곧 그 라벨의 의도된 삭제다. 저장된 캐릭터 값은 건드리지 않는다 — SELECT 옵션과 같은 규칙).
     */
    fun effective(
        systemGrades: Map<String, Double>,
        overrides: Map<String, Double>
    ): LinkedHashMap<String, Double> {
        val result = LinkedHashMap<String, Double>(systemGrades.size)
        for ((label, base) in systemGrades) result[label] = overrides[label] ?: base
        return result
    }

    /**
     * 실효 표에서 재정의를 역산한다 — 체계 기본과 **다른** 라벨만 남는다.
     * (엑셀에서 참조 + 실효 표만 든 파일, 필드 편집 화면의 숫자 칸이 이 경로로 재정의가 된다)
     */
    fun deriveOverrides(
        effective: Map<String, Double>,
        systemGrades: Map<String, Double>
    ): LinkedHashMap<String, Double> {
        val result = LinkedHashMap<String, Double>()
        for ((label, value) in effective) {
            val base = systemGrades[label] ?: continue   // 체계 밖 라벨은 재정의가 아니다
            if (base != value) result[label] = value
        }
        return result
    }

    /** 체계 라벨 개명을 재정의에 따라가게 한다 — 개명으로 재정의가 조용히 증발하지 않게(변수 제어). */
    fun renameOverrides(
        overrides: Map<String, Double>,
        renames: Map<String, String>
    ): LinkedHashMap<String, Double> {
        if (renames.isEmpty()) return LinkedHashMap(overrides)
        val result = LinkedHashMap<String, Double>(overrides.size)
        for ((label, value) in overrides) result[renames[label] ?: label] = value
        return result
    }

    /**
     * config에 참조·재정의·실효 표를 함께 기록한다(그 외 키는 보존 — 수술적 편집).
     * 재정의는 체계 라벨로 잘라 기록하고, 비면 키를 남기지 않는다. 손상 JSON은 원문 유지
     * (여기서 고치려 들면 다른 키까지 파괴한다 — FieldConfigColumns와 같은 규칙).
     */
    fun materialize(
        configJson: String,
        systemCode: String,
        systemGrades: Map<String, Double>,
        overrides: Map<String, Double>
    ): String = try {
        val json = if (configJson.isBlank()) JSONObject() else JSONObject(configJson)
        json.put(CONFIG_KEY, systemCode)
        val trimmed = LinkedHashMap<String, Double>()
        for ((label, value) in overrides) if (label in systemGrades) trimmed[label] = value
        if (trimmed.isEmpty()) json.remove(OVERRIDES_KEY)
        else json.put(OVERRIDES_KEY, JSONObject().also { o -> trimmed.forEach { (k, v) -> o.put(k, v) } })
        json.put(GRADES_KEY, JSONObject().also { o -> effective(systemGrades, trimmed).forEach { (k, v) -> o.put(k, v) } })
        json.toString()
    } catch (_: Exception) {
        configJson
    }

    /**
     * 독자 표로 내려앉힌다 — 참조·재정의 키만 지우고 **실효 표는 그대로 둔다.**
     * 체계 삭제·가리키는 체계가 없는 파일의 관대 수용이 이 한 가지 변환으로 끝나는 것이
     * 실효 표를 물질화해 둔 값어치다(필드는 어제와 똑같이 동작한다).
     */
    fun demote(configJson: String): String = try {
        val json = JSONObject(configJson)
        if (!json.has(CONFIG_KEY) && !json.has(OVERRIDES_KEY)) {
            // 지울 키가 없으면 원문 그대로 — 재직렬화는 키 순서를 흔들어, 무관한 config까지
            // "바뀐 것"으로 만든다(프리셋 왕복·전파의 변경 감지가 그 문자열 비교 위에 있다).
            configJson
        } else {
            json.remove(CONFIG_KEY)
            json.remove(OVERRIDES_KEY)
            json.toString()
        }
    } catch (_: Exception) {
        configJson
    }

    /**
     * 참조 code를 재발급 표에 따라 바꾼다 — 월드패키지 가져오기·휴지통 복원이 code를
     * 재발급했을 때, 같은 묶음의 필드가 옛 code로 남의 체계에 붙지 않게 한다(R-1의 교훈:
     * 코드가 방금 바뀌었을 수 있다는 것을 한 단계 더 생각할 것).
     */
    fun remapCode(configJson: String, remap: Map<String, String>): String {
        if (remap.isEmpty()) return configJson
        val current = codeFromConfig(configJson) ?: return configJson
        val next = remap[current] ?: return configJson
        return try {
            JSONObject(configJson).put(CONFIG_KEY, next).toString()
        } catch (_: Exception) {
            configJson
        }
    }

    /** [mergeResolved]의 산출 — 병합된 config와, 체계에 없어 실효 표에서 빠진 라벨(고지 대상). */
    data class ImportMerge(val config: String, val droppedLabels: List<String>)

    /**
     * 엑셀 가져오기 병합 — **참조가 해석된 뒤**의 config 산출 (순수. 해석 자체는 DB가 필요해
     * 서비스가 한다).
     *
     * 재정의의 근거는 우선순위가 있다:
     * 1. 파일의 `grades`(실효 표)가 있으면 **그것이 사용자가 보고 고친 값**이다 — 체계 기본과
     *    다른 라벨만 재정의로 역산한다. 체계에 없는 라벨은 실을 자리가 없어 빠지며, 그 목록을
     *    돌려준다(조용히 좁히지 않는다 — 호출부가 고지한다).
     * 2. `grades`가 없으면 `gradeOverrides` 키를 그대로 쓴다(손편집 파일).
     * 3. 둘 다 없으면 체계 기본 그대로다.
     */
    fun mergeResolved(sheetConfig: String, systemCode: String, systemGradesJson: String): ImportMerge {
        val systemGrades = gradesFromJson(systemGradesJson)
        val sheetGrades = gradesFromConfig(sheetConfig)
        val overrides: Map<String, Double>
        val dropped: List<String>
        if (sheetGrades.isNotEmpty()) {
            overrides = deriveOverrides(sheetGrades, systemGrades)
            dropped = sheetGrades.keys.filter { it !in systemGrades }
        } else {
            overrides = overridesFromConfig(sheetConfig)
            dropped = emptyList()
        }
        return ImportMerge(materialize(sheetConfig, systemCode, systemGrades, overrides), dropped)
    }

    private fun numberMap(obj: JSONObject?): LinkedHashMap<String, Double> {
        val result = LinkedHashMap<String, Double>()
        if (obj == null) return result
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            if (value is Number) result[key] = value.toDouble()
            else (value as? String)?.toDoubleOrNull()?.let { result[key] = it }
        }
        return result
    }
}
