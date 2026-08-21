package com.novelcharacter.app.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JSON 문자열 읽기의 계약 — **JSON `null`은 '없음'이다.**
 *
 * ## 이 시험이 보증하는 것과 못 하는 것
 *
 * 이 하네스는 **참조 구현**(`org.json:json`)을 싣는다. 그 구현에서는 문제의 `optString`도
 * `""`를 돌려주므로, **이 시험은 "기기에서 `"null"`이 나오는 것"을 재현하지 못한다** —
 * 재현하려면 안드로이드 libcore가 필요하고 그것은 여기 없다.
 *
 * **그래서 두 겹으로 막는다:**
 * 1. 여기서는 [stringOr]·[stringOrNull]의 **계약**을 못박는다 — 없음·`null`·빈 문자열·값이
 *    각각 무엇이 되는가. 이 계약은 두 구현에서 같다(`isNull`이 양쪽 다 *없음*과 *`null`*을
 *    함께 참으로 답하기 때문이다).
 * 2. `optString`을 **직접 부르는 것 자체**는 `tools/check_platform_api_parity.sh`가 막는다.
 *    기기 전용 동작은 실행으로 못 보므로 그쪽은 소스를 본다.
 *
 * 즉 *"어떤 값이어야 하는가"*는 여기서, *"그 통로만 쓰는가"*는 검사에서 지킨다.
 */
class JsonTextTest {

    @Test fun jsonNullReadsAsAbsentNotAsTheFourLetterWord() {
        val o = JSONObject("""{"a":null}""")
        // 기기(libcore)의 optString은 여기서 "null"이라는 **네 글자**를 준다 — 그것이 이 통로가 생긴 이유다.
        assertEquals("", o.stringOr("a"))
        assertNull(o.stringOrNull("a"))
    }

    @Test fun absentKeyIsTheSameAsJsonNull() {
        val o = JSONObject("""{"a":null}""")
        assertEquals(o.stringOr("a"), o.stringOr("없는키"))
        assertEquals(o.stringOrNull("a"), o.stringOrNull("없는키"))
    }

    @Test fun presentValuesComeThroughUnchanged() {
        val o = JSONObject("""{"s":"값","n":7,"b":true,"empty":""}""")
        assertEquals("값", o.stringOr("s"))
        assertEquals("7", o.stringOr("n"))
        assertEquals("true", o.stringOr("b"))
        // **빈 문자열은 '없음'이 아니다** — 적힌 것과 안 적힌 것을 갈라야 하는 자리가 있다.
        assertEquals("", o.stringOr("empty"))
        assertEquals("", o.stringOrNull("empty"))
    }

    @Test fun fallbackIsUsedOnlyWhenThereIsNothingThere() {
        val o = JSONObject("""{"a":null,"b":"값","c":""}""")
        assertEquals("기본", o.stringOr("a", "기본"))
        assertEquals("기본", o.stringOr("없는키", "기본"))
        assertEquals("값", o.stringOr("b", "기본"))
        // 빈 문자열이 적혀 있으면 기본값으로 덮지 않는다 — 사용자가 지운 것일 수 있다.
        assertEquals("", o.stringOr("c", "기본"))
    }

    @Test fun arrayElementsFollowTheSameRule() {
        val a = JSONArray("""["실내",null,"","야외"]""")
        assertEquals("실내", a.stringOr(0))
        // 기기에서는 이 자리가 "null"이 되어 **`null`이라는 이름의 태그**가 붙었다.
        assertEquals("", a.stringOr(1))
        assertEquals("", a.stringOr(2))
        assertEquals("야외", a.stringOr(3))
        assertEquals("기본", a.stringOr(1, "기본"))
        // 범위 밖도 '없음'이다
        assertEquals("", a.stringOr(99))
    }

    @Test fun theImageTagPathDropsNullInsteadOfNamingATagNull() {
        // ImageBatchTagSuggester가 실제로 하는 일: 태그 배열을 훑어 빈 것을 버린다.
        val tags = JSONArray("""["실내",null,"야외"]""")
        val picked = (0 until tags.length())
            .map { tags.stringOr(it).trim() }
            .filter { it.isNotEmpty() }
        assertEquals(listOf("실내", "야외"), picked)
    }
}
