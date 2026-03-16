package com.novelcharacter.app.ui.character

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TableRow
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentCharacterCompareBinding
import kotlinx.coroutines.launch

class CharacterCompareFragment : Fragment() {

    private var _binding: FragmentCharacterCompareBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterCompareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val idsStr = arguments?.getString("characterIds")
        if (idsStr.isNullOrEmpty()) {
            findNavController().popBackStack()
            return
        }
        val characterIds = idsStr.split(",").mapNotNull { it.trim().toLongOrNull() }

        if (characterIds.size < 2) {
            findNavController().popBackStack()
            return
        }

        loadComparison(characterIds)
    }

    private data class CompareEntry(
        val name: String,
        val novelTitle: String,
        val fieldValues: Map<String, String>,
        val tags: List<String>
    )

    private fun loadComparison(characterIds: List<Long>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val characters = characterIds.mapNotNull { viewModel.getCharacterByIdSuspend(it) }
            if (characters.size < 2) return@launch

            val entries = mutableListOf<CompareEntry>()
            val allFields = linkedMapOf<String, String>() // key -> display name

            for (char in characters) {
                val novel = char.novelId?.let { viewModel.getNovelById(it) }
                val universeId = novel?.universeId
                val fields = if (universeId != null) viewModel.getFieldsByUniverseList(universeId) else emptyList()
                val values = viewModel.getValuesByCharacterList(char.id)
                val tags = viewModel.getTagsByCharacterList(char.id).map { it.tag }

                val valueMap = mutableMapOf<String, String>()
                for (field in fields.sortedBy { it.displayOrder }) {
                    allFields[field.key] = field.name
                    val v = values.find { it.fieldDefinitionId == field.id }?.value ?: ""
                    valueMap[field.key] = v
                }
                entries.add(CompareEntry(char.name, novel?.title ?: getString(R.string.novel_unassigned), valueMap, tags))
            }

            if (isAdded && _binding != null) {
                buildComparisonTable(entries, allFields)
            }
        }
    }

    private fun buildComparisonTable(
        entries: List<CompareEntry>,
        allFields: Map<String, String>
    ) {
        val table = binding.compareTable
        table.removeAllViews()
        val context = requireContext()
        val density = resources.displayMetrics.density
        val cellPadding = (8 * density).toInt()
        val colWidth = (140 * density).toInt()

        // Header row
        val hRow = TableRow(context)
        hRow.addView(createCell("", true, cellPadding, colWidth))
        for (e in entries) {
            hRow.addView(createCell(e.name, true, cellPadding, colWidth))
        }
        table.addView(hRow)

        // Novel row
        val novelRow = TableRow(context)
        novelRow.addView(createCell(getString(R.string.tab_novels), false, cellPadding, colWidth))
        for (e in entries) {
            novelRow.addView(createCell(e.novelTitle, false, cellPadding, colWidth))
        }
        table.addView(novelRow)

        // Tags row
        val tagRow = TableRow(context)
        tagRow.addView(createCell(getString(R.string.tags), false, cellPadding, colWidth))
        for (e in entries) {
            tagRow.addView(createCell(
                if (e.tags.isNotEmpty()) e.tags.joinToString(", ") else "-",
                false, cellPadding, colWidth
            ))
        }
        table.addView(tagRow)

        // Field rows
        for ((key, name) in allFields) {
            val row = TableRow(context)
            row.addView(createCell(name, false, cellPadding, colWidth))
            for (e in entries) {
                val value = e.fieldValues[key]?.ifBlank { "-" } ?: "-"
                row.addView(createCell(value, false, cellPadding, colWidth))
            }
            table.addView(row)
        }
    }

    private fun createCell(text: String, isHeader: Boolean, padding: Int, width: Int): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER
            minWidth = width
            maxWidth = width
            if (isHeader) {
                setTypeface(null, Typeface.BOLD)
                textSize = 15f
                setTextColor(context.getColor(R.color.primary))
                setBackgroundColor(context.getColor(R.color.primary_light))
            } else {
                textSize = 13f
                setTextColor(context.getColor(R.color.on_surface))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
