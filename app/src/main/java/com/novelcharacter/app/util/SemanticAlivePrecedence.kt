package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole
import org.json.JSONObject

/**
 * **사용자가 고른 생존여부가 파생을 이긴다** — 사망연도 → 생존여부 연동의 양보 규칙
 * (순수 로직 — JVM 시험 대상).
 *
 * ## 왜 이 판단이 필요한가 — 한 저장에 두 갈래가 같은 칸을 쓴다
 *
 * `SemanticFieldSyncHelper.applyFieldsToStateChange`는 **폼이 넘긴 값 전량**을 돈다
 * (바뀐 값만이 아니다 — `CharacterViewModel.updateCharacterWithFields`가 `values`를 그대로
 * 넘긴다). 그래서 사망연도 값이 남아 있는 캐릭터는 **저장할 때마다** `DEATH_YEAR` 갈래를
 * 지나고, 그 갈래가 `syncDeathToAlive(isDead = true)`로 **생존여부 필드값을 `deadValue`로
 * 덮어썼다.** 한편 `ALIVE` 갈래의 중립('불명') 처분은 이력만 `unknown`으로 적고 필드값은
 * 건드리지 않는다.
 *
 * 둘이 겹치면 이렇게 된다:
 *
 * | 사용자가 고른 것 | 종전 결과 |
 * |---|---|
 * | **불명** | 저장 직후 **'사망'으로 되돌아간다.** 고지는 한 줄도 없다 |
 * | **생존** | `ALIVE`가 사망연도를 지우는데 `DEATH_YEAR`가 **다시 세운다**(값 목록의 차례에 달렸다) |
 *
 * 게다가 최종 상태가 **`values`의 차례**에 달려, 필드값은 '사망'인데 이력은 `unknown`인
 * *서로 다른 말을 하는* 상태가 나올 수 있었다(연표 시점뷰·관계도가 이력을 읽는다).
 *
 * ## 처방 — 루프 **전에** 한 번 접는다
 *
 * 루프가 값을 하나씩 처분하는 구조로는 *"같은 저장에 생존여부가 함께 실려 왔는가"*를 알 수
 * 없다. 그래서 [SemanticClearPlan]이 비움을 미리 접는 것과 **같은 모양으로** 이 판단도
 * 미리 접는다 — 그러면 두 갈래의 차례가 답을 바꾸지 못한다.
 *
 * **명시가 파생을 이긴다**가 규칙이다. 사용자가 생존여부 칸에 무언가를 골랐으면 그것이
 * 사용자의 판단이고, 사망연도에서 끌어낸 값은 *추측*이다(개발 의도 3번 — 자율성 우선).
 */
object SemanticAlivePrecedence {

    /**
     * 생존여부 **필드값 → `__alive` 행의 표식**. 빈 값이면 null — 적을 것이 없다.
     *
     * 이 대응이 함수가 된 것은 **두 자리가 그것을 각자 적고 있었기 때문이다**(2026.08.23).
     * 저장 경로(`SemanticFieldSyncHelper`)는 값에서 표식을 끌어냈는데, 1회 마이그레이션
     * (`NovelCharacterApp.syncAliveFor`)은 *사망·출생 이력이 있는가*만 보고 표식을 정했다.
     * 그래서 **생존여부 칸은 채워져 있는데 이력이 하나도 없는 캐릭터**가 마이그레이션의
     * 어느 갈래에도 안 걸려 `__alive` 행 없이 남았다 — 연표·통계·필터가 그 캐릭터만
     * 다르게 셌다(실측: 사용자 데이터에서 두 명).
     *
     * 규칙은 저장 경로가 쓰던 것 그대로다: 사망값 → 사망, 생존값 → 생존, **그 밖의 모든
     * 값은 '불명'**('불명' 선택지뿐 아니라 사용자가 선택지를 고친 뒤 남은 옛 값도 여기 온다 —
     * 모르는 값을 생존·사망 어느 쪽으로도 접지 않는 것이 요점이다).
     * 사망값과 생존값이 같게 설정된 파일에서는 **사망이 이긴다**(종전 `when`의 차례 그대로).
     */
    fun aliveMarker(rawValue: String, aliveValue: String, deadValue: String): String? {
        val raw = rawValue.trim()
        if (raw.isEmpty()) return null
        return when (raw) {
            deadValue -> CharacterStateChange.ALIVE_MARKER_DEAD
            aliveValue -> CharacterStateChange.ALIVE_MARKER_ALIVE
            else -> CharacterStateChange.ALIVE_MARKER_UNKNOWN
        }
    }

    /** 사망연도 갈래가 이번 저장에서 어디까지 할 것인가. */
    enum class DeathDerivation {
        /** 종전 그대로 — 이력을 적고 생존여부 필드값도 파생시킨다. 생존여부가 안 실려 온 저장이다 */
        APPLY,

        /** `__death` 이력만 적는다 — 생존여부 필드값과 `__alive`는 `ALIVE` 갈래의 몫이다('사망'·'불명') */
        HISTORY_ONLY,

        /** 아무것도 하지 않는다 — '생존'을 골랐으므로 `ALIVE` 갈래가 사망연도와 `__death`를 지운다 */
        SKIP
    }

    /**
     * 이 저장이 실어 온 **명시적** 생존여부를 보고 사망연도 갈래의 몫을 정한다.
     *
     * @param fields 이 세계관의 필드 정의
     * @param values 지금 저장되는 값. **빈 값은 고르지 않은 것으로 본다** — 저장 규약이
     *   빈 값을 값 없음으로 읽으므로 여기서도 같게 읽는다(자리마다 갈리지 않게).
     */
    fun deathDerivation(
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>
    ): DeathDerivation {
        val aliveField = fields.firstOrNull {
            SemanticRole.fromConfig(it.config) == SemanticRole.ALIVE
        } ?: return DeathDerivation.APPLY

        val chosen = values
            .firstOrNull { it.fieldDefinitionId == aliveField.id }
            ?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return DeathDerivation.APPLY

        // **여기 온 config는 반드시 파싱된다** — 위의 `SemanticRole.fromConfig`가 이미
        // `JSONObject(config)`를 세우고 실패하면 null을 내는 문지기라, 못 읽는 config를
        // 든 필드는 애초에 안 잡히고 위의 `?: return APPLY`(생존여부 필드 없음)로 나간다.
        // 그것이 곧 *"설정을 못 읽으면 종전 동작을 지킨다"*이기도 하다 — 모른다고 파생을
        // 끄면 사망연도만 적은 사용자가 생존여부를 영영 못 받는다(그쪽이 더 흔한 경로다).
        // 그래서 이 자리에 try/catch를 한 벌 더 두지 않는다(닿지 않는 갈래는 시험도 못 잰다).
        val aliveValue = JSONObject(aliveField.config).stringOr("aliveValue", "")

        // '생존'은 사망연도 자체를 지우는 선택이다 — 파생이 그것을 되살리면 안 된다.
        return if (aliveValue.isNotEmpty() && chosen == aliveValue) DeathDerivation.SKIP
        else DeathDerivation.HISTORY_ONLY
    }
}
