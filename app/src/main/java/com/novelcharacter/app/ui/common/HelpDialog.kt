package com.novelcharacter.app.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldDescription

/**
 * 도움말·설명 표시 다이얼로그의 공용 껍데기 (제목 + 본문).
 *
 * 진입은 둘로 나뉜다 — **컴포넌트는 공유하되 출처가 다르므로 아이콘을 나눈다**:
 * - `ⓘ` → [showFieldNote]: **필드 설명** — 사용자가 쓴 글(`FieldDefinition.config`의
 *   `description`). P-A(A-2)가 만든다.
 * - `?` → `showHelp(fragment, helpKey)`: **앱 도움말** — 개발자 작성, `strings.xml`.
 *   P3가 `helpKey` 규약과 본문을 얹는다(본문 승인 게이트는 그대로 — 여기서는 껍데기만 만든다).
 */
object HelpDialog {

    /** 필드 설명 표시 — 설명이 없으면 아무것도 띄우지 않는다(호출측이 아이콘 자체를 감춘다). */
    fun showFieldNote(context: Context, field: FieldDefinition) {
        val description = FieldDescription.fromConfig(field.config)
        if (description.isBlank()) return
        MaterialAlertDialogBuilder(context)
            .setTitle(field.name)
            .setMessage(description)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}
