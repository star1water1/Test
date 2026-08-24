package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch

/**
 * **이미지 축의 참가자 코드가 파일 개명을 따라가는 규칙의 단일 소스** (B-104 소비처 ⓑ·ⓒ의 빗장).
 *
 * 설계 정본은 `docs/duel_system_design_2026-08.md` **13장**이다.
 *
 * 이미지 축의 참가자 코드는 **이미지 경로**다([DuelMatch.aCode]가 그 규약의 원문). 경로는
 * 캐릭터 코드와 달리 **바뀐다** — 재압축이 파일을 새 UUID로 갈고, 되돌리기가 그것을 물리고,
 * 엑셀·월드패키지가 다른 기기의 경로를 이 기기의 것으로 옮긴다. 그때 판의 참가자 코드를
 * 함께 옮기지 않으면 **쌓아 둔 판이 통째로 고아가 되거나 남의 이미지에 붙는다** — 설계 4장 ①의
 * ⚠️가 이미지 축을 닫아 두었던 이유가 이것이고, 이 파일이 그 빗장이다.
 *
 * **R-32와 같은 부류다.** 대표 이미지 포인터가 `imagePaths`의 한 원소를 가리키듯, 판의 참가자
 * 코드도 같은 목록을 가리킨다. 다른 것은 **가리키는 쪽이 다른 표에 산다**는 것뿐이라, 포인터를
 * 엔티티 안에서 정합시키는 `Character.withImagePaths`로는 닿지 않는다. 그래서 경로를 바꾸는
 * 자리가 저장소 함수를 한 번 더 불러야 하고, 그 짝을 `tools/check_duel_image_codes.sh`가 본다.
 *
 * ## 여기가 pure인 이유
 * 아래 판정 셋은 전부 **틀려도 오류가 나지 않는다** — 조용히 어긋난 판이 남을 뿐이다.
 * 저장소(Room)에 두면 이 저장소의 어떤 로컬 검증도 그 규칙을 보지 못한다(「세션 착수 규칙」 4번).
 *
 * ## 지키는 규칙 셋
 *
 * 1. **대조는 정규 경로로 한다.** 개명을 기록하는 자리가 원본 표기와 canonical 표기를 섞어
 *    넘긴다(`ImageManagerViewModel.commitInternal`이 실제로 둘 다 넘긴다). [ImagePathMatch]가
 *    그 대조의 단일 소스이고, 새 코드는 그것만 쓴다.
 * 2. **합쳐져 뜻을 잃은 행은 지우지 않는다.** 개명이 여럿을 하나로 모으면 판의 두 참가자가
 *    같아질 수 있다. 그때도 **행을 지우지 않고 그대로 옮겨 적는다** — 그러면
 *    [DuelMatch.isWellFormed]가 거짓이 되어 적합이 `malformedMatches`로 **세어서 알린다**
 *    (개발 의도 2번). 지우면 사용자가 고른 승패가 말없이 사라지고, 그것이 이 앱이 금지하는
 *    무음 유실이다.
 * 3. **처분은 뜻을 잃으면 손대지 않는다.** `(axisId, memberKey)`가 유니크라, 옮겨 적은 키가
 *    이미 있는 처분과 부딪히거나 참가자가 하나로 접히면 **쓰기 자체가 실패한다.** 그런 행은
 *    옛 코드 그대로 두고 [Plan.verdictConflicts]로 센다 — 옛 코드는 살아 있지 않은 참가자가
 *    되어 화면이 이미 세고 있는 자리(`missingParticipants`)로 흘러가므로, 사라지지 않는다.
 */
object DuelImageParticipants {

    /**
     * 옮겨 적을 것 전부. **행을 그대로 담는 것이 아니라 바뀐 행만 담는다** — 안 바뀐 수만 행을
     * 다시 쓰면 이 앱에서 가장 큰 표에 쓸모없는 쓰기가 붙는다.
     *
     * @property matches 코드가 바뀐 판.
     * @property verdicts 코드가 바뀐 처분.
     * @property collapsedMatches 옮기고 나니 두 참가자가 같아진 판 수(규칙 2 — 그래도 옮겨 적는다).
     * @property verdictConflicts 옮기면 유니크 키가 부딪히거나 참가자가 하나로 접혀
     *   **손대지 않은** 처분 수(규칙 3).
     */
    data class Plan(
        val matches: List<DuelMatch>,
        val verdicts: List<DuelCounterVerdict>,
        val collapsedMatches: Int,
        val verdictConflicts: Int
    ) {
        /** 쓸 것이 있는가 — 없으면 호출부가 트랜잭션 자체를 열지 않는다. */
        val any: Boolean get() = matches.isNotEmpty() || verdicts.isNotEmpty()

        /** 사용자에게 말할 것이 있는가. */
        val hasCaveat: Boolean get() = collapsedMatches > 0 || verdictConflicts > 0

        companion object {
            val EMPTY = Plan(emptyList(), emptyList(), 0, 0)
        }
    }

