package com.novelcharacter.app.ui.supplement

import kotlin.random.Random

/**
 * 랜덤 보충 탭의 뽑기 엔진 — 순수 로직(안드로이드 의존 없음, 단위 테스트 대상).
 *
 * 동작 원리:
 * - **셔플백(no-repeat)**: 풀 전체를 섞어 가방에 담고 하나씩 뽑는다. 가방이 비면
 *   다시 섞어 새 사이클을 시작한다(한 바퀴 완료 신호 [lastDrawCompletedCycle]).
 *   재충전 시 직전 뽑기가 바로 다시 나오지 않도록 현재 캐릭터를 맨 뒤로 보낸다.
 * - **뽑기 모드**: ① 순수 랜덤 ② 미흡 우선(감사 미흡 대상만; 없으면 전체 폴백
 *   [lastDrawFellBackToFullPool]) ③ 오래 안 본 순(updatedAt 오름차순 큐).
 * - **히스토리**: 이전/다음 뽑기 이동. 뒤로 간 상태에서 새로 뽑으면 앞쪽 분기는 잘린다.
 * - **풀 갱신**: 필터 변경·캐릭터 삭제 시 [updatePool]이 가방·히스토리에서 사라진 id를
 *   정리하고 현재 뽑기의 생존 여부를 알려준다.
 */
class RandomPickEngine(private val random: Random = Random.Default) {

    enum class PickMode { PURE_RANDOM, INCOMPLETE_FIRST, LEAST_RECENT }

    /** 뽑기 풀 항목 — id, 최근 수정 시각, 감사 미흡 여부 */
    data class Entry(val id: Long, val updatedAt: Long, val isIncomplete: Boolean)

    /** [updatePool] 결과 — 현재 뽑기의 생존 여부와 풀 공백 여부 */
    data class PoolUpdateResult(val currentSurvived: Boolean, val poolEmpty: Boolean)

    companion object {
        /** 히스토리 상한 — 무한 성장 방지 (초과 시 가장 오래된 항목 제거) */
        const val MAX_HISTORY = 100
    }

    var mode: PickMode = PickMode.PURE_RANDOM
        private set

    private var entries: Map<Long, Entry> = emptyMap()
    private var orderedIds: List<Long> = emptyList()
    private val bag = ArrayDeque<Long>()
    private var bagIsFallback = false
    // 이번 사이클 가방을 '뽑기'로 모두 소진했는가 — 모드 변경·풀 정리로 비워진 경우와 구분
    private var bagExhaustedByDraw = false
    private val history = mutableListOf<Long>()
    private var historyIndex = -1

    /** 직전 [next] 호출에서 셔플백이 한 바퀴를 돌아 재충전됐는가 */
    var lastDrawCompletedCycle = false
        private set

    /** 직전 [next] 호출에서 미흡 우선 모드가 미흡 캐릭터 부재로 전체 풀로 폴백했는가 */
    var lastDrawFellBackToFullPool = false
        private set

    fun current(): Long? = history.getOrNull(historyIndex)

    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex in 0 until history.size - 1

    /** 모드 변경 — 진행 중인 사이클을 리셋한다(히스토리와 현재 뽑기는 유지) */
    fun setMode(newMode: PickMode) {
        if (mode == newMode) return
        mode = newMode
        bag.clear()
        bagIsFallback = false
        bagExhaustedByDraw = false
    }

