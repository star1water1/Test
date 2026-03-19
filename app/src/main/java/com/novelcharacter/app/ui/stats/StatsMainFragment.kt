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
                Toast.makeText(requireContext(), R.string.stats_load_error, Toast.LENGTH_SHORT).show()
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
                append("태그 ${charStats.tagDistribution.size}종")
                if (topTag != null) append(" | 최다: ${topTag.key}(${topTag.value})")
            }
        }

        viewModel.eventStats.observe(viewLifecycleOwner) { eventStats ->
            binding.eventPreview.text = buildString {
                append("연도 밀도 ${eventStats.yearDensity.size}개")
                append(" | 고아사건 ${eventStats.orphanEventCount}건")
            }
        }

        viewModel.relationshipStats.observe(viewLifecycleOwner) { relStats ->
            binding.relPreview.text = buildString {
                append("유형 ${relStats.typeDistribution.size}종")
                append(" | 고립 ${relStats.isolatedCharacters.size}명")
            }
        }

        viewModel.nameBankStats.observe(viewLifecycleOwner) { nameStats ->
            binding.namePreview.text = buildString {
                append("사용률 ${String.format("%.0f", nameStats.usageRate)}%")
                append(" (${nameStats.usedNames}/${nameStats.totalNames})")
            }
        }

        viewModel.dataHealthStats.observe(viewLifecycleOwner) { healthStats ->
            val totalIssues = healthStats.noImageChars.size +
                healthStats.incompleteFieldChars.size +
                healthStats.isolatedChars.size +
                healthStats.unlinkedChars.size
            binding.healthPreview.text = buildString {
                if (totalIssues == 0) append("문제 없음")
                else {
                    append("발견 사항 ${totalIssues}건")
                    if (healthStats.noImageChars.isNotEmpty()) append(" | 이미지없음 ${healthStats.noImageChars.size}")
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
