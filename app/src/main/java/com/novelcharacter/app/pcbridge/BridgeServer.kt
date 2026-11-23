package com.novelcharacter.app.pcbridge

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * ktor 어댑터 — **꺼내서 넘기고, 받아서 적는다.**
 *
 * ## 이 파일에는 판정이 없다
 *
 * `tools/probe_compile.sh`의 `PCBRIDGE_EXCLUDE`에 있어 **지역 컴파일 증명이 없다**(ktor가
 * 프로브 클래스패스에 없고, 넣으면 `unresolved reference` 그림자가 진짜 결함을 덮는다 —
 * `ai/`가 들어온 날 한 파일에서 신규 61건이 떴던 그 모양).
 *
 * 그래서 인증 차례·라우팅·상태 코드·관문의 사유 꼴은 전부 [BridgeHandler]가 들고, 여기
 * 남은 것은 요청에서 **글자 네 개와 주소**를 꺼내 넘기고 돌아온 것을 쓰는 일뿐이다.
 * 이 파일이 두꺼워지면 증명 없는 코드가 늘어난다는 뜻이므로, 그때는 판정을 꺼낼 것.
 *
 * ## 엔진이 CIO인 이유
 *
 * `app/build.gradle.kts`의 그 주석이 든다 — Netty·Jetty는 안드로이드에 없는 JDK API에
 * 의존하고, Netty 4.2는 D8이 API 26 미만에서 desugar하지 못하는 핸들을 쓴다.
 */
internal class BridgeServer(
    private val port: Int,
    private val settingsProvider: suspend () -> BridgeSettingsStore.BridgeSettings,
    private val data: BridgeData
) {

    private var engine: EmbeddedServer<*, *>? = null

    /**
     * 서버를 띄운다. 포트를 못 열면 **false**이고, 호출부가 그 사실을 알림에 적는다
     * (조용히 꺼지지 않는다).
     *
     * `0.0.0.0`에 붙이는 것은 케이블과 Wi-Fi가 서로 다른 인터페이스이기 때문이다 —
     * **어느 선에서 온 것을 받아들일지는 주소가 아니라 [BridgeAccessPolicy]가 정한다.**
     * 한쪽 주소에만 붙이면 케이블이 꽂히고 빠질 때마다 서버를 다시 세워야 한다.
     */
    fun start(): Boolean = runCatching {
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/{path...}") { respond(call, "GET") }
                post("/{path...}") { respond(call, "POST") }
            }
        }
        server.start(wait = false)
        engine = server
        true
    }.getOrDefault(false)

    fun stop() {
        runCatching { engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500) }
        engine = null
    }

    private suspend fun respond(call: ApplicationCall, method: String) {
        val query = HashMap<String, String>()
        // 같은 이름이 여러 번 오면 **첫 값만** 담는다 — 뒤의 값으로 앞을 덮으면 주소를
        // 손으로 고치다 생긴 중복이 조용히 다른 뜻이 된다.
        for (name in call.request.queryParameters.names()) {
            call.request.queryParameters[name]?.let { query[name] = it }
        }
        val body = if (method == "POST") runCatching { call.receiveText() }.getOrDefault("") else ""

        val result = BridgeHandler.handle(
            method = method,
            path = call.request.local.uri.substringBefore('?'),
            query = query,
            body = body,
            clientIp = call.request.local.remoteAddress,
            presentedToken = query["t"],
            settings = settingsProvider(),
            // **매 요청 새로 뜬다** — 케이블은 작업 중에 꽂히고 빠진다.
            interfaces = BridgeAddresses.localInterfaces(),
            data = data
        )

        when (result) {
            is BridgeHandler.BridgeResponse.Text -> call.respondText(
                text = result.body,
                contentType = ContentType.parse(result.contentType),
                status = HttpStatusCode.fromValue(result.status)
            )
            is BridgeHandler.BridgeResponse.Bytes -> {
                call.response.headers.append("Content-Type", result.contentType)
                call.respondFile(result.file)
            }
        }
    }
}
