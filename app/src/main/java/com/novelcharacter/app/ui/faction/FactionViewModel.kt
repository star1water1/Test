package com.novelcharacter.app.ui.faction

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.repository.MemberAddResult
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import com.novelcharacter.app.util.logResult
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

    // 데이터 처리 결과 알림 채널 (변수 제어: 모든 의미있는 조작 결과 통보)
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    fun setUniverseId(id: Long) {
        _universeId.value = id
    }

    fun setSelectedFactionId(id: Long) {
        _selectedFactionId.value = id
    }

    fun insertFaction(faction: Faction) = viewModelScope.launch {
        try {
            factionRepository.insertFaction(faction)
            reportResult(_result, OpResult.success(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_saved, faction.name)))
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to insert faction", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_save_failed), e.message))
        }
    }

    fun updateFaction(faction: Faction) = viewModelScope.launch {
        try {
            factionRepository.updateFaction(faction)
            reportResult(_result, OpResult.success(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_updated, faction.name)))
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to update faction", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_update_failed), e.message))
        }
    }

    fun deleteFaction(faction: Faction, deleteRelationships: Boolean = true) = viewModelScope.launch {
        try {
            factionRepository.deleteFaction(faction, deleteRelationships)
            reportResult(_result, OpResult.success(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_deleted, faction.name)))
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to delete faction", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_delete_failed), e.message))
        }
    }

    fun updateFactionOrder(factions: List<Faction>) = viewModelScope.launch {
        try {
            val updated = factions.mapIndexed { index, f -> f.copy(displayOrder = index) }
            factionRepository.updateFactionDisplayOrders(updated)
            // 재정렬은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to update faction order", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_reorder_failed), e.message))
        }
    }

    fun addMember(
        factionId: Long,
        characterId: Long,
        joinYear: Int? = null,
        onResult: (MemberAddResult) -> Unit = {}
    ) = viewModelScope.launch {
        try {
            val result = factionRepository.addMember(factionId, characterId, joinYear)
            onResult(result)
            // 멤버 추가는 onResult 콜백이 건수/스킵을 통보 — 이력만 추가 (중복 알림 방지)
            if (result.added > 0) {
                logResult(OpResult.success(OpResult.CAT_FACTION,
                    app.getString(R.string.result_faction_member_added, result.added)))
            }
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to add member", e)
            onResult(MemberAddResult(0, 0, 0))
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_member_add_failed), e.message))
        }
    }

    fun addMembers(
        factionId: Long,
        characterIds: List<Long>,
        joinYear: Int?,
        onResult: (MemberAddResult) -> Unit = {}
    ) = viewModelScope.launch {
        try {
            val result = factionRepository.addMembers(factionId, characterIds, joinYear)
            onResult(result)
            // 멤버 추가는 onResult 콜백이 건수/스킵을 통보 — 이력만 추가 (중복 알림 방지)
            if (result.added > 0) {
                logResult(OpResult.success(OpResult.CAT_FACTION,
                    app.getString(R.string.result_faction_member_added, result.added)))
            }
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to add members", e)
            onResult(MemberAddResult(0, 0, 0))
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_member_add_failed), e.message))
        }
    }

    fun removeMember(factionId: Long, characterId: Long) = viewModelScope.launch {
        try {
            factionRepository.removeMember(factionId, characterId)
            reportResult(_result, OpResult.success(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_member_removed)))
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to remove member", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_member_remove_failed), e.message))
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
            reportResult(_result, OpResult.success(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_member_departed)))
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to depart member", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_member_depart_failed), e.message))
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
