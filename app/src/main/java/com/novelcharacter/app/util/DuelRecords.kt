package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import org.json.JSONArray

/**
 * **저장된 대결 기록과 순수 계층 사이의 단일 소스** (B-104 데이터 계층).
 *
 * 순수 계층 셋([DuelRating]·[DuelPairing]·[DuelCounterRelations])은 참가자를 `Long` id로 본다.
 * 그런데 저장은 **코드**로 한다(R-1 — 되살아난 캐릭터의 판이 남에게 붙는 오배정을 막는다).
 * 그 사이를 잇는 것이 이 파일이고, **여기가 pure이기 때문에 계약이 전부 실행으로 검증된다** —
 * 저장소(Room) 쪽에 두면 이 앱의 어떤 로컬 검증도 이 규칙을 못 본다.
 *
 * ## 지키는 규칙 셋
 *
 * 1. **살아 있지 않은 참가자의 판은 지우지도 감추지도 않는다.** 참가자 목록에 없는 코드에도
 *    id를 주되 그 id를 `participants`에 넣지 않는다 — 그러면 [DuelRating.fit]이 그 판을
 *    `orphanMatches`로 **세어서 알린다**(개발 의도 2번). 휴지통에서 캐릭터를 복원하면 코드가
 *    되살아나므로 그 판들이 **그대로 다시 적합에 든다.**
 * 2. **id는 이 해석 안에서만 뜻이 있다.** DB id를 그대로 쓰지 않는 것은 이미지 축(참가자가
 *    경로다)과 캐릭터 축이 같은 코드로 돌게 하기 위해서이기도 하다.
 * 3. **처분은 종류를 가려 적용한다.** 점수에서 빼는 것은 ③(상성)뿐이고 ②(미정)는 표시만 한다 —
 *    미정까지 빼면 *"아직 안 정했다"*가 순위를 조용히 바꾼다.
 */
object DuelRecords {

    /**
     * 한 축의 기록을 순수 계층이 받는 모양으로 옮긴 것.
     *
     * @property participants 점수를 낼 참가자 id — **살아 있는 것만** 든다.
     * @property matches 저장된 판 전부(고아 포함). 빼는 판정은 [DuelRating.fit]이 하고 개수를 낸다.
     * @property excludedPairs ③ 상성으로 확정된 짝. 적합에서 빠진다.
     * @property undecidedPairs ② 미정으로 미룬 짝. 점수는 그대로 두고 화면이 표시에만 쓴다.
     * @property missingParticipants 판에는 있으나 지금 살아 있지 않은 참가자 수 —
     *   *"지워진 캐릭터의 판 N개가 점수에서 빠져 있습니다"*를 말할 수 있게 세어 둔다.
     */
    data class Resolved(
        val participants: List<Long>,
        val matches: List<DuelRating.Match>,
        val excludedPairs: Set<DuelRating.PairKey>,
        val undecidedPairs: Set<DuelRating.PairKey>,
        val idByCode: Map<String, Long>,
        val codeById: Map<Long, String>,
        val missingParticipants: Int
    ) {
        fun codeOf(id: Long): String? = codeById[id]

        fun idOf(code: String): Long? = idByCode[code]

        /** 짝을 코드 둘로 되돌린다 — 화면·엑셀이 순수 계층의 결과를 사람이 읽는 것으로 옮길 때 쓴다. */
        fun codesOf(pair: DuelRating.PairKey): Pair<String, String>? {
            val low = codeById[pair.lowId] ?: return null
            val high = codeById[pair.highId] ?: return null
            return low to high
        }
    }