    /**
     * 풀 교체(필터 변경·데이터 리로드·캐릭터 삭제). 가방과 히스토리에서 사라진 id를
     * 정리하고, 현재 표시 중이던 뽑기가 살아남았는지 반환한다.
     */
    fun updatePool(newEntries: List<Entry>): PoolUpdateResult {
        entries = newEntries.associateBy { it.id }
        orderedIds = newEntries.map { it.id }

        val ids = entries.keys
        bag.retainAll(ids)

        // 폴백 사이클(미흡 부재로 전체 풀 순회) 중 새 미흡 캐릭터가 나타나면 가방을 비워
        // 다음 뽑기부터 미흡 우선으로 복귀한다 — 미흡 해소 시 스킵(isEligible)과 대칭
        if (mode == PickMode.INCOMPLETE_FIRST && bagIsFallback && newEntries.any { it.isIncomplete }) {
            bag.clear()
            bagIsFallback = false
            bagExhaustedByDraw = false
        }

        // 히스토리 정리 — 현재 위치를 최대한 보존하고, 현재가 삭제됐으면 직전 생존 항목으로 물러난다
        val survivedCurrent = current() != null && current() in ids
        var newIndex = -1
        val newHistory = mutableListOf<Long>()
        for ((i, id) in history.withIndex()) {
            if (id in ids) {
                newHistory.add(id)
                if (i == historyIndex) newIndex = newHistory.size - 1
            } else if (i == historyIndex) {
                newIndex = newHistory.size - 1
            }
        }
        history.clear()
        history.addAll(newHistory)
        historyIndex = if (newIndex >= 0) newIndex else newHistory.size - 1

        return PoolUpdateResult(currentSurvived = survivedCurrent, poolEmpty = entries.isEmpty())
    }

    /** 새로 뽑기 — 풀이 비어 있으면 null. 뒤로 가 있던 상태라면 앞쪽 히스토리 분기를 자른다. */
    fun next(): Long? {
        lastDrawCompletedCycle = false
        lastDrawFellBackToFullPool = false
        if (entries.isEmpty()) return null

        // 가방 앞에서부터 자격을 잃은 id(삭제됨·미흡 해소됨)를 걸러낸다
        while (bag.isNotEmpty() && !isEligible(bag.first())) bag.removeFirst()
        if (bag.isEmpty()) refillBag()
        val id = bag.removeFirstOrNull() ?: return null
        if (bag.isEmpty()) bagExhaustedByDraw = true

        // 뒤로 간 상태에서 새로 뽑으면 앞쪽 분기 절단
        while (history.size - 1 > historyIndex) history.removeAt(history.size - 1)
        history.add(id)
        if (history.size > MAX_HISTORY) history.removeAt(0)
        historyIndex = history.size - 1
        return id
    }

    fun goBack(): Long? {
        if (!canGoBack) return null
        historyIndex--
        return current()
    }

    fun goForward(): Long? {
        if (!canGoForward) return null
        historyIndex++
        return current()
    }

    private fun isEligible(id: Long): Boolean {
        val entry = entries[id] ?: return false
        if (mode == PickMode.INCOMPLETE_FIRST && !bagIsFallback) return entry.isIncomplete
        return true
    }

    private fun refillBag() {
        bagIsFallback = false
        var candidates: List<Entry> = orderedIds.mapNotNull { entries[it] }

        if (mode == PickMode.INCOMPLETE_FIRST) {
            val incomplete = candidates.filter { it.isIncomplete }
            if (incomplete.isNotEmpty()) {
                candidates = incomplete
            } else {
                bagIsFallback = true
                lastDrawFellBackToFullPool = true
            }
        }
        if (candidates.isEmpty()) return

        // 직전 뽑기가 바로 다시 나오지 않도록 현재 캐릭터는 맨 뒤로
        val cur = current()
        val rest = candidates.filter { it.id != cur }
        val ordered = when (mode) {
            PickMode.LEAST_RECENT -> rest.sortedBy { it.updatedAt }
            else -> rest.shuffled(random)
        }
        bag.clear()
        ordered.mapTo(bag) { it.id }
        if (cur != null && candidates.any { it.id == cur }) bag.addLast(cur)

        // 직전 사이클을 뽑기로 완주한 뒤의 재충전이면 한 바퀴 완료 신호
        if (bagExhaustedByDraw) lastDrawCompletedCycle = true
        bagExhaustedByDraw = false
    }
}
