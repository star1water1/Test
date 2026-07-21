package com.novelcharacter.app.util

import android.content.Context
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.excel.ImageZipHelper
import java.io.File

/**
 * 이미지 파일 삭제 통합 가드.
 *
 * 경로 공유 배정(하나의 파일을 여러 엔티티가 참조)과 라이브러리(image_meta) 도입 이후,
 * 어떤 삭제 경로도 "다른 곳이 아직 쓰는 파일"이나 "라이브러리 관리 파일"을 지워서는 안 된다.
 * 보호 집합 = 엔티티(캐릭터/작품/세계관) 참조 ∪ 휴지통 보류 ∪ image_meta ∪ 드래프트(컨텍스트 있을 때).
 * 비교는 canonical 경로로 한다(저장은 absolutePath — 코드베이스 관례).
 *
 * 적용처: 캐릭터 편집창 제거 정리, 휴지통 purge(pruneIfNeeded 자동 발화 포함),
 * 작품/세계관 삭제. 이미지 탭의 명시적 삭제는 예외(참조·meta·파일을 함께 제거하는 의도된 파괴).
 */
object ImageOwnershipGuard {

    /**
     * 보호 집합 조합(순수 함수) — 모든 입력을 canonical로 정규화해 합친다.
     * canonical 변환 실패 경로는 원문 그대로 포함(보수적 — 보호가 삭제보다 안전).
     */
    fun computeProtected(vararg pathSets: Collection<String>): Set<String> {
        val result = HashSet<String>()
        for (set in pathSets) {
            for (p in set) {
                val canon = runCatching { File(p).canonicalPath }.getOrNull() ?: p
                result.add(canon)
            }
        }
        return result
    }

    /**
     * 현재 보호 집합을 수집한다.
     * @param context null이면 드래프트 보호는 생략된다(Context 없는 저장소 계층 — 문서화된 트레이드오프).
     * @param excludeTrashSnapshotId 이 스냅샷 자신의 보류분은 보호에서 제외(purge 대상이므로).
     *        다른 스냅샷의 보류분은 계속 보호된다.
     */
    suspend fun collectProtectedPaths(
        db: AppDatabase,
        context: Context?,
        excludeTrashSnapshotId: Long? = null
    ): Set<String> {
        val gson = Gson()
        val entityPaths = ImageZipHelper.collectAllImagePaths(db, gson)
        val metaPaths = runCatching { db.imageMetaDao().getAllPaths() }.getOrDefault(emptyList())
        val draftPaths = context?.let { CharacterDraftPrefs.collectAllDraftImagePaths(it) } ?: emptySet()
        val trashPaths = collectTrashPaths(db, gson, excludeTrashSnapshotId)
        return computeProtected(entityPaths, metaPaths, draftPaths, trashPaths)
    }

    private suspend fun collectTrashPaths(db: AppDatabase, gson: Gson, excludeId: Long?): Set<String> {
        if (excludeId == null) return StorageAnalyzer.collectTrashHeldPaths(db, gson)
        val result = mutableSetOf<String>()
        val snapshots = runCatching { db.trashSnapshotDao().getAllList() }.getOrDefault(emptyList())
        for (snap in snapshots) {
            if (snap.id == excludeId) continue
            val json = snap.imagePaths
            if (json.isBlank() || json == "[]") continue
            val paths = runCatching {
                gson.fromJson<List<String?>>(json, GsonTypes.STRING_LIST)
            }.getOrNull() ?: continue
            paths.filterNotNull().forEach { result.add(it) }
        }
        return computeProtected(result)
    }

    /**
     * 후보들 중 보호되지 않은 파일만 삭제한다. @return 실제 삭제 수.
     * 보호된 후보는 그대로 남는다(공유 참조 유지 또는 라이브러리 미배정으로 잔존).
     */
    suspend fun deleteIfUnprotected(
        db: AppDatabase,
        context: Context?,
        candidates: Collection<String>,
        excludeTrashSnapshotId: Long? = null
    ): Int {
        if (candidates.isEmpty()) return 0
        val protectedSet = collectProtectedPaths(db, context, excludeTrashSnapshotId)
        var deleted = 0
        for (p in candidates) {
            val canon = runCatching { File(p).canonicalPath }.getOrNull() ?: p
            if (canon in protectedSet) continue
            val f = File(p)
            if (runCatching { f.exists() && f.delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    /**
     * 삭제 대신 입양: 소유자·meta가 없는(=이대로 두면 고아가 되어 이후 정리에 지워질) 후보를
     * 라이브러리(미배정)로 편입한다. "항상 미배정 복귀" 편집창 정책용. @return 입양 수.
     */
    suspend fun adoptOrphans(db: AppDatabase, context: Context?, candidates: Collection<String>): Int {
        if (candidates.isEmpty()) return 0
        val protectedSet = collectProtectedPaths(db, context)
        var adopted = 0
        val now = System.currentTimeMillis()
        for (p in candidates) {
            val canon = runCatching { File(p).canonicalPath }.getOrNull() ?: p
            if (canon in protectedSet) continue          // 아직 참조·보류·meta 보호 중 — 입양 불필요
            if (!File(p).exists()) continue
            runCatching { db.imageMetaDao().adopt(p, now) }.onSuccess { adopted++ }
        }
        return adopted
    }
}
