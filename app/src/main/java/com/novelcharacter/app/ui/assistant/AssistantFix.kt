package com.novelcharacter.app.ui.assistant

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.novelcharacter.app.R
import com.novelcharacter.app.ui.character.CharacterViewModel
import com.novelcharacter.app.util.notifyError
import com.novelcharacter.app.util.notifySuccess
import kotlinx.coroutines.launch

/**
 * 나이 불일치 / 작품 미배정 교정 흐름 — **카드 기본버튼(N==1)과 '전체 보기' 시트 행이 동일하게 재사용**한다.
 * 모두 그 화면 안에서 완결하고, [onDone]은 성공(또는 이미 해결)에 호출된다(카드→refresh, 시트→행 제거).
 *
 * `lifecycleScope`(프래그먼트 수준)를 쓰므로 뷰가 잠깐 재생성돼도 DB 쓰기가 끊기지 않는다.
 */
fun Fragment.runAgeFix(cvm: CharacterViewModel, characterId: Long, onDone: () -> Unit) {
    lifecycleScope.launch {
        val conflict = cvm.detectAgeLinkageConflictForCharacter(characterId)
        if (!isAdded) return@launch
        if (conflict == null) {
            // 스냅샷이 낡아 이미 해결된 경우 — 억지로 고치지 않고 알린 뒤 정리.
            notifySuccess(getString(R.string.assistant_age_resolved))
            onDone()
            return@launch
        }
        val name = cvm.getCharacterByIdSuspend(characterId)?.name
        if (!isAdded) return@launch
        showAgeResolutionDialog(name, conflict) { choice ->
            lifecycleScope.launch {
                cvm.resolveAgeLinkage(characterId, conflict, choice)
                if (!isAdded) return@launch
                notifySuccess(getString(R.string.assistant_age_fixed))
                onDone()
            }
        }
    }
}

private fun Fragment.showAgeResolutionDialog(
    characterName: String?,
    conflict: CharacterViewModel.AgeLinkageConflict,
    onApply: (CharacterViewModel.AgeResolution) -> Unit
) {
    val options = arrayOf<CharSequence>(
        getString(R.string.age_linkage_option_adjust_birth, conflict.suggestedBirthYear, conflict.inputAge),
        getString(R.string.age_linkage_option_adjust_age, conflict.expectedAge, conflict.inputBirthYear),
        if (conflict.affectedCharacterCount > 0) {
            getString(
                R.string.age_linkage_option_adjust_std_year_with_warn,
                conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear, conflict.affectedCharacterCount
            )
        } else {
            getString(
                R.string.age_linkage_option_adjust_std_year,
                conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear
            )
        }
    )
    // 제목에 캐릭터 이름 + 기준연도 → "어느 캐릭터를 왜 고치는지"가 분명(피드백 1).
    val title = if (characterName != null) {
        getString(R.string.assistant_age_dialog_title_named, characterName, conflict.currentStdYear)
    } else {
        getString(R.string.assistant_age_dialog_title, conflict.currentStdYear)
    }
    var selected = 0
    AlertDialog.Builder(requireContext())
        .setTitle(title)
        .setSingleChoiceItems(options, 0) { _, which -> selected = which }
        .setPositiveButton(R.string.apply) { _, _ ->
            onApply(
                when (selected) {
                    0 -> CharacterViewModel.AgeResolution.BIRTH_YEAR
                    1 -> CharacterViewModel.AgeResolution.AGE
                    else -> CharacterViewModel.AgeResolution.STANDARD_YEAR
                }
            )
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** 작품 미배정 캐릭터를 작품에 배정. 캐릭터가 이미 없으면 실패 통보(변수 제어). [onDone]은 처리 후 호출. */
fun Fragment.runAssignNovel(cvm: CharacterViewModel, characterId: Long, characterName: String, onDone: () -> Unit) {
    lifecycleScope.launch {
        val novels = cvm.getAllNovelsList()
        if (!isAdded) return@launch
        if (novels.isEmpty()) {
            notifyError(getString(R.string.assistant_assign_no_novels))
            return@launch
        }
        val titles = novels.map { it.title }.toTypedArray<CharSequence>()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.assistant_assign_novel_title, characterName))
            .setItems(titles) { _, which ->
                val novel = novels[which]
                lifecycleScope.launch {
                    val ok = cvm.assignNovel(characterId, novel.id)
                    if (!isAdded) return@launch
                    if (ok) {
                        notifySuccess(getString(R.string.assistant_novel_assigned, characterName, novel.title))
                    } else {
                        notifyError(getString(R.string.assistant_character_gone))
                    }
                    onDone()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
