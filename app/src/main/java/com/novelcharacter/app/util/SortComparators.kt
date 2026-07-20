package com.novelcharacter.app.util

/**
 * 목록 정렬용 공용 비교자. 값 없음(null)은 정렬 방향과 무관하게 항상 최후순으로 보내고, 동값은 타이브레이크로 안정화한다.
 *
 * `CharacterViewModel`의 필드/CALCULATED 정렬이 쓰던 인라인 비교 로직을 순수 함수로 추출해 단위 테스트가 가능하게 했다.
 */
object SortComparators {

    /**
     * 값 있는 항목은 [ascending] 방향대로, `null`(값 없음)은 항상 뒤로. 1차 비교가 같으면 [tiebreak]로 안정 정렬.
     *
     * @param ascending true면 오름차순, false면 내림차순(단, null 위치는 방향과 무관하게 최후순 고정).
     * @param tiebreak 1차 키가 같을 때 쓰는 보조 키(항상 오름차순). 보통 이름 등 안정적 식별자.
     * @param key 1차 정렬 키. null이면 값 없음으로 간주.
     */
    fun <T, R : Comparable<R>> nullsLast(
        ascending: Boolean,
        tiebreak: (T) -> String,
        key: (T) -> R?
    ): Comparator<T> = Comparator { a, b ->
        val va = key(a)
        val vb = key(b)
        val primary = when {
            va == null && vb == null -> 0
            va == null -> 1
            vb == null -> -1
            else -> if (ascending) va.compareTo(vb) else vb.compareTo(va)
        }
        if (primary != 0) primary else tiebreak(a).compareTo(tiebreak(b))
    }
}
