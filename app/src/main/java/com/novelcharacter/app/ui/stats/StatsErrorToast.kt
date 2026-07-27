package com.novelcharacter.app.ui.stats

import android.widget.Toast
import androidx.fragment.app.Fragment
import com.novelcharacter.app.R

/**
 * 통계 화면들의 오류 고지 단일 소스 (B-32).
 *
 * 종전에는 화면 8곳이 각자 `Toast.makeText(ctx, R.string.stats_load_error, …)`를 띄웠다.
 * ViewModel이 애써 만든 **구체적 사유**("필드를 찾을 수 없습니다", "축이 섞였습니다")가
 * 화면에 닿기 전에 통짜 문구로 덮여 사라졌고, 반대로 사유가 비어 있으면 아무것도 뜨지 않았다.
 *
 * 규칙은 하나다 — **사유가 있으면 사유를, 없으면 통짜 문구를, 어느 쪽이든 반드시 띄운다.**
 */
internal fun Fragment.showStatsError(viewModel: StatsViewModel, error: String?) {
    if (error == null) return
    val ctx = context ?: return
    val message = error.takeIf { it.isNotBlank() } ?: getString(R.string.stats_load_error)
    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    // 보여준 오류는 비운다 — 안 그러면 회전·복귀 때 지나간 오류가 다시 뜬다(sticky LiveData).
    viewModel.consumeError()
}