    /**
     * 개명 표를 정규 경로 색인으로. **키는 정규화하고 값은 원본 표기를 지킨다** —
     * 저장에 들어갈 것은 사용자·파일 시스템이 실제로 쓰는 표기여야 한다(정규화한 값을 적으면
     * 다음 대조가 정규화에 기대게 되고, 정규화가 실패하는 날 코드가 죽는다 —
     * `CharacterRepresentativeImage.retain`이 같은 이유로 목록의 표기를 지킨다).
     */
    private fun index(renames: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>(renames.size)
        for ((old, new) in renames) {
            val key = ImagePathMatch.canonical(old)
            val value = new.trim()
            if (key.isEmpty() || value.isEmpty()) continue
            // 제자리 개명은 표에서 뺀다 — 남겨 두면 아래 사슬 걷기가 헛돈다.
            if (ImagePathMatch.canonical(value) == key) continue
            out[key] = value
        }
        return out
    }

    /**
     * 이미지 참가자 코드를 **사람이 읽는 이름**으로 — 경로에서 파일 이름만 뗀다.
     *
     * 경로를 통째로 적으면 카드 폭을 다 먹으면서 두 참가자가 앞부분이 똑같아 오히려 못 가른다.
     * 파일 이름은 폴더를 열었을 때 사용자가 실제로 보는 이름이라 정리할 때 그대로 이어지고,
     * '이미지' 시트의 '파일명' 열이 이미 그 글자를 든다.
     *
     * **없는 척하지 않는다** — 파일 이름을 못 떼면 *"이름 없음"*이 아니라 받은 글자 그대로다
     * (개발 의도 2번). 비어 있는 코드만 빈 글자로 남는데, 그것은 애초에 참가자가 아니다.
     *
     * 이 판정이 여기 있는 이유는 **쓰는 자리가 셋**이기 때문이다 — 대결 화면(`DuelPlayFragment`)·
     * 기록 화면(`DuelMatchesFragment`)·엑셀 '대결 기록' 시트. 셋이 각자 `substringAfterLast('/')`를
     * 쓰던 동안 엑셀만 그 규칙이 없어 **필수 열이 빈칸으로 나갔다**(2026.08.23 실측: 36행 중 23행).
     */
    fun displayName(code: String?): String {
        val raw = code?.trim().orEmpty()
        return raw.substringAfterLast('/').ifBlank { raw }
    }

    /**
     * 이미지 축의 판·처분이 참조하는 이미지 경로 전부 — **엑셀·백업 완전성이 이 데이터를 아는
     * 유일한 자리**(`ImageZipHelper.collectDuelImagePaths`가 부른다).
     *
     * `winnerCode`도 담는다 — 정상 판이면 `aCode`·`bCode` 중 하나와 같아 `Set`이 그냥
     * 흡수하지만, **깨진 판**([DuelMatch.isWellFormed] — 외부 편집된 엑셀 등으로 승자가 두
     * 참가자 어느 쪽도 아닌 경우, `malformedMatches`로 세어지는 그 부류)은 승자가 **셋째
     * 경로**일 수 있다. 그 경로가 다른 판의 참가자로 우연히 안 걸리면 여기서 빠뜨리는 순간
     * 이 함수가 막으려는 바로 그 문제(참조를 몰라 zip·손실 고지가 놓치는 것)가 재현된다.
     *
     * **[com.novelcharacter.app.excel.ImageZipHelper.collectAllImagePathsWithStatus]와 다른
     * 물음이다.** 그 함수는 "지금 캐릭터·세계관·작품이 쓰는가"(고아 정리·`StorageAnalyzer`의
     * 계약)를 답하고, 여기는 "이 백업이 이 그림을 담아야 왕복이 되는가"를 답한다 — 지워진
     * 그림이 낀 판은 이미 정상 상태로 다뤄지므로([DuelRecords]의 orphan 집계) 두 물음을
     * 하나로 합치면 안 된다.
     */
    fun referencedPaths(matches: List<DuelMatch>, verdicts: List<DuelCounterVerdict>): Set<String> {
        val out = HashSet<String>(matches.size * 3 + verdicts.size * 2)
        for (match in matches) {
            if (match.aCode.isNotBlank()) out.add(match.aCode)
            if (match.bCode.isNotBlank()) out.add(match.bCode)
            match.winnerCode?.let { if (it.isNotBlank()) out.add(it) }
        }
        for (verdict in verdicts) {
            for (member in DuelRecords.decodeMembers(verdict.memberCodes)) {
                if (member.isNotBlank()) out.add(member)
            }
        }
        return out
    }

