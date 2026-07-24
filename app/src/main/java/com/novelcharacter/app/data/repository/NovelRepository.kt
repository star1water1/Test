package com.novelcharacter.app.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.RecentActivity
import java.io.File

class NovelRepository(
    private val db: AppDatabase,
    private val novelDao: NovelDao
) {
    private val recentActivityDao get() = db.recentActivityDao()
    val allNovels: LiveData<List<Novel>> = novelDao.getAllNovels()

    suspend fun getAllNovelsList(): List<Novel> = novelDao.getAllNovelsList()
    suspend fun getNovelById(id: Long): Novel? = novelDao.getNovelById(id)
    /** 여러 작품 일괄 조회 (호출부에서 900개 단위 청크 권장 — SQLite 999-변수 상한). */
    suspend fun getNovelsByIds(ids: List<Long>): List<Novel> = novelDao.getNovelsByIds(ids)
    suspend fun insertNovel(novel: Novel): Long {
        return db.withTransaction {
            val next = if (novel.universeId != null) {
                novelDao.getNextDisplayOrderInUniverse(novel.universeId)
            } else {
                novelDao.getNextDisplayOrderNoUniverse()
            }
            novelDao.insert(novel.copy(displayOrder = next))
        }
    }
    suspend fun updateNovel(novel: Novel) = novelDao.update(novel)

    /**
     * 작품 삭제 — 소속 캐릭터까지 계단식으로 함께 삭제한다.
     * 캐릭터는 삭제 전 개별 휴지통 스냅샷을 남겨 복원 가능하게 한다(변수 제어 — 말없는 유실 방지).
     */
    suspend fun deleteNovel(novel: Novel) {
        val trash = TrashRepository(db)
        // 이미지 파일 경로를 트랜잭션 전에 파싱 (DB 삭제 후에는 접근 불가)
        val imageFiles = parseImagePaths(novel.imagePaths)

        db.withTransaction {
            // 소속 캐릭터 계단식 삭제 (휴지통 스냅샷 후 삭제 — 태그/필드값/관계 등은 FK CASCADE)
            val characterIds = db.characterDao().getCharactersByNovelList(novel.id).map { it.id }
            CharacterRepository.deleteCharactersCascade(db, trash, characterIds)

            recentActivityDao.deleteByEntity(RecentActivity.TYPE_NOVEL, novel.id)
            // 이 작품을 이미지로 참조하는 세계관의 댕글링 참조 정리
            db.universeDao().clearImageNovelRef(novel.id)
            novelDao.delete(novel)
        }

        // 캐릭터 이미지 파일은 휴지통 스냅샷이 살아 있는 동안 유지 — 정리 시점에 함께 삭제
        trash.pruneIfNeeded()

        // DB 트랜잭션 성공 후 디스크에서 이미지 파일 삭제 — 단, 다른 엔티티가 공유 중이거나
        // 라이브러리(image_meta)·휴지통이 보유한 파일은 지우지 않는다(경로 공유 배정 하 무음 파괴 방지).
        // Context 없는 저장소 계층이라 드래프트 보호는 생략된다(가드 문서 참조).
        try {
            com.novelcharacter.app.util.ImageOwnershipGuard.deleteIfUnprotected(
                db, null, imageFiles.map { it.absolutePath }
            )
        } catch (e: Exception) {
            Log.w("NovelRepository", "Guarded image cleanup failed", e)
        }
    }

    private fun parseImagePaths(imagePathsJson: String): List<File> {
        return try {
            val raw: List<String?>? = com.google.gson.Gson().fromJson(imagePathsJson, com.novelcharacter.app.util.GsonTypes.STRING_LIST)
            raw?.filterNotNull()?.map { File(it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>> =
        novelDao.getNovelsByUniverse(universeId)
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel> =
        novelDao.getNovelsByUniverseList(universeId)
    fun searchNovels(query: String): LiveData<List<Novel>> =
        novelDao.searchNovels(sanitizeLikeQuery(query))
    suspend fun updateNovelDisplayOrders(novels: List<Novel>) = novelDao.updateAll(novels)

    suspend fun setPinned(id: Long, isPinned: Boolean) =
        novelDao.setPinned(id, isPinned)
}
