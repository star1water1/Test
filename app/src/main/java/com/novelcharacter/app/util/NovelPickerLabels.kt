package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Novel

/**
 * **고르는 목록에서 작품을 가리키는 이름표** — 제목이 겹치는 것만 세계관을 덧붙인다.
 *
 * ## 왜 있는가
 *
 * 캐릭터 편집의 작품 스피너는 `novels.map { it.title }`이라, **세계관이 다른 같은 이름
 * 작품을 구별해 주지 않았다**(2026.11.07 워크스루 S25 관찰). 두 줄이 글자 그대로 같으면
 * 사용자는 어느 쪽을 고르는지 알 수 없고, 작품 선택은 **그 캐릭터의 동적 필드 구역을
 * 정하는 선택**이라 잘못 고르면 폼 자체가 다른 필드로 선다.
 *
 * 가져오기는 같은 상황을 이미 `'제목'(세계관: A, B)` 꼴로 말하고 있었다
 * ([com.novelcharacter.app.excel.novelAmbiguityDetail]) — 화면도 같은 결로 말한다.
 *
 * ## 왜 겹칠 때만 붙이는가
 *
 * 전부에 붙이면 **세계관이 하나인 사용자의 모든 줄이 길어진다**(원칙 04 — 조작 마찰).
 * 이 앱의 흔한 모양이 그쪽이다. 겹칠 때만 붙이면 구별이 필요한 자리에만 글자가 늘고,
 * 목록이 바뀌면 판정도 함께 바뀐다 — **목록이 판정의 재료**이므로 상태를 들지 않는다.
 *
 * ## 겹친 것이 세계관까지 같을 때
 *
 * 덧붙여도 구별되지 않는다. 그래도 **세계관은 붙인다** — 어느 세계관의 동명인지는 알려
 * 주는 것이 모르는 것보다 낫고, 그 경우의 처방(이름을 고치거나 코드로 가리기)은 목록이
 * 아니라 작품 편집의 몫이다. 없는 구별을 지어내지는 않는다.
 */
object NovelPickerLabels {

    /**
     * @property title 작품 제목
     * @property universeName 소속 세계관 이름. `null`은 **무소속**이다(열이 없는 것과 다르다).
     */
    data class Item(val title: String, val universeName: String?)

    /** 무소속 작품의 표기 — 화면의 '세계관 미배정' 어휘와 같은 결이다. */
    const val UNASSIGNED = "세계관 미배정"

    /**
     * [items]와 **같은 순서·같은 길이**의 이름표. 순서를 바꾸지 않는 것이 계약이다 —
     * 부르는 쪽은 고른 자리(position)로 원래 항목을 되찾는다.
     */
    fun labels(items: List<Item>): List<String> {
        val duplicatedTitles = duplicatedTitles(items.map { it.title })
        return items.map { item ->
            if (item.title !in duplicatedTitles) item.title
            else "${item.title} (세계관: ${item.universeName?.takeIf { it.isNotBlank() } ?: UNASSIGNED})"
        }
    }

    private fun duplicatedTitles(titles: List<String>): Set<String> =
        titles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    /** 이 목록에 같은 제목이 둘 이상 있는가 — 없으면 세계관 이름을 물을 필요가 없다. */
    fun hasDuplicateTitle(novels: List<Novel>): Boolean =
        duplicatedTitles(novels.map { it.title }).isNotEmpty()

    /**
     * 작품 목록의 이름표 — [universeNames]는 세계관 id → 이름이다.
     * 무소속 작품(`universeId == null`)은 [UNASSIGNED]로 선다.
     */
    fun forNovels(novels: List<Novel>, universeNames: Map<Long, String>): List<String> {
        val base = labels(novels.map { Item(it.title, it.universeId?.let { id -> universeNames[id] }) })
        // 세계관이 있는데 그 이름을 모르면(목록이 아직 안 왔다) **제목만 둔다** — `null`은 무소속이라
        // 그대로 넘기면 '세계관 미배정'이라는 거짓 구별을 짓는다.
        return novels.mapIndexed { i, n ->
            val id = n.universeId
            if (id != null && id !in universeNames) n.title else base[i]
        }
    }

    /**
     * 작품 목록의 이름표 — **동명이 있을 때만** [loadUniverseNames]를 부른다.
     *
     * 흔한 모양(동명 없음)에서 세계관 조회를 하지 않는 것이 이 판의 비용 계약이다 — 고르는
     * 목록이 열세 자리라 자리마다 세계관 표를 읽으면 열 때마다 질의가 하나씩 는다.
     */
    suspend fun forNovels(
        novels: List<Novel>,
        loadUniverseNames: suspend () -> Map<Long, String>
    ): List<String> =
        if (!hasDuplicateTitle(novels)) novels.map { it.title }
        else forNovels(novels, loadUniverseNames())
}
