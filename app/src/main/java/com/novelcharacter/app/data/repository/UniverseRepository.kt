package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.FieldDefinitionDao
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.dao.UniverseDao
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe

class UniverseRepository(
    private val universeDao: UniverseDao,
    private val fieldDefinitionDao: FieldDefinitionDao,
    private val novelDao: NovelDao
) {
    // ===== Universe =====
    val allUniverses: LiveData<List<Universe>> = universeDao.getAllUniverses()

    suspend fun getAllUniversesList(): List<Universe> = universeDao.getAllUniversesList()
    suspend fun getUniverseById(id: Long): Universe? = universeDao.getUniverseById(id)
    suspend fun insertUniverse(universe: Universe): Long = universeDao.insert(universe)
    suspend fun updateUniverse(universe: Universe) = universeDao.update(universe)
    suspend fun deleteUniverse(universe: Universe) = universeDao.delete(universe)

    // ===== FieldDefinition =====
    fun getFieldsByUniverse(universeId: Long): LiveData<List<FieldDefinition>> =
        fieldDefinitionDao.getFieldsByUniverse(universeId)

    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByUniverseList(universeId)

    suspend fun getFieldById(id: Long): FieldDefinition? =
        fieldDefinitionDao.getFieldById(id)

    suspend fun getFieldByKey(universeId: Long, key: String): FieldDefinition? =
        fieldDefinitionDao.getFieldByKey(universeId, key)

    suspend fun getFieldsByType(universeId: Long, type: String): List<FieldDefinition> =
        fieldDefinitionDao.getFieldsByType(universeId, type)

    suspend fun getGroupNames(universeId: Long): List<String> =
        fieldDefinitionDao.getGroupNames(universeId)

    suspend fun insertField(field: FieldDefinition): Long =
        fieldDefinitionDao.insert(field)

    suspend fun insertAllFields(fields: List<FieldDefinition>) =
        fieldDefinitionDao.insertAll(fields)

    suspend fun updateField(field: FieldDefinition) =
        fieldDefinitionDao.update(field)

    suspend fun updateFieldsOrder(fields: List<FieldDefinition>) =
        fieldDefinitionDao.updateAll(fields)

    suspend fun deleteField(field: FieldDefinition) =
        fieldDefinitionDao.delete(field)

    suspend fun deleteAllFieldsByUniverse(universeId: Long) =
        fieldDefinitionDao.deleteAllByUniverse(universeId)

    // ===== Batch count queries =====
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        novelDao.getNovelCountsByUniverses(universeIds).associate { it.universeId to it.cnt }

    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): Map<Long, Int> =
        fieldDefinitionDao.getFieldCountsByUniverses(universeIds).associate { it.universeId to it.cnt }
}
