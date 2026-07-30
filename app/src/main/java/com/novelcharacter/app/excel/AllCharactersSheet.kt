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
 */
object AllCharactersSheet {

    /** 여러 세계관이 함께 쓰는 캐릭터 필드 하나. [header]는 시트의 열 이름이다. */
    data class SharedField(
        val key: String,
        val type: String,
        val header: String,
        /** 이 (키, 타입)을 가진 세계관 수 — 열 순서의 근거다(많이 쓰는 축이 앞에 온다). */
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
     */
    fun sharedFields(
        fields: List<FieldDefinition>,
        universeIdsWithCharacters: Set<Long>
    ): List<SharedField> {
        val grouped = fields
            .filter { it.universeId in universeIdsWithCharacters }
            .groupBy { it.key to it.type }

        return grouped.mapNotNull { (keyType, defs) ->
            val universeCount = defs.mapTo(HashSet()) { it.universeId }.size
            if (universeCount < 2) return@mapNotNull null
            val (key, type) = keyType
            val name = defs.groupingBy { it.name }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
            SharedField(
                key = key,
                type = type,
                header = characterFieldHeader(name, key, disambiguate = true),
                universeCount = universeCount
            )
        }.sortedWith(
            compareByDescending<SharedField> { it.universeCount }
                .thenBy { it.key }
                .thenBy { it.type }
        )
    }
}
