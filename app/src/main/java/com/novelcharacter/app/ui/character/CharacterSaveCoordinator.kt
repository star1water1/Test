package com.novelcharacter.app.ui.character

import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        val imagePaths: List<String>,
        /**
         * 폼이 들고 있는 대표 이미지 지정(B-103). 빈 문자열 = 지정 없음.
         *
         * **반드시 폼에서 온다** — 기존 엔티티에서 바로 읽으면 편집 중에 ☆로 바꾼 것이
         * 저장에서 사라진다. [buildCharacterFromForm]이 새 목록에 남아 있는지 한 번 더 거른다.
         */
        val representativeImagePath: String = ""
    )

    interface Host {
        /** 현재 폼 입력의 스냅샷 (중복 다이얼로그 결과가 회전 후 도착해도 최신 폼 기준으로 재구성) */
        fun snapshot(): FormSnapshot
        fun collectFieldValues(characterId: Long): List<CharacterFieldValue>

        /**
         * 폼이 실제로 렌더한 필드 정의 id 집합 — 저장 시 **폼의 권한 범위**가 된다 (N2).
         * 이 집합 밖의 기존 값은 폼이 판단할 근거가 없으므로 저장이 건드리지 않는다.
         * ([collectFieldValues]가 빈 목록을 돌려주는 것과 "값이 없다"는 서로 다른 상태다)
         */
        fun coveredFieldDefinitionIds(): Set<Long>
        /** 저장을 **막는** 빈 필수 필드 이름(없으면 null) — 강도가 `BLOCK`인 것만이다 (B-90). */
        fun validateRequiredFields(): String?
        /** 저장은 되지만 알려야 할 빈 필수 칸 수 (B-90). */
        fun emptyRequiredNoticeCount(): Int
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

        /**
         * 편집창에서 **"앱에서 삭제"를 고른** 이미지 경로들(B-107 D7).
         *
         * 고른 즉시가 아니라 저장할 때 실행하는 것이 이 화면의 계약이다 — 취소하면 무해해야
         * 한다. 기본 구현이 빈 목록이라, 이 조작이 없는 호스트는 아무것도 하지 않는다.
         */
        fun pendingImageDeletes(): List<String> = emptyList()

        /** 대기 삭제를 실제로 실행한 뒤 호출된다 — 호스트가 목록을 비운다(중복 실행 방지). */
        fun onPendingImageDeletesApplied(deleted: Int, protectedCount: Int) {}

        /**
         * 편집 폼의 **[은행] 시트에서 고른** 이름은행 엔트리 id (B-124 ⓐ). 없으면 null.
         *
         * 고른 즉시가 아니라 저장할 때 거는 것이 이 화면의 계약이다 — 취소하면 무해해야 한다
         * (`pendingImageDeletes`와 같은 계약). 기본 구현이 null이라, 이 조작이 없는 호스트는
         * 자동 대조(ⓑ)만 받는다.
         */
        fun requestedNameBankEntryId(): Long? = null

        /** 은행 링크를 실제로 맞춘 뒤 호출된다 — 호스트가 고른 값을 비운다(다음 저장에 재사용 방지). */
        fun onNameBankLinkApplied() {}
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
            // 이 함수는 기존 엔티티를 copy()하지 않고 폼 스냅샷으로 새로 조립한다 —
            // 여기 적지 않으면 **저장할 때마다 대표가 조용히 지워진다**(`partSlots`가 이 병으로
            // 지워지던 전례: 세션 로그 1-ar). D5의 "목록이 통째로 바뀐다" 갈래이므로
            // 새 목록에 남아 있을 때만 유지한다.
            representativeImagePath = com.novelcharacter.app.util.CharacterRepresentativeImage
                .retain(snapshot.representativeImagePath, snapshot.imagePaths),
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

        // 막지 않는 필수 칸은 **저장을 멈추지 않고 알리기만 한다** (B-90). 러프하게 적어 두고
        // 나중에 채우는 경로를 막지 않는 것이 원칙 04이고, 그래도 비어 있다는 사실은 말한다
        // (개발 의도 2번 — 일일이 열어 보지 않으면 모르는 데이터를 남기지 않는다).
        val emptyRequiredCount = host.emptyRequiredNoticeCount()
        if (emptyRequiredCount > 0) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.required_fields_empty_notice, emptyRequiredCount),
                Toast.LENGTH_SHORT
            ).show()
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

        // restricted 입력 모드 검증 — 코디네이터 레벨이라 캐릭터 편집·보충 화면이 함께 커버된다 (검토 A8).
        // 차단 대신 사유 + 교정 경로 제공 (변수 제어): 추가하고 저장 / 입력 수정.
        val restrictedViolations = viewModel.findRestrictedViolations(tempFieldValues)
        if (restrictedViolations.isNotEmpty() && fragment.isAdded && fragment.view != null) {
            showRestrictedViolationDialog(restrictedViolations, character, isUpdate, targetCharacterId)
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
            val scopeMove = scopeMoveOf(character, targetCharacterId)
            val crossUniv = scopeMove.crossUniverseId
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
            applyCharacterUpdate(character, fieldValues, crossUniv, scopeMove.leavingUniverse)
            savedCharId = targetCharacterId
            // 자동 링크 재동기화는 제거 파일 정리보다 먼저 — 빠진 이미지의 자동 링크가 풀리고
            // 자동 입양 행이 반납된 뒤에 제거 정책이 봐야 "라이브러리 보존" 판정이 종전과 같다.
            resyncAutoLink()
            val previousPaths = host.existingCharacter()?.imagePaths
            cleanupRemovedImages(previousPaths, snapshot.imagePaths)
            syncDetachedMarks(character.code, previousPaths, snapshot.imagePaths)
            // 명시적 삭제는 맨 뒤다 — 이 캐릭터의 참조가 빠진 뒤라야 "다른 곳이 쓰는가"를
            // 제대로 판정할 수 있다(D7).
            applyPendingImageDeletes()
        } else {
            val newId = viewModel.insertCharacterSuspend(character)
            val fieldValues = resolvedFieldValues?.map { it.copy(characterId = newId) }
                ?: host.collectFieldValues(newId)
            viewModel.saveAllFieldValues(newId, fieldValues)
            savedCharId = newId
            resyncAutoLink()
            // 새 캐릭터도 서랍을 건드린다 — 전에 뗐던 이미지를 다시 골라 쓰는 경로가 여기다.
            // 뗄 것은 없으므로(옛 목록이 없다) 이 호출은 지우기만 한다.
            syncDetachedMarks(character.code, null, snapshot.imagePaths)
            applyPendingImageDeletes()
        }

        val tagList = snapshot.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        viewModel.replaceAllTagsSuspend(savedCharId, tagList.map { CharacterTag(characterId = savedCharId, tag = it) })

        syncNameBankLink(savedCharId, character.name)

        resetSavingState()
        if (fragment.isAdded && fragment.view != null) {
            Toast.makeText(fragment.requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
        }
        host.onSaved(savedCharId)
    }

    /**
     * 저장된 이름을 이름은행과 맞춘다 (B-124 ⓑ — Q7 확정: 자동 + 고지).
     *
     * **저장이 끝난 뒤에 부른다.** 신규 캐릭터는 그 전까지 id가 없어 걸 자리가 없고, 기존
     * 캐릭터도 이름이 DB에 들어간 뒤라야 은행과 화면이 같은 사실을 말한다.
     *
     * **실패해도 저장을 되돌리지 않는다** — 캐릭터는 이미 저장됐고 링크는 부가 정보다.
     * 다만 *조용히* 넘기지는 않는다: 결과 한 줄을 반드시 낸다(개발 의도 2번). 고지가
     * `Toast`인 것은 [notifyPreservedFieldValues]와 같은 이유다 — 저장 직후 호스트가
     * `popBackStack`하므로 뷰에 붙는 고지는 사용자에게 도달하지 못한다.
     */
    private suspend fun syncNameBankLink(savedCharacterId: Long, savedName: String) {
        val requested = host.requestedNameBankEntryId()
        val outcome = try {
            viewModel.applyNameBankLink(savedCharacterId, savedName, requested)
        } catch (e: Exception) {
            android.util.Log.e("CharacterSaveCoordinator", "Failed to sync name bank link", e)
            // 고른 것이 있었는데 못 걸었다면 그 사실만은 말한다 — 자동 대조뿐이었다면
            // 사용자가 지시한 조작이 아니므로 실패를 알릴 것이 없다(소음).
            if (requested != null) notifyNameBank(R.string.name_bank_link_failed)
            host.onNameBankLinkApplied()
            return
        }
        host.onNameBankLinkApplied()
        if (outcome.isSilent) return
        val ctx = fragment.context?.applicationContext ?: return
        val message = buildString {
            outcome.linkedName?.let { append(ctx.getString(R.string.name_bank_link_marked_used, it)) }
            if (outcome.releasedCount > 0) {
                if (isNotEmpty()) append(" ")
                append(ctx.getString(R.string.name_bank_link_released, outcome.releasedCount))
            }
            if (outcome.requestedTaken) {
                if (isNotEmpty()) append(" ")
                append(ctx.getString(R.string.name_bank_link_taken))
            }
            if (outcome.requestedMissing) {
                if (isNotEmpty()) append(" ")
                append(ctx.getString(R.string.name_bank_link_missing))
            }
        }
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }

    private fun notifyNameBank(messageRes: Int) {
        val ctx = fragment.context?.applicationContext ?: return
        Toast.makeText(ctx, messageRes, Toast.LENGTH_LONG).show()
    }

    /** restricted 위반 안내 — 사유(위반 토큰)와 허용 값 미리보기 + 두 가지 교정 경로 */
    private fun showRestrictedViolationDialog(
        violations: List<Pair<com.novelcharacter.app.data.model.FieldDefinition, List<String>>>,
        character: Character,
        isUpdate: Boolean,
        targetCharacterId: Long
    ) {
        val ctx = fragment.context ?: run { abortSave(); return }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val message = buildString {
                for ((fd, tokens) in violations) {
                    val allowed = viewModel.allowedValuesPreview(fd.id)
                    append(ctx.getString(R.string.field_library_restricted_violation_line,
                        fd.name, tokens.joinToString(", ")))
                    append("\n")
                    append(ctx.getString(R.string.field_library_restricted_allowed_preview,
                        if (allowed.isEmpty()) "-" else allowed.joinToString(", ")))
                    append("\n\n")
                }
                append(ctx.getString(R.string.field_library_restricted_violation_paths))
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.field_library_restricted_violation_title)
                .setMessage(message.trim())
                .setPositiveButton(R.string.field_library_restricted_add_and_save) { _, _ ->
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            viewModel.addRestrictedValuesToLibrary(violations)
                            performSave(character, isUpdate, targetCharacterId)
                        } catch (e: Exception) {
                            showSaveFailed()
                            abortSave()
                        }
                    }
                }
                .setNegativeButton(R.string.field_library_restricted_edit_input) { _, _ -> abortSave() }
                .setOnCancelListener { abortSave() }
                .show()
        }
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
        MaterialAlertDialogBuilder(ctx)
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

    /**
     * 세계관 안으로 들어가는 저장이면 새 세계관 id, 아니면 null.
     *
     * 종전 판정은 `old != null && new != null && old != new`라 **(미분류 → 세계관)** 이동을
     * "세계관 이동이 아님"으로 흘려보냈다. 그 경로는 이관(같은 key 재매핑)도 고지도 없이
     * 기존 값을 폼 값으로 통째로 대체했다 — 일괄 편집은 같은 조작에서 재매핑 + 휴지통
     * 백업 + 사후 고지를 한다. 이제 **새 세계관이 있고 기존과 다르면** 이동으로 본다.
     *
     * 반대 방향(세계관 → 미분류)은 이동이 아니라 '이탈'이라 [ScopeMove.crossUniverseId]가 null이고,
     * 저장 경로가 폼 커버 밖 값을 보존한다(일괄 편집의 `newUniverseId == null` 가드와 동형).
     * **다만 값을 전역 구역의 짝으로 이어 주는 일은 그쪽에서도 한다** — [ScopeMove.leavingUniverse]가
     * 그 경로를 가른다(B-128). 세력 소속까지 이동으로 다루면 소속이 말없이 지워지므로 갈라 둔다.
     *
     * '기존 세계관'은 **저장 대상**([targetCharacterId])의 것을 본다. 중복 이름 다이얼로그에서
     * '기존에 덮어쓰기'를 고르면 저장 대상이 편집 중인 캐릭터가 아니라 다른 캐릭터가 되는데,
     * 편집 중인 쪽을 기준으로 판정하면 이동이 아닌 저장을 이동으로 오판한다.
     */
    private data class ScopeMove(val old: Long?, val new: Long?) {
        /** 세계관 **안으로** 들어가는 저장이면 새 세계관 id, 아니면 null. */
        val crossUniverseId: Long? get() = if (new != null && old != new) new else null

        /** 세계관을 **떠나** 무소속이 되는 저장인가 (B-128 반대 방향). */
        val leavingUniverse: Boolean get() = new == null && old != null
    }

    private suspend fun scopeMoveOf(character: Character, targetCharacterId: Long): ScopeMove {
        val existingNovelId = if (targetCharacterId != -1L) {
            viewModel.getCharacterByIdSuspend(targetCharacterId)?.novelId
        } else {
            host.existingCharacter()?.novelId
        }
        return ScopeMove(
            old = viewModel.universeIdForNovel(existingNovelId),
            new = viewModel.universeIdForNovel(character.novelId)
        )
    }

    /**
     * 세계관 이동 여부에 따라 이관 저장(같은 이름 필드 유지·유실 시 스냅샷) 또는 일반 저장을 선택한다.
     *
     * 일반 저장에는 **폼이 실제로 렌더한 필드 정의 집합**을 함께 넘긴다. 폼의 권한을 그
     * 집합까지로 한정해야 작품을 '없음'으로 바꾼 저장이 필드값을 전량 삭제하지 않는다(N2).
     */
    private suspend fun applyCharacterUpdate(
        character: Character,
        values: List<CharacterFieldValue>,
        crossUniverseId: Long?,
        leavingUniverse: Boolean
    ) {
        if (crossUniverseId != null) {
            val counts = viewModel.updateCharacterAcrossUniverse(character, values, crossUniverseId)
            if (counts.keptGlobalValues > 0) notifyKeptGlobalValues(counts.keptGlobalValues)
        } else if (leavingUniverse) {
            // 세계관을 떠나는 저장 — 값을 전역 구역의 짝으로 이어 준다 (B-128).
            val outcome = viewModel.updateCharacterLeavingUniverse(
                character, values, host.coveredFieldDefinitionIds()
            )
            if (outcome.kept > 0) notifyKeptGlobalValues(outcome.kept)
            else if (outcome.preserved > 0) notifyPreservedFieldValues(outcome.preserved)
        } else {
            val preserved = viewModel.updateCharacterWithFields(
                character, values, host.coveredFieldDefinitionIds()
            )
            if (preserved > 0) notifyPreservedFieldValues(preserved)
        }
    }

    /**
     * 화면에 보이지 않지만 지우지 않고 남긴 필드값을 알린다.
     *
     * 유실은 막았으니 이제 '일일이 확인하지 않으면 존재를 알 수 없는 데이터'가 되지 않게
     * 해야 한다(원칙 04). 작품을 다시 배정하면 되살아난다는 교정 경로까지 함께 알린다.
     */
    private fun notifyPreservedFieldValues(count: Int) {
        val ctx = fragment.context?.applicationContext ?: return
        // Snackbar가 아니라 Toast인 이유: 저장이 끝나면 host.onSaved가 곧바로 popBackStack해
        // 프래그먼트 뷰가 사라진다. 뷰에 붙는 고지는 사용자에게 도달하지 못한다.
        Toast.makeText(ctx, ctx.getString(R.string.field_values_preserved, count), Toast.LENGTH_LONG).show()
    }

    /**
     * **안전하지 않아 이어 주지 못한 값을 알린다** (B-128 — 확정이 착수 세션에 맡긴 자리).
     *
     * 확정은 *"보관 값으로 남기고 **그 사유를 고지**한다"*까지를 정했고, 어디서 말할지는
     * 남겨 두었다. 저장 경로에 이미 서 있는 고지 표면([notifyPreservedFieldValues])을 그대로
     * 쓴다 — 이관은 저장이 끝나는 그 순간에 일어나고, 같은 자리에서 같은 방식으로 말해야
     * 사용자가 두 고지를 다른 사건으로 오해하지 않는다. **묻지 않는 것도 확정이다**
     * (*항상 묻기*는 안전한 대부분에 마찰을 물린다는 이유로 기각됐다 — 원칙 04).
     *
     * 옮긴 것은 알리지 않는다 — 알릴 것은 *사용자가 손댈 것이 남았다*는 사실이지
     * *잘 처리됐다*는 사실이 아니다(활성 프로바이더 고지가 '첫 등록'에는 침묵하는 것과 같은 규칙).
     */
    private fun notifyKeptGlobalValues(count: Int) {
        val ctx = fragment.context?.applicationContext ?: return
        Toast.makeText(
            ctx, ctx.getString(R.string.global_field_values_kept, count), Toast.LENGTH_LONG
        ).show()
    }

    /** 세계관 이동 시 유실(제거) 고지 다이얼로그 — 같은 이름 필드 이관·제거분 휴지통 백업(복원 가능) 안내. */
    private fun showCrossUniverseMoveDialog(
        loss: com.novelcharacter.app.data.repository.UniverseMoveCounts,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (!fragment.isAdded || fragment.view == null) { onCancel(); return }
        MaterialAlertDialogBuilder(fragment.requireContext())
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
     * 캐릭터 자동 링크 재동기화 — 이번 저장으로 등록된 이미지는 함께 링크되고, 빠진 이미지는
     * 링크가 풀린다(설정 기본 켜짐 — [com.novelcharacter.app.util.ImageSettingsStore]).
     * 실패해도 저장을 되돌리지 않는다 — 링크는 다음 저장·배정의 전체 재동기화가 치유한다.
     */
    private suspend fun resyncAutoLink() {
        val appCtx = fragment.context?.applicationContext ?: return
        val db = (fragment.activity?.application as? com.novelcharacter.app.NovelCharacterApp)?.database ?: return
        try {
            com.novelcharacter.app.util.CharacterImageAutoLinker.resyncIfEnabled(appCtx, db)
        } catch (_: Exception) { /* 저장이 우선 — 링크는 다음 재동기화가 수렴시킨다 */ }
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

    /**
     * 뗀 표식을 저장 결과에 맞춘다(B-107 D2 — 붙는 자리 ②, 푸는 자리 ①).
     *
     * **뺀 것과 남은 것을 함께 넘긴다.** 판정은
     * [com.novelcharacter.app.util.DetachedImageMarker]가 하고, 그것이 뺀 것 중 캐릭터가
     * 아무도 안 쓰게 된 것에 표식을 붙이며 남은 것에서는 표식을 지운다 — **전에 뗐던 이미지를
     * 다시 골라 쓴 경우가 후자다.** 두 방향을 한 호출로 두는 이유는 규칙이 하나이기 때문이다.
     *
     * [cleanupRemovedImages] **뒤에** 부른다: 그쪽이 정책에 따라 meta 행을 만들거나
     * (`adoptOrphans`) 지울 수 있고, 없는 행에는 표식을 붙일 자리가 없다.
     */
    private suspend fun syncDetachedMarks(
        code: String,
        oldPathsJson: String?,
        currentPaths: List<String>
    ) {
        val oldPaths: List<String> = try {
            gson.fromJson(oldPathsJson ?: "[]", com.novelcharacter.app.util.GsonTypes.STRING_LIST) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val touched = (oldPaths + currentPaths).distinct()
        if (touched.isEmpty()) return
        val db = (fragment.activity?.application as? com.novelcharacter.app.NovelCharacterApp)?.database ?: return
        // 표식은 부가 정보다 — 실패해도 저장 자체를 되돌리지 않는다(파일도 소유도 이미 맞다).
        runCatching {
            com.novelcharacter.app.util.DetachedImageMarker.sync(
                db, touched, code.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 편집창에서 "앱에서 삭제"를 고른 이미지를 **지금** 지운다(B-107 D7).
     *
     * 저장이 끝난 뒤에 부른다 — 그래야 이 캐릭터의 참조가 이미 빠져 있고, 그때까지 남아 있는
     * 소유자는 **정말로 다른 곳**이다.
     *
     * `ImageOwnershipGuard.deleteIfUnprotected`를 쓰지 않는 이유: 그쪽은 **라이브러리 meta 행이
     * 있는 파일을 통째로 보호**한다(그것이 그 함수의 목적이다). 여기서 쓰면 라이브러리에 든
     * 이미지는 사용자가 명시적으로 골라도 영영 안 지워진다. 대신 보호해야 할 것을 직접 본다 —
     * **다른 엔티티가 쓰는가 · 휴지통이 쥐고 있는가.** 못 지운 것은 세어서 알린다(조용한 실패 금지).
     */
    private suspend fun applyPendingImageDeletes() {
        val pending = host.pendingImageDeletes()
        if (pending.isEmpty()) return
        val db = (fragment.activity?.application as? com.novelcharacter.app.NovelCharacterApp)?.database ?: return
        var deleted = 0
        var protectedCount = 0
        runCatching {
            val ownerIndex = com.novelcharacter.app.util.ImageDeletionService.buildOwnerIndex(db, gson)
            val trashHeld = com.novelcharacter.app.util.StorageAnalyzer.collectTrashHeldPaths(db, gson)
            for (path in pending) {
                val canon = com.novelcharacter.app.util.ImagePathMatch.canonical(path)
                val stillUsed = ownerIndex[canon]?.isEmpty == false || canon in trashHeld
                if (stillUsed) { protectedCount++; continue }
                val freed = com.novelcharacter.app.util.ImageDeletionService.delete(
                    db = db, path = path,
                    owners = com.novelcharacter.app.util.ImageDeletionService.Owners.NONE,
                    // `OrganizeFolderService`의 삭제 루프와 같은 모양이다 — 같은 `delete`에
                    // 먹이고, 그 함수가 다른 행의 linkGroupId를 바꾼다.
                    // 단발 허용(B-243 — 읽는 값을 소비처가 되쓴다. 겹이 필요하다)
                    linkGroupId = db.imageMetaDao().getByPath(path)?.linkGroupId,
                    gson = gson
                )
                if (freed != null) deleted++ else protectedCount++
            }
        }
        host.onPendingImageDeletesApplied(deleted, protectedCount)
    }
}
