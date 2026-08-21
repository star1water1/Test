package com.novelcharacter.app.util

/**
 * 링크 묶음의 **접기·표본·전개** 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * [ImageLinkResolver]가 *선택을 그룹으로 넓히는* 계약이라면, 이쪽은 반대 방향 셋을 든다:
 * - [fold] — 목록을 그룹당 **대표 한 항목**으로 접는다(묶어 보기 — 이미지 탭 그리드와
 *   라이브러리 피커가 같은 규칙을 쓴다. 두 화면이 다르게 접으면 같은 묶음이
 *   화면마다 다른 대표로 보인다).
 * - [sampleForAi] — 그룹당 앞 N장만 뽑아 **보낼** 목록을 만든다(AI 태깅의 전송 계획).
 * - [expandPicked] — 경로에 붙은 태그를 묶음 전원으로 되편다(적용 시점의 전개).
 *
 * ## 링크 묶음은 태그를 공유하는 단위다 (2026.08.21 사용자 판정)
 *
 * 배정이 그룹 단위로 확장되듯([ImageLinkResolver]의 계약) **태그도 어느 경로로 붙든 묶음
 * 전원에 붙는다** — 수동 편집·일괄 추가/제거·AI 제안·폴더 제안 전부가 같은 불변식을 진다.
 * 경계가 갈리는 축은 둘뿐이다:
 * - **전송의 경계는 선택이다** — AI에 보내는 것은 비용이 들고 기기 밖으로 나가므로,
 *   사용자가 고르지 않은 식구를 대신 보내지 않는다([sampleForAi]는 선택 안에서만 뽑는다).
 * - **적용의 경계는 적용 시점의 살아 있는 명단이다** — 전개 표를 실행 시점에 떠서 들고
 *   다니면 검토 중 링크를 바꿨을 때 낡은 명단에 붙는다. 그래서 [expandPicked]에 먹일
 *   명단은 호출측(ViewModel)이 **적용 직전에** 현재 링크에서 뜬다.
 * 엑셀 들이기만 예외다 — 왕복 무결성은 파일이 말한 그대로의 복원이 계약이라 행 단위로 쓴다.
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
     * AI 묶음 단위 전송의 표본 계획 — **보낼 장수만 정한다.** 붙는 범위는 이 계획과 무관하게
     * 언제나 묶음 전원이다(위 불변식 — 전개는 적용 시점에 [expandPicked]가 한다).
     *
     * @param sendPaths 실제로 보낼 경로(입력 순서 보존) — 그룹은 앞 [perGroup]장, 미링크는 전부.
     * @param sampledGroups 대상에 걸린 링크 묶음 수 — 고지 "묶음 N개"의 재료.
     * @param expandedTotal 그 묶음들의 **전체 식구 수**(선택 밖 포함) — 고지 "M장에 붙는다"의 재료.
     */
    data class SamplePlan(
        val sendPaths: List<String>,
        val sampledGroups: Int,
        val expandedTotal: Int
    )

    /**
     * 그룹당 앞 [perGroup]장만 뽑는다. 표본의 자리 역시 입력 순서다(정렬이 대표를 정한다 —
     * [fold]와 같은 근거). 전원 전송(표본 없이)은 [perGroup]에 [Int.MAX_VALUE]를 넣으면 된다.
     *
     * **묶음 판정은 선택이 아니라 라이브러리 전체 식구 수([fullSizeOf])로 한다.** 종전에는
     * 선택 안에서 명단을 만들어 5장 묶음에서 1장만 고르면 묶음으로 인식조차 안 됐다 —
     * 그 장의 태그가 식구에게 못 갔다(공유 불변식 위반). 지금은 선택에 1장만 보여도 그 장이
     * 묶음의 표본이고, 붙는 범위 고지도 전체 식구 수로 센다.
     *
     * 미링크 경로는 표본 대상이 아니라 그대로 실린다 — 붙는 범위도 그 한 장이다
     * (묶음이 아닌 것을 묶음처럼 다루면 오배정이다).
     */
    fun sampleForAi(
        paths: List<String>,
        groupIdOf: (String) -> String?,
        perGroup: Int,
        fullSizeOf: (String) -> Int
    ): SamplePlan {
        val per = perGroup.coerceAtLeast(1)
        val selectedByGroup = LinkedHashMap<String, MutableList<String>>()
        for (p in paths) {
            val g = groupIdOf(p) ?: continue
            selectedByGroup.getOrPut(g) { mutableListOf() }.add(p)
        }
        val takenByGroup = HashMap<String, Int>()
        val send = ArrayList<String>(paths.size)
        var sampledGroups = 0
        var expandedTotal = 0
        for (p in paths) {
            val g = groupIdOf(p)
            if (g == null) { send.add(p); continue }
            // 전체 식구 수가 정본이되, 목록이 낡아 선택 수보다 작게 답하면 선택 수로 받친다.
            val size = maxOf(fullSizeOf(g), selectedByGroup.getValue(g).size)
            if (size < 2) { send.add(p); continue }
            val taken = takenByGroup.getOrDefault(g, 0)
            if (taken >= per) continue
            takenByGroup[g] = taken + 1
            send.add(p)
            if (taken == 0) { sampledGroups++; expandedTotal += size }
        }
        return SamplePlan(send, sampledGroups, expandedTotal)
    }

    /**
     * 경로에 붙을 태그를 묶음 전원으로 되편다 — 공유 불변식의 전개 자리.
     *
     * - [membersBySentPath]는 **적용 직전에 살아 있는 링크에서 뜬 명단**이어야 한다
     *   (경로 → 그 경로가 속한 묶음 전원). 실행 시점 표를 들고 다니면 검토 중 링크를
     *   바꿨을 때 낡은 명단에 붙는다.
     * - 표에 없는 경로(미링크)는 그대로 통과한다.
     * - 한 그룹에서 여러 장이 각자 태그를 받았으면 **합집합**이 전원에 붙는다
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
