package com.novelcharacter.app.util

/**
 * "이미지" 시트가 배정한 **링크 묶음 반영 계획**의 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * 종전에는 가져오기가 토큰마다 `imageMetaDao().getByGroup(token)`을 물어 *"이 묶음이 최종
 * 인원 2장 이상인가"*를 판정했다. 토큰 수는 최악의 경우 행 수와 같고(행마다 제 묶음이면),
 * `linkGroupId`에 인덱스가 있어 풀스캔은 아니지만 비용이 **왕복 × 토큰 수**로 붙는다 (B-239).
 *
 * **이 자리를 읽기 전 일괄 조회로 그냥 바꿀 수 없는 것이 이 클래스가 있는 이유다.**
 * 반영 루프는 제 안에서 쓰고(`setGroup(ids, token)`), **그 쓰기가 뒤 토큰의 답을 바꾼다** —
 * 이미지가 묶음 X에서 Y로 옮겨 가면 Y를 처리한 뒤의 X 조회는 그것을 식구로 세지 않는다.
 * 그래서 일괄 조회 하나를 **쓰기를 그대로 흉내 내는 메모리 겹**으로 받아, 토큰을 넣은 순서대로
 * 훑으며 겹을 갱신한다. 겹이 없으면 `>= 2` 가드의 답이 조용히 달라진다(1장짜리 묶음을 만들지
 * 않으려는 가드다 — 유실은 아니지만 **조용한 행동 변화라 더 나쁘다**).
 *
 * 계약:
 * - **처리 순서가 곧 [desired]의 순서다.** 호출부가 `LinkedHashMap`으로 넘겨 시트 행 순서가
 *   유지되며, 순서가 갈리면 위 이동 사례에서 답이 갈린다.
 * - **가드는 SQL 시절과 글자 그대로 같다** — `(시트가 든 id + 지금 그 토큰의 식구).toSet().size >= 2`.
 * - **쓸 것이 없으면 쓰지 않는다.** 시트가 든 id가 전부 이미 그 토큰이면 `setGroup`은 같은 값을
 *   다시 적을 뿐이라 내용이 바뀌지 않는다 — 같은 백업을 다시 들이는 흔한 경로에서 쓰기가
 *   통째로 사라진다. 겹이 있어 **"이미 그 토큰인가"를 왕복 없이 알 수 있는 것**이 그 근거다.
 */
object ImageLinkGroupPlanner {

    /** 쓰기 1건 — [ids]를 [token] 묶음으로 옮긴다. 호출부가 `setGroup(ids, token)` 하나로 옮긴다. */
    data class Assignment(val token: String, val ids: List<Long>)

    /**
     * @param desired 시트가 배정한 (묶음 토큰 → 이미지 id들). **넣은 순서가 처리 순서다.**
     * @param currentByToken 해제 반영 뒤 DB의 (묶음 토큰 → 그 토큰의 현행 식구 id들).
     *   [desired]의 토큰만 실으면 된다 — 그 밖의 토큰은 어느 판정에도 쓰이지 않는다.
     *   **읽는 시점이 규약이다:** 해제(`setGroup(…, null)`·singleton 정리)를 반영한 *뒤*여야
     *   종전 `getByGroup`이 보던 것과 같은 상태다.
     */
    fun plan(
        desired: Map<String, List<Long>>,
        currentByToken: Map<String, Set<Long>>
    ): List<Assignment> {
        // 겹: 토큰 → 식구. 그리고 그 역색인(id → 지금 속한 토큰) — 이동이 앞 토큰의 식구를
        // 줄이는 것을 왕복 없이 반영하려면 역방향이 있어야 한다.
        val membersByToken = HashMap<String, MutableSet<Long>>()
        val tokenOfId = HashMap<Long, String>()
        for ((token, ids) in currentByToken) {
            membersByToken[token] = ids.toMutableSet()
            for (id in ids) tokenOfId[id] = token
        }

        val plan = mutableListOf<Assignment>()
        for ((token, ids) in desired) {
            val existing = membersByToken[token].orEmpty()
            // 1장짜리 묶음은 만들지 않는다 — SQL 시절의 그 가드다.
            if ((ids + existing).toSet().size < 2) continue
            // 이미 전원이 그 토큰이면 쓸 것이 없다(내용이 같은 UPDATE).
            if (ids.all { tokenOfId[it] == token }) continue

            plan.add(Assignment(token, ids))
            // 겹에도 그대로 반영한다 — 뒤 토큰의 가드가 이 쓰기에 달렸다.
            for (id in ids) {
                tokenOfId[id]?.let { prev -> if (prev != token) membersByToken[prev]?.remove(id) }
                membersByToken.getOrPut(token) { mutableSetOf() }.add(id)
                tokenOfId[id] = token
            }
        }
        return plan
    }
}
