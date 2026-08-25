package com.novelcharacter.app.util

import com.google.gson.Gson

/**
 * GRADE 필드의 등급 표(라벨→숫자) 편집 로직 — 순수 계산이라 테스트가 고정한다 (B-69).
 *
 * 종전에는 필드 편집 화면의 등급 표가 C·B·A·S 네 칸으로 고정돼 있었다. config 형식과
 * [GradeValueResolver]는 임의의 표를 다루는데 **화면만 닫혀 있어서**(원칙 01), 엑셀
 * '필드 정의' 시트로 넣은 `SS`·`D` 같은 등급이 통계·수식에서는 살아 있으면서 화면에는
 * 나타나지도 고쳐지지도 않았다(원칙 04). 이 유틸이 그 화면의 읽기/쓰기를 담당한다.
 *
 * 오류는 문구가 아니라 **타입으로** 돌려준다 — 문구는 화면 계층(strings.xml)의 일이고,
 * 순수 계층에 한국어를 두면 검사 사각이 하나 늘어난다(KT_USER_FACING의 교훈).
 */
object GradeTable {

    private val gson = Gson()

    /** 새 GRADE 필드의 기본 표 — 종전 고정 UI의 기본값 그대로다(익숙함 유지). */
    val DEFAULT_ROWS: List<Pair<String, String>> =
        listOf("C" to "0.5", "B" to "1", "A" to "2", "S" to "3")

    /** 행 단위 문제 — 화면이 strings.xml로 번역한다. */
    sealed class Problem {
        /** 수치는 있는데 등급 이름이 비었다 — 조용히 버리면 사용자의 입력이 유실된다. */
        data class BlankLabel(val valueText: String) : Problem()

        /** 수치를 숫자로 해석할 수 없다. */
        data class BadNumber(val label: String, val valueText: String) : Problem()

        /** 같은 등급 이름이 두 번 있다 — 뒤가 앞을 덮어쓰므로 조용히 받으면 안 된다. */
        data class DuplicateLabel(val label: String) : Problem()

        /**
         * 이름이 -나 +로 시작한다 — [GradeValueResolver.resolveFromConfig]가 조회 전에
         * 그 접두를 벗기므로, 이런 이름의 등급은 어떤 값과도 매칭되지 않는 죽은 행이 된다.
         */
        data class SignPrefixedLabel(val label: String) : Problem()

        /** 등급이 하나도 없다 — 빈 표의 GRADE 필드는 입력·통계 어느 쪽에도 쓸모가 없다. */
        object Empty : Problem()
    }

    data class Outcome(
        /** 표시 순서 그대로의 등급 표. [problems]가 비어 있을 때만 신뢰한다. */
        val grades: LinkedHashMap<String, Double>,
        val problems: List<Problem>
    )

    /**
     * config의 등급 표 → 편집 행 (라벨, 수치 문자열). 등급 표가 없으면 빈 목록.
     *
     * - 순서는 수치 오름차순 — 입력 스피너([FieldOptionParser.parseGradeOptions])와 같은
     *   순서라 편집 화면과 입력 화면이 같은 모양을 보인다. 해석 불가 수치는 뒤에 config
     *   순서대로 둔다.
     * - **해석할 수 없는 수치도 행으로 돌려준다**(원문 그대로). 여기서 걸러 버리면 손으로
     *   고친 엑셀 JSON의 손상 값이 저장 시 무통보로 사라진다 — 화면에 보여 주고
     *   [build]가 오류로 잡아 사용자가 바로잡게 한다.
     */
    fun fromConfigRows(configJson: String): List<Pair<String, String>> {
        // fromJson에 엘비스를 직접 붙이지 말 것 — `fromJson(...) ?: return`은 제네릭 T가
        // Nothing으로 추론되어 결과를 java.lang.Void로 캐스트하다 죽는다(테스트가 잡았다.
        // catch가 그 예외를 삼켜 "등급 표 없음"으로 위장되는, 정확히 조용한 유실 모양이었다).
        val parsed: Map<String, Any>? = try {
            gson.fromJson(configJson, GsonTypes.STRING_ANY_MAP)
        } catch (_: Exception) {
            null
        }
        val configMap = parsed ?: return emptyList()
        val grades = configMap["grades"] as? Map<*, *> ?: return emptyList()
        val rows = grades.entries.mapNotNull { (k, v) ->
            val label = k as? String ?: return@mapNotNull null
            label to when (v) {
                is Number -> formatValue(v.toDouble())
                else -> v?.toString() ?: ""
            }
        }
        // 해석 가능한 수치는 오름차순, 불가한 것은 뒤에 원래 순서대로 (안정 정렬 + null 후순)
        return rows.sortedWith(compareBy(nullsLast()) { it.second.toDoubleOrNull() })
    }

