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
import com.novelcharacter.app.util.PresetTemplates
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import android.util.Log
import kotlinx.coroutines.launch

class FieldViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val universeRepository = app.universeRepository
    private val userPresetDao = app.database.userPresetTemplateDao()

    private val _universeId = MutableLiveData<Long>()
    val universeId: LiveData<Long> = _universeId

    // 데이터 처리 결과 알림 채널 — 필드 저장 성공/실패·자동 교정 정보를 OpResult로 일원화
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    // 관리 대상 (B-10): 캐릭터 필드 / 사건 필드 전환
    private val _entityType = MutableLiveData(FieldDefinition.ENTITY_CHARACTER)
    val entityType: LiveData<String> = _entityType

    private val _fieldsTrigger = MediatorLiveData<Unit>().apply {
        addSource(_universeId) { value = Unit }
        addSource(_entityType) { value = Unit }
    }

    val fields: LiveData<List<FieldDefinition>> = _fieldsTrigger.switchMap {
        val id = _universeId.value ?: return@switchMap MutableLiveData(emptyList<FieldDefinition>())
        if (_entityType.value == FieldDefinition.ENTITY_EVENT) {
            universeRepository.getEventFieldsByUniverse(id)
        } else {
            universeRepository.getFieldsByUniverse(id)
        }
    }

    fun setUniverseId(id: Long) {
        _universeId.value = id
    }

    fun setEntityType(type: String) {
        if (_entityType.value != type) _entityType.value = type
    }

    fun currentEntityType(): String = _entityType.value ?: FieldDefinition.ENTITY_CHARACTER

    fun insertField(field: FieldDefinition) = viewModelScope.launch {
        try {
            universeRepository.insertField(field)
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_added, field.name)))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            Log.e("FieldViewModel", "Duplicate field key: ${field.key}", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                "필드 키 '${field.key}'이(가) 이미 존재합니다."))
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to insert field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                "필드 저장에 실패했습니다.", e.message))
        }
    }

    fun updateField(field: FieldDefinition) = viewModelScope.launch {
        try {
            // 키 변경 자동 감지: 참조 수식·상태변화 이력이 무통보로 파손되지 않도록 함께 갱신한다
            val old = app.database.fieldDefinitionDao().getFieldById(field.id)
            if (old != null && old.key != field.key) {
                val (formulaCount, historyCount) = migrateFieldKey(old, field)
                // 자동 교정이 일어났으면 상세로 노출 (조용한 파급 방지)
                val detail = if (formulaCount > 0 || historyCount > 0)
                    "필드 키 변경('${old.key}'→'${field.key}'): 참조 수식 ${formulaCount}건, 상태변화 이력 ${historyCount}건을 자동으로 갱신했습니다."
                    else null
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                    app.getString(R.string.result_field_updated, field.name), detail))
            } else {
                universeRepository.updateField(field)
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                    app.getString(R.string.result_field_updated, field.name)))
            }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            Log.e("FieldViewModel", "Duplicate field key on update: ${field.key}", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                "필드 키 '${field.key}'이(가) 이미 존재합니다."))
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to update field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /** 키 변경 시 참조 수식과 상태변화 이력을 필드 저장과 함께 단일 트랜잭션으로 갱신한다. */
    private suspend fun migrateFieldKey(old: FieldDefinition, new: FieldDefinition): Pair<Int, Int> {
        var formulaCount = 0
        var historyCount = 0
        app.database.withTransaction {
            val referencing = getReferencingCalculatedFields(new.universeId, old.key)
                .filter { it.id != new.id }
            // field('키') / field("키") / field(키) 3형태 완전 일치 치환 (부분 문자열 오탐 방지)
            val refRegex = Regex("""field\(\s*(['"]?)${Regex.escape(old.key)}\1\s*\)""")
            for (f in referencing) {
                val cfg = org.json.JSONObject(f.config)
                val formula = cfg.optString("formula", "")
                val updated = refRegex.replace(formula) { m ->
                    "field(${m.groupValues[1]}${new.key}${m.groupValues[1]})"
                }
                if (updated != formula) {
                    cfg.put("formula", updated)
                    universeRepository.updateField(f.copy(config = cfg.toString()))
                    formulaCount++
                }
            }
            historyCount = app.database.characterStateChangeDao()
                .migrateFieldKeyForUniverse(new.universeId, old.key, new.key)
            universeRepository.updateField(new)
        }
        return formulaCount to historyCount
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

    /** 다른 세계관 + 프리셋의 필드 목록 통합 조회 */
    suspend fun getFieldsFromAllSources(currentUniverseId: Long): Map<String, List<FieldDefinition>> {
        val result = linkedMapOf<String, List<FieldDefinition>>()

        // 1. 다른 세계관 (이름 중복 시 구분을 위해 카운터 추가)
        val allUniverses = universeRepository.getAllUniversesList()
        val nameCount = mutableMapOf<String, Int>()
        for (universe in allUniverses) {
            if (universe.id == currentUniverseId) continue
            val fields = universeRepository.getFieldsByUniverseList(universe.id)
            if (fields.isNotEmpty()) {
                val count = nameCount.getOrDefault(universe.name, 0)
                nameCount[universe.name] = count + 1
                val label = if (count > 0) "${universe.name} (${count + 1})" else universe.name
                result[label] = fields
            }
        }

        // 2. 내장 프리셋 템플릿
        for (preset in PresetTemplates.getBuiltInTemplates()) {
            val label = "${preset.universe.name} (프리셋)"
            result[label] = preset.fields
        }

        // 3. 사용자 정의 프리셋
        val userPresets = userPresetDao.getAllTemplatesList()
        for (preset in userPresets) {
            val template = PresetTemplates.fromUserPreset(preset)
            val label = "${template.universe.name} (사용자 프리셋)"
            result[label] = template.fields
        }

        return result
    }

    /** 현재 세계관의 필드 키 목록 조회 */
    suspend fun getCurrentFieldKeys(universeId: Long): Set<String> {
        return universeRepository.getFieldsByUniverseList(universeId).map { it.key }.toSet()
    }

    /** 지정 필드 키를 formula에서 참조하는 CALCULATED 필드 목록 조회 */
    suspend fun getReferencingCalculatedFields(universeId: Long, fieldKey: String): List<FieldDefinition> {
        val allFields = universeRepository.getFieldsByUniverseList(universeId)
        return allFields.filter { field ->
            if (field.type != "CALCULATED") return@filter false
            val formula = try {
                org.json.JSONObject(field.config).optString("formula", "")
            } catch (_: Exception) { "" }
            formula.contains("field('$fieldKey')") ||
                formula.contains("field(\"$fieldKey\")") ||
                formula.contains("field($fieldKey)")
        }
    }

    /** 선택된 필드를 현재 세계관으로 복사 */
    fun importFields(targetUniverseId: Long, sourceFields: List<FieldDefinition>) = viewModelScope.launch {
        try {
            val currentFields = universeRepository.getFieldsByUniverseList(targetUniverseId)
            val existingKeys = currentFields.map { it.key }.toSet()
            val maxOrder = currentFields.maxOfOrNull { it.displayOrder } ?: -1

            val newFields = sourceFields
                .filter { it.key !in existingKeys }
                .mapIndexed { index, field ->
                    field.copy(
                        id = 0,
                        universeId = targetUniverseId,
                        displayOrder = maxOrder + 1 + index
                    )
                }
            if (newFields.isNotEmpty()) {
                universeRepository.insertAllFields(newFields)
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                    app.getString(R.string.result_field_imported, newFields.size)))
            } else {
                reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                    app.getString(R.string.result_field_import_none)))
            }
        } catch (e: Exception) {
            Log.e("FieldViewModel", "Failed to import fields", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_import_failed), e.message))
        }
    }
}
