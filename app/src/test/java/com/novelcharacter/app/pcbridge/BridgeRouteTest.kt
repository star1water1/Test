package com.novelcharacter.app.pcbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeRouteTest {

    @Test
    fun root_isShell() {
        assertEquals(Route.Shell, Route.of("GET", "/"))
        assertEquals(Route.Shell, Route.of("get", ""))
    }

    @Test
    fun shell_onlyByGet() {
        assertEquals(Route.Unknown, Route.of("POST", "/"))
    }

    @Test
    fun assets_areAllowListed() {
        assertEquals(Route.Asset("app.css"), Route.of("GET", "/app.css"))
        assertEquals(Route.Asset("app.js"), Route.of("GET", "/app.js"))
        // 목록 밖은 없는 길이다 — 경로 탈출을 시도해도 조각이 하나가 아니다.
        assertEquals(Route.Unknown, Route.of("GET", "/secret.txt"))
        assertEquals(Route.Unknown, Route.of("GET", "/../AndroidManifest.xml"))
    }

    @Test
    fun novels() {
        assertEquals(Route.Novels, Route.of("GET", "/api/novels"))
        assertEquals(Route.Unknown, Route.of("POST", "/api/novels"))
    }

    /** 질의가 비면 "전체"다. `?novel=`만 남겨도 없는 길이 되지 않는다. */
    @Test
    fun characters_blankNovelMeansAll() {
        assertEquals(Route.Characters(null), Route.of("GET", "/api/characters"))
        assertEquals(Route.Characters(null), Route.of("GET", "/api/characters", mapOf("novel" to "")))
        assertEquals(Route.Characters(null), Route.of("GET", "/api/characters", mapOf("novel" to "   ")))
    }

    @Test
    fun characters_withNovel() {
        assertEquals(Route.Characters(12L), Route.of("GET", "/api/characters", mapOf("novel" to "12")))
    }

    /** 적혀 있는데 숫자가 아닌 것은 **틀린 인자**이고 없는 길과 다르다 (R-17). */
    @Test
    fun characters_badNovelIsBadRequest() {
        assertEquals(Route.BadRequest("novel"), Route.of("GET", "/api/characters", mapOf("novel" to "abc")))
        assertEquals(Route.BadRequest("novel"), Route.of("GET", "/api/characters", mapOf("novel" to "0")))
        assertEquals(Route.BadRequest("novel"), Route.of("GET", "/api/characters", mapOf("novel" to "-3")))
    }

    @Test
    fun characterGetAndSave() {
        assertEquals(Route.CharacterGet(5L), Route.of("GET", "/api/character/5"))
        assertEquals(Route.CharacterSave(5L, "{}"), Route.of("POST", "/api/character/5", emptyMap(), "{}"))
        assertEquals(Route.Unknown, Route.of("DELETE", "/api/character/5"))
    }

    @Test
    fun characterId_mustBePositive() {
        assertEquals(Route.BadRequest("characterId"), Route.of("GET", "/api/character/0"))
        assertEquals(Route.BadRequest("characterId"), Route.of("GET", "/api/character/-1"))
        assertEquals(Route.BadRequest("characterId"), Route.of("GET", "/api/character/x"))
    }

    @Test
    fun supplementNext() {
        assertEquals(Route.SupplementNext(null, null), Route.of("GET", "/api/supplement/next"))
        assertEquals(
            Route.SupplementNext(3L, 77L),
            Route.of("GET", "/api/supplement/next", mapOf("novel" to "3", "after" to "77"))
        )
        assertEquals(
            Route.BadRequest("after"),
            Route.of("GET", "/api/supplement/next", mapOf("after" to "nope"))
        )
    }

    /** 이미지 id는 **캐릭터 범위 안에서만** 풀린다 — 조각 둘짜리 옛 꼴은 없는 길이다. */
    @Test
    fun image_isScopedToCharacter() {
        assertEquals(Route.Image(9L, "deadbeef"), Route.of("GET", "/api/image/9/deadbeef"))
        assertEquals(Route.Unknown, Route.of("GET", "/api/image/deadbeef"))
        assertEquals(Route.BadRequest("characterId"), Route.of("GET", "/api/image/x/deadbeef"))
    }

    @Test
    fun unknownApiBranch() {
        assertEquals(Route.Unknown, Route.of("GET", "/api"))
        assertEquals(Route.Unknown, Route.of("GET", "/api/nope"))
        assertEquals(Route.Unknown, Route.of("GET", "/notapi/novels"))
    }

    /** 앞뒤 슬래시는 같게 읽는다 — 브라우저·프록시가 붙이고 떼는 자리다. */
    @Test
    fun slashesAreNormalized() {
        assertEquals(Route.Novels, Route.of("GET", "api/novels"))
        assertEquals(Route.Novels, Route.of("GET", "/api/novels/"))
        assertEquals(Route.Novels, Route.of("GET", "//api//novels//"))
    }
}
