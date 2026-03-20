package com.novelcharacter.app.util

import android.util.Log
import com.novelcharacter.app.data.model.FieldDefinition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FormulaEvaluator(
    private val fieldValues: Map<String, String>,       // fieldKey -> value
    private val fieldDefinitions: List<FieldDefinition>  // for GRADE mapping
) {
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
        val value = fieldValues[key]
        if (value == null) {
            Log.w("FormulaEvaluator", "Field '$key' not found in values, defaulting to 0.0")
            return 0.0
        }
        val fieldDef = fieldDefinitions.find { it.key == key }
        if (fieldDef != null && fieldDef.type == "GRADE") {
            return resolveGradeValue(fieldDef, value)
        }
        return value.toDoubleOrNull() ?: 0.0
    }

    private fun resolveGradeValue(fieldDef: FieldDefinition, gradeLabel: String): Double {
        // Parse config JSON to get grade mappings
        val config = try { Gson().fromJson<Map<String, Any>>(fieldDef.config, object : TypeToken<Map<String, Any>>() {}.type) } catch (e: Exception) { emptyMap() }
        val grades = (config["grades"] as? Map<*, *>) ?: return 0.0
        val allowNegative = config["allowNegative"] as? Boolean ?: false
        // gradeLabel could be "A", "-B", "+A" etc
        val isNegative = allowNegative && gradeLabel.startsWith("-")
        val cleanLabel = gradeLabel.removePrefix("-").removePrefix("+")
        val baseValue = (grades[cleanLabel] as? Number)?.toDouble() ?: 0.0
        return if (isNegative) -baseValue else baseValue
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
                        if (formula[i] == '-') {
                            tokens.add(Token.Num(0.0))
                            tokens.add(Token.Op('-'))
                        }
                        // Unary plus: simply skip it ("+5" → "5")
                        i++
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
        else -> 0
    }

    private fun shuntingYard(tokens: List<Token>): List<Token> {
        val output = mutableListOf<Token>()
        val stack = ArrayDeque<Token>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> output.add(token)
                is Token.Func -> stack.addLast(token)
                is Token.Separator -> {
                    // 콤마: LParen까지 연산자를 출력으로 이동 (LParen은 유지)
                    while (stack.isNotEmpty() && stack.last() !is Token.LParen) {
                        output.add(stack.removeLast())
                    }
                }
                is Token.Op -> {
                    while (stack.isNotEmpty() && stack.last() is Token.Op && precedence((stack.last() as Token.Op).op) >= precedence(token.op)) {
                        output.add(stack.removeLast())
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
                        output.add(stack.removeLast())
                    }
                }
            }
        }
        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            if (top !is Token.LParen) output.add(top) // discard unmatched LParen
        }
        return output
    }

    private fun evaluateRPN(tokens: List<Token>): Double {
        val stack = ArrayDeque<Double>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> stack.addLast(token.value)
                is Token.Op -> {
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
                is Token.Func -> {
                    if (token.arity == 2) {
                        if (stack.size < 2) return Double.NaN
                        val b = stack.removeLast()
                        val a = stack.removeLast()
                        stack.addLast(when (token.name) {
                            "max" -> max(a, b)
                            "min" -> min(a, b)
                            else -> a
                        })
                    } else {
                        if (stack.isEmpty()) return 0.0
                        val v = stack.removeLast()
                        stack.addLast(when (token.name) {
                            "abs" -> abs(v)
                            else -> v
                        })
                    }
                }
                else -> {}
            }
        }
        return stack.lastOrNull() ?: 0.0
    }

    companion object {
        private const val MAX_TOKENS = 500
    }
}
