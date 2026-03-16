package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.NameBankDao
import com.novelcharacter.app.data.model.NameBankEntry

class NameBankRepository(
    private val nameBankDao: NameBankDao
) {
    val allNameBankEntries: LiveData<List<NameBankEntry>> = nameBankDao.getAllNames()
    val availableNameBankEntries: LiveData<List<NameBankEntry>> = nameBankDao.getAvailableNames()

    suspend fun getAvailableNameBankList(): List<NameBankEntry> =
        nameBankDao.getAvailableNamesList()

    suspend fun getAllNameBankList(): List<NameBankEntry> =
        nameBankDao.getAllNamesList()

    suspend fun insertNameBankEntry(entry: NameBankEntry): Long =
        nameBankDao.insert(entry)

    suspend fun updateNameBankEntry(entry: NameBankEntry) =
        nameBankDao.update(entry)

    suspend fun deleteNameBankEntry(entry: NameBankEntry) =
        nameBankDao.delete(entry)

    suspend fun markNameBankAsUsed(id: Long, characterId: Long) =
        nameBankDao.markAsUsed(id, characterId)

    suspend fun markNameBankAsAvailable(id: Long) =
        nameBankDao.markAsAvailable(id)

    suspend fun resetUsageByCharacter(characterId: Long) =
        nameBankDao.resetUsageByCharacter(characterId)
}
