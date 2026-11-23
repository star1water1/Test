package com.novelcharacter.app.pcbridge

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.repository.RelationshipRules
import com.novelcharacter.app.util.stringOr
import org.json.JSONObject

/**
 * PC 관계 편집의 **기술자 생성과 값 수집** (순수 계층 — Android·서버 라이브러리 비의존).
 *
 * [BridgeForm]이 필드 폼에 하는 일을 관계 목록에 한다. 같은 규약 아래 있다 —
 * **자바스크립트는 도메인 로직을 갖지 않는다**(설계 5-5).
 *
 * 그래서 이 파일이 다음을 **코틀린에서 정해** 내려보낸다:
 *
 * - **화살표 글자**([arrowFor]). 양방향인가 · 보고 있는 캐릭터가 출발점인가에 따라 갈리는데,
 *   그 판정을 JS에 두면 앱의 관계 목록과 PC의 화살표가 갈릴 수 있다.
 * - **고칠 수 있는가와 그 사유**([RelationshipView.readOnly] · [RelationshipView.notice]).
 * - **양 끝의 배치**([endpointsFor]) — 아래 KDoc이 이 자리의 함정을 적는다.
 *
 * 겹침·중복·강도 판정은 여기에도 적지 않는다. [RelationshipRules]가 단일 소스이고 AI 쪽이
 * 부르는 것과 **같은 구현**이다(R-46).
 */
object BridgeRelationships {

    /** 세력 소속에서 파생된 자동 관계라 PC에서 고치지 않는다. */
    const val NOTICE_FACTION = "faction"

    /** 상대 쪽 인물을 찾지 못했다 — 값은 보여 주고 고치는 길은 열지 않는다. */
    const val NOTICE_MISSING_OTHER = "missingOther"

    /**
     * 상대가 **지금은 다른 세계관에 있다** — 고치면 거절되지만 지울 수는 있다.
     *
     * **읽기 전용으로 만들지 않는 것이 판단이다.** 이 상태는 1단계의 웹 폼이 만들 수 있다 —
     * 캐릭터의 작품을 바꾸면 세계관이 따라 바뀌고, 그러면 기존 관계가 세계관을 가로지른
     * 채 남는다. 그때 사용자가 가장 하고 싶은 일은 **그 관계를 지우는 것**이므로 지우는
     * 길을 막으면 안 된다. 그래서 단추는 그대로 두고 **먼저 사실을 알린다** — 저장을
     * 눌러 보고 거절로 알게 하지 않는 것이 검증 → 고지의 순서다.
     *
     * 세력 자동 관계에는 붙이지 않는다. 그쪽은 이미 사유가 있고, 둘을 겹쳐 적으면
     * *무엇을 먼저 고쳐야 하는가*가 흐려진다.
     */
    const val ADVISORY_CROSS_UNIVERSE = "crossUniverse"

    /**
     * 관계 저장이 막혔다는 **관문 이름**. 응답의 `gate` 값이고 웹이 이 글자로 가른다.
     *
     * **상수로 두는 이유는 짝이 두 언어에 살기 때문이다.** 서버가 적는 글자와 웹이 보는
     * 글자가 갈리면 사유가 화면에 닿지 않는데, 컴파일러는 자바스크립트 쪽을 보지 못한다 —
     * 1단계가 자산 주소에서 물린 그 부류다. 여기 두면 서버 쪽은 컴파일러가, 웹 쪽은
     * `BridgeWebTest`가 지킨다.
     */
    const val GATE = "relationship"

    /** 관계 삭제가 **확인을 기다린다**는 관문 이름. */
    const val GATE_DELETE = "relationshipDelete"

    /** 삭제 확인을 싣는 `resolve` 칸의 이름. */
    const val RESOLVE_DELETE = "delete"

    /** 양방향. */
    const val ARROW_BOTH = "↔"

    /** 보고 있는 캐릭터에서 상대로. */
    const val ARROW_OUT = "→"

    /** 상대에서 보고 있는 캐릭터로. */
    const val ARROW_IN = "←"

    /** 관계 한 줄의 기술자. 웹은 이 갈래만 보고 그린다. */
    data class RelationshipView(
        val id: Long,
        /** 왕복 안정 식별자. 웹은 쓰지 않고 그대로 되돌려보낸다. */
        val code: String?,
        val otherCharacterId: Long,
        val otherCharacterName: String,
        val type: String,
        val description: String,
        val intensity: Int,
        val bidirectional: Boolean,
        /** 저장된 `characterId1`이 보고 있는 캐릭터인가. 양방향이면 뜻이 없다. */
        val outgoing: Boolean,
        /** [ARROW_BOTH] · [ARROW_OUT] · [ARROW_IN] 중 하나 — **코틀린이 고른다.** */
        val arrow: String,
        /** 고칠 수 없는가. 참이면 웹이 고치기·삭제 단추를 그리지 않는다. */
        val readOnly: Boolean,
        /** 읽기 전용인 **사유 코드**. null이면 사유가 없다(=고칠 수 있다). */
        val notice: String?,
        /** [NOTICE_FACTION]일 때 어느 세력인가 — *"어디를 고치면 되는가"*를 말하기 위해 싣는다. */
        val factionName: String?,
        /**
         * 고칠 수는 있지만 **알아 둘 것이 있다**는 사유 코드. 지금은
         * [ADVISORY_CROSS_UNIVERSE] 하나다. [notice]와 달리 단추를 막지 않는다.
         */
        val advisory: String? = null
    )

