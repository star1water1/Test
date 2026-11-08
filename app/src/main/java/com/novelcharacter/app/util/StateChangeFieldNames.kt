package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 상태변화 `fieldKey`의 **사람 이름을, 지금 이 캐릭터의 폼 밖에서도** 찾는다.
 *
 * 이름은 본래 이 캐릭터가 가진 필드에서 찾는다([com.novelcharacter.app.ui.common.StateChangeFieldLabel]).
 * 그런데 캐릭터를 작품 미배정으로 옮기거나 다른 세계관으로 옮기면 옛 필드는 폼 밖으로 나가고,
 * 그 키를 든 상태변화는 목록·수정·삭제 창 모두에서 **내부 키**(`job_title`)로 불렸다
 * (S8 에뮬레이터 2026-11-08 · `1-ㅎ4`). 정의는 여전히 DB에 있다 — 다른 구역에 살 뿐이다.
 *
 * 같은 키가 여러 구역에 있을 수 있다. **이름이 하나로 모일 때만** 그 이름을 쓴다 —
 * 갈리면 어느 쪽인지 모르므로 키 그대로 둔다(틀린 이름보다 키가 낫다).
 */
object StateChangeFieldNames {

    /** 이름을 찾아야 할 키 — 특수 키(`__birth` 등, 앱이 따로 번역한다)를 뺀 나머지. */
    fun customKeys(fieldKeys: Collection<String>): Set<String> =
        fieldKeys.filterTo(mutableSetOf()) { it.isNotBlank() && !it.startsWith(SPECIAL_PREFIX) }

    /** 키 → 이름. 같은 키의 정의들이 **한 이름**을 말할 때만 싣는다. */
    fun unambiguous(definitions: List<FieldDefinition>): Map<String, String> =
        definitions.groupBy { it.key }.mapNotNull { (key, defs) ->
            defs.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().singleOrNull()
                ?.let { key to it }
        }.toMap()

    /** `CharacterStateChange.KEY_*`가 모두 이 머리로 시작한다(그쪽은 Room 엔티티라 여기서 잇지 않는다). */
    private const val SPECIAL_PREFIX = "__"
}
