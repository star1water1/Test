package com.novelcharacter.app.util

import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import java.io.File

/**
 * **앱에서 이미지를 지우는 처분의 단일 소스**(B-107 결정 D6).
 *
 * 종전에는 이 처분이 `ImageManagerViewModel.deleteInternal`에 private으로 갇혀 있었다.
 * `_삭제승인/`(폴더 받아오기)이 같은 일을 해야 하는데 규칙을 한 벌 더 적으면 **반드시 갈라진다** —
 * 링크 정리를 한쪽만 하거나, 대표 포인터를 한쪽만 비우거나, 롤백 조건이 달라진다.
 *
 * ### 처분 (셋이 한 트랜잭션)
 *
 * 1. 소유 엔티티(캐릭터·작품·세계관)의 `imagePaths`에서 뺀다.
 * 2. 라이브러리 meta 행을 지운다(태그는 CASCADE로 함께 간다).
 * 3. 파일을 지운다.
 *
 * **파일 삭제가 실패하면 예외를 던져 1·2를 되돌린다.** 그래야 캐릭터가 이미지를 조용히
 * 잃지 않는다 — 파일이 남았는데 참조만 사라지면 그것이 곧 유실이다(개발 의도 2번).
 * 파일이 이미 없는(고아) 경우는 참조만 정리한다.
 *
 * 대표 이미지 포인터는 [withImagePaths]가 같은 트랜잭션에서 정합시킨다(R-32).
 */
object ImageDeletionService {

    /**
     * 이 이미지를 쓰고 있는 엔티티.
     *
     * **호출부가 실어 보낸다** — 이미지 탭은 목록을 만들 때 이미 알고 있고, 폴더 왕복은
     * [buildOwnerIndex]로 한 번에 만든다. 여기서 경로마다 전수를 훑으면 배치 삭제가
     * 이미지 수 × 엔티티 수가 된다.
     */
    data class Owners(
        val characterIds: List<Long> = emptyList(),
        val novelIds: List<Long> = emptyList(),
        val universeIds: List<Long> = emptyList()
    ) {
        val isEmpty: Boolean get() =
            characterIds.isEmpty() && novelIds.isEmpty() && universeIds.isEmpty()

        companion object { val NONE = Owners() }
    }

    /**
     * 경로(canonical) → 소유 엔티티 색인. **전수를 한 번만 훑는다.**
     *
     * 폴더 왕복이 이것을 쓴다: 그쪽 계획은 캐릭터 축만 알아서(`characterIdsByPath`)
     * **작품·세계관이 쓰는 이미지를 지우면 그쪽 참조가 끊긴 채 남는다.** 색인을 여기서
     * 만들어 세 축을 함께 본다.
     */
    suspend fun buildOwnerIndex(db: AppDatabase, gson: Gson = Gson()): Map<String, Owners> {
        val chars = HashMap<String, MutableList<Long>>()
        val novels = HashMap<String, MutableList<Long>>()
        val universes = HashMap<String, MutableList<Long>>()

        fun consume(json: String, id: Long, into: HashMap<String, MutableList<Long>>) {
            val parsed = runCatching { gson.fromJson(json, Array<String>::class.java) }.getOrNull() ?: return
            for (p in parsed) {
                val canon = ImagePathMatch.canonical(p)
                if (canon.isNotEmpty()) into.getOrPut(canon) { mutableListOf() }.add(id)
            }
        }

        for (c in db.characterDao().getAllCharactersList()) consume(c.imagePaths, c.id, chars)
        for (n in db.novelDao().getAllNovelsList()) consume(n.imagePaths, n.id, novels)
        for (u in db.universeDao().getAllUniversesList()) consume(u.imagePaths, u.id, universes)

        val keys = chars.keys + novels.keys + universes.keys
        return keys.associateWith { key ->
            Owners(
                characterIds = chars[key].orEmpty().distinct(),
                novelIds = novels[key].orEmpty().distinct(),
                universeIds = universes[key].orEmpty().distinct()
            )
        }
    }

    /**
     * 지운다. **되돌릴 수 없다** — 부르기 전에 사용자 확인을 받는 것은 호출부의 몫이다(R-4).
     *
     * @param linkGroupId 이 이미지의 링크 그룹. 지운 뒤 그룹이 1장만 남으면 잔존 "링크" 표식이
     *        오해를 부르므로 자동 해제한다(인앱 규약과 같다).
     * @return 확보한 바이트. **실패하면 null**이고 그때는 아무것도 바뀌지 않았다(트랜잭션 롤백).
     */
    suspend fun delete(
        db: AppDatabase,
        path: String,
        owners: Owners,
        linkGroupId: String?,
        gson: Gson = Gson()
    ): Long? = try {
        val file = File(path)
        val canon = ImagePathMatch.canonical(path).ifEmpty { path }
        val existed = file.exists()
        val size = if (existed) file.length() else 0L
        db.withTransaction {
            for (id in owners.characterIds) {
                db.characterDao().getCharacterById(id)?.let { c ->
                    db.characterDao().update(c.withImagePaths(removePath(gson, c.imagePaths, path, canon)))
                }
            }
            for (id in owners.novelIds) {
                db.novelDao().getNovelById(id)?.let { n ->
                    db.novelDao().update(n.copy(imagePaths = removePath(gson, n.imagePaths, path, canon)))
                }
            }
            for (id in owners.universeIds) {
                db.universeDao().getUniverseById(id)?.let { u ->
                    db.universeDao().update(u.copy(imagePaths = removePath(gson, u.imagePaths, path, canon)))
                }
            }
            db.imageMetaDao().deleteByPaths(listOf(path, canon))
            if (linkGroupId != null) db.imageMetaDao().clearGroupIfSingleton(linkGroupId)
            // 마지막에 시도한다 — 실패하면 위의 참조 정리가 통째로 되돌아간다.
            if (existed && !file.delete()) throw java.io.IOException("파일 삭제 실패: $path")
        }
        size
    } catch (_: Exception) {
        null
    }

    private fun removePath(gson: Gson, json: String, path: String, canon: String): String {
        val current = runCatching {
            gson.fromJson(json, Array<String>::class.java)?.toList()
        }.getOrNull().orEmpty()
        val kept = current.filter { it != path && ImagePathMatch.canonical(it) != canon }
        return gson.toJson(kept)
    }
}
