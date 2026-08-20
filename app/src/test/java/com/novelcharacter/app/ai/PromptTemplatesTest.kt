package com.novelcharacter.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기본 양식 열넷의 **자기 정합성** — 이 시험이 새 검사 스크립트를 대신한다.
 *
 * 여기서 잡는 부류는 전부 *"컴파일은 되는데 요청이 조용히 망가지는"* 것들이다:
 * 선언하지 않은 자리표를 기본 양식이 쓰고 있거나, 필수 자리표가 기본값에서 빠져 있거나,
 * 저장 키가 겹치거나.
 */
class PromptTemplatesTest {

    private val ids = PromptTemplates.Id.entries

    @Test fun everyDefault_isNotBlank() {
        ids.forEach { assertTrue("$it 기본 양식이 비었다", PromptTemplates.default(it).isNotBlank()) }
    }

    @Test fun everyDefault_passesItsOwnValidator() {
        // **기본값이 저장 거절을 당하면 '기본값으로 되돌리기'가 동작하지 않는다.**
        ids.forEach {
            assertEquals(
                "$it 기본 양식이 검증을 통과하지 못한다",
                emptyList<PromptTemplateValidator.Problem>(),
                PromptTemplateValidator.validate(it, PromptTemplates.default(it))
            )
        }
    }

    @Test fun everyDefault_onlyUsesDeclaredTokens() {
        ids.forEach { id ->
            val declared = PromptTemplates.tokensOf(id).map { it.name }.toSet()
            PromptTokens.namesIn(PromptTemplates.default(id)).forEach { name ->
                assertTrue("$id 기본 양식이 선언 없는 자리표 {{$name}}을 쓴다", name in declared)
            }
        }
    }

    @Test fun everyRequiredToken_isPresentInDefault() {
        ids.forEach { id ->
            val used = PromptTokens.namesIn(PromptTemplates.default(id))
            PromptTemplates.requiredTokensOf(id).forEach { name ->
                assertTrue("$id 기본 양식에 필수 자리표 {{$name}}이 없다", name in used)
            }
        }
    }

    @Test fun everyDefault_fitsTheCharLimit() {
        ids.forEach {
            assertTrue(
                "$it 기본 양식이 상한을 넘는다 (${PromptTemplates.default(it).length}자)",
                PromptTemplates.default(it).length <= AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS
            )
        }
    }

    @Test fun keys_areUniqueAndPrefixed() {
        assertEquals(ids.size, ids.map { it.key }.distinct().size)
        // '앱 설정' 시트의 AI 갈래에 모이게 하는 접두 — 카탈로그의 EXCLUDED_STORES 사유도
        // "`ai_`로 시작하는 키들로 실린다"고 적고 있다.
        ids.forEach { assertTrue("${it.key}", it.key.startsWith("ai_tpl_")) }
    }

    @Test fun everySystemTemplate_declaresTheResponseSlot() {
        ids.filter { it.kind == PromptTemplates.Kind.SYSTEM }.forEach {
            assertTrue("$it", PromptTemplates.T_RESPONSE in PromptTemplates.requiredTokensOf(it))
            assertTrue("$it", PromptTemplates.responseFormat(it).isNotBlank())
        }
    }

    @Test fun userTemplates_carryNoResponseFormat() {
        // 형식 지시가 두 자리에 있으면 한쪽이 낡는다 — 지시문에만 둔다.
        ids.filter { it.kind == PromptTemplates.Kind.USER }.forEach {
            assertEquals("$it", "", PromptTemplates.responseFormat(it))
        }
    }

    @Test fun responseFormat_namesTheKeysTheParserReads() {
        // 이 문자열과 응답을 읽는 코드가 갈리면 **컴파일은 통과하고 런타임에 0건**이 된다.
        assertTrue(
            PromptTemplates.responseFormat(PromptTemplates.Id.CHAR_FIELD_SYSTEM)
                .contains("\"suggestions\"")
        )
        assertTrue(
            PromptTemplates.responseFormat(PromptTemplates.Id.EVENT_FIELD_SYSTEM)
                .contains("\"suggestions\"")
        )
        assertTrue(PromptTemplates.responseFormat(PromptTemplates.Id.NARRATIVE_SYSTEM).contains("\"drafts\""))
        assertTrue(PromptTemplates.responseFormat(PromptTemplates.Id.NAME_SYSTEM).contains("\"names\""))
        assertTrue(
            PromptTemplates.responseFormat(PromptTemplates.Id.VALUE_LIBRARY_SYSTEM).contains("\"merges\"")
        )
        assertTrue(PromptTemplates.responseFormat(PromptTemplates.Id.FOLDER_TAG_SYSTEM).contains("\"folders\""))
        assertTrue(PromptTemplates.responseFormat(PromptTemplates.Id.IMAGE_TAG_SYSTEM).contains("\"images\""))
    }

