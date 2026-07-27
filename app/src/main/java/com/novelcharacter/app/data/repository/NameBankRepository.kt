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

    /** 선택 엔트리 일괄 조회 (IN 절 청크 분할, 입력 순서 보존) */
    suspend fun getByIds(ids: List<Long>): List<NameBankEntry> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(900)
            .flatMap { nameBankDao.getByIds(it) }
            .associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

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
}
