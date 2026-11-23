package com.novelcharacter.app.pcbridge

/**
 * 요청 경로 → **동작** (순수 계층 — Android·서버 라이브러리 비의존).
 *
 * ## 왜 서버 라이브러리 밖에 두는가
 *
 * 이 파일이 `NanoHTTPD.IHTTPSession`을 받으면 **라우팅 판정을 시험할 때마다 서버 라이브러리가
 * 필요해지고**, 그러면 순수 JVM 하네스(`tools/setup_jvm_env.sh`의 jar 목록)에 의존성 하나가
 * 늘어난다. 하네스에 들어간 jar은 CI와 모든 작업자의 준비 단계에 영구히 붙는다.
 *
 * 그래서 **경계를 여기 둔다**: 서버 어댑터는 (메서드, 경로, 질의, 본문)이라는 네 글자열만
 * 꺼내 이 파일에 넘기고, 판정 결과를 받아 응답으로 옮긴다. 라우팅·인증·직렬화의 시험은
 * 전부 서버 없이 돈다.
 *
 * ## 알 수 없는 경로는 사유를 들고 거절한다
 *
 * [Route.Unknown]은 *"없는 길"*이고 [Route.BadRequest]는 *"있는 길인데 인자가 틀렸다"*다.
 * 두 가지를 404 하나로 접지 않는 것은 R-17과 같은 판단이다 — PC에서 주소를 손으로 고치다
 * 숫자를 빠뜨렸을 때 *"그런 화면이 없다"*고 답하면 사용자가 엉뚱한 곳을 본다.
 */
sealed class Route {

    /** 웹 껍데기(HTML). 토큰 질의를 들고 오는 첫 진입이다. */
    object Shell : Route()

    /** 정적 자산 — [name]은 이미 **허용 목록으로 걸러진 이름**이다(경로 탈출 불가). */
    data class Asset(val name: String) : Route()

    /** 작품 목록. 캐릭터 목록의 범위를 고르는 데 쓴다. */
    object Novels : Route()

    /** 캐릭터 목록. [novelId]가 null이면 전체, 있으면 그 작품으로 좁힌다. */
    data class Characters(val novelId: Long?) : Route()

    /** 캐릭터 하나의 편집 기술자(필드 정의 + 현재 값). */
    data class CharacterGet(val characterId: Long) : Route()

    /** 캐릭터 하나의 저장. 본문은 [body]에 날 글자로 실려 온다 — 파싱은 저장 계층이 한다. */
    data class CharacterSave(val characterId: Long, val body: String) : Route()

    /** 보충 모드의 다음 대상. [afterCharacterId]는 '저장하고 다음'이 넘기는 현재 자리다. */
    data class SupplementNext(val novelId: Long?, val afterCharacterId: Long?) : Route()

    /**
     * 이미지 바이트. [imageId]는 경로가 아니라 **불투명 id**다([BridgeImageRef]).
     *
     * **캐릭터 id를 함께 받는 것이 설계다.** id 하나만으로 열면 해석의 후보 집합이 *기기의
     * 모든 이미지*가 되고, 그러면 한 요청의 비용이 이미지 수에 비례하는 데다 범위가 사실상
     * 전역이 된다. 캐릭터로 묶으면 후보는 그 캐릭터의 `imagePaths`뿐이다 — 값싸고, id를
     * 찍어 맞혀도 그 캐릭터 밖은 열리지 않는다.
     */
    data class Image(val characterId: Long, val imageId: String) : Route()

    /** 없는 길. */
    object Unknown : Route()

    /** 있는 길인데 인자가 틀렸다. [what]은 사유 코드(화면 문구가 아니다). */
    data class BadRequest(val what: String) : Route()

    companion object {

        /** 서버가 내보내는 정적 자산 — **허용 목록이다.** 목록 밖 이름은 [Route.Unknown]이다. */
        private val ASSETS = setOf("app.css", "app.js")

        /**
         * 경로와 질의를 동작으로 옮긴다.
         *
         * @param method `GET`·`POST` 등. 대소문자는 무시한다.
         * @param path 질의를 뗀 경로. 앞의 `/`는 있어도 없어도 같게 읽는다.
         * @param query 질의 이름 → 값. 같은 이름이 여러 번 오면 호출부가 첫 값만 담는다.
         * @param body `POST` 본문의 날 글자. `GET`에서는 무시된다.
         */
        fun of(
            method: String,
            path: String,
            query: Map<String, String> = emptyMap(),
            body: String = ""
        ): Route {
            val m = method.trim().uppercase()
            val segs = path.trim().trim('/').split('/').filter { it.isNotEmpty() }

            if (segs.isEmpty()) return if (m == "GET") Shell else Unknown

            // 정적 자산은 api 밖의 한 조각이다.
            if (segs.size == 1 && segs[0] in ASSETS) {
                return if (m == "GET") Asset(segs[0]) else Unknown
            }

            if (segs[0] != "api") return Unknown
            val rest = segs.drop(1)
            if (rest.isEmpty()) return Unknown

            return when (rest[0]) {
                "novels" -> if (m == "GET" && rest.size == 1) Novels else Unknown

                "characters" -> if (m == "GET" && rest.size == 1) {
                    // 빈 질의는 "전체"이고, 적혀 있는데 숫자가 아닌 것은 틀린 인자다.
                    // 두 사실을 가르지 않으면 주소창에서 `?novel=`만 남겨도 "없는 길"이 된다.
                    if (query["novel"].isNullOrBlank()) Characters(null)
                    else Characters(optionalId(query["novel"]) ?: return BadRequest("novel"))
                } else Unknown

                "character" -> when {
                    rest.size != 2 -> Unknown
                    m == "GET" -> CharacterGet(requiredId(rest[1]) ?: return BadRequest("characterId"))
                    m == "POST" -> CharacterSave(
                        requiredId(rest[1]) ?: return BadRequest("characterId"), body
                    )
                    else -> Unknown
                }

                "supplement" -> if (m == "GET" && rest.size == 2 && rest[1] == "next") {
                    val novel = if (query["novel"].isNullOrBlank()) null
                    else optionalId(query["novel"]) ?: return BadRequest("novel")
                    val after = if (query["after"].isNullOrBlank()) null
                    else optionalId(query["after"]) ?: return BadRequest("after")
                    SupplementNext(novel, after)
                } else Unknown

                "image" -> if (m == "GET" && rest.size == 3) {
                    val cid = requiredId(rest[1]) ?: return BadRequest("characterId")
                    val id = rest[2].trim()
                    if (id.isEmpty()) BadRequest("imageId") else Image(cid, id)
                } else Unknown

                else -> Unknown
            }
        }

        /** 경로 조각의 id. 양수만 받는다 — 0·음수는 저장되지 않는 id라 요청으로 올 수 없다. */
        private fun requiredId(s: String): Long? = s.toLongOrNull()?.takeIf { it > 0 }

        /**
         * 질의의 id. **빈 값은 호출부가 먼저 가린다** — 이 함수가 null을 돌려주는 것은
         * *"적혀 있는데 숫자가 아니다"*라는 사실 하나뿐이고, 그것은 [Route.BadRequest]다.
         */
        private fun optionalId(s: String?): Long? = s?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }
}
