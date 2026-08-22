package com.novelcharacter.app.util

/**
 * CALCULATED 수식의 **저장 시점 검증** (색출 개혁 로드맵 5 / S-11).
 *
 * ## 무엇을 막는가
 *
 * U-9는 수식이 깨졌을 때 화면·엑셀에 `오류`가 보이도록 표시 계층을 하나로 모았다. 그런데 그
 * 방어선은 **평가가 NaN을 낼 때만** 작동한다. 실제로 흔한 실수 여럿은 NaN조차 내지 않는다 —
 * [FormulaLexer]가 알아보지 못한 글자를 조용히 버리기 때문에, 남은 조각만으로 **그럴듯한 수**가
 * 나온다(`sqrt(4)` → `4`, `2 * pow(3,2)` → `6`). 값이 틀렸다는 신호 자체가 없으므로 사용자는
 * 알 길이 없고, 그 값은 통계·엑셀로 그대로 퍼진다.
 *
 * 그래서 잘못은 **쓰는 자리에서** 알린다. CLAUDE.md의 변수 제어 원칙 그대로 검증 → 알림 →
 * 교정이며, **막지는 않는다** — 아직 만들지 않은 필드를 먼저 참조해 두는 작업 순서는 사용자의
 * 자유이므로(원칙 03·자율성 우선) 호출부는 '그대로 저장' 선택지를 유지한다.
 *
 * ## 왜 평가기와 같은 눈으로 읽는가
 *
 * 문법을 여기서 따로 구현하면 평가기와 반드시 갈라진다. 함수를 하나 추가했는데 검증기가 모르면
 * **정상 수식에 거짓 경고**가 붙고, 반대로 평가기가 버리는 것을 검증기가 인정하면 조용한 오답이
 * 그대로 통과한다. 그래서 판단 근거는 [FormulaLexer.lex]의 결과 하나뿐이다.
 */
object FormulaValidator {

    /** 검증이 찾은 문제 하나. 문구는 화면 계층이 입힌다(순수 계층은 리소스를 모른다). */
    sealed class Problem {
        /** 괄호 짝이 맞지 않는다 — 여는 괄호가 남거나, 닫는 괄호가 더 많다. */
        object UnbalancedParen : Problem()

        /** 자기 자신을 참조한다 — 평가는 NaN이 된다. */
        data class SelfReference(val key: String) : Problem()

        /** 수식끼리 돌아온다(A → B → A). [path]는 처음 필드부터 되돌아온 필드까지. */
        data class CircularReference(val path: List<String>) : Problem()

        /** 세계관에 없는 필드 키 — 평가는 그 자리를 0으로 둔다. */
        data class UnknownKeys(val keys: List<String>) : Problem()

        /**
         * 앞뒤 공백까지 키 이름으로 읽히는 참조(`field( 키 )`). 공백을 지우면 실재하는 키다 —
         * 눈으로는 옳아 보이는데 평가는 0이 되므로 [UnknownKeys]와 나눠서 알린다.
         */
        data class PaddedKeys(val keys: List<String>) : Problem()

        /** 수식에 없는 함수 이름 — 이름은 버려지고 괄호 안 값만 남는다. */
        data class UnknownFunctions(val names: List<String>) : Problem()

        /**
         * 아는 함수인데 표기가 어긋나 인식되지 않는다 — 이름과 여는 괄호 사이 공백(`abs (…)`),
         * 또는 인자를 받지 않는 `sum_all_grades`에 인자를 준 경우.
         */
        data class MalformedCalls(val names: List<String>) : Problem()

        /** 그 밖에 계산에서 빠지는 조각(연산자 오타, 설명 글자 등). */
        data class UnrecognizedText(val fragments: List<String>) : Problem()

        /**
         * 키는 **실재하는데 그 타입의 값이 수로 읽히는 것이 보장되지 않는** 참조.
         *
         * 그 자리는 값이 수로 안 읽히는 순간 조용히 0이 되고, 수식은 **그럴듯한 오답**을
         * 낸다 — 형제 사유([UnknownKeys]·[PaddedKeys])와 결과가 같은데 고지만 없었다.
         * 판정은 [com.novelcharacter.app.util.FormulaEvaluator.isReliablyNumeric]이 든다.
         *
         * **막지 않는다.** TEXT 칸에 `42`를 적어 두고 참조하는 수식은 오늘도 옳게 돌고,
         * 이 고지는 *그 값이 글자가 되는 순간 0이 된다*는 사실을 미리 알릴 뿐이다.
         */
        data class NonNumericKeys(val keys: List<String>) : Problem()
    }

