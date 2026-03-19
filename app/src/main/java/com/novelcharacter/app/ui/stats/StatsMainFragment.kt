package com.novelcharacter.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        viewModel.summary.observe(viewLifecycleOwner) { summary ->
            binding.summaryCharCount.text = summary.totalCharacters.toString()
            binding.summaryEventCount.text = summary.totalEvents.toString()
            binding.summaryRelCount.text = summary.totalRelationships.toString()
            binding.summaryNovelCount.text = summary.totalNovels.toString()
            binding.summaryUniverseCount.text = summary.totalUniverses.toString()
            binding.summaryNameCount.text = summary.totalNames.toString()
        }

        viewModel.characterStats.observe(viewLifecycleOwner) { charStats ->
            val topTag = charStats.tagDistribution.entries.firstOrNull()
            binding.charPreview.text = buildString {
                append(getString(R.string.stats_tag_preview, charStats.tagDistribution.size))
                if (topTag != null) append(getString(R.string.stats_tag_top_format, topTag.key, topTag.value))
            }
        }

        viewModel.eventStats.observe(viewLifecycleOwner) { eventStats ->
            binding.eventPreview.text = buildString {
                append(getString(R.string.stats_event_density_preview, eventStats.yearDensity.size))
                append(getString(R.string.stats_orphan_event_preview, eventStats.orphanEventCount))
            }
        }

        viewModel.relationshipStats.observe(viewLifecycleOwner) { relStats ->
            binding.relPreview.text = buildString {
                append(getString(R.string.stats_rel_type_preview, relStats.typeDistribution.size))
                append(getString(R.string.stats_isolated_preview, relStats.isolatedCharacters.size))
            }
        }

        viewModel.nameBankStats.observe(viewLifecycleOwner) { nameStats ->
            binding.namePreview.text = getString(R.string.stats_usage_rate_preview, nameStats.usageRate, nameStats.usedNames, nameStats.totalNames)
        }

        viewModel.dataHealthStats.observe(viewLifecycleOwner) { healthStats ->
            val totalIssues = healthStats.noImageChars.size +
                healthStats.incompleteFieldChars.size +
                healthStats.isolatedChars.size +
                healthStats.unlinkedChars.size
            binding.healthPreview.text = buildString {
                if (totalIssues == 0) append(getString(R.string.stats_health_no_issues))
                else {
                    append(getString(R.string.stats_health_issues_found, totalIssues))
                    if (healthStats.noImageChars.isNotEmpty()) append(getString(R.string.stats_health_no_image, healthStats.noImageChars.size))
                }
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
