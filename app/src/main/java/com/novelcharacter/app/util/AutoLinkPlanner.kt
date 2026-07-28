package com.novelcharacter.app.util

/**
 * 캐릭터 자동 링크("한 캐릭터에 등록된 이미지끼리는 자동으로 같은 캐릭터 묶음") 계획의
 * 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * 핵심 계약:
 * - 자동 그룹 토큰은 "char:<캐릭터id>"([autoToken]) 형태라 수동 링크(UUID 토큰)와 구별된다.
 *   **자동화는 수동 그룹을 절대 건드리지 않는다** — 만들지도, 풀지도, 옮기지도 않는다
 *   (자율성 우선: 사용자의 명시적 링크가 항상 자동화보다 우선한다).
 * - 불변식: 이미지의 char:N 소속은 "그 이미지가 캐릭터 N에 등록되어 있는 동안"만 유효하다.
 *   캐릭터에서 빼면 소속도 풀리고, 토큰이 낡으면(캐릭터 삭제, 가져오기·복원으로 id 재발급)
 *   현재 등록 상태 기준으로 재배정되거나 해제된다 — 그래서 **전체 재동기화([plan])를 어느
 *   시점에 몇 번을 돌려도 안전**하며, 모든 진입점(저장·배정·해제·가져오기)이 같은 계획을 쓴다.
 * - 그룹은 최종 인원 2장 이상일 때만 만든다(1장 링크 배지는 오해 — clearGroupIfSingleton과
 *   같은 기준). 인원이 줄어 1장이 되는 정리는 SQL(clearGroupIfSingleton)이 맡고, 계획은
 *   변동 가능성이 있는 자동 토큰을 [Plan.dirtyAutoTokens]로 알려 준다.
 * - 유효한 기존 소속은 유지한다(안정성) — 여러 캐릭터가 같은 이미지를 공유해도 이미 유효한
 *   소속을 다른 캐릭터가 빼앗아 가지 않는다. 새 귀속이 필요한 경우만 캐릭터 id 오름차순으로
 *   결정적으로 배정한다.
 *
 * 경로는 호출부가 canonical로 정규화해 넘긴다(저장은 absolutePath, 비교만 canonical —
 * 코드베이스 관례. [ImageLinkResolver]와 동일하게 이 계층은 문자열 일치만 본다).
 */
object AutoLinkPlanner {

    /** 자동 그룹 토큰 접두 — UUID 수동 토큰과 형태로 구별된다. */
    const val AUTO_TOKEN_PREFIX = "char:"

    fun autoToken(characterId: Long): String = AUTO_TOKEN_PREFIX + characterId

    fun isAutoToken(groupId: String?): Boolean = groupId?.startsWith(AUTO_TOKEN_PREFIX) == true

    /** "char:N" 토큰의 N. 자동 토큰이 아니거나 숫자가 아니면 null. */
    fun characterIdOf(groupId: String?): Long? =
        if (groupId != null && groupId.startsWith(AUTO_TOKEN_PREFIX)) {
            groupId.substring(AUTO_TOKEN_PREFIX.length).toLongOrNull()
        } else null

    /** 계획 입력 1건: 링크 상태를 아는 이미지(라이브러리 행). 라이브러리 밖 이미지는 안 실어도 된다(=미링크 취급). */
    data class ImageState(val path: String, val groupId: String?)

    /**
     * 계획 결과 — DB 반영은 호출부 몫.
     * @param claims 자동 토큰 → 그 토큰으로 새로 설정할 경로들(이미 그 토큰인 행은 제외 — 무변경 쓰기 없음)
     * @param releases 낡은 자동 소속을 해제(그룹 null)할 경로들 — 수동 그룹은 절대 포함되지 않는다
     * @param dirtyAutoTokens 인원이 변했거나 1장 이하로 남는 자동 토큰 — 반영 후 singleton 정리 대상
     */
    data class Plan(
        val claims: Map<String, List<String>>,
        val releases: List<String>,
        val dirtyAutoTokens: Set<String>
    ) {
        val isEmpty: Boolean get() = claims.isEmpty() && releases.isEmpty() && dirtyAutoTokens.isEmpty()
        val claimedCount: Int get() = claims.values.sumOf { it.size }
    }

