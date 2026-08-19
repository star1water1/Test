package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사건 필드 AI 추천의 프롬프트 조립·재료 범위 (B-43).
 *
 * 잠그는 것은 셋이다:
 * ① 사건 축 프롬프트가 **사건의 재료**를 말하는가(캐릭터 재료를 요구하지 않는가)
 * ② 사용자가 끈 재료가 **조용히 빠지지 않는가**(고지가 붙는가)
 * ③ 응답 계약 절(`[추천할 필드]`)이 캐릭터 축과 **글자 그대로 같은가** — 파싱이 한 벌이라
 *    여기가 갈리면 한쪽 축에서만 검증이 느슨해진다.
 */
class EventFieldAiSuggesterTest {

    private fun context(
        description: String = "왕도 함락",
        dateLabel: String = "천개력 1023년 5월",
        eventTypeLabel: String = "",
        universeName: String = "아르카나",
        novels: List<String> = listOf("붉은 성좌"),
        characters: List<String> = listOf("리안", "카일"),
        neighborEvents: List<String> = listOf("1020년 – 국경 분쟁", "1025년 – 대관식"),
        filledFields: List<Pair<String, String>> = listOf("장소" to "왕도"),
        loadFailures: List<String> = emptyList()
    ) = EventFieldAiSuggester.EventAiContext(
        description = description,
        dateLabel = dateLabel,
        eventTypeLabel = eventTypeLabel,
        universeName = universeName,
        novels = novels,
        characters = characters,
        neighborEvents = neighborEvents,
        filledFields = filledFields,
        loadFailures = loadFailures
    )

    private fun target(
        key: String = "scale",
        name: String = "규모",
        type: FieldType = FieldType.TEXT,
        options: List<String> = emptyList(),
        currentValue: String = ""
    ) = CharacterFieldAiSuggester.FieldSpec(
        key = key, name = name, type = type, options = options,
        isBirthDate = false, numberRange = null, currentValue = currentValue
    )

    // ── 컨텍스트 블록 ──

    @Test fun prompt_carriesEventMaterials() {
        val text = EventFieldAiSuggester.buildUserPrompt(context(), listOf(target())).text
        assertTrue(text.contains("[사건]"))
        assertTrue(text.contains("설명: 왕도 함락"))
        assertTrue(text.contains("때: 천개력 1023년 5월"))
        assertTrue(text.contains("세계관: 아르카나"))
        assertTrue(text.contains("관련 작품: 붉은 성좌"))
        assertTrue(text.contains("관련 캐릭터: 리안, 카일"))
        assertTrue(text.contains("가까운 사건: 1020년 – 국경 분쟁 / 1025년 – 대관식"))
    }

    @Test fun prompt_doesNotClaimCharacterOnlyMaterials() {
        // 사건에는 태그·메모·소속·관계·대결 우열이라는 칸 자체가 없다 —
        // 캐릭터판을 재사용했다면 이 단언이 깨진다(없는 근거를 대라는 지시가 나간다).
        val text = EventFieldAiSuggester.buildUserPrompt(context(), listOf(target())).text
        assertFalse(text.contains("[캐릭터]"))
        assertFalse(text.contains("소속 세력"))
        assertFalse(text.contains("대결"))
    }

    @Test fun prompt_emptyDescriptionIsMarkedNotOmitted() {
        // 설명이 비어도 줄을 지우지 않는다 — 지우면 모델이 '설명이 없다'를 알 길이 없다
        val text = EventFieldAiSuggester.buildUserPrompt(context(description = "  "), listOf(target())).text
        assertTrue(text.contains("설명: (미입력)"))
    }

    @Test fun prompt_omitsBlankOptionalLines() {
        val ctx = context(dateLabel = "", universeName = "", novels = emptyList(), characters = emptyList())
        val text = EventFieldAiSuggester.buildUserPrompt(ctx, listOf(target())).text
        assertFalse(text.contains("때:"))
        assertFalse(text.contains("세계관:"))
        assertFalse(text.contains("관련 작품:"))
    }

    @Test fun prompt_includesEventTypeWhenPresent() {
        val text = EventFieldAiSuggester.buildUserPrompt(
            context(eventTypeLabel = "출생"), listOf(target())
        ).text
        assertTrue(text.contains("유형: 출생"))
    }

    // ── 재료 범위 (확정 16번 ㄱ1/ㄱ2 인앱 선택) ──

