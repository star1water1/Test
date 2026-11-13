package com.novelcharacter.app.ui.common

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import com.novelcharacter.app.R
import com.novelcharacter.app.util.ListNameFilter

/**
 * 툴바의 '이 목록에서 찾기'(B-49) — 세계관·작품·세력 목록이 함께 쓴다.
 *
 * 검색어는 화면이 아니라 이 객체가 들고, 회전·뒤로 돌아오기에서 [attach]가 검색 칸을 다시 편다.
 * 검색 칸을 접으면 검색어를 비운다 — 접힌 채 걸러진 목록은 왜 줄었는지 화면에 근거가 없다.
 */
class ListSearchAction(private val stateKey: String) {
    var query: String = ""
        private set

    val isActive: Boolean get() = ListNameFilter.isActive(query)

    fun restore(savedInstanceState: Bundle?) {
        savedInstanceState?.getString(stateKey)?.let { query = it }
    }

    fun save(outState: Bundle) {
        outState.putString(stateKey, query)
    }

    /**
     * 메뉴가 이미 붙은 [toolbar]에 연결한다. [onChanged]는 검색어가 바뀔 때마다 불린다.
     *
     * **뒤로 가기는 검색 칸을 접는다.** 액션바가 아닌 독립 툴바는 펼친 액션 뷰를 뒤로 가기로 접지 않아,
     * 그대로 두면 검색 칸이 열린 동안 뒤로 가기가 아무 일도 하지 않는다(에뮬레이터 실측).
     */
    fun attach(owner: Fragment, toolbar: Toolbar, onChanged: () -> Unit) {
        val item = toolbar.menu.findItem(R.id.action_list_search) ?: return
        val view = item.actionView as? SearchView ?: return
        toolbar.setBackInvokedCallbackEnabled(true)
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { item.collapseActionView() }
        }
        owner.requireActivity().onBackPressedDispatcher.addCallback(owner.viewLifecycleOwner, back)
        view.queryHint = toolbar.context.getString(R.string.list_search_hint)
        view.maxWidth = Int.MAX_VALUE
        // 펴는 순간 SearchView가 빈 검색어를 한 번 알린다 — 복원 중에는 그것이 보관한 검색어를 지운다.
        var restoring = query.isNotEmpty()
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean { view.clearFocus(); return true }
            override fun onQueryTextChange(text: String?): Boolean {
                if (restoring) return true
                val next = text.orEmpty()
                if (next != query) { query = next; onChanged() }
                return true
            }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean { back.isEnabled = true; return true }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                back.isEnabled = false
                if (query.isNotEmpty()) { query = ""; onChanged() }
                return true
            }
        })
        if (restoring) {
            val kept = query
            item.expandActionView()
            view.setQuery(kept, false)
            view.clearFocus()
            restoring = false
        }
    }
}
