package com.novelcharacter.app.pcbridge

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 이 기기의 **IPv4 인터페이스를 지금 읽는다** — [BridgeAccessPolicy]의 입력을 만드는 자리.
 *
 * ## 캐시하지 않는다
 *
 * 케이블은 작업 중에 꽂히고 빠지고, Wi-Fi도 붙었다 끊긴다. 한 번 읽어 들고 있으면 판정이
 * 낡고, **낡은 판정은 두 방향으로 다 틀린다** — 빠진 케이블의 서브넷을 아직 품고 있으면
 * 통과해서는 안 될 것을 통과시키고, 새로 꽂힌 케이블을 모르면 사용자가 "왜 안 되지"를 만난다.
 * `ImagePathMatch.Canonicalizer`가 *"작업보다 오래 살려 두지 말 것"*이라 적은 것과 같은 판단이다.
 *
 * 비용은 요청마다 인터페이스 목록 한 번이고, 이 서버의 요청 수는 사람이 타이핑하는 속도에
 * 묶여 있다.
 *
 * ## 실패는 빈 목록이다 — 그리고 빈 목록은 "막는다"가 된다
 *
 * [NetworkInterface.getNetworkInterfaces]는 `SocketException`을 던질 수 있다. 던지게 두면
 * 요청 처리가 죽으므로 삼키고 빈 목록을 돌려주는데, **그것이 곧 거절이다** —
 * [BridgeAccessPolicy.decide]가 빈 목록을 `UNDETERMINED`로 읽는다(*"모르면 막는다"*).
 * 즉 읽기 실패가 조용한 통과가 되지 않는다.
 */
object BridgeAddresses {

    /** 지금 올라와 있는 IPv4 인터페이스 전부. 루프백은 담지 않는다(정책이 따로 통과시킨다). */
    fun localInterfaces(): List<BridgeAccessPolicy.LocalInterface> {
        val out = ArrayList<BridgeAccessPolicy.LocalInterface>(4)
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (iface in ifaces) {
            val up = runCatching { iface.isUp }.getOrDefault(false)
            if (!up) continue
            if (runCatching { iface.isLoopback }.getOrDefault(false)) continue
            for (addr in iface.interfaceAddresses) {
                val ip = addr.address
                if (ip !is Inet4Address) continue
                val host = ip.hostAddress ?: continue
                out.add(
                    BridgeAccessPolicy.LocalInterface(
                        name = iface.name.orEmpty(),
                        ipv4 = host,
                        prefixLength = addr.networkPrefixLength.toInt()
                    )
                )
            }
        }
        return out
    }

    /**
     * 화면에 적을 접속 주소들 — **지금 쓸 수 있는 것만** 낸다.
     *
     * 케이블 전용 모드에서 Wi-Fi 주소를 함께 보여 주면 사용자가 그것을 PC에 적고
     * *"연결이 거부됐다"*를 만난다. 그래서 **모드가 받아들일 인터페이스만** 싣는다 —
     * 화면이 거짓을 말하지 않는 것이 이 함수의 일이다.
     *
     * @return 사람이 PC 주소창에 적을 수 있는 꼴의 목록. 비어 있으면 **지금 붙을 길이 없다**
     *   (케이블 전용인데 케이블이 없거나, 네트워크를 읽지 못했다). 화면은 그 사실을 적는다.
     */
    fun reachableUrls(
        mode: BridgeAccessPolicy.Mode,
        port: Int,
        token: String
    ): List<String> {
        val ifaces = localInterfaces()
        val usable = when (mode) {
            BridgeAccessPolicy.Mode.TETHER_ONLY ->
                ifaces.filter { BridgeAccessPolicy.isTetherInterface(it.name) }
            BridgeAccessPolicy.Mode.SAME_LINK -> ifaces
        }
        // 토큰을 질의에 담는다 — 확정 ㄴ이 고른 모양이고, 즐겨찾기 한 번으로 끝나게 하는 것이
        // 그 선택의 이유다(원칙 04).
        return usable.map { "http://${it.ipv4}:$port/?t=$token" }
    }
}
