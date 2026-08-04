package com.novelcharacter.app.util

import org.json.JSONArray

/**
 * **축과 필드를 잇는 것** (B-104 층 C — 사용자 확정 2-1의 *"필드 대조"*를 구현한다).
 *
 * 사용자 요청 원문(2026.08.04): *"대결 기능에서 이 값이 어느 필드와 연관있는지를 연결할 수
 * 있으면 좋겠어(대전의 승패에 영향을 주는 필드, 여러개도 가능)"* 그리고 이어서
 * *"영향 필드가 여럿이면 순위를 정해도 좋겠다. … 영향력과 상관없이 이 대전의 결과가 이
 * 필드로 이어진다는 또 다른 거니까 산출 필드는 따로 둬야지."*
 *
 * ## 연결이 둘인 것은 방향이 반대이기 때문이다
 *
 * | | 무엇 | 방향 | 대결 화면에 보이는가 |
 * |---|---|---|---|
 * | **영향 필드**([Axis.influences]) | 승패를 **가르는 재료** — 마력량·속성·소속 | 필드 → 대결 | **보인다** |
 * | **산출 필드**([Axis.outcomes]) | 대결이 **만들어 내는 값** — 강함 | 대결 → 필드 | **보이지 않는다** |
 *
 * **산출 필드를 대결 화면에 띄우지 않는 것은 그것이 곧 답이기 때문이다.** *"누가 더 센가"*를
 * 물으면서 '강함' 필드값을 함께 보이면 물음과 답을 같은 화면에 놓는 셈이고, 그러면 대결이
 * 재는 것이 창작자의 감각이 아니라 이미 적힌 숫자가 된다 — 설계 5장 ①이 **점수**를 두고
 * 금지한 것과 정확히 같은 부류다. 반대로 영향 필드는 **판단 재료**라 보여야 한다:
 * 마력량과 속성을 보고 고르는 것이 사용자가 실제로 하는 일이다.
 *
 * ## 순위는 사전식으로 읽는다
 * [Axis.influences]의 **순서가 곧 영향력 순위**다(1순위가 맨 앞). 예측은 1순위에서 갈리면
 * 거기서 끝나고, 비기면 다음 순위로 넘어간다([predict]) — *"마력량이 같으면 속성으로 가른다"*가
 * 사용자가 쓴 말 그대로의 뜻이다. 가중합으로 뭉개지 않는 것은 **가중치가 근거 없는 숫자**이기
 * 때문이다: 1순위가 2순위의 몇 배인지는 아무도 모르고, 정하면 그 숫자가 결과를 지어낸다.
 *
 * ## 견줄 수 없는 값은 견주지 않는다 (개발 의도 2번)
 * 속성·소속처럼 **차례가 없는 값**은 크고 작음을 말할 수 없다. 그런 필드는 [Side.UNKNOWN]을
 * 내고 예측에서 빠지며, **표시에서는 그대로 보인다** — 사람은 그것을 보고 판단할 수 있다.
 * 숫자로 읽히지 않는 값을 억지로 정렬하면 앱이 지어낸 서열이 사용자의 판단을 밀어낸다.
 */
object DuelFieldLinks {

    /**
     * 축에 걸린 필드 하나.
     *
     * @property key `FieldDefinition.key`. **id가 아니다** — 엑셀 왕복과 백업 복원이 필드를
     *   지웠다 다시 만들 수 있고 그때 id가 재발급된다(R-1의 오배정과 같은 자리). 키는
     *   세계관 안에서 유일하고 사람이 읽을 수 있어 외부에서 편집한 파일에서도 성립한다.
     * @property higherWins 값이 **클수록** 유리한가. false면 작을수록 유리하다
     *   (예: '나이'가 어릴수록 유리한 축).
     */
    data class Link(val key: String, val higherWins: Boolean = true)