    /**
     * 편집 행(표시 순서) → 등급 표. 라벨·수치가 **둘 다 빈 행만** 조용히 건너뛴다 —
     * 한쪽만 채워진 행을 버리는 것은 입력 유실이다(변수 제어).
     */
    fun build(rows: List<Pair<String, String>>): Outcome {
        val grades = LinkedHashMap<String, Double>()
        val problems = mutableListOf<Problem>()
        for ((rawLabel, rawValue) in rows) {
            val label = rawLabel.trim()
            val valueText = rawValue.trim()
            if (label.isEmpty() && valueText.isEmpty()) continue
            if (label.isEmpty()) {
                problems.add(Problem.BlankLabel(valueText)); continue
            }
            if (label.startsWith("-") || label.startsWith("+")) {
                problems.add(Problem.SignPrefixedLabel(label)); continue
            }
            val value = valueText.toDoubleOrNull()
            if (value == null || !value.isFinite()) {
                problems.add(Problem.BadNumber(label, valueText)); continue
            }
            if (grades.containsKey(label)) {
                problems.add(Problem.DuplicateLabel(label)); continue
            }
            grades[label] = value
        }
        if (grades.isEmpty() && problems.isEmpty()) problems.add(Problem.Empty)
        return Outcome(grades, problems)
    }

    /**
     * **수치가 겹치는 등급 무리** — 같은 숫자를 든 라벨이 둘 이상인 무리만, 숫자 오름차순으로.
     * 겹치는 것이 없으면 빈 목록이다.
     *
     * ## 왜 [Problem]이 아닌가
     *
     * [Problem]에 든 다섯은 전부 **그 행을 못 쓰게 만드는 것**이라 [build]가 행을 버리고
     * 화면이 저장을 막는다. 수치 겹침은 다르다 — 행은 멀쩡하고 값도 사용자가 적은 그대로다.
     * 무너지는 것은 **표의 뜻**이다: GRADE 필드에서 숫자는 곧 순위이므로, `C`와 `F`가 같은
     * 0.5이면 그 필드는 두 등급의 우열을 말하지 못한다. 저장을 막을 일은 아니고(막으면
     * 이미 그런 표를 가진 사용자가 다른 칸도 못 고친다) **말해 줄 일**이다 — 그래서 판정만
     * 여기 두고 처분은 부르는 쪽이 정한다(화면은 안내 줄, 가져오기는 경고 한 줄).
     *
     * ## 왜 생겼는가 (2026.08.25 사용자 파일)
     *
     * 등급 체계를 고르면 표가 체계의 라벨로 바뀌는데 **그 필드가 쓰던 숫자는 남는다**
     * (`FieldEditDialog`가 `currentValues[label]`을 그대로 옮긴다 — 재정의로 남기려는
     * 의도된 동작이다). 그래서 `{C:0.5,B:1,A:2,S:3}`짜리 필드를 `F~SSS` 체계에 붙이면
     * 실효 표가 `{F:0.5,E:1,D:2,C:0.5,B:1,A:2,…}`가 된다 — **C가 F와, B가 E와, A가 D와
     * 같은 점수다.** 화면은 "재정의 N개"라고만 말해 그 충돌을 알려 주지 않았고, 실제
     * 파일에서 그런 필드가 넷 나왔다(전투 센스·마력 회복 속도·지략·fgbde).
     *
     * 앱이 등급을 늘어놓는 유일한 규칙이 **수치 오름차순**이라([FieldOptionParser
     * .parseGradeOptions]·[fromConfigRows]·'등급 체계' 시트가 전부 그 규칙이다) 겹친 표는
     * 드롭다운 차례도 무너뜨린다 — 사용자가 본 `F,C,E,B,D,A,S,SS,SSS`가 그 모양이다.
     */
    fun duplicateValues(grades: Map<String, Double>): List<DuplicateValueGroup> =
        grades.entries
            .groupBy { it.value }
            .filterValues { it.size > 1 }
            .toSortedMap()
            .map { (value, entries) -> DuplicateValueGroup(value, entries.map { it.key }) }

    /** 같은 수치를 든 라벨 무리 — 라벨은 표에 있던 차례 그대로다. */
    data class DuplicateValueGroup(val value: Double, val labels: List<String>)

    /** 1.0 → "1", 0.5 → "0.5" — JSON의 정수 등급이 편집 칸에서 "1.0"으로 불어나지 않게. */
    fun formatValue(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}
