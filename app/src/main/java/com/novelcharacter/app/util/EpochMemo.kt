package com.novelcharacter.app.util

/**
 * 단일 슬롯 메모이제이션. `(key, epoch)`가 직전 계산과 동일하면 캐시값을 재사용하고, 다르면 [compute]를 다시 실행한다.
 *
 * `epoch`는 원본 데이터(예: Room 테이블) 변경을 나타내는 단조 증가 카운터다. 키가 같아도 epoch가 오르면
 * 재계산하므로, 외부 편집 후 stale한 결과를 조용히 내주지 않는다(CLAUDE.md 개발 의도 #2 변수 제어).
 *
 * 단일 슬롯인 이유: 캐릭터 목록의 실사용 패턴은 "필터·정렬은 고정, 검색어만 연속 변경"이다. 마지막 (key,epoch)
 * 하나만 보유해도 히트율이 충분하며, 무한 성장(받쳐주지 못하는 확장=결함)을 원천 차단한다.
 *
 * 스레드: 한 VM의 recompute 코루틴(단일 디스패처 컨텍스트)에서만 호출되는 것을 전제로 한다(교차 VM 공유 금지).
 */
class EpochMemo<K, V>(private val compute: suspend (K) -> V) {
    private var has = false
    private var cachedKey: K? = null
    private var cachedEpoch: Int = Int.MIN_VALUE
    private var cachedValue: V? = null

    /** (key, epoch)가 캐시와 같으면 저장값 반환, 아니면 [compute] 실행 후 저장·반환. */
    @Suppress("UNCHECKED_CAST")
    suspend fun get(key: K, epoch: Int): V {
        if (has && epoch == cachedEpoch && key == cachedKey) return cachedValue as V
        val value = compute(key)
        has = true
        cachedKey = key
        cachedEpoch = epoch
        cachedValue = value
        return value
    }

    /** 다음 [get]이 반드시 재계산하도록 캐시를 비운다. */
    fun invalidate() {
        has = false
        cachedKey = null
        cachedValue = null
        cachedEpoch = Int.MIN_VALUE
    }
}
