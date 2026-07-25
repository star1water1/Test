package com.novelcharacter.app.ui.trash

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.TrashRepository
import com.novelcharacter.app.databinding.FragmentTrashBinding
import com.novelcharacter.app.databinding.ItemTrashBinding
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.reportAndNotify
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 휴지통 (B-7) — 삭제된 캐릭터 스냅샷 목록. 복원 / 영구 삭제 / 비우기.
 */
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    private val trashRepository: TrashRepository by lazy {
        (requireActivity().application as NovelCharacterApp).trashRepository
    }

    private val adapter = TrashAdapter(
        onRestore = { confirmRestore(it) },
        onPurge = { confirmPurge(it) }
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
            adapter.submit(snapshots)
            binding.emptyText.visibility = if (snapshots.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * 복원 확인 — 먼저 미리보기로 무엇이 되살아나지 않는지 확인한다.
     *
     * 복원은 성공하면 스냅샷을 소각하므로, 되살릴 수 없는 부분이 있는 채로 진행하면
     * payload에만 남아 있던 원본이 그 순간 영구 소멸한다. 취소하면 스냅샷은 그대로 남으므로
     * 세계관·작품·필드 정의를 먼저 되살린 뒤 다시 복원할 수 있다(검증 → 알림 → 교정 경로).
     */
    private fun confirmRestore(snapshot: TrashSnapshot) {
        viewLifecycleOwner.lifecycleScope.launch {
            val preview = try {
                trashRepository.previewRestore(snapshot.id)
            } catch (_: Exception) {
                null
            }
            if (!isAdded) return@launch
            if (preview == null || !preview.needsConfirmation) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.trash_restore)
                    .setMessage(getString(R.string.trash_restore_confirm, snapshot.entityName))
                    .setPositiveButton(R.string.confirm) { _, _ -> restore(snapshot, warned = false) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@launch
            }

            val details = buildSkipDetails(
                novelCleared = preview.novelCleared,
                fieldValues = preview.skippedFieldValues,
                relationships = preview.skippedRelationships,
                relationshipChanges = preview.skippedRelationshipChanges,
                memberships = preview.skippedMemberships,
                events = preview.skippedEvents,
                relFactions = preview.clearedRelationshipFactions,
                changeEvents = preview.clearedChangeEvents
            )
            val message = StringBuilder(
                getString(
                    R.string.trash_restore_preview,
                    snapshot.entityName,
                    details.ifEmpty { "-" }
                )
            )
            if (preview.duplicatesLivingCharacter) {
                message.append(getString(R.string.trash_restore_duplicate_warning))
            }
            if (preview.legacyPayload) {
                message.append(getString(R.string.trash_restore_legacy_note))
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.trash_restore_preview_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.trash_restore) { _, _ -> restore(snapshot, warned = true) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 복원 미리보기·결과가 공유하는 항목별 사유 문구 — 두 곳이 드리프트하지 않게 한 곳에서 만든다. */
    private fun buildSkipDetails(
        novelCleared: Boolean,
        fieldValues: Int,
        relationships: Int,
        relationshipChanges: Int,
        memberships: Int,
        events: Int,
        relFactions: Int,
        changeEvents: Int
    ): String {
        val details = mutableListOf<String>()
        if (novelCleared) details.add(getString(R.string.trash_skip_novel))
        if (fieldValues > 0) details.add(getString(R.string.trash_skip_fields, fieldValues))
        if (relationships > 0) details.add(getString(R.string.trash_skip_relationships, relationships))
        if (relationshipChanges > 0) {
            details.add(getString(R.string.trash_skip_relationship_changes, relationshipChanges))
        }
        if (memberships > 0) details.add(getString(R.string.trash_skip_memberships, memberships))
        if (events > 0) details.add(getString(R.string.trash_skip_events, events))
        if (relFactions > 0) details.add(getString(R.string.trash_skip_rel_factions, relFactions))
        if (changeEvents > 0) details.add(getString(R.string.trash_skip_change_events, changeEvents))
        return details.joinToString("\n")
    }

    /**
     * @param warned 복원 전 확인 다이얼로그에서 유실 항목을 이미 고지하고 동의를 받았는가.
     *   받았다면 같은 내용을 사후에 반복하지 않고, 미리보기가 예고하지 못한 결과만 알린다.
     *   (미리보기 이후 DB가 바뀌어 실제 유실이 더 커졌을 수 있으므로, 고지 없이 진행한
     *    경우에는 사후에라도 반드시 알린다 — 무음 유실 금지)
     */
    private fun restore(snapshot: TrashSnapshot, warned: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = trashRepository.restoreCharacter(snapshot.id)
                if (!isAdded) return@launch
                if (result == null) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                    logOperation(OpResult.failure(OpResult.CAT_TRASH,
                        getString(R.string.result_trash_restore_failed)))
                    return@launch
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.trash_restored, result.restoredName),
                    Toast.LENGTH_SHORT
                ).show()
                // 즉시 알림은 위 Toast/부분복원 다이얼로그가 담당 — 이력만 추가
                logOperation(OpResult.success(OpResult.CAT_TRASH,
                    getString(R.string.trash_restored, result.restoredName)))
                // 미리보기가 예고하지 못하는 결과만 사후에 알린다 — 참조 소실은 이미 복원 전
                // 확인 다이얼로그에서 고지하고 동의를 받았으므로 같은 내용을 두 번 띄우지 않는다.
                // 코드 재연결·중복 관계는 실제로 써 보기 전에는 알 수 없는 결과다.
                val notes = mutableListOf<String>()
                if (!warned && result.hasSkipped) {
                    notes.add(
                        getString(
                            R.string.trash_restore_partial,
                            buildSkipDetails(
                                novelCleared = result.novelCleared,
                                fieldValues = result.skippedFieldValues,
                                relationships = result.skippedRelationships,
                                relationshipChanges = result.skippedRelationshipChanges,
                                memberships = result.skippedMemberships,
                                events = result.skippedEvents,
                                relFactions = result.clearedRelationshipFactions,
                                changeEvents = result.clearedChangeEvents
                            )
                        )
                    )
                }
                if (result.relinkedByCode > 0) {
                    notes.add(getString(R.string.trash_restore_relinked, result.relinkedByCode))
                }
                if (result.duplicateRelationships > 0) {
                    notes.add(getString(R.string.trash_restore_duplicate_rel, result.duplicateRelationships))
                }
                if (notes.isNotEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.trash_restore_note_title)
                        .setMessage(notes.joinToString("\n"))
                        .setPositiveButton(R.string.confirm, null)
                        .show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.trash_restore_failed, Toast.LENGTH_SHORT).show()
                }
                logOperation(OpResult.failure(OpResult.CAT_TRASH,
                    getString(R.string.result_trash_restore_failed), e.message))
            }
        }
    }

    private fun confirmPurge(snapshot: TrashSnapshot) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_delete_forever)
            .setMessage(getString(R.string.trash_purge_confirm, snapshot.entityName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        trashRepository.purgeSnapshot(snapshot)
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

    private class TrashAdapter(
        private val onRestore: (TrashSnapshot) -> Unit,
        private val onPurge: (TrashSnapshot) -> Unit
    ) : RecyclerView.Adapter<TrashAdapter.Holder>() {

        private var items: List<TrashSnapshot> = emptyList()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun submit(list: List<TrashSnapshot>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemTrashBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.trashItemName.text = item.entityName
            val typeLabel = when (item.entityType) {
                TrashSnapshot.TYPE_CHARACTER -> holder.binding.root.context.getString(R.string.trash_type_character)
                else -> item.entityType
            }
            holder.binding.trashItemMeta.text = holder.binding.root.context.getString(
                R.string.trash_item_meta, typeLabel, dateFormat.format(Date(item.deletedAt))
            )
            holder.binding.btnRestore.setOnClickListener { onRestore(item) }
            holder.binding.btnPurge.setOnClickListener { onPurge(item) }
        }

        override fun getItemCount() = items.size

        class Holder(val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