    /**
     * @param formula 검사할 수식 원문.
     * @param currentKey 이 수식이 붙는 필드의 키(아직 저장 전일 수 있다).
     * @param knownKeys 같은 세계관·같은 대상(캐릭터/사건)의 필드 키. **null이면 키 존재 검사를
     *   건너뛴다** — 목록을 못 읽었을 때 있는 키를 없다고 알리는 편이 더 나쁘다.
     * @param calculatedFormulas 자기 자신을 뺀 CALCULATED 필드의 키 → 수식. 전이 순환 참조를
     *   보는 데 쓴다. 비어 있으면 직접 자기참조만 잡힌다.
     * @param knownFieldTypes 같은 구역 필드의 키 → 타입. **null이거나 그 키가 없으면 타입
     *   검사를 건너뛴다** — 목록을 못 읽었을 때 멀쩡한 참조를 경고하는 편이 더 나쁘다
     *   (`knownKeys`가 같은 이유로 null을 허용하는 것과 같은 규약).
     */
    fun validate(
        formula: String,
        currentKey: String,
        knownKeys: Set<String>?,
        calculatedFormulas: Map<String, String> = emptyMap(),
        knownFieldTypes: Map<String, com.novelcharacter.app.data.model.FieldType?>? = null
    ): List<Problem> {
        if (formula.isBlank()) return emptyList()

        val lexemes = FormulaLexer.lex(formula)
        val problems = mutableListOf<Problem>()

        if (hasUnbalancedParens(lexemes)) problems.add(Problem.UnbalancedParen)

        // ── 필드 참조 ──
        val refs = fieldRefs(lexemes)
        // 편집 중인 필드는 아직 저장 전이라 목록에 없을 수 있다 — 자기 키는 항상 아는 키로 친다.
        val allKeys = knownKeys?.plus(currentKey)
        val unknown = mutableListOf<String>()
        val padded = mutableListOf<String>()
        var selfReferenced = false
        for (key in refs.distinct()) {
            if (key == currentKey) { selfReferenced = true; continue }
            if (allKeys == null || key in allKeys) continue
            val trimmed = key.trim()
            if (trimmed != key && trimmed in allKeys) padded.add(key) else unknown.add(key)
        }
        if (selfReferenced) problems.add(Problem.SelfReference(currentKey))
        if (unknown.isNotEmpty()) problems.add(Problem.UnknownKeys(unknown))
        if (padded.isNotEmpty()) problems.add(Problem.PaddedKeys(padded))

        // **있는 키인데 타입이 수를 보장하지 않는** 참조 — 없는 키와 결과가 같은데(그 자리는
        // 0이 된다) 고지만 없었다. 자기 자신과 못 찾은 키는 위에서 이미 다뤘으므로 뺀다.
        if (knownFieldTypes != null) {
            val nonNumeric = refs.distinct().filter { key ->
                key != currentKey &&
                    key !in unknown &&
                    key !in padded &&
                    knownFieldTypes.containsKey(key) &&
                    !FormulaEvaluator.isReliablyNumeric(knownFieldTypes[key])
            }
            if (nonNumeric.isNotEmpty()) problems.add(Problem.NonNumericKeys(nonNumeric))
        }

        // ── 전이 순환 참조 ── (자기참조는 위에서 이미 알렸으므로 두 마디 이상만)
        detectCycle(currentKey, formula, calculatedFormulas)
            ?.takeIf { it.size > 2 }
            ?.let { problems.add(Problem.CircularReference(it)) }

        // ── 계산에서 버려지는 구간 ──
        val unknownFunctions = mutableListOf<String>()
        val malformed = mutableListOf<String>()
        val fragments = mutableListOf<String>()
        lexemes.forEachIndexed { i, lexeme ->
            if (lexeme !is FormulaLexer.Lexeme.Unrecognized) return@forEachIndexed
            // 뒤에 여는 괄호가 붙었다면 꼬리의 이름 부분이 함수 자리다("&sqrt(" → "&" + "sqrt").
            val name = identifierTail(lexeme.text)
            val callSite = name.isNotEmpty() && lexemes.getOrNull(i + 1) === FormulaLexer.Lexeme.LParen
            when {
                // 아는 이름이 통째로 버려졌다면 표기가 어긋난 것이다 — 괄호 앞 공백,
                // 인자를 받지 않는 함수에 준 인자, 괄호를 잊은 이름이 모두 여기 온다.
                name in FormulaLexer.KNOWN_NAMES -> malformed.add(name)
                callSite -> unknownFunctions.add(name)
                // 이름 자리가 아니면 통째로 버려지는 조각이다.
                else -> { fragments.add(lexeme.text); return@forEachIndexed }
            }
            val rest = lexeme.text.dropLast(name.length)
            if (rest.isNotEmpty()) fragments.add(rest)
        }
        if (unknownFunctions.isNotEmpty()) problems.add(Problem.UnknownFunctions(unknownFunctions.distinct()))
        if (malformed.isNotEmpty()) problems.add(Problem.MalformedCalls(malformed.distinct()))
        if (fragments.isNotEmpty()) problems.add(Problem.UnrecognizedText(fragments.distinct()))

        return problems
    }

