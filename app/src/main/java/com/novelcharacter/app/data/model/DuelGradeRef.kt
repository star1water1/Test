package com.novelcharacter.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * GRADE 필드 config의 **대결 등급 산정 속성** 표현 — 단일 소스 (B-113).
 *
 * 설계 정본은 `docs/feature_roadmap_2026-08.md` **4장**이다.
 *
 * 대결은 점수와 순위를 갖췄지만 그 결과가 **필드 값이 되는 길**이 없었다(B-113의 물음).
 * 사용자가 답을 줬다 — *상위 %에 따른 등급*. 이 파일은 그 약속(어느 축의 순위를 어느
 * 비율로 어느 등급에 나눌 것인가)을 config에 적고 읽는 자리다.
 *
 * ## 왜 config 키인가
 *
 * 대상은 **GRADE 타입 필드**다 — 등급 라벨과 서열이 이미 있는 유일한 타입이고, 실효 표
 * ([GradeSystemRef.GRADES_KEY])·엑셀 왕복·통계가 전부 깔려 있다. 그 위에 키 하나를 얹으므로
 * **DB 마이그레이션이 없다.**
 *
 * ```json
 * "duelGrade": {
 *   "axisCode": "<대결 축 code>",
 *   "cuts": [ {"label": "S", "topPercent": 5}, {"label": "A", "topPercent": 20} ],
 *   "lastApplied": { "at": 1754400000000, "assignments": {"CH-1": "S"} }
 * }
 * ```
 *
 * ## 세 가지 규칙
 *
 * 1. **축은 code로 가리킨다**(R-1 — [CharacterListPreset.sortDuelAxisCode]와 같은 이유).
 *    복원·가져오기가 id를 재발급해도 남의 축에 붙지 않는다.
 * 2. **세계관 경계를 넘는 복사에서는 통째로 걷어낸다**([remove]). 축 code는 전역 유니크라
 *    남의 세계관 축을 **정확히 찾아내는 것**이 더 위험하다 — 못 찾는 것이 아니라 오배정이다.
 *    읽는 쪽의 소속 검사가 그 이중 방어이고(설계 4-2 ⓑ), 이쪽이 첫 겹이다.
 * 3. **[LastApplied]는 "이 값이 어디서 왔는가"의 유일한 증거다.** 값 자체에는 출처가 없어서,
 *    반영이 흔적을 남기지 않으면 2차 반영 때 *"직전에 내가 쓴 값"*과 *"사용자가 손으로 적은
 *    값"*을 가를 수 없다. 그 구분이 곧 미리보기의 기본 체크(Q1 — 손값은 기본 해제)다.
 */
object DuelGradeRef {

    /** 대결 등급 산정 속성이 사는 config 키. 없으면 이 필드는 대결과 무관하다. */
    const val CONFIG_KEY = "duelGrade"

    private const val AXIS_CODE_KEY = "axisCode"
    private const val CUTS_KEY = "cuts"
    private const val LABEL_KEY = "label"
    private const val TOP_PERCENT_KEY = "topPercent"
    private const val LAST_APPLIED_KEY = "lastApplied"
    private const val AT_KEY = "at"
    private const val ASSIGNMENTS_KEY = "assignments"

    /**
     * config 한 칸이 가질 수 있는 크기의 상한 — [assignments][LastApplied.assignments]를
     * 실을지 말지가 여기서 갈린다.
     *
     * **엑셀이 이 한도의 이유다.** '필드 정의' 시트는 config를 셀 하나에 통째로 싣는데,
     * xlsx 셀은 32,767자에서 잘린다(`SheetSpec.EXCEL_CELL_TEXT_LIMIT`, 내보내기가 실제로
     * `take`로 자른다). 잘린 config는 **JSON으로 깨져서 돌아온다** — 등급 표도 재정의도 함께
     * 잃는다. 반영 흔적 하나 남기려다 필드를 통째로 부수는 것이라, 넘칠 것 같으면 흔적 쪽을
     * 포기한다(그 사실은 미리보기가 말한다 — 조용히 줄이지 않는다).
     *
     * 절반 아래로 잡은 것은 config에 이미 실효 표·재정의·다른 속성이 살고 있어서다.
     * 이 값과 셀 한도의 관계는 `DuelGradeRefTest`가 잠근다.
     */
    const val MAX_CONFIG_CHARS = 16_000

    /** 한 등급의 컷 — *"상위 [topPercent]%까지 [label]"*. 누적값이다(구간 폭이 아니다). */
    data class Cut(val label: String, val topPercent: Double)

