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
}
