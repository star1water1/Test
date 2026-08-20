package com.novelcharacter.app.util

/**
 * 링크 묶음의 **접기·표본·전개** 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * [ImageLinkResolver]가 *선택을 그룹으로 넓히는* 계약이라면, 이쪽은 반대 방향 셋을 든다:
 * - [fold] — 목록을 그룹당 **대표 한 항목**으로 접는다(묶어 보기 — 이미지 탭 그리드와
 *   라이브러리 피커가 같은 규칙을 쓴다. 두 화면이 다르게 접으면 같은 묶음이
 *   화면마다 다른 대표로 보인다).
 * - [sampleForAi] — 그룹당 앞 N장만 뽑아 보낼 목록을 만든다(AI 태깅의 묶음 단위 전송).
 * - [expandPicked] — 표본에 붙은 태그를 묶음 전원으로 되편다(적용 시점의 전개).
 *
 * 대표는 **입력 순서의 첫 항목**이다 — 스키마에 대표 개념이 없으므로(그룹은 토큰 공유로만
 * 존재한다) 화면의 현재 정렬이 곧 대표를 정한다. 호출측이 정렬을 바꾸면 대표도 따라 바뀌는
 * 것이 의도다: 크기순이면 가장 큰 장이, 날짜순이면 가장 최근 장이 묶음을 대표한다.
 */
object LinkGroupFold {

    /**
     * 접힌 한 칸. [members]는 대표를 **포함한** 그룹 전원(입력 순서 보존)이다 —
     * 선택·전체화면·일괄 작업이 이 목록을 그대로 쓴다.
     */
    data class Stack<T>(val representative: T, val members: List<T>) {
        val size: Int get() = members.size
    }

    /**
     * 목록을 그룹당 한 칸으로 접는다. 그룹 토큰이 null인 항목은 저마다 한 칸이다.
     *
     * 칸의 자리는 **그 그룹의 첫 항목 자리**다 — 접기가 순서를 다시 매기면 정렬을 바꾼
     * 사용자가 결과에서 그 정렬을 찾을 수 없다.
     */
    fun <T> fold(items: List<T>, groupIdOf: (T) -> String?): List<Stack<T>> {
        val membersByGroup = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val g = groupIdOf(item) ?: continue
            membersByGroup.getOrPut(g) { mutableListOf() }.add(item)
        }
        val seen = HashSet<String>()
        val out = ArrayList<Stack<T>>(items.size)
        for (item in items) {
            val g = groupIdOf(item)
            if (g == null) {
                out.add(Stack(item, listOf(item)))
            } else if (seen.add(g)) {
                val members = membersByGroup.getValue(g)
                out.add(Stack(members.first(), members))
            }
        }
        return out
    }

    /**
     * AI 묶음 단위 전송의 표본 계획.
     *
     * @param sendPaths 실제로 보낼 경로(입력 순서 보존) — 그룹은 앞 [perGroup]장, 미링크는 전부.
     * @param membersBySentPath 보낸 경로 → 태그가 붙을 그룹 전원. **표본이 아닌 경로는 없다** —
     *   전개는 이 표에 있는 것만 넓히므로, 묶음 단위가 아닌 실행의 태그가 옆 장으로 새지 않는다.
     * @param sampledGroups 표본으로 줄어든 그룹 수(2장 이상 그룹만 센다).
     * @param expandedTotal 그 그룹들의 전체 장수 — 고지가 "몇 장에 붙는가"를 말할 재료.
     */
    data class SamplePlan(
        val sendPaths: List<String>,
        val membersBySentPath: Map<String, List<String>>,
        val sampledGroups: Int,
        val expandedTotal: Int
    )

    /**
     * 그룹당 앞 [perGroup]장만 뽑는다. 표본의 자리 역시 입력 순서다(정렬이 대표를 정한다 —
     * [fold]와 같은 근거).
     *
     * 미링크 경로는 표본 대상이 아니라 그대로 실리고 전개 표에도 오르지 않는다 —
     * 그 장의 태그는 그 장에만 붙는다(묶음이 아닌 것을 묶음처럼 다루면 오배정이다).
     */
    fun sampleForAi(
        paths: List<String>,
        groupIdOf: (String) -> String?,
        perGroup: Int
    ): SamplePlan {
        val per = perGroup.coerceAtLeast(1)
        val membersByGroup = LinkedHashMap<String, MutableList<String>>()
        for (p in paths) {
            val g = groupIdOf(p) ?: continue
            membersByGroup.getOrPut(g) { mutableListOf() }.add(p)
        }
        val takenByGroup = HashMap<String, Int>()
        val send = ArrayList<String>(paths.size)
        val expand = LinkedHashMap<String, List<String>>()
        var sampledGroups = 0
        var expandedTotal = 0
        for (p in paths) {
            val g = groupIdOf(p)
            if (g == null) { send.add(p); continue }
            val members = membersByGroup.getValue(g)
            if (members.size < 2) { send.add(p); continue }
            val taken = takenByGroup.getOrDefault(g, 0)
            if (taken >= per) continue
            takenByGroup[g] = taken + 1
            send.add(p)
            expand[p] = members
            if (taken == 0) { sampledGroups++; expandedTotal += members.size }
        }
        return SamplePlan(send, expand, sampledGroups, expandedTotal)
    }

    /**
     * 검토에서 고른 태그를 묶음 전원으로 되편다.
     *
     * - 전개 표에 없는 경로(미링크·묶음 단위가 아닌 실행)는 그대로 통과한다.
     * - 한 그룹에서 표본 여러 장이 각자 태그를 받았으면 **합집합**이 전원에 붙는다
     *   (첫 등장 순서 보존) — 표본을 늘린 뜻이 곧 근거를 늘리는 것이라 어느 한 장을
     *   버릴 이유가 없다.
     */
    fun expandPicked(
        picked: Map<String, List<String>>,
        membersBySentPath: Map<String, List<String>>
    ): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        fun addAll(path: String, tags: List<String>) {
            val bucket = out.getOrPut(path) { mutableListOf() }
            for (t in tags) if (t !in bucket) bucket.add(t)
        }
        for ((path, tags) in picked) {
            val members = membersBySentPath[path]
            if (members == null) {
                addAll(path, tags)
            } else {
                for (m in members) addAll(m, tags)
            }
        }
        return out
    }
}
