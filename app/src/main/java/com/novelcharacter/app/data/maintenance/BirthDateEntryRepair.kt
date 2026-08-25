package com.novelcharacter.app.data.maintenance

import com.novelcharacter.app.util.BirthDateFormat

/**
 * **값 라이브러리에 남은 규격 밖 생일 표기를 저장 모양으로 올리는 계획** (2026.08.25).
 *
 * ## 무엇이 갈려 있었나 — 고친 쪽이 짝을 두고 갔다
 *
 * [LegacyValueFormats.repairBirthDates]가 `character_field_values`의 `5-30`을 `05-30`으로
 * 올릴 때 **`field_value_entries`를 손대지 않았다.** 그래서 한 파일 안에서 이렇게 갈렸다
 * (실측 — 2026.08.25 사용자가 내보낸 파일, '스텔라 크로니클'):
 *
 * | 어디 | 무엇이 있었나 |
 * |---|---|
 * | 캐릭터 값 | `05-30`·`06-07`을 각 한 명이 쓴다 |
 * | 값 라이브러리 | 그 둘의 **행이 없고**, 대신 `5-30`·`6-7`이 사용횟수 0으로 남아 있다 |
 *
 * '필드 데이터' 시트는 스스로를 *"필드마다 실제로 쓰인 값이 모이는 시트"*라 적는데 그 말이
 * 거짓이 된 자리다. 증상도 조용하지 않다 — **자동완성이 규격 밖 표기를 제안하고**,
 * 표시라벨·별칭·카테고리를 붙일 자리가 없으며, '미사용 자동수집 정리'가 그 고아 행을 지우자고
 * 권한다.
 *
 * ## 왜 수확만으로는 부족한가
 *
 * `harvest*`는 **더하기만 한다.** 그것만 돌리면 `05-30`은 생기지만 `5-30`이 그대로 남아
 * 같은 생일이 값 둘로 서고, 그것이 애초에 [BirthDateFormat]이 없애려던 모양이다
 * (원칙 02 — 통계·검색이 하나를 둘로 센다). 그래서 **엔트리 쪽도 함께 올린다.**
 *
 * ## 규칙 — 앱의 '값 이름 변경'과 같은 처분
 *
 * [com.novelcharacter.app.data.repository.FieldValueLibraryRepository.renameValue]가
 * *"고친 값은 옛 표기를 별칭으로 남긴다"*로 못박아 둔 그 처분을 그대로 쓴다. 별칭은
 * *"데이터에 있는 다른 표기 → 이 값"*이므로, 옛 파일·옛 검색어가 계속 이 값에 닿는다.
 *
 * - 저장 모양의 엔트리가 **없으면** → [Action.Rename] (값을 올리고 옛 표기를 별칭으로)
 * - **있으면** → [Action.Merge] (별칭을 그쪽으로 접고 이 행을 지운다 — 유니크 색인이
 *   `(fieldDefinitionId, value)`라 두 행이 같은 값을 들 수 없다)
 *
 * **읽을 수 없는 값은 건드리지 않는다** — [BirthDateFormat.needsRepair]의 계약 그대로다.
 * 사용자가 적어 둔 글자를 우리가 못 읽는다고 해서 바꾸지 않는다(개발 의도 2번).
 *
 * **[com.novelcharacter.app.data.model.FieldValueEntry.source]는 손대지 않는다** — 표기만
 * 올렸을 뿐 그 값이 여전히 자동 수확된 것이기 때문이다. `MANUAL`로 올리면 아무도 안 쓰는
 * 값(실측의 `4-28`)이 '미사용 자동수집 정리'에서 영영 빠져 대기열에 남는다. 병합도 같다 —
 * 앱의 `mergeValues`가 `MANUAL`로 올리는 것은 *사용자가 골라서* 접었기 때문이고,
 * 여기는 규격을 맞추는 정리라 그 뜻이 서지 않는다.
 *
 * **차례가 있다.** 두 엔트리가 같은 저장 모양으로 접히는 경우(`5-30`과 `2026-05-30`)
 * 먼저 처리된 쪽이 대상이 되고 나중 것이 그리로 병합된다. id 오름차순으로 도는 것은
 * 출력이 실행마다 흔들리지 않게 하기 위한 것이다.
 *
 * **멱등이다** — 계획을 적용한 뒤 다시 세우면 빈 목록이 나온다(올린 값은 `needsRepair`가
 * 거짓이 된다).
 *
 * 순수 코틀린인 것은 의도다 — SQL로 적으면 0 채움 규칙이 두 벌이 되고([BirthDateFormat]과),
 * 무엇보다 순수 JVM 시험이 이 계획을 재지 못한다.
 */
