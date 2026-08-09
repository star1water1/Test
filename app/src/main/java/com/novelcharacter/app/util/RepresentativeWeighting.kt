package com.novelcharacter.app.util

import kotlin.math.pow

/**
 * **대표 이미지 추첨의 가중치** (B-104 소비처 ⓑ — 설계 `docs/duel_system_design_2026-08.md` 13-5).
 *
 * ## 왜 '고정 1위'가 아니라 '가중 추첨'인가 (사용자 확정 2026.08.09)
 * 물었을 때 사용자가 되물어 갈음한 자리다: *"대결 1위 그림이 대표가 되면 이미지 다양성이
 * 아쉽잖아. 확률이면 모를까."* 그래서 `representative_image_design_2026-08.md` **D9**의
 * 사다리 ②칸(*이미지 축 승자를 살아 있는 칸으로 두는 안*)은 **폐기됐고**, 처방은
 * [CharacterRepresentativeImage]가 이미 하고 있는 **시드 랜덤을 균등에서 가중으로 바꾸는 것**이다.
 * 사다리는 그대로 셋이다 — 지정한 대표가 이기고, 없을 때의 추첨만 기울어진다.
 *
 * ## 가중치는 지어내지 않는다 — γ가 그대로 확률이다
 * 적합이 내는 강함 γ는 **"i가 j를 이길 확률 = γi/(γi+γj)"**로 정의된 수이고
 * ([DuelRating]), 표시 점수는 `앵커 + SCALE·log₁₀γ`다. 그래서 점수를 되돌린 γ를 그대로
 * 추첨 무게로 쓰면 **"이길 확률이 두 배인 그림이 두 배 자주 뜬다"**가 되어, 무게의 뜻을
 * 따로 설명할 필요가 없다. 세기는 그 γ에 지수 α를 씌워 조절한다(`γ^α`) —
 * α=0이면 균등, α=1이면 강함 그대로다.
 *
 * ## 두 개의 빗장
 * 1. **한 판도 안 친 그림은 앵커에 선다**(γ=1). 그래서 **대결을 한 번도 안 한 캐릭터는
 *    무게가 전부 같고, [weights]가 그때 null을 내어 종전 균등 추첨이 그대로 돈다** —
 *    *"지금과 완전히 같다"*가 문서의 약속이 아니라 코드의 성질이 된다.
 * 2. **벌어진 차이를 [SPREAD_CAP]에서 끊는다.** 안 끊으면 점수가 크게 벌어진 캐릭터에서
 *    1위 그림의 무게가 수백 배가 되어 *확률*이 사실상 *고정*이 된다 — 사용자가 물리친
 *    바로 그 모양이다. 끊으면 최악의 비율이 `10^α`로 묶인다(약하게 ≈ 3.2:1, 세게 10:1).
 *
 * **Android 무의존 순수 계층이다** — 무게가 틀려도 앱은 죽지 않고 그림만 조용히 기울며,
 * 컴파일도 정적 검사도 전부 통과한다. 실행으로 재는 자리에 둔다(「세션 착수 규칙」 4번).
 */
object RepresentativeWeighting {

    /**
     * 추첨을 얼마나 기울이는가 — **약하게가 기본이다**(사용자 확정).
     *
     * @property alpha γ에 씌우는 지수. 0이면 균등이고 1이면 강함 그대로다.
     */
    enum class Strength(val alpha: Double) {
        /** 꺼둠 — 종전 그대로의 균등 추첨. 점수를 읽지도 않는다. */
        OFF(0.0),

        /** 약하게 — γ의 제곱근. [SPREAD_CAP]까지 벌어져도 약 3.2배 안에 든다. */
        WEAK(0.5),

        /** 세게 — γ 그대로. [SPREAD_CAP]까지 벌어지면 10배다. */
        STRONG(1.0);

