package com.novelcharacter.app.util

/**
 * AI 태그 검토 시트가 고른 태그의 **적용 계획**의 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * 종전에는 이미지판·폴더판 두 함수가 경로마다 `imageTagDao().getTagsByImageList(imageId)`를
 * 물어 *"이 이미지가 이미 가진 태그"*를 읽었다. 폴더판은 고른 폴더 아래 **전량**을 도므로
 * 조회 수가 이미지 수에 비례하고, 그 비용이 **왕복 × 이미지 수**로 붙는다 (B-241).
 * `image_tags.imageId`에는 인덱스가 있어 조회 하나하나가 풀스캔은 아니다 — 없앨 값은 왕복이다.
 *
 * **읽기를 앞으로 모아도 답이 같아야 하는 이유가 이 클래스가 있는 이유다.** 적용 루프는 제
 * 안에서 쓰고(`insertAll`), 같은 이미지가 두 번 돌면 **그 쓰기가 뒤 차례의 '이미 가진 태그'를
 * 바꾼다.** 일괄 조회 하나를 **쓰기를 흉내 내는 메모리 겹**으로 받아 넣은 순서대로 훑으며
 * 갱신하는 것이 그래서 필요하다. 겹이 없으면 같은 태그가 두 번 `fresh`로 세어져 **화면이
 * 사용자에게 큰 수를 말한다**(행은 `(imageId, tag)` 유니크라 늘지 않는다 — 유실은 아니지만
 * 조용히 틀린 고지다. 개발 의도 2번).
 *
 * 계약:
 * - **처리 순서가 곧 [work]의 순서다.** 호출부가 `picked`의 순회 순서를 그대로 넘긴다.
 * - **한 항목 안의 겹도 한 번만 센다** (B-246, 2026.08.16). 종전에는 `tags`가 같은 값을 두 번
 *   들면 `fresh`도 두 번 들어 `tagCount`가 2 올랐는데, 행은 `(imageId, tag)` 유니크라
 *   **실제로 늘어나는 행은 하나다** — 즉 B-241이 *항목 사이*에서 없앤 바로 그 거짓 고지가
 *   *항목 안*에 남아 있었다. 그때 항목 안을 손대지 않은 것은 종전 코드와 글자 그대로 같게
 *   두려던 것이고(성능 판이 계수 성질까지 바꾸지 않는다), 그 판단은 옳았다 — 그래서 이 자리에
 *   남겨 두었던 몫을 여기서 닫는다. **`Insert.freshTags`도 함께 겹이 없어진다**:
 *   같은 태그를 두 번 실어 보내도 DB가 하나로 접으므로, 겹은 쓰기에도 값이 없다.
 * - **id를 못 찾은 경로는 건너뛴다.** 호출부가 `adopt`로 전원의 id를 확보하지만, 못 얻은
 *   경로를 조용히 태그 없이 통과시키지 않으려면 계획에서도 자리가 있어야 한다.
 */
object ImageTagApplyPlanner {

    /** 쓰기 1건 — [imageId]에 [freshTags]를 더한다. 호출부가 `insertAll` 하나로 옮긴다. */
    data class Insert(val path: String, val imageId: Long, val freshTags: List<String>)

    /**
     * @param inserts 쓸 것이 있는 항목만. 없는 항목은 종전 `continue`와 같이 빠진다.
     * @param tagCount 더해진 태그 수 — 종전 `tagCount += fresh.size`와 같은 수다.
     * @param touchedPaths 태그가 하나라도 더해진 **서로 다른** 경로 수.
     */
    data class Plan(
        val inserts: List<Insert>,
        val tagCount: Int,
        val touchedPaths: Int
    )

    /**
     * @param work (경로 → 붙일 태그들). **넣은 순서가 처리 순서다.**
     * @param imageIdByPath 경로 → 라이브러리 행 id (`adopt`가 확보한 것).
     * @param existingByImageId 이미지 id → 지금 그 이미지가 가진 태그. [work]에 실린 id만
     *   담으면 된다 — 그 밖은 어느 판정에도 쓰이지 않는다.
     */
    fun plan(
        work: List<Pair<String, List<String>>>,
        imageIdByPath: Map<String, Long>,
        existingByImageId: Map<Long, Set<String>>
    ): Plan {
        // 겹: 이미지 id → 지금 가진 태그. 같은 이미지가 두 번 돌 때 앞 차례의 쓰기를 반영한다.
        val overlay = HashMap<Long, MutableSet<String>>()
        val inserts = ArrayList<Insert>()
        val touched = LinkedHashSet<String>()
        var tagCount = 0

        for ((path, tags) in work) {
            if (tags.isEmpty()) continue
            val imageId = imageIdByPath[path] ?: continue
            val existing = overlay.getOrPut(imageId) {
                existingByImageId[imageId].orEmpty().toMutableSet()
            }
            // **항목 안의 겹도 한 번만 센다**(위 계약 · B-246) — `distinct()`가 그 자리다.
            // 겹을 지우는 것이 걸러 내기 *뒤*인 것은 일부러다: 앞에 두어도 답은 같지만,
            // 이 줄이 *"이미 가진 것"*과 *"이번에 두 번 적힌 것"*을 갈라 읽히게 한다.
            val fresh = tags.filterNot { it in existing }.distinct()
            if (fresh.isEmpty()) continue

            inserts.add(Insert(path, imageId, fresh))
            tagCount += fresh.size
            touched.add(path)
            existing.addAll(fresh)
        }
        return Plan(inserts, tagCount, touched.size)
    }
}
