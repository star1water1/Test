package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership

class FactionRepository(private val db: AppDatabase) {

    private val factionDao = db.factionDao()
    private val membershipDao = db.factionMembershipDao()
    private val relationshipDao = db.characterRelationshipDao()
    private val relationshipChangeDao = db.characterRelationshipChangeDao()

    // ===== Faction CRUD =====

    fun getFactionsByUniverse(universeId: Long): LiveData<List<Faction>> =
        factionDao.getFactionsByUniverse(universeId)

    suspend fun getFactionsByUniverseList(universeId: Long): List<Faction> =
        factionDao.getFactionsByUniverseList(universeId)

    suspend fun getAllFactionsList(): List<Faction> =
        factionDao.getAllFactionsList()

    suspend fun getFactionById(id: Long): Faction? =
        factionDao.getById(id)

    suspend fun insertFaction(faction: Faction): Long =
        factionDao.insert(faction)

    suspend fun updateFaction(faction: Faction) =
        factionDao.update(faction)

    /**
     * 세력 삭제. deleteRelationships=true이면 자동 관계도 함께 삭제.
     * false이면 관계의 factionId가 null로 전환 (FK SET_NULL).
     */
    suspend fun deleteFaction(faction: Faction, deleteRelationships: Boolean = true) {
        db.withTransaction {
            if (deleteRelationships) {
                relationshipDao.deleteAllByFaction(faction.id)
            }
            factionDao.delete(faction)
        }
    }

    suspend fun updateFactionDisplayOrders(factions: List<Faction>) =
        factionDao.updateAll(factions)

    // ===== Membership =====

    fun getMembershipsByFaction(factionId: Long): LiveData<List<FactionMembership>> =
        membershipDao.getMembershipsByFaction(factionId)

    suspend fun getMembershipsByFactionList(factionId: Long): List<FactionMembership> =
        membershipDao.getMembershipsByFactionList(factionId)

    fun getMembershipsByCharacter(characterId: Long): LiveData<List<FactionMembership>> =
        membershipDao.getMembershipsByCharacter(characterId)

    suspend fun getMembershipsByCharacterList(characterId: Long): List<FactionMembership> =
        membershipDao.getMembershipsByCharacterList(characterId)

    suspend fun getAllMembershipsList(): List<FactionMembership> =
        membershipDao.getAllMembershipsList()

    // ===== 핵심 비즈니스 로직: 가입 =====

    /**
     * 캐릭터를 세력에 가입시키고 기존 활성 멤버들과 자동 관계를 생성한다.
     * 이미 활성 멤버십이 있으면 무시한다.
     */
    suspend fun addMember(factionId: Long, characterId: Long, joinYear: Int? = null): Boolean {
        return db.withTransaction {
            // 이미 활성 멤버인지 체크
            val existing = membershipDao.getActiveMembership(factionId, characterId)
            if (existing != null) return@withTransaction false

            val faction = factionDao.getById(factionId) ?: return@withTransaction false

            // 멤버십 생성
            membershipDao.insert(FactionMembership(
                factionId = factionId,
                characterId = characterId,
                joinYear = joinYear
            ))

            // 기존 활성 멤버들과 자동 관계 생성
            val activeMembers = membershipDao.getActiveMembershipsByFaction(factionId)
            val otherCharIds = activeMembers
                .filter { it.characterId != characterId }
                .map { it.characterId }

            if (otherCharIds.isNotEmpty()) {
                val newRelationships = otherCharIds.map { otherCharId ->
                    val (c1, c2) = if (characterId < otherCharId)
                        characterId to otherCharId else otherCharId to characterId
                    CharacterRelationship(
                        characterId1 = c1,
                        characterId2 = c2,
                        relationshipType = faction.autoRelationType,
                        intensity = faction.autoRelationIntensity,
                        isBidirectional = true,
                        factionId = factionId
                    )
                }
                relationshipDao.insertAll(newRelationships)
            }

            true
        }
    }

    /**
     * 여러 캐릭터를 세력에 일괄 가입시킨다.
     * 하나의 트랜잭션으로 감싸서 원자적으로 실행하며, 순차 추가하여 관계가 올바르게 캐스케이딩된다.
     */
    suspend fun addMembers(factionId: Long, characterIds: List<Long>, joinYear: Int? = null): Int {
        return db.withTransaction {
            var addedCount = 0
            val faction = factionDao.getById(factionId) ?: return@withTransaction 0
            for (characterId in characterIds) {
                val existing = membershipDao.getActiveMembership(factionId, characterId)
                if (existing != null) continue

                membershipDao.insert(FactionMembership(
                    factionId = factionId,
                    characterId = characterId,
                    joinYear = joinYear
                ))

                val activeMembers = membershipDao.getActiveMembershipsByFaction(factionId)
                val otherCharIds = activeMembers
                    .filter { it.characterId != characterId }
                    .map { it.characterId }

                if (otherCharIds.isNotEmpty()) {
                    val newRelationships = otherCharIds.map { otherCharId ->
                        val (c1, c2) = if (characterId < otherCharId)
                            characterId to otherCharId else otherCharId to characterId
                        CharacterRelationship(
                            characterId1 = c1,
                            characterId2 = c2,
                            relationshipType = faction.autoRelationType,
                            intensity = faction.autoRelationIntensity,
                            isBidirectional = true,
                            factionId = factionId
                        )
                    }
                    relationshipDao.insertAll(newRelationships)
                }
                addedCount++
            }
            addedCount
        }
    }

    // ===== 핵심 비즈니스 로직: 순수 제거 =====

    /**
     * 캐릭터를 세력에서 순수 제거한다.
     * 해당 세력의 자동 관계 중 이 캐릭터가 포함된 것을 모두 삭제한다.
     */
    suspend fun removeMember(factionId: Long, characterId: Long) {
        db.withTransaction {
            // 자동 관계 삭제
            relationshipDao.deleteFactionRelationshipsForCharacter(factionId, characterId)
            // 멤버십 삭제 (활성 멤버십만)
            membershipDao.deleteActiveMembership(factionId, characterId)
        }
    }

    // ===== 핵심 비즈니스 로직: 설정상 탈퇴 =====

    /**
     * 캐릭터가 특정 시점에 세력을 탈퇴한다 (설정상).
     * 탈퇴 시점 이전에는 세력 관계가 유지되고, 이후에는 변경된 유형/강도로 전환된다.
     * 기존 CharacterRelationshipChange 시스템을 활용한다.
     */
    suspend fun departMember(
        factionId: Long,
        characterId: Long,
        leaveYear: Int,
        departedRelationType: String,
        departedIntensity: Int
    ) {
        db.withTransaction {
            // 멤버십 업데이트
            val membership = membershipDao.getActiveMembership(factionId, characterId) ?: return@withTransaction
            membershipDao.update(membership.copy(
                leaveYear = leaveYear,
                leaveType = FactionMembership.LEAVE_DEPARTED,
                departedRelationType = departedRelationType,
                departedIntensity = departedIntensity
            ))

            // 세력 자동 관계에 CharacterRelationshipChange 생성 (탈퇴 시점)
            val factionRelations = relationshipDao.getFactionRelationshipsForCharacter(factionId, characterId)
            val changes = factionRelations.map { rel ->
                CharacterRelationshipChange(
                    relationshipId = rel.id,
                    year = leaveYear,
                    relationshipType = departedRelationType,
                    intensity = departedIntensity,
                    isBidirectional = true,
                    description = ""
                )
            }
            if (changes.isNotEmpty()) {
                relationshipChangeDao.insertAll(changes)
            }
        }
    }
}
