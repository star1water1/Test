package com.novelcharacter.app.ui.novel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class NovelListFragment : Fragment() {

    private var _binding: FragmentNovelListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NovelViewModel by viewModels()

    private lateinit var adapter: NovelAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNovelListBinding.inflate(inflater, container, false)
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
        adapter = NovelAdapter(
            onClick = { novel ->
                val bundle = Bundle().apply { putLong("novelId", novel.id) }
                findNavController().navigate(R.id.characterListFragment, bundle)
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
        viewModel.allNovels.observe(viewLifecycleOwner) { novels ->
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
                        viewModel.insertNovel(Novel(title = title, description = description))
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
            .setItems(arrayOf("편집", "삭제")) { _, which ->
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

    private fun exportToExcel() {
        // Excel 내보내기는 ExcelExporter에서 처리
        val exporter = com.novelcharacter.app.excel.ExcelExporter(requireContext())
        exporter.exportAll()
    }

    private fun importFromExcel() {
        val importer = com.novelcharacter.app.excel.ExcelImporter(requireContext())
        importer.showImportDialog(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
