package com.novelcharacter.app.ui.stats

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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentStatsDataHealthDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsDataHealthDetailFragment : Fragment() {

    private var _binding: FragmentStatsDataHealthDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsDataHealthDetailBinding.inflate(inflater, container, false)
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
                val stats = withContext(Dispatchers.IO) { provider.computeDataHealth(snapshot) }

                if (!isAdded) return@launch

                binding.loadingProgress.visibility = View.GONE
                binding.contentLayout.visibility = View.VISIBLE

                // No-image characters
                binding.textNoImageCount.text = "${stats.noImageChars.size}명"
                populateSimpleList(binding.listNoImage, stats.noImageChars)

                // Incomplete fields
                populateIncompleteList(binding.listIncompleteFields, stats.incompleteFieldChars)

                // Isolated characters
                populateSimpleList(binding.listIsolated, stats.isolatedChars)

                // Unlinked characters
                populateSimpleList(binding.listUnlinked, stats.unlinkedChars)

                // Duplicate tags
                populateSimpleList(binding.listDuplicateTags, stats.duplicateTags)
            } catch (e: Exception) {
                if (isAdded) {
                    binding.loadingProgress.visibility = View.GONE
                    Toast.makeText(requireContext(), R.string.stats_load_error, Toast.LENGTH_SHORT).show()
                }
            }
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

    private fun populateIncompleteList(container: LinearLayout, items: List<Pair<String, Float>>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(makeTextView("--"))
            return
        }
        val marginSm = resources.getDimensionPixelSize(R.dimen.stats_margin_sm)
        val progressHeight = resources.getDimensionPixelSize(R.dimen.stats_progress_bar_height_sm)
        items.sortedBy { it.second }.forEach { (name, rate) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = marginSm
                layoutParams = lp
            }
            row.addView(makeTextView("$name  ${String.format("%.0f", rate)}%"))
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
        super.onDestroyView()
        _binding = null
    }
}
