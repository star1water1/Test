package com.novelcharacter.app.util

/**
 * 목록 화면의 이름 찾기(B-49) — 세계관·작품·세력 목록이 같은 규칙을 쓴다.
 *
 * 화면에 이미 올라온 목록을 좁힐 뿐이라 DB를 다시 묻지 않는다. 대소문자와 공백은 가리지 않는다
 * ("knight order" = "KnightOrder") — 사람이 이름을 떠올릴 때 띄어쓰기는 기억의 대상이 아니다.
 */
object ListNameFilter {

    /** 비교용으로 다듬은 검색어. 빈 문자열이면 '찾는 중이 아님'이다. */
    fun normalize(text: String?): String =
        text.orEmpty().lowercase().filterNot { it.isWhitespace() }

    fun isActive(query: String?): Boolean = normalize(query).isNotEmpty()

    /** [texts] 중 하나라도 검색어를 담으면 참. 검색어가 비면 전부 참이다. */
    fun matches(query: String?, vararg texts: String?): Boolean {
        val needle = normalize(query)
        if (needle.isEmpty()) return true
        return texts.any { normalize(it).contains(needle) }
    }

    /** 순서를 지킨 채 좁힌다. 검색어가 비면 **같은 목록**을 돌려준다(순서 편집이 그 목록을 쓴다). */
    fun <T> filter(items: List<T>, query: String?, texts: (T) -> List<String?>): List<T> {
        if (!isActive(query)) return items
        return items.filter { matches(query, *texts(it).toTypedArray()) }
    }
}
