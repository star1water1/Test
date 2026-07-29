package com.novelcharacter.app.util

import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R

/**
 * 양성(저장) 버튼을 수동 처리하여 [onValid]가 true를 반환할 때만 다이얼로그를 닫는다.
 * 검증 실패(false) 시 다이얼로그가 유지되어 사용자의 입력이 유실되지 않는다.
 * 반드시 setPositiveButton(text, null)로 리스너 없이 생성한 다이얼로그에 사용한다.
 */
fun AlertDialog.setValidatedPositiveButton(onValid: () -> Boolean) {
    setOnShowListener {
        getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (onValid()) {
                dismiss()
            }
        }
    }
}

/**
 * 검증 실패를 **입력 자리에** 붙인다 — 토스트는 화면을 떠나지만 인라인 오류는 고칠 곳에 남는다.
 * 사용자가 값을 고치기 시작하면 스스로 사라진다(검증 → 알림 → 교정의 마지막 걸음).
 *
 * B-28: 여기가 '검증 실패를 무엇으로 보여 주는가'의 단일 소스다. 화면마다 TextWatcher를
 * 다시 짜면 오류가 사라지는 시점이 갈린다 — S-12가 만든 것을 화면 하나에 두었더니
 * 나머지 여섯 다이얼로그가 토스트로 남았다.
 *
 * [TextInputLayout]이 있으면 그쪽에 붙이고(라벨 아래 붉은 글씨), 코드로 만든 맨 [EditText]는
 * 자기 자신에 붙인다(말풍선). 둘 다 지우는 시점은 같다.
 */
fun TextInputLayout.showInlineError(message: String) {
    error = message
    editText?.attachErrorClearing { error = null }
}

/** [showInlineError]의 맨 EditText판 — 동적 생성 폼(TextInputLayout이 없는 자리)용. */
fun EditText.showInlineError(message: String) {
    error = message
    attachErrorClearing { error = null }
}

/**
 * 값이 바뀌면 [clear]를 부르는 감시자를 **한 번만** 단다.
 * 두 번 달면 오류를 지우는 콜백이 중복 실행되고, 다이얼로그를 다시 열 때마다 쌓인다.
 */
private fun EditText.attachErrorClearing(clear: () -> Unit) {
    if (getTag(R.id.inline_error_watcher) != null) return
    setTag(R.id.inline_error_watcher, true)
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = clear()
        override fun afterTextChanged(s: android.text.Editable?) {}
    })
}
