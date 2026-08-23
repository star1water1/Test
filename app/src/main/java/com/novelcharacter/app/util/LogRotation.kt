package com.novelcharacter.app.util

/**
 * 오류 로그가 상한에 닿았을 때 **무엇을 남기는가** — 순수 판정 (2026.08.22).
 *
 * **왜 생겼나.** 종전 `AppLogger.writeEntry`는 상한(512KB)을 넘으면
 * `logFile.delete()`였다 — 회전이 아니라 **전량 삭제**다. 그 뒤 새 항목 하나만 붙으므로,
 * 설정 화면의 '오류 로그'가 *"총 137건"*을 보이다가 오류 하나가 더 나는 순간 **1건**으로
 * 떨어졌다. 간헐적 결함을 좇으며 쌓아 온 기록이 **예고도 흔적도 없이** 사라진다
 * (쓰기 실패는 `catch (_: Exception) { }`가 삼키므로 알릴 자리도 없었다).
 *
 * 형제 자리인 휴지통은 `pruneIfNeeded()`로 **넘치는 만큼만** 덜어낸다. 로그만 전량이었다.
 *
 * 판정을 순수 계층에 두는 이유는 **파일 입출력 없이 잴 수 있게** 하기 위해서다 —
 * 경계(빈 파일 · 블록 하나가 상한보다 큰 경우 · 구분자가 없는 옛 파일)가 여기 다 모인다.
 */
object LogRotation {

    /** 잘라냈다는 사실을 파일에 남기는 표식 — 목록이 이 줄을 그대로 보여 준다. */
    const val TRUNCATION_NOTICE = "[오래된 기록은 용량 상한으로 잘렸습니다]"

    /**
     * [existing]에서 **최근 쪽 절반가량**을 남긴다.
     *
     * 블록은 [separator] 줄로 갈린다(파일 머리부터 오래된 순서다). 남길 바이트 상한은
     * [keepBytes]이고, **뒤에서부터** 담아 상한에 닿으면 멈춘다 — 최근 것을 지키는 것이
     * 이 함수의 목적이다.
     *
     * - 남길 것이 없으면(한 블록이 [keepBytes]보다 크다) **가장 최근 블록 하나는 남긴다** —
     *   상한 때문에 방금 난 오류를 잃는 것이 가장 나쁘다.
     * - 구분자가 없는 옛 파일은 통째로 한 블록이라 같은 규칙을 탄다.
     * - 잘라낸 것이 있으면 머리에 [TRUNCATION_NOTICE]를 남긴다(R-14 — 잘랐으면 말한다).
     */
    fun rotate(existing: String, keepBytes: Long, separator: String): String {
        if (existing.isEmpty()) return existing
        val blocks = splitBlocks(existing, separator)
        if (blocks.isEmpty()) return ""

        val kept = ArrayList<String>()
        var used = 0L
        for (block in blocks.asReversed()) {
            val size = block.toByteArray().size.toLong()
            if (used + size > keepBytes && kept.isNotEmpty()) break
            kept.add(block)
            used += size
            // 한 블록이 상한을 통째로 넘겨도 그 하나는 담고 멈춘다.
            if (used >= keepBytes) break
        }
        kept.reverse()

        val dropped = blocks.size - kept.size
        val body = kept.joinToString("")
        return if (dropped > 0) "$separator\n$TRUNCATION_NOTICE\n$body" else body
    }

    /**
     * 구분자 줄을 **블록의 머리로** 삼아 자른다 — 잘라 붙여도 원래 모양이 그대로 나온다
     * (파서가 구분자로 나누므로 머리를 잃으면 첫 블록이 안 읽힌다).
     */
    private fun splitBlocks(text: String, separator: String): List<String> {
        // **글자를 그대로 보존한다** — 이어 붙이면 원문이 나와야 한다. 줄 단위로 다시
        // 조립하면 끝의 개행이 늘거나 줄어 *상한 안이면 그대로 둔다*가 깨진다(실제로 깨졌다).
        val marks = ArrayList<Int>()
        var idx = 0
        while (true) {
            val i = text.indexOf(separator, idx)
            if (i < 0) break
            if (i == 0 || text[i - 1] == '\n') marks.add(i)
            idx = i + separator.length
        }
        if (marks.isEmpty()) return listOf(text)

        val out = ArrayList<String>()
        // 첫 구분자 앞의 글자(옛 형식의 머리말 등)도 한 블록으로 담는다 — 버리지 않는다.
        if (marks[0] > 0) out.add(text.substring(0, marks[0]))
        for (k in marks.indices) {
            val end = if (k + 1 < marks.size) marks[k + 1] else text.length
            out.add(text.substring(marks[k], end))
        }
        return out
    }
}
