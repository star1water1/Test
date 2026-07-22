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

    private fun confirmRestore(snapshot: TrashSnapshot) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_restore)
            .setMessage(getString(R.string.trash_restore_confirm, snapshot.entityName))
            .setPositiveButton(R.string.confirm) { _, _ -> restore(snapshot) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restore(snapshot: TrashSnapshot) {
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
                // 참조 소실로 생략된 연관 데이터가 있으면 조용히 넘기지 않고 알린다 (변수 제어)
                if (result.hasSkipped) {
                    val details = mutableListOf<String>()
                    if (result.novelCleared) details.add(getString(R.string.trash_skip_novel))
                    if (result.skippedFieldValues > 0) details.add(getString(R.string.trash_skip_fields, result.skippedFieldValues))
                    if (result.skippedRelationships > 0) details.add(getString(R.string.trash_skip_relationships, result.skippedRelationships))
                    if (result.skippedMemberships > 0) details.add(getString(R.string.trash_skip_memberships, result.skippedMemberships))
                    if (result.skippedEvents > 0) details.add(getString(R.string.trash_skip_events, result.skippedEvents))
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.trash_restore_partial_title)
                        .setMessage(getString(R.string.trash_restore_partial, details.joinToString("\n")))
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
