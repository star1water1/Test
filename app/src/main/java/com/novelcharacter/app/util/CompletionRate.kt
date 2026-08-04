package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType

/**
 * 필드 완성도의 **단일 소스** (B-100 — B-90이 설계한 '필수 표식'의 소비처 ③).
 *
 * ## 무엇을 고치는가 — 둘이다
 *
 * **① 필수를 가중한다(이 항목의 본래 몫).** 종전 완성도는 모든 필드를 **동등하게** 셌다.
 * 그래서 "이 캐릭터 쓸 만한가"를 묻는 값이 *메모성 필드 스무 개를 채운 캐릭터*와
 * *필수 다섯 개를 채운 캐릭터*를 구분하지 못했다(원칙 02 — 입력량이 아니라 인사이트).
 * 이제 필수 칸은 [CompletionWeights.requiredWeight]배로 센다.
 *
 * **② '채운 칸'을 세는 법을 통일한다(착수 대조가 찾은 것).** 완성도를 내는 자리가
 * 열 곳인데 **분자의 규칙이 갈려 있었다** — 여섯 곳은 그 캐릭터의 값을 **전부** 셌고
 * (`values.count { it.value.isNotBlank() }`), 네 곳만 해당 세계관 정의와 교집합을 냈다.
 * 전자는 [com.novelcharacter.app.data.repository.CharacterFieldValueMerge]가 **일부러 남기는**
 * 값 — 다른 세계관 정의를 가리키는 보존 값, 사건 정의를 가리키는 값 — 까지 분자에 넣는다.
 * 분모는 현재 세계관 정의뿐이므로 **작품을 옮긴 적 있는 캐릭터의 완성도는 부풀고, 100%를
 * 넘을 수도 있다.** 게다가 캐릭터 상세 화면은 분모에서 `CALCULATED`를 빼지 않아
 * **같은 캐릭터가 화면마다 다른 %로 보였다.**
 *
 * 가중은 "어느 칸이 몇 배인가"를 정하는 일이므로 **칸의 집합이 먼저 하나여야 한다.**
 * 그래서 ②는 이 슬라이스 밖의 곁다리가 아니라 ①의 전제다.
 *
 * ## 규칙 — 소비처 ①②와 같은 칸을 센다
 *
 * 세는 칸은 **`CALCULATED`가 아닌 필드**다. 계산 필드는 사람이 값을 넣는 자리가 아니므로
 * 폼도([DynamicFieldFormBuilder]) 어시스턴트도([RequiredFieldGaps]) 세지 않는다.
 * 그중 **필수 표식이 붙은 칸**이 [RequiredFieldGaps.countsAsSlot]로 갈린다 —
 * 판정을 여기서 다시 쓰지 않고 그 함수를 부르는 것은, 소비처마다 '필수'가 다른 뜻이 되는
 * 것이 바로 B-90이 고친 결함이기 때문이다.
 *
 * **강도(`RequiredEnforcement`)는 보지 않는다.** 막든 알리든 *"채워야 할 칸"*이라는 사실은
 * 같다 — 소비처 ①②도 같은 이유로 강도를 보지 않는다.
 */
object CompletionRate {

    /** 이 필드가 완성도의 '칸'으로 세어지는가 — 계산 필드는 제외한다. */
    fun countsAsSlot(field: FieldDefinition): Boolean =
        FieldType.fromName(field.type) != FieldType.CALCULATED

    /**
     * 한 항목의 완성도. 셀 칸이 하나도 없으면 **null**(= 산출 불가)이다 —
     * 0%로 내면 "필드를 안 만든 세계관"과 "다 비운 캐릭터"가 같은 값이 된다.
     *
     * @param definitions 이 항목에 적용되는 필드 정의 전부(호출부가 세계관으로 좁혀 넘긴다).
     *   계산 필드가 섞여 있어도 된다 — 여기서 거른다.
     * @param filledDefIds **값이 비어 있지 않은** 필드 정의 id. 호출부가 값 테이블에서 만든다.
     *   [definitions] 밖의 id가 섞여 있어도 된다 — 교집합만 세므로 보존 값이 분자를 부풀리지 않는다.
     */
    fun of(
        definitions: List<FieldDefinition>,
        filledDefIds: Set<Long>,
        weights: CompletionWeights
    ): CompletionBreakdown? {
        var requiredTotal = 0
        var requiredFilled = 0
        var optionalTotal = 0
        var optionalFilled = 0
        for (def in definitions) {
            if (!countsAsSlot(def)) continue
            val filled = def.id in filledDefIds
            if (RequiredFieldGaps.countsAsSlot(def)) {
                requiredTotal++
                if (filled) requiredFilled++
            } else {
                optionalTotal++
                if (filled) optionalFilled++
            }
        }
        if (requiredTotal + optionalTotal == 0) return null
        return CompletionBreakdown(
            requiredTotal = requiredTotal,
            requiredFilled = requiredFilled,
            optionalTotal = optionalTotal,
            optionalFilled = optionalFilled,
            requiredWeight = weights.requiredWeight
        )
    }

