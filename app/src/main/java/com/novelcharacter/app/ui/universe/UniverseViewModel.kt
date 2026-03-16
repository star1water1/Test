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

    private val universeRepository = (application as NovelCharacterApp).universeRepository
    val allUniverses: LiveData<List<Universe>> = universeRepository.allUniverses

    // 각 세계관의 작품 수, 필드 수를 캐시
    private val _universeNovelCounts = MutableLiveData<Map<Long, Int>>()
    val universeNovelCounts: LiveData<Map<Long, Int>> = _universeNovelCounts

    private val _universeFieldCounts = MutableLiveData<Map<Long, Int>>()
    val universeFieldCounts: LiveData<Map<Long, Int>> = _universeFieldCounts

    fun loadCounts(universes: List<Universe>) = viewModelScope.launch {
        val ids = universes.map { it.id }
        _universeNovelCounts.value = universeRepository.getNovelCountsByUniverses(ids)
        _universeFieldCounts.value = universeRepository.getFieldCountsByUniverses(ids)
    }

    fun insertUniverse(universe: Universe) = viewModelScope.launch {
        universeRepository.insertUniverse(universe)
    }

    fun updateUniverse(universe: Universe) = viewModelScope.launch {
        universeRepository.updateUniverse(universe)
    }

    fun deleteUniverse(universe: Universe) = viewModelScope.launch {
        universeRepository.deleteUniverse(universe)
    }

    fun getPresetTemplates(): List<PresetTemplates.PresetTemplate> =
        PresetTemplates.getTemplates()

    // SingleLiveEvent pattern: wrap value so observer consumes it only once
    class Event<out T>(private val content: T) {
        private var hasBeenHandled = false
        fun getContentIfNotHandled(): T? = if (hasBeenHandled) null else { hasBeenHandled = true; content }
    }

    private val _presetApplied = MutableLiveData<Event<String>?>()
    val presetApplied: LiveData<Event<String>?> = _presetApplied

    fun applyPreset(template: PresetTemplates.PresetTemplate) =
        viewModelScope.launch {
            val universeId = universeRepository.insertUniverse(template.universe)
            val fieldsWithId = template.fields.map { it.copy(universeId = universeId) }
            universeRepository.insertAllFields(fieldsWithId)
            _presetApplied.value = Event(template.universe.name)
        }
}
