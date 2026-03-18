package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.model.Novel

class NovelRepository(
    private val db: AppDatabase,
    private val novelDao: NovelDao
) {
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
    suspend fun deleteNovel(novel: Novel) = novelDao.delete(novel)
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>> =
        novelDao.getNovelsByUniverse(universeId)
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel> =
        novelDao.getNovelsByUniverseList(universeId)
    fun searchNovels(query: String): LiveData<List<Novel>> =
        novelDao.searchNovels(query)
    suspend fun updateNovelDisplayOrders(novels: List<Novel>) = novelDao.updateAll(novels)
}
