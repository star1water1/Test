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

    private val repository = (application as NovelCharacterApp).repository

    private val _universeId = MutableLiveData<Long>()
    val universeId: LiveData<Long> = _universeId

    val fields: LiveData<List<FieldDefinition>> = _universeId.switchMap { id ->
        repository.getFieldsByUniverse(id)
    }

    fun setUniverseId(id: Long) {
        _universeId.value = id
    }

    fun insertField(field: FieldDefinition) = viewModelScope.launch {
        repository.insertField(field)
    }

    fun updateField(field: FieldDefinition) = viewModelScope.launch {
        repository.updateField(field)
    }

    fun deleteField(field: FieldDefinition) = viewModelScope.launch {
        repository.deleteField(field)
    }

    fun updateFieldOrder(fields: List<FieldDefinition>) = viewModelScope.launch {
        fields.forEachIndexed { index, field ->
            repository.updateField(field.copy(displayOrder = index))
        }
    }
}
