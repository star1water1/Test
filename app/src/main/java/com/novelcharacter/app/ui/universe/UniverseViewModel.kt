package com.novelcharacter.app.ui.universe

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.PresetTemplates
import kotlinx.coroutines.launch

class UniverseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as NovelCharacterApp).repository
    val allUniverses: LiveData<List<Universe>> = repository.allUniverses

    // 각 세계관의 작품 수, 필드 수를 캐시
    private val _universeNovelCounts = MutableLiveData<Map<Long, Int>>()
    val universeNovelCounts: LiveData<Map<Long, Int>> = _universeNovelCounts

    private val _universeFieldCounts = MutableLiveData<Map<Long, Int>>()
    val universeFieldCounts: LiveData<Map<Long, Int>> = _universeFieldCounts

    fun loadCounts(universes: List<Universe>) = viewModelScope.launch {
        val ids = universes.map { it.id }
        _universeNovelCounts.value = repository.getNovelCountsByUniverses(ids)
        _universeFieldCounts.value = repository.getFieldCountsByUniverses(ids)
    }

    fun insertUniverse(universe: Universe) = viewModelScope.launch {
        repository.insertUniverse(universe)
    }

    fun updateUniverse(universe: Universe) = viewModelScope.launch {
        repository.updateUniverse(universe)
    }

    fun deleteUniverse(universe: Universe) = viewModelScope.launch {
        repository.deleteUniverse(universe)
    }

    fun getPresetTemplates(): List<PresetTemplates.PresetTemplate> =
        PresetTemplates.getTemplates()

    fun applyPreset(template: PresetTemplates.PresetTemplate, onComplete: () -> Unit) =
        viewModelScope.launch {
            val universeId = repository.insertUniverse(template.universe)
            val fieldsWithId = template.fields.map { it.copy(universeId = universeId) }
            repository.insertAllFields(fieldsWithId)
            onComplete()
        }
}
