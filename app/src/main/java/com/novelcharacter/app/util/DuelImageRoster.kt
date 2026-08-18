package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch

/**
 * **이미지 축에서 "누가 누구와 붙는가"의 단일 소스** (B-104 이미지 축).
 *
 * 설계 정본은 `docs/duel_system_design_2026-08.md` **13장**이다.
 *
 * 캐릭터 축과 갈리는 것은 **참가자 집합의 모양** 하나다. 캐릭터 축은 세계관 전원이 한 판에서
 * 서로 붙지만, 이미지 축은 **캐릭터마다 따로 논다** — 사용자 확정: *"이미지 대결은 캐릭터마다
 * 하는거야. 다른 캐릭터랑 붙는 게 아니라. 자기 이미지중에 하는 거야."* 그래서 축 하나가
 * 세계관에 걸려 있어도 그 안은 **캐릭터 수만큼의 독립된 대결**이고, 적합도 캐릭터마다 따로 돈다.
 *
 * **캐릭터를 넘는 비교를 애초에 만들지 않는 것이 요점이다.** 한 축의 이미지 전부를 한 번에
 * 적합하면 그래프가 캐릭터별로 끊긴 채라(짝이 캐릭터를 넘지 않으므로) 캐릭터 사이의 점수 차는
 * **아무 판도 근거하지 않은 수**가 된다. 그런 수로 줄을 세우면 원칙 02가 금지하는 겉핥기다.
 * 캐릭터별로 나눠 적합하면 그 수가 아예 생기지 않고, 덤으로 **훨씬 싸다**(참가자 수의 제곱이
 * 붙는 비용이 전원이 아니라 한 캐릭터의 이미지 수에만 붙는다).
 *
 * ## 두 장이 없으면 대결이 없다
 * 이미지가 한 장뿐인 캐릭터는 붙일 짝이 없다. **목록에 올리지 않는다** — 올려 두면 눌렀을 때
 * 빈 화면이 뜨고, 그것이 *"기능이 고장 났다"*로 읽힌다(원칙 02). 그 캐릭터가 몇인지는
 * [Roster.skippedSingleImage]가 세어 화면이 말한다(개발 의도 2번 — 조용히 빠지지 않는다).
 *
 * ## 몫을 가르는 잣대는 [claimOf] 하나다 (B-175 — 로드맵 20판)
 * *"이 판은 누구 것인가"*를 [build]·[split]이 각자 적고 있었고, 순위표는 **아예 가르지도
 * 않았다**(축 전체를 한 캐릭터의 참가자로 적합해 남의 판을 전부 고아로 읽었다 —
 * 화면에는 *"지워진 참가자 N명의 판"*이라는 **거짓 고지**로 나타났다). 잣대를 하나로 세우고
 * 셋이 그것만 부른다.
 */
object DuelImageRoster {

    /**
     * **어느 그림이 누구 것인가** — 몫 가르기의 유일한 잣대가 보는 표.
     *
     * **이미지 수를 가리지 않고 전원을 담는다.** 종전에는 두 장 미만인 캐릭터를 표에서 뺐는데,
     * 그러면 *"두 장이었다가 한 장이 된 캐릭터"*의 남은 그림이 **주인 없는 그림**으로 보여
     * 그 그림이 낀 판이 **남의 몫으로 넘어간다**(그 판은 상대 캐릭터의 순위표에서
     * *"지워진 그림의 판"*으로 잘못 고지된다). 목록에 올릴지는 [build]가 따로 가린다 —
     * **누가 가졌는가**와 **대결할 수 있는가**는 다른 물음이다.
     */
    class Owners internal constructor(
        private val byCanonical: Map<String, Long>,
        private val canon: ImagePathMatch.Canonicalizer
    ) {
        /** 이 코드(=경로)를 가진 캐릭터. 아무도 안 가졌으면 null — **지워진 그림**이다. */
        fun of(code: String): Long? = byCanonical[canon.of(code)]

        /**
         * 대조용 열쇠 — **[of]와 같은 정규화기를 지난다.**
         *
         * 짝을 세는 자리처럼 *주인이 아니라 값 자체*가 필요한 데가 있는데, 거기서
         * [ImagePathMatch.canonical]을 직접 부르면 **담아 둔 값을 지나쳐 파일 시스템을 다시
         * 친다** — 같은 경로가 판 수만큼 되풀이되는 자리라 그 하나로 이 표의 값어치가 사라진다.
         */
        fun key(code: String): String = canon.of(code)

        /** 담긴 그림 수 — 시험이 *"두 장 미만도 들어 있다"*를 재는 자리다. */
        val size: Int get() = byCanonical.size

        /**
         * 이 표가 지금까지 **실제로 잰 문자열 수.**
         *
         * 시험이 *"판마다 다시 재지 않는다"*를 확인하는 자리다 — 그 성질은 **틀려도 답이
         * 같아서** 다른 어떤 검증에도 안 걸리고, 느려지는 것만으로는 아무도 모른다.
         */
        val measured: Int get() = canon.measured
    }