    /**
     * 직전 반영이 남긴 흔적.
     *
     * @property assignments 캐릭터 code → 그때 써 넣은 라벨. 비어 있을 수 있다([omitted] 참조).
     * @property omitted 한도([MAX_CONFIG_CHARS])에 걸려 [assignments]를 싣지 못했다는 사실.
     *   **이것이 있으면 미리보기는 3분류를 2분류로 정직하게 줄인다** — 손값과 반영값을 가를
     *   근거가 없는데 갈라 보이면 사용자는 없는 판별을 믿게 된다.
     */
    data class LastApplied(
        val at: Long,
        val assignments: Map<String, String>,
        val omitted: Boolean = false
    )

    /**
     * 한 GRADE 필드에 얹힌 대결 등급 산정 약속.
     *
     * @property cuts 실효 등급 표의 **가장 높은 등급부터** 마지막 하나를 뺀 차례. 마지막 등급은
     *   나머지 전부라 컷이 필요 없다(설계 4-2). 검증은 [com.novelcharacter.app.util.DuelGradeAssign]이 한다.
     */
    data class Spec(
        val axisCode: String,
        val cuts: List<Cut>,
        val lastApplied: LastApplied? = null
    )

    /**
     * config의 대결 등급 산정 속성. 키 없음·손상 JSON·축 code 없음 = null(이 필드는 대결과 무관).
     *
     * **축 code가 비면 통째로 없는 것으로 읽는다** — 컷만 있고 축이 없는 약속은 어느 순위를
     * 나눌지 말하지 않아 실행할 수 없고, 반쯤 살아 있는 상태로 두면 화면이 그것을 켜진 것처럼
     * 그린다.
     */
    fun fromConfig(configJson: String): Spec? = try {
        val node = JSONObject(configJson).optJSONObject(CONFIG_KEY)
        val axisCode = node?.optString(AXIS_CODE_KEY, "")?.takeIf { it.isNotBlank() }
        if (node == null || axisCode == null) null
        else Spec(axisCode, cutsFrom(node.optJSONArray(CUTS_KEY)), lastAppliedFrom(node.optJSONObject(LAST_APPLIED_KEY)))
    } catch (_: Exception) {
        null
    }

    /**
     * config에 속성을 적는다(그 외 키는 보존 — 수술적 편집. 손상 JSON은 원문 유지).
     *
     * [LastApplied.assignments]는 **[MAX_CONFIG_CHARS]를 넘기면 생략하고 그 사실을 기록한다**
     * — 조용히 자르면 다음 반영이 "손값"을 잘못 판정하는데, 그 오판은 사용자가 직접 적은 값을
     * 기본 체크된 채로 덮는 방향으로 틀린다(가장 나쁜 방향이다).
     */
    fun write(configJson: String, spec: Spec): String = try {
        val json = if (configJson.isBlank()) JSONObject() else JSONObject(configJson)
        json.put(CONFIG_KEY, node(spec))
        val full = json.toString()
        if (full.length <= MAX_CONFIG_CHARS || spec.lastApplied == null) {
            full
        } else {
            // 흔적을 버리는 것이 아니라 **가장 큰 조각만** 버린다 — 시각(at)은 남아서
            // "언제 반영했는가"는 그대로 말할 수 있다.
            json.put(CONFIG_KEY, node(spec.copy(lastApplied = spec.lastApplied.copy(assignments = emptyMap(), omitted = true))))
            json.toString()
        }
    } catch (_: Exception) {
        configJson
    }

    /**
     * 속성을 걷어낸다 — **세계관 경계를 넘는 복사의 강등**이다(설계 4-2 ⓐ).
     *
     * 필드 가져오기·프리셋 직렬화·월드패키지·엑셀 가져오기가 [GradeSystemRef.demote]를 부르는
     * 그 자리에서 함께 부른다. 등급 체계 참조와 달리 **남길 잔여가 없다**: 실효 표는 config에
     * 물질화되어 필드가 그대로 동작하지만, 대결 축은 그런 물질화가 없고 다른 세계관에는
     * 그 순위 자체가 존재하지 않는다.
     */
    fun remove(configJson: String): String = try {
        val json = JSONObject(configJson)
        // 지울 키가 없으면 원문 그대로 — 재직렬화는 키 순서를 흔들어 무관한 config까지
        // "바뀐 것"으로 만든다(GradeSystemRef.demote와 같은 규칙. 프리셋 왕복·전파의 변경
        // 감지가 그 문자열 비교 위에 있다).
        if (!json.has(CONFIG_KEY)) {
            configJson
        } else {
            json.remove(CONFIG_KEY)
            json.toString()
        }
    } catch (_: Exception) {
        configJson
    }

    /** config가 가리키는 축 code. 화면·검사가 "이 필드가 어느 축에 붙었나"만 물을 때 쓴다. */
    fun axisCodeFromConfig(configJson: String): String? = fromConfig(configJson)?.axisCode

