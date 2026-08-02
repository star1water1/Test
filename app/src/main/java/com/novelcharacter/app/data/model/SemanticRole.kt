package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 커스텀 필드와 시스템 특수 필드(__birth, __death 등)를 연동하는 역할 정의.
 * FieldDefinition.config JSON에 "semanticRole" 키로 저장됨.
 */
enum class SemanticRole(
    val key: String,
    val label: String,
    val linkedKey: String,
    val description: String,
    /**
     * 이 역할이 성립하는 필드 종류 (B-81).
     *
     * 역할은 시스템 특수 필드([linkedKey])와 잇는 것이라 **그 시스템 필드를 가진 종류에서만
     * 뜻이 있다.** 지금은 여덟 전부 캐릭터 축이다(`__birth`·`__death`·`__alive`·`__age`·
     * `__height`·`__weight`·`__body_size`) — 그래서 사건·작품에서는 [forEntityType]이 빈 목록을
     * 내고, 호출부가 그 사실만 보고 섹션을 감춘다.
     *
     * **사건·작품에 뜻 있는 역할을 여는 자리가 여기다** — 사건의 시작연도·종료연도(연표가
     * 커스텀 필드를 축으로 쓸 수 있게 된다), 작품의 출간연도·순서. 소비처(연표 축·정렬)까지
     * 함께 세워야 하므로 이 목록에 싣는 것은 그때 한다. 목록에 실리는 순간 섹션은 저절로 선다.
     */
    val entityTypes: Set<String> = setOf(FieldDefinition.ENTITY_CHARACTER)
) {
    BIRTH_YEAR("birth_year", "출생연도", "__birth", "생일 알림, 위젯, 나이 자동계산, 생존기간 통계"),
    BIRTH_DATE("birth_date", "생일(월/일)", "__birth", "생일 알림, 오늘의 캐릭터 위젯"),
    DEATH_YEAR("death_year", "사망연도", "__death", "생존 여부 판정, 생존기간 통계"),
    ALIVE("alive", "생존 여부", "__alive", "사망연도 연동, 생존/사망 상태 추적"),
    AGE("age", "나이", "__age", "표준 년도 기반 자동 나이 계산"),
    HEIGHT("height", "키(신장)", "__height", "체형 분석 연동"),
    WEIGHT("weight", "체중", "__weight", "체형 분석, BMI 연동"),
    BODY_SIZE("body_size", "신체 사이즈", "__body_size", "체형 분석, 컵사이즈 연동");

    companion object {
        private const val CONFIG_KEY = "semanticRole"

        fun fromKey(key: String?): SemanticRole? =
            entries.find { it.key == key }

        fun fromConfig(configJson: String): SemanticRole? {
            return try {
                val json = JSONObject(configJson)
                fromKey(json.optString(CONFIG_KEY, null))
            } catch (_: Exception) {
                null
            }
        }

        fun applyToConfig(existing: String, role: SemanticRole?): String {
            return try {
                val json = JSONObject(existing)
                if (role != null) {
                    json.put(CONFIG_KEY, role.key)
                } else {
                    json.remove(CONFIG_KEY)
                }
                json.toString()
            } catch (_: Exception) {
                if (role != null) {
                    """{"$CONFIG_KEY":"${role.key}"}"""
                } else {
                    existing
                }
            }
        }

        /**
         * [entityType]에서 성립하는 역할만 (B-81).
         *
         * **빈 목록은 "이 종류에는 시맨틱 역할이 없다"는 뜻이고, 호출부는 그 사실만 보고
         * 섹션을 통째로 감춘다.** 종류 이름으로 감춤을 하드코딩하지 않는 것이 요점이다 —
         * 나중에 사건·작품 역할이 [entityTypes]에 실리면 섹션이 **저절로** 다시 서므로,
         * R-24("성립하지 않는 조합의 설정은 보이지 않는다")를 조건문 없이 지키면서
         * 확장 경로는 열린 채로 남는다.
         */
        fun forEntityType(entityType: String): List<SemanticRole> =
            entries.filter { entityType in it.entityTypes }
    }
}
