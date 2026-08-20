package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사용자가 고친 양식의 거절 사유 — 문구가 아니라 **부류**를 잠근다(화면이 문구를 입힌다). */
class PromptTemplateValidatorTest {

    private val sysId = PromptTemplates.Id.CHAR_FIELD_SYSTEM
    private val userId = PromptTemplates.Id.CHAR_FIELD_USER

    private inline fun <reified T> problemOf(id: PromptTemplates.Id, text: String): T? =
        PromptTemplateValidator.validate(id, text).filterIsInstance<T>().firstOrNull()

    @Test fun blank_isNotAProblem() {
        // 빈 값은 '기본 양식으로 되돌리기'라는 뜻이다.
        assertEquals(emptyList<PromptTemplateValidator.Problem>(), PromptTemplateValidator.validate(sysId, ""))
        assertEquals(emptyList<PromptTemplateValidator.Problem>(), PromptTemplateValidator.validate(sysId, "  \n "))
    }

    @Test fun unknownToken_isRejected() {
        val p = problemOf<PromptTemplateValidator.Problem.UnknownTokens>(sysId, "{{응답형식}}\n{{캐릭터명}}")
        assertEquals(listOf("캐릭터명"), p?.names)
    }

    @Test fun missingRequiredToken_isRejected() {
        val p = problemOf<PromptTemplateValidator.Problem.MissingRequired>(sysId, "그냥 답해라")
        assertEquals(listOf(PromptTemplates.T_RESPONSE), p?.names)
    }

    @Test fun duplicatedBlockToken_isRejected() {
        val p = problemOf<PromptTemplateValidator.Problem.Duplicated>(sysId, "{{응답형식}}\n{{응답형식}}")
        assertEquals(listOf(PromptTemplates.T_RESPONSE), p?.names)
    }

    @Test fun repeatedShortToken_isAllowed() {
        // `{{장수}}`는 한 문장에 두 번 나오는 것이 기본 양식이다 — 짧은 값은 되뇌어도 손해가 없다.
        assertEquals(
            emptyList<PromptTemplateValidator.Problem>(),
            PromptTemplateValidator.validate(
                PromptTemplates.Id.IMAGE_TAG_USER,
                PromptTemplates.default(PromptTemplates.Id.IMAGE_TAG_USER)
            )
        )
    }

    @Test fun paddedToken_isReportedSeparately() {
        // 눈으로는 옳아 보이는데 안 먹는 부류라 '모르는 이름'과 뭉뚱그리면 못 고친다.
        val problems = PromptTemplateValidator.validate(sysId, "{{응답형식}}\n{{ 응답형식 }}")
        assertTrue(problems.any { it is PromptTemplateValidator.Problem.PaddedTokens })
    }

    @Test fun tooLong_isRejectedNotTruncated() {
        val long = "{{응답형식}}\n" + "가".repeat(AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS)
        val p = problemOf<PromptTemplateValidator.Problem.TooLong>(sysId, long)
        assertEquals(AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS, p?.limit)
    }

    @Test fun userTemplate_requiresItsOwnBlockToken() {
        val p = problemOf<PromptTemplateValidator.Problem.MissingRequired>(userId, "[캐릭터]\n이름: {{캐릭터명}}")
        assertEquals(listOf(PromptTemplates.T_TARGET_FIELDS), p?.names)
    }

    @Test fun acceptable_matchesEmptyProblemList() {
        assertTrue(PromptTemplateValidator.isAcceptable(userId, PromptTemplates.default(userId)))
        assertTrue(!PromptTemplateValidator.isAcceptable(userId, "재료만 적었다"))
    }
}
