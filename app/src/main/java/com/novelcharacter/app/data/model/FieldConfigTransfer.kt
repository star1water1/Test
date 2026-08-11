package com.novelcharacter.app.data.model

/**
 * **필드 config가 세계관 경계를 넘을 때의 강등** — 한 자리에 모은 이유가 전부다 (B-113).
 *
 * config에는 *"이 세계관 안에서만 뜻이 있는 참조"*가 둘 산다:
 *
 * | 키 | 가리키는 것 | 넘어가면 |
 * |---|---|---|
 * | [GradeSystemRef.CONFIG_KEY] | 등급 체계 code | 남의 세계관 체계를 가리키는 유령 |
 * | [DuelGradeRef.CONFIG_KEY] | 대결 축 code | **남의 세계관 축을 정확히 찾는다** |
 *
 * 아래 칸이 위 칸보다 나쁘다. 등급 체계 code는 대상 세계관에 없으면 조회가 비지만, **대결 축
 * code는 전역 유니크라 다른 세계관의 축을 정확히 집어낸다** — 못 찾는 것이 아니라 오배정이고,
 * 오배정은 생략보다 나쁘다(R-1). 그래서 복사 경계에서 둘을 **함께** 걷어낸다.
 *
 * ## 왜 함수 하나로 모았는가
 *
 * 걷어내야 하는 자리가 여섯이다(프리셋 직렬화·복원, 필드 가져오기, 프리셋 병합 둘,
 * 월드패키지). 각 자리에서 두 줄씩 쓰면 **일곱 번째 자리가 생길 때 한 줄만 쓰는 것이
 * 자연스러워진다** — 그때 빠지는 쪽은 새로 들어온 키다. 이 저장소는 그 형태의 결함을 이미
 * 겪었고(R-29의 "종류를 손으로 더하는" 형태), 처방도 같다: **손으로 더하는 대신 한 자리로 모은다.**
 *
 * `tools/check_config_boundary.sh`가 그 한 자리를 우회하는 새 호출을 잡는다.
 */
object FieldConfigTransfer {

    /**
     * 세계관 경계를 넘는 복사의 강등 — **잃는 것은 참조뿐이고 필드는 그대로 동작한다.**
     *
     * 등급 표는 config에 물질화되어 있어([GradeSystemRef.GRADES_KEY]) 체계 참조를 벗겨도
     * 표·값·통계가 남는다. 대결 등급 산정은 그런 물질화가 없고 있을 수도 없다 — 대상
     * 세계관에는 그 순위 자체가 존재하지 않으므로, 남길 잔여가 없는 것이 맞다.
     */
    fun demoteAcrossUniverse(configJson: String): String =
        DuelGradeRef.remove(GradeSystemRef.demote(configJson))

