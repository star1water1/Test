package com.novelcharacter.app.ui.stats

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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsRelationshipDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsRelationshipDetailFragment : Fragment() {

    private var _binding: FragmentStatsRelationshipDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsRelationshipDetailBinding.inflate(inflater, container, false)
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
                val stats = withContext(Dispatchers.IO) { provider.computeRelationshipStats(snapshot) }

                if (!isAdded) return@launch

                binding.loadingProgress.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE

                setupTypePieChart(stats.typeDistribution)
                populateRankedList(binding.listTopConnected, stats.topConnectedChars)
                populateSimpleList(binding.listIsolated, stats.isolatedCharacters)
            } catch (e: Exception) {
                if (isAdded) {
                    binding.loadingProgress.visibility = View.GONE
                    Toast.makeText(requireContext(), R.string.stats_load_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupTypePieChart(data: Map<String, Int>) {
        val chart = binding.chartTypeDist
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

    private fun populateRankedList(container: LinearLayout, items: List<Pair<String, Int>>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeTextView("--"))
            return
        }
        items.forEachIndexed { index, (name, count) ->
            container.addView(makeTextView("${index + 1}. $name ($count)"))
        }
    }

    private fun populateSimpleList(container: LinearLayout, items: List<String>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeTextView("--"))
            return
        }
        items.forEach { name ->
            container.addView(makeTextView(name))
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
        _binding?.chartTypeDist?.clear()
        super.onDestroyView()
        _binding = null
    }
}
