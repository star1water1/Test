package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.ui.theme.ChartTheme
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsNameBankDetailBinding
import com.novelcharacter.app.util.navigateSafe

class StatsNameBankDetailFragment : Fragment() {

    private var _binding: FragmentStatsNameBankDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsNameBankDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadNameBankStats()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        // 사유가 있으면 사유를, 없으면 통짜 문구를 — 어느 쪽이든 반드시 띄운다(B-32).
        viewModel.error.observe(viewLifecycleOwner) { error -> showStatsError(viewModel, error) }

        viewModel.nameBankStats.observe(viewLifecycleOwner) { stats ->
            binding.textUsageRate.text = "${String.format("%.0f", stats.usageRate)}%"
            binding.progressUsageRate.progress = stats.usageRate.toInt().coerceIn(0, 100)
            binding.textAvgNameLength.text = getString(R.string.stats_avg_name_length, stats.avgNameLength)

            setupGenderPieChart(stats.genderDistribution)
            setupOriginBarChart(stats.originDistribution)
            setupNameLengthChart(stats.nameLengthDistribution)
            setupFirstCharChart(stats.firstCharDistribution)
            populateUnusedNames(stats.unusedNames)
        }
    }

    private fun setupNameLengthChart(data: Map<Int, Int>) {
        val chart = binding.chartNameLength
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val labels = data.keys.map { getString(R.string.stats_chars_unit, it) }
        val entries = data.values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(ctx, R.color.primary)
            valueTextSize = chartValueSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    /** 첫 글자 분포 막대의 표시 상한 — 문구는 이 상수에서 채운다(R-14). */
    private val FIRST_CHAR_CHART_LIMIT = 15

    private fun setupFirstCharChart(data: Map<String, Int>) {
        val chart = binding.chartFirstChar
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        // **자른 종수를 말한다** (R-14 — *"상한을 두는 것만으로는 절반이다"*).
        // 종전에는 `data.entries.take(15)` 뒤에 아무 고지가 없어, 성씨가 40종이어도
        // 15개 막대가 전부인 것처럼 보였다 — *없는 성씨*라 판단해 새 이름을 짓게 되는 자리다.
        // 같은 화면의 미사용 이름 목록은 이미 `stats_and_more`로 잘린 수를 말한다(한 화면
        // 안에서 같은 성격의 두 목록이 반대로 돌던 것을 맞춘다).
        // 막대는 '기타'로 접지 않는다 — 첫 글자 분포에서 '기타' 막대는 글자가 아니라
        // 묶음이라 x축의 뜻이 갈린다. 접는 대신 **밖에서 말한다.**
        val view = com.novelcharacter.app.util.ValueDistributions.view(data, FIRST_CHAR_CHART_LIMIT)
        binding.firstCharTruncNote.apply {
            if (view.hasHidden) {
                text = getString(
                    R.string.stats_chart_top_kinds_note,
                    view.shown.size, view.hiddenKinds, view.hiddenCount
                )
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        val labels = view.shown.map { it.label }
        val entries = view.shown.mapIndexed { i, e -> BarEntry(i.toFloat(), e.count.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = chartValueSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    /**
     * 미사용 이름 목록 — **표시 전용이던 자리에 행동 하나를 뒀다** (B-124 ⓒ).
     *
     * 종전에는 여기서 미사용 이름을 알아도 할 수 있는 일이 없어, 사용자가 은행 화면을
     * 손으로 다시 찾아가야 했다(원칙 04의 마찰). 이름을 탭하면 은행으로 간다.
     */
    private fun populateUnusedNames(names: List<String>) {
        val container = binding.listUnusedNames
        container.removeAllViews()
        if (names.isEmpty()) {
            container.addView(makeTextView(getString(R.string.stats_all_names_used)))
            return
        }
        names.take(30).forEach { name ->
            container.addView(makeTextView(name, clickable = true))
        }
        if (names.size > 30) {
            container.addView(makeTextView(getString(R.string.stats_and_more, names.size - 30)))
        }
        container.addView(makeTextView(getString(R.string.stats_name_bank_open), clickable = true))
    }

    private fun openNameBank() {
        findNavController().navigateSafe(R.id.statsNameBankDetailFragment, R.id.nameBankFragment, null)
    }

    private fun setupGenderPieChart(data: Map<String, Int>) {
        val chart = binding.chartGenderDist
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val captionSize = resources.getDimension(R.dimen.stats_text_caption) / resources.displayMetrics.scaledDensity
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val entries = data.entries.map { PieEntry(it.value.toFloat(), it.key) }
        val dataSet = PieDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = captionSize
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }
        chart.apply {
            this.data = PieData(dataSet)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 40f
            applyDarkModeHole(this)
            setUsePercentValues(true)
            legend.isEnabled = true
            legend.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(chartValueSize)
            animateY(600)
            invalidate()
        }
    }

    private fun setupOriginBarChart(data: Map<String, Int>) {
        val chart = binding.chartOriginDist
        if (data.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val labels = data.keys.toList()
        val entries = data.values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = chartValueSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
        }
        chart.apply {
            this.data = BarData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisLeft.textColor = ContextCompat.getColor(ctx, R.color.on_surface)
            axisRight.isEnabled = false
            setFitBars(true)
            animateY(600)
            invalidate()
        }
    }

    private fun makeTextView(text: String, clickable: Boolean = false): TextView {
        val textSizeSp = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        val marginXs = resources.getDimensionPixelSize(R.dimen.stats_margin_xs)
        return TextView(requireContext()).apply {
            this.text = text
            textSize = textSizeSp
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (clickable) R.color.primary_dark else R.color.on_surface
                )
            )
            if (clickable) {
                setPadding(0, marginXs, 0, marginXs)
                setOnClickListener { openNameBank() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = marginXs
            layoutParams = lp
        }
    }

    private fun applyDarkModeHole(chart: PieChart) {
        chart.setHoleColor(ContextCompat.getColor(requireContext(), R.color.surface))
        chart.setTransparentCircleColor(ContextCompat.getColor(requireContext(), R.color.surface))
    }

    private fun chartColors(): List<Int> =
        ChartTheme.palette(requireContext())

    override fun onDestroyView() {
        _binding?.let {
            it.chartGenderDist.clear()
            it.chartOriginDist.clear()
            it.chartNameLength.clear()
            it.chartFirstChar.clear()
        }
        super.onDestroyView()
        _binding = null
    }
}
