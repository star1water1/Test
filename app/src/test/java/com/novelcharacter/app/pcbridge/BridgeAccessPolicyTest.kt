package com.novelcharacter.app.pcbridge

import com.novelcharacter.app.pcbridge.BridgeAccessPolicy.LocalInterface
import com.novelcharacter.app.pcbridge.BridgeAccessPolicy.Mode
import com.novelcharacter.app.pcbridge.BridgeAccessPolicy.Reason
import com.novelcharacter.app.pcbridge.BridgeAccessPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAccessPolicyTest {

    private val wifi = LocalInterface("wlan0", "192.168.0.7", 24)
    private val tether = LocalInterface("rndis0", "192.168.42.129", 24)

    private fun allow(v: Verdict): String {
        assertTrue("통과가 아니다: $v", v is Verdict.Allow)
        return (v as Verdict.Allow).via
    }

    private fun deny(v: Verdict): Reason {
        assertTrue("거절이 아니다: $v", v is Verdict.Deny)
        return (v as Verdict.Deny).reason
    }

    @Test
    fun tetherOnly_acceptsCableClient() {
        val v = BridgeAccessPolicy.decide("192.168.42.4", listOf(wifi, tether), Mode.TETHER_ONLY)
        assertEquals("rndis0", allow(v))
    }

    @Test
    fun tetherOnly_rejectsWifiClient() {
        val v = BridgeAccessPolicy.decide("192.168.0.22", listOf(wifi, tether), Mode.TETHER_ONLY)
        assertEquals(Reason.NOT_TETHERED, deny(v))
    }

    @Test
    fun sameLink_acceptsWifiClient() {
        val v = BridgeAccessPolicy.decide("192.168.0.22", listOf(wifi, tether), Mode.SAME_LINK)
        assertEquals("wlan0", allow(v))
    }

    /**
     * **공유기가 42번 망을 쓰는 집** — 같은 주소가 두 인터페이스의 서브넷에 동시에 들어온다.
     * 주소의 생김새로 갈랐다면 Wi-Fi가 케이블로 통과하는 자리이고, 이 구현은 케이블을
     * 먼저 보므로 `rndis0`로 읽는다.
     */
    @Test
    fun overlappingSubnets_preferTetherInterface() {
        val wifiOn42 = LocalInterface("wlan0", "192.168.42.10", 24)
        val v = BridgeAccessPolicy.decide("192.168.42.4", listOf(wifiOn42, tether), Mode.TETHER_ONLY)
        assertEquals("rndis0", allow(v))
    }

    @Test
    fun clientOutsideEveryLink_isNotSameLink() {
        val v = BridgeAccessPolicy.decide("10.0.0.5", listOf(wifi, tether), Mode.SAME_LINK)
        assertEquals(Reason.NOT_SAME_LINK, deny(v))
    }

    @Test
    fun loopback_passesInBothModes() {
        for (mode in Mode.entries) {
            assertEquals("loopback", allow(BridgeAccessPolicy.decide("127.0.0.1", listOf(wifi), mode)))
            assertEquals("loopback", allow(BridgeAccessPolicy.decide("::1", listOf(wifi), mode)))
        }
    }

    /** 듀얼스택 소켓이 주는 `::ffff:a.b.c.d` 꼴을 벗겨 읽는다. */
    @Test
    fun ipv4MappedIpv6_isUnwrapped() {
        val v = BridgeAccessPolicy.decide("::ffff:192.168.42.4", listOf(tether), Mode.TETHER_ONLY)
        assertEquals("rndis0", allow(v))
    }

    /** 가릴 근거가 없으면 막는다 — `ImagePathMatch.isInside`와 같은 쪽이다. */
    @Test
    fun undetermined_whenNothingToJudgeWith() {
        assertEquals(Reason.UNDETERMINED, deny(BridgeAccessPolicy.decide(null, listOf(wifi), Mode.SAME_LINK)))
        assertEquals(Reason.UNDETERMINED, deny(BridgeAccessPolicy.decide("", listOf(wifi), Mode.SAME_LINK)))
        assertEquals(Reason.UNDETERMINED, deny(BridgeAccessPolicy.decide("192.168.0.2", emptyList(), Mode.SAME_LINK)))
        // 순수 IPv6은 테더링 축에서 가릴 수 없다.
        assertEquals(Reason.UNDETERMINED, deny(BridgeAccessPolicy.decide("fe80::1", listOf(wifi), Mode.SAME_LINK)))
        // 형식이 어긋난 주소를 0.0.0.0으로 접지 않는다.
        assertEquals(Reason.UNDETERMINED, deny(BridgeAccessPolicy.decide("192.168.0.999", listOf(wifi), Mode.SAME_LINK)))
        assertEquals(Reason.UNDETERMINED, deny(BridgeAccessPolicy.decide("192.168.0", listOf(wifi), Mode.SAME_LINK)))
    }

    /** 접두 길이가 범위 밖인 인터페이스는 아무것도 품지 않는다. */
    @Test
    fun invalidPrefixLength_containsNothing() {
        val broken = LocalInterface("wlan0", "192.168.0.7", 0)
        assertEquals(
            Reason.NOT_SAME_LINK,
            deny(BridgeAccessPolicy.decide("192.168.0.9", listOf(broken), Mode.SAME_LINK))
        )
    }

    /** /32는 자기 자신만 품는다 — `-1 shl 32`가 그대로 -1이 되는 자리의 확인이다. */
    @Test
    fun prefix32_containsOnlyItself() {
        val host = LocalInterface("rndis0", "192.168.42.129", 32)
        assertEquals("rndis0", allow(BridgeAccessPolicy.decide("192.168.42.129", listOf(host), Mode.TETHER_ONLY)))
        assertEquals(
            Reason.NOT_SAME_LINK,
            deny(BridgeAccessPolicy.decide("192.168.42.130", listOf(host), Mode.TETHER_ONLY))
        )
    }

    @Test
    fun tetherNames_matchByPrefix_andDoNotCatchWifiOrCellular() {
        for (n in listOf("rndis0", "ncm0", "usb0", "rndis0.1", "NCM1")) {
            assertTrue("$n 을 테더링으로 보지 못했다", BridgeAccessPolicy.isTetherInterface(n))
        }
        for (n in listOf("wlan0", "rmnet0", "eth0", "lo", "p2p0")) {
            assertTrue("$n 을 테더링으로 잘못 봤다", !BridgeAccessPolicy.isTetherInterface(n))
        }
    }
}
