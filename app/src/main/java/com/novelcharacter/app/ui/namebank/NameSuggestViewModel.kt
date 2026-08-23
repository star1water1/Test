package com.novelcharacter.app.ui.namebank

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiErrorMessages
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.CharacterNameAiSuggester
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 이름 추천 시트의 **유료 응답 보관소이자 실행 엔진** (B-123 · R-38).
 *
 * 시트가 자기 ViewModel을 갖는 이유는 진입이 둘이기 때문이다 — 편집 폼의 ✨과 이름은행의
 * [AI로 이름 만들기]. 호스트 화면의 ViewModel에 실행을 넣으면 **같은 엔진이 두 벌**이 되고,
 * 두 벌은 반드시 갈린다(이 저장소가 반복해서 겪은 실패 형태). 시트의 `by viewModels()`는
 * 회전을 넘으므로 R-38이 요구하는 것 — *유료 응답은 뷰 밖, 실행도 뷰 밖* — 을 그대로 만족한다.
 *
 * **호스트가 다시 붙여야 하는 것은 콜백뿐이다**(적용 = 폼 위젯 기입). 재생성된 시트의 람다는
 * null이라 다시 붙이지 않으면 눌러도 아무 일이 없다 — 호스트가 `findFragmentByTag`로 찾아
 * 붙인다(`ImageManagerFragment.bindAiTagReviewCallbacks`가 세운 선례).
 *
 * **세션 풀은 시트가 살아 있는 동안만이다**(설계 7-4). 시트를 닫으면 이 ViewModel도 함께
 * 사라지고 그것이 맞다 — 닫는 것은 사용자가 그 세션을 끝낸 것이다. 남길 것은 [은행에 담기]로
 * 담은 이름이고, 그것은 DB에 있다.
 */
class NameSuggestViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp

    /**
     * 시트를 여는 쪽이 정하는 재료. **한 번만 먹인다** — 회전 뒤 호스트가 다시 불러도
     * 이미 있는 것을 덮지 않는다(라운드가 쌓인 뒤 덮으면 컨텍스트만 첫 라운드와 갈린다).
     */
    data class Setup(
        /** 편집 폼에서 온 캐릭터 컨텍스트. 은행 진입은 null이다. */
        val character: CharacterFieldAiSuggester.CharacterAiContext? = null,
        /** 견본을 뜰 세계관. 없으면 견본 없이 간다(작품 미지정 캐릭터·세계관 없는 은행 진입). */
        val universeId: Long? = null,
        val universeName: String = "",
        /** 화면 제목에 쓸 이름 — 캐릭터명 또는 세계관명. */
        val title: String = "",
        /** GENDER 역할 필드의 현재 값 — 프롬프트의 결 재료이자 담기의 기본 성별이다. */
        val gender: String = "",
        val fixedSurname: String = "",
        val fixedGiven: String = ""
    )

    private var setup: Setup = Setup()
    var initialized: Boolean = false
        private set

    private val _pool = MutableLiveData(CharacterNameAiSuggester.Pool())
    val pool: LiveData<CharacterNameAiSuggester.Pool> = _pool

    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    /**
     * **담기 진행 중** — 연타 빗장이 뷰가 아니라 여기 산다(R-65).
     *
     * 종전에는 빗장이 아예 없었다. [deposit]은 *중복 대조 → 삽입*인데 그 둘이 한
     * 트랜잭션이 아니었으므로, 두 번 누르면 **둘째 회차가 첫째의 삽입을 보기 전에** 대조를
     * 마쳐 같은 이름이 두 벌 들어갔다. 뷰에 두면 회전 한 번에 풀려 같은 창이 다시 열린다.
     */
    private val _depositing = MutableLiveData(false)
    val depositing: LiveData<Boolean> = _depositing

    /** 담기 계획 — 풀·은행·캐릭터 이름이 바뀔 때마다 다시 선다. 화면의 (N)이 이 값이다. */
    private val _depositPlan = MutableLiveData(
        CharacterNameAiSuggester.DepositPlan(emptyList(), 0, 0)
    )
    val depositPlan: LiveData<CharacterNameAiSuggester.DepositPlan> = _depositPlan

    /** 담기 결과 — 화면이 한 줄로 알리고 비운다. */
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    /**
     * 견본·대조 재료의 캐시.
     *
     * **캐릭터 쪽은 한 번만 뜬다** — 시트가 떠 있는 동안 캐릭터가 생기지 않기 때문이고
     * (이 시트에는 캐릭터를 만드는 길이 없다), 목표 규모 ×30에서 6,420행이라 라운드마다
     * 다시 뜨면 그 자체가 비용 축이 된다. **은행 쪽은 라운드마다 다시 뜬다** — 여기서
     * 담을 수 있고(그 순간 늘어난다) 표가 작다(×30에서 1,230행).
     */
    private var universeNames: List<String>? = null
    private var characterNames: List<String>? = null
    private var bankNames: List<String> = emptyList()

    fun setupOnce(setup: Setup) {
        if (initialized) return
        this.setup = setup
        initialized = true
        // 담기 계획의 재료를 미리 뜬다 — 첫 라운드 전에도 (0)이 정확해야 한다.
        viewModelScope.launch { refreshDepositMaterials() }
    }

    val title: String get() = setup.title
    val hasCharacter: Boolean get() = setup.character != null
    val fixedSurname: String get() = setup.fixedSurname
    val fixedGiven: String get() = setup.fixedGiven

    /** 모드 전환 — 풀을 비운다(전체 이름과 이명은 한 목록에 섞일 수 없다). */
    fun switchMode(mode: CharacterNameAiSuggester.Mode) {
        if (_pool.value?.mode == mode) return
        _pool.value = (_pool.value ?: CharacterNameAiSuggester.Pool()).switchedTo(mode)
        recomputeDepositPlan()
    }

    fun setMark(name: String, mark: CharacterNameAiSuggester.Mark) {
        val current = _pool.value ?: return
        _pool.value = current.withMark(name, mark)
        recomputeDepositPlan()
    }

    /**
     * 라운드 하나를 실행한다. 이미 실행 중이면 false — 호출측이 반드시 알린다(무통보 무시 금지).
     *
     * 실행이 여기 있는 이유는 R-38이다: 뷰 스코프에 두면 **회전 한 번이 결제 중인 요청을 끊는다.**
     */
    fun runRound(instruction: String): Boolean {
        if (_running.value == true) return false
        _running.value = true
        viewModelScope.launch {
            try {
                val pool = _pool.value ?: CharacterNameAiSuggester.Pool()
                val settings = AiPromptSettings(getApplication())
                val samples = loadSamples()
                val context = CharacterNameAiSuggester.NameContext(
                    character = setup.character,
                    universeName = setup.universeName,
                    existingNames = samples.first,
                    bankNames = samples.second,
                    fixedSurname = setup.fixedSurname,
                    fixedGiven = setup.fixedGiven,
                    gender = setup.gender
                )
                val request = CharacterNameAiSuggester.RoundRequest(
                    mode = pool.mode,
                    batchSize = settings.nameSuggestBatchSize,
                    keep = pool.namesMarked(CharacterNameAiSuggester.Mark.HEART),
                    avoid = pool.namesMarked(CharacterNameAiSuggester.Mark.REJECT),
                    instruction = instruction
                )
                val suggester = CharacterNameAiSuggester(AiService(getApplication()))
                val outcome = suggester.suggest(
                    context, request, settings.creativity,
                    // 사용자가 고친 메시지 양식 (2026.08.20). 손댄 적이 없으면 기본 양식이다.
                    settings.asTemplateSource()
                ) { failure ->
                    AiErrorMessages.of(getApplication(), failure)
                }
                // **응답이 오는 사이 풀이 바뀌었을 수 있다.** 시작 시점의 pool에 그대로 얹으면
                // 두 가지가 조용히 뒤집힌다: ⓐ 요청 중에 찍은 ♥·✕가 사라지고(그 사이의 표식이
                // 담긴 것은 지금 값이다) ⓑ 모드를 바꿨다면 **비운 풀이 되살아난다** — 게다가
                // 새 후보는 *옛 모드로 만든 이름*이라 화면이 없는 사실을 말하게 된다.
                // 그래서 쓰는 시점의 값에 얹고, 모드가 갈렸으면 얹지 않고 **말한다**.
                val latest = _pool.value ?: pool
                if (latest.mode != request.mode) {
                    reportResult(_result, OpResult.failure(
                        OpResult.CAT_NAMEBANK,
                        app.getString(R.string.ai_name_round_mode_changed),
                        outcome.candidates.joinToString(", ") { it.name }.ifBlank { null }
                    ))
                    return@launch
                }
                // 되풀이는 **응답이 준 것과 풀이 아는 것의 차이**라 순수 계층이 판정한다.
                val fresh = latest.freshOnly(outcome.candidates)
                val notices = buildNotices(outcome, fresh.repeated)
                if (fresh.candidates.isEmpty()) {
                    // **빈 판은 쌓지 않는다.** 쌓으면 직전 라운드가 '지난 라운드'로 접혀 내려가
                    // 사용자가 훑던 자리를 잃는다 — 아무것도 못 받은 실행이 **받아 둔 것까지
                    // 치우는** 셈이다. 대신 사유를 그대로 말한다(R-17 · B-144와 같은 축:
                    // 비용을 치른 실행은 결과가 비어도 침묵하지 않는다).
                    reportResult(_result, OpResult.failure(
                        OpResult.CAT_NAMEBANK,
                        app.getString(R.string.ai_name_round_empty),
                        notices.joinToString("\n").ifBlank { null }
                    ))
                } else {
                    _pool.value = latest.addRound(fresh.candidates, instruction, notices)
                }
                // 담은 것이 없어도 은행은 다른 화면에서 바뀔 수 있다 — 라운드마다 다시 뜬다.
                refreshDepositMaterials()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run name suggest round", e)
                // 여기서도 빈 판을 쌓지 않는다 — 이미 받아 둔 라운드를 밀어내지 않는다.
                reportResult(_result, OpResult.failure(
                    OpResult.CAT_NAMEBANK,
                    app.getString(R.string.ai_name_round_failed),
                    e.message
                ))
            } finally {
                _running.value = false
            }
        }
        return true
    }

    /**
     * 라운드 고지 — **조용히 버린 것이 없음을 항상 보인다**(R-14).
     *
     * 되풀이를 따로 세는 것은 드롭과 **처방이 다르기 때문**이다(R-17): 형식 이탈은 다시
     * 요청하면 되지만, 되풀이는 *지시를 더 주거나 회피 목록을 늘리라*는 뜻이다.
     */
    private fun buildNotices(
        outcome: CharacterNameAiSuggester.RoundOutcome,
        repeated: Int
    ): List<String> = buildList {
        // 토큰은 **보고받았을 때만** 적는다 — 실패하거나 제공사가 안 알려준 실행에
        // *"입력 0 · 출력 0"*을 적으면 재지 않은 것을 잰 것처럼 말하게 된다.
        if (outcome.inputTokens > 0 || outcome.outputTokens > 0) {
            add(app.getString(R.string.field_library_ai_token_usage, outcome.inputTokens, outcome.outputTokens))
        }
        if (outcome.droppedCount > 0) {
            add(app.getString(R.string.ai_name_dropped, outcome.droppedCount))
        }
        if (repeated > 0) add(app.getString(R.string.ai_name_repeated, repeated))
        outcome.truncationNotes.forEach {
            add(app.getString(R.string.ai_field_truncated_prefix, it))
        }
        addAll(outcome.failures)
    }

    /** (세계관 기존 캐릭터 이름, 은행 이름) — 상한·선별은 순수 계층이 한다. */
    private suspend fun loadSamples(): Pair<List<String>, List<String>> =
        withContext(Dispatchers.IO) {
            val universe = universeNames ?: run {
                val loaded = setup.universeId?.let { id ->
                    try {
                        app.characterRepository.getCharactersByUniverseList(id).map { it.name }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load universe names for AI name suggest", e)
                        emptyList()
                    }
                } ?: emptyList()
                universeNames = loaded
                loaded
            }
            // 은행 견본은 **미사용분**이다 — 이미 쓰인 이름은 창고의 취향이 아니라 결과다.
            val bank = try {
                app.nameBankRepository.getAvailableNameBankList().map { it.name }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load name bank samples", e)
                emptyList()
            }
            universe to bank
        }

    /**
     * 담기 계획의 재료를 다시 뜬다.
     *
     * 캐릭터 이름 전량을 보는 것은 은행이 **세계관 스코프가 없기 때문**이다(설계 8-3 — v1 밖).
     * 일괄 등록의 중복 사전 고지(`getExistingCharacterNames`)와 같은 조회이고 같은 규모다.
     */
    private suspend fun refreshDepositMaterials() {
        withContext(Dispatchers.IO) {
            try {
                bankNames = app.nameBankRepository.getAllNameBankList().map { it.name }
                if (characterNames == null) {
                    characterNames = app.characterRepository.getAllCharactersList().map { it.name }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load deposit materials", e)
            }
        }
        recomputeDepositPlan()
    }

    private fun recomputeDepositPlan() {
        val pool = _pool.value ?: return
        _depositPlan.value =
            CharacterNameAiSuggester.depositPlan(pool, bankNames, characterNames.orEmpty())
    }

    /**
     * 고른 이름을 이름은행에 담는다 (설계 7-4).
     *
     * **성별과 출처를 자동으로 채운다** — 실측에서 은행의 출처·메모가 전부 빈 칸인 것은 손으로
     * 채울 이유가 없어서다. 자동으로 채울 수 있는 칸은 앱이 채운다.
     *
     * 사용자가 확정한 쓰기라 [NonCancellable]이다 — 확정 직후 시트를 닫아도 완주한다.
     * **풀은 비우지 않는다**: 담기는 검토의 끝이 아니라 곁가지이고, 비우면 실패했을 때
     * 되돌아갈 자리가 사라진다(R-38 · B-163의 교훈).
     */
    fun deposit(names: List<String>) = viewModelScope.launch {
        if (_depositing.value == true) return@launch
        _depositing.value = true
        try {
            withContext(NonCancellable) { depositInternal(names) }
        } finally {
            _depositing.value = false
        }
    }

    private suspend fun depositInternal(names: List<String>) {
        if (names.isEmpty()) {
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.ai_name_deposit_none)))
            return
        }
        try {
            val origin = app.getString(R.string.ai_name_deposit_origin)
            var inserted = 0
            var skipped = 0
            val insertedNames = mutableListOf<String>()
            // **대조와 삽입을 한 트랜잭션에 둔다.** 둘이 갈려 있으면 다른 화면(또는 연타로
            // 겹친 둘째 회차)이 그 사이에 같은 이름을 넣어도 이쪽 대조는 그것을 못 보고,
            // 같은 이름이 두 벌 들어간다. 빗장(`_depositing`)은 같은 화면의 연타를 막고
            // 트랜잭션은 화면 밖에서 온 쓰기까지 막는다 — 둘이 막는 것이 다르다.
            app.database.withTransaction {
                // 쓰기 직전에 다시 대조한다 — 시트가 열려 있는 동안 다른 화면이 은행을 고쳤을 수 있다.
                val existing = app.nameBankRepository.getAllNameBankList()
                val seen = existing.mapTo(HashSet()) {
                    com.novelcharacter.app.util.NameBankMatch.normalize(it.name)
                }
                for (raw in names) {
                    val name = raw.trim()
                    if (name.isEmpty()) { skipped++; continue }
                    if (!seen.add(com.novelcharacter.app.util.NameBankMatch.normalize(name))) {
                        skipped++
                        continue
                    }
                    app.nameBankRepository.insertNameBankEntry(
                        NameBankEntry(name = name, gender = setup.gender.trim(), origin = origin)
                    )
                    inserted++
                    insertedNames.add(name)
                }
            }
            val summary = buildString {
                append(app.getString(R.string.ai_name_deposit_done, inserted))
                if (skipped > 0) append(app.getString(R.string.ai_name_deposit_skipped, skipped))
            }
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK, summary,
                insertedNames.joinToString(", ").ifEmpty { null }))
            refreshDepositMaterials()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deposit names into name bank", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.ai_name_deposit_failed), e.message))
        }
    }

    companion object {
        private const val TAG = "NameSuggestViewModel"
    }
}
