package com.novelcharacter.app.util

/**
 * 글자 수 상한으로 자를 때의 **경계 규칙 한 벌** (2026.08.22).
 *
 * 코틀린 `String`은 UTF-16이라 `take(n)`은 이모지 같은 **보충 문자의 서러게이트 쌍을
 * 반토막** 낼 수 있다. 그러면 저장된 값의 마지막 글자가 짝 잃은 반쪽이 되어 칸에 깨진
 * 글자로 남고, 그대로 프롬프트·시트에 실려 나간다.
 *
 * 엑셀 셀 쪽은 이 규칙을 이미 지키고 있었는데(`truncateForCell`) **AI 기조 문구 절단은
 * 맨 `take()`였다.** 같은 판정을 두 벌로 두면 이렇게 한쪽만 지켜진다 — 그래서 규칙을
 * 여기 한 곳에 두고 둘이 그것을 쓴다.
 */
object SafeTruncate {

    /**
     * [value]를 [limit]자 이하로 자르되 **서러게이트 쌍을 쪼개지 않는다.**
     *
     * 상한 안이면 원문 그대로다(사본을 만들지 않는다). 자를 자리가 하필 쌍의 한가운데면
     * 한 글자를 더 떨어뜨린다 — 반쪽을 남기느니 온전한 글자만 남긴다.
     */
    fun atCharLimit(value: String, limit: Int): String {
        if (limit <= 0) return ""
        if (value.length <= limit) return value
        val cut = value.take(limit)
        return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}
