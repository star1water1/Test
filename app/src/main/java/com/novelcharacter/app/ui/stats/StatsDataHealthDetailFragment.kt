package com.novelcharacter.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
        items.sortedBy { it.second }.forEach { (name, rate) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 8
                layoutParams = lp
            }
            row.addView(makeTextView("$name  ${String.format("%.0f", rate)}%"))
            val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    8
                )
                max = 100
                this.progress = rate.toInt().coerceIn(0, 100)
            }
            row.addView(progress)
            container.addView(row)
        }
    }

    private fun makeTextView(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 4
            layoutParams = lp
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
