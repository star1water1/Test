package com.novelcharacter.app.ui.field

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DefaultFieldTemplate
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.databinding.FragmentDefaultFieldManageBinding
import com.novelcharacter.app.databinding.ItemDefaultFieldTemplateBinding
import com.novelcharacter.app.util.DefaultFieldPlan
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.notifyResult
import kotlinx.coroutines.launch

/**
 * **기본 필드 관리 화면** (B-119 — 설계 1-4).
 *
 * 목록(이름·타입·대상·*"세계관 N곳 연결 · M곳 다름"*) / 새로 만들기 / 전파 / 다시 심기 / 해제.
 *
 * ## 이 화면이 필드 편집의 스위치와 다른 점
 *
 * 스위치는 *"이 필드를 전역 기본으로"* 하나만 한다. 여기는 **그 뒤에 필요한 것들**을 든다 —
 * 특히 **'다시 심기'**가 그렇다. 세계관을 만드는 길 중 셋(월드패키지·엑셀·휴지통 복원)은
 * 자기 필드를 곧바로 넣느라 심기를 지나치지 않으므로, 그 세계관들을 따라잡는 단추가 필요하다
 * ([com.novelcharacter.app.data.repository.DefaultFieldTemplateRepository.plantIntoUniverse] 참조).
 */
class DefaultFieldManageFragment : Fragment() {

    private var _binding: FragmentDefaultFieldManageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DefaultFieldViewModel by viewModels()

    private lateinit var adapter: TemplateAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDefaultFieldManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        adapter = TemplateAdapter(
            onClick = { showTemplateEditDialog(it) },
            onMenuClick = { template, anchor -> showTemplateMenu(template, anchor) }
        )
        binding.templateRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.templateRecyclerView.adapter = adapter

        binding.fabAddTemplate.setOnClickListener { showTemplateEditDialog(null) }

