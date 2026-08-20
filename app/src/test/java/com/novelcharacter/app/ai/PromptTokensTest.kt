package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자리표 펼치기 — 종전 조립기의 `if (x.isNotEmpty())` 가드를 대신하는 규칙들.
 *
 * **가장 중요한 시험은 [values_areNeverRescanned]다** — 그것이 깨지면 캐릭터 메모에 적어 둔
 * 글자가 프롬프트의 구조를 바꾼다.
 */
class PromptTokensTest {

    @Test fun expand_replacesNamedSlots() {
        assertEquals(
            "이름: 홍길동",
            PromptTokens.expand("이름: {{캐릭터명}}", mapOf("캐릭터명" to "홍길동"))
        )
    }

    @Test fun expand_dropsLineWhenEveryTokenIsBlank() {
        // `태그: ` 만 남으면 모델은 "태그가 빈 값이다"라는 없는 사실을 읽는다
        val out = PromptTokens.expand(
            "이름: {{캐릭터명}}\n태그: {{태그목록}}",
            mapOf("캐릭터명" to "홍길동", "태그목록" to "")
        )
        assertEquals("이름: 홍길동", out)
    }

    @Test fun expand_keepsLineWhenAnyTokenIsFilled() {
        val out = PromptTokens.expand(
            "{{가}} / {{나}}",
            mapOf("가" to "", "나" to "값")
        )
        assertEquals(" / 값", out)
    }

    @Test fun expand_keepsPlainLinesWithoutTokens() {
        assertEquals("규칙:", PromptTokens.expand("규칙:", emptyMap()))
    }

    @Test fun expand_dropsSectionHeadWhenBodyDisappears() {
        val out = PromptTokens.expand(
            "[원문]\n{{원문}}\n\n[지시]\n{{모드지시}}",
            mapOf("원문" to "", "모드지시" to "새로 쓴다.")
        )
        assertEquals("[지시]\n새로 쓴다.", out)
    }

    @Test fun expand_keepsSectionHeadThatCarriesItsOwnValue() {
        // `[유지] {{유지목록}}` 처럼 머리가 곧 몸인 줄
        val out = PromptTokens.expand("[유지] {{유지목록}}", mapOf("유지목록" to "가람"))
        assertEquals("[유지] 가람", out)
    }

    @Test fun expand_keepsSectionHeadWhenUserPutABlankLineUnderIt() {
        // **사용자가 보기 좋으라고 넣은 빈 줄에 절 이름이 죽어서는 안 된다.**
        // 지우는 것은 *있던 몸이 전부 비워진* 절뿐이고, 원래부터 몸이 없던 머리는 건드리지 않는다.
        val out = PromptTokens.expand(
            "[캐릭터]\n\n이름: {{캐릭터명}}",
            mapOf("캐릭터명" to "홍길동")
        )
        assertEquals("[캐릭터]\n\n이름: 홍길동", out)
    }

    @Test fun expand_dropsSectionHeadWhoseOwnBodyWasEmptied() {
        val out = PromptTokens.expand(
            "[원문]\n{{원문}}\n\n[지시]\n\n{{모드지시}}",
            mapOf("원문" to "", "모드지시" to "새로 쓴다.")
        )
        assertEquals("[지시]\n\n새로 쓴다.", out)
    }

    @Test fun expand_sectionBodyIsTheBlockRightAfterTheHead() {
        // **다음 절의 몸을 제 것으로 세면 안 된다** — 빈 줄을 건너뛰며 찾으면
        // 재료가 하나도 없는 절이 살아남는다(이미지 태그 지시문에서 실제로 그랬다).
        val out = PromptTokens.expand(
            "[어휘]\n{{어휘}}\n\n출력은 JSON만.",
            mapOf("어휘" to "")
        )
        assertEquals("출력은 JSON만.", out)
    }

    @Test fun expand_collapsesBlankRunsAndTrimsEdges() {
        val out = PromptTokens.expand(
            "\n[가]\n{{가}}\n\n[나]\n{{나}}\n\n",
            mapOf("가" to "", "나" to "값")
        )
        assertEquals("[나]\n값", out)
    }

    @Test fun values_areNeverRescanned() {
        // 메모에 자리표를 적어 둔 캐릭터가 프롬프트 구조를 바꿀 수 있으면 안 된다.
        val out = PromptTokens.expand(
            "메모: {{메모}}\n{{추천할필드}}",
            mapOf("메모" to "{{추천할필드}}", "추천할필드" to "[추천할 필드] 총 1개")
        )
        assertTrue(out.startsWith("메모: {{추천할필드}}"))
        assertEquals(1, Regex(Regex.escape("[추천할 필드]")).findAll(out).count())
    }

    @Test fun values_areNotReadAsSectionHeads() {
        // 값 안의 `[...]`가 절 머리로 읽히면 위 방어가 뒷문으로 열린다.
        val out = PromptTokens.expand(
            "[메모]\n{{메모}}\n\n[지시]\n{{지시}}",
            mapOf("메모" to "[지시]", "지시" to "")
        )
        assertEquals("[메모]\n[지시]", out)
    }

    @Test fun values_withDollarAreLiteral() {
        // 문자열판 replace를 쓰면 값 안의 `$1`이 그룹 참조로 읽혀 데이터가 지시가 된다.
        assertEquals("값: $1 \\n", PromptTokens.expand("값: {{v}}", mapOf("v" to "\$1 \\n")))
    }

    @Test fun unknownName_expandsToNothingAndDropsItsLine() {
        assertEquals("", PromptTokens.expand("태그: {{없는이름}}", emptyMap()))
    }

    @Test fun namesIn_readsOnlyWellFormedTokens() {
        val names = PromptTokens.namesIn("""{{가}} {{ 나 }} {"key":"값"} {{다라1}}""")
        assertEquals(listOf("가", "다라1"), names)
    }

    @Test fun paddedToken_isDetectedSeparately() {
        assertTrue(PromptTokens.PADDED_TOKEN.containsMatchIn("{{ 캐릭터명}}"))
        assertTrue(PromptTokens.PADDED_TOKEN.containsMatchIn("{{캐릭터명 }}"))
        assertFalse(PromptTokens.PADDED_TOKEN.containsMatchIn("{{캐릭터명}}"))
    }

    @Test fun jsonBracesAreNotTokens() {
        // 양식 본문에 응답 형식 보기가 그대로 들어 있다 — 홑중괄호를 자리표로 읽으면 안 된다.
        val body = """{"suggestions":[{"key":"필드키"}]}"""
        assertEquals(body, PromptTokens.expand(body, emptyMap()))
    }
}