    /**
     * 한 축의 연결 전부 — 저장 형식 둘을 해석한 결과.
     *
     * @property influences 승패에 영향을 주는 필드. **순서가 영향력 순위다.**
     * @property outcomes 대결 결과가 이어지는 필드. 보통 하나지만 열어 둔다(원칙 01).
     */
    data class Axis(
        val influences: List<Link> = emptyList(),
        val outcomes: List<Link> = emptyList()
    ) {
        val hasAny: Boolean get() = influences.isNotEmpty() || outcomes.isNotEmpty()

        /** 같은 필드가 양쪽에 걸린 자리 — 재료이면서 결과일 수는 없다([conflicts]가 근거). */
        val conflicts: List<String>
            get() {
                val outs = outcomes.map { it.key }.toSet()
                return influences.map { it.key }.filter { it in outs }.distinct()
            }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 저장 형식 — JSON 배열(DB)과 사람이 적는 글(엑셀)
    // ──────────────────────────────────────────────────────────────────────

    /** 작을수록 유리함을 나타내는 앞머리. 엑셀에서 사람이 직접 적을 수 있어야 해 한 글자로 뒀다. */
    private const val LOWER_WINS_PREFIX = "-"

    /**
     * 저장 형식으로. **JSON 배열인 것은 [DuelRecords.encodeMembers]와 같은 근거다** —
     * 구분자를 정해 이어 붙이면 그 구분자를 담은 값에서 깨진다.
     */
    fun encode(links: List<Link>): String =
        JSONArray().apply { normalize(links).forEach { put(token(it)) } }.toString()

    /** 저장 형식에서. 깨진 값은 빈 목록이다 — 외부에서 편집된 파일도 앱을 죽이지 않는다. */
    fun decode(json: String?): List<Link> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            normalize((0 until array.length()).mapNotNull { i ->
                array.optString(i, "").takeIf { it.isNotEmpty() }?.let { parseToken(it) }
            })
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 엑셀 한 칸에서. 쉼표로 나누고 앞머리 `-`는 *작을수록 유리*로 읽는다.
     * **순서가 그대로 영향력 순위**라 사람이 적은 차례를 지킨다.
     */
    fun parseText(text: String?): List<Link> {
        if (text.isNullOrBlank()) return emptyList()
        return normalize(text.split(',', '\n').mapNotNull { parseToken(it) })
    }

    /** 엑셀 한 칸으로. [parseText]가 그대로 되읽을 수 있는 모양이다(왕복 무결성). */
    fun toText(links: List<Link>): String =
        normalize(links).joinToString(", ") { token(it) }

    private fun token(link: Link): String =
        if (link.higherWins) link.key else LOWER_WINS_PREFIX + link.key

    private fun parseToken(raw: String): Link? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.startsWith(LOWER_WINS_PREFIX)
        val key = (if (lower) trimmed.removePrefix(LOWER_WINS_PREFIX) else trimmed).trim()
        return if (key.isEmpty()) null else Link(key, higherWins = !lower)
    }

