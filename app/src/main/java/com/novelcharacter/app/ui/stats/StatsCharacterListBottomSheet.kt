package com.novelcharacter.app.ui.stats

import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetStatsCharacterListBinding
import com.novelcharacter.app.util.FieldValueMatchSpec

/**
 * 차트 조각 드릴다운 시트 — **캐릭터/사건 두 축을 모두** 다룬다.
 *
 * 종전에는 캐릭터 전용이었고 사건 필드 카드도 이 시트로 흘러와 항상 0명짜리 빈 목록이 떴다(S-9).
 * 또 대표 fieldDefId 하나만 받아 전체 세계관 보기에서 차트보다 적게 나왔다(S-7) — 이제
 * 카드가 합산한 머지 id 전체를 받는다.
 */
class StatsCharacterListBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetStatsCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    private var fieldDefIds: List<Long> = emptyList()
    private var fieldName: String = ""
    private var selectedValue: String = ""
    private var isEventAxis: Boolean = false
    /** 이 조각의 매칭 규칙 — 화면이 분포를 만든 그 규칙 그대로다(S-16). */
    private var matchSpec: FieldValueMatchSpec = FieldValueMatchSpec.Values(emptySet())

    var onCharacterClick: ((Long) -> Unit)? = null
    /** 사건 행 탭 — 인자는 연표를 맞출 연도다(사건 상세 화면이 없어 전역 검색과 같은 규약을 쓴다). */
    var onEventClick: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetStatsCharacterListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fieldDefIds = arguments?.getLongArray(ARG_FIELD_DEF_IDS)?.toList() ?: emptyList()
        fieldName = arguments?.getString(ARG_FIELD_NAME, "") ?: ""
        selectedValue = arguments?.getString(ARG_SELECTED_VALUE, "") ?: ""
        isEventAxis = arguments?.getBoolean(ARG_IS_EVENT_AXIS, false) ?: false
        matchSpec = readMatchSpec(arguments, selectedValue)

        binding.titleText.text = getString(R.string.stats_chart_tap_title, fieldName, selectedValue)
        binding.btnSubgroupAnalysis.setText(
            if (isEventAxis) R.string.stats_subgroup_analysis_events
            else R.string.stats_subgroup_analysis
        )

        binding.characterRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        setupObservers()
        setupSubgroupAnalysis()

        if (isEventAxis) {
            viewModel.loadEventsByFieldValue(fieldDefIds, matchSpec)
        } else {
            viewModel.loadCharactersByFieldValue(fieldDefIds, matchSpec)
        }
    }

    private fun setupObservers() {
        viewModel.chartTapCharacters.observe(viewLifecycleOwner) { characters ->
            if (isEventAxis || characters == null) return@observe
            binding.countText.text = getString(R.string.stats_chart_tap_count, characters.size)
            binding.characterRecyclerView.adapter = RowAdapter(
                characters.map { Row(it.characterId.toString(), it.characterName, it.fieldValue) }
            ) { key ->
                onCharacterClick?.invoke(key.toLong())
                dismiss()
            }
        }

        viewModel.chartTapEvents.observe(viewLifecycleOwner) { events ->
            if (!isEventAxis || events == null) return@observe
            binding.countText.text = getString(R.string.stats_chart_tap_count_events, events.size)
            binding.characterRecyclerView.adapter = RowAdapter(
                events.map {
                    // 사건은 이름이 없으므로 설명이 제목이고, 부제에 날짜와 값을 함께 싣는다 —
                    // 시트만 보고도 어떤 사건인지 알 수 있어야 한다(원칙 04).
                    Row(
                        it.year.toString(),
                        it.description.ifBlank { it.formattedDate },
                        getString(R.string.stats_event_row_value, it.formattedDate, it.fieldValue)
                    )
                }
            ) { key ->
                onEventClick?.invoke(key.toInt())
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
            // 인사이트 카드와 같은 (key,type) 머지 축으로 고른다 — 머지하지 않으면 전체 세계관
            // 보기에서 같은 필드가 중복 나열되고 한 세계관 값만 집계된다.
            val currentIds = fieldDefIds.toSet()
            val groups = viewModel.getMergedFieldGroups(isEventAxis)
                // 현재 필드 제외 — 대표 id 하나가 아니라 **그룹 전체**를 걸러야 형제 세계관의
                // 같은 필드가 다른 이름인 척 남지 않는다.
                .filter { g -> g.mergedFieldDefIds.none { it in currentIds } }
            if (groups.isEmpty()) {
                // 버튼을 눌렀는데 아무 일도 일어나지 않으면 고장과 구분되지 않는다 — 사유를 알린다.
                Toast.makeText(
                    requireContext(), R.string.stats_subgroup_no_other_field, Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val names = groups.map { it.primary.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.stats_subgroup_select_field)
                .setItems(names) { _, which ->
                    val target = groups[which]
                    // 모수가 아직 없으면(목록 로딩 미완·조회 실패) 고른 것을 조용히 삼키지 않는다 —
                    // 다이얼로그만 닫히면 사용자는 선택이 먹힌 줄 안다.
                    val ids: Set<Long>? = if (isEventAxis) {
                        viewModel.chartTapEvents.value?.map { it.eventId }?.toSet()
                    } else {
                        viewModel.chartTapCharacters.value?.map { it.characterId }?.toSet()
                    }
                    if (ids == null) {
                        Toast.makeText(
                            requireContext(), R.string.stats_subgroup_list_not_ready, Toast.LENGTH_LONG
                        ).show()
                        return@setItems
                    }
                    if (isEventAxis) viewModel.loadEventSubgroupAnalysis(ids, target.mergedFieldDefIds)
                    else viewModel.loadSubgroupAnalysis(ids, target.mergedFieldDefIds)
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
            text = getString(
                if (isEventAxis) R.string.stats_subgroup_result_title_events
                else R.string.stats_subgroup_result_title,
                analysis.targetFieldName, analysis.totalCount
            )
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
                text = getString(
                    if (isEventAxis) R.string.stats_subgroup_row_events
                    else R.string.stats_subgroup_row_characters,
                    value, count, String.format("%.1f", pct)
                )
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

        // R-14: 잘라낸 것은 개수로 존재를 알린다 — 상한 숫자는 상수가 단일 소스다.
        if (analysis.truncatedCount > 0) {
            container.addView(TextView(ctx).apply {
                text = getString(
                    R.string.stats_subgroup_truncated,
                    analysis.truncatedCount, SUBGROUP_DISTRIBUTION_LIMIT
                )
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            })
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

    // ===== 두 줄 목록 행 (캐릭터/사건 공용) =====

    /** [key]는 탭 시 호출부에 넘길 식별자 — 캐릭터는 id, 사건은 연표를 맞출 연도다. */
    private data class Row(val key: String, val title: String, val subtitle: String)

    private class RowAdapter(
        private val rows: List<Row>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

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
            val item = rows[position]
            holder.nameText.text = item.title
            holder.valueText.text = item.subtitle
            holder.itemView.setOnClickListener { onClick(item.key) }
        }

        override fun getItemCount() = rows.size
    }

    companion object {
        const val TAG = "StatsCharacterListBottomSheet"
        private const val ARG_FIELD_DEF_IDS = "fieldDefIds"
        private const val ARG_FIELD_NAME = "fieldName"
        private const val ARG_SELECTED_VALUE = "selectedValue"
        private const val ARG_IS_EVENT_AXIS = "isEventAxis"
        private const val ARG_MATCH_VALUES = "matchValues"
        private const val ARG_MATCH_PART_INDEX = "matchPartIndex"
        private const val ARG_MATCH_SEPARATOR = "matchSeparator"
        private const val ARG_MATCH_MIN = "matchMin"
        private const val ARG_MATCH_MAX = "matchMax"
        private const val ARG_MATCH_INCLUSIVE_MAX = "matchInclusiveMax"

        /**
         * [fieldDefIds]에는 인사이트 카드의 `mergedFieldDefIds`를 그대로 준다 — 첫 원소가
         * 파싱 기준 def이므로 **순서를 보존**해야 차트와 같은 값 공간이 된다.
         *
         * [matchSpec]은 **화면에 보인 그 조각의 규칙**이다(S-16·S-17). 라벨 문자열을 매칭 키로
         * 재사용하면 라벨이 계산 결과인 조각(구간)은 어떤 입력에서도 0명이 되고, 접힌 '기타'
         * 묶음은 아예 조회할 수 없다. [selectedValue]는 제목에만 쓴다.
         */
        fun newInstance(
            fieldDefIds: List<Long>,
            fieldName: String,
            selectedValue: String,
            isEventAxis: Boolean,
            matchSpec: FieldValueMatchSpec = FieldValueMatchSpec.Values(selectedValue)
        ): StatsCharacterListBottomSheet {
            return StatsCharacterListBottomSheet().apply {
                arguments = Bundle().apply {
                    putLongArray(ARG_FIELD_DEF_IDS, fieldDefIds.toLongArray())
                    putString(ARG_FIELD_NAME, fieldName)
                    putString(ARG_SELECTED_VALUE, selectedValue)
                    putBoolean(ARG_IS_EVENT_AXIS, isEventAxis)
                    putMatchSpec(this, matchSpec)
                }
            }
        }

        /** 스펙은 Bundle에 원시값으로 싣는다(Parcelable 도입 없이 프로세스 재생성에도 살아남게). */
        private fun putMatchSpec(bundle: Bundle, spec: FieldValueMatchSpec) {
            when (spec) {
                is FieldValueMatchSpec.Values -> {
                    bundle.putStringArray(ARG_MATCH_VALUES, spec.values.toTypedArray())
                    bundle.putInt(ARG_MATCH_PART_INDEX, -1)
                }
                is FieldValueMatchSpec.NumericPartRange -> {
                    bundle.putInt(ARG_MATCH_PART_INDEX, spec.partIndex)
                    bundle.putString(ARG_MATCH_SEPARATOR, spec.separator)
                    bundle.putFloat(ARG_MATCH_MIN, spec.min)
                    bundle.putFloat(ARG_MATCH_MAX, spec.max)
                    bundle.putBoolean(ARG_MATCH_INCLUSIVE_MAX, spec.inclusiveMax)
                }
            }
        }

        /** 구버전 인자(스펙 없이 값 하나)로 열린 시트도 그대로 동작한다. */
        private fun readMatchSpec(bundle: Bundle?, fallbackValue: String): FieldValueMatchSpec {
            if (bundle == null) return FieldValueMatchSpec.Values(fallbackValue)
            val partIndex = bundle.getInt(ARG_MATCH_PART_INDEX, -1)
            if (partIndex >= 0) {
                return FieldValueMatchSpec.NumericPartRange(
                    partIndex = partIndex,
                    separator = bundle.getString(ARG_MATCH_SEPARATOR, "-"),
                    min = bundle.getFloat(ARG_MATCH_MIN),
                    max = bundle.getFloat(ARG_MATCH_MAX),
                    inclusiveMax = bundle.getBoolean(ARG_MATCH_INCLUSIVE_MAX, false)
                )
            }
            val values = bundle.getStringArray(ARG_MATCH_VALUES)
            return if (values != null && values.isNotEmpty()) FieldValueMatchSpec.Values(values.toSet())
            else FieldValueMatchSpec.Values(fallbackValue)
        }
    }
}
