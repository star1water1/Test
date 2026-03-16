package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

class FormulaEvaluator(
    private val fieldValues: Map<String, String>,       // fieldKey -> value
    private val fieldDefinitions: List<FieldDefinition>  // for GRADE mapping
) {
    fun evaluate(formula: String): Double {
        val tokens = tokenize(formula)
        val rpn = shuntingYard(tokens)
        return evaluateRPN(rpn)
    }

    private fun resolveField(key: String): Double {
        val value = fieldValues[key] ?: return 0.0
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
        val isNegative = gradeLabel.startsWith("-")
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
        data class Func(val name: String) : Token()
        object LParen : Token()
        object RParen : Token()
    }

    private fun tokenize(formula: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < formula.length) {
            when {
                formula[i].isWhitespace() -> i++
                // Handle unary +/- (at start, after operator, or after left paren)
                formula[i] in "+-" && (tokens.isEmpty() || tokens.last() is Token.Op || tokens.last() == Token.LParen) -> {
                    val sign = formula[i]
                    i++
                    // Check if followed by a digit or dot (unary number)
                    if (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) {
                        val start = i
                        while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                        val numStr = formula.substring(start, i)
                        val num = numStr.toDoubleOrNull() ?: 0.0
                        tokens.add(Token.Num(if (sign == '-') -num else num))
                    } else {
                        // Unary sign before non-number (e.g., -(expr)): insert 0 and the operator
                        tokens.add(Token.Num(0.0))
                        tokens.add(Token.Op(sign))
                    }
                }
                formula[i] in "+-*/" -> { tokens.add(Token.Op(formula[i])); i++ }
                formula[i] == '(' -> { tokens.add(Token.LParen); i++ }
                formula[i] == ')' -> { tokens.add(Token.RParen); i++ }
                formula[i].isDigit() || (formula[i] == '.' && i + 1 < formula.length && formula[i + 1].isDigit()) -> {
                    val start = i
                    while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                    tokens.add(Token.Num(formula.substring(start, i).toDouble()))
                }
                formula.startsWith("field(", i) -> {
                    i += 6 // skip "field("
                    // Track which quote char was used (if any)
                    val quoteChar = if (i < formula.length && (formula[i] == '\'' || formula[i] == '"')) formula[i++] else null
                    val start = i
                    if (quoteChar != null) {
                        while (i < formula.length && formula[i] != quoteChar) i++
                    } else {
                        while (i < formula.length && formula[i] != ')') i++
                    }
                    val key = formula.substring(start, i)
                    // skip closing quote and paren
                    if (quoteChar != null && i < formula.length && formula[i] == quoteChar) i++
                    if (i < formula.length && formula[i] == ')') i++
                    tokens.add(Token.Num(resolveField(key)))
                }
                formula.startsWith("sum_all_grades()", i) -> {
                    tokens.add(Token.Num(sumAllGrades()))
                    i += 16
                }
                formula.startsWith("abs(", i) -> {
                    tokens.add(Token.Func("abs"))
                    tokens.add(Token.LParen)
                    i += 4
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
                    if (stack.isNotEmpty() && stack.last() is Token.LParen) {
                        stack.removeLast() // remove LParen
                    }
                    if (stack.isNotEmpty() && stack.last() is Token.Func) {
                        output.add(stack.removeLast())
                    }
                }
            }
        }
        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            // Discard unmatched LParen instead of adding to output
            if (top !is Token.LParen) {
                output.add(top)
            }
        }
        return output
    }

    private fun evaluateRPN(tokens: List<Token>): Double {
        val stack = ArrayDeque<Double>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> stack.addLast(token.value)
                is Token.Op -> {
                    if (stack.size < 2) return 0.0
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(when (token.op) {
                        '+' -> a + b
                        '-' -> a - b
                        '*' -> a * b
                        '/' -> if (b != 0.0) a / b else 0.0
                        else -> 0.0
                    })
                }
                is Token.Func -> {
                    if (stack.isEmpty()) return 0.0
                    val v = stack.removeLast()
                    stack.addLast(when (token.name) {
                        "abs" -> abs(v)
                        else -> v
                    })
                }
                else -> {}
            }
        }
        return stack.lastOrNull() ?: 0.0
    }
}