    /**
     * 같은 필드가 두 번 들어오면 **앞의 것만** 남긴다 — 순위가 둘일 수는 없고,
     * 뒤의 것을 살리면 사용자가 정한 차례가 조용히 뒤집힌다.
     */
    private fun normalize(links: List<Link>): List<Link> {
        val seen = LinkedHashMap<String, Link>()
        for (link in links) if (link.key.isNotEmpty()) seen.putIfAbsent(link.key, link)
        return seen.values.toList()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 값 견주기
    // ──────────────────────────────────────────────────────────────────────

    /** 한 필드가 가리키는 쪽. */
    enum class Side {
        A, B,

        /** 같은 값 — 이 필드로는 갈리지 않는다. 다음 순위로 넘어간다. */
        TIE,

        /** 견줄 수 없다 — 값이 비었거나 차례가 없는 값(속성·소속 등)이다. */
        UNKNOWN
    }

    /**
     * 값에서 수를 읽는다. **맨 앞에서 시작하는 수만** 인정한다.
     *
     * `"3.5cm"`는 3.5이고 `"1,200"`은 1200이지만 `"S-1"`·`"불속성"`은 **읽지 않는다** —
     * 아무 데서나 수를 주워 오면 등급 이름 `S-1`이 −1로 둔갑해 서열이 뒤집힌다.
     */
    fun numberOf(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(",", "")
        val match = LEADING_NUMBER.find(cleaned) ?: return null
        return match.value.toDoubleOrNull()
    }

    private val LEADING_NUMBER = Regex("^[+-]?\\d+(?:\\.\\d+)?")

    /** 이 필드 하나가 가리키는 쪽. 둘 다 수로 읽혀야 갈린다. */
    fun compareOne(link: Link, aValue: String?, bValue: String?): Side {
        val a = numberOf(aValue) ?: return Side.UNKNOWN
        val b = numberOf(bValue) ?: return Side.UNKNOWN
        if (a == b) return Side.TIE
        val aHigher = a > b
        return if (aHigher == link.higherWins) Side.A else Side.B
    }

    /**
     * 영향 필드가 가리키는 쪽 — **사전식**이다.
     *
     * @property side 어느 쪽이 유리한가.
     * @property decidedBy 갈린 필드의 키. 아무 필드도 갈리지 못했으면 null.
     * @property rank 갈린 필드의 순위(1부터). 갈리지 않았으면 0.
     * @property comparable 수로 읽혀 실제로 견줄 수 있었던 필드 수 — *"필드를 걸었는데 아무
     *   말도 안 한다"*와 *"걸린 필드가 없다"*를 구별한다(개발 의도 2번).
     */
    data class Prediction(
        val side: Side,
        val decidedBy: String? = null,
        val rank: Int = 0,
        val comparable: Int = 0
    )

    /**
     * 영향 필드 목록으로 한 짝의 우열을 점친다.
     *
     * 1순위부터 훑어 **처음으로 갈리는 필드**에서 멈춘다. 비기면(TIE) 다음 순위로 넘어가고,
     * 견줄 수 없으면(UNKNOWN) 그 필드는 없는 셈 치고 넘어간다 — **빼는 것이 아니라 넘어가는
     * 것**이라 뒤 순위가 여전히 말할 기회를 갖는다.
     */
    fun predict(
        influences: List<Link>,
        aValues: Map<String, String>,
        bValues: Map<String, String>
    ): Prediction {
        var comparable = 0
        influences.forEachIndexed { index, link ->
            when (val side = compareOne(link, aValues[link.key], bValues[link.key])) {
                Side.UNKNOWN -> Unit
                Side.TIE -> comparable++
                else -> return Prediction(side, link.key, index + 1, comparable + 1)
            }
        }
        return Prediction(Side.TIE.takeIf { comparable > 0 } ?: Side.UNKNOWN, null, 0, comparable)
    }

    /** 예측과 실제 결과가 맞는가. */
    enum class Agreement {
        /** 예측대로였다. */
        AGREE,

        /** **예측과 반대다** — 필드가 말하는 것과 사용자가 고른 것이 어긋났다. */
        DISAGREE,

        /** 예측은 갈렸는데 실제는 무승부이거나, 그 반대다. 어긋남으로 세지 않는다. */
        UNDECIDED,

        /** 점칠 수 없었다(걸린 필드가 없거나 값이 비었다). */
        UNKNOWN
    }

    /**
     * @param winner 실제로 이긴 쪽. null이면 무승부다.
     *
     * **어긋남으로 세는 것은 [Agreement.DISAGREE] 하나뿐이다.** 무승부와 판정 불가까지 세면
     * *"값을 안 적은 캐릭터"*가 어긋남으로 몰려, 정작 봐야 할 자리가 그 소음에 묻힌다.
     */
    fun agreementOf(prediction: Prediction, winner: Side?): Agreement = when {
        prediction.side == Side.UNKNOWN -> Agreement.UNKNOWN
        winner == null || winner == Side.TIE -> Agreement.UNDECIDED
        prediction.side == Side.TIE -> Agreement.UNDECIDED
        prediction.side == winner -> Agreement.AGREE
        else -> Agreement.DISAGREE
    }
}
