package com.novelcharacter.app.data.model

import org.json.JSONObject
import com.novelcharacter.app.util.stringOr

/**
 * '필수 입력'이 저장을 **막는가**, 표시하고 **알리기만 하는가** (B-90).
 *
 * `FieldDefinition.config` JSON에 `"requiredEnforcement"` 키로 담긴다 —
 * 새 컬럼이 아니므로 **DB 마이그레이션이 없다.**
 *
 * ## 왜 강도를 나누는가
 *
 * 종전에 `isRequired`가 하는 일은 **저장 차단 하나뿐**이었고, 그것을 부르는 경로는 캐릭터 축
 * 둘뿐이었다. 그래서 사건·작품 필드에서는 스위치를 켜도 아무 일도 일어나지 않았고(R-24 위반),
 * 캐릭터에서는 반대로 **너무 셌다** — 쓰다 만 캐릭터를 저장 못 하게 막는 것은 러프 입력
 * (원칙 04)을 부정한다. 사용자가 그 강도를 기각한 자리다(2026.08.01 "빡빡하다").
 *
 * 그래서 '필수'의 뜻을 **"채워야 할 것" 표식**으로 옮기고, 차단은 원하는 사람이 필드별로
 * 고르는 것으로 남겼다. 표식은 종류를 가리지 않으므로 **사건·작품 구분이 사라진다.**
 */
/**
 * 필수 필드에 붙는 화면 표식 (B-90).
 *
 * 라벨을 만드는 자리가 캐릭터·사건·작품 폼에 흩어져 있다(각 화면이 자기 칸을 따로 조립한다).
 * **그래서 표식의 모양은 한 곳에 둔다** — 흩어 두면 종류마다 다른 표식이 붙고, 그것이 바로
 * B-90이 고치려던 '필수가 종류마다 다르게 동작한다'의 재발이다.
 */
object RequiredFieldMark {
    /** 이름 뒤에 붙는 표식. 모양을 바꾸려면 여기만 고친다. */
    const val SUFFIX = " *"

    /** 폼 라벨 — 필수면 표식이 붙는다. */
    fun label(name: String, isRequired: Boolean): String =
        if (isRequired) "$name$SUFFIX" else name

    fun label(field: FieldDefinition): String = label(field.name, field.isRequired)
}

enum class RequiredEnforcement(val key: String) {
    /** 빈 칸을 표시하고 알리되 **저장은 된다.** 새로 만드는 필드의 기본값. */
    NOTIFY("notify"),

    /** 비어 있으면 **저장을 막는다.** B-90 이전 캐릭터 필드의 동작이다. */
    BLOCK("block");

    companion object {
        /** 편집 화면이 config를 조립할 때 쓰는 키 — 문자열을 두 벌로 적지 않는다. */
        const val CONFIG_KEY = "requiredEnforcement"

        /**
         * 새로 만드는 필드의 기본값 — **알림**이다.
         * 차단이 기본이면 앱이 사용자에게 완성을 강요하게 되고, 그것이 기각된 강도다.
         */
        val DEFAULT_FOR_NEW = NOTIFY

        /**
         * 키가 없는 필드(= B-90 이전에 만들어진 필드)의 강도. **종류마다 다르다.**
         *
         * - **캐릭터**: [BLOCK] — 종전에 실제로 저장을 막던 자리다. 조용히 알림으로 낮추면
         *   사용자가 기대하던 방어가 **말없이 사라진다**(개발 의도 2번). 바꾸려면 사용자가
         *   그 필드를 열어 직접 내린다.
         * - **사건·작품**: [NOTIFY] — 이쪽은 **한 번도 막은 적이 없다.** 여기에 [BLOCK]을
         *   주면 어제까지 저장되던 사건이 오늘 갑자기 막히는데, 그것이 B-90의 처방 ①이었고
         *   사용자가 기각했다. '있던 동작을 지킨다'는 같은 원칙이 종류에 따라 반대 값을 낸다.
         */
        fun legacyDefaultFor(entityType: String): RequiredEnforcement =
            if (entityType == FieldDefinition.ENTITY_CHARACTER) BLOCK else NOTIFY

        /**
         * 이 필드의 실제 강도. 설정이 없으면 [legacyDefaultFor]가 답한다.
         *
         * `isRequired`가 꺼져 있으면 강도를 묻는 것 자체가 뜻이 없으므로 호출부가 먼저 거른다
         * — 이 함수는 강도만 답한다.
         */
        fun resolve(configJson: String, entityType: String): RequiredEnforcement {
            val stored = fromConfigOrNull(configJson)
            return stored ?: legacyDefaultFor(entityType)
        }

        /** 저장된 값만 읽는다(없으면 null) — '설정되지 않음'과 '알림'을 가르려면 이쪽을 쓴다. */
        fun fromConfigOrNull(configJson: String): RequiredEnforcement? {
            return try {
                val key = JSONObject(configJson).stringOr(CONFIG_KEY, "")
                entries.find { it.key == key }
            } catch (_: Exception) {
                null
            }
        }
    }
}