    /**
     * 전체 재동기화 계획을 세운다.
     *
     * @param characters (캐릭터 id, 등록 이미지 경로 목록) 전체 — 존재하는 파일만 넘길 것
     *        (소실 파일에 링크를 얹으면 죽은 경로가 배정 확장에 끌려 들어간다)
     * @param states 현재 링크 상태(라이브러리 행). [characters]에 등장하지 않는 경로도
     *        자동 토큰이 낡았으면 해제 대상이 된다.
     */
    fun plan(characters: List<Pair<Long, List<String>>>, states: List<ImageState>): Plan {
        val groupOf = HashMap<String, String?>(states.size)
        // 판정 대상 전체 — 입력 순서를 보존해 산출(claims/releases 목록)이 결정적이게 한다
        val allPaths = LinkedHashSet<String>()
        for (s in states) {
            groupOf[s.path] = s.groupId
            allPaths.add(s.path)
        }

        // 경로 → 등록 캐릭터 id 집합 (자동 소속의 유효성 판정용)
        val registrants = HashMap<String, MutableSet<Long>>()
        for ((id, paths) in characters) {
            for (p in paths) {
                registrants.getOrPut(p) { HashSet() }.add(id)
                allPaths.add(p)
            }
        }

        // 수동 그룹 경로는 어떤 판정에서도 제외한다. 유효한 자동 소속은 미리 확정(안정성).
        val locked = HashSet<String>()
        val assigned = HashMap<String, String>()   // 경로 → 최종 자동 토큰
        for ((path, g) in groupOf) {
            if (g == null) continue
            if (!isAutoToken(g)) {
                locked.add(path)
            } else {
                val owner = characterIdOf(g)
                if (owner != null && registrants[path]?.contains(owner) == true) assigned[path] = g
            }
        }

        // 캐릭터 id 오름차순으로 미귀속 경로를 배정한다. 후보(유지분 + 미귀속)가 2장 이상일 때만
        // 그룹이 성립한다 — 한 캐릭터에서 불성립한 경로는 소진되지 않고 다음 캐릭터가 쓸 수 있다
        // (예: 캐릭터 A=[X] 단독, B=[X,Y] — A에서 그룹이 안 만들어져도 B가 X·Y를 묶는다).
        for ((id, paths) in characters.sortedBy { it.first }) {
            val token = autoToken(id)
            val candidates = LinkedHashSet<String>()
            for (p in paths) {
                if (p in locked) continue
                val cur = assigned[p]
                if (cur == null || cur == token) candidates.add(p)
            }
            if (candidates.size < 2) continue
            for (p in candidates) if (p !in assigned) assigned[p] = token
        }

        // 산출: 현재 상태와의 차이만 낸다. 낡은 자동 소속은 재귀속(claims) 아니면 해제(releases)로
        // 반드시 끝난다. dirty에는 옮겨 나간 원 토큰·새 토큰·1장 이하로 남는 토큰을 모두 담는다.
        val finalCount = HashMap<String, Int>()
        for (t in assigned.values) finalCount[t] = (finalCount[t] ?: 0) + 1

        val claims = LinkedHashMap<String, MutableList<String>>()
        val releases = ArrayList<String>()
        val dirty = HashSet<String>()

        for (path in allPaths) {
            if (path in locked) continue
            val current = groupOf[path]
            val target = assigned[path]
            if (target != current) {
                if (target == null) {
                    releases.add(path)
                } else {
                    claims.getOrPut(target) { mutableListOf() }.add(path)
                    dirty.add(target)
                }
                if (isAutoToken(current)) dirty.add(current!!)
            }
        }
        // 유지되지만 1장 이하로 남는 토큰(동료가 빠져나간 경우) — singleton 정리를 예약한다
        for ((token, count) in finalCount) if (count <= 1) dirty.add(token)

        return Plan(claims, releases, dirty)
    }
}
