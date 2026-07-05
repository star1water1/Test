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

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            val result = withContext(Dispatchers.Default) {
                loadMutex.withLock {
                    val snapshot = statsProvider.loadSnapshot()
                    val enabled = prefs.enabledCategories()
                    engine.run(snapshot, statsProvider, enabled)
                        .filterNot { prefs.isDismissed(it) }
                }
            }
            _insights.value = result
            _errorCount.value = result.count { it.category.isError }
            _loading.value = false
        }
    }

    fun dismiss(insight: AssistantInsight) {
        prefs.dismiss(insight)
        val remaining = _insights.value.orEmpty().filterNot { it.id == insight.id }
        _insights.value = remaining
        _errorCount.value = remaining.count { it.category.isError }
    }

    /** 방금 숨긴 카드를 되돌린다(실행취소). */
    fun undismiss(insight: AssistantInsight) = restore(insight.id)

    /** 숨긴 카드를 복원한다. 다시 조건을 만족하면 목록에 재등장. */
    fun restore(id: String) {
        prefs.undismiss(id)
        refresh()
    }

    /** 현재 숨김 상태인 카드들의 id→제목(복원 다이얼로그 목록용). */
    fun dismissedTitles(): Map<String, String> = prefs.dismissedTitles()

    fun isCategoryEnabled(category: InsightCategory) = prefs.isCategoryEnabled(category)

    fun setCategoryEnabled(category: InsightCategory, enabled: Boolean) {
        prefs.setCategoryEnabled(category, enabled)
        refresh()
    }
}
