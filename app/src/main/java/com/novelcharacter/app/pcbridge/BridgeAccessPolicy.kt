package com.novelcharacter.app.pcbridge

/**
 * **어느 기기의 접속을 받아들이는가** — PC 브리지의 그물 판정 (순수 계층 — Android 비의존).
 *
 * 토큰([BridgeToken])이 *"이 사람이 맞는가"*를 보는 반면 이 파일은 *"이 선에서 온 것인가"*를
 * 본다. 사용자 확정(ㄴ+ㄷ)이 둘을 **병행**으로 고른 근거가 그 분업이다 — 토큰 하나만으로는
 * 공유기에 붙은 무엇이든 시도할 수 있고, 테더링 하나만으로는 케이블을 꽂은 PC의 다른
 * 사용자를 가릴 수 없다.
 *
 * ## 왜 서브넷이 아니라 **인터페이스**로 가르는가
 *
 * USB 테더링은 보통 `192.168.42.0/24`를 쓰고 집 Wi-Fi도 `192.168.x.x`를 쓴다. 주소의 생김새로
 * 가르면 **공유기가 42번 망을 쓰는 집에서 Wi-Fi가 테더링으로 통과한다.** 그래서 판정의 입력은
 * 주소가 아니라 **그 주소를 품은 로컬 인터페이스**이고, 이름이 테더링 계열인지를 본다
 * (`rndis0` · `ncm0` · `usb0` — 안드로이드 USB 테더링이 세우는 이름들).
 *
 * ## 실패 처분은 "모르면 막는다"
 *
 * `ImagePathMatch.isInside`와 같은 쪽이다. 어느 인터페이스에 속하는지 가릴 수 없는 주소
 * (인터페이스 목록이 비었다 · 주소 파싱 실패 · 어느 서브넷에도 안 들어온다)는 **거절한다.**
 * 이 판정의 소비처가 *캐릭터 DB 전체를 내보내도 되는가*이므로 "모르겠으면 통과"는 곧 유출이다.
 *
 * 거절은 조용하지 않다 — 호출부가 사유를 들고 고지한다([Verdict]가 사유를 나른다).
 */
object BridgeAccessPolicy {

    /** 접속을 받아들이는 범위. 사용자가 설정에서 고른다. */
    enum class Mode {
        /** 케이블(USB 테더링)로 온 것만 받는다 — 확정 ㄷ. */
        TETHER_ONLY,

        /** 같은 선에 붙은 기기면 받는다(Wi-Fi 포함). 토큰이 유일한 방벽이 되는 모드다. */
        SAME_LINK
    }

    /** 로컬 인터페이스 하나 — 이름과 그 인터페이스가 가진 IPv4 주소·접두 길이. */
    data class LocalInterface(
        val name: String,
        /** 점 넷 표기 IPv4. IPv6은 담지 않는다 — 테더링 판정이 IPv4 축이다. */
        val ipv4: String,
        /** 서브넷 접두 길이(예: 24). 0~32 밖이면 그 항목은 쓰이지 않는다. */
        val prefixLength: Int
    )

    /** 판정 결과. 거절은 **사유를 반드시 나른다**(R-17 — "못 찾음"과 "없음"은 다르다). */
    sealed class Verdict {
        /** 통과. [via]는 그 주소를 품은 인터페이스 이름 — 고지와 기록에 쓴다. */
        data class Allow(val via: String) : Verdict()

        /** 거절. [reason]은 화면 문구가 아니라 **사유 코드**다 — 문구는 호출부가 입힌다. */
        data class Deny(val reason: Reason) : Verdict()
    }

    enum class Reason {
        /** 어느 로컬 인터페이스의 서브넷에도 들어오지 않는다 — 같은 선이 아니다. */
        NOT_SAME_LINK,

        /** 같은 선이지만 케이블이 아니다 — [Mode.TETHER_ONLY]에서만 난다. */
        NOT_TETHERED,

        /** 주소를 읽을 수 없거나 인터페이스 목록이 비었다 — "모르면 막는다". */
        UNDETERMINED
    }

    /**
     * USB 테더링이 세우는 인터페이스 이름의 접두.
     *
     * **접두로 견주는 것이 요점이다** — 제조사·커널에 따라 `rndis0`·`ncm0`·`usb0`에 번호가
     * 붙거나 `rndis0.1` 꼴이 된다. 목록을 늘릴 때는 **Wi-Fi(`wlan`)·셀룰러(`rmnet`)와
     * 접두가 겹치지 않는지**를 먼저 볼 것(R-40이 의사키 어휘에서 배운 것과 같은 축이다).
     */
    private val TETHER_PREFIXES = listOf("rndis", "ncm", "usb")