    /**
     * 상대로 고를 수 있는 인물 하나.
     *
     * **같은 세계관 안에서만 고를 수 있다.** AI 쪽 `validEndpoints`가 마지막에
     * `endpoints[0].universeId == endpoints[1].universeId`를 요구하는 그 조건이고, 세계관이
     * 다른 두 인물을 이으면 그 관계는 어느 세계관의 것도 아니게 된다.
     */
    data class Candidate(
        val id: Long,
        val name: String,
        /** 작품명. 같은 세계관에 작품이 여럿이면 이름만으로는 누구인지 모른다. */
        val novelTitle: String
    )

    /** 웹이 되돌려보낸 한 관계의 **날 값**. 판정은 아직 하지 않았다. */
    data class Draft(
        val otherCharacterId: Long,
        val type: String,
        val description: String,
        val intensity: Int,
        val bidirectional: Boolean,
        /** 단방향일 때 보고 있는 캐릭터가 출발점인가. 양방향이면 쓰이지 않는다. */
        val outgoing: Boolean
    )

    /** 본문을 읽지 못한 사유. [parseDraft]가 null 대신 이것을 낸다. */
    sealed class DraftResult {
        data class Parsed(val draft: Draft) : DraftResult()

        /** [what]은 사유 코드(화면 문구가 아니다). */
        data class Bad(val what: String) : DraftResult()
    }

    /** 보고 있는 캐릭터를 기준으로 화살표를 고른다. */
    fun arrowFor(bidirectional: Boolean, outgoing: Boolean): String = when {
        bidirectional -> ARROW_BOTH
        outgoing -> ARROW_OUT
        else -> ARROW_IN
    }

    /**
     * 저장된 행들을 기술자로.
     *
     * @param rows 이 캐릭터에 닿는 관계 — DAO가 준 순서(`displayOrder`, `createdAt`)를 **그대로 둔다.**
     *   끌기 정렬은 이 단계의 밖이고(설계 9-1), 여기서 다시 정렬하면 앱 목록과 순서가 갈린다.
     * @param selfId 보고 있는 캐릭터.
     * @param names 캐릭터 id → 이름. **없는 id는 사유를 들고 읽기 전용이 된다** — 조용히
     *   빼지 않는다(앱의 관계 목록은 `mapNotNull`로 빼는데, 그러면 사용자는 그 관계가 있다는
     *   사실조차 알 수 없다).
     * @param factionNames 세력 id → 이름.
     * @param crossUniverseOtherIds 지금 **다른 세계관에 있는** 상대의 id들. 비면 아무 줄에도
     *   안내가 붙지 않는다(기본값이 그것이다 — 부르는 쪽이 셈을 안 해도 동작은 같다).
     */
    fun describe(
        rows: List<CharacterRelationship>,
        selfId: Long,
        names: Map<Long, String>,
        factionNames: Map<Long, String>,
        crossUniverseOtherIds: Set<Long> = emptySet()
    ): List<RelationshipView> {
        val out = ArrayList<RelationshipView>(rows.size)
        for (row in rows) {
            val otherId = RelationshipRules.otherEndOf(row, selfId)
            val name = names[otherId]
            val automatic = RelationshipRules.isAutomatic(row)
            val notice = when {
                automatic -> NOTICE_FACTION
                name == null -> NOTICE_MISSING_OTHER
                else -> null
            }
            out.add(
                RelationshipView(
                    id = row.id,
                    code = row.code,
                    otherCharacterId = otherId,
                    otherCharacterName = name.orEmpty(),
                    type = row.relationshipType,
                    description = row.description,
                    intensity = row.intensity,
                    bidirectional = row.isBidirectional,
                    outgoing = row.characterId1 == selfId,
                    arrow = arrowFor(row.isBidirectional, row.characterId1 == selfId),
                    readOnly = notice != null,
                    notice = notice,
                    factionName = if (automatic) row.factionId?.let(factionNames::get) else null,
                    // 이미 사유가 있는 줄에는 겹쳐 적지 않는다(위 KDoc).
                    advisory = if (notice == null && otherId in crossUniverseOtherIds) {
                        ADVISORY_CROSS_UNIVERSE
                    } else {
                        null
                    }
                )
            )
        }
        return out
    }

