package com.novelcharacter.app.util

import android.util.Log
import com.novelcharacter.app.data.model.FieldDefinition
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
            if (fieldDef != null && fieldDef.type == "CALCULATED") {
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
            if (fieldDef != null && fieldDef.type == "GRADE") {
                return resolveGradeValue(fieldDef, value)
            }
            return value.toDoubleOrNull() ?: 0.0
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
        return fieldDefinitions.filter { it.type == "GRADE" }.sumOf { fd ->
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

    private fun tokenize(formula: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < formula.length && tokens.size < MAX_TOKENS) {
            when {
                formula[i].isWhitespace() -> i++
                formula[i] in "+-*/" -> {
                    // Handle unary minus/plus: treat as sign if at start, after '(' or after another operator
                    if ((formula[i] == '-' || formula[i] == '+') && (tokens.isEmpty() || tokens.last() is Token.LParen || tokens.last() is Token.Op || tokens.last() is Token.Separator)) {
                        // Collapse a run of consecutive signs; negate only when the minus count is odd ("--3" == 3)
                        var minusCount = 0
                        while (i < formula.length && (formula[i] == '-' || formula[i] == '+' || formula[i].isWhitespace())) {
                            if (formula[i] == '-') minusCount++
                            i++
                        }
                        if (minusCount % 2 == 1) {
                            // High-precedence unary negation token so "5*-3" binds as 5*(-3), not (5*0)-3
                            tokens.add(Token.Op(UNARY_MINUS))
                        }
                    } else {
                        tokens.add(Token.Op(formula[i]))
                        i++
                    }
                }
                formula[i] == '(' -> { tokens.add(Token.LParen); i++ }
                formula[i] == ')' -> { tokens.add(Token.RParen); i++ }
                formula[i].isDigit() || (formula[i] == '.' && i + 1 < formula.length && formula[i + 1].isDigit()) -> {
                    val start = i
                    while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                    tokens.add(Token.Num(formula.substring(start, i).toDoubleOrNull() ?: 0.0))
                }
                formula.startsWith("field(", i) -> {
                    i += 6 // skip "field("
                    // skip quote
                    if (i < formula.length && (formula[i] == '\'' || formula[i] == '"')) i++
                    val start = i
                    while (i < formula.length && formula[i] != '\'' && formula[i] != '"' && formula[i] != ')') i++
                    val key = formula.substring(start, i)
                    // skip closing quote and paren
                    if (i < formula.length && (formula[i] == '\'' || formula[i] == '"')) i++
                    if (i < formula.length && formula[i] == ')') i++
                    tokens.add(Token.Num(resolveField(key)))
                }
                formula.startsWith("sum_all_grades()", i) -> {
                    tokens.add(Token.Num(sumAllGrades()))
                    i += 16
                }
                formula.startsWith("abs(", i) -> {
                    tokens.add(Token.Func("abs", 1))
                    tokens.add(Token.LParen)
                    i += 4
                }
                formula.startsWith("max(", i) -> {
                    tokens.add(Token.Func("max", 2))
                    tokens.add(Token.LParen)
                    i += 4
                }
                formula.startsWith("min(", i) -> {
                    tokens.add(Token.Func("min", 2))
                    tokens.add(Token.LParen)
                    i += 4
                }
                formula.startsWith("avg(", i) -> {
                    tokens.add(Token.Func("avg", 2))
                    tokens.add(Token.LParen)
                    i += 4
                }
                formula[i] == ',' -> {
                    // 콤마를 RParen + LParen으로 변환하여 인자 분리
                    // 함수 내 콤마: 첫 인자를 스택에 남기고 다음 인자 시작
                    tokens.add(Token.Separator)
                    i++
                }
                else -> i++ // skip unknown
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
        return stack.lastOrNull() ?: Double.NaN
    }

    companion object {
        private const val MAX_TOKENS = 500

        // 단항 부정 내부 토큰. 수식 입력에서 '~'는 연산자로 토큰화되지 않으므로 충돌하지 않는다.
        private const val UNARY_MINUS = '~'
    }
}
