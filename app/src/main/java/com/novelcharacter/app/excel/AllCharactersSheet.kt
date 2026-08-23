package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * '전체 캐릭터' 시트(U-12a)의 **공유 필드 열 판정** — 순수 계산이라 테스트가 고정한다.
 *
 * 이 시트가 있는 이유는 캐릭터 시트가 세계관마다 갈려 **전 인원에 정렬·필터·피벗을 걸 수
 * 없다**는 것이다(실사용 대조 §8-3 E-3). 그런데 열을 전 세계관 필드의 합집합으로 두면
 * 시트가 다시 넓어져 피벗이 어려워지고, 반대로 고정 열만 두면 세계관·작품·태그로만 셀 수
 * 있어 통계 화면이 이미 하는 일과 겹친다. 그래서 **여러 세계관이 함께 쓰는 필드**만 싣는다.
 *
 * **판정은 (필드키, 타입)이다** — 통계의 세계관 병합이 쓰는 것과 같은 규칙이라
 * 여기서 새 개념을 만들지 않는다(`field_value_library.md`의 '세계관 간 병합 해석').
 *
 * ## 그 판정이 열 머리를 겹치게 한다 — [disambiguated]가 그 자리다
 *
 * 그룹이 `(필드키, 타입)`인데 머리는 `이름(필드키)`라 **타입이 안 들어간다.** 같은 키가
 * 세계관마다 다른 타입으로 쓰이고 그 타입 그룹이 각각 세계관 2곳 이상이면 **열이 둘 서고
 * 이름이 같아진다.** 실측(2026.08.23 내보낸 파일): `height`가 NUMBER 2곳·TEXT 6곳이라
 * `키(height)` 열이 AI·AM 두 자리에 섰고, 65명의 키가 이름이 같은 두 열로 갈려 있었다.
 *
 * **이 시트에서 그것이 특히 나쁜 이유는 시트의 값어치가 정확히 그 축이기 때문이다** —
 * 어느 쪽을 정렬하거나 평균 내도 조용히 일부만 잡히고, 화면에는 갈렸다는 단서가 없다.
 * 위 문단이 *"이름만 쓰면 어느 축인지 확정되지 않는다"*며 키를 병기하는데 키까지 같아
 * 그 방어가 무력해진다.
 *
 * **합치지 않는다** — 등급 'S'와 숫자 4를 한 열에 섞으면 그 열로 만든 피벗이 조용히 틀리고,
 * 그것이 애초에 타입으로 나눈 이유다. **버리지도 않는다** — 형제 시트는 겹친 뒤 필드를
 * 오버플로 시트로 보내는데([CharacterFieldHeaders] ②) 이 시트에는 그런 짝이 없어
 * 버리면 그 값이 이 시트에서 그냥 사라진다. 그래서 **머리에 타입을 병기한다.**
 *
 * 병기하는 글자는 [FieldDefinition.type] 원문이다 — '필드 정의' 시트의 '타입' 열과 **같은
 * 글자**라, 어느 축인지 확인하러 갈 자리가 곧 그 글자를 든 자리다.
 */
object AllCharactersSheet {

    /** 타입 병기 구분자 — `키(height·NUMBER)`. */
    private const val TYPE_SEPARATOR = "·"

    /** 여러 세계관이 함께 쓰는 캐릭터 필드 하나. [header]는 시트의 열 이름이다. */
    data class SharedField(
        val key: String,
        val type: String,
        val header: String,
        /** 이 (키, 타입)을 가진 세계관 수 — 열 순서의 근거다(많이 쓰는 축이 앞에 온다). */
        val universeCount: Int
    )

    /** 열이 되기로 정해진 (키, 타입) 하나 — 머리를 짓기 전 단계다. */
    private data class Candidate(
        val key: String,
        val type: String,
        val name: String,
        val universeCount: Int
    )

