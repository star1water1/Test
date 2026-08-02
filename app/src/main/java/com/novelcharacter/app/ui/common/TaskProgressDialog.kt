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

    /**
     * 숫자 칸이 무엇을 세는가 — 구간마다 단위가 다르다.
     *
     * [ITEMS]는 장·건이고 [MEGABYTES]는 파일 바이트다. 단위를 표시하지 않으면 "350 / 750"이
     * 이미지 750장인지 750MB인지 알 수 없다. 바이트 구간의 눈금 환산은
     * [com.novelcharacter.app.util.ProgressScale]이 한다(Int 상한을 넘는 총량 때문).
     */
    enum class CountFormat { ITEMS, MEGABYTES }

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
         * @param current 처리 완료 항목 수, [total] 총량.
         * @param stage 현재 단계 문구(예: "이미지를 들여오는 중"). null이면 유지.
         * @param format 숫자 칸의 단위 — 장·건인가 MB인가.
         *
         * **[total]이 0이면 "총량을 모른다"는 뜻이다**(그 구간에 한해). 퍼센트를 지어내지 않고
         * 불확정 막대로 돌리며 숫자는 "지금까지 얼마"만 말한다 — R-26 후단(총량을 모르는 작업에
         * 퍼센트를 붙이지 않는다)을 한 창 안에서 구간별로 지키는 방법이다.
         */
        fun update(
            current: Int,
            total: Int,
            stage: String? = null,
            format: CountFormat = CountFormat.ITEMS
        ) {
            if (stage != null) binding.stageText.text = stage
            val safeTotal = total.coerceAtLeast(0)
            if (safeTotal == 0) {
                val done = current.coerceAtLeast(0)
                binding.progressBar.visibility = android.view.View.GONE
                binding.indeterminateBar.visibility = android.view.View.VISIBLE
                binding.countText.text = context.getString(
                    when (format) {
                        CountFormat.ITEMS -> R.string.task_progress_count_unknown
                        CountFormat.MEGABYTES -> R.string.task_progress_count_unknown_mb
                    },
                    done
                )
                return
            }
            binding.indeterminateBar.visibility = android.view.View.GONE
            binding.progressBar.visibility = android.view.View.VISIBLE
            val safeCurrent = current.coerceIn(0, safeTotal)
            // Long으로 셈한다 — 항목 수가 큰 작업(ZIP 이미지 엔트리 상한 5만)에서 `current * 100`이
            // Int를 넘길 여지를 아예 없앤다.
            val percent = ((safeCurrent.toLong() * 100L) / safeTotal.toLong()).toInt()
            binding.progressBar.progress = percent
            binding.countText.text = context.getString(
                when (format) {
                    CountFormat.ITEMS -> R.string.task_progress_count
                    CountFormat.MEGABYTES -> R.string.task_progress_count_mb
                },
                safeCurrent, safeTotal, percent
            )
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
        format: CountFormat = CountFormat.ITEMS,
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
        handle.update(0, total, stageRes?.let { context.getString(it) }, format)
        return handle
    }
}