    /**
     * 판·처분 하나가 **어느 몫인가.**
     *
     * 세 갈래인 것이 요점이다 — 종전 코드는 *"내 것"*과 *"내 것 아님"* 둘로만 갈라
     * **성격이 다른 둘을 한 자루에 넣었다:** 지워진 그림이 낀 판(내 대결의 일부였다)과
     * 남의 그림이 낀 판(애초에 누구의 대결도 아니다)이다. 화면이 할 말이 서로 다르므로
     * 여기서 갈라 둔다(개발 의도 2번 — 조용히 사라지지도, 거짓으로 고지되지도 않게).
     */
    sealed interface Claim {
        /**
         * 이 캐릭터 몫이다.
         *
         * @property complete 참가자가 **다 살아 있는가.** 하나가 지워졌으면 false이고, 그 판은
         *   몫에는 들되 짝으로는 세지 않는다 — 적합이 고아로 세어 화면이 말한다.
         */
        data class Owned(val characterId: Long, val complete: Boolean) : Claim

        /**
         * **캐릭터를 넘는다** — 어느 몫도 아니다. 이 화면은 그런 판을 만들지 않지만
         * 엑셀·백업이 만들 수 있다. [characterIds]는 그 판에 든 캐릭터들이라 **각자의 화면이
         * 자기 몫으로 세어 말한다.**
         */
        data class Cross(val characterIds: Set<Long>) : Claim

        /** 살아 있는 그림이 하나도 없다 — 어느 캐릭터 화면의 몫도 아니다(기록 화면이 든다). */
        data object None : Claim
    }

    /** 세계관의 캐릭터에서 소유 표를 만든다. 정규화는 **경로 하나당 한 번**이다. */
    fun owners(characters: List<Character>): Owners {
        val canon = ImagePathMatch.Canonicalizer()
        val byCanonical = HashMap<String, Long>(characters.size * 4)
        for (character in characters) {
            // 같은 그림을 둘이 들고 있으면 **나중 것이 이긴다** — 목록 차례가 곧 사용자가 정한
            // 차례이고, 어느 쪽을 골라도 그 상태 자체가 정상이 아니라 여기서 판정하지 않는다.
            for (path in CharacterRepresentativeImage.paths(character.imagePaths)) {
                val key = canon.of(path)
                if (key.isNotEmpty()) byCanonical[key] = character.id
            }
        }
        return Owners(byCanonical, canon)
    }

    /**
     * 이 코드들이 어느 몫인가 — **몫 가르기의 유일한 잣대.**
     *
     * 규칙은 한 줄이다: **주인이 있는 코드들의 주인이 전부 같으면 그 캐릭터 몫**이고,
     * 갈리면 [Claim.Cross], 주인이 하나도 없으면 [Claim.None]이다.
     */
    fun claimOf(codes: List<String>, owners: Owners): Claim {
        var mine: Long? = null
        var others: MutableSet<Long>? = null
        var missing = false
        for (code in codes) {
            val owner = owners.of(code)
            if (owner == null) { missing = true; continue }
            if (mine == null) {
                mine = owner
            } else if (mine != owner) {
                // 캐릭터를 넘는 판은 드물다(엑셀·백업으로만 들어온다) — 그 자리에서만 표를 만든다.
                others = (others ?: linkedSetOf(mine)).apply { add(owner) }
            }
        }
        others?.let { return Claim.Cross(it) }
        return mine?.let { Claim.Owned(it, !missing) } ?: Claim.None
    }

    /** 판 하나의 몫 — 참가자가 둘이라는 것 말고는 [claimOf]와 같다(잣대를 두 벌로 적지 않는다). */
    fun claimOf(aCode: String, bCode: String, owners: Owners): Claim =
        claimOf(listOf(aCode, bCode), owners)

