package com.novelcharacter.app.ui.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.ui.stats.StatsDataProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 어시스턴트 화면 상태. HomeFragment(탭 배지)와 AssistantFragment(카드 목록)가
 * 부모 프래그먼트 스코프로 공유한다 — 스냅샷을 한 번만 로드해 양쪽이 관찰한다.
 */
class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val statsProvider = StatsDataProvider(app)
    private val engine = AssistantEngine(app)
    private val prefs = AssistantPrefs(app)

    private val _insights = MutableLiveData<List<AssistantInsight>>(emptyList())
    val insights: LiveData<List<AssistantInsight>> = _insights

    /** 탭 배지용 — 현재 표시 중인(숨김 제외) 정합성 오류 카드 수. */
    private val _errorCount = MutableLiveData(0)
    val errorCount: LiveData<Int> = _errorCount

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val loadMutex = Mutex()
    private var loadJob: Job? = null

    /**
     * 복원 다이얼로그용 — **지금 실제로 숨겨져 있는** 카드들의 id→제목.
     * prefs 키를 스캔하지 않고 매 refresh마다 라이브 산출하므로, 재노출됐거나 더 이상
     * 해당하지 않는(dead) 항목이 목록에 남지 않는다(리뷰 Finding 2 대응).
     */
    @Volatile private var hiddenTitles: Map<String, String> = emptyMap()

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            val result = withContext(Dispatchers.Default) {
                loadMutex.withLock {
                    val snapshot = statsProvider.loadSnapshot()
                    val enabled = prefs.enabledCategories()
                    val all = engine.run(snapshot, statsProvider, enabled)
                    val hidden = all.filter { prefs.isDismissed(it) }.associate { it.id to it.title }
                    // 편향 카드 상한은 숨김 반영 '후'에 적용 — 숨긴 카드가 슬롯을 먹지 않고 다음 카드가 승격된다.
                    val visible = capBiasCards(all.filterNot { prefs.isDismissed(it) }, prefs.biasMaxCards())
                    // 배지는 숨김과 무관하게 **실제로 존재하는** 정합성 오류 수 — 카드를 숨겨도 오류가 사라진 척하지 않는다(P2-7).
                    val errorTotal = all.count { it.category.isError }
                    Triple(visible, hidden, errorTotal)
                }
            }
            hiddenTitles = result.second
            _insights.value = result.first
            _errorCount.value = result.third
            _loading.value = false
        }
    }

    /** 편향 카드 상한 — 심각도순(엔진이 정렬) 목록에서 BIAS 상위 [maxBias]장만 남기고 나머지 카테고리는 유지. */
    private fun capBiasCards(list: List<AssistantInsight>, maxBias: Int): List<AssistantInsight> {
        if (maxBias <= 0) return list.filterNot { it.category == InsightCategory.BIAS }
        var biasCount = 0
        return list.filter {
            if (it.category == InsightCategory.BIAS) {
                biasCount++
                biasCount <= maxBias
            } else true
        }
    }

    fun dismiss(insight: AssistantInsight) {
        prefs.dismiss(insight)
        // 방금 숨긴 카드를 즉시 복원 목록에 반영(다음 refresh 전에도 정확하도록).
        hiddenTitles = hiddenTitles + (insight.id to insight.title)
        val remaining = _insights.value.orEmpty().filterNot { it.id == insight.id }
        _insights.value = remaining
        // 배지(_errorCount)는 갱신하지 않는다 — 카드를 숨겨도 실제 정합성 오류는 그대로 존재하므로
        // 배지가 0으로 떨어져 "깨끗한 척"하면 안 된다(P2-7). 실제 수는 다음 refresh에서 재산출된다.
    }

    /** 방금 숨긴 카드를 되돌린다(실행취소). */
    fun undismiss(insight: AssistantInsight) = restore(insight.id)

    /** 숨긴 카드를 복원한다. 다시 조건을 만족하면 목록에 재등장. */
    fun restore(id: String) {
        prefs.undismiss(id)
        hiddenTitles = hiddenTitles - id
        refresh()
    }

    /** 현재 숨김 상태인 카드들의 id→제목(복원 다이얼로그 목록용). */
    fun dismissedTitles(): Map<String, String> = hiddenTitles

    fun isCategoryEnabled(category: InsightCategory) = prefs.isCategoryEnabled(category)

    fun setCategoryEnabled(category: InsightCategory, enabled: Boolean) {
        prefs.setCategoryEnabled(category, enabled)
        refresh()
    }

    // ── 편향 카드 규모 설정 ──
    fun biasMinPopulation() = prefs.biasMinPopulation()
    fun biasMaxCards() = prefs.biasMaxCards()
    fun setBiasSettings(minPopulation: Int, maxCards: Int) {
        prefs.setBiasMinPopulation(minPopulation)
        prefs.setBiasMaxCards(maxCards)
        refresh()
    }
}
