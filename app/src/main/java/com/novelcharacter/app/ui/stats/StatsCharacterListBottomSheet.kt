package com.novelcharacter.app.ui.stats

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.BottomSheetStatsCharacterListBinding

class StatsCharacterListBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetStatsCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    private var fieldDefId: Long = 0
    private var fieldName: String = ""
    private var selectedValue: String = ""

    var onCharacterClick: ((Long) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetStatsCharacterListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fieldDefId = arguments?.getLong(ARG_FIELD_DEF_ID, 0) ?: 0
        fieldName = arguments?.getString(ARG_FIELD_NAME, "") ?: ""
        selectedValue = arguments?.getString(ARG_SELECTED_VALUE, "") ?: ""

        binding.titleText.text = getString(R.string.stats_chart_tap_title, fieldName, selectedValue)

        binding.characterRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        setupObservers()
        setupSubgroupAnalysis()

        viewModel.loadCharactersByFieldValue(fieldDefId, selectedValue)
    }

    private fun setupObservers() {
        viewModel.chartTapCharacters.observe(viewLifecycleOwner) { characters ->
            if (characters == null) return@observe
            binding.countText.text = getString(R.string.stats_chart_tap_count, characters.size)
            binding.characterRecyclerView.adapter = CharacterListAdapter(characters) { charId ->
                onCharacterClick?.invoke(charId)
                dismiss()
            }
        }

        viewModel.subgroupAnalysis.observe(viewLifecycleOwner) { analysis ->
            if (analysis == null) return@observe
            showSubgroupResult(analysis)
        }
    }

    private fun setupSubgroupAnalysis() {
        binding.btnSubgroupAnalysis.setOnClickListener {
            val fieldDefs = viewModel.getFieldDefinitions()
                .filter { it.id != fieldDefId } // 현재 필드 제외
            if (fieldDefs.isEmpty()) return@setOnClickListener

            val names = fieldDefs.map { it.name }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.stats_subgroup_select_field)
                .setItems(names) { _, which ->
                    val targetField = fieldDefs[which]
                    val charIds = viewModel.chartTapCharacters.value
                        ?.map { it.characterId }?.toSet() ?: return@setItems
                    viewModel.loadSubgroupAnalysis(charIds, targetField.id)
                }
                .show()
        }
    }

    private fun showSubgroupResult(analysis: SubgroupAnalysis) {
        val container = binding.subgroupContainer
        container.removeAllViews()
        container.visibility = View.VISIBLE

        val ctx = context ?: return

        // 타이틀
        val title = TextView(ctx).apply {
            text = getString(R.string.stats_subgroup_result_title, analysis.targetFieldName, analysis.totalCount)
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            layoutParams = lp
        }
        container.addView(title)

        // 분포 표시
        val totalValues = analysis.distribution.values.sum()
        for ((value, count) in analysis.distribution) {
            val pct = if (totalValues > 0) count * 100f / totalValues else 0f
            val row = TextView(ctx).apply {
                text = "$value: ${count}명 (${String.format("%.1f", pct)}%)"
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * resources.displayMetrics.density).toInt() }
                layoutParams = lp
            }
            container.addView(row)
        }

        if (analysis.distribution.isEmpty()) {
            val empty = TextView(ctx).apply {
                text = getString(R.string.stats_no_data)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            container.addView(empty)
        }
    }

    override fun onDestroyView() {
        viewModel.clearChartTapData()
        super.onDestroyView()
        _binding = null
    }

    // ===== 간단한 캐릭터 리스트 어댑터 =====

    private class CharacterListAdapter(
        private val characters: List<FieldValueCharacter>,
        private val onClick: (Long) -> Unit
    ) : RecyclerView.Adapter<CharacterListAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(android.R.id.text1)
            val valueText: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, (8 * resources.displayMetrics.density).toInt())
                isClickable = true
                isFocusable = true
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = context.obtainStyledAttributes(attrs)
                foreground = ta.getDrawable(0)
                ta.recycle()
            }

            val name = TextView(parent.context).apply {
                id = android.R.id.text1
                textSize = 15f
                setTextColor(ContextCompat.getColor(parent.context, R.color.on_surface))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val value = TextView(parent.context).apply {
                id = android.R.id.text2
                textSize = 12f
                setTextColor(ContextCompat.getColor(parent.context, R.color.text_secondary))
            }
            layout.addView(name)
            layout.addView(value)
            return VH(layout)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = characters[position]
            holder.nameText.text = item.characterName
            holder.valueText.text = item.fieldValue
            holder.itemView.setOnClickListener { onClick(item.characterId) }
        }

        override fun getItemCount() = characters.size
    }

    companion object {
        const val TAG = "StatsCharacterListBottomSheet"
        private const val ARG_FIELD_DEF_ID = "fieldDefId"
        private const val ARG_FIELD_NAME = "fieldName"
        private const val ARG_SELECTED_VALUE = "selectedValue"

        fun newInstance(fieldDefId: Long, fieldName: String, selectedValue: String): StatsCharacterListBottomSheet {
            return StatsCharacterListBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_FIELD_DEF_ID, fieldDefId)
                    putString(ARG_FIELD_NAME, fieldName)
                    putString(ARG_SELECTED_VALUE, selectedValue)
                }
            }
        }
    }
}
