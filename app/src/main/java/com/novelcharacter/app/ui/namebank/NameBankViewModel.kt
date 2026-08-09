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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val _sortMode = MutableLiveData(prefs.getString("sort_mode", SORT_RECENT) ?: SORT_RECENT)

    // 일괄 등록 선택 모드 상태 — VM 보관으로 회전 생존 (프로세스 재생성은 별도 과제)
    var selectionMode = false
    val selectedIds = linkedSetOf<Long>()

    /** 은행 전체 목록 — 삭제된 엔트리의 선택 정리 기준 (표시 목록과 무관하게 전체 대비) */
    val allEntries: LiveData<List<NameBankEntry>> = nameBankRepository.allNameBankEntries

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
            val filtered = if (query.isBlank()) {
                currentList
            } else {
                currentList.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.notes.contains(query, ignoreCase = true) ||
                    it.origin.contains(query, ignoreCase = true)
                }
            }
            // 등록 최신순은 DAO 기본 순서(createdAt DESC) 그대로 — 재정렬하지 않는다
            value = if (_sortMode.value == SORT_NAME) {
                filtered.sortedBy { it.name.lowercase() }
            } else {
                filtered
            }
        }

        addSource(allNames) { latestAll = it; update() }
        addSource(availableNames) { latestAvailable = it; update() }
        addSource(_searchQuery) { update() }
        addSource(_showOnlyAvailable) { update() }
        addSource(_sortMode) { update() }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowOnlyAvailable(onlyAvailable: Boolean) {
        _showOnlyAvailable.value = onlyAvailable
        prefs.edit().putBoolean("show_only_available", onlyAvailable).apply()
    }

    fun isShowOnlyAvailable(): Boolean = _showOnlyAvailable.value ?: false

    fun setSortMode(mode: String) {
        _sortMode.value = mode
        prefs.edit().putString("sort_mode", mode).apply()
    }

    fun getSortMode(): String = _sortMode.value ?: SORT_RECENT

    companion object {
        /** 등록 최신순(기본) — DAO의 createdAt DESC 순서를 그대로 쓴다. */
        const val SORT_RECENT = "recent"
        const val SORT_NAME = "name"
    }

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

    // ===== 사용 캐릭터 표시·행동 (B-124 ⓒ) =====

    /**
     * 사용 캐릭터 id → 이름 (행 표시용).
     *
     * `allEntries`에 맞물려 갱신된다 — 링크가 바뀌면 이름표도 함께 바뀌어야 한다.
     * 캐릭터 목록을 통째로 보는 것은 은행이 세계관 스코프가 없어서다(v1 밖 — 설계 8-3).
     */
    val usedByNames: LiveData<Map<Long, String>> =
        MediatorLiveData<Map<Long, String>>().apply {
            val characters = app.characterRepository.allCharacters
            var latestEntries: List<NameBankEntry> = emptyList()
            var latestCharacters: List<com.novelcharacter.app.data.model.Character> = emptyList()

            fun update() {
                val usedIds = latestEntries.mapNotNull { it.usedByCharacterId }.toSet()
                value = if (usedIds.isEmpty()) emptyMap()
                else latestCharacters.filter { it.id in usedIds }.associate { it.id to it.name }
            }
            addSource(nameBankRepository.allNameBankEntries) { latestEntries = it; update() }
            addSource(characters) { latestCharacters = it; update() }
        }

    /**
     * [사용 처리]의 캐릭터 고르기 재료 (죽어 있던 `markAsUsed`에 UI를 다는 자리).
     *
     * `EntityPickerBottomSheet`의 행 타입을 그대로 쓴다 — 그 시트를 재사용하기 위해서다
     * (`AlertDialog.setItems`는 검색이 없어 목표 규모 6,420명에서 부적합하다는 판정이
     * 그 파일 머리에 이미 있다). 부제는 작품명이라 **동명이인을 가려 준다.**
     */
    suspend fun getCharacterPickRows(): List<com.novelcharacter.app.ui.image.ImageManagerViewModel.PickRow> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val novels = app.database.novelDao().getAllNovelsList().associateBy({ it.id }, { it.title })
            app.characterRepository.getAllCharactersList().sortedBy { it.name }.map {
                com.novelcharacter.app.ui.image.ImageManagerViewModel.PickRow(
                    it.id, it.name, it.novelId?.let { n -> novels[n] } ?: ""
                )
            }
        }

    /** 이름은행이 쓰고 있는 성별 값들 — 자유 입력의 제안 목록 (ⓓ, 고정 3옵션을 연 자리) */
    suspend fun getGenderSuggestions(): List<String> =
        nameBankRepository.getAllNameBankList()
            .map { it.gender.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

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
     * 사용자가 확정한 쓰기 작업이므로 NonCancellable — 확정 직후 화면을 떠나 VM이 소멸해도
     * 트랜잭션이 완주한다(중간 취소 시 전량 롤백이 무통보로 일어나는 유실 방지).
     */
    fun bulkRegister(
        ids: List<Long>,
        novelId: Long?,
        mapGender: Boolean,
        includeOriginNotes: Boolean,
        policy: BulkRegisterPlanner.DuplicatePolicy
    ) = viewModelScope.launch {
        withContext(NonCancellable) {
            bulkRegisterInternal(ids, novelId, mapGender, includeOriginNotes, policy)
        }
    }

    private suspend fun bulkRegisterInternal(
        ids: List<Long>,
        novelId: Long?,
        mapGender: Boolean,
        includeOriginNotes: Boolean,
        policy: BulkRegisterPlanner.DuplicatePolicy
    ) {
        try {
            val entries = nameBankRepository.getByIds(ids)
            if (entries.isEmpty()) {
                reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                    app.getString(R.string.name_bank_bulk_none_created)))
                return
            }

            // 작품 → 세계관 해석. 작품이 사라졌으면 조용히 미지정으로 강등하지 않고 중단 (변수 제어)
            var universeId: Long? = null
            if (novelId != null) {
                val novel = app.novelRepository.getNovelById(novelId)
                if (novel == null) {
                    reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                        app.getString(R.string.name_bank_bulk_novel_missing)))
                    return
                }
                universeId = novel.universeId
            }

            // 성별 필드 해석 (B-124 ⓓ) — **역할이 먼저, key는 뒤다.**
            //
            // 종전에는 `getFieldByKey(universeId, "gender")`로 key를 고정해, 사용자가 다른
            // key로 만든 성별 필드는 영영 안 잡혔다(열린 구조 위반 — 이 앱에서 key는 자유
            // 입력이다). Q4가 세운 `SemanticRole.GENDER`가 *"이 필드가 무엇인가"*를 라벨
            // 추측 없이 말해 주므로 그것이 정본이다(R-20).
            //
            // **key 폴백을 남기는 것은 의도다** — 역할을 아직 안 단 기존 세계관에서 걷어내면
            // 잘 돌던 기능이 조용히 죽는다. 역할이 서면 자연히 그쪽이 이긴다.
            var genderField: com.novelcharacter.app.data.model.FieldDefinition? = null
            var genderOptions: List<String> = emptyList()
            var genderFreeText = false
            var genderFieldMissing = false
            if (mapGender && universeId != null) {
                val fields = app.database.fieldDefinitionDao().getFieldsByUniverseList(universeId)
                // 역할 필드는 세계관에 하나여야 하지만 사용자가 둘에 같은 역할을 줄 수도 있다 —
                // 그때 첫 번째를 쓰는 것은 필드 관리의 차례가 사용자가 정한 차례이기 때문이다
                // (`DuelPlayFragment`가 같은 자리에서 정해 둔 규칙).
                val fd = fields.firstOrNull {
                    com.novelcharacter.app.data.model.SemanticRole.fromConfig(it.config) ==
                        com.novelcharacter.app.data.model.SemanticRole.GENDER
                // 폴백도 **이미 읽은 목록에서** 찾는다 — 두 번째 조회를 하면 그 둘이
                // 같은 entityType을 보는지가 두 자리의 기본값에 달리게 된다(R-29의 부류).
                } ?: fields.firstOrNull { it.key == "gender" }
                val type = fd?.let { com.novelcharacter.app.data.model.FieldType.fromName(it.type) }
                when (type) {
                    com.novelcharacter.app.data.model.FieldType.SELECT -> {
                        genderField = fd
                        genderOptions = com.novelcharacter.app.util.FieldOptionParser.parseSelectOptions(fd!!.config)
                    }
                    // 자유 입력 — 허용 값 목록이 없어 거를 근거가 없다. 그대로 기록한다.
                    com.novelcharacter.app.data.model.FieldType.TEXT,
                    com.novelcharacter.app.data.model.FieldType.MULTI_TEXT -> {
                        genderField = fd
                        genderFreeText = true
                    }
                    // 숫자·등급·자동 계산·신체 사이즈에 성별 문자열을 밀어 넣지 않는다 —
                    // 타입이 못 받는 값을 강제 기입하는 것은 R-11이 금지한 그것이다.
                    else -> genderFieldMissing = true
                }
            } else if (mapGender) {
                // 작품 미지정 또는 세계관 미연결 작품 — 기록할 성별 필드 자체가 없다.
                // 시트 게이트(매핑 불가 시 스위치 자동 OFF)가 막지 못하는 초기 콜백 전
                // 확정 레이스의 최종 방어선: 무통보 소실 대신 결과 요약에 고지한다.
                genderFieldMissing = true
            }

            val options = BulkRegisterPlanner.Options(
                novelId = novelId,
                mapGender = genderField != null,
                genderOptions = genderOptions,
                genderFreeText = genderFreeText,
                includeOriginNotes = includeOriginNotes,
                originPrefixFormat = app.getString(R.string.name_bank_bulk_origin_prefix),
                policy = policy
            )
            val plan = BulkRegisterPlanner.plan(entries, getExistingCharacterNames(), options)
            if (plan.toCreate.isEmpty()) {
                reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                    app.getString(R.string.name_bank_bulk_none_created)))
                return
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
                if (plan.alreadyUsedCount > 0) {
                    append(app.getString(R.string.name_bank_bulk_used_moved, plan.alreadyUsedCount))
                }
                if (plan.blankSkipped > 0) {
                    append(app.getString(R.string.name_bank_bulk_blank_skipped, plan.blankSkipped))
                }
            }
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK, summary,
                createdNames.joinToString(", ")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to bulk register characters", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.name_bank_bulk_failed), e.message))
        }
    }
}