        viewModel.templates.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            viewModel.refreshCounts(list)
        }
        viewModel.linkCounts.observe(viewLifecycleOwner) { counts ->
            adapter.setCounts(counts)
        }
        // 데이터 처리 결과 알림 — 필드 관리 화면과 같은 관례(성공/실패 즉시 통보 + 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // 편집 — 필드 편집 다이얼로그를 그대로 쓴다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 템플릿을 [FieldEditDialog]로 편집한다 — **정의의 모양이 같기 때문에 폼도 같아야 한다**
     * (설계 1-4: *"새로 만들기(FieldEditDialog 재사용, 등급 체계 참조 없음)"*).
     *
     * `universeId = 0`으로 여는 것이 그 *"등급 체계 참조 없음"*의 실행이다 — 그 값이면
     * 다이얼로그가 세계관 DB 조회를 건너뛰므로 체계 목록이 뜨지 않고, 전역 템플릿이 가리킬 수
     * 없는 참조를 애초에 고를 수 없다.
     */
    private fun showTemplateEditDialog(template: DefaultFieldTemplate?) {
        val asField = template?.let {
            FieldDefinition(
                id = 0, universeId = 0, key = it.key, name = it.name, type = it.type,
                config = it.config, groupName = it.groupName, displayOrder = it.displayOrder,
                isRequired = it.isRequired, entityType = it.entityType
            )
        }
        val dialog = FieldEditDialog.newInstance(
            universeId = 0,
            field = asField,
            entityType = template?.entityType ?: FieldDefinition.ENTITY_CHARACTER
        )
        dialog.setOnSaveListener { edited ->
            if (template == null) {
                viewModel.createFromField(edited)
            } else {
                // 정의만 고친다 — **퍼뜨리지 않는다**(명시적 전파). 고친 직후 전파 미리보기를
                // 열어 주되, `previous`로 **고치기 전 템플릿**을 넘긴다. 그래야 *아직 못 받은
                // 세계관*과 *그 세계관에서 손댄 것*이 갈려 기본 선택이 정확해진다.
                val next = template.copy(
                    key = edited.key, name = edited.name, type = edited.type,
                    config = com.novelcharacter.app.data.model.DefaultFieldRef.remove(edited.config),
                    groupName = edited.groupName, isRequired = edited.isRequired,
                    entityType = edited.entityType
                )
                viewModel.saveTemplate(next)
                showPropagateDialog(next, previous = template)
            }
            true
        }
        dialog.show(childFragmentManager, "DefaultFieldEditDialog")
    }

    private fun showTemplateMenu(template: DefaultFieldTemplate, anchor: View) {
        val actions = listOf(
            getString(R.string.default_field_action_propagate),
            getString(R.string.default_field_action_replant),
            getString(R.string.default_field_action_unlink)
        )
        android.widget.PopupMenu(requireContext(), anchor).apply {
            actions.forEachIndexed { i, label -> menu.add(0, i, i, label) }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> showPropagateDialog(template, previous = null)
                    1 -> viewModel.replant(template)
                    2 -> confirmUnlink(template)
                }
                true
            }
            show()
        }
    }

    private fun confirmUnlink(template: DefaultFieldTemplate) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.default_field_unlink_confirm_title)
            .setMessage(R.string.default_field_unlink_confirm)
            .setPositiveButton(R.string.default_field_action_unlink) { _, _ -> viewModel.unlink(template) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 전파 미리보기
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 세계관별 상태를 보이고 **고른 곳만** 덮는다 (설계 1-3).
     *
     * 기본 체크는 [DefaultFieldPlan.PropagatePlan.defaultSelection]이 정한다 —
     * *아직 못 받은 곳*만 켜고 *그 세계관에서 고친 곳*은 끈다. '모두 선택'을 함께 두는 것은
     * 흔한 조작(*"고쳤으니 전부 밀어라"*)을 한 번의 탭으로 만들기 위해서다(원칙 04).
     */
    private fun showPropagateDialog(
        template: DefaultFieldTemplate,
        previous: DefaultFieldTemplate?
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val plan = try {
                viewModel.buildPropagatePlan(template, previous)
            } catch (e: Exception) {
                android.util.Log.e("DefaultFieldManage", "Failed to build propagate plan", e)
                return@launch
            }
            if (!isAdded) return@launch
            if (plan.isNoop) {
                android.widget.Toast.makeText(
                    requireContext(), R.string.default_field_propagate_none,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            container.addView(TextView(requireContext()).apply {
                setText(R.string.default_field_propagate_purpose)
                setTextAppearance(R.style.TextAppearance_App_Caption)
            })

            val selected = plan.defaultSelection().toMutableSet()
            val boxes = LinkedHashMap<Long, CheckBox>()
            for (item in plan.actionable) {
                val box = CheckBox(requireContext()).apply {
                    text = buildString {
                        append(item.universeName)
                        append("  ")
                        append(
                            if (item.divergence == DefaultFieldPlan.Divergence.OUTDATED)
                                getString(R.string.default_field_propagate_outdated)
                            else getString(R.string.default_field_propagate_diverged)
                        )
                    }
                    isChecked = item.universeId in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(item.universeId) else selected.remove(item.universeId)
                    }
                }
                container.addView(box)
                boxes[item.universeId] = box
                // 타입이 바뀌면 **값이 몇 개 초기화되는지** 그 세계관 줄에 붙인다 — 세는 규칙은
                // 저장 경로와 같은 FieldTypeCompatibility다(미리보기가 거짓을 말할 자리가 없다).
                if (item.typeChanges && item.incompatibleValues > 0) {
                    container.addView(TextView(requireContext()).apply {
                        text = getString(R.string.default_field_propagate_type_warning, item.incompatibleValues)
                        setTextAppearance(R.style.TextAppearance_App_Caption)
                        setPadding(dp(32), 0, 0, dp(4))
                    })
                }
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.default_field_propagate_title)
                // 세계관이 많으면 목록이 길어진다 — 다이얼로그 본문에 높이 상한을 건다(R-31).
                .setView(cappedScrollView(requireContext()).apply { addView(container) })
                .setPositiveButton(R.string.save) { _, _ ->
                    viewModel.applyPropagate(template, plan, selected.toSet())
                }
                .setNeutralButton(R.string.default_field_propagate_select_all, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
                .also { dialog ->
                    // '모두 선택'은 **닫지 않는다** — 눌러서 켜고 눈으로 확인한 뒤 저장하는 것이
                    // 이 단추의 쓰임이다(기본 동작은 누르는 즉시 닫힌다).
                    dialog.setOnShowListener {
                        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            boxes.values.forEach { it.isChecked = true }
                        }
                    }
                }
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────

    private class TemplateAdapter(
        val onClick: (DefaultFieldTemplate) -> Unit,
        val onMenuClick: (DefaultFieldTemplate, View) -> Unit
    ) : ListAdapter<DefaultFieldTemplate, TemplateAdapter.VH>(DIFF) {

        private var counts: Map<String, Pair<Int, Int>> = emptyMap()

        fun setCounts(next: Map<String, Pair<Int, Int>>) {
            counts = next
            notifyItemRangeChanged(0, itemCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemDefaultFieldTemplateBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(private val binding: ItemDefaultFieldTemplateBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(template: DefaultFieldTemplate) {
                binding.templateName.text = template.name
                binding.templateKey.text = template.key
                binding.templateTypeBadge.text =
                    try { FieldType.valueOf(template.type).label } catch (_: Exception) { template.type }
                val ctx = binding.root.context
                // 화면 어휘는 필드 관리 화면과 같은 문자열을 쓴다 — 엑셀 시트의 어휘
                // (`FieldValueSheetMapper`)는 파일 안에서 쓰는 말이라 화면과 갈린다(R-7).
                binding.templateEntityBadge.text = ctx.getString(
                    when (template.entityType) {
                        FieldDefinition.ENTITY_EVENT -> R.string.field_target_event
                        FieldDefinition.ENTITY_NOVEL -> R.string.field_target_novel
                        else -> R.string.field_target_character
                    }
                )

                val pair = counts[template.code]
                binding.templateLinkSummary.text = when {
                    pair == null -> ""
                    pair.first == 0 -> ctx.getString(R.string.default_field_linked_none)
                    else -> ctx.getString(R.string.default_field_linked_summary, pair.first, pair.second)
                }

                binding.root.setOnClickListener { onClick(template) }
                binding.btnTemplateMenu.setOnClickListener { onMenuClick(template, it) }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<DefaultFieldTemplate>() {
                override fun areItemsTheSame(a: DefaultFieldTemplate, b: DefaultFieldTemplate) = a.id == b.id
                override fun areContentsTheSame(a: DefaultFieldTemplate, b: DefaultFieldTemplate) = a == b
            }
        }
    }
}