        companion object {
            /** 저장된 값을 읽는다. 모르는 값은 기본값으로 — 외부에서 편집된 설정이 앱을 막지 않는다. */
            fun of(name: String?): Strength = entries.firstOrNull { it.name == name } ?: WEAK
        }
    }

    /**
     * 무게를 낼 때 세는 점수 차의 상한(1위 대비).
     *
     * **[DuelRating.SCALE]과 같은 값인 것은 우연이 아니다** — 그 눈금 한 칸이 곧 *강함 10배*라
     * 여기서 끊으면 *"세게 두어도 열 배까지"*라고 말할 수 있다. 이보다 더 벌어진 차이를
     * 그대로 세면 추첨이 아니라 고정이 되고, 그것이 이 기능이 갈음한 그 안이다.
     */
    const val SPREAD_CAP = DuelRating.SCALE

    /**
     * 이 캐릭터의 그림별 추첨 무게 — 키는 **정규 경로**([ImagePathMatch.canonical])다.
     *
     * 위치가 아니라 경로로 키를 두는 것은 무게를 만든 시점과 쓰는 시점 사이에 목록이
     * 바뀔 수 있기 때문이다(편집·폴더 왕복). 위치로 두면 그때 **남의 그림의 무게**가 적용되고,
     * 그것은 오류가 나지 않아 아무도 모른다.
     *
     * @param paths 이 캐릭터의 이미지 경로.
     * @param scores 경로별 표시 점수. **여기 없는 경로는 앵커**(=γ 1)로 친다 — 한 판도
     *   안 친 그림이고, [DuelScoreIndex]의 계약 2가 그런 참가자를 표에서 빼기 때문에
     *   *"빠졌다"*와 *"평균이다"*가 여기서 같은 뜻이 된다. **키는 어느 표기로 넘겨도 된다** —
     *   이 함수가 정규화해 맞춘다(판에 적힌 표기와 목록의 표기는 갈릴 수 있다. 개명 추종이
     *   원본 표기를 지키기 때문이며, 그때 대조에 실패하면 멀쩡한 점수가 통째로 무시된다).
     * @return 기울일 것이 없으면 **null**이다 — 세기가 꺼져 있거나, 점수가 하나도 없거나,
     *   전원이 같은 점수일 때. 호출부는 null을 받으면 종전 균등 추첨을 그대로 쓴다.
     */
    fun weights(
        paths: List<String>,
        scores: Map<String, Int>,
        strength: Strength
    ): Map<String, Double>? {
        if (strength == Strength.OFF || paths.size < 2 || scores.isEmpty()) return null

        val canonScores = HashMap<String, Int>(scores.size)
        for ((key, score) in scores) {
            val canon = ImagePathMatch.canonical(key)
            if (canon.isNotEmpty()) canonScores[canon] = score
        }

        val scoreByPath = LinkedHashMap<String, Int>(paths.size)
        for (path in paths) {
            val key = ImagePathMatch.canonical(path)
            if (key.isEmpty()) continue
            scoreByPath[key] = canonScores[key] ?: DuelRating.ANCHOR_SCORE
        }
        if (scoreByPath.size < 2) return null
        // 전원이 같은 점수면 기울일 것이 없다 — null을 내어 **종전 추첨 그대로**가 되게 한다.
        // 여기서 전부 1.0인 표를 내면 뽑는 방법이 나머지 연산에서 누적합으로 갈려,
        // 같은 시드가 다른 그림을 고른다(대결을 안 한 캐릭터의 그림이 이유 없이 바뀐다).
        val top = scoreByPath.values.max()
        if (scoreByPath.values.all { it == top }) return null

        val out = LinkedHashMap<String, Double>(scoreByPath.size)
        for ((key, score) in scoreByPath) {
            val delta = (score - top).toDouble().coerceAtLeast(-SPREAD_CAP)
            out[key] = 10.0.pow(strength.alpha * delta / DuelRating.SCALE)
        }
        return out
    }
}
