package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.ImageMeta

/**
 * 입양(insert-or-get)을 **목록 단위로** 접는 순수 판정 (Android 무의존 — JVM 테스트로 검증. B-244).
 *
 * 종전에는 `ImageMetaDao.adopt(path, now)`를 **경로마다** 불렀다. 그 함수는 삽입-또는-조회라
 * 행이 이미 있으면 `insert`(실패) → `promoteToUserByPaths` → `getByPath`로 **한 번에 왕복 셋**을
 * 치르고, 부르는 자리 대부분이 경로 목록을 돌았다(2026.08.16 실측 열한 곳). 폴더판 태그 적용처럼
 * `N`이 *고른 폴더 아래 이미지 전량*인 자리에는 상한이 없어, 비용이 **왕복 × 3N**으로 붙었다.
 * 이 계획을 지나면 목록 길이와 무관하게 **읽기 하나 · 삽입 하나 · 승격 하나**다.
 *
 * **왜 판정을 따로 꺼냈는가 — 틀려도 DB를 봐서는 안 보이는 부류이기 때문이다.**
 * 이 자리가 잘못되면 나타나는 모양은 셋이고 **셋 다 조용하다**:
 * ⓐ 승격 대상을 빠뜨리면 자동 반납([ImageMetaDao.sweepBareAutoAdopted])이 **사용자가 태그를 붙인
 * 행을 지운다** — 지워진 뒤에는 그것이 자동 행이었는지 알 길이 없다.
 * ⓑ 같은 경로가 목록에 두 번 들면 id가 갈릴 수 있고, 그러면 뒤따르는 태그·링크가 **엉뚱한 행**에
 * 붙는다(행은 `path` 유니크라 DB는 멀쩡해 보인다).
 * ⓒ 삽입이 경합에 져도(`rowId == -1`) 그 경로를 조용히 빠뜨리면 호출부가 **id 없이 통과**한다.
 * 셋 다 오류도 로그도 남기지 않으므로 시험이 아니면 잡히지 않는다(개발 의도 2번 — 변수 제어).
 *
 * 계약:
 * - **입력 순서가 곧 답의 순서다.** 중복은 첫 자리에서 접는다 — 옛 루프도 같은 경로를 두 번
 *   만나면 두 번째에 같은 id를 돌려받았으므로 답이 글자 그대로 같다.
 * - **승격은 *이미 있던* 행에만 붙는다**([ImageMetaDao.adopt]의 성질). 새로 넣는 행은 애초에
 *   사용자 소유(`adoptSource = null`)라 승격이 무의미하고, 경합으로 남이 넣은 행은 *이미 있던*
 *   쪽이라 승격 대상이다 — 옛 코드에서 `insert` 실패가 곧 `promoteToUserByPaths`였던 그 자리다.
 * - **자동 입양([ImageMetaDao.adoptAuto])은 승격하지 않는다.** 자동화는 사용자 의사 표시가
 *   아니라는 그 함수의 판단을 그대로 옮긴다 — [Split.promote]가 그 갈림이다.
 * - **id를 얻지 못한 경로는 답에서 빠진다.** 조용히 넣어 주지 않는다 — 호출부가 그 자리를
 *   보고 제 실패 경로로 보낼 수 있어야 한다(옛 `requireNotNull`이 던지던 자리).
 */
object ImageAdoptionPlanner {

    /** 삽입이 경합에 졌다는 Room의 표식 — `OnConflictStrategy.IGNORE`가 돌려주는 rowId. */
    const val NOT_INSERTED = -1L

    /**
     * 1단계 — 중복을 접고, **이미 있는 것**과 **새로 넣을 것**을 가른다.
     *
     * @param promote `adopt` 계열이면 true, `adoptAuto` 계열이면 false.
     */
    data class Split(
        val wanted: List<String>,
        val existingIdByPath: Map<String, Long>,
        val fresh: List<String>,
        val promote: Boolean
    )

