package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.NameBankEntry

/**
 * 캐릭터 이름 ↔ 이름은행 엔트리의 **대조·연결 판정** (순수 JVM — 하네스 대상, B-124).
 *
 * 이 판정이 순수 계층에 있는 이유는 세 소비처가 **같은 규칙**을 써야 하기 때문이다:
 * 저장 시 자동 대조(ⓑ — [planOnSave]) · 선택 시트의 "이미 은행에 있는가"(ⓐ 역방향 버튼) ·
 * 은행 화면의 사용 표시. 화면마다 따로 적으면 *같은 이름이 어느 화면에서는 일치하고 어느
 * 화면에서는 아닌* 모양이 된다.
 *
 * **자연키의 대조 축은 이름 하나다**(설계 8-2 ⓑ). 은행의 자연키는 이름+성별이지만 성별은
 * 커스텀 필드라 캐릭터 쪽에서 항상 읽을 수 있는 값이 아니고, 그것을 조건에 넣으면 성별을
 * 안 적은 캐릭터가 영영 링크되지 않는다. 성별은 **고르는 순서**에만 쓴다([NameBankPickOrder]).
 */
object NameBankMatch {

    /**
     * 대조용 정규화 — 앞뒤 공백 제거 + **ASCII 대소문자 접기**.
     *
     * **왜 ASCII만인가(의도적인 좁힘):** 이 규칙은 DAO의 조회 조건
     * (`TRIM(name) = :name COLLATE NOCASE`)과 **한 벌이어야 한다.** SQLite의 `NOCASE`는
     * ASCII `A-Z`만 접으므로, 여기서 코틀린 `lowercase()`(유니코드 전체)를 쓰면 순수 규칙이
     * SQL보다 넓어져 **조회가 후보를 안 주는데 판정만 일치한다고 믿는** 어긋남이 생긴다.
     * 좁은 쪽으로 맞춘 결과는 *못 건 링크*이지 *잘못 건 링크*가 아니다 — 기존 데이터를
     * 재해석하는 자리(Q7)라 실패는 안전한 쪽으로 낸다. 못 걸린 것은 ⓐ의 명시 선택이 잇는다.
     *
     * 내부 공백은 접지 않는다 — `"홍 길동"`과 `"홍길동"`은 창작자가 구별해 쓰는 다른 이름이다.
     */
    fun normalize(name: String): String {
        val trimmed = name.trim()
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            sb.append(if (ch in 'A'..'Z') ch + 32 else ch)
        }
        return sb.toString()
    }

    fun sameName(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /**
     * 이 저장이 은행에 무엇을 할 것인가.
     *
     * @param linkEntryId 새로 사용 표시를 걸 엔트리 (없으면 null)
     * @param releaseEntryIds 사용 표시를 풀 엔트리 — 이름이 바뀌어 더는 이 캐릭터의 것이 아닌 것들
     * @param requestedTaken 사용자가 시트에서 **고른** 엔트리가 이미 남의 것이거나 사라져 걸지 못했다.
     *   조용히 다른 동명 엔트리로 갈아치우지 않는다 — 고른 것과 다른 것이 걸리면 그것은
     *   사용자가 지시하지 않은 결과다(변수 제어). 걸지 않고 **말한다.**
     */
    data class LinkPlan(
        val linkEntryId: Long? = null,
        val releaseEntryIds: List<Long> = emptyList(),
        val requestedTaken: Boolean = false
    ) {
        /** 은행에 쓸 것이 하나도 없는가 — 고지할 것도 없다는 뜻이다. */
        val isNoop: Boolean get() = linkEntryId == null && releaseEntryIds.isEmpty() && !requestedTaken
    }

    /**
     * 저장된 캐릭터의 이름으로 은행 연결을 계획한다 (ⓑ Q7 = 자동 + 고지).
     *
     * @param savedName 저장된 캐릭터의 이름(`Character.name`).
     * @param characterId 저장된 캐릭터 id.
     * @param requestedEntryId 편집 폼에서 **명시적으로 고른** 엔트리(ⓐ). 없으면 null이고 자동 대조만 한다.
     * @param candidates [savedName]과 이름이 같은 은행 엔트리 — 호출측이 조회로 좁혀 준다.
     *   좁히기가 이 함수의 규칙보다 넓어도 무해하다(여기서 [sameName]으로 다시 거른다).
     * @param currentlyLinked 지금 이 캐릭터에 걸려 있는 엔트리들.
     *
     * 규칙:
     * - **빼앗지 않는다**(B-3 규약 그대로) — 남이 쓰는 엔트리는 후보에서 제외한다.
     * - 이미 이 캐릭터가 물고 있고 이름도 그대로면 **아무 일도 하지 않는다**(고지도 없다).
     *   저장할 때마다 *"연결했습니다"*가 뜨면 그것이 소음이다(원칙 04).
     * - 동명 미사용 엔트리가 여럿이면 **먼저 등록된 것**(id 오름차순)을 고른다. 목록 순서에
     *   판정을 맡기면 같은 저장이 실행마다 다른 엔트리를 문다.
     */
    fun planOnSave(
        savedName: String,
        characterId: Long,
        requestedEntryId: Long?,
        candidates: List<NameBankEntry>,
        currentlyLinked: List<NameBankEntry>
    ): LinkPlan {
        val name = savedName.trim()
        // 이름이 비면 걸 것이 없다. 다만 **풀 것은 있다** — 이름을 지운 저장도 옛 링크를 남기지 않는다.
        val matching = if (name.isEmpty()) emptyList() else candidates.filter { sameName(it.name, name) }

        // 이 캐릭터가 물고 있던 것 중 이름이 더는 맞지 않는 것은 푼다.
        val keep = currentlyLinked.filter { name.isNotEmpty() && sameName(it.name, name) }
        val release = currentlyLinked.filter { linked -> keep.none { it.id == linked.id } }.map { it.id }

        // 남이 쓰는 것은 후보가 아니다. 내가 쓰는 것은 후보다(같은 캐릭터의 재저장).
        fun free(e: NameBankEntry) = !e.isUsed || e.usedByCharacterId == null || e.usedByCharacterId == characterId

        if (requestedEntryId != null) {
            val requested = matching.firstOrNull { it.id == requestedEntryId }
            return when {
                requested == null || !free(requested) ->
                    // 고른 것이 사라졌거나(삭제) 남의 것이 됐다(그 사이 다른 저장) — 갈아치우지 않고 말한다.
                    LinkPlan(releaseEntryIds = release, requestedTaken = true)
                keep.any { it.id == requested.id } ->
                    // 이미 그 엔트리를 물고 있다 — 다시 걸 것도 말할 것도 없다.
                    LinkPlan(releaseEntryIds = release)
                else ->
                    // **고른 것 하나만 남긴다.** 이름이 같은 옛 링크도 함께 푼다 — 안 그러면
                    // 동명 엔트리 둘을 한 캐릭터가 물어, 은행 화면이 같은 캐릭터를 두 줄에
                    // 걸쳐 보여 주고 [미사용으로 변경] 한 번으로는 풀리지 않는 상태가 된다.
                    // (자동 대조 경로는 keep이 있으면 새로 걸지 않으므로 이 자리가 없다.)
                    LinkPlan(
                        linkEntryId = requested.id,
                        releaseEntryIds = release + keep.map { it.id }.filter { it != requested.id }
                    )
            }
        }

        // 자동 대조(ⓑ). 이미 맞는 것을 물고 있으면 그대로 둔다.
        if (keep.isNotEmpty()) return LinkPlan(releaseEntryIds = release)
        val picked = matching.filter { free(it) }.minByOrNull { it.id }
        return LinkPlan(linkEntryId = picked?.id, releaseEntryIds = release)
    }
}
