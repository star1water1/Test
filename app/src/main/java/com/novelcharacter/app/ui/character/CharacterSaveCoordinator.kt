package com.novelcharacter.app.ui.character

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.generateEntityCode
import kotlinx.coroutines.launch

/**
 * 캐릭터 저장 체인 공용 코디네이터 — CharacterEditFragment에서 추출.
 * 이름/필수 필드 검증 → (옵션) 중복 이름 검사 → 나이-출생연도 연동 충돌 감지 →
 * 교차 세계관 이동 고지 → DB 저장(태그·이미지 정리 포함)의 전체 체인을 담당한다.
 * 캐릭터 편집 화면과 보충탭 인라인 편집이 공유하며, 순차 보충 모드는
 * [checkDuplicates]=false로 기존 동작(중복 검사 생략)을 유지한다.
 *
 * 사용 계약:
 * - 호스트 프래그먼트의 onViewCreated에서 생성 후 [registerResultListeners]를 즉시 호출한다
 *   (중복 다이얼로그 결과 리스너 — 회전 안전, P1-A).
 * - 저장 버튼에서 [requestSave]를 호출한다. 진행 상태는 [Host.onSavingChanged]로 통지된다.
 * - 저장 성공 시 [Host.onSaved]가 호출된다(후처리: 드래프트 제거·화면 전환 등은 호스트 몫).
 */
