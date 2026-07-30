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

    /** 1.0 → "1", 0.5 → "0.5" — JSON의 정수 등급이 편집 칸에서 "1.0"으로 불어나지 않게. */
    fun formatValue(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}
