package com.novelcharacter.app.pcbridge

import com.novelcharacter.app.data.repository.AgeLinkageConflict
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * **요청 하나의 판정 전부** — 서버 라이브러리를 모르는 자리.
 *
 * ## 왜 이 파일이 있는가
 *
 * ktor 어댑터([BridgeServer])는 `tools/probe_compile.sh`의 `PCBRIDGE_EXCLUDE`에 있어
 * **지역 컴파일 증명이 없다**(ktor가 프로브 클래스패스에 없고, 넣으면 미해석 그림자가 진짜
 * 결함을 덮는다). 그 자리에 판정을 두면 증명 없는 코드가 늘어난다.
 *
 * 그래서 어댑터가 꺼낸 **글자 네 개**(메서드·경로·질의·본문)와 클라이언트 주소를 받아
 * [BridgeResponse]를 돌려주는 함수 하나로 모았다. 인증 차례 · 라우팅 · 상태 코드 · 관문의
 * 사유 꼴이 전부 여기 있고 전부 프로브가 본다. 어댑터에 남는 것은 *꺼내서 넘기고 받아
 * 적는* 일뿐이다.
 *
 * ## 인증을 먼저 보지 않는다 — 그물을 먼저 본다
 *
 * 차례가 판단이다. 토큰을 먼저 보면 **허용되지 않은 선에서 온 요청에도 토큰 대조가 일어나고**,
 * 그 응답 시간 차이가 곧 토큰을 때려 맞히는 쪽의 단서가 된다. 그물이 먼저 막으면 그 선에서는
 * 토큰에 관한 어떤 사실도 새지 않는다.
 */
internal object BridgeHandler {

    /** 어댑터가 그대로 옮길 응답. */
    sealed class BridgeResponse {
        data class Text(val status: Int, val contentType: String, val body: String) : BridgeResponse()
        data class Bytes(val status: Int, val contentType: String, val file: File) : BridgeResponse()
    }

    private const val JSON = "application/json; charset=utf-8"
    private const val HTML = "text/html; charset=utf-8"
    private const val CSS = "text/css; charset=utf-8"
    private const val JS = "application/javascript; charset=utf-8"

