package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FactionRelationship
import com.novelcharacter.app.util.SqlInChunks

/**
 * 세력 가입 결과. 자동관계 정책은 "수동 관계 우선" — 동일 (캐릭터쌍, 유형)의 수동 관계가 이미 있으면
 * 자동관계를 만들지 않고(가로채지도 않고) 건너뛴 건수를 집계해 사용자에게 알린다.
 * 수동 관계에 factionId를 부착하는 방식은 탈퇴 시 사용자가 만든 관계가 삭제되는 조용한 유실 경로가 되므로 금지.
 */
/**
 * 세력 하나를 지울 때 함께 사라지는 것의 규모 (R-4).
 *
 * [autoRelations]만 **선택지에 따라 처분이 갈린다** — '관계도 함께 삭제'면 사라지고,
 * '관계 유지'면 관계는 남고 *'이 세력의 자동 관계'*라는 지정만 풀린다. 나머지 둘은
 * 어느 쪽을 골라도 사라진다.
 */
data class FactionDeleteImpact(
    val members: Int = 0,
    val autoRelations: Int = 0,
    val factionRelations: Int = 0
)

/**
 * 탈퇴 기록 편집 결과.
 * [relationChangesMoved]는 탈퇴가 만들었던 관계 변화 중 함께 옮긴 건수 — 0이 아니면 고지한다.
 */
data class DepartureEditResult(val found: Boolean, val relationChangesMoved: Int)

data class MemberAddResult(
    val added: Int,
    val autoRelationsCreated: Int,
    val autoRelationsSkipped: Int,
    /**
     * 이미 활성 소속이라 건너뛴 수. 종전에는 조용히 `continue`해서 "N명 추가"의 N에서만
     * 빠졌고 **왜 빠졌는지는 말하지 않았다** — 골랐는데 안 들어간 이유를 사용자가 알 길이 없다.
     */
    val alreadyMember: Int = 0
)

class FactionRepository(private val db: AppDatabase) {

    private val factionDao = db.factionDao()
    private val membershipDao = db.factionMembershipDao()
    private val relationshipDao = db.characterRelationshipDao()
    private val relationshipChangeDao = db.characterRelationshipChangeDao()
    private val factionRelationshipDao = db.factionRelationshipDao()

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
     * 세력 하나를 지우면 함께 사라지는 것의 규모 (R-4 — 파괴적 조작은 결과를 먼저 말한다).
     *
     * **셋을 갈라 센다.** 소속과 세력 간 관계는 어느 쪽을 고르든 사라지고, 자동 관계는
     * 고른 쪽에 따라 사라지거나 지정만 풀린다 — 한 수로 뭉치면 확인창이 사실과 다른 말을
     * 하게 된다. **COUNT로 센다**(목록을 불러 `.size`로 세면 100명 세력에서 수천 행을 읽는다).
     */
    suspend fun getFactionDeleteImpact(factionId: Long): FactionDeleteImpact =
        FactionDeleteImpact(
            members = membershipDao.countByFaction(factionId),
            autoRelations = relationshipDao.countByFaction(factionId),
            factionRelations = factionRelationshipDao.countForFaction(factionId)
        )