    /**
     * [fields] 중 **캐릭터가 실제로 있는 세계관** 2곳 이상이 함께 쓰는 (필드키, 타입)을 고른다.
     *
     * 캐릭터가 없는 세계관을 세지 않는 이유: 그 세계관은 이 시트에 행을 하나도 만들지 않으면서
     * 열만 늘린다. 전부 빈 칸인 열이 피벗 후보 목록에 섞이면 그 자체가 소음이다.
     *
     * 열 이름에 필드키를 항상 병기한다 — **같은 키의 필드가 세계관마다 다른 이름을 가질 수
     * 있어서**, 이름만 쓰면 어느 축인지 확정되지 않는다. 이름은 가장 많이 쓰이는 것을 고르고
     * 동수면 사전순으로 갈라 출력이 실행마다 흔들리지 않게 한다.
     *
     * **돌려주는 머리는 유일하다** — [disambiguated]가 든다.
     */
    fun sharedFields(
        fields: List<FieldDefinition>,
        universeIdsWithCharacters: Set<Long>
    ): List<SharedField> {
        val grouped = fields
            .filter { it.universeId in universeIdsWithCharacters }
            .groupBy { it.key to it.type }

        val candidates = grouped.mapNotNull { (keyType, defs) ->
            val universeCount = defs.mapTo(HashSet()) { it.universeId }.size
            if (universeCount < 2) return@mapNotNull null
            val (key, type) = keyType
            val name = defs.groupingBy { it.name }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
            Candidate(key = key, type = type, name = name, universeCount = universeCount)
        }.sortedWith(
            compareByDescending<Candidate> { it.universeCount }
                .thenBy { it.key }
                .thenBy { it.type }
        )

        return disambiguated(candidates)
    }

    /**
     * 머리가 겹치는 후보에만 타입을 병기하고, **그러고도 겹치면 번호로 가른다.**
     *
     * 겹칠 때만 붙이는 것은 형제 규칙과 같은 결정이다([CharacterFieldHeaders.headersFor]의
     * `disambiguate`) — 안 겹치는 열까지 `성별(gender·SELECT)`이 되면 머리가 길어지기만 한다.
     * 판정을 *키가 겹치는가*가 아니라 **머리가 겹치는가**로 두는 것이 요점이다: 같은 키라도
     * 타입 그룹마다 이름이 다르면(`키(height)` · `신장(height)`) 머리는 이미 갈려 있다.
     *
     * 번호 단은 **이름 자체가 병기 꼴인 필드**를 위한 것이다 — 이름이 `키(height·NUMBER)`인
     * 필드가 실제로 있을 수 있고(사용자가 필드 이름을 자유롭게 짓는다 — 원칙 01), 그러면
     * 병기가 남의 머리와 부딪힌다. 형제 시트가 `규모(명)`에서 겪은 것과 같은 부류다.
     * 가져오기가 이 시트를 읽지 않으므로 번호는 왕복 규약을 건드리지 않는다.
     */
    private fun disambiguated(candidates: List<Candidate>): List<SharedField> {
        val baseHeaders = candidates.map { characterFieldHeader(it.name, it.key, disambiguate = true) }
        val baseCounts = baseHeaders.groupingBy { it }.eachCount()
        val taken = HashSet<String>(candidates.size)
        return candidates.mapIndexed { index, candidate ->
            val base = baseHeaders[index]
            val wanted = if ((baseCounts[base] ?: 0) > 1) {
                characterFieldHeader(
                    candidate.name,
                    candidate.key + TYPE_SEPARATOR + candidate.type,
                    disambiguate = true
                )
            } else {
                base
            }
            var header = wanted
            var ordinal = 2
            while (!taken.add(header)) {
                header = "$wanted($ordinal)"
                ordinal++
            }
            SharedField(
                key = candidate.key,
                type = candidate.type,
                header = header,
                universeCount = candidate.universeCount
            )
        }
    }
}
