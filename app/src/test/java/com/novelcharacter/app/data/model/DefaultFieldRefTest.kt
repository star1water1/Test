package com.novelcharacter.app.data.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 전역 기본 필드의 **연결 표식**(B-119) — config 키 하나의 읽기·쓰기·걷어내기.
 *
 * 이 표식은 심긴 필드가 원본 템플릿을 가리키는 **유일한 끈**이다. 끊어지면 전파도 강등도
 * 대상을 못 찾고, 그 고장은 조용하다(필드는 멀쩡히 동작한다). 그래서 잠근다.
 */
class DefaultFieldRefTest {

    @Test fun 표식이_없으면_연결이_없다() {
        assertNull(DefaultFieldRef.codeFromConfig("{}"))
        assertNull(DefaultFieldRef.codeFromConfig("""{"options":["남","여"]}"""))
        assertFalse(DefaultFieldRef.isLinked("{}"))
    }

    @Test fun 표식을_읽는다() {
        assertEquals("DFT-1", DefaultFieldRef.codeFromConfig("""{"defaultField":"DFT-1"}"""))
        assertTrue(DefaultFieldRef.isLinked("""{"defaultField":"DFT-1"}"""))
    }

    /**
     * 엑셀 `기본필드코드` 열을 비운 채 들여온 파일이 `""`를 만들 수 있다. 그것은 연결이
     * 아니라 **해제**이며, 연결로 읽으면 화면은 배지를 그리는데 전파는 대상으로 세지 않는다.
     */
    @Test fun 빈_표식은_연결이_아니다() {
        assertNull(DefaultFieldRef.codeFromConfig("""{"defaultField":""}"""))
        assertNull(DefaultFieldRef.codeFromConfig("""{"defaultField":"   "}"""))
    }

    @Test fun 손상된_JSON은_연결이_없는_것으로_읽는다() {
        assertNull(DefaultFieldRef.codeFromConfig("{이건 JSON이 아니다"))
        assertNull(DefaultFieldRef.codeFromConfig(""))
    }

    @Test fun 표식을_적어도_다른_키는_남는다() {
        val written = DefaultFieldRef.write("""{"options":["남","여"],"aiSuggest":true}""", "DFT-9")
        val json = JSONObject(written)
        assertEquals("DFT-9", json.getString("defaultField"))
        assertEquals(2, json.getJSONArray("options").length())
        assertTrue(json.getBoolean("aiSuggest"))
    }

    @Test fun 빈_config에도_적을_수_있다() {
        assertEquals("DFT-1", DefaultFieldRef.codeFromConfig(DefaultFieldRef.write("", "DFT-1")))
        assertEquals("DFT-1", DefaultFieldRef.codeFromConfig(DefaultFieldRef.write("{}", "DFT-1")))
    }

    /** 빈 code로 적는 것은 해제와 같다 — 반쯤 연결된 상태를 만들지 않는다. */
    @Test fun 빈_code를_적으면_해제된다() {
        val linked = """{"defaultField":"DFT-1","options":[]}"""
        val written = DefaultFieldRef.write(linked, "")
        assertNull(DefaultFieldRef.codeFromConfig(written))
        assertTrue(JSONObject(written).has("options"))
    }

    @Test fun 강등은_표식만_걷어내고_나머지는_남긴다() {
        val demoted = DefaultFieldRef.remove("""{"defaultField":"DFT-1","options":["남"],"grades":{"C":1}}""")
        val json = JSONObject(demoted)
        assertFalse(json.has("defaultField"))
        assertEquals(1, json.getJSONArray("options").length())
        assertTrue(json.has("grades"))
    }

    /**
     * **지울 키가 없으면 원문 그대로**(DuelGradeRef.remove와 같은 규칙).
     *
     * 재직렬화는 키 순서를 흔들어 무관한 config까지 *"바뀐 것"*으로 만든다 — 전파 미리보기의
     * 다름 판정이 그 비교 위에 서 있으므로, 여기서 문자열이 흔들리면 **손대지 않은 필드가
     * 전부 '다름'으로 떠서 덮어쓰기를 권하게 된다.**
     */
    @Test fun 지울_표식이_없으면_원문_문자열_그대로다() {
        val original = """{"b":2,"a":1}"""
        assertSame(original, DefaultFieldRef.remove(original))
    }

    @Test fun 손상된_JSON은_강등해도_원문_그대로다() {
        val broken = "{깨진 것"
        assertSame(broken, DefaultFieldRef.remove(broken))
        assertEquals(broken, DefaultFieldRef.write(broken, "DFT-1"))
    }

    /** 적기 → 걷어내기 왕복이 뜻을 보존한다. */
    @Test fun 적고_걷어내면_뜻이_돌아온다() {
        val original = """{"options":["남","여"]}"""
        val roundTrip = DefaultFieldRef.remove(DefaultFieldRef.write(original, "DFT-1"))
        assertEquals(
            JSONObject(original).getJSONArray("options").toString(),
            JSONObject(roundTrip).getJSONArray("options").toString()
        )
        assertNull(DefaultFieldRef.codeFromConfig(roundTrip))
    }

    /**
     * **이 참조는 세계관 경계에서 걷어내지 않는다** — [FieldConfigTransfer]가 걷어내는 둘과
     * 전제가 다르다(템플릿은 전역이라 경계 안에서만 뜻이 있는 참조가 아니다).
     *
     * 이 시험이 그 사실을 잠근다: 누군가 `defaultField`를 강등 목록에 넣으면 여기서 깨진다.
     * 넣으면 다른 세계관으로 복사된 필드만 기본 필드가 아닌 것처럼 보이게 된다.
     */
    @Test fun 세계관_경계_강등은_이_표식을_건드리지_않는다() {
        val config = """{"defaultField":"DFT-1","gradeSystem":"GS-1","duelGrade":{"axisCode":"DAX-1"}}"""
        val demoted = FieldConfigTransfer.demoteAcrossUniverse(config)
        val json = JSONObject(demoted)
        assertEquals("DFT-1", json.getString("defaultField"))
        assertFalse("세계관 한정 참조는 걷힌다", json.has("gradeSystem"))
        assertFalse("세계관 한정 참조는 걷힌다", json.has("duelGrade"))
    }
}