    /**
     * 한 캐릭터 몫의 이미지 대결.
     *
     * @property paths 이 캐릭터의 이미지 경로 — **참가자 코드 그 자체**다(순서가 목록 순서).
     * @property played 이 캐릭터의 이미지끼리 치른 판 수.
     * @property coveredPairs 한 번이라도 붙여 본 짝의 수.
     * @property totalPairs 붙일 수 있는 짝 전수 `n(n-1)/2`.
     */
    data class Entry(
        val characterId: Long,
        val characterCode: String,
        val name: String,
        val paths: List<String>,
        val played: Int,
        val coveredPairs: Int,
        val totalPairs: Int
    ) {
        val imageCount: Int get() = paths.size

        /** 아직 한 번도 안 붙인 짝. 진행률의 분자가 아니라 **남은 일**을 말한다. */
        val remainingPairs: Int get() = (totalPairs - coveredPairs).coerceAtLeast(0)

        /** 한 판도 안 친 캐릭터인가 — 목록이 *"아직 시작 안 함"*을 그대로 말한다. */
        val untouched: Boolean get() = played == 0
    }

    /**
     * @property entries 대결할 수 있는 캐릭터만(이미지 2장 이상). 순서는 넘긴 순서 그대로다.
     * @property skippedSingleImage 이미지가 한 장뿐이라 뺀 캐릭터 수.
     * @property skippedNoImage 이미지가 아예 없어 뺀 캐릭터 수. 한 장짜리와 갈라 세는 것은
     *   **할 말이 다르기 때문이다** — 한 장은 *"한 장 더 넣으면 됩니다"*이고 0장은
     *   *"이미지를 넣어야 합니다"*다.
     * @property owners 이 세계관에서 **어느 그림이 누구 것인가.** 판을 몫으로 가르는 자리가
     *   이것을 받아 쓴다 — 다시 만들면 그림 수만큼의 파일 시스템 호출이 되풀이되고, 그 자리가
     *   하필 **한 판 누를 때마다 다시 도는 경로**다.
     */
    data class Roster(
        val entries: List<Entry>,
        val skippedSingleImage: Int,
        val skippedNoImage: Int,
        val owners: Owners = Owners(emptyMap(), ImagePathMatch.Canonicalizer())
    ) {
        val any: Boolean get() = entries.isNotEmpty()

        /** 목록에서 빠진 캐릭터가 있는가 — 있으면 화면이 그 사실을 말한다. */
        val hasSkipped: Boolean get() = skippedSingleImage > 0 || skippedNoImage > 0

        fun entryOf(characterId: Long): Entry? = entries.firstOrNull { it.characterId == characterId }
    }