    /**
     * 한 코드를 개명 표에 따라 옮긴다. **사슬을 끝까지 따라간다** — 한 표 안에 `a→b`와 `b→c`가
     * 함께 있을 수 있고(`FolderRoundtripLedger.putRenameBounded`가 넣을 때 접기는 하지만,
     * 이 함수가 받는 표는 그 장부만이 아니다), 한 번만 따라가면 중간 경로에서 멈춘다.
     *
     * 걸음 수를 표 크기로 묶는 것은 **고리를 만나도 돌지 않기 위해서다**(`a→b`, `b→a`).
     * 고리면 마지막에 닿은 값을 그대로 쓴다 — 어느 쪽이든 살아 있는 경로 하나이고,
     * 무한히 도는 것보다 낫다.
     */
    fun follow(code: String?, renames: Map<String, String>): String {
        val raw = code?.trim().orEmpty()
        if (raw.isEmpty() || renames.isEmpty()) return raw
        return followIndexed(raw, index(renames))
    }

    private fun followIndexed(code: String, byCanonical: Map<String, String>): String {
        var current = code
        var steps = 0
        val seen = HashSet<String>()
        while (steps < byCanonical.size) {
            val key = ImagePathMatch.canonical(current)
            if (key.isEmpty() || !seen.add(key)) break
            current = byCanonical[key] ?: break
            steps++
        }
        return current
    }

    /**
     * 이 축의 판·처분을 개명에 맞춰 옮겨 적을 계획.
     *
     * @param matches 그 이미지 축의 판 전부.
     * @param verdicts 그 이미지 축의 처분 전부 — 유니크 충돌을 보려면 **바뀌지 않는 것까지**
     *   넘겨야 한다(옮긴 키가 부딪힐 상대가 그쪽에 있다).
     */
    fun plan(
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict>,
        renames: Map<String, String>
    ): Plan {
        val byCanonical = index(renames)
        if (byCanonical.isEmpty()) return Plan.EMPTY

        val changedMatches = ArrayList<DuelMatch>()
        var collapsed = 0
        for (match in matches) {
            val a = followIndexed(match.aCode, byCanonical)
            val b = followIndexed(match.bCode, byCanonical)
            // 승자는 **비어 있으면 비운 채로 둔다** — null은 무승부라는 뜻이고, 개명이 그것을
            // 승패로 바꿔서는 안 된다.
            val winner = match.winnerCode?.takeIf { it.isNotBlank() }
                ?.let { followIndexed(it, byCanonical) }
            if (a == match.aCode && b == match.bCode && winner == match.winnerCode) continue
            if (ImagePathMatch.same(a, b)) collapsed++
            changedMatches.add(match.copy(aCode = a, bCode = b, winnerCode = winner))
        }

        // 옮기지 않는 처분의 키까지 담아 둔다 — 그것이 충돌 상대다.
        val takenKeys = HashSet<String>(verdicts.size)
        val changedVerdicts = ArrayList<DuelCounterVerdict>()
        val pending = ArrayList<Pair<DuelCounterVerdict, List<String>>>()
        for (verdict in verdicts) {
            val members = DuelRecords.decodeMembers(verdict.memberCodes)
            val moved = members.map { followIndexed(it, byCanonical) }
            if (moved == members) {
                takenKeys.add(verdict.memberKey)
                continue
            }
            pending.add(verdict to moved)
        }
        var conflicts = 0
        for ((verdict, moved) in pending) {
            // 규칙 3 — 접혔거나 부딪히면 손대지 않는다. 옛 키를 자리에 남겨 둬야
            // 다른 행이 그 자리를 차지하지 않는다.
            val distinct = moved.filter { it.isNotBlank() }.distinct()
            val key = DuelRecords.memberKey(moved)
            if (distinct.size < 2 || DuelRecords.shapeOf(moved) != verdict.shape || !takenKeys.add(key)) {
                conflicts++
                takenKeys.add(verdict.memberKey)
                continue
            }
            changedVerdicts.add(
                verdict.copy(memberCodes = DuelRecords.encodeMembers(moved), memberKey = key)
            )
        }

        return Plan(
            matches = changedMatches,
            verdicts = changedVerdicts,
            collapsedMatches = collapsed,
            verdictConflicts = conflicts
        )
    }
}
