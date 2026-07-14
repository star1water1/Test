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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetImageDetailBinding
import com.novelcharacter.app.databinding.FragmentImageManagerBinding
import com.novelcharacter.app.util.ImageImportHelper
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.StorageAnalyzer
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.reportAndNotify

/**
 * 이미지 관리 탭 — 앱 내 모든 이미지(캐릭터·작품·세계관)를 그리드로 조회/필터/정렬하고,
 * 상세·전체화면 보기·삭제(고아 + 참조본 안전삭제)·고아 일괄 정리·용량 요약을 제공한다.
 *
 * PR-3a: 다중선택 일괄(삭제/재압축) + 기존 이미지 재압축(정확한 전/후 크기 미리보기 + 스킵 사유 고지 + 확인).
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

    private var selectionMode = false
    private val selectedPaths = LinkedHashSet<String>()
    private var currentList: List<ImageManagerViewModel.ManagedImage> = emptyList()

    private lateinit var adapter: ImageManagerAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ImageManagerAdapter(
            viewLifecycleOwner.lifecycleScope,
            onClick = { showDetail(it) },
            onToggleSelect = { toggleSelect(it) },
            onLongPress = { enterSelection(it) }
        )
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
        binding.selectButton.setOnClickListener { if (selectionMode) exitSelection() else enterSelection(null) }
        binding.selectAllButton.setOnClickListener { selectAll() }
        binding.recompressSelectedButton.setOnClickListener { recompressSelected() }
        binding.deleteSelectedButton.setOnClickListener { deleteSelected() }

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

        updateSelectionUi()
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
        currentList = sorted
        // 사라진 이미지가 선택에 남아 있지 않게 정리
        val livePaths = all.mapTo(HashSet()) { it.path }
        if (selectedPaths.retainAll(livePaths)) updateSelectionUi()
        adapter.submitList(sorted)
        val empty = sorted.isEmpty() && viewModel.loading.value != true
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ---------- 선택 모드 ----------

    private fun enterSelection(initial: ImageManagerViewModel.ManagedImage?) {
        selectionMode = true
        if (initial != null) selectedPaths.add(initial.path)
        updateSelectionUi()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedPaths.clear()
        updateSelectionUi()
    }

    private fun toggleSelect(item: ImageManagerViewModel.ManagedImage) {
        if (!selectedPaths.add(item.path)) selectedPaths.remove(item.path)
        updateSelectionUi()
    }

    private fun selectAll() {
        val allSelected = currentList.isNotEmpty() && selectedPaths.containsAll(currentList.map { it.path })
        if (allSelected) {
            currentList.forEach { selectedPaths.remove(it.path) }
        } else {
            currentList.forEach { selectedPaths.add(it.path) }
        }
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        binding.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.selectButton.text = getString(
            if (selectionMode) R.string.image_manager_select_cancel else R.string.image_manager_select
        )
        binding.selectionCountText.text = getString(R.string.image_manager_selected_count, selectedPaths.size)
        adapter.setSelectionState(selectionMode, selectedPaths.toSet())
    }

    /** 선택된 경로에 해당하는 현재 이미지 항목들. */
    private fun selectedItems(): List<ImageManagerViewModel.ManagedImage> {
        val all = viewModel.images.value ?: return emptyList()
        return all.filter { selectedPaths.contains(it.path) }
    }

    // ---------- 정렬/옵션 ----------

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

    // ---------- 상세(바텀시트) ----------

    private fun showDetail(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        val sheetBinding = BottomSheetImageDetailBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(ctx)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.detailSizeText.text = StorageAnalyzer.formatBytes(item.sizeBytes)
        sheetBinding.detailOwnerText.text = if (item.owners.isEmpty()) {
            if (item.status == ImageManagerViewModel.Status.TRASH_HELD) {
                getString(R.string.image_manager_owner_trash)
            } else getString(R.string.image_manager_owner_orphan)
        } else {
            item.owners.joinToString("\n") { "${typeLabel(it.type)} · ${it.name}" }
        }

        // 재압축은 참조본에만 의미가 있음(고아/휴지통은 스킵됨) → 참조본에서만 노출
        sheetBinding.detailRecompressButton.visibility =
            if (item.status == ImageManagerViewModel.Status.REFERENCED) View.VISIBLE else View.GONE

        sheetBinding.detailFullScreenButton.setOnClickListener { dialog.dismiss(); openFullScreen(item) }
        sheetBinding.detailRecompressButton.setOnClickListener { dialog.dismiss(); startRecompress(listOf(item)) }
        sheetBinding.detailDeleteButton.setOnClickListener { dialog.dismiss(); confirmDelete(item) }
        sheetBinding.detailCloseButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun openFullScreen(item: ImageManagerViewModel.ManagedImage) {
        val json = gson.toJson(listOf(item.path))
        findNavController().navigateSafe(
            R.id.imageViewerFragment,
            bundleOf("imagePaths" to json, "startPosition" to 0)
        )
    }

    // ---------- 삭제(개별/일괄) ----------

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

    private fun deleteSelected() {
        val ctx = context ?: return
        val items = selectedItems()
        if (items.isEmpty()) {
            reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_select_none)))
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.image_manager_delete_title)
            .setMessage(getString(R.string.image_manager_delete_selected_confirm, items.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteImages(items) { result ->
                    // isAdded는 onDestroyView 후에도 true라 회전/백스택 중 콜백이 파괴된 뷰에 닿는다 →
                    // _binding까지 확인해야 exitSelection()의 binding!! NPE를 막는다(ViewModel이 프래그먼트 스코프라 코루틴 생존).
                    if (!isAdded || _binding == null) return@deleteImages
                    exitSelection()
                    if (result.failed > 0) {
                        reportAndNotify(OpResult.failure(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_bulk_delete_failed, result.failed)
                        ))
                    } else {
                        reportAndNotify(OpResult.success(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_bulk_deleted, result.deleted, StorageAnalyzer.formatBytes(result.freed))
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 재압축(개별/일괄) ----------

    private fun recompressSelected() {
        val items = selectedItems()
        if (items.isEmpty()) {
            reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_select_none)))
            return
        }
        startRecompress(items)
    }

    /** 대상들을 준비(임시 재인코드)한 뒤, 정확한 전/후 크기·스킵 사유를 담은 확인 다이얼로그를 띄운다. */
    private fun startRecompress(items: List<ImageManagerViewModel.ManagedImage>) {
        viewModel.prepareRecompress(items) { preview ->
            if (!isAdded) { viewModel.discardRecompress(); return@prepareRecompress }
            showRecompressConfirm(preview)
        }
    }

    private fun showRecompressConfirm(preview: ImageManagerViewModel.RecompressPreview) {
        val ctx = context ?: run { viewModel.discardRecompress(); return }
        val skipSummary = skipSummary(preview.skips)

        if (preview.plans.isEmpty()) {
            val msg = buildString {
                append(getString(R.string.image_manager_recompress_none))
                if (skipSummary != null) append("\n").append(skipSummary)
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.image_manager_recompress_confirm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.confirm) { _, _ -> viewModel.discardRecompress() }
                .setOnCancelListener { viewModel.discardRecompress() }
                .show()
            return
        }

        val msg = buildString {
            append(getString(
                R.string.image_manager_recompress_confirm,
                preview.plans.size,
                StorageAnalyzer.formatBytes(preview.totalBefore),
                StorageAnalyzer.formatBytes(preview.totalAfter),
                StorageAnalyzer.formatBytes(preview.savings)
            ))
            if (skipSummary != null) append("\n\n").append(skipSummary)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.image_manager_recompress_confirm_title)
            .setMessage(msg)
            .setPositiveButton(R.string.image_manager_recompress) { _, _ ->
                viewModel.commitRecompress { result ->
                    // onDestroyView 후에도 isAdded==true인 창(회전/백스택)에서 파괴된 뷰 접근 방지 — _binding까지 확인.
                    if (!isAdded || _binding == null) return@commitRecompress
                    exitSelection()
                    val base = if (result.skipped > 0) {
                        getString(
                            R.string.image_manager_recompress_done_skipped,
                            result.recompressed, StorageAnalyzer.formatBytes(result.freed), result.skipped
                        )
                    } else {
                        getString(
                            R.string.image_manager_recompress_done,
                            result.recompressed, StorageAnalyzer.formatBytes(result.freed)
                        )
                    }
                    // 커밋 실패분이 있으면 조용히 넘기지 않고 함께 고지(변수 제어). 전량 실패면 실패로 통보.
                    val text = if (result.failed > 0) {
                        base + " " + getString(R.string.image_manager_recompress_failed_suffix, result.failed)
                    } else base
                    val op = if (result.failed > 0 && result.recompressed == 0) {
                        OpResult.failure(OpResult.CAT_MAINTENANCE, text)
                    } else {
                        OpResult.success(OpResult.CAT_MAINTENANCE, text)
                    }
                    reportAndNotify(op)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.discardRecompress() }
            .setOnCancelListener { viewModel.discardRecompress() }
            .show()
    }

    /** 스킵 내역을 사유별 개수로 요약. 없으면 null. */
    private fun skipSummary(skips: List<ImageManagerViewModel.RecompressSkip>): String? {
        if (skips.isEmpty()) return null
        val parts = skips.groupingBy { it.reason }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { "${skipReasonLabel(it.key)} ${it.value}" }
        return getString(R.string.image_manager_recompress_skip_summary, skips.size, parts.joinToString(" · "))
    }

    private fun skipReasonLabel(reason: ImageImportHelper.SkipReason): String = getString(
        when (reason) {
            ImageImportHelper.SkipReason.NOT_REFERENCED -> R.string.image_manager_skip_not_referenced
            ImageImportHelper.SkipReason.TOO_SMALL -> R.string.image_manager_skip_too_small
            ImageImportHelper.SkipReason.TOO_LARGE -> R.string.image_manager_skip_too_large
            ImageImportHelper.SkipReason.CORRUPT -> R.string.image_manager_skip_corrupt
            ImageImportHelper.SkipReason.NO_BENEFIT -> R.string.image_manager_skip_no_benefit
            ImageImportHelper.SkipReason.ERROR -> R.string.image_manager_skip_error
        }
    )

    // ---------- 고아 정리 ----------

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
