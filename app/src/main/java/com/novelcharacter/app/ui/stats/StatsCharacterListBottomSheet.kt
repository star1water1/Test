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
 * 차트 조각 드릴다운 시트 — **캐릭터·사건·작품 세 축을 모두** 다룬다([StatsEntityAxis]).
 *
 * 종전에는 캐릭터 전용이었고 사건 필드 카드도 이 시트로 흘러와 항상 0명짜리 빈 목록이 떴다(S-9).
 * 또 대표 fieldDefId 하나만 받아 전체 세계관 보기에서 차트보다 적게 나왔다(S-7) — 이제
 * 카드가 합산한 머지 id 전체를 받는다.
 *
 * 축이 셋이 되면서 판정을 **enum으로 옮겼다**(확-3) — 불리언으로 두면 "사건이 아니면 캐릭터"가
 * 되어 작품 카드가 캐릭터 조회로 흘러가 S-9가 그대로 재현된다.
 */
class StatsCharacterListBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetStatsCharacterListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    private var fieldDefIds: List<Long> = emptyList()
    private var fieldName: String = ""
    private var selectedValue: String = ""
    private var axis: StatsEntityAxis = StatsEntityAxis.CHARACTER
    /** 이 조각의 매칭 규칙 — 화면이 분포를 만든 그 규칙 그대로다(S-16). */
    private var matchSpec: FieldValueMatchSpec = FieldValueMatchSpec.Values(emptySet())
    /** 화면에 보인 조각의 **값 건수**. 목록의 대상 수와 다를 수 있어 그 차이를 고지하는 데 쓴다. */
    private var sliceCount: Int = -1

    var onCharacterClick: ((Long) -> Unit)? = null
    /** 사건 행 탭 — 인자는 연표를 맞출 연도다(사건 상세 화면이 없어 전역 검색과 같은 규약을 쓴다). */
    var onEventClick: ((Int) -> Unit)? = null
    /** 작품 행 탭 — 인자는 그 작품의 세계관 id다(작품 상세 화면이 없어 목록으로 보낸다). */
    var onNovelClick: ((Long) -> Unit)? = null

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
        axis = readAxis(arguments)
        matchSpec = readMatchSpec(arguments, selectedValue)
        sliceCount = arguments?.getInt(ARG_SLICE_COUNT, -1) ?: -1

        binding.titleText.text = getString(R.string.stats_chart_tap_title, fieldName, selectedValue)
        binding.btnSubgroupAnalysis.setText(
            when (axis) {
                StatsEntityAxis.EVENT -> R.string.stats_subgroup_analysis_events
                StatsEntityAxis.NOVEL -> R.string.stats_subgroup_analysis_novels
                StatsEntityAxis.CHARACTER -> R.string.stats_subgroup_analysis
            }
        )

        binding.characterRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        setupObservers()
        setupSubgroupAnalysis()

        when (axis) {
            StatsEntityAxis.EVENT -> viewModel.loadEventsByFieldValue(fieldDefIds, matchSpec)
            StatsEntityAxis.NOVEL -> viewModel.loadNovelsByFieldValue(fieldDefIds, matchSpec)
            StatsEntityAxis.CHARACTER -> viewModel.loadCharactersByFieldValue(fieldDefIds, matchSpec)
        }
    }

    private fun setupObservers() {
        viewModel.chartTapCharacters.observe(viewLifecycleOwner) { characters ->
            if (axis != StatsEntityAxis.CHARACTER || characters == null) return@observe
            binding.countText.text = countText(
                getString(R.string.stats_chart_tap_count, characters.size), characters.size
            )
            binding.characterRecyclerView.adapter = RowAdapter(
                characters.map { Row(it.characterId.toString(), it.characterName, it.fieldValue) }
            ) { key ->
                onCharacterClick?.invoke(key.toLong())
                dismiss()
            }
        }

        viewModel.chartTapEvents.observe(viewLifecycleOwner) { events ->
            if (axis != StatsEntityAxis.EVENT || events == null) return@observe
            binding.countText.text = countText(
                getString(R.string.stats_chart_tap_count_events, events.size), events.size
            )
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

        viewModel.chartTapNovels.observe(viewLifecycleOwner) { novels ->
            if (axis != StatsEntityAxis.NOVEL || novels == null) return@observe
            binding.countText.text = countText(
                getString(R.string.stats_chart_tap_count_novels, novels.size), novels.size
            )
            binding.characterRecyclerView.adapter = RowAdapter(
                // 작품은 세계관 목록으로 보낸다 — 작품 상세 화면이 없으므로 사건 축과 같은 규약이다.
                // 세계관이 없는 작품은 전체 목록으로 보낸다(-1).
                novels.map { Row((it.universeId ?: -1L).toString(), it.title, it.fieldValue) }
            ) { key ->
                onNovelClick?.invoke(key.toLong())
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
            val groups = viewModel.getMergedFieldGroups(axis)
                // 현재 필드 제외 — 대표 id 하나가 아니라 **그룹 전체**를 걸러야 형제 세계관의
                // 같은 필드가 다른 이름인 척 남지 않는다.
                .filter { g -> g.mergedFieldDefIds.none { it in currentIds } }
            if (groups.isEmpty()) {
                // 버튼을 눌렀는데 아무 일도 일어나지 않으면 고장과 구분되지 않는다 — 사유를 알린다.
                // **사유는 사실이어야 한다**: 필드가 없는 것과, 있는데 '통계에 포함'이 꺼진 것은
                // 다르다. 후자에게 "필드를 더 만들면"이라고 말하면 되돌리는 경로를 못 찾는다.
                val excluded = viewModel.statsExcludedFieldGroupCount(axis)
                val message = if (excluded > 0) {
                    getString(R.string.stats_subgroup_all_excluded, excluded)
                } else {
                    getString(R.string.stats_subgroup_no_other_field)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val names = groups.map { it.primary.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.stats_subgroup_select_field)
                .setItems(names) { _, which ->
                    val target = groups[which]
                    // 모수가 아직 없으면(목록 로딩 미완·조회 실패) 고른 것을 조용히 삼키지 않는다 —
                    // 다이얼로그만 닫히면 사용자는 선택이 먹힌 줄 안다.
                    val ids: Set<Long>? = when (axis) {
                        StatsEntityAxis.EVENT -> viewModel.chartTapEvents.value?.map { it.eventId }?.toSet()
                        StatsEntityAxis.NOVEL -> viewModel.chartTapNovels.value?.map { it.novelId }?.toSet()
                        StatsEntityAxis.CHARACTER ->
                            viewModel.chartTapCharacters.value?.map { it.characterId }?.toSet()
                    }
                    if (ids == null) {
                        Toast.makeText(
                            requireContext(), R.string.stats_subgroup_list_not_ready, Toast.LENGTH_LONG
                        ).show()
                        return@setItems
                    }
                    when (axis) {
                        StatsEntityAxis.EVENT -> viewModel.loadEventSubgroupAnalysis(ids, target.mergedFieldDefIds)
                        StatsEntityAxis.NOVEL -> viewModel.loadNovelSubgroupAnalysis(ids, target.mergedFieldDefIds)
                        StatsEntityAxis.CHARACTER -> viewModel.loadSubgroupAnalysis(ids, target.mergedFieldDefIds)
                    }
                }
                .show()
        }
    }

    /**
     * 목록 인원 문구 — 차트 조각의 **값 건수**와 목록의 **대상 수**가 다르면 그 차이를 설명한다.
     *
     * 다중값 필드에서는 한 대상이 조각에 여러 번 기여할 수 있고(접힌 '기타'는 잘린 값 여러 개를
     * 한 대상이 함께 가질 수 있다), 세계관을 합친 카드에서는 한 대상이 형제 def로 두 번 매칭될
     * 수 있다. 조각은 건수를 세고 목록은 대상을 센다 — 어느 쪽도 틀리지 않았으므로, 두 숫자가
     * 어긋날 때 **왜 다른지**를 말한다(말하지 않으면 사용자는 어느 쪽이 맞는지 알 수 없다).
     */
    private fun countText(base: String, listedCount: Int): String =
        if (sliceCount >= 0 && sliceCount != listedCount) {
            base + " " + getString(R.string.stats_chart_tap_count_note, sliceCount)
        } else base

    private fun showSubgroupResult(analysis: SubgroupAnalysis) {
        val container = binding.subgroupContainer
        container.removeAllViews()
        container.visibility = View.VISIBLE

        val ctx = context ?: return

        // 타이틀
        val title = TextView(ctx).apply {
            text = getString(
                when (axis) {
                    StatsEntityAxis.EVENT -> R.string.stats_subgroup_result_title_events
                    StatsEntityAxis.NOVEL -> R.string.stats_subgroup_result_title_novels
                    StatsEntityAxis.CHARACTER -> R.string.stats_subgroup_result_title
                },
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

        // 분모는 **이 그룹의 대상 수**다. 행 라벨이 '명/건'이고 제목이 'N명 기준'이므로,
        // 값 건수를 분모로 쓰면 10명 전원이 가진 값이 33%로 표시되고 행의 합이 모집단을 넘는다.
        // 다중값 필드에서는 비율의 합이 100%를 넘을 수 있다 — 그것이 다중값의 사실이다.
        val totalValues = analysis.totalCount
        for ((value, count) in analysis.distribution) {
            val pct = if (totalValues > 0) count * 100f / totalValues else 0f
            val row = TextView(ctx).apply {
                text = getString(
                    when (axis) {
                        StatsEntityAxis.EVENT -> R.string.stats_subgroup_row_events
                        StatsEntityAxis.NOVEL -> R.string.stats_subgroup_row_novels
                        StatsEntityAxis.CHARACTER -> R.string.stats_subgroup_row_characters
                    },
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

    // ===== 두 줄 목록 행 (캐릭터/사건/작품 공용) =====

    /**
     * [key]는 탭 시 호출부에 넘길 식별자 — 캐릭터는 id, 사건은 연표를 맞출 연도,
     * 작품은 그 작품이 있는 세계관 id다(각 축에 상세 화면이 있는가에 따라 다르다).
     */
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
        private const val ARG_AXIS = "axis"
        private const val ARG_MATCH_VALUES = "matchValues"
        private const val ARG_MATCH_PART_INDEX = "matchPartIndex"
        private const val ARG_MATCH_SEPARATOR = "matchSeparator"
        private const val ARG_MATCH_MIN = "matchMin"
        private const val ARG_MATCH_MAX = "matchMax"
        private const val ARG_MATCH_INCLUSIVE_MAX = "matchInclusiveMax"

        // 여집합 스펙(B-197) — 구간이 **여러 개**라 위 단수 키로는 실을 수 없다.
        // 나란한 세 배열이 구간 목록 하나를 이룬다(길이가 같아야 한다 — 읽는 쪽이 검사한다).
        private const val ARG_MATCH_OUTSIDE_MINS = "matchOutsideMins"
        private const val ARG_MATCH_OUTSIDE_MAXES = "matchOutsideMaxes"
        private const val ARG_MATCH_OUTSIDE_INCLUSIVE = "matchOutsideInclusive"
        private const val ARG_SLICE_COUNT = "sliceCount"

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
            axis: StatsEntityAxis,
            matchSpec: FieldValueMatchSpec = FieldValueMatchSpec.Values(selectedValue),
            sliceCount: Int = -1
        ): StatsCharacterListBottomSheet {
            return StatsCharacterListBottomSheet().apply {
                arguments = Bundle().apply {
                    putLongArray(ARG_FIELD_DEF_IDS, fieldDefIds.toLongArray())
                    putString(ARG_FIELD_NAME, fieldName)
                    putString(ARG_SELECTED_VALUE, selectedValue)
                    putString(ARG_AXIS, axis.name)
                    putMatchSpec(this, matchSpec)
                    putInt(ARG_SLICE_COUNT, sliceCount)
                }
            }
        }

        /** 축은 이름으로 싣는다 — 프로세스 재생성 뒤에도 살아남고, 값이 늘어도 순서에 의존하지 않는다. */
        private fun readAxis(bundle: Bundle?): StatsEntityAxis {
            val name = bundle?.getString(ARG_AXIS) ?: return StatsEntityAxis.CHARACTER
            return StatsEntityAxis.entries.firstOrNull { it.name == name } ?: StatsEntityAxis.CHARACTER
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
                is FieldValueMatchSpec.NumericPartOutsideRanges -> {
                    bundle.putInt(ARG_MATCH_PART_INDEX, spec.partIndex)
                    bundle.putString(ARG_MATCH_SEPARATOR, spec.separator)
                    bundle.putFloatArray(ARG_MATCH_OUTSIDE_MINS, spec.ranges.map { it.min }.toFloatArray())
                    bundle.putFloatArray(ARG_MATCH_OUTSIDE_MAXES, spec.ranges.map { it.max }.toFloatArray())
                    bundle.putBooleanArray(
                        ARG_MATCH_OUTSIDE_INCLUSIVE,
                        spec.ranges.map { it.inclusiveMax }.toBooleanArray()
                    )
                }
            }
        }

        /** 구버전 인자(스펙 없이 값 하나)로 열린 시트도 그대로 동작한다. */
        private fun readMatchSpec(bundle: Bundle?, fallbackValue: String): FieldValueMatchSpec {
            if (bundle == null) return FieldValueMatchSpec.Values(fallbackValue)
            val partIndex = bundle.getInt(ARG_MATCH_PART_INDEX, -1)
            // **여집합을 단수 구간보다 먼저 읽는다** — 둘 다 partIndex를 싣기 때문이다.
            // 순서가 뒤집히면 '구간 밖' 조각이 min/max가 0인 구간으로 읽혀 아무도 못 찾는다.
            val outsideMins = bundle.getFloatArray(ARG_MATCH_OUTSIDE_MINS)
            if (partIndex >= 0 && outsideMins != null) {
                val maxes = bundle.getFloatArray(ARG_MATCH_OUTSIDE_MAXES)
                val inclusive = bundle.getBooleanArray(ARG_MATCH_OUTSIDE_INCLUSIVE)
                // 셋의 길이가 어긋나면 구간을 짝지을 수 없다 — 조용히 일부만 쓰지 않고
                // 값 일치로 되돌린다(그쪽은 라벨로라도 NUMBER·CALCULATED에서는 맞는다).
                if (maxes != null && inclusive != null &&
                    maxes.size == outsideMins.size && inclusive.size == outsideMins.size
                ) {
                    val separator = bundle.getString(ARG_MATCH_SEPARATOR, "-")
                    return FieldValueMatchSpec.outside(
                        outsideMins.indices.map { i ->
                            FieldValueMatchSpec.NumericPartRange(
                                partIndex = partIndex,
                                separator = separator,
                                min = outsideMins[i],
                                max = maxes[i],
                                inclusiveMax = inclusive[i]
                            )
                        },
                        partIndex,
                        separator
                    )
                }
                return FieldValueMatchSpec.Values(fallbackValue)
            }
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
