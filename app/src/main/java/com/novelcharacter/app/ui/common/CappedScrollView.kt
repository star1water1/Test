package com.novelcharacter.app.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import com.novelcharacter.app.util.DialogScrollCap

/**
 * 레이아웃 XML의 루트로 쓰는 **높이 상한 스크롤**(규약 R-31의 XML 축, 백로그 B-98).
 *
 * **왜 클래스인가:** 코드에서 쓰는 `cappedScrollView(context)`는 익명 객체였는데,
 * **XML은 익명 객체를 세울 수 없다.** 그래서 `setView(binding.root)` 꼴로 XML을 본문으로
 * 쓰는 다이얼로그 열둘은 상한 없이 남아 있었다 — 하필 가장 긴 폼들이 거기 있다
 * (필드 편집·연표 편집·작품 편집). 증상은 코드 쪽과 같다: **긴 내용이 잘리고 끝까지
 * 스크롤되지 않는다.**
 *
 * **규약이 갈리지 않게 하는 법:** 측정 규칙은 [DialogScrollCap] 한 벌이고 이 클래스는
 * 껍데기다. `cappedScrollView(context)`도 이 클래스를 돌려주므로 **구현은 하나다.**
 */
class CappedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** 코드 경로가 다른 몫을 줄 수 있게 열어 둔다. XML은 기본값을 쓴다. */
    var capFraction: Float = DialogScrollCap.DEFAULT_FRACTION

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, cappedHeightSpec(context, heightMeasureSpec, capFraction))
    }
}

/**
 * [CappedScrollView]의 중첩 스크롤판 — 안에 `RecyclerView`가 있는 본문용(연표 편집).
 *
 * **왜 둘인가:** `ScrollView`와 `NestedScrollView`는 상속 계열이 달라 한 클래스가 둘 다
 * 될 수 없다. 대신 **규칙을 공유한다** — 둘 다 [cappedHeightSpec]만 부르므로 상한 규약은
 * 여전히 한 벌이다. `NestedScrollView`를 평범한 `ScrollView`로 바꾸면 안쪽 목록의
 * 중첩 스크롤이 깨지므로, 계열을 유지한 채 상한만 얹는다.
 */
class CappedNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    var capFraction: Float = DialogScrollCap.DEFAULT_FRACTION

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, cappedHeightSpec(context, heightMeasureSpec, capFraction))
    }
}

/**
 * 부모가 준 높이 스펙을 상한이 걸린 `AT_MOST` 스펙으로 바꾼다.
 *
 * `AT_MOST`로 측정하면 두 가지가 한 번에 된다 — **짧으면 내용만큼만** 차지해 작은 창이
 * 유지되고, 길면 상한에서 멈춘 뒤 **안에서 스크롤**된다.
 */
private fun cappedHeightSpec(context: Context, heightMeasureSpec: Int, fraction: Float): Int {
    val capPx = DialogScrollCap.capPx(context.resources.displayMetrics.heightPixels, fraction)
    val bounded = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
    val limit = DialogScrollCap.limitPx(capPx, bounded, MeasureSpec.getSize(heightMeasureSpec))
    return MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
}
