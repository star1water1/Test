package com.novelcharacter.app.ui.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

/**
 * 프로그래매틱 UI용 공용 헬퍼 — 인라인 density 계산·GradientDrawable 조립의
 * 40파일 중복을 줄인다. 신규 코드와 이미 손대는 파일에서 사용한다.
 */

/** dp → px (Int) */
fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/** dp → px (Float) */
fun Context.dpF(value: Float): Float =
    value * resources.displayMetrics.density

/** 테마 attr 색 해석 (예: ?attr/colorPrimary) */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/** 필(알약) 배경 — 배지·칩류 프로그래매틱 생성용 */
fun Context.pill(
    @ColorInt fill: Int,
    radiusDp: Int = 999,
    @ColorInt stroke: Int? = null,
    strokeWidthDp: Int = 1
): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dpF(radiusDp.toFloat())
    if (stroke != null) setStroke(dp(strokeWidthDp), stroke)
}