    /**
     * 요청 하나를 처리한다.
     *
     * @param clientIp 접속해 온 쪽의 주소. 어댑터가 소켓에서 꺼낸 그대로 넘긴다.
     * @param interfaces 지금 이 기기의 인터페이스. 어댑터가 [BridgeAddresses]로 매 요청 뜬다
     *   (캐시하면 케이블이 꽂히고 빠진 사실을 놓친다).
     */
    suspend fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String,
        clientIp: String?,
        presentedToken: String?,
        settings: BridgeSettingsStore.BridgeSettings,
        interfaces: List<BridgeAccessPolicy.LocalInterface>,
        data: BridgeData
    ): BridgeResponse {
        // ── ① 그물 ──
        when (val v = BridgeAccessPolicy.decide(clientIp, interfaces, settings.mode)) {
            is BridgeAccessPolicy.Verdict.Deny -> return denied(v.reason)
            is BridgeAccessPolicy.Verdict.Allow -> Unit
        }

        // ── ② 토큰 ──
        if (!BridgeToken.matches(settings.token, presentedToken)) {
            return json(401, JSONObject().put("message", "접속 주소가 올바르지 않습니다."))
        }

        // ── ③ 라우팅 ──
        return when (val route = Route.of(method, path, query, body)) {
            Route.Shell -> BridgeResponse.Text(200, HTML, BridgeWeb.html(settings.token))

            is Route.Asset -> when (route.name) {
                "app.css" -> BridgeResponse.Text(200, CSS, BridgeWeb.CSS)
                "app.js" -> BridgeResponse.Text(200, JS, BridgeWeb.JS)
                // 허용 목록과 이 분기가 갈리면 자산이 조용히 404가 된다 — enum이 아니라
                // 글자라 컴파일러가 못 보므로, 여기서 사유를 내어 눈에 띄게 한다.
                else -> json(500, JSONObject().put("message", "자산 이름이 서버와 맞지 않습니다."))
            }

            Route.Novels -> BridgeResponse.Text(200, JSON, data.novelsJson())

            is Route.Characters -> BridgeResponse.Text(200, JSON, data.charactersJson(route.novelId))

            is Route.CharacterGet -> {
                val j = data.characterJson(route.characterId)
                if (j == null) json(404, JSONObject().put("message", "그 캐릭터가 없습니다."))
                else BridgeResponse.Text(200, JSON, j)
            }

            is Route.CharacterSave -> saveResponse(data.save(route.characterId, route.body))

            is Route.SupplementNext ->
                BridgeResponse.Text(200, JSON, data.supplementNextJson(route.novelId, route.afterCharacterId))

            is Route.Image -> when (val r = data.image(route.characterId, route.imageId)) {
                is BridgeData.ImageOutcome.Found ->
                    BridgeResponse.Bytes(200, contentTypeOf(r.file.name), r.file)
                BridgeData.ImageOutcome.NotFound ->
                    json(404, JSONObject().put("message", "그 그림이 없습니다."))
                // **막힌 것은 없는 것과 다르다** (R-39 · R-17). 바이트는 나가지 않지만
                // 사유는 나간다 — 화면이 "사라졌다"로 읽지 않게 하는 것이 이 구분의 값이다.
                BridgeData.ImageOutcome.Blocked ->
                    json(403, JSONObject().put("message", "앱 저장소 밖에 있는 그림은 PC로 보내지 않습니다."))
            }

            is Route.BadRequest ->
                json(400, JSONObject().put("message", "요청에 적힌 값이 올바르지 않습니다.").put("what", route.what))

            Route.Unknown -> json(404, JSONObject().put("message", "그 주소는 없습니다."))
        }
    }

    /** 그물 거절. 사유마다 다른 문구를 낸다 — "왜 안 되는가"를 모르면 고칠 수 없다. */
    private fun denied(reason: BridgeAccessPolicy.Reason): BridgeResponse {
        val message = when (reason) {
            BridgeAccessPolicy.Reason.NOT_TETHERED ->
                "지금은 케이블로 연결한 PC만 받습니다. 와이파이로 쓰려면 앱 설정에서 바꾸십시오."
            BridgeAccessPolicy.Reason.NOT_SAME_LINK ->
                "폰과 같은 연결에 있지 않습니다. 같은 와이파이에 두거나 케이블로 연결하십시오."
            BridgeAccessPolicy.Reason.UNDETERMINED ->
                "연결을 가릴 수 없어 받지 않았습니다. 케이블이나 와이파이를 다시 연결해 주십시오."
        }
        return json(403, JSONObject().put("message", message).put("reason", reason.name))
    }

    /**
     * 저장 결과를 응답으로.
     *
     * **관문은 409로 낸다.** 400(잘못된 요청)이 아니다 — 요청은 올바르고, 사용자가 정해야
     * 할 것이 남아 있다는 뜻이다. 화면은 `gate` 값으로 어느 관문인지 가른다.
     */
    private fun saveResponse(outcome: BridgeData.SaveOutcome): BridgeResponse = when (outcome) {
        is BridgeData.SaveOutcome.Saved -> json(
            200,
            JSONObject()
                .put("id", outcome.characterId)
                // 화면에 없던 값이 몇 개 남았는가 — 유실은 막았으니 **존재를 알린다**(원칙 04).
                .put("preserved", outcome.preserved)
                .put("keptGlobal", outcome.keptGlobal)
        )

        BridgeData.SaveOutcome.NotFound -> json(404, JSONObject().put("message", "그 캐릭터가 없습니다."))

        is BridgeData.SaveOutcome.BadBody ->
            json(400, JSONObject().put("message", "보낸 내용을 읽지 못했습니다.").put("what", outcome.what))

        is BridgeData.SaveOutcome.Restricted -> {
            val arr = JSONArray()
            for ((fd, tokens) in outcome.violations) {
                val t = JSONArray()
                for (x in tokens) t.put(x)
                arr.put(JSONObject().put("field", fd.name).put("tokens", t))
            }
            json(409, JSONObject().put("gate", "restricted").put("violations", arr))
        }

        is BridgeData.SaveOutcome.AgeConflict ->
            json(409, JSONObject().put("gate", "ageLinkage").put("conflict", ageJson(outcome.conflict)))

        is BridgeData.SaveOutcome.CrossUniverseLoss ->
            json(
                409,
                JSONObject().put("gate", "crossUniverse")
                    .put("universeId", outcome.universeId)
                    .put("summary", outcome.summary)
            )
    }

    private fun ageJson(c: AgeLinkageConflict): JSONObject = JSONObject()
        .put("inputAge", c.inputAge)
        .put("inputBirthYear", c.inputBirthYear)
        .put("currentStdYear", c.currentStdYear)
        .put("expectedAge", c.expectedAge)
        .put("suggestedBirthYear", c.suggestedBirthYear)
        .put("suggestedStdYear", c.suggestedStdYear)
        .put("affectedCharacterCount", c.affectedCharacterCount)

    private fun json(status: Int, o: JSONObject) = BridgeResponse.Text(status, JSON, o.toString())

    /**
     * 파일 이름으로 내보낼 종류를 고른다.
     *
     * 모르는 확장자는 `application/octet-stream`이다 — 브라우저가 그림으로 그리려 하지 않고
     * 내려받기로 둔다. 추측해서 그림 종류를 붙이면 그림이 아닌 파일을 그림이라고 말하게 된다.
     */
    private fun contentTypeOf(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".heif") -> "image/heif"
            lower.endsWith(".bmp") -> "image/bmp"
            else -> "application/octet-stream"
        }
    }
}
