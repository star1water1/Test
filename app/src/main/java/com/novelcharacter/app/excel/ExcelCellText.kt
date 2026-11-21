package com.novelcharacter.app.excel

/**
 * **셀 글자의 쓰기·읽기 한 쌍** — 엑셀 파일 형식의 `_xHHHH_` 표기 규약(ECMA-376 `ST_Xstring`).
 *
 * ## 무엇이 깨지고 있었나 (2026.11.21 실측)
 *
 * ① **XML에 실을 수 없는 글자가 `?`로 바뀌었다.** `\u0000`~`\u0008`·`\u000B`·`\u000C`·
 *   `\u000E`~`\u001F`·`￾`·`￿`·짝 잃은 서러게이트는 XML 1.0 문서에 담을 수 없다.
 *   POI는 그 글자를 던지지 않고 **`?`로 바꿔 쓴다** — 일반(XSSF)·스트리밍(SXSSF) 내보내기
 *   모두다. 앱은 입력에서 이 글자를 거르지 않으므로 붙여넣기로 들어온다(Word의 수동 줄바꿈이
 *   `\u000B`다). 그 파일을 다시 들이면 앱의 값이 `?`로 덮이고, **자동 백업도 같은 경로다.**
 *
 * ② **사용자가 적은 `_xHHHH_` 꼴 글자가 다른 글자가 됐다.** 형식은 `_x0041_`을 `A`로 읽는
 *   규약이라, 그 꼴을 그대로 쓰면 `user_xcafe_`가 한자 하나가 된다. 게다가 앱의 두 읽기 경로가
 *   **다르게** 읽었다 — DOM은 해석하고, 스트리밍은 인라인 문자열과 수식 결과를 해석하지
 *   않았다. 같은 파일이 **크기에 따라** 다른 값으로 들어왔다.
 *
 * ## 처분 — 엑셀 자신이 쓰는 규약을 따른다
 *
 * - 쓰기([encode]): 실을 수 없는 글자는 `_xHHHH_`로, **`_` 다음이 `x`+16진 넷이면 그 `_`를
 *   `_x005F_`로** 적는다. 엑셀·LibreOffice가 같은 규약으로 읽으므로 외부 편집기에서도 원래
 *   글자로 보인다.
 * - 읽기([decode]): POI의 해석과 **글자 단위로 같다**(`_x`+16진 넷+`_`를 왼쪽부터 겹치지 않게
 *   한 번 훑는다 — 대소문자 16진 모두, `_X`는 아니다). DOM 경로는 POI가, 스트리밍 경로는 이
 *   함수가 해석한다. 시험이 POI의 해석과 무작위 대조한다.
 *
 * ## 왜 닫는 `_`를 보지 않고 이스케이프하는가
 *
 * `_`가 `x`+16진 넷+`_` 앞일 때만 이스케이프하면 반례가 있다: `_x0041` 뒤에 실을 수 없는 글자가
 * 오면 그 글자의 표기 `_x0001_`가 `_`로 시작해 **앞의 `_x0041`과 이어져** `_x0041_`이 된다 —
 * 읽으면 `A`다. 그래서 `_`+`x`+16진 넷이면 무조건 이스케이프한다(`_x005F_`는 언제나 `_`로
 * 읽히므로 더 이스케이프해도 값은 안 바뀐다). 이 규칙 아래에서 해석기가 찾는 표기는 **쓰기가
 * 적은 표기뿐**이다: 날 `_` 뒤에는 `x`+16진 넷이 올 수 없고(올 자리면 이스케이프했다), 표기의
 * 닫는 `_`는 자기 표기가 먼저 먹는다.
 *
 * ## 시트 이름은 다르다
 *
 * 시트 이름에는 이 표기가 없다 — 그래서 실을 수 없는 글자는 **뺀다**([stripXmlIllegal]).
 * `sanitizeSheetNameBase`가 이미 대괄호·별표·빗금·물음표·쌍점을 빼는 것과 같은 처분이다.
 */
object ExcelCellText {

    /** [encodeWithin]의 결과 — [truncated]면 한도에 걸려 뒤가 잘렸다(호출부가 센다). */
    data class Encoded(val text: String, val truncated: Boolean)

