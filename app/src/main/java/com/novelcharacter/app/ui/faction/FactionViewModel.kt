package com.novelcharacter.app.ui.faction

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import kotlinx.coroutines.launch

class FactionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val factionRepository = app.factionRepository
    private val characterRepository = app.characterRepository

    private val _universeId = MutableLiveData<Long>()
    val universeId: LiveData<Long> = _universeId

    val factions: LiveData<List<Faction>> = _universeId.switchMap { id ->
        factionRepository.getFactionsByUniverse(id)
    }

    private val _selectedFactionId = MutableLiveData<Long>()

    val memberships: LiveData<List<FactionMembership>> = _selectedFactionId.switchMap { id ->
        factionRepository.getMembershipsByFaction(id)
    }

    fun setUniverseId(id: Long) {
        _universeId.value = id
    }

    fun setSelectedFactionId(id: Long) {
        _selectedFactionId.value = id
    }

    fun insertFaction(faction: Faction) = viewModelScope.launch {
        try {
            factionRepository.insertFaction(faction)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to insert faction", e)
        }
    }

    fun updateFaction(faction: Faction) = viewModelScope.launch {
        try {
            factionRepository.updateFaction(faction)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to update faction", e)
        }
    }

    fun deleteFaction(faction: Faction, deleteRelationships: Boolean = true) = viewModelScope.launch {
        try {
            factionRepository.deleteFaction(faction, deleteRelationships)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to delete faction", e)
        }
    }

    fun updateFactionOrder(factions: List<Faction>) = viewModelScope.launch {
        try {
            val updated = factions.mapIndexed { index, f -> f.copy(displayOrder = index) }
            factionRepository.updateFactionDisplayOrders(updated)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to update faction order", e)
        }
    }

    fun addMember(factionId: Long, characterId: Long, joinYear: Int? = null) = viewModelScope.launch {
        try {
            factionRepository.addMember(factionId, characterId, joinYear)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to add member", e)
        }
    }

    fun addMembers(factionId: Long, characterIds: List<Long>, joinYear: Int?, onResult: (Int) -> Unit = {}) = viewModelScope.launch {
        try {
            val count = factionRepository.addMembers(factionId, characterIds, joinYear)
            onResult(count)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to add members", e)
            onResult(0)
        }
    }

    fun removeMember(factionId: Long, characterId: Long) = viewModelScope.launch {
        try {
            factionRepository.removeMember(factionId, characterId)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to remove member", e)
        }
    }

    fun departMember(
        factionId: Long,
        characterId: Long,
        leaveYear: Int,
        departedRelationType: String,
        departedIntensity: Int
    ) = viewModelScope.launch {
        try {
            factionRepository.departMember(factionId, characterId, leaveYear, departedRelationType, departedIntensity)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to depart member", e)
        }
    }

    /** 세계관에 속한 캐릭터 목록 (멤버 추가 시 선택용) */
    suspend fun getCharactersForUniverse(universeId: Long): List<com.novelcharacter.app.data.model.Character> {
        return try {
            characterRepository.getCharactersByUniverseList(universeId)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to get characters", e)
            emptyList()
        }
    }

    /** 캐릭터 ID로 캐릭터 조회 */
    suspend fun getCharacterById(characterId: Long): com.novelcharacter.app.data.model.Character? {
        return try {
            characterRepository.getCharacterById(characterId)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to get character", e)
            null
        }
    }

    /** 세력별 활성 멤버 수 조회 */
    suspend fun getActiveMemberCountsForFactions(factionIds: List<Long>): Map<Long, Int> {
        return try {
            factionIds.associateWith { id ->
                factionRepository.getMembershipsByFactionList(id).count { it.leaveType == null }
            }
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to get member counts", e)
            emptyMap()
        }
    }
}
