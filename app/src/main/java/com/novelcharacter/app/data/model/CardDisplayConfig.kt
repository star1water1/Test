package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 목록 카드에 이 필드의 값을 얹을지의 설정 (B-5).
 * `FieldDefinition.config` JSON의 "cardDisplay" 객체에 저장된다.
 *
 * 기본값이 `show = true`인 이유: 값을 넣었는데 목록에서 보이지 않아 하나씩 열어봐야 아는 상태가
 * 원칙 04 위반이다. 그러니 **기본은 보이는 쪽**이고, 넘치면 사용자가 끄는 방향으로 조절한다
 * (자율성 우선 — 쓸모는 사용자가 가린다).
 *
 * 소비처는 **연표 사건 카드와 작품 목록 카드 둘**이다(B-67에서 둘이 됐고, 그때 스위치를
 * 여는 조건도 함께 넓어졌다 — `FieldEditDialog.setupCardDisplaySection`).
 * 캐릭터 목록 등 다른 카드가 같은 설정을 쓰게 될 수 있어 필드 종류를 가리지 않는 이름으로
 * 둔다 (원칙 01). **그 조건은 *요약을 실제로 그리는 목록이 있는가*로 넓힌다** — 그리는 곳이
 * 없는 종류에 열면 켜도 아무 데도 안 나오는 스위치가 된다(원칙 02).
 */
data class CardDisplayConfig(
    val show: Boolean = DEFAULT_SHOW
) {
    companion object {
        private const val KEY = "cardDisplay"
        const val DEFAULT_SHOW = true

        /**
         * 카드에 한 번에 그리는 필드 수 상한.
         *
         * 상한을 두는 이유는 규모다 — 필드 수십 개짜리 세계관에서 사건마다 전부 그리면 카드가
         * 화면을 넘고 스크롤 성능도 무너진다. 다만 **잘라낸 사실을 감추지 않는다**:
         * 소비처는 남은 개수를 반드시 함께 표시해 "값이 더 있다"는 존재를 알린다(원칙 04).
         */
        const val MAX_ON_CARD = 4

        fun fromConfig(configJson: String): CardDisplayConfig {
            return try {
                val obj = JSONObject(configJson).optJSONObject(KEY) ?: return CardDisplayConfig()
                CardDisplayConfig(show = obj.optBoolean("show", DEFAULT_SHOW))
            } catch (_: Exception) {
                // 설정을 못 읽는다고 값을 숨기지는 않는다 — 기본값(표시)으로 떨어진다.
                CardDisplayConfig()
            }
        }

        fun applyToConfig(existingConfig: String, cardConfig: CardDisplayConfig): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                JSONObject()
            }
            if (cardConfig.show == DEFAULT_SHOW) {
                // 기본값이면 키를 남기지 않는다 — config JSON이 기본값으로 부풀지 않게.
                root.remove(KEY)
            } else {
                root.put(KEY, JSONObject().put("show", cardConfig.show))
            }
            return root.toString()
        }
    }
}
