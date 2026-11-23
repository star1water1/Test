package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.CharacterRelationship

/**
 * 관계 편집의 **순수 판정 규칙** — 창구를 모르는 자리.
 *
 * ## 왜 이 파일이 있는가 (R-46)
 *
 * 겹침·중복 판정은 줄곧 `ai/NaturalBatchRelationshipEdits`의 **사적 함수**로만 있었다.
 * 그 파일은 AI 계획 모델(`NaturalBatchPlan.Operation` 등)에 묶여 그대로는 못 쓰는데,
 * PC 동반 편집이 두 번째 편집 창구를 열면서 선택이 둘로 좁혀졌다 — **복붙하거나 꺼내거나.**
 *
 * 복붙은 R-46이 금지한 모양이다(*"복붙은 그 방향을 자리마다 뒤집는다"*). 겹침 판정을 웹에
 * 한 벌 더 적으면 *"AI로 만들면 막히는데 PC로 만들면 통과하는"* 자리가 생기고, 그것은
 * 사용자가 알 수 없는 종류의 갈림이다. 그래서 꺼냈다 — [CharacterFormGates]가 저장 관문
 * 둘에 한 것과 같은 처분이고, 이 폴더의 [FieldValueRules]와 같은 계열이다.
 *
 * ## 판정은 공유하고 **배치는 창구가 한다**
 *
 * 이 구분이 중요하다. [overlaps] · [sameMeaning] · [findOverlap] · [isValidIntensity] ·
 * [isAutomatic]은 **두 창구가 같은 구현을 부른다** — 같은 질문에 반드시 같은 답이 나온다.
 *
 * 그러나 *그 답을 어떤 차례로 묻고 어떤 사유로 내보내는가*는 창구마다 다르다. AI 쪽은
 * 스냅샷이 낡았는가(`STALE`)·이미 충족됐는가(`ALREADY_SATISFIED`)를 가려야 하고, PC 쪽은
 * 사용자가 방금 적은 것을 고치라고 돌려보낸다. 그래서 AI는 위 낱개 함수만 부르고(차례는
 * 그 파일이 그대로 든다), PC는 [validate]가 조립한 것을 쓴다. **판정이 두 벌이 되는 것은
 * 막고, 창구마다 다른 흐름은 그대로 둔다.**
 *
 * ## 관문은 막지 않는다 — 사실을 낸다
 *
 * [CharacterFormGates]와 같다. 이 객체의 어느 함수도 저장을 거부하지 않고 *무엇이 어긋났는가*를
 * 돌려줄 뿐이며, **고지와 교정 경로는 창구가 입힌다.** 화면 문구는 여기 두지 않는다 —
 * [Rejection]은 사유 코드이지 문구가 아니다.
 */
object RelationshipRules {

    /** 관계 강도의 하한. 그래프 선 굵기의 근거라 0이 될 수 없다. */
    const val MIN_INTENSITY = 1

    /** 관계 강도의 상한. */
    const val MAX_INTENSITY = 10

    /** 강도가 저장 가능한 범위인가. */
    fun isValidIntensity(intensity: Int): Boolean = intensity in MIN_INTENSITY..MAX_INTENSITY

    /**
     * **세력 소속에서 파생된 자동 관계인가.**
     *
     * `factionId`가 있으면 그 행은 세력 소속이 만든 것이고 **손으로 고칠 것이 아니다** —
     * 고치거나 지워도 다음 재동기에서 되살아나거나 어긋난다. 그 사실을 `factionId != null`로
     * 자리마다 다시 적지 않기 위해 이름을 붙였다.
     */
    fun isAutomatic(row: CharacterRelationship): Boolean = row.factionId != null

    /**
     * 이 관계에서 [characterId]의 **상대 쪽 끝**.
     *
     * 양 끝이 같은 행은 저장되지 않으므로([CharacterRepository.insertRelationship]의 `require`)
     * 어느 쪽이든 하나만 맞는다. [characterId]가 양 끝 어디에도 없으면 `characterId1`을
     * 돌려주는데, 그 경우는 호출부가 먼저 가려야 하는 잘못이다.
     */
    fun otherEndOf(row: CharacterRelationship, characterId: Long): Long =
        if (row.characterId1 == characterId) row.characterId2 else row.characterId1

    /** 이 관계가 [characterId]에 닿는가. */
    fun touches(row: CharacterRelationship, characterId: Long): Boolean =
        row.characterId1 == characterId || row.characterId2 == characterId

    /**
     * 두 관계가 **같은 자리를 차지하는가.**
     *
     * 같은 유형이고 양 끝이 같으면 겹친다. 끝이 뒤바뀐 경우는 **한쪽이라도 양방향일 때만**
     * 겹치는 것으로 본다 — 단방향 `A → B`와 단방향 `B → A`는 서로 다른 사실이라 둘 다
     * 있을 수 있다.
     *
     * **Room의 유일 인덱스로는 이것을 막을 수 없다.** 그 인덱스는
     * `(characterId1, characterId2, relationshipType)`이라 **끝이 뒤바뀐 짝을 다른 행으로 본다** —
     * 그래서 이 판정이 코드에 있어야 하고, 두 창구가 같은 것을 불러야 한다.
     */
    fun overlaps(left: CharacterRelationship, right: CharacterRelationship): Boolean =
        left.relationshipType == right.relationshipType &&
            ((left.characterId1 == right.characterId1 && left.characterId2 == right.characterId2) ||
                (left.characterId1 == right.characterId2 && left.characterId2 == right.characterId1 &&
                    (left.isBidirectional || right.isBidirectional)))

