package com.novelcharacter.app.ui.namebank

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.launch

class NameBankViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val nameBankRepository = app.nameBankRepository
    private val prefs = application.getSharedPreferences("namebank_ui_state", Context.MODE_PRIVATE)

    // 데이터 처리 결과 알림 채널 (이름은행 CRUD·사용처리/해제 결과 통보)
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    private val _searchQuery = MutableLiveData("")
    private val _showOnlyAvailable = MutableLiveData(prefs.getBoolean("show_only_available", false))

    val displayedNames: LiveData<List<NameBankEntry>> = MediatorLiveData<List<NameBankEntry>>().apply {
        val allNames = nameBankRepository.allNameBankEntries
        val availableNames = nameBankRepository.availableNameBankEntries

        // Cache the latest values from both sources to avoid stale data
        var latestAll: List<NameBankEntry> = emptyList()
        var latestAvailable: List<NameBankEntry> = emptyList()

        fun update() {
            val query = _searchQuery.value ?: ""
            val onlyAvailable = _showOnlyAvailable.value ?: false
            val currentList = if (onlyAvailable) latestAvailable else latestAll
            value = if (query.isBlank()) {
                currentList
            } else {
                currentList.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.notes.contains(query, ignoreCase = true) ||
                    it.origin.contains(query, ignoreCase = true)
                }
            }
        }

        addSource(allNames) { latestAll = it; update() }
        addSource(availableNames) { latestAvailable = it; update() }
        addSource(_searchQuery) { update() }
        addSource(_showOnlyAvailable) { update() }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowOnlyAvailable(onlyAvailable: Boolean) {
        _showOnlyAvailable.value = onlyAvailable
        prefs.edit().putBoolean("show_only_available", onlyAvailable).apply()
    }

    fun isShowOnlyAvailable(): Boolean = _showOnlyAvailable.value ?: false

    fun insert(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.insertNameBankEntry(entry)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_added, entry.name)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to insert name bank entry", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_add_failed), e.message))
        }
    }

    fun update(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.updateNameBankEntry(entry)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_updated, entry.name)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to update name bank entry", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_update_failed), e.message))
        }
    }

    fun delete(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.deleteNameBankEntry(entry)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_deleted, entry.name)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to delete name bank entry", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_delete_failed), e.message))
        }
    }

    fun markAsUsed(id: Long, characterId: Long) = viewModelScope.launch {
        try {
            nameBankRepository.markNameBankAsUsed(id, characterId)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_marked_used)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to mark as used", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_mark_used_failed), e.message))
        }
    }

    fun markAsAvailable(id: Long) = viewModelScope.launch {
        try {
            nameBankRepository.markNameBankAsAvailable(id)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_marked_available)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to mark as available", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_mark_available_failed), e.message))
        }
    }

    suspend fun getAvailableNamesList(): List<NameBankEntry> =
        nameBankRepository.getAvailableNameBankList()

    // ===== 일괄 캐릭터 등록 =====

    suspend fun getEntriesByIds(ids: List<Long>): List<NameBankEntry> =
        nameBankRepository.getByIds(ids)

    suspend fun getAllNovelsList(): List<com.novelcharacter.app.data.model.Novel> =
        app.novelRepository.getAllNovelsList()

    /** 중복 사전 고지용 기존 캐릭터 이름 집합 (저장 규약과 동일하게 Character.name 기준) */
    suspend fun getExistingCharacterNames(): Set<String> =
        app.characterRepository.getAllCharactersList().mapTo(HashSet()) { it.name.trim() }

    /**
     * 선택 엔트리를 일괄 캐릭터 등록.
     * 전 항목을 단일 트랜잭션으로 생성하고(부분 생성 잔재 방지) 사용 표시(isUsed+usedByCharacterId
     * 동시 설정)를 새 캐릭터로 옮긴다. 건너뜀·미기록은 결과 요약에 전부 집계한다 (R-11·R-14).
     */
    fun bulkRegister(
        ids: List<Long>,
        novelId: Long?,
        mapGender: Boolean,
        includeOriginNotes: Boolean,
        policy: BulkRegisterPlanner.DuplicatePolicy
    ) = viewModelScope.launch {
        try {
            val entries = nameBankRepository.getByIds(ids)
            if (entries.isEmpty()) {
                reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                    app.getString(R.string.name_bank_bulk_none_created)))
                return@launch
            }

            // 작품 → 세계관 해석. 작품이 사라졌으면 조용히 미지정으로 강등하지 않고 중단 (변수 제어)
            var universeId: Long? = null
            if (novelId != null) {
                val novel = app.novelRepository.getNovelById(novelId)
                if (novel == null) {
                    reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                        app.getString(R.string.name_bank_bulk_novel_missing)))
                    return@launch
                }
                universeId = novel.universeId
            }

            // 성별 필드 해석 — SELECT 타입 gender 필드가 있을 때만 매핑 유효
            var genderField: com.novelcharacter.app.data.model.FieldDefinition? = null
            var genderOptions: List<String> = emptyList()
            var genderFieldMissing = false
            if (mapGender && universeId != null) {
                val fd = app.database.fieldDefinitionDao().getFieldByKey(universeId, "gender")
                if (fd != null &&
                    com.novelcharacter.app.data.model.FieldType.fromName(fd.type) ==
                    com.novelcharacter.app.data.model.FieldType.SELECT
                ) {
                    genderField = fd
                    genderOptions = com.novelcharacter.app.util.FieldOptionParser.parseSelectOptions(fd.config)
                } else {
                    genderFieldMissing = true
                }
            }

            val options = BulkRegisterPlanner.Options(
                novelId = novelId,
                mapGender = genderField != null,
                genderOptions = genderOptions,
                includeOriginNotes = includeOriginNotes,
                originPrefixFormat = app.getString(R.string.name_bank_bulk_origin_prefix),
                policy = policy
            )
            val plan = BulkRegisterPlanner.plan(entries, getExistingCharacterNames(), options)
            if (plan.toCreate.isEmpty()) {
                reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                    app.getString(R.string.name_bank_bulk_none_created)))
                return@launch
            }

            var created = 0
            val createdNames = mutableListOf<String>()
            app.database.withTransaction {
                for (item in plan.toCreate) {
                    val newId = app.characterRepository.insertCharacter(
                        com.novelcharacter.app.data.model.Character(
                            name = item.entry.name.trim(),
                            novelId = novelId,
                            memo = item.memo
                        )
                    )
                    if (item.genderValue != null && genderField != null) {
                        app.characterRepository.saveAllFieldValues(newId, listOf(
                            com.novelcharacter.app.data.model.CharacterFieldValue(
                                characterId = newId,
                                fieldDefinitionId = genderField.id,
                                value = item.genderValue
                            )
                        ))
                    }
                    app.database.nameBankDao().markAsUsed(item.entry.id, newId)
                    created++
                    createdNames.add(item.entry.name.trim())
                }
            }

            val summary = buildString {
                append(app.getString(R.string.name_bank_bulk_done, created))
                if (plan.skippedDuplicates > 0) {
                    append(app.getString(R.string.name_bank_bulk_done_skipped, plan.skippedDuplicates))
                }
                if (plan.genderUnmatched > 0) {
                    append(app.getString(R.string.name_bank_bulk_gender_skipped, plan.genderUnmatched))
                }
                if (genderFieldMissing) {
                    append(app.getString(R.string.name_bank_bulk_gender_no_field))
                }
                if (plan.blankSkipped > 0) {
                    append(app.getString(R.string.name_bank_bulk_blank_skipped, plan.blankSkipped))
                }
            }
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK, summary,
                createdNames.joinToString(", ")))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to bulk register characters", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.name_bank_bulk_failed), e.message))
        }
    }
}
