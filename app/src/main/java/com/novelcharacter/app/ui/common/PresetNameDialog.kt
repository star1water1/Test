package com.novelcharacter.app.ui.common

import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.util.PresetNameConflict
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import kotlinx.coroutines.launch

/** 겹침 판정에 필요한 최소 정보 — 화면 둘의 엔티티가 달라 여기서는 이것만 오간다. */
data class PresetRef(val id: Long, val isDefault: Boolean)

/**
 * 프리셋 이름을 받는 창 — **저장·개명, 목록 프리셋·검색 프리셋 네 자리가 같은 한 벌을 쓴다**
 * (B-191, 확정 15장 1번 착수 조건 ⓑ).
 *
 * ## 무엇을 막는가
 * 두 프리셋 표는 `name`이 유니크 색인인데 저장이 REPLACE였다 — 같은 이름을 적으면 **예외도
 * 경고도 없이** 옛 프리셋이 지워지고 id까지 바뀌었다(가리키던 참조가 함께 끊긴다). 프리셋은
 * 휴지통을 타지 않아 되돌릴 길도 없었다. 확정은 **겹치면 묻는다**이고, 덮어쓰기를 골라도
 * **id는 보존한다**(지우고-다시-넣기 금지) — 그래서 [onConfirm]이 넘겨받는 것이 *덮어쓸 id*다.
 *
 * ## R-27의 비동기 갈래를 그대로 따른다
 * 겹침 판정은 DB를 한 번 물어야 하므로 **판정을 받고 나서 닫는다.** 먼저 닫으면 고지가 갈
 * 곳이 없고, 창이 살아 있는 동안 연타가 가능해지므로 판정하는 동안 저장 버튼을 잠갔다가
 * `finally`로 되돌린다(예외로 끝나면 창이 영영 반응하지 않는다). 저장할 이름은 **누른 순간에**
 * 짓는다 — 중단 뒤에 칸을 다시 읽으면 그사이 바뀐 값을 읽는다.
 *
 * @param selfId 개명 중인 프리셋의 id(새로 저장하는 중이면 null). 자기 이름은 충돌이 아니다.
 * @param allowOverwrite 저장이면 true(덮어쓰기를 제안한다), 개명이면 false. 개명의 덮어쓰기는
 *   *다른 프리셋을 없애는 것*이라 이름을 바꾸는 일의 결과로는 과하다 — 그 자리에서 고치게 한다.
 * @param lookup 이 이름을 이미 쓰고 있는 프리셋(없으면 null). 이름은 유니크 색인이라 한 번의
 *   색인 조회다.
 * @param suggest '다른 이름으로 저장'을 고를 때 칸에 채워 줄 이름.
 * @param onConfirm 확정된 이름과 **덮어쓸 id**(새로 저장이면 null)를 받는다.
 */
fun Fragment.showPresetNameDialog(
    titleRes: Int,
    hintRes: Int,
    initialName: String = "",
    selfId: Long? = null,
    allowOverwrite: Boolean,
    lookup: suspend (String) -> PresetRef?,
    suggest: suspend (String) -> String,
    onConfirm: (name: String, overwriteId: Long?) -> Unit
) {
    val ctx = context ?: return
    val input = EditText(ctx).apply {
        hint = getString(hintRes)
        setText(initialName)
        setSelection(text.length)
    }
    val pad = (20 * resources.displayMetrics.density).toInt()
    val container = FrameLayout(ctx).apply {
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }
    val dialog = MaterialAlertDialogBuilder(ctx)
        .setTitle(titleRes)
        .setView(container)
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
        .create()

    // R-27: 검증 실패는 창을 닫지 않는다. 여기서는 판정이 비동기라 **언제나 false를 돌려주고**
    // 닫는 시점을 아래 코루틴이 정한다 — 겹치지 않았을 때만 닫는다.
    dialog.setValidatedPositiveButton {
        val name = PresetNameConflict.normalize(input.text.toString())
        if (name.isEmpty()) {
            input.showInlineError(getString(R.string.preset_name_required))
            return@setValidatedPositiveButton false
        }
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 조회가 실패하면 **저장하지 않고 말한다** — 여기서 예외가 밖으로 나가면
                // 이 판이 고치러 온 그 모양(고지 없이 앱이 죽는 것)을 새로 만든다.
                val existing = try {
                    lookup(name)
                } catch (e: Exception) {
                    if (isAdded) input.showInlineError(getString(R.string.result_preset_save_failed))
                    return@launch
                }
                if (!isAdded) return@launch
                when (PresetNameConflict.verdict(existing?.id, existing?.isDefault == true, selfId)) {
                    PresetNameConflict.Verdict.FREE,
                    PresetNameConflict.Verdict.SELF -> {
                        onConfirm(name, null)
                        dialog.dismiss()
                    }
                    PresetNameConflict.Verdict.TAKEN_BY_DEFAULT ->
                        input.showInlineError(getString(R.string.preset_name_taken_by_default))
                    PresetNameConflict.Verdict.TAKEN ->
                        if (!allowOverwrite) {
                            input.showInlineError(getString(R.string.preset_name_taken))
                        } else {
                            askOverwrite(
                                name = name,
                                onOverwrite = {
                                    onConfirm(name, existing?.id)
                                    dialog.dismiss()
                                },
                                onRename = {
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        // 제안은 편의라 실패해도 창을 막지 않는다 — 사용자가 직접 적으면 된다.
                                        val alt = try { suggest(name) } catch (e: Exception) { null }
                                        if (!isAdded || alt == null) return@launch
                                        input.setText(alt)
                                        input.setSelection(alt.length)
                                        input.requestFocus()
                                    }
                                }
                            )
                        }
                }
            } finally {
                // 예외로 끝나도 잠금은 풀린다 — 안 풀면 창이 영영 반응하지 않는다(R-27).
                if (isAdded) button.isEnabled = true
            }
        }
        false
    }
    dialog.show()
}

/**
 * 겹쳤다는 사실과 두 갈래를 함께 준다 — 알림과 바로잡을 경로를 한 창에서(개발 의도 2번).
 * **저장 창은 열어 둔 채 그 위에 띄운다** — 취소하고 돌아가면 적어 둔 이름이 그대로 있다.
 */
private fun Fragment.askOverwrite(name: String, onOverwrite: () -> Unit, onRename: () -> Unit) {
    val ctx = context ?: return
    MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.preset_name_conflict_title)
        .setMessage(getString(R.string.preset_name_conflict_message, name))
        .setPositiveButton(R.string.preset_name_conflict_overwrite) { _, _ -> onOverwrite() }
        .setNegativeButton(R.string.preset_name_conflict_rename) { _, _ -> onRename() }
        .setNeutralButton(R.string.cancel, null)
        .show()
}