object BirthDateEntryRepair {

    /** 한 생일 필드의 값 라이브러리 행 하나 — 계획에 필요한 것만 든다. */
    data class Entry(val id: Long, val value: String, val aliases: List<String>)

    sealed class Action {
        /** 이 행의 값을 [newValue]로 올리고 별칭을 [aliases]로 바꾼다. */
        data class Rename(val id: Long, val newValue: String, val aliases: List<String>) : Action()

        /**
         * [sourceId]의 정체·별칭을 [targetId]로 접고 [sourceId] 행을 지운다.
         * 대상 행의 별칭은 [targetAliases]가 된다.
         */
        data class Merge(val sourceId: Long, val targetId: Long, val targetAliases: List<String>) : Action()
    }

    /**
     * 한 생일 필드의 엔트리 전부를 받아 할 일을 낸다. 고칠 것이 없으면 빈 목록.
     *
     * @param entries 그 필드의 `field_value_entries` 행 전부 — **일부만 넘기면
     *   저장 모양의 행이 이미 있는지 알 수 없어 병합이어야 할 자리가 이름 변경이 되고,
     *   유니크 색인에 걸린다.**
     */
    fun plan(entries: List<Entry>): List<Action> {
        // **id 오름차순 한 벌로 둘 다 돈다** — 아래 두 순회가 다른 차례를 보면 어느 행이
        // 대상이 되는가가 부르는 쪽의 정렬에 따라 흔들린다.
        val ordered = entries.sortedBy { it.id }

        // 지금 어떤 값이 어느 행에 서 있는가 — 계획이 진행되는 동안 함께 옮겨 간다.
        // 계획을 세우기 전에 **전부** 담아야 한다: 저장 모양의 행이 뒤에 있어도 앞의 행이
        // 그것을 보고 병합을 고른다(못 보면 이름 변경을 골라 유니크 색인에 걸린다).
        val standing = HashMap<String, Entry>()
        for (entry in ordered) {
            val token = entry.value.trim()
            if (token.isEmpty()) continue
            // 같은 값이 두 행에 있을 수 없으므로(유니크 색인) 먼저 온 것을 둔다.
            standing.putIfAbsent(token, entry.copy(value = token))
        }

        val actions = ArrayList<Action>()
        for (entry in ordered) {
            val token = entry.value.trim()
            if (!BirthDateFormat.needsRepair(token)) continue
            // `needsRepair`가 참이면 읽히는 값이므로 여기서 null이 나올 수 없다 —
            // 그래도 되묻는 것은 두 함수가 갈릴 때 조용히 잘못 쓰지 않기 위해서다.
            val canonical = BirthDateFormat.canonicalOrNull(token) ?: continue

            // 저장 모양이 이미 서 있으면 그 행이 대상이다(그 행은 canonical이라 자기 자신일 수 없다).
            val target = standing[canonical]
            if (target == null) {
                val aliases = foldAliases(canonical, entry.aliases, token)
                actions.add(Action.Rename(entry.id, canonical, aliases))
                standing.remove(token)
                standing[canonical] = entry.copy(value = canonical, aliases = aliases)
            } else {
                val aliases = foldAliases(canonical, target.aliases + entry.aliases, token)
                actions.add(Action.Merge(entry.id, target.id, aliases))
                standing.remove(token)
                standing[canonical] = target.copy(aliases = aliases)
            }
        }
        return actions
    }

    /**
     * 별칭을 접는다 — 옛 표기를 더하고, 빈 것·정규값과 같은 것·중복을 뺀다.
     *
     * 정규값 자신이 별칭에 남으면 해석이 자기를 가리켜 무의미하고, 엑셀의 '별칭' 칸에도
     * 같은 글자가 두 번 보인다.
     */
    private fun foldAliases(canonical: String, existing: List<String>, oldToken: String): List<String> =
        (existing + oldToken)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != canonical }
            .distinct()
}