    @Test fun scope_offMaterialIsDroppedAndAnnounced() {
        val build = EventFieldAiSuggester.buildUserPrompt(
            context(), listOf(target()),
            scope = EventAiMaterial.DEFAULT - EventAiMaterial.CHARACTERS
        )
        assertFalse(build.text.contains("관련 캐릭터"))
        // 조용히 빠지지 않는다 — 추천이 빈약할 때 원인을 사용자가 알 수 있어야 한다
        assertTrue(build.truncationNotes.any { it.contains("관련 캐릭터") && it.contains("설정") })
    }

    @Test fun scope_emptyScopeKeepsIdentityLines() {
        // 전부 꺼도 '무엇에 대한 값인가'는 남는다 — 그것까지 빠지면 요청이 성립하지 않는다
        val build = EventFieldAiSuggester.buildUserPrompt(context(), listOf(target()), scope = emptySet())
        assertTrue(build.text.contains("설명: 왕도 함락"))
        assertTrue(build.text.contains("때: 천개력 1023년 5월"))
        assertFalse(build.text.contains("관련 작품"))
        assertFalse(build.text.contains("가까운 사건"))
        assertFalse(build.text.contains("[입력된 필드]"))
    }

    @Test fun scope_absentMaterialIsNotAnnouncedAsExcluded() {
        // 애초에 없는 것은 '내가 껐다'가 아니다 — 없는데 고지하면 사용자가 설정을 뒤진다
        val build = EventFieldAiSuggester.buildUserPrompt(
            context(characters = emptyList()), listOf(target()),
            scope = EventAiMaterial.DEFAULT - EventAiMaterial.CHARACTERS
        )
        assertTrue(build.truncationNotes.none { it.contains("관련 캐릭터") })
    }

    @Test fun scope_loadFailureIsToldApartFromUserChoice() {
        val build = EventFieldAiSuggester.buildUserPrompt(
            context(loadFailures = listOf("관련 캐릭터")), listOf(target())
        )
        assertTrue(build.truncationNotes.any { it.contains("불러오지 못함") })
    }

    // ── 상한 (R-14) ──

    @Test fun caps_longDescriptionIsCutAndAnnounced() {
        val long = "가".repeat(EventFieldAiSuggester.MAX_DESCRIPTION_CHARS + 50)
        val build = EventFieldAiSuggester.buildUserPrompt(context(description = long), listOf(target()))
        assertTrue(build.text.contains("…"))
        assertTrue(build.truncationNotes.any { it.contains("사건 설명") })
    }

    @Test fun caps_tooManyCharactersAreCutAndCounted() {
        val many = (1..EventFieldAiSuggester.MAX_CHARACTERS + 5).map { "인물$it" }
        val build = EventFieldAiSuggester.buildUserPrompt(context(characters = many), listOf(target()))
        assertTrue(build.truncationNotes.any { it.contains("관련 캐릭터 5건 생략") })
    }

    @Test fun caps_tooManyNeighborsAreCutAndCounted() {
        val many = (1..EventFieldAiSuggester.MAX_NEIGHBORS + 3).map { "${1000 + it}년 – 사건$it" }
        val build = EventFieldAiSuggester.buildUserPrompt(context(neighborEvents = many), listOf(target()))
        assertTrue(build.truncationNotes.any { it.contains("가까운 사건 3건 생략") })
    }

    // ── 공통 계약 절 ──

    @Test fun targetSection_isIdenticalToCharacterAxis() {
        // 파싱이 한 벌이므로 이 절도 한 벌이어야 한다. 두 축의 산출을 직접 견준다.
        val targets = listOf(target(), target(key = "kind", name = "종류", type = FieldType.SELECT, options = listOf("전투", "회담")))
        val eventText = EventFieldAiSuggester.buildUserPrompt(context(), targets).text
        val shared = StringBuilder()
        CharacterFieldAiSuggester.appendTargetSection(shared, targets, mutableListOf())
        assertTrue(eventText.endsWith(shared.toString()))
    }

    @Test fun targetSection_carriesOptionsAndCount() {
        val targets = listOf(target(), target(key = "kind", name = "종류", type = FieldType.SELECT, options = listOf("전투", "회담")))
        val text = EventFieldAiSuggester.buildUserPrompt(context(), targets).text
        assertTrue(text.contains("[추천할 필드] 총 2개"))
        assertTrue(text.contains("옵션: 전투, 회담"))
    }

