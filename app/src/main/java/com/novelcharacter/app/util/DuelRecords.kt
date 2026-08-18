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
 * 4. **무엇으로 견주는가는 축이 정한다**([CodeMatch] — B-175). 저장·표시는 **원본 표기**
 *    그대로이고(R-42) 정규화는 **비교 시점에만** 일어난다.
 */
object DuelRecords {

    /**
     * 참가자 코드를 **무엇으로 견주는가** (B-175 — 로드맵 20판).
     *
     * ## 왜 축마다 다른가
     * 캐릭터 축의 코드는 `Character.code`이고 **그것은 개명되지 않는다**(R-1). 글자 대조로
     * 충분하고, 정규화하면 되레 망가진다 — `"C-7"`을 경로로 읽으면 작업 디렉터리가 앞에 붙는다.
     * 이미지 축의 코드는 **경로**이고, 같은 파일이 여러 표기를 가질 수 있다.
     *
     * ## 한쪽만 고치면 더 나빠진다 — 이 enum이 있는 이유
     * `DuelImageRoster`는 처음부터 **정규 경로**로 주인을 찾았는데 여기서는 **글자 그대로**
     * 견주고 있었다. 그래서 판에 적힌 표기가 목록의 표기와 갈리면 **나누기에서는 그 캐릭터
     * 몫이고 적합에서는 고아**가 되어, 고르는 화면의 진행률과 순위표의 판 수가 어긋났다.
     * 소비처만 고치면 순위표(`DuelRepository.stateOf`)도 같은 원본 대조라
     * [DuelScoreIndex]의 계약 1(*"밖에서 보는 수는 순위표가 보이는 그 수다"*)이 정면으로
     * 깨진다 — **그래서 대조를 값 하나로 만들어 세 자리가 그것만 부르게 했다.**
     */
    enum class CodeMatch {
        /** 글자 그대로. 캐릭터 축의 기본이고, **정규화가 뜻을 바꿀 수 있는 모든 축**의 답이다. */
        EXACT,

        /**
         * **같은 파일인가.** 이미지 축의 코드는 경로라 표기가 갈릴 수 있다
         * (개명 추종이 원본 표기를 지키므로 — R-42).
         */
        IMAGE_PATH;

        /**
         * 대조용 열쇠. **저장에 들어가는 값이 아니다** — 이 값으로 다시 적으면 정규화가
         * 실패하는 날 코드가 죽는다(설계 13-1이 저장을 원본 표기로 못박은 근거).
         */
        fun keyOf(code: String): String = when (this) {
            EXACT -> code
            IMAGE_PATH -> ImagePathMatch.canonical(code)
        }
    }

