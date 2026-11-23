package com.novelcharacter.app.pcbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 껍데기 HTML이 **자산 주소에 토큰을 싣는가** — 실사용에서 물린 자리를 잠근다 (2026.11.23).
 *
 * 종전에는 `href="app.css"`라 브라우저가 질의 없이 자산을 불렀고, 모든 요청에 토큰을
 * 요구하는 서버가 **자산만 401로 막았다.** 증상이 고약했다 — 껍데기는 그대로 오므로
 * 화면이 뜨고 글자도 보이는데 **스타일이 없고 아무것도 동작하지 않았다.**
 *
 * 컴파일로는 잡히지 않고(글자 하나의 문제다) 화면을 띄워야만 보이는 부류라, 그 자리를
 * 기계가 지킨다.
 */
class BridgeWebTest {

    private val token = "g7amvRJ78Ya6mQtgqZJ5QK6UUtoBLCAx"

    @Test
    fun shell_putsTokenOnStylesheet() {
        assertTrue(BridgeWeb.html(token).contains("""href="app.css?t=$token""""))
    }

    @Test
    fun shell_putsTokenOnScript() {
        assertTrue(BridgeWeb.html(token).contains("""src="app.js?t=$token""""))
    }

    /** 토큰 없는 날 주소가 **하나도** 남아 있으면 안 된다 — 그 하나가 401이 된다. */
    @Test
    fun shell_hasNoTokenlessAssetReference() {
        val html = BridgeWeb.html(token)
        assertFalse(html.contains("""href="app.css""""))
        assertFalse(html.contains("""src="app.js""""))
    }

    /** 자리 글자가 그대로 남으면 토큰이 아니라 "TOKEN"을 보내게 된다. */
    @Test
    fun shell_replacesPlaceholderEntirely() {
        assertFalse(BridgeWeb.html(token).contains("?t=TOKEN"))
    }

    /** 토큰이 바뀌면 껍데기도 바뀐다 — 상수로 굳어 있지 않다. */
    @Test
    fun shell_reflectsTheGivenToken() {
        val a = BridgeWeb.html("aaaa")
        val b = BridgeWeb.html("bbbb")
        assertTrue(a.contains("?t=aaaa"))
        assertTrue(b.contains("?t=bbbb"))
        assertFalse(a.contains("?t=bbbb"))
    }

    /**
     * 자산 이름이 [Route]의 허용 목록과 같아야 한다 — 갈리면 껍데기가 404를 부른다.
     * 라우터는 글자로 가르므로 컴파일러가 이 짝을 보지 못한다.
     */
    @Test
    fun assetNames_matchTheRouter() {
        for (name in listOf("app.css", "app.js")) {
            assertTrue(
                "$name 이 껍데기에 없다",
                BridgeWeb.html(token).contains("$name?t=")
            )
            assertTrue(
                "$name 을 라우터가 모른다",
                Route.of("GET", "/$name") == Route.Asset(name)
            )
        }
    }

    @Test
    fun assets_areNotEmpty() {
        assertTrue(BridgeWeb.CSS.isNotBlank())
        assertTrue(BridgeWeb.JS.isNotBlank())
    }

    // ───────── 관계 화면 ─────────
    //
    // **여기서 잠그는 것은 "컴파일러가 보지 못하는 짝"이다.** 자바스크립트의 주소·사유 코드·
    // 관문 이름은 글자 상수이고, 서버 쪽 짝과 갈려도 컴파일은 통과한다 — 1단계가 자산
    // 주소에서 물린 그 부류이며, 증상은 *"화면은 뜨는데 그 자리만 조용히 안 되는"* 모양이다.

    /** 스크립트가 부르는 주소가 **라우터가 아는 길**이어야 한다. */
    @Test
    fun relationshipPaths_matchTheRouter() {
        assertTrue(
            "관계 목록 주소가 스크립트에 없다",
            BridgeWeb.JS.contains("'/api/relationships/'")
        )
        assertTrue(
            "관계 저장 주소가 스크립트에 없다",
            BridgeWeb.JS.contains("'/api/relationship/'")
        )
        // 스크립트가 짓는 꼴 그대로 라우터에 물어본다.
        assertTrue(Route.of("GET", "/api/relationships/7") == Route.RelationshipsGet(7))
        assertTrue(Route.of("POST", "/api/relationship/7") == Route.RelationshipAdd(7, ""))
        assertTrue(Route.of("POST", "/api/relationship/7/12") == Route.RelationshipUpdate(7, 12, ""))
    }

    /** 삭제 주소의 꼬리 글자가 라우터의 것과 같아야 한다 — 갈리면 삭제만 404가 된다. */
    @Test
    fun relationshipDeletePath_matchesTheRouter() {
        assertTrue(BridgeWeb.JS.contains("'/delete'"))
        assertTrue(
            Route.of("POST", "/api/relationship/7/12/delete") ==
                Route.RelationshipDelete(7, 12, "")
        )
    }

    /** 읽기 전용 사유 코드의 짝. 갈리면 **사유가 화면에 닿지 않는다.** */
    @Test
    fun relationshipNotices_matchTheDescriptor() {
        assertTrue(
            BridgeRelationships.NOTICE_FACTION + " 사유를 스크립트가 모른다",
            BridgeWeb.JS.contains("'" + BridgeRelationships.NOTICE_FACTION + "'")
        )
        assertTrue(
            BridgeRelationships.NOTICE_MISSING_OTHER + " 사유를 스크립트가 모른다",
            BridgeWeb.JS.contains("'" + BridgeRelationships.NOTICE_MISSING_OTHER + "'")
        )
    }

    /** 안내 사유 코드의 짝. 갈리면 **안내가 조용히 사라진다.** */
    @Test
    fun relationshipAdvisory_matchesTheDescriptor() {
        assertTrue(
            BridgeWeb.JS.contains("'" + BridgeRelationships.ADVISORY_CROSS_UNIVERSE + "'")
        )
    }

    /** 관문 이름의 짝. 갈리면 삭제 확인 창이 **뜨지 않고** 일반 실패로 접힌다. */
    @Test
    fun relationshipGateNames_matchTheServer() {
        assertTrue(
            BridgeWeb.JS.contains("'" + BridgeRelationships.GATE_DELETE + "'")
        )
        assertTrue(
            BridgeWeb.JS.contains(BridgeRelationships.RESOLVE_DELETE + ":")
        )
    }

    /** 본문 사유 코드(`what`)마다 화면 문구가 있어야 한다 — 없으면 코드가 그대로 보인다. */
    @Test
    fun relationshipWhatCodes_allHaveWording() {
        for (what in listOf("otherCharacterId", "type", "intensity", "json")) {
            assertTrue("$what 의 문구가 없다", BridgeWeb.JS.contains("$what:"))
        }
    }

    /**
     * **편집 폼에 너비 상한을 두지 않는다.**
     *
     * 1단계가 넓은 화면을 쓰자고 시작한 일에서 편집 폼에 900px를 박아 2000px 모니터의
     * 절반을 비웠다. 같은 자리를 되풀이하지 않게 기계가 본다 — CSS는 글자 상수라
     * 컴파일도 시험도 모양을 보지 못하지만, *상한을 적었는가*는 볼 수 있다.
     */
    @Test
    fun css_hasNoWidthCapOnTheEditor() {
        assertFalse(BridgeWeb.CSS.contains("max-width:900px"))
        for (rule in listOf("#editor", ".rel-form", ".rels", ".grid")) {
            val start = BridgeWeb.CSS.indexOf(rule)
            if (start < 0) continue
            val end = BridgeWeb.CSS.indexOf('}', start)
            val block = BridgeWeb.CSS.substring(start, if (end < 0) BridgeWeb.CSS.length else end)
            assertFalse("$rule 에 너비 상한이 붙었다", block.contains("max-width"))
        }
    }

    /**
     * 관계 줄이 **긴 이름에서 단추를 밀어내지 않는가.**
     *
     * flex 칸은 `min-width:0`이 없으면 내용보다 작아지지 않아, 상대 이름이 길면 단추가
     * 줄 밖으로 밀린다 — 1단계가 머리말 목록에서 겪은 그 자리다.
     */
    @Test
    fun css_relationshipRowCanShrinkItsName() {
        val start = BridgeWeb.CSS.indexOf(".rel .who")
        assertTrue(".rel .who 규칙이 없다", start >= 0)
        val block = BridgeWeb.CSS.substring(start, BridgeWeb.CSS.indexOf('}', start))
        assertTrue(block.contains("min-width:0"))
        assertTrue(block.contains("overflow-wrap:anywhere"))
    }
}
