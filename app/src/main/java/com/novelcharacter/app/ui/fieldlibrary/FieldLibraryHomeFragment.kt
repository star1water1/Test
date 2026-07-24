package com.novelcharacter.app.ui.fieldlibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentFieldLibraryHomeBinding
import com.novelcharacter.app.databinding.ItemLibraryFieldBinding
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.launch

/**
 * 필드 데이터 라이브러리 홈 — 세계관/대상(캐릭터·사건)별 필드 목록과 값 카탈로그 요약.
 * 값 자체는 로드하지 않고 countByField 1쿼리 요약만 표시한다 (받쳐주는 확장성).
 * 미지원 타입 필드는 숨기지 않고 사유와 함께 하단 섹션에 표기한다 (원칙 04 — 숨은 데이터 없음).
 */
class FieldLibraryHomeFragment : Fragment() {

    private var _binding: FragmentFieldLibraryHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FieldValueLibraryViewModel by viewModels()

    private var universes: List<Universe> = emptyList()
    private var selectedUniverseId: Long = -1L
    private var entityType: String = FieldDefinition.ENTITY_CHARACTER
    private lateinit var adapter: FieldRowAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFieldLibraryHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        selectedUniverseId = arguments?.getLong("universeId", -1L) ?: -1L

        adapter = FieldRowAdapter { row ->
            if (row.supported) {
                findNavController().navigateSafe(
                    R.id.fieldLibraryHomeFragment,
                    R.id.fieldValueListFragment,
                    bundleOf("fieldDefinitionId" to row.field.id)
                )
            }
        }
        binding.fieldRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.fieldRecyclerView.adapter = adapter

        binding.entityTypeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            entityType = if (R.id.chipEventFields in checkedIds) {
                FieldDefinition.ENTITY_EVENT
            } else {
                FieldDefinition.ENTITY_CHARACTER
            }
            reload()
        }

        setupUniverseSpinner()
    }

    override fun onResume() {
        super.onResume()
        // 값 목록에서 병합/삭제 후 돌아왔을 때 요약 갱신
        if (universes.isNotEmpty()) reload()
    }

    private fun setupUniverseSpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            universes = viewModel.loadUniverses()
            val labels = listOf(getString(R.string.field_library_all_universes)) + universes.map { it.name }
            binding.universeSpinner.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, labels
            )
            val initialIndex = universes.indexOfFirst { it.id == selectedUniverseId }
                .let { if (it >= 0) it + 1 else 0 }
            binding.universeSpinner.setSelection(initialIndex, false)
            binding.universeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    selectedUniverseId = if (position == 0) -1L else universes[position - 1].id
                    reload()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            reload()
        }
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = viewModel.loadFieldRows(selectedUniverseId, entityType)
            if (_binding == null) return@launch
            // 지원 필드 먼저, 미지원 필드는 섹션 헤더 뒤에
            val supported = rows.filter { it.supported }
            val unsupported = rows.filter { !it.supported }
            val items = buildList {
                supported.forEach { add(ListItem.Row(it)) }
                if (unsupported.isNotEmpty()) {
                    add(ListItem.Header(getString(R.string.field_library_unsupported_section)))
                    unsupported.forEach { add(ListItem.Row(it)) }
                }
            }
            adapter.submit(items)
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    sealed class ListItem {
        data class Row(val row: FieldValueLibraryViewModel.FieldRow) : ListItem()
        data class Header(val title: String) : ListItem()
    }

    private inner class FieldRowAdapter(
        private val onClick: (FieldValueLibraryViewModel.FieldRow) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<ListItem>()

        fun submit(newItems: List<ListItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = if (items[position] is ListItem.Header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                val tv = android.widget.TextView(parent.context).apply {
                    setPadding(dp(16), dp(16), dp(16), dp(4))
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    textSize = 13f
                }
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                val b = ItemLibraryFieldBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                object : RecyclerView.ViewHolder(b.root) {}
            }
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> (holder.itemView as android.widget.TextView).text = item.title
                is ListItem.Row -> {
                    val b = ItemLibraryFieldBinding.bind(holder.itemView)
                    val row = item.row
                    b.fieldNameText.text = if (selectedUniverseId == -1L && row.universeName.isNotBlank()) {
                        "${row.field.name} · ${row.universeName}"
                    } else {
                        row.field.name
                    }
                    b.fieldTypeText.text = FieldType.fromName(row.field.type)?.label ?: row.field.type
                    b.fieldSummaryText.text = when {
                        !row.supported -> getString(row.unsupportedReasonRes)
                        row.entryCount == 0 -> getString(R.string.field_library_field_summary_empty)
                        else -> getString(
                            R.string.field_library_field_summary,
                            row.entryCount, row.uncategorizedCount, row.unusedCount
                        )
                    }
                    b.root.alpha = if (row.supported) 1f else 0.55f
                    b.root.setOnClickListener { onClick(row) }
                }
            }
        }

        private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    }
}
