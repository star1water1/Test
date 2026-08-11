package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 연표·작품 시트의 **필드 열 하나가 어느 필드를 가리키는가** — 내보내기 헤더 규칙
 * ([EntityFieldHeaders])의 역함수 중 *구역 해석* 몫 (순수 JVM, 단위 테스트 대상).
 *
 * ## 왜 갈라 두는가 (B-65)
 *
 * 두 시트는 캐릭터 시트와 달리 **전 구역의 필드를 한 장에 싣는다.** 그래서 내보내기에는 다른
 * 세계관 필드의 값도 나가는데, 가져오기는 *"그 행의 세계관에 속한 필드"*만 해석했다 — 소유자가
 * 세계관을 옮겨 생긴 값은 들이기에서 경고와 함께 버려졌고 **신규 기기 복원에서 사라졌다.**
 *
 * **사용자 확정 15번(ㄱ1)이 방향을 정했다: 열 이름이 맞으면 세계관이 달라도 받아들인다.**
 * 왕복 무결성이 오입력 방지보다 앞선다 — 버려진 값은 되돌릴 길이 없고, 잘못 들어온 값은
 * 화면에서 고칠 수 있다.
 *
 * 판정을 두 호출부(작품·사건)에 따로 적으면 갈린다. 실제로 갈린 채로 있었다 — 둘 다 같은
 * `when` 블록을 각자 들고 있었고, 한쪽만 고치면 R-16의 짝 규칙이 깨진다.
 */
object EntityFieldColumnResolver {

    /**
     * @param resolved 기대 헤더 정확 일치로 **이미 확정된** 필드(내보낸 그대로의 머리). null이면 폴백 해석 몫.
     * @param fieldName 폴백이 읽은 필드 이름
     * @param qualifier 폴백이 읽은 구역 한정자 — 세계관 이름, [EntityFieldHeaders.GLOBAL_QUALIFIER], 또는 null(한정자 없음)
     * @param entityUniverseId 이 행(작품·사건)의 세계관 — 무소속이면 null
     * @param allFields 그 대상(entityType)의 **전 구역** 필드
     */
    fun resolve(
        resolved: FieldDefinition?,
        fieldName: String,
        qualifier: String?,
        entityUniverseId: Long?,
        allFields: List<FieldDefinition>,
        universeIdsByName: Map<String, Long>
    ): FieldDefinition? {
        // ① 내보낸 그대로의 머리 — **구역이 달라도 받는다**(확정 15번 ㄱ1).
        //    이 한 줄이 B-65가 고친 것의 전부다: 종전에는 `resolved.universeId == entityUniverseId`가
        //    아니면 null이라, 정확히 그 필드라고 확정해 놓고도 값을 버렸다.
        if (resolved != null) return resolved

        val byName = allFields.filter { it.name == fieldName }
        if (byName.isEmpty()) return null

        // ② 한정자가 있으면 그 구역에서만 찾는다 — 한정자는 "이 이름이 여러 구역에 있다"는
        //    내보내기의 신호이므로, 구역을 무시하면 동명의 남의 필드에 붙는다(R-1).
        if (qualifier != null) {
            val qualifiedUniverseId = universeIdsByName[qualifier]
            if (qualifiedUniverseId != null) {
                return byName.find { it.universeId == qualifiedUniverseId }
            }
            // 실존 세계관 이름이 아니면 전역 한정자로만 해석한다(그 판정은 parseFallback이 이미 걸렀다).
            if (qualifier == EntityFieldHeaders.GLOBAL_QUALIFIER) {
                return byName.find { it.universeId == null }
            }
            return null
        }

        // ③ 한정자가 없다 = 내보내기 규칙상 **그 이름이 유일했다**는 뜻이다.
        //    ⓐ 이 행의 구역에 그 이름이 있으면 그것(종전 동작 — 손편집 파일의 가장 흔한 모양)
        //    ⓑ 없으면, 이름이 전 구역에서 유일할 때만 그 필드. 유일하지 않은데 한정자도 없으면
        //       고를 근거가 없다 — 여기서 멈추고 호출부가 경고한다(오배정보다 생략이 낫다).
        byName.find { it.universeId == entityUniverseId }?.let { return it }
        return byName.singleOrNull()
    }
}