    /**
     * 컷의 라벨을 개명 표에 따라 바꾼다 — 등급 체계 편집이 라벨 이름을 고쳤을 때
     * ([GradeSystemRepository.propagate]의 `renames`가 재정의에 하는 그 일과 한 몸이다).
     *
     * 따라가지 않으면 컷이 **실재하지 않는 라벨**을 가리키게 되고, 반영 직전 재검증이 그것을
     * 유령으로 잡아 반영을 막는다 — 사용자가 한 것은 이름 바꾸기뿐인데 기능이 죽는다.
     */
    fun renameCuts(cuts: List<Cut>, renames: Map<String, String>): List<Cut> {
        if (renames.isEmpty()) return cuts
        return cuts.map { cut -> renames[cut.label]?.let { cut.copy(label = it) } ?: cut }
    }

    /** [retainCuts]의 산출 — 남은 컷과, 표에서 사라져 **다음 등급에 합쳐진** 라벨들. */
    data class RetainResult(val cuts: List<Cut>, val mergedLabels: List<String>)

    /**
     * 실효 표에서 사라진 라벨의 컷을 지운다 — **그 구간은 다음(더 낮은) 등급이 흡수한다.**
     *
     * 컷은 누적 상위 %라, 없어진 라벨의 컷을 그냥 지우면 그 아래 등급의 컷이 그대로 위로
     * 이어져 구간이 자동으로 합쳐진다(값 계산이 따로 필요 없다 — 이것이 누적 표현을 고른
     * 값어치다). 합쳐진 사실은 돌려주어 **필드 편집 섹션이 사유를 말한다**(변수 제어 —
     * 조용히 좁히지 않는다).
     */
    fun retainCuts(cuts: List<Cut>, labels: Collection<String>): RetainResult {
        val kept = ArrayList<Cut>(cuts.size)
        val merged = ArrayList<String>()
        for (cut in cuts) {
            if (cut.label in labels) kept.add(cut) else merged.add(cut.label)
        }
        return RetainResult(kept, merged)
    }

    private fun node(spec: Spec): JSONObject {
        val node = JSONObject()
        node.put(AXIS_CODE_KEY, spec.axisCode)
        node.put(CUTS_KEY, JSONArray().also { array ->
            for (cut in spec.cuts) {
                array.put(JSONObject().put(LABEL_KEY, cut.label).put(TOP_PERCENT_KEY, cut.topPercent))
            }
        })
        val applied = spec.lastApplied
        if (applied != null) {
            val obj = JSONObject().put(AT_KEY, applied.at)
            // **생략일 때만** 키를 빼는 것이 요점이다 — 빈 배정과 생략된 배정은 뜻이 다르고
            // (전자는 "그때 아무에게도 안 썼다", 후자는 "무엇을 썼는지 모른다"), 되읽는 쪽은
            // 키의 유무로만 그것을 가른다.
            if (!applied.omitted) {
                obj.put(ASSIGNMENTS_KEY, JSONObject().also { o ->
                    applied.assignments.forEach { (code, label) -> o.put(code, label) }
                })
            }
            node.put(LAST_APPLIED_KEY, obj)
        }
        return node
    }

    /**
     * 컷 배열 파싱. **라벨이 비었거나 수치가 아닌 항목은 건너뛴다** — 그런 항목은 어느 등급을
     * 뜻하는지 말하지 못해 배정에 쓸 수 없고, 남겨 두면 검증이 매번 같은 자리에서 걸린다.
     * (사용자 입력을 버리는 것이 아니다. 여기 오는 것은 손으로 고친 엑셀 JSON이나 손상 파일이고,
     * 컷이 줄어든 결과는 필드 편집 섹션에서 그대로 보인다.)
     */
    private fun cutsFrom(array: JSONArray?): List<Cut> {
        if (array == null) return emptyList()
        val cuts = ArrayList<Cut>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val label = obj.optString(LABEL_KEY, "").takeIf { it.isNotBlank() } ?: continue
            val raw = obj.opt(TOP_PERCENT_KEY)
            val percent = (raw as? Number)?.toDouble() ?: (raw as? String)?.toDoubleOrNull() ?: continue
            if (!percent.isFinite()) continue
            cuts.add(Cut(label, percent))
        }
        return cuts
    }

    private fun lastAppliedFrom(obj: JSONObject?): LastApplied? {
        if (obj == null) return null
        val at = obj.optLong(AT_KEY, 0L)
        val assignments = LinkedHashMap<String, String>()
        val node = obj.optJSONObject(ASSIGNMENTS_KEY)
        if (node != null) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val label = node.optString(key, "")
                if (label.isNotBlank()) assignments[key] = label
            }
        }
        // assignments가 없는 흔적은 **한도에 걸려 생략된 것**이다(write가 그렇게 쓴다).
        // 그 사실을 되읽어야 미리보기가 3분류를 2분류로 줄일 근거를 갖는다.
        return LastApplied(at, assignments, omitted = node == null)
    }
}
