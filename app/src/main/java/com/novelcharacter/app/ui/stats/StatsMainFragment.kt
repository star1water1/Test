package com.novelcharacter.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsMainBinding
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsMainFragment : Fragment() {

    private var _binding: FragmentStatsMainBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadStats()
    }

    private fun loadStats() {
        binding.loadingProgress.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as NovelCharacterApp
            val provider = StatsDataProvider(app)

            val snapshot = withContext(Dispatchers.IO) { provider.loadSnapshot() }
            val summary = provider.computeSummary(snapshot)
            val charStats = withContext(Dispatchers.IO) { provider.computeCharacterStats(snapshot) }
            val eventStats = withContext(Dispatchers.IO) { provider.computeEventStats(snapshot) }
            val relStats = withContext(Dispatchers.IO) { provider.computeRelationshipStats(snapshot) }
            val nameStats = withContext(Dispatchers.IO) { provider.computeNameBankStats(snapshot) }
            val healthStats = withContext(Dispatchers.IO) { provider.computeDataHealth(snapshot) }

            if (!isAdded) return@launch

            binding.loadingProgress.visibility = View.GONE
            binding.contentLayout.visibility = View.VISIBLE

            // 전체 요약 카드
            binding.summaryCharCount.text = summary.totalCharacters.toString()
            binding.summaryEventCount.text = summary.totalEvents.toString()
            binding.summaryRelCount.text = summary.totalRelationships.toString()
            binding.summaryNovelCount.text = summary.totalNovels.toString()
            binding.summaryUniverseCount.text = summary.totalUniverses.toString()
            binding.summaryNameCount.text = summary.totalNames.toString()

            // 캐릭터 분석 카드 — 미리보기
            val topTag = charStats.tagDistribution.entries.firstOrNull()
            binding.charPreview.text = buildString {
                append("태그 ${charStats.tagDistribution.size}종")
                if (topTag != null) append(" | 최다: ${topTag.key}(${topTag.value})")
            }
            binding.cardCharacters.setOnClickListener {
                findNavController().navigateSafe(
                    R.id.statsMainFragment, R.id.statsCharacterDetailFragment, null
                )
            }

            // 사건 분석 카드
            binding.eventPreview.text = buildString {
                append("연도 밀도 ${eventStats.yearDensity.size}개")
                append(" | 고아사건 ${eventStats.orphanEventCount}건")
            }
            binding.cardEvents.setOnClickListener {
                findNavController().navigateSafe(
                    R.id.statsMainFragment, R.id.statsEventDetailFragment, null
                )
            }

            // 관계 분석 카드
            binding.relPreview.text = buildString {
                append("유형 ${relStats.typeDistribution.size}종")
                append(" | 고립 ${relStats.isolatedCharacters.size}명")
            }
            binding.cardRelationships.setOnClickListener {
                findNavController().navigateSafe(
                    R.id.statsMainFragment, R.id.statsRelationshipDetailFragment, null
                )
            }

            // 이름뱅크 카드
            binding.namePreview.text = buildString {
                append("사용률 ${String.format("%.0f", nameStats.usageRate)}%")
                append(" (${nameStats.usedNames}/${nameStats.totalNames})")
            }
            binding.cardNameBank.setOnClickListener {
                findNavController().navigateSafe(
                    R.id.statsMainFragment, R.id.statsNameBankDetailFragment, null
                )
            }

            // 데이터 건강도 카드
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
            binding.cardDataHealth.setOnClickListener {
                findNavController().navigateSafe(
                    R.id.statsMainFragment, R.id.statsDataHealthDetailFragment, null
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
