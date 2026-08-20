package com.novelcharacter.app.ai

/**
 * 사용자가 고친 양식이 성립하는가 — **순수 판정**. 문구는 화면과 가져오기가 각자 입힌다.
 *
 * `FormulaValidator`와 같은 꼴이다(`Problem`만 내고 리소스를 모른다). 다른 점은 **막는다**는
 * 것 하나다 — 수식은 *"아직 안 만든 필드를 먼저 참조해 둔다"*는 정당한 작업 순서가 있어
 * 경고만 하고 저장을 열어 두지만, 자리표 어휘는 [PromptTemplates]가 정하는 닫힌 목록이라
 * 모르는 이름은 오타 말고 될 것이 없다. 오타는 `{{캐릭터명오타}}`라는 글자 그대로 실려
 * 나가고, 사용자는 **돈을 내고 망가진 요청**을 받는다.
 *
 * 막는 방식은 R-27을 따른다 — 창을 닫지 않고 그 칸에서 고치게 한다.
 */
object PromptTemplateValidator {

    sealed class Problem {
        /** 목록에 없는 자리표 이름 — 오타다. */
        data class UnknownTokens(val names: List<String>) : Problem()

        /** 반드시 있어야 하는 자리표가 없다 — 응답을 읽을 수 없게 된다. */
        data class MissingRequired(val names: List<String>) : Problem()

        /** 한 번만 써야 하는 자리표가 두 번 이상 나온다 — 지시가 두 벌이면 서로 모순이 된다. */
        data class Duplicated(val names: List<String>) : Problem()

        /**
         * `{{ 이름 }}` — 앞뒤에 공백이 있다.
         *
         * **[UnknownTokens]와 갈라 알린다**: 눈으로는 옳아 보이는데 안 먹는 부류라,
         * "모르는 이름"이라고만 말하면 사용자가 어디를 고칠지 모른다(수식의 `PaddedKeys`가
         * 같은 이유로 갈라져 있다).
         */
        data class PaddedTokens(val samples: List<String>) : Problem()

        /**
         * `{{캐릭터 명}}` · `{{}}` · `{{a-b}}` — 자리표처럼 생겼는데 성립하지 않는다.
         *
         * [UnknownTokens]가 못 잡는 부류다 — 그쪽은 *"이름은 읽었는데 목록에 없다"*이고
         * 이쪽은 **이름으로 읽히지조차 않는다.** 짚지 않으면 글자 그대로 실려 나가고,
         * 사용자는 그 자리가 왜 비었는지 알 수 없다.
         */
        data class MalformedTokens(val samples: List<String>) : Problem()

        /** 글자 수 상한 초과 — 자르지 않고 거절한다(잘린 양식은 계약이 깨진 양식이다). */
        data class TooLong(val chars: Int, val limit: Int) : Problem()
    }

    /**
     * @param template 사용자가 적은 글. **빈 값은 문제가 아니다** — 기본 양식으로 되돌리라는
     *   뜻이라 [PromptTemplates.effective]가 받아 준다.
     */
    fun validate(id: PromptTemplates.Id, template: String): List<Problem> {
        val problems = mutableListOf<Problem>()
        if (template.isBlank()) return problems

        if (template.length > AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS) {
            problems.add(Problem.TooLong(template.length, AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS))
        }

        val padded = PromptTokens.PADDED_TOKEN.findAll(template).map { it.value }.distinct().toList()
        if (padded.isNotEmpty()) problems.add(Problem.PaddedTokens(padded))

        val malformed = PromptTokens.malformedIn(template)
        if (malformed.isNotEmpty()) problems.add(Problem.MalformedTokens(malformed))

        val allowed = PromptTemplates.tokensOf(id).map { it.name }.toSet()
        val used = PromptTokens.namesIn(template)
        val unknown = used.filterNot { it in allowed }.distinct()
        if (unknown.isNotEmpty()) problems.add(Problem.UnknownTokens(unknown))

        val required = PromptTemplates.requiredTokensOf(id)
        val missing = required.filterNot { it in used }
        if (missing.isNotEmpty()) problems.add(Problem.MissingRequired(missing))

        val duplicated = PromptTemplates.singleTokensOf(id).filter { name -> used.count { it == name } > 1 }
        if (duplicated.isNotEmpty()) problems.add(Problem.Duplicated(duplicated))

        return problems
    }

    /** 저장해도 되는가 — 문제가 하나라도 있으면 안 된다. */
    fun isAcceptable(id: PromptTemplates.Id, template: String): Boolean =
        validate(id, template).isEmpty()
}
