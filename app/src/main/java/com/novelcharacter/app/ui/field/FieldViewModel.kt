package com.novelcharacter.app.ui.field

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.FieldDefinition
import kotlinx.coroutines.launch

class FieldViewModel(application: Application) : AndroidViewModel(application) {

    private val universeRepository = (application as NovelCharacterApp).universeRepository

    private val _universeId = MutableLiveData<Long>()
    val universeId: LiveData<Long> = _universeId

    val fields: LiveData<List<FieldDefinition>> = _universeId.switchMap { id ->
        universeRepository.getFieldsByUniverse(id)
    }

    fun setUniverseId(id: Long) {
        _universeId.value = id
    }

    fun insertField(field: FieldDefinition) = viewModelScope.launch {
        universeRepository.insertField(field)
    }

    fun updateField(field: FieldDefinition) = viewModelScope.launch {
        universeRepository.updateField(field)
    }

    fun deleteField(field: FieldDefinition) = viewModelScope.launch {
        universeRepository.deleteField(field)
    }

    fun updateFieldOrder(fields: List<FieldDefinition>) = viewModelScope.launch {
        fields.forEachIndexed { index, field ->
            universeRepository.updateField(field.copy(displayOrder = index))
        }
    }
}