    /**
     * **종류 경계를 넘는 복사의 강등** (B-63) — 세계관 경계와 **다른 축**이다.
     *
     * 위 [demoteAcrossUniverse]가 *"남의 세계관을 가리키는 참조"*를 걷는다면, 이쪽은
     * *"이 종류에서는 뜻이 없는 설정"*을 걷는다. 캐릭터 필드 '장소'를 사건 필드로 심을 때
     * (확정 14번 — 종류를 바꿔 심기), 그 필드가 달고 있던 나이 계산·체형 분석·대결 등급은
     * 사건에서 성립하지 않는다.
     *
     * **조용히 떨어뜨리면 변수 제어 위반이다**(개발 의도 2번) — 그래서 [droppedKeys]가
     * 무엇이 빠지는지 먼저 세고, 화면이 그것을 심기 전에 말한다.
     *
     * | 키 | 왜 종류를 넘지 못하는가 |
     * |---|---|
     * | `semanticRole` | 성립 종류를 [SemanticRole.entityTypes]가 정한다 — 그 목록이 단일 소스다 |
     * | `bodyAnalysis` | 체형 분석은 캐릭터 축이다(`BODY_SIZE`가 그 필드 타입) |
     * | [DuelGradeRef.CONFIG_KEY] | 대결 참가자는 캐릭터다 — 사건에는 그 순위가 존재하지 않는다 |
     * | [DefaultFieldRef.CONFIG_KEY] | 전역 기본 필드의 자리는 **(종류, 키)** 쌍이다 — 종류가 바뀌면 남의 자리를 가리킨다 |
     *
     * `gradeSystem`은 **걷지 않는다** — 등급 체계는 세계관 단위이지 종류 단위가 아니고,
     * 같은 세계관 안에서 종류만 바뀌는 이 경로에서는 참조가 그대로 성립한다.
     * (경계 둘을 함께 넘는 복사는 두 함수를 **차례로** 부른다 — 축이 다르므로 합치지 않는다.)
     *
     * @param targetEntityType 심을 종류. 이 값과 원래 종류가 같으면 원문 그대로다.
     */
    fun demoteAcrossEntityType(
        configJson: String,
        sourceEntityType: String,
        targetEntityType: String
    ): String {
        if (sourceEntityType == targetEntityType) return configJson
        val dropped = droppedKeys(configJson, sourceEntityType, targetEntityType)
        if (dropped.isEmpty()) return configJson
        return try {
            val obj = org.json.JSONObject(configJson)
            dropped.forEach { obj.remove(it) }
            obj.toString()
        } catch (_: Exception) {
            // 손상 JSON은 손대지 않는다 — 여기서 다시 쓰면 사용자가 적어 둔 원문까지 잃는다.
            configJson
        }
    }

    /**
     * [demoteAcrossEntityType]이 **실제로 걷어낼** config 키 — 고지의 단일 소스다.
     *
     * 걷는 쪽과 세는 쪽이 갈리면 화면이 예고한 것과 심기는 것이 달라진다(R-29가 조회·판정·
     * 순서·쓰기에 요구하는 것과 같은 계약). 그래서 강등이 이 목록을 그대로 쓴다.
     *
     * config에 **없는 키는 담기지 않는다** — 빠지지도 않을 것을 예고하면 사용자가 잃는 것이
     * 있다고 오해한다.
     */
    fun droppedKeys(
        configJson: String,
        sourceEntityType: String,
        targetEntityType: String
    ): List<String> {
        if (sourceEntityType == targetEntityType) return emptyList()
        val obj = try {
            org.json.JSONObject(configJson)
        } catch (_: Exception) {
            return emptyList()
        }
        val dropped = ArrayList<String>()
        // 역할은 이름을 박지 않고 성립 종류에 묻는다 — 나중에 사건 역할이 실리면
        // 그 역할은 저절로 살아남는다(`SemanticRoleEntityScopeTest`가 지키는 계약).
        val role = SemanticRole.fromConfig(configJson)
        if (role != null && targetEntityType !in role.entityTypes) dropped.add(SEMANTIC_ROLE_KEY)
        if (targetEntityType != FieldDefinition.ENTITY_CHARACTER) {
            if (obj.has(BODY_ANALYSIS_KEY)) dropped.add(BODY_ANALYSIS_KEY)
            if (obj.has(DuelGradeRef.CONFIG_KEY)) dropped.add(DuelGradeRef.CONFIG_KEY)
        }
        if (obj.has(DefaultFieldRef.CONFIG_KEY)) dropped.add(DefaultFieldRef.CONFIG_KEY)
        return dropped
    }

    /**
     * [SemanticRole]이 자기 키를 private으로 쥐고 있어 여기 한 벌 둔다.
     * 값이 갈리면 강등이 아무것도 못 걷으므로 `FieldConfigTransferTest`가 실제 config를
     * 왕복시켜 그 사실을 잠근다.
     */
    private const val SEMANTIC_ROLE_KEY = "semanticRole"

    /** [BodyAnalysisConfig]가 사는 키. 같은 이유로 여기 한 벌 둔다. */
    private const val BODY_ANALYSIS_KEY = "bodyAnalysis"
}
