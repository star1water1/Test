package com.novelcharacter.app.ui.trash

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.dao.TrashSnapshotSummary
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.RestoreLossCounts
import com.novelcharacter.app.data.repository.RestoreModes
import com.novelcharacter.app.data.repository.TrashGrouping
import com.novelcharacter.app.data.repository.TrashRepository
import com.novelcharacter.app.databinding.FragmentTrashBinding
import com.novelcharacter.app.databinding.ItemTrashBinding
import com.novelcharacter.app.databinding.ItemTrashOperationBinding
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.reportAndNotify
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 휴지통 (B-7 → B-1) — 삭제된 캐릭터·세계관·작품·세력·사건 스냅샷 목록.
 *
 * 목록은 **삭제 작업 단위로 묶여** 표시된다. 세계관 하나를 지우면 항목이 수백 개 생기므로
 * 평평한 목록만으로는 "무엇을 지웠는지"가 사라지고, 개별 복원만 있으면 사용자가 순서
 * (세계관 → 작품 → 세력 → 사건 → 캐릭터)를 직접 맞춰야 한다(원칙 04 위반).
 * 그룹 머리글의 '전체 복원'이 그 순서를 대신 지킨다.
 */
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    private val trashRepository: TrashRepository by lazy {
        (requireActivity().application as NovelCharacterApp).trashRepository
    }

    private val adapter = TrashAdapter(
        onRestore = { confirmRestore(it) },
        onPurge = { item, siblings -> confirmPurge(item, siblings) },
        onRestoreOperation = { confirmRestoreOperation(it) },
        onPurgeOperation = { confirmPurgeOperation(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.toolbar.inflateMenu(R.menu.menu_trash)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_empty_trash) {
                confirmEmptyTrash()
                true
            } else {
                false
            }
        }

        binding.trashRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.trashRecyclerView.adapter = adapter

        trashRepository.allSnapshots.observe(viewLifecycleOwner) { snapshots ->
            adapter.submit(buildRows(snapshots, requireContext()))
            binding.emptyText.visibility = if (snapshots.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 항목 단위 복원
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 복원 확인 — 먼저 미리보기로 무엇이 되살아나지 않는지 확인한다.
     *
     * 복원은 성공하면 스냅샷을 소각하므로, 되살릴 수 없는 부분이 있는 채로 진행하면
     * payload에만 남아 있던 원본이 그 순간 영구 소멸한다. 취소하면 스냅샷은 그대로 남으므로
     * 세계관·작품·필드 정의를 먼저 되살린 뒤 다시 복원할 수 있다(검증 → 알림 → 교정 경로).
     */
    private fun confirmRestore(snapshot: TrashSnapshotSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            val preview = try {
                trashRepository.previewRestore(snapshot.id)
            } catch (_: Exception) {
                null
            }
            if (!isAdded) return@launch

            // 되살릴 수 없는 사유가 있으면 진행 자체를 막는다 — 스냅샷을 남겨야
            // 상위 항목을 먼저 복원한 뒤 다시 시도할 수 있다(R-4).
            preview?.blocker?.let { blocker ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.trash_restore_blocked_title)
                    .setMessage(blockerMessage(blocker))
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@launch
            }

            if (preview == null || !preview.needsConfirmation) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.trash_restore)
                    .setMessage(getString(R.string.trash_restore_confirm, snapshot.entityName))
                    .setPositiveButton(R.string.confirm) { _, _ -> restore(snapshot, warned = false) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@launch
            }

            val details = buildSkipDetails(preview.losses)
            val message = StringBuilder(
                if (details.isEmpty()) {
                    // 유실은 없고 다른 이유(편집 백업·구버전 payload)로만 확인이 필요한 경우 —
                    // 없는 유실을 있는 것처럼 적지 않는다.
                    getString(R.string.trash_restore_confirm, snapshot.entityName)
                } else {
                    getString(R.string.trash_restore_preview, snapshot.entityName, details)
                }
            )
            if (preview.duplicatesLivingEntity) {
                message.append(getString(R.string.trash_restore_duplicate_warning))
            }
            if (preview.revertsInPlace) {
                message.append(
                    getString(R.string.trash_restore_revert_warning, revertScopeLines(preview.revertScope))
                )
            }
            if (preview.legacyPayload) {
                message.append(getString(R.string.trash_restore_legacy_note))
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_restore_preview_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.trash_restore) { _, _ ->
                    restore(
                        snapshot, warned = true, predicted = preview.losses,
                        consentedRevert = preview.revertsInPlace
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun blockerMessage(blocker: TrashRepository.RestoreBlocker): String = when (blocker) {
        TrashRepository.RestoreBlocker.MISSING_UNIVERSE -> getString(R.string.trash_restore_blocked_universe)
        TrashRepository.RestoreBlocker.MISSING_CHARACTER -> getString(R.string.trash_restore_blocked_character)
        TrashRepository.RestoreBlocker.ALREADY_EXISTS -> getString(R.string.trash_restore_blocked_exists)
    }

    /**
     * 되돌리기가 덮어쓸 갈래를 사람이 읽는 목록으로 — 무엇이 덮이는지 **세어서** 알려야
     * 동의가 성립한다(R-4). 범위는 그 편집이 실제로 파괴한 것까지다.
     */
    private fun revertScopeLines(scope: Set<String>): String {
        val lines = mutableListOf<String>()
        if (RestoreModes.SCOPE_CHARACTER_ROW in scope) {
            lines.add(getString(R.string.trash_revert_scope_character_row))
        }
        if (RestoreModes.SCOPE_FIELD_VALUES in scope) {
            lines.add(getString(R.string.trash_revert_scope_field_values))
        }
        if (RestoreModes.SCOPE_MEMBERSHIPS in scope) {
            lines.add(getString(R.string.trash_revert_scope_memberships))
        }
        if (RestoreModes.SCOPE_STATE_CHANGES in scope) {
            lines.add(getString(R.string.trash_revert_scope_state_changes))
        }
        return lines.joinToString("\n")
    }

    /**
     * 미리보기·결과가 공유하는 항목별 사유 문구 — 두 곳이 드리프트하지 않게 한 곳에서 만든다.
     * [RestoreLossCounts]를 통째로 받는다: 항목이 늘어날 때 인자 목록을 고치지 않아도 되고,
     * "새 유실 칸을 만들었는데 화면에는 안 나오는" 무음 경로가 생기지 않는다.
     */
    private fun buildSkipDetails(losses: RestoreLossCounts): String {
        val details = mutableListOf<String>()
        if (losses.novelCleared) details.add(getString(R.string.trash_skip_novel))
        if (losses.universeCleared) details.add(getString(R.string.trash_skip_universe))
        if (losses.fieldValues > 0) details.add(getString(R.string.trash_skip_fields, losses.fieldValues))
        if (losses.mergedFieldValues > 0) {
            details.add(getString(R.string.trash_skip_merged_fields, losses.mergedFieldValues))
        }
        if (losses.orphanFieldValues > 0) {
            details.add(getString(R.string.trash_skip_orphan_values, losses.orphanFieldValues))
        }
        if (losses.fieldDefinitions > 0) {
            details.add(getString(R.string.trash_skip_field_definitions, losses.fieldDefinitions))
        }
        if (losses.fieldValueEntries > 0) {
            details.add(getString(R.string.trash_skip_field_value_entries, losses.fieldValueEntries))
        }
        if (losses.relationships > 0) {
            details.add(getString(R.string.trash_skip_relationships, losses.relationships))
        }
        if (losses.relationshipChanges > 0) {
            details.add(getString(R.string.trash_skip_relationship_changes, losses.relationshipChanges))
        }
        if (losses.duplicateRelationshipChanges > 0) {
            details.add(getString(R.string.trash_skip_dup_relationship_changes, losses.duplicateRelationshipChanges))
        }
        if (losses.memberships > 0) details.add(getString(R.string.trash_skip_memberships, losses.memberships))
        if (losses.factionRelationships > 0) {
            details.add(getString(R.string.trash_skip_faction_relationships, losses.factionRelationships))
        }
        if (losses.detachedRelationships > 0) {
            details.add(getString(R.string.trash_skip_detached_relationships, losses.detachedRelationships))
        }
        if (losses.events > 0) details.add(getString(R.string.trash_skip_events, losses.events))
        if (losses.characterLinks > 0) {
            details.add(getString(R.string.trash_skip_character_links, losses.characterLinks))
        }
        if (losses.novelLinks > 0) details.add(getString(R.string.trash_skip_novel_links, losses.novelLinks))
        if (losses.changeLinks > 0) details.add(getString(R.string.trash_skip_change_links, losses.changeLinks))
        if (losses.relationshipFactions > 0) {
            details.add(getString(R.string.trash_skip_rel_factions, losses.relationshipFactions))
        }
        if (losses.changeEvents > 0) details.add(getString(R.string.trash_skip_change_events, losses.changeEvents))
        if (losses.imageLinks > 0) details.add(getString(R.string.trash_skip_image_links, losses.imageLinks))
        if (losses.stateChanges > 0) {
            details.add(getString(R.string.trash_skip_state_changes, losses.stateChanges))
        }
        if (losses.nameBankLinks > 0) {
            details.add(getString(R.string.trash_skip_name_bank, losses.nameBankLinks))
        }
        return details.joinToString("\n")
    }

    /**
     * @param warned 복원 전 확인 다이얼로그에서 유실 항목을 이미 고지하고 동의를 받았는가.
     * @param predicted 그때 고지한 항목별 유실 규모. **어느 항목이라도 실제가 이보다 커지면
     *   사후에도 알린다** — 미리보기는 예측이고 결과가 사실이다. 총량 하나로 비교하면
     *   예고분이 사라지고 다른 유실이 생긴 경우(총량은 줄었지만 동의한 적 없는 유실)를 놓친다.
     */
    private fun restore(
        snapshot: TrashSnapshotSummary,
        warned: Boolean,
        predicted: RestoreLossCounts = RestoreLossCounts(),
        /**
         * 되돌리기(살아 있는 대상 덮어쓰기)에 동의했는가 — 동의 없이는 덮어쓰지 않는다.
         * 미리보기 이후 원본이 되살아나 판정이 뒤집혀도 사용자가 동의한 범위를 넘지 않는다.
         */
        consentedRevert: Boolean = false
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = trashRepository.restoreSnapshot(snapshot.id, consentedRevert)
                resyncAutoLinkAfterRestore()
                if (!isAdded) return@launch
                if (result == null) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                    logOperation(OpResult.failure(OpResult.CAT_TRASH,
                        getString(R.string.result_trash_restore_failed)))
                    return@launch
                }
                // 실제로 한 일을 말한다 — 동의 시점의 예고가 아니라 결과가 사실이다.
                val doneMessage = if (result.revertedInPlace) {
                    getString(R.string.trash_reverted, result.restoredName)
                } else {
                    getString(R.string.trash_restored, result.restoredName)
                }
                Toast.makeText(requireContext(), doneMessage, Toast.LENGTH_SHORT).show()
                // 즉시 알림은 위 Toast/부분복원 다이얼로그가 담당 — 이력만 추가
                logOperation(OpResult.success(OpResult.CAT_TRASH, doneMessage))
                showRestoreNotes(
                    result.losses, result.relinkedByCode, result.duplicateRelationships,
                    warned, predicted, semanticStateChanges = result.restoredSemanticStateChanges
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                }
                logOperation(OpResult.failure(OpResult.CAT_TRASH,
                    getString(R.string.result_trash_restore_failed), e.message))
            }
        }
    }

    /**
     * 미리보기가 예고하지 못하는 결과만 사후에 알린다 — 참조 소실은 이미 복원 전 확인
     * 다이얼로그에서 고지하고 동의를 받았으므로 같은 내용을 두 번 띄우지 않는다.
     * 코드 재연결·중복 관계는 실제로 써 보기 전에는 알 수 없는 결과다.
     */
    private fun showRestoreNotes(
        losses: RestoreLossCounts,
        relinkedByCode: Int,
        duplicateRelationships: Int,
        warned: Boolean,
        predicted: RestoreLossCounts,
        extraNote: String? = null,
        semanticStateChanges: Int = 0
    ) {
        val notes = mutableListOf<String>()
        extraNote?.let { notes.add(it) }
        // 이력은 되살렸지만 파생 필드값은 되돌리지 않았다 — 사실대로 알린다.
        if (semanticStateChanges > 0) {
            notes.add(getString(R.string.trash_restore_semantic_note, semanticStateChanges))
        }
        // 고지 없이 진행했거나, 실제 유실이 예고보다 커졌으면 사실대로 알린다.
        if (losses.any && (!warned || losses.exceeds(predicted))) {
            val details = buildSkipDetails(losses)
            if (details.isNotEmpty()) {
                notes.add(getString(R.string.trash_restore_partial, details))
            }
        }
        if (relinkedByCode > 0) {
            notes.add(getString(R.string.trash_restore_relinked, relinkedByCode))
        }
        if (duplicateRelationships > 0) {
            notes.add(getString(R.string.trash_restore_duplicate_rel, duplicateRelationships))
        }
        if (notes.isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_restore_note_title)
                .setMessage(notes.joinToString("\n"))
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 작업(그룹) 단위 복원·삭제
    // ──────────────────────────────────────────────────────────────────────

    private fun confirmRestoreOperation(row: TrashRow.Operation) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 그룹 미리보기는 "이 작업이 곧 되살릴 코드"를 알고 계산한다 — 그러지 않으면
            // "세력을 못 살린다"처럼 순서만 지키면 해결될 일을 유실로 예고하게 된다.
            val previews = try {
                trashRepository.previewOperation(row.opKey)
            } catch (_: Exception) {
                emptyList()
            }
            if (!isAdded) return@launch

            // 막힌 항목(상위 엔티티가 없어 되살릴 수 없는 것)은 복원되지 않고 휴지통에 남는다.
            // 그 사실을 **실행 전에** 말해야 한다(R-4). 그 항목들의 예상 유실은 실제로 일어나지
            // 않으므로 예고 집계에서도 뺀다 — 넣으면 없는 유실을 예고하고, 사후 비교 기준까지
            // 부풀어 진짜 예상 밖 유실을 덮는다.
            val blocked = previews.filter { it.blocker != null }
            val restorable = previews.filter { it.blocker == null }
            val predicted = restorable.fold(RestoreLossCounts()) { acc, p -> acc + p.losses }
            val message = StringBuilder(
                getString(R.string.trash_operation_restore_confirm, row.itemCount - blocked.size)
            )
            if (blocked.isNotEmpty()) {
                message.append("\n\n").append(
                    getString(
                        R.string.trash_operation_blocked,
                        blocked.size,
                        blocked.joinToString("\n") { "- ${it.entityName}" }
                    )
                )
            }
            val details = buildSkipDetails(predicted)
            if (details.isNotEmpty()) {
                message.append("\n\n").append(getString(R.string.trash_restore_partial, details))
            }
            if (restorable.any { it.legacyPayload }) {
                message.append(getString(R.string.trash_restore_legacy_note))
            }
            if (restorable.any { it.duplicatesLivingEntity }) {
                message.append(getString(R.string.trash_restore_duplicate_warning))
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_restore_operation)
                .setMessage(message.toString())
                .setPositiveButton(R.string.trash_restore) { _, _ -> restoreOperation(row, predicted) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun restoreOperation(row: TrashRow.Operation, predicted: RestoreLossCounts) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = trashRepository.restoreOperation(row.opKey)
                resyncAutoLinkAfterRestore()
                if (!isAdded) return@launch
                Toast.makeText(
                    requireContext(),
                    getString(R.string.trash_operation_restored, result.restored.size),
                    Toast.LENGTH_SHORT
                ).show()
                logOperation(OpResult.success(OpResult.CAT_TRASH,
                    getString(R.string.trash_operation_restored, result.restored.size)))
                // 되살리지 못한 항목은 휴지통에 남는다 — 사실을 먼저 알린다(막힌 항목이 있으면
                // 상위 항목이 없다는 뜻이라, 이것을 숨기면 사용자는 조용히 잃었다고 오해한다).
                val failedNote = if (result.failed.isEmpty()) null else getString(
                    R.string.trash_operation_failed,
                    result.failed.size,
                    result.failed.joinToString("\n") { "- $it" }
                )
                showRestoreNotes(
                    result.losses, result.relinkedByCode, result.duplicateRelationships,
                    warned = true, predicted = predicted, extraNote = failedNote,
                    semanticStateChanges = result.restored.sumOf { it.restoredSemanticStateChanges }
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                }
                logOperation(OpResult.failure(OpResult.CAT_TRASH,
                    getString(R.string.result_trash_restore_failed), e.message))
            }
        }
    }

    /**
     * 복원 직후 캐릭터 자동 링크 재동기화 — 복원은 등록 이벤트라, 되살아난 캐릭터의 이미지가
     * 다음 저장을 기다리지 않고 지금 다시 묶이게 한다(id 재발급으로 낡은 토큰도 함께 치유).
     * 실패해도 복원 결과를 되돌리지 않는다(다음 재동기화가 수렴시킨다).
     */
    private suspend fun resyncAutoLinkAfterRestore() {
        val appCtx = context?.applicationContext ?: return
        val db = (activity?.application as? com.novelcharacter.app.NovelCharacterApp)?.database ?: return
        runCatching { com.novelcharacter.app.util.CharacterImageAutoLinker.resyncIfEnabled(appCtx, db) }
    }

    private fun confirmPurgeOperation(row: TrashRow.Operation) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_delete_forever)
            .setMessage(getString(R.string.trash_operation_purge_confirm, row.itemCount))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val purged = trashRepository.purgeOperation(row.opKey)
                        if (isAdded) reportAndNotify(OpResult.success(OpResult.CAT_TRASH,
                            getString(R.string.trash_operation_purged, purged)))
                    } catch (e: Exception) {
                        if (isAdded) reportAndNotify(OpResult.failure(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_purge_failed), e.message))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * @param siblingCount 같은 삭제 작업에 남아 있는 다른 항목 수. 0이 아니면 **먼저 알린다** —
     *   묶음의 뿌리(세계관 등)를 혼자 영구 삭제하면 남은 항목들이 붙을 자리를 잃어 복원이
     *   막히거나 참조가 빠진 채 되살아난다. 그 사실을 말하지 않으면 '개별 영구 삭제'가
     *   조용히 나머지를 못 쓰게 만든다(R-4).
     */
    private fun confirmPurge(snapshot: TrashSnapshotSummary, siblingCount: Int = 0) {
        val message = StringBuilder(getString(R.string.trash_purge_confirm, snapshot.entityName))
        if (siblingCount > 0) {
            message.append("\n\n").append(getString(R.string.trash_purge_sibling_warning, siblingCount))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_delete_forever)
            .setMessage(message.toString())
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        trashRepository.purgeSnapshot(snapshot.id)
                        if (isAdded) reportAndNotify(OpResult.success(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_purged, snapshot.entityName)))
                    } catch (e: Exception) {
                        if (isAdded) reportAndNotify(OpResult.failure(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_purge_failed), e.message))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_empty_all)
            .setMessage(R.string.trash_empty_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val purged = trashRepository.emptyTrash()
                        if (isAdded) reportAndNotify(OpResult.success(OpResult.CAT_TRASH,
                            if (purged > 0) getString(R.string.result_trash_emptied, purged)
                            else getString(R.string.result_trash_empty_none)))
                    } catch (e: Exception) {
                        if (isAdded) reportAndNotify(OpResult.failure(OpResult.CAT_TRASH,
                            getString(R.string.result_trash_empty_failed), e.message))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding.trashRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // 목록 구성
    // ──────────────────────────────────────────────────────────────────────

    /** 목록 행 — 작업 머리글과 그에 속한 항목. */
    sealed class TrashRow {
        data class Operation(
            val opKey: String,
            val title: String,
            val itemCount: Int,
            val deletedAt: Long
        ) : TrashRow()

        /** @param siblingCount 같은 삭제 작업에 함께 있는 다른 항목 수 (개별 영구 삭제 고지용) */
        data class Item(val snapshot: TrashSnapshotSummary, val siblingCount: Int = 0) : TrashRow()
    }

    private class TrashAdapter(
        private val onRestore: (TrashSnapshotSummary) -> Unit,
        private val onPurge: (TrashSnapshotSummary, Int) -> Unit,
        private val onRestoreOperation: (TrashRow.Operation) -> Unit,
        private val onPurgeOperation: (TrashRow.Operation) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<TrashRow> = emptyList()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun submit(list: List<TrashRow>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is TrashRow.Operation) VIEW_OPERATION else VIEW_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_OPERATION) {
                OperationHolder(ItemTrashOperationBinding.inflate(inflater, parent, false))
            } else {
                Holder(ItemTrashBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is TrashRow.Operation -> {
                    val b = (holder as OperationHolder).binding
                    b.opTitle.text = row.title
                    b.opMeta.text = b.root.context.getString(
                        R.string.trash_operation_meta, row.itemCount, dateFormat.format(Date(row.deletedAt))
                    )
                    b.btnRestoreAll.setOnClickListener { onRestoreOperation(row) }
                    b.btnPurgeAll.setOnClickListener { onPurgeOperation(row) }
                }
                is TrashRow.Item -> {
                    val b = (holder as Holder).binding
                    val item = row.snapshot
                    b.trashItemName.text = item.entityName
                    b.trashItemMeta.text = b.root.context.getString(
                        R.string.trash_item_meta,
                        typeLabel(b.root.context, item.entityType),
                        dateFormat.format(Date(item.deletedAt))
                    )
                    b.btnRestore.setOnClickListener { onRestore(item) }
                    b.btnPurge.setOnClickListener { onPurge(item, row.siblingCount) }
                }
            }
        }

        override fun getItemCount() = items.size

        class Holder(val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root)
        class OperationHolder(val binding: ItemTrashOperationBinding) :
            RecyclerView.ViewHolder(binding.root)

        companion object {
            const val VIEW_OPERATION = 0
            const val VIEW_ITEM = 1
        }
    }

    companion object {
        /** 알 수 없는 타입은 원문 그대로 보여준다 — 라벨이 없다고 항목을 숨기면 존재를 잃는다. */
        fun typeLabel(context: android.content.Context, entityType: String): String = when (entityType) {
            TrashSnapshot.TYPE_CHARACTER -> context.getString(R.string.trash_type_character)
            TrashSnapshot.TYPE_UNIVERSE -> context.getString(R.string.trash_type_universe)
            TrashSnapshot.TYPE_UNIVERSE_DATA -> context.getString(R.string.trash_type_universe_data)
            TrashSnapshot.TYPE_NOVEL -> context.getString(R.string.trash_type_novel)
            TrashSnapshot.TYPE_FACTION -> context.getString(R.string.trash_type_faction)
            TrashSnapshot.TYPE_EVENT -> context.getString(R.string.trash_type_event)
            TrashSnapshot.TYPE_STATE_CHANGE -> context.getString(R.string.trash_type_state_change)
            else -> entityType
        }

        /**
         * 스냅샷 목록을 작업 그룹 행으로 펼친다.
         *
         * 묶음·정렬은 [TrashGrouping]이 정한다(그 정렬이 곧 복원 순서라 순수 로직으로 떼어
         * 실행 검증한다). 여기는 문구 조립만 한다 — 머리글은 묶음의 **뿌리 항목**
         * (= 그 삭제의 주어)에서 만들며, 문구를 DB 컬럼에 굳히지 않는 이유가 이것이다.
         */
        fun buildRows(snapshots: List<TrashSnapshotSummary>, context: android.content.Context): List<TrashRow> {
            val groups = TrashGrouping.group(snapshots)
            val rows = ArrayList<TrashRow>(snapshots.size + groups.size)
            for (group in groups) {
                if (group.needsHeader) {
                    val root = group.root
                    rows.add(
                        TrashRow.Operation(
                            opKey = group.opKey,
                            title = context.getString(
                                R.string.trash_operation_title,
                                typeLabel(context, root.entityType),
                                root.entityName
                            ),
                            itemCount = group.size,
                            deletedAt = group.newestAt
                        )
                    )
                }
                // 편집 백업은 항목끼리 의존하지 않으므로 '형제를 못 쓰게 만든다'는 경고가
                // 해당하지 않는다 — 머리글이 없는 묶음에는 형제 수를 세지 않는다.
                val siblings = if (group.needsHeader) group.size - 1 else 0
                group.items.forEach { rows.add(TrashRow.Item(it, siblings)) }
            }
            return rows
        }
    }
}
