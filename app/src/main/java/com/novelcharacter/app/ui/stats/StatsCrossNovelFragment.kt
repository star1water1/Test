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
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.card.MaterialCardView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsCrossNovelBinding

class StatsCrossNovelFragment : Fragment() {

    private var _binding: FragmentStatsCrossNovelBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsCrossNovelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupObservers()
        viewModel.loadCrossNovelComparison()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(context ?: return@observe, R.string.stats_load_error, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.crossNovelComparison.observe(viewLifecycleOwner) { comparison ->
            populateNovelCards(comparison)
        }
    }

    private fun populateNovelCards(comparison: CrossNovelComparison) {
        val container = binding.novelCardsContainer
        container.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val marginMd = resources.getDimensionPixelSize(R.dimen.stats_margin_md)
        val paddingCard = resources.getDimensionPixelSize(R.dimen.stats_padding_card)
        val textSizeHeading = resources.getDimension(R.dimen.stats_text_heading) / resources.displayMetrics.scaledDensity
        val textSizeBody = resources.getDimension(R.dimen.stats_text_body) / resources.displayMetrics.scaledDensity
        val textSizeBodySm = resources.getDimension(R.dimen.stats_text_body_sm) / resources.displayMetrics.scaledDensity
        val cornerRadius = resources.getDimension(R.dimen.stats_card_corner_radius)
        val elevation = resources.getDimension(R.dimen.stats_card_elevation)

        // 캐릭터 수 비교 막대 차트
        if (comparison.novels.size >= 2) {
            val chartCard = createChartCard(comparison, cornerRadius, elevation, paddingCard, marginMd)
            container.addView(chartCard)
        }

        // 각 작품별 카드
        comparison.novels.forEach { entry ->
            val card = MaterialCardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = marginMd }
                radius = cornerRadius
                cardElevation = elevation
            }

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(paddingCard, paddingCard, paddingCard, paddingCard)
            }

            // 작품 제목
            content.addView(TextView(ctx).apply {
                text = entry.novelTitle
                textSize = textSizeHeading
                setTextColor(ContextCompat.getColor(ctx, R.color.primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            // 기본 수치
            val statsLine = buildString {
                append(getString(R.string.stats_cross_novel_char_count, entry.characterCount))
                append(" · ")
                append(getString(R.string.stats_cross_novel_event_count, entry.eventCount))
                append(" · ")
                append(getString(R.string.stats_cross_novel_rel_count, entry.relationshipCount))
            }
            content.addView(TextView(ctx).apply {
                text = statsLine
                textSize = textSizeBody
                setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                setPadding(0, (4 * density).toInt(), 0, 0)
            })

            // 평균 복잡도
            content.addView(TextView(ctx).apply {
                text = getString(R.string.stats_cross_novel_avg_complexity, entry.avgComplexity)
                textSize = textSizeBodySm
                setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                setPadding(0, (2 * density).toInt(), 0, 0)
            })

            // 특화 유형
            if (entry.specializationDist.isNotEmpty()) {
                val specText = entry.specializationDist.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} ${it.value}" }
                content.addView(TextView(ctx).apply {
                    text = getString(R.string.stats_cross_novel_specialization, specText)
                    textSize = textSizeBodySm
                    setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }

            // 주요 필드 값
            if (entry.topFieldValues.isNotEmpty()) {
                val valText = entry.topFieldValues
                    .joinToString(", ") { "${it.first}(${it.second})" }
                content.addView(TextView(ctx).apply {
                    text = getString(R.string.stats_cross_novel_top_values, valText)
                    textSize = textSizeBodySm
                    setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }

            card.addView(content)
            container.addView(card)
        }
    }

    private fun createChartCard(
        comparison: CrossNovelComparison,
        cornerRadius: Float,
        elevation: Float,
        paddingCard: Int,
        marginMd: Int
    ): MaterialCardView {
        val ctx = requireContext()
        val chartValueSize = resources.getDimension(R.dimen.stats_text_chart_value) / resources.displayMetrics.scaledDensity
        val chartHeight = resources.getDimensionPixelSize(R.dimen.stats_chart_height)

        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = marginMd }
            radius = cornerRadius
            cardElevation = elevation
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingCard, paddingCard, paddingCard, paddingCard)
        }

        val chart = HorizontalBarChart(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                chartHeight
            )
        }

        val labels = comparison.novels.map { it.novelTitle }
        val entries = comparison.novels.mapIndexed { i, n ->
            BarEntry(i.toFloat(), n.characterCount.toFloat())
        }
        val dataSet = BarDataSet(entries, "").apply {
            colors = chartColors()
            valueTextSize = chartValueSize
            valueTextColor = ContextCompat.getColor(ctx, R.color.on_surface)
        }
        chart.apply {
            data = BarData(dataSet)
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

        content.addView(chart)
        card.addView(content)
        return card
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
        super.onDestroyView()
        _binding = null
    }
}
