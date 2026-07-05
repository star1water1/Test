package com.novelcharacter.app.ui.assistant

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentAssistantBinding
import com.novelcharacter.app.databinding.ItemAssistantInsightBinding
import com.novelcharacter.app.util.navigateSafe

/**
 * 창작 어시스턴트 화면 — 정합성 오류·편향 인사이트·건강 제안·넛지를 심각도순 카드로 보여준다.
 * HomeFragment의 마지막 탭. ViewModel은 부모(HomeFragment)와 공유하여 스냅샷을 한 번만 로드한다.
 */
class AssistantFragment : Fragment() {

    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!

    // 부모(HomeFragment) 스코프로 공유 — 탭 배지와 카드 목록이 같은 상태를 관찰한다.
    private val viewModel: AssistantViewModel by viewModels({ requireParentFragment() })

    private val adapter = AssistantInsightAdapter(
        onAction = { onAction(it) },
        onDismiss = { viewModel.dismiss(it) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.insightRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.insightRecyclerView.adapter = adapter
        binding.overflowButton.setOnClickListener { showOverflowMenu(it) }

        viewModel.insights.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            // 로딩 중에는 빈 상태 문구를 잠깐이라도 깜빡이지 않게 숨긴다.
            if (loading) binding.emptyState.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // 탭이 보일 때마다 최신 데이터로 갱신(다른 화면에서 편집 후 돌아온 경우 반영).
        viewModel.refresh()
    }

    private fun onAction(insight: AssistantInsight) {
        when (val action = insight.action) {
            is InsightAction.Navigate -> {
                val args = action.characterId?.let { bundleOf("characterId" to it) }
                findNavController().navigateSafe(R.id.homeFragment, action.destId, args)
            }
            null -> {}
        }
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_REFRESH, 0, R.string.assistant_refresh)
            menu.add(0, MENU_CATEGORIES, 1, R.string.assistant_categories)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_REFRESH -> { viewModel.refresh(); true }
                    MENU_CATEGORIES -> { showCategoryDialog(); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showCategoryDialog() {
        val categories = InsightCategory.values()
        val labels = categories.map { getString(categoryLabelRes(it)) }.toTypedArray()
        val checked = categories.map { viewModel.isCategoryEnabled(it) }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.assistant_categories)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                viewModel.setCategoryEnabled(categories[which], isChecked)
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        binding.insightRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MENU_REFRESH = 1
        private const val MENU_CATEGORIES = 2
    }
}

/** 카테고리별 강조색(강조 바·라벨). 기존 상태 점(OperationLog)과 같은 팔레트 톤. */
internal fun categoryColor(category: InsightCategory): Int = when (category) {
    InsightCategory.CONSISTENCY -> Color.parseColor("#EF5350") // 빨강 — 오류
    InsightCategory.HEALTH -> Color.parseColor("#FFA726")      // 주황 — 건강 제안
    InsightCategory.BIAS -> Color.parseColor("#5C6BC0")        // 남보라 — 편향/패턴
    InsightCategory.NUDGE -> Color.parseColor("#66BB6A")       // 초록 — 넛지
}

internal fun categoryLabelRes(category: InsightCategory): Int = when (category) {
    InsightCategory.CONSISTENCY -> R.string.assistant_cat_consistency
    InsightCategory.HEALTH -> R.string.assistant_cat_health
    InsightCategory.BIAS -> R.string.assistant_cat_bias
    InsightCategory.NUDGE -> R.string.assistant_cat_nudge
}

private class AssistantInsightAdapter(
    private val onAction: (AssistantInsight) -> Unit,
    private val onDismiss: (AssistantInsight) -> Unit
) : ListAdapter<AssistantInsight, AssistantInsightAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAssistantInsightBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding, onAction, onDismiss)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        private val binding: ItemAssistantInsightBinding,
        private val onAction: (AssistantInsight) -> Unit,
        private val onDismiss: (AssistantInsight) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(insight: AssistantInsight) {
            val ctx = binding.root.context
            val color = categoryColor(insight.category)

            binding.accentBar.setBackgroundColor(color)
            binding.categoryLabel.text = ctx.getString(categoryLabelRes(insight.category))
            binding.categoryLabel.setTextColor(color)
            binding.countBadge.visibility = View.GONE // 개수는 제목에 포함 — 배지 중복 표시 안 함

            binding.titleText.text = insight.title
            binding.detailText.text = insight.detail

            val action = insight.action
            if (action is InsightAction.Navigate) {
                binding.actionButton.visibility = View.VISIBLE
                binding.actionButton.text = action.label
                binding.actionButton.setOnClickListener { onAction(insight) }
            } else {
                binding.actionButton.visibility = View.GONE
                binding.actionButton.setOnClickListener(null)
            }

            binding.dismissButton.visibility = if (insight.dismissible) View.VISIBLE else View.GONE
            binding.dismissButton.setOnClickListener { onDismiss(insight) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AssistantInsight>() {
            override fun areItemsTheSame(a: AssistantInsight, b: AssistantInsight) = a.id == b.id
            override fun areContentsTheSame(a: AssistantInsight, b: AssistantInsight) = a == b
        }
    }
}
