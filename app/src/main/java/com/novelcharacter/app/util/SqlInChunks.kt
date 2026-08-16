package com.novelcharacter.app.util

/**
 * `IN (:목록)` 질의에 넘길 목록을 **바인드 변수 상한 아래로 갈라 주는 단일 소스** (B-242).
 *
 * SQLite는 한 문장이 쓸 수 있는 바인드 변수 수에 상한이 있다(API 31 미만의 기본값 999).
 * `WHERE id IN (:ids)`는 목록 길이만큼 변수를 쓰므로 **목록이 길면 질의가 통째로 죽는다** —
 * 목록을 사용자가 고른다면(일괄 선택·태그 전체·복원 참조) 그 길이에 상한이 없다.
 *
 * **이 저장소는 그 부류를 이미 한 번 겪었다 — B-51.** `removeTagsFromImages`가 선택분 전량을
 * 한 질의에 실어 상한을 넘겼고, **그 예외를 자기 `catch`가 삼켜** *"지웠다고 생각했는데 그대로"*가
 * 됐다(조용한 유실). 그때 `chunked(900)` 관례가 섰는데 **관례로만 두었더니 다시 벌어졌고**,
 * 값은 일곱 자리에 따로 적혀 있었다. 그래서 값도 나누는 법도 여기 하나로 모은다.
 * 빠짐을 막는 것은 이 파일이 아니라 `tools/check_in_clause_chunk.sh`이며, 이 파일은
 * **그 검사가 인정하는 유일한 통로**다.
 *
 * **왜 999가 아니라 900인가:** 같은 질의가 목록 밖에도 변수를 쓴다
 * (`markDetachedByPaths(paths, now, fromCode)`처럼 상수 인자, `LIMIT`/`OFFSET` 등).
 * 900은 그 여유분이고, 목록이 **둘**인 질의(`imageId IN (…) AND tag IN (…)`)처럼 여유로
 * 덮이지 않는 자리는 [sizeFor]에 그 몫을 밝혀 넘긴다.
 *
 * **안전한 길이 곧 빠른 길이다.** 목록이 한 덩이에 들어가면 [flat]·[each]·[sum]은 **쪼개지 않고
 * 원본을 그대로 넘긴다** — 잘라 담는 비용도, 결과를 다시 합치는 비용도 없다. 손으로 적던
 * `.chunked(900).flatMap { … }`은 그 흔한 경우에도 리스트 둘을 새로 만들었으므로, 이 통로로
 * 옮기는 것은 안전을 얻으면서 비용을 **덜어내는** 쪽이다. 나눠야 할 때도 조각은 [List.subList]
 * 뷰라 원소를 복사하지 않는다.
 *
 * 계약: **질의가 도는 동안 호출부가 원본 목록을 바꾸지 않는다.** 조각이 뷰이기 때문이다
 * (호출부는 이미 `map`·`distinct`·`toList`로 만든 것을 넘기므로 실제로 그렇다).
 */
object SqlInChunks {

    /** 한 질의에 실을 목록 길이의 상한. **이 값이 적히는 자리는 여기 하나다.** */
    const val LIMIT = 900

    /**
     * 목록 밖이 [reservedBinds]개의 변수를 더 쓸 때 쓸 수 있는 길이.
     *
     * 목록이 둘인 질의가 이것을 쓴다 — 한쪽을 [LIMIT]까지 채우면 다른 쪽 몫이 남지 않는다.
     * 상한을 넘길 만큼 예약분이 크면 1로 눌러 **적어도 한 건씩은 나아가게** 한다
     * (그 경우 질의가 여전히 상한에 닿을 수 있으나, 그것은 예약분 자체가 상한을 넘는 것이라
     * 나누기로 풀 수 있는 문제가 아니다).
     */
    fun sizeFor(reservedBinds: Int): Int = (LIMIT - reservedBinds).coerceAtLeast(1)

    /** 조각마다 [query]를 부르고 결과를 이은다 — `SELECT`용. */
    suspend fun <T, R> flat(
        items: Collection<T>,
        reservedBinds: Int = 0,
        query: suspend (List<T>) -> List<R>
    ): List<R> {
        val list = asList(items)
        if (list.isEmpty()) return emptyList()
        val size = sizeFor(reservedBinds)
        if (list.size <= size) return query(list)
        val out = ArrayList<R>()
        forEachSlice(list, size) { out.addAll(query(it)) }
        return out
    }

    /** 조각마다 [action]을 부른다 — `UPDATE`·`DELETE`처럼 돌려받을 것이 없는 자리용. */
    suspend fun <T> each(
        items: Collection<T>,
        reservedBinds: Int = 0,
        action: suspend (List<T>) -> Unit
    ) {
        val list = asList(items)
        if (list.isEmpty()) return
        val size = sizeFor(reservedBinds)
        if (list.size <= size) return action(list)
        forEachSlice(list, size, action)
    }

    /**
     * 조각마다 [query]를 부르고 **돌려받은 수를 더한다** — 영향 행 수를 세는 자리용.
     *
     * 나누기가 계수를 바꾸지 않는 것이 이 함수가 있는 이유다: 조각별 답을 그냥 쓰면
     * 마지막 조각의 수가 전체인 척하고, 그러면 화면이 사용자에게 **적은 수를 말한다**
     * (개발 의도 2번 — 조용히 틀린 고지도 유실이다). 목록이 비면 0이다.
     */
    suspend fun <T> sum(
        items: Collection<T>,
        reservedBinds: Int = 0,
        query: suspend (List<T>) -> Int
    ): Int {
        val list = asList(items)
        if (list.isEmpty()) return 0
        val size = sizeFor(reservedBinds)
        if (list.size <= size) return query(list)
        var total = 0
        forEachSlice(list, size) { total += query(it) }
        return total
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> asList(items: Collection<T>): List<T> =
        items as? List<T> ?: items.toList()

    private suspend fun <T> forEachSlice(
        list: List<T>,
        size: Int,
        action: suspend (List<T>) -> Unit
    ) {
        var start = 0
        while (start < list.size) {
            val end = minOf(start + size, list.size)
            action(list.subList(start, end))
            start = end
        }
    }
}
