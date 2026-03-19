package com.novelcharacter.app.ui.field

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.FieldDefinition
import android.util.Log
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
}
