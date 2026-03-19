package com.novelcharacter.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsMainBinding
import com.novelcharacter.app.util.navigateSafe

class StatsMainFragment : Fragment() {

    private var _binding: FragmentStatsMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        setupClickListeners()
        viewModel.loadAllStats()
    }

    private fun setupObservers() {
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                val ctx = context ?: return@observe
                Toast.makeText(ctx, R.string.stats_load_error, Toast.LENGTH_SHORT).show()
            }
        }

        // 작품 필터 스피너
        viewModel.novelList.observe(viewLifecycleOwner) { novels ->
            val ctx = context ?: return@observe
            val items = mutableListOf(getString(R.string.stats_filter_all))
            items.addAll(novels.map { it.second })
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerNovelFilter.adapter = adapter
            binding.spinnerNovelFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val novelId = if (pos == 0) null else novels[pos - 1].first
                    if (viewModel.selectedNovelId.value != novelId) {
                        viewModel.setNovelFilter(novelId)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        viewModel.summary.observe(viewLifecycleOwner) { summary ->
            binding.summaryCharCount.text = summary.totalCharacters.toString()
            binding.summaryEventCount.text = summary.totalEvents.toString()
            binding.summaryRelCount.text = summary.totalRelationships.toString()
            binding.summaryNovelCount.text = summary.totalNovels.toString()
            binding.summaryUniverseCount.text = summary.totalUniverses.toString()
            binding.summaryNameCount.text = summary.totalNames.toString()

            // 인사이트
            binding.insightMostActive.text = summary.mostActiveNovel?.let {
                getString(R.string.stats_insight_most_active, it)
            } ?: ""
            binding.insightMostActive.visibility = if (summary.mostActiveNovel != null) View.VISIBLE else View.GONE

            binding.insightMostConnected.text = summary.mostConnectedChar?.let {
                getString(R.string.stats_insight_most_connected, it)
            } ?: ""
            binding.insightMostConnected.visibility = if (summary.mostConnectedChar != null) View.VISIBLE else View.GONE

            binding.insightFieldCompletion.text =
                getString(R.string.stats_insight_field_completion, summary.avgFieldCompletion)
            binding.insightFieldCompletion.visibility =
                if (summary.totalCharacters > 0) View.VISIBLE else View.GONE

            binding.insightRecentActivity.text =
                getString(R.string.stats_insight_recent_activity, summary.recentActivityCount)
            binding.insightRecentActivity.visibility =
                if (summary.recentActivityCount > 0) View.VISIBLE else View.GONE

            binding.insightContainer.visibility =
                if (summary.totalCharacters > 0) View.VISIBLE else View.GONE
        }

        viewModel.characterStats.observe(viewLifecycleOwner) { charStats ->
            val topTag = charStats.tagDistribution.entries.firstOrNull()
            binding.charPreview.text = buildString {
                append(getString(R.string.stats_tag_preview, charStats.tagDistribution.size))
                if (topTag != null) append(getString(R.string.stats_tag_top_format, topTag.key, topTag.value))
                if (charStats.complexityScores.isNotEmpty()) {
                    val top = charStats.complexityScores.first()
                    append(" | ${getString(R.string.stats_insight_complex_char, top.name)}")
                }
            }
        }

        viewModel.eventStats.observe(viewLifecycleOwner) { eventStats ->
            binding.eventPreview.text = buildString {
                append(getString(R.string.stats_event_density_preview, eventStats.yearDensity.size))
                append(getString(R.string.stats_orphan_event_preview, eventStats.orphanEventCount))
                if (eventStats.calendarTypeDistribution.size > 1) {
                    append(" | ${getString(R.string.stats_calendar_types, eventStats.calendarTypeDistribution.size)}")
                }
            }
        }

        viewModel.relationshipStats.observe(viewLifecycleOwner) { relStats ->
            binding.relPreview.text = buildString {
                append(getString(R.string.stats_rel_type_preview, relStats.typeDistribution.size))
                append(getString(R.string.stats_isolated_preview, relStats.isolatedCharacters.size))
                append(" | ${getString(R.string.stats_network_density, relStats.networkDensity * 100)}")
            }
        }

        viewModel.nameBankStats.observe(viewLifecycleOwner) { nameStats ->
            binding.namePreview.text = getString(R.string.stats_usage_rate_preview, nameStats.usageRate, nameStats.usedNames, nameStats.totalNames)
        }

        viewModel.dataHealthStats.observe(viewLifecycleOwner) { healthStats ->
            val totalIssues = healthStats.noImageChars.size +
                healthStats.incompleteFieldChars.size +
                healthStats.isolatedChars.size +
                healthStats.unlinkedChars.size +
                healthStats.noMemoChars.size +
                healthStats.emptyDescRelationships
            binding.healthPreview.text = buildString {
                if (totalIssues == 0) append(getString(R.string.stats_health_no_issues))
                else {
                    append(getString(R.string.stats_health_issues_found, totalIssues))
                    if (healthStats.noImageChars.isNotEmpty()) append(getString(R.string.stats_health_no_image, healthStats.noImageChars.size))
                    if (healthStats.lowPrecisionEvents > 0) append(" | ${getString(R.string.stats_low_precision_events, healthStats.lowPrecisionEvents)}")
                }
            }
        }

        // 필드 분석 프리뷰
        viewModel.fieldAnalysisStats.observe(viewLifecycleOwner) { fieldStats ->
            binding.fieldAnalysisPreview.text = buildString {
                append(getString(R.string.stats_field_analysis_preview,
                    fieldStats.fieldValueDistributions.size,
                    fieldStats.numberFieldSummaries.size))
            }
        }
    }

    private fun setupClickListeners() {
        binding.cardCharacters.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsCharacterDetailFragment, null
            )
        }
        binding.cardEvents.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsEventDetailFragment, null
            )
        }
        binding.cardRelationships.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsRelationshipDetailFragment, null
            )
        }
        binding.cardFieldAnalysis.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsFieldAnalysisDetailFragment, null
            )
        }
        binding.cardNameBank.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsNameBankDetailFragment, null
            )
        }
        binding.cardDataHealth.setOnClickListener {
            findNavController().navigateSafe(
                R.id.statsMainFragment, R.id.statsDataHealthDetailFragment, null
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