    @Test fun targetSection_excludesTargetFromFilledFields() {
        // 대상 필드의 현재 값은 스펙 쪽에 실린다 — [입력된 필드]에 또 실으면 두 번 말한다
        val build = EventFieldAiSuggester.buildUserPrompt(
            context(filledFields = listOf("규모" to "대규모", "장소" to "왕도")),
            listOf(target(name = "규모"))
        )
        assertTrue(build.text.contains("장소: 왕도"))
        assertFalse(build.text.contains("규모: 대규모"))
    }

    // ── 시스템 프롬프트 ──

    @Test fun systemPrompt_asksForEventGroundedReasons() {
        val sys = EventFieldAiSuggester.buildSystemPrompt()
        assertTrue(sys.contains("연표 사건"))
        assertTrue(sys.contains("가까운 사건"))
        assertFalse(sys.contains("대결 우열"))
    }

    @Test fun systemPrompt_valueGlossRuleMatchesCharacterAxis() {
        // 규칙 9는 두 축에서 뜻이 같아야 한다(공통 규칙) — 값 뜻은 사건 축 필드에도 실린다.
        // 번호를 새로 매기지 않고 9번을 넓힌 것은 16번(근거 강도)이 조건부 자리이기 때문이다.
        val sys = EventFieldAiSuggester.buildSystemPrompt()
        assertTrue(sys.contains("'값 뜻'이 함께 오면"))
        assertTrue(sys.contains("설명을 값에 붙여 적지 마라"))
        assertEquals(1, Regex("^10\\.", RegexOption.MULTILINE).findAll(sys).count())
    }

    @Test fun systemPrompt_confidenceFloorRuleUsesFreeNumber() {
        // 사건판의 마지막 규칙이 15번이므로 하한 규칙은 16번이어야 한다 —
        // 번호가 겹치면 규칙 하나가 통째로 묻힌다
        val sys = EventFieldAiSuggester.buildSystemPrompt(CharacterFieldAiSuggester.Confidence.MEDIUM)
        assertTrue(sys.contains("16. 사용자는 근거 강도 'medium' 이상만"))
        assertEquals(1, Regex("^16\\.", RegexOption.MULTILINE).findAll(sys).count())
    }

    @Test fun systemPrompt_noFloorRuleWhenAcceptingAll() {
        assertFalse(EventFieldAiSuggester.buildSystemPrompt(null).contains("16."))
    }

    // ── 재료 범위 저장 표기 ──

    @Test fun material_absentKeyMeansDefault() {
        assertEquals(EventAiMaterial.DEFAULT, EventAiMaterial.parse(null))
    }

    @Test fun material_emptyStringMeansAllOff() {
        // '전부 끔'이 저장되지 않으면 다음에 열 때 되살아난다 — 없는 것과 빈 것은 다르다
        assertEquals(emptySet<EventAiMaterial>(), EventAiMaterial.parse(""))
    }

    @Test fun material_unknownTokensAreDropped() {
        assertEquals(setOf(EventAiMaterial.NOVELS), EventAiMaterial.parse("novels,행성,"))
    }

    @Test fun material_unknownTokensAreReportedForImport() {
        // 오타를 조용히 무시하면 적어 넣은 재료가 빠진 채 "적용됨"이 뜬다
        assertEquals(listOf("행성"), EventAiMaterial.unknownTokens("novels, 행성"))
        assertEquals(emptyList<String>(), EventAiMaterial.unknownTokens(""))
        assertEquals(emptyList<String>(), EventAiMaterial.unknownTokens("novels, filled"))
    }

    @Test fun material_serializeIsStableRegardlessOfSetOrder() {
        // 같은 설정이 저장할 때마다 다른 문자열이면 엑셀 왕복에서 값이 바뀐 것처럼 보인다
        val a = linkedSetOf(EventAiMaterial.FILLED_FIELDS, EventAiMaterial.NOVELS)
        val b = linkedSetOf(EventAiMaterial.NOVELS, EventAiMaterial.FILLED_FIELDS)
        assertEquals(EventAiMaterial.serialize(a), EventAiMaterial.serialize(b))
        assertEquals("novels, filled", EventAiMaterial.serialize(a))
    }

    @Test fun material_roundTripsThroughText() {
        for (scope in listOf(EventAiMaterial.DEFAULT, emptySet(), setOf(EventAiMaterial.CHARACTERS))) {
            assertEquals(scope, EventAiMaterial.parse(EventAiMaterial.serialize(scope)))
        }
    }
}
