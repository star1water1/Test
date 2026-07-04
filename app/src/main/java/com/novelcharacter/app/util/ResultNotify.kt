package com.novelcharacter.app.util

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R

/**
 * 데이터 처리 결과를 사용자에게 즉시 알리는 Fragment 확장함수 모음.
 *
 * 생명주기 안전 관례를 한 곳에 캡슐화한다:
 * - view/부착 상태를 확인해 Snackbar "No suitable parent found" 크래시(v35 전 사례) 회피
 * - Snackbar 부착 실패 시 Toast로 폴백 (ExcelImporter의 WeakRef+폴백 관례 답습)
 * - detail이 있으면 '상세' 액션으로 전체 내역 다이얼로그 제공
 */

/** OpResult를 성공/실패 스타일로 알린다. detail이 있으면 '상세' 액션 노출. */
fun Fragment.notifyResult(result: OpResult) {
    val duration = if (result.success && result.detail == null) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
    showResultSnackbar(result.summary, duration, result.detail)
}

/**
 * ViewModel을 거치지 않는 Fragment 직접 조작(IO·공유·휴지통 등)에서
 * 결과를 즉시 알리고 작업 이력에도 기록한다 — reportResult(ViewModel용)의 Fragment 대응.
 */
fun Fragment.reportAndNotify(result: OpResult) {
    (context?.applicationContext as? NovelCharacterApp)?.operationLogRepository?.logAsync(result)
    notifyResult(result)
}

/**
 * 작업 이력에만 기록한다(즉시 알림 없음). 이미 자체 Toast/다이얼로그로 알리는 Fragment 조작이
 * 이력 완성도를 위해 기록만 추가할 때 사용.
 */
fun Fragment.logOperation(result: OpResult) {
    (context?.applicationContext as? NovelCharacterApp)?.operationLogRepository?.logAsync(result)
}

fun Fragment.notifySuccess(message: String) =
    showResultSnackbar(message, Snackbar.LENGTH_SHORT, null)

fun Fragment.notifyError(message: String) =
    showResultSnackbar(message, Snackbar.LENGTH_LONG, null)

private fun Fragment.showResultSnackbar(message: String, duration: Int, detail: String?) {
    if (!isAdded) return
    val root = view
    if (root == null || !root.isAttachedToWindow) {
        // 뷰가 아직/이미 없으면 Toast 폴백
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        return
    }
    val snackbar = Snackbar.make(root, message, duration)
    if (!detail.isNullOrBlank()) {
        snackbar.setAction(R.string.result_detail_action) {
            val ctx = context ?: return@setAction
            AlertDialog.Builder(ctx)
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
    snackbar.show()
}
