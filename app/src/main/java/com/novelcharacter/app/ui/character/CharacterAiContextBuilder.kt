package com.novelcharacter.app.ui.character

import com.novelcharacter.app.ai.CharacterFieldAiSuggester

/**
 * AI 추천 컨텍스트 조립의 **단일 소스** (P-A A-3).
 *
 * 캐릭터 편집 화면과 보충(랜덤) 탭 인라인 편집이 같은 id의 위젯·같은 [DynamicFieldFormBuilder]·
 * 같은 [CharacterViewModel]을 쓰므로 조립 규칙도 하나여야 한다 — 각 화면이 복사해 가지면
 * 두 화면의 추천 근거가 반드시 갈린다(이 저장소가 반복해서 겪은 실패 형태).
 *
 * 저장된 DB 상태가 아니라 **폼의 라이브 입력값**을 쓴다(미저장 편집 중에도 최신 정보 기준).
 * 소속·관계는 저장된 캐릭터에만 존재하므로 [Host.characterId]가 -1이면 조회를 건너뛴다.
 * 조회 실패(null)는 '없음'(빈 목록)과 구별해 loadFailures로 고지한다(변수 제어).
 */
object CharacterAiContextBuilder {

    /** 화면이 구현하는 위젯 접근 계약 — 두 화면 모두 같은 의미의 입력 위젯을 가진다. */
    interface Host {
        fun rawName(): String
        fun firstName(): String
        fun lastName(): String
        fun aliasesRaw(): String
        fun tagsRaw(): String
        fun memo(): String
        fun imagePaths(): List<String>

        /** -1이면 미저장 신규 — 소속·관계 조회를 건너뛴다 */
        fun characterId(): Long
        fun formBuilder(): DynamicFieldFormBuilder
    }

    suspend fun build(
        host: Host,
        viewModel: CharacterViewModel
    ): CharacterFieldAiSuggester.CharacterAiContext {
        val formBuilder = host.formBuilder()
        val valuesById = formBuilder.collectFieldValues(0L).associate { it.fieldDefinitionId to it.value }
        val filledFields = formBuilder.fieldDefinitions.mapNotNull { fd ->
            valuesById[fd.id]?.let { fd.name to it }
        }
        val firstName = host.firstName().trim()
        val lastName = host.lastName().trim()
        // Character.displayName과 동일 규칙: 성/이름이 있으면 조합, 없으면 name
        val name = if (firstName.isNotBlank() || lastName.isNotBlank()) {
            listOf(lastName, firstName).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            host.rawName().trim()
        }
        val aliases = host.aliasesRaw().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val tags = host.tagsRaw().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val characterId = host.characterId()
        val imageTags = viewModel.getImageTagsForPaths(host.imagePaths())
        val factions =
            if (characterId != -1L) viewModel.getFactionNamesForCharacter(characterId) else emptyList()
        val relationships =
            if (characterId != -1L) viewModel.getRelationshipSummariesForCharacter(characterId) else emptyList()
        val loadFailures = buildList {
            if (imageTags == null) add("이미지 태그")
            if (factions == null) add("소속 세력")
            if (relationships == null) add("관계")
        }
        return CharacterFieldAiSuggester.CharacterAiContext(
            name = name,
            aliases = aliases,
            tags = tags,
            memo = host.memo(),
            filledFields = filledFields,
            imageTags = imageTags ?: emptyList(),
            factions = factions ?: emptyList(),
            relationships = relationships ?: emptyList(),
            loadFailures = loadFailures
        )
    }
}
