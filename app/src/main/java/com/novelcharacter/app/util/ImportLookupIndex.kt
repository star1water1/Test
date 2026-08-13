package com.novelcharacter.app.util

/**
 * 가져오기 한 시트 동안 **키 → 엔티티**를 들고 있는 색인 (B-210).
 *
 * ## 무엇을 없애는가
 *
 * 엑셀 가져오기의 시트 루프는 행마다 *그 행이 가리키는 엔티티가 이미 있는가*를 물었다 —
 * `getEventByCode` · `getChangeByNaturalKey` · `getCharacterByCode` 처럼 **행 하나에 한두 번**이다.
 * B-72 ②가 값 조회의 단위를 내렸을 때 지배적인 둘(캐릭터 시트 · '캐릭터 필드값' 시트)만 없앴고,
 * **같은 모양이 행 수가 한 자리 작은 시트들에 그대로 남았다**(B-210).
 *
 * 처방은 이미 이 저장소 안에 있었다 — `charByCode`·`universesByCode`·`entriesByField`가 그 본보기다.
 * 이 클래스는 그 손코딩 맵들이 **각자 다르게 틀리던 세 가지**를 한 자리에 모은다.
 *
 * ## 지켜야 하는 것 — 세는 법은 `ImportLookupIndexTest`의 절 머리다
 *
 * 1. **`LIMIT 1`의 답을 그대로 낸다 — 먼저 실은 것이 이긴다.** 대체하는 질의는 대부분
 *    `SELECT … WHERE code = :code LIMIT 1` 꼴인데, `ORDER BY`가 없으므로 SQLite는 사실상
 *    **rowid가 작은 행**을 준다. 그런데 `associateBy`로 맵을 지으면 **뒤엣것이 이긴다** —
 *    같은 키를 든 행이 둘 있는 파일(코드 중복·동명이인)에서 **합쳐지는 상대가 바뀐다.**
 *    그 갈림은 오류도 고지도 내지 않아 *"왜 저 캐릭터가 고쳐졌지"*로만 드러난다.
 *    → 그래서 [load]는 **덮어쓰지 않고 뒤에 붙이고**, [first]는 앞에서 꺼낸다.
 *    **호출부는 id 오름차순으로 실을 것**(그 순서가 곧 rowid 순서다).
 * 2. **쓴 것은 곧바로 읽힌다.** 한 시트에 같은 코드가 두 번 있으면 — 이 저장소는 그것을
 *    *"덮어쓴다"*고 고지하고 받는다 — 두 번째 행은 첫 번째가 만든 엔티티를 봐야 한다.
 *    못 보면 **있는 것을 없다고 보고 insert를 쳐** 한 파일에서 같은 사건이 둘로 갈린다.
 *    → insert·update 뒤에 [put]을 부른다(`CharacterValueLedger`가 값 쪽에서 세운 것과 같은 성질).
 * 3. **키가 바뀌면 옛 키는 그 행을 잊는다.** 손코딩 맵이 가장 자주 빠뜨리는 자리다.
 *    캐릭터 시트는 이름·코드를 **고칠 수 있고**, 고친 뒤 SQL로 옛 이름을 물으면 당연히 없다.
 *    맵을 갱신만 하면 옛 키가 그 행을 계속 가리켜, 이름이 바뀐 캐릭터가 **옛 이름으로도**
 *    잡힌다. → [put]은 그 id가 매달려 있던 키를 먼저 끊는다.
 *
 * ## 왜 순수 클래스인가
 *
 * `ExcelImportService`는 8,700줄짜리 suspend 함수 뭉치라 **이 저장소의 어떤 로컬 검증도 닿지
 * 못한다** — 로직을 util로 내려야 순수 JVM 시험이 잰다(저장소의 기존 처방이고
 * [CharacterValueLedger]·[EpochMemo]가 같은 자리다). 위 셋은 전부 **조용히** 틀리는 부류라
 * 특히 그렇다.
 *
 * ## 메모리
 *
 * 담는 것은 **한 표의 행 전부**다. 목표 규모(×30)에서 가장 큰 대상이 값 라이브러리 14,460행이고
 * 나머지는 사건 4,110 · 관계 4,050 · 상태변화 3,390 · 캐릭터 6,420이다. 이 색인을 든 시트는
 * `StreamingImportWorkbook`이 **이미 통째로** 들고 있는 시트를 함께 도는데, 한 엔티티가 한 행보다
 * 작을 수 없으므로 봉우리는 이미 서 있는 상한(*가장 큰 시트 하나*) 안에 들어간다.
 * **다만 시트 함수의 지역 변수로 둘 것** — 서비스에 매달면 가져오기가 끝나도 남아
 * 곧이어 도는 내보내기·자동 백업의 바닥을 올린다(B-72가 지키는 경로다.
 * [CharacterValueLedger]가 그래서 [reset]을 갖는다). 시트를 넘어 사는 색인은 그 이유로
 * [reset]을 갖추고, 호출부가 가져오기마다 비운다.
 *
 * ## ⚠️ 여러 칸이 한 키인 자리(자연키)는 **타입이 있는 `data class`로 지을 것**
 *
 * `getChangeByNaturalKey(characterId, year, fieldKey, newValue)` 꼴이 그것이다. 손쉬워 보이는
 * `listOf(charId, year, …)`는 **쓰면 안 된다** — 코틀린이 원소 타입을 첫 원소에서 추론해
 * `listOf(10L, 1993, null)`의 `1993`을 **`Long`으로 올려 버리고**, 그러면 `Int` 칸에서 실은
 * 키(`Integer(1993)`)와 **영원히 같지 않다.** 컴파일도 통과하고 예외도 없이 **모든 조회가
 * `null`**이 되어, 가져오기는 *"기존 행이 하나도 없다"*고 보고 전부 새로 만든다.
 * 이 시험을 처음 쓸 때 실제로 그 모양으로 한 번 걸렸다(`ImportLookupIndexTest` ⑤).
 * `data class`로 지으면 그 자리를 **컴파일러가 잡는다.**
 *
 * @param idOf 행의 **안정 정체성**. 키가 바뀌어도 같은 행임을 알아보는 근거다.
 * @param keyOf 이 색인이 답하는 키. `null`을 주면 그 행은 이 색인에 실리지 않는다
 *   (빈 코드처럼 *키가 없는* 행 — 빈 문자열로 실으면 빈 코드끼리 서로를 찾는다).
 */
