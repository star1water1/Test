package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentImageManagerBinding
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.StorageAnalyzer
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.reportAndNotify

/**
 * 이미지 관리 탭 — 앱 내 모든 이미지(캐릭터·작품·세계관)를 그리드로 조회/필터/정렬하고,
 * 전체화면 보기·삭제(고아 + 참조본 안전삭제)·고아 일괄 정리·용량 요약을 제공한다.
 */
class ImageManagerFragment : Fragment() {

    private var _binding: FragmentImageManagerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageManagerViewModel by viewModels()
    private val gson = Gson()

    private enum class Filter { ALL, CHARACTER, NOVEL, UNIVERSE, ORPHAN }
    private enum class Sort { SIZE, NAME, DATE }

    private var filter = Filter.ALL
    private var sort = Sort.SIZE

    private lateinit var adapter: ImageManagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ImageManagerAdapter(viewLifecycleOwner.lifecycleScope) { showDetail(it) }
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chipCharacter -> Filter.CHARACTER
                R.id.chipNovel -> Filter.NOVEL
                R.id.chipUniverse -> Filter.UNIVERSE
                R.id.chipOrphan -> Filter.ORPHAN
                else -> Filter.ALL
            }
            applyView()
        }

        binding.sortButton.setOnClickListener { showSortMenu() }
        binding.optionsButton.setOnClickListener { showOptionsMenu() }

        viewModel.loading.observe(viewLifecycleOwner) {
            binding.progressBar.visibility = if (it) View.VISIBLE else View.GONE
        }
        viewModel.summary.observe(viewLifecycleOwner) { s ->
            binding.summaryText.text = getString(
                R.string.image_manager_summary,
                s.totalCount, StorageAnalyzer.formatBytes(s.totalBytes), s.referencedCount, s.orphanCount
            )
        }
        viewModel.images.observe(viewLifecycleOwner) { applyView() }

        viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        // 편집화면에서 이미지를 추가/삭제하고 돌아왔을 수 있으니 갱신.
        viewModel.load()
    }

    /** 현재 필터·정렬을 적용해 어댑터에 반영. */
    private fun applyView() {
        val all = viewModel.images.value ?: emptyList()
        val filtered = all.filter { item ->
            when (filter) {
                Filter.ALL -> true
                Filter.CHARACTER -> item.owners.any { it.type == ImageManagerViewModel.OwnerType.CHARACTER }
                Filter.NOVEL -> item.owners.any { it.type == ImageManagerViewModel.OwnerType.NOVEL }
                Filter.UNIVERSE -> item.owners.any { it.type == ImageManagerViewModel.OwnerType.UNIVERSE }
                Filter.ORPHAN -> item.status == ImageManagerViewModel.Status.ORPHAN
            }
        }
        val sorted = when (sort) {
            Sort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            Sort.NAME -> filtered.sortedBy { it.path.substringAfterLast('/') }
            Sort.DATE -> filtered.sortedByDescending { it.lastModified }
        }
        adapter.submitList(sorted)
        val empty = sorted.isEmpty() && viewModel.loading.value != true
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.sortButton)
        popup.menu.add(0, 0, 0, R.string.image_manager_sort_size)
        popup.menu.add(0, 1, 1, R.string.image_manager_sort_name)
        popup.menu.add(0, 2, 2, R.string.image_manager_sort_date)
        popup.setOnMenuItemClickListener { mi ->
            sort = when (mi.itemId) { 0 -> Sort.SIZE; 1 -> Sort.NAME; else -> Sort.DATE }
            applyView()
            true
        }
        popup.show()
    }

    private fun showOptionsMenu() {
        val popup = PopupMenu(requireContext(), binding.optionsButton)
        popup.menu.add(0, 0, 0, R.string.settings_image_compress_title)
        popup.menu.add(0, 1, 1, R.string.image_manager_clean_orphans)
        popup.menu.add(0, 2, 2, R.string.image_manager_refresh)
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                0 -> ImageSettingsDialog.show(this) { viewModel.load() }
                1 -> confirmCleanOrphans()
                else -> viewModel.load()
            }
            true
        }
        popup.show()
    }

    private fun showDetail(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        val ownerText = if (item.owners.isEmpty()) {
            if (item.status == ImageManagerViewModel.Status.TRASH_HELD) {
                getString(R.string.image_manager_owner_trash)
            } else getString(R.string.image_manager_owner_orphan)
        } else {
            item.owners.joinToString("\n") { "${typeLabel(it.type)} · ${it.name}" }
        }
        AlertDialog.Builder(ctx)
            .setTitle(StorageAnalyzer.formatBytes(item.sizeBytes))
            .setMessage(ownerText)
            .setNeutralButton(R.string.image_manager_view_full) { _, _ -> openFullScreen(item) }
            .setNegativeButton(R.string.delete) { _, _ -> confirmDelete(item) }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun openFullScreen(item: ImageManagerViewModel.ManagedImage) {
        val json = gson.toJson(listOf(item.path))
        findNavController().navigateSafe(
            R.id.imageViewerFragment,
            bundleOf("imagePaths" to json, "startPosition" to 0)
        )
    }

    private fun confirmDelete(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        val msg = if (item.owners.isNotEmpty()) {
            getString(R.string.image_manager_delete_referenced_confirm, item.owners.size)
        } else {
            getString(R.string.image_manager_delete_orphan_confirm)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.image_manager_delete_title)
            .setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteImage(item) { freed ->
                    if (!isAdded) return@deleteImage
                    if (freed != null) {
                        reportAndNotify(OpResult.success(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_deleted, StorageAnalyzer.formatBytes(freed))
                        ))
                    } else {
                        reportAndNotify(OpResult.failure(
                            OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_delete_failed)
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmCleanOrphans() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.image_manager_clean_orphans)
            .setMessage(R.string.storage_clean_orphan_confirm)
            .setPositiveButton(R.string.storage_clean_run) { _, _ ->
                viewModel.cleanOrphans { result ->
                    if (!isAdded) return@cleanOrphans
                    if (result.aborted) {
                        reportAndNotify(OpResult.failure(
                            OpResult.CAT_MAINTENANCE, getString(R.string.storage_clean_orphan_aborted)
                        ))
                    } else {
                        reportAndNotify(OpResult.success(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.storage_clean_orphan_done, result.deleted, StorageAnalyzer.formatBytes(result.freed))
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun typeLabel(type: ImageManagerViewModel.OwnerType): String = when (type) {
        ImageManagerViewModel.OwnerType.CHARACTER -> getString(R.string.image_manager_type_character)
        ImageManagerViewModel.OwnerType.NOVEL -> getString(R.string.image_manager_type_novel)
        ImageManagerViewModel.OwnerType.UNIVERSE -> getString(R.string.image_manager_type_universe)
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