class CharacterSaveCoordinator(
    private val fragment: Fragment,
    private val viewModel: CharacterViewModel,
    private val checkDuplicates: Boolean,
    private val host: Host
) {

    /** 저장 시점의 폼 입력 스냅샷 — 코디네이터는 호스트 뷰를 직접 알지 않는다 */
    data class FormSnapshot(
        val name: String,
        val firstName: String,
        val lastName: String,
        val anotherName: String,
        val tags: String,
        val memo: String,
        val novelId: Long?,
        val imagePaths: List<String>
    )

    interface Host {
        /** 현재 폼 입력의 스냅샷 (중복 다이얼로그 결과가 회전 후 도착해도 최신 폼 기준으로 재구성) */
        fun snapshot(): FormSnapshot
        fun collectFieldValues(characterId: Long): List<CharacterFieldValue>
        fun validateRequiredFields(): String?
        /** 편집 중인 캐릭터 id (-1L = 신규) */
        fun editingCharacterId(): Long
        fun existingCharacter(): Character?
        /** 저장 진행 상태 변화 — 저장 버튼 활성/비활성 등에 사용 */
        fun onSavingChanged(saving: Boolean)
        /** 저장 성공 후처리 — 드래프트 제거, 화면 전환/갱신 등 */
        fun onSaved(savedCharacterId: Long)
        /**
         * 시작된 저장 체인이 저장 없이 중단됨 — 중간 다이얼로그(중복 이름·나이 연동·교차 세계관)
         * 취소, 오류 등. 호스트가 저장 완료에 걸어둔 예약 동작(이탈 후속 처리 등)을 해제해야
         * 다음 저장 성공 시 스테일 동작이 실행되지 않는다.
         */
        fun onSaveAborted() {}
    }

    private val gson = Gson()

    var isSaving = false
        private set

    private fun resetSavingState() {
        isSaving = false
        host.onSavingChanged(false)
    }

    /** 저장 체인이 저장 없이 끝나는 모든 지점(취소·오류)에서 호출 — 성공 경로는 resetSavingState + onSaved */
    private fun abortSave() {
        resetSavingState()
        host.onSaveAborted()
    }

    /** 폼 스냅샷으로 Character를 조립한다. 중복 다이얼로그 결과가 회전 후 도착해도 최신 폼에서 재구성하기 위해 공용화. */
    private fun buildCharacterFromForm(): Character {
        val snapshot = host.snapshot()
        val characterId = host.editingCharacterId()
        val existingCharacter = host.existingCharacter()
        return Character(
            id = if (characterId != -1L) characterId else 0,
            name = snapshot.name.trim(),
            firstName = snapshot.firstName.trim(),
            lastName = snapshot.lastName.trim(),
            anotherName = snapshot.anotherName.trim(),
            novelId = snapshot.novelId,
            imagePaths = gson.toJson(snapshot.imagePaths),
            createdAt = existingCharacter?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            memo = snapshot.memo,
            code = existingCharacter?.code ?: generateEntityCode(),
            displayOrder = existingCharacter?.displayOrder ?: 0,
            isPinned = existingCharacter?.isPinned ?: false
        )
    }

    /**
     * 저장 요청 진입점. 검증 실패 시 토스트를 띄우고 false를 반환한다.
     * 검증 통과 시 비동기 저장 체인을 시작하고 true를 반환한다
     * (다이얼로그 분기 등으로 실제 저장은 취소될 수 있다 — 완료 통지는 [Host.onSaved]).
     */
    fun requestSave(): Boolean {
        if (isSaving) return false
        val ctx = fragment.context ?: return false
        val name = host.snapshot().name.trim()
        if (name.isEmpty()) {
            Toast.makeText(ctx, R.string.enter_name, Toast.LENGTH_SHORT).show()
            return false
        }
        if (name.length > 100) {
            Toast.makeText(ctx, R.string.name_too_long, Toast.LENGTH_SHORT).show()
            return false
        }

        val missingRequired = host.validateRequiredFields()
        if (missingRequired != null) {
            Toast.makeText(ctx, ctx.getString(R.string.required_field_empty, missingRequired), Toast.LENGTH_SHORT).show()
            return false
        }

        val character = buildCharacterFromForm()
        val characterId = host.editingCharacterId()

        isSaving = true
        host.onSavingChanged(true)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (checkDuplicates) {
                    // 중복 이름 체크
                    val duplicates = viewModel.getAllCharactersByName(name)
                        .filter { it.id != characterId } // 자기 자신 제외 (수정 시)

                    if (duplicates.isNotEmpty()) {
                        // 중복 후보 목록 구성 (작품명 포함)
                        val candidates = duplicates.map { dup ->
                            val novelTitle = dup.novelId?.let { viewModel.getNovelById(it)?.title }
                            DuplicateCandidate(dup, novelTitle)
                        }

                        val isEdit = characterId != -1L
                        if (!fragment.isAdded) { abortSave(); return@launch }
                        showDuplicateDialog(candidates, isEdit)
                    } else {
                        performSave(character, isUpdate = characterId != -1L, targetCharacterId = characterId)
                    }
                } else {
                    performSave(character, isUpdate = characterId != -1L, targetCharacterId = characterId)
                }
            } catch (e: Exception) {
                showSaveFailed()
                abortSave()
            }
        }
        return true
    }

    private fun showSaveFailed() {
        val ctx = fragment.context ?: return
        if (fragment.isAdded) {
            Toast.makeText(ctx, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDuplicateDialog(
        candidates: List<DuplicateCandidate>,
        isEditMode: Boolean
    ) {
        // 결과 리스너는 onViewCreated에서 1회 등록(회전 안전, P1-A). 여기선 다이얼로그만 띄운다.
        val dialog = DuplicateCharacterDialog.newInstance(candidates, isEditMode)
        dialog.show(fragment.childFragmentManager, "duplicate_character")
    }

    /**
     * 중복 캐릭터 다이얼로그 결과 리스너 — **호스트의 onViewCreated에서 1회 등록**(회전 안전, P1-A).
     * 지연 등록하면 회전으로 재생성 시 결과가 유실되고 캐릭터가 조용히 저장되지 않는다(변수 제어 위반).
     * 결과 도착 시 폼에서 character를 재구성해 처리한다.
     */
    fun registerResultListeners() {
        fragment.childFragmentManager.setFragmentResultListener(
            DuplicateCharacterDialog.RESULT_KEY,
            fragment.viewLifecycleOwner
        ) { _, bundle ->
            val resolutionName = bundle.getString(DuplicateCharacterDialog.RESULT_RESOLUTION)
                ?: DuplicateCharacterDialog.Resolution.CANCEL.name
            val resolution = DuplicateCharacterDialog.Resolution.valueOf(resolutionName)
            val selectedCharId = bundle.getLong(DuplicateCharacterDialog.RESULT_SELECTED_CHARACTER_ID, -1L)
            val character = buildCharacterFromForm()
            val characterId = host.editingCharacterId()

            when (resolution) {
                DuplicateCharacterDialog.Resolution.CANCEL -> {
                    abortSave()
                }
                DuplicateCharacterDialog.Resolution.CREATE_NEW -> {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            performSave(character, isUpdate = false, targetCharacterId = -1L)
                        } catch (e: Exception) {
                            showSaveFailed()
                            abortSave()
                        }
                    }
                }
                DuplicateCharacterDialog.Resolution.UPDATE_EXISTING -> {
                    if (selectedCharId == -1L) { abortSave(); return@setFragmentResultListener }
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val target = viewModel.getCharacterByIdSuspend(selectedCharId)
                            if (target == null) { abortSave(); return@launch }
                            val updatedChar = character.copy(
                                id = target.id,
                                code = target.code,
                                createdAt = target.createdAt,
                                displayOrder = target.displayOrder,
                                isPinned = target.isPinned
                            )
                            performSave(updatedChar, isUpdate = true, targetCharacterId = target.id)
                        } catch (e: Exception) {
                            showSaveFailed()
                            abortSave()
                        }
                    }
                }
                DuplicateCharacterDialog.Resolution.SAVE_ANYWAY -> {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            performSave(character, isUpdate = true, targetCharacterId = characterId)
                        } catch (e: Exception) {
                            showSaveFailed()
                            abortSave()
                        }
                    }
                }
            }
        }
    }

    private suspend fun performSave(character: Character, isUpdate: Boolean, targetCharacterId: Long) {
        // 저장 전 나이-출생연도 불일치 감지 (삽입 전에 체크하여 고아 레코드 방지)
        val tempCharId = if (isUpdate) targetCharacterId else -1L
        val tempFieldValues = host.collectFieldValues(tempCharId)
        val conflict = viewModel.detectAgeLinkageConflict(
            novelId = character.novelId,
            characterId = tempCharId,
            fieldValues = tempFieldValues
        )
        if (conflict != null && fragment.isAdded && fragment.view != null) {
            showAgeLinkageConflictDialog(conflict, character, isUpdate, targetCharacterId, tempFieldValues)
            return
        }

        executeSave(character, isUpdate, targetCharacterId, tempFieldValues)
    }

    /**
     * 실제 저장 실행 (불일치 감지 후 또는 해결 후 호출)
     * @param resolvedFieldValues 불일치 해결 후 수정된 필드값 목록 (null이면 다시 수집)
     */
    private suspend fun executeSave(
        character: Character,
        isUpdate: Boolean,
        targetCharacterId: Long,
        resolvedFieldValues: List<CharacterFieldValue>? = null,
        crossUniverseConfirmed: Boolean = false
    ) {
        val snapshot = host.snapshot()
        val savedCharId: Long
        if (isUpdate && targetCharacterId != -1L) {
            val fieldValues = resolvedFieldValues?.map { it.copy(characterId = targetCharacterId) }
                ?: host.collectFieldValues(targetCharacterId)
            // 다른 세계관으로 이동하는 저장이면 유실 고지·이관 처리(변수 제어: 조용한 필드값 유실 방지)
            val crossUniv = crossUniverseTargetId(character)
            if (crossUniv != null && !crossUniverseConfirmed) {
                val loss = viewModel.countCrossUniverseLoss(targetCharacterId, crossUniv)
                if (loss.hasRemoval) {
                    showCrossUniverseMoveDialog(loss,
                        onConfirm = {
                            fragment.viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    executeSave(character, isUpdate, targetCharacterId, fieldValues, crossUniverseConfirmed = true)
                                } catch (e: Exception) {
                                    showSaveFailed()
                                    abortSave()
                                }
                            }
                        },
                        onCancel = { abortSave() })
                    return
                }
            }
            applyCharacterUpdate(character, fieldValues, crossUniv)
            savedCharId = targetCharacterId
            cleanupRemovedImages(host.existingCharacter()?.imagePaths, snapshot.imagePaths)
        } else {
            val newId = viewModel.insertCharacterSuspend(character)
            val fieldValues = resolvedFieldValues?.map { it.copy(characterId = newId) }
                ?: host.collectFieldValues(newId)
            viewModel.saveAllFieldValues(newId, fieldValues)
            savedCharId = newId
        }

        val tagList = snapshot.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        viewModel.replaceAllTagsSuspend(savedCharId, tagList.map { CharacterTag(characterId = savedCharId, tag = it) })

        resetSavingState()
        if (fragment.isAdded && fragment.view != null) {
            Toast.makeText(fragment.requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
        }
        host.onSaved(savedCharId)
    }

    private fun showAgeLinkageConflictDialog(
        conflict: CharacterViewModel.AgeLinkageConflict,
        character: Character,
        isUpdate: Boolean,
        targetCharacterId: Long,
        originalFieldValues: List<CharacterFieldValue>
    ) {
        val ctx = fragment.context ?: run { abortSave(); return }
        val options = arrayOf(
            ctx.getString(R.string.age_linkage_option_adjust_birth,
                conflict.suggestedBirthYear, conflict.inputAge),
            ctx.getString(R.string.age_linkage_option_adjust_age,
                conflict.expectedAge, conflict.inputBirthYear),
            if (conflict.affectedCharacterCount > 0)
                ctx.getString(R.string.age_linkage_option_adjust_std_year_with_warn,
                    conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear,
                    conflict.affectedCharacterCount)
            else
                ctx.getString(R.string.age_linkage_option_adjust_std_year,
                    conflict.suggestedStdYear, conflict.inputAge, conflict.inputBirthYear)
        )

        var selected = 0
        AlertDialog.Builder(ctx)
            .setTitle(R.string.age_linkage_conflict_title)
            .setMessage(ctx.getString(R.string.age_linkage_conflict_message,
                conflict.currentStdYear, conflict.inputAge, conflict.inputBirthYear, conflict.expectedAge))
            .setSingleChoiceItems(options, 0) { _, which -> selected = which }
            .setPositiveButton(R.string.apply) { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val fieldValues = originalFieldValues.toMutableList()

                        when (selected) {
                            0 -> {
                                // 출생연도 변경 (나이 유지)
                                val idx = fieldValues.indexOfFirst { it.fieldDefinitionId == conflict.birthYearFieldId }
                                val newVal = CharacterFieldValue(
                                    characterId = 0, // executeSave에서 재설정됨
                                    fieldDefinitionId = conflict.birthYearFieldId,
                                    value = conflict.suggestedBirthYear.toString()
                                )
                                if (idx >= 0) fieldValues[idx] = newVal else fieldValues.add(newVal)
                            }
                            1 -> {
                                // 나이 변경 (출생연도 유지)
                                val idx = fieldValues.indexOfFirst { it.fieldDefinitionId == conflict.ageFieldId }
                                val newVal = CharacterFieldValue(
                                    characterId = 0,
                                    fieldDefinitionId = conflict.ageFieldId,
                                    value = conflict.expectedAge.toString()
                                )
                                if (idx >= 0) fieldValues[idx] = newVal else fieldValues.add(newVal)
                            }
                            2 -> {
                                // 기준연도 변경 (둘 다 유지) + 다른 캐릭터 일괄 재계산
                                viewModel.applyStandardYearChange(
                                    conflict.novelId,
                                    conflict.currentStdYear,
                                    conflict.suggestedStdYear
                                )
                            }
                        }

                        executeSave(character, isUpdate, targetCharacterId, fieldValues)
                    } catch (e: Exception) {
                        showSaveFailed()
                        abortSave()
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                abortSave()
            }
            .setOnCancelListener {
                abortSave()
            }
            .show()
    }

    /** 다른 세계관으로 이동하는 저장이면 새 세계관 id, 아니면 null(기존·새 세계관 모두 있고 서로 다를 때). */
    private suspend fun crossUniverseTargetId(character: Character): Long? {
        val old = viewModel.universeIdForNovel(host.existingCharacter()?.novelId)
        val new = viewModel.universeIdForNovel(character.novelId)
        return if (old != null && new != null && old != new) new else null
    }

    /** 세계관 이동 여부에 따라 이관 저장(같은 이름 필드 유지·유실 시 스냅샷) 또는 일반 저장을 선택한다. */
    private suspend fun applyCharacterUpdate(
        character: Character,
        values: List<CharacterFieldValue>,
        crossUniverseId: Long?
    ) {
        if (crossUniverseId != null) viewModel.updateCharacterAcrossUniverse(character, values, crossUniverseId)
        else viewModel.updateCharacterWithFields(character, values)
    }

    /** 세계관 이동 시 유실(제거) 고지 다이얼로그 — 같은 이름 필드 이관·제거분 휴지통 백업(복원 가능) 안내. */
    private fun showCrossUniverseMoveDialog(
        loss: com.novelcharacter.app.data.repository.UniverseMoveCounts,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (!fragment.isAdded || fragment.view == null) { onCancel(); return }
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.cross_universe_move_title)
            .setMessage(fragment.getString(
                R.string.cross_universe_move_message,
                loss.removedValues, loss.removedMemberships, loss.remappedValues
            ))
            .setPositiveButton(R.string.cross_universe_move_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /**
     * 이전 이미지 목록과 현재 목록을 비교하여 제거된 파일을 정리한다.
     * 정책(이미지 설정): LIBRARY_ONLY(기본) = 공유·라이브러리·휴지통 보호 파일은 남기고 나머지만 삭제,
     * ALWAYS_ADOPT = 삭제 대신 라이브러리(미배정)로 입양(삭제는 이미지 탭에서만).
     */
    private suspend fun cleanupRemovedImages(oldPathsJson: String?, currentPaths: List<String>) {
        val oldPaths: List<String> = try {
            gson.fromJson(oldPathsJson ?: "[]", com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val currentSet = currentPaths.toSet()
        val removed = oldPaths.filter { it !in currentSet }
        if (removed.isEmpty()) return
        val appCtx = fragment.context?.applicationContext ?: return
        val db = (fragment.activity?.application as? com.novelcharacter.app.NovelCharacterApp)?.database ?: return
        try {
            when (com.novelcharacter.app.util.ImageSettingsStore(appCtx).getEditorRemovePolicy()) {
                com.novelcharacter.app.util.ImageSettingsStore.EditorRemovePolicy.LIBRARY_ONLY ->
                    com.novelcharacter.app.util.ImageOwnershipGuard.deleteIfUnprotected(db, appCtx, removed)
                com.novelcharacter.app.util.ImageSettingsStore.EditorRemovePolicy.ALWAYS_ADOPT ->
                    com.novelcharacter.app.util.ImageOwnershipGuard.adoptOrphans(db, appCtx, removed)
            }
        } catch (_: Exception) { /* 보존이 삭제보다 안전 — 실패 시 파일 유지 */ }
    }
}