    /**
     * [of]의 백분율만 필요한 호출부용 축약. 산출 불가면 null이다.
     */
    fun percentOf(
        definitions: List<FieldDefinition>,
        filledDefIds: Set<Long>,
        weights: CompletionWeights
    ): Float? = of(definitions, filledDefIds, weights)?.percent
}

/**
 * 완성도 가중치 (B-100 — 사용자 확정 ㄱ1+ㄱ2: **기본 배수를 두되 사용자가 바꿀 수 있게**).
 *
 * 저장 자리는 이 순수 계층 밖이다([com.novelcharacter.app.ui.stats.CompletionWeightPrefs]) —
 * 계산은 배수를 **받아서** 쓰기만 하므로, 배수를 어디에 두는가(P-4: 앱 전체인가 세계관
 * 단위인가)가 나중에 바뀌어도 이 계층은 그대로다.
 */
data class CompletionWeights(val requiredWeight: Float = DEFAULT_REQUIRED_WEIGHT) {

    init {
        require(requiredWeight >= MIN_REQUIRED_WEIGHT) { "requiredWeight must be >= $MIN_REQUIRED_WEIGHT" }
    }

    companion object {
        /**
         * 기본 배수. 필수 한 칸이 일반 두 칸만큼 셈해진다.
         *
         * **1이 아닌 것이 ㄱ1의 뜻이다** — 1로 두면 "기본 배수를 둔다"가 아무 일도 하지 않는
         * 설정이 된다(R-24가 금지한 자리). 대신 **필수를 한 개도 켜지 않은 세계관에서는
         * 배수가 곱해질 칸이 없어 값이 종전과 완전히 같다**(사용자 확정 ㄴ1).
         */
        const val DEFAULT_REQUIRED_WEIGHT = 2f

        /**
         * 배수의 하한. **1 미만을 허용하지 않는다** — 필수를 일반보다 *덜* 세는 것은
         * '필수'라는 말의 뜻과 반대이고, 사용자가 그것을 원한다면 그 필드의 필수를 끄면 된다.
         */
        const val MIN_REQUIRED_WEIGHT = 1f

        /** 배수의 상한. 넘겨도 막지 않고 [clamp]가 접는다 — 저장된 값이 범위 밖이어도 죽지 않게. */
        const val MAX_REQUIRED_WEIGHT = 5f

        /** 가중 없음 — 종전 동작. 비교·고지용이며 설정으로는 [MIN_REQUIRED_WEIGHT]가 같은 값이다. */
        val NONE = CompletionWeights(MIN_REQUIRED_WEIGHT)

        val DEFAULT = CompletionWeights(DEFAULT_REQUIRED_WEIGHT)

        /** 범위 밖 값을 접어 만든다 — 저장소·엑셀·복원 어디서 와도 계산이 깨지지 않는다. */
        fun clamp(requiredWeight: Float): CompletionWeights {
            val safe = if (requiredWeight.isNaN()) DEFAULT_REQUIRED_WEIGHT else requiredWeight
            return CompletionWeights(safe.coerceIn(MIN_REQUIRED_WEIGHT, MAX_REQUIRED_WEIGHT))
        }
    }
}

/**
 * 한 항목의 완성도 내역.
 *
 * 백분율만이 아니라 **필수·일반을 나눠 들고 있는 것**은 화면이 *"왜 이 숫자인가"*를 말할 수
 * 있어야 하기 때문이다 — 가중이 켜진 채 숫자만 바뀌면 사용자는 앱이 무엇을 세는지 알 수 없다
 * (원칙 04 — 일일이 확인하지 않으면 존재를 알 수 없는 데이터를 만들지 않는다).
 */
data class CompletionBreakdown(
    val requiredTotal: Int,
    val requiredFilled: Int,
    val optionalTotal: Int,
    val optionalFilled: Int,
    val requiredWeight: Float
) {
    /** 센 칸의 총수(가중 전). */
    val slotTotal: Int get() = requiredTotal + optionalTotal

    /** 채운 칸의 총수(가중 전). */
    val slotFilled: Int get() = requiredFilled + optionalFilled

    /** 가중 완성도(%). **필수가 0개면 [plainPercent]와 정확히 같다**(사용자 확정 ㄴ1). */
    val percent: Float
        get() {
            val total = requiredTotal * requiredWeight + optionalTotal
            if (total <= 0f) return 0f
            return (requiredFilled * requiredWeight + optionalFilled) / total * 100f
        }

    /** 가중 없는 완성도(%) — B-100 이전의 값. 가중이 실제로 무엇을 바꿨는지 고지할 때 쓴다. */
    val plainPercent: Float
        get() = if (slotTotal == 0) 0f else slotFilled.toFloat() / slotTotal * 100f

    /** 가중이 이 항목의 숫자를 실제로 바꾸는가 — 필수가 하나라도 있고 배수가 1이 아닐 때만이다. */
    val isWeighted: Boolean
        get() = requiredTotal > 0 && requiredWeight != CompletionWeights.MIN_REQUIRED_WEIGHT
}
