package com.novelcharacter.app.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.DialogTaskProgressBinding

/**
 * **작업형 로딩의 공용 진행도**(규약 R-26) — 총량을 아는 순회 작업 전용.
 *
 * 조회형 로딩(화면 데이터 읽기·통계 계산)은 대상이 아니다. 총량을 모르는 작업에 퍼센트를
 * 붙이면 0→100 깜빡임(노이즈) 아니면 90% 정지(거짓말)가 되기 때문이다 — 그쪽은
 * [com.novelcharacter.app.util.createProgressDialog](불확정 스피너)를 쓴다. 두 컴포넌트는
 * 이름이 비슷하지만 **역할이 다르다**: 여기는 "N/M을 안다", 저기는 "끝을 모른다".
 *
 * 규약:
 * - 총량 확정 후에 [show]한다. 총량을 모르면 이 다이얼로그를 쓰지 않는다.
 * - [Handle.update]는 메인 스레드에서 부른다(작업은 IO, 갱신은 메인).
 * - 취소를 넘기면 취소 버튼이 붙는다. **취소는 즉시 중단이 아니라 "더 시작하지 않음"**이며,
 *   항목 단위로 완결되는 작업만 취소를 제공한다(반쪽 항목이 남으면 안 된다).
 * - 완료·실패와 무관하게 [Handle.dismiss]를 finally에서 부른다.
 */
object TaskProgressDialog {

    /** 표시 중인 진행도 손잡이. 작업 쪽은 이것만 들고 다닌다. */
    class Handle internal constructor(
        private val dialog: AlertDialog,
        private val binding: DialogTaskProgressBinding,
        private val context: Context
    ) {
        /** 사용자가 취소를 눌렀는가 — 작업 루프가 매 항목마다 확인한다. */
        @Volatile
        var isCancelled: Boolean = false
            internal set

        /**
         * 진행도 갱신.
         *
         * @param current 처리 완료 항목 수, [total] 총량. total이 0이면 퍼센트를 그리지 않는다.
         * @param stage 현재 단계 문구(예: "이미지를 들여오는 중"). null이면 유지.
         */
        fun update(current: Int, total: Int, stage: String? = null) {
            if (stage != null) binding.stageText.text = stage
            val safeTotal = total.coerceAtLeast(0)
            val safeCurrent = current.coerceIn(0, if (safeTotal == 0) 0 else safeTotal)
            val percent = if (safeTotal == 0) 0 else (safeCurrent * 100) / safeTotal
            binding.progressBar.progress = percent
            binding.countText.text =
                context.getString(R.string.task_progress_count, safeCurrent, safeTotal, percent)
        }

        /** 뷰가 이미 사라진 뒤에도 안전하게 닫는다(윈도우 분리 예외 무해화). */
        fun dismiss() {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Exception) {
                // 액티비티·윈도우가 먼저 사라진 경우 — 무시해도 안전
            }
        }
    }

    /**
     * 진행도 다이얼로그를 띄운다.
     *
     * @param titleRes 작업 이름(예: "정리 폴더 받아오기").
     * @param onCancel 취소를 제공할 때만 넘긴다. 넘기면 취소 버튼이 붙고
     *        [Handle.isCancelled]가 true가 된다 — 작업 루프가 그것을 보고 멈춘다.
     */
    fun show(
        context: Context,
        @StringRes titleRes: Int,
        total: Int,
        @StringRes stageRes: Int? = null,
        onCancel: (() -> Unit)? = null
    ): Handle {
        val binding = DialogTaskProgressBinding.inflate(android.view.LayoutInflater.from(context))
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(binding.root)
            .setCancelable(false)
        val dialog = builder.create()
        val handle = Handle(dialog, binding, context)
        if (onCancel != null) {
            dialog.setButton(AlertDialog.BUTTON_NEGATIVE, context.getString(R.string.cancel)) { _, _ ->
                handle.isCancelled = true
                onCancel()
            }
        }
        dialog.show()
        // 취소는 "더 시작하지 않음"이라 누른 뒤에도 다이얼로그가 남아 마무리를 보여 준다.
        if (onCancel != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                handle.isCancelled = true
                onCancel()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
            }
        }
        handle.update(0, total, stageRes?.let { context.getString(it) })
        return handle
    }
}