    /** 루프백 — 폰 자신의 브라우저에서 여는 경로. 두 모드 모두 통과시킨다. */
    private const val LOOPBACK_V4_PREFIX = "127."

    /** 이름이 테더링 계열인가. */
    fun isTetherInterface(name: String): Boolean {
        val n = name.lowercase()
        return TETHER_PREFIXES.any { n.startsWith(it) }
    }

    /**
     * [clientIp]를 받아들일지 판정한다.
     *
     * @param clientIp 접속해 온 쪽의 주소. IPv6 꼴(`::1`·`::ffff:a.b.c.d`)도 들어온다 —
     *   매핑된 IPv4는 벗겨 보고, 순수 IPv6은 가릴 근거가 없으므로 [Reason.UNDETERMINED]다.
     * @param interfaces 지금 이 기기의 IPv4 인터페이스 전부. 호출부가 매 요청 새로 뜬다
     *   (케이블은 작업 중에 꽂히고 빠진다 — 캐시하면 판정이 낡는다).
     */
    fun decide(
        clientIp: String?,
        interfaces: List<LocalInterface>,
        mode: Mode
    ): Verdict {
        val raw = clientIp?.trim().orEmpty()
        if (raw.isEmpty()) return Verdict.Deny(Reason.UNDETERMINED)

        val v4 = toIpv4(raw) ?: return Verdict.Deny(Reason.UNDETERMINED)
        if (v4.startsWith(LOOPBACK_V4_PREFIX)) return Verdict.Allow("loopback")

        val clientBits = parseIpv4(v4) ?: return Verdict.Deny(Reason.UNDETERMINED)
        if (interfaces.isEmpty()) return Verdict.Deny(Reason.UNDETERMINED)

        // **테더링 인터페이스를 먼저 본다.** 같은 주소가 두 인터페이스의 서브넷에 동시에
        // 들어오는 구성(공유기가 42번 망을 쓰는 집)에서 차례가 답을 가르기 때문이다 —
        // 케이블이 후보에 있으면 케이블로 읽는 것이 사용자의 의도에 가깝다.
        val matched = interfaces
            .filter { containsClient(it, clientBits) }
            .sortedByDescending { isTetherInterface(it.name) }

        val hit = matched.firstOrNull() ?: return Verdict.Deny(Reason.NOT_SAME_LINK)
        return when (mode) {
            Mode.SAME_LINK -> Verdict.Allow(hit.name)
            Mode.TETHER_ONLY ->
                if (isTetherInterface(hit.name)) Verdict.Allow(hit.name)
                else Verdict.Deny(Reason.NOT_TETHERED)
        }
    }

    /** 인터페이스의 서브넷이 클라이언트를 품는가. 접두 길이가 범위 밖이면 품지 않는다. */
    private fun containsClient(iface: LocalInterface, clientBits: Int): Boolean {
        if (iface.prefixLength < 1 || iface.prefixLength > 32) return false
        val own = parseIpv4(iface.ipv4) ?: return false
        // 접두 32는 `-1 shl 32`가 되어 자리이동 규칙상 그대로 -1이다 — 의도한 값(전체 비트)이다.
        val mask = if (iface.prefixLength == 32) -1 else (-1 shl (32 - iface.prefixLength))
        return (own and mask) == (clientBits and mask)
    }

    /**
     * IPv6 표기에서 IPv4를 끄집어낸다. 못 꺼내면 null.
     *
     * NanoHTTPD가 주는 주소는 소켓 그대로라 듀얼스택에서 `::ffff:192.168.42.4` 꼴이 온다.
     * `::1`은 루프백의 IPv6 표기이므로 IPv4 루프백으로 접는다 — 같은 사실이다.
     */
    private fun toIpv4(raw: String): String? {
        val s = raw.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (s == "::1" || s == "0:0:0:0:0:0:0:1") return "127.0.0.1"
        val mapped = s.substringAfterLast(':', missingDelimiterValue = s)
        return if (mapped.count { it == '.' } == 3) mapped else null
    }

    /** 점 넷 표기를 32비트로. 형식이 어긋나면 null — 0으로 접지 않는다(0.0.0.0은 뜻이 있는 주소다). */
    private fun parseIpv4(s: String): Int? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var acc = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3) return null
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            acc = (acc shl 8) or n
        }
        return acc
    }
}