    /**
     * 한 축의 기록을 순수 계층이 받는 모양으로 옮긴 것.
     *
     * @property participants 점수를 낼 참가자 id — **살아 있는 것만** 든다.
     * @property matches 저장된 판 전부(고아 포함). 빼는 판정은 [DuelRating.fit]이 하고 개수를 낸다.
     * @property excludedPairs ③ 상성으로 확정된 짝. 적합에서 빠진다.
     * @property undecidedPairs ② 미정으로 미룬 짝. 점수는 그대로 두고 화면이 표시에만 쓴다.
     * @property missingParticipants 판에는 있으나 지금 살아 있지 않은 참가자 수 —
     *   *"지워진 캐릭터의 판 N개가 점수에서 빠져 있습니다"*를 말할 수 있게 세어 둔다.
     * @property idByCode **열쇠는 [matching]이 정한 대조 값**이지 원본 표기가 아니다
     *   ([CodeMatch.EXACT]이면 둘이 같다). 직접 찾지 말고 [idOf]를 쓸 것 — 그쪽이 열쇠를 맞춘다.
     * @property codeById id → **처음 만난 원본 표기.** 살아 있는 참가자를 먼저 배정하므로
     *   같은 파일의 표기가 여럿이면 **목록에 있는 표기가 이긴다** — 화면·엑셀·새 판이 그것을
     *   그대로 쓴다(R-42: 저장에 들어가는 값은 원본 표기다).
     * @property matching 이 해석이 쓴 대조 규칙. [idOf]·[canonicalCode]가 같은 규칙으로
     *   찾도록 함께 담는다 — 부르는 쪽이 따로 기억하면 두 자리가 갈린다.
     */
    data class Resolved(
        val participants: List<Long>,
        val matches: List<DuelRating.Match>,
        val excludedPairs: Set<DuelRating.PairKey>,
        val undecidedPairs: Set<DuelRating.PairKey>,
        val idByCode: Map<String, Long>,
        val codeById: Map<Long, String>,
        val missingParticipants: Int,
        val matching: CodeMatch = CodeMatch.EXACT
    ) {
        fun codeOf(id: Long): String? = codeById[id]

        fun idOf(code: String): Long? = idByCode[matching.keyOf(code)]

        /**
         * 이 해석 안의 **대표 표기** — 표기가 갈려도 같은 참가자면 같은 문자열이 된다.
         *
         * 열쇠를 문자열로 맞춰야 하는 자리가 쓴다(`DuelStandings.counterReview`의 정규 키가
         * 그 자리다). **모르는 코드는 그대로 돌려준다** — 버리지 않는 것이 이 앱의 규약이다.
         */
        fun canonicalCode(code: String): String = idOf(code)?.let { codeById[it] } ?: code

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
     * @param matching 참가자 코드를 무엇으로 견주는가 — **축의 종류가 정한다**([CodeMatch]).
     *   기본값이 [CodeMatch.EXACT]인 것은 그것이 *뜻을 바꾸지 않는 쪽*이라서다(모르는 축을
     *   경로로 읽으면 코드가 통째로 달라진다).
     */
    fun resolve(
        participantCodes: Collection<String>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict> = emptyList(),
        matching: CodeMatch = CodeMatch.EXACT
    ): Resolved {
        val idByCode = LinkedHashMap<String, Long>()
        val codeById = LinkedHashMap<Long, String>()
        var next = 1L

        // 열쇠를 **한 문자열당 한 번만** 낸다. [CodeMatch.IMAGE_PATH]의 정규화는 파일 시스템
        // 호출이고, 같은 경로가 그 참가자의 판 수만큼 되풀이해 들어온다(짝이 `n(n-1)/2`).
        // 캐릭터 축은 열쇠가 곧 코드라 표를 아예 만들지 않는다 — 공짜인 자리에 비용을 붙이지 않는다.
        val keyCache = if (matching == CodeMatch.EXACT) null else HashMap<String, String>()
        fun keyOf(code: String): String =
            keyCache?.getOrPut(code) { matching.keyOf(code) } ?: code

        fun idOf(code: String): Long {
            val key = keyOf(code)
            idByCode[key]?.let { return it }
            val id = next++
            idByCode[key] = id
            // **처음 만난 표기를 대표로 삼는다.** 참가자를 먼저 배정하므로 살아 있는 목록의
            // 표기가 이기고, 판에만 남은 표기는 그 참가자가 없을 때만 대표가 된다.
            codeById[id] = code
            return id
        }

        val participants = ArrayList<Long>(participantCodes.size)
        for (code in participantCodes) {
            val key = keyOf(code)
            // 빈 코드는 참가자가 아니다 — *"지정 없음"*은 무엇과도 같지 않다
            // (`ImagePathMatch.same`이 같은 판단을 한다).
            if (key.isEmpty() || idByCode.containsKey(key)) continue
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
            missingParticipants = idByCode.size - liveCount,
            matching = matching
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

    // ──────────────────────────────────────────────────────────────────────
    // 상성 행의 참가자 해석 — 가져오기와 미리보기가 **같은 사다리**를 쓴다 (R-33 · B-263 ⓐ)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 한 상성 행이 가리키는 참가자들.
     *
     * **여기가 순수인 것이 요점이다.** 종전에는 이 사다리가 `importDuelVerdicts` 본문에만
     * 있었고, 그래서 미리보기가 같은 행을 **다르게 읽을 길**밖에 없었다(R-33이 없애려는
     * *손으로 짠 두 벌*). 사다리를 여기로 내리면 왕복이 실행으로 증명된다.
     */
    sealed class MemberResolution {
        /** 코드 열이 있었거나, 이름이 전부 **하나로** 확정됐다. 순서는 적힌 차례 그대로다. */
        data class Resolved(val members: List<String>) : MemberResolution()

        /**
         * 이름으로 확정할 수 없는 참가자가 있다 — 그 행은 **거부한다**.
         * 인원이 줄어든 상성은 사용자가 적은 것과 *다른 판정*이라 조용히 뺄 수 없다.
         *
         * @param names 확정하지 못한 이름들(적힌 차례 — 고지 문구가 이 순서로 든다)
         * @param ambiguous 그중 **동명이인**이 있는가. 없으면 전부 *아직 없는 이름*이라
         *   같은 파일의 캐릭터 시트가 만들어 줄 수 있다(미리보기의 '신규' 판정이 이 갈래에 선다.
         *   `analyzeDuelMatches`의 `nameAmbiguous`와 같은 규약 — B-102 ⓑ).
         */
        data class Unresolved(val names: List<String>, val ambiguous: Boolean) : MemberResolution()
    }

    /**
     * 참가자 열 둘을 코드 목록으로 옮긴다.
     *
     * **코드 열이 먼저다** — 코드는 유일하지만 이름은 겹칠 수 있다(`resolveDuelWinner`와 같은
     * 규약). 코드 열이 비었을 때만 이름을 보고, 그때도 **하나로 확정되는 이름만** 옮긴다.
     *
     * @param codeByName 이름 → 그 이름을 든 캐릭터 코드들(동명이인이면 둘 이상)
     */
    fun resolveMembers(
        rawCodes: List<String>,
        names: List<String>,
        codeByName: Map<String, List<String>>
    ): MemberResolution {
        if (rawCodes.isNotEmpty()) return MemberResolution.Resolved(rawCodes)
        val unresolved = names.filter { name -> codeByName[name].orEmpty().size != 1 }
        if (unresolved.isNotEmpty()) {
            return MemberResolution.Unresolved(
                names = unresolved,
                ambiguous = unresolved.any { name -> codeByName[name].orEmpty().size > 1 }
            )
        }
        return MemberResolution.Resolved(names.map { name -> codeByName.getValue(name).single() })
    }
}
