package com.novelcharacter.app.pcbridge

/**
 * PC 브리지 접속 토큰의 **발급과 대조** (순수 계층 — Android 비의존).
 *
 * 사용자 확정(2026.11.22)이 고른 것은 **고정 토큰 + USB 테더링 전용**의 병행이다
 * (`docs/pc_companion_design_2026-11.md` 6-2의 ㄴ+ㄷ). 고정 토큰을 고른 이유가 마찰이라
 * 이 파일의 규약도 그 쪽을 향한다 — URL에 담겨 즐겨찾기 한 번으로 끝나야 하고, 그래서
 * **URL에 담아도 되는 글자만 쓴다.**
 *
 * ## 왜 길이를 32로 두는가
 *
 * 이 토큰이 막는 것은 *같은 네트워크에 있는 다른 기기*다. 공개 인터넷이 아니라 LAN이므로
 * 초당 시도 수가 네트워크 속도에 묶이지만, **그것을 안전의 근거로 적지 않는다** — 테더링
 * 전용을 끄고 Wi-Fi로 쓰는 경로가 열려 있고(ㄴ 단독 사용), 그때는 공유기에 붙은 무엇이든
 * 시도할 수 있다. 32자 × 32종 ≈ 160비트다.
 *
 * ## 대조는 상수 시간이다
 *
 * `==`로 견주면 **첫 글자가 틀린 토큰과 서른한 글자가 맞는 토큰의 응답 시간이 다르다.**
 * LAN 안에서는 그 차이가 재어질 만큼 조용하다. 그래서 길이를 먼저 흘리지 않고
 * 전체를 XOR로 접는다 — [matches]가 그 자리다.
 */
object BridgeToken {

    /**
     * 토큰 글자 — URL 질의에 그대로 담기고 사용자가 눈으로 베껴 적을 수도 있는 집합.
     *
     * **헷갈리는 글자를 뺐다**(`0`·`O`·`1`·`l`·`I`). 토큰을 손으로 옮기는 경로가 실재하기
     * 때문이다 — 앱 화면의 URL을 PC 주소창에 적는 첫 1회가 그것이다. 빼는 것만으로
     * 글자당 정보량이 5.17 → 5비트로 줄지만, 32자에서 165 → 160비트이므로 값을 치를
     * 만하다.
     */
    private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 발급 길이. 이 값을 줄이면 기존 토큰은 그대로 유효하다 — [matches]는 길이를 가정하지 않는다. */
    const val LENGTH = 32

    /**
     * 새 토큰을 만든다. [randomInt]는 `0 until bound`를 내는 난수원이고,
     * 호출부가 `java.security.SecureRandom`을 넘긴다 — **이 파일은 난수원을 고르지 않는다**
     * (순수 계층으로 두어 시험이 결정적 난수원을 넣을 수 있게 하는 것이 목적이다).
     */
    fun generate(randomInt: (Int) -> Int): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) { sb.append(ALPHABET[randomInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /**
     * 받은 글자가 저장된 토큰인가 — **상수 시간 대조**.
     *
     * 길이가 다르면 즉시 false를 돌려주고 싶은 유혹이 있지만 그러면 길이가 새어 나간다.
     * 대신 **두 글자열을 같은 길이로 보고** 접는다: 길이 차이 자체를 누적 비트에 섞으므로
     * 길이가 다른 입력은 언제나 실패하고, 걸리는 시간은 저장된 토큰의 길이에만 달려 있다.
     *
     * **빈 토큰은 무엇과도 맞지 않는다.** 저장된 토큰이 없는 상태(서버를 한 번도 켜지 않음)가
     * *"아무 글자나 통과"*가 되면 안 된다 — `ImagePathMatch.isInside`가 *"모르면 막는다"*로
     * 둔 것과 같은 판단이다.
     */
    fun matches(stored: String?, presented: String?): Boolean {
        val s = stored.orEmpty()
        val p = presented.orEmpty()
        if (s.isEmpty()) return false
        var diff = s.length xor p.length
        for (i in s.indices) {
            // 짧은 쪽은 0으로 채워 **같은 횟수를 돈다** — 길이로 갈리는 분기를 두지 않는다.
            val pc = if (i < p.length) p[i].code else 0
            diff = diff or (s[i].code xor pc)
        }
        return diff == 0
    }
}
