package com.novelcharacter.app.ui.field

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.PresetTemplates
import android.util.Log
import kotlinx.coroutines.launch

class FieldViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val universeRepository = app.universeRepository
    private val userPresetDao = app.database.userPresetTemplateDao()

    private val _universeId = MutableLiveData<Long>()
    val universeId: LiveData<Long> = _universeId

    val fields: LiveData<List<FieldDefinition>> = _universeId.switchMap { id ->
        universeRepository.getFieldsByUniverse(id)
    }

    fun setUniverseId(id: Long) {
        _universeId.value = id
    }

    fun insertField(field: FieldDefinition) = viewModelScope.launch {
        try {
            universeRepository.insertField(field)
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to insert field", e)
        }
    }

    fun updateField(field: FieldDefinition) = viewModelScope.launch {
        try {
            universeRepository.updateField(field)
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to update field", e)
        }
    }

    fun deleteField(field: FieldDefinition) = viewModelScope.launch {
        try {
            universeRepository.deleteField(field)
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to delete field", e)
        }
    }

    fun updateFieldOrder(fields: List<FieldDefinition>) = viewModelScope.launch {
        try {
            val updated = fields.mapIndexed { index, field -> field.copy(displayOrder = index) }
            universeRepository.updateFieldsOrder(updated)
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to update field order", e)
        }
    }

    /** 다른 세계관의 필드 목록 조회 */
    suspend fun getFieldsFromOtherUniverses(currentUniverseId: Long): Map<Universe, List<FieldDefinition>> {
        val allUniverses = universeRepository.getAllUniversesList()
        val result = mutableMapOf<Universe, List<FieldDefinition>>()
        for (universe in allUniverses) {
            if (universe.id == currentUniverseId) continue
            val fields = universeRepository.getFieldsByUniverseList(universe.id)
            if (fields.isNotEmpty()) {
                result[universe] = fields
            }
        }
        return result
    }

    /** 다른 세계관 + 프리셋의 필드 목록 통합 조회 */
    suspend fun getFieldsFromAllSources(currentUniverseId: Long): Map<String, List<FieldDefinition>> {
        val result = linkedMapOf<String, List<FieldDefinition>>()

        // 1. 다른 세계관
        val allUniverses = universeRepository.getAllUniversesList()
        for (universe in allUniverses) {
            if (universe.id == currentUniverseId) continue
            val fields = universeRepository.getFieldsByUniverseList(universe.id)
            if (fields.isNotEmpty()) {
                result[universe.name] = fields
            }
        }

        // 2. 내장 프리셋 템플릿
        for (preset in PresetTemplates.getBuiltInTemplates()) {
            val label = "${preset.universe.name} (프리셋)"
            result[label] = preset.fields
        }

        // 3. 사용자 정의 프리셋
        val userPresets = userPresetDao.getAllTemplatesList()
        for (preset in userPresets) {
            val template = PresetTemplates.fromUserPreset(preset)
            val label = "${template.universe.name} (사용자 프리셋)"
            result[label] = template.fields
        }

        return result
    }

    /** 현재 세계관의 필드 키 목록 조회 */
    suspend fun getCurrentFieldKeys(universeId: Long): Set<String> {
        return universeRepository.getFieldsByUniverseList(universeId).map { it.key }.toSet()
    }

    /** 선택된 필드를 현재 세계관으로 복사 */
    fun importFields(targetUniverseId: Long, sourceFields: List<FieldDefinition>) = viewModelScope.launch {
        try {
            val currentFields = universeRepository.getFieldsByUniverseList(targetUniverseId)
            val existingKeys = currentFields.map { it.key }.toSet()
            val maxOrder = currentFields.maxOfOrNull { it.displayOrder } ?: -1

            val newFields = sourceFields.mapIndexedNotNull { index, field ->
                if (field.key in existingKeys) return@mapIndexedNotNull null
                field.copy(
                    id = 0,
                    universeId = targetUniverseId,
                    displayOrder = maxOrder + 1 + index
                )
            }
            if (newFields.isNotEmpty()) {
                universeRepository.insertAllFields(newFields)
            }
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to import fields", e)
        }
    }
}
