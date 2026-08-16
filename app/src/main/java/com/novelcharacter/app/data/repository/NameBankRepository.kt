package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.NameBankDao
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.util.SqlInChunks

/**
 * 저장 한 번이 이름은행에 실제로 한 일 (B-124 ⓑ — Q7의 "고지" 재료).
 *
 * 화면은 이 값 하나로 한 줄을 낸다. **아무 일도 없었으면 [isSilent]** — 저장할 때마다
 * *"연결했습니다"*가 뜨면 그것이 소음이다(원칙 04).
 */
data class NameBankLinkOutcome(
    /** 사용 표시를 건 엔트리의 이름 (안 걸었으면 null) */
    val linkedName: String? = null,
    /** 이름이 바뀌어 사용 표시를 푼 엔트리 수 */
    val releasedCount: Int = 0,
    /** 폼에서 고른 엔트리를 **남이 이미 쓰고 있어** 걸지 못했다 */
    val requestedTaken: Boolean = false,
    /**
     * 폼에서 고른 엔트리가 **은행에서 사라져** 걸지 못했다.
     *
     * [requestedTaken]과 갈라 두는 것은 사유가 다르면 처방도 다르기 때문이다(R-17) —
     * 빼앗긴 것은 *다른 이름을 고르라*이고 사라진 것은 *다시 등록하라*다.
     * **이름을 고쳐 적어 선택이 무의미해진 경우는 둘 다 아니다** — 사용자가 스스로
     * 덮어쓴 것이라 알릴 사건이 아니고, 판정이 조용히 자동 대조로 넘긴다.
     */
    val requestedMissing: Boolean = false
) {
    val isSilent: Boolean
        get() = linkedName == null && releasedCount == 0 && !requestedTaken && !requestedMissing
}

class NameBankRepository(
    private val nameBankDao: NameBankDao
) {
    val allNameBankEntries: LiveData<List<NameBankEntry>> = nameBankDao.getAllNames()
    val availableNameBankEntries: LiveData<List<NameBankEntry>> = nameBankDao.getAvailableNames()

    suspend fun getAvailableNameBankList(): List<NameBankEntry> =
        nameBankDao.getAvailableNamesList()

    /**
     * 은행 전체 (일회성 조회).
     *
     * **B-124 전까지 소비처가 없어 죽어 있던 자리다**(설계 8-2 ⓓ가 *"걷거나 쓰임을 단다"*로
     * 남긴 넷 중 하나). 성별 자유 입력의 제안 목록이 이것을 쓴다 — 사용자가 이미 쓰고 있는
     * 값을 제안해야 고정 3옵션을 연 것이 값을 한다.
     */
    suspend fun getAllNameBankList(): List<NameBankEntry> =
        nameBankDao.getAllNamesList()

    /** 이름이 같은 엔트리 — 저장 시 자동 대조의 후보 (B-124 ⓑ. 판정은 `NameBankMatch`가 한다) */
    suspend fun findByName(name: String): List<NameBankEntry> =
        nameBankDao.findByName(name.trim())

    /** 이 캐릭터가 지금 물고 있는 엔트리 (B-3의 스냅샷 조회를 대조에도 쓴다) */
    suspend fun getEntriesUsedByCharacter(characterId: Long): List<NameBankEntry> =
        nameBankDao.getEntriesUsedByCharacter(characterId)

    /** 선택 엔트리 일괄 조회 (IN 절은 [SqlInChunks]를 지난다 — R-54, 입력 순서 보존) */
    suspend fun getByIds(ids: List<Long>): List<NameBankEntry> {
        if (ids.isEmpty()) return emptyList()
        val byId = SqlInChunks.flat(ids) { nameBankDao.getByIds(it) }
            .associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    suspend fun insertNameBankEntry(entry: NameBankEntry): Long =
        nameBankDao.insert(entry)

    suspend fun updateNameBankEntry(entry: NameBankEntry) =
        nameBankDao.update(entry)

    suspend fun deleteNameBankEntry(entry: NameBankEntry) =
        nameBankDao.delete(entry)

    suspend fun markNameBankAsUsed(id: Long, characterId: Long) =
        nameBankDao.markAsUsed(id, characterId)

    suspend fun markNameBankAsAvailable(id: Long) =
        nameBankDao.markAsAvailable(id)
}
