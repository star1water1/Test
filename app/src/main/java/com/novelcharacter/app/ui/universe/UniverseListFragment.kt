package com.novelcharacter.app.ui.universe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentUniverseListBinding
import androidx.recyclerview.widget.ItemTouchHelper
import com.novelcharacter.app.ui.adapter.UniverseAdapter
import com.novelcharacter.app.util.navigateSafe

class UniverseListFragment : Fragment() {

    private var _binding: FragmentUniverseListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UniverseViewModel by viewModels()

    private lateinit var adapter: UniverseAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private var importerInitialized = false
    private val importer by lazy {
        importerInitialized = true
        com.novelcharacter.app.excel.ExcelImporter(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUniverseListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getString("pendingExportFilePath")?.let {
            pendingExportFile = java.io.File(it)
        }
        importer.registerLauncher(this)
        setupRecyclerView()
        setupFab()
        setupToolbarMenu()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = UniverseAdapter(
            onClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.novelListFragment, bundle)
            },
            onLongClick = { universe ->
                showEditDeleteDialog(universe)
            },
            onFieldManageClick = { universe ->
                val bundle = Bundle().apply { putLong("universeId", universe.id) }
                findNavController().navigateSafe(R.id.universeListFragment, R.id.fieldManageFragment, bundle)
            }
        )
        binding.universeRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.universeRecyclerView.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        }
        itemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.universeRecyclerView)
            adapter.itemTouchHelper = it
        }
    }

    private fun setupFab() {
        binding.fabAddUniverse.setOnClickListener {
            showUniverseEditDialog(null)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    exportToExcel()
                    true
                }
                R.id.action_import -> {
                    importFromExcel()
                    true
                }
                R.id.action_preset -> {
                    showPresetDialog()
                    true
                }
                R.id.action_reorder -> {
                    toggleReorderMode()
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

        viewModel.presetApplied.observe(viewLifecycleOwner) { event ->
            event?.getContentIfNotHandled()?.let { name ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.preset_loaded, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun toggleReorderMode() {
        if (adapter.isReorderMode()) {
            // Save and exit reorder mode
            viewModel.updateDisplayOrders(adapter.getReorderedList())
            adapter.setReorderMode(false)
            Toast.makeText(requireContext(), R.string.reorder_saved, Toast.LENGTH_SHORT).show()
        } else {
            adapter.setReorderMode(true)
            Toast.makeText(requireContext(), R.string.reorder_hint, Toast.LENGTH_SHORT).show()
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

    private var exporter: com.novelcharacter.app.excel.ExcelExporter? = null
    private var pendingExportFile: java.io.File? = null

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (!isAdded) return@registerForActivityResult
        val file = pendingExportFile
        if (uri != null && file != null) {
            if (exporter == null) {
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
            }
            exporter?.writeToUri(uri, file)
        }
        pendingExportFile = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExportFile?.absolutePath?.let {
            outState.putString("pendingExportFilePath", it)
        }
    }

    private fun exportToExcel() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_mode_title)
            .setItems(arrayOf(getString(R.string.export_mode_share), getString(R.string.export_mode_save))) { _, which ->
                exporter?.cancel()
                exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext().applicationContext)
                when (which) {
                    0 -> exporter?.exportAll()
                    1 -> exporter?.exportAll { file, fileName ->
                        if (isAdded) {
                            pendingExportFile = file
                            saveFileLauncher.launch(fileName)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importFromExcel() {
        if (!isAdded) return
        importer.showImportDialog(this)
    }

    override fun onDestroyView() {
        binding.universeRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter?.cancel()
        exporter = null
        if (importerInitialized) {
            importer.cleanup()
        }
    }
}
