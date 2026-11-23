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
}
