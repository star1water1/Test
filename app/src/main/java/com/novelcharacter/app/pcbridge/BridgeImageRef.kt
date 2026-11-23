package com.novelcharacter.app.pcbridge

import com.novelcharacter.app.util.ImagePathMatch
import java.io.File
import java.security.MessageDigest

/**
 * 이미지 **불투명 id ↔ 파일 경로** (순수 계층 — Android 비의존).
 *
 * ## 왜 경로를 내보내지 않는가
 *
 * 웹이 `/api/image?path=/data/.../char_x.jpg` 꼴을 쓰면 **경로가 요청 인자가 된다.** 그러면
 * 서버는 받은 경로를 믿을지 말지를 매 요청 판정해야 하고, 그 판정이 한 번 느슨해지는 것이
 * 곧 기기 파일 유출이다(`..`를 섞는 고전적인 자리). 그래서 **경로는 요청에 아예 등장하지
 * 않는다** — 서버가 DB에서 읽은 경로에 id를 붙여 내보내고, 요청은 그 id만 들고 온다.
 *
 * id는 경로의 해시이므로 **되돌릴 수 없고**, 같은 파일이면 매번 같다(브라우저 캐시가 듣는다).
 *
 * ## 봉쇄 가드는 `ImagePathMatch.isInside`다
 *
 * R-39가 정한 단일 소스이고, 그 KDoc이 소비처를 *"파일 바이트를 기기 밖으로 내보내도 되는가"*
 * 라고 적어 두었다. **HTTP로 이미지를 내보내는 이 파일이 바로 그 소비처다** — 지금까지 가장
 * 직접적인 자리다. 새 판정을 적지 않고 그 함수를 부른다.
 *
 * ## 막힌 것과 없는 것은 다르다
 *
 * [Resolution.Blocked]와 [Resolution.NotFound]를 가른다(R-17·R-39). 엑셀 '이미지' 열로
 * 들어온 외부 경로는 **DB에 그대로 살아 있고**(`ImagePathCell` — 버리지 않는 것이 규약),
 * 그 경로의 그림은 앱 화면에서도 플레이스홀더로만 보인다. 그것이 PC에서 *"없다"*로 보이면
 * 사용자는 파일이 사라졌다고 읽는다 — 사실은 **앱 저장소 밖이라 내보내지 않는 것**이다.
 */
object BridgeImageRef {

    /** id 글자 수 — SHA-256 앞 16바이트의 16진 표기. */
    const val ID_LENGTH = 32

    sealed class Resolution {
        /** 후보에서 찾았고 봉쇄 가드를 지났다. */
        data class Found(val path: String) : Resolution()

        /** 후보에 그 id가 없다. */
        object NotFound : Resolution()

        /**
         * 후보에 있지만 앱 저장소 밖이라 내보내지 않는다.
         * [path]는 **사용자에게 사유를 적기 위한 것**이고 바이트는 나가지 않는다.
         */
        data class Blocked(val path: String) : Resolution()
    }

    /**
     * 경로의 불투명 id. 같은 경로 글자는 언제나 같은 id다.
     *
     * **정규화하지 않는다.** `ImagePathMatch.canonical`을 통과시키면 파일 시스템 호출이
     * 붙고(경로 조각마다 stat), 이 함수는 목록을 내보낼 때 이미지 수만큼 불린다. 더 중요한
     * 것은 **DB에 실린 글자 그대로를 가리켜야 한다**는 점이다 — 해석은 [resolve]가 후보
     * 목록과 견주며 하고, 그때 쓰는 대조가 `canonical`을 아는 쪽이다.
     */
    fun idFor(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(ID_LENGTH)
        for (i in 0 until ID_LENGTH / 2) {
            val b = digest[i].toInt() and 0xff
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    /**
     * id를 후보 경로들 안에서 해석한다.
     *
     * @param id 요청이 들고 온 id.
     * @param candidates 이 요청이 **볼 권한이 있는** 경로들(그 캐릭터의 `imagePaths`).
     *   권한 판정은 호출부가 이미 한 것이고 이 함수는 그 집합 밖을 보지 않는다.
     * @param filesDir 앱 저장소 뿌리. null이면 전부 [Resolution.Blocked]다 — *"모르면 막는다"*.
     */
    fun resolve(id: String, candidates: List<String>, filesDir: File?): Resolution {
        val wanted = id.trim()
        if (wanted.length != ID_LENGTH) return Resolution.NotFound
        for (path in candidates) {
            val p = path.trim()
            if (p.isEmpty()) continue
            if (!idFor(p).equals(wanted, ignoreCase = true)) continue
            return if (ImagePathMatch.isInside(p, filesDir)) Resolution.Found(p)
            else Resolution.Blocked(p)
        }
        return Resolution.NotFound
    }

    /**
     * 후보 중 **실제로 내보낼 수 있는** 것들의 (id, 경로) 목록 — 목록 응답을 만드는 자리.
     *
     * 막힌 것은 여기서 빠지고, **빠진 수를 호출부가 고지한다**(R-14 — 목록에서 잘라낸 것은
     * 개수로 존재를 알린다). 그 수가 0이 아니면 PC 화면에 *"앱 저장소 밖이라 보이지 않는
     * 그림 n장"*이 적힌다.
     */
    fun servable(candidates: List<String>, filesDir: File?): Servable {
        val ids = ArrayList<Pair<String, String>>(candidates.size)
        var blocked = 0
        for (path in candidates) {
            val p = path.trim()
            if (p.isEmpty()) continue
            if (ImagePathMatch.isInside(p, filesDir)) ids.add(idFor(p) to p) else blocked++
        }
        return Servable(ids, blocked)
    }

    /** [servable]의 결과. [blockedCount]는 **0이 아니면 반드시 화면에 적힌다.** */
    data class Servable(val entries: List<Pair<String, String>>, val blockedCount: Int)
}