    /**
     * 같은 세계관의 다른 인물들 — 상대로 고를 수 있는 목록.
     *
     * @param universeOf 작품 id → 세계관 id. 작품이 없는 캐릭터의 세계관은 null이고,
     *   **그 경우 세계관 없는 인물끼리만 이을 수 있다**(둘 다 null이면 같다).
     */
    fun candidatesFor(
        all: List<Character>,
        selfId: Long,
        selfUniverseId: Long?,
        universeOf: Map<Long, Long?>,
        novelTitles: Map<Long, String>
    ): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (c in all) {
            if (c.id == selfId) continue
            val universeId = c.novelId?.let { universeOf[it] }
            if (universeId != selfUniverseId) continue
            out.add(
                Candidate(
                    id = c.id,
                    name = c.displayName.ifBlank { c.name },
                    novelTitle = c.novelId?.let { novelTitles[it] }.orEmpty()
                )
            )
        }
        return out
    }

    /**
     * 웹이 보낸 본문을 [Draft]로.
     *
     * **`optString`을 쓰지 않는다** — 기기(libcore)는 JSON null에 문자열 `"null"`을 돌려주므로
     * 유형이 그 글자로 저장될 수 있다(R-70 · `tools/check_platform_api_parity.sh`).
     *
     * 강도는 **범위를 여기서 보지 않는다.** 숫자인가만 보고 범위는 [RelationshipRules]가
     * 가린다 — 두 자리에서 보면 그 둘이 갈린다.
     */
    fun parseDraft(body: String): DraftResult {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return DraftResult.Bad("json")
        val otherId = root.optLong("otherCharacterId").takeIf { it > 0 }
            ?: return DraftResult.Bad("otherCharacterId")
        val type = root.stringOr("type").trim()
        if (type.isEmpty()) return DraftResult.Bad("type")
        if (!root.has("intensity")) return DraftResult.Bad("intensity")
        val intensity = root.optInt("intensity", Int.MIN_VALUE)
        if (intensity == Int.MIN_VALUE) return DraftResult.Bad("intensity")
        return DraftResult.Parsed(
            Draft(
                otherCharacterId = otherId,
                type = type,
                description = root.stringOr("description").trim(),
                intensity = intensity,
                // 적혀 있지 않으면 양방향이다 — 엔티티의 기본값과 같게 둔다.
                bidirectional = root.optBoolean("bidirectional", true),
                outgoing = root.optBoolean("outgoing", true)
            )
        )
    }

    /**
     * 저장할 **양 끝**을 정한다.
     *
     * **고치는 중이고 상대와 방향이 그대로면 저장된 양 끝을 그대로 둔다.** 이 자리가 함정이다 —
     * 양방향 관계는 `A ↔ B`와 `B ↔ A`가 같은 뜻이므로 늘 (보는 쪽, 상대)로 다시 적어도
     * *뜻은* 맞지만, **강도만 고친 저장이 행의 양 끝을 조용히 뒤집는다.** 그러면 엑셀 왕복과
     * 월드패키지에서 같은 관계가 다른 모양으로 나가고, 유일 인덱스
     * `(characterId1, characterId2, relationshipType)`가 보는 자리도 함께 바뀐다.
     *
     * 더하는 중이거나 사용자가 방향을 바꿨으면 **단방향은 적힌 대로**, 양방향은 보는 쪽을
     * `characterId1`에 둔다.
     */
    fun endpointsFor(
        selfId: Long,
        draft: Draft,
        existing: CharacterRelationship?
    ): Pair<Long, Long> {
        if (existing != null &&
            RelationshipRules.otherEndOf(existing, selfId) == draft.otherCharacterId &&
            existing.isBidirectional == draft.bidirectional &&
            (draft.bidirectional || (existing.characterId1 == selfId) == draft.outgoing)
        ) {
            return existing.characterId1 to existing.characterId2
        }
        return if (draft.bidirectional || draft.outgoing) selfId to draft.otherCharacterId
        else draft.otherCharacterId to selfId
    }

    /**
     * [Draft]를 저장할 행으로.
     *
     * 고치는 중이면 `id` · `code` · `createdAt` · `displayOrder`를 **보존한다** — `code`는
     * 왕복 안정 식별자라 새로 내면 외부 편집기가 같은 관계를 다른 관계로 읽고, `displayOrder`를
     * 0으로 돌리면 사용자가 끌어 둔 순서가 저장만으로 흐트러진다.
     *
     * **`factionId`는 싣지 않는다.** PC에서 만드는 것은 언제나 수동 관계이고, 고치는 쪽은
     * 자동 관계를 애초에 막는다([RelationshipRules.Rejection.AUTOMATIC_ROW]).
     */
    fun toRow(
        selfId: Long,
        draft: Draft,
        existing: CharacterRelationship?
    ): CharacterRelationship {
        val (end1, end2) = endpointsFor(selfId, draft, existing)
        val base = existing ?: CharacterRelationship(
            characterId1 = end1,
            characterId2 = end2,
            relationshipType = draft.type
        )
        return base.copy(
            characterId1 = end1,
            characterId2 = end2,
            relationshipType = draft.type,
            description = draft.description,
            intensity = draft.intensity,
            isBidirectional = draft.bidirectional
        )
    }
}
