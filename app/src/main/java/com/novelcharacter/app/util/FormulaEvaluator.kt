package com.novelcharacter.app.util

import android.util.Log
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.google.gson.Gson
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FormulaEvaluator(
    private val fieldValues: Map<String, String>,       // fieldKey -> value
    private val fieldDefinitions: List<FieldDefinition>  // for GRADE mapping
) {
    // Track field keys currently being resolved to detect circular references
    private val resolvingFields = mutableSetOf<String>()

    // 중첩 CALCULATED 평가 결과 캐시 (인스턴스 수명 동안 유효 — 동일 필드 반복 평가 방지)
    private val calculatedCache = mutableMapOf<String, Double>()

    fun evaluate(formula: String): Double {
        val tokens = tokenize(formula)
        if (tokens.size >= MAX_TOKENS) {
            // Formula too complex — return NaN to signal error
            return Double.NaN
        }
        val rpn = shuntingYard(tokens)
        return evaluateRPN(rpn)
    }

    private fun resolveField(key: String): Double {
        if (!resolvingFields.add(key)) {
            // 순환 참조는 0이 아닌 NaN으로 알린다 (조용한 오답 대신 오류 표시로 전파)
            Log.w("FormulaEvaluator", "Circular reference detected for field '$key', returning NaN")
            return Double.NaN
        }
        try {
            val fieldDef = fieldDefinitions.find { it.key == key }
            // CALCULATED 필드는 저장값(엑셀 유입 정적 값 포함) 대신 항상 수식을 재귀 평가한다
            if (fieldDef != null && fieldDef.fieldType == FieldType.CALCULATED) {
                calculatedCache[key]?.let { return it }
                val formula = extractFormula(fieldDef)
                if (formula.isNullOrBlank()) {
                    Log.w("FormulaEvaluator", "CALCULATED field '$key' has no formula, defaulting to 0.0")
                    return 0.0
                }
                val result = evaluate(formula)
                calculatedCache[key] = result
                return result
            }
            val value = fieldValues[key]
            if (value == null) {
                Log.w("FormulaEvaluator", "Field '$key' not found in values, defaulting to 0.0")
                return 0.0
            }
            if (fieldDef != null && fieldDef.fieldType == FieldType.GRADE) {
                return resolveGradeValue(fieldDef, value)
            }
            // **읽히면 그대로 쓴다** — TEXT 칸의 "42"를 참조하는 수식은 오늘도 42로 계산된다.
            // 그 보장이 없는 타입이라는 사실은 [isReliablyNumeric]이 들고, 검증기가 *미리*
            // 알린다. 여기서 타입으로 미리 잘라 내면 지금 도는 수식이 조용히 0이 된다.
            val parsed = value.toDoubleOrNull()
            if (parsed == null) {
                Log.w(
                    "FormulaEvaluator",
                    "Field '$key' (${fieldDef?.fieldType}) value is not numeric, defaulting to 0.0"
                )
                return 0.0
            }
            return parsed
        } finally {
            resolvingFields.remove(key)
        }
    }

    private fun extractFormula(fieldDef: FieldDefinition): String? {
        return try {
            val config = Gson().fromJson<Map<String, Any>>(fieldDef.config, GsonTypes.STRING_ANY_MAP)
            config["formula"] as? String
        } catch (e: Exception) {
            Log.w("FormulaEvaluator", "Failed to parse config for field '${fieldDef.key}'", e)
            null
        }
    }

    private fun resolveGradeValue(fieldDef: FieldDefinition, gradeLabel: String): Double {
        // 등급 해석 단일 소스 위임. 수식 경로는 미정의 라벨을 0.0으로 취급한다 (기존 시맨틱 보존).
        return GradeValueResolver.resolveFromConfig(fieldDef, gradeLabel) ?: 0.0
    }

    private fun sumAllGrades(): Double {
        return fieldDefinitions.filter { it.fieldType == FieldType.GRADE }.sumOf { fd ->
            val value = fieldValues[fd.key] ?: return@sumOf 0.0
            resolveGradeValue(fd, value)
        }
    }

    // Token types
    private sealed class Token {
        data class Num(val value: Double) : Token()
        data class Op(val op: Char) : Token()
        data class Func(val name: String, val arity: Int = 1) : Token()
        object LParen : Token()
        object RParen : Token()
        object Separator : Token()  // 콤마 (함수 인자 구분)
    }

    /**
     * 어휘 분석은 [FormulaLexer]가 한다 — 문법을 아는 곳은 거기 하나다.
     * 여기서는 어휘를 계산 토큰으로 옮기고, 필드 참조·등급 합계만 이 자리에서 값으로 바꾼다
     * (값 해석은 평가기의 몫이므로 렉서가 알 필요가 없다).
     */
    private fun tokenize(formula: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val lexemes = FormulaLexer.lex(formula)
        var i = 0
        while (i < lexemes.size && tokens.size < MAX_TOKENS) {
            when (val lexeme = lexemes[i]) {
                is FormulaLexer.Lexeme.Op -> {
                    // Handle unary minus/plus: treat as sign if at start, after '(' or after another operator
                    val signPosition = tokens.isEmpty() || tokens.last() is Token.LParen ||
                        tokens.last() is Token.Op || tokens.last() is Token.Separator
                    if ((lexeme.op == '-' || lexeme.op == '+') && signPosition) {
                        // Collapse a run of consecutive signs; negate only when the minus count is odd ("--3" == 3)
                        var minusCount = 0
                        while (i < lexemes.size) {
                            val sign = lexemes[i]
                            if (sign is FormulaLexer.Lexeme.Op && (sign.op == '-' || sign.op == '+')) {
                                if (sign.op == '-') minusCount++
                                i++
                            } else break
                        }
                        if (minusCount % 2 == 1) {
                            // High-precedence unary negation token so "5*-3" binds as 5*(-3), not (5*0)-3
                            tokens.add(Token.Op(UNARY_MINUS))
                        }
                    } else {
                        tokens.add(Token.Op(lexeme.op))
                        i++
                    }
                }
                is FormulaLexer.Lexeme.Num -> { tokens.add(Token.Num(lexeme.value)); i++ }
                is FormulaLexer.Lexeme.FieldRef -> { tokens.add(Token.Num(resolveField(lexeme.key))); i++ }
                FormulaLexer.Lexeme.SumAllGrades -> { tokens.add(Token.Num(sumAllGrades())); i++ }
                is FormulaLexer.Lexeme.Func -> { tokens.add(Token.Func(lexeme.name, lexeme.arity)); i++ }
                FormulaLexer.Lexeme.LParen -> { tokens.add(Token.LParen); i++ }
                FormulaLexer.Lexeme.RParen -> { tokens.add(Token.RParen); i++ }
                // 콤마: 첫 인자를 스택에 남기고 다음 인자를 시작한다
                FormulaLexer.Lexeme.Separator -> { tokens.add(Token.Separator); i++ }
                // 알아보지 못한 구간은 계산에서 빠진다. 고지는 저장 시점에 FormulaValidator가 한다 —
                // 여기서 막으면 이미 저장된 수식을 가진 사용자의 값이 통째로 사라진다.
                is FormulaLexer.Lexeme.Unrecognized -> i++
            }
        }
        return tokens
    }

    private fun precedence(op: Char): Int = when (op) {
        '+', '-' -> 1
        '*', '/' -> 2
        UNARY_MINUS -> 3
        else -> 0
    }

    private fun shuntingYard(tokens: List<Token>): List<Token> {
        val output = mutableListOf<Token>()
        val stack = ArrayDeque<Token>()
        // 함수별 실제 인자 개수를 추적 (가변 인자 지원)
        val argCountStack = ArrayDeque<Int>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> output.add(token)
                is Token.Func -> {
                    stack.addLast(token)
                    argCountStack.addLast(1) // 최소 1개 인자
                }
                is Token.Separator -> {
                    // 콤마: LParen까지 연산자를 출력으로 이동 (LParen은 유지)
                    while (stack.isNotEmpty() && stack.last() !is Token.LParen) {
                        output.add(stack.removeLast())
                    }
                    if (argCountStack.isNotEmpty()) {
                        argCountStack.addLast(argCountStack.removeLast() + 1)
                    }
                }
                is Token.Op -> {
                    val prec = precedence(token.op)
                    while (stack.isNotEmpty() && stack.last() is Token.Op) {
                        val topPrec = precedence((stack.last() as Token.Op).op)
                        // 단항 부정은 우결합이므로 같은 우선순위끼리는 pop하지 않는다 (예: "--x"의 중첩 부정)
                        if (topPrec > prec || (topPrec == prec && token.op != UNARY_MINUS)) {
                            output.add(stack.removeLast())
                        } else {
                            break
                        }
                    }
                    stack.addLast(token)
                }
                Token.LParen -> stack.addLast(token)
                Token.RParen -> {
                    while (stack.isNotEmpty() && stack.last() !is Token.LParen) {
                        output.add(stack.removeLast())
                    }
                    if (stack.isNotEmpty()) stack.removeLast() // remove LParen
                    if (stack.isNotEmpty() && stack.last() is Token.Func) {
                        val func = stack.removeLast() as Token.Func
                        val arity = if (argCountStack.isNotEmpty()) argCountStack.removeLast() else func.arity
                        output.add(Token.Func(func.name, arity))
                    }
                }
            }
        }
        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            if (top is Token.LParen) {
                // Unmatched opening parenthesis — formula is malformed
                return listOf(Token.Num(Double.NaN))
            }
            output.add(top)
        }
        return output
    }

    private fun evaluateRPN(tokens: List<Token>): Double {
        val stack = ArrayDeque<Double>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> stack.addLast(token.value)
                is Token.Op -> {
                    if (token.op == UNARY_MINUS) {
                        if (stack.isEmpty()) return Double.NaN
                        stack.addLast(-stack.removeLast())
                    } else {
                        if (stack.size < 2) return Double.NaN
                        val b = stack.removeLast()
                        val a = stack.removeLast()
                        stack.addLast(when (token.op) {
                            '+' -> a + b
                            '-' -> a - b
                            '*' -> a * b
                            '/' -> if (b != 0.0) a / b else Double.NaN
                            else -> 0.0
                        })
                    }
                }
                is Token.Func -> {
                    val arity = token.arity
                    if (stack.size < arity) return Double.NaN
                    val args = mutableListOf<Double>()
                    repeat(arity) { args.add(0, stack.removeLast()) }
                    stack.addLast(when (token.name) {
                        "max" -> args.maxOrNull() ?: Double.NaN
                        "min" -> args.minOrNull() ?: Double.NaN
                        "avg" -> if (args.isNotEmpty()) args.sum() / args.size else Double.NaN
                        "abs" -> if (args.size == 1) abs(args[0]) else Double.NaN
                        else -> args.lastOrNull() ?: 0.0
                    })
                }
                else -> {}
            }
        }
        // 값이 둘 이상 남았다면 연산자가 빠진 것이다(예: "field(a) field(b)", "2 * pow(3,2)"에서
        // pow가 버려진 뒤). 종전에는 **마지막 값을 그대로 돌려줘** 그럴듯한 오답이 됐고, NaN이
        // 아니므로 화면의 '오류' 표시(U-9)도 닿지 않았다. 남은 값이 정확히 하나일 때만 결과다.
        return if (stack.size == 1) stack.first() else Double.NaN
    }

    companion object {
        private const val MAX_TOKENS = 500

        /**
         * 이 타입의 값이 **수로 읽히는 것이 보장되는가** — 수식 참조의 단일 판정.
         *
         * `false`는 *"언제나 0이 된다"*가 아니라 **"0이 될 수 있다"**이다. TEXT 칸에 `42`를
         * 적어 두고 참조하는 수식은 오늘도 42로 계산되고 이 판정이 그것을 바꾸지 않는다 —
         * 평가는 종전대로 `toDoubleOrNull()`을 지난다. 이 술어는 **검증기가 미리 알리는**
         * 데 쓴다(0이 되는 자리는 조용히 그럴듯한 오답을 만들고, 그 사유만 고지가 없었다).
         *
         * **`else`를 두지 않는다** — 타입이 늘면 여기가 컴파일을 깨서 답을 강제한다(R-52).
         */
        fun isReliablyNumeric(type: FieldType?): Boolean = when (type) {
            // NUMBER는 수 그 자체, GRADE는 등급→점수 표가 값을 수로 바꾼다,
            // CALCULATED는 재귀 평가라 언제나 수(또는 NaN)다.
            FieldType.NUMBER, FieldType.GRADE, FieldType.CALCULATED -> true
            // BODY_SIZE는 파트를 먼저 골라야 하는 복합 값이라 통째로는 수가 아니다.
            // TEXT·SELECT·MULTI_TEXT는 값 집합이 열려 있어 **보장되지 않는다**.
            FieldType.BODY_SIZE, FieldType.TEXT, FieldType.SELECT, FieldType.MULTI_TEXT -> false
            null -> false
        }

        // 단항 부정 내부 토큰. 수식 입력에서 '~'는 연산자로 토큰화되지 않으므로 충돌하지 않는다.
        private const val UNARY_MINUS = '~'
    }
}