    /**
     * 겹치는 데다 **담은 내용까지 같은가** — 즉 더해도 바뀌는 것이 없는가.
     *
     * `description`과 `intensity`까지 보는 것이 [overlaps]와의 차이다. 겹치지만 내용이 다르면
     * *"고치려는 것"*이고, 내용까지 같으면 *"이미 있는 것"*이다.
     */
    fun sameMeaning(left: CharacterRelationship, right: CharacterRelationship): Boolean =
        overlaps(left, right) && left.isBidirectional == right.isBidirectional &&
            left.description == right.description && left.intensity == right.intensity

    /**
     * [candidates] 중 [proposed]와 겹치는 첫 행. 없으면 null.
     *
     * @param candidates **고치려는 행 자신은 빼고 넘긴다** — 넣으면 자기와 겹친다고 나온다.
     */
    fun findOverlap(
        candidates: List<CharacterRelationship>,
        proposed: CharacterRelationship
    ): CharacterRelationship? = candidates.firstOrNull { overlaps(it, proposed) }

    /** 저장을 멈춘 **사유 코드**. 화면 문구가 아니다 — 창구가 문구를 입힌다. */
    enum class Rejection {
        /** 양 끝이 같은 인물이다. */
        SELF_REFERENCE,

        /** 이 세계관의 관계 유형 목록에 없는 유형이다. */
        UNKNOWN_TYPE,

        /** 강도가 [MIN_INTENSITY]~[MAX_INTENSITY] 밖이다. */
        BAD_INTENSITY,

        /** 고치려는 행 자체가 **세력 자동 관계**다. */
        AUTOMATIC_ROW,

        /** 겹치는 자리를 **세력 자동 관계**가 이미 차지하고 있다. */
        AUTOMATIC_OVERLAP,

        /** 같은 뜻의 관계가 이미 있다 — 저장해도 바뀌는 것이 없다. */
        DUPLICATE,

        /** 같은 자리를 차지하는 다른 관계가 있다. */
        OVERLAP
    }

    /** [validate]의 판정. */
    sealed class Verdict {

        /** 저장해도 된다. */
        object Allowed : Verdict()

        /**
         * 멈춘다. [blocking]은 길을 막은 행 — 겹침 사유일 때만 있고, 창구가 *"무엇이 막았는가"*를
         * 보여 주는 데 쓴다(사유만 내고 무엇이 막았는지 감추면 사용자가 고칠 수 없다).
         */
        data class Rejected(
            val rejection: Rejection,
            val blocking: CharacterRelationship? = null
        ) : Verdict()
    }

    /**
     * PC 창구의 조립된 판정 — 한 관계를 더하거나 고칠 수 있는가.
     *
     * **차례가 판단이다.** 자동 관계를 가장 먼저 보는 것은 그것이 *고칠 수 없는 행*이라서다 —
     * 유형이나 강도를 먼저 따지면 사용자가 그것을 고쳐 다시 보내고, 그래도 막힌다. 막는 사유는
     * 처음부터 사유 그대로 나가야 한다.
     *
     * @param proposed 저장하려는 모양.
     * @param existing 고치는 중이면 **고치기 전의 그 행**, 더하는 중이면 null.
     * @param others 이 캐릭터의 다른 관계들 — **[existing]은 빼고 넘긴다.**
     * @param allowedTypes 이 세계관의 관계 유형(`Universe.getRelationshipTypes()`).
     */
    fun validate(
        proposed: CharacterRelationship,
        existing: CharacterRelationship?,
        others: List<CharacterRelationship>,
        allowedTypes: List<String>
    ): Verdict {
        // 고칠 수 없는 행인가 — 가장 먼저 본다(위 KDoc).
        if (existing != null && isAutomatic(existing)) {
            return Verdict.Rejected(Rejection.AUTOMATIC_ROW, existing)
        }
        if (proposed.characterId1 == proposed.characterId2) {
            return Verdict.Rejected(Rejection.SELF_REFERENCE)
        }
        if (proposed.relationshipType !in allowedTypes) {
            return Verdict.Rejected(Rejection.UNKNOWN_TYPE)
        }
        if (!isValidIntensity(proposed.intensity)) {
            return Verdict.Rejected(Rejection.BAD_INTENSITY)
        }
        val blocking = findOverlap(others, proposed)
        if (blocking != null) {
            // **자동 관계가 막은 것을 수동 겹침과 가른다.** 고칠 자리가 다르다 — 수동 겹침은
            // 그 관계를 고치면 되고, 자동 관계는 세력 쪽 소속을 고쳐야 한다.
            if (isAutomatic(blocking)) return Verdict.Rejected(Rejection.AUTOMATIC_OVERLAP, blocking)
            if (sameMeaning(blocking, proposed)) return Verdict.Rejected(Rejection.DUPLICATE, blocking)
            return Verdict.Rejected(Rejection.OVERLAP, blocking)
        }
        return Verdict.Allowed
    }
}
