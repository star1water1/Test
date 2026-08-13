package com.novelcharacter.app.ui.stats

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.novelcharacter.app.util.DisplayCap

/**
 * 상한이 붙은 세로 명단을 그리는 자리 — **한 번의 조작이 만드는 뷰 수를 묶음으로 못박는다**
 * (B-199 · 규약 R-14 · R-19).
 *
 * 통계 상세 화면들의 명단은 `LinearLayout`에 `TextView`를 하나씩 붙여 만든다. 그 자리들은
 * `NestedScrollView` 안이라 **재활용이 없고**, 목록은 전부 캐릭터 축이라 목표 규모 ×30에서
 * 한 목록이 6,420개의 뷰가 된다 — 만드는 것도 재는 것도 메인 스레드에서 한 번에 돈다.
 *
 * **그런데 그냥 자르면 R-14 위반이다** — 상한은 감추는 장치가 아니라 접는 장치이므로,
 * 잘라낸 것은 개수로 알리고(R-14) 눌러서 볼 수 있어야 한다(R-19). 그래서 여기는
 * *"더 보기 → 나머지 전부"*가 아니라 **묶음 단위로 는다**: 어떤 탭도 한 묶음보다 많이 만들지
 * 않고, 계속 누르면 끝까지 간다. 성능과 R-19가 함께 서는 자리가 이 한 가지다.
 *
 * **왜 공용인가** — 같은 `populateSimpleList`가 두 화면에 **글자 그대로 복제**돼 있었고,
 * 상한을 자리마다 손으로 넣으면 다음에 생기는 명단이 조용히 빠진다. 목록을 만드는 방법은
 * 호출부가 [makeRow]로 주고, **몇 개를 언제 만드는가만** 이 파일이 정한다.
 */
object StatsCappedList {

    /**
     * [items]를 [container]에 그린다.
     *
     * @param chunk 첫 표시분이자 '더 보기' 한 번의 증가분
     * @param emptyView 비었을 때 세울 뷰 — 빈 자리를 조용히 두지 않는다(R-17)
     * @param toggleView 접기/펼치기 줄의 모양 — 화면마다 글꼴·색이 다르므로 호출부가 짓는다
     * @param moreText 남은 수를 받아 '더 보기' 문구를 짓는다. **숫자는 문구에 박지 않는다**(R-14)
     * @param lessText 전부 펼쳐졌을 때의 '접기' 문구
     * @param makeRow 항목 하나의 뷰
     */
    fun <T> populate(
        container: LinearLayout,
        items: List<T>,
        chunk: Int = DisplayCap.NAME_LIST_CHUNK,
        emptyView: () -> View,
        toggleView: (CharSequence) -> TextView,
        moreText: (remaining: Int) -> CharSequence,
        lessText: () -> CharSequence,
        makeRow: (T) -> View
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(emptyView())
            return
        }

        val first = DisplayCap.shownCount(items.size, chunk, 0)
        for (i in 0 until first) container.addView(makeRow(items[i]))
        if (first >= items.size) return

        var steps = 0
        var shown = first
        val toggle = toggleView(moreText(items.size - shown))
        toggle.setOnClickListener {
            if (shown >= items.size) {
                // 접기 — 첫 묶음만 남기고 걷는다. 토글은 목록 **뒤**에 있으므로
                // 뒤에서부터 지워야 인덱스가 밀리지 않는다.
                for (i in shown - 1 downTo first) container.removeViewAt(i)
                shown = first
                steps = 0
                toggle.text = moreText(items.size - shown)
            } else {
                // 펼치기 — 다음 한 묶음만 만든다. 여기가 이 파일의 요점이다.
                steps += 1
                val next = DisplayCap.shownCount(items.size, chunk, steps)
                val insertAt = container.indexOfChild(toggle)
                for ((offset, i) in (shown until next).withIndex()) {
                    container.addView(makeRow(items[i]), insertAt + offset)
                }
                shown = next
                toggle.text =
                    if (shown >= items.size) lessText() else moreText(items.size - shown)
            }
        }
        container.addView(toggle)
    }
}
