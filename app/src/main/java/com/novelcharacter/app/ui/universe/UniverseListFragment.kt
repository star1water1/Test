package com.novelcharacter.app.ui.universe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentUniverseListBinding
import com.novelcharacter.app.ui.adapter.UniverseAdapter

class UniverseListFragment : Fragment() {

    private var _binding: FragmentUniverseListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UniverseViewModel by viewModels()

    private lateinit var adapter: UniverseAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUniverseListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = UniverseAdapter(
            onClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigate(R.id.novelListFragment, bundle)
            },
            onLongClick = { universe ->
                showEditDeleteDialog(universe)
            },
            onFieldManageClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigate(R.id.fieldManageFragment, bundle)
            }
        )
        binding.universeRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.universeRecyclerView.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddUniverse.setOnClickListener {
            showUniverseEditDialog(null)
        }
    }

    private fun observeData() {
        viewModel.allUniverses.observe(viewLifecycleOwner) { universes ->
            adapter.submitList(universes)
            binding.emptyText.visibility = if (universes.isEmpty()) View.VISIBLE else View.GONE
            viewModel.loadCounts(universes)
        }

        viewModel.universeNovelCounts.observe(viewLifecycleOwner) { counts ->
            adapter.updateNovelCounts(counts)
        }

        viewModel.universeFieldCounts.observe(viewLifecycleOwner) { counts ->
            adapter.updateFieldCounts(counts)
        }
    }

    private fun showUniverseEditDialog(universe: Universe?) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val nameEdit = EditText(requireContext()).apply {
            hint = "세계관 이름"
            universe?.let { setText(it.name) }
        }
        val descEdit = EditText(requireContext()).apply {
            hint = "설명 (선택)"
            universe?.let { setText(it.description) }
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        AlertDialog.Builder(requireContext())
            .setTitle(if (universe == null) "세계관 추가" else "세계관 편집")
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameEdit.text.toString().trim()
                val desc = descEdit.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (universe == null) {
                        viewModel.insertUniverse(Universe(name = name, description = desc))
                    } else {
                        viewModel.updateUniverse(universe.copy(name = name, description = desc))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditDeleteDialog(universe: Universe) {
        AlertDialog.Builder(requireContext())
            .setTitle(universe.name)
            .setItems(arrayOf("편집", "삭제")) { _, which ->
                when (which) {
                    0 -> showUniverseEditDialog(universe)
                    1 -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage("\"${universe.name}\" 세계관을 삭제하시겠습니까?\n관련 필드 정의도 함께 삭제됩니다.")
                            .setPositiveButton(R.string.yes) { _, _ ->
                                viewModel.deleteUniverse(universe)
                            }
                            .setNegativeButton(R.string.no, null)
                            .show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
