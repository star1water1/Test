package com.novelcharacter.app.util

import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase

/**
 * 뗀 표식([com.novelcharacter.app.data.model.ImageMeta.detachedAt])을 **읽고 쓰는 손**(B-107 D2).
 *
 * **판정은 여기 없다** — 무엇이 뗀 것인가는 [DetachedImageRule]이 정하고, 이 파일은 그 답을
 * DB에 옮긴다. 규칙을 순수 계층에 둔 덕에 진리표가 JVM 단위 테스트로 고정된다
 * (`DetachedImageRuleTest`). 이 파일은 실클래스패스 프로브가 타입 검사한다.
 */
object DetachedImageMarker {

    /**
     * 지금 어떤 캐릭터라도 쓰고 있는 경로의 canonical 집합.
     *
     * 캐릭터 전수를 **한 번** 훑는다 — 경로마다 훑으면 배치 조작(수십 장 일괄 해제)에서
     * 캐릭터 수 × 경로 수가 된다. 목표 규모(캐릭터 수백)에서 한 번은 무해하다
     * (`scalability_performance_2026-07.md` 7장 ② — 비용이 이미지 수 축에 붙지 않는다).
     */
    suspend fun characterOwnedCanonicalPaths(db: AppDatabase, gson: Gson = Gson()): Set<String> {
        val owned = HashSet<String>()
        for (c in db.characterDao().getAllCharactersList()) {
            val parsed = runCatching {
                gson.fromJson(c.imagePaths, Array<String>::class.java)
            }.getOrNull() ?: continue
            // 파싱 실패는 건너뛴다 — 손상된 JSON 하나 때문에 남의 이미지를 서랍에 넣지 않는다.
            // 보수적인 쪽이 유실이 없는 쪽이다(손상 행에는 별도 고지 경로가 이미 있다).
            for (p in parsed) ImagePathMatch.canonical(p).takeIf { it.isNotEmpty() }?.let(owned::add)
        }
        return owned
    }

    /**
     * 뗀 표식을 현행 소유 상태에 맞춘다 — **붙이는 자리와 푸는 자리가 같이 이것을 부른다.**
     *
     * 호출측은 소유를 이미 고친 **뒤에**, 자기가 건드린 경로를 넘긴다. 트랜잭션 안에서 부르면
     * 소유 변경과 표식이 함께 들어가거나 함께 되돌아간다(중간 상태가 남지 않는다).
     */
    suspend fun sync(
        db: AppDatabase,
        candidates: Collection<DetachedImageRule.Candidate>,
        now: Long = System.currentTimeMillis(),
        gson: Gson = Gson()
    ): DetachedImageRule.Decision {
        if (candidates.isEmpty()) return DetachedImageRule.Decision(emptyList(), emptyList())
        val decision = DetachedImageRule.decide(candidates, characterOwnedCanonicalPaths(db, gson))
        if (decision.isEmpty) return decision
        val dao = db.imageMetaDao()
        // 출처가 다른 것끼리 갈라 쓴다 — 보통 한 무리다.
        for ((code, group) in decision.toMark.groupBy { it.fromCode }) {
            dao.markDetachedByPaths(DetachedImageRule.bothForms(group.map { it.path }), now, code)
        }
        if (decision.toClear.isNotEmpty()) {
            dao.clearDetachedByPaths(DetachedImageRule.bothForms(decision.toClear))
        }
        return decision
    }

    /** 한 캐릭터에서 떼는 흔한 경우 — 경로 전부가 같은 출처다. */
    suspend fun sync(
        db: AppDatabase,
        paths: Collection<String>,
        fromCode: String?,
        now: Long = System.currentTimeMillis(),
        gson: Gson = Gson()
    ): DetachedImageRule.Decision =
        sync(db, paths.map { DetachedImageRule.Candidate(it, fromCode) }, now, gson)

    /**
     * 표식을 **판정 없이** 지운다 — 사용자가 서랍에서 "뗀 표식 지우기"를 눌렀거나,
     * 폴더 왕복이 `_미배정/`으로 되돌렸을 때(D5의 '살림' 갈래).
     *
     * 여기만 [sync]의 판정을 타지 않는다. 판정을 태우면 캐릭터가 안 쓰는 이미지는 지워도
     * 곧바로 다시 붙어 **버튼이 듣지 않는다.** 안 쓰면서 서랍에도 두지 않는 것은 사용자의
     * 자유다(D2의 푸는 길 ②).
     */
    suspend fun clearMark(db: AppDatabase, paths: Collection<String>) {
        if (paths.isEmpty()) return
        db.imageMetaDao().clearDetachedByPaths(DetachedImageRule.bothForms(paths))
    }

    /** 표식을 **판정 없이** 붙인다 — `_분리됨/`에서 온 배정 해제(D5의 '판단 안 함' 갈래). */
    suspend fun markDetached(
        db: AppDatabase,
        paths: Collection<String>,
        fromCode: String?,
        now: Long = System.currentTimeMillis()
    ) {
        if (paths.isEmpty()) return
        db.imageMetaDao().markDetachedByPaths(DetachedImageRule.bothForms(paths), now, fromCode)
    }

    /** 지금 뗀 표식이 붙어 있는 경로 전부 — 폴더 받아오기 계획이 이것을 받아 간다(D5). */
    suspend fun detachedPaths(db: AppDatabase): Set<String> =
        db.imageMetaDao().getAllList().filter { it.isDetached }.mapTo(HashSet()) { it.path }
}
