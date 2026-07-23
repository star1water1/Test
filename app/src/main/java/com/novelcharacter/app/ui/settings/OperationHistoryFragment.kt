package com.novelcharacter.app.ui.settings

import android.graphics.Color
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.OperationLog
import com.novelcharacter.app.databinding.FragmentOperationHistoryBinding
import com.novelcharacter.app.databinding.ItemOperationLogBinding
import com.novelcharacter.app.util.OpResult
import kotlinx.coroutines.launch

/**
 * 작업 이력 화면 — 데이터 처리 결과(OperationLog)를 시간순으로 보여준다.
 * 변수 제어: 토스트가 사라진 뒤에도 무엇이 처리됐는지 나중에 확인 가능.
 */
class OperationHistoryFragment : Fragment() {

    private var _binding: FragmentOperationHistoryBinding? = null
    private val binding get() = _binding!!

    private val app by lazy { requireActivity().application as NovelCharacterApp }
    private val adapter = OperationLogAdapter { log -> showDetail(log) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOperationHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.toolbar.inflateMenu(R.menu.menu_operation_history)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_history) { confirmClear(); true } else false
        }

        binding.historyRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.historyRecyclerView.adapter = adapter

        app.operationLogRepository.recent.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            binding.emptyText.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showDetail(log: OperationLog) {
        val ctx = context ?: return
        val detail = log.detail
        if (detail.isNullOrBlank()) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(log.summary)
            .setMessage(detail)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmClear() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.operation_history_clear)
            .setMessage(R.string.operation_history_clear_confirm)
            .setPositiveButton(R.string.operation_history_clear) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    app.operationLogRepository.clear()
                    if (isAdded) {
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root, R.string.operation_history_cleared,
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding.historyRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}

private class OperationLogAdapter(
    private val onClick: (OperationLog) -> Unit
) : ListAdapter<OperationLog, OperationLogAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOperationLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        private val binding: ItemOperationLogBinding,
        private val onClick: (OperationLog) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(log: OperationLog) {
            val ctx = binding.root.context
            binding.summaryText.text = log.summary

            val catLabel = categoryLabel(ctx, log.category)
            val relTime = DateUtils.getRelativeTimeSpanString(
                log.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            binding.metaText.text = "$catLabel · $relTime"

            // 성공/실패 색 점 (색만이 아니라 라벨도 함께)
            binding.statusDot.setBackgroundColor(
                if (log.success) Color.parseColor("#66BB6A") else Color.parseColor("#EF5350")
            )

            val hasDetail = !log.detail.isNullOrBlank()
            binding.detailChevron.visibility = if (hasDetail) View.VISIBLE else View.GONE
            binding.root.isClickable = hasDetail
            binding.root.setOnClickListener { if (hasDetail) onClick(log) }
        }

        private fun categoryLabel(ctx: android.content.Context, category: String): String {
            val resId = when (category) {
                OpResult.CAT_CHARACTER -> R.string.op_cat_character
                OpResult.CAT_UNIVERSE -> R.string.op_cat_universe
                OpResult.CAT_NOVEL -> R.string.op_cat_novel
                OpResult.CAT_FACTION -> R.string.op_cat_faction
                OpResult.CAT_FIELD -> R.string.op_cat_field
                OpResult.CAT_RELATIONSHIP -> R.string.op_cat_relationship
                OpResult.CAT_EVENT -> R.string.op_cat_event
                OpResult.CAT_NAMEBANK -> R.string.op_cat_namebank
                OpResult.CAT_PRESET -> R.string.op_cat_preset
                OpResult.CAT_EXCEL -> R.string.op_cat_excel
                OpResult.CAT_BACKUP -> R.string.op_cat_backup
                OpResult.CAT_SHARE -> R.string.op_cat_share
                OpResult.CAT_TRASH -> R.string.op_cat_trash
                OpResult.CAT_MAINTENANCE -> R.string.op_cat_maintenance
                OpResult.CAT_BATCH -> R.string.op_cat_batch
                else -> null
            }
            return if (resId != null) ctx.getString(resId) else category
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<OperationLog>() {
            override fun areItemsTheSame(a: OperationLog, b: OperationLog) = a.id == b.id
            override fun areContentsTheSame(a: OperationLog, b: OperationLog) = a == b
        }
    }
}
