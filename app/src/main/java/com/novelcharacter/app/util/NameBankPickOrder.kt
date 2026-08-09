package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.NameBankEntry

/**
 * 편집 폼의 **은행 선택 시트** 목록 구성 (순수 JVM — 하네스 대상, B-124 설계 8-2 ⓐ).
 *
 * 정렬 규칙이 순수 계층에 있는 이유는 *"미사용 우선 + 성별 일치 우선"*이 **두 축의 우선순위**라
 * 화면에서 즉석으로 적으면 다음 화면이 순서를 뒤집기 때문이다(은행 화면의 정렬과 뜻이 다르다 —
 * 그쪽은 창고 훑기이고 이쪽은 *지금 이 캐릭터에게 줄 이름 고르기*다).
 */
object NameBankPickOrder {

    /**
     * @param entries 은행 전체.
     * @param query 검색어 — 이름·출처·메모를 본다(은행 화면의 검색 축과 같은 셋).
     * @param preferredGender 편집 중인 캐릭터의 성별 값 — [com.novelcharacter.app.data.model.SemanticRole.GENDER]를
     *   단 필드에서 읽는다. 비었으면 이 축은 무효다(라벨 추측 금지 — R-20).
     *
     * 순서: **미사용 먼저 → 성별 일치 먼저 → 최근 등록순.** 셋째 축이 있는 것은 앞 둘이
     * 같을 때 목록이 실행마다 흔들리지 않게 하기 위해서다.
     *
     * **사용된 엔트리를 빼지 않고 뒤로 미는 것이 의도다** — 빼면 *"내가 등록한 이름이 왜
     * 목록에 없지"*가 되고, 사용 중임을 보여 주는 편이 창고의 현황을 말해 준다(원칙 04 —
     * 일일이 확인해야 아는 데이터를 만들지 않는다). 거는 것은 [NameBankMatch]가 막는다.
     */
    fun order(
        entries: List<NameBankEntry>,
        query: String,
        preferredGender: String?
    ): List<NameBankEntry> {
        val q = query.trim()
        val filtered = if (q.isEmpty()) entries else entries.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.origin.contains(q, ignoreCase = true) ||
                it.notes.contains(q, ignoreCase = true)
        }
        val gender = preferredGender?.trim().orEmpty()
        return filtered.sortedWith(
            compareBy<NameBankEntry> { if (it.isUsed) 1 else 0 }
                .thenBy { if (gender.isNotEmpty() && it.gender.trim().equals(gender, ignoreCase = true)) 0 else 1 }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id }
        )
    }
}