    /**
     * 이 축의 캐릭터별 대결 현황.
     *
     * @param characters 세계관의 캐릭터 — 순서가 곧 목록 순서다(호출부가 정한 차례를 지킨다).
     * @param matches 이 축의 판 전부. **캐릭터별로 나누는 것은 여기서 한다** — 판은 참가자를
     *   경로로만 담고 있어 어느 캐릭터의 것인지 스스로 말하지 못한다.
     */
    fun build(
        characters: List<Character>,
        matches: List<DuelMatch>,
        owners: Owners = owners(characters)
    ): Roster {
        val entries = ArrayList<Entry>(characters.size)
        var singles = 0
        var none = 0

        for (character in characters) {
            val paths = CharacterRepresentativeImage.paths(character.imagePaths)
            when (paths.size) {
                0 -> { none++; continue }
                1 -> { singles++; continue }
            }
            val n = paths.size
            entries.add(
                Entry(
                    characterId = character.id,
                    characterCode = character.code,
                    name = character.displayName,
                    paths = paths,
                    played = 0,
                    coveredPairs = 0,
                    totalPairs = n * (n - 1) / 2
                )
            )
        }

        if (matches.isEmpty() || entries.isEmpty()) {
            return Roster(entries, singles, none, owners)
        }

        val playedBy = HashMap<Long, Int>()
        // 짝의 열쇠를 **문자열로 잇지 않는다** — 참가자가 경로라 어떤 구분자를 골라도 그
        // 글자를 담은 파일 이름에서 키가 깨진다(`DuelRecords.encodeMembers`가 JSON 배열을
        // 쓰는 것과 같은 이유). 쌍 자체를 열쇠로 두면 구분자가 아예 필요 없다.
        val pairsBy = HashMap<Long, MutableSet<Pair<String, String>>>()
        for (match in matches) {
            // **짝으로 서려면 두 그림이 다 살아 있어야 한다**(`complete`). 캐릭터를 넘는 판은
            // 어느 쪽에 세도 그 캐릭터의 진행률이 100%를 넘고, 한쪽이 지워진 판은 붙일 짝이
            // 남아 있지 않다. 둘 다 조용히 빠지지 않는다 — 앞은 [Split.crossCharacterMatches]가,
            // 뒤는 적합의 고아 집계가 세어 화면이 말한다.
            val claim = claimOf(match.aCode, match.bCode, owners)
            if (claim !is Claim.Owned || !claim.complete) continue
            val owner = claim.characterId
            // **소유 표의 정규화기를 그대로 쓴다** — 여기서 `ImagePathMatch.canonical`을 직접
            // 부르면 방금 담아 둔 값을 지나쳐 판마다 파일 시스템을 다시 친다.
            val a = owners.key(match.aCode)
            val b = owners.key(match.bCode)
            playedBy[owner] = (playedBy[owner] ?: 0) + 1
            pairsBy.getOrPut(owner) { HashSet() }.add(if (a <= b) a to b else b to a)
        }

        return Roster(
            entries = entries.map { entry ->
                entry.copy(
                    played = playedBy[entry.characterId] ?: 0,
                    coveredPairs = pairsBy[entry.characterId]?.size ?: 0
                )
            },
            skippedSingleImage = singles,
            skippedNoImage = none,
            owners = owners
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 캐릭터 몫으로 나누기 — 소비처 ⓑ·ⓒ가 점수를 낼 때 쓴다 (설계 13-5)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 한 캐릭터 몫의 대결 재료 — **이대로 적합 한 번이면 그 캐릭터의 순위표가 나온다.**
     *
     * @property paths 참가자 코드(=경로) 전부. 점수가 없는 그림도 들어 있다.
     * @property matches **이 캐릭터 몫의 판.** 두 그림이 다 살아 있는 판과, 한쪽이 지워진 판이
     *   함께 든다 — 뒤엣것을 빼면 그 판이 어느 화면에서도 세어지지 않아 *"쌓아 둔 판이
     *   조용히 사라졌다"*가 된다. 넣어 두면 적합이 **고아로 세어** 화면이 말한다.
     * @property verdicts 참가자가 이 캐릭터의 그림인 처분(지워진 그림이 낀 것도 든다 —
     *   빼면 사용자가 **물릴 수도 없는 처분**이 남는다).
     * @property crossCharacterMatches 이 캐릭터의 그림과 **남의 그림**이 함께 적힌 판 수.
     *   어느 몫도 아니라 점수에 들지 않으며, 그 사실을 화면이 말한다.
     * @property crossCharacterVerdicts 같은 이유로 몫에서 빠진 처분 수.
     */
    data class Split(
        val characterId: Long,
        val paths: List<String>,
        val matches: List<DuelMatch>,
        val verdicts: List<DuelCounterVerdict>,
        val crossCharacterMatches: Int = 0,
        val crossCharacterVerdicts: Int = 0
    )

    /**
     * 축의 판·처분을 캐릭터별로 나눈다 — **적합을 캐릭터마다 따로 돌리기 위한 재료**다(13-2).
     *
     * **여기가 그 판정의 유일한 자리다.** 소비처가 둘이라(대표 추첨 ⓑ · 걸러낼 후보 ⓒ)
     * 나누는 규칙을 두 벌로 적으면 **대표가 보는 순위와 순위표가 갈린다** — 인수인계 ③이
     * 다음 세션에 남긴 못이 그것이다.
     *
     * **판을 한 번만 읽는다.** 캐릭터마다 질의하면 인원만큼 왕복이 늘고, 그것이 목표 규모에서
     * 먼저 무너지는 자리다(`scalability_performance` 7장 2단계).
     *
     * ## 대조는 **정규 경로 하나**다 (B-175 — 로드맵 20판)
     * 종전에는 여기서만 [ImagePathMatch.canonical]로 주인을 찾고, 이 산출물을 받는
     * [DuelRecords.resolve]는 참가자를 **글자 그대로** 대조했다. 그래서 판에 적힌 표기가
     * 목록의 표기와 갈리면 **여기서는 그 캐릭터 몫으로 나뉘고 적합에서는 고아가 되어**
     * [build]의 `played`(고르는 화면)와 [DuelScoreIndex.Entry.played](걸러낼 후보의 최소 판 수)가
     * 어긋났다. 이제 부르는 쪽이 [DuelRecords.CodeMatch.IMAGE_PATH]를 함께 넘겨 **한 잣대**다 —
     * 한쪽만 정규화하면 순위표와 소비처가 다른 수를 갖게 되어 계약 1이 깨지므로
     * `split`·`resolve`·순위표가 **같은 커밋에서** 함께 옮겨졌다.
     *
     * @param owners 이미 만들어 둔 소유 표. 화면이 들고 있으면 넘길 것 — 다시 만들면 그림
     *   수만큼의 파일 시스템 호출이 되풀이된다(한 판 누를 때마다 다시 도는 경로다).
     * @return 이미지가 둘 이상인 캐릭터만. 순서는 [characters]가 준 순서 그대로다.
     *   **판이 하나도 없는 캐릭터도 든다** — 빼면 부르는 쪽이 *"계산했는데 없다"*와
     *   *"계산할 것이 없었다"*를 구별하려 또 세어야 한다.
     */
    fun split(
        characters: List<Character>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict>,
        owners: Owners = owners(characters)
    ): List<Split> {
        val pathsBy = LinkedHashMap<Long, List<String>>()
        for (character in characters) {
            val paths = CharacterRepresentativeImage.paths(character.imagePaths)
            if (paths.size < 2) continue
            pathsBy[character.id] = paths
        }
        if (pathsBy.isEmpty()) return emptyList()

        // **판을 한 번만 훑는다.** 캐릭터마다 전수를 다시 훑으면 비용이 인원×판이 되고,
        // 그 곱이 목표 규모에서 먼저 무너지는 자리다(`scalability_performance` 7장 2단계).
        val matchesBy = HashMap<Long, MutableList<DuelMatch>>()
        val crossMatchesBy = HashMap<Long, Int>()
        for (match in matches) {
            when (val claim = claimOf(match.aCode, match.bCode, owners)) {
                is Claim.Owned -> matchesBy.getOrPut(claim.characterId) { ArrayList() }.add(match)
                // 캐릭터를 넘는 판은 **낀 캐릭터마다 자기 몫으로 센다** — 그 화면이 말할 것은
                // *"내 그림이 낀 판이 점수에 안 들었다"*이고, 그것은 캐릭터마다 다른 사실이다.
                is Claim.Cross -> for (id in claim.characterIds) {
                    crossMatchesBy[id] = (crossMatchesBy[id] ?: 0) + 1
                }
                Claim.None -> {}
            }
        }

        val verdictsBy = HashMap<Long, MutableList<DuelCounterVerdict>>()
        val crossVerdictsBy = HashMap<Long, Int>()
        for (verdict in verdicts) {
            val members = DuelRecords.decodeMembers(verdict.memberCodes)
            if (members.isEmpty()) continue
            // 처분은 셋 이상일 수 있다(순환). 잣대는 판과 **같은 것**을 쓴다 — 두 벌로 적으면
            // 판은 내 몫인데 그 판을 빼는 처분은 남의 몫인 상태가 생긴다.
            when (val claim = claimOf(members, owners)) {
                is Claim.Owned -> verdictsBy.getOrPut(claim.characterId) { ArrayList() }.add(verdict)
                is Claim.Cross -> for (id in claim.characterIds) {
                    crossVerdictsBy[id] = (crossVerdictsBy[id] ?: 0) + 1
                }
                Claim.None -> {}
            }
        }

        return pathsBy.map { (characterId, paths) ->
            Split(
                characterId = characterId,
                paths = paths,
                matches = matchesBy[characterId].orEmpty(),
                verdicts = verdictsBy[characterId].orEmpty(),
                crossCharacterMatches = crossMatchesBy[characterId] ?: 0,
                crossCharacterVerdicts = crossVerdictsBy[characterId] ?: 0
            )
        }
    }

    /**
     * 한 캐릭터 몫만 — **순위표·상성 상세·대결 화면이 이것을 쓴다.**
     *
     * ## 왜 [split]을 부르고 하나를 집지 않는가
     * **이 함수가 도는 자리가 한 판 누를 때마다다.** [split]은 판을 한 번만 훑으므로 그 축의
     * 비용은 같지만, **세계관 인원(N)에 붙는 비용이 따로 있다** — 인원마다 경로 목록과 버킷과
     * [Split]을 만들고 그중 하나만 쓴다. 인원이 수백이면 그 쓰레기가 **판마다** 난다.
     * 무엇보다 [split]은 캐릭터 표 전부를 받아야 해서 **화면이 그것을 들고 있어야 하는데**,
     * 이 함수가 필요한 것은 *내 경로*와 *소유 표*뿐이다.
     *
     * ## 그래서 두 벌이 됐고, 그 위험은 시험이 든다
     * 훑는 벌이 둘이면 *"순위표가 세는 판"*과 *"점수표가 세는 판"*이 갈릴 길이 생긴다 —
     * 그 갈림이 정확히 B-175가 등재된 이유다. **잣대는 [claimOf] 하나로 공유하고**(판정이
     * 두 벌이 되지는 않는다), **둘이 같은 답을 낸다는 사실 자체를 시험이 잰다**
     * (`splitOf는 split과 글자 그대로 같은 몫을 낸다`). 버킷 짜기는 판정이 아니라 기계적인
     * 자리라 이 처분이 성립한다.
     *
     * @param paths 이 캐릭터의 참가자 경로 — 목록에 적힌 **원본 표기 그대로**(R-42).
     */
    fun splitOf(
        characterId: Long,
        paths: List<String>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict>,
        owners: Owners
    ): Split {
        val mine = ArrayList<DuelMatch>()
        var crossMatches = 0
        for (match in matches) {
            when (val claim = claimOf(match.aCode, match.bCode, owners)) {
                is Claim.Owned -> if (claim.characterId == characterId) mine.add(match)
                is Claim.Cross -> if (characterId in claim.characterIds) crossMatches++
                Claim.None -> {}
            }
        }

        val myVerdicts = ArrayList<DuelCounterVerdict>()
        var crossVerdicts = 0
        for (verdict in verdicts) {
            val members = DuelRecords.decodeMembers(verdict.memberCodes)
            if (members.isEmpty()) continue
            when (val claim = claimOf(members, owners)) {
                is Claim.Owned -> if (claim.characterId == characterId) myVerdicts.add(verdict)
                is Claim.Cross -> if (characterId in claim.characterIds) crossVerdicts++
                Claim.None -> {}
            }
        }

        return Split(
            characterId = characterId,
            paths = paths,
            matches = mine,
            verdicts = myVerdicts,
            crossCharacterMatches = crossMatches,
            crossCharacterVerdicts = crossVerdicts
        )
    }

    /**
     * **캐릭터를 넘는 판을 골라낸다** — 순위표가 *"N개 있습니다"*라고 말한 바로 그 판들 (B-208).
     *
     * ## 왜 세는 자리 옆에 두는가
     * 순위표는 [Split.crossCharacterMatches]로 **수만** 말하고, 기록 화면은 그 판을 **줄로**
     * 보여야 한다. 세는 쪽과 고르는 쪽이 각자 판정하면 *"N개라더니 M개가 보인다"*가 되는데,
     * 그 둘은 **같은 화면을 오가는 한 동작**이라 어긋남이 곧바로 눈에 띈다(확정 15-8의 착수
     * 조건이 지목한 자리다). 그래서 잣대를 [claimOf] 하나로 두고, 세기와 고르기가 그것만 부른다.
     * **둘이 같은 답을 낸다는 사실 자체를 시험이 잰다**(`splitOf`의 수와 이 목록의 크기).
     *
     * @param characterId 0 이하면 **축 전체의** 캐릭터-넘는 판. 0보다 크면 **그 캐릭터가 낀
     *   것만** — 순위표의 고지는 *그 캐릭터 몫*이라(`splitOf`) 범위를 맞추지 않으면 수가 갈린다.
     * @return [matches]가 준 차례 그대로. 기록 화면이 최근순으로 넘기므로 그 차례가 곧 표시 차례다.
     */
    fun crossCharacterMatchesOf(
        matches: List<DuelMatch>,
        owners: Owners,
        characterId: Long = 0L
    ): List<DuelMatch> = matches.filter { match ->
        when (val claim = claimOf(match.aCode, match.bCode, owners)) {
            is Claim.Cross -> characterId <= 0L || characterId in claim.characterIds
            else -> false
        }
    }
}
