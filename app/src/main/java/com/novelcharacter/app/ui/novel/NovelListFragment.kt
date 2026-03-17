package com.novelcharacter.app.ui.novel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.DialogNovelEditBinding
import com.novelcharacter.app.databinding.FragmentNovelListBinding
import com.novelcharacter.app.ui.adapter.NovelAdapter
import com.novelcharacter.app.util.navigateSafe

class NovelListFragment : Fragment() {

    private var _binding: FragmentNovelListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NovelViewModel by viewModels()

    private lateinit var adapter: NovelAdapter
    private var universeId: Long = -1L
    private var importerInitialized = false
    private val importer by lazy {
        importerInitialized = true
        com.novelcharacter.app.excel.ExcelImporter(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNovelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.getString("pendingExportFilePath")?.let {
            pendingExportFile = java.io.File(it)
        }

        importer.registerLauncher(this)
        universeId = arguments?.getLong("universeId", -1L) ?: -1L
        viewModel.setUniverseFilter(universeId)

        setupRecyclerView()
        setupFab()
        setupToolbarMenu()
        observeData()

        if (universeId != -1L) {
            binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        }
    }

    private fun setupRecyclerView() {
        adapter = NovelAdapter(
            onClick = { novel ->
                val bundle = Bundle().apply { putLong("novelId", novel.id) }
                findNavController().navigateSafe(R.id.novelListFragment, R.id.characterListFragment, bundle)
            },
            onLongClick = { novel ->
                showEditDeleteDialog(novel)
            }
        )
        binding.novelRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.novelRecyclerView.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddNovel.setOnClickListener {
            showNovelEditDialog(null)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_global_search -> {
                    findNavController().navigateSafe(R.id.novelListFragment, R.id.globalSearchFragment)
                    true
                }
                R.id.action_export -> {
                    exportToExcel()
                    true
                }
                R.id.action_import -> {
                    importFromExcel()
                    true
                }
                else -> false
            }
        }
    }

    private fun observeData() {
        viewModel.filteredNovels.observe(viewLifecycleOwner) { novels ->
            adapter.submitList(novels)
            binding.emptyText.visibility = if (novels.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showNovelEditDialog(novel: Novel?) {
        val dialogBinding = DialogNovelEditBinding.inflate(layoutInflater)
        novel?.let {
            dialogBinding.editTitle.setText(it.title)
            dialogBinding.editDescription.setText(it.description)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(if (novel == null) R.string.add_novel else R.string.edit_novel)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = dialogBinding.editTitle.text.toString().trim()
                val description = dialogBinding.editDescription.text.toString().trim()
                if (title.isNotEmpty()) {
                    if (novel == null) {
                        val newNovel = Novel(
                            title = title,
                            description = description,
                            universeId = if (universeId != -1L) universeId else null
                        )
                        viewModel.insertNovel(newNovel)
                    } else {
                        viewModel.updateNovel(novel.copy(title = title, description = description))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditDeleteDialog(novel: Novel) {
        AlertDialog.Builder(requireContext())
            .setTitle(novel.title)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> showNovelEditDialog(novel)
                    1 -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage(R.string.confirm_delete)
                            .setPositiveButton(R.string.yes) { _, _ ->
                                viewModel.deleteNovel(novel)
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
        binding.novelRecyclerView.adapter = null
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
