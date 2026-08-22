package com.novelcharacter.app.ui.field

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.PresetMerge
import com.novelcharacter.app.util.UnassignedHistoryScope
import com.novelcharacter.app.util.PresetTemplates
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.SqlInChunks
import com.novelcharacter.app.util.reportResult
import android.util.Log
import com.novelcharacter.app.util.DetailListSort
import kotlinx.coroutines.launch
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.util.RegexCharClasses
import com.novelcharacter.app.util.stringOr

class FieldViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val universeRepository = app.universeRepository
    private val userPresetDao = app.database.userPresetTemplateDao()
    // 관리 대상 세그먼트(캐릭터/사건 필드)를 재방문 시 복원 — 다른 목록 표면과 동일 규칙
    private val prefs = application.getSharedPreferences("field_manage_ui_state", android.content.Context.MODE_PRIVATE)

    private val _universeId = MutableLiveData<Long>()
    val universeId: LiveData<Long> = _universeId

    // 데이터 처리 결과 알림 채널 — 필드 저장 성공/실패·자동 교정 정보를 OpResult로 일원화
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    /**
     * 보기 정렬 (B-48 · 확정 7-7). **저장 순서와 다른 것이다** —
     * 이 값은 화면에 보이는 순서만 정하고 `displayOrder`를 읽지도 쓰지도 않는다.
     *
     * 화면별로 기억한다(캐릭터 상세의 보기 정렬과 같은 규약): "나는 필드를 종류끼리 모아
     * 본다"는 사용자의 보기 습관이지 세계관 하나의 속성이 아니다. 세계관마다 따로 두면
     * 세계관을 옮길 때마다 다시 골라야 한다(원칙 04).
     *
     * 모르는 값은 기본으로 떨어진다 — 구버전·손상에 죽지 않는다.
     */
    private val _sortMode = MutableLiveData(
        DetailListSort.FieldMode.entries
            .firstOrNull { it.name == prefs.getString(KEY_SORT_MODE, null) }
            ?: DetailListSort.FieldMode.MANUAL
    )
    val sortMode: LiveData<DetailListSort.FieldMode> = _sortMode

    fun setSortMode(mode: DetailListSort.FieldMode) {
        if (_sortMode.value == mode) return
        prefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
        _sortMode.value = mode
    }

    // 관리 대상 (B-10 · 확-3): 캐릭터 / 사건 / 작품 필드 전환 — 저장된 세그먼트에서 복원
    private val _entityType = MutableLiveData(
        prefs.getString("entity_type", FieldDefinition.ENTITY_CHARACTER) ?: FieldDefinition.ENTITY_CHARACTER
    )
    val entityType: LiveData<String> = _entityType

    private val _fieldsTrigger = MediatorLiveData<Unit>().apply {
        addSource(_universeId) { value = Unit }
        addSource(_entityType) { value = Unit }
    }

    val fields: LiveData<List<FieldDefinition>> = _fieldsTrigger.switchMap {
        val id = _universeId.value ?: return@switchMap MutableLiveData(emptyList<FieldDefinition>())
        // 종류 분기는 [fieldsOf]와 **같은 판정**이어야 한다 — 목록만 다른 종류를 보면
        // 편집·삭제·가져오기가 화면에 없는 필드를 건드린다(R-29).
        when (_entityType.value) {
            FieldDefinition.ENTITY_EVENT -> universeRepository.getEventFieldsByUniverse(id)
            FieldDefinition.ENTITY_NOVEL -> universeRepository.getNovelFieldsByUniverse(id)
            else -> universeRepository.getFieldsByUniverse(id)
        }
    }

    fun setUniverseId(id: Long) {
        _universeId.value = id
    }

    fun setEntityType(type: String) {
        if (_entityType.value != type) {
            _entityType.value = type
            prefs.edit().putString("entity_type", type).apply()
        }
    }

    fun currentEntityType(): String = _entityType.value ?: FieldDefinition.ENTITY_CHARACTER

    /**
     * @param defaultField 저장 뒤 **전역 기본 필드 상태를 이 값으로 맞춘다**(B-119).
     *   null이면 건드리지 않는다 — 이 스위치를 열지 않은 화면의 저장이 남의 상태를 바꾸지
     *   않게 하는 기본값이다.
     */
    fun insertField(
        field: FieldDefinition,
        initialValues: String = "",
        defaultField: Boolean? = null
    ) = viewModelScope.launch {
        try {
            val newId = universeRepository.insertField(field)
            // 생성 다이얼로그의 값 사전 등록분 — 해석·등재 규칙은 저장소가 단일 소스다
            // (종전에는 이 로직이 작품 경로와 두 벌이었고 실패 처리가 서로 달랐다).
            val outcome = app.fieldValueLibraryRepository
                .registerInitialValues(newId, field, initialValues)
            val note = listOfNotNull(
                syncDefaultField(field.copy(id = newId), defaultField),
                // **등재하지 못한 값을 말한다.** 종전에는 반환값을 버려서, 같은 조작의 고지가
                // 화면마다 갈렸다(사건 편집만 말했다). 촉발은 중복이 아니라 DB 예외라
                // 사용자는 자기가 적어 둔 값이 어디에도 없다는 것을 알 길이 없었다.
                initialValuePartialNote(outcome)
            ).joinToString("\n").takeIf { it.isNotBlank() }
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_added, field.name), note))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            Log.e("FieldViewModel", "Duplicate field key: ${field.key}", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_key_duplicate, field.key)))
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to insert field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_save_failed), e.message))
        }
    }

    /** @param defaultField [insertField]와 같은 뜻 — null이면 전역 기본 필드 상태를 건드리지 않는다. */
    fun updateField(field: FieldDefinition, defaultField: Boolean? = null) = viewModelScope.launch {
        try {
            // 키 변경 자동 감지: 참조 수식·상태변화 이력이 무통보로 파손되지 않도록 함께 갱신한다
            val old = app.database.fieldDefinitionDao().getFieldById(field.id)
            if (old != null && old.key != field.key) {
                val migration = migrateFieldKey(old, field)
                // 자동 교정이 일어났으면 상세로 노출 (조용한 파급 방지)
                val detail = if (migration.changedSomething) app.getString(
                    R.string.result_field_key_migrated,
                    old.key, field.key, migration.formulas, migration.history
                ) else null
                // 가리지 못해 옛 키로 남긴 이력은 **별도 문장**이다 — 위 문장에 붙이면
                // '자동으로 갱신했습니다'의 일부로 읽혀 손댈 것이 남았다는 사실이 묻힌다.
                val unresolved = if (migration.unresolvedHistory > 0) app.getString(
                    R.string.result_field_key_history_unresolved, migration.unresolvedHistory, old.key
                ) else null
                val note = syncDefaultField(field, defaultField)
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                    app.getString(R.string.result_field_updated, field.name),
                    listOfNotNull(detail, unresolved, note).joinToString("\n").ifBlank { null }))
            } else {
                universeRepository.updateField(field)
                val note = syncDefaultField(field, defaultField)
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                    app.getString(R.string.result_field_updated, field.name), note))
            }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            Log.e("FieldViewModel", "Duplicate field key on update: ${field.key}", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_key_duplicate, field.key)))
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to update field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /**
     * 저장 뒤 **전역 기본 필드 상태를 맞춘다** (B-119 — 설계 1-3).
     *
     * 승격·해제는 `field_definitions`가 아니라 **별도 표**를 건드리므로 필드 저장과 같은 값에
     * 담기지 않는다. 저장이 끝난 뒤 한 번 부르고, 결과 한 줄을 돌려준다.
     *
     * **해제가 필드를 지우지 않는다는 것이 요점이다** — 심긴 필드는 전 세계관에서 보통 필드로
     * 강등될 뿐이고 값은 그대로다(이 기능이 캐릭터 데이터를 지우는 경로는 없다).
     * 해제가 전 세계관에 걸린다는 사실은 화면이 먼저 묻는다([FieldManageFragment]).
     *
     * @return 사용자에게 보일 한 줄. 바뀐 것이 없으면 null.
     */
    /**
     * 사전 등록에 실패한 값을 말하는 한 줄 — **세 경로가 같은 문구를 쓴다**(사건 편집이
     * 이미 쓰던 그것). 실패의 촉발은 중복이 아니라 DB 예외이고, 말하지 않으면 사용자는
     * 자기가 미리 적어 둔 값이 어디에도 없다는 것을 알 길이 없다(개발 의도 2번).
     */
    private fun initialValuePartialNote(
        outcome: com.novelcharacter.app.data.repository.FieldValueLibraryRepository.InitialValueOutcome
    ): String? =
        if (outcome.failed <= 0) null
        else app.getString(R.string.event_field_initial_values_partial, outcome.failed)

    private suspend fun syncDefaultField(field: FieldDefinition, want: Boolean?): String? {
        if (want == null) return null
        val repo = app.defaultFieldTemplateRepository
        val existing = repo.getBySlot(field.entityType, field.key)
        return when {
            // 켰다 — 템플릿이 없으면 만들고, **있으면 다시 심는다.**
            //
            // 뒤엣것을 빠뜨리면 조용한 무동작이 난다: 스위치는 이 필드의 표식만 보고 켜지므로
            // (`DefaultFieldRef.isLinked`), *템플릿은 있는데 이 세계관의 필드만 연결이 없는*
            // 상태에서 사용자가 켜면 **아무 일도 일어나지 않는다.** 그 상태는 실재한다 —
            // 월드패키지·엑셀·휴지통 복원으로 들어온 세계관은 심기를 지나치지 않는다.
            // 다시 심기는 이미 있는 필드를 덮지 않으므로(연결만 건다) 안전하고, 겸사겸사
            // 그 사이 생긴 다른 세계관까지 따라잡는다.
            want -> {
                val r = if (existing == null) repo.promoteAndPlant(field) else repo.plantAll(existing)
                if (r.planted == 0 && r.linked == 0) app.getString(R.string.default_field_plant_none)
                else app.getString(R.string.default_field_planted, r.planted, r.linked)
            }
            existing != null -> {
                val outcome = repo.deleteTemplate(existing)
                // 두 수를 갈라 말한다 — 값 없는 무소속 그림자는 함께 정리되고(2026.08.07 확정),
                // 그 사실을 확인 문구도 결과 문구도 말하지 않고 있었다.
                if (outcome.cleanedShadows > 0) {
                    app.getString(
                        R.string.default_field_demoted_with_cleanup,
                        existing.name, outcome.demoted, outcome.cleanedShadows
                    )
                } else {
                    app.getString(R.string.default_field_demoted, existing.name, outcome.demoted)
                }
            }
            // 껐는데 템플릿이 애초에 없다 — 아무 일도 하지 않고 아무 말도 하지 않는다.
            else -> null
        }
    }

    /**
     * 목록 스위치 등 **1탭 설정 변경** 전용 — 재정렬과 같은 규칙: 성공 무통보, 실패만 알림(원칙 04).
     * 키·이름이 바뀌지 않는 config 전용 갱신이므로 [updateField]의 키 이관 경로를 타지 않는다.
     */
    fun updateFieldQuiet(field: FieldDefinition) = viewModelScope.launch {
        try {
            universeRepository.updateField(field)
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to update field config", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /**
     * 키 변경의 파급 — 참조 수식과 상태변화 이력을 필드 저장과 **한 트랜잭션**으로 갱신한다.
     *
     * @param formulas 키를 고쳐 준 참조 수식 수.
     * @param history 새 키로 옮긴 상태변화 이력 건수(작품에 든 캐릭터 + 가려낸 미분류 캐릭터).
     * @param unresolvedHistory **옛 키로 남긴** 이력 건수 — 어느 세계관 것인지 가리지 못한
     *   미분류 캐릭터의 몫이다(B-13 · [UnassignedHistoryScope]). 0이면 말하지 않는다.
     */
    private data class KeyMigration(
        val formulas: Int = 0,
        val history: Int = 0,
        val unresolvedHistory: Int = 0
    ) {
        val changedSomething: Boolean get() = formulas > 0 || history > 0
    }

    private suspend fun migrateFieldKey(old: FieldDefinition, new: FieldDefinition): KeyMigration {
        var formulaCount = 0
        var historyCount = 0
        var unresolvedCount = 0
        app.database.withTransaction {
            // 참조 수식 모집단은 **바뀌는 필드와 같은 종류**다 — 종류가 다르면 같은 key가
            // 공존할 수 있고(인덱스가 (universeId, entityType, key)), 남의 종류 수식을 고치면
            // 그 화면의 수식이 존재하지 않는 키를 가리키게 된다(R-29).
            val referencing = getReferencingCalculatedFields(new.universeId, old.key, new.entityType)
                .filter { it.id != new.id }
            // field('키') / field("키") / field(키) 3형태 완전 일치 치환 (부분 문자열 오탐 방지)
            val refRegex = Regex("""field\([${RegexCharClasses.WHITESPACE}]*(['"]?)${Regex.escape(old.key)}\1[${RegexCharClasses.WHITESPACE}]*\)""")
            for (f in referencing) {
                val cfg = org.json.JSONObject(f.config)
                val formula = cfg.stringOr("formula", "")
                val updated = refRegex.replace(formula) { m ->
                    "field(${m.groupValues[1]}${new.key}${m.groupValues[1]})"
                }
                if (updated != formula) {
                    cfg.put("formula", updated)
                    universeRepository.updateField(f.copy(config = cfg.toString()))
                    formulaCount++
                }
            }
            val renameUniverseId = new.universeId
            if (renameUniverseId != null) {
                val changeDao = app.database.characterStateChangeDao()
                // 전역 구역(null)은 이관할 이력이 원리적으로 없다 — 연표는 세계관 기능이다.
                historyCount = changeDao.migrateFieldKeyForUniverse(renameUniverseId, old.key, new.key)

                // 위 질의의 `JOIN novels`가 **원리적으로 빠뜨리는** 미분류 캐릭터(B-13).
                // 통째로 옮길 수는 없다 — 키는 세계관 안에서만 유일해서, 이력 행 하나만으로는
                // 그것이 어느 세계관의 것인지 알 수 없다. 값이 가리키는 세계관으로 가린다.
                val candidates = changeDao.getUnassignedCharactersWithFieldKey(old.key)
                if (candidates.isNotEmpty()) {
                    val attribution = SqlInChunks
                        .flat(candidates) {
                            app.database.characterFieldValueDao().getValueUniversesForCharacters(it)
                        }
                        .groupBy({ it.characterId }, { it.universeId })
                        .mapValues { (_, ids) -> ids.toSet() }
                    val plan = UnassignedHistoryScope.plan(candidates, attribution, renameUniverseId)
                    // 두 질의 다 **영향 건수를 돌려준다** — 나눠 물으면 조각별 답이 오므로
                    // 이어 붙이는 것이 아니라 더해야 한다. 그냥 나누면 마지막 조각의 수가
                    // 전체인 척하고, 화면이 사용자에게 **적은 수를 말한다** (B-242).
                    historyCount += SqlInChunks.sum(plan.migrate) {
                        changeDao.migrateFieldKeyForCharacters(it, old.key, new.key)
                    }
                    // 가리지 못한 것은 **건드리지 않고 센다** — 조용히 넘기면 사용자는 자기
                    // 연표 일부가 옛 키에 남은 것을 영영 모른다(개발 의도 2번).
                    unresolvedCount = SqlInChunks.sum(plan.reportable) {
                        changeDao.countByFieldKeyForCharacters(it, old.key)
                    }
                }
            }
            universeRepository.updateField(new)
        }
        return KeyMigration(formulaCount, historyCount, unresolvedCount)
    }

    fun deleteField(field: FieldDefinition) = viewModelScope.launch {
        try {
            universeRepository.deleteField(field)
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_deleted, field.name)))
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to delete field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_delete_failed), e.message))
        }
    }

    fun updateFieldOrder(fields: List<FieldDefinition>) = viewModelScope.launch {
        try {
            val updated = fields.mapIndexed { index, field -> field.copy(displayOrder = index) }
            universeRepository.updateFieldsOrder(updated)
            // 재정렬은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to update field order", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_reorder_failed), e.message))
        }
    }

    /**
     * 다른 세계관 + 프리셋의 필드 목록 통합 조회 — **관리 중인 [entityType]과 같은 종류만** 모은다.
     *
     * 종전에는 종류를 가리지 않고 캐릭터 필드만 조회해, 사건 필드 탭에서 가져오기를 하면
     * 캐릭터 필드가 소스로 뜨고 그것을 캐릭터 필드로 심어 **보고 있는 목록에 아무것도 나타나지
     * 않았다**(중복 판정·순서도 캐릭터 모집단 기준이었다). 소스·중복·순서·삽입이 모두 같은
     * 종류를 봐야 한다 — 한 곳만 어긋나도 조용한 무동작이 된다.
     */
    suspend fun getFieldsFromAllSources(
        currentUniverseId: Long,
        entityType: String
    ): Map<String, List<FieldDefinition>> = collectSources(currentUniverseId, entityType)

    /**
     * 같은 합집합을 **종류를 가리지 않고** 모은다 — 프리셋 병합 미리보기(B-89) 전용.
     *
     * 걸러 받지 않는 것은 사용자 확정이다(③): 프리셋은 `entityType`을 담고 있고, 캐릭터
     * 필드만 받으면 열린 구조를 스스로 닫는 것이다(원칙 01). 원치 않는 종류는 미리보기가
     * 종류별로 보여 주므로 그 자리에서 빼면 된다.
     *
     * 위 [getFieldsFromAllSources]와 **본체를 공유한다** — 두 벌이 되면 한쪽만 고쳐진다.
     */
    suspend fun getMergeSources(currentUniverseId: Long): Map<String, List<FieldDefinition>> =
        collectSources(currentUniverseId, entityType = null)

    /**
     * 소스 합집합의 단일 소스. [entityType]이 null이면 종류를 가리지 않는다.
     *
     * 어느 쪽이든 **한 소스가 내주는 목록의 종류 구성이 곧 심기는 구성**이다 — 거르는 자리와
     * 심는 자리가 갈리면 화면에 없는 것이 들어가거나 고른 것이 사라진다(R-29).
     */
    private suspend fun collectSources(
        currentUniverseId: Long,
        entityType: String?
    ): Map<String, List<FieldDefinition>> {
        val result = linkedMapOf<String, List<FieldDefinition>>()

        // 0. 기본 제공 추천 — **맨 앞에 둔다.** 재고가 0인 종류(사건)에서는 이것이 유일한
        // 출발점이고, 목록의 첫 항목이 곧 기본 선택이라 여기 있어야 값을 한다.
        // 자동으로 심지 않으므로(설계 D1) 프리셋 `fields`가 아니라 이 경로로만 나온다.
        val recommended = if (entityType == null) PresetTemplates.allRecommendedFields()
        else PresetTemplates.recommendedFields(entityType)
        if (recommended.isNotEmpty()) {
            result[app.getString(R.string.field_source_recommended)] = recommended
        }

        // 1. 다른 세계관 (이름 중복 시 구분을 위해 카운터 추가)
        val allUniverses = universeRepository.getAllUniversesList()
        val nameCount = mutableMapOf<String, Int>()
        for (universe in allUniverses) {
            if (universe.id == currentUniverseId) continue
            val fields = if (entityType == null) {
                app.database.fieldDefinitionDao().getFieldsByUniverseAllTypes(universe.id)
            } else {
                fieldsOf(universe.id, entityType)
            }
            if (fields.isNotEmpty()) {
                val count = nameCount.getOrDefault(universe.name, 0)
                nameCount[universe.name] = count + 1
                val label = if (count > 0) "${universe.name} (${count + 1})" else universe.name
                result[label] = fields
            }
        }

        // 2. 내장 프리셋 템플릿 — 해당 종류가 없는 프리셋은 빈 목록으로 뜨지 않게 건너뛴다
        for (preset in PresetTemplates.getBuiltInTemplates()) {
            val fields = preset.fields.ofType(entityType)
            if (fields.isEmpty()) continue
            result["${preset.universe.name} (프리셋)"] = fields
        }

        // 3. 사용자 정의 프리셋
        val userPresets = userPresetDao.getAllTemplatesList()
        for (preset in userPresets) {
            val template = PresetTemplates.fromUserPreset(preset)
            val fields = template.fields.ofType(entityType)
            if (fields.isEmpty()) continue
            result["${template.universe.name} (사용자 프리셋)"] = fields
        }

        return result
    }

    /** null이면 전 종류. 걸러 내는 자리를 하나로 모아 호출부가 조건을 잊지 못하게 한다. */
    private fun List<FieldDefinition>.ofType(entityType: String?): List<FieldDefinition> =
        if (entityType == null) this else filter { it.entityType == entityType }

    /** 관리 중인 종류에 맞는 필드 조회 — 종류 분기를 한 자리에 모아 호출부가 어긋나지 않게 한다. */
    private suspend fun fieldsOf(universeId: Long?, entityType: String): List<FieldDefinition> =
        // null = 전역 구역(무소속 — B-119 확장). 참조 수식의 모집단도 그 구역이다 —
        // 전역 필드의 key를 세계관 수식이 참조할 수는 없다(다른 구역의 필드는 폼에 함께
        // 뜨지 않으므로 참조가 성립하지 않는다).
        if (universeId == null) {
            app.database.fieldDefinitionDao().getGlobalFieldsList(entityType)
        } else when (entityType) {
            FieldDefinition.ENTITY_EVENT -> universeRepository.getEventFieldsByUniverseList(universeId)
            FieldDefinition.ENTITY_NOVEL -> universeRepository.getNovelFieldsByUniverseList(universeId)
            else -> universeRepository.getFieldsByUniverseList(universeId)
        }

    /** 현재 세계관에서 관리 중인 종류의 필드 키 목록 조회 (중복 표시용) */
    suspend fun getCurrentFieldKeys(universeId: Long, entityType: String): Set<String> {
        return fieldsOf(universeId, entityType).map { it.key }.toSet()
    }

    /**
     * 지정 필드 키를 formula에서 참조하는 CALCULATED 필드 목록 조회.
     *
     * **모집단은 같은 [entityType]이다**(R-29). 수식은 자기 종류의 필드값만 읽으므로
     * (`FormulaEvaluator`에 넘어가는 정의 목록이 종류별이다) 캐릭터 필드를 모집단으로 세면
     * 사건·작품 필드의 키를 바꿀 때 그 종류의 수식이 조용히 깨지고, 삭제 경고도 뜨지 않는다.
     */
    suspend fun getReferencingCalculatedFields(
        universeId: Long?,
        fieldKey: String,
        entityType: String = FieldDefinition.ENTITY_CHARACTER
    ): List<FieldDefinition> {
        val allFields = fieldsOf(universeId, entityType)
        return allFields.filter { field ->
            if (field.fieldType != FieldType.CALCULATED) return@filter false
            val formula = try {
                org.json.JSONObject(field.config).stringOr("formula", "")
            } catch (_: Exception) { "" }
            formula.contains("field('$fieldKey')") ||
                formula.contains("field(\"$fieldKey\")") ||
                formula.contains("field($fieldKey)")
        }
    }

    /**
     * 선택된 필드를 현재 세계관의 [entityType] 종류로 복사.
     *
     * 중복 판정과 순서 산정은 **같은 종류의 기존 필드**만 모집단으로 삼는다 — key 유일성 제약이
     * `(universeId, entityType, key)`라(FieldDefinition 인덱스) 종류가 다르면 같은 key가 공존할
     * 수 있고, 캐릭터 필드를 모집단으로 세면 사건 필드를 넣을 때 멀쩡한 key가 중복으로 걸린다.
     */
    fun importFields(
        targetUniverseId: Long,
        sourceFields: List<FieldDefinition>,
        entityType: String
    ) = viewModelScope.launch {
        try {
            val inserted = importFieldsNow(targetUniverseId, sourceFields, entityType)
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                if (inserted > 0) app.getString(R.string.result_field_imported, inserted)
                else app.getString(R.string.result_field_import_none)))
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to import fields", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_import_failed), e.message))
        }
    }

    /**
     * [importFields]의 본체 — **심은 수를 돌려주고 고지는 하지 않는다.**
     *
     * 사건 편집처럼 `_result`를 관찰하지 않는 화면이 이 경로를 쓰기 때문이다. 고지를 여기서
     * 하면 그 화면에서는 조용해진다(`DataProvider.insertEventField`의 KDoc이 같은 이유로
     * 세운 규약). 실패는 예외로 올려 호출부가 알린다.
     */
    suspend fun importFieldsNow(
        targetUniverseId: Long,
        sourceFields: List<FieldDefinition>,
        entityType: String
    ): Int {
        run {
            val currentFields = fieldsOf(targetUniverseId, entityType)
            val existingKeys = currentFields.map { it.key }.toSet()
            val maxOrder = currentFields.maxOfOrNull { it.displayOrder } ?: -1

            val newFields = sourceFields
                .filter { it.key !in existingKeys }
                .mapIndexed { index, field ->
                    field.copy(
                        id = 0,
                        universeId = targetUniverseId,
                        // 소스는 이미 같은 종류로 걸러져 있으나 명시해 불변식을 코드에 남긴다
                        entityType = entityType,
                        displayOrder = maxOrder + 1 + index,
                        // 등급 체계 참조는 세계관 안에서만 성립한다(U-1) — 다른 세계관에서
                        // 가져온 필드의 참조를 그대로 두면 남의 세계관 체계를 가리키는 유령이
                        // 된다. 실효 표는 config에 물질화되어 있어 강등해도 표·값·통계는 그대로다.
                        config = if (field.universeId != targetUniverseId) {
                            com.novelcharacter.app.data.model.FieldConfigTransfer.demoteAcrossUniverse(field.config)
                        } else {
                            field.config
                        }
                    )
                }
            if (newFields.isEmpty()) return 0
            universeRepository.insertAllFields(newFields)
            return newFields.size
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 프리셋·세계관 병합 (B-89)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 소스 필드를 대상 세계관에 합칠 계획을 세운다 — **쓰지 않는다.**
     *
     * 중복 판정 모집단은 **전 종류**다([getFieldsByUniverseAllTypes]). 한 종류만 보면 다른
     * 종류의 중복을 놓쳐 삽입이 유니크 제약에 걸리고, 그 실패는 화면이 예고하지 않은 것이다
     * (R-29 — 조회·판정·순서·쓰기가 모두 같은 모집단을 봐야 한다).
     */
    suspend fun buildMergePlan(
        targetUniverseId: Long,
        sourceFields: List<FieldDefinition>
    ): PresetMerge.Plan = PresetMerge.buildPlan(
        sourceFields,
        app.database.fieldDefinitionDao().getFieldsByUniverseAllTypes(targetUniverseId)
    )

    /**
     * 고른 처분을 실제로 반영한다 — 삽입·덮어쓰기·되돌리기 백업이 **한 트랜잭션**이다.
     *
     * 백업이 트랜잭션 밖이면 쓰기가 실패했을 때 되돌릴 것이 없는 백업만 남고, 백업이 실패했을
     * 때는 되돌릴 수 없는 덮어쓰기가 남는다. 둘 다 R-4가 막으려는 상태다.
     *
     * @param sourceName 소스(프리셋·세계관)의 이름 — 되돌리기 항목이 무엇을 물리는지 말한다.
     * @return 실제로 심고 덮은 것. 화면은 이것으로 고지한다 — **고른 수가 아니라 반영된 수**여야
     *   한다(종전 경로는 고른 수를 토스트로 말하고 실제 반영 수를 결과 채널로 말해, 같은 조작에
     *   숫자가 둘이었다).
     */
    suspend fun applyMergePlan(
        targetUniverseId: Long,
        plan: PresetMerge.Plan,
        selected: Set<String>,
        sourceName: String
    ): PresetMerge.Resolution {
        val existing = app.database.fieldDefinitionDao().getFieldsByUniverseAllTypes(targetUniverseId)
        val maxOrder = existing
            .groupBy { it.entityType }
            .mapValues { (_, fields) -> fields.maxOf { it.displayOrder } }
        val resolution = PresetMerge.resolve(plan, selected, targetUniverseId, maxOrder)
        if (resolution.isEmpty) return resolution

        app.database.withTransaction {
            // 덮기 **전에** 백업한다 — 순서가 뒤집히면 백업이 덮인 값을 담는다.
            if (resolution.backups.isNotEmpty()) {
                // **한 인스턴스 = 한 작업**(위 대결 축과 같은 근거) — 앱 수준 싱글턴을 쓰면
                // 이 덮어쓰기 백업이 앱 수명 안의 다른 조작들과 한 묶음으로 붙는다.
                com.novelcharacter.app.data.repository.TrashRepository(app.database)
                    .snapshotFieldDefinitions(targetUniverseId, resolution.backups, sourceName)
            }
            if (resolution.inserts.isNotEmpty()) {
                universeRepository.insertAllFields(resolution.inserts)
            }
            for (field in resolution.updates) {
                universeRepository.updateField(field)
            }
        }
        return resolution
    }

    private companion object {
        /**
         * 같은 prefs 파일의 `entity_type`과 **다른 키여야 한다**(규약 R-28 —
         * `tools/check_prefs_keys.sh`). 둘 다 이 화면의 보기 상태이므로 한 파일에 둔다.
         */
        const val KEY_SORT_MODE = "sort_mode"
    }
}
