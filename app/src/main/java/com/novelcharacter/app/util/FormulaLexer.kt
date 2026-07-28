package com.novelcharacter.app.util

/**
 * CALCULATED 수식의 **어휘 분석** — 평가([FormulaEvaluator])와 검증([FormulaValidator])이
 * 함께 쓰는 단일 소스.
 *
 * ## 왜 분리했나
 *
 * 종전에는 "무엇이 수식 문법인가"를 아는 곳이 `FormulaEvaluator.tokenize` 하나뿐이었고,
 * 그 안에서 **알아보지 못한 글자는 조용히 건너뛰었다**(`else -> i++`). 그래서 오타·미지 함수·
 * 설명 글자가 섞여도 평가는 아무 말 없이 진행됐고, 남은 조각만으로 **그럴듯한 오답**이 나왔다:
 *
 * | 사용자가 쓴 수식 | 실제 결과 | 사용자가 기대한 것 |
 * |---|---|---|
 * | `sqrt(4)` | `4` — `sqrt`가 버려지고 괄호만 남는다 | 2 |
 * | `2 * pow(3,2)` | `6` — `pow`와 앞의 `2 *`가 버려진다 | 18 |
 * | `abs (field(키))` | 괄호 안 값 그대로 — 공백 때문에 `abs`가 버려진다 | 절댓값 |
 * | `field(a) & field(b)` | `b`의 값 — `&`가 버려진다 | (오류) |
 *
 * NaN조차 나지 않으므로 U-9가 세운 "오류는 화면에 `오류`로 보인다"는 방어선이 **닿지 않는다.**
 * 값이 틀렸다는 신호 자체가 없기 때문이다. 그래서 저장 시점에 알려야 하고, 알리려면 검증기가
 * 평가기와 **똑같은 눈**으로 수식을 읽어야 한다. 검증기가 문법을 따로 구현하면 둘은 반드시
 * 갈라진다 — 여기에 함수를 하나 추가했는데 검증기가 모르면 정상 수식에 거짓 경고가 붙는다.
 *
 * 그래서 어휘 규칙은 이 파일에만 둔다. 평가기는 [Lexeme]을 계산 토큰으로 옮기고, 검증기는
 * 같은 [Lexeme] 목록에서 [Lexeme.Unrecognized]를 찾는다. **버려지는 것을 목록에 남기는 것**이
 * 이 분리의 핵심이다 — 종전 토큰화는 버린 것을 남기지 않아 아무도 물어볼 수 없었다.
 */
object FormulaLexer {

    /** 이름 → 인자 수. 여기 없는 이름은 함수가 아니다 — 평가·검증·화면 안내가 모두 이 표를 본다. */
    val FUNCTIONS: Map<String, Int> = linkedMapOf(
        "abs" to 1,
        "max" to 2,
        "min" to 2,
        "avg" to 2
    )

    /** 필드값 참조 문법: `field(키)` · `field('키')` */
    const val FIELD = "field"

    /** 인자 없이 쓰는 특수 함수: `sum_all_grades()` */
    const val SUM_ALL_GRADES = "sum_all_grades"

    /** 수식에 쓸 수 있는 이름 전부 — 화면 안내와 "아는 이름인데 표기가 틀렸다" 판정에 쓴다. */
    val KNOWN_NAMES: Set<String> = FUNCTIONS.keys + FIELD + SUM_ALL_GRADES

    sealed class Lexeme {
        data class Num(val value: Double) : Lexeme()
        data class Op(val op: Char) : Lexeme()

        /** `field(키)` — 키는 **원문 그대로**다(앞뒤 공백을 다듬지 않는다, [FormulaEvaluator]와 동일). */
        data class FieldRef(val key: String) : Lexeme()
        object SumAllGrades : Lexeme()
        data class Func(val name: String, val arity: Int) : Lexeme()
        object LParen : Lexeme()
        object RParen : Lexeme()
        object Separator : Lexeme()

        /**
         * 어느 규칙에도 걸리지 않아 **계산에서 버려지는** 구간(연속된 글자를 하나로 모은다).
         * 평가는 종전대로 무시하고, 검증은 이것을 사용자에게 고지한다.
         */
        data class Unrecognized(val text: String) : Lexeme()
    }

    /**
     * 수식을 어휘 목록으로. 공백은 버려지되 [Lexeme.Unrecognized]에 담기지 않는다
     * (종전 토큰화가 공백을 먼저 건너뛰던 것과 같다 — 공백은 사용자의 실수가 아니다).
     *
     * 인식 순서는 종전 `tokenize`와 같다: 공백 → 연산자 → 괄호 → 수 → `field(` →
     * `sum_all_grades()` → 함수표 → 콤마 → (그 밖 전부) 미인식.
     */
    fun lex(formula: String): List<Lexeme> {
        val lexemes = mutableListOf<Lexeme>()
        val pending = StringBuilder()

        fun flushUnrecognized() {
            if (pending.isNotEmpty()) {
                lexemes.add(Lexeme.Unrecognized(pending.toString()))
                pending.setLength(0)
            }
        }

        fun add(lexeme: Lexeme) {
            flushUnrecognized()
            lexemes.add(lexeme)
        }

        var i = 0
        while (i < formula.length) {
            when {
                formula[i].isWhitespace() -> {
                    // 공백은 미인식 구간을 끊는다 — "그리고 또"가 한 덩어리로 보고되지 않게.
                    flushUnrecognized()
                    i++
                }
                formula[i] in "+-*/" -> { add(Lexeme.Op(formula[i])); i++ }
                formula[i] == '(' -> { add(Lexeme.LParen); i++ }
                formula[i] == ')' -> { add(Lexeme.RParen); i++ }
                formula[i].isDigit() ||
                    (formula[i] == '.' && i + 1 < formula.length && formula[i + 1].isDigit()) -> {
                    val start = i
                    while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                    // 소수점이 둘 이상이면 수로 읽히지 않는다 — 종전과 같이 0.0으로 둔다.
                    add(Lexeme.Num(formula.substring(start, i).toDoubleOrNull() ?: 0.0))
                }
                formula.startsWith("$FIELD(", i) -> {
                    i += FIELD.length + 1
                    if (i < formula.length && (formula[i] == '\'' || formula[i] == '"')) i++
                    val start = i
                    while (i < formula.length &&
                        formula[i] != '\'' && formula[i] != '"' && formula[i] != ')'
                    ) i++
                    val key = formula.substring(start, i)
                    if (i < formula.length && (formula[i] == '\'' || formula[i] == '"')) i++
                    if (i < formula.length && formula[i] == ')') i++
                    add(Lexeme.FieldRef(key))
                }
                formula.startsWith("$SUM_ALL_GRADES()", i) -> {
                    add(Lexeme.SumAllGrades)
                    i += SUM_ALL_GRADES.length + 2
                }
                else -> {
                    val fn = FUNCTIONS.entries.firstOrNull { formula.startsWith("${it.key}(", i) }
                    if (fn != null) {
                        add(Lexeme.Func(fn.key, fn.value))
                        add(Lexeme.LParen)
                        i += fn.key.length + 1
                    } else if (formula[i] == ',') {
                        add(Lexeme.Separator)
                        i++
                    } else {
                        pending.append(formula[i])
                        i++
                    }
                }
            }
        }
        flushUnrecognized()
        return lexemes
    }
}
