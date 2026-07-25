package com.novelcharacter.app.util

import android.content.Context
import android.widget.ProgressBar
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 공용 불확정 진행 다이얼로그 — 백업/내보내기/초기화 등 오래 걸리는 작업에서
 * "진행 중"인지 "조용히 실패"했는지 사용자가 판별할 수 있게 한다(변수 제어).
 *
 * 호출측 규약:
 * - 작업 시작 직전에 show(), 완료/실패와 무관하게 finally에서 dismiss한다.
 * - 취소 불가(cancelable=false) — 중간 취소를 지원하는 작업은 자체 UI를 구성할 것.
 */
fun createProgressDialog(context: Context, @StringRes messageRes: Int): AlertDialog =
    MaterialAlertDialogBuilder(context)
        .setMessage(context.getString(messageRes))
        .setCancelable(false)
        .setView(ProgressBar(context).apply {
            isIndeterminate = true
            val pad = (24 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        })
        .create()

/** 뷰가 이미 파괴된 뒤에도 안전하게 닫기 — 윈도우 분리 예외를 무해화한다. */
fun AlertDialog.dismissSafely() {
    try {
        if (isShowing) dismiss()
    } catch (_: Exception) {
        // 액티비티/윈도우가 먼저 사라진 경우 — 무시해도 안전
    }
}
