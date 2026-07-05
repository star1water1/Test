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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentAssistantBinding
import com.novelcharacter.app.databinding.ItemAssistantInsightBinding
import com.novelcharacter.app.ui.character.CharacterViewModel
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyError
import com.novelcharacter.app.util.notifySuccess
import com.novelcharacter.app.util.notifyWithAction
import kotlinx.coroutines.launch

/**
 * 창작 어시스턴트 화면 — 정합성 오류·편향 인사이트·건강 제안·넛지를 심각도순 카드로 보여준다.
 * 행위자화(A2): 카드의 기본 버튼으로 그 자리에서 교정(나이 불일치·작품 지정)하고, 부가 대처와
 * '숨기기'는 ⋮ 오버플로에 담아 대처의 수는 늘리되 화면은 어지럽지 않게 한다.
 */
class AssistantFragment : Fragment() {

    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!

    // 부모(HomeFragment) 스코프로 공유 — 탭 배지와 카드 목록이 같은 상태를 관찰한다.
    private val viewModel: AssistantViewModel by viewModels({ requireParentFragment() })

    // 교정(픽스)용 — 캐릭터 변경의 정규 경로(검출/적용)를 재사용한다. 생성은 지연·가벼움.
    private val characterViewModel: CharacterViewModel by viewModels()

    private val adapter = AssistantInsightAdapter(
        onExecute = { _, action -> executeAction(action) },
        onOverflow = { insight, anchor -> showCardOverflow(insight, anchor) }
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
        binding.overflowButton.setOnClickListener { showScreenMenu(it) }

        viewModel.insights.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            if (loading) binding.emptyState.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // 탭이 보일 때마다 최신 데이터로 갱신(다른 화면에서 편집 후 돌아온 경우 반영).
        viewModel.refresh()
    }

    // ===== 액션 디스패치 =====

    private fun executeAction(action: InsightAction) {
        when (action) {
            is InsightAction.Navigate -> {
                val args = action.characterId?.let { bundleOf("characterId" to it) }
                findNavController().navigateSafe(R.id.homeFragment, action.destId, args)
            }
            is InsightAction.Fix -> handleFix(action.kind)
        }
    }

    private fun handleFix(kind: InsightAction.FixKind) {
        when (kind) {
            is InsightAction.FixKind.AgeLinkage -> startAgeFix(kind.characterId)
            is InsightAction.FixKind.AssignNovel -> startAssignNovel(kind.characterId, kind.characterName)
        }
    }