    /**
     * 세력 삭제. deleteRelationships=true이면 자동 관계도 함께 삭제.
     * false이면 관계의 factionId가 null로 전환 (FK SET_NULL).
     *
     * **삭제 전 휴지통 스냅샷을 남긴다 (B-1).** 종전에는 세력 소속·세력 간 관계·자동 관계가
     * 전부 즉시 영구 소멸했고, '관계 유지' 쪽을 골라도 '이 세력의 자동 관계'라는 지정이
     * 조용히 사라졌다(SET_NULL). 두 경우는 복원 방법이 달라 스냅샷이 구분해 담는다.
     */
    suspend fun deleteFaction(faction: Faction, deleteRelationships: Boolean = true) {
        val trash = TrashRepository(db)
        db.withTransaction {
            trash.snapshotFaction(faction, deleteRelationships)
            if (deleteRelationships) {
                relationshipDao.deleteAllByFaction(faction.id)
            }
            factionDao.delete(faction)
        }
        trash.pruneIfNeeded()
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

    // ===== 세력 간 관계 (B-3) =====

    fun getFactionRelationshipsForFaction(factionId: Long): LiveData<List<FactionRelationship>> =
        factionRelationshipDao.getRelationshipsForFaction(factionId)

    suspend fun getFactionRelationshipsForFactionList(factionId: Long): List<FactionRelationship> =
        factionRelationshipDao.getRelationshipsForFactionList(factionId)

    suspend fun getFactionRelationshipsByUniverseList(universeId: Long): List<FactionRelationship> =
        factionRelationshipDao.getRelationshipsByUniverseList(universeId)

    suspend fun getAllFactionRelationshipsList(): List<FactionRelationship> =
        factionRelationshipDao.getAllRelationshipsList()

    /** @return 삽입된 id, 동일 (세력쌍, 유형) 관계가 이미 있으면 -1 */
    suspend fun insertFactionRelationship(relationship: FactionRelationship): Long =
        factionRelationshipDao.insert(relationship)

    suspend fun updateFactionRelationship(relationship: FactionRelationship) =
        factionRelationshipDao.update(relationship)

    suspend fun deleteFactionRelationship(relationship: FactionRelationship) =
        factionRelationshipDao.delete(relationship)

    // ===== 핵심 비즈니스 로직: 가입 =====

    /**
     * 캐릭터를 세력에 가입시키고 기존 활성 멤버들과 자동 관계를 생성한다.
     * 이미 활성 멤버십이 있으면 무시한다.
     * 동일 (캐릭터쌍, 유형)의 수동 관계가 있으면 자동관계는 건너뛰고 결과에 집계한다 (수동 관계 우선).
     */
    suspend fun addMember(factionId: Long, characterId: Long, joinYear: Int? = null): MemberAddResult {
        return db.withTransaction {
            // 이미 활성 멤버인지 체크
            val existing = membershipDao.getActiveMembership(factionId, characterId)
            if (existing != null) return@withTransaction MemberAddResult(0, 0, 0, alreadyMember = 1)

            val faction = factionDao.getById(factionId)
                ?: return@withTransaction MemberAddResult(0, 0, 0)

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

            val (created, skipped) = insertAutoRelations(faction, characterId, otherCharIds)
            MemberAddResult(added = 1, autoRelationsCreated = created, autoRelationsSkipped = skipped)
        }
    }

    /**
     * 여러 캐릭터를 세력에 일괄 가입시킨다.
     * 하나의 트랜잭션으로 감싸서 원자적으로 실행하며, 순차 추가하여 관계가 올바르게 캐스케이딩된다.
     * 활성 멤버 목록은 최초 1회만 조회하고 로컬로 누적한다 (반복 재조회 O(N²) 제거 — 생성되는 관계 쌍은 동일).
     */
    suspend fun addMembers(factionId: Long, characterIds: List<Long>, joinYear: Int? = null): MemberAddResult {
        return db.withTransaction {
            var addedCount = 0
            var createdTotal = 0
            var skippedTotal = 0
            var alreadyCount = 0
            val faction = factionDao.getById(factionId)
                ?: return@withTransaction MemberAddResult(0, 0, 0)

            val activeCharIds = membershipDao.getActiveMembershipsByFaction(factionId)
                .map { it.characterId }
                .toMutableSet()

            for (characterId in characterIds) {
                if (characterId in activeCharIds) { alreadyCount++; continue }
                val existing = membershipDao.getActiveMembership(factionId, characterId)
                if (existing != null) {
                    activeCharIds.add(characterId)
                    alreadyCount++
                    continue
                }

                membershipDao.insert(FactionMembership(
                    factionId = factionId,
                    characterId = characterId,
                    joinYear = joinYear
                ))

                val otherCharIds = activeCharIds.filter { it != characterId }
                val (created, skipped) = insertAutoRelations(faction, characterId, otherCharIds)
                createdTotal += created
                skippedTotal += skipped

                activeCharIds.add(characterId)
                addedCount++
            }
            MemberAddResult(addedCount, createdTotal, skippedTotal, alreadyCount)
        }
    }

    /**
     * 가입 연도만 고친다 — 소속 **행 자체는 보존**한다(이력의 정체성).
     * 종전에는 추가할 때 한 번 넣는 것이 전부였고 고칠 길이 없어, 잘못 넣으면
     * 제거 후 재추가밖에 없었다(그러면 소속 이력이 끊긴다).
     *
     * @param joinYear null이면 '시점 불명'으로 되돌린다.
     * @return 대상 행을 찾아 고쳤으면 true. false는 호출부가 사유를 고지해야 한다.
     */
    suspend fun updateJoinYear(membershipId: Long, joinYear: Int?): Boolean {
        val membership = membershipDao.getById(membershipId) ?: return false
        membershipDao.update(membership.copy(joinYear = joinYear))
        return true
    }

    /**
     * 끝난 소속(탈퇴) 기록을 고친다 — 네 값 전부.
     *
     * **탈퇴가 만든 관계 변화도 함께 옮긴다.** `departMember`는 탈퇴 시점에
     * `CharacterRelationshipChange`를 만드는데 그 행에는 소속으로 돌아오는 참조가 없다.
     * 그래서 **옛 값 네 가지가 모두 일치하는 행**만 골라 옮긴다
     * (이 세력의 자동 관계 · 옛 탈퇴 연도 · 옛 유형 · 옛 강도).
     * 사용자가 손수 만든 변화가 네 가지 모두 우연히 같을 확률은 낮고, 같다면 그것은
     * 사실상 같은 사건이다. **옮긴 건수는 반드시 호출부가 고지한다** — 조용히 남의 데이터를
     * 건드리지 않는다.
     */
    suspend fun updateDepartedMembership(
        membershipId: Long,
        joinYear: Int?,
        leaveYear: Int,
        relationType: String,
        intensity: Int
    ): DepartureEditResult = db.withTransaction {
        val old = membershipDao.getById(membershipId)
            ?: return@withTransaction DepartureEditResult(found = false, relationChangesMoved = 0)

        membershipDao.update(old.copy(
            joinYear = joinYear,
            leaveYear = leaveYear,
            departedRelationType = relationType,
            departedIntensity = intensity
        ))

        val oldLeave = old.leaveYear
        val oldType = old.departedRelationType
        val oldIntensity = old.departedIntensity
        var moved = 0
        if (oldLeave != null && oldType != null && oldIntensity != null) {
            val relIds = relationshipDao
                .getFactionRelationshipsForCharacter(old.factionId, old.characterId)
                .map { it.id }
            if (relIds.isNotEmpty()) {
                val changes = SqlInChunks.flat(relIds) {
                    relationshipChangeDao.getChangesForRelationships(it)
                }
                for (change in changes) {
                    if (change.year == oldLeave &&
                        change.relationshipType == oldType &&
                        change.intensity == oldIntensity
                    ) {
                        relationshipChangeDao.update(change.copy(
                            year = leaveYear,
                            relationshipType = relationType,
                            intensity = intensity
                        ))
                        moved++
                    }
                }
            }
        }
        DepartureEditResult(found = true, relationChangesMoved = moved)
    }

    /**
     * 소속 기록 한 줄을 지운다 — 탈퇴 이력 정리용.
     * `removeMember`(활성 소속 제거)와 달리 **이미 끝난 소속의 기록**을 대상으로 한다.
     * 되돌릴 수 없으므로 호출부가 실행 전에 알려야 한다(R-4).
     */
    suspend fun deleteMembershipRecord(membershipId: Long): Boolean {
        membershipDao.getById(membershipId) ?: return false
        membershipDao.deleteById(membershipId)
        return true
    }

    /**
     * 신규 멤버와 기존 멤버들 간 자동 관계 삽입.
     * @return (생성 건수, 기존 수동 관계와 충돌해 건너뛴 건수)
     * INSERT IGNORE의 -1 반환을 검사해 무통보 누락을 집계로 전환한다 (변수 제어).
     */
    private suspend fun insertAutoRelations(
        faction: Faction,
        characterId: Long,
        otherCharIds: List<Long>
    ): Pair<Int, Int> {
        if (otherCharIds.isEmpty()) return 0 to 0
        val newRelationships = otherCharIds.map { otherCharId ->
            val (c1, c2) = if (characterId < otherCharId)
                characterId to otherCharId else otherCharId to characterId
            CharacterRelationship(
                characterId1 = c1,
                characterId2 = c2,
                relationshipType = faction.autoRelationType,
                intensity = faction.autoRelationIntensity,
                isBidirectional = true,
                factionId = faction.id
            )
        }
        val insertedIds = relationshipDao.insertAll(newRelationships)
        val skipped = insertedIds.count { it == -1L }
        return (insertedIds.size - skipped) to skipped
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
