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
    suspend fun deleteNovel(novel: Novel) {
        // 이미지 파일 경로를 트랜잭션 전에 파싱 (DB 삭제 후에는 접근 불가)
        val imageFiles = parseImagePaths(novel.imagePaths)

        db.withTransaction {
            recentActivityDao.deleteByEntity(RecentActivity.TYPE_NOVEL, novel.id)
            // 이 작품을 이미지로 참조하는 세계관의 댕글링 참조 정리
            db.universeDao().clearImageNovelRef(novel.id)
            novelDao.delete(novel)
        }

        // DB 트랜잭션 성공 후 디스크에서 이미지 파일 삭제
        for (file in imageFiles) {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.w("NovelRepository", "Failed to delete image: ${file.absolutePath}", e)
            }
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
