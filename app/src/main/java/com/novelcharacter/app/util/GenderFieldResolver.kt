package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole

/**
 * 세계관 필드 목록에서 **성별 필드**를 집는다 — 역할이 먼저, key는 뒤다.
 *
 * [SemanticRole.GENDER]가 *"이 필드가 무엇인가"*를 라벨 추측 없이 말해 주므로 그것이
 * 정본이다(R-20). 다만 **`"gender"` key 폴백을 남긴다** — 역할은 사용자가 필드 편집 창에서
 * 손으로 다는 것이고, 프리셋 템플릿·구버전에서 넘어온 세계관의 성별 필드는 역할 없이
 * `key = "gender"`로만 서 있다. 폴백을 걷어내면 그 세계관에서 잘 돌던 기능이 조용히 죽는다.
 *
 * 종전에는 이 규칙이 이름은행 일괄 등록(`NameBankViewModel`)에만 있고 캐릭터 편집의
 * [은행]·이름 추천은 역할만 봤다 — 같은 세계관에서 일괄 등록은 성별을 싣는데 [은행]은
 * *"성별이 같은 이름 먼저"*를 건너뛰었다(S5 `10-ㄱ`, 에뮬레이터 2026-11-07). 그래서 한 자리로 모았다.
 *
 * 역할 필드가 둘 이상이면 첫 번째다 — 필드 관리의 차례가 사용자가 정한 차례이기 때문이다
 * (`DuelPlayFragment`가 같은 자리에서 정해 둔 규칙).
 */
object GenderFieldResolver {

    /** 폴백 key — 프리셋 템플릿(`PresetTemplates`)이 성별 필드에 주는 key다. */
    const val FALLBACK_KEY = "gender"

    fun pick(fields: List<FieldDefinition>): FieldDefinition? =
        fields.firstOrNull { SemanticRole.fromConfig(it.config) == SemanticRole.GENDER }
            ?: fields.firstOrNull { it.key == FALLBACK_KEY }
}