    /**
     * 저장된 행들을 순수 계층의 입력으로 옮긴다.
     *
     * @param participantCodes 지금 살아 있는 참가자의 코드(캐릭터 축이면 `Character.code`).
     *   **순서가 id 배정을 정하므로** 호출부가 안정된 순서로 넘기면 결과도 안정된다.
     * @param matches 이 축의 판 전부. 깨진 판도 넘길 것 — 걸러 내면 개수를 알릴 수 없다.
     */
    fun resolve(
        participantCodes: Collection<String>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict> = emptyList()
    ): Resolved {
        val idByCode = LinkedHashMap<String, Long>()
        val codeById = LinkedHashMap<Long, String>()
        var next = 1L

        fun idOf(code: String): Long = idByCode.getOrPut(code) {
            val id = next++
            codeById[id] = code
            id
        }

        val participants = ArrayList<Long>(participantCodes.size)
        for (code in participantCodes) {
            if (code.isEmpty() || idByCode.containsKey(code)) continue
            participants.add(idOf(code))
        }
        val liveCount = idByCode.size

        val converted = ArrayList<DuelRating.Match>(matches.size)
        for (row in matches) {
            // 승자 코드가 두 참가자 중 어느 쪽도 아니면 그 값을 살려서 넘긴다 — 적합이
            // `malformedMatches`로 세도록. 여기서 null로 바꾸면 깨진 판이 무승부로 둔갑한다.
            val winner = row.winnerCode?.takeIf { it.isNotEmpty() }?.let { idOf(it) }
            converted.add(DuelRating.Match(idOf(row.aCode), idOf(row.bCode), winner))
        }

        val excluded = LinkedHashSet<DuelRating.PairKey>()
        val undecided = LinkedHashSet<DuelRating.PairKey>()
        for (verdict in verdicts) {
            val target = if (verdict.excludesFromRating) excluded else undecided
            for ((a, b) in relationPairs(decodeMembers(verdict.memberCodes))) {
                target.add(DuelRating.PairKey.of(idOf(a), idOf(b)))
            }
        }

        return Resolved(
            participants = participants,
            matches = converted,
            excludedPairs = excluded,
            undecidedPairs = undecided,
            idByCode = idByCode,
            codeById = codeById,
            // 살아 있는 참가자를 먼저 배정했으므로 그 뒤에 붙은 코드가 곧 사라진 참가자다.
            // (처분에만 등장하는 코드도 여기 든다 — 상성 상대가 지워진 경우이며 같은 부류다.)
            missingParticipants = idByCode.size - liveCount
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 처분의 참가자 목록 — 저장 형식과 정규 키
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 참가자 코드 목록을 저장 형식으로. **JSON 배열인 것은 이미지 축 때문이다** —
     * 이미지 축의 코드는 경로라 구분자를 정해 이어 붙이면 그 구분자를 담은 파일 이름에서 깨진다.
     */
    fun encodeMembers(members: List<String>): String =
        JSONArray().apply { members.forEach { put(it) } }.toString()

    /** 저장 형식을 목록으로. 깨진 값은 빈 목록이다 — 외부에서 편집된 파일도 앱을 죽이지 않는다. */
    fun decodeMembers(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optString(i, "").takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 순서를 지운 정규 키 — 같은 관계가 회전(`A>B>C` vs `B>C>A`)이나 반전으로 두 번 등재되지
     * 않게 한다. 유니크 인덱스가 이 값 위에 선다.
     */
    fun memberKey(members: List<String>): String = encodeMembers(members.distinct().sorted())

    /**
     * 한 처분이 덮는 짝들. 천적은 그 한 짝이고, 순환은 **이어지는 변 전부**다(`A>B`, `B>C`, `C>A`).
     * 순환에서 마주 보는 짝까지 빼면 사용자가 판정하지 않은 관계를 점수에서 지우게 된다.
     */
    fun relationPairs(members: List<String>): List<Pair<String, String>> {
        val distinct = members.filter { it.isNotEmpty() }.distinct()
        return when {
            distinct.size < 2 -> emptyList()
            distinct.size == 2 -> listOf(distinct[0] to distinct[1])
            else -> distinct.indices.map { i -> distinct[i] to distinct[(i + 1) % distinct.size] }
        }
    }

    /**
     * 처분의 모양이 참가자 수와 맞는가 — 저장 전 검증이다.
     * 둘이면 [DuelCounterVerdict.SHAPE_DIRECT], 셋 이상이면 [DuelCounterVerdict.SHAPE_CYCLE].
     */
    fun shapeOf(members: List<String>): String? = when (members.filter { it.isNotEmpty() }.distinct().size) {
        2 -> DuelCounterVerdict.SHAPE_DIRECT
        0, 1 -> null
        else -> DuelCounterVerdict.SHAPE_CYCLE
    }
}