    @Test fun effective_fallsBackToDefaultOnBlank() {
        val id = PromptTemplates.Id.NAME_SYSTEM
        assertEquals(PromptTemplates.default(id), PromptTemplates.effective(id, null))
        assertEquals(PromptTemplates.default(id), PromptTemplates.effective(id, "   "))
        assertEquals("내 글", PromptTemplates.effective(id, "내 글"))
    }

    @Test fun isDefault_ignoresEdgeWhitespace() {
        val id = PromptTemplates.Id.NAME_USER
        assertTrue(PromptTemplates.isDefault(id, null))
        assertTrue(PromptTemplates.isDefault(id, "\n" + PromptTemplates.default(id) + "  "))
        assertFalse(PromptTemplates.isDefault(id, PromptTemplates.default(id) + "\n한 줄 더"))
    }

    @Test fun labels_areDistinctPerTemplate() {
        // 목록·엑셀 `설명` 칸이 이 이름으로 갈린다 — 겹치면 어느 양식인지 알 수 없다.
        assertEquals(ids.size, ids.map { it.label }.distinct().size)
    }

    @Test fun tokenMeanings_areFilledIn() {
        // 엑셀 `설명` 칸과 편집 창의 자리표 목록이 이 글을 그대로 쓴다.
        ids.forEach { id ->
            PromptTemplates.tokensOf(id).forEach {
                assertTrue("$id / ${it.name}", it.meaning.isNotBlank())
            }
        }
    }

    @Test fun tokenNames_areUniqueWithinATemplate() {
        ids.forEach { id ->
            val names = PromptTemplates.tokensOf(id).map { it.name }
            assertEquals("$id 자리표 선언이 겹친다", names.size, names.distinct().size)
        }
    }

    @Test fun defaultUserPrompts_haveNoDanglingLabels() {
        // 재료가 하나도 없는 캐릭터로 펼쳐도 `태그: ` 같은 빈 이름표가 남으면 안 된다.
        val text = CharacterFieldAiSuggester.buildUserPrompt(
            CharacterFieldAiSuggester.CharacterAiContext(
                name = "홍길동",
                aliases = emptyList(), tags = emptyList(), memo = "",
                filledFields = emptyList(), imageTags = emptyList(),
                factions = emptyList(), relationships = emptyList()
            ),
            listOf(
                CharacterFieldAiSuggester.FieldSpec(
                    key = "hair", name = "머리색",
                    type = com.novelcharacter.app.data.model.FieldType.TEXT,
                    options = emptyList(), isBirthDate = false,
                    numberRange = null, currentValue = ""
                )
            )
        ).text
        assertTrue(text.startsWith("[캐릭터]\n이름: 홍길동"))
        assertFalse(text.contains("태그: \n"))
        assertFalse(text.contains("메모: \n"))
        assertFalse(text.contains("[입력된 필드]"))
        assertTrue(text.contains("[추천할 필드] 총 1개"))
    }

    @Test
    fun unusedMaterial_isAnnouncedOnlyWhenItHadAValue() {
        // 값이 있는데 자리가 없으면 알린다 — 사용자가 뺀 것을 *잘렸다*고 하지 않는 것과 짝이다.
        val notes = PromptTemplates.unusedMaterialNotes(
            used = setOf("캐릭터명"),
            present = mapOf("메모" to true, "태그목록" to false, "캐릭터명" to true)
        )
        assertEquals(1, notes.size)
        assertTrue(notes.first(), notes.first().contains(PromptTemplates.wrap("메모")))
    }

    @Test
    fun defaultTemplates_announceNothingAsUnused() {
        // **기본 양식은 재료를 하나도 빠뜨리지 않는다** — 빠뜨렸다면 그 재료를 만든 자리가
        // 죽은 코드이거나, 양식이 그것을 실을 자리를 잊은 것이다.
        PromptTemplates.Id.entries.forEach { id ->
            val used = PromptTokens.usedNames(PromptTemplates.default(id))
            val present = PromptTemplates.tokensOf(id).associate { it.name to true }
            assertEquals(
                "$id 기본 양식이 선언된 자리를 다 쓰지 않는다",
                emptyList<String>(),
                PromptTemplates.unusedMaterialNotes(used, present)
            )
        }
    }
}