    /** XML 1.0에 실을 수 없는 UTF-16 단위인가 — 서러게이트는 짝을 따로 본다([encodeWithin]). */
    private fun isIllegalUnit(c: Char): Boolean = when {
        c == '\t' || c == '\n' || c == '\r' -> false
        c < ' ' -> true
        c == '￾' || c == '￿' -> true
        else -> false
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** [value]의 [i]가 이스케이프해야 할 `_`인가 — 뒤가 `x`+16진 넷(닫는 `_`는 보지 않는다, KDoc). */
    private fun isEscapableUnderscore(value: String, i: Int): Boolean =
        value[i] == '_' && i + 5 < value.length && value[i + 1] == 'x' &&
            isHex(value[i + 2]) && isHex(value[i + 3]) && isHex(value[i + 4]) && isHex(value[i + 5])

    private fun escapeOf(code: Int): String = "_x" + String.format(java.util.Locale.ROOT, "%04X", code) + "_"

    private const val ESCAPED_UNDERSCORE = "_x005F_"

    /** 바꿔 쓸 것이 있는가 — 없으면 쓰기는 원문 그대로다(흔한 모양의 비용 0). */
    fun needsEncoding(value: String): Boolean {
        for (i in value.indices) {
            val c = value[i]
            if (isIllegalUnit(c)) return true
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return true
            } else if (Character.isLowSurrogate(c)) {
                if (i == 0 || !Character.isHighSurrogate(value[i - 1])) return true
            } else if (c == '_' && isEscapableUnderscore(value, i)) {
                return true
            }
        }
        return false
    }

    /** 셀에 적을 글자 — 길이 상한 없음. 머리글·안내처럼 짧은 것이 확실한 자리용이다. */
    fun encode(value: String): String = encodeWithin(value, Int.MAX_VALUE).text

    /**
     * 셀에 적을 글자를 **적은 뒤의 길이로** [limit] 안에 맞춘다.
     *
     * 한도는 파일에 실리는 글자 수다(POI가 그 길이로 거절한다) — 원문이 한도 안이어도 표기가
     * 늘면 넘을 수 있다. 자를 때는 **서러게이트 쌍과 `_xHHHH_` 표기를 쪼개지 않는다**(반쪽
     * 표기는 다른 글자로 읽힌다). 바꿔 쓸 것이 없으면 종전 절단(`truncateForCell`)과 같다.
     */
    fun encodeWithin(value: String, limit: Int): Encoded {
        if (!needsEncoding(value)) {
            return if (value.length <= limit) Encoded(value, false)
            else Encoded(truncateForCell(value, limit), true)
        }
        val sb = StringBuilder(value.length + 16)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val token: String
            val step: Int
            if (Character.isHighSurrogate(c) && i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                token = value.substring(i, i + 2); step = 2
            } else if (Character.isSurrogate(c) || isIllegalUnit(c)) {
                token = escapeOf(c.code); step = 1
            } else if (c == '_' && isEscapableUnderscore(value, i)) {
                token = ESCAPED_UNDERSCORE; step = 1
            } else {
                token = c.toString(); step = 1
            }
            if (sb.length + token.length > limit) return Encoded(sb.toString(), true)
            sb.append(token)
            i += step
        }
        return Encoded(sb.toString(), false)
    }

    private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

    /** 파일의 글자를 앱의 값으로 — POI(DOM 경로)의 해석과 같다. */
    fun decode(value: String): String {
        if (value.indexOf("_x") < 0) return value
        return ESCAPE.replace(value) { m -> m.groupValues[1].toInt(16).toChar().toString() }
    }

    /**
     * 표기 규약이 없는 자리(시트 이름)를 위한 처분 — 실을 수 없는 글자를 뺀다.
     * 짝 잃은 서러게이트도 뺀다(POI가 `?`로 바꿔, 앱이 배정한 이름과 파일 속 이름이 갈린다).
     */
    fun stripXmlIllegal(value: String): String {
        if (!needsIllegalStrip(value)) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c) && i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                sb.append(c).append(value[i + 1]); i += 2; continue
            }
            if (!Character.isSurrogate(c) && !isIllegalUnit(c)) sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun needsIllegalStrip(value: String): Boolean {
        for (i in value.indices) {
            val c = value[i]
            if (isIllegalUnit(c)) return true
            if (Character.isHighSurrogate(c) && (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1]))) return true
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(value[i - 1]))) return true
        }
        return false
    }
}