    /** 카드별 ⋮ 오버플로 — 부가 액션 + '숨기기'. 숨기기를 2탭 뒤로 두어 오탭을 막는다. */
    private fun showCardOverflow(insight: AssistantInsight, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        insight.secondaryActions.forEachIndexed { i, action ->
            popup.menu.add(0, i, i, action.label)
        }
        if (insight.dismissible) {
            popup.menu.add(0, MENU_DISMISS, insight.secondaryActions.size, R.string.assistant_dismiss)
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_DISMISS) {
                dismissWithUndo(insight)
            } else {
                insight.secondaryActions.getOrNull(item.itemId)?.let { executeAction(it) }
            }
            true
        }
        popup.show()
    }

    private fun dismissWithUndo(insight: AssistantInsight) {
        viewModel.dismiss(insight)
        notifyWithAction(getString(R.string.assistant_dismissed), getString(R.string.assistant_undo)) {
            viewModel.undismiss(insight)
        }
    }

    // ===== 나이-출생연도 교정 (탭 안에서 완결) =====

    private fun startAgeFix(characterId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val conflict = characterViewModel.detectAgeLinkageConflictForCharacter(characterId)
            if (!isAdded) return@launch
            if (conflict == null) {
                // 스냅샷이 낡아 이미 해결된 경우 — 억지로 교정하지 않고 알리고 갱신.
                notifySuccess(getString(R.string.assistant_age_resolved))
                viewModel.refresh()
                return@launch
            }
            showAgeResolutionDialog(characterId, conflict)
        }
    }

    private fun showAgeResolutionDialog(characterId: Long, conflict: CharacterViewModel.AgeLinkageConflict) {
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
        var selected = 0
        AlertDialog.Builder(requireContext())
            // 제목에 기준연도를 노출해 "무엇이 왜 어긋났는지"가 다이얼로그에서도 분명하게(문제 1).
            .setTitle(getString(R.string.assistant_age_dialog_title, conflict.currentStdYear))
            .setSingleChoiceItems(options, 0) { _, which -> selected = which }
            .setPositiveButton(R.string.apply) { _, _ -> applyAgeFix(characterId, conflict, selected) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyAgeFix(characterId: Long, conflict: CharacterViewModel.AgeLinkageConflict, selected: Int) {
        val choice = when (selected) {
            0 -> CharacterViewModel.AgeResolution.BIRTH_YEAR
            1 -> CharacterViewModel.AgeResolution.AGE
            else -> CharacterViewModel.AgeResolution.STANDARD_YEAR
        }
        viewLifecycleOwner.lifecycleScope.launch {
            characterViewModel.resolveAgeLinkage(characterId, conflict, choice)
            if (!isAdded) return@launch
            notifySuccess(getString(R.string.assistant_age_fixed))
            viewModel.refresh()
        }
    }

    // ===== 작품 지정 (탭 안에서 완결) =====

    private fun startAssignNovel(characterId: Long, characterName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val novels = characterViewModel.getAllNovelsList()
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
                    viewLifecycleOwner.lifecycleScope.launch {
                        val ok = characterViewModel.assignNovel(characterId, novel.id)
                        if (!isAdded) return@launch
                        if (ok) {
                            notifySuccess(getString(R.string.assistant_novel_assigned, characterName, novel.title))
                        } else {
                            // 스냅샷 이후 캐릭터가 삭제된 경우 — 성공으로 오인 통보하지 않는다(변수 제어).
                            notifyError(getString(R.string.assistant_character_gone))
                        }
                        viewModel.refresh()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ===== 화면 메뉴 (새로고침 / 표시할 항목 / 숨긴 항목 복원) =====

    private fun showScreenMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_REFRESH, 0, R.string.assistant_refresh)
            menu.add(0, MENU_CATEGORIES, 1, R.string.assistant_categories)
            menu.add(0, MENU_RESTORE, 2, R.string.assistant_restore_hidden)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_REFRESH -> { viewModel.refresh(); true }
                    MENU_CATEGORIES -> { showCategoryDialog(); true }
                    MENU_RESTORE -> { showRestoreDialog(); true }
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

    private fun showRestoreDialog() {
        val titles = viewModel.dismissedTitles()
        if (titles.isEmpty()) {
            notifySuccess(getString(R.string.assistant_no_hidden))
            return
        }
        val ids = titles.keys.toList()
        val labels = ids.map { titles[it] ?: "" }.toTypedArray<CharSequence>()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.assistant_restore_hidden)
            .setItems(labels) { _, which -> viewModel.restore(ids[which]) }
            .setNegativeButton(R.string.cancel, null)
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
        private const val MENU_RESTORE = 3
        // 카드 오버플로에서 '숨기기'가 부가 액션 인덱스와 겹치지 않도록 큰 값 사용.
        private const val MENU_DISMISS = 10001
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
    private val onExecute: (AssistantInsight, InsightAction) -> Unit,
    private val onOverflow: (AssistantInsight, View) -> Unit
) : ListAdapter<AssistantInsight, AssistantInsightAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAssistantInsightBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding, onExecute, onOverflow)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        private val binding: ItemAssistantInsightBinding,
        private val onExecute: (AssistantInsight, InsightAction) -> Unit,
        private val onOverflow: (AssistantInsight, View) -> Unit
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

            // 기본 액션 = 눈에 띄는 버튼(왼쪽)
            val primary = insight.primaryAction
            if (primary != null) {
                binding.actionButton.visibility = View.VISIBLE
                binding.actionButton.text = primary.label
                binding.actionButton.setOnClickListener { onExecute(insight, primary) }
            } else {
                binding.actionButton.visibility = View.GONE
                binding.actionButton.setOnClickListener(null)
            }

            // ⋮ 오버플로 = 부가 액션 + 숨기기
            val hasOverflow = insight.secondaryActions.isNotEmpty() || insight.dismissible
            binding.overflowButton.visibility = if (hasOverflow) View.VISIBLE else View.GONE
            binding.overflowButton.setOnClickListener(
                if (hasOverflow) View.OnClickListener { onOverflow(insight, binding.overflowButton) } else null
            )
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AssistantInsight>() {
            override fun areItemsTheSame(a: AssistantInsight, b: AssistantInsight) = a.id == b.id
            override fun areContentsTheSame(a: AssistantInsight, b: AssistantInsight) = a == b
        }
    }
}
