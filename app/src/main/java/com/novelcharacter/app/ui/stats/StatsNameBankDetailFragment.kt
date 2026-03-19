package com.novelcharacter.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsNameBankDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsNameBankDetailFragment : Fragment() {

    private var _binding: FragmentStatsNameBankDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsNameBankDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        loadData()
    }

    private fun loadData() {
        binding.loadingProgress.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val app = requireActivity().application as NovelCharacterApp
                val provider = StatsDataProvider(app)
                val snapshot = withContext(Dispatchers.IO) { provider.loadSnapshot() }
                val stats = withContext(Dispatchers.IO) { provider.computeNameBankStats(snapshot) }

                if (!isAdded) return@launch

                binding.loadingProgress.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE

                // Usage rate
                binding.textUsageRate.text = "${String.format("%.0f", stats.usageRate)}%"
                binding.progressUsageRate.progress = stats.usageRate.toInt().coerceIn(0, 100)

                setupGenderPieChart(stats.genderDistribution)
                setupOriginBarChart(stats.originDistribution)
            } catch (e: Exception) {
                if (isAdded) {
                    binding.loadingProgress.visibility = View.GONE
                    Toast.makeText(requireContext(), R.string.stats_load_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
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

    private fun chartColors(): List<Int> {
        val ctx = requireContext()
        return listOf(
            ContextCompat.getColor(ctx, R.color.primary),
            ContextCompat.getColor(ctx, R.color.accent),
            ContextCompat.getColor(ctx, R.color.search_type_novel),
            ContextCompat.getColor(ctx, R.color.primary_light),
            ContextCompat.getColor(ctx, R.color.primary_dark)
        ) + ColorTemplate.MATERIAL_COLORS.toList()
    }

    override fun onDestroyView() {
        _binding?.let {
            it.chartGenderDist.clear()
            it.chartOriginDist.clear()
        }
        super.onDestroyView()
        _binding = null
    }
}
