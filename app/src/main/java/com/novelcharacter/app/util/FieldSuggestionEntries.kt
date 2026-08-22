package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldValueEntry

/**
 * 입력 폼 자동완성에 세울 값 라이브러리 엔트리를 고른다 (B-129).
 *
 * **왜 순수 계층으로 올렸는가.** 이 잣대는 종전에 SQL에만 있었다 —
 * `FieldValueEntryDao.getForUniverse`의 `isHidden = 0` + `ORDER BY usageCount DESC, value`
 * (그 질의는 2026.08.22에 **없앴다** — 마지막 소비처였던 캐릭터·사건 축이 이리로 왔다).
 * 그 질의는 `fd.universeId = :universeId`로 묶여 있어 **전역 구역(무소속)에서는 원리적으로
 * 아무것도 돌려주지 못한다.** 무소속 작품 폼이 필드를 그리기 시작하면(B-129) 그 자리는
 * *필드는 있는데 제안만 없는* 상태가 되고, 그것은 고장과 구분되지 않는다.
 *
 * 폼은 허용 목록 판정을 위해 **이미 같은 필드의 엔트리를 전부 읽어 두었다**
 * ([com.novelcharacter.app.data.repository.FieldValueLibraryRepository.entriesForFields] —
 * 숨김 포함). 거기서 걸러 내면 구역을 가리지 않고, 폼을 그릴 때마다 나가던 **두 번째 질의가
 * 통째로 없어진다.**
 *
 * 잣대를 한 벌로 두는 것이 요점이다 — 두 벌이면 폼이 권하는 값과 라이브러리가 든 값이
 * 갈리고, 갈렸을 때 **어느 쪽이 옳은지 화면에서 알 길이 없다.**
 */
object FieldSuggestionEntries {

    /**
     * @param entriesByField 필드 id → 그 필드의 엔트리 전체(**숨김 포함**)
     * @return 필드 id → 제안에 세울 엔트리. 빈 목록이 되는 필드는 키 자체를 남기지 않는다.
     */
    fun from(entriesByField: Map<Long, List<FieldValueEntry>>): Map<Long, List<FieldValueEntry>> {
        if (entriesByField.isEmpty()) return emptyMap()
        val out = HashMap<Long, List<FieldValueEntry>>(entriesByField.size)
        for ((fieldId, entries) in entriesByField) {
            val visible = suggestable(entries)
            if (visible.isNotEmpty()) out[fieldId] = visible
        }
        return out
    }

    /**
     * 한 필드 몫 — DAO의 `isHidden = 0` + `ORDER BY usageCount DESC, value`와 **같은 잣대**다.
     *
     * 숨김을 여기서 빼는 것이 계약의 요점이다: 저장소는 숨김까지 준 뒤 *용도별 필터링은
     * 호출측이 한다*고 못박았고(허용 목록 판정은 숨긴 값도 목록의 일부로 본다), 제안은
     * 그 계약에서 **숨김을 빼는 쪽**이다.
     */
    fun suggestable(entries: List<FieldValueEntry>): List<FieldValueEntry> =
        entries.asSequence()
            .filterNot { it.isHidden }
            .sortedWith(compareByDescending<FieldValueEntry> { it.usageCount }.thenBy { it.value })
            .toList()
}
