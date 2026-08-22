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
import com.novelcharacter.app.util.FactionStanding
import com.novelcharacter.app.util.MembershipTimeline
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import com.novelcharacter.app.util.logResult
import kotlinx.coroutines.launch

/** 한 세력에 대한 캐릭터의 소속 상태 — 후보 목록이 셋을 구별해 보이기 위한 축. */
enum class MemberState { ACTIVE, DEPARTED }

/**
 * 한 (세력, 캐릭터)의 소속 이력 요약.
 * [latestLeaveYear]는 재가입의 가입 연도가 이전 이력과 모순되지 않는지 판정하는 기준이다
 * (`MembershipTimeline`). 상태와 함께 한 번의 조회로 얻는다.
 */
data class MembershipSummary(val state: MemberState, val latestLeaveYear: Int?)

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

    /** 세력 하나를 지우면 함께 사라지는 것의 규모 — 확인창이 먼저 묻는다(R-4). */
    suspend fun getFactionDeleteImpact(factionId: Long) =
        factionRepository.getFactionDeleteImpact(factionId)

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

    /**
     * 세력별 캐릭터 소속 요약 — 후보 목록의 상태 표시 **와** 가입 연도 판정에 함께 쓴다.
     * 활성 소속이 하나라도 있으면 ACTIVE가 이긴다(재가입한 캐릭터는 옛 탈퇴 행도 함께 갖는다).
     * 탈퇴 연도는 **가장 늦은 것**을 남긴다 — 새 소속은 마지막 탈퇴 뒤여야 겹치지 않는다.
     */
    suspend fun getMembershipSummaryForFaction(factionId: Long): Map<Long, MembershipSummary> {
        val rows = factionRepository.getMembershipsByFactionList(factionId)
        return rows.groupBy { it.characterId }.mapValues { (_, group) ->
            MembershipSummary(
                state = if (FactionStanding.hasCurrent(group)) MemberState.ACTIVE else MemberState.DEPARTED,
                latestLeaveYear = MembershipTimeline.latestKnownLeaveYear(group.map { it.leaveYear })
            )
        }
    }

    /** 한 (세력, 캐릭터)의 이전 탈퇴 중 가장 늦은 시점 — 재가입 창의 판정 기준. */
    suspend fun latestLeaveYearFor(factionId: Long, characterId: Long): Int? =
        MembershipTimeline.latestKnownLeaveYear(
            factionRepository.getMembershipsByFactionList(factionId)
                .filter { it.characterId == characterId }
                .map { it.leaveYear }
        )

    /** 가입 연도 수정 — 소속 행은 보존한다(이력의 정체성). null이면 '시점 불명'. */
    fun updateJoinYear(membershipId: Long, joinYear: Int?) = viewModelScope.launch {
        try {
            val ok = factionRepository.updateJoinYear(membershipId, joinYear)
            if (ok) {
                reportResult(_result, OpResult.success(OpResult.CAT_FACTION,
                    app.getString(R.string.result_faction_join_year_updated)))
            } else {
                // 대상이 사라진 경우도 반드시 알린다 — 조용한 무동작을 남기지 않는다
                reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                    app.getString(R.string.result_faction_membership_gone)))
            }
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to update join year", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_join_year_update_failed), e.message))
        }
    }

    /**
     * 끝난 소속 기록 편집 — 네 값 전부. 탈퇴가 만든 관계 변화도 함께 옮긴다.
     * 옮긴 건수는 [onDone]으로 돌려 호출부가 고지한다(조용히 남의 데이터를 건드리지 않는다).
     */
    fun updateDepartedMembership(
        membershipId: Long,
        joinYear: Int?,
        leaveYear: Int,
        relationType: String,
        intensity: Int,
        onDone: (Int) -> Unit = {}
    ) = viewModelScope.launch {
        try {
            val r = factionRepository.updateDepartedMembership(
                membershipId, joinYear, leaveYear, relationType, intensity)
            if (!r.found) {
                reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                    app.getString(R.string.result_faction_membership_gone)))
                return@launch
            }
            onDone(r.relationChangesMoved)
            logResult(OpResult.success(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_departure_updated)))
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to update departure", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_departure_update_failed), e.message))
        }
    }

    /** 한 캐릭터의 이 세력 소속 구간들 — 편집 시 겹침 판정용(고치는 당사자는 뺀다). */
    suspend fun otherSpansFor(factionId: Long, characterId: Long, excludeMembershipId: Long):
        List<MembershipTimeline.Span> =
        factionRepository.getMembershipsByFactionList(factionId)
            .filter { it.characterId == characterId && it.id != excludeMembershipId }
            .map { MembershipTimeline.Span(it.joinYear, it.leaveYear) }

    /** 소속 기록 삭제 — 이미 끝난 소속의 기록을 지운다(되돌릴 수 없다). */
    fun deleteMembershipRecord(membershipId: Long) = viewModelScope.launch {
        try {
            val ok = factionRepository.deleteMembershipRecord(membershipId)
            if (ok) {
                reportResult(_result, OpResult.success(OpResult.CAT_FACTION,
                    app.getString(R.string.result_faction_membership_deleted)))
            } else {
                reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                    app.getString(R.string.result_faction_membership_gone)))
            }
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to delete membership record", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FACTION,
                app.getString(R.string.result_faction_membership_delete_failed), e.message))
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
                FactionStanding.countCurrent(factionRepository.getMembershipsByFactionList(id))
            }
        } catch (e: Exception) {
            Log.e("FactionViewModel", "Failed to get member counts", e)
            emptyMap()
        }
    }
}