    /** 최종 답 — [idByPath]는 [Split.wanted] 순서다. */
    data class Resolved(
        val idByPath: Map<String, Long>,
        val promotePaths: List<String>,
        /** 어느 단계에서도 id를 얻지 못한 경로. 비어 있는 것이 정상이다. */
        val unresolved: List<String>
    )

    /**
     * @param paths 입양할 경로. 중복이 있어도 되고 순서가 보존된다.
     * @param existing 그 경로들로 물은 `getByPaths`의 결과. [paths] 밖의 행이 섞여 있어도
     *   무시한다 — 호출부가 청크로 갈라 물어도 답이 같아야 한다.
     */
    fun split(paths: Collection<String>, existing: List<ImageMeta>, promote: Boolean): Split {
        val wanted = paths.toCollection(LinkedHashSet()).toList()
        if (wanted.isEmpty()) return Split(emptyList(), emptyMap(), emptyList(), promote)

        val wantedSet = wanted.toHashSet()
        val existingIdByPath = LinkedHashMap<String, Long>(existing.size)
        for (m in existing) if (m.path in wantedSet) existingIdByPath[m.path] = m.id

        val fresh = wanted.filterNot { it in existingIdByPath }
        return Split(wanted, existingIdByPath, fresh, promote)
    }

    /**
     * 2단계 — 삽입이 **경합에 진** 경로를 고른다. 그 사이 다른 연결이 같은 경로를 넣었다는 뜻이라,
     * 호출부가 그것만 다시 물어 [resolve]에 넘긴다.
     *
     * `rowIds`가 [Split.fresh]보다 짧으면 남은 자리를 전부 경합으로 본다 — 답을 모르는 것을
     * 아는 척하지 않는 쪽이 안전하다(모자란 자리는 다시 물으면 실재가 나온다).
     */
    fun raced(split: Split, rowIds: List<Long>): List<String> =
        split.fresh.filterIndexed { i, _ -> rowIds.getOrElse(i) { NOT_INSERTED } == NOT_INSERTED }

    /**
     * 3단계 — 셋을 접어 답을 낸다.
     *
     * @param rowIds [Split.fresh]와 자리를 맞춘 `insertAll`의 결과.
     * @param recovered [raced]가 고른 경로를 다시 물은 결과. 경합이 없었으면 빈 목록이다.
     */
    fun resolve(split: Split, rowIds: List<Long>, recovered: List<ImageMeta>): Resolved {
        if (split.wanted.isEmpty()) return Resolved(emptyMap(), emptyList(), emptyList())

        val insertedIdByPath = HashMap<String, Long>(split.fresh.size)
        split.fresh.forEachIndexed { i, path ->
            val rowId = rowIds.getOrElse(i) { NOT_INSERTED }
            if (rowId != NOT_INSERTED) insertedIdByPath[path] = rowId
        }

        // 경합으로 회수한 행은 **이미 있던 행**이다 — 승격 대상이 되는 것이 그래서다.
        val racedSet = raced(split, rowIds).toHashSet()
        val recoveredIdByPath = LinkedHashMap<String, Long>()
        for (m in recovered) if (m.path in racedSet) recoveredIdByPath[m.path] = m.id

        val idByPath = LinkedHashMap<String, Long>(split.wanted.size)
        val unresolved = ArrayList<String>()
        for (path in split.wanted) {
            val id = split.existingIdByPath[path] ?: insertedIdByPath[path] ?: recoveredIdByPath[path]
            if (id != null) idByPath[path] = id else unresolved.add(path)
        }

        // 승격은 **이미 있던 행**(처음부터 있던 것 + 경합으로 드러난 것)에만 붙는다.
        // `wanted` 순서로 훑는 것은 청크가 갈려도 보내는 순서가 같게 하려는 것이다.
        val promotePaths = if (!split.promote) emptyList() else split.wanted.filter {
            it in split.existingIdByPath || it in recoveredIdByPath
        }
        return Resolved(idByPath, promotePaths, unresolved)
    }
}
