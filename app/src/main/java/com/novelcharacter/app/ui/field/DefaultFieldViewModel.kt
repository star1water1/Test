package com.novelcharacter.app.ui.field

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DefaultFieldTemplate
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.DefaultFieldPlan
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import kotlinx.coroutines.launch

/**
 * 기본 필드 관리 화면의 상태 (B-119 — 설계 1-4).
 *
 * 계획은 [DefaultFieldPlan]이, 쓰기는
 * [com.novelcharacter.app.data.repository.DefaultFieldTemplateRepository]가 한다 —
 * 이 클래스는 **그 둘을 화면에 잇는 것 말고는 아무 판정도 하지 않는다.**
 */
class DefaultFieldViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val repository = app.defaultFieldTemplateRepository

    val templates: LiveData<List<DefaultFieldTemplate>> = repository.all

    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    /**
     * 목록 행의 *"세계관 N곳 연결 · M곳 다름"* — 템플릿 code → (연결 수, 다름 수).
     *
     * 목록과 함께 한 번 세고 화면이 그 표를 읽는다. 행마다 따로 세면 목록 스크롤이 DB 조회를
     * 행 수만큼 낸다(목표 규모 270 세계관 × 템플릿 수 — `scalability_performance` 4장).
     */
    private val _linkCounts = MutableLiveData<Map<String, Pair<Int, Int>>>(emptyMap())
    val linkCounts: LiveData<Map<String, Pair<Int, Int>>> = _linkCounts

    fun refreshCounts(templates: List<DefaultFieldTemplate>) = viewModelScope.launch {
        try {
            _linkCounts.value = repository.linkSummaries(templates)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to count default field links", e)
        }
    }

    /**
     * 관리 화면의 '새로 만들기' — 만들고 곧바로 전 세계관에 심는다.
     *
     * [com.novelcharacter.app.data.repository.DefaultFieldTemplateRepository.promoteAndPlant]를
     * 쓰는 것은 **같은 자리에 이미 템플릿이
     * 있는 경우를 예외가 아니라 결과로 만들기 위해서다** — 유니크 `(entityType, key)`가 둘째
     * 템플릿을 거절하므로, 그대로 두면 사용자는 *"저장에 실패했습니다"*만 받는다.
     * 그쪽은 있는 템플릿을 돌려주고 심기만 다시 돌린다.
     */
    fun createFromField(field: FieldDefinition) = viewModelScope.launch {
        try {
            val r = repository.promoteAndPlant(field)
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                r.template.name, plantedText(r.planted, r.linked)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create default field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /** 템플릿 정의만 고친다 — **퍼뜨리지 않는다**(명시적 전파. 설계 1-3). */
    fun saveTemplate(template: DefaultFieldTemplate) = viewModelScope.launch {
        try {
            repository.updateTemplate(template)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /** '다시 심기' — 그 사이 생긴 세계관·지워진 필드를 따라잡는다. */
    fun replant(template: DefaultFieldTemplate) = viewModelScope.launch {
        try {
            val r = repository.plantAll(template)
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                template.name, plantedText(r.planted, r.linked)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replant default field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /**
     * 해제 결과 한 줄 — **정리된 그림자가 0이면 그 절을 붙이지 않는다**(R-14 결:
     * 일어나지 않은 일을 0으로 말하지 않는다). 두 화면이 같은 함수를 쓴다.
     */
    private fun unlinkMessage(
        name: String,
        outcome: com.novelcharacter.app.data.repository.DefaultFieldTemplateRepository.UnlinkOutcome
    ): String = if (outcome.cleanedShadows > 0) {
        app.getString(
            R.string.default_field_demoted_with_cleanup, name, outcome.demoted, outcome.cleanedShadows
        )
    } else {
        app.getString(R.string.default_field_demoted, name, outcome.demoted)
    }

    /**
     * 해제 — 심긴 필드는 보통 필드로 **강등**되고, 값이 없는 무소속 구역의 그림자는
     * **함께 정리된다**(2026.08.07 확정). 캐릭터 값은 어느 갈래에서도 지우지 않는다.
     *
     * 두 수를 갈라 말한다 — 합치면 사용자가 자기 필드가 지워진 줄 안다.
     */
    fun unlink(template: DefaultFieldTemplate) = viewModelScope.launch {
        try {
            val outcome = repository.deleteTemplate(template)
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                unlinkMessage(template.name, outcome)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unlink default field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /** 전파 미리보기를 세운다 — **쓰지 않는다.** 화면이 고른 뒤 [applyPropagate]가 쓴다. */
    suspend fun buildPropagatePlan(
        template: DefaultFieldTemplate,
        previous: DefaultFieldTemplate? = null
    ): DefaultFieldPlan.PropagatePlan = repository.planPropagate(template, previous)

    fun applyPropagate(
        template: DefaultFieldTemplate,
        plan: DefaultFieldPlan.PropagatePlan,
        // null 원소 = 전역 구역(무소속 — B-119 확장). 전파 미리보기의 "무소속" 행이 이것이다.
        selected: Set<Long?>
    ) = viewModelScope.launch {
        try {
            val r = repository.applyPropagate(template, plan, selected)
            reportResult(_result, OpResult.success(OpResult.CAT_FIELD,
                app.getString(R.string.default_field_propagated, r.changed)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to propagate default field", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_FIELD,
                app.getString(R.string.result_field_update_failed), e.message))
        }
    }

    /** 두 수를 가른다 — 합치면 사용자가 자기가 고쳐 둔 필드가 덮인 줄 안다(설계 1-3). */
    private fun plantedText(planted: Int, linked: Int): String =
        if (planted == 0 && linked == 0) app.getString(R.string.default_field_plant_none)
        else app.getString(R.string.default_field_planted, planted, linked)

    private companion object {
        const val TAG = "DefaultFieldViewModel"
    }
}
