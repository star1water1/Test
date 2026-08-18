package com.novelcharacter.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.FragmentStatsDataHealthDetailBinding
import com.novelcharacter.app.util.FieldValueFixRoute
import com.novelcharacter.app.util.FieldValueTypeMismatch
import com.novelcharacter.app.util.navigateSafe

class StatsDataHealthDetailFragment : Fragment() {

    private var _binding: FragmentStatsDataHealthDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsDataHealthDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadDataHealthStats()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        // 사유가 있으면 사유를, 없으면 통짜 문구를 — 어느 쪽이든 반드시 띄운다(B-32).
        viewModel.error.observe(viewLifecycleOwner) { error -> showStatsError(viewModel, error) }

        viewModel.dataHealthStats.observe(viewLifecycleOwner) { stats ->
            // 데이터 건강도 개요 도넛 차트
            setupHealthOverviewChart(stats)

            // 타입이 안 맞는 값 (B-156) — 이 화면에서 유일하게 **값 단위**인 항목이다.
            binding.textTypeMismatchCount.text =
                getString(R.string.stats_count_format, stats.typeMismatchedValues.size)
            populateTypeMismatchList(stats.typeMismatchedValues)

            populateIncompleteList(binding.listIncompleteFields, stats.incompleteFieldChars)

            // 그룹별 완성도
            populateGroupCompletionList(stats.fieldCompletionByGroup)

            // 입력 현황 (B-59) — 잘못이 아니라 "아직 안 쓴 칸"이다.
            binding.textNoImageCount.text =
                getString(R.string.stats_person_suffix, stats.inputProgress.noImageChars.size)
            populateSimpleList(binding.listNoImage, stats.inputProgress.noImageChars)
            binding.textNoMemoCount.text =
                getString(R.string.stats_person_suffix, stats.inputProgress.noMemoChars.size)
            populateSimpleList(binding.listNoMemo, stats.inputProgress.noMemoChars)

            // 관계 설명 미작성
            binding.textEmptyRelDesc.text = getString(R.string.stats_count_format, stats.emptyDescRelationships)

            // 시간 정밀도 낮은 사건
            binding.textLowPrecision.text = getString(R.string.stats_count_format, stats.lowPrecisionEvents)

            populateSimpleList(binding.listIsolated, stats.isolatedChars)
            populateSimpleList(binding.listUnlinked, stats.unlinkedChars)
            populateSimpleList(binding.listDuplicateTags, stats.duplicateTags)

            // 별명 미작성
            binding.textNoAnotherNameCount.text =
                getString(R.string.stats_person_suffix, stats.inputProgress.noAnotherNameChars.size)
            populateSimpleList(binding.listNoAnotherName, stats.inputProgress.noAnotherNameChars)

            // 작품 미배정
            binding.textNoNovelCount.text = getString(R.string.stats_person_suffix, stats.noNovelChars.size)
            populateSimpleList(binding.listNoNovel, stats.noNovelChars)
        }
    }

    /**
     * 개요 도넛 — **조각은 전부 캐릭터 수다**(R-13).
     *
     * 종전에는 '이미지 없음'이 첫 조각이었는데 그것은 입력 현황이라 이 차트가 말하는
     * *"무엇이 잘못됐는가"*와 급이 다르다(B-59). 대신 같은 단위인 '작품 미배정'을 세운다.
     *
     * **타입 불일치는 여기 넣지 않는다** — 그것만 **값 수**라, 한 도넛에 섞으면 어느 조각의
     * 백분율도 읽을 수 없게 된다. 그 항목은 아래에 자기 단위로 따로 선다.
     */
    private fun setupHealthOverviewChart(stats: DataHealthStats) {
        val chart = binding.chartDataHealthOverview
        val ctx = requireContext()

        val entries = mutableListOf<PieEntry>()
        val isolated = stats.isolatedChars.size
        val unlinked = stats.unlinkedChars.size
        val noNovel = stats.noNovelChars.size

        if (isolated > 0) entries.add(PieEntry(isolated.toFloat(), getString(R.string.stats_health_isolated)))
        if (unlinked > 0) entries.add(PieEntry(unlinked.toFloat(), getString(R.string.stats_health_event_unlinked)))
        if (noNovel > 0) entries.add(PieEntry(noNovel.toFloat(), getString(R.string.stats_no_novel_assigned)))

        if (entries.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE

        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val dataSet = PieDataSet(entries, "").apply {
            colors = listOf(
                ContextCompat.getColor(ctx, R.color.accent),
                ContextCompat.getColor(ctx, R.color.primary_dark),
                ContextCompat.getColor(ctx, R.color.search_type_novel)
            )
            valueTextSize = captionSize
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }
        chart.apply {
            data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 50f
            setHoleColor(ContextCompat.getColor(ctx, R.color.surface))
            setTransparentCircleColor(ContextCompat.getColor(ctx, R.color.surface))
            setUsePercentValues(true)
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(chartValueSize)
            animateY(600)
            invalidate()
        }
    }

    /**
     * 타입이 안 맞는 값 목록 — **바로잡을 경로가 붙는 유일한 목록이다**(B-156).
     *
     * 개발 의도 2번은 *검증 → 알림 → 바로잡을 경로*를 요구하는데, 이 부류는 종전에 둘째에서
     * 끊겨 있었다(전파 미리보기의 고지는 창을 닫으면 사라진다). 그래서 여기서는 이름만
     * 늘어놓지 않고 **누르면 그 칸을 고칠 자리가 열린다.**
     *
     * **세 축이 전부 눌린다**(B-198 — 종전에는 캐릭터뿐이었다). 어디로 가고 무엇을 싣는지는
     * [FieldValueFixRoute]가 정한다 — 이 화면은 목적지 enum을 자원 id로 옮기기만 한다.
     * 사건·작품은 목적지가 곧 편집 창이라 **그 칸까지 잡아 준다**(캐릭터는 상세 화면이라
     * 잡을 칸이 없다. 그 갈래도 같은 객체가 든다).
     */
    private fun populateTypeMismatchList(items: List<TypeMismatchedValue>) {
        val container = binding.listTypeMismatch
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        // 상한은 축마다다 — 통짜로 자르면 캐릭터 값 무더기가 사건·작품 축을 통째로 가린다(R-14).
        val view = TypeMismatchList.view(items)
        var lastOwnerType: String? = null
        fun addOverflowLine(ownerType: String?) {
            val over = ownerType?.let { view.hiddenByOwnerType[it] } ?: return
            container.addView(makeCaptionTextView(getString(R.string.stats_type_mismatch_more, over)))
        }
        view.shown.forEach { item ->
            if (lastOwnerType != null && lastOwnerType != item.ownerType) addOverflowLine(lastOwnerType)
            lastOwnerType = item.ownerType
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            val ownerLabel = when (item.ownerType) {
                FieldDefinition.ENTITY_EVENT ->
                    "[${getString(R.string.stats_type_mismatch_owner_event)}] ${item.ownerName}"
                FieldDefinition.ENTITY_NOVEL ->
                    "[${getString(R.string.stats_type_mismatch_owner_novel)}] ${item.ownerName}"
                else -> item.ownerName
            }
            row.addView(
                makeTextView(
                    getString(R.string.stats_type_mismatch_row, ownerLabel, item.fieldName, item.value)
                )
            )
            row.addView(makeCaptionTextView(reasonText(item.reason)))
            // 갈 곳이 없는 줄(모르는 종류·임자를 잃은 값)은 그대로 둔다 — 누를 수 있는 줄과
            // 없는 줄이 한 목록에 섞이므로 **보여서 알 수 있어야 한다**(원칙 04).
            val route = FieldValueFixRoute.of(
                item.ownerType, item.ownerId, item.fieldDefId, item.fieldName
            )
            if (route != null) {
                row.isClickable = true
                row.isFocusable = true
                val ta = requireContext().obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                )
                row.foreground = ta.getDrawable(0)
                ta.recycle()
                row.setOnClickListener { navigateToFix(route) }
            }
            container.addView(row)
        }
        // 마지막 축의 잔여 — 루프 안에서는 '다음 축'이 없어 붙일 자리가 없다.
        addOverflowLine(lastOwnerType)
    }

    /**
     * 목적지 enum → 자원 id. **이 함수가 이 화면이 아는 전부다** — 어느 종류가 어디로 가고
     * 무엇을 싣는지는 [FieldValueFixRoute]가 정한다(순수 시험이 그 갈래를 잠근다).
     */
    private fun navigateToFix(route: FieldValueFixRoute.Route) {
        val destId = when (route.destination) {
            FieldValueFixRoute.Destination.CHARACTER_DETAIL -> R.id.characterDetailFragment
            FieldValueFixRoute.Destination.TIMELINE -> R.id.timelineFragment
            FieldValueFixRoute.Destination.NOVEL_LIST -> R.id.novelListFragment
        }
        val bundle = Bundle().apply {
            putLong(route.ownerArg, route.ownerId)
            // 잡을 칸이 없는 축에는 싣지 않는다 — 받는 쪽이 없는 인자는 조용히 버려진다.
            if (route.fieldId > 0L) {
                putLong(FieldValueFixRoute.ARG_FOCUS_FIELD_ID, route.fieldId)
                putString(FieldValueFixRoute.ARG_FOCUS_FIELD_NAME, route.fieldName)
            }
        }
        findNavController().navigateSafe(R.id.statsDataHealthDetailFragment, destId, bundle)
    }

    private fun reasonText(reason: FieldValueTypeMismatch.Reason): String = getString(
        when (reason) {
            FieldValueTypeMismatch.Reason.NOT_A_NUMBER -> R.string.stats_type_mismatch_reason_number
            FieldValueTypeMismatch.Reason.UNKNOWN_GRADE -> R.string.stats_type_mismatch_reason_grade
            FieldValueTypeMismatch.Reason.NOT_MEASUREMENTS -> R.string.stats_type_mismatch_reason_body
        }
    )

    private fun populateGroupCompletionList(data: Map<String, Float>) {
        val container = binding.listGroupCompletion
        container.removeAllViews()
        if (data.isEmpty()) {
            container.addView(makeEmptyTextView())
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        data.entries.sortedBy { it.value }.forEach { (group, rate) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            row.addView(makeTextView("$group  ${String.format("%.0f", rate)}%"))
            val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    progressHeight
                )
                max = 100
                this.progress = rate.toInt().coerceIn(0, 100)
            }
            row.addView(progress)
            container.addView(row)
        }
    }

    /**
     * 이 화면의 명단은 **전부 캐릭터 축**이다(이미지·메모·별명 미작성 · 고립 · 사건 미연계 ·
     * 작품 미배정 · 완성도 미달). 상한 없이 그리면 ×30에서 한 목록이 6,420개의 뷰가 되므로
     * 묶음 단위로 접는다 — 세는 법과 접는 법은 [StatsCappedList] 하나가 든다(B-199).
     */
    private fun populateSimpleList(container: LinearLayout, items: List<String>) {
        StatsCappedList.populate(
            container = container,
            items = items,
            emptyView = { makeEmptyTextView() },
            toggleView = { makeToggleTextView(it) },
            moreText = { getString(R.string.stats_show_more, it) },
            lessText = { getString(R.string.stats_show_less) },
            makeRow = { name -> makeTextView(name) }
        )
    }

    private fun populateIncompleteList(container: LinearLayout, items: List<Pair<String, Float>>) {
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        // 정렬은 접기 **전에** 한 번 한다 — 접힌 뒤에 정렬하면 남는 것이 '가장 덜 채운 쪽'이
        // 아니게 되고, 그러면 이 목록이 말하는 것 자체가 달라진다.
        StatsCappedList.populate(
            container = container,
            items = items.sortedBy { it.second },
            emptyView = { makeEmptyTextView() },
            toggleView = { makeToggleTextView(it) },
            moreText = { getString(R.string.stats_show_more, it) },
            lessText = { getString(R.string.stats_show_less) },
            makeRow = { (name, rate) ->
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = marginSm
                    layoutParams = lp
                    addView(makeTextView("$name  ${String.format("%.0f", rate)}%"))
                    addView(
                        ProgressBar(
                            requireContext(),
                            null,
                            android.R.attr.progressBarStyleHorizontal
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                progressHeight
                            )
                            max = 100
                            this.progress = rate.toInt().coerceIn(0, 100)
                        }
                    )
                }
            }
        )
    }

    /** 접기/펼치기 줄 — 값 줄과 구별되게 강조색으로 둔다(누를 수 있다는 것이 보여야 한다). */
    private fun makeToggleTextView(label: CharSequence): TextView {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        return TextView(requireContext()).apply {
            text = label
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
            setPadding(0, marginSm, 0, marginSm)
        }
    }

    private fun makeEmptyTextView(): TextView = makeTextView(getString(R.string.stats_no_data))

    /** 사유 줄 — 값 줄보다 작고 흐리게 둬서 *무엇이 틀렸는지*와 *왜*가 한눈에 갈린다. */
    private fun makeCaptionTextView(text: String): TextView {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        return TextView(requireContext()).apply {
            this.text = text
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun makeTextView(text: String): TextView {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        val marginXs = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)
        return TextView(requireContext()).apply {
            this.text = text
            textSize = textSizeSp
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = marginXs
            layoutParams = lp
        }
    }

    override fun onDestroyView() {
        _binding?.chartDataHealthOverview?.clear()
        super.onDestroyView()
        _binding = null
    }
}
