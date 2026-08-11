package com.novelcharacter.app.data.repository

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 휴지통 스냅샷 payload의 **저장 형식** — GZIP+Base64 인라인 압축 (B-17).
 *
 * 캐릭터 300명짜리 세계관 삭제는 JSON 수 MB를 `trash_snapshots.payload`에 그대로 쌓는다.
 * 작업 단위 보관(R-9)이 묶음을 통째로 남기므로 총량은 종전보다 커졌고, 보관 정책을 사용자가
 * 올릴 수 있게 되면서(B-74) 더 커진다.
 *
 * ## 왜 인라인인가 — 별도 파일 보관을 고르지 않은 이유 (확정 14번)
 *
 * 백업이 **DB 파일만 복사**하는 현행 구조에서 payload를 별도 파일로 빼면, 백업·복원 경로를
 * 통째로 새로 만들지 않는 한 **복원했을 때 스냅샷만 사라진다.** 스냅샷은 휴지통 복원의
 * *재료*라 사라지면 되돌리기 자체가 죽는데, 파일도 앱도 멀쩡하므로 **사용자는 되돌리려 할
 * 때까지 모른다.** 인라인은 그 갈래를 원리적으로 만들지 않는다 — payload는 언제나 행 안에 있다.
 *
 * ## 읽기 둘·쓰기 하나 (R-2)
 *
 * 값 앞의 [MARKER]가 형식을 말하고, **표식이 없으면 평문이다.** 이미 쌓여 있는 평문 payload는
 * 마이그레이션 없이 그대로 읽힌다 — 스키마도 행도 건드리지 않는다.
 *
 * **표식이 평문 JSON과 겹칠 수 없다:** payload는 Gson이 만든 JSON이라 언제나 `{` 또는 `[`로
 * 시작하고, [MARKER]는 영문자로 시작한다.
 *
 * ## 압축이 원본보다 크면 평문으로 쓴다
 *
 * 작은 payload는 gzip 머리말과 Base64의 4/3 팽창 때문에 되레 커진다. 크기로 갈라 쓰면
 * **곁다리 이득**이 하나 더 있다 — 평문이 계속 *쓰이는* 형식으로 남으므로 폴백 경로가 죽은
 * 코드가 되지 않고, 그래서 다음 사람이 *"이제 안 쓰니 지우자"*로 지울 수 없다.
 */
object TrashPayloadCodec {

    /**
     * 압축 형식 표식. 숫자를 달아 둔 것은 형식이 또 바뀔 때 **읽기를 늘리기 위해서다** —
     * `gz2:`가 생겨도 `gz1:`은 계속 읽힌다(R-2).
     */
    const val MARKER = "gz1:"

    /**
     * 저장할 문자열로 만든다. 압축이 이득일 때만 압축하고, 아니면 원문 그대로 돌려준다.
     *
     * **압축에 실패하면 평문을 돌려준다** — 백업을 남기는 것이 압축보다 우선이다.
     * 여기서 예외를 던지면 삭제 자체가 실패하고, 그러면 사용자는 지우지도 못한다.
     */
    fun encode(plain: String): String {
        val compressed = try {
            val bytes = plain.toByteArray(Charsets.UTF_8)
            val buffer = ByteArrayOutputStream(bytes.size / 4 + 64)
            GZIPOutputStream(buffer).use { it.write(bytes) }
            MARKER + Base64.getEncoder().encodeToString(buffer.toByteArray())
        } catch (_: Exception) {
            return plain
        }
        return if (compressed.length < plain.length) compressed else plain
    }

    /**
     * 저장된 문자열을 원문 JSON으로 되돌린다. 표식이 없으면 그대로 돌려준다(구버전 평문).
     *
     * **깨진 압축 값은 예외를 던진다** — 부르는 쪽([TrashRepository]의 `parse`)이 그것을
     * "이 스냅샷은 읽을 수 없다"로 처분한다. 여기서 빈 문자열이나 원문을 돌려주면 Gson이
     * 조용히 null을 내고, 복원은 *유실이 없다고 말하면서* 빈 껍데기를 되살린다.
     */
    fun decode(stored: String): String {
        if (!stored.startsWith(MARKER)) return stored
        val raw = Base64.getDecoder().decode(stored.substring(MARKER.length))
        return GZIPInputStream(raw.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 이 값이 압축된 형식인가 — 진단·시험용. */
    fun isCompressed(stored: String): Boolean = stored.startsWith(MARKER)
}
