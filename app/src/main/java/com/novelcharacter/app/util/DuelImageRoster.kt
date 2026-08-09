package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
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
 */
object DuelImageRoster {

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
     */
    data class Roster(
        val entries: List<Entry>,
        val skippedSingleImage: Int,
        val skippedNoImage: Int
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
    fun build(characters: List<Character>, matches: List<DuelMatch>): Roster {
        // 경로 → 그 경로를 가진 캐릭터. **정규 경로로 색인한다** — 판에 적힌 표기와 목록의
        // 표기가 갈릴 수 있고(개명 추종이 원본 표기를 지키므로), 그때 대조에 실패하면
        // 멀쩡한 판이 통째로 남의 것도 내 것도 아닌 것이 된다.
        val ownerByPath = HashMap<String, Long>()
        val entries = ArrayList<Entry>(characters.size)
        var singles = 0
        var none = 0

        for (character in characters) {
            val paths = CharacterRepresentativeImage.paths(character.imagePaths)
            when (paths.size) {
                0 -> { none++; continue }
                1 -> { singles++; continue }
            }
            for (path in paths) ownerByPath[ImagePathMatch.canonical(path)] = character.id
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
            return Roster(entries, singles, none)
        }

        val playedBy = HashMap<Long, Int>()
        // 짝의 열쇠를 **문자열로 잇지 않는다** — 참가자가 경로라 어떤 구분자를 골라도 그
        // 글자를 담은 파일 이름에서 키가 깨진다(`DuelRecords.encodeMembers`가 JSON 배열을
        // 쓰는 것과 같은 이유). 쌍 자체를 열쇠로 두면 구분자가 아예 필요 없다.
        val pairsBy = HashMap<Long, MutableSet<Pair<String, String>>>()
        for (match in matches) {
            val a = ImagePathMatch.canonical(match.aCode)
            val b = ImagePathMatch.canonical(match.bCode)
            val owner = ownerByPath[a] ?: continue
            // **두 참가자가 같은 캐릭터일 때만 센다.** 캐릭터를 넘는 판은 이 화면이 만들지
            // 않지만 엑셀·백업으로 들어올 수 있고, 그것을 어느 한쪽에 세면 그 캐릭터의
            // 진행률이 100%를 넘는다. 세지 않은 사실은 적합의 고아 집계가 말한다.
            if (ownerByPath[b] != owner) continue
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
            skippedNoImage = none
        )
    }
}