class ImportLookupIndex<K : Any, V : Any>(
    private val idOf: (V) -> Long,
    private val keyOf: (V) -> K?
) {

    private val buckets = HashMap<K, MutableList<V>>()

    /** id가 지금 어느 키에 매달려 있는가 — 성질 3(옛 키 끊기)의 유일한 근거다. */
    private val keyById = HashMap<Long, K>()

    /**
     * 표의 현재 내용을 싣는다. **id 오름차순으로 넘길 것** — 그 순서가 `LIMIT 1`이 고르는
     * 순서(rowid)이고, [first]는 그 순서의 앞에서 꺼낸다(성질 1).
     */
    fun load(rows: Iterable<V>) {
        for (row in rows) put(row)
    }

    /**
     * 이 키의 답 — `… WHERE key = :key LIMIT 1`의 자리.
     * 같은 키를 든 행이 여럿이면 **먼저 실린 것**을 준다(성질 1).
     */
    fun first(key: K): V? = buckets[key]?.firstOrNull()

    /** 이 키의 행 전부 — `… WHERE key = :key`(LIMIT 없음)의 자리. 없으면 빈 목록. */
    fun all(key: K): List<V> = buckets[key] ?: emptyList()

    /** 이 키를 든 행이 있는가. */
    fun contains(key: K): Boolean = buckets[key]?.isNotEmpty() == true

    /**
     * 행을 썼다고 기록한다(insert·update 양쪽 — DB에 쓴 **그 행**을 그대로 넘길 것).
     * 키가 바뀌었으면 옛 키에서 먼저 뺀다(성질 3). 이미 있던 행이면 **자리를 지켜**
     * 갈아 끼운다 — 뒤로 밀면 `LIMIT 1`의 답이 바뀐다.
     */
    fun put(row: V) {
        val id = idOf(row)
        val newKey = keyOf(row)
        val oldKey = keyById[id]
        if (oldKey != null && oldKey != newKey) detach(id, oldKey)
        if (newKey == null) {
            keyById.remove(id)
            return
        }
        val bucket = buckets.getOrPut(newKey) { mutableListOf() }
        val at = bucket.indexOfFirst { idOf(it) == id }
        if (at >= 0) bucket[at] = row else bucket.add(row)
        keyById[id] = newKey
    }

    /** 행을 지웠다고 기록한다 — 다음 [first]는 그 행을 주지 않는다. */
    fun remove(row: V) = removeById(idOf(row))

    /** id로 지운다 — 지울 때 행 전체가 손에 없는 자리를 위해 갈라 둔다. */
    fun removeById(id: Long) {
        val key = keyById.remove(id) ?: return
        detach(id, key)
    }

    /** 색인을 통째로 비운다 — 시트를 넘어 사는 색인은 **가져오기마다** 부른다. */
    fun reset() {
        buckets.clear()
        keyById.clear()
    }

    private fun detach(id: Long, key: K) {
        val bucket = buckets[key] ?: return
        bucket.removeAll { idOf(it) == id }
        if (bucket.isEmpty()) buckets.remove(key)
    }
}