    /**
     * 괄호 짝. 원문의 `(`·`)`를 세지 않고 어휘를 세는 이유는 `field(키)`의 괄호가 참조 문법의
     * 일부라 원문 계수로는 키에 괄호가 든 경우를 거짓 경고하기 때문이다.
     */
    private fun hasUnbalancedParens(lexemes: List<FormulaLexer.Lexeme>): Boolean {
        var depth = 0
        for (lexeme in lexemes) {
            when (lexeme) {
                FormulaLexer.Lexeme.LParen -> depth++
                // 닫는 괄호가 더 많으면 평가는 그것을 조용히 버린다 — 짝이 맞지 않는 것은 같다.
                FormulaLexer.Lexeme.RParen -> if (--depth < 0) return true
                else -> {}
            }
        }
        return depth != 0
    }

    private fun fieldRefs(lexemes: List<FormulaLexer.Lexeme>): List<String> =
        lexemes.filterIsInstance<FormulaLexer.Lexeme.FieldRef>().map { it.key }

    /** 뒤에서부터 이어지는 식별자 글자(영문·숫자·밑줄). 함수 이름 자리를 떼어낼 때 쓴다. */
    private fun identifierTail(text: String): String {
        var start = text.length
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return text.substring(start)
    }

    /**
     * [start]에서 출발해 수식 참조를 따라가다 다시 만나는 필드가 있으면 그 경로를 돌려준다.
     * CALCULATED가 아닌 필드는 값에서 멈추므로 따라가지 않는다.
     *
     * 경로는 되돌아온 필드를 양 끝에 둔다 — `[A, B, A]`.
     */
    private fun detectCycle(
        start: String,
        startFormula: String,
        calculatedFormulas: Map<String, String>
    ): List<String>? {
        val path = mutableListOf<String>()
        val onPath = mutableSetOf<String>()
        val settled = mutableSetOf<String>()

        fun refsOf(key: String): List<String> {
            val formula = if (key == start) startFormula else calculatedFormulas[key] ?: return emptyList()
            // 키를 다듬지 않는다 — 평가기가 `field( a )`를 " a "로 읽어 값을 못 찾는 이상,
            // 그것은 돌아오는 길이 아니다. 다듬으면 없는 순환을 있다고 알리게 된다.
            return fieldRefs(FormulaLexer.lex(formula)).distinct()
        }

        fun walk(key: String): List<String>? {
            if (key in onPath) return path.subList(path.indexOf(key), path.size) + key
            if (key in settled) return null
            path.add(key)
            onPath.add(key)
            for (next in refsOf(key)) {
                // 수식이 아닌 필드는 값에서 끝난다 — 돌아올 길이 없다.
                if (next != start && next !in calculatedFormulas) continue
                walk(next)?.let { return it }
            }
            path.removeAt(path.size - 1)
            onPath.remove(key)
            settled.add(key)
            return null
        }

        return walk(start)
    }
}
