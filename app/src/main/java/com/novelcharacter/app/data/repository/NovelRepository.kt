package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.dao.CharacterDao
import com.novelcharacter.app.data.dao.NameBankDao
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.model.Novel

class NovelRepository(
    private val db: AppDatabase,
    private val novelDao: NovelDao,
    private val characterDao: CharacterDao,
    private val nameBankDao: NameBankDao
) {
    val allNovels: LiveData<List<Novel>> = novelDao.getAllNovels()

    suspend fun getAllNovelsList(): List<Novel> = novelDao.getAllNovelsList()
    suspend fun getNovelById(id: Long): Novel? = novelDao.getNovelById(id)
    suspend fun insertNovel(novel: Novel): Long = novelDao.insert(novel)
    suspend fun updateNovel(novel: Novel) = novelDao.update(novel)
    suspend fun deleteNovel(novel: Novel) {
        db.withTransaction {
            // Reset name bank usage for all characters belonging to this novel
            val characters = characterDao.getCharactersByNovelList(novel.id)
            for (character in characters) {
                nameBankDao.resetUsageByCharacter(character.id)
            }
            novelDao.delete(novel)
        }
    }
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>> =
        novelDao.getNovelsByUniverse(universeId)
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel> =
        novelDao.getNovelsByUniverseList(universeId)
    fun searchNovels(query: String): LiveData<List<Novel>> =
        novelDao.searchNovels(query)
}
