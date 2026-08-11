package com.novelcharacter.app.ui.timeline

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.ai.AiErrorMessages
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.EventFieldAiSuggester
import kotlinx.coroutines.launch

/**
 * 사건 필드 AI 추천의 **실행 자리** (B-43).
 *
 * ## 왜 창이 아니라 ViewModel이 실행하는가
 *
 * 캐릭터 축이 같은 결론에 이미 도달해 있다(`CharacterViewModel`의 AI 절 주석) — 뷰 수명
 * 스코프에서 돌리면 **회전 한 번이 유료 응답을 폐기하고**, 파괴된 화면의 진행 다이얼로그를
 * 닫다가 죽는다. 여기는 그 위험이 한 겹 더 크다: 호스트가 셋이고(연표·캐릭터 상세·캐릭터
 * 편집) 사건 편집은 다이얼로그라, 화면 회전 말고도 창이 다시 만들어질 길이 많다.
 *
 * **스코프는 사건 편집 창 자신이다**(`ViewModelProvider(dialogFragment)`). 호스트 VM에
 * 매달면 호스트마다 다른 VM을 써야 하고, 그러면 같은 창의 같은 기능이 호스트에 따라
 * 다르게 살아남는다. 창은 회전을 넘어 같은 인스턴스로 복원되므로 이 스코프면 충분하다.
 *
 * ## 결과를 언제 버리는가
 *
 * [clearResult]는 **검토 다이얼로그의 액션 시점**에만 부른다 — 결과가 도착하자마자 비우면
 * 검토 중 회전에서 이미 결제한 응답이 사라진다(`check_ai_review_retention.sh`가 캐릭터·
 * 이미지 축에서 잠근 것과 같은 규약).
 */
class EventFieldAiViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp

    /** 추천 1회 실행분 — 검토 화면이 필요로 하는 전부. */
    data class Run(
        val targets: List<CharacterFieldAiSuggester.FieldSpec>,
        val outcome: CharacterFieldAiSuggester.SuggestOutcome,
        /**
         * 요청 시점의 사건 id(미저장 신규는 -1). 창이 결과를 받을 때 지금 편집 중인 사건과
         * 견줘, 다른 사건이면 적용하지 않는다 — 캐릭터 축이 A-3에서 세운 오적용 차단과 같다.
         */
        val eventId: Long
    )

    val running = MutableLiveData(false)
    val result = MutableLiveData<Run?>()

    fun clearResult() { result.value = null }

    /**
     * 유료 응답을 **되살린다** — 적용이 실패했을 때 다시 결제하지 않고 검토로 돌아가기 위한 자리.
     * 캐릭터·이미지 축이 B-163에서 세운 규약과 같다(비우는 것은 뷰가 아니라 여기다).
     */
    fun restoreResult(run: Run) { result.value = run }

    /**
     * 추천 실행. 이미 실행 중이면 false — **호출측이 반드시 사용자에게 알린다**(무통보 무시 금지).
     */
    fun run(
        context: EventFieldAiSuggester.EventAiContext,
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        eventId: Long
    ): Boolean {
        if (running.value == true) return false
        running.value = true
        viewModelScope.launch {
            try {
                val settings = AiPromptSettings(getApplication())
                val outcome = EventFieldAiSuggester(AiService(getApplication())).suggest(
                    context = context,
                    targets = withFieldUsage(targets, settings),
                    scope = settings.eventContextScope,
                    minConfidence = settings.minConfidence,
                    creativity = settings.creativity
                ) { failure -> AiErrorMessages.of(getApplication(), failure) }
                result.value = Run(targets, outcome, eventId)
            } finally {
                running.value = false
            }
        }
        return true
    }

    /**
     * 대상 스펙에 값 라이브러리의 **기존 사용값**을 싣는다 — 캐릭터 축과 같은 이유이고
     * 같은 순수 함수를 쓴다([CharacterFieldAiSuggester.withLibraryUsage]).
     *
     * 조회 실패는 추천 자체를 막지 않는다. 용례가 없으면 표기 기조가 덜 실릴 뿐이고,
     * 그 때문에 요청을 통째로 막으면 사용자는 얻을 수 있던 것도 못 얻는다.
     */
    private suspend fun withFieldUsage(
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        settings: AiPromptSettings
    ): List<CharacterFieldAiSuggester.FieldSpec> {
        val ids = targets.filter { it.libraryEligible && it.fieldId > 0 }.map { it.fieldId }
        if (ids.isEmpty()) return targets
        val byField = try {
            app.fieldValueLibraryRepository.entriesForFields(ids)
        } catch (e: Exception) {
            Log.e("EventFieldAiViewModel", "Failed to load field value library for event AI suggest", e)
            return targets
        }
        val exampleLimit = settings.usageExampleCount
        return targets.map { spec ->
            val entries = byField[spec.fieldId] ?: return@map spec
            CharacterFieldAiSuggester.withLibraryUsage(spec, entries, exampleLimit)
        }
    }
}
