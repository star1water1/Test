package com.novelcharacter.app.util

import androidx.appcompat.app.AlertDialog

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
