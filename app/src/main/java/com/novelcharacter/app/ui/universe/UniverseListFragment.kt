package com.novelcharacter.app.ui.universe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
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
        setupToolbarMenu()
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

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_preset -> {
                    showPresetDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun showPresetDialog() {
        val templates = viewModel.getPresetTemplates()
        val names = templates.map { "${it.universe.name} — ${it.universe.description}" }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_preset)
            .setItems(names) { _, which ->
                val template = templates[which]
                viewModel.applyPreset(template)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

        viewModel.presetApplied.observe(viewLifecycleOwner) { name ->
            if (name != null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_loaded, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showUniverseEditDialog(universe: Universe?) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }
        val nameEdit = EditText(requireContext()).apply {
            hint = getString(R.string.universe_name_hint)
            universe?.let { setText(it.name) }
        }
        val descEdit = EditText(requireContext()).apply {
            hint = getString(R.string.universe_desc_hint)
            universe?.let { setText(it.description) }
        }
        layout.addView(nameEdit)
        layout.addView(descEdit)

        AlertDialog.Builder(requireContext())
            .setTitle(if (universe == null) R.string.add_universe else R.string.edit_universe)
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
            .setItems(arrayOf(getString(R.string.menu_edit), getString(R.string.menu_delete))) { _, which ->
                when (which) {
                    0 -> showUniverseEditDialog(universe)
                    1 -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.confirm_delete_universe, universe.name))
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
