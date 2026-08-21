package com.novelcharacter.app.data.model

import org.json.JSONObject
import com.novelcharacter.app.util.stringOrNull

/**
 * 잇는 시스템 필드가 없는 역할의 [SemanticRole.linkedKey].
 *
 * **최상위에 두는 것은 enum 항목의 인자로 쓰이기 때문이다** — companion의 값은 항목보다
 * 늦게 서므로 그 자리에서 쓸 수 없다(`const`는 인라인되어 되지만, 되는지 아닌지를 읽는
 * 사람이 따져야 하는 자리를 만들지 않는다).
 */
private const val MARKER_ONLY = ""

/**
 * 커스텀 필드와 시스템 특수 필드(__birth, __death 등)를 연동하는 역할 정의.
 * FieldDefinition.config JSON에 "semanticRole" 키로 저장됨.
 *
 * **역할이 전부 동기화 규칙인 것은 아니다** — [GENDER]처럼 *"이 필드가 무엇인가"*만 말하는
 * **표식 전용 역할**이 있고, 그런 역할의 [linkedKey]는 [MARKER_ONLY]다.
 */
enum class SemanticRole(
    val key: String,
    val label: String,
    /**
     * 이 역할이 잇는 시스템 특수 필드. **[MARKER_ONLY]이면 잇는 것이 없다** — 역할이
     * *동기화 규칙*이 아니라 **표식**인 경우다([GENDER]가 그 첫 사례다).
     */
    val linkedKey: String,
    val description: String,
    /**
     * 이 역할이 성립하는 필드 종류 (B-81).
     *
     * 지금은 아홉 전부 캐릭터 축이다 — 그래서 사건·작품에서는 [forEntityType]이 빈 목록을
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
    BODY_SIZE("body_size", "신체 사이즈", "__body_size", "체형 분석, 컵사이즈 연동"),

    /**
     * **성별** (B-122 — 사용자 확정 Q4). 아홉 번째이자 **첫 표식 전용 역할**이다.
     *
     * ## 왜 역할이어야 하는가
     * 성별은 지금 보통 SELECT 필드라 **앱이 "어느 필드가 성별인지" 알 길이 없다.** 라벨
     * 문자열로 추측하는 것은 R-20이 금지한다(*"성별"*·*"性別"*·*"Gender"*·*"젠더"*를 다 적어도
     * 다음 사용자의 말 하나에서 어긋나고, 어긋난 사실을 아무도 모른다). 사용자가 *이것이
     * 성별이다*라고 표시하는 것만이 어긋나지 않는 길이다.
     *
     * ## 규칙이 붙지 않는다 — [linkedKey]가 [MARKER_ONLY]인 이유
     * [AGE]가 표준연도와 나이를 서로 갱신하듯 잇는 시스템 필드가 있는 것과 달리, 성별은
     * **다른 값에서 유도되지도 다른 값을 바꾸지도 않는다.** 상태변화 동기화가 없으므로
     * [SemanticFieldSyncHelper]는 이 역할을 다루지 않고, 그것이 결함이 아니라 이 역할의 성질이다.
     *
     * ## 대결 밖에서도 값을 한다 (원칙 05)
     * 대결 카드의 필수 트리오가 첫 소비처일 뿐이고, 통계의 성별 비율과 AI 컨텍스트의 성별
     * 줄도 추측 없이 이 필드를 집을 수 있게 된다.
     */
    GENDER("gender", "성별", MARKER_ONLY, "대결 카드 트리오, 통계 성별 비율, AI 컨텍스트");

    companion object {
        private const val CONFIG_KEY = "semanticRole"

        fun fromKey(key: String?): SemanticRole? =
            entries.find { it.key == key }

        fun fromConfig(configJson: String): SemanticRole? {
            return try {
                val json = JSONObject(configJson)
                fromKey(json.stringOrNull(CONFIG_KEY))
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
